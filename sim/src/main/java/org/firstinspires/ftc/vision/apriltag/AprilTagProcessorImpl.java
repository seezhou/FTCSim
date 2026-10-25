package org.firstinspires.ftc.vision.apriltag;

import android.graphics.Canvas;
import com.qualcomm.robotcore.util.RobotLog;
import ftcsim.physics.Field;
import ftcsim.physics.Pose2;
import ftcsim.physics.Units;
import ftcsim.physics.Vec2;
import ftcsim.vision.CameraMount;
import ftcsim.vision.SimVision;
import ftcsim.vision.SimVisionProcessor;
import org.firstinspires.ftc.robotcore.external.matrices.GeneralMatrixF;
import org.firstinspires.ftc.robotcore.external.matrices.MatrixF;
import org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix;
import org.firstinspires.ftc.robotcore.external.matrices.VectorF;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.opencv.core.Mat;
import org.opencv.core.Point;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FTCSim replacement for the SDK's AprilTag detector. Detections are computed geometrically from the
 * true robot pose, the camera mount and the tag library (tags the library leaves unplaced, such as
 * the DECODE obelisk, take their position from the simulated field).
 */
public class AprilTagProcessorImpl extends AprilTagProcessor implements SimVisionProcessor {
    public static final String TAG = "AprilTagProcessorImpl";
    /** Minimum facing dot-product for a tag to be considered visible (see Camera.see). */
    public static final double MIN_FACING = 0.25;

    private final OpenGLMatrix cameraPoseInverse;
    private final double fx, fy;
    private final DistanceUnit outputUnitsLength;
    private final AngleUnit outputUnitsAngle;
    private final AprilTagLibrary tagLibrary;
    private final Random random = new Random(11);
    private volatile ArrayList<AprilTagDetection> detections = new ArrayList<>();
    private final AtomicBoolean fresh = new AtomicBoolean(false);
    private volatile String summary = "";
    private boolean warnedLookingUp;

    private static final Constructor<?> DETECTION_CTOR;
    private static final boolean DETECTION_HAS_UNIT;
    // SDK 12 cluster API (absent on SDK 11): resolved reflectively so one source compiles against both.
    private static final java.lang.reflect.Method GET_ALL_CLUSTERS;
    private static final Constructor<?> CLUSTER_DETECTION_CTOR;
    private static final java.lang.reflect.Field CLUSTER_NAME, CLUSTER_POSITION, CLUSTER_ORIENTATION, CLUSTER_UNIT;
    static {
        java.lang.reflect.Method gac = null; Constructor<?> cdc = null; java.lang.reflect.Field cn = null, cp = null, co = null, cu = null;
        try {
            gac = AprilTagLibrary.class.getMethod("getAllClusters");
            Class<?> meta = Class.forName("org.firstinspires.ftc.vision.apriltag.AprilTagClusterMetadata");
            Class<?> det = Class.forName("org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection");
            cdc = det.getConstructor(int.class, meta, DistanceUnit.class, AprilTagPoseFtc.class, AprilTagPoseRaw.class, Pose3D.class, long.class);
            cn = meta.getField("name"); cp = meta.getField("fieldPosition"); co = meta.getField("fieldOrientation"); cu = meta.getField("distanceUnit");
        } catch (Throwable ignored) { gac = null; cdc = null; }
        GET_ALL_CLUSTERS = gac; CLUSTER_DETECTION_CTOR = cdc; CLUSTER_NAME = cn; CLUSTER_POSITION = cp; CLUSTER_ORIENTATION = co; CLUSTER_UNIT = cu;
    }
    static {
        Constructor<?> c = null; boolean hasUnit = false;
        try {
            Class<?> single = Class.forName("org.firstinspires.ftc.vision.apriltag.AprilTagSingleDetection");
            c = single.getConstructor(int.class, int.class, float.class, Point.class, Point[].class, AprilTagMetadata.class, AprilTagPoseFtc.class, AprilTagPoseRaw.class, Pose3D.class, long.class, DistanceUnit.class);
            hasUnit = true;
        } catch (Throwable ignored) {
            try {
                c = AprilTagDetection.class.getConstructor(int.class, int.class, float.class, Point.class, Point[].class, AprilTagMetadata.class, AprilTagPoseFtc.class, AprilTagPoseRaw.class, Pose3D.class, long.class);
            } catch (NoSuchMethodException e) { throw new ExceptionInInitializerError(e); }
        }
        DETECTION_CTOR = c; DETECTION_HAS_UNIT = hasUnit;
    }

