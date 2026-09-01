import type { BadgeTone } from '../../components/kit';
import type { ReleaseBlocker, ReleaseStatus, ReleaseVerdict } from '../../api/types';
import { releaseVerdictLabels } from '../../lib/labels';

/**
 * What 2.4 says on every screen that mentions it — the list, the detail header, the roadmap and
 * (in T6) the overview's Next release panel.
 *
 * One function rather than a `<ReleaseBadge>` component, because four call sites want the same two
 * strings in three different wrappers: the list puts them in a `Badge`, the roadmap in a bar's
 * `title`, and the overview in a tile's caption. A component would have forced the roadmap to
 * render a pill inside a 6px bar.
 *
 * ## `status` wins over `verdict`, and only in one direction (D-e)
 *
 * A `RELEASED` release reads **Released** whatever its verdict, because it shipped and the question
 * the verdict answers — *can we ship this?* — has been answered by the fact. Nothing goes the other
 * way: a `PLANNED` release never borrows a word from its status, so "Planned" is not a badge here.
 * `releases.html` has it in the Readiness column and that is this module's one deliberate divergence
 * from a mockup: two of that column's five values are a status a person set and three are a verdict
 * the server computed, so the same badge would mean "nobody started" on the 2.5 row and "somebody
 * did, and it failed" on the 2.4 row above it.
 */
export function releaseBadge(
  status: ReleaseStatus,
  verdict: ReleaseVerdict,
): { label: string; tone: BadgeTone } {
  if (status === 'RELEASED') return { label: 'Released', tone: 'passed' };
  return { label: releaseVerdictLabels[verdict], tone: VERDICT_TONES[verdict] };
}

/**
 * `NOT_TESTED` is `untested` and not `blocked`, which is the pairing this whole module is about: it
 * is the absence of proof, not a failure, and the two must not read as the same amount of trouble.
 * `NOT_READY` takes `failed` because something is genuinely outstanding.
 */
const VERDICT_TONES: Record<ReleaseVerdict, BadgeTone> = {
  READY: 'passed',
  NOT_READY: 'failed',
  NOT_TESTED: 'untested',
};

/**
 * The story behind `release.html`'s red banner — done, and its test says otherwise.
 *
 * **Derived rather than a `headline` string on the wire**, because prose assembled server-side
 * cannot link the issue key or the case key, and both are links there. The server sends the facts
 * (`status.kind` and `failedCaseKeys` per blocker) and the sentence is written where the links are.
 *
 * It lives beside {@link releaseBadge} rather than in `ReadinessPanels.tsx` for oxlint's
 * `react(only-export-components)` — a module exporting a component and a function is a warning, and
 * the lint gate is zero. `features/board/cardLink.ts` exists for the same reason.
 *
 * The **first** such blocker, not all of them: the banner names one story and one case, as the
 * mockup does, because a banner listing six is the blockers table with a red border. The table
 * below it is where the rest are.
 */
export function doneButFailing(blockers: readonly ReleaseBlocker[]): ReleaseBlocker | null {
  return blockers.find((b) => b.status.kind === 'DONE' && b.failedCaseKeys.length > 0) ?? null;
}

/**
 * What a blocker's Verdict cell says — the mockup's fourth column, derived from the two facts the
 * server sends about it rather than from a fifth field.
 *
 * `failedCaseKeys` first, because a story whose case failed is the one this module exists to
 * surface and it outranks whatever its status says. Then the status kind, in the mockup's own
 * words: a `DONE` story with no failures is not a blocker at all and cannot reach here, so the
 * three remaining readings are *in flight*, *not started*, and the failure.
 */
export function blockerVerdict(blocker: ReleaseBlocker): { text: string; failing: boolean } {
  if (blocker.failedCaseKeys.length > 0) {
    return { text: `${blocker.failedCaseKeys.join(', ')} failed`, failing: true };
  }
  return {
    text: blocker.status.kind === 'IN_PROGRESS' ? 'in flight' : 'not started',
    failing: false,
  };
}
