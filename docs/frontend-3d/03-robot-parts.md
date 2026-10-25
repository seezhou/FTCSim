---
summary: 零件如何按 mount 摆放与动画（三层 Group、两遍挂载）
refs:
  - path: sim/src/main/resources/web/3d/parts.js
    anchor: "export function buildPartMesh(mount)"
    lines: 55-117
    hash: a43c7c7ed839
    since: f353923
  - path: sim/src/main/java/ftcsim/vision/DeviceMount.java
    anchor: "public Map<String, Object> toView()"
    lines: 54-63
    hash: 8c2b8267092c
    since: f353923
---

# 零件如何按 mount 摆上机器人（Phase 2 · 第 3 步：`robot.js` / `parts.js`）

一句话：**团队在机器人配置里给电机/伺服声明一个 `mount`（装在哪个位置、绕哪根轴转、行程多少、挂在谁下面），后端把它原样下发，前端据此生成零件并驱动它运动。没有声明 mount 的设备，前端干脆不画。**

---

## 图 1：端到端数据流——从配置里的一行 mount 到屏幕上的一个零件

```mermaid
flowchart TB
  CFG["机器人配置 JSON<br/>device.params.mount<br/>{ x, y, z, axis, minDeg, maxDeg,<br/>shape, lengthIn, diameterIn, parent }"]

  CFG --> DM["后端 DeviceMount.fromSpec()<br/>ftcsim/vision/DeviceMount.java:77-92<br/>（读不到就返回 null）"]
  DM --> VIEW["DeviceMount.toView()<br/>序列化成 JSON :54-63"]
  VIEW --> MSG["随 20Hz state 消息的<br/>devices[].mount 下发"]
  MSG --> PARSE["前端 parseMounts(devices)<br/>parts.js:19-39<br/>没有 mount 的设备直接跳过"]
  PARSE --> SIG["mountSignature() 比对签名<br/>parts.js:42-45<br/>（没变就不重建）"]
  SIG --> BUILD["buildParts()<br/>robot.js:76-99"]
  BUILD --> MESH["buildPartMesh(mount)<br/>parts.js:55-117"]
  MESH --> SCENE[("挂到 robot.group<br/>出现在 3D 场景里")]

  classDef data fill:#101a2b,stroke:#3b82f6,color:#bfdbfe
  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  classDef core fill:#121820,stroke:#64748b,color:#cbd5e1
  class CFG,DM,VIEW,MSG data
  class PARSE,SIG,BUILD,MESH web
  class SCENE core
```

**一个很重要的设计决策**（`parts.js:4-6` 注释原话）：一个没声明 mount 的设备**不会被停在原点，而是根本不画**——*"absence is a statement, and drawing a part somewhere the team never declared would be a guess"*。这与后端 `DeviceMount.fromSpec` 返回 `null` 的语义是配套的（:47-52）。

---

## 图 2：坐标系——`parts.js` 和 `field.js` 用的是同一套映射

机器人配置写在**机器人帧**（x 前、y 左、z 上，英寸）；场景的局部帧是 **X 前、Y 上、Z 右**。转换就一个函数（`parts.js:14-16`）：

```mermaid
flowchart LR
  subgraph ROBOT["机器人帧（配置里写的）"]
    R["x → 前<br/>y → 左<br/>z → 上"]
  end
  subgraph LOCAL["场景局部帧（Three.js）"]
    L["X → 前<br/>Y → 上<br/>Z → 右"]
  end
  R -->|"toLocal(m) → Vector3(m.x, m.z, -m.y)<br/>parts.js:14"| L

  CMP["对照 field.js:26 的 ftc(x,y,z) → Vector3(x, z, -y)<br/>同一套规则：x 不变、z 提到中间当高度、y 取负放最后"]

  classDef data fill:#101a2b,stroke:#3b82f6,color:#bfdbfe
  classDef warn fill:#2a1a06,stroke:#f59e0b,color:#fde68a
  class R,L data
  class CMP warn
```

**这套映射在前后端是统一的**：场地用全场坐标、零件用机器人局部坐标，但"x 不变 / z 变高度 / y 取负"这个规则两处一致。所以你在脑子里只需要记**一个**换算。

---

## 图 3：一个零件的三层 Group 结构（为什么要三层）

每个零件不是单个 Mesh，而是 `Group(part) → Group(joint) → Group(body) → Mesh`：

```mermaid
flowchart TB
  P["g = Group('part-NAME')<br/>position = toLocal(mount)<br/>（零件装在哪）"]
  P --> J["joint = Group('joint-NAME')<br/>★ 转动只作用在这一层"]
  J --> B["body = Group<br/>沿轴偏移 lengthIn/2<br/>（让零件挂在安装点外侧，而不是穿过它）"]
  B --> M1["shape=cylinder（默认，电机）<br/>CylinderGeometry 鼓身<br/>+ 两端 CircleGeometry 端盖<br/>+ 一条 stripe 细条（让旋转可见）"]
  B --> M2["shape=box（intake 手指）<br/>BoxGeometry 扁平刀片"]

  AX["mount.axis 决定转哪根轴<br/>axis='x'（roll，轮子）<br/>　→ joint.rotation.order='XYZ'，body 沿 local X 偏移<br/>axis='z'（pitch，臂）<br/>　→ joint.rotation.order='ZYX'，body 沿 local Z 偏移"]

  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef web fill:#240e18,stroke:#ec4899,color:#fbcfe8
  classDef warn fill:#2a1a06,stroke:#f59e0b,color:#fde68a
  class P,J,B sim
  class M1,M2 web
  class AX warn
```

