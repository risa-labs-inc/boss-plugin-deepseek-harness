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
DshFailure         a harness death rattle -> one line someone can act on
DshCredentials     DEEPSEEK_API_KEY from BOSS -> child env
DshMcpBridge       the opt-in cordis patch overlay
DshMcpTools        the dsh_* tools
DshPaths           $DSH_HOME + the plugin's own npm prefix (pure - creates nothing)
DshNodeResolver    which of the machine's `node` binaries the harness runs on
DshIcon            the DeepSeek whale, shared by the panel and the tab
DshSecretSync      which BOSS secrets to inject, and the defaults
DshProviderRegistrar  registers provider routes using the installed CLI settings protocol
```

## Verified facts about the harness

The original probes used `dsh 0.1.0-rc.7`. The 0.2.0-rc.2 upgrade was re-probed
with Node 22.19.0 in isolated homes: authenticated web startup, boot-free config
composition, provider catalogs, first-turn legacy/default preservation, native
Models editing, and profile-write rollback. Successful live model inference was
verified on 0.1; 0.2 provider probes used nonexistent models and no live keys.
Re-probe after another harness upgrade.

- **`dsh web` prints its bound localhost URL.** On 0.2 it includes a session
  query: `dsh web: http://127.0.0.1:62375/?token=<session-token>`. Older CLI
  versions print a bare URL and remain supported. With `--port 0` the OS picks;
  reading the readiness line avoids a pre-bound `ServerSocket` race. The startup
  probe uses the authenticated URL and accepts its cookie-setting redirect
  without following it. The full URL is private navigation data; observable
  state, MCP output, and UI labels expose the bare port/URL without the token.
- **`--profile headless "<task>"`** on the original 0.1 probe prints final assistant text on stdout,
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
- **`$DSH_HOME` defaults to `~/.dsh`**, with `profiles/`, `sessions/`, and
  `.credentials.yaml`. CLI 0.1 reads `settings.yaml`; CLI 0.2 imports it once
  into the active profile then archives it. See Settings protocol compatibility.
- **`.credentials.yaml` is a flat mapping of `NAME: "string"` and nothing else.**
  `dsh-credentials-local`'s `parseCredentialsDocument` rejects, rather than skips,
  a non-mapping root, a key outside `/^[A-Za-z_][A-Za-z0-9_]*$/`, a non-string
  value, an empty string, a duplicate key, and a file any other OS user can read.
  Each of those aborts `[cordis.init]`, which fails the profile's `#credentials`
  include, which fails the whole plugin tree - so **one stray line in that file
  means `dsh web` does not boot at all**. Reported from the field as
  `the value for "version" in ~/.dsh/.credentials.yaml must be a string`, from a
  hand-added `version: 1`. The plugin does not write this file and never has;
  `DshFailure` says so in the remedy, because a BOSS dialog naming a file the user
  never opened reads as BOSS having written it.

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
- **A Node stack trace's useful part is at the FRONT.** The opposite of a JVM
  trace, and `DshWebServer.failureText` used to keep the last twelve lines "because
  a stack trace's useful part is at the end". Node prints the message first and
  frames after it, then repeats the message once per `[cause]` - so the tail was
  the one slice guaranteed to say nothing, and the field report was an error dialog
  opening on `at Entry._init (file:///...cordis-plugin-loader/lib/index.js:519:10) {`.
  Every transcript now goes through `DshFailure.explain`.
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

`DshSecretSync` picks which BOSS secrets to inject. `DshProviderRegistrar` uses
`settings.yaml` for CLI 0.1; CLI 0.2 uses profile-owned `cordis.patch.yml`.
Resolve credential values once per launch and register only names that are
actually in that child's environment. Neither writer changes the default vendor
when adding a provider route.

### Route names are PROBED, never guessed

A route pi-ai does not ship registers **no adapter at all** and fails only when a
request reaches it (`NO_ADAPTER: no adapter registered for provider "x"`). It does
NOT fail at boot, so a wrong name is a silent misconfiguration that surfaces later
as a broken harness.

The original probe used `dsh 0.1.0-rc.7`; all entries were re-probed against
0.2.0-rc.2 using a direct patch plus `agent-default-model` naming a nonexistent
model. `UNKNOWN_MODEL` means the route resolved and its catalog was consulted.
Unshipped routes reported `NO_ADAPTER` on 0.1 and `INVALID_CONFIG` on 0.2.
Close stdin and capture complete stderr; no request needs a live credential.

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

### Settings protocol compatibility

The old CLI's settings writer remains for **0.1.x**, including installations
outside the plugin's npm prefix. It only rewrites simple `apiKeyEnv` dictionaries,
refuses custom options or comments, and takes a backup. Canonical key spelling
wins when several injected names resolve to one provider route.

**0.2.0-rc.2 removed the active global settings layer.** Its legacy importer runs
after mounting and imports `settings.yaml` into only the first launched profile,
then renames it to `settings.yaml.imported`. Merely writing a new global settings
file can therefore affect the second turn rather than the first, and opening web
first leaves headless without the user's former default vendor. Do not use a
fake AI turn to force migration.

