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
import type { QueryClient } from '@tanstack/react-query';
import type {
  AuditFilters,
  BacklogFilters,
  BoardFilters,
  CaseFilters,
  DirectoryFilters,
  IssueFilters,
} from '../types';

export const keys = {
  me: ['me'] as const,
  permissions: (p: string) => ['me', 'permissions', p] as const,
  /** Instance configuration, identical for every caller — cached forever per session. */
  socialProviders: ['auth', 'social-providers'] as const,
  mySocialIdentities: ['me', 'social-identities'] as const,
  invitation: (token: string) => ['invitation', token] as const,
  projects: ['projects'] as const,
  project: (id: string) => ['projects', id] as const,
  classification: (p: string) => ['projects', p, 'classification'] as const,
  projectSettings: (p: string) => ['projects', p, 'settings'] as const,
  /**
   * What changing the review policy would land on (005 S7).
   *
   * Under `projectSettings`' prefix so a saved policy refreshes it — the counts move the moment the
   * gate they describe does — and **read fresh every time the screen opens**, because the whole
   * point of the panel is the number *now*.
   */
  reviewImpact: (p: string) => ['projects', p, 'settings', 'review-impact'] as const,
  statuses: (p: string) => ['projects', p, 'statuses'] as const,
  /**
   * The project's repositories — beside `statuses` and `configurations` because it is the same kind
   * of thing: reference data the project owns, which outlives every issue that is worked in it.
   */
  projectRepositories: (p: string) => ['projects', p, 'repositories'] as const,
  projectMembers: (p: string) => ['projects', p, 'members'] as const,
  /**
   * The project's environments — the vocabulary a run form picks from.
   *
   * Beside `statuses` and `projectMembers` rather than under `runs`, because it is a property of
   * the project and outlives every run that names it. A run-scoped key would be invalidated by a
   * run write, which is exactly the refetch this list never needs.
   */
  configurations: (p: string) => ['projects', p, 'configurations'] as const,
  suites: (p: string) => ['projects', p, 'suites'] as const,
  suiteHistory: (p: string, s: string) => ['projects', p, 'suites', s, 'history'] as const,
  cases: (p: string, filters: CaseFilters, sort: string) =>
    ['projects', p, 'cases', { ...filters, sort }] as const,
  case: (p: string, id: string) => ['projects', p, 'cases', id] as const,
  caseVersions: (p: string, id: string) => ['projects', p, 'cases', id, 'versions'] as const,
  caseHistory: (p: string, id: string, scope: string) =>
    ['projects', p, 'cases', id, 'history', scope] as const,
  /**
   * The case rendered as canonical `.feature` text (§255).
   *
   * Under `keys.case`'s prefix for `caseDraft`'s reason and with the same consequence, wanted here
   * too: a save invalidates `['projects', p, 'cases']`, so the text a reader opened before an edit
   * cannot survive the edit. A key outside that prefix would serve the previous version's scenario
   * from cache with nothing on screen saying so.
   */
  caseFeature: (p: string, id: string) => ['projects', p, 'cases', id, 'feature'] as const,
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
   * The same corpus **unnarrowed** — the case editor's standing panel (003 Phase 1).
   *
   * A key of its own rather than `stepSuggestions(p, '')`, and the two must not collapse into one.
   * They are read under different rules: the completion above fires per fragment as the author
   * types and is gated on there being one, while this is a browsing surface mounted **once per
   * page** and asks for the whole list. Sharing a key would make the panel's answer the cache
   * entry an empty completion then reads, which is the read `useStepSuggestions`'s `enabled` gate
   * exists to prevent — and it would put the panel's list into a popup under somebody's caret.
   */
  stepCorpus: (p: string) => ['projects', p, 'step-corpus'] as const,
  /**
   * A subject's reviews. Keyed by the subject *type* as well as its id, because the id spaces are
   * independent — a case and a suite may in principle carry the same uuid, and a key that dropped
   * the type would serve one artifact's review trail for another.
   */
  reviews: (p: string, subjectType: string, subjectId: string) =>
    ['projects', p, 'reviews', subjectType, subjectId] as const,
  plans: (p: string) => ['projects', p, 'plans'] as const,
  /**
   * The plan list WITH the archive (`V65`, audit D2).
   *
   * A key of its own rather than a parameter on `plans`, because the two are different answers to
   * different questions and caching them under one key would serve whichever was fetched last. It
   * is still under the `plans` prefix, so every existing `invalidateQueries(keys.plans(p))` — of
   * which there are several, on every plan write — refreshes both without being told about this.
   */
  plansWithArchived: (p: string) => ['projects', p, 'plans', { archived: true }] as const,
  plan: (p: string, id: string) => ['projects', p, 'plans', id] as const,
  planStats: (p: string, id: string, aggregation: string) =>
    ['projects', p, 'plans', id, 'statistics', aggregation] as const,
  runs: (p: string, planId?: string) => ['projects', p, 'runs', planId ?? 'ALL'] as const,
  run: (p: string, id: string) => ['projects', p, 'runs', id] as const,
  execution: (p: string, runId: string, id: string) =>
    ['projects', p, 'runs', runId, 'executions', id] as const,
  /**
   * The project's run schedules (§247).
   *
   * Deliberately NOT under the `runs` prefix, even though a schedule is delegated run creation:
   * `['projects', p, 'runs']` is invalidated by every result recorded and every run created, and a
   * standing request does not move when a run does. Sharing the prefix would refetch this list on
   * every verdict a tester enters.
   *
   * One key for the list and the item, because there is no second read shape — `GET /{scheduleId}`
   * serves exactly the schema the list serves, so an item read out of the list's cache is the same
   * document rather than a partial one (`keys.ingestions`' distinction, answering the other way).
   */
  runSchedules: (p: string) => ['projects', p, 'run-schedules'] as const,
  runSchedule: (p: string, id: string) => ['projects', p, 'run-schedules', id] as const,
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
    The query text is IN the key, which is the whole of how a slow answer to "refu" cannot land on
    top of the answer to "refund". `'search'` sits before it so a page id can never collide with a
    search term — `docPage(p, 'search')` is a different key from this one at every length.
  */
  docPageSearch: (p: string, q: string) => ['projects', p, 'doc-pages', 'search', q] as const,
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
   * What this deployment can render (B5).
   *
   * Project-keyed because the route is, and **not** because the answer varies by project — it does
   * not. Deliberately **outside the `reports` prefix**, which both `reportDefinitions` and
   * `reportJobs` sit under and which every generate and every save invalidates: this answer changes
   * when an operator installs a renderer, which is never during a session, so a key inside that
   * prefix would refetch a constant on every write in the feature.
   */
  reportCapabilities: (p: string) => ['projects', p, 'report-capabilities'] as const,
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
   * Every generation request this project has made (§227's list).
   *
   * A **prefix** of `reportJob` above, deliberately: the list and the single-job poll answer the
   * same rows, so invalidating the list after a generate brings both current in one call. The
   * alternative — a sibling key like `['reports', 'jobList']` — makes a screen holding a card and
   * the list show two ages of one job, which is the drift the key factory exists to prevent.
   */
  reportJobs: (p: string) => ['projects', p, 'reports', 'jobs'] as const,
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
  /**
   * The project's notification channels (§218), and one of them.
   *
   * Two keys rather than one with a `find` over the list, unlike most detail reads in this factory
   * — and here the reason is not a difference of shape. `GET …/notification-channels/{id}` and the
   * list serve the **same** schema, because this resource has no `*Summary`. What differs is
   * reachability: the list is refused outright for a caller without `integration:manage`, so a
   * detail read served out of a cache entry that never populated would be indistinguishable from a
   * channel that does not exist.
   */
  notificationChannels: (p: string) => ['projects', p, 'notification-channels'] as const,
  notificationChannel: (p: string, id: string) =>
    ['projects', p, 'notification-channels', id] as const,
  /**
   * One channel's delivery attempts.
   *
   * Nested under the channel because the endpoint is — there is no project-level log, and the
   * reason is the same one that makes this key correct: deleting a channel takes its history with
   * it, so an entry keyed anywhere else would outlive the rows it names. The delete hook removes
   * this entry rather than invalidating it, for exactly that.
   */
  notificationDeliveries: (p: string, id: string) =>
    ['projects', p, 'notification-channels', id, 'deliveries'] as const,
  /**
   * The project's tracker bindings (004 Phase 1), and one of them.
   *
   * Two keys, `notificationChannels`' pair and for its reason rather than a shape difference:
   * `GET …/bindings/{id}` serves exactly what the list serves, but the list is **refused** for a
   * caller without `integration:manage`, so a detail read served out of an entry that never
   * populated would be indistinguishable from a binding that does not exist.
   */
  /**
   * Everything under this project's tracker sub-resource, as a prefix (§250's rule).
   *
   * A binding write moves three different reads — the list, the connected-members states (the set
   * of bound hosts just changed) and every issue previewed through it — so the invalidation needs
   * the prefix on its own rather than three call sites naming three keys.
   */
  trackerRoot: (p: string) => ['projects', p, 'tracker'] as const,
  trackerBindings: (p: string) => ['projects', p, 'tracker', 'bindings'] as const,
  trackerBinding: (p: string, id: string) => ['projects', p, 'tracker', 'bindings', id] as const,
  /**
   * Who on this project has connected to the hosts its bindings name (004 D19).
   *
   * Under the project's `tracker` prefix, so deleting or adding a binding — which changes the set
   * of hosts every row is scored against — refreshes it with no hook naming this key.
   */
  trackerConnectedMembers: (p: string) => ['projects', p, 'tracker', 'connected-members'] as const,
  /**
   * The caller's **own** tracker connections (004 D2). No project segment: an identity is a
   * person's credential for a host, and every project bound to that host uses the same one.
   *
   * Under `['me', …]` beside `mySocialIdentities`, which is the same kind of thing — a personal
   * credential, self-service, gated on being the caller and nothing else.
   */
  myTrackerIdentities: ['me', 'tracker-identities'] as const,
  /**
   * A tracker issue typeahead, keyed by the binding **and** the term.
   *
   * The binding is in the key because 004 D17(c) makes it part of the issue's identity: two
   * bindings to different hosts may hold the same numeric id, and they are different issues. The
   * term is in the key for every search key's reason — the answer to `PAY-9` must not land in the
   * list showing `PAY-99`.
   */
  trackerIssues: (p: string, bindingId: string, q: string) =>
    ['projects', p, 'tracker', 'issues', bindingId, { q }] as const,
  /**
   * One issue, **as fetched with this viewer's credential** (004 D15).
   *
   * The client cache is per session and therefore already per user, which is what makes a key
   * without a user segment correct here — and it is worth saying, because the *server's* cache is
   * not: `TrackerCache` keys on `(userId, bindingId, issueId)` and 004's leak test is about that
   * one. The rule travels with the data, not with this factory.
   *
   * `bindingId` leads the id for `trackerIssues`' reason: the id alone is not an identity.
   */
  trackerIssue: (p: string, bindingId: string, issueId: string) =>
    ['projects', p, 'tracker', 'issues', bindingId, issueId] as const,
  /**
   * A case's links out to tracker issues (004 Phase 3) — the `EXTERNAL_ISSUE` `doc_link` rows.
   *
   * Under `keys.case`'s prefix, so a case write refreshes them, and **separate from
   * `keys.caseFeature`/`caseDraft` rather than folded into the case document**: the rows are
   * served by their own endpoint and a link added does not change the case's own version.
   */
  issueLinks: (p: string, caseId: string) =>
    ['projects', p, 'cases', caseId, 'issue-links'] as const,
  /**
   * A project's issues, keyed by the filter and the sort — the work table (§5's Table view).
   *
   * Under `['projects', p]` rather than a root of its own, so archiving or renaming a project
   * sweeps its issues with it. The filter object is part of the key because two filters are two
   * caches: D34 says the filter survives switching views, which is a URL concern, but a filter
   * that survived into the WRONG cache would serve one filter's rows under another's chips.
   */
  issues: (p: string, filters: IssueFilters, sort: string) =>
    ['projects', p, 'issues', { ...filters, sort }] as const,
  /**
   * Every issue list this project has, as a prefix (§250's rule) — what a create or a delete
   * invalidates, since neither knows which filters are on screen.
   */
  issuesRoot: (p: string) => ['projects', p, 'issues'] as const,
  /**
   * One issue, BY KEY and not by id — the key is what the route carries and what a person types.
   *
   * Not under `keys.issues`' filter-bearing entry: a detail read must not be swept by a list
   * invalidation that only changed which rows matched a chip.
   */
  issue: (k: string) => ['issues', k] as const,
  issueComments: (k: string) => ['issues', k, 'comments'] as const,
  issueDocLinks: (k: string) => ['issues', k, 'doc-links'] as const,
  /**
   * The composite an assistant is handed for one task (015 D4).
   *
   * Under `keys.issue`'s prefix, and wanted: the payload embeds the issue itself with its
   * caller-scoped `allowedTransitions`, so a move that changes what is legal must not leave a
   * "Moves allowed" list from before the move on screen.
   */
  agentContext: (k: string) => ['issues', k, 'agent-context'] as const,
  /**
   * The prefix every issue detail shares. A transition changes the issue AND its epic's
   * completion, so a move invalidates this root and `keys.issues` together — the epic's
   * percentage is on a different screen and must not be able to lag behind the status that
   * produced it (D8: one number, one place).
   */
  issueRoot: ['issues'] as const,
  /**
   * The project's statuses, types and legal moves. Beside `statuses` and `configurations` rather
   * than under an issue, because it is a property of the PROJECT and outlives every issue that
   * names it — an issue-scoped key would be refetched by every issue write.
   */
  workflow: (p: string) => ['projects', p, 'workflow'] as const,
  users: ['users'] as const,
  teams: ['teams'] as const,
  /**
   * The whole org chart in one entry (§9.5).
   *
   * No project segment, because the document is company-wide and its *columns* are already the
   * caller's own — the server clips them, so there is nothing for a key to narrow. One session, one
   * caller, one entry.
   *
   * **Every staffing write invalidates this**, all nine of them. It is a derived view of `teams`,
   * `users`, `role-assignments` and `projects` at once, so a write that touches any of those four
   * can move a cell here; missing one leaves the chart stale in exactly the moment somebody just
   * restaffed a project, which is the moment they opened it.
   */
  orgChart: ['org-chart'] as const,
  /**
   * Who can grant one permission — what a REFUSAL renders (`kit/StateBlock`'s `grantee`).
   *
   * Keyed on the permission alone and not on a project: the endpoint answers for the company, and
   * a company-wide holder is who a refused reader should be sent to wherever they are standing.
   */
  grantees: (permission: string) => ['grantees', permission] as const,
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
  /**
   * The caller's **own** assistants (015 D26) — deliberately not under {@link apiTokens}.
   *
   * They are the same rows on the wire, and that is the trap: `/api-tokens` is the company's whole
   * credential list behind `token:issue`, and `/me/assistants` is the caller's own Agent-role
   * subset behind authentication alone. TanStack matches an invalidation by prefix, so the obvious
   * `['api-tokens', 'me']` would have an administrator revoking somebody else's token refetch this
   * list — a request most readers of this key are refused outright.
   */
  myAssistants: ['me', 'assistants'] as const,
  /**
   * One project's audit trail (§242).
   *
   * Under the project segment, so switching projects cannot serve the previous project's rows
   * while the new request is in flight — the property `cases` carries the whole filter object for.
   */
  projectAuditEvents: (p: string, filters: AuditFilters) =>
    ['projects', p, 'audit-events', filters] as const,
  /**
   * The company's audit trail (§242).
   *
   * **No project segment even though `filters.projectId` may narrow it**, because the *scope* is
   * the company: the gate is `audit:view`, held company-wide, and keying this under
   * `['projects', id]` would have an invalidation of one project's caches drop rows from a list
   * that is not that project's. The filter travels in the object, where every other filter does.
   */
  companyAuditEvents: (filters: AuditFilters) => ['audit-events', filters] as const,
  /**
   * The company's identity providers (§222). Company-scoped like {@link apiTokens} and for the same
   * reason: a provider belongs to the company, and no project segment narrows it. Refused rather
   * than narrowed for a caller without `company:idp`, so its consumers gate the fetch.
   */
  identityProviders: ['identity-providers'] as const,
  /**
   * The domains the company claims.
   *
   * A sub-resource of the providers on the wire, and deliberately **not** a key under theirs.
   * TanStack matches an invalidation by prefix, so `['identity-providers', 'email-domains']` —
   * the obvious spelling, and the one that mirrors the URL — would be swept away by every
   * `invalidateQueries(keys.identityProviders)`. The two are edited apart, so a save on one must
   * leave the other's cache alone.
   */
  emailDomains: ['identity-provider-email-domains'] as const,
  /*
    ~~`compositeBoard: (p, mine) => ['board', p, mine]` — the **composite** board, `GET /board`,
    the four-kind card, one project or the caller's own. (~~`workItems`~~ → renamed in 012 T6 for a
    collision that had not happened yet: this module holds three keys with `board` in the name.)~~
    → **removed with `useBoard` (021 R9, T8)**, whose only caller was the retired project-scoped
    composite screen. `myWork` and `teamBoard` below still spell the root literal; `boardRoot` is
    still what every card write invalidates.
  */
  /**
   * The prefix every board shares — invalidate this and all three refresh.
   *
   * Named here rather than spelled `['board']` at each call site, which is what the three
   * entries below argue for and what ~~`workItems.ts`~~ → `board.ts` had privately. §121 phase 4
   * gave it a second module's worth of callers: quarantining a case changes a card on a board the
   * case screen does not know about, so the literal was about to exist twice.
   *
   * **The root literal is `'board'` since M1**, matching the path `GET /work-items` moved to
   * (011 D-a). The four entries keep their *names* — the argument for one root is unchanged, and
   * renaming them would rewrite eleven call sites to say nothing new.
   */
  workItemRoot: ['board'] as const,
  /**
   * §6l's cross-project personal board (§83) — **under the same `board` root as the project
   * board, and that is the point.**
   *
   * The two boards show overlapping cards: a task assigned to me in this project is on both, and a
   * review I owe is on both. So a move made on either has to refresh the other, and keeping one
   * root lets `['board']` do it as a prefix — neither board has to name the other's key, which
   * is how the invalidation would drift the first time a third board arrives.
   *
   * `'ALL'` sits where a project id goes and cannot collide with one: project ids are UUIDs.
   * `true` is not a parameter — there is no unfiltered cross-project board. The server answers
   * **400** for that request rather than narrowing it, so the key space has no entry for it either.
   */
  myWork: ['board', 'ALL', true] as const,
  /**
   * §6l's **team board** (§84) — under the same `board` root, for `myWork`'s reason.
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
  teamBoard: (teamId: string) => ['board', 'TEAM', teamId] as const,

  /*
    The PROJECT board (012 M2) — a different board, a different endpoint, a different card shape
    (D-a). Nothing here shares a root with the four entries above, and that is deliberate: the
    composite board's cards and a project board's cards are answers from two operations, so one
    invalidation must never be able to sweep the other's cache and leave the reader wondering which
    board refreshed.
  */
  /**
   * A project's boards. Under the project so archiving or renaming one sweeps its boards with it,
   * and so a board created or deleted refreshes the picker.
   */
  boards: (p: string) => ['projects', p, 'boards'] as const,
  /**
   * One board's **configuration** — its columns and its swimlane axis.
   *
   * Deliberately not under `keys.boards`: editing one board's columns must not evict the picker's
   * list, and the two have different lifetimes (D-h).
   */
  board: (id: string) => ['boards', id] as const,
  /**
   * The status × board matrix (021 R7) — **under {@link boards}, deliberately.**
   *
   * It is the one board read that is about the *set* rather than about one board, so it must go
   * stale for the same reasons the picker's list does: a board created, deleted or re-columned
   * changes which boards claim a status. `keys.boards(p)` is a prefix of this, so every
   * invalidation the settings screen already makes sweeps it and nothing new has to remember to.
   *
   * That is the opposite arrangement from {@link boardCards}, and for the opposite reason: cards
   * must survive a configuration edit, and coverage must not.
   */
  boardCoverage: (p: string) => ['projects', p, 'boards', 'coverage'] as const,
  /**
   * One board's **cards**, keyed by the filter — and under a root of its own rather than under
   * {@link board}, which is the shape D-h's reason actually requires rather than the one its
   * sentence suggests.
   *
   * `['boards', id, 'cards', filters]` is the obvious spelling and it cannot express either half of
   * what D-h asks for. TanStack matches by prefix, so a configuration invalidation (`['boards', id]`)
   * would evict the cards too and relayout the board on every column edit — the flicker D-h names —
   * while sweeping *every* board's cards after a move would need `['boards']`, which evicts every
   * board's configuration as well. Two roots give both: config and cards are invalidated apart, and
   * {@link boardCardsRoot} reaches all the cards without touching any layout.
   *
   * Two filters are two lists, and the filter lives in the URL (D34), so the key is derived from
   * there rather than from component state — a slow answer for one chip set must not land under
   * another's.
   */
  boardCards: (id: string, filters: BoardFilters) => ['board-cards', id, filters] as const,
  /**
   * Every board's cards, as a prefix — what a move invalidates.
   *
   * Not the moved card's own board only: a project has several boards over the same statuses (D2),
   * and that is what makes the M2 done-criterion — *"a card moved on the team board is in the
   * matching column on the stakeholder board"* — true on screen rather than only in the database.
   * `staleTime` is 30 s, so switching boards inside half a minute would otherwise serve the order
   * from before the move.
   */
  boardCardsRoot: ['board-cards'] as const,
  /**
   * The ranked backlog, keyed by the filter it declares — `BacklogFilters`, which since 013 T6 is
   * the board's six **plus** `releaseId` and `unscheduled` (D-k). One filter, three views (D34), and
   * three parameter sets.
   */
  backlog: (p: string, filters: BacklogFilters) => ['projects', p, 'backlog', filters] as const,
  /**
   * Every backlog list this project has, as a prefix — what the rank write targets.
   *
   * The optimistic patch and its snapshot rollback both go through this rather than through one
   * filter's key: a drag on a filtered list reorders rows in the unfiltered one too, and a rollback
   * that restored only the list on screen would leave the other holding the order the server
   * refused.
   */
  backlogRoot: (p: string) => ['projects', p, 'backlog'] as const,
  /**
   * The project's labels — beside `statuses` and `configurations` rather than under an issue,
   * because a label is the project's vocabulary and outlives every issue that wears one.
   */
  labels: (p: string) => ['projects', p, 'labels'] as const,

  /*
    Releases (013 M3).

    `readiness` under `['releases', id]` is deliberate and is **the opposite of the board's D-u
    decision above**, for a reason that is about the data rather than about consistency: a release's
    readiness has exactly one parent and changes for the same reasons the release does, so prefix
    eviction is what you want here. The board's cards were kept out of `['boards', id]` because a
    *column edit* would have evicted them; nothing edits a release without changing its readiness.
  */
  releases: (p: string) => ['projects', p, 'releases'] as const,
  /**
   * One release, outside `keys.releases`' prefix for `keys.issue`'s reason: a detail read must not
   * be swept by a list invalidation. The list is a different payload — `ReleaseSummary`, with its
   * readiness inline — and not a narrowing of this one.
   */
  release: (id: string) => ['releases', id] as const,
  /** Under {@link release}'s prefix, per the note above. */
  readiness: (id: string) => ['releases', id, 'readiness'] as const,
  /**
   * Every release detail and every readiness, as a prefix — what a write that changes readiness
   * **without naming a release** invalidates.
   *
   * `PUT /issues/{key}/test-cases` is the case, and it is the reason this root exists rather than
   * the readiness key being spelled at the call site: the payload is a story's cases, so nothing in
   * that response says which release the story targets, and the verdict of whichever release it
   * targets has just moved. Sweeping `['releases']` is the only correct answer available, and it
   * costs a refetch of a detail screen that is usually not mounted.
   */
  releaseRoot: ['releases'] as const,
  /**
   * The roadmap — the same releases the list draws, laid out against dates.
   *
   * A key of its own rather than a member of `releases`, because it is a different endpoint with a
   * different payload; but **every release write invalidates both**, since a create that refreshed
   * only the list leaves the timeline a version behind.
   */
  roadmap: (p: string) => ['projects', p, 'roadmap'] as const,
  /**
   * The cases that prove one story (H7), keyed by the issue **key** for `keys.issue`'s reason —
   * the key is what the route carries.
   *
   * Under `['issues', key]`'s prefix, so a story edit refreshes it: linking a case *is* an
   * `issue:edit`, and the story's own detail read is what the panel sits on.
   */
  issueTestCases: (key: string) => ['issues', key, 'test-cases'] as const,
  /**
   * Which stories each case verifies — `test-cases.html`'s Verifies column, read from `tracker`'s
   * side of the same fact (D-i).
   *
   * Under the project rather than under `casesRoot`: it is served from `tracker` and a *case* write
   * does not change it, while an issue's link write does. Putting it under the case list's prefix
   * would refetch the whole grid's verifications on every case edit and still miss the write that
   * actually moves it.
   */
  caseVerifications: (p: string) => ['projects', p, 'case-verifications'] as const,
  /**
   * The caller's permissions with **no project in the question** — a different answer from
   * `permissions(projectId)` and therefore a different key.
   *
   * Not `permissions('')`: an empty string is a project id that failed to arrive, and reusing the
   * project-keyed factory for the company-wide question would make a screen that renders before
   * its route params resolve read the company answer and cache it as that project's.
   */
  companyPermissions: ['me', 'permissions', 'company'] as const,
  /**
   * The cross-project case search (§249) — deliberately NOT under `['projects', …]`.
   *
   * This list belongs to no project, so an invalidation targeting one project's subtree must not
   * reach it: every write on a case invalidates `['projects', p, …]`, and a search spanning eleven
   * projects sitting under one of them would be dropped by a write in that one and kept stale by a
   * write in the other ten. `myWork` and `teamBoard` are outside that root for the same reason.
   *
   * It carries the whole filter object for `cases`' reason: a stale narrower result must not be
   * shown under a wider filter while the new request is in flight.
   */
  caseSearch: (filters: Record<string, unknown>) => ['case-search', filters] as const,

  /**
   * The cross-project **issue** search — the palette's issue group (021 D-j).
   *
   * Outside `['projects', …]` for `caseSearch`'s reason, and it is the same reason twice over here:
   * this list spans every project the caller may read, so an invalidation aimed at one of them
   * must not decide the fate of the other ten's hits. `limit` is in the key because it is in the
   * request — a six-row answer must never be served under a twenty-row ask.
   */
  issueSearch: (q: string, limit: number) => ['issue-search', q, limit] as const,

  /*
    Imports (014 M4).

    **Two keys, and there is deliberately no third.** The source-project list and the preview are
    `POST`s carrying an API token, so they are mutations and their results live in the wizard's own
    state — a query key is a cache key, and the token would then sit in the query cache, in the
    devtools panel and in any future persister. `imports.ts`' header makes the argument at length,
    because it is exactly the kind of thing somebody later "simplifies" into a `useQuery`.
  */
  imports: {
    all: ['imports'] as const,
    forProject: (projectId: string) => ['imports', 'project', projectId] as const,
    run: (importRunId: string) => ['imports', 'run', importRunId] as const,
  },

  /*
    Time (016 M6).

    `currentPeriod` is keyed by the **date** rather than by a period id, because the screen opens on
    "now" and the id is something the answer *contains* — keying by id would need a round trip to
    build the key for the first render. `approvals` is keyed by the same date for the same reason,
    and is deliberately **not** under `periods`: a PM approving somebody else's fortnight must not
    invalidate their own, and the two lists answer different permissions.

    `periods` is both a key and a prefix of `currentPeriod` / `period`, which is wanted: every one
    of the three renders the same rows, so a write that stales one stales all three.
  */
  time: {
    currentPeriod: (on: string) => ['time', 'periods', 'current', on] as const,
    periods: ['time', 'periods'] as const,
    period: (periodId: string) => ['time', 'periods', periodId] as const,
    approvals: (on: string) => ['time', 'approvals', on] as const,
    issueRollup: (issueKey: string) => ['time', 'rollup', 'issue', issueKey] as const,
    epicRollup: (epicKey: string) => ['time', 'rollup', 'epic', epicKey] as const,
    /*
      The work table's whole `Logged` column, keyed on the keys it asked for.

      It sits under `['time','rollup','issue']` on purpose, so it shares the prefix with the single
      grain above: the two answer the same figures at different widths, and a write that stales one
      must stale the other. It is NOT keyed per issue and then read row by row — fifty rows would be
      fifty requests, and TanStack would refetch all fifty on every filter change.
    */
    issueRollups: (issueKeys: readonly string[]) =>
      ['time', 'rollup', 'issue', 'bulk', [...issueKeys].sort().join(',')] as const,
  },

  /**
   * The prefix root for every time list a write must refresh (§250's argument).
   *
   * Every write in the module sweeps this one root rather than a list of narrower keys, and the
   * reason is 016 D-i: a period spans projects and an approval writes the rollup, so an approve
   * changes the approver's queue, the owner's own fortnight, that person's history **and** the
   * per-issue figures on three other screens. Enumerating those at each call site is four chances
   * to miss the one that is not on screen.
   */
  timeRoot: ['time'] as const,

  /**
   * Staffing (017 M7) — the company capacity view, the directory, one person, and the vocabulary.
   *
   * The **window is inside every key that has one**, for this file's own §250 rule: a capacity view
   * over September–November and one over the next six months are two different answers, and a key
   * that carried only `['staffing','capacity']` would serve the first under the second's heading
   * the moment the range picker moved. Same for the directory's filters, which is what makes the
   * URL-held skill chips a *cache* key rather than only a query string.
   *
   * `myCapacity` sits **under** `capacity` deliberately, at `['staffing','capacity','me',…]`: it is
   * the same computation over one person, and every write that stales the company view stales it
   * too. It is also the one read here with no staffing permission on it (017 D-m), which is a fact
   * about the endpoint and not about where its answer is cached.
   *
   * `projectStaffing` sits under `['projects', p, …]` rather than under `['staffing']`, which means
   * a project-level invalidation ALSO sweeps it. That is wanted — a project's own screen goes stale
   * for the same reasons the project does — and it is why every staffing write below sweeps both
   * roots rather than one.
   */
  staffing: {
    capacity: (from: string, to: string) => ['staffing', 'capacity', from, to] as const,
    myCapacity: (from: string, to: string) => ['staffing', 'capacity', 'me', from, to] as const,
    people: (filters: DirectoryFilters) => ['staffing', 'people', filters] as const,
    person: (userId: string, from: string, to: string) =>
      ['staffing', 'people', userId, from, to] as const,
    skills: ['staffing', 'skills'] as const,
    /*
      The window is in this key too, and it was not until T5 read the rule above against it: a
      caller's absences over September and over the next six months are two different answers, and a
      key carrying only the person would serve the first under the second's heading. Nothing
      consumed `useAbsences` when the mismatch was found — the person page reads
      `PersonCapacity.absences`, which arrives beside the capacity figures it was subtracted from —
      so this is the rule applied before the first consumer rather than after a wrong figure.
    */
    absences: (userId: string, from: string, to: string) =>
      ['staffing', 'absences', userId, from, to] as const,
    projectStaffing: (projectId: string, from: string, to: string) =>
      ['projects', projectId, 'staffing', from, to] as const,
  },

  /**
   * The prefix root every staffing write sweeps — `timeRoot`'s argument, one module over.
   *
   * An allocation written on one project changes that project's staffing screen, the company
   * capacity view, the directory's free-capacity answer, the allocated person's own capacity tile
   * on two *other* screens, and the over-allocation panel that reads the peak day. Enumerating
   * those at each call site is five chances to miss the one that is not on screen — and the one
   * that is not on screen is precisely the one nothing will refetch.
   *
   * It does **not** reach `projectStaffing`, which lives under the project's own prefix. That is
   * why the writes sweep both, rather than this root being moved to cover it: putting a project's
   * staffing under `['staffing']` would take it out of reach of every project-level invalidation
   * the rest of the app already does.
   */
  staffingRoot: ['staffing'] as const,

  /**
   * Clients (018 Mc). One root and three keys, and the third one is somewhere else on purpose.
   *
   * `optionsFor` is keyed under the **project** — `['projects', p, 'client-options']` — and not
   * under `clientsRoot`, because the invalidation is what decides where a key lives. A client
   * created from the project-settings picker must refresh that picker, and every project-scoped
   * write already sweeps `['projects', p, …]`; under `clientsRoot` it would be refreshed by client
   * writes and by nothing the project does. It is also a different question with a different gate
   * — `project:settings` rather than `client:read` — so a reader holding one and not the other
   * must not evict the other's answer.
   */
  clientsRoot: ['clients'] as const,
  clients: {
    list: (status: string, managerId: string) => ['clients', 'list', status, managerId] as const,
    one: (clientId: string) => ['clients', clientId] as const,
    optionsFor: (projectId: string) => ['projects', projectId, 'client-options'] as const,
  },

  /**
   * Billing (019 M8). One root, and two of its keys live somewhere else on purpose.
   *
   * `billingRoot` is what an invoice write sweeps. A draft stores no lines — they are computed on
   * every read (019 D-c) — so issuing one changes the four summary tiles, the budget table's
   * priced figures, the invoice list and the client page's rollup **at the same moment**, and
   * none of them can be individually invalidated with any confidence about which. The sweep is
   * therefore the root, exactly as `timeRoot` and `staffingRoot` are and for the same reason:
   * enumerating narrower keys at each call site is one chance per site to miss the one that is
   * not on screen, which is precisely the one nothing will refetch.
   *
   * `clientBilling` sits under `clientsRoot` rather than here, for the reason the comment above
   * `clientsRoot` gives — where a key lives is decided by which invalidation must sweep it, and
   * that table is on the client page, which every client write already stales. An invoice write
   * consequently sweeps **both** roots, and {@link keys} cannot express that on its own: it is
   * stated in `useIssueInvoice` and its two siblings rather than left to be discovered from a
   * stale table on another screen.
   *
   * `budgetBurn` sits under `['projects', projectId, …]` rather than here, which is
   * `projectStaffing`'s decision one module over (017's note on `staffingRoot`). It is a
   * project-scope read on a project screen, gated on `project:read` rather than on a money key
   * (019 D-h), and it must be swept by the project invalidation the overview already runs. Under
   * `billingRoot` it would be refreshed by billing writes and by nothing the project does.
   */
  billingRoot: ['billing'] as const,
  billing: {
    summary: (on: string) => ['billing', 'summary', on] as const,
    rateCards: ['billing', 'rate-cards'] as const,
    rateCard: (rateCardId: string) => ['billing', 'rate-cards', rateCardId] as const,
    budgets: (clientId: string) => ['billing', 'budgets', clientId] as const,
    invoices: (clientId: string, status: string) =>
      ['billing', 'invoices', clientId, status] as const,
    invoice: (invoiceId: string) => ['billing', 'invoices', invoiceId] as const,
    expenses: (projectId: string) => ['billing', 'expenses', projectId] as const,
  },
  /**
   * The client's own hours-and-invoices rollup — under `clientsRoot`, and see {@link keys.billing}.
   *
   * It is `client:read` rather than a money key, and it is the one billing read an Account Manager
   * makes. `amountsVisible` on the answer is what decides whether it carries amounts (019 D-i), so
   * the flag is part of the *body* and not part of the key: two readers of one client get two
   * different answers under one cache entry, which is correct — a cache is per session, and a
   * session has one reader.
   */
  clientBilling: (clientId: string) => ['clients', clientId, 'billing'] as const,
  /** The project-scope burn, with no amount on it (019 D-h). See {@link keys.billing}. */
  budgetBurn: (projectId: string) => ['projects', projectId, 'budget-burn'] as const,

  /*
    The prefix roots (§250).

    Every key above that takes a filter object or an id carries it INSIDE the array, so an
    invalidation meant for "all of this project's cases" cannot go through `keys.cases` — it needs
    the prefix on its own. Twenty-nine call sites across seven modules were therefore spelling one
    of these six prefixes by hand, and such a literal is the one thing in this file with no compiler
    behind it: rename a segment here and every hand-built copy still typechecks, still runs, and
    silently invalidates a subtree nothing is stored under. The failure has no error and no empty
    screen — just data that stops refreshing, which is the hardest defect on this list to attribute
    later.

    `workItemRoot` already made this argument for `['work-items']`. These are the same helper for
    the six subtrees writes actually target.
  */
  casesRoot: (p: string) => ['projects', p, 'cases'] as const,
  conditionsRoot: (p: string) => ['projects', p, 'conditions'] as const,
  artifactsRoot: (p: string) => ['projects', p, 'artifacts'] as const,
  plansRoot: (p: string) => ['projects', p, 'plans'] as const,
  runsRoot: (p: string) => ['projects', p, 'runs'] as const,
  aiProposalsRoot: (p: string) => ['projects', p, 'ai-proposals'] as const,

  /*
    Delivery analytics (020, M9). Under the project prefix, so switching project drops all three,
    and each key carries its own WINDOW — the release picker and the 30/90/180 selector are the
    controls a reader changes, and a key that ignored them would serve the previous answer while
    the control above it said otherwise.
  */
  analyticsBurnUp: (p: string, releaseId: string) =>
    ['projects', p, 'analytics', 'burn-up', releaseId] as const,
  analyticsCycleTime: (p: string, days: number) =>
    ['projects', p, 'analytics', 'cycle-time', days] as const,
  analyticsFlow: (p: string, weeks: number) =>
    ['projects', p, 'analytics', 'flow', weeks] as const,

  /**
   * The cross-project search, as a prefix — the companion to `caseSearch`'s exclusion from
   * `['projects', …]`.
   *
   * That exclusion is deliberate and stays: a write in one project must not drop a search spanning
   * eleven. But it left the search reachable by NO invalidation at all, so a case renamed, retired
   * or deleted stayed in a held result until something else happened to refetch it. The answer to
   * "a project write must not reach this" is a targeted root, not an unreachable key.
   */
  caseSearchRoot: ['case-search'] as const,
};

/**
 * Invalidates every list that renders a case, in both of the two places cases are listed.
 *
 * The cross-project search is a SECOND list of the same rows under a key that no project prefix
 * reaches (`caseSearchRoot`), so any write invalidating `casesRoot` must invalidate it too — and
 * saying that in one function is the point: the pair would otherwise be spelled out at eleven call
 * sites across four modules, which is how one of them ends up updated and the other does not.
 */
export function invalidateCaseLists(client: QueryClient, projectId: string) {
  void client.invalidateQueries({ queryKey: keys.casesRoot(projectId) });
  void client.invalidateQueries({ queryKey: keys.caseSearchRoot });
}

export const base = (projectId: string) => `/projects/${projectId}`;