**为什么要把"转动"单独抽一层 joint**：因为 body 需要沿轴偏移半个长度（这样零件是"挂"在安装点外面摆动，像真的机械臂），如果把偏移和旋转混在一层，旋转会把零件甩到别处。把 joint 独立出来后，`updateParts` 只需要设 `joint.rotation.z` 一个值。

---

## 图 4：父子嵌套——子零件跟着父零件动

`mount.parent` 让零件可以嵌套（比如 intake 手指挂在摆臂上）。前端用**两遍**处理（`robot.js:83-98`）：

```mermaid
flowchart TB
  S1["第一遍：把全部零件 add 到 robot.group"]
  S1 --> S2["第二遍：对声明了 parent 的零件重新挂载<br/>child 挂到 parent 的 Group 下"]
  S2 --> S3["用 toLocalIn(mount, parentMount) 重算偏移<br/>robot.js:452-454<br/>（在机器人帧里做差，再映射成场景坐标）"]
  S3 --> R["结果：父零件转动时，子零件靠 Three.js<br/>父子变换继承自动跟着转"]

  WHY["为什么要两遍？<br/>如果一边 add 一边 re-parent，某个 child 的 parent<br/>可能还没加进来，它就会被留在底盘上不动"]

  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef warn fill:#2a1a06,stroke:#f59e0b,color:#fde68a
  class S1,S2,S3,R sim
  class WHY warn
```

这和第 11 篇讲的 HIVE 倾覆是**同一个技巧**：把要一起动的东西挂在同一个 Group 下，靠变换继承联动，而不是每帧手动更新每个子物体的世界坐标。

---

## 图 5：伺服和电机的动画策略不同（这里有个很容易踩的坑）

```mermaid
flowchart TB
  V["每帧拿到该设备的实时值 deviceValues"]

  V --> Q{"mount.sweeps?<br/>（maxDeg ≠ minDeg）"}

  Q -->|"是：伺服"| SV["joint.rotation.z = angleFor(mount, pos)<br/>pos 取 v.position（后端实际位置）优先<br/>没有才用 v.commanded<br/>→ 直接跳到角度，不做缓动"]
  Q -->|"否：电机"| MO["_spin += rpm × (π/30) × dt<br/>joint.rotation.z = _spin<br/>→ 按物理转速积分"]

  SV --> SVN["为什么不缓动？<br/>伺服没有速度可积分，前端缓动会误报机构实际位置。真要缓动，后端 ServoState 已按 secondsFullRange 做过（第 3 步）"]
  MO --> MON["为什么用 rpm 而不是 duty？<br/>rpm 取自 MotorState.physicalOmega()，已带 physicalSign；<br/>CCW 电机（goBILDA / REV HD Hex）的符号在后端就处理好了，<br/>用 duty 会让飞轮看起来倒转"]

  MO --> COL["着色按 duty 而非 rpm<br/>堵转的飞轮转得慢，但代码仍在全力驱动，<br/>颜色该表达'指令'而不是'结果'"]

  classDef sim fill:#211d08,stroke:#eab308,color:#fde68a
  classDef warn fill:#2a1a06,stroke:#f59e0b,color:#fde68a
  class V,Q sim
  class SV,MO warn
  class SVN,MON,COL sim
```

**这条和第 2、3 步的物理是直接咬合的**：后端 `MotorState.physicalSign`（第 2 步）和 `ServoState.secondsFullRange` 缓动（第 3 步）在这里决定了前端该读哪个数、怎么动。

---

## 顺带：机器人自身的位姿是怎么平滑的

`robot.update(dt)`（`robot.js:304-331`）做了两件事，正好呼应第 09 篇的双时钟：

1. **用速度外推**（:306-310）：后端 20 Hz 的位姿是 50 ms 前的，前端按 `vx/vy/ω` 外推最多 0.12 s 来补偿这段延迟；
2. **指数平滑**（:312-315）：`k = 1 - exp(-14·dt)`，把当前显示位姿朝目标插值。

另外底盘刻意做成**半透明**（`CHASSIS_OPACITY = 0.22`，且 `depthWrite: false`），这样装在内部的机构（飞轮、intake 伺服）能从外面看见；同时用**不透明的边线**勾勒轮廓（:140-145）——注释里还记了一笔踩坑：橙色边太扎眼、蓝色边和里程轨迹颜色太接近导致看不清。

## 源码证据

- 后端 mount 模型：`ftcsim/vision/DeviceMount.java:22-33`（字段）、`:44`（`sweeps`）、`:54-63`（`toView` 下发）、`:77-92`（`fromSpec` 从配置读）
- 前端解析与构建：`parts.js:14-16`（`toLocal`）、`:19-39`（`parseMounts`）、`:42-45`（`mountSignature`）、`:55-117`（`buildPartMesh`）、`:120-124`（`angleFor`）
- 机器人装配：`robot.js:56-63`（`setDevices`）、`:66-69`（`setDeviceValues`）、`:76-99`（`buildParts` 两遍挂载）、`:101-187`（`buildBody` 底盘与轮子）、`:193-235`（相机 pod）、`:246-258`（`setCamMount` 接管团队相机位姿）、`:260-286`（`setSpec`）、`:289-301`（`setState`）、`:304-331`（`update` 外推+平滑）、`:341-368`（`updateParts`）、`:391-415`（`setAmmo` 弹药球）、`:452-454`（`toLocalIn`）
- 对照：`main.js:490-491`（注释：没有 mount 的零件不画）、`field.js:26`（同一套轴映射）
