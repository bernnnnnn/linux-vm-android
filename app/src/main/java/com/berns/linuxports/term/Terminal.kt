package com.berns.linuxports.term

/**
 * A small VT100/xterm terminal: enough of the escape-sequence grammar to run bash,
 * apt, nano, htop and the rest of the things people actually open a shell for.
 */
class TerminalBuffer(var rows: Int, var cols: Int, private val scrollbackLimit: Int = 4000) {

    class Row(cols: Int) {
        var text = CharArray(cols) { ' ' }
        var fg = IntArray(cols) { DEFAULT_FG }
        var bg = IntArray(cols) { DEFAULT_BG }
        var flags = IntArray(cols)

        fun resize(newCols: Int) {
            if (text.size == newCols) return
            val old = text.size
            text = text.copyOf(newCols)
            fg = fg.copyOf(newCols)
            bg = bg.copyOf(newCols)
            flags = flags.copyOf(newCols)
            for (i in old until newCols) {
                text[i] = ' '; fg[i] = DEFAULT_FG; bg[i] = DEFAULT_BG; flags[i] = 0
            }
        }

        fun clear(from: Int, to: Int, fgv: Int, bgv: Int) {
            for (i in from.coerceAtLeast(0) until minOf(to, text.size)) {
                text[i] = ' '; fg[i] = fgv; bg[i] = bgv; flags[i] = 0
            }
        }
    }

    var screen: MutableList<Row> = MutableList(rows) { Row(cols) }
    val scrollback: ArrayDeque<Row> = ArrayDeque()

    fun row(y: Int): Row = screen[y.coerceIn(0, screen.size - 1)]

    fun scrollUp(top: Int, bottom: Int, keepHistory: Boolean, fgv: Int, bgv: Int) {
        if (top >= screen.size || bottom >= screen.size || top > bottom) return
        val removed = screen.removeAt(top)
        if (keepHistory && top == 0 && scrollbackLimit > 0) {
            scrollback.addLast(removed)
            while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
        }
        val fresh = Row(cols)
        fresh.clear(0, cols, fgv, bgv)
        screen.add(bottom, fresh)
    }

    fun scrollDown(top: Int, bottom: Int, fgv: Int, bgv: Int) {
        if (top >= screen.size || bottom >= screen.size || top > bottom) return
        screen.removeAt(bottom)
        val fresh = Row(cols)
        fresh.clear(0, cols, fgv, bgv)
        screen.add(top, fresh)
    }

    fun resize(newRows: Int, newCols: Int) {
        if (newCols != cols) screen.forEach { it.resize(newCols) }
        while (screen.size > newRows) {
            val removed = screen.removeAt(0)
            if (scrollbackLimit > 0) {
                scrollback.addLast(removed)
                while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
            }
        }
        while (screen.size < newRows) {
            screen.add(Row(newCols).also { it.clear(0, newCols, DEFAULT_FG, DEFAULT_BG) })
        }
        rows = newRows; cols = newCols
    }

    companion object {
        const val DEFAULT_FG = -1
        const val DEFAULT_BG = -2
        const val FLAG_BOLD = 1
        const val FLAG_UNDERLINE = 2
        const val FLAG_ITALIC = 4
        const val FLAG_INVERSE = 8
    }
}

class TerminalEmulator(rows: Int, cols: Int) {

    var buffer = TerminalBuffer(rows, cols)
        private set
    private var altBuffer: TerminalBuffer? = null

    var cursorX = 0; private set
    var cursorY = 0; private set
    var cursorVisible = true; private set
    var title: String = ""; private set

    /** Bumped on every change so Compose knows when to redraw. */
    var revision = 0L; private set

    val rows get() = buffer.rows
    val cols get() = buffer.cols

    private var fg = TerminalBuffer.DEFAULT_FG
    private var bg = TerminalBuffer.DEFAULT_BG
    private var flags = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var wrapPending = false
    private var autoWrap = true
    private var savedX = 0
    private var savedY = 0
    private var savedFg = fg
    private var savedBg = bg
    private var savedFlags = 0
    private var applicationCursorKeys = false

    val cursorKeysApplication get() = applicationCursorKeys

    private enum class State { GROUND, ESC, CSI, OSC, CHARSET }

