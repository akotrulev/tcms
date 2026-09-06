import { useBoardCoverage } from '../../api/queries';
import type { BoardSummary } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Badge, Banner } from '../../components/kit';
import { cn } from '../../lib/cn';
import { issueStatusKindTones } from '../../lib/labels';
import { plural } from '../../lib/format';

const TEST_ID = 'BoardSettings.Coverage';

/**
 * `15-board-coverage.html` — **which boards show each status**, and the warning row when none does.
 *
 * ## It asks a different question than the table it replaced (021 R7 / D-e)
 *
 * The old table asked *"is every status claimed on this board?"* — a per-board checklist that had to
 * come back all-green or the API refused the board with a 422, which is what forced every board to
 * show the whole lifecycle. This one asks *"which boards show each status?"*: one row per status,
 * one column per board, a cell naming the **column** that claims it, and a warning row for a status
 * **no** board claims.
 *
 * That warning is the whole of what the old rule protected — a card nobody can see — reported at the
 * one place boards are configured instead of refused where a board is saved. §2.4's seeded three
 * cover every status, so on a fresh project the warning is empty and this is a picture of the three
 * hand-offs.
 *
 * ## It reads the server, not the drafts, and that is a change from the old table
 *
 * The old one computed from the unsaved drafts so a reader watched it react to an unclaimed status
 * in the same beat. This one cannot: the question is over **every board at once**, and the drafts on
 * this screen are one board's — a matrix half from a draft and half from the server would report a
 * status as uncovered while the board that covers it sits unsaved in the next panel. So it is
 * `GET …/boards/coverage`, invalidated by `keys.boards(projectId)` — which every board write on this
 * screen already sweeps — and the reader sees it move when they save rather than as they type.
 *
 * The **shared** statuses are the interesting rows and are marked as such: a status two boards claim
 * is a hand-off (§2.4), and `Blocked` on all three is everyone's problem. Marked with a word, never
 * a colour alone.
 */
export function CoverageMatrix({
  projectId,
  boards,
}: {
  projectId: string;
  /** The picker's list, in position order — the matrix's columns, left to right. */
  boards: readonly BoardSummary[];
}) {
  const coverage = useBoardCoverage(projectId);

  return (
    <section
      className="rounded-[10px] border border-line bg-panel"
      data-testid={TEST_ID}
      aria-label="Coverage — which boards show each status"
    >
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">
          Coverage — which boards show each status
        </h2>
      </div>

      <DataRegion
        testId={TEST_ID}
        state={{ ...coverage, refetch: () => void coverage.refetch() }}
      >
        {(matrix) => (
          <>
            {/*
              A WARNING, not a refusal (D-e). It names the consequence in the reader's terms — the
              card is still there, still counts, and is still reachable from the Table and from
              search; what it is not is on a board — because "uncovered" on its own reads as data
              loss, which it is not.
            */}
            {matrix.uncovered.length > 0 && (
              <div className="px-3.5 pt-3">
                <Banner tone="warn" data-testid={`${TEST_ID}.Uncovered`}>
                  <span>
                    <strong>
                      {`${plural(matrix.uncovered.length, 'status')} on no board: `}
                      {matrix.uncovered.map((status) => status.name).join(', ')}
                      .
                    </strong>
                    {' A card in one of these is invisible on every board in this project. It still '}
                    exists, still counts in the epic roll-up and is still reachable from the Table
                    view and from search &mdash; but nobody will see it while working a board. Add it
                    to a board, or delete the status if nothing is in it.
                  </span>
                </Banner>
              </div>
            )}

            <div className="overflow-x-auto p-3">
              <table className="w-full min-w-[620px] border-collapse text-[12.5px]">
                <thead>
                  <tr>
                    <th className="border-b border-line px-2 py-1.5 text-left font-medium text-ink-3">
                      Status
                    </th>
                    {boards.map((board) => (
                      <th
                        key={board.id}
                        className="border-b border-line px-2 py-1.5 text-left font-medium text-ink-3"
                      >
                        {board.name}
                      </th>
                    ))}
                    <th className="border-b border-line px-2 py-1.5 text-left font-medium text-ink-3">
                      Covered by
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {matrix.rows.map((row) => {
                    const shared = row.claims.length > 1;
                    return (
                      <tr
                        key={row.status.id}
                        data-testid={`${TEST_ID}.Row.${row.status.name}`}
                        className={cn(
                          row.claims.length === 0
                            // The row is marked as well as the banner above it, and never by the
                            // tint alone — the `on no board` badge in the last cell is the word.
                            && 'bg-[color-mix(in_srgb,var(--color-warning)_12%,transparent)]',
                        )}
                      >
                        <td className="border-b border-line px-2 py-1.5">
                          <Badge tone={issueStatusKindTones[row.status.kind]}>{row.status.name}</Badge>
                        </td>
                        {boards.map((board) => {
                          // A cell names the COLUMN that claims the status, not a tick: a board's
                          // column may be named something other than the status (the Dev board's
                          // `In progress` claims `Rework` too), and that is exactly the fact a
                          // person reading this table is trying to find.
                          const claim = row.claims.find((entry) => entry.boardId === board.id);
                          return (
                            <td
                              key={board.id}
                              className="border-b border-line px-2 py-1.5 text-ink-2"
                              data-testid={`${TEST_ID}.Cell.${board.name}.${row.status.name}`}
                            >
                              {claim ? claim.columnName : <span className="text-ink-4">&mdash;</span>}
                            </td>
                          );
                        })}
                        <td className="border-b border-line px-2 py-1.5">
                          {row.claims.length === 0 ? (
                            <Badge tone="blocked" data-testid={`${TEST_ID}.Row.${row.status.name}.None`}>
                              on no board
                            </Badge>
                          ) : shared ? (
                            <Badge tone="steel" data-testid={`${TEST_ID}.Row.${row.status.name}.Shared`}>
                              {`shared — ${row.claims.map((entry) => entry.boardName).join(' · ')}`}
                            </Badge>
                          ) : (
                            <span className="text-[11.5px] text-ink-4">
                              {`${row.claims[0].boardName} only`}
                            </span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="border-t border-line px-3.5 py-2.5 text-[11.5px] leading-[1.55] text-ink-4">
              {matrix.uncovered.length === 0 ? (
                <span className="text-[color:var(--color-success)]" data-testid={`${TEST_ID}.Holds`}>
                  ✓ Every status is on at least one board.
                </span>
              ) : null}
              <span className={cn(matrix.uncovered.length === 0 && 'ml-1.5')}>
                A cell names the <em>column</em> that claims the status. A status may be claimed at
                most once per board, and a column may claim several &mdash; which is how a board stays
                at five columns over eleven statuses.
              </span>
            </div>
          </>
        )}
      </DataRegion>
    </section>
  );
}
