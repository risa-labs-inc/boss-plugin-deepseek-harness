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
 * A timestamped backup is taken before any write, because "it refused when unsure"
 * is only half a promise if the half it did write is unrecoverable.
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
        val body = block.value.lines().drop(1).joinToString("\n")
        // Any key under llm-pi-ai other than `providers` is unrecognised.
        val topKeys = TOP_KEY.findAll(body).map { it.groupValues[1] }.toSet()
        if (topKeys.any { it != "providers" }) return null

        val region = providersValue(block) ?: return emptyMap()
        // Every option name that appears must be apiKeyEnv, or we cannot re-emit.
        val options = OPTION_KEY.findAll(region).map { it.groupValues[1] }.toSet()
        if (options.any { it != "apiKeyEnv" }) return null
        if (region.contains('[') || region.contains('-')) return null

        val pairs = SIMPLE_PROFILE.findAll(region).associate { it.groupValues[1] to it.groupValues[2] }
        // A route present but not parsed as a simple profile means an unhandled shape.
        val routes = ROUTE_KEY.findAll(region).map { it.groupValues[1] }.toSet()
        if (routes != pairs.keys) return null
        return pairs
    }

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

        private val PI_AI_BLOCK = Regex("^llm-pi-ai:[ \\t]*\\n(?:[ \\t]+\\S.*\\n?)*", RegexOption.MULTILINE)
        private val PROVIDERS_REGION = Regex("^[ \\t]+providers:.*(?:\\n[ \\t]{3,}\\S.*)*", RegexOption.MULTILINE)

        /** A key at the first indent level under `llm-pi-ai:`. */
        private val TOP_KEY = Regex("^[ \\t]{1,3}([A-Za-z][A-Za-z0-9_-]*)\\s*:", RegexOption.MULTILINE)

        /** A route name, in flow or block style. */
        private val ROUTE_KEY = Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*:\s*[{\n]""")

        /** An option name inside a profile. */
        private val OPTION_KEY = Regex("""\b(apiKeyEnv|baseURL|models|modelOverrides|compat|retryPolicy|api|displayName|reasoning|streamIdleTimeoutMs)\s*:""")

        /** `route: { apiKeyEnv: NAME }` or its block equivalent. */
        private val SIMPLE_PROFILE = Regex(
            """([A-Za-z][A-Za-z0-9_-]*)\s*:\s*\{?\s*apiKeyEnv\s*:\s*([A-Z][A-Z0-9_]{2,63})\s*\}?""",
        )
    }
}
