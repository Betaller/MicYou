package com.lanrhyme.micyou

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AudioPacketMessage(
    @ProtoNumber(1)
    val buffer: ByteArray,
    @ProtoNumber(2)
    val sampleRate: Int,
    @ProtoNumber(3)
    val channelCount: Int,
    @ProtoNumber(4)
    val audioFormat: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as AudioPacketMessage

        if (!buffer.contentEquals(other.buffer)) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (audioFormat != other.audioFormat) return false

        return true
    }

    override fun hashCode(): Int {
        var result = buffer.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + audioFormat
        return result
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AudioPacketMessageOrdered(
    @ProtoNumber(1)
    val sequenceNumber: Int,
    @ProtoNumber(2)
    val audioPacket: AudioPacketMessage,
    @ProtoNumber(3)
    val timestamp: Long = 0,
    @ProtoNumber(4)
    val fecBuffer: ByteArray? = null,
    @ProtoNumber(5)
    val fecSequenceNumber: Int = -1
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MuteMessage(
    @ProtoNumber(1)
    val isMuted: Boolean
)

@Serializable
class ConnectMessage

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PluginInfoMessage(
    @ProtoNumber(1)
    val id: String,
    @ProtoNumber(2)
    val name: String,
    @ProtoNumber(3)
    val version: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PluginSyncMessage(
    @ProtoNumber(1)
    val plugins: List<PluginInfoMessage> = emptyList(),
    @ProtoNumber(2)
    val platform: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PingMessage(
    @ProtoNumber(1)
    val timestamp: Long
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PongMessage(
    @ProtoNumber(1)
    val timestamp: Long
)

const val PACKET_MAGIC = 0x4D696359 // "MicY" in ASCII
const val UDP_PACKET_MAGIC = 0x4D696355 // "MicU" in ASCII

/** UDP 端口 = TCP 端口 + 1 */
const val UDP_PORT_OFFSET = 1

/**
 * 计算 UDP 端口，带边界校验防止端口溢出。
 * @param tcpPort TCP 端口号
 * @return UDP 端口号
 * @throws IllegalArgumentException 当计算结果超出有效端口范围 (0-65535)
 */
fun calculateUdpPort(tcpPort: Int): Int {
    val udpPort = tcpPort + UDP_PORT_OFFSET
    if (udpPort !in 0..65535) {
        throw IllegalArgumentException("UDP 端口溢出: TCP 端口 $tcpPort + 偏移量 $UDP_PORT_OFFSET = $udpPort，超出有效范围 0-65535")
    }
    return udpPort
}

/** 判断 MessageWrapper 是否包含控制消息（应通过 TCP 发送） */
fun MessageWrapper.hasControlMessage(): Boolean {
    return connect != null || mute != null || pluginSync != null || ping != null || pong != null ||
            mouse != null || key != null || inputAuth != null
}

/** 鼠标事件类型常量。Protobuf 用整数序列化，避免枚举的 wire format 假设。 */
object MouseEventType {
    const val MOVE_RELATIVE = 0
    const val BUTTON_DOWN = 1
    const val BUTTON_UP = 2
    const val WHEEL = 3
    const val MOVE_ABSOLUTE = 4
}

object MouseButton {
    const val LEFT = 1
    const val RIGHT = 2
    const val MIDDLE = 3
}

object KeyEventType {
    const val KEY_DOWN = 0
    const val KEY_UP = 1
    const val TEXT = 2
}

/** 修饰键 bitmask；与 Win32 VK 修饰位语义对齐但定义在 commonMain 保持平台中立 */
object ModifierMask {
    const val NONE = 0
    const val SHIFT = 1 shl 0
    const val CTRL = 1 shl 1
    const val ALT = 1 shl 2
    const val META = 1 shl 3 // Win / Cmd
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MouseEventMessage(
    @ProtoNumber(1)
    val type: Int,
    @ProtoNumber(2)
    val dx: Int = 0,
    @ProtoNumber(3)
    val dy: Int = 0,
    @ProtoNumber(4)
    val button: Int = 0,
    @ProtoNumber(5)
    val wheelDelta: Int = 0
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class KeyEventMessage(
    @ProtoNumber(1)
    val type: Int,
    @ProtoNumber(2)
    val keyCode: Int = 0,
    @ProtoNumber(3)
    val modifiers: Int = 0,
    @ProtoNumber(4)
    val text: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InputAuthMessage(
    @ProtoNumber(1)
    val pin: String? = null,
    @ProtoNumber(2)
    val token: String? = null,
    @ProtoNumber(3)
    val deviceFingerprint: String? = null,
    @ProtoNumber(4)
    val deviceName: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageWrapper(
    @ProtoNumber(1)
    val audioPacket: AudioPacketMessageOrdered? = null,
    @ProtoNumber(2)
    val connect: ConnectMessage? = null,
    @ProtoNumber(3)
    val mute: MuteMessage? = null,
    @ProtoNumber(4)
    val pluginSync: PluginSyncMessage? = null,
    @ProtoNumber(5)
    val ping: PingMessage? = null,
    @ProtoNumber(6)
    val pong: PongMessage? = null,
    @ProtoNumber(7)
    val mouse: MouseEventMessage? = null,
    @ProtoNumber(8)
    val key: KeyEventMessage? = null,
    @ProtoNumber(9)
    val inputAuth: InputAuthMessage? = null
)

