/**
 * The data module. Every server read and write in the app is declared here.
 *
 * Rules this file exists to enforce in one place rather than per component:
 *
 *   - **Query keys carry every parameter the request depends on**, so a response for a
 *     filter the user has already replaced can never land and overwrite a newer one. This
 *     is a data-correctness property, not a caching nicety.
 *   - **Every call threads the cancel signal and a timeout budget**, chosen per endpoint
 *     class (typeahead vs interactive vs report), so no region can spin forever.
 *   - **Dependent queries are gated with `enabled`**, so they never fire with an
 *     `undefined` id and never sit in a skeleton because their parent failed.
 *   - **No server data goes into a client store** — this cache *is* the server state.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { useRef } from 'react';
import { TIMEOUTS, api, newIntentKey, qs } from './client';
import type {
  Artifact,
  BulkResult,
  CaseHistorySummary,
  CaseStatus,
  ClassificationDimension,
  Condition,
  CurrentUser,
  DocPage,
  DocPageSummary,
  EntityVersion,
  ExecutionHistoryEntry,
  Paged,
  PlanStatistics,
  Project,
  RepositoryDashboard,
  Role,
  RoleAssignment,
  SharedStep,
  Suite,
  SuiteRunSummary,
  Team,
  TestCase,
  TestCaseSummary,
  TestExecution,
  TestPlan,
  TestRun,
  TestRunDetail,
  User,
  VersionMarker,
  WorkItem,
} from './types';
import type { CaseFilters } from './types';

/**
 * Key factory. Centralized so an invalidation can target a subtree (`['cases', projectId]`)
 * without every call site re-deriving the shape and getting it subtly wrong.
 */
export const keys = {
  me: ['me'] as const,
  projects: ['projects'] as const,
  project: (id: string) => ['projects', id] as const,
  classification: (p: string) => ['projects', p, 'classification'] as const,
  statuses: (p: string) => ['projects', p, 'statuses'] as const,
  suites: (p: string) => ['projects', p, 'suites'] as const,
  suiteHistory: (p: string, s: string) => ['projects', p, 'suites', s, 'history'] as const,
  cases: (p: string, filters: CaseFilters, sort: string) =>
    ['projects', p, 'cases', { ...filters, sort }] as const,
  case: (p: string, id: string) => ['projects', p, 'cases', id] as const,
  caseVersions: (p: string, id: string) => ['projects', p, 'cases', id, 'versions'] as const,
  caseHistory: (p: string, id: string, scope: string) =>
    ['projects', p, 'cases', id, 'history', scope] as const,
  conditions: (p: string, q: string, type?: string) => ['projects', p, 'conditions', q, type ?? 'ALL'] as const,
  sharedSteps: (p: string, q: string) => ['projects', p, 'shared-steps', q] as const,
  plans: (p: string) => ['projects', p, 'plans'] as const,
  plan: (p: string, id: string) => ['projects', p, 'plans', id] as const,
  planStats: (p: string, id: string, aggregation: string) =>
    ['projects', p, 'plans', id, 'statistics', aggregation] as const,
  runs: (p: string, planId?: string) => ['projects', p, 'runs', planId ?? 'ALL'] as const,
  run: (p: string, id: string) => ['projects', p, 'runs', id] as const,
  execution: (p: string, runId: string, id: string) =>
    ['projects', p, 'runs', runId, 'executions', id] as const,
  docPages: (p: string) => ['projects', p, 'doc-pages'] as const,
  docPage: (p: string, id: string) => ['projects', p, 'doc-pages', id] as const,
  artifacts: (p: string, scope?: string, planId?: string) =>
    ['projects', p, 'artifacts', scope ?? 'ALL', planId ?? 'NONE'] as const,
  dashboard: (p: string) => ['projects', p, 'dashboard'] as const,
  users: ['users'] as const,
  teams: ['teams'] as const,
  roles: ['roles'] as const,
  roleAssignments: (p?: string) => ['role-assignments', p ?? 'ALL'] as const,
  workItems: (p: string, mine: boolean) => ['work-items', p, mine] as const,
};

const base = (projectId: string) => `/projects/${projectId}`;

// ------------------------------------------------------------------ identity ----

