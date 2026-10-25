---
summary: hub 时延 / 暂停门 / 线程豁免（真实感三件套）
refs:
  - path: sim/src/main/java/ftcsim/hardware/HardwareBus.java
    anchor: "public static void gate()"
    hash: d345eeacc9fc
  - path: sim/src/main/java/ftcsim/hardware/HardwareBus.java
    anchor: "private static void charge(double millis, AtomicLong counter, long count)"
    hash: 516c4765a87b
---

# FTCSim 真实感三件套（Phase 1 · 第 5 步：hub 时序延迟 / 暂停门 / 线程豁免）

配色：**黄 = 模拟器实现**（ HardwareBus / World 等 ftcsim.*）· **绿 = 团队代码**（唯一"付费"的一方）· **紫 = 模拟器自己的豁免线程** · **红 = 会阻塞的危险区域**。

本步一句话结论：**时延收费与暂停门由同一个 `ThreadLocal EXEMPT` 驱动**——只有团队代码线程付费、也只有团队代码线程会被门卡住；而且 `gate()` 是**嵌在 `charge()` 内部**的，所以「机器人代码冻在下一个 hub 调用」是单点实现的。

## 图 1：三件套总览——谁付费、谁豁免、谁被卡

```mermaid
flowchart TB
  subgraph TEAM["团队代码线程（OpMode）· 唯一付费方"]
    A["hub 调用：setPower / getPosition / I2C ..."]
    B["eventLoop 每次迭代<br/>（迭代式 OpMode 的 loop 之间）"]
    C["publishTelemetry<br/>（几乎每个 loop 都经过）"]
  end

  subgraph HB["HardwareBus · 同一个 ThreadLocal 管两件事"]
    direction TB
    CH["charge(ms) 扣时延<br/>team: gate() → 缩放 → 睡 OR 豁免: 直接返回"]
    G["gate() 暂停门<br/>while(frozen) parkNanos(1ms)<br/>被中断立即返回"]
    E["EXEMPT &lt;ThreadLocal Boolean&gt;<br/>exempt(Runnable) · markExemptThread()"]
  end

  subgraph EXE["模拟器自己的线程 · 终身豁免（不付费、不过门）"]
    PHYS["ftcsim-physics · 反射豁免"]
    BC["ftcsim-broadcast"]
    VIS["ftcsim-vision-*"]
    STOP["ftcsim-internalStop · 必须豁免"]
  end

  A --> CH
  B --> G
  C --> G
  CH -->|"内部第一步就调用"| G
  E -.->|"跳过收费"| CH
  E -.->|"跳过门"| G
  PHYS --> E
  BC --> E
  VIS --> E
  STOP --> E

  classDef team fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef exe fill:#191131,stroke:#a78bfa,color:#ddd6fe
  class A,B,C team
  class CH,G,E sim
  class PHYS,BC,VIS,STOP exe
```

> **为什么 stop 线程必须豁免**：暂停时 OpMode 线程正卡在 `gate()` 里；若 stop 线程也过门，就永远停不掉一个已暂停的 OpMode。

## 图 2：`charge()` —— 一次 hub 事务到底做了什么

```mermaid
flowchart TD
  CH["charge(millis, counter)"] --> EX{"调用线程被豁免?"}
  EX -->|"是（模拟器线程）"| R1["直接返回<br/>不扣费、不过门"]
  EX -->|"否（团队代码）"| G1["先 gate()：冻结就 park 住<br/>← 暂停门的单点实现"]
  G1 --> EN{"enabled 且 millis 为正?"}
  EN -->|"否"| R2["仅累计计数，不睡"]
  EN -->|"是"| SC["scale = World.timeScale()<br/>nanos = millis·1e6 / scale<br/>（UI 加速 → 等待等比变短）"]
  SC --> CNT["计数 + ioNanos 累计"]
  CNT --> SN["sleepNanos(nanos)"]
  SN --> W{"remaining − SPIN(1.5ms) ≥ 1ms?"}
  W -->|"是"| SL["Thread.sleep(整毫秒)"]
  W -->|"否"| SP["Thread.onSpinWait() 自旋"]
  SL --> CHK{"线程被中断?"}
  SP --> CHK
  CHK -->|"是"| RET["立即返回（stop 请求）"]
  CHK -->|"否"| W2{"已到 deadline?"}
  W2 -->|"否"| W
  W2 -->|"是"| R3["返回"]

  classDef yes fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  classDef no fill:#1c1114,stroke:#ef4444,color:#fecaca
  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  class CH,G1,SC,CNT,SN sim
  class R1 yes
  class RET no
```

