package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Harness-home resolution.
 *
 * The precedence has to match `@deepseek-ai/dsh-home-paths` exactly, because the
 * consequence of getting it wrong is a panel that confidently describes a
 * directory the harness never reads — profiles listed as missing that exist,
 * sessions reported as none.
 */
class DshPathsTest {

    private val userHome = System.getProperty("user.home")

    @Test
    fun `DSH_HOME wins over the default`() {
        val home = DshPaths.home(mapOf(DshPaths.HOME_ENV to "/tmp/custom-dsh"))
        assertEquals(File("/tmp/custom-dsh").absolutePath, home.absolutePath)
    }

    @Test
    fun `the default is a dot-dsh directory under the user home`() {
        assertEquals(File(userHome, ".dsh").absolutePath, DshPaths.home(emptyMap()).absolutePath)
    }

    @Test
    fun `a blank DSH_HOME falls back rather than resolving to the filesystem root`() {
        // `DSH_HOME=` exported but empty is common in shell profiles and CI. Taking
        // it literally would put the harness home at "" — which resolves to the
        // process working directory, so the panel would describe whatever project
        // happened to be open.
        assertEquals(File(userHome, ".dsh").absolutePath, DshPaths.home(mapOf(DshPaths.HOME_ENV to "")).absolutePath)
        assertEquals(File(userHome, ".dsh").absolutePath, DshPaths.home(mapOf(DshPaths.HOME_ENV to "   ")).absolutePath)
    }

    @Test
    fun `a tilde in DSH_HOME is expanded the way the harness expands it`() {
        assertEquals(
            File(userHome, "harness").absolutePath,
            DshPaths.home(mapOf(DshPaths.HOME_ENV to "~/harness")).absolutePath,
        )
        assertEquals(userHome, DshPaths.home(mapOf(DshPaths.HOME_ENV to "~")).absolutePath)
    }

    @Test
    fun `a tilde-user form is left alone rather than guessed at`() {
        // The harness expands only `~`, `~/` and `~\`. Guessing at `~someone`
        // would silently point somewhere the harness does not.
        assertEquals("~someone/dsh", DshPaths.expandTilde("~someone/dsh"))
    }

    @Test
    fun `the derived directories hang off the resolved home`() {
        val env = mapOf(
            DshPaths.HOME_ENV to "/tmp/custom-dsh",
            DshPaths.BOSS_ROOT_ENV to "/tmp/custom-boss",
        )
        assertEquals("/tmp/custom-dsh/profiles", DshPaths.profilesDir(env).absolutePath)
        assertEquals("/tmp/custom-dsh/profiles/web", DshPaths.profileDir("web", env).absolutePath)
        assertEquals("/tmp/custom-dsh/sessions", DshPaths.sessionsDir(env).absolutePath)
    }

    @Test
    fun `the plugin's overlay directory is not one the harness writes`() {
        val env = mapOf(
            DshPaths.HOME_ENV to "/tmp/custom-dsh",
            DshPaths.BOSS_ROOT_ENV to "/tmp/custom-boss",
        )
        val overlays = DshPaths.overlayDir(env).absolutePath
        assertTrue(File(overlays).toPath().startsWith(File("/tmp/custom-boss").canonicalFile.toPath()))
        assertFalse(overlays.endsWith("/profiles"))
        assertFalse(overlays.endsWith("/sessions"))
    }

    @Test
    fun `plugin-owned data is contained beneath boss`() {
        val env = mapOf(DshPaths.BOSS_ROOT_ENV to File(userHome, ".boss").absolutePath)
        val root = DshPaths.bossDataRoot(env)

        assertTrue(root.toPath().startsWith(File(userHome, ".boss").toPath()))
        assertTrue(DshPaths.overlayDir(env).toPath().startsWith(root.toPath()))
        assertTrue(DshPaths.toolchainDir(env).toPath().startsWith(root.toPath()))
    }

    @Test
    fun `legacy plugin-owned directories move without touching harness data`() {
        val oldUserHome = System.getProperty("user.home")
        val fakeHome = kotlin.io.path.createTempDirectory("dsh-migration-home").toFile()
        try {
            System.setProperty("user.home", fakeHome.absolutePath)
            val harnessHome = File(fakeHome, "external-dsh").apply { mkdirs() }
            val env = mapOf(
                DshPaths.HOME_ENV to harnessHome.absolutePath,
                DshPaths.BOSS_ROOT_ENV to File(fakeHome, ".boss").absolutePath,
            )
            File(harnessHome, "boss-toolchain/bin").apply { mkdirs() }
            File(harnessHome, "boss-toolchain/bin/dsh").writeText("binary")
            File(harnessHome, "sessions").mkdirs()

            DshPaths.migrateLegacyBossData(env)

            assertTrue(File(DshPaths.toolchainDir(env), "bin/dsh").isFile)
            assertFalse(File(harnessHome, "boss-toolchain").exists())
            assertTrue(File(harnessHome, "sessions").isDirectory)
        } finally {
            System.setProperty("user.home", oldUserHome)
        }
    }

    @Test
    fun `web and headless are the profiles the harness self-initializes`() {
        // Anything else fails boot loud with a hint to install a bundle, so the
        // panel must not promise it will appear on its own.
        assertTrue(DshPaths.isShippedProfile("web"))
        assertTrue(DshPaths.isShippedProfile("headless"))
        assertFalse(DshPaths.isShippedProfile("tui"))
        assertFalse(DshPaths.isShippedProfile("anything-custom"))
    }

    @Test
    fun `resolution creates nothing on disk`() {
        val target = File(System.getProperty("java.io.tmpdir"), "dsh-paths-should-not-exist-${System.nanoTime()}")
        val env = mapOf(DshPaths.HOME_ENV to target.absolutePath)

        DshPaths.home(env)
        DshPaths.profilesDir(env)
        DshPaths.profileDir("web", env)
        DshPaths.sessionsDir(env)
        DshPaths.overlayDir(env)

        // Pre-creating a profile directory turns the harness's loud "run
        // dsh plugin add" into a confusing half-initialized state.
        assertFalse(target.exists(), "path resolution must be pure")
    }
}
