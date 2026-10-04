import { X } from 'lucide-react';
import type { IssueStatusRef } from '../../api/types';
import { Badge } from '../../components/kit';
import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  Input,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { issueStatusKindTones } from '../../lib/labels';
import { coverageOf, type DraftColumn } from './boardCoverage';

/**
 * One board's columns and what each of them claims — `settings-boards.html`, the two board panels.
 *
 * <h2>The coverage check is the screen's point — and 021 R7 took half of it away</h2>
 *
 * ~~The mockup renders it as a table with a green footer: "Every status is claimed by exactly one
 * column on every board. A status claimed twice, or not at all, is what this check exists to
 * catch."~~ → **superseded.** A board claims a **subset** now, so *not at all* is the ordinary
 * state of every board §2.4 seeds and is no longer part of the check. What survives is *claimed
 * twice* (a unique index) and *a column claiming nothing* (`minItems: 1` on the contract), and both
 * still have to say so **by status name, before the save is attempted**, rather than letting
 * `createBoard`/`updateBoard` answer 422 with a body the reader has to decode. `ui-rules`: never
 * render an action the backend will always reject.
 *
 * The tray of unclaimed statuses below the columns stays exactly as it was, and it is now a
 * *control* rather than a repair: it is how a status is put onto this board, and the sentence beside
 * it reads "On no column" rather than naming a failure. Whether it is on **any** board is
 * `CoverageMatrix`'s question, which is over the set and cannot be answered from one board's draft.
 *
 * <h2>The default-status control appears only on a column claiming more than one status</h2>
 *
 * On a one-status column it has exactly one legal value, so it is a select that cannot be used to
 * decide anything. The mockup draws it only on the stakeholder board and puts the reason in the
 * team board's footer — *"One status per column here, so a drop has no ambiguity about which status
 * it means."* Both footers are rendered from the same condition that shows or hides the control, so
 * the sentence cannot end up explaining a control that is on screen.
 *
 * <h2>Why a status is moved and unclaimed, and never added</h2>
 *
 * `Move` takes a status out of one column and into another in one step, so the "claimed twice" arm
 * of the invariant is not constructible here at all — which is deliberate, because it is the arm a
 * person cannot repair by looking at one column. {@link coverageOf} still derives both arms from
 * one count rather than special-casing the reachable one: the board arrives from a server whose
 * unique index makes a double claim impossible, and a check that trusted that would be a check that
 * only ever answered the question it already knew the answer to.
 *
 * `×` unclaims instead of moving, which is the arm that *is* reachable — and the status lands in
 * the tray below the columns rather than vanishing, because a status with no chip anywhere is one
 * a reader has no control over and no way to put back.
 */

