package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The private npm prefix, and the command that installs into it.
 *
 * Replaces `npm install -g @deepseek-ai/dsh@latest`, which was wrong in three
 * ways at once: it wrote into whichever Node's global prefix was selected (so
 * the harness vanished when the user switched Node), it needed that prefix to be
 * writable without sudo, and `@latest` meant the harness a user got had no fixed
 * relationship to the plugin version that was tested against it.
 */
class DshToolchainTest {

    private val env = mapOf(DshPaths.HOME_ENV to "/tmp/dsh-home-under-test")
    private fun engine() = DshEngine(FakeServices.context(), env)

    // ------------------------------------------------------------------ paths

    @Test
    fun `the prefix sits under the harness home so DSH_HOME moves everything`() {
        assertEquals(
            File("/tmp/dsh-home-under-test/boss-toolchain"),
            DshPaths.toolchainDir(env),
        )
        assertEquals(
            File("/tmp/dsh-home-under-test/boss-toolchain/bin"),
            DshPaths.toolchainBin(env),
        )
    }

    /**
     * Named for BOSS, and distinct from every directory the harness writes. The
     * plugin must never be able to clobber `profiles/`, `sessions/`,
     * `settings.yaml` or `.credentials.yaml` — those belong to the user.
     */
    @Test
    fun `the prefix collides with nothing the harness owns`() {
        val toolchain = DshPaths.toolchainDir(env)
        val harnessOwned = listOf(
            DshPaths.profilesDir(env),
            DshPaths.sessionsDir(env),
            DshPaths.home(env),
        )
        assertFalse(toolchain in harnessOwned)
        assertTrue(toolchain.name.startsWith("boss-"), "must read as BOSS's, not the harness's")
        assertTrue(toolchain != DshPaths.overlayDir(env), "and not the overlay directory either")
    }

    @Test
    fun `no installed dsh when the prefix does not exist`() {
        assertNull(DshPaths.installedDsh(mapOf(DshPaths.HOME_ENV to "/tmp/definitely-not-here-$this")))
    }

    /**
     * Both npm layouts. `npm install -g --prefix <dir>` links `<dir>/bin/dsh` on
     * Unix but shims `<dir>\dsh.cmd` in the prefix *root* on Windows, with no
     * `bin` directory at all — so a plugin that only looked in `bin` would
     * install the harness on Windows and then report it missing.
     */
    @Test
    fun `looks in both the bin directory and the prefix root`() {
        val dirs = DshPaths.toolchainExecDirs(env)

        assertTrue(DshPaths.toolchainBin(env) in dirs, "the Unix layout")
        assertTrue(DshPaths.toolchainDir(env) in dirs, "the Windows layout")
        assertEquals(DshPaths.toolchainBin(env), dirs.first(), "bin is the likelier of the two")
    }

    /** Found for real, from a prefix laid out the way npm lays one out. */
    @Test
    fun `finds a dsh that is actually on disk`() {
        val home = File.createTempFile("dsh-home", "").let { it.delete(); it }
        try {
            val bin = File(home, "boss-toolchain/bin").apply { mkdirs() }
            val dsh = File(bin, "dsh").apply { writeText("#!/bin/sh\n"); setExecutable(true) }

            val found = DshPaths.installedDsh(mapOf(DshPaths.HOME_ENV to home.absolutePath))

            assertEquals(dsh.canonicalFile, found?.canonicalFile)
        } finally {
            home.deleteRecursively()
        }
    }

    // -------------------------------------------------------- install command

    @Test
    fun `installs into the plugin's own prefix rather than globally`() {
        val command = engine().installCommand()

        assertTrue(
            command.contains("""--prefix "${DshPaths.toolchainDir(env).absolutePath}""""),
            "not scoped to the plugin prefix: $command",
        )
    }

    /**
     * `-g` is still correct *with* `--prefix`: it is what produces `bin/dsh` plus
     * `lib/node_modules`, rather than a project-style `node_modules/.bin`. The
     * flag that matters is `--prefix`, which is what stops it being global.
     */
    @Test
    fun `uses the global layout inside that prefix`() {
        val command = engine().installCommand()

        assertTrue(command.contains("npm install -g "), "expected the -g layout: $command")
    }

    @Test
    fun `installs the pinned version and never latest`() {
        val command = engine().installCommand()

        assertTrue(command.contains(DshCli.PINNED_SPEC), "does not install the pin: $command")
        assertFalse(command.contains("@latest"), "still floating on latest: $command")
    }

    /**
     * The pin has to be a real, resolvable version — a typo here is a plugin that
     * cannot install at all, and the failure would only show up on a user's
     * machine. The CI bump workflow installs it for real; this just catches the
     * shape.
     */
    @Test
    fun `the pin is a concrete version, not a range`() {
        assertTrue(DshCli.PINNED_VERSION.isNotBlank())
        assertFalse(DshCli.PINNED_VERSION.startsWith("^"), "a caret is a range, not a pin")
        assertFalse(DshCli.PINNED_VERSION.startsWith("~"), "a tilde is a range, not a pin")
        assertFalse(DshCli.PINNED_VERSION.contains("latest"))
        assertTrue(
            Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""").matches(DshCli.PINNED_VERSION),
            "not a semver: ${DshCli.PINNED_VERSION}",
        )
        assertEquals("${DshCli.PACKAGE}@${DshCli.PINNED_VERSION}", DshCli.PINNED_SPEC)
    }

    /**
     * With no resolved Node the command still has to be runnable — the panel
     * falls back to copying it out, and a user pasting it into their own shell
     * should get something that works there.
     */
    @Test
    fun `is a runnable command even before a Node has been resolved`() {
        val command = engine().installCommand()

        assertTrue(command.startsWith("npm install -g ") || command.startsWith("""PATH="""))
        assertFalse(command.contains("null"), "a null leaked into the command: $command")
    }
}
