package ftcsim.hardware;

import android.app.Activity;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.rev.RevBlinkinLedDriver;
import com.qualcomm.hardware.sparkfun.SparkFunOTOS;
import com.qualcomm.robotcore.hardware.*;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;
import com.qualcomm.robotcore.util.RobotLog;
import ftcsim.config.RobotConfig;
import ftcsim.physics.*;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.external.navigation.Rotation;

import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Builds the simulated robot (hubs, controllers, devices) from a RobotConfig,
 * wires the drivetrain into the physics, and creates devices on demand when
 * team code asks for something the configuration does not list.
 */
public final class SimHardware {
    public static final String TAG = "FTCSim";

    private static final class HubPorts {
        final LynxModule hub;
        final SimMotorController motors;
        final SimServoController servos;
        final SimAnalogController analog;
        final SimDigitalController digital;
        HubPorts(LynxModule hub) {
            this.hub = hub;
            motors = new SimMotorController(hub);
            servos = new SimServoController(hub);
            analog = new SimAnalogController(hub);
            digital = new SimDigitalController(hub);
        }
    }

    public final World world;
    public final RobotConfig config;
    public final SimHardwareMap hardwareMap;
    private final Map<String, HubPorts> hubs = new LinkedHashMap<>();
    private final Map<String, SimDevice> devices = new LinkedHashMap<>();
    private final Map<String, MotorState> motorStates = new LinkedHashMap<>();
    private final Map<String, ServoState> servoStates = new LinkedHashMap<>();
    private final List<SimPinpoint> pinpoints = new ArrayList<>();
    private final Supplier<Field> fieldSupplier;
    private final Object lock = new Object();
    /** Set by the simulator: returns true while snapping to code poses is allowed (OpMode in INIT). */
    public volatile java.util.function.BooleanSupplier snapAllowed = () -> true;
    private volatile String codeFrame;

    public SimHardware(World world, RobotConfig config, Supplier<Field> fieldSupplier) {
        this.world = world;
        this.config = config;
        this.fieldSupplier = fieldSupplier;
        this.hardwareMap = new SimHardwareMap(new Activity());
        build();
    }

    private void build() {
        world.battery.openCircuitVolts = config.batteryVolts;
        world.battery.internalResistanceOhms = config.batteryResistanceOhms;
        applyChassisConfig();
        for (RobotConfig.Hub h : config.hubs) createHub(h.name, h.address, h.parent);
        for (RobotConfig.Device d : new ArrayList<>(config.devices)) {
            try { createDevice(d); }
            catch (RuntimeException e) { RobotLog.ee(TAG, e, "Could not create configured device \"%s\" (%s)", d.name, d.type); }
        }
        wireDrivetrain();
        if (config.autoCreateDevices) hardwareMap.setAutoCreator(this::autoCreate);
        ftcsim.vision.SimVision.install(world.chassis, fieldSupplier);
        ftcsim.vision.SimClassFactory.install(() -> hardwareMap);
        world.addStepListener(new java.util.function.DoubleConsumer() {
            double acc;
            @Override public void accept(double dt) {
                for (SimPinpoint p : pinpoints) p.step(dt);
                acc += dt;
                if (acc >= 0.02) { acc = 0; updateHubPower(); }
            }
        });
    }

    private void applyChassisConfig() {
        Chassis c = world.chassis;
        RobotConfig.Drivetrain d = config.drivetrain;
        c.mass = config.massKg;
        c.length = Units.inToM(config.lengthIn);
        c.width = Units.inToM(config.widthIn);
        c.computeInertia();
        c.wheelRadius = Units.inToM(d.wheelDiameterIn) / 2;
        c.halfTrackWidth = Units.inToM(d.trackWidthIn) / 2;
        c.halfWheelBase = Units.inToM(d.wheelBaseIn) / 2;
        c.gearRatio = d.gearRatio > 0 ? d.gearRatio : 1.0;
        c.tractionCoefficient = d.tractionCoefficient;
        c.coulombX = c.mass * Units.inToM(d.forwardDecelInPerSec2);
        c.coulombY = c.mass * Units.inToM(d.lateralDecelInPerSec2);
        c.coulombTheta = c.inertiaZ * Math.toRadians(d.angularDecelDegPerSec2);
        c.viscousX = d.viscousFraction * c.coulombX;
        c.viscousY = d.viscousFraction * c.coulombY;
        c.viscousTheta = d.viscousFraction * c.coulombTheta;
        c.driveType = "tank".equalsIgnoreCase(d.type) ? Chassis.DriveType.TANK : "none".equalsIgnoreCase(d.type) ? Chassis.DriveType.NONE : Chassis.DriveType.MECANUM;
        c.setPose(new Pose2(Units.inToM(config.startPose.x), Units.inToM(config.startPose.y), Math.toRadians(config.startPose.headingDeg)));
    }

