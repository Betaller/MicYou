package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.AudioPacketMessage
import com.lanrhyme.micyou.AudioPacketMessageOrdered
import com.lanrhyme.micyou.KeyEventMessage
import com.lanrhyme.micyou.KeyEventType
import com.lanrhyme.micyou.MessageWrapper
import com.lanrhyme.micyou.MouseButton
import com.lanrhyme.micyou.MouseEventMessage
import com.lanrhyme.micyou.MouseEventType
import com.lanrhyme.micyou.input.InputInjector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteInputHandlerTest {

    private class RecordingInjector : InputInjector {
        val events = mutableListOf<String>()
        override fun mouseMoveRelative(dx: Int, dy: Int) { events += "move($dx,$dy)" }
        override fun mousePress(button: Int) { events += "press($button)" }
        override fun mouseRelease(button: Int) { events += "release($button)" }
        override fun wheel(delta: Int) { events += "wheel($delta)" }
        override fun keyPress(vk: Int, modifiers: Int) { events += "kdown($vk,$modifiers)" }
        override fun keyRelease(vk: Int, modifiers: Int) { events += "kup($vk,$modifiers)" }
        override fun typeUnicode(text: String) { events += "text($text)" }
    }

    private fun mouse(type: Int, dx: Int = 0, dy: Int = 0, button: Int = 0, wheelDelta: Int = 0) =
        MessageWrapper(mouse = MouseEventMessage(type = type, dx = dx, dy = dy, button = button, wheelDelta = wheelDelta))

    private fun key(type: Int, code: Int = 0, mod: Int = 0, text: String? = null) =
        MessageWrapper(key = KeyEventMessage(type = type, keyCode = code, modifiers = mod, text = text))

    @Test
    fun handle_dropsMouseEvent_whenRemoteInputDisabled() {
        val inj = RecordingInjector()
        val handler = RemoteInputHandler(injector = inj, isEnabled = { false })
        handler.handle(mouse(MouseEventType.MOVE_RELATIVE, dx = 5, dy = 5))
        assertTrue(inj.events.isEmpty())
    }

    @Test
    fun handle_dispatchesMouseMove_whenEnabled() {
        val inj = RecordingInjector()
        val handler = RemoteInputHandler(injector = inj, isEnabled = { true })
        handler.handle(mouse(MouseEventType.MOVE_RELATIVE, dx = 10, dy = -3))
        assertEquals(listOf("move(10,-3)"), inj.events)
    }

    @Test
    fun handle_appliesSensitivityScaling() {
        val inj = RecordingInjector()
        val handler = RemoteInputHandler(
            injector = inj, isEnabled = { true }, sensitivity = { 2.0f }
        )
        handler.handle(mouse(MouseEventType.MOVE_RELATIVE, dx = 10, dy = 0))
        assertEquals(listOf("move(20,0)"), inj.events)
    }

    @Test
    fun handle_buttonAndWheel_routedCorrectly() {
        val inj = RecordingInjector()
        val handler = RemoteInputHandler(injector = inj, isEnabled = { true })
        handler.handle(mouse(MouseEventType.BUTTON_DOWN, button = MouseButton.LEFT))
        handler.handle(mouse(MouseEventType.BUTTON_UP, button = MouseButton.LEFT))
        handler.handle(mouse(MouseEventType.WHEEL, wheelDelta = 1))
        assertEquals(
            listOf("press(${MouseButton.LEFT})", "release(${MouseButton.LEFT})", "wheel(1)"),
            inj.events
        )
    }

    @Test
    fun handle_keyEvents_routedToInjector() {
        val inj = RecordingInjector()
        val handler = RemoteInputHandler(injector = inj, isEnabled = { true })
        handler.handle(key(KeyEventType.KEY_DOWN, code = 0x43, mod = 0x02))
        handler.handle(key(KeyEventType.KEY_UP, code = 0x43, mod = 0x02))
        handler.handle(key(KeyEventType.TEXT, text = "你好"))
        assertEquals(listOf("kdown(67,2)", "kup(67,2)", "text(你好)"), inj.events)
    }

    @Test
    fun handle_rateLimits_above200msgPerSecond() {
        val inj = RecordingInjector()
        var fakeNs = 0L
        val handler = RemoteInputHandler(
            injector = inj,
            isEnabled = { true },
            ratePerSecond = 200,
            burst = 20,
            nowNanos = { fakeNs }
        )
        // Within ~1ms, blast 1000 events. We should accept at most ~burst (20) + minimal refill.
        repeat(1000) { handler.handle(mouse(MouseEventType.MOVE_RELATIVE, dx = 1, dy = 0)) }
        // Burst is 20; refill at 200/s with ~0 ns elapsed → ≤ burst.
        assertTrue(inj.events.size in 1..30, "expected ≤ ~burst, got ${inj.events.size}")
    }

    @Test
    fun handle_doesNotInterfereWithAudioMessages() {
        val inj = RecordingInjector()
        val handler = RemoteInputHandler(injector = inj, isEnabled = { true })
        val audio = MessageWrapper(
            audioPacket = AudioPacketMessageOrdered(
                sequenceNumber = 1,
                audioPacket = AudioPacketMessage(ByteArray(8), 48000, 1, 16)
            )
        )
        handler.handle(audio)
        assertTrue(inj.events.isEmpty())
    }

    @Test
    fun handle_refillsTokensOverTime() {
        val inj = RecordingInjector()
        var fakeNs = 0L
        val handler = RemoteInputHandler(
            injector = inj,
            isEnabled = { true },
            ratePerSecond = 200,
            burst = 1,
            nowNanos = { fakeNs }
        )
        // Drain initial token
        handler.handle(mouse(MouseEventType.MOVE_RELATIVE, dx = 1, dy = 0))
        // Immediately again — should be dropped (no refill yet)
        handler.handle(mouse(MouseEventType.MOVE_RELATIVE, dx = 2, dy = 0))
        // Advance 10ms = 200/s * 0.01 = 2 tokens of headroom; one event accepted.
        fakeNs += 10_000_000L
        handler.handle(mouse(MouseEventType.MOVE_RELATIVE, dx = 3, dy = 0))
        assertEquals(listOf("move(1,0)", "move(3,0)"), inj.events)
    }
}
