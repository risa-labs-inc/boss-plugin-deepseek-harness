package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Everything the panel and the MCP tools read, and the operations they both run.
 *
 * State lives in [StateFlow]s so the panel recomposes and a tool reads the same
 * values. Nothing here caches an "absent" answer: the panel offers an install
 * action, and a memoised `dsh: missing` would survive the install that fixed it.
 */
class DshEngine(
    private val context: PluginContext,
    private val env: Map<String, String> = System.getenv(),
) {

    val credentials = DshCredentials(context)
    val secretSync = DshSecretSync(context, env)
    val registrar = DshProviderRegistrar(env)
    val bridge = DshMcpBridge(env)
    val server = DshWebServer(env)

    private val _install = MutableStateFlow<DshInstall>(DshInstall.NodeMissing)
    val install: StateFlow<DshInstall> = _install.asStateFlow()

    private val _pnpm = MutableStateFlow<File?>(null)
    val pnpm: StateFlow<File?> = _pnpm.asStateFlow()

    private val _profiles = MutableStateFlow<List<DshProfile>>(emptyList())
    val profiles: StateFlow<List<DshProfile>> = _profiles.asStateFlow()

    private val _keySource = MutableStateFlow(DshKeySource.NONE)
    val keySource: StateFlow<DshKeySource> = _keySource.asStateFlow()

    private val _bridgeEnabled = MutableStateFlow(false)
    val bridgeEnabled: StateFlow<Boolean> = _bridgeEnabled.asStateFlow()

    private val _keyCandidates = MutableStateFlow<List<DshKeyCandidate>>(emptyList())
    val keyCandidates: StateFlow<List<DshKeyCandidate>> = _keyCandidates.asStateFlow()

    private val _keySelection = MutableStateFlow(DshKeySelection())
    val keySelection: StateFlow<DshKeySelection> = _keySelection.asStateFlow()

    private val _lastRegister = MutableStateFlow<DshRegisterOutcome>(DshRegisterOutcome.UpToDate)

    /** Outcome of the most recent provider registration, for the panel and doctor. */
    val lastRegister: StateFlow<DshRegisterOutcome> = _lastRegister.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)

    /** Non-null while a long operation runs, carrying a label for the panel. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** The harness home in effect, honouring `$DSH_HOME`. */
    val home: File get() = DshPaths.home(env)

    // ---------------------------------------------------------------- refresh

    /** Re-read every derived value. Cheap enough to run on panel open. */
    suspend fun refreshAll() {
        refreshInstall()
        refreshProfiles()
        refreshKeySource()
        refreshKeyCandidates()
    }

    suspend fun refreshKeyCandidates() {
        _keyCandidates.value = secretSync.candidates()
    }

    /** Restored from plugin storage at start; see DshServices. */
    fun setKeySelection(selection: DshKeySelection) {
        _keySelection.value = selection
    }

    /**
     * The complete environment handed to a harness child: the DeepSeek provider
     * key (or its removal) plus every secret the user ticked.
     *
     * Assembled in one place so the server and the one-shot `ask` path cannot
     * drift - a key that worked in the web UI but not in dsh_ask would be a
     * miserable thing to debug.
     */
    /**
     * Register a harness route for every key being injected that maps to one.
     *
     * Run before each launch rather than once, because the set of injected keys
     * changes when the user ticks a switch or adds a secret, and a route is only
     * ever written when its credential will actually be present - the harness
     * fails a route whose `apiKeyEnv` resolves to nothing rather than falling
     * through to another. Idempotent, so the common case touches no file.
     */
    suspend fun syncProviders(): DshRegisterOutcome {
        val names = secretSync.namesFor(_keySelection.value, credentials.suppliedNames())
        val outcome = registrar.register(registrar.plan(names))
        _lastRegister.value = outcome
        return outcome
    }

    private suspend fun childEnv(): Map<String, String?> =
        credentials.childEnv() + secretSync.envFor(_keySelection.value, credentials.suppliedNames())

    suspend fun refreshInstall() {
        val node = DshCli.which("node")
        _pnpm.value = DshCli.which("pnpm")
        if (node == null) {
            _install.value = DshInstall.NodeMissing
            return
        }
        val dsh = DshCli.which("dsh")
        if (dsh == null) {
            _install.value = DshInstall.DshMissing(node)
            return
        }
        val probe = DshCli.exec(listOf(dsh.absolutePath, "--version"), timeoutSeconds = VERSION_TIMEOUT)
        _install.value = if (probe.ok) {
            DshInstall.Ready(dsh, probe.stdout.trim().ifBlank { "unknown" })
        } else {
            // The binary is on disk but will not answer. Treat it as missing
            // rather than Ready-with-a-bad-version: every later call would fail
            // the same way, and "install it" is the honest remedy.
            DshInstall.DshMissing(node)
        }
    }

    fun refreshProfiles() {
        val dir = DshPaths.profilesDir(env)
        val onDisk = dir.listFiles { f: File -> f.isDirectory && f.name != "node_modules" }
            ?.map { it.name }
            .orEmpty()
        // Shipped profiles are always listed, even before first use, because the
        // harness creates them on demand and a user should see that they exist.
        val names = (DshPaths.SHIPPED_PROFILES + onDisk).distinct().sorted()
        _profiles.value = names.map { name ->
            val profileDir = DshPaths.profileDir(name, env)
            DshProfile(
                name = name,
                initialized = profileDir.isDirectory,
                bundles = readBundles(profileDir),
            )
        }
    }

    suspend fun refreshKeySource() {
        _keySource.value = credentials.describe()
    }

    // ------------------------------------------------------------- operations

    /**
     * Start the embedded server.
     *
     * Returns a human-readable outcome for a tool result; the panel reads
     * [DshWebServer.state] instead.
     */
    suspend fun startServer(): String {
        val ready = _install.value as? DshInstall.Ready
            ?: return "DeepSeek Harness is not installed. Open the DeepSeek Harness panel to install it."
        val overlay = if (_bridgeEnabled.value) bridge.overlayFile().takeIf { it.isFile } else null
        syncProviders()
        _busy.value = "Starting dsh web"
        return try {
            when (val outcome = server.start(ready.dsh, workspaceRoot(), childEnv(), overlay)) {
                is DshServer.Running -> "dsh web is serving ${outcome.url} (pid ${outcome.pid})."
                is DshServer.Failed -> "dsh web did not start: ${outcome.reason}"
                else -> "dsh web is ${outcome::class.simpleName}."
            }
        } finally {
            _busy.value = null
        }
    }

    suspend fun stopServer(): String {
        server.stop()
        return "dsh web stopped."
    }

    /**
     * Run one task through the one-shot profile.
     *
     * The harness's headless profile prints the final assistant text on stdout
     * and exits 0 for a completed turn, 1 otherwise, with diagnostics on stderr.
     * Both non-zero cases are mapped to something a caller can act on rather
     * than passed through raw: a missing credential is the single most likely
     * failure and has nothing to do with the task.
     */
    suspend fun ask(task: String, cwd: File?, timeoutSeconds: Long): Pair<String, Boolean> {
        val ready = _install.value as? DshInstall.Ready
            ?: return "DeepSeek Harness is not installed on this machine." to true

        if (task.isBlank()) return "A task is required." to true
        syncProviders()

        val exec = DshCli.exec(
            argv = listOf(ready.dsh.absolutePath, "--profile", "headless", task),
            cwd = cwd,
            extraEnv = childEnv(),
            timeoutSeconds = timeoutSeconds,
        )

        return when {
            exec.ok -> exec.stdout.trim().ifBlank { "(the harness completed the turn with no text)" } to false
            exec.timedOut -> "The task exceeded ${timeoutSeconds}s and was stopped." to true
            exec.missing -> "DeepSeek Harness could not be started." to true
            exec.message.contains(DshCredentials.MISSING_MARKER) ->
                ("No DeepSeek API key is configured. Add a DeepSeek provider on BOSS's AI Providers " +
                    "settings page, or store a `${DshCredentials.SECRET_WEBSITE}` secret, then retry.") to true
            else -> exec.message.ifBlank { "The turn ended without completing." } to true
        }
    }

    /**
     * Add or remove a profile bundle.
     *
     * `dsh plugin` forwards to pnpm, so pnpm's absence is checked first and named
     * as such. Without that check the failure arrives as the harness's own
     * "pnpm not found on PATH", which is accurate but reads like a harness bug.
     */
    suspend fun bundle(profile: String, verb: String, packages: List<String>): Pair<String, Boolean> {
        val ready = _install.value as? DshInstall.Ready
            ?: return "DeepSeek Harness is not installed." to true
        if (_pnpm.value == null) {
            return ("Managing bundles needs pnpm, which is not installed. Install it " +
                "(`npm i -g pnpm`) and refresh the DeepSeek Harness panel.") to true
        }
        if (packages.isEmpty()) return "At least one package is required." to true

        val exec = DshCli.exec(
            argv = listOf(ready.dsh.absolutePath, "plugin", "--profile", profile, verb) + packages,
            extraEnv = credentials.childEnv(),
            timeoutSeconds = BUNDLE_TIMEOUT,
        )
        refreshProfiles()
        return if (exec.ok) {
            // Bundle membership is fixed at profile start, which the harness's own
            // reference states explicitly. Saying so here is the difference between
            // "it worked" and "it worked and you must restart to see it".
            ("$verb succeeded for profile `$profile`. Restart the server for the change " +
                "to take effect - bundle membership is fixed when a profile starts.") to false
        } else {
            exec.message.ifBlank { "pnpm reported failure." } to true
        }
    }

    /** The composed config tree for a profile, without booting it. */
    suspend fun dumpConfig(profile: String, defaultsOnly: Boolean, row: String? = null): Pair<String, Boolean> {
        val ready = _install.value as? DshInstall.Ready
            ?: return "DeepSeek Harness is not installed." to true
        val flag = if (defaultsOnly) "--dump-default-config" else "--dump-config"
        val overlay = if (!defaultsOnly && _bridgeEnabled.value) {
            bridge.overlayFile().takeIf { it.isFile }
        } else {
            null
        }
        val argv = buildList {
            add(ready.dsh.absolutePath)
            add("--profile"); add(profile)
            if (overlay != null) { add("--patch"); add(overlay.absolutePath) }
            add(flag)
        }
        val exec = DshCli.exec(argv, timeoutSeconds = DUMP_TIMEOUT)
        if (!exec.ok) return exec.message to true
        val dump = exec.stdout.trim()
        val filtered = row?.trim()?.takeIf { it.isNotEmpty() }?.let { selectRow(dump, it) }
            ?: return dump to false
        return filtered
    }

    /**
     * The block for one row id, plus the source-attribution comment above it.
     *
     * The whole tree is hundreds of rows and tens of thousands of characters, and
     * the question it is usually asked is about one row. Returning everything makes
     * the caller pay for the other three hundred, which for an agent means its
     * context rather than its patience.
     *
     * A row block runs from its `- id:` line to the next top-level `- ` line, and
     * the `# ==` comment above it is what names the file that supplied the value —
     * so dropping it would remove the only part that answers "why".
     */
    internal fun selectRow(dump: String, row: String): Pair<String, Boolean> {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.trimEnd() == "- id: $row" }
        if (start < 0) {
            val available = lines.filter { it.startsWith("- id: ") }.map { it.removePrefix("- id: ") }
            return ("No row `$row` in this profile's composed tree. " +
                "Rows: ${available.joinToString(", ")}") to true
        }
        // Walk back over the contiguous comment block, which carries the attribution.
        var from = start
        while (from > 0 && lines[from - 1].startsWith("#")) from--
        var to = start + 1
        while (to < lines.size && !lines[to].startsWith("- ") && !lines[to].startsWith("#")) to++
        return lines.subList(from, to).joinToString("\n") to false
    }

    /** Sessions on disk, described without decoding them. */
    fun sessions(limit: Int): List<DshSession> {
        val dir = DshPaths.sessionsDir(env)
        val files = dir.listFiles { f: File -> f.isFile }?.toList().orEmpty()
        return files
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .map { DshSession(it.nameWithoutExtension, it.length(), it.lastModified()) }
    }

    /**
     * Turn the BOSS MCP bridge on or off.
     *
     * Enabling writes the overlay; disabling stops passing it. Either way the
     * running server keeps the composition it started with, so the caller is told
     * a restart is needed rather than left to wonder why nothing changed.
     */
    fun setBridgeEnabled(enabled: Boolean, mcpUrl: String): String {
        _bridgeEnabled.value = enabled
        return if (enabled) {
            bridge.writeOverlay(mcpUrl)
            "BOSS MCP tools will be served to harness agents as `mcp__${DshMcpBridge.SERVER_NAME}__*`. " +
                "Restart the server to apply."
        } else {
            "BOSS MCP tools will no longer be served. Restart the server to apply."
        }
    }

    /** Install command for the panel to run in a visible terminal. */
    fun installCommand(): String = "npm install -g ${DshCli.PACKAGE}@latest"

    /**
     * Workspace root handed to the harness as its cwd.
     *
     * The harness treats the invoking directory as its default workspace root and
     * confines bash and filesystem mutations to it under the default
     * `workspace-write` preset, so this choice is a real boundary rather than a
     * convenience. Falls back to the user's home only when BOSS has no project
     * open, which is also what the harness would do with no better information.
     */
    fun workspaceRoot(): File? {
        val path = runCatching { context.projectPath }.getOrNull()
        return path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
    }

    fun dispose() {
        server.disposeNow()
    }

    private fun readBundles(profileDir: File): List<String> {
        val manifest = File(profileDir, "package.json").takeIf { it.isFile } ?: return emptyList()
        val text = runCatching { manifest.readText() }.getOrNull() ?: return emptyList()
        // A deliberately small extraction rather than a JSON dependency: the only
        // thing wanted is the string list under dsh.profile.bundles, and the file
        // is written by the harness, not by a user.
        val block = Regex(""""bundles"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: return emptyList()
        return Regex(""""([^"]+)"""").findAll(block).map { it.groupValues[1] }.toList()
    }

    private companion object {
        const val VERSION_TIMEOUT = 60L
        const val BUNDLE_TIMEOUT = 600L
        const val DUMP_TIMEOUT = 120L
    }
}
