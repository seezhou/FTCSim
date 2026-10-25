# FTCSim 物理模拟研究笔记

> 配套交接文档：`notes/research/sim-physics-brief.md`（研究已收官）。每步追加一条：`【日期 | 步骤 | 结论 | 源码证据文件】`。会话中断后从这里继续。
> **讲解与图见 `docs/index.md`**：本文件记"推导过程与结论"，`docs/` 记"主题讲解"，两者分工见索引。
> 注意：文中早期写下的 `diagrams/…` 路径已全部迁入 `docs/`，现按主题分布（见各条结论的更新说明）。

---

【2026-10-23 | Phase 1 第 1 步：总览梳理 | 见下 | 见末尾清单】

## 结论 1：启动链路

`Main.main()`（`sim/src/main/java/ftcsim/Main.java:35-37`）
→ `new Simulator().start()` → 主线程 `join()` 挂起，此后 Main 线程不再干活（`Thread.currentThread().join()`）。

`Simulator.start()`（`sim/src/main/java/ftcsim/Simulator.java:77-134`）的顺序：
1. 装 Android 侧桩：`StdoutCapture.install()`、`android.util.Log` sink、`setStringResolver`（已由 Main 提前装，Main.java:11）。
2. `loadConfig()` → `RobotConfig`（:88）。
3. `new World()` → `HardwareBus.install(world)` + `configure(latency)`（:91-96）——中央物理世界 + hub 时序配置在这一步接线。
4. `new SimHardware(world, config, …)` → `LauncherController` → `new SimOpModeRunner(hardwareMap, beforeInit, afterStop)`（:97-100）。此时还没有任何 OpMode 线程。
5. **`world.start()`（:107）→ 创建 `ftcsim-physics` 线程（World.java:269，MAX_PRIORITY）。**
6. 起 `ftcsim-initial-build` 守护线程编译队伍代码 + `startWatching()` 文件监听（:117-119；监听线程在 TeamCode.java:128，`WatchService.take()` 阻塞式）。
7. `new WebServer(...).start()`（:124-126）→ 起 `ftcsim-http-accept` 线程（WebServer.java:162），每个连接交给 `Executors.newCachedThreadPool()`（线程名 `ftcsim-http`，WebServer.java:144）。
8. 起 `ftcsim-broadcast` 守护线程，先 `markExemptThread()` 再进 `broadcastLoop()`（:130-132）。

**web 工程师类比**：`Main` ≈ `server.listen()` 之后把自己 `await` 掉的 launcher；`Simulator.start()` 就是装配组合根（composition root），把 World（状态）、SimHardware（设备视图）、Runner（用户代码运行时）、WebServer（API 层）四个组件互相接线——像 Nest/Spring 的 bootstrap，之后所有实际工作都在专门线程里跑。

## 结论 2：进程内线程清单（全部为 daemon，Main 线程 join 后即靠它们活着）

| 线程名 | 创建点 | 周期 | 职责 |
|---|---|---|---|
| `ftcsim-physics` | World.java:269 | **固定步 DT=0.001s（1 kHz）**，追赶模式（最多 catchUp 50 步，`Thread.onSpinWait` 微等待） | 唯一推 tick 的人：`loop()`（World.java:285-319）里 `step()` → `steps += DT`；`step()`（World.java:84-101）按序积分 chassis → 非 drive 电机 → 舵机 → deadwheel → 发射物 → 电池，再广播 stepListeners。`markPhysicsThread()`（World.java:282）反射调用 `HardwareBus.markExemptThread` 豁免 hub 时延/暂停门 |
| `ftcsim-broadcast` | Simulator.java:130 | **20 Hz**（`next += 50_000_000L` = 50 ms；`launcher.poll()` 注释印证同为 20 Hz） | 只在有 client 时：launcher.poll()、drainScores/Tips/Pickups、广播 `state`/`log`，每 10 tick（约 0.5 s）广播 configurables/graphs/buildStatus（broadcastLoop，Simulator.java:611-652）。比赛计时器到点也在它这里派生 `ftcsim-timer-stop`（:641） |
| `ftcsim-eventloop` | SimOpModeRunner.java:281 | 50 Hz（"像 DS 的手柄包"注释，:316） | 每次 pass：`HardwareBus.gate()`（暂停在这冻）→ `newGamepadDataAvailable` → `internalOnEventLoopIteration`（SDK 内此回调跑团队 `loop()`）→ `drainGamepadEffects`；监测 OpMode 线程结束则派生 stopper（:310-313） |
| 团队 OpMode 线程 | SDK 自己（`internalInit()` 起，SimOpModeRunner.java:154） | 无（用户代码节奏） | 运行 `runOpMode()`/`loop()`；真正的 hardware 读写发生在这条线程上，因此吃 hub 延迟与暂停门。异常经 `RobotLog.threadPoolErrorHook` 回到 runner（:87, 242） |
| `ftcsim-http-accept` | WebServer.java:162 | 阻塞 accept | 每连接 → `ftcsim-http` 池线程 |
| `ftcsim-http`（池） | WebServer.java:144 | 按请求 | 处理 HTTP API 与 websocket 消息；`Simulator.onMessage`（手柄状态、init/start/stop、sim.* 全部 UI 控制消息）在这条线程同步执行（Simulator.java:200-316），UI 侧的 stop/restart/rebuild 明确 `new Thread(...)` 派生（:221-222, 294），避免占住 HTTP 线程 |
| `ftcsim-ws-writer`（每 client 一条） | WebServer.java:71 | 从 `pending` 队列 drain（200ms poll 超时） | 只写 socket；`send()` 永不阻塞——帧入队，落后 64 帧标记掉帧、8 次 → 关闭（Dead-drop 保护，WebServer.java:55-91） |
| `ftcsim-vision-<camera>` | VisionPortalImpl.java:116 | 30 Hz（`FPS`，frameLoop :78-95） | 对每个 `SimVisionProcessor` 调 `simFrame()`（真实 pose 算检出，无图像帧）；先 `markExemptThread()`（:79） |
| `ftcsim-initial-build` / `ftcsim-rebuild` | Simulator.java:117, 294 | 一次性 / 事件触发 | TeamCode.build()；build 完成后 startWatching() 的 WatchService 线程（TeamCode.java:128） |
| `ftcsim-internalStop` / `ftcsim-opmode-stopper` / `ftcsim-ui-stop` / `ftcsim-ui-restart` | SimOpModeRunner.java:186, :313; Simulator.java:221-222 | 一次性 | stop 路径各环节（internalStop 豁免 hub 时延；eventloop 发现 OpMode 线程死了也派生 stopper） |

**web 工程师类比**：很像一个"多事件循环的单体"：
- `ftcsim-physics` = 固定 dt 的 game loop（渲染循环的对等物），**自带追赶 + 防螺旋限制**（catchUp<50，落后太久直接 re-anchor `next=now`，和 `browser.requestAnimationFrame` 落后处理同构）；
- `ftcsim-eventloop` = Driver Station 心跳包的处理回调定时器；
- `ftcsim-broadcast` = SSE/websocket 推送聚合器；
- HTTP 线程 = 无状态 API 层，只做消息路由，重活都 `spawn` 出去。

## 结论 3（核心问题回答）：tick 是谁在推

**结论：只有 `ftcsim-physics` 一个线程推 tick；其余线程全是它的观察者/消费者。**

- `World.loop()`（World.java:285-319）自锁式调度：`next += stepNanos/timeScale`，冻住（`frozen()` = paused 且无 stepU 预算，World.java:67）时 `sleep(5)` 空转。
- 团队代码**不**跑在 physics 线程上；它通过 hub 调用（HardwareBus）与 physics 只共享 `MotorState`/`ServoState` 等内存状态——**tick 与机器人代码是生产者-消费者，通过设备状态解耦**（这也是"暂停时机器代码冻在下一个 hub 调用"能成立的条件）。
- 另外两条周期线程不推物理：`ftcsim-eventloop`（50Hz）只跑与 SDK 语义一致的回调 + 手柄状态复制；`ftcsim-broadcast`（50Hz）只做 UI 出站推送。三者共享同一时钟来源 `System.nanoTime()`（墙钟近似，玩 timeScale 时 physics 自行缩放其步长（World.java:303），另两条线程节奏不变）。

**web 工程师类比**：就像游戏引擎里"物理 update 在独立线程、输入轮询与网络推送各占一条"，但团队代码跑的是"真机语义另一条线程"，两者由设备状态缓冲区交流——不是 lockstep。

## 源码证据文件
- `sim/src/main/java/ftcsim/Main.java:9-38`（入口 + join）
- `sim/src/main/java/ftcsim/Simulator.java:77-134`（start 装配）、:221-222,294,641（UI/timer 派生线程）、:611-652（broadcastLoop 50Hz）
- `sim/src/main/java/ftcsim/physics/World.java:17（DT=0.001）, 67-77（frozen/stepFor）, 84-101（step 一次做什么）, 266-319（loop 调度）`
- `sim/src/main/java/com/qualcomm/robotcore/eventloop/opmode/SimOpModeRunner.java:131-158（init 流程）, 161-199（start/stop）, 279-321（eventLoop 50Hz）, 330-338（gamepad effects）`
- `sim/src/main/java/ftcsim/web/WebServer.java:55-137（每 client writer）, 139-173（accept + 池）`
- `sim/src/main/java/org/firstinspires/ftc/vision/VisionPortalImpl.java:78-121（frameLoop 30Hz / resumeStreaming）`
- `sim/src/main/java/ftcsim/teamcode/TeamCode.java:100-144（构建完成通知 + WatchService 监听）`

---

【2026-10-23 | Phase 1 第 2 步：电机信号链 | 见下 | 见末尾清单】

## 结论 4：写入链路 setPower → MotorState

团队代码拿到的电机对象是 `SimDcMotor`（它 `extends DcMotorImplEx`，即 SDK 真实实现的子类，只覆写了 `setDirection`/`fillView` 等极少数方法）。

链路：
- `motor.setPower(p)` → SDK `DcMotorImplEx.setPower` → `controller.setMotorPower(port, p)`，此处的 `controller` 是 `SimMotorController`（`SimDcMotor` 构造时 `super(controller, …)` 注入）。
- `SimMotorController.setMotorPower`（`SimMotorController.java:69-74`）：先 `HardwareBus.write()` 扣 **写时延 2.5ms**，再 `st.power = clamp(p, -1, 1)`（写入的是 *controller frame* 的 -1..1），并清掉 `useVelocityTarget`（setPower 会覆盖 setVelocity 模式）。
- `MotorState.power` 是 `volatile`，由 physics 线程读取——这就是"SDK 命令"与"物理积分"之间唯一的解耦点。

**web 工程师类比**：`MotorState` 就像前后端之间的一个**共享设备状态库（如 Redis 里的 device:<port> 哈希）**。SDK 侧（team 线程）是"写方"，调 setPower 相当于 `HSET device:2 power 0.8`；physics 线程（后台 worker，1kHz）是"读方"，poll 这个字段算出新角度再写回 `position`。两边不直接调用，只通过这块共享状态通信——这正是"暂停时机器人代码冻在下一个 hub 调用"能成立的原因（第 1 步结论 3 已点出）。

## 结论 5：物理符号 physicalSign（mount × orientation）

`MotorState.physicalSign = mountSign * orientationSign`（`MotorState.java:45,70,77`）。这是"控制器系（电机 nominal FORWARD）"与"机构系（轮子/机械臂实际前进方向）"之间的符号桥：

