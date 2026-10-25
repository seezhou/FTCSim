---
summary: 已知问题与待办：文档与代码不一致之处、代码待修项，以及文档保鲜机制的设计
---

# 已知问题与待办

> 2026-10-24 文档整理时发现。**除"已修"一节外，下面这些问题都还没有动代码。**
> 处理原则：明确的错误顺手改掉并留校对注；不能确认或影响运行行为的，只记录不擅改。

---

## 待修（代码）

### 3. 前端硬编码了一整组赛季常量，与后端逐字重复

- **位置**：`web/3d/field.js:232-240`（`CELL_PENT`、`CELL_DEPTH`、`CELL_BOTTOM`、`CELL_ARM_DROP`、`HIVE_PIVOT_Z`、`HIVE_Y`、`HIVE_TILT`）；另 `web/3d/webcam.js:28`（`MIN_FACING = 0.25`）
- **问题**：前一组与后端 `Field.java:246/252-253` 数值完全重复；`MIN_FACING` 与后端 `AprilTagProcessorImpl.Camera.see()` 的标签朝向阈值重复
- **影响**：换赛季要改两处且容易不同步；更隐蔽的是——改后端判定阈值时若忘了前端，会出现"程序检出了但画面没框"或反之
- **建议**：几何数值只保留一份在后端，经 `fieldMessage` 下发给前端（与 `tags` / `obstacles` 同样的做法）
- **优先级**：中。建议并入 #5 的赛季剥离工作一起处理（详见 `seasons/01-season-abstraction.md`）

### 4. 相机 mount 用正则拆字符串，而设备 mount 是结构化对象

- **位置**：`web/3d/webcam.js:24`（`MOUNT_RE`）、`:189-200`（`parseMount`）
- **问题**：电机/伺服的 `DeviceMount` 下发的是结构化 JSON（`DeviceMount.toView()`），前端直接读字段；而相机 mount 是 `SimWebcamName` 报的一个格式化字符串，前端只能靠正则拆
- **影响**：后端格式一变（加字段、改分隔符）正则即失效
- **建议**：后端把相机 mount 也改成结构化对象下发
- **优先级**：低

### 5. 赛季代码泄漏进了通用类

- **位置**：`Projectile.java:190-249`（BIOBUZZ CELL 五边形碰撞）、`World.java:180-182`（`onGoalReached` 用 if 区分赛季）、`:232-245`（HIVE 倾覆）、`Field.java:246/252/290`（赛季常量）
- **问题**：通用物理/世界类里出现了赛季专属的逻辑与常量
- **影响**：换赛季的改动会散落在多个通用类里，而不是集中在一处
- **建议**：把赛季专属交互抽成插件接口。完整设计与优先级见 `seasons/01-season-abstraction.md`
- **优先级**：中。建议在明年赛季确定前做一次剥离（纯挪位置、不改功能、低风险）

---

## 待修（文档）

（暂无。原列的两项——结论 44 表述 / KEYMAP 行号、结论 9 数字同步——已于 2026-10-24 核查并修正，见下方「已修」。）

---

## 已修（整理时顺手改掉）

| 问题 | 位置 | 处理 |
|---|---|---|
| `HIVE_TIP_BALLS` 记 3，代码是 4 | `docs/launcher/01-ball-launcher.md`（原 `.codebuddy/notes/ball-launcher.md`） | 已改为 **4**，并加校对注说明依据（`Field.java:290`，注释说明手册未给数字、sim 取 4） |
| 结论 44：输入源优先级表述 + KEYMAP 行号归属错误 | `notes/research/sim-physics-research-notes.md` 结论 44（`:612-618`）+ 源码证据列表（`:624`） | 修正两点：①虚拟手柄**不是轮询源**，仅在物理/键盘都未激活时保留 `p.s`（依据 `gamepad.js:231-245`）；②WASD/方向键/Q·E 在 `applyKeyboard()`（`:44-51`）做轴向合成，不在 `KEYMAP`（`:12-14`），并补全 `KeyR→start`/`Backspace→back`/`Tab→guide` |
| 结论 9：tip-ball 数字同步核查 | `notes/research/sim-physics-research-notes.md` | 全文 `HIVE_TIP_BALLS` 零命中，研究笔记从未引用该数字，无需同步；3/4 球更正已在 `docs/launcher/01-ball-launcher.md` 完成 |
| #1 场地尺寸 72 硬编码三处 | `web/3d/field.js`（buildShell/rebuild + 新增 getHalfField）、`web/3d/drag.js`（LIMIT 改读 getHalfField）、`Simulator.java:408` | 前端改用后端已下发的 `sizeIn` 取半场，保留 72 兜底；`drag.js` 拖拽边界同样走动态半场。原"后端发了前端没用"已修复 |
| #2 rumbleIndicator 无效行 | `web/3d/gamepad.js:174` | 删除 `toggle('hidden', false)` 无效行；徽标显示实际由 CSS `.gamepad.rumbling` 控制（`gamepad.js:173` 的 `toggle('rumbling', on)` 才生效） |
| #6 obstacle 高度未下发 | `Simulator.java:408` + 前端 `web/3d/field.js:199` | 后端 `fieldMessage` 补发 `height`；前端 `obstacleHeight()` 本就优先读 `o.height`，如今生效，不再按名字猜高度 |

---

## 关于"文档保鲜"（设计中的下一步）

目前文档与代码的关联只靠每篇末尾的"源码证据"（`文件:行号`），**行号会漂移、无法自动发现过时**。
计划中的改进：

1. 每篇加 front-matter 记录结构化 `refs`：`{ path, anchor, lines, hash, since }`
   - `anchor`（如方法签名）用于重新定位，解决行号漂移；
   - `hash` 对锚点起的片段计算，避免整文件改动牵连无关文档；
   - `since`（写文档时的 commit）配合 `git diff` 直接看到"变成什么样了"。
2. 配一个校验脚本，输出"哪些文档的哪些引用已变化"，并按严重度分级（锚点找不到 > 片段内容变化 > 仅 commit 前进）。
3. 能写成断言的事实（hub 时延、默认常量）尽量落到 `sim/src/test/`，而不是只留在散文里——那是唯一不会脱节的保障。
