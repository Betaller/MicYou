package com.lanrhyme.micyou.security

import com.lanrhyme.micyou.Logger

/**
 * 6 位 PIN 校验 + 失败锁定。**不**做 PIN 持久化——每次启用功能时桌面端弹一次新 PIN。
 * - [maxFailures] 次失败后进入锁定，锁定时长 [lockoutMillis]
 * - 锁定期间即使 PIN 正确也直接返回 [Result.LockedOut]
 * - 时间源由 [nowMillis] 注入，便于测试
 */
class PinAuthenticator(
    private val maxFailures: Int = 3,
    private val lockoutMillis: Long = 5 * 60 * 1000L,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    @Volatile
    private var currentPin: String? = null
    private var failureCount: Int = 0
    private var lockoutUntil: Long = 0L

    /** 桌面端展示新 PIN 时调用——重置失败计数。 */
    fun rotatePin(pin: String) {
        require(pin.length in 4..8) { "PIN must be 4-8 digits" }
        require(pin.all { it.isDigit() }) { "PIN must be numeric" }
        synchronized(this) {
            currentPin = pin
            failureCount = 0
            lockoutUntil = 0L
            Logger.i(TAG, "PIN rotated; awaiting client")
        }
    }

    fun verify(candidate: String): Result {
        synchronized(this) {
            val now = nowMillis()
            if (now < lockoutUntil) {
                Logger.w(TAG, "PIN attempt while locked out (${lockoutUntil - now} ms remain)")
                return Result.LockedOut(remainingMillis = lockoutUntil - now)
            }
            val pin = currentPin ?: return Result.NotConfigured
            if (candidate == pin) {
                failureCount = 0
                Logger.i(TAG, "PIN accepted")
                return Result.Accepted
            }
            failureCount++
            Logger.w(TAG, "PIN rejected ($failureCount/$maxFailures)")
            if (failureCount >= maxFailures) {
                lockoutUntil = now + lockoutMillis
                failureCount = 0
                return Result.LockedOut(remainingMillis = lockoutMillis)
            }
            return Result.Rejected
        }
    }

    /** 测试/管理用：清除锁定。 */
    fun reset() {
        synchronized(this) {
            failureCount = 0
            lockoutUntil = 0L
        }
    }

    sealed class Result {
        object Accepted : Result()
        object Rejected : Result()
        object NotConfigured : Result()
        data class LockedOut(val remainingMillis: Long) : Result()
    }

    companion object { private const val TAG = "PinAuthenticator" }
}
