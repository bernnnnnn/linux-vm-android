package com.berns.linuxports.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.berns.linuxports.core.SessionManager
import com.berns.linuxports.core.TerminalSession
import com.berns.linuxports.model.Distro
import com.berns.linuxports.service.VmService
import com.berns.linuxports.term.TerminalBuffer
import kotlinx.coroutines.flow.MutableStateFlow

private const val ESC = "\u001B"
private const val BACKGROUND = 0xFF0B0F11.toInt()
private const val FOREGROUND = 0xFFD6E0E3.toInt()
private const val ACCENT = 0xFF7FD1B9.toInt()

@Composable
fun TerminalScreen(distro: Distro, onBack: () -> Unit) {
    // Without this, the system back gesture leaves the app entirely instead of
    // returning to the port's page.
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var fontSize by remember { mutableStateOf(13f) }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }

    val paint = remember(fontSize, density) {
        Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textSize = with(density) { fontSize.sp.toPx() }
        }
    }
    val charWidth = remember(paint) { paint.measureText("M").coerceAtLeast(1f) }
    val lineHeight = remember(paint) {
        (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
    }

    LaunchedEffect(distro.key) { VmService.start(context, "${distro.name} terminal") }

    val idle = remember { MutableStateFlow(0L) }
    val revision by (session?.revision ?: idle).collectAsState()

    fun send(text: String) = session?.write(text)

    Surface(color = Color(BACKGROUND), modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121A1E))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color(FOREGROUND))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "${distro.name} shell",
                        color = Color(FOREGROUND),
                        style = MaterialTheme.typography.titleSmall
                    )
                    session?.let {
                        Text(
                            "${it.emulator.cols}x${it.emulator.rows}",
                            color = Color(0xFF7A8B90),
                            fontSize = 10.sp
                        )
                    }
                }
                IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(8f) }) {
                    Text("A-", color = Color(FOREGROUND), fontSize = 13.sp)
                }
                IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(24f) }) {
                    Text("A+", color = Color(FOREGROUND), fontSize = 13.sp)
                }
                IconButton(onClick = {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }) {
                    Text("KB", color = Color(FOREGROUND), fontSize = 13.sp)
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val cols = with(density) { (maxWidth.toPx() / charWidth).toInt() }.coerceIn(20, 400)
                val rows = with(density) { (maxHeight.toPx() / lineHeight).toInt() }.coerceIn(6, 200)

                LaunchedEffect(rows, cols) {
                    session = SessionManager.terminal(context, distro, rows, cols)
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        // Without this the background fill below escapes the node and
                        // paints over the bar above it - Compose does not clip a Canvas.
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTapGestures {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            }
                        }
                ) {
                    val active = session
                    drawRect(Color(BACKGROUND))
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        if (active == null) return@drawIntoCanvas
                        val emu = active.emulator
                        // revision is read so Compose redraws when new output arrives
                        val ignored = revision
                        synchronized(emu) {
                            val baseline = -paint.fontMetrics.ascent
                            val screen = emu.buffer.screen
                            for (y in 0 until minOf(rows, screen.size)) {
                                val row = screen[y]
                                val top = y * lineHeight
                                val limit = minOf(cols, row.text.size)
                                var x = 0
                                while (x < limit) {
                                    var end = x + 1
                                    while (end < limit &&
                                        row.bg[end] == row.bg[x] &&
                                        row.fg[end] == row.fg[x] &&
                                        row.flags[end] == row.flags[x]
                                    ) end++

                                    var bgColor = resolve(row.bg[x], BACKGROUND)
                                    var fgColor = resolve(row.fg[x], FOREGROUND)
                                    if (row.flags[x] and TerminalBuffer.FLAG_INVERSE != 0) {
                                        val swap = bgColor
                                        bgColor = fgColor
                                        fgColor = swap
                                    }
                                    if (bgColor != BACKGROUND) {
                                        paint.color = bgColor
                                        native.drawRect(
                                            x * charWidth, top, end * charWidth, top + lineHeight, paint
                                        )
                                    }
                                    val text = String(row.text, x, end - x)
                                    if (text.isNotBlank()) {
                                        paint.color = fgColor
                                        paint.isFakeBoldText = row.flags[x] and TerminalBuffer.FLAG_BOLD != 0
                                        paint.isUnderlineText = row.flags[x] and TerminalBuffer.FLAG_UNDERLINE != 0
                                        native.drawText(text, x * charWidth, top + baseline, paint)
                                        paint.isFakeBoldText = false
                                        paint.isUnderlineText = false
                                    }
                                    x = end
                                }
                            }
                            if (emu.cursorVisible && emu.cursorY < rows) {
                                paint.color = ACCENT
                                native.drawRect(
                                    emu.cursorX * charWidth,
                                    emu.cursorY * lineHeight + lineHeight - 3f,
                                    (emu.cursorX + 1) * charWidth,
                                    emu.cursorY * lineHeight + lineHeight,
                                    paint
                                )
                            }
                            if (ignored < 0) return@synchronized
                        }
                    }
                }

                HiddenInput(
                    focusRequester = focusRequester,
                    onText = { text ->
                        when {
                            ctrlArmed && text.isNotEmpty() -> {
                                send(controlOf(text[0]))
                                ctrlArmed = false
                            }
                            altArmed && text.isNotEmpty() -> {
                                send(ESC + text[0])
                                altArmed = false
                            }
                            else -> send(text)
                        }
                    },
                    onSpecial = { send(it) }
                )
            }

            KeyBar(
                ctrlArmed = ctrlArmed,
                altArmed = altArmed,
                onCtrl = { ctrlArmed = !ctrlArmed },
                onAlt = { altArmed = !altArmed },
                onSend = { send(it) }
            )
        }
    }
}

