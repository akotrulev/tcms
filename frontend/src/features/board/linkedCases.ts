import type { BadgeTone } from '../../components/kit';
import type { LinkedCasesSummary } from '../../api/types';

/**
 * How a card's linked-case roll-up reads — the QA card face's line (021 §2.7, T8, render note 22).
 *
 * <h2>Three zeroes that are not the same zero</h2>
 *
 * This file exists because `LinkedCasesSummary` can carry a `0` in three places and each means
 * something a reader would act on differently, while all three typecheck identically:
 *
 *   - **The summary itself is `null`** — *not served*. The key is always present on the wire and
 *     was null until T4 filled it, so a client cannot infer "none linked" from its absence. There
 *     is **no row at all** for this case: {@link describeLinkedCases} answers `null` and the face
 *     draws nothing. `?? 0` here would put `0/0 passing` on every card of any instance running a
 *     backend older than T4 — a screen full of a claim nobody made.
 *   - **`total === 0`** — *none linked*. A real answer, and the one a tester acts on by linking
 *     cases. It reads `no cases linked`, never `0/0 passing`.
 *   - **`passing === 0` with nothing executed** — *not run*. Render note 22, verbatim: `0/6 run` is
 *     not `0/6 passing`; reporting a card whose cases have never been executed as failing is the
 *     dishonest reading of the same zero. So the headline switches from *passing* to *run* the
 *     moment no verdict exists at all.
 *
 * <h2>Two badges, not one sentence</h2>
 *
 * The render draws `4/6 passing` and `1 blocked` as separate chips, and they are separate here for
 * the reason the render has them separate: a blocked case is not a failed one — nobody could run
 * it — so folding the count into the ratio would move cases out of the denominator's story into a
 * verdict they never got. `failing` gets the same treatment for the same reason, and it is what
 * turns the headline red rather than a count of its own: the ratio already says how many of the
 * six are not passing, and a `2 failed` chip beside `4/6 passing` states the same fact twice.
 *
 * The mockup's `TC-518 failed` is **absent**: `LinkedCasesSummary` carries five counts and no case
 * keys, so naming the failing case would be a sentence with nothing behind it (D-f's rule). The
 * key is one click away on the issue's own Linked test cases panel, which is where `Run linked
 * cases` leads.
 */
export type LinkedCasesLine = {
  /** `4/6 passing`, `0/6 run`, `no cases linked` — the leading chip, always present. */
  headline: string;
  tone: BadgeTone;
  /** Cases nobody could run. `0` draws no chip — a zero here says nothing a reader would act on. */
  blocked: number;
};

export function describeLinkedCases(
  summary: LinkedCasesSummary | null | undefined,
): LinkedCasesLine | null {
  // Not served (T4 absent, or an older instance). Distinct from `total === 0` below — see above.
  if (summary == null) return null;

  if (summary.total === 0) {
    return { headline: 'no cases linked', tone: 'untested', blocked: 0 };
  }

  /*
    A verdict exists when something passed or failed. `blocked` is not a verdict — a blocked case
    was never executed — so a card whose every linked case is blocked still reads `0/6 run`, which
    is the true statement, rather than `0/6 passing`, which is an accusation.
  */
  const judged = summary.passing + summary.failing;
  if (judged === 0) {
    const run = summary.total - summary.notRun - summary.blocked;
    return {
      headline: `${run}/${summary.total} run`,
      tone: 'untested',
      blocked: summary.blocked,
    };
  }

  return {
    headline: `${summary.passing}/${summary.total} passing`,
    // Red on any failure, green only when every linked case passed: "5/6 passing" with one failure
    // is not a green card, and a tone that said otherwise would be read before the numbers are.
    tone: summary.failing > 0 ? 'failed' : summary.passing === summary.total ? 'passed' : 'untested',
    blocked: summary.blocked,
  };
}
