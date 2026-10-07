/**
 * Register BOSS routes before dsh 0.2 boots, using its own profile/parser/write
 * protocol. Provider config must live in the profile: a CLI config overlay
 * prevents the native Models page from editing that row.
 *
 * argv: dsh package directory, Harness home, initialized profile, env-name CSV.
 * stdout contains only fixed status metadata; config and exception text never
 * leave this helper. The harness's settings.yaml/imported files are read-only.
 */
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { join, resolve } from 'node:path';
import { readFile, mkdir, chmod, lstat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';

const ROUTES = {
  OPENAI_API_KEY: 'openai', OPEN_AI_API_KEY: 'openai', OPENAI_KEY: 'openai',
  ANTHROPIC_API_KEY: 'anthropic', DEEPSEEK_API_KEY: 'deepseek',
  GOOGLE_API_KEY: 'google', GEMINI_API_KEY: 'google', XAI_API_KEY: 'xai', GROK_API_KEY: 'xai',
  TOGETHER_API_KEY: 'together', TOGETHERAI_API_KEY: 'together', MISTRAL_API_KEY: 'mistral',
  GROQ_API_KEY: 'groq', OPENROUTER_API_KEY: 'openrouter', FIREWORKS_API_KEY: 'fireworks',
  CEREBRAS_API_KEY: 'cerebras', NVIDIA_API_KEY: 'nvidia',
};
const CANONICAL = {
  openai: 'OPENAI_API_KEY', anthropic: 'ANTHROPIC_API_KEY', deepseek: 'DEEPSEEK_API_KEY',
  google: 'GOOGLE_API_KEY', xai: 'XAI_API_KEY', together: 'TOGETHER_API_KEY',
  mistral: 'MISTRAL_API_KEY', groq: 'GROQ_API_KEY', openrouter: 'OPENROUTER_API_KEY',
  fireworks: 'FIREWORKS_API_KEY', cerebras: 'CEREBRAS_API_KEY', nvidia: 'NVIDIA_API_KEY',
};
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value)
  && Object.getPrototypeOf(value) === Object.prototype;
const flatten = rows => rows.flatMap(row => [row, ...(row.group && Array.isArray(row.config) ? flatten(row.config) : [])]);
class Refused extends Error {}
const refuse = () => { throw new Refused(); };

async function optionalRead(path) {
  try { return await readFile(path, 'utf8'); }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}
async function privateDirectory(path) {
  await mkdir(path, { recursive: true, mode: 0o700 });
  if (!(await lstat(path)).isDirectory()) refuse();
  if (process.platform !== 'win32') await chmod(path, 0o700);
}

