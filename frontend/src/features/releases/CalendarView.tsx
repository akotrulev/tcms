import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useMemo } from 'react';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { useIssues, useReleases } from '../../api/queries';
import type { IssueSummary, ReleaseSummary } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { FilterStrip } from '../../components/kit';
import { WorkStripActions } from '../tracker/workStrip';
import { Button } from '../../components/ui';
import { cn } from '../../lib/cn';
import { useIssueFilterChips, useWorkFilter } from '../board/useWorkFilter';

/**
 * `work-calendar.html` — one month, due dates and release targets.
 *
 * <h2>Two of the mockup's five event kinds, and the footer names the other three (D-n)</h2>
 *
 * The mockup draws due dates, overdue items, release targets, absences and a timesheet period end.
 * This module can serve the first three:
 *
 *   - **issue due dates**, from `GET /issues` with the shared work filter;
 *   - **release target dates**, from `GET /projects/{id}/releases`.
 *
 * A **test run** is deliberately not drawn: a run has three candidate dates — scheduled, started,
 * finished — and picking one blind is a decision this module has no reason to take. Absences are
 * M7's `absence` table and timesheet period ends are M6's `timesheet_period`; neither exists.
 *
 * The footer names all three and the module each arrives with, exactly as `WorkTablePage`'s own
 * `VIEWS` entries do for a view that is not built — because *"coming soon" names nothing a reader can
 * plan around*.
 *
 * <h2>The month is in the URL and is not a filter</h2>
 *
 * `?month=YYYY-MM`, and it is in `useWorkFilter`'s `CARRIED` list beside `view` and `sort`: it is
 * *which month you are looking at*, so clearing the filters must not throw the reader back to today.
 *
 * <h2>The grid draws every issue with a due date in the month, not a windowed query</h2>
 *
 * `GET /issues` has no date-range parameter, so the month is applied in the client over the list the
 * filter already produced. Said out loud because it is a real limit rather than a choice: a project
 * with tens of thousands of issues fetches all of the filtered ones to draw thirty days. It is the
 * same list the Table view holds, under the same cache key, so switching between them costs no
 * second read — which is what makes it acceptable today and what would have to change first.
 */

const TEST_ID = 'Calendar';

export function CalendarView({ projectId }: { projectId: string }) {
  const [params] = useSearchParams();
  const { filters, setParam, dropOne, clearFilters } = useWorkFilter();
  const applied = useIssueFilterChips(projectId, filters.issue, { setParam, dropOne });

  const month = monthOf(params.get('month'));
  const issues = useIssues(projectId, filters.issue, 'dueDate');
  const releases = useReleases(projectId);

  const days = useMemo(() => gridOf(month), [month]);

  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <span
          data-testid={`${TEST_ID}.Month`}
          className="text-[13px] font-semibold tracking-[-.01em] text-ink"
        >
          {MONTH_YEAR.format(new Date(`${month}-01T00:00:00Z`))}
        </span>
        <span className="flex items-center gap-1">
          <Button
            size="sm"
            aria-label="Previous month"
            data-testid={`${TEST_ID}.Previous`}
            onClick={() => setParam((next) => next.set('month', shift(month, -1)))}
          >
            <ChevronLeft className="size-4" />
          </Button>
          <Button
            size="sm"
            aria-label="Next month"
            data-testid={`${TEST_ID}.Next`}
            onClick={() => setParam((next) => next.set('month', shift(month, 1)))}
          >
            <ChevronRight className="size-4" />
          </Button>
          <Button
            size="sm"
            data-testid={`${TEST_ID}.Today`}
            onClick={() => setParam((next) => next.delete('month'))}
          >
            Today
          </Button>
        </span>
      </div>

      {/*
        Above the region, like every other filter strip in the product: a failed read must not
        unmount the controls a reader would use to change the query or retry.
      */}
      <WorkStripActions>
        <FilterStrip
          data-testid={`${TEST_ID}.Filters`}
          applied={applied}
          onClear={applied.length > 0 ? clearFilters : undefined}
        />
      </WorkStripActions>

      <section className="overflow-hidden rounded-lg border border-line bg-panel">
        <DataRegion
          testId={`${TEST_ID}.Content`}
          state={{ ...issues, refetch: () => void issues.refetch() }}
        >
          {(rows) => (
            <>
              <div
                data-testid={`${TEST_ID}.Grid`}
                className="grid grid-cols-7 border-l border-t border-line"
              >
                {DOW.map((day) => (
                  <div
                    key={day}
                    className="border-r border-b border-line bg-paper px-2 py-1.5 font-mono text-[11px] font-medium tracking-[.07em] uppercase text-ink-3"
                  >
                    {day}
                  </div>
                ))}

                {days.map((day) => (
                  <Day
                    key={day.date}
                    date={day.date}
                    inMonth={day.inMonth}
                    projectId={projectId}
                    issues={rows}
                    releases={releases.data ?? []}
                  />
                ))}
              </div>

              <p className="m-0 border-t border-line bg-field/60 px-[22px] py-2.5 text-[12px] text-ink-4">
                Due dates and release targets are drawn. <strong>Test runs are not</strong> — a run
                has three candidate dates and this module does not choose between them. Absences
                arrive with the absence module (M7) and timesheet period ends with time tracking
                (M6).
              </p>
            </>
          )}
        </DataRegion>
      </section>
    </>
  );
}