    private HubPorts createHub(String name, int address, boolean parent) {
        LynxModule hub = new LynxModule(name, address, parent);
        hub.setInputVoltage(world.battery.volts());
        HubPorts hp = new HubPorts(hub);
        hubs.put(name, hp);
        hardwareMap.registerDevice(name, hub);
        SimVoltageSensor vs = new SimVoltageSensor(name, name, world.battery::volts);
        hardwareMap.registerDevice(name, vs);
        devices.put(name + " (voltage)", vs);
        return hp;
    }

    private HubPorts hubFor(RobotConfig.Device spec) {
        String want = spec.hub != null ? spec.hub : spec.str("hub", null);
        if (want != null && hubs.containsKey(want)) return hubs.get(want);
        return hubs.values().iterator().next();
    }

    /** Finds a hub with a free port of the given kind, creating an extra virtual hub if all are full. */
    private HubPorts hubWithFreePort(RobotConfig.Device spec, String kind) {
        HubPorts preferred = hubFor(spec);
        if (spec.port != null && spec.hub != null) return preferred;
        List<HubPorts> order = new ArrayList<>();
        order.add(preferred);
        for (HubPorts h : hubs.values()) if (h != preferred) order.add(h);
        for (HubPorts h : order) if (freePort(h, kind) >= 0) return h;
        String name = "Expansion Hub " + (hubs.size() + 1);
        RobotLog.ww(TAG, "All %s ports are in use; adding virtual hub \"%s\"", kind, name);
        config.hubs.add(new RobotConfig.Hub(name, hubs.size() + 1, false));
        return createHub(name, hubs.size() + 1, false);
    }

    private static int freePort(HubPorts h, String kind) {
        switch (kind) {
            case "motor": return h.motors.freePort();
            case "servo": return h.servos.freePort();
            case "analog": return h.analog.freePort();
            default: return h.digital.freePort();
        }
    }

    private int choosePort(HubPorts h, RobotConfig.Device spec, String kind) {
        if (spec.port != null) {
            int p = spec.port;
            boolean free;
            switch (kind) {
                case "motor": free = p >= 0 && p < SimMotorController.PORTS && h.motors.isFree(p); break;
                case "servo": free = p >= 0 && p < SimServoController.PORTS && h.servos.state(p) == null; break;
                case "analog": free = p >= 0 && p < SimAnalogController.PORTS && h.analog.isFree(p); break;
                default: free = p >= 0 && p < SimDigitalController.PORTS && h.digital.isFree(p); break;
            }
            if (free) return p;
            RobotLog.ww(TAG, "Port %d on %s already used by another %s; assigning a free one for \"%s\"", p, h.hub.getName(), kind, spec.name);
        }
        int p = freePort(h, kind);
        spec.port = p;
        spec.hub = h.hub.getName();
        return p;
    }

    // ------------------------------------------------------------------ devices
    public HardwareDevice createDevice(RobotConfig.Device spec) {
        synchronized (lock) {
            String type = spec.type == null ? "DcMotorEx" : spec.type;
            HardwareDevice device;
            switch (type) {
                case "DcMotor": case "DcMotorEx": case "DcMotorSimple": case "Motor": device = createMotor(spec); break;
                case "Servo": case "ServoImplEx": device = createServo(spec, false); break;
                case "CRServo": case "CRServoImplEx": device = createServo(spec, true); break;
                case "AnalogInput": device = createAnalog(spec); break;
                case "DigitalChannel": device = createDigital(spec); break;
                case "TouchSensor": device = createTouch(spec); break;
                case "LED": device = createLed(spec); break;
                case "IMU": device = createImu(spec); break;
                case "BNO055IMU": device = new SimBNO055IMU(spec.name, hubFor(spec).hub.getName(), world.chassis); break;
                case "VoltageSensor": device = new SimVoltageSensor(spec.name, hubFor(spec).hub.getName(), world.battery::volts); break;
                case "RevColorSensorV3": case "ColorSensor": case "NormalizedColorSensor": device = createColor(spec); break;
                case "Rev2mDistanceSensor": case "DistanceSensor": device = createDistance(spec); break;
                case "AndyMarkTOF": {
                    SimAndyMarkTOF d = new SimAndyMarkTOF(spec.name, hubFor(spec).hub.getName(), world.chassis, fieldSupplier);
                    Map<String, Object> mount = spec.map("mount");
                    if (mount != null) d.setMount(RobotConfig.Device.num(mount, "x", 0), RobotConfig.Device.num(mount, "y", 0), RobotConfig.Device.num(mount, "yawDeg", 0));
                    device = d; break;
                }
                case "GoBildaPinpointDriver": case "Pinpoint": device = createPinpoint(spec); break;
                case "SparkFunOTOS": case "OTOS": device = createOtos(spec); break;
                case "Limelight3A": case "Limelight": device = createLimelight(spec); break;
                case "RevBlinkinLedDriver": case "Blinkin": device = createBlinkin(spec); break;
                case "WebcamName": case "Webcam": device = new SimWebcamName(spec.name, cameraMount(spec)); break;
                case "LynxModule": device = createHub(spec.name, config.hubs.size() + 1, false).hub; break;
                default: throw new IllegalArgumentException("Unknown device type " + type + " for \"" + spec.name + "\"");
            }
            if (device instanceof SimDevice) devices.put(spec.name, (SimDevice) device);
            if (!(device instanceof LynxModule)) hardwareMap.registerDevice(spec.name, device);
            if (config.findDevice(spec.name) == null) config.devices.add(spec);
            return device;
        }
    }

