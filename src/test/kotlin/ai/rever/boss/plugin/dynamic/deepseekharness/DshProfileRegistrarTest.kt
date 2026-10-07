package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

        assertEquals(listOf("openai"), (result.outcome as DshRegisterOutcome.Added).routes)
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
        assertTrue(result.outcome is DshRegisterOutcome.Failed)
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
        assertTrue(registrar.registerProfile(dsh, "web", emptySet(), emptyMap()).outcome is DshRegisterOutcome.Added)
        assertEquals(DshRegisterOutcome.UpToDate, registrar.registerProfile(dsh, "web", emptySet(), emptyMap()).outcome)
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
    fun `concurrent web and headless registrations return their own immutable metadata`() = runTest {
        val webStarted = CompletableDeferred<Unit>()
        val releaseWeb = CompletableDeferred<Unit>()
        val registrar = DshProviderRegistrar(env, { node }, { argv, _ ->
            if (argv.first() == dsh.absolutePath) DshExec(0, "config", "")
            else {
                val profile = argv[4]
                if (profile == "web") { webStarted.complete(Unit); releaseWeb.await() }
                DshExec(0, "ADDED\topenai\nMODEL\topenai\t$profile-model\nENVS\tOPENAI_API_KEY\n", "")
            }
        }, { "// trusted helper" })
        val web = async { registrar.registerProfile(dsh, "web", emptySet(), emptyMap()) }
        webStarted.await()
        val headless = registrar.registerProfile(dsh, "headless", emptySet(), emptyMap())
        releaseWeb.complete(Unit)
        val webResult = web.await()
        assertEquals("openai / web-model", webResult.metadata?.defaultModel)
        assertEquals("openai / headless-model", headless.metadata?.defaultModel)
        assertEquals(listOf("openai"), (webResult.outcome as DshRegisterOutcome.Added).routes)
    }

    @Test
    fun `inspection of an initialized profile skips CLI compose and starts only the helper`() = runTest {
        File(home, "profiles/web/package.json").apply { parentFile.mkdirs(); writeText("{}") }
        val commands = mutableListOf<List<String>>()
        val registrar = DshProviderRegistrar(env, { node }, { argv, _ ->
            commands += argv
            DshExec(0, "UP_TO_DATE\nMODEL\topenai\tcurrent-model\nENVS\tOPENAI_API_KEY\n", "")
        }, { "// trusted helper" })
        assertEquals("openai / current-model", registrar.inspectProfile(dsh, "web")?.defaultModel)
        assertEquals(1, commands.size)
        assertEquals(node.absolutePath, commands.single().first())
        assertEquals("--inspect", commands.single().last())
    }

    @Test
    fun `registration of an initialized profile skips redundant CLI composition`() = runTest {
        File(home, "profiles/web/package.json").apply { parentFile.mkdirs(); writeText("{}") }
        val commands = mutableListOf<List<String>>()
        val registrar = DshProviderRegistrar(env, { node }, { argv, _ ->
            commands += argv
            DshExec(0, "UP_TO_DATE", "")
        }, { "// trusted helper" })
        assertEquals(DshRegisterOutcome.UpToDate,
            registrar.registerProfile(dsh, "web", emptySet(), emptyMap()).outcome)
        assertEquals(1, commands.size)
        assertEquals(node.absolutePath, commands.single().first())
    }

    @Test
    fun `explicit resolved Node bypasses PATH lookup for the helper`() = runTest {
        val chosenNode = File(directory, "chosen-node")
        var helperNode: String? = null
        val registrar = DshProviderRegistrar(env, { error("PATH must not choose the helper runtime") }, { argv, _ ->
            if (argv.first() != dsh.absolutePath) helperNode = argv.first()
            DshExec(0, "UP_TO_DATE", "")
        }, { "// trusted helper" })
        val ready = DshInstall.Ready(dsh, "0.2.0-rc.2", chosenNode)
        assertEquals(DshRegisterOutcome.UpToDate,
            registrar.registerProfile(ready.dsh, "web", emptySet(), emptyMap(), resolvedNode = ready.resolvedNode).outcome)
        assertEquals(chosenNode.absolutePath, helperNode)
        assertEquals(dsh, ready.component1())
        assertEquals("0.2.0-rc.2", ready.component2())
        assertFalse(ready == DshInstall.Ready(dsh, ready.version, File(directory, "other-node")))
        assertEquals(chosenNode, ready.copy().resolvedNode)
    }

    @Test
    fun `unsupported non POSIX permission flags do not block Windows registration`() {
        val attempts = mutableListOf<Pair<Boolean, Boolean>>()
        DshProviderRegistrar(env).bestEffortNonPosixPermissions(node,
            readable = { _, enabled, ownerOnly -> attempts += enabled to ownerOnly; false },
            writable = { _, enabled, ownerOnly -> attempts += enabled to ownerOnly; false },
        )
        assertEquals(listOf(false to false, true to true, false to false, true to true), attempts)
    }

    @Test
    fun `credential configuration failures keep an actionable remedy without raw contents`() = runTest {
        val registrar = DshProviderRegistrar(env, { node }, { _, _ ->
            DshExec(1, "", "credentials-local: the value for \"version\" in /private/.credentials.yaml must be a string; secret-value")
        }, { "// trusted helper" })
        val result = registrar.registerProfile(dsh, "web", emptySet(), emptyMap())
        assertTrue(result.outcome is DshRegisterOutcome.Failed)
        assertTrue(result.outcome.toString().contains(".credentials.yaml"))
        assertFalse(result.toString().contains("secret-value"))
    }

    @Test
    fun `refused profile registration explains the patch file and backup remedy`() {
        val result = DshProviderRegistrar(env).parseProfileOutcome("REFUSED\tunsafe-document")
        assertTrue(result is DshRegisterOutcome.Failed)
        assertTrue(result.reason.contains("profile or home cordis.patch.yml"))
        assertTrue(result.reason.contains("boss-overlays/profile-migrations"))
        assertFalse(result.reason.contains("Models page"))
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
