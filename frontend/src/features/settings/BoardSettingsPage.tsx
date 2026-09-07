import { useEffect, useMemo, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import {
  useBoardConfigs,
  useBoards,
  useCreateBoard,
  useCreateLabel,
  useDeleteBoard,
  useDeleteLabel,
  useLabels,
  useProject,
  useProjectWorkflow,
  useUpdateBoard,
  useUpdateLabel,
} from '../../api/queries';
import type {
  BoardColumnInput,
  BoardConfig,
  BoardInput,
  BoardSummary,
  IssueLabel,
  IssueStatusRef,
  ProjectWorkflow,
} from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { SettingsSubnav } from './SettingsSubnav';
import { PermissionGate } from '../../components/PermissionGate';
import { Banner } from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
} from '../../components/ui';
import { useCan } from '../../auth/useCan';
import { plural } from '../../lib/format';
import { swimlaneByLabels } from '../../lib/labels';
import { LABEL_COLORS, labelFill } from '../../lib/statusColors';
import { ColumnEditor } from './ColumnEditor';
import { CoverageMatrix } from './CoverageMatrix';
import {
  coverageOf,
  coverageProblem,
  draftOf,
  unclaimedNote,
  type DraftColumn,
} from './boardCoverage';

const TEST_ID = 'BoardSettings';

/**
 * `settings-boards.html` — every board over one project, what each column claims, and the check
 * that says whether the claims add up.
 *
 * <h2>The note at the top is the screen's answer to the only frightening word on it</h2>
 *
 * *"A board is a view, not a copy. The two boards below show the same issues. Move a card on one
 * and it is in the matching column on the other; delete a board and no issue is lost."* This is the
 * mockup's own copy and it is load-bearing: **Delete** is the one control here a person will
 * hesitate over, and the answer to the hesitation is a property of the model (scope §12.1 — a board
 * is a *lens*, it owns nothing) rather than a reassurance. The confirmation dialog says it again at
 * the moment it is asked, because a note at the top of a page is not read at the bottom of it.
 *
 * <h2>The coverage table is the screen's point, and it is read-only</h2>
 *
 * `ColumnEditor` beside this file derives the verdict; this renders it as the mockup's status ×
 * board grid, with the green footer when it holds and the failing statuses named when it does not.
 * The **repair** is in the editor — the tray of unclaimed statuses, each with its Move menu — and
 * not in the table, so the check stays a check: a person reading a row is asking whether the board
 * is right, not editing it from there.
 *
 * <h2>Three disagreements with the mockup</h2>
 *
 * **"used by 7 people" is not drawn.** `BoardSummary` carries `id`, `name`, `columnCount` and
 * `swimlaneBy`, and there is nothing on the contract that counts a board's viewers — `board_swimlane`
 * holds a per-viewer collapse state and is reachable from no endpoint (012 D-z). The hint says what
 * is on the wire: the column count and the grouping axis.
 *
 * **The subnav is one entry, not five.** The mockup's row is *General · Workflow · Boards ·
 * Repositories · Imports*; ~~Repositories is M5's and Imports is M4's~~ → **Imports landed in 014
 * and is in the rail now, gated on `issue:import`; Repositories is still M5's**, and a nav link
 * that leads nowhere is worse than an absent one. This screen adds Boards to the project rail
 * beside Workflow and nothing else.
 *
 * **Labels are here and the mockup has no panel for them.** `ProjectBoardView`'s Configure boards
 * button has been promising *"columns, WIP limits and labels"* since 012 T7, the four label
 * operations have had no caller since T6, and the label's `color` is a choice nothing else in the
 * product offers. A second settings screen for one panel would be a second rail entry for the same
 * subject.
 */
