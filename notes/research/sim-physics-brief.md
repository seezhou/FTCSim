# FTCSim 研究简报 · Phase 1：物理模拟是怎么实现的

> **状态：研究已收官**（Phase 1 与 Phase 2 均已完成）。
> **新会话请从 `docs/index.md`（文档总索引）与根目录 `CLAUDE.md` 入手。**
> 本文件保留为研究过程的背景与约定记录——它解释了"当初为什么这样研究"，但不再是任务清单。

## 读者画像（决定讲解深度）

- 15+ 年经验软件工程师；长年 Linux 工程，熟悉：Web 服务端/客户端、文件交互、进程/线程、终端。
- **不熟悉的领域**：物理模拟（刚体积分、电机信号链、PID 控制）、3D 渲染。讲解这两个领域时需要从"同构类比的 web 工程师视角"切入。
- 目标验成果：真机上验证过——模拟器里写的 OpMode 代码不改一行就能驱动真机并运行良好。我们要解释**为什么能做到**。

## 项目核心事实（来自 CLAUDE.md，先给结论当路标）

- 项目原则：**真 SDK 类尽量直接用**，只 shadow 硬件/Android 绑定类（`ftc-sdk/shadowed-classes.txt`；shadow 必须同 FQN 放在 `sim/`）。→ 这是"模拟器代码=真机代码"的根本机制。
- 物理世界：`World.step` 以固定步长积分，`World.projectiles` 发射物；硬件状态集中在 `MotorState` / `ServoState` 等。
- 电机信号链：`MotorState.physicalSign = mountSign * orientationSign`，SDK 对 CCW 电机反转 power/encoder 符号（goBILDA、REV HD Hex）。
- Hub 模拟：hub PIDF 仿真（`PidfController`，16-bit 输出单位，`HUB_LOOP_HZ=20`，I/D 缩放，积分过零重置；默认 F=32767/maxTps、P=0.1F、I=0.01F）。
- Hub 延迟模拟：`HardwareBus` 按真实 hub 时序扣时延（bulk 读 2ms、写 2.5ms、读 2ms、I2C 2.5ms，源自 `RobotConfig.hardwareLatency`），并有暂停门（gate）与线程豁免（`markExemptThread`/`exempt`：broadcast、physics、vision、OpMode stop 线程）。
- 暂停语义：`World.frozen()` 与 `World.stepFor(s)` —— 世界和机器人代码一起冻。
- 遥测/执行线程：OpMode 错误经 `RobotLog.threadPoolErrorHook` 捕获；急停后 INIT 自动重启。
- 视觉：`VisionPortalImpl` 30Hz 驱动 `SimVisionProcessor`；`AprilTagProcessorImpl` 基于 `SimVision`（真实 pose + 场地 + 相机安装）计算检出，没有真实帧图像。
- 遥测与 UI：`Simulator` 通过 websocket 消息输出（前端在 `sim/src/main/resources/web`，原生 JS/CSS）。
- 我已完成的两帧记录：FTC 场地系 ↔ Pedro 系换算在 `SimHardware.snapTo` / app.js `toPedro`/`fromPedro`。

## 运作约定（新增会话仍建议遵守）

1. **研究阶段不动代码**：理解、分析、画图、记结论都不改实现；确需改动先问用户。（文档整理类的改动另论——见第 5 条。）
2. 用**简体中文**交流与写笔记。
3. 讲解方式：每一步先给"web 工程师同构类比"，再给源码证据（文件路径 + 关键行号或引用），不给耸人猜测。
4. 画图分工：
   - 聊天内快速图 → Mermaid（用户用桌面版，mermaid 可渲染）；
   - 成篇的图文档 → 保存到 **`docs/` 对应主题目录**（见 `docs/index.md`）。
     > 校对注：原文此处写的是 `diagrams/`，并提到参考 `diagrams/architecture.html`。
     > 二者均已不存在——`diagrams/` 已并入 `docs/`，而那个 `architecture.html` 在整理前就已被移除，勿再引用。
