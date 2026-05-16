# MicYou 键鼠扩展 —— 实施拆分与验收计划

> 配套文档：`docs/KEYBOARD_MOUSE_PROPOSAL.md`  
> 范围：Windows 桌面端 + Android 移动端（macOS/Linux 暂不实现，但接口预留）  
> 提交策略：每个 PR 自包含、可独立 revert，主分支始终可发版

## 锁定的设计决策（来自 2026-05-16 评审）

1. **首期同时交付触控板 + 软键盘**（PR #7、#8 都属于首期 MVP，不可砍）。
2. **引入 JNA**：`net.java.dev.jna:jna-platform` 5.14.0，仅 jvmMain 依赖。
3. **桌面端必须有「正在被远程控制」明示**：托盘红点 + 浮动提示窗 + 任务栏角标，并入 PR #9。
4. **安全采用双因子**：PIN 配对 + 设备指纹绑定，并入 PR #9。
5. **跨平台扩展性约束**：`InputInjector` 是接口，commonMain 与 androidMain 不允许出现「Windows」字样。后续如要加 macOS/Linux，只新增 jvmMain 下的 actual 实现 + Factory 分支。

## 总览

| # | PR 标题 | 主要目录 | 估时 | 阻塞下游 |
|---|---|---|---|---|
| 1 | chore(test): 接入 commonTest/jvmTest 基础设施 | `composeApp/src/*Test/`、`build.gradle.kts` | 0.5d | 全部 |
| 2 | feat(protocol): 扩展 MessageWrapper 增加 mouse/key/inputAuth | `commonMain/Protocol.kt` | 0.5d | 3、5、7 |
| 3 | feat(input): 引入 InputInjector 抽象与 RobotFallbackInjector | `jvmMain/input/` | 0.5d | 4、5 |
| 4 | feat(input): WindowsSendInputInjector（JNA + SendInput） | `jvmMain/input/`、`gradle/libs.versions.toml` | 1.5d | 5 |
| 5 | feat(network): RemoteInputHandler 接入 ConnectionHandler | `jvmMain/network/`、`jvmMain/input/` | 0.5d | 7 |
| 6 | feat(android): 远程键鼠 ViewModel + 协议发送通道 | `commonMain/`、`androidMain/` | 1d | 7、8 |
| 7 | feat(android): 触控板 UI + 手势识别 | `commonMain/MobileRemoteControl.kt` | 1.5d | — |
| 8 | feat(android): 软键盘 + 物理键面板 + 文本/键码分流 | `commonMain/MobileRemoteControl.kt` | 1d | — |
| 9 | feat(security): 配对码握手、TLS 校验、速率限制、托盘明示、强停热键 | 多模块 | 1.5d | 全部上线前 |

合计 ≈ **8.5 人日**。建议按此顺序串行；2 和 3 可并行，6/7/8 在 5 之后可并行（不同人各取一）。

---

## PR #1 — 接入测试基础设施

### 目标
项目目前 `commonTest` 已声明 `kotlin.test`，但仓库无任何测试文件。本 PR 落地最小测试骨架，让后续 PR 可以「带测试合入」。

### 改动
- 新增目录：
  - `composeApp/src/commonTest/kotlin/com/lanrhyme/micyou/`
  - `composeApp/src/jvmTest/kotlin/com/lanrhyme/micyou/`
- `composeApp/build.gradle.kts`：在 `sourceSets` 内新增 `jvmTest.dependencies { implementation(libs.kotlin.test) }`、`implementation(libs.kotlinx.coroutines.test)`。
- `gradle/libs.versions.toml`：新增 `kotlinx-coroutines-test`（与既有协程版本对齐）。
- 新增 smoke 测试 `SmokeTest.kt`：仅断言 `1 + 1 == 2`，验证 runner 接通。
- 在根 `build.gradle.kts` 不需改动（`./gradlew check` 已自动汇总）。

