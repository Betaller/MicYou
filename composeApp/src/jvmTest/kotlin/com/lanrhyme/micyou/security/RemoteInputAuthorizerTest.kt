package com.lanrhyme.micyou.security

import com.lanrhyme.micyou.InputAuthMessage
import com.lanrhyme.micyou.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteInputAuthorizerTest {

    private class InMemorySettings : Settings {
        private val map = HashMap<String, Any?>()
        override fun getString(key: String, defaultValue: String): String = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getLong(key: String, defaultValue: Long): Long = map[key] as? Long ?: defaultValue
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getInt(key: String, defaultValue: Int): Int = map[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getFloat(key: String, defaultValue: Float): Float = map[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) { map[key] = value }
    }

    private fun newAuth(): RemoteInputAuthorizer {
        val pin = PinAuthenticator()
        pin.rotatePin("123456")
        return RemoteInputAuthorizer(pin, DeviceTrustStore(InMemorySettings()))
    }

    @Test
    fun clientWithoutPin_isRejected() {
        val auth = newAuth()
        val r = auth.handle(InputAuthMessage(deviceFingerprint = "fp-A"))
        assertTrue(r is RemoteInputAuthorizer.HandshakeResult.Rejected)
    }

    @Test
    fun clientWithWrongPin_isRejected() {
        val auth = newAuth()
        val r = auth.handle(InputAuthMessage(pin = "000000", deviceFingerprint = "fp-A"))
        assertTrue(r is RemoteInputAuthorizer.HandshakeResult.Rejected)
        assertFalse(auth.isAuthorized)
    }

    @Test
    fun clientWithValidPin_receivesToken_andIsAuthorized() {
        val auth = newAuth()
        val r = auth.handle(
            InputAuthMessage(pin = "123456", deviceFingerprint = "fp-A", deviceName = "Pixel")
        )
        assertTrue(r is RemoteInputAuthorizer.HandshakeResult.AuthorizedNew)
        assertTrue(auth.isAuthorized)
    }

    @Test
    fun returningClient_authorizesViaToken() {
        val pin = PinAuthenticator(); pin.rotatePin("123456")
        val store = DeviceTrustStore(InMemorySettings())
        val first = RemoteInputAuthorizer(pin, store)
        val pair = first.handle(InputAuthMessage(pin = "123456", deviceFingerprint = "fp-A", deviceName = "Pixel"))
        require(pair is RemoteInputAuthorizer.HandshakeResult.AuthorizedNew)
        // New connection re-uses token
        val second = RemoteInputAuthorizer(pin, store)
        val r = second.handle(InputAuthMessage(token = pair.token, deviceFingerprint = "fp-A"))
        assertEquals(RemoteInputAuthorizer.HandshakeResult.AuthorizedExisting, r)
    }

    @Test
    fun anotherDeviceWithStolenToken_isRejected() {
        val pin = PinAuthenticator(); pin.rotatePin("123456")
        val store = DeviceTrustStore(InMemorySettings())
        val first = RemoteInputAuthorizer(pin, store)
        val pair = first.handle(InputAuthMessage(pin = "123456", deviceFingerprint = "fp-A", deviceName = "Pixel"))
        require(pair is RemoteInputAuthorizer.HandshakeResult.AuthorizedNew)
        val attacker = RemoteInputAuthorizer(pin, store)
        // Attacker presents A's token but their own fingerprint
        val r = attacker.handle(InputAuthMessage(token = pair.token, deviceFingerprint = "fp-attacker"))
        assertTrue(r is RemoteInputAuthorizer.HandshakeResult.Rejected)
    }
}
