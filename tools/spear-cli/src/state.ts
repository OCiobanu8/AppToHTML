/**
 * State helpers — slug-aware paths under .spear/<slug>/.
 *
 * A SPEAR project lives at:
 *   .spear/<slug>/SCOPE.md         ← tracked
 *   .spear/<slug>/PLAN.md          ← tracked
 *   .spear/<slug>/ASSESS.md        ← tracked
 *   .spear/<slug>/RESOLVE.md       ← tracked
 *   .spear/<slug>/state.json       ← gitignored (runtime)
 *   .spear/<slug>/rounds/N/        ← gitignored (per-round artifacts)
 *
 * One repo can host multiple SPEAR projects, each with its own slug.
 * Single-slug repos auto-resolve; multi-slug repos require --name <slug> or
 * SPEAR_PROJECT=<slug>.
 *
 * State writes are atomic (temp + rename) to survive Ctrl-C mid-write.
 */
import { promises as fs } from 'fs';
import { existsSync, readdirSync } from 'fs';
import path from 'path';

export const SPEAR_DIR = '.spear';

export type SpecName = 'scope' | 'plan' | 'assess' | 'resolve';

const SPEC_FILE: Record<SpecName, string> = {
  scope: 'SCOPE.md',
  plan: 'PLAN.md',
  assess: 'ASSESS.md',
  resolve: 'RESOLVE.md',
};

export type Phase = 'scope' | 'plan' | 'execute' | 'assess' | 'resolve' | 'converged';

export interface SpearState {
  type: 'deck' | 'blog' | 'code' | 'generic';
  slug: string;
  round: number;
  phase: Phase;
  maxRounds: number;
  /**
   * If true, phase commands require an explicit `spear approve <phase>` for
   * the upstream phase before they will run. Use --skip-approval to bypass
   * per-command. Default false for back-compat.
   */
  gated?: boolean;
  lastAssess?: {
    defectCount: number;
    evidenceCount?: number;
    timestamp: string;
    fixed?: number;
    progress?: string;
  };
  lastRoundDefectCount?: number;
  stuckSince?: number;
  failureReason?: string;
  blockers?: string;
  completedAt?: string;
  history?: Array<{
    round: number;
    defectCount: number;
    durationMs?: number;
    exitCode?: number;
    timestamp: string;
  }>;
}

// ---------- path helpers ----------

export function projectDir(slug: string, cwd: string = process.cwd()): string {
  return path.join(cwd, SPEAR_DIR, slug);
}

export function specPath(slug: string, name: SpecName, cwd: string = process.cwd()): string {
  return path.join(projectDir(slug, cwd), SPEC_FILE[name]);
}

export function statePath(slug: string, cwd: string = process.cwd()): string {
  return path.join(projectDir(slug, cwd), 'state.json');
}

export function roundDir(slug: string, round: number, cwd: string = process.cwd()): string {
  return path.join(projectDir(slug, cwd), 'rounds', String(round));
}

export function evidenceDir(slug: string, round: number, cwd: string = process.cwd()): string {
  return path.join(roundDir(slug, round, cwd), 'evidence');
}

// ---------- slug resolution ----------

const SLUG_RE = /^[a-z0-9][a-z0-9_-]*$/i;

export function validateSlug(slug: string): void {
  if (!SLUG_RE.test(slug)) {
    throw new Error(
      `Invalid SPEAR project name "${slug}". Names must start with a letter or digit and contain only letters, digits, _ or -.`,
    );
  }
}

/**
 * List slugs in cwd's .spear/. A slug is a directory under .spear/ that
 * contains state.json (so partial init / scratch dirs don't show up).
 */
export function listSlugs(cwd: string = process.cwd()): string[] {
  const dir = path.join(cwd, SPEAR_DIR);
  if (!existsSync(dir)) return [];
  const out: string[] = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (!e.isDirectory()) continue;
    if (existsSync(path.join(dir, e.name, 'state.json'))) out.push(e.name);
  }
  return out.sort();
}

/**
 * Resolve which slug a command should operate on:
 *   1. explicit --name flag (or positional)
 *   2. SPEAR_PROJECT env var
 *   3. if exactly one slug in .spear/, that one
 *   4. error
 */
export function resolveSlug(explicit: string | undefined, cwd: string = process.cwd()): string {
  if (explicit) {
    validateSlug(explicit);
    return explicit;
  }
  const fromEnv = process.env.SPEAR_PROJECT?.trim();
  if (fromEnv) {
    validateSlug(fromEnv);
    return fromEnv;
  }
  const slugs = listSlugs(cwd);
  if (slugs.length === 1) return slugs[0];
  if (slugs.length === 0) {
    throw new Error('No SPEAR project found. Run `spear init <type> [name]` first.');
  }
  throw new Error(
    `Multiple SPEAR projects in ${SPEAR_DIR}/: ${slugs.join(', ')}. ` +
      `Pick one with --name <slug> or SPEAR_PROJECT=<slug>.`,
  );
}

// ---------- read/write ----------

export async function readState(slug: string, cwd: string = process.cwd()): Promise<SpearState | null> {
  const p = statePath(slug, cwd);
  if (!existsSync(p)) return null;
  return JSON.parse(await fs.readFile(p, 'utf-8'));
}

/**
 * Atomic state write: serialize to a sibling .tmp file, then rename.
 */
export async function writeState(slug: string, state: SpearState, cwd: string = process.cwd()): Promise<void> {
  const p = statePath(slug, cwd);
  await fs.mkdir(path.dirname(p), { recursive: true });
  const tmp = `${p}.tmp.${process.pid}`;
  await fs.writeFile(tmp, JSON.stringify(state, null, 2) + '\n');
  await fs.rename(tmp, p);
}

