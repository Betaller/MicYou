package com.lanrhyme.micyou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RemoteInputConnectionState { Disconnected, Connected, Disabled }

/**
 * 把发送 [MessageWrapper] 的副作用抽象出来，便于在 commonMain 写 VM、
 * 在 androidMain 注入真实 socket、在测试里注入 fake。
 */
interface RemoteInputTransport {
    suspend fun send(message: MessageWrapper)
    val connectionState: StateFlow<RemoteInputConnectionState>
}

/** 默认空实现：未配置 transport 时所有发送都是 no-op。 */
object NoopRemoteInputTransport : RemoteInputTransport {
    private val _state = MutableStateFlow(RemoteInputConnectionState.Disconnected)
    override val connectionState: StateFlow<RemoteInputConnectionState> = _state.asStateFlow()
    override suspend fun send(message: MessageWrapper) {}
}

/** 把 AudioEngine 的现有 TCP socket 当作远程输入通道。 */
class AudioEngineRemoteInputTransport(
    private val audioEngine: AudioEngine,
    scope: kotlinx.coroutines.CoroutineScope
) : RemoteInputTransport {
    private val _state = MutableStateFlow(RemoteInputConnectionState.Disconnected)
    override val connectionState: StateFlow<RemoteInputConnectionState> = _state.asStateFlow()

    init {
        scope.launch {
            audioEngine.streamState.collect { s ->
                _state.value = when (s) {
                    StreamState.Streaming -> RemoteInputConnectionState.Connected
                    else -> RemoteInputConnectionState.Disconnected
                }
            }
        }
    }

    override suspend fun send(message: MessageWrapper) {
        audioEngine.trySendRemoteInput(message)
    }
}

/**
 * Android 端远程键鼠 VM。
 * - mouseMove 在 16ms 窗口内合并（约 60 Hz），减少网络帧数
 * - 按键 / 文本 / 滚轮 / 按钮立即发送，不参与合并
 * - 未连接时所有 send 走 no-op
 */
class RemoteInputViewModel(
    transportProvider: () -> RemoteInputTransport = { NoopRemoteInputTransport },
    private val coalesceMillis: Long = 16L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    @Volatile
    private var transportSupplier: () -> RemoteInputTransport = transportProvider

    fun setTransport(transport: RemoteInputTransport) {
        transportSupplier = { transport }
    }

    val connectionState: StateFlow<RemoteInputConnectionState>
        get() = transportSupplier().connectionState

    private val pendingMoveMutex = Mutex()
    private var pendingDx: Int = 0
    private var pendingDy: Int = 0
    private var flushJob: Job? = null

    fun sendMouseMove(dx: Int, dy: Int) {
        if (!isLive()) return
        viewModelScope.launch(ioDispatcher) {
            pendingMoveMutex.withLock {
                pendingDx += dx
                pendingDy += dy
                if (flushJob == null || flushJob?.isActive == false) {
                    flushJob = viewModelScope.launch(ioDispatcher) {
                        delay(coalesceMillis)
                        flushPendingMove()
                    }
                }
            }
        }
    }

    private suspend fun flushPendingMove() {
        val (dx, dy) = pendingMoveMutex.withLock {
            val v = pendingDx to pendingDy
            pendingDx = 0
            pendingDy = 0
            flushJob = null
            v
        }
        if (dx == 0 && dy == 0) return
        send(MessageWrapper(mouse = MouseEventMessage(type = MouseEventType.MOVE_RELATIVE, dx = dx, dy = dy)))
    }

    fun sendMouseButton(button: Int, pressed: Boolean) {
        if (!isLive()) return
        val type = if (pressed) MouseEventType.BUTTON_DOWN else MouseEventType.BUTTON_UP
        viewModelScope.launch(ioDispatcher) {
            send(MessageWrapper(mouse = MouseEventMessage(type = type, button = button)))
        }
    }

    fun sendWheel(notches: Int) {
        if (!isLive() || notches == 0) return
        viewModelScope.launch(ioDispatcher) {
            send(MessageWrapper(mouse = MouseEventMessage(type = MouseEventType.WHEEL, wheelDelta = notches)))
        }
    }

    fun sendKeyEvent(vk: Int, modifiers: Int, pressed: Boolean) {
        if (!isLive()) return
        val type = if (pressed) KeyEventType.KEY_DOWN else KeyEventType.KEY_UP
        viewModelScope.launch(ioDispatcher) {
            send(MessageWrapper(key = KeyEventMessage(type = type, keyCode = vk, modifiers = modifiers)))
        }
    }

    fun sendUnicodeText(text: String) {
        if (!isLive() || text.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            send(MessageWrapper(key = KeyEventMessage(type = KeyEventType.TEXT, text = text)))
        }
    }

    private suspend fun send(message: MessageWrapper) {
        try {
            transportSupplier().send(message)
        } catch (t: Throwable) {
            Logger.w(TAG, "remote input send failed: ${t.message}")
        }
    }

    private fun isLive(): Boolean =
        transportSupplier().connectionState.value == RemoteInputConnectionState.Connected

    companion object {
        private const val TAG = "RemoteInputViewModel"
    }
}
