/**
 * Deck adapter — pptxgenjs build + LibreOffice render + per-slide JPEG checks.
 *
 * Workspace layout (relative to ctx.workspaceDir = .spear/<slug>/workspace):
 *   deck/build.js         — pptxgenjs source (LLM-generated)
 *   deck/package.json     — npm deps
 *   qa/v-NN.jpg           — rendered slide images
 *
 * Output: .spear/<slug>/output/deck.pptx
 *
 * Mechanical checks emit Evidence with expected/actual; subjective checks
 * (pyramid principle, MECE, voice) are deferred to the LLM via Evidence
 * pointing at each rendered JPEG.
 */
import { execSync, spawnSync } from 'child_process';
import { existsSync, statSync, readdirSync } from 'fs';
import path from 'path';
import type { Adapter, AdapterContext, AssessOutput, ExecuteResult } from './index.js';
import type { Defect, Evidence } from '../types.js';
import { fileEvidence, valueEvidence } from '../evidence.js';

export const deckAdapter: Adapter = {
  async execute(ctx: AdapterContext): Promise<ExecuteResult> {
    const steps: ExecuteResult['steps'] = [];
    const buildJs = path.join(ctx.workspaceDir, 'deck', 'build.js');

    if (!existsSync(buildJs)) {
      steps.push({
        name: `${path.relative(ctx.cwd, buildJs)} exists`,
        success: false,
        error: 'Have the LLM generate build.js based on PLAN.md',
      });
      return { success: false, steps };
    }
    steps.push({ name: `${path.relative(ctx.cwd, buildJs)} exists`, success: true });

    const deckDir = path.join(ctx.workspaceDir, 'deck');
    if (!existsSync(path.join(deckDir, 'node_modules'))) {
      const r = spawnSync('npm', ['install', '--silent'], { cwd: deckDir, stdio: 'pipe' });
      const success = r.status === 0;
      steps.push({ name: 'npm install', success, error: success ? undefined : r.stderr?.toString() });
      if (!success) return { success: false, steps };
    }

    const outputDir = path.join(ctx.projectDir, 'output');
    const r = spawnSync('node', ['build.js'], {
      cwd: deckDir,
      stdio: 'pipe',
      env: { ...process.env, OUTPUT_DIR: outputDir },
    });
    const buildSuccess = r.status === 0;
    steps.push({ name: 'node build.js', success: buildSuccess, error: buildSuccess ? undefined : r.stderr?.toString() });
    if (!buildSuccess) return { success: false, steps };

    const pptx = path.join(outputDir, 'deck.pptx');
    const pptxOk = existsSync(pptx) && statSync(pptx).size > 1024;
    steps.push({ name: `${path.relative(ctx.cwd, pptx)} exists`, success: pptxOk });
    if (!pptxOk) return { success: false, steps };

    const soffice = findSoffice();
    if (!soffice) {
      steps.push({
        name: 'libreoffice available',
        success: false,
        error: 'Install: brew install --cask libreoffice (Mac) or apt install libreoffice (Linux)',
      });
      return { success: false, steps };
    }
    steps.push({ name: 'libreoffice available', success: true });

    const qaDir = path.join(ctx.workspaceDir, 'qa');
    if (existsSync(qaDir)) {
      spawnSync(
        'rm',
        ['-f', ...readdirSync(qaDir).filter((f) => f.endsWith('.jpg') || f.endsWith('.pdf')).map((f) => path.join(qaDir, f))],
        { stdio: 'pipe' },
      );
    }
    const r2 = spawnSync(soffice, ['--headless', '--convert-to', 'pdf', pptx, '--outdir', qaDir], { stdio: 'pipe' });
    const pdfOk = r2.status === 0 && existsSync(path.join(qaDir, 'deck.pdf'));
    steps.push({ name: 'pptx → pdf', success: pdfOk });
    if (!pdfOk) return { success: false, steps };

    const r3 = spawnSync('pdftoppm', ['-jpeg', '-r', '100', path.join(qaDir, 'deck.pdf'), path.join(qaDir, 'v')], { stdio: 'pipe' });
    const jpegOk = r3.status === 0;
    steps.push({ name: 'pdf → jpegs', success: jpegOk });

    return { success: jpegOk, steps };
  },

  async assess(ctx: AdapterContext, opts: { fast: boolean }): Promise<AssessOutput> {
    const defects: Defect[] = [];
    const evidence: Evidence[] = [];
    const qaDir = path.join(ctx.workspaceDir, 'qa');
    const qaRel = path.relative(ctx.cwd, qaDir);

    if (!existsSync(qaDir)) {
      evidence.push(
        valueEvidence({
          id: 'deck.render.qa-dir',
          description: `${qaRel}/ directory exists`,
          pass: false,
          expected: 'directory exists',
          actual: 'missing',
        }),
      );
      defects.push({
        unit: qaRel,
        metric: 'render',
        severity: 'high',
        description: 'No rendered JPEGs found. Run `spear execute` first.',
        mechanical: true,
        evidenceId: 'deck.render.qa-dir',
      });
      return { defects, evidence };
    }

    const jpegs = readdirSync(qaDir).filter((f) => /^v-\d+\.jpg$/.test(f)).sort();
    evidence.push(
      valueEvidence({
        id: 'deck.render.slide-count',
        description: 'Rendered slide JPEGs',
        pass: jpegs.length > 0,
        expected: '> 0',
        actual: jpegs.length,
      }),
    );
    if (jpegs.length === 0) {
      defects.push({
        unit: qaRel,
        metric: 'render',
        severity: 'high',
        description: 'No v-NN.jpg files. Render pipeline produced no slides.',
        mechanical: true,
        evidenceId: 'deck.render.slide-count',
      });
      return { defects, evidence };
    }

    for (const f of jpegs) {
      const slideNum = parseInt(f.replace('v-', '').replace('.jpg', ''), 10);
      const abs = path.join(qaDir, f);
      const size = statSync(abs).size;
      const evId = `deck.slide.${slideNum}.render`;
      evidence.push(
        await fileEvidence({
          id: evId,
          kind: 'mechanical',
          description: `Slide ${slideNum} JPEG (${size} bytes)`,
          filePath: abs,
          cwd: ctx.cwd,
          pass: size >= 1024,
          expected: '>= 1024 bytes',
          actual: size,
        }),
      );
      if (size < 1024) {
        defects.push({
          unit: `Slide ${slideNum}`,
          metric: 'render',
          severity: 'high',
          description: `${f} is empty/corrupt (${size} bytes)`,
          mechanical: true,
          evidenceId: evId,
        });
      }
    }

    if (!opts.fast) {
      for (const f of jpegs) {
        const slideNum = parseInt(f.replace('v-', '').replace('.jpg', ''), 10);
        const abs = path.join(qaDir, f);
        const evId = `deck.slide.${slideNum}.rubric`;
        evidence.push(
          await fileEvidence({
            id: evId,
            kind: 'subjective',
            description: `Slide ${slideNum} — score against ASSESS.md`,
            filePath: abs,
            cwd: ctx.cwd,
            rubricRef: 'ASSESS.md',
          }),
        );
        defects.push({
          unit: `Slide ${slideNum}`,
          metric: 'rubric',
          severity: 'medium',
          description: `Score slide against ASSESS.md (read ${path.relative(ctx.cwd, abs)})`,
          mechanical: false,
          evidenceId: evId,
        });
      }
    }

    return { defects, evidence };
  },

  defaultGraderArtifacts(ctx: AdapterContext): string[] {
    const qaDir = path.join(ctx.workspaceDir, 'qa');
    if (!existsSync(qaDir)) return [];
    return readdirSync(qaDir)
      .filter((f) => /^v-\d+\.jpg$/.test(f))
      .sort()
      .map((f) => path.join(qaDir, f));
  },
};

function findSoffice(): string | null {
  const candidates = [
    '/Applications/LibreOffice.app/Contents/MacOS/soffice',
    '/usr/bin/soffice',
    '/usr/local/bin/soffice',
  ];
  for (const c of candidates) if (existsSync(c)) return c;
  try {
    const out = execSync('which soffice', { stdio: 'pipe' }).toString().trim();
    return out || null;
  } catch {
    return null;
  }
}
