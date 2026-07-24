/**
 * spear assess — run the rubric checks, write RESOLVE.md, exit nonzero if defects.
 *
 * Three layers:
 *   1. Mechanical checks → pass/expected/actual evidence
 *   2. Subjective checks → evidence pointing at the artifact
 *   3. Stuck-loop detection: if defectCount unchanged for ≥2 rounds, flag it
 *
 * Per-round artifacts persist under .spear/<slug>/rounds/N/.
 *
 * Exit codes: 0 = converged, 2 = defects open
 */
import path from 'path';
import kleur from 'kleur';
import {
  atomicWrite,
  checkApprovalGate,
  ensureRoundDir,
  readMd,
  readState,
  resolveSlug,
  roundDir,
  writeMd,
  writeState,
} from '../state.js';
import { buildContext, getAdapter } from '../adapters/index.js';
import { persistEvidence } from '../evidence.js';
import { buildGraderPromptFromFiles, graderToEvidence, resolveGraderFiles, runGrader } from '../grader.js';
import type { AssessResult } from '../types.js';

export async function assessCmd(opts: {
  json?: boolean;
  fast?: boolean;
  name?: string;
  skipApproval?: boolean;
  grader?: string;
  gradeFiles?: string;
}): Promise<void> {
  const slug = resolveSlugOrExit(opts);
  const cwd = process.cwd();
  const startedAt = Date.now();
  const state = await readState(slug);
  if (!state) {
    console.error(kleur.red(`✗ No SPEAR project "${slug}" found.`));
    process.exit(1);
  }

  try {
    checkApprovalGate(slug, state, 'assess', !!opts.skipApproval);
  } catch (e) {
    console.error(kleur.red('✗ ' + (e as Error).message));
    process.exit(1);
  }

  const adapter = getAdapter(state.type);
  const ctx = buildContext(slug, state.type, cwd);
  const { defects, evidence } = await adapter.assess(ctx, { fast: !!opts.fast });

  // Sub-agent grader: run the subjective grading in a fresh subprocess with
  // an adversarial prompt, separate from the drafting context. Solves the
  // rubber-stamp problem structurally (the drafting LLM never sees the
  // grader's prompt or scoring reasoning).
  if (opts.grader) {
    try {
      const rubricMd = await readMd(slug, 'assess', cwd);
      if (!rubricMd) throw new Error(`ASSESS.md not found for "${slug}"`);

      // File list comes from --grade-files override OR adapter's default
      let filePaths: string[];
      if (opts.gradeFiles && opts.gradeFiles.trim()) {
        filePaths = opts.gradeFiles.split(',').map((s) => s.trim()).filter(Boolean);
      } else if (adapter.defaultGraderArtifacts) {
        filePaths = adapter.defaultGraderArtifacts(ctx);
      } else {
        filePaths = [];
      }

      if (filePaths.length === 0) {
        console.error(kleur.yellow(
          `⚠ --grader: no artifact files to grade. Pass --grade-files <path,path,...> or ensure the adapter has produced output.`,
        ));
      } else {
        const files = resolveGraderFiles(filePaths, cwd);
        const prompt = buildGraderPromptFromFiles({
          rubricMd,
          files,
          cwd,
          artifactType: state.type,
        });
        if (!opts.json) {
          console.log(kleur.dim(`→ running grader (${opts.grader}) on ${files.length} file(s)...`));
        }
        const result = await runGrader({ cmd: opts.grader, prompt });
        const { evidence: graderEv, defects: graderDef } = graderToEvidence(result.output);
        evidence.push(...graderEv);
        defects.push(...graderDef);
        if (!opts.json) {
          console.log(
            kleur.dim(
              `  grader returned ${result.output.metrics.length} metric scores ` +
                `+ ${result.output.failure_modes.filter((f) => f.open).length} open failure modes ` +
                `in ${result.durationMs}ms`,
            ),
          );
        }
      }
    } catch (e) {
      console.error(kleur.red(`✗ grader failed: ${(e as Error).message}`));
      console.error(kleur.dim('  Continuing with adapter-only assessment.'));
    }
  }

  const round = state.round + 1;
  const timestamp = new Date().toISOString();

  const prevCount = state.lastRoundDefectCount;
  const stuck = prevCount !== undefined && prevCount === defects.length && round > 1 && defects.length > 0;
  const stuckSince = stuck ? state.stuckSince ?? round - 1 : undefined;

  const result: AssessResult = {
    round,
    totalUnits: 0,
    perUnitScores: {},
    defects,
    evidence,
    converged: defects.length === 0,
    timestamp,
    stuck: stuck || undefined,
    stuckSince,
  };

  for (const d of defects) {
    result.totalUnits = Math.max(result.totalUnits, parseUnitNumber(d.unit) ?? 0);
    result.perUnitScores[d.unit] = (result.perUnitScores[d.unit] ?? 10) - 1;
  }

  await ensureRoundDir(slug, round, cwd);
  const persisted = await persistEvidence(slug, round, evidence, cwd);
  result.evidence = persisted;

  const dir = roundDir(slug, round, cwd);
  await atomicWrite(path.join(dir, 'assess.json'), JSON.stringify(result, null, 2) + '\n');

  const resolveMd = renderResolveMd(result);
  await writeMd(slug, 'resolve', resolveMd);
  await atomicWrite(path.join(dir, 'RESOLVE.md'), resolveMd);

  state.round = round;
  state.phase = result.converged ? 'converged' : 'resolve';
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
    durationMs: Date.now() - startedAt,
    timestamp,
  });
  await writeState(slug, state);

  if (opts.json) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    if (result.converged) {
      console.log(kleur.green('✓ Converged — no defects.'));
      console.log(kleur.dim(`  ${persisted.length} evidence items written to ${path.relative(cwd, dir)}/`));
    } else {
      console.log(kleur.yellow(`Round ${result.round}: ${defects.length} defect(s), ${persisted.length} evidence items.`));
      if (stuck) {
        console.log(
          kleur.red(
            `  ⚠ Stuck since round ${stuckSince}: defect count unchanged. Inspect RESOLVE.md, consider revising approach.`,
          ),
        );
      }
      for (const d of defects.slice(0, 10)) {
        const sev = d.severity === 'high' ? kleur.red : d.severity === 'medium' ? kleur.yellow : kleur.dim;
        const tag = d.mechanical ? '[mech]' : '[llm] ';
        console.log(`  ${sev('●')} ${tag} ${d.unit} / ${d.metric}: ${d.description}`);
      }
      if (defects.length > 10) {
        console.log(kleur.dim(`  ...and ${defects.length - 10} more (see RESOLVE.md)`));
      }
      console.log(kleur.dim(`  Per-round dir: ${path.relative(cwd, dir)}/`));
    }
  }

  process.exit(result.converged ? 0 : 2);
}