    private HardwareDevice createMotor(RobotConfig.Device spec) {
        HubPorts h = hubWithFreePort(spec, "motor");
        int port = choosePort(h, spec, "motor");
        String typeName = spec.str("motorType", config.drivetrain.motorType);
        MotorType mt = MotorType.byName(typeName);
        if (mt == null) {
            mt = MotorType.custom(spec.num("maxRpm", 312), spec.num("ticksPerRev", 537.7), spec.num("stallTorqueNm", 2.38), spec.num("stallCurrentA", 9.2));
            if (!spec.params.containsKey("maxRpm")) RobotLog.ww(TAG, "Unknown motor type \"%s\" for \"%s\"; using goBILDA 312 RPM characteristics", typeName, spec.name);
        }
        MotorState st = new MotorState(port, spec.name, mt);
        st.emulateVelocityOverflow = config.emulateVelocityOverflow;
        if (spec.params.containsKey("inertia")) st.inertia = spec.num("inertia", st.inertia);
        if (spec.params.containsKey("viscousFriction")) st.viscousFriction = spec.num("viscousFriction", st.viscousFriction);
        if (spec.params.containsKey("coulombFriction")) st.coulombFriction = spec.num("coulombFriction", st.coulombFriction);
        if (spec.params.containsKey("gravityTorque")) st.gravityTorque = spec.num("gravityTorque", 0);
        if (spec.params.containsKey("minTicks")) st.minAngle = spec.num("minTicks", 0) / mt.ticksPerRev * 2 * Math.PI;
        if (spec.params.containsKey("maxTicks")) st.maxAngle = spec.num("maxTicks", 0) / mt.ticksPerRev * 2 * Math.PI;
        if (!config.assumeTeamDirectionsCorrect) st.setMountSign(spec.bool("reversed", false) ? -1 : 1);
        MotorConfigurationType mct = new MotorConfigurationType();
        mct.setTicksPerRev(mt.ticksPerRev);
        mct.setGearing(mt.gearing);
        mct.setMaxRPM(mt.maxRpm);
        mct.setAchieveableMaxRPMFraction(0.85);
        // The SDK inverts raw power/encoder sign for CCW motor types (goBILDA, REV HD Hex); MotorState mirrors that.
        try { mct.setOrientation(mt.ccw ? Rotation.CCW : Rotation.CW); } catch (RuntimeException ignored) {}
        h.motors.attach(port, st, mct);
        world.motors.add(st);
        motorStates.put(spec.name, st);
        // A drive wheel's axle runs across the chassis (robot +z); a mechanism motor's axis is
        // whatever the configuration says. Defaulting to the config value rather than guessing
        // keeps "turns about what I declared" true even when the declaration is wrong — visible
        // and fixable, instead of silently wrong.
        String axis = st.isDriveWheel ? "z" : spec.str("axis", "x");
        return new SimDcMotor(h.motors, port, mct, st, spec.name, config.assumeTeamDirectionsCorrect,
                ftcsim.vision.DeviceMount.fromSpec(spec, axis));
    }

    private HardwareDevice createServo(RobotConfig.Device spec, boolean continuous) {
        HubPorts h = hubWithFreePort(spec, "servo");
        int port = choosePort(h, spec, "servo");
        ServoState st = new ServoState(port, spec.name);
        st.continuous = continuous;
        st.secondsFullRange = spec.num("secondsFullRange", 0.6);
        st.maxRevPerSec = spec.num("maxRevPerSec", 1.5);
        if (spec.params.containsKey("initialPosition")) st.setPosition(spec.num("initialPosition", 0.5));
        h.servos.attach(port, st);
        world.servos.add(st);
        servoStates.put(spec.name, st);
        // Servos sweep, so the default axis is pitch (robot +z) and a mount without explicit
        // minDeg/maxDeg is a plain servo parked at one angle.
        ftcsim.vision.DeviceMount mount = ftcsim.vision.DeviceMount.fromSpec(spec, "z");
        return continuous
                ? new SimCRServo(h.servos, port, st, spec.name, mount)
                : new SimServo(h.servos, port, st, spec.name, mount);
    }

