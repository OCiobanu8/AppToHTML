/**
 * Shared types + zod schemas for structured CLI I/O.
 * Every command that supports --json returns one of these.
 */
import { z } from 'zod';

export const DefectSchema = z.object({
  unit: z.string(),                 // "Slide 7" or "Section 3" or "function foo"
  metric: z.string(),               // "F" (lettered failure mode) or "Pyramid principle"
  severity: z.enum(['low', 'medium', 'high']),
  description: z.string(),
  suggestedFix: z.string().optional(),
  mechanical: z.boolean(),          // true = CLI can auto-fix; false = needs LLM judgment
  evidenceId: z.string().optional(),// link to the Evidence row that triggered this defect
});
export type Defect = z.infer<typeof DefectSchema>;

/**
 * Evidence is the verifiable trace of an assess pass. Mechanical evidence has
 * a pass/fail with expected/actual values; subjective evidence points the LLM
 * at the artifact it must read (e.g., a JPEG to score, a draft to read).
 *
 * Principle: verify computed values, not just claims. An assess pass without
 * evidence is not an assess.
 */
export const EvidenceSchema = z.object({
  id: z.string(),                            // dotted: "deck.render.slide-count"
  kind: z.enum(['mechanical', 'subjective']),
  description: z.string(),                   // human-readable: "14 slides rendered"
  pass: z.boolean().optional(),              // mechanical only
  expected: z.unknown().optional(),
  actual: z.unknown().optional(),
  artifact: z.string().optional(),           // path (cwd-relative) to the file
  artifactHash: z.string().optional(),       // sha256 hex
  artifactSize: z.number().optional(),
  rubricRef: z.string().optional(),          // "ASSESS.md#F" or letter "F"
});
export type Evidence = z.infer<typeof EvidenceSchema>;

/**
 * Structured report block the LLM writes into RESOLVE.md after a round of
 * fixes. SPEAR parses it on the next loop call and persists it into state.
 *
 * Format on disk:
 *
 *   <spear-report>
 *   ITERATION: 3
 *   PHASE: resolve
 *   COMPLETED: fixed slide 7 RESPONDED wrap, slide 11 squish
 *   FILES_CHANGED: deck/build.js
 *   TESTS: N/A
 *   NEXT: re-run spear loop
 *   BLOCKERS: None
 *   PROGRESS: 8/10
 *   </spear-report>
 */
export const SpearReportSchema = z.object({
  iteration: z.number().optional(),
  phase: z.string().optional(),
  completed: z.string().optional(),
  filesChanged: z.array(z.string()).optional(),
  tests: z.string().optional(),
  next: z.string().optional(),
  blockers: z.string().optional(),           // "None" or text — empty/None means not blocked
  progress: z.string().optional(),           // free-form: "8/10" or "60%"
  extras: z.record(z.string()).optional(),   // adapter-specific (DEFECTS_FIXED, COVERAGE_AFTER, ...)
});
export type SpearReport = z.infer<typeof SpearReportSchema>;

export const AssessResultSchema = z.object({
  round: z.number(),
  totalUnits: z.number(),
  perUnitScores: z.record(z.number()),
  defects: z.array(DefectSchema),
  evidence: z.array(EvidenceSchema),
  converged: z.boolean(),
  timestamp: z.string(),
  stuck: z.boolean().optional(),             // defectCount unchanged ≥2 rounds
  stuckSince: z.number().optional(),         // round number when stuck started
});
export type AssessResult = z.infer<typeof AssessResultSchema>;

export const StatusSchema = z.object({
  phase: z.enum(['scope', 'plan', 'execute', 'assess', 'resolve', 'converged']),
  round: z.number(),
  maxRounds: z.number(),
  type: z.enum(['deck', 'blog', 'code', 'generic']),
  scopeValid: z.boolean(),
  planValid: z.boolean(),
  executeValid: z.boolean(),
  assessValid: z.boolean(),
  openDefects: z.number(),
  blocked: z.boolean().optional(),
  failed: z.boolean().optional(),
  stuck: z.boolean().optional(),
});
export type Status = z.infer<typeof StatusSchema>;
