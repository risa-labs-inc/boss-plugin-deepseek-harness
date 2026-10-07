package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.LlmApiFormat
import ai.rever.boss.plugin.api.LlmConfig
import ai.rever.boss.plugin.api.LlmProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DshLegacyProviderSyncTest {
    @Test
    fun `legacy CLI does not write a pi ai route for only the dedicated BOSS DeepSeek credential`() = runTest {
        val home = Files.createTempDirectory("dsh-legacy-provider-sync").toFile()
        try {
            val config = LlmConfig(providerId = "deepseek", displayName = "DeepSeek",
                apiFormat = LlmApiFormat.OPENAI_CHAT, apiKey = "test-boss-key",
                baseUrl = "https://example.invalid/v1", modelId = "test-model")
            val provider = object : LlmProvider {
                override fun activeConfig(): LlmConfig = config
                override fun configuredProviders(): List<LlmConfig> = listOf(config)
            }
            val engine = DshEngine(FakeServices.context(mapOf("getLlmProvider" to provider)),
                mapOf(DshPaths.HOME_ENV to home.absolutePath))
            val field = DshEngine::class.java.getDeclaredField("_install").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val install = field.get(engine) as MutableStateFlow<DshInstall>
            install.value = DshInstall.Ready(File(home, "dsh"), "0.1.0-rc.7")

            assertEquals("test-boss-key", engine.credentials.resolve())
            assertEquals(DshRegisterOutcome.UpToDate, engine.syncProviders())
            val settings = File(home, "settings.yaml")
            assertFalse(settings.exists(), "the dedicated adapter key must not create a new pi-ai config")
            val original = "# user selection\nagent-default-model: { provider: openai, model: user-model }\n"
            settings.writeText(original)
            assertEquals(DshRegisterOutcome.UpToDate, engine.syncProviders())
            assertEquals(original, settings.readText())
            assertFalse(File(home, "settings.yaml.boss-backup").exists())
        } finally {
            home.deleteRecursively()
        }
    }
}