- `orientationSign`：由电机型号决定。`MotorType.ccw`（goBILDA、REV HD Hex 等 CCW 电机）SDK 会反转 raw power/encoder 符号，模拟器在 `setType` 里同步（`MotorState.java:76`）。这正是 brief 里说的"SDK 对 CCW 电机反转 power/encoder 符号"。
- `mountSign`：由**团队代码 `setDirection(REVERSE)`** 设置（`SimDcMotor.setDirection`，`SimDcMotor.java:36-42`），前提是 `assumeDirectionsCorrect`——即"团队声明反向是因为安装朝向如此"，于是把它镜像到物理世界。

约定：**永远不要直接设 `physicalSign`**，改 mount/orientation，由乘积派生（CLAUDE.md 明确）。

## 结论 6：物理积分（physics 线程，1kHz）

`World.step`（`World.java:84-101`）顺序：先 `chassis.step`（驱动轮），再对每个非驱动 `MotorState` 调 `m.stepLoad(dt, volts)`（drive wheel 在 chassis 里单独处理，见下）。

**drive wheel（被地面约束）**：`Chassis.step`（`Chassis.java:72-103`）按运动学反解出每根轴的转速 `shaft`，调 `w[i].observeShaftSpeed(shaft, dt)`（`MotorState.java:177-180`，直接覆写 `omega` 并推进 `angle`），再 `torqueStep` 只为算反作用力与电流（轮子不打滑时由牵引力上限限幅）。drive wheel **不**走 `stepLoad` 的惯性积分——它的转速是底盘决定的，不是扭矩积分出来的。

**非驱动电机（自由负载，如臂、intake）**：`MotorState.stepLoad`（`MotorState.java:183-198`）：
1. `torqueStep(dt, volts)`（`159-174`）：
   - `controlStep(dt)`（`126-153`）按 `mode` 算 **duty（占空比 -1..1）**：
     - `RUN_WITHOUT_ENCODER`：`duty = power`（开环，line 149）
     - `RUN_USING_ENCODER`：`duty = velocityPidf.update(power*achievableMaxTps, measured, dt)`（line 132-133，闭环速度）
     - `RUN_TO_POSITION`：`duty = velocityPidf.update(vt, measured, dt)`，`vt` 由位置误差×`positionP` 限幅（line 137-143）
     - `STOP_AND_RESET_ENCODER`：`duty = 0`
   - `omegaC = physicalSign * omega`（转到控制器系，line 161）→ `volts = duty * batteryVolts`（line 162）
   - 据 `duty`/`zeroPowerBehavior` 取 `model.torque` / `model.brakeTorque`（BRAKE）／`0`（FLOAT）（line 164-172）
   - 返回 `physicalSign * tauC`（转回机构系，line 173）
2. 用 `tauMotor` 做转动惯量积分：`alpha = (tauNet − 库仑摩擦)/inertia`，更新 `omega`、`angle`，再过 `minAngle/maxAngle` 限位（`183-198`）。

**速度模型 `MotorModel`**（线性有刷直流电机，`MotorModel.java:4-8`）：
- `torque = stallTorque·(V/Vnom) − (stallTorque/freeSpeed)·omega`（line 15-18）
- `brakeTorque = −(stallTorque/freeSpeed)·omega`（短路制动，line 21-24）
- `current = stallCurrent·|V/Vnom − omega/freeSpeed| + noLoadCurrent`，封顶 1.2×stall（line 27-31）

**web 工程师类比**：`controlStep` 就是"把开环目标翻译成闭环占空比的中间件"。`RUN_WITHOUT_ENCODER` 像直连（duty=power）；`RUN_USING_ENCODER`/`RUN_TO_POSITION` 像挂了个**自动调温器（PID）**——你给目标转速/位置，它不断比较"实际 encoder 速度"与"目标"，输出占空比让误差归零。`MotorModel` 则是这个电机的"物理响应函数"，类似一个纯函数 `torque(volts, rpm) => N·m`。

## 结论 7：Hub PIDF 仿真（REV hub 速度环）

PIDF 在 `controlStep` 的 `RUN_USING_ENCODER` 与 `RUN_TO_POSITION` 分支里通过 `MotorState.velocityPidf.update(...)` 调用。

`PidfController.update`（`PidfController.java:29-45`）：
- 输出单位模拟 hub 的 **16-bit duty 范围**（-32767..32767）：`out = f*target + p*error + i*integral + d*deriv`，再 `/32767` 归一化到 -1..1（line 35, 43-44）。系数约定 `f = 32767/maxVel, p = 0.1f, i = 0.01f`，由 `MotorState.resetDefaultVelocityPidf` 在换型时重置（line 92-95，CLAUDE.md 已记）。
- **`HUB_LOOP_HZ = 20`**（line 23）：I/D 项按 20Hz 缩放（line 31 的 `deriv`、line 39 的 `integral += error*dt*loopHz`），使"F=32767/maxVel, P=0.1F, I=0.01F"这套常见 SDK 建议值在模拟器里表现与真 hub 一致（带百分之几超调）。`HUB_LOOP_HZ_OVERRIDE` 是测试钩子（line 25）。
- **积分过零重置**（line 33）：误差在 setpoint 两侧变号时 `integral = 0`，避免起转时攒的积分量冲过目标后还要回拉。
- **anti-windup**（line 36-42）：输出饱和且与误差同号时不再积分；I 项单独限在 1/4 满量程。

`setVelocity`（SDK）也走这条路：`SimMotorController.setMotorVelocity` 置 `useVelocityTarget=true` 并切到 `RUN_USING_ENCODER`（line 89-95）。

## 结论 8：读数链路 MotorState → 团队代码（含物理符号回灌）

- `motor.getCurrentPosition()` → `SimMotorController.getMotorCurrentPosition`（`SimMotorController.java:82`）→ `hub.bulkRead("pos/"+port, st::encoderTicks, …)`。`LynxModule.bulkRead`（`LynxModule.java:120-148`）**复刻真 hub 的 bulk 缓存语义**（OFF/MANUAL/AUTO 三模式、AUTO 下重复读触发刷新），并在取值后扣 **bulk 读时延 2ms**（`HardwareBus.bulkRead()`，line 144）。
- `MotorState.encoderTicks()`（`MotorState.java:102`）：`angleControllerFrame() = physicalSign * angle − encoderOffset`（line 101）→ `ticks = round(· / 2π * ticksPerRev)`（line 102）。`resetEncoder` 把 `encoderOffset` 设为当前值（line 120），即"清零"是把偏移对齐到此刻角度。
- 速度读数 `velocityTps`（line 105-109）额外模拟了 hub 的 **int16 溢出**（`emulateVelocityOverflow`，默认开）：值被强转 `(short)`，与真 hub 行为一致。
- `fillBulkData`（`SimMotorController.java:47-56`）把 4 个口的 position/velocity/busy/overcurrent 一次性灌进 `LynxModule.BulkData`，供 bulk read 缓存消费——这就是真机上"一次 bulk 读拿到所有传感器"的对应物。

**web 工程师类比**：`getCurrentPosition` 像前端轮询 `/api/device/2/position`，但带了一层 **bulk read 缓存**（类似 HTTP 客户端的响应缓存 + 304 语义）：OFF 模式每次都打"后端"（live），AUTO 模式同一 key 重复读会触发一次刷新后再用缓存。缓存里的值来自 `fillBulkData`（模拟器一次性快照），不是每次实时算。

## 结论 9（回答本步核心问题）：为什么"模拟器代码 = 真机代码"

整条链路上，**团队 OpMode 接触到的唯一 SDK 类就是真实的 `DcMotorImplEx`**（`SimDcMotor` 只是它的子类，没改任何业务方法）。模拟器只替换了两处"硬件绑定"件：
1. 构造 `SimDcMotor` 时把一个 `SimMotorController` 当 `DcMotorController` 注入（替代真 hub 的 `LynxMotorController`）；
2. `LynxModule` 被 shadow（`ftc-sdk/shadowed-classes.txt` 第 6 行）。

于是：
- **写命令**：真机 → 真实硬件协议；模拟器 → `SimMotorController` → `MotorState.power` → `World` 物理积分。
- **读命令**：真机 → 编码器硬件；模拟器 → `MotorState.encoderTicks/velocityTps`（已含 controller-frame 符号与 int16 溢出）。
- 两者用的是**同一套 FQN、同一套 SDK 方法签名**，所以 OpMode 里 `setPower/getCurrentPosition/setVelocity/setMode` 逐字不变，仿真器跑通的就是真机跑通的代码。这正是 brief 读者画像里要解释的那件事。

## 源码证据文件
- `sim/src/main/java/ftcsim/hardware/SimDcMotor.java:12（extends DcMotorImplEx）, 23-30（注入 SimMotorController）, 36-42（setDirection→mountSign）`
- `sim/src/main/java/ftcsim/hardware/SimMotorController.java:69-74（setMotorPower 写 power+扣写时延）, 82（getMotorCurrentPosition→bulkRead）, 47-56（fillBulkData）, 89-95（setMotorVelocity）, 107-120（PIDF 系数）`
- `sim/src/main/java/com/qualcomm/hardware/lynx/LynxModule.java:120-148（bulkRead 缓存语义+扣读时延）, 54-72（BulkData）, 150-156（refreshBulkData）`
- `sim/src/main/java/ftcsim/physics/MotorState.java:45,70,77（physicalSign）, 21-36（命令字段）, 101-123（读数/符号）, 126-153（controlStep 算 duty）, 159-198（torqueStep/stepLoad 积分）, 92-96（默认 PIDF 系数）`
- `sim/src/main/java/ftcsim/physics/PidfController.java:23-45（HUB_LOOP_HZ、积分过零、anti-windup、16-bit 归一化）`
- `sim/src/main/java/ftcsim/physics/MotorModel.java:4-31（线性直流电机 torque/brake/current 公式）`
- `sim/src/main/java/ftcsim/physics/World.java:84-101（step 顺序：chassis 先，非驱动电机 stepLoad）`
- `sim/src/main/java/ftcsim/physics/Chassis.java:53-103（drive wheel 经 observeShaftSpeed 设 omega，无惯性积分）`

---

【2026-10-23 | Phase 1 第 3 步：传感器模型 | 见下 | 见末尾清单】

## 结论 10：伺服链路与 secondsFullRange 缓动（本步最关键）

写入：`servo.setPosition(p)` → `SimServoController.setServoPosition(port, p)`（`SimServoController.java:35-41`）：扣 `HardwareBus.write()` **写时延 2.5ms**，写 `st.commanded = clamp(p, 0, 1)`，并把 `everCommanded`、`pwmEnabled` 置 true（hub 只有在写过位置后才驱动伺服，`ServoState.java:12-13`）。

**缓动在 `ServoState.step(dt)`，由 `World.step` 每 1ms 调用**（`World.java:93`）：
- 位置模式（`ServoState.java:48-56`）：`rate = 1/secondsFullRange`（**默认 `secondsFullRange = 0.6`，即 0.6 秒走完 0..1 全程**，`ServoState.java:16`）；每步最多移动 `stepMax = rate*dt`，向 `commanded` 靠拢；`omega = move/dt`，电流按是否在动取 idle/moving（`ServoState.java:55`）。
- CR 连续模式（`ServoState.java:42-47`）：`power = (commanded-0.5)*2`（绝对值 <0.02 归零），`omega = power*maxRevPerSec*2π`，累加 `angle`。

**关键洞察（也是 CLAUDE.md 那个 launcher 陷阱的根因）**：`getServoPosition(port)` 返回的是 `st.commanded`（**指令值**，`SimServoController.java:42`），而实际物理位置是 `ServoState.position()`——指令下达后两者最多相差约 0.6s。因为伺服是按 `secondsFullRange` **限速缓动**而非瞬移，所以"发射扳机"这类判断必须用带迟滞的电平穿越，逐帧看跳变会完全错过慢速伺服。这与 CLAUDE.md 的 launcher 第 1 个陷阱直接对应。

