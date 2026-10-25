---
summary: FTC / Pedro / Three.js 三套坐标系换算（Pedro 仅存在于 UI 层）
refs:
  - path: sim/src/main/resources/web/3d/panels.js
    anchor: "const toPedro = (x, y, h)"
    lines: 21-22
    hash: 147c2d0d96ce
    since: f353923
  - path: sim/src/main/resources/web/3d/field.js
    anchor: "export function ftc(x, y, z = 0)"
    lines: 28-28
    hash: 428752ac3b3a
    since: f353923
---

# 三套坐标系总图（Phase 2 · 第 5 步 · 收官）

项目里同时存在**三套坐标系**。这篇把它们放在一起，给出完整换算、数值演算，以及**换算分别发生在哪一步**。

先给一个关键结论，帮你少走弯路：

> **Pedro 系只存在于 UI 的读数/输入框里。** 后端权威数据、3D 场景里机器人的实际位置，**始终都是 FTC 系**。切换界面上的 Frame 选择器，只改变那三个输入框显示的数值，机器人该在哪还在哪。

---

## 图 1：三套坐标系各是什么

```mermaid
flowchart TB
  subgraph FTC["① FTC 场地系 —— 权威（后端 + 3D 渲染都用它）"]
    F["原点：场地中心<br/>+X → 观众侧（audience）<br/>+Y → 从红墙 (-Y) 到蓝墙 (+Y)<br/>+Z → 上<br/>heading：从 +X 起逆时针（CCW），度<br/>单位：英寸（下发给前端的值）"]
  end

  subgraph PED["② Pedro 系 —— 只用于界面读数与输入"]
    P["原点：场地一角（0..144 范围）<br/>pedro_x = FTC_y + 72<br/>pedro_y = 72 − FTC_x<br/>pedro_h = FTC_h − 90<br/>服务 Road Runner / Pedro Pathing 生态"]
  end

  subgraph T3["③ Three.js 3D 系 —— 渲染用"]
    T["three_x = FTC.x<br/>three_y = FTC.z（高度提到中间）<br/>three_z = −FTC.y（y 取负放最后）<br/>1 unit = 1 inch<br/>yaw：group.rotation.y = heading（弧度）"]
  end

  FTC -->|"toPedro / fromPedro<br/>panels.js:21-22"| PED
  FTC -->|"ftc(x,y,z) / toLocal(m)<br/>field.js:26 · parts.js:14"| T3

  classDef data fill:#101a2b,stroke:#3b82f6,color:#bfdbfe
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  class F data
  class P web
  class T sim
```

注意 **FTC → 3D** 与 **机器人局部帧 → 3D** 用的是同一条规则（x 不变、z 提到中间当高度、y 取负），只是前者是全场坐标、后者是机器人局部坐标。

---

## 图 2：全部换算公式（含反向）

```mermaid
flowchart LR
  subgraph A["FTC ↔ Pedro（UI 层）"]
    A1["toPedro(x, y, h)<br/>→ { x: y + 72, y: 72 − x, h: norm(h − 90) }"]
    A2["fromPedro(px, py, ph)<br/>→ { x: 72 − py, y: px − 72, h: norm(ph + 90) }"]
    A1 <--> A2
  end

  subgraph B["FTC ↔ Three.js（渲染层）"]
    B1["ftc(x, y, z)<br/>→ Vector3(x, z, −y)"]
    B2["反向：FTC.x = three.x<br/>FTC.y = −three.z<br/>FTC.z = three.y"]
    B1 <--> B2
  end

  N["norm(d) 把角度归一到 (−180, 180]<br/>d = ((d + 180) % 360 + 360) % 360 − 180<br/>panels.js:20"]

  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef warn fill:#2a1a06,stroke:#f59e0b,color:#fde68a
  class A1,A2 web
  class B1,B2 sim
  class N warn
```

---

## 图 3：一个具体的数值演算（照着算一遍最不容易忘）

假设机器人在 **FTC 系**里位于 `x = 30, y = −20`，朝向 `heading = 0°`（朝 +X，即观众侧），离地高度 `z = 8`。

```mermaid
flowchart TB
  IN["FTC：x = 30, y = −20, z = 8, heading = 0°"]

  IN --> P["Pedro 读数<br/>pedro_x = y + 72 = −20 + 72 = 52<br/>pedro_y = 72 − x = 72 − 30 = 42<br/>pedro_h = norm(0 − 90) = −90°<br/>→ 显示：x 52.0　y 42.0　θ −90°"]

  IN --> T["Three.js 场景坐标<br/>three_x = 30<br/>three_y = 8（高度）<br/>three_z = −(−20) = 20<br/>→ group.position.set(30, 8, 20)；rotation.y = 0"]

  P --> BACK["反向验证 fromPedro(52, 42, −90)<br/>x = 72 − 42 = 30　✓<br/>y = 52 − 72 = −20　✓<br/>h = norm(−90 + 90) = 0　✓"]

  classDef data fill:#101a2b,stroke:#3b82f6,color:#bfdbfe
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef ok fill:#0c1f13,stroke:#22c55e,color:#a7f3d0
  class IN data
  class P web
  class T sim
  class BACK ok
```

