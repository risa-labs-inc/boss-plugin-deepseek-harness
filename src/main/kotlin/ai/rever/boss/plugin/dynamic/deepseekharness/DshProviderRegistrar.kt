package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File

/** One provider route this plugin would add to the harness's settings. */
data class DshRouteAddition(
    /** pi-ai catalog route name, e.g. `openai`. */
    val route: String,
    /** The environment variable the route resolves its credential from. */
    val apiKeyEnv: String,
)

/** Why a settings write was refused, in words a panel can show. */
sealed interface DshRegisterOutcome {
    /** Nothing to do: every eligible route is already registered. */
    data object UpToDate : DshRegisterOutcome

    /** Routes were added. [backup] is the copy taken first. */
    data class Added(val routes: List<String>, val backup: File?) : DshRegisterOutcome

    /**
     * The existing `llm-pi-ai` block holds configuration this writer cannot
     * round-trip, so it declined rather than risk losing it. [manualYaml] is
     * exactly what the user can paste instead.
     */
    data class TooComplex(val reason: String, val manualYaml: String) : DshRegisterOutcome

    data class Failed(val reason: String) : DshRegisterOutcome
}

/**
 * Registering provider routes in the harness's own `settings.yaml`.
 *
 * ## Why this is written so defensively
 *
 * This edits a file the user owns, with no YAML parser available - the host
 * bundles none, and pulling one in would round-trip the whole document through a
 * serializer that discards comments and reorders keys, which is a worse outcome
 * for a config file than a targeted edit.
 *
 * So the rule is: **only write when the existing `llm-pi-ai` block can be
 * round-tripped exactly.** Their real file is
 * `providers: { google: { apiKeyEnv: GOOGLE_API_KEY } }` - a flow mapping of
 * simple profiles, trivially re-emitted. But `llm-pi-ai` profiles can also carry
 * `models` lists, `modelOverrides`, `compat` blocks and `retryPolicy` trees, and
 * re-emitting those from a regex would silently drop them. When anything beyond a
 * bare `apiKeyEnv` appears, [register] refuses and hands back the YAML to paste.
 *
 * A backup is taken before any write - `settings.yaml.boss-backup`, replaced each
 * time - because "it refused when unsure" is only half a promise if the half it
 * did write is unrecoverable. Not timestamped: this runs on every launch, and a
 * directory filling with dated copies of a config file is its own problem.
 *
 * ## Route names are verified, never guessed
 *
 * A route name pi-ai does not ship registers **no adapter at all** and fails only
 * when a request reaches it - `NO_ADAPTER: no adapter registered for provider
 * "x"`. It does not fail at boot, so a wrong name is a silent misconfiguration
 * that surfaces later as a broken harness. [ROUTE_FOR_ENV] therefore maps only
 * names probed against a real `dsh` (see AGENTS.md), and an unmapped key is left
 * alone rather than guessed at.
 *
 * ## What it deliberately does not touch
 *
 * `agent-default-model`. Registering a provider is not the same as switching the
 * user's model, and a plugin that silently repointed the default would change
 * which vendor gets billed for the next turn.
 */
class DshProviderRegistrar(private val env: Map<String, String> = System.getenv()) {

    private val settingsFile: File get() = File(DshPaths.home(env), "settings.yaml")

    /**
     * Routes that would be added for [envNames], excluding those already present.
     *
     * [envNames] are the variables the plugin actually injects, so a route is only
     * ever proposed when its credential will be there. A route whose `apiKeyEnv`
     * resolves to nothing fails every request with `MISSING_CREDENTIAL` rather
     * than falling through, so proposing one without its key would be worse than
     * proposing nothing.
     */
    fun plan(envNames: Set<String>): List<DshRouteAddition> {
        val existing = existingRoutes()
        return envNames
            .mapNotNull { name -> ROUTE_FOR_ENV[name]?.let { DshRouteAddition(it, name) } }
            .filter { it.route !in existing }
            // Several names can map to one route (OPENAI_API_KEY and
            // OPEN_AI_API_KEY both mean `openai`). A plain distinctBy over a Set
            // picked an arbitrary one, and on a real store it chose a colleague's
            // key over the user's own. Prefer the canonical spelling, then
            // alphabetical, so the choice is both sensible and reproducible.
            .groupBy { it.route }
            .map { (_, options) -> options.minWith(BY_PREFERENCE) }
            .sortedBy { it.route }
    }

