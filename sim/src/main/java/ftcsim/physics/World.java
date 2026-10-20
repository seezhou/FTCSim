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
        field.resetHives();
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
            if (p.capturedIn == null) checkCellCapture(p);
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

    /** A DECODE goal scores; a BIOBUZZ CELL is a box, not a hit box — capture handles it. */
    private void onGoalReached(Projectile p) {
        if (field.cluster(p.scoredGoal) != null) { p.scoredGoal = null; return; }
        scoreEvents.add(p.scoredGoal);
    }

    /** A ball that has gone into an upward CELL's box is captured into it. */
    private void checkCellCapture(Projectile p) {
        for (Field.Cluster cl : field.clusters) {
            if (cl.pitchDeg <= 0) continue;          // only the upward CELL collects balls
            if (!enteringMouth(p, cl)) continue;
            p.scoredGoal = null;
            capture(p, cl);
            return;
        }
    }

    /**
     * True when the ball is genuinely in a CELL: flying against the mouth normal (the interior
     * lies behind the mouth) with its centre inside the box proper. The mouth's hit volume is only
     * an axis-aligned box around the mouth plane, so the CELL's own geometry decides — a ball that
     * merely grazes the rim bounces instead of being captured.
     */
    private static boolean enteringMouth(Projectile p, Field.Cluster cell) {
        double dx = p.x - cell.x, dy = p.y - cell.y, dz = p.z - cell.z;
        if (dx * dx + dy * dy + dz * dz > 400) return false;   // 20 in sphere — the box fits in ~15
        double[] f = cell.facing();
        if (p.vx * f[0] + p.vy * f[1] + p.vz * f[2] > 0) return false;   // going back out
        double[] l = cell.local(p.x, p.y, p.z);
        double halfD = Field.CELL_DEPTH_IN / 2;
        return Field.pentagonSd(l[0], l[1]) <= 0 && l[2] >= -halfD && l[2] <= halfD;
    }

    private void capture(Projectile p, Field.Cluster cell) {
        p.capturedIn = cell.name;
        p.slot = capturedIn(cell.name);
        p.state = Projectile.State.CAPTURED;
        p.scoredGoal = null;
        if (capturedIn(cell.name) >= Field.HIVE_TIP_BALLS) tip(cell.name);
    }

    private int capturedIn(String cellName) {
        int n = 0;
        for (Projectile q : projectiles) if (cellName.equals(q.capturedIn)) n++;
        return n;
    }

    /**
     * The upward CELL is heavy enough: the HIVE falls through to its other stable state (a 60°
     * turn around the pivot), spilling the balls that were in it. Worth 20 points, per manual
     * table 10-2.
     */
    private void tip(String cellName) {
        String alliance = cellName.startsWith("RED") ? "red" : "blue";
        String option = alliance + "Hive";
        String up = field.option(option);
        field.setOption(option, "audience".equals(up) ? "scoring" : "audience");
        Field.Cluster nowDown = field.cluster(cellName);   // same CELL, now facing the floor
        for (Projectile p : projectiles) {
            if (!cellName.equals(p.capturedIn)) continue;
            release(p, nowDown);
        }
        String name = alliance.toUpperCase();
        tipEvents.add(name);
        tipCounts.merge(name, 1, Integer::sum);
        fieldChanged = true;   // the CELLs moved: the UI has to rebuild the field to see it
    }

    /** A tipped-out ball pours from the mouth of the now-downward CELL and tumbles to the floor. */
    private static void release(Projectile p, Field.Cluster cell) {
        p.capturedIn = null;
        p.scoredGoal = null;
        p.state = Projectile.State.FLIGHT;
        double[] out = cell.facing();                      // points outward and 30° down
        double yaw = Math.toRadians(cell.yawDeg);
        double[] across = { -Math.sin(yaw), Math.cos(yaw), 0 };
        double nudge = ((p.id % 5) - 2) * 2.0;             // a little spread so they do not overlap
        double dist = Field.CELL_DEPTH_IN / 2 + p.radiusIn + 0.5;
        p.x = cell.x + out[0] * dist + across[0] * nudge;
        p.y = cell.y + out[1] * dist + across[1] * nudge;
        p.z = cell.z + out[2] * dist;
        p.vx = out[0] * 18 + across[0] * nudge * 3;
        p.vy = out[1] * 18 + across[1] * nudge * 3;
        p.vz = out[2] * 18;
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
