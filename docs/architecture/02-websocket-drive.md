---
summary: hello/field/state 三类消息如何驱动前端；20 Hz 推送与 60 fps 渲染的双时钟
refs:
  - path: sim/src/main/java/ftcsim/Simulator.java
    anchor: "private Map<String, Object> stateMessage()"
    lines: 473-532
    hash: bcf940fb4b84
    since: e9256b5
  - path: sim/src/main/java/ftcsim/Simulator.java
    anchor: "private void sendHello(WebServer.Client client)"
    lines: 356-370
    hash: f8ce26c1202f
    since: e9256b5
---

# WebSocket 消息如何驱动 3D（Phase 2 · 第 1 步）

一句话：**后端每隔 50 ms 把整个世界"拍一张全量快照"发给前端，前端收到后直接覆盖各模块的目标状态，再用自己的 `requestAnimationFrame` 插值画出来**。后端只管发，前端只管画，两边通过一条 `/ws` 连接单向流动。

---

## 图 1：消息全景——谁在什么时候发什么

```mermaid
flowchart TB
  subgraph BACK["后端（JVM · Simulator）"]
    direction TB
    H["hello（按需，一次性）<br/>客户端连上后主动请求<br/>Simulator.java:356-370"]
    F["field（事件驱动）<br/>HIVE 倾覆 / 场地选项被改<br/>broadcastField :378-380"]
    S["state（周期 20 Hz）<br/>broadcastLoop 每 50ms<br/>stateMessage() :473-532"]
    E["事件类（随时）<br/>ball.score / hive.tip / toast<br/>gamepad.rumble / opmodes / log"]
  end

  W[("/ws 同一条 WebSocket<br/>全双工：后端推，前端也发控制")]

  subgraph FRONT["前端（浏览器 · main.js handle）"]
    direction TB
    H2["handle(hello) :115<br/>field.rebuild + robot.setSpec<br/>→ 重建整个 3D 场景"]
    F2["handle(field) :125<br/>field.rebuild + renderBioBuzz<br/>→ 只重建场地"]
    S2["handle(state) :131<br/>robot / devices / balls / launcher<br/>drawings / telemetry / HUD"]
    E2["其余 :140-150<br/>比分提示 / HIVE 提示 / 手柄震动"]
  end

  H --> W --> H2
  F --> W --> F2
  S --> W --> S2
  E --> W --> E2

  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef wire fill:#121820,stroke:#64748b,color:#cbd5e1
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  class H,F,S,E sim
  class W wire
  class H2,F2,S2,E2 web
```

**三种不同的触发节奏**（这是理解整个前端的钥匙）：

| 消息 | 节奏 | 触发条件 | 体量 |
|---|---|---|---|
| `hello` | **按需一次** | 前端连上后发 `{type:"hello"}`，后端回应（:309） | 大：整个场地 + 机器人规格 + 起始位姿 |
| `field` | **事件驱动** | HIVE 倾覆使 CELL 移动、或场地选项被改 | 大：整份场地描述 |
| `state` | **周期 20 Hz** | broadcastLoop 每 50 ms（:625） | 中：全量快照，每次都重发所有设备/球/遥测 |
| `log` / `toast` / `ball.score` / `hive.tip` | 事件 | 随时 | 小 |

> `hello` 之后后端还会**紧跟着单独发一条 `opmodes`**（:368），所以 OpMode 下拉列表不用等下一次 state。

---

## 图 2：`state` 消息的字段 → 分别驱动前端哪个模块（本步核心图）

`state` 是**全量快照**：不管有没有变化，每次都把所有东西发一遍。所以前端不需要维护状态机，收到就直接覆盖。

