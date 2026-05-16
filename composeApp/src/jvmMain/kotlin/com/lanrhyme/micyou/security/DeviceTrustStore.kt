package com.lanrhyme.micyou.security

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * 已信任设备记录：fingerprint → 备注名 + 首次配对时间 + 当前 token。
 * 持久化通过通用 [Settings] 接口走的是简单 KV 序列化（行格式 fp|name|ts|token|expiry）；
 * 上层不应直接操作这些 key——所有访问都经本类。
 */
class DeviceTrustStore(
    private val settings: Settings,
    private val tokenTtlMillis: Long = 24L * 60 * 60 * 1000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val tokenGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    data class TrustedDevice(
        val fingerprint: String,
        val name: String,
        val firstSeenMillis: Long,
        val token: String,
        val tokenExpiryMillis: Long
    )

    private val lock = Any()

    /** 通过 PIN 后调用：登记并下发 token。 */
    fun trustDevice(fingerprint: String, name: String): TrustedDevice {
        require(fingerprint.isNotBlank())
        synchronized(lock) {
            val now = nowMillis()
            val token = tokenGenerator()
            val device = TrustedDevice(
                fingerprint = fingerprint,
                name = name,
                firstSeenMillis = list().firstOrNull { it.fingerprint == fingerprint }?.firstSeenMillis ?: now,
                token = token,
                tokenExpiryMillis = now + tokenTtlMillis
            )
            persist(list().filterNot { it.fingerprint == fingerprint } + device)
            Logger.i(TAG, "trusted device added: ${device.name} (fp=${device.fingerprint.take(8)}…)")
            return device
        }
    }

    fun isAuthorized(fingerprint: String, token: String): Boolean {
        synchronized(lock) {
            val device = list().firstOrNull { it.fingerprint == fingerprint } ?: return false
            if (device.token != token) return false
            if (nowMillis() > device.tokenExpiryMillis) return false
            return true
        }
    }

    fun revoke(fingerprint: String) {
        synchronized(lock) {
            val before = list()
            persist(before.filterNot { it.fingerprint == fingerprint })
            if (before.size != list().size) Logger.i(TAG, "revoked fp=${fingerprint.take(8)}…")
        }
    }

    fun list(): List<TrustedDevice> {
        val raw = settings.getString(KEY, "")
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 5) return@mapNotNull null
            try {
                TrustedDevice(
                    fingerprint = parts[0],
                    name = parts[1],
                    firstSeenMillis = parts[2].toLong(),
                    token = parts[3],
                    tokenExpiryMillis = parts[4].toLong()
                )
            } catch (e: Exception) { null }
        }
    }

    private fun persist(devices: List<TrustedDevice>) {
        val encoded = devices.joinToString("\n") {
            "${it.fingerprint}|${it.name}|${it.firstSeenMillis}|${it.token}|${it.tokenExpiryMillis}"
        }
        settings.putString(KEY, encoded)
    }

    companion object {
        private const val KEY = "remoteInput.trustedDevices"
        private const val TAG = "DeviceTrustStore"

        /** 把任意标识哈希成稳定的设备指纹；客户端侧也用同样算法。 */
        fun hashFingerprint(rawId: String, salt: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(salt.toByteArray(Charsets.UTF_8))
            md.update(rawId.toByteArray(Charsets.UTF_8))
            return md.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }
}
