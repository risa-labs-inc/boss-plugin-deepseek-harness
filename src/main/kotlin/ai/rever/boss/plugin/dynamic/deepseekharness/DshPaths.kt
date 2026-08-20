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
