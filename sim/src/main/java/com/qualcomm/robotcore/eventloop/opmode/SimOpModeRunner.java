package com.qualcomm.robotcore.eventloop.opmode;

import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.robocol.TelemetryMessage;
import com.qualcomm.robotcore.util.RobotLog;
import ftcsim.runner.OpModeEntry;
import ftcsim.runner.SimGamepad;
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeServices;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Drives the SDK's real OpMode lifecycle the way the Robot Controller's
 * OpModeManagerImpl does: init on the OpMode thread, an event loop delivering
 * gamepad data and loop notifications, start, and stop with the same
 * stuck-detection behaviour. Lives in the SDK package to reach the
 * package-private lifecycle hooks of {@link OpModeInternal}.
 */
public final class SimOpModeRunner {
    public static final String TAG = "OpModeManager";
    public enum State { IDLE, INIT, RUNNING, STOPPING, ESTOP }

    /** Receives every telemetry transmission (already composed into lines). */
    public interface TelemetryListener { void onTelemetry(List<String> lines, long timestampMs); }

    /**
     * Receives the gamepad effects the OpMode queued. On a real Robot Controller these are drained by
     * {@code FtcEventLoopHandler.gamepadEffects()} and shipped to the Driver Station, which rumbles the
     * physical controller; the simulator hands them to the browser instead.
     */
    public interface GamepadEffectListener {
        void onRumble(int gamepadId, Gamepad.RumbleEffect effect);
        /** The OpMode stopped: cancel any continuous rumble. */
        void onStopRumble();
    }

    public static final class Snapshot {
        public final State state; public final String opModeName; public final String flavor; public final double runtime;
        public final String error; public final List<String> telemetry; public final long telemetryTime; public final String warning; public final String globalError;
        Snapshot(State state, String opModeName, String flavor, double runtime, String error, List<String> telemetry, long telemetryTime, String warning, String globalError) {
            this.state = state; this.opModeName = opModeName; this.flavor = flavor; this.runtime = runtime; this.error = error; this.telemetry = telemetry; this.telemetryTime = telemetryTime; this.warning = warning; this.globalError = globalError;
        }
    }

    private final HardwareMap hardwareMap;
    private final SimGamepad gamepad1 = new SimGamepad(1);
    private final SimGamepad gamepad2 = new SimGamepad(2);
    private final Runnable beforeInit;
    private final Runnable afterStop;
    private final List<TelemetryListener> telemetryListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<State>> stateListeners = new CopyOnWriteArrayList<>();
    private final List<GamepadEffectListener> gamepadEffectListeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private volatile State state = State.IDLE;
    private volatile OpMode opMode;
    /** The gamepads the OpMode sees; they own the rumble queues the event loop drains. */
    private volatile Gamepad opModeGamepad1;
    private volatile Gamepad opModeGamepad2;
    private volatile OpModeEntry entry;
    private volatile String error;
    private volatile long stateSince = System.currentTimeMillis();
    private volatile List<String> telemetryLines = new ArrayList<>();
    private volatile long telemetryTime;
    private Thread eventLoopThread;
    private volatile boolean stopRequestedByOpMode;
    private volatile boolean ignoreStopRequests;

    private final OpModeServices services = new OpModeServices() {
        @Override public void refreshUserTelemetry(TelemetryMessage telemetry, double sInterval) { publishTelemetry(telemetry); }
        @Override public void requestOpModeStop(OpMode opModeToStop) {
            if (opModeToStop != opMode || ignoreStopRequests) return;
            stopRequestedByOpMode = true;
            new Thread(() -> stop(), "ftcsim-opmode-stopper").start();
        }
    };

    public SimOpModeRunner(HardwareMap hardwareMap, Runnable beforeInit, Runnable afterStop) {
        this.hardwareMap = hardwareMap;
        this.beforeInit = beforeInit;
        this.afterStop = afterStop;
        RobotLog.threadPoolErrorHook = this::onThreadPoolError;
    }