    private HardwareDevice createAnalog(RobotConfig.Device spec) {
        HubPorts h = hubWithFreePort(spec, "analog");
        int port = choosePort(h, spec, "analog");
        SimAnalogInput ai = new SimAnalogInput(h.analog, port, spec.name, spec.num("voltage", 0));
        Map<String, Object> src = spec.map("source");
        if (src != null && src.get("servo") != null) {
            String servoName = String.valueOf(src.get("servo"));
            double min = RobotConfig.Device.num(src, "minVoltage", 0), max = RobotConfig.Device.num(src, "maxVoltage", 3.3);
            boolean angular = "continuous".equals(RobotConfig.Device.str(src, "type", "position"));
            double revsPerRange = RobotConfig.Device.num(src, "revolutionsPerRange", 1.0);
            ai.setDerivedSource(() -> {
                ServoState s = servoStates.get(servoName);
                if (s == null) return 0;
                double frac = angular ? ((s.angle() / (2 * Math.PI) / revsPerRange) % 1.0 + 1.0) % 1.0 : s.position();
                return min + frac * (max - min);
            }, "servo " + servoName);
        }
        return ai;
    }

    private HardwareDevice createDigital(RobotConfig.Device spec) {
        HubPorts h = hubWithFreePort(spec, "digital");
        int port = choosePort(h, spec, "digital");
        return new SimDigitalChannel(h.digital, port, spec.name, spec.bool("state", true));
    }

    private HardwareDevice createTouch(RobotConfig.Device spec) {
        HubPorts h = hubWithFreePort(spec, "digital");
        int port = choosePort(h, spec, "digital");
        return new SimTouchSensor(h.digital, port, spec.name);
    }

    private HardwareDevice createLed(RobotConfig.Device spec) {
        HubPorts h = hubWithFreePort(spec, "digital");
        int port = choosePort(h, spec, "digital");
        return new SimLED(h.digital, port, spec.name);
    }

    private HardwareDevice createImu(RobotConfig.Device spec) {
        SimIMU imu = new SimIMU(spec.name, hubFor(spec).hub.getName(), world.chassis);
        imu.setNoise(spec.num("yawNoiseDeg", 0), spec.num("driftDegPerMin", 0));
        return imu;
    }

    private HardwareDevice createColor(RobotConfig.Device spec) {
        SimColorSensorV3 c = new SimColorSensorV3(spec.name, hubFor(spec).hub.getName());
        for (String k : new String[] { "red", "green", "blue", "alpha", "distanceMm" }) if (spec.params.containsKey(k)) c.applyInput(k, spec.params.get(k));
        return c;
    }

    private HardwareDevice createDistance(RobotConfig.Device spec) {
        SimDistanceSensor d = new SimDistanceSensor(spec.name, hubFor(spec).hub.getName(), world.chassis, fieldSupplier);
        Map<String, Object> mount = spec.map("mount");
        if (mount != null) d.setMount(RobotConfig.Device.num(mount, "x", 0), RobotConfig.Device.num(mount, "y", 0), RobotConfig.Device.num(mount, "yawDeg", 0));
        else if (spec.params.containsKey("distanceMm")) d.applyInput("distanceMm", spec.params.get("distanceMm"));
        return d;
    }

    /** "pedro" or "ftc": the frame the team code expresses field poses in. */
    public String codeFrame() {
        if (codeFrame == null) {
            String cf = config.codeCoordinateFrame == null ? "auto" : config.codeCoordinateFrame;
            if ("auto".equalsIgnoreCase(cf)) {
                boolean pedro;
                try { Class.forName("com.pedropathing.follower.Follower", false, SimHardware.class.getClassLoader()); pedro = true; } catch (Throwable t) { pedro = false; }
                cf = pedro ? "pedro" : "ftc";
            }
            codeFrame = cf.toLowerCase();
        }
        return codeFrame;
    }

    private final PoseSnapper snapper = this::snapTo;

    private void snapTo(double xIn, double yIn, double headingRad) {
        if (!config.snapToCodePose || !snapAllowed.getAsBoolean()) return;
        double fx, fy, fh;
        if ("pedro".equals(codeFrame())) { fx = 72 - yIn; fy = xIn - 72; fh = headingRad + Math.PI / 2; }
        else { fx = xIn; fy = yIn; fh = headingRad; }
        if (Math.abs(fx) > 200 || Math.abs(fy) > 200 || Double.isNaN(fh)) return;
        world.chassis.setPose(new Pose2(Units.inToM(fx), Units.inToM(fy), Pose2.normalize(fh)));
        RobotLog.ii(TAG, "Robot moved to the pose set by the code: (%.1f, %.1f, %.0f deg) [%s frame]", xIn, yIn, Math.toDegrees(headingRad), codeFrame());
    }