### 单元测试
| 测试文件 | 覆盖目标 | 关键断言 |
|---|---|---|
| `commonTest/.../SmokeTest.kt` | 验证 commonTest runner | `assertEquals(2, 1 + 1)` |
| `jvmTest/.../SmokeTest.kt` | 验证 jvmTest runner | `assertEquals(2, 1 + 1)` |

### 验收 checklist
- [ ] `./gradlew :composeApp:allTests` 在本地与 CI（如有）均通过。
- [ ] `./gradlew :composeApp:check` 通过。
- [ ] `./gradlew checkLocalization` 仍通过（pre-commit hook）。
- [ ] 不影响 `./gradlew :composeApp:assembleDebug` 与 `:composeApp:jvmRun`。

### 回滚
直接 revert，无运行时影响。

---

## PR #2 — 协议扩展

### 目标
在 `MessageWrapper` 上增加 `mouse`/`key`/`inputAuth` 三个 oneof 字段，**严格保持 protobuf 编号不冲突、向后兼容**。

### 改动
- `commonMain/Protocol.kt`：
  - 新增 `MouseEventMessage`、`KeyEventMessage`、`InputAuthMessage` 三个 `@Serializable data class`
  - 在 `MessageWrapper` 追加 `@ProtoNumber(7) mouse`、`@ProtoNumber(8) key`、`@ProtoNumber(9) inputAuth`
  - 扩展 `hasControlMessage()` 把 `mouse`/`key`/`inputAuth` 算作控制消息（必须走 TCP）
  - 定义常量：`MouseEventType`、`KeyEventType`、`MouseButton`、`ModifierMask`（写成 object 常量，不引枚举，便于 Protobuf 整数序列化）

### 单元测试（`commonTest/.../ProtocolTest.kt`）
| 测试名 | 断言 |
|---|---|
| `mouseMessage_roundTrip_preservesAllFields` | 序列化/反序列化后字段一致 |
| `keyMessage_unicodeText_roundTrip` | 中文 + Emoji 文本字段不丢字节 |
| `messageWrapper_oldClient_canDecodeNewMessage_withoutMouseField` | **关键向后兼容**：用只含旧字段（1–6）的 wrapper 编码，新 schema 解码后 `mouse == null` |
| `messageWrapper_newClient_canDecodeOldServerWrapper` | 反向：新字段缺省时正常解析 |
| `hasControlMessage_returnsTrueForMouse_andKey_andInputAuth` | 控制消息判定正确 |
| `hasControlMessage_returnsFalseForAudioOnly` | 音频包仍走数据面 |

> 实现技巧：用 `ProtoBuf.encodeToByteArray` 生成 bytes，再 `ProtoBuf.decodeFromByteArray` 验证，覆盖 wire format 兼容性而不是 in-memory 类拷贝。

### 验收 checklist
- [ ] `./gradlew :composeApp:allTests` 通过。
- [ ] 用 1.3.1 旧 Android 客户端连接新桌面服务端，**音频仍正常工作**（人工验收，最小回归集）。
- [ ] 用新 Android 客户端连接 1.3.1 旧桌面服务端，**音频仍正常工作**（旧端会忽略未知字段 7/8/9）。
- [ ] grep 确认 `MessageWrapper` 的所有现有读路径都默认处理了新字段为 null。

### 回滚
直接 revert；新字段未被任何运行路径消费，零影响。

---

## PR #3 — InputInjector 抽象 + Robot 兜底

### 目标
建立桌面端注入抽象，先上 `Robot` 兜底实现，便于在没有 JNA 的环境下也能基本工作（不能输入 Unicode，但鼠标/ASCII 键能用）。

### 改动
- `jvmMain/input/InputInjector.kt`：定义 `interface InputInjector`（API 见提案 3.1.2）
- `jvmMain/input/RobotFallbackInjector.kt`：基于 `java.awt.Robot` 实现
  - 单线程 `Executors.newSingleThreadExecutor` 序列化所有调用
  - `mouseMoveRelative` 用 `MouseInfo.getPointerInfo().location` + `mouseMove`
  - `typeUnicode` 仅支持 ASCII；非 ASCII 抛 `UnsupportedOperationException`（PR #4 用 SendInput 覆盖）
