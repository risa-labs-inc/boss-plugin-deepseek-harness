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
DshPaths           $DSH_HOME + the plugin's own npm prefix (pure - creates nothing)
DshNodeResolver    which of the machine's `node` binaries the harness runs on
DshIcon            the DeepSeek whale, shared by the panel and the tab
DshSecretSync      which BOSS secrets to inject, and the defaults
DshProviderRegistrar  writes provider routes into the harness's settings.yaml
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
- **`which` is the wrong question for `node`.** A machine has several, and the
  harness has a *minimum version*. First-match-on-PATH picked an nvm 18.16.0 that
  was shadowing a Homebrew 26.7.0 on the reporting machine - the install then died
  in a postinstall on `import.meta.resolve is not a function`, and the first fix
  for that told the user to upgrade a Node they already had. `DshNodeResolver`
  probes every candidate from `DshCli.whichAll("node")`; `DshInstall.NodeTooOld`
  means *none* qualified, which is the only case where "upgrade Node" is honest.
- **The Node floor is fail-open.** An unreadable `node --version` is a reason to
  stay out of the way, not to disable a machine - a false `NodeTooOld` hides a
  working setup behind a wrong error, which is worse than the npm failure the
  floor exists to pre-empt. `DshNode.parse` returns null and the resolver ranks
  unreadable below known-good but above known-too-old.
- **Never `npm install -g` without `--prefix`.** The harness goes into
  `DshPaths.toolchainDir` ($DSH_HOME/boss-toolchain), which the plugin owns. A
  real global install lands in whichever Node's prefix is selected - so it
  vanishes when the user switches Node, may need sudo, and cannot be removed with
  the plugin.
- **npm's prefix layout differs on Windows.** `--prefix <dir>` links
  `<dir>/bin/dsh` on Unix but shims `<dir>\dsh.cmd` in the prefix *root*, with no
  `bin` at all. `DshPaths.toolchainExecDirs` returns both and everything searches
  both; looking only in `bin` installs fine on Windows and then reports the
  harness missing.
- **An install in a terminal reports nothing back.** `DshEngine.awaitInstalled`
  watches the prefix for `bin/dsh` and only then re-probes; without it the panel
  sat on "not installed" until the user pressed Refresh. It must re-probe rather
  than latch on the file: npm links `bin/` before postinstalls finish, so the
  binary exists for a few seconds before it will answer `--version`.
- **The harness version is pinned, not `@latest`.** Everything under "Verified
  facts" was probed against one release, so `@latest` means those facts describe
  whatever npm served that day. `DshCli.PINNED_VERSION` is moved by
  `.github/workflows/harness-bump.yml`, which installs the candidate on all three
  OSes at the declared Node floor and proves the binary runs before opening a PR.
  A green run there means it installs - not that the probed behaviour still holds,
  which is why the PR body carries a re-probe checklist.
- **One child PATH, not two.** `DshWebServer` used to build its own, missing the
  toolchain-manager directories and later the resolved Node, so `dsh --version`
  could be probed with one Node and `dsh web` spawned with another. Both go
  through `DshCli.childPath()` now.
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

## Provider keys and routes

Two halves, deliberately split the way the harness itself splits them: the
harness owns provider *registration*, BOSS owns the *credential*. Its
`llm-pi-ai` README is explicit that `apiKeyEnv` is a credential reference and
"no secret enters this file", so nothing here ever writes a secret to disk.

`DshSecretSync` picks which BOSS secrets to inject. `DshProviderRegistrar` writes
the matching routes into `$DSH_HOME/settings.yaml`.

### Route names are PROBED, never guessed

A route pi-ai does not ship registers **no adapter at all** and fails only when a
request reaches it (`NO_ADAPTER: no adapter registered for provider "x"`). It does
NOT fail at boot, so a wrong name is a silent misconfiguration that surfaces later
as a broken harness.

