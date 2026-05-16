package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.ModifierMask

/**
 * 移动端"粘性修饰键"模型：用户先点 Ctrl/Shift/Alt/Win 按钮，状态保留到下一次按下普通键，
 * 普通键释放后修饰键自动释放（除非用户长按粘住）。本类只产出 down/up 序列，发送由调用方负责。
 */
class KeyComboTracker {
    private var sticky: Int = 0
    private var locked: Int = 0

    fun toggleSticky(modifier: Int) {
        sticky = sticky xor modifier
    }

    fun lock(modifier: Int, lock: Boolean) {
        locked = if (lock) locked or modifier else locked and modifier.inv()
    }

    fun activeModifiers(): Int = sticky or locked

    /**
     * 给定一个普通键，返回应当发送的 (down 序列, up 序列)。
     * 普通键被按下后，未锁住的粘性修饰会自动清除。
     */
    fun pressAndRelease(vk: Int): Pair<List<Step>, List<Step>> {
        val mods = activeModifiers()
        val down = buildList {
            modifierVks(mods).forEach { add(Step(it, true)) }
            add(Step(vk, true))
        }
        val up = buildList {
            add(Step(vk, false))
            modifierVks(mods).reversed().forEach { add(Step(it, false)) }
        }
        sticky = 0 // 粘性键自动消费；锁住的修饰保留
        return down to up
    }

    private fun modifierVks(mask: Int): List<Int> = buildList {
        if (mask and ModifierMask.CTRL != 0) add(0x11) // VK_CONTROL
        if (mask and ModifierMask.ALT != 0) add(0x12) // VK_MENU
        if (mask and ModifierMask.SHIFT != 0) add(0x10) // VK_SHIFT
        if (mask and ModifierMask.META != 0) add(KeyCodeTable.VK_LWIN)
    }

    data class Step(val vk: Int, val pressed: Boolean)
}