    public SimGamepad gamepad1() { return gamepad1; }
    public SimGamepad gamepad2() { return gamepad2; }
    public State state() { return state; }
    public OpModeEntry entry() { return entry; }
    public OpMode activeOpMode() { return opMode; }
    public String error() { return error; }
    public void addTelemetryListener(TelemetryListener l) { telemetryListeners.add(l); }
    public void addStateListener(Consumer<State> l) { stateListeners.add(l); }
    public void addGamepadEffectListener(GamepadEffectListener l) { gamepadEffectListeners.add(l); }
    public double runtime() { return opMode == null ? 0 : opMode.getRuntime(); }
    public double secondsInState() { return (System.currentTimeMillis() - stateSince) / 1000.0; }

    private void setState(State s) {
        state = s; stateSince = System.currentTimeMillis();
        for (Consumer<State> l : stateListeners) { try { l.accept(s); } catch (RuntimeException ignored) {} }
    }

    public Snapshot snapshot() {
        RobotLog.GlobalWarningMessage w = RobotLog.getGlobalWarningMessage();
        OpModeEntry e = entry;
        return new Snapshot(state, e == null ? null : e.name, e == null ? null : e.flavor.name(), runtime(), error, telemetryLines, telemetryTime, w == null ? "" : w.message, RobotLog.getGlobalErrorMsg());
    }

    // ------------------------------------------------------------------ lifecycle
    /** Instantiates the OpMode and runs its init phase (like pressing INIT on the Driver Station). */
    public void init(OpModeEntry e) {
        synchronized (lock) {
            if (state == State.ESTOP) {
                // On a real robot the driver would press "Restart Robot" before the next INIT; do that for them.
                RobotLog.ii(TAG, "Robot restarted after emergency stop (previous error: %s)", error == null ? "" : error.split("\n")[0]);
                restartRobot();
            }
            if (opMode != null) stopInternal(false);
            OpMode instance;
            try {
                instance = e.clazz.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                Throwable cause = ex instanceof java.lang.reflect.InvocationTargetException ? ex.getCause() : ex;
                enterEstop("Could not create OpMode " + e.name + ": " + describe(cause), cause);
                return;
            }
            entry = e;
            error = null;
            stopRequestedByOpMode = false;
            ignoreStopRequests = false;
            RobotLog.clearGlobalWarningMsg();
            try { beforeInit.run(); } catch (RuntimeException ex) { RobotLog.ee(TAG, ex, "hardware reset before init failed"); }
            instance.hardwareMap = hardwareMap;
            // OpModeManagerImpl gives the OpMode its own gamepad instances (which own the effect queues the
            // event loop drains) and tags them so the Driver Station knows which controller to rumble.
            instance.gamepad1 = new Gamepad();
            instance.gamepad2 = new Gamepad();
            instance.gamepad1.setUser(org.firstinspires.ftc.robotcore.internal.ui.GamepadUser.ONE);
            instance.gamepad2.setUser(org.firstinspires.ftc.robotcore.internal.ui.GamepadUser.TWO);
            instance.gamepad1.setUserForEffects(org.firstinspires.ftc.robotcore.internal.ui.GamepadUser.ONE.id);
            instance.gamepad2.setUserForEffects(org.firstinspires.ftc.robotcore.internal.ui.GamepadUser.TWO.id);
            opModeGamepad1 = instance.gamepad1;
            opModeGamepad2 = instance.gamepad2;
            instance.internalOpModeServices = services;
            instance.newGamepadDataAvailable(gamepad1, gamepad2);
            telemetryLines = new ArrayList<>();
            opMode = instance;
            RobotLog.ii(TAG, RobotLog.OPMODE_START_TAG, e.name);
            RobotLog.ii(TAG, "Initializing %s (%s)", e.name, e.clazz.getName());
            instance.internalInit();
            setState(State.INIT);
            startEventLoop();
        }
    }

    /** Starts the initialised OpMode (the PLAY button). */
    public void start() {
        synchronized (lock) {
            OpMode om = opMode;
            if (om == null || state != State.INIT) return;
            RobotLog.ii(TAG, "Starting %s", entry.name);
            om.internalStart();
            setState(State.RUNNING);
        }
    }