export function useCurrentUser(): UseQueryResult<CurrentUser> {
  return useQuery({
    queryKey: keys.me,
    queryFn: ({ signal }) => api.get<CurrentUser>('/me', { signal }),
  });
}

export function useProjects(): UseQueryResult<Project[]> {
  return useQuery({
    queryKey: keys.projects,
    queryFn: ({ signal }) => api.get<Project[]>('/projects', { signal }),
    // The project list changes rarely and is read on every screen via the rail.
    staleTime: 5 * 60_000,
  });
}

export function useProject(projectId: string | undefined): UseQueryResult<Project> {
  return useQuery({
    queryKey: keys.project(projectId ?? ''),
    queryFn: ({ signal }) => api.get<Project>(base(projectId!), { signal }),
    enabled: !!projectId,
    staleTime: 5 * 60_000,
  });
}

// ------------------------------------------------------------ project config ----

export function useClassification(projectId: string): UseQueryResult<ClassificationDimension[]> {
  return useQuery({
    queryKey: keys.classification(projectId),
    queryFn: ({ signal }) => api.get<ClassificationDimension[]>(`${base(projectId)}/classification`, { signal }),
    staleTime: 5 * 60_000,
  });
}

export function useStatuses(projectId: string): UseQueryResult<CaseStatus[]> {
  return useQuery({
    queryKey: keys.statuses(projectId),
    queryFn: ({ signal }) => api.get<CaseStatus[]>(`${base(projectId)}/statuses`, { signal }),
    staleTime: 5 * 60_000,
  });
}

// --------------------------------------------------------------------- suites ----

export function useSuites(projectId: string): UseQueryResult<Suite[]> {
  return useQuery({
    queryKey: keys.suites(projectId),
    queryFn: ({ signal }) => api.get<Suite[]>(`${base(projectId)}/suites`, { signal }),
  });
}

export function useCreateSuite(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { name: string; parentId?: string | null; description?: string | null }) =>
      api.post<Suite>(`${base(projectId)}/suites`, input),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.suites(projectId) }),
  });
}

export function useSuiteHistory(
  projectId: string,
  suiteId: string | undefined,
): UseQueryResult<{ runs: SuiteRunSummary[]; versionMarkers: VersionMarker[] }> {
  return useQuery({
    queryKey: keys.suiteHistory(projectId, suiteId ?? ''),
    queryFn: ({ signal }) =>
      api.get<{ runs: SuiteRunSummary[]; versionMarkers: VersionMarker[] }>(
        `${base(projectId)}/suites/${suiteId}/history`,
        { signal },
      ),
    enabled: !!suiteId,
  });
}

// ---------------------------------------------------------------------- cases ----

