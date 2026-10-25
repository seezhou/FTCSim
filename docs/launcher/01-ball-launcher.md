---
summary: 射球/发射器/HIVE·CELL 的实现、配置 schema 与 9 条踩坑
refs:
  - path: sim/src/main/java/ftcsim/physics/Field.java
    anchor: "public static final int HIVE_TIP_BALLS"
    lines: 290-290
    hash: 8995bfd71234
    since: e9256b5
  - path: sim/src/main/java/ftcsim/hardware/LauncherController.java
    anchor: "public void poll()"
    lines: 123-154
    hash: b0118a157fcd
    since: e9256b5
---

# FTCSim — 射球 / 发射器 / HIVE·CELL（现状总结）

> 合并自 `notes/launcher-progress.md` 与 `plans/ball-launch-3d_*.md`，只保留当前最终设计、踩坑与验证。
> 状态：**已实现并验证，未提交 git**。硬约束：运行**未修改**的团队 OpMode，发射只能靠观察设备状态推断。

## 1. 功能范围

- **真物理球**：重力、空气阻尼、撞墙/落地反弹、落地滚动至静止，命中场地已有目标时计分。
- **触发**：默认按配置推断自动发射（监视推球伺服位置跳变或飞轮转速超阈值）；另提供 `sim.launch` 消息供调试手动发射。
- **弹量 / 装填**：机器人带固定数量球（`capacity`），射一发少一发；补满途径只有 **INIT / `robot.restart`**（`reload()`）和**开车碾过落地球**（`collect()`）。**已删除**自动补满的 `reloadZone`。
- **HIVE / CELL**：球打中 CELL **不计入分**，而是装进箱子；攒够阈值后 HIVE 像跷跷板一样 tip 到另一侧，并把箱内球倒出。
- **车内弹量可视化**：弹匣剩余球数直接渲染在机器人车体内部（居中球簇），替代原来的 "剩余/总量" 文本药丸。

## 2. 文件清单

新增：
- `sim/src/main/java/ftcsim/physics/Projectile.java` — 球实体（位置/速度/半径/状态）。
- `sim/src/main/java/ftcsim/hardware/LauncherSpec.java` — 解析配置 `params.launcher`。
- `sim/src/main/java/ftcsim/hardware/LauncherController.java` — 触发、弹量、齐射发射。
- `sim/src/main/resources/web/3d/balls.js` — 飞行球体渲染（插值、命中高亮）。
- 测试：`ProjectileTest.java`、`LauncherControllerTest.java`、`HiveTipTest.java`。

修改：
- `physics/World.java` — `projectiles` 容器、`spawnProjectile()`、`clearProjectiles()`、`collectBalls()`（碾球捡拾）；球挂在 `step()` 里，继承暂停/单步/时间缩放。
- `physics/Field.java` — DECODE 球门命中区 + BIOBUZZ CELL（五边形棱柱）捕获判定、HIVE tip 逻辑。
- `Simulator.java` — state 增 `balls` / `launcher` / `hive.tip`；`sim.launch` / `sim.reload` / `sim.clearBalls` / `sim.launcherPitch` / `sim.launcherPitch` 消息。
- `web/3d/robot.js` — `setAmmo()` / `ammoLayout()`：车内居中球簇渲染。
- `web/3d/main.js` / `index.html` / `style.css` — 发射器角度滑块、隐藏原 `ammoPill` 文本。

## 3. 物理模型（英寸/秒，场地系 z 向上）

- 重力 386 in/s²；有 `massG` 走二次阻力 `½·ρ·Cd·A·v²/m`（ρ=1.204, Cd=0.47），无质量走线性阻尼。
- 地面反弹 RESTITUTION=0.55，滚动减速 45 in/s²，`REST_SPEED`=2。
- 场地四壁 ±(72−r) 反弹；`Field.Obstacle`（脚杆/FLOWER）用最近面法向反弹。
- **进球判定排在碰撞之前**（goal 同时是障碍，先判定才不会被弹开漏判）。
- 初速 `v = |ω| × (wheelDiameterIn/2) × slip`，叠加底盘速度；方向 = 机器人前方偏 `yawDeg`、抬 `pitchDeg`。

