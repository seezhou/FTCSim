package ftcsim.hardware;

import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import ftcsim.config.RobotConfig;
import ftcsim.physics.Units;
import ftcsim.physics.World;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SDK inverts raw power/encoder sign for CCW motor types (goBILDA etc.), so a user-level power must
 * still drive the wheel the way the team's {@code setDirection} calls say. Regression test for the
 * field-centric strafe that went the wrong way.
 */
public class MotorDirectionTest {
    private static SimHardware decodeHardware(World w) throws Exception {
        RobotConfig cfg = RobotConfig.load(new File(System.getProperty("ftcsim.home", "."), "configs/Decode.json"));
        return new SimHardware(w, cfg, () -> w.field);
    }

    @Test public void userPowersFollowTeamDirections() throws Exception {
        World w = new World();
        w.field = null;
        SimHardware hw = decodeHardware(w);
        DcMotorEx lf = hw.hardwareMap.get(DcMotorEx.class, "frontLeft"), rf = hw.hardwareMap.get(DcMotorEx.class, "frontRight");
        DcMotorEx lr = hw.hardwareMap.get(DcMotorEx.class, "backLeft"), rr = hw.hardwareMap.get(DcMotorEx.class, "backRight");
        lf.setDirection(DcMotorSimple.Direction.REVERSE); lr.setDirection(DcMotorSimple.Direction.REVERSE);
        // gm0 strafe left: FL-, FR+, BL+, BR-
        lf.setPower(-1); rf.setPower(1); lr.setPower(1); rr.setPower(-1);
        for (int i = 0; i < 1000; i++) w.step();
        double vy = Units.mToIn(w.chassis.velocityRobot().y), vx = Units.mToIn(w.chassis.velocityRobot().x);
        assertTrue(vy > 20, "should strafe left (robot +y), vy=" + vy);
        assertTrue(Math.abs(vx) < 1, "no forward motion, vx=" + vx);
        // encoders: the user sees positive ticks for the wheels that rolled "forward" in their own sense
        assertTrue(rf.getCurrentPosition() > 100, "rf ticks " + rf.getCurrentPosition());
        assertTrue(lf.getCurrentPosition() < -100, "lf ticks " + lf.getCurrentPosition());
        assertTrue(rf.getVelocity() > 100, "rf velocity " + rf.getVelocity());
        // drive forward: all +1
        lf.setPower(1); rf.setPower(1); lr.setPower(1); rr.setPower(1);
        for (int i = 0; i < 1500; i++) w.step();
        assertTrue(Units.mToIn(w.chassis.velocityRobot().x) > 40, "forward vx=" + Units.mToIn(w.chassis.velocityRobot().x));
        assertTrue(lf.getVelocity() > 500 && rf.getVelocity() > 500 && lr.getVelocity() > 500 && rr.getVelocity() > 500, "all wheels report positive velocity when driving forward");
    }

    @Test public void cwMotorTypesAreNotInverted() throws Exception {
        World w = new World();
        w.field = null;
        SimHardware hw = decodeHardware(w);
        DcMotorEx m = hw.hardwareMap.get(DcMotorEx.class, "frontRight");
        SimDcMotor sm = (SimDcMotor) m;
        assertEquals(-1, sm.state().orientationSign, "goBILDA motors are CCW in the SDK");
        assertEquals(-1, sm.state().physicalSign);
        m.setDirection(DcMotorSimple.Direction.REVERSE);
        assertEquals(1, sm.state().physicalSign, "REVERSE on a CCW motor cancels the inversion at the raw level");
    }
}