export function useCases(
  projectId: string,
  filters: CaseFilters,
  sort: string,
): UseQueryResult<Paged<TestCaseSummary>> {
  return useQuery({
    // Every filter, the page, the size and the sort are in the key: a slow response for
    // "ref" can no longer overwrite the rows for "refund".
    queryKey: keys.cases(projectId, filters, sort),
    queryFn: ({ signal }) =>
      api.get<Paged<TestCaseSummary>>(`${base(projectId)}/cases${qs({ ...filters, sort })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    // Keep the previous page's rows on screen while the next one loads, so paging doesn't
    // flash an empty grid.
    placeholderData: (previous) => previous,
  });
}

export function useCase(projectId: string, caseId: string | undefined): UseQueryResult<TestCase> {
  return useQuery({
    queryKey: keys.case(projectId, caseId ?? ''),
    queryFn: ({ signal }) => api.get<TestCase>(`${base(projectId)}/cases/${caseId}`, { signal }),
    enabled: !!caseId,
  });
}

export function useCreateCase(projectId: string) {
  const client = useQueryClient();
  /*
    One key per mounted editor — that is, per user intent to create *this* case. It survives
    re-renders and failed attempts, so pressing Save again after a timeout sends the same key and
    the server recognizes the retry instead of creating a second case. A key generated inside
    `mutationFn` would be a fresh one per attempt, which is the mistake that makes the header
    look right and do nothing.
  */
  const intentKey = useRef(newIntentKey());
  return useMutation({
    mutationFn: (input: Record<string, unknown>) =>
      api.post<TestCase>(`${base(projectId)}/cases`, input, { idempotencyKey: intentKey.current }),
    // The editor maps field errors onto the inputs and renders anything else in a form-level
    // banner, so the global handler must stay out of it or the user sees the same failure twice.
    meta: { handled: true },
    onSuccess: (created) => {
      client.setQueryData(keys.case(projectId, created.id), created);
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
      void client.invalidateQueries({ queryKey: keys.suites(projectId) });
    },
  });
}

export function useUpdateCase(projectId: string, caseId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: Record<string, unknown>) =>
      api.patch<TestCase>(`${base(projectId)}/cases/${caseId}`, input),
    // Same reasoning as the create above — and this one also renders 409 conflicts itself,
    // which are a decision to hand back rather than a generic failure to announce.
    meta: { handled: true },
    onSuccess: (updated) => {
      client.setQueryData(keys.case(projectId, caseId), updated);
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
      void client.invalidateQueries({ queryKey: keys.caseVersions(projectId, caseId) });
    },
  });
}

export function useDeleteCase(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (caseId: string) => api.del<void>(`${base(projectId)}/cases/${caseId}`),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
      void client.invalidateQueries({ queryKey: keys.suites(projectId) });
    },
  });
}

export interface BulkInput {
  caseIds: string[];
  /** One of the contract's `BulkOperation.action` values. */
  action: string;
  /** Action-specific arguments — the contract's `payload`. */
  payload?: Record<string, unknown>;
  dryRun: boolean;
}

/**
 * Bulk edits are preview-then-commit: the same call with `dryRun` gives the preview, and
 * the result always names what changed, what was already compliant, and what was skipped
 * and why. Mass edits without a preview are how someone destroys a suite at 5pm.
 */
export function useBulkCases(projectId: string): UseMutationResult<BulkResult, Error, BulkInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: BulkInput) =>
      api.post<BulkResult>(`${base(projectId)}/cases/bulk`, input, { timeoutMs: TIMEOUTS.long }),
    // Bulk results are reported per item by the dialog. A blanket notification over a partial
    // failure is the worse half of the defect §6f exists to prevent.
    meta: { handled: true },
    onSuccess: (result) => {
      if (!result.dryRun) {
        void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
      }
    },
  });
}

export function useCaseVersions(
  projectId: string,
  caseId: string | undefined,
): UseQueryResult<EntityVersion[]> {
  return useQuery({
    queryKey: keys.caseVersions(projectId, caseId ?? ''),
    queryFn: ({ signal }) => api.get<EntityVersion[]>(`${base(projectId)}/cases/${caseId}/versions`, { signal }),
    enabled: !!caseId,
  });
}

export interface CaseHistoryResponse {
  summary: CaseHistorySummary;
  entries: ExecutionHistoryEntry[];
  versionMarkers: VersionMarker[];
}

export function useCaseHistory(
  projectId: string,
  caseId: string | undefined,
  scope: 'CURRENT_VERSION' | 'ALL_VERSIONS',
): UseQueryResult<CaseHistoryResponse> {
  return useQuery({
    queryKey: keys.caseHistory(projectId, caseId ?? '', scope),
    queryFn: ({ signal }) =>
      api.get<CaseHistoryResponse>(`${base(projectId)}/cases/${caseId}/history${qs({ scope })}`, { signal }),
    enabled: !!caseId,
  });
}

// ------------------------------------------------- conditions & shared steps ----

export function useConditions(
  projectId: string,
  q: string,
  type?: 'PRE' | 'POST',
): UseQueryResult<Condition[]> {
  return useQuery({
    queryKey: keys.conditions(projectId, q, type),
    queryFn: ({ signal }) =>
      api.get<Condition[]>(`${base(projectId)}/conditions${qs({ q, type })}`, {
        signal,
        // Search-as-you-type: a 10s budget here would be a broken experience.
        timeoutMs: TIMEOUTS.typeahead,
      }),
  });
}

export function useSharedSteps(projectId: string, q: string): UseQueryResult<SharedStep[]> {
  return useQuery({
    queryKey: keys.sharedSteps(projectId, q),
    queryFn: ({ signal }) =>
      api.get<SharedStep[]>(`${base(projectId)}/shared-steps${qs({ q })}`, {
        signal,
        timeoutMs: TIMEOUTS.typeahead,
      }),
  });
}

// ---------------------------------------------------------------------- plans ----

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

export function useCreatePlan(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: Record<string, unknown>) => api.post<TestPlan>(`${base(projectId)}/plans`, input),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.plans(projectId) }),
  });
}

// ----------------------------------------------------------------------- runs ----

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
  lockVersion?: number;
  stepResults?: { stepId?: string; statusId?: string; comment?: string | null }[];
}

export function useSubmitResult(projectId: string, runId: string, executionId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ExecutionResultInput) =>
      api.patch<TestExecution>(`${base(projectId)}/runs/${runId}/executions/${executionId}`, input),
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

// -------------------------------------------------- documentation & artifacts ----

export function useDocPages(projectId: string): UseQueryResult<DocPageSummary[]> {
  return useQuery({
    queryKey: keys.docPages(projectId),
    queryFn: ({ signal }) => api.get<DocPageSummary[]>(`${base(projectId)}/doc-pages`, { signal }),
  });
}

export function useDocPage(projectId: string, pageId: string | undefined): UseQueryResult<DocPage> {
  return useQuery({
    queryKey: keys.docPage(projectId, pageId ?? ''),
    queryFn: ({ signal }) => api.get<DocPage>(`${base(projectId)}/doc-pages/${pageId}`, { signal }),
    enabled: !!pageId,
  });
}

export function useArtifacts(
  projectId: string,
  scope?: 'PROJECT' | 'PLAN',
  planId?: string,
): UseQueryResult<Artifact[]> {
  return useQuery({
    queryKey: keys.artifacts(projectId, scope, planId),
    queryFn: ({ signal }) =>
      api.get<Artifact[]>(`${base(projectId)}/artifacts${qs({ scope, planId })}`, { signal }),
  });
}

// ------------------------------------------------------------------ reporting ----

export function useDashboard(projectId: string): UseQueryResult<RepositoryDashboard> {
  return useQuery({
    queryKey: keys.dashboard(projectId),
    queryFn: ({ signal }) =>
      api.get<RepositoryDashboard>(`${base(projectId)}/dashboard`, { signal, timeoutMs: TIMEOUTS.long }),
  });
}

// ---------------------------------------------------------------------- admin ----

export function useUsers(): UseQueryResult<User[]> {
  return useQuery({
    queryKey: keys.users,
    queryFn: ({ signal }) => api.get<User[]>('/users', { signal }),
    staleTime: 60_000,
  });
}

export function useTeams(): UseQueryResult<Team[]> {
  return useQuery({
    queryKey: keys.teams,
    queryFn: ({ signal }) => api.get<Team[]>('/teams', { signal }),
  });
}

export function useRoles(): UseQueryResult<Role[]> {
  return useQuery({
    queryKey: keys.roles,
    queryFn: ({ signal }) => api.get<Role[]>('/roles', { signal }),
    staleTime: 5 * 60_000,
  });
}

export function useRoleAssignments(projectId?: string): UseQueryResult<RoleAssignment[]> {
  return useQuery({
    queryKey: keys.roleAssignments(projectId),
    queryFn: ({ signal }) => api.get<RoleAssignment[]>(`/role-assignments${qs({ projectId })}`, { signal }),
  });
}

// ----------------------------------------------------------------- work items ----

export function useWorkItems(projectId: string, mine: boolean): UseQueryResult<WorkItem[]> {
  return useQuery({
    queryKey: keys.workItems(projectId, mine),
    queryFn: ({ signal }) =>
      api.get<WorkItem[]>(`/work-items${qs({ projectId, mine: mine ? 'true' : undefined })}`, { signal }),
  });
}

export function useMoveWorkItem(projectId: string, mine: boolean) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) =>
      api.patch<WorkItem>(`/work-items/${id}`, { status }),
    // The board says which card failed to move and where it snapped back to; that is strictly
    // more useful than "couldn't save", so the global handler stays out.
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.workItems(projectId, mine) }),
  });
}

export function useCreateWorkItem(projectId: string, mine: boolean) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: Record<string, unknown>) =>
      api.post<WorkItem>('/work-items', { ...input, projectId }),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.workItems(projectId, mine) }),
  });
}
