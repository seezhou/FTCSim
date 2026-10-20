package ftcsim.physics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The playing field in the FTC field coordinate system: origin at the centre, +Y from the red
 * wall toward the blue wall, +X toward the audience (the "inverted" square layout DECODE and
 * BIOBUZZ use), +Z up, heading CCW from +X. Lengths in inches.
 */
public final class Field {
    public static final double HALF_SIZE_IN = 72.0;

    /** Something the robot collides with. */
    public static final class Obstacle {
        public final String name;
        public final double minX, minY, maxX, maxY; // inches
        public final double height;
        public final String color;
        public Obstacle(String name, double minX, double minY, double maxX, double maxY, double height, String color) {
            this.name = name; this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY; this.height = height; this.color = color;
        }
    }

    /**
     * A scoring target for launched balls: an axis-aligned volume. The DECODE goals are the goal
     * boxes themselves; the BIOBUZZ CELLs are the mouths the balls are thrown into.
     */
    public static final class Goal {
        public final String name;
        public final double x, y, z;      // centre, inches
        public final double hx, hy, hz;   // half extents, inches
        public Goal(String name, double x, double y, double z, double hx, double hy, double hz) {
            this.name = name; this.x = x; this.y = y; this.z = z; this.hx = hx; this.hy = hy; this.hz = hz;
        }
        public boolean contains(double px, double py, double pz) {
            return Math.abs(px - x) <= hx && Math.abs(py - y) <= hy && Math.abs(pz - z) <= hz;
        }
        /** The scoring volume of a goal obstacle: the box itself, floor to its top. */
        public static Goal from(Obstacle o) {
            return new Goal(o.name, (o.minX + o.maxX) / 2, (o.minY + o.maxY) / 2, o.height / 2,
                            (o.maxX - o.minX) / 2, (o.maxY - o.minY) / 2, o.height / 2);
        }
    }

    /** A single AprilTag (or a cluster member) placed on the field. */
    public static final class AprilTag {
        public final int id;
        public final String name;
        public final double x, y, z;      // inches
        public final double yawDeg;       // direction the tag face points (normal), CCW from +X
        public final double sizeIn;
        public AprilTag(int id, String name, double x, double y, double z, double yawDeg, double sizeIn) {
            this.id = id; this.name = name; this.x = x; this.y = y; this.z = z; this.yawDeg = yawDeg; this.sizeIn = sizeIn;
        }
    }

    /**
     * An AprilTag cluster (SDK 12): co-planar tags with positions relative to a common origin. The
     * cluster frame follows the tag convention: x across the plane, y down, z into the plane; the
     * face points along -z, which is described here by a yaw (CCW from +X) and a pitch (positive up).
     */
    public static final class Cluster {
        public final String name;
        public final double x, y, z;       // origin, inches
        public final double yawDeg, pitchDeg;
        public final int[] memberIds;
        public final double[][] memberOffsets; // per member: (x, y, z) in the cluster frame, inches
        public final double tagSizeIn;
        public Cluster(String name, double x, double y, double z, double yawDeg, double pitchDeg, int[] memberIds, double[][] memberOffsets, double tagSizeIn) {
            this.name = name; this.x = x; this.y = y; this.z = z; this.yawDeg = yawDeg; this.pitchDeg = pitchDeg;
            this.memberIds = memberIds; this.memberOffsets = memberOffsets; this.tagSizeIn = tagSizeIn;
        }
        /** Direction the cluster faces (unit vector). */
        public double[] facing() { double p = Math.toRadians(pitchDeg), y = Math.toRadians(yawDeg); return new double[] { Math.cos(p) * Math.cos(y), Math.cos(p) * Math.sin(y), Math.sin(p) }; }
        /**
         * Unit axes of the cluster frame: across (the tag row), up (toward the pentagon apex) and
         * depth (from the mouth into the box, i.e. -facing).
         */
        public double[][] axes() {
            double p = Math.toRadians(pitchDeg), yaw = Math.toRadians(yawDeg);
            double[] f = facing();
            return new double[][] {
                { -Math.sin(yaw), Math.cos(yaw), 0 },
                { -Math.sin(p) * Math.cos(yaw), -Math.sin(p) * Math.sin(yaw), Math.cos(p) },
                { -f[0], -f[1], -f[2] } };
        }
        /** Field position of a box-local offset: (across, up, depth) from the cluster origin. */
        public double[] point(double u, double w, double d) {
            double[][] a = axes();
            return new double[] {
                x + a[0][0] * u + a[1][0] * w + a[2][0] * d,
                y + a[0][1] * u + a[1][1] * w + a[2][1] * d,
                z + a[0][2] * u + a[1][2] * w + a[2][2] * d };
        }
        /** The inverse: a field point as box-local offsets (across, up, depth) from the origin. */
        public double[] local(double px, double py, double pz) {
            double[][] a = axes();
            double dx = px - x, dy = py - y, dz = pz - z;
            return new double[] {
                dx * a[0][0] + dy * a[0][1] + dz * a[0][2],
                dx * a[1][0] + dy * a[1][1] + dz * a[1][2],
                dx * a[2][0] + dy * a[2][1] + dz * a[2][2] };
        }
        /** Field position of a member given its offset in the cluster frame. */
        public double[] memberPosition(double[] off) {
            double p = Math.toRadians(pitchDeg), yaw = Math.toRadians(yawDeg);
            double[] f = facing();
            double[] xc = { -Math.sin(yaw), Math.cos(yaw), 0 };
            double[] yc = { Math.sin(p) * Math.cos(yaw), Math.sin(p) * Math.sin(yaw), -Math.cos(p) }; // z_c x x_c with z_c = -facing
            double[] zc = { -f[0], -f[1], -f[2] };
            return new double[] {
                x + xc[0] * off[0] + yc[0] * off[1] + zc[0] * off[2],
                y + xc[1] * off[0] + yc[1] * off[1] + zc[1] * off[2],
                z + xc[2] * off[0] + yc[2] * off[1] + zc[2] * off[2] };
        }
    }