async function run() {
  const [packageDirectory, homeArgument, profile, namesCsv, mode] = process.argv.slice(2);
  const inspect = mode === '--inspect';
  if (!packageDirectory || !homeArgument || !profile || namesCsv === undefined
    || (mode !== undefined && !inspect) || process.argv.length !== (inspect ? 7 : 6)
    || profile === '.' || profile === '..' || /[\\/\x00]/.test(profile)) refuse();
  const names = namesCsv ? namesCsv.split(',') : [];
  if (names.some(name => !Object.hasOwn(ROUTES, name))) refuse();
  const home = resolve(homeArgument);
  const profileDir = join(home, 'profiles', profile);
  const packageJson = join(resolve(packageDirectory), 'package.json');
  const require = createRequire(packageJson);
  const load = name => import(pathToFileURL(require.resolve(name)).href);
  const [{ loadProfileDirectory, composeEntries }, { entryListSchema, applyEntryPatches },
    { withFileLock, writeFileAtomic }, yaml, ast] = await Promise.all([
      load('@deepseek-ai/dsh-app-boot'), load('@deepseek-ai/cordis-plugin-include'),
      load('@deepseek-ai/dsh-atomic-write'), load('js-yaml'), load('yaml'),
    ]);
  const { parseDocument, isMap, isSeq, isAlias, Scalar, visit } = ast;
  const parseEntries = text => {
    const value = yaml.load(text, { schema: entryListSchema });
    if (value === null || value === undefined) return [];
    if (!Array.isArray(value)) refuse();
    return value;
  };
  const parseAst = (text, sequence) => {
    const doc = parseDocument(text, { customTags: [{
      tag: 'tag:yaml.org,2002:js', resolve: value => ({ __jsExpr: value }),
    }] });
    if (doc.contents === null && sequence) doc.contents = doc.createNode([]);
    if (doc.errors.length || doc.warnings.length || (sequence ? !isSeq(doc.contents) : !isMap(doc.contents))) refuse();
    // Cross-node alias ownership becomes ambiguous when moving a config into
    // another patch row; refuse it rather than leave a dangling anchor.
    visit(doc, (_key, node) => { if (isAlias(node)) refuse(); });
    return doc;
  };
  const toNode = (doc, value) => {
    const node = doc.createNode(value);
    visit(node, { Map(_key, map) {
      if (map.items.length !== 1 || typeof map.get('__jsExpr') !== 'string') return;
      const expression = new Scalar(map.get('__jsExpr'));
      expression.tag = 'tag:yaml.org,2002:js';
      return expression;
    } });
    return node;
  };
  const merge = (under, over) => {
    if (!plain(under) || !plain(over)) return structuredClone(over);
    const result = structuredClone(under);
    for (const [key, value] of Object.entries(over)) Object.defineProperty(result, key, {
      value: Object.hasOwn(result, key) ? merge(result[key], value) : structuredClone(value),
      enumerable: true, writable: true, configurable: true,
    });
    return result;
  };
  const parseLegacy = text => {
    if (text === null) return null;
    const legacy = parseAst(text, false).toJS();
    if (!plain(legacy) || Object.values(legacy).some(section => !plain(section))) refuse();
    const rejectExpressions = value => {
      if (plain(value)) {
        if (Object.hasOwn(value, '__jsExpr')) refuse();
        Object.values(value).forEach(rejectExpressions);
      } else if (Array.isArray(value)) value.forEach(rejectExpressions);
    };
    rejectExpressions(legacy);
    return legacy;
  };
  const parseMarker = text => {
    if (text === null) return null;
    let marker;
    try { marker = JSON.parse(text); }
    catch { refuse(); }
    if (!plain(marker) || marker.version !== 1 || typeof marker.legacyDigest !== 'string') refuse();
    return marker;
  };
  const digestOf = text => text === null ? null : createHash('sha256').update(text).digest('hex');
  const homeText = await optionalRead(join(home, 'cordis.patch.yml'));
  if (homeText !== null) parseAst(homeText, true);
  const homePatches = homeText === null ? [] : parseEntries(homeText);
  const migrateRows = (base, currentWithoutHome, userPatches, legacy, active) => {
    // A live old document matches native import's once-only precedence.
    // An archived global baseline sits below explicit profile/home choices.
    let migrated = structuredClone(active ? currentWithoutHome : base);
    const aliases = {
      'ui-developer-tools': 'ui-settings', 'ui-onboarding': 'ui-settings-general',
      shell: process.platform === 'win32' ? 'pwsh-sandbox' : 'bash-sandbox',
    };
    for (const [section, values] of Object.entries(legacy)) {
      const id = Object.hasOwn(aliases, section) ? aliases[section] : section;
      const matches = flatten(migrated).filter(row => row.id === id);
      if (!matches.length) continue; // Native importer also leaves unavailable sections archived.
      if (matches.length !== 1 || !plain(matches[0].config ?? {})) refuse();
      matches[0].config = merge(matches[0].config ?? {}, values);
    }
    if (!active) migrated = applyEntryPatches(migrated, userPatches, () => refuse());
    return applyEntryPatches(migrated, homePatches, () => refuse());
  };
  const metadata = rows => {
    const flat = flatten(rows);
    const model = flat.find(row => row.id === 'agent-default-model')?.config;
    const safeId = value => typeof value === 'string' && value.length <= 1024 && /^[A-Za-z0-9_.:/+-]+$/.test(value);
    const lines = [];
    lines.push(`MODEL\t${safeId(model?.provider) ? model.provider : ''}\t${safeId(model?.model) ? model.model : ''}`);
    const envs = new Set();
    const scan = value => {
      if (plain(value)) for (const [key, item] of Object.entries(value)) {
        if (key === 'apiKeyEnv' && typeof item === 'string' && /^[A-Z][A-Z0-9_]*$/.test(item)) envs.add(item);
        else scan(item);
      }
      else if (Array.isArray(value)) value.forEach(scan);
    };
    flat.forEach(row => scan(row.config));
    lines.push(`ENVS\t${[...envs].sort().join(',')}`);
    return '\n' + lines.join('\n');
  };
  const privateDir = join(home, 'boss-overlays', 'profile-migrations');
  const baselinePath = join(privateDir, 'legacy-settings.yaml');
  if (inspect) {
    const loaded = loadProfileDirectory('dsh', profileDir, packageJson);
    if (loaded.skippedBundles.length) refuse();
    const activeLegacy = await optionalRead(join(home, 'settings.yaml'));
    const baseline = activeLegacy ?? await optionalRead(baselinePath)
      ?? await optionalRead(join(home, 'settings.yaml.imported'));
    const legacy = parseLegacy(baseline);
    const marker = parseMarker(await optionalRead(join(privateDir, `${profile}.json`)));
    const base = composeEntries(loaded.layers.map(layer => layer.patches));
    const currentWithoutHome = applyEntryPatches(base, loaded.patches, () => refuse());
    const rows = legacy !== null && marker?.legacyDigest !== digestOf(baseline)
      ? migrateRows(base, currentWithoutHome, loaded.patches, legacy, activeLegacy !== null)
      : applyEntryPatches(currentWithoutHome, homePatches, () => refuse());
    return 'UP_TO_DATE' + metadata(rows);
  }
  await privateDirectory(privateDir);
  // Preserve the global document before native web import consumes it. This is
  // shared across profiles and never emitted through stdout or diagnostics.
  return withFileLock(baselinePath, async () => {
    const activeLegacy = await optionalRead(join(home, 'settings.yaml'));
    const previousBaseline = await optionalRead(baselinePath);
    const baseline = activeLegacy ?? previousBaseline ?? await optionalRead(join(home, 'settings.yaml.imported'));
    const legacy = parseLegacy(baseline);
    const digest = digestOf(baseline);
    if (baseline !== null && baseline !== previousBaseline) {
      await writeFileAtomic(baselinePath, baseline, { mode: 0o600, dirMode: 0o700 });
    }
    return withFileLock(join(profileDir, 'package.json'), async () => {
      const path = join(profileDir, 'cordis.patch.yml');
      const original = await optionalRead(path) ?? '[]\n';
      const document = parseAst(original, true);
      const loaded = loadProfileDirectory('dsh', profileDir, packageJson);
      if (loaded.skippedBundles.length) refuse();
      const markerPath = join(privateDir, `${profile}.json`);
      const originalMarker = await optionalRead(markerPath);
      const marker = parseMarker(originalMarker);
      const migrate = legacy !== null && marker?.legacyDigest !== digest;
      const bundles = loaded.layers.map(layer => layer.patches);
      const userPatches = parseEntries(original);
      const base = composeEntries(bundles);
      const currentWithoutHome = applyEntryPatches(base, userPatches, () => refuse());
      const current = applyEntryPatches(currentWithoutHome, homePatches, () => refuse());
      let desired = structuredClone(current);
      if (migrate) {
        desired = migrateRows(base, currentWithoutHome, userPatches, legacy, activeLegacy !== null);
      }
      const candidates = new Map();
      for (const name of names.sort()) {
        const route = ROUTES[name];
        if (!candidates.has(route) || name === CANONICAL[route]) candidates.set(route, name);
      }
      const rows = flatten(desired).filter(row => row.id === 'llm-pi-ai');
      const routes = [];
      if (candidates.size) {
        if (rows.length !== 1 || rows[0].name !== '@deepseek-ai/dsh-llm-pi-ai'
          || !plain(rows[0].config ?? {}) || !plain(rows[0].config?.providers ?? {})) refuse();
        const row = rows[0];
        row.config ??= {};
        row.config.providers ??= {};
        for (const [route, envName] of [...candidates].sort(([a], [b]) => a.localeCompare(b))) {
          if (Object.hasOwn(row.config.providers, route)) continue;
          // Home config is a higher-priority whole-row override. Leave it in
          // control instead of writing a route that would be silently ignored.
          if (homePatches.some(patch => patch.id === row.id && Object.hasOwn(patch, 'config'))) refuse();
          Object.defineProperty(row.config.providers, route, {
            value: { apiKeyEnv: envName }, enumerable: true, writable: true, configurable: true,
          });
          routes.push(route);
        }
      }
      const changes = [];
      for (const row of flatten(desired)) {
        const prior = flatten(current).filter(candidate => candidate.id === row.id);
        if (!row.id || prior.length !== 1 || isDeepStrictEqual(row.config, prior[0].config)) continue;
        if (homePatches.some(patch => patch.id === row.id && Object.hasOwn(patch, 'config'))) refuse();
        changes.push(row);
      }
      for (const row of changes) {
        let index = document.contents.items.findLastIndex((item, index) => isMap(item)
          && document.getIn([index, 'id']) === row.id && !item.has('insert')
          && (!item.has('name') || document.getIn([index, 'name']) === row.name));
        if (index < 0) {
          document.add(document.createNode({ id: row.id, name: row.name }));
          index = document.contents.items.length - 1;
        }
        const previousNode = document.getIn([index, 'config'], true);
        const nextNode = toNode(document, row.config ?? {});
        // Keep comments attached to unchanged config nodes, including fields
        // deep inside models/retry/options. Metadata is copied only when the
        // node has the same value; replaced values keep their preceding comment.
        const preserveComments = (before, after) => {
          if (!before || !after) return;
          after.commentBefore = before.commentBefore;
          after.comment = before.comment;
          if (isMap(before) && isMap(after)) for (const pair of after.items) {
            const match = before.items.find(old => isDeepStrictEqual(old.key?.toJSON(), pair.key?.toJSON()));
            if (match) { pair.key = match.key.clone(); preserveComments(match.value, pair.value); }
          };
          if (isSeq(before) && isSeq(after)) after.items.forEach((item, i) => preserveComments(before.items[i], item));
        };
        preserveComments(previousNode, nextNode);
        document.setIn([index, 'config'], nextNode);
      }
      const next = String(document);
      const verified = applyEntryPatches(base, parseEntries(next), () => refuse());
      const effective = applyEntryPatches(verified, homePatches, () => refuse());
      if (!isDeepStrictEqual(effective, desired)) refuse();
      const profileChanged = changes.length > 0;
      const markerChanged = migrate;
      if (!profileChanged && !markerChanged) return 'UP_TO_DATE' + metadata(desired);
      const backupPath = join(privateDir, `${profile}.cordis.patch.yml.boss-backup`);
      if (profileChanged) await writeFileAtomic(backupPath, original, { mode: 0o600, dirMode: 0o700 });
      try {
        if (profileChanged) await writeFileAtomic(path, next, { mode: 0o600 });
        if (markerChanged) await writeFileAtomic(markerPath,
          JSON.stringify({ version: 1, legacyDigest: digest }) + '\n', { mode: 0o600, dirMode: 0o700 });
      } catch (error) {
        // A marker is committed only after the profile; restore the profile if
        // committing the marker fails so the next attempt repeats the migration.
        if (profileChanged) await writeFileAtomic(path, original, { mode: 0o600 });
        throw error;
      }
      return `ADDED\t${routes.join(',')}` + metadata(desired);
    });
  });
}

try { process.stdout.write((await run()) + '\n'); }
catch (error) {
  process.stdout.write(error instanceof Refused
    ? 'REFUSED\tprofile configuration cannot be updated safely\n'
    : 'FAILED\tprofile configuration could not be updated\n');
  process.exitCode = error instanceof Refused ? 0 : 1;
}
