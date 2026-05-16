# MicYou 键鼠扩展方案评估（Windows + Android）

> 作者：架构评审  
> 日期：2026-05-16  
> 适用版本：MicYou 1.3.1（commit 6abb1f5）  
> **范围限定**：桌面端仅 Windows，移动端仅 Android。macOS/Linux 不在本提案范围内（后续如需可作为独立 follow-up）。

## 一、背景与目标

MicYou 当前形态是「Android → PC 麦克风」的单向音频通道。本提案讨论在不破坏现有架构的前提下，**复用同一连接，扩展 Android 作为 Windows PC 的远程键鼠输入终端**：

- 远程触控板（鼠标移动、左右键、滚轮、双指滑动）
- 远程软键盘（物理键码 + 中文 IME 文本）
- 可选：媒体控制（音量/上下曲）、演示翻页、快捷键宏

典型场景：投影演示、躺沙发刷剧、临时缺键盘、HTPC 控制。

## 二、可行性评估

### 2.1 总体结论

| 维度 | 评级 | 说明 |
|---|---|---|
| **技术可行性** | ✅ 高 | Windows 上 `User32.SendInput` 通过 JNA 调用即可，能力完整 |
| **架构契合度** | ✅ 高 | 现有 `MessageWrapper`/Protobuf 协议天然可扩展 |
| **开发工作量** | 🟢 中下 | MVP 约 5–6 人日；完整版约 10–12 人日 |
| **运行时风险** | 🟢 低 | 不涉及 macOS 辅助功能授权、Wayland 等坑点 |
| **维护成本** | 🟢 低 | 与音频管线解耦，独立模块 |

只做 Windows 的最大好处：**没有平台权限弹窗**、**没有 Wayland 兼容问题**、**Unicode 文本注入有原生 API（`KEYEVENTF_UNICODE`）**，整体复杂度比多平台版本下降约 40%。

### 2.2 Windows 注入方式选型

| 方式 | 推荐 | 说明 |
|---|---|---|
| **JNA + `User32.SendInput`** | ✅ 首选 | 原生 Win32 API，支持鼠标/键盘/Unicode 文本，事件可被 UAC 之外的窗口接收 |
| `java.awt.Robot` | 🟡 备选 | JDK 自带，无外部依赖；但 **不支持 Unicode 字符直接输入**（中文需走剪贴板）、不支持高分屏物理坐标精确控制、被某些反作弊视为可疑 |
| `SendKeys` / PowerShell | ❌ | 进程开销大，不适合实时 |

**结论**：用 JNA 调用 `SendInput`，并保留 `Robot` 作为兜底实现（用于无 JNA 的精简打包）。JNA 已经在 Compose Desktop 生态成熟可用，不增加显著体积。

> 注：`SendInput` 对「以管理员权限运行的目标程序」无法注入（UIPI 限制）。需在文档中告知用户：**若想控制以管理员身份运行的窗口（如某些游戏/RegEdit），MicYou 桌面端也需以管理员身份启动**。

### 2.3 现有架构契合点

- **协议层**：`composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/Protocol.kt:128` 的 `MessageWrapper` 是 oneof 风格信封，加几个 `@ProtoNumber` 字段即可向后兼容。
- **传输层**：`NetworkServer` 已经区分 TCP（控制面）/UDP（音频数据面）。键鼠走 TCP，与 `MuteMessage`、`PluginSync` 同通道，复用 `hasControlMessage()` 判定。
- **平台抽象**：`Platform.kt` 的 `expect`/`actual` 模式可直接套用，新增 `expect class InputInjector` 即可（仅需 jvmMain 实际实现，androidMain 可空实现或反向不实现）。
- **ViewModel**：`MainViewModel` 是门面模式，新增 `RemoteInputViewModel` 与 `AudioStreamViewModel` 平级，零侵入。

## 三、实施方案

### 3.1 推荐方案：内置功能模块

直接作为应用内置能力，与音频共用连接。

#### 3.1.1 协议扩展

在 `Protocol.kt` 的 `MessageWrapper` 中追加：

```kotlin
@Serializable
data class MouseEventMessage(
    @ProtoNumber(1) val type: Int,        // 0=move(rel), 1=down, 2=up, 3=wheel, 4=move(abs)
    @ProtoNumber(2) val dx: Int = 0,      // 推荐相对位移，避免 DPI/多屏转换
    @ProtoNumber(3) val dy: Int = 0,
    @ProtoNumber(4) val button: Int = 0,  // 1=L, 2=R, 3=M
    @ProtoNumber(5) val wheelDelta: Int = 0
)

@Serializable
data class KeyEventMessage(
    @ProtoNumber(1) val type: Int,        // 0=down, 1=up, 2=text(unicode)
    @ProtoNumber(2) val keyCode: Int = 0, // Win VK_* 或跨平台中间码
    @ProtoNumber(3) val modifiers: Int = 0, // bitmask: Ctrl/Alt/Shift/Win
    @ProtoNumber(4) val text: String? = null // type=2 时使用，走 KEYEVENTF_UNICODE
)

@Serializable
data class InputAuthMessage(
    @ProtoNumber(1) val pin: String? = null,
    @ProtoNumber(2) val token: String? = null
)

data class MessageWrapper(
    // ... 现有字段
    @ProtoNumber(7) val mouse: MouseEventMessage? = null,
    @ProtoNumber(8) val key: KeyEventMessage? = null,
    @ProtoNumber(9) val inputAuth: InputAuthMessage? = null
)
```