`DshProviderRegistrar.registerProfile` initializes the target profile with the
boot-free `--dump-config` path, then runs the packaged
`META-INF/boss-plugin/dsh-profile-update.mjs` helper on the resolved Node. The helper
uses the installed harness's own parser, composition, file-lock, and atomic-write
libraries. It copies legacy settings into each profile once, preserves an explicit
user model/vendor selection, and adds only missing provider routes. Existing
provider endpoints, models, retry settings, comments and `!!js` nodes survive.
Unrecognized or unsafe document shapes are refused rather than guessed at.

**Provider registration must not use `--patch` config overlays.** The include
loader replaces the whole targeted row config, and the native config editor then
rejects Models-page changes because that row is overridden by a CLI layer. The
helper updates the profile-owned patch, so native controls remain editable. Home
patch overrides remain authoritative. The BOSS MCP overlay is independent and
still passed through `--patch`.

The helper writes private backups and migration markers. POSIX owner-only modes
are enforced; Windows retains the user directory's inherited ACL and applies JDK
permission flags as best-effort hints. Its temporary script is removed after completion. Its output
contains fixed status, route names, the selected model label, and environment
variable references only. Never return raw Node exceptions or composed YAML to
the panel because configuration can contain credentials.

`--inspect` reads current composed profile metadata without updating a profile,
backup, or migration marker. Refresh uses this for CLI 0.2 so the doctor and key
panel report current Models-page choices after the global settings file has been
archived. Returning to CLI 0.1 restores the legacy metadata reader.
An initialized profile needs only the inspection helper; it skips the extra CLI
config-composition process. A missing profile first uses boot-free initialization.

The regular PR workflow runs `src/test/resources/dsh-profile-update.test.mjs`
against the exact pinned CLI on macOS, Linux, and Windows with Node 22.19.0. The
harness-bump workflow runs those fixtures against its installed candidate too.
Locally, set `DSH_TEST_PACKAGE_ROOT` to the isolated installed package directory
and `DSH_TEST_REQUIRE_PACKAGE=true`, then run
`node --test src/test/resources/dsh-profile-update.test.mjs` using Node 22.19.0.
The Kotlin route-parity tests also compare the packaged helper's allowlist and
canonical credential preferences against the registrar's tables. Changing the
Kotlin `OPENAI_KEY` mapping to a different valid route compiled and failed the
named allowlist parity test; restoring it passed the focused suite.

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

**The bridge is ON by default**, and the overlay is written fresh at every
harness launch rather than read off disk. Those two are one change. Default-off
could afford to resolve the endpoint once in `DshServices.start()`, because a
user who had just clicked the toggle was demonstrably looking at a running host.
Default-on cannot: plugin load order is not guaranteed, so BOSS's MCP server may
not answer when this plugin loads, and the result would be a toggle reading "on"
while the harness silently got nothing. `DshEngine.mcpEndpoint` is a seam
`DshServices` fills in; `bridgeOverlay()` resolves through it per launch and
passes **no** overlay when nothing answers - never a stale one, because a row the
harness cannot initialise stops `dsh web` booting at all.

`DshServices.BRIDGE_DEFAULT` is the stored default and only applies to an install
that never touched the toggle: `setBridgeEnabled` writes the pref in both
directions, so a user's "off" is a stored value, not an absent one, and survives.

The security argument in the harness's own docs is about *arbitrary* MCP servers.
This bridge points at exactly one - BOSS's own, the same server every in-terminal
agent in this app already talks to, RBAC-gated at the host end. Nothing new
becomes reachable that the user has not already granted to the agents beside it.

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

Enabling the BOSS MCP bridge is **not** a tool, on purpose: it is on by default,
and turning it off is a decision a person makes in the panel, not one an agent
makes for them.

`dsh_dump_config` takes a `row` filter. The full tree is ~15k tokens; prefer the
filter when answering a question about one setting.

## Testing

```bash
./gradlew build   # 200 JVM tests
```

Count results from `build/test-results/test/*.xml`, not from "BUILD SUCCESSFUL" -
a `test` task with no sources is NO-SOURCE and passes.

The regression guards have been shown to fail against real mutations:

- dropping `dsh_ask`'s permission fails 2 tests in `DshMcpToolRbacTest`
- switching the overlay to the bare-id override form fails
  `DshBridgeOverlayTest`
- removing the modern profile-preservation launch guard fails
  `DshProfileLaunchGuardTest` before an unverified vendor/default can run
- probing the bare web URL instead of the authenticated URL fails
  `DshWebAuthenticationTest`
- changing Kotlin's `OPENAI_KEY` route to another valid route fails the allowlist
  test in `DshProviderRouteParityTest`
- returning raw failed config-dump output fails the diagnostic test in
  `DshProfileLaunchGuardTest`
- reverting `DshNodeResolver` to first-match fails 6 of 10 in
  `DshNodeResolverTest`
- pointing the Install button back at `setPendingSidebarCommand` fails
  `DshInstallTerminalTest`
- latching `awaitInstalled` on the binary appearing, without re-probing, fails 2
  in `DshAwaitInstalledTest`
- restoring `failureText`'s last-twelve-lines rule, and making `DshFailure.explain`
  a passthrough, fails 10 of 14 in `DshFailureTest`
- defaulting the bridge back to off, and reading the overlay off disk instead of
  rewriting it per launch, fails 5 of 8 in `DshBridgeDefaultTest`

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
