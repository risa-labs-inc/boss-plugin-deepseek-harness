package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.CreateSecretRequestData
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
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Choosing which BOSS secrets the harness may see.
 *
 * The stakes are why this is thorough. The store this was built against holds
 * `MACOS_P12_CERTIFICATE`, `GPG_SIGNING_KEY`, `SUPABASE_SERVICE_ROLE_KEY` and
 * about twenty-five other CI secrets whose names end in `_KEY` exactly like an
 * API key's does. Anything that leaked those into a harness process would be
 * handing signing material to an agent, so the tests below use those real names.
 */
class DshSecretSyncTest {

    private val tempHome: File = File.createTempFile("dsh-sync", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val env = mapOf(DshPaths.HOME_ENV to tempHome.absolutePath)

    @AfterTest
    fun cleanup() = tempHome.deleteRecursively().let { }

    private fun entry(id: String, website: String, username: String, password: String = "v") =
        SecretEntryData(
            id = id, website = website, username = username, password = password,
            createdAt = "now", updatedAt = "now",
        )

    private class Secrets(private val all: List<SecretEntryData>) : SecretDataProvider {
        override suspend fun getUserSecrets(limit: Int, offset: Int) =
            Result.success(PaginatedSecretsData(all, hasMore = false))
        override suspend fun searchSecrets(query: String, limit: Int, offset: Int) =
            Result.success(PaginatedSecretsData(all.filter { it.website.contains(query, true) }, false))
        override suspend fun getUserSecretsWithSharingInfo(limit: Int, offset: Int) =
            Result.success(PaginatedSecretsWithSharingData(emptyList(), hasMore = false))
        override suspend fun createSecret(request: CreateSecretRequestData) = Result.success(Unit)
        override suspend fun updateSecret(request: UpdateSecretRequestData) = Result.success(Unit)
        override suspend fun deleteSecret(id: String) = Result.success(Unit)
        override suspend fun getSecretShares(secretId: String) = Result.success(emptyList<SecretShareData>())
        override suspend fun shareSecret(request: ShareSecretRequestData) = Result.success(Unit)
        override suspend fun unshareSecret(request: UnshareSecretRequestData) = Result.success(Unit)
    }

    private fun context(secrets: SecretDataProvider?): PluginContext =
        Proxy.newProxyInstance(
            PluginContext::class.java.classLoader,
            arrayOf(PluginContext::class.java),
        ) { proxy, method, args ->
            when (method.name) {
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

    private fun sync(vararg entries: SecretEntryData) =
        DshSecretSync(context(Secrets(entries.toList())), env)

    // ------------------------------------------------------- what is offered

    @Test
    fun `env-var-shaped usernames are offered`() = runTest {
        val names = sync(
            entry("1", "OPENAI", "OPENAI_API_KEY"),
            entry("2", "XAI", "XAI_API_KEY"),
            entry("3", "GOOGLE", "GEMINI_API_KEY"),
        ).candidates().map { it.envName }

        assertEquals(listOf("GEMINI_API_KEY", "OPENAI_API_KEY", "XAI_API_KEY"), names.sorted())
    }

    @Test
    fun `website logins are not offered`() = runTest {
        // A username that is an email or a handle is a site login, not a key.
        val offered = sync(
            entry("1", "github.com", "shivang.iitk@gmail.com"),
            entry("2", "linkedin.com", "shivang@risa.health"),
            entry("3", "b2clogin.com", "kshivang"),
            entry("4", "localhost", "provider1@risa.health"),
        ).candidates()

        assertEquals(emptyList(), offered.map { it.envName })
    }

    @Test
    fun `recognised provider keys are ON by default`() = runTest {
        // The user asked for keys to work without ticking a box each.
        val s = sync(
            entry("1", "OPENAI", "OPENAI_API_KEY", "sk-openai"),
            entry("2", "XAI", "XAI_API_KEY", "sk-xai"),
            entry("3", "TOGETHER", "TOGETHER_API_KEY", "sk-together"),
        )

        assertTrue(s.candidates().all { it.onByDefault })
        assertEquals(
            mapOf("OPENAI_API_KEY" to "sk-openai", "XAI_API_KEY" to "sk-xai", "TOGETHER_API_KEY" to "sk-together"),
            s.envFor(DshKeySelection(), emptySet()),
        )
    }

    @Test
    fun `signing material and CI tokens are OFF by default`() = runTest {
        // Verbatim names from a real store. Every one ends in _KEY or _TOKEN like
        // an API key does, which is why the default is an allowlist and not a
        // pattern: this is signing material and a service-role key.
        val s = sync(
            entry("c1", "risa-labs-inc/BossConsole", "MACOS_P12_CERTIFICATE", "cert"),
            entry("c2", "risa-labs-inc/BossConsole", "GPG_SIGNING_KEY", "gpg"),
            entry("c3", "risa-labs-inc/BossConsole", "SUPABASE_SERVICE_ROLE_KEY", "srk"),
            entry("c4", "risa-labs-inc/BossConsole", "JXBROWSER_LICENSE_KEY", "lic"),
            entry("c5", "risa-labs-inc/boss-plugin-ai-gateway", "BOSS_STORE_PLUGIN_PUBLISH_KEY", "pub"),
            entry("c6", "CoreWeave Token", "CW_API_TOKEN", "cw"),
        )

        assertTrue(s.candidates().none { it.onByDefault }, "none of these may default on")
        assertEquals(emptyMap(), s.envFor(DshKeySelection(), emptySet()))
    }

    @Test
    fun `a key the harness explicitly asks for is on by default even if unrecognised`() = runTest {
        // The user registered that route themselves naming that variable, which
        // is a direct statement of intent.
        File(tempHome, "settings.yaml").writeText("providers: { acme: { apiKeyEnv: ACME_GATEWAY_TOKEN } }")
        val s = sync(entry("1", "Acme", "ACME_GATEWAY_TOKEN", "sk-acme"))

        val only = s.candidates().single()
        assertFalse(only.recognisedProvider)
        assertTrue(only.referencedByHarness)
        assertTrue(only.onByDefault)
        assertEquals(mapOf("ACME_GATEWAY_TOKEN" to "sk-acme"), s.envFor(DshKeySelection(), emptySet()))
    }

    @Test
    fun `two secrets claiming one name are OFF by default rather than guessed between`() = runTest {
        // Their store really does hold ANTHROPIC_API_KEY twice. Defaulting on
        // would silently pick one, and the wrong API key fails as though the
        // provider rejected you.
        val s = sync(
            entry("1", "Claude Code Github Review", "ANTHROPIC_API_KEY", "sk-a"),
            entry("2", "open_claw", "ANTHROPIC_API_KEY", "sk-b"),
        )

        assertTrue(s.candidates().all { it.conflicted })
        assertTrue(s.candidates().none { it.onByDefault })
        assertEquals(emptyMap(), s.envFor(DshKeySelection(), emptySet()))
    }

    @Test
    fun `a conflict resolves when exactly one side is picked`() = runTest {
        val s = sync(
            entry("1", "Claude Code Github Review", "ANTHROPIC_API_KEY", "sk-a"),
            entry("2", "open_claw", "ANTHROPIC_API_KEY", "sk-b"),
        )

        assertEquals(
            mapOf("ANTHROPIC_API_KEY" to "sk-b"),
            s.envFor(DshKeySelection(enabled = setOf("2")), emptySet()),
        )
    }

    @Test
    fun `turning a default-on key off is remembered, not re-defaulted`() = runTest {
        // The bug a single selected-set would have: "off" indistinguishable from
        // "never chose", so the key returns at the next launch.
        val s = sync(entry("1", "OPENAI", "OPENAI_API_KEY", "sk"))
        val candidate = s.candidates().single()

        val off = DshKeySelection().with(candidate.secretId, on = false, onByDefault = candidate.onByDefault)
        assertEquals(setOf("1"), off.disabled)
        assertFalse(off.isOn(candidate))
        assertEquals(emptyMap(), s.envFor(off, emptySet()))
    }

    @Test
    fun `turning an off-by-default key on is remembered`() = runTest {
        val s = sync(entry("1", "CoreWeave Token", "CW_API_TOKEN", "cw"))
        val candidate = s.candidates().single()

        val on = DshKeySelection().with(candidate.secretId, on = true, onByDefault = candidate.onByDefault)
        assertEquals(setOf("1"), on.enabled)
        assertEquals(mapOf("CW_API_TOKEN" to "cw"), s.envFor(on, emptySet()))
    }

    @Test
    fun `setting a key back to its default clears the override`() = runTest {
        // So that widening the provider allowlist later reaches keys the user
        // never deliberately touched.
        val s = sync(entry("1", "OPENAI", "OPENAI_API_KEY", "sk"))
        val candidate = s.candidates().single()

        val off = DshKeySelection().with(candidate.secretId, false, candidate.onByDefault)
        val back = off.with(candidate.secretId, true, candidate.onByDefault)
        assertEquals(DshKeySelection(), back, "an override equal to the default must not be stored")
    }

    @Test
    fun `underscore spellings of the same provider are both recognised`() = runTest {
        // Their store has OPEN_AI_API_KEY alongside OPENAI_API_KEY.
        assertTrue(DshSecretSync.isProviderKey("OPEN_AI_API_KEY"))
        assertTrue(DshSecretSync.isProviderKey("OPENAI_API_KEY"))
        assertFalse(DshSecretSync.isProviderKey("ANTRHOPIC_API_KEY"), "a typo must not be recognised")
        assertFalse(DshSecretSync.isProviderKey("GPG_SIGNING_KEY"))
    }

    @Test
    fun `an already-supplied name is not overwritten`() = runTest {
        // The DeepSeek provider path owns DEEPSEEK_API_KEY.
        val s = sync(entry("1", "DEEPSEEK", "DEEPSEEK_API_KEY", "sk-from-secret"))

        assertEquals(emptyMap(), s.envFor(DshKeySelection(), setOf("DEEPSEEK_API_KEY")))
    }

    @Test
    fun `a selection naming a deleted secret is skipped, not fatal`() = runTest {
        val s = sync(entry("1", "OPENAI", "OPENAI_API_KEY", "sk"))

        assertEquals(
            mapOf("OPENAI_API_KEY" to "sk"),
            s.envFor(DshKeySelection(enabled = setOf("gone")), emptySet()),
        )
    }

    @Test
    fun `a blank password is skipped rather than sent as empty`() = runTest {
        // An empty value looks "set" to the harness and, since inherited env
        // outranks its files, would shadow a working key in .credentials.yaml.
        val s = sync(entry("1", "OPENAI", "OPENAI_API_KEY", "   "))

        assertEquals(emptyMap(), s.envFor(DshKeySelection(), emptySet()))
    }

    @Test
    fun `no secret provider degrades to nothing`() = runTest {
        val s = DshSecretSync(context(null), env)

        assertEquals(emptyList(), s.candidates())
        assertEquals(emptyMap(), s.envFor(DshKeySelection(enabled = setOf("1")), emptySet()))
    }

    @Test
    fun `namesFor applies the same blank-value filter as envFor`() = runTest {
        // They disagreed: namesFor skipped the blank check, so plan() proposed a
        // route for a credential that would never be injected - the exact
        // MISSING_CREDENTIAL failure the registrar exists to avoid.
        val s = sync(
            entry("1", "OPENAI", "OPENAI_API_KEY", "   "),
            entry("2", "XAI", "XAI_API_KEY", "sk-xai"),
        )

        assertEquals(setOf("XAI_API_KEY"), s.namesFor(DshKeySelection(), emptySet()))
    }

    @Test
    fun `namesFor and envFor always agree`() = runTest {
        val s = sync(
            entry("1", "OPENAI", "OPENAI_API_KEY", "sk"),
            entry("2", "XAI", "XAI_API_KEY", ""),
            entry("3", "risa-labs-inc/BossConsole", "GPG_SIGNING_KEY", "gpg"),
        )

        assertEquals(
            s.envFor(DshKeySelection(), emptySet()).keys,
            s.namesFor(DshKeySelection(), emptySet()),
        )
    }

    // --------------------------------------------- reading the harness config

    @Test
    fun `apiKeyEnv references are read from flow and block style alike`() {
        // Their real file uses a flow mapping on one line; the README documents
        // block style. Both have to be seen.
        File(tempHome, "settings.yaml").writeText(
            """
            llm-pi-ai:
              providers: { google: { apiKeyEnv: GOOGLE_API_KEY } }
              more:
                openai:
                  apiKeyEnv: OPENAI_API_KEY
            """.trimIndent(),
        )
        val referenced = DshSecretSync(context(null), env).harnessReferencedEnvNames()

        assertEquals(setOf("GOOGLE_API_KEY", "OPENAI_API_KEY"), referenced)
    }

    @Test
    fun `a candidate the harness already asks for is flagged and sorted first`() = runTest {
        File(tempHome, "settings.yaml").writeText("providers: { google: { apiKeyEnv: GEMINI_API_KEY } }")
        val offered = sync(
            entry("1", "OPENAI", "OPENAI_API_KEY"),
            entry("2", "GOOGLE", "GEMINI_API_KEY"),
        ).candidates()

        assertEquals("GEMINI_API_KEY", offered.first().envName, "a referenced key must sort first")
        assertTrue(offered.first().referencedByHarness)
        assertFalse(offered.last().referencedByHarness)
    }

    @Test
    fun `the harness default model is read, and the header does not fool the model field`() {
        // `agent-default-model:` literally contains "model", so a word-boundary
        // match found the header and captured the NEXT line's value, reporting
        // the provider as the model. Caught against a real settings.yaml.
        File(tempHome, "settings.yaml").writeText(
            """
            ui-theme:
              preference: system
            agent-default-model:
              provider: google
              model: gemini-3.6-flash
            """.trimIndent(),
        )

        assertEquals("google / gemini-3.6-flash", DshSecretSync(context(null), env).harnessDefaultModel())
    }

    @Test
    fun `no settings file and no selection read as absent`() {
        val s = DshSecretSync(context(null), env)

        assertNull(s.harnessDefaultModel())
        assertEquals(emptySet(), s.harnessReferencedEnvNames())
    }

    @Test
    fun `a stored selection string round-trips, tolerating stray separators`() {
        assertEquals(setOf("a", "b"), DshServices.decodeIds("a,b"))
        assertEquals(setOf("a", "b"), DshServices.decodeIds(" a , b ,,"))
        assertEquals(emptySet(), DshServices.decodeIds(""))
        assertEquals(emptySet(), DshServices.decodeIds(",,,"))
    }
}