并扩展 `hasControlMessage()` 把 `mouse`/`key`/`inputAuth` 归为控制消息，统一走 TCP。

#### 3.1.2 桌面端（Windows）注入

新增模块：

```
composeApp/src/jvmMain/kotlin/com/lanrhyme/micyou/input/
├── InputInjector.kt              # 接口
├── WindowsSendInputInjector.kt   # JNA 实现（首选）
├── RobotFallbackInjector.kt      # java.awt.Robot 兜底
└── InputInjectorFactory.kt       # 启动时检测，优先 JNA
```

`InputInjector` 关键 API：

```kotlin
interface InputInjector {
    fun mouseMoveRelative(dx: Int, dy: Int)
    fun mousePress(button: Int)
    fun mouseRelease(button: Int)
    fun wheel(delta: Int)
    fun keyPress(vk: Int, modifiers: Int)
    fun keyRelease(vk: Int, modifiers: Int)
    fun typeUnicode(text: String)   // SendInput + KEYEVENTF_UNICODE，逐字符
}
```

实现要点：
- JNA 直接绑定 `user32.dll` 的 `SendInput`、`INPUT`、`MOUSEINPUT`、`KEYBDINPUT` 结构。
- 鼠标移动用 `MOUSEEVENTF_MOVE`（相对）；如要支持指定屏幕位置，使用 `MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK` 配合 0–65535 归一化坐标。
- 中文/Emoji 输入直接 `KEYEVENTF_UNICODE` 逐 `WORD` 发送，**无需剪贴板**。
- 单线程执行器序列化注入，1ms 节流避免事件丢失。
- 启动自检：调用一次空 `SendInput` 验证，失败则记录降级到 `Robot`。

#### 3.1.3 Android 端 UI

新增页面 `MobileRemoteControl.kt`，设计与现有 Material 3 风格一致：

- **触控板区**：全屏 Surface，`pointerInput` 收集 `Drag`/`Tap`/`DoubleTap`/`scroll`，量化为 `MouseEventMessage`。建议 90 Hz 上报上限。
- **软键盘区**：弹出系统 IME，使用 `BasicTextField` 但拦截 `onValueChange` 把每次差分文本作为 `text` 字段发送。中文/Emoji 直接通过 `typeUnicode` 路径送达 Windows，无需任何 hack。
- **物理键映射**：媒体键、Esc、F1–F12、方向键、Win 键通过自定义按钮映射到 Win VK code（在 commonMain 维护一个跨平台键码表）。
- **手势映射**：
  - 单指拖拽 = 鼠标移动
  - 单击 = 左键
  - 双击 = 双击左键
  - 双击拖拽 = 按住左键拖动
  - 双指滑动 = 滚轮
  - 长按 = 右键
  - 三指点击 = 中键

#### 3.1.4 设置项

桌面端 `DesktopSettings.kt` 增加：
- 启用远程键鼠开关（默认关闭）
- 鼠标灵敏度倍率（0.5x–3.0x）
- 自然滚动方向
- 配对设备列表（管理已信任设备）

移动端 `MobileSettingsContent.kt` 增加：
- 触控板灵敏度
- 是否启用震动反馈
- 自定义按键面板配置

### 3.2 安全设计

远程键鼠 = 完整远控能力，必须比音频通道更严格：

1. **服务端开关默认关闭**，必须用户主动到设置勾选「允许远程键鼠」。
2. **首次配对码**：6 位一次性 PIN，桌面端弹窗显示，移动端输入；通过后下发短期 token 存到 `Settings`。
3. **TLS**：复用现有 `SelfSignedCertificate.kt`，键鼠消息必须在 TLS 之上。
4. **频率限制**：服务端单连接消息上限 200 msg/s，超限断开。
5. **托盘明示**：连接期间桌面端托盘图标变色 + 加红点；提供「立即断开远控」一键操作。
6. **快捷强停**：全局热键 `Ctrl+Alt+Pause`（可改）立即终止注入并断开连接。
7. **审计日志**：通过 `Logger` 记录配对、连接、断开、异常事件，可在设置中查看最近 N 条。
8. **管理员权限提示**：检测到 MicYou 自身非管理员、但用户可能要操作管理员窗口时，弹出说明（不强制提权）。

