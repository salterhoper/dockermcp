package tech.codesky.dockdeploy.ui.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    @Test
    fun `handles carriage return overwrite and newline`() {
        val emu = TerminalEmulator(initialCols = 40, initialRows = 10)
        emu.feed("hello world\rHELLO\nnext")
        val text = emu.plainText()
        assertEquals("HELLO world\nnext", text)
    }

    @Test
    fun `handles backspace and clear line`() {
        val emu = TerminalEmulator(initialCols = 40, initialRows = 10)
        emu.feed("abc\b\bxy")
        assertEquals("axy", emu.plainText())
        emu.feed("\r\u001B[2Kprompt$ ")
        assertEquals("prompt$", emu.plainText())
    }

    @Test
    fun `strips osc titles and handles ansi sgr`() {
        val emu = TerminalEmulator(initialCols = 40, initialRows = 10)
        emu.feed("\u001B]0;user@host\u0007\u001B[32mok\u001B[0m")
        assertEquals("ok", emu.plainText())
    }

    @Test
    fun `ctrl and alt modifier encoding matches termux`() {
        assertEquals("\u0003", TermuxKeyEncoder.encodeTypedInput("c", ctrlArmed = true, altArmed = false))
        assertEquals("\u0004", TermuxKeyEncoder.encodeTypedInput("d", ctrlArmed = true, altArmed = false))
        assertEquals("\u001Bx", TermuxKeyEncoder.encodeTypedInput("x", ctrlArmed = false, altArmed = true))
        assertTrue(TermuxKeyEncoder.encodeTypedInput("ls\n", ctrlArmed = false, altArmed = false) == "ls\r")
    }
}
