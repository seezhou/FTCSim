# FTCSim

![The 3D view: BioBuzz field, robot mid-run, and the robot's own camera boxed onto a RED AUDIENCE tag](demo.png)

A desktop simulator that runs **unmodified** FIRST Tech Challenge robot code. Point it at a
repository based on [FtcRobotController](https://github.com/FIRST-Tech-Challenge/FtcRobotController),
press INIT / START in the browser, and drive the simulated robot with a gamepad or the keyboard.

The screenshot above is the 3D view: the field and robot in three dimensions, the robot's camera
picture-in-picture in the lower left showing what the program actually sees — here the
`AprilTagProcessor` reporting RED AUDIENCE at 100% — with the hardware, telemetry and log panels
around it.

This is a fork of [WheatleyFromPortal2/FTCSim](https://github.com/WheatleyFromPortal2/FTCSim). That
project runs its first release on [this commit](https://github.com/WheatleyFromPortal2/FTCSim/commit/7ad0de4)
and the upstream has been quiet since, so this fork carries on from there. **The upstream README is the
reference for anything not listed under [What this fork adds](#what-this-fork-adds)** — the simulator
core, the hardware models and the configuration format are unchanged and are documented there in full.

The team code runs against the **real FTC SDK classes** (RobotCore, Hardware, FtcCommon, Vision,
downloaded from Maven Central in the version your repository declares). Only the pieces that talk to
real hardware or to Android are replaced by simulations.

## Requirements

* A JDK 17 or newer to build and run the simulator (JDK 21 is what it is developed with). You do not
  need it on your path — `gradle/gradle-daemon-jvm.properties` points the Gradle launcher at a JDK 21
  it finds on the machine or downloads from Adoptium.
* Network access to Maven Central the first time; everything is cached afterwards.
* A modern browser (Chrome, Edge, Firefox).

## Running

```bash
./gradlew run -Prepo=/path/to/your/FtcRobotController-based/repo
```

This compiles the team code in-process, starts the web UI and opens a browser. Sources are watched:
press **Reload code** after editing.

The repository is taken from the Gradle `-Prepo` property (or `--repo` if you run the simulator
main class directly). Other options:

```bash
./gradlew run -Prepo=../Decode --args="--port 9000 --no-browser"
./gradlew run -Prepo=../MyRepo --args="--config configs/MyRobot.json"
./gradlew run -Prepo=../MyRepo --args="--samples"      # also load the official sample OpModes
./gradlew run -Prepo=../MyRepo --args="--prebuilt"     # use classes Android Studio built (Kotlin projects)
```

Team dependencies from the repository's Gradle files are resolved automatically; see the upstream
README for what that covers.

## The UI

Two front ends ship side by side:

| URL | View |
|-----|------|
| `/` | **3D view** (default) — the field and robot in three dimensions, with the robot camera in a panel |
| `/2d/` | **2D view** — the original top-down interface |

Both carry the same Driver Station, hardware, telemetry, config and log panels, so only the field
rendering differs. The 2D view additionally has the two on-screen gamepads with their sticks and the
rumble indicator; the 3D view uses the physical controller directly.

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

## What this fork adds

### A 3D view with a picture-in-picture robot camera

`/` opens a Three.js view of the field: orbit, zoom and pan, the robot modelled with its wheels and
drivetrain, and — in the lower-left corner — a panel showing **what the robot's own camera sees**.

Drag the robot to move it, shift-drag or right-drag to turn it. The pose can also be typed into the
Pose / View panel and set with the FTC field, Pedro or code coordinate frame.

The camera pod is not a decoration: it is placed from the mount your team code declares on its
`WebcamName` device, including yaw, pitch and horizontal FOV, so the panel frames the field the way
the real camera does. Whatever your `AprilTagProcessor` detects is boxed on that view using the same
fiducial positions and camera mount the backend uses for its own results, so you can check detection
angles and IDs against what the program actually receives. A pitch slider takes over when no mount is
configured, and the status line says which of the two is in use.

### Gamepad rumble on the bound controller

`gamepad1.rumble(...)`, `rumbleBlips()` and `stopRumble()` used to be drained and dropped. They are
now drained from the OpMode's effect queues once per event loop pass, mirroring what
`FtcEventLoopHandler.gamepadEffects()` does on a robot, relayed over the WebSocket and replayed on the
controller bound to that gamepad through the browser Gamepad API. `large` / `small` map to the strong
and weak motors, a `duration` of −1 keeps the effect running until the next one or a stop, and the
2D gamepad shows a `rumble` badge so it is visible without a controller attached. LEDs are still not
forwarded — the browser Gamepad API has no LED support.

### Fixes

* **Hub timing on Windows.** `LockSupport.parkNanos` rounds every request up to the 15.6 ms scheduler
  tick there, so parking the first millisecond of a 2.5 ms hub write actually cost 15 ms and every
  OpMode ran about six times slower than on a real robot. Waits now sleep in whole milliseconds and
  spin the last 1.5 ms, which is what it takes to keep the latency emulation honest on Windows.
* **The UI could wedge silently and only a restart fixed it.** The state broadcast ran on one thread
  and wrote to every connected client inline under a lock, so a browser tab that stopped reading —
  reloading the page, or switching from the 2D view to the 3D one — filled a TCP send buffer and
  blocked the write forever. Every other client then stopped receiving state, with nothing in the
  console and nothing in the server log. Each client now has its own writer thread behind a bounded
  queue: sending never blocks, a client that keeps dropping frames is disconnected, and closing a
  client shuts its socket down first, which is what releases a stuck writer.

## Tests

```bash
./gradlew test                                            # physics and hardware unit tests
node tools/wsclient.mjs 8000 BlueTeleOp 4                 # drive an OpMode over the WebSocket API
node tools/run-all.mjs 8000 4                             # init/start/stop every OpMode
```

## Layout

* `sim/` — the simulator: physics (`ftcsim.physics`), hardware models (`ftcsim.hardware`), OpMode
  runner (`com.qualcomm.robotcore.eventloop.opmode.SimOpModeRunner`), team code compiler
  (`ftcsim.teamcode`), web server (`ftcsim.web`), vision (`ftcsim.vision`).
* `sim/src/main/resources/web/3d/` — the 3D view and the robot camera panel.
* `sim/src/main/resources/web/2d/` — the original 2D view.
* `android-compat/`, `ftc-sdk/` — the SDK desktop jar and the `android.*` shims.
* `configs/` — robot configurations, one per repository, named after its folder.

The configuration format, the device models and the simulator core are documented in the
[upstream README](https://github.com/WheatleyFromPortal2/FTCSim/blob/main/README.md); `configs/Decode.json`
is a complete example.