**web 工程师类比**：`commanded` 像 WebSocket 刚 push 过来的"目标数值"，`position` 像 CSS transition 实际渲染到的中间值——`secondsFullRange` 就是那个 `transition-duration`。读 `servo.getPosition()` 拿到的是目标值，不是动画当前帧的值。

## 结论 11：IMU 由底盘真实位姿反算（无独立噪声模型）

`SimBNO055IMU`（legacy `BNO055IMU` 接口）直接持有 `Chassis` 引用，**所有读数都是底盘真实状态的翻译**：
- `heading() = chassis.pose().heading`（`SimBNO055IMU.java:38`）；`getAngularOrientation` 用它构造 INTRINSIC ZYX 朝向，CCW 为正（`51-54`）。
- `getAngularVelocity` → `chassis.omega()`（`56-59`）；`getOverallAcceleration` → `chassis.accelerationRobot()` + 9.81 重力，`getLinearAcceleration` 不含重力（`55, 60`）。
- 位置：靠 `startAccelerationIntegration` 记下 `integrationOrigin = chassis.pose()`，`getPosition` 返回当前 pose 与 origin 之差加初值（`65-77`）——**不是真正的积分误差累积**。
- 固定"健康响应"：校准状态全 true、温度 25°C、磁场 0、`isArmed/SystemStatus` 恒好（`62-63, 81-85`）。

**web 工程师类比**：IMU 不是传感器模型，而是底盘数据的**只读转换视图**（像 GraphQL resolver 把内部 domain model 映射成 IMU 协议返回）——它的 heading 是"上帝视角的真值"，永远不漂移。

## 结论 12：数字量 / 模拟量走"手动 or 派生"双源

两者同构：`SimDigitalChannel extends DigitalChannelImpl`（`SimDigitalChannel.java:9`）、`SimAnalogInput extends AnalogInput`（`SimAnalogInput.java:9`），构造时把数据源 `attach` 到各自的 controller：
- **手动源**（默认）：`manualState` / `manualVoltage`，由 UI 经 `applyInput("state"/"voltage")` 设置（`SimDigitalChannel.java:39-42`、`SimAnalogInput.java:33-36`）。
- **派生源**：`setDerivedSource(BooleanSupplier/DoubleSupplier, desc)` 挂到世界或其他设备上（`SimDigitalChannel.java:24`、`SimAnalogInput.java:24`），例如把模拟量接到伺服反馈冒充绝对编码器（`SimAnalogInput.java:8` 注释 "e.g. servo feedback"）。
- 读数时 INPUT 模式走 `inputSource()`；数字量 OUTPUT 模式读 controller 的 `outputState(port)`（`SimDigitalChannel.java:34`）。`fillView` 会把 `source` 描述给 UI 看。

**web 工程师类比**：像前端组件既接受 `value` prop 也支持 `valueSource` 回调——两路数据源二选一，没挂回调就用手填的那个。

## 结论 13：I2C —— 真实模型优先，VirtualI2cDevice 兜底

- **有模型的走专门 `Sim*`**：`SimColorSensorV3`、`SimOTOS`、`SimPinpoint`、`SimBNO055IMU` 等直接**覆写公开 API，完全不谈 I2C 寄存器**（`VirtualI2cDevice.java:7-8` 注释明确）。
- **没模型的走 generic path**（`SimHardware.createGenericI2c`，`SimHardware.java:415-431`）：若请求的是 interface 且在 `I2C_IMPLS` 映射里则反射替换为 driver impl（`I2C_IMPLS` 目前**只有 OctoQuad**，`SimHardware.java:412-413`）→ 要求必须是 `I2cDeviceSynchDevice` 子类且非抽象 → 遍历构造函数，用 `(I2cDeviceSynch bus, boolean)` 或 `(bus)` 签名注入 `VirtualI2cDevice` 实例 → 否则放弃返回 null。
- **`VirtualI2cDevice` 是个"什么都读不出、写操作空实现"的总线**：所有 `read8/read` 返回 0 或空数组，`write` 全部 no-op（`VirtualI2cDevice.java:24-38`），存在的唯一目的是让那些未知 driver 类的**构造函数能跑通**（`VirtualI2cDevice.java:5-9`）。

**web 工程师类比**：像 HTTP 客户端 mock——未知设备拿到的是一个"对所有路径都返回空 200"的 stub driver，能构造、能调用，但没有真实数据；只有被专门实现的设备才返回真实业务数据。

## 结论 14：传感器层的统一套路（与第 2 步电机同源）

所有传感器都遵循同一个模式：`Sim*` 类 **extends / implements SDK 真实设备类型**（`SimDigitalChannel extends DigitalChannelImpl`、`SimAnalogInput extends AnalogInput`、`SimBNO055IMU implements BNO055IMU`、`SimServo`/`SimCRServo` 同理），因此 OpMode 用 `hardwareMap.get(...)` 拿到的仍是**真的 SDK 类型**；模拟器只是把"读硬件寄存器"换成"读模拟世界状态"。这就是 CLAUDE.md"只用真 SDK 类、只 shadow 硬件绑定类"在传感器层的落地。

## 源码证据文件
- `sim/src/main/java/ftcsim/physics/ServoState.java:16（secondsFullRange=0.6）, 40-57（step：位置缓动 / CR）, 21-24（position vs commanded）`
- `sim/src/main/java/ftcsim/hardware/SimServoController.java:35-41（setServoPosition 扣写时延）, 42（getServoPosition 返回 commanded）, 48-67（pwm range / SDK12 pulse width / forgetLastKnownPosition）`
- `sim/src/main/java/ftcsim/hardware/SimBNO055IMU.java:38（heading←chassis）, 51-77（朝向/角速度/加速度/位置积分）`
- `sim/src/main/java/ftcsim/hardware/SimDigitalChannel.java:9,17-42（manual/derived 双源 + applyInput）`
- `sim/src/main/java/ftcsim/hardware/SimAnalogInput.java:9,17-36（同上）`
- `sim/src/main/java/ftcsim/hardware/VirtualI2cDevice.java:5-9（目的）, 24-38（读写 no-op）`
- `sim/src/main/java/ftcsim/hardware/SimHardware.java:407-431（I2C_IMPLS + createGenericI2c 反射注入）`
- `sim/src/main/java/ftcsim/physics/World.java:93（每步驱动 ServoState.step）`

---

【2026-10-23 | Phase 1 第 4 步：物理积分与世界 | 见下 | 见末尾清单】

## 结论 15：World.step —— 一个 tick 里到底按什么顺序推进

`DT = 0.001`（1 kHz，`World.java:17`）。`step(double dt)`（`World.java:84-101`）严格按序：
1. `chassis.step(dt, volts, field)`（:86）——底盘（含 drive wheel 的运动学反解与刚体积分）；
2. 对每个**跳过 drive wheel** 的电机 `m.stepLoad(dt, volts)`（:88-92）——drive wheel 已在①处理；
3. `for (ServoState s : servos) s.step(dt)`（:93）——**伺服缓动**在这里按 `secondsFullRange` 推进（接第 3 步结论 10）；
4. 取 `vr = chassis.velocityRobot()`、`omega = chassis.omega()`，逐个 `d.step(vr, omega, dt)`（:94-96）——**deadwheel 里程计**；
5. `stepProjectiles(dt)`（:97）——球；
6. `battery.step(current)`（:98）——负载电流汇总后算电压 sag；
7. `simTime += dt`；最后回调 `stepListeners`（:99-100）。

`current` 是①~③所有驱动/电机/舵机电流之和加底盘电流，喂给⑥，闭环到下一步的电压。

**web 工程师类比**：像一个严格有序的 `requestAnimationFrame` 回调链，或一条 ETL pipeline——顺序不能乱，因为后一步依赖前一步刚积分出来的速度/位置（例如 deadwheel 必须用 chassis 本步最新的 `vr`）。

## 结论 16：底盘运动学——"先给轮子上约束，再反问每个轮子有多大力"

`Chassis.step`（`Chassis.java:60-133`）是第 2 步"drive wheel 不走 stepLoad"的另一半：
- **逆运动学反解轮速**：由机体系速度 `vr` 与 yaw rate `omega` 反算每轮 rim 速度（mecanum `rim = vr.x ± vr.y ± k*omega`，`k = halfWheelBase+halfTrackWidth`，`Chassis.java:69`；tank 见 `:91,99`）→ `shaft = rim/wheelRadius/gearRatio`（:74）→ `w[i].observeShaftSpeed(shaft, dt)` **直接覆写该轮 omega**（:75）。
- **再由每轮反算推力**：`torqueStep(dt, batteryVolts)` → `force = tauShaft/gearRatio/wheelRadius`（:76-77）→ **按牵引上限限幅** `±tractionCoefficient*normalPerWheel`（:78-79；mecanum `N = mass*9.81/4`，tank `/n`）。
- **合成刚体力/力矩**：mecanum `fx=Σf, fy=-f0+f1+f2-f3, tz=k*(-f0+f1-f2+f3)`（:82-84）；tank `fx=fl+fr, tz=halfTrackWidth*(fr-fl)`（:106-107），且强制 `vr.y=0`（无侧向滑移约束，:88）。
- **体摩擦** `frictionForce`（:136-147）：黏性 `-viscous*v` + 库仑；**静止时**库仑项只抵消驱动力（上限 coulomb，不会反向驱动）；**运动时**与"一步内让它停下的力"取小，避免库仑摩擦把物体推成反向。
- **积分**：`aRobot=(fx+ffx)/mass` → `aField=aRobot.rotated(heading)`（:116-118）→ `v += aField*dt`、`omega += (tz+tf)/inertiaZ*dt`（:119,:125）→ `pose.x/y += v*dt`、`heading += omega*dt`（:128-131）。`inertiaZ = mass*(length²+width²)/12`（:40）。
- **碰撞** `resolveCollisions(field)`（:149-193）：场地边界按四角推出；障碍物把机器人当作半径 `hypot(hl,hw)*0.85` 的圆；碰撞后**消掉指向障碍的法向速度分量**，`omega *= 0.5`。

**web 工程师类比**：这是"**先解约束、再积分**"——先把约束得到的轮速灌进电机，再从电机取回它此刻能给多少力，就像先定好布局再问每个组件实际占多宽，而不是反过来。

## 结论 17：发射推断三陷阱（与第 3 步伺服缓动的接缝）

核心哲学（`LauncherController.java:15-17`）：**"the simulator watches, it is never told"**——不改团队代码、SDK 没有"我发射了"的回调，射击**从硬件行为推断**；入口 `poll()` 由 UI 的 20 Hz 广播循环驱动（:121-122，呼应第 1 步 broadcastLoop）。

- **陷阱 1｜伺服是斜坡不是台阶 → 用"带迟滞的电平穿越"，不用位移差**
  读的是 **`ServoState.position()`（实际物理位置）**（`LauncherController.java:182-185`），**不是** `commanded`——正是第 3 步结论 10 的落点。判据为 `pos >= spec.triggerPosition`（电平穿越，:135），而非"两次 poll 之间位移改变了多少"；注释明说 *watching how far it moved between two polls misses slow ones*（:130-132）。重新装弹条件是 `pos <= triggerPosition - triggerDelta`（:141）——**`triggerDelta` 就是迟滞量**。
- **陷阱 2｜`minRpm` 是"球推入后等待轮子到速"的速度，不是一次性闸门**
  `if (pushPending && rpmReached() && fire() > 0) pushPending = false`（:140）：推球先把球喂进轮子，球**等着**轮子升到 `triggerMinRpm` 才出膛，因此即使团队的 spin-up 比飞轮加速短，仍能拿到全速射击（:137-139）。
