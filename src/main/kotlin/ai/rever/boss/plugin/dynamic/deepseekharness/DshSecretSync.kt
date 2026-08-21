package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SecretEntryData
import java.io.File

/**
 * A BOSS secret the user can expose to the harness as an environment variable.
 *
 * Carries no secret value - only what the panel needs to render a row. Values are
 * fetched at launch and handed straight to the child process, never stored on an
 * object something might log.
 */
data class DshKeyCandidate(
    /** BOSS secret id: the stable handle a user's choice is stored against. */
    val secretId: String,
    /** The secret's `website`, which by convention names the provider. */
    val label: String,
    /** The secret's `username`, which by convention is the environment variable name. */
    val envName: String,
    /** A harness route's `apiKeyEnv` already references [envName]. */
    val referencedByHarness: Boolean,
    /** [envName] is a known model-provider key (see [DshSecretSync.PROVIDER_KEYS]). */
    val recognisedProvider: Boolean,
    /** Another candidate claims the same [envName], so which value wins is ambiguous. */
    val conflicted: Boolean,
) {
    /**
     * Whether this key is exposed when the user has expressed no preference.
     *
     * On for a recognised provider key, or one the harness explicitly asks for -
     * both are evidence the key exists in order to be used by a model runner.
     *
     * Off for a conflict, always: with two secrets claiming one variable name,
     * "on by default" would silently pick one, and picking the wrong API key is a
     * failure that looks like the provider rejecting you.
     */
    val onByDefault: Boolean get() = !conflicted && (recognisedProvider || referencedByHarness)
}

/**
 * Which keys the user has overridden, in each direction.
 *
 * Two sets rather than one, because a default of *on* makes "off" a real state
 * that has to survive a restart. With a single selected-set, switching a
 * default-on key off would be indistinguishable from never having chosen, and it
 * would come back on at the next launch - the user turning something off and
 * watching it reappear.
 */
data class DshKeySelection(
    val enabled: Set<String> = emptySet(),
    val disabled: Set<String> = emptySet(),
) {
    fun isOn(candidate: DshKeyCandidate): Boolean = when (candidate.secretId) {
        in enabled -> true
        in disabled -> false
        else -> candidate.onByDefault
    }

    fun with(secretId: String, on: Boolean, onByDefault: Boolean): DshKeySelection = when (on) {
        // Recording only the side that differs from the default keeps the stored
        // value small and lets a changed default (a new provider added to the
        // allowlist) apply to keys the user never touched.
        onByDefault -> DshKeySelection(enabled - secretId, disabled - secretId)
        true -> DshKeySelection(enabled + secretId, disabled - secretId)
        false -> DshKeySelection(enabled - secretId, disabled + secretId)
    }
}

/**
 * Exposing BOSS secrets to the harness as environment variables.
 *
 * ## Why keys and not provider registration
 *
 * The harness's `llm-pi-ai` adapter states that `apiKeyEnv` is a credential
 * *reference* resolved per request and that no secret enters its config file. So
 * the harness already owns provider registration - route name, endpoint, wire
 * protocol and model catalog all come from its own configurable-provider
 * directory - and what it lacks is the credential the reference points at.
 *
 * Registering routes from here was rejected twice over: it needs a
 * read-modify-write of the user's `$DSH_HOME/settings.yaml`, which mixes block
 * style with flow mappings and would need a YAML parser the host does not bundle;
 * and a route whose `apiKeyEnv` resolves to nothing fails every request with
 * `MISSING_CREDENTIAL` rather than falling through, so a route written here would
 * break `dsh` run from a plain terminal.
 *
 * ## Why the default is per-key rather than all-on
 *
 * A real secret store is not a list of API keys. The one this was built against
 * holds `MACOS_P12_CERTIFICATE`, `GPG_SIGNING_KEY`, `SUPABASE_SERVICE_ROLE_KEY`,
 * `JXBROWSER_LICENSE_KEY` and about twenty-five other CI secrets - every one
 * ending in `_KEY` or `_TOKEN` exactly like an API key does. Defaulting all of
 * them on would put signing material and a service-role key into the environment
 * of a process running a model's tool calls.
 *
 * So the default is *on for keys recognisable as model-provider credentials*, by
 * an explicit allowlist rather than a pattern, and off for everything else - which
 * stays visible and one switch away. An allowlist fails closed: a provider nobody
 * has added yet shows up off rather than a certificate showing up on.
 */
