package com.lanrhyme.micyou.input

/**
 * 平台无关的输入注入接口。Windows 优先实现使用 SendInput；非 Windows / 失败时退化到 Robot。
 * 后续接 macOS/Linux 时只需新增同包的实现，不应改 commonMain/androidMain。
 */
interface InputInjector {
    /** 相对位移鼠标光标。 */
    fun mouseMoveRelative(dx: Int, dy: Int)

    /** 按下鼠标按键。button 取自 [com.lanrhyme.micyou.MouseButton]。 */
    fun mousePress(button: Int)

    /** 释放鼠标按键。 */
    fun mouseRelease(button: Int)

    /** 滚轮事件。delta 为标准 wheel notch（120 = 一格）。 */
    fun wheel(delta: Int)

    /** 按下物理键。vk 为 Win32 VK_* 编号；modifiers 为 [com.lanrhyme.micyou.ModifierMask] bitmask。 */
    fun keyPress(vk: Int, modifiers: Int)

    /** 释放物理键。 */
    fun keyRelease(vk: Int, modifiers: Int)

    /**
     * 输入 Unicode 文本（包括 CJK / Emoji）。Windows 实现使用 KEYEVENTF_UNICODE，
     * Robot 兜底仅支持 ASCII，遇到非 ASCII 字符抛 [UnsupportedOperationException]。
     */
    fun typeUnicode(text: String)

    /** 释放底层资源（线程池等）。 */
    fun dispose() {}
}
