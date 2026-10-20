package ftcsim.hardware;

import com.qualcomm.robotcore.util.RobotLog;
import ftcsim.physics.Chassis;
import ftcsim.physics.MotorState;
import ftcsim.physics.Pose2;
import ftcsim.physics.ServoState;
import ftcsim.physics.Units;
import ftcsim.physics.Vec2;
import ftcsim.physics.World;

/**
 * Decides when the robot fires and puts a ball into the world when it does.
 * <p>
 * The team's code is never modified and the SDK has no "I launched a ball" call, so a shot is
 * inferred from what the code does to its hardware: a push servo moving, or a flywheel spinning up
 * past a speed. That is the whole trick — the simulator watches, it is never told.
 * <p>
 * The shot itself is described by {@link LauncherSpec}, never by the mechanism, so one controller
 * serves every launcher a team can configure.
 */
public final class LauncherController {
    private static final String TAG = "FTCSim";
    /** Simulated seconds between two shots, so one push cannot empty the magazine. */
    private static final double COOLDOWN_S = 0.25;
    /** Flywheel revs (rpm) a shot needs; below this the push is ignored. */
    private static final double DEFAULT_MIN_RPM = 0;

    private final World world;
    private LauncherSpec spec;
    private int remaining;
    /** Launch angle set from the UI, or null to use the one in the robot configuration. */
    private Double pitchOverride;
    private boolean servoArmed;
    /** A push has fed a ball into the wheel and it is waiting for the wheel to come up to speed. */
    private boolean pushPending;
    private double lastFireAt = Double.NEGATIVE_INFINITY;
    private boolean flywheelArmed;
    /** A salvo in progress: balls still to release, and the sim-time the next one may leave. */
    private int salvoLeft = 0;
    private double salvoNextAt = Double.NEGATIVE_INFINITY;

    public LauncherController(World world) { this.world = world; }

    public void configure(LauncherSpec spec) {
        this.spec = spec;
        this.remaining = spec == null ? 0 : spec.capacity;
        pitchOverride = null;
        resetTrigger();
    }

    /** Aims the launcher from the UI. Null goes back to the angle in the configuration. */
    public void setPitchDeg(Double deg) { pitchOverride = deg; }

    /** The angle a ball leaves at, in degrees: whatever the UI last asked for, else the configuration. */
    public double pitchDeg() {
        if (pitchOverride != null) return pitchOverride;
        return spec == null ? 0 : spec.pitchDeg;
    }

    public LauncherSpec spec() { return spec; }
    public boolean enabled() { return spec != null; }
    public int remaining() { return spec == null ? 0 : remaining; }
    public int capacity() { return spec == null ? 0 : spec.capacity; }

    /** Refills the magazine (a new match starts with a full one). */
    public void reload() { if (spec != null) { remaining = spec.capacity; salvoLeft = 0; } }

    /** One ball picked up off the floor: it goes back in the magazine, capped at its capacity. */
    public void collect() { if (spec != null && remaining < spec.capacity) remaining++; }

    /** Fires the whole magazine as a salvo: the first ball now, the rest 0.5 s apart. */
    public double fire() {
        if (spec == null || remaining <= 0) return 0;
        if (salvoLeft > 0) return 0;            // a salvo is already running
        salvoLeft = remaining;
        remaining = 0;
        salvoNextAt = world.simTime();
        return releaseOne();
    }