    /** Tape or marked area the robot drives over (no collision). */
    public static final class Zone {
        public final String name;
        public final double minX, minY, maxX, maxY;
        public final String color;
        public Zone(String name, double minX, double minY, double maxX, double maxY, String color) { this.name = name; this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY; this.color = color; }
    }

    /** Drawing hint for the UI (structures that are not collision boxes, e.g. the HIVE arms). */
    public static final class Shape {
        public final String type;   // rect | circle | line | polygon | label
        public final double[] coords; // rect: x0,y0,x1,y1; circle: cx,cy,r; line: x1,y1,x2,y2; polygon: x,y pairs; label: x,y
        public final String fill, stroke, label;
        public final double width;
        public Shape(String type, double[] coords, String fill, String stroke, double width, String label) { this.type = type; this.coords = coords; this.fill = fill; this.stroke = stroke; this.width = width; this.label = label; }
    }

    /** A user-settable option of the field (obelisk face, HIVE tips...). */
    public static final class Option {
        public final String key, label; public final String[] choices; public final String[] choiceLabels;
        public Option(String key, String label, String[] choices, String[] choiceLabels) { this.key = key; this.label = label; this.choices = choices; this.choiceLabels = choiceLabels; }
    }

    public String name = "DECODE";
    public final List<Obstacle> obstacles = new CopyOnWriteArrayList<>();
    public final List<AprilTag> tags = new CopyOnWriteArrayList<>();
    public final List<Cluster> clusters = new CopyOnWriteArrayList<>();
    public final List<Zone> zones = new CopyOnWriteArrayList<>();
    public final List<Shape> shapes = new CopyOnWriteArrayList<>();
    public final List<Option> options = new ArrayList<>();
    /** Scoring targets for launched balls; rebuilt with the field (CELLs move when a HIVE tips). */
    public final List<Goal> goals = new CopyOnWriteArrayList<>();
    private final Map<String, String> state = new LinkedHashMap<>();
    private Runnable rebuilder = () -> {};

    /** Current values of the field options. */
    public synchronized Map<String, String> state() { return new LinkedHashMap<>(state); }
    public synchronized String option(String key) { return state.get(key); }
    /** Sets an option and rebuilds the movable elements. Returns false for an unknown key or value. */
    public synchronized boolean setOption(String key, String value) {
        for (Option o : options) {
            if (!o.key.equals(key)) continue;
            for (String c : o.choices) if (c.equals(value)) { state.put(key, value); rebuilder.run(); return true; }
            return false;
        }
        return false;
    }

    public static Field byName(String name) {
        if (name == null) return decode();
        switch (name.trim().toUpperCase()) {
            case "EMPTY": return empty();
            case "BIOBUZZ": return biobuzz();
            case "DECODE": default: return decode();
        }
    }

    /** The CELL cluster with this exact name, or null (the DECODE goals are not clusters). */
    public Cluster cluster(String name) {
        if (name == null) return null;
        for (Cluster c : clusters) if (c.name.equals(name)) return c;
        return null;
    }

