/**
 * spear runner — multi-loop status reporter for parallel SPEAR projects.
 *
 * Discovers projects two ways:
 *   1. By default: every slug under `.spear/<slug>/state.json` in cwd
 *   2. With --paths a,b,c: each path is treated as a repo root; runner
 *      enumerates that repo's `.spear/<slug>/` projects too
 *
 * The table is the only output — no narrative — so it can be piped or diffed.
 */
import { existsSync } from 'fs';
import path from 'path';
import { spawnSync } from 'child_process';
import kleur from 'kleur';
import { listSlugs, readState, statePath } from '../state.js';

interface RunnerOpts {
  paths?: string;
  interval?: string;
  once?: boolean;
  json?: boolean;
}

interface LoopStatus {
  id: string;          // "<repo-name>:<slug>" or just "<slug>"
  cwd: string;
  slug: string;
  scope: string;
  plan: string;
  execute: string;
  assess: string;
  resolve: string;
  counts: string;
  cursor: string;
  lastCommit: string;
  action: string;
}

export async function runnerCmd(opts: RunnerOpts): Promise<void> {
  const interval = parseInt(opts.interval ?? '300', 10) * 1000;
  const projects = await discover(opts.paths);

  if (projects.length === 0) {
    console.error(kleur.red('No SPEAR projects found.'));
    console.error(kleur.dim('Run from a directory that has .spear/<slug>/state.json, or pass --paths a,b,c'));
    process.exit(1);
  }

  do {
    const statuses = await Promise.all(projects.map((p) => statusOf(p.cwd, p.slug, p.label)));
    if (opts.json) {
      console.log(JSON.stringify({ timestamp: new Date().toISOString(), loops: statuses }, null, 2));
    } else {
      print(statuses);
    }
    if (opts.once) break;
    await sleep(interval);
  } while (true);
}

interface DiscoveredProject {
  cwd: string;
  slug: string;
  label: string;       // how to display in the id column
}

async function discover(pathsOpt?: string): Promise<DiscoveredProject[]> {
  const out: DiscoveredProject[] = [];
  const roots = pathsOpt
    ? pathsOpt.split(',').map((p) => path.resolve(p.trim()))
    : [process.cwd()];

  for (const root of roots) {
    const slugs = listSlugs(root);
    const labelPrefix = roots.length > 1 ? `${path.basename(root)}:` : '';
    for (const slug of slugs) {
      out.push({
        cwd: root,
        slug,
        label: labelPrefix + slug,
      });
    }
  }
  return out;
}

async function statusOf(cwd: string, slug: string, label: string): Promise<LoopStatus> {
  const state = await readState(slug, cwd);
  const phase = state?.phase ?? 'pending';
  const round = state?.round ?? 0;
  const maxRounds = state?.maxRounds ?? 20;

  const phaseGlyph = (target: string) => {
    if (phase === target) return `🟡 r${round}/${maxRounds}`;
    return phaseAfter(phase, target) ? '✅' : '⏸';
  };

  // 5-state runner status: completed / blocked / stuck / failed / in-progress
  const overrideGlyph = state?.completedAt ? '✅'
    : state?.failureReason ? '🔴'
    : state?.blockers ? '❌'
    : state?.stuckSince ? '⚠'
    : null;

  const counts = formatCounts(state);
  const cursor = lastCommitSha(cwd);
  const lastCommit = lastCommitMsg(cwd);
  const action = inferAction(state);

  const stateExists = existsSync(statePath(slug, cwd));
  const has = (predicate: boolean) => predicate ? '✅' : '⏸';

  return {
    id: label,
    cwd,
    slug,
    scope: has(stateExists && phaseAfter(phase, 'scope')),
    plan: has(stateExists && phaseAfter(phase, 'plan')),
    execute: overrideGlyph ?? phaseGlyph('execute'),
    assess: overrideGlyph ?? phaseGlyph('assess'),
    resolve: overrideGlyph ?? phaseGlyph('resolve'),
    counts,
    cursor,
    lastCommit,
    action,
  };
}

const PHASE_ORDER = ['scope', 'plan', 'execute', 'assess', 'resolve', 'converged'];
function phaseAfter(current: string, target: string): boolean {
  return PHASE_ORDER.indexOf(current) > PHASE_ORDER.indexOf(target);
}

function formatCounts(state: Awaited<ReturnType<typeof readState>>): string {
  if (!state || !state.lastAssess) return '—';
  return `${state.lastAssess.defectCount} defect(s) @ r${state.round}`;
}

function lastCommitSha(cwd: string): string {
  const r = spawnSync('git', ['-C', cwd, 'rev-parse', '--short', 'HEAD'], { stdio: 'pipe' });
  return r.status === 0 ? r.stdout.toString().trim() : '—';
}

function lastCommitMsg(cwd: string): string {
  const r = spawnSync('git', ['-C', cwd, 'log', '-1', '--pretty=%h — %s'], { stdio: 'pipe' });
  return r.status === 0 ? r.stdout.toString().trim().slice(0, 60) : '—';
}

function inferAction(state: Awaited<ReturnType<typeof readState>>): string {
  if (!state) return 'init';
  if (state.completedAt) return '✓ done';
  if (state.blockers) return `blocked: ${state.blockers.slice(0, 30)}`;
  if (state.stuckSince) return `stuck since r${state.stuckSince}`;
  switch (state.phase) {
    case 'scope': return 'fill SCOPE.md';
    case 'plan': return 'have LLM write PLAN.md';
    case 'execute': return 'spear execute';
    case 'assess': return 'spear assess';
    case 'resolve': return state.lastAssess
      ? `LLM applies fixes for ${state.lastAssess.defectCount} defect(s)`
      : 'spear resolve';
    case 'converged': return '✓ done';
    default: return '—';
  }
}

function print(statuses: LoopStatus[]): void {
  const ts = new Date().toISOString();
  console.log(`=== SPEAR check-in — ${ts} ===`);
  console.log();
  const header = pad('Loop', 14) + pad('S', 4) + pad('P', 4) + pad('E', 14) + pad('A', 14) + pad('R', 8) + pad('Counts', 30) + pad('Cursor', 8) + pad('Last commit', 60) + 'Action';
  console.log(header);
  console.log('-'.repeat(header.length));
  for (const s of statuses) {
    console.log(
      pad(s.id, 14) +
      pad(s.scope, 4) +
      pad(s.plan, 4) +
      pad(s.execute, 14) +
      pad(s.assess, 14) +
      pad(s.resolve, 8) +
      pad(s.counts, 30) +
      pad(s.cursor, 8) +
      pad(s.lastCommit, 60) +
      s.action,
    );
  }
}

function pad(s: string, n: number): string {
  const visible = stripWide(s);
  return s + ' '.repeat(Math.max(0, n - visible.length));
}

function stripWide(s: string): string {
  let w = 0;
  for (const ch of s) {
    const cp = ch.codePointAt(0) ?? 0;
    if (cp > 0x1F000) w += 2;
    else w += 1;
  }
  return ' '.repeat(w);
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
