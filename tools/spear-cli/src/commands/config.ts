/**
 * spear config — manage ~/.spear/config.json (currently just openai-key).
 *
 *   spear config set openai-key sk-...
 *   spear config get openai-key
 *   spear config unset openai-key
 *   spear config list
 *
 * Secrets are masked when displayed. The file is written with mode 600.
 */
import kleur from 'kleur';
import {
  CONFIG_KEYS,
  ConfigKey,
  configPath,
  getConfigValue,
  maskSecret,
  readConfig,
  SECRET_KEYS,
  setConfigValue,
  unsetConfigValue,
} from '../config.js';

const FIELD_TO_KEY: Record<string, ConfigKey> = {
  openai_key: 'openai-key',
};

function assertKey(key: string): asserts key is ConfigKey {
  if (!(CONFIG_KEYS as readonly string[]).includes(key)) {
    console.error(kleur.red(`✗ Unknown config key: ${key}`));
    console.error(kleur.dim(`  Valid keys: ${CONFIG_KEYS.join(', ')}`));
    process.exit(1);
  }
}

export async function configSetCmd(key: string, value: string): Promise<void> {
  assertKey(key);
  await setConfigValue(key, value);
  const display = SECRET_KEYS.has(key) ? maskSecret(value) : value;
  console.log(kleur.green(`✓ ${key} = ${display}`));
  console.log(kleur.dim(`  Written to ${configPath()}`));
}

export async function configGetCmd(key: string, opts: { json?: boolean }): Promise<void> {
  assertKey(key);
  const value = await getConfigValue(key);
  if (opts.json) {
    console.log(JSON.stringify({ key, value: value ?? null }));
    return;
  }
  if (value === undefined) {
    console.log(kleur.dim(`${key} is unset`));
    process.exit(1);
  }
  const display = SECRET_KEYS.has(key) ? maskSecret(value) : value;
  console.log(display);
}

export async function configUnsetCmd(key: string): Promise<void> {
  assertKey(key);
  const removed = await unsetConfigValue(key);
  if (removed) {
    console.log(kleur.green(`✓ ${key} unset`));
  } else {
    console.log(kleur.dim(`${key} was not set`));
  }
}

export async function configListCmd(opts: { json?: boolean }): Promise<void> {
  const cfg = await readConfig();
  if (opts.json) {
    const masked: Record<string, string | null> = {};
    for (const [field, value] of Object.entries(cfg)) {
      const key = FIELD_TO_KEY[field] ?? field;
      masked[key] = SECRET_KEYS.has(key as ConfigKey) ? maskSecret(value as string) : (value as string);
    }
    console.log(JSON.stringify({ path: configPath(), values: masked }, null, 2));
    return;
  }
  const entries = Object.entries(cfg);
  if (entries.length === 0) {
    console.log(kleur.dim(`(no values set in ${configPath()})`));
    return;
  }
  console.log(kleur.dim(configPath()));
  for (const [field, value] of entries) {
    const key = FIELD_TO_KEY[field] ?? field;
    const display = SECRET_KEYS.has(key as ConfigKey) ? maskSecret(value as string) : (value as string);
    console.log(`  ${kleur.cyan(key)} = ${display}`);
  }
}
