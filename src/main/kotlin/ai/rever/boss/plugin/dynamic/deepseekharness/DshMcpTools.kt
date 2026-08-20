package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import java.io.File
import java.time.Instant

/**
 * `dsh_*` tools for in-terminal agents. They surface as `mcp__boss__dsh_*` and
 * appear and disappear with the plugin.
 *
 * ## What is gated, and why
 *
 * Two permissions, declared in the manifest:
 *
 * - **`dsh.run`** gates [dsh_ask]. A harness turn spends model tokens and, under
 *   the harness's default `workspace-write` preset, can write files anywhere in
 *   the workspace it runs against. That is the most consequential thing this
 *   plugin can be asked to do and it is the one tool that costs money.
 * - **`dsh.manage`** gates the server lifecycle and bundle management: starting a
 *   long-lived process, and running pnpm installs into a profile.
 *
 * `dsh_open` is `readOnly = false` and deliberately **ungated**: it opens a BOSS
 * tab and touches no harness state. That exception is recorded in
 * `DshMcpToolRbacTest`, which fails the build if any *other* mutating tool ships
 * without a permission — the same guard boss-plugin-docker uses, for the same
 * reason: gating half the mutating surface is an asymmetry, not a policy.
 */
class DshMcpToolProvider(
    override val providerId: String,
    private val services: DshServices,
) : McpToolProvider {

    private val engine get() = services.engine

    override fun tools(): List<McpToolDefinition> = listOf(
        // ------------------------------------------------------------- run
        McpToolDefinition.withRbac(
            name = "dsh_ask",
            description = "Ask DeepSeek Harness to carry out one task and return its final answer. " +
                "Runs the harness's one-shot `headless` profile, which completes the whole turn " +
                "(including any tool use it decides on) before returning. Spends model tokens and " +
                "may modify files under the working directory.",
            inputSchema = """
                {"type":"object","properties":{
                  "task":{"type":"string","description":"What the harness should do"},
                  "cwd":{"type":"string","description":"Absolute working directory; defaults to the open BOSS project"},
                  "timeout_seconds":{"type":"integer","description":"Give up after this long (default 600, max 3600)"}
                },"required":["task"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_RUN),
            handler = McpToolHandler { args ->
                val task = args.string("task")?.trim().orEmpty()
                val cwd = args.string("cwd")?.let(::File)?.takeIf { it.isDirectory }
                    ?: engine.workspaceRoot()
                val timeout = (args.int("timeout_seconds") ?: DEFAULT_ASK_TIMEOUT)
                    .coerceIn(MIN_ASK_TIMEOUT, MAX_ASK_TIMEOUT)
                    .toLong()
                val (text, isError) = engine.ask(task, cwd, timeout)
                McpToolResult(text, isError = isError)
            },
        ),

        // ---------------------------------------------------------- doctor
        McpToolDefinition(
            name = "dsh_doctor",
            description = "Readiness of DeepSeek Harness on this machine: whether node, dsh and pnpm " +
                "are present and at what version, the harness home in use, which profiles exist, " +
                "where the API key comes from, and whether the embedded server is running. " +
                "Call this first when anything dsh-related is not working.",
            handler = McpToolHandler {
                engine.refreshAll()
                McpToolResult(doctorReport())
            },
        ),

        // ---------------------------------------------------- server state
        McpToolDefinition(
            name = "dsh_web_status",
            description = "Whether the embedded DeepSeek Harness web server is running, and on what URL.",
            handler = McpToolHandler {
                McpToolResult(
                    when (val state = engine.server.state.value) {
                        is DshServer.Running -> "Running on ${state.url} (pid ${state.pid})."
                        is DshServer.Failed -> "Not running. Last attempt failed: ${state.reason}"
                        DshServer.Starting -> "Starting."
                        DshServer.Stopped -> "Stopped."
                    },
                )
            },
        ),

        McpToolDefinition.withRbac(
            name = "dsh_web_start",
            description = "Start the embedded DeepSeek Harness web server. Picks a free port, waits " +
                "until it answers, and returns its URL. No-op when already running.",
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler {
                engine.refreshInstall()
                val message = engine.startServer()
                McpToolResult(message, isError = engine.server.state.value !is DshServer.Running)
            },
        ),

        McpToolDefinition.withRbac(
            name = "dsh_web_stop",
            description = "Stop the embedded DeepSeek Harness web server and every process it started.",
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { McpToolResult(engine.stopServer()) },
        ),

        // Mutating but harness-neutral: it opens a BOSS tab. See the class doc.
        McpToolDefinition(
            name = "dsh_open",
            description = "Open the DeepSeek Harness tab in BOSS, showing the harness's own web UI. " +
                "Starts the server first if it is not already running.",
            readOnly = false,
            handler = McpToolHandler {
                engine.refreshInstall()
                if (engine.server.state.value !is DshServer.Running) engine.startServer()
                val state = engine.server.state.value
                if (state !is DshServer.Running) {
                    McpToolResult("Could not start the harness, so there is nothing to show.", isError = true)
                } else {
                    val opened = services.openWebTab()
                    if (opened) {
                        McpToolResult("Opened the DeepSeek Harness tab on ${state.url}.")
                    } else {
                        McpToolResult(
                            "The harness is serving ${state.url} but the tab could not be opened.",
                            isError = true,
                        )
                    }
                }
            },
        ),

        // -------------------------------------------------------- profiles
        McpToolDefinition(
            name = "dsh_profiles",
            description = "List DeepSeek Harness profiles under the harness home, whether each has been " +
                "initialized, and which bundles it composes.",
            handler = McpToolHandler {
                engine.refreshProfiles()
                val profiles = engine.profiles.value
                if (profiles.isEmpty()) {
                    McpToolResult("No profiles. The harness creates `web` and `headless` on first use.")
                } else {
                    McpToolResult(
                        profiles.joinToString("\n") { p ->
                            val state = if (p.initialized) "initialized" else "not yet created"
                            val bundles = p.bundles.joinToString(", ").ifBlank { "-" }
                            "${p.name}  $state  bundles=$bundles"
                        },
                    )
                }
            },
        ),

        McpToolDefinition(
            name = "dsh_dump_config",
            description = "Print a profile's composed configuration tree without booting it. Each row is " +
                "annotated with the file that supplied it, which is the way to find out why a setting " +
                "has the value it does. Pass `row` to get one row instead of the whole tree, which is " +
                "hundreds of rows long.",
            inputSchema = """
                {"type":"object","properties":{
                  "profile":{"type":"string","description":"Profile name (default web)"},
                  "row":{"type":"string","description":"Return only this row id (e.g. system-prompt) with the comment naming the file that set it. Strongly preferred: the whole tree is hundreds of rows."},
                  "defaults_only":{"type":"boolean","description":"Bundle layers only, omitting user layers (default false)"}
                }}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                val profile = args.string("profile")?.trim().orEmpty().ifBlank { "web" }
                val (text, isError) = engine.dumpConfig(
                    profile = profile,
                    defaultsOnly = args.boolean("defaults_only") ?: false,
                    row = args.string("row"),
                )
                McpToolResult(text.take(MAX_DUMP_CHARS), isError = isError)
            },
        ),

        McpToolDefinition.withRbac(
            name = "dsh_bundle_add",
            description = "Install one or more plugin bundles into a DeepSeek Harness profile. Forwards to " +
                "pnpm, so pnpm must be installed. The server must be restarted afterwards: bundle " +
                "membership is fixed when a profile starts.",
            inputSchema = """
                {"type":"object","properties":{
                  "packages":{"type":"array","items":{"type":"string"},"description":"npm names or git specs"},
                  "profile":{"type":"string","description":"Profile to install into (default web)"}
                },"required":["packages"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                val profile = args.string("profile")?.trim().orEmpty().ifBlank { "web" }
                val (text, isError) = engine.bundle(profile, "add", parsePackages(args.raw))
                McpToolResult(text, isError = isError)
            },
        ),

        McpToolDefinition.withRbac(
            name = "dsh_bundle_remove",
            description = "Remove one or more plugin bundles from a DeepSeek Harness profile. Forwards to " +
                "pnpm. The server must be restarted afterwards.",
            inputSchema = """
                {"type":"object","properties":{
                  "packages":{"type":"array","items":{"type":"string"},"description":"Package names to remove"},
                  "profile":{"type":"string","description":"Profile to remove from (default web)"}
                },"required":["packages"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                val profile = args.string("profile")?.trim().orEmpty().ifBlank { "web" }
                val (text, isError) = engine.bundle(profile, "remove", parsePackages(args.raw))
                McpToolResult(text, isError = isError)
            },
        ),

        // -------------------------------------------------------- sessions
        McpToolDefinition(
            name = "dsh_sessions",
            description = "List recent DeepSeek Harness session logs by id, size and last-modified time. " +
                "Contents are deliberately not decoded: the harness pins its session log at format " +
                "version 0 and promises no compatibility, so anything parsed out of one would break " +
                "without warning. Read a session in the harness's own web UI instead.",
            inputSchema = """
                {"type":"object","properties":{
                  "limit":{"type":"integer","description":"How many to list, newest first (default 20, max 200)"}
                }}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                val limit = (args.int("limit") ?: DEFAULT_SESSION_LIMIT).coerceIn(1, MAX_SESSION_LIMIT)
                val sessions = engine.sessions(limit)
                if (sessions.isEmpty()) {
                    McpToolResult("No sessions under ${DshPaths.sessionsDir()}.")
                } else {
                    McpToolResult(
                        sessions.joinToString("\n") { s ->
                            "${s.id}  ${s.sizeBytes}B  ${Instant.ofEpochMilli(s.modifiedEpochMs)}"
                        },
                    )
                }
            },
        ),
    )

    private fun doctorReport(): String {
        val install = engine.install.value
        val lines = mutableListOf<String>()
        lines += when (install) {
            DshInstall.NodeMissing ->
                "node:    ABSENT - install Node 22.19+ or 24+, then install the harness"
            is DshInstall.DshMissing ->
                "node:    ${install.node.absolutePath}\ndsh:     ABSENT - run `${engine.installCommand()}`"
            is DshInstall.Ready ->
                "node:    present\ndsh:     ${install.dsh.absolutePath} (${install.version})"
        }
        lines += "pnpm:    " + (engine.pnpm.value?.absolutePath ?: "ABSENT - needed only for bundle management")
        lines += "home:    ${engine.home.absolutePath}"
        lines += "profiles: " + engine.profiles.value
            .joinToString(", ") { "${it.name}${if (it.initialized) "" else " (not yet created)"}" }
            .ifBlank { "none" }
        lines += "api key: ${engine.keySource.value.label()}"
        lines += "server:  " + when (val s = engine.server.state.value) {
            is DshServer.Running -> "running on ${s.url}"
            is DshServer.Failed -> "stopped; last failure: ${s.reason}"
            DshServer.Starting -> "starting"
            DshServer.Stopped -> "stopped"
        }
        lines += "boss mcp bridge: " + if (engine.bridgeEnabled.value) "on" else "off"
        return lines.joinToString("\n")
    }

    /**
     * Pull the `packages` array out of the raw arguments.
     *
     * [ai.rever.boss.plugin.api.McpToolArgs] exposes only top-level scalars
     * through its typed getters, and this is an array — so `raw` is the documented
     * route. It is parsed defensively because `raw` is explicitly not guaranteed
     * to be valid JSON: when the client sends something malformed the typed
     * getters see an empty arg set but `raw` still holds the original text.
     */
    internal fun parsePackages(raw: String): List<String> {
        val block = Regex(""""packages"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1) ?: return emptyList()
        return Regex(""""((?:[^"\\]|\\.)*)"""").findAll(block)
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    companion object {
        const val PERMISSION_RUN = "dsh.run"
        const val PERMISSION_MANAGE = "dsh.manage"

        /**
         * The one mutating tool that ships without a permission, and why.
         * `DshMcpToolRbacTest` reads this so the exception lives next to the
         * reason rather than in a test's own copy of the list.
         */
        val UNGATED_MUTATING_TOOLS = mapOf(
            "dsh_open" to "opens a BOSS tab and changes no harness state",
        )

        private const val DEFAULT_ASK_TIMEOUT = 600
        private const val MIN_ASK_TIMEOUT = 10
        private const val MAX_ASK_TIMEOUT = 3600
        private const val DEFAULT_SESSION_LIMIT = 20
        private const val MAX_SESSION_LIMIT = 200
        private const val MAX_DUMP_CHARS = 60_000
    }
}
