package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Writing provider routes into a file the user owns.
 *
 * The tests that matter most here are the ones where it *declines*. There is no
 * YAML parser available, so the writer only handles `{ route: { apiKeyEnv: NAME } }`
 * shapes and must refuse everything else rather than re-emit a `models` list or a
 * `compat` block from a regex and silently drop it.
 */
class DshProviderRegistrarTest {

    private val home: File = File.createTempFile("dsh-reg", "").let { it.delete(); it.mkdirs(); it }
    private val env = mapOf(DshPaths.HOME_ENV to home.absolutePath)
    private val registrar = DshProviderRegistrar(env)
    private val settings get() = File(home, "settings.yaml")

    @AfterTest
    fun cleanup() = home.deleteRecursively().let { }

    // ------------------------------------------------------- verified route map

    @Test
    fun `every mapped route is one probed to exist`() {
        // A route pi-ai does not ship registers NO adapter and fails only when a
        // request reaches it, so an unverified name here is a silent breakage.
        val unverified = DshProviderRegistrar.ROUTE_FOR_ENV.values.toSet() -
            DshProviderRegistrar.VERIFIED_ROUTES
        assertEquals(emptySet(), unverified, "these route names were never probed against a real dsh")
    }

    @Test
    fun `no known-absent route name is ever mapped`() {
        // `gemini`, `grok` and `togetherai` are the traps: they read like the
        // obvious names and ship nothing. The keys map to google/xai/together.
        val absent = DshProviderRegistrar.ROUTE_FOR_ENV.values.toSet()
            .intersect(DshProviderRegistrar.KNOWN_ABSENT_ROUTES)
        assertEquals(emptySet(), absent)
        assertEquals("google", DshProviderRegistrar.ROUTE_FOR_ENV["GEMINI_API_KEY"])
        assertEquals("xai", DshProviderRegistrar.ROUTE_FOR_ENV["GROK_API_KEY"])
        assertEquals("together", DshProviderRegistrar.ROUTE_FOR_ENV["TOGETHERAI_API_KEY"])
    }

    // ------------------------------------------------------------------ planning

    @Test
    fun `only keys with a known route are proposed`() {
        val plan = registrar.plan(setOf("OPENAI_API_KEY", "CW_API_TOKEN", "GPG_SIGNING_KEY"))

        assertEquals(listOf("openai"), plan.map { it.route })
    }

    @Test
    fun `an already-registered route is not proposed again`() {
        settings.writeText("llm-pi-ai:\n  providers: { google: { apiKeyEnv: GOOGLE_API_KEY } }\n")

        val plan = registrar.plan(setOf("GEMINI_API_KEY", "OPENAI_API_KEY"))

        assertEquals(listOf("openai"), plan.map { it.route }, "google is already there")
    }

    @Test
    fun `two keys for one route propose it once, preferring the canonical name`() {
        // Seen on a real store: an `OPENAI` secret holding OPENAI_API_KEY and a
        // "Mahmood OpenAI Key" holding OPEN_AI_API_KEY. A plain distinctBy over a
        // Set picked the colleague's key. The choice must be the canonical name,
        // and it must not depend on iteration order.
        repeat(20) {
            val plan = registrar.plan(setOf("OPEN_AI_API_KEY", "OPENAI_API_KEY", "OPENAI_KEY"))

            assertEquals(1, plan.size)
            assertEquals("openai", plan.single().route)
            assertEquals("OPENAI_API_KEY", plan.single().apiKeyEnv)
        }
    }

    @Test
    fun `a non-canonical name is still used when it is the only one`() {
        val plan = registrar.plan(setOf("OPEN_AI_API_KEY"))

        assertEquals("OPEN_AI_API_KEY", plan.single().apiKeyEnv)
    }

    @Test
    fun `GEMINI_API_KEY registers the google route, not a gemini route`() {
        val plan = registrar.plan(setOf("GEMINI_API_KEY"))

        assertEquals("google", plan.single().route)
        assertEquals("GEMINI_API_KEY", plan.single().apiKeyEnv)
    }

    @Test
    fun `every route has a canonical env name that maps back to it`() {
        DshProviderRegistrar.CANONICAL_ENV_FOR_ROUTE.forEach { (route, envName) ->
            assertEquals(route, DshProviderRegistrar.ROUTE_FOR_ENV[envName], "$envName should map to $route")
        }
        assertEquals(
            DshProviderRegistrar.VERIFIED_ROUTES,
            DshProviderRegistrar.CANONICAL_ENV_FOR_ROUTE.keys,
            "every verified route needs a canonical spelling for tie-breaking",
        )
    }

    // ------------------------------------------------------------------ writing

    @Test
    fun `their real settings shape is extended, preserving the other sections`() {
        val original = """
            ui-onboarding:
              welcomeNoticeVersion: 2026-08-13.1
            ui-theme:
              preference: system
            llm-pi-ai:
              providers: { google: { apiKeyEnv: GOOGLE_API_KEY } }
            agent-default-model:
              provider: google
              model: gemini-3.6-flash
        """.trimIndent() + "\n"
        settings.writeText(original)

        val outcome = registrar.register(listOf(DshRouteAddition("openai", "OPENAI_API_KEY")))
        val after = settings.readText()

        assertTrue(outcome is DshRegisterOutcome.Added, "expected a write, got $outcome")
        // The pre-existing route survives, the new one is added...
        assertTrue(after.contains("google:"), after)
        assertTrue(after.contains("apiKeyEnv: GOOGLE_API_KEY"), after)
        assertTrue(after.contains("openai:"), after)
        assertTrue(after.contains("apiKeyEnv: OPENAI_API_KEY"), after)
        // ...and every unrelated section is untouched.
        assertTrue(after.contains("welcomeNoticeVersion: 2026-08-13.1"), after)
        assertTrue(after.contains("preference: system"), after)
        assertTrue(after.contains("model: gemini-3.6-flash"), after)
    }

