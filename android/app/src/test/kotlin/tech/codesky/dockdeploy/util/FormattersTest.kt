package tech.codesky.dockdeploy.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `formatBytes formats B KB MB GB cleanly`() {
        assertEquals("0 B", Formatters.formatBytes(0))
        assertEquals("512 B", Formatters.formatBytes(512))
        assertEquals("1.0 KB", Formatters.formatBytes(1024))
        assertEquals("2.5 MB", Formatters.formatBytes(2_621_440))
    }

    @Test
    fun `formatUptime formats days hours minutes`() {
        assertEquals("—", Formatters.formatUptime(0))
        assertEquals("1m 5s", Formatters.formatUptime(65))
        assertEquals("2h 5m", Formatters.formatUptime(7500))
        assertEquals("3d 1h", Formatters.formatUptime(3 * 86400 + 3600))
    }

    @Test
    fun `stripAnsi removes escape sequences`() {
        assertEquals("dockdeploy: error", Formatters.stripAnsi("\u001B[31;1mdockdeploy:\u001B[0m error"))
    }
}
