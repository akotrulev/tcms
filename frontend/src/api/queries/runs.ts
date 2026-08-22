/** Runs, the executions inside them, and the one write that records a result. */
import { useMutation, useQueries, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { useRef } from 'react';
import { api, newIntentKey, qs } from '../client';
import type {
  Configuration,
  DefectLink,
  Evidence,
  ExecutionBulkOperation,
  ExecutionBulkResult,
  TestExecution,
  TestRun,
  TestRunCloneInput,
  TestRunDetail,
  TestRunInput as TestRunInputShape,
} from '../types';
import { base, keys } from './keys';

/**
 * The project's environments — `GET /configurations`, the read a run form needed and never had.
 *
 * The list existed on `TestPlanDetail` and nowhere else, so a run scoped by suites or tags rather
 * than by a plan (§99) was created against a configuration nobody could name. `RunCloneDialog`'s
 * header recorded the consequence as *"there is no configuration picker anywhere in this SPA"*.
 *
 * **`enabled` rather than an unconditional fetch**, and `staleTime` matching the other per-project
 * vocabularies — `useProjectMembers`'s reasoning exactly: the only consumers are two dialogs, and
 * the environment matrix changes on the timescale of a new browser, not of a page view.
 *
 * Never empty: every project is seeded with a `Default`, which is why `configurationId` is
 * optional on the wire while the column is `NOT NULL`.
 */
export function useConfigurations(
  projectId: string,
  enabled: boolean,
): UseQueryResult<Configuration[]> {
  return useQuery({
    queryKey: keys.configurations(projectId),
    queryFn: ({ signal }) =>
      api.get<Configuration[]>(`${base(projectId)}/configurations`, { signal }),
    enabled: enabled && !!projectId,
    staleTime: 5 * 60_000,
  });
}

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
 * Clones a run (§129) — a second checklist over the **same, already-resolved** case set.
 *
 * The `Idempotency-Key` argument above applies here in full and then some: a fallen-through retry
 * would materialise a second checklist over the same cases, and the tester's results would split
 * across two runs each reporting a pass rate over a denominator nobody intended.
 *
 * Unlike `useCloneCase` this takes no long timeout — nothing is copied byte for byte, and a run's
 * results, evidence and defect links are not reachable from a create at all.
 *
 * `overrideApprovalGate` is generated as `boolean | null` and optional, so no correction of the
 * generator's `default` handling is needed here (see `TestRunInput` above for the case where it
 * was): an ordinary clone simply omits it.
 */
export function useCloneRun(projectId: string, runId: string) {
  const client = useQueryClient();
  const intentKey = useRef(newIntentKey());
  return useMutation({
    mutationFn: (input: TestRunCloneInput) =>
      api.post<TestRun>(`${base(projectId)}/runs/${runId}/clone`, input, {
        idempotencyKey: intentKey.current,
      }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'runs'] });
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'plans'] });
    },
  });
}

/**
 * Completes, aborts or reopens a run — `run:close`, ~~which a Test Engineer does not hold~~ → held
 * by every contributor since `V52` (§210), and still a key of its own.
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

/**
 * The maximum number of executions the failure summary will open to read step detail.
 *
 * **The bound is on the READS, not on the answer.** The case-level half of the summary — which
 * cases failed, which were blocked — is complete at every run size and costs nothing: it is
 * already in the `TestRunDetail` the page has. Only the *step* half needs a request per case,
 * because `steps` lives on `TestExecution` and the contract publishes no step rollup at run
 * level (see the handoff for why that was not fixed by widening the contract in this slice).
 *
 * 25 is a judgement, and the number matters less than the fact that the screen **says when it
 * has been reached**. A run with 300 failures is a run whose first question is not "which step",
 * and firing 300 parallel reads to answer a question nobody asked would take the page down on
 * exactly the runs that are already going badly.
 */
export const STEP_DETAIL_LIMIT = 25;

/** One execution's step detail, as the failure summary reads it. */
export interface ExecutionSteps {
  executionId: string;
  steps: TestExecution['steps'];
}

export interface ExecutionStepsState {
  data: ExecutionSteps[] | undefined;
  isPending: boolean;
  isError: boolean;
  error: unknown;
  isFetching: boolean;
  dataUpdatedAt: number | undefined;
  refetch: () => void;
}

/**
 * Step detail for a set of executions — one read each, combined into one region state.
 *
 * The second `useQueries` in this codebase after §103's `useReferencedCases`, and combined the
 * same pessimistic way for the same reason: the reader's question is about the *run*, so a
 * half-arrived answer rendered as a list is a set of findings that is wrong and looks complete,
 * and a partial failure is a **shorter** list with nothing on screen saying so.
 *
 * The keys are `keys.execution(...)` — the same ones the execution screen reads and the grid
 * writes — so opening a case warms this panel, marking a result moves it, and no second copy of
 * an execution can exist in the cache.
 *
 * `dataUpdatedAt` is the **oldest** arm: a combined answer is only as current as its laggard.
 */
