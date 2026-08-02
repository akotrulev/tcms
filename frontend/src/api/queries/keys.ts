/**
 * The query-key factory, and the one path helper every module builds URLs from.
 *
 * This lives in its own module because it is the one thing the per-domain modules beside it
 * must **share**: an invalidation in `cases.ts` targets a key `suites.ts` owns, and the moment
 * two modules derive the same key shape independently they drift, silently, in the direction
 * of an invalidation that matches nothing.
 *
 * Centralized so an invalidation can target a subtree (`['cases', projectId]`) without every
 * call site re-deriving the shape and getting it subtly wrong.
 */
import type { CaseFilters } from '../types';

export const keys = {
  me: ['me'] as const,
  permissions: (p: string) => ['me', 'permissions', p] as const,
  invitation: (token: string) => ['invitation', token] as const,
  projects: ['projects'] as const,
  project: (id: string) => ['projects', id] as const,
  classification: (p: string) => ['projects', p, 'classification'] as const,
  statuses: (p: string) => ['projects', p, 'statuses'] as const,
  projectMembers: (p: string) => ['projects', p, 'members'] as const,
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
  /**
   * A subject's reviews. Keyed by the subject *type* as well as its id, because the id spaces are
   * independent — a case and a suite may in principle carry the same uuid, and a key that dropped
   * the type would serve one artifact's review trail for another.
   */
  reviews: (p: string, subjectType: string, subjectId: string) =>
    ['projects', p, 'reviews', subjectType, subjectId] as const,
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
  /**
   * One key, no project segment — and it was a function taking an optional project until §63.
   *
   * `GET /role-assignments` still declares a `projectId` filter and the handler still binds it; the
   * SPA no longer asks. The filter returns only the grants *naming* that project, and there is no
   * filter for the ones naming none — so it cannot assemble `ProjectAccessPage`, which needs both
   * populations to compute §A1.1's override across them. Every consumer therefore reads the whole
   * list and partitions it, which also means every consumer shares this cache entry.
   */
  roleAssignments: ['role-assignments'] as const,
  workItems: (p: string, mine: boolean) => ['work-items', p, mine] as const,
  /**
   * The caller's permissions with **no project in the question** — a different answer from
   * `permissions(projectId)` and therefore a different key.
   *
   * Not `permissions('')`: an empty string is a project id that failed to arrive, and reusing the
   * project-keyed factory for the company-wide question would make a screen that renders before
   * its route params resolve read the company answer and cache it as that project's.
   */
  companyPermissions: ['me', 'permissions', 'company'] as const,
};

export const base = (projectId: string) => `/projects/${projectId}`;
