---
summary: 通用引擎 vs 赛季专属的分层；泄漏点与剥离建议
refs:
  - path: sim/src/main/java/ftcsim/physics/Field.java
    anchor: "public static Field byName(String name)"
    lines: 170-177
    hash: 0cba9b497f22
    since: 2581a52
  - path: sim/src/main/java/ftcsim/physics/Projectile.java
    anchor: "private void resolveCell(Field.Cluster cl)"
    lines: 203-249
    hash: d26091e51d6b
    since: 2581a52
---

# 赛季可复用性分层图（通用引擎 / 几何数据 / 赛季插件）

> 背景：每季场地会大变样（26 年是投球 + CELL + HIVE；24 年是抓取零件挂上去；明年未知）。
> 本文回答一个问题：**换赛季时，哪些代码能不动、哪些必须动，以及"必须动"的部分如何收敛到最小范围。**

核心结论：**变的从来不是"机器人怎么动"，而是"场上那个东西怎么被操作、怎么算得分"**。前者是通用能力（电机 + 伺服 + 限位，早已具备），后者才是每季全新的部分。

---

## 图 1：三层划分与正常数据流

```mermaid
flowchart TB
  subgraph C["C 赛季专属层（每季全新）"]
    C1["BIOBUZZ：球 / 发射 / CELL / HIVE 倾覆"]
    C2["DECODE：goal boxes / obelisk"]
    C3["明年：？（抓取 / 挂载 …）"]
  end

  subgraph B["B 几何数据层（填数据即可）"]
    B1["Field 通用基元<br/>Obstacle · AprilTag · Cluster<br/>Zone · Shape · Option · Goal"]
    B2["Field.byName(name) 注册表<br/>decode() / biobuzz() / empty()<br/>（现状：数值硬编码在 Java）"]
    B1 --- B2
  end

  subgraph A["A 引擎层（换赛季零改动）"]
    A1["物理：Chassis / MotorState / ServoState / Battery"]
    A2["时序：World.step / HardwareBus / 暂停门"]
    A3["通信：stateMessage / fieldMessage / WebSocket"]
    A4["视觉：AprilTag 几何检出（读 field.tags）"]
    A5["机器人能力：臂 / 伸缩 / 爪（电机+伺服+限位）"]
  end

  C -->|"用 B 的基元描述自己"| B
  B -->|"fieldMessage 下发"| A3
  A3 -->|"hello / field 消息"| FE["前端 field.rebuild<br/>按描述渲染，不认赛季"]
  A4 -->|"读 tags"| B1

  classDef season fill:#211d08,stroke:#eab308,color:#fde68a
  classDef data fill:#101a2b,stroke:#3b82f6,color:#bfdbfe
  classDef engine fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  class C1,C2,C3 season
  class B1,B2 data
  class A1,A2,A3,A4,A5 engine
  class FE web
```

**关键**：`Field.byName(name)` 这个注册表（`Field.java:170-177`）**已经就位**——加新赛季 = 加一个 `case` + 一个工厂方法。而且选哪个场地是配置驱动的：`Simulator.java:157` 会按机器人配置名自动匹配（`contains("biobuzz")` → `BIOBUZZ`）。

---

## 图 2：现状的「泄漏」——C 的代码混进了 A（本图是重点）

理想情况下 C 层代码不该出现在 A 层的类里，但目前有几处越界。红色是需要优先处理的部分。

