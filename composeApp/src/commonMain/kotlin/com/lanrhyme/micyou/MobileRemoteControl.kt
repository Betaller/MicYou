package com.lanrhyme.micyou

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.input.GestureCommand
import com.lanrhyme.micyou.input.GestureRecognizer
import com.lanrhyme.micyou.input.PointerEvent as InputPointerEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.stringResource
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.remoteControlConnectionDisconnected
import micyou.composeapp.generated.resources.remoteControlSensitivityLabel
import micyou.composeapp.generated.resources.remoteControlTitle
import micyou.composeapp.generated.resources.remoteControlTouchpadHint

@Composable
fun MobileRemoteControlScreen(viewModel: RemoteInputViewModel) {
    val connection by viewModel.connectionState.collectAsState()
    var sensitivity by remember { mutableStateOf(1.0f) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                text = stringResource(Res.string.remoteControlTitle),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (connection) {
                    RemoteInputConnectionState.Connected -> "•"
                    RemoteInputConnectionState.Disconnected -> stringResource(Res.string.remoteControlConnectionDisconnected)
                    RemoteInputConnectionState.Disabled -> stringResource(Res.string.remoteControlConnectionDisconnected)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.remoteControlSensitivityLabel))
                Spacer(Modifier.weight(1f))
                Text(formatSensitivity(sensitivity))
            }
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                valueRange = 0.5f..3.0f
            )

            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    TouchpadSurface(
                        modifier = Modifier.fillMaxSize(),
                        sensitivity = { sensitivity },
                        onCommand = { cmd -> dispatch(viewModel, cmd, sensitivity) }
                    )
                    Text(
                        text = stringResource(Res.string.remoteControlTouchpadHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun formatSensitivity(s: Float): String =
    "${(s * 10).toInt() / 10.0f}x"

private fun dispatch(vm: RemoteInputViewModel, cmd: GestureCommand, sensitivity: Float) {
    when (cmd) {
        is GestureCommand.MouseMove -> vm.sendMouseMove(
            (cmd.dx * sensitivity).toInt(),
            (cmd.dy * sensitivity).toInt()
        )
        is GestureCommand.MouseClick -> {
            vm.sendMouseButton(cmd.button, pressed = true)
            vm.sendMouseButton(cmd.button, pressed = false)
        }
        is GestureCommand.MouseDoubleClick -> repeat(2) {
            vm.sendMouseButton(cmd.button, pressed = true)
            vm.sendMouseButton(cmd.button, pressed = false)
        }
        is GestureCommand.Wheel -> vm.sendWheel(cmd.notches)
    }
}

@Composable
private fun TouchpadSurface(
    modifier: Modifier,
    sensitivity: () -> Float,
    onCommand: (GestureCommand) -> Unit
) {
    val recognizer = remember { GestureRecognizer() }
    var anyDown by remember { mutableStateOf(false) }

    // Drive long-press tick while a pointer is down
    LaunchedEffect(anyDown) {
        if (!anyDown) return@LaunchedEffect
        while (isActive && anyDown) {
            recognizer.onEvent(InputPointerEvent.Tick(currentTimeMs()), onCommand)
            delay(50)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val now = currentTimeMs()
                        for (change in event.changes) {
                            val id = change.id.value
                            val x = change.position.x
                            val y = change.position.y
                            when (event.type) {
                                PointerEventType.Press -> {
                                    anyDown = true
                                    recognizer.onEvent(InputPointerEvent.Down(id, x, y, now), onCommand)
                                }
                                PointerEventType.Release -> {
                                    recognizer.onEvent(InputPointerEvent.Up(id, x, y, now), onCommand)
                                }
                                PointerEventType.Move -> {
                                    if (change.pressed) {
                                        recognizer.onEvent(InputPointerEvent.Move(id, x, y, now), onCommand)
                                    }
                                }
                            }
                            change.consume()
                        }
                        if (event.changes.none { it.pressed }) {
                            anyDown = false
                            break
                        }
                    }
                }
            }
    )
}

private fun currentTimeMs(): Long {
    @OptIn(kotlin.time.ExperimentalTime::class)
    return kotlin.time.Clock.System.now().toEpochMilliseconds()
}
