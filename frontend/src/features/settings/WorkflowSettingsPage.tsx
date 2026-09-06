import { useEffect, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import {
  useIssues,
  useProject,
  useProjectWorkflow,
  useRemoveWorkflowStatus,
  useReplaceWorkflow,
} from '../../api/queries';
import { ApiError } from '../../api/client';
import type { IssueStatusKind, IssueStatusRef, ProjectWorkflow } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Badge, Banner } from '../../components/kit';
import { Button, Input } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { plural } from '../../lib/format';
import { issueStatusKinds, issueStatusKindTones } from '../../lib/labels';
import { StatusBadge } from '../tracker/StatusBadge';

const TEST_ID = 'WorkflowSettings';

/** A draft row. `id` is null for a status this session added and the server has not seen. */
interface DraftStatus {
  id: string | null;
  name: string;
  kind: IssueStatusKind;
  /**
   * 021 D-b's flag, carried through untouched and **not editable here yet** (021 T5 adds the
   * control since 021 T5). It is on the draft because the save is a whole-document PUT: a field this
   * screen dropped would be a field this screen silently cleared, and `blocking` is what makes `Unblock`
   * find its way back.
   */
  blocking: boolean;
  /** Stable within the session, so React's key survives a reorder of an unsaved row. */
  rowId: string;
}

const KINDS: readonly IssueStatusKind[] = ['NOT_STARTED', 'IN_PROGRESS', 'DONE'];

/**
 * Story B1's workflow screen — `settings-workflow.html`, ported whole.
 *
 * Three things in that mockup are requirements rather than decoration, and each is the reason a
 * line of this file exists:
 *
 * 1. **The note at the top.** *"Every percentage in this project is computed from the done column
 *    below. A status marked in progress is not partial credit — it is simply not done."* This is
 *    the only place in the product where D8's rule is explained to the person who can change it,
 *    and that person is the one for whom the rule is otherwise invisible: they pick a kind from a
 *    dropdown and three screens' arithmetic moves.
 * 2. **`↑ / ↓` beside every row, not only drag.** WCAG 2.2 *Dragging Movements* (2.5.7) requires a
 *    single-pointer alternative to any drag, and the mockups carry it as a standing rule —
 *    *dragging is never the only way*. Here the buttons are the **only** way: a reorder is a
 *    whole-document PUT, and the board next door already owns the drag machinery this screen would
 *    have to duplicate. So the accessible path is not the fallback, it is the path.
 * 3. **The removal panel.** Selecting a status that issues hold cannot silently orphan them, so the
 *    screen asks where they go, names the count, and shows which issues are affected. The last
 *    `DONE`-kind status cannot be removed at all — the control is disabled with the reason on it
 *    rather than letting the person reach the server's 409.
 *
 * ## Why the whole document is submitted at once
 *
 * Reordering is a statement about the *set*. A sequence of per-status writes has an intermediate
 * state where two statuses claim one position, which is exactly what these buttons generate — so
 * the endpoint is a `PUT` of the whole workflow and the draft below is local until Save. The
 * consequence worth knowing: **a status dropped from the draft is a removal the PUT will refuse**
 * with a 409 naming the count, because that request carries no destination. The removal that
 * answers the question is the panel, and it is a different operation.
 *
 * ## Read by everyone, written by `issue:workflow`
 *
 * The `GET` is gated on project visibility alone — every screen that renders a status picker needs
 * this document — so this page renders its current state for anybody who can reach the project and
 * disables the controls for anybody who cannot change them. §31's disable-don't-hide, and the
 * reason a Test Engineer learns the capability exists rather than that the product lacks it.
 */
