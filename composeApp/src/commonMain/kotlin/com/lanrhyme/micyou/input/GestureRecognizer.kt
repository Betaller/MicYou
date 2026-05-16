package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.MouseButton
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 触控板手势识别——纯 Kotlin、无 Compose 依赖，便于单测覆盖。
 * 上层 UI 把指针事件平铺成 [PointerEvent]、调用 [onEvent]，
 * 收到 [GestureCommand] 后通过 RemoteInputViewModel 发出去。
 *
 * 阈值：tap < 150ms 且位移 < tapSlop；longPress ≥ 500ms；
 * 双击窗口 250ms；滚轮每 wheelStep 像素 = 1 notch。
 */
class GestureRecognizer(
    private val tapMaxMillis: Long = 150L,
    private val longPressMillis: Long = 500L,
    private val doubleTapWindowMillis: Long = 250L,
    private val tapSlop: Float = 8f,
    private val wheelStep: Float = 60f
) {
    private val pointers = HashMap<Long, PointerInfo>()
    private var lastTapUpTime = 0L
    private var pendingDoubleTap = false
    private var longPressFired = false
    private var primaryPointerId: Long? = null
    private var twoFingerY: Float? = null
    private var wheelAccumulator: Float = 0f
    private var threeFingerCandidate = false
    private var threeFingerActiveTime: Long = 0L
    private var consumeRemainingLifts = false

    private data class PointerInfo(
        val downX: Float,
        val downY: Float,
        var lastX: Float,
        var lastY: Float,
        val downTime: Long,
        var moved: Boolean = false
    )

    fun onEvent(e: PointerEvent, emit: (GestureCommand) -> Unit) {
        when (e) {
            is PointerEvent.Down -> handleDown(e, emit)
            is PointerEvent.Move -> handleMove(e, emit)
            is PointerEvent.Up -> handleUp(e, emit)
            is PointerEvent.Tick -> handleTick(e, emit)
        }
    }

    private fun handleDown(e: PointerEvent.Down, emit: (GestureCommand) -> Unit) {
        pointers[e.id] = PointerInfo(e.x, e.y, e.x, e.y, e.time)
        if (pointers.size == 1) {
            primaryPointerId = e.id
            longPressFired = false
        } else if (pointers.size == 2) {
            // Two-finger gesture begins; reset wheel state
            twoFingerY = pointers.values.map { it.lastY }.average().toFloat()
            wheelAccumulator = 0f
        } else if (pointers.size == 3) {
            threeFingerCandidate = true
            threeFingerActiveTime = e.time
        }
    }

    private fun handleMove(e: PointerEvent.Move, emit: (GestureCommand) -> Unit) {
        val info = pointers[e.id] ?: return
        val dx = e.x - info.lastX
        val dy = e.y - info.lastY
        info.lastX = e.x
        info.lastY = e.y
        if (hypot((e.x - info.downX).toDouble(), (e.y - info.downY).toDouble()) > tapSlop) {
            info.moved = true
            // Drag → cancel any pending three-finger / double-tap
            threeFingerCandidate = false
        }
        when (pointers.size) {
            1 -> {
                if (info.moved && (dx != 0f || dy != 0f)) {
                    emit(GestureCommand.MouseMove(dx.toInt(), dy.toInt()))
                }
            }
            2 -> {
                val avgY = pointers.values.map { it.lastY }.average().toFloat()
                val baseline = twoFingerY ?: run { twoFingerY = avgY; avgY }
                val delta = avgY - baseline
                wheelAccumulator += delta
                twoFingerY = avgY
                while (abs(wheelAccumulator) >= wheelStep) {
                    val notches = if (wheelAccumulator > 0) -1 else 1 // 上滑→正方向（向上滚）
                    emit(GestureCommand.Wheel(notches))
                    wheelAccumulator -= wheelStep * (if (wheelAccumulator > 0) 1f else -1f)
                }
            }
        }
    }

    private fun handleUp(e: PointerEvent.Up, emit: (GestureCommand) -> Unit) {
        val info = pointers.remove(e.id) ?: return
        val dur = e.time - info.downTime
        val moved = info.moved
        val activeBeforeRelease = pointers.size + 1

        if (consumeRemainingLifts) {
            if (pointers.isEmpty()) consumeRemainingLifts = false
            return
        }

        if (activeBeforeRelease == 3 && threeFingerCandidate && !moved) {
            emit(GestureCommand.MouseClick(MouseButton.MIDDLE))
            threeFingerCandidate = false
            consumeRemainingLifts = pointers.isNotEmpty()
            twoFingerY = null
            wheelAccumulator = 0f
            return
        }
        if (activeBeforeRelease >= 2) {
            twoFingerY = null
            wheelAccumulator = 0f
            return
        }

        // Single-finger release
        primaryPointerId = null
        if (longPressFired) return // long-press already emitted right click

        if (!moved && dur < tapMaxMillis) {
            val now = e.time
            if (pendingDoubleTap && now - lastTapUpTime <= doubleTapWindowMillis) {
                emit(GestureCommand.MouseDoubleClick(MouseButton.LEFT))
                pendingDoubleTap = false
                lastTapUpTime = 0L
            } else {
                emit(GestureCommand.MouseClick(MouseButton.LEFT))
                pendingDoubleTap = true
                lastTapUpTime = now
            }
        } else {
            pendingDoubleTap = false
        }
    }

    private fun handleTick(e: PointerEvent.Tick, emit: (GestureCommand) -> Unit) {
        // Long-press detection: while a single pointer is held without much movement.
        val pid = primaryPointerId ?: return
        val info = pointers[pid] ?: return
        if (!longPressFired && !info.moved && pointers.size == 1 && (e.time - info.downTime) >= longPressMillis) {
            longPressFired = true
            emit(GestureCommand.MouseClick(MouseButton.RIGHT))
        }
    }
}

sealed class PointerEvent {
    abstract val time: Long
    data class Down(val id: Long, val x: Float, val y: Float, override val time: Long) : PointerEvent()
    data class Move(val id: Long, val x: Float, val y: Float, override val time: Long) : PointerEvent()
    data class Up(val id: Long, val x: Float, val y: Float, override val time: Long) : PointerEvent()
    data class Tick(override val time: Long) : PointerEvent()
}

sealed class GestureCommand {
    data class MouseMove(val dx: Int, val dy: Int) : GestureCommand()
    data class MouseClick(val button: Int) : GestureCommand()
    data class MouseDoubleClick(val button: Int) : GestureCommand()
    data class Wheel(val notches: Int) : GestureCommand()
}