    private HardwareDevice createPinpoint(RobotConfig.Device spec) {
        Map<String, Object> xp = spec.map("xPod"), yp = spec.map("yPod");
        double ticksPerRev = spec.num("ticksPerRev", 2000);
        double wheelMm = spec.num("wheelDiameterMm", "4bar".equalsIgnoreCase(spec.str("podType", "swingarm")) ? 32 : 48);
        double radius = Units.mmToM(wheelMm) / 2;
        DeadWheel x = new DeadWheel(spec.name + ".x", new Vec2(Units.inToM(RobotConfig.Device.num(xp, "x", 0)), Units.inToM(RobotConfig.Device.num(xp, "y", 0))), 0, radius, ticksPerRev);
        DeadWheel y = new DeadWheel(spec.name + ".y", new Vec2(Units.inToM(RobotConfig.Device.num(yp, "x", 0)), Units.inToM(RobotConfig.Device.num(yp, "y", 0))), Math.PI / 2, radius, ticksPerRev);
        if (!config.assumeTeamDirectionsCorrect) {
            x.physicalSign = RobotConfig.Device.bool(xp, "reversed", false) ? -1 : 1;
            y.physicalSign = RobotConfig.Device.bool(yp, "reversed", false) ? -1 : 1;
        }
        world.deadWheels.add(x); world.deadWheels.add(y);
        SimPinpoint p = new SimPinpoint(spec.name, hubFor(spec).hub.getName(), world.chassis, x, y, config.assumeTeamDirectionsCorrect);
        p.setPoseSnapper(snapper);
        pinpoints.add(p);
        return p;
    }

    private HardwareDevice createOtos(RobotConfig.Device spec) { SimOTOS o = new SimOTOS(spec.name, hubFor(spec).hub.getName(), world.chassis); o.setPoseSnapper(snapper); return o; }

    private HardwareDevice createLimelight(RobotConfig.Device spec) {
        Limelight3A ll = new Limelight3A(spec.name, world.chassis, fieldSupplier);
        Map<String, Object> mount = spec.map("mount");
        if (mount != null) ll.configureMount(RobotConfig.Device.num(mount, "x", 0), RobotConfig.Device.num(mount, "y", 0), RobotConfig.Device.num(mount, "z", 10), RobotConfig.Device.num(mount, "yawDeg", 0), RobotConfig.Device.num(mount, "pitchDeg", 0));
        ll.configureOptics(spec.num("hfovDeg", 54.5), spec.num("vfovDeg", 42), spec.num("maxRangeIn", 150));
        ll.configureNoise(spec.num("noiseIn", 0.4), spec.num("noiseDeg", 0.4));
        return ll;
    }

    private HardwareDevice createBlinkin(RobotConfig.Device spec) {
        HubPorts h = hubWithFreePort(spec, "servo");
        int port = choosePort(h, spec, "servo");
        ServoState st = new ServoState(port, spec.name);
        h.servos.attach(port, st);
        world.servos.add(st);
        servoStates.put(spec.name, st);
        return new SimBlinkin(h.servos, port, st, spec.name);
    }

    // ------------------------------------------------------------- auto create
    private ftcsim.vision.CameraMount cameraMount(RobotConfig.Device spec) {
        ftcsim.vision.CameraMount m = new ftcsim.vision.CameraMount();
        Map<String, Object> mount = spec.map("mount");
        if (mount != null) {
            m.xIn = RobotConfig.Device.num(mount, "x", m.xIn); m.yIn = RobotConfig.Device.num(mount, "y", m.yIn); m.zIn = RobotConfig.Device.num(mount, "z", m.zIn);
            m.yawDeg = RobotConfig.Device.num(mount, "yawDeg", m.yawDeg); m.pitchDeg = RobotConfig.Device.num(mount, "pitchDeg", m.pitchDeg);
        }
        m.hfovDeg = spec.num("hfovDeg", m.hfovDeg); m.vfovDeg = spec.num("vfovDeg", m.vfovDeg);
        m.widthPx = (int) spec.num("widthPx", m.widthPx); m.heightPx = (int) spec.num("heightPx", m.heightPx);
        m.maxRangeIn = spec.num("maxRangeIn", m.maxRangeIn); m.noiseIn = spec.num("noiseIn", m.noiseIn); m.noiseDeg = spec.num("noiseDeg", m.noiseDeg);
        return m;
    }

    /**
     * Fallback for I2C driver classes the simulator has no model for (LED sticks, older IMUs, third
     * party sensors): the SDK's own driver is created on a virtual bus that accepts writes and reads zeros.
     */
    /** SDK interfaces whose driver class the generic I2C path can instantiate. */
    private static final Map<String, String> I2C_IMPLS = Map.of(
        "com.qualcomm.hardware.digitalchickenlabs.OctoQuad", "com.qualcomm.hardware.digitalchickenlabs.OctoQuadImpl");

