---
summary: Phase 2 总览：前端模块拓扑 + 逐帧时序
refs:
  - path: sim/src/main/resources/web/3d/main.js
    anchor: "function handle(m)"
    lines: 112-151
    hash: 2b74052c84f3
    since: 2581a52
  - path: sim/src/main/resources/web/3d/main.js
    anchor: "function animate()"
    lines: 524-553
    hash: 828f395e07a1
    since: 2581a52
---

# FTCSim Phase 2 总览（第 7 步：结构图 + 逐帧时序图）

Phase 2「3D 前端怎么把后端世界画出来」到此收官。本文两张图：**图 1 = 前端模块 + 后端消息契约拓扑结构图**，**图 2 = 一次 `state` 消息的旅程 + 一个渲染帧的时序图**。

配色：**黄 = 后端/模拟器消息契约** · **粉 = 前端 ES module** · **灰 = 核心（Scene / WebSocket / canvas）** · **绿 = 输入端（手柄/键盘/拖拽）**。

> 配套结论见研究笔记 `notes/research/sim-physics-research-notes.md`（Phase 2 结论 28–47）；本图的详细版分见 `docs/frontend-3d/01-threejs-basics.md`、`02-field-3d-build.md`、`03-robot-parts.md`、`04-webcam-pip.md`
与 `docs/architecture/02-websocket-drive.md`、`03-coordinate-systems.md`。

## 图 1：前端模块拓扑 + 后端消息契约（结构图）

```mermaid
flowchart TB
  subgraph BACK["后端 · Simulator（JVM）"]
    BC["ftcsim-broadcast 20Hz 豁免<br/>stateMessage() 全量快照 :473-532"]
    FH["sendHello :356-370<br/>fieldMessage() :382-411"]
    WSX["ftcsim-ws-writer（每 client 一条）<br/>落后 64 帧丢帧"]
  end

  WS[("/ws 一条 WebSocket<br/>全双工：后端推，前端也发控制")]

  subgraph FE["前端 main.js（组合根 · 原生 ES modules）"]
    H["handle(m) 分发<br/>:112-151"]
    AN["animate() rAF 渲染循环<br/>:524-553"]
  end

  subgraph MOD["前端各模块（各管一块 canvas/DOM）"]
    FL["field.js 场地几何<br/>ftc(x,z,-y) 重建流水线"]
    RB["robot.js / parts.js<br/>机器人模型 + 声明零件<br/>两遍挂载 · 伺服/电机动画"]
    WC["webcam.js 画中画<br/>第二渲染器 + 检出框投影"]
    OV["overlay.js 场地画线<br/>sticky 生命周期"]
    BL["balls.js 球（纯展示）<br/>插值防闪光"]
    PN["panels.js HUD + Pedro 换算"]
  end

  subgraph IN["输入端"]
    GP["gamepad.js 三合一<br/>物理手柄/键盘/虚拟手柄 + rumble 回放"]
    DG["drag.js 拖拽<br/>sim.setPose 回写 · 乐观更新"]
  end

  BC --> WSX --> WS
  FH --> WSX
  WS --> H
  H --> FL
  H --> RB
  H --> WC
  H --> OV
  H --> BL
  H --> PN
  H --> AN
  AN -->|"robot.update / balls.update / field.update / webcam.render"| MOD
  AN -->|"renderer.render(scene,camera)"| SCENE[("Three.js Scene<br/>canvas#scene")]
  WC -->|"第二个 renderer 画到<br/>canvas#camCanvas"| PIP[("PiP 面板")]
  IN -->|"gamepad / sim.setPose / sim.* 控制消息"| H

  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  classDef core fill:#121820,stroke:#64748b,color:#cbd5e1
  classDef green fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  class BC,FH,WSX sim
  class H,AN,FL,RB,WC,OV,BL,PN web
  class WS,SCENE,PIP core
  class GP,DG green
```

**一句话**：`main.js` 是唯一入口 + 消息分发中枢；各模块只对"自己那块世界"负责；后端只推三种消息（hello/field/state），前端没有状态机、收到即覆盖。

## 图 2：一次 `state` 消息的旅程 + 一个渲染帧的时序（时序图）

