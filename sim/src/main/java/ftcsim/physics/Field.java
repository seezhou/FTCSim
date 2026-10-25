package ftcsim.physics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

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
    private SeasonRules rules = SeasonRules.NONE;

    /** The season rules that act on this field's movable elements. */
    public SeasonRules rules() { return rules; }

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

    /** Sets several options as one action and rebuilds once, for a season reset. */
    synchronized void setOptions(Map<String, String> values) {
        boolean changed = false;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!e.getValue().equals(state.put(e.getKey(), e.getValue()))) changed = true;
        }
        if (changed) rebuilder.run();
    }

    private static final Map<String, Supplier<Field>> FACTORIES = new LinkedHashMap<>();
    static {
        FACTORIES.put("DECODE", Field::decode);
        FACTORIES.put("BIOBUZZ", Field::biobuzz);
        FACTORIES.put("EMPTY", Field::empty);
    }

    /** Adds a season to the registry. A new season only needs its own factory and rules. */
    public static void register(String name, Supplier<Field> factory) {
        FACTORIES.put(name.trim().toUpperCase(Locale.ROOT), factory);
    }

    public static Field byName(String name) {
        if (name == null) return decode();
        Supplier<Field> factory = FACTORIES.get(name.trim().toUpperCase(Locale.ROOT));
        return factory == null ? decode() : factory.get();
    }

    /**
     * The registered season whose name appears in free-form text (a repository or configuration
     * name), or null. Lets an auto-created configuration pick the right field without knowing
     * any season by name.
     */
    public static String nameFromHint(String text) {
        if (text == null) return null;
        String upper = text.toUpperCase(Locale.ROOT);
        for (String name : FACTORIES.keySet()) {
            if (name.length() >= 3 && upper.contains(name)) return name;
        }
        return null;
    }

    /** The CELL cluster with this exact name, or null (the DECODE goals are not clusters). */
    public Cluster cluster(String name) {
        if (name == null) return null;
        for (Cluster c : clusters) if (c.name.equals(name)) return c;
        return null;
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
        f.rules = new DecodeRules();
        return f;
    }

    public static Field empty() { Field f = new Field(); f.name = "Empty"; return f; }

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
        f.obstacles.add(new Obstacle("FLOWER 0", -72, -26.95, -67.2, -21.05, 21.5, BiobuzzRules.YELLOW));
        f.obstacles.add(new Obstacle("FLOWER 1", -26.95, 67.2, -21.05, 72, 21.5, BiobuzzRules.YELLOW));
        f.obstacles.add(new Obstacle("FLOWER 2", 67.2, 21.05, 72, 26.95, 21.5, BiobuzzRules.YELLOW));
        f.obstacles.add(new Obstacle("FLOWER 3", 21.05, -72, 26.95, -67.2, 21.5, BiobuzzRules.YELLOW));
        // Tape: LOADING ZONEs against the alliance walls and the GARDENs in the corners.
        f.zones.add(new Zone("Red LOADING ZONE", -48, -72, -24, -61, BiobuzzRules.RED));
        f.zones.add(new Zone("Blue LOADING ZONE", 24, 61, 48, 72, BiobuzzRules.BLUE));
        f.zones.add(new Zone("Red GARDEN", 68, -72, 70, -48, BiobuzzRules.RED));
        f.zones.add(new Zone("Blue GARDEN", -70, 48, -68, 72, BiobuzzRules.BLUE));
        f.rules = new BiobuzzRules();
        f.rebuilder = () -> BiobuzzRules.buildHives(f);
        f.rebuilder.run();
        return f;
    }
}
