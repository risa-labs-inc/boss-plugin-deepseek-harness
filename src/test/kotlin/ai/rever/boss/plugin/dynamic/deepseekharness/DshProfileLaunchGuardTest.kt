package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class DshProfileLaunchGuardTest {
    private val home = Files.createTempDirectory("dsh-profile-launch-guard").toFile()
    private val marker = File(home, "child-started")
    private val dsh = File(home, "dsh").apply {
        // This is deliberately not an installed package, so modern profile
        // preservation fails. Reaching the process launch is the regression.
        writeText("#!/bin/sh\n/usr/bin/touch '${marker.absolutePath.replace("'", "'\"'\"'")}'\nprintf 'unexpected child turn'\n")
        setExecutable(true)
    }
    private fun engine(): DshEngine {
        val engine = DshEngine(FakeServices.context(), mapOf(DshPaths.HOME_ENV to home.absolutePath))
        val field = DshEngine::class.java.getDeclaredField("_install").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val install = field.get(engine) as MutableStateFlow<DshInstall>
        install.value = DshInstall.Ready(dsh, "0.2.0-rc.2", File(home, "resolved-node"))
        return engine
    }

    @AfterTest
    fun cleanup() { home.deleteRecursively() }

    @Test
    fun `modern ask refuses a turn before spawning when configured settings cannot be preserved`() = runTest {
        val (message, failed) = engine().ask("test task", null, 1)
        assertTrue(failed)
        assertTrue(message.contains("provider settings could not be preserved safely"))
        assertFalse(marker.exists(), "a task ran with an unverified vendor/default model")
    }

    @Test
    fun `modern web refuses to start before spawning when configured settings cannot be preserved`() = runTest {
        val engine = engine()
        val message = engine.startServer()
        assertTrue(message.contains("provider settings could not be preserved safely"))
        assertFalse(marker.exists(), "a server started with unverified settings")
        assertTrue(engine.server.state.value is DshServer.Stopped)
        assertTrue(engine.busy.value == null, "failed preparation must clear the busy indicator")
    }

    @Test
    fun `failed diagnostic config dumps do not expose parser contents`() = runTest {
        assumeTrue(Files.isExecutable(java.nio.file.Path.of("/bin/sh")))
        dsh.writeText("#!/bin/sh\nprintf '%s\\n' 'Error: apiKey: private-credential-value' >&2\nexit 1\n")
        val (message, failed) = engine().dumpConfig("web", defaultsOnly = true)
        assertTrue(failed)
        assertTrue(message.contains("profile configuration"))
        assertFalse(message.contains("private-credential-value"))
    }
}
