/**
 * spear scope — validate SCOPE.md is complete enough to proceed.
 *
 * Deterministic checks:
 *   - File exists at .spear/<slug>/SCOPE.md
 *   - Required H2 sections present (Goal, Audience, Inputs, Constraints, Done means)
 *   - Each section has non-template content
 *
 * Exits 0 if valid, 1 if not. Always writes a status block.
 */
import path from 'path';
import kleur from 'kleur';
import { readMd, readState, resolveSlug, specPath, writeState } from '../state.js';

const REQUIRED_SECTIONS = ['Goal', 'Audience', 'Inputs', 'Constraints', 'Done means'];

interface ScopeReport {
  valid: boolean;
  missingSection: string[];
  unfilledSection: string[];
  maxRounds: number;
}

export async function scopeCmd(opts: { json?: boolean; name?: string }): Promise<void> {
  const slug = resolveSlugOrExit(opts);
  const scopeRel = path.relative(process.cwd(), specPath(slug, 'scope'));
  const md = await readMd(slug, 'scope');
  if (md === null) {
    fail(`${scopeRel} not found. Run \`spear init <type> ${slug}\` first.`, opts);
    return;
  }

  const report = analyze(md);
  await persistRound(slug, report);

  if (opts.json) {
    console.log(JSON.stringify(report, null, 2));
  } else {
    print(report, scopeRel);
  }

  process.exit(report.valid ? 0 : 1);
}

function analyze(md: string): ScopeReport {
  const sections = parseSections(md);
  const missingSection: string[] = [];
  const unfilledSection: string[] = [];

  for (const required of REQUIRED_SECTIONS) {
    const found = Object.keys(sections).find((s) => s.toLowerCase().includes(required.toLowerCase()));
    if (!found) {
      missingSection.push(required);
      continue;
    }
    const body = sections[found].trim();
    if (looksLikePlaceholder(body)) {
      unfilledSection.push(required);
    }
  }

  const maxRoundsMatch = md.match(/MAX_ROUNDS\s*=\s*(\d+)/);
  const maxRounds = maxRoundsMatch ? parseInt(maxRoundsMatch[1], 10) : 20;

  return {
    valid: missingSection.length === 0 && unfilledSection.length === 0,
    missingSection,
    unfilledSection,
    maxRounds,
  };
}

function parseSections(md: string): Record<string, string> {
  const lines = md.split('\n');
  const sections: Record<string, string> = {};
  let current = '';
  let buf: string[] = [];
  for (const line of lines) {
    const m = line.match(/^##\s+(.+?)\s*$/);
    if (m) {
      if (current) sections[current] = buf.join('\n');
      current = m[1];
      buf = [];
    } else if (current) {
      buf.push(line);
    }
  }
  if (current) sections[current] = buf.join('\n');
  return sections;
}

function looksLikePlaceholder(body: string): boolean {
  // A section is filled if at least one line has substantive non-template content.
  // Substantive = non-blockquote, non-italic-only, non-empty-checkbox, contains at
  // least 5 alphabetic words after stripping italic spans + an optional "Label:" prefix.
  for (const rawLine of body.split('\n')) {
    let line = rawLine.trim();
    if (!line) continue;
    if (line.startsWith('>')) continue;
    if (line.startsWith('- [ ]')) continue;
    if (/^_.*_$/.test(line)) continue;
    line = line.replace(/_[^_]*_/g, '').trim();
    const afterColon = line.replace(/^[A-Z][A-Za-z\s/(),.-]*:\s*/, '').trim();
    if (!afterColon) continue;
    const words = afterColon.match(/[A-Za-z][A-Za-z0-9'-]+/g) ?? [];
    if (words.length >= 5) return false;
  }
  return true;
}

async function persistRound(slug: string, report: ScopeReport): Promise<void> {
  const state = (await readState(slug)) ?? {
    type: 'generic' as const,
    slug,
    round: 0,
    phase: 'scope' as const,
    maxRounds: report.maxRounds,
  };
  state.maxRounds = report.maxRounds;
  state.phase = report.valid ? 'plan' : 'scope';
  await writeState(slug, state);
}

function print(report: ScopeReport, scopeRel: string): void {
  if (report.valid) {
    console.log(kleur.green(`✓ ${scopeRel} is valid.`));
    console.log(kleur.dim(`  MAX_ROUNDS = ${report.maxRounds}`));
    return;
  }
  console.log(kleur.red(`✗ ${scopeRel} has gaps:`));
  for (const s of report.missingSection) {
    console.log(`  - missing section: ${kleur.cyan(s)}`);
  }
  for (const s of report.unfilledSection) {
    console.log(`  - section unfilled (still has placeholder): ${kleur.cyan(s)}`);
  }
  console.log();
  console.log(kleur.dim(`Fix ${scopeRel}, then re-run \`spear scope\`.`));
}

function fail(msg: string, opts: { json?: boolean }): void {
  if (opts.json) {
    console.log(JSON.stringify({ valid: false, error: msg }));
  } else {
    console.error(kleur.red('✗ ' + msg));
  }
  process.exit(1);
}

function resolveSlugOrExit(opts: { name?: string }): string {
  try {
    return resolveSlug(opts.name);
  } catch (e) {
    console.error(kleur.red('✗ ' + (e as Error).message));
    process.exit(1);
  }
}
