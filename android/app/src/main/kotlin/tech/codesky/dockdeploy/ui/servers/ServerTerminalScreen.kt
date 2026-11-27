package tech.codesky.dockdeploy.ui.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.components.BadgeTone
import tech.codesky.dockdeploy.ui.components.StatusBadge
import tech.codesky.dockdeploy.ui.theme.TerminalBg
import tech.codesky.dockdeploy.ui.theme.TerminalFg

private const val IME_SENTINEL = " "

/**
 * Termux-style interactive SSH terminal:
 * - Pure black full-viewport canvas with ANSI color & cursor rendering
 * - Tapping anywhere focuses the hidden IME input field so every keystroke,
 *   backspace, Enter, and Ctrl/Alt combo streams directly over WebSocket
 * - Sticky CTRL & ALT toggles + Termux Extra Keys row docked above the keyboard
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerTerminalDialog(
    repo: DockDeployRepository,
    serverId: String,
    serverName: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val termStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }

    val charMetrics = remember(termStyle) {
        val m = textMeasurer.measure("W", style = termStyle)
        m.size.width.coerceAtLeast(1) to m.size.height.coerceAtLeast(1)
    }

    val emulator = remember { TerminalEmulator(initialCols = 80, initialRows = 24) }
    var renderTick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Connecting...") }
    var connected by remember { mutableStateOf(false) }
    var webSocket by remember { mutableStateOf<WebSocket?>(null) }
    var reconnectNonce by remember { mutableIntStateOf(0) }

    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }

    var imeValue by remember {
        mutableStateOf(TextFieldValue(IME_SENTINEL, TextRange(1)))
    }

    val scrollState = rememberScrollState()

    fun sendBytes(seq: String) {
        if (seq.isNotEmpty()) {
            webSocket?.send(seq)
        }
    }

    fun sendWithModifiers(seq: String) {
        val encoded = TermuxKeyEncoder.encodeTypedInput(seq, ctrlArmed = ctrlArmed, altArmed = altArmed)
        ctrlArmed = false
        altArmed = false
        sendBytes(encoded)
    }

    DisposableEffect(serverId, reconnectNonce) {
        status = "Connecting..."
        connected = false
        emulator.clear()
        emulator.feed("Connecting to $serverName...\r\n")
        renderTick++

        val ws = repo.openTerminal(
            serverId = serverId,
            cols = emulator.cols,
            rows = emulator.rows,
            listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    status = "Connected (${emulator.cols}x${emulator.rows})"
                    connected = true
                    webSocket.send("""{"type":"resize","cols":${emulator.cols},"rows":${emulator.rows}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    emulator.feed(text)
                    renderTick++
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    emulator.feed(bytes.utf8())
                    renderTick++
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    status = "Closed ($code)"
                    emulator.feed("\r\n\u001B[33m[Session closed: $code]\u001B[0m\r\n")
                    renderTick++
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    status = "Disconnected"
                    emulator.feed("\r\n\u001B[31m[Connection error: ${t.message ?: "disconnected"}]\u001B[0m\r\n")
                    renderTick++
                }
            },
        )
        webSocket = ws
        onDispose {
            ws.close(1000, "User closed terminal")
            webSocket = null
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    LaunchedEffect(renderTick) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    val rendered = remember(renderTick) {
        emulator.render(
            defaultFg = TerminalFg,
            cursorBg = Color(0xFFF4F4F5),
            cursorFg = Color(0xFF000000),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(TerminalBg)
                .imePadding(),
            containerColor = TerminalBg,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF0A0A0A),
                        titleContentColor = TerminalFg,
                    ),
                    title = {
                        Column {
                            Text(serverName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close terminal")
                        }
                    },
                    actions = {
                        StatusBadge(
                            label = if (connected) "SSH" else "OFF",
                            tone = if (connected) BadgeTone.SUCCESS else BadgeTone.DANGER,
                        )
                        IconButton(onClick = { selectionMode = !selectionMode }) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = if (selectionMode) "Resume typing" else "Copy mode",
                                tint = if (selectionMode) MaterialTheme.colorScheme.tertiary else TerminalFg,
                            )
                        }
                        IconButton(onClick = {
                            emulator.clear()
                            renderTick++
                        }) {
                            Icon(Icons.Rounded.CleaningServices, contentDescription = "Clear screen")
                        }
                        IconButton(onClick = { reconnectNonce++ }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Reconnect")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(TerminalBg),
            ) {
                // Hidden live-IME keystroke receiver (captures soft & hardware keys directly)
                BasicTextField(
                    value = imeValue,
                    onValueChange = { next ->
                        val newText = next.text
                        if (newText.isEmpty()) {
                            // User hit backspace
                            sendBytes("\u007F")
                        } else if (newText != IME_SENTINEL) {
                            val typed = if (newText.startsWith(IME_SENTINEL)) {
                                newText.removePrefix(IME_SENTINEL)
                            } else if (newText.endsWith(IME_SENTINEL)) {
                                newText.removeSuffix(IME_SENTINEL)
                            } else {
                                newText
                            }
                            if (typed.isNotEmpty()) {
                                sendWithModifiers(typed)
                            }
                        }
                        imeValue = TextFieldValue(IME_SENTINEL, TextRange(1))
                    },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.None,
                    ),
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    sendBytes("\r"); true
                                }
                                Key.Backspace -> {
                                    sendBytes("\u007F"); true
                                }
                                Key.Tab -> {
                                    sendBytes("\t"); true
                                }
                                Key.Escape -> {
                                    sendBytes("\u001B"); true
                                }
                                Key.DirectionUp -> {
                                    sendBytes("\u001B[A"); true
                                }
                                Key.DirectionDown -> {
                                    sendBytes("\u001B[B"); true
                                }
                                Key.DirectionRight -> {
                                    sendBytes("\u001B[C"); true
                                }
                                Key.DirectionLeft -> {
                                    sendBytes("\u001B[D"); true
                                }
                                Key.MoveHome -> {
                                    sendBytes("\u001B[H"); true
                                }
                                Key.MoveEnd -> {
                                    sendBytes("\u001B[F"); true
                                }
                                Key.PageUp -> {
                                    sendBytes("\u001B[5~"); true
                                }
                                Key.PageDown -> {
                                    sendBytes("\u001B[6~"); true
                                }
                                else -> {
                                    if (event.isCtrlPressed) {
                                        val code = event.utf16CodePoint
                                        if (code > 0) {
                                            sendBytes(TermuxKeyEncoder.encodeTypedInput(code.toChar().toString(), ctrlArmed = true, altArmed = false))
                                            true
                                        } else false
                                    } else false
                                }
                            }
                        },
                )

                // Terminal viewport
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(TerminalBg)
                        .onSizeChanged { size ->
                            val padPx = with(density) { 16.dp.toPx() }.toInt()
                            val availW = (size.width - padPx).coerceAtLeast(1)
                            val availH = (size.height - padPx).coerceAtLeast(1)
                            val newCols = (availW / charMetrics.first).coerceIn(24, 240)
                            val newRows = (availH / charMetrics.second).coerceIn(8, 100)
                            if (emulator.resize(newCols, newRows)) {
                                renderTick++
                                if (connected) {
                                    status = "Connected (${newCols}x${newRows})"
                                    webSocket?.send("""{"type":"resize","cols":$newCols,"rows":$newRows}""")
                                }
                            }
                        }
                        .then(
                            if (!selectionMode) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            } else Modifier,
                        )
                        .verticalScroll(scrollState)
                        .padding(8.dp),
                ) {
                    if (selectionMode) {
                        SelectionContainer {
                            Text(text = rendered, style = termStyle, color = TerminalFg)
                        }
                    } else {
                        Text(text = rendered, style = termStyle, color = TerminalFg)
                    }
                }

                // Termux-style two-row Extra Keys Bar docked right above the keyboard
                TermuxExtraKeysBar(
                    ctrlArmed = ctrlArmed,
                    altArmed = altArmed,
                    onToggleCtrl = { ctrlArmed = !ctrlArmed },
                    onToggleAlt = { altArmed = !altArmed },
                    onSendKey = { seq ->
                        sendWithModifiers(seq)
                        focusRequester.requestFocus()
                    },
                    onPaste = {
                        val pasted = clipboard.getText()?.text
                        if (!pasted.isNullOrEmpty()) {
                            sendBytes(pasted.replace("\n", "\r"))
                        }
                        focusRequester.requestFocus()
                    },
                    onShowKeyboard = {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    },
                )
            }
        }
    }
}

@Composable
private fun TermuxExtraKeysBar(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendKey: (String) -> Unit,
    onPaste: () -> Unit,
    onShowKeyboard: () -> Unit,
) {
    Surface(
        color = Color(0xFF0B0B0B),
        border = BorderStroke(1.dp, Color(0xFF222222)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ExtraKeyButton("ESC") { onSendKey("\u001B") }
                ExtraKeyButton("/") { onSendKey("/") }
                ExtraKeyButton("-") { onSendKey("-") }
                ExtraKeyButton("HOME") { onSendKey("\u001B[H") }
                ExtraKeyButton("↑") { onSendKey("\u001B[A") }
                ExtraKeyButton("END") { onSendKey("\u001B[F") }
                ExtraKeyButton("PGUP") { onSendKey("\u001B[5~") }
                ExtraKeyButton("|") { onSendKey("|") }
                ExtraKeyButton("~") { onSendKey("~") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ExtraKeyButton("TAB") { onSendKey("\t") }
                ExtraKeyButton("CTRL", active = ctrlArmed, onClick = onToggleCtrl)
                ExtraKeyButton("ALT", active = altArmed, onClick = onToggleAlt)
                ExtraKeyButton("←") { onSendKey("\u001B[D") }
                ExtraKeyButton("↓") { onSendKey("\u001B[B") }
                ExtraKeyButton("→") { onSendKey("\u001B[C") }
                ExtraKeyButton("PGDN") { onSendKey("\u001B[6~") }
                ExtraKeyButton("^C") { onSendKey("\u0003") }
                ExtraKeyButton("PASTE", onClick = onPaste)
                ExtraKeyButton("KB", onClick = onShowKeyboard)
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (active) Color(0xFF22C55E) else Color(0xFF171717),
        border = BorderStroke(1.dp, if (active) Color(0xFF22C55E) else Color(0xFF2B2B2B)),
        modifier = Modifier
            .height(32.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (active) Color(0xFF000000) else Color(0xFFE4E4E7),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }
}
