/**
 * spear loop — orchestrate the full pipeline.
 *
 * Validates → executes → assesses → loops. Stops on convergence, MAX_ROUNDS,
 * fatal error, or `<spear-complete/>` in the current RESOLVE.md.
 */
import path from 'path';
import kleur from 'kleur';
import {
  atomicWrite,
  ensureRoundDir,
  readMd,
  readState,
  resolveSlug,
  roundDir,
  writeState,
} from '../state.js';
import { buildContext, getAdapter } from '../adapters/index.js';
import { hasCompleteSignal, isBlocked, parseReport } from '../report.js';
import { persistEvidence } from '../evidence.js';
import type { AssessResult, SpearReport } from '../types.js';

export async function loopCmd(opts: { maxRounds?: string; json?: boolean; name?: string; allowFastConvergence?: boolean; skipApproval?: boolean }): Promise<void> {
  const slug = resolveSlugOrExit(opts);
  const cwd = process.cwd();
  const state = await readState(slug);
  if (!state) {
    console.error(kleur.red(`✗ No SPEAR project "${slug}" found.`));
    process.exit(1);
  }

  const existingResolve = await readMd(slug, 'resolve');
  if (existingResolve && hasCompleteSignal(existingResolve)) {
    // Anti-rubber-stamp guard: refuse <spear-complete/> when state suggests
    // self-rubber-stamping (round 1 with reported defects, zero fixes, or a
    // monotonic 10/10 history without any defect downturn). Bypass with
    // --allow-fast-convergence for the legitimate "nothing to fix" case.
    if (!opts.allowFastConvergence) {
      const guard = rubberStampGuard(state, existingResolve);
      if (guard.suspicious) {
        console.error(kleur.red(`✗ Rubber-stamp guard: ${guard.reason}`));
        console.error(
          kleur.dim(
            '  Run at least one assess round with concrete fixes before signaling complete, ' +
              'or pass --allow-fast-convergence if you genuinely had no defects to address.',
          ),
        );
        process.exit(1);
      }
    }
    state.phase = 'converged';
    state.completedAt = new Date().toISOString();
    await writeState(slug, state);
    report({ phase: 'converged', round: state.round, success: true, completed: 'user-signaled' }, opts);
    return;
  }

  if (existingResolve) {
    const r = parseReport(existingResolve);
    if (r) applyReportToState(state, r);
  }

  const cap = opts.maxRounds ? parseInt(opts.maxRounds, 10) : state.maxRounds;
  const adapter = getAdapter(state.type);
  const ctx = buildContext(slug, state.type, cwd);

  for (let i = 0; i < cap; i++) {
    const roundStart = Date.now();

    const ex = await adapter.execute(ctx);
    if (!ex.success) {
      state.failureReason = ex.steps.find((s) => !s.success)?.error ?? 'execute failed';
      await writeState(slug, state);
      report({ phase: 'execute', round: state.round, success: false, ex }, opts);
      process.exit(1);
    }
    state.failureReason = undefined;

    const { defects, evidence } = await adapter.assess(ctx, { fast: false });
    const round = state.round + 1;
    const timestamp = new Date().toISOString();

    const prevCount = state.lastRoundDefectCount;
    const stuck = prevCount !== undefined && prevCount === defects.length && round > 1 && defects.length > 0;
    const stuckSince = stuck ? state.stuckSince ?? round - 1 : undefined;

    await ensureRoundDir(slug, round, cwd);
    const persisted = await persistEvidence(slug, round, evidence, cwd);

    const result: AssessResult = {
      round,
      totalUnits: 0,
      perUnitScores: {},
      defects,
      evidence: persisted,
      converged: defects.length === 0,
      timestamp,
      stuck: stuck || undefined,
      stuckSince,
    };

    const dir = roundDir(slug, round, cwd);
    await atomicWrite(path.join(dir, 'assess.json'), JSON.stringify(result, null, 2) + '\n');

    state.round = round;
    state.lastAssess = {
      defectCount: defects.length,
      evidenceCount: persisted.length,
      timestamp,
    };
    state.lastRoundDefectCount = defects.length;
    state.stuckSince = stuckSince;
    state.history = (state.history ?? []).slice(-9);
    state.history.push({
      round,
      defectCount: defects.length,
      durationMs: Date.now() - roundStart,
      timestamp,
    });

    if (defects.length === 0) {
      state.phase = 'converged';
      await writeState(slug, state);
      report({ phase: 'converged', round, success: true, evidenceCount: persisted.length }, opts);
      return;
    }

    state.phase = 'resolve';
    await writeState(slug, state);
    report(
      {
        phase: 'resolve',
        round,
        defects,
        openDefectCount: defects.length,
        evidenceCount: persisted.length,
        stuck,
        stuckSince,
      },
      opts,
    );
    process.exit(2);
  }

  state.phase = 'resolve';
  await writeState(slug, state);
  report({ phase: 'resolve', round: state.round, exhausted: true }, opts);
  process.exit(3);
}