export function useExecutionSteps(
  projectId: string,
  runId: string,
  executionIds: string[],
): ExecutionStepsState {
  return useQueries({
    queries: executionIds.map((executionId) => executionQueryOptions(projectId, runId, executionId)),
    combine: (results): ExecutionStepsState => {
      const failed = results.find((result) => result.isError);
      const loaded = results.every((result) => result.data !== undefined);
      const oldest = results.reduce(
        (soFar, result) => Math.min(soFar, result.dataUpdatedAt || Infinity),
        Infinity,
      );
      return {
        data: loaded
          ? results.map((result, index) => ({
              executionId: executionIds[index],
              steps: result.data?.steps ?? [],
            }))
          : undefined,
        // `useQueries` over an EMPTY list answers `[]`, and `[].some()` is false — so an empty
        // set is "loaded with nothing", never "pending for ever". That is the state a run with
        // no failures is in, and it is the common one.
        isPending: results.some((result) => result.isPending),
        isError: failed !== undefined,
        error: failed?.error,
        isFetching: results.some((result) => result.isFetching),
        dataUpdatedAt: Number.isFinite(oldest) ? oldest : undefined,
        refetch: () => results.forEach((result) => void result.refetch()),
      };
    },
  });
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
  /**
   * Milliseconds, and sent on **every** save — the same total-state rule `comment` carries. Omitting
   * it when the tester left the box empty would make the field un-clearable from the UI at all,
   * which is the one state the server explicitly supports.
   */
  durationMs?: number | null;
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

export interface DefectLinkInput {
  externalKey: string;
  url: string;
}

/**
 * Links this result to a defect in somebody else's tracker.
 *
 * <p>No `provider` field: the server writes `EXTERNAL` and refuses to take a caller's word for a
 * tracker it cannot reach (`V62`). Both the run detail and the case history render these links, so
 * both are invalidated — the execution alone would leave the run's table showing the old set.
 */
export function useLinkDefect(projectId: string, runId: string, executionId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: DefectLinkInput) =>
      api.post<DefectLink>(
        `${base(projectId)}/runs/${runId}/executions/${executionId}/defects`,
        input,
      ),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.run(projectId, runId) });
    },
  });
}

export function useUnlinkDefect(projectId: string, runId: string, executionId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (linkId: string) =>
      api.del<void>(
        `${base(projectId)}/runs/${runId}/executions/${executionId}/defects/${linkId}`,
      ),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.run(projectId, runId) });
    },
  });
}

/**
 * Assigns one execution — or unassigns it with `null`.
 *
 * **`assignee_id`'s second writer, and the SPA's first.** The column has been rendered on the run
 * detail and the execution queue since M2 and the only thing that could set it was `POST /runs`'s
 * whole-checklist `assigneeId`, which had no control on the create dialog either — so in the
 * shipped product every execution was permanently unassigned.
 *
 * **No `If-Match`**, unlike `useSubmitResult` beside it: the server takes none, because a
 * reassignment refused over a verdict somebody recorded in between is a conflict about a field
 * neither call touches. The response still carries a bumped `lockVersion`, and writing it into the
 * execution's cache is what keeps the runner's next save from 409ing against this write.
 *
 * The run is invalidated as well as the execution: `TestRunDetail` carries every row's assignee, so
 * a queue left showing "unassigned" beside a case somebody just took is the stale-claim class §42
 * found on the other end of this screen.
 */
export function useAssignExecution(projectId: string, runId: string, executionId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (assigneeId: string | null) =>
      api.put<TestExecution>(
        `${base(projectId)}/runs/${runId}/executions/${executionId}/assignee`,
        { assigneeId },
      ),
    // The 409 here is "the run has ended", which is a decision to hand back beside the control that
    // raised it — the same call `useSetRunStatus` makes about its own refusals.
    meta: { handled: true },
    onSuccess: (updated) => {
      client.setQueryData(keys.execution(projectId, runId, executionId), updated);
      void client.invalidateQueries({ queryKey: keys.run(projectId, runId) });
    },
  });
}

/**
 * Assigns many rows of a run's checklist in one act — one person, or spread across several.
 *
 * **Two calls per act, and the first one writes nothing.** `dryRun` defaults to true on the server,
 * so the dialog previews, shows the split, and only then commits. Sending the commit without the
 * preview is possible and is exactly what the preview exists to prevent (plan §6f).
 *
 * **No `If-Match` and no `Idempotency-Key`.** The first for `useAssignExecution`'s reason — a
 * reassignment refused over a verdict somebody recorded in between is a conflict about a field
 * neither call touches. The second because there is nothing to key: the operation creates no
 * resource, and its split is a pure function of the run's row order and the assignee order, so a
 * retry lands every row where it already is and reports them all as `unchanged`.
 *
 * The run is invalidated rather than patched: `TestRunDetail` carries every row's assignee, the
 * response carries changes rather than executions, and up to 500 rows moved — a hand-merged cache
 * here is the stale-claim class §42 found on the other end of this screen.
 */
export function useBulkAssignExecutions(projectId: string, runId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ExecutionBulkOperation) =>
      api.post<ExecutionBulkResult>(`${base(projectId)}/runs/${runId}/executions/bulk`, body),
    // The dialog lands 400s on their own controls and renders the 409 in place, the same call
    // `useAssignExecution` makes about its own refusals.
    meta: { handled: true },
    onSuccess: (result) => {
      // A preview changed nothing, so invalidating would refetch the run for no reason and flash
      // the grid under an open dialog.
      if (result.dryRun) return;
      void client.invalidateQueries({ queryKey: keys.run(projectId, runId) });
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
