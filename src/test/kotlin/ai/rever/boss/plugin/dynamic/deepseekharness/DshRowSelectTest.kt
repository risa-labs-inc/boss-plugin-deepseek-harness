package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Picking one row out of a composed config tree.
 *
 * The fixture is real output from `dsh --profile web --dump-default-config`
 * (0.1.0-rc.7), trimmed. The shape that matters: a `# ==` comment names the file
 * that supplied the rows beneath it, and that attribution is the entire reason
 * to run a dump — so a selector that returned the row without it would answer
 * "what is the value" while dropping "why".
 */
class DshRowSelectTest {

    private val engine = DshEngine(FakeServices.context())

    private val dump = """
        # == @deepseek-ai/dsh-base
        - id: timer
          name: '@deepseek-ai/cordis-plugin-timer'
        # == @deepseek-ai/dsh-base, patched by @deepseek-ai/dsh-web-app
        - id: hmr
          name: '@deepseek-ai/cordis-plugin-hmr'
          config:
            root:
              - .
          disabled: true
        # == @deepseek-ai/dsh-base
        - id: llm
          name: '@deepseek-ai/dsh-llm'
        - id: agent-default-model
          name: '@deepseek-ai/dsh-agent-default-model'
          config:
            provider: deepseek-official
            model: deepseek-v4-flash
    """.trimIndent()

    @Test
    fun `a selected row carries the comment naming the file that set it`() {
        val (text, isError) = engine.selectRow(dump, "hmr")

        assertFalse(isError)
        assertTrue(
            text.startsWith("# == @deepseek-ai/dsh-base, patched by @deepseek-ai/dsh-web-app"),
            "the attribution comment is the point of a dump; it must lead:\n$text",
        )
        assertTrue(text.contains("- id: hmr"))
        assertTrue(text.contains("disabled: true"))
    }

    @Test
    fun `a selected row stops before the next row`() {
        val (text, _) = engine.selectRow(dump, "hmr")

        assertFalse(text.contains("- id: llm"), "bled into the following row:\n$text")
        assertFalse(text.contains("- id: timer"), "bled into the preceding row:\n$text")
    }

    @Test
    fun `a row with no comment of its own is still returned`() {
        // `agent-default-model` sits under a comment that also covers `llm`, so
        // walking back over the contiguous comment block must not overshoot into
        // the previous row's body.
        val (text, isError) = engine.selectRow(dump, "agent-default-model")

        assertFalse(isError)
        assertTrue(text.contains("model: deepseek-v4-flash"))
        assertFalse(text.contains("- id: llm"), "must not include the sibling row:\n$text")
    }

    @Test
    fun `the last row in the tree is returned to the end`() {
        val (text, isError) = engine.selectRow(dump, "agent-default-model")

        assertFalse(isError)
        assertTrue(text.trimEnd().endsWith("model: deepseek-v4-flash"))
    }

    @Test
    fun `an unknown row is an error that lists what does exist`() {
        // Answering "no such row" without saying what there is leaves the caller
        // guessing at spellings, which for an agent means another round trip.
        val (text, isError) = engine.selectRow(dump, "not-a-row")

        assertTrue(isError)
        assertTrue(text.contains("not-a-row"))
        assertTrue(text.contains("timer"), "must list the available rows: $text")
        assertTrue(text.contains("agent-default-model"))
    }

    @Test
    fun `a row id that is a prefix of another does not match it`() {
        // `llm` must not match `llm-deepseek`. Substring matching here would
        // silently return the wrong row's value.
        val withPrefix = dump + "\n- id: llm-deepseek\n  name: '@deepseek-ai/dsh-llm-deepseek'"
        val (text, isError) = engine.selectRow(withPrefix, "llm")

        assertFalse(isError)
        assertEquals("# == @deepseek-ai/dsh-base\n- id: llm\n  name: '@deepseek-ai/dsh-llm'", text)
    }
}
