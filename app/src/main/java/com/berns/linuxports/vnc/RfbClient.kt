package com.berns.linuxports.vnc

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * A small RFB (VNC) client, enough for TigerVNC on the loopback interface:
 * Raw, CopyRect, RRE and Hextile, plus the DesktopSize pseudo-encoding.
 *
 * Everything travels over 127.0.0.1 inside the phone, so there is no point paying for
 * Tight/ZRLE compression - raw pixels over loopback are cheaper than decoding them.
 */
class RfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int,
    private val password: String
) {
    enum class Status { IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _frame = MutableStateFlow(0L)
    /** Increments on every applied framebuffer update, so the UI knows to redraw. */
    val frame: StateFlow<Long> = _frame.asStateFlow()
    private val _size = MutableStateFlow(0 to 0)
    val size: StateFlow<Pair<Int, Int>> = _size.asStateFlow()

    var desktopName: String = ""
        private set

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val bitmapLock = Any()
    private var bitmap: Bitmap? = null
    private var width = 0
    private var height = 0

    @Volatile private var running = false
    @Volatile private var pendingRequest = false

    fun connect() {
        if (running) return
        running = true
        _status.value = Status.CONNECTING
        thread(name = "berns-vnc", isDaemon = true) {
            try {
                runSession()
            } catch (e: Throwable) {
                if (running) {
                    _error.value = e.message ?: e.javaClass.simpleName
                    _status.value = Status.ERROR
                }
            } finally {
                running = false
                if (_status.value == Status.CONNECTED) _status.value = Status.DISCONNECTED
                closeQuietly()
            }
        }
    }

    fun disconnect() {
        running = false
        _status.value = Status.DISCONNECTED
        closeQuietly()
    }

    /** Runs [block] with the current framebuffer; returns null while there is none yet. */
    fun <T> withBitmap(block: (Bitmap) -> T): T? = synchronized(bitmapLock) {
        bitmap?.let(block)
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    // ------------------------------------------------------------- handshake

    private fun runSession() {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 15_000)
        s.tcpNoDelay = true
        socket = s
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 15))
        input = inp
        output = out

        // ProtocolVersion
        val serverVersion = ByteArray(12).also { inp.readFully(it) }.toString(Charsets.US_ASCII)
        if (!serverVersion.startsWith("RFB ")) throw IOException("not a VNC server")
        val minor = serverVersion.substring(8, 11).toIntOrNull() ?: 8
        val useVersion = if (minor >= 8) "RFB 003.008\n" else "RFB 003.003\n"
        out.write(useVersion.toByteArray(Charsets.US_ASCII))
        out.flush()

        // Security
        val chosen: Int
        if (minor >= 7) {
            val count = inp.readUnsignedByte()
            if (count == 0) throw IOException(readFailureReason(inp))
            val types = ByteArray(count).also { inp.readFully(it) }.map { it.toInt() and 0xFF }
            chosen = when {
                types.contains(SEC_VNC_AUTH) -> SEC_VNC_AUTH
                types.contains(SEC_NONE) -> SEC_NONE
                else -> throw IOException("no supported security type in $types")
            }
            out.writeByte(chosen)
            out.flush()
        } else {
            chosen = inp.readInt()
            if (chosen == 0) throw IOException(readFailureReason(inp))
        }
        if (chosen == SEC_VNC_AUTH) vncAuth(inp, out)

        // 3.8 always sends a SecurityResult; earlier versions only send one after auth.
        if (minor >= 8 || chosen == SEC_VNC_AUTH) {
            val result = inp.readInt()
            if (result != 0) {
                val reason = if (minor >= 8) readFailureReason(inp) else "wrong password"
                throw IOException("authentication failed: $reason")
            }
        }

        // ClientInit / ServerInit
        out.writeByte(1) // shared
        out.flush()
        width = inp.readUnsignedShort()
        height = inp.readUnsignedShort()
        inp.skipBytes(16) // server pixel format; replaced below
        val nameLength = inp.readInt()
        desktopName = ByteArray(nameLength).also { inp.readFully(it) }.toString(Charsets.UTF_8)

        allocate(width, height)
        _size.value = width to height
        sendPixelFormat(out)
        sendEncodings(out)
        _status.value = Status.CONNECTED
        requestUpdate(false)

        val buffer = ByteArray(1 shl 18)
        while (running) {
            when (val msg = inp.read()) {
                -1 -> throw IOException("server closed the connection")
                0 -> {
                    handleFramebufferUpdate(inp, buffer)
                    pendingRequest = false
                    requestUpdate(true)
                }
                1 -> { // SetColourMapEntries
                    inp.skipBytes(3)
                    val n = inp.readUnsignedShort()
                    inp.skipBytes(n * 6)
                }
                2 -> Unit // Bell
                3 -> { // ServerCutText
                    inp.skipBytes(3)
                    val len = inp.readInt()
                    inp.skipBytes(len)
                }
                else -> throw IOException("unexpected server message $msg")
            }
        }
    }

    private fun readFailureReason(inp: DataInputStream): String = runCatching {
        val len = inp.readInt()
        ByteArray(len).also { inp.readFully(it) }.toString(Charsets.UTF_8)
    }.getOrDefault("connection refused by the server")

    /**
     * VNC's challenge/response: DES with the password as the key, except each key byte has
     * its bits reversed, which is a quirk of the original implementation everyone copied.
     */
    private fun vncAuth(inp: DataInputStream, out: DataOutputStream) {
        val challenge = ByteArray(16).also { inp.readFully(it) }
        val key = ByteArray(8)
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until 8) {
            val b = if (i < pw.size) pw[i].toInt() and 0xFF else 0
            var reversed = 0
            for (bit in 0 until 8) if (b and (1 shl bit) != 0) reversed = reversed or (1 shl (7 - bit))
            key[i] = reversed.toByte()
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        out.write(cipher.doFinal(challenge))
        out.flush()
    }

    private fun sendPixelFormat(out: DataOutputStream) {
        out.writeByte(0) // SetPixelFormat
        out.write(byteArrayOf(0, 0, 0))
        out.writeByte(32)  // bits per pixel
        out.writeByte(24)  // depth
        out.writeByte(0)   // little endian
        out.writeByte(1)   // true colour
        out.writeShort(255); out.writeShort(255); out.writeShort(255)
        out.writeByte(16); out.writeByte(8); out.writeByte(0) // r, g, b shift -> 0xAARRGGBB
        out.write(byteArrayOf(0, 0, 0))
        out.flush()
    }

    private fun sendEncodings(out: DataOutputStream) {
        val encodings = intArrayOf(ENC_COPYRECT, ENC_HEXTILE, ENC_RRE, ENC_RAW, ENC_DESKTOP_SIZE)
        out.writeByte(2)
        out.writeByte(0)
        out.writeShort(encodings.size)
        encodings.forEach { out.writeInt(it) }
        out.flush()
    }

    private fun requestUpdate(incremental: Boolean) {
        if (pendingRequest) return
        val out = output ?: return
        synchronized(out) {
            out.writeByte(3)
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0); out.writeShort(0)
            out.writeShort(width); out.writeShort(height)
            out.flush()
        }
        pendingRequest = true
    }

    private fun allocate(w: Int, h: Int) {
        synchronized(bitmapLock) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }
    }

    // ------------------------------------------------------------- decoding

    private fun handleFramebufferUpdate(inp: DataInputStream, scratch: ByteArray) {
        inp.skipBytes(1)
        val rects = inp.readUnsignedShort()
        for (i in 0 until rects) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            when (val encoding = inp.readInt()) {
                ENC_RAW -> decodeRaw(inp, x, y, w, h, scratch)
                ENC_COPYRECT -> decodeCopyRect(inp, x, y, w, h)
                ENC_RRE -> decodeRre(inp, x, y, w, h)
                ENC_HEXTILE -> decodeHextile(inp, x, y, w, h)
                ENC_DESKTOP_SIZE -> {
                    width = w
                    height = h
                    allocate(w, h)
                    _size.value = w to h
                    pendingRequest = false
                    requestUpdate(false)
                }
                else -> throw IOException("unsupported encoding $encoding")
            }
        }
        _frame.value = _frame.value + 1
    }

    private fun readPixels(inp: DataInputStream, count: Int, scratch: ByteArray): IntArray {
        val bytes = count * 4
        val buf = if (scratch.size >= bytes) scratch else ByteArray(bytes)
        inp.readFully(buf, 0, bytes)
        val out = IntArray(count)
        var p = 0
        for (i in 0 until count) {
            val b = buf[p].toInt() and 0xFF
            val g = buf[p + 1].toInt() and 0xFF
            val r = buf[p + 2].toInt() and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            p += 4
        }
        return out
    }

    private fun readPixel(inp: DataInputStream): Int {
        val b = inp.readUnsignedByte()
        val g = inp.readUnsignedByte()
        val r = inp.readUnsignedByte()
        inp.readUnsignedByte()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun blit(pixels: IntArray, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        synchronized(bitmapLock) {
            val bm = bitmap ?: return
            if (x + w > bm.width || y + h > bm.height) return
            bm.setPixels(pixels, 0, w, x, y, w, h)
        }
    }

    private fun fill(color: Int, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        blit(IntArray(w * h) { color }, x, y, w, h)
    }

    private fun decodeRaw(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int, scratch: ByteArray) {
        // Read row by row so a full-screen update does not need a 8 MB scratch buffer.
        val rowsPerChunk = (scratch.size / 4 / w.coerceAtLeast(1)).coerceIn(1, h.coerceAtLeast(1))
        var row = 0
        while (row < h) {
            val chunk = minOf(rowsPerChunk, h - row)
            val pixels = readPixels(inp, w * chunk, scratch)
            blit(pixels, x, y + row, w, chunk)
            row += chunk
        }
    }

    private fun decodeCopyRect(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val srcX = inp.readUnsignedShort()
        val srcY = inp.readUnsignedShort()
        synchronized(bitmapLock) {
            val bm = bitmap ?: return
            if (srcX + w > bm.width || srcY + h > bm.height || x + w > bm.width || y + h > bm.height) return
            val pixels = IntArray(w * h)
            bm.getPixels(pixels, 0, w, srcX, srcY, w, h)
            bm.setPixels(pixels, 0, w, x, y, w, h)
        }
    }

    private fun decodeRre(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val subrects = inp.readInt()
        fill(readPixel(inp), x, y, w, h)
        for (i in 0 until subrects) {
            val color = readPixel(inp)
            val sx = inp.readUnsignedShort()
            val sy = inp.readUnsignedShort()
            val sw = inp.readUnsignedShort()
            val sh = inp.readUnsignedShort()
            fill(color, x + sx, y + sy, sw, sh)
        }
    }

    private fun decodeHextile(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        var background = 0
        var foreground = 0
        val scratch = ByteArray(16 * 16 * 4)

        var ty = 0
        while (ty < h) {
            val th = minOf(16, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(16, w - tx)
                val subencoding = inp.readUnsignedByte()

                if (subencoding and HEXTILE_RAW != 0) {
                    val pixels = readPixels(inp, tw * th, scratch)
                    blit(pixels, x + tx, y + ty, tw, th)
                    tx += 16
                    continue
                }
                if (subencoding and HEXTILE_BG_SPECIFIED != 0) background = readPixel(inp)
                if (subencoding and HEXTILE_FG_SPECIFIED != 0) foreground = readPixel(inp)

                val tile = IntArray(tw * th) { background }
                if (subencoding and HEXTILE_ANY_SUBRECTS != 0) {
                    val count = inp.readUnsignedByte()
                    val colored = subencoding and HEXTILE_SUBRECTS_COLOURED != 0
                    for (i in 0 until count) {
                        val color = if (colored) readPixel(inp) else foreground
                        val xy = inp.readUnsignedByte()
                        val wh = inp.readUnsignedByte()
                        val sx = xy shr 4
                        val sy = xy and 0x0F
                        val sw = (wh shr 4) + 1
                        val sh = (wh and 0x0F) + 1
                        for (row in sy until minOf(sy + sh, th)) {
                            val base = row * tw
                            for (col in sx until minOf(sx + sw, tw)) tile[base + col] = color
                        }
                    }
                }
                blit(tile, x + tx, y + ty, tw, th)
                tx += 16
            }
            ty += 16
        }
    }

    // ------------------------------------------------------------------ input

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        val out = output ?: return
        runCatching {
            synchronized(out) {
                out.writeByte(5)
                out.writeByte(buttonMask)
                out.writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0)))
                out.writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
                out.flush()
            }
        }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        val out = output ?: return
        runCatching {
            synchronized(out) {
                out.writeByte(4)
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0)
                out.writeInt(keysym)
                out.flush()
            }
        }
    }

    fun typeKey(keysym: Int) {
        sendKey(keysym, true)
        sendKey(keysym, false)
    }

    fun typeText(text: String) {
        text.forEach { ch -> typeKey(Keysyms.forChar(ch)) }
    }

    companion object {
        private const val SEC_NONE = 1
        private const val SEC_VNC_AUTH = 2

        private const val ENC_RAW = 0
        private const val ENC_COPYRECT = 1
        private const val ENC_RRE = 2
        private const val ENC_HEXTILE = 5
        private const val ENC_DESKTOP_SIZE = -223

        private const val HEXTILE_RAW = 1
        private const val HEXTILE_BG_SPECIFIED = 2
        private const val HEXTILE_FG_SPECIFIED = 4
        private const val HEXTILE_ANY_SUBRECTS = 8
        private const val HEXTILE_SUBRECTS_COLOURED = 16
    }
}

/** X11 keysyms for the keys the on-screen controls and a hardware keyboard can produce. */
object Keysyms {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val INSERT = 0xFF63
    const val DELETE = 0xFFFF
    const val SHIFT_L = 0xFFE1
    const val CONTROL_L = 0xFFE3
    const val ALT_L = 0xFFE9
    const val SUPER_L = 0xFFEB
    const val F1 = 0xFFBE

    fun f(n: Int) = F1 + (n - 1)

    /** Latin-1 maps straight onto keysyms; anything else uses the Unicode range. */
    fun forChar(ch: Char): Int = when {
        ch == '\n' -> RETURN
        ch == '\t' -> TAB
        ch == '\b' -> BACKSPACE
        ch.code in 0x20..0xFF -> ch.code
        else -> 0x01000000 + ch.code
    }
}
