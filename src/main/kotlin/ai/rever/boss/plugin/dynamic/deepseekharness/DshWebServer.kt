package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Supervises one `dsh web` process.
 *
 * ## How the port is learned
 *
 * The harness accepts `--port 0` and lets the OS choose, then prints exactly one
 * line to stdout:
 *
 * ```
 * dsh web: http://127.0.0.1:62375
 * ```
 *
 * So the port is *read*, never guessed. The obvious alternative — bind a
 * `ServerSocket(0)`, close it, and pass the number along — has a race the OS can
 * lose between the close and the harness's bind, and the failure surfaces as a
 * bind error the user did nothing to cause. Reading the line has no race at all.
 * (Verified against `dsh 0.1.0-rc.7`; the harness's own webserver README states
 * the URL line belongs to the shell, which is what makes this a contract rather
 * than an implementation detail.)
 *
 * ## Teardown
 *
 * Every exit path funnels through [stop], which uses [DshProcesses.terminate]:
 * SIGTERM first (the harness drains and exits 0), descendants included, forcible
 * kill only if the grace period lapses. In addition:
 *
 * - a JVM shutdown hook is the backstop, because a *disabled* plugin never gets
 *   `dispose()` at all and would otherwise orphan the server;
 * - the pid is recorded so a later load can reap a server this one left behind,
 *   and reaping requires the recorded pid's command line to still look like ours,
 *   since pids are recycled.
 */
