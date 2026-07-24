/**
 * Adapter dispatcher. Every artifact type implements two methods:
 *   - execute(ctx): run the build pipeline, return per-step results
 *   - assess(ctx, opts): score the artifact, return defects + evidence
 *
 * The CLI is artifact-agnostic; adapters know the build commands + checks.
 *
 * Evidence discipline: every assess pass MUST emit evidence.
 * Mechanical evidence has expected/actual/pass; subjective evidence points
 * the LLM at the artifact it must read. An assess without evidence is a bug.
 */
import path from 'path';
import { deckAdapter } from './deck.js';
import { blogAdapter } from './blog.js';
import { codeAdapter } from './code.js';
import { genericAdapter } from './generic.js';
import { projectDir } from '../state.js';
import type { Defect, Evidence } from '../types.js';

export interface ExecuteResult {
  success: boolean;
  steps: Array<{ name: string; success: boolean; error?: string }>;
}

export interface AssessOutput {
  defects: Defect[];
  evidence: Evidence[];
}

/**
 * Adapter context. The CLI builds this once per command and passes it to the
 * adapter. Adapters operate ON `workspaceDir` (where the artifact lives) and
 * write evidence/output paths cwd-relative for portability.
 *
 * For deck/blog/generic: workspaceDir = .spear/<slug>/workspace
 * For code:              workspaceDir = cwd (the surrounding repo)
 */
export interface AdapterContext {
  cwd: string;          // the user's actual cwd (relative-path base for evidence)
  slug: string;
  projectDir: string;   // .spear/<slug>/
  workspaceDir: string; // where the actual artifact lives
}

export interface Adapter {
  execute(ctx: AdapterContext): Promise<ExecuteResult>;
  assess(ctx: AdapterContext, opts: { fast: boolean }): Promise<AssessOutput>;
  /**
   * Default file list for the sub-agent grader (`spear assess --grader <cmd>`).
   * Returns absolute paths of artifacts to grade. The CLI passes these to the
   * generic grader prompt builder, which auto-decides inline-vs-Read-tool
   * based on file types and sizes.
   *
   * Returns [] (or null) if the project has no gradeable artifact yet.
   * Users can always override the defaults with `--grade-files <path,path,...>`.
   */
  defaultGraderArtifacts?(ctx: AdapterContext): string[];
}

const adapters: Record<string, Adapter> = {
  deck: deckAdapter,
  blog: blogAdapter,
  code: codeAdapter,
  generic: genericAdapter,
};

export function getAdapter(type: string): Adapter {
  const a = adapters[type];
  if (!a) throw new Error(`Unknown artifact type: ${type}`);
  return a;
}

/**
 * Build an AdapterContext for a given slug + type. Code projects scan the
 * surrounding repo (cwd); all other types scope to .spear/<slug>/workspace.
 */
export function buildContext(slug: string, type: string, cwd: string = process.cwd()): AdapterContext {
  const pdir = projectDir(slug, cwd);
  const workspaceDir = type === 'code' ? cwd : path.join(pdir, 'workspace');
  return { cwd, slug, projectDir: pdir, workspaceDir };
}
