package ai.rever.boss.plugin.dynamic.deepseekharness

/**
 * Turning what the harness printed while dying into something a person can act on.
 *
 * ## Why this exists
 *
 * The harness is a Node program, and when its plugin tree fails to boot it dies
 * the way Node does: the offending source line, a caret, the message, eight stack
 * frames, then the whole message again once per `[cause]` in the chain, then
 * `Node.js v25.2.1`. Thirty lines, of which exactly one is the diagnosis, and it
 * is the *first* one.
 *
 * [DshWebServer] used to keep the last twelve lines of the transcript, on the
 * reasoning that "a stack trace's useful part is at the end". That is true of a
 * JVM trace and exactly backwards for Node. The reported symptom was an error
 * dialog whose opening line was `at Entry._init (file:///...index.js:519:10) {`
 * — the headline had been cut off above it, and the real cause survived only
 * because it happened to be repeated inside a nested `[cause]`.
 *
 * ## What it does
 *
 * [explain] returns the harness's own one-line diagnosis with the plugin loader's
 * internal wiring unwrapped, plus a BOSS-specific note for the failures whose
 * shape we know. Nothing is invented: the summary is always a line the harness
 * actually printed, and when no line is recognised the *first* lines are kept
 * rather than the last.
 *
 * ## Verified against
 *
 * `dsh 0.1.0-rc.7` on Node 25, booted with a deliberately malformed
 * `.credentials.yaml`. The transcript that produced the report is pinned verbatim
 * in `DshFailureTest`; the remedy grammar is read off `dsh-credentials-local`'s
 * `parseCredentialsDocument`, which is the only place these five messages are
 * produced.
 */
object DshFailure {

