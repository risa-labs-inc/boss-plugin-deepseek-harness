package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading the port off the harness's readiness line.
 *
 * The exact line, captured from `dsh 0.1.0-rc.7` with `--port 0`:
 *
 * ```
 * dsh web: http://127.0.0.1:62375
 * ```
 *
 * This is the whole reason the plugin does not pre-bind a port: reading what the
 * harness actually chose has no race, whereas binding a `ServerSocket(0)` and
 * passing the number along can lose the port between the close and the harness's
 * bind, surfacing as a failure the user did nothing to cause.
 */
class DshWebServerParseTest {

    @Test
    fun `the real readiness line yields its port`() {
        assertEquals(62375, DshWebServer.parsePort("dsh web: http://127.0.0.1:62375"))
    }

    @Test
    fun `trailing whitespace and carriage returns do not defeat it`() {
        assertEquals(3080, DshWebServer.parsePort("dsh web: http://127.0.0.1:3080  \r"))
    }

    @Test
    fun `a line with no url is not a readiness line`() {
        assertNull(DshWebServer.parsePort("dsh web: opening the default browser; pass --no-open to disable"))
        assertNull(DshWebServer.parsePort(""))
        assertNull(DshWebServer.parsePort("dsh: MISSING_CREDENTIAL: llm-deepseek: no API key"))
    }

    @Test
    fun `a non-loopback url is ignored`() {
        // The harness only binds 127.0.0.1 or 0.0.0.0, and a URL mentioned inside
        // some other diagnostic must not be mistaken for the port we serve on.
        assertNull(DshWebServer.parsePort("see https://github.com/deepseek-ai/deepseek-harness:443"))
        assertNull(DshWebServer.parsePort("dsh web: http://0.0.0.0:3080"))
    }

    @Test
    fun `an out-of-range port is rejected rather than truncated`() {
        assertNull(DshWebServer.parsePort("dsh web: http://127.0.0.1:99999"))
        assertNull(DshWebServer.parsePort("dsh web: http://127.0.0.1:0"))
    }

    @Test
    fun `the first loopback port on the line wins`() {
        assertEquals(4321, DshWebServer.parsePort("dsh web: http://127.0.0.1:4321 (was http://127.0.0.1:1111)"))
    }

    @Test
    fun `authenticated readiness retains the token but diagnostics do not`() {
        val url = "http://127.0.0.1:62375/?token=test_launch-token_123"
        assertEquals(url, DshWebServer.parseBrowserUrl("dsh web: $url (LAN: http://192.168.1.2:62375/?token=other)"))
        val running = DshServer.Running(62375, 42, url)
        assertEquals(url, running.browserUrl)
        assertEquals("http://127.0.0.1:62375", running.url)
        kotlin.test.assertFalse(running.toString().contains("test_launch"))
    }

    @Test
    fun `readiness rejects diagnostic urls malformed ports and foreign authorities`() {
        for (url in listOf(
            "http://127.0.0.1:123456", "http://127.0.0.1:1234.evil.test",
            "http://127.0.0.1:1234@evil.test", "http://127.0.0.1:1234/other",
            "http://127.0.0.1:1234/?token=ok&redirect=evil",
        )) assertNull(DshWebServer.parseBrowserUrl("dsh web: $url"))
        assertNull(DshWebServer.parseBrowserUrl("error: see http://127.0.0.1:1234"))
    }

    @Test
    fun `readiness probe accepts token exchange without following cookie redirect`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.rawQuery == "token=test_token") {
                exchange.responseHeaders.add("Location", "/")
                exchange.responseHeaders.add("Set-Cookie", "session=test; HttpOnly")
                exchange.sendResponseHeaders(303, -1)
            } else exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/"
            kotlin.test.assertFalse(DshWebServer.probe(url))
            kotlin.test.assertTrue(DshWebServer.probe("${url}?token=test_token"))
        } finally {
            server.stop(0)
        }
    }
}