```mermaid
flowchart LR
  subgraph SHOULD["本应只存在于 C 赛季层"]
    S1["CELL 五边形几何<br/>CELL_PENT · pentagonSd<br/>CELL_DEPTH_IN · cellSlotPosition"]
    S2["HIVE 倾覆逻辑<br/>tip() · HIVE_TIP_BALLS<br/>option redHive / blueHive"]
    S3["球与 CELL 的交互<br/>捕获 · 堆叠 · 随 CELL 移动"]
  end

  subgraph ACTUAL["实际泄漏到了（越界）"]
    P["Projectile（通用物理类）<br/>hitCells / resolveCell / rideInCell<br/>直接用 CELL_PENT　Projectile.java:190-249"]
    W["World（通用世界类）<br/>tip() :232-245 · capturedIn :221<br/>onGoalReached 用 if 分赛季 :180-182"]
    F["Field（通用场地类）<br/>CELL_PENT :252 · CELL_DEPTH_IN :246<br/>HIVE_TIP_BALLS :290"]
    FE2["前端 field.js<br/>硬编码同一份五边形数值<br/>（CELL_PENT 注释：3D view 也用这数）"]
  end

  S1 -.->|"漏进"| P
  S1 -.->|"漏进"| F
  S1 -.->|"抄了一份"| FE2
  S2 -.->|"漏进"| W
  S2 -.->|"漏进"| F
  S3 -.->|"漏进"| P

  classDef bad fill:#1c1114,stroke:#ef4444,color:#fecaca
  classDef ok fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  class S1,S2,S3 ok
  class P,W,F,FE2 bad
```

**最典型的两句话**（读代码时能直接看到赛季名出现在通用类里）：

- `World.onGoalReached` 的注释（`World.java:180`）：*"A DECODE goal scores; a BIOBUZZ CELL is a box, not a hit box"*——通用方法里用分支区分赛季。
- `Projectile.hitCells` 的注释（`Projectile.java:190-194`）：*"The BIOBUZZ CELLS: boxes, not hit boxes…"*——通用物理类直接实现某赛季元素的碰撞。

---

## 图 3：剥离后的目标形态（插件化）

```mermaid
flowchart TB
  subgraph PLUGIN["赛季插件层（每季一个实现，互不影响）"]
    PI["统一接口<br/>① 游戏元素（几何 + 位姿，可下发）<br/>② 交互判定（抓取 / 捕获 / 挂载 / 进洞）<br/>③ 计分与状态转移<br/>④ 赛季选项（自动生成 UI）"]
    P1["BIOBUZZ 实现<br/>球 / CELL / HIVE"]
    P2["DECODE 实现<br/>goal boxes"]
    P3["明年实现<br/>抓取 / 挂载 …"]
    PI --> P1
    PI --> P2
    PI --> P3
  end

  subgraph CORE["引擎（不再出现任何赛季名）"]
    W2["World / Projectile<br/>只调用接口"]
    A6["物理 · 时序 · 通信 · 视觉 · 设备模型"]
  end

  subgraph FRONT["前端（通用）"]
    FE3["field.rebuild 按下发数据渲染"]
    FE4["options 自动生成下拉 UI"]
  end

  PI -->|"注册到引擎"| W2
  PI -->|"元素几何下发"| FE3
  PI -->|"选项声明"| FE4

  classDef season fill:#211d08,stroke:#eab308,color:#fde68a
  classDef engine fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  class PI,P1,P2,P3 season
  class W2,A6 engine
  class FE3,FE4 web
```

这样，明年的改动就是**新增一个插件实现**，而不是去 `Projectile` 里删掉去年的五边形、去 `World` 里改倾覆逻辑。

---

## 图 4：明年换赛季时的改动范围

```mermaid
flowchart TB
  subgraph MUST["必须动（集中在新增文件）"]
    M1["新赛季插件实现<br/>元素几何 + 交互判定 + 计分规则"]
    M2["场地描述数据<br/>（用通用基元填：障碍/标签/区域/形状）"]
    M3["可选：专属 3D 模型或场地贴图"]
  end

  subgraph KEEP["完全不用动"]
    K1["物理引擎（chassis / 电机 / 伺服 / 电池）"]
    K2["hub 时序 / 暂停门 / 线程豁免"]
    K3["WebSocket 协议与消息构造"]
    K4["前端渲染管线与面板框架"]
    K5["设备模型 / DeviceMount / 机器人能力"]
    K6["AprilTag 几何检出（读 field.tags）"]
  end

  MUST -->|"通过既有接口接入"| KEEP

  classDef season fill:#211d08,stroke:#eab308,color:#fde68a
  classDef engine fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  class M1,M2,M3 season
  class K1,K2,K3,K4,K5,K6 engine
```

