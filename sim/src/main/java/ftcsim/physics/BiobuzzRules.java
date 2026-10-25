package ftcsim.physics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BIOBUZZ (2026-27): a CELL is a box with one open pentagon face, a ball thrown into the
 * upward-facing CELL is captured, and enough captured balls tip the HIVE. Geometry from the
 * 2026-27 Competition Manual (section 9) as transcribed by the community field model at
 * github.com/jsherman999/ftc_demo (docs/FIELD.md). FTC frame: red wall at -Y, blue wall at +Y,
 * audience at +X.
 */
public final class BiobuzzRules implements SeasonRules {
    private static final double HIVE_PIVOT_Z = 43.95, HIVE_ARM = 21.46, HIVE_TILT_DEG = 30, HIVE_Y = 12.75;
    private static final double CELL_CENTER_ABOVE_ARM = 5.6375; // pentagon centre above the arm line (lip at 53.5 in, top at 65.6 in)
    // AprilTag sticker offsets within the CELL frame: { across, height above cluster origin, depth
    // along the mouth normal }. Depth is 0 — the sticker sits at the MIDDLE of the bottom face, not
    // recessed to the back wall (-5.622) — which is also where the 3D view draws it, so the simulated
    // detections line up with what the picture-in-picture actually shows.
    private static final double[][] CLUSTER_OFFSETS = { { -6.5, 7.187, 0 }, { -2.75, 7.187, 0 }, { 2.75, 7.187, 0 }, { 6.5, 7.187, 0 } };
    private static final double CLUSTER_TAG_SIZE = 3.25;
    /** CELL box, shared with the ball physics: the pentagon bottom face sits 9.938 in below the
     *  cluster origin (the mouth centre) and the box is 11.244 in deep along the mouth normal. */
    public static final double CELL_BOTTOM_IN = 9.938, CELL_DEPTH_IN = 11.244;
    /**
     * The CELL's pentagon cross-section in the cluster plane: (across, up) in inches, the same
     * numbers the 3D view draws (manual fig 9-11). Listed counter-clockwise, so an edge from a to
     * b has the outward normal (dy, -dx)/|.|.
     */
    public static final double[][] CELL_PENT = {
        { -10, -9.938 }, { 10, -9.938 }, { 10, -2.328 }, { 0, 4.062 }, { -10, -2.328 } };
    /**
     * Launched balls the upward-facing CELL must hold before the HIVE tips. The manual only says
     * "enough POLLEN or NECTAR" (section 10.4) without a number; this sim tips at four.
     */
    public static final int HIVE_TIP_BALLS = 4;
    static final String RED = "#d93b2b", BLUE = "#2b5fd9", YELLOW = "#e8c547";

    /**
     * Signed distance from (across, up) to the CELL pentagon: negative inside, zero on an edge.
     * The polygon is convex, so the largest signed distance over its edges is the real one and
     * {@code <= margin} means "inside, grown by margin".
     */
    public static double pentagonSd(double u, double w) {
        double sd = -Double.MAX_VALUE;
        for (int i = 0; i < CELL_PENT.length; i++) {
            double[] a = CELL_PENT[i], b = CELL_PENT[(i + 1) % CELL_PENT.length];
            double ex = b[0] - a[0], ey = b[1] - a[1];
            double len = Math.hypot(ex, ey);
            double s = ((u - a[0]) * ey - (w - a[1]) * ex) / len;   // (p-a)·(ey,-ex)/|.|
            if (s > sd) sd = s;
        }
        return sd;
    }

    /** Closest point on the CELL pentagon boundary to (across, up). */
    public static double[] pentagonClosest(double u, double w) {
        double best = Double.MAX_VALUE, bu = u, bw = w;
        for (int i = 0; i < CELL_PENT.length; i++) {
            double[] a = CELL_PENT[i], b = CELL_PENT[(i + 1) % CELL_PENT.length];
            double ex = b[0] - a[0], ey = b[1] - a[1];
            double len2 = ex * ex + ey * ey;
            double t = len2 > 1e-12 ? Math.max(0, Math.min(1, ((u - a[0]) * ex + (w - a[1]) * ey) / len2)) : 0;
            double qu = a[0] + ex * t, qw = a[1] + ey * t;
            double d2 = (u - qu) * (u - qu) + (w - qw) * (w - qw);
            if (d2 < best) { best = d2; bu = qu; bw = qw; }
        }
        return new double[] { bu, bw };
    }

