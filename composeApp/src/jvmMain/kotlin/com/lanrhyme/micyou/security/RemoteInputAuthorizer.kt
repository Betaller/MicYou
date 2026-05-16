package com.lanrhyme.micyou.security

import com.lanrhyme.micyou.InputAuthMessage
import com.lanrhyme.micyou.Logger

/**
 * 把 [PinAuthenticator] + [DeviceTrustStore] 串起来，处理 InputAuthMessage 握手。
 * 状态机：客户端先发 PIN → 通过后下发 token & fingerprint 绑定；之后客户端每个连接
 * 必须发一次 token + fingerprint 才能解锁鼠标/键盘消息。
 */
class RemoteInputAuthorizer(
    private val pin: PinAuthenticator,
    private val trust: DeviceTrustStore
) {
    @Volatile
    private var authorizedFingerprint: String? = null

    val isAuthorized: Boolean get() = authorizedFingerprint != null

    fun handle(message: InputAuthMessage): HandshakeResult {
        val fingerprint = message.deviceFingerprint?.takeIf { it.isNotBlank() }
            ?: return HandshakeResult.Rejected("missing fingerprint")

        // Token path: trusted device returning
        message.token?.takeIf { it.isNotBlank() }?.let { token ->
            return if (trust.isAuthorized(fingerprint, token)) {
                authorizedFingerprint = fingerprint
                Logger.i(TAG, "authorized via token (fp=${fingerprint.take(8)}…)")
                HandshakeResult.AuthorizedExisting
            } else {
                Logger.w(TAG, "token rejected for fp=${fingerprint.take(8)}…")
                HandshakeResult.Rejected("invalid or expired token")
            }
        }

        // PIN path: first-time pairing
        val candidate = message.pin?.takeIf { it.isNotBlank() }
            ?: return HandshakeResult.Rejected("missing pin")
        return when (val r = pin.verify(candidate)) {
            PinAuthenticator.Result.Accepted -> {
                val device = trust.trustDevice(fingerprint, message.deviceName ?: "unknown")
                authorizedFingerprint = fingerprint
                HandshakeResult.AuthorizedNew(device.token, device.tokenExpiryMillis)
            }
            PinAuthenticator.Result.Rejected -> HandshakeResult.Rejected("pin rejected")
            PinAuthenticator.Result.NotConfigured -> HandshakeResult.Rejected("pin not configured")
            is PinAuthenticator.Result.LockedOut -> HandshakeResult.LockedOut(r.remainingMillis)
        }
    }

    fun revokeCurrentSession() {
        authorizedFingerprint = null
    }

    sealed class HandshakeResult {
        object AuthorizedExisting : HandshakeResult()
        data class AuthorizedNew(val token: String, val expiryMillis: Long) : HandshakeResult()
        data class Rejected(val reason: String) : HandshakeResult()
        data class LockedOut(val remainingMillis: Long) : HandshakeResult()
    }

    companion object { private const val TAG = "RemoteInputAuthorizer" }
}
