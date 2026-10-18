package ftcsim.hardware;

import ftcsim.physics.World;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Models the cost of talking to the REV hubs, and freezes robot code while the simulator is paused.
 *
 * <p>On a real robot every hub transaction is a round trip over USB / RS-485: a bulk read of one hub
 * takes roughly 2 ms, and every write (a motor power, a servo position, a run mode...) about 2-3 ms.
 * That is what makes loop times what they are, and why bulk caching matters, so the simulator charges
 * the calling thread the same time. Only robot code pays: the UI, the physics loop and the vision
 * threads read the same state through {@link #exempt}.
 *
 * <p>The pause gate lives here too. Robot code touches hardware constantly, so blocking it at the
 * transaction boundary freezes the OpMode within one hub call of the pause, with the world stopped at
 * exactly the same instant.
 */
public final class HardwareBus {
    /** Time of one hub transaction, milliseconds. */
    public static final double DEFAULT_BULK_READ_MS = 2.0, DEFAULT_WRITE_MS = 2.5, DEFAULT_READ_MS = 2.0, DEFAULT_I2C_MS = 2.5;

    private static volatile World world;
    private static volatile boolean enabled = true;
    private static volatile double bulkReadMs = DEFAULT_BULK_READ_MS, writeMs = DEFAULT_WRITE_MS, readMs = DEFAULT_READ_MS, i2cMs = DEFAULT_I2C_MS;
    private static final AtomicLong writes = new AtomicLong(), reads = new AtomicLong(), bulkReads = new AtomicLong(), i2cOps = new AtomicLong();
    private static final AtomicLong ioNanos = new AtomicLong();
    private static final ThreadLocal<Boolean> EXEMPT = ThreadLocal.withInitial(() -> false);

    private HardwareBus() {}

    public static void install(World w) { world = w; }
    public static void configure(boolean on, double bulkRead, double write, double read, double i2c) {
        enabled = on; bulkReadMs = bulkRead; writeMs = write; readMs = read; i2cMs = i2c;
    }
    public static boolean enabled() { return enabled; }
    public static double bulkReadMs() { return bulkReadMs; }
    public static double writeMs() { return writeMs; }
    public static double readMs() { return readMs; }
    public static double i2cMs() { return i2cMs; }

    public static long writeCount() { return writes.get(); }
    public static long readCount() { return reads.get(); }
    public static long bulkReadCount() { return bulkReads.get(); }
    public static long i2cCount() { return i2cOps.get(); }
    /** Total time robot code has spent waiting on hub transactions, milliseconds. */
    public static double ioMillis() { return ioNanos.get() / 1e6; }
    public static void resetCounters() { writes.set(0); reads.set(0); bulkReads.set(0); i2cOps.set(0); ioNanos.set(0); }

    /** True when the calling thread is the simulator's own (UI, physics, vision) and must not be charged or frozen. */
    public static boolean isExempt() { return EXEMPT.get(); }

    /** Runs {@code r} without hub latency or pause gating: for the simulator's own reads of device state. */
    public static void exempt(Runnable r) {
        if (EXEMPT.get()) { r.run(); return; }
        EXEMPT.set(true);
        try { r.run(); } finally { EXEMPT.set(false); }
    }

    /** Marks the calling thread as the simulator's own for its whole life (UI, physics, vision loops). */
    public static void markExemptThread() { EXEMPT.set(true); }

    public static void write() { charge(writeMs, writes); }
    public static void write(int transactions) { charge(writeMs * Math.max(0, transactions), writes, Math.max(0, transactions)); }
    public static void read() { charge(readMs, reads); }
    public static void bulkRead() { charge(bulkReadMs, bulkReads); }
    public static void i2c() { charge(i2cMs, i2cOps); }

    private static void charge(double millis, AtomicLong counter) { charge(millis, counter, 1); }

    private static void charge(double millis, AtomicLong counter, long count) {
        if (EXEMPT.get()) return;
        gate();
        if (!enabled || millis <= 0) { counter.addAndGet(count); return; }
        counter.addAndGet(count);
        World w = world;
        double scale = w == null ? 1.0 : w.timeScale();
        long nanos = (long) (millis * 1e6 / (scale <= 0 ? 1 : scale));
        ioNanos.addAndGet(nanos);
        sleepNanos(nanos);
    }

    /** Blocks while the simulator is frozen; returns immediately if the thread is interrupted (a stop request). */
    public static void gate() {
        if (EXEMPT.get()) return;
        World w = world;
        if (w == null) return;
        while (w.frozen()) {
            if (Thread.currentThread().isInterrupted()) return;
            LockSupport.parkNanos(1_000_000L);
        }
    }

    /** The tail of a wait is spun, so the cheaper waits below it do not decide when we wake up. */
    private static final long SPIN_NANOS = 1_500_000L;

    /**
     * Accurate short sleep, well under the OS timer tick.
     *
     * <p>{@link LockSupport#parkNanos} cannot be used for this: on Windows it rounds every request up
     * to the 15.6 ms scheduler tick, so parking the first millisecond of a 2.5 ms hub write actually
     * cost 15 ms and every OpMode ran about six times slower than on a real robot. A millisecond
     * sleep does land within about a millisecond there, so the wait sleeps in whole milliseconds and
     * spins the last {@link #SPIN_NANOS} to land on the deadline.
     */
    private static void sleepNanos(long nanos) {
        long deadline = System.nanoTime() + nanos;
        long remaining = nanos;
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted()) return;
            if (remaining - SPIN_NANOS >= 1_000_000L) {
                try { Thread.sleep((remaining - SPIN_NANOS) / 1_000_000L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            } else {
                Thread.onSpinWait();
            }
            remaining = deadline - System.nanoTime();
        }
    }
}