    /**
     * Where the n-th captured ball rests inside a CELL: stacked three across on the bottom face,
     * row by row from the back wall toward the mouth (the facing side, the only open face).
     */
    public static double[] cellSlotPosition(Field.Cluster cl, int slot, double radiusIn) {
        int col = (slot % 3) - 1, row = slot / 3;
        double x = col * 2.4 * radiusIn;
        double z = CELL_DEPTH_IN / 2 - radiusIn - row * 2.1 * radiusIn;   // back wall first
        return cl.memberPosition(new double[] { x, CELL_BOTTOM_IN - radiusIn, z });
    }

    /** Structured CELL/HIVE geometry for the 3D view, sent in the backend's field message. */
    public static Map<String, Object> cellGeometry() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pent", CELL_PENT);
        m.put("depth", CELL_DEPTH_IN);
        m.put("bottom", CELL_BOTTOM_IN);
        m.put("armDrop", CELL_CENTER_ABOVE_ARM);
        m.put("hivePivotZ", HIVE_PIVOT_Z);
        m.put("hiveY", HIVE_Y);
        m.put("hiveTiltDeg", HIVE_TILT_DEG);
        return m;
    }

    /** Places the CELLs, their tag clusters and the drawing of both HIVEs for the current tip state. */
    static void buildHives(Field f) {
        f.tags.clear(); f.clusters.clear(); f.shapes.clear(); f.goals.clear();
        // frame outline and the pivot bar
        f.shapes.add(new Field.Shape("rect", new double[] { -19.475, -24.73, 19.475, 24.73 }, null, "#6c7a95", 1.5, null));
        f.shapes.add(new Field.Shape("line", new double[] { 0, -24.73, 0, 24.73 }, null, "#6c7a95", 2, null));
        for (String alliance : new String[] { "red", "blue" }) {
            boolean red = alliance.equals("red");
            double hy = red ? -HIVE_Y : HIVE_Y;
            String color = red ? RED : BLUE;
            boolean audienceUp = "audience".equals(f.option(red ? "redHive" : "blueHive"));
            int s = audienceUp ? 1 : -1; // the up end of the arm points toward +X when the audience CELL is up
            double c = Math.cos(Math.toRadians(HIVE_TILT_DEG)), sn = Math.sin(Math.toRadians(HIVE_TILT_DEG));
            // CELL mouth centres: along the arm to its end, then to the centre of the pentagon (perpendicular "up")
            double upX = s * (HIVE_ARM * c - CELL_CENTER_ABOVE_ARM * sn), upZ = HIVE_PIVOT_Z + HIVE_ARM * sn + CELL_CENTER_ABOVE_ARM * c;
            double downX = -s * (HIVE_ARM * c + CELL_CENTER_ABOVE_ARM * sn), downZ = HIVE_PIVOT_Z - HIVE_ARM * sn + CELL_CENTER_ABOVE_ARM * c;
            double upYaw = s > 0 ? 0 : 180, downYaw = s > 0 ? 180 : 0;
            // audience CELL lives at the +X end, scoring CELL at the -X end
            int[] audienceIds = red ? new int[] { 34, 35, 36, 37 } : new int[] { 38, 39, 40, 41 };
            int[] scoringIds = red ? new int[] { 30, 31, 32, 33 } : new int[] { 42, 43, 44, 45 };
            String prefix = red ? "RED" : "BLUE";
            Field.Cluster audience = audienceUp
                ? new Field.Cluster(prefix + " AUDIENCE", upX, hy, upZ, upYaw, HIVE_TILT_DEG, audienceIds, CLUSTER_OFFSETS, CLUSTER_TAG_SIZE)
                : new Field.Cluster(prefix + " AUDIENCE", downX, hy, downZ, downYaw, -HIVE_TILT_DEG, audienceIds, CLUSTER_OFFSETS, CLUSTER_TAG_SIZE);
            Field.Cluster scoring = audienceUp
                ? new Field.Cluster(prefix + " SCORING", downX, hy, downZ, downYaw, -HIVE_TILT_DEG, scoringIds, CLUSTER_OFFSETS, CLUSTER_TAG_SIZE)
                : new Field.Cluster(prefix + " SCORING", upX, hy, upZ, upYaw, HIVE_TILT_DEG, scoringIds, CLUSTER_OFFSETS, CLUSTER_TAG_SIZE);
            for (Field.Cluster cl : new Field.Cluster[] { audience, scoring }) {
                f.clusters.add(cl);
                // the CELL mouth: ~12 in along the arm by 20 in across; a ball that reaches it scores
                f.goals.add(new Field.Goal(cl.name, cl.x, cl.y, cl.z, 6, 10, 8));
                for (int i = 0; i < cl.memberIds.length; i++) {
                    double[] p = cl.memberPosition(cl.memberOffsets[i]);
                    f.tags.add(new Field.AprilTag(cl.memberIds[i], cl.name + " " + (i + 1), p[0], p[1], p[2], cl.yawDeg, cl.tagSizeIn));
                }
                boolean up = cl.pitchDeg > 0;
                // CELL footprint: 20 in across (Y), ~12 in along the arm (X projection)
                double x0 = cl.x - 6, x1 = cl.x + 6;
                f.shapes.add(new Field.Shape("rect", new double[] { x0, hy - 10, x1, hy + 10 }, up ? color + "aa" : color + "33", color, up ? 2.5 : 1, (red ? "R" : "B") + (up ? " up" : " down")));
                f.shapes.add(new Field.Shape("line", new double[] { 0, hy, cl.x, hy }, null, color, up ? 3 : 1.5, null));
            }
        }
        // FLOWER openings and the pivot marks
        f.shapes.add(new Field.Shape("circle", new double[] { -69.7, -24, 2.0 }, YELLOW + "88", YELLOW, 1.5, null));
        f.shapes.add(new Field.Shape("circle", new double[] { -24, 69.7, 2.0 }, YELLOW + "88", YELLOW, 1.5, null));
        f.shapes.add(new Field.Shape("circle", new double[] { 69.7, 24, 2.0 }, YELLOW + "88", YELLOW, 1.5, null));
        f.shapes.add(new Field.Shape("circle", new double[] { 24, -69.7, 2.0 }, YELLOW + "88", YELLOW, 1.5, null));
    }

    @Override
    public void collide(Projectile p, Field field) {
        for (Field.Cluster cl : field.clusters) {
            double dx = p.x - cl.x, dy = p.y - cl.y, dz = p.z - cl.z;
            if (dx * dx + dy * dy + dz * dz > 400) continue;   // 20 in sphere — the box fits in ~15
            resolveCell(p, cl);
        }
    }

    /**
     * The CELL is a box, not a hit box: the ball bounces off its five closed faces and only ever
     * gets inside through the mouth. A ball already flying in through the mouth is left alone; it
     * is being captured.
     */
    private static void resolveCell(Projectile p, Field.Cluster cl) {
        double halfD = CELL_DEPTH_IN / 2;
        double[] l = cl.local(p.x, p.y, p.z);    // (across, up, depth); the mouth is at -halfD
        double u = l[0], w = l[1], dp = l[2];
        boolean inSection = pentagonSd(u, w) <= 0;
        if (inSection && dp < -halfD) return;    // in front of the open mouth: nothing to hit

        double nu = 0, nw = 0, nd = 0, push;     // outward direction (box local) and how far to move
        if (inSection && dp <= halfD) {
            // the centre is inside the box: leave through whichever face is nearest
            double best = dp + halfD, bu = 0, bw = 0, bd = -1;   // out through the mouth
            if (halfD - dp < best) { best = halfD - dp; bu = 0; bw = 0; bd = 1; }  // or the back
            for (int i = 0; i < CELL_PENT.length; i++) {
                double[] a = CELL_PENT[i], b = CELL_PENT[(i + 1) % CELL_PENT.length];
                double ex = b[0] - a[0], ey = b[1] - a[1], len = Math.hypot(ex, ey);
                double nx = ey / len, ny = -ex / len;
                double d = -((u - a[0]) * nx + (w - a[1]) * ny);  // inside -> the distance to that wall
                if (d < best) { best = d; bu = nx; bw = ny; bd = 0; }
            }
            // flying deeper in through the mouth is capture, not a bounce
            double[][] ax = cl.axes();
            if (bd < 0 && p.vx * ax[2][0] + p.vy * ax[2][1] + p.vz * ax[2][2] > 0) return;
            nu = bu; nw = bw; nd = bd; push = best + p.radiusIn;
        } else {
            double[] q = inSection ? new double[] { u, w } : pentagonClosest(u, w);
            double du = u - q[0], dw = w - q[1];
            double dd = dp - Math.max(-halfD, Math.min(halfD, dp));
            double dist = Math.sqrt(du * du + dw * dw + dd * dd);
            if (dist >= p.radiusIn) return;
            if (dist > 1e-9) { nu = du / dist; nw = dw / dist; nd = dd / dist; }
            else nd = dp > 0 ? 1 : -1;
            push = p.radiusIn - dist;
        }

        double[][] ax = cl.axes();
        double[] n = {
            ax[0][0] * nu + ax[1][0] * nw + ax[2][0] * nd,
            ax[0][1] * nu + ax[1][1] * nw + ax[2][1] * nd,
            ax[0][2] * nu + ax[1][2] * nw + ax[2][2] * nd };
        p.x += n[0] * push; p.y += n[1] * push; p.z += n[2] * push;
        double vn = p.vx * n[0] + p.vy * n[1] + p.vz * n[2];
        if (vn < 0) {
            p.vx -= (1 + Projectile.RESTITUTION) * vn * n[0];
            p.vy -= (1 + Projectile.RESTITUTION) * vn * n[1];
            p.vz -= (1 + Projectile.RESTITUTION) * vn * n[2];
        }
    }

    @Override
    public boolean ride(Projectile p, Field field) {
        Field.Cluster cl = field.cluster(p.capturedIn);
        if (cl == null) {                 // field changed under us
            p.capturedIn = null;
            p.state = Projectile.State.FLIGHT;
            return false;
        }
        double[] pos = cellSlotPosition(cl, p.slot, p.radiusIn);
        p.x = pos[0]; p.y = pos[1]; p.z = pos[2];
        p.vx = p.vy = p.vz = 0;
        return true;
    }

    @Override
    public void afterStep(Projectile p, World world) {
        for (Field.Cluster cl : world.field.clusters) {
            if (cl.pitchDeg <= 0) continue;          // only the upward CELL collects balls
            if (!enteringMouth(p, cl)) continue;
            p.scoredGoal = null;
            capture(p, cl, world);
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
        double halfD = CELL_DEPTH_IN / 2;
        return pentagonSd(l[0], l[1]) <= 0 && l[2] >= -halfD && l[2] <= halfD;
    }

    private static void capture(Projectile p, Field.Cluster cell, World world) {
        p.capturedIn = cell.name;
        p.slot = capturedIn(world, cell.name);
        p.state = Projectile.State.CAPTURED;
        p.scoredGoal = null;
        if (capturedIn(world, cell.name) >= HIVE_TIP_BALLS) tip(cell.name, world);
    }

    private static int capturedIn(World world, String cellName) {
        int n = 0;
        for (Projectile q : world.projectiles) if (cellName.equals(q.capturedIn)) n++;
        return n;
    }

    /**
     * The upward CELL is heavy enough: the HIVE falls through to its other stable state (a 60°
     * turn around the pivot), spilling the balls that were in it. Worth {@link #tipPoints()}.
     */
    private static void tip(String cellName, World world) {
        Field field = world.field;
        String alliance = cellName.startsWith("RED") ? "red" : "blue";
        String option = alliance + "Hive";
        String up = field.option(option);
        field.setOption(option, "audience".equals(up) ? "scoring" : "audience");
        Field.Cluster nowDown = field.cluster(cellName);   // same CELL, now facing the floor
        for (Projectile p : world.projectiles) {
            if (!cellName.equals(p.capturedIn)) continue;
            release(p, nowDown);
        }
        String name = alliance.toUpperCase();
        world.tipEvents.add(name);
        world.tipCounts.merge(name, 1, Integer::sum);
        world.markFieldChanged();   // the CELLs moved: the UI has to rebuild the field to see it
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
        double dist = CELL_DEPTH_IN / 2 + p.radiusIn + 0.5;
        p.x = cell.x + out[0] * dist + across[0] * nudge;
        p.y = cell.y + out[1] * dist + across[1] * nudge;
        p.z = cell.z + out[2] * dist;
        p.vx = out[0] * 18 + across[0] * nudge * 3;
        p.vy = out[1] * 18 + across[1] * nudge * 3;
        p.vz = out[2] * 18;
    }

    @Override
    public int tipPoints() {
        return 20;   // manual table 10-2
    }

    @Override
    public String scoreEventLabel(String event) {
        return event + " HIVE tipped";
    }

    @Override
    public boolean isScoringGoal(String goalName, Field field) {
        return field.cluster(goalName) == null;   // a CELL is a container, not a goal
    }

    @Override
    public void resetFieldState(Field field) {
        field.setOptions(Map.of("redHive", "audience", "blueHive", "scoring"));
    }

    @Override
    public Map<String, Object> extraFieldData() {
        return Map.of("cellGeometry", cellGeometry());
    }
}
