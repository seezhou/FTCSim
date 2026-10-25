# FTCSim project notes

Desktop simulator for FTC robot code (see README.md for usage). Key facts for working on it:

## Build / run
- `./gradlew run -Prepo=<repo>`; dev loop: `tools/dev-run.sh <repo> <port> [--samples]` then
  `node tools/wsclient.mjs <port> <OpMode> [s]`, `node tools/run-all.mjs <port> [s] [regex]`
  (`RUN_DISABLED=1` includes @Disabled OpModes), `node tools/ui-test.mjs <port>` (Playwright, needs
  `tools/node_modules -> /opt/node22/lib/node_modules` symlink or a local Playwright install).
- The SDK version is read from the repo's `build.dependencies.gradle` (`org.firstinspires.ftc:RobotCore:x`).
  Both 11.0.0 and 12.0.0 must compile: `./gradlew :sim:compileJava` and
  `./gradlew :sim:compileJava -Prepo=<sdk12 repo>`. SDK 12 differences handled so far: `ServoControllerEx`
  pulse width methods, `ServoController.forgetLastKnownPosition`, `AprilTagDetection` abstract with
  `AprilTagSingleDetection` (constructed reflectively), Gamepad trigger thresholds.
- Unit tests: `./gradlew test` (`ChassisTest`, `MotorDirectionTest`). Tests that need Pedro on the
  classpath are not kept (the test classpath only has Pedro when `-Prepo` points at a repo using it).
- Scratch checkouts used during development live in the session scratchpad (`decode`, `ftcrc`, `pedro`).

- Java: `gradle/gradle-daemon-jvm.properties` (daemon JVM criteria, JDK 21, hand-written Adoptium download
  URLs because `updateDaemonJvm` needs api.foojay.io, which the sandbox blocks) makes the Gradle daemon run
  on a JDK 21 even when `./gradlew` is launched by a Java 8 JRE. The foojay plugin in settings needs Java 17+
  itself, so it only loads once the daemon is on 21. Root build.gradle.kts additionally falls back to a
  JDK 21 compile/run toolchain if the daemon JVM has no compiler; `-Pftcsim.jdk=N` forces a version.
  Test the launcher path with a jlink'ed compiler-less runtime: `JAVA_HOME=<jre> ./gradlew :sim:compileJava`.

## Architecture rules
- Real SDK classes are used wherever possible; only hardware/Android-bound classes are shadowed
  (list in `ftc-sdk/shadowed-classes.txt`; the shadow must live at the same FQN in `sim/`).
  Nested classes of a shadowed class are stripped too, so a shadow must re-declare them.
- `MotorState.physicalSign = mountSign * orientationSign`. The SDK inverts raw power/encoder sign for
  CCW motor types (goBILDA, REV HD Hex); `MotorType.ccw` mirrors that. Never set `physicalSign` directly.
- Frames: FTC field frame (center origin; +X to the right when the goal wall is on the left, +Y up,
  CCW heading; `Field.decode()` places the goal tags at x = -58.37). Pedro: `pedro_x = FTC_y + 72`,
  `pedro_y = 72 - FTC_x`, `FTC_heading = pedro_heading + 90°` (see `SimHardware.snapTo`, app.js
  `toPedro`/`fromPedro`). Tag library quaternions: the tag's local +Z points INTO the tag face.
- Hub PIDF emulation: `PidfController`, 16-bit output units, I/D scaled by `HUB_LOOP_HZ` (20), integral
  zero-crossing reset. Default velocity coefficients F = 32767/maxTps, P = 0.1F, I = 0.01F.