## 4. 发射器（LauncherController）

### 配置 schema（写在设备 `params.launcher`，Gson 直接读）

```json
"launcher": {
  "exit": {"x": -4.5, "y": 0.0, "z": 8.0},
  "speedFrom": "launchLeft", "wheelDiameterIn": 1.4, "slip": 0.85,
  "pitchDeg": 60, "yawDeg": 0, "speedInPerSec": 0,
  "ballRadiusIn": 1.4, "ballMassG": 0,
  "capacity": 5,
  "trigger": {"servo": "leftServo", "position": 0.5, "deltaPosition": 0.15, "minRpm": 3500}
}
```

- `exit` 机器人系（x 前 / y 左 / z 上），缺省取设备 mount 位置。
- `trigger.servo` 为 null 时用飞轮 `minRpm` 上升沿。
- `ballMassG`=0 表示未知重量，走线性阻尼。
- 球尺寸/重量：手册 POLLEN 直径 2.8 in（半径 1.4）、NECTAR 3.6 in（半径 1.8）；**手册没有重量**。

### 齐射（salvo）

- 一次触发（`fire()`）把弹匣里**剩余全部**球作为一次齐射放出：第一颗立即发射，其余由 `poll()` 每 **`salvoIntervalSec` 秒**放出一颗。该值来自配置 `params.launcher.salvoIntervalSec`（默认 0.5，当前 `FtcRobotController.json` 设为 0.1），改配置即改间隔，需重启后端生效。
- `poll()` 在 20 Hz 循环里检查 `salvoLeft > 0 && 到了间隔` 就放下一颗，直到队列空；中途重载/重配会取消进行中的齐射。
- 球之间靠飞行速度自然拉开距离，不重叠。

### 弹量 / 装填

- `remaining` 来自 `LauncherSpec.capacity` 减发射数。
- `collect()`：开车碾过落地球时 `World.collectBalls()` 在 `stepProjectiles` 末尾遍历在场球——跳过在 CELL 里的（`capturedIn != null`）和还在空中的（`z > r+1`）；用 `underRobot(p)` 判断球是否落在车身 footprint（机器人局部系 ahead/left，含车长/宽一半 + 球半径），命中即移除并 `launcher.collect()`（`capacity` 封顶）。停下压在车下的球也会被捡。
- 补满只来自 `reload()`（INIT / `robot.restart`），**无** `reloadZone` 自动补满。

## 5. HIVE / CELL

- 球打中 CELL **装进箱里**（不计分）；装到阈值 **`HIVE_TIP_BALLS = 4` 球**后 HIVE tip 到另一侧。

> 校对注（2026-10-24）：原文写的是 3 球，与代码不符。`Field.java:290` 实际为
> `public static final int HIVE_TIP_BALLS = 4`，注释说明手册只写"enough POLLEN or NECTAR"未给数字、
> sim 取 4。已更正为 4。
- tip 时箱内球从口部倒出（口朝下外 30°，给 18 in/s 初速继续自由飞行，落地为 litter）；比赛重置（INIT / `robot.restart` / `sim.clearBalls`）把两个 HIVE 复位到开局态并清空 tip 计数。
- tip **+20 分**（table 10-2），通过 `hive.tip {alliance, tips, points}` 广播；球的 `ball.score` 只保留给 DECODE goal。
- tip 必须回传场地：`World.tip()` 置 `fieldChanged`，广播循环调 `broadcastField()`，前端收到后 `rebuild` + `aimHives` 触发 60° 摆动动画。

## 6. 前端

