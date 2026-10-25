---
summary: 文档总索引：按主题分类的入口、建议阅读路线与维护约定
---

# FTCSim 文档索引

> 这个目录是项目的 Wiki。它写的是**代码里读不出来的东西**：为什么这样设计、权衡是什么、踩过哪些坑。
> 代码本身能表达的事实（常量、签名、字段含义）尽量不写在这里——它们在 `sim/src/test/` 的断言里更可靠，
> 因为测试挂了必须改，而文档不会。

## 从哪开始

| 你想… | 读这些 |
|---|---|
| 第一次接触项目 | `CLAUDE.md`（约定）→ `architecture/01-threads.md`（全局图景） |
| 理解物理仿真 | `physics/` 四篇，建议顺序：01 电机 → 02 传感器 → 03 世界积分 → 04 时序真实感 |
| 理解 3D 前端 | `frontend-3d/` 五篇，建议顺序：01 Three.js 入门 → 02 场地 → 03 零件 → 04 画中画 → 05 总览 |
| 理解射球 / 发射器 | `launcher/01-ball-launcher.md` |
| 了解换赛季要改多少 | `seasons/01-season-abstraction.md` |
| 找当前已知问题 | `known-issues.md` |

---

## architecture 架构

| 文档 | 讲什么 |
|---|---|
| `01-threads.md` | 进程内线程拓扑；**谁在推 tick**（1 kHz 的 `ftcsim-physics` 是唯一推 tick 的线程） |
| `02-websocket-drive.md` | hello / field / state 三类消息如何驱动前端；20 Hz 推送与 60 fps 渲染的双时钟 |
| `03-coordinate-systems.md` | FTC / Pedro / Three.js 三套坐标系换算（含数值演算） |
| `04-phase1-overview.md` | Phase 1 总览：模块 + 线程结构图、一个 tick 的时序与阻塞关系 |

## physics 物理与仿真

| 文档 | 讲什么 |
|---|---|
| `01-motor-signal-chain.md` | setPower → 物理积分 → 编码器读数；hub PIDF 仿真在哪一层 |
| `02-sensor-model.md` | IMU、伺服 `secondsFullRange` 缓动、数字量/模拟量、I2C 兜底 |
| `03-world-physics.md` | `World.step` 七步顺序、底盘运动学、发射物与三个陷阱 |
| `04-hub-timing-gate.md` | hub 时延 / 暂停门 / 线程豁免（"真实感三件套"） |

## vision 视觉

| 文档 | 讲什么 |
|---|---|
| `01-vision.md` | **没有摄像头**：AprilTag 是几何检出而非图像处理；视锥六重门槛与噪声模型 |

## frontend-3d 3D 前端

| 文档 | 讲什么 |
|---|---|
| `01-threejs-basics.md` | Three.js 零基础入门（用 DOM / CSS / rAF 类比） |
| `02-field-3d-build.md` | 场地描述如何变成 3D 几何（`rebuild()` 七步流水线） |
| `03-robot-parts.md` | 零件如何按 mount 摆放与动画（三层 Group、两遍挂载） |
| `04-webcam-pip.md` | 画中画 = 第二个渲染器 + AprilTag 检出框投影 |
| `05-phase2-overview.md` | Phase 2 总览：前端模块拓扑 + 逐帧时序 |

## launcher 发射

| 文档 | 讲什么 |
|---|---|
| `01-ball-launcher.md` | 射球 / 发射器 / HIVE·CELL 的实现、配置 schema、验证，含**9 条踩坑** |

## seasons 赛季

| 文档 | 讲什么 |
|---|---|
| `01-season-abstraction.md` | 通用引擎 vs 赛季专属的分层；当前泄漏点与剥离建议 |

---

## 研究过程记录

| 文件 | 内容 |
|---|---|
| `notes/research/sim-physics-research-notes.md` | 结论 1–47 的流水账（Phase 1 = 1–27，Phase 2 = 28–47） |
| `notes/research/sim-physics-brief.md` | 研究交接文档（研究已收官，保留为背景与约定记录） |

**分工**：研究笔记记"推导过程与结论"，本目录记"主题讲解"。笔记保留原样（它的价值是当时的推导顺序），
本目录的文档按需重写而不必与其逐条对齐。

---

## 维护约定

1. **每篇末尾都有"源码证据"**，形如 `文件:行号`。**行号会漂移**——引用前请回源码核对，搜索时优先用符号名。
2. **序号只在各自目录内从 01 连续**，它是该主题下的阅读顺序，不是全局写作顺序；跨目录时以目录名为准。
3. **发现文档与代码不符**：明确的错误直接改（并在附近留一句校对注说明依据）；一时无法确认的记进 `known-issues.md`。
4. **待办**：为每篇加结构化 `refs`（文件 + 锚点 + 内容哈希 + 当时 commit），配一个校验脚本，
   用来回答"哪些文档引用的源码已经变了"。（见 `known-issues.md` 的设计说明）