    /** Stops the OpMode (the STOP button or the OpMode ending itself). */
    public void stop() {
        synchronized (lock) {
            if (state == State.ESTOP && opMode == null) { restartRobot(); return; }
            stopInternal(true);
        }
    }

    private void stopInternal(boolean keepSelection) {
        OpMode om = opMode;
        if (om == null) return;
        if (state == State.STOPPING) return;
        setState(State.STOPPING);
        ignoreStopRequests = true;
        RobotLog.ii(TAG, "Stopping %s", entry == null ? "OpMode" : entry.name);
        final Thread stopper = new Thread(() -> { ftcsim.hardware.HardwareBus.markExemptThread(); om.internalStop(); }, "ftcsim-internalStop");
        stopper.setDaemon(true);
        stopper.start();
        long timeout = om.msStuckDetectStop + 1500L;
        try { stopper.join(timeout); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        boolean stuck = stopper.isAlive();
        stopEventLoop();
        opModeGamepad1 = null;
        opModeGamepad2 = null;
        fireStopRumble();
        Throwable userException = om.exception != null ? om.exception : om.noClassDefFoundError;
        if (userException == null) userException = threadError;
        threadError = null;
        try { afterStop.run(); } catch (RuntimeException ex) { RobotLog.ee(TAG, ex, "post-stop hardware handling failed"); }
        RobotLog.ii(TAG, RobotLog.OPMODE_STOP_TAG, entry == null ? "" : entry.name);
        opMode = null;
        if (stuck) {
            enterEstop("User OpMode " + (entry == null ? "" : entry.name) + " did not stop within " + om.msStuckDetectStop + " ms of stop being requested (the Robot Controller app would restart now). Make sure your loops check opModeIsActive()/isStopRequested() and that you do not swallow InterruptedException.", null);
            return;
        }
        if (userException != null) {
            enterEstop("User code threw an uncaught exception: " + describe(userException), userException);
            return;
        }
        setState(State.IDLE);
    }

    private void enterEstop(String message, Throwable t) {
        error = message;
        if (t != null) { StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); error = message + "\n" + trimTrace(sw.toString()); RobotLog.ee(TAG, t, message); }
        else RobotLog.ee(TAG, message);
        RobotLog.setGlobalErrorMsg(message);
        setState(State.ESTOP);
    }

