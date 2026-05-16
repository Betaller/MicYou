package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.MouseButton
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 触控板手势识别——纯 Kotlin、无 Compose 依赖，便于单测覆盖。
 * 上层 UI 把指针事件平铺成 [PointerEvent]、调用 [onEvent]，
 * 收到 [GestureCommand] 后通过 RemoteInputViewModel 发出去。
 *
 * 当前手势：
 * - 单指轻点    → 左键
 * - 单指双击    → 左键双击
 * - 单指拖拽    → 鼠标移动
 * - 单指长按    → 进入拖动模式（按住左键直到松开）
 * - 双指轻点    → 右键
 * - 双指竖滑    → 滚轮（上滑=页面向下；自然滚动方向）
 * - 三指轻点    → 中键
 */
class GestureRecognizer(
    private val tapMaxMillis: Long = 200L,
    private val longPressMillis: Long = 500L,
    private val doubleTapWindowMillis: Long = 250L,
    private val tapSlop: Float = 8f,
    private val wheelStep: Float = 60f
) {
    private val pointers = LinkedHashMap<Long, PointerInfo>()
    private var lastTapUpTime = 0L
    private var pendingDoubleTap = false
    private var primaryPointerId: Long? = null
    private var twoFingerY: Float? = null
    private var wheelAccumulator: Float = 0f
    private var threeFingerCandidate = false
    private var consumeRemainingLifts = false

    /** 单指长按进入"按住左键拖动"模式，直到该指松开 */
    private var dragMode = false

    /** 双指同时按下且双方都没显著位移、没触发滚动 → 双指 tap = 右键 */
    private var twoFingerTapCandidate = false
    /** 双指 tap 候选过程中是否产生过滚动事件，产生过即取消右键 */
    private var twoFingerScrolled = false

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
        when (pointers.size) {
            1 -> {
                primaryPointerId = e.id
            }
            2 -> {
                twoFingerY = pointers.values.map { it.lastY }.average().toFloat()
                wheelAccumulator = 0f
                twoFingerTapCandidate = true
                twoFingerScrolled = false
            }
            3 -> {
                threeFingerCandidate = true
                twoFingerTapCandidate = false
            }
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
            threeFingerCandidate = false
            twoFingerTapCandidate = false
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
                    // 自然滚动：finger 上滑 (avgY 减小) → 页面向下 → wheel 向下 → notches 负
                    val notches = if (wheelAccumulator > 0) 1 else -1
                    emit(GestureCommand.Wheel(notches))
                    wheelAccumulator -= wheelStep * (if (wheelAccumulator > 0) 1f else -1f)
                    twoFingerScrolled = true
                    twoFingerTapCandidate = false
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

        // 三指 tap：第一指抬起且无移动 → 中键，余下抬起忽略
        if (activeBeforeRelease == 3 && threeFingerCandidate && !moved) {
            emit(GestureCommand.MouseClick(MouseButton.MIDDLE))
            threeFingerCandidate = false
            twoFingerTapCandidate = false
            consumeRemainingLifts = pointers.isNotEmpty()
            twoFingerY = null
            wheelAccumulator = 0f
            return
        }

        // 双指释放路径
        if (activeBeforeRelease == 2) {
            // 第一指抬起：如果是两指快速 tap (无显著移动、无滚动) → 右键，并消费第二指
            if (twoFingerTapCandidate && !twoFingerScrolled && !moved && dur < tapMaxMillis) {
                val otherStillBrief = pointers.values.firstOrNull()?.let { other ->
                    !other.moved && (e.time - other.downTime) < tapMaxMillis * 2
                } ?: false
                if (otherStillBrief || pointers.isEmpty()) {
                    emit(GestureCommand.MouseClick(MouseButton.RIGHT))
                    twoFingerTapCandidate = false
                    consumeRemainingLifts = pointers.isNotEmpty()
                    twoFingerY = null
                    wheelAccumulator = 0f
                    return
                }
            }
            twoFingerTapCandidate = false
            twoFingerY = null
            wheelAccumulator = 0f
            return
        }
        if (activeBeforeRelease > 2) {
            // 多指松到 2 指中间状态，不发命令
            return
        }

        // 单指释放
        primaryPointerId = null

        if (dragMode) {
            emit(GestureCommand.MouseButtonUp(MouseButton.LEFT))
            dragMode = false
            pendingDoubleTap = false
            return
        }

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
        // 单指长按 → 进入拖动：按住左键，后续 Move 仍发 MouseMove
        val pid = primaryPointerId ?: return
        val info = pointers[pid] ?: return
        if (!dragMode && !info.moved && pointers.size == 1 &&
            (e.time - info.downTime) >= longPressMillis
        ) {
            dragMode = true
            emit(GestureCommand.MouseButtonDown(MouseButton.LEFT))
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
    data class MouseButtonDown(val button: Int) : GestureCommand()
    data class MouseButtonUp(val button: Int) : GestureCommand()
    data class Wheel(val notches: Int) : GestureCommand()
}
