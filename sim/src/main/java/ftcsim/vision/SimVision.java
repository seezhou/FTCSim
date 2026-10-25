package ftcsim.vision;

import ftcsim.physics.Chassis;
import ftcsim.physics.Field;
import ftcsim.physics.Pose2;
import org.firstinspires.ftc.vision.VisionPortalImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Shared context for the simulated vision stack (VisionPortal / AprilTag processors): the true robot pose and the field. */
public final class SimVision {
    private static volatile Chassis chassis;
    private static volatile Supplier<Field> field = () -> null;
    private static final List<VisionPortalImpl> portals = new CopyOnWriteArrayList<>();

    private SimVision() {}

    public static void install(Chassis c, Supplier<Field> f) { chassis = c; field = f; }
    public static Pose2 robotPose() { Chassis c = chassis; return c == null ? new Pose2(0, 0, 0) : c.pose(); }
    public static Field field() { return field.get(); }

    public static void register(VisionPortalImpl p) { portals.add(p); }
    public static void unregister(VisionPortalImpl p) { portals.remove(p); }
    /** Closes every portal, like the Robot Controller does when an OpMode stops. */
    public static void closeAll() { for (VisionPortalImpl p : new ArrayList<>(portals)) { try { p.close(); } catch (RuntimeException ignored) {} } }

    /** Device-panel entries for the active portals. */
    public static List<Map<String, Object>> views() {
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (VisionPortalImpl p : portals) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", "VisionPortal " + (++i) + " (" + p.cameraLabel() + ")");
            v.put("type", "VisionPortal");
            v.put("hub", "Control Hub");
            v.put("port", 0);
            v.put("autoCreated", false);
            v.put("values", p.view());
            out.add(v);
        }
        return out;
    }
}