function applyReportToState(state: NonNullable<Awaited<ReturnType<typeof readState>>>, r: SpearReport): void {
  if (r.blockers !== undefined) {
    state.blockers = isBlocked(r) ? r.blockers : undefined;
  }
  if (r.progress !== undefined && state.lastAssess) {
    state.lastAssess.progress = r.progress;
  }
  if (r.completed !== undefined && state.lastAssess) {
    state.lastAssess.fixed = r.completed.split(',').filter(Boolean).length || undefined;
  }
}

function report(payload: Record<string, unknown>, opts: { json?: boolean }): void {
  if (opts.json) {
    console.log(JSON.stringify(payload, null, 2));
    return;
  }
  if (payload.phase === 'converged') {
    if (payload.completed === 'user-signaled') {
      console.log(kleur.green(`✓ Stopped: <spear-complete/> honored at round ${payload.round}.`));
    } else {
      console.log(kleur.green(`✓ Converged at round ${payload.round}.`));
      if (payload.evidenceCount) {
        console.log(kleur.dim(`  ${payload.evidenceCount} evidence items recorded.`));
      }
    }
  } else if (payload.exhausted) {
    console.log(kleur.yellow(`MAX_ROUNDS hit at round ${payload.round}. Open defects remain.`));
  } else if (payload.phase === 'resolve') {
    console.log(kleur.yellow(`Round ${payload.round}: ${payload.openDefectCount} defects, ${payload.evidenceCount} evidence items.`));
    if (payload.stuck) {
      console.log(kleur.red(`  ⚠ Stuck since round ${payload.stuckSince}.`));
    }
    console.log(kleur.dim('  → LLM: read RESOLVE.md, apply fixes, append <spear-report>, re-run `spear loop`.'));
  } else if (payload.phase === 'execute' && !payload.success) {
    console.log(kleur.red(`Round ${payload.round}: execute failed.`));
  }
}

function resolveSlugOrExit(opts: { name?: string }): string {
  try {
    return resolveSlug(opts.name);
  } catch (e) {
    console.error(kleur.red('✗ ' + (e as Error).message));
    process.exit(1);
  }
}

/**
 * Anti-rubber-stamp guard. Returns { suspicious: true, reason } if the current
 * state + RESOLVE.md report look like the LLM declared convergence without
 * doing real work. The "deck case" took 30+ rounds; a one-round self-completion
 * with zero fixes is a smell.
 *
 * Heuristics:
 *   1. round === 1 AND latest spear-report shows DEFECTS_FIXED: 0
 *   2. lastRoundDefectCount > 0 AND round === 1 (defects reported, none fixed)
 *   3. history is monotonic 0 defects from round 1 with no defect-trend evidence
 *
 * Bypass with --allow-fast-convergence (or simply add concrete fixes and re-loop).
 */
function rubberStampGuard(
  state: NonNullable<Awaited<ReturnType<typeof readState>>>,
  resolveMd: string,
): { suspicious: boolean; reason: string } {
  const report = parseReport(resolveMd);

  // Round-1 self-completion with reported defects
  if (state.round <= 1 && (state.lastAssess?.defectCount ?? 0) > 0) {
    return {
      suspicious: true,
      reason: `round ${state.round} convergence with ${state.lastAssess?.defectCount} defect(s) reported by assess`,
    };
  }

  // Round-1 self-completion with DEFECTS_FIXED: 0 (or absent)
  if (state.round <= 1 && report) {
    const fixedField = report.extras?.DEFECTS_FIXED?.trim();
    const fixedNum = fixedField ? parseInt(fixedField, 10) : NaN;
    const completedField = report.completed?.trim();
    const completedItems = completedField
      ? completedField.split(/[,;]/).map((s) => s.trim()).filter(Boolean).length
      : 0;
    if ((Number.isFinite(fixedNum) && fixedNum === 0) || completedItems === 0) {
      return {
        suspicious: true,
        reason: `round 1 convergence with no concrete fixes reported (DEFECTS_FIXED=${fixedField ?? 'absent'}, COMPLETED items=${completedItems})`,
      };
    }
  }

  return { suspicious: false, reason: '' };
}
