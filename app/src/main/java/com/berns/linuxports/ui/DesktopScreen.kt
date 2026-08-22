package com.berns.linuxports.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.berns.linuxports.core.DesktopState
import com.berns.linuxports.core.SessionManager
import com.berns.linuxports.model.Distro
import com.berns.linuxports.service.VmService
import com.berns.linuxports.vnc.Keysyms
import com.berns.linuxports.vnc.RfbClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max
import kotlin.math.min

@Composable
fun DesktopScreen(distro: Distro, viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val prefs = viewModel.prefs

    val session = remember(distro.key) {
        SessionManager.desktop(
            context = context,
            distro = distro,
            display = prefs.display(distro),
            geometry = prefs.geometry(distro),
            dpi = prefs.dpi(distro)
        )
    }
    val state by session.state.collectAsState()
    val log by session.log.collectAsState()

    var client by remember { mutableStateOf<RfbClient?>(null) }

    LaunchedEffect(distro.key) { VmService.start(context, "${distro.name} desktop") }

    LaunchedEffect(state) {
        if (state == DesktopState.READY && client == null) {
            client = RfbClient(port = session.port, password = prefs.vncPassword(distro)).also { it.connect() }
        }
    }

    DisposableEffect(Unit) {
        onDispose { client?.disconnect() }
    }

    Surface(color = Color(0xFF05080A), modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Toolbar(
                distro = distro,
                client = client,
                onBack = onBack,
                onKeyboard = {
                    focusRequester.requestFocus()
                    keyboard?.show()
                },
                onStop = {
                    client?.disconnect()
                    SessionManager.stopDesktop(distro)
                    onBack()
                }
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val active = client
                if (active != null) {
                    RemoteScreen(active)
                    HiddenKeyInput(focusRequester) { text -> active.typeText(text) }
                } else {
                    Starting(state, log)
                }
            }

            if (client != null) {
                ModifierBar(client!!)
            }
        }
    }
}

@Composable
private fun Toolbar(
    distro: Distro,
    client: RfbClient?,
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onStop: () -> Unit
) {
    val idleStatus = remember { MutableStateFlow(RfbClient.Status.IDLE) }
    val status by (client?.status ?: idleStatus).collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF101A1F))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text("${distro.name} desktop", color = Color.White, fontSize = 14.sp)
            Text(
                status.name.lowercase(),
                color = Color(0xFF7FD1B9),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        IconButton(onClick = onKeyboard) { Text("KB", color = Color.White, fontSize = 13.sp) }
        IconButton(onClick = onStop) { Text("Stop", color = Color(0xFFFF8A80), fontSize = 12.sp) }
    }
}

