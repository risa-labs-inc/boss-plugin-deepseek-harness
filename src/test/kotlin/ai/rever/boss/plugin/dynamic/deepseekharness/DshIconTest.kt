package ai.rever.boss.plugin.dynamic.deepseekharness

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorPath
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whale is intact, tintable, and shared.
 *
 * Worth testing because every failure here is silent. The icon is built from a
 * ~2,000-character SVG path; a mangled copy does not throw, it renders a blank or
 * warped square. And a fill colour baked into the vector does not throw either -
 * it just ignores the caller's tint and reads wrong in one of the two themes.
 * Neither shows up in a build, and the icon is the first thing a user sees.
 *
 * The three checks catch different things and none subsumes another: the digest
 * catches any byte change, the node floor catches a path that parsed to nothing
 * useful, and the fill assertion catches a tint that will not apply.
 */
class DshIconTest {

    @Test
    fun `the path data is byte-for-byte upstream's deepseek svg`() {
        // The file claims the string can be diffed against upstream. This is what
        // makes that true. A node count cannot: a single mistyped digit in those
        // 1,974 characters changes zero nodes and moves the whale's geometry.
        //
        // Updating the logo means updating this digest, deliberately - that is the
        // point, not an inconvenience.
        assertEquals(
            1974,
            DEEPSEEK_WHALE_PATH.length,
            "path length changed; re-fetch simple-icons deepseek.svg and update the digest below",
        )
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(DEEPSEEK_WHALE_PATH.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "daab1943fd8420ab62e84f14cef45a5b25ade97723aa54ff80f87868e4086561",
            digest,
            "path data no longer matches upstream simple-icons deepseek.svg",
        )
    }

    @Test
    fun `the icon builds with the simple-icons geometry`() {
        val icon = DeepSeekIcon

        assertEquals("DeepSeek", icon.name)
        // 24x24 is the simple-icons viewport, and callers size it themselves. A
        // mismatch here renders the whale in a corner of its own bounds.
        assertEquals(24f, icon.viewportWidth)
        assertEquals(24f, icon.viewportHeight)
    }

    @Test
    fun `the outline parsed to a whole whale rather than a fragment`() {
        // Counted from the source path: 75 command instances over four subpaths
        // (54 relative cubics, 15 arcs, 4 moves, 1 absolute cubic, 1 close). The
        // floor sits below 75 rather than at it, because the parser may split
        // implicit repeats differently than a command count does. This guards
        // gross truncation; the digest above guards everything finer.
        val nodes = DeepSeekIcon.root.filterIsInstance<VectorPath>().sumOf { it.pathData.size }
        assertTrue(nodes >= 60, "expected the whole outline, got $nodes path nodes")
    }

    @Test
    fun `the single path is opaque black so a caller's tint covers all of it`() {
        // `single()` throws unless there is exactly one path, which is the other
        // half of "a tint covers all of it".
        val path = DeepSeekIcon.root.filterIsInstance<VectorPath>().single()

        assertEquals(
            SolidColor(Color.Black),
            path.fill,
            "a brand colour baked in here would ignore Icon(tint = ...) and read wrong in one theme",
        )
        assertEquals(1f, path.fillAlpha)
    }

    @Test
    fun `panel and tab share one icon instance`() {
        // Two vectors drifting apart is the bug the single alias exists to stop.
        assertTrue(DshPanelInfo.icon === DeepSeekIcon, "the panel must use the shared alias")
        assertTrue(DshWebTabType.icon === DeepSeekIcon, "the tab type must use the shared alias")
        assertTrue(DshWebTabInfo().icon === DeepSeekIcon, "the tab info must use the shared alias")
    }
}
