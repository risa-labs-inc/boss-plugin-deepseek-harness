# BOSS DeepSeek Harness plugin

Runs [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (`dsh`)
inside BOSS: a sidebar panel that installs and supervises it, a tab that embeds
the harness's own web UI, and `dsh_*` MCP tools so in-terminal agents can drive it.

## What you get

- **A panel** with the harness's readiness (node, `dsh`, pnpm, and versions),
  a Start/Stop control for its server, the profiles under your harness home,
  where its API key is coming from, and the BOSS MCP bridge toggle.
- **A tab** showing the harness's own web UI - streaming, tool calls, approvals,
  session list, model picker - served by the process the panel supervises.
- **MCP tools** so an agent can ask the harness to do work, manage its profiles
  and bundles, and find out why it is not working.
- **Credentials from BOSS.** A DeepSeek key configured on BOSS's AI Providers
  settings page is passed to each harness run through the environment. Nothing is
  written to disk.

## Requirements

- Node 22.19+ or 24+ (the harness's own engine requirement).
- The harness itself, which the panel offers to install (`npm i -g @deepseek-ai/dsh`).
  The first install pulls a large dependency tree and takes a few minutes.
- **pnpm only for bundle management.** `dsh plugin` forwards to pnpm, so the
  panel's Bundles section and the `dsh_bundle_*` tools need it. Everything else
  works without it.
- A DeepSeek API key to run a turn. Without one the harness starts and serves its
  UI, but any turn fails.

## Tools

| Tool | Changes state | Permission |
|---|---|---|
| `dsh_ask` | yes | `dsh.run` |
| `dsh_doctor` | no | - |
| `dsh_web_status` | no | - |
| `dsh_web_start` / `dsh_web_stop` | yes | `dsh.manage` |
| `dsh_open` | opens a BOSS tab only | - |
| `dsh_profiles`, `dsh_dump_config` | no | - |
| `dsh_bundle_add` / `dsh_bundle_remove` | yes | `dsh.manage` |
| `dsh_sessions` | no | - |

`dsh_ask` is gated because a harness turn spends model tokens and, under the
harness's default `workspace-write` preset, can write files anywhere in the
workspace it runs against. `DshMcpToolRbacTest` fails the build if any other
mutating tool ships ungated.

Start with `dsh_doctor` when something is not working. It reports every
precondition in one answer.

### `dsh_dump_config` - pass `row`

The composed tree is hundreds of rows. `row` returns one, with the comment naming
the file that supplied it, which is the part that answers "why does this setting
have this value".

## The BOSS MCP bridge

Off by default. When on, harness agents can call every BOSS tool as
`mcp__boss__*` - the same server BOSS's own in-terminal agents use.

The harness ships an MCP client but enables no server by default, on the grounds
that each server command is trusted executable code outside its agent sandbox.
That reasoning applies here too, which is why this is opt-in and why the panel
repeats it.

The bridge is a `--patch` overlay the plugin owns, at
`$DSH_HOME/boss-overlays/boss-mcp.yml`. Your own `cordis.patch.yml` layers are
never touched. Restart the server to apply a change - bundle and composition
membership is fixed when a profile starts.

Enabling it is a panel action, deliberately not an MCP tool: it widens what the
harness can reach, so it stays a decision a person makes.

## What this plugin depends on, and what it avoids

The harness is in developer preview and says it will make
compatibility-breaking changes. This plugin therefore talks to it only through
surfaces documented in `apps/cli/reference/README.md` - `dsh --profile`,
`dsh web`, `dsh plugin`, `--dump-config` - plus the existence of files under
`$DSH_HOME`.

Two surfaces are avoided on purpose:

- **the `/api` RPC**, which is generated from internal contracts, carries no auth,
  and is explicitly unstable. Embedding the harness's own web UI gets the same
  capability and stays correct across upgrades for free.
- **the session event log**, pinned at format version 0 with no compatibility
  promised. `dsh_sessions` lists files and never decodes one.

A harness release that renames a CLI flag is a one-line fix here. One that
reshapes `/api` costs nothing.

## Development

```bash
./gradlew build            # compile + tests + plugin jar
./gradlew buildPluginJar   # jar only, into build/libs/
```

The API pin lives in `.boss-plugin-api-version`, read by both `build.gradle.kts`
and `test.yml`, so there is no second copy to hand-bump.

Local development compiles against `../boss-plugin-api/build/libs/`. Build that
sibling repo first, or symlink it next to this checkout.

To test in a running BOSS, copy `build/libs/boss-plugin-deepseek-harness-*.jar`
into `~/.boss/plugins` (packaged host) or `~/.boss_debug/plugins` (dev host).