```mermaid
flowchart TB
  ST["state 消息（20 Hz）<br/>由 stateMessage() 构造<br/>Simulator.java:473-532"]

  ST --> R["robot<br/>x · y · headingDeg<br/>vx · vy · omegaDeg · wheels · epoch"]
  ST --> D["devices<br/>所有设备的实时视图<br/>(含 Webcam mount + AprilTag 检出)"]
  ST --> B["balls<br/>每个球的 x/y/z · 半径 · 状态 · 进没进 CELL"]
  ST --> L["launcher<br/>弹匣剩余 · 容量 · 发射角 pitchDeg"]
  ST --> DW["drawings<br/>Panels / Dashboard 的场地画线<br/>(如 Road Runner 规划路径)"]
  ST --> OP["opmode<br/>状态 IDLE/INIT/RUNNING/ERROR · 名字 · 运行时长 · 报错"]
  ST --> TL["telemetry / panels / dashboard<br/>三套遥测的文本行"]
  ST --> SIM["sim<br/>paused · stepRate · simTime · timeScale · io 统计"]
  ST --> BAT["battery<br/>电压 · 电流"]

  R --> R3["robot.setState → 3D 机器人位姿<br/>main.js:132"]
  D --> D3["syncCameraTeam → 设备面板 + 相机挂载 + 检出框<br/>main.js:133"]
  B --> B3["balls.setState → 3D 里的球<br/>main.js:134"]
  L --> L3["updateLauncher → 弹药显示 + pitch 滑块<br/>main.js:135"]
  DW --> DW3["overlay.setDrawings → 场地上的画线<br/>main.js:136"]
  OP --> OP3["updateHud + syncOverlayLifecycle<br/>main.js:137-138"]
  TL --> TL3["renderTelemetry → 遥测文本框<br/>main.js:300-315"]
  SIM --> SIM3["顶部状态条：暂停 / 步率 / I·O 统计"]
  BAT --> BAT3["batteryPill 电压显示"]

  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  class ST sim
  class R,D,B,L,DW,OP,TL,SIM,BAT sim
  class R3,D3,B3,L3,DW3,OP3,TL3,SIM3,BAT3 web
```

**两个必须记住的单位约定**（后端在 `stateMessage()` 里就转好了）：

- 位置：`robot.x / y` 已经是**英寸**——后端物理内部是**米**，发送前用 `Units.mToIn(...)` 换算（:481-482）；
- 角度：`headingDeg / omegaDeg` 是**度**（后端内部是弧度，发送前 `Math.toDegrees(...)`）。

也就是说：**后端负责把物理单位翻译成"前端好画"的单位**，前端拿到的直接就是英寸和度，不用再做换算。

---

## 图 3：`hello` / `field` 如何重建 3D 场景

`hello` 是唯一的"建场"消息。它携带的 `fieldMessage()`（:382-411）是一份完整的场地描述：

```mermaid
flowchart LR
  HM["fieldMessage()<br/>Simulator.java:382-411"]
  HM --> Z["zones 区域色块"]
  HM --> SH["shapes 场地线条/形状"]
  HM --> CL["clusters CELL（含 yaw/pitch）"]
  HM --> OP["options 场地选项下拉<br/>（如 obelisk 选哪一面）"]
  HM --> TG["tags AprilTag 标签<br/>id · x/y/z · yawDeg · size"]
  HM --> OB["obstacles 障碍物（轴对齐盒）"]

  Z --> RB["前端 field.rebuild(m.field)<br/>清空旧几何 → 按描述重建 3D<br/>main.js:118 / :128"]
  SH --> RB
  CL --> RB
  TG --> RB
  OB --> RB
  OP --> BB["renderBioBuzz → 场地选项下拉<br/>main.js:120 / :129"]

  RB --> SCENE[("Three.js Scene<br/>新的场地几何")]
  BB --> HTML[("HTML 面板<br/>BioBuzz 选项")]

  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  classDef core fill:#121820,stroke:#64748b,color:#cbd5e1
  class HM,Z,SH,CL,OP,TG,OB sim
  class RB,BB web
  class SCENE,HTML core
```

