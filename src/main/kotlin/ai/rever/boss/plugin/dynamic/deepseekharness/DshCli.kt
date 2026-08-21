package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Outcome of one child-process invocation.
 *
 * [exitCode] carries two synthetic negatives so a caller can tell "the binary
 * isn't there" apart from "the binary ran and said no". Collapsing those into a
 * generic failure is what makes a plugin tell a user their task failed when in
 * fact nothing was ever installed.
 */
data class DshExec(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0

    /** True when the executable could not be located at all. */
    val missing: Boolean get() = exitCode == EXIT_CLI_MISSING

    /** True when the child outlived its budget and was destroyed. */
    val timedOut: Boolean get() = exitCode == EXIT_TIMEOUT

    /**
     * The most useful failure text. stderr first, because that is where both
     * node and the harness put diagnostics; stdout is the answer channel.
     */
    val message: String get() = stderr.ifBlank { stdout }.trim()

    companion object {
        const val EXIT_CLI_MISSING = -1
        const val EXIT_TIMEOUT = -2

        fun missing(what: String) = DshExec(EXIT_CLI_MISSING, "", "$what was not found on this machine")
    }
}

/**
 * The single place this plugin starts a process.
 *
 * Two rules hold throughout, both learned the hard way elsewhere in this
 * workspace:
 *
 * 1. **Absolute binary, and widen the child's PATH.** `ProcessBuilder` resolves
 *    a bare command name against the *parent* process's PATH, which is nearly
 *    empty when the packaged host is launched from Finder rather than a shell.
 *    Node installs under `/opt/homebrew/bin` or `/usr/local/bin` and global npm
 *    binaries land somewhere else again, so a bare `"dsh"` works in dev and
 *    fails in the shipped app. Every spawn here uses a resolved absolute path
 *    and hands the child a PATH that still contains the usual directories, since
 *    `dsh` itself shells out to `node` and (for bundle management) `pnpm`.
 *
 * 2. **argv lists, never a shell string.** Task text comes from a model or a
 *    text field; profile names and paths come from disk. Passing a
 *    `List<String>` means there is no shell to inject into, so a task containing
 *    `; rm -rf ~` is one argument rather than two commands.
 */
object DshCli {

    /** The npm package that provides the `dsh` binary. */
    const val PACKAGE = "@deepseek-ai/dsh"

    /**
     * The harness release this plugin installs and is tested against.
     *
     * Pinned, not `@latest`. Every fact in AGENTS.md under "Verified facts about
     * the harness" was probed against a specific release — the stdout shape of
     * `dsh web`, the `MISSING_CREDENTIAL` text, which profiles self-initialize,
     * the patch-layer YAML form, the set of provider routes pi-ai actually ships.
     * With `@latest` those facts describe whatever npm served the day a given
     * user clicked Install, so two people running "the same plugin version" can
     * have different harnesses and only one of them matches the code.
     *
     * Bumped by `.github/workflows/harness-bump.yml`, which installs the
     * candidate and runs it before opening the PR, so moving forward stays a
     * decision with evidence rather than a side effect of the clock.
     */
    const val PINNED_VERSION = "0.1.0-rc.7"

    /** What to hand npm: the pinned package spec. */
    const val PINNED_SPEC = "$PACKAGE@$PINNED_VERSION"