export function BoardSettingsPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const boards = useBoards(projectId);
  const workflow = useProjectWorkflow(projectId);

  const [creating, setCreating] = useState(false);

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Boards"
        documentTitle={project.data ? `Boards · ${project.data.name}` : 'Boards'}
        subtitle="Every board over this project, the statuses each column claims, and the check that says the claims add up."
        actions={
          <PermissionGate projectId={projectId} permission="board:configure">
            {({ allowed, reason }) => (
              <Button
                variant="primary"
                disabled={!allowed}
                title={allowed ? undefined : reason}
                data-testid={`${TEST_ID}.NewBoard`}
                onClick={() => setCreating(true)}
              >
                + New board
              </Button>
            )}
          </PermissionGate>
        }
      />

      <SettingsSubnav projectId={projectId} />

      <div className="mb-4 max-w-[960px]" data-testid={`${TEST_ID}.Note`}>
        <Banner tone="neutral">
          <span>
            <strong>A board is a view, not a copy.</strong> Every board below shows the same issues.
            Move a card on one and it is in the matching column on the other; delete a board and no
            issue is lost.
          </span>
        </Banner>
      </div>

      <DataRegion
        testId={`${TEST_ID}.Workflow`}
        state={{ ...workflow, refetch: () => void workflow.refetch() }}
      >
        {(document) => (
          <DataRegion
            testId={`${TEST_ID}.Boards`}
            state={{ ...boards, refetch: () => void boards.refetch() }}
          >
            {(summaries) => (
              <BoardConfigurations
                projectId={projectId}
                statuses={document.statuses}
                summaries={summaries}
                creating={creating}
                onCloseCreate={() => setCreating(false)}
                workflow={document}
              />
            )}
          </DataRegion>
        )}
      </DataRegion>
    </div>
  );
}

/**
 * The N configuration reads, as one region.
 *
 * `BoardSummary` carries a column *count* and not the columns (the picker renders "4 columns" and
 * shipping the layout there would make switching boards a second read of something already
 * fetched), so this screen — which needs every board's columns at once, for the coverage table —
 * is the one caller that asks for all of them. `useBoardConfigs` is `useQueries` over the same
 * options object `useBoardConfig` uses, so the cache entries are shared with the board screen
 * rather than duplicated beside it.
 */
function BoardConfigurations({
  projectId,
  statuses,
  summaries,
  workflow,
  creating,
  onCloseCreate,
}: {
  projectId: string;
  statuses: readonly IssueStatusRef[];
  /** The picker's list, in position order — the matrix's columns come from it. */
  summaries: readonly BoardSummary[];
  workflow: ProjectWorkflow;
  creating: boolean;
  onCloseCreate: () => void;
}) {
  const configs = useBoardConfigs(useMemo(() => summaries.map((s) => s.id), [summaries]));

  return (
    <>
      <DataRegion testId={`${TEST_ID}.Configs`} state={configs}>
        {(boards) => (
          <BoardSettings
            projectId={projectId}
            statuses={statuses}
            boards={boards}
            summaries={summaries}
          />
        )}
      </DataRegion>
      {creating && (
        <NewBoardDialog projectId={projectId} workflow={workflow} onClose={onCloseCreate} />
      )}
    </>
  );
}

