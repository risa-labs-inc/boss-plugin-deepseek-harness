package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reducing a Node death rattle to a diagnosis.
 *
 * [REPORTED] is captured verbatim from `dsh 0.1.0-rc.7`, booted with a
 * `.credentials.yaml` holding `version: 1`, with only the home directory
 * rewritten to the reporting user's. It is the transcript behind the bug this
 * class exists for: the plugin kept its last twelve lines and put them in an
 * error dialog, so what the user read first was
 * `at Entry._init (file:///…/cordis-plugin-loader/lib/index.js:519:10) {`.
 *
 * The `${'$'}{binName}` escape in the fixture is Kotlin's, not the harness's — the
 * source line Node echoes really does contain an uninterpolated JS template, and
 * a raw Kotlin string would otherwise try to interpolate it.
 */
class DshFailureTest {

    @Test
    fun `profile preflight retains actionable credential remedies without raw configuration`() {
        val failure = DshFailure.configurationFailure(REPORTED + "\nprivate-api-key-value")
        assertTrue(failure.contains("Quote the value for \"version\""))
        assertTrue(failure.contains(".credentials.yaml"))
        assertFalse(failure.contains("private-api-key-value"))
        assertFalse(failure.contains("/Users/deepak"))
    }

    @Test
    fun `unrecognized profile errors cannot expose configuration or session tokens`() {
        val failure = DshFailure.configurationFailure("Error: apiKey: private-api-key-value token=private-token")
        assertTrue(failure.contains("profile configuration"))
        assertFalse(failure.contains("private-api-key-value"))
        assertFalse(failure.contains("private-token"))
    }

    @Test
    fun `profile credential ownership errors give a safe remedy`() {
        val failure = DshFailure.configurationFailure(
            "Error: credentials-local: /private-user/.credentials.yaml is readable beyond its owner",
        )
        assertTrue(failure.contains("Restrict access"))
        assertFalse(failure.contains("/private-user"))
    }

    @Test
    fun `the reported transcript reduces to the line that names the cause`() {
        val explained = DshFailure.explain(REPORTED)

        assertEquals(
            "dsh: plugin tree failed to load: credentials-local: the value for \"version\" in " +
                "/Users/deepak/.dsh/.credentials.yaml must be a string",
            explained.lines().first(),
        )
    }

    @Test
    fun `nothing a stack trace is made of survives`() {
        val explained = DshFailure.explain(REPORTED)

        assertFalse(explained.contains("at Entry._init"), "kept a stack frame")
        assertFalse(explained.contains("cordis-plugin-loader"), "kept an internal package path")
        assertFalse(explained.contains("Node.js v"), "kept the runtime banner")
        assertFalse(explained.contains("[cause]"), "kept a repeated cause")
        assertFalse(explained.contains("failed to apply loader entry"), "kept the loader's wiring")
    }

    @Test
    fun `the note names the key, the file, and who did not write it`() {
        val explained = DshFailure.explain(REPORTED)

        assertTrue(explained.contains("Quote the value for \"version\", or delete that line"))
        assertTrue(explained.contains("one NAME: \"value\" per line"))
        assertTrue(explained.contains("BOSS never writes it"))
    }

    @Test
    fun `an empty value is the same class of mistake and gets the same note`() {
        val explained = DshFailure.explain(
            "Error: dsh: plugin tree failed to load: credentials-local: the value for " +
                "\"DEEPSEEK_API_KEY\" in /home/dev/.dsh/.credentials.yaml is empty; " +
                "remove the key instead",
        )

        assertTrue(explained.contains("Quote the value for \"DEEPSEEK_API_KEY\", or delete that line"))
    }

    @Test
    fun `a document that is not a mapping has no key to name`() {
        val explained = DshFailure.explain(
            "Error: dsh: plugin tree failed to load: credentials-local: " +
                "/home/dev/.dsh/.credentials.yaml must be a mapping of credential reference to value",
        )

        assertTrue(explained.contains("Quote every value, remove anything that is not a credential"))
        assertFalse(explained.contains("the value for"))
    }

    /**
     * A file other users can read already carries its own `chmod 600` command in
     * the harness's message, so the note stays out of its way and says only the
     * one thing the harness cannot: that BOSS did not create the file.
     */
    @Test
    fun `a too-permissive file keeps the harness's own command and gains no schema lesson`() {
        val explained = DshFailure.explain(
            "Error: dsh: plugin tree failed to load: credentials-local: " +
                "/home/dev/.dsh/.credentials.yaml is readable beyond its owner (mode 644); " +
                "run \"chmod 600 /home/dev/.dsh/.credentials.yaml\" before starting again",
        )

        assertTrue(explained.contains("chmod 600"))
        assertTrue(explained.contains("BOSS never writes"))
        assertFalse(explained.contains("one NAME:"))
    }

    /** A home directory is allowed to contain a space; a `\S+` path match choked on one. */
    @Test
    fun `a path with a space is still recognised`() {
        val explained = DshFailure.explain(
            "TypeError: credentials-local: the value for \"version\" in " +
                "/Users/Ada Lovelace/.dsh/.credentials.yaml must be a string",
        )

        assertTrue(explained.contains("Quote the value for \"version\""))
        assertTrue(explained.contains("BOSS never writes it"))
    }

