package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The MCP-bridge overlay: right shape, and nothing of the user's touched.
 *
 * The shape assertions are not cosmetic. Verified against `dsh 0.1.0-rc.7`: a
 * top-level entry carrying a bare `id` is an *override* of an existing row and
 * silently does nothing when no such row exists, while `- insert:` adds one. An
 * overlay that got that wrong would produce a green toggle, a clean
 * `--dump-config`, and no BOSS tools in the harness.
 */
class DshBridgeOverlayTest {

    private val tempHome: File = File.createTempFile("dsh-home", "").let { probe ->
        probe.delete()
        probe.mkdirs()
        probe
    }

    private val env = mapOf(DshPaths.HOME_ENV to tempHome.absolutePath)
    private val bridge = DshMcpBridge(env)

    @AfterTest
    fun cleanup() {
        tempHome.deleteRecursively()
    }

    @Test
    fun `the overlay inserts a row rather than overriding one`() {
        val yaml = bridge.overlayYaml("http://127.0.0.1:7677/mcp")

        assertTrue(
            yaml.lineSequence().any { it.trimEnd() == "- insert:" },
            "must use the loader's insert form, or the row is an override of nothing:\n$yaml",
        )
        assertTrue(yaml.contains("- id: ${DshMcpBridge.ROW_ID}"))
        assertTrue(yaml.contains("name: '@deepseek-ai/dsh-mcp-client'"))
    }

    @Test
    fun `the overlay names the streamable-http transport and the url`() {
        val yaml = bridge.overlayYaml("http://127.0.0.1:9999/mcp")

        assertTrue(yaml.contains("transport: streamable-http"))
        assertTrue(yaml.contains("url: http://127.0.0.1:9999/mcp"))
        assertTrue(yaml.contains("serverName: ${DshMcpBridge.SERVER_NAME}"))
    }

    @Test
    fun `the server name fixes the tool prefix agents already use`() {
        // Harness tools arrive as mcp__<serverName>__<tool>. Naming the server
        // anything else would mean a BOSS skill or prompt that says
        // mcp__boss__git_status is wrong on the harness side only.
        assertEquals("boss", DshMcpBridge.SERVER_NAME)
        assertTrue(
            DshMcpBridge.SERVER_NAME.matches(Regex("[A-Za-z0-9_-]{1,32}")),
            "the harness constrains serverName to [A-Za-z0-9_-]{1,32}",
        )
    }

    @Test
    fun `writeOverlay lands under the plugin's own directory, not a user file`() {
        val written = bridge.writeOverlay("http://127.0.0.1:7677/mcp")

        assertTrue(written.isFile)
        assertEquals(DshPaths.overlayDir(env).absolutePath, written.parentFile.absolutePath)
        // The two files the user owns. Editing either would be wrong: a patch
        // replaces a row's whole config rather than merging, so a rewrite can drop
        // settings silently.
        assertFalse(written.absolutePath.endsWith("cordis.patch.yml"))
        assertFalse(written.absolutePath.contains("${File.separator}profiles${File.separator}"))
    }

    @Test
    fun `enabling the bridge leaves a pre-existing user patch layer byte-identical`() {
        val profile = DshPaths.profileDir("web", env).apply { mkdirs() }
        val userLayer = File(profile, "cordis.patch.yml")
        val original = "# my own layer\n- id: system-prompt\n  config:\n    persona: mine\n"
        userLayer.writeText(original)
        val homeLayer = File(DshPaths.home(env), "cordis.patch.yml")
        homeLayer.writeText("[]\n")

        bridge.writeOverlay("http://127.0.0.1:7677/mcp")

        assertEquals(original, userLayer.readText(), "the profile's own patch layer must be untouched")
        assertEquals("[]\n", homeLayer.readText(), "the home patch layer must be untouched")
    }

    @Test
    fun `rewriting picks up a changed port`() {
        bridge.writeOverlay("http://127.0.0.1:7677/mcp")
        val second = bridge.writeOverlay("http://127.0.0.1:8888/mcp")

        val text = second.readText()
        assertTrue(text.contains("8888"), "a re-enable must refresh the URL")
        assertFalse(text.contains("7677"), "the stale URL must not survive")
    }

    @Test
    fun `removeOverlay succeeds whether or not the file is there`() {
        assertTrue(bridge.removeOverlay(), "absence is success — removal must be idempotent")

        bridge.writeOverlay("http://127.0.0.1:7677/mcp")
        assertTrue(bridge.overlayFile().isFile)

        assertTrue(bridge.removeOverlay())
        assertFalse(bridge.overlayFile().exists())
    }

    @Test
    fun `the overlay explains itself to whoever finds it`() {
        // Someone will find this file in their harness home with no idea what
        // wrote it. It has to say so, and say that deleting it is safe.
        val yaml = bridge.overlayYaml("http://127.0.0.1:7677/mcp")
        assertTrue(yaml.contains("BOSS"), "must name what wrote it")
        assertTrue(yaml.contains("Safe to delete"), "must say deleting it is safe")
        // Matched as tokens, not as a sentence: the reason lives in a wrapped YAML
        // comment, so asserting the exact phrasing would fail on a reflow that
        // changed nothing that matters.
        assertTrue(
            yaml.contains("trusted") && yaml.contains("sandbox"),
            "must repeat the harness's own reason for enabling no MCP server by default:\n$yaml",
        )
    }
}
