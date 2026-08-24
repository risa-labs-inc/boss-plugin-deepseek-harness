package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The BOSS MCP bridge is on unless the user turned it off, and the overlay it
 * passes is always aimed at an endpoint that answered a moment ago.
 *
 * Both halves are the same change. Default-off could afford to resolve the
 * endpoint once at load, because a user who had just clicked the toggle was
 * demonstrably looking at a running host. Default-on cannot: the plugin may load
 * before the host's MCP server does, and a bridge that reads "on" while the
 * harness silently got no tools is worse than one that reads "off".
 */
class DshBridgeDefaultTest {

    private val tempHome: File = File.createTempFile("dsh-home", "").let { probe ->
        probe.delete()
        probe.mkdirs()
        probe
    }

    private val env = mapOf(DshPaths.HOME_ENV to tempHome.absolutePath)

    private fun engine() = DshEngine(FakeServices.context(), env)

    @AfterTest
    fun cleanup() {
        tempHome.deleteRecursively()
    }

    @Test
    fun `a fresh engine has the bridge on`() {
        assertTrue(engine().bridgeEnabled.value)
    }

    @Test
    fun `the stored default is on, so an install that never chose gets it`() {
        assertEquals("true", DshServices.BRIDGE_DEFAULT)
    }

    @Test
    fun `on, with an endpoint, an overlay is written and passed`() {
        val engine = engine()
        engine.mcpEndpoint = { "boss" to "http://127.0.0.1:7679/mcp" }

        val overlay = assertNotNull(engine.bridgeOverlay())

        assertTrue(overlay.isFile)
        val body = overlay.readText()
        assertTrue(body.contains("serverName: boss"))
        assertTrue(body.contains("url: http://127.0.0.1:7679/mcp"))
    }

    /**
     * The reason the overlay is written per launch rather than read off disk: the
     * bound port can differ between runs, and BOSS's own doc for the controller
     * says so. An overlay left over from last time points the harness's MCP client
     * at whatever now holds that port, or at nothing.
     */
    @Test
    fun `a second launch rewrites the overlay from the endpoint of the moment`() {
        val engine = engine()
        engine.mcpEndpoint = { "boss" to "http://127.0.0.1:7679/mcp" }
        engine.bridgeOverlay()

        engine.mcpEndpoint = { "bossterm" to "http://127.0.0.1:7680/mcp" }
        val overlay = assertNotNull(engine.bridgeOverlay())

        val body = overlay.readText()
        assertTrue(body.contains("serverName: bossterm"))
        assertTrue(body.contains("url: http://127.0.0.1:7680/mcp"))
        assertFalse(body.contains("7679"))
    }

    /**
     * The load-order case, stated as the property that matters: a stale overlay on
     * disk must not be passed when nothing answers now. The harness would take it,
     * fail to initialise the row, and - as [DshFailure]'s reason for existing
     * shows - a plugin row that cannot initialise stops `dsh web` booting at all.
     */
    @Test
    fun `on, with no endpoint, a leftover overlay is not passed`() {
        val engine = engine()
        engine.mcpEndpoint = { "boss" to "http://127.0.0.1:7679/mcp" }
        val written = assertNotNull(engine.bridgeOverlay())
        assertTrue(written.isFile, "precondition: an overlay exists on disk")

        engine.mcpEndpoint = { null }

        assertNull(engine.bridgeOverlay())
        assertTrue(written.isFile, "the file is left alone, only not passed")
    }

    @Test
    fun `an engine nobody wired resolves no endpoint and passes nothing`() {
        assertNull(engine().bridgeOverlay())
    }

    @Test
    fun `off beats the endpoint`() {
        val engine = engine()
        engine.mcpEndpoint = { "boss" to "http://127.0.0.1:7679/mcp" }
        engine.restoreBridge(false)

        assertNull(engine.bridgeOverlay())
    }

    /**
     * A restore is not a decision: it must work with the host's MCP server still
     * starting, which is the whole reason it is separate from `setBridgeEnabled`.
     */
    @Test
    fun `restoring on needs no endpoint`() {
        val engine = engine()
        engine.restoreBridge(false)
        engine.restoreBridge(true)

        assertTrue(engine.bridgeEnabled.value)
    }
}
