package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Panel actions.
 *
 * Its own scope, not the plugin's: a panel that is closed and reopened must not
 * inherit a job the previous instance left running, and a click that outlives the
 * panel should be cancelled rather than complete against a dead UI. The engine's
 * state is shared, so anything long-running that must survive the panel (the
 * server) lives there instead.
 */
class DshPanelViewModel(private val services: DshServices) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine get() = services.engine

    val install: StateFlow<DshInstall> = engine.install
    val server: StateFlow<DshServer> = engine.server.state
    val profiles: StateFlow<List<DshProfile>> = engine.profiles
    val keySource: StateFlow<DshKeySource> = engine.keySource
    val bridgeEnabled: StateFlow<Boolean> = engine.bridgeEnabled
    val keyCandidates: StateFlow<List<DshKeyCandidate>> = engine.keyCandidates
    val keySelection: StateFlow<DshKeySelection> = engine.keySelection
    val busy: StateFlow<String?> = engine.busy

    private val _expanded = MutableStateFlow(setOf(Section.STATUS, Section.SERVER))
    val expanded: StateFlow<Set<Section>> = _expanded.asStateFlow()

    /** The harness home path, for display. */
    val homePath: String get() = engine.home.absolutePath

    fun refresh() {
        scope.launch { engine.refreshAll() }
    }

    fun toggleSection(section: Section) {
        _expanded.value = _expanded.value.let { if (section in it) it - section else it + section }
    }

    fun startServer() {
        scope.launch {
            engine.refreshInstall()
            val message = engine.startServer()
            if (engine.server.state.value is DshServer.Running) {
                services.toastSuccess(message)
            } else {
                services.toastError(message)
            }
        }
    }

    fun stopServer() {
        scope.launch { services.toastInfo(engine.stopServer()) }
    }

    fun openTab() {
        scope.launch {
            engine.refreshInstall()
            if (engine.server.state.value !is DshServer.Running) engine.startServer()
            if (engine.server.state.value is DshServer.Running) {
                if (!services.openWebTab()) services.toastError("The DeepSeek Harness tab could not be opened")
            } else {
                services.toastError("DeepSeek Harness is not running, so there is nothing to show")
            }
        }
    }

    /**
     * Install the harness in a visible terminal tab.
     *
     * The install is minutes long — measured at over five minutes for a cold
     * dependency tree — so it runs where its output is visible rather than behind
     * a spinner that is indistinguishable from a hang. The terminal is the host's
     * to provide; without it the command is copied out for the user to run.
     */
    fun install() {
        val command = engine.installCommand()
        val terminal = runCatching {
            services.context.getPluginAPI(ai.rever.boss.plugin.api.TerminalTabPluginAPI::class.java)
        }.getOrNull()
        val windowId = services.context.windowId
        if (terminal == null || windowId == null) {
            services.context.clipboardProvider?.setText(command)
            services.toastInfo("Install command copied. Run it in a terminal: $command")
            return
        }
        terminal.setPendingSidebarCommand(windowId, command, workingDirectory = null)
        services.toastInfo("Installing DeepSeek Harness in a terminal - this takes a few minutes")
    }

    fun setBridgeEnabled(enabled: Boolean) {
        val message = services.setBridgeEnabled(enabled)
        services.toastInfo(message)
    }

    fun setKeySelected(candidate: DshKeyCandidate, selected: Boolean) {
        services.setKeySelected(candidate, selected)
        val verb = if (selected) "will be passed to" else "will no longer be passed to"
        services.toastInfo("${candidate.envName} $verb DeepSeek Harness. Restart the server to apply.")
    }

    fun copyUrl() {
        val running = server.value as? DshServer.Running ?: return
        services.context.clipboardProvider?.setText(running.url)
        services.toastSuccess("Copied ${running.url}")
    }

    fun dispose() {
        scope.cancel()
    }

    /** Collapsible sections of the panel. */
    enum class Section(val label: String) {
        STATUS("Status"),
        SERVER("Server"),
        PROFILES("Profiles"),
        KEYS("Provider keys"),
        BRIDGE("BOSS MCP tools"),
    }
}
