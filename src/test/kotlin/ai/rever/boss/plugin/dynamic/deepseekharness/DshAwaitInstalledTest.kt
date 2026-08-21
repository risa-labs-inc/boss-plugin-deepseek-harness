package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Picking the install up on its own.
 *
 * The install runs in a terminal tab, so nothing calls back when it finishes.
 * The panel showed "not installed" until the user pressed Refresh — an Install
 * button whose effect only appears when you press a *different* button reads as
 * a button that did not work.
 *
 * These drive the watch against a real directory, because the thing being
 * asserted is a filesystem observation and a fake filesystem would assert only
 * that the fake works.
 */
class DshAwaitInstalledTest {

    private val home = File.createTempFile("dsh-await", "").let { it.delete(); it.mkdirs(); it }
    private val env = mapOf(DshPaths.HOME_ENV to home.absolutePath)
    private fun engine() = DshEngine(FakeServices.context(), env)

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
    }

    /** Lay out a prefix the way `npm install -g --prefix` does on this platform. */
    private fun installFakeDsh(): File {
        val bin = DshPaths.toolchainBin(env).apply { mkdirs() }
        return File(bin, "dsh").apply {
            writeText("#!/bin/sh\necho 0.1.0-rc.7\n")
            setExecutable(true)
        }
    }

    @Test
    fun `gives up rather than watching forever when nothing installs`() = runTest {
        val engine = engine()

        val landed = engine.awaitInstalled(timeoutMs = 150, intervalMs = 20)

        assertFalse(landed, "nothing was installed, so it must not claim success")
    }

    /**
     * The binary appearing is not the same as the binary working. npm links
     * `bin/` before postinstall scripts finish, so the watch must keep going
     * rather than latch on the first sight of the file.
     */
    @Test
    fun `does not report success for a binary that will not run`() = runTest {
        val engine = engine()
        val dsh = DshPaths.toolchainBin(env).apply { mkdirs() }.let { File(it, "dsh") }
        dsh.writeText("#!/bin/sh\nexit 1\n")
        dsh.setExecutable(true)

        val landed = engine.awaitInstalled(timeoutMs = 300, intervalMs = 20)

        assertFalse(landed, "a dsh that exits non-zero is not a finished install")
    }

    /** Nothing here may create the prefix — that is the harness's business. */
    @Test
    fun `watching does not create the directory it is watching`() = runTest {
        val engine = engine()

        engine.awaitInstalled(timeoutMs = 100, intervalMs = 20)

        assertFalse(DshPaths.toolchainDir(env).exists(), "the watch created the prefix")
    }

    /** The busy label is cleared on every exit, including the timeout one. */
    @Test
    fun `clears the busy label when it gives up`() = runTest {
        val engine = engine()

        engine.awaitInstalled(timeoutMs = 100, intervalMs = 20)

        assertNull(engine.busy.value, "a stuck busy label leaves the panel spinning forever")
    }

    /**
     * The happy path, against a real executable. Skipped on Windows, where a
     * `#!/bin/sh` script is not runnable and the shim would be a `.cmd` — the
     * layout difference itself is covered by DshToolchainTest.
     */
    @Test
    fun `reports success once a working dsh appears`() = runTest {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runTest
        val engine = engine()
        installFakeDsh()

        val landed = engine.awaitInstalled(timeoutMs = 10_000, intervalMs = 20)

        assertTrue(landed, "a runnable dsh in the prefix is a finished install")
        assertTrue(engine.install.value.ready, "and the panel state must say so without a Refresh")
        assertNull(engine.busy.value)
    }
}