    @Test
    fun `the user's model selection is never rewritten`() {
        // Adding a provider is not switching which vendor bills the next turn.
        settings.writeText("agent-default-model:\n  provider: google\n  model: gemini-3.6-flash\n")

        registrar.register(listOf(DshRouteAddition("openai", "OPENAI_API_KEY")))

        val after = settings.readText()
        assertTrue(after.contains("provider: google"))
        assertTrue(after.contains("model: gemini-3.6-flash"))
        assertFalse(after.contains("provider: openai"))
    }

    @Test
    fun `a backup is taken before the file is changed`() {
        val original = "llm-pi-ai:\n  providers: { google: { apiKeyEnv: GOOGLE_API_KEY } }\n"
        settings.writeText(original)

        val outcome = registrar.register(listOf(DshRouteAddition("openai", "OPENAI_API_KEY")))

        val added = outcome as DshRegisterOutcome.Added
        assertTrue(added.backup!!.isFile)
        assertEquals(original, added.backup.readText(), "the backup must be the file as it was")
    }

    @Test
    fun `a missing settings file is created`() {
        assertFalse(settings.exists())

        val outcome = registrar.register(listOf(DshRouteAddition("openai", "OPENAI_API_KEY")))

        assertTrue(outcome is DshRegisterOutcome.Added)
        assertTrue(settings.readText().contains("apiKeyEnv: OPENAI_API_KEY"))
    }

    @Test
    fun `an empty plan touches nothing`() {
        settings.writeText("ui-theme:\n  preference: system\n")
        val before = settings.readText()

        assertEquals(DshRegisterOutcome.UpToDate, registrar.register(emptyList()))
        assertEquals(before, settings.readText())
    }

    @Test
    fun `registering twice is idempotent`() {
        registrar.register(registrar.plan(setOf("OPENAI_API_KEY")))
        val first = settings.readText()

        assertEquals(DshRegisterOutcome.UpToDate, registrar.register(registrar.plan(setOf("OPENAI_API_KEY"))))
        assertEquals(first, settings.readText())
    }

    // ------------------------------------------------------- refusing to guess

    @Test
    fun `a models list makes it refuse rather than re-emit`() {
        // Re-emitting this from a regex would drop the list and quietly change
        // which models the route serves.
        val original = """
            llm-pi-ai:
              providers:
                anthropic:
                  apiKeyEnv: ANTHROPIC_API_KEY
                  models:
                    - id: claude-sonnet-4-5
                      contextWindow: 200000
        """.trimIndent() + "\n"
        settings.writeText(original)

        val outcome = registrar.register(listOf(DshRouteAddition("openai", "OPENAI_API_KEY")))

        assertTrue(outcome is DshRegisterOutcome.TooComplex, "expected a refusal, got $outcome")
        assertEquals(original, settings.readText(), "a refusal must not modify the file")
        assertTrue(
            (outcome as DshRegisterOutcome.TooComplex).manualYaml.contains("apiKeyEnv: OPENAI_API_KEY"),
            "a refusal has to hand back something the user can paste",
        )
    }

    @Test
    fun `other profile options also make it refuse`() {
        for (extra in listOf(
            "      baseURL: https://proxy.example/v1",
            "      retryPolicy:\n        maxRetries: 3",
            "      compat:\n        maxTokensField: max_tokens",
            "      modelOverrides:\n        x:\n          contextWindow: 1",
            "      reasoning: high",
        )) {
            val original = "llm-pi-ai:\n  providers:\n    openai:\n      apiKeyEnv: OPENAI_API_KEY\n$extra\n"
            settings.writeText(original)

            val outcome = registrar.register(listOf(DshRouteAddition("xai", "XAI_API_KEY")))

            assertTrue(
                outcome is DshRegisterOutcome.TooComplex,
                "should refuse when a profile carries `${extra.trim().substringBefore(':')}`, got $outcome",
            )
            assertEquals(original, settings.readText(), "the file must be untouched")
        }
    }

    @Test
    fun `an unrecognised key under llm-pi-ai makes it refuse`() {
        val original = "llm-pi-ai:\n  somethingNew: 1\n  providers: { google: { apiKeyEnv: GOOGLE_API_KEY } }\n"
        settings.writeText(original)

        val outcome = registrar.register(listOf(DshRouteAddition("openai", "OPENAI_API_KEY")))

        assertTrue(outcome is DshRegisterOutcome.TooComplex, "got $outcome")
        assertEquals(original, settings.readText())
    }

    @Test
    fun `output is canonical block style so a diff is readable`() {
        val rendered = registrar.renderPiAiBlock(mapOf("openai" to "OPENAI_API_KEY", "google" to "GOOGLE_API_KEY"))

        assertEquals(
            """
            llm-pi-ai:
              providers:
                google:
                  apiKeyEnv: GOOGLE_API_KEY
                openai:
                  apiKeyEnv: OPENAI_API_KEY
            """.trimIndent() + "\n",
            rendered,
        )
    }
}
