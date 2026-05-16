package com.lanrhyme.micyou

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmSmokeTest {
    @Test
    fun jvmTestRunner_isWiredUp() {
        assertEquals(2, 1 + 1)
    }
}