private fun resolve(color: Int, default: Int): Int = when (color) {
    TerminalBuffer.DEFAULT_FG, TerminalBuffer.DEFAULT_BG -> default
    else -> color
}

private fun controlOf(c: Char): String {
    val upper = c.uppercaseChar()
    return when {
        upper.code in 0x40..0x5F -> (upper.code - 0x40).toChar().toString()
        upper == ' ' -> "\u0000"
        else -> c.toString()
    }
}

@Composable
private fun HiddenInput(
    focusRequester: FocusRequester,
    onText: (String) -> Unit,
    onSpecial: (String) -> Unit
) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    BasicTextField(
        value = value,
        onValueChange = { updated ->
            if (updated.text.isNotEmpty()) onText(updated.text)
            value = TextFieldValue("", TextRange(0))
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val sequence = when (event.key) {
                    Key.Enter, Key.NumPadEnter -> "\r"
                    Key.Backspace -> "\u007F"
                    Key.DirectionUp -> "$ESC[A"
                    Key.DirectionDown -> "$ESC[B"
                    Key.DirectionRight -> "$ESC[C"
                    Key.DirectionLeft -> "$ESC[D"
                    Key.Tab -> "\t"
                    Key.Escape -> ESC
                    else -> null
                }
                if (sequence != null) {
                    onSpecial(sequence)
                    true
                } else {
                    false
                }
            },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.None
        ),
        singleLine = true
    )
}

@Composable
private fun KeyBar(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onSend: (String) -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF121A1E))
            .horizontalScroll(scroll)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SoftKey("ESC") { onSend(ESC) }
        SoftKey("CTRL", active = ctrlArmed, onClick = onCtrl)
        SoftKey("ALT", active = altArmed, onClick = onAlt)
        SoftKey("TAB") { onSend("\t") }
        SoftKey("left") { onSend("$ESC[D") }
        SoftKey("down") { onSend("$ESC[B") }
        SoftKey("up") { onSend("$ESC[A") }
        SoftKey("right") { onSend("$ESC[C") }
        SoftKey("^C") { onSend("\u0003") }
        SoftKey("^D") { onSend("\u0004") }
        SoftKey("^Z") { onSend("\u001A") }
        SoftKey("^L") { onSend("\u000C") }
        SoftKey("|") { onSend("|") }
        SoftKey("/") { onSend("/") }
        SoftKey("-") { onSend("-") }
        SoftKey("~") { onSend("~") }
        SoftKey("HOME") { onSend("$ESC[H") }
        SoftKey("END") { onSend("$ESC[F") }
        SoftKey("PGUP") { onSend("$ESC[5~") }
        SoftKey("PGDN") { onSend("$ESC[6~") }
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun SoftKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (active) Color(ACCENT) else Color(0xFF202C31),
                MaterialTheme.shapes.small
            )
            .pointerInput(label) { detectTapGestures { onClick() } }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (active) Color(0xFF04231C) else Color(0xFFCBD8DB),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}