    private HardwareDevice createGenericI2c(Class<?> requested, String name) {
        if (requested.isInterface() && I2C_IMPLS.containsKey(requested.getName())) {
            try { requested = Class.forName(I2C_IMPLS.get(requested.getName())); } catch (ClassNotFoundException e) { return null; }
        }
        if (requested.isInterface() || java.lang.reflect.Modifier.isAbstract(requested.getModifiers())) return null;
        if (!com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice.class.isAssignableFrom(requested)) return null;
        VirtualI2cDevice bus = new VirtualI2cDevice(name);
        Object instance = null;
        for (java.lang.reflect.Constructor<?> c : requested.getConstructors()) {
            Class<?>[] pt = c.getParameterTypes();
            try {
                if (pt.length == 2 && pt[0].isInstance(bus) && pt[1] == boolean.class) instance = c.newInstance(bus, true);
                else if (pt.length == 1 && pt[0].isInstance(bus)) instance = c.newInstance(bus);
            } catch (ReflectiveOperationException | RuntimeException e) { RobotLog.ww(TAG, e, "generic I2C constructor failed for %s", requested.getName()); }
            if (instance != null) break;
        }
        if (!(instance instanceof HardwareDevice)) return null;
        HardwareDevice device = (HardwareDevice) instance;
        markInitialized(device, name);
        synchronized (lock) { genericI2c.add(device); }
        RobotConfig.Device spec = new RobotConfig.Device(name, requested.getSimpleName());
        spec.autoCreated = true;
        synchronized (lock) {
            devices.put(name, new GenericI2cView(name, requested.getSimpleName(), device));
            hardwareMap.registerDevice(name, device);
            if (config.findDevice(name) == null) config.devices.add(spec);
        }
        RobotLog.ww(TAG, "Device \"%s\" (%s) has no simulation model; created the SDK driver on a virtual I2C bus (writes accepted, reads return zeros)", name, requested.getSimpleName());
        return device;
    }

    private final List<HardwareDevice> genericI2c = new ArrayList<>();

    /** The SDK initialises I2C devices on first use (doInitialize talks to the bus / casts to the Lynx client): skip that. */
    private static void markInitialized(HardwareDevice device, String name) {
        try {
            java.lang.reflect.Field f = com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice.class.getDeclaredField("isInitialized");
            f.setAccessible(true);
            f.setBoolean(device, true);
        } catch (ReflectiveOperationException | RuntimeException e) { if (name != null) RobotLog.ww(TAG, e, "could not mark %s initialised", name); }
    }

    private static final class GenericI2cView implements SimDevice {
        private final String name, type; private final HardwareDevice device;
        GenericI2cView(String name, String type, HardwareDevice device) { this.name = name; this.type = type; this.device = device; }
        @Override public String simName() { return name; }
        @Override public String simType() { return type; }
        @Override public String simHub() { return "Control Hub"; }
        @Override public int simPort() { return 0; }
        @Override public void fillView(Map<String, Object> v) {
            String driver;
            try { driver = device.getDeviceName(); } catch (Throwable t) { driver = device.getClass().getSimpleName(); }
            v.put("simulated", false); v.put("driver", driver); v.put("note", "virtual I2C bus: reads return zeros");
        }
    }

    private HardwareDevice autoCreate(Class<?> requested, String name) {
        String type = typeFor(requested);
        if (type == null) {
            HardwareDevice generic = createGenericI2c(requested, name);
            if (generic != null) return generic;
            RobotLog.ee(TAG, "Team code asked for \"%s\" of type %s which FTCSim cannot simulate", name, requested.getName());
            return null;
        }
        if ("LynxModule".equals(type) && hubs.containsKey(name)) return hubs.get(name).hub;
        RobotConfig.Device spec = new RobotConfig.Device(name, type);
        spec.autoCreated = true;
        HardwareDevice d = createDevice(spec);
        if ("DcMotorEx".equals(type)) wireDrivetrain();
        return d;
    }

    public static String typeFor(Class<?> c) {
        if (c.isAssignableFrom(SimPinpoint.class) && c != HardwareDevice.class && c != Object.class) return "GoBildaPinpointDriver";
        if (c.isAssignableFrom(SimOTOS.class) && c != HardwareDevice.class && c != Object.class) return "SparkFunOTOS";
        if (c.isAssignableFrom(Limelight3A.class) && c != HardwareDevice.class && c != Object.class) return "Limelight3A";
        if (c.isAssignableFrom(SimBlinkin.class) && c != HardwareDevice.class && c != Object.class) return "RevBlinkinLedDriver";
        if (c.isAssignableFrom(SimAndyMarkTOF.class) && !c.isAssignableFrom(SimDistanceSensor.class)) return "AndyMarkTOF";
        if (c.isAssignableFrom(SimDistanceSensor.class) && c != HardwareDevice.class && c != Object.class) return "Rev2mDistanceSensor";
        if (c.isAssignableFrom(SimColorSensorV3.class) && c != HardwareDevice.class && c != Object.class) return "RevColorSensorV3";
        if (c.isAssignableFrom(SimTouchSensor.class) && c != HardwareDevice.class && c != Object.class) return "TouchSensor";
        if (c.isAssignableFrom(SimLED.class) && c != HardwareDevice.class && c != Object.class) return "LED";
        if (c.isAssignableFrom(SimBNO055IMU.class) && c != HardwareDevice.class && c != Object.class) return "BNO055IMU";
        if (c.isAssignableFrom(SimIMU.class) && c != HardwareDevice.class && c != Object.class) return "IMU";
        if (c.isAssignableFrom(SimVoltageSensor.class) && c != HardwareDevice.class && c != Object.class) return "VoltageSensor";
        if (c.isAssignableFrom(SimCRServo.class) && c != HardwareDevice.class && c != Object.class && c != PwmControl.class) return "CRServo";
        if (c.isAssignableFrom(SimServo.class) && c != HardwareDevice.class && c != Object.class && c != PwmControl.class) return "Servo";
        if (c.isAssignableFrom(SimDcMotor.class) && c != HardwareDevice.class && c != Object.class) return "DcMotorEx";
        if (c.isAssignableFrom(SimAnalogInput.class) && c != HardwareDevice.class && c != Object.class) return "AnalogInput";
        if (c.isAssignableFrom(SimDigitalChannel.class) && c != HardwareDevice.class && c != Object.class) return "DigitalChannel";
        if (c == WebcamName.class || c == CameraName.class) return "WebcamName";
        if (c == LynxModule.class) return "LynxModule";
        if (c == GoBildaPinpointDriver.class || c == SparkFunOTOS.class || c == RevBlinkinLedDriver.class) return c.getSimpleName();
        return null;
    }

