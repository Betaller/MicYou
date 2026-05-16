package com.lanrhyme.micyou

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class ProtocolTest {

    // === Legacy wrapper containing only fields 1..6 — used to verify forward compatibility ===
    @Serializable
    private data class LegacyMessageWrapper(
        @ProtoNumber(1) val audioPacket: AudioPacketMessageOrdered? = null,
        @ProtoNumber(2) val connect: ConnectMessage? = null,
        @ProtoNumber(3) val mute: MuteMessage? = null,
        @ProtoNumber(4) val pluginSync: PluginSyncMessage? = null,
        @ProtoNumber(5) val ping: PingMessage? = null,
        @ProtoNumber(6) val pong: PongMessage? = null
    )

    @Test
    fun mouseMessage_roundTrip_preservesAllFields() {
        val original = MouseEventMessage(
            type = MouseEventType.WHEEL,
            dx = 12,
            dy = -34,
            button = MouseButton.RIGHT,
            wheelDelta = 240
        )
        val bytes = ProtoBuf.encodeToByteArray(original)
        val decoded = ProtoBuf.decodeFromByteArray<MouseEventMessage>(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun keyMessage_unicodeText_roundTrip() {
        val original = KeyEventMessage(
            type = KeyEventType.TEXT,
            text = "你好 Hello 😀🎉"
        )
        val bytes = ProtoBuf.encodeToByteArray(original)
        val decoded = ProtoBuf.decodeFromByteArray<KeyEventMessage>(bytes)
        assertEquals(original.text, decoded.text)
        assertEquals(KeyEventType.TEXT, decoded.type)
    }

    @Test
    fun inputAuthMessage_roundTrip() {
        val original = InputAuthMessage(
            pin = "123456",
            token = "tok-abc",
            deviceFingerprint = "fp-xyz",
            deviceName = "Pixel 8"
        )
        val bytes = ProtoBuf.encodeToByteArray(original)
        val decoded = ProtoBuf.decodeFromByteArray<InputAuthMessage>(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun messageWrapper_oldClient_canDecodeNewMessage_withoutMouseField() {
        // Old client (pre-1.4) only knows fields 1..6. A wrapper produced by a new client carrying mouse=...
        // should still be parseable by the legacy schema, with new fields silently dropped.
        val newWrapper = MessageWrapper(
            mouse = MouseEventMessage(type = MouseEventType.MOVE_RELATIVE, dx = 1, dy = 2),
            ping = PingMessage(timestamp = 42L)
        )
        val bytes = ProtoBuf.encodeToByteArray(newWrapper)
        val legacy = ProtoBuf.decodeFromByteArray<LegacyMessageWrapper>(bytes)
        assertEquals(42L, legacy.ping?.timestamp)
        assertNull(legacy.mute)
    }

    @Test
    fun messageWrapper_newClient_canDecodeOldServerWrapper() {
        val legacy = LegacyMessageWrapper(mute = MuteMessage(isMuted = true))
        val bytes = ProtoBuf.encodeToByteArray(legacy)
        val parsed = ProtoBuf.decodeFromByteArray<MessageWrapper>(bytes)
        assertEquals(true, parsed.mute?.isMuted)
        assertNull(parsed.mouse)
        assertNull(parsed.key)
        assertNull(parsed.inputAuth)
    }

    @Test
    fun hasControlMessage_returnsTrueForMouse_andKey_andInputAuth() {
        assertTrue(MessageWrapper(mouse = MouseEventMessage(type = MouseEventType.MOVE_RELATIVE)).hasControlMessage())
        assertTrue(MessageWrapper(key = KeyEventMessage(type = KeyEventType.KEY_DOWN, keyCode = 65)).hasControlMessage())
        assertTrue(MessageWrapper(inputAuth = InputAuthMessage(pin = "111111")).hasControlMessage())
    }

    @Test
    fun hasControlMessage_returnsFalseForAudioOnly() {
        val audioOnly = MessageWrapper(
            audioPacket = AudioPacketMessageOrdered(
                sequenceNumber = 0,
                audioPacket = AudioPacketMessage(
                    buffer = ByteArray(0),
                    sampleRate = 48000,
                    channelCount = 1,
                    audioFormat = 16
                )
            )
        )
        assertFalse(audioOnly.hasControlMessage())
    }

    @Test
    fun mouseMessage_defaultValues_keepPayloadCompact() {
        val minimal = MouseEventMessage(type = MouseEventType.BUTTON_DOWN, button = MouseButton.LEFT)
        val bytes = ProtoBuf.encodeToByteArray(minimal)
        // type=1 + button=1 → small payload; assert we don't accidentally serialize zero defaults that bloat.
        // Protobuf default: omitted-when-default → expect <= 6 bytes (2 varint pairs).
        assertTrue(bytes.size <= 6, "expected compact encoding, got ${bytes.size} bytes")
    }
}