**一句话记忆**：Pedro 是"把场地摊成 0..144 的第一象限"（所以 x、y 都要 ±72 平移再互换），3D 是"把 z 抽出来当高度、y 取负"（所以 `ftc = (x, z, −y)`）。

---

## 图 4：换算分别发生在数据流的哪一步

```mermaid
flowchart TB
  PHY["后端物理内部<br/>Chassis.pose 单位 = 米，heading = 弧度"]
  PHY --> CONV["stateMessage() 发送前换算<br/>Units.mToIn(...) · Math.toDegrees(...)<br/>Simulator.java:481-482<br/>★ 后端负责把物理单位翻成好画的值"]
  CONV --> MSG["state.robot<br/>x / y（英寸）· headingDeg（度）"]
  MSG --> R3D["3D 渲染：robot.update()<br/>group.position.set(x, 0, −y)<br/>robot.js:317<br/>★ 用 FTC 系"]
  MSG --> SHOW["界面读数：panels.js:374-376<br/>若 Frame = Pedro → toPedro() 再显示<br/>★ 只改显示"]
  SHOW --> INPUT["界面输入：panels.js:283<br/>若 Frame = Pedro → fromPedro() 转回 FTC<br/>再发 sim.setPose 给后端<br/>★ 后端只认 FTC"]

  classDef data fill:#101a2b,stroke:#3b82f6,color:#bfdbfe
  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  classDef warn fill:#2a1a06,stroke:#f59e0b,color:#fde68a
  class PHY,MSG data
  class CONV warn
  class R3D sim
  class SHOW,INPUT web
```

**关键**：后端下发前就把米→英寸、弧度→度做完了。所以前端拿到的 `state.robot` **直接就是 FTC 系的英寸和度**，不需要再换算单位——只需要做轴向映射（FTC→3D）或读数换算（FTC→Pedro）。

---

## 图 5：一个容易漏掉的地方——drawings 自带坐标系标记

场地上画出的那些线（Road Runner 规划路径、Pedro Pathing 图形、Panels 形状）是通过 `state.drawings` 下发的，**它们自己也带坐标系标记**：

```mermaid
flowchart LR
  D["state.drawings<br/>每项带 frame 字段：'ftc' 或 'pedro'"]
  D --> Q{"d.frame === 'pedro' ?"}
  Q -->|"是"| C1["先 fromPedro(x, y, 0) 转成 FTC<br/>2d/app.js:224"]
  Q -->|"否（ftc）"| C2["直接使用"]
  C1 --> DRAW["铺到场地地面（3D 里画成薄 ribbon）"]
  C2 --> DRAW

  classDef data fill:#101a2b,stroke:#3b82f6,color:#bfdbfe
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  classDef warn fill:#2a1a06,stroke:#f59e0b,color:#fde68a
  class D data
  class Q warn
  class C1,C2,DRAW web
```

因为团队可能用 **Pedro Pathing** 画图（那套坐标系是 Pedro），也可能用 **Road Runner / FTC Dashboard**（FTC 系）。所以前端必须看 `frame` 字段决定要不要转换，否则路径会画在错误的位置。

---

## 速查卡

| 你手上有什么 | 想要什么 | 怎么做 |
|---|---|---|
| FTC (x, y, h) | 界面显示 Pedro | `toPedro`：`(y+72, 72−x, norm(h−90))` |
| 界面输入的 Pedro | 发给后端的 FTC | `fromPedro`：`(72−py, px−72, norm(ph+90))` |
| FTC (x, y, z) | 摆到 3D 场景 | `ftc`：`Vector3(x, z, −y)` |
| 机器人局部 mount (x, y, z) | 摆到机器人上 | `toLocal`：`Vector3(m.x, m.z, −m.y)` |
| 3D 场景坐标 | 反推 FTC | `x = three.x`，`y = −three.z`，`z = three.y` |
| 后端物理值 | 前端收到的值 | 后端已转好：米→英寸、弧度→度 |

## 源码证据

- FTC 场地系定义：`Field.java:10-12`（原点/+X 观众侧/+Y 红→蓝/+Z 上/CCW heading，英寸）
- 后端单位换算：`Simulator.java:481-482`（`Units.mToIn`、`Math.toDegrees`）
- Pedro 换算：`web/3d/panels.js:20-22`（`norm` / `toPedro` / `fromPedro`）、`:283`（输入转回 FTC）、`:320-325` 与 `:374-376`（显示切换）；2D 同款 `web/2d/app.js:119-120`
- FTC → 3D：`web/3d/field.js:26`（`ftc`）、`web/3d/parts.js:14-16`（`toLocal`）
- 3D 摆位与 yaw：`web/3d/robot.js:317-318`（`group.position.set(x, 0, -y)`、`rotation.y = h`）
- drawings 的 frame：`web/2d/app.js:224`（按 `d.frame` 决定是否 fromPedro）
- 项目约定：根 `CLAUDE.md`（Frames 段：Pedro 换算与 tag 四元数约定）