    /**
     * Directories searched beyond PATH, and prepended to the child's PATH.
     *
     * Ordered most-specific first. The npm/volta/asdf shim directories come
     * before the system ones because a user who installed a toolchain manager
     * means for it to win.
     */
    private val extraDirs: List<String> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        listOfNotNull(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            home.takeIf { it.isNotBlank() }?.let { "$it/.volta/bin" },
            home.takeIf { it.isNotBlank() }?.let { "$it/.npm-global/bin" },
            home.takeIf { it.isNotBlank() }?.let { "$it/.local/bin" },
            home.takeIf { it.isNotBlank() }?.let { "$it/.asdf/shims" },
            "/usr/bin",
            "/bin",
        )
    }

    /**
     * Absolute path to [name], or null when it is nowhere to be found.
     *
     * Not memoised: the panel offers an install action, and a cached "absent"
     * would survive the install that fixed it. Resolution is a handful of
     * `File.canExecute()` calls, so the cost of asking again is not worth a
     * cache-invalidation bug.
     */
    fun which(name: String): File? = whichAll(name).firstOrNull()

    /**
     * Every [name] we can find, most-preferred first, deduplicated.
     *
     * [which] answers "where is it", which is the wrong question for a binary
     * with a minimum version — see [DshNodeResolver]. Deduplicated by canonical
     * path because the same Node is routinely reachable twice (a shim directory
     * and the real install, or a symlinked `/usr/local/bin`), and probing it
     * twice would just be slower.
     */
    fun whichAll(name: String): List<File> {
        val fromPath = System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
        val seen = LinkedHashMap<String, File>()
        for (dir in preferredDirs + fromPath + extraDirs) {
            val candidate = File(dir, name)
            if (!candidate.isFile || !candidate.canExecute()) continue
            val key = runCatching { candidate.canonicalPath }.getOrDefault(candidate.absolutePath)
            seen.putIfAbsent(key, candidate)
        }
        return seen.values.toList()
    }

    /**
     * Directories to put ahead of everything else, set once the engine knows
     * which Node it settled on and where the plugin installed the harness.
     *
     * This exists because the *decision* is made in one place (a suspending
     * resolve that probes each candidate) and *used* in several that cannot make
     * it themselves — `DshWebServer` spawns `dsh web` long after, on a thread
     * with no access to the engine. Threading a parameter through every spawn
     * would have the same effect with more places to forget.
     *
     * Empty until the first `refreshInstall`, which degrades to exactly the old
     * behaviour rather than to something new and untested.
     */
    @Volatile
    private var preferredDirs: List<String> = emptyList()

    /** Record the resolved toolchain. Later spawns lead their PATH with it. */
    fun preferDirs(dirs: List<File>) {
        preferredDirs = dirs.map { it.absolutePath }.distinct()
    }

    /**
     * PATH handed to children: the resolved toolchain, our search directories,
     * then whatever we inherited.
     *
     * Public and shared because `DshWebServer` had grown a second copy of this
     * with a *different* directory list, so a spawn through one path and a spawn
     * through the other could disagree about which Node ran the harness — which
     * is precisely the class of bug this whole change is about.
     */
    fun childPath(): String {
        val inherited = System.getenv("PATH").orEmpty()
        val prefix = (preferredDirs + extraDirs).distinct().joinToString(File.pathSeparator)
        return if (inherited.isBlank()) prefix else "$prefix${File.pathSeparator}$inherited"
    }

    /**
     * Run [argv] to completion and capture both streams.
     *
     * The blocking wait sits inside [Dispatchers.IO] because an `McpToolHandler`
     * must be cancellation-cooperative: the host wraps every tool call in
     * `withTimeout`, which can only interrupt a handler that reaches a
     * suspension point. A bare `waitFor()` on the calling coroutine would keep
     * running past the host's timeout with nobody left waiting for it.
     *
     * [extraEnv] entries are added to the inherited environment. A null *value*
     * removes that variable from the child, which is how a stale key is kept out
     * rather than overwritten with an empty string the harness would still treat
     * as set.
     */
    suspend fun exec(
        argv: List<String>,
        cwd: File? = null,
        extraEnv: Map<String, String?> = emptyMap(),
        timeoutSeconds: Long = 120,
    ): DshExec = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(argv)
        if (cwd != null && cwd.isDirectory) builder.directory(cwd)
        builder.environment().let { env ->
            env["PATH"] = childPath()
            for ((key, value) in extraEnv) {
                if (value == null) env.remove(key) else env[key] = value
            }
        }

        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            return@withContext DshExec(DshExec.EXIT_CLI_MISSING, "", e.message.orEmpty())
        }

        // Read both pipes concurrently. Draining them in sequence deadlocks the
        // moment the child fills the pipe we are not reading, and `dsh` is
        // talkative on both.
        val out = StringBuilder()
        val err = StringBuilder()
        val outReader = Thread { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val errReader = Thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        outReader.isDaemon = true
        errReader.isDaemon = true
        outReader.start()
        errReader.start()

        try {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                DshProcesses.terminate(process)
                return@withContext DshExec(DshExec.EXIT_TIMEOUT, out.toString(), err.toString())
            }
            outReader.join(READER_DRAIN_MS)
            errReader.join(READER_DRAIN_MS)
            DshExec(process.exitValue(), out.toString(), err.toString())
        } catch (e: InterruptedException) {
            // Cancellation of the calling coroutine interrupts this thread. Take
            // the child with us rather than leaving a detached `dsh` holding a
            // port and spending tokens for a turn nobody is collecting.
            DshProcesses.terminate(process)
            throw e
        }
    }

    /** Grace period for the pipe readers to finish after the child exits. */
    private const val READER_DRAIN_MS = 2_000L
}