@Composable
private fun Starting(state: DesktopState, log: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        if (state == DesktopState.STARTING) {
            CircularProgressIndicator(color = Color(0xFF7FD1B9))
            Spacer(Modifier.height(18.dp))
            Text("Starting the X server and the desktop session", color = Color.White, fontSize = 14.sp)
            Text(
                "The first start is the slow one - Xfce has to build its caches.",
                color = Color(0xFF8A9BA1),
                fontSize = 12.sp
            )
        } else {
            Text(
                if (state == DesktopState.FAILED) "The desktop did not start" else "Desktop stopped",
                color = Color(0xFFFF8A80),
                fontSize = 15.sp
            )
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(log) { line ->
                Text(line, color = Color(0xFF9FB1B6), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}

/** Draws the remote framebuffer and turns touches into pointer events. */
@Composable
private fun RemoteScreen(client: RfbClient) {
    val frame by client.frame.collectAsState()
    val size by client.size.collectAsState()

    var scale by remember { mutableStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(client, size) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var isGesture = false
                    var lastCentroid = first.position
                    var lastDistance = 0f
                    var moved = false
                    val startScale = scale
                    val tracker = VelocityTracker()

                    fun toRemote(p: Offset): Pair<Int, Int> {
                        val s = if (scale <= 0f) 1f else scale
                        return ((p.x - offset.x) / s).toInt() to ((p.y - offset.y) / s).toInt()
                    }

                    val (sx, sy) = toRemote(first.position)
                    client.sendPointer(sx, sy, 1)

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pointers = event.changes.filter { it.pressed }
                        if (pointers.isEmpty()) break

                        if (pointers.size >= 2) {
                            if (!isGesture) {
                                // Two fingers: stop dragging, start panning and zooming.
                                val (ux, uy) = toRemote(lastCentroid)
                                client.sendPointer(ux, uy, 0)
                                isGesture = true
                                lastDistance = 0f
                            }
                            val centroid = pointers.fold(Offset.Zero) { acc, p -> acc + p.position } /
                                pointers.size.toFloat()
                            val distance = (pointers[0].position - pointers[1].position).getDistance()
                            if (lastDistance > 0f && distance > 0f) {
                                val factor = distance / lastDistance
                                val newScale = (scale * factor).coerceIn(startScale * 0.4f, startScale * 6f)
                                offset = centroid - (centroid - offset) * (newScale / scale)
                                scale = newScale
                            }
                            offset += centroid - lastCentroid
                            lastCentroid = centroid
                            lastDistance = distance
                            event.changes.forEach { it.consume() }
                        } else if (!isGesture) {
                            val change = pointers.first()
                            if (change.positionChange() != Offset.Zero) {
                                moved = true
                                lastCentroid = change.position
                                val (mx, my) = toRemote(change.position)
                                client.sendPointer(mx, my, 1)
                                tracker.addPosition(change.uptimeMillis, change.position)
                            }
                            change.consume()
                        } else {
                            lastCentroid = pointers.first().position
                        }
                    }

                    if (!isGesture) {
                        val (ux, uy) = toRemote(lastCentroid)
                        client.sendPointer(ux, uy, 0)
                        if (!moved) {
                            // A clean tap: make sure the click registers as press + release.
                            client.sendPointer(ux, uy, 1)
                            client.sendPointer(ux, uy, 0)
                        }
                    }
                }
            }
    ) {
        val (remoteW, remoteH) = size
        if (remoteW <= 0 || remoteH <= 0) return@Canvas
        if (scale <= 0f || viewport != Offset(this.size.width, this.size.height)) {
            viewport = Offset(this.size.width, this.size.height)
            scale = min(this.size.width / remoteW, this.size.height / remoteH)
            offset = Offset(
                (this.size.width - remoteW * scale) / 2f,
                max(0f, (this.size.height - remoteH * scale) / 2f)
            )
        }

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.drawColor(0xFF05080A.toInt())
            val ignored = frame
            client.withBitmap { bitmap ->
                native.save()
                native.translate(offset.x, offset.y)
                native.scale(scale, scale)
                native.drawBitmap(bitmap, 0f, 0f, null)
                native.restore()
            }
            if (ignored < 0) native.drawColor(0)
        }
    }
}

@Composable
private fun HiddenKeyInput(focusRequester: FocusRequester, onText: (String) -> Unit) {
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
            .focusRequester(focusRequester),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.None
        ),
        singleLine = true
    )
}

/** Modifier keys and the handful of buttons a touch screen cannot produce on its own. */
@Composable
private fun ModifierBar(client: RfbClient) {
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    fun withModifiers(action: () -> Unit) {
        if (ctrl) client.sendKey(Keysyms.CONTROL_L, true)
        if (alt) client.sendKey(Keysyms.ALT_L, true)
        action()
        if (alt) client.sendKey(Keysyms.ALT_L, false)
        if (ctrl) client.sendKey(Keysyms.CONTROL_L, false)
        ctrl = false
        alt = false
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF101A1F))
            .horizontalScroll(scroll)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DeskKey("CTRL", ctrl) { ctrl = !ctrl }
        DeskKey("ALT", alt) { alt = !alt }
        DeskKey("ESC") { withModifiers { client.typeKey(Keysyms.ESCAPE) } }
        DeskKey("TAB") { withModifiers { client.typeKey(Keysyms.TAB) } }
        DeskKey("ENTER") { withModifiers { client.typeKey(Keysyms.RETURN) } }
        DeskKey("BKSP") { withModifiers { client.typeKey(Keysyms.BACKSPACE) } }
        DeskKey("SUPER") { client.typeKey(Keysyms.SUPER_L) }
        DeskKey("left") { withModifiers { client.typeKey(Keysyms.LEFT) } }
        DeskKey("down") { withModifiers { client.typeKey(Keysyms.DOWN) } }
        DeskKey("up") { withModifiers { client.typeKey(Keysyms.UP) } }
        DeskKey("right") { withModifiers { client.typeKey(Keysyms.RIGHT) } }
        DeskKey("F1") { client.typeKey(Keysyms.f(1)) }
        DeskKey("F5") { client.typeKey(Keysyms.f(5)) }
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun DeskKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (active) Color(0xFF7FD1B9) else Color(0xFF1E2C32),
                MaterialTheme.shapes.small
            )
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown()
                    onClick()
                }
            }
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
