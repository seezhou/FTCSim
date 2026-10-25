---
summary: 已知问题与待办：文档与代码不一致之处、代码待修项，以及文档保鲜机制的设计
---

# 已知问题与待办

> 2026-10-24 文档整理时发现。**除"已修"一节外，下面这些问题都还没有动代码。**
> 处理原则：明确的错误顺手改掉并留校对注；不能确认或影响运行行为的，只记录不擅改。

---

## 待修（代码）

### 1. 场地半场尺寸 `72` 硬编码三处，而后端其实已经下发了

- **位置**：`web/3d/field.js:13`（`export const HALF = 72`）、`web/3d/drag.js:8`（`const LIMIT = 72`）、后端 `Field.java:15`（`HALF_SIZE_IN = 72.0`）
- **问题**：后端 `fieldMessage()` 已经把尺寸发给前端了（`Simulator.java:385`：`f.put("sizeIn", Field.HALF_SIZE_IN * 2)`），但前端**完全没用**——`rebuild()` 里 `buildShell(fieldMsg.name)` 只传了 `name`，`sizeIn` 从未被读取，场地和拖拽边界仍按硬编码的 72 画
- **影响**：现在无害（场地固定 144×144），但任何尺寸变化都会画错，且错得很隐蔽
- **建议**：前端从 `fieldMsg.sizeIn / 2` 取半场尺寸，保留 72 作为缺省兜底；`drag.js` 的 `LIMIT` 同样从这一处取
- **优先级**：低。但属于"数据只应有一份"的一致性问题，建议与 #3 一起收掉

### 2. `rumbleIndicator` 有一行无效代码

- **位置**：`web/3d/gamepad.js:174`
- **问题**：`pads[id].rumbleEl.classList.toggle('hidden', false)` 是在移除一个**从未被添加过**的 `hidden` class，什么也没做。徽标显示实际由 CSS 控制：`style.css:165` 默认 `display:none`，`:167` `.gamepad.rumbling .rumbleInd { display: inline }`；而 `gamepad.js:173` 的 `toggle('rumbling', on)` 才是生效的那一行
- **影响**：功能正常，但会误导后来读代码的人以为显示由 `hidden` 控制
- **建议**：直接删掉这一行（若本意是想用 `hidden` 控制，应写成 `toggle('hidden', !on)`，但那样与 CSS 机制重复，删掉更干净）
- **优先级**：极低

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

### 6. obstacle 高度没有下发，前端靠名字猜

- **位置**：后端 `Simulator.java:407-409`（`fieldMessage` 只发 name / 边界 / color，**没有 height**，虽然 `Field.Obstacle` 类里有该字段）；前端 `field.js:193-202`（按名字正则猜：`HIVE foot bar`→1.5、`FLOWER`→21.5、`goal`→40，猜不到默认 1.5）
- **影响**：新赛季出现名字不匹配的新障碍时，高度会画错成贴地薄片
- **建议**：后端补发 `height`（一行的事）
- **优先级**：低

---

## 待修（文档）

- **研究笔记结论 44**：
  - "输入源优先级：物理 > 键盘 > 虚拟手柄"的表述需修正——虚拟手柄**不是轮询源**，它直接写 `p.s`，
    只在没有轮询源（物理 / 键盘）覆盖它时才保留（见 `gamepad.js:231-245`）；
  - KEYMAP 的行号归属有误：WASD、方向键、Q/E **不在** `KEYMAP`（`:12-14`），而在 `applyKeyboard()`（`:44-51`）；
    同时 KEYMAP 里还有 `KeyR→start`、`Backspace→back`、`Tab→guide` 未被列出。
- **研究笔记结论 9 的验证部分**：`ball-launcher.md` 原写"3 球 tip"，实际为 4（已在 `docs/launcher/ball-launcher.md` 更正，
  但研究笔记若引用到该数字需同步）。

---

## 已修（整理时顺手改掉）

| 问题 | 位置 | 处理 |
|---|---|---|
| `HIVE_TIP_BALLS` 记 3，代码是 4 | `docs/launcher/01-ball-launcher.md`（原 `.codebuddy/notes/ball-launcher.md`） | 已改为 **4**，并加校对注说明依据（`Field.java:290`，注释说明手册未给数字、sim 取 4） |

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