class DshWebServer(
    private val env: Map<String, String> = System.getenv(),
) {

    private val _state = MutableStateFlow<DshServer>(DshServer.Stopped)
    val state: StateFlow<DshServer> = _state.asStateFlow()

    /** Serialises start/stop so two panel clicks cannot race into two servers. */
    private val lifecycle = Mutex()

    private var process: Process? = null
    private var shutdownHook: Thread? = null

    /** Recorded so a later plugin load can reap a server this one left running. */
    private val pidFile: File get() = File(DshPaths.overlayDir(env), "web-server.pid")

    /**
     * Start the server, or return the running one's state unchanged.
     *
     * @param dsh absolute path to the `dsh` binary, already resolved.
     * @param cwd workspace root the harness treats as its default workspace.
     * @param extraEnv credential injection; see [DshCredentials.childEnv].
     * @param patchOverlay optional `--patch` overlay, e.g. the BOSS MCP bridge.
     */
    suspend fun start(
        dsh: File,
        cwd: File?,
        extraEnv: Map<String, String?>,
        patchOverlay: File?,
    ): DshServer = lifecycle.withLock {
        (state.value as? DshServer.Running)?.let { return it }

        reapStaleLocked()
        _state.value = DshServer.Starting

        val argv = buildList {
            add(dsh.absolutePath)
            add("--profile"); add("web")
            // The launcher's own flags must precede the app's, and --patch is the
            // launcher's. Placing it after `--no-open` would hand it to the web
            // app, which does not know the flag.
            if (patchOverlay != null) { add("--patch"); add(patchOverlay.absolutePath) }
            add("--no-open")
            // Let the OS pick; we read the chosen port off stdout below.
            add("--port"); add("0")
        }

        val started = runCatching { spawn(argv, cwd, extraEnv) }.getOrElse { e ->
            _state.value = DshServer.Failed(e.message ?: "could not start dsh")
            return _state.value
        }

        val outcome = awaitReady(started)
        _state.value = outcome
        if (outcome is DshServer.Running) recordPid(outcome.pid) else stopLocked()
        outcome
    }

    /** Stop the server. Idempotent, and safe when nothing is running. */
    suspend fun stop() = lifecycle.withLock { stopLocked() }

    private fun stopLocked() {
        process?.let { DshProcesses.terminate(it) }
        process = null
        shutdownHook?.let { hook -> runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
        shutdownHook = null
        runCatching { pidFile.delete() }
        _state.value = DshServer.Stopped
    }

    /**
     * Synchronous teardown for `dispose()`.
     *
     * `dispose()` is not a suspending function and the host does not wait on a
     * coroutine we launch from it, so the stop cannot go through the mutex — a
     * suspension there would let the plugin's scope be cancelled mid-teardown and
     * leave the process running. This deliberately skips the lock and destroys
     * what it holds.
     */
    fun disposeNow() {
        process?.let { DshProcesses.terminate(it) }
        process = null
        shutdownHook?.let { hook -> runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
        shutdownHook = null
        runCatching { pidFile.delete() }
        _state.value = DshServer.Stopped
    }

    private fun spawn(argv: List<String>, cwd: File?, extraEnv: Map<String, String?>): Process {
        val builder = ProcessBuilder(argv)
        if (cwd != null && cwd.isDirectory) builder.directory(cwd)
        // Merge stderr into stdout: the readiness line is on stdout, and a
        // failure diagnostic is on stderr. Reading one stream means a failure
        // cannot sit unread in a pipe while we block waiting for a line that
        // will never come.
        builder.redirectErrorStream(true)
        builder.environment().let { e ->
            e["PATH"] = childPathForSpawn()
            for ((key, value) in extraEnv) {
                if (value == null) e.remove(key) else e[key] = value
            }
        }
        val started = builder.start()
        process = started
        shutdownHook = Thread { DshProcesses.terminate(started) }
            .also { Runtime.getRuntime().addShutdownHook(it) }
        return started
    }

    /**
     * Read stdout until the URL line appears, the process dies, or we run out of
     * patience, then confirm the server actually answers.
     *
     * Readiness is a successful HTTP response, not just the printed line: the
     * harness prints the URL after its loader tree settles, but proving the
     * socket accepts a request is what the tab needs before it points a browser
     * at it.
     */
    private suspend fun awaitReady(started: Process): DshServer = withContext(Dispatchers.IO) {
        val transcript = StringBuilder()
        val reader = started.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive && !reader.ready()) {
                return@withContext DshServer.Failed(failureText(transcript.toString()))
            }
            val line = runCatching { reader.readLine() }.getOrNull()
                ?: return@withContext DshServer.Failed(failureText(transcript.toString()))
            val browserUrl = parseBrowserUrl(line)
            if (browserUrl == null) {
                // Readiness URLs carry a credential; never put them in failures.
                if (!line.startsWith("dsh web: http")) transcript.appendLine(line)
                continue
            }
            val port = URL(browserUrl).port
            return@withContext if (awaitHttp(browserUrl, deadline)) {
                DshServer.Running(port = port, pid = started.pid(), browserUrl = browserUrl)
            } else {
                DshServer.Failed("dsh reported port $port but never answered a request")
            }
        }
        DshServer.Failed("dsh web did not report a URL within ${STARTUP_TIMEOUT_MS / 1000}s")
    }

    private suspend fun awaitHttp(browserUrl: String, deadline: Long): Boolean {
        while (System.currentTimeMillis() < deadline) {
            if (probe(browserUrl)) return true
            delay(HTTP_POLL_MS)
        }
        return false
    }

    /** Reap a server left behind by an earlier load of this plugin. */
    private fun reapStaleLocked() {
        val recorded = runCatching { pidFile.readText().trim().toLong() }.getOrNull() ?: return
        DshProcesses.terminateStale(recorded, STALE_COMMAND_MARKER)
        runCatching { pidFile.delete() }
    }

    private fun recordPid(pid: Long) = runCatching {
        pidFile.parentFile?.mkdirs()
        pidFile.writeText(pid.toString())
    }

    /**
     * Delegated to [DshCli.childPath] rather than built here.
     *
     * This used to be its own list — homebrew, /usr/local, /usr/bin, /bin — which
     * was DshCli's list minus the toolchain-manager directories and, once the
     * plugin started choosing a Node by version, minus the chosen Node too. Two
     * copies of "where do we find things" meant `dsh --version` could be probed
     * with one Node and `dsh web` spawned with another.
     */
    private fun childPathForSpawn(): String = DshCli.childPath()

    companion object {
        /**
         * Matches the harness's readiness line, e.g.
         * `dsh web: http://127.0.0.1:62375`. Anchored on the loopback host so a
         * URL mentioned inside some other diagnostic cannot be mistaken for it.
         */
        private val URL_LINE = Regex("""^dsh web: (http://127\.0\.0\.1:[0-9]+(?:/\?token=[A-Za-z0-9_-]+|/)?)(?=\s|$)""")

        /** Enough of our own argv to tell our server from a recycled pid. */
        private const val STALE_COMMAND_MARKER = "--profile web"

        private const val STARTUP_TIMEOUT_MS = 120_000L
        private const val HTTP_POLL_MS = 250L
        private const val PROBE_TIMEOUT_MS = 2_000

        /**
         * Reduce the transcript of a failed boot to a diagnosis.
         *
         * This used to keep the last twelve lines, "because a stack trace's
         * useful part is at the end". It is, in a JVM trace. The harness is a
         * Node program, where the message is the *first* line and everything
         * after it is frames — so the tail was the one part guaranteed to say
         * nothing, and a user whose `.credentials.yaml` held one bad line got an
         * error dialog opening on
         * `at Entry._init (file:///…cordis-plugin-loader/lib/index.js:519:10) {`.
         * See [DshFailure].
         *
         * Internal rather than private, and in the companion rather than on the
         * instance, for the same reason [parsePort] is: it reads no state, and
         * what it does to a real transcript is worth pinning.
         */
        internal fun failureText(transcript: String): String =
            DshFailure.explain(transcript).ifBlank { "dsh web exited without output" }

        /** Keep the launch token only for navigation; accept only the local root URL. */
        internal fun parseBrowserUrl(line: String): String? =
            URL_LINE.find(line)?.groupValues?.get(1)?.takeIf {
                runCatching { URL(it).port in 1..65535 }.getOrDefault(false)
            }

        internal fun parsePort(line: String): Int? = parseBrowserUrl(line)?.let { URL(it).port }

        internal fun probe(browserUrl: String): Boolean = runCatching {
            val connection = URL(browserUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                // The token exchange returns 303 + a cookie. This probe has no
                // browser cookie jar; following it would turn success into 401.
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.responseCode in 200..399
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
