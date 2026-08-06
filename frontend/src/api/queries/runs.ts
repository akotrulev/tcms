/** Runs, the executions inside them, and the one write that records a result. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { useRef } from 'react';
import { api, newIntentKey, qs } from '../client';
import type {
  Evidence,
  TestExecution,
  TestRun,
  TestRunDetail,
  TestRunInput as TestRunInputShape,
} from '../types';
import { base, keys } from './keys';

export function useRuns(projectId: string, planId?: string): UseQueryResult<TestRun[]> {
  return useQuery({
    queryKey: keys.runs(projectId, planId),
    queryFn: ({ signal }) => api.get<TestRun[]>(`${base(projectId)}/runs${qs({ planId })}`, { signal }),
  });
}

export function useRun(projectId: string, runId: string | undefined): UseQueryResult<TestRunDetail> {
  return useQuery({
    queryKey: keys.run(projectId, runId ?? ''),
    queryFn: ({ signal }) => api.get<TestRunDetail>(`${base(projectId)}/runs/${runId}`, { signal }),
    enabled: !!runId,
  });
}

/**
 * The body of `POST /runs` — the contract's `TestRunInput`, generated.
 *
 * ~~Hand-written rather than `S['TestRunInput']`, which predates this slice and is a divergence
 * risk worth naming: nothing mechanically checks it against the contract, so a property added to
 * the spec is invisible here until somebody notices. Left as it is (a minimal change), recorded as
 * a follow-up in the handoff.~~
 *
 * → **closed in §99, by the risk it named happening.** Two properties were added to the spec, the
 * generated type grew them, and this one did not — so the dialog sending `suiteIds` was a type
 * error against a hand-written copy that was simply out of date. It is the whole of the recorded
 * risk, arriving the first time the shape changed after the note was written. The lead-override
 * paragraph the old declaration carried is on the schema itself, where `gen:api` copies it here.
 *
 * **One property is corrected rather than taken as generated, and the correction is the interesting
 * part.** `openapi-typescript` renders a property carrying a `default` as **required** — true of a
 * *response*, where the server has already applied the default, and false of a *request body*,
 * which is the only thing this type is ever used as. Taken literally it would force every caller to
 * send `overrideApprovalGate: false`, and §82's whole point is that an ordinary create is
 * byte-identical to what callers sent before the override existed. Narrowed here, once, with the
 * reason — the alternative is dropping `default:` from the contract, which would delete a true
 * statement about the server to satisfy a generator.
 */
export type TestRunInput = Omit<TestRunInputShape, 'overrideApprovalGate'> & {
  overrideApprovalGate?: boolean;
};

/**
 * Creates a run — **and, server-side, its whole checklist**: one execution per case in scope,
 * each pinned to that case's current version.
 *
 * That is why the `Idempotency-Key` matters more here than on any other create in the product. A
 * retry that fell through would not merely make a second row; it would make a second checklist,
 * and the same tester's results would end up split across two runs that each report a pass rate
 * over a denominator nobody intended. One key per mounted dialog, per the intent-not-attempt rule.
 *
 * The run list *and* the plans list are both invalidated: a plan's case count is unchanged by a
 * run, but its statistics are not, and a plan page open in another tab is the one place a stale
 * "0 runs" is read as fact.
 */
export function useCreateRun(projectId: string) {
  const client = useQueryClient();
  const intentKey = useRef(newIntentKey());
  return useMutation({
    mutationFn: (input: TestRunInput) =>
      api.post<TestRun>(`${base(projectId)}/runs`, input, { idempotencyKey: intentKey.current }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'runs'] });
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'plans'] });
    },
  });
}

/**
 * Completes, aborts or reopens a run — `run:close`, which a Test Engineer does not hold.
 *
 * The run *list* is invalidated as well as the run itself, because the status is a column on it and
 * a "Still running" chip that survives the close is the same class of stale claim §42's live pass
 * found on the other end of this lifecycle. Plans too: a plan's statistics read its runs.
 *
 * `meta: { handled: true }` because the 409 here is a decision to hand back in place — "abort it
 * instead", "reopen it first" — and those sentences belong beside the control that raised them,
 * not in a corner toast detached from the run they are about.
 */
export function useSetRunStatus(projectId: string, runId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (status: TestRun['status']) =>
      api.put<TestRun>(`${base(projectId)}/runs/${runId}/status`, { status }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.run(projectId, runId) });
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'runs'] });
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'plans'] });
    },
  });
}