- `jvmMain/input/InputInjectorFactory.kt`：暂时硬编码返回 `RobotFallbackInjector`（PR #4 改为优先 JNA）
- 不接入任何调用方，纯能力层。

### 单元测试（`jvmTest/.../RobotFallbackInjectorTest.kt`）
| 测试名 | 策略 |
|---|---|
| `mouseMoveRelative_serializesEvents_onSingleThread` | mock 一个 `Robot` 双向探针，并行 100 个调用，断言执行顺序唯一且单线程 |
| `typeUnicode_throwsOnNonAscii` | 输入 `"中文"` 抛 `UnsupportedOperationException` |
| `typeUnicode_typesAsciiCharByChar` | 输入 `"abc"` 触发 3 次 `keyPress`/`keyRelease` 序列 |
| `wheel_clampsExtremeDelta` | 输入 `Int.MAX_VALUE` 不抛，被裁剪到合理范围 |

> 不能用真 `Robot` 跑 CI（headless），通过把 Robot 操作抽到一个内部 `RobotAdapter` 接口、测试时注入 fake 实现。

### 验收 checklist
- [ ] `./gradlew :composeApp:jvmTest` 通过。
- [ ] 桌面端启动后日志显示 `InputInjector loaded: RobotFallbackInjector`。
- [ ] 没有任何用户可见行为变化（未接调用方）。

### 回滚
删除 `input/` 目录即可，零运行时影响。

---

## PR #4 — Windows SendInput 主实现

### 目标
落地 `WindowsSendInputInjector`，覆盖 Robot 的 Unicode 短板，并把 Factory 切换为「Windows 优先 JNA、其它/失败回退 Robot」。

### 改动
- `gradle/libs.versions.toml`：新增 `jna = "5.14.0"`，对应 `net.java.dev.jna:jna-platform`（已含 User32 绑定）
- `composeApp/build.gradle.kts` 的 `jvmMain.dependencies`：`implementation(libs.jna.platform)`
- `jvmMain/input/WindowsSendInputInjector.kt`：
  - 使用 `com.sun.jna.platform.win32.User32`、`WinUser.INPUT`、`WinUser.MOUSEINPUT`、`WinUser.KEYBDINPUT`
  - `mouseMoveRelative` 走 `MOUSEEVENTF_MOVE`
  - `typeUnicode` 走 `KEYEVENTF_UNICODE`，逐 `WORD` 发送（处理代理对：高低代理对必须连发，且不带 keyCode）
  - 启动自检：调用一次 `SendInput(0, null, ...)` 探测，失败抛初始化异常
- `jvmMain/input/InputInjectorFactory.kt`：
  - `if (Platform.isWindows()) try { WindowsSendInputInjector() } catch { RobotFallbackInjector() } else RobotFallbackInjector()`
  - 通过 `Logger.i` 记录最终选择

### 单元测试（`jvmTest/.../WindowsSendInputInjectorTest.kt`）
非 Windows 环境跳过：用 `assumeTrue(System.getProperty("os.name").startsWith("Windows"))`。

| 测试名 | 策略 |
|---|---|
| `factory_returnsWindowsImpl_onWindows` | 仅 Windows 跑，断言 instance type |
| `factory_returnsRobotFallback_onNonWindows` | 仅非 Windows 跑 |
| `typeUnicode_buildsCorrectInputStruct_forBmpChar` | 把 SendInput 抽象到 `User32Adapter` 接口，注入 fake，断言生成的 `INPUT` 数组 `wScan == 'A'.code`、`dwFlags & KEYEVENTF_UNICODE != 0` |
| `typeUnicode_emitsSurrogatePairAsTwoInputs` | 输入 `"😀"` 生成 2 个 INPUT（高低代理对） |
| `mouseWheel_usesWheelDelta_120Multiple` | 滚动 1 单位 → `mouseData == 120` |
| `keyPress_setsScanCodeAndModifiers` | Ctrl+C 生成 4 个 INPUT（Ctrl down, C down, C up, Ctrl up） |

