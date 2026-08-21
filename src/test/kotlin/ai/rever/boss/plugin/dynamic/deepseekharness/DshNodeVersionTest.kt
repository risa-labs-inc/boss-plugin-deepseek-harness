package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Node floor, and the state that stops the Install button on a Node below it.
 *
 * `refreshInstall` used to ask only `which("node")`. Any Node at all counted, so a
 * machine on v18 got the Install button, and clicking it ran an `npm install -g`
 * that dies in a postinstall on `import.meta.resolve is not a function` after a
 * screenful of EBADENGINE warnings. The plugin already knew the requirement — its
 * own NodeMissing copy names 22.19 — it just never compared anything to it.
 *
 * The parse is the risky half: it decides whether a working Node gets disabled, so
 * its unknown-input behaviour is asserted as carefully as its happy path.
 */
class DshNodeVersionTest {

    @Test
    fun `parses the output node actually prints`() {
        val parsed = assertNotNull(DshNode.parse("v18.16.0\n"))
        assertEquals(DshNode.Semver(18, 16, 0), parsed)
    }

    @Test
    fun `tolerates a missing leading v and surrounding whitespace`() {
        assertEquals(DshNode.Semver(24, 3, 1), DshNode.parse("  24.3.1  "))
    }

    @Test
    fun `keeps the release number from a prerelease build`() {
        assertEquals(DshNode.Semver(25, 0, 0), DshNode.parse("v25.0.0-nightly20260101abcdef"))
    }

    /**
     * Null, not a guess. Every caller reads null as "don't know, don't block" —
     * a misparse that produced a low version would disable a Node that works.
     */
    @Test
    fun `refuses to guess at unrecognised output`() {
        assertNull(DshNode.parse(""))
        assertNull(DshNode.parse("not a version"))
        assertNull(DshNode.parse("v22"))
        assertNull(DshNode.parse("v22.19"))
    }

    @Test
    fun `orders versions by major then minor then patch`() {
        assertTrue(DshNode.Semver(18, 16, 0) < DshNode.MIN_VERSION)
        assertTrue(DshNode.Semver(22, 18, 99) < DshNode.MIN_VERSION)
        assertTrue(DshNode.Semver(22, 19, 0) >= DshNode.MIN_VERSION)
        assertTrue(DshNode.Semver(22, 19, 1) >= DshNode.MIN_VERSION)
        assertTrue(DshNode.Semver(24, 0, 0) >= DshNode.MIN_VERSION)
        // A newer major must never read as too old just because its minor is small.
        assertTrue(DshNode.Semver(24, 0, 0) > DshNode.Semver(22, 19, 0))
    }

    /**
     * The floor is the harness's own (`@earendil-works/pi-ai` declares
     * `node >=22.19.0`), and the panel prints [DshNode.MIN_LABEL] at the user. If
     * one moves without the other the plugin starts telling people to install a
     * version it does not require.
     */
    @Test
    fun `the label the user is shown matches the version being enforced`() {
        assertEquals(DshNode.Semver(22, 19, 0), DshNode.MIN_VERSION)
        assertEquals("22.19", DshNode.MIN_LABEL)
        assertTrue(DshNode.MIN_VERSION.toString().startsWith(DshNode.MIN_LABEL))
    }

    /** The state carries what the user needs to act: which Node, and how old. */
    @Test
    fun `NodeTooOld reports the offending binary and its version`() {
        val state = DshInstall.NodeTooOld(File("/usr/local/bin/node"), "18.16.0")

        assertEquals("/usr/local/bin/node", state.node.absolutePath)
        assertEquals("18.16.0", state.version)
        assertTrue(!state.ready, "a Node too old to install on is not ready")
    }
}