/**
 * One execution, as query options rather than only as a hook.
 *
 * Extracted in §100 for the grid execution mode, which needs an execution **imperatively**: a row
 * carries `TestExecutionSummary`, and recording a result needs two fields that live only on the
 * full `TestExecution` — `lockVersion`, which travels as `If-Match`, and `comment`, which the
 * `PATCH` overwrites with whatever it is sent (`execution.comment = body.comment ?? null` in the
 * mock, `request.comment()` on the server — **an omitted comment CLEARS it**). So the grid reads
 * the execution through `queryClient.fetchQuery` before it writes.
 *
 * Shared options rather than a second `api.get` beside the hook, because the two would drift on
 * exactly the fields that matter: the key (so a grid read warms the detail screen's cache instead
 * of racing it) and the budget.
 */
export function executionQueryOptions(projectId: string, runId: string, executionId: string) {
  return {
    queryKey: keys.execution(projectId, runId, executionId),
    queryFn: ({ signal }: { signal?: AbortSignal }) =>
      api.get<TestExecution>(`${base(projectId)}/runs/${runId}/executions/${executionId}`, { signal }),
  };
}

export function useExecution(
  projectId: string,
  runId: string | undefined,
  executionId: string | undefined,
): UseQueryResult<TestExecution> {
  return useQuery({
    ...executionQueryOptions(projectId, runId ?? '', executionId ?? ''),
    enabled: !!runId && !!executionId,
  });
}

export interface ExecutionResultInput {
  statusId?: string;
  comment?: string | null;
  stepResults?: { stepId?: string; statusId?: string; comment?: string | null }[];
}

/**
 * The instance's upload ceiling, mirroring `spring.servlet.multipart.max-file-size`.
 *
 * <p>Checked client-side as **copy, not as security** — the server is the gate and answers 413.
 * This exists so the user learns before a 10 MB upload finishes, rather than after.
 */
export const MAX_EVIDENCE_BYTES = 10 * 1024 * 1024;

export interface EvidenceUploadInput {
  file: File;
  caption?: string;
  /** Attaches to one step's result rather than to the execution as a whole. */
  stepId?: string;
}

export function useAttachEvidence(projectId: string, runId: string, executionId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ file, caption, stepId }: EvidenceUploadInput) => {
      // FormData, never a JSON body — `api.upload` is the one call that leaves Content-Type to the
      // browser so the multipart boundary survives.
      const form = new FormData();
      form.append('file', file);
      if (caption) form.append('caption', caption);
      if (stepId) form.append('stepId', stepId);
      return api.upload<Evidence>(
        `${base(projectId)}/runs/${runId}/executions/${executionId}/evidence`,
        form,
      );
    },
    onSuccess: () => {
      // Refetch rather than setQueryData: evidence lands either on the execution or on one step,
      // and the server decides which. Patching the cache here would need a second copy of that
      // rule, which is how the mock and the client came to disagree in §52.
      void client.invalidateQueries({ queryKey: keys.execution(projectId, runId, executionId) });
    },
  });
}

export function useDeleteEvidence(projectId: string, runId: string, executionId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (evidenceId: string) =>
      api.del<void>(
        `${base(projectId)}/runs/${runId}/executions/${executionId}/evidence/${evidenceId}`,
      ),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.execution(projectId, runId, executionId) });
    },
  });
}

export function useSubmitResult(projectId: string, runId: string, executionId: string) {
  const client = useQueryClient();
  return useMutation({
    // `lockVersion` travels as `If-Match`, never in the body — the same correction §30 made to
    // the suite rename. This call site carried the divergence longer than any other: the SPA has
    // sent it in the body since Phase 1, and the mock read it from there, so both sides agreed and
    // both were wrong about where the contract puts it. The server built in M2 s1 reads only the
    // header, and `ExecutionResultInput` no longer has a field for the other spelling.
    mutationFn: ({ lockVersion, ...input }: ExecutionResultInput & { lockVersion?: number }) =>
      api.patch<TestExecution>(
        `${base(projectId)}/runs/${runId}/executions/${executionId}`,
        input,
        lockVersion === undefined ? undefined : { headers: { 'If-Match': String(lockVersion) } },
      ),
    // The execution screen renders its own 409 conflict panel — a concurrent edit is a decision
    // to hand back, and it needs the server's detail in place, not a generic corner message.
    meta: { handled: true },
    onSuccess: (updated) => {
      client.setQueryData(keys.execution(projectId, runId, executionId), updated);
      // The run's counts move with the result, so the run view must not keep the old ones.
      void client.invalidateQueries({ queryKey: keys.run(projectId, runId) });
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'plans'] });
    },
  });
}