export function ColumnEditor({
  statuses,
  columns,
  canEdit,
  onChange,
  testId,
}: {
  /** The project's workflow, in its own order — the rows of the coverage check. */
  statuses: readonly IssueStatusRef[];
  columns: readonly DraftColumn[];
  canEdit: boolean;
  onChange: (columns: DraftColumn[]) => void;
  testId: string;
}) {
  const byId = new Map(statuses.map((status) => [status.id, status]));
  const coverage = coverageOf(statuses, columns);
  const folded = columns.some((column) => column.statusIds.length > 1);

  const replace = (rowId: string, change: Partial<DraftColumn>) =>
    onChange(columns.map((column) => (column.rowId === rowId ? { ...column, ...change } : column)));

  /*
    One step, not an unclaim followed by a claim: the intermediate state of the two-step version is
    a board with the status claimed twice or not at all, and a reader who stopped there would be
    looking at a failing coverage check they did not cause.
  */
  const moveStatus = (statusId: string, toRowId: string) =>
    onChange(
      columns.map((column) => {
        if (column.rowId === toRowId) {
          if (column.statusIds.includes(statusId)) return column;
          const statusIds = [...column.statusIds, statusId];
          return { ...column, statusIds, defaultStatusId: defaultAfter(column, statusIds) };
        }
        if (!column.statusIds.includes(statusId)) return column;
        const statusIds = column.statusIds.filter((id) => id !== statusId);
        return { ...column, statusIds, defaultStatusId: defaultAfter(column, statusIds) };
      }),
    );

  const unclaim = (statusId: string) =>
    onChange(
      columns.map((column) => {
        if (!column.statusIds.includes(statusId)) return column;
        const statusIds = column.statusIds.filter((id) => id !== statusId);
        return { ...column, statusIds, defaultStatusId: defaultAfter(column, statusIds) };
      }),
    );

  return (
    <div className="flex flex-col gap-3">
      <div className="overflow-x-auto" data-testid={`${testId}.Columns`}>
        <div className="flex min-w-min items-stretch gap-2 pb-1">
          {columns.map((column) => (
            <section
              key={column.rowId}
              className="flex w-[212px] flex-none flex-col gap-2 rounded-lg border border-line bg-paper p-2.5"
              data-testid={`${testId}.Column.${column.name}`}
              aria-label={column.name || 'Unnamed column'}
            >
              <div className="flex items-center gap-1.5">
                <label className="sr-only" htmlFor={`${testId}-name-${column.rowId}`}>
                  {`Column ${column.name || 'name'}`}
                </label>
                <Input
                  id={`${testId}-name-${column.rowId}`}
                  value={column.name}
                  disabled={!canEdit}
                  placeholder="Name this column"
                  onChange={(event) => replace(column.rowId, { name: event.target.value })}
                  data-testid={`${testId}.Column.${column.name}.Name`}
                  className="h-7 min-h-7 flex-1 text-[12px]"
                />
                <Button
                  variant="quiet"
                  size="sm"
                  disabled={!canEdit || columns.length === 1}
                  title={
                    columns.length === 1
                      ? 'A board keeps at least one column.'
                      : undefined
                  }
                  aria-label={`Remove column ${column.name}`}
                  data-testid={`${testId}.Column.${column.name}.Remove`}
                  className="size-6 min-h-6 flex-none p-0"
                  onClick={() =>
                    onChange(columns.filter((other) => other.rowId !== column.rowId))
                  }
                >
                  <X aria-hidden className="size-3.5" />
                </Button>
              </div>

              <div className="flex flex-wrap gap-1">
                {column.statusIds.map((statusId) => {
                  const status = byId.get(statusId);
                  if (!status) return null;
                  return (
                    <span key={statusId} className="inline-flex items-center gap-0.5">
                      <Badge
                        tone={issueStatusKindTones[status.kind]}
                        data-testid={`${testId}.Column.${column.name}.Status.${status.name}`}
                      >
                        {status.name}
                      </Badge>
                      <MoveStatusMenu
                        status={status}
                        from={column}
                        columns={columns}
                        canEdit={canEdit}
                        onMove={(toRowId) => moveStatus(statusId, toRowId)}
                        testId={`${testId}.Move.${status.name}`}
                      />
                      <Button
                        variant="quiet"
                        size="sm"
                        disabled={!canEdit}
                        aria-label={`Remove ${status.name} from ${column.name}`}
                        data-testid={`${testId}.Unclaim.${status.name}`}
                        className="size-5 min-h-5 flex-none p-0"
                        onClick={() => unclaim(statusId)}
                      >
                        <X aria-hidden className="size-3" />
                      </Button>
                    </span>
                  );
                })}
              </div>

              {/*
                Only on a folded column, and the footer below says so. On a one-status column this
                select has exactly one option, which is a control that cannot decide anything —
                rendering it would teach a reader that the choice exists and then refuse to let them
                make it.
              */}
              {column.statusIds.length > 1 && (
                <div className="flex flex-col gap-0.5">
                  <label
                    className="text-[10.5px] text-ink-4"
                    htmlFor={`${testId}-default-${column.rowId}`}
                  >
                    Default on drop
                  </label>
                  <NativeSelect
                    id={`${testId}-default-${column.rowId}`}
                    value={column.defaultStatusId}
                    disabled={!canEdit}
                    data-testid={`${testId}.Column.${column.name}.Default`}
                    className="h-7 min-h-7 text-[12px]"
                    onChange={(event) =>
                      replace(column.rowId, { defaultStatusId: event.target.value })
                    }
                  >
                    {column.statusIds.map((statusId) => (
                      <option key={statusId} value={statusId}>
                        {byId.get(statusId)?.name ?? statusId}
                      </option>
                    ))}
                  </NativeSelect>
                </div>
              )}

              <div className="flex items-center gap-1.5">
                <label
                  className="text-[10.5px] text-ink-4"
                  htmlFor={`${testId}-wip-${column.rowId}`}
                >
                  WIP limit
                </label>
                {/*
                  Empty is `null` and `null` is "no limit", which the contract is explicit is not
                  the same as zero. `min={1}` matches `BoardColumnInput.wipLimit`, so a 0 is refused
                  here rather than reaching a server that reads it as a column nothing may be in.
                */}
                <Input
                  id={`${testId}-wip-${column.rowId}`}
                  type="number"
                  min={1}
                  value={column.wipLimit === null ? '' : String(column.wipLimit)}
                  disabled={!canEdit}
                  placeholder="none"
                  data-testid={`${testId}.Column.${column.name}.Wip`}
                  className="h-7 min-h-7 w-[72px] text-[12px]"
                  onChange={(event) =>
                    replace(column.rowId, {
                      wipLimit: event.target.value === '' ? null : Number(event.target.value),
                    })
                  }
                />
              </div>
            </section>
          ))}
        </div>
      </div>

      {coverage.unclaimed.length > 0 && (
        <div
          className="flex flex-wrap items-center gap-1.5 rounded-lg border border-line bg-wash-1 px-2.5 py-2"
          data-testid={`${testId}.Unclaimed`}
        >
          <span className="text-[11.5px] text-ink-3">On no column:</span>
          {coverage.unclaimed.map((status) => (
            <span key={status.id} className="inline-flex items-center gap-0.5">
              <Badge tone={issueStatusKindTones[status.kind]}>{status.name}</Badge>
              <MoveStatusMenu
                status={status}
                from={null}
                columns={columns}
                canEdit={canEdit}
                onMove={(toRowId) => moveStatus(status.id, toRowId)}
                testId={`${testId}.Move.${status.name}`}
              />
            </span>
          ))}
        </div>
      )}

      <p className="m-0 max-w-[78ch] text-[11.5px] leading-[1.55] text-ink-4">
        {folded
          ? 'A card dropped into a folded column takes that column’s default status. A coarser '
            + 'board never invents a status the workflow does not have.'
          : 'One status per column here, so a drop has no ambiguity about which status it means.'}
      </p>
    </div>
  );
}

