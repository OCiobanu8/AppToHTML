/**
 * Evidence helpers — used by adapters during assess to emit verifiable traces.
 *
 * The discipline: verify computed values, not just claims. Every mechanical
 * assertion ("14 slides rendered") gets an Evidence with expected/
 * actual + pass. Every subjective claim ("score this slide") gets an Evidence
 * pointing at the artifact (with hash + size) so the LLM has a stable handle
 * and a reviewer can replay the check.
 *
 * persistEvidence copies referenced artifacts into .spear/<slug>/rounds/N/evidence/
 * so the trail is reconstructable round-over-round.
 */
import { promises as fs } from 'fs';
import { existsSync, statSync } from 'fs';
import { createHash } from 'crypto';
import path from 'path';
import { ensureRoundDir, evidenceDir } from './state.js';
import type { Evidence } from './types.js';

export async function hashFile(filePath: string): Promise<string> {
  const buf = await fs.readFile(filePath);
  return 'sha256:' + createHash('sha256').update(buf).digest('hex');
}

export function fileSize(filePath: string): number {
  return statSync(filePath).size;
}

/**
 * Build an Evidence row for a file artifact (auto-fills size + hash).
 * `filePath` may be absolute or cwd-relative; the resulting Evidence stores
 * the cwd-relative path for portability across rounds.
 */
export async function fileEvidence(opts: {
  id: string;
  kind: 'mechanical' | 'subjective';
  description: string;
  filePath: string;
  cwd: string;
  pass?: boolean;
  expected?: unknown;
  actual?: unknown;
  rubricRef?: string;
}): Promise<Evidence> {
  const abs = path.isAbsolute(opts.filePath) ? opts.filePath : path.join(opts.cwd, opts.filePath);
  const rel = path.relative(opts.cwd, abs);
  const ev: Evidence = {
    id: opts.id,
    kind: opts.kind,
    description: opts.description,
    artifact: rel,
    rubricRef: opts.rubricRef,
  };
  if (existsSync(abs)) {
    ev.artifactSize = fileSize(abs);
    ev.artifactHash = await hashFile(abs);
  }
  if (opts.pass !== undefined) ev.pass = opts.pass;
  if (opts.expected !== undefined) ev.expected = opts.expected;
  if (opts.actual !== undefined) ev.actual = opts.actual;
  return ev;
}

export function valueEvidence(opts: {
  id: string;
  description: string;
  pass: boolean;
  expected: unknown;
  actual: unknown;
  rubricRef?: string;
}): Evidence {
  return {
    id: opts.id,
    kind: 'mechanical',
    description: opts.description,
    pass: opts.pass,
    expected: opts.expected,
    actual: opts.actual,
    rubricRef: opts.rubricRef,
  };
}

/**
 * Persist evidence for a round under .spear/<slug>/rounds/N/evidence/:
 *   - copies each referenced artifact
 *   - writes evidence.json (post-copy paths)
 */
export async function persistEvidence(
  slug: string,
  round: number,
  evidence: Evidence[],
  cwd: string = process.cwd(),
): Promise<Evidence[]> {
  const dir = await ensureRoundDir(slug, round, cwd);
  const evDir = evidenceDir(slug, round, cwd);
  const persisted: Evidence[] = [];
  for (const ev of evidence) {
    if (!ev.artifact) {
      persisted.push(ev);
      continue;
    }
    const src = path.isAbsolute(ev.artifact) ? ev.artifact : path.join(cwd, ev.artifact);
    if (!existsSync(src)) {
      persisted.push(ev);
      continue;
    }
    const dest = path.join(evDir, path.basename(ev.artifact));
    try {
      await fs.copyFile(src, dest);
    } catch {
      persisted.push(ev);
      continue;
    }
    persisted.push({
      ...ev,
      artifact: path.relative(cwd, dest),
    });
  }
  const tmp = path.join(dir, 'evidence.json.tmp');
  await fs.writeFile(tmp, JSON.stringify(persisted, null, 2) + '\n');
  await fs.rename(tmp, path.join(dir, 'evidence.json'));
  return persisted;
}
