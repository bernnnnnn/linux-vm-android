package com.berns.linuxports.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.berns.linuxports.core.DesktopState
import com.berns.linuxports.core.Prefs
import com.berns.linuxports.core.SessionManager
import com.berns.linuxports.model.Distro
import com.berns.linuxports.service.VmService
import com.berns.linuxports.vnc.Keysyms
import com.berns.linuxports.vnc.RfbClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class PointerMode(val key: String, val label: String) {
    TRACKPAD("trackpad", "Trackpad"),
    DIRECT("direct", "Touch"),
    JOYSTICK("joystick", "Stick");

    companion object {
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: TRACKPAD
    }
}

private const val BUTTON_LEFT = 1
private const val BUTTON_MIDDLE = 2
private const val BUTTON_RIGHT = 4

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
    var mode by remember { mutableStateOf(PointerMode.from(prefs.pointerMode())) }
    var speed by remember { mutableStateOf(prefs.pointerSpeed()) }

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
            val active = client
            Toolbar(
                distro = distro,
                client = active,
                mode = mode,
                speed = speed,
                onModeChange = {
                    mode = it
                    prefs.setPointerMode(it.key)
                },
                onSpeedChange = {
                    speed = it
                    prefs.setPointerSpeed(it)
                },
                onBack = onBack,
                onKeyboard = {
                    focusRequester.requestFocus()
                    keyboard?.show()
                },
                onStop = {
                    active?.disconnect()
                    SessionManager.stopDesktop(distro)
                    onBack()
                }
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (active != null) {
                    val input = remember(active) { RemoteInput(active) }
                    RemoteScreen(active, input, mode, speed)
                    HiddenKeyInput(focusRequester) { text -> active.typeText(text) }
                    ControlBar(active, input, Modifier.align(Alignment.BottomCenter))
                } else {
                    Starting(state, log)
                }
            }
        }
    }
}

/**
 * The remote pointer as this app models it: a position the phone owns, moved by whichever
 * input mode is active, plus whatever buttons are currently held down.
 */
private class RemoteInput(private val client: RfbClient) {
    var cursor by mutableStateOf(Offset(-1f, -1f))
    var buttons by mutableStateOf(0)
        private set

    private var width = 0
    private var height = 0

    fun bounds(w: Int, h: Int) {
        width = w
        height = h
        if (cursor.x < 0f && w > 0) {
            cursor = Offset(w / 2f, h / 2f)
            send(motion = true)
        }
    }

    private fun send(motion: Boolean) {
        client.sendPointer(cursor.x.toInt(), cursor.y.toInt(), buttons, motion)
    }

    fun moveTo(x: Float, y: Float) {
        if (width == 0 || height == 0) return
        cursor = Offset(x.coerceIn(0f, (width - 1).toFloat()), y.coerceIn(0f, (height - 1).toFloat()))
        send(motion = true)
    }

    fun moveBy(dx: Float, dy: Float) = moveTo(cursor.x + dx, cursor.y + dy)

    fun press(mask: Int) {
        buttons = buttons or mask
        send(motion = false)
    }

    fun release(mask: Int) {
        buttons = buttons and mask.inv()
        send(motion = false)
    }

    fun click(mask: Int) {
        press(mask)
        release(mask)
    }

    fun toggleDrag() {
        if (buttons and BUTTON_LEFT != 0) release(BUTTON_LEFT) else press(BUTTON_LEFT)
    }

    fun scroll(up: Boolean) = client.sendScroll(cursor.x.toInt(), cursor.y.toInt(), up)
}

