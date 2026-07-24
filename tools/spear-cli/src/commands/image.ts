/**
 * spear image — generate a single image via OpenAI's image API (gpt-image-2).
 *
 * Examples:
 *   spear image --prompt "..." --out images/hero.png --aspect 2:3
 *   spear image --prompt "..." --out images/wide.png --size 1536x1024 --quality high
 *   spear image --prompt "..." --out images/foo.png --force --json
 *
 * Notes:
 *   - The LLM is in charge of prompt-writing. The CLI just makes the API call.
 *   - Auto-creates parent directories.
 *   - Refuses to overwrite an existing file unless --force is passed.
 *   - Reads OPENAI_API_KEY from env, then ~/.spear/config.json (see `spear config`).
 */
import { promises as fs } from 'fs';
import { existsSync } from 'fs';
import path from 'path';
import kleur from 'kleur';
import { atomicWrite } from '../state.js';
import { requireOpenAIKey } from '../config.js';

interface ImageOpts {
  prompt: string;
  out: string;
  size?: string;
  aspect?: string;
  quality?: string;
  model?: string;
  force?: boolean;
  json?: boolean;
}

const ASPECT_TO_SIZE: Record<string, string> = {
  '1:1': '1024x1024',
  'square': '1024x1024',
  '2:3': '1024x1536',
  'portrait': '1024x1536',
  '3:2': '1536x1024',
  'landscape': '1536x1024',
};

const SIZE_RE = /^\d{3,5}x\d{3,5}$/;

export async function imageCmd(opts: ImageOpts): Promise<void> {
  if (!opts.prompt || !opts.prompt.trim()) {
    fail('--prompt is required (and non-empty)', opts);
    return;
  }
  if (!opts.out) {
    fail('--out is required', opts);
    return;
  }

  const size = resolveSize(opts);
  if (!size) {
    fail(
      `Invalid --size or --aspect. Use --size WIDTHxHEIGHT (e.g., 1024x1536) ` +
        `or --aspect ${Object.keys(ASPECT_TO_SIZE).join('|')}`,
      opts,
    );
    return;
  }

  const outPath = path.resolve(opts.out);
  if (existsSync(outPath) && !opts.force) {
    fail(`${opts.out} already exists. Use --force to overwrite.`, opts);
    return;
  }

  let apiKey: string;
  try {
    apiKey = await requireOpenAIKey();
  } catch (e) {
    fail((e as Error).message, opts);
    return;
  }

  await fs.mkdir(path.dirname(outPath), { recursive: true });

  const body: Record<string, unknown> = {
    model: opts.model ?? 'gpt-image-2',
    prompt: opts.prompt,
    size,
    n: 1,
  };
  if (opts.quality) body.quality = opts.quality;

  if (!opts.json) {
    console.log(kleur.dim(`→ POST /v1/images/generations  (${body.model}, ${size}${opts.quality ? `, ${opts.quality}` : ''})`));
  }

  let response: Response;
  try {
    response = await fetch('https://api.openai.com/v1/images/generations', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    });
  } catch (e) {
    fail(`Network error: ${(e as Error).message}`, opts);
    return;
  }

  if (!response.ok) {
    const text = await response.text();
    let detail = text;
    try {
      const j = JSON.parse(text);
      detail = j.error?.message ?? text;
    } catch {
      /* keep raw text */
    }
    fail(`OpenAI API ${response.status}: ${detail}`, opts);
    return;
  }

  let data: { data?: { b64_json?: string; url?: string }[] };
  try {
    data = (await response.json()) as typeof data;
  } catch (e) {
    fail(`Invalid JSON from API: ${(e as Error).message}`, opts);
    return;
  }

  const item = data.data?.[0];
  if (!item) {
    fail('API response had no image data.', opts);
    return;
  }

  let bytes: Buffer;
  if (item.b64_json) {
    bytes = Buffer.from(item.b64_json, 'base64');
  } else if (item.url) {
    const r = await fetch(item.url);
    if (!r.ok) {
      fail(`Failed to download image from ${item.url}: ${r.status}`, opts);
      return;
    }
    bytes = Buffer.from(await r.arrayBuffer());
  } else {
    fail('API response had neither b64_json nor url.', opts);
    return;
  }

  await atomicWrite(outPath, bytes);

  const result = {
    ok: true,
    path: opts.out,
    absolutePath: outPath,
    size,
    bytes: bytes.length,
    model: body.model,
  };

  if (opts.json) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    const kb = (bytes.length / 1024).toFixed(0);
    console.log(kleur.green(`✓ ${opts.out}`) + kleur.dim(`  (${size}, ${kb} KB)`));
  }
}

function resolveSize(opts: ImageOpts): string | null {
  if (opts.size) {
    return SIZE_RE.test(opts.size) ? opts.size : null;
  }
  if (opts.aspect) {
    return ASPECT_TO_SIZE[opts.aspect.toLowerCase()] ?? null;
  }
  return '1024x1024';
}

function fail(msg: string, opts: { json?: boolean }): void {
  if (opts.json) {
    console.log(JSON.stringify({ ok: false, error: msg }));
  } else {
    console.error(kleur.red(`✗ ${msg}`));
  }
  process.exit(1);
}
