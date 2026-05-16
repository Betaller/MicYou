package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.MouseButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GestureRecognizerTest {

    private fun collect(block: GestureRecognizer.((GestureCommand) -> Unit) -> Unit): List<GestureCommand> {
        val out = mutableListOf<GestureCommand>()
        val r = GestureRecognizer()
        r.block { out += it }
        return out
    }

    @Test
    fun singleTap_within150ms_emitsLeftClick() {
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 10f, 10f, 0), emit)
            onEvent(PointerEvent.Up(1, 11f, 11f, 100), emit)
        }
        assertEquals(listOf(GestureCommand.MouseClick(MouseButton.LEFT)), cmds)
    }

    @Test
    fun longPress_entersDragMode_pressesLeftDownThenReleasesOnLift() {
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 10f, 10f, 0), emit)
            onEvent(PointerEvent.Tick(500), emit)
            // After long-press, finger moves → drag, expect MouseMove
            onEvent(PointerEvent.Move(1, 30f, 10f, 600), emit)
            onEvent(PointerEvent.Up(1, 30f, 10f, 700), emit)
        }
        assertEquals(GestureCommand.MouseButtonDown(MouseButton.LEFT), cmds.first())
        assertTrue(cmds.any { it is GestureCommand.MouseMove }, "expected drag move events")
        assertEquals(GestureCommand.MouseButtonUp(MouseButton.LEFT), cmds.last())
        // No left click on release
        assertTrue(cmds.none { it is GestureCommand.MouseClick && it.button == MouseButton.LEFT })
    }

    @Test
    fun dragOver8dp_emitsMouseMove_notTap() {
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 0f, 0f, 0), emit)
            onEvent(PointerEvent.Move(1, 5f, 0f, 10), emit)
            onEvent(PointerEvent.Move(1, 20f, 0f, 20), emit)
            onEvent(PointerEvent.Move(1, 35f, 0f, 30), emit)
            onEvent(PointerEvent.Up(1, 35f, 0f, 50), emit)
        }
        val moves = cmds.filterIsInstance<GestureCommand.MouseMove>()
        assertTrue(moves.isNotEmpty(), "expected at least one move")
        assertTrue(cmds.none { it is GestureCommand.MouseClick }, "should not emit click after drag")
    }

    @Test
    fun doubleTap_emitsDoubleLeftClick() {
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 0f, 0f, 0), emit)
            onEvent(PointerEvent.Up(1, 0f, 0f, 50), emit)
            onEvent(PointerEvent.Down(1, 0f, 0f, 200), emit)
            onEvent(PointerEvent.Up(1, 0f, 0f, 250), emit)
        }
        assertEquals(
            listOf(
                GestureCommand.MouseClick(MouseButton.LEFT),
                GestureCommand.MouseDoubleClick(MouseButton.LEFT)
            ),
            cmds
        )
    }

    @Test
    fun twoFingerVerticalSwipe_naturalScroll_upSwipeWheelDown() {
        // 双指上滑 (Y 减小) → 自然滚动 → 滚轮向下 → notches 负
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 0f, 200f, 0), emit)
            onEvent(PointerEvent.Down(2, 30f, 200f, 5), emit)
            onEvent(PointerEvent.Move(1, 0f, 140f, 10), emit)
            onEvent(PointerEvent.Move(2, 30f, 140f, 11), emit)
            onEvent(PointerEvent.Move(1, 0f, 80f, 20), emit)
            onEvent(PointerEvent.Move(2, 30f, 80f, 21), emit)
            onEvent(PointerEvent.Up(1, 0f, 80f, 30), emit)
            onEvent(PointerEvent.Up(2, 30f, 80f, 31), emit)
        }
        val total = cmds.filterIsInstance<GestureCommand.Wheel>().sumOf { it.notches }
        assertTrue(total <= -1, "up-swipe should yield negative notches (page-down), got $total")
    }

    @Test
    fun twoFingerVerticalSwipe_downSwipeWheelUp() {
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 0f, 100f, 0), emit)
            onEvent(PointerEvent.Down(2, 30f, 100f, 5), emit)
            onEvent(PointerEvent.Move(1, 0f, 160f, 10), emit)
            onEvent(PointerEvent.Move(2, 30f, 160f, 11), emit)
            onEvent(PointerEvent.Move(1, 0f, 220f, 20), emit)
            onEvent(PointerEvent.Move(2, 30f, 220f, 21), emit)
            onEvent(PointerEvent.Up(1, 0f, 220f, 30), emit)
            onEvent(PointerEvent.Up(2, 30f, 220f, 31), emit)
        }
        val total = cmds.filterIsInstance<GestureCommand.Wheel>().sumOf { it.notches }
        assertTrue(total >= 1, "down-swipe should yield positive notches (page-up), got $total")
    }

    @Test
    fun twoFingerTap_emitsRightClick() {
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 0f, 0f, 0), emit)
            onEvent(PointerEvent.Down(2, 30f, 0f, 5), emit)
            onEvent(PointerEvent.Up(2, 30f, 0f, 80), emit)
            onEvent(PointerEvent.Up(1, 0f, 0f, 90), emit)
        }
        assertTrue(
            cmds.any { it == GestureCommand.MouseClick(MouseButton.RIGHT) },
            "expected right click for two-finger tap, got $cmds"
        )
    }

    @Test
    fun twoFingerTap_afterScroll_doesNotEmitRightClick() {
        // 双指落下、滑动产生 wheel、再抬起 → 不应该触发右键
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 0f, 200f, 0), emit)
            onEvent(PointerEvent.Down(2, 30f, 200f, 5), emit)
            onEvent(PointerEvent.Move(1, 0f, 130f, 10), emit)
            onEvent(PointerEvent.Move(2, 30f, 130f, 11), emit)
            onEvent(PointerEvent.Up(2, 30f, 130f, 30), emit)
            onEvent(PointerEvent.Up(1, 0f, 130f, 40), emit)
        }
        assertTrue(cmds.none { it == GestureCommand.MouseClick(MouseButton.RIGHT) })
    }

    @Test
    fun threeFingerTap_emitsMiddleClick() {
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 0f, 0f, 0), emit)
            onEvent(PointerEvent.Down(2, 30f, 0f, 1), emit)
            onEvent(PointerEvent.Down(3, 60f, 0f, 2), emit)
            onEvent(PointerEvent.Up(3, 60f, 0f, 50), emit)
            onEvent(PointerEvent.Up(2, 30f, 0f, 51), emit)
            onEvent(PointerEvent.Up(1, 0f, 0f, 52), emit)
        }
        assertTrue(
            cmds.any { it == GestureCommand.MouseClick(MouseButton.MIDDLE) },
            "expected a middle click, got $cmds"
        )
    }
}