### 验收 checklist
- [ ] Windows 物理机：连接 Java fake client（写一个 main 函数）发送 mouse move/click → 真实光标移动。
- [ ] 在记事本中通过 fake client 发送 `typeUnicode("Hello 你好 😀")` → 文本完整出现。
- [ ] 任务管理器观察 MicYou 进程，无异常 CPU 飙高。
- [ ] Linux/macOS 启动桌面端，日志显示降级到 `RobotFallbackInjector`，无崩溃。
- [ ] 反病毒（Defender、火绒）扫描安装包不报毒。

### 回滚
- 单 PR revert；如已发版，发布 hotfix 把 Factory 改为强制 `RobotFallbackInjector`。

---

## PR #5 — RemoteInputHandler 接入 ConnectionHandler

### 目标
让 TCP 连接收到 `mouse`/`key` 消息时调用 `InputInjector`。这是把前面三个 PR 串起来的关键一刀。

### 改动
- `jvmMain/network/RemoteInputHandler.kt`：新建
  - 持有 `InputInjector` 实例
  - `handle(message: MessageWrapper)`：根据 `mouse`/`key` 字段分发
  - 内置速率限制（令牌桶，200 msg/s/conn），超限丢弃并 `Logger.w`
  - 内置「远程输入未启用」短路开关（读 `Settings.remoteInputEnabled`）
- `jvmMain/network/ConnectionHandler.kt`：
  - 在收到 `MessageWrapper` 后，先判断 `mouse != null || key != null` 委托给 `RemoteInputHandler`
  - 不影响音频/Mute/Plugin/Ping 既有路径
- `commonMain/Settings.kt`：增加 `remoteInputEnabled: Boolean = false`、`remoteInputMouseSensitivity: Float = 1.0f`
- `commonMain/SettingsViewModel.kt`：暴露 setter

### 单元测试（`jvmTest/.../RemoteInputHandlerTest.kt`）
| 测试名 | 策略 |
|---|---|
| `handle_dropsMouseEvent_whenRemoteInputDisabled` | settings.enabled=false，注入器收到 0 调用 |
| `handle_dispatchesMouseMove_whenEnabled` | enabled=true，断言 `mouseMoveRelative(dx, dy)` 被调用 1 次 |
| `handle_appliesSensitivityScaling` | sensitivity=2.0，dx=10 → 实际注入 dx=20 |
| `handle_rateLimits_above200msgPerSecond` | 1 秒内灌 1000 条，被注入的 ≤ 220（含突发） |
| `handle_doesNotInterfereWithAudioMessages` | 含 audioPacket 的 wrapper 不会被路由到 InputHandler |

### 验收 checklist
- [ ] 单元测试全绿。
- [ ] 桌面端启用「允许远程键鼠」开关，用 fake TCP client 发送 `MouseEventMessage(type=move, dx=50, dy=0)` → 光标右移 50px。
- [ ] 关闭开关后再发，光标不动。
- [ ] 同时跑音频流 + 键鼠注入，**音频不卡顿**（人工监听 30s）。
- [ ] 速率限制：fake client 暴力发 10000/s，桌面端日志出现 `rate limit exceeded`，进程不崩溃。

### 回滚
revert 即可；`Settings` 新字段保留无影响（默认 false）。

---

## PR #6 — Android 端：远程键鼠 ViewModel + 发送通道

### 目标
打通 Android 端的发送侧，独立于 UI 先做好「能把 MouseEventMessage 通过现有 socket 发出去」。

### 改动
- `commonMain/RemoteInputViewModel.kt`：
  - 持有对当前 `AudioStreamViewModel` 的连接句柄（注入或经 `MainViewModel` 提供）
  - `sendMouseMove(dx, dy)`、`sendMouseButton(...)`、`sendWheel(...)`、`sendKeyEvent(...)`、`sendUnicodeText(...)`
  - 内部协程合批：16ms 节流（约 60 Hz），**仅对 mouseMove 合并**，按键事件立即发送
  - StateFlow `connectionState`（连接 / 断开 / 未启用）
