package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.KeyEventType
import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.MessageWrapper
import com.lanrhyme.micyou.MouseEventType
import com.lanrhyme.micyou.input.InputInjector
import java.util.concurrent.atomic.AtomicLong

/**
 * 把网络层收到的 mouse / key 消息派发到 [InputInjector]。
 * - 默认禁用：[isEnabled] 返回 false 时丢弃所有消息（不触达 injector）
 * - 速率限制：令牌桶，默认 200 msg/s/conn，溢出丢弃并记录一次
 * - 灵敏度：仅对相对鼠标位移生效
 */
class RemoteInputHandler(
    private val injector: InputInjector,
    private val isEnabled: () -> Boolean,
    private val isAuthorized: () -> Boolean = { true },
    private val sensitivity: () -> Float = { 1.0f },
    private val ratePerSecond: Int = 200,
    private val burst: Int = 20,
    private val nowNanos: () -> Long = { System.nanoTime() }
) {
    private val tokensTimes1000 = AtomicLong(burst.toLong() * 1000L)
    private val lastRefillNanos = AtomicLong(nowNanos())
    @Volatile
    private var lastRateWarnNanos: Long = 0

    fun handle(message: MessageWrapper) {
        if (message.mouse == null && message.key == null) return
        if (!isEnabled()) return
        if (!isAuthorized()) {
            Logger.w("RemoteInputHandler", "drop unauthorized input event")
            return
        }
        if (!consumeToken()) {
            warnRateLimit()
            return
        }

        message.mouse?.let { dispatchMouse(it) }
        message.key?.let { dispatchKey(it) }
    }

    private fun dispatchMouse(m: com.lanrhyme.micyou.MouseEventMessage) {
        when (m.type) {
            MouseEventType.MOVE_RELATIVE -> {
                val s = sensitivity().coerceIn(0.1f, 5.0f)
                val sx = (m.dx * s).toInt()
                val sy = (m.dy * s).toInt()
                if (sx != 0 || sy != 0) injector.mouseMoveRelative(sx, sy)
            }
            MouseEventType.BUTTON_DOWN -> injector.mousePress(m.button)
            MouseEventType.BUTTON_UP -> injector.mouseRelease(m.button)
            MouseEventType.WHEEL -> injector.wheel(m.wheelDelta)
            MouseEventType.MOVE_ABSOLUTE -> {
                // 暂以相对位移近似；绝对坐标在 PR #7 的多屏支持中再补
                injector.mouseMoveRelative(m.dx, m.dy)
            }
        }
    }

    private fun dispatchKey(k: com.lanrhyme.micyou.KeyEventMessage) {
        when (k.type) {
            KeyEventType.KEY_DOWN -> injector.keyPress(k.keyCode, k.modifiers)
            KeyEventType.KEY_UP -> injector.keyRelease(k.keyCode, k.modifiers)
            KeyEventType.TEXT -> k.text?.takeIf { it.isNotEmpty() }?.let { injector.typeUnicode(it) }
        }
    }

    private fun consumeToken(): Boolean {
        val now = nowNanos()
        val last = lastRefillNanos.getAndSet(now)
        val elapsedNs = (now - last).coerceAtLeast(0L)
        // 1 秒补 ratePerSecond 个 token；用 *1000 整数算法避免浮点
        val refill = elapsedNs * ratePerSecond / 1_000_000L // *1000 token-units per ns
        val cap = burst.toLong() * 1000L
        val updated = tokensTimes1000.updateAndGet { current ->
            (current + refill).coerceAtMost(cap)
        }
        if (updated < 1000L) return false
        tokensTimes1000.addAndGet(-1000L)
        return true
    }

    private fun warnRateLimit() {
        val now = nowNanos()
        if (now - lastRateWarnNanos > 1_000_000_000L) {
            lastRateWarnNanos = now
            Logger.w(TAG, "rate limit exceeded; dropping remote input messages")
        }
    }

    companion object {
        private const val TAG = "RemoteInputHandler"
    }
}