    // ------------------------------------------------------------- drivetrain
    private static final Pattern LF = Pattern.compile("(?i)^(left[_ ]?front|front[_ ]?left|lf|fl|leftfrontdrive|frontleftdrive|leftfrontmotor|frontleftmotor|motor[_ ]?fl|motor[_ ]?lf|fl[_ ]?motor|lf[_ ]?motor|drive[_ ]?fl|drive[_ ]?lf)$");
    private static final Pattern RF = Pattern.compile("(?i)^(right[_ ]?front|front[_ ]?right|rf|fr|rightfrontdrive|frontrightdrive|rightfrontmotor|frontrightmotor|motor[_ ]?fr|motor[_ ]?rf|fr[_ ]?motor|rf[_ ]?motor|drive[_ ]?fr|drive[_ ]?rf)$");
    private static final Pattern LR = Pattern.compile("(?i)^(left[_ ]?(back|rear)|(back|rear)[_ ]?left|lb|bl|lr|rl|left(back|rear)drive|(back|rear)leftdrive|left(back|rear)motor|(back|rear)leftmotor|motor[_ ]?(bl|lb|lr|rl)|(bl|lb)[_ ]?motor|drive[_ ]?(bl|lb))$");
    private static final Pattern RR = Pattern.compile("(?i)^(right[_ ]?(back|rear)|(back|rear)[_ ]?right|rb|br|rr|right(back|rear)drive|(back|rear)rightdrive|right(back|rear)motor|(back|rear)rightmotor|motor[_ ]?(br|rb|rr)|(br|rb)[_ ]?motor|drive[_ ]?(br|rb))$");

    /** Assigns motors to drivetrain roles from the config, falling back to name heuristics. */
    public void wireDrivetrain() {
        synchronized (lock) {
            Chassis c = world.chassis;
            RobotConfig.Drivetrain d = config.drivetrain;
            for (MotorState m : motorStates.values()) m.isDriveWheel = false;
            if (c.driveType == Chassis.DriveType.MECANUM) {
                c.leftFront = pick(d.leftFront, LF); c.rightFront = pick(d.rightFront, RF);
                c.leftRear = pick(d.leftRear, LR); c.rightRear = pick(d.rightRear, RR);
                for (MotorState m : new MotorState[] { c.leftFront, c.rightFront, c.leftRear, c.rightRear }) if (m != null) m.isDriveWheel = true;
            } else if (c.driveType == Chassis.DriveType.TANK) {
                c.leftMotors.clear(); c.rightMotors.clear();
                for (String n : d.left) { MotorState m = motorStates.get(n); if (m != null) { c.leftMotors.add(m); m.isDriveWheel = true; } }
                for (String n : d.right) { MotorState m = motorStates.get(n); if (m != null) { c.rightMotors.add(m); m.isDriveWheel = true; } }
                if (c.leftMotors.isEmpty() && c.rightMotors.isEmpty()) {
                    for (Map.Entry<String, MotorState> e : motorStates.entrySet()) {
                        String n = e.getKey().toLowerCase();
                        if (n.contains("left")) { c.leftMotors.add(e.getValue()); e.getValue().isDriveWheel = true; }
                        else if (n.contains("right")) { c.rightMotors.add(e.getValue()); e.getValue().isDriveWheel = true; }
                    }
                }
            }
        }
    }

    private MotorState pick(String configured, Pattern heuristic) {
        if (configured != null && motorStates.containsKey(configured)) return motorStates.get(configured);
        for (Map.Entry<String, MotorState> e : motorStates.entrySet()) if (heuristic.matcher(e.getKey()).matches()) return e.getValue();
        return null;
    }