- **陷阱 3｜`minRpm` 必须接近真实击球转速**
  设得太低，球会在轮子还在加速时就离开，勉强飞过机器人（CLAUDE.md 已记）。无 servo 的纯飞轮分支：`rpm >= min && !flywheelArmed` 发射，`rpm < min*0.8` 重新武装（0.8 倍迟滞，:151-152）。
- 其余：`COOLDOWN_S = 0.25`（:25）保证一次推球不能清空弹匣；`fire()` 把整个弹匣排成齐射，首发立即、其余按 `salvoIntervalSec` 由 `poll()` 到点逐发释放（:73-80, :126）。

**web 工程师类比**：等于给第三方页面做埋点时"**只能观察，拿不到业务事件**"——于是只能定义"看到某元素宽度超过 X 就算触发"。斜坡 vs 台阶，正等价于不能用"两次采样差多少"去判断一个带 CSS transition 的动画是否到达终点。

## 结论 18：球的积分、状态机与回收

- 球不是通用刚体求解器，而是"带半径的质点"（`Projectile.java:12-15`），只与地面、边界、轴对齐障碍物、CELL 作用。
- `step(dt, field)`（:73-90）顺序：`age += dt` → `vz -= GRAVITY*dt`（`GRAVITY = 9.80665/INCH`，:21）→ `applyDrag` → 位置积分 → `hitPerimeter` → **`checkGoal` 在 `hitObstacles` 之前**（:82-86：球门同时也是障碍物，先判进球否则会被弹出）→ `hitObstacles` → `hitCells` → `hitFloor`；落地后若 `ROLLING` 则 `roll(dt)`。
- **空气阻力**（:97-112）：给了质量用真实公式 `½·ρ·Cd·A·v²/m`（这正是轻球比重球飞得近的原因）；没给质量退回线性阻尼 `DRAG = 0.06`。
- 落地 `RESTITUTION = 0.55`，每次弹跳水平速度再 ×0.86（:167-169）；`|vz| < SETTLE_SPEED(6)` 转 ROLLING（:171）；滚动 `ROLL_DECEL = 45 in/s²`，`< REST_SPEED(2)` 转 REST（:174-181）。
- **状态机**：`FLIGHT → ROLLING → REST`；另有一个 `CAPTURED`（进 CELL 后随 CELL 移动、不再参与积分，`rideInCell`，:252-258）。
- **上限**：`MAX_PROJECTILES = 24`（`World.java:44`），超限**先丢已静止的球**（:114-119），所以对着墙打也推不掉还在场的球。
- **唯一的补弹方式**：机器人开过地上的静止球 → `collectBalls()` 移出场地并入 `pickupEvents`（`World.java:160-168`），弹匣 +1（`LauncherController.collect()`）。

## 结论 19：里程计、电池与 UI 耦合

- **DeadWheel**（`World.java:96` → `DeadWheel.java:21-25`）：由底盘机体系速度与 yaw rate 精确推算触点速度 `contact = vRobot + omega × position`，投影到 pod 方向积分出角度。**是真值模型，不含打滑/安装误差**——团队代码若靠它做航位推算拿到的是理想值。
- **Battery**（`World.java:98` → `Battery.java:14-17`）：`volts = max(6.0, 12.8 - 0.06*(load + 0.5))`。**电流越大电压越塌**（下限 6 V）。这形成闭环：电机电流（第 2 步 MotorModel）→ 电池压降 → 下一步所有电机可用电压下降。
- **UI 耦合（本步的"与 UI 滑块/交互"）**：
  - `LauncherController.setPitchDeg(Double)`（:52-59）：**UI 的 pitch 滑块运行时覆盖 `spec.pitchDeg`**（`pitchOverride` 优先），对应 CLAUDE.md「launcher row 自己的 pitch slider 会覆盖 `sim.launcherPitch`，配置的 `pitchDeg` 只是滑块起点」。
  - `Chassis.teleportEpoch`（:38,:43-45）：UI 拖动 / reset / snap 每次自增 1，标记"机器人被瞬移过"，让依赖连续位姿的逻辑（如团队代码的航位推算重置）知道世界被打断。
  - `World.timeScale` / `stepFor`（`World.java:49-51`）：UI 时间缩放与"单步"按钮；`stepFor` 在暂停期间开一个 sim-time 预算，让世界走一小段再冻住。

## 源码证据文件
- `sim/src/main/java/ftcsim/physics/World.java:17（DT=0.001）, 84-101（step 顺序）, 44（MAX_PROJECTILES）, 108-123（spawnProjectile）, 144-168（stepProjectiles/collectBalls）, 49-51（timeScale/stepFor）`
- `sim/src/main/java/ftcsim/physics/Chassis.java:15-25（默认参数）, 40（inertiaZ）, 60-133（step）, 136-147（frictionForce）, 149-193（resolveCollisions）, 38-45（teleportEpoch）`
- `sim/src/main/java/ftcsim/hardware/LauncherController.java:15-17（推断哲学）, 121-154（poll 决策/三陷阱）, 83-119（releaseOne）, 25-27（COOLDOWN/DEFAULT_MIN_RPM）, 52-59（pitchOverride）, 182-185（读 ServoState.position）`
- `sim/src/main/java/ftcsim/physics/Projectile.java:73-90（step 顺序）, 97-112（阻力）, 160-181（落地/滚动）, 20-33（常量）, 252-258（rideInCell）`
- `sim/src/main/java/ftcsim/physics/DeadWheel.java:21-30（里程计推算）`
- `sim/src/main/java/ftcsim/physics/Battery.java:14-17（电压 sag）`

---

【2026-10-23 | Phase 1 第 5 步：真实感三件套 | 见下 | 见末尾清单】

## 结论 20：三件套是同一个 ThreadLocal 驱动的两件事

`HardwareBus`（122 行）用**一个 `ThreadLocal<Boolean> EXEMPT`**（`HardwareBus.java:30`）同时管控「要不要扣 hub 时延」和「要不要过暂停门」——这是第 5 步的枢纽：

- **收费对象只有团队代码**。类注释明说（:14-15）：*"Only robot code pays: the UI, the physics loop and the vision threads read the same state through exempt."*
- **四类模拟器自己的线程**调用 `markExemptThread()` 终身豁免：`ftcsim-broadcast`（`Simulator.java:130`）、`ftcsim-physics`（`World.java:290`）、`ftcsim-vision-*`（`VisionPortalImpl.java:79`）、**OpMode stop 线程**（`SimOpModeRunner.java:186`）。
- 另有 `exempt(Runnable)`（:55-60）做**块级临时豁免**（可重入，对称恢复），供 UI/诊断读设备状态时用。
- **stop 线程为什么要豁免**：暂停时 OpMode 线程正卡在 `gate()` 里；若 stop 线程也过门，就永远停不掉一个已暂停的 OpMode。

## 结论 21：一次 hub 事务到底做了什么（`charge`）

`charge(millis, counter, count)`（`HardwareBus.java:73-83`）的顺序很关键：
1. `if (EXEMPT.get()) return;`（:74）——豁免线程直接返回，**不扣费也不过门**；
2. **`gate();`（:75）——暂停门被嵌在收费里**：因此每一次 hub 事务都会顺带检查一次是否冻结。这就是「机器人代码冻在下一个 hub 调用」的**单点实现**；
3. 计数（`writes/reads/bulkReads/i2cOps`），`ioNanos` 累计（团队代码累计等待时长，UI 可读 `ioMillis()`，:49）；
4. **时延按 timeScale 缩放**：`scale = World.timeScale(); nanos = millis*1e6/scale`（:79-80）——UI 加速时等待等比变短，减速时变长；
5. `sleepNanos(nanos)`。

时延常量（`HardwareBus.java:23`，可由 `configure()` 从 `RobotConfig.hardwareLatency` 覆盖，:35-37）：**bulk 读 2.0ms / 写 2.5ms / 读 2.0ms / I2C 2.5ms**。

**一个精妙的细节**：`LynxModule.bulkRead`（:120-147）里，**只有真正发生 hub 事务才扣费**——`refreshed` 才 `bulkRead()`（扣 2ms），`singleRead`（OFF 模式）走 `read()`（扣 2ms），**命中缓存时只调 `gate()` 不扣费**（:144-146）。所以 bulk caching 在模拟器里同样能提速，和真机是同构的。

**web 工程师类比**：`EXEMPT` 就是一个 request-scoped 的 feature flag / 权限凭证。团队代码每次访问"hub 这个远端服务"都要过统一的拦截器 `charge()`：拦截器先检查是否暂停（熔断/降级开关），再按配置好的 RTT 睡一会儿（模拟网络往返）。命中本地缓存的请求不产生 RTT，但仍要过熔断检查。

## 结论 22：暂停门 —— 世界和机器人代码冻在同一瞬间

- 冻结判据：`World.frozen() = paused && simTime >= stepUntil`（`World.java:67`），即"已暂停**且**没有遗留的单步预算"。
- `stepFor(seconds)`（:70-74）打开预算：`stepUntil = simTime + seconds; paused = true;`。UI 的 Step 按钮就是它。`setPaused(true)` 则会把 `stepUntil` 清成 `-INF`（:59）。
- `gate()`（`HardwareBus.java:85-94`）本体：豁免直接返回；否则 `while (w.frozen()) { 若线程被中断则立即返回; LockSupport.parkNanos(1ms); }`——**每毫秒轮询**，而非一次性阻塞，这样 stop 请求（interrupt）能立刻打断等待。
- **三个 blocking 入口**（对应 CLAUDE.md 那句）：
  1. `charge()` 内部（:75）——任何 hub 事务；
  2. `SimOpModeRunner` 事件循环 each pass（:303）——注释（:302）*"a paused simulation freezes robot code: an iterative OpMode stops between loop() calls"*；
  3. `publishTelemetry`（:347）——注释（:346）*"telemetry.update() is the other place nearly every OpMode passes through each loop"*。
  
  因此**既不碰硬件也不发 telemetry 的那部分代码会继续往下跑**，直到它第一次触碰这三者之一才被冻住。
- physics 线程**不走 `gate`**，而是在自己的 `loop()` 里显式 `if (frozen()) { next = now; Thread.sleep(5); continue; }`（`World.java:293-297`）——世界自己在同一个 `frozen()` 判据上停下，于是**世界与 OpMode 停在同一判据的同一瞬间**。

## 结论 23：让时序真的像真机 —— Windows 15.6ms scheduler tick 陷阱

这是本项目最值得记的工程细节之一（`HardwareBus.java:96-121`）：
- **坑**：`LockSupport.parkNanos` 在 Windows 上会把请求**向上取整到 15.6ms 的调度 tick**。若用它去 sleep「2.5ms hub write 的第一毫秒」，实测要花 ~15ms，结果**每个 OpMode 比真机慢约 6 倍**（:99-107 注释原话）。
- **解法（混合策略）**：`sleepNanos` 先睡**整毫秒** `Thread.sleep((remaining - SPIN_NANOS)/1e6)`，剩下最后 `SPIN_NANOS = 1.5ms` 用 `Thread.onSpinWait()` **自旋到 deadline**（:108-121）。每轮都检查 `isInterrupted()`，所以 stop 能随时打断。
- 同一手法在 physics loop 的收尾维持 1kHz 节拍时复用：差值 > 2ms 才 `Thread.sleep`，否则 `Thread.onSpinWait()`（`World.java:307-312`）。

