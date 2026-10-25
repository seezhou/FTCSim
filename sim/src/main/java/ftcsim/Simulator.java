package ftcsim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.qualcomm.robotcore.eventloop.opmode.SimOpModeRunner;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.util.RobotLog;
import ftcsim.bridge.Configurables;
import ftcsim.bridge.DashboardBridge;
import ftcsim.config.RobotConfig;
import ftcsim.hardware.LauncherController;
import ftcsim.hardware.LauncherSpec;
import ftcsim.hardware.SimHardware;
import ftcsim.log.SimLog;
import ftcsim.log.StdoutCapture;
import ftcsim.physics.*;
import ftcsim.runner.OpModeEntry;
import ftcsim.runner.SimGamepad;
import ftcsim.teamcode.TeamCode;
import ftcsim.teamcode.TeamCompiler;
import ftcsim.teamcode.TeamRepo;
import ftcsim.web.WebServer;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Wires the world, hardware, OpMode runner, team code and web UI together. */
public final class Simulator {
    public static final String TAG = "FTCSim";

    public static final class Options {
        public File repo;
        public int port = 8000;
        public String host = WebServer.DEFAULT_HOST;
        public File config;
        public boolean includeSamples;
        public boolean prebuilt;
        public boolean openBrowser = true;
        public File home = new File(System.getProperty("ftcsim.home", "."));
        public File dataDir;
        public File webOverrideDir;

        /**
         * Explicit relative paths follow the repository root, not the process working directory:
         * {@code gradlew run} starts Java in {@code sim/}, so {@code --config configs/X.json}
         * would otherwise create the config in the wrong place.
         */
        void resolvePaths() {
            config = resolveAgainstHome(config);
            dataDir = resolveAgainstHome(dataDir);
            webOverrideDir = resolveAgainstHome(webOverrideDir);
        }

        private File resolveAgainstHome(File f) {
            if (f == null || f.isAbsolute()) return f;
            return new File(home, f.getPath());
        }
    }

    private final Options opts;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
    private RobotConfig config;
    private File configFile;
    private World world;
    private SimHardware hardware;
    private SimOpModeRunner runner;
    private TeamCode teamCode;
    private WebServer web;
    private final Configurables configurables = new Configurables();
    private volatile int obeliskTag = 21;
    private volatile boolean matchTimerEnabled;
    private volatile long matchDeadline;
    private volatile int poseEpoch;
    private volatile long lastLogSeq;
    private volatile List<OpModeEntry> opModes = new ArrayList<>();
    private volatile long startedAt;
    private LauncherController launcher;
    /** Balls that have gone into a goal since the last INIT. */
    private volatile int ballsScored;

    public Simulator(Options opts) { this.opts = opts; }

    public World world() { return world; }
    public SimHardware hardware() { return hardware; }
    public SimOpModeRunner runner() { return runner; }
    public TeamCode teamCode() { return teamCode; }
    public RobotConfig config() { return config; }
    public WebServer web() { return web; }