@Composable
private fun Toolbar(
    distro: Distro,
    client: RfbClient?,
    mode: PointerMode,
    speed: Float,
    onModeChange: (PointerMode) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onStop: () -> Unit
) {
    val idleStatus = remember { MutableStateFlow(RfbClient.Status.IDLE) }
    val status by (client?.status ?: idleStatus).collectAsState()
    val idleError = remember { MutableStateFlow<String?>(null) }
    val error by (client?.error ?: idleError).collectAsState()

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
                error ?: status.name.lowercase(),
                color = if (error != null) Color(0xFFFF8A80) else Color(0xFF7FD1B9),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        // Cycles trackpad -> touch -> stick.
        Pill(mode.label) {
            val next = PointerMode.entries[(mode.ordinal + 1) % PointerMode.entries.size]
            onModeChange(next)
        }
        Spacer(Modifier.width(4.dp))
        Pill("%.1fx".format(speed)) {
            val speeds = Prefs.POINTER_SPEEDS
            onSpeedChange(speeds[(speeds.indexOf(speed).coerceAtLeast(0) + 1) % speeds.size])
        }
        IconButton(onClick = onKeyboard) { Text("KB", color = Color.White, fontSize = 13.sp) }
        IconButton(onClick = onStop) { Text("Stop", color = Color(0xFFFF8A80), fontSize = 12.sp) }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Color(0xFF1E2C32))
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown()
                    onClick()
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = Color(0xFFCBD8DB), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(log) { line ->
                Text(line, color = Color(0xFF9FB1B6), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}

/** Draws the remote framebuffer and turns touches into pointer events. */
@Composable
private fun RemoteScreen(client: RfbClient, input: RemoteInput, mode: PointerMode, speed: Float) {
    val frame by client.frame.collectAsState()
    val size by client.size.collectAsState()

    var scale by remember { mutableStateOf(0f) }
    var fitScale by remember { mutableStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(size) { input.bounds(size.first, size.second) }

    // Fit the remote screen to the view whenever either changes. This has to happen outside
    // the draw phase - writing Compose state while drawing invalidates the frame being drawn.
    LaunchedEffect(viewport, size) {
        val (remoteW, remoteH) = size
        if (remoteW <= 0 || remoteH <= 0 || viewport.width == 0 || viewport.height == 0) return@LaunchedEffect
        fitScale = min(viewport.width.toFloat() / remoteW, viewport.height.toFloat() / remoteH)
        scale = fitScale
        offset = Offset(
            (viewport.width - remoteW * fitScale) / 2f,
            max(0f, (viewport.height - remoteH * fitScale) / 2f)
        )
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                .pointerInput(client, mode, speed, size) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startedAt = down.uptimeMillis
                        var lastTime = down.uptimeMillis
                        var lastPos = down.position
                        var lastCentroid = down.position
                        var lastDistance = 0f
                        var scrollAccum = 0f
                        var multi = false
                        var moved = false

                        fun toRemote(p: Offset): Offset {
                            val s = if (scale <= 0f) 1f else scale
                            return Offset((p.x - offset.x) / s, (p.y - offset.y) / s)
                        }

                        if (mode == PointerMode.DIRECT) {
                            val r = toRemote(down.position)
                            input.moveTo(r.x, r.y)
                            input.press(BUTTON_LEFT)
                        }

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            event.changes.firstOrNull()?.let { lastTime = it.uptimeMillis }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                if (!multi) {
                                    // A second finger arrived: stop whatever the first was doing.
                                    if (mode == PointerMode.DIRECT) input.release(BUTTON_LEFT)
                                    multi = true
                                    lastDistance = 0f
                                    scrollAccum = 0f
                                    lastCentroid = pressed.fold(Offset.Zero) { a, p -> a + p.position } /
                                        pressed.size.toFloat()
                                }
                                val centroid = pressed.fold(Offset.Zero) { a, p -> a + p.position } /
                                    pressed.size.toFloat()
                                val dx = pressed[0].position.x - pressed[1].position.x
                                val dy = pressed[0].position.y - pressed[1].position.y
                                val distance = sqrt(dx * dx + dy * dy)

                                if (lastDistance > 0f && abs(distance - lastDistance) > 6f) {
                                    val next = (scale * (distance / lastDistance))
                                        .coerceIn(fitScale * 0.5f, fitScale * 8f)
                                    offset = centroid - (centroid - offset) * (next / scale)
                                    scale = next
                                } else if (scale > fitScale * 1.02f) {
                                    // Zoomed in: two fingers pan the view.
                                    offset += centroid - lastCentroid
                                } else {
                                    // Fit to screen: two fingers are the scroll wheel.
                                    scrollAccum += centroid.y - lastCentroid.y
                                    while (abs(scrollAccum) >= SCROLL_NOTCH) {
                                        input.scroll(up = scrollAccum > 0)
                                        scrollAccum -= if (scrollAccum > 0) SCROLL_NOTCH else -SCROLL_NOTCH
                                    }
                                }
                                lastCentroid = centroid
                                lastDistance = distance
                                event.changes.forEach { it.consume() }
                            } else if (!multi) {
                                val change = pressed.first()
                                val delta = change.position - lastPos
                                if (delta != Offset.Zero) {
                                    if ((change.position - down.position).getDistance() > slop) moved = true
                                    when (mode) {
                                        PointerMode.DIRECT -> {
                                            val r = toRemote(change.position)
                                            input.moveTo(r.x, r.y)
                                        }
                                        PointerMode.TRACKPAD -> {
                                            val s = if (scale <= 0f) 1f else scale
                                            input.moveBy(delta.x * speed / s, delta.y * speed / s)
                                        }
                                        PointerMode.JOYSTICK -> offset += delta
                                    }
                                    lastPos = change.position
                                }
                                change.consume()
                            } else {
                                lastCentroid = pressed.first().position
                            }
                        }

                        if (!multi) {
                            when (mode) {
                                PointerMode.DIRECT -> input.release(BUTTON_LEFT)
                                // A short touch that never travelled is a click; a drag has
                                // already moved the pointer and should not also click.
                                PointerMode.TRACKPAD ->
                                    if (!moved && lastTime - startedAt < TAP_MILLIS) {
                                        input.click(BUTTON_LEFT)
                                    }
                                PointerMode.JOYSTICK -> Unit
                            }
                        }
                    }
                }
        ) {
            if (scale <= 0f) return@Canvas
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

        if (mode == PointerMode.JOYSTICK) {
            Joystick(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 76.dp)
            ) { direction ->
                input.moveBy(direction.x * speed * JOYSTICK_PIXELS, direction.y * speed * JOYSTICK_PIXELS)
            }
        }
    }
}

