package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DshProviderRouteParityTest {
    private val source = assertNotNull(javaClass.getResourceAsStream("/META-INF/boss-plugin/dsh-profile-update.mjs"))
        .bufferedReader().use { it.readText() }

    private fun table(name: String): Map<String, String> {
        val block = assertNotNull(Regex("""const\s+$name\s*=\s*\{([\s\S]*?)\};""").find(source)).groupValues[1]
        return Regex("""([A-Za-z][A-Za-z0-9_]*):\s*'([^']+)'""").findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `packaged helper route names exactly match the Kotlin credential allowlist`() {
        assertEquals(DshProviderRegistrar.ROUTE_FOR_ENV, table("ROUTES"))
    }

    @Test
    fun `packaged helper canonical credential preference matches the Kotlin registrar`() {
        assertEquals(DshProviderRegistrar.CANONICAL_ENV_FOR_ROUTE, table("CANONICAL"))
    }
}