    // ------------------------------------------------------------------ startup
    public void start() throws IOException {
        opts.resolvePaths();
        startedAt = System.currentTimeMillis();
        File data = opts.dataDir != null ? opts.dataDir : new File(opts.home, "build/ftcsim-data");
        data.mkdirs();
        System.setProperty("ftcsim.data", data.getAbsolutePath());
        android.content.Context.setFilesDir(new File(data, "files"));
        StdoutCapture.install();
        android.util.Log.setSink((priority, tag, msg, tr) -> SimLog.log(priority <= 2 ? SimLog.Level.VERBOSE : priority == 3 ? SimLog.Level.DEBUG : priority == 4 ? SimLog.Level.INFO : priority == 5 ? SimLog.Level.WARN : SimLog.Level.ERROR, tag, msg, tr));

        TeamRepo repo = opts.repo == null ? null : new TeamRepo(opts.repo);
        if (repo != null) System.setProperty("ftcsim.sdkVersion", repo.sdkVersion);
        config = loadConfig(repo);
        RobotLog.ii(TAG, "Robot configuration: %s (%s)", config.name, configFile);

        world = new World();
        world.field = Field.byName(config.field);
        obeliskTag = obeliskFrom(world.field);
        ftcsim.hardware.HardwareBus.install(world);
        ftcsim.hardware.HardwareBus.configure(config.hardwareLatency.enabled, config.hardwareLatency.bulkReadMs,
            config.hardwareLatency.writeMs, config.hardwareLatency.readMs, config.hardwareLatency.i2cMs);
        hardware = new SimHardware(world, config, () -> world.field, () -> obeliskTag);
        launcher = new LauncherController(world);
        launcher.configure(LauncherSpec.from(config));
        runner = new SimOpModeRunner(hardware.hardwareMap, this::beforeInit, this::afterStop);
        hardware.snapAllowed = () -> runner.state() == SimOpModeRunner.State.INIT || runner.state() == SimOpModeRunner.State.IDLE;
        runner.addStateListener(s -> { if (s == SimOpModeRunner.State.RUNNING && matchTimerEnabled) armMatchTimer(); });
        runner.addGamepadEffectListener(new SimOpModeRunner.GamepadEffectListener() {
            @Override public void onRumble(int gamepadId, Gamepad.RumbleEffect effect) { broadcastRumble(gamepadId, effect); }
            @Override public void onStopRumble() { broadcastRumbleStop(); }
        });
        world.start();

        if (repo != null) {
            String classpath = System.getProperty("java.class.path");
            teamCode = new TeamCode(repo, new File(data, "teamcode"), classpath, opts.includeSamples, opts.prebuilt);
            teamCode.addListener(b -> {
                opModes = b.opModes;
                configurables.setClasses(b.allClasses);
                broadcastOpModes();
            });
            Thread builder = new Thread(() -> { teamCode.build(); teamCode.startWatching(); }, "ftcsim-initial-build");
            builder.setDaemon(true);
            builder.start();
        } else {
            RobotLog.ww(TAG, "No team repository configured: start FTCSim with --repo <path to your FtcRobotController project>");
        }

        web = new WebServer(opts.host, opts.port, "/web", opts.webOverrideDir, this::onMessage);
        web.addApi("/api/", this::api);
        web.start();
        String displayHost = opts.host;
        if (displayHost.equals("0.0.0.0") || displayHost.equals("::")) displayHost = "localhost";
        else if (displayHost.indexOf(':') >= 0 && !displayHost.startsWith("[")) displayHost = "[" + displayHost + "]";
        String url = "http://" + displayHost + ":" + web.port() + "/";
        if (!web.isLoopbackOnly()) {
            RobotLog.ww(TAG, "Web UI bound to %s: reachable from other machines (--host). Anyone who can reach it can control the robot.", opts.host);
        }
        RobotLog.ii(TAG, "FTCSim UI: %s", url);
        StdoutCapture.originalOut().println("\n  FTCSim is running: open " + url + " in your browser\n");
        Thread bc = new Thread(() -> { ftcsim.hardware.HardwareBus.markExemptThread(); broadcastLoop(); }, "ftcsim-broadcast");
        bc.setDaemon(true);
        bc.start();
        if (opts.openBrowser) openBrowser(url);
    }

    private RobotConfig loadConfig(TeamRepo repo) throws IOException {
        File f = opts.config;
        if (f == null) {
            String name = repo == null ? "default" : repo.dir.getName();
            File configs = new File(opts.home, "configs");
            f = new File(configs, name + ".json");
            // prefer an existing file that matches the repository name ignoring case (Decode.json for .../decode)
            File[] existing = configs.listFiles();
            if (existing != null) {
                for (File e : existing) {
                    if (e.getName().equalsIgnoreCase(name + ".local.json")) { f = e; break; }
                }
                if (!f.exists()) for (File e : existing) {
                    if (e.getName().equalsIgnoreCase(name + ".json")) { f = e; break; }
                }
            }
        }
        configFile = f;
        if (f.exists()) return RobotConfig.load(f);
        RobotConfig c = RobotConfig.defaults();
        c.name = f.getName().replace(".json", "");
        if (c.name.toLowerCase().contains("biobuzz")) c.field = "BIOBUZZ";
        c.notes = "Auto-generated by FTCSim. Devices requested by the team code are added as they are used; edit this file (or use the Config tab) to describe your robot.";
        try { c.save(f); RobotLog.ii(TAG, "Created robot configuration %s", f); } catch (IOException e) { RobotLog.ww(TAG, "Could not save configuration: %s", e); }
        return c;
    }

