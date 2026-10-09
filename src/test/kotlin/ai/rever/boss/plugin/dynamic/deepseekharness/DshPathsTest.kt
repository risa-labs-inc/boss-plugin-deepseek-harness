package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import java.io.IOException
import java.nio.file.Files
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
        assertTrue(File(overlays).toPath().startsWith(File("/tmp/custom-boss").absoluteFile.normalize().toPath()))
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
    fun `legacy plugin-owned directories copy without touching harness data`() {
        val oldUserHome = System.getProperty("user.home")
        val fakeHome = kotlin.io.path.createTempDirectory("dsh-migration-home").toFile()
        try {
            System.setProperty("user.home", fakeHome.absolutePath)
            val harnessHome = File(fakeHome, "external-dsh").apply { mkdirs() }
            val env = mapOf(
                DshPaths.HOME_ENV to harnessHome.absolutePath,
                DshPaths.BOSS_ROOT_ENV to File(fakeHome, ".boss").absolutePath,
            )
            File(harnessHome, "boss-toolchain/bin").mkdirs()
            File(harnessHome, "boss-toolchain/bin/dsh").apply {
                writeText("binary")
                setExecutable(true)
            }
            File(harnessHome, "sessions").mkdirs()

            DshPaths.migrateLegacyBossData(env)

            assertTrue(File(DshPaths.toolchainDir(env), "bin/dsh").isFile)
            assertTrue(File(DshPaths.toolchainDir(env), "bin/dsh").canExecute())
            assertTrue(File(harnessHome, "boss-toolchain/bin/dsh").isFile, "legacy rollback copy was removed")
            assertTrue(File(harnessHome, "sessions").isDirectory)
        } finally {
            System.setProperty("user.home", oldUserHome)
        }
    }

    @Test
    fun `failed migration keeps legacy toolchain readable and never escapes startup`() {
        val root = kotlin.io.path.createTempDirectory("dsh-migration-failure").toFile()
        try {
            val harnessHome = File(root, "dsh").apply { mkdirs() }
            val bossHome = File(root, ".boss")
            val legacyDsh = File(harnessHome, "boss-toolchain/bin/dsh").apply {
                parentFile.mkdirs()
                writeText("binary")
                setExecutable(true)
            }
            val env = mapOf(
                DshPaths.HOME_ENV to harnessHome.absolutePath,
                DshPaths.BOSS_ROOT_ENV to bossHome.absolutePath,
            )

            DshPaths.migrateLegacyBossData(env) { _, _ -> throw IOException("different filesystem") }

            assertEquals(legacyDsh.canonicalFile, DshPaths.installedDsh(env)?.canonicalFile)
            assertFalse(DshPaths.toolchainDir(env).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent destination wins without overwriting either copy`() {
        val root = kotlin.io.path.createTempDirectory("dsh-migration-race").toFile()
        try {
            val harnessHome = File(root, "dsh").apply { mkdirs() }
            val legacyDsh = File(harnessHome, "boss-toolchain/bin/dsh").apply {
                parentFile.mkdirs()
                writeText("legacy")
            }
            val env = mapOf(
                DshPaths.HOME_ENV to harnessHome.absolutePath,
                DshPaths.BOSS_ROOT_ENV to File(root, ".boss").absolutePath,
            )
            val currentDsh = File(DshPaths.toolchainDir(env), "bin/dsh")

            DshPaths.migrateLegacyBossData(env) { source, temporary ->
                Files.walk(source).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.forEach { file ->
                        val copied = temporary.resolve(source.relativize(file))
                        Files.createDirectories(copied.parent)
                        Files.copy(file, copied)
                    }
                }
                currentDsh.parentFile.mkdirs()
                currentDsh.writeText("current")
            }

            assertEquals("current", currentDsh.readText())
            assertEquals("legacy", legacyDsh.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stale pid reads legacy fallback but current data wins`() {
        val root = kotlin.io.path.createTempDirectory("dsh-pid-fallback").toFile()
        try {
            val env = mapOf(
                DshPaths.HOME_ENV to File(root, "dsh").absolutePath,
                DshPaths.BOSS_ROOT_ENV to File(root, ".boss").absolutePath,
            )
            val legacyPid = File(DshPaths.legacyOverlayDir(env), "web-server.pid").apply {
                parentFile.mkdirs()
                writeText("41")
            }
            val server = DshWebServer(env)

            assertEquals(legacyPid.absoluteFile, server.stalePidFile()?.absoluteFile)

            val currentPid = File(DshPaths.overlayDir(env), "web-server.pid").apply {
                parentFile.mkdirs()
                writeText("42")
            }
            assertEquals(currentPid.absoluteFile, server.stalePidFile()?.absoluteFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `completed migration preserves symlinks and leaves the source as rollback`() {
        val root = kotlin.io.path.createTempDirectory("dsh-migration-link").toFile()
        try {
            val harnessHome = File(root, "dsh").apply { mkdirs() }
            val legacy = File(harnessHome, "boss-toolchain")
            val executable = File(legacy, "lib/dsh.js").apply {
                parentFile.mkdirs()
                writeText("binary")
            }
            val link = File(legacy, "bin/dsh").toPath()
            Files.createDirectories(link.parent)
            Files.createSymbolicLink(link, link.parent.relativize(executable.toPath()))
            val env = mapOf(
                DshPaths.HOME_ENV to harnessHome.absolutePath,
                DshPaths.BOSS_ROOT_ENV to File(root, ".boss").absolutePath,
            )

            DshPaths.migrateLegacyBossData(env)

            val migratedLink = File(DshPaths.toolchainDir(env), "bin/dsh").toPath()
            assertTrue(Files.isSymbolicLink(migratedLink))
            assertTrue(Files.exists(migratedLink))
            assertTrue(Files.exists(link), "legacy rollback link was removed")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `symlinked boss root resolves lexically without touching the target`() {
        val root = kotlin.io.path.createTempDirectory("dsh-boss-link").toFile()
        try {
            val target = File(root, "durable").apply { mkdirs() }
            val link = File(root, "boss-link").toPath()
            Files.createSymbolicLink(link, target.toPath())
            val resolved = DshPaths.bossDataRoot(mapOf(DshPaths.BOSS_ROOT_ENV to link.toString()))

            assertEquals(
                link.resolve("plugin-data/ai.rever.boss.plugin.dynamic.deepseekharness").normalize(),
                resolved.toPath(),
            )
            assertFalse(File(target, "plugin-data").exists(), "path resolution created data")
        } finally {
            root.deleteRecursively()
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
