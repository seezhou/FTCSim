package ftcsim.physics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleConsumer;

/**
 * Owns all simulated physical state and advances it in real time on a fixed
 * time step. Sensor models register a step listener to sample the world.
 */
public final class World {
    public static final double DT = 0.001; // 1 kHz physics
    public final Chassis chassis = new Chassis();
    public final Battery battery = new Battery();
    public Field field = Field.decode();
    public final List<MotorState> motors = new CopyOnWriteArrayList<>();
    public final List<ServoState> servos = new CopyOnWriteArrayList<>();
    public final List<DeadWheel> deadWheels = new CopyOnWriteArrayList<>();
    /** Balls the robot has launched. They stay on the field once they settle. */
    public final List<Projectile> projectiles = new CopyOnWriteArrayList<>();
    /** Goals a ball entered since the last read; the UI broadcast drains it. */
    public final Queue<String> scoreEvents = new ConcurrentLinkedQueue<>();
    /** HIVE tips since the last read, as alliance names ("RED"/"BLUE"); the UI drains it. */
    public final Queue<String> tipEvents = new ConcurrentLinkedQueue<>();
    /** Tips per alliance since the last INIT, kept for the UI's running count. */
    public final Map<String, Integer> tipCounts = new LinkedHashMap<>();
    /** Balls the robot drove over and collected since the last read; the broadcast drains it. */
    public final Queue<Integer> pickupEvents = new ConcurrentLinkedQueue<>();
    /** Set when the world moved the field itself (a HIVE tipped, or a match reset put them back)
     *  so the UI can rebuild it. The broadcast loop drains it. */
    private volatile boolean fieldChanged;

    public boolean takeFieldChanged() {
        if (!fieldChanged) return false;
        fieldChanged = false;
        return true;
    }

    /** Marks the field as changed after season rules moved its elements. */
    public void markFieldChanged() { fieldChanged = true; }
    /** Ceiling on balls kept at once, so a long match cannot grow the list without bound. */
    public static final int MAX_PROJECTILES = 24;
    private final List<DoubleConsumer> stepListeners = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private volatile boolean paused;
    private volatile double timeScale = 1.0;
    /** While paused, the sim time at which the world freezes again (set by {@link #stepFor}). */
    private volatile double stepUntil = Double.NEGATIVE_INFINITY;
    private Thread thread;
    private volatile double simTime;
    private volatile double actualStepRate;

    public double simTime() { return simTime; }
    public double stepRate() { return actualStepRate; }
    public boolean isPaused() { return paused; }
    public void setPaused(boolean p) { paused = p; stepUntil = Double.NEGATIVE_INFINITY; }
    public double timeScale() { return timeScale; }
    public void setTimeScale(double s) { timeScale = s <= 0 ? 1.0 : s; }

    /**
     * True when nothing may advance: the simulation is paused and no step budget is left. Robot code
     * blocks on this at its next hub transaction, so the world and the OpMode freeze together.
     */
    public boolean frozen() { return paused && simTime >= stepUntil; }

    /** Advances the frozen world (and the robot code waiting on it) by {@code seconds} of simulated time. */
    public void stepFor(double seconds) {
        if (seconds <= 0) return;
        stepUntil = simTime + seconds;
        paused = true;
    }

    /** How much of the current step budget is left, in seconds (0 when running or fully frozen). */
    public double stepRemaining() { return paused && stepUntil > simTime ? stepUntil - simTime : 0; }
    public void addStepListener(DoubleConsumer l) { stepListeners.add(l); }
    public void removeStepListener(DoubleConsumer l) { stepListeners.remove(l); }

    /** Advances the simulation by one fixed step. */
    public void step() { step(DT); }

    public void step(double dt) {
        double volts = battery.volts();
        chassis.step(dt, volts, field);
        double current = chassis.driveCurrent();
        for (MotorState m : motors) {
            if (m.isDriveWheel) continue;
            m.stepLoad(dt, volts);
            current += m.currentAmps();
        }
        for (ServoState s : servos) { s.step(dt); current += s.current(); }
        Vec2 vr = chassis.velocityRobot();
        double omega = chassis.omega();
        for (DeadWheel d : deadWheels) d.step(vr, omega, dt);
        stepProjectiles(dt);
        battery.step(current);
        simTime += dt;
        for (DoubleConsumer l : stepListeners) l.accept(dt);
    }

    /**
     * Launches a ball at the given field-frame position and velocity (inches, in/s). Balls are kept
     * to {@link #MAX_PROJECTILES}: a settled one is dropped first, so a team firing into a wall does
     * not push the ones still in play off the field.
     */
    public Projectile spawnProjectile(double x, double y, double z, double vx, double vy, double vz, double radiusIn) {
        return spawnProjectile(x, y, z, vx, vy, vz, radiusIn, 0);
    }

