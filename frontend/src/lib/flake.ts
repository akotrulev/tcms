/**
 * How a case's flake evidence becomes something a person can act on (§121 phase 4, plan §6l).
 *
 * ## The whole reason this is a module and not three lines in the panel
 *
 * **There are three ways for a case to have no flake number and they are different claims.**
 *
 * | Wire state | Means | Renders as |
 * |---|---|---|
 * | `flake === null` | no `case_flake` row — the recompute has never reached this case | *Not assessed yet* |
 * | `flakeScore === null` | a row exists, below the evidence floor | *Not enough evidence — 3 results* |
 * | `flakeScore === 0` | measured over 38 transitions and never alternated | *Stable* |
 *
 * The middle and the last are **opposite** — one says nobody can tell, the other says we looked
 * hard and it is fine — and `score ?? 0` collapses them while type-checking on both sides of the
 * wire. The API ships `executions`, `transitions`, `alternations` and `cells` for exactly this
 * reason, and a panel that renders a bare `—` for the middle case throws away the counts that
 * distinguish it. §95's `usage_count` and §100's exit criteria are the same defect; this is the
 * place it costs most, because the number it would invent is the one somebody uses to decide
 * whether to pull a test out of the gate.
 *
 * `formatRatio` is deliberately **not** used for the null branch: it answers an em dash, which is
 * the unexplained blank this module exists to avoid.
 */
import type { CaseFlake } from '../api/types';
// Moved to `format.ts` in §194, when the case editor became its second consumer. Imported rather
// than re-declared: a private copy here and a public one there is exactly the drift this project's
// shared display layer exists to prevent, and the sibilant rule below is the evidence that a
// re-implementation gets it wrong.
import { plural } from './format';

/**
 * How flaky, banded — for a chip's colour and word, never for a decision.
 *
 * `STABLE` is a **measurement** and not the absence of one; see `UNMEASURED`/`INSUFFICIENT`, which
 * are states of the evidence rather than bands of the score.
 */
export type FlakeBand = 'STABLE' | 'LOW' | 'NOTABLE' | 'HIGH';

export type FlakeVerdict =
  /** No row at all. Nobody has looked at this test. */
  | { kind: 'UNMEASURED' }
  /** A row, and not enough of it. The counts are carried so the panel can say how much there is. */
  | { kind: 'INSUFFICIENT'; executions: number; transitions: number }
  /** A real score. */
  | { kind: 'SCORED'; score: number; band: FlakeBand; executions: number; transitions: number; alternations: number; cells: number };

/**
 * The band thresholds.
 *
 * Bands, not a raw percentage alone, because a bare `22.2%` invites arithmetic nobody should do:
 * the denominator is *transitions inside one cell*, not runs, so the number is not comparable
 * across two cases with different execution histories. The word is what a reader should act on and
 * the percentage is the supporting detail — which is why `describeFlake` returns both and the panel
 * renders the word first.
 *
 * `STABLE` is exactly zero rather than a small band around it. A test that alternated **once** is a
 * test that alternated; calling that stable would be the same rounding-toward-reassurance the
 * `EXCLUDED.*` set-list would have done to the quarantine flag one table over.
 */
export function bandOf(score: number): FlakeBand {
  if (score === 0) return 'STABLE';
  if (score < 0.2) return 'LOW';
  if (score < 0.5) return 'NOTABLE';
  return 'HIGH';
}

export const flakeBandLabels = {
  STABLE: 'Stable',
  LOW: 'Slightly flaky',
  NOTABLE: 'Flaky',
  HIGH: 'Very flaky',
} satisfies Record<FlakeBand, string>;

/**
 * Reads the wire shape into the three-state verdict above.
 *
 * `undefined` is folded into `UNMEASURED` alongside `null`: the contract marks `flake` required, so
 * an absent key means an older server rather than a scored case, and guessing a score for it would
 * be the one direction this module exists to refuse.
 */
export function describeFlake(flake: CaseFlake | null | undefined): FlakeVerdict {
  if (!flake) return { kind: 'UNMEASURED' };

  // Explicitly `== null`, never falsy: `flakeScore: 0` is a real and meaningful score, and `!score`
  // would route the most reassuring measurement in the product into "not enough evidence".
  if (flake.flakeScore == null) {
    return {
      kind: 'INSUFFICIENT',
      executions: flake.executions,
      transitions: flake.transitions,
    };
  }

  return {
    kind: 'SCORED',
    score: flake.flakeScore,
    band: bandOf(flake.flakeScore),
    executions: flake.executions,
    transitions: flake.transitions,
    alternations: flake.alternations,
    cells: flake.cells,
  };
}

/**
 * The sentence under the headline — what the score was drawn from.
 *
 * Always states the **denominator**, because §121's own finding is that `transitions` and not
 * `executions` is what the score divides by: a case with six results across six cells has six
 * executions and zero transitions, and a reader shown only "6 results" would take a score computed
 * from nothing as a measurement over six.
 */
export function flakeEvidence(verdict: FlakeVerdict): string | null {
  switch (verdict.kind) {
    case 'UNMEASURED':
      return null;
    case 'INSUFFICIENT':
      return verdict.executions === 0
        ? 'No pass or fail results inside the scoring window yet.'
        : `${plural(verdict.executions, 'result')}, ${plural(verdict.transitions, 'repeat run')} under identical conditions — too few to score.`;
    case 'SCORED':
      return `${plural(verdict.alternations, 'switch')} between pass and fail across ${plural(verdict.transitions, 'repeat run')}, in ${plural(verdict.cells, 'configuration')}.`;
  }
}

