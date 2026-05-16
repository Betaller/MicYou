package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.ModifierMask
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyComboTrackerTest {

    @Test
    fun ctrlPlusC_sendsCtrlDownCDownCUpCtrlUp() {
        val tracker = KeyComboTracker()
        tracker.toggleSticky(ModifierMask.CTRL)
        val (down, up) = tracker.pressAndRelease(vk = 0x43) // C
        assertEquals(
            listOf(KeyComboTracker.Step(0x11, true), KeyComboTracker.Step(0x43, true)),
            down
        )
        assertEquals(
            listOf(KeyComboTracker.Step(0x43, false), KeyComboTracker.Step(0x11, false)),
            up
        )
    }

    @Test
    fun stickyShift_holdsAcrossNextKey_thenReleases() {
        val tracker = KeyComboTracker()
        tracker.toggleSticky(ModifierMask.SHIFT)
        assertEquals(ModifierMask.SHIFT, tracker.activeModifiers())
        tracker.pressAndRelease(vk = 0x41) // A — consumes sticky
        assertEquals(0, tracker.activeModifiers(), "sticky should be cleared after one key press")
    }

    @Test
    fun lockedModifier_persistsAcrossPresses() {
        val tracker = KeyComboTracker()
        tracker.lock(ModifierMask.SHIFT, true) // CapsLock-like
        val (down1, _) = tracker.pressAndRelease(vk = 0x41)
        val (down2, _) = tracker.pressAndRelease(vk = 0x42)
        // Both should include shift down
        assertEquals(true, down1.any { it.vk == 0x10 && it.pressed })
        assertEquals(true, down2.any { it.vk == 0x10 && it.pressed })
    }

    @Test
    fun multipleStickyModifiers_combineAsBitmask() {
        val tracker = KeyComboTracker()
        tracker.toggleSticky(ModifierMask.CTRL)
        tracker.toggleSticky(ModifierMask.SHIFT)
        assertEquals(ModifierMask.CTRL or ModifierMask.SHIFT, tracker.activeModifiers())
        val (down, _) = tracker.pressAndRelease(vk = 0x42) // B
        // Order: CTRL → SHIFT → vk
        assertEquals(0x11, down[0].vk)
        assertEquals(0x10, down[1].vk)
        assertEquals(0x42, down[2].vk)
    }
}
