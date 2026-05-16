package com.lanrhyme.micyou

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteInputViewModelTest {

    private class FakeTransport(initial: RemoteInputConnectionState = RemoteInputConnectionState.Connected) :
        RemoteInputTransport {
        val sent = mutableListOf<MessageWrapper>()
        private val state = MutableStateFlow(initial)
        override val connectionState: StateFlow<RemoteInputConnectionState> = state.asStateFlow()
        override suspend fun send(message: MessageWrapper) {
            sent += message
        }
        fun setState(s: RemoteInputConnectionState) { state.value = s }
    }

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sendMouseMove_coalescesWithin16ms() = runTest(testDispatcher) {
        val transport = FakeTransport()
        val vm = RemoteInputViewModel(
            transportProvider = { transport },
            coalesceMillis = 16L,
            ioDispatcher = testDispatcher
        )
        repeat(5) { vm.sendMouseMove(1, 0) }
        advanceTimeBy(20)
        advanceUntilIdle()
        assertEquals(1, transport.sent.size, "expected 1 coalesced batch, got ${transport.sent.size}")
        assertEquals(5, transport.sent[0].mouse?.dx)
        assertEquals(0, transport.sent[0].mouse?.dy)
        assertEquals(MouseEventType.MOVE_RELATIVE, transport.sent[0].mouse?.type)
    }

    @Test
    fun sendMouseButton_isImmediate_notCoalesced() = runTest(testDispatcher) {
        val transport = FakeTransport()
        val vm = RemoteInputViewModel(
            transportProvider = { transport },
            coalesceMillis = 16L,
            ioDispatcher = testDispatcher
        )
        vm.sendMouseMove(3, 0)
        vm.sendMouseButton(MouseButton.LEFT, pressed = true)
        advanceUntilIdle()
        // Button should arrive separately (not folded into the move batch)
        val buttonMsgs = transport.sent.filter { it.mouse?.type == MouseEventType.BUTTON_DOWN }
        val moveMsgs = transport.sent.filter { it.mouse?.type == MouseEventType.MOVE_RELATIVE }
        assertEquals(1, buttonMsgs.size)
        assertEquals(1, moveMsgs.size)
    }

    @Test
    fun sendUnicodeText_routedAsKeyEventTextType() = runTest(testDispatcher) {
        val transport = FakeTransport()
        val vm = RemoteInputViewModel(
            transportProvider = { transport },
            ioDispatcher = testDispatcher
        )
        vm.sendUnicodeText("你好 😀")
        advanceUntilIdle()
        val msg = transport.sent.single()
        assertEquals(KeyEventType.TEXT, msg.key?.type)
        assertEquals("你好 😀", msg.key?.text)
    }

    @Test
    fun noSend_whenDisconnected() = runTest(testDispatcher) {
        val transport = FakeTransport(initial = RemoteInputConnectionState.Disconnected)
        val vm = RemoteInputViewModel(
            transportProvider = { transport },
            ioDispatcher = testDispatcher
        )
        vm.sendMouseMove(10, 10)
        vm.sendUnicodeText("hi")
        vm.sendMouseButton(MouseButton.LEFT, pressed = true)
        advanceUntilIdle()
        assertTrue(transport.sent.isEmpty(), "expected 0 sends while disconnected, got ${transport.sent.size}")
    }

    @Test
    fun coalescing_resetsAfterFlush() = runTest(testDispatcher) {
        val transport = FakeTransport()
        val vm = RemoteInputViewModel(
            transportProvider = { transport },
            coalesceMillis = 16L,
            ioDispatcher = testDispatcher
        )
        // Batch 1
        vm.sendMouseMove(2, 0)
        vm.sendMouseMove(3, 0)
        advanceTimeBy(20)
        advanceUntilIdle()
        // Batch 2
        vm.sendMouseMove(7, 0)
        advanceTimeBy(20)
        advanceUntilIdle()
        assertEquals(2, transport.sent.size)
        assertEquals(5, transport.sent[0].mouse?.dx)
        assertEquals(7, transport.sent[1].mouse?.dx)
    }

    @Test
    fun sendKeyEvent_emitsDownAndUp_separately() = runTest(testDispatcher) {
        val transport = FakeTransport()
        val vm = RemoteInputViewModel(
            transportProvider = { transport },
            ioDispatcher = testDispatcher
        )
        vm.sendKeyEvent(0x43, ModifierMask.CTRL, pressed = true)
        vm.sendKeyEvent(0x43, ModifierMask.CTRL, pressed = false)
        advanceUntilIdle()
        assertEquals(2, transport.sent.size)
        assertEquals(KeyEventType.KEY_DOWN, transport.sent[0].key?.type)
        assertEquals(KeyEventType.KEY_UP, transport.sent[1].key?.type)
        assertEquals(0x43, transport.sent[0].key?.keyCode)
        assertEquals(ModifierMask.CTRL, transport.sent[0].key?.modifiers)
    }

    @Test
    fun sendWheel_skipsZero() = runTest(testDispatcher) {
        val transport = FakeTransport()
        val vm = RemoteInputViewModel(
            transportProvider = { transport },
            ioDispatcher = testDispatcher
        )
        vm.sendWheel(0)
        vm.sendWheel(2)
        advanceUntilIdle()
        assertEquals(1, transport.sent.size)
        assertEquals(2, transport.sent[0].mouse?.wheelDelta)
    }
}