The probe, against `dsh 0.1.0-rc.7`: set one route plus `agent-default-model`
naming a nonexistent model, run one turn with stdin closed, and read stderr.
`UNKNOWN_MODEL` means the route resolved and its catalog was consulted; `NO_ADAPTER`
means pi-ai ships nothing under that key.

| Verdict | Routes |
|---|---|
| valid | `openai` `anthropic` `deepseek` `google` `xai` `together` `mistral` `groq` `openrouter` `fireworks` `cerebras` `nvidia` |
| **not shipped** | `gemini` `grok` `togetherai` `cohere` `perplexity` `moonshot` `azure` `bedrock` |

The three traps are `gemini` (it is `google`), `grok` (it is `xai`) and
`togetherai` (it is `together`) - all three read like the obvious name and ship
nothing. `DshProviderRegistrarTest` fails if any mapped route is outside
`VERIFIED_ROUTES` or inside `KNOWN_ABSENT_ROUTES`. Re-probe after a dsh upgrade.

**Probe gotcha:** `dsh` inside a shell loop consumes the loop's stdin and exits
before writing anything, so the first two enumeration runs came back uniformly
empty. Redirect `</dev/null` and capture stderr to a *file* - piping to `head`
closes the pipe before dsh writes and loses the line too.

### Why keys default on, but not all of them

The user asked for keys to work without ticking a box each, so a recognised
provider key defaults ON. It is an explicit allowlist (`PROVIDER_KEYS`), not a
`*_KEY` pattern, because a real secret store also holds `MACOS_P12_CERTIFICATE`,
`GPG_SIGNING_KEY`, `SUPABASE_SERVICE_ROLE_KEY` and ~25 CI secrets whose names end
the same way. An allowlist fails closed: an unknown provider shows up off rather
than a certificate showing up on. Swapping it for `endsWith("_KEY")` fails three
tests.

Two secrets claiming one variable name default OFF rather than being guessed
between - a wrong API key fails as though the provider rejected you.

`DshKeySelection` is **two** override sets, not one selected-set. With a default
of *on*, "off" is a real state: a single set makes it indistinguishable from
"never chose", so a key turned off returns at the next launch.

### Writing settings.yaml, and when it refuses

There is no YAML parser in the host, and pulling one in would round-trip the whole
document through a serializer that drops comments and reorders keys - worse for a
config file than a targeted edit. So the registrar only writes when the existing
`llm-pi-ai` block round-trips exactly: `{ route: { apiKeyEnv: NAME } }` shapes and
nothing else. A `models` list, `compat`, `retryPolicy`, `baseURL` or
`modelOverrides` makes it refuse and hand back the YAML to paste. A backup is
taken before any write.

It never touches `agent-default-model`: registering a provider is not switching
which vendor bills the next turn.

**Two bugs that only showed up when run against a real file**, both now pinned:
the route-name regex matched `providers:` itself, so the parsed set never equalled
the found set and it refused the commonest shape; and several env names mapping to
one route were deduped with `distinctBy` over a Set, which on a real store picked
a colleague's `OPEN_AI_API_KEY` over the user's own `OPENAI_API_KEY`. There is now
a canonical spelling per route.

### The BOSS MCP bridge: ports and both launch paths

**7677 is BossTerm's MCP server, not BOSS's.** Probed on a live machine:

| Port | `initialize` reports |
|---|---|
| 7677 | `bossterm` |
| **7679** | **`boss`** |
| 7680 | `boss` (second instance / fallback) |

The first version hardcoded 7677 plus an invented `BOSS_MCP_PORT` env var the host
never sets, so the bridge pointed at the wrong server and no BOSS tool ever
reached the harness. Never hardcode a port: ask
`McpServerController.state`, which reports the **bound** port (its own doc notes
it may be a fallback) and the server name. The name matters too - the same server
answers to `boss` inside BOSS and `bossterm` standalone, and the model-facing
`mcp__<name>__*` prefix follows it. Resolve per call, never at `register()`:
terminal-tab may not have loaded yet.

