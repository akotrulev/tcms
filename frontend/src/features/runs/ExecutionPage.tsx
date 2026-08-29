import { Check, X } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { useAnnounce } from '../../a11y/useAnnounce';
import { useCan } from '../../auth/useCan';
import { useUnsavedWork } from '../../auth/useSessionRecovery';
import type {
  CaseStatus,
  StatusKind,
  StepKeyword,
  StepResult,
  TestExecution,
  TestExecutionSummary,
} from '../../api/types';
import { useExecution, useProject, useRun, useStatuses, useSubmitResult } from '../../api/queries';
import { TopStripSlot } from '../../app/topStripSlot';
import { DataRegion } from '../../components/DataRegion';
import {
  Badge,
  Banner,
  DocumentSurface,
  DocumentTitle,
  ProcedureLine,
  type BadgeTone,
  type ProcedureKeyword,
} from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Checkbox, Input, Textarea } from '../../components/ui';
import { cn } from '../../lib/cn';
import { toast } from '../../notifications/toast';
import { DefectPanel } from './DefectPanel';
import { StepEvidence } from './StepEvidence';
import { StepDataView } from '../cases/StepDataCell';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { QuarantineChip, StatusChip } from '../../components/StatusChip';
import { BlockedDialog } from './BlockedDialog';
import { EvidencePanel } from './EvidencePanel';
import { ExecutionAssignee } from './ExecutionAssignee';
import { ExecutionGrid } from './ExecutionGrid';
import { ExecutionQueue } from './ExecutionQueue';
import { durationSecondsField, formatDateTime, parseDurationSeconds } from '../../lib/format';
import { isRunClosed, statusLabels } from '../../lib/labels';
import {
  EXECUTION_MODES,
  executionModeParam,
  parseExecutionMode,
  type ExecutionMode,
} from './executionMode';
import {
  EXECUTION_SHORTCUTS,
  isTypingTarget,
  matchNotesShortcut,
  matchShortcut,
  type ExecutionAction,
} from './executionKeys';
import { orderedQueue } from './queueOrder';
import { ShortcutOverlay } from './ShortcutOverlay';
import { suggestedVerdict } from './suggestedVerdict';

/**
 * Recording a result.
 *
 * The two rules that shape this screen:
 *
 *   1. **Preconditions are a checklist that gates Blocked.** A tester who can't get the
 *      preconditions to hold is not looking at a failing feature, they're looking at a
 *      broken environment — and recording that as *Failed* poisons the pass rate and sends
 *      someone chasing a defect that doesn't exist. So Blocked requires saying which
 *      precondition didn't hold. **This holds in all three modes** — it is why Grid's `B`
 *      opens the dialog rather than writing.
 *   2. **The case version is pinned and displayed.** This execution records a result against
 *      the text shown here, not against whatever the case says next week.
 *
 * Since §100 the screen has three layouts over one queue — Focus, Split, Grid — chosen in the
 * URL. `executionMode.ts` carries what each is for; all three write the same result through the
 * same mutation, and none of them relaxes rule 1.
 *
 * <h3>007 T12 — the runner drawn as a document</h3>
 *
 * Ported against `docs/specs/assets/renders/03-execution.html`. Three of the render's four notes
 * land here, and the fourth is recorded in the plan as blocked rather than half-built:
 *
 *   - **The procedure gets the reading width and the ink.** The steps are the document, at the
 *     editor's own 74ch measure, with a verdict gutter beside each line: the current step at full
 *     ink behind a steel edge, the ones already recorded ticked and quiet, the ones ahead legible
 *     but dimmed. A tester reads ahead to know what is coming, so nothing is hidden.
 *   - **The verdict is a number key.** `1`–`4` record the step the tester is on and move to the
 *     next, and the digits are printed on that step's own marks rather than left in a help page.
 *   - **A failure is answered in place, not in a dialog.** Marking a step Failed turns the rail's
 *     recording section into the failure panel — the step it is about stays on screen and readable
 *     while the description is typed, which a modal would have covered (UX#55).
 */
export function ExecutionPage() {
  const { projectId = '', runId = '', executionId = '' } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const project = useProject(projectId);
  const execution = useExecution(projectId, runId, executionId);
  const statuses = useStatuses(projectId);

  /*
    The run, for three things now: whether it has ended (since §72), the queue Split and Grid
    render, and the case count in the header. Still normally free — the tester arrived from the
    run detail, so this key is already in the cache.
  */
  const run = useRun(projectId, runId);
  const mode = parseExecutionMode(searchParams.get('mode'));
  const modeParam = executionModeParam(mode);

  /*
    What arriving here announces — which case, and how far through the run. Raised on ARRIVAL
    rather than from `moveBy` on the keypress (§248), because the keypress's own announcement was
    overwritten by the generic "page loaded" a moment later, every single time: the sentence was
    written, shipped, and never heard. Announcing it here also covers arriving from the rail or
    from a link, which the keyboard's version never did.

    Undefined until both reads land, so the fallback sentence is used rather than a position
    computed from an empty queue.

    ~~`PageHeader` raised this from its `announcement` prop.~~ → **superseded by 007 T12**: this
    screen has no page heading, for T11's reason — the case's own title is the document's subject
    and repeating it above the rail's active item is design language rule 1's named failure. React
    19 hoists a bare `<title>`, so the two things the header did that nothing else does survive it.
  */
  const arrivalQueue = orderedQueue(run.data?.executions ?? []);
  const arrivalAt = arrivalQueue.findIndex((candidate) => candidate.id === executionId);
  const arrival =
    execution.data && arrivalAt >= 0
      ? `Case ${arrivalAt + 1} of ${arrivalQueue.length}. `
        + `${execution.data.caseKey}, ${execution.data.caseTitle}.`
      : undefined;

  const pageTitle = execution.data ? `${execution.data.caseKey} in this run` : 'Execution';
  const announce = useAnnounce();
  useEffect(() => {
    announce(arrival ?? `${pageTitle} page loaded`);
  }, [announce, arrival, pageTitle]);

  const setMode = (next: ExecutionMode) => {
    const params = new URLSearchParams(searchParams);
    const value = executionModeParam(next);
    if (value === null) params.delete('mode');
    else params.set('mode', value);
    // `replace`, so flipping between layouts does not fill the back button with them — Back
    // belongs to the case the tester came from, not to the last three times they changed view.
    setSearchParams(params, { replace: true });
  };

  return (
    <div className="flex min-h-full flex-col">
      <title>{`${pageTitle} · TCMS`}</title>

      {/*
        THE CRUMB AND THE LENS ARE CHROME AND STAY UP. They render outside the region on purpose:
        a failed or slow read must not take the only non-rail way back to the run with it, and a
        tester whose case failed to load still needs the layout switch — the resilience rule that
        a failure is scoped to the response-dependent subtree.
      */}
      <TopStripSlot>
        <nav
          aria-label="Breadcrumb"
          data-testid="Execution.Breadcrumbs"
          className="mr-auto hidden min-w-0 truncate text-[12.5px] text-ink-3 sm:block"
        >
          <RouterLink to={`/projects/${projectId}`} className="hover:underline">
            {project.data?.name ?? 'Project'}
          </RouterLink>
          {' / '}
          <RouterLink to={`/projects/${projectId}/runs`} className="hover:underline">
            Test runs
          </RouterLink>
          {' / '}
          <RouterLink to={`/projects/${projectId}/runs/${runId}`} className="hover:underline">
            <span className="font-medium text-ink-2">{run.data?.key ?? 'Run'}</span>
          </RouterLink>
        </nav>
      </TopStripSlot>

      <TopStripSlot>
        <div className="flex flex-wrap items-center gap-2.5">
          {execution.data && (
            <>
              {arrivalAt >= 0 && (
                <span className="text-[12px] text-ink-4">
                  Case <b className="font-mono font-medium text-ink-2">{arrivalAt + 1}</b> of{' '}
                  <b className="font-mono font-medium text-ink-2">{arrivalQueue.length}</b>
                </span>
              )}
              {/*
                An execution has no display key of its own (plan §6t) — it is identified by the
                case it ran and the run it belongs to. So the case key IS the subject identifier
                here, and it links to the case. Showing "TE_1401" told a tester nothing; "TC-3"
                is what they recognise and what they'd paste into a message.
              */}
              <RouterLink
                to={`/projects/${projectId}/cases/${execution.data.caseId}`}
                data-testid="Execution.CaseLink"
              >
                <Identifier variant="strong">{execution.data.caseKey}</Identifier>
              </RouterLink>
              <span title="This execution is recorded against this version of the case. Later edits do not change it.">
                <Identifier variant="muted">v{execution.data.caseVersionNo}</Identifier>
              </span>
              {execution.data.status?.kind && <StatusChip kind={execution.data.status.kind} />}
              {execution.data.quarantined && <QuarantineChip testId="Execution.Quarantined" />}
            </>
          )}
          <ModeSwitch mode={mode} onChange={setMode} />
        </div>
      </TopStripSlot>

      <Workspace
        mode={mode}
        modeParam={modeParam}
        projectId={projectId}
        runId={runId}
        executionId={executionId}
        executions={run.data?.executions ?? []}
        statuses={statuses.data ?? []}
        frozen={run.data ? isRunClosed(run.data.status) : false}
      >
        <DataRegion testId="Execution.Content" state={{ ...execution, refetch: () => void execution.refetch() }}>
          {(data) => (
            <ExecutionForm
              execution={data}
              statuses={statuses.data ?? []}
              projectId={projectId}
              runId={runId}
              executionId={executionId}
              modeParam={modeParam}
            />
          )}
        </DataRegion>
      </Workspace>
    </div>
  );
}

