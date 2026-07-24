/**
 * spear execute — run the artifact's deterministic build pipeline.
 * Per-artifact-type adapters define exactly which commands to run.
 */
import kleur from 'kleur';
import { checkApprovalGate, phaseAtLeast, readState, resolveSlug, writeState } from '../state.js';
import { buildContext, getAdapter } from '../adapters/index.js';

export async function executeCmd(opts: { json?: boolean; name?: string; skipApproval?: boolean }): Promise<void> {
  const slug = resolveSlugOrExit(opts);
  const state = await readState(slug);
  if (!state) {
    fail(`No SPEAR project "${slug}" found. Run \`spear init <type> ${slug}\` first.`, opts);
    return;
  }

  if (!phaseAtLeast(state.phase, 'execute')) {
    fail(
      `Cannot execute: state.phase = "${state.phase}". Run \`spear scope\` and \`spear plan\` first ` +
        `(both must pass) before \`spear execute\`.`,
      opts,
    );
    return;
  }

  try {
    checkApprovalGate(slug, state, 'execute', !!opts.skipApproval);
  } catch (e) {
    fail((e as Error).message, opts);
    return;
  }

  const adapter = getAdapter(state.type);
  const ctx = buildContext(slug, state.type);
  const result = await adapter.execute(ctx);

  state.phase = result.success ? 'assess' : 'execute';
  await writeState(slug, state);

  if (opts.json) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    if (result.success) {
      console.log(kleur.green('✓ Execute complete.'));
      for (const step of result.steps) {
        console.log(`  ${step.success ? kleur.green('✓') : kleur.red('✗')} ${step.name}`);
      }
    } else {
      console.log(kleur.red('✗ Execute failed.'));
      for (const step of result.steps) {
        console.log(`  ${step.success ? kleur.green('✓') : kleur.red('✗')} ${step.name}`);
        if (!step.success && step.error) {
          console.log(kleur.dim('    ' + step.error));
        }
      }
    }
  }

  process.exit(result.success ? 0 : 1);
}

function fail(msg: string, opts: { json?: boolean }): void {
  if (opts.json) {
    console.log(JSON.stringify({ success: false, error: msg }));
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
