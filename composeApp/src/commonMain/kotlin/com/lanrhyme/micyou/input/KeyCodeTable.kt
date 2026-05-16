package com.lanrhyme.micyou.input

/**
 * 跨平台键码常量。命名遵循 Win32 VK_*；commonMain 不直接依赖 Win32 头文件，
 * 仅用整数值以便 Protobuf 自由序列化。
 */
object KeyCodeTable {
    const val VK_BACK = 0x08
    const val VK_TAB = 0x09
    const val VK_RETURN = 0x0D
    const val VK_ESCAPE = 0x1B
    const val VK_SPACE = 0x20

    const val VK_PRIOR = 0x21 // PageUp
    const val VK_NEXT = 0x22 // PageDown
    const val VK_END = 0x23
    const val VK_HOME = 0x24
    const val VK_LEFT = 0x25
    const val VK_UP = 0x26
    const val VK_RIGHT = 0x27
    const val VK_DOWN = 0x28
    const val VK_PRINT_SCREEN = 0x2C
    const val VK_INSERT = 0x2D
    const val VK_DELETE = 0x2E

    const val VK_LWIN = 0x5B
    const val VK_RWIN = 0x5C

    // F1..F12
    const val VK_F1 = 0x70
    const val VK_F2 = 0x71
    const val VK_F3 = 0x72
    const val VK_F4 = 0x73
    const val VK_F5 = 0x74
    const val VK_F6 = 0x75
    const val VK_F7 = 0x76
    const val VK_F8 = 0x77
    const val VK_F9 = 0x78
    const val VK_F10 = 0x79
    const val VK_F11 = 0x7A
    const val VK_F12 = 0x7B
}
