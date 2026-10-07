package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

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
    fun `authenticated readiness URL keeps its session query`() {
        val url = "http://127.0.0.1:4321/?token=test-session-token"
        assertEquals(url, DshWebServer.parseUrl("dsh web: $url  \r"))
        assertEquals(4321, DshWebServer.parsePort("dsh web: $url"))
    }

    @Test
    fun `readiness requires a complete local URL rather than a prefix match`() {
        for (line in listOf(
            "diagnostic: http://127.0.0.1:4321/?token=test-token",
            "dsh web: http://127.0.0.1:4321@remote.example/?token=test-token",
            "dsh web: http://127.0.0.1:432100/?token=test-token",
            "dsh web: http://127.0.0.1:4321/#token=test-token",
        )) assertNull(DshWebServer.parseUrl(line))
    }

    @Test
    fun `boot failures redact the web session query`() {
        val failure = DshWebServer.failureText(
            "dsh web: http://127.0.0.1:4321/?token=test-session-token\nfailed to start",
        )
        assertFalse(failure.contains("test-session-token"))
        assertFalse(failure.contains("token="))
    }
}