- `robot.js` `setAmmo(remaining, capacity, radiusIn)`：在 `ammoGroup`（挂机器人 group，随车移动）按 `ammoLayout()` 在车身**正中心**摆出半径 `r` 的球簇（间距 `2r+0.2`，中线高度），渲染 `remaining` 个、隐藏其余；`buildBody()` 重建时跳过 `ammoGroup` 不丢球。
- 原 `ammoPill`（"剩余/总量" 文本）在 `updateLauncher` 里隐藏，改由车内球表示。
- `index.html` 的发射器行只保留 **Launch pitch 滑块**（`launchPitch`），绑定 `sim.launcherPitch`。
- `balls.js` 按 `state.balls` 渲染飞行中的球（插值平滑、命中高亮）。
- 注意：`panels.js` 的 `reloadBtn` 发的是 `code.reload`（**重载团队代码**），**不是**装填弹匣；3D 前端没有独立的 Launch/Reload 发射按钮，手动发射走 `sim.launch` 调试消息。

## 7. 消息协议

- state：`balls:[{id,x,y,z,r,state,goal?}]`、`launcher:{enabled,remaining,capacity,pitchDeg,ballRadiusIn,scored,captured}`、`hive.tip`（在 `sim.field` 整体场地消息里回传）。
- 后端→前端事件：`ball.score {goal,total}`、`hive.tip {alliance,tips,points}`、捡球通过 `balls` 状态消失体现。
- 前端→后端：`sim.launch` / `sim.reload` / `sim.clearBalls` / `sim.launcherPitch{deg}`。
- INIT 与 `robot.restart` 清场并补满弹量。

## 8. 踩过的坑（精炼，别重犯）

1. **伺服是渐变的**（`secondsFullRange`）：相邻轮询位移检测会漏掉慢伺服 → 必须用**电平跨越 + 迟滞**（`position` 触发，回落 `position−deltaPosition` 复位）。
2. **`minRpm` 是"推球后等待的转速"**：只在跨电平瞬间检查会让低转速那一发被永久吞掉；必须实现"待发射"等转速（团队代码按键后 0.5 s 推球，飞轮满速要 ~2 s）。
3. **捕获方向/法线**：箱子内部在 `-facing` 侧，判定时先确认"法线朝外、内部在反向"；单测要按正确入射方向写。
4. **CELL 不是碰撞体**（原 `Field.Obstacle` 只有脚杆/FLOWER）：`Projectile.hitCells()` 用五边形棱柱做碰撞，五面封闭、**口部敞开**；球心在箱内时从最近面向外推出，唯独"从口部往里飞"不算碰撞。AABB 只用于 DECODE 球门。
5. **AprilTag 朝向**：tag 挂进 CELL 组后用**局部**绕 X 轴 +90°（与 sticker 同 pose），勿再叠世界基；局部坐标取标量分量 `d.dot(across)`，别把世界向量当局部轴。
6. **坐标系符号**：`Field` 的 depth 轴 = −facing（向箱内），前端 CELL 局部 z = +facing（向口部），两边符号相反。
7. **前端静态资源缓存**：`WebServer` 回 `Cache-Control: no-cache`，浏览器不缓存；但默认从 **classpath** 读文件（编译产物），改源码后需**重启后端**才生效。想前端改动即时刷新，启动加 `--web <源码 web 目录>`（如 `gradlew run --args="--web D:/FTCSim/sim/src/main/resources/web"`）走实时目录。前端 import 里已无 `?v=` 缓存串。
8. **配置写在 `Device.params` 内**：写在 device 层会被 Gson 静默丢弃（README 记载过的坑）。
9. 箱内堆放 z 必须 ≤ `CELL_DEPTH/2 − r`，否则球穿背板；blue HIVE 开局就是 "scoring"（朝上 CELL 朝后墙），别断言它是 "audience"。

## 9. 验证

- 单元测试全绿（约 50 项，`./gradlew test`）：含弹道、反弹、进门、捕获不得分、4 球 tip 并倒球、朝下 CELL 不捕获、DECODE 照旧、清场复位、碾球捡拾（`drivingOverABallPicksItUp` / `aBallAwayFromTheRobotIsNotPickedUp`）。
- 端到端（真实 `BioBuzzTele`，按住 gamepad1.a）：自动推断触发射出，弹量递减；滑块改仰角实时生效。
- 本机无 node，`tools/*.mjs`（Playwright）跑不了；用 PowerShell WebSocket 脚本直连验证。