export async function readMd(slug: string, name: SpecName, cwd: string = process.cwd()): Promise<string | null> {
  const p = specPath(slug, name, cwd);
  if (!existsSync(p)) return null;
  return fs.readFile(p, 'utf-8');
}

export async function writeMd(
  slug: string,
  name: SpecName,
  content: string,
  cwd: string = process.cwd(),
): Promise<void> {
  const p = specPath(slug, name, cwd);
  await fs.mkdir(path.dirname(p), { recursive: true });
  const tmp = `${p}.tmp.${process.pid}`;
  await fs.writeFile(tmp, content);
  await fs.rename(tmp, p);
}

const PHASE_ORDER: SpearState['phase'][] = ['scope', 'plan', 'execute', 'assess', 'resolve', 'converged'];

/**
 * Hard phase-gate check. Returns true if `current` is at or beyond `required`.
 * Use to refuse downstream commands when upstream phases haven't passed.
 *
 *   phaseAtLeast('plan', 'execute')  → false (execute hasn't been reached yet)
 *   phaseAtLeast('execute', 'execute') → true
 *   phaseAtLeast('converged', 'assess') → true
 */
export function phaseAtLeast(current: SpearState['phase'], required: SpearState['phase']): boolean {
  return PHASE_ORDER.indexOf(current) >= PHASE_ORDER.indexOf(required);
}

// ---------- approval gates ----------

const APPROVABLE_PHASES: Phase[] = ['scope', 'plan', 'execute', 'assess'];

export function approvalsDir(slug: string, cwd: string = process.cwd()): string {
  return path.join(projectDir(slug, cwd), '.approvals');
}

export function approvalPath(slug: string, phase: Phase, cwd: string = process.cwd()): string {
  return path.join(approvalsDir(slug, cwd), `${phase}.json`);
}

export function isApproved(slug: string, phase: Phase, cwd: string = process.cwd()): boolean {
  return existsSync(approvalPath(slug, phase, cwd));
}

export async function writeApproval(slug: string, phase: Phase, cwd: string = process.cwd()): Promise<void> {
  const dir = approvalsDir(slug, cwd);
  await fs.mkdir(dir, { recursive: true });
  const data = JSON.stringify({ phase, timestamp: new Date().toISOString() }, null, 2) + '\n';
  const target = approvalPath(slug, phase, cwd);
  const tmp = `${target}.tmp.${process.pid}`;
  await fs.writeFile(tmp, data);
  await fs.rename(tmp, target);
}

export async function clearApproval(slug: string, phase: Phase, cwd: string = process.cwd()): Promise<boolean> {
  const target = approvalPath(slug, phase, cwd);
  if (!existsSync(target)) return false;
  await fs.unlink(target);
  return true;
}

export function listApprovals(slug: string, cwd: string = process.cwd()): Phase[] {
  const dir = approvalsDir(slug, cwd);
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((f) => f.endsWith('.json'))
    .map((f) => f.replace('.json', '') as Phase)
    .filter((p) => APPROVABLE_PHASES.includes(p))
    .sort();
}

/**
 * Phase requires upstream approval when state.gated = true. Returns the
 * required upstream phase (or null if no approval required).
 *
 *   plan    requires approval of scope
 *   execute requires approval of plan
 *
 * Execute and assess are intentionally a single atomic unit — once plan is
 * approved, the autonomous execute/assess loop runs to convergence (or
 * MAX_ROUNDS) without further human intervention. The next human checkpoint
 * is `spear resolve`, which renders a closeout report for review before
 * merge / publish.
 */
export function requiredUpstreamApproval(forPhase: Phase): Phase | null {
  switch (forPhase) {
    case 'plan': return 'scope';
    case 'execute': return 'plan';
    default: return null;
  }
}

/**
 * Throw a clear error if state.gated is true and the upstream phase has no
 * approval recorded. Phase commands call this at the top to enforce the
 * checkpoint discipline.
 */
export function checkApprovalGate(
  slug: string,
  state: SpearState,
  forPhase: Phase,
  skipApproval: boolean = false,
  cwd: string = process.cwd(),
): void {
  if (!state.gated) return;
  if (skipApproval) return;
  const required = requiredUpstreamApproval(forPhase);
  if (!required) return;
  if (!isApproved(slug, required, cwd)) {
    throw new Error(
      `${forPhase} requires approval of "${required}" first. ` +
        `Run \`spear approve ${required}${state.gated ? ` --name ${slug}` : ''}\` to record the checkpoint, ` +
        `or pass --skip-approval to bypass for this run.`,
    );
  }
}

export async function ensureRoundDir(slug: string, round: number, cwd: string = process.cwd()): Promise<string> {
  const dir = roundDir(slug, round, cwd);
  await fs.mkdir(path.join(dir, 'evidence'), { recursive: true });
  return dir;
}

/**
 * Atomic file write: write to a sibling .tmp file, then rename. Rename is
 * atomic on POSIX, so a partial write or Ctrl-C between steps cannot leave
 * the target file half-written. Use for any state/spec/per-round artifact
 * that must survive interrupts.
 */
export async function atomicWrite(filePath: string, data: string | Uint8Array, opts: { mode?: number } = {}): Promise<void> {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  const tmp = `${filePath}.tmp.${process.pid}`;
  await fs.writeFile(tmp, data, opts.mode !== undefined ? { mode: opts.mode } : undefined);
  await fs.rename(tmp, filePath);
}