/**
 * The layout each mode produces, and the one place the queue is rendered beside the detail.
 *
 * Focus is the fallthrough rather than a branch of its own: a mode that renders neither the rail
 * nor the grid *is* Focus, so an unrecognised `?mode=` degrades to the narrowest working screen
 * rather than to an empty one. (`parseExecutionMode` already normalises those, so this is the
 * second of two guards on the same failure — a blank page produced by a query parameter.)
 */
function Workspace({
  mode,
  modeParam,
  projectId,
  runId,
  executionId,
  executions,
  statuses,
  frozen,
  children,
}: {
  mode: ExecutionMode;
  modeParam: string | null;
  projectId: string;
  runId: string;
  executionId: string;
  executions: TestExecutionSummary[];
  statuses: CaseStatus[];
  frozen: boolean;
  children: ReactNode;
}) {
  /* Grid's Blocked, held here because the dialog outlives the row that opened it. */
  const [blockedFor, setBlockedFor] = useState<TestExecutionSummary | null>(null);

  if (mode === 'grid') {
    return (
      <>
        <ExecutionGrid
          executions={executions}
          statuses={statuses}
          projectId={projectId}
          runId={runId}
          currentExecutionId={executionId}
          modeParam={modeParam}
          frozen={frozen}
          onBlockedRequested={setBlockedFor}
        />
        {blockedFor && (
          <GridBlockedDialog
            // Keyed on the execution, so opening the dialog for a second row mounts a fresh one
            // rather than inheriting the first row's ticked conditions and typed reason.
            key={blockedFor.id}
            execution={blockedFor}
            statuses={statuses}
            projectId={projectId}
            runId={runId}
            onClose={() => setBlockedFor(null)}
          />
        )}
      </>
    );
  }

  return (
    <div className="flex min-w-0 flex-1 items-start">
      {mode === 'split' && (
        <ExecutionQueue
          executions={executions}
          currentExecutionId={executionId}
          projectId={projectId}
          runId={runId}
          modeParam={modeParam}
        />
      )}
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
}

/**
 * Blocked from Grid mode: read the execution, show its preconditions, write the verdict.
 *
 * A component rather than inline state because it calls two hooks bound to one execution id —
 * and because mounting it only when a row asks means Grid makes no per-row detail request until
 * somebody actually presses `B`.
 */
function GridBlockedDialog({
  execution,
  statuses,
  projectId,
  runId,
  onClose,
}: {
  execution: TestExecutionSummary;
  statuses: CaseStatus[];
  projectId: string;
  runId: string;
  onClose: () => void;
}) {
  const detail = useExecution(projectId, runId, execution.id);
  const submit = useSubmitResult(projectId, runId, execution.id);

  const record = async (reason: string) => {
    const blocked = statuses.find((s) => s.kind === 'BLOCKED');
    if (!blocked || !detail.data) return;
    try {
      await submit.mutateAsync({
        statusId: blocked.id,
        // The existing comment is kept and the reason appended — the same composition the
        // detail screen does, because a PATCH overwrites `comment` with whatever it is sent.
        comment: [detail.data.comment, reason].filter(Boolean).join('\n\n'),
        // Carried for the same reason the comment is, and it is easier to miss: this dialog has
        // no duration control, so an omitted field here would CLEAR a duration the tester
        // recorded on the detail screen, from a dialog that never showed it to them.
        durationMs: detail.data.durationMs ?? null,
        lockVersion: detail.data.lockVersion,
      });
      toast(`Recorded Blocked for ${execution.caseKey}`, { variant: 'warning' });
      onClose();
    } catch (failure) {
      toast(
        failure instanceof ApiError ? failure.detail : "Couldn't record the result. Check your connection and try again.",
        { variant: 'error' },
      );
    }
  };

  return (
    <BlockedDialog
      open
      onClose={onClose}
      // `conditions` rather than `unmet`: Grid has no checklist on screen, so the dialog renders
      // one. Undefined while the read is in flight — the dialog then shows the free-text reason
      // alone, which is still a valid Blocked, rather than an empty checklist claiming the case
      // has no preconditions.
      conditions={detail.data?.preconditions}
      pending={submit.isPending || detail.isPending}
      onConfirm={(reason) => void record(reason)}
    />
  );
}

/** Which layout, in the URL — the render's `.lens`. Three segments rather than a menu: the set is
 *  small, fixed, and the tester switches between them often enough that a click to open a menu is
 *  a click too many. */
function ModeSwitch({ mode, onChange }: { mode: ExecutionMode; onChange: (next: ExecutionMode) => void }) {
  const hints = {
    focus: 'Focus — one case at a time, nothing else on screen',
    split: 'Split — the run’s cases beside the one you are running',
    grid: 'Grid — every case in one table, for a pass you already know the answers to',
  } satisfies Record<ExecutionMode, string>;

  return (
    <div role="group" aria-label="Execution layout" className="inline-flex gap-0.5 rounded-md bg-wash-1 p-0.5">
      {EXECUTION_MODES.map((option) => {
        const active = option === mode;
        return (
          <button
            key={option}
            type="button"
            title={hints[option]}
            onClick={() => onChange(option)}
            aria-pressed={active}
            // Derived from the mode itself rather than a lookup table, so a fourth mode cannot
            // silently share a locator with a third (`test-ids` rule 7).
            data-testid={`Execution.Mode.${option}`}
            className={cn(
              'cursor-pointer rounded-[6px] px-2.5 py-1 text-[12px] capitalize',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
              // Weight and background, not colour alone — the active-state rule the rail and the
              // nav already follow.
              active ? 'bg-panel font-medium text-ink shadow-[0_1px_2px_rgb(0_0_0/40%)]' : 'text-ink-3 hover:text-ink',
            )}
          >
            {option}
          </button>
        );
      })}
    </div>
  );
}

/** `.sec h3` from `renders/tcms.css` — one spelling of a rail section heading on this screen. */
const SECTION_HEADING =
  'm-0 mb-2.5 flex items-center gap-2 text-[11px] font-semibold tracking-[.08em] text-ink-3 uppercase';

/**
 * One `.sec` of the runner's rail.
 *
 * It replaces `PageHeader`'s `Panel` on this screen only. A panel is a titled box with a border of
 * its own; a rail section is a heading and a rule, and stacking six panels 300px wide is what the
 * render's note calls *everything that is not the current step failing to get out of the way*.
 */
function RailSection({
  title,
  aside,
  testId,
  className,
  children,
}: {
  title: ReactNode;
  aside?: ReactNode;
  testId?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section className={cn('border-b border-line px-4 py-3.5', className)} data-testid={testId}>
      <h2 className={SECTION_HEADING}>
        {title}
        {aside && <span className="ml-auto font-mono tracking-normal normal-case">{aside}</span>}
      </h2>
      {children}
    </section>
  );
}

const KEYWORD = {
  GIVEN: 'given',
  WHEN: 'when',
  THEN: 'then',
  AND: 'and',
  BUT: 'but',
} as const satisfies Record<StepKeyword, ProcedureKeyword>;

const VERDICT_KINDS = ['PASSED', 'FAILED', 'BLOCKED', 'SKIPPED'] as const;

type VerdictKind = (typeof VERDICT_KINDS)[number];

/** Every status kind's badge tone. `satisfies` so a new kind in the contract is a compile error
 *  here rather than a silently grey badge. */
const BADGE_TONE = {
  PASSED: 'passed',
  FAILED: 'failed',
  BLOCKED: 'blocked',
  SKIPPED: 'skipped',
  RETEST: 'retest',
  UNTESTED: 'untested',
  WIP: 'steel',
} as const satisfies Record<StatusKind, BadgeTone>;

function badgeTone(kind: StatusKind): BadgeTone {
  return BADGE_TONE[kind];
}

/**
 * The number keys, and the one place on this screen that is a second source of key truth.
 *
 * `executionKeys.ts` is the map both the handler and the overlay read, and the reason it exists is
 * that a shortcut written twice drifts. These four are not in it: they bind a **step** rather than
 * the case verdict its four letters bind, and the file is outside 007 T12's list. They are
 * therefore printed on the marks they operate — the render's own rule, *"printed on the buttons,
 * not hidden in a help page"* — rather than in the overlay, which would claim the map holds them.
 * Folding them into `EXECUTION_SHORTCUTS` needs that file, and is recorded in the plan as the
 * follow-up it is.
 */
const STEP_VERDICT_KEYS = {
  '1': 'PASSED',
  '2': 'FAILED',
  '3': 'BLOCKED',
  '4': 'SKIPPED',
} as const satisfies Record<string, VerdictKind>;

/** The letter the overlay prints for a case verdict, read from the map rather than restated. */
function verdictKey(kind: VerdictKind): string | undefined {
  return EXECUTION_SHORTCUTS.find(
    (shortcut) => shortcut.action.kind === 'verdict' && shortcut.action.verdict === kind,
  )?.display;
}

function ExecutionForm({
  execution,
  statuses,
  projectId,
  runId,
  executionId,
  modeParam,
}: {
  execution: TestExecution;
  statuses: CaseStatus[];
  projectId: string;
  runId: string;
  executionId: string;
  /** The `?mode=` to carry across a keyboard move, or null when the mode is the default. */
  modeParam: string | null;
}) {
  const navigate = useNavigate();
  const submit = useSubmitResult(projectId, runId, executionId);

  /*
    The run, for one bit: has it ended. Normally free — the tester arrived from the run detail, so
    this key is already in the cache — and correct on a deep link, where it costs one request.

    `frozen` is FALSE while the query is pending, and that is the deliberate direction. §65's rule
    is that "not yet" is not "no" for a permission, and the mirror holds here: refusing the verdict
    buttons during a load would tell a tester on an open run that it has ended, which is a false
    statement, where the failure in the other direction is one 409 the server raises correctly and
    the page already renders. The server is the gate; this is the courtesy.
  */
  const run = useRun(projectId, runId);
  const frozen = run.data ? isRunClosed(run.data.status) : false;

  const steps = execution.steps ?? [];

  const [stepResults, setStepResults] = useState<Record<string, string | null>>(() =>
    Object.fromEntries(steps.map((step) => [step.stepId ?? '', step.statusId ?? null])),
  );
  const [stepComments, setStepComments] = useState<Record<string, string>>({});
  const [comment, setComment] = useState(execution.comment ?? '');
  const [durationSeconds, setDurationSeconds] = useState(
    durationSecondsField(execution.durationMs),
  );
  const [metPreconditions, setMetPreconditions] = useState<Record<string, boolean>>(() =>
    Object.fromEntries((execution.preconditions ?? []).map((c) => [c.id, true])),
  );
  const [blockedDialog, setBlockedDialog] = useState(false);
  const [conflict, setConflict] = useState<string | null>(null);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  /* The notes box, so `n` can put the caret in it and `Escape` can take it back out. A ref rather
     than an id lookup: two executions can be mounted at once during a route transition, and
     `getElementById` would find whichever rendered first. */
  const commentRef = useRef<HTMLTextAreaElement>(null);

  /*
    The residual §41 lists, and the screen that most deserves it.

    This form has no `formState.isDirty` — it is four `useState` buffers, not react-hook-form — so
    "dirty" is derived: a step verdict differing from what the row carried, a typed comment, or a
    precondition unticked. Deriving it rather than setting a flag in each handler is what keeps a
    fifth buffer from being added later and silently not counting.

    Why it matters more here than on the case editor: an execution is filled in *while running the
    test*. The tester has been through fifteen steps in another window and the marks on this screen
    are the only record of what happened — losing them to a deploy-skew reload means running the
    test again, not retyping a title. The pending mutation counts too: a submit in flight when the
    session recovers is work the user believes is saved.

    §41 records that lane C checked the *board* for the same treatment and found it correctly does
    not need one — its writes are immediate single-field mutations with no unsaved buffer. This
    screen is the opposite shape.
  */
  const markedSomeStep = steps.some(
    (step) => stepResults[step.stepId ?? ''] !== (step.statusId ?? null),
  );
  const typedSomething =
    comment !== (execution.comment ?? '') ||
    durationSeconds !== durationSecondsField(execution.durationMs) ||
    Object.values(stepComments).some((value) => value.trim() !== '');
  const untickedAPrecondition = (execution.preconditions ?? []).some((c) => !metPreconditions[c.id]);

  useUnsavedWork(
    markedSomeStep || typedSomething || untickedAPrecondition || submit.isPending,
    'a test result you have not recorded yet',
  );

  const byKind = (kind: string) => statuses.find((s) => s.kind === kind);
  const kindOfStatusId = (statusId: string | null) =>
    statusId ? (statuses.find((s) => s.id === statusId)?.kind ?? null) : null;
  const unmetPreconditions = (execution.preconditions ?? []).filter((c) => !metPreconditions[c.id]);

  const duration = parseDurationSeconds(durationSeconds);
  const durationIsNonsense = duration === undefined;

  const record = async (statusId: string | undefined, overrides?: Partial<Record<string, unknown>>) => {
    setConflict(null);
    try {
      const updated = await submit.mutateAsync({
        statusId,
        comment: comment || null,
        // Sent on EVERY save, and `?? null` rather than a falsy check: an empty box is "I did not
        // time this" and a typed 0 is "it was instant". Collapsing the two is how the one state the
        // server supports — clearing a duration somebody recorded by mistake — becomes unreachable.
        durationMs: duration ?? null,
        // The version we read travels as `If-Match` (the hook puts it there), so a second
        // tester recording against the same execution is rejected rather than silently
        // overwritten. This run may legitimately have two people on it — that's why the check is
        // here. It was sent in the BODY until the server existed to read it, which is exactly how
        // a divergence survives: both sides agreed, and both were wrong.
        lockVersion: execution.lockVersion,
        stepResults: Object.entries(stepResults)
          .filter(([, value]) => value !== null)
          .map(([stepId, value]) => ({
            stepId,
            statusId: value ?? undefined,
            comment: stepComments[stepId] || null,
          })),
        ...overrides,
      });
      toast(`Recorded ${updated.status?.name ?? 'result'} for ${updated.caseKey}`, {
        variant: updated.status?.countsAsPass ? 'success' : 'warning',
      });
      navigate(`/projects/${projectId}/runs/${runId}`);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setConflict(error.detail);
        return;
      }
      toast(
        error instanceof ApiError ? error.detail : "Couldn't record the result. Check your connection and try again.",
        { variant: 'error' },
      );
    }
  };

  const markedCount = steps.filter((step) => stepResults[step.stepId ?? ''] !== null).length;
  const allStepsMarked = steps.length > 0 && markedCount === steps.length;
  const anyStepFailed = steps.some(
    (step) => stepResults[step.stepId ?? ''] === byKind('FAILED')?.id,
  );

  /*
    Where the tester is: the first step with no verdict yet. It is what the number keys record, and
    it is the only line drawn at full ink — the render's second note, and the reason the steps
    ahead of it are dimmed rather than hidden. `-1` once every step is marked, which is a real
    state and not an error: there is nothing left to record but the case's own verdict.
  */
  const currentStepIndex = steps.findIndex((step) => stepResults[step.stepId ?? ''] === null);

  /*
    The failure panel's subject — the first step marked Failed, and `null` when there is none.

    The render puts this in the rail *in place of* the recording section rather than in a dialog:
    the step the failure is about stays on screen and re-readable while its description is typed,
    which is UX#55's *error near the problem*, and which a modal would have covered.
  */
  const failedStepIndex = steps.findIndex(
    (step) => kindOfStatusId(stepResults[step.stepId ?? ''] ?? null) === 'FAILED',
  );
  const failedStep = failedStepIndex >= 0 ? steps[failedStepIndex] : null;
  const failureDescription = failedStep ? (stepComments[failedStep.stepId ?? ''] ?? '') : '';
  /* A failure without a description is refused, and says so — the render's note, and §258's rule
     that a refusal path which cannot be seen is no refusal path. The sentence is rendered under
     the field it is about and announced to the keyboard, never collected into a summary. */
  const failureNeedsDescription = failedStep !== null && failureDescription.trim() === '';
  const failureRefusal =
    'A failure needs a description. This is what the next person reads, and what a defect is filed from.';

  /*
    The verdict the steps propose — a suggestion, never a decision. The rules and the argument
    for keeping it advisory live in `suggestedVerdict.ts`, with their own test; this call site
    owns only the id → kind resolution, which is the same lookup the step buttons already do.

    `statuses` is a project's status vocabulary, so an id has exactly one kind — resolving here
    rather than passing ids into the helper keeps the rules written in the vocabulary they are
    about, and means a project that renames a status changes nothing in the logic.
  */
  const suggestedKind = suggestedVerdict(
    steps.map((step) => kindOfStatusId(stepResults[step.stepId ?? ''] ?? null)),
  );

  /*
    The keyboard (§248). Everything below is an accelerator for a control that is also on screen —
    the verdict buttons, the step marks, the Previous/Next pair, the notes field, the shortcuts
    button — because a shortcut that is the ONLY route to something is not a shortcut.
  */
  const announce = useAnnounce();
  const gate = useCan(projectId);

  /*
    The permission a verdict needs depends on the row, exactly as the four buttons decide it: a
    first result is `result:submit`, amending one that is already there is `result:amend`. Asking
    the same question here rather than a simpler one is what stops the keyboard being a way past a
    gate the buttons enforce — a shortcut that 403s is worse than a disabled button, because
    nothing on screen ever said no.
  */
  const verdictPermission = execution.status ? 'result:amend' : 'result:submit';

  /* Where this case sits in the run, in the rail's order — `queueOrder.ts` owns that order and
     this reads it rather than deriving a second one, so the keyboard and the rail cannot disagree
     about what "next" means. */
  const queue = orderedQueue(run.data?.executions ?? []);
  const position = queue.findIndex((candidate) => candidate.id === executionId);

  /*
    `j` is one keystroke and this form holds unsaved work in four buffers. The `useUnsavedWork`
    header above says what losing it costs: the marks on this screen are the only record of what
    happened, so losing them means running the test again.

    So a move is REFUSED while there is unsaved work rather than confirmed — this repo has no
    shared confirm dialog, and inventing one here would be new machinery guarding one control. The
    buttons carry the reason in a `title` and the keyboard announces the same sentence. `§248 KB-9`
    asks the live pass whether that is too strict; it is a judgement no file can settle.
  */
  const unsavedWork = markedSomeStep || typedSomething || untickedAPrecondition;
  const moveRefusal = unsavedWork
    ? 'Record or clear this result first — moving on would discard what you have marked here.'
    : undefined;

  const moveBy = (direction: -1 | 1) => {
    if (position < 0) return;
    if (moveRefusal) {
      announce(moveRefusal);
      return;
    }
    const target = queue[position + direction];
    if (!target) {
      announce(direction === 1 ? 'This is the last case in the run.' : 'This is the first case in the run.');
      return;
    }
    const suffix = modeParam ? `?mode=${modeParam}` : '';
    // The arrival announcement belongs to the destination and is raised on arrival — see
    // `arrival` above. Announcing here as well was the defect: this sentence was replaced by the
    // destination's a moment later, so the progress it carries never reached anybody.
    navigate(`/projects/${projectId}/runs/${runId}/executions/${target.id}${suffix}`);
  };

  const setStepVerdict = (stepId: string, statusId: string | null) =>
    setStepResults((previous) => ({ ...previous, [stepId]: statusId }));

  /**
   * A number key: record the step the tester is on, and move to the next one.
   *
   * Refused on a frozen run for the same reason the verdict buttons are disabled there — a mark
   * that can never be saved is a control that appears to work. Every other refusal the case
   * verdict carries is absent on purpose: marking a step writes nothing, so neither the result
   * permission nor an unreadable duration has any say over it.
   */
  const markCurrentStep = (verdict: VerdictKind) => {
    if (frozen) {
      announce('This run has ended, so its results can no longer change.');
      return;
    }
    if (currentStepIndex < 0) {
      announce(
        steps.length === 0
          ? 'This version has no steps. Record the overall result instead.'
          : 'Every step is marked. Record the case result.',
      );
      return;
    }
    const status = byKind(verdict);
    if (!status) return;
    setStepVerdict(steps[currentStepIndex].stepId ?? '', status.id);
    announce(`Step ${currentStepIndex + 1} marked ${statusLabels[verdict]}.`);
  };

  /**
   * What a screen shortcut does, and every reason it may do nothing.
   *
   * The refusals are announced rather than silent. A keypress that worked and a keypress that was
   * refused look identical to somebody not watching the verdict panel, and identical to
   * *everybody* using a screen reader — which is the whole argument for the live region this
   * calls into.
   */
  const runShortcutAction = (action: ExecutionAction) => {
    if (action.kind === 'toggleHelp') {
      setShortcutsOpen((open) => !open);
      return;
    }
    if (action.kind === 'focusNotes') {
      commentRef.current?.focus();
      announce('Notes. Press Escape to leave the box.');
      return;
    }
    if (action.kind === 'leaveNotes') {
      commentRef.current?.blur();
      announce('Left the notes box. The result keys work again.');
      return;
    }
    if (action.kind === 'move') {
      moveBy(action.direction);
      return;
    }

    // A verdict, and the gates the buttons already carry, in the same order.
    if (frozen) {
      announce('This run has ended, so its results can no longer change.');
      return;
    }
    if (!gate.can(verdictPermission)) {
      announce(gate.reason(verdictPermission) ?? 'You cannot record a result here.');
      return;
    }
    if (durationIsNonsense) {
      announce('Give a number of seconds in “How long it took”, or leave it empty.');
      return;
    }
    if (submit.isPending) return;

    if (action.verdict === 'BLOCKED') {
      // Blocked is never one keystroke — it opens the checklist, in every mode. See
      // `executionKeys.ts` and this file's own header for the rule.
      setBlockedDialog(true);
      announce('Blocked. Say which precondition did not hold.');
      return;
    }
    if (failureNeedsDescription) {
      announce(failureRefusal);
      return;
    }
    const status = byKind(action.verdict);
    if (!status) return;
    announce(`Recording ${statusLabels[action.verdict]} for ${execution.caseKey}.`);
    void record(status.id);
  };

  /*
    Bound at the screen, not globally: added when this form mounts and removed when it unmounts, so
    the bindings die with the screen they belong to. `document` rather than a wrapper div because
    the tester's focus is legitimately all over this page — a step's verdict button, the evidence
    list, nothing at all — and a container listener would answer only some of those.

    Grid mode never mounts this component (`Workspace` renders the grid INSTEAD of its children),
    so Grid has no shortcuts. Deliberate, not an omission: a single-key verdict there would have to
    mean "the row I am on", and Grid has no such thing.

    **No dependency array.** The handler closes over `frozen`, the gate, the duration parse, the
    pending mutation and the queue position — all of which change between renders. A dependency
    list here would be a hand-maintained list of exactly the values whose staleness is invisible: a
    stale closure would refuse a verdict on a run that has since loaded, or allow one on a run that
    has since closed. Re-registering one listener per render is cheap and cannot be wrong.
  */
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const stepVerdict =
        !event.ctrlKey && !event.metaKey && !event.altKey && !isTypingTarget(event.target)
          ? STEP_VERDICT_KEYS[event.key as keyof typeof STEP_VERDICT_KEYS]
          : undefined;
      if (stepVerdict) {
        // Only after a match, exactly as below: an unbound key, or one typed into a box, must
        // reach the page untouched.
        event.preventDefault();
        markCurrentStep(stepVerdict);
        return;
      }
      const shortcut = matchShortcut(event);
      if (!shortcut) return;
      // Only after a match: an unbound key, or one typed into a box, must reach the page
      // untouched. Preventing first would swallow the tester's own text.
      event.preventDefault();
      runShortcutAction(shortcut.action);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  });

  return (
    <div className="flex min-w-0 flex-1 flex-col lg:flex-row">
      {/*
        THE DOCUMENT COLUMN — `renders/03-execution.html` frame A. The tester is reading a
        procedure, so it gets the case editor's own 74ch measure and the same ink: what a case
        looks like when it is written is what it looks like when it is run.
      */}
      <DocumentSurface measure="procedure" className="min-w-0 flex-1">
        {conflict && (
          <div role="alert" className="mb-4">
            <Banner tone="warn" data-testid="Execution.Conflict">
              <span className="flex w-full flex-wrap items-center gap-2">
                <span className="flex-1">{conflict}</span>
                <Button size="sm" onClick={() => window.location.reload()}>
                  Reload
                </Button>
              </span>
            </Banner>
          </div>
        )}

        {execution.status && (
          <Banner tone="steel" data-testid="Execution.AlreadyRecorded" className="mb-4">
            <span>
              A result was already recorded here — {execution.status.name} by{' '}
              {execution.executedBy?.displayName ?? 'CI'} on {formatDateTime(execution.executedAt)}.
              Saving again amends it and is kept in the audit trail.
            </span>
          </Banner>
        )}

        {/*
          The precondition checklist, drawn as the render's *Before you start* banner rather than
          as a titled panel. Unticking one is how a tester says "the environment wasn't right", and
          that is precisely the information the Blocked result needs — so the ticks stay real
          controls inside it (UX#117: what looks like a summary must not secretly be a form, and
          what is a form must look like one).
        */}
        {(execution.preconditions ?? []).length > 0 && (
          <Banner tone="neutral" data-testid="Execution.Preconditions" className="mb-5">
            <div className="min-w-0 flex-1">
            <div className="flex w-full items-center gap-2">
              <b className="text-[12.5px] font-medium text-ink">Before you start</b>
              {unmetPreconditions.length > 0 && (
                <Badge tone="blocked" data-testid="Execution.Preconditions.UnmetCount">
                  {unmetPreconditions.length} not met
                </Badge>
              )}
            </div>
            <ul className="m-0 mt-2 flex w-full list-none flex-col gap-2 p-0">
              {(execution.preconditions ?? []).map((condition) => (
                <li key={condition.id} data-testid="Execution.Preconditions.Item">
                  <div className="flex items-start gap-2">
                    <Checkbox
                      id={`Execution.Precondition.${condition.id}`}
                      checked={metPreconditions[condition.id] ?? false}
                      onCheckedChange={(checked) =>
                        setMetPreconditions((previous) => ({
                          ...previous,
                          [condition.id]: checked === true,
                        }))
                      }
                      data-testid="Execution.Preconditions.Checkbox"
                      aria-label={condition.title}
                      className="mt-0.5"
                    />
                    <label
                      htmlFor={`Execution.Precondition.${condition.id}`}
                      className="flex cursor-pointer flex-wrap items-center gap-2 text-[13px] font-medium text-ink"
                    >
                      {condition.title}
                      {condition.inherited && <Badge tone="steel">from {condition.inheritedFrom}</Badge>}
                    </label>
                  </div>
                  <ol className="m-0 mt-0.5 list-none pl-6 text-[12.5px] text-ink-4">
                    {condition.body.map((line, i) => (
                      <li key={`${condition.id}-${i}`}>{line}</li>
                    ))}
                  </ol>
                </li>
              ))}
            </ul>
            </div>
          </Banner>
        )}

        <DocumentTitle>{execution.caseTitle}</DocumentTitle>

        <div data-testid="Execution.Steps">
          {steps.length === 0 ? (
            <p className="m-0 text-[13px] text-ink-4">
              {/*
                §255 removed the other half of this sentence, which said "structured" and meant
                "the case is Gherkin, so there is nothing to tick". A case is step rows in either
                lens now, so the only version of the pinned snapshot with no rows is one written
                before the backfill reached it — and that is not something the tester can act on
                beyond recording the overall result, which is what this says.
              */}
              The version being executed has no steps — record the overall result below.
            </p>
          ) : (
            steps.map((step, index) => (
              <StepLine
                key={step.stepId ?? index}
                step={step}
                index={index}
                statuses={statuses}
                value={stepResults[step.stepId ?? ''] ?? null}
                current={index === currentStepIndex}
                frozen={frozen}
                onChange={(statusId) => setStepVerdict(step.stepId ?? '', statusId)}
                projectId={projectId}
                runId={runId}
                executionId={execution.id}
              />
            ))
          )}
        </div>

        {/*
          The render's footer strip, kept inside the document because the shell's own footer is not
          this screen's to write into. It is where the number keys are printed for a reader who has
          not yet noticed them on the current step.
        */}
        {steps.length > 0 && (
          <p className="mt-5 flex flex-wrap items-center gap-3 border-t border-line pt-3 text-[12px] text-ink-4">
            <span>
              Recorded{' '}
              <b className="font-medium text-ink-3">
                {markedCount} of {steps.length} steps
              </b>
            </span>
            <span className="ml-auto font-mono text-[11px]">1–4 verdict · J/K case · N notes · ? help</span>
          </p>
        )}
      </DocumentSurface>

      {/* THE RAIL — the render's right `.side`: record, describe, attach, assign. */}
      <aside className="w-full shrink-0 border-line bg-panel lg:w-[300px] lg:border-l">
        <RailSection
          title={failedStep ? `Failed at step ${failedStepIndex + 1}` : 'Record the result'}
          testId="Execution.Actions"
          className={failedStep ? 'bg-[color-mix(in_oklab,var(--color-failed)_6%,transparent)]' : undefined}
        >
          {frozen && (
            <Banner tone="warn" data-testid="Execution.RunClosed" className="mb-2.5">
              <span>
                This run has ended, so its results can no longer change. A test lead can reopen it
                from the run page.
              </span>
            </Banner>
          )}

          {/*
            Replaces a bare "A step is marked failed." alert. That sentence stated a fact the
            tester had just entered themselves and asked nothing of them; this names the
            verdict the marks point at and leaves every button live (see `suggestedKind`).
          */}
          {suggestedKind && !failedStep && (
            <p className="m-0 mb-2.5 text-[12px] text-ink-4" data-testid="Execution.SuggestedVerdict">
              {anyStepFailed
                ? `A step failed, so the steps point at ${statusLabels[suggestedKind]}.`
                : `Every step is marked, and they point at ${statusLabels[suggestedKind]}.`}{' '}
              Record whichever verdict is right.
            </p>
          )}

          {/* The render's `.verdicts` — four buttons in one row, each printing its own key. */}
          <div className="flex gap-[7px]">
            {VERDICT_KINDS.map((kind) => {
              const status = byKind(kind);
              if (!status) return null;
              const suggested = kind === suggestedKind;
              const lit = failedStep !== null && kind === 'FAILED';

              // Blocked goes through a dialog that requires naming the unmet precondition;
              // everything else records directly.
              const isBlocked = kind === 'BLOCKED';
              return (
                // All four verdicts ride ONE permission, but WHICH one depends on the row: a
                // first result needs `result:submit`, and changing one that is already there
                // needs `result:amend` — a separate key, held by Test Engineer since V52 and
                // still separately grantable. The server decides the same way, so the disabled
                // state and the refusal cannot disagree; gating both on `result:submit` would
                // show a caller holding only that key four enabled buttons, every one of which
                // 403s.
                //
                // Gated per button rather than around the group so each keeps its own
                // `data-testid` and its own title.
                <PermissionGate
                  key={kind}
                  projectId={projectId}
                  permission={execution.status ? 'result:amend' : 'result:submit'}
                >
                  {({ allowed }) => (
                    <button
                      type="button"
                      // `durationIsNonsense` joins the three: the duration is sent on every
                      // save, so recording with unreadable text in that box would quietly
                      // clear it rather than refuse. The field carries the sentence saying so.
                      //
                      // `failureNeedsDescription` is the fourth, and it is the render's refusal:
                      // a failed step whose description is empty cannot be recorded, and the
                      // sentence why sits under the box it is about.
                      disabled={
                        !allowed
                        || submit.isPending
                        || frozen
                        || durationIsNonsense
                        || (failureNeedsDescription && !isBlocked)
                      }
                      onClick={() => (isBlocked ? setBlockedDialog(true) : void record(status.id))}
                      data-testid={`Execution.Action.${kind}`}
                      style={
                        lit || suggested
                          ? {
                              color: `var(--color-${kind.toLowerCase()})`,
                              borderColor: `color-mix(in oklab, var(--color-${kind.toLowerCase()}) 45%, transparent)`,
                              backgroundColor: lit
                                ? `color-mix(in oklab, var(--color-${kind.toLowerCase()}) 13%, transparent)`
                                : undefined,
                            }
                          : undefined
                      }
                      className={cn(
                        'flex-1 cursor-pointer rounded-md border border-line px-1.5 py-2 text-center',
                        'text-[12.5px] font-medium text-ink-2 hover:border-line-strong hover:bg-wash-1',
                        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
                        'disabled:cursor-not-allowed disabled:opacity-45',
                        // The suggestion is a weight, not a state: a heavier border on the verdict
                        // the steps point at. Deliberately NOT a colour change, a disabled sibling
                        // or a preselection — the tester's judgement outranks the arithmetic, and
                        // every other verdict stays exactly as clickable.
                        suggested && 'border-2',
                      )}
                    >
                      {submit.isPending ? 'Saving…' : statusLabels[kind]}
                      <span className="mt-[3px] block font-mono text-[10px] text-ink-4">
                        {verdictKey(kind)}
                      </span>
                    </button>
                  )}
                </PermissionGate>
              );
            })}
          </div>

          {failedStep ? (
            <div className="mt-3">
              <Field
                label="What actually happened"
                htmlFor="Execution.FailureDescription"
                error={failureNeedsDescription ? failureRefusal : undefined}
                help="This travels with the step, not with the case, so the next reader finds it where it happened."
              >
                <Textarea
                  id="Execution.FailureDescription"
                  rows={4}
                  value={failureDescription}
                  onChange={(event) =>
                    setStepComments((previous) => ({
                      ...previous,
                      [failedStep.stepId ?? '']: event.target.value,
                    }))
                  }
                  data-testid="Execution.Steps.Row.CommentField"
                />
              </Field>
            </div>
          ) : (
            <p className="m-0 mt-2.5 text-[12px] leading-[1.55] text-ink-4">
              {allStepsMarked
                ? 'Every step is marked. Recording the case result closes it and returns to the run.'
                : 'Press 1–4 to record the step you are on. The case result below is what the run counts.'}
            </p>
          )}

          {/* Seconds here, milliseconds on the wire — `lib/format.ts` owns the conversion. */}
          <div className="mt-3">
            <Field
              label="How long it took"
              htmlFor="Execution.Duration"
              error={durationIsNonsense ? 'Give a number of seconds, or leave it empty.' : undefined}
              help="Optional. Leave it empty if you did not time this run — an empty box clears a duration that was there."
            >
              <Input
                id="Execution.Duration"
                type="number"
                min={0}
                step="any"
                value={durationSeconds}
                onChange={(event) => setDurationSeconds(event.target.value)}
                data-testid="Execution.DurationField"
              />
            </Field>
          </div>

          {/*
            The visible half of `j` and `k`. Every shortcut on this screen has one — an
            accelerator is a second route to something, never the only route to it — and this
            pair matters most: Focus mode has no queue rail at all, and Split drops the rail
            below `lg`. Without these two buttons, "next case" would be keyboard-only on the
            narrowest screen, which is the one most likely to be a tablet on a test bench.

            ~~The `Box` inside each `Tooltip` is not decoration: MUI puts `pointer-events: none` on
            a disabled button, so a tooltip wrapped directly around one greys out and explains
            nothing.~~ → **superseded by 007 T12**: the hint is a native `title` on a wrapping
            `<span>`, which a disabled button does not suppress.
          */}
          <div className="mt-3 flex gap-2">
            <span
              className="flex-1"
              title={moveRefusal ?? (position <= 0 ? 'This is the first case in the run.' : 'Previous case (K)')}
            >
              <Button
                size="sm"
                className="w-full"
                disabled={position <= 0 || !!moveRefusal}
                onClick={() => moveBy(-1)}
                data-testid="Execution.Nav.Previous"
              >
                Previous case
              </Button>
            </span>
            <span
              className="flex-1"
              title={
                moveRefusal ??
                (position < 0 || position >= queue.length - 1
                  ? 'This is the last case in the run.'
                  : 'Next case (J)')
              }
            >
              <Button
                size="sm"
                className="w-full"
                disabled={position < 0 || position >= queue.length - 1 || !!moveRefusal}
                onClick={() => moveBy(1)}
                data-testid="Execution.Nav.Next"
              >
                Next case
              </Button>
            </span>
          </div>

          <div className="mt-2 flex gap-2">
            <Button
              size="sm"
              variant="quiet"
              className="flex-1"
              onClick={() => navigate(`/projects/${projectId}/runs/${runId}`)}
              data-testid="Execution.Cancel"
            >
              Back to the run
            </Button>
            {/*
              The visible route to the help, and it is not optional: an overlay reachable only by
              a keyboard shortcut is circular help for precisely the people who do not yet know
              the shortcuts.
            */}
            <Button
              size="sm"
              variant="quiet"
              className="flex-1"
              onClick={() => setShortcutsOpen(true)}
              data-testid="Execution.Shortcuts.Open"
            >
              Shortcuts (?)
            </Button>
          </div>
        </RailSection>

        <RailSection title="Notes" testId="Execution.Notes">
          <Textarea
            aria-label="What happened"
            rows={3}
            placeholder="Anything the next person should know"
            value={comment}
            onChange={(event) => setComment(event.target.value)}
            /*
              The one binding that must fire while a text field has focus, which is why it has
              its own scope in the map and its own matcher. Handled here rather than in the
              screen listener because that listener refuses typing targets on purpose — asking
              it to make an exception would be a hole in the guard this whole feature is about.

              `stopPropagation`, so Escape here does not also close a dialog open above the form.
            */
            onKeyDown={(event) => {
              const shortcut = matchNotesShortcut(event);
              if (!shortcut) return;
              event.preventDefault();
              event.stopPropagation();
              runShortcutAction(shortcut.action);
            }}
            ref={commentRef}
            data-testid="Execution.CommentField"
          />
          <p className="m-0 mt-1 text-xs text-ink-4">
            An error message, the data you used, what you tried.
          </p>
        </RailSection>

        {/* Below Notes rather than beside the verdicts: attaching a screenshot is part of
            describing what happened, not part of choosing the verdict. */}
        <EvidencePanel
          projectId={projectId}
          runId={runId}
          executionId={execution.id}
          evidence={execution.evidence}
          frozen={frozen}
        />

        {/* Below Evidence: linking a defect is what a tester does AFTER describing the failure
            and attaching the proof of it, and it is the one control here that writes somewhere
            else's record into this one. */}
        <DefectPanel
          projectId={projectId}
          runId={runId}
          executionId={execution.id}
          defects={execution.defects}
          frozen={frozen}
          /*
            What a filed defect would say (004 Phase 3), composed here because this is the screen
            that knows it. Omitted while the run is still loading rather than filled with
            placeholders: a defect report naming an empty environment is worse than a button that
            appears a beat late.
          */
          filing={
            run.data && {
              caseKey: execution.caseKey,
              caseTitle: execution.caseTitle,
              runName: run.data.name,
              environment: run.data.configuration?.name ?? null,
              releaseVersion: execution.releaseVersion ?? run.data.releaseVersion,
              verdict: execution.status?.name ?? null,
              failedStep: firstUnpassedStep(execution, statuses),
            }
          }
        />

        {/*
          A4. A section of its own below the verdicts rather than a row inside them: it is not part
          of recording a result — a different key gates it (`run:execute`, not `result:*`), it
          writes a different column, and a control mixed into that stack would read as a fifth
          thing to do before saving.
        */}
        <RailSection title="Assignment" testId="Execution.AssignmentPanel">
          <ExecutionAssignee projectId={projectId} runId={runId} execution={execution} frozen={frozen} />
        </RailSection>
      </aside>

      <ShortcutOverlay open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} />

      <BlockedDialog
        open={blockedDialog}
        onClose={() => setBlockedDialog(false)}
        unmet={unmetPreconditions.map((c) => c.title)}
        onConfirm={(reason) => {
          setBlockedDialog(false);
          void record(byKind('BLOCKED')?.id, {
            comment: [comment, reason].filter(Boolean).join('\n\n'),
          });
        }}
      />
    </div>
  );
}