    public AprilTagProcessorImpl(OpenGLMatrix cameraPose, double fx, double fy, double cx, double cy, DistanceUnit outputUnitsLength, AngleUnit outputUnitsAngle,
                                 AprilTagLibrary tagLibrary, boolean drawAxes, boolean drawCube, boolean drawOutline, boolean drawTagID,
                                 TagFamily tagFamily, int threads, boolean suppressCalibrationWarnings) {
        this.cameraPoseInverse = cameraPose;
        this.fx = fx; this.fy = fy;
        this.outputUnitsLength = outputUnitsLength == null ? DistanceUnit.INCH : outputUnitsLength;
        this.outputUnitsAngle = outputUnitsAngle == null ? AngleUnit.DEGREES : outputUnitsAngle;
        this.tagLibrary = tagLibrary == null ? AprilTagGameDatabase.getCurrentGameTagLibrary() : tagLibrary;
    }

    // ---- VisionProcessor (no real frames) ----
    @Override public void init(int width, int height, CameraCalibration calibration) {}
    @Override public Object processFrame(Mat frame, long captureTimeNanos) { return null; }
    @Override public void onDrawFrame(Canvas canvas, int onscreenWidth, int onscreenHeight, float scaleBmpPxToCanvasPx, float scaleCanvasDensity, Object userContext) {}
    @Override public void setDecimation(float decimation) {}
    @Override public void setPoseSolver(PoseSolver poseSolver) {}
    @Override public int getPerTagAvgPoseSolveTime() { return 0; }
    @Override public ArrayList<AprilTagDetection> getDetections() { return detections; }
    @Override public ArrayList<AprilTagDetection> getFreshDetections() { return fresh.getAndSet(false) ? detections : null; }
    @Override public String simSummary() { return summary; }

    /** A tag's placement on the field: inches, yaw = direction the face points (CCW from +X). */
    private static final class Placement { double x, y, z, yawDeg, sizeIn; }

    /** Result of projecting a point on the field into the camera. */
    private static final class Sight {
        double az, el, range3, xF, yF, zF, txp, typ, facing;
    }

    /** Camera geometry for one simulated frame. */
    private final class Camera {
        double camYaw, camPitch, camZ, focal, focalV, hfov, vfov; Vec2 camWorld; int w, h; double maxRange;
        /** Whether a face at (x, y, z) pointing along yawDeg is inside the camera's view; null when not. */
        Sight see(double x, double y, double z, double yawDeg, double minFacing) {
            Vec2 rel = new Vec2(x - camWorld.x, y - camWorld.y);
            double dist = rel.norm();
            if (dist < 2) return null;
            Vec2 normal = Vec2.polar(1, Math.toRadians(yawDeg));
            double facing = normal.dot(rel.times(-1 / dist));
            if (facing < minFacing) return null;
            Vec2 inCam = rel.rotated(-camYaw);
            if (inCam.x <= 0) return null;
            double az = Math.atan2(inCam.y, inCam.x);
            if (Math.abs(az) > hfov / 2) return null;
            double dz = z - camZ;
            double el = Math.atan2(dz, inCam.x) - camPitch;
            if (Math.abs(el) > vfov / 2) return null;
            Sight s = new Sight();
            s.az = az; s.el = el; s.facing = facing;
            s.range3 = Math.sqrt(inCam.x * inCam.x + inCam.y * inCam.y + dz * dz);
            if (s.range3 > maxRange) return null;
            s.xF = -inCam.y;
            s.yF = inCam.x * Math.cos(camPitch) + dz * Math.sin(camPitch);
            s.zF = -inCam.x * Math.sin(camPitch) + dz * Math.cos(camPitch);
            s.txp = w / 2.0 - Math.tan(az) * focal; s.typ = h / 2.0 - Math.tan(el) * focalV;
            return s;
        }
    }