顺带两个结构事实：
- **physics 线程用反射把自己标成豁免**：`World.markPhysicsThread()`（:281-283）通过 `Class.forName("ftcsim.hardware.HardwareBus").getMethod("markExemptThread").invoke(null)` 调用。原因很可能是 `ftcsim.physics` 包不想反向依赖 `ftcsim.hardware` 包——**用反射打破包循环依赖**。
- physics loop 的**防死亡螺旋**：追赶循环限制 `catchUp < 50`，一旦达到上限就直接 `next = System.nanoTime()` 重新锚定（:299-306），和浏览器 `requestAnimationFrame` 落后时的丢弃策略同构（呼应第 1 步结论 2）。

## 源码证据文件
- `sim/src/main/java/ftcsim/hardware/HardwareBus.java:23（时延常量）, 30（EXEMPT ThreadLocal）, 55-63（exempt/markExemptThread）, 65-83（write/read/bulkRead/i2c + charge）, 85-94（gate）, 96-121（sleepNanos 混合策略与注释）`
- `sim/src/main/java/ftcsim/physics/World.java:67（frozen）, 70-77（stepFor/stepRemaining）, 285-319（loop：frozen 自停/catchUp<50/sleep-spin）, 280-283（反射 markExemptThread）`
- `sim/src/main/java/com/qualcomm/robotcore/eventloop/opmode/SimOpModeRunner.java:186（stop 线程豁免）, 301-305（eventLoop gate）, 345-348（publishTelemetry gate）`
- `sim/src/main/java/com/qualcomm/hardware/lynx/LynxModule.java:120-147（isExempt 直读 live + 三种扣费路径）`
- `sim/src/main/java/ftcsim/Simulator.java:130（broadcast 线程豁免）`
- `sim/src/main/java/org/firstinspires/ftc/vision/VisionPortalImpl.java:79（vision 线程豁免）`

---

【2026-10-23 | Phase 1 第 6 步：Vision | 见下 | 见末尾清单】

## 结论 24：VisionPortalImpl —— 30 Hz 的假 framesuloop 与"团队自写 processor 的天花板"

`VisionPortalImpl extends VisionPortal`（`VisionPortalImpl.java:30`），`FPS = 30.0`（:32）。类注释把话说死了（:25-29）：*"There is no camera: the portal runs a 30 Hz 'frame' loop that feeds simulated processors ... Processors written by the team are initialised but never receive frames."*

- 构造（:45-65）：对每个 processor 调一次 `p.init(w, h, null)`（:57）；**凡是 `!(p instanceof SimVisionProcessor)` 的都记入 `notSimulated` 并在构造末尾 `RobotLog.ww` 列出类名**（:58-60）——这是最重要的边界：**团队自己写的 CV pipeline 在此处被明确告知"不会产生结果"**。随后 `SimVision.register(this)`（:62）。
- `frameLoop()`（:78-95）：第一行 `HardwareBus.markExemptThread()`（:79，**接第 5 步**，视觉线程不付 hub 时延也不过暂停门）；循环里只对 **enabled 且是 `SimVisionProcessor`** 的 processor 调 `simFrame(mount, w, h, now)`（:84-88）；严格 30 Hz 由 `next += 1e9/FPS` 维持，`Thread.sleep(ms, ns)` 纳秒级睡眠，**落后则 `next = now` 重新锚定**（:90-93，与 physics loop 的防死亡螺旋同构）。
- 线程由 `resumeStreaming()` 起 daemon 线程 `ftcsim-vision-<camera>`（:113-121），`stopStreaming()` 中断它（:107-112）。`getFps()` 仅在 STREAMING 时返回 30（:124）。

**"没有图像帧"的三个具象证据**：
1. `AprilTagProcessorImpl.processFrame(Mat frame, ...) → null`（`AprilTagProcessorImpl.java:94`）；
2. `getFrameBitmap()` 返回 `Bitmap.createBitmap(w, h, ARGB_8888)`——**有 Bitmap 对象，但里面什么都没画**（`VisionPortalImpl.java:155-158`）；
3. `getCameraControl(Class)` 返回一个**动态代理万能 stub**（:126-150）：`set*` 存进 map、`get*` 原值返回、`is*`/boolean 一律 TRUE、int/long/double 中含 `Max`→100 含 `Min`→1 否则 0、enum 返回第一个常量。任何 `CameraControl` 接口都不会 NPE。

**web 工程师类比**：这是一个 **mock server + feature-detect 的文件夹**。`SimVisionProcessor` 相当于只有第一方 SDK 组件才实现的私有 protocol；团队自定义组件虽有同样的 public interface 并能通过构造/初始化，但因为没实现这个私有 protocol，压根不会被调用——等价于注册了一个 mock 但永远收不到 webhook。

## 结论 25：AprilTagProcessorImpl —— 纯几何检出（视锥 + 朝向 + 最小像素 + 距离噪声）

`extends AprilTagProcessor implements SimVisionProcessor`（:37）。`simFrame`（:165-253）每帧从头算一遍，**没有任何像素处理**：

1. 真值起点：`p = SimVision.robotPose()`（:166），即 `chassis.pose()`（`SimVision.java:26`）→ 相机世界位置 `camWorld = robot + mount偏移.rotated(heading)`（:185）。
2. **可见性判定 `Camera.see(x, y, z, yawDeg, minFacing)`（:115-138）是一个 return-null 链，任一失败即不可见**：
   - `dist < 2 in` → 太近（:118）；
   - `facing = normal.dot(-rel/dist)`，小于 `minFacing`（标签 0.25 / cluster 0.15）→ **标签没大致朝向相机**（:119-121）；
   - `inCam = rel.rotated(-camYaw)`，`inCam.x <= 0` → **在相机背后**（:122-123）；
   - `az = atan2(inCam.y, inCam.x)`，`|az| > hfov/2` → 水平出视锥（:124-125）；
   - `el = atan2(dz, inCam.x) - camPitch`，`|el| > vfov/2` → 垂直出视锥（:126-128）；
   - `range3 > maxRange`（默认 200 in）→ 太远（:131-132）。
   通过后算出 `xF/yF/zF`（相机系左/前/上）与像素坐标 `txp/typ`（:133-136）。
3. **最小像素门槛**：`apparentPx = sizeIn*focal/range3*facing`，**小于 6 px 直接丢弃**（:200-201）——所以远处标签不是"模糊"而是彻底消失。
4. **距离相关噪声**：`noise = mount.noiseIn * max(1, range3/48)`（:202，48 in 为基准，越远越不准），叠加 `random.nextGaussian()*noise`（:259）与 `random.nextGaussian()*mount.noiseDeg` 的角度噪声（:203,:263-264）。`Random` 种子固定为 11（:45），因此**同一次运行是可复现的**。
5. 三种 pose 一并构造（:257-274）：`AprilTagPoseFtc`（x/y/z + yaw/pitch/roll + range/bearing/elevation，含噪声，:266）、`AprilTagPoseRaw`（x, −z, y + 由 yaw 构造的旋转矩阵，:268-269）、以及 **`Pose3D robot` = 真值位姿 + 高斯噪声**（:270-272）——团队调 `getRobotPose()` 拿到的是"带噪声的真值"，不是视觉推算结果。
6. `getFreshDetections()` 靠 `fresh.getAndSet(false)` 实现"只看新帧"语义（:100）。

## 结论 26：相机位置怎么定（mount vs setCameraPose，含朝上/下防呆）

默认用**配置的相机安装**（`CameraMount`，`CameraMount.java:5-10`：机器人坐标系 x 前 y 左 z 上，`zIn` 默认 8 in，`hfov 70.4/vfov 43.3`（Logitech C920 级别），`maxRangeIn 200`，`noiseIn/noiseDeg 0.3`）。

若团队调了 `setCameraPose()`（`cameraPoseInverse != null`），则从 `OpenGLMatrix` 反推（:169-183）：
- 先做 identity 判断：**单位矩阵就当作没设，继续用配置的 mount**（:173-174）；
- **`|fZ| > 0.99` → 相机光轴指向正上/正下**，此时 `RobotLog.ww` 警告并**回退到配置的 mount**（:175-176）——这是个很实用的防呆：团队给的 pose 让它看天时会明确提示并被忽略；
- 否则转换坐标系：`camX = cam.get(1,3); camY = -cam.get(0,3); camZ = cam.get(2,3)`，`camYawRel = atan2(-fX, fY)`，`camPitch = atan2(fZ, hypot(fX, fY))`（:178-180）。注意这一步把 OpenGL 的"X 右 Y 前 Z 上"换成内部"x 前 y 左"约定。

## 结论 27：标签放在哪 & SDK 12 cluster，以及一个跳 holistic 事实

- `placement(AprilTagMetadata)`（:143-165）优先用**库自带的 `fieldPosition` + `fieldOrientation`**；其中 `pl.yawDeg = degrees(atan2(-z.y, -z.x))`（:150-151），因为 **`VectorF(0,0,1)` 经 quaternion 变换后得到的是"指向 tag 内部"的法线，标签朝向是其反方向**——这正是 CLAUDE.md 那条「tag library quaternions: the tag's local +Z points INTO the tag face」的代码落地。
- **未被 library 放置的标签**（如 DECODE obelisk）改从 `SimVision.field()` 的 `Field.AprilTag` 里按 id 取位置（:154-161）；可见性统一交给 `field.rules().isTagVisible(field, tag)`（:160）——`DecodeRules` 让 `id 21..23` 中只有 obelisk 选项选中的那一面在场，其余返回 null。`SimVision.obeliskTag()` 与 `sim.obelisk` 已删除。
- **SDK 12 cluster**（:220-249）反射调用库的 `getAllClusters()`：先逐 member 用更宽松的 `minFacing(0.15)` 判定可见数，**`percent = 可见成员数 / 总成员数`** 作为置信度（:241）；成员越多，噪声除以 `sqrt(visible)` 越准（:237）。
- **跨 SDK 版本兼容全靠反射**（静态块 :51-80）：优先找 SDK 12 的 `AprilTagSingleDetection` 带 `DistanceUnit` 的构造函数（:71-73），找不到退回 SDK 11 的 `AprilTagDetection` 老签名（:76）；cluster 相关 Method/Field 也一律 `Class.forName` + 失败置 null（:58-67）。这与 CLAUDE.md「SDK 11/12 都得编译通过」的约束一致。
- **一个容易被忽略的事实**：视觉线程是**豁免线程**（第 5 步结论 20），所以**暂停时 `simFrame` 仍在被调用**，只是 `robotPose()` 被冻结故检出结果恒定——暂停期间 UI 里的 AprilTag 读数不会消失也不会变化。

## 源码证据文件
- `sim/src/main/java/org/firstinspires/ftc/vision/VisionPortalImpl.java:25-32（类注释/FPS）, 45-65（构造 init + notSimulated warn）, 78-95（frameLoop 30Hz/豁免/重锚定）, 107-121（stop/resume）, 126-150（getCameraControl 动态代理）, 155-158（空白 Bitmap）, 167-182（view）`
- `sim/src/main/java/org/firstinspires/ftc/vision/apriltag/AprilTagProcessorImpl.java:37（继承+SimVisionProcessor）, 94-98（processFrame 等空实现）, 117-141（Camera.see 视锥判定）, 143-165（placement / tag +Z / isTagVisible）, 167-276（simFrame 全貌）, 202-204（6px 门槛 + 距离噪声）, 257-274（三种 pose + 噪声）, 59-80（SDK 11/12 反射兼容）`
- `sim/src/main/java/ftcsim/physics/DecodeRules.java`（`isTagVisible`：obelisk 只留选中那一面）
- `sim/src/main/java/ftcsim/vision/SimVision.java:17-33（真值上下文 install/robotPose/field/register/closeAll）`
- `sim/src/main/java/ftcsim/vision/SimVisionProcessor.java:4-9（simFrame/simSummary 接口）`
- `sim/src/main/java/ftcsim/vision/CameraMount.java:5-10（相机安装默认参数）`

---