**三个 blocking 入口**（共同实现「世界与 OpMode 冻在同一瞬间」）：
1. `charge()` 内部 —— 任何 hub 事务；
2. `SimOpModeRunner` 事件循环每次 pass；
3. `publishTelemetry`。
所以**既不碰硬件也不发 telemetry 的代码会继续跑**，直到触碰这三者之一才被冻住。

## 图 3：为什么不用 `parkNanos` —— Windows 15.6ms scheduler tick 陷阱

```mermaid
flowchart LR
  subgraph BAD["❌ LockSupport.parkNanos(2.5ms)"]
    B1["请求 2.5ms"] --> B2["Windows 向上取整到<br/>15.6ms 调度 tick"]
    B2 --> B3["实测耗时约 15ms<br/>每个 OpMode 比真机慢约 6 倍"]
  end
  subgraph GOOD["✅ sleepNanos 混合策略"]
    G1["请求 2.5ms"] --> G2["Thread.sleep 整毫秒部分"]
    G2 --> G3["剩余 1.5ms 用<br/>Thread.onSpinWait 自旋到 deadline"]
    G3 --> G4["实测耗时接近 2.5ms<br/>时序与真机可比"]
  end

  classDef no fill:#1c1114,stroke:#ef4444,color:#fecaca
  classDef yes fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  class B1,B2,B3 no
  class G1,G2,G3,G4 yes
```

同一手法在 physics loop 维持 1kHz 节拍时复用：差值 > 2ms 才 `Thread.sleep`，否则 `Thread.onSpinWait()`。

## 时延常量一览

| hub 事务 | 默认耗时 | 何时扣 |
|---|---|---|
| bulk 读 | 2.0 ms | `LynxModule.bulkRead` 里缓存**刷新**时（`HardwareBus.bulkRead()`） |
| 单次读 | 2.0 ms | bulk caching 为 OFF 时的单次读（`HardwareBus.read()`） |
| 写 | 2.5 ms | `setPower` / `setServoPosition` / `setMode` 等每次写（`HardwareBus.write()`） |
| I2C | 2.5 ms | I2C 设备事务（`HardwareBus.i2c()`） |

> **命中缓存时不扣费**：bulk caching 为 MANUAL/AUTO 且未触发刷新时，只调 `gate()`（过门）不扣时延——所以 bulk caching 在模拟器里同样能提速，与真机同构。

## 三个角色的差异总表

| 线程 | 付 hub 时延 | 过暂停门 | 理由 |
|---|---|---|---|
| 团队 OpMode 线程 | ✅ 付 | ✅ 过 | 它就是要被模拟成"在真机上跑"的那一方 |
| `ftcsim-physics` | ❌ 免 | ❌ 免（自己判 `frozen()` 自停） | 推 tick 的人不能被冻结，否则世界永不前进 |
| `ftcsim-broadcast` | ❌ 免 | ❌ 免 | UI 要能在暂停时继续读快照（否则暂停后 UI 卡死） |
| `ftcsim-vision-*` | ❌ 免 | ❌ 免 | 视觉线程，同理 |
| `ftcsim-internalStop` | ❌ 免 | ❌ 免 | 否则暂停时 OpMode 永远停不掉 |

> `World.markPhysicsThread()` 用**反射**调用 `HardwareBus.markExemptThread()`（`World.java:281-283`）——因为 `ftcsim.physics` 包不该反向依赖 `ftcsim.hardware` 包，用反射打破包循环依赖。

## 源码证据

- `sim/src/main/java/ftcsim/hardware/HardwareBus.java:23（时延常量）, 30（EXEMPT ThreadLocal）, 55-63（exempt/markExemptThread）, 65-83（write/read/bulkRead/i2c + charge）, 85-94（gate）, 96-121（sleepNanos 混合策略与注释）`
- `sim/src/main/java/ftcsim/physics/World.java:67（frozen）, 70-77（stepFor/stepRemaining）, 285-319（loop：frozen 自停 / catchUp&lt;50 / sleep-spin）, 280-283（反射豁免）`
- `sim/src/main/java/com/qualcomm/robotcore/eventloop/opmode/SimOpModeRunner.java:186（stop 豁免）, 301-305（eventLoop gate）, 345-348（publishTelemetry gate）`
- `sim/src/main/java/com/qualcomm/hardware/lynx/LynxModule.java:120-147（isExempt 直读 live + 三条扣费路径）`
- `sim/src/main/java/ftcsim/Simulator.java:130（broadcast 豁免）`
- `sim/src/main/java/org/firstinspires/ftc/vision/VisionPortalImpl.java:79（vision 豁免）`

配套研究笔记：`notes/research/sim-physics-research-notes.md` 结论 20–23。
