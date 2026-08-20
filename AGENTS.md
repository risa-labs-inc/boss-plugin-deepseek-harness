# AGENTS.md - BOSS DeepSeek Harness plugin

Orientation for coding agents. [README.md](README.md) is the user-facing
description; this file is the things that will bite you.

## What this is

A BOSS plugin (`type: "mixed"` - one sidebar panel, one tab) that supervises
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) as a child
process, embeds its web UI, and exposes `dsh_*` MCP tools.

Modelled on `boss_plugins/docker`, which solves the same shape: shell out to a
CLI, embed a localhost web UI in a tab, RBAC-gate the mutating tools.

```
DshDynamicPlugin   entry point: panel + tab + MCP provider
DshServices        per-activation holder; tab opening, toasts, prefs
DshEngine          all state (StateFlow) and every operation
DshCli             THE only place a process is started
DshProcesses       killing a process and its descendants
DshWebServer       spawn / await / reap `dsh web`
DshCredentials     DEEPSEEK_API_KEY from BOSS -> child env
DshMcpBridge       the opt-in cordis patch overlay
DshMcpTools        the dsh_* tools
DshPaths           $DSH_HOME resolution (pure - creates nothing)
```

## Verified facts about the harness

Probed against `dsh 0.1.0-rc.7`, not read off docs. Re-probe before trusting any
of these after a harness upgrade.

- **`dsh web` prints exactly one stdout line:** `dsh web: http://127.0.0.1:62375`.
  With `--port 0` the OS picks and this line is the only way to learn the port.
  Do not go back to pre-binding a `ServerSocket(0)` - it has a race the harness's
  bind can lose, and the failure looks like a user error.
- **`--profile headless "<task>"`** prints the final assistant text on stdout,
  exit 0 for a completed turn and 1 otherwise, diagnostics on stderr. Nothing on
  stderr on success.
- **No key gives** exit 1 and
  `dsh: MISSING_CREDENTIAL: llm-deepseek: no API key for provider route "deepseek-official"; ...`.
  `DshCredentials.MISSING_MARKER` matches on `MISSING_CREDENTIAL`; the raw text is
  mapped to a BOSS-specific remedy rather than passed through.
- **`web` and `headless` self-initialize** from shipped templates on first use,
  and need no pnpm. Any *other* profile fails boot loud. `DshPaths.SHIPPED_PROFILES`
  encodes this; do not pre-create a profile directory - that turns a loud, fixable
  error into a confusing half-state.
- **A profile's bundle list is in its own `package.json`** under
  `dsh.profile.bundles`, so it can be read without pnpm.
- **The inherited process environment outranks every credential file layer.** This
  is why the key is injected into the child env and never written to
  `.credentials.yaml`. Stated in the harness's `dsh-credentials-local` README.
- **A patch layer is a top-level YAML array.** `- insert:` adds a row; a bare
  top-level `id` *overrides* an existing row and silently does nothing when none
  exists. Getting this wrong yields a green toggle and no tools.
- **SIGTERM is the harness's ordinary stop:** it drains up to 5s and exits 0.
  SIGINT reports 130.
- **`$DSH_HOME` defaults to `~/.dsh`**, with `profiles/`, `sessions/`,
  `settings.yaml`, `.credentials.yaml`.

## Traps

- **Never a bare command name.** `ProcessBuilder` resolves against the *parent*
  PATH, which is nearly empty when the packaged host launches from Finder. This is
  not hypothetical here: on the verification machine `dsh` lives at
  `/opt/homebrew/bin/dsh` and a bare `"dsh"` fails in the shipped app while
  working in dev. Everything goes through `DshCli.which` and gets a widened child
  PATH.
- **Never a shell string.** Task text comes from a model. `DshCli.exec` takes a
  `List<String>` so there is no shell to inject into. `DshCliTest` pins this with
  a hostile task.
- **Drain stdout and stderr concurrently.** Reading them in sequence deadlocks the
  moment the child fills the pipe nobody is reading. Pinned by a 4000-line test.
- **Kill descendants, and snapshot them first.** A dead parent's descendants are
  reparented away and can no longer be enumerated from it, so
  `DshProcesses.terminate` collects the list *before* destroying the parent.
- **`dispose()` cannot suspend.** `DshWebServer.disposeNow` deliberately skips the
  mutex: a stop launched into a scope that is being cancelled leaves the process
  running. The shutdown hook is the backstop for the *disabled*-plugin path, which
  never calls `dispose()` at all.
- **A null value in `extraEnv` removes the variable**; an empty string does not.
  The harness treats a set-but-empty `DEEPSEEK_API_KEY` as the inherited layer
  having supplied one, which would shadow a working key the user stored through
  the harness's own Models page.
- **Never put the key in a data class.** `DshCredentials.resolve` returns it;
  `describe` returns only a `DshKeySource`. A data class whose components include
  a credential prints it from its own `toString()` into any log line that touches
  it - that has happened in this workspace before.
- **`openTab` is fire-and-forget** and is silently dropped when no factory is
  registered. `DshServices.openWebTab` polls `activeTabsProvider` to confirm,
  because otherwise "Opened the tab" can be a lie.
- **`PanelId` order must match the manifest.** The host's registry keys on the
  whole `PanelId`, so a mismatch registers a panel that `openPanel` can never
  find - a silent miss, not an error.
- **The default `:jar` task would clobber `buildPluginJar`.** `tasks.jar` carries a
  `thin` classifier so the two cannot collide; without it you can ship a jar that
  loads with no panel and no tools.

## MCP tools

RBAC lives in the manifest (`dsh.run`, `dsh.manage`) and is asserted in
`DshMcpToolRbacTest` against the real tool objects. `dsh_open` is the one
`readOnly = false` tool without a permission, recorded with its reason in
`DshMcpToolProvider.UNGATED_MUTATING_TOOLS` - the test fails if that entry names
a tool that no longer exists or has become read-only.

Enabling the BOSS MCP bridge is **not** a tool, on purpose. It widens what the
harness can reach, so it stays a panel action a person takes.

`dsh_dump_config` takes a `row` filter. The full tree is ~15k tokens; prefer the
filter when answering a question about one setting.

## Testing

```bash
./gradlew build   # 49 tests
```

Count results from `build/test-results/test/*.xml`, not from "BUILD SUCCESSFUL" -
a `test` task with no sources is NO-SOURCE and passes.

Both central guards have been shown to fail against a real mutation:

- dropping `dsh_ask`'s permission fails 2 tests in `DshMcpToolRbacTest`
- switching the overlay to the bare-id override form fails
  `DshBridgeOverlayTest`

Do that again for any new guard. Two regression tests in this workspace's history
passed against their own bug.

`FakeServices` answers null for every host provider, which is the hostile case:
anything passing against it also survives a host with no browser engine, no
secrets, no terminal and no project open.

## Not verified

**No model turn has ever run through this plugin.** The verification machine has
no DeepSeek key, so `dsh_ask` has only been exercised down its
`MISSING_CREDENTIAL` path. The first real turn will be a user's. One
`dsh_ask` with a key configured retires this.
