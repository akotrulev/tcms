import type { WipBreach } from '../../api/types';
import { formatCount } from '../../lib/format';
import type { ServerColumn } from './cardMove';

/**
 * A column header: name, card count, and the WIP state.
 *
 * **The breach is reported, never enforced** (story B3) — the mockup states the rule on the screen:
 * *"The drop was accepted; the breach is the column's to show, not the card's to refuse."*
 *
 * Three states, and the middle one matters: no limit renders `—`, a limit under or at renders
 * `2 / 4`, and a limit exceeded renders `4 / 3 ⚠` with the warning styling. A column at exactly its
 * limit is **not** in breach; off-by-one here would put a permanent warning on any team that
 * actually works to its limit, and a warning that is always on is one nobody reads.
 *
 * **The off-by-one is not decided here, and that is the point.** `breach` is presence in
 * `BoardContents.breaches`, which the server counted over the whole project — so the middle state
 * and the third are one comparison made once, on the side that can see every card. This component
 * has no `>` in it.
 *
 * **The two states read their numerator from two different facts, and say which.** A breach carries
 * its own `count`, counted over the project and not shrinking under a filter (D-w), so it is
 * rendered as sent. The under-limit state has no such number on the wire — `breaches` lists only
 * the columns that are over — so its numerator is the cards actually drawn, and its title says so.
 * Substituting one for the other would be a figure whose meaning changed with the filter, which is
 * D8's named failure wearing a different hat.
 */
export function ColumnHeader({
  column,
  count,
  breach,
  testId,
}: {
  column: ServerColumn;
  /** Cards drawn in this column, across every expanded lane. */
  count: number;
  /** This column's entry in `BoardContents.breaches`, when it has one. */
  breach: WipBreach | undefined;
  testId: string;
}) {
  return (
    <div
      data-testid={testId}
      className="flex items-center gap-[7px] border-b border-line px-3 py-2.5 text-[12px] font-semibold text-ink"
    >
      {/* The name is its own element: the count sits in the same header, and a reader — or a test —
          asking for the column by name must not have to know that. */}
      <span>{column.name}</span>
      <span data-testid={`${testId}.Count`} className="font-mono text-[11px] font-normal text-ink-3">
        {formatCount(count)}
      </span>
      <span className="ml-auto">
        {column.wipLimit === null ? (
          <span
            data-testid={`${testId}.Wip`}
            title="No work-in-progress limit on this column."
            className="font-mono text-[10.5px] font-normal text-ink-4"
          >
            &mdash;
          </span>
        ) : breach ? (
          <span
            data-testid={`${testId}.Wip`}
            title="Work-in-progress limit exceeded, counted over the whole project."
            className="font-mono text-[10.5px] font-bold text-[color:var(--color-failed)]"
          >
            {`${breach.count} / ${breach.limit} `}
            {/* Never colour and a glyph alone: the sentence is what a screen reader gets, and the
                warning hue is the third statement of the same fact rather than the only one. */}
            <span aria-hidden>&#9888;</span>
            <span className="sr-only">over the limit</span>
          </span>
        ) : (
          <span
            data-testid={`${testId}.Wip`}
            title="Cards shown here, against this column's work-in-progress limit."
            className="font-mono text-[10.5px] font-normal text-ink-4"
          >
            {`${count} / ${column.wipLimit}`}
          </span>
        )}
      </span>
    </div>
  );
}
