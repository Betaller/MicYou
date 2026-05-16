package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.ModifierMask
import com.lanrhyme.micyou.MouseButton
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RobotFallbackInjectorTest {

    private class FakeRobotAdapter : RobotAdapter {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val executingThreads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private var px = 100
        private var py = 100
        override fun mouseMoveAbsolute(x: Int, y: Int) {
            executingThreads += Thread.currentThread().name
            px = x; py = y
            events += "move($x,$y)"
        }
        override fun pointerLocation(): Pair<Int, Int> = px to py
        override fun mousePress(buttons: Int) { events += "press($buttons)" }
        override fun mouseRelease(buttons: Int) { events += "release($buttons)" }
        override fun mouseWheel(notches: Int) { events += "wheel($notches)" }
        override fun keyPress(keycode: Int) { events += "keyPress($keycode)" }
        override fun keyRelease(keycode: Int) { events += "keyRelease($keycode)" }
    }

    private fun newInjector(adapter: FakeRobotAdapter): RobotFallbackInjector =
        RobotFallbackInjector(
            adapter = adapter,
            executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "MicYou-Input-Robot-Test").apply { isDaemon = true }
            }
        )

    private fun RobotFallbackInjector.flushAndDispose() {
        // dispose() shutdownNow's executor; ensure all queued tasks ran by submitting a barrier.
        val latch = java.util.concurrent.CountDownLatch(1)
        val barrierField = RobotFallbackInjector::class.java.getDeclaredField("executor")
        barrierField.isAccessible = true
        val exec = barrierField.get(this) as java.util.concurrent.ExecutorService
        exec.execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "barrier task did not run")
        dispose()
    }

    @Test
    fun mouseMoveRelative_serializesEventsOnSingleThread() {
        val adapter = FakeRobotAdapter()
        val injector = newInjector(adapter)
        val outstanding = AtomicInteger(0)
        repeat(100) { i -> injector.mouseMoveRelative(1, 0); outstanding.incrementAndGet() }
        injector.flushAndDispose()
        // Fake adapter only saw one executing thread → executor truly serialized everything.
        assertEquals(1, adapter.executingThreads.size, "expected single-thread, got ${adapter.executingThreads}")
        assertEquals(100, adapter.events.count { it.startsWith("move(") })
    }

    @Test
    fun typeUnicode_throwsOnNonAscii() {
        val adapter = FakeRobotAdapter()
        val injector = newInjector(adapter)
        try {
            assertFailsWith<UnsupportedOperationException> { injector.typeUnicode("中文") }
        } finally {
            injector.flushAndDispose()
        }
    }

    @Test
    fun typeUnicode_typesAsciiCharByChar() {
        val adapter = FakeRobotAdapter()
        val injector = newInjector(adapter)
        injector.typeUnicode("ab")
        injector.flushAndDispose()
        // 'a' lowercase → no shift; 'b' lowercase → no shift
        assertEquals(
            listOf(
                "keyPress(${KeyEvent.VK_A})", "keyRelease(${KeyEvent.VK_A})",
                "keyPress(${KeyEvent.VK_B})", "keyRelease(${KeyEvent.VK_B})"
            ),
            adapter.events.toList()
        )
    }

    @Test
    fun typeUnicode_uppercaseUsesShift() {
        val adapter = FakeRobotAdapter()
        val injector = newInjector(adapter)
        injector.typeUnicode("A")
        injector.flushAndDispose()
        assertEquals(
            listOf(
                "keyPress(${KeyEvent.VK_SHIFT})",
                "keyPress(${KeyEvent.VK_A})",
                "keyRelease(${KeyEvent.VK_A})",
                "keyRelease(${KeyEvent.VK_SHIFT})"
            ),
            adapter.events.toList()
        )
    }

    @Test
    fun wheel_clampsExtremeDelta_andDropsZero() {
        val adapter = FakeRobotAdapter()
        val injector = newInjector(adapter)
        injector.wheel(Int.MAX_VALUE)
        injector.wheel(60) // 60/120 = 0 notches → dropped
        injector.flushAndDispose()
        val notches = adapter.events.filter { it.startsWith("wheel(") }
        assertEquals(1, notches.size)
        // Clamped to MAX_NOTCHES (100)
        assertEquals("wheel(100)", notches.first())
    }

    @Test
    fun mousePress_mapsButtons() {
        val adapter = FakeRobotAdapter()
        val injector = newInjector(adapter)
        injector.mousePress(MouseButton.LEFT)
        injector.mousePress(MouseButton.RIGHT)
        injector.mousePress(MouseButton.MIDDLE)
        injector.flushAndDispose()
        assertEquals(
            listOf(
                "press(${InputEvent.BUTTON1_DOWN_MASK})",
                "press(${InputEvent.BUTTON3_DOWN_MASK})",
                "press(${InputEvent.BUTTON2_DOWN_MASK})"
            ),
            adapter.events.toList()
        )
    }

    @Test
    fun keyPress_emitsModifiersThenKey_thenReleaseInReverse() {
        val adapter = FakeRobotAdapter()
        val injector = newInjector(adapter)
        injector.keyPress(KeyEvent.VK_C, ModifierMask.CTRL)
        injector.keyRelease(KeyEvent.VK_C, ModifierMask.CTRL)
        injector.flushAndDispose()
        assertEquals(
            listOf(
                "keyPress(${KeyEvent.VK_CONTROL})",
                "keyPress(${KeyEvent.VK_C})",
                "keyRelease(${KeyEvent.VK_C})",
                "keyRelease(${KeyEvent.VK_CONTROL})"
            ),
            adapter.events.toList()
        )
    }
}
