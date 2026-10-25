---
summary: AprilTag 是几何检出而非图像处理；视锥六重门槛与噪声模型
refs:
  - path: sim/src/main/java/org/firstinspires/ftc/vision/apriltag/AprilTagProcessorImpl.java
    anchor: "public void simFrame(CameraMount mount"
    lines: 165-253
    hash: 0a126747f897
    since: 2581a52
  - path: sim/src/main/java/org/firstinspires/ftc/vision/VisionPortalImpl.java
    anchor: "private void frameLoop()"
    lines: 78-95
    hash: 1f17ae93ffd6
    since: 2581a52
---

# FTCSim Vision（Phase 1 · 第 6 步：`VisionPortalImpl` → `AprilTagProcessorImpl`）

配色：**黄 = 模拟器实现**（ftcsim.* 及挂在 SDK 包名下的 shadow）· **蓝 = 真 SDK 基类**（`VisionPortal` / `AprilTagProcessor`）· **绿 = 团队代码** · **红 = 明确不仿真的边界** · **灰 = 真值来源**。

本步一句话结论：**没有摄像头**。30 Hz 的 `frameLoop` 只驱动实现了 `SimVisionProcessor` 的第一方 processor，检出是**纯几何 + 高斯噪声**算出来的；团队自写的 processor **会被 `init()` 一次，此后永不受帧**并会在构造时 warn 列出类名。

## 图 1：Vision 全链路

```mermaid
flowchart LR
  subgraph TEAM["团队代码（OpMode）· 绿"]
    T1["new VisionPortal.Builder()<br/>.setCamera(webcam)<br/>.addProcessor(aprilTag)"]
    T2["aprilTag.getDetections()<br/>.getFreshDetections()"]
  end

  subgraph SDK["真 SDK 基类 · 蓝"]
    VP["VisionPortal"]
    ATP["AprilTagProcessor"]
  end

  subgraph SIM["30Hz ftcsim-vision 线程（豁免）· 黄"]
    FL["frameLoop()<br/>markExemptThread() （接第5步）<br/>next += 1e9/30，落后则重锚定"]
    SF["对每个 enabled 且 implements<br/>SimVisionProcessor 的 processor<br/>调 simFrame(mount, w, h, now)"]
  end

  subgraph TRUTH["真值来源 · 灰"]
    RP["SimVision.robotPose()<br/>← chassis.pose()（真值）"]
    FLD["SimVision.field()<br/>← 场地 tags / clusters"]
  end

  subgraph GEO["几何检出 · 无任何像素处理"]
    SEE["Camera.see()：视锥判定<br/>见 图2 六重门槛"]
    PL["placement()：标签放哪<br/>库位置 or 场地 tags<br/>obelisk 只留选中那一面"]
    PS["poses()：叠高斯噪声<br/>→ Ftc / Raw / robot Pose3D"]
  end

  T1 --> VP --> FL --> SF --> SEE
  RP --> SEE
  FLD --> PL --> SEE
  SEE --> PS --> T2

  classDef team fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  classDef sdk fill:#101a2b,stroke:#3b82f6,color:#bfdbfe
  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef truth fill:#121820,stroke:#64748b,color:#cbd5e1
  class T1,T2 team
  class VP,ATP sdk
  class FL,SF,SEE,PL,PS sim
  class RP,FLD truth
```

## 图 2：`Camera.see()` —— 一个标签到底凭什么看不见（return-null 链）

这张图用来解释"为什么某个 tag 检测不到"。**任一条件失败就返回 null**：

```mermaid
flowchart TD
  SEE["Camera.see(x, y, z, yawDeg, minFacing)"] --> D1{"距离 dist 小于 2 in?"}
  D1 -->|"是"| N["❌ 不可见"]
  D1 -->|"否"| D2{"facing 小于 minFacing?<br/>标签 0.25 / cluster 0.15<br/>（标签没大致朝向相机）"}
  D2 -->|"是"| N
  D2 -->|"否"| D3{"inCam.x ≤ 0?<br/>（在相机背后）"}
  D3 -->|"是"| N
  D3 -->|"否"| D4{"|az| 大于 hfov/2?<br/>（水平出视锥，默认 70.4°）"}
  D4 -->|"是"| N
  D4 -->|"否"| D5{"|el| 大于 vfov/2?<br/>（垂直出视锥，默认 43.3°）"}
  D5 -->|"是"| N
  D5 -->|"否"| D6{"range3 大于 maxRange?<br/>（默认 200 in）"}
  D6 -->|"是"| N
  D6 -->|"否"| VIS["✅ 通过：算出 az/el/range3<br/>xF/yF/zF 与像素 txp/typ"]
  VIS --> PX{"apparentPx = size·focal/range3·facing<br/>小于 6 px?"}
  PX -->|"是"| N
  PX -->|"否"| OUT["加入检出，并叠距离相关高斯噪声<br/>noise = noiseIn·max(1, range3/48)"]

  classDef no fill:#1c1114,stroke:#ef4444,color:#fecaca
  classDef yes fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  class SEE,VIS,PX sim
  class OUT yes
  class N no
```

