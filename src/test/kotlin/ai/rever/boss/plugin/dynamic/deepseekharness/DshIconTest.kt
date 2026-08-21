package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whale actually parses into a drawable outline.
 *
 * Worth a test because the failure mode is silent. The icon is built from a
 * ~2,000-character SVG path string; a truncated or mangled copy does not throw at
 * class-init, it yields a vector with too few nodes that renders as a blank or
 * mangled square. Nothing in the build or in a screenshot-free smoke test would
 * catch that, and the icon is the one part of this plugin a user sees before
 * anything else works.
 */
class DshIconTest {

    @Test
    fun `the icon builds with the simple-icons geometry`() {
        val icon = DeepSeekIcon

        assertEquals("DeepSeek", icon.name)
        // 24x24 is the simple-icons viewport, and callers size it themselves. A
        // mismatch here would render the whale in a corner of its own bounds.
        assertEquals(24f, icon.viewportWidth)
        assertEquals(24f, icon.viewportHeight)
    }

    @Test
    fun `the outline has the node count a whole whale needs`() {
        // Counted from the source path: 75 command instances over four subpaths
        // (54 relative cubics, 15 arcs, 4 moves, 1 absolute cubic, 1 close).
        // A truncated string still builds and still renders *something*, so a
        // floor is what separates "parsed" from "parsed a fragment". Set below 75
        // rather than at it, because the parser is entitled to split implicit
        // repeats differently than a command count does - this guards corruption,
        // it does not pin upstream's exact geometry.
        val nodes = DeepSeekIcon.root.sumOf { group ->
            (group as? androidx.compose.ui.graphics.vector.VectorPath)?.pathData?.size ?: 0
        }
        assertTrue(nodes >= 60, "expected the whole outline, got $nodes path nodes")
    }

    @Test
    fun `the icon is a single tintable path`() {
        // One path, opaque black, so Icon(tint = ...) recolours the whole mark.
        // A vector carrying its own brand colour ignores the tint and reads wrong
        // in one of the two themes.
        val paths = DeepSeekIcon.root.count { it is androidx.compose.ui.graphics.vector.VectorPath }
        assertEquals(1, paths, "expected exactly one path so a tint covers all of it")
    }

    @Test
    fun `panel and tab share one icon instance`() {
        // Two vectors that drift apart is the bug the single alias exists to stop.
        assertTrue(DshPanelInfo.icon === DeepSeekIcon, "the panel must use the shared alias")
        assertTrue(DshWebTabType.icon === DeepSeekIcon, "the tab type must use the shared alias")
        assertTrue(DshWebTabInfo().icon === DeepSeekIcon, "the tab info must use the shared alias")
    }
}