function parseUnitNumber(unit: string): number | null {
  const m = unit.match(/(\d+)/);
  return m ? parseInt(m[1], 10) : null;
}

function renderResolveMd(result: AssessResult): string {
  const lines = [
    `# RESOLVE — Round ${result.round}`,
    '',
    `Convergence: ${result.converged ? '✓ PASS' : `${result.defects.length} defects open`}`,
    `Timestamp: ${result.timestamp}`,
    `Evidence items: ${result.evidence.length}`,
  ];
  if (result.stuck) {
    lines.push(`⚠ Stuck since round ${result.stuckSince} — defect count unchanged across rounds.`);
  }
  lines.push('');
  if (result.converged) {
    lines.push('No defects. Loop complete.');
    lines.push('');
    lines.push(reportTemplate(result));
    return lines.join('\n') + '\n';
  }

  lines.push('## Defects to fix');
  lines.push('');
  result.defects.forEach((d, i) => {
    lines.push(`${i + 1}. **${d.unit} / ${d.metric}** — ${d.description}`);
    if (d.suggestedFix) lines.push(`   - Fix: ${d.suggestedFix}`);
    lines.push(`   - ${d.mechanical ? 'Mechanical (CLI can auto-fix)' : 'Subjective (LLM judgment)'}`);
    if (d.evidenceId) lines.push(`   - Evidence: \`${d.evidenceId}\``);
    lines.push('');
  });

  lines.push('## Evidence');
  lines.push('');
  lines.push('Mechanical checks (pass/fail with expected vs actual) and subjective pointers (artifacts to read).');
  lines.push(`Full list in \`evidence.json\` under the round dir.`);
  lines.push('');
  for (const ev of result.evidence) {
    const head = ev.kind === 'mechanical'
      ? `- [${ev.pass ? '✓' : '✗'}] **${ev.id}** — ${ev.description} (expected ${JSON.stringify(ev.expected)}, got ${JSON.stringify(ev.actual)})`
      : `- [→] **${ev.id}** — ${ev.description}` + (ev.artifact ? ` → \`${ev.artifact}\`` : '');
    lines.push(head);
  }
  lines.push('');

  lines.push(reportTemplate(result));
  return lines.join('\n') + '\n';
}

function reportTemplate(result: AssessResult): string {
  return [
    '## Report (LLM fills this in after applying fixes)',
    '',
    'Replace this template with a real <spear-report> block. SPEAR parses it on the next loop call.',
    '',
    '```',
    '<spear-report>',
    `ITERATION: ${result.round}`,
    'PHASE: resolve',
    'COMPLETED: <what you fixed this round>',
    'FILES_CHANGED: <comma-separated paths>',
    'TESTS: <pass/fail/N/A>',
    'NEXT: re-run spear loop',
    'BLOCKERS: None',
    `PROGRESS: <fixed>/${result.defects.length}`,
    '</spear-report>',
    '```',
    '',
    'When the rubric is satisfied, add `<spear-complete/>` on its own line above the report block to stop the loop.',
  ].join('\n');
}

function resolveSlugOrExit(opts: { name?: string }): string {
  try {
    return resolveSlug(opts.name);
  } catch (e) {
    console.error(kleur.red('✗ ' + (e as Error).message));
    process.exit(1);
  }
}