> 远处标签不是"变模糊"，而是**彻底消失**（丢进 6 px 门槛）。`Random` 种子固定为 11，所以同一段运行是可复现的。

## 图 3：相机到底装在哪（mount vs `setCameraPose`，含朝天防呆）

```mermaid
flowchart TD
  SF["simFrame 开始"] --> CP{"团队调过 setCameraPose()?<br/>（cameraPoseInverse 非空）"}
  CP -->|"否"| M["用配置的 CameraMount<br/>x前 y左 z上，zIn 默认 8in<br/>hfov 70.4 / vfov 43.3（C920 级）<br/>maxRange 200in，noise 0.3"]
  CP -->|"是"| ID{"camPose 是单位矩阵?"}
  ID -->|"是（等于没设）"| M
  ID -->|"否"| V{"光轴 |fZ| 大于 0.99?<br/>（正上 / 正下）"}
  V -->|"是"| W["⚠ warn：相机朝天/朝地<br/>回退用配置的 mount<br/>（团队的 setCameraPose 被忽略）"]
  V -->|"否"| USE["从矩阵反推（坐标变换）<br/>camX=get(1,3), camY=−get(0,3), camZ=get(2,3)<br/>camYawRel=atan2(−fX, fY)<br/>camPitch=atan2(fZ, hypot(fX,fY))"]
  M --> CAM["Camera 成像参数"]
  W --> CAM
  USE --> CAM

  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef no fill:#1c1114,stroke:#ef4444,color:#fecaca
  class SF,M,USE,CAM sim
  class W no
```

## 图 4：模拟器**不**仿真什么（团队自写 processor 的天花板）

```mermaid
flowchart TB
  subgraph DO["✅ 仿真"]
    YES["SDK 自带且 implements SimVisionProcessor<br/>如 AprilTagProcessorImpl：几何检出<br/>每帧被 simFrame 驱动"]
  end
  subgraph DONT["❌ 明确不仿真"]
    NO1["团队自写 VisionProcessor<br/>init() 被调用一次，此后永不收帧<br/>构造时 RobotLog.ww 列出类名"]
    NO2["processFrame(Mat, …) 恒返回 null"]
    NO3["getFrameBitmap()<br/>返回一张空白 Bitmap（有对象无内容）"]
    NO4["getCameraControl()<br/>动态代理万能 stub<br/>set 存值 / get 回值 / boolean→true / Max→100"]
    NO5["遮挡、曝光、运动模糊、自定义 CV<br/>根本没有像素，无从谈起"]
  end

  classDef yes fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  classDef no fill:#1c1114,stroke:#ef4444,color:#fecaca
  class YES yes
  class NO1,NO2,NO3,NO4,NO5 no
```

> **这意味着什么**：FTC 里常见的"用 OpenCV 自己做颜色/形状识别"的代码，在 FTCSim 里会通过构造和初始化不报错，但**永远查不到任何结果**——这是设计上的诚实告知（warn 了），不是 bug。

## 三个容易忽略的细节

| 细节 | 说明 |
|---|---|
| **暂停时视觉仍在跑** | 视觉线程是豁免线程，不付 hub 时延也不过暂停门；但 `robotPose()` 被冻结，所以暂停期间 AprilTag 读数**保持不变也不消失** |
| **tag 的 +Z 指向 tag 内部** | 库 quaternion 变换出的 `VectorF(0,0,1)` 是"指向 tag 内"的法线，标签朝向取反方向：`yawDeg = degrees(atan2(−z.y, −z.x))` |
| **`getRobotPose()` 是"带噪声的真值"** | `Pose3D robot` = `robotPose()` 真值 + 高斯噪声，不是视觉推算出来的——所以用它做定位会比真实机器人乐观 |

## SDK 11 / 12 兼容（全靠反射）

静态初始化块里探测：优先找 SDK 12 的 `AprilTagSingleDetection` 带 `DistanceUnit` 的构造函数；找不到退回 SDK 11 的 `AprilTagDetection` 老签名。SDK 12 的 cluster API（`getAllClusters` / `AprilTagClusterMetadata` / `AprilTagClusterDetection`）同样用 `Class.forName` 探测，失败置 null 时整个 cluster 分支跳过。检出的 cluster 置信度 = **可见成员数 / 总成员数**，成员越多噪声除以 `sqrt(visible)` 越准。

## 源码证据

- `sim/src/main/java/org/firstinspires/ftc/vision/VisionPortalImpl.java:25-32（类注释/FPS）, 45-65（init + notSimulated warn）, 78-95（frameLoop）, 126-150（动态代理）, 155-158（空白 Bitmap）`
- `sim/src/main/java/org/firstinspires/ftc/vision/apriltag/AprilTagProcessorImpl.java:115-138（see 六重门槛）, 141-163（placement / tag +Z / obelisk）, 165-253（simFrame）, 200-202（6px + 噪声）, 51-80（SDK 11/12 反射）`
- `sim/src/main/java/ftcsim/vision/SimVision.java:17-33（真值上下文）`
- `sim/src/main/java/ftcsim/vision/CameraMount.java:5-10（默认参数）`

配套研究笔记：`notes/research/sim-physics-research-notes.md` 结论 24–27。
