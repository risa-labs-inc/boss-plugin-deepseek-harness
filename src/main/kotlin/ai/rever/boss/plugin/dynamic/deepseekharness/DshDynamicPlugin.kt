package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * DeepSeek Harness — run DeepSeek's `dsh` agent harness inside BOSS.
 *
 * Registers:
 * - a sidebar panel that installs the harness, supervises its server, shows
 *   which profiles exist and where its API key is coming from,
 * - a tab embedding the harness's own web UI, served by the process this plugin
 *   supervises,
 * - `dsh_*` MCP tools so in-terminal agents can ask the harness to do work,
 *   manage its profiles, and check why it is not working.
 *
 * The integration deliberately talks to the harness through its documented CLI
 * and the files under `$DSH_HOME`, never through its `/api` RPC or its session
 * log. The harness is in developer preview and says it will break; those two
 * surfaces are the ones that will break first, and neither is needed.
 */
class DshDynamicPlugin : DynamicPlugin {

    override val pluginId = DshServices.PLUGIN_ID
    override val displayName = "DeepSeek Harness"
    override val version = "1.0.0"
    override val description =
        "Run DeepSeek Harness (dsh) inside BOSS: install and supervise it, embed its web UI in a " +
            "tab, feed it the API key BOSS already manages, and drive it from dsh_* MCP tools"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-deepseek-harness"

    private var services: DshServices? = null

    override fun register(context: PluginContext) {
        val services = DshServices(context).also { this.services = it }
        services.start()

        context.panelRegistry.registerPanel(DshPanelInfo) { ctx, panelInfo ->
            DshPanelComponent(ctx, panelInfo, services)
        }
        context.tabRegistry.registerTabType(DshWebTabType) { tabInfo, ctx ->
            DshWebTabComponent(ctx, tabInfo, services)
        }
        context.registerMcpToolProvider(DshMcpToolProvider(pluginId, services))
    }

    override fun dispose() {
        services?.dispose()
        services = null
    }
}