# Phase 2 · 3D 前端（`sim/src/main/resources/web/3d/`）

> 交接补充：Phase 2 的研究结论此前只以图的形式存在（现位于 `docs/` 各主题目录，见 `docs/index.md`），未入笔记。本文按同样格式补记，并新增第 6 步（其余模块）与第 7 步（总览：结构 + 逐帧时序）。**只读研究，不改代码、不提交 git。**
> 前端是**原生 JS/CSS + ES modules**（`index.html:8-15` 用 importmap 从 CDN 引 `three@0.170.0`），不是框架。核心心智模型：**后端 20 Hz 推"目标状态快照"，前端 `requestAnimationFrame` 自己插值画出来——两条独立时钟**（呼应 Phase 1 的 broadcast 20 Hz 与 physics 1 kHz）。

---

【2026-10-24 | Phase 2 第 1 步：WebSocket 消息如何驱动 3D | 见下 | 见末尾清单】

## 结论 28：三种消息、三种节奏，一条 `/ws` 全双工

`main.js:112-151` 的 `handle(m)` 按 `m.type` 分发。**没有状态机，收到即覆盖**：

| 消息 | 节奏 | 触发 | 体量 | 前端动作 |
|---|---|---|---|---|
| `hello` | 按需一次 | 连上后发 `{type:"hello"}`（`main.js:106`） | 大 | `field.rebuild` + `robot.setSpec` + `renderFieldOptions` + `clearTrail` + `balls.clear`（`:115-124`）→ **重建整个 3D 场景** |
| `field` | 事件驱动 | HIVE 倾覆/场地选项改（后端 `broadcastField`，`Simulator.java:378-380`） | 大 | 只 `field.rebuild` + `renderFieldOptions`（`:125-129`） |
| `state` | **周期 20 Hz** | `broadcastLoop` 每 50 ms（`Simulator.java:625`） | 中 | 分发到 robot/devices/balls/launcher/overlay/HUD（`:131-139`） |

`hello` 之后后端**紧跟着单独发一条 `opmodes`**（`sendHello`，`Simulator.java:368`），所以 OpMode 下拉不用等下一次 state。

**web 工程师类比**：像 `SSE/ws` 推全量快照的推送架构——后端不维护"每个客户端的增量差异"，每次把整个世界序列化一遍；前端拿到的是一份幂等快照，直接整体覆盖，天然无状态。

## 结论 29：`state` 的字段 → 各驱动前端哪个模块，以及两个单位约定

`stateMessage()`（`Simulator.java:473-532`）构造**全量快照**：`robot` / `opmode` / `telemetry`+`panels`+`dashboard` / `drawings` / `devices` / `balls` / `launcher` / `battery` / `sim`（含 `io`）/ `drivetrain` / `codeFrame`。

**两个必须记住的单位约定（后端发送前就转好了，前端不用再换算）**：
- 位置：`robot.x / y` 已是**英寸**——物理内部是**米**，`Units.mToIn(p.x)`（`Simulator.java:481`）；速度 `vx/vy` 同理（`:482`）。
- 角度：`headingDeg / omegaDeg` 已是**度**——物理内部是弧度，`Math.toDegrees(...)`（`:481-482`）。

另外 `robot.epoch`（`:483`）是"瞬移纪元"：UI 拖拽 / reset 会让后端 `poseEpoch` 自增（呼应 Phase 1 结论 19 的 `Chassis.teleportEpoch`），前端用它触发轨迹清空（`main.js:96` `robot.onEpochChange = clearTrail`，`robot.js:296-300`）。

**web 工程师类比**：后端像 API 网关，把领域单位（米/弧度）翻译成前端好画的"展示单位"（英寸/度），前端是纯展示层。

## 结论 30：前端在"目标"和"显示"之间补了一层平滑

两个真实例子（`main.js` 注释原话 + 代码）：
1. **机器人位姿**：`robot.setState` 只**设定目标**（`robot.js:289-301`），`robot.update(dt)` 才负责**平滑走过去**（`robot.js:304-331`）：先按 `vx/vy/ω` **外推最多 0.12 s** 补偿 20 Hz 延迟（`:306-310`），再指数平滑 `k = 1 - exp(-14·dt)`（`:312-315`）。
2. **画线 sticky 策略**：后端对 `drawings` 有 **3 秒新鲜度限制**（`Simulator.java:500/502`：`now - pd.time < 3000`），团队停止画线 3 秒后路径就不广播了——早期版本导致 Road Runner 规划路径"闪一下就没了"。前端改成 **sticky**（`main.js:258-268`、`overlay.js:257-265` 注释）：自己不主动过期，只在**加载新 OpMode（INIT 态）/ Clear 按钮 / 机器人重启**三个时机清。

**web 工程师类比**：后端像"每 50ms 推一次的目标值"，前端像"rAF 补间动画"；而 sticky 策略是"后端语义不够用，前端补一层生命周期策略"。**遇到 UI 上东西莫名消失，第一反应查后端广播的新鲜度/生命周期条件。**

## 源码证据文件
- `sim/src/main/java/ftcsim/Simulator.java:356-370（sendHello：field+robot+startPose+紧跟 opmodes）, 378-380（broadcastField）, 382-411（fieldMessage：zones/shapes/clusters/options/state/tags/obstacles）, 473-532（stateMessage：robot 单位换算/opmode/三路遥测/drawings/devices/balls/launcher/battery/sim+io/drivetrain/codeFrame）, 500/502（drawings 3s 新鲜度）, 611-652（broadcastLoop 20Hz）, 625（广播 state）`
- `sim/src/main/resources/web/3d/main.js:103-110（connect/send）, 112-151（handle 分发）, 131-139（state 各字段分发）, 258-268（overlay sticky）, 524-553（animate 渲染循环）`

---

【2026-10-24 | Phase 2 第 2 步：场地 `field.js`——描述如何变 3D | 见下 | 见末尾清单】

## 结论 31：整个前端只有**一个**坐标映射函数

`field.js:26`：`ftc(x, y, z=0) → Vector3(x, z, -y)`。把后端 FTC 场地系（x 观众侧 / y 红→蓝 / z 高度，英寸）映射到 Three.js（**1 unit = 1 英寸**）：**x 不变、z 提到中间当高度、y 取负放最后**。`facingNormal(yawDeg, pitchDeg)`（`:29-32`）把标签朝向一并换算。

**web 工程师类比**：像一个 `transform: translate3d(...)` 的轴重排——"把平面的 y 摊成高度、另一个平面轴取负"。看到任何 `ftc(...)` 调用都这么替换，就不晕。

## 结论 32：`rebuild()` 七步流水线，以及"必须 dispose"的坑

前端收到 `hello`/`field` 后 `field.rebuild(m.field)`（`main.js:118/128`），内部按序（`field.js:551-561` 起）：
① `clear()`——**dispose 旧几何/材质/贴图**再 `root.clear()`（`:118-124`，WebGL 资源不手动释放会内存泄漏，与 DOM 不同）；② `buildShell(name)` 地板+联盟墙+网格+围墙（`:127-171`）；③ `buildZones(zones)` 半透明色块+边线（`:174-189`）；④ `buildObstacles` 盒子（`:204-214`）；⑤ `buildClusters`（CELL 五边形 + HIVE 跷跷板，`:272-339`）；⑥ `buildTags`（canvas 画 id 再贴 `CanvasTexture`，`:423-465`）；⑦ `buildShapes`（2D 俯视覆盖层，**默认关闭** `SHOW_FLAT_SHAPES=false`，因为 3D 里已有立体几何，`:23, 467-535`）。

**web 工程师类比**：像一条 ETL pipeline——每个后端基元类型对应一个 build 函数；`dispose()` 相当于前端资源管理里显式的"垃圾回收"，这是 3D 与 DOM 最大的心智差异。

## 结论 33：三个真实的"契约缺口"（值得记录）

| 缺口 | 现状 | 风险 |
|---|---|---|
| **obstacle 没有高度** | `fieldMessage` 只发 `name/minX/minY/maxX/maxY/color`（`Simulator.java:408`），而 `Field.Obstacle` 类里**有** height 字段（`Field.java:21`）。前端只能按**名字正则猜**高度（`field.js:193-202`：HIVE→1.5、FLOWER→21.5、goal→40，默认 1.5） | 新赛季名字不匹配就画成贴地薄片 |
| **tag 尺寸字段名** | 后端发 `size`（`Simulator.java:405`），前端曾读 `sizeIn` 回退到默认 6.5 in——CELL 上的 tag 只有 3.25 in 且间距 3.75 in，导致重叠（`field.js:427-429` 注释） | 已兼容 `t.size \|\| t.sizeIn`，属"靠容错兜住"而非契约明确 |
| **HIVE 倾覆联动靠父子 Group** | CELL 与贴在它底面的 AprilTag 挂在同一个 `hiveAssembly` Group 下（`:263`），倾覆 `aimHives()` + `update(dt)` 指数衰减缓动（`:398-413`），变换继承自动带动 | 后端只发 `state`（`scoring`/`audience` 字符串），**动画完全前端自己算**（又印证"后端给目标、前端做平滑"） |

**web 工程师类比**：`disposeObject` 的显式资源回收 + `renderOrder`（overlay 里地面 ribbon 分层）——典型的"数据驱动渲染"工程。

## 源码证据文件
- `sim/src/main/resources/web/3d/field.js:26（ftc）, 29-32（facingNormal）, 34-44（disposeObject）, 47-73（tag 数字贴图）, 118-124（clear）, 127-171（buildShell）, 174-189（buildZones）, 204-214（buildObstacles）, 272-339（buildClusters/buildHives）, 398-421（aimHives/update 缓动）, 423-465（buildTags）, 467-535（buildShapes）, 551-561（rebuild）`
- `sim/src/main/java/ftcsim/Simulator.java:382-411（fieldMessage）, 405（tag 用 size）, 408（obstacle 无 height）, 403（state 字段）`
- `sim/src/main/java/ftcsim/physics/Field.java:18-47（Obstacle/Goal 基元）`

---

【2026-10-24 | Phase 2 第 3 步：机器人 `robot.js`/`parts.js`——mount 如何变成零件 | 见下 | 见末尾清单】

## 结论 34：端到端数据流与"没有 mount 就不画"

团队在配置 JSON 里给电机/伺服声明 `device.params.mount`（`{x,y,z,axis,minDeg,maxDeg,shape,lengthIn,diameterIn,parent}`）→ 后端 `DeviceMount.fromSpec()` 读（`ftcsim/vision/DeviceMount.java:77-92`，读不到返回 null）→ `toView()` 序列化（`:54-63`）→ 随 20 Hz `state.devices[].mount` 下发 → 前端 `parseMounts(devices)`（`parts.js:19-39`，**没有 mount 的设备直接跳过**）→ `mountSignature()` 比对签名（`:42-45`，没变不重建）→ `buildPartMesh(mount)`（`parts.js:55-117`）。

**设计决策**（`parts.js:4-6` 注释原话）：*"A part with no mount is left out of the robot model rather than parked at the origin: absence is a statement, and drawing a part somewhere the team never declared would be a guess."* —— 与后端 `fromSpec` 返回 null 语义配套。

**web 工程师类比**：像"只有声明了 data-attribute 的元素才进组件树"——缺省即缺席，不做猜测性渲染。

## 结论 35：一个零件的三层 Group 结构与两遍挂载（两个关键技巧）

