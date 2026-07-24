/**
 * spear plan — validate PLAN.md exists and has a numbered step list.
 * Does NOT generate the plan content — that's the LLM's job. The CLI just
 * enforces "PLAN.md must exist and be reviewed before Execute can start."
 */
import kleur from 'kleur';
import { checkApprovalGate, readMd, readState, resolveSlug, writeState } from '../state.js';

export async function planCmd(opts: { json?: boolean; name?: string; skipApproval?: boolean }): Promise<void> {
  const slug = resolveSlugOrExit(opts);
  const state = await readState(slug);
  if (state) {
    try {
      checkApprovalGate(slug, state, 'plan', !!opts.skipApproval);
    } catch (e) {
      console.error(kleur.red('✗ ' + (e as Error).message));
      process.exit(1);
    }
  }
  const md = await readMd(slug, 'plan');
  if (md === null) {
    fail(`PLAN.md not found for "${slug}".`, opts, 'Have the LLM write the plan, then re-run.');
    return;
  }

  const stepCount = countSteps(md);
  const approved = isApproved(md);

  const report = {
    valid: stepCount > 0 && approved,
    stepCount,
    approved,
  };

  if (state && report.valid) {
    state.phase = 'execute';
    await writeState(slug, state);
  }

  if (opts.json) {
    console.log(JSON.stringify(report, null, 2));
  } else {
    if (report.valid) {
      console.log(kleur.green(`✓ PLAN.md is valid (${stepCount} steps, approved).`));
    } else {
      console.log(kleur.red('✗ PLAN.md is incomplete:'));
      if (stepCount === 0) console.log('  - no numbered steps found');
      if (!approved) console.log('  - not yet approved (mark `[x] User confirmed` in PLAN.md)');
    }
  }

  process.exit(report.valid ? 0 : 1);
}

function countSteps(md: string): number {
  const matches = md.match(/^\d+\.\s+/gm);
  return matches ? matches.length : 0;
}

function isApproved(md: string): boolean {
  return /\[x\]\s*User confirmed/i.test(md);
}

function fail(msg: string, opts: { json?: boolean }, hint?: string): void {
  if (opts.json) {
    console.log(JSON.stringify({ valid: false, error: msg, hint }));
  } else {
    console.error(kleur.red('✗ ' + msg));
    if (hint) console.error(kleur.dim('  ' + hint));
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
