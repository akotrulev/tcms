import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  FormControlLabel,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import CenterFocusStrongOutlinedIcon from '@mui/icons-material/CenterFocusStrongOutlined';
import TableRowsOutlinedIcon from '@mui/icons-material/TableRowsOutlined';
import VerticalSplitOutlinedIcon from '@mui/icons-material/VerticalSplitOutlined';
import { useSnackbar } from 'notistack';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { useAnnounce } from '../../a11y/useAnnounce';
import { useCan } from '../../auth/useCan';
import { useUnsavedWork } from '../../auth/useSessionRecovery';
import type { CaseStatus, StepResult, TestExecution, TestExecutionSummary } from '../../api/types';
import { useExecution, useProject, useRun, useStatuses, useSubmitResult } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { DefectPanel } from './DefectPanel';
import { StepEvidence } from './StepEvidence';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
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
import { matchNotesShortcut, matchShortcut, type ExecutionAction } from './executionKeys';
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
    <Box>
      <PageHeader
        testId="Execution.Header"
        title={execution.data?.caseTitle ?? 'Execution'}
        documentTitle={execution.data ? `${execution.data.caseKey} in this run` : 'Execution'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test runs', to: `/projects/${projectId}/runs` },
          { label: 'Run', to: `/projects/${projectId}/runs/${runId}` },
          { label: execution.data?.caseKey ?? 'Execution' },
        ]}
        actions={<ModeSwitch mode={mode} onChange={setMode} />}
        meta={
          execution.data && (
            <>
              {/*
                An execution has no display key of its own (plan §6t) — it is identified by the
                case it ran and the run it belongs to. So the case key IS the subject identifier
                here, and it links to the case. Showing "TE_1401" told a tester nothing; "TC-3"
                is what they recognise and what they'd paste into a message.
              */}
              <RouterLink to={`/projects/${projectId}/cases/${execution.data.caseId}`} data-testid="Execution.CaseLink">
                <Identifier variant="strong">{execution.data.caseKey}</Identifier>
              </RouterLink>
              <Tooltip title="This execution is recorded against this version of the case. Later edits do not change it.">
                <Box>
                  <Identifier variant="muted">v{execution.data.caseVersionNo}</Identifier>
                </Box>
              </Tooltip>
              {execution.data.status?.kind && <StatusChip kind={execution.data.status.kind} />}
              {execution.data.quarantined && <QuarantineChip testId="Execution.Quarantined" />}
            </>
          )
        }
      />

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
    </Box>
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
    <Stack direction="row" sx={{ alignItems: 'flex-start', gap: 0 }}>
      {mode === 'split' && (
        <ExecutionQueue
          executions={executions}
          currentExecutionId={executionId}
          projectId={projectId}
          runId={runId}
          modeParam={modeParam}
        />
      )}
      <Box sx={{ flex: 1, minWidth: 0, pl: mode === 'split' ? { xs: 0, lg: 3 } : 0 }}>{children}</Box>
    </Stack>
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
  const { enqueueSnackbar } = useSnackbar();

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
      enqueueSnackbar(`Recorded Blocked for ${execution.caseKey}`, { variant: 'warning' });
      onClose();
    } catch (failure) {
      enqueueSnackbar(
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

/** Which layout, in the URL. Three segments rather than a menu: the set is small, fixed, and the
 *  tester switches between them often enough that a click to open a menu is a click too many. */
function ModeSwitch({ mode, onChange }: { mode: ExecutionMode; onChange: (next: ExecutionMode) => void }) {
  const icons = {
    focus: <CenterFocusStrongOutlinedIcon fontSize="small" />,
    split: <VerticalSplitOutlinedIcon fontSize="small" />,
    grid: <TableRowsOutlinedIcon fontSize="small" />,
  } satisfies Record<ExecutionMode, ReactNode>;

  const hints = {
    focus: 'Focus — one case at a time, nothing else on screen',
    split: 'Split — the run’s cases beside the one you are running',
    grid: 'Grid — every case in one table, for a pass you already know the answers to',
  } satisfies Record<ExecutionMode, string>;

  return (
    <Stack
      direction="row"
      role="group"
      aria-label="Execution layout"
      sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, overflow: 'hidden' }}
    >
      {EXECUTION_MODES.map((option) => {
        const active = option === mode;
        return (
          <Tooltip key={option} title={hints[option]}>
            <Box
              component="button"
              type="button"
              onClick={() => onChange(option)}
              aria-pressed={active}
              // Derived from the mode itself rather than a lookup table, so a fourth mode cannot
              // silently share a locator with a third (`test-ids` rule 7).
              data-testid={`Execution.Mode.${option}`}
              sx={{
                font: 'inherit',
                fontSize: '0.75rem',
                fontWeight: active ? 600 : 400,
                display: 'flex',
                alignItems: 'center',
                gap: 0.75,
                px: 1.25,
                height: 30,
                cursor: 'pointer',
                border: 'none',
                borderLeft: '1px solid',
                borderLeftColor: 'divider',
                '&:first-of-type': { borderLeft: 'none' },
                // Weight and background, not colour alone — the active-state rule the rail and
                // the nav already follow.
                bgcolor: active ? 'action.selected' : 'transparent',
                color: active ? 'primary.main' : 'text.secondary',
                textTransform: 'capitalize',
              }}
            >
              {icons[option]}
              {option}
            </Box>
          </Tooltip>
        );
      })}
    </Stack>
  );
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
  const { enqueueSnackbar } = useSnackbar();
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

  const [stepResults, setStepResults] = useState<Record<string, string | null>>(() =>
    Object.fromEntries((execution.steps ?? []).map((step) => [step.stepId ?? '', step.statusId ?? null])),
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
  const markedSomeStep = (execution.steps ?? []).some(
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
      enqueueSnackbar(`Recorded ${updated.status?.name ?? 'result'} for ${updated.caseKey}`, {
        variant: updated.status?.countsAsPass ? 'success' : 'warning',
      });
      navigate(`/projects/${projectId}/runs/${runId}`);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setConflict(error.detail);
        return;
      }
      enqueueSnackbar(
        error instanceof ApiError ? error.detail : "Couldn't record the result. Check your connection and try again.",
        { variant: 'error' },
      );
    }
  };

  const allStepsMarked =
    (execution.steps ?? []).length > 0 &&
    (execution.steps ?? []).every((step) => stepResults[step.stepId ?? ''] !== null);
  const anyStepFailed = (execution.steps ?? []).some(
    (step) => stepResults[step.stepId ?? ''] === byKind('FAILED')?.id,
  );

  /*
    The verdict the steps propose — a suggestion, never a decision. The rules and the argument
    for keeping it advisory live in `suggestedVerdict.ts`, with their own test; this call site
    owns only the id → kind resolution, which is the same lookup the step buttons already do.

    `statuses` is a project's status vocabulary, so an id has exactly one kind — resolving here
    rather than passing ids into the helper keeps the rules written in the vocabulary they are
    about, and means a project that renames a status changes nothing in the logic.
  */
  const suggestedKind = suggestedVerdict(
    (execution.steps ?? []).map((step) => {
      const statusId = stepResults[step.stepId ?? ''];
      return statuses.find((s) => s.id === statusId)?.kind ?? null;
    }),
  );

  /*
    The keyboard (§248). Everything below is an accelerator for a control that is also on screen —
    the verdict buttons, the Previous/Next pair, the notes field, the shortcuts button — because a
    shortcut that is the ONLY route to something is not a shortcut.
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
    buttons carry the reason in a tooltip (§31, disable-don't-hide) and the keyboard announces the
    same sentence. `§248 KB-9` asks the live pass whether that is too strict; it is a judgement no
    file can settle.
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
    navigate(`/projects/${projectId}/runs/${runId}/executions/${target.id}${suffix}`);
    announce(`Case ${position + 1 + direction} of ${queue.length}. ${target.caseKey}, ${target.caseTitle}.`);
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

    // A verdict, and the four gates the buttons already carry, in the same order.
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
    <Stack sx={{ gap: 3 }}>
      {conflict && (
        <Alert
          severity="warning"
          role="alert"
          data-testid="Execution.Conflict"
          action={
            <Button size="small" color="warning" onClick={() => window.location.reload()}>
              Reload
            </Button>
          }
        >
          <Typography variant="body2">{conflict}</Typography>
        </Alert>
      )}

      {execution.status && (
        <Alert severity="info" data-testid="Execution.AlreadyRecorded">
          <Typography variant="body2">
            A result was already recorded here — {execution.status.name} by{' '}
            {execution.executedBy?.displayName ?? 'CI'} on {formatDateTime(execution.executedAt)}. Saving
            again amends it and is kept in the audit trail.
          </Typography>
        </Alert>
      )}

      <Stack direction={{ xs: 'column', lg: 'row' }} sx={{ gap: 3, alignItems: 'flex-start' }}>
        <Stack sx={{ gap: 3, flex: 1, minWidth: 0, width: '100%' }}>
          {/*
            The precondition checklist. Unticking one is how a tester says "the environment
            wasn't right", and that is precisely the information the Blocked result needs.
          */}
          {(execution.preconditions ?? []).length > 0 && (
            <Panel
              title="Before you start"
              testId="Execution.Preconditions"
              actions={
                unmetPreconditions.length > 0 && (
                  <Chip
                    size="small"
                    label={`${unmetPreconditions.length} not met`}
                    data-testid="Execution.Preconditions.UnmetCount"
                    sx={{
                      bgcolor: 'transparent',
                      color: 'var(--color-blocked)',
                      border: '1px solid color-mix(in srgb, var(--color-blocked) 45%, transparent)',
                    }}
                  />
                )
              }
            >
              <Stack sx={{ gap: 1.5 }}>
                {(execution.preconditions ?? []).map((condition) => (
                  <Box key={condition.id} data-testid="Execution.Preconditions.Item">
                    <FormControlLabel
                      control={
                        <Checkbox
                          size="small"
                          checked={metPreconditions[condition.id] ?? false}
                          onChange={(event) =>
                            setMetPreconditions((previous) => ({
                              ...previous,
                              [condition.id]: event.target.checked,
                            }))
                          }
                          data-testid="Execution.Preconditions.Checkbox"
                        />
                      }
                      label={
                        <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                          <Typography variant="body2" sx={{ fontWeight: 500 }}>
                            {condition.title}
                          </Typography>
                          {condition.inherited && (
                            <Chip
                              size="small"
                              label={`from ${condition.inheritedFrom}`}
                              sx={{
                                bgcolor: 'transparent',
                                color: 'var(--color-info)',
                                border: '1px solid color-mix(in srgb, var(--color-info) 45%, transparent)',
                              }}
                            />
                          )}
                        </Stack>
                      }
                    />
                    <Stack component="ol" sx={{ m: 0, pl: 5.5, gap: 0.25 }}>
                      {condition.body.map((line, i) => (
                        <Box component="li" key={`${condition.id}-${i}`}>
                          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                            {line}
                          </Typography>
                        </Box>
                      ))}
                    </Stack>
                  </Box>
                ))}
              </Stack>
            </Panel>
          )}

          <Panel
            title="Steps"
            testId="Execution.Steps"
            actions={
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                {allStepsMarked ? 'All steps marked' : 'Mark each step as you go'}
              </Typography>
            }
          >
            {(execution.steps ?? []).length === 0 ? (
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                This case has no structured steps — record the overall result below.
              </Typography>
            ) : (
              <Stack sx={{ gap: 2 }}>
                {(execution.steps ?? []).map((step, index) => (
                  <StepRow
                    key={step.stepId ?? index}
                    step={step}
                    index={index}
                    statuses={statuses}
                    value={stepResults[step.stepId ?? ''] ?? null}
                    comment={stepComments[step.stepId ?? ''] ?? ''}
                    onChange={(statusId) =>
                      setStepResults((previous) => ({ ...previous, [step.stepId ?? '']: statusId }))
                    }
                    onComment={(text) =>
                      setStepComments((previous) => ({ ...previous, [step.stepId ?? '']: text }))
                    }
                    projectId={projectId}
                    runId={runId}
                    executionId={execution.id}
                    frozen={frozen}
                  />
                ))}
              </Stack>
            )}
          </Panel>

          <Panel title="Notes" testId="Execution.Notes">
            <Stack spacing={2}>
              <TextField
                label="What happened"
                fullWidth
                multiline
                minRows={3}
                value={comment}
                onChange={(event) => setComment(event.target.value)}
                /*
                  The one binding that must fire while a text field has focus, which is why it has
                  its own scope in the map and its own matcher. Handled here rather than in the
                  screen listener because that listener refuses typing targets on purpose — asking
                  it to make an exception would be a hole in the guard this whole feature is about.

                  `stopPropagation`, so Escape here does not also close a dialog open above the
                  form.
                */
                onKeyDown={(event) => {
                  const shortcut = matchNotesShortcut(event);
                  if (!shortcut) return;
                  event.preventDefault();
                  event.stopPropagation();
                  runShortcutAction(shortcut.action);
                }}
                helperText="Anything the next reader needs — an error message, the data you used, what you tried."
                slotProps={{ htmlInput: { 'data-testid': 'Execution.CommentField', ref: commentRef } }}
              />
              {/* Seconds here, milliseconds on the wire — `lib/format.ts` owns the conversion.
                  Beside the note rather than beside the verdict buttons: how long it took is part
                  of describing the run, and a control on the verdict row would read as something
                  the tester must fill in before they may record anything. */}
              <TextField
                label="How long it took (seconds)"
                type="number"
                sx={{ maxWidth: 260 }}
                value={durationSeconds}
                onChange={(event) => setDurationSeconds(event.target.value)}
                error={durationIsNonsense}
                helperText={
                  durationIsNonsense
                    ? 'Give a number of seconds, or leave it empty.'
                    : 'Optional. Leave it empty if you did not time this run — an empty box clears a duration that was there.'
                }
                slotProps={{
                  htmlInput: { min: 0, step: 'any', 'data-testid': 'Execution.DurationField' },
                }}
              />
            </Stack>
          </Panel>

          {/* Below Notes rather than beside the result buttons: attaching a screenshot is part of
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
          />
        </Stack>

        {/* The result actions. Sticky, because on a long case the tester is at the bottom of
            the steps when they're ready to record. */}
        <Box sx={{ width: { xs: '100%', lg: 300 }, flexShrink: 0, position: { lg: 'sticky' }, top: { lg: 68 } }}>
          <Panel title="Record the result" testId="Execution.Actions">
            <Stack sx={{ gap: 1 }}>
              {frozen && (
                <Alert severity="info" sx={{ mb: 0.5 }} data-testid="Execution.RunClosed">
                  <Typography variant="body2">
                    This run has ended, so its results can no longer change. A test lead can reopen
                    it from the run page.
                  </Typography>
                </Alert>
              )}

              {/*
                Replaces a bare "A step is marked failed." alert. That sentence stated a fact the
                tester had just entered themselves and asked nothing of them; this names the
                verdict the marks point at and leaves every button live (see `suggestedKind`).
              */}
              {suggestedKind && (
                <Typography
                  variant="caption"
                  sx={{ color: 'text.secondary', mb: 0.5 }}
                  data-testid="Execution.SuggestedVerdict"
                >
                  {anyStepFailed
                    ? `A step failed, so the steps point at ${statusLabels[suggestedKind]}.`
                    : `Every step is marked, and they point at ${statusLabels[suggestedKind]}.`}{' '}
                  Record whichever verdict is right.
                </Typography>
              )}

              {(['PASSED', 'FAILED', 'BLOCKED', 'SKIPPED'] as const).map((kind) => {
                const status = byKind(kind);
                if (!status) return null;
                const suggested = kind === suggestedKind;

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
                  // `data-testid` and its own tooltip anchor.
                  <PermissionGate
                    key={kind}
                    projectId={projectId}
                    permission={execution.status ? 'result:amend' : 'result:submit'}
                  >
                    {({ allowed }) => (
                      <Button
                        variant={kind === 'PASSED' ? 'contained' : 'outlined'}
                        fullWidth
                        // `durationIsNonsense` joins the three: the duration is sent on every
                        // save, so recording with unreadable text in that box would quietly
                        // clear it rather than refuse. The field carries the sentence saying so.
                        disabled={!allowed || submit.isPending || frozen || durationIsNonsense}
                        onClick={() => (isBlocked ? setBlockedDialog(true) : void record(status.id))}
                        data-testid={`Execution.Action.${kind}`}
                        sx={{
                          justifyContent: 'flex-start',
                          ...(kind === 'PASSED'
                            ? { bgcolor: 'var(--color-passed)', '&:hover': { bgcolor: 'var(--color-passed)', filter: 'brightness(0.92)' } }
                            : {
                                color: `var(--color-${kind.toLowerCase()})`,
                                borderColor: `color-mix(in srgb, var(--color-${kind.toLowerCase()}) 45%, transparent)`,
                              }),
                          // The suggestion is a weight, not a state: a thicker border on the
                          // verdict the steps point at. Deliberately NOT a colour change, a
                          // disabled sibling or a preselection — the tester's judgement outranks
                          // the arithmetic, and every other verdict stays exactly as clickable.
                          ...(suggested && {
                            borderWidth: 2,
                            borderStyle: 'solid',
                            borderColor: `var(--color-${kind.toLowerCase()})`,
                          }),
                        }}
                      >
                        {submit.isPending ? 'Saving…' : statusLabels[kind]}
                        {suggested && (
                          <Typography
                            component="span"
                            variant="caption"
                            sx={{ ml: 'auto', pl: 1, opacity: 0.75, textTransform: 'none' }}
                          >
                            suggested
                          </Typography>
                        )}
                      </Button>
                    )}
                  </PermissionGate>
                );
              })}

              {/*
                The visible half of `j` and `k`. Every shortcut on this screen has one — an
                accelerator is a second route to something, never the only route to it — and this
                pair matters most: Focus mode has no queue rail at all, and Split drops the rail
                below `lg`. Without these two buttons, "next case" would be keyboard-only on the
                narrowest screen, which is the one most likely to be a tablet on a test bench.

                The `Box` inside each `Tooltip` is not decoration: MUI puts `pointer-events: none`
                on a disabled button, so a tooltip wrapped directly around one greys out and
                explains nothing — the trap `PermissionGate.tsx` documents and solves the same way.
              */}
              <Stack direction="row" sx={{ gap: 1, mt: 1 }}>
                <Tooltip title={moveRefusal ?? (position <= 0 ? 'This is the first case in the run.' : 'Previous case (K)')}>
                  <Box sx={{ flex: 1 }}>
                    <Button
                      fullWidth
                      size="small"
                      disabled={position <= 0 || !!moveRefusal}
                      onClick={() => moveBy(-1)}
                      data-testid="Execution.Nav.Previous"
                    >
                      Previous case
                    </Button>
                  </Box>
                </Tooltip>
                <Tooltip
                  title={
                    moveRefusal ??
                    (position < 0 || position >= queue.length - 1
                      ? 'This is the last case in the run.'
                      : 'Next case (J)')
                  }
                >
                  <Box sx={{ flex: 1 }}>
                    <Button
                      fullWidth
                      size="small"
                      disabled={position < 0 || position >= queue.length - 1 || !!moveRefusal}
                      onClick={() => moveBy(1)}
                      data-testid="Execution.Nav.Next"
                    >
                      Next case
                    </Button>
                  </Box>
                </Tooltip>
              </Stack>

              <Button
                onClick={() => navigate(`/projects/${projectId}/runs/${runId}`)}
                data-testid="Execution.Cancel"
                sx={{ mt: 1 }}
              >
                Back to the run
              </Button>

              {/*
                The visible route to the help, and it is not optional: an overlay reachable only by
                a keyboard shortcut is circular help for precisely the people who do not yet know
                the shortcuts.
              */}
              <Button
                size="small"
                onClick={() => setShortcutsOpen(true)}
                data-testid="Execution.Shortcuts.Open"
                sx={{ mt: 0.5 }}
              >
                Keyboard shortcuts (?)
              </Button>
            </Stack>
          </Panel>

          {/*
            A4. A panel of its own below the verdicts rather than a row inside them: it is not part
            of recording a result — a different key gates it (`run:execute`, not `result:*`), it
            writes a different column, and a control mixed into that stack would read as a fifth
            thing to do before saving.
          */}
          <Box sx={{ mt: 3 }}>
            <Panel title="Assignment" testId="Execution.AssignmentPanel">
              <ExecutionAssignee
                projectId={projectId}
                runId={runId}
                execution={execution}
                frozen={frozen}
              />
            </Panel>
          </Box>
        </Box>
      </Stack>

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
    </Stack>
  );
}

