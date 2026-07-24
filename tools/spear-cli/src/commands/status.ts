/**
 * spear status — show current phase + open defects for one project.
 * Designed for piping: `spear status --json | jq .phase`
 *
 * Single-project repos: auto-resolves. Multi-project: --name <slug> required.
 * Use `spear list` to see all projects in a repo.
 */
import kleur from 'kleur';
import { existsSync } from 'fs';
import {
  readState,
  resolveSlug,
  specPath,
  statePath,
} from '../state.js';

export async function statusCmd(opts: { json?: boolean; name?: string }): Promise<void> {
  const slug = resolveSlugOrExit(opts);
  const state = await readState(slug);
  if (!state) {
    if (opts.json) console.log(JSON.stringify({ initialized: false, slug }));
    else console.log(kleur.dim(`No SPEAR project "${slug}".`));
    process.exit(1);
  }

  const cwd = process.cwd();
  const status = {
    initialized: true,
    slug,
    type: state.type,
    phase: state.phase,
    round: state.round,
    maxRounds: state.maxRounds,
    files: {
      scope: existsSync(specPath(slug, 'scope', cwd)),
      plan: existsSync(specPath(slug, 'plan', cwd)),
      assess: existsSync(specPath(slug, 'assess', cwd)),
      resolve: existsSync(specPath(slug, 'resolve', cwd)),
      state: existsSync(statePath(slug, cwd)),
    },
    lastAssess: state.lastAssess,
    blockers: state.blockers,
    stuckSince: state.stuckSince,
    completedAt: state.completedAt,
  };

  if (opts.json) {
    console.log(JSON.stringify(status, null, 2));
    return;
  }

  console.log(kleur.bold(`SPEAR project: ${slug}`));
  console.log(`  type:  ${kleur.cyan(status.type)}`);
  console.log(`  phase: ${kleur.cyan(status.phase)}`);
  console.log(`  round: ${status.round} / ${status.maxRounds}`);
  if (status.lastAssess) {
    console.log(`  last assess: ${status.lastAssess.defectCount} defect(s) @ ${status.lastAssess.timestamp}`);
  }
  if (status.blockers) console.log(kleur.red(`  blockers: ${status.blockers}`));
  if (status.stuckSince) console.log(kleur.yellow(`  stuck since: round ${status.stuckSince}`));
  if (status.completedAt) console.log(kleur.green(`  completed: ${status.completedAt}`));
}

function resolveSlugOrExit(opts: { name?: string }): string {
  try {
    return resolveSlug(opts.name);
  } catch (e) {
    console.error(kleur.red('✗ ' + (e as Error).message));
    process.exit(1);
  }
}
