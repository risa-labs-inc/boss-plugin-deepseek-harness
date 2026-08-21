package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File

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

    /** Environment variable the harness honours as its home override. */
    const val HOME_ENV = "DSH_HOME"

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
    fun overlayDir(env: Map<String, String> = System.getenv()): File = File(home(env), "boss-overlays")

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
     * Under the harness home rather than beside it so `$DSH_HOME` still moves
     * everything together, and named for BOSS so it cannot be confused with
     * anything the harness itself writes — same reasoning as [overlayDir].
     */
    fun toolchainDir(env: Map<String, String> = System.getenv()): File = File(home(env), "boss-toolchain")

    /**
     * `<toolchain>/bin` — where npm links the executable on Unix.
     *
     * Windows is the exception and is why [toolchainExecDirs] exists rather than
     * every caller using this one: `npm install -g --prefix C:\dir` shims to
     * `C:\dir\dsh.cmd`, in the prefix *root*, with no `bin` directory at all.
     */
    fun toolchainBin(env: Map<String, String> = System.getenv()): File = File(toolchainDir(env), "bin")

    /**
     * Every directory the prefix might have put an executable in, most likely
     * first. Both are handed to child processes: getting it wrong on one platform
     * would mean the plugin installs the harness and then cannot find it.
     */
    fun toolchainExecDirs(env: Map<String, String> = System.getenv()): List<File> =
        listOf(toolchainBin(env), toolchainDir(env))

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
