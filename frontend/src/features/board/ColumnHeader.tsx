import { Plus, TriangleAlert } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import type { LinkedCasesSummary, WipBreach } from '../../api/types';
import { PermissionGate } from '../../components/PermissionGate';
import { ResultBar } from '../../components/kit';
import { Button } from '../../components/ui';
import { cn } from '../../lib/cn';
import { formatCount } from '../../lib/format';
import type { ServerColumn } from './cardMove';
import { describeLinkedCases } from './linkedCases';

const HEALTH_INK = {
  passed: 'text-[color:var(--color-passed)]',
  failed: 'text-[color:var(--color-failed)]',
  untested: 'text-ink-3',
} as const;

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
 *
 * **Row two is the column's linked-case health (023 D8).** The cards' summaries summed, so a case
 * linked to two cards here is counted twice and the title says so. No row at all when no card was
 * served a summary or the sum is empty: `describeLinkedCases` keeps the three zeroes apart and this
 * draws only the one that has something to say.
 *
 * **The door `+` is drawn only when `door` is set (D10).** A create enters the project's first
 * status, so a `+` on any other column would make a card that appears somewhere else.
 */
export function ColumnHeader({
  column,
  count,
  breach,
  health,
  joined,
  door,
  doneOpen,
  onToggleDone,
  testId,
}: {
  column: ServerColumn;
  /** Cards drawn in this column, across every expanded lane. */
  count: number;
  /** This column's entry in `BoardContents.breaches`, when it has one. */
  breach: WipBreach | undefined;
  /** The sum of the summaries of the cards drawn here, or null when none was served. */
  health: LinkedCasesSummary | null;
  /** True when one lane's cell sits directly under this header and the two read as one panel. */
  joined: boolean;
  /** Set on the one column that claims the workflow's first status. */
  door: { projectId: string } | undefined;
  doneOpen: boolean;
  onToggleDone: () => void;
  testId: string;
}) {
  const line = health === null ? null : describeLinkedCases(health);
  const showHealth = health !== null && health.total > 0 && line !== null;

  return (
    <div
      data-testid={testId}
      className={cn(
        'flex flex-col gap-1.5 border border-line bg-panel px-3 py-2.5 text-ink',
        joined ? 'rounded-t-xl border-b-0' : 'rounded-xl',
      )}
    >
      <div className="flex items-center gap-[7px]">
        {/* The name is its own element: the count sits in the same header, and a reader — or a test —
            asking for the column by name must not have to know that. */}
        <span className="text-[13px] font-semibold">{column.name}</span>
        <span data-testid={`${testId}.Count`} className="font-mono text-[11.5px] text-ink-3">
          {formatCount(count)}
        </span>
        {column.wipLimit === null ? (
          <span
            data-testid={`${testId}.Wip`}
            title="No work-in-progress limit on this column."
            className="inline-flex h-5 items-center rounded-full px-1.5 font-mono text-[11px] text-ink-4"
          >
            &mdash;
          </span>
        ) : breach ? (
          <span
            data-testid={`${testId}.Wip`}
            title="Work-in-progress limit exceeded, counted over the whole project."
            className={cn(
              'inline-flex h-5 items-center gap-1 rounded-full px-1.5 font-mono text-[11px] font-semibold',
              'bg-[color:var(--color-failed)]/15 text-[color:var(--color-failed)]',
            )}
          >
            {`${breach.count} / ${breach.limit}`}
            {/* Never colour and a glyph alone: the sentence is what a screen reader gets, and the
                warning hue is the third statement of the same fact rather than the only one. */}
            <TriangleAlert aria-hidden className="size-3" />
            <span className="sr-only">over the limit</span>
          </span>
        ) : (
          <span
            data-testid={`${testId}.Wip`}
            title="Cards shown here, against this column's work-in-progress limit."
            className="inline-flex h-5 items-center rounded-full bg-wash-2 px-1.5 font-mono text-[11px] text-ink-3"
          >
            {`${count} / ${column.wipLimit}`}
          </span>
        )}

        <span className="flex-1" />

        {column.done && (
          <button
            type="button"
            onClick={onToggleDone}
            aria-expanded={doneOpen}
            data-testid={`${testId}.DoneToggle`}
            className={cn(
              'cursor-pointer rounded-sm border-0 bg-transparent p-0 text-[11.5px] text-ink-3',
              'hover:text-ink focus-visible:outline-2 focus-visible:outline-[color:var(--color-given)]',
            )}
          >
            {doneOpen ? 'Hide' : 'Show'}
          </button>
        )}

        {door && (
          <PermissionGate projectId={door.projectId} permission="issue:create">
            {({ allowed, reason }) =>
              allowed ? (
                <Button variant="quiet" size="icon" className="size-6" asChild>
                  <RouterLink
                    to={`/projects/${door.projectId}/work/new`}
                    aria-label={`Create an issue in ${column.name}`}
                    data-testid={`${testId}.Create`}
                  >
                    <Plus aria-hidden className="size-4" />
                  </RouterLink>
                </Button>
              ) : (
                <Button
                  variant="quiet"
                  size="icon"
                  className="size-6"
                  disabled
                  title={reason}
                  aria-label={`Create an issue in ${column.name}`}
                  data-testid={`${testId}.Create`}
                >
                  <Plus aria-hidden className="size-4" />
                </Button>
              )
            }
          </PermissionGate>
        )}
      </div>

      {showHealth && (
        <div
          data-testid={`${testId}.Health`}
          title="Linked test cases across this column's cards, counted once per card link."
          className="flex items-center gap-2 text-[11.5px]"
        >
          <span
            className={cn(
              'font-mono text-[11px]',
              HEALTH_INK[line.tone === 'passed' || line.tone === 'failed' ? line.tone : 'untested'],
            )}
          >
            {line.headline}
          </span>
          {line.blocked > 0 && (
            <span className="text-[color:var(--color-blocked)]">{`${line.blocked} blocked`}</span>
          )}
          <ResultBar
            className="min-w-0 flex-1"
            passed={health.passing}
            failed={health.failing}
            blocked={health.blocked}
            untested={Math.max(0, health.total - health.passing - health.failing - health.blocked)}
          />
        </div>
      )}
    </div>
  );
}