    /** Route names already present under `llm-pi-ai.providers`. */
    fun existingRoutes(): Set<String> {
        val block = piAiBlock(readSettings() ?: return emptySet()) ?: return emptySet()
        return ROUTE_KEY.findAll(providersValue(block) ?: return emptySet())
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * Add [additions] to `llm-pi-ai.providers`, or explain why it will not.
     *
     * Idempotent: a route already present is not re-added, and an empty plan is
     * [DshRegisterOutcome.UpToDate] without touching the file.
     */
    fun register(additions: List<DshRouteAddition>): DshRegisterOutcome {
        if (additions.isEmpty()) return DshRegisterOutcome.UpToDate

        val file = settingsFile
        val original = readSettings()

        // No settings document at all: write a minimal one. Nothing to preserve,
        // so nothing to be careful about.
        if (original == null) {
            return runCatching {
                file.parentFile?.mkdirs()
                file.writeText(renderPiAiBlock(additions.associate { it.route to it.apiKeyEnv }))
                DshRegisterOutcome.Added(additions.map { it.route }, backup = null)
            }.getOrElse { DshRegisterOutcome.Failed(it.message ?: "could not write settings.yaml") }
        }

        val block = piAiBlock(original)
        val existing: Map<String, String> = if (block == null) {
            emptyMap()
        } else {
            parseSimpleProfiles(block) ?: return DshRegisterOutcome.TooComplex(
                reason = "the harness's llm-pi-ai settings hold profile options this plugin will not rewrite",
                manualYaml = renderProvidersEntries(additions.associate { it.route to it.apiKeyEnv }),
            )
        }

        val merged = existing + additions.associate { it.route to it.apiKeyEnv }
        val rendered = renderPiAiBlock(merged)
        val updated = if (block == null) {
            original.trimEnd() + "\n" + rendered
        } else {
            original.replaceRange(block.range, rendered.trimEnd() + "\n")
        }

        // Verify before committing. The line-shape guards above are necessary but
        // not sufficient: a 4-space-indented block hid a sibling key from TOP_KEY,
        // and a blank line between routes truncated PI_AI_BLOCK so routes below it
        // were invisible - the first silently deleted the sibling, the second
        // produced a duplicate route key. Re-parsing the candidate output and
        // requiring it to round-trip to `merged` turns any shape this writer does
        // not understand, now or later, into a refusal instead of a rewrite.
        val check = piAiBlock(updated)?.let { parseSimpleProfiles(it) }
        if (check != merged) {
            return DshRegisterOutcome.TooComplex(
                reason = "the harness's llm-pi-ai settings are shaped in a way this plugin will not rewrite safely",
                manualYaml = renderProvidersEntries(additions.associate { it.route to it.apiKeyEnv }),
            )
        }

        return runCatching {
            val backup = File(file.parentFile, "settings.yaml.boss-backup")
            file.copyTo(backup, overwrite = true)
            file.writeText(updated)
            DshRegisterOutcome.Added(additions.map { it.route }, backup)
        }.getOrElse { DshRegisterOutcome.Failed(it.message ?: "could not write settings.yaml") }
    }

    // ------------------------------------------------------------------ parsing

    private fun readSettings(): String? =
        settingsFile.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    /** The `llm-pi-ai:` top-level block and its indented body. */
    private fun piAiBlock(text: String): MatchResult? = PI_AI_BLOCK.find(text)

    private fun providersRegion(block: MatchResult): String? =
        PROVIDERS_REGION.find(block.value)?.value

    /**
     * Everything after the `providers:` key, so a route scan cannot match the key
     * itself.
     *
     * Without this, `ROUTE_KEY` matched `providers:` in
     * `providers: { google: { apiKeyEnv: X } }` and reported it as a route - which
     * made the parsed-versus-found comparison disagree and refused the single
     * commonest real-world shape. Caught by a test written from a real file.
     */
    private fun providersValue(block: MatchResult): String? =
        providersRegion(block)?.substringAfter("providers:", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }

    /**
     * The route to `apiKeyEnv` map, or null when the block holds anything else.
     *
     * Null is the important return: it means "do not rewrite this". Recognising
     * only `{ route: { apiKeyEnv: NAME } }` shapes keeps the refusal wide, which
     * is the safe direction - a refusal costs the user one paste, a bad rewrite
     * costs them a `models` list they tuned by hand.
     */
    internal fun parseSimpleProfiles(block: MatchResult): Map<String, String>? {
        val body = block.value.lines().drop(1).dropLastWhile { it.isBlank() }
        val meaningful = body.filter { it.isNotBlank() }
        if (meaningful.isEmpty()) return emptyMap()

        // A comment would be dropped by the rewrite, and losing a user's config
        // text is worse than refusing.
        if (meaningful.any { it.trimStart().startsWith("#") }) return null

        // Derive the block's own indent instead of assuming a width. Assuming
        // 1-3 spaces meant a 4-space file matched no keys at all, so the
        // "unrecognised sibling key" check never fired and the rewrite silently
        // deleted the sibling.
        val base = meaningful.minOf { it.takeWhile { c -> c == ' ' || c == '\t' }.length }
        val topLines = meaningful.filter { indentOf(it) == base }
        val topKeys = topLines.mapNotNull { KEY_LINE.find(it)?.groupValues?.get(1) }
        if (topKeys.size != topLines.size) return null
        if (topKeys != listOf("providers")) return null

        val providersLine = topLines.single()
        val inlineValue = providersLine.substringAfter(':', "").trim()
        val deeper = meaningful.filter { indentOf(it) > base }

        // Flow style on one line: `providers: { a: { apiKeyEnv: X }, b: {...} }`.
        if (inlineValue.isNotEmpty()) {
            if (deeper.isNotEmpty()) return null
            return parseFlowProfiles(inlineValue)
        }
        if (deeper.isEmpty()) return emptyMap()

        // Block style: route keys at one indent, exactly one `apiKeyEnv` under each.
        val routeIndent = deeper.minOf { indentOf(it) }
        val profiles = LinkedHashMap<String, String>()
        var current: String? = null
        for (line in deeper) {
            val indent = indentOf(line)
            val match = KEY_LINE.find(line) ?: return null
            val key = match.groupValues[1]
            val value = line.substringAfter(':', "").trim()
            when (indent) {
                routeIndent -> {
                    if (value.isNotEmpty()) {
                        // `route: { apiKeyEnv: X }` nested one level down.
                        val flow = parseFlowProfiles("{ $key: $value }") ?: return null
                        profiles += flow
                        current = null
                    } else {
                        if (profiles.containsKey(key)) return null
                        current = key
                    }
                }
                else -> {
                    // Only a bare apiKeyEnv may live under a route; anything else
                    // (models, compat, retryPolicy...) cannot be re-emitted.
                    if (key != "apiKeyEnv" || current == null) return null
                    if (!ENV_VALUE.matches(value)) return null
                    if (profiles.put(current, value) != null) return null
                    current = null
                }
            }
        }
        // A route header with no apiKeyEnv beneath it is a shape we cannot re-emit.
        if (current != null) return null
        return profiles
    }

    /** `{ a: { apiKeyEnv: X }, b: { apiKeyEnv: Y } }`, or null for anything else. */
    private fun parseFlowProfiles(text: String): Map<String, String>? {
        val inner = text.trim().removeSurrounding("{", "}").trim()
        if (inner.isEmpty()) return emptyMap()
        val profiles = LinkedHashMap<String, String>()
        for (part in splitTopLevel(inner)) {
            val match = FLOW_PROFILE.matchEntire(part.trim()) ?: return null
            if (profiles.put(match.groupValues[1], match.groupValues[2]) != null) return null
        }
        return profiles
    }

    /** Split on commas that are not inside braces. */
    private fun splitTopLevel(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val buf = StringBuilder()
        for (c in text) {
            when (c) {
                '{' -> { depth++; buf.append(c) }
                '}' -> { depth--; buf.append(c) }
                ',' -> if (depth == 0) { out += buf.toString(); buf.clear() } else buf.append(c)
                else -> buf.append(c)
            }
        }
        if (buf.isNotBlank()) out += buf.toString()
        return out
    }

    private fun indentOf(line: String) = line.takeWhile { it == ' ' || it == '\t' }.length

    // ----------------------------------------------------------------- emitting

    /** Canonical block style, one route per line - never flow, so a diff is readable. */
    internal fun renderPiAiBlock(profiles: Map<String, String>): String = buildString {
        appendLine("llm-pi-ai:")
        append(renderProvidersEntries(profiles))
    }

    internal fun renderProvidersEntries(profiles: Map<String, String>): String = buildString {
        appendLine("  providers:")
        profiles.toSortedMap().forEach { (route, envName) ->
            appendLine("    $route:")
            appendLine("      apiKeyEnv: $envName")
        }
    }

    companion object {
        /**
         * Environment variable name to pi-ai catalog route.
         *
         * **Every entry here was probed against a real `dsh`**, because an
         * unshipped route name registers no adapter and fails only when a request
         * reaches it. The probe: set the route plus `agent-default-model` naming a
         * nonexistent model, then run one turn - `UNKNOWN_MODEL` means the route
         * resolved and its catalog was consulted, `NO_ADAPTER` means pi-ai ships
         * nothing under that key.
         *
         * An env name absent from this map is deliberately left unregistered
         * rather than guessed at.
         */
        val ROUTE_FOR_ENV: Map<String, String> = mapOf(
            "OPENAI_API_KEY" to "openai",
            "OPEN_AI_API_KEY" to "openai",
            "OPENAI_KEY" to "openai",
            "ANTHROPIC_API_KEY" to "anthropic",
            "DEEPSEEK_API_KEY" to "deepseek",
            // GEMINI_API_KEY maps to `google`, NOT `gemini`: probing showed
            // `gemini` ships no adapter. Same trap for GROK -> xai and
            // TOGETHERAI -> together.
            "GOOGLE_API_KEY" to "google",
            "GEMINI_API_KEY" to "google",
            "XAI_API_KEY" to "xai",
            "GROK_API_KEY" to "xai",
            "TOGETHER_API_KEY" to "together",
            "TOGETHERAI_API_KEY" to "together",
            "MISTRAL_API_KEY" to "mistral",
            "GROQ_API_KEY" to "groq",
            "OPENROUTER_API_KEY" to "openrouter",
            "FIREWORKS_API_KEY" to "fireworks",
            "CEREBRAS_API_KEY" to "cerebras",
            "NVIDIA_API_KEY" to "nvidia",
        )

        /**
         * Routes proved to exist against `dsh 0.1.0-rc.7`, and those proved not to.
         *
         * Kept as data so a test can assert every value in [ROUTE_FOR_ENV] is one
         * of the verified-present names, and that no known-absent name creeps in.
         */
        val VERIFIED_ROUTES: Set<String> = setOf(
            "openai", "anthropic", "deepseek", "google", "xai", "together",
            "mistral", "groq", "openrouter", "fireworks", "cerebras", "nvidia",
        )

        /** Probed and confirmed to register no adapter; never write these. */
        val KNOWN_ABSENT_ROUTES: Set<String> = setOf(
            "gemini", "grok", "togetherai", "cohere", "perplexity", "moonshot", "azure", "bedrock",
        )

        /**
         * The spelling to prefer when a route has more than one candidate key.
         *
         * One per route, so the everyday name wins over a variant someone
         * happened to store under a different label.
         */
        val CANONICAL_ENV_FOR_ROUTE: Map<String, String> = mapOf(
            "openai" to "OPENAI_API_KEY",
            "anthropic" to "ANTHROPIC_API_KEY",
            "deepseek" to "DEEPSEEK_API_KEY",
            "google" to "GOOGLE_API_KEY",
            "xai" to "XAI_API_KEY",
            "together" to "TOGETHER_API_KEY",
            "mistral" to "MISTRAL_API_KEY",
            "groq" to "GROQ_API_KEY",
            "openrouter" to "OPENROUTER_API_KEY",
            "fireworks" to "FIREWORKS_API_KEY",
            "cerebras" to "CEREBRAS_API_KEY",
            "nvidia" to "NVIDIA_API_KEY",
        )

        private val BY_PREFERENCE = compareBy<DshRouteAddition>(
            { if (it.apiKeyEnv == CANONICAL_ENV_FOR_ROUTE[it.route]) 0 else 1 },
            { it.apiKeyEnv },
        )

        /**
         * The `llm-pi-ai:` block, INCLUDING blank lines inside it.
         *
         * The first version stopped at the first blank line, so routes below one
         * were invisible to both the route scan and the parser - the rewrite then
         * emitted a block that already existed further down, producing a duplicate
         * YAML key. Blank lines between provider entries are ordinary formatting.
         */
        private val PI_AI_BLOCK =
            Regex("^llm-pi-ai:[ \\t]*\\n(?:[ \\t]*\\n|[ \\t]+\\S.*\\n?)*", RegexOption.MULTILINE)
        private val PROVIDERS_REGION = Regex("^[ \\t]+providers:.*(?:\\n[ \\t]{3,}\\S.*)*", RegexOption.MULTILINE)


        /** A route name, in flow or block style. */
        private val ROUTE_KEY = Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*:\s*[{\n]""")

        /** `key:` at the start of a line, with whatever follows captured separately. */
        private val KEY_LINE = Regex("""^[ \t]*([A-Za-z][A-Za-z0-9_-]*)\s*:""")

        /** One flow profile: `route: { apiKeyEnv: NAME }`. */
        private val FLOW_PROFILE =
            Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*:\s*\{\s*apiKeyEnv\s*:\s*([A-Z][A-Z0-9_]{2,63})\s*}""")

        /** A bare environment variable name as a value. */
        private val ENV_VALUE = Regex("""[A-Z][A-Z0-9_]{2,63}""")


    }
}