### 3.3 性能预算

| 指标 | 目标 |
|---|---|
| 端到端延迟（Wi-Fi 5GHz 同子网） | < 20 ms |
| 端到端延迟（USB ADB） | < 10 ms |
| 移动端事件采样率 | 90–120 Hz |
| 上行带宽 | < 20 KB/s（远低于音频通道） |
| 桌面端 SendInput 吞吐 | ≥ 1000 events/s |

## 四、工作量拆解（MVP）

| # | 任务 | 估算 |
|---|---|---|
| 1 | Protocol 扩展（Mouse/Key/InputAuth） | 0.5d |
| 2 | JNA 依赖引入 + `WindowsSendInputInjector` | 1.5d |
| 3 | `RobotFallbackInjector` 兜底 | 0.5d |
| 4 | `RemoteInputHandler` 接入 `ConnectionHandler` | 0.5d |
| 5 | Android 触控板 UI + 手势识别 | 1.5d |
| 6 | Android 软键盘 + 文本/物理键分流 | 1d |
| 7 | 配对码握手 + 设置项 + 安全开关 | 1d |
| 8 | i18n（en/zh-CN/zh-TW 等 6 套） | 0.5d |
| 9 | FAQ / 截图 / 用户文档 | 0.5d |
| **合计** | | **≈ 7.5 人日** |

> 完整版（按键宏录制、配置导入导出、自动化测试、屏幕角标提示）追加约 4–5 人日。

## 五、风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 用户期望注入到管理员/受 UIPI 保护的窗口 | 中 | FAQ 说明「需以管理员身份启动 MicYou」 |
| 中文/Emoji 输入失败 | 中 | 用 `KEYEVENTF_UNICODE`，每字符独立 SendInput；测试 emoji 代理对 |
| 远控被恶意利用 | 高 | 配对码 + TLS + 默认关闭 + 托盘明示 + 一键断开 + 速率限制 |
| 多屏/HiDPI 下绝对坐标偏移 | 低 | 默认使用相对位移；绝对坐标用归一化 0–65535 + VIRTUALDESK 标志 |
| 与音频共用 TCP 通道时阻塞 | 低 | 控制消息体积极小（<200B），实测无影响；必要时再开独立 socket |
| 反病毒/反作弊误报 | 中 | 在产品页明示功能；签名安装包；JNA 调用走标准 API |

## 六、上线建议

1. **1.4.0**：作为 Experimental 上线，默认关闭，菜单加 Beta tag。
2. **1.5.0**：转为 GA，补齐宏录制与设置导入导出。
3. **README/官网 Features**：新增「Remote Input (Windows)」小节，明确平台范围与默认禁用。
4. **隐私声明**：在 Settings 中明确告知「远程输入仅在用户授权与启用后生效」。

## 七、决策记录（2026-05-16 已敲定）

| # | 问题 | 决策 |
|---|---|---|
| 1 | 首期范围 | ✅ **触控板 + 软键盘 同步上**（实现按 PR 步骤分阶段） |
| 2 | 是否引入 JNA | ✅ **是**，使用 `net.java.dev.jna:jna-platform` 提供完整能力（Unicode 物理输入、SendInput） |
| 3 | 桌面端「正在被远程控制」明示 | ✅ **是**，托盘红点 + 任务栏角标 + 浮动提示窗（小，可关闭） |
| 4 | 双因子安全（PIN + 设备绑定） | ✅ **是**，配对码握手成功后绑定设备指纹（Android `ANDROID_ID` 哈希），换设备需重新配对 |
| 5 | macOS / Linux 路线 | ⏸️ **首期不做，但接口需保留扩展性**：`InputInjector` 必须是 OS-agnostic 接口；`WindowsSendInputInjector` 只是 actual 之一；后续 PR 添加 macOS/Linux actual 时不应改 commonMain 与 androidMain 任何代码 |

---

**附：相关代码位置（实施时索引）**

| 模块 | 文件 |
|---|---|
| 协议定义 | `composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/Protocol.kt` |
| 桌面 TCP 处理 | `composeApp/src/jvmMain/kotlin/com/lanrhyme/micyou/network/ConnectionHandler.kt` |
| 桌面 UDP 处理 | `composeApp/src/jvmMain/kotlin/com/lanrhyme/micyou/network/UdpConnectionHandler.kt` |
| 平台抽象 | `composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/Platform.kt` |
| Android 入口 | `composeApp/src/androidMain/kotlin/com/lanrhyme/micyou/MainActivity.kt` |
| 设置门面 | `composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/SettingsViewModel.kt` |
| 主 ViewModel | `composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/MainViewModel.kt` |