5. **结论持续追加**：每完成一步，把结论追加到 `notes/research/sim-physics-research-notes.md`，格式为
   【日期 | 步骤 | 结论 | 源码证据文件】。会话中断后从这里继续。
6. **文档与代码的关联**：每篇文档末尾保留"源码证据"清单，但**行号会漂移**，引用前回源码核对。
   待办中的改进是为每篇加结构化 `refs`（含内容哈希与当时 commit），详见 `docs/known-issues.md`。

## Phase 1 任务拆解（物理模拟这道题）—— 七步全部完成 ✅

逐步做，每步给内容小结 + 选择继续或调整：

1. **总览梳理**：线程模型与启动链路，回答"tick 是谁在推" → `docs/architecture/01-threads.md`
2. **电机信号链**：power → `MotorState` → 物理符号 → 速度模型 → 编码器读数；hub PIDF → `docs/physics/02-motor-signal-chain.md`
3. **传感器模型**：IMU、伺服 `secondsFullRange` 缓动、数字量/模拟量、I2C → `docs/physics/03-sensor-model.md`
4. **物理积分与世界**：`World.step` 步长、底盘运动学、发射物三陷阱、UI 滑块耦合 → `docs/physics/04-world-physics.md`
5. **真实感三件套**：hub 时序延迟、暂停门、线程豁免 → `docs/physics/05-hub-timing-gate.md`
6. **Vision**：`VisionPortalImpl` → `AprilTagProcessorImpl` 之路 → `docs/vision/01-vision.md`
7. **产图**：结构图（模块 + 线程拓扑）+ 时序图（一个 tick 的先后与阻塞）→ `docs/architecture/04-phase1-overview.md`

## Phase 2（3D 前端）—— 已完成 ✅

- **现状**：Phase 2 于 2026-10-24 补完。结论 28–47 已入研究笔记（`sim-physics-research-notes.md`）；
  各小步详解见 `docs/frontend-3d/`（`01-threejs-basics.md`、`02-field-3d-build.md`、`03-robot-parts.md`、`04-webcam-pip.md`）、
  `docs/architecture/`（`02-websocket-drive.md`、`03-coordinate-systems.md`）与 `docs/seasons/01-season-abstraction.md`，
  总览为 `docs/frontend-3d/05-phase2-overview.md`。
- **覆盖**：WebSocket 消息驱动（hello/field/state + 双时钟）→ 场地 `field.js` 重建流水线 → 机器人 `robot.js`/`parts.js`（mount → 零件、两遍挂载、伺服/电机动画）→ 画中画 `webcam.js`（双渲染器 + 检出框投影 + synced）→ 三套坐标系（权威永远是 FTC，Pedro 只活在 UI 输入框）→ 其余模块（activity 着色 / balls / drag / gamepad rumble）→ 赛季可复用分层。
- **核心心智模型**：**后端 20 Hz 推全量目标快照，前端 rAF 自己插值画出来——两条独立时钟**；前端数据驱动渲染，缺省即缺席。
- **遗留待办**：**已全部移入 `docs/known-issues.md` 集中维护**，此处不再重复列出（避免两处维护导致不同步）。

## 起点提示

- 阅读顺序建议：`sim/` 目录结构 → `Main`（或启动类）→ `World` → `HardwareBus`/`SimHardware` → shadow 清单。
- README.md 有运行方式（`./gradlew run -Prepo=<repo>`、`tools/wsclient.mjs` 等），分析时可用这些工具实际跑一次验证理解（仅读，不修改）。
- **现在更快的入口**：先读 `docs/index.md`，按主题挑文档；结论全貌在 `notes/research/sim-physics-research-notes.md`。

—— 研究已收官。结论在 `sim-physics-research-notes.md`（结论 1–47），讲解与图在 `docs/`（见 `docs/index.md`）。