**三层 Group**（`parts.js:55-117`）：`Group(part)` → `Group(joint)` → `Group(body)` → Mesh。
- `joint` 是**转动只作用的层**（`updateParts` 只需设 `joint.rotation.z` 一个值）；
- `body` 沿轴偏移 `lengthIn/2`（`parts.js:107/111`），让零件"挂在安装点外侧摆动"而非穿过它；
- 转动轴由 `mount.axis` 决定：`axis='z'`（臂）→ `rotation.order='ZYX'` + body 沿 local Z 偏移；`axis='x'`（轮）→ `order='XYZ'` + body 沿 local X 偏移（`:105-113`）。
- **坐标映射** `toLocal(m) = Vector3(m.x, m.z, -m.y)`（`parts.js:14-16`）——与 `field.js:ftc` 同一套规则（x 不变、z 当高度、y 取负）。

**两遍挂载**（`robot.js:83-98`）：第一遍全部 add 到 `robot.group`，第二遍对声明了 `parent` 的零件重新挂到父 Group 下，并用 `toLocalIn(mount, parentMount)`（`robot.js:452-454`）**在机器人帧里做差再映射**（不能在已旋转的父场景系里直接减）。**为什么要两遍**：如果一边 add 一边 re-parent，某个 child 的 parent 可能还没加进来，就会被留在底盘上。

**web 工程师类比**：`joint` 独立出来 = CSS 里把 `transform` 和 `position` 分开写在两个嵌套容器；两遍挂载 = "先建全 DOM 再统一移动节点"，避免依赖插入顺序。

## 结论 36：伺服和电机的动画策略不同（并咬合 Phase 1 物理）

`updateParts(dt)`（`robot.js:341-368`）按 `mount.sweeps` 区分（`sweeps = |maxDeg-minDeg| > 1e-6`，`parts.js:35`）：
- **伺服**：`joint.rotation.z = angleFor(mount, pos)`（`parts.js:120-124`），`pos` 优先取 `v.position`（后端实际物理位置）否则 `v.commanded`——**直接跳到角度，不做缓动**。因为伺服没有速度可积分，前端缓动会误报机构实际位置（真实缓动后端 `ServoState` 已按 `secondsFullRange` 做过，Phase 1 结论 10）。
- **电机**：`_spin += rpm·(π/30)·dt`（`robot.js:361`）**按物理转速积分**。`rpm` 来自 `MotorState.physicalOmega()` **已带 physicalSign**——CCW 电机（goBILDA/REV HD Hex）的符号后端就处理好了，用 `duty` 会让飞轮看起来倒转（Phase 1 结论 4-5 的直接落点）。
- **着色按 duty 而非 rpm**（`robot.js:363-365` + `activity.js`）：堵转的飞轮转得慢但仍在全力驱动，颜色表达"指令"而非"结果"。

**web 工程师类比**：`activity.js` 的两种编码 = "接口设计上区分 position 型与 velocity 型控件"——伺服是范围（amber 亮度表达走到哪），电机是带符号驱动（绿前进/红后退，亮度带功率）。

## 源码证据文件
- `sim/src/main/resources/web/3d/parts.js:14-16（toLocal）, 19-39（parseMounts）, 35（sweeps）, 42-45（mountSignature）, 55-117（buildPartMesh 三层+轴）, 120-124（angleFor）`
- `sim/src/main/resources/web/3d/robot.js:56-63（setDevices）, 76-99（buildParts 两遍）, 101-187（buildBody：CHASSIS_OPACITY=0.22 半透明+不透明边线）, 246-258（setCamMount）, 260-286（setSpec：heightIn/chassisOpacity/drivetrain）, 289-301（setState+epoch）, 304-331（update 外推+平滑）, 341-368（updateParts）, 391-415（setAmmo 弹药球）, 452-454（toLocalIn）`
- `sim/src/main/java/ftcsim/vision/DeviceMount.java:22-33, 44（sweeps）, 54-63（toView）, 77-92（fromSpec）`
- `sim/src/main/resources/web/3d/activity.js:23-60（motorColor/servoColor 编码）, 69-71（activityColor 按 sweeps 二选一）`

---

【2026-10-24 | Phase 2 第 4 步：画中画 `webcam.js`——第二渲染器 + AprilTag 检出框 | 见下 | 见末尾清单】

## 结论 37：PiP = 第二个 WebGLRenderer + 第二个 PerspectiveCamera，渲染同一个 Scene

`webcam.js:30-34`：第二个 `WebGLRenderer` 画到 `#camCanvas`，**`shadowMap.enabled = false`**（两个 renderer 共享同一个 `DirectionalLight`，若都写它的 shadow map 每帧重建两次——闪烁+浪费，注释 `:17-19` 原话）。相机 `camera.rotation.y = -π/2`（`:46`）——Three.js 相机看自己的 **-Z**，而 pod 的"前方"是机器人 **+X**，不转则 pitch 会让画面"翻滚"而非"俯仰"。相机 `robot.camPivot.add(camera)`（`:47`），**靠变换继承自动拿到位姿与俯仰，无需手动同步世界矩阵**。

`render()`（`:163-175`）每帧：`resize()`（按 hfov 推导垂直 FOV，`:50-63`）→ **临时隐藏 pod 下除相机外的子网格**（否则相机会看到锥壳内壁，`:167-170`）→ `renderer.render(scene, camera)`（`:171`）→ 恢复隐藏（`:172`）→ `drawOverlay()` 在 2D overlay canvas 上画检出框（`:173`）。

## 结论 38：检出框是前端自己投影的，不是后端给的像素坐标

后端只告诉前端"检出了哪些标签"（`AprilTagProcessorImpl` 摘要如 `tags [35, 36, RED SCORING 100%]`，随 `state.devices` 下发）。前端：
1. `parseDetections(devices)`（`:203-220`）正则 `TAGS_RE = /tags\s*\[([^\]]*)\]/` 抠出 `tag(id)` / `cluster(name,pct)`；
2. `findMesh` 在场景里按 `tag-<id>` / `cell-<name>` 找到对象（`:124`）；
3. tag 做 `facingOK()` 朝向检查（`:103-109`），**阈值 `MIN_FACING = 0.25` 与后端 `AprilTagProcessorImpl.Camera.see()` 里标签用的 0.25 是同一个数**（`:28` 注释）——否则 HIVE 背面的标签可能"刚好投影进画面"却被框出；
4. `_box.setFromObject(obj)` 取世界 AABB，8 个角逐个 `project(camera)` 转 NDC 再转屏幕坐标（`:127-138`），求 2D 矩形包围盒，2D canvas 上画框（tag 琥珀 `#ffd166` / cluster 绿 `#7ee2a0`，`:144-147`）。

**web 工程师类比**：像"自己把 DOM 元素的 getBoundingClientRect 投影到视口坐标再画高亮"——前端复刻后端一部分可见性判定，两边是自洽的几何投影，都不碰像素。

## 结论 39：`synced`——一个"宁可不画也不画错"的设计 + 相机 mount 是正则拆的字符串

- **synced**（`main.js:438-473`）：pod 摆在团队声明的 mount 上 → `synced=true`，overlay 正常画框；用户拖 pitch 滑块偏离超过 0.5° → `synced=false`，**overlay 停止画框**、状态行改"free look — team cam stays at X°"。因为一旦手动改俯仰，画面就不再是"团队程序看到的"，此时再画检出框就是撒谎。
- **契约不一致**：电机/伺服 mount 下发的是**结构化 JSON**（`DeviceMount.toView()`），而**相机 mount 是格式化字符串** `"(6.0, 0.0, 10.0) in yaw 0 pitch 60 fov 76x43"`，前端只能 `MOUNT_RE` 正则拆（`webcam.js:22-24`）。字符串解析更脆弱——后端格式一变（加字段/改分隔符）正则就失效。注释也点明这是个可改进点：把相机 mount 也改成结构化对象是低风险高回报。

## 源码证据文件
- `sim/src/main/resources/web/3d/webcam.js:22-28（MOUNT_RE/TAGS_RE/MIN_FACING=0.25）, 30-47（第二 renderer 关阴影 + 相机挂 camPivot + -90°）, 50-63（resize 垂直 FOV 推导）, 71-76（applyMount）, 81-84（setDetections/synced）, 100-109（facingOK）, 112-160（drawOverlay AABB→8 角投影→2D）, 163-175（render 隐藏 pod→渲染→恢复→画框）, 189-220（parseMount/parseDetections）`
- `sim/src/main/resources/web/3d/main.js:410-476（PiP 创建/camPitch/synced 文案）, 487-503（syncCameraTeam 当设备列表到达时接管）, 536-544（每帧 render + fps）`
- `sim/src/main/java/org/firstinspires/ftc/vision/apriltag/AprilTagProcessorImpl.java:198（cam.see(..., 0.25) 同一朝向阈值）`

---

【2026-10-24 | Phase 2 第 5 步：三套坐标系统一 | 见下 | 见末尾清单】

## 结论 40：Pedro 只存在于 UI 读数/输入框；权威数据与 3D 渲染始终是 FTC 系

**关键结论**：`Pedro` 系（服务 Road Runner / Pedro Pathing 生态）**只在界面读数与输入框里存在**。后端权威数据、3D 场景里机器人的实际位置，**始终是 FTC 系**。切 Frame 选择器只改那三个输入框显示的数值。

三套坐标系与换算（`panels.js:20-22`、`field.js:26`、`parts.js:14`）：

| 系 | 定义 | 换算 |
|---|---|---|
| ① FTC（权威，后端+3D） | 原点场地中心；+X 观众侧；+Y 红→蓝；+Z 上；heading 从 +X CCW（度）；英寸 | — |
| ② Pedro（仅 UI） | 原点场地一角（0..144） | `toPedro: (x,y,h) → (y+72, 72−x, norm(h−90))`；`fromPedro: (72−py, px−72, norm(ph+90))` |
| ③ Three.js（渲染） | 1 unit=1 inch；Y 上 | `ftc: Vector3(x, z, −y)`；反向 `FTC.x=three.x, FTC.y=−three.z, FTC.z=three.y` |

`norm(d)` 把角度归一到 (−180, 180]（`panels.js:20`）。换算发生的位置：
- **后端发送前**就把米→英寸、弧度→度（`Simulator.java:481-482`），前端拿到的 `state.robot` 直接是 FTC 英寸/度；
- **渲染**：`robot.update()` `group.position.set(x, 0, -y)`、`rotation.y = h`（`robot.js:317-318`，用 FTC 系）；
- **UI 读数**：`panels.js:374-376`，若 Frame=Pedro 则 `toPedro` 再显示；
- **UI 输入**：`panels.js:283`，若 Frame=Pedro 则 `fromPedro` 转回 FTC 再发 `sim.setPose`（后端只认 FTC）；
- **drawings 自带 frame 标记**：`state.drawings` 每项带 `frame:'ftc'|'pedro'`（Road Runner 用 FTC，Pedro Pathing 用 Pedro），前端按 `d.frame` 决定是否 `fromPedro`（`overlay.js:26, 44`、`2d/app.js:224`）。

**web 工程师类比**：像"后端 API 统一用 UTC 存时间，前端按本地时区显示，再提供一个 12/24 小时制的输入框"——数据权威层只认一种单位，展示层做多单位翻译。

## 源码证据文件
- `sim/src/main/resources/web/3d/panels.js:20-22（norm/toPedro/fromPedro）, 283（输入 fromPedro）, 298-325（showPose/frameSelect）, 372-377（读数 toPedro）`
- `sim/src/main/resources/web/3d/field.js:26（ftc）, parts.js:14-16（toLocal）, robot.js:317-318（摆位+yaw）`
- `sim/src/main/resources/web/3d/overlay.js:26,44（drawings 按 frame 转换）, 2d/app.js:224（同款）`
- `sim/src/main/java/ftcsim/Simulator.java:481-482（米→英寸/弧度→度）`

---

【2026-10-24 | Phase 2 第 6 步：其余前端模块（活动着色 / 球 / 拖拽 / 手柄震动） | 见下 | 见末尾清单】

