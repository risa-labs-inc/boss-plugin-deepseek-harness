package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Where DeepSeek Harness keeps its user data.
 *
 * Precedence matches the harness itself (`@deepseek-ai/dsh-home-paths`): an
 * explicit `$DSH_HOME` wins, otherwise `~/.dsh`. The harness resolves this from
 * the environment of the process that launched it, so a plugin that wants to
 * *read* the same tree has to apply the same rule rather than assuming the
 * default — a user with `DSH_HOME` exported in their shell profile would
 * otherwise have the panel describe a directory the harness never touches.
 *
 * Nothing here creates a directory. The harness auto-initializes the `web` and
 * `headless` profiles from shipped templates on first use; any other missing
 * profile is a loud failure there, and pre-creating one here would turn that
 * into a confusing half-state.
 */
object DshPaths {

    private val logger = Logger.getLogger(DshPaths::class.java.name)

    /** Environment variable the harness honours as its home override. */
    const val HOME_ENV = "DSH_HOME"

    /** Host-provided BOSS root; defaults to ~/.boss on current hosts. */
    const val BOSS_ROOT_ENV = "BOSS_HOME"

    /** Profiles the harness ships templates for and initializes on first use. */
    val SHIPPED_PROFILES = listOf("web", "headless")

    /**
     * The resolved harness home.
     *
     * [env] is the environment to read, injected so tests need not mutate the
     * process environment (which Java cannot do portably anyway).
     */
    fun home(env: Map<String, String> = System.getenv()): File {
        val configured = env[HOME_ENV]?.trim()
        if (!configured.isNullOrEmpty()) return File(expandTilde(configured))
        return File(userHome(), ".dsh")
    }

    /** `$DSH_HOME/profiles` — one directory per profile, each pnpm-managed. */
    fun profilesDir(env: Map<String, String> = System.getenv()): File = File(home(env), "profiles")

    /** `$DSH_HOME/profiles/<name>`. */
    fun profileDir(name: String, env: Map<String, String> = System.getenv()): File =
        File(profilesDir(env), name)

    /** `$DSH_HOME/sessions` — the durable session logs. */
    fun sessionsDir(env: Map<String, String> = System.getenv()): File = File(home(env), "sessions")

    /**
     * Where this plugin keeps the overlay files it owns.
     *
     * Deliberately under the harness home but in a directory the harness itself
     * never writes, so the plugin can rewrite its own overlays freely and can
     * never be accused of having clobbered `$DSH_HOME/cordis.patch.yml` or a
     * profile's own layer. Those two belong to the user.
     */
    fun overlayDir(env: Map<String, String> = System.getenv()): File = File(bossDataRoot(env), "overlays")

    /**
     * The npm prefix this plugin installs the harness into.
     *
     * Not `npm install -g`, which was the first implementation and is wrong three
     * ways. It writes into whichever Node's global prefix happens to be selected,
     * so the harness silently disappears when the user switches Node version. It
     * needs a writable global prefix, which a Homebrew or system Node may not
     * give without sudo. And it leaves `dsh` behind when the plugin is
     * uninstalled, because nothing here can safely remove a binary that the user
     * might also have installed for themselves.
     *
     * A prefix the plugin owns has none of those problems: `npm install -g
     * --prefix <this>` lays out `bin/dsh` and `lib/node_modules`, the tree
     * belongs to one plugin, and removing the directory removes the install.
     *
     * BOSS owns this install, so it lives under BOSS's durable-data root rather
     * than the external harness's `$DSH_HOME`.
     */
    fun toolchainDir(env: Map<String, String> = System.getenv()): File = File(bossDataRoot(env), "toolchain")

    /**
     * `<toolchain>/bin` — where npm links the executable on Unix.
     *
     * Windows is the exception and is why [toolchainExecDirs] exists rather than
     * every caller using this one: `npm install -g --prefix C:\dir` shims to
     * `C:\dir\dsh.cmd`, in the prefix *root*, with no `bin` directory at all.
     */
    fun toolchainBin(env: Map<String, String> = System.getenv()): File = File(toolchainDir(env), "bin")

    /** The old BOSS-managed prefix. Kept as a read fallback until migration succeeds. */
    internal fun legacyToolchainDir(env: Map<String, String> = System.getenv()): File =
        File(home(env), "boss-toolchain")

    /** The old BOSS-managed overlay directory. Never used for new writes. */
    internal fun legacyOverlayDir(env: Map<String, String> = System.getenv()): File =
        File(home(env), "boss-overlays")

