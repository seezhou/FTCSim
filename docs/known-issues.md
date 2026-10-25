---
summary: 已知问题与待办：文档与代码不一致之处、代码待修项，以及文档保鲜机制的现状
---

# 已知问题与待办

> 2026-10-24 文档整理时发现。**除"已修"一节外，下面这些问题都还没有动代码。**
> 处理原则：明确的错误顺手改掉并留校对注；不能确认或影响运行行为的，只记录不擅改。

---

## 待修（代码）

（暂无。）

---

## 待修（文档）

（暂无。原列的两项——结论 44 表述 / KEYMAP 行号、结论 9 数字同步——已于 2026-10-24 核查并修正，见下方「已修」。）

---

## 已知限制（非缺陷）

| 限制 | 位置 | 说明 |
|---|---|---|
| 2D 视图不渲染赛季专属结构 | `web/2d/app.js` | 2D 只画通用 `f.obstacles` / `f.tags` 和选项面板；BIOBUZZ 的 CELL/HIVE、机器人相机面板只在 3D 视图。README 已注明；若要在 2D 补画这些几何，属于新增前端特性。 |

---

## 已修（整理时顺手改掉）

| 问题 | 位置 | 处理 |
|---|---|---|
| `HIVE_TIP_BALLS` 记 3，代码是 4 | `docs/launcher/01-ball-launcher.md`（原 `.codebuddy/notes/ball-launcher.md`） | 已改为 **4**，并加校对注说明依据（`Field.java:290`，注释说明手册未给数字、sim 取 4；该常量现随第一批剥离位于 `BiobuzzRules.java:36`） |
| 结论 44：输入源优先级表述 + KEYMAP 行号归属错误 | `notes/research/sim-physics-research-notes.md` 结论 44（`:612-618`）+ 源码证据列表（`:624`） | 修正两点：①虚拟手柄**不是轮询源**，仅在物理/键盘都未激活时保留 `p.s`（依据 `gamepad.js:231-245`）；②WASD/方向键/Q·E 在 `applyKeyboard()`（`:44-51`）做轴向合成，不在 `KEYMAP`（`:12-14`），并补全 `KeyR→start`/`Backspace→back`/`Tab→guide` |
| 结论 9：tip-ball 数字同步核查 | `notes/research/sim-physics-research-notes.md` | 全文 `HIVE_TIP_BALLS` 零命中，研究笔记从未引用该数字，无需同步；3/4 球更正已在 `docs/launcher/01-ball-launcher.md` 完成 |
| #1 场地尺寸 72 硬编码三处 | `web/3d/field.js`（buildShell/rebuild + 新增 getHalfField）、`web/3d/drag.js`（LIMIT 改读 getHalfField）、`Simulator.java:408` | 前端改用后端已下发的 `sizeIn` 取半场，保留 72 兜底；`drag.js` 拖拽边界同样走动态半场。原"后端发了前端没用"已修复 |
| #2 rumbleIndicator 无效行 | `web/3d/gamepad.js:174` | 删除 `toggle('hidden', false)` 无效行；徽标显示实际由 CSS `.gamepad.rumbling` 控制（`gamepad.js:173` 的 `toggle('rumbling', on)` 才生效） |
| #3 前端硬编码赛季常量与后端重复 | `web/3d/field.js`、`web/3d/webcam.js` + 后端 `Field.java`(`cellGeometry()`)、`CameraMount.java`(`toView()`)、`SimWebcamName.java`、`AprilTagProcessorImpl.java`(`MIN_FACING`)、`Simulator.java`(`fieldMessage` 补发 `cellGeometry`/`aprilTagMinFacing`) | 后端 `Field.cellGeometry()` 下发 CELL/HIVE 几何（第一批剥离后改由 `BiobuzzRules.extraFieldData()` 提供），`CameraMount.toView()` 下发结构化 mount，`AprilTagProcessorImpl.MIN_FACING` 下发阈值；前端 `field.js` 删除手抄常量改读 `cellGeom`（由 `fieldMessage.cellGeometry` 注入，保留兜底值），`webcam.js` 删除 `MOUNT_RE` 正则、`parseMount` 改读结构化对象、`minFacing` 由 `setMinFacing` 接收 |
| #4 相机 mount 正则拆字符串 | `web/3d/webcam.js`(`MOUNT_RE`/`parseMount`) + 后端 `SimWebcamName.java`(`fillView`)、`CameraMount.java`(`toView()`) | 后端把相机 mount 改为结构化对象下发（`CameraMount.toView()`），前端 `parseMount` 读结构化字段、删除正则；`MIN_FACING` 阈值改由后端 `aprilTagMinFacing` 下发（`setMinFacing`），不再硬编码 |
| #5 赛季代码泄漏进通用类 | `Projectile`/`World`/`Field`/`Simulator` + 前端 `3d/main.js`、`3d/field.js`、`2d/app.js` | 两批均已剥离：几何/规则在 `BiobuzzRules`/`DecodeRules`，引擎经 `SeasonRules` 调用；注册表 `Field.FACTORIES` + `nameFromHint`；计分用 `rules.tipPoints()`/`scoreEventLabel()` 发通用 `field.event`；obelisk 可见性移入 `rules.isTagVisible()`；前端 `renderFieldOptions` + 通用事件 toast，删除 fallback 常量与 `sim.obelisk`。全部测试通过（当前 67/67，含 WebSocket 协议测试），浏览器 smoke 通过。详见 `seasons/01-season-abstraction.md` |
| #6 obstacle 高度未下发 | `Simulator.java:408` + 前端 `web/3d/field.js:199` | 后端 `fieldMessage` 补发 `height`；前端 `obstacleHeight()` 本就优先读 `o.height`，如今生效，不再按名字猜高度 |

---

## 关于"文档保鲜"（已落地）

文档末尾的"源码证据"仍是 `文件:行号`，行号会漂移；可自动校验的部分已改由 front matter 的
`refs` 承担：

```yaml
refs:
  - path: sim/src/main/java/ftcsim/physics/World.java
    anchor: "private void loop()"
    hash: 1c9b9200be2d
```

- `anchor`（方法签名等稳定片段）用于**重新定位**，代码只是挪位置不会误报；
- `hash` 只对锚点起的 40 行计算，文件其他位置改动不影响本引用；
- 刻意不存 `lines` / `since`：它们每次编辑都会变，只会制造噪声 diff（旧字段会在
  `--update` 时清除）。

`tools/docs-check.mjs` 提供三种用法：

| 命令 | 行为 | 退出码 |
|---|---|---|
| `node tools/docs-check.mjs` | 报告哪些引用变了，并给出 `git diff` 提示 | 始终 0 |
| `node tools/docs-check.mjs --check` | CI 门禁：有引用变化 / 锚点丢失 / 未写 anchor 即失败 | 失败时 1 |
| `node tools/docs-check.mjs --update` | 人工确认正文后刷新基准 hash | 0 |

CI（`.github/workflows/ci.yml`）跑 `--check`，所以引用漂移会直接让构建变红。当前覆盖 18 篇文档、
39 个引用，只锚定最核心的符号，不是全文每一处 `文件:行号`；散文里写死的事实（hub 时延、默认
常量）仍应尽量落到 `sim/src/test/`，那是不依赖人工维护的保障。
