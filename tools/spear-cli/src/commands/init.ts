/**
 * spear init <type> [name] — scaffold a SPEAR project under .spear/<name>/.
 *
 * Deterministic file copy from templates/<type>/ into .spear/<name>/.
 * If [name] is omitted, defaults to <type>. One repo can host multiple
 * projects, each with its own slug — workspace + output directories are
 * scoped under the slug too.
 */
import { promises as fs } from 'fs';
import { existsSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import kleur from 'kleur';
import {
  SPEAR_DIR,
  projectDir,
  specPath,
  validateSlug,
  writeState,
} from '../state.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES_DIR = path.resolve(__dirname, '..', '..', 'templates');

const VALID_TYPES = ['deck', 'blog', 'code', 'generic'] as const;
type ProjectType = (typeof VALID_TYPES)[number];

const SPEC_FILES: Array<{ name: 'scope' | 'plan' | 'assess' | 'resolve'; file: string }> = [
  { name: 'scope', file: 'SCOPE.md' },
  { name: 'plan', file: 'PLAN.md' },
  { name: 'assess', file: 'ASSESS.md' },
  { name: 'resolve', file: 'RESOLVE.md' },
];

export async function initCmd(type: string, name: string | undefined, opts: { force?: boolean; gated?: boolean }): Promise<void> {
  if (!VALID_TYPES.includes(type as ProjectType)) {
    console.error(kleur.red(`Unknown type: ${type}`));
    console.error(`Valid types: ${VALID_TYPES.join(', ')}`);
    process.exit(1);
  }

  const slug = name ?? type;
  try {
    validateSlug(slug);
  } catch (e) {
    console.error(kleur.red('✗ ' + (e as Error).message));
    process.exit(1);
  }

  const src = path.join(TEMPLATES_DIR, type);
  const cwd = process.cwd();
  const dst = projectDir(slug, cwd);

  if (!existsSync(src)) {
    console.error(kleur.red(`Template not found: ${src}`));
    console.error('The CLI may not be installed correctly.');
    process.exit(1);
  }

  await fs.mkdir(dst, { recursive: true });

  for (const { file } of SPEC_FILES) {
    const srcFile = path.join(src, file);
    const dstFile = path.join(dst, file);
    if (existsSync(dstFile) && !opts.force) {
      console.error(kleur.yellow(`Exists, skipping: ${path.relative(cwd, dstFile)} (--force to overwrite)`));
      continue;
    }
    if (existsSync(srcFile)) {
      await fs.copyFile(srcFile, dstFile);
      console.log(kleur.green('  + ') + path.relative(cwd, dstFile));
    }
  }

  // Per-slug workspace + output directories. These hold the actual artifact
  // (e.g., the deck source, blog draft) and the build outputs. Keeping them
  // under .spear/<slug>/ means multiple slugs in one repo don't collide.
  for (const dir of ['workspace', 'output']) {
    await fs.mkdir(path.join(dst, dir), { recursive: true });
  }

  if (type === 'deck') {
    const pkgPath = path.join(dst, 'workspace', 'deck', 'package.json');
    if (!existsSync(pkgPath)) {
      await fs.mkdir(path.dirname(pkgPath), { recursive: true });
      await fs.writeFile(
        pkgPath,
        JSON.stringify(
          {
            name: `deck-${slug}`,
            type: 'module',
            private: true,
            dependencies: { pptxgenjs: '^3.12.0' },
          },
          null,
          2,
        ) + '\n',
      );
      console.log(kleur.green('  + ') + path.relative(cwd, pkgPath));
    }
  }

  await writeState(slug, {
    type: type as ProjectType,
    slug,
    round: 0,
    phase: 'scope',
    maxRounds: 20,
    gated: opts.gated || undefined,
  }, cwd);

  console.log();
  console.log(kleur.bold(`✓ SPEAR project "${slug}" initialized at ${SPEAR_DIR}/${slug}/.`));
  console.log();
  console.log('Next steps:');
  const scopeRel = path.relative(cwd, specPath(slug, 'scope', cwd));
  console.log(`  1. Edit ${kleur.cyan(scopeRel)} — fill in goal, audience, constraints, done means`);
  console.log(`  2. Run ${kleur.cyan('spear scope' + (slug !== type ? ` --name ${slug}` : ''))} to validate scope`);
  console.log(`  3. Run ${kleur.cyan('spear loop' + (slug !== type ? ` --name ${slug}` : ''))} to execute the full SPEAR pipeline`);
}
