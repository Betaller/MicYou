package com.lanrhyme.micyou.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PinAuthenticatorTest {

    private class FakeClock(var t: Long = 0L) {
        fun get(): Long = t
        fun advance(ms: Long) { t += ms }
    }

    @Test
    fun acceptedPin_resetsFailureCount() {
        val auth = PinAuthenticator()
        auth.rotatePin("123456")
        repeat(2) { assertEquals(PinAuthenticator.Result.Rejected, auth.verify("000000")) }
        assertEquals(PinAuthenticator.Result.Accepted, auth.verify("123456"))
        // After accepted, two more failures should not lock out
        repeat(2) { assertEquals(PinAuthenticator.Result.Rejected, auth.verify("000000")) }
    }

    @Test
    fun threeFailures_lockOut_lasts5min() {
        val clock = FakeClock()
        val auth = PinAuthenticator(maxFailures = 3, lockoutMillis = 5 * 60 * 1000L, nowMillis = clock::get)
        auth.rotatePin("123456")
        assertEquals(PinAuthenticator.Result.Rejected, auth.verify("0"))
        assertEquals(PinAuthenticator.Result.Rejected, auth.verify("0"))
        val locked = auth.verify("0")
        assertTrue(locked is PinAuthenticator.Result.LockedOut)
        // Even valid PIN rejected during lockout
        val stillLocked = auth.verify("123456")
        assertTrue(stillLocked is PinAuthenticator.Result.LockedOut)
        clock.advance(5 * 60 * 1000L + 1)
        assertEquals(PinAuthenticator.Result.Accepted, auth.verify("123456"))
    }

    @Test
    fun rotatePin_rejectsNonNumeric() {
        val auth = PinAuthenticator()
        assertFailsWith<IllegalArgumentException> { auth.rotatePin("abcdef") }
    }

    @Test
    fun verifyBeforeRotate_returnsNotConfigured() {
        val auth = PinAuthenticator()
        assertEquals(PinAuthenticator.Result.NotConfigured, auth.verify("anything"))
    }
}