function BoardSettings({
  projectId,
  statuses,
  boards,
  summaries,
}: {
  projectId: string;
  statuses: readonly IssueStatusRef[];
  boards: readonly BoardConfig[];
  summaries: readonly BoardSummary[];
}) {
  /*
    Read directly rather than through `PermissionGate`, for the reason `BacklogView` records: the
    denied branch renders its child inside a tooltip-anchoring `<span role="none">`, which is right
    around one control and is invalid HTML — and a tooltip over every row — around a list. The
    verdict travels down as a prop instead, and the one control that IS single (New board) keeps the
    component.
  */
  const gate = useCan(projectId);
  const canEdit = gate.can('board:configure');
  const reason = gate.reason('board:configure');

  /*
    One draft per board, re-seeded when the server's copy changes identity. Keyed on the fields
    rather than on the object: TanStack hands back a new reference on every refetch, so comparing
    references would discard a half-edited board every time the window regained focus.
    `WorkflowSettingsPage` and `ReviewSettingsPage` both make this argument; this is the third.
  */
  const fingerprint = JSON.stringify(boards);
  const [drafts, setDrafts] = useState<Record<string, DraftColumn[]>>(() => seed(boards));
  useEffect(() => {
    setDrafts(seed(JSON.parse(fingerprint) as BoardConfig[]));
  }, [fingerprint]);

  const [deleting, setDeleting] = useState<BoardConfig | null>(null);

  return (
    <div className="flex max-w-[1180px] flex-col gap-3.5">
      {boards.map((board) => (
        <BoardPanel
          key={board.id}
          projectId={projectId}
          board={board}
          statuses={statuses}
          columns={drafts[board.id] ?? draftOf(board)}
          canEdit={canEdit}
          reason={reason}
          lastBoard={boards.length === 1}
          onChange={(columns) => setDrafts((current) => ({ ...current, [board.id]: columns }))}
          onDelete={() => setDeleting(board)}
        />
      ))}

      {/*
        ~~`CoverageTable` — the per-board *"every status claimed exactly once"* checklist.~~ →
        **`CoverageMatrix`, 021 R7.** The question inverted: not *"does this board claim every
        status?"* but *"which boards show each status?"*, with the uncovered row as a **warning**
        rather than the refusal it used to be. It reads `GET …/boards/coverage` rather than the
        drafts, because the answer is over every board and this screen's drafts are one board's —
        the component's own header has the argument.
      */}
      <CoverageMatrix projectId={projectId} boards={summaries} />

      <LabelsPanel projectId={projectId} canEdit={canEdit} reason={reason} />

      {deleting && (
        <DeleteBoardDialog
          projectId={projectId}
          board={deleting}
          onClose={() => setDeleting(null)}
        />
      )}
    </div>
  );
}

function seed(boards: readonly BoardConfig[]): Record<string, DraftColumn[]> {
  return Object.fromEntries(boards.map((board) => [board.id, draftOf(board)]));
}

