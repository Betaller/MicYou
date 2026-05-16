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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.lanrhyme.micyou.ModifierMask
import com.lanrhyme.micyou.input.GestureCommand
import com.lanrhyme.micyou.input.GestureRecognizer
import com.lanrhyme.micyou.input.KeyCodeTable
import com.lanrhyme.micyou.input.KeyComboTracker
import com.lanrhyme.micyou.input.PointerEvent as InputPointerEvent
import com.lanrhyme.micyou.input.TextDiff
import com.lanrhyme.micyou.input.TextDiffResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.stringResource
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.remoteControlConnectionDisconnected
import micyou.composeapp.generated.resources.remoteControlKeyboardOpen
import micyou.composeapp.generated.resources.remoteControlKeyboardClose
import micyou.composeapp.generated.resources.remoteControlKeyboardHint
import micyou.composeapp.generated.resources.remoteControlPhysicalKeysLabel
import micyou.composeapp.generated.resources.remoteControlSensitivityLabel
import micyou.composeapp.generated.resources.remoteControlTitle
import micyou.composeapp.generated.resources.remoteControlTouchpadHint

@Composable
fun MobileRemoteControlScreen(viewModel: RemoteInputViewModel, onBack: () -> Unit = {}) {
    val connection by viewModel.connectionState.collectAsState()
    var sensitivity by remember { mutableStateOf(1.0f) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null
                    )
                }
                Text(
                    text = stringResource(Res.string.remoteControlTitle),
                    style = MaterialTheme.typography.titleLarge
                )
            }
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

            Spacer(Modifier.height(12.dp))

            KeyboardSection(viewModel = viewModel)
        }
    }
}

@Composable
private fun KeyboardSection(viewModel: RemoteInputViewModel) {
    var keyboardOpen by remember { mutableStateOf(false) }
    val tracker = remember { KeyComboTracker() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { keyboardOpen = !keyboardOpen }) {
            Icon(
                imageVector = if (keyboardOpen) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                contentDescription = null
            )
            Spacer(Modifier.height(0.dp))
            Text(
                text = stringResource(
                    if (keyboardOpen) Res.string.remoteControlKeyboardClose
                    else Res.string.remoteControlKeyboardOpen
                )
            )
        }
    }

    if (keyboardOpen) {
        Spacer(Modifier.height(8.dp))
        SoftKeyboardField(viewModel = viewModel)
    }

    Spacer(Modifier.height(8.dp))
    PhysicalKeyPanel(tracker = tracker, onSendStep = { vk, mods, pressed ->
        viewModel.sendKeyEvent(vk = vk, modifiers = mods, pressed = pressed)
    })
}

@Composable
private fun SoftKeyboardField(viewModel: RemoteInputViewModel) {
    var lastSent by remember { mutableStateOf("") }
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BasicTextField(
        value = fieldValue,
        onValueChange = { newValue ->
            val newText = newValue.text
            when (val diff = TextDiff.diff(lastSent, newText)) {
                is TextDiffResult.Edit -> {
                    repeat(diff.backspaces) {
                        viewModel.sendKeyEvent(KeyCodeTable.VK_BACK, ModifierMask.NONE, pressed = true)
                        viewModel.sendKeyEvent(KeyCodeTable.VK_BACK, ModifierMask.NONE, pressed = false)
                    }
                    if (diff.insert.isNotEmpty()) viewModel.sendUnicodeText(diff.insert)
                }
                TextDiffResult.Noop -> {}
            }
            lastSent = newText
            fieldValue = newValue
        },
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .focusRequester(focusRequester)
    )
    Text(
        text = stringResource(Res.string.remoteControlKeyboardHint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
}

private data class PhysicalKey(val label: String, val vk: Int, val modifier: Int = 0)

@Composable
private fun PhysicalKeyPanel(
    tracker: KeyComboTracker,
    onSendStep: (vk: Int, modifiers: Int, pressed: Boolean) -> Unit
) {
    val keys = remember {
        listOf(
            PhysicalKey("Esc", KeyCodeTable.VK_ESCAPE),
            PhysicalKey("Tab", KeyCodeTable.VK_TAB),
            PhysicalKey("←", KeyCodeTable.VK_LEFT),
            PhysicalKey("↑", KeyCodeTable.VK_UP),
            PhysicalKey("↓", KeyCodeTable.VK_DOWN),
            PhysicalKey("→", KeyCodeTable.VK_RIGHT),
            PhysicalKey("Home", KeyCodeTable.VK_HOME),
            PhysicalKey("End", KeyCodeTable.VK_END),
            PhysicalKey("PgUp", KeyCodeTable.VK_PRIOR),
            PhysicalKey("PgDn", KeyCodeTable.VK_NEXT),
            PhysicalKey("Win", KeyCodeTable.VK_LWIN),
            PhysicalKey("F1", KeyCodeTable.VK_F1),
            PhysicalKey("F2", KeyCodeTable.VK_F2),
            PhysicalKey("F3", KeyCodeTable.VK_F3),
            PhysicalKey("F4", KeyCodeTable.VK_F4),
            PhysicalKey("F5", KeyCodeTable.VK_F5),
            PhysicalKey("F6", KeyCodeTable.VK_F6),
            PhysicalKey("F11", KeyCodeTable.VK_F11),
            PhysicalKey("F12", KeyCodeTable.VK_F12),
            PhysicalKey("PrtSc", KeyCodeTable.VK_PRINT_SCREEN)
        )
    }
    val stickyMods = remember { mutableStateOf(0) }
    val modifiers = listOf(
        "Ctrl" to ModifierMask.CTRL,
        "Shift" to ModifierMask.SHIFT,
        "Alt" to ModifierMask.ALT
    )

    Text(stringResource(Res.string.remoteControlPhysicalKeysLabel), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        modifiers.forEach { (label, mask) ->
            FilterChip(
                selected = (stickyMods.value and mask) != 0,
                onClick = {
                    stickyMods.value = stickyMods.value xor mask
                    tracker.toggleSticky(mask)
                },
                label = { Text(label) }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
    ) {
        items(keys) { k ->
            Button(
                onClick = {
                    val (down, up) = tracker.pressAndRelease(k.vk)
                    val mods = (down.firstOrNull { it.vk != k.vk }?.let { stickyMods.value }) ?: 0
                    down.forEach { onSendStep(it.vk, if (it.vk == k.vk) mods else 0, true) }
                    up.forEach { onSendStep(it.vk, if (it.vk == k.vk) mods else 0, false) }
                    stickyMods.value = 0
                },
                modifier = Modifier.padding(2.dp)
            ) { Text(k.label) }
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
