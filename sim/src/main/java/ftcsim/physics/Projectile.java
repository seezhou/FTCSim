package ftcsim.physics;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A ball the robot launched, in the FTC field frame (inches, +Z up, centre origin).
 * <p>
 * It is integrated from {@link World#step(double)}, so it inherits everything the rest of the
 * world has: the 1 kHz fixed step, the pause gate (a frozen world freezes the ball), the step
 * button and the time scale.
 * <p>
 * The model is a point mass with a radius rather than a rigid body: a ball only ever meets the
 * floor, the perimeter and the field's axis-aligned obstacles, and a general solver would cost far
 * more than it would ever show. Balls that stop are kept on the field — they are litter, and a
 * team that has to drive around them gets a more honest picture of its own code.
 */
public final class Projectile {
    public enum State { FLIGHT, ROLLING, REST, CAPTURED }

    /** 9.80665 m/s² expressed in in/s². */
    public static final double GRAVITY = 9.80665 / Units.INCH;
    /** Linear air drag: the fraction of its speed a ball loses per second (used when no mass is known). */
    public static final double DRAG = 0.06;
    /** Air density, kg/m³, and the drag coefficient of a sphere — for the drag of a ball with a mass. */
    public static final double AIR_DENSITY = 1.204, SPHERE_CD = 0.47;
    /** How much of the normal speed survives a bounce off the floor, a wall or an obstacle. */
    public static final double RESTITUTION = 0.55;
    /** Carpet friction on a rolling ball, in/s². */
    public static final double ROLL_DECEL = 45.0;
    /** Below this horizontal speed a rolling ball is taken to have stopped. */
    public static final double REST_SPEED = 2.0;
    /** Below this vertical speed a ball stops bouncing and starts rolling. */
    public static final double SETTLE_SPEED = 6.0;

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    public final int id;
    public final double radiusIn;
    /**
     * Mass in grams, or 0 when the ball's weight is not known. Gravity accelerates every ball alike,
     * so the mass only ever shows up in drag: a light ball is slowed by the air far more than a heavy
     * one of the same size.
     */
    public final double massG;
    public double x, y, z;          // field frame, inches
    public double vx, vy, vz;       // in/s
    public State state = State.FLIGHT;
    /** Set the first time the ball enters a goal. A ball only ever scores once. */
    public volatile String scoredGoal;
    /** Name of the CELL the ball is riding in, or null while it flies free. A captured ball is
     *  inside the box: it stops being integrated and moves with the CELL (BIOBUZZ HIVEs). */
    public volatile String capturedIn;
    /** Stacking slot inside that CELL, assigned when the ball is captured. */
    public int slot;
    /** Seconds since the ball was launched. */
    public double age;

    public Projectile(double x, double y, double z, double vx, double vy, double vz, double radiusIn) {
        this(x, y, z, vx, vy, vz, radiusIn, 0);
    }

    public Projectile(double x, double y, double z, double vx, double vy, double vz, double radiusIn, double massG) {
        this.id = NEXT_ID.getAndIncrement();
        this.x = x; this.y = y; this.z = z;
        this.vx = vx; this.vy = vy; this.vz = vz;
        this.radiusIn = radiusIn > 0 ? radiusIn : 3.0;
        this.massG = massG > 0 ? massG : 0;
    }

    public double speed() { return Math.sqrt(vx * vx + vy * vy + vz * vz); }

    /** Advances the ball by one physics step. */
    public void step(double dt, Field field) {
        age += dt;
        if (state == State.REST) return;
        if (capturedIn != null) { rideInCell(field); return; }

        vz -= GRAVITY * dt;
        applyDrag(dt);
        x += vx * dt; y += vy * dt; z += vz * dt;

        hitPerimeter();
        // Score before colliding: a goal is also an obstacle, so a ball that reaches one has scored
        // even though the collision below bounces it straight back out.
        checkGoal(field);
        hitObstacles(field);
        hitCells(field);
        hitFloor();
        if (state == State.ROLLING) roll(dt);
    }

    /**
     * Air resistance. With a mass it is the real thing — ½·ρ·Cd·A·v²/m, which is exactly why a light
     * ball falls short of a heavy one of the same size — and with no mass to work from it falls back
     * to plain linear damping.
     */
    private void applyDrag(double dt) {
        if (massG <= 0) {
            double damp = 1 - Math.min(0.5, DRAG * dt);
            vx *= damp; vy *= damp; vz *= damp;
            return;
        }
        double v = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (v < 1e-9) return;
        double vm = Units.inToM(v);
        double area = Math.PI * Math.pow(Units.inToM(radiusIn), 2);
        double accel = 0.5 * AIR_DENSITY * SPHERE_CD * area * vm * vm / (massG / 1000.0);  // m/s²
        // one step of drag must never turn the ball around
        double drop = Math.min(v, Units.mToIn(accel) * dt);
        double keep = (v - drop) / v;
        vx *= keep; vy *= keep; vz *= keep;
    }

    /** The field perimeter: four walls the ball bounces off rather than leaving through. */
    private void hitPerimeter() {
        double limit = Field.HALF_SIZE_IN - radiusIn;
        if (x > limit) { x = limit; vx = -Math.abs(vx) * RESTITUTION; }
        if (x < -limit) { x = -limit; vx = Math.abs(vx) * RESTITUTION; }
        if (y > limit) { y = limit; vy = -Math.abs(vy) * RESTITUTION; }
        if (y < -limit) { y = -limit; vy = Math.abs(vy) * RESTITUTION; }
    }

    /**
     * Field obstacles are axis-aligned boxes with a height; the ball is pushed out of the nearest
     * face and loses speed into it. A ball flying higher than the box simply passes over.
     */
    private void hitObstacles(Field field) {
        for (Field.Obstacle o : field.obstacles) {
            if (z - radiusIn >= o.height) continue;
            double cx = Math.max(o.minX, Math.min(x, o.maxX));
            double cy = Math.max(o.minY, Math.min(y, o.maxY));
            double dx = x - cx, dy = y - cy;
            double d2 = dx * dx + dy * dy;
            if (d2 > radiusIn * radiusIn) continue;
            if (d2 > 1e-9) {
                // against a face or a corner: push out along the contact normal
                double d = Math.sqrt(d2), nx = dx / d, ny = dy / d;
                x = cx + nx * radiusIn;
                y = cy + ny * radiusIn;
                double vn = vx * nx + vy * ny;
                if (vn < 0) {
                    vx -= (1 + RESTITUTION) * vn * nx;
                    vy -= (1 + RESTITUTION) * vn * ny;
                }
            } else {
                // centre inside the footprint: leave through the nearest face
                double left = x - o.minX, right = o.maxX - x, low = y - o.minY, high = o.maxY - y;
                double outX = Math.min(left, right), outY = Math.min(low, high);
                if (outX < outY) {
                    x = left < right ? o.minX - radiusIn : o.maxX + radiusIn;
                    vx = -vx * RESTITUTION;
                } else {
                    y = low < high ? o.minY - radiusIn : o.maxY + radiusIn;
                    vy = -vy * RESTITUTION;
                }
            }
        }
    }

    private void hitFloor() {
        if (z > radiusIn) {
            if (state == State.ROLLING) state = State.FLIGHT;
            return;
        }
        z = radiusIn;
        if (vz < 0) {
            vz = -vz * RESTITUTION;
            // every bounce scrubs some horizontal speed
            vx *= 0.86; vy *= 0.86;
        }
        if (Math.abs(vz) < SETTLE_SPEED) { vz = 0; state = State.ROLLING; }
    }

    private void roll(double dt) {
        double sp = Math.hypot(vx, vy);
        if (sp < 1e-6) { state = State.REST; vx = 0; vy = 0; return; }
        double drop = Math.min(sp, ROLL_DECEL * dt);
        vx -= vx / sp * drop;
        vy -= vy / sp * drop;
        if (Math.hypot(vx, vy) < REST_SPEED) { state = State.REST; vx = 0; vy = 0; }
    }

    private void checkGoal(Field field) {
        if (scoredGoal != null) return;
        for (Field.Goal g : field.goals) {
            if (g.contains(x, y, z)) { scoredGoal = g.name; return; }
        }
    }

    /**
     * The BIOBUZZ CELLS: boxes, not hit boxes. A ball bounces off their five closed faces and only
     * ever gets inside through the mouth — the one open face, the big pentagon out at the end of
     * the arm. A ball already flying in through the mouth is left alone; it is being captured.
     */
    private void hitCells(Field field) {
        for (Field.Cluster cl : field.clusters) {
            double dx = x - cl.x, dy = y - cl.y, dz = z - cl.z;
            if (dx * dx + dy * dy + dz * dz > 400) continue;   // 20 in sphere — the box fits in ~15
            resolveCell(cl);
        }
    }

    private void resolveCell(Field.Cluster cl) {
        double halfD = Field.CELL_DEPTH_IN / 2;
        double[] l = cl.local(x, y, z);          // (across, up, depth); the mouth is at -halfD
        double u = l[0], w = l[1], dp = l[2];
        boolean inSection = Field.pentagonSd(u, w) <= 0;
        if (inSection && dp < -halfD) return;    // in front of the open mouth: nothing to hit

        double nu = 0, nw = 0, nd = 0, push;     // outward direction (box local) and how far to move
        if (inSection && dp <= halfD) {
            // the centre is inside the box: leave through whichever face is nearest
            double best = dp + halfD, bu = 0, bw = 0, bd = -1;   // out through the mouth
            if (halfD - dp < best) { best = halfD - dp; bu = 0; bw = 0; bd = 1; }  // or the back
            for (int i = 0; i < Field.CELL_PENT.length; i++) {
                double[] a = Field.CELL_PENT[i], b = Field.CELL_PENT[(i + 1) % Field.CELL_PENT.length];
                double ex = b[0] - a[0], ey = b[1] - a[1], len = Math.hypot(ex, ey);
                double nx = ey / len, ny = -ex / len;
                double d = -((u - a[0]) * nx + (w - a[1]) * ny);  // inside -> the distance to that wall
                if (d < best) { best = d; bu = nx; bw = ny; bd = 0; }
            }
            // flying deeper in through the mouth is capture, not a bounce
            double[][] ax = cl.axes();
            if (bd < 0 && vx * ax[2][0] + vy * ax[2][1] + vz * ax[2][2] > 0) return;
            nu = bu; nw = bw; nd = bd; push = best + radiusIn;
        } else {
            double[] q = inSection ? new double[] { u, w } : Field.pentagonClosest(u, w);
            double du = u - q[0], dw = w - q[1];
            double dd = dp - Math.max(-halfD, Math.min(halfD, dp));
            double dist = Math.sqrt(du * du + dw * dw + dd * dd);
            if (dist >= radiusIn) return;
            if (dist > 1e-9) { nu = du / dist; nw = dw / dist; nd = dd / dist; }
            else nd = dp > 0 ? 1 : -1;
            push = radiusIn - dist;
        }

        double[][] ax = cl.axes();
        double[] n = {
            ax[0][0] * nu + ax[1][0] * nw + ax[2][0] * nd,
            ax[0][1] * nu + ax[1][1] * nw + ax[2][1] * nd,
            ax[0][2] * nu + ax[1][2] * nw + ax[2][2] * nd };
        x += n[0] * push; y += n[1] * push; z += n[2] * push;
        double vn = vx * n[0] + vy * n[1] + vz * n[2];
        if (vn < 0) {
            vx -= (1 + RESTITUTION) * vn * n[0];
            vy -= (1 + RESTITUTION) * vn * n[1];
            vz -= (1 + RESTITUTION) * vn * n[2];
        }
    }

    /** A captured ball rides with its CELL, parked on the stack inside the box. */
    private void rideInCell(Field field) {
        Field.Cluster cl = field.cluster(capturedIn);
        if (cl == null) { capturedIn = null; state = State.FLIGHT; return; }  // field changed under us
        double[] p = field.cellSlotPosition(cl, slot, radiusIn);
        x = p[0]; y = p[1]; z = p[2];
        vx = vy = vz = 0;
    }
}