- `commonMain/MainViewModel.kt`：在门面层暴露 `remoteInput: RemoteInputViewModel`
- `androidMain/MainActivity.kt`：无需改动（UI 在 PR #7 接入）

### 单元测试（`commonTest/.../RemoteInputViewModelTest.kt`，使用 `kotlinx.coroutines.test`）
| 测试名 | 策略 |
|---|---|
| `sendMouseMove_coalescesWithin16ms` | 1ms 内连发 5 次 (1,0)，最终上行只有 1 个 (5,0) |
| `sendMouseButton_isImmediate_notCoalesced` | 按下立即发，不与移动合并 |
| `sendUnicodeText_routedAsKeyEventTextType` | 生成 KeyEventMessage(type=2, text=...) |
| `noSend_whenDisconnected` | connectionState=Disconnected 时所有 send 走 no-op，断言 transport 0 调用 |
| `coalescing_resetsAfterFlush` | 16ms 后再发新批，断言上行第二批不丢字 |

### 验收 checklist
- [ ] 单元测试全绿。
- [ ] 用 Android Studio 连接调试，断点 `sendMouseMove` 触发，确认 protobuf bytes 已写入 socket。
- [ ] 桌面端 PR #5 的 RemoteInputHandler 收到事件计数。

---

## PR #7 — Android 触控板 UI + 手势识别

### 目标
落地触控板交互。

### 改动
- `commonMain/MobileRemoteControl.kt`：
  - 顶层 `RemoteControlScreen` Composable，路由 `MainViewModel.remoteInput`
  - 触控板 Surface：`pointerInput` 收集 `awaitPointerEventScope`，自实现：
    - 单指 drag → `sendMouseMove`
    - 短点 < 150ms 且位移 < 8dp → 左键单击
    - 短双点 → 双击左键
    - 长按 ≥ 500ms → 右键
    - 双指竖滑 → 滚轮（dy 累积，每 120 像素 = 1 wheel notch）
    - 三指点击 → 中键
  - 顶部状态栏：连接状态、灵敏度滑条、设置入口
- `commonMain/MobileHome.kt`：增加跳转入口（连接状态为已连接时显示按钮）

### 单元测试（`commonTest/.../GestureRecognizerTest.kt`）
将手势识别逻辑抽到 pure Kotlin 类 `GestureRecognizer`，与 Compose UI 解耦，便于无 UI 测试。

| 测试名 | 策略 |
|---|---|
| `singleTap_within150ms_emitsLeftClick` | 模拟 down→up，dt=100ms，dist=2dp |
| `longPress_500ms_emitsRightClick` | down，hold 500ms，up |
| `dragOver8dp_emitsMouseMove_notTap` | down，move 20dp，up → 期望 move 事件 ≥ 1，无 click |
| `doubleTap_emitsDoubleLeftClick` | 两次 tap 间隔 200ms |
| `twoFingerVerticalSwipe_emitsWheel` | 双指 down，同向 dy=240 → 2 个 wheel notch |
| `threeFingerTap_emitsMiddleClick` | 三指同时 down → up |

### 人工验收 checklist
- [ ] APK 安装到 Android 设备，连接到 Windows 桌面端。
- [ ] 单指拖：桌面光标流畅跟随，主观无明显卡顿（< 25ms）。
- [ ] 单击 → 左键；双击 → 双击文件可打开。
- [ ] 长按 → 右键菜单弹出。
- [ ] 双指上下滑 → 浏览器滚动。
- [ ] 三指点击 → 中键关闭浏览器 tab。
- [ ] 切换灵敏度滑条，立刻生效。

---

## PR #8 — Android 软键盘 + 物理键面板

### 目标
覆盖文本输入与组合键。

### 改动
- `commonMain/MobileRemoteControl.kt`：
  - 「键盘」抽屉：拉起后展示一个隐藏的 `BasicTextField`，自动获焦，弹起系统 IME
  - `onValueChange` 与上次值做差分：
    - 净增加文本 → `sendUnicodeText(added)`
    - 净删除 → `sendKeyEvent(VK_BACK, count)`
    - 不做光标位置同步（Windows 端文本插入到目标焦点，依目标应用而定）
  - 物理键面板：网格按钮（Esc、Tab、F1–F12、方向键、Win、Ctrl/Shift/Alt 粘性修饰、PgUp/PgDn/Home/End、PrintScreen）
