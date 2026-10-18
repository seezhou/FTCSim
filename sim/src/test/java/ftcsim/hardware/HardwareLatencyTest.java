package ftcsim.hardware;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import ftcsim.config.RobotConfig;
import ftcsim.physics.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Hub transactions cost the robot code time, and a paused simulation freezes it. */
public class HardwareLatencyTest {
    private World world;
    private SimHardware hw;

    @BeforeEach public void setUp() throws Exception {
        world = new World();
        world.field = null;
        RobotConfig cfg = RobotConfig.load(new File(System.getProperty("ftcsim.home", "."), "configs/Decode.json"));
        hw = new SimHardware(world, cfg, () -> world.field, () -> 21);
        HardwareBus.install(world);
        HardwareBus.configure(true, 2.0, 2.5, 2.0, 2.5);
        HardwareBus.resetCounters();
    }

    @AfterEach public void tearDown() {
        world.setPaused(false);
        world.stop();
        HardwareBus.configure(true, HardwareBus.DEFAULT_BULK_READ_MS, HardwareBus.DEFAULT_WRITE_MS, HardwareBus.DEFAULT_READ_MS, HardwareBus.DEFAULT_I2C_MS);
        HardwareBus.resetCounters();
    }

    @Test public void writesCostAboutTwoAndAHalfMilliseconds() {
        DcMotorEx m = hw.hardwareMap.get(DcMotorEx.class, "frontLeft");
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) m.setPower(0.5);
        double ms = (System.nanoTime() - t0) / 1e6;
        assertEquals(20, HardwareBus.writeCount(), "one transaction per setPower");
        assertEquals(50.0, HardwareBus.ioMillis(), 1e-6, "20 writes at 2.5 ms is 50 ms of hub time");
        // Wall clock, on whatever machine this runs on: the bounds only have to catch a unit error or
        // a wait that never happens, not benchmark the box. A wait rounded up to the 15.6 ms OS timer
        // tick lands near 300 ms instead, which is exactly what this test caught on Windows.
        assertTrue(ms > 40 && ms < 150, "20 writes should take about 50 ms, took " + ms);
    }

    @Test public void bulkCachingSavesTransactions() {
        LynxModule hub = hw.hardwareMap.get(LynxModule.class, "Control Hub");
        DcMotorEx a = hw.hardwareMap.get(DcMotorEx.class, "frontLeft"), b = hw.hardwareMap.get(DcMotorEx.class, "frontRight");
        hub.setBulkCachingMode(LynxModule.BulkCachingMode.OFF);
        HardwareBus.resetCounters();
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) { a.getCurrentPosition(); b.getCurrentPosition(); }
        double offMs = (System.nanoTime() - t0) / 1e6;
        assertEquals(10, HardwareBus.readCount(), "every read goes to the hub with caching off");

        hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);
        HardwareBus.resetCounters();
        t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) { hub.clearBulkCache(); a.getCurrentPosition(); b.getCurrentPosition(); }
        double manualMs = (System.nanoTime() - t0) / 1e6;
        assertEquals(5, HardwareBus.bulkReadCount(), "one bulk read per loop serves both motors");
        assertTrue(manualMs < offMs, "bulk caching should be faster: " + manualMs + " vs " + offMs);
    }

    @Test public void latencyCanBeTurnedOff() {
        HardwareBus.configure(false, 2.0, 2.5, 2.0, 2.5);
        DcMotorEx m = hw.hardwareMap.get(DcMotorEx.class, "frontLeft");
        long t0 = System.nanoTime();
        for (int i = 0; i < 50; i++) m.setPower(0.1);
        double ms = (System.nanoTime() - t0) / 1e6;
        assertTrue(ms < 20, "writes should be instant when latency is off, took " + ms);
        assertEquals(50, HardwareBus.writeCount(), "transactions are still counted");
    }

    @Test public void pauseFreezesRobotCodeAtItsNextHardwareCall() throws Exception {
        DcMotorEx m = hw.hardwareMap.get(DcMotorEx.class, "frontLeft");
        AtomicBoolean pastTheCall = new AtomicBoolean();
        CountDownLatch atGate = new CountDownLatch(1);
        world.setPaused(true);
        Thread robot = new Thread(() -> { atGate.countDown(); m.setPower(1.0); pastTheCall.set(true); }, "fake-opmode");
        robot.setDaemon(true);
        robot.start();
        assertTrue(atGate.await(1, TimeUnit.SECONDS));
        Thread.sleep(150);
        assertFalse(pastTheCall.get(), "robot code must block at the hardware call while paused");
        world.setPaused(false);
        robot.join(2000);
        assertTrue(pastTheCall.get(), "robot code must continue when the simulation resumes");
    }

    @Test public void steppingReleasesTheFreezeForOneSliceOfTime() throws Exception {
        world.setPaused(true);
        assertTrue(world.frozen());
        world.stepFor(0.02);
        assertFalse(world.frozen(), "a step budget unfreezes the world");
        for (int i = 0; i < 20; i++) world.step();
        assertTrue(world.frozen(), "the world freezes again once the budget is spent");
        assertEquals(0, world.stepRemaining(), 1e-9);
    }

    @Test public void theSimulatorsOwnReadsAreFree() {
        DcMotorEx m = hw.hardwareMap.get(DcMotorEx.class, "frontLeft");
        HardwareBus.resetCounters();
        long t0 = System.nanoTime();
        HardwareBus.exempt(() -> { for (int i = 0; i < 100; i++) m.getCurrentPosition(); });
        double ms = (System.nanoTime() - t0) / 1e6;
        assertTrue(ms < 20, "UI reads must not pay hub latency, took " + ms);
        assertEquals(0, HardwareBus.readCount() + HardwareBus.bulkReadCount());
    }
}