    /** Releases one ball of the current salvo at the live muzzle and velocity. Returns its speed. */
    private double releaseOne() {
        if (salvoLeft <= 0) return 0;
        Chassis c = world.chassis;
        Pose2 pose = c.pose();
        double h = pose.heading, cosH = Math.cos(h), sinH = Math.sin(h);

        // Muzzle in the field frame: the exit is stored in the robot frame (x forward, y left).
        double mx = Units.mToIn(pose.x) + spec.exitX * cosH - spec.exitY * sinH;
        double my = Units.mToIn(pose.y) + spec.exitX * sinH + spec.exitY * cosH;
        double mz = spec.exitZ;

        // Surface speed of the wheel is what throws the ball: omega x radius, less what slips.
        double speed = spec.speedInPerSec > 0
            ? spec.speedInPerSec
            : Math.abs(omegaOf(spec.speedFrom)) * (spec.wheelDiameterIn / 2.0) * spec.slip;
        if (speed < 0) speed = 0;

        double yaw = Math.toRadians(spec.yawDeg), pitch = Math.toRadians(pitchDeg());
        double cp = Math.cos(pitch);
        double fx = cp * Math.cos(yaw), fy = cp * Math.sin(yaw);
        double vx = speed * (fx * cosH - fy * sinH);
        double vy = speed * (fx * sinH + fy * cosH);
        double vz = speed * Math.sin(pitch);

        // A moving robot carries the ball: firing while driving adds the chassis velocity.
        Vec2 vr = c.velocityRobot();
        double rvx = Units.mToIn(vr.x), rvy = Units.mToIn(vr.y);
        vx += rvx * cosH - rvy * sinH;
        vy += rvx * sinH + rvy * cosH;

        world.spawnProjectile(mx, my, mz, vx, vy, vz, spec.ballRadiusIn, spec.ballMassG);
        salvoLeft--;
        lastFireAt = world.simTime();
        salvoNextAt = salvoLeft > 0 ? world.simTime() + spec.salvoIntervalSec : Double.NEGATIVE_INFINITY;
        RobotLog.ii(TAG, "Launcher released a ball (salvo: %d left) at %.0f in/s", salvoLeft, speed);
        return speed;
    }

    /** Called at the UI's 20 Hz cadence: look for a shot. The magazine only fills by reloading
     *  (start of match) or by the robot driving over a ball it earlier launched (see World). */
    public void poll() {
        if (spec == null) return;
        // keep a running salvo going on its configured cadence, independent of the trigger logic below
        if (salvoLeft > 0 && world.simTime() >= salvoNextAt) releaseOne();
        if (remaining <= 0 || world.simTime() - lastFireAt < COOLDOWN_S) return;

        if (spec.triggerServo != null) {
            // One push = one ball, and the push is a ramp rather than a step: a servo only travels
            // at secondsFullRange, so watching how far it moved between two polls misses slow ones.
            // Trigger on the level crossing instead, and rearm only once it is back at rest.
            double pos = servoPosition(spec.triggerServo);
            if (Double.isNaN(pos)) return;
            if (pos >= spec.triggerPosition) {
                if (!servoArmed) { servoArmed = true; pushPending = true; }
                // The push feeds the ball into the wheel; it leaves when the wheel is fast enough.
                // Waiting for minRpm instead of firing on the push itself is what stops a team's
                // spin-up delay from throwing the ball at a fraction of the speed it should have.
                if (pushPending && rpmReached() && fire() > 0) pushPending = false;
            } else if (pos <= spec.triggerPosition - spec.triggerDelta) {
                servoArmed = false;
                pushPending = false;
            }
            return;
        }
        // No push servo: the flywheel reaching speed is the shot.
        double rpm = Math.abs(rpmOf(spec.speedFrom));
        double min = spec.triggerMinRpm > 0 ? spec.triggerMinRpm : DEFAULT_MIN_RPM;
        if (min > 0) {
            if (rpm >= min && !flywheelArmed) { flywheelArmed = true; fire(); }
            else if (rpm < min * 0.8) flywheelArmed = false;
        }
    }

    private void resetTrigger() {
        servoArmed = false;
        pushPending = false;
        flywheelArmed = false;
        salvoLeft = 0;
        salvoNextAt = Double.NEGATIVE_INFINITY;
        lastFireAt = Double.NEGATIVE_INFINITY;
    }

    private boolean rpmReached() {
        if (spec.triggerMinRpm <= 0) return true;
        return Math.abs(rpmOf(spec.speedFrom)) >= spec.triggerMinRpm;
    }

    /** Output shaft speed in rad/s, physical direction. */
    private double omegaOf(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) return 0;
        for (MotorState m : world.motors) if (deviceName.equals(m.name)) return m.physicalOmega();
        return 0;
    }

    private double rpmOf(String deviceName) {
        return omegaOf(deviceName) * 60.0 / (2 * Math.PI);
    }

    /** Commanded servo position 0..1, or NaN when the device is not there. */
    private double servoPosition(String deviceName) {
        for (ServoState s : world.servos) if (deviceName.equals(s.name)) return s.position();
        return Double.NaN;
    }
}
