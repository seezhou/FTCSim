package ftcsim.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Description of the simulated robot: chassis, drivetrain, hubs and hardware
 * devices (what the Robot Controller's XML configuration plus the physical
 * robot would define). Serialised as JSON in FTCSim/configs/&lt;repo&gt;.json.
 */
public class RobotConfig {
    public String name = "default";
    public String field = "DECODE";
    public String notes = "";
    public double lengthIn = 18;
    public double widthIn = 18;
    /** Height of the chassis box, inches. The 3D view draws the frame at this height so a tall robot reads as one. */
    public double heightIn = 6;
    /** How opaque the chassis is drawn, 0..1. Low enough to see mechanisms inside the frame. */
    public double chassisOpacity = 0.22;
    public double massKg = 12;
    public double batteryVolts = 12.8;
    public double batteryResistanceOhms = 0.06;
    /** Treat the directions/offsets the team code configures as physically correct (no mirrored wheels or pods). */
    public boolean assumeTeamDirectionsCorrect = true;
    /** Reproduce the REV hub's 16-bit encoder velocity overflow above 32767 ticks/s. */
    public boolean emulateVelocityOverflow = true;
    /** Create devices the team code asks for but that are not listed here. */
    public boolean autoCreateDevices = true;
    /** While an OpMode initialises, move the simulated robot to the pose the code writes into its localizer (Pinpoint/OTOS setPosition). */
    public boolean snapToCodePose = true;
    /** Coordinate frame the team code uses for field poses: "pedro" (0..144 in, x away from goal wall), "ftc" (centre origin) or "auto". */
    public String codeCoordinateFrame = "auto";
    /** Cost of talking to the hubs; set enabled=false for an instant, unrealistic bus. */
    public Latency hardwareLatency = new Latency();
    public StartPose startPose = new StartPose();
    public Drivetrain drivetrain = new Drivetrain();
    public List<Hub> hubs = new ArrayList<>();
    public List<Device> devices = new ArrayList<>();

    public static class Latency {
        public boolean enabled = true;
        /** One bulk read of a hub (all motor/encoder/digital/analog values at once). */
        public double bulkReadMs = 2.0;
        /** One write: a motor power, a run mode, a servo position... */
        public double writeMs = 2.5;
        /** One single (non-bulk) read of a hub value. */
        public double readMs = 2.0;
        /** One I2C transaction: Pinpoint, OTOS, colour/distance sensors, the hub IMU. */
        public double i2cMs = 2.5;
    }

    public static class StartPose {
        /** FTC field coordinates, inches; heading in degrees CCW from +X. */
        public double x = 0, y = 0, headingDeg = 90;
        public StartPose() {}
        public StartPose(double x, double y, double headingDeg) { this.x = x; this.y = y; this.headingDeg = headingDeg; }
    }

    public static class Drivetrain {
        /** mecanum | tank | none */
        public String type = "mecanum";
        public double wheelDiameterIn = 3.78;   // 96 mm goBILDA mecanum
        public double trackWidthIn = 14;
        public double wheelBaseIn = 12;
        public double gearRatio = 1.0;          // wheel rev per motor output rev
        public String motorType = "goBILDA_312";
        public double tractionCoefficient = 0.75;
        /** Zero-power decelerations (in/s^2), like Pedro Pathing's tuners measure. */
        public double forwardDecelInPerSec2 = 35;
        public double lateralDecelInPerSec2 = 65;
        public double angularDecelDegPerSec2 = 400;
        /** Viscous friction as fraction of coulomb at 1 m/s. */
        public double viscousFraction = 0.25;
        public String leftFront, rightFront, leftRear, rightRear;   // mecanum motor names
        public List<String> left = new ArrayList<>(), right = new ArrayList<>(); // tank motor names
    }

    public static class Hub {
        public String name;
        public int address;
        public boolean parent;
        public Hub() {}
        public Hub(String name, int address, boolean parent) { this.name = name; this.address = address; this.parent = parent; }
    }

    public static class Device {
        public String name;
        /** DcMotorEx, Servo, CRServo, AnalogInput, DigitalChannel, TouchSensor, LED, IMU, VoltageSensor, RevColorSensorV3, Rev2mDistanceSensor, GoBildaPinpointDriver, SparkFunOTOS, Limelight3A, RevBlinkinLedDriver, WebcamName */
        public String type;
        public String hub;
        public Integer port;
        public Map<String, Object> params = new LinkedHashMap<>();
        public transient boolean autoCreated;

        public Device() {}
        public Device(String name, String type) { this.name = name; this.type = type; }
        public Device param(String key, Object value) { params.put(key, value); return this; }

        public double num(String key, double def) {
            Object v = params.get(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) { try { return Double.parseDouble((String) v); } catch (NumberFormatException e) { return def; } }
            return def;
        }
        public boolean bool(String key, boolean def) {
            Object v = params.get(key);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) return Boolean.parseBoolean((String) v);
            return def;
        }
        public String str(String key, String def) {
            Object v = params.get(key);
            return v == null ? def : String.valueOf(v);
        }
        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String key) {
            Object v = params.get(key);
            return v instanceof Map ? (Map<String, Object>) v : null;
        }
        public static double num(Map<String, Object> m, String key, double def) {
            if (m == null) return def;
            Object v = m.get(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) { try { return Double.parseDouble((String) v); } catch (NumberFormatException e) { return def; } }
            return def;
        }
        public static boolean bool(Map<String, Object> m, String key, boolean def) {
            if (m == null) return def;
            Object v = m.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }
        public static String str(Map<String, Object> m, String key, String def) {
            if (m == null) return def;
            Object v = m.get(key);
            return v == null ? def : String.valueOf(v);
        }
    }

    public Device findDevice(String name) {
        for (Device d : devices) if (d.name.equals(name)) return d;
        return null;
    }

    public Hub findHub(String name) {
        for (Hub h : hubs) if (h.name.equals(name)) return h;
        return null;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static RobotConfig defaults() {
        RobotConfig c = new RobotConfig();
        c.hubs.add(new Hub("Control Hub", 173, true));
        c.hubs.add(new Hub("Expansion Hub 2", 2, false));
        c.devices.add(new Device("imu", "IMU").param("hub", "Control Hub"));
        return c;
    }

    public static RobotConfig load(File file) throws IOException {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        RobotConfig c = GSON.fromJson(json, RobotConfig.class);
        if (c.hubs == null) c.hubs = new ArrayList<>();
        if (c.devices == null) c.devices = new ArrayList<>();
        if (c.drivetrain == null) c.drivetrain = new Drivetrain();
        if (c.startPose == null) c.startPose = new StartPose();
        if (c.hubs.isEmpty()) c.hubs.add(new Hub("Control Hub", 173, true));
        for (Device d : c.devices) if (d.params == null) d.params = new LinkedHashMap<>();
        return c;
    }

    public void save(File file) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), toJson().getBytes(StandardCharsets.UTF_8));
    }

    public String toJson() { return GSON.toJson(this); }
    public static RobotConfig fromJson(String json) { return GSON.fromJson(json, RobotConfig.class); }
}