    private var state = State.GROUND
    private var params = StringBuilder()
    private var oscBuf = StringBuilder()
    private var utf8Remaining = 0
    private var utf8Cp = 0

    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return
        buffer.resize(newRows, newCols)
        altBuffer?.resize(newRows, newCols)
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorY = cursorY.coerceIn(0, newRows - 1)
        cursorX = cursorX.coerceIn(0, newCols - 1)
        revision++
    }

    fun feed(bytes: ByteArray, len: Int) {
        for (i in 0 until len) process(bytes[i].toInt() and 0xFF)
        revision++
    }

    private fun process(b: Int) {
        if (utf8Remaining > 0 && state == State.GROUND) {
            if (b and 0xC0 == 0x80) {
                utf8Cp = (utf8Cp shl 6) or (b and 0x3F)
                if (--utf8Remaining == 0) putCodePoint(utf8Cp)
                return
            }
            utf8Remaining = 0
        }
        when (state) {
            State.GROUND -> ground(b)
            State.ESC -> esc(b)
            State.CSI -> csi(b)
            State.OSC -> osc(b)
            State.CHARSET -> state = State.GROUND
        }
    }

    private fun ground(b: Int) {
        when {
            b == 0x1B -> { state = State.ESC; params.setLength(0) }
            b == 0x07 -> Unit
            b == 0x08 -> { if (cursorX > 0) cursorX--; wrapPending = false }
            b == 0x09 -> cursorX = minOf(((cursorX / 8) + 1) * 8, cols - 1)
            b == 0x0A || b == 0x0B || b == 0x0C -> lineFeed()
            b == 0x0D -> { cursorX = 0; wrapPending = false }
            b < 0x20 -> Unit
            b < 0x80 -> putCodePoint(b)
            b and 0xE0 == 0xC0 -> { utf8Cp = b and 0x1F; utf8Remaining = 1 }
            b and 0xF0 == 0xE0 -> { utf8Cp = b and 0x0F; utf8Remaining = 2 }
            b and 0xF8 == 0xF0 -> { utf8Cp = b and 0x07; utf8Remaining = 3 }
            else -> putCodePoint('?'.code)
        }
    }

    private fun putCodePoint(cp: Int) {
        if (wrapPending) {
            cursorX = 0
            lineFeed()
            wrapPending = false
        }
        val row = buffer.row(cursorY)
        if (cursorX < cols) {
            row.text[cursorX] = if (cp in 0x20..0xFFFF) cp.toChar() else REPLACEMENT
            row.fg[cursorX] = fg
            row.bg[cursorX] = bg
            row.flags[cursorX] = flags
        }
        if (cursorX + 1 >= cols) {
            if (autoWrap) wrapPending = true else cursorX = cols - 1
        } else {
            cursorX++
        }
    }

    private fun lineFeed() {
        when {
            cursorY == scrollBottom -> buffer.scrollUp(scrollTop, scrollBottom, altBuffer == null, fg, bg)
            cursorY < rows - 1 -> cursorY++
        }
        wrapPending = false
    }

    private fun esc(b: Int) {
        when (b.toChar()) {
            '[' -> { state = State.CSI; params.setLength(0) }
            ']' -> { state = State.OSC; oscBuf.setLength(0) }
            '(', ')', '*', '+' -> state = State.CHARSET
            'M' -> {
                if (cursorY == scrollTop) buffer.scrollDown(scrollTop, scrollBottom, fg, bg) else if (cursorY > 0) cursorY--
                state = State.GROUND
            }
            'D' -> { lineFeed(); state = State.GROUND }
            'E' -> { cursorX = 0; lineFeed(); state = State.GROUND }
            '7' -> { savedX = cursorX; savedY = cursorY; savedFg = fg; savedBg = bg; savedFlags = flags; state = State.GROUND }
            '8' -> { cursorX = savedX; cursorY = savedY; fg = savedFg; bg = savedBg; flags = savedFlags; state = State.GROUND }
            'c' -> { reset(); state = State.GROUND }
            else -> state = State.GROUND
        }
    }

    private fun osc(b: Int) {
        if (b == 0x07 || b == 0x1B) {
            val s = oscBuf.toString()
            val idx = s.indexOf(';')
            if (idx >= 0 && (s.startsWith("0") || s.startsWith("2"))) title = s.substring(idx + 1)
            state = State.GROUND
            return
        }
        if (oscBuf.length < 512) oscBuf.append(b.toChar())
    }

    private fun csi(b: Int) {
        val c = b.toChar()
        if (c in '0'..'9' || c == ';' || c == '?' || c == '>' || c == '!' || c == ' ' ||
            c == '$' || c == '"' || c == '\''
        ) {
            if (params.length < 64) params.append(c)
            return
        }
        state = State.GROUND
        val priv = params.startsWith("?")
        val raw = params.toString().trimStart('?', '>', '!')
        val nums = raw.split(';').map { it.trim().toIntOrNull() ?: 0 }
        fun p(i: Int) = nums.getOrNull(i)?.takeIf { it != 0 } ?: 1
        fun p0(i: Int) = nums.getOrNull(i) ?: 0

        when (c) {
            'A' -> { cursorY = (cursorY - p(0)).coerceAtLeast(0); wrapPending = false }
            'B' -> { cursorY = (cursorY + p(0)).coerceAtMost(rows - 1); wrapPending = false }
            'C' -> { cursorX = (cursorX + p(0)).coerceAtMost(cols - 1); wrapPending = false }
            'D' -> { cursorX = (cursorX - p(0)).coerceAtLeast(0); wrapPending = false }
            'E' -> { cursorY = (cursorY + p(0)).coerceAtMost(rows - 1); cursorX = 0 }
            'F' -> { cursorY = (cursorY - p(0)).coerceAtLeast(0); cursorX = 0 }
            'G', '`' -> { cursorX = (p(0) - 1).coerceIn(0, cols - 1); wrapPending = false }
            'd' -> cursorY = (p(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorY = (p(0) - 1).coerceIn(0, rows - 1)
                cursorX = (p(1) - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'J' -> eraseInDisplay(p0(0))
            'K' -> eraseInLine(p0(0))
            'L' -> repeat(p(0)) { if (cursorY in scrollTop..scrollBottom) buffer.scrollDown(cursorY, scrollBottom, fg, bg) }
            'M' -> repeat(p(0)) { if (cursorY in scrollTop..scrollBottom) buffer.scrollUp(cursorY, scrollBottom, false, fg, bg) }
            'P' -> deleteChars(p(0))
            '@' -> insertChars(p(0))
            'X' -> buffer.row(cursorY).clear(cursorX, cursorX + p(0), fg, bg)
            'S' -> repeat(p(0)) { buffer.scrollUp(scrollTop, scrollBottom, false, fg, bg) }
            'T' -> repeat(p(0)) { buffer.scrollDown(scrollTop, scrollBottom, fg, bg) }
            'r' -> {
                scrollTop = (p(0) - 1).coerceIn(0, rows - 1)
                scrollBottom = ((nums.getOrNull(1)?.takeIf { it != 0 } ?: rows) - 1).coerceIn(scrollTop, rows - 1)
                cursorX = 0
                cursorY = scrollTop
            }
            'm' -> sgr(nums, raw)
            'h' -> setMode(nums, priv, true)
            'l' -> setMode(nums, priv, false)
            's' -> { savedX = cursorX; savedY = cursorY }
            'u' -> { cursorX = savedX; cursorY = savedY }
            else -> Unit
        }
    }

    private fun setMode(nums: List<Int>, priv: Boolean, on: Boolean) {
        if (!priv) return
        for (n in nums) when (n) {
            1 -> applicationCursorKeys = on
            7 -> autoWrap = on
            25 -> cursorVisible = on
            47, 1047, 1049 -> switchAltScreen(on)
        }
    }

    private fun switchAltScreen(on: Boolean) {
        if (on && altBuffer == null) {
            savedX = cursorX
            savedY = cursorY
            altBuffer = buffer
            buffer = TerminalBuffer(rows, cols, scrollbackLimit = 0)
            buffer.screen.forEach { it.clear(0, cols, fg, bg) }
            cursorX = 0
            cursorY = 0
        } else if (!on && altBuffer != null) {
            buffer = altBuffer!!
            altBuffer = null
            cursorX = savedX.coerceIn(0, cols - 1)
            cursorY = savedY.coerceIn(0, rows - 1)
        }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                buffer.row(cursorY).clear(cursorX, cols, fg, bg)
                for (y in cursorY + 1 until rows) buffer.row(y).clear(0, cols, fg, bg)
            }
            1 -> {
                for (y in 0 until cursorY) buffer.row(y).clear(0, cols, fg, bg)
                buffer.row(cursorY).clear(0, cursorX + 1, fg, bg)
            }
            else -> for (y in 0 until rows) buffer.row(y).clear(0, cols, fg, bg)
        }
    }

    private fun eraseInLine(mode: Int) {
        val r = buffer.row(cursorY)
        when (mode) {
            0 -> r.clear(cursorX, cols, fg, bg)
            1 -> r.clear(0, cursorX + 1, fg, bg)
            else -> r.clear(0, cols, fg, bg)
        }
    }

    private fun deleteChars(n: Int) {
        val r = buffer.row(cursorY)
        val count = n.coerceAtMost(cols - cursorX)
        for (x in cursorX until cols - count) {
            r.text[x] = r.text[x + count]
            r.fg[x] = r.fg[x + count]
            r.bg[x] = r.bg[x + count]
            r.flags[x] = r.flags[x + count]
        }
        r.clear(cols - count, cols, fg, bg)
    }

    private fun insertChars(n: Int) {
        val r = buffer.row(cursorY)
        val count = n.coerceAtMost(cols - cursorX)
        for (x in cols - 1 downTo cursorX + count) {
            r.text[x] = r.text[x - count]
            r.fg[x] = r.fg[x - count]
            r.bg[x] = r.bg[x - count]
            r.flags[x] = r.flags[x - count]
        }
        r.clear(cursorX, cursorX + count, fg, bg)
    }

    private fun sgr(nums: List<Int>, raw: String) {
        if (raw.isEmpty()) {
            fg = TerminalBuffer.DEFAULT_FG
            bg = TerminalBuffer.DEFAULT_BG
            flags = 0
            return
        }
        var i = 0
        while (i < nums.size) {
            when (val n = nums[i]) {
                0 -> { fg = TerminalBuffer.DEFAULT_FG; bg = TerminalBuffer.DEFAULT_BG; flags = 0 }
                1 -> flags = flags or TerminalBuffer.FLAG_BOLD
                3 -> flags = flags or TerminalBuffer.FLAG_ITALIC
                4 -> flags = flags or TerminalBuffer.FLAG_UNDERLINE
                7 -> flags = flags or TerminalBuffer.FLAG_INVERSE
                22 -> flags = flags and TerminalBuffer.FLAG_BOLD.inv()
                23 -> flags = flags and TerminalBuffer.FLAG_ITALIC.inv()
                24 -> flags = flags and TerminalBuffer.FLAG_UNDERLINE.inv()
                27 -> flags = flags and TerminalBuffer.FLAG_INVERSE.inv()
                in 30..37 -> fg = Palette.ANSI[n - 30]
                in 90..97 -> fg = Palette.ANSI[8 + n - 90]
                39 -> fg = TerminalBuffer.DEFAULT_FG
                in 40..47 -> bg = Palette.ANSI[n - 40]
                in 100..107 -> bg = Palette.ANSI[8 + n - 100]
                49 -> bg = TerminalBuffer.DEFAULT_BG
                38, 48 -> {
                    when (nums.getOrNull(i + 1)) {
                        5 -> {
                            val c = Palette.xterm256(nums.getOrNull(i + 2) ?: 0)
                            if (n == 38) fg = c else bg = c
                            i += 2
                        }
                        2 -> {
                            val r = nums.getOrNull(i + 2) ?: 0
                            val g = nums.getOrNull(i + 3) ?: 0
                            val bl = nums.getOrNull(i + 4) ?: 0
                            val c = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                            if (n == 38) fg = c else bg = c
                            i += 4
                        }
                    }
                }
            }
            i++
        }
    }

    fun reset() {
        buffer = TerminalBuffer(rows, cols)
        altBuffer = null
        cursorX = 0
        cursorY = 0
        fg = TerminalBuffer.DEFAULT_FG
        bg = TerminalBuffer.DEFAULT_BG
        flags = 0
        scrollTop = 0
        scrollBottom = rows - 1
        cursorVisible = true
        autoWrap = true
        revision++
    }

    companion object {
        private const val REPLACEMENT = '�'
    }
}

object Palette {
    val ANSI = intArrayOf(
        0xFF2E3436.toInt(), 0xFFCC0000.toInt(), 0xFF4E9A06.toInt(), 0xFFC4A000.toInt(),
        0xFF3465A4.toInt(), 0xFF75507B.toInt(), 0xFF06989A.toInt(), 0xFFD3D7CF.toInt(),
        0xFF555753.toInt(), 0xFFEF2929.toInt(), 0xFF8AE234.toInt(), 0xFFFCE94F.toInt(),
        0xFF729FCF.toInt(), 0xFFAD7FA8.toInt(), 0xFF34E2E2.toInt(), 0xFFEEEEEC.toInt()
    )

    private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

    fun xterm256(i: Int): Int = when {
        i < 16 -> ANSI[i.coerceAtLeast(0)]
        i < 232 -> {
            val n = i - 16
            val r = CUBE[(n / 36) % 6]
            val g = CUBE[(n / 6) % 6]
            val b = CUBE[n % 6]
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        else -> {
            val v = (8 + (i - 232) * 10).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
}
