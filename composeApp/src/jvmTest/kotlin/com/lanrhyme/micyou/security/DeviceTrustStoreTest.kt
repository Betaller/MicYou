package com.lanrhyme.micyou.security

import com.lanrhyme.micyou.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceTrustStoreTest {

    private class InMemorySettings : Settings {
        private val map = HashMap<String, Any?>()
        override fun getString(key: String, defaultValue: String): String =
            map[key] as? String ?: defaultValue
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

    @Test
    fun trustDevice_persistsAndAuthorizes() {
        var now = 0L
        var counter = 0
        val store = DeviceTrustStore(
            settings = InMemorySettings(),
            nowMillis = { now },
            tokenGenerator = { "tok-${counter++}" }
        )
        val device = store.trustDevice("fp-A", "Pixel")
        assertEquals("tok-0", device.token)
        assertTrue(store.isAuthorized("fp-A", "tok-0"))
    }

    @Test
    fun unknownFingerprint_evenWithValidToken_isRejected() {
        val store = DeviceTrustStore(InMemorySettings())
        store.trustDevice("fp-A", "A")
        assertFalse(store.isAuthorized("fp-B", store.list().single().token))
    }

    @Test
    fun changedFingerprint_requiresNewPin() {
        val store = DeviceTrustStore(InMemorySettings())
        store.trustDevice("fp-A", "A")
        // Different fp → unknown to store
        assertFalse(store.isAuthorized("fp-B", "any-token"))
    }

    @Test
    fun trustedDeviceList_persistsAcrossInstances() {
        val settings = InMemorySettings()
        DeviceTrustStore(settings).trustDevice("fp-A", "A")
        val reopened = DeviceTrustStore(settings)
        assertEquals(1, reopened.list().size)
        assertEquals("fp-A", reopened.list().single().fingerprint)
    }

    @Test
    fun revokeDevice_removesFromTrustList_andInvalidatesToken() {
        val store = DeviceTrustStore(InMemorySettings())
        val d = store.trustDevice("fp-A", "A")
        store.revoke("fp-A")
        assertTrue(store.list().isEmpty())
        assertFalse(store.isAuthorized("fp-A", d.token))
    }

    @Test
    fun expiredToken_isRejected() {
        var now = 0L
        val store = DeviceTrustStore(
            InMemorySettings(),
            tokenTtlMillis = 1000L,
            nowMillis = { now }
        )
        val d = store.trustDevice("fp-A", "A")
        assertTrue(store.isAuthorized("fp-A", d.token))
        now += 2000L
        assertFalse(store.isAuthorized("fp-A", d.token))
    }

    @Test
    fun fingerprintHash_isStableAndSalted() {
        val a = DeviceTrustStore.hashFingerprint("android-id-x", "salt-1")
        val b = DeviceTrustStore.hashFingerprint("android-id-x", "salt-1")
        val c = DeviceTrustStore.hashFingerprint("android-id-x", "salt-2")
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
