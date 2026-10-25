# Third-party notices

FTCSim is a fork of [WheatleyFromPortal2/FTCSim](https://github.com/WheatleyFromPortal2/FTCSim).
The upstream repository does not currently declare a license, so this file does not grant rights to
upstream code or relicense it. See the License section of [README.md](README.md).

Full license texts are in [`licenses/`](licenses/). The table lists what the simulator actually
redistributes or loads; team libraries (RoadRunner, Pedro Pathing, FTCLib, ...) are resolved from the
simulated team repository at build time and remain under their own licenses.

## Components

| Component | Version | License | How FTCSim uses it |
|---|---|---|---|
| FIRST Tech Challenge SDK — RobotCore, Hardware, FtcCommon, Vision | the version declared by the team repository (10.x / 11.x / 12.x) | BSD-3-Clause-style FIRST license (`licenses/BSD-3-Clause-FIRST.txt`) | `ftc-sdk` merges their `classes.jar` into the desktop SDK jar |
| [Three.js](https://threejs.org/) | 0.170.0 | MIT (`licenses/MIT.txt`) | loaded by the 3D view from jsDelivr at runtime |
| [EasyOpenCV](https://github.com/OpenFTC/EasyOpenCV) | 1.7.3 | MIT, as declared in the Maven POM (`licenses/MIT.txt`) | `ftc-sdk` bundles its Java classes |
| [OpenFTC AprilTag](https://github.com/OpenFTC/apriltag) | 2.1.0 | MIT, as declared in the Maven POM (`licenses/MIT.txt`) | `ftc-sdk` bundles its Java classes |
| OpenCV repackaged for Android (`org.openftc:opencv-repackaged-bundled-dylibs`) | 4.10.0-A | MIT, as declared in the Maven POM; OpenCV itself is Apache-2.0 (`licenses/Apache-2.0.txt`) | `ftc-sdk` bundles its Java classes; native libraries are not loaded |
| [Gson](https://github.com/google/gson) | 2.10.1 | Apache-2.0 (`licenses/Apache-2.0.txt`) | JSON/WebSocket messages |
| [Kotlin standard library](https://kotlinlang.org/) | 1.9.24 | Apache-2.0 (`licenses/Apache-2.0.txt`) | runtime for Kotlin-based team libraries |
| [JSON-java](https://github.com/stleary/JSON-java) (`org.json:json`) | 20240303 | Public Domain, as declared in the Maven POM | Android SDK compatibility APIs |
| [JUnit Jupiter](https://junit.org/junit5/) | 5.10.2 | Eclipse Public License 2.0 (`licenses/EPL-2.0.txt`) | tests only; not part of the distributed simulator |

## Copyright notices

- Three.js: Copyright © 2010-2024 three.js authors.
- FIRST Tech Challenge SDK: Copyright © 2014-2022 FIRST. All rights reserved.
- EasyOpenCV, OpenFTC AprilTag, OpenCV repackaged: see the linked project pages for the notices
  shipped with each artifact.
- OpenCV: see the OpenCV project for its copyright notices.
- Gson: see the Gson project for its copyright notices.
- Kotlin: see the Kotlin project for its copyright notices.
- JUnit: see the JUnit project for its copyright notices.
