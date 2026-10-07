/**
 * Run against the exact installed CLI dependencies, without keys or inference:
 * DSH_TEST_PACKAGE_ROOT=/private/npm/lib/node_modules/@deepseek-ai/dsh \
 *   node --test src/test/resources/dsh-profile-update.test.mjs
 * The ordinary JVM suite verifies the registrar wrapper separately.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm, stat, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { isDeepStrictEqual } from 'node:util';

const packageRoot = process.env.DSH_TEST_PACKAGE_ROOT;
if (process.env.DSH_TEST_REQUIRE_PACKAGE === 'true' && !packageRoot) {
  throw new Error('DSH_TEST_PACKAGE_ROOT is required for the CI compatibility check');
}
const helper = resolve(dirname(fileURLToPath(import.meta.url)), '../../main/resources/META-INF/boss-plugin/dsh-profile-update.mjs');
const enabled = { skip: !packageRoot };
const require = packageRoot ? createRequire(join(packageRoot, 'package.json')) : null;
const load = name => import(pathToFileURL(require.resolve(name)).href);

async function fixture(operation) {
  const home = await mkdtemp(join(tmpdir(), 'boss-profile-update-'));
  const api = await load('@deepseek-ai/dsh-app-boot');
  // The public initializer is the same one the CLI uses on shipped profiles.
  api.initProfile(join(home, 'profiles', 'web'), api.PROFILE_TEMPLATES.web.bundles);
  api.initProfile(join(home, 'profiles', 'headless'), api.PROFILE_TEMPLATES.headless.bundles);
  try { await operation(home, api); }
  finally { await rm(home, { recursive: true, force: true }); }
}
function invoke(home, profile = 'headless', names = 'OPENAI_API_KEY', inspect = false, preload) {
  return new Promise((resolvePromise, reject) => {
    const args = [...(preload ? ['--import', pathToFileURL(preload).href] : []), helper, packageRoot, home, profile, names,
      ...(inspect ? ['--inspect'] : [])];
    // No inherited provider credentials; the helper only needs installed files.
    const child = spawn(process.execPath, args, {
      env: { PATH: dirname(process.execPath), HOME: home, DSH_HOME: home }, stdio: ['ignore', 'pipe', 'pipe'],
    });
    // Cold Windows file scanning can delay dependency imports. Keep a bounded
    // budget without treating that startup delay as a compatibility failure.
    const timer = setTimeout(() => child.kill('SIGKILL'), 60_000);
    let stdout = '', stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.on('error', reject);
    child.on('close', code => {
      clearTimeout(timer);
      try {
        assert.equal(stderr, '', 'never expose parser/config/exception contents');
        resolvePromise({ code, stdout });
      } catch (error) { reject(error); }
    });
  });
}
const patchPath = (home, profile = 'headless') => join(home, 'profiles', profile, 'cordis.patch.yml');

test('first registration preserves default model, backup, permissions and idempotence', enabled, async () => fixture(async home => {
  const original = await readFile(patchPath(home), 'utf8');
  const result = await invoke(home);
  assert.equal(result.code, 0);
  assert.match(result.stdout, /^ADDED\topenai\n/);
  assert.match(result.stdout, /MODEL\tdeepseek-official\tdeepseek-flash/);
  const updated = await readFile(patchPath(home), 'utf8');
  assert.match(updated, /apiKeyEnv: OPENAI_API_KEY/);
  assert.doesNotMatch(updated, /agent-default-model/);
  assert.equal(await readFile(join(home, 'boss-overlays/profile-migrations/headless.cordis.patch.yml.boss-backup'), 'utf8'), original);
  if (process.platform !== 'win32') {
    assert.equal((await stat(patchPath(home))).mode & 0o777, 0o600);
    assert.equal((await stat(join(home, 'boss-overlays/profile-migrations'))).mode & 0o777, 0o700);
  }
  assert.match((await invoke(home)).stdout, /^UP_TO_DATE\n/);
  assert.equal(await readFile(patchPath(home), 'utf8'), updated);
}));

test('existing models, options, comments, expression tags and unrelated entry fields survive', enabled, async () => fixture(async home => {
  await writeFile(patchPath(home), `# retained document comment
- id: llm-pi-ai
  name: '@deepseek-ai/dsh-llm-pi-ai'
  disabled: false # retained option
  config:
    # retained providers comment
    providers:
      google:
        apiKeyEnv: GOOGLE_API_KEY # retained env comment
        baseURL: https://example.invalid/v1
        models: [{id: custom-model, contextWindow: 123456}]
    retryPolicy:
      attempts: 4 # retained retry comment
    expression: !!js 'ctx.probe'
`);
  assert.match((await invoke(home)).stdout, /^ADDED\topenai/);
  const text = await readFile(patchPath(home), 'utf8');
  for (const preserved of ['retained document comment', 'retained option', 'retained providers comment',
    'retained env comment', 'retained retry comment', '!!js', 'ctx.probe', 'custom-model', '123456',
    'https://example.invalid/v1', 'OPENAI_API_KEY']) assert.ok(text.includes(preserved), preserved);
}));

test('legacy migration survives web-first archival, with later profile edits taking precedence', enabled, async () => fixture(async home => {
  const legacy = `llm-pi-ai:
  providers:
    openai:
      apiKeyEnv: OPENAI_API_KEY
      baseURL: https://example.invalid/v1
agent-default-model:
  provider: openai
  model: missing-test-model
shell:
  timeoutMs: 43210
`;
  await writeFile(join(home, 'settings.yaml'), legacy);
  const untouchedProfile = await readFile(patchPath(home, 'web'), 'utf8');
  assert.match((await invoke(home, 'web', '', true)).stdout, /MODEL\topenai\tmissing-test-model/);
  assert.equal(await readFile(patchPath(home, 'web'), 'utf8'), untouchedProfile);
  assert.equal((await readdir(home)).includes('boss-overlays'), false);
  assert.match((await invoke(home, 'web')).stdout, /MODEL\topenai\tmissing-test-model/);
  assert.equal(await readFile(join(home, 'settings.yaml'), 'utf8'), legacy);
  await writeFile(join(home, 'settings.yaml.imported'), legacy);
  await rm(join(home, 'settings.yaml'));
  assert.match((await invoke(home)).stdout, /MODEL\topenai\tmissing-test-model/);
  const migratedHeadless = await readFile(patchPath(home), 'utf8');
  assert.ok(migratedHeadless.includes(`id: ${process.platform === 'win32' ? 'pwsh-sandbox' : 'bash-sandbox'}`));
  assert.match(migratedHeadless, /timeoutMs: 43210/);
  const webPath = patchPath(home, 'web');
  await writeFile(webPath, (await readFile(webPath, 'utf8')).replace('missing-test-model', 'later-user-model'));
  assert.match((await invoke(home, 'web')).stdout, /MODEL\topenai\tlater-user-model/);
  assert.match(await readFile(webPath, 'utf8'), /later-user-model/);
}));

test('native ConfigEditor composition permits subsequent Models edits', enabled, async () => fixture(async (home, api) => {
  await invoke(home);
  const profileDir = join(home, 'profiles', 'headless');
  const packageJson = join(packageRoot, 'package.json');
  const loaded = api.loadProfileDirectory('dsh', profileDir, packageJson);
  const next = { providers: { openai: { apiKeyEnv: 'OPENAI_API_KEY' }, anthropic: { apiKeyEnv: 'ANTHROPIC_API_KEY' } } };
  const proposed = { id: 'llm-pi-ai', name: '@deepseek-ai/dsh-llm-pi-ai', config: next };
  const context = { dir: profileDir, home, installAnchor: packageJson, patchPath: patchPath(home), overlays: [] };
  // Exact pre-write validation in upstream ConfigEditor.edit: profile-owned
  // config passes; moving the same config to --patch would freeze Models edits.
  const validate = () => isDeepStrictEqual(api.composeEntries([api.readProfilePatches('dsh', context,
    { ...loaded, patches: [proposed] })]).find(row => row.id === 'llm-pi-ai')?.config ?? {}, next);
  assert.equal(validate(), true);
  context.overlays = [{ ...proposed, config: { providers: { openai: { apiKeyEnv: 'OPENAI_API_KEY' } } } }];
  assert.equal(validate(), false);
}));

test('inspect is read-only, including absence of plugin-owned directories', enabled, async () => fixture(async home => {
  const before = await readFile(patchPath(home), 'utf8');
  const result = await invoke(home, 'web', '', true);
  assert.match(result.stdout, /^UP_TO_DATE\nMODEL\t/);
  assert.equal(await readFile(patchPath(home), 'utf8'), before);
  assert.equal((await readdir(home)).includes('boss-overlays'), false);
}));

test('home overrides and aliases are refused without modifying profile bytes', enabled, async () => fixture(async home => {
  await writeFile(join(home, 'cordis.patch.yml'), `- id: llm-pi-ai
  name: '@deepseek-ai/dsh-llm-pi-ai'
  config: {providers: {google: {apiKeyEnv: GOOGLE_API_KEY}}}
`);
  let before = await readFile(patchPath(home), 'utf8');
  assert.match((await invoke(home)).stdout, /^REFUSED\t/);
  assert.equal(await readFile(patchPath(home), 'utf8'), before);
  await rm(join(home, 'cordis.patch.yml'));
  await writeFile(patchPath(home), `- id: llm-pi-ai
  name: '@deepseek-ai/dsh-llm-pi-ai'
  config: &options {providers: {google: {apiKeyEnv: GOOGLE_API_KEY}}}
- id: another
  config: *options
`);
  before = await readFile(patchPath(home), 'utf8');
  assert.match((await invoke(home)).stdout, /^REFUSED\t/);
  assert.equal(await readFile(patchPath(home), 'utf8'), before);
}));

test('marker commit failure restores the previous profile and leaves migration retryable', enabled, async () => fixture(async home => {
  await writeFile(join(home, 'settings.yaml'), 'agent-default-model: {provider: openai, model: missing-test-model}\n');
  const before = await readFile(patchPath(home), 'utf8');
  const fault = join(home, 'rename-fault.mjs');
  await writeFile(fault, `import {createRequire,syncBuiltinESMExports} from 'node:module';
const fs=createRequire(import.meta.url)('node:fs/promises');const rename=fs.rename;
const {basename}=await import('node:path');
fs.rename=async(from,to)=>{if(basename(to)==='headless.json')throw new Error('private-failure');return rename(from,to)};
syncBuiltinESMExports();\n`);
  const result = await invoke(home, 'headless', 'OPENAI_API_KEY', false, fault);
  assert.equal(result.code, 1);
  assert.equal(result.stdout, 'FAILED\tprofile configuration could not be updated\n');
  assert.equal(await readFile(patchPath(home), 'utf8'), before);
  assert.equal((await readdir(join(home, 'boss-overlays/profile-migrations'))).includes('headless.json'), false);
  assert.match((await invoke(home)).stdout, /^ADDED\topenai/);
}));
