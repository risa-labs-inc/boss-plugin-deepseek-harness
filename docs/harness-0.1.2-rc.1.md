# Harness 0.1.2-rc.1 compatibility review

Codex self-review fallback for the pin from 0.1.0-rc.7 to 0.1.2-rc.1.
This is a source review and plugin regression test record, not a live harness
re-probe or an independent Claude review.

## Browser authentication requires preserving the launch URL

The published `@deepseek-ai/dsh-web-app@0.1.2-rc.1` (`lib/index.js`,
`announceReady`) prints the URL returned by `connection.authenticatedUrl`.
The published `@deepseek-ai/dsh-client-connection@0.1.2-rc.1`
(`lib/index.js`, `BrowserAuth`) appends `/?token=...`. A fresh GET without that
token or an existing signed cookie receives 401. A valid token receives 303,
`Set-Cookie`, and a redirect to the clean root.

The previous plugin extracted only the port, then probed and opened the clean
origin. It would fail readiness and could not authenticate a fresh browser.
The supervisor now retains the full loopback URL, probes without following the
cookie exchange redirect, and supplies that URL to the embedded browser,
external browser and explicit Copy URL action. Status text, MCP results,
toasts and state `toString()` retain the clean origin. Failure diagnostics redact
tokens. The browser handle is recreated on server replacement, including a
restart that reuses a port with a new token.

`DshWebAuthTest` uses a local HTTP fixture implementing 401/303 exchange semantics
and a fake CLI process to exercise the supervisor. It also tests token retention,
redaction and old tokenless URLs. `DshWebServerParseTest` rejects diagnostic URLs,
non-loopback authorities, malformed ports and unexpected paths.

## Other integration boundaries inspected

- `dsh/lib/bin.js` still accepts `--profile`, `--patch` and dump flags before
  app arguments. `dsh-web-app/lib/startup.js` accepts `--no-open` and `--port 0`.
- `dsh-app-boot/lib/index.js` still initializes `web` and `headless` profiles
  from shipped templates without pnpm; reads `dsh.profile.bundles`; requires
  top-level patch arrays; and appends rows for `insert`. Additional upstream
  SDK/ACP profiles are outside this plugin's advertised web/headless surfaces.
- `dsh-headless/lib/index.js` still prints final text to stdout and exits 0 for
  a completed turn, 1 otherwise. It now streams reasoning to stderr; the plugin
  already drains stderr separately and uses exit status to determine success.
- `dsh-llm-deepseek/lib/index.js` retains `DEEPSEEK_API_KEY` and the
  `MISSING_CREDENTIAL` marker. `dsh-credentials-local/lib/index.js` still gives
  inherited environment credentials precedence over stored values.
- `dsh-llm-pi-ai/lib/index.js` accepts the registrar's provider dictionary and
  `apiKeyEnv` reference. Its dependency `@earendil-works/pi-ai@0.84.2` declares
  Node >=22.19.0, matching the plugin floor. Its published provider modules
  contain all twelve mapped route IDs and none of the eight exact IDs in
  `KNOWN_ABSENT_ROUTES`.
- `dsh-mcp-client/lib/index.js` retains `serverName`, `streamable-http`, `url`
  and the `mcp__<serverName>__*` namespace used by the bridge.

Sources were the npm registry metadata and published tarballs for the exact
versions above. The dsh and DeepSeek package tarballs were checked against their
registry SHA-512 integrity values before inspection. The pre-PR Harness pin
workflow separately passed install/version checks on macOS, Linux and Windows
at Node 22.19.0:
https://github.com/risa-labs-inc/boss-plugin-deepseek-harness/actions/runs/34092701113

## Remaining validation

The harness and BOSS were not launched, and no plugin was installed or reloaded.
A real browser cookie exchange, real provider turns, and the PR's live behavior
re-probe checklist still need validation before merge. Static route inspection
is not a live UNKNOWN_MODEL/NO_ADAPTER probe. The npm top-level pin also does not
freeze upstream's ranged transitive dependencies.