    private Placement placement(AprilTagMetadata m) {
        VectorF p = m.fieldPosition;
        Quaternion q = m.fieldOrientation;
        boolean placed = p != null && (Math.abs(p.get(0)) > 1e-6 || Math.abs(p.get(1)) > 1e-6 || Math.abs(p.get(2)) > 1e-6);
        Placement pl = new Placement();
        DistanceUnit unit = m.distanceUnit == null ? DistanceUnit.INCH : m.distanceUnit;
        pl.sizeIn = DistanceUnit.INCH.fromUnit(unit, m.tagsize);
        if (placed && q != null) {
            pl.x = DistanceUnit.INCH.fromUnit(unit, p.get(0)); pl.y = DistanceUnit.INCH.fromUnit(unit, p.get(1)); pl.z = DistanceUnit.INCH.fromUnit(unit, p.get(2));
            VectorF z = q.applyToVector(new VectorF(0, 0, 1)); // the tag's +Z points into the tag; the face points the other way
            pl.yawDeg = Math.toDegrees(Math.atan2(-z.get(1), -z.get(0)));
            return pl;
        }
        Field f = SimVision.field();
        if (f == null) return null;
        for (Field.AprilTag t : f.tags) {
            if (t.id != m.id) continue;
            if (!f.rules().isTagVisible(f, t)) return null;
            pl.x = t.x; pl.y = t.y; pl.z = t.z; pl.yawDeg = t.yawDeg; if (pl.sizeIn <= 0) pl.sizeIn = t.sizeIn;
            return pl;
        }
        return null;
    }

