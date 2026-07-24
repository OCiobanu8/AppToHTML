/**
 * Blog adapter — markdown → mechanical checks.
 *
 * Workspace: ctx.workspaceDir/draft.md (i.e., .spear/<slug>/workspace/draft.md)
 *
 * Mechanical checks emit Evidence with expected/actual:
 *   - draft.md exists
 *   - Word count within bounds (default 500–3000)
 *   - Image cadence: at least one image per ~600 words
 *   - Header case consistency (title-case OR sentence-case throughout)
 *
 * Subjective checks (deferred): single thesis, lead anecdote, voice.
 */
import { existsSync, readFileSync } from 'fs';
import path from 'path';
import type { Adapter, AdapterContext, AssessOutput, ExecuteResult } from './index.js';
import type { Defect, Evidence } from '../types.js';
import { fileEvidence, valueEvidence } from '../evidence.js';

const DRAFT_NAME = 'draft.md';

export const blogAdapter: Adapter = {
  async execute(ctx: AdapterContext): Promise<ExecuteResult> {
    const steps: ExecuteResult['steps'] = [];
    const draft = path.join(ctx.workspaceDir, DRAFT_NAME);
    const exists = existsSync(draft);
    steps.push({
      name: `${path.relative(ctx.cwd, draft)} exists`,
      success: exists,
      error: exists ? undefined : 'Have the LLM write the draft',
    });
    return { success: exists, steps };
  },

  async assess(ctx: AdapterContext, opts: { fast: boolean }): Promise<AssessOutput> {
    const defects: Defect[] = [];
    const evidence: Evidence[] = [];
    const draft = path.join(ctx.workspaceDir, DRAFT_NAME);
    const draftRel = path.relative(ctx.cwd, draft);

    if (!existsSync(draft)) {
      evidence.push(
        valueEvidence({
          id: 'blog.draft.exists',
          description: `${draftRel} exists`,
          pass: false,
          expected: 'exists',
          actual: 'missing',
        }),
      );
      defects.push({
        unit: draftRel,
        metric: 'render',
        severity: 'high',
        description: `${draftRel} not found`,
        mechanical: true,
        evidenceId: 'blog.draft.exists',
      });
      return { defects, evidence };
    }

    const md = readFileSync(draft, 'utf-8');
    const words = md.split(/\s+/).filter(Boolean).length;
    const images = (md.match(/!\[/g) ?? []).length;

    evidence.push(
      valueEvidence({
        id: 'blog.draft.word-count',
        description: 'Draft word count within target band',
        pass: words >= 500 && words <= 3000,
        expected: '500..3000',
        actual: words,
      }),
    );

    if (words < 500) {
      defects.push({
        unit: draftRel,
        metric: 'word-count',
        severity: 'medium',
        description: `Draft has ${words} words; target 500-3000`,
        mechanical: true,
        evidenceId: 'blog.draft.word-count',
      });
    } else if (words > 3000) {
      defects.push({
        unit: draftRel,
        metric: 'word-count',
        severity: 'low',
        description: `Draft has ${words} words; consider tightening`,
        mechanical: true,
        evidenceId: 'blog.draft.word-count',
      });
    }

    const expectedImages = Math.floor(words / 600);
    evidence.push(
      valueEvidence({
        id: 'blog.draft.image-cadence',
        description: `≥1 image per 600 words (have ${images} for ${words} words)`,
        pass: images >= expectedImages,
        expected: `>= ${expectedImages}`,
        actual: images,
      }),
    );
    if (images < expectedImages) {
      defects.push({
        unit: draftRel,
        metric: 'K (wall-of-text)',
        severity: 'medium',
        description: `${images} images for ${words} words; expected ≥${expectedImages}`,
        mechanical: true,
        evidenceId: 'blog.draft.image-cadence',
      });
    }

    const h2s = [...md.matchAll(/^##\s+(.+)$/gm)].map((m) => m[1]);
    if (h2s.length > 1) {
      const titleCase = h2s.filter((h) => /^[A-Z][A-Za-z]+(\s+[A-Z][A-Za-z]+)+/.test(h)).length;
      const sentenceCase = h2s.length - titleCase;
      const mixed = titleCase > 0 && sentenceCase > 0 && Math.min(titleCase, sentenceCase) > 1;
      evidence.push(
        valueEvidence({
          id: 'blog.draft.header-case',
          description: 'H2 header casing is consistent',
          pass: !mixed,
          expected: 'all title-case OR all sentence-case',
          actual: { titleCase, sentenceCase, total: h2s.length },
        }),
      );
      if (mixed) {
        defects.push({
          unit: draftRel,
          metric: 'G (header-case-mixing)',
          severity: 'medium',
          description: `${titleCase} title-case vs ${sentenceCase} sentence-case H2s; pick one`,
          mechanical: true,
          evidenceId: 'blog.draft.header-case',
        });
      }
    }

    if (!opts.fast) {
      const evId = 'blog.draft.rubric';
      evidence.push(
        await fileEvidence({
          id: evId,
          kind: 'subjective',
          description: 'Score draft against ASSESS.md',
          filePath: draft,
          cwd: ctx.cwd,
          rubricRef: 'ASSESS.md',
        }),
      );
      defects.push({
        unit: draftRel,
        metric: 'rubric',
        severity: 'medium',
        description: `Score draft against ASSESS.md (read ${draftRel})`,
        mechanical: false,
        evidenceId: evId,
      });
    }

    return { defects, evidence };
  },

  defaultGraderArtifacts(ctx: AdapterContext): string[] {
    const draft = path.join(ctx.workspaceDir, DRAFT_NAME);
    return existsSync(draft) ? [draft] : [];
  },
};
