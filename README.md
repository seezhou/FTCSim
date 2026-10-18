# FTCSim

A desktop simulator that runs **unmodified** FIRST Tech Challenge robot code. Point it at a
repository based on [FtcRobotController](https://github.com/FIRST-Tech-Challenge/FtcRobotController),
pick an OpMode in the browser UI, press INIT / START and drive the simulated robot with a gamepad or
the keyboard.

The team code runs against the **real FTC SDK classes** (RobotCore, Hardware, FtcCommon, Vision,
downloaded from Maven Central in the version your repository declares). Only the pieces that talk to
real hardware or to Android are replaced by simulations: the hub controllers, I2C sensors, the
Limelight, VisionPortal / AprilTag detection, RobotLog, and a small `android.*` compatibility layer.
Third-party libraries such as [Pedro Pathing](https://github.com/Pedro-Pathing/PedroPathing), Panels
telemetry and FTC Dashboard work as they do on the robot.

## Requirements

* A JDK 17 or newer to build and run the simulator (JDK 21 is what the project is developed with).
  You do not need it on your path: `gradle/gradle-daemon-jvm.properties` tells the Gradle launcher to
  run the build on a JDK 21, which it takes from the JVMs installed on the machine (`/usr/lib/jvm`,
  `JAVA_HOME`, `~/.jdks`, SDKMAN, ...) or downloads from Adoptium on first use. The `java` on your
  path only starts the launcher, so a Java 8 JRE there is fine. To use a particular JDK instead, point
  `JAVA_HOME` at it before running `./gradlew`.
* Network access to Maven Central (plus the Gradle plugin portal, and api.adoptium.net if a JDK has to be
  downloaded) the first time; everything is cached afterwards.
* A modern browser (Chrome, Edge, Firefox). A physical gamepad works through the browser Gamepad API.

## Running

```bash
./gradlew run -Prepo=/path/to/your/FtcRobotController-based/repo
```

This compiles the team code (every Gradle module of the repository except `FtcRobotController`,
normally `TeamCode`) in-process, starts the web UI on http://localhost:8000 and opens a browser.
Source files are watched; press **Reload code** in the UI after editing (the sim rebuilds in a couple
of seconds and the OpMode list refreshes).

Options can be passed with `--args`:

```bash
./gradlew run -Prepo=../Decode --args="--port 9000 --no-browser"
./gradlew run -Prepo=../FtcRobotController --args="--samples"      # also load the official sample OpModes
./gradlew run -Prepo=../MyRepo --args="--prebuilt"                 # use the classes Android Studio built (Kotlin projects)
./gradlew run -Prepo=../MyRepo --args="--config configs/MyRobot.json"
```

The repository can also be set with the `FTCSIM_REPO` environment variable or a `ftcsim.properties`
file containing `repo=/path/to/repo` next to this README.

Team dependencies declared in the repository's `build.dependencies.gradle` / `TeamCode/build.gradle`
(`implementation 'group:artifact:version'`) are resolved automatically. Pedro Pathing and its
telemetry module are pulled from their Maven repository, or from `~/.m2` if you have built them
locally. Panels (`com.bylazar`) and FTC Dashboard (`com.acmerobotics.dashboard`) are replaced by
API-compatible shims whose telemetry, field drawings and `@Configurable` / `@Config` values show up in
the simulator's own tabs.

## The UI

* **Driver Station** – select an OpMode (TeleOp / Autonomous, grouped like the real DS), INIT, START,
  STOP, an optional 30 s / 2 min match timer, Restart Robot after an emergency stop.
* **Pause and step** – ⏸ Pause freezes the world *and* the robot code: the OpMode stops at its next
  hub transaction, so the pose, the sensors and the code all stand still together. ⏭ Step then runs a
  single slice of simulated time (1, 5, 20 or 100 ms) and freezes again, which is how you walk through
  a control loop. STOP still works while paused.
* **Field** – top view of the DECODE field with the AprilTags, goals and the obelisk. The robot, its
  wheel powers, the odometry trail and Pedro / Dashboard drawings are rendered live. Set the pose or
  drag the robot; choose the coordinate frame (FTC field, Pedro, or "code" which follows what the
  OpMode uses); choose which obelisk face is visible.
* **Robot / Hardware** – every device in the hardware map with live values: motor power, mode,
  encoder, velocity, current; servo position and pulse width; sensor readings (editable, e.g. touch
  sensors, distance, colour, digital pins); Pinpoint / OTOS pose; Limelight results; hub voltage and
  bulk-read statistics; VisionPortals and their detections.
* **Telemetry / Panels / Dashboard** – the OpMode's `telemetry`, Panels telemetry and FTC Dashboard
  packets; a graph tab plots numeric telemetry over time.
* **Config** – live editing of `@Configurable` / `@Config` static fields, as on Panels.
* **Log** – RobotLog, `System.out` / `System.err` and stack traces, filterable by level and text.
* **Gamepads** – two on-screen gamepads. Gamepad 1 is driven by the keyboard, both can be bound to a
  physical controller (press Start+A / Start+B on the controller like on a real Driver Station). Rumble
  requested by the team code is played on the bound controller and flagged with a `rumble` badge on the pad.

Keyboard map for gamepad 1:

| Keys | Gamepad |
|------|---------|
| W / S, A / D | left stick Y / X |
| Arrow keys | right stick |
| Q, E | left / right trigger |
| Z, X, C, V | A, B, X, Y |
| 1, 2 | left / right bumper |
| T, G, F, H | d-pad up / down / left / right |
| R, Backspace, Tab | start, back, guide |
| 3, 4 | left / right stick button |

## Robot configuration

Each repository gets a JSON file in `configs/` (named after the repository folder; created with
defaults the first time). It describes the physical robot, since the code alone does not say how heavy
the robot is or where the odometry pods are. `configs/Decode.json` is a complete example for the
St. Mark's Robotics DECODE robot.

```jsonc
{
  "name": "My robot",
  "field": "DECODE",                    // or "EMPTY"
  "lengthIn": 18, "widthIn": 17, "massKg": 13.3,
  "batteryVolts": 12.8, "batteryResistanceOhms": 0.06,
  "assumeTeamDirectionsCorrect": true,   // setDirection(REVERSE) in code means the motor is mounted mirrored
  "emulateVelocityOverflow": true,       // 16-bit velocity wrap of the REV hub
  "autoCreateDevices": true,             // create devices the code asks for but the config lacks
  "snapToCodePose": true,                // move the robot when the code calls Pinpoint/OTOS setPosition during INIT
  "codeCoordinateFrame": "auto",         // "ftc", "pedro" or "auto" (pedro when Pedro Pathing is on the classpath)
  "startPose": { "x": 0, "y": 0, "headingDeg": 90 },
  "hardwareLatency": {                   // what a hub transaction costs the robot code, milliseconds
    "enabled": true,
    "bulkReadMs": 2.0,                   // one bulk read of a hub
    "writeMs": 2.5,                      // one write: motor power, run mode, servo position...
    "readMs": 2.0,                       // one single (non-bulk) read
    "i2cMs": 2.5                         // one I2C transaction: Pinpoint, OTOS, colour, distance, IMU
  },
  "drivetrain": {
    "type": "mecanum",                   // "mecanum", "tank" or "none"
    "wheelDiameterIn": 4.094, "trackWidthIn": 13.5, "wheelBaseIn": 11,
    "gearRatio": 1.0, "motorType": "goBILDA_435", "tractionCoefficient": 0.8,
    "forwardDecelInPerSec2": 34.6, "lateralDecelInPerSec2": 65, "angularDecelDegPerSec2": 500,
    "leftFront": "frontLeft", "rightFront": "frontRight", "leftRear": "backLeft", "rightRear": "backRight"
  },
  "hubs": [ { "name": "Control Hub", "address": 173, "parent": true }, { "name": "Expansion Hub 2", "address": 2 } ],
  "devices": [
    { "name": "frontLeft", "type": "DcMotorEx", "hub": "Control Hub", "port": 0, "motorType": "goBILDA_435" },
    { "name": "launchLeft", "type": "DcMotorEx", "motorType": "goBILDA_6000", "inertia": 0.00025 },
    { "name": "hoodServo", "type": "Servo", "secondsFullRange": 0.6 },
    { "name": "odo", "type": "GoBildaPinpointDriver", "xPod": { "offsetIn": 5.56, "ticksPerMm": 13.26 }, "yPod": { "offsetIn": -5.28 } },
    { "name": "imu", "type": "IMU" },
    { "name": "limelight", "type": "Limelight3A", "mount": { "x": 0, "y": 0, "z": 14, "yawDeg": 0, "pitchDeg": 0 } },
    { "name": "Webcam 1", "type": "WebcamName", "mount": { "x": 7, "y": 0, "z": 8 }, "hfovDeg": 70 },
    { "name": "intakeSensor", "type": "Rev2mDistanceSensor" },
    { "name": "color", "type": "RevColorSensorV3" },
    { "name": "lowerTransferSensor", "type": "DigitalChannel" }
  ]
}
```

Device types: `DcMotorEx`, `Servo`, `CRServo`, `AnalogInput`, `DigitalChannel`, `TouchSensor`, `LED`,
`IMU`, `VoltageSensor`, `RevColorSensorV3`, `Rev2mDistanceSensor`, `GoBildaPinpointDriver`,
`SparkFunOTOS`, `Limelight3A`, `RevBlinkinLedDriver`, `WebcamName`, `LynxModule`. Any other SDK I2C
driver class the code asks for is created on a virtual bus (writes accepted, reads return zeros).
Motor types come from the goBILDA / REV / AndyMark / TETRIX catalogue (`goBILDA_312`, `goBILDA_435`,
`goBILDA_6000`, `REV_HD_HEX_20`, `REV_CORE_HEX`, `NeveRest_20`, ...) and carry the same orientation the
SDK uses, so `Direction.FORWARD` means the same thing as on the robot.

## What is simulated

* 1 kHz rigid-body physics: DC motor torque/speed/current model per motor type, mecanum and tank
  chassis with traction limits, rolling friction from the Pedro-style zero-power decelerations, field
  walls and DECODE obstacles, battery sag.
* REV hub behaviour: `RUN_USING_ENCODER` / `RUN_TO_POSITION` PIDF loops in hub units, 16-bit velocity
  overflow, bulk caching modes (OFF / MANUAL / AUTO) with read counters, servo PWM ranges.
* Sensors: encoders, IMU (yaw / pitch / roll, angular velocity), goBILDA Pinpoint with pod offsets and
  encoder directions, SparkFun OTOS, distance / colour / touch sensors editable from the UI,
  Limelight 3A AprilTag results (`tx`, `ty`, `ta`, botpose, fiducials) and VisionPortal AprilTag
  detections (`ftcPose`, `robotPose`) computed from the true robot pose and camera mount.
* Hub I/O cost: each bulk read takes 2 ms and each write 2.5 ms of the robot code's time (I2C devices
  2.5 ms), so loop times match a real robot and bulk caching pays off exactly as it does on the field.
  The `I/O` pill in the toolbar counts the transactions and the time they cost since INIT.
* Driver Station semantics: INIT / START / STOP, `opModeIsActive()`, stuck-OpMode detection,
  emergency stop on uncaught exceptions with the stack trace in the log, gamepad edge detection,
  telemetry, match timer.
* Gamepad rumble: `gamepad1.rumble(...)`, `rumbleBlips()` and `stopRumble()` are drained from the
  OpMode's effect queues and replayed on the controller bound to that gamepad through the browser
  Gamepad API. The on-screen pad shows a `rumble` badge so the effect is visible without a controller.
* Team code lifecycle: fresh class loader per build (statics reset like a robot restart), `@TeleOp` /
  `@Autonomous` / `@Disabled` discovery, `SelectableOpMode`s, OpMode registration from
  `OpModeRegistrar` methods.

Not simulated: camera frames (custom `VisionProcessor`s and EasyOpenCV pipelines get no images;
colour-blob / predominant-colour processors return empty results), sounds, Wi-Fi / Driver Station
networking, the REV hub firmware's exact PID tuning. AprilTag detections use the tag library the OpMode
passes to the processor: tags the library places on the field are detected at those positions, unplaced
tags (for example the DECODE obelisk faces, or the sample tags SDK 12's "current game" library returns
between seasons) are matched by id against the simulated field, so tags that are not on the field are
never detected. I2C driver classes without a simulation model (OctoQuad, HuskyLens, navX, LED sticks,
Modern Robotics sensors) are created on a virtual bus and read zeros. Devices the code asks for by an
interface (`DistanceSensor`, `IMU`) are created as the REV part unless the configuration names another
type (for example `"type": "AndyMarkTOF"`), exactly as the robot configuration decides on the real robot.

## Tests

```bash
./gradlew test                                            # physics and hardware unit tests
node tools/wsclient.mjs 8000 BlueTeleOp 4                 # drive an OpMode over the WebSocket API
node tools/run-all.mjs 8000 4                             # init/start/stop every OpMode, report exceptions
node tools/ui-test.mjs 8000                               # Playwright smoke test of the browser UI
```

`tools/dev-run.sh <repo> <port> [args]` starts the simulator in the background for the scripts above.

## Layout

* `android-compat/` – the `android.*` / `androidx.annotation` classes the SDK needs on a JVM.
* `ftc-sdk/` – builds one desktop jar from the SDK Android archives (classes listed in
  `shadowed-classes.txt` are dropped in favour of the simulator's versions).
* `sim/` – the simulator: physics (`ftcsim.physics`), hardware models (`ftcsim.hardware`), OpMode
  runner (`com.qualcomm.robotcore.eventloop.opmode.SimOpModeRunner`), team code compiler
  (`ftcsim.teamcode`), web server and UI (`ftcsim.web`, `src/main/resources/web`), Panels / Dashboard
  shims (`com.bylazar`, `com.acmerobotics.dashboard`), vision (`ftcsim.vision`,
  `org.firstinspires.ftc.vision`).
* `configs/` – robot configurations.
* `tools/` – development and test scripts.
