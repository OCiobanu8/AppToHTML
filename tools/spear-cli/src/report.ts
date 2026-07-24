/**
 * Parse <spear-report>...</spear-report> and the <spear-complete/> stop signal
 * out of RESOLVE.md.
 *
 * The report block is what the LLM writes after a round of fixes. SPEAR reads
 * it on the next loop call to update state.lastAssess and surface BLOCKERS to
 * the runner. The field set is strict so the parser stays deterministic.
 *
 * Required fields are NOT enforced (the LLM may omit some). Adapter-specific
 * extras (DEFECTS_FIXED, COVERAGE_AFTER, ...) are captured into `extras`.
 */
import type { SpearReport } from './types.js';

const REPORT_RE = /<spear-report>([\s\S]*?)<\/spear-report>/i;
// Tag must be alone on its line (only whitespace before/after).
const COMPLETE_RE = /(?:^|\n)\s*<spear-complete\s*\/?>\s*(?:\r?\n|$)/i;
// Fenced code blocks render examples that mention the tags. Strip them
// before parsing so prose/examples don't false-positive as real signals.
function stripFenced(md: string): string {
  return md.replace(/```[\s\S]*?```/g, '');
}

const KNOWN_FIELDS: Record<string, keyof SpearReport> = {
  ITERATION: 'iteration',
  PHASE: 'phase',
  COMPLETED: 'completed',
  FILES_CHANGED: 'filesChanged',
  TESTS: 'tests',
  NEXT: 'next',
  BLOCKERS: 'blockers',
  PROGRESS: 'progress',
};

export function parseReport(md: string): SpearReport | null {
  const m = stripFenced(md).match(REPORT_RE);
  if (!m) return null;
  const body = m[1];
  const report: SpearReport = {};
  const extras: Record<string, string> = {};
  for (const rawLine of body.split('\n')) {
    const line = rawLine.trim();
    if (!line) continue;
    const fm = line.match(/^([A-Z][A-Z0-9_]*)\s*:\s*(.+)$/);
    if (!fm) continue;
    const key = fm[1];
    const value = fm[2].trim();
    if (key in KNOWN_FIELDS) {
      const target = KNOWN_FIELDS[key];
      if (target === 'iteration') {
        const n = parseInt(value, 10);
        if (!Number.isNaN(n)) report.iteration = n;
      } else if (target === 'filesChanged') {
        report.filesChanged = value
          .split(/[,\n]/)
          .map((s) => s.trim())
          .filter(Boolean);
      } else {
        // string field
        (report as Record<string, unknown>)[target] = value;
      }
    } else {
      extras[key] = value;
    }
  }
  if (Object.keys(extras).length > 0) report.extras = extras;
  return report;
}

export function hasCompleteSignal(md: string): boolean {
  return COMPLETE_RE.test(stripFenced(md));
}

/**
 * "None" / empty / absent BLOCKERS = not blocked. Anything else = blocked.
 */
export function isBlocked(report: SpearReport | null): boolean {
  if (!report?.blockers) return false;
  const trimmed = report.blockers.trim();
  if (!trimmed) return false;
  return !/^(none|n\/?a|—|-)$/i.test(trimmed);
}
