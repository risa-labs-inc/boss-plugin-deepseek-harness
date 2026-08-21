package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.PluginContext

/**
 * Supplying the harness its API key from what BOSS already holds.
 *
 * ## Why the environment, and only the environment
 *
 * The harness resolves credentials through four layers, and its own
 * `dsh-credentials-local` README states the order: **the inherited process
 * environment always wins**, over `$DSH_HOME/.credentials.yaml` and over both
 * `.env` layers. Its stated reason is that a per-run override is operator
 * intent for that run, and that because it cannot be edited from inside it must
 * be visibly read-only.
 *
 * That is exactly the seam this plugin wants. Injecting the key into the child's
 * environment means:
 *
 * - it takes effect for the run BOSS started and no other,
 * - it does not fight, or silently shadow, a key the user typed into the
 *   harness's own Models page,
 * - and **nothing is written to disk**. Writing `.credentials.yaml` would copy a
 *   secret out of BOSS's store into a plaintext YAML file that outlives the
 *   session, which is a worse posture than the one we started from.
 *
 * Confirmed empirically: with no key, `dsh --profile headless` exits 1 with
 * `MISSING_CREDENTIAL: llm-deepseek: no API key for provider route
 * "deepseek-official"; store DEEPSEEK_API_KEY through the credentials service
 * ..., or export DEEPSEEK_API_KEY in the launching environment`.
 *
 * ## Handling the value
 *
 * [resolve] returns the key; [describe] returns only where it came from. The UI
 * and every MCP tool result use [describe]. This split is deliberate: a data
 * class whose components include a live credential prints it from its own
 * `toString()` into any log line or crash report that touches it, which has
 * already happened once in this workspace.
 */
class DshCredentials(private val context: PluginContext) {

    /** The variable the harness reads for the DeepSeek provider route. */
    companion object {
        const val ENV_KEY = "DEEPSEEK_API_KEY"

        /** Marker the harness prints when no key resolved, on stderr, exit 1. */
        const val MISSING_MARKER = "MISSING_CREDENTIAL"

        /** Tag/website a secret-manager entry uses to offer its key to the harness. */
        const val SECRET_WEBSITE = "deepseek.com"

        private const val PROVIDER_MATCH = "deepseek"
    }

    /**
     * The key to hand the harness, or null when nothing is configured.
     *
     * Order mirrors "most explicit intent first":
     *
     * 1. A DeepSeek provider on BOSS's AI Providers page — the user configured
     *    this for BOSS to use, so using it here needs no second decision.
     * 2. A secret-manager entry for `deepseek.com`. The key goes in `password`,
     *    never `username`: secret *listings* return the username field, so a
     *    credential placed there is readable by anything that can list.
     * 3. BOSS's own inherited environment. If BOSS was launched from a shell
     *    that exported the key, the child would inherit it anyway — resolving it
     *    here only makes the panel able to say so.
     */
    suspend fun resolve(): String? {
        fromProvider()?.let { return it }
        fromSecret()?.let { return it }
        return System.getenv(ENV_KEY)?.takeIf { it.isNotBlank() }
    }

    /** Where a key would come from, for display. Never returns the key. */
    suspend fun describe(): DshKeySource = when {
        fromProvider() != null -> DshKeySource.BOSS_PROVIDER
        fromSecret() != null -> DshKeySource.BOSS_SECRET
        !System.getenv(ENV_KEY).isNullOrBlank() -> DshKeySource.INHERITED_ENV
        else -> DshKeySource.NONE
    }

    /**
     * Environment additions for a harness child process.
     *
     * A resolved key is set. When nothing resolved, [ENV_KEY] is mapped to null,
     * which [DshCli.exec] turns into a *removal* rather than an empty string.
     * That distinction matters: the harness treats a set-but-empty variable as
     * the inherited layer having supplied a value, so an empty string would
     * shadow the `.credentials.yaml` entry the user may have written through the
     * harness's own Models page — turning "BOSS has no key" into "your working
     * key stopped being used".
     */
    suspend fun childEnv(): Map<String, String?> = mapOf(ENV_KEY to resolve())

    /**
     * Names this class supplies, so [DshSecretSync] does not overwrite them.
     *
     * A ticked secret and a configured AI provider can both name
     * `DEEPSEEK_API_KEY`; the provider is the more specific statement of intent,
     * so it wins and the sync skips that name rather than the two racing on map
     * insertion order.
     *
     * **Conditional on a value actually resolving.** Claiming the name
     * unconditionally meant that with no provider configured, `childEnv()` mapped
     * the name to null (a removal) while the sync skipped it as "already
     * supplied" - so a DEEPSEEK_API_KEY secret the user had ticked was never
     * injected, the inherited one was unset, and the `deepseek` route could never
     * be registered.
     */
    suspend fun suppliedNames(): Set<String> = if (resolve() != null) setOf(ENV_KEY) else emptySet()

    private fun fromProvider(): String? {
        val provider = context.llmProvider ?: return null
        val configs = runCatching { provider.configuredProviders() }.getOrNull().orEmpty()
        return configs
            .firstOrNull { it.providerId.contains(PROVIDER_MATCH, ignoreCase = true) }
            ?.apiKey
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun fromSecret(): String? {
        val secrets = context.secretDataProvider ?: return null
        val found = runCatching { secrets.searchSecrets(SECRET_WEBSITE, limit = 10) }
            .getOrNull()
            ?.getOrNull()
            ?.data
            .orEmpty()
        return found
            .firstOrNull { it.website.contains(PROVIDER_MATCH, ignoreCase = true) }
            ?.password
            ?.takeIf { it.isNotBlank() }
    }
}

/** Human-facing label for a key source, safe to render anywhere. */
fun DshKeySource.label(): String = when (this) {
    DshKeySource.BOSS_PROVIDER -> "BOSS AI Providers"
    DshKeySource.BOSS_SECRET -> "BOSS secret manager"
    DshKeySource.INHERITED_ENV -> "inherited environment"
    DshKeySource.NONE -> "not configured"
}
