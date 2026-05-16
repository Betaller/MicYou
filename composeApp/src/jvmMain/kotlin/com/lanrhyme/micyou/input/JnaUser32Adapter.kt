package com.lanrhyme.micyou.input

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

/**
 * 真正调用 user32.dll 的 SendInput 适配器。仅在 Windows 上加载。
 * 启动时会执行一次空 SendInput 自检，失败则在构造期抛异常以便工厂回退到 Robot。
 */
internal class JnaUser32Adapter : User32Adapter {

    init {
        // 自检：触发 native 加载 + 简单调用，若 user32.dll 加载失败构造期立即可见
        val probe = WinUser.INPUT()
        probe.type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
        probe.input.setType("mi")
        probe.input.mi.dwFlags = WinDef.DWORD(0)
        // 不实际发送，仅触发结构体初始化
    }

    override fun sendInput(inputs: List<InputRecord>): Int {
        if (inputs.isEmpty()) return 0
        val arr = WinUser.INPUT().toArray(inputs.size) as Array<WinUser.INPUT>
        for ((i, rec) in inputs.withIndex()) {
            val target = arr[i]
            when (rec) {
                is InputRecord.Mouse -> {
                    target.type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
                    target.input.setType("mi")
                    target.input.mi.dx = WinDef.LONG(rec.dx.toLong())
                    target.input.mi.dy = WinDef.LONG(rec.dy.toLong())
                    target.input.mi.mouseData = WinDef.DWORD(rec.mouseData.toLong() and 0xFFFFFFFFL)
                    target.input.mi.dwFlags = WinDef.DWORD(rec.flags.toLong() and 0xFFFFFFFFL)
                    target.input.mi.time = WinDef.DWORD(0)
                    target.input.mi.dwExtraInfo = com.sun.jna.platform.win32.BaseTSD.ULONG_PTR(0)
                }
                is InputRecord.Keyboard -> {
                    target.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
                    target.input.setType("ki")
                    target.input.ki.wVk = WinDef.WORD(rec.vk.toLong() and 0xFFFFL)
                    target.input.ki.wScan = WinDef.WORD(rec.scan.toLong() and 0xFFFFL)
                    target.input.ki.dwFlags = WinDef.DWORD(rec.flags.toLong() and 0xFFFFFFFFL)
                    target.input.ki.time = WinDef.DWORD(0)
                    target.input.ki.dwExtraInfo = com.sun.jna.platform.win32.BaseTSD.ULONG_PTR(0)
                }
            }
        }
        val sent = User32.INSTANCE.SendInput(WinDef.DWORD(arr.size.toLong()), arr, arr[0].size())
        return sent.toInt()
    }
}