**特别提醒**：机器人的"伸缩 + 抓取"这类能力**根本不需要新物理**——
- 伸缩臂/升降 = 非驱动电机（`MotorState.stepLoad` + 惯性/摩擦/重力/限位），**第 2 步已具备**；
- 行程限位 = `minAngle / maxAngle`（`applyLimits`），**已有**；
- 爪子 = 伺服（`ServoState` 位置/CR 模式），**第 3 步已具备**。

所以 24 年那种"抓取零件挂上去"的需求，在**机器人侧是现成的**，要新写的只有"那个零件和 backdrop 的几何 + 交互判定"。

---

## 泄漏点清单与处理优先级

| 优先级 | 泄漏点 | 位置 | 建议 |
|---|---|---|---|
| **P0** | CELL 五边形碰撞写在通用物理类 | `Projectile.java:190-249`（`hitCells`/`resolveCell`/`rideInCell`） | 抽到赛季插件，`Projectile` 只保留通用质点物理 |
| **P0** | HIVE 倾覆写在通用世界类 | `World.java:180-182`（if 分赛季）、`:232-245`（`tip`） | 抽到赛季插件，阈值与效果参数化 |
| **P1** | 赛季常量写在通用 `Field` | `Field.java:246/252/290`（`CELL_*`、`CELL_PENT`、`HIVE_TIP_BALLS`） | 随插件搬走，`Field` 只留通用基元 |
| **P1** | 前后端各存一份几何数值 | `Field.CELL_PENT` 与前端 `field.js` | 只留一份在后端，经 `fieldMessage` 下发 |
| **P2** | 场地几何手工转录、硬编码在 Java | `biobuzz()` 注释：转录自社区文档 | 考虑改为资源文件（数据驱动），降低每季录入成本 |
| — | 场地贴图按赛季各一份 | `FieldImages`（DECODE/BIOBUZZ png） | 合理，保持现状即可 |

## 务实建议

**现在不必为明年做过度设计**——明年的场地和玩法都未知，提前抽象很容易抽象错。但有**一件低成本高回报的事**值得现在做：

> 把已泄漏到 `Projectile` / `World` 里的 BIOBUZZ 代码**先剥出去**（纯挪位置，不改功能，风险低）。

好处是：明年新赛季的改动会集中在一个新文件，而不是面对一个已被赛季代码污染过的 `Projectile`。这比"等明年一起大改"省事得多。

## 源码证据

- 场地注册表与切换：`Field.java:170-177`（`byName(name)`）、`:208`（`decode`）、`:230`（`empty`）、`:293`（`biobuzz`）；`Simulator.java:157`（按配置名自动选）；`World.java:20`（默认 `decode()`）
- 赛季常量泄漏：`Field.java:246`（`CELL_DEPTH_IN`/`CELL_BOTTOM_IN`）、`:252-253`（`CELL_PENT`）、`:260-286`（`pentagonSd`/`pentagonClosest`）、`:190-195`（`cellSlotPosition`）、`:290`（`HIVE_TIP_BALLS`）
- 物理层越界：`Projectile.java:190-249`（CELL 碰撞/捕获/随动）、`:252-258`（`rideInCell`）
- 世界层越界：`World.java:180-182`（`onGoalReached` 分赛季）、`:216-225`（`checkCellCapture`/`capturedIn`）、`:232-245`（`tip`）
- 通用能力已具备：`MotorState.java:183-198`（`stepLoad` 含惯性/摩擦/重力）、`:200-203`（`applyLimits` 行程限位）；`ServoState.java:40-57`（位置/CR 模式）
- 前端：`main.js:118/128`（`field.rebuild` 通用重建）、`:317-349`（`renderBioBuzz` 按 options 自动生成 UI）
