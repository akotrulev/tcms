import type { IssueCompletion } from '../../api/types';
import { completionBases } from '../../lib/labels';
import { plural } from '../../lib/format';

/**
 * An epic's completion — the percentage, the meter, and **the basis beside it, without exception**.
 *
 * The caption is not decoration. D8: an epic weighted by hours and one counted by heads are
 * different measurements, and a number that changes meaning when somebody clears an estimate is
 * the number people learn to distrust. `epic.html` renders the basis as a chip beside the figure
 * and spends a whole note on it — *"the moment one does not, this switches to by count and says
 * so"*.
 *
 * The count is shown beside the percentage (D31: `12 of 17 stories done`), and the excluded spikes
 * are named when there are any — *"1 spike excluded from the percentage"* — because an epic whose
 * denominator is smaller than its child list is otherwise just wrong-looking.
 *
 * **This component computes nothing.** `percent`, `basis`, `done`, `total` and `excludedSpikes`
 * all arrive from the server. D8's "one number, one place" — this repo has shipped two
 * contradictory percentages on one screen, and a component that did its own arithmetic is how.
 * The only expression here is the meter's CSS width, which is the number being *drawn* rather than
 * a second computation of it.
 */
export function CompletionMeter({
  completion,
  childNoun = 'story',
  testId = 'Issue.Completion',
}: {
  completion: IssueCompletion;
  /** What the counted children are called on this screen — "story" on an epic. */
  childNoun?: string;
  testId?: string;
}) {
  const { percent, basis, done, total, excludedSpikes, estimatedHoursDone, estimatedHoursTotal } =
    completion;

  return (
    <div data-testid={testId}>
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <strong className="text-[13px] text-ink">Completion</strong>
        <span className="flex items-center gap-2">
          <span
            data-testid={`${testId}.Percent`}
            className="font-mono text-[15px] font-semibold text-ink"
          >
            {`${percent}%`}
          </span>
          {/*
            **Always rendered, and never derived.** The whole of D8's mitigation is that the number
            says which measurement it is, so this element has no conditional around it: a build
            that could omit it is a build that can show a percentage whose meaning is unknown.
          */}
          <span data-testid={`${testId}.Basis`} className="text-[11px] text-ink-4 italic">
            {completionBases[basis]}
          </span>
        </span>
      </div>

      <div aria-hidden className="flex h-[9px] overflow-hidden rounded-full bg-wash-2">
        <span
          className="block h-full bg-[color:var(--color-passed)]"
          style={{ width: `${percent}%` }}
        />
      </div>

      <div className="mt-2 flex flex-wrap gap-3.5 text-[11.5px] text-ink-4">
        <span data-testid={`${testId}.Count`}>{`${done} of ${plural(total, childNoun)} done`}</span>
        {estimatedHoursDone != null && estimatedHoursTotal != null && (
          <span className="font-mono">{`${estimatedHoursDone} of ${estimatedHoursTotal} h`}</span>
        )}
        {excludedSpikes > 0 && (
          // An epic whose denominator is smaller than its child list is otherwise just
          // wrong-looking: the reader counts seventeen rows and the meter counts sixteen.
          <span data-testid={`${testId}.Excluded`}>
            {`${plural(excludedSpikes, 'spike')} excluded from the percentage`}
          </span>
        )}
      </div>
    </div>
  );
}
