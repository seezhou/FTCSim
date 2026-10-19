package ftcsim.vision;

import ftcsim.config.RobotConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a motor or servo sits on the robot and how it moves. Distances in inches, angles in
 * degrees, robot frame (x forward, y left, z up) — the same frame as {@link CameraMount}, so a
 * camera and an intake arm read the same way in the robot configuration.
 *
 * <p>The camera mount has a fixed pose. This one has a joint: a motor spins continuously about
 * its axis, and a servo sweeps between two angles. {@link #axis} says which of the robot's axes
 * the part turns about and {@link #minDeg}/{@link #maxDeg} map a servo's 0..1 position onto that
 * sweep, so the 3D view can show the arm moving rather than just existing.
 *
 * <p>{@link #parent} nests parts. A intake finger whose parent is the arm it is bolted to turns
 * with the arm when the arm's servo sweeps, because the 3D view mirrors this hierarchy with
 * Three.js parent transforms.
 */
public final class DeviceMount {
    /** Robot frame: x forward, y left, z up. */
    public double xIn, yIn, zIn;
    /** Robot frame axis the part turns about: "x" (roll, e.g. a wheel) or "z" (pitch, e.g. an arm). */
    public String axis = "x";
    /** Servo travel endpoints in degrees, mapped from position 0..1. Equal means no sweep (motor-like). */
    public double minDeg = 0, maxDeg = 0;
    /** Name of the part this one is mounted on; null attaches straight to the chassis. */
    public String parent;
    /** How to draw the part: "cylinder" (motor/servo body) or "box". */
    public String shape = "cylinder";
    public double lengthIn = 2, diameterIn = 1.4;

    public DeviceMount copy() {
        DeviceMount m = new DeviceMount();
        m.xIn = xIn; m.yIn = yIn; m.zIn = zIn; m.axis = axis;
        m.minDeg = minDeg; m.maxDeg = maxDeg; m.parent = parent;
        m.shape = shape; m.lengthIn = lengthIn; m.diameterIn = diameterIn;
        return m;
    }

    /** True when the part sweeps between two angles (a servo) rather than spinning freely. */
    public boolean sweeps() { return Math.abs(maxDeg - minDeg) > 1e-6; }

    /**
     * The mount as the JSON object the web view reads, or null when this device has no mount in
     * the configuration (the view then leaves it out of the robot model rather than guessing a pose).
     *
     * <p>This is a real object, not a formatted string like the camera's. It arrives on the same
     * 20 Hz {@code devices} message either way, but a map needs no regular expression to take
     * apart and keeps working when another field is added.
     */
    public Map<String, Object> toView() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("x", xIn); v.put("y", yIn); v.put("z", zIn);
        v.put("axis", axis);
        v.put("minDeg", minDeg); v.put("maxDeg", maxDeg);
        v.put("shape", shape);
        v.put("lengthIn", lengthIn); v.put("diameterIn", diameterIn);
        if (parent != null && !parent.isEmpty()) v.put("parent", parent);
        return v;
    }

    @Override public String toString() {
        return String.format("(%.1f, %.1f, %.1f) axis %s", xIn, yIn, zIn, axis);
    }

    /**
     * Read a {@code mount} block off a device entry. Mounts live under {@code params.mount}, the
     * same place {@link SimClassFactory}'s camera and TOF mounts already do, so a device that has
     * both a sensor and a mechanism keeps one consistent spelling.
     *
     * <p>Returns null when the device declares no mount — absence is meaningful, and the caller
     * uses it to leave the device out of the 3D robot rather than parking it at the origin.
     */
    public static DeviceMount fromSpec(RobotConfig.Device spec, String defaultAxis) {
        Map<String, Object> m = spec.map("mount");
        if (m == null) return null;
        DeviceMount d = new DeviceMount();
        d.xIn = RobotConfig.Device.num(m, "x", 0);
        d.yIn = RobotConfig.Device.num(m, "y", 0);
        d.zIn = RobotConfig.Device.num(m, "z", 0);
        d.axis = RobotConfig.Device.str(m, "axis", defaultAxis);
        d.minDeg = RobotConfig.Device.num(m, "minDeg", 0);
        d.maxDeg = RobotConfig.Device.num(m, "maxDeg", 0);
        d.shape = RobotConfig.Device.str(m, "shape", "cylinder");
        d.lengthIn = RobotConfig.Device.num(m, "lengthIn", 2);
        d.diameterIn = RobotConfig.Device.num(m, "diameterIn", 1.4);
        d.parent = RobotConfig.Device.str(m, "parent", null);
        return d;
    }
}