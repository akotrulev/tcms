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
  projectSettings: (p: string) => ['projects', p, 'settings'] as const,
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
  /**
   * The case's buffered autosave draft (§128).
   *
   * Under `keys.case`'s prefix like the two above, which is what a reader would expect — and it
   * carries one consequence worth naming, because it is load-bearing rather than incidental:
   * `useUpdateCase` invalidates `['projects', p, 'cases']`, so a successful save invalidates this
   * key too. That is exactly right, since the save clears the buffer server-side, and the refetch
   * is what turns the editor's "restore unsaved changes" offer off without anybody wiring it up.
   */
  caseDraft: (p: string, id: string) => ['projects', p, 'cases', id, 'draft'] as const,
  /**
   * The project's tags **in use** (§97 phase 3b's endpoint, first consumer in phase 6).
   *
   * No `q` in the key, unlike the searches around it: this list is derived from `case_tag` and is a
   * project's whole vocabulary, filtered in the client. A tag list is short and the picker has to be
   * able to say "this project uses none yet", which a server-side search cannot distinguish from
   * "none match what you typed".
   */
  tags: (p: string) => ['projects', p, 'tags'] as const,
  conditions: (p: string, q: string, type?: string) => ['projects', p, 'conditions', q, type ?? 'ALL'] as const,
  sharedSteps: (p: string, q: string) => ['projects', p, 'shared-steps', q] as const,
  /**
   * The derived step corpus (§94), keyed by the fragment it was narrowed with.
   *
   * `q` is in the key for the reason every search key carries its term: the case editor fires one
   * of these per step row as the author types, so several are in flight at once and the answer to
   * "refu" must not land in the list a row that now reads "refund" is showing. Two rows typing the
   * same fragment share the entry, which is the other half of why the term rather than the row is
   * what identifies it.
   */
  stepSuggestions: (p: string, q: string) => ['projects', p, 'step-suggestions', q] as const,
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
  /**
   * The ingestion history, and one job.
   *
   * Two keys rather than one with a `find` over the list, because the two endpoints serve
   * **different shapes**: the list carries no `unmatched` array at all. A detail read served out of
   * the list's cache would render an empty binder over a job with twelve unmatched tests.
   */
  ingestions: (p: string) => ['projects', p, 'ingestions'] as const,
  ingestion: (p: string, id: string) => ['projects', p, 'ingestions', id] as const,
  /**
   * The review queue (§138), keyed by the status it is showing.
   *
   * The status is IN the key for the reason every filter is: accepting a proposal invalidates the
   * whole `['projects', p, 'ai-proposals']` subtree, and the pending list and the accepted list
   * are two answers that must not be able to land in each other's cache entry — the row just
   * decided is absent from one and present in the other, which is exactly the pair a shared key
   * would let cross.
   */
  aiProposals: (p: string, status: string) => ['projects', p, 'ai-proposals', status] as const,
  aiProposal: (p: string, id: string) => ['projects', p, 'ai-proposals', 'one', id] as const,
  automationMappings: (p: string) => ['projects', p, 'automation-mappings'] as const,
  docPages: (p: string) => ['projects', p, 'doc-pages'] as const,
  docPage: (p: string, id: string) => ['projects', p, 'doc-pages', id] as const,
  /*
    An attachment's BYTES, keyed by the server's own content URL.

    Keyed on the URL rather than on (project, page, attachment) because the thing being cached is
    one immutable response and the URL is what identifies it — an attachment's bytes never change,
    the row is hard-deleted instead. That also makes the key correct for any future owner (a case's
    screenshot, an artifact) with no second spelling of the same idea.
  */
  attachmentBlob: (url: string) => ['attachment-blob', url] as const,
  /*
    A plan's document.

    Deliberately NOT nested under `docPage`, even though it resolves to one. The plan id is what
    the caller has; the page id is what the answer contains, and it is absent until somebody links
    a page. A key derived from the page id could not be built before the first fetch.

    The consequence is two keys for one page once one is linked, so a mutation that writes the page
    must invalidate both.
  */
  planDocument: (p: string, planId: string) => ['projects', p, 'plans', planId, 'document'] as const,
  artifacts: (p: string, scope?: string, planId?: string) =>
    ['projects', p, 'artifacts', scope ?? 'ALL', planId ?? 'NONE'] as const,
  dashboard: (p: string) => ['projects', p, 'dashboard'] as const,
  reportDefinitions: (p: string) => ['projects', p, 'reports'] as const,
  /**
   * One generation job (§87's poll).
   *
   * Keyed by the job as well as the project, because a screen holds several at once: generating a
   * second report while the first is still running is the ordinary case, and a key that named only
   * the project would make the two jobs one cache entry — the second answer overwriting the first's
   * `downloadUrl` and the first card losing the link it had just earned.
   */
  reportJob: (p: string, jobId: string) => ['projects', p, 'reports', 'jobs', jobId] as const,
  /**
   * The stored model that job produced (F2's on-screen view).
   *
   * Keyed by the **job** and not by the artifact URL, though the URL is what the request sends. The
   * two identify the same bytes — a job's `modelUrl` is written once, when it completes, and a
   * completed job is immutable — and the job id is the one of them that exists before the fetch
   * does, so a key built from the URL would be absent from the cache exactly while the screen is
   * waiting for it.
   */
  reportModel: (p: string, jobId: string) =>
    ['projects', p, 'reports', 'jobs', jobId, 'model'] as const,
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
  /**
   * The company's API tokens (§118).
   *
   * No project segment and no filter parameter, and both absences are the contract's: a token
   * belongs to the *company* even when its `projectId` pins it to one project, and the list is
   * **refused** rather than narrowed for a caller who may not read it. So there is exactly one
   * entry for the whole company and nothing to key it by.
   */
  apiTokens: ['api-tokens'] as const,
  workItems: (p: string, mine: boolean) => ['work-items', p, mine] as const,
  /**
   * The prefix every board shares — invalidate this and all three refresh.
   *
   * Named here rather than spelled `['work-items']` at each call site, which is what the three
   * entries below argue for and what `workItems.ts` had privately. §121 phase 4 gave it a second
   * module's worth of callers: quarantining a case changes a card on a board the case screen does
   * not know about, so the literal was about to exist twice.
   */
  workItemRoot: ['work-items'] as const,
  /**
   * §6l's cross-project personal board (§83) — **under the same `work-items` root as the project
   * board, and that is the point.**
   *
   * The two boards show overlapping cards: a task assigned to me in this project is on both, and a
   * review I owe is on both. So a move made on either has to refresh the other, and keeping one
   * root lets `['work-items']` do it as a prefix — neither board has to name the other's key, which
   * is how the invalidation would drift the first time a third board arrives.
   *
   * `'ALL'` sits where a project id goes and cannot collide with one: project ids are UUIDs.
   * `true` is not a parameter — there is no unfiltered cross-project board. The server answers
   * **400** for that request rather than narrowing it, so the key space has no entry for it either.
   */
  myWork: ['work-items', 'ALL', true] as const,
  /**
   * §6l's **team board** (§84) — under the same `work-items` root, for `myWork`'s reason.
   *
   * A team board's cards overlap both other boards: an execution assigned to a colleague is on
   * their My Work, on their project's board, and on every team board they are a member of. So a
   * move made anywhere has to refresh this one too, which the root invalidation already does — a
   * third board is exactly the case that comment predicted.
   *
   * `'TEAM'` sits where a project id goes and cannot collide with one (project ids are UUIDs), and
   * the team id follows it rather than replacing it: two team boards are two caches, and keying
   * only on `'TEAM'` would serve one team's cards under another team's name for as long as the
   * entry stayed fresh.
   *
   * **No `mine` and no project**, because neither is a request this board can make: `teamId` with
   * `mine=true` is a **400** (two boards at once), and the `projectId` conjunction §6l calls
   * "swimlanes by member" is a layout nothing renders yet. Both would be key-space entries for
   * requests no hook sends.
   */
  teamBoard: (teamId: string) => ['work-items', 'TEAM', teamId] as const,
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
