package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.Logger

/**
 * 工厂：在 PR #4 中会改为 Windows 优先 SendInput；当前先返回 Robot 兜底。
 */
object InputInjectorFactory {
    private const val TAG = "InputInjectorFactory"

    fun create(): InputInjector {
        val injector = RobotFallbackInjector()
        Logger.i(TAG, "InputInjector loaded: RobotFallbackInjector")
        return injector
    }
}
