package tech.codesky.dockdeploy.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostUrlNormalizerTest {

    @Test
    fun `public domain defaults to https`() {
        val result = HostUrlNormalizer.normalize("dc.codesky.tech/").getOrThrow()
        assertEquals("https://dc.codesky.tech", result.baseUrl)
        assertEquals("https://dc.codesky.tech", result.origin)
    }

    @Test
    fun `explicit http and port are preserved`() {
        val result = HostUrlNormalizer.normalize("http://192.168.1.50:8080/").getOrThrow()
        assertEquals("http://192.168.1.50:8080", result.baseUrl)
        assertEquals("http://192.168.1.50:8080", result.origin)
    }

    @Test
    fun `private lan ip without scheme defaults to http`() {
        val result = HostUrlNormalizer.normalize("10.0.2.2:8080").getOrThrow()
        assertEquals("http://10.0.2.2:8080", result.baseUrl)
    }

    @Test
    fun `blank input fails`() {
        val result = HostUrlNormalizer.normalize("   ")
        assertTrue(result.isFailure)
    }

    @Test
    fun `websocket url conversion handles https and http`() {
        assertEquals(
            "wss://dc.codesky.tech/api/servers/s1/terminal?cols=80&rows=24",
            HostUrlNormalizer.toWebSocketUrl("https://dc.codesky.tech", "/servers/s1/terminal?cols=80&rows=24"),
        )
        assertEquals(
            "ws://10.0.2.2:8080/api/servers/s1/terminal",
            HostUrlNormalizer.toWebSocketUrl("http://10.0.2.2:8080", "/servers/s1/terminal"),
        )
    }
}
