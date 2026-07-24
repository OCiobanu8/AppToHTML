/**
 * User-level config at ~/.spear/config.json.
 *
 * Stores secrets (currently only the OpenAI API key) and any future
 * machine-wide settings. Written with mode 600 so secrets aren't
 * world-readable. Resolution order for the OpenAI key:
 *
 *   1. process.env.OPENAI_API_KEY  (highest)
 *   2. ~/.spear/config.json → openai_key
 *   3. error
 */
import { promises as fs } from 'fs';
import { existsSync } from 'fs';
import os from 'os';
import path from 'path';

const CONFIG_DIR = path.join(os.homedir(), '.spear');
const CONFIG_PATH = path.join(CONFIG_DIR, 'config.json');

export interface UserConfig {
  openai_key?: string;
}

export const CONFIG_KEYS = ['openai-key'] as const;
export type ConfigKey = (typeof CONFIG_KEYS)[number];

export const SECRET_KEYS: ReadonlySet<ConfigKey> = new Set(['openai-key']);

const KEY_TO_FIELD: Record<ConfigKey, keyof UserConfig> = {
  'openai-key': 'openai_key',
};

export async function readConfig(): Promise<UserConfig> {
  if (!existsSync(CONFIG_PATH)) return {};
  try {
    return JSON.parse(await fs.readFile(CONFIG_PATH, 'utf-8'));
  } catch {
    return {};
  }
}

export async function writeConfig(cfg: UserConfig): Promise<void> {
  await fs.mkdir(CONFIG_DIR, { recursive: true, mode: 0o700 });
  // Atomic: write to .tmp, then rename. Preserves mode 0600 on the final file.
  const tmp = `${CONFIG_PATH}.tmp.${process.pid}`;
  await fs.writeFile(tmp, JSON.stringify(cfg, null, 2) + '\n', { mode: 0o600 });
  await fs.rename(tmp, CONFIG_PATH);
}

export async function setConfigValue(key: ConfigKey, value: string): Promise<void> {
  const cfg = await readConfig();
  cfg[KEY_TO_FIELD[key]] = value;
  await writeConfig(cfg);
}

export async function unsetConfigValue(key: ConfigKey): Promise<boolean> {
  const cfg = await readConfig();
  const field = KEY_TO_FIELD[key];
  if (!(field in cfg)) return false;
  delete cfg[field];
  await writeConfig(cfg);
  return true;
}

export async function getConfigValue(key: ConfigKey): Promise<string | undefined> {
  const cfg = await readConfig();
  return cfg[KEY_TO_FIELD[key]];
}

export function maskSecret(value: string | undefined): string {
  if (!value) return '(unset)';
  if (value.length <= 8) return '***';
  return `${value.slice(0, 4)}…${value.slice(-4)}`;
}

export function configPath(): string {
  return CONFIG_PATH;
}

/**
 * Resolve the OpenAI API key. Env var wins; config file is fallback.
 * Throws with a helpful message if neither is set.
 */
export async function requireOpenAIKey(): Promise<string> {
  const fromEnv = process.env.OPENAI_API_KEY?.trim();
  if (fromEnv) return fromEnv;
  const fromConfig = (await getConfigValue('openai-key'))?.trim();
  if (fromConfig) return fromConfig;
  throw new Error(
    'OPENAI_API_KEY is not set. Either:\n' +
      '  • export OPENAI_API_KEY=sk-...\n' +
      '  • spear config set openai-key sk-...',
  );
}