/** One board: its head, its columns, and the save that is refused before the server would refuse it. */
function BoardPanel({
  projectId,
  board,
  statuses,
  columns,
  canEdit,
  reason,
  lastBoard,
  onChange,
  onDelete,
}: {
  projectId: string;
  board: BoardConfig;
  statuses: readonly IssueStatusRef[];
  columns: DraftColumn[];
  canEdit: boolean;
  reason: string | undefined;
  lastBoard: boolean;
  onChange: (columns: DraftColumn[]) => void;
  onDelete: () => void;
}) {
  const save = useUpdateBoard(projectId);
  const [name, setName] = useState(board.name);
  const [refused, setRefused] = useState<string | null>(null);
  useEffect(() => setName(board.name), [board.name]);

  const coverage = coverageOf(statuses, columns);
  const problem = coverageProblem(coverage);
  const note = unclaimedNote(coverage);
  const unnamed = name.trim() === '' || columns.some((column) => column.name.trim() === '');
  const dirty =
    name !== board.name || JSON.stringify(columns) !== JSON.stringify(draftOf(board));

  const testId = `${TEST_ID}.Board.${board.name}`;

  return (
    <section
      className="rounded-[10px] border border-line bg-panel"
      data-testid={testId}
      aria-label={board.name}
    >
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">{board.name}</h2>
        <Identifier variant="muted">
          {`${plural(board.columns.length, 'column')} · ${swimlaneByLabels[board.swimlaneBy]}`}
        </Identifier>
        <span className="flex-1" />
        {/*
          A real `<Link>`, and it opens the board this panel is about: `?board=` is what
          `ProjectBoardView`'s picker reads, so Open lands on THIS board rather than on whichever
          one the picker last held.
        */}
        <Button asChild size="sm" data-testid={`${testId}.Open`}>
          <RouterLink to={`/projects/${projectId}/work?view=board&board=${board.id}`}>
            Open
          </RouterLink>
        </Button>
        {/*
          §31, and it is the server's own 422 said before it is provoked: `deleteBoard` refuses the
          project's last board, because a project with no board cannot render `?view=board` at all
          and an empty picker is a dead end rather than a state.
        */}
        <Button
          variant="danger"
          size="sm"
          disabled={!canEdit || lastBoard}
          title={
            lastBoard
              ? 'A project keeps at least one board — deleting this one would leave the board view with nothing to show.'
              : canEdit
                ? undefined
                : reason
          }
          data-testid={`${testId}.Delete`}
          onClick={onDelete}
        >
          Delete
        </Button>
      </div>

      <div className="flex flex-col gap-3 p-3">
        <div className="flex flex-wrap items-center gap-2">
          <label className="text-[11.5px] text-ink-3" htmlFor={`${testId}-name`}>
            Board name
          </label>
          <Input
            id={`${testId}-name`}
            value={name}
            disabled={!canEdit}
            maxLength={80}
            onChange={(event) => setName(event.target.value)}
            data-testid={`${testId}.Name`}
            className="h-7 min-h-7 w-[220px] text-[12px]"
          />
        </div>

        <ColumnEditor
          statuses={statuses}
          columns={columns}
          canEdit={canEdit}
          onChange={onChange}
          testId={testId}
        />

        {problem && (
          <Banner tone="warn" data-testid={`${testId}.Coverage.Failing`}>
            <span>{problem}</span>
          </Banner>
        )}

        {/*
          021 D-e: an unclaimed status is a **note**, not a refusal. It used to be `problem`'s first
          arm and to disable Save; a board claims a subset now, so saying "refused" about the
          ordinary state of every board §2.4 seeds would be the screen refusing its own defaults.
        */}
        {note && (
          <Banner tone="neutral" data-testid={`${testId}.Coverage.Unclaimed`}>
            <span>{note}</span>
          </Banner>
        )}

        {refused && (
          <div role="alert">
            <Banner tone="bad" data-testid={`${testId}.Refused`}>
              <span>{refused}</span>
            </Banner>
          </div>
        )}

        <div className="flex flex-wrap items-center gap-2.5">
          <Button
            variant="primary"
            size="sm"
            disabled={!canEdit || !dirty || unnamed || !coverage.holds || save.isPending}
            aria-label={`Save ${board.name}`}
            title={canEdit ? undefined : reason}
            data-testid={`${testId}.Save`}
            onClick={() => {
              setRefused(null);
              save.mutate(
                { boardId: board.id, input: inputOf(name, board, columns) },
                {
                  onError: (error) =>
                    setRefused(
                      error instanceof ApiError
                        ? error.detail
                        : "Couldn't save this board. Check your connection and try again.",
                    ),
                },
              );
            }}
          >
            {save.isPending ? 'Saving…' : 'Save'}
          </Button>
          <Button
            size="sm"
            disabled={!dirty || save.isPending}
            data-testid={`${testId}.Revert`}
            onClick={() => {
              setRefused(null);
              setName(board.name);
              onChange(draftOf(board));
            }}
          >
            Revert
          </Button>
          {!canEdit && (
            <span className="text-[12px] text-ink-4" data-testid={`${testId}.ReadOnly`}>
              You can see this project&rsquo;s boards but not change them.
            </span>
          )}
        </div>
      </div>
    </section>
  );
}

/**
 * The draft as the endpoint spells it. `columns` is a **replacement**: a column with an `id` is kept
 * and updated, one without is created, one whose id is absent is deleted, and position is the array
 * index — so the order here is the order on the board.
 */