    /**
     * The drivetrain as the UI needs it: which motor fills which role, plus the geometry the 3D view
     * needs to place the wheels. Values are typed rather than all strings, so the web view receives
     * JSON numbers and can use them without parsing.
     */
    public Map<String, Object> drivetrainAssignment() {
        Map<String, Object> m = new LinkedHashMap<>();
        Chassis c = world.chassis;
        m.put("type", c.driveType.name().toLowerCase());
        // Geometry travels with the names so the 3D view can place the wheels where the team
        // actually built them instead of guessing offsets from the chassis box.
        RobotConfig.Drivetrain d = config.drivetrain;
        m.put("wheelDiameterIn", d.wheelDiameterIn);
        m.put("trackWidthIn", d.trackWidthIn);
        m.put("wheelBaseIn", d.wheelBaseIn);
        if (c.driveType == Chassis.DriveType.MECANUM) {
            m.put("leftFront", c.leftFront == null ? null : c.leftFront.name);
            m.put("rightFront", c.rightFront == null ? null : c.rightFront.name);
            m.put("leftRear", c.leftRear == null ? null : c.leftRear.name);
            m.put("rightRear", c.rightRear == null ? null : c.rightRear.name);
        } else if (c.driveType == Chassis.DriveType.TANK) {
            List<String> l = new ArrayList<>(), r = new ArrayList<>();
            for (MotorState s : c.leftMotors) l.add(s.name);
            for (MotorState s : c.rightMotors) r.add(s.name);
            m.put("left", String.join(",", l)); m.put("right", String.join(",", r));
        }
        return m;
    }

    // ------------------------------------------------------------- lifecycle
    private void updateHubPower() {
        for (HubPorts h : hubs.values()) {
            double current = 0.25;
            for (int i = 0; i < SimMotorController.PORTS; i++) { MotorState s = h.motors.state(i); if (s != null) current += s.currentAmps(); }
            for (int i = 0; i < SimServoController.PORTS; i++) { ServoState s = h.servos.state(i); if (s != null) current += s.current(); }
            h.hub.setCurrent(current);
            h.hub.setInputVoltage(world.battery.volts());
        }
    }

    /** Puts the hardware in the state a new OpMode finds it in (motors stopped, caches cleared). */
    public void resetForOpMode() {
        synchronized (lock) {
            for (HubPorts h : hubs.values()) {
                h.hub.resetDeviceConfigurationForOpMode();
                h.motors.resetDeviceConfigurationForOpMode();
                h.servos.resetDeviceConfigurationForOpMode();
                h.digital.resetDeviceConfigurationForOpMode();
            }
            for (HardwareDevice d : hardwareMap.unsafeIterable()) {
                try { d.resetDeviceConfigurationForOpMode(); } catch (RuntimeException e) { RobotLog.ww(TAG, "reset of %s failed: %s", d, e); }
            }
            for (SimDevice d : devices.values()) d.simResetForOpMode();
            for (HardwareDevice d : genericI2c) markInitialized(d, null);
        }
    }

    /** Stops every motor (what the hub does when the OpMode stops). */
    public void stopAllMotors() {
        for (MotorState m : motorStates.values()) { m.power = 0; m.useVelocityTarget = false; }
    }

    public Collection<LynxModule> hubs() { List<LynxModule> l = new ArrayList<>(); for (HubPorts h : hubs.values()) l.add(h.hub); return l; }
    public Map<String, SimDevice> devices() { synchronized (lock) { return new LinkedHashMap<>(devices); } }
    public MotorState motorState(String name) { return motorStates.get(name); }
    public ServoState servoState(String name) { return servoStates.get(name); }
    public List<SimPinpoint> pinpoints() { return pinpoints; }

    public boolean applyInput(String deviceName, String key, Object value) {
        SimDevice d;
        synchronized (lock) { d = devices.get(deviceName); }
        return d != null && d.applyInput(key, value);
    }

    /** Snapshot of all devices for the UI. */
    public List<Map<String, Object>> deviceViews() {
        List<Map<String, Object>> out = new ArrayList<>(ftcsim.vision.SimVision.views());
        List<SimDevice> list;
        synchronized (lock) { list = new ArrayList<>(devices.values()); }
        for (SimDevice d : list) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", d.simName());
            v.put("type", d.simType());
            v.put("hub", d.simHub());
            v.put("port", d.simPort());
            RobotConfig.Device spec = config.findDevice(d.simName());
            v.put("autoCreated", spec != null && spec.autoCreated);
            // Declared pose, sent next to the live values so the 3D view can place the part on the
            // same message it uses to animate it — no second round trip, no frame where the part
            // moves but is still drawn at the origin.
            ftcsim.vision.DeviceMount mount = d.simMount();
            if (mount != null) v.put("mount", mount.toView());
            Map<String, Object> values = new LinkedHashMap<>();
            try { d.fillView(values); } catch (RuntimeException e) { values.put("error", e.toString()); }
            v.put("values", values);
            out.add(v);
        }
        for (HubPorts h : hubs.values()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", h.hub.getName()); v.put("type", "LynxModule"); v.put("hub", h.hub.getName()); v.put("port", h.hub.getModuleAddress());
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("voltage", h.hub.getInputVoltage(org.firstinspires.ftc.robotcore.external.navigation.VoltageUnit.VOLTS));
            values.put("current", h.hub.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS));
            values.put("bulkCaching", h.hub.getBulkCachingMode().name());
            values.put("bulkReads", h.hub.getBulkReadCount());
            v.put("values", values);
            out.add(v);
        }
        return out;
    }
}