    /**
     * Where the n-th captured ball rests inside a CELL: stacked three across on the bottom face,
     * row by row from the back wall toward the mouth (the facing side, the only open face).
     */
    public double[] cellSlotPosition(Cluster cl, int slot, double radiusIn) {
        int col = (slot % 3) - 1, row = slot / 3;
        double x = col * 2.4 * radiusIn;
        double z = CELL_DEPTH_IN / 2 - radiusIn - row * 2.1 * radiusIn;   // back wall first
        return cl.memberPosition(new double[] { x, CELL_BOTTOM_IN - radiusIn, z });
    }

    /** Match start: the red upward CELL faces the audience, the blue one the rear wall. */
    public synchronized void resetHives() {
        boolean changed = false;
        for (Option o : options) {
            if (!o.key.equals("redHive") && !o.key.equals("blueHive")) continue;
            String start = o.key.equals("redHive") ? "audience" : "scoring";
            if (!start.equals(state.get(o.key))) { state.put(o.key, start); changed = true; }
        }
        if (changed) rebuilder.run();
    }

    public static Field decode() {
        Field f = new Field();
        f.name = "DECODE";
        // Goals occupy the two corners on the -X wall; tag faces point toward the field centre diagonally.
        f.tags.add(new AprilTag(20, "BlueTarget", -58.3727, -55.6425, 29.5, 54.0, 6.5));
        f.tags.add(new AprilTag(24, "RedTarget", -58.3727, 55.6425, 29.5, -54.0, 6.5));
        // Obelisk (tags 21-23) sits outside the field on the +X side, centred; randomised per match.
        f.tags.add(new AprilTag(21, "Obelisk GPP", 78.0, 0.0, 12.0, 180.0, 6.5));
        f.tags.add(new AprilTag(22, "Obelisk PGP", 78.0, 0.0, 12.0, 180.0, 6.5));
        f.tags.add(new AprilTag(23, "Obelisk PPG", 78.0, 0.0, 12.0, 180.0, 6.5));
        // Goal structures (approximate footprints) in the -X corners. A ball that lands inside one scores.
        Obstacle blueGoal = new Obstacle("Blue goal", -72, -72, -48, -48, 40, "#2b5fd9");
        Obstacle redGoal = new Obstacle("Red goal", -72, 48, -48, 72, 40, "#d93b2b");
        f.obstacles.add(blueGoal);
        f.obstacles.add(redGoal);
        f.goals.add(Goal.from(blueGoal));
        f.goals.add(Goal.from(redGoal));
        f.options.add(new Option("obelisk", "Obelisk", new String[] { "21", "22", "23" }, new String[] { "21 (GPP)", "22 (PGP)", "23 (PPG)" }));
        f.state.put("obelisk", "21");
        return f;
    }

    public static Field empty() { Field f = new Field(); f.name = "Empty"; return f; }

    // ------------------------------------------------------------------ BIOBUZZ (2026-27)
    // Geometry from the 2026-27 Competition Manual (section 9) as transcribed by the community field
    // model at github.com/jsherman999/ftc_demo (docs/FIELD.md). FTC frame: red wall at -Y, blue wall at
    // +Y, audience at +X.
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
     * Launched balls the upward-facing CELL must hold before the HIVE tips. The manual only says
     * "enough POLLEN or NECTAR" (section 10.4) without a number; this sim tips at four.
     */
    public static final int HIVE_TIP_BALLS = 4;
    public static final String RED = "#d93b2b", BLUE = "#2b5fd9", YELLOW = "#e8c547";