function inputOf(name: string, board: BoardConfig, columns: readonly DraftColumn[]): BoardInput {
  return {
    name: name.trim(),
    swimlaneBy: board.swimlaneBy,
    columns: columns.map(
      (column): BoardColumnInput => ({
        ...(column.id === null ? {} : { id: column.id }),
        name: column.name.trim(),
        wipLimit: column.wipLimit,
        statusIds: column.statusIds,
        defaultStatusId: column.defaultStatusId,
      }),
    ),
  };
}

/**
 * Creating a board — a name, and columns nobody has to compose.
 *
 * **One column per status, in workflow order, each claiming one.** That is the shape a project is
 * seeded with, it is the only starting arrangement that cannot fail the 422, and it is the one a
 * person can then fold: making a coarse board is moving statuses together and deleting the columns
 * they left, which the editor above does and this dialog would only duplicate. Asking somebody to
 * define six columns and their claims in a modal before they have seen the board is the render-then-
 * reject failure with the reject moved to the end of a form.
 */
function NewBoardDialog({
  projectId,
  workflow,
  onClose,
}: {
  projectId: string;
  workflow: ProjectWorkflow;
  onClose: () => void;
}) {
  const create = useCreateBoard(projectId);
  const [name, setName] = useState('');
  const failed = create.error !== null;

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <DialogTitle>New board</DialogTitle>
          <DialogDescription>
            It starts with one column per status, in workflow order. Fold them together afterwards to
            make a coarser board — no issue moves either way, because a board is a view.
          </DialogDescription>
        </DialogHeader>

        <div className="mt-3 flex flex-col gap-1.5">
          <label className="text-[11.5px] text-ink-3" htmlFor={`${TEST_ID}-new-board-name`}>
            Name
          </label>
          <Input
            id={`${TEST_ID}-new-board-name`}
            value={name}
            maxLength={80}
            autoFocus
            placeholder="Stakeholder board"
            onChange={(event) => setName(event.target.value)}
            data-testid={`${TEST_ID}.NewBoard.Name`}
          />
        </div>

        {failed && (
          <Banner tone="bad" className="mt-3" data-testid={`${TEST_ID}.NewBoard.Error`}>
            <span>
              {create.error instanceof ApiError
                ? create.error.detail
                : "That didn't work. Try again."}
            </span>
          </Banner>
        )}

        <DialogFooter>
          <Button onClick={onClose} data-testid={`${TEST_ID}.NewBoard.Cancel`}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={name.trim() === '' || create.isPending}
            data-testid={`${TEST_ID}.NewBoard.Confirm`}
            onClick={() =>
              create.mutate(
                {
                  name: name.trim(),
                  swimlaneBy: 'NONE',
                  columns: workflow.statuses.map(
                    (status): BoardColumnInput => ({
                      name: status.name,
                      wipLimit: null,
                      statusIds: [status.id],
                      defaultStatusId: status.id,
                    }),
                  ),
                },
                { onSuccess: () => onClose() },
              )
            }
          >
            Create board
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Confirming a delete, and saying what a delete of a *lens* actually is.
 *
 * The destructive word is the one thing on this screen a person will hesitate over, and the answer
 * is the mockup's: a board owns no issue, so deleting one loses no work. Repeated here rather than
 * relied on from the note at the top of the page, because the note is not on screen at the moment
 * the question is asked.
 */
function DeleteBoardDialog({
  projectId,
  board,
  onClose,
}: {
  projectId: string;
  board: BoardConfig;
  onClose: () => void;
}) {
  const remove = useDeleteBoard(projectId);

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <DialogTitle>Delete {board.name}?</DialogTitle>
          <DialogDescription>
            A board is a view, not a copy — delete it and no issue is lost. Every issue keeps its
            status and stays on every other board of this project. Only this arrangement of them
            goes.
          </DialogDescription>
        </DialogHeader>

        {remove.error !== null && (
          <Banner tone="bad" className="mt-3" data-testid={`${TEST_ID}.Delete.Error`}>
            <span>
              {remove.error instanceof ApiError
                ? remove.error.detail
                : "That didn't work. Try again."}
            </span>
          </Banner>
        )}

        <DialogFooter>
          <Button onClick={onClose} data-testid={`${TEST_ID}.Delete.Cancel`}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={remove.isPending}
            data-testid={`${TEST_ID}.Delete.Confirm`}
            onClick={() => remove.mutate(board.id, { onSuccess: () => onClose() })}
          >
            Delete board
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * The project's labels — the informal, fast-moving axis (scope §12.2), and the screen where a
 * label's colour is finally chosen.
 *
 * **`board:configure`**, not a `label:*` key: there is none, and creating or renaming a project's
 * labels is project configuration beside columns and WIP limits (012 D-e). Putting a label *on* an
 * issue costs `issue:edit` and happens on the issue.
 *
 * The ten colour slots are `--color-label-1` … `--color-label-10`, declared in `index.css` by this
 * task. They were deliberately undeclared until now, and three files carried a comment saying why:
 * picking a hue on a screen that cannot show you the hue is guessing, and this is the screen that
 * can show it. The picker is the chips themselves — pressing one *is* choosing it — because a
 * `<select>` of ten token names would be that same blind guess with a dropdown around it.
 */
function LabelsPanel({
  projectId,
  canEdit,
  reason,
}: {
  projectId: string;
  canEdit: boolean;
  reason: string | undefined;
}) {
  const labels = useLabels(projectId);
  const create = useCreateLabel(projectId);
  const [name, setName] = useState('');
  const [color, setColor] = useState<string>(LABEL_COLORS[0]);

  return (
    <section
      className="rounded-[10px] border border-line bg-panel"
      data-testid={`${TEST_ID}.Labels`}
      aria-label="Labels"
    >
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Labels</h2>
        <span className="max-w-[62ch] text-[11.5px] leading-[1.55] text-ink-4">
          The fast axis — many per card, and what a board is scanned by. Not the classification
          vocabulary, which is a different screen and a controlled one.
        </span>
      </div>

      <DataRegion
        testId={`${TEST_ID}.Labels.Region`}
        state={{ ...labels, refetch: () => void labels.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          title: 'No labels yet',
          body: 'A label is a word this project agreed on. Add the first one below.',
        }}
      >
        {(rows) => (
          <ul className="m-0 flex list-none flex-col gap-1.5 p-3">
            {rows.map((label) => (
              <LabelRow
                key={label.id}
                projectId={projectId}
                label={label}
                canEdit={canEdit}
                reason={reason}
              />
            ))}
          </ul>
        )}
      </DataRegion>

      <div className="flex flex-wrap items-end gap-2.5 border-t border-line px-3.5 py-2.5">
        <div className="flex flex-col gap-1">
          <label className="text-[11.5px] text-ink-3" htmlFor={`${TEST_ID}-label-name`}>
            New label
          </label>
          <Input
            id={`${TEST_ID}-label-name`}
            value={name}
            maxLength={32}
            disabled={!canEdit}
            placeholder="checkout"
            onChange={(event) => setName(event.target.value)}
            data-testid={`${TEST_ID}.Labels.Name`}
            className="h-7 min-h-7 w-[180px] text-[12px]"
          />
        </div>
        <ColorPicker
          value={color}
          disabled={!canEdit}
          onChange={setColor}
          testId={`${TEST_ID}.Labels.Color`}
        />
        <Button
          size="sm"
          variant="primary"
          disabled={!canEdit || name.trim() === '' || create.isPending}
          title={canEdit ? undefined : reason}
          data-testid={`${TEST_ID}.Labels.Add`}
          onClick={() =>
            create.mutate(
              { name: name.trim(), color },
              { onSuccess: () => setName('') },
            )
          }
        >
          Add label
        </Button>
        {create.error !== null && (
          <span className="text-[11.5px] text-[color:var(--color-failed)]" role="alert">
            {create.error instanceof ApiError && create.error.status === 409
              ? 'This project already has a label with that name.'
              : "That didn't work. Try again."}
          </span>
        )}
      </div>
    </section>
  );
}

function LabelRow({
  projectId,
  label,
  canEdit,
  reason,
}: {
  projectId: string;
  label: IssueLabel;
  canEdit: boolean;
  reason: string | undefined;
}) {
  const update = useUpdateLabel(projectId);
  const remove = useDeleteLabel(projectId);
  const [name, setName] = useState(label.name);
  useEffect(() => setName(label.name), [label.name]);

  const dirty = name.trim() !== label.name && name.trim() !== '';

  return (
    <li className="flex flex-wrap items-center gap-2 rounded-[8px] border border-line bg-paper px-2.5 py-2">
      <span
        className="rounded-full px-2 py-0.5 text-[11px] text-ink"
        style={{ background: labelFill(label.color) }}
        data-testid={`${TEST_ID}.Labels.Chip.${label.name}`}
      >
        {label.name}
      </span>

      <label className="sr-only" htmlFor={`${TEST_ID}-label-${label.id}`}>
        {`Rename ${label.name}`}
      </label>
      <Input
        id={`${TEST_ID}-label-${label.id}`}
        value={name}
        maxLength={32}
        disabled={!canEdit}
        onChange={(event) => setName(event.target.value)}
        data-testid={`${TEST_ID}.Labels.Rename.${label.name}`}
        className="h-7 min-h-7 w-[180px] text-[12px]"
      />

      <ColorPicker
        value={label.color}
        disabled={!canEdit}
        onChange={(color) => update.mutate({ labelId: label.id, input: { name: label.name, color } })}
        testId={`${TEST_ID}.Labels.Recolor.${label.name}`}
      />

      <span className="flex-1" />

      <Button
        size="sm"
        disabled={!canEdit || !dirty || update.isPending}
        title={canEdit ? undefined : reason}
        data-testid={`${TEST_ID}.Labels.Save.${label.name}`}
        onClick={() =>
          update.mutate({ labelId: label.id, input: { name: name.trim(), color: label.color } })
        }
      >
        Rename
      </Button>
      {/*
        No confirmation: deleting a label takes a word off some cards and loses nothing else, which
        is a different weight of act from deleting a board. `useDeleteLabel` invalidates every
        board's cards and the backlog, so the chips go with it.
      */}
      <Button
        variant="danger"
        size="sm"
        disabled={!canEdit || remove.isPending}
        title={canEdit ? undefined : reason}
        aria-label={`Delete ${label.name}`}
        data-testid={`${TEST_ID}.Labels.Delete.${label.name}`}
        onClick={() => remove.mutate(label.id)}
      >
        Delete
      </Button>
    </li>
  );
}

/** The ten slots, as themselves. A swatch is the only honest control for choosing a colour. */
function ColorPicker({
  value,
  disabled,
  onChange,
  testId,
}: {
  value: string;
  disabled: boolean;
  onChange: (color: string) => void;
  testId: string;
}) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-[11.5px] text-ink-3" id={`${testId}-legend`}>
        Colour
      </span>
      <div className="flex flex-wrap gap-1" role="radiogroup" aria-labelledby={`${testId}-legend`}>
        {LABEL_COLORS.map((slot, index) => (
          <button
            key={slot}
            type="button"
            role="radio"
            aria-checked={value === slot}
            aria-label={`Colour ${index + 1}`}
            disabled={disabled}
            data-testid={`${testId}.${slot}`}
            className={
              'size-5 rounded-full border transition-colors disabled:opacity-45 '
              + (value === slot ? 'border-line-strong ring-2 ring-[color:var(--color-given)]' : 'border-line')
            }
            style={{ background: labelFill(slot) }}
            onClick={() => onChange(slot)}
          />
        ))}
      </div>
    </div>
  );
}
