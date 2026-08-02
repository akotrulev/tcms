/** Test plans and the plan statistics report. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { useRef } from 'react';
import { TIMEOUTS, api, newIntentKey, qs } from '../client';
import type { PlanStatistics, PlanStatus, TestPlan } from '../types';
import { base, keys } from './keys';

export function usePlans(projectId: string): UseQueryResult<TestPlan[]> {
  return useQuery({
    queryKey: keys.plans(projectId),
    queryFn: ({ signal }) => api.get<TestPlan[]>(`${base(projectId)}/plans`, { signal }),
  });
}

export function usePlan(projectId: string, planId: string | undefined): UseQueryResult<TestPlan> {
  return useQuery({
    queryKey: keys.plan(projectId, planId ?? ''),
    queryFn: ({ signal }) => api.get<TestPlan>(`${base(projectId)}/plans/${planId}`, { signal }),
    enabled: !!planId,
  });
}

export function usePlanStatistics(
  projectId: string,
  planId: string | undefined,
  aggregation: string,
): UseQueryResult<PlanStatistics> {
  return useQuery({
    queryKey: keys.planStats(projectId, planId ?? '', aggregation),
    queryFn: ({ signal }) =>
      api.get<PlanStatistics>(`${base(projectId)}/plans/${planId}/statistics${qs({ aggregation })}`, {
        signal,
        timeoutMs: TIMEOUTS.long,
      }),
    enabled: !!planId,
  });
}

/** The body of `POST /plans` — `TestPlanInput`, spelled out so the dialog cannot mistype a field. */
export interface TestPlanInput {
  name: string;
  description?: string | null;
  milestoneName?: string | null;
  milestoneDate?: string | null;
  suiteIds?: string[];
  caseIds?: string[];
}

/**
 * Creates a plan. Live since backend M2 slice 2; before that this hook had no caller at all —
 * the dead-hook trap §28 and §29 each caught one slice apart.
 *
 * One `Idempotency-Key` per *mounted dialog*, which is one per user intent to create this plan.
 * A key generated inside `mutationFn` would be a fresh one per attempt, which is the mistake that
 * makes the header look right and do nothing (`useCreateCase` carries the full argument, and §29
 * records the server side finally reading it).
 */
export function useCreatePlan(projectId: string) {
  const client = useQueryClient();
  const intentKey = useRef(newIntentKey());
  return useMutation({
    mutationFn: (input: TestPlanInput) =>
      api.post<TestPlan>(`${base(projectId)}/plans`, input, { idempotencyKey: intentKey.current }),
    // The dialog lands field errors on its inputs and anything else in a form-level banner, so
    // the global handler must stay out of it or the user is told twice.
    meta: { handled: true },
    onSuccess: (created) => {
      client.setQueryData(keys.plan(projectId, created.id), created);
      void client.invalidateQueries({ queryKey: keys.plans(projectId) });
    },
  });
}

/**
 * Submits, approves, starts, closes or reopens a plan.
 *
 * `meta: { handled: true }` for `useSetRunStatus`' reason: "submit it for review first" and
 * "close the plan instead" are answers about the button that was just pressed, and a corner
 * notification detaches them from it.
 *
 * The client sends an absolute target and never a verb — which of them a plan may reach is the
 * server's question (`PlanLifecycle`), and a second copy of that ladder here would be one applied
 * to a `status` the cache may hold one save out of date.
 */
export function useSetPlanStatus(projectId: string, planId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (status: PlanStatus) =>
      api.put<TestPlan>(`${base(projectId)}/plans/${planId}/status`, { status }),
    meta: { handled: true },
    onSuccess: (updated) => {
      client.setQueryData(keys.plan(projectId, planId), updated);
      void client.invalidateQueries({ queryKey: keys.plans(projectId) });
      // The run list renders each run's plan key, and a run's own screen links to the plan — both
      // read a plan whose status has just moved.
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'runs'] });
    },
  });
}

/**
 * Deletes a plan. Refused by the server with a 409 while any live run names it, which is a
 * sentence the caller has to see — hence `handled`, as above.
 */
export function useDeletePlan(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (planId: string) => api.del<void>(`${base(projectId)}/plans/${planId}`),
    meta: { handled: true },
    onSuccess: (_deleted, planId) => {
      client.removeQueries({ queryKey: keys.plan(projectId, planId) });
      void client.invalidateQueries({ queryKey: keys.plans(projectId) });
    },
  });
}