const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const;

const MONTH_YEAR = new Intl.DateTimeFormat('en-GB', {
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});

function Day({
  date,
  inMonth,
  projectId,
  issues,
  releases,
}: {
  date: string;
  inMonth: boolean;
  projectId: string;
  issues: readonly IssueSummary[];
  releases: readonly ReleaseSummary[];
}) {
  const due = issues.filter((issue) => issue.dueDate === date);
  const targets = releases.filter((release) => release.targetDate === date);

  return (
    <div
      data-testid={`${TEST_ID}.Day.${date}`}
      className={cn(
        'min-h-[86px] border-r border-b border-line p-1.5',
        inMonth ? 'bg-panel' : 'bg-wash-1',
      )}
    >
      <div className={cn('mb-1 font-mono text-[11px]', inMonth ? 'text-ink-3' : 'text-ink-4')}>
        {Number(date.slice(8, 10))}
      </div>

      <div className="flex flex-col gap-1">
        {due.map((issue) => (
          <RouterLink
            key={issue.key}
            to={`/issues/${encodeURIComponent(issue.key)}`}
            data-testid={`${TEST_ID}.Due.${issue.key}`}
            title={issue.title}
            /*
              `overdue` is the server's flag and not a comparison here: a client that recomputed it
              from `dueDate < today` would disagree with every other screen the moment the two
              disagreed about a timezone.
            */
            style={{
              borderLeftColor: issue.overdue ? 'var(--color-failed)' : 'var(--color-steel)',
            }}
            className="block truncate rounded-sm border-l-2 bg-wash-2 px-1.5 py-[3px] text-[11px] text-ink-2 no-underline hover:text-ink"
          >
            {`${issue.key} ${issue.overdue ? 'overdue' : 'due'}`}
          </RouterLink>
        ))}

        {targets.map((release) => (
          <RouterLink
            key={release.id}
            to={`/projects/${projectId}/releases/${release.id}`}
            data-testid={`${TEST_ID}.Target.${release.version}`}
            style={{ borderLeftColor: 'var(--color-given)' }}
            className="block truncate rounded-sm border-l-2 bg-wash-2 px-1.5 py-[3px] text-[11px] font-medium text-ink-2 no-underline hover:text-ink"
          >
            {`${release.version} target`}
          </RouterLink>
        ))}
      </div>
    </div>
  );
}

/** `YYYY-MM`, defaulting to the current month. A malformed parameter is a link somebody edited. */
function monthOf(value: string | null): string {
  if (value !== null && /^\d{4}-(0[1-9]|1[0-2])$/.test(value)) return value;
  return new Date().toISOString().slice(0, 7);
}

function shift(month: string, by: number): string {
  const at = new Date(`${month}-01T00:00:00Z`);
  return new Date(Date.UTC(at.getUTCFullYear(), at.getUTCMonth() + by, 1)).toISOString().slice(0, 7);
}

/**
 * Six weeks of Monday-first cells covering the month, with the leading and trailing days of the
 * neighbouring months drawn dimmed — the mockup's `.day.out`.
 *
 * A fixed 42 cells rather than a variable count: a grid that is five rows one month and six the next
 * moves everything below it, which is the layout shift the responsiveness budget forbids.
 */
function gridOf(month: string): { date: string; inMonth: boolean }[] {
  const first = new Date(`${month}-01T00:00:00Z`);
  // `getUTCDay()` is Sunday-0; the grid is Monday-first, so Sunday leads six days rather than none.
  const lead = (first.getUTCDay() + 6) % 7;

  return Array.from({ length: 42 }, (_unused, index) => {
    const at = new Date(
      Date.UTC(first.getUTCFullYear(), first.getUTCMonth(), 1 - lead + index),
    );
    return {
      date: at.toISOString().slice(0, 10),
      inMonth: at.getUTCMonth() === first.getUTCMonth(),
    };
  });
}
