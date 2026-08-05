/**
 * `impactSummary` — the sentence an author decides from before changing every case at once.
 *
 * ## What this file exists to catch
 *
 * **The two counts being added, or the suite count rendered as cases.** They measure different
 * things: `usageCount` is cases attaching the entry directly, `suiteUsageCount` is suites, each of
 * which propagates to every case beneath it. Their sum is a number about nothing, and the case
 * reach of a suite attachment is not computable on this side at all.
 *
 * That failure is invisible in every other kind of test. The dialog still renders, the numbers
 * still look plausible, and the only symptom is an author being told an edit touches 4 cases when
 * it touches 400 — which they discover afterwards, if ever. So the assertions here are on the exact
 * string, and the fixtures deliberately use counts whose sum (7) is a number that must never
 * appear.
 */
import { describe, expect, it } from 'vitest';
import type { Condition } from '../api/types';
import { impactSummary } from './conditions';

const condition = (usageCount: number, suiteUsageCount: number): Condition => ({
  id: 'c1',
  title: 'Log in as standard user',
  type: 'PRE',
  body: ['Open the application'],
  usageCount,
  suiteUsageCount,
  lockVersion: 0,
});

describe('impactSummary', () => {
  it('names the case count on its own when nothing else attaches it', () => {
    expect(impactSummary(condition(4, 0))).toBe('Used by 4 cases');
  });

  it('never adds the two counts, and never calls suites cases', () => {
    // 4 + 3 = 7, and 7 must not appear anywhere: it is the blend that would be a guess presented
    // as a measurement, and it understates nothing while overstating the direct reach.
    const summary = impactSummary(condition(4, 3));

    expect(summary).not.toContain('7');
    expect(summary).toContain('Used by 4 cases');
    expect(summary).toContain('3 suites');
  });

  it('says what a suite attachment MEANS rather than leaving the reader to guess', () => {
    // "attached to 3 suites" alone reads as a smaller impact than "used by 4 cases", when it is
    // very likely the larger one. The inheritance is the whole point of stating it.
    expect(impactSummary(condition(4, 3))).toContain('every case beneath inherits them');
  });

  it('singularizes both halves', () => {
    expect(impactSummary(condition(1, 1))).toBe(
      'Used by 1 case, attached to 1 suite — every case beneath inherits it',
    );
  });

  it('reports zero rather than omitting the sentence — a new entry is used by nothing', () => {
    // The state of every entry the moment it is created, so it is the common case rather than an
    // edge. An empty string here would render the dialog's warning as a dangling "Saving changes
    // X everywhere it is used.".
    expect(impactSummary(condition(0, 0))).toBe('Used by 0 cases');
  });
});
