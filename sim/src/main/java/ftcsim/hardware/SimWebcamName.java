package ftcsim.hardware;

import android.content.Context;
import com.qualcomm.robotcore.util.SerialNumber;
import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.external.function.Continuation;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraCharacteristics;
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.internal.system.Deadline;

import java.util.Map;

/** A webcam configuration entry. Frames are not simulated; VisionPortal pipelines get synthetic detections. */
public class SimWebcamName implements WebcamName, SimDevice {
    private final String name;
    private final SerialNumber serial = SerialNumber.createFake();
    private final ftcsim.vision.CameraMount mount;
    public SimWebcamName(String name) { this(name, new ftcsim.vision.CameraMount()); }
    public SimWebcamName(String name, ftcsim.vision.CameraMount mount) { this.name = name; this.mount = mount; }
    /** Where the camera sits on the robot (used by the simulated VisionPortal). */
    public ftcsim.vision.CameraMount mount() { return mount; }
    @Override public SerialNumber getSerialNumber() { return serial; }
    @Override public String getUsbDeviceNameIfAttached() { return "FTCSim virtual webcam"; }
    @Override public boolean isAttached() { return true; }
    @Override public boolean isWebcam() { return true; }
    @Override public boolean isCameraDirection() { return false; }
    @Override public boolean isSwitchable() { return false; }
    @Override public boolean isUnknown() { return false; }
    @Override public void asyncRequestCameraPermission(Context context, Deadline deadline, Continuation<? extends Consumer<Boolean>> continuation) {
        try { continuation.dispatch(consumer -> consumer.accept(true)); } catch (RuntimeException ignored) {}
    }
    @Override public boolean requestCameraPermission(Deadline deadline) { return true; }
    @Override public CameraCharacteristics getCameraCharacteristics() { return null; }
    @Override public Manufacturer getManufacturer() { return Manufacturer.Unknown; }
    @Override public String getDeviceName() { return "Webcam"; }
    @Override public String getConnectionInfo() { return "virtual USB"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
    @Override public String simName() { return name; }
    @Override public String simType() { return "WebcamName"; }
    @Override public String simHub() { return "Control Hub"; }
    @Override public int simPort() { return 0; }
    @Override public void fillView(Map<String, Object> v) { v.put("attached", true); v.put("mount", mount.toView()); }
}
