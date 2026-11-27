package tech.codesky.dockdeploy.ui.servers

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * Lightweight VT100/ANSI terminal screen buffer & scrollback emulator.
 *
 * Handles cursor positioning, line overwrite (\r), backspace (\b), erase commands
 * (CSI J / K), alternate screen buffer (?1049h/l), OSC title stripping, and SGR
 * foreground ANSI colors so shells, progress bars, and TUI tools render cleanly.
 */
class TerminalEmulator(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val maxScrollback: Int = 1500,
) {
    data class Cell(
        val ch: Char = ' ',
        val fg: Color? = null,
        val bold: Boolean = false,
    )

    var cols: Int = initialCols.coerceIn(20, 300)
        private set
    var rows: Int = initialRows.coerceIn(5, 120)
        private set

    private var screen = Array(rows) { Array(cols) { Cell() } }
    private var altScreen: Array<Array<Cell>>? = null
    private val scrollback = ArrayDeque<Array<Cell>>()

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    private var savedRow = 0
    private var savedCol = 0
    private var currentFg: Color? = null
    private var currentBold: Boolean = false

    private enum class ParseState { GROUND, ESC, CSI, OSC, OSC_ESC }
    private var parseState = ParseState.GROUND
    private val escBuf = StringBuilder()

    @Synchronized
    fun resize(newCols: Int, newRows: Int): Boolean {
        val c = newCols.coerceIn(20, 300)
        val r = newRows.coerceIn(5, 120)
        if (c == cols && r == rows) return false

        val next = Array(r) { rowIdx ->
            Array(c) { colIdx ->
                if (rowIdx < screen.size && colIdx < screen[rowIdx].size) screen[rowIdx][colIdx] else Cell()
            }
        }
        screen = next
        cols = c
        rows = r
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        return true
    }

    @Synchronized
    fun clear() {
        screen = Array(rows) { Array(cols) { Cell() } }
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
    }

    @Synchronized
    fun feed(text: String) {
        for (ch in text) {
            processChar(ch)
        }
    }

    private fun processChar(ch: Char) {
        when (parseState) {
            ParseState.GROUND -> handleGround(ch)
            ParseState.ESC -> handleEsc(ch)
            ParseState.CSI -> handleCsi(ch)
            ParseState.OSC -> {
                if (ch == '\u0007') parseState = ParseState.GROUND
                else if (ch == '\u001B') parseState = ParseState.OSC_ESC
            }
            ParseState.OSC_ESC -> {
                parseState = if (ch == '\\') ParseState.GROUND else ParseState.OSC
            }
        }
    }

    private fun handleGround(ch: Char) {
        when (ch) {
            '\u001B' -> {
                escBuf.clear()
                parseState = ParseState.ESC
            }
            '\r' -> cursorCol = 0
            '\n' -> newLine()
            '\b' -> if (cursorCol > 0) cursorCol--
            '\t' -> {
                val nextStop = ((cursorCol / 8) + 1) * 8
                cursorCol = nextStop.coerceAtMost(cols - 1)
            }
            '\u0007' -> Unit
            else -> {
                if (ch.code >= 32) {
                    if (cursorCol >= cols) {
                        cursorCol = 0
                        newLine()
                    }
                    screen[cursorRow][cursorCol] = Cell(ch, currentFg, currentBold)
                    cursorCol++
                }
            }
        }
    }

    private fun handleEsc(ch: Char) {
        when (ch) {
            '[' -> {
                escBuf.clear()
                parseState = ParseState.CSI
            }
            ']' -> {
                escBuf.clear()
                parseState = ParseState.OSC
            }
            '7' -> {
                savedRow = cursorRow
                savedCol = cursorCol
                parseState = ParseState.GROUND
            }
            '8' -> {
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
                parseState = ParseState.GROUND
            }
            'c' -> {
                clear()
                currentFg = null
                currentBold = false
                parseState = ParseState.GROUND
            }
            else -> parseState = ParseState.GROUND
        }
    }

    private fun handleCsi(ch: Char) {
        if (ch.code in 0x40..0x7E) {
            executeCsi(escBuf.toString(), ch)
            parseState = ParseState.GROUND
        } else {
            if (escBuf.length < 64) escBuf.append(ch)
        }
    }

    private fun executeCsi(rawParams: String, cmd: Char) {
        if (rawParams.startsWith("?")) {
            val code = rawParams.drop(1).toIntOrNull()
            if (code == 1049 || code == 47 || code == 1047) {
                if (cmd == 'h' && altScreen == null) {
                    altScreen = screen
                    screen = Array(rows) { Array(cols) { Cell() } }
                    cursorRow = 0
                    cursorCol = 0
                } else if (cmd == 'l' && altScreen != null) {
                    screen = altScreen!!
                    altScreen = null
                    cursorRow = cursorRow.coerceIn(0, rows - 1)
                    cursorCol = cursorCol.coerceIn(0, cols - 1)
                }
            }
            return
        }

        val params = if (rawParams.isEmpty()) emptyList()
        else rawParams.split(';').map { it.toIntOrNull() ?: 0 }

        when (cmd) {
            'A' -> cursorRow = (cursorRow - (params.firstOrNull()?.coerceAtLeast(1) ?: 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + (params.firstOrNull()?.coerceAtLeast(1) ?: 1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + (params.firstOrNull()?.coerceAtLeast(1) ?: 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - (params.firstOrNull()?.coerceAtLeast(1) ?: 1)).coerceAtLeast(0)
            'G' -> cursorCol = ((params.firstOrNull() ?: 1) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                val r = (params.getOrNull(0)?.takeIf { it > 0 } ?: 1) - 1
                val c = (params.getOrNull(1)?.takeIf { it > 0 } ?: 1) - 1
                cursorRow = r.coerceIn(0, rows - 1)
                cursorCol = c.coerceIn(0, cols - 1)
            }
            'J' -> {
                when (params.firstOrNull() ?: 0) {
                    0 -> {
                        for (c in cursorCol until cols) screen[cursorRow][c] = Cell()
                        for (r in cursorRow + 1 until rows) screen[r] = Array(cols) { Cell() }
                    }
                    1 -> {
                        for (r in 0 until cursorRow) screen[r] = Array(cols) { Cell() }
                        for (c in 0..cursorCol.coerceAtMost(cols - 1)) screen[cursorRow][c] = Cell()
                    }
                    2, 3 -> {
                        screen = Array(rows) { Array(cols) { Cell() } }
                        if ((params.firstOrNull() ?: 0) == 3) scrollback.clear()
                    }
                }
            }
            'K' -> {
                when (params.firstOrNull() ?: 0) {
                    0 -> for (c in cursorCol until cols) screen[cursorRow][c] = Cell()
                    1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) screen[cursorRow][c] = Cell()
                    2 -> screen[cursorRow] = Array(cols) { Cell() }
                }
            }
            'm' -> applySgr(if (params.isEmpty()) listOf(0) else params)
        }
    }

    private fun applySgr(codes: List<Int>) {
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> {
                    currentFg = null
                    currentBold = false
                }
                1 -> currentBold = true
                22 -> currentBold = false
                in 30..37 -> currentFg = ansiBasicColor(code - 30, bright = false)
                39 -> currentFg = null
                in 90..97 -> currentFg = ansiBasicColor(code - 90, bright = true)
                38 -> {
                    if (i + 2 < codes.size && codes[i + 1] == 5) {
                        currentFg = ansi256Color(codes[i + 2])
                        i += 2
                    } else if (i + 4 < codes.size && codes[i + 1] == 2) {
                        currentFg = Color(
                            codes[i + 2].coerceIn(0, 255),
                            codes[i + 3].coerceIn(0, 255),
                            codes[i + 4].coerceIn(0, 255),
                        )
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun newLine() {
        cursorCol = 0
        if (cursorRow < rows - 1) {
            cursorRow++
        } else {
            if (altScreen == null) {
                if (scrollback.size >= maxScrollback) scrollback.removeFirst()
                scrollback.addLast(screen[0])
            }
            for (r in 0 until rows - 1) {
                screen[r] = screen[r + 1]
            }
            screen[rows - 1] = Array(cols) { Cell() }
        }
    }

    @Synchronized
    fun render(defaultFg: Color, cursorBg: Color, cursorFg: Color): AnnotatedString {
        // Trim trailing blank rows on active screen below cursor so viewport doesn't show empty padding lines unnecessarily
        var lastNonEmpty = cursorRow
        for (r in rows - 1 downTo cursorRow + 1) {
            if (screen[r].any { it.ch != ' ' }) {
                lastNonEmpty = r
                break
            }
        }

        return buildAnnotatedString {
            scrollback.forEach { row ->
                appendRow(row, cursorAtCol = -1, defaultFg = defaultFg, cursorBg = cursorBg, cursorFg = cursorFg)
                append('\n')
            }
            for (r in 0..lastNonEmpty) {
                val cCol = if (r == cursorRow) cursorCol.coerceIn(0, cols - 1) else -1
                appendRow(screen[r], cursorAtCol = cCol, defaultFg = defaultFg, cursorBg = cursorBg, cursorFg = cursorFg)
                if (r < lastNonEmpty) append('\n')
            }
        }
    }

    @Synchronized
    fun plainText(): String = buildString {
        scrollback.forEach { row ->
            append(rowText(row))
            append('\n')
        }
        for (r in 0 until rows) {
            append(rowText(screen[r]))
            if (r < rows - 1) append('\n')
        }
    }.trimEnd()

    private fun rowText(row: Array<Cell>): String {
        var end = row.size
        while (end > 0 && row[end - 1].ch == ' ') end--
        return buildString(end) {
            for (i in 0 until end) append(row[i].ch)
        }
    }

    private fun AnnotatedString.Builder.appendRow(
        row: Array<Cell>,
        cursorAtCol: Int,
        defaultFg: Color,
        cursorBg: Color,
        cursorFg: Color,
    ) {
        var end = row.size
        while (end > 0 && row[end - 1].ch == ' ') end--
        val limit = maxOf(end, if (cursorAtCol >= 0) cursorAtCol + 1 else 0).coerceAtMost(row.size)

        for (c in 0 until limit) {
            val cell = row[c]
            if (c == cursorAtCol) {
                pushStyle(SpanStyle(color = cursorFg, background = cursorBg, fontWeight = FontWeight.Bold))
                append(cell.ch)
                pop()
            } else {
                val fg = cell.fg ?: defaultFg
                if (fg != defaultFg || cell.bold) {
                    pushStyle(
                        SpanStyle(
                            color = fg,
                            fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                    append(cell.ch)
                    pop()
                } else {
                    append(cell.ch)
                }
            }
        }
    }

    private fun ansiBasicColor(idx: Int, bright: Boolean): Color = when (idx) {
        0 -> if (bright) Color(0xFF71717A) else Color(0xFF27272A)
        1 -> if (bright) Color(0xFFF87171) else Color(0xFFEF4444)
        2 -> if (bright) Color(0xFF4ADE80) else Color(0xFF22C55E)
        3 -> if (bright) Color(0xFFFACC15) else Color(0xFFEAB308)
        4 -> if (bright) Color(0xFF93C5FD) else Color(0xFF60A5FA)
        5 -> if (bright) Color(0xFFE879F9) else Color(0xFFC084FC)
        6 -> if (bright) Color(0xFF2DD4BF) else Color(0xFF14B8A6)
        7 -> if (bright) Color(0xFFFFFFFF) else Color(0xFFE4E4E7)
        else -> Color(0xFFF4F4F5)
    }

    private fun ansi256Color(n: Int): Color = when {
        n < 8 -> ansiBasicColor(n, bright = false)
        n < 16 -> ansiBasicColor(n - 8, bright = true)
        n in 16..231 -> {
            val v = n - 16
            val r = (v / 36) * 51
            val g = ((v % 36) / 6) * 51
            val b = (v % 6) * 51
            Color(r, g, b)
        }
        n in 232..255 -> {
            val gray = 8 + (n - 232) * 10
            Color(gray, gray, gray)
        }
        else -> Color(0xFFF4F4F5)
    }
}

object TermuxKeyEncoder {
    fun encodeTypedInput(raw: String, ctrlArmed: Boolean, altArmed: Boolean): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder()
        var ctrl = ctrlArmed
        var alt = altArmed
        for (ch in raw) {
            val normalized = if (ch == '\n') '\r' else ch
            if (ctrl) {
                val ctrlChar = toControlChar(normalized)
                if (ctrlChar != null) {
                    if (alt) sb.append('\u001B')
                    sb.append(ctrlChar)
                    ctrl = false
                    alt = false
                    continue
                }
                ctrl = false
            }
            if (alt) {
                sb.append('\u001B')
                alt = false
            }
            sb.append(normalized)
        }
        return sb.toString()
    }

    private fun toControlChar(ch: Char): Char? {
        val lower = ch.lowercaseChar()
        return when {
            lower in 'a'..'z' -> (lower.code - 'a'.code + 1).toChar()
            ch == '@' || ch == ' ' -> '\u0000'
            ch == '[' -> '\u001B'
            ch == '\\' -> '\u001C'
            ch == ']' -> '\u001D'
            ch == '^' -> '\u001E'
            ch == '_' -> '\u001F'
            else -> null
        }
    }
}