    public static Field biobuzz() {
        Field f = new Field();
        f.name = "BIOBUZZ";
        f.options.add(new Option("redHive", "Red HIVE up", new String[] { "audience", "scoring" }, new String[] { "Audience", "Scoring" }));
        f.options.add(new Option("blueHive", "Blue HIVE up", new String[] { "audience", "scoring" }, new String[] { "Audience", "Scoring" }));
        f.state.put("redHive", "audience");  // match start: the red upward CELL faces the audience
        f.state.put("blueHive", "scoring");  // the blue upward CELL faces the rear wall
        // Static collision boxes: HIVE foot bars (parallel to X at Y = +-24) and the four wall-mounted FLOWERs.
        f.obstacles.add(new Obstacle("HIVE foot bar (red)", -19.475, -24.73, 19.475, -23.27, 1.5, "#8a93a6"));
        f.obstacles.add(new Obstacle("HIVE foot bar (blue)", -19.475, 23.27, 19.475, 24.73, 1.5, "#8a93a6"));
        f.obstacles.add(new Obstacle("FLOWER 0", -72, -26.95, -67.2, -21.05, 21.5, YELLOW));
        f.obstacles.add(new Obstacle("FLOWER 1", -26.95, 67.2, -21.05, 72, 21.5, YELLOW));
        f.obstacles.add(new Obstacle("FLOWER 2", 67.2, 21.05, 72, 26.95, 21.5, YELLOW));
        f.obstacles.add(new Obstacle("FLOWER 3", 21.05, -72, 26.95, -67.2, 21.5, YELLOW));
        // Tape: LOADING ZONEs against the alliance walls and the GARDENs in the corners.
        f.zones.add(new Zone("Red LOADING ZONE", -48, -72, -24, -61, RED));
        f.zones.add(new Zone("Blue LOADING ZONE", 24, 61, 48, 72, BLUE));
        f.zones.add(new Zone("Red GARDEN", 68, -72, 70, -48, RED));
        f.zones.add(new Zone("Blue GARDEN", -70, 48, -68, 72, BLUE));
        f.rebuilder = () -> buildHives(f);
        f.rebuilder.run();
        return f;
    }

    /** Places the CELLs, their tag clusters and the drawing of both HIVEs for the current tip state. */
    private static void buildHives(Field f) {
        f.tags.clear(); f.clusters.clear(); f.shapes.clear(); f.goals.clear();
        // frame outline and the pivot bar
        f.shapes.add(new Shape("rect", new double[] { -19.475, -24.73, 19.475, 24.73 }, null, "#6c7a95", 1.5, null));
        f.shapes.add(new Shape("line", new double[] { 0, -24.73, 0, 24.73 }, null, "#6c7a95", 2, null));
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
            Cluster audience = audienceUp
                ? new Cluster(prefix + " AUDIENCE", upX, hy, upZ, upYaw, HIVE_TILT_DEG, audienceIds, CLUSTER_OFFSETS, CLUSTER_TAG_SIZE)
                : new Cluster(prefix + " AUDIENCE", downX, hy, downZ, downYaw, -HIVE_TILT_DEG, audienceIds, CLUSTER_OFFSETS, CLUSTER_TAG_SIZE);
            Cluster scoring = audienceUp
                ? new Cluster(prefix + " SCORING", downX, hy, downZ, downYaw, -HIVE_TILT_DEG, scoringIds, CLUSTER_OFFSETS, CLUSTER_TAG_SIZE)
                : new Cluster(prefix + " SCORING", upX, hy, upZ, upYaw, HIVE_TILT_DEG, scoringIds, CLUSTER_OFFSETS, CLUSTER_TAG_SIZE);
            for (Cluster cl : new Cluster[] { audience, scoring }) {
                f.clusters.add(cl);
                // the CELL mouth: ~12 in along the arm by 20 in across; a ball that reaches it scores
                f.goals.add(new Goal(cl.name, cl.x, cl.y, cl.z, 6, 10, 8));
                for (int i = 0; i < cl.memberIds.length; i++) {
                    double[] p = cl.memberPosition(cl.memberOffsets[i]);
                    f.tags.add(new AprilTag(cl.memberIds[i], cl.name + " " + (i + 1), p[0], p[1], p[2], cl.yawDeg, cl.tagSizeIn));
                }
                boolean up = cl.pitchDeg > 0;
                // CELL footprint: 20 in across (Y), ~12 in along the arm (X projection)
                double x0 = cl.x - 6, x1 = cl.x + 6;
                f.shapes.add(new Shape("rect", new double[] { x0, hy - 10, x1, hy + 10 }, up ? color + "aa" : color + "33", color, up ? 2.5 : 1, (red ? "R" : "B") + (up ? " up" : " down")));
                f.shapes.add(new Shape("line", new double[] { 0, hy, cl.x, hy }, null, color, up ? 3 : 1.5, null));
            }
        }
        // FLOWER openings and the pivot marks
        f.shapes.add(new Shape("circle", new double[] { -69.7, -24, 2.0 }, YELLOW + "88", YELLOW, 1.5, null));
        f.shapes.add(new Shape("circle", new double[] { -24, 69.7, 2.0 }, YELLOW + "88", YELLOW, 1.5, null));
        f.shapes.add(new Shape("circle", new double[] { 69.7, 24, 2.0 }, YELLOW + "88", YELLOW, 1.5, null));
        f.shapes.add(new Shape("circle", new double[] { 24, -69.7, 2.0 }, YELLOW + "88", YELLOW, 1.5, null));
    }
}