- `commonMain/KeyCodeTable.kt`：跨平台键码常量（命名空间用 Win VK）

### 单元测试
| 测试文件 | 测试名 |
|---|---|
| `commonTest/.../TextDiffTest.kt` | `appendingChar_emitsTypeText`、`backspace_emitsBackKey`、`replaceMiddle_emitsBackThenInsert`、`unicodeEmoji_remainsAsSingleText` |
| `commonTest/.../KeyComboTest.kt` | `ctrlPlusC_sendsCtrlDown_CDown_CUp_CtrlUp`、`stickyShift_holdsAcrossNextKey_thenReleases` |

### 人工验收 checklist
- [ ] 在 Windows 记事本输入 `Hello 你好 😀` → 完整显示。
- [ ] 退格键删除多个 Emoji，目标 app 显示同步删除。
- [ ] `Ctrl+C` / `Ctrl+V` 在浏览器地址栏复制粘贴生效。
- [ ] `Alt+Tab` 切换窗口（粘性 Alt + Tab 单击）。
- [ ] `Win + D` 显示桌面。
- [ ] F11 全屏切换。

---

## PR #9 — 安全护栏与上线 gating

### 目标
不开此 PR 不允许把功能默认开启对外发版。**双因子（PIN + 设备绑定）+ 显著明示 + kill switch 一次到位**。

### 改动
- **双因子配对**：
  - 桌面端：首次客户端连接键鼠通道时弹出 6 位 PIN，客户端必须发 `InputAuthMessage(pin=..., deviceFingerprint=...)`
  - **设备指纹**：Android 端用 `Settings.Secure.ANDROID_ID` + 应用安装签名做 SHA-256 哈希，避免裸 ID 泄露
  - 通过后下发短期 token（24h）+ 持久化「已信任设备」记录（fingerprint → 备注名 → 首次配对时间）
  - 换设备 / 重装 APP / 清除应用数据 → fingerprint 变化 → 重新走 PIN
  - 桌面端「设置」内可查看与撤销已信任设备
- **TLS 强制**：在 `ConnectionHandler` 检测：若 `remoteInput=true` 但 socket 未走 TLS，断开连接
- **明示提示（三重）**：
  - 系统托盘图标：远控活跃时切换为带红点变体；hover tooltip 显示「正在被 <设备名> 远程控制」
  - 任务栏角标：使用 `Taskbar.getTaskbar().setIconBadge("●")`（JDK 9+）
  - 浮动提示窗：远控开始时右下角弹出半透明小窗（120×40），显示设备名 + 「断开」按钮，可关闭，5s 自动收起为悬浮小球
- **强停热键**：注册全局 `Ctrl+Alt+Pause`（JNA `User32.RegisterHotKey`），触发立即断开所有远程输入连接并撤销当前会话 token
- **审计日志**：`Logger.i` 记录 connect/disconnect/auth 失败/速率限制/手动断开
- **README + FAQ**：新增章节，明确「默认禁用、需 PIN、需设备绑定、TLS 强制、热键强停」