    private static void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Throwable ignored) {}
    }

    private void beforeInit() {
        // the simulator's own setup does not pay hub latency
        ftcsim.hardware.HardwareBus.exempt(() -> {
            hardware.resetForOpMode();
            DashboardBridge.reset();
        });
        // A new OpMode starts on a clean field: the balls from the last run go, the magazine is full
        // again and the score starts over.
        world.clearProjectiles();
        if (launcher != null) launcher.reload();
        ballsScored = 0;
        ftcsim.hardware.HardwareBus.resetCounters();
    }

    private void afterStop() {
        ftcsim.hardware.HardwareBus.exempt(() -> {
            ftcsim.vision.SimVision.closeAll(); // the Robot Controller closes VisionPortals when an OpMode stops
            hardware.stopAllMotors();
        });
    }

    private void armMatchTimer() {
        OpModeEntry e = runner.entry();
        long seconds = e != null && e.flavor == OpModeEntry.Flavor.AUTONOMOUS ? 30 : 120;
        matchDeadline = System.currentTimeMillis() + seconds * 1000;
    }

    // ------------------------------------------------------------------ messages
    @SuppressWarnings("unchecked")
    private void onMessage(WebServer.Client client, String text) {
        Map<String, Object> msg = gson.fromJson(text, new TypeToken<Map<String, Object>>() {}.getType());
        if (msg == null) return;
        String type = String.valueOf(msg.get("type"));
        try {
            switch (type) {
                case "gamepad": {
                    int id = (int) num(msg.get("id"), 1);
                    SimGamepad gp = id == 2 ? runner.gamepad2() : runner.gamepad1();
                    Object state = msg.get("state");
                    if (state instanceof Map) gp.apply((Map<String, Object>) state);
                    break;
                }
                case "opmode.init": {
                    String name = String.valueOf(msg.get("name"));
                    OpModeEntry entry = findOpMode(name, String.valueOf(msg.get("flavor")));
                    if (entry == null) { toast(client, "error", "Unknown OpMode " + name); break; }
                    runner.init(entry);
                    break;
                }
                case "opmode.start": runner.start(); break;
                case "opmode.stop": new Thread(runner::stop, "ftcsim-ui-stop").start(); break;
                case "robot.restart": new Thread(() -> { runner.restartRobot(); hardware.resetForOpMode(); world.clearProjectiles(); if (launcher != null) launcher.reload(); }, "ftcsim-ui-restart").start(); break;
                case "sim.setPose": {
                    double x = num(msg.get("x"), 0), y = num(msg.get("y"), 0), h = num(msg.get("headingDeg"), 90);
                    world.chassis.setPose(new Pose2(Units.inToM(x), Units.inToM(y), Math.toRadians(h)));
                    poseEpoch++;
                    break;
                }
                case "sim.reset": {
                    world.chassis.setPose(new Pose2(Units.inToM(config.startPose.x), Units.inToM(config.startPose.y), Math.toRadians(config.startPose.headingDeg)));
                    poseEpoch++;
                    break;
                }
                case "sim.setStartPose": {
                    config.startPose = new RobotConfig.StartPose(num(msg.get("x"), 0), num(msg.get("y"), 0), num(msg.get("headingDeg"), 90));
                    saveConfigQuiet();
                    break;
                }
                case "sim.pause": {
                    boolean paused = Boolean.TRUE.equals(msg.get("paused"));
                    world.setPaused(paused);
                    RobotLog.ii(TAG, paused ? "Simulation paused (robot code freezes at its next hardware call)" : "Simulation resumed");
                    break;
                }
                case "sim.step": {
                    double ms = num(msg.get("ms"), 20);
                    world.stepFor(Math.max(0.001, ms) / 1000.0);
                    break;
                }
                case "sim.timeScale": world.setTimeScale(num(msg.get("scale"), 1.0)); break;
                case "sim.latency": {
                    config.hardwareLatency.enabled = !Boolean.FALSE.equals(msg.get("enabled"));
                    config.hardwareLatency.bulkReadMs = num(msg.get("bulkReadMs"), config.hardwareLatency.bulkReadMs);
                    config.hardwareLatency.writeMs = num(msg.get("writeMs"), config.hardwareLatency.writeMs);
                    config.hardwareLatency.readMs = num(msg.get("readMs"), config.hardwareLatency.readMs);
                    config.hardwareLatency.i2cMs = num(msg.get("i2cMs"), config.hardwareLatency.i2cMs);
                    ftcsim.hardware.HardwareBus.configure(config.hardwareLatency.enabled, config.hardwareLatency.bulkReadMs,
                        config.hardwareLatency.writeMs, config.hardwareLatency.readMs, config.hardwareLatency.i2cMs);
                    RobotLog.ii(TAG, "Hardware latency %s (bulk read %.1f ms, write %.1f ms, read %.1f ms, I2C %.1f ms)",
                        config.hardwareLatency.enabled ? "on" : "off", config.hardwareLatency.bulkReadMs,
                        config.hardwareLatency.writeMs, config.hardwareLatency.readMs, config.hardwareLatency.i2cMs);
                    break;
                }
                case "sim.obelisk": obeliskTag = (int) num(msg.get("id"), 21); world.field.setOption("obelisk", String.valueOf(obeliskTag)); break;
                case "sim.field": {
                    String key = String.valueOf(msg.get("key")), value = String.valueOf(msg.get("value"));
                    if (world.field.setOption(key, value)) {
                        obeliskTag = obeliskFrom(world.field);
                        RobotLog.ii(TAG, "Field option %s = %s", key, value);
                        broadcastField();
                    } else RobotLog.ww(TAG, "Unknown field option %s = %s", key, value);
                    break;
                }
                case "sim.timer": matchTimerEnabled = Boolean.TRUE.equals(msg.get("enabled")); if (matchTimerEnabled && runner.state() == SimOpModeRunner.State.RUNNING) armMatchTimer(); break;
                case "sim.launch": {
                    if (launcher == null || !launcher.enabled()) { toast(client, "warn", "No launcher in the robot configuration"); break; }
                    double speed = launcher.fire();
                    if (speed <= 0 && launcher.remaining() <= 0) toast(client, "warn", "Magazine empty — reload");
                    break;
                }
                case "sim.launcherPitch": {
                    if (launcher == null) break;
                    Object deg = msg.get("deg");
                    launcher.setPitchDeg(deg == null ? null : num(deg, 0));   // null: back to the configuration
                    break;
                }
                case "sim.reload": {
                    if (launcher == null || !launcher.enabled()) { toast(client, "warn", "No launcher in the robot configuration"); break; }
                    launcher.reload();
                    break;
                }
                case "sim.clearBalls": world.clearProjectiles(); break;
                case "device.set": hardware.applyInput(String.valueOf(msg.get("name")), String.valueOf(msg.get("key")), msg.get("value")); break;
                case "code.reload": if (teamCode != null) new Thread(teamCode::build, "ftcsim-rebuild").start(); break;
                case "configurable.set": {
                    boolean ok = configurables.set(String.valueOf(msg.get("className")), String.valueOf(msg.get("path")), msg.get("value"));
                    if (!ok) toast(client, "error", "Could not set " + msg.get("className") + "." + msg.get("path"));
                    break;
                }
                case "config.get": client.send(gson.toJson(Map.of("type", "config", "json", config.toJson(), "file", configFile.getAbsolutePath()))); break;
                case "config.save": {
                    RobotConfig c = RobotConfig.fromJson(String.valueOf(msg.get("json")));
                    c.save(configFile);
                    toast(client, "info", "Saved " + configFile.getName() + " (restart FTCSim to apply hardware changes)");
                    break;
                }
                case "config.saveCurrent": saveConfigQuiet(); toast(client, "info", "Saved " + configFile.getName()); break;
                case "log.clear": SimLog.clear(); break;
                case "hello": sendHello(client); break;
                default: toast(client, "warn", "Unknown message " + type);
            }
        } catch (RuntimeException | IOException e) {
            RobotLog.ee(TAG, e, "UI message %s failed", type);
            toast(client, "error", e.toString());
        }
    }

    private void saveConfigQuiet() {
        try { config.save(configFile); } catch (IOException e) { RobotLog.ww(TAG, "save failed: %s", e); }
    }

    private OpModeEntry findOpMode(String name, String flavor) {
        for (OpModeEntry e : opModes) if (e.name.equals(name) && (flavor == null || "null".equals(flavor) || e.flavor.name().equals(flavor))) return e;
        for (OpModeEntry e : opModes) if (e.name.equals(name)) return e;
        return null;
    }

    private static double num(Object o, double def) { return o instanceof Number ? ((Number) o).doubleValue() : def; }

    private void toast(WebServer.Client c, String level, String text) { c.send(gson.toJson(Map.of("type", "toast", "level", level, "text", text))); }

    private WebServer.Response api(String method, String path, Map<String, String> query, byte[] body) {
        if (path.equals("/api/state")) return WebServer.Response.json(gson.toJson(stateMessage()));
        if (path.equals("/api/opmodes")) return WebServer.Response.json(gson.toJson(opModesMessage()));
        if (path.equals("/api/config")) return WebServer.Response.json(config.toJson());
        if (path.equals("/api/log")) return WebServer.Response.json(gson.toJson(SimLog.since(0, 4000)));
        return null;
    }

    // ------------------------------------------------------------------ broadcasting
    /**
     * The robot's declared shape, as the 3D view needs it. Height and chassis opacity live here too:
     * a translucent body whose height cannot be set from the configuration would be a drawing
     * trick, and the team should tune it the way they tune everything else.
     */
    private Map<String, Object> robotSpecMessage() {
        Map<String, Object> robot = new LinkedHashMap<>();
        robot.put("lengthIn", config.lengthIn);
        robot.put("widthIn", config.widthIn);
        robot.put("heightIn", config.heightIn);
        robot.put("chassisOpacity", config.chassisOpacity);
        robot.put("drivetrain", hardware.drivetrainAssignment());
        return robot;
    }

    private void sendHello(WebServer.Client client) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "hello");
        m.put("repo", opts.repo == null ? null : opts.repo.getAbsolutePath());
        m.put("repoName", opts.repo == null ? null : opts.repo.getName());
        m.put("sdkVersion", System.getProperty("ftcsim.sdkVersion", "11.0.0"));
        m.put("configName", config.name);
        m.put("configFile", configFile.getAbsolutePath());
        m.put("field", fieldMessage());
        m.put("robot", robotSpecMessage());
        m.put("startPose", config.startPose);
        client.send(gson.toJson(m));
        client.send(gson.toJson(opModesMessage()));
        lastLogSeq = 0;
    }

    private static int obeliskFrom(Field field) {
        String v = field.option("obelisk");
        try { return v == null ? 21 : Integer.parseInt(v); } catch (NumberFormatException e) { return 21; }
    }

    /** Ships the whole field description again: the CELLs moved (a HIVE tipped) or an option changed. */
    private void broadcastField() {
        web.broadcast(gson.toJson(Map.of("type", "field", "field", fieldMessage())));
    }

    private Map<String, Object> fieldMessage() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", world.field.name);
        f.put("sizeIn", Field.HALF_SIZE_IN * 2);
        List<Map<String, Object>> zones = new ArrayList<>();
        for (Field.Zone z : world.field.zones) zones.add(Map.of("name", z.name, "minX", z.minX, "minY", z.minY, "maxX", z.maxX, "maxY", z.maxY, "color", z.color));
        f.put("zones", zones);
        List<Map<String, Object>> shapes = new ArrayList<>();
        for (Field.Shape sh : world.field.shapes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", sh.type); m.put("coords", sh.coords); m.put("width", sh.width);
            if (sh.fill != null) m.put("fill", sh.fill); if (sh.stroke != null) m.put("stroke", sh.stroke); if (sh.label != null) m.put("label", sh.label);
            shapes.add(m);
        }
        f.put("shapes", shapes);
        List<Map<String, Object>> clusters = new ArrayList<>();
        for (Field.Cluster c : world.field.clusters) clusters.add(Map.of("name", c.name, "x", c.x, "y", c.y, "z", c.z, "yawDeg", c.yawDeg, "pitchDeg", c.pitchDeg, "ids", c.memberIds));
        f.put("clusters", clusters);
        List<Map<String, Object>> options = new ArrayList<>();
        for (Field.Option o : world.field.options) options.add(Map.of("key", o.key, "label", o.label, "choices", o.choices, "choiceLabels", o.choiceLabels));
        f.put("options", options);
        f.put("state", world.field.state());
        List<Map<String, Object>> tags = new ArrayList<>();
        for (Field.AprilTag t : world.field.tags) tags.add(Map.of("id", t.id, "name", t.name, "x", t.x, "y", t.y, "z", t.z, "yawDeg", t.yawDeg, "size", t.sizeIn));
        f.put("tags", tags);
        List<Map<String, Object>> obs = new ArrayList<>();
        for (Field.Obstacle o : world.field.obstacles) obs.add(Map.of("name", o.name, "minX", o.minX, "minY", o.minY, "maxX", o.maxX, "maxY", o.maxY, "height", o.height, "color", o.color));
        f.put("obstacles", obs);
        f.putAll(world.field.rules().extraFieldData());
        f.put("aprilTagMinFacing", org.firstinspires.ftc.vision.apriltag.AprilTagProcessorImpl.MIN_FACING);
        return f;
    }

    private Map<String, Object> opModesMessage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "opmodes");
        List<Map<String, Object>> list = new ArrayList<>();
        for (OpModeEntry e : opModes) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", e.name); o.put("group", e.group); o.put("flavor", e.flavor.name()); o.put("disabled", e.disabled);
            o.put("className", e.clazz.getName()); o.put("linear", e.linear); o.put("preselectTeleOp", e.preselectTeleOp);
            list.add(o);
        }
        m.put("list", list);
        TeamCode.Build b = teamCode == null ? null : teamCode.current();
        Map<String, Object> build = new LinkedHashMap<>();
        build.put("building", teamCode != null && teamCode.isBuilding());
        build.put("sourcesChanged", teamCode != null && teamCode.sourcesChanged());
        if (b != null) {
            TeamCompiler.Result r = b.compileResult;
            build.put("success", r.success); build.put("summary", r.summary); build.put("builtAt", b.builtAt);
            List<Map<String, Object>> diags = new ArrayList<>();
            for (TeamCompiler.Diagnostic d : r.diagnostics) diags.add(Map.of("kind", d.kind, "file", d.file, "line", d.line, "message", d.message));
            build.put("diagnostics", diags);
            build.put("loadErrors", b.loadErrors);
        } else if (teamCode == null) {
            build.put("success", false); build.put("summary", "No team repository configured (use --repo)");
        }
        m.put("build", build);
        if (teamCode != null) {
            TeamRepo r = teamCode.repo();
            List<String> roots = new ArrayList<>();
            for (File f : r.sourceRoots) roots.add(f.getAbsolutePath());
            m.put("sourceRoots", roots);
            m.put("hasKotlin", r.hasKotlin);
        }
        return m;
    }

    private void broadcastOpModes() { if (web != null) web.broadcast(gson.toJson(opModesMessage())); }

    /**
     * Ships a gamepad rumble effect queued by the OpMode to the UI, which replays it on the physical
     * controller through the Gamepad API. The steps are what the Robot Controller would send the Driver
     * Station in a CMD_RUMBLE_GAMEPAD command: {@code large}/{@code small} are 0..255 motor intensities
     * and {@code duration} is milliseconds (-1 = until cancelled).
     */
    private void broadcastRumble(int gamepadId, Gamepad.RumbleEffect effect) {
        if (web == null) return;
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Gamepad.RumbleEffect.Step step : effect.steps) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("large", step.large); s.put("small", step.small); s.put("duration", step.duration);
            steps.add(s);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "gamepad.rumble"); msg.put("id", gamepadId); msg.put("user", effect.user); msg.put("steps", steps);
        web.broadcast(gson.toJson(msg));
    }

    /** The OpMode stopped: a continuous rumble must not outlive it. */
    private void broadcastRumbleStop() { if (web != null) web.broadcast(gson.toJson(Map.of("type", "gamepad.rumble.stop"))); }

    private Map<String, Object> stateMessage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "state");
        m.put("t", System.currentTimeMillis());
        Chassis c = world.chassis;
        Pose2 p = c.pose();
        Vec2 v = c.velocityRobot();
        Map<String, Object> robot = new LinkedHashMap<>();
        robot.put("x", Units.mToIn(p.x)); robot.put("y", Units.mToIn(p.y)); robot.put("headingDeg", Math.toDegrees(p.heading));
        robot.put("vx", Units.mToIn(v.x)); robot.put("vy", Units.mToIn(v.y)); robot.put("omegaDeg", Math.toDegrees(c.omega()));
        robot.put("epoch", poseEpoch);
        List<Double> wheels = new ArrayList<>();
        for (MotorState ms : c.driveMotors()) wheels.add(ms.duty() * ms.physicalSign);
        robot.put("wheels", wheels);
        m.put("robot", robot);
        SimOpModeRunner.Snapshot s = runner.snapshot();
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("state", s.state.name()); op.put("name", s.opModeName); op.put("flavor", s.flavor); op.put("runtime", s.runtime);
        op.put("error", s.error); op.put("warning", s.warning); op.put("globalError", s.globalError);
        op.put("timerEnabled", matchTimerEnabled);
        op.put("timerRemaining", s.state == SimOpModeRunner.State.RUNNING && matchTimerEnabled ? Math.max(0, (matchDeadline - System.currentTimeMillis()) / 1000.0) : null);
        m.put("opmode", op);
        m.put("telemetry", Map.of("lines", s.telemetry, "time", s.telemetryTime));
        m.put("panels", Map.of("lines", DashboardBridge.panelsTelemetry(), "time", DashboardBridge.panelsTelemetryTime()));
        m.put("dashboard", Map.of("lines", DashboardBridge.dashboardTelemetry(), "time", DashboardBridge.dashboardTelemetryTime()));
        List<Map<String, Object>> drawings = new ArrayList<>();
        DashboardBridge.Drawing pd = DashboardBridge.panelsDrawing();
        if (pd != null && System.currentTimeMillis() - pd.time < 3000) drawings.add(Map.of("source", pd.source, "frame", pd.frame, "ops", pd.ops));
        DashboardBridge.Drawing dd = DashboardBridge.dashboardDrawing();
        if (dd != null && System.currentTimeMillis() - dd.time < 3000) drawings.add(Map.of("source", dd.source, "frame", dd.frame, "ops", dd.ops));
        m.put("drawings", drawings);
        m.put("devices", hardware.deviceViews());
        m.put("balls", ballsMessage());
        m.put("launcher", launcherMessage());
        m.put("battery", Map.of("volts", world.battery.volts(), "current", world.battery.current()));
        Map<String, Object> sim = new LinkedHashMap<>();
        sim.put("paused", world.isPaused());
        sim.put("stepping", world.stepRemaining() > 0);
        sim.put("stepRate", world.stepRate());
        sim.put("simTime", world.simTime());
        sim.put("timeScale", world.timeScale());
        sim.put("obelisk", obeliskTag);
        sim.put("uptime", (System.currentTimeMillis() - startedAt) / 1000);
        Map<String, Object> io = new LinkedHashMap<>();
        io.put("enabled", config.hardwareLatency.enabled);
        io.put("bulkReadMs", config.hardwareLatency.bulkReadMs);
        io.put("writeMs", config.hardwareLatency.writeMs);
        io.put("readMs", config.hardwareLatency.readMs);
        io.put("i2cMs", config.hardwareLatency.i2cMs);
        io.put("writes", ftcsim.hardware.HardwareBus.writeCount());
        io.put("reads", ftcsim.hardware.HardwareBus.readCount());
        io.put("bulkReads", ftcsim.hardware.HardwareBus.bulkReadCount());
        io.put("i2c", ftcsim.hardware.HardwareBus.i2cCount());
        io.put("totalMs", ftcsim.hardware.HardwareBus.ioMillis());
        sim.put("io", io);
        m.put("sim", sim);
        m.put("drivetrain", hardware.drivetrainAssignment());
        m.put("codeFrame", hardware.codeFrame());
        return m;
    }

    /**
     * Every ball on the field, in the field frame (inches, z up). Settled balls stay in the list:
     * they are litter on the floor until the field is cleared.
     */
    private List<Map<String, Object>> ballsMessage() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Projectile p : world.projectiles) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", p.id);
            b.put("x", p.x); b.put("y", p.y); b.put("z", p.z);
            b.put("r", p.radiusIn);
            b.put("state", p.state.name());
            if (p.scoredGoal != null) b.put("goal", p.scoredGoal);
            if (p.capturedIn != null) b.put("cell", p.capturedIn);
            out.add(b);
        }
        return out;
    }

    /** The magazine: whether a launcher is configured at all, and how many balls are left. */
    private Map<String, Object> launcherMessage() {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("enabled", launcher != null && launcher.enabled());
        l.put("remaining", launcher == null ? 0 : launcher.remaining());
        l.put("capacity", launcher == null ? 0 : launcher.capacity());
        l.put("pitchDeg", launcher == null ? 0 : launcher.pitchDeg());
        double br = 3.0;
        if (launcher != null && launcher.spec() != null) br = launcher.spec().ballRadiusIn;
        l.put("ballRadiusIn", br);
        l.put("scored", ballsScored);
        l.put("captured", world.capturedCount());
        int tips = 0;
        for (int n : world.tipCounts.values()) tips += n;
        l.put("tips", tips);
        return l;
    }

    /** Ships goals the balls reached to the UI. The world only records them; the UI is told once. */
    private void drainScores() {
        if (web == null) return;
        String goal;
        while ((goal = world.scoreEvents.poll()) != null) {
            ballsScored++;
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "ball.score");
            msg.put("goal", goal);
            msg.put("total", ballsScored);
            web.broadcast(gson.toJson(msg));
            RobotLog.ii(TAG, "Ball scored in %s (%d total)", goal, ballsScored);
        }
    }

    /** Ships HIVE tips to the UI: one message per tip, with the running count for that alliance. */
    private void drainTips() {
        if (web == null) return;
        String alliance;
        while ((alliance = world.tipEvents.poll()) != null) {
            int tips = world.tipCounts.getOrDefault(alliance, 0);
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "hive.tip");
            msg.put("alliance", alliance);
            msg.put("tips", tips);
            msg.put("points", 20 * tips);
            web.broadcast(gson.toJson(msg));
            RobotLog.ii(TAG, "%s HIVE tipped (%d)", alliance, tips);
        }
    }

    /** The robot drove over a ball: it is gone from the field and back in the magazine. */
    private void drainPickups() {
        Integer id;
        while ((id = world.pickupEvents.poll()) != null) {
            if (launcher != null) launcher.collect();
            RobotLog.ii(TAG, "Ball %d picked up (%d in the magazine)", id, launcher == null ? -1 : launcher.remaining());
        }
    }

    private void broadcastLoop() {
        long next = System.nanoTime();
        int tick = 0;
        while (true) {
            try {
                if (!web.clients().isEmpty()) {
                    // 20 Hz is also the cadence at which the launcher watches the team's devices
                    if (launcher != null) launcher.poll();
                    drainScores();
                    drainTips();
                    drainPickups();
                    // a HIVE that tipped on its own moved its CELLs: the UI only sees that from a
                    // fresh field description (this is the same message the option selects send)
                    if (world.takeFieldChanged()) broadcastField();
                    web.broadcast(gson.toJson(stateMessage()));
                    List<SimLog.Entry> entries = SimLog.since(lastLogSeq, 200);
                    if (!entries.isEmpty()) {
                        lastLogSeq = entries.get(entries.size() - 1).seq;
                        web.broadcast(gson.toJson(Map.of("type", "log", "entries", entries)));
                    }
                    if (tick % 10 == 0) {
                        web.broadcast(gson.toJson(Map.of("type", "configurables", "classes", configurables.snapshot())));
                        web.broadcast(gson.toJson(Map.of("type", "graphs", "series", DashboardBridge.graphs())));
                        Map<String, Object> om = opModesMessage();
                        om.put("listOnlyIfChanged", true);
                        web.broadcast(gson.toJson(Map.of("type", "buildStatus", "build", om.get("build"))));
                    }
                }
                if (matchTimerEnabled && runner.state() == SimOpModeRunner.State.RUNNING && System.currentTimeMillis() > matchDeadline) {
                    RobotLog.ii(TAG, "Match timer expired; stopping OpMode");
                    new Thread(runner::stop, "ftcsim-timer-stop").start();
                }
                tick++;
            } catch (Throwable e) {
                RobotLog.ee(TAG, e, "broadcast failed");
            }
            next += 50_000_000L;
            long sleep = next - System.nanoTime();
            if (sleep > 0) { try { Thread.sleep(sleep / 1_000_000L); } catch (InterruptedException e) { return; } }
            else next = System.nanoTime();
        }
    }

    public String toJson(Object o) { return gson.toJson(o); }
    public JsonElement toJsonTree(Object o) { return gson.toJsonTree(o); }
}
