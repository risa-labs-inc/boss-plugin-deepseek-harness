package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File

/**
 * Serving BOSS's own MCP tools to harness agents.
 *
 * The harness ships `@deepseek-ai/dsh-mcp-client` as a dependency precisely so a
 * patch layer can add servers, but enables **none** by default, and says why:
 * each server command is trusted executable code outside the agent sandbox. That
 * is a real decision, not an oversight, so this bridge is opt-in and default-off,
 * and the panel repeats the reason rather than burying it.
 *
 * Turning it on gives a harness agent every `mcp__boss__*` tool BOSS currently
 * exposes — the same set an in-terminal agent sees, since it is literally the
 * same server.
 *
 * ## Why an overlay file rather than editing a patch layer
 *
 * The harness composes a profile from bundle layers, then the profile's own
 * `cordis.patch.yml`, then `$DSH_HOME/cordis.patch.yml`, then `--patch`
 * overlays in argv order. The two `cordis.patch.yml` files belong to the user.
 * Editing them would be wrong for two independent reasons: a patch replaces the
 * targeted row's *whole* `config` rather than merging into it, so a
 * well-intentioned rewrite can silently drop a setting; and a plugin that edits
 * a user's config file has to get removal right too, forever.
 *
 * So the bridge owns a file nobody else writes, under
 * [DshPaths.overlayDir], and is passed as `--patch`. Turning the toggle off
 * stops passing the flag; the file's continued existence changes nothing.
 *
 * Verified against `dsh 0.1.0-rc.7`: with the overlay passed,
 * `dsh --profile web --patch <overlay> --dump-config` prints the inserted row
 * attributed to the overlay file, and the profile's own `cordis.patch.yml` is
 * byte-identical afterwards.
 */
class DshMcpBridge(private val env: Map<String, String> = System.getenv()) {

    companion object {
        /**
         * Row id in the composed tree. Namespaced so it cannot collide with a
         * row the user or a bundle owns — a duplicate id would be an override
         * of someone else's row rather than an insert of ours.
         */
        const val ROW_ID = "boss-mcp-bridge"

        /**
         * The name used when the host cannot be asked.
         *
         * Normally the name comes from [McpServerState.serverName], because the
         * same server answers to `boss` inside BOSS and `bossterm` in a standalone
         * BossTerm - and the model-facing tool prefix follows it, so a hardcoded
         * name would make every `mcp__boss__*` reference wrong on one of the two.
         * The harness constrains it to `[A-Za-z0-9_-]{1,32}`.
         */
        const val DEFAULT_SERVER_NAME = "boss"

        private const val FILE_NAME = "boss-mcp.yml"
    }

    /** The overlay path, whether or not it exists yet. */
    fun overlayFile(): File = File(DshPaths.overlayDir(env), FILE_NAME)

    /**
     * Write the overlay for BOSS's MCP server at [mcpUrl] and return its path.
     *
     * Rewritten on every enable rather than written once, so a changed port is
     * picked up without the user having to know a stale file exists.
     */
    fun writeOverlay(serverName: String, mcpUrl: String): File {
        val file = overlayFile()
        file.parentFile?.mkdirs()
        file.writeText(overlayYaml(serverName, mcpUrl))
        return file
    }

    /** Remove the overlay. Absence is success, so this is safe to call repeatedly. */
    fun removeOverlay(): Boolean = overlayFile().let { !it.exists() || it.delete() }

    /**
     * The overlay body.
     *
     * `- insert:` is the loader's add-a-row form; an entry with a bare `id` at
     * the top level would instead *override* an existing row with that id and
     * silently do nothing if none exists. The URL is written as a literal rather
     * than a `!!js process.env...` expression so that what the file says is what
     * the harness uses, and `--dump-config` shows the real value.
     */
    internal fun overlayYaml(serverName: String, mcpUrl: String): String = """
        # Written by the BOSS DeepSeek Harness plugin. Safe to delete: it is passed
        # with --patch only while the "BOSS MCP tools" toggle is on, and rewritten
        # whenever that toggle is turned on again.
        #
        # This exposes every tool BOSS's MCP server offers to harness agents as
        # mcp__${serverName}__*. An MCP server is trusted executable code outside the
        # harness's agent sandbox, which is why the harness enables none by default.
        - insert:
            - id: $ROW_ID
              name: '@deepseek-ai/dsh-mcp-client'
              config:
                serverName: $serverName
                transport: streamable-http
                url: $mcpUrl
    """.trimIndent() + "\n"
}