    @Test
    fun `a missing key is answered, not quoted`() {
        val harness = "dsh: MISSING_CREDENTIAL: llm-deepseek: no API key for provider route " +
            "\"deepseek-official\"; store DEEPSEEK_API_KEY through the credentials service, " +
            "or export DEEPSEEK_API_KEY in the launching environment"

        val explained = DshFailure.explain(harness)

        assertEquals(DshFailure.MISSING_KEY, explained)
        assertFalse(explained.contains("export DEEPSEEK_API_KEY"), "quoted advice BOSS cannot be given")
    }

    @Test
    fun `a harness diagnostic with no error class is still the headline`() {
        assertEquals(
            "dsh: profile \"analyst\" is not initialized",
            DshFailure.explain("dsh: profile \"analyst\" is not initialized"),
        )
    }

    /**
     * The regression this class was written for, stated as a property: for an
     * unrecognised Node failure the kept lines come off the *front*.
     */
    @Test
    fun `an unrecognised failure keeps its first lines, not its last`() {
        val output = buildString {
            appendLine("RangeError: Maximum call stack size exceeded")
            repeat(40) { appendLine("    at someFrame$it (file:///opt/dsh/lib/index.js:$it:9)") }
            appendLine("Node.js v26.7.0")
        }

        val explained = DshFailure.explain(output)

        assertEquals("Maximum call stack size exceeded", explained)
    }

    /**
     * Output with no Node error class and no `dsh: ` prefix - pnpm's, in
     * practice. There is nothing to recognise, so the front of it is passed
     * through and capped rather than reshaped into a sentence it never said.
     */
    @Test
    fun `an unstructured failure is passed through from the front and capped`() {
        val output = buildString {
            repeat(20) { appendLine("ERR_PNPM_FETCH_404  GET https://registry.npmjs.org/nope-$it") }
        }

        val explained = DshFailure.explain(output)

        assertTrue(explained.startsWith("ERR_PNPM_FETCH_404  GET https://registry.npmjs.org/nope-0"))
        assertEquals(6, explained.lines().size)
    }

    @Test
    fun `silence stays silent so the caller can name the event`() {
        assertEquals("", DshFailure.explain(""))
        assertEquals("", DshFailure.explain("   \n\n  \n"))
    }

    /**
     * The wiring, not just the mapper: what [DshWebServer] puts into
     * `DshServer.Failed`, which the panel renders and `dsh_web_start` returns.
     * The mapper being right is worth nothing if the server still keeps the tail.
     */
    @Test
    fun `the server's own failure text is the diagnosis, not the tail`() {
        val text = DshWebServer.failureText(REPORTED)

        assertTrue(text.startsWith("dsh: plugin tree failed to load: credentials-local:"))
        assertFalse(text.contains("at Entry._init"))
        assertTrue(text.contains("Quote the value for \"version\""))
    }

    @Test
    fun `a server that died silently keeps the server's own wording`() {
        assertEquals("dsh web exited without output", DshWebServer.failureText(""))
    }

    companion object {
        private val REPORTED = """
            file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js:1187
            		throw new Error(`${'$'}{binName}: ${'$'}{stage}: ${'$'}{detail}${'$'}{stack}`, { cause });
            		      ^

            Error: dsh: plugin tree failed to load: failed to apply loader entry include (cordis:include): failed to apply loader entry credentials (@deepseek-ai/dsh-credentials-local): credentials-local: the value for "version" in /Users/deepak/.dsh/.credentials.yaml must be a string
            TypeError: credentials-local: the value for "version" in /Users/deepak/.dsh/.credentials.yaml must be a string
                at parseCredentialsDocument (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-credentials-local/lib/index.js:132:40)
                at LocalCredentialProvider.loadInitial (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-credentials-local/lib/index.js:344:17)
                at async [cordis.init] (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-credentials-local/lib/index.js:207:3)
                at file:///Users/deepak/.dsh/profiles/web/#credentials
                at file:///Users/deepak/.dsh/profiles/web/#include
                at boot (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js:1187:9)
                at process.processTicksAndRejections (node:internal/process/task_queues:103:5)
                at async runProfile (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/lib/profile-boot-DG5t9aNs.js:247:14)
                at async file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/lib/bin.js:133:3 {
              [cause]: Error: failed to apply loader entry include (cordis:include): failed to apply loader entry credentials (@deepseek-ai/dsh-credentials-local): credentials-local: the value for "version" in /Users/deepak/.dsh/.credentials.yaml must be a string
                  at updateError (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/cordis-plugin-loader/lib/index.js:299:9)
                  at Entry._init (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/cordis-plugin-loader/lib/index.js:519:10)
                  at process.processTicksAndRejections (node:internal/process/task_queues:103:5) {
                [cause]: Error: failed to apply loader entry credentials (@deepseek-ai/dsh-credentials-local): credentials-local: the value for "version" in /Users/deepak/.dsh/.credentials.yaml must be a string
                    at updateError (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/cordis-plugin-loader/lib/index.js:299:9)
                    at Entry._init (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/cordis-plugin-loader/lib/index.js:519:10) {
                  [cause]: TypeError: credentials-local: the value for "version" in /Users/deepak/.dsh/.credentials.yaml must be a string
                      at parseCredentialsDocument (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-credentials-local/lib/index.js:132:40)
                      at LocalCredentialProvider.loadInitial (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-credentials-local/lib/index.js:344:17)
                      at async [cordis.init] (file:///Users/deepak/.dsh/boss-toolchain/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-credentials-local/lib/index.js:207:3)
                      at file:///Users/deepak/.dsh/profiles/web/#credentials
                      at file:///Users/deepak/.dsh/profiles/web/#include
                }
              }
            }

            Node.js v26.7.0
        """.trimIndent()
    }
}
