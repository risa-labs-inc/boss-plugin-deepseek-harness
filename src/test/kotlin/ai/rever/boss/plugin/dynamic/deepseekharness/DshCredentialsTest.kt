package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.LlmApiFormat
import ai.rever.boss.plugin.api.LlmConfig
import ai.rever.boss.plugin.api.LlmProvider
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Getting the DeepSeek key out of BOSS and into the harness.
 *
 * This is the path that decides whether the plugin can do anything at all: with
 * no key the harness starts, serves its UI, and fails every turn with
 * MISSING_CREDENTIAL. It had no test until someone asked whether it was actually
 * wired up, which is the wrong way round - so the resolution order, the removal
 * semantics, and the no-leak property are all pinned here.
 *
 * It has still never run against a real DeepSeek key. These fakes prove the
 * plumbing, not the credential.
 */
class DshCredentialsTest {

    // ------------------------------------------------------------------ fakes

    private fun llmConfig(providerId: String, key: String) = LlmConfig(
        providerId = providerId,
        displayName = providerId,
        apiFormat = LlmApiFormat.OPENAI_CHAT,
        apiKey = key,
        baseUrl = "https://example.invalid/v1/chat/completions",
        modelId = "m",
    )

    private fun secret(website: String, username: String, password: String) = SecretEntryData(
        id = "id-$website",
        website = website,
        username = username,
        password = password,
        createdAt = "now",
        updatedAt = "now",
    )

    private class FakeLlm(private val configs: List<LlmConfig>) : LlmProvider {
        override fun activeConfig(): LlmConfig? = configs.firstOrNull()
        override fun configuredProviders(): List<LlmConfig> = configs
    }

    /** Throws from [configuredProviders] the way a cross-classloader call can. */
    private class HostileLlm : LlmProvider {
        override fun activeConfig(): LlmConfig? = null
        override fun configuredProviders(): List<LlmConfig> = throw AbstractMethodError("stale api")
    }

    private class FakeSecrets(private val entries: List<SecretEntryData>) : SecretDataProvider {
        override suspend fun searchSecrets(query: String, limit: Int, offset: Int) =
            Result.success(
                PaginatedSecretsData(
                    entries.filter { it.website.contains(query, ignoreCase = true) },
                    hasMore = false,
                ),
            )

        override suspend fun getUserSecrets(limit: Int, offset: Int) =
            Result.success(PaginatedSecretsData(entries, hasMore = false))

        override suspend fun getUserSecretsWithSharingInfo(limit: Int, offset: Int) =
            Result.success(PaginatedSecretsWithSharingData(emptyList(), hasMore = false))

        override suspend fun createSecret(request: CreateSecretRequestData) = Result.success(Unit)
        override suspend fun updateSecret(request: UpdateSecretRequestData) = Result.success(Unit)
        override suspend fun deleteSecret(id: String) = Result.success(Unit)
        override suspend fun getSecretShares(secretId: String) = Result.success(emptyList<SecretShareData>())
        override suspend fun shareSecret(request: ShareSecretRequestData) = Result.success(Unit)
        override suspend fun unshareSecret(request: UnshareSecretRequestData) = Result.success(Unit)
    }

