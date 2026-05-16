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
    fun longPress_500ms_emitsRightClick() {
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 10f, 10f, 0), emit)
            onEvent(PointerEvent.Tick(500), emit)
            onEvent(PointerEvent.Up(1, 10f, 10f, 510), emit)
        }
        assertEquals(listOf(GestureCommand.MouseClick(MouseButton.RIGHT)), cmds)
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
    fun twoFingerVerticalSwipe_emitsWheel() {
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 0f, 100f, 0), emit)
            onEvent(PointerEvent.Down(2, 30f, 100f, 5), emit)
            // Both fingers slide down by 60 px (one notch worth) twice → 2 wheel notches
            onEvent(PointerEvent.Move(1, 0f, 160f, 10), emit)
            onEvent(PointerEvent.Move(2, 30f, 160f, 11), emit)
            onEvent(PointerEvent.Move(1, 0f, 220f, 20), emit)
            onEvent(PointerEvent.Move(2, 30f, 220f, 21), emit)
            onEvent(PointerEvent.Up(1, 0f, 220f, 30), emit)
            onEvent(PointerEvent.Up(2, 30f, 220f, 31), emit)
        }
        val wheelTotal = cmds.filterIsInstance<GestureCommand.Wheel>().sumOf { it.notches }
        // Downward swipe → negative notches (page-down direction)
        assertTrue(wheelTotal <= -1, "expected at least 1 down-notch, got $wheelTotal")
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
        // Middle click is emitted when the third (last in our impl) finger lifts.
        assertTrue(
            cmds.any { it == GestureCommand.MouseClick(MouseButton.MIDDLE) },
            "expected a middle click, got $cmds"
        )
    }

    @Test
    fun longPress_doesNotAlsoEmitClickOnRelease() {
        val cmds = collect { emit ->
            onEvent(PointerEvent.Down(1, 0f, 0f, 0), emit)
            onEvent(PointerEvent.Tick(600), emit)
            onEvent(PointerEvent.Up(1, 0f, 0f, 700), emit)
        }
        assertEquals(1, cmds.size, "long-press release should not produce a stray click")
        assertEquals(GestureCommand.MouseClick(MouseButton.RIGHT), cmds.single())
    }
}