    /**
     * Every directory the prefix might have put an executable in, most likely
     * first. Both are handed to child processes: getting it wrong on one platform
     * would mean the plugin installs the harness and then cannot find it.
     */
    fun toolchainExecDirs(env: Map<String, String> = System.getenv()): List<File> =
        listOf(
            toolchainBin(env),
            toolchainDir(env),
            File(legacyToolchainDir(env), "bin"),
            legacyToolchainDir(env),
        ).distinctBy { it.absoluteFile.normalize().path }

    /**
     * The `dsh` this plugin installed, or null when it is not there.
     *
     * Both layouts and both spellings: npm writes a `.cmd` shim on Windows and an
     * extensionless shell script elsewhere, in different directories.
     */
    fun installedDsh(env: Map<String, String> = System.getenv()): File? =
        toolchainExecDirs(env)
            .flatMap { dir -> listOf("dsh.cmd", "dsh").map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }

    /**
     * A profile that the harness initializes on its own.
     *
     * The distinction matters for messaging: a missing `web` directory is
     * normal and self-healing, while a missing custom profile needs
     * `dsh plugin --profile <name> add <package>` and will otherwise fail boot.
     */
    fun isShippedProfile(name: String): Boolean = name in SHIPPED_PROFILES

    /** Durable files owned by this BOSS plugin, never by the external harness. */
    internal fun bossDataRoot(env: Map<String, String> = System.getenv()): File {
        val configuredRoot = env[BOSS_ROOT_ENV]?.trim().orEmpty()
        val bossRoot = (if (configuredRoot.isNotEmpty()) File(expandTilde(configuredRoot)) else File(userHome(), ".boss"))
            .absoluteFile
            .normalize()
            .toPath()
        val pluginRoot = bossRoot.resolve("plugin-data/ai.rever.boss.plugin.dynamic.deepseekharness").normalize()
        require(pluginRoot.startsWith(bossRoot)) { "DeepSeek plugin data escaped the BOSS root" }
        return pluginRoot.toFile()
    }

    /**
     * Copy plugin-owned legacy directories out of `$DSH_HOME`, then publish the
     * completed copy with a same-filesystem rename. The source remains as a
     * rollback and read fallback. Harness-owned profiles, settings, credentials
     * and sessions remain untouched.
     *
     * Every failure is contained here. Migration runs during plugin activation,
     * so a bad mount, permission error or unsupported link must never prevent the
     * rest of the plugin from registering.
     */
    internal fun migrateLegacyBossData(
        env: Map<String, String> = System.getenv(),
        copyTree: (Path, Path) -> Unit = ::copyTree,
    ) {
        runCatching {
            val migrations = listOf(
                legacyToolchainDir(env).toPath() to toolchainDir(env).toPath(),
                legacyOverlayDir(env).toPath() to overlayDir(env).toPath(),
            )
            migrations.forEach { (legacy, destination) ->
                migrateOne(legacy, destination, copyTree)
            }
        }.onFailure { failure ->
            logger.log(Level.WARNING, "Could not prepare the DeepSeek data migration; continuing with legacy data", failure)
        }
    }

    private fun migrateOne(legacy: Path, destination: Path, copyTree: (Path, Path) -> Unit) {
        if (!Files.isDirectory(legacy) || Files.isSymbolicLink(legacy) || Files.exists(destination)) return
        runCatching {
            Files.createDirectories(destination.parent)
            val temporary = Files.createTempDirectory(destination.parent, ".${destination.fileName}-import-")
            try {
                copyTree(legacy, temporary)
                // No REPLACE_EXISTING: a concurrent install or overlay write wins.
                Files.move(temporary, destination)
            } finally {
                deleteTree(temporary)
            }
        }.onFailure { failure ->
            if (failure !is FileAlreadyExistsException) {
                logger.log(Level.WARNING, "Could not import legacy DeepSeek data; continuing with the legacy read fallback", failure)
            }
        }
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                if (dir != source) Files.createDirectory(target.resolve(source.relativize(dir)))
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                val copied = target.resolve(source.relativize(file))
                if (attrs.isSymbolicLink) {
                    Files.copy(file, copied, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                } else {
                    Files.copy(file, copied, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }
    }

    private fun userHome(): String = System.getProperty("user.home").orEmpty()

    /**
     * Expand a leading `~`, matching the harness's own `expandHomePath`.
     *
     * Only the documented prefixes: bare `~`, `~/`, and the Windows `~\`. A
     * `~user` form is left alone rather than guessed at, which is also what the
     * harness does.
     */
    internal fun expandTilde(path: String): String = when {
        path == "~" -> userHome()
        path.startsWith("~/") || path.startsWith("~\\") -> File(userHome(), path.substring(2)).path
        else -> path
    }
}
