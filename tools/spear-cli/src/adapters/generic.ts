/**
 * Generic adapter — open-ended artifacts. The CLI can't run a build here;
 * Execute is a no-op that just verifies workspace/ has files. All assessment
 * is deferred to the LLM (read ASSESS.md, score the output).
 *
 * Workspace: ctx.workspaceDir (i.e., .spear/<slug>/workspace/).
 */
import { existsSync, readdirSync, statSync } from 'fs';
import path from 'path';
import type { Adapter, AdapterContext, AssessOutput, ExecuteResult } from './index.js';
import type { Defect, Evidence } from '../types.js';
import { fileEvidence, valueEvidence } from '../evidence.js';

export const genericAdapter: Adapter = {
  async execute(ctx: AdapterContext): Promise<ExecuteResult> {
    const exists = existsSync(ctx.workspaceDir) && readdirSync(ctx.workspaceDir).length > 0;
    return {
      success: exists,
      steps: [
        {
          name: `${path.relative(ctx.cwd, ctx.workspaceDir)} has content`,
          success: exists,
          error: exists ? undefined : `Add files to ${path.relative(ctx.cwd, ctx.workspaceDir)}/`,
        },
      ],
    };
  },

  async assess(ctx: AdapterContext, opts: { fast: boolean }): Promise<AssessOutput> {
    const defects: Defect[] = [];
    const evidence: Evidence[] = [];

    if (!existsSync(ctx.workspaceDir)) {
      evidence.push(
        valueEvidence({
          id: 'generic.workspace.exists',
          description: `${path.relative(ctx.cwd, ctx.workspaceDir)}/ exists`,
          pass: false,
          expected: 'exists',
          actual: 'missing',
        }),
      );
      return { defects, evidence };
    }

    const files: string[] = [];
    walk(ctx.workspaceDir, ctx.workspaceDir, files);
    evidence.push(
      valueEvidence({
        id: 'generic.workspace.file-count',
        description: 'Files under workspace/',
        pass: files.length > 0,
        expected: '> 0',
        actual: files.length,
      }),
    );

    for (const rel of files) {
      const evId = `generic.workspace.${rel.replace(/[^A-Za-z0-9]+/g, '-')}`;
      evidence.push(
        await fileEvidence({
          id: evId,
          kind: 'subjective',
          description: `Score ${rel} against ASSESS.md`,
          filePath: path.join(ctx.workspaceDir, rel),
          cwd: ctx.cwd,
          rubricRef: 'ASSESS.md',
        }),
      );
    }

    if (!opts.fast) {
      defects.push({
        unit: 'workspace',
        metric: 'rubric',
        severity: 'medium',
        description: 'Score against ASSESS.md (custom rubric)',
        mechanical: false,
        evidenceId: 'generic.workspace.file-count',
      });
    }

    return { defects, evidence };
  },

  defaultGraderArtifacts(ctx: AdapterContext): string[] {
    if (!existsSync(ctx.workspaceDir)) return [];
    const rels: string[] = [];
    walk(ctx.workspaceDir, ctx.workspaceDir, rels);
    return rels.map((rel) => path.join(ctx.workspaceDir, rel));
  },
};

function walk(root: string, dir: string, out: string[]): void {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.name.startsWith('.')) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      walk(root, p, out);
    } else if (statSync(p).isFile()) {
      out.push(path.relative(root, p));
    }
  }
}
