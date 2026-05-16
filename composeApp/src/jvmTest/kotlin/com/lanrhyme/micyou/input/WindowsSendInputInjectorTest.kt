package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.ModifierMask
import com.lanrhyme.micyou.MouseButton
import com.lanrhyme.micyou.input.Win32Constants.KEYEVENTF_KEYUP
import com.lanrhyme.micyou.input.Win32Constants.KEYEVENTF_UNICODE
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_LEFTDOWN
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_LEFTUP
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_MOVE
import com.lanrhyme.micyou.input.Win32Constants.MOUSEEVENTF_WHEEL
import com.lanrhyme.micyou.input.Win32Constants.VK_CONTROL
import com.lanrhyme.micyou.input.Win32Constants.WHEEL_DELTA
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsSendInputInjectorTest {

    private class FakeUser32 : User32Adapter {
        val batches = java.util.concurrent.CopyOnWriteArrayList<List<InputRecord>>()
        override fun sendInput(inputs: List<InputRecord>): Int {
            batches += inputs
            return inputs.size
        }
    }

    private fun newInjector(adapter: FakeUser32): WindowsSendInputInjector =
        WindowsSendInputInjector(
            adapter = adapter,
            executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "MicYou-Input-Win-Test").apply { isDaemon = true }
            }
        )

    private fun WindowsSendInputInjector.flushAndDispose() {
        val latch = java.util.concurrent.CountDownLatch(1)
        val f = WindowsSendInputInjector::class.java.getDeclaredField("executor").also { it.isAccessible = true }
        val exec = f.get(this) as java.util.concurrent.ExecutorService
        exec.execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        dispose()
    }

    @Test
    fun mouseMoveRelative_emitsSingleMouseInput() {
        val a = FakeUser32()
        val inj = newInjector(a)
        inj.mouseMoveRelative(50, -20)
        inj.flushAndDispose()
        assertEquals(1, a.batches.size)
        assertEquals(
            listOf(InputRecord.Mouse(dx = 50, dy = -20, flags = MOUSEEVENTF_MOVE)),
            a.batches[0]
        )
    }

    @Test
    fun mousePress_left_emitsLeftDownFlag() {
        val a = FakeUser32()
        val inj = newInjector(a)
        inj.mousePress(MouseButton.LEFT)
        inj.flushAndDispose()
        assertEquals(listOf(InputRecord.Mouse(flags = MOUSEEVENTF_LEFTDOWN)), a.batches[0])
    }

    @Test
    fun wheel_usesWheelDelta120Multiple() {
        val a = FakeUser32()
        val inj = newInjector(a)
        inj.wheel(1)
        inj.wheel(-2)
        inj.flushAndDispose()
        assertEquals(
            InputRecord.Mouse(mouseData = WHEEL_DELTA, flags = MOUSEEVENTF_WHEEL),
            a.batches[0].single()
        )
        assertEquals(
            InputRecord.Mouse(mouseData = -2 * WHEEL_DELTA, flags = MOUSEEVENTF_WHEEL),
            a.batches[1].single()
        )
    }

    @Test
    fun typeUnicode_buildsUnicodeInputForBmpChar() {
        val a = FakeUser32()
        val inj = newInjector(a)
        inj.typeUnicode("A")
        inj.flushAndDispose()
        assertEquals(
            listOf(
                InputRecord.Keyboard(scan = 'A'.code, flags = KEYEVENTF_UNICODE),
                InputRecord.Keyboard(scan = 'A'.code, flags = KEYEVENTF_UNICODE or KEYEVENTF_KEYUP)
            ),
            a.batches[0]
        )
    }

    @Test
    fun typeUnicode_emitsSurrogatePairAsTwoInputs() {
        val a = FakeUser32()
        val inj = newInjector(a)
        inj.typeUnicode("😀") // U+1F600 → surrogate pair D83D DE00
        inj.flushAndDispose()
        val batch = a.batches.single()
        assertEquals(4, batch.size, "expected 2 down + 2 up = 4 events for surrogate pair")
        val high = batch[0] as InputRecord.Keyboard
        val low = batch[2] as InputRecord.Keyboard
        assertEquals(0xD83D, high.scan)
        assertEquals(0xDE00, low.scan)
        assertEquals(KEYEVENTF_UNICODE, high.flags)
        assertEquals(KEYEVENTF_UNICODE or KEYEVENTF_KEYUP, batch[1].let { (it as InputRecord.Keyboard).flags })
    }

    @Test
    fun typeUnicode_chineseTextBuildsCorrectScans() {
        val a = FakeUser32()
        val inj = newInjector(a)
        inj.typeUnicode("你好")
        inj.flushAndDispose()
        val batch = a.batches.single()
        assertEquals(4, batch.size)
        assertEquals(0x4F60, (batch[0] as InputRecord.Keyboard).scan) // 你
        assertEquals(0x597D, (batch[2] as InputRecord.Keyboard).scan) // 好
    }

    @Test
    fun keyPress_ctrlPlusC_sendsCtrlDownThenCDown_thenReleaseInReverse() {
        val a = FakeUser32()
        val inj = newInjector(a)
        val vkC = 0x43
        inj.keyPress(vkC, ModifierMask.CTRL)
        inj.keyRelease(vkC, ModifierMask.CTRL)
        inj.flushAndDispose()
        // Press batch
        assertEquals(
            listOf(
                InputRecord.Keyboard(vk = VK_CONTROL),
                InputRecord.Keyboard(vk = vkC)
            ),
            a.batches[0]
        )
        // Release batch
        assertEquals(
            listOf(
                InputRecord.Keyboard(vk = vkC, flags = KEYEVENTF_KEYUP),
                InputRecord.Keyboard(vk = VK_CONTROL, flags = KEYEVENTF_KEYUP)
            ),
            a.batches[1]
        )
    }

    @Test
    fun factory_returnsRobotFallback_onNonWindows() {
        // 仅在非 Windows 上跑这条断言
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        if (osName.startsWith("windows")) return
        val inj = InputInjectorFactory.create()
        assertTrue(inj is RobotFallbackInjector, "expected RobotFallbackInjector on $osName")
        inj.dispose()
    }
}
