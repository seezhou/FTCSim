package ftcsim.hardware;

import ftcsim.config.RobotConfig;
import ftcsim.physics.MotorState;
import ftcsim.physics.MotorType;
import ftcsim.physics.Pose2;
import ftcsim.physics.ServoState;
import ftcsim.physics.Units;
import ftcsim.physics.World;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The launcher's arithmetic: where the ball leaves from, how fast, and what it costs the magazine.
 * The trigger itself is not tested here — it is inferred from live devices, which is a job for a
 * running robot, not a unit test.
 */
public class LauncherControllerTest {
    private static final String FLYWHEEL = "flywheel";

    private static World worldWithFlywheel(double rpm) {
        World w = new World();
        MotorState m = new MotorState(0, FLYWHEEL, MotorType.byName("goBILDA_6000"));
        m.setPhysicalOmega(Units.rpmToRadPerSec(rpm));
        w.motors.add(m);
        return w;
    }

    private static RobotConfig config(Map<String, Object> launcher) {
        RobotConfig c = new RobotConfig();
        RobotConfig.Device d = new RobotConfig.Device(FLYWHEEL, "DcMotorEx");
        d.params.put("launcher", launcher);
        c.devices.add(d);
        return c;
    }

    private static LauncherSpec spec(Map<String, Object> launcher) {
        return LauncherSpec.from(config(launcher));
    }

