package ftcsim.vision;

import ftcsim.physics.Field;
import ftcsim.physics.Pose2;
import ftcsim.physics.Units;
import ftcsim.physics.World;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagGameDatabase;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AprilTagSimTest {
    @Test public void seesTheRedGoalTagWhenFacingIt() {
        World w = new World();
        w.field = Field.decode();
        SimVision.install(w.chassis, () -> w.field);
        w.chassis.setPose(new Pose2(0, 0, Math.toRadians(136)));
        AprilTagProcessor p = new AprilTagProcessor.Builder().setTagLibrary(AprilTagGameDatabase.getDecodeTagLibrary()).setOutputUnits(DistanceUnit.INCH, AngleUnit.DEGREES).build();
        assertTrue(p instanceof SimVisionProcessor, "the simulator's AprilTagProcessorImpl should be built");
        CameraMount mount = new CameraMount();
        ((SimVisionProcessor) p).simFrame(mount, 640, 480, System.nanoTime());
        List<AprilTagDetection> dets = p.getDetections();
        System.out.println("detections: " + dets.size() + " " + ((SimVisionProcessor) p).simSummary());
        assertEquals(1, dets.size(), "tag 24 should be visible");
        AprilTagDetection d = dets.get(0);
        System.out.printf("ftcPose x=%.1f y=%.1f z=%.1f range=%.1f bearing=%.1f elevation=%.1f yaw=%.1f%n", d.ftcPose.x, d.ftcPose.y, d.ftcPose.z, d.ftcPose.range, d.ftcPose.bearing, d.ftcPose.elevation, d.ftcPose.yaw);
        assertEquals(80.6, d.ftcPose.y, 3.0);
        assertEquals(0, d.ftcPose.bearing, 3.0);
        assertEquals(21.5, d.ftcPose.z, 2.0);
        assertEquals(0, d.robotPose.getPosition().x, 2.0);
        assertEquals(136, d.robotPose.getOrientation().getYaw(AngleUnit.DEGREES), 2.0);
        // turn away: nothing visible
        w.chassis.setPose(new Pose2(0, 0, Math.toRadians(-90)));
        ((SimVisionProcessor) p).simFrame(mount, 640, 480, System.nanoTime());
        assertEquals(0, p.getDetections().size());
        // the obelisk (tag 21 selected) from the middle of the field facing +x
        w.chassis.setPose(new Pose2(Units.inToM(30), 0, 0));
        ((SimVisionProcessor) p).simFrame(mount, 640, 480, System.nanoTime());
        System.out.println("obelisk view: " + ((SimVisionProcessor) p).simSummary());
        assertEquals(1, p.getDetections().size());
        assertEquals(21, idOf(p.getDetections().get(0)));
    }

    private static int idOf(AprilTagDetection d) {
        try { return d.getClass().getField("id").getInt(d); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
