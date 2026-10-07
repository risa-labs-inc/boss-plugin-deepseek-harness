package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DshProfileRegistrarTest {
    private val directory = Files.createTempDirectory("dsh-profile-registrar").toFile()
    private val home = File(directory, "home").apply { mkdirs() }
    private val root = File(directory, "toolchain/lib/node_modules/@deepseek-ai/dsh").apply {
        mkdirs()
        File(this, "package.json").writeText("{\"name\": \"@deepseek-ai/dsh\"}")
    }
    private val dsh = File(root, "lib/bin.js").apply { parentFile.mkdirs(); writeText("// harness") }
    private val node = File(directory, "node").apply { writeText("// node") }
    private val env = mapOf(DshPaths.HOME_ENV to home.absolutePath)

    @AfterTest
    fun cleanup() { directory.deleteRecursively() }

    @Test
    fun `profile configuration runs before launch with only validated credential names in argv`() = runTest {
        val commands = mutableListOf<List<String>>()
        val registrar = DshProviderRegistrar(env, { node }, { argv, child ->
            commands += argv
            assertEquals(home.absolutePath, child[DshPaths.HOME_ENV])
            if (argv.first() == dsh.absolutePath) DshExec(0, "composed config", "")
            else {
                assertTrue(File(argv[1]).exists())
                if (Files.getFileStore(File(argv[1]).toPath()).supportsFileAttributeView("posix")) {
                    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(File(argv[1]).toPath()))
                }
                DshExec(0, "ADDED\topenai", "")
            }
        }, { "// trusted helper" })
        val result = registrar.registerProfile(dsh, "headless", setOf("OPENAI_API_KEY", "SUPABASE_SERVICE_ROLE_KEY"), mapOf("OPENAI_API_KEY" to "never-in-argv"))

        assertEquals(listOf("openai"), (result as DshRegisterOutcome.Added).routes)
        assertEquals(listOf(dsh.absolutePath, "--profile", "headless", "--dump-config"), commands.first())
        assertEquals(listOf(root.canonicalPath, home.absolutePath, "headless", "OPENAI_API_KEY"), commands.last().drop(2))
        assertFalse(commands.flatten().any { it == "never-in-argv" })
        assertFalse(File(commands.last()[1]).exists(), "private helper was not cleaned up")
        assertFalse(File(home, "settings.yaml").exists())
    }

    @Test
    fun `failed composition declines writes and does not expose config contents`() = runTest {
        var calls = 0
        val registrar = DshProviderRegistrar(env, { node }, { _, _ ->
            calls++
            DshExec(1, "private config", "credential-value")
        }, { "// trusted helper" })
        val result = registrar.registerProfile(dsh, "web", setOf("OPENAI_API_KEY"), emptyMap())
        assertTrue(result is DshRegisterOutcome.Failed)
        assertEquals(1, calls)
        assertFalse(result.toString().contains("credential-value"))
    }

    @Test
    fun `malformed helper metadata cannot masquerade as successful routes`() {
        val registrar = DshProviderRegistrar(env)
        for (message in listOf("ADDED\tno-adapter", "ADDED\topenai,openai", "untrusted text", "FAILED\tcredential-value")) {
            val result = registrar.parseProfileOutcome(message)
            assertTrue(result is DshRegisterOutcome.Failed)
            assertFalse(result.toString().contains("credential-value"))
        }
        assertEquals(DshRegisterOutcome.UpToDate, registrar.parseProfileOutcome("UP_TO_DATE"))
        assertEquals(emptyList(), (registrar.parseProfileOutcome("ADDED") as DshRegisterOutcome.Added).routes)
    }

    @Test
    fun `first migration and second launch both recheck live profile configuration`() = runTest {
        var helperCalls = 0
        val registrar = DshProviderRegistrar(env, { node }, { argv, _ ->
            if (argv.first() == dsh.absolutePath) DshExec(0, "current settings", "")
            else { helperCalls++; DshExec(0, if (helperCalls == 1) "ADDED" else "UP_TO_DATE", "") }
        }, { "// trusted helper" })
        assertTrue(registrar.registerProfile(dsh, "web", emptySet(), emptyMap()) is DshRegisterOutcome.Added)
        assertEquals(DshRegisterOutcome.UpToDate, registrar.registerProfile(dsh, "web", emptySet(), emptyMap()))
        assertEquals(2, helperCalls)
    }

    @Test
    fun `cancellation removes private helper and propagates to caller`() = runTest {
        var helper: File? = null
        val registrar = DshProviderRegistrar(env, { node }, { argv, _ ->
            if (argv.first() == dsh.absolutePath) DshExec(0, "config", "")
            else { helper = File(argv[1]); throw CancellationException("cancel") }
        }, { "// trusted helper" })
        assertFailsWith<CancellationException> { registrar.registerProfile(dsh, "web", emptySet(), emptyMap()) }
        assertFalse(helper!!.exists())
    }

    @Test
    fun `metadata inspection requests the read only mode and parses only provider references`() = runTest {
        val commands = mutableListOf<List<String>>()
        val registrar = DshProviderRegistrar(env, { node }, { argv, _ ->
            commands += argv
            DshExec(0, "UP_TO_DATE\nMODEL\topenai\tgpt-current\nENVS\tCUSTOM_OPENAI_TOKEN,GOOGLE_API_KEY\n", "")
        }, { "// trusted helper" })
        val metadata = registrar.inspectProfile(dsh, "web")
        assertEquals("--inspect", commands.last().last())
        assertEquals("openai / gpt-current", metadata?.defaultModel)
        assertEquals(setOf("CUSTOM_OPENAI_TOKEN", "GOOGLE_API_KEY"), metadata?.referencedEnvNames)
        assertEquals(DshRegisterOutcome.UpToDate, registrar.parseProfileOutcome("UP_TO_DATE\nMODEL\topenai\tgpt-current\nENVS\tOPENAI_API_KEY\n"))
    }

    @Test
    fun `failed or malformed metadata never seats a misleading provider label`() {
        val registrar = DshProviderRegistrar(env)
        for (message in listOf(
            "FAILED\nMODEL\topenai\tgpt\nENVS\tOPENAI_API_KEY",
            "UP_TO_DATE\nMODEL\topenai\tgpt\nENVS\tinvalid-name",
            "UP_TO_DATE\nMODEL\topenai\tgpt\nMODEL\tother\tmodel\nENVS\tOPENAI_API_KEY",
        )) assertEquals(null, registrar.parseProfileMetadata(message))
    }

    @Test
    fun `settings version detection accepts prereleases and unknown versions avoid legacy rewrites`() {
        val registrar = DshProviderRegistrar(env)
        assertFalse(registrar.usesProfileSettings("0.1.0-rc.7"))
        assertTrue(registrar.usesProfileSettings("0.2.0-rc.2"))
        assertTrue(registrar.usesProfileSettings("v0.2.1"))
        assertTrue(registrar.usesProfileSettings("1.0.0"))
        assertTrue(registrar.usesProfileSettings("unknown"))
    }
}