    private static Map<String, Object> launcher(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void ballLeavesAtTheWheelSurfaceSpeed() {
        World w = worldWithFlywheel(6000);
        w.chassis.setPose(new Pose2(0, 0, 0));   // facing +x, so "forward" is +x
        // 2 in wheel at 6000 rpm, no slip: v = omega x radius
        LauncherController c = new LauncherController(w);
        c.configure(spec(launcher("speedFrom", FLYWHEEL, "wheelDiameterIn", 2.0, "slip", 1.0)));
        double speed = c.fire();
        double omega = Units.rpmToRadPerSec(6000);
        assertEquals(omega * 1.0, speed, 1e-6);
        assertEquals(1, w.projectiles.size());
        // facing +x (heading 0), flat: all of it is forward, none of it is up
        assertEquals(speed, w.projectiles.get(0).vx, 1e-6);
        assertEquals(0.0, w.projectiles.get(0).vz, 1e-6);
    }

    @Test
    public void slipAndPitchAreApplied() {
        World w = worldWithFlywheel(3000);
        LauncherController c = new LauncherController(w);
        c.configure(spec(launcher("speedFrom", FLYWHEEL, "wheelDiameterIn", 2.0, "slip", 0.5, "pitchDeg", 30.0)));
        double speed = c.fire();
        assertEquals(Units.rpmToRadPerSec(3000) * 0.5, speed, 1e-6);
        assertTrue(w.projectiles.get(0).vz > 0, "a positive pitch throws the ball upward");
    }

    @Test
    public void magazineRunsOutAndReloads() {
        World w = worldWithFlywheel(6000);
        LauncherController c = new LauncherController(w);
        c.configure(spec(launcher("speedFrom", FLYWHEEL, "capacity", 2)));
        assertEquals(2, c.remaining());
        assertTrue(c.fire() > 0);
        assertTrue(c.fire() > 0);
        assertEquals(0, c.remaining());
        assertEquals(0.0, c.fire(), 1e-9);   // empty: nothing leaves
        assertEquals(2, w.projectiles.size());
        c.reload();
        assertEquals(2, c.remaining());
    }

    @Test
    public void exitFollowsTheRobotFrame() {
        World w = worldWithFlywheel(6000);
        // facing +y (heading 90°): the robot's left is the field's +x
        w.chassis.setPose(new Pose2(0, 0, Math.toRadians(90)));
        LauncherController c = new LauncherController(w);
        c.configure(spec(launcher("speedFrom", FLYWHEEL, "exit", Map.of("x", 0.0, "y", 5.0, "z", 6.0))));
        c.fire();
        assertEquals(-5.0, w.projectiles.get(0).x, 1e-6);
        assertEquals(0.0, w.projectiles.get(0).y, 1e-6);
        assertEquals(6.0, w.projectiles.get(0).z, 1e-6);
    }

    @Test
    public void noLauncherMeansNoBalls() {
        World w = worldWithFlywheel(6000);
        LauncherController c = new LauncherController(w);
        c.configure(null);
        assertFalse(c.enabled());
        assertEquals(0.0, c.fire(), 1e-9);
        assertTrue(w.projectiles.isEmpty());
    }

    @Test
    public void oneServoPushFiresOneBall() {
        World w = worldWithFlywheel(3000);
        ServoState pusher = new ServoState(0, "pusher");
        pusher.setPosition(0.0);
        w.servos.add(pusher);
        LauncherController c = new LauncherController(w);
        c.configure(spec(launcher("speedFrom", FLYWHEEL, "wheelDiameterIn", 2.0, "slip", 1.0,
            "trigger", Map.of("servo", "pusher", "position", 0.5, "minRpm", 250.0))));

        c.poll();                       // first look only records where the servo is
        assertEquals(0, w.projectiles.size());
        pusher.setPosition(0.5);        // the push
        c.poll();
        assertEquals(1, w.projectiles.size(), "a push past the threshold should fire one ball");
        // holding the servo there must not empty the magazine: one push, one ball
        c.poll();
        assertEquals(1, w.projectiles.size());
    }

    @Test
    public void aFlywheelBelowTheThresholdDoesNotFire() {
        World w = worldWithFlywheel(100);   // below minRpm
        ServoState pusher = new ServoState(0, "pusher");
        pusher.setPosition(0.0);
        w.servos.add(pusher);
        LauncherController c = new LauncherController(w);
        c.configure(spec(launcher("speedFrom", FLYWHEEL, "wheelDiameterIn", 2.0, "slip", 1.0,
            "trigger", Map.of("servo", "pusher", "position", 0.5, "minRpm", 250.0))));
        c.poll();
        pusher.setPosition(0.5);
        c.poll();
        assertEquals(0, w.projectiles.size(), "a slow flywheel should not launch");
    }

    @Test
    public void aServoThatRampsStillFiresOnce() {
        World w = worldWithFlywheel(4000);
        ServoState pusher = new ServoState(0, "pusher");
        pusher.setPosition(0.0);
        w.servos.add(pusher);
        LauncherController c = new LauncherController(w);
        c.configure(spec(launcher("speedFrom", FLYWHEEL, "wheelDiameterIn", 2.0, "slip", 1.0,
            "trigger", Map.of("servo", "pusher", "position", 0.5, "minRpm", 250.0))));
        // A real servo travels at secondsFullRange: it ramps to the fire position over several
        // polls, so no single step is a large jump.
        for (double p = 0.0; p <= 0.85; p += 0.05) { pusher.setPosition(p); c.poll(); }
        assertEquals(1, w.projectiles.size(), "a ramping push should fire exactly one ball");
    }

    /**
     * The field only ever holds the balls the robot started with: a ball that lands on the floor
     * and is driven over disappears, and the magazine grows back by one (capped at its capacity).
     */
    @Test
    public void drivingOverABallPicksItUp() {
        World w = worldWithFlywheel(6000);
        w.chassis.setPose(new Pose2(0, 0, 0));   // robot at the origin, facing +x
        LauncherController c = new LauncherController(w);
        c.configure(spec(launcher("speedFrom", FLYWHEEL, "capacity", 4, "ballRadiusIn", 1.4)));
        c.fire();
        assertEquals(3, c.remaining());
        // a loose ball resting on the floor, right under the chassis
        w.spawnProjectile(0, 0, 1.4, 0, 0, 0, 1.4);
        w.step();
        assertEquals(1, w.projectiles.size(), "the shot is still in the air; only the loose ball was taken");
        assertEquals(1, w.pickupEvents.size(), "the floor ball was picked up");
        // the UI broadcast loop turns the event into a magazine refill (capped at capacity)
        Integer id;
        while ((id = w.pickupEvents.poll()) != null) c.collect();
        assertEquals(4, c.remaining());
    }

    @Test
    public void aBallAwayFromTheRobotIsNotPickedUp() {
        World w = worldWithFlywheel(6000);
        w.chassis.setPose(new Pose2(0, 0, 0));
        LauncherController c = new LauncherController(w);
        c.configure(spec(launcher("speedFrom", FLYWHEEL, "capacity", 4, "ballRadiusIn", 1.4)));
        // a loose ball well clear of the chassis footprint (the body is ~18 in long)
        w.spawnProjectile(25, 0, 1.4, 0, 0, 0, 1.4);
        w.step();
        assertEquals(1, w.projectiles.size(), "a ball beside the robot stays on the field");
        assertEquals(0, w.pickupEvents.size());
    }
}
