/** Runs, the executions inside them, and the one write that records a result. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { api, qs } from '../client';
import type { TestExecution, TestRun, TestRunDetail } from '../types';
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

export function useCreateRun(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: Record<string, unknown>) => api.post<TestRun>(`${base(projectId)}/runs`, input),
    onSuccess: () => client.invalidateQueries({ queryKey: ['projects', projectId, 'runs'] }),
  });
}

export function useExecution(
  projectId: string,
  runId: string | undefined,
  executionId: string | undefined,
): UseQueryResult<TestExecution> {
  return useQuery({
    queryKey: keys.execution(projectId, runId ?? '', executionId ?? ''),
    queryFn: ({ signal }) =>
      api.get<TestExecution>(`${base(projectId)}/runs/${runId}/executions/${executionId}`, { signal }),
    enabled: !!runId && !!executionId,
  });
}

export interface ExecutionResultInput {
  statusId?: string;
  comment?: string | null;
  stepResults?: { stepId?: string; statusId?: string; comment?: string | null }[];
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
