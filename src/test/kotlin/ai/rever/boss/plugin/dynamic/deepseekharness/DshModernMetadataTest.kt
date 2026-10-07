package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DshModernMetadataTest {
    private val home = Files.createTempDirectory("dsh-modern-metadata").toFile()
    private val sync = DshSecretSync(FakeServices.context(), mapOf(DshPaths.HOME_ENV to home.absolutePath))

    @AfterTest
    fun cleanup() { home.deleteRecursively() }

    @Test
    fun `archived global settings do not mask the active profile model and custom key references`() {
        File(home, "settings.yaml.imported").writeText("llm-pi-ai:\n  providers:\n    openai:\n      apiKeyEnv: STALE_KEY\nagent-default-model:\n  provider: openai\n  model: stale-model\n")
        val registrar = DshProviderRegistrar(mapOf(DshPaths.HOME_ENV to home.absolutePath))
        sync.setProfileMetadata(registrar.parseProfileMetadata("UP_TO_DATE\nMODEL\topenai\tprofile-model\nENVS\tCURRENT_PROFILE_KEY\n"))
        assertEquals("openai / profile-model", sync.harnessDefaultModel())
        assertEquals(setOf("CURRENT_PROFILE_KEY"), sync.harnessReferencedEnvNames())

        sync.setProfileMetadata(registrar.parseProfileMetadata("UP_TO_DATE\nMODEL\tanthropic\tnew-model\nENVS\tANTHROPIC_API_KEY\n"))
        assertEquals("anthropic / new-model", sync.harnessDefaultModel())
        assertEquals(setOf("ANTHROPIC_API_KEY"), sync.harnessReferencedEnvNames())
    }

    @Test
    fun `switching back to an old CLI restores the legacy settings reader`() {
        File(home, "settings.yaml").writeText("llm-pi-ai:\n  providers:\n    google:\n      apiKeyEnv: GOOGLE_API_KEY\nagent-default-model:\n  provider: google\n  model: legacy-model\n")
        sync.setProfileMetadata(DshHarnessMetadata("openai / profile-model", setOf("OPENAI_API_KEY")))
        sync.setProfileMetadata(null)
        assertEquals("google / legacy-model", sync.harnessDefaultModel())
        assertEquals(setOf("GOOGLE_API_KEY"), sync.harnessReferencedEnvNames())
    }
}
