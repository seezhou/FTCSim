package ftcsim.hardware;

import ftcsim.config.RobotConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code launcher} block a robot configuration may put on the {@code params} of any device.
 * <p>
 * A launcher is described by what it does, not by how it is built: where the ball leaves the robot,
 * how fast it leaves, and what the team code does that means "fire". A double flywheel, a
 * single-wheel shooter and a catapult arm are three configurations of this one block — the
 * simulator never models the mechanism in between, which is the only way one code path can serve
 * every robot.
 * <p>
 * Nothing here is required: a {@code launcher} block naming nothing but a trigger servo still works,
 * falling back to the part it is written on for the muzzle and the wheel.
 */
public final class LauncherSpec {
    /** The device carrying the block; the default muzzle and speed source when none is named. */
    public final String owner;
    /** Muzzle in the robot frame: x forward, y left, z up, inches. */
    public final double exitX, exitY, exitZ;
    /** Device whose rotation sets the ball's speed — normally the flywheel motor. */
    public final String speedFrom;
    /** Flywheel diameter in inches: the ball leaves at omega x radius x slip. */
    public final double wheelDiameterIn;
    /** How much of the wheel's surface speed actually reaches the ball. */
    public final double slip;
    /** Launch angle above horizontal, and yaw away from the robot's own forward, in degrees. */
    public final double pitchDeg, yawDeg;
    /** Fixed launch speed in in/s. Positive replaces the wheel calculation outright. */
    public final double speedInPerSec;
    public final double ballRadiusIn;
    /** Mass in grams, or 0 when it is not known: it only changes how much the air slows the ball. */
    public final double ballMassG;
    /** Balls the robot carries: firing spends one, reloading refills. */
    public final int capacity;
    /** Servo whose movement means "push a ball in". Null fires on flywheel speed alone. */
    public final String triggerServo;
    /** Servo position (0..1) that counts as the push having happened. */
    public final double triggerPosition;
    /**
     * Hysteresis below {@link #triggerPosition} the servo has to fall back to before the next push
     * can register. A servo travels at secondsFullRange, so the push is a ramp, not a step: the
     * trigger has to be a level crossing, or a slow servo would never be seen to move far enough
     * between two polls.
     */
    public final double triggerDelta;
    /** Flywheel speed in rpm a shot needs; below it the trigger is ignored. */
    public final double triggerMinRpm;
    /** Seconds between balls of a salvo: one trigger fires the whole magazine, this far apart. */
    public final double salvoIntervalSec;

    private LauncherSpec(String owner, double exitX, double exitY, double exitZ, String speedFrom,
                         double wheelDiameterIn, double slip, double pitchDeg, double yawDeg,
                         double speedInPerSec, double ballRadiusIn, double ballMassG, int capacity, String triggerServo,
                         double triggerPosition, double triggerDelta, double triggerMinRpm, double salvoIntervalSec) {
        this.owner = owner;
        this.exitX = exitX; this.exitY = exitY; this.exitZ = exitZ;
        this.speedFrom = speedFrom;
        this.wheelDiameterIn = wheelDiameterIn;
        this.slip = slip;
        this.pitchDeg = pitchDeg; this.yawDeg = yawDeg;
        this.speedInPerSec = speedInPerSec;
        this.ballRadiusIn = ballRadiusIn;
        this.ballMassG = ballMassG;
        this.capacity = capacity;
        this.triggerServo = triggerServo;
        this.triggerPosition = triggerPosition;
        this.triggerDelta = triggerDelta;
        this.triggerMinRpm = triggerMinRpm;
        this.salvoIntervalSec = salvoIntervalSec;
    }

    /** The first device in the configuration carrying a {@code launcher} block, or null. */
    public static LauncherSpec from(RobotConfig config) {
        if (config == null || config.devices == null) return null;
        Map<String, RobotConfig.Device> byName = new HashMap<>();
        for (RobotConfig.Device d : config.devices) if (d.name != null) byName.put(d.name, d);
        for (RobotConfig.Device d : config.devices) {
            Map<String, Object> l = d.map("launcher");
            if (l != null) return of(d, l, byName);
        }
        return null;
    }

    private static LauncherSpec of(RobotConfig.Device owner, Map<String, Object> l, Map<String, RobotConfig.Device> byName) {
        Map<String, Object> exit = child(l, "exit");
        Map<String, Object> mount = owner.map("mount");
        // No exit: the ball leaves where the part itself is mounted, which is where it would.
        double ex = pick(exit, "x", mount, "x", 0);
        double ey = pick(exit, "y", mount, "y", 0);
        double ez = pick(exit, "z", mount, "z", 6);

        boolean ownerIsMotor = owner.type != null && owner.type.contains("Motor");
        String speedFrom = str(l, "speedFrom", ownerIsMotor ? owner.name : "");
        RobotConfig.Device wheel = speedFrom.isEmpty() ? null : byName.get(speedFrom);
        Map<String, Object> wheelMount = wheel != null ? wheel.map("mount") : null;
        double dia = num(l, "wheelDiameterIn", num(wheelMount, "diameterIn", 1.4));
        if (dia <= 0) dia = 1.4;

        double slip = num(l, "slip", 1.0);
        if (slip <= 0) slip = 1.0;

        double radius = num(l, "ballRadiusIn", 3.0);
        if (radius <= 0) radius = 3.0;
        double mass = num(l, "ballMassG", 0);

        int capacity = (int) Math.round(num(l, "capacity", 5));
        if (capacity < 1) capacity = 5;

        Map<String, Object> trig = child(l, "trigger");
        String servo = trig == null ? null : str(trig, "servo", null);
        if (servo != null && servo.isEmpty()) servo = null;
        double at = num(trig, "position", 0.5);
        double delta = num(trig, "deltaPosition", 0.15);
        if (delta <= 0) delta = 0.15;
        double minRpm = num(trig, "minRpm", 0);

        double salvoInterval = num(l, "salvoIntervalSec", 0.5);
        if (salvoInterval <= 0) salvoInterval = 0.5;

        return new LauncherSpec(owner.name, ex, ey, ez, speedFrom, dia, slip,
            num(l, "pitchDeg", 0), num(l, "yawDeg", 0), num(l, "speedInPerSec", 0),
            radius, mass, capacity, servo, at, delta, minRpm, salvoInterval);
    }

    // --- Map reading; every value is optional and every type is whatever Gson produced ---
    private static double pick(Map<String, Object> first, String k1, Map<String, Object> second, String k2, double def) {
        if (first != null && first.get(k1) != null) return num(first, k1, def);
        return num(second, k2, def);
    }

    private static double num(Map<String, Object> m, String key, double def) {
        return RobotConfig.Device.num(m, key, def);
    }

    private static String str(Map<String, Object> m, String key, String def) {
        return RobotConfig.Device.str(m, key, def);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }
}
