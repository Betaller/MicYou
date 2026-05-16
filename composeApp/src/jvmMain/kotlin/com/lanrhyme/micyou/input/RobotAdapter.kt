package com.lanrhyme.micyou.input

/**
 * 内部适配层：把 java.awt.Robot 的副作用接口化，便于在无 head 环境下注入 fake 进行测试。
 * 真实实现见 [AwtRobotAdapter]；测试中传入 fake 即可断言事件序列。
 */
internal interface RobotAdapter {
    fun mouseMoveAbsolute(x: Int, y: Int)
    fun pointerLocation(): Pair<Int, Int>
    fun mousePress(buttons: Int)
    fun mouseRelease(buttons: Int)
    fun mouseWheel(notches: Int)
    fun keyPress(keycode: Int)
    fun keyRelease(keycode: Int)
}

internal class AwtRobotAdapter : RobotAdapter {
    private val robot = java.awt.Robot().also { it.isAutoWaitForIdle = false }

    override fun mouseMoveAbsolute(x: Int, y: Int) = robot.mouseMove(x, y)

    override fun pointerLocation(): Pair<Int, Int> {
        val info = java.awt.MouseInfo.getPointerInfo() ?: return 0 to 0
        val p = info.location
        return p.x to p.y
    }

    override fun mousePress(buttons: Int) = robot.mousePress(buttons)
    override fun mouseRelease(buttons: Int) = robot.mouseRelease(buttons)
    override fun mouseWheel(notches: Int) = robot.mouseWheel(notches)
    override fun keyPress(keycode: Int) = robot.keyPress(keycode)
    override fun keyRelease(keycode: Int) = robot.keyRelease(keycode)
}