同时 `hello` 里的 `robot`（机器人规格）+ `startPose` 交给 `robot.setSpec(...)`（:119）——**零件怎么摆、初始位姿在哪，都是这条消息决定的**（第 3 步研究过的 `DeviceMount` 就在这里起作用，后续第 3 小步会展开）。

`hello` 还会顺手 `clearTrail()` 和 `balls.clear()`（:122-123）——**新连接 = 新的一场**，轨迹和球都要清掉。

---

## 图 4：两条独立的时钟——20 Hz 推送 vs 60 fps 渲染

这是"驱动"最微妙的地方，也是你在屏幕上看到的运动是**平滑的**而不是**一顿一顿**的原因。

```mermaid
flowchart LR
  subgraph BACK["后端：20 Hz"]
    B1["broadcastLoop 每 50ms"]
    B2["stateMessage() 全量快照"]
    B1 --> B2
  end

  subgraph FRONT["前端：rAF 约 60 fps"]
    F1["handle(state) 收到快照<br/>写入各模块的目标状态"]
    F2["animate() 每约 16ms 一帧<br/>robot.update(dt) 朝目标插值/平滑"]
    F3["renderer.render(scene, camera)"]
    F1 --> F2 --> F3
  end

  B2 -->|"WebSocket"| F1
  B1 -.->|"两条节奏互不阻塞<br/>后端慢不影响前端画面流畅"| F2

  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  class B1,B2 sim
  class F1,F2,F3 web
```

**类比**：后端 20 Hz 推送 ≈ 服务端每 50 ms 告诉你"目标位置在这"；前端 60 fps 渲染 ≈ 你用 rAF 做补间动画把物体平滑移过去。这和你做过的"服务端推数据 + 前端补间"**完全同构**。

所以：`robot.setState()` 只是**设定目标**，`robot.update(dt)` 才负责**平滑地走过去**（`main.js:528`）。如果你看到机器人移动有"黏滞感"，那是插值系数的问题，不是后端 tick 慢。

---

## 一个真实的前后端协同细节（值得单独讲）

`state` 里的 `drawings`（场地画线，如 Road Runner 的规划路径）在后端有 **3 秒新鲜度限制**：

```java
// Simulator.java:499-502
if (pd != null && System.currentTimeMillis() - pd.time < 3000) drawings.add(...);
if (dd != null && System.currentTimeMillis() - dd.time < 3000) drawings.add(...);
```

后果：**团队代码停止画线 3 秒后，后端就不再广播这条路径**——早期版本里这导致路径在比赛中途"闪一下就没了"。于是前端改成 **sticky 策略**（`main.js:258-268`）：自己记住画线不主动过期，只在**加载新 OpMode / 点 Clear / 重启机器人**这三个时机才清。

> 这是典型的"后端语义不够用，前端补一层策略"的协同案例。再遇到类似"UI 上东西莫名消失"的问题，第一反应应该是查后端广播的新鲜度/生命周期条件。

## 源码证据

**后端（JVM）**
- `Simulator.java:309`（收到前端 hello 请求）、`356-370`（sendHello：field + robot + startPose + 紧跟 opmodes）
- `Simulator.java:378-380`（broadcastField）、`382-411`（fieldMessage：zones/shapes/clusters/options/tags/obstacles）
- `Simulator.java:473-532`（stateMessage：robot/devices/balls/launcher/drawings/telemetry/sim/battery/drivetrain/codeFrame）
- `Simulator.java:611-652`（broadcastLoop 20 Hz）、`:625`（广播 state）

**前端（浏览器）**
- `main.js:103-110`（connect / send，同一条 `/ws`）
- `main.js:112-151`（handle：hello / field / state / opmodes / toast 等分支）
- `main.js:131-139`（state 各字段的分发）
- `main.js:258-268`（overlay 的 sticky 生命周期策略）
- `main.js:524-553`（animate 渲染循环与插值）