/**
 * One line of the procedure, with the verdict gutter the runner adds to it.
 *
 * The keyword and the number stay `kit/ProcedureLine`'s: the component owns the word as well as
 * the hue, and two spellings of *Given* on two screens is the drift design language rule 5 exists
 * to stop. What this adds is the 26px medallion column beside it, which is the whole difference
 * between reading a case and running one.
 */
function StepLine({
  step,
  index,
  statuses,
  value,
  current,
  frozen,
  onChange,
  projectId,
  runId,
  executionId,
}: {
  step: StepResult;
  index: number;
  statuses: CaseStatus[];
  value: string | null;
  current: boolean;
  frozen: boolean;
  onChange: (statusId: string | null) => void;
  projectId: string;
  runId: string;
  executionId: string;
}) {
  const kind = value ? (statuses.find((s) => s.id === value)?.kind ?? null) : null;
  const failed = kind === 'FAILED';

  return (
    <div
      data-testid="Execution.Steps.Row"
      style={
        current || failed
          ? { boxShadow: `inset 2px 0 0 var(--color-${failed ? 'failed' : 'steel'})` }
          : undefined
      }
      className={cn(
        'grid grid-cols-[26px_1fr] gap-3 rounded-[5px] py-[7px]',
        // The current step is the only one at full ink, and the ones ahead stay legible — a
        // tester reads ahead to know what is coming, so nothing here is hidden.
        current && '-mx-3 bg-[color-mix(in_oklab,var(--color-steel)_12%,transparent)] px-3',
        failed && 'bg-[color-mix(in_oklab,var(--color-failed)_8%,transparent)]',
        !current && !kind && 'text-ink-3',
      )}
    >
      <StepMedallion kind={kind} />

      <div className="min-w-0">
        <ProcedureLine
          line={index + 1}
          keyword={step.keyword ? KEYWORD[step.keyword] : undefined}
          keywordTestId="Execution.Steps.Row.Keyword"
          className="py-0"
        >
          <span data-testid="Execution.Steps.Row.Action">{step.action}</span>
          {/*
            The two block arguments, from the PINNED snapshot like every other authored field on
            this row. `StepDataView` is the case detail's renderer — one component, because the
            tester and the author must read the same table, and a version written before §255 has
            neither key and renders nothing.
          */}
          <StepDataView
            docString={step.docString}
            dataTable={step.dataTable}
            testId="Execution.Steps.Row.Data"
          />
          {step.testData && (
            <span className="mt-0.5 block font-mono text-[12px] text-ink-4">{step.testData}</span>
          )}
          {step.expectedResult && (
            <span className="mt-0.5 block text-[13px] text-ink-3" data-testid="Execution.Steps.Row.Expected">
              {'→ '}
              {step.expectedResult}
            </span>
          )}
        </ProcedureLine>

        {/* The marks and the evidence sit BELOW the line rather than inside it: `ProcedureLine`'s
            body is a `<span>`, and a control column nested in one is markup a browser is entitled
            to reparent. `pl-10` is the component's own 26px gutter plus its gap, so the two align. */}
        <div className="mt-1.5 pl-10">
          <div className="flex flex-wrap items-center gap-1.5">
            {VERDICT_KINDS.map((verdict) => {
              const status = statuses.find((s) => s.kind === verdict);
              if (!status) return null;
              const selected = value === status.id;
              const key = Object.entries(STEP_VERDICT_KEYS).find(([, k]) => k === verdict)?.[0];
              return (
                <button
                  key={verdict}
                  type="button"
                  // Clicking the selected result again clears it — otherwise a mis-click is
                  // unrecoverable without reloading.
                  onClick={() => onChange(selected ? null : status.id)}
                  aria-pressed={selected}
                  aria-label={`Mark step ${index + 1} as ${statusLabels[verdict]}`}
                  title={statusLabels[verdict]}
                  disabled={frozen}
                  data-testid={`Execution.Steps.Row.Mark.${verdict}`}
                  style={
                    selected
                      ? {
                          color: `var(--color-${verdict.toLowerCase()})`,
                          borderColor: `var(--color-${verdict.toLowerCase()})`,
                          backgroundColor: `color-mix(in oklab, var(--color-${verdict.toLowerCase()}) 12%, transparent)`,
                        }
                      : undefined
                  }
                  className={cn(
                    // A fixed square rather than text-sized padding: four marks per step,
                    // fifteen steps, and a ragged right edge makes the column unscannable — the
                    // tester is aiming down it, not reading it.
                    'grid h-6 w-[30px] cursor-pointer place-items-center rounded-[3px] border',
                    'border-line font-mono text-[11px] font-semibold text-ink-3',
                    'hover:border-line-strong hover:text-ink',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
                    'disabled:cursor-not-allowed disabled:opacity-45',
                  )}
                >
                  {/*
                    The initial, not `statusLabels[kind].slice(0, 4)` — which rendered "Bloc", a
                    word that is not a word, four times per step row. Derived from the shared label
                    rather than a second map (`test-ids` rule 7's reasoning applies to display
                    too): a renamed status renames its key with it. The `title` and the
                    `aria-label` carry the full label, so the letter is never the only thing
                    identifying the button.
                  */}
                  {statusLabels[verdict].charAt(0)}
                  {/* The number key, printed on the control it drives, and only on the step it
                      would act on — a digit on every row would name four keys that do nothing.
                      `aria-hidden`: the button's `aria-label` is its whole accessible name, so a
                      bare digit inside it would be read out as part of the label or not at all. */}
                  {current && key && (
                    <span aria-hidden className="ml-1 text-[9px] font-normal text-ink-4">
                      {key}
                    </span>
                  )}
                </button>
              );
            })}
            {kind && (
              <Badge tone={badgeTone(kind)} data-testid={`Execution.Step.${index + 1}.Verdict`}>
                {statusLabels[kind]}
              </Badge>
            )}
          </div>

          {step.stepId && (
            <StepEvidence
              projectId={projectId}
              runId={runId}
              executionId={executionId}
              stepId={step.stepId}
              evidence={step.evidence ?? []}
              // The SERVER's recorded verdict, never the local mark: `value` is unsaved until
              // Submit, and the attach endpoint resolves the step against `step_outcome`.
              recorded={step.statusId != null}
              frozen={frozen}
            />
          )}
        </div>
      </div>
    </div>
  );
}

