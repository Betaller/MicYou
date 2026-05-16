package com.lanrhyme.micyou.input

import com.lanrhyme.micyou.Logger

/**
 * Windows 上优先用 JNA SendInput；其它平台或 JNA 加载失败时回退到 Robot。
 */
object InputInjectorFactory {
    private const val TAG = "InputInjectorFactory"

    fun create(): InputInjector {
        if (isWindows()) {
            try {
                val adapter = JnaUser32Adapter()
                Logger.i(TAG, "InputInjector loaded: WindowsSendInputInjector (JNA)")
                return WindowsSendInputInjector(adapter)
            } catch (t: Throwable) {
                Logger.w(TAG, "JNA SendInput init failed (${t.message}); falling back to Robot")
            }
        }
        Logger.i(TAG, "InputInjector loaded: RobotFallbackInjector")
        return RobotFallbackInjector()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.startsWith("windows") == true
}
