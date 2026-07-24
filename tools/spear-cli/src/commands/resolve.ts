/**
 * spear resolve — the close-out phase.
 *
 * Default action: render a project-closure report (highlights, lowlights, what
 * to test, warnings, next steps). Inside a git repo it doubles as a PR body;
 * outside one it stands alone as a handoff doc.
 *
 * Flags:
 *   --write [path]      write to file (default: CLOSEOUT.md). Without --write, prints to stdout.
 *   --template <path>   override the template (default: .spear/<slug>/pr-template.md or built-in)
 *   --next              [legacy] show next defect to fix during the assess loop
 *   --apply             [legacy] dispatch mechanical fixers (still a stub)
 *   --json              emit JSON (PRContext) instead of rendered markdown
 *   --name <slug>       pick a SPEAR project (auto-detected if only one)
 */
import { promises as fs } from 'fs';
import path from 'path';
import kleur from 'kleur';
import { readMd, resolveSlug } from '../state.js';
import { renderPR } from '../pr.js';

interface ResolveOpts {
  write?: string | boolean;
  template?: string;
  next?: boolean;
  apply?: boolean;
  json?: boolean;
  name?: string;
}

export async function resolveCmd(opts: ResolveOpts): Promise<void> {
  const slug = resolveSlugOrExit(opts);

  if (opts.next) return showNext(slug, opts);
  if (opts.apply) return applyMechanical(opts);

  const cwd = process.cwd();
  const { markdown, context } = await renderPR(slug, {
    cwd,
    templatePath: opts.template,
  });

  if (opts.json) {
    console.log(JSON.stringify(context, null, 2));
    return;
  }

  if (opts.write !== undefined) {
    const outPath = typeof opts.write === 'string' && opts.write.length > 0 ? opts.write : 'CLOSEOUT.md';
    const abs = path.isAbsolute(outPath) ? outPath : path.join(cwd, outPath);
    await fs.mkdir(path.dirname(abs), { recursive: true });
    const tmp = `${abs}.tmp.${process.pid}`;
    await fs.writeFile(tmp, markdown);
    await fs.rename(tmp, abs);
    console.log(kleur.green(`✓ Wrote ${path.relative(cwd, abs)}`));
    console.log(kleur.dim(`  ${context.rounds} round(s), ${context.evidenceCount} evidence items, ${context.defectsRemaining} defect(s) remaining.`));
    return;
  }

  process.stdout.write(markdown);
}

async function showNext(slug: string, opts: ResolveOpts): Promise<void> {
  const md = await readMd(slug, 'resolve');
  if (!md) {
    if (opts.json) console.log(JSON.stringify({ defect: null }));
    else console.log(kleur.dim('No RESOLVE.md found. Run `spear assess` first.'));
    return;
  }
  const defects = parseDefects(md);
  if (defects.length === 0) {
    if (opts.json) console.log(JSON.stringify({ defect: null }));
    else console.log(kleur.green('✓ No defects.'));
    return;
  }
  const next = defects[0];
  if (opts.json) console.log(JSON.stringify({ defect: next }));
  else console.log(`Next: ${kleur.cyan(next)}`);
}

async function applyMechanical(_opts: ResolveOpts): Promise<void> {
  console.log(kleur.dim('--apply: per-adapter mechanical-fix dispatch not yet implemented.'));
  console.log(kleur.dim('Defects flagged as [mech] in `spear assess` output need adapter-specific fixers.'));
}

function parseDefects(md: string): string[] {
  const lines = md.split('\n');
  const out: string[] = [];
  let inDefects = false;
  for (const line of lines) {
    if (line.startsWith('## Defects to fix')) inDefects = true;
    else if (line.startsWith('## ')) inDefects = false;
    if (inDefects) {
      const m = line.match(/^\d+\.\s+\*\*(.+?)\*\*\s+—\s+(.+)/);
      if (m) out.push(`${m[1]} — ${m[2]}`);
    }
  }
  return out;
}

function resolveSlugOrExit(opts: { name?: string }): string {
  try {
    return resolveSlug(opts.name);
  } catch (e) {
    console.error(kleur.red('✗ ' + (e as Error).message));
    process.exit(1);
  }
}
