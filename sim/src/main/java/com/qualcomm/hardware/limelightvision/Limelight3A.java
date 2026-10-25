package com.qualcomm.hardware.limelightvision;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.util.RobotLog;
import ftcsim.hardware.SimDevice;
import ftcsim.physics.Chassis;
import ftcsim.physics.Field;
import ftcsim.physics.Pose2;
import ftcsim.physics.Units;
import ftcsim.physics.Vec2;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/**
 * FTCSim replacement for the SDK's Limelight3A driver. Instead of polling a
 * camera over the network it renders the field's AprilTags into a Limelight
 * style JSON result from the simulated robot pose, which the SDK's own
 * {@link LLResult} / {@link LLStatus} parsers then consume unchanged.
 */
public class Limelight3A implements HardwareDevice, SimDevice {
    public static final String TAG = "Limelight3A";

    private final String name;
    private final Chassis chassis;
    private final Supplier<Field> field;
    private final Random random = new Random(7);

    // mounting on the robot (metres / radians)
    private volatile double mountX = 0, mountY = 0, mountZ = 0.25, mountYaw = 0, mountPitch = 0;
    private volatile double hfov = Math.toRadians(54.5), vfov = Math.toRadians(42);
    private volatile int imageWidth = 1280, imageHeight = 960;
    private volatile double maxRange = 150 * Units.INCH;
    private volatile double noiseXY = 0.4 * Units.INCH, noiseYawDeg = 0.4;

    private volatile boolean running;
    private volatile int pollRateHz = 100;
    private volatile int pipelineIndex = 0;
    private volatile double robotOrientationDeg = Double.NaN;
    private volatile boolean forceNoTargets;
    private volatile long lastUpdateNanos;
    private volatile LLResult latest;
    private volatile List<Integer> lastVisibleIds = new ArrayList<>();
    private volatile long startNanos = System.nanoTime();

    public Limelight3A(String name, Chassis chassis, Supplier<Field> field) {
        this.name = name; this.chassis = chassis; this.field = field;
    }

    public void configureMount(double xIn, double yIn, double zIn, double yawDeg, double pitchDeg) {
        mountX = Units.inToM(xIn); mountY = Units.inToM(yIn); mountZ = Units.inToM(zIn); mountYaw = Math.toRadians(yawDeg); mountPitch = Math.toRadians(pitchDeg);
    }
    public void configureOptics(double hfovDeg, double vfovDeg, double maxRangeIn) { hfov = Math.toRadians(hfovDeg); vfov = Math.toRadians(vfovDeg); maxRange = Units.inToM(maxRangeIn); }
    public void configureNoise(double xyIn, double yawDeg) { noiseXY = Units.inToM(xyIn); noiseYawDeg = yawDeg; }

