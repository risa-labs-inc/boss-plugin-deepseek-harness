package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.dynamic.deepseekharness.DshNodeResolver.Resolution
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Picking a Node when the machine has several.
 *
 * The scenario every case here is built around is a real one, off the machine
 * that produced the bug report:
 *
 * ```
 * ~/.nvm/versions/node/v18.16.0/bin/node   18.16.0   <- first on PATH
 * /opt/homebrew/bin/node                   26.7.0
 * ~/.local/bin/node                        22.23.2
 * ```
 *
 * First-match-on-PATH picks the only one of the three that cannot run the
 * harness. Nothing on that machine needed upgrading, but the install failed and
 * then the first attempt at a fix told the user to go install a newer Node.
 */
class DshNodeResolverTest {

    private val nvm18 = File("/Users/x/.nvm/versions/node/v18.16.0/bin/node")
    private val brew26 = File("/opt/homebrew/bin/node")
    private val local22 = File("/Users/x/.local/bin/node")
    private val old20 = File("/usr/local/bin/node")

    /** A probe that answers from a table and records the order it was asked. */
    private class FakeProbe(private val versions: Map<File, String?>) {
        val asked = mutableListOf<File>()
        suspend fun probe(node: File): String? {
            asked += node
            return versions[node]
        }
    }

    @Test
    fun `skips the too-old Node at the front of PATH and takes the one that works`() = runTest {
        val probe = FakeProbe(mapOf(nvm18 to "v18.16.0", brew26 to "v26.7.0", local22 to "v22.23.2"))

        val result = DshNodeResolver.resolve(listOf(nvm18, brew26, local22), probe::probe)

        val usable = assertIs<Resolution.Usable>(result)
        assertEquals(brew26, usable.node, "the Homebrew 26.7.0 was right there")
        assertEquals(DshNode.Semver(26, 7, 0), usable.version)
    }

    /**
     * First qualifying, not newest. The caller orders candidates by how much the
     * user meant them; reaching past a perfectly good Node on their PATH to grab
     * a newer one from a directory they never mentioned is not this function's
     * decision to make.
     */
    @Test
    fun `takes the first qualifying candidate rather than the newest`() = runTest {
        val probe = FakeProbe(mapOf(local22 to "v22.23.2", brew26 to "v26.7.0"))

        val result = DshNodeResolver.resolve(listOf(local22, brew26), probe::probe)

        assertEquals(local22, assertIs<Resolution.Usable>(result).node)
    }

    /** And it stops asking once it has an answer — probes are process spawns. */
    @Test
    fun `stops probing at the first qualifying candidate`() = runTest {
        val probe = FakeProbe(mapOf(nvm18 to "v18.16.0", brew26 to "v26.7.0", local22 to "v22.23.2"))

        DshNodeResolver.resolve(listOf(nvm18, brew26, local22), probe::probe)

        assertEquals(listOf(nvm18, brew26), probe.asked, "it kept probing after deciding")
    }

    @Test
    fun `reports the newest when every candidate is too old`() = runTest {
        val probe = FakeProbe(mapOf(nvm18 to "v18.16.0", old20 to "v20.11.0"))

        val result = DshNodeResolver.resolve(listOf(nvm18, old20), probe::probe)

        val tooOld = assertIs<Resolution.AllTooOld>(result)
        assertEquals(old20, tooOld.node, "quote the best they have, not the first found")
        assertEquals(DshNode.Semver(20, 11, 0), tooOld.version)
    }

    @Test
    fun `no candidates means no Node`() = runTest {
        assertIs<Resolution.NoNode>(DshNodeResolver.resolve(emptyList()) { null })
    }

    /**
     * Fail open, but only after looking. An unreadable candidate must not shadow
     * a known-good one further down the list — that would reintroduce
     * first-match under a different name.
     */
    @Test
    fun `prefers a known-good Node over one whose version could not be read`() = runTest {
        val probe = FakeProbe(mapOf(nvm18 to null, brew26 to "v26.7.0"))

        val result = DshNodeResolver.resolve(listOf(nvm18, brew26), probe::probe)

        assertEquals(brew26, assertIs<Resolution.Usable>(result).node)
    }

    @Test
    fun `falls back to an unreadable Node rather than declaring the machine unusable`() = runTest {
        val probe = FakeProbe(mapOf(nvm18 to "v18.16.0", brew26 to null))

        val result = DshNodeResolver.resolve(listOf(nvm18, brew26), probe::probe)

        val usable = assertIs<Resolution.Usable>(result)
        assertEquals(brew26, usable.node)
        assertNull(usable.version, "we do not know its version and must not pretend to")
    }

    /** An unreadable candidate beats a too-old one: unknown is not known-bad. */
    @Test
    fun `an unreadable Node outranks a Node known to be too old`() = runTest {
        val probe = FakeProbe(mapOf(nvm18 to "v18.16.0", old20 to null))

        val result = DshNodeResolver.resolve(listOf(nvm18, old20), probe::probe)

        assertIs<Resolution.Usable>(result)
    }

    @Test
    fun `the floor itself qualifies`() = runTest {
        val floor = File("/opt/node/bin/node")
        val probe = FakeProbe(mapOf(floor to "v${DshNode.MIN_VERSION}"))

        assertIs<Resolution.Usable>(DshNodeResolver.resolve(listOf(floor), probe::probe))
    }

    @Test
    fun `one below the floor does not`() = runTest {
        val just = File("/opt/node/bin/node")
        val probe = FakeProbe(mapOf(just to "v22.18.99"))

        assertTrue(DshNodeResolver.resolve(listOf(just), probe::probe) is Resolution.AllTooOld)
    }
}
