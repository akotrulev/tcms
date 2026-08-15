import { Box, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from '@mui/material';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError } from '../../api/client';
import { executionQueryOptions, useSubmitResult } from '../../api/queries';
import type { CaseStatus, TestExecution, TestExecutionSummary } from '../../api/types';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import { StatusChip } from '../../components/StatusChip';
import { describeError } from '../../lib/describeError';
import { statusLabels } from '../../lib/labels';
import { CaseKeyLink, ExecuteCaseLink } from './ExecutionRowLinks';
import { orderedQueue } from './queueOrder';

/**
 * Grid mode — every case in the run, verdicts inline.
 *
 * For the pass a tester already knows the answer to: a re-run after a fix, a smoke sweep, an
 * import somebody is correcting by hand. Opening each case is the entire cost there, and this
 * removes it.
 *
 * <h3>Four rules it would be easy to lose here, and how each is kept</h3>
 *
 * **1. Blocked still routes through the precondition dialog.** It is the rule the whole execution
 * screen is built around — a tester who could not get the environment up is not looking at a
 * failing feature, and recording that as Failed poisons the pass rate and sends somebody chasing a
 * defect that does not exist. So Grid's `B` opens the same dialog the detail screen uses, with
 * that case's own preconditions in it. It is the one verdict here that is not one click, and that
 * is deliberate.
 *
 * **2. The row asks the permission its own state implies.** A first result needs `result:submit`;
 * changing one that is already recorded needs `result:amend` — a separate key (~~lead-only~~ → held
 * by Test Engineer since `V52`, and still its own key, so a caller may hold one without the other).
 * That differs *per row* — half a run may be recorded and half not — so the gate is per row rather
 * than around the table. Gating the table on `result:submit` would give a caller holding only that
 * key a screen of enabled buttons, of which every one over an already-recorded row 403s.
 *
 * The server also demands `run:execute` on this endpoint since 2026-08-07 (plan §8 #53), and this
 * gate deliberately **does not** ask for it. That is not an oversight and it is not §31's
 * disable-don't-hide rule being broken: `run:execute` is withheld from exactly one principal,
 * Automation Service, which is a token attached to a CI job and never renders a screen. Every human
 * role holding `result:submit` holds it too, so adding it here could not change what any person
 * sees — it would only add a key to a check whose answer is already fixed. If roles ever become
 * authorable (§110's F5), revisit this: a cloned role that splits the two would then be expressible
 * and this comment stops being true.
 *
 * **3. Every write carries `If-Match`.** A run may legitimately have two testers on it, which is
 * exactly why the lock exists — and bulk marking is the surface where silently overwriting
 * somebody else's result is easiest. `lockVersion` lives on the full `TestExecution` and not on
 * the summary a row carries, so a mark **reads the execution before it writes** rather than
 * dropping the header. The 409 lands on the row that caused it.
 *
 * **4. A mark must not erase the comment or the step marks.** `PATCH` overwrites `comment` with
 * whatever it is sent — an omitted comment CLEARS it, on the server and in the mock alike — so
 * the read in rule 3 supplies the existing comment back. `stepResults` is omitted, which the
 * endpoint reads as "leave them alone", so a case marked here keeps whatever step detail somebody
 * recorded in Focus mode.
 *
 * **5. The key and the title go to different places, and neither decision is made here.**
 * `ExecutionRowLinks` owns both, shared with `RunDetailPage` — which had the pair the right way
 * round while this screen had the key pointing at the execution and the title inert, so Grid mode
 * could not reach a test case at all. See that file for the accessible-name rule the pair carries.
 */

const RowTestId = 'Execution.Grid.Row';

/** The verdicts a row offers, and the key each is marked with. Skipped included — a re-run sweep
 *  is precisely where "not applicable this time" is a real answer. */
const VERDICTS = ['PASSED', 'FAILED', 'BLOCKED', 'SKIPPED'] as const;

export function ExecutionGrid({
  executions,
  statuses,
  projectId,
  runId,
  currentExecutionId,
  modeParam,
  frozen,
  onBlockedRequested,
}: {
  executions: TestExecutionSummary[];
  statuses: CaseStatus[];
  projectId: string;
  runId: string;
  currentExecutionId: string;
  modeParam: string | null;
  frozen: boolean;
  /** Blocked needs the precondition dialog, which the page owns — see rule 1. */
  onBlockedRequested: (execution: TestExecutionSummary) => void;
}) {
  const ordered = orderedQueue(executions);
  const suffix = modeParam ? `?mode=${modeParam}` : '';

  return (
    <Box sx={{ flex: 1, minWidth: 0, p: { xs: 2, md: 3 } }}>
      <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 1.5 }}>
        For a pass whose answers you already know. Blocked still asks which precondition failed, so
        it opens the checklist rather than recording in one click.
      </Typography>

      <Box sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, bgcolor: 'background.paper' }}>
        <ScrollX>
          <Table size="small" data-testid="Execution.Grid">
            <TableHead>
              <TableRow>
                <TableCell sx={{ color: 'text.secondary', width: 104 }}>Key</TableCell>
                <TableCell sx={{ color: 'text.secondary' }}>Case</TableCell>
                <TableCell sx={{ color: 'text.secondary', width: 120 }}>Result</TableCell>
                <TableCell sx={{ color: 'text.secondary', width: 190 }}>Record</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {ordered.map((execution) => (
                <GridRow
                  key={execution.id}
                  execution={execution}
                  statuses={statuses}
                  projectId={projectId}
                  runId={runId}
                  current={execution.id === currentExecutionId}
                  linkSuffix={suffix}
                  frozen={frozen}
                  onBlockedRequested={onBlockedRequested}
                />
              ))}
            </TableBody>
          </Table>
        </ScrollX>
      </Box>
    </Box>
  );
}

