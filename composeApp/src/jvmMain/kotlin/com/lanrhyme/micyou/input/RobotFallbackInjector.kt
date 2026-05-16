package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.MouseButton
import com.lanrhyme.micyou.ModifierMask
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * java.awt.Robot 兜底实现。所有调用经单线程执行器序列化以避免事件交错。
 * 不支持 Unicode 物理输入：[typeUnicode] 遇到非 ASCII 字符会抛
 * [UnsupportedOperationException]。Windows 上请使用 WindowsSendInputInjector。
 */
internal class RobotFallbackInjector(
    private val adapter: RobotAdapter = AwtRobotAdapter(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MicYou-Input-Robot").apply { isDaemon = true }
    }
) : InputInjector {

    override fun mouseMoveRelative(dx: Int, dy: Int) {
        executor.execute {
            val (x, y) = adapter.pointerLocation()
            adapter.mouseMoveAbsolute(x + dx, y + dy)
        }
    }

    override fun mousePress(button: Int) {
        val mask = button.toAwtMask() ?: return
        executor.execute { adapter.mousePress(mask) }
    }

    override fun mouseRelease(button: Int) {
        val mask = button.toAwtMask() ?: return
        executor.execute { adapter.mouseRelease(mask) }
    }

    override fun wheel(delta: Int) {
        // Robot.mouseWheel 接收 notch 数（每 120 = 1 notch，正数 = 向下滚）
        val clamped = (delta / WHEEL_NOTCH).coerceIn(-MAX_NOTCHES, MAX_NOTCHES)
        if (clamped == 0) return
        executor.execute { adapter.mouseWheel(clamped) }
    }

    override fun keyPress(vk: Int, modifiers: Int) {
        executor.execute {
            modifiersOrder(modifiers).forEach { adapter.keyPress(it) }
            adapter.keyPress(vk)
        }
    }

    override fun keyRelease(vk: Int, modifiers: Int) {
        executor.execute {
            adapter.keyRelease(vk)
            modifiersOrder(modifiers).reversed().forEach { adapter.keyRelease(it) }
        }
    }

    override fun typeUnicode(text: String) {
        if (text.any { it.code !in 0x20..0x7E }) {
            throw UnsupportedOperationException(
                "RobotFallbackInjector cannot type non-ASCII text; use WindowsSendInputInjector"
            )
        }
        executor.execute {
            for (c in text) {
                val vk = asciiToVk(c) ?: continue
                val needShift = c.isUpperCase() || c in SHIFTED_PUNCT
                if (needShift) adapter.keyPress(KeyEvent.VK_SHIFT)
                adapter.keyPress(vk)
                adapter.keyRelease(vk)
                if (needShift) adapter.keyRelease(KeyEvent.VK_SHIFT)
            }
        }
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    private fun Int.toAwtMask(): Int? = when (this) {
        MouseButton.LEFT -> InputEvent.BUTTON1_DOWN_MASK
        MouseButton.RIGHT -> InputEvent.BUTTON3_DOWN_MASK
        MouseButton.MIDDLE -> InputEvent.BUTTON2_DOWN_MASK
        else -> null
    }

    private fun modifiersOrder(mask: Int): List<Int> = buildList {
        if (mask and ModifierMask.CTRL != 0) add(KeyEvent.VK_CONTROL)
        if (mask and ModifierMask.ALT != 0) add(KeyEvent.VK_ALT)
        if (mask and ModifierMask.SHIFT != 0) add(KeyEvent.VK_SHIFT)
        if (mask and ModifierMask.META != 0) add(KeyEvent.VK_WINDOWS)
    }

    private fun asciiToVk(c: Char): Int? {
        val upper = c.uppercaseChar()
        return when (upper) {
            in 'A'..'Z' -> KeyEvent.VK_A + (upper - 'A')
            in '0'..'9' -> KeyEvent.VK_0 + (upper - '0')
            ' ' -> KeyEvent.VK_SPACE
            '\n' -> KeyEvent.VK_ENTER
            '\t' -> KeyEvent.VK_TAB
            '.' -> KeyEvent.VK_PERIOD
            ',' -> KeyEvent.VK_COMMA
            '-' -> KeyEvent.VK_MINUS
            '=' -> KeyEvent.VK_EQUALS
            ';' -> KeyEvent.VK_SEMICOLON
            '/' -> KeyEvent.VK_SLASH
            else -> null
        }
    }

    companion object {
        private const val WHEEL_NOTCH = 120
        private const val MAX_NOTCHES = 100
        private val SHIFTED_PUNCT = setOf('!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '+', ':', '<', '>', '?')
    }
}
