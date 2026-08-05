import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  FormControlLabel,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useSnackbar } from 'notistack';
import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { useUnsavedWork } from '../../auth/useSessionRecovery';
import type { CaseStatus, StepResult, TestExecution } from '../../api/types';
import { useExecution, useProject, useRun, useStatuses, useSubmitResult } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { StatusChip } from '../../components/StatusChip';
import { EvidencePanel } from './EvidencePanel';
import { formatDateTime } from '../../lib/format';
import { isRunClosed, statusLabels } from '../../lib/labels';
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
 *      precondition didn't hold.
 *   2. **The case version is pinned and displayed.** This execution records a result against
 *      the text shown here, not against whatever the case says next week.
 */
export function ExecutionPage() {
  const { projectId = '', runId = '', executionId = '' } = useParams();
  const project = useProject(projectId);
  const execution = useExecution(projectId, runId, executionId);
  const statuses = useStatuses(projectId);

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
            </>
          )
        }
      />

      <DataRegion testId="Execution.Content" state={{ ...execution, refetch: () => void execution.refetch() }}>
        {(data) => (
          <ExecutionForm
            execution={data}
            statuses={statuses.data ?? []}
            projectId={projectId}
            runId={runId}
            executionId={executionId}
          />
        )}
      </DataRegion>
    </Box>
  );
}

function ExecutionForm({
  execution,
  statuses,
  projectId,
  runId,
  executionId,
}: {
  execution: TestExecution;
  statuses: CaseStatus[];
  projectId: string;
  runId: string;
  executionId: string;
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
  const [metPreconditions, setMetPreconditions] = useState<Record<string, boolean>>(() =>
    Object.fromEntries((execution.preconditions ?? []).map((c) => [c.id, true])),
  );
  const [blockedDialog, setBlockedDialog] = useState(false);
  const [conflict, setConflict] = useState<string | null>(null);

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
    Object.values(stepComments).some((value) => value.trim() !== '');
  const untickedAPrecondition = (execution.preconditions ?? []).some((c) => !metPreconditions[c.id]);

  useUnsavedWork(
    markedSomeStep || typedSomething || untickedAPrecondition || submit.isPending,
    'a test result you have not recorded yet',
  );

  const byKind = (kind: string) => statuses.find((s) => s.kind === kind);
  const unmetPreconditions = (execution.preconditions ?? []).filter((c) => !metPreconditions[c.id]);

  const record = async (statusId: string | undefined, overrides?: Partial<Record<string, unknown>>) => {
    setConflict(null);
    try {
      const updated = await submit.mutateAsync({
        statusId,
        comment: comment || null,
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
                  />
                ))}
              </Stack>
            )}
          </Panel>

          <Panel title="Notes" testId="Execution.Notes">
            <TextField
              label="What happened"
              fullWidth
              multiline
              minRows={3}
              value={comment}
              onChange={(event) => setComment(event.target.value)}
              helperText="Anything the next reader needs — an error message, the data you used, what you tried."
              slotProps={{ htmlInput: { 'data-testid': 'Execution.CommentField' } }}
            />
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
                  // needs `result:amend`, which is lead-only (roles spec §A4 — amending is
                  // unmaking somebody else's work). The server decides the same way, so the
                  // disabled state and the refusal cannot disagree; gating both on
                  // `result:submit` would show a Test Engineer four enabled buttons that every
                  // one of them 403s.
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
                        disabled={!allowed || submit.isPending || frozen}
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

              <Button
                onClick={() => navigate(`/projects/${projectId}/runs/${runId}`)}
                data-testid="Execution.Cancel"
                sx={{ mt: 1 }}
              >
                Back to the run
              </Button>
            </Stack>
          </Panel>
        </Box>
      </Stack>

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
}: {
  step: StepResult;
  index: number;
  statuses: CaseStatus[];
  value: string | null;
  comment: string;
  onChange: (statusId: string | null) => void;
  onComment: (text: string) => void;
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

/**
 * Blocked is the one result that requires an explanation, because it is a statement about the
 * environment rather than about the software. Recording it without saying what blocked you
 * produces a row nobody can act on — and the pre-filled unmet preconditions mean the tester
 * usually doesn't have to type anything.
 */
function BlockedDialog({
  open,
  onClose,
  unmet,
  onConfirm,
}: {
  open: boolean;
  onClose: () => void;
  unmet: string[];
  onConfirm: (reason: string) => void;
}) {
  const [reason, setReason] = useState('');

  const prefilled = unmet.length
    ? `Blocked — these preconditions did not hold:\n${unmet.map((title) => `- ${title}`).join('\n')}`
    : '';
  const value = reason || prefilled;

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth data-testid="Execution.BlockedDialog">
      <DialogTitle>Record this as blocked</DialogTitle>
      <DialogContent>
        <DialogContentText sx={{ mb: 2, fontSize: '0.875rem' }}>
          Blocked means the test couldn't be run — not that the software failed. It's excluded
          from the pass rate and shown separately, so say what stopped you.
        </DialogContentText>
        <TextField
          label="What blocked this test"
          fullWidth
          multiline
          minRows={3}
          value={value}
          onChange={(event) => setReason(event.target.value)}
          slotProps={{ htmlInput: { 'data-testid': 'Execution.BlockedDialog.ReasonField' } }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="Execution.BlockedDialog.Cancel">
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={!value.trim()}
          onClick={() => onConfirm(value)}
          data-testid="Execution.BlockedDialog.Confirm"
          sx={{ bgcolor: 'var(--color-blocked)', '&:hover': { bgcolor: 'var(--color-blocked)', filter: 'brightness(0.92)' } }}
        >
          Record blocked
        </Button>
      </DialogActions>
    </Dialog>
  );
}