**The overlay must reach BOTH launch paths.** It was passed to `dsh web` only, so
the web UI could call every BOSS tool while `dsh_ask` reported having none -
silent, since the tools were merely absent. `headlessArgv` is extracted for that
reason and `DshHeadlessArgvTest` pins it, including that `--patch` precedes the
task (a launcher flag after the task reaches the app, which does not know it).

Verified live: with the bridge on, a headless turn lists 187 `mcp__boss__*` tools.

**Consequence of env-only injection, now observed:** a route BOSS registered fails
in a plain terminal. `dsh --profile headless` run from a shell gives
`MISSING_CREDENTIAL: llm-pi-ai: no credential for provider route "openai"; its
profile resolves OPENAI_API_KEY, which is not set`, because only BOSS injects it.
That is the accepted trade of keeping secrets off disk.

## The icon

`DshIcon.kt` holds the DeepSeek whale as an `ImageVector`, aliased once and used
by the panel, the tab type and the tab info - `DshIconTest` fails if those three
ever point at different vectors.

- **It is hand-carried because nothing supplies it.** The `simple-icons` Compose
  port the host bundles is 1.1.1, which predates DeepSeek, and Material has no
  whale. Note that boss-plugin-docker's icon is *also* a whale, so the two sit
  side by side in the sidebar; this is the DeepSeek silhouette, not a second
  Docker.
- **The path string is upstream's `deepseek.svg`, verbatim, and parsed at build
  time** via `PathParser` rather than transcribed into `PathBuilder` calls. The
  outline has 15 elliptical arcs; hand-converting ~2,000 characters of arc
  parameters corrupts silently. Keeping the string intact also means a logo change
  is a copy-paste and can be diffed against upstream.
- **Opaque black at 24x24**, matching the `simple-icons` convention, because
  callers tint it. Baking in DeepSeek blue would ignore `Icon`'s tint and render
  the same colour in both themes, which is what makes a sidebar item's selected
  and disabled states read wrong.
- **A truncated path still builds and still draws something**, so
  `DshIconTest` asserts a node-count floor. Verified by truncating the literal to
  3 of its 22 chunks: the test fails.
- **The manifest's `panel.icon` string is not what you see.** Nothing on the
  in-process path reads it - `iconName` is consumed only by
  `RemoteUiSurfaceRegistry` (the out-of-process UI path) and a host test. The real
  icon is `PanelInfo.icon`. It is left as a valid Material name so it always
  resolves; do not "fix" the apparent mismatch by hunting for a whale that is not
  there.

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
./gradlew build   # 151 tests
```

Count results from `build/test-results/test/*.xml`, not from "BUILD SUCCESSFUL" -
a `test` task with no sources is NO-SOURCE and passes.

Both central guards have been shown to fail against a real mutation:

- dropping `dsh_ask`'s permission fails 2 tests in `DshMcpToolRbacTest`
- switching the overlay to the bare-id override form fails
  `DshBridgeOverlayTest`
- reverting `DshNodeResolver` to first-match fails 6 of 10 in
  `DshNodeResolverTest`
- pointing the Install button back at `setPendingSidebarCommand` fails
  `DshInstallTerminalTest`
- latching `awaitInstalled` on the binary appearing, without re-probing, fails 2
  in `DshAwaitInstalledTest`

Do that again for any new guard. Two regression tests in this workspace's history
passed against their own bug.

`FakeServices` answers null for every host provider, which is the hostile case:
anything passing against it also survives a host with no browser engine, no
secrets, no terminal and no project open.

## Not verified

The web UI's **provider-keys panel section has not been seen rendered** - the
detection logic is tested and the doctor output confirms the wiring, but nobody
has looked at the switches.

Everything else here has run end to end against a real harness: a model turn
completes (`dsh_ask`), keys inject, and routes register into a real
`settings.yaml`. The turn ran on a **Google** provider configured through the
harness's own Models page, not on DeepSeek - there is still no DeepSeek key on the
build machine, so `dsh_ask`'s DeepSeek-specific `MISSING_CREDENTIAL` remapping has
only been exercised down its failure path.
