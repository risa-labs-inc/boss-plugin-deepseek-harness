package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared brain for the plugin: one instance per activation, handed to the sidebar
 * panel, the web tab, and the MCP tools.
 *
 * Every host provider is resolved lazily and may be null; each call site degrades
 * to something usable rather than crashing.
 */
class DshServices(val context: PluginContext) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = DshEngine(context)

    private val storage: PluginStorageProvider? by lazy {
        runCatching { context.pluginStorageFactory?.createStorage(PLUGIN_ID) }.getOrNull()
    }

    fun start() {
        scope.launch {
            // Restore the bridge preference before the first start, or a server
            // started from a restored session would compose without the overlay
            // while the toggle showed as on.
            val enabled = getPref(KEY_BRIDGE, "false").toBoolean()
            if (enabled) engine.setBridgeEnabled(true, bossMcpUrl())
            // Restore before the first launch, or a server started from a
            // restored session would run without the keys the panel shows ticked.
            engine.setKeySelection(
                DshKeySelection(
                    enabled = decodeIds(getPref(KEY_KEYS_ON, "")),
                    disabled = decodeIds(getPref(KEY_KEYS_OFF, "")),
                ),
            )
            engine.refreshAll()
        }
    }

    fun dispose() {
        // Kill the server before cancelling the scope. The teardown is synchronous
        // precisely so it cannot depend on a coroutine that is about to be
        // cancelled — a stop launched into a dying scope leaves the process up.
        engine.dispose()
        scope.cancel()
    }

    // ------------------------------------------------------------ preferences

    fun setBridgeEnabled(enabled: Boolean): String {
        val message = engine.setBridgeEnabled(enabled, bossMcpUrl())
        scope.launch { setPref(KEY_BRIDGE, enabled.toString()) }
        return message
    }

    /**
     * The MCP endpoint the harness should call back into.
     *
     * BOSS's own MCP server is loopback-only and streamable-HTTP at `/mcp`. The
     * port is read from the environment when the host published it, so a
     * non-default port still works, and falls back to the standard one.
     */
    fun bossMcpUrl(): String {
        val port = System.getenv(ENV_MCP_PORT)?.trim()?.toIntOrNull() ?: DEFAULT_MCP_PORT
        return "http://127.0.0.1:$port/mcp"
    }

    /**
     * Expose, or stop exposing, one BOSS secret to the harness.
     *
     * Stored by secret id rather than by name: a renamed secret keeps its
     * selection, and an id cannot collide the way two secrets sharing an
     * `ANTHROPIC_API_KEY` username can.
     */
    fun setKeySelected(candidate: DshKeyCandidate, selected: Boolean) {
        val next = engine.keySelection.value.with(candidate.secretId, selected, candidate.onByDefault)
        engine.setKeySelection(next)
        scope.launch {
            setPref(KEY_KEYS_ON, next.enabled.sorted().joinToString(","))
            setPref(KEY_KEYS_OFF, next.disabled.sorted().joinToString(","))
        }
    }

    // ------------------------------------------------------------------ tabs

    /**
     * Open, or focus, the harness tab.
     *
     * `openTab` is fire-and-forget: the host marshals it onto the UI thread and
     * silently drops the tab when no factory is registered for its type. A caller
     * that reports success to a user has to confirm rather than assume, or
     * "Opened the tab" becomes a lie — which is exactly what happened to the
     * docker plugin's first version of this call.
     */
    suspend fun openWebTab(): Boolean {
        val tabInfo = DshWebTabInfo()
        val tabs = context.activeTabsProvider
        tabs?.activeTabs?.value?.firstOrNull { it.tabId == tabInfo.id }?.let { existing ->
            tabs.selectTab(existing.tabId, existing.panelId)
            return true
        }
        val ops = context.splitViewOperations ?: run {
            toastError("This host exposes no split-view operations, so the tab cannot be opened")
            return false
        }
        ops.openTab(tabInfo)

        // Nothing to verify against; report the request as made.
        if (tabs == null) return true
        repeat(TAB_POLL_ATTEMPTS) {
            delay(TAB_POLL_INTERVAL_MS)
            if (tabs.activeTabs.value.any { it.tabId == tabInfo.id }) return true
        }
        return false
    }

    /** Open [url] in a host browser tab — the fallback when embedding is unavailable. */
    fun openUrl(url: String, title: String) {
        val ops = context.splitViewOperations ?: run {
            toastError("Cannot open $url - this host exposes no split-view operations")
            return
        }
        ops.openUrlInActivePanel(url, title)
    }

    // ---------------------------------------------------------------- toasts

    fun toastSuccess(message: String) = toast(message, NotificationType.SUCCESS)
    fun toastError(message: String) = toast(message, NotificationType.ERROR)
    fun toastInfo(message: String) = toast(message, NotificationType.INFO)

    private fun toast(message: String, type: NotificationType) {
        context.notificationProvider?.showToast(message, type, title = "DeepSeek Harness")
    }

    // --------------------------------------------------------------- storage

    suspend fun getPref(key: String, default: String): String =
        runCatching { storage?.getString(key, default) }.getOrNull() ?: default

    suspend fun setPref(key: String, value: String) {
        runCatching { storage?.putString(key, value) }
    }

    companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.deepseekharness"
        const val KEY_BRIDGE = "bossMcpBridgeEnabled"
        const val KEY_KEYS_ON = "providerKeysOn"
        const val KEY_KEYS_OFF = "providerKeysOff"

        /** Tolerates the empty string and stray separators from an older write. */
        internal fun decodeIds(raw: String): Set<String> =
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        /** Set by the host when its MCP server is not on the default port. */
        private const val ENV_MCP_PORT = "BOSS_MCP_PORT"
        private const val DEFAULT_MCP_PORT = 7677

        private const val TAB_POLL_ATTEMPTS = 25
        private const val TAB_POLL_INTERVAL_MS = 100L
    }
}