    @Override public void simFrame(CameraMount mount, int widthPx, int heightPx, long frameNanos) {
        Pose2 p = SimVision.robotPose();
        double rxIn = Units.mToIn(p.x), ryIn = Units.mToIn(p.y);
        // camera on the robot: from setCameraPose() when the team gave one, else the configured mount
        double camX = mount.xIn, camY = mount.yIn, camZ = mount.zIn, camYawRel = Math.toRadians(mount.yawDeg), camPitch = Math.toRadians(mount.pitchDeg);
        if (cameraPoseInverse != null) {
            OpenGLMatrix cam = cameraPoseInverse.inverted();
            float fX = cam.get(0, 2), fY = cam.get(1, 2), fZ = cam.get(2, 2); // camera optical axis in the robot frame (X right, Y forward, Z up)
            boolean identity = Math.abs(cam.get(0, 3)) < 1e-6 && Math.abs(cam.get(1, 3)) < 1e-6 && Math.abs(cam.get(2, 3)) < 1e-6 && Math.abs(fZ - 1) < 1e-6;
            if (!identity) {
                if (Math.abs(fZ) > 0.99) {
                    if (!warnedLookingUp) { warnedLookingUp = true; RobotLog.ww(TAG, "[sim] setCameraPose() leaves the camera looking straight up/down; using the configured camera mount instead"); }
                } else {
                    camX = cam.get(1, 3); camY = -cam.get(0, 3); camZ = cam.get(2, 3);
                    camYawRel = Math.atan2(-fX, fY);
                    camPitch = Math.atan2(fZ, Math.hypot(fX, fY));
                }
            }
        }
        Camera cam = new Camera();
        cam.camWorld = new Vec2(rxIn, ryIn).plus(new Vec2(camX, camY).rotated(p.heading));
        cam.camYaw = Pose2.normalize(p.heading + camYawRel);
        cam.camPitch = camPitch; cam.camZ = camZ; cam.w = widthPx; cam.h = heightPx; cam.maxRange = mount.maxRangeIn;
        cam.hfov = Math.toRadians(mount.hfovDeg); cam.vfov = Math.toRadians(mount.vfovDeg);
        cam.focal = fx > 0 ? fx : (widthPx / 2.0) / Math.tan(cam.hfov / 2);
        cam.focalV = fy > 0 ? fy : (heightPx / 2.0) / Math.tan(cam.vfov / 2);
        DistanceUnit du = outputUnitsLength; AngleUnit au = outputUnitsAngle;
        ArrayList<AprilTagDetection> found = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        for (AprilTagMetadata meta : tagLibrary.getAllTags()) {
            Placement t = placement(meta);
            if (t == null) continue;
            Sight s = cam.see(t.x, t.y, t.z, t.yawDeg, MIN_FACING);
            if (s == null) continue;
            double apparentPx = t.sizeIn * cam.focal / s.range3 * s.facing;
            if (apparentPx < 6) continue;
            double noise = mount.noiseIn * Math.max(1, s.range3 / 48);
            double yaw = Pose2.normalizeDeg(t.yawDeg - Math.toDegrees(cam.camYaw) + 180) + random.nextGaussian() * mount.noiseDeg;
            Poses poses = poses(s, noise, yaw, 0, p, rxIn, ryIn, frameNanos, mount);
            double half = t.sizeIn / 2 * cam.focal / s.range3;
            Point center = new Point(s.txp, s.typ);
            Point[] corners = { new Point(s.txp - half, s.typ + half), new Point(s.txp + half, s.typ + half), new Point(s.txp + half, s.typ - half), new Point(s.txp - half, s.typ - half) };
            float margin = (float) Math.max(5, 120 - s.range3 / 2);
            try {
                Object det = DETECTION_HAS_UNIT
                    ? DETECTION_CTOR.newInstance(meta.id, 0, margin, center, corners, meta, poses.ftc, poses.raw, poses.robot, frameNanos, du)
                    : DETECTION_CTOR.newInstance(meta.id, 0, margin, center, corners, meta, poses.ftc, poses.raw, poses.robot, frameNanos);
                found.add((AprilTagDetection) det);
                ids.add(String.valueOf(meta.id));
            } catch (ReflectiveOperationException e) {
                RobotLog.ee(TAG, e, "[sim] could not create AprilTagDetection");
            }
        }

        // SDK 12 clusters: the library's own placement when it has one, else the simulated field's element that carries it
        if (GET_ALL_CLUSTERS != null && CLUSTER_DETECTION_CTOR != null) {
            try {
                Object[] clusters = (Object[]) GET_ALL_CLUSTERS.invoke(tagLibrary);
                for (Object meta : clusters) {
                    String name = String.valueOf(CLUSTER_NAME.get(meta));
                    Field.Cluster fc = clusterPlacement(name, meta);
                    if (fc == null) continue;
                    int visible = 0;
                    for (double[] off : fc.memberOffsets) {
                        double[] mp = fc.memberPosition(off);
                        Sight ms = cam.see(mp[0], mp[1], mp[2], fc.yawDeg, 0.15);
                        if (ms != null && fc.tagSizeIn * cam.focal / ms.range3 * ms.facing >= 6) visible++;
                    }
                    if (visible == 0) continue;
                    Sight s = cam.see(fc.x, fc.y, fc.z, fc.yawDeg, -1); // origin of the cluster (the CELL opening)
                    if (s == null) continue;
                    double noise = mount.noiseIn * Math.max(1, s.range3 / 48) / Math.sqrt(visible);
                    double yaw = Pose2.normalizeDeg(fc.yawDeg - Math.toDegrees(cam.camYaw) + 180) + random.nextGaussian() * mount.noiseDeg;
                    double pitch = fc.pitchDeg - Math.toDegrees(cam.camPitch);
                    Poses poses = poses(s, noise, yaw, pitch, p, rxIn, ryIn, frameNanos, mount);
                    int percent = (int) Math.round(100.0 * visible / fc.memberOffsets.length);
                    Object det = CLUSTER_DETECTION_CTOR.newInstance(percent, meta, du, poses.ftc, poses.raw, poses.robot, frameNanos);
                    found.add((AprilTagDetection) det);
                    ids.add(name + " " + percent + "%");
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                RobotLog.ee(TAG, e, "[sim] cluster detection failed");
            }
        }
        detections = found;
        fresh.set(true);
        summary = ids.isEmpty() ? "no tags" : "tags " + ids;
    }

    private static final class Poses { AprilTagPoseFtc ftc; AprilTagPoseRaw raw; Pose3D robot; }

    private Poses poses(Sight s, double noise, double yaw, double pitch, Pose2 p, double rxIn, double ryIn, long frameNanos, CameraMount mount) {
        DistanceUnit du = outputUnitsLength; AngleUnit au = outputUnitsAngle;
        double xF = s.xF + random.nextGaussian() * noise, yF = s.yF + random.nextGaussian() * noise, zF = s.zF + random.nextGaussian() * noise * 0.5;
        double range = Math.sqrt(xF * xF + yF * yF + zF * zF);
        double bearing = Math.toDegrees(Math.atan2(-xF, yF));
        double elevation = Math.toDegrees(Math.atan2(zF, Math.hypot(xF, yF)));
        double roll = random.nextGaussian() * mount.noiseDeg;
        pitch += random.nextGaussian() * mount.noiseDeg;
        Poses out = new Poses();
        out.ftc = new AprilTagPoseFtc(du.fromInches(xF), du.fromInches(yF), du.fromInches(zF), au.fromDegrees(yaw), au.fromDegrees(pitch), au.fromDegrees(roll), du.fromInches(range), au.fromDegrees(bearing), au.fromDegrees(elevation));
        double yr = Math.toRadians(yaw);
        MatrixF R = new GeneralMatrixF(3, 3, new float[] { (float) Math.cos(yr), 0, (float) Math.sin(yr), 0, 1, 0, (float) -Math.sin(yr), 0, (float) Math.cos(yr) });
        out.raw = new AprilTagPoseRaw(du.fromInches(xF), du.fromInches(-zF), du.fromInches(yF), R);
        double nx = rxIn + random.nextGaussian() * noise * 0.5, ny = ryIn + random.nextGaussian() * noise * 0.5;
        double nh = Math.toDegrees(p.heading) + random.nextGaussian() * mount.noiseDeg;
        out.robot = new Pose3D(new Position(du, du.fromInches(nx), du.fromInches(ny), du.fromInches(0), frameNanos), new YawPitchRollAngles(au, au.fromDegrees(nh), 0, 0, frameNanos));
        return out;
    }

    /** Where a cluster is: the library's placement when it gives one, else the simulated field's cluster of the same name. */
    private Field.Cluster clusterPlacement(String name, Object meta) throws ReflectiveOperationException {
        VectorF pos = (VectorF) CLUSTER_POSITION.get(meta);
        Quaternion q = (Quaternion) CLUSTER_ORIENTATION.get(meta);
        boolean placed = pos != null && (Math.abs(pos.get(0)) > 1e-6 || Math.abs(pos.get(1)) > 1e-6 || Math.abs(pos.get(2)) > 1e-6);
        if (placed && q != null) {
            DistanceUnit unit = (DistanceUnit) CLUSTER_UNIT.get(meta);
            if (unit == null) unit = DistanceUnit.INCH;
            VectorF z = q.applyToVector(new VectorF(0, 0, 1));
            double yaw = Math.toDegrees(Math.atan2(-z.get(1), -z.get(0)));
            double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -z.get(2)))));
            // member offsets: reuse the simulated field's layout for this cluster when known, else a single origin tag
            Field f = SimVision.field();
            double[][] offsets = { { 0, 0, 0 } }; double size = 3.25;
            if (f != null) for (Field.Cluster c : f.clusters) if (c.name.equalsIgnoreCase(name)) { offsets = c.memberOffsets; size = c.tagSizeIn; }
            return new Field.Cluster(name, DistanceUnit.INCH.fromUnit(unit, pos.get(0)), DistanceUnit.INCH.fromUnit(unit, pos.get(1)), DistanceUnit.INCH.fromUnit(unit, pos.get(2)), yaw, pitch, new int[0], offsets, size);
        }
        Field f = SimVision.field();
        if (f == null) return null;
        for (Field.Cluster c : f.clusters) if (c.name.equalsIgnoreCase(name)) return c;
        return null;
    }
}