    /**
     * A [PluginContext] answering only the two providers this class reads.
     *
     * A proxy rather than a hand-written fake because PluginContext has dozens of
     * members and gains more every api release; only these two matter here, and
     * null is the legal answer for the rest.
     */
    private fun context(llm: LlmProvider?, secrets: SecretDataProvider?): PluginContext =
        Proxy.newProxyInstance(
            PluginContext::class.java.classLoader,
            arrayOf(PluginContext::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getLlmProvider" -> llm
                "getSecretDataProvider" -> secrets
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "PluginContext(fake)"
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        } as PluginContext

    // ---------------------------------------------------------------- the path

    @Test
    fun `a DeepSeek provider on the AI Providers page is used`() = runTest {
        val creds = DshCredentials(context(FakeLlm(listOf(llmConfig("DEEPSEEK", "k-provider"))), null))

        assertEquals("k-provider", creds.resolve())
        assertEquals(DshKeySource.BOSS_PROVIDER, creds.describe())
    }

    @Test
    fun `the provider match is case-insensitive and substring, since providerId is an open set`() = runTest {
        // The api documents providerId as an open set to be matched defensively.
        // "deepseek-official" is what the harness itself calls its route, so an
        // exact-equality match on "DEEPSEEK" would miss the likely spelling.
        for (id in listOf("DEEPSEEK", "deepseek", "DeepSeek", "deepseek-official", "CUSTOM_DEEPSEEK")) {
            val creds = DshCredentials(context(FakeLlm(listOf(llmConfig(id, "k"))), null))
            assertEquals("k", creds.resolve(), "providerId `$id` should match")
        }
    }

    @Test
    fun `an unrelated provider is not mistaken for DeepSeek`() = runTest {
        // The real machine this was built on has activeProviderId = OPENAI. Using
        // an OpenAI key as a DeepSeek key would send someone's key to the wrong
        // vendor's endpoint.
        val creds = DshCredentials(
            context(FakeLlm(listOf(llmConfig("OPENAI", "k-openai"), llmConfig("RISA_GLM", "k-glm"))), null),
        )

        assertNull(creds.resolve())
        assertEquals(DshKeySource.NONE, creds.describe())
    }

    @Test
    fun `a secret-manager entry is used, and the key comes from password not username`() = runTest {
        // Secret *listings* return the username field, so a credential placed
        // there is readable by anything that can list. Reading username would
        // both break and endorse that.
        val creds = DshCredentials(
            context(null, FakeSecrets(listOf(secret("deepseek.com", "not-the-key", "k-secret")))),
        )

        assertEquals("k-secret", creds.resolve())
        assertEquals(DshKeySource.BOSS_SECRET, creds.describe())
    }

    @Test
    fun `the AI Providers page wins over the secret manager`() = runTest {
        val creds = DshCredentials(
            context(
                FakeLlm(listOf(llmConfig("DEEPSEEK", "k-provider"))),
                FakeSecrets(listOf(secret("deepseek.com", "u", "k-secret"))),
            ),
        )

        assertEquals("k-provider", creds.resolve())
        assertEquals(DshKeySource.BOSS_PROVIDER, creds.describe())
    }

    @Test
    fun `a blank key is treated as absent rather than passed through`() = runTest {
        // An empty apiKey reaching the child would shadow whatever the user typed
        // into the harness's own Models page - see the removal test below.
        val creds = DshCredentials(
            context(FakeLlm(listOf(llmConfig("DEEPSEEK", "   "))), FakeSecrets(listOf(secret("deepseek.com", "u", "")))),
        )

        assertNull(creds.resolve())
    }

    @Test
    fun `nothing configured resolves to nothing`() = runTest {
        val creds = DshCredentials(context(null, null))

        assertNull(creds.resolve())
        assertEquals(DshKeySource.NONE, creds.describe())
    }

    // -------------------------------------------------------------- child env

    @Test
    fun `a resolved key is set in the child environment`() = runTest {
        val env = DshCredentials(context(FakeLlm(listOf(llmConfig("DEEPSEEK", "k"))), null)).childEnv()

        assertEquals(mapOf(DshCredentials.ENV_KEY to "k"), env)
    }

    @Test
    fun `no key maps the variable to null, which means REMOVE, not empty`() = runTest {
        // The load-bearing one. DshCli turns a null value into a removal. An empty
        // string instead would look "configured" to the harness, whose inherited
        // environment outranks every file layer - so BOSS having no key would
        // silently stop a key the user stored through the harness's own UI from
        // being used.
        val env = DshCredentials(context(null, null)).childEnv()

        assertTrue(DshCredentials.ENV_KEY in env, "the variable must be present as a removal instruction")
        assertNull(env[DshCredentials.ENV_KEY], "must be null (remove), never \"\" (set-but-empty)")
    }

    // ------------------------------------------------------------- resilience

    @Test
    fun `a throwing provider degrades instead of taking the panel down`() = runTest {
        // Resolving a host provider is a call across a plugin classloader
        // boundary, so AbstractMethodError and NoSuchMethodError are live. An
        // escape here would propagate into the panel and every MCP tool.
        val creds = DshCredentials(context(HostileLlm(), FakeSecrets(listOf(secret("deepseek.com", "u", "k")))))

        assertEquals("k", creds.resolve(), "must fall through to the secret store")
        assertEquals(DshKeySource.BOSS_SECRET, creds.describe())
    }

    // ------------------------------------------------------------- no leaking

    @Test
    fun `the key never appears in anything user- or model-facing`() = runTest {
        val key = "sk-deepseek-super-secret-value"
        val creds = DshCredentials(context(FakeLlm(listOf(llmConfig("DEEPSEEK", key))), null))

        // describe() is what the panel and dsh_doctor render. A source enum, never
        // the value: a data class carrying a credential prints it from its own
        // toString into any log line that touches it.
        val source = creds.describe()
        assertFalse(source.toString().contains(key))
        assertFalse(source.label().contains(key))
        assertFalse(DshKeySource.entries.any { it.label().contains("sk-") })
    }
}
