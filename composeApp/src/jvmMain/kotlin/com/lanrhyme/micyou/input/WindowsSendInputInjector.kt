package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.ModifierMask
import com.lanrhyme.micyou.MouseButton
import com.lanrhyme.micyou.input.Win32Constants.KEYEVENTF_KEYUP
import com.lanrhyme.micyou.input.Win32Constants.KEYEVENTF_UNICODE
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_LEFTDOWN
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_LEFTUP
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_MIDDLEDOWN
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_MIDDLEUP
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_MOVE
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_RIGHTDOWN
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_RIGHTUP
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_WHEEL
import com.lanrhyme.micyou.input.Win32Constants.VK_CONTROL
import com.lanrhyme.micyou.input.Win32Constants.VK_LWIN
import com.lanrhyme.micyou.input.Win32Constants.VK_MENU
import com.lanrhyme.micyou.input.Win32Constants.VK_SHIFT
import com.lanrhyme.micyou.input.Win32Constants.WHEEL_DELTA
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Windows 主实现。所有调用经单线程执行器序列化避免事件交错。
 * 支持 KEYEVENTF_UNICODE 直接注入 CJK / Emoji（含代理对）。
 */
internal class WindowsSendInputInjector(
    private val adapter: User32Adapter,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MicYou-Input-Win").apply { isDaemon = true }
    }
) : InputInjector {

    override fun mouseMoveRelative(dx: Int, dy: Int) {
        executor.execute {
            adapter.sendInput(listOf(InputRecord.Mouse(dx = dx, dy = dy, flags = MOUSEEVENTF_MOVE)))
        }
    }

    override fun mousePress(button: Int) {
        val flag = button.downFlag() ?: return
        executor.execute { adapter.sendInput(listOf(InputRecord.Mouse(flags = flag))) }
    }

    override fun mouseRelease(button: Int) {
        val flag = button.upFlag() ?: return
        executor.execute { adapter.sendInput(listOf(InputRecord.Mouse(flags = flag))) }
    }

    override fun wheel(delta: Int) {
        if (delta == 0) return
        executor.execute {
            adapter.sendInput(
                listOf(InputRecord.Mouse(mouseData = delta * WHEEL_DELTA, flags = MOUSEEVENTF_WHEEL))
            )
        }
    }

    override fun keyPress(vk: Int, modifiers: Int) {
        executor.execute {
            val records = mutableListOf<InputRecord>()
            modifierVks(modifiers).forEach { records += InputRecord.Keyboard(vk = it) }
            records += InputRecord.Keyboard(vk = vk)
            adapter.sendInput(records)
        }
    }

    override fun keyRelease(vk: Int, modifiers: Int) {
        executor.execute {
            val records = mutableListOf<InputRecord>()
            records += InputRecord.Keyboard(vk = vk, flags = KEYEVENTF_KEYUP)
            modifierVks(modifiers).reversed().forEach {
                records += InputRecord.Keyboard(vk = it, flags = KEYEVENTF_KEYUP)
            }
            adapter.sendInput(records)
        }
    }

    override fun typeUnicode(text: String) {
        if (text.isEmpty()) return
        executor.execute {
            val records = mutableListOf<InputRecord>()
            for (c in text) {
                records += InputRecord.Keyboard(scan = c.code, flags = KEYEVENTF_UNICODE)
                records += InputRecord.Keyboard(scan = c.code, flags = KEYEVENTF_UNICODE or KEYEVENTF_KEYUP)
            }
            adapter.sendInput(records)
        }
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    private fun Int.downFlag(): Int? = when (this) {
        MouseButton.LEFT -> MOUSEEVENTF_LEFTDOWN
        MouseButton.RIGHT -> MOUSEEVENTF_RIGHTDOWN
        MouseButton.MIDDLE -> MOUSEEVENTF_MIDDLEDOWN
        else -> null
    }

    private fun Int.upFlag(): Int? = when (this) {
        MouseButton.LEFT -> MOUSEEVENTF_LEFTUP
        MouseButton.RIGHT -> MOUSEEVENTF_RIGHTUP
        MouseButton.MIDDLE -> MOUSEEVENTF_MIDDLEUP
        else -> null
    }

    private fun modifierVks(mask: Int): List<Int> = buildList {
        if (mask and ModifierMask.CTRL != 0) add(VK_CONTROL)
        if (mask and ModifierMask.ALT != 0) add(VK_MENU)
        if (mask and ModifierMask.SHIFT != 0) add(VK_SHIFT)
        if (mask and ModifierMask.META != 0) add(VK_LWIN)
    }
}