function StepRow({
  step,
  index,
  statuses,
  value,
  comment,
  onChange,
  onComment,
  projectId,
  runId,
  executionId,
  frozen,
}: {
  step: StepResult;
  index: number;
  statuses: CaseStatus[];
  value: string | null;
  comment: string;
  onChange: (statusId: string | null) => void;
  onComment: (text: string) => void;
  projectId: string;
  runId: string;
  executionId: string;
  frozen: boolean;
}) {
  const failedId = statuses.find((s) => s.kind === 'FAILED')?.id;
  const isFailed = value === failedId;

  return (
    <Box
      data-testid="Execution.Steps.Row"
      sx={{
        p: 1.5,
        border: '1px solid',
        borderColor: isFailed ? 'color-mix(in srgb, var(--color-failed) 45%, transparent)' : 'divider',
        borderRadius: 1,
      }}
    >
      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ gap: 2 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1, mb: 0.5 }}>
            <Identifier variant="muted">{index + 1}</Identifier>
            {step.keyword && (
              <Typography variant="caption" sx={{ color: 'primary.main', fontWeight: 600 }}>
                {step.keyword}
              </Typography>
            )}
            <Typography variant="body2" sx={{ fontWeight: 500 }} data-testid="Execution.Steps.Row.Action">
              {step.action}
            </Typography>
          </Stack>
          {step.testData && (
            <Typography variant="body2" sx={{ color: 'text.secondary', pl: 3 }}>
              Data: {step.testData}
            </Typography>
          )}
          {step.expectedResult && (
            <Typography variant="body2" sx={{ pl: 3, mt: 0.5 }} data-testid="Execution.Steps.Row.Expected">
              Expected: {step.expectedResult}
            </Typography>
          )}
          {isFailed && (
            <TextField
              label="What actually happened"
              fullWidth
              multiline
              size="small"
              value={comment}
              onChange={(event) => onComment(event.target.value)}
              sx={{ mt: 1.5 }}
              slotProps={{ htmlInput: { 'data-testid': 'Execution.Steps.Row.CommentField' } }}
            />
          )}
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
        </Box>

        <Stack direction="row" sx={{ gap: 0.5, flexShrink: 0, alignItems: 'flex-start' }}>
          {(['PASSED', 'FAILED', 'BLOCKED', 'SKIPPED'] as const).map((kind) => {
            const status = statuses.find((s) => s.kind === kind);
            if (!status) return null;
            const selected = value === status.id;
            return (
              <Tooltip key={kind} title={statusLabels[kind]}>
                <Box
                  component="button"
                  type="button"
                  // Clicking the selected result again clears it — otherwise a mis-click is
                  // unrecoverable without reloading.
                  onClick={() => onChange(selected ? null : status.id)}
                  aria-pressed={selected}
                  aria-label={`Mark step ${index + 1} as ${statusLabels[kind]}`}
                  data-testid={`Execution.Steps.Row.Mark.${kind}`}
                  sx={{
                    // A fixed square rather than text-sized padding: four marks per step row,
                    // fifteen rows, and a ragged right edge makes the column unscannable — the
                    // tester is aiming down it, not reading it.
                    font: 'inherit',
                    fontFamily: 'var(--font-mono)',
                    fontSize: '0.6875rem',
                    fontWeight: 600,
                    width: 30,
                    height: 24,
                    display: 'grid',
                    placeItems: 'center',
                    cursor: 'pointer',
                    borderRadius: 0.5,
                    border: '1px solid',
                    borderColor: selected
                      ? `var(--color-${kind.toLowerCase()})`
                      : 'divider',
                    color: selected ? `var(--color-${kind.toLowerCase()})` : 'text.secondary',
                    bgcolor: selected
                      ? `color-mix(in srgb, var(--color-${kind.toLowerCase()}) 12%, transparent)`
                      : 'transparent',
                  }}
                >
                  {/*
                    The initial, not `statusLabels[kind].slice(0, 4)` — which rendered "Bloc",
                    a word that is not a word, four times per step row.

                    Derived from the shared label rather than a second map (`test-ids` rule 7's
                    reasoning applies to display too): a renamed status renames its key with it.
                    The four kinds are the literal tuple below, and their initials — P F B S — are
                    distinct; the tooltip and the `aria-label` above carry the full label, so the
                    letter is never the only thing identifying the button.
                  */}
                  {statusLabels[kind].charAt(0)}
                </Box>
              </Tooltip>
            );
          })}
        </Stack>
      </Stack>
    </Box>
  );
}
