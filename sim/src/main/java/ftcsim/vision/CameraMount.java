package ftcsim.vision;

/** Where a camera sits on the robot and what it can see. Distances in inches, angles in degrees. */
public final class CameraMount {
    public double xIn, yIn, zIn = 8;      // robot frame: x forward, y left, z up
    public double yawDeg, pitchDeg;       // yaw CCW from robot forward; pitch positive = looking up
    public double hfovDeg = 70.4, vfovDeg = 43.3; // Logitech C920-class webcam
    public int widthPx = 640, heightPx = 480;
    public double maxRangeIn = 200;
    public double noiseIn = 0.3, noiseDeg = 0.3;

    public CameraMount copy() {
        CameraMount m = new CameraMount();
        m.xIn = xIn; m.yIn = yIn; m.zIn = zIn; m.yawDeg = yawDeg; m.pitchDeg = pitchDeg; m.hfovDeg = hfovDeg; m.vfovDeg = vfovDeg;
        m.widthPx = widthPx; m.heightPx = heightPx; m.maxRangeIn = maxRangeIn; m.noiseIn = noiseIn; m.noiseDeg = noiseDeg;
        return m;
    }

    @Override public String toString() {
        return String.format("(%.1f, %.1f, %.1f) in yaw %.0f pitch %.0f fov %.0fx%.0f", xIn, yIn, zIn, yawDeg, pitchDeg, hfovDeg, vfovDeg);
    }

    /** Structured mount for the frontend (replaces parsing the formatted toString()). */
    public java.util.Map<String, Object> toView() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("xIn", xIn); m.put("yIn", yIn); m.put("zIn", zIn);
        m.put("yawDeg", yawDeg); m.put("pitchDeg", pitchDeg);
        m.put("hfovDeg", hfovDeg); m.put("vfovDeg", vfovDeg);
        return m;
    }
}