    private static String trimTrace(String trace) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : trace.split("\n")) {
            if (line.contains("com.qualcomm.robotcore.eventloop.opmode.OpModeInternal") || line.contains("java.util.concurrent.ThreadPoolExecutor") || line.contains("com.qualcomm.robotcore.util.ThreadPool")) continue;
            sb.append(line).append('\n');
            if (++n > 25) { sb.append("    ..."); break; }
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    /** Clears an emergency stop (the Driver Station's "Restart Robot"). */
    /** An Error (e.g. UnsatisfiedLinkError) that killed the OpMode thread; the SDK's thread pool only logs those. */
    private volatile Throwable threadError;

    private void onThreadPoolError(Throwable t) {
        if (opMode != null && (state == State.INIT || state == State.RUNNING) && threadError == null) threadError = t;
    }

    private static volatile SimOpModeRunner instance;
    { instance = this; }

    /** The OpMode currently in INIT or RUNNING, else null. */
    public static OpMode currentOpMode() {
        SimOpModeRunner r = instance;
        if (r == null) return null;
        State s = r.state;
        return (s == State.INIT || s == State.RUNNING) ? r.opMode : null;
    }

    /**
     * Prepares a nested OpMode (Pedro's tuning OpModes) to run on the current thread inside {@code host}:
     * it shares the host's services and is considered started so opModeIsActive() is true.
     */
    public static void adoptInline(OpMode nested, OpMode host) {
        nested.internalOpModeServices = host.internalOpModeServices;
        nested.isStarted = host.isStarted;
        nested.stopRequested = false;
    }

    public void restartRobot() {
        synchronized (lock) {
            if (opMode != null) stopInternal(false);
            error = null;
            RobotLog.forceClearGlobalErrorMsg();
            RobotLog.clearGlobalWarningMsg();
            setState(State.IDLE);
            RobotLog.ii(TAG, "Robot restarted");
        }
    }

    // ------------------------------------------------------------------ event loop
    private void startEventLoop() {
        stopEventLoop();
        Thread t = new Thread(this::eventLoop, "ftcsim-eventloop");
        t.setDaemon(true);
        eventLoopThread = t;
        t.start();
    }

    private void stopEventLoop() {
        Thread t = eventLoopThread;
        eventLoopThread = null;
        if (t != null && t != Thread.currentThread()) { t.interrupt(); }
    }

    private void eventLoop() {
        final Thread self = Thread.currentThread();
        long next = System.nanoTime();
        while (eventLoopThread == self && !self.isInterrupted()) {
            OpMode om = opMode;
            if (om == null) break;
            State s = state;
            if (s == State.INIT || s == State.RUNNING) {
                try {
                    // a paused simulation freezes robot code: an iterative OpMode stops between loop() calls
                    ftcsim.hardware.HardwareBus.gate();
                    om.newGamepadDataAvailable(gamepad1, gamepad2);
                    om.internalOnEventLoopIteration();
                    drainGamepadEffects();
                } catch (RuntimeException ex) {
                    RobotLog.ee(TAG, ex, "event loop iteration failed");
                }
                if ((om.opModeThreadFinished || threadError != null) && !stopRequestedByOpMode) {
                    // The OpMode thread ended (exception or a LinearOpMode returning): stop like the RC does.
                    stopRequestedByOpMode = true;
                    new Thread(this::stop, "ftcsim-opmode-stopper").start();
                }
            }
            next += 20_000_000L; // 50 Hz like the Driver Station gamepad packets
            long sleep = next - System.nanoTime();
            if (sleep > 0) { try { Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L)); } catch (InterruptedException e) { return; } }
            else next = System.nanoTime();
        }
    }

    // ------------------------------------------------------------------ gamepad effects
    /**
     * Grabs the rumble effects the OpMode queued ({@code gamepad1.rumble(...)}) the way
     * {@code FtcEventLoopHandler.gamepadEffects()} does on the Robot Controller: one poll per gamepad per
     * event loop pass. Both queues are 1-element evicting queues, so a tight loop of rumble calls
     * collapses to the latest effect instead of piling up.
     */
    private void drainGamepadEffects() {
        Gamepad gp1 = opModeGamepad1, gp2 = opModeGamepad2;
        if (gp1 != null) { Gamepad.RumbleEffect effect = gp1.rumbleQueue.poll(); if (effect != null) fireRumble(1, effect); }
        if (gp2 != null) { Gamepad.RumbleEffect effect = gp2.rumbleQueue.poll(); if (effect != null) fireRumble(2, effect); }
    }

    private void fireRumble(int gamepadId, Gamepad.RumbleEffect effect) {
        for (GamepadEffectListener l : gamepadEffectListeners) { try { l.onRumble(gamepadId, effect); } catch (RuntimeException ignored) {} }
    }

    private void fireStopRumble() {
        for (GamepadEffectListener l : gamepadEffectListeners) { try { l.onStopRumble(); } catch (RuntimeException ignored) {} }
    }

    // ------------------------------------------------------------------ telemetry
    private void publishTelemetry(TelemetryMessage msg) {
        // telemetry.update() is the other place nearly every OpMode passes through each loop
        ftcsim.hardware.HardwareBus.gate();
        Map<String, String> data = msg.getDataStrings();
        List<String> keys = new ArrayList<>(data.keySet());
        Collections.sort(keys);
        List<String> lines = new ArrayList<>(keys.size());
        for (String k : keys) lines.add(data.get(k));
        telemetryLines = lines;
        telemetryTime = System.currentTimeMillis();
        for (TelemetryListener l : telemetryListeners) { try { l.onTelemetry(lines, telemetryTime); } catch (RuntimeException ignored) {} }
    }

    public List<String> telemetryLines() { return telemetryLines; }
}