- OpMode runner: an Error in the OpMode thread is caught through `RobotLog.threadPoolErrorHook`
  (the SDK's ThreadPool only logs it). INIT after an emergency stop restarts the robot automatically.
- Gamepad effects: `gamepad1.rumble(...)` / `rumbleBlips()` / `stopRumble()` are queued on the OpMode's
  *own* `gamepad1`/`gamepad2` instances (the runner creates them in `init`, tags them with
  `setUserForEffects` and never replaces them — `newGamepadDataAvailable` only copies state, so queued
  effects survive). `SimOpModeRunner.drainGamepadEffects()` polls those queues once per 20 ms event loop
  pass, mirroring `FtcEventLoopHandler.gamepadEffects()` (50 ms, `CMD_RUMBLE_GAMEPAD`), and `Simulator`
  relays them as `gamepad.rumble` / `gamepad.rumble.stop` websocket messages. app.js replays the steps on
  the controller bound to that gamepad through `vibrationActuator.playEffect('dual-rumble')`, mapping
  `large`->strong and `small`->weak; a zero-power effect and the stop message reset the actuator, and a
  `-1` duration step is played as a long effect that a later effect or the stop message cancels.
  `ledQueue`/`setLedColor` are still not forwarded (the Gamepad API has no LED support).
- VisionPortal: `VisionPortalImpl` runs a 30 Hz loop driving `SimVisionProcessor`s;
  `AprilTagProcessorImpl` computes detections from `SimVision` (true pose + field + camera mount from
  the `WebcamName` config entry or `setCameraPose`). No camera frames exist.
- `ftcsim.hardware.HardwareBus` charges hub latency (bulk read 2 ms, write 2.5 ms, read 2 ms, I2C 2.5 ms,
  all from `RobotConfig.hardwareLatency`) to the calling thread and holds the pause gate. Rules:
  never charge while holding a device monitor (the physics thread samples the same devices and would
  stall), and never let the UI read through the SDK's synchronized wrappers such as
  `DcMotorImpl.getCurrentPosition()` (robot code frozen by a pause holds that monitor). Device views read
  `MotorState`/`ServoState` directly. Simulator-owned threads call `markExemptThread()`/`exempt(...)`:
  broadcast, physics, vision, the OpMode stop thread.
- Pause freezes world and robot code together: `World.frozen()` = paused with no step budget;
  `World.stepFor(s)` opens a budget (the UI's Step button). Robot code blocks in `HardwareBus.gate()` at
  its next hub call, in the runner's event loop, and in `publishTelemetry`. Code that neither touches
  hardware nor calls telemetry keeps running until it does.
- Unknown I2C driver classes are instantiated on `VirtualI2cDevice` (reads return zeros); interfaces map
  to drivers through `SimHardware.I2C_IMPLS` (OctoQuad). Legacy `BNO055IMU` has a real model (`SimBNO055IMU`).
- Android `R` classes: `ftc-sdk` generates `<aar package>.R` + `ftcsim.generated.ResourceNames` from each
  AAR's `R.txt` / `res/values/values.xml` (task `generateR`); `Main` installs the string resolver so
  `getDeviceName()` etc. return the SDK's English strings.
- SDK 12's `AprilTagGameDatabase.getCurrentGameTagLibrary()` returns unplaced sample tags (583-586), so the
  official AprilTag samples detect nothing on the DECODE field; Decode (SDK 11) uses the DECODE library.
- Sweep results are kept in `build/*-sweep*.log`; `ClassFactory.getInstance()` is `SimClassFactory`.
- Launcher: balls live in `World.projectiles` and are integrated from `World.step` like everything
  else. A shot is inferred, never announced by team code: `trigger.servo` crossing `position`, or
  the flywheel passing `minRpm` when no servo is named. Three traps here — a servo ramps at
  `secondsFullRange`, so the trigger has to be a level crossing with hysteresis (a per-poll jump
  test misses slow servos entirely); `minRpm` is the speed the ball *waits for* after the push, not
  a gate checked once, so a team whose spin-up is shorter than its flywheel's acceleration still
  gets a full-speed shot; and it must be set near the wheel's real firing speed, or the ball leaves
  while the wheel is still spinning up and barely clears the robot. The angle is not fixed at
  configuration time either: the launcher row's own pitch slider overrides it at runtime over
  `sim.launcherPitch` (the camera panel's pitch slider is the camera's alone), and the configured
  `pitchDeg` is only where that slider starts.

## Conventions
- Commit messages: plain description, no model identifiers. Written in **English**, matching the
  existing history (`git log --oneline` shows all-English messages); imperative mood — "Add …",
  "Fix …", "Move …". Chinese is not used in commit messages.
- Language: prose in `docs/` and `notes/research/` is written in **Chinese**; code identifiers,
  class/method names, config keys and WebSocket message `type` values stay in **English**.
  Introduce a term once as 中文（English name）, then stick to one name for it.
- Do not use the Frontend Design skill unless asked. UI is vanilla JS/CSS in `sim/src/main/resources/web`.

## Documentation map (load on demand)

Long-form write-ups live in `docs/` (one topic per file: Mermaid diagrams plus a "source evidence"
list), grouped by area: `docs/architecture`, `docs/physics`, `docs/vision`, `docs/frontend-3d`,
`docs/launcher`, `docs/seasons`. The running conclusions log is `notes/research/`.
**Start at `docs/index.md`, then load a file only when the task touches that topic** — do not read
them all up front.

| Area | Doc | Read it when |
|---|---|---|
| Overview | `docs/index.md` | start here — the topic index and suggested reading routes |
| Architecture | `docs/architecture/01-threads.md` | thread topology; "who advances the tick" |
| Architecture | `docs/architecture/02-websocket-drive.md` | the hello/field/state messages that drive the web UI |
| Architecture | `docs/architecture/03-coordinate-systems.md` | converting FTC / Pedro / Three.js frames (Pedro is UI-only) |
| Architecture | `docs/architecture/04-phase1-overview.md` | Phase 1 summary: module+thread structure and per-tick timing |
| Physics | `docs/physics/01-motor-signal-chain.md` | setPower to physics to encoder reads; hub PIDF |
| Physics | `docs/physics/02-sensor-model.md` | IMU, servo `secondsFullRange`, digital/analog, I2C |
| Physics | `docs/physics/03-world-physics.md` | `World.step` order, chassis kinematics, projectiles/launcher |
| Physics | `docs/physics/04-hub-timing-gate.md` | hub latency, pause gate, thread exemption |
| Vision | `docs/vision/01-vision.md` | AprilTag geometric detection (there are no camera frames) |
| Vision | `docs/vision/02-occlusion.md` | AprilTag occlusion design draft (no impl yet): geometry inputs, obstacle / tag-to-tag ray tests |
| Frontend 3D | `docs/frontend-3d/01-threejs-basics.md` | new to Three.js: core concepts with web analogies |
| Frontend 3D | `docs/frontend-3d/02-field-3d-build.md` | how the field description becomes 3D geometry |
| Frontend 3D | `docs/frontend-3d/03-robot-parts.md` | how parts are placed and animated from device mounts |
| Frontend 3D | `docs/frontend-3d/04-webcam-pip.md` | picture-in-picture camera and the AprilTag boxes |
| Frontend 3D | `docs/frontend-3d/05-phase2-overview.md` | Phase 2 summary: frontend module topology + per-frame timing |
| Frontend 3D | `docs/frontend-3d/06-inputs-and-overlays.md` | balls / overlay / drag / gamepad four-module diagrams (conclusions 41–44) |
| Launcher | `docs/launcher/01-ball-launcher.md` | projectiles, launcher config/inference, HIVE/CELL, 9 pitfalls |
| Seasons | `docs/seasons/01-season-abstraction.md` | reusable vs season-specific layering; interface signatures + migration steps for stripping season code |
| Known issues | `docs/known-issues.md` | known bugs and deferred fixes |
| Research log | `notes/research/sim-physics-research-notes.md` | running conclusions log: Phase 1 = 1–27, Phase 2 = 28–47 |

Caveats:
- Each doc ends with `file:line` references. Line numbers drift — re-check against the code before
  trusting a claim, and prefer symbol names when searching.
- The numeric prefixes are **writing order, not a topic taxonomy**; a topic-based reorganisation is
  planned, so route via the table above rather than guessing from filenames.
- Facts that must not silently drift (hub latencies, default constants) belong in tests under
  `sim/src/test/`, not only in prose.
