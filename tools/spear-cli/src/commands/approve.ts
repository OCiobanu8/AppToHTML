/**
 * spear approve <phase> — write a checkpoint approval for an upstream phase.
 *
 * Used in `--gated` projects: each phase command refuses to run unless the
 * upstream phase has an approval file under .spear/<slug>/.approvals/.
 *
 *   spear scope                  # validates SCOPE.md
 *   spear approve scope          # human checkpoint: "OK to plan"
 *   spear plan                   # now allowed (would be blocked without approval in --gated mode)
 *
 * Available phases: scope, plan, execute, assess.
 *
 * Flags:
 *   --revoke      remove a previously written approval
 *   --list        list which approvals are currently set
 *   --json        emit JSON
 */
import kleur from 'kleur';
import {
  Phase,
  approvalPath,
  clearApproval,
  isApproved,
  listApprovals,
  readState,
  resolveSlug,
  writeApproval,
} from '../state.js';

interface ApproveOpts {
  name?: string;
  revoke?: boolean;
  list?: boolean;
  json?: boolean;
}

const VALID_PHASES: Phase[] = ['scope', 'plan', 'execute', 'assess'];

export async function approveCmd(phase: string | undefined, opts: ApproveOpts): Promise<void> {
  const slug = resolveSlugOrExit(opts);
  const cwd = process.cwd();

  if (opts.list || !phase) {
    const approved = listApprovals(slug, cwd);
    const result = { slug, approved, all: VALID_PHASES };
    if (opts.json) {
      console.log(JSON.stringify(result, null, 2));
    } else {
      console.log(kleur.bold(`Approvals for ${slug}:`));
      for (const p of VALID_PHASES) {
        const has = approved.includes(p);
        console.log(`  ${has ? kleur.green('✓') : kleur.dim('○')} ${p}`);
      }
    }
    return;
  }

  if (!VALID_PHASES.includes(phase as Phase)) {
    console.error(kleur.red(`✗ Unknown phase: ${phase}`));
    console.error(kleur.dim(`  Valid phases: ${VALID_PHASES.join(', ')}`));
    process.exit(1);
  }

  const state = await readState(slug, cwd);
  if (!state) {
    console.error(kleur.red(`✗ No SPEAR project "${slug}" found.`));
    process.exit(1);
  }

  if (opts.revoke) {
    const removed = await clearApproval(slug, phase as Phase, cwd);
    if (opts.json) {
      console.log(JSON.stringify({ slug, phase, revoked: removed }));
    } else if (removed) {
      console.log(kleur.green(`✓ Approval for "${phase}" revoked.`));
    } else {
      console.log(kleur.dim(`No approval for "${phase}" was set.`));
    }
    return;
  }

  // Default: write approval
  if (isApproved(slug, phase as Phase, cwd)) {
    if (opts.json) {
      console.log(JSON.stringify({ slug, phase, approved: true, alreadySet: true }));
    } else {
      console.log(kleur.dim(`Approval for "${phase}" was already set.`));
    }
    return;
  }

  await writeApproval(slug, phase as Phase, cwd);
  if (opts.json) {
    console.log(JSON.stringify({ slug, phase, approved: true, path: approvalPath(slug, phase as Phase, cwd) }));
  } else {
    console.log(kleur.green(`✓ Approval recorded for "${phase}".`));
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