```mermaid
sequenceDiagram
  participant BC as ftcsim-broadcast 20Hz 豁免
  participant WS as ws-writer
  participant H as handle(state)
  participant OBJ as robot/balls/overlay/…
  participant AN as animate() rAF ~60fps
  participant UI as 浏览器画面

  Note over BC: 每 50ms 构造全量快照（米→英寸/弧度→度已转好）
  loop 每 50ms
    BC->>WS: stateMessage() 序列化
    WS->>H: 写 socket（落后 64 帧丢帧）
    H->>OBJ: setState / setDrawings / updateHud（设目标）
  end

  Note over AN,UI: 每 rAF 帧（约 16ms）
  loop 每 ~16ms
    AN->>AN: dt = clock.getDelta()
    AN->>OBJ: robot.update(dt) 外推+指数平滑<br/>balls.update(dt) 插值 · field.update(dt)
    AN->>AN: webcam.render()（若相机面板展开）
    AN->>UI: renderer.render(scene, camera)
  end

  Note over UI,H: 反向：输入控制回路（异步，不阻塞画面）
  UI->>H: gamepad / sim.setPose / init·start·stop / sim.*
  H->>BC: onMessage（重活派生新线程）
```

**两条时钟**：后端 20 Hz 决定"目标在哪"，前端 60 fps 决定"怎么平滑走过去"——后端慢不影响画面流畅（结论 30、46）。

## 频率/节奏速查表

| 动作 | 频率 | 节拍来源 | 备注 |
|---|---|---|---|
| 后端推 `state` | **20 Hz**（50 ms） | `ftcsim-broadcast`（`Simulator.java:625`） | 全量快照，收到即覆盖 |
| 前端渲染 | **~60 fps**（rAF） | `animate()`（`main.js:524`） | 各 `update(dt)` 插值后 `renderer.render` |
| 拖拽回写 | **~30 Hz**（35 ms） | `drag.js SEND_MS=35`（`:9,31-40`） | `sim.setPose`，乐观更新 |
| 手柄轮询 | **~30 Hz**（33 ms） | `gamepad.js loop()`（`:205-251`） | `sendIfNeeded` 只在变化时发 |

## Phase 2 成果索引

| 图 | 文件 | 对应小步 |
|---|---|---|
| Three.js 基础（Scene/Camera/Renderer/坐标系/双层/帧循环） | `docs/frontend-3d/01-threejs-basics.md` | 准备篇 |
| WebSocket 消息驱动（hello/field/state + 双时钟） | `docs/architecture/02-websocket-drive.md` | 第 1 步 |
| 场地 → 3D（rebuild 流水线 + 契约缺口） | `docs/frontend-3d/02-field-3d-build.md` | 第 2 步 |
| mount → 零件（三层 Group + 两遍挂载 + 伺服/电机动画） | `docs/frontend-3d/03-robot-parts.md` | 第 3 步 |
| 画中画 + 检出框投影 + synced | `docs/frontend-3d/04-webcam-pip.md` | 第 4 步 |
| 三套坐标系总图（含数值演算） | `docs/architecture/03-coordinate-systems.md` | 第 5 步 |
| 赛季可复用分层（泄漏点与剥离） | `docs/seasons/01-season-abstraction.md` | 第 7 步配套 |
| **总览：结构 + 逐帧时序** | **`docs/frontend-3d/05-phase2-overview.md`** | **第 7 步** |

完整结论见研究笔记 `notes/research/sim-physics-research-notes.md`（Phase 1 结论 1–27；Phase 2 结论 28–47）。

## 源码证据

- 后端消息契约：`Simulator.java:356-370`（sendHello）、`:382-411`（fieldMessage）、`:473-532`（stateMessage）、`:611-652`（broadcastLoop 20Hz）、`:625`（广播 state）
- 前端入口与循环：`main.js:112-151`（handle 分发）、`:524-553`（animate）、`:63-96`（world pieces/trail）
- 各模块：`field.js:26`（ftc）、`robot.js:304-331`（update 平滑）、`parts.js:55-117`（buildPartMesh）、`webcam.js:30-47`（第二渲染器）、`overlay.js:257-265`（sticky）、`balls.js:46-83`（setState/update）、`panels.js:20-22`（Pedro）、`gamepad.js:205-251`（loop）、`drag.js:9,31-40`（sim.setPose）