    // ---- SDK API ----
    public synchronized void start() { running = true; startNanos = System.nanoTime(); }
    public synchronized void pause() { running = false; }
    public synchronized void stop() { running = false; }
    public boolean isRunning() { return running; }
    public synchronized void setPollRateHz(int hz) { pollRateHz = Math.max(1, Math.min(250, hz)); }
    public long getTimeSinceLastUpdate() { return (System.nanoTime() - lastUpdateNanos) / 1_000_000L; }
    public boolean isConnected() { return true; }
    public LLResult getLatestResult() {
        if (!running) return latest;
        long now = System.nanoTime();
        long period = 1_000_000_000L / pollRateHz;
        LLResult r = latest;
        if (r == null || now - lastUpdateNanos >= period) {
            r = compute();
            latest = r;
            lastUpdateNanos = now;
        }
        return r;
    }
    public LLStatus getStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("cameraQuat", new JSONObject().put("w", 1.0).put("x", 0.0).put("y", 0.0).put("z", 0.0));
            o.put("cid", 0); o.put("cpu", 35.0); o.put("finalYaw", Double.isNaN(robotOrientationDeg) ? 0.0 : robotOrientationDeg);
            o.put("fps", running ? 90.0 : 0.0); o.put("hwType", 3); o.put("ignoreNT", 0); o.put("interfaceNeedsRefresh", 0);
            o.put("name", "limelight-sim"); o.put("pipeImgCount", 0); o.put("pipelineIndex", pipelineIndex);
            o.put("pipelineType", "pipe_apriltag"); o.put("ram", 40.0); o.put("snapshotMode", 0); o.put("temp", 45.0);
            return new LLStatus(o);
        } catch (Exception e) { return new LLStatus(); }
    }
    public boolean reloadPipeline() { return true; }
    public boolean pipelineSwitch(int index) { if (index < 0 || index > 9) return false; pipelineIndex = index; return true; }
    public boolean captureSnapshot(String snapname) { RobotLog.ii(TAG, "[sim] captureSnapshot(%s)", snapname); return true; }
    public boolean deleteSnapshots() { return true; }
    public boolean deleteSnapshot(String snapname) { return true; }
    public boolean updatePythonInputs(double i0, double i1, double i2, double i3, double i4, double i5, double i6, double i7) { return true; }
    public boolean updatePythonInputs(double[] inputs) { return true; }
    public boolean updateRobotOrientation(double yaw) { robotOrientationDeg = yaw; return true; }
    public boolean uploadPipeline(String pipeline, Integer index) { return true; }
    public boolean uploadFieldmap(LLFieldMap fieldmap, Integer index) { return true; }
    public boolean uploadPython(String python, Integer index) { return true; }
    public LLResultTypes.CalibrationResult getCalDefault() { return null; }
    public LLResultTypes.CalibrationResult getCalFile() { return null; }
    public LLResultTypes.CalibrationResult getCalEEPROM() { return null; }
    public LLResultTypes.CalibrationResult getCalLatest() { return null; }
    public void shutdown() { running = false; }
    @Override public Manufacturer getManufacturer() { return Manufacturer.LimelightVision; }
    @Override public String getDeviceName() { return "Limelight 3A"; }
    @Override public String getConnectionInfo() { return "FTCSim virtual Ethernet (172.29.0.1)"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() { running = false; }

    // ---- simulation ----
    private static final class Seen {
        Field.AprilTag tag; double tx, ty, ta, dist; Vec2 camTag; double zRel; double txp, typ;
    }

    private LLResult compute() {
        try {
            Pose2 p = chassis.pose();
            Vec2 cam = new Vec2(p.x, p.y).plus(new Vec2(mountX, mountY).rotated(p.heading));
            double camYaw = Pose2.normalize(p.heading + mountYaw);
            double focal = (imageWidth / 2.0) / Math.tan(hfov / 2);
            double focalV = (imageHeight / 2.0) / Math.tan(vfov / 2);
            List<Seen> seen = new ArrayList<>();
            Field f = field.get();
            if (f != null && !forceNoTargets) {
                for (Field.AprilTag t : f.tags) {
                    if (!f.rules().isTagVisible(f, t)) continue;
                    Vec2 tagPos = new Vec2(Units.inToM(t.x), Units.inToM(t.y));
                    Vec2 rel = tagPos.minus(cam);
                    double dist = rel.norm();
                    if (dist < 0.1 || dist > maxRange) continue;
                    // tag must face the camera
                    Vec2 normal = Vec2.polar(1, Math.toRadians(t.yawDeg));
                    double facing = normal.dot(rel.times(-1 / dist));
                    if (facing < 0.25) continue;
                    Vec2 inCam = rel.rotated(-camYaw); // x forward, y left
                    if (inCam.x <= 0) continue;
                    double az = Math.atan2(inCam.y, inCam.x);
                    if (Math.abs(az) > hfov / 2) continue;
                    double dz = Units.inToM(t.z) - mountZ;
                    double el = Math.atan2(dz, inCam.x) - mountPitch;
                    if (Math.abs(el) > vfov / 2) continue;
                    Seen s = new Seen();
                    s.tag = t; s.dist = Math.sqrt(dist * dist + dz * dz); s.camTag = inCam; s.zRel = dz;
                    s.tx = -Math.toDegrees(az);   // Limelight: positive to the right
                    s.ty = Math.toDegrees(el);
                    double sizeM = Units.inToM(t.sizeIn);
                    double projected = (sizeM * focal / s.dist) * (sizeM * focalV / s.dist) * facing;
                    s.ta = Math.min(100.0, projected / (imageWidth * (double) imageHeight) * 100.0);
                    s.txp = imageWidth / 2.0 + Math.tan(az) * focal * -1;
                    s.typ = imageHeight / 2.0 - Math.tan(el) * focalV;
                    seen.add(s);
                }
            }
            seen.sort((a, b) -> Double.compare(b.ta, a.ta));
            List<Integer> ids = new ArrayList<>();
            for (Seen s : seen) ids.add(s.tag.id);
            lastVisibleIds = ids;

            JSONObject o = new JSONObject();
            boolean valid = !seen.isEmpty();
            double nowMs = (System.nanoTime() - startNanos) / 1e6;
            o.put("v", valid ? 1 : 0);
            o.put("pID", pipelineIndex);
            o.put("pipelineType", "pipe_apriltag");
            o.put("tl", 8.5); o.put("cl", 12.0); o.put("ts", nowMs);
            o.put("focus_metric", 0.0);
            Seen best = valid ? seen.get(0) : null;
            o.put("tx", best != null ? best.tx : 0.0); o.put("ty", best != null ? best.ty : 0.0);
            o.put("txnc", best != null ? best.tx : 0.0); o.put("tync", best != null ? best.ty : 0.0);
            o.put("ta", best != null ? best.ta : 0.0);
            // MegaTag1 pose estimate (field space, metres, degrees) from the true pose plus noise
            double bx = p.x, by = p.y, byaw = Math.toDegrees(p.heading);
            JSONArray botpose = new JSONArray();
            JSONArray botposeMt2 = new JSONArray();
            double avgDist = 0, avgArea = 0;
            if (valid) {
                double nx = bx + random.nextGaussian() * noiseXY, ny = by + random.nextGaussian() * noiseXY;
                double nyaw = byaw + random.nextGaussian() * noiseYawDeg;
                for (double d : new double[] { nx, ny, mountZ, 0.0, 0.0, nyaw }) botpose.put(d);
                double yaw2 = Double.isNaN(robotOrientationDeg) ? nyaw : robotOrientationDeg;
                for (double d : new double[] { nx, ny, mountZ, 0.0, 0.0, yaw2 }) botposeMt2.put(d);
                for (Seen s : seen) { avgDist += s.dist; avgArea += s.ta; }
                avgDist /= seen.size(); avgArea /= seen.size();
            } else {
                for (int i = 0; i < 6; i++) { botpose.put(0.0); botposeMt2.put(0.0); }
            }
            o.put("botpose", botpose); o.put("botpose_wpiblue", botpose); o.put("botpose_wpired", botpose);
            o.put("botpose_orb", botposeMt2); o.put("botpose_orb_wpiblue", botposeMt2); o.put("botpose_orb_wpired", botposeMt2);
            o.put("botpose_tagcount", seen.size()); o.put("botpose_span", seen.size() > 1 ? 1.0 : 0.0);
            o.put("botpose_avgdist", avgDist); o.put("botpose_avgarea", avgArea);
            JSONArray std = new JSONArray(); for (int i = 0; i < 6; i++) std.put(valid ? 0.02 : 0.0);
            o.put("stdev_mt1", std); o.put("stdev_mt2", std);
            o.put("t6c_rs", poseArray(mountX, mountY, mountZ, 0, Math.toDegrees(mountPitch), Math.toDegrees(mountYaw)));
            JSONArray fiducials = new JSONArray();
            for (Seen s : seen) {
                JSONObject fd = new JSONObject();
                fd.put("fID", s.tag.id); fd.put("fam", "36h11"); fd.put("skew", 0.0);
                fd.put("ta", s.ta); fd.put("tx", s.tx); fd.put("ty", s.ty); fd.put("tx_nocross", s.tx); fd.put("ty_nocross", s.ty);
                fd.put("txp", s.txp); fd.put("typ", s.typ);
                // target in camera space (x right, y down, z forward) and in robot space (x forward, y left, z up)
                fd.put("t6t_cs", poseArray(-s.camTag.y, -s.zRel, s.camTag.x, 0, 0, 0));
                Vec2 inRobot = s.camTag.rotated(mountYaw).plus(new Vec2(mountX, mountY));
                fd.put("t6t_rs", poseArray(inRobot.x, inRobot.y, s.zRel + mountZ, 0, 0, Pose2.normalizeDeg(s.tag.yawDeg - Math.toDegrees(p.heading))));
                // camera / robot in target space (target: z out of the tag face)
                double tagYaw = Math.toRadians(s.tag.yawDeg);
                Vec2 camRelTag = cam.minus(new Vec2(Units.inToM(s.tag.x), Units.inToM(s.tag.y))).rotated(-tagYaw);
                fd.put("t6c_ts", poseArray(-camRelTag.y, -(mountZ - Units.inToM(s.tag.z)), camRelTag.x, 0, 0, Pose2.normalizeDeg(Math.toDegrees(camYaw) - s.tag.yawDeg + 180)));
                Vec2 robRelTag = new Vec2(p.x, p.y).minus(new Vec2(Units.inToM(s.tag.x), Units.inToM(s.tag.y))).rotated(-tagYaw);
                fd.put("t6r_ts", poseArray(-robRelTag.y, -(0 - Units.inToM(s.tag.z)), robRelTag.x, 0, 0, Pose2.normalizeDeg(Math.toDegrees(p.heading) - s.tag.yawDeg + 180)));
                fd.put("t6r_fs", botpose);
                JSONArray pts = new JSONArray();
                double half = Units.inToM(s.tag.sizeIn) / 2 * focal / s.dist;
                double[][] corners = { { s.txp - half, s.typ + half }, { s.txp + half, s.typ + half }, { s.txp + half, s.typ - half }, { s.txp - half, s.typ - half } };
                for (double[] c : corners) { JSONArray pt = new JSONArray(); pt.put(c[0]); pt.put(c[1]); pts.put(pt); }
                fd.put("pts", pts);
                fiducials.put(fd);
            }
            o.put("Fiducial", fiducials);
            o.put("Retro", new JSONArray()); o.put("Classifier", new JSONArray()); o.put("Detector", new JSONArray());
            o.put("Barcode", new JSONArray()); o.put("PythonOut", new JSONArray());
            LLResult r = LLResult.parse(o);
            if (r != null) r.setControlHubTimeStamp(System.currentTimeMillis());
            return r;
        } catch (Exception e) {
            RobotLog.ee(TAG, e, "simulated result generation failed");
            return null;
        }
    }

    private static JSONArray poseArray(double x, double y, double z, double roll, double pitch, double yaw) {
        JSONArray a = new JSONArray();
        a.put(x); a.put(y); a.put(z); a.put(roll); a.put(pitch); a.put(yaw);
        return a;
    }

    // ---- SimDevice ----
    @Override public String simName() { return name; }
    @Override public String simType() { return "Limelight3A"; }
    @Override public String simHub() { return "Control Hub"; }
    @Override public int simPort() { return 0; }
    @Override public void fillView(Map<String, Object> v) {
        v.put("running", running); v.put("pipeline", pipelineIndex); v.put("pollRateHz", pollRateHz);
        v.put("visibleTags", lastVisibleIds); v.put("forceNoTargets", forceNoTargets);
        v.put("mountIn", String.format("(%.1f, %.1f, %.1f) yaw %.0f", Units.mToIn(mountX), Units.mToIn(mountY), Units.mToIn(mountZ), Math.toDegrees(mountYaw)));
        v.put("input", true);
    }
    @Override public boolean applyInput(String key, Object value) {
        if ("forceNoTargets".equals(key)) { forceNoTargets = SimDevice.asBool(value, forceNoTargets); return true; }
        return false;
    }
}
