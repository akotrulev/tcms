import { describe, expect, it } from 'vitest';
import type { CaseFlake } from '../api/types';
import { bandOf, describeFlake, flakeEvidence } from './flake';

function row(over: Partial<CaseFlake> = {}): CaseFlake {
  return {
    caseId: 'c1',
    executions: 20,
    transitions: 18,
    alternations: 4,
    cells: 2,
    flakeScore: 0.2222,
    lastAlternationAt: '2026-08-06T10:00:00Z',
    computedAt: '2026-08-07T02:00:00Z',
    quarantined: false,
    quarantinedAt: null,
    quarantinedBy: null,
    quarantineReason: null,
    ...over,
  };
}

describe('describeFlake — the three ways to have no number', () => {
  it('THE ONE THAT MATTERS: a null score is not a zero, and they do not collapse', () => {
    // The whole reason this module exists. `flakeScore: null` (a row below the evidence floor) and
    // `flakeScore: 0` (measured 38 times, never alternated) are OPPOSITE claims about a test, and
    // `score ?? 0` type-checks on both sides of the wire. If this assertion ever has to change,
    // read §121 before changing it.
    const insufficient = describeFlake(row({ flakeScore: null, executions: 3, transitions: 2 }));
    const stable = describeFlake(row({ flakeScore: 0, executions: 40, transitions: 38, alternations: 0 }));

    expect(insufficient.kind).toBe('INSUFFICIENT');
    expect(stable.kind).toBe('SCORED');
    expect(stable).toMatchObject({ score: 0, band: 'STABLE' });
  });

  it('distinguishes NO ROW from a row that could not be scored', () => {
    // A third state above the two, and the one a `flake ?? {}` would erase: nobody has looked at
    // this case at all, which is not the same as having looked and found too little.
    expect(describeFlake(null).kind).toBe('UNMEASURED');
    expect(describeFlake(undefined).kind).toBe('UNMEASURED');
    expect(describeFlake(row({ flakeScore: null })).kind).toBe('INSUFFICIENT');
  });

  it('carries the counts on the unscored branch, because they are what makes it legible', () => {
    // The API ships these precisely so a panel can say "3 results, too few" rather than render a
    // blank. A verdict that dropped them would leave the panel with nothing to say.
    expect(describeFlake(row({ flakeScore: null, executions: 3, transitions: 2 }))).toEqual({
      kind: 'INSUFFICIENT',
      executions: 3,
      transitions: 2,
    });
  });

  it('passes the score and every count through on the scored branch', () => {
    expect(describeFlake(row())).toEqual({
      kind: 'SCORED',
      score: 0.2222,
      band: 'NOTABLE',
      executions: 20,
      transitions: 18,
      alternations: 4,
      cells: 2,
    });
  });
});

describe('bandOf', () => {
  it('reserves STABLE for exactly zero', () => {
    // A test that alternated once is a test that alternated. Rounding a small score down to
    // "stable" is the same reassuring direction the `EXCLUDED.*` set-list would have taken the
    // quarantine flag one table over.
    expect(bandOf(0)).toBe('STABLE');
    expect(bandOf(0.01)).toBe('LOW');
  });

  it('bands the rest at the documented thresholds', () => {
    expect(bandOf(0.19)).toBe('LOW');
    expect(bandOf(0.2)).toBe('NOTABLE');
    expect(bandOf(0.49)).toBe('NOTABLE');
    expect(bandOf(0.5)).toBe('HIGH');
    expect(bandOf(1)).toBe('HIGH');
  });
});

describe('flakeEvidence', () => {
  it('denominates on TRANSITIONS and says so, never on the result count', () => {
    // §121's own finding: a case with six results across six cells has six executions and zero
    // transitions. A sentence quoting only "6 results" invites the reader to treat a score computed
    // from nothing as a measurement over six.
    const sentence = flakeEvidence(describeFlake(row()));

    expect(sentence).toContain('18 repeat runs');
    expect(sentence).toContain('4 switches');
    expect(sentence).toContain('2 configurations');
  });

  it('says nothing at all when there is no row', () => {
    expect(flakeEvidence(describeFlake(null))).toBeNull();
  });

  it('has a separate sentence for a case with no pass/fail results yet', () => {
    // Zero is not "too few" — there is nothing, and "0 results, 0 repeat runs" reads as a
    // computation that ran and found nothing rather than as a case nothing has run against.
    expect(flakeEvidence(describeFlake(row({ flakeScore: null, executions: 0, transitions: 0 }))))
      .toBe('No pass or fail results inside the scoring window yet.');
  });

  it('singularises, because "1 results" reads as a bug in the page', () => {
    const sentence = flakeEvidence(
      describeFlake(row({ flakeScore: null, executions: 1, transitions: 1 })),
    );

    expect(sentence).toContain('1 result,');
    expect(sentence).toContain('1 repeat run ');
  });
});