## 结论 41：`activity.js` —— 两种着色编码（呼应 README 那句"颜色跟随活动"）

`activity.js:42-60`：`motorColor(power)` 用**色相表达方向**（绿 `HUE=0.33` 前进 / 红 `0.00` 后退）+**亮度表达功率**（`0.16+0.42·|p|`）；`servoColor(position)` 用**amber 表达位置**（`SERVO_HUE=0.135`，`0.42+0.28·p`）。`DEAD_ZONE=0.06` 以下当 idle（`IDLE_COLOR=0x14171c`）。`activityColor(mount, value, restColor)`（`:69-71`）按 `mount.sweeps` 二选一。

**设计取舍**（注释 `activity.js:13-19` 原话）：伺服**没有 forward/backward**，它的值是在 sweep 里"走到哪"，涂成红色会错误地暗示它在倒转——所以伺服永远 amber，与电机的绿/红区分。静止时保留零件自身颜色而非变灰（灰色对轮子是"idle"的语义，但整机构变灰会毁掉形状线索）。

## 结论 42：`balls.js` —— 只画球、物理在后端，插值避免"闪光"

`balls.js:29-93`：所有球**共享一个单位球体** `SphereGeometry(1,...)`，每个 mesh 用 `scale.setScalar(r)` 放大到自己半径（`:34-35, 56`）。`setState(list)`（`:46-76`）从 20 Hz `state.balls` 增/移/删：`target = Vector3(b.x, b.z, -b.y)`（`:50`，与 `ftc` 同一套映射），tint 按 `b.cell`（联盟色）/`b.goal`（计分绿）区分；`update(dt)` 指数插值 `k = 1 - exp(-30·dt)` 朝 target 靠（`:79-83`）——"fast ball does not strobe across the screen"。

**web 工程师类比**：像"游戏里客户端只渲染服务端下发的实体位置并做 tween"——球是纯展示，物理（重力/弹跳/滚动，Phase 1 结论 18）完全在后端 1 kHz。

## 结论 43：`drag.js` —— 用 `sim.setPose` 同一条消息回写，摇杆补偿

`drag.js:11-104`：`Raycaster` 拾取 `robot.group`（`:25`），命中后 `setPointerCapture` 抢占手势（`:48`，避免 OrbitControls 也处理）。拖拽移动 / shift+右键旋转，FTC 系坐标 `toFtc(p) = {x: p.x, y: -p.z}`（`:29`）。**关键**：`pushPose()` 每 `SEND_MS=35ms`（约 30 Hz）发 `sim.setPose`（`:31-40`）——同一条后端消息（`Simulator.java:onMessage` 里 `sim.setPose` 处理），与 2D 前端共用通道。发送后**立即把 `robot.current` 设为目标**（`:37-39`），否则会落后于 20 Hz 状态回推造成"拖不动"的滞后感。

**web 工程师类比**：像"拖拽后先乐观更新本地 UI，再异步同步服务端"——避免等待 50ms 广播回环造成卡顿。

## 结论 44：`gamepad.js` —— 物理手柄 + 键盘 + 虚拟手柄三合一，及 rumble 回放

`gamepad.js`：每个 gamepad 一个 `Pad`（`constructor` `:17-28`）。**输入融合**（`loop()` `:205-251`）：每轮先查物理手柄（浏览器 Gamepad API，DS 式分配 **Start+A→GP1 / Start+B→GP2** `:217-230`），有则 `applyPhysical()` 覆盖 `p.s`；否则若 GP1 仍有键盘按键则 `applyKeyboard()`（`:44-51`）；**两者都无时 `p.s` 保持原值**——这一「原值」正是**虚拟手柄**直接写入的（`:75-148`，2D 同款 widget）。故虚拟手柄**不是轮询源**，仅在物理/键盘都未激活时才保留，优先级应读作「物理 > 键盘 > 虚拟手柄（仅当无轮询源时有效）」，而非三者轮流覆盖。离散按键靠 `KEYMAP`（`:12-14`）查表（Z/X/C/V→a/b/x/y，Digit1/2/3/4→lb/rb/ls/rs，T/G/F/H→du/dd/dl/dr，另含 `KeyR→start`、`Backspace→back`、`Tab→guide`）；而 **WASD / 方向键（左右摇杆）/ Q·E（扳机 lt/rt）在 `applyKeyboard()`（`:44-51`）里用 `ax()` 做轴向合成，不进 `KEYMAP`**（原笔记误把它们归到 `KEYMAP:12-14`）。30 Hz `loop()` 轮询物理手柄 + `sendIfNeeded` 仅在状态变化时发 `{type:'gamepad', id, state}`（`:53-59`），每 15 轮一次 keepalive。

**rumble 回放**（`:156-201`）：后端在 OpMode 的 `gamepad1.rumble(...)` 效果队列里 drain（Phase 1）后经 `gamepad.rumble` websocket 下发，前端 `applyRumble` 在 `vibrationActuator.playEffect('dual-rumble', {...})` 上回放（`:162-167`）：`large→strong`、`small→weak`、`duration=-1` 当作 10 分钟连续效果、零功率/stop 消息重置（`:187, 199-201`）。无控制器时虚拟手柄面板显示 `rumble` 徽标（`:172-175`）。

**web 工程师类比**：`Pad` 像一个"多数据源归一化的输入控制器"，`sendIfNeeded` 像"状态变更时才触发同步"，rumble 是"服务端命令在客户端震动马达上重放"。

## 源码证据文件
- `sim/src/main/resources/web/3d/activity.js:23-31（HUE/DEAD_ZONE）, 42-60（motor/servoColor）, 69-71（activityColor）`
- `sim/src/main/resources/web/3d/balls.js:34-35（共享单位球）, 46-76（setState/tint）, 79-83（update 插值）`
- `sim/src/main/resources/web/3d/drag.js:25-40（拾取+pushPose+乐观更新）, 29（toFtc）, 8-9（LIMIT/SEND_MS）`
- `sim/src/main/resources/web/3d/gamepad.js:12-14（KEYMAP）, 44-51（applyKeyboard 轴向合成）, 53-59（sendIfNeeded）, 75-148（虚拟手柄）, 156-201（rumble 回放）, 217-230（DS 式分配）, 205-251（30Hz loop）`
- 后端对照：`Simulator.java:457-471（broadcastRumble/Stop）`

---

【2026-10-24 | Phase 2 第 7 步：总览——结构 + 逐帧时序（对应 `docs/frontend-3d/05-phase2-overview.md`） | 见下 | 见末尾清单】

## 结论 45：3D 前端的模块拓扑（与后端消息契约咬合）

前端不是框架，是**一组 ES module + 一个入口 `main.js`**。拓扑：
- `main.js` = 组合根：连 ws、`handle()` 分发消息、`animate()` 渲染循环、摄像头模式 / 顶部状态 / toast；
- `field.js` = 场地几何（重建流水线 + `ftc` 坐标）；
- `robot.js` / `parts.js` = 机器人模型 + 声明零件；
- `webcam.js` = 第二渲染器 + 检出框；
- `overlay.js` = 场地画线（sticky）；
- `balls.js` = 球（纯展示）；
- `panels.js` = HUD 面板 + Pedro 换算；
- `gamepad.js` / `drag.js` / `activity.js` = 输入 / 拖拽 / 着色。

**web 工程师类比**：像"原生 ES modules 的微前端/单页"——`main.js` 是 router + 状态分发中枢，其余模块各管一块 DOM/canvas，模块间通过事件 / 消息字段解耦。

## 结论 46：逐帧时序——20 Hz 状态 vs 60 fps 渲染（两条时钟）

`animate()`（`main.js:524-553`）每 rAF 帧：`robot.update(dt)` → `balls.update(dt)` → `field.update(dt)` → 轨迹 pushTrail（若开）→ `updateChase`/`controls.update` → （相机面板未折叠时）`webcam.render()` → `renderer.render(scene, camera)`。后端每 50 ms 来一个 `state` 更新目标，前端在自己的帧里插值。**后端慢不影响前端画面流畅**——这正是 README 说的"机器人的移动是被插值过的"。

**一次 `state` 消息的完整旅程**（对照 `docs/frontend-3d/05-phase2-overview.md` 图 2）：
1. `Simulator.broadcastLoop`（20 Hz，豁免线程）构造 `stateMessage()` 全量快照；
2. 经 `ftcsim-ws-writer`（每 client 一条，落后丢帧）写 socket；
3. `main.js handle('state')` 分发 → `robot.setState`（设目标）/ `syncCameraTeam`（设备+相机 mount+检出）/ `balls.setState` / `updateLauncher` / `overlay.setDrawings` / `syncOverlayLifecycle` / `updateHud`；
4. 下一 rAF 帧，各 `update(dt)` 朝目标插值，`renderer.render` 出图。

## 结论 47：赛季可复用分层（呼应 `docs/seasons/01-season-abstraction.md`）

前端 `field.rebuild` **按下发数据渲染，不认赛季**（`main.js:118/128`），这就是"换赛季只动赛季专属层"能在前端成立的原因。**现状的泄漏点**（值得记录）：
- `field.js:232-240` 前端硬编码了一整组赛季常量（`CELL_PENT`、`CELL_DEPTH`、`HIVE_PIVOT_Z`、`HIVE_Y`、`HIVE_TILT`），与后端 `Field.java:246/252` 数值重复；
- 后端 `Projectile` / `World` / `Field` 里也有 BIOBUZZ 赛季代码（Phase 1 结论 18 已见）。

**务实建议**（图 10 原话）：现在不必为明年过度设计，但把已泄漏到通用类的赛季代码**先剥出去**（纯挪位置、不改功能、低风险），明年新赛季改动就集中在一个新文件。

## 源码证据文件
- `sim/src/main/resources/web/3d/main.js:524-553（animate）, 112-151（handle）, 20-51（renderer/scene/camera/lights）, 63-96（world pieces/trail）`
- `docs/frontend-3d/05-phase2-overview.md`（结构图 + 逐帧时序图，Phase 2 总览）

---

## Phase 2 成果索引

| 图 | 文件 | 对应小步 |
|---|---|---|
| Three.js 基础（Scene/Camera/Renderer/坐标系/双层/帧循环） | `docs/frontend-3d/01-threejs-basics.md` | 准备篇 |
| WebSocket 消息驱动（hello/field/state + 双时钟） | `docs/architecture/02-websocket-drive.md` | 第 1 步 |
| 场地 → 3D（rebuild 流水线 + 契约缺口） | `docs/frontend-3d/02-field-3d-build.md` | 第 2 步 |
| mount → 零件（三层 Group + 两遍挂载 + 伺服/电机动画） | `docs/frontend-3d/03-robot-parts.md` | 第 3 步 |
| 画中画 + 检出框投影 + synced | `docs/frontend-3d/04-webcam-pip.md` | 第 4 步 |
| 三套坐标系总图（含数值演算） | `docs/architecture/03-coordinate-systems.md` | 第 5 步 |
| 赛季可复用分层（泄漏点与剥离） | `docs/seasons/01-season-abstraction.md` | 第 7 步配套 |
| **总览：结构 + 逐帧时序** | **`docs/frontend-3d/05-phase2-overview.md`** | **第 7 步** |

> 结论 41–44（activity/balls/drag/gamepad）与结论 45–47（总览）为本次补记新增，图 15 为本次新增总览图。

## 一句总结（Phase 2）

**"后端 20 Hz 推全量目标快照、前端 rAF 自己插值"贯穿始终**；三套坐标系里权威永远是 FTC，Pedro 只活在 UI 输入框；零件/相机/球都遵循"缺省即缺席、宁可不画也不画错"的数据驱动哲学。
