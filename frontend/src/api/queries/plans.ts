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
 * The body of `PATCH /plans/{planId}` — `TestPlanUpdateInput`.
 *
 * **Every list is optional and that is load-bearing, not convenience.** The server reads an
 * omitted list as *leave it alone* and `[]` as *clear it* (a scalar `null` still means clear), so
 * a dialog that edits only the name must send only the name — spreading a partially-filled object
 * with `suiteIds: []` in it would empty the plan's scope on a rename. Spelled out here rather than
 * taken from the generated schema so the shape a caller builds is readable at the call site.
 */
export interface TestPlanUpdateInput {
  name: string;
  description?: string | null;
  milestoneName?: string | null;
  milestoneDate?: string | null;
  suiteIds?: string[];
  caseIds?: string[];
  entryCriteria?: CriterionInput[];
  exitCriteria?: CriterionInput[];
}

/**
 * One authored gate. `Criterion` minus `met` and `actual`, which the server computes and would
 * overwrite — so they are not on the way in, and a form must not round-trip them.
 */
export interface CriterionInput {
  metric: string;
  op: string;
  value: number;
  label?: string | null;
}

/**
 * Edits a plan's content — name, description, milestone, scope and criteria.
 *
 * `lockVersion` travels as `If-Match`, never in the body, and is required rather than optional for
 * `useUpdateCase`'s reason: omitting the header is last-write-wins, which is right for an importer
 * with no prior read and never right for an editor, so a screen cannot opt out of conflict
 * detection by forgetting.
 *
 * `handled` because two of this endpoint's refusals are sentences about the button just pressed —
 * a closed plan says "reopen it" and a stale save says "reload it", and a corner notification
 * detaches both from the dialog that caused them.
 */
export function useUpdatePlan(projectId: string, planId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      lockVersion,
      ...input
    }: TestPlanUpdateInput & { lockVersion: number }) =>
      api.patch<TestPlan>(`${base(projectId)}/plans/${planId}`, input, {
        headers: { 'If-Match': String(lockVersion) },
      }),
    meta: { handled: true },
    onSuccess: (updated) => {
      client.setQueryData(keys.plan(projectId, planId), updated);
      // `keys.plans` is a PREFIX of `keys.plan` and of `keys.planStats`, so this one call also
      // refetches the statistics — which matters here more than on the status endpoint, because
      // that screen evaluates the exit criteria and counts the scope, and this is what edits both.
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
/** The argument to {@link useSetPlanStatus} — the target, plus §6g's opt-in override (§82). */
export interface SetPlanStatus {
  status: PlanStatus;
  overrideApprovalGate?: boolean;
}

export function useSetPlanStatus(projectId: string, planId: string) {
  const client = useQueryClient();
  return useMutation({
    /*
      `overrideApprovalGate` (§82) is meaningful on exactly one move — starting a plan on a project
      that requires approved plans — and is read-and-ignored on the other four. Sent as an optional
      second argument rather than a second hook, so the ladder's buttons keep one call site and no
      caller has to hold a copy of which edge the rule binds on.
    */
    mutationFn: ({ status, overrideApprovalGate }: SetPlanStatus) =>
      api.put<TestPlan>(`${base(projectId)}/plans/${planId}/status`, {
        status,
        ...(overrideApprovalGate ? { overrideApprovalGate: true } : {}),
      }),
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