/**
 * A thumbstick that nudges the pointer while it is held off-centre. Reports a direction
 * vector in the range -1..1 on every animation tick.
 */
@Composable
private fun Joystick(modifier: Modifier = Modifier, onDirection: (Offset) -> Unit) {
    val sizeDp = 128.dp
    var knob by remember { mutableStateOf(Offset.Zero) }
    var radiusPx by remember { mutableStateOf(1f) }

    LaunchedEffect(knob) {
        if (knob == Offset.Zero) return@LaunchedEffect
        while (true) {
            onDirection(Offset(knob.x / radiusPx, knob.y / radiusPx))
            delay(16)
        }
    }

    Box(
        modifier = modifier
            .size(sizeDp)
            .onSizeChanged { radiusPx = (it.width / 2f).coerceAtLeast(1f) }
            .clip(CircleShape)
            .background(Color(0x33101A1F))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { knob = Offset.Zero },
                    onDragCancel = { knob = Offset.Zero }
                ) { change, delta ->
                    change.consume()
                    val next = knob + delta
                    val length = next.getDistance()
                    knob = if (length > radiusPx) next * (radiusPx / length) else next
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 3f
                    color = 0x667FD1B9
                }
                val cx = size.width / 2f
                val cy = size.height / 2f
                native.drawCircle(cx, cy, size.width / 2f - 3f, paint)
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = 0xCC7FD1B9.toInt()
                native.drawCircle(cx + knob.x, cy + knob.y, size.width / 7f, paint)
            }
        }
    }
}

/** Mouse buttons, scroll and a latching drag, which touch alone cannot express. */
@Composable
private fun ControlBar(client: RfbClient, input: RemoteInput, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val dragging = input.buttons and BUTTON_LEFT != 0

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
        modifier
            .fillMaxWidth()
            .background(Color(0xCC101A1F))
            .horizontalScroll(scroll)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DeskKey("LEFT") { withModifiers { input.click(BUTTON_LEFT) } }
        DeskKey("RIGHT") { input.click(BUTTON_RIGHT) }
        DeskKey("MID") { input.click(BUTTON_MIDDLE) }
        DeskKey("DRAG", dragging) { input.toggleDrag() }
        DeskKey("SCR+") { input.scroll(true) }
        DeskKey("SCR-") { input.scroll(false) }
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

private const val SCROLL_NOTCH = 48f
private const val TAP_MILLIS = 400L
private const val JOYSTICK_PIXELS = 12f