class DshSecretSync(
    private val context: PluginContext,
    private val env: Map<String, String> = System.getenv(),
) {

    /**
     * Every secret whose username is shaped like an environment variable name,
     * annotated with everything the panel and the defaults need.
     *
     * Harness-referenced keys sort first, then recognised providers, then the
     * rest: the order puts the rows that will do something at the top.
     */
    suspend fun candidates(): List<DshKeyCandidate> = annotate(scan())

    /**
     * The variable NAMES that would be injected, without reading any value.
     *
     * Registration only needs names, and fetching secret values to compute a name
     * set would pull credentials into memory for no reason.
     */
    suspend fun namesFor(selection: DshKeySelection, alreadySupplied: Set<String>): Set<String> =
        candidates()
            .filter { selection.isOn(it) }
            .map { it.envName }
            .filter { it !in alreadySupplied }
            .toSet()

    /**
     * Environment entries to hand a harness child, for the effective selection.
     *
     * Re-reads the store rather than trusting a cached candidate list, so a key
     * rotated in BOSS takes effect on the next launch without a panel refresh.
     *
     * A name the caller already supplies is skipped: the DeepSeek provider path
     * resolves from BOSS's AI Providers page, a more specific statement of intent
     * than a key that merely defaulted on.
     */
    suspend fun envFor(selection: DshKeySelection, alreadySupplied: Set<String>): Map<String, String> {
        val entries = scan()
        val byId = entries.associateBy { it.id }
        return annotate(entries)
            .filter { selection.isOn(it) }
            .filter { it.envName !in alreadySupplied }
            .mapNotNull { candidate ->
                val value = byId[candidate.secretId]?.password?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                candidate.envName to value
            }
            // A conflicted name can still be reached by an explicit tick on both
            // sides; last-write-wins there would be a silent wrong-key. Keeping
            // the first by id at least makes it deterministic and reproducible.
            .distinctBy { it.first }
            .toMap()
    }

    /**
     * Environment variable names referenced by `apiKeyEnv` in the harness's own
     * settings, read-only.
     *
     * A regex rather than a parse, because this only reads and a missed match
     * costs a hint in the panel. Writing this file would be another matter - see
     * the class doc.
     */
    fun harnessReferencedEnvNames(): Set<String> {
        val text = settingsText() ?: return emptySet()
        return API_KEY_ENV.findAll(text).map { it.groupValues[1] }.toSet()
    }

    /**
     * What the harness itself is configured to use, from its own settings.
     *
     * This exists because the panel used to report `api key: not configured`
     * whenever BOSS had no DeepSeek key - including when the harness was fully
     * configured with another provider and turns were succeeding. That reads as
     * "broken" and sends people hunting a fault that is not there.
     */
    fun harnessDefaultModel(): String? {
        val text = settingsText() ?: return null
        val block = DEFAULT_MODEL_BLOCK.find(text)?.value ?: return null
        val provider = field("provider").find(block)?.groupValues?.get(1)
        val model = field("model").find(block)?.groupValues?.get(1)
        return when {
            provider != null && model != null -> "$provider / $model"
            provider != null -> provider
            else -> null
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun scan(): List<SecretEntryData> {
        val secrets = context.secretDataProvider ?: return emptyList()
        return runCatching { secrets.getUserSecrets(limit = MAX_SCAN) }
            .getOrNull()?.getOrNull()?.data.orEmpty()
            .filter { ENV_NAME.matches(it.username) }
    }

    private fun annotate(entries: List<SecretEntryData>): List<DshKeyCandidate> {
        val referenced = harnessReferencedEnvNames()
        val duplicated = entries.groupingBy { it.username }.eachCount().filterValues { it > 1 }.keys
        return entries
            .map {
                DshKeyCandidate(
                    secretId = it.id,
                    label = it.website,
                    envName = it.username,
                    referencedByHarness = it.username in referenced,
                    recognisedProvider = isProviderKey(it.username),
                    conflicted = it.username in duplicated,
                )
            }
            .sortedWith(
                compareByDescending<DshKeyCandidate> { it.referencedByHarness }
                    .thenByDescending { it.recognisedProvider }
                    .thenBy { it.label },
            )
    }

    private fun settingsText(): String? =
        File(DshPaths.home(env), "settings.yaml").takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }

    companion object {
        /**
         * Environment variable names that are model-provider credentials.
         *
         * Compared after removing underscores, so `OPEN_AI_API_KEY` and
         * `OPENAI_API_KEY` are both recognised without the list carrying every
         * spelling. Deliberately an allowlist: it fails closed, so an unknown name
         * defaults off rather than a certificate defaulting on. Add to it freely -
         * that is a smaller decision than loosening it into a pattern.
         */
        val PROVIDER_KEYS: Set<String> = setOf(
            "OPENAI_API_KEY", "OPENAI_KEY", "AZURE_OPENAI_API_KEY",
            "ANTHROPIC_API_KEY",
            "GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_GENERATIVE_AI_API_KEY",
            "XAI_API_KEY", "GROK_API_KEY",
            "DEEPSEEK_API_KEY",
            "TOGETHER_API_KEY", "TOGETHERAI_API_KEY",
            "MISTRAL_API_KEY", "COHERE_API_KEY", "GROQ_API_KEY",
            "OPENROUTER_API_KEY", "PERPLEXITY_API_KEY",
            "FIREWORKS_API_KEY", "DEEPINFRA_API_KEY", "CEREBRAS_API_KEY",
            "MOONSHOT_API_KEY", "KIMI_API_KEY",
            "ZHIPUAI_API_KEY", "GLM_API_KEY",
            "DASHSCOPE_API_KEY", "QWEN_API_KEY",
            "NVIDIA_API_KEY", "HUGGINGFACE_API_KEY", "HF_TOKEN",
        )

        /** True when [envName] names a model-provider credential. */
        fun isProviderKey(envName: String): Boolean =
            normalise(envName) in PROVIDER_KEYS.map(::normalise).toSet()

        private fun normalise(name: String) = name.replace("_", "").uppercase()

        /**
         * An environment-variable-shaped name. Anchored and uppercase, so an email
         * address or a handle in the username field is not offered as one.
         */
        internal val ENV_NAME = Regex("^[A-Z][A-Z0-9_]{2,63}$")

        /** `apiKeyEnv: NAME`, in block or flow style. */
        private val API_KEY_ENV = Regex("""apiKeyEnv\s*:\s*([A-Z][A-Z0-9_]{2,63})""")

        /** The `agent-default-model:` block plus its indented body. */
        private val DEFAULT_MODEL_BLOCK = Regex("agent-default-model:[ \\t]*\\n(?:[ \\t]+\\S.*\\n?)*")

        /**
         * `<name>: value` on an INDENTED line of that block.
         *
         * Anchored to line start with required indentation, not a word boundary:
         * the block header is literally `agent-default-model:`, so a bare `\bmodel`
         * matched the header and captured the NEXT line's value, reporting the
         * provider as the model. Caught by checking the output against a real
         * settings.yaml rather than trusting the pattern.
         */
        private fun field(name: String) =
            Regex("^[ \\t]+" + name + "\\s*:\\s*([A-Za-z0-9._-]+)", RegexOption.MULTILINE)

        private const val MAX_SCAN = 500
    }
}
