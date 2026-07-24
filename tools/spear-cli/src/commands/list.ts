/**
 * spear list — enumerate every SPEAR project in the current repo.
 *
 * Reads each .spear/<slug>/state.json and prints a one-line summary per slug.
 * Designed for both human glance and piping: `spear list --json | jq`.
 */
import kleur from 'kleur';
import { listSlugs, readState } from '../state.js';

interface Row {
  slug: string;
  type: string;
  phase: string;
  round: number;
  maxRounds: number;
  defectCount?: number;
  blockers?: string;
  stuckSince?: number;
  completedAt?: string;
}

export async function listCmd(opts: { json?: boolean }): Promise<void> {
  const slugs = listSlugs();
  if (slugs.length === 0) {
    if (opts.json) console.log(JSON.stringify({ projects: [] }));
    else console.log(kleur.dim('No SPEAR projects in this directory. Run `spear init <type> [name]` first.'));
    return;
  }

  const rows: Row[] = [];
  for (const slug of slugs) {
    const s = await readState(slug);
    if (!s) continue;
    rows.push({
      slug,
      type: s.type,
      phase: s.phase,
      round: s.round,
      maxRounds: s.maxRounds,
      defectCount: s.lastAssess?.defectCount,
      blockers: s.blockers,
      stuckSince: s.stuckSince,
      completedAt: s.completedAt,
    });
  }

  if (opts.json) {
    console.log(JSON.stringify({ projects: rows }, null, 2));
    return;
  }

  const header = pad('NAME', 16) + pad('TYPE', 10) + pad('PHASE', 12) + pad('ROUND', 9) + pad('DEFECTS', 9) + 'STATUS';
  console.log(kleur.bold(header));
  console.log('-'.repeat(header.length));
  for (const r of rows) {
    const phaseColor = r.phase === 'converged' ? kleur.green
      : r.phase === 'execute' || r.phase === 'assess' || r.phase === 'resolve' ? kleur.yellow
      : kleur.dim;
    const status = r.completedAt ? kleur.green('✓ complete')
      : r.blockers ? kleur.red(`❌ ${r.blockers.slice(0, 30)}`)
      : r.stuckSince ? kleur.yellow(`⚠ stuck since r${r.stuckSince}`)
      : r.phase === 'converged' ? kleur.green('✓ converged')
      : '';
    console.log(
      pad(r.slug, 16) +
      pad(r.type, 10) +
      pad(phaseColor(r.phase), 12 + (phaseColor === kleur.dim ? 0 : 9)) +
      pad(`${r.round}/${r.maxRounds}`, 9) +
      pad(String(r.defectCount ?? '—'), 9) +
      status,
    );
  }
}

function pad(s: string, n: number): string {
  // Approximate visible width: ANSI escapes don't count
  const visible = s.replace(/\[[0-9;]*m/g, '');
  return s + ' '.repeat(Math.max(0, n - visible.length));
}