/**
 * Where a status goes next. Every other column is offered; the one it is already on is not, because
 * moving it there is not a refusal, it is a non-event.
 */
function MoveStatusMenu({
  status,
  from,
  columns,
  canEdit,
  onMove,
  testId,
}: {
  status: IssueStatusRef;
  /** The column it sits on, or null when it sits on none — the tray's arm. */
  from: DraftColumn | null;
  columns: readonly DraftColumn[];
  canEdit: boolean;
  onMove: (toRowId: string) => void;
  testId: string;
}) {
  const targets = columns.filter((column) => column.rowId !== from?.rowId);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="quiet"
          size="sm"
          disabled={!canEdit || targets.length === 0}
          aria-label={`Move ${status.name}`}
          data-testid={testId}
          className="h-5 min-h-5 flex-none px-1 text-[10.5px]"
        >
          Move
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        {targets.map((column) => (
          <DropdownMenuItem
            key={column.rowId}
            onSelect={() => onMove(column.rowId)}
            data-testid={`${testId}.To.${column.name}`}
          >
            {column.name || 'Unnamed column'}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * The default a column keeps after its claims change.
 *
 * `defaultStatusId` must always be one of `statusIds` — the server enforces it — so a default whose
 * status has just left falls to the first remaining claim. An empty column keeps the string empty
 * rather than holding a stale id: it is already a coverage failure, and a default pointing at a
 * status the column no longer claims would be a second, invisible one underneath it.
 */
function defaultAfter(column: DraftColumn, statusIds: readonly string[]): string {
  if (statusIds.includes(column.defaultStatusId)) return column.defaultStatusId;
  return statusIds[0] ?? '';
}