/** The render's `.vm` — a tick for a pass, a cross for a failure, the status initial for the two
 *  that have no glyph, and an empty ring for a step nobody has reached. */
function StepMedallion({ kind }: { kind: StatusKind | null }) {
  const tone = kind === 'PASSED' || kind === 'FAILED' ? `var(--color-${kind.toLowerCase()})` : undefined;

  return (
    <span
      aria-hidden
      style={
        tone
          ? {
              color: tone,
              borderColor: `color-mix(in oklab, ${tone} 42%, transparent)`,
              backgroundColor: `color-mix(in oklab, ${tone} 12%, transparent)`,
            }
          : undefined
      }
      className="mt-[5px] grid size-5 place-items-center rounded-full border border-line-strong font-mono text-[10px] text-ink-4"
    >
      {kind === 'PASSED' ? (
        <Check className="size-2.5" strokeWidth={3} />
      ) : kind === 'FAILED' ? (
        <X className="size-2.5" strokeWidth={3} />
      ) : (
        kind && statusLabels[kind].charAt(0)
      )}
    </span>
  );
}

/**
 * The first step whose recorded status is not a pass — what a filed defect is about (004 Phase 3).
 *
 * Resolved through the project's status vocabulary rather than by name: a status is a project's
 * own row and only its `kind` is bounded, so matching on the word "Failed" would find nothing in a
 * project that calls it something else. `null` when nothing was recorded per step, which is
 * ordinary — a tester may record one verdict for the whole case — and the description then simply
 * omits the line rather than guessing at one.
 */
function firstUnpassedStep(execution: TestExecution, statuses: CaseStatus[]): string | null {
  const kindOf = (statusId: string | null) =>
    statusId ? (statuses.find((s) => s.id === statusId)?.kind ?? null) : null;
  const step = (execution.steps ?? []).find((s) => {
    const kind = kindOf(s.statusId ?? null);
    return kind !== null && kind !== 'PASSED' && kind !== 'UNTESTED';
  });
  return step ? `step ${step.position} — ${step.action}` : null;
}