    /** As above, with the ball's mass in grams (0 when unknown: drag falls back to linear damping). */
    public Projectile spawnProjectile(double x, double y, double z, double vx, double vy, double vz, double radiusIn, double massG) {
        while (projectiles.size() >= MAX_PROJECTILES) {
            Projectile victim = null;
            for (Projectile p : projectiles) if (p.state == Projectile.State.REST) { victim = p; break; }
            if (victim == null) victim = projectiles.get(0);
            projectiles.remove(victim);
        }
        Projectile p = new Projectile(x, y, z, vx, vy, vz, radiusIn, massG);
        projectiles.add(p);
        return p;
    }

    /** Empties the field of balls. Restarting the robot starts the match over, litter included —
     *  and both HIVEs tip back to their starting sides. */
    public void clearProjectiles() {
        projectiles.clear();
        scoreEvents.clear();
        tipEvents.clear();
        tipCounts.clear();
        pickupEvents.clear();
        field.rules().resetFieldState(field);
        fieldChanged = true;
    }

    /** Balls currently riding inside CELLs (for the UI's "in CELL" count). */
    public int capturedCount() {
        int n = 0;
        for (Projectile p : projectiles) if (p.capturedIn != null) n++;
        return n;
    }

    private void stepProjectiles(double dt) {
        if (projectiles.isEmpty()) return;
        for (Projectile p : projectiles) {
            String before = p.scoredGoal;
            p.step(dt, field);
            if (before == null && p.scoredGoal != null) onGoalReached(p);
            if (p.capturedIn == null) field.rules().afterStep(p, this);
        }
        collectBalls();
    }

    /**
     * Pick-up: the robot takes a ball back by driving over it. The ball leaves the field and the
     * magazine grows by one — the only way it refills, since the field only ever holds the balls
     * the robot started with.
     */
    private void collectBalls() {
        for (int i = projectiles.size() - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            if (p.capturedIn != null || p.z > p.radiusIn + 1.0) continue;  // in a CELL, or airborne
            if (!underRobot(p)) continue;
            projectiles.remove(i);
            pickupEvents.add(p.id);
        }
    }

    /** True when a ball lying on the floor is inside the robot's footprint. */
    private boolean underRobot(Projectile p) {
        Pose2 pose = chassis.pose();
        double dx = Units.inToM(p.x) - pose.x, dy = Units.inToM(p.y) - pose.y;
        double c = Math.cos(pose.heading), s = Math.sin(pose.heading);
        double ahead = dx * c + dy * s, left = -dx * s + dy * c;
        double r = Units.inToM(p.radiusIn);
        return Math.abs(ahead) <= chassis.length / 2 + r && Math.abs(left) <= chassis.width / 2 + r;
    }

    /** Season rules decide whether the volume is a goal or a container (a BIOBUZZ CELL). */
    private void onGoalReached(Projectile p) {
        if (!field.rules().isScoringGoal(p.scoredGoal, field)) { p.scoredGoal = null; return; }
        scoreEvents.add(p.scoredGoal);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "ftcsim-physics");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) { thread.interrupt(); thread = null; }
    }

    /** The physics thread never pays hub latency and is never frozen by the pause gate. */
    private static void markPhysicsThread() {
        try { Class.forName("ftcsim.hardware.HardwareBus").getMethod("markExemptThread").invoke(null); } catch (ReflectiveOperationException ignored) {}
    }

    private void loop() {
        long stepNanos = (long) (DT * 1e9);
        long next = System.nanoTime();
        long rateWindowStart = System.nanoTime();
        int stepsInWindow = 0;
        markPhysicsThread();
        while (running) {
            long now = System.nanoTime();
            if (frozen()) {
                next = now;
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
                continue;
            }
            // catch up (bounded) if we fell behind
            int catchUp = 0;
            while (now >= next && catchUp < 50) {
                step();
                stepsInWindow++;
                next += (long) (stepNanos / timeScale);
                catchUp++;
            }
            if (catchUp >= 50) next = System.nanoTime();
            long sleep = next - System.nanoTime();
            if (sleep > 2_000_000L) {
                try { Thread.sleep((sleep - 1_000_000L) / 1_000_000L); } catch (InterruptedException e) { return; }
            } else if (sleep > 0) {
                Thread.onSpinWait();
            }
            if (now - rateWindowStart > 1_000_000_000L) {
                actualStepRate = stepsInWindow * 1e9 / (now - rateWindowStart);
                stepsInWindow = 0;
                rateWindowStart = now;
            }
        }
    }
}