export function WorkflowSettingsPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const workflow = useProjectWorkflow(projectId);

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Workflow"
        documentTitle={project.data ? `Workflow · ${project.data.name}` : 'Workflow'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Workflow' },
        ]}
        subtitle="The statuses an issue in this project can be in, the order they read in, and which of them mean done."
      />

      <div className="mb-4 max-w-[960px]" data-testid={`${TEST_ID}.CompletionNote`}>
        <Banner tone="neutral">
          <span>
            Every percentage in this project is computed from the <strong>done</strong> column below.
            A status marked <em>in progress</em> is not partial credit — it is simply not done.
          </span>
        </Banner>
      </div>

      <DataRegion
        testId={`${TEST_ID}.Region`}
        state={{ ...workflow, refetch: () => void workflow.refetch() }}
      >
        {(current) => (
          <PermissionGate projectId={projectId} permission="issue:workflow">
            {({ allowed }) => (
              <WorkflowEditor projectId={projectId} current={current} canEdit={allowed} />
            )}
          </PermissionGate>
        )}
      </DataRegion>
    </div>
  );
}

function WorkflowEditor({
  projectId,
  current,
  canEdit,
}: {
  projectId: string;
  current: ProjectWorkflow;
  canEdit: boolean;
}) {
  const save = useReplaceWorkflow(projectId);
  const [statuses, setStatuses] = useState<DraftStatus[]>(() => draftOf(current.statuses));
  const [moves, setMoves] = useState<Set<string>>(() => movesOf(current));
  const [removing, setRemoving] = useState<IssueStatusRef | null>(null);
  const [refused, setRefused] = useState<string | null>(null);

  /*
    Re-seeded when the server's copy changes identity — after a save, or after another admin's
    change arrives through an invalidation. Keyed on the fields rather than on the object, because
    TanStack Query hands back a new reference on every refetch and comparing references would
    discard a half-edited workflow every time the window regained focus. `ReviewSettingsPage` makes
    the same argument for the same reason.
  */
  const fingerprint = JSON.stringify(current);
  useEffect(() => {
    const server = JSON.parse(fingerprint) as ProjectWorkflow;
    setStatuses(draftOf(server.statuses));
    setMoves(movesOf(server));
    setRemoving(null);
  }, [fingerprint]);

  const dirty =
    // `blocking` joined the tuple with the radio that sets it (021 T5): without it, marking a
    // different status blocking left Save disabled and the change unreachable.
    JSON.stringify(statuses.map(({ id, name, kind, blocking }) => ({ id, name, kind, blocking })))
      !== JSON.stringify(
        current.statuses.map(({ id, name, kind, blocking }) => ({ id, name, kind, blocking })),
      )
    || JSON.stringify([...moves].sort()) !== JSON.stringify([...movesOf(current)].sort());

  const doneCount = statuses.filter((status) => status.kind === 'DONE').length;
  const blankName = statuses.some((status) => status.name.trim() === '');
  /*
    Two statuses with one name is a 400 the server returns as a field error. Checked here as well
    so the refusal arrives before the request rather than after it — the constraint is the backstop
    and this is the check, which is `ReviewSettingsPage`'s pattern for the peer-review bound.
  */
  const duplicateName =
    new Set(statuses.map((status) => status.name.trim().toLowerCase())).size !== statuses.length;

  const move = (index: number, by: -1 | 1) => {
    const target = index + by;
    if (target < 0 || target >= statuses.length) return;
    const next = [...statuses];
    [next[index], next[target]] = [next[target], next[index]];
    setStatuses(next);
  };

  const submit = () => {
    setRefused(null);
    save.mutate(
      {
        projectId: current.projectId,
        statuses: statuses.map((status, position) => ({
          // A row this session added has no id, and the server allocates one — which is also what
          // tells it apart from a rename of an existing row.
          ...(status.id === null ? {} : { id: status.id }),
          name: status.name.trim(),
          kind: status.kind,
          position,
          // ~~Sent back as it came; this screen does not yet own the flag.~~ → **it does, 021 T5**,
          // through the radio on each row. Still spelled explicitly, because a whole-document PUT
          // drops what it omits and an omitted flag is a cleared one.
          blocking: status.blocking,
        })) as ProjectWorkflow['statuses'],
        types: current.types,
        transitions: [...moves].map((pair) => {
          const [fromStatusId, toStatusId] = pair.split('>');
          return { fromStatusId, toStatusId };
        }),
      },
      {
        onError: (error) =>
          setRefused(
            error instanceof ApiError
              ? error.detail
              : "Couldn't save the workflow. Check your connection and try again.",
          ),
      },
    );
  };

  return (
    <div className="grid max-w-[1180px] gap-3.5 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="flex flex-col gap-3.5">
        <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Statuses`}>
          <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
            <h2 className="m-0 text-[13.5px] font-semibold text-ink">Statuses, in order</h2>
            <Identifier variant="muted">{String(statuses.length)}</Identifier>
            <span className="flex-1" />
            {/*
              The mockup reads "drag ⠿ or use ↑ / ↓". There is no drag here, so the caption says
              what this screen actually offers — a caption promising an affordance the page does
              not have is the render-then-reject failure with the reject left to the reader.
            */}
            <span className="text-[11.5px] text-ink-4">reorder with ↑ / ↓</span>
          </div>

          <div className="flex flex-col gap-1.5 p-2.5">
            {statuses.map((status, index) => (
              <StatusRow
                key={status.rowId}
                status={status}
                index={index}
                total={statuses.length}
                canEdit={canEdit}
                lastDone={status.kind === 'DONE' && doneCount === 1}
                onRename={(name) =>
                  setStatuses((rows) =>
                    rows.map((row) => (row.rowId === status.rowId ? { ...row, name } : row)),
                  )
                }
                onKind={(kind) =>
                  setStatuses((rows) =>
                    rows.map((row) => (row.rowId === status.rowId ? { ...row, kind } : row)),
                  )
                }
                /*
                  A radio, so choosing one **clears every other** in the same update — which is what
                  makes the server's 422 (D-b: at most one per project) unreachable from this screen
                  rather than a thing the reader can walk into. The 422 stays as the guard against a
                  second tab, and it arrives in the refusal banner like any other write failure; a
                  field error would need a field, and a radio group whose invariant cannot be
                  violated has nothing to hang one on.
                */
                onBlocking={() =>
                  setStatuses((rows) =>
                    rows.map((row) => ({ ...row, blocking: row.rowId === status.rowId })),
                  )
                }
                onMove={(by) => move(index, by)}
                onRemove={() => {
                  if (status.id === null) {
                    // Never saved, so nothing holds it and there is nothing to ask about.
                    setStatuses((rows) => rows.filter((row) => row.rowId !== status.rowId));
                    return;
                  }
                  setRemoving({
                    id: status.id,
                    name: status.name,
                    kind: status.kind,
                    position: index,
                    blocking: status.blocking,
                  });
                }}
              />
            ))}
          </div>

          <div className="flex flex-wrap items-center gap-2.5 border-t border-line px-3.5 py-2.5">
            <span className="max-w-[62ch] text-[11.5px] leading-[1.55] text-ink-4">
              A brand-new project arrives with exactly this set, so nobody has to configure anything
              before creating their first issue.
            </span>
            <span className="flex-1" />
            <Button
              size="sm"
              disabled={!canEdit}
              data-testid={`${TEST_ID}.AddStatus`}
              onClick={() =>
                setStatuses((rows) => [
                  ...rows,
                  {
                    id: null,
                    name: '',
                    kind: 'NOT_STARTED',
                    // A new row is never the blocking one: it has no name yet, and the radio beside
                    // it is how somebody says otherwise once it does.
                    blocking: false,
                    rowId: `new-${rows.length}-${Date.now()}`,
                  },
                ])
              }
            >
              + Add status
            </Button>
          </div>
        </section>

        {refused && (
          <div role="alert">
            <Banner tone="warn" data-testid={`${TEST_ID}.Refused`}>
              <span>{refused}</span>
            </Banner>
          </div>
        )}

        {(blankName || duplicateName || doneCount === 0) && (
          <Banner tone="warn" data-testid={`${TEST_ID}.Invalid`}>
            <span>
              {doneCount === 0
                ? 'Keep one status that means done — every completion figure in the product counts the issues in one.'
                : blankName
                  ? 'Every status needs a name.'
                  : 'Two statuses cannot share a name.'}
            </span>
          </Banner>
        )}

        <div className="flex items-center gap-2.5">
          <Button
            variant="primary"
            disabled={!canEdit || !dirty || blankName || duplicateName || doneCount === 0 || save.isPending}
            onClick={submit}
            data-testid={`${TEST_ID}.Save`}
          >
            {save.isPending ? 'Saving…' : 'Save workflow'}
          </Button>
          {!canEdit && (
            <span className="text-[12px] text-ink-4" data-testid={`${TEST_ID}.ReadOnly`}>
              You can see this project's workflow but not change it.
            </span>
          )}
        </div>

        {removing && (
          <RemovalPanel
            projectId={projectId}
            status={removing}
            statuses={current.statuses}
            lastDone={removing.kind === 'DONE' && current.statuses.filter((s) => s.kind === 'DONE').length === 1}
            onCancel={() => setRemoving(null)}
          />
        )}
      </div>

      <div className="flex flex-col gap-3.5">
        <AllowedMoves
          statuses={statuses}
          moves={moves}
          canEdit={canEdit}
          onToggle={(pair) =>
            setMoves((current_) => {
              const next = new Set(current_);
              if (next.has(pair)) next.delete(pair);
              else next.add(pair);
              return next;
            })
          }
        />
      </div>
    </div>
  );
}

function StatusRow({
  status,
  index,
  total,
  canEdit,
  lastDone,
  onRename,
  onKind,
  onBlocking,
  onMove,
  onRemove,
}: {
  status: DraftStatus;
  index: number;
  total: number;
  canEdit: boolean;
  lastDone: boolean;
  onRename: (name: string) => void;
  onKind: (kind: IssueStatusKind) => void;
  /** Mark this status the project's `blocking` one, clearing whichever held it (021 D-b). */
  onBlocking: () => void;
  onMove: (by: -1 | 1) => void;
  onRemove: () => void;
}) {
  return (
    <div
      className="flex flex-wrap items-center gap-2.5 rounded-[8px] border border-line bg-paper px-2.5 py-2"
      data-testid={`${TEST_ID}.Row`}
    >
      <span className="w-4 font-mono text-[11px] text-ink-4">{index + 1}</span>

      {/*
        The name is an input, not a label with a pencil that opens one. The mockup's ✎ is an extra
        press on a field that is already a field, and a rename here is a draft edit rather than a
        write — nothing is saved until Save, so there is nothing for an edit mode to protect.
      */}
      <label className="sr-only" htmlFor={`${TEST_ID}-name-${status.rowId}`}>
        {`Status ${index + 1} name`}
      </label>
      <Input
        id={`${TEST_ID}-name-${status.rowId}`}
        value={status.name}
        disabled={!canEdit}
        placeholder="Name this status"
        onChange={(event) => onRename(event.target.value)}
        data-testid={`${TEST_ID}.Row.Name`}
        className="w-[190px]"
      />

      {/*
        The badge beside the field is not a duplicate of it: it is how this status will read on
        every card and in every column, which is the one thing the kind dropdown cannot show.
        Somebody marking a status done and seeing the pill go green has learned what they changed.
      */}
      <Badge tone={issueStatusKindTones[status.kind]} data-testid={`${TEST_ID}.Row.Tone`}>
        {status.name.trim() === '' ? 'unnamed' : status.name}
      </Badge>

      <span className="flex-1" />

      <label className="sr-only" htmlFor={`${TEST_ID}-kind-${status.rowId}`}>
        {`What ${status.name || `status ${index + 1}`} means`}
      </label>
      <NativeSelect
        id={`${TEST_ID}-kind-${status.rowId}`}
        value={status.kind}
        disabled={!canEdit}
        onChange={(event) => onKind(event.target.value as IssueStatusKind)}
        data-testid={`${TEST_ID}.Row.Kind`}
        className="w-[130px]"
      >
        {KINDS.map((kind) => (
          <option key={kind} value={kind}>
            {issueStatusKinds[kind]}
          </option>
        ))}
      </NativeSelect>

      {/*
        The `blocking` radio — **one per project**, which is why it is a radio and not a checkbox
        (021 D-b). The status that carries it is the one that remembers where a card came from:
        entering it writes `blockedFromStatus` on the issue and the origin becomes a legal target on
        the way out, which is the whole of how `Unblock` knows where to put a card back.

        A `name` shared across every row is what makes the browser clear the others, and it is not
        the control's only mechanism — the handler clears them in the draft as well, because a radio
        group rendered from state is only as exclusive as the state behind it.

        No "none" option, and that is deliberate rather than an omission: a project may have no
        blocking status, and un-marking one is done by marking another. A project that genuinely
        wants none has no such status to un-mark — removing the status is how that is expressed.
      */}
      <label
        className="flex items-center gap-1.5 text-[11.5px] text-ink-3"
        title={
          'The one status per project that remembers where a card came from, so Unblock can put it '
          + 'back without asking.'
        }
      >
        <input
          type="radio"
          name={`${TEST_ID}-blocking`}
          checked={status.blocking}
          disabled={!canEdit}
          onChange={onBlocking}
          aria-label={`Make ${status.name || `status ${index + 1}`} the blocking status`}
          data-testid={`${TEST_ID}.Row.Blocking`}
          className="size-3.5 accent-[color:var(--color-failed)]"
        />
        blocking
      </label>

      {/*
        WCAG 2.2 Dragging Movements, and the mockups' standing rule that dragging is never the only
        way. `aria-label` names the status rather than the direction alone: a screen reader reading
        "Move up" twelve times down a list says nothing about which row is about to move.
      */}
      <div className="flex items-center gap-1">
        <Button
          size="sm"
          disabled={!canEdit || index === 0}
          aria-label={`Move ${status.name || `status ${index + 1}`} up`}
          onClick={() => onMove(-1)}
          data-testid={`${TEST_ID}.Row.Up`}
        >
          ↑
        </Button>
        <Button
          size="sm"
          disabled={!canEdit || index === total - 1}
          aria-label={`Move ${status.name || `status ${index + 1}`} down`}
          onClick={() => onMove(1)}
          data-testid={`${TEST_ID}.Row.Down`}
        >
          ↓
        </Button>
      </div>

      {/*
        Disabled with the reason on it rather than letting the person reach the server's 409: every
        percentage in the product is DONE over the counted total, so a project with no done status
        has no expressible completion at all.
      */}
      <Button
        size="sm"
        variant="ghost"
        disabled={!canEdit || lastDone || total === 1}
        title={
          lastDone
            ? 'This is the only status that means done, and every completion figure counts those issues.'
            : total === 1
              ? 'A project needs at least one status.'
              : undefined
        }
        aria-label={`Remove ${status.name || `status ${index + 1}`}`}
        onClick={onRemove}
        data-testid={`${TEST_ID}.Row.Remove`}
      >
        Remove
      </Button>
    </div>
  );
}

/**
 * Story B1's third criterion — *"I am asked which status those issues move to"*.
 *
 * The count and the chips are `GET /issues?statusId=…`, which is the same read the work table
 * makes: there is no count endpoint, and there does not need to be one, because the question the
 * panel asks is *which* issues rather than how many, and the rows answer both. A project where the
 * status holds nothing still renders this panel — the removal is still a decision, and a
 * destination is still required by the endpoint — but it says so instead of asking about issues
 * that do not exist.
 */
function RemovalPanel({
  projectId,
  status,
  statuses,
  lastDone,
  onCancel,
}: {
  projectId: string;
  status: IssueStatusRef;
  statuses: ProjectWorkflow['statuses'];
  lastDone: boolean;
  onCancel: () => void;
}) {
  const held = useIssues(projectId, { statusId: [status.id] }, 'key');
  const remove = useRemoveWorkflowStatus(projectId);
  const destinations = statuses.filter((candidate) => candidate.id !== status.id);
  const [destination, setDestination] = useState(destinations[0]?.id ?? '');
  const [refused, setRefused] = useState<string | null>(null);

  return (
    <section
      className="rounded-[10px] border border-[color:var(--color-warning)]/35 bg-panel"
      data-testid={`${TEST_ID}.Removal`}
    >
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">{`Removing "${status.name}"`}</h2>
        <Badge tone="retest">needs a decision</Badge>
      </div>

      <div className="flex flex-col gap-3 p-3.5">
        {lastDone ? (
          <Banner tone="warn" data-testid={`${TEST_ID}.Removal.LastDone`}>
            <span>
              This is the project's only status that means <strong>done</strong>. Every completion
              figure in the product counts the issues in one, so it cannot be removed — mark another
              status done first.
            </span>
          </Banner>
        ) : (
          <DataRegion
            testId={`${TEST_ID}.Removal.Region`}
            state={{ ...held, refetch: () => void held.refetch() }}
          >
            {(issues) => (
              <>
                <Banner tone={issues.length > 0 ? 'warn' : 'neutral'}>
                  <span data-testid={`${TEST_ID}.Removal.Count`}>
                    {issues.length > 0 ? (
                      <>
                        <strong>{`${plural(issues.length, 'issue')} currently ${issues.length === 1 ? 'holds' : 'hold'} this status.`}</strong>{' '}
                        Removing it cannot silently orphan them, so pick where they go.
                      </>
                    ) : (
                      'No issue holds this status. The move below still has to name a destination — the endpoint takes one either way.'
                    )}
                  </span>
                </Banner>

                <div className="max-w-[340px]">
                  <label
                    className="mb-1 block text-[12.5px] text-ink-2"
                    htmlFor={`${TEST_ID}-destination`}
                  >
                    {issues.length > 0 ? `Move those ${plural(issues.length, 'issue')} to` : 'Move any issues to'}
                  </label>
                  <NativeSelect
                    id={`${TEST_ID}-destination`}
                    value={destination}
                    onChange={(event) => setDestination(event.target.value)}
                    data-testid={`${TEST_ID}.Removal.Destination`}
                  >
                    {destinations.map((candidate) => (
                      <option key={candidate.id} value={candidate.id}>
                        {candidate.name}
                      </option>
                    ))}
                  </NativeSelect>
                </div>

                {issues.length > 0 && (
                  <div className="flex flex-wrap gap-1.5" data-testid={`${TEST_ID}.Removal.Affected`}>
                    {/*
                      Links rather than `kit/Chip`, whose `onClick` is required: these name specific
                      issues somebody is about to move, and the reader's next question is "which
                      ones" — so each one goes where it can be read. A chip driving navigation from
                      an `onClick` would also break `ui-rules`' in-app-nav-is-a-real-link rule.
                    */}
                    {issues.slice(0, 4).map((issue) => (
                      <RouterLink
                        key={issue.key}
                        to={`/issues/${encodeURIComponent(issue.key)}`}
                        className="flex items-center gap-1.5 rounded-full border border-line bg-wash-1 px-2.5 py-1 text-[11.5px] text-ink-2 no-underline hover:text-steel"
                      >
                        <Identifier variant="muted">{issue.key}</Identifier>
                        <span>{issue.title}</span>
                      </RouterLink>
                    ))}
                    {issues.length > 4 && (
                      <span className="rounded-full border border-line bg-wash-1 px-2.5 py-1 text-[11.5px] text-ink-3">
                        {`+${issues.length - 4}`}
                      </span>
                    )}
                  </div>
                )}

                {refused && (
                  <div role="alert">
                    <Banner tone="bad" data-testid={`${TEST_ID}.Removal.Refused`}>
                      <span>{refused}</span>
                    </Banner>
                  </div>
                )}

                <div className="flex items-center gap-2">
                  <Button onClick={onCancel} data-testid={`${TEST_ID}.Removal.Cancel`}>
                    Cancel
                  </Button>
                  <Button
                    variant="danger"
                    disabled={destination === '' || remove.isPending}
                    data-testid={`${TEST_ID}.Removal.Confirm`}
                    onClick={() => {
                      setRefused(null);
                      remove.mutate(
                        { statusId: status.id, reassignToStatusId: destination },
                        {
                          onSuccess: onCancel,
                          onError: (error) =>
                            setRefused(
                              error instanceof ApiError
                                ? error.detail
                                : "Couldn't remove that status. Check your connection and try again.",
                            ),
                        },
                      );
                    }}
                  >
                    {issues.length > 0
                      ? `Remove and move ${plural(issues.length, 'issue')}`
                      : 'Remove status'}
                  </Button>
                </div>
              </>
            )}
          </DataRegion>
        )}
      </div>
    </section>
  );
}

/**
 * The legal moves, as a matrix — the mockup's "Allowed moves" panel with its *"Edit transitions"*
 * button already open.
 *
 * A pair is a cell rather than an entity: the endpoint submits the whole set with the statuses, and
 * the mockup's own copy is about the *set* — *"From In Progress an issue may go to … Not to Done —
 * a review gate stands between them."* So a checkbox grid is the shape, and the "not to" sentence
 * is the unchecked cells rather than a second list to keep in step.
 *
 * The diagonal is omitted rather than disabled: a status moving to itself is not a move, and the
 * server drops such a pair. A disabled checkbox there would be a control that exists to be
 * unusable.
 */
function AllowedMoves({
  statuses,
  moves,
  canEdit,
  onToggle,
}: {
  statuses: readonly DraftStatus[];
  moves: ReadonlySet<string>;
  canEdit: boolean;
  onToggle: (pair: string) => void;
}) {
  const saved = statuses.filter((status): status is DraftStatus & { id: string } => status.id !== null);

  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Moves`}>
      <div className="border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Allowed moves</h2>
      </div>

      <div className="flex flex-col gap-2.5 p-3.5">
        <p className="m-0 text-[12.5px] leading-[1.55] text-ink-3">
          A move that is not ticked is refused, and the board says why rather than snapping the card
          back in silence.
        </p>

        {saved.length === 0 ? (
          <p className="m-0 text-[12.5px] text-ink-4" data-testid={`${TEST_ID}.Moves.Unsaved`}>
            Save the statuses first — a move can only point at a status the project has.
          </p>
        ) : (
          <div className="flex flex-col gap-1.5">
            {saved.map((from) => (
              <div key={from.id} className="border-b border-line pb-1.5 last:border-b-0">
                <div className="mb-1 flex items-center gap-1.5 text-[12px] text-ink-3">
                  <span>From</span>
                  <StatusBadge status={from} />
                </div>
                <div className="flex flex-wrap gap-2.5">
                  {saved
                    .filter((to) => to.id !== from.id)
                    .map((to) => {
                      const pair = `${from.id}>${to.id}`;
                      return (
                        <label
                          key={to.id}
                          className="flex cursor-pointer items-center gap-1.5 text-[12px] text-ink-2"
                        >
                          <input
                            type="checkbox"
                            checked={moves.has(pair)}
                            disabled={!canEdit}
                            onChange={() => onToggle(pair)}
                            data-testid={`${TEST_ID}.Moves.${from.name}>${to.name}`}
                            className="accent-[color:var(--color-action-primary)]"
                          />
                          {to.name}
                        </label>
                      );
                    })}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function draftOf(statuses: readonly IssueStatusRef[]): DraftStatus[] {
  return statuses.map((status) => ({
    id: status.id,
    name: status.name,
    kind: status.kind,
    blocking: status.blocking,
    rowId: status.id,
  }));
}

/** The transition set as `from>to` strings, which is what a checkbox can hold and compare. */
function movesOf(workflow: ProjectWorkflow): Set<string> {
  return new Set(workflow.transitions.map((pair) => `${pair.fromStatusId}>${pair.toStatusId}`));
}