### 单元测试
| 测试文件 | 测试名 |
|---|---|
| `jvmTest/.../PairingHandshakeTest.kt` | `clientWithoutPin_isRejected`、`clientWithWrongPin_isRejectedAndLogged`、`pinLockout_after3Failures_lasts5min`、`clientWithValidPin_receivesToken`、`tokenExpiry_after24h_forcesRePairing`、`token_revoked_isRejectedImmediately` |
| `jvmTest/.../DeviceBindingTest.kt` | `unknownFingerprint_evenWithValidToken_isRejected`、`changedFingerprint_requiresNewPin`、`trustedDeviceList_persistsAcrossRestarts`、`revokeDevice_removesFromTrustList_andInvalidatesToken` |
| `jvmTest/.../TlsEnforcementTest.kt` | `plaintextConnection_rejectedWhenRemoteInputEnabled`、`tlsConnection_acceptedWhenRemoteInputEnabled` |
| `jvmTest/.../HotkeyKillSwitchTest.kt` | mock JNA hotkey 触发 → 所有 `RemoteInputHandler` 进入 disabled 态、当前 token 被撤销 |
| `jvmTest/.../IndicatorTest.kt` | `trayIcon_switchesToActive_whenRemoteInputBegins`、`trayIcon_revertsToIdle_whenAllSessionsClosed`、`floatingWindow_showsDeviceName`、`floatingWindow_clickDisconnect_revokesSession` |
| `jvmTest/.../RateLimitTest.kt`（如未在 PR #5 完整覆盖，补充） | 突发 + 长稳态两种模式 |

### 人工验收 checklist
- [ ] 默认安装后远程键鼠不可用，必须用户主动开启。
- [ ] 首次连接弹出 PIN，输错 3 次锁定 5 分钟，时间内即使 PIN 正确也拒绝。
- [ ] 用 A 设备成功配对后，**用 B 设备携带 A 的 token 连接 → 被拒绝**（设备指纹校验）。
- [ ] B 设备 PIN 通过后再连接 → 信任列表新增一条记录。
- [ ] 桌面端设置撤销 A 设备 → A 设备下次连接需重走 PIN，即使 token 未过期。
- [ ] 拔掉 TLS（构造非 TLS 客户端）尝试发送鼠标事件 → 桌面端拒绝并断开。
- [ ] 远控期间：托盘图标变红、任务栏角标出现、右下角浮动窗显示设备名。
- [ ] 浮动窗「断开」按钮、托盘菜单「立即断开远控」、`Ctrl+Alt+Pause` 三个入口都能立即停止注入。
- [ ] 卸载后重装，旧 token 不再被接受（Settings 确实清空，重装等于设备指纹重置）。
- [ ] 安全测试：用未配对设备扫描局域网尝试连接 → 被拒绝。
- [ ] 速率攻击：客户端发 10k events/s → 服务端日志报警，光标不会被狂跳，连接被断开。

### 回滚
在出现安全事件时，发布 hotfix 把 `remoteInputEnabled` 强制为 `false` 并禁用 UI 入口，等修复后再开。

---

## 跨 PR 的工程约定

1. **每个 PR 必须自带 changelog 一行**：写在 PR 描述的 `## Summary`，便于 release notes 整合。
2. **每个 PR 通过 `./gradlew check`**（含 `checkLocalization`、`allTests`）才允许合入。
3. **每次合入主分支前**：跑 `gitnexus_detect_changes()`，确认改动只触达预期符号。
4. **触达任何已存在符号前**：跑 `gitnexus_impact({target, direction: "upstream"})`，把 risk 与 d=1 callers 写在 PR 描述里。
5. **i18n**：PR #7、#8、#9 所有用户可见字符串必须同时改 `values/strings.xml` + `values-zh/strings.xml`，否则 pre-commit hook 阻断。
6. **不引入新的依赖目录**（`commonMain` 的 protobuf/序列化已足够）；唯一新增依赖是 `jna-platform`，仅用于 `jvmMain`。
7. **测试覆盖率门槛**（建议而非强制）：协议层与 InputInjector 抽象层 ≥ 80%；UI 手势识别纯逻辑部分 ≥ 70%。

## 风险性最高的 3 个 PR

1. **#4 WindowsSendInputInjector**：JNA 结构体内存布局错一字节就崩进程。要求 PR Reviewer 必须在真 Windows 上运行 fake client 验证才能合。
2. **#9 安全 PR**：握手与 TLS 强制如有漏洞 → 远控被劫持。建议邀请第二位 reviewer 专门审 auth 路径。
3. **#7/#8 手势识别**：误触发概率与用户体验直接相关，建议拉真人在多种屏幕尺寸（5"/6.7"/平板）上 dogfood 至少 3 天再发。
