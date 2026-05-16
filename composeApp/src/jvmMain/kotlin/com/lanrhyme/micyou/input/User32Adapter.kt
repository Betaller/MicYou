package com.lanrhyme.micyou.input

/**
 * 把 SendInput 的副作用抽到接口层，便于在非 Windows / 测试环境注入 fake，
 * 直接断言生成的 INPUT 数组而不需要真的调用 user32.dll。
 */
internal interface User32Adapter {
    /** 返回成功注入的事件数。failure 时返回 0。 */
    fun sendInput(inputs: List<InputRecord>): Int
}

/** 平台无关的 INPUT 事件中间表示，方便测试断言。 */
internal sealed class InputRecord {
    data class Mouse(
        val dx: Int = 0,
        val dy: Int = 0,
        val mouseData: Int = 0,
        val flags: Int = 0
    ) : InputRecord()

    data class Keyboard(
        val vk: Int = 0,
        val scan: Int = 0,
        val flags: Int = 0
    ) : InputRecord()
}

/** Win32 常量集合，避免在多处复制。 */
internal object Win32Constants {
    const val MOUSEEVENTF_MOVE = 0x0001
    const val MOUSEEVENTF_LEFTDOWN = 0x0002
    const val MOUSEEVENTF_LEFTUP = 0x0004
    const val MOUSEEVENTF_RIGHTDOWN = 0x0008
    const val MOUSEEVENTF_RIGHTUP = 0x0010
    const val MOUSEEVENTF_MIDDLEDOWN = 0x0020
    const val MOUSEEVENTF_MIDDLEUP = 0x0040
    const val MOUSEEVENTF_WHEEL = 0x0800

    const val KEYEVENTF_EXTENDEDKEY = 0x0001
    const val KEYEVENTF_KEYUP = 0x0002
    const val KEYEVENTF_UNICODE = 0x0004
    const val KEYEVENTF_SCANCODE = 0x0008

    const val WHEEL_DELTA = 120

    // VK_*
    const val VK_SHIFT = 0x10
    const val VK_CONTROL = 0x11
    const val VK_MENU = 0x12 // Alt
    const val VK_LWIN = 0x5B
}