/**
 * One row, and a component rather than a `map` body because it calls hooks — `useSubmitResult` is
 * bound to one execution id at construction. Same reason `StepActionField` exists in the case
 * editor: a hook cannot be called inside a map.
 */
function GridRow({
  execution,
  statuses,
  projectId,
  runId,
  current,
  linkSuffix,
  frozen,
  onBlockedRequested,
}: {
  execution: TestExecutionSummary;
  statuses: CaseStatus[];
  projectId: string;
  runId: string;
  current: boolean;
  linkSuffix: string;
  frozen: boolean;
  onBlockedRequested: (execution: TestExecutionSummary) => void;
}) {
  const client = useQueryClient();
  const submit = useSubmitResult(projectId, runId, execution.id);
  const [error, setError] = useState<string | null>(null);

  const mark = async (statusId: string) => {
    setError(null);
    try {
      /*
        Read, then write. `fetchQuery` rather than a bare `api.get`: it serves the cached execution
        when the tester has already opened this case, writes the answer into the same key the
        detail screen reads, and de-duplicates two fast clicks on one row into one request.
      */
      const detail: TestExecution = await client.fetchQuery(
        executionQueryOptions(projectId, runId, execution.id),
      );
      await submit.mutateAsync({
        statusId,
        // The existing comment, sent back deliberately — see rule 4. `stepResults` is omitted so
        // whatever was marked step-by-step in Focus mode survives a verdict recorded here.
        comment: detail.comment,
        lockVersion: detail.lockVersion,
      });
    } catch (failure) {
      // In place, on the row. `useSubmitResult` marks its errors handled, and a 409 — "someone
      // else recorded a result first" — is a sentence about one row that a corner toast would
      // detach from the row it is about.
      setError(
        failure instanceof ApiError ? failure.detail : describeError(failure).body,
      );
    }
  };

  const recorded = !!execution.status;

  return (
    <TableRow
      data-testid={RowTestId}
      hover
      sx={{ bgcolor: current ? 'action.selected' : 'transparent' }}
    >
      <TableCell>
        {/* The key goes to the CASE and the title to the EXECUTION — both decided in
            `ExecutionRowLinks`, which is also why Grid mode can reach a test case at all now. */}
        <CaseKeyLink execution={execution} projectId={projectId} testId={RowTestId} />
      </TableCell>
      <TableCell>
        <ExecuteCaseLink
          execution={execution}
          projectId={projectId}
          runId={runId}
          linkSuffix={linkSuffix}
          maxWidth={480}
          testId={RowTestId}
        />
        {error && (
          <Typography
            variant="caption"
            sx={{ color: 'var(--color-failed)', display: 'block' }}
            data-testid={`${RowTestId}.Error`}
            role="alert"
          >
            {error}
          </Typography>
        )}
      </TableCell>
      <TableCell>
        {execution.status?.kind ? (
          <StatusChip kind={execution.status.kind} testId={`${RowTestId}.Status`} />
        ) : (
          <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid={`${RowTestId}.Status`}>
            {statusLabels.UNTESTED}
          </Typography>
        )}
      </TableCell>
      <TableCell>
        {/* Rule 2: the permission the ROW's state implies, resolved per row. */}
        <PermissionGate projectId={projectId} permission={recorded ? 'result:amend' : 'result:submit'}>
          {({ allowed }) => (
            <Stack direction="row" sx={{ gap: 0.5 }}>
              {VERDICTS.map((kind) => {
                const status = statuses.find((s) => s.kind === kind);
                if (!status) return null;
                const selected = execution.status?.kind === kind;
                const disabled = !allowed || submit.isPending || frozen;
                const reason = frozen
                  ? 'This run has ended, so its results can no longer change.'
                  : !allowed
                    ? recorded
                      ? 'Changing a result somebody already recorded needs the amend permission.'
                      : 'You do not have permission to record results in this run.'
                    : kind === 'BLOCKED'
                      ? 'Blocked — opens the precondition checklist'
                      : statusLabels[kind];

                return (
                  <Tooltip key={kind} title={reason}>
                    {/* A disabled button fires no events, so the tooltip needs the wrapper. */}
                    <Box component="span" sx={{ display: 'inline-flex' }}>
                      <Box
                        component="button"
                        type="button"
                        disabled={disabled}
                        aria-pressed={selected}
                        aria-label={`Record ${execution.caseKey} as ${statusLabels[kind]}`}
                        data-testid={`${RowTestId}.Mark.${kind}`}
                        onClick={() =>
                          // Rule 1: Blocked goes to the dialog, never straight to the wire.
                          kind === 'BLOCKED' ? onBlockedRequested(execution) : void mark(status.id)
                        }
                        sx={{
                          font: 'inherit',
                          fontFamily: 'var(--font-mono)',
                          fontSize: '0.6875rem',
                          fontWeight: 600,
                          width: 30,
                          height: 24,
                          display: 'grid',
                          placeItems: 'center',
                          borderRadius: 0.5,
                          border: '1px solid',
                          cursor: disabled ? 'not-allowed' : 'pointer',
                          opacity: disabled ? 0.5 : 1,
                          borderColor: selected ? `var(--color-${kind.toLowerCase()})` : 'divider',
                          color: selected ? `var(--color-${kind.toLowerCase()})` : 'text.secondary',
                          bgcolor: selected
                            ? `color-mix(in srgb, var(--color-${kind.toLowerCase()}) 12%, transparent)`
                            : 'transparent',
                        }}
                      >
                        {statusLabels[kind].charAt(0)}
                      </Box>
                    </Box>
                  </Tooltip>
                );
              })}
            </Stack>
          )}
        </PermissionGate>
      </TableCell>
    </TableRow>
  );
}
