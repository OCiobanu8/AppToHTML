/**
 * Code adapter — runs build/lint/typecheck/test commands defined in the
 * surrounding repo's package.json.
 *
 * For code projects, ctx.workspaceDir = ctx.cwd (the repo itself), not
 * .spear/<slug>/workspace. The adapter scans the actual codebase.
 *
 * Mechanical evidence: type-check passes, tests pass, lint clean, no `any`/console.log.
 * Subjective: contract docs, edge cases — deferred to LLM.
 */
import { spawnSync } from 'child_process';
import { existsSync, readFileSync, readdirSync } from 'fs';
import path from 'path';
import type { Adapter, AdapterContext, AssessOutput, ExecuteResult } from './index.js';
import type { Defect, Evidence } from '../types.js';
import { valueEvidence } from '../evidence.js';

export const codeAdapter: Adapter = {
  async execute(ctx: AdapterContext): Promise<ExecuteResult> {
    const steps: ExecuteResult['steps'] = [];
    const pkgPath = path.join(ctx.workspaceDir, 'package.json');
    if (existsSync(pkgPath)) {
      const pkg = JSON.parse(readFileSync(pkgPath, 'utf-8'));
      for (const script of ['typecheck', 'lint', 'test']) {
        if (pkg.scripts?.[script]) {
          const r = spawnSync('npm', ['run', '--silent', script], { cwd: ctx.workspaceDir, stdio: 'pipe' });
          steps.push({
            name: `npm run ${script}`,
            success: r.status === 0,
            error: r.status !== 0 ? r.stderr?.toString().slice(0, 500) : undefined,
          });
        }
      }
    }
    return { success: steps.every((s) => s.success), steps };
  },

  async assess(ctx: AdapterContext, opts: { fast: boolean }): Promise<AssessOutput> {
    const defects: Defect[] = [];
    const evidence: Evidence[] = [];

    const files = listSourceFiles(ctx.workspaceDir);
    let anyHits = 0;
    let logHits = 0;
    let todoHits = 0;

    for (const f of files) {
      const txt = readFileSync(f, 'utf-8');
      const rel = path.relative(ctx.cwd, f);
      if (/:\s*any\b/.test(txt)) {
        anyHits++;
        defects.push({
          unit: rel,
          metric: 'A (any-type)',
          severity: 'medium',
          description: '`any` type used — use a concrete type',
          mechanical: false,
          evidenceId: 'code.scan.any-type',
        });
      }
      if (/console\.log\(/.test(txt)) {
        logHits++;
        defects.push({
          unit: rel,
          metric: 'I (debug-log)',
          severity: 'low',
          description: '`console.log` left in code',
          mechanical: true,
          suggestedFix: 'remove or replace with proper logger',
          evidenceId: 'code.scan.console-log',
        });
      }
      if (/^\s*\/\/\s*(TODO|FIXME|XXX)/m.test(txt)) {
        todoHits++;
        defects.push({
          unit: rel,
          metric: 'leftover-todo',
          severity: 'low',
          description: 'TODO/FIXME comment present',
          mechanical: false,
          evidenceId: 'code.scan.todo-comment',
        });
      }
    }

    evidence.push(
      valueEvidence({
        id: 'code.scan.any-type',
        description: 'Source files containing `: any` annotations',
        pass: anyHits === 0,
        expected: 0,
        actual: anyHits,
      }),
      valueEvidence({
        id: 'code.scan.console-log',
        description: 'Source files containing console.log()',
        pass: logHits === 0,
        expected: 0,
        actual: logHits,
      }),
      valueEvidence({
        id: 'code.scan.todo-comment',
        description: 'Source files containing TODO/FIXME/XXX comments',
        pass: todoHits === 0,
        expected: 0,
        actual: todoHits,
      }),
      valueEvidence({
        id: 'code.scan.files-checked',
        description: 'Source files scanned',
        pass: true,
        expected: '> 0',
        actual: files.length,
      }),
    );

    if (!opts.fast) {
      defects.push({
        unit: 'all changed files',
        metric: 'rubric',
        severity: 'medium',
        description: 'Score against ASSESS.md (contracts, edge cases, race conditions, etc.)',
        mechanical: false,
        evidenceId: 'code.scan.files-checked',
      });
    }

    return { defects, evidence };
  },

  defaultGraderArtifacts(ctx: AdapterContext): string[] {
    return listSourceFiles(ctx.workspaceDir);
  },
};

function listSourceFiles(root: string, dir = root, out: string[] = []): string[] {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.name.startsWith('.') || e.name === 'node_modules' || e.name === 'dist') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) listSourceFiles(root, p, out);
    else if (/\.(ts|tsx|js|jsx|py|go|rs)$/.test(e.name)) out.push(p);
  }
  return out;
}
