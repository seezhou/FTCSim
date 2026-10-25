---
summary: 进程内线程拓扑；谁在推 tick（1 kHz physics 是唯一推 tick 的线程）
refs:
  - path: sim/src/main/java/ftcsim/physics/World.java
    anchor: "private void loop()"
    hash: 1c9b9200be2d
  - path: sim/src/main/java/ftcsim/Simulator.java
    anchor: "private void broadcastLoop()"
    hash: ee465b48d71f
---

# FTCSim 进程内线程拓扑（Phase 1 · 第 1 步：总览梳理）

配色按代码归属：**黄 = 模拟器实现**（ftcsim.*，含挂在 SDK 包名下的 shadow）· **蓝 = 真 SDK** · **绿 = 团队代码**（FTC Control 机器人代码）· 粉 = Web 出口。

核心问题「tick 是谁在推」的答案：只有 `ftcsim-physics` 一条线在推（1 kHz 固定步），其余线程全是观察者/消费者。

```mermaid
flowchart TB
  MAIN["Main 主线程<br/>装配 Simulator.start() 后 join() 挂起"]

  subgraph LOOPS["周期线程 · 全部 daemon"]
    direction LR
    PHYS["ftcsim-physics · 1 kHz<br/>World.loop() 固定步 DT=1ms<br/>追赶 catchUp&lt;50 · step()每步:<br/>底盘→电机→舵机→球→电池"]
    EV["ftcsim-eventloop · 50 Hz<br/>SimOpModeRunner 的 DS 心跳<br/>gate() → newGamepadDataAvailable<br/>→ SDK 回调 → 团队 loop()"]
    BC["ftcsim-broadcast · 50 Hz<br/>launcher.poll + drain scores/tips<br/>广播 state/log · 有 client 才推"]
    VIS["ftcsim-vision-* · 30 Hz<br/>simFrame() 真实 pose 算检出<br/>无图像帧"]
    OPTR["SDK ThreadPool<br/>OpMode 生命周期<br/>internalInit/Start/Stop"]
    TEAM["团队 OpMode 线程<br/>runOpMode()/loop()<br/>FTC Control 机器人代码"]
  end
  BUILD["ftcsim-initial-build / rebuild<br/>TeamCode.build() + WatchService"]

  subgraph STATE["共享设备状态 · 唯一被多方读写处"]
    HUB["MotorState / ServoState + HardwareBus<br/>gate(): 暂停时机器人代码在此冻住<br/>收 hub 时延: bulk读 2 · 写 2.5 · 读 2 · I2C 2.5 ms<br/>豁免: broadcast / physics / vision / OpMode-stop"]
  end

  subgraph WEB["HTTP / UI 层"]
    direction LR
    HTTP["ftcsim-http accept + 线程池<br/>onMessage 同步执行<br/>stop/restart/rebuild 派新线程"]
    WS["ftcsim-ws-writer (每 client 一条)<br/>send() 永不阻塞·落后丢帧·8次判死"]
  end

  MAIN -->|"SimOpModeRunner.init"| OPTR
  OPTR ==>|"SDK 线程承载"| TEAM
  PHYS -- "step() 每 1ms 写入" --> HUB
  TEAM -- "硬件调用·吃时延+过 gate" --> HUB
  EV -. "gate() / publishTelemetry" .-> HUB
  HUB -. "◀ 读快照" .-> BC
  VIS -. "simFrame 读取真实 pose" .-> HUB
  HTTP -- "UI → init / start / stop" --> OPTR
  HTTP -. "gamepad → SimGamepad" .-> EV
  BC -->|"state / log 推送"| WS
  MAIN ~~~ BUILD

  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef sdk fill:#101a2b,stroke:#3b82f6,color:#bfdbfe
  classDef team fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  class MAIN,PHYS,EV,BC,VIS,BUILD,HUB sim
  class OPTR sdk
  class TEAM team
  class HTTP,WS web
```

源码证据见 `notes/research/sim-physics-research-notes.md`。