    /** Config dumps can quote credentials; expose only recognized, fixed remedies. */
    internal fun configurationFailure(output: String): String {
        if (output.contains(DshCredentials.MISSING_MARKER)) return MISSING_KEY
        if (PROVIDER in output) {
            if (MODE_RULE.containsMatchIn(output)) {
                return "The harness credentials file is readable beyond its owner. Restrict access to " +
                    "your .credentials.yaml file before retrying. " + OWNERSHIP
            }
            if (listOf(VALUE_RULE, MAPPING_RULE, DOCUMENT_RULE).any { it.containsMatchIn(output) }) {
                val key = VALUE_RULE.find(output)?.groupValues?.get(1)
                    ?.takeIf { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) }
                val remedy = if (key != null) "Quote the value for \"$key\", or delete that line. "
                    else "Quote every value and remove anything that is not a credential. "
                return "The harness .credentials.yaml file is invalid. " + remedy +
                    "It must contain one NAME: \"value\" per line. " + OWNERSHIP
            }
        }
        return "The harness could not compose this profile safely. Check its profile configuration and retry."
    }

    /**
     * What to say when no key resolved.
     *
     * The harness's own text for this ends "store DEEPSEEK_API_KEY through the
     * credentials service, or export DEEPSEEK_API_KEY in the launching
     * environment" — accurate for the harness, and useless to someone inside
     * BOSS, who has neither of those two things in front of them. So this case is
     * answered rather than quoted.
     */
    const val MISSING_KEY: String =
        "No DeepSeek API key is configured. Add a DeepSeek provider on BOSS's AI Providers " +
            "settings page, or store a ${DshCredentials.SECRET_WEBSITE} secret, then retry."

    /**
     * One actionable summary of a harness failure, or `""` when it printed nothing.
     *
     * Callers keep their own wording for the empty case, because "dsh web exited
     * without output" and "the turn ended without completing" are different
     * events and only the caller knows which one it is looking at.
     */
    fun explain(output: String): String {
        if (output.contains(DshCredentials.MISSING_MARKER)) return MISSING_KEY

        val lines = diagnosticLines(output)
        if (lines.isEmpty()) return ""

        val headline = headline(lines) ?: return lines.take(FALLBACK_LINES).joinToString("\n")
        val note = credentialsNote(output) ?: return headline
        return "$headline\n\n$note"
    }

    /**
     * The lines that carry meaning, in the order the harness printed them.
     *
     * Dropped: stack frames, the caret under the offending column, the bare
     * braces that close a `[cause]` chain, the `file:///…` header Node prints
     * above the source line, and the trailing runtime banner. What remains is
     * messages.
     */
    private fun diagnosticLines(output: String): List<String> =
        output.trim().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { STACK_FRAME.containsMatchIn(it) }
            .filterNot { NOISE.matches(it) }

    /**
     * The single line that names the failure.
     *
     * Node's uncaught-error format puts it first and prefixes it with the error
     * class, so that is the primary match. A harness error raised through its own
     * CLI path has no such prefix and starts with `dsh: ` instead — that is the
     * `MISSING_CREDENTIAL` shape, and the shape of every diagnostic the harness
     * chooses to print deliberately.
     */
    private fun headline(lines: List<String>): String? {
        val raw = lines.firstNotNullOfOrNull { ERROR_CLASS.find(it)?.groupValues?.get(1) }
            ?: lines.firstOrNull { it.startsWith("$BIN: ") }
            ?: return null
        return raw.replace(LOADER_WRAP, "").trim()
    }

    /**
     * The note for a failure to read `$DSH_HOME/.credentials.yaml`, or null.
     *
     * Matched against the whole transcript rather than the headline: the loader
     * wraps the provider's message in two layers of its own, and a future third
     * layer should not silently cost the remedy.
     *
     * Two variants, because the two failures need different things said. A file
     * other users can read comes with its own `chmod 600` command in the
     * harness's message, so repeating a schema lesson there would be noise; every
     * other case is someone having put something in the file that is not a
     * credential, which is worth spelling out — along with the fact that BOSS did
     * not put it there, since a user looking at a BOSS dialog naming a file they
     * never opened will reasonably assume BOSS wrote it.
     */
    private fun credentialsNote(output: String): String? {
        if (PROVIDER !in output) return null

        // Recognition, not extraction: matching one of the five grammars is what
        // says this note applies. The path each rule captures is deliberately
        // unused - the headline already carries it - but the capture is what
        // makes the match specific, and `(.+?)` rather than `\S+` is what lets a
        // home directory contain a space.
        val recognised = listOf(VALUE_RULE, MAPPING_RULE, DOCUMENT_RULE, MODE_RULE)
            .any { it.containsMatchIn(output) }
        if (!recognised) return null

        if (MODE_RULE.containsMatchIn(output)) return OWNERSHIP

        val key = VALUE_RULE.find(output)?.groupValues?.get(1)
        val fix = if (key != null) {
            "Quote the value for \"$key\", or delete that line, then start again. "
        } else {
            "Quote every value, remove anything that is not a credential, then start again. "
        }
        return fix +
            "The file holds credentials only: one NAME: \"value\" per line, every value a " +
            "quoted string. " + OWNERSHIP
    }

    /**
     * The one thing the harness's own message cannot say.
     *
     * The headline already names the file, so this does not repeat it. Someone
     * reading a BOSS dialog about a file they have never opened will reasonably
     * assume BOSS put it there.
     */
    private const val OWNERSHIP =
        "BOSS never writes it - provider keys reach the harness through the child " +
            "environment instead."

    /** The harness's binary name, which prefixes every diagnostic it prints itself. */
    private const val BIN = "dsh"

    /** Package name in every message this class knows the grammar of. */
    private const val PROVIDER = "credentials-local:"

    /** Cap on an unrecognised failure, which has no structure to lean on. */
    private const val FALLBACK_LINES = 6

    /** `Error: …`, `TypeError: …` — Node's uncaught-error headline. */
    private val ERROR_CLASS = Regex("""^[A-Za-z]*Error:\s+(.+)$""")

    /** A stack frame, once the line is trimmed. */
    private val STACK_FRAME = Regex("""^at\s""")

    /** Carets, stray braces, the `file:///…` source header, the runtime banner. */
    private val NOISE = Regex("""^(?:\^+|[{}]+|file:///\S+|Node\.js v[\d.]+)$""")

    /**
     * The plugin loader's own wrapping, which names its internal entries.
     *
     * `failed to apply loader entry credentials (@deepseek-ai/dsh-credentials-local): `
     * tells a harness developer where in the tree the boot stopped and tells a
     * BOSS user nothing. Nested, so every occurrence goes.
     */
    private val LOADER_WRAP = Regex("""failed to apply loader entry \S+ \([^)]*\):\s*""")

    // The five messages `parseCredentialsDocument` and its guards can produce.
    // Paths are matched non-greedily up to the fixed tail rather than as \S+,
    // because a home directory is allowed to contain a space.
    private val VALUE_RULE =
        Regex("""credentials-local: the value for "([^"]+)" in (.+?) (?:must be a string|is empty)""")
    private val MAPPING_RULE =
        Regex("""credentials-local: (.+?) must be a mapping of credential reference""")
    private val DOCUMENT_RULE = Regex("""credentials-local: invalid document at (.+?):\s""")
    private val MODE_RULE = Regex("""credentials-local: (.+?) is readable beyond its owner""")
}
