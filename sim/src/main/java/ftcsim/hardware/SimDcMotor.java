package ftcsim.hardware;

import com.qualcomm.robotcore.hardware.DcMotorImplEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;
import ftcsim.physics.MotorState;
import ftcsim.vision.DeviceMount;

import java.util.Map;

/** A DcMotorEx backed by a simulated motor port. Uses the SDK's own DcMotorImplEx logic. */
public class SimDcMotor extends DcMotorImplEx implements SimDevice {
    private final SimMotorController simController;
    private final MotorState state;
    private final String name;
    private final boolean assumeDirectionsCorrect;
    private final DeviceMount mount;

    public SimDcMotor(SimMotorController controller, int port, MotorConfigurationType type, MotorState state, String name, boolean assumeDirectionsCorrect) {
        this(controller, port, type, state, name, assumeDirectionsCorrect, null);
    }

    public SimDcMotor(SimMotorController controller, int port, MotorConfigurationType type, MotorState state, String name, boolean assumeDirectionsCorrect, DeviceMount mount) {
        super(controller, port, DcMotorSimple.Direction.FORWARD, type);
        this.simController = controller;
        this.state = state;
        this.name = name;
        this.assumeDirectionsCorrect = assumeDirectionsCorrect;
        this.mount = mount;
    }

    public MotorState state() { return state; }

    @Override public DeviceMount simMount() { return mount; }

    @Override public synchronized void setDirection(DcMotorSimple.Direction direction) {
        super.setDirection(direction);
        if (assumeDirectionsCorrect) {
            // The team declares this motor reversed because that is how it is mounted: mirror the physics.
            state.setMountSign(direction == DcMotorSimple.Direction.REVERSE ? -1 : 1);
        }
    }

    @Override public String getDeviceName() { return state.type.name.replace('_', ' ') + " motor"; }
    @Override public String getConnectionInfo() { return simController.hub().getName() + "; port " + getPortNumber(); }
    @Override public String simName() { return name; }
    @Override public String simType() { return "DcMotorEx"; }
    @Override public String simHub() { return simController.hub().getName(); }
    @Override public int simPort() { return getPortNumber(); }
    @Override public void fillView(Map<String, Object> v) {
        v.put("power", state.power);
        v.put("duty", state.duty());
        v.put("mode", state.mode.name());
        v.put("direction", state.mountSign < 0 ? "REVERSE" : "FORWARD");
        v.put("zeroPower", state.zeroPowerBehavior.name());
        v.put("position", state.encoderTicks());
        v.put("velocity", state.velocityTps());
        v.put("velocityExact", state.velocityTpsExact());
        v.put("rpm", state.physicalOmega() * 60.0 / (2 * Math.PI));
        v.put("current", state.currentAmps());
        v.put("volts", state.appliedVolts());
        v.put("enabled", state.enabled);
        v.put("busy", state.isBusy());
        if (state.targetPositionSet) v.put("target", state.targetPosition);
        if (state.useVelocityTarget) v.put("targetVelocity", state.targetVelocityTps);
        v.put("motorType", state.type.name);
        v.put("drive", state.isDriveWheel);
    }
}
