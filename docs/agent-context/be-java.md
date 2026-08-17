<!-- orientation-cache v1 -->

# be-java — orientation cache (TCMS)

**Last verified:** 2026-08-17 (**§241** — **the org chart: `GET /api/v1/org-chart` + `/org-chart`, executed from `docs/plans/2026-08-15-org-chart-tasks.md`. No migration, no permission seeded or moved, catalogue still 55.** **THE RULE TO CARRY: when a rule is about WHO IS ASKING, a test written about who is NAMED passes straight through it.** The plan's own §8.3 agreement test — called "the single most valuable test in this slice" — pins the SUBJECT's `ProjectAccess.canView`, and removing the disclosure filter leaves both its directions GREEN, because a leaked cell still names somebody who legitimately holds that role. The arm that reddens is a third one asking the evaluator about the CALLER. Measured by breaking it and reverting. The invariant the surface rests on: **the people axis is a company fact, the project axis is the reader's own** — person rows complete, matrix columns exactly `AdminReach.visibleProjects`, and a project the caller cannot open contributing no column, no cell and **no count**. **`EffectiveRoleNames` is now the one display implementation of roles spec §A1.1's replace-don't-union override**, so the count of §A1.1 implementations stays at three; turning replace into union reddens twelve arms across three classes, three of them in `ProjectEndpointTest`, which is the evidence it is genuinely shared. **`ProjectAccess` lost its two role-name methods for one `effectiveProjectRoleNames(userId, projectIds)`** — the plan wanted the helper called from `ProjectService` and that does not build: `project`'s declaration is `{api, tenancy}`, its own header forbids widening it, and `com.tcms.api`'s charter refuses shared helpers. **If `project` must share logic with `identity`, the port answers a further question — never a wider list and never `api`.** The two role-name queries take a `Collection<UUID>` and each grant path is its own `UNION ALL` arm, because a team grant's row carries `user_id = NULL` and nothing on it says whose cell it is — **and that same join is what makes `via` and `teamId` answerable at all**. `openapi.yaml`'s `CurrentUser.teams` claimed `GET /teams` is gated on `company:teams`; it is `company:read`, corrected in place, and **`tierClaims.test.ts` has no "gated on `<key>`" shape**, which is an open instrument gap. Gates: backend **3082 / 0 / 0**.)

---

## §241 — THE PROJECT AXIS IS THE READER'S OWN, AND §A1.1 HAS ONE DISPLAY IMPLEMENTATION

**Read this before touching `RoleAssignmentRepository`'s role-name queries, `ProjectAccess`,
`Project.myRole`, or anything that renders an effective role name.**

**`GET /api/v1/org-chart` exists** — `OrgChartService` + `OrgChartController` in `identity`, gated on
`company:read`, answering an **empty document** rather than 403 like the four other identity reads.
No migration; catalogue still 55.

**The invariant: the people axis is a company fact, the project axis is the reader's own.** Person
rows are every non-`REMOVED` member (nothing new — `GET /users` and `GET /teams` already serve that
to every member). The matrix **columns** are exactly `AdminReach.visibleProjects`. A project the
caller cannot open contributes **no column, no cell and no count**; a count is a name-free existence
claim about the company's portfolio. The boundary is enforced **twice by construction** — the column
filter, and `cells()` iterating the column ids so an absent column cannot produce a cell.

**`EffectiveRoleNames` (package-private, `identity`) is now the ONE display implementation of roles
spec §A1.1's replace-don't-union override, and of the strongest-grant ranking.** It was
`roleNames.getOrDefault(id, inherited)` in `ProjectService.view`. Do not re-inline it: the count of
§A1.1 implementations is three (`PermissionEvaluator.projectTerm` for permissions, this for display
on both screens, `roleAccess.ts` on the client) and a fourth is the one nobody keeps in step. Turning
replace into union reddens **twelve** arms across three classes — measured, §241 `OC-5`.

**`ProjectAccess` lost `projectRoleNames` + `inheritedProjectRoleName` and gained
`effectiveProjectRoleNames(userId, projectIds)`.** The plan wanted the helper called from
`ProjectService` directly; that does not build. `project`'s module declaration is `{api, tenancy}`
and its own header forbids widening it, while `com.tcms.api`'s charter refuses shared helpers in as
many words. **If you need `project` to share logic with `identity`, the answer is the port answering
a further question — never a wider dependency list and never `api`.** The old split's objection
(*only the caller knows which projects are rendered*) is answered by the caller passing them, and it
survives struck through in the port's javadoc.

**The two role-name queries take a `Collection<UUID>` now, and each grant path is its own `UNION ALL`
arm.** The single-user `user_id = :userId OR team_id IN (…)` form is gone and cannot come back: with
a list, a team grant's row carries `user_id = NULL` and nothing on it says whose cell it is. **That
same membership join is what makes `via` and `teamId` answerable at all** — they are not an extra
query. `UNION ALL`, never `UNION`: two grants become one string in the ranking, where the rule lives.
The single-user forms are `default` methods delegating to the list form, which Spring Data never
proxies, so `Project.myRole` and the chart run **one SQL statement**.

**`findCompanyRoleNames` is a third projection and deliberately does NOT filter on
`role.assignable`.** The `Company Member` baseline is dropped **by id** in `OrgChartService`. Three
seeded rows are non-assignable and only that one is a consequence of membership; filtering on the
flag is the baseline-specific-vs-general conversion `V40` §2 and `V41` §1 name as the one-line
"cleanup" that breaks four unrelated sites. Both spellings are indistinguishable today (`OC-8`).

**`AdminDirectoryService.statusOf` is package-private now**, for `peopleById`'s reason: two people can
disagree about whether an invitee here who is active elsewhere reads `INVITED`, and the answer must
not depend on which screen asked.

**`AssignmentVia` has three members and no COMPANY arm, on purpose.** A Company Admin produces **no
cell at all** and appears in `companyRoles`, because §A1.1 property 2 says authority over the company
is not a default for its projects. A fourth arm would be that misreading given a name.

**A finding about the plan's own test, worth carrying: an agreement test that pins the SUBJECT's
`canView` does not observe a READER-side disclosure boundary.** Replacing `reach.maySee` with `true`
leaves both §8.3 directions green, because a leaked cell still names somebody who legitimately holds
that role. `OrgChartEndpointTest.Agreement` has a third arm asking the evaluator about the **caller**,
and that is the one that reddens. Generalise: *when a rule is about who is asking, a test written
about who is named will pass through it.*

**`openapi.yaml`'s `CurrentUser.teams` prose was false and is corrected in place** — it said
`GET /teams` is gated on `company:teams`; it is `company:read` (`listTeams` asks `readsCompany()`).
`tierClaims.test.ts` has **no "gated on `<key>`" shape**, so nothing checks that form in any corpus.
That instrument gap is open and is its own slice.

---

## §240 — TWO REVERSALS, AND THE THREE RULES THAT CAUGHT THE COLUMN NOBODY READS

**Read this before touching `test_case_draft`, plan visibility, `reporting`'s header, or any create
that takes `Idempotency-Key`.**

**A pinned decision is not thereby a correct one.** §128 keyed the draft buffer on the case alone,
argued it in four places, and wrote `anotherPersonsSaveClearsIt` *so that a later slice would have
to change a test rather than change behaviour quietly*. That test did its job: it is what this slice
had to edit. The shape it pinned was a defect — a colleague's save deleted your unsaved draft — and
the objection §128 raised against the re-key (*"which of the four drafts do I open"*) **does not
arise**, because a reader opens their own. When a header argues against a change, check whether its
argument survives the change rather than treating it as a decision already taken.

**A field that discloses a hazard the schema no longer has is worse than no field.**
`CaseDraft.updatedBy` existed to warn a returning editor that the draft might be a colleague's. Under
`(case_id, user_id)` it can only answer "you", so it left the contract in the same slice. This is
§152's family read forwards: the stale claim is usually created by the slice that removes the thing
it describes.

**`V64` is the only migration in this repository that deletes rows, and it says why at length.**
`updated_by` is nullable and becomes half the key, so an unattributed row cannot be carried across —
and is unreachable by construction afterwards, since the read is keyed on the caller. The DELETE is
the honest form of what the re-key already does. Its blast radius is bounded because no path has
ever written a null there.

**Copy a shape the product already has before inventing one.** D2's archive is
`PUT /projects/{projectId}/archive`'s (M2) in every respect: one endpoint naming the target state
(idempotent by construction), a timestamp in the column, a **boolean on the wire**, and **no
`archived_by`** — the actor lives in `audit_event` as `plan.archived` / `plan.unarchived`. The first
draft had that column, and three rules caught the cost between them: `everyMappedColumnHasAReader`
(written and never read), `ContractConventionTest` (a schema paired with no record — which is what
sent me to `ProjectArchiveRequest` and therefore to the precedent), and `SchemaCommentTest` (a
column comment naming an identifier the database does not declare). **No single one of them would
have.**

**Archiving is a VISIBILITY act and the restraint is the design.** An archived plan still reads,
edits, approves, clones, **runs** and reports; `PlanDeleteService`'s 409 is untouched and a test
asserts that archiving does not make the delete succeed. A flag that also froze the plan would be
`CLOSED` under another name — the state `V32` removed. It is `plan:edit`, not `plan:delete`: it
destroys nothing and the same call reverses it. `findLiveInProject(projectId)` excludes archived
rows so every reader defaults to the working list; `findAllInProject` is the explicit opt-in, and
`findLiveInProject(projectId, planId)` deliberately does **not** exclude them, or un-archiving would
be unreachable.

**A report's freshness needs both sides and neither can write the sentence.**
`ReportComputation.freshness()` declares `LIVE` (the default, so a new fact-based report cannot
inherit a claim that its numbers are current) or `PROJECTED`; the *instant* is the job's watermark,
which only `ReportJobRunner` holds. `ReportHeaders` composes them. This is `ReportHeader`'s own rule
about the aggregation policy — an item only the report can answer stays with the report — applied to
the half only the job can answer. The projected sentence names the **refresh** rather than the
schedule, because the runner refreshes inside the computing transaction: a *generated* report is
exact while the same table read outside a job is a tick behind.

`ReportRenderingTest.nothingInTheHeaderIsSilentlyDropped` covered the new field the moment it
existed — that is what a computed check buys — with one trap: its premise is that every header value
survives **verbatim** in all three formats, so its fixture value carries no apostrophe. HTML escapes
one.

**`V66` is the tenth `MAX_IDEMPOTENCY_KEY` and the first where the client half shipped first.** §205
found `packages/mcp` declaring and sending a key `ReportController` never read — a header that looks
right and does nothing, which is the exact failure the parameter's own description warns about,
arriving from the client side. A replay answers **200** with the job it already queued, because
`202` means work was started. What a duplicate costs here is not a row: a job refreshes
`execution_fact` for the whole company, computes, renders and writes **two** artifacts.

**D3's deliverable is a note, and the note is load-bearing.** `TestRun`'s no-`@Version` argument
rests entirely on there being no `PATCH /runs/{runId}`. Its header and the contract now say that a
slice adding run edit maps the version, serves `lockVersion` and takes an `If-Match` **in that same
slice**, and keeps the two result writers off the counter — which is what `startIfNotStarted`'s
predicate and `@DynamicUpdate` already do, and why they must survive rather than be replaced by the
annotation.

---

## §238 — A SUITE'S COMPOSITION IS THE RESOLVED UNION, AND THE LOSER IS UNFINDABLE AFTERWARDS

**Read this before touching `SuiteVersions`, any case write path, or anything that reads
`suite.current_version_no`.**

**One seam owns the bump.** `SuiteVersions.reconcile(projectId, suiteIds, authorId, now)` resolves
`SuiteMembership.caseIdsIn`, hashes it, and — only if the hash moved — writes the `suite_version` row
and calls `Suite.applyCompositionVersion`. It is idempotent, order-independent and safe to
over-declare, which is deliberate: the alternative is each call site working out exactly which suites
it moved, and the one that gets it wrong fails silently.

**The two-phase read is the part that is easy to delete and impossible to notice.** A case write must
call `holdersBefore` *before* mutating anything, then `reconcileAfterCaseWrite` with that set. A
suite that GAINS a case can be found afterwards; a suite that LOSES one — refiled, untagged,
soft-deleted — cannot, because after the write nothing relates the two rows. **An implementation that
reconciles only the after-set passes every gaining assertion**, and every emptied folder stays pinned
to a composition it no longer has. `SuiteCompositionVersionTest.theLosingSuiteAdvancesToo` is the
observer; mutation row `§238 SV-1` is the measurement.

**Seven call sites, and the one that is deliberately absent.** `SuiteWriteService` create and edit;
`CaseCreateService`, `CaseUpdateService`, `CaseDeleteService`, `CaseBulkService`. **`CaseCloneService`
is NOT one** — it builds a full create request, tags and suite included, and delegates to
`CaseCreateService`, so the create path already covers it. Adding a reconcile there would be a second
one on the same transaction. The bulk path skips the whole thing on `dryRun`.

**What does NOT bump, and each has a test:** a rename (a name is content — the half of the old rule
that was never the debt), a status transition (see below), a no-op suite save, a case content edit,
and **filing a case into a suite that already claimed it through a tag rule**. That last one is the
reason the snapshot is the resolved union rather than the suite's definition: the filed arm genuinely
changed and what the folder holds did not.

**The status refusal is a decision, not an omission.** C1's wording says "membership/status change";
`Suite.applyStatus` still declines. An approval that minted a version would make
`SuiteStatusService`'s pin name a version **the transition itself created**, when what an auditor
needs back is the composition that was approved — the one standing before the move.
`TestPlan.applyStatus` declined the same bump in §73. Both directions are asserted
(`approvingDoesNotMintItsOwnVersion`, `thePinMoves`, `aLaterChangeDoesNotRewriteThePin`).

**`applyCompositionVersion` stamps no timestamp and no actor.** Every other mutator on `Suite` is
somebody editing that row; this one is reached from the other direction, so re-stamping would sort an
untouched folder to the top of "recently updated" and name its last editor as whoever tagged a case
matching one of its rules. Attribution lives on the `suite_version` row's `created_by`. Nothing
asserts this — it is mutation row `§238 SV-4`, the cheapest gap in the queue to close.

**Two populations that are NOT backfilled and must stay that way.** Suites predating `SuiteVersions`
have no version row at all; suites created between then and §238 have a v1 that says **empty** even
when the create carried `caseIds` (`writeInitial` hard-coded `List.of()`, unnoticed since §98).
Correcting either needs a composition nobody recorded. `CaseVersions`' v1-gap argument governs both.

**If you add a fixture here:** suite review is **off by default per artifact type**, so no `review`
row is written and `subject_version_no` is unobservable until `project_settings.review_enabled_suite`
is set; author-cannot-approve-own-work means the approver cannot be the suite's creator; and a **tag
is project-wide**, so two tests sharing a tag string in a class that does not roll back will claim
each other's cases.

## §223 — A DIRECTORY IS A SECOND KIND OF PROVIDER, AND THE PROTOCOL LIVES IN `auth`

**Read this before touching anything LDAP-shaped.** The plan is
`docs/plans/2026-08-15-m4-external-identity-ldap-ad.md` — D1–D9 and the seven traps are decided
there; do not re-litigate them from this summary.

- **`auth/LdapDirectory`** — the only class that speaks LDAP. In `auth` and not `identity` because
  `identity` may not reach the network (ArchUnit). Prevents: filter injection (escaped `{0}`),
  binding as an empty password (an unauthenticated bind that most directories answer with success),
  picking a person by result ordering when a filter matches twice, following a referral to a server
  the response chose, and a subject that is platform-dependent mojibake (`objectGUID` is 16 binary
  bytes, little-endian in its first three fields). Its `environmentFor` is the trust seam: the JVM
  default trust store validates the directory's certificate, tests override the method, and there is
  deliberately no per-provider PEM column.
- **`auth/BindThrottle`** — 5 failures per (provider, identifier), then refuse without binding.
  Prevents: locking out a customer's Active Directory accounts from the internet. `RateLimitFilter`
  cannot do this — it counts API tokens only.
- **`auth/ProviderResolution`** — V53's pre-authentication lookup, extracted from `SsoLoginService`
  because both sign-in flows ask it. Answers a company and `enabled`, never the configuration.
- **`ck_identity_provider_shape` (V57)** — the conditional replacement for `client_id NOT NULL`,
  which had to be dropped so an LDAP row could exist. Prevents: an OIDC provider with no client id,
  which fails at the token exchange days later rather than at the insert.
- **`LdapAutoConfiguration` is excluded** (`spring.autoconfigure.exclude`). Boot triggers it on the
  class alone and wires one application-wide `ContextSource` from `spring.ldap.*` — defaulting to
  `localhost:389` — whose actuator health indicator reports the instance DOWN. This product's
  context sources are per provider row, built in `LdapDirectory` at sign-in time. Do not "fix" a
  DOWN health probe by configuring `spring.ldap.urls`.
- **The test recipe is `TlsTestDirectory`** — an in-memory LDAPS server whose certificate carries
  explicit `localhost`/`127.0.0.1` SANs via `ManageCertificates` directly, because the JDK's LDAPS
  endpoint identification runs even under a trust-all manager and the convenience generator
  silently drops `localhost` from its SAN list. A trust-manager-only `SSLUtil` is the other silent
  failure: no server certificate, every handshake refused, and six of seven tests green against a
  server nothing had ever connected to.

## §228 — THE CASE'S OWN STATUS ENDPOINT, AND WHY IT IS NOT A ONE-ROW BULK OPERATION

**Read this before touching anything that writes `test_case.lifecycle_status`.** Full account:
`docs/handoff/228-the-status-a-case-could-not-be-given.md`.

- **There are now TWO writers of that column outside a create** — `CaseBulkService` and
  `CaseStatusService` — and they share `LifecycleLadder` and nothing else. That is deliberate; what
  must never happen is a third table or a second `Keys` literal.
- **`CaseStatusService.KEYS` IS `BulkOperations.CASE_KEYS`, read rather than re-spelled.** A second
  `new LifecycleLadder.Keys("case:edit", "case:approve")` is two endpoints gating one edge on
  different keys, both looking correct. `§227 PM-9` is the mutation.
- **The refusals differ ON PURPOSE and this is the thing to not "fix".** A row whose edge costs more
  than the caller holds is **skipped with a reason** by the bulk path — right, because a selection
  is a filter's output and may hold rows of several statuses — and **403** here, because one case is
  not a selection. `CaseBulkEndpointTest.SingleCaseStatus.engineerIsForbiddenWhereBulkWouldSkip`
  drives one edge through both endpoints and asserts both shapes; it goes green if this endpoint is
  ever delegated to the other.
- **Per-act audit verbs here (`case.submitted`, `case.approved`, `case.unapproved`,
  `case.withdrawn`, `case.revived`, `case.deprecated`), `case.bulk.*` there.** Plan §7 lists the acts
  by name; the `bulk` infix stays because a batch is a different act.
- **A status move writes NO `test_case_version`** — same call the bulk path makes through
  `changesAuthoredContent`, and the reason is `CaseVersions#hash`: a version with an unchanged digest
  makes the *next* ordinary save attribute its own content to this move.
- **`CaseBulkEndpointTest` covers both endpoints now**, filename unchanged so citations resolve. Its
  fixture (a case in each ladder state, the three personas) is what a second class would have had to
  copy.
- **Two of this slice's mutation rows predict ZERO reds and are the honest gap**: nothing in that
  class reads a `review` row after a single-case move (`§227 PM-12`), and §6g's review policy is
  unobservable there because the fixture has review switched off (`§227 PM-11`).
- **`TestCaseDetail` is not a schema.** The case detail's contract schema is `TestCase`. A `$ref` to
  a name the contract does not have surfaces in the SPA harness as
  `Cannot read properties of undefined (reading '$ref')`, which names nothing.

## §227 — THE REPORT LIST, THE MODEL'S OWN GATE, AND THE REAPER THE HEADER ARGUED AGAINST

**Read this before touching `com.tcms.reporting`.** Full account:
`docs/handoff/227-the-reports-that-stopped-disappearing.md`. Audit and ledger:
`docs/audits/2026-08-15-flow-review-and-improvements.md` §0.

- **`GET …/reports/jobs/{jobId}/model` (`report:view`) is the on-screen view's own route.** It used
  to be the download route (`report:export`), which made reading a report cost exactly what carrying
  it away costs — the §6e persona `ReportEndpointTest`'s `Report Reader` fixture exists to express.
  `ReportJobView.modelUrl` points here; `downloadUrl` still points at `…/artifacts/{id}/content` and
  still costs `report:export`. **Do not collapse them.** `ReportEndpointTest.Model` asserts the pair
  in both directions, which is the only thing that makes either half falsifiable.
- **`GET …/reports/jobs` (`report:view`) serves the PROJECT's jobs, not the caller's** — the
  opposite call to `ReportDefinitionService.list` in the same module, and deliberate:
  `report_job.requested_by` is nullable since §66, so a "mine" reading hides every report requested
  by somebody who has since left. Bounded in the repository *method name*
  (`findTop100By…`), because the operation declares no paging and a page size arriving as an
  argument would be a knob with one setting.
- **`ReportJobView.modelUrl` keys on `modelArtifactId`, never on `status`.** The two agree in the
  designed system; the artifact id is the one that is false for a job completed by a deployment
  older than §88's stored model, and that is exactly the row whose link would 404.
- **`ReportJobRunner.failStuckJobs` exists and the header's no-reaper paragraph is struck through.**
  Every word of that argument was about *retrying*, and this does not retry: a claim older than
  `tcms.reporting.stuck-job-timeout` (`PT15M`, refuses non-positive) goes to `FAILED` with a
  sentence, and a re-request is still a new row. **Its count is deliberately outside
  `MAX_JOBS_PER_TICK`** — folded in, a company that lost twenty-five jobs to a restart spends its
  whole next tick on rows nobody is computing. The cutoff is `startedAt`, not `requestedAt`.
- **The sweep is bounded by the open company and nothing else** — same unqualified query shape as
  the claim, same RLS-plus-filter reliance, same one-worker assumption. A second replica breaks both
  together, which is where that work starts.
- **`ReportJob` gained a required `requestedAt`.** Any double or fixture serving a `ReportJob`
  without it fails the contract-body comparison in `frontend/` and `packages/`.

## §225 — THE EVALUATOR MEMOIZES PER TRANSACTION, AND THE GRANT TABLES REFUSE A MIS-SCOPED SHAPE

**Read this before touching `PermissionEvaluator`, before writing a service that mutates a
grant-bearing table, and before hand-writing a `role_assignment` or `instance_role_assignment` row
in a test or migration.**

### The memo, its key, and the one obligation

`PermissionEvaluator` answers a repeated question once per transaction: a map bound through
`TransactionSynchronizationManager`, keyed `(scope, companyId, userId, projectId, tokenPrincipal)`,
unbound at completion. The three public entry points route through `memoized(...)`; the resolution
bodies moved to `resolveOnProject` / `resolveCompanyWide` unchanged. Outside a transaction it
resolves directly and memoizes nothing — no production path reaches that, every entry point is
`@Transactional`. **It is NOT the cross-request cache the `role_assignment` table comment
specifies**, and the evaluator's header argues both directions: do not widen the memo's lifetime,
and the real cache is its own future slice (grants-epoch sketch in handoff §225).

**The obligation: a service that writes `role_assignment`, `instance_role_assignment`,
`team_membership`, `role_permission` or `company_membership` and asks the evaluator again in the
SAME transaction must call `forgetMemoizedAnswers()` between the write and the question.**
`RoleGrantService` is the only such service — the revoke path's self-lockout check is the site that
breaks first (it re-asks the gate question after the delete; the memo would answer from before it),
and the grant path clears before `viewOf` re-derives the caller's reach. `RoleGrantEndpointTest`'s
lockout tests redden if the revoke-path call is removed;
`PermissionEvaluatorTest.TransactionScopedMemo` (4 tests) pins the memo itself, including the
pre-write staleness as a stated contract. Every other evaluator test calls outside a shared
transaction, so none changed.

### `V56` — the shapes the triggers refuse, and why triggers

`RoleGrantService.scopeError` was the only refusal of a COMPANY-scope role paired with a
`project_id`, and nothing refused a non-SYSTEM role in `instance_role_assignment`. Neither row
errors when read — the first resolves its keys as project permissions AND trips
`hasProjectScopedAssignment`'s override switch (replacing the subject's inherited company-wide
default: a row written to widen quietly removes), the second confers its keys instance-wide with no
company gate. `V56` adds `BEFORE INSERT OR UPDATE` triggers on both tables — triggers because
`role.scope` lives in another table and the denormalized-column alternative needs every writer to
populate it — plus a sweep of existing rows that iterates companies under a transaction-local GUC
(`FORCE` RLS covers the migration role; `V15`'s backfill is the precedent). `RoleScopeShapeTest`
(schema package, raw SQL below the service layer, `5c09e000` fixture prefix) drives all three
refusals and the legal shapes. Mutation rows `§225 PM-1..PM-3`.

---

## §217 — SIGN-IN THROUGH SOMEBODY ELSE'S ISSUER, AND THE FOUR RULES AN EARLY ENTITY TRIPS

**Read this before touching `com.tcms.auth`'s OIDC classes, before adding a pre-authentication
read, and before landing an entity a phase ahead of its callers.**

### An entity with no callers fails FOUR architecture rules, not one

§217 phase 2 landed `IdentityProvider` and `UserIdentity` and ran `RepositoryTransactionTest`,
which is what its plan named. `./gradlew build` was red for two commits: `everyMappedColumnHasAReader`,
`everyMappedColumnHasAWriter`, `everyDeclaredRepositoryMethodHasACaller` and
`everyEntityIsWritableByTheProduct` all report the same absence from four angles, and none of them
is the class a persistence plan thinks to run.

The mechanism is worth knowing because it decides what "give it a reader" means here:

- **A self-write counts only if the writing method has callers** — `isWrittenInJava` accepts a `SET`
  from the field's own class when `origin.getAccessesToSelf()` is non-empty. So `UserIdentity.link`
  assigning six fields writes nothing until a product class calls `link`.
- **A getter in the entity's own file is not a reader.** `namedIn(columnText, own, column)` excludes
  the file, so the reader has to be another `src/main` class, a repository method name containing
  the capitalised field name, or the column name in someone else's native query.
- **The reader corpus is TEXT with writes, comments and mapping annotations stripped**, which
  over-matches one way on purpose: `SsoLoginAttempt.createdAt` and `UserIdentity.lastLoginAt` both
  read as live because `created_at` and `last_login_at` appear in unrelated queries. Both were
  written into `READERLESS_BY_DECISION` on the way through and both had to come back out —
  §162's "an exemption must still exempt something" catching a guess.

`UserIdentity.emailAtLink` and `.linkedAt` stayed, and the first is the interesting one: a reader
for it would be the defect. The key is `(provider, subject)` precisely so that a corporate rename
does not fork somebody into a second account.

### The plan's own trap 1, one endpoint further along than the plan noticed

`V53`'s header is emphatic that every SSO read happens before company context exists, and it built
`tcms_resolve_identity_provider` for the authorize leg. Then the plan specified `POST /auth/providers`
— email in, buttons out — resolved through `company_email_domain`, which is company-owned and FORCE
RLS-protected. Under RLS that read returns zero rows for an anonymous caller, so SSO would have been
**undiscoverable, permanently, presenting as an empty button list rather than an error**.

`tcms_resolve_providers_for_domain` is the answer. It lives in **`V55`, not `V53`** — §217 phase 4
appended it to `V53`, which the lab had already applied, and the next deploy failed to boot on a
Flyway checksum mismatch; `V53` is now back to its applied bytes. **An applied migration is
immutable and no local gate can tell you it has been applied** — every backend test migrates a fresh
container, where the edited file and the original are equally valid.

It is `V8`'s shape, not `V7`'s: **`SECURITY
INVOKER`, walking the ACTIVE companies with `set_config` and asking the ordinary fully-policed
question in each.** `SECURITY DEFINER` does not work on these tables and the reason is the one this
repository has paid for once — `FORCE ROW LEVEL SECURITY` subjects the table's owner to its own
policies, so a definer function owned by `tcms_owner` matches an unset GUC and returns nothing.

**The generalisation, and it is the thing to carry:** when a slice adds a pre-authentication surface,
enumerate *every* read it makes, not the one the plan drew a box around. There are now two resolver
functions and three instance-level tables (`membership_invitation` `V6`, `api_token_lookup` `V36`,
`sso_login_attempt` `V53`), and the family exists because this mistake is easy and silent.

### The split is forced: `identity` holds the policy, `auth` holds the protocol

`identity` may not reach the network and `auth` may not own a table, so:

| `com.tcms.identity` | `com.tcms.auth` |
|---|---|
| `FederatedIdentityService` — link / provision / refuse | `OidcDiscovery`, `OidcClient`, `OidcIdTokenVerifier` |
| `IdentityProvider(Service·Controller·Input·View)`, `ProviderSecrets` | `SsoLoginAttempt(·Repository)`, `SsoLoginService`, `SsoController` |
| `CompanyEmailDomain(·Repository)` | `OidcProperties`, `OidcConfiguration` |

`AuthenticationService` and `SecurityConfiguration` are **untouched**, deliberately. `/api/v1/auth/**`
is already `permitAll` by a wildcard matcher, and registering an external issuer on
`oauth2ResourceServer` would let an IdP-minted token authenticate against the whole API directly.

### `FederatedIdentityService` — the order IS the policy

1. `findByProviderIdAndSubject` hits → done, **and email is never read**. Everything else is below
   this line for that reason.
2. an account holds the address, the provider auto-links, **and the issuer said verified** → link.
3. nobody holds the address and the provider provisions → create.
4. anything else → one `Refused`, with no reason field to leak one.

**JIT grants exactly what accepting an invitation grants** — account, ACTIVE membership,
`tcms_grant_company_read_baseline`, and **no `RoleAssignment` at all**. The plan's own test asserted
a Viewer grant; `InvitationService.accept` creates none, so that would have made SSO grant *more*
than the flow it claims parity with. This is the third call site of the `V15` function and
`MembershipInviteService`'s header argued against needing one — the argument was that accepting is
where a person joins, and a JIT membership joins without ever passing through an acceptance.

`AppUser.federate` is new: `ACTIVE` from the first moment and **no password**, so
`AuthenticationService`'s null-hash refusal keeps the only way in the provider that created it.

### `ProviderSecrets` is the SECOND AES-GCM implementation — AES-GCM ×2

`integration.SecretCipher` is the first and this is a copy, because `identity` may not depend on
`integration` and there is nowhere shared to put it: `api`'s charter takes contract shapes and
refuses services, and a sixth foundation module is a decision rather than an import. Recorded as a
running tally in this package's tradition, so **the third caller arrives with the extraction**.

Its own asymmetry is the confusing part and is written at both ends: `ApiTokenSecrets` and
`InvitationTokens` **digest**, because those credentials are only checked; this one **encrypts**,
because a token exchange has to present the plaintext. Key is `${TCMS_IDENTITY_SECRET_KEY}` — no
colon, §108 — and `IntegrationTestBase` supplies one for every context in the suite. **That is two
required secret keys now**; a third module wanting one should extract instead.

### What is built and NOT observed — six of the nine mutation rows are expected green

The flow has no integration test. `FederatedIdentityServiceTest` (9) and `OidcIdTokenVerifierTest`
(8) are real and pure, but they sit either side of an unobserved wire: **the verifier is proved to
refuse a wrong nonce and nothing proves `SsoLoginService` passes the attempt's nonce to it.** Same
for the single-use `state`, the `enabled` re-check inside the context, the URL fragment, and the
whole of `IdentityProviderService` — the CRUD is gated, validated and audited, and `IdentityProviderServiceTest`
does not exist. `docs/verification/backend-mutation-checks.md` §217 FD-7…FD-15 has each with what a
green build would mean. Task 8's Keycloak in compose is what changes this.

**`§218 NC-5` and `§217 FD-15` are the same missing rule, recorded twice in two days**: nothing in
this repository asserts that a credential is absent from a response schema. The pairing rules
compare the two sides to each other, so adding `clientSecret` to both the schema and the view
satisfies them. The third slice to want it should build it.

---

## §218 — THE OUTBOX'S PAYLOAD ARM HAS PRODUCT CALLERS, AND THERE IS A THIRTEENTH MODULE

**Read this before adding an outbox event, before touching `com.tcms.integration`, and before
putting anything recoverable in a column.**

### `Outbox.record` had no product caller for eighty sections, and the debt it named is now paid

`Outbox#record` — the payload-carrying, past-tense arm — was built in §131 and called by nothing but
`OutboxDispatchTest` until this slice. `ArchitectureRulesTest.UNEXERCISED_BY_DECISION` held it as its
single entry; **that entry is gone and the map is now empty** (kept as a map, so the next unexercised
seam still costs an argument somebody has to defend).

Three files predicted this slice without naming it, and all three are now corrected rather than left
standing: `Outbox#record`'s "what the first real caller owes", `OutboxMessage#payload`'s "stated in
the conditional because no such pair exists (§153)", and `OutboxEvents`' "the first name whose
handler wants to know *which* row moved".

**Why it took this long, and it is the rule for deciding which arm a new event uses.** Every event
type before these was consumed by a **whole-company recompute**, which takes no arguments — so there
was nothing truthful for a payload to carry and `recordCoalesced` was right both times. A
notification is the opposite: the payload **is** the message, because a consumer in `integration`
may not read `test_run` or `work_item` to find out anything the event did not say. *"Something in
this company finished"* is not a notification.

### THE PIN IS THREE TESTS, NOT TWO, AND THE THIRD IS THE ONE THAT MATTERS

Two records declare each payload, one per side, because plan §3 forbids the two modules from sharing
a class and `ArchitectureRulesTest` reads every import.

| Test | Fails when |
|---|---|
| `com.tcms.execution.RunCompletedPayloadTest` (+ `work.ItemAssignedPayloadTest`) | the producer's field names or values move |
| `com.tcms.integration.RunCompletedPayloadTest` (+ `ItemAssignedPayloadTest`) | the consumer stops requiring a field, or starts requiring a new one |
| `com.tcms.integration.NotificationDeliveryTest` | **the two no longer meet** |

**Two pins updated in step redden neither**, which is exactly what renaming a field looks like. That
is not a hole in the design; it is why the third test drives the real service, writes a real `outbox`
row, runs the real dispatcher and asserts a delivery attempt. If you add a fourth event type, all
three are the cost.

The consumer pin asserts the record's **component list** as well as the parse, and the asymmetry is
deliberate: an *unknown* field is tolerated (a producer may widen before every consumer wants it),
an *absent* one is not, because it deserializes to null or zero in silence and a renderer then says
"0 failures" about a run that failed.

### Two new event names, both occurrences, and one the plan asked for that has no producer

- **`execution.run_completed`** — `RunStatusService.move`, inside the closing transaction,
  **`COMPLETED` only**. Aborting is not completing and reopening is not an ending; one "the status
  moved" name would push the question onto every subscriber and one of them would announce a
  reopened run as finished. `onlyCompletionIsAnOccurrence` pins it.
- **`work.item_assigned`** — `BoardService.create`, and **nothing at all for an unassigned task**.
- **`work.defect_created` IS NOT BUILT AND MUST NOT BE.** `defect:create` is seeded and there is no
  defect entity, table or filing path anywhere in `backend/src`. A constant in a foundation module
  with nothing recording it is the writerless shape one level up from the column `V54` drops.

**The payload carries no display name and no previous assignee, and both absences are decisions.**
A name is a display decision plan §6u makes per company membership, and a name frozen into a payload
is the one they had when the row was written — so the handler resolves it through
`project.PeopleDirectory`. A previous assignee does not exist: `work.WorkItem` has exactly one
mutator and it moves a card between columns, and its own header records that editing an assignee
"has no affordance anywhere in the SPA". **Creation is the only assignment this product can
perform**, so that is the only producer.

### `integration` is the thirteenth module and it is NOT a foundation module

Declares `{"api", "tenancy", "project"}`. The plan also named `identity` and it is **left off**: a
person's display name arrives through `project.PeopleDirectory`, which is that module's port
precisely so a module which may not import `identity` can still ask *who is this*. A declaration is
a fact about code that exists, never a forecast.

It fails §125's second property for a foundation module outright — a notification channel is a
domain concept with a lifecycle, an owner and a permission — so **nothing may depend on it**, and
`ArchitectureRulesTest.nothingDependsOnIntegration` pins that from the other side, alongside the
`provisioning` and `auth` leaf rules. Modulith would only catch a module *declaring* the edge; that
rule also catches the composition root and a module widening its own declaration in the same commit.

**The constraint is the design.** `integration` may see neither `execution` nor `work`, and every
fact it renders belongs to one of them — so the outbox is not one mechanism among several here, it
is the only one. The temptation the leaf rule exists for is specific: calling
`NotificationDispatchService` from `RunStatusService` is one line shorter than recording an event,
and it puts a POST to a third party inside the transaction that closed the run, which is precisely
what `V1`'s comment on `outbox` forbids.

### `V54`, three tables, and a `V1` column with no writer since M0

Policy count **64 → 67**; `SchemaConventionTest` pins it. `team.notification_channel JSONB` is
**dropped** — no writer, no reader, no mapping, and leaving it beside a real `notification_channel`
table would be two spellings of one concept with the dead one first in `V1`.

- **`notification_channel`** — unique `(company_id, project_id, name)`. `kind` is `updatable = false`:
  changing a Slack channel into an email one keeps the name and history while replacing everything
  they were about. `lock_version` **is** a real `@Version` here, unlike `work_item`'s — a channel
  edit is a field edit typed over what somebody read, which is `ApiToken`'s rubric answering the
  other way.
- **`notification_subscription`** — `min_failures` is **a predicate on the event, not an event**.
  A `RUN_FAILED` event would have `execution` record a distinct name against a threshold configured
  in `integration`, which the module layout forbids and which breaks anyway the moment two
  subscribers disagree about what a bad run is. `0` is every run; **null failures matches every
  threshold**, because a filter that silently silenced a channel is indistinguishable from one that
  never fires.
- **`notification_delivery`** — the operator's log **and** the idempotency record. Unique
  `(company_id, channel_id, event_id)`, and `NotificationLedger.beginAttempt` refuses a second
  attempt against a row already `SENT`. Without it one crash between the POST and the settle is a
  duplicate message, and the outbox's ladder makes it up to eight.
  **`event_id` is minted by the PRODUCER and carried in the payload**, not the outbox row's id —
  `OutboxMessage` deliberately hands a handler no row identity.

### The credential is the first recoverable secret in this codebase

A webhook URL **is** the authentication for the room it posts to, and unlike an API token a digest is
unavailable because the value has to be *used*. So: AES-GCM, nonce prepended to the ciphertext, key
from `${TCMS_INTEGRATION_SECRET_KEY}` — **no colon**, §108's lesson taken literally — and
`IntegrationProperties` refuses anything that is not Base64 of exactly 32 bytes rather than hashing a
passphrase, because a short key makes a perfectly valid AES key and nothing the running product does
would reveal it.

**This is a required property with no default, so EVERY context in the suite needs one.**
`IntegrationTestBase` supplies it beside the JWT secret, with the same warning attached: that
override replaces the shipped expression outright, so no test can see a defect in its *text* —
`ConfigConventionTest` is what reads that.

Four rules, each with a test:

1. **Never returned.** `NotificationChannelView` has no `secret` component and must never get one;
   `secretHint` (the last four characters, masked) is all that leaves the module.
2. **Never in an outbox payload.** Asserted in the producer pin, not only asserted in prose.
3. **Never in a delivery error.** `SecretRedaction` runs two passes — the exact secret, then any
   `http(s)://` loses its path — before anything durable sees the text. **This is the leak that is
   invisible in a green run**: an HTTP client's message quotes the URL it posted to, nothing fails,
   and a live credential sits in a table an operator browses and a backup copies.
4. **Never in `outbox.last_error` either.** `NotificationDeliveryFailure` names channels and carries
   no cause, because that exception is what `OutboxDispatcher` writes into the row.

**`ChannelConfig` is the only place a plaintext URL exists**, it is built immediately before one
delivery, and it has no `toString` override *on purpose* — a redacting one would make the type look
safe to interpolate and the next component added would not be.

### The write path is package-private, and that is a rule rather than a style

`NotificationChannelService`'s methods are package-private because
`everyPublicBeanMethodIsExercisedInProduction` refuses a public bean method whose only callers are
tests, and `NotificationChannelController` — the caller, landed in phase 2 — is in the same package.
**`@Transactional` on a package-private method works here** — Spring Framework 6 detects it for
class-based proxies, and `tenancy.OutboxLedger.claim` is the in-repo proof. Do not "fix" it by
widening visibility.

`NotificationLedger` is split out of `NotificationDispatchService` for `OutboxLedger`'s exact reason:
a `@Transactional` method called from another method of the same bean does not go through the proxy,
so the annotation does nothing — no `app.current_company`, and RLS answers zero rows. It is also
what keeps a connection from being held open across a POST.

### One model, three renderers

`RenderedNotification` is the model; the three adapters are the renderers. `SlackWebhookAdapter.body`
and `TeamsWebhookAdapter.body` are **statics separate from the POST**, so a document can be asserted
without an HTTP server. What the three disagree about is the argument for the port existing: Slack's
header block refuses markdown and needs `text` beside `blocks` or it pushes as "This content can't be
displayed"; Teams requires `summary` and takes facts as objects; email has no room at all.

**The email adapter throws where `identity.SmtpInvitationMailer` swallows**, and the difference is
recorded at both: a failed invitation must not undo a provisioning run, and a notification has no
other route and sits on a row that will retry.

### Phase 2 — the contract and the REST surface, and the read gate is the decision in it

Six operations under `/projects/{projectId}/notification-channels`, all on
`NotificationChannelController` in the same package as the service it drives. **Both halves cost
`integration:manage`, reads included, and phase 1's javadoc said the opposite** — it is struck
through in place rather than deleted, per the working agreement.

**The argument, because it is the reusable half.** `getProjectSettings` is deliberately readable by
anybody who can see the project, and the reason is that *every* client has a decision to make from
it — whether to render a review panel — while none of those readers is an administrator. Neither
half transfers here. Nothing in this product branches on whether a channel exists, so there is no
reader who needs the answer and cannot have the key; and what the reads serve points the other way
— `secretHint` is the tail of a live credential, `recipients` are people's addresses, and a
delivery row's `error` is whatever a remote endpoint said. So the reads went to
`ProjectService.readGranted`: **visibility still answers 404, the permission half answers 403**,
which is `report:view`'s (§87) split and the reason that method exists. Generalised: *read the gate
off what the response carries, not off the screen the endpoint belongs to.*

- **The delivery log hangs off the CHANNEL** — `…/{channelId}/deliveries`, not a project-level list.
  A delivery is only ever asked about as one (*is this webhook working*), and deleting a channel
  takes its history with it, so a project-level log would outlive the channels it names.
- **Subscriptions travel in the channel body, not as a sub-resource** — `updateProjectSettings`'
  argument: the form shows every control and submits every control. The array is the **complete
  set**, reconciled, because an upsert-only sub-resource can add and change a subscription and can
  never take one away.
- **`secret` is on the input schema and on NO response schema**, and `NotificationChannel` has no
  property that could carry it back. Absent on the `PUT` means **unchanged** — the one field in this
  API where an omission is not a clear, and the reason is that the screen has nothing to send back.
- **NO `Idempotency-Key` on the create**, on `POST /projects/{projectId}/conditions`' terms: there is
  no idempotency record behind the path, and §205's finding is what makes declaring one anyway the
  expensive mistake — a client that trusted the header would replay a timed-out create and get a
  second channel that delivers every notice twice.
- **`If-Match` on the `PUT` and none on the `DELETE`.** `Long`-bound so a malformed header is a 400
  from the binder rather than a skipped precondition. A delete names an absolute destination, so a
  precondition there could only refuse somebody who wanted exactly what happened.

**Five schemas and two enums had to be registered in the contract tests, and that is the tax on any
new surface here.** `ContractConventionTest.PACKAGE_PRIVATE_OWNERS` grew from five entries to ten
(the whole `integration` module is package-private by design, so it is `Map.ofEntries` now);
`ContractEnumTest.OWNERS` gained `NotificationChannelKind` and `NotificationDeliveryStatus`.

**`NotificationEventType` is the third named enum in this contract with NO server type**, joining
`PermissionKey`: the outbox's names are `String` constants on `OutboxEvents`, in a foundation module
that must not learn what a consumer does with them. What checks it instead is
`com.tcms.integration.NotificationEventTypeTest` — the schema's members against
`NotificationChannelService.DELIVERABLE_EVENTS` **both ways**, and each against the constants the
producers record, because both sides could agree on a typo and the only symptom would be a channel
that never fires. `OpenApiContract.enumSchemas()` was widened to public for it.

**`NotificationDelivery.eventType` is a bare string and not that enum, on purpose**: it is a
historical row and may name an event this instance no longer sends, so typing it as the live
vocabulary would make an honest log fail a client's own parse.

### ~~Left for phase 3~~ → phase 3 shipped, and what the whole slice leaves behind

~~No frontend and no `packages/` surface — the SPA's settings screen (channel list, create/edit
dialog with the write-only secret, delivery history) is phase 3~~ → **the screen exists**
(`features/settings/NotificationSettingsPage.tsx`); see `fe-react.md` §218 for its two rules. The
half that matters to this stack is that **the read gate above is what shaped it**: with the reads
403ing there is no read-only view to render, so `ReviewSettingsPage`'s pattern was unavailable and
the SPA answers §31 by keeping the rail item and fetching nothing at all. **Still no `packages/`
surface** — neither the CLI nor the MCP server offers notification channels, and that is a decision:
an agent that could create a channel could point one at a URL it chose.

**No message carries a link to the run** — that needs a base URL this module does not have, and
borrowing `tcms.invitation.base-url` would be `identity`'s property read from a module that may not
see it.

**Two things nothing in this build watches, both recorded as queue rows rather than fixed:**

- **No delivery has ever succeeded under observation.** `NotificationDeliveryTest` points at port 1
  on loopback on purpose, because the *failing* path is where the credential leaks — so the `SENT`
  branch of `beginAttempt`, `succeeded`, and all three adapters' bodies against a real endpoint are
  unexercised end to end. `§218 NT-6`'s local-HTTP-sink recommendation is what changes that.
- **`work.item_assigned` has a producer pin and a consumer pin and nothing that crosses between
  them.** `NotificationDeliveryTest` drives `run_completed` only — which is precisely the shape this
  slice's own three-test argument says a pair of pins cannot catch. `§218 NT-8` drives it through the
  board; a fourth method in that class would close it here.

**`§218 NC-5` stays open and is the slice's standing candidate**: nothing in this repository asserts
that a credential is absent from a *response* schema, and `pairedSchemasAndRecordsNameTheSameMembers`
compares the two sides to **each other**, so adding `secret` to both satisfies it. §217's `FD-15` is
the same missing rule two days earlier. **It does not belong in this suite** — `client/src/test/`
already parses `openapi.yaml` and `ci.yml` runs no backend test, which is §215's placement rule.


## §209 — THE ARTIFACT REGISTER HAS THREE REACHABLE STATES, AND TWO WAYS IN

**Read this before touching `ArtifactWriteService.validateSupersedes`, `setStatus`, or
`Artifact.circulate` / `supersededBy`.**

`ArtifactStatus` had **one writer** from `V1` until §209: `Artifact.register` writing `ACTIVE`. Both
other values now have one, and they are not symmetrical.

| | `ARCHIVED` | `SUPERSEDED` |
|---|---|---|
| Written by | `PUT /projects/{projectId}/artifacts/{artifactId}/status` | `ArtifactInput.supersedes` / `ArtifactFileInput.supersedes` on either create |
| Chosen by a caller? | yes | **never** |
| Reversible? | yes, same endpoint | **no** — a 409, and the way out is to archive the *replacement* |
| In `ArtifactStatusInput`'s enum? | yes | **no**, deliberately |

**`SUPERSEDED` is a fact about another row.** It holds exactly when `artifact.superseded_by_id` names
a replacement, so `Artifact.supersededBy` writes the status and the pointer together and nothing else
writes either. `Artifact.circulate` **throws** on `SUPERSEDED` and `ArtifactWriteService.parseCirculation`
returns `null` for it — two refusals on purpose, so a second parser cannot let it through.
`ArtifactCirculationTest` asserts the *correctly spelled* member is refused, which the obvious
implementation (a lookup over `values()`) would not survive.

### The same-scope rule is a gate, not tidiness

`supersedes` writes a **second row** — the one being replaced — and nothing gates that row.
`validateSupersedes` requires the replacement to sit at the **same scope** and, at `PLAN` scope, in
the **same plan**, after which the create's own `writeGrantedByRowScope` check covers both. ~~Delete
the comparison and a Test Engineer (whose `artifact:edit` is `PLAN_SCOPE`) registers a plan-scope note
that retires the project's test strategy, with a `201` that looks correct.~~ `§209 AM-1` is the
mutation row; `Supersession.theEngineerCannotRetireWhatTheyMayNotEdit` is the assertion.

> **§210 moved the premise of that argument and the rule survives it — read which half.** `V52`
> de-qualified `artifact:edit`, so a Test Engineer holds it at `ALL` and `writeGrantedByRowScope` no
> longer refuses them the project-scope row. The same-scope comparison is **not** thereby redundant:
> it is a **400 validation** — a plan note is not a project document, whoever is asking — and the
> named test still passes for that reason and not the permission one. What is gone is its second job
> as an authorization backstop. **Same shape as §207's find**: an argument outliving its premise
> while every test stayed green, which is why the reason is rewritten here rather than left to be
> re-derived from a comment that now misleads.

Two more rules in the same method, both `400` on `errors.supersedes`: only an `ACTIVE` artifact may be
replaced (a second replacement overwrites the pointer and loses the middle of the chain; an archived
one is already out of use). **Kinds are free to differ.** **`write_once` is NOT checked** — superseding
is what a sign-off record is for, and the delete's own 409 says so.

### `setStatus` reads the qualifier against the ROW's scope

The one word that differs from the create, and it carries the rule: a create is gated on the scope in
the *request* (the scope the row will have), this on the scope the row *already has*. Read the request
instead and the restriction is defeated by an omitted field. Not `artifact:delete` — archiving keeps
the row and can be undone, which is §208's suite argument one module over. Idempotent, and the audit
row is written only when the status actually moved (`DocPageWriteService.setStatus`'s call).

### What did NOT change, and was re-argued rather than inherited

**A `SUPERSEDED` artifact stays deletable.** `theReplacementCannotBeDeleted` has asserted since §124
that the other end of the edge is deletable, and that stands: being replaced is not what makes a
record permanent — `write_once` is. Refusing both ends would make a *mis-registered* link immortal the
moment somebody corrected it, since the replacement cannot be deleted either.

`ArtifactEndpointTest.setSupersededBy` (raw SQL) is **deleted** — the state is reachable through the
register's own act now. `aSignOffRecordCannotBeDeleted`'s `setWriteOnce` fixture stays, because that
column still has no writer.

`superseded_by_id` is **mapped**, so the entity's unmapped-column count is **two**. Third time inside
this one entity that a "blocked" note has outlived its blocker.

---

## §208 — ONE SENTENCE DECIDES THE AUTHORING LADDER'S GATE, AND `rank` IS GONE

**Read this before touching `LifecycleLadder`, `CaseBulkService`'s status arm, or `SuiteStatusService`.**

`LifecycleLadder.permissionFor` is now four lines and the rule is one sentence:

> **The approve key buys a sign-off, and it buys touching an artifact that has one.**

```java
if (requested == APPROVED) return keys.approve();
return current == APPROVED ? keys.approve() : keys.edit();
```

Consequences you will otherwise re-derive:

- **Deprecating an unapproved case or suite costs the EDIT key** (`case:edit` / `suite:manage`), and
  so does **reviving** a deprecated one. This reverses §75's `DEPRECATED` arm. §207's recorded
  incoherence — a contributor could delete a colleague's case and not retire it — is closed.
- **`rank` is DELETED and must not come back.** It put `DEPRECATED` at 3, *above* `APPROVED`, which
  is what made retiring reachable only from the ladder's top and reviving read as an unmaking.
  Narrative order (drafted → reviewed → approved → retired) was being used as authority order. If you
  find yourself wanting an ordinal here, that is the mistake. **`PlanLifecycle#rank` is a different
  ladder and stays** — `PlanStatus` has no `DEPRECATED`.
- **`APPROVED → anything` still costs approve.** §75's finding is untouched, and it is the half a
  refactor breaks silently: `LifecycleLadderTest.Approve.leavingAnApproval` is its independent
  observer, and `§208 DM-2` in the mutation queue exists because the fail-closed instinct on *revive*
  frees `APPROVED → DRAFT` as a side effect.
- **`LifecycleLadderTest` exists now and is total over the sixteen pairs**, with no Spring context.
  Before §208 the gate's whole coverage was five assertions across two endpoint tests. Add a rule
  here and the table is where it gets pinned; the endpoint tests assert the *wiring* (each caller's
  own `Keys` plus the row's stored status), not the rule.
- **`CaseBulkService.leadOnly` no longer has a `DEPRECATED` arm.** Everything that is not
  `target == APPROVED` names the state the row is **in**, because since §208 `current == APPROVED` is
  the only other way the approve key is asked for.
- **`BulkOperations.permission` is unchanged and still correct** — it is the request-level *floor*
  (approve iff the target is `APPROVED`), and the per-row half has always been `evaluateStatus`.

**No permission moved and no migration was written.** This is a gate placement, so `V3`/`V51`,
`Permission`'s enum and `lib/permissions.ts` are all untouched. Roles spec **§A4 principle 2** carries
the rule; handoff [§208](../handoff/208-the-retire-that-cost-more-than-the-delete.md) carries the
argument.

Found on the way and **not** taken: **`ArtifactStatus` has two unreachable values.**
`Artifact.java:230` is the only writer of `artifact.status` and it writes `ACTIVE`; `SUPERSEDED` and
`ARCHIVED` have no endpoint, and `artifact.superseded_by_id` exists with nothing able to set it.

---

## §207 + §210 — BOTH QUALIFIERS ARE LIVE MECHANISMS WITH ZERO GRANTS, AND FOUR MIGRATIONS LIE ABOUT IT

**Read this before any change to permissions, role seeding, or a delete gate.**

### The state, as of `V52` (§210) — supersedes the `V51` table below

**The Test Engineer / Test Lead line is exactly seven keys**, and nothing else:

| Test Lead only | |
|---|---|
| `case:approve` · `suite:approve` · `plan:approve` | giving approvals |
| `project:members` · `project:settings` · `project:audit` · `integration:manage` | adding and managing permission on the project |

**Everything else in the project is a contributor's** — created, edited and deleted, whoever authored
it. `SeedDataTest.testEngineerToTestLeadBoundary` asserts that delta **exactly, in both directions**,
so do not "add" to it with a `contains`.

What `V52` moved: ~~`suite:delete` · `artifact:delete` (shared **structure**)~~,
~~`run:close` · `result:amend` (judgements about the **work**)~~, ~~`doc:publish`~~ — all ✓ for Test
Engineer now — and `artifact:edit` went `PLAN_SCOPE` → `ALL`, which was the **last** qualified grant
in the catalogue.

> **The lesson, and it is why the table above is a closed set rather than a principle.** §A4's first
> line was stated three times in six weeks — *creative vs destructive* (`V3`/`V12`), *content vs
> shared structure and judgement* (`V51`), then **deleted** (`V52`) — and each rewrite was broken by
> an **ordinary** workflow case. When a tier line has to be re-stated every time it meets the
> workflow, the line is not the principle; the tier is. Do not propose a fourth.

<details><summary>The `V51` state, superseded by the above — kept because `V51`'s header argues it</summary>

| Key | Test Engineer | Note |
|---|---|---|
| `case:delete` · `evidence:delete` | ✓ (was `OWN`) | qualifier removed |
| `plan:delete` | ✓ (was **not held**) | a *tier* gate, not a qualifier |
| `suite:delete` · `artifact:delete` | — | shared **structure**, Lead-only |
| `run:close` · `result:amend` | — | judgements about the **work** |

</details>

**Steps have no permission key** and never did — part of the case, written by `CaseUpdateService`,
gated by `case:edit`. Do not go looking for `step:*`.

Author-cannot-approve-own-work is untouched — approving is not CRUD, and it is now the *only* place
authorship decides anything.

### `Agent` is Test Engineer, and the ceiling left the catalogue (§210)

`Agent` (`V40`) was *"Test Engineer minus the things an API caller must not do"*. Since `V52` it is
**Test Engineer entire, minus `company:read`**, derived from Test Engineer's rows *in the migration*
so the two cannot drift.

- **`company:read` is not a ceiling.** A PROJECT-scope grant of it is unanswerable by
  `evaluateCompanyWide`, and a token has no membership row and so no `V15` baseline — the row would
  be a company surface permanently false at the gate. `companyReadIsAlwaysExplicit` caught this on
  `V52`'s first run: a §43 rule catching a §210 migration.
- **What replaced the ceiling is a consent prompt in `packages/mcp/src/server.ts`** —
  `destructiveHint` so a host prompts, plus an elicitation that **refuses when the client cannot be
  asked**. It binds the **MCP transport only**; the same `tcms_` bearer deletes over `curl` with
  nobody to ask. Roles spec §A6a carries that objection, raised before the change and struck through
  as *superseded rather than wrong*. **Do not cite the Agent role as a delete boundary.**

### The mechanism is loaded and unused, deliberately — do not "clean it up"

`OWN` is still a legal `CHECK` value, still a `PermissionQualifier` member, still resolved by
`IdentityProjectAccess.canDoOnAuthored:111`, and `CaseDeleteService` still routes through
`projects.writeGrantedByAuthorship`. The SPA still passes `authorId` at every delete site.
**`PLAN_SCOPE` is in the same state since `V52`** — legal value, enum member, live resolution arm,
zero grants. All of it is unreachable from the shipped catalogue: **every row of
`role_permission.qualifier` is now `'ALL'`.**

~~Three~~ **Two** reasons it stays, and the count matters:

1. **The qualifier is DATA.** Deleting an arm is what would make a future qualified grant fail
   **open** at whichever gate forgot it.
2. ~~`PLAN_SCOPE` (`V19`) is the same mechanism and *is* populated (`artifact:edit`, Test Engineer and
   Agent), so the column and seam earn their keep regardless.~~ → **EXPIRED at `V52`**, which
   de-qualified both `artifact:edit` rows. This was the **only** reason that claimed the mechanism
   was *in use*; the two that remain are arguments from future cost.
3. Rebuilding it later costs a migration + gate + contract change; keeping it costs a comment.

`SeedDataTest.everyQualifierIsAll` pins the whole column in both directions (no qualified grant,
*and* a non-zero row count, because a misnamed table produces the same green);
`ownIsCarriedByNoGrant` still pins the value specifically. **If you are here to propose deleting the
mechanism, read roles spec §A5 first** — the argument for keeping it is already down to two reasons
and neither is "it is in use". #confirmed

### `V12`, `V40` and `V3` still argue the pre-`V51` model and are NOT edited

Flyway checksums an applied migration, so editing one breaks every already-migrated database. `V12`
has exactly one commit in its entire history — that is the convention. They are history the way
`V1`'s `suite_condition` comment is after `V49`. **`V51`'s header is the redirect** for anyone who
arrives at `V12` by grep, and the roles spec wins wherever they disagree. The only restatement
possible was a fresh `COMMENT ON COLUMN role_permission.qualifier` (replace-only), which `V51` does.

### `Agent` is Test Engineer minus FIVE, and the fifth arrived by test failure

`V40` **enumerates** Agent's 23 keys, so Test Engineer gaining `plan:delete` gave no agent anything
in the data. Two things still broke:

- `V40` justified dropping `case:delete`/`evidence:delete` by *"`OWN` follows `api_token.owner_id`"*
  — an argument `V51` deletes the premise of. Re-based: **agent access creates, reads and edits; it
  does not delete.**
- `plan:delete` sat under *"never held, **inherited from Test Engineer's own boundary**"*, which
  excludes a key only while the contributor lacks it. Promoted to an explicit drop.

**Neither was found by reading.** `agentIsTestEngineerMinusThree` (now `…MinusFive`) is a **delta**
check, and its own 2026-08-08 comment predicted this exact scenario. **Generalise it: when a rule
reads "X minus the things that…", assert the delta, never the subset** — a subset check cannot
distinguish "still correct" from "correct for a reason that no longer exists". #confirmed

### ~~A recorded, unfixed incoherence in `LifecycleLadder`~~ → **CLOSED BY §208**

~~`permissionFor` charges the **approve** key for the move to `DEPRECATED`, on the argument that
retiring is the soft form of a Lead-only delete. Half that premise is gone: **a Test Engineer may
now delete a colleague's case and may not deprecate it** — the stronger act is cheaper than the
weaker one. Still coherent for a *suite* (`suite:delete` unchanged). Left standing because moving it
is a change to the **approval ladder**, out of §207's scope.~~

**Fixed 2026-08-15 — see the §208 section at the top of this file.** Kept struck rather than deleted
because the "still coherent for a suite" clause is the half §208 had to re-argue rather than inherit:
`suite:delete` *removes* the folder while deprecating keeps it, keeps its members and can be undone,
so they were never two strengths of one act. #confirmed

### Where the prose was re-based (all comment-only)

`CaseDeleteService` · `CaseController` · `TestCase` · `TestCaseSummaryView` · `TestCaseDetailView` ·
`CaseCreateService` · `CaseAttachmentService` · `SuiteWriteService` · `SuiteVersions` ·
`IdentityProjectAccess`. **`createdBy` stays on the wire** — no longer load-bearing for
authorization, kept as a display field (author ≠ owner; `CaseVersionPanel` renders it).

Five `openapi.yaml` descriptions moved too, so **both `schema` copies were regenerated** — contract
prose is copied verbatim by `gen:api`, which makes it code for this purpose.

---

## §206 — YOUR JAVADOC IS SCANNED, BY A TEST IN `packages/`, AND `{@code}` IS THE UNCHECKED FORM

**No Java code moved in §206 — nine javadocs did, and one new rule reads all 749 `.java` files.**
Read this before writing a comment that names a path.

`packages/client/src/test/citedFiles.test.ts` asserts that **every path a comment names exists**,
over four corpora, of which `backend/src` (both source sets) is the newest. It reads two forms:

| form | scanned | why |
|---|---|---|
| `{@link Foo}` | no | already resolved by the compiler; a stale one is a build failure today |
| `{@code Foo.java}` | **yes** | a formatting instruction — nothing on earth resolves it |
| `` `Foo.java` `` | yes | a handful of comments use backticks; free to include |
| a trailing `/` (`src/mocks/`) | yes | checked as a directory, same claim |
| **`{@code SomethingTest}`** | **yes** | the extension is appended and the file must exist. `*Test` ONLY — it is the subset where `{@link}` is *impossible*, because test classes are not on `src/main`'s compile classpath |

Three things that will bite a backend agent:

- **It runs in the `packages` CI job, not `:test`.** A stale `{@code}` in your javadoc reddens the
  other stack's job. That is the cost, stated in the check's own header, and the alternative is
  worse: `:test`'s declared inputs are its classpath and the five out-of-source artifacts
  `build.gradle.kts` lists, so **a rename or deletion anywhere else changes no backend input at
  all** and a Java rule would be green by cache against the very edit that makes a citation stale.
  Measured in §206 (`> Task :test UP-TO-DATE` after a same-line-count javadoc edit); the §185
  comment in `build.gradle.kts` is the same trap one layer down.
- **The escape hatch is this repo's notation.** `~~x~~` already says the file does not exist, so
  struck spans are excused. Otherwise the name goes in `CITED_BUT_ABSENT_BY_DECISION` with a reason,
  pinned both ways — `testng-results.xml` and `allure-results/` are there because they are the
  **caller's** files, which is a kind this product produces a lot of.
- **What it found is a shape, not two typos.** `handlers.ts` was cited by `CaseSort` and
  `ConditionType`; pulling that thread found **fifteen** comments in `backend/src/main` arguing from
  the SPA's mock, deleted 2026-08-06. Six are past-tense records of a defect and are correct. Nine
  made a present-tense claim — the condition search "matching the SPA's mock", `CaseSearch`'s facet
  semantics, `LifecycleLadder`'s wording existing so "the mock has to be able to copy exactly one
  wording", `CaseQuery` accepting `TC_1` because "the SPA's mock also accepts" it. All nine repaired.
  **If you are about to justify server behaviour by what the SPA's mock does, there is no SPA mock.**
- **The class-name half found eight of sixty-five, and one of them was a pin that did not exist.**
  Six were a drifted name (`ArchitectureTest` → `ArchitectureRulesTest`, `PlanStatisticsTest` →
  `PlanStatisticsEndpointTest`, and four more); `ApiSurfaceTest` ×2 named a guarantee that is
  **Spring's** — an ambiguous mapping stops the context building, so every context test fails and no
  dedicated check exists or needs to. The eighth:
  `AggregateExecutionReport.WORST_FIRST` said it was *"pinned by ~~AggregateExecutionPolicyTest~~
  against `StatusKind`'s own value set"*, no such class had ever existed, and nothing else made that
  comparison — so an eighth `StatusKind` would have fallen through the SQL `CASE`'s `ELSE` and ranked
  as the **best** result in the worst-first callout. The check is in
  `AggregateExecutionCrossCheckTest` now and the field is package-private for it. **Note the
  asymmetry this leaves**: it is pinned against its vocabulary, not against
  `PlanStatisticsService.WORST_FIRST`, the private twin it calls itself a mirror of.

---

## §200 — `CaseEdit.validate` HAS ONE PATH-DEPENDENT RULE, AND IT REACHES THREE CALL SITES

**`CaseEdit.ProcedureRule`** — whether the payload must state how the case is run. It is the
fourteenth parameter and the **only** rule in that method that differs by path.

| Call site | Value | Why |
|---|---|---|
| `CaseUpdateService` | `REQUIRED` | A `PATCH` body is the whole editable set (§144), sent by an author with the step field on screen. The funnel a stub is finished through. |
| `CaseCreateService` | `DEFERRED` | §97 D2's plan tab authors a case from a title and renders no step field. The alternatives were refusing the save (§97→§199) or inventing a step. |
| `CaseEdit.of` | `DEFERRED` | **Not a second decision.** A stub is a legal row once create admits it. |

- **STEPS AND A SCENARIO ARE ONE RULE, NOT TWO.** `StepFormat` makes them alternatives, so a case
  states its procedure in exactly one; `CaseCompleteness` has read them as one question since §182.
  They defer together. Half-deferring accepts or refuses the same act depending on a format
  (`§200 PR-4`).
- **`CaseEdit.of` IS THE HALF THAT WOULD HAVE SHIPPED BROKEN, and it was measured red** (`§200 PR-3`).
  `of` re-validates a stored row before an authored bulk action may write its version. Left at
  `REQUIRED`, every plan-authored stub returns from a `MOVE_TO_SUITE` as **skipped** under
  `NEEDS_A_SAVE` — *"Open this case and save it once"* — which `PATCH` refuses for the same missing
  steps. Two rules each correct alone, one dead end, reachable by an ordinary click, from a change
  that touches neither of them. **If you add a fourth call site, the question is not "create or
  update" but "is this payload an author's statement".**
- **`of`'s `@return` javadoc named two unreconstructable states and BOTH are now unreachable.** The
  stepless case is legal; a suite with live cases filed in it cannot be deleted
  (`SuiteWriteService.delete`). So `CaseBulkService`'s `reconstructable` branch is defensive rather
  than reachable — §181's shape — and **`NEEDS_A_SAVE` has never had a test**. Recorded at the
  constant, opened rather than resolved.
- **`case_health.incomplete` gets its producer and the SQL did not change.** §182 wrote two terms it
  could not reach; this is the relaxation its own javadoc predicted. It fires only on **APPROVED**,
  which is what makes the deferral cheap: a stub is a `DRAFT`, and §6o already says a draft missing
  things is a draft. Drop the gate (`§200 PR-5`) and every plan a team writes fills the dashboard.
- **`CaseCloneService` and `AiProposalDecisionService` inherit the deferral**, since both delegate to
  `CaseCreateService`. A clone of a stub is a stub; an accepted stepless proposal is a stub. The
  client-side `requireProposableCase` in `packages/` refuses to *file* one anyway, on purpose, and
  says so at the check.
- **`contractRequiredMatchesWhatCreateEnforces` is §144's check on the operation it never ran
  against.** One `TestCaseInput` serves two verbs that agreed until now. `required` is unchanged and
  still right for both — but that was a claim about `POST` nothing in the suite made.
- Fixture: `CaseBulkEndpointTest.stub()` (`TC-7`) is the only seeded case with no steps. The comment
  explaining why every *other* case gets one still stands.

---

## §193 — THE BULK RESULT REPORTS WHAT THE MOVE COST, AND THE CALL SITE'S POSITION IS THE RULE

Read this with §192 directly below, whose event it makes visible. **`BulkResult.declinesEnded`** —
the keys of cases a `MOVE_TO_SUITE` takes a decline from.

- **It is a SUBSET of `changed`, not a fourth bucket.** `changed`/`unchanged`/`skipped` partition
  the selection; this annotates one of them. Moving those keys out of `changed` would be false and
  would make every count derived from a list length wrong.
- **`CaseBulkService.endsADecline` MUST be called BEFORE `mutate`.** §192 made `MOVE_TO_SUITE` the
  first bulk command that writes a reconstructed field back into the context
  (`context.declines().put(...)`), so afterwards the stored list *is* the retained list and the
  answer is `false` **on every commit and only on a commit**. Measured red, not reasoned about:
  the preview arm was green beside it (`§193 LD-1`).
- **One projection, two readers — `Context.retainedDeclinesOf`.** The write narrows with it, the
  response counts against it. Two local expressions of "what survives" is how a response starts
  describing an operation the database did not perform.
- **The `instanceof MoveToSuite` guard in `endsADecline` is load-bearing.** `retainedAtTarget` is
  empty for the other seven commands because *nobody asked*, not because the folder imposes
  nothing; without the guard a `SET_AUTOMATION_STATUS` reports the loss of rows it never touches
  (`§193 LD-2`).
- **Populated on the PREVIEW, and that is the whole point** — the bulk endpoint is the only writer
  in the product with a moment before the act. The contract states it as a property clients may rely
  on.
- **The retention half has no witness.** Suites are flat (§98), so `ancestry` is one suite and a
  decline always names the departed folder's attachment: `declinesEnded` and *"this case declined
  something and moved"* are the same set today (`§193 LD-4`, a hole).
- **The version-history shape was REFUSED, and not because it is wrong**: no endpoint serves a
  `test_case_version.snapshot` at all (`EntityVersionView`, since §28), so "show it in the case's
  history" is a diff subsystem, not a field.

## §192 — A DECLINE LIVES AS LONG AS ITS FOLDER IMPOSES THE RULE

Read this with §187's section below, which it half-reverses. **`CaseConditions.reconcileOptouts`
carries TWO bounds and the order of the two `if`s is the rule.**

    optout.suite_condition.suite_id = test_case.suite_id, for every row.   (V50 swept, V50 comments)

- **`declinableAttachmentIds` is GONE.** One method answers both bounds:
  `CaseConditions.reachingAt(suiteId, liveSuites)` → `Reaching(all, declinable)`. `all` includes
  **retired entries' attachments**; `declinable` does not. They differ by exactly that, and each
  bound wants a different one.
- **`retained` (= `Reaching.all()` at the POST-SAVE folder) ⇒ outside it, DELETE, whatever the
  request said.** A move is the only way out of that set, and a move is the one act that decides
  which standing rules govern a case (§98: inheritance follows *filing* alone).
- **`declinable` ⇒ inside `retained` but outside this, KEEP, whatever the request omitted.** The
  retired-entry case: nothing renders a withdrawn procedure, so the client could not restate it.
  **Swapping the two arms deletes exactly the rows §187's bound exists for** and looks like tidying
  (`§192 DF-2`).
- **`CaseEdit.normalizeDeclines` intersects with `retained` LAST**, after §187's union — so the hash
  is what the case declines after the save. Bounding it after `CaseVersions.decide` instead records
  a decline the row does not carry, and the next no-op save is credited with un-declining it.
- **Validated against the OLD folder, stored against the NEW.** A request that moves the case *and*
  restates a decline of the folder it is leaving is a **200**, not a 400 — refusing it is the screen
  rejecting a value it just served. It is simply not retained.
- **`CaseEdit.of` passes `Set.copyOf(storedDeclinedIds)` — "retain all of it".** It cannot pass
  `Set.of()` the way it passes an empty `declinable`: that one is never consulted for a null field
  and this one always is. **The bulk caller narrows instead** — one `MOVE_TO_SUITE` has one
  destination, so it is one query for four hundred cases — and it must also
  `context.declines().put(...)` the narrowed list, because `writeVersion` reconstructs from it.
- **The reason this reverses §187 is that the CLONE had already answered it.** `declinedAtSuite` has
  carried a clone's declines only into the source's own folder since §129, so a *moved* case was
  declining a rule a *copy* of it would not.
- **`V50` is a DML migration under RLS** — the `company` loop with `set_config(..., true)`, V44's
  pattern, fourth use. A `DELETE` with no `app.current_company` set matches **zero rows and reports
  success**. Nothing in the build can observe it: `IntegrationTestBase` migrates a fresh container,
  so there is never a violating row (`§192 DF-7`, a hole).
- **Fixture trap, §187's one table over**: `ON CONFLICT DO NOTHING` on a `test_case` seed does not
  reset `suite_id`, and MockMvc rows outlive the method — so one test that moves a case leaves every
  later decline in the class a legitimate 400. The seed is `DO UPDATE SET suite_id` now. In
  `CaseBulkEndpointTest`, `suite_condition` must be deleted **before** `condition` in the reset.

## §190 — A COMMENT IS CHECKED NOW, AND `V1`'S TEXT IS NOT WHAT `\d+` PRINTS

Read this before writing a `COMMENT ON`, and before believing a comment in `V1`.

- **`SchemaCommentTest` (`com.tcms.schema`) reads every comment out of `pg_description`** and
  resolves the identifiers in it against the live catalog. Two rules: **qualified**
  (`table.column` → that table's live columns) and **bare** (any `snake_case` token → every name
  the database declares, *functions in any namespace*, so `to_tsvector` is a function and not an
  exemption). A stale identifier now fails the build.
- **The corpus is the LIVE text, not the migration file.** `V49` replaced `suite_condition`'s and
  `role_assignment`'s comments; `V1` still contains the old strings and always will, because
  `COMMENT ON` is replace-only. **Grep `V1` for a comment and you may be reading history** — the
  live one is whichever migration wrote it last. `V33` did the same for `suite`.
- **`suite_condition`'s comment said "walking `suite.path` upward" for fifteen sections**, and
  `CaseConditions`' class header *quoted* it as the statement of the §6f order. Both now say what
  the code does: flat, inherited by the cases **filed** in the suite (`test_case.suite_id`) and by
  neither other membership arm.
- **Halves of a dotted name whose left side is not a table are skipped** — `app.current_company`
  (a GUC), `documentation.links_changed` (an outbox event type). Asking which namespace beat
  exempting the word: the exemption map is **one entry**, `pass_rate`.
- **A full stop is not a qualifier**, and the first version of the rule thought it was — a
  sentence-final identifier was skipped, which is the most ordinary way a comment names a table.
  Found by mutation (`§190 SM-2`), not by reading.
- **The catalog is read in `@BeforeEach`, deliberately.** At `@BeforeAll` time `public` has **zero
  tables**: Flyway runs with the Spring context, which is created when the test instance is
  post-processed. Every rule here carries a corpus pin for that reason — without them the class
  passes while reading nothing.

## §188 — WHAT COUNTS AS A READER AND AS A WRITER, AND THE SPELLING THAT DEFEATS BOTH

**Both column rules in `ArchitectureRulesTest` read `src/main` ONLY.** Read this before adding a
test that touches a column in order to make a rule go quiet, and before "tidying" a factory.

- **A test is not a reader and not a writer, for a COLUMN.** §153's rule — a test caller is a
  consumer for a read seam — is about **seams**; a column is a **value**. A fixture selecting one
  proves the write happened, not that anything consults it. `SOURCE_ROOTS` and `SQL_ROOTS` are gone;
  the corpora are `MAIN_SOURCE` and `MAIN_RESOURCES`.
- **`@Version` fields are skipped by the writer rule** and the mirror rule deliberately has no such
  carve-out. Hibernate's assignment is in no source file, so reporting the fifteen `lockVersion`
  fields would be the rule asserting something untrue; `lock_version` really *is* read, and
  `everyLockVersionColumnIsAVersion` asks the question `@Version` actually raises.
- **`READERLESS_BY_DECISION` is 18 and `WRITERLESS_BY_DECISION` is 9** — both counts went **up**,
  which is the first time either has. Every arrival is argued at its field.
- **`x = null` IS A WRITER.** The rule reads bytecode and an assignment is an assignment.
  `SuiteVersion.of` and `TestPlanVersion.of` each carried one for `changeComment` and were silent
  for it while `DocPageVersion.of`, which merely omitted the field, was the only one ever reported.
  Both deleted. **There is no rule behind this** — ArchUnit's field-access model does not carry the
  assigned constant — so `§188 NR-2` in the mutation queue is what stands in its place.
- **`OWN_FILE` is deleted.** It excluded this file from its own corpus because each entry's key is a
  string literal naming the column it excuses. The arguments are in `src/test` and the corpus is
  `src/main`, so the shape is unreachable rather than filtered. Do not reintroduce the constant;
  reintroducing `src/test` is what `§188 NR-1` exists to catch.
- **`everyEntityIsWritableByTheProduct` still earns its place.** An entity whose columns are *all*
  individually excused passes the column rule entry by entry and is still a table the product cannot
  put a row in — `case_condition_optout` exactly. One bit per table ≠ one bit per column.
- **`SeedDataTest.areasMatchTheSpa` reads `frontend/src/lib/permissions.ts`.** It is the only reason
  `permission.area` is maintained, since nothing in the backend reads the column. **A backend test
  reading a file outside `src/` MUST get a line in `build.gradle.kts`'s `inputs.files`** or it is
  green by cache (§185) — declared as that one **file**, never the directory.
- **Never edit a shipped migration, including its comments.** A comment change is a checksum change
  and `baseline-on-migrate` is false, so `V3` keeps its unchecked-looking sentence and the record
  went to `Permission`'s javadoc. A correction to a migration comment costs a *new* migration.

## §175 — THERE IS ONE `TeamRef` AND IT LIVES IN `com.tcms.api`

**`com.tcms.api.TeamRef` is the contract's team.** If you are looking for `project.TeamRefView`,
`identity.RoleAssignmentView.TeamRef` or `auth.CurrentUserService.TeamRef`, that is why — all three
are gone. `project.TeamDirectory`'s port now returns `api.TeamRef` and `identity.IdentityTeamDirectory`
builds one. `auth`, `identity` and `project` all already had `"api"` in `allowedDependencies`.

- **The schema has THREE `$ref`s now**, not one: `ReviewAssignee.team`, `RoleAssignment.team` and
  `CurrentUser.teams[]`. The last two were **inline object schemas** until this slice, which is why
  three records could serve one shape with nothing comparing them. A field added to `TeamRef`
  reaches all three — that is the cost of the collapse and it is stated in the schema's comment.
- **`CurrentUser.teams[].isLead` IS OFF THE WIRE.** Nothing read it (`frontend/`, `packages/`, any
  generated client), `team_membership.is_lead` confers no permission by its own field comment, and
  `Team.leads` is where a lead is rendered. **The array itself stays** and the reason is a gate:
  `GET /teams` needs `company:teams`, so `GET /me` is the only way a regular member learns their own
  teams. Nothing renders it either — that is a recorded gap, not a second deletion.
- **`ContractConventionTest.HOMONYMS` is `Map.of()`** — it was both `TeamRef` entries and nothing
  else. Kept empty on purpose: `everyNameMatchedRecordIsPairedOrExplained` names it in its failure
  message, and its staleness rule needs a map to be a rule about.
- **`INLINE_RECORDS` is 23 locations** (17 entries in the map, 2 package-private, 4 multipart forms), down two, and `RESPONSE_RECORDS`' `TeamRef` entry points at
  `com.tcms.api.TeamRef`. That map's header no longer uses `TeamRef` as its worked example of a
  human-only pairing — it uses `Attachment`, whose two owners no name match proposes at all.
- **Before homing a shape in `api`, MEASURE the population first.** `{id: UUID, name: String}` is
  generic, and `noRecordOutsideApiRepeatsAnApiShape` matches on **shape, not name** — exactly two
  production records had those components and both were the ones deleted. A near-miss homed here
  reddens unrelated types and the rule gets blamed. Mutation-verified: a fourth copy called
  `GranteeGroup` fails that rule and **nothing in `ContractConventionTest`**, which is the whole
  argument for a shape-keyed rule.

## §174 — `DocLink` HAS EXACTLY ONE MUTABLE COLUMN, AND A NAMED ACT TO MOVE IT

**`doc_link.relationship` is correctable in place; every other column stays `updatable = false`.**
Read this before adding a write to `documentation`'s link services or widening the entity.

- **The immutability argument in `DocLink`'s header is about the TARGET COORDINATES** and was applied
  to `relationship` by being applied to everything. It stays, in place, for the coordinates: a link
  that silently changes what it points at keeps every count over the table agreeing with itself while
  pointing somewhere new. `relationship` is the *claim*, and the coverage matrix counts `VERIFIES`
  and ignores the other two — a mislabelled link is a false coverage number, not a cosmetic error.
- **`relabel(next, actorId, now)` is a named act, not a setter**, and returns whether anything moved.
  There is no way to move the field without stamping `updated_at`/`updated_by`, so the audit is a
  property of the type rather than a convention two services remember. A `false` return means **no
  audit row and no `DOC_LINKS_CHANGED` event** — a correction that corrected nothing must not be
  reported as one.
- **Nothing in Postgres ever made the column immutable** — no trigger, no rule, no grant. `V48` adds
  only `updated_at`/`updated_by`, nullable, **with no backfill**: NULL means *never corrected*, which
  is a different fact from "corrected at creation", and defaulting from `created_at` would state the
  second about every row that has ever existed.
- **Two endpoints, one `LinkRelabelInput`.** `PATCH .../doc-pages/{pageId}/links/{linkId}` under
  `doc:edit` and `PATCH .../cases/{caseId}/external-links/{linkId}` under `case:edit` — each gated by
  whoever owns the thing the link is a property of, and both existing because §173 spent a slice
  removing this exact asymmetry from the withdrawal of the same rows.
- **`relationship` is REQUIRED on the correction and defaulted on the create.** Absent on a `POST`
  means "the ordinary one"; absent on a `PATCH` means the caller did not say what to correct it *to*.
  Copying the create's default here is mutation `§174 RL-3` and it silently promotes links to
  coverage.
- **`updated_at`/`updated_by` are written and read by nothing** — a deliberate absence, recorded in
  the handoff. `audit_event`'s `doc_link.relabelled` is the readable half.

## §176 — FOUR BINDING RULES, TWO QUESTIONS, AND ONE OWNER PER NAME

Read this before adding a `@RequestParam` to any handler, or a query parameter to `openapi.yaml`.

- **`ContractParameterBindingTest` now asks both directions of both populations.** Declared-and-unbound:
  `everyDeclaredQueryParameterIsBound` / `everyDeclaredFormPartIsBound`. Bound-and-undeclared:
  **`noHandlerBindsAnUndeclaredQueryParameter`** (§176) / `noHandlerBindsAnUndeclaredFormPart`. The
  grid is a table in the class javadoc.
- **A handler binding a key `openapi.yaml` does not declare now FAILS THE BUILD.** Nothing fails at
  runtime — both copies of `schema.d.ts` lack the key, so no generated client can send it and the
  argument is its default forever. Two repairs: declare it (regenerating **both** copies) or delete
  the parameter. **There is no ignore list and adding one is not a third repair.**
- **The query rule SKIPS any operation with a `multipart/form-data` body**, and that is a boundary
  rather than a hole: on a form, every bound name is the form rule's, declared or not. Do not
  "improve" it into subtracting the declared parts — that reports an undeclared name from both rules
  under two diagnoses. Mutation `§176 QP-2` is the pin (expected **green** on the query rule, red on
  the form rule).
- **The floor is `isGreaterThan(5)` against a measured 13.** It guards the mapping lookup silently
  matching nothing; it is deliberately not 13, or every legitimate deletion of a filter reddens it.
- **Green on arrival, like §171.** No handler in this codebase binds an undeclared parameter today.
  What the rule buys is the next edit — an *addition*, which had no observer at all; a *rename* was
  already half-covered by the declared-and-unbound rule from either side.

---

## §171 — A MULTIPART FORM'S PARTS ARE COMPARED TO THE HANDLER'S SIGNATURE

Read this before adding a `multipart/form-data` body or touching one of the four handlers
(`CaseAttachmentController`, `DocPageAttachmentController`, `EvidenceController`,
`ArtifactFileController`).

- **The pairing exemption is not a comparison exemption any more.** A form has no record —
  Spring binds it to a *parameter list* — so `BOUND_AS_FORM_PARTS` excuses it from
  `ContractConventionTest`'s pairing rules and always will. The three rules that replace what a
  pairing would have given are in **`ContractParameterBindingTest`** (§171):
  `everyDeclaredFormPartIsBound`, `noHandlerBindsAnUndeclaredFormPart`, `formPartRequirednessAgrees`.
  `OpenApiContract.formPartsOf(operation)` is the contract side — part name → required.
- **`formPartsBoundBy` reads THREE spellings and the third is load-bearing:** `@RequestPart`,
  `@RequestParam`, and **a bare `MultipartFile` parameter**, which Spring binds by parameter name.
  Nothing is written the third way today; delete the arm and a handler rewritten that way reads as
  binding nothing. `defaultValue` is folded in — Spring treats a defaulted parameter as optional
  whatever `required` says.
- **`@RequestParam` binds a query key and a text form field identically.** The only thing separating
  them is which side of the contract declares the name, so `noHandlerBindsAnUndeclaredFormPart`
  subtracts the operation's declared query parameters. It removes nothing today (none of the four
  paths declares one) and ~~it is why the *bound-and-undeclared query parameter* direction is still
  unwritten — that rule needs the media type in hand~~ → **§176 wrote that rule**; see the §176
  section below for how the two halves divide the population.
- **The floor is `isEqualTo(4)`**, not a `>`: the contract has exactly four multipart bodies, and a
  fifth is a deliberate act that should re-read this section.
- **The rules compare names and requiredness, statically.** A part bound to the right name with the
  wrong type passes, as does one the handler binds and ignores — the multipart mirror of §165's
  limit.

---

## §170 — THE CONTRACT PAIRING RULES COVER EVERY OBJECT SHAPE, NAMED AND INLINE

Read this before adding a schema to `openapi.yaml` or a wire record to the backend — **both now
fail the build until they are paired.**

- **`everyNameMatchedRecordIsPairedOrExplained` was never a population rule.** It reports a schema
  only when a record is called `Foo`/`FooView`, so 45 of 129 object schemas — a third, and almost
  exactly the write side — were compared to nothing in either direction. `TestCaseInput` is served
  by `CaseUpdateRequest`, `SuiteInput` by `SuiteWriteRequest`, `RepositoryDashboard` by
  `DashboardView`: the schema is named for the wire and the record for the act, which is correct in
  both places and is what hid them. **`everyObjectSchemaIsPairedOrExempt` is the population rule**;
  `RESPONSE_RECORDS` is 125 entries.
- **`INLINE_RECORDS` pairs anonymous shapes BY LOCATION** — `TestCaseSummary.lastResult`,
  `Facet.values[]`, `POST /auth/login (request)`, `GET /projects/{projectId}/cases (200)`. ~~25
  locations, 21 paired~~ → **23 and 19 since §175**, which made two of them `$ref`s (2 package-private), 4 multipart forms in `BOUND_AS_FORM_PARTS`
  (~~`BOUND_AS_REQUEST_PARAMETERS`~~, renamed in §171 — the parts are `@RequestPart`s).
  `OpenApiContract.inlineObjectSchemas()` derives them.
- **The traversal counts what a node DECLARES ITSELF, not what it resolves to.** Every nullable
  reference here is `allOf: [{$ref: UserRef}], nullable: true`, so a resolve-first rule sees
  UserRef's members at 22 positions and demands 22 records. `declaresPropertiesItself()` is the
  guard; do not "simplify" it into `collectProperties`.
- **Two escape maps and no third.** `BRANCH_ONLY_SCHEMAS` (`PageMeta`, `TestPlanSummary`) is for a
  schema reachable only as an `allOf` branch — the composition is what a record serves, and pairing
  the branch generates false positives. The build **re-derives** it via
  `schemasReferencedOutsideAllOf()`, and the three accounting maps must be **disjoint**.
- **`MEMBER_DIVERGENCE_BY_DECISION` serves BOTH rules and is still `Map.of()`** across 144
  pairings; `isInlineKey` splits it. `compareMembers` is the one comparison — do not write a second.
- ~~**`IngestionRequest` lost `branch` and `commitSha`** (§170)~~ → **§177 PUT THEM BACK, with the
  columns mapped and read.** See §177 below; the map/declare/pass triple is one commit by design.

---

## §178 — `case_health` HAS NINE WRITTEN SIGNALS AND ONE LEFT, AND THE LAST TWO CAME OFF `case_doc_link`

**Read this before touching `CaseHealthRecomputeService`, any `*Facts`/derivation class in
`repository`, or `WRITERLESS_BY_DECISION`.**

- **`SpecLinkage` derives `OUTDATED_SPEC_CHANGED` and `UNLINKED`**, from `case_doc_link` — §167's
  mirror — and **not** from `doc_link`. It is deliberately not a `*Facts`: that suffix in this module
  means *rows another module owns, read through one file*, and this class names only `repository`
  tables. `DocumentationFacts` remains the single crossing.
- **The obstacle these two waited on since §158 was real, correctly named, and had stopped
  applying.** `HealthSignal`'s header said they "need `documentation`, which this module may not
  depend on" — still true, never what blocked them, because §167 put the rows in this module for a
  panel. **A sentence naming a live constraint can go stale by something else being built**, and no
  rule in this repo asks that question.
- **STATEMENT ORDER IS LOAD-BEARING.** `recompute()` runs `refreshCaseDocLinks()` **then**
  `recomputeLinkageSignals(now)`, one transaction. Swap them and both signals lag one tick behind the
  Traceability panel they must agree with. `aLinkMadeSinceTheLastTickCountsOnThisTick` is the only
  assertion in that class that fails on position rather than content; mutation `§178 SL-M1` is its
  other half.
- **THE DECISION, two ways at once:** `UNLINKED` counts **any** link (any relationship, external
  targets included) because the chip renders inches from the panel listing those links;
  `OUTDATED_SPEC_CHANGED` counts **any relationship** too, because a case *derived from* a rewritten
  statement is as wrong as one verifying it. `BlockCoverageView` and `CoverageMatrixReport` filter
  `VERIFIES` and are **not** being contradicted — they answer a question about a *statement*.
  `LINKED_BY_REFERENCE_ONLY` and `LINKED_TO_ONE_MOVED_SPEC_OF_TWO` (whose stale link is
  `DERIVED_FROM`) are the fixtures that fail if either is narrowed.
- **A link into a soft-deleted page, or a sibling project's page, reads as NO link** — the mirror drops
  it, so the chip agrees with the panel. Looks like a bug, is the design, and is the strongest reason
  the derivation reads the mirror.
- ~~**`WRITERLESS_BY_DECISION` is EIGHT entries and `CaseHealth.orphaned` is the last health signal in
  it.** Four left by being written (§157, §158, §178 ×2). `orphaned` needs a *decision* first, so the
  run of cheap exits is over. It is also the column
  `CaseHealthRecomputeTest.Boundaries.otherSignalsAreNotClobbered` now uses — the second column that
  test has had to move to, and there is no third.~~ → **§181 wrote it. Seven entries, no health
  signal among them, and the boundary test moved to `incomplete`.** See below.
- **`unlinked` fires on most cases of most projects** (INFO for that reason). Any new assertion over
  `signalsOf(...)` in `CaseHealthRecomputeTest` must name it.
- ~~**`StatusChip.SEVERE_SIGNALS` (frontend) disagrees with `HealthSignal.Severity` about two
  members**~~ → **fixed in §179 by deleting the copy: severity is on the wire.** See below.

---

## §187 — `case_condition_optout` HAS A WRITER, AND THE HASH RULE THAT COMES WITH IT

**Read this before touching `CaseConditions`, `CaseEdit.normalizeDeclines`, `CaseCloneService.derive`,
or before "simplifying" the union in either.**

`TestCaseInput.declinedConditionIds` is the writer — **`suite_condition` ids, never library
`condition` ids** — reconciled by `CaseConditions.reconcileOptouts`. `TestCase` serves
`declinedPreconditions` / `declinedPostconditions` beside the two resolved lists.

- **Two extra lists, NOT a `declined` flag on `CaseConditionRef`.** `TestCaseDetailView` *is* the
  version snapshot (`CaseVersions` serializes it whole) and `CaseFacts` pins a run's checklist out of
  that snapshot's `preconditions`. A flag puts declined rows into every checklist of every run
  started after the decline, marked by a boolean the pin's reader has never heard of. **Do not add
  the flag.**
- **The INPUT is one array for both kinds**, because a `suite_condition` id already determines its
  kind. Splitting it would let a caller file an id under the wrong one.
- **`CaseEdit.normalizeDeclines` unions the invisible stored declines back in, and that is a HASH
  requirement.** `reconcileOptouts` protects a decline whose attachment no longer reaches the case
  (the retired-attachment bound, transferred from `reconcile`), so the client's set and the stored
  set are different populations. Without the union, a request that *sends* the field and one that
  *omits* it answer two different `CaseEdit`s for one stored state — two hashes, and a save that
  changed nothing writes an empty-diff version. What is hashed is **what the case declines after
  this save**.
- **`storedDeclinedIds` is UNRESTRICTED on purpose**, unlike `liveAttachedIdsOf`. It feeds a hash
  input, and a hash input must be a function of the stored row. Bounding it here forces
  `CaseEdit.of` to resolve a suite chain and a library per case to agree — `CaseBulkService`'s trap
  on a third field.
- **`declinable` is resolved BEFORE `applyEdit`, and reused by the reconcile.** A save that also
  moves the case would otherwise validate against the folder it is going *to*, not the one the
  client was looking at. **Since §192 it is one of TWO bounds — see that section.**
- **The CREATE path accepts declines because `CaseCloneService` is a caller.** The SPA's create form
  draws no inherited marker, so no screen reaches it; the clone does, and its §129 header prescribed
  the rule (`declinedAtSuite`: carry them when the clone lands in the source's own suite, none
  otherwise). Writing the rows behind the create path's back would record a v1 hashing as a case
  that declines nothing.
- ~~**The INERT decline is real and invisible.** A decline whose attachment no longer reaches the
  case survives every save.~~ → **HALF-REVERSED BY §192**: a **move** ends the decline now; a
  **retired entry** still does not. Three of the four holes closed with it.
- **A suite-level attachment is inherited by every case filed beneath it**, so a `suite_condition`
  fixture cannot be shared with tests that assert `preconditions` — it reddened five of
  `ConditionWriteEndpointTest`'s twice. §187's fixture is a suite, a case and an empty third folder
  of its own.

## §187 — `everyEntityIsWritableByTheProduct`, AND WHY THE COLUMN RULE COULD NOT CATCH THIS

`everyMappedColumnHasAWriter`'s corpus reads **`src/test` as well as `src/main`**, so a table whose
only writer is a fixture reads as written. That is why §156's rule ran green over
`case_condition_optout` for thirty-one sections. §153's finding one level down: a test caller is a
consumer for a *read* seam and never for a *write* one.

- **Do not "fix" it by narrowing that corpus without doing the work.** Measured in §187: writerless
  goes **6 → 22**, of which **fifteen are `lockVersion`** (`@Version`, written by Hibernate, in no
  source file, definitionally not a finding) and one is real (`DocPageVersion.changeComment`).
  Readerless goes **11 → 18** and **all seven are real** — `TestRun.releaseSortKey` and
  `TestExecution.releaseSortKey` among them, so §151 shipped that column as a *fix* and nothing in
  the product reads it back. Eight findings, eight decisions: that is a slice.
- **The new rule is at TABLE granularity**, where a `@Version` column carries no weight. Migrations
  are in its corpus — leaving them out gave four false members (`permission`/`role` from `V3`,
  `configuration`/`doc_space` from `V1`'s seed functions).
- **`UNWRITABLE_BY_DECISION` has one member**, `CaseAutomationTag`, whose argument is already at
  `CaseUpdateRequest`: `automationTags` is still refused with a 400, and building the reconcile
  quietly would make that refusal a lie.

---

## §186 — `suite_condition` HAS A WRITER, AND ITS RECONCILE MUST NOT DELETE-AND-REINSERT

**Read this before touching `SuiteConditions`, `SuiteEdit`, `SuiteWriteService`'s condition arm, or
before "unifying" the two condition reconciles.**

The table shipped in M0 with its `kind` column, its `V10` index, `CaseConditions` reading it, a
response shape and a `suiteUsageCount` — and **no writer at all** until §186. If you are looking for
why a fixture writes `suite_condition` in raw SQL, that is why, and `SuiteWriteEndpointTest` no
longer does.

- **`Suite` serves `preconditions` AND `postconditions`, not `conditions`.** One array could not
  express `kind`, which `CaseConditionRef` does not carry. The case endpoint's answer, applied here:
  which array a row is in *is* the field. Do not add `kind` to `CaseConditionRef`.
- **`SuiteConditions.reconcile` rewrites rows IN PLACE and this is not a preference.**
  `case_condition_optout.suite_condition_id` references this table `ON DELETE CASCADE`, so a
  clear-and-reinsert discards every case's opt-out on **every save of the suite**. `CaseConditions`'
  own javadoc says a delete-and-reinsert "would in fact be *safe* here" and names this table as the
  exception — **that sentence is about this class.** Measured: with the reuse branch removed exactly
  one of eleven endpoint tests fails, and the other ten look correct.
- **The validation is `CaseEdit.normalizeConditions` TRANSFERRED, deliberately not extracted.** The
  rules being identical is the invariant, and each endpoint test pins them separately. Same error
  strings, asserted on both sides. If you share it, you have made divergence undetectable to buy six
  lines.
- **Absent means "leave alone", `[]` means "detach"** — and `suiteInputFrom` in `packages/client`
  **drops** both lists, so a CLI `suite edit --name` relies on that distinction. The SPA sends them
  always. Collapsing absent into empty makes the two clients disagree about what a rename does.
  **§189 added `--precondition` / `--postcondition` and the drop still stands**: the change goes in
  the delta, where the caller's own library ids are, and `''` (CLI) / `[]` (MCP) is how "detach
  them all" is spelled. So this endpoint now has three callers depending on the distinction.
- **Two known holes, recorded as mutation rows and measured**: nothing reddens if you drop the
  retired bound in `reconcile` (`§186 SQ-2`) or the stored half of `acceptableConditionIds` in
  `rename` (`§186 SQ-5`). Both need a condition retired *after* being attached to a suite, which no
  test reaches.
- ~~**`case_condition_optout` still has no writer**, and it is now the only reader of
  `suite_condition.id`. The reconcile protects a row nothing can create.~~ → **§187 gave it one**,
  so the reconcile now protects a row the product can create. See §187 below.
- **`V1`'s `COMMENT ON TABLE suite_condition` is stale** — it describes walking `suite.path` upward,
  which §98 deleted. Correcting it costs a migration for a comment; not done, and it is a decision.

---

## §185 — THE BLOB ROOTS ARE PROVISIONED BY THE IMAGE, PINNED BY A RULE, AND CHECKED AT BOOT

**Read this before adding a fourth blob store, editing `backend/Dockerfile`, either compose file,
or `FilesystemBlobStore`'s constructor.**

`application.yml` defaults three roots under `/var/lib/tcms`. Nothing created them, so the first
*write* — evidence upload, report download, CI ingestion — threw `AccessDeniedException` on a
container that had reported healthy. **Both live passes this product has had died on it, six days
apart** (§110 F10 on 2026-08-06; the 2026-08-12 sitting), the second one after the first was
"fixed": §110 corrected `deploy/compose.prod.yaml` and recorded *"production is unaffected"*, which
was true of the artifact it was looking at and left the one `CLAUDE.md` tells every reader to run.

- **A fourth store is four edits, and three of them fail the build if you skip them.**
  `application.yml`, a path in `backend/Dockerfile`'s `mkdir`/`chown`, the `environment:` and
  `volumes:` entries in `deploy/compose.prod.yaml`, and the runbook's §7 `chown`.
  `ConfigConventionTest` derives the roots from `application.yml` and asks the Dockerfile and both
  compose files about **every** member — so the only one still on a human is the runbook, which is
  prose about a host no test reads, **and it is the one that was already wrong**: §160 added the
  third root and did not update it, so `/var/lib/tcms/raw-reports` is root-owned on the live VM.
- **Named volume and bind mount behave OPPOSITELY, and this is the load-bearing fact.** Docker
  seeds a fresh *named* volume from the image's content at that path, ownership included — so the
  root `compose.yaml` works *because* the Dockerfile creates the directories. A *bind mount* takes
  the host directory's ownership and ignores the image's outright, so the Dockerfile buys
  production nothing and §7's `chown` is the whole of it.
- **`FilesystemBlobStore`'s constructor creates and probes its root, and throws if it cannot.** In
  the constructor rather than an adapter so a fourth adapter cannot forget it. The cost is
  deliberate: a misprovisioned deployment now **refuses to start**, including the operator CLI,
  rather than starting and 500ing at the first upload weeks later.
- **`build.gradle.kts` declares `Dockerfile`, `../compose.yaml`, `../deploy` and `../docs/specs`
  as test inputs.** Without it every source-*text* rule here is **green by cache**: Gradle tracks a
  Test task's classpath and resources, so editing the checked file leaves `:test` UP-TO-DATE and
  the rule about the edit never runs. Measured. `IdempotencyKeyCapTest` had read
  `../docs/specs/openapi.yaml` that way since §136. **Any new rule reading a file outside
  `src/` must be covered by that `inputs.files` list or it observes nothing.**

---

## §184 — `DashboardService.health` IS THE ONLY ORDERING OF HEALTH TILES, AND IT IS PACKAGE-PRIVATE ON PURPOSE

**Read this before touching `DashboardService.health`, its comparator, or
`frontend/features/dashboard/DashboardPage.tsx`.**

- **`health` is `static`, not `private static`, and the reason is a test.** `HealthTileOrderTest`
  (no database, no context — §150's lane, `RaisedSignalTest`'s neighbour) calls it directly. Do not
  re-narrow it, and do not "clean up" by asserting the order through `DashboardEndpointTest`: that
  fixture writes no `case_health` row, which is exactly why the tile order had **no observer on this
  side at all** until §184.
- **The comparator has TWO keys — severity, then count DESCENDING.** The second one was invisible
  for the panel's whole life: the SPA re-sorted by severity alone and preserved the tiebreak only
  because `Array.prototype.sort` is stable and severity happened to be this comparator's first key.
  A coincidence held it, no test named it, and §184 deleted the SPA's copy — so this is now the only
  place a tile order is decided.
- **`Comparator.comparing(TileView::severity)` orders by ORDINAL.** `RaisedSignalTest`'s
  `severityIsDeclaredWorstFirst` is what stops somebody alphabetising `HealthSignal.Severity`'s four
  constants and silently inverting both this panel and the case grid's chip.
- **§179's rule now holds on both screens that show a health severity:** the ranking is a property
  of the answer and a client-side sort is the second copy of it. The grid took it in §179, the
  dashboard in §184. Neither SPA file ranks; both carry a comment saying not to.

---

## §182 — `case_health` IS FINISHED: EVERY COLUMN DERIVED, AND NO WITNESS LEFT TO BORROW

**Read this before touching `CaseHealthRecomputeService`, `CaseCompleteness`, `HealthSignal`, either
`*_BY_DECISION` map, or `CaseHealthRecomputeTest.Boundaries`.**

- **`HealthSignal` HAS ELEVEN MEMBERS.** `INCOMPLETE` was contract-only from 2026-07-30 until §182 and
  is now a server constant with a derivation (`CaseCompleteness`), **appended LAST** for
  `UNDECLARED_AUTOMATION`'s reason — `CaseSearch.tallyHealth` reads its aggregates back **by
  position**. `Severity.INFO`.
- **THE DECISION:** an **APPROVED** case with no steps, or with nothing anywhere saying what should
  happen, or with no `case_classification` row. **The gate is the signal, not a filter on it** — a
  draft missing things is a draft, and `DEPRECATED` is out because a retired case is not going to be
  finished. Dropping the gate fires on most of an active project.
- **`step_format` IS A TERM IN TWO OF THE THREE.** A `GHERKIN` case legitimately has no `test_step`
  rows (plan §6a), so the obvious "has steps" predicate reports **every Gherkin case in the product**
  as unfinished. The scenario also settles the expected-result term for that format: its `Then`
  clause is the expected result, and finding it means parsing Gherkin in SQL in a cron job.
- **EITHER `expected_outcome` OR any step's `expected_result` counts.** They answer different
  questions (`TestCase.expectedOutcome`'s comment); requiring both fires on almost every case,
  requiring only the per-step one calls a house style a gap.
- **Classification means ANY row**, because `classification_dimension` has no `required` column.
- **Two of the three terms have no reachable producer** — `CaseEdit.validate` refuses both on create
  and update, and every creation path goes through it. Written anyway, and the argument is
  *different* from §181's: that term was missing a **writer**, these are refused by a **validator**,
  and a validator says nothing about rows already stored.
- **`EXISTS`, not a join — the opposite call from §181's and the same reasoning.** A semi-join cannot
  duplicate a row, so the cardinality failure `OwnerStanding` carries `m.company_id = c.company_id`
  to prevent is unreachable here rather than guarded against.
- **`Boundaries.otherSignalsAreNotClobbered` NO LONGER USES A WITNESS.** The witness moved three times
  (§157, §178, §181) and §182 derived the last borrowable column, so the population is *closed*. Each
  of the seven statements is driven alone (autowired components + `TransactionTemplate` +
  `CompanyContext.open` — outside a transaction the GUC is unset and the statement writes nothing
  while looking successful), and the seven `owns` lists must **partition** the columns this job
  derives. That partition check can see two statements colliding on one column; the witness shape
  structurally could not.
- **`CaseHealth.incomplete` IS IN NEITHER `*_BY_DECISION` MAP.** Third move between them in three
  slices, with the column unchanged each time. `WRITERLESS_BY_DECISION` is six entries and names **no
  `case_health` column**; `ContractEnumTest.DIVERGENCE_BY_DECISION` is down to one **server-only**
  entry, so there is no contract-only member left.
- **`RaisedSignalTest.DECIDED` is `Map.ofEntries`** — `Map.of` caps at ten pairs and this was the
  eleventh. Dropping a member to fit is the silent fix.
- **The five APPROVED fixtures in `CaseHealthRecomputeTest`'s stale-approval block now raise
  `incomplete`**, and their assertions say so rather than filtering it out (§178's call).

---

## §181 — EVERY HEALTH SIGNAL HAS A WRITER, AND "LEFT THE PROJECT" MEANS MEMBERSHIP

**Read this before touching `CaseHealthRecomputeService`, `OwnerStanding`, either
`*_BY_DECISION` map in `ArchitectureRulesTest`, or anything that assigns a case owner.**

- **`OwnerStanding` derives `ORPHANED`** — the seventh statement in `recompute()`, one `LEFT JOIN`
  pair over `company_membership` + `app_user`. Its position is **not** load-bearing (unlike §178's):
  it reads no row this job writes.
- **THE DECISION, and it is the whole slice:** a case is orphaned when it **has** an owner and that
  owner is *no longer in this company's directory* — membership missing or `REMOVED`, or the account
  `DEACTIVATED`. **Not "holds no grant on this project".** That reading needs project visibility
  re-derived from grants, teams, company-scope roles and SYSTEM assignments — a second
  `PermissionEvaluator`, in SQL, in a cron job — and it is *wrong* on Company Admins and Instance
  Admins, who own cases in projects they hold no project-scope grant for. Membership is the
  precondition for every permission (`CompanyMembership`'s header), so offboarding is the one status
  the derivation needs.
- **It is the SQL spelling of `AdminDirectoryService.listUsers` + `statusOf`.** Same three cases,
  negated. A second definition of "still here" is a chip and the Members screen disagreeing about one
  person — §180's defect shape, which lives only in the transition between two screens.
- **An `INVITED` membership is NOT an orphan, and neither is an unowned case.** Both are decisions
  with tests (`anInvitationOutstandingIsNotAnOrphan`, `anUnownedCaseIsNotAnOrphan`); "not ACTIVE" and
  a missing `owner_id IS NOT NULL` are the two tempting widenings, and the second is `UNLINKED`'s
  volume failure with none of its justification.
- **The membership join carries `m.company_id = c.company_id` and its siblings carry no company
  predicate at all.** Cardinality, not isolation: it is the first statement here joining a table where
  one person has many rows legitimately (one per client), so with RLS disabled — which `V1` permits
  per deployment — the `ON CONFLICT` would refuse to affect a row twice. Loud, not silent.
- **`AppUser.deactivate` has NO production caller** (four test callers). The term is in the predicate
  anyway; the frontend's words named *only* that unreachable half until phase 2.
- **`CaseHealth.orphaned`'s own javadoc said the signal meant "a case in no suite and no plan"** —
  a predicate `test_case.suite_id NOT NULL` forbids. It sat at the column for five slices while four
  signals were derived around it. If you are about to trust a field comment about a writerless
  column, that is the precedent.
- **`CaseHealth.incomplete` LEFT `READERLESS_BY_DECISION` and stays in `WRITERLESS_BY_DECISION`.**
  The boundary test needed a column with no owner, so `signalsOf` selects `incomplete` — and a test
  SELECT is a reader by that rule's own definition. It is a weaker reader than the rule was written
  for (it verifies an *absence*), the text corpus cannot tell, and the entry was deleted rather than
  kept, because a one-directional ignore list is §162's finding.
- **`Boundaries.otherSignalsAreNotClobbered` now uses `incomplete`** — the fourth witness, and the
  first that is not a signal. There is no fifth: it is the only `case_health` boolean left that
  nothing writes.

---

## §180 — THE DASHBOARD SENDS NO DISPLAY TEXT AND NO COLOURS

**Read this before touching `DashboardView`, `DashboardService`, `HealthSignal` or
`LifecycleStatus`.**

- **`HealthSignal` HAS NO `label`.** The field, the constructor argument, the accessor and all ten
  constant arguments are gone. It reached the SPA through `DashboardTile.label` and **six of the ten
  disagreed with `lib/labels.ts`**, so a tile reading "Outdated — spec changed" drilled into a grid
  reading "Linked spec changed" — one navigation, two vocabularies, each screen internally
  consistent. `CaseService.facets` argued the direction since M1.
- **`TileView` is `(key, count, severity, filter)` and `SegmentView` is `(key, count, filter)`.**
  `DashboardTile.label` and the segment's `label` **and `color`** are off the wire and out of the
  contract's `required` arrays. The client labels and colours from `key`.
- **The "generic tile" argument survives, but it was never about the words.** What makes a tile
  extensible is that `filter` is opaque to the client. A new `HealthSignal` is a *contract* enum
  member, so it is a compile error in `lib/labels.ts` before it can render anywhere — the label was
  duplicated, not extensible.
- **`LifecycleStatus.label()` STAYS, with exactly one caller** — `CaseBulkService.leadOnly`, which
  composes *"…a case that is already approved."* A **refusal is a sentence** (§149), not a field a
  client renders. Do not delete it as leftover; `§180 TL-M4` is the mutation that says so.
- **`DashboardBreakdown.label` STAYS**: a classification path or a person's name in this company is
  data only the server can resolve. That is the line — rendering a closed enum versus resolving data.
- **`DashboardService.quietDays` is deleted — it had no caller since §96.** A *private static*
  helper is in neither §152's nor §153's population and javac has no lint for one, so it was
  invisible to every gate here. §170's finding at a method.
- **`DashboardEndpointTest` asserts the fields are ABSENT** (`tilesCarryNoWords`,
  `barsKeepDataWordsAndSegmentsLoseRenderedOnes`). An assertion on a tile's other fields passes
  happily beside a re-added `label`; only absence fails on an addition.

---

## §179 — A RAISED SIGNAL CARRIES ITS SEVERITY, AND `signals()` IS SORTED WORST FIRST

**Read this before touching `HealthSignal`, `CaseHealth.signals()`, either `TestCase*View`, or
anything that renders a health signal.**

- **`TestCaseSummary.healthSignals` is `RaisedSignal[]` (`{signal, severity}`), not an array of
  codes.** `RaisedSignalView.of` is the only constructor path, so the pair is always read off one
  enum in one expression. Nothing may assemble the two separately.
- **Severity is the SERVER's; the LABEL is the SPA's, and both directions are argued in the code.**
  `CaseService.facets` refuses to send a second set of enum *words* (the SPA's catalogue is the one
  place a new member is a compile error); severity is a judgement about how wrong a case is, and the
  filter bar needs labels for members no server can send (`INCOMPLETE`). Do not "finish the job" by
  putting `label` on `RaisedSignal`.
- ~~**`HealthSignal.label()` IS STILL A SECOND SET OF WORDS**, on the wire via `DashboardTile.label`,
  and **six of the ten disagree with `lib/labels.ts`**~~ → **§180 deleted it; see that entry.**
- **`CaseHealth.signals()` SORTS BY SEVERITY, stably**, so ties keep declaration order. It is not
  cosmetic: `CaseGrid` renders `healthSignals[0]` and hides the rest behind `+n`, so declaration order
  showed `UNLINKED` (INFO, most cases since §178) in place of a case whose automation had stopped
  reporting. `DashboardService.health` sorts for the same reason in the same words. **The client must
  not sort.**
- **`HealthSignal.Severity`'s declaration order is load-bearing** — `Comparator.comparing` over an
  enum orders by ordinal, and two sorts depend on it. Alphabetising the four constants inverts both
  and fails nothing else. `RaisedSignalTest.severityIsDeclaredWorstFirst` is the only observer.
- **`RaisedSignalTest` writes all ten severities out by hand, on purpose.** Reading them off the enum
  would assert the enum equals itself. It also carries the automation-pair asymmetry that
  `HealthChip.test.tsx` used to hold — the frontend list's deletion would otherwise have taken the
  only check on it (§153's shape).
- **`HealthSignalSeverity` is a NAMED contract enum now** with a `ContractEnumTest.OWNERS` entry. It
  was an anonymous inline enum on `DashboardTile.severity` and therefore in no population any rule
  could see — §170's finding at an enum, and why §161's audit could not have found it.
- **`TestCaseVersion.getSnapshot()` has no reader in `src/main`**, which is what made the shape change
  inert for the version snapshot. Checked, not assumed.

---

## §177 — `test_run.commit_sha` AND `.branch` HAVE A WRITER, AND THE CLONE INHERITS NEITHER

**Git traceability (M4, plan §3 phase 2).** Read this before touching `TestRun`, `RunEdit`,
`RunCloneService.derive`, `IngestionRequest` or either run view.

- **`TestRun` maps both columns, `updatable = false`.** `TestRun.create` is the only writer; there is
  **no run edit endpoint at all** (only `PUT /status`), so create is the one moment either value can
  be recorded. That absence is now load-bearing in two arguments and is itself a named candidate.
- **THE TRIPLE IS ONE COMMIT: declare on the contract · map the column · pass it through.** Any two
  of the three reproduce §170's defect — a bound property nothing stores, a mapped column nothing
  writes, a declared property nothing binds. `GT-M1`/`GT-M2` in the mutation queue exist to prove the
  row-assertion and the response-assertion are two different tests.
- **`RunEdit` is the ONE validator and `IngestionService` inherits it** — the ingestion path opens its
  run through `RunCreateService`, so there is no second answer to what a commit id is. Consequence
  worth knowing: **a malformed `commitSha` refuses the whole CI post**, losing that build's report. No
  `ingestion_job` row and no run are written (`RunEdit` refuses before a display key is burned).
- **`RunEdit.SHA` is a SECOND COPY of `documentation.ExternalLinkService.SHA` and stays one.**
  `api`'s charter refuses it explicitly (*"a helper two modules happen to share does not qualify"*)
  and the two modules may not import each other. `config.GitObjectNameRuleTest` pins them equal **by
  reading the source text of both** and pins the count at 2 — `IdempotencyKeyCapTest`'s shape. A third
  validator means editing that number, deliberately.
- **`MAX_BRANCH` is 255, NOT `MAX_RELEASE`'s 100**, and `branch` has no shape check. `commit_sha` is
  trimmed and **not lower-cased**, matching `doc_link.external_commit_sha`.
- **`RunCloneService.derive` passes `null` for both — the one fallback that does not go to the
  source.** A release version is what you intend to test; a sha is what ran, and a fresh clone has run
  nothing. `TestRunCloneInput` therefore declares no override. **This is a deliberate absence a
  consistency cleanup would remove**; it is pinned in the schema comment, the `derive` comment and
  `RunCloneEndpointTest.theGitCoordinateIsNotInherited`.
- **`TestRunView` serves both because `TestRunDetailView` is built from it** — the *list* renders
  neither, on purpose (`§177 GT-5`).

---

## §169 — `doc_link` HAS TWO WRITERS AND TWO GATES, AND THE DIVERGENCE MAP IS EMPTY

**`EXTERNAL_GIT` and `EXTERNAL_URL` are written** — `V1` designed both and only `DOC_BLOCK` had a
writer (open question #26, resolved *build it* 2026-08-02, plan §6m). Read this before touching
`DocLink`, either link service, `case_doc_link` or the case detail's Traceability panel.

- **THE GATE IS `case:edit`, NOT `doc:edit`, AND THAT IS A REVERSAL RATHER THAN AN INCONSISTENCY.**
  `BlockLinkService`'s argument — *"a link is a property of the documentation"* — turns entirely on
  the target being a doc block. An external link renders on the case's Traceability panel and on **no
  page** (`BlockLinks` reads by `target_block_id`), so `doc:edit` would let a docs author annotate a
  case they may not edit, and would make the act impossible in a project keeping no documentation.
  The cost is recorded at `ExternalLinkService`: two writers, two gates, and the rule that remains is
  *whoever may edit the thing the link renders on*. **Do not "unify" them.**
- **`ExternalLinkController` serves `/projects/{id}/cases/{caseId}/external-links` from
  `documentation`.** `PlanDocumentController` is the precedent — a path under another module's
  aggregate, served by the module that owns the row. **It must never grow a `GET`**: a case's links
  are already served on the case, off `case_doc_link`, and a second answer from the other side of
  the boundary would disagree for as long as the mirror lagged.
- **Idempotency is a Java comparison (`ExternalLinkService#sameTarget`), never a query predicate.**
  Four identifying columns are nullable and `external_line_start = :start` is **UNKNOWN, not true**,
  when both sides are null — the SQL spelling writes a duplicate for every double-clicked whole-file
  link with every other test green. The line range **is** part of the identity.
- **Validation is narrower than `ck_doc_link_target` in two places, deliberately**: `repo` is
  required (a path with no repository is a link nobody can follow) and the sha is 7–40 hex (a branch
  name moves; SHA-256's 64 characters are a known, refused limit). `http`/`https` only on a URL —
  the value is rendered as an `href`, so it is a stored-XSS gate refused at the one place it can be
  refused once.
- **`V47` widens `case_doc_link`** (four `NOT NULL`s dropped, `ck_case_doc_link_target` in their
  place, six columns copied) and `DocumentationFacts` grows a **`UNION ALL` second arm** joining
  `test_case` alone — three of the four predicates are *absent rather than relaxed*, because there is
  no page. `source_type = 'TEST_CASE'` is on **both** arms; dropping it from the second inserts a
  suite id into `case_id` and the FK aborts the refresh. **`stale` is the literal `false` there**
  (`V47` §3): the sha pins the lines, so what could fall behind is the pin, and that is a different
  question. Putting the block arm's comparison on this arm yields NULL into a `NOT NULL boolean` and
  aborts the whole recompute.
- **The external copies cannot go stale**, unlike the page title and block excerpt beside them:
  `doc_link` has no update path at all, so a row is right or the link was deleted.
- **`MEMBER_DIVERGENCE_BY_DECISION` is now `Map.of()`.** Ten entries, all removed by deletion, four
  of them in §166–§169. An empty map is a result: every named object schema and its record agree
  both ways. `ExternalLink` and `ExternalLinkInput` are both **named** schemas paired in
  `RESPONSE_RECORDS` — the build failed until they were, which is the rule working.
- `CaseDocLinkRepository`'s `ORDER BY` leads with `targetKind` and closes with `id`; the old
  `pageKey, blockId` was total only while every row was a block link.

---

## §168 — THE COVERAGE MARKER IS SERVED, AND NOTHING WAS BUILT TO SERVE IT

**`DocBlock.coverage` is on the wire** — plan §6i's inline marker, declared from M0 and served by
nothing until now. **No migration, no table, no column.** Read this before touching `BlockLinks`,
`CatalogueFacts` or `DocsPage.tsx`'s `CoverageMarker`.

- **The entry's stated obstacle ("two modules `documentation` may not name") had been false since
  §166.** That slice mirrored the last result onto `case_health.last_status_kind` — one column, in
  `repository`, the module `CatalogueFacts` has been allowed to name since §93. **Three of
  `MEMBER_DIVERGENCE_BY_DECISION`'s four opening entries closed by re-reading the argument rather
  than by removing what it named** (§160's `rawStorageKey` is the same shape a third time). The map
  is pinned so a *closed* divergence cannot keep its argument; it has **no pin on an argument whose
  obstacle expired while the gap remained**. `ContractConventionTest`'s header now says: name the
  obstacle precisely enough that a later slice can tell it has gone. **The map now holds no
  module-boundary entry at all** — only the six `DocLink.external*` keys.
- **`CatalogueFacts.lastResultKinds` is the second thing this module asks `repository`.** Still the
  ONE file here that may name a `repository` table; do not add a second for a "short" query. It
  restates the project and soft-delete predicates rather than trusting its caller, and collapses "no
  `case_health` row" and "null kind" into one absent-from-the-map answer.
- **The marker is assembled in `BlockLinks`, from rows it had ALREADY resolved** — not in a
  `BlockCoverage` component beside it. Sharing the resolved map is what makes the marker and the
  bullets incapable of disagreeing about which links exist; a second read of `doc_link` would put
  "2 cases verify this" over one visible bullet. `forPage` returns a **`PageLinks` record**, not a
  bare map, for the same reason: a caller that can fetch links without the marker can render a block
  whose marker counts links the page did not show. Three queries became four; the fourth is skipped
  when no link names a case.
- **`VERIFIES` only** (matching `CoverageMatrixReport`'s spine), **null and never zeroed** on an
  unverified block, and **`passed + failed + notRun == linkedCases`** because `notRun` is the
  computed remainder over six `StatusKind`s. The kind is compared **by name**, never parsed into the
  enum — an unknown kind must fall through to `notRun`, not throw while rendering a specification.
- **The seam to know about: this buckets by `StatusKind`, the coverage matrix reads
  `counts_as_pass`.** A "Passed with comment" status (kind `PASSED`, `counts_as_pass = false`) is a
  pass here and not there. Unreachable from this module — `case_health` carries the kind, not the
  status id. Closing it means a second mirrored column.
- **`links` and `coverage` are now in `DocBlock`'s `required`** (both always emitted; present-and-null
  is an answer, absent is not), which cost one frontend fixture — §167's `caseBody()` again.
- **`CoverageMarker` in `DocsPage.tsx` had rendered against a field nothing sent since M0.** One line
  changed: null and zero are one branch, because the `!coverage` arm drew an *empty margin* exactly
  where §6i wanted "Not tested".

## §167 — `relatedDocuments` IS SERVED, THROUGH A TABLE AND NOT A COLUMN

**`TestCase.relatedDocuments` is on the wire.** It was declared from M0 and served by nothing, so
the traceability panel said *"Not linked to any specification"* about every case in the product,
including ones linked from the doc page minutes earlier. Read this before touching `case_doc_link`,
`DocumentationFacts` or the case detail's traceability panel.

- **A new read model, `case_doc_link` (`V46`, policy count 64) — not three more columns on
  `case_health`.** §166's argument for columns was arithmetic (one fact, one row) and does not
  transfer: a case has **zero or more** links, and a column holds one value. The alternative to a
  table is JSON in a column, which the database cannot index, join or count.
- **`DocumentationFacts` is the only file in `repository` that may name a `documentation` table** —
  `documentation.CatalogueFacts`'s mirror image, exactly as `ExecutionFacts` is the one file naming
  an `execution` table. **Do not add a second one for a "short" query.** It replaces the whole
  company's mirror **DELETE-then-INSERT**, not `ON CONFLICT`: an upsert cannot express a withdrawal,
  and unlinking/block-removal/page-deletion are all ordinary removals here.
- **The refresh is now the FIFTH statement pair in `CaseHealthRecomputeService.recompute()`, same
  transaction as the three signals and `lastResult`.** It has to be: the two documentation-derived
  health signals (`OUTDATED_SPEC_CHANGED`, not yet built; and this table's own `stale` column) are
  computed FROM `case_doc_link`'s rows, and a mirror refreshed by one job and read by another is the
  traceability panel disagreeing with the case's own chip about whether a spec moved.
- **First non-`execution` outbox event: `OutboxEvents.DOC_LINKS_CHANGED` (`documentation.links_changed`),
  coalesced, one handler (`HealthRecomputeOnLinksChanged`).** Two producers, and the second is the
  one to remember when adding a third link-adjacent feature: `BlockLinkService` fires it on
  create/delete, **and `DocPageWriteService.edit` fires it too** — `case_doc_link` copies the page's
  title and a block's text, so editing a page changes what a case's panel says exactly as creating a
  link does. `#create`/`#setStatus`/`#delete` on that class are NOT producers (a page reaching
  `softDelete` already has zero links; `#create` makes a page nothing links to yet; `#setStatus`
  moves a column the mirror does not carry).
- **`stale` is a content-VERSION comparison on both sides, never `updated_at`** — same refusal
  `ApprovalFacts` made for `STALE_APPROVAL` (§157), doubled: assigning a case an owner or moving its
  lifecycle status also touches `updated_at`, so the timestamp spelling would forgive every
  accumulated spec change on an act that read nothing. **Page granularity, not block** — `doc_block`
  carries no timestamp, so an edit to an unrelated paragraph on the same page raises every link into
  it. Stored at refresh time, not computed at read time, so this marker and the eventual
  `OUTDATED_SPEC_CHANGED` chip move together rather than disagreeing for up to an hour.
- **No cross-module FK, on either the `documentation` ids (`page_id`, `block_id`) or the mirror-image
  side (`doc_link.source_id`).** Cross-module FKs are allowed only toward `tenancy` / `identity` /
  `project` / `storage` (plan §3) — the FKs on `case_doc_link` are `company_id` and `case_id`, the
  two the rule permits.
- **`relationship` and `targetKind` are `String`, never an enum, on both `CaseDocLink` and
  `DocLinkView`.** The value sets are `documentation`'s (`LinkRelationship`, `doc_link`'s
  `target_kind` CHECK); re-declaring either here would be a second copy of another module's value set
  in a module forbidden to import the first, and a value added there would arrive as an
  `IllegalArgumentException` on read rather than a compile error.
- **The excerpt bound (160 chars, ellipsised) is a second spelling of
  `reporting.CoverageMatrixReport.STATEMENT_LIMIT`, and nothing checks the two agree** — `repository`
  may not import `reporting`. Deliberately different on one point: a wordless block is `NULL` here,
  `"(empty block at position N)"` there — a blank cell reads as a rendering fault, an absent panel
  line reads as nothing, which is what it is.
- **The contract change was `required`, same shape as §166's `lastResult`.** Both `schema.d.ts`
  copies changed; **this time a frontend test fixture needed a matching edit** —
  `CaseDetailPage.test.tsx`'s `caseBody()` gained `relatedDocuments: []` — because `required` makes a
  missing key a `tsc` error on any fixture asserting the full response shape, not just a runtime gap.
- **`TestCase.relatedDocuments` left `MEMBER_DIVERGENCE_BY_DECISION` by DELETION** (second divergence
  closed that way; §166's two `lastResult` entries were the first). **Six new entries arrived with
  it**: `DocLink.external{Repo,Path,LineStart,LineEnd,CommitSha,Url}`, CONTRACT-ONLY and paired
  together — pairing `DocLink` with the record that finally serves it (`DocLinkView`) made them
  visible for the first time. They are a designed-but-unbuilt capability (`V1`'s `ck_doc_link_target`
  allows `EXTERNAL_GIT`/`EXTERNAL_URL`; nothing writes either), not a module-boundary gap, and all six
  leave together the day an external target gets a writer. **`DocBlock.coverage` is the one entry
  left from §165's original four** — the control, still blocked by needing two modules
  `documentation` may not name.
- `CaseRenderer.docLinksFor` is the third bulk-lookup method beside `flakesFor`/`attachmentsFor`, one
  query for any batch size, feeding both `CaseBulkService`'s snapshot and the single-case render path.
  Detail only (not on `TestCaseSummaryView`) — same reasoning as `attachments`: the grid draws what a
  case *is*, traceability is read while deciding what to do about one case.

---

## §166 — `lastResult` IS SERVED, AND `case_health` IS THE PER-CASE READ MODEL

**`TestCaseSummary.lastResult` is on the wire.** It was declared from M0 and served by nothing, so
`CaseGrid`'s Last result column read **"Never run" for every case in the product** — executed ones
included. Read this before touching `case_health`, `ExecutionFacts` or either case view.

- **`case_health` is not only health signals.** `V45` added `last_status_kind`, `last_executed_at`
  and `last_release_version`. The table name is narrower than its contents **on purpose**:
  `never_executed`, `long_unexecuted` and those three are **one fact** (the newest recorded
  execution) computed by **one statement**, and they are rendered inches apart on one grid row — two
  tables would be two answers to "has this case ever run" on one screen. V45's header is the full
  argument; the honest alternative was renaming the table, not splitting it.
- **`ExecutionFacts` is still the ONLY file here that may name an `execution` table, and it is still
  WRITE-SIDE ONLY.** Its opening clause ("neither is ever needed as a value") is struck — the last
  result is a value — and the property is not. **Do not add a value-returning helper to it.** A
  "just fetch the last result for these ids" method satisfies every ArchUnit rule in this repo and
  gives away the thing they protect; on the detail endpoint it is a single row, which is why
  somebody will propose it.
- **The lateral takes the ROW now (`ORDER BY … LIMIT 1`), not `max()`** — so
  `AND e.executed_at IS NOT NULL` is load-bearing: **Postgres sorts NULLs FIRST under `DESC`** where
  `max()` skipped them. That state is unreachable today (`TestExecution.record()` sets both
  columns), so **no test can catch its removal** — recorded as `§166 LR-1`.
- **The `ON CONFLICT` must write the three `last_*` columns unconditionally.** `EXCLUDED` carries
  the null out of the `LEFT JOIN LATERAL`, which is what clears a result whose executions were
  removed. A `COALESCE(EXCLUDED.x, case_health.x)` "only update when non-null" guard leaves a
  deleted run's verdict on the case forever while `never_executed` goes correct — the two then
  disagree on one grid row (`§166 LR-4`).
- **A migration cannot backfill a company-owned table.** Flyway runs as `tcms_owner`, which does not
  bypass `FORCE ROW LEVEL SECURITY`, so `app.current_company` is unset, V4's `nullif` matches
  nothing and the UPDATE reports a confident zero. The job owns the backfill; that is also why the
  `never_executed` ⇔ `last_executed_at IS NULL` invariant is a **test**, not a `CHECK`.
- **The contract change was one word: `required`.** The shape was already declared. Jackson's
  default inclusion means the key is always emitted, so "always present, sometimes null" is what the
  response *is* — and optional would have left a consumer unable to tell "never run" from "not
  served". Both `schema.d.ts` copies: `lastResult?:` → `lastResult:`. No frontend source changed.
- **The two `lastResult` entries left `MEMBER_DIVERGENCE_BY_DECISION` by DELETION.** That map is
  pinned both ways, so closing a divergence and keeping its argument fails the build — first time
  that fired on a divergence somebody actually closed. ~~`TestCase.relatedDocuments` and
  `DocBlock.coverage` remain**, and `relatedDocuments` does **not** get a column on `case_health`:
  a list of links is not one, so it needs its own table. That is the next slice.~~ →
  **`relatedDocuments` CLOSED BY §167**, through the table this paragraph predicted
  (`case_doc_link`, not a `case_health` column). `DocBlock.coverage` is the one entry left.
- `CaseSummaries.healthByCase` returns the **rows** now, not `List<HealthSignal>` — two projections
  off one bulk lookup, so the last result costs **no extra query**.

---

## §165 — EVERY NAMED OBJECT SCHEMA IS PAIRED WITH ITS RECORD, AND COMPARED BOTH WAYS

**`ContractConventionTest.RESPONSE_RECORDS` is 79 pairings, not four, and the rule is two-way.**
Read this before adding a schema, adding a record, or changing either's members.

- **Adding a property to a paired schema is a TWO-FILE edit, in either order**, and the second is
  not optional — `pairedSchemasAndRecordsNameTheSameMembers` fails otherwise. Same for adding a
  record component. This is §164's `Problem` rule generalised to every named object schema.
- **`everyNameMatchedRecordIsPairedOrExplained` is the population rule and nothing like it existed.**
  A production record whose simple name matches a named object schema (`Foo` or `FooView`) must be
  paired, in `PAIRED_ELSEWHERE`, or in `HOMONYMS`. **A name match PROPOSES; the map entry is a human
  accepting it** — that is why it stays hand-written, and the class literals then make a rename a
  compile error.
- **`MEMBER_DIVERGENCE_BY_DECISION` has four entries, all CONTRACT-ONLY, and is pinned BOTH ways** —
  closing a divergence without deleting its entry fails the build. SERVER-ONLY is the defect
  (Jackson writes it, no generated union names it); CONTRACT-ONLY costs a dead branch. Same
  asymmetry `ContractEnumTest` records for the enums.
- **`Problem` is in `PAIRED_ELSEWHERE`, deliberately** — §164's `theContractsProblemIsThisRecord`
  already pins it both ways *and* compares the two envelopes as serialized bytes, which a static
  record-component comparison cannot do. Do not fold it in; the population rule still accounts for it.
- **`BulkResultView` and `ReportDefinitionView` are package-private and are named by STRING** in
  `PACKAGE_PRIVATE_OWNERS`. **Do not widen their visibility to tidy this up** — that is a production
  change made for a test. The cost is recorded: those two fail at runtime rather than at compile time.
- **The rule reads `src/main/java` source text for record NAMES and imports no ArchUnit graph**, on
  purpose: a third live class graph is what hung the build at 522685K of 512m (§161).
- **`requiredPropertiesHaveAComponent` IS GONE** — strictly contained in the two-way rule (a
  required property with no component is a schema-only member). §66's `createdBy` history lives in
  the new rule's failure message. Older docs (§68, the M2.5 plans) still name it.
- **`OpenApiContract` gained `propertiesOf` and `objectSchemas`**, both `allOf`-resolving and both
  **component-level only** — inline object shapes on a property are out of scope for the same
  measured reason `enumSchemas()` gives, and that population is the named next candidate.

### The three arguments §165 corrected, and the two live product gaps under them

- **NEVER write "the module does not exist" as a reason.** Three sites did —
  `TestCaseSummaryView`'s header, `TestCaseDetailView`'s header (twice) and `openapi.yaml`'s
  `TestCase` comment — while `execution` is **119 files** and `documentation` is **57**. The live
  obstacle is the **module boundary**: `repository` may not import either, so each closes as a read
  model fed by events (§3 lesson 4). `repository/package-info.java` said this correctly the whole
  time, forty lines from the expired version. §160's shape, and the cost is the same: an argument
  that misnames the obstacle deters the repair.
- ~~**`TestCaseSummary.lastResult` HAS NEVER BEEN ON THE WIRE**, so `CaseGrid`'s Last result column
  and the case detail's read **"Never run" for every case in the product**, executed or not.~~ →
  **CLOSED BY §166** — see the section above. Kept because it is what the repair was written
  against, and because the measurement (a declared field served by nothing for nine months) is the
  finding rather than the fix.
- **`TestCase.relatedDocuments` likewise**, and links have been creatable since
  `POST …/blocks/{blockId}/links`: **a doc link is never shown on the case it links to.**
  `DocBlockView` struck its "nothing can create a link" clause when that shipped and the
  `repository` side never heard — one claim, struck in one module, left standing in another.
- **`DocBlock.coverage` is the control** and holds: the one argument that was *revised* when its
  constraint moved rather than left to expire.
- ~~Both gaps are~~ ~~`relatedDocuments` is~~ **both are CLOSED, `lastResult` by §166 and
  `relatedDocuments` by §167** — and both have browser rows (`§165 RC-1` — revised in place by §166,
  `§165 RC-2` — see §167's queue note) that exist so a live pass **looks for the value rather than at
  the widget** — each screen is a correct rendering of a key the server does not send, which reads as
  working.

### `TestPlan.keySeq`, and `TeamRef` is three types

- **`keySeq` is declared on `TestPlanSummary` now.** It was already on the wire — `TestPlanView` has
  had the component since §74, all seven `TestPlan` operations send it — and no generated client
  could name it. **§100 widened this exact schema by hand and missed it.**
- ~~**`TeamRef` is THREE records, not the two §147 recorded**, and only one serves the named schema:
  `project.TeamRefView` (via `ReviewAssignee.team`, the schema's single `$ref`);
  `identity.RoleAssignmentView.TeamRef` (serves `RoleAssignment.team`, **inline**);
  `auth.CurrentUserService.TeamRef` (serves `CurrentUser.teams`, inline, and carries a third
  component **`isLead`**). The last two are `HOMONYMS` entries, themselves checked for staleness.
  **Consolidating the first two is a contract change before it is a Java one**, and the third has a
  member the schema does not declare~~ → **§175 did exactly that and there is now one record,
  `com.tcms.api.TeamRef`** — see that section above. The sentence was accurate and it was also the
  work order: the contract change came first, `isLead` was deleted, and the Java collapse was what
  was left.

## §163 — NO REFUSAL ECHOES THE REQUEST, AND THE PROPERTY WAS WRITTEN BY SPRING

**Read this with the §162 section below; together they are the whole of what a refusal looks like.**

- **`api.ProblemMediaType` IS NOW `api.ProblemEnvelope`** and does two things, not one: sets
  `application/problem+json` on the record and on Boot's `/error` (§162), and **clears
  `ProblemDetail.instance` on every response** (§163). Renamed because a class that strips a
  property under a name saying it decides a header is §152's shape.
- **`instance` WAS BEING WRITTEN ON ALL 21 `ProblemDetail` CONTROLLERS AND NOTHING IN THIS REPO ASKED
  FOR IT.** `setInstance` occurs **zero** times in `backend/src`. Spring Framework 6.2's
  `HttpEntityMethodProcessor` and `RequestResponseBodyMethodProcessor` each do
  `if (detail.getInstance() == null) detail.setInstance(URI.create(request.getRequestURI()))` on the
  way to the converter. **Not `ResponseEntityExceptionHandler`** — this app registers none, and
  `spring.mvc.problemdetails.enabled` appears in no config file. *The obvious hypothesis was that
  `instance` was written on zero refusals; every instrument that hypothesis was built from reads
  `src/main`, and the property is not in `src/main`.*
- **The decision was suppress, not declare**, argued from §162 (a refusal's shape must not depend on
  which of four writers produced it), from cost (the other direction needs a component
  `api.Problem`'s factories cannot fill, a hand-composed path in `ProblemWriter`, and the exact key
  §146 deleted put back in `ProblemErrorAttributes`), and from §141 (`detail` says what to do,
  `errors` says which field, a path says where the caller just was).
- **THE ADVICE MUST RUN AFTER THE RETURN-VALUE HANDLER** or the clear is a no-op. It does —
  `HttpEntityMethodProcessor` sets `instance` and *then* calls `writeWithMessageConverters` — and
  that ordering is asserted, not reasoned, by driving the real serialization.
- **Two rules, and they lock in opposite directions.**
  `ErrorBodyPolicyTest.noRefusalBodyEchoesTheRequest` reads the **server**: each envelope's
  serialized key set must be a **subset** of what `openapi.yaml`'s `Problem` declares — general, so
  any undeclared property arriving on a refusal fails, not just this one.
  `theProblemSchemaDeclaresNoRequestEcho` reads the **contract**: `Problem` may declare no member
  from the echo family (`instance`, `path`, `uri`, `url`, `requestUri`, `requestUrl`, `resource`).
  Without the second, a slice could satisfy the first by re-declaring the property — which is the
  edit §163 reversed.
- **Third check, real HTTP:** `ErrorDispatchStatusTest.aControllerProblemDetailCarriesNoInstance`
  (an Automation Service token refused `token:issue` → `ApiTokenController`'s `ProblemDetail` 403),
  and `theBodyIsAProblem` now asserts no `instance` in the same words it has asserted no `path`
  since §146.
- ~~**Still not pinned:** `Problem` is the one contract schema written by **two Java types**, and
  nothing compares their field names.~~ → **PINNED §164 — see the section immediately below.**

## §164 — THE TWO ENVELOPES AND THE SCHEMA ARE NOW COMPARED, BOTH WAYS

**Read this with §163 and §162 above; the three together are the whole of what a refusal looks like.**

- **`ErrorBodyPolicyTest.bothEnvelopesSerializeTheSameKeys`** — the two envelopes as **serialized
  JSON keys**, on equivalent refusals, through the same standalone `MockMvc` fixture. §162's queue
  excused this gap (*"only one [type] this project owns, so the record-component comparison has
  nothing to compare"*) — true of a **static** comparison, false of the bytes.
  **`noRefusalBodyEchoesTheRequest` does not cover it and cannot**: that is a *subset* rule against
  the contract, and both envelopes can satisfy it while sending **different subsets**.
- **`ErrorBodyPolicyTest.theContractsProblemIsThisRecord`** — `Problem`'s declared properties
  against `com.tcms.api.Problem`'s record components, **both directions**. A property only the
  contract has is a promise nothing keeps (§148); a component only the record has is a value on the
  wire no generated client can name (the direction `instance` went). §161's `ContractEnumTest` is
  this rule for the twenty enums and `RESPONSE_RECORDS` is it for four response schemas — **the
  envelope every refusal answers with was in neither.**
- **New fixture `/record-with-errors`** (`Problem.validation(…)`), the counterpart to
  `/detail-with-errors`, so both shapes are compared. `errors` is the one optional member, hence the
  one a writer can set and its counterpart omit while every other rule in the file stays green —
  which is exactly mutation `EN-1b`, red on the new rule **alone**.
- **Adding a member to `Problem` is now a two-file edit**, in either order, and the second is not
  optional. `openapi.yaml`'s comment says so and names both rules.
- ~~**Do not "simplify" these into `RESPONSE_RECORDS`.** That map is four entries and **one
  direction** … folding `Problem` into it as it stands would lose the direction this section exists
  for.~~ → **§165 generalised it: the map is 79 pairings and two-way.** The instruction still holds
  and now for a different reason — `Problem` sits in `PAIRED_ELSEWHERE` because §164's rule *also*
  compares the two envelopes as serialized bytes, which the static rule cannot. See the §165 section
  above.

## §162 — ONE MEDIA TYPE FOR EVERY REFUSAL, AND THERE ARE TWO ERROR ENVELOPES

**Read this before touching any refusal, any `Problem`, or `openapi.yaml`'s error responses.**

- **THERE ARE TWO ENVELOPES AND BOTH ARE LEGITIMATE.** Spring's `ProblemDetail` in **21**
  production files; `com.tcms.api.Problem` (the record, with the curated `validation` / `stale` /
  `conflict` / `forbidden` factories) in **8**. `api.Problem`'s header and `api/package-info.java`
  both used to say the record was *the* envelope "every refusal answers with" — **it never was**,
  and §146 did not make it so: that slice removed the copies that could not *reach* a shared record
  and never touched the ones that had chosen `ProblemDetail`. Both claims are struck in place.
- **ALL REFUSALS ARE `application/problem+json`.** Four writers, three mechanisms:
  | writer | how the type is set |
  |---|---|
  | `ProblemDetail` (21 files) | Spring, by construction — and it **beats** an explicit `Accept: application/json`, which is what `@tcms/client` sends |
  | `api.Problem` (8 files, ~50 sites) | `api.ProblemEnvelope` (named `ProblemMediaType` until §163), a `ResponseBodyAdvice` keyed on the **type** — no call site sets it |
  | Boot's `/error` dispatch | the same advice, matched on `BasicErrorController` (its mapping is hard-coded `produces = application/json`) |
  | `ProblemWriter` (the filter 429) | set directly — **a filter's response reaches no advice** |
  So: **returning a `Problem` from a new controller needs no content type.** Writing one from a new
  *filter* does, and there is exactly one existing example.
- **`ErrorBodyPolicyTest.everyDeclaredErrorBodyIsProblemJson` is the contract-side gate**, keyed on
  "declares content at all" rather than on `BODILESS_STATUSES`/`BODIED_STATUSES` — those two name
  six statuses between them and the document declares 403 eighty times and 413 four, so neither had
  an opinion about either. A new error response declaring `application/json` fails the build.
- **What it cost before the gate: 132 of 176 declared error bodies named the wrong media type, in
  both directions.** Nothing observed it — `problem+json` occurred **zero** times in
  `backend/src` — because both TS consumers call `.json()` without reading `Content-Type`. The
  falsehood was in the generated types, not at runtime.
- ~~**`Problem.instance` is declared, optional, and UNRESOLVED.**~~ → **RESOLVED §163 — see the
  section above this one. `instance` is suppressed and undeclared; no refusal echoes the request.**

## §162 — AN EXEMPTION MUST STILL EXEMPT SOMETHING

- **`ContractConventionTest.everyExemptionStillExemptsSomething`** (+ the `DELIBERATELY_UNBOUND`
  sibling in `ContractParameterBindingTest`) pins the file's ignore lists in the **second**
  direction. A one-directional list cannot report a member that stopped needing to be on it: the
  skip that was never going to fire looks exactly like a rule being obeyed.
- Two were dead when the rule was written: `CaseConditionInput` (§95 gave the schema
  `required: [conditionId]`; the exclusivity its comment argued from is gone) and
  `CONTAINER_ERROR_PATH`, which exempted `ProblemErrorAttributes` from the `HttpStatus.NOT_FOUND`
  ban — **a string that file has never contained.** It bodies a 404 via the int `404`, i.e. by a
  spelling the scan cannot see. *An ignore list naming the wrong mechanism reads as coverage and is
  the absence of it.*
- **`ArchitectureRulesTest`'s and `ContractEnumTest`'s maps did not rot**, and that is the whole
  finding's control group: every one of them is asserted in both directions.

---

## §161 — CONTRACT ENUMS ARE CHECKED AGAINST SERVER ENUMS; THE BY-DECISION MAPS WERE RE-DERIVED

`contract/ContractEnumTest.java` is the new seam — the **third** contract-versus-server rule, after
`ContractParameterBindingTest`'s two. No Spring context, no database: one YAML parse
(`OpenApiContract.enumSchemas()`, new) and one ArchUnit import.

- **`OWNERS` is the contract↔server correspondence and nothing recorded it before §161.** Twenty
  named enum schemas; the map must cover them exactly, both ways, so a new one fails the build until
  somebody names the type that serves it. **Do not treat it as an ignore list.**
- **Four schemas have TWO owners** — `Priority` (`repository`+`work`), `StepFormat`
  (`repository`+`execution`), `AggregationPolicy` (`reporting`+`execution`). Copied because a
  feature module may not import another's types; both copies are compared, which is the point.
- **`ReportFormat` is a HOMONYM, not a copy.** `execution.ReportFormat` = the dialect CI posted
  (JUNIT/TESTNG/ALLURE/CUCUMBER, and the contract's schema); `reporting.ReportFormat` = what a
  report's bytes are (PDF/MD/HTML/CSV/XLSX/JSON). One word of context apart. `HOMONYMS` holds the
  second, and that entry is itself checked for staleness.
- **Direction is recorded, and it is not symmetric.** SERVER-ONLY is the defect (a constant Jackson
  writes that no generated union contains — `undefined` in every `satisfies Record<Union, …>`);
  CONTRACT-ONLY is a dead branch. `DIVERGENCE_BY_DECISION` has two: `HealthSignal.INCOMPLETE`
  (contract-only) and `PermissionQualifier.NONE` (server-only, safe because `PermissionSet`'s
  constructor **throws** on a key held as `NONE`).
- **An enum constant is `ACC_ENUM`, not "a static field of the enum's own type."**
  `execution.AggregationPolicy.DEFAULT` is a `public static final` alias for `LATEST` and the first
  draft reported it as a missing contract member. `JavaModifier.ENUM` is the filter.
- **Never spell a column name in a string literal in a test that argues a column is dead.**
  `ArchitectureRulesTest.everyMappedColumnHasAReader` reads *string literals* across `src/main` and
  `src/test` with comments stripped, so such a literal marks the column read and its
  `READERLESS_BY_DECISION` entry disappears. Identifiers go in the javadoc. §154 solved this for its
  own file by exclusion; the shape is general.
- **`CaseHealth.incomplete`'s reason is now "the SERVER's `HealthSignal` has no constant, because
  nothing derives it"** — in three places (field javadoc, both decision-map entries). The old reason
  blamed the contract, which has carried `INCOMPLETE` since 2026-07-30. Closing it is a derivation
  plus a product decision; **it is not a contract change**.
- **§154's HANDOFF SECTION IS FLAGGED "REDO REQUIRED" — do not quote it as fact.** The *rule* it
  shipped (`everyMappedColumnHasAReader`) is sound and passes; two of its written claims were
  disproved and stood unstruck for two days — that `RunController.onWriteRace` "has never been
  reachable" (§155: it is, via the execution) and that `IngestionJob.rawStorageKey` was *written*
  and merely unread (§117 stored neither the file nor the key; §156, §160). Both are struck in place
  now. Its `RL-*` mutation ids also collide with §141's, one of three collisions in that file.
  **The claim audit RAN on 2026-08-11 and closed that item**: all twelve surviving
  `READERLESS_BY_DECISION` members re-derived one at a time, rule re-run green, **eleven hold and
  two more were wrong** — `SuiteCase.addedBy` is `jwt.getSubject()` from `SuiteController.create`,
  **not** the "caller-supplied actor id" §154 called it at three sites, and
  `CompanyMembership.invitedBy` has **three** writers (`MembershipInviteService`'s two plus
  `CompanyProvisioningService`, which passes null on an unattended run), not the two factory methods
  §154 counted. Four wrong claims in one section, none of which touched the rule: the check
  recomputes its population, the arguments beside it are prose.
- **AN ID IN A COLLIDING MUTATION-QUEUE RANGE IS WRITTEN `§154 RL-4`, NEVER BARE** — decided
  2026-08-11, closing §161's second item. Three ranges in
  `docs/verification/backend-mutation-checks.md` are minted twice: `AG-*` (§124 vs §133), `CD-*`
  (§128 vs §158), `RL-*` (§141 vs §154). All six tables carry the section in the `id` column and
  **nothing was renumbered** — an id a handoff section has quoted keeps its meaning. **Grep the
  file before minting a prefix**: every collision came of two lanes picking the obvious initials.
  **`READERLESS_BY_DECISION` holds TWELVE members, not §154's thirteen** (`rawStorageKey` left with
  §156/§160), which is why `§154 RL-1` no longer works as written — it names that column. Only
  `§154 RL-3`…`RL-6` remain unrun, deferred under §65 on purpose.
- **THE TEST WORKER'S HEAP IS PINNED AT `2g` IN `build.gradle.kts` — DO NOT REMOVE IT.** Gradle's
  default is 512m and the suite was measured at **522685K of 524288K** while hanging. An exhausted
  G1 heap here throws nothing and fails nothing: every test passes, then the worker parks for ever
  in `MessageHub.stop` and the build never returns or prints. Three ArchUnit class graphs live in
  that heap (two `static final` in `ArchitectureRulesTest`, one in `ContractEnumTest`, the last
  released in `@AfterAll`), and they grow with the codebase. A build that "hangs after the tests
  pass" is this, and `jcmd <worker pid> GC.heap_info` is how to confirm it in one command.
- **`openapi.yaml`'s `HealthSignal` no longer enumerates which signals have writers.** That list was
  stale from §157 to §161 and is a second copy of `WRITERLESS_BY_DECISION`. Do not re-add it.

---

## §160 — `raw_storage_key` HAS BOTH HALVES; `storage` HAS A THIRD PORT AND A THIRD ROOT

`storage/RawReportStore.java` + `FilesystemRawReportStore.java` are the new seam — the **third**
blob port, beside `AttachmentStore` and `ReportStore`, over the same `FilesystemBlobStore` body.
`IngestionJob.rawStorageKey` leaves **both** `ArchitectureRulesTest` decision maps at once:
`READERLESS_BY_DECISION` is **twelve**, `WRITERLESS_BY_DECISION` is **ten**.

- **`RawReportStore` IS NOT `ReportStore` and the names are one letter apart.** `ReportStore` holds
  reports this product *generated* and can regenerate from `execution_fact`; this holds the file CI
  *sent*, which nothing can reproduce. Wrong one handed over = a compile error, which is the whole
  reason the module keeps them as separate types rather than one bean behind a qualifier.
- **Three roots now**, and `IntegrationTestBase` registers three temp directories.
  `tcms.storage.raw-reports.root` / `TCMS_RAW_REPORTS_ROOT`. A new store means a line in
  `application.yml`, a line in `compose.prod.yaml`'s environment **and** one under its `volumes:` —
  a root without a mount is the runbook's recorded outage.
- **`IngestionJob.completed` takes the id from the caller.** The only factory in `execution` that
  does. The blob key's middle segment is the job id, so the id must exist before the row; the
  alternative was a second mutator on an entity whose header says nothing edits an ingestion job.
- **Row first, bytes second, inside the transaction** — `EvidenceService`'s ordering, not
  `ReportJobRunner`'s. This is external I/O inside a transaction boundary on purpose: 1 MB cap,
  local write, and the sibling caller in this module does the same. Do not "fix" it.
- **`IngestionReadService.openRawReport` and `locate` are BOTH unannotated.** Annotating `locate`
  would be a self-invocation Spring's proxy never sees — no `app.current_company`, every
  RLS-protected read silently zero rows. `ProjectService.readVisible` and the repositories carry the
  boundaries. `ReportReadService.open` is the shape being copied.
- **The gate is membership** (`readVisible`), not a new key. No `ingestion:read` exists and one
  seeded to nobody is a 403 nobody can grant their way out of.
- **`IngestionJobView.reportUrl` is server-composed and required-and-nullable.** Null is the only
  wire signal distinguishing a job posted before uploads were kept; `raw_storage_key` itself is
  deliberately not served. `IngestionJobView.reportUrl(...)` is the one place it is built.

---

## §157 — `STALE_APPROVAL` HAS A DERIVATION; FOUR HEALTH SIGNALS ARE LEFT WITHOUT ONE

`repository/ApprovalFacts.java` is the new seam and the first exit from
`ArchitectureRulesTest.WRITERLESS_BY_DECISION` **by writing the column** rather than by deleting it.
Population there is **thirteen**, not fourteen.

- **`repository` may name `project`'s tables directly** — `project` is a foundation module, so this
  is a permitted edge and `ApprovalFacts` is a *convention* (one file, no second entity over
  `review`), not a boundary like `ExecutionFacts`. Do not describe the two as the same kind.
- **The predicate is a VERSION comparison** — `max(review.subject_version_no) WHERE state =
  'APPROVED'` against `test_case.current_version_no` — because that is what `ReviewPanel`'s stale
  banner has used since §76. The timestamp spelling (`updated_at > approved_at`) is shorter, looks
  equivalent, and disagrees with the banner: a no-op save moves `updated_at` and writes no version.
- **`lifecycle_status = 'APPROVED'` is a TERM, not a `WHERE` filter.** As a filter the statement
  skips the row and a demoted case keeps a stale flag for ever. Same for any future signal here.
- **No review settings predicate.** `ReviewService.moved` opens no review for a project with §6g
  off, so those projects are honestly zero; a predicate would erase the signal for a project that
  switches review off with approvals outstanding.
- **A review row needs `SUBMIT_FOR_REVIEW` first.** Going `DRAFT → APPROVED` writes **no** review —
  `moved` closes an open one and opens none — and `authorMayApproveOwnWork` defaults to false, so
  approving needs a second account. Any fixture for this signal has to do all three.
- **`CaseHealthRecomputeService` is no longer "execution-driven"** — three statements, one
  transaction, one `computed_at`. The `ON CONFLICT` column-ownership rule now matters in-module:
  each statement names only its own columns.
- **Tests must stop borrowing `stale_approval` as the empty column.** `CaseHealthRecomputeTest`'s
  clobber test uses `unlinked` now. The remaining borrowable ones are `outdated_spec_changed`,
  ~~`condition_drift`~~ (§158), `unlinked`, `orphaned` — and each disappears when it gets a writer.

## §159 — `project.archived_at` HAS A WRITER; ARCHIVING MARKS AND DOES NOT LOCK

`PUT /projects/{projectId}/archive` → `ProjectArchiveService` (`project` module).
`ArchitectureRulesTest.WRITERLESS_BY_DECISION` is **eleven**, not twelve — and that map's javadoc
was stale by one from §158 to §159 while the method comment below it was right, so read the map, not
the prose.

- **THE BODY NAMES THE TARGET STATE, NOT A VERB.** `{"archived": true|false}` on one endpoint, both
  directions. Two verb endpoints make a stale client do the opposite of what was pressed; this makes
  it a no-op. Absent field ⇒ **400 on the field**, never a default — a default un-archives.
- **IDEMPOTENT AND SILENT.** Setting the state a project already has writes nothing: no timestamp
  re-stamped, no audit row, no `lock_version` bump. Decided in `apply` before either mutator runs,
  which is why `Project.archive` does not defend itself.
- **THE GATE IS `project:settings` (PROJECT scope).** Not `project:create`: `createGranted` has **no
  `Unreachable` arm**, so a company key answers 403 for a project the caller cannot see — §6t's
  oracle through the gate. Roles spec §A6a withholds the key from both token-only roles, so no agent
  and no CI credential can retire a project; **do not add a second check for that.**
- **ARCHIVING IS A MARK, NOT A LOCK.** An archived project is listed, readable and fully writable —
  `DocPageStatus.ARCHIVED` one level up. Making it read-only is a **separate decision**: a fourth
  `GateResult` arm at **53 call sites**, pinned meanwhile by `Archiving.archivingDoesNotLock` and
  browser row `AR-3`.
- **`archived_at` stays a timestamp**; `unarchive()` clears it and the sequence lives in
  `audit_event` (`project.archived` / `project.unarchived`). No second column.
- **`ProjectService.viewOfChanged`** is the write-path assembler — `viewOfCreated`'s sibling, but it
  runs the case-count query. Use it for any future project write; `viewOfCreated`'s empty map is only
  correct for a project created in that transaction.
- **`ProjectEndpointTest` now writes.** Its seed calls `restoreArchiveState()` and must keep doing
  so: nothing cleans between tests and every helper there is "create if absent", so a state one test
  writes survives into the read-only assertions two groups up.

## §158 — `CONDITION_DRIFT` HAS A DERIVATION; THREE HEALTH SIGNALS ARE LEFT WITHOUT ONE

`repository/ConditionDrift.java` is the fourth statement in the recompute tick and the **second exit
from `ArchitectureRulesTest.WRITERLESS_BY_DECISION` by writing the column**. Population there is
**twelve**, not thirteen.

- **NOT named `*Facts`, deliberately.** That suffix means "rows another module owns, read through
  one file" — `ExecutionFacts` over a *forbidden* edge, `ApprovalFacts` over a permitted one. Every
  table here (`test_case`, `test_case_version`, `condition`) is `repository`'s own, so there is no
  crossing to announce. Do not rename it for symmetry.
- **THE JOIN IS `LEFT` AND `lib.id IS NULL` IS A TERM IN THE EXPRESSION.** A soft-deleted library
  condition is dropped by `CaseConditions.forCase`, so the live case renders one instruction fewer
  than the snapshot records — the *worst* drift — and an inner join makes it the case that reports
  clean. Its test's fixture is byte-identical to the snapshot except `deleted_at`.
- **THE COMPARISON IS CONTENT (title + body), NOT `updated_at`.** The timestamp spelling
  (`condition.updated_at > version.created_at`) is one join shorter and **can never go false**:
  editing a condition and reverting it leaves `updated_at` moved for ever. That is `case_health`'s
  self-healing rule inverted. The test suite **cannot currently distinguish the two** — no fixture
  expresses edit-and-revert — and `CD-6`/`CD-B3` say so rather than implying coverage.
- **The reference point is the NEWEST version's snapshot**, not the approved one. Pinning to
  approvals reports a confident zero for every project with §6g off, which is exactly the state
  `STALE_AUTOMATION` was stuck in from `V1` to M3.
- **Attachment changes are not reported and that is correct for the case's own arm**:
  `preconditionIds`/`postconditionIds` are on `CaseEdit`, so attach/detach moves
  `CaseVersions.hash` and writes a version that re-baselines. The **inherited** arm is a real gap
  (a suite-level attach writes no case version) and is named at the method rather than closed.
- **`StatusChip.tsx`'s tooltip said "since the case was last reviewed" and now says "last saved".**
  The server compares versions and never consults review; a project with review off raises this
  signal identically.
- **Four statements now share the `case_health` row.** The `ON CONFLICT` column-ownership rule binds
  `ExecutionFacts` ×2, `ApprovalFacts`, `ConditionDrift` and `reporting.ExecutionSource`. Each names
  only its own columns; adding a column to one of the five blanks another's work.

## §156 — A MAPPED COLUMN WITH NO WRITER FAILS THE BUILD NOW, AND FIVE HEALTH SIGNALS ARE THE COST

`ArchitectureRulesTest.everyMappedColumnHasAWriter` is the **fifth** rule in §152/§153/§154/§155's
family and §154's mirror. Population **fourteen**, pinned in `WRITERLESS_BY_DECISION` with the
argument at each field. The hand-kept tally it replaces is §116's, §117's, §132's and §151's — four
slices, four single instances, no question.

- **IT READS BYTECODE FIRST; §154 READS TEXT. Do not converge them.** A reader is a query and a
  query is a string; a **writer is an assignment**, which is what bytecode records. `isWrittenInJava`
  is the substance and mirrors `isReadInJava` including the one-level caller check — a mutator whose
  method has no caller is **not** a writer, which is the only reason `Project.archivedAt` is in the
  population.
- **The SQL arm is the remainder and needs BOTH spellings**: the **column** name for native SQL
  (`case_health` is written by `INSERT … ON CONFLICT`) and the **field** name for JPQL
  (`startIfNotStarted` names `r.startedAt`, `V1` names `started_at`). `.sql` files are in the corpus
  — §154's has no need of them — because a migration that **backfills** is a writer (`V44`) and one
  that only creates the column is not.
- **`writesInLiterals` matches ONE LITERAL AT A TIME and that is load-bearing.** A statement runs to
  the next `;`; `DocPageEndpointTest`'s unterminated `"UPDATE key_sequence SET next_value = 1"`
  swallowed every later literal in the file and an `@DisplayName` made `case_health.unlinked` read
  as written. **Over-matching here HIDES findings**, unlike §154 where it only defers them.
- **§154's by-decision javadoc was wrong about two members and is corrected**: `rawStorageKey` and
  `incomplete` are writerless as well. `IngestionJob.completed` takes no `rawStorageKey`, and the
  class header has said since §67 that the bytes are not stored either.
- **`Project.archive(now)` HAS NO CALLER and `ProjectView.archived` is `required` on the wire** —
  false in every deployment. Do not delete the mutator to "clean up": that leaves `isArchived` a
  constant with the contract still promising the field. What is missing is an operation.
- **`case_health` STOPPED BEING EMPTY AT §53 AND FIVE SIGNALS DID NOT** (**four** since §157,
  **three** since §158 — `stale_approval` and `condition_drift` are derived now, see the sections
  above). `outdated_spec_changed`,
  ~~`stale_approval`~~, ~~`condition_drift`~~, `unlinked`, `orphaned` have no computation;
  `CaseHealthScheduler` is `matchIfMissing = true` on an hourly delay, so **`computed_at` is
  non-null within two minutes of any deployment** and the dashboard's "not computed yet" branch —
  which five files still described as universal — is the rare one. The SPA's panel now says **"No
  health signal was raised"**, a claim about the checks; it must not go back to "Nothing needs
  attention" until the remaining three have writers. Do not make the columns nullable: that moves a three-valued
  signal onto every reader.

## §155 — `lock_version` IS EITHER `@Version` OR UNMAPPED, AND `TestRun` HAS NEITHER

`ArchitectureRulesTest.everyLockVersionColumnIsAVersion` is the fourth rule in §152/§153/§154's
family. No entity may map a column named `lock_version` without `@Version`. The tally it replaces is
**§74's, kept by hand**: three annotated, two left, and the note about the two in a javadoc on a
*third* entity from §74 to §155.

- **THE STATE IT FORBIDS IS A GUARD THAT CANNOT FIRE**, not dead weight. An unmaintained
  `lock_version` reads `0` for ever and it is the value an `If-Match` is compared against — the
  precondition passes over any number of intervening saves and no "a stale header is refused" test
  can go red. `TestPlan` came within one slice of shipping exactly that.
- **THE RULE HAS TWO EXITS AND THE ANNOTATION IS ONLY ONE.** `ApiToken`'s rubric decides: *is a
  lost update here a discarded edit, or a duplicated intent?* Both §155 survivors are **unmapped**,
  with the argument at the class. Population of violators is **empty** — no by-decision map, and
  the vacuity floor is `versioned > 10` (measured: 17 mappings, 15 annotated; §154 recorded 19).
- **`work_item.lock_version` — UNMAPPED. Do not "restore" it.** The only mutator is `moveTo`, an
  absolute destination from a drag; the contract already calls that "no lost update to protect" on
  three status endpoints. And `WorkItemController` has **no `OptimisticLockingFailureException`
  handler**, so annotating turns a board race that resolves correctly into a **500**.
- **`test_run.lock_version` — UNMAPPED, AND ITS RACE IS CLOSED BY TWO OTHER THINGS.** This is the
  one aggregate with **two writers that are not the same act**, overlapping on `status` alone: the
  first result (`status`, `started_at`) and a lead's close (`status`, `completed_at`). Each order
  discarded the other's column — a start after a close **erased the closure** with two 200s.
  1. **`TestRunRepository.startIfNotStarted`** — a conditional `UPDATE … WHERE status =
     NOT_STARTED`. `TestRun.begin` is **deleted**; its `if` is the `WHERE`, re-evaluated by Postgres
     under the row lock. Returns 1/0, and `ExecutionService` writes the `run.started` audit row only
     on 1. **Do not put the guard back in Java** — a snapshot cannot answer "has this started yet".
  2. **`@DynamicUpdate` on `TestRun`** — the only one in the codebase. It is why `applyStatus`
     stops writing `started_at` back. Removing it reddens one test (`LV-1`).
- `@Version` was the recorded candidate (`RL-7`) and is the **wrong instrument**: it makes the
  product's most-used write a contention point, and `TestPlan.lockVersion`'s header said so before
  §154 proposed it. `RunController.onWriteRace` **is** reachable — on the execution, not the run.
- **Suite storage roots now carry `user.name`** (`IntegrationTestBase`). Fixture prefixes are
  hand-assigned and identical everywhere, so a fixed `/tmp/tcms-evidence-test` is shared between the
  nightly lane's `ghrunner` and a developer on the same box: eleven `AccessDeniedException` failures
  about nothing you changed.

---

## §154 — A MAPPED COLUMN WITH NO READER FAILS THE BUILD NOW

`ArchitectureRulesTest.everyMappedColumnHasAReader` is the third question in §152/§153's family,
asked at the column: which mapped column does nothing read back. The tally §116/§117/§132/§151 each
kept by hand. **The population is 13** and it is pinned in `READERLESS_BY_DECISION`, both
directions, like §153's.

- **IT IS THE ONLY RULE IN THAT FILE THAT READS SOURCE TEXT, AND THAT IS LOAD-BEARING.** A column's
  reader is usually a query and a query is a string — JPQL in `@Query`, native SQL in a text block,
  or a Spring Data method *name*. Over bytecode alone the population is **212 of 635 fields**,
  measured. Do not "tidy" it back onto bytecode. `ConfigConventionTest` (§108) is the precedent.
- **Corpus = `src/main/java` + `src/test/java`**, minus three things, each measured: mapping
  annotations (else every entity's `created_at` marks every other one live), **comments** (nineteen
  columns read as live off a javadoc alone), and **`INSERT` statements** (seven read as live off the
  fixtures that create them). A reader is the column name as a whole word in another file, the field
  name in a string literal, the capitalised field name in any repository-declared name, or a Java read.
- **`OWN_FILE` EXCLUDES `ArchitectureRulesTest.java` FROM ITS OWN CORPUS AND THE RULE CANNOT FAIL
  WITHOUT IT.** Every key is the string `"Entity.field"`, so each *argument* for keeping a column is
  a literal naming it. Five of thirteen deleted themselves; the count went 7 → 13 when this landed.
  Third slice running: §152's javadoc, §153's prefix match, now this.
- **No exemption for `@Id`, `@Version` or `company_id`** — all three were carved out of a draft and
  all three are matched by the corpus anyway (`findById`'s derived name, the queries naming
  `lock_version`, the RLS predicate and filter condition).
- **What it cannot see, named:** the corpus has no idea which table a statement is about, so a
  native query naming *another* table's `lock_version` marks this one read. ~~`test_run` and
  `work_item` map it WITHOUT `@Version` … recorded as the next slice (`RL-7`)~~ → **§155 took it and
  the answer was no on both**: see the §155 section above. Its "19 other entities" was a hand count
  and is **15**.
- **Near misses recorded at the field, not pinned:** `ReportArtifact.generatedAt` and
  `AuditEvent.actorTokenId` are read only by a test's `SELECT`, which counts (§152's widening).

---

## §153 — A PUBLIC BEAN METHOD ONLY A TEST CALLS FAILS THE BUILD NOW

`ArchitectureRulesTest.everyPublicBeanMethodIsExercisedInProduction` is §152's question from the
other side: not *has this a caller* but *is every caller a test*. §152's widening (a test counts as
a consumer) holds for a **read** seam and not for a **write** one — a public bean method every test
and no production code calls is something the product has never done.

- **THE POPULATION IS PINNED, NOT EMPTIED.** `UNEXERCISED_BY_DECISION` names exactly one member,
  `Outbox.record`, with its argument. The rule fails **both** on a new member and on the
  disappearance of a named one — so do not delete `Outbox.record` without removing its entry, and
  do not add an entry without writing the argument at the method itself. This is deliberately not
  an ignore list; the both-directions property is what stops an argument outliving its code.
- **`Outbox.record` is kept by decision (2026-08-09), and nothing calls it.** Only
  `Outbox.recordCoalesced` is used, by `ExecutionService` and `IngestionService`, for the one event
  type `OutboxEvents.RESULTS_CHANGED`. Deleting `record` would make `outbox.aggregate_type`,
  `aggregate_id` and `payload` write-constant — the writerless-column shape §116/§117/§132/§151
  each found. **A first occurrence event owes a consumer-side payload test**, which does not exist.
- **Deleted:** `ProvisionCompanyCommand.isRequestedBy` (superseded by `OperatorCommands`, with a
  javadoc rewritten to defend it in the same slice that stopped calling it). Its test moved to the
  new pure `OperatorCommandsTest`, derived from `OperatorCommands.ALL` (now package-private for
  that reason) and covering all three verbs.
- **The `public` narrowing rests on a codebase property, not on Java**: all four `@Scheduled`
  methods here are package-private, so container-invoked methods fall outside the rule. Make one
  public and the rule false-positives (`UX-3`).
- Rule scope: Spring beans (not `@Controller`), public, non-override; self-calls count as
  production callers.

## §152 — A REPOSITORY METHOD WITH NO CALLER FAILS THE BUILD NOW

`ArchitectureRulesTest.everyDeclaredRepositoryMethodHasACaller` is the tally §121/§116/§117/§132/
§151 each kept by hand, computed. Ten methods were found and **deleted**; §121's `findQuarantined`
is the precedent (deleted, not wired).

- **ADD A REPOSITORY METHOD AND ITS CALLER IN THE SAME COMMIT.** A declared-but-uncalled query now
  reddens the build. Nothing else catches it — the interface compiles, Spring Data implements it,
  the context boots, and every other test stays green.
- **Deleted (do not restore without a caller):** `AppUserRepository.existsByEmail`,
  `CompanyRepository.existsBySlug`, `DocBlockRepository.findForPages`,
  `EvidenceRepository.findByExecutionIdIn`, `PermissionRepository.findByAreaOrderByKeyAsc`,
  `RoleAssignmentRepository.findByProjectId`, `RoleRepository.findByScope`,
  `SuiteRepository.countLiveCasesBySuite` (+ `SuiteCaseCount`), `TeamRepository.findByName`,
  `TeamRepository.findByParentTeamId`. Each has a `// … sat here until §152` note where it was.
- **`SuiteRepository.countLiveCasesBySuite` was the dangerous one.** §98 replaced it with
  `SuiteMembership.countsBySuite` (filed ∪ added ∪ tagged) and **revised its javadoc in the same
  slice** to claim it "feeds the internal reads that mean FILING specifically" — there are none. It
  returned the **filed arm alone**, the count §98 removed from the wire. A superseding slice is when
  a stale seam's comment gets *more* convincing, not less.
- **The rule reads accesses AND references.** `cases::existsByAutomationKey` and the projection
  accessors read via `Comparator.comparingLong(ProjectRoleName::getPermissionCount)` are live code
  that an invocation-only scan condemns. A hand-grep for `.name(` got two of ten wrong in **both**
  directions — it also missed `TeamRepository.findByName` because `RoleRepository.findByName` is
  called.
- **It runs over `PRODUCTION_AND_TESTS` on purpose.** Three seams' only reader is a test asserting
  what a write left in the database (`AuditEventRepository.findByActionOrderByOccurredAtDesc` — the
  audit trail has no screen). A test consumer is a consumer.
- **It is scoped to `*Repository`, and the broad form was measured before being refused**: "no
  uncalled public method" reports every controller handler, because Spring calls those reflectively.
- **`PermissionRepository` declares NOTHING now** and that is correct: `PermissionCatalogue` calls
  `findAll()` once over 55 immutable seeded rows.
- **`TestExecutionRepository`'s two headers were wrong in four places** — `countEvidence` claimed
  `evidence` has no entity, nothing uploads one, and `evidence:attach` has no endpoint (all three
  false since `Evidence`/`EvidenceController`/`EvidenceService` shipped, seven files in the same
  package); `findDefectLinks` claimed to be the only native query in a class where the method ten
  lines above it is native. **A javadoc is the one artifact here no gate reads.**

---

## §151 — THE SORT KEY HAS A PRECEDENCE FLAG, AND THE OBVIOUS FIX IS A NO-OP ON THIS DATABASE

§150's finding 4a, repaired. `ReleaseSortKey.of` now emits a **fourth component** between the triple
and the suffix — `.0` for a pre-release, `.1` for the release it precedes — so `4.3.0-rc.1` is
`00004.00003.00000.0-rc.1` and `4.3.0` is `00004.00003.00000.1`.

- **DO NOT "SIMPLIFY" THE FLAG INTO A `~` SENTINEL.** It is the textbook fix for this exact problem
  and it is a **no-op here**, measured: `'…-rc.1' < '…~'` is **false** under `en_US.utf8` (libc,
  what this instance runs — glibc weights punctuation below letters) and **true** under `C` and
  `und-x-icu`. It passes under two collations and fails under the one in use. `RK-1` in the mutation
  queue is that fix applied faithfully, and it leaves three tests red. **Postgres orders this column,
  not Java** — no unit test over `String` can settle anything about it.
- **NOTHING READS `release_sort_key`.** Three tables write it, three indexes cover it
  (`test_run_release_idx`, `test_execution_release_idx`, `execution_fact_release_idx`), and there is
  no `ORDER BY release_sort_key` in `src/main` — `releaseVersion` is an exact-match filter in every
  operation. Readerless, the mirror of the writerless shape. Giving it a reader is §6q's
  latest-release view and a feature, not a cleanup.
- **`V44` is data-only and is a STRING REWRITE, not a re-derivation** — it does not re-implement the
  parser in SQL, because every part of the old key survives into the new one. Its predicate is
  anchored (`^[0-9]{5}[.][0-9]{5}[.][0-9]{5}([-+].*)?$`), which is what makes it **idempotent** and
  what keeps it off `12345.12345.12345 hotfix` — a text fallback that merely looks padded and that an
  unanchored match corrupts.
- **A backfill on a company-owned table MUST loop over `company` setting `app.current_company`.**
  `FORCE ROW LEVEL SECURITY` covers the owner Flyway connects as, so the naive spelling updates
  **zero rows and reports success**. V15, V18, now V44 — third use of that block; copy it.
- **`ReleaseSortKeyBackfillTest` executes the migration file off the classpath** (`db/migration/
  V44__*.sql`) and asserts results against `ReleaseSortKey.of()` rather than literals. First test
  here that runs a migration as its subject. `execution_fact` has **no foreign keys at all**, which
  makes it the cheap table to build a fixture in. Free id prefix used: `f5…`.
- **Known limit, pinned not fixed**: pre-release *precedence among pre-releases*. `-rc.10` still
  sorts before `-rc.9`; repairing it is a second format change plus a second backfill.

---

## §150 — THERE IS A PURE UNIT-TEST LANE NOW, AND `ReleaseSortKey` SORTS A PRE-RELEASE LAST

**Before §150 the split was 100 context-booting test classes to 16 pure ones**; it is 100 to 21.
Every backend test but those boots a real Postgres through Testcontainers, which is right for what
this schema is and leaves a pure function reached only through an endpoint asserted at that
endpoint's resolution — which for five classes was no resolution at all.

- **The five new pure classes, 61 tests, `src/test` only — no production code changed**:
  `ReleaseSortKeyTest`, `BlobUploadTest` (`storage`), `PlanScopeTest`, `PermissionSetTest`,
  `ApprovalGatingTest`. None starts a context or a container. `spring-boot-starter-test` already
  brings `mockito-junit-jupiter` (5.17.0); **no dependency was added.**
- **`ReleaseSortKey` had NO test naming it at all** before this, and its javadoc is wrong twice.
  **A pre-release sorts AFTER its release** — `of("4.3.0")` is a strict *prefix* of
  `of("4.3.0-rc.1")`, and a prefix collates first, so the RC reads as newer. That is the same silent
  wrong-answer shape the class exists to prevent (`"1.10" < "1.9"`), arriving through the suffix
  instead of the padding. **It is PINNED AS IT BEHAVES, not repaired**: the column is written on
  three tables, ordered by Postgres rather than Java, and every existing row holds the current
  format — the repair is a format change + a backfill + a collation decision, i.e. its own slice.
  Second: `"2026-W31"` is offered in the javadoc as an example of the text fallback and **is not
  one** — it parses as `02026.00000.00000-W31`. Order is right, documented value is wrong.
- **`PlanScope.ofItems(PlanScopeItem.rowsOf(planId, scope)) == scope`** is now asserted. That fixed
  point is the class's stated purpose ("the one definition of what the same scope means", agreed on
  by the response, the hash and the written set) and nothing checked it. `CompanyContext.open` is a
  plain `ThreadLocal` guard, so building rows in a unit test needs no Spring.
- **`PermissionSet`'s two throws are the assertions worth having**: a `NONE` qualifier stored as a
  held entry makes `has()` answer true for a grant covering no rows, and an unknown key denies
  forever while reading as a grants problem. Also pinned: `hasAll()` with **no** keys is `true` —
  the vacuous arm a future caller passing a computed array will hit.
- **The suite already conforms to the skill everywhere else, and the scan is the evidence**: zero
  `@InjectMocks`, `lenient()`, `@MockBean`, `mockStatic`, `spy()`, `Thread.sleep`, `@Disabled`, and
  zero JUnit `Assertions` (AssertJ throughout). Exactly one pre-existing class imports Mockito.
- **Four classes were considered and left to the integration lane on purpose** — `CaseSort`,
  `CaseSpecifications`, `AggregateSelection` (all build SQL or Criteria expressions, where the
  generated SQL is the thing under test) and every entity. The skill's "never unit-test this" table
  names them; a mocked `CriteriaBuilder` would assert Mockito.
- **No PIT / mutation score.** The skill hands assertion quality to `qa-test-quality-check-java`,
  never run here. Each new assertion was checked red-against-the-denied-behaviour by hand instead.

---

## §149 — A 400/409/422 ALWAYS CARRIES A BODY, AND AN `errors` VALUE IS ALWAYS A SENTENCE

The other direction of §148's rule, now pinned, plus the one site that broke the rule inside it.

- **`ErrorBodyPolicyTest` has EIGHT tests now**, five of them §149's: every declared 400/409/422
  **and the `TooManyRequests` component** declares `content`; all 98 source sites are followed by
  `.body(`; the back-door spellings (`status(409)`, `HttpStatusCode.valueOf`, `SC_CONFLICT`) are
  banned so the six-spelling scan is a rule rather than a sample; **every literal `errors` value
  contains words**; and the four sites keying `errors` by a blocker are pinned.
- **`Problem.errors` values are complete sentences, printed verbatim by three clients.** The SPA
  lands them beside an input, `@tcms/cli` prints `name: message`, `@tcms/mcp` hands them to a model.
  `ProjectController` sent `children: 3` until §149 — a datum — under a javadoc citing
  `SuiteWriteService.NotEmpty` as precedent, which does the opposite (count in `detail`, no
  `errors` at all).
- **`errors` is NOT validation-only**, though the contract said so until §149: it also names
  blockers on two 409s (`children`, `grants` — the two deletes) and `scope` on the artifact 403s.
  Four sites, pinned; a fifth is a contract decision.
- Unaudited, deliberately: `errors` maps built inside a **service** (the validation path). The rule
  is stated in the contract and checked at the four literal sites only.

---

## §148 — A 401 AND A 404 CARRY NO BODY, AND A CHECK NOW READS THE SOURCE TO SAY SO

**Every 404 this server writes is `ResponseEntity.notFound().build()` — 94 sites — and every 401 is
the security chain setting a status.** `openapi.yaml` declared a `Problem` on both until §148 and
now declares none, so **adding a body to either is a contract change first.**

- **`ErrorBodyPolicyTest` (`com.tcms.contract`, no Spring) is the check.** Three tests: the contract
  declares no `content` on any 401/404; every `notFound()` is followed by `.build()` **and no file
  but `ProblemErrorAttributes` may name `HttpStatus.NOT_FOUND` at all** (the scan knows one spelling,
  so the other is banned rather than left as its hole); and the bodiless-403 set equals three pinned
  sites. Each has a floor, so a scan that stops matching fails instead of passing vacuously.
- **A 403 normally DOES carry a `Problem` — 56 of 59 sites.** The three that do not are
  `reporting/ReportController` (typed to a binary `Resource`) and the two artifact controllers
  (no field to land a sentence on). `Forbidden`'s description names them. A fourth fails the build.
- **`EndpointAccessTest.everyOtherEndpointRequiresAuthentication` now also asserts the 401 body is
  empty**, on every mapped endpoint. That sweep already existed; the 401 half of this rule therefore
  needs no source scan and does not have one.
- **`openapi.yaml` IS OUTSIDE GRADLE'S INPUT SET.** A contract-only edit leaves `:test` UP-TO-DATE,
  so `./gradlew test` after editing the spec can execute nothing and report success. Use
  `--rerun-tasks` when verifying a contract change.
- **Not pinned, deliberately**: 409/400/429 body-presence. A `.build()` on a 409 passes today.

The frontend half: `describeError`'s 404 branch no longer reads `error.detail` — there is none, so
it was rendering `client.ts`'s `statusText` floor ("Not Found" under "Not found"). Both HTTP clients
keep that floor and their comments about it are the accurate ones.

## §147 — THERE IS ONE `UserRef` AND SEVEN MODULES IMPORT IT

**`com.tcms.api.UserRef` is the contract's person, and the six `UserRefView` copies are gone**
(`identity`, `project`, `repository`, `documentation`, `execution`, `work`; `reporting` imported
`project`'s). If you are looking for `UserRefView`, that is why. Six `package-info.java` files
gained `"api"` in `allowedDependencies`.

- **`CaseOwners#resolve` and `ExecutionPeople#resolve` are `new HashMap<>(people.resolve(...))` —
  and the `new HashMap<>` IS THE POINT.** Each held a loop copying `project.UserRefView` into its
  own; with one type that loop copies a record onto itself, so the obvious repair is a bare
  delegation. **It is wrong and the suite says so**: `CaseOwnersNullKeyTest` and
  `PeopleResolutionNullKeyTest` stub a directory returning `Map.of()` and assert the result still
  answers `null` to `get(null)`, and both go red. The loop was also the **§38 guard**, against the
  *port* rather than the caller. `PeopleDirectory#resolve` does contract a mutable never-`Map.of()`
  result and `IdentityPeopleDirectory` honours it — the copy is there so a second implementation
  cannot reintroduce a 500 nobody sees until the page with nobody named on it.
- **Both classes stay**: they are the seam that keeps §6u reachable from one place per module (§76),
  and the header of each says so.
- **`ArchitectureRulesTest.noRecordOutsideApiRepeatsAnApiShape` is the new check and it replaces a
  HAND-KEPT TALLY.** For every record in `com.tcms.api`, no record elsewhere may have the same
  components — **matched on shape, not on name**, because the next copy will be called something
  else. Set of `name:type` pairs, so a reordering does not evade it.
- **It is deliberately not "no two records anywhere share a shape".** Measured before writing:
  fifty `Invalid(Map<String, List<String>> errors)` records exist, one per sealed `Outcome`, plus a
  dozen `Refused(String message)`. That rule would ship with an ignore list, which is §145's finding
  arriving before the check does.
- **The charter has now refused three things, each recorded in its own header**:
  `reporting.AggregationPolicy` (two vocabularies a cross-check pins equal, not one shape two modules
  serve), `documentation.DocPageAttachmentView` vs `repository.CaseAttachmentView` (the *contract*
  declares two schemas — merging them in Java is a contract change in disguise), and
  `project.TeamRefView` vs `identity.RoleAssignmentView.TeamRef` (**the next tenant**, left for a
  third consumer — **§175 took it**, having first found that there had been a third consumer all
  along and that the real obstacle was two inline schemas).
- **`ModuleStructureTest`'s two failure messages were stale from §146** — they named four foundation
  modules and called a fifth "a decision, not an import" after the fifth was taken. Corrected;
  they now say **sixth**.

---

## §146 — ONE ERROR ENVELOPE, AND A FIFTH FOUNDATION MODULE

**`com.tcms.api` exists and is a FOUNDATION module — the fifth.** `allowedDependencies = {}`.

- **`Problem` is the one error envelope.** It was `repository.ApiProblem` (package-private, so six
  modules named it in *prose* and none could call it), `auth.InvitationController.ProblemBody`, and
  a hand-written JSON **string literal** in `auth.RateLimitFilter`. All three collapse here.
  `ProblemWriter` is for the two callers with no controller — a filter refusing before dispatch,
  and the container-error path.
- **`ProblemErrorAttributes` makes `/error` answer `Problem`**, so the five failures Spring resolves
  through `sendError` (unreadable body, malformed JSON, wrong `Content-Type`, wrong method, unmapped
  path) stop answering Boot's `{timestamp,status,error,path}`. `detail` is a **fixed sentence per
  status and never the exception's message** — forwarding it publishes Jackson types and JSON
  pointers to a CI job or a model.
- **An empty 404 does NOT pass through it.** `ResponseEntity.notFound().build()` writes its status
  directly and never calls `sendError`, which is what keeps §6t's bodiless 404 intact.
  `aControllersOwn404IsStillBodiless` asserts it rather than trusting the reasoning.
- **`ArchitectureRulesTest.apiDependsOnNothing` is load-bearing.** Modulith enforces the
  declaration and cannot object to it being *widened*, and every widening available is toward
  another foundation module — legal under the foundation rule and green everywhere else. Adding
  `identity` there is one word, and the next commit composes a refusal out of who was refused.
- **The charter refuses things**: a type belongs in `api` only if the **contract** defines it and
  more than one module serves it. Not "two modules happen to share it".
- ~~**`UserRefView` is the next tenant and was deliberately left alone** — SIX copies, not the three
  `work.UserRefView`'s tally claimed. A migration now, not a decision.~~ → **done in §147 above.**
- Contract change is **document-level**: `components.responses.PreHandlerRefusal`, referenced by no
  operation, for `TooManyRequests`' reason. **`NotFound` still over-promises** a body where §6t's
  404s are bodiless — recorded, not fixed.

## §145 — EVERY DECLARED OPERATION MUST HAVE A HANDLER, AND ONE DID NOT

**`ContractParameterBindingTest.everyDeclaredOperationHasAHandler` is new and has NO ignore list.**
Adding one is the thing it exists to prevent.

- **The contract and the server now agree operation for operation** — 127 operations, 92 paths, zero
  unserved and zero undocumented. If you add a path to `openapi.yaml` before implementing it, the
  build fails. That is deliberate: implement it, or leave it out of the contract until a consumer
  exists. Both copies of `schema.d.ts` generate from that file, so a declared-and-unserved operation
  ships as a compiling, typechecking call that 405s at runtime in every client.
- **`GET /projects/{projectId}/suites/{suiteId}` was that operation, from M0 until now.**
  `SuiteController.detail` + `SuiteService.findOne` serve it. The SPA never called it (the collection
  carries the whole `SuiteView`), so the only consumers were the CLI's `suite edit` read-modify-write
  and two MCP arms — all three answering 405 in production with every gate green.
- **The sibling test skips unmapped operations and that is now the *reason* the new one exists.**
  `everyDeclaredQueryParameterIsBound` returns early on a null handler, on a javadoc note about MSW
  serving the SPA ahead of the API. **§107 deleted the mock layer**; the note outlived it. The
  javadoc records the supersession — do not re-tidy it into a rule.
- **`findOne` renders and picks rather than assembling its own view**, for `viewOf`'s reason: a
  second definition of a suite's wire shape diverges later, on one field, between the list and the
  detail. `SuiteEndpointTest.Detail.detailAgreesWithTheList` compares the two documents.
- 404 covers no-such-suite, soft-deleted, a sibling project's suite and a project you may not see.
  **No 403 arm**, and the contract declares none.

## §143 — THE ERROR DISPATCH, AND WHY MOCKMVC CANNOT SEE IT

**`ApiTokenAuthenticationFilter` now overrides `shouldNotFilterErrorDispatch()` to return `false`.**
Do not "tidy" that away; it is the whole slice.

- **`OncePerRequestFilter` skips the container's ERROR dispatch by default**, and that dispatch is a
  second trip through the entire chain against `/error`, which is behind
  `.anyRequest().authenticated()` like everything else. With the token unauthenticated there,
  `HttpStatusEntryPoint(UNAUTHORIZED)` **overwrote a status that had already been decided** — so an
  unreadable body, malformed JSON, a wrong `Content-Type`, a wrong method and an unmapped path were
  all **401 for an API token** and correct for a session (the resource server's own bearer filter
  re-authenticates there). Anything answered via `response.sendError` went through it.
- **This is a live-pass finding, not a test finding, and it could not have been either.** *Every*
  endpoint test in this suite drives **MockMvc, which performs no ERROR dispatch** — it records the
  status the resolver set and stops. 2302 tests agreed with each other about a status the deployed
  server does not produce. §58's shape with a different observer, and the nightly Linux lane does
  not help: it runs the same MockMvc.
- **`ErrorDispatchStatusTest` is the only test in the suite on a real servlet container**
  (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`, fixture prefix `e4`). Its
  assertions **cannot be written with MockMvc** — converting it would leave five green tests
  asserting nothing. It also pins the branch a careless fix breaks: an unknown `tcms_` secret is
  still 401.
- **If you add a filter to `SecurityConfiguration`'s chain, decide about the ERROR dispatch
  explicitly.** `PermissionVersionFilter`, `RateLimitFilter` and `CompanyContextFilter` all still
  skip it; that is currently harmless only because nothing downstream of authentication reads them
  on `/error`.

~~**Two known-open defects a backend reader will meet**~~ → **fixed in §144, below.**

**§142 changed no Java.** It is the MCP plan's phase 10 — a Dockerfile in `packages/mcp`, an `mcp`
service in `deploy/compose.prod.yaml` behind `profiles: [mcp]`, a `handle /mcp` block, and an
`mcp-image` job. **The one thing that matters to a backend reader**: `ci.yml`'s `lab` deploy now
`needs` the `packages` job, because a package is deployed for the first time — so a red
`packages` job stops the *backend* image reaching the box too, which is new and intended.

**SCHEMA HEAD IS `V43__ai_proposal.sql`; `V44` is next free.** The policy count is **63** — `V43`
adds `ai_proposal`, the first new policed table since `V38`. Before it, `V42` adds two *columns* and two partial unique indexes to `suite` and
`doc_page`, both already company-owned and already forced. Before it, `V41` added no table and no
column either (it flips one seeded `role` row), and `role` / `role_permission` are instance-level
reference data on the ERD §0.1 exception list, so `V40` added no policy either. Before them,
**neither §129 nor §131 carried a migration** —
a clone reuses the version snapshot (which is why #59 dropped plan *templates*: they need a table),
and the outbox's table, `CHECK` and partial index have all been in `V1` since M0.

**The nightly Linux gate is LIVE** (`tests.yml` on `[self-hosted, tcms-lab]`, 01:17 UTC, green since
2026-08-06) — four documents described it as inert until 2026-08-08. `gh run list --workflow=tests.yml`
before repeating any claim about it. `ci.yml` still runs **no** backend tests.

---

## §144 — `TestCaseInput.required` IS FOUR, AND THE FIRST TEST THAT READS THE CONTRACT AGAINST THE SERVER

**`docs/specs/openapi.yaml` — `TestCaseInput.required` is now
`[title, stepFormat, priority, automationStatus]`.** It said `[title]` from M0 while
`CaseEdit.validate` refused four, on create *and* edit. **The server was right and the contract was
wrong**, in the direction that generates broken clients through a generator: both copies of
`schema.d.ts` typed three mandatory fields as optional.

- **`CaseUpdateEndpointTest.contractRequiredMatchesWhatTheServerEnforces` is the check, and it is
  the only test here that compares `openapi.yaml` to the server's BEHAVIOUR.** It drops each
  editable field in turn from an accepted payload and asserts `required` names exactly what the
  server then refuses — failing in both directions. `ContractConventionTest` reads the contract
  against its own rules and would have approved either number. **If you change what
  `CaseEdit.validate` requires, this test is what you update, and the contract is what you update
  with it.**
- **`OpenApiContract.requiredOf` is `public` for that one caller.** Its javadoc says why.
- **Four and not eight, deliberately.** `steps` (STRUCTURED), `gherkinSource` (GHERKIN),
  `automationKey` (AUTOMATED) and `notAutomatableReason` (NOT_AUTOMATABLE) are required *given a
  value of another field*; the schema cannot say that without a `oneOf`. The test's
  `CONDITIONALLY_REQUIRED` set names them and its javadoc records the trade.
- **`PATCH …/cases/{id}` now has a description**, which it never had. It states what
  `CaseUpdateRequest`'s header has always said: the body is the **whole editable set**, an omitted
  nullable scalar is *cleared*, and `ownerId`/`automationTags` are **refused with a 400 when
  present** while being served by the `GET` — so a round-trip must drop them.

**No Java behaviour changed.** The only backend edits are the new test, the `public` modifier, and
the contract. The clients are where the rest of §144 lives (`packages/client/src/core/rewrite.ts`,
`core/proposal.ts`) — read `docs/handoff/144-*.md` before touching an edit arm.

---

## §141 — THE RATE LIMIT, AND A CLOCK METHOD THAT THROWS

`com.tcms.auth` gained a fourth filter. `RateLimitFilter` → `TokenRateLimiter` → `RateLimitProperties`
(`tcms.rate-limit.*` in `application.yml`), the MCP plan's phase 9.

- **`clock.millis()` IS A LANDMINE ON THIS PROJECT'S `Clock` BEAN — DO NOT CALL IT.** Not
  "imprecise": `ArithmeticException: / by zero`, on every call. `TcmsApplication.clock()` is
  `Clock.tick(systemUTC(), ofNanos(1000))` (§58) and the JDK's `TickClock.millis()` divides by
  `tickNanos / 1_000_000`, which is **zero** for a sub-millisecond tick. Use
  `clock.instant().toEpochMilli()`. `ArchitectureRulesTest.nothingCallsClockMillis` fails the build
  on it now; before §141 nothing in the codebase had ever called it, so the method was uncallable
  and unobserved for eighty slices.
- **ONLY AN API TOKEN IS COUNTED, AND THE KEY IS `api_token.id`.** Sessions are deliberately not
  limited (a person cannot produce the shape, and the SPA has no 429 copy); unauthenticated requests
  cannot be (no key the caller does not control — that is the ingress's job). Keyed per token rather
  than per company so one agent's loop cannot refuse a colleague's CI job. The key is read from
  `TokenContext` — **the fourth reader, and the first inside the purpose its header names**.
- **FILTER ORDER IS FOUR RUNGS NOW**: `apiToken → bearer → pv → rateLimit → companyContext`. The
  limit sits after `pv` (a deactivated owner's token is 401'd, not counted) and before the company
  filter (a refusal opens no company and starts no transaction). Anything added here goes in that
  list in `SecurityConfiguration`, whose comment names the reason for each rung.
- **THE BUCKET IS A TIMESTAMP (GCRA), NOT A COUNT — DO NOT "SIMPLIFY" IT BACK.** The obvious
  fractional accumulator is a floating-point one: ten increments of `0.1` sum to
  `0.9999999999999999`, so a caller that obeys `Retry-After` exactly is refused again, which is the
  loop the limit exists to prevent. `TokenRateLimiterTest.fractionsAccumulate` is the row.
- **`Retry-After` IS ROUNDED UP AND NEVER ZERO**, and the same number is repeated in the problem
  `detail` — `@tcms/cli` prints `detail` verbatim and `packages/client`'s `describeRefusal` reads
  the **header**. The two must not disagree.
- **A 429 FROM THIS FILTER CHANGED NOTHING**, because it is decided before dispatch. That is what
  lets the client tell a model to repeat the call, which is the opposite of a timed-out write's copy.
- **THE ERROR ENVELOPE IS COPIED A THIRD TIME**, in `RateLimitFilter.refuse` — forced, because a
  filter's response never reaches Spring MVC's exception handling and `auth` may not import
  `repository`. `ApiProblem`'s header still names the `shared`-module extraction as the follow-up.
- **429 IS DOCUMENTED AT DOCUMENT LEVEL, NOT PER OPERATION** — `components.responses.TooManyRequests`
  is referenced by nothing on purpose, because the response is written before routing. Do not
  "fix" that by enumerating it on 88 operations.
- **A WRITE SUB-LIMIT WAS DESIGNED AND REFUSED**: result submission is one `POST` per test, so a
  ceiling tuned against proposal-spam refuses the product's fastest legitimate writer.

---

## §138 — A CREDENTIAL MAY NOT DECIDE, AND NO PERMISSION CAN SAY SO

`V43`'s `ai_proposal` is where a generated case waits for a person (§6w.6). `AiProposalService`
files and reads; **`AiProposalDecisionService` decides**, and accept goes through
`CaseCreateService` — the accept path *is* the create path, so an accepted proposal is
indistinguishable from a case authored any other way.

- **READ THIS BEFORE TOUCHING EITHER DECISION'S GATE.** `case:create` alone is **not sufficient**:
  the `Agent` role *holds* `case:create` (WIP plan §2.2 keeps it there because accept needs it), so
  a permission-only gate lets an agent file a proposal and accept it in two calls. **No permission
  can express the rule** — a token's set is its owner's set ∩ its role's, so every permission a
  token holds is one its owner holds. The gate is `case:create` **and**
  `TokenContext.currentOrNull() == null`, with its own `Outcome.CredentialRefused` and its own copy
  (telling an agent it lacks a permission is *false* and sends its owner to widen a grant that
  changes nothing). This is the **third** reader of `TokenContext` outside the permission bound, and
  the one its header's expiry clause did not anticipate: reading it to **refuse**.
- **THE CREDENTIAL CHECK RUNS BEFORE THE ROW LOOKUP.** Ordering, not style: after it, an agent
  learns which proposal ids exist from 404-versus-403. `AP-10` is the row; nothing tests it.
- **`verdict_source` IS DERIVED AND HAS NO REQUEST FIELD.** `AiProposal.file` computes it from
  whether a verdict was claimed at all — `NONE` / `EXTERNAL` — and `V43`'s `CHECK` has no
  `TCMS_VERIFIED`. Two barriers, because one field added "for symmetry" lets any MCP client mint
  verified coverage (§6w.1a). `AP-1` is the row that keeps them honest.
- **`suite_id` HAS NO FOREIGN KEY, DELIBERATELY.** It was written with one, failed its own test, and
  was removed: an FK answers *"is this suite real"* at **file** time as a 500, and that is the
  question **accept** exists to answer as field errors. The payload is stored **unvalidated** for
  the same reason — validating twice, a week apart, gives two answers and only the accept-time one
  creates a case. Do not "fix" either; `AP-4` and `AP-5` are the rows.
- **TWO ENUM VALUES ARE ABSENT ON PURPOSE** (`V39`'s rule, #50): `verdict_source = TCMS_VERIFIED`
  (no critic exists to write it) and `status = SUPERSEDED` (nothing displaces a proposal).
- **`MAX_IDEMPOTENCY_KEY` IS DECLARED EIGHT TIMES NOW.** `AiProposalService` is the eighth and the
  first whose caller has **no screen at all**; `IdempotencyKeyCapTest.EXPECTED_DECLARATIONS` is 8.
  The generalisation was re-asked at the count the test exists to force and refused on `V17`'s
  argument, which is about what the alternative must *store*, not about the count.
- **Accept passes a synthetic key (`ai-proposal:<id>`) to `CaseCreateService`**, so two reviewers
  accepting at once create one case. `AP-12`: nothing tests the race.
- **`ProposalRejection.note` is required-and-nullable.** `ContractConventionTest` refused the schema
  without a `required` array, and the fix is the better shape — one wire form instead of three.

## §136 — SIX CREATE ENDPOINTS CARRY `Idempotency-Key`, AND THE CAP IS COPIED SEVEN TIMES

`V42` adds `created_idempotency_key` to **`suite` and `doc_page`** — the fourth migration in the
`V11`/`V14`/`V17` family, now six columns over six tables. Both services gained the replay lookup,
both controllers the header and a **200 arm with no `Location`**.

- **THIS REVERSED A RECORDED DECISION, NOT AN OMISSION.** `SuiteWriteService#create` and
  `DocPageWriteService#create` both *argued* against the header since §29 — "a duplicate folder is
  visible in the tree the moment the create returns". Both javadocs are struck through in place.
  The premise expired twice over: the sentence reasons from a person at a screen, and **§98 deleted
  the tree it names**. Do not "restore" it.
- **THE GENERALISATION IS REFUSED FOR THE FOURTH TIME** on `V17`'s argument, which does not weaken
  with the count: a general `idempotent_request` table must store the **rendered response**, and a
  stored response goes stale the moment the row it describes is edited. The column form re-renders
  through the same assembler the `GET` uses.
- **`MAX_IDEMPOTENCY_KEY = 200` IS DECLARED IN SEVEN SERVICES ACROSS FOUR MODULES** —
  `CaseCreateService`, `SuiteWriteService`, `RunCreateService`, `PlanCreateService`,
  `IngestionEdit`, `DocPageWriteService`, `BoardService`. **Do not extract it to `tenancy`**: that
  is the only module all four may import, and an HTTP header's cap is not a tenancy concept (§125's
  bar, which refused `shared` as a grab bag). The invariant is a **computed check** instead —
  **`config/IdempotencyKeyCapTest`**, no Spring context, which also asserts that `openapi.yaml`'s
  `maxLength: 200` equals the code's cap. An eighth create endpoint must bump
  `EXPECTED_DECLARATIONS`, and that edit is the prompt to re-ask the question.
- **`DocPageWriteService#writeSubjectPage` PASSES `null` DELIBERATELY.** It is an internal call with
  no header, already deduplicated by `uq_doc_page_subject` — a stronger constraint. Comment in
  place; a null that looks like an omission gets "fixed".
- **`CaseController.trimToNull` IS PACKAGE-PRIVATE NOW** so `SuiteController` shares it;
  `DocPageController` has its own copy because it is in another module. Blank ≠ a key everyone
  shares, and mutation `IK-3` is the row for it.
- **`documentation/package-info.java`'s `doc_link` claim is CORRECTED** — it said "has no writer",
  stale since §93, in the file an agent reads first to orient in the module. What is still absent is
  `coverage`, for §81's reason.

## §134 — `assignable` DRIVES **ONE** SITE, AND REVOKE IS NOT IT

`V41` sets `assignable = false` on **`Automation Service`** (`…-000000000005`) — a CI credential's
capability set, which `V3` has called "never people" since M0 with nothing enforcing it. Nothing
else about the role changed: same six keys, still issuable, still listed, still the role every CI
token carries. **Three rows are non-assignable now and one of them IS one of the six**, so
`assignable = false` is no longer a synonym for "not one of the six".

- **READ THIS BEFORE TOUCHING `RoleGrantService`. `revoke` DOES NOT READ THE FLAG ANY MORE.** It
  compares `Role.COMPANY_MEMBER_ID`. Grant asks *may a person hold this*; revoke asks *does removing
  this do damage the endpoint cannot repair*, and only the `V15` baseline qualifies. **Putting the
  flag back strands data**: `AdminDirectoryService:418` lists pre-`V41` Automation Service grants, so
  the row renders with a live Revoke button that 403s forever — §31's disable-don't-hide from the
  server side, with nothing failing. Mutation `AS-2`, comment in place at both sites, `V41` §1 is the
  long form.
- **The site table, corrected.** One of five reads the flag:

  | Site | After `V41` |
  |---|---|
  | `RoleGrantService.validate` (grant) | **the only reader of `isAssignable()`** |
  | `RoleGrantService.revoke` | **`COMPANY_MEMBER_ID`** — changed by `V41` |
  | `AdminDirectoryService` `GET /roles` | stays `COMPANY_MEMBER_ID` — `WHERE assignable` now hides **CI tokens' role too** |
  | `AdminDirectoryService` `GET /role-assignments` | stays — and now it matters: it is what makes the legacy grant's Revoke reachable |
  | `ApiTokenService.validate` | stays — the flag here makes both ceilings unissuable |

- **`V41` deletes nothing.** Existing person-held grants keep conferring, stay listed, stay
  revocable. The `DELETE`-in-a-migration alternative is recorded as refused (`AS-5`): no actor, so no
  audit row.
- **`SeedDataTest`**: `nonAssignableRolesAreExactlyTheTwo` → `…TheThree`, plus new
  `theAssignableRolesAreTheFive`, which pins that **Instance Admin stays `assignable = true`** — it
  is refused by this endpoint for a *scope* reason and must not be swept in with the ceilings.
  `RoleGrantEndpointTest` gains three (refused grant, revocable legacy grant, listed legacy grant).
- **Roles spec §A2's "the six are the complete set of assignable roles" is RETIRED**, not reworded.
  Five of the six are. The surviving invariant is "no role outside the catalogue is assignable".
- **No frontend code changed** — the pickers filter on `assignable` since §133. One stale hand-written
  fixture (`IssueTokenDialog.test.tsx` had `assignable: true`) was the only SPA edit.

---

## §133 — THERE ARE EIGHT ROLE ROWS, AND `assignable` ANSWERS ONE OF THREE QUESTIONS

> **Superseded in one place by §134 above: the flag drives ONE site, not two, and the questions are
> four rather than three.** Everything else here stands.

`V40` seeds **`Agent`** (`Role.AGENT_ID`, `…-000000000008`, PROJECT, `built_in`,
`assignable = false`) — the ceiling an API token carries for agent access, **Test Engineer minus
`library:manage`, `case:delete`, `evidence:delete` and `company:read`**, 23 keys. Enumerated once in
roles spec **§A6a**; `CLAUDE.md` and `README.md` carry the delta sentence, not a fourth copy.
**`PermissionEvaluator` is unchanged** — the token intersection that already caps a credential is
the only narrowing point, which is why the ceiling is a role and not validation code.

- **READ THIS BEFORE TOUCHING ANY ROLE CHECK. `role.assignable` is false for TWO rows and they
  differ everywhere else.** `Company Member` is non-assignable AND non-issuable AND never listed;
  **`Agent` is non-assignable BUT issuable and MUST be listed.** Five sites compare against a role
  constant and **only two may read the flag**:

  | Site | After `V40` (see §134 for the correction to row 1) |
  |---|---|
  | `RoleGrantService` grant + revoke | **read `isAssignable()`** — ~~both~~ → **grant only, since `V41`** |
  | `AdminDirectoryService:352` (`GET /roles`) | **stays `COMPANY_MEMBER_ID`** — `WHERE assignable` hides `Agent` from the token form |
  | `AdminDirectoryService:402` (`GET /role-assignments`) | stays — an `Agent` grant cannot exist |
  | `ApiTokenService.validate` | **stays** — the flag here makes `Agent` unissuable, the one thing it exists for |

  Each of the three has a javadoc/comment refusing the cleanup, and `V40` §2 is the long form.
  `ApiTokenEndpointTest.theAgentCeilingIsIssuable` is the check that goes red.
- **`revoke` loads the role to read the flag and `orElse(false)`s** — an unknown role is
  unrevocable, not revocable. Unreachable behind the FK; recorded as mutation `AG-4`.
- **`RoleView` gained `assignable`** (contract `required`), and `Role.name`'s closed enum gained
  `Agent`. `Company Member` is still filtered server-side and is not in the enum.
- **`SeedDataTest.agentIsTestEngineerMinusThree` derives the delta**, both directions — what Agent
  drops, and that it adds nothing. A subset check would let a permission a future migration adds to
  Test Engineer land silently in a credential's ceiling. Also new:
  `nonAssignableRolesAreExactlyTheTwo`. `sixBuiltInRoles` now expects **eight** rows and
  `theRoleCatalogueIsWholeAndHasNoWildcard` **seven**.
- **`Agent`'s `artifact:edit` must reuse Test Engineer's exact `PLAN_SCOPE` spelling** (`V19`), or
  `noPermissionCarriesTwoDifferentRestrictiveQualifiers` fails — which is that test working.
  `case:edit` is **`ALL`**, deliberately: editing anyone's case is what the role is for, and
  `case:delete` is dropped rather than `OWN`-qualified because `OWN` follows `api_token.owner_id`,
  so the agent would delete what the *human* authored.
- **No `company:read`, and it is not an oversight**: a token is not a member, so it has no `V15`
  baseline grant, and a PROJECT-scope role's `company:read` is unreachable from
  `evaluateCompanyWide` anyway. `automationServiceIsTheSmallestRole` still holds.
- **Phase 3: `expiresAt` is REQUIRED for an `Agent` token and optional for every other role** —
  the first per-role rule in `ApiTokenService.validate`. In the service, not the form: the bearer
  works with `curl`. The form pre-fills 90 days in **local wall clock** (`datetime-local`) and
  **only into a blank field**.
- **Frontend: three of four `useRoles()` consumers filter `assignable`, and the fourth must not.**
  `GrantRoleDialog`, `RoleReference` (both screens) and `ProjectAccessPage`'s `isEmpty` do;
  **`IssueTokenDialog` does not** and carries a comment saying so. `RoleReference`'s own javadoc
  had already made the argument — about *scope* — and `Agent` is the first role where the two axes
  disagree.
- **NO RED RUN in any of the three phases**, which is unusual enough to state. The one failure was
  a test of my own: `optionNames` leaves the MUI menu open, so a second `chooseFrom` in the same
  test finds no accessible combobox. `{Escape}` between them.
- **Deliberately not done: `Automation Service` is still assignable to a person**, although `V3`'s
  description has said *"attached to API tokens, never people"* since M0 — §100's rule, eighth
  sighting. Flipping it is a behaviour change for an existing role with a live-data question
  (existing grants) and belongs in its own slice.

---

## §131 — THE OUTBOX IS ALSO THE ONLY CROSS-MODULE SHAPE CHANNEL

Round 3's step 9 (#52), two phases, **no migration** — `V1` has carried every column, `CHECK` and
index since M0. New package content in `tenancy`: `OutboxEntry` · `OutboxStatus` · `OutboxMessage`
(the **first `CompanyScopedEvent` implementation in `src/main`**) · `OutboxHandler` ·
`OutboxRepository` · `Outbox` (writer) · `OutboxLedger` (row-state transitions) · `OutboxDispatcher`
· `OutboxScheduler` · `OutboxProperties` · `OutboxEvents`.

- **A typed cross-module Spring event is unbuildable here.** `ArchitectureRulesTest`'s foundation
  rule reads `getDirectDependenciesFromSelf` and has **no exemption for an event type**, so
  `reporting` cannot import a class from `execution`. `event_type TEXT` + `payload JSONB` is
  therefore the mechanism and not a storage detail. Before proposing "just publish a Spring event",
  check that rule.
- **`Outbox.record` / `recordCoalesced` throw unless a transaction is active**, and
  `OutboxDispatcher.dispatchDue` throws if one *is*. Both guard defects with no symptom.
- **Three transactions per event; the handler runs in none of them.** `OutboxLedger` is a separate
  bean because a handler's exception marks the surrounding transaction rollback-only (the failure
  record would be discarded) and because a self-invoked `@Transactional` is not intercepted at all.
- **`PENDING` is the retry status**, forced by `outbox_pending_idx` being partial on it. `FAILED` =
  ceiling reached, replayable; `DEAD` = no handler / unreadable payload. **Unclaimed type ⇒ DEAD,
  never SENT.**
- **The lease, not a lock, is the concurrency mechanism**: `claim` bumps `attempts` and pushes
  `next_attempt_at`. Delivery is **at least once** and every handler must be idempotent.
- **Several handlers may claim one event type** (fan-out); they share one row, so one failure
  retries all. Ordering between them is undefined — co-dependent effects go in one handler
  (`reporting`'s does refresh-then-rollups for §101).
- **`recordCoalesced` takes no aggregate and no payload**, and folds only into a row with
  `attempts = 0`. Use it for *state moved*; use `record` for *a thing happened*.
- Phase 2: `execution.ExecutionService.record` and `IngestionService` produce
  `OutboxEvents.RESULTS_CHANGED`; `reporting.FactRefreshOnResultsChanged` and
  `repository.HealthRecomputeOnResultsChanged` consume it (**named apart on purpose: two
  `@Component`s with one simple name collide on the default bean name and fail every
  `@SpringBootTest`**), each under its own module's existing
  `enabled` property. The schedulers **stay** — they are the rebuild path.
- `tcms.outbox.*` in `application.yml`; `IntegrationTestBase` sets `tcms.outbox.enabled=false` for
  the suite's third scheduled writer. Fixture prefix **`0b`**, slugs `obx-`.
- **`ArchitectureRulesTest.springStereotypesHaveDistinctSimpleNames`** is new and is the check for a
  defect class the suite cannot otherwise see: two `@Component`s with one simple name share a
  default bean name and the context refuses to start — but if either carries
  `@ConditionalOnProperty` and the suite disables it, **no integration test registers the bean and
  nothing reproduces it until a real boot**. Reads bytecode, not a context, for that reason.
- **The invitation email cannot use the outbox**: only a token digest is stored, so the payload
  would be a live credential at rest and a retry could not reconstruct it.

---

## §132 — TOKEN ATTRIBUTION, AND `TokenContext` IS IN `tenancy` NOW

Plan: [`docs/plans/2026-08-08-token-attribution.md`](../plans/2026-08-08-token-attribution.md).
Derived from the code after round 3 closed; lands the MCP WIP plan's §7 phase 1. **No migration, no
contract change** — all three columns are in `V1` and both wire fields were already in
`openapi.yaml`.

- **`com.tcms.identity.TokenContext` → `com.tcms.tenancy.TokenContext`.** If you are looking for it
  in `identity`, that is why. `AuditEvent` is in `tenancy` (every module writes there) and `tenancy`
  may not import `identity`, so the type moved rather than the rule bending. Four import sites.
- **`AuditEvent.record` fills `actor_token_id` from the ambient context**, exactly as it fills
  `company_id` from `CompanyContext`. **None of the 42 call sites gained a parameter** and none
  should: a forgotten bound defaults to the unbounded answer, a forgotten attribution defaults to
  *"a person did this"* — a false record rather than a missing one. Both actor columns are written:
  the owner is still answerable.
- **`CompanyContextTaskDecorator` now clears BOTH contexts**, and `TokenContext.clear()` returns a
  `Scope`. This guard was unbuildable before the move, and `TokenContext`'s own javadoc had argued
  the gap away — *"this context only ever narrows"* — while naming its expiry: *"it stops holding
  the moment anything reads this context to grant rather than to bound."* Reading it to **attribute**
  is neither, and breaks the asymmetry from the other side.
- **`IngestionEndpointTest.Attribution` is the only place in the suite where a write is
  authenticated by a real `api_token`** rather than a session for a user id. That absence is how
  three token columns sat unwritten through the two slices that shipped tokens.

Phase 2 — `test_execution.executed_by_token`:

- **The column was not mapped at all.** `TestExecution` had no field, while `RunSource`,
  `TestExecutionSummaryView`, `CaseHistoryView` and `IngestionService` all referred to it in prose.
  `record` fills it from the ambient token, so no call site spells it; **it is not exclusive with
  `executedBy`** — ingestion nulls the person on purpose, but Automation Service holds
  `result:submit`, so a credential posting one verdict fills both.
- **`ExecutionTokens`** (new, `execution`) resolves the id to the token's **name**, batched, via
  `ApiTokenRepository.findAllByIdIn` — a derived query and not `findAllById`, because
  `EntityManager.find` does not apply the `@Filter`. The name is unique only among *live* tokens
  (`V36`), so it is ambiguous across a rotation; recorded in the class, not designed away.
- **`Map.of().get(null)` threw again — §38's trap, SEVENTH sighting, one slice after §121 phase 4
  recorded the sixth.** `tokenNames` is `Map.of()` when no row on the page named a token, which is
  the *ordinary* case, so every case history 500'd and eighteen tests about withdrawn runs, markers
  and scope went red. A lookup keyed by a nullable column must guard the key.
- **The SPA branched on the run's `source` to decide the author caption**, which is a property of
  the run where the actor is a property of the verdict — so a credential posting into a MANUAL run
  printed the owner's name. `authorLine` in `CaseHistoryPage.tsx` branches on the actor now. No
  backend test can see it; browser row `A3-16`.

Phase 3 — `api_token.last_used_at`:

- **Written in `ApiTokenAuthenticationService`'s SECOND transaction**, not in the filter: that one
  is already open inside the company on the already-loaded policed row. A writer in the filter gets
  its own transaction, which begins *outside* the company scope and therefore updates zero rows
  under RLS — §116's red run, one layer up.
- **`ApiToken.markUsed(now, resolution)` returns whether the row changed**, and usually it has not.
  `tcms.api-token.last-used-resolution` (`ApiTokenProperties`, default `PT1H`) is what keeps a busy
  CI token's single row off the hot path. **Still no `@Version`** — two concurrent requests on one
  credential are the ordinary case, and a lost bump of a deliberately coarse timestamp costs
  nothing where a 409 would fail an unattended pipeline.
- **Only a *usable* token is recorded**: the `isUsable` filter runs first, so "last used" is not
  "last presented". An administrator reading the column to decide what to revoke must not see a
  live date on a credential nothing accepts.
- The token screen grew the **Last used** column its own header had promised would be "a one-line
  change the day it gets a writer". `Never used` is a sentence, and the value renders as a
  date-time and never as "3 minutes ago".

---

## §129 — A CLONE IS A CREATE, EXCEPT WHERE THE CREATE CONTRACT IS NARROWER THAN THE AGGREGATE

Decision round 3's step 8, second half (#59). Three endpoints, all `POST …/{id}/clone`.

- **The shape: read the source, build the request the create endpoint already takes, delegate.** It
  holds for the **case** and the **run** and gives six of §6v's rules for free (new display key,
  DRAFT/OPEN status, own version 1, no shared history, no execution history, idempotency). It does
  **not** hold for the **plan**: `PlanWriteRequest` has six fields where `PlanUpdateRequest` has
  twelve, so a delegated clone drops the tag arm, every §97 section, both prose fields and both
  criteria lists — and answers 201.
- **`PlanCreateService.write` is the shared orchestration** (validated `PlanEdit` + the two criteria
  JSON columns, instead of a request). Create-then-edit was refused: it gives every cloned plan a
  v1 describing a plan that never existed and a v2 one second later.
- **THE RED RUN WAS THE AUTOMATION STATUS.** §6v's "copy the status" and "never copy the key" are
  jointly unsatisfiable — `CaseEdit.validate` refuses `AUTOMATED` with no key. `AUTOMATED` becomes
  `TO_BE_AUTOMATED`; everything else copies. Relaxing the validator instead leaves a case reporting
  as automated that §117's matcher can never match.
- **`AttachmentStore.copy` is a fifth port method**, and `ReportStore` deliberately does not get it
  — the two ports are two types precisely so they can differ, and this is the first divergence.
  §6v's "attachments by reference" is **unimplementable**: the store holds no reference count, so
  two rows over one key means either delete destroys the other's bytes.
  `Attachment.copyForCase` **carries** the source checksum rather than recomputing it, so a corrupt
  blob cannot launder itself through a clone.
- **`PlanScopeItem.rowsOf` is the one scope→rows mapping.** It existed twice — five arms in
  `PlanUpdateService`, two in `PlanCreateService` — correct only while the create request could not
  express the other three. §127's `BlockContent.of` finding, one aggregate over.
- **A run stores no scope and no assignee.** §99's three arms are resolved once into
  `test_execution`; the declaration is discarded. So a cloned run carries the **resolved** case set
  (a suite-scoped clone does *not* pick up cases added since) and is **unassigned** unless the
  request says otherwise.
- **§6g's approval gate is re-evaluated on a cloned run and the override is never inherited** —
  nothing stores it, which is what keeps that unexpressible rather than merely unwritten.
- **Provenance is a SECOND audit row** (`case.cloned` / `plan.cloned` / `run.cloned`) beside the
  created one, naming both display keys.
- Fixture prefixes **`1a`** (`CaseCloneEndpointTest`), **`2a`** (`PlanCloneEndpointTest`), **`3a`**
  (`RunCloneEndpointTest`); slugs `clone-`, `pcl-`, `rcl-`.

---

## §128 — THE AUTOSAVE BUFFER, AND THE VERSION THE STALE-EDITOR CHECK CANNOT SEE

Decision round 3's step 8 (#57). `test_case_draft` had a `V1` comment describing its whole flow and
no writer; three verbs on `/projects/{p}/cases/{c}/draft` now serve it.

- **`CaseDraftService` is the only writer, and there is NO promote endpoint.** `V1`'s comment says a
  save "promotes this into `test_case`", which reads like a method this class should have. It would
  be a second write path into `test_case` whose input is by construction unvalidated — either a
  duplicate of `CaseEdit` (two definitions of a legal case, free to disagree) or no validation at
  all. Promotion is the client restoring into the form and the user pressing the ordinary `PATCH`.
- **`CaseUpdateService.apply` clears the buffer on every successful save**, in the same transaction
  as the version row, via `TestCaseDraftRepository.deleteForCase` (a `@Modifying` bulk delete —
  usually zero rows, on the hottest write path). Placed **after** validation: a clear at the top of
  the method destroys the work a refused save was protecting, and passes every other test.
- **`CaseDraftInput.lockVersion` is the field the existing concurrency machinery is blind to.** A
  restored draft's save carries an `If-Match` from the *fresh* read, so it is correct and the
  stale-editor check passes — silently overwriting the edit made while the draft sat there. The
  buffer is the only thing that knows what the draft was written *against*. `stale` is computed in
  `render` and is advisory; the content is always served.
- **`case_id` IS the primary key, so the buffer is per case and not per person.** `updatedBy` is on
  the wire for that reason, not as an audit column. `TestCaseDraft`'s header records the per-person
  key as the option **not** taken.
- **Nothing in `content` is validated and there is no `@Version`** — both deliberate, both with the
  failure they prevent in the javadoc. `MAX_CONTENT_BYTES` is 256 KB measured in UTF-8 **bytes**.
- **`Outcome.Empty` is a 204 and is not `NotFound`.** Almost every case has no draft almost always.
- **`case:edit` gates all three verbs including the GET** — the one gate here that looks harmless to
  relax; `CD-3` is its mutation.
- **FIXTURE COLLISIONS ARE NOT ONLY UUIDs.** `CaseDraftEndpointTest` died 26/26 in `@BeforeEach` on
  the company **slug** `cd-acme` and its email domain, both already `CaseDeleteEndpointTest`'s and
  both globally unique. §19's family, sixth instance, through fields the convention does not name.
  **Check the slug and the email domain as well as the UUID prefix.**

---

## §127 phase 1 — A BLOCK'S PAYLOAD IS PART OF THE VERSION SNAPSHOT, OR ITS HISTORY LIES

Decision round 3's step 7 (#48): `DocBlockType` had six values and `BlockContent` modelled the two
fields four of them share. `table` and `image` now carry their own payload.

- **`BlockContent` is `(text, level, rows, attachmentId)` and `BlockContent.of(input)` is the ONE
  mapping.** `DocPageWriteService.applyCreate` and `PageContent.reconcile` each spelled the
  constructor out — two copies of one mapping, where a field wired into the edit path and forgotten
  on the create path gives a page whose table renders on save and comes back empty when it is first
  created, with nothing failing at either end.
- **`PageVersions.SnapshotBlock` HAD TO GROW WITH IT, and forgetting that is silent in the direction
  that looks like success.** The hash decides whether a version row is written, and it is computed
  over that record — so a content field the snapshot does not carry is a field somebody can rewrite
  all day while the history says nothing happened. Every other assertion about tables passes without
  it. `TableBlocks.aCellEditIsAuthorship` is the guard.
- **`@JsonInclude(NON_NULL)` on the two NEW snapshot components and deliberately not on the older
  two.** Without it every stored `content_hash` in every deployment changes on deploy and the first
  save of each untouched page writes an empty-diff version — history noise delivered once per page
  as a migration artefact. Nothing in a test run can see this (every hash in a run is computed by
  one build), so the assertion is that a paragraph's stored snapshot has **no `rows` key**.
- **Three payload fields, one rule: refused on the types that do not have it.** `level` (heading /
  list), `rows` (table), `attachmentId` (image). A field on the wrong type means the client believes
  it is sending something else.
- **An `image` names an attachment ON THIS PAGE — `parentId`'s rule one field over.** So **a create
  can never carry an image block** (a page has no files until it exists), and the refusal says
  *"create the page, attach the image, then add the block"* rather than reporting a valid id as
  unknown. `PageAttachments.idsOf(files)` derives the set from the **same read** the response
  renders, so the validator and the client cannot be shown different worlds.
- **`text` is the alt text on an `image` and blank is REFUSED** — the one block type where empty
  text is not legal, one `else if` away from the empty-paragraph rule that must stay legal.
- **A ragged table row is refused, not padded**, and **row 0 decides the width** because row 0 is the
  header. Taking the widest row lets a stray cell silently widen the table.
- **`DocBlockRepository.countBlocksReferencing` is a new guard with no foreign key behind it** — the
  reference lives inside a JSONB document, so `DELETE /attachments/{id}` is a **409** while a block
  names the file, checked *before* the bytes, which do not come back. Native, `content ->>
  'attachmentId'` compared as **text** (a `uuid`-to-`text` comparison is a 500, not a wrong answer).
- **The V2 search trigger needed no change**: `jsonb_to_tsvector(…, '["string"]')` walks the whole
  document, so a table's cells were searchable the moment they could be stored. `SearchVectorTest`
  pins it, because narrowing that trigger to `content ->> 'text'` reads as a tidy-up.

---

## §126 phase 3 — `ArtifactKind.FILE` IS REGISTRABLE, AND AN ARTIFACT IS NOT A LIST

**`attachment.owner_type = 'ARTIFACT'` has a writer, and it is the last of the five surviving owner
types to get one.** `V1`'s polymorphic table is now fully claimed.

- **AN ARTIFACT DOES NOT HAVE ATTACHMENTS — IT *IS* ONE.** Read this before assuming the
  `CASE`/`DOC_PAGE` shape carries over; the slice plan assumed it and was wrong. `V1`'s
  `artifact.attachment_id` is a **single nullable FK** and sits among four payload columns, one per
  kind: `attachment_id` (`FILE`) · `doc_page_id` (`DOC_PAGE_REF`) · `external_url`
  (`EXTERNAL_URL`) · `report_artifact_id` (`GENERATED_REPORT`). So there is **no attachment family
  under `/artifacts/{id}`**, no list, no attach-afterwards and no detach.
- **`artifact.attachment_id` and `attachment.owner_type='ARTIFACT'` are BOTH written and are not
  rivals.** `owner_id`/`owner_type` are `NOT NULL` (no blob is unowned); the FK is the kind's
  payload. A reader who assumes the schema declared one relationship twice will delete the wrong
  half.
- **`POST /projects/{projectId}/artifacts/files`** — multipart, `artifact` (JSON, `ArtifactFileInput`)
  + `file`. **A separate path, not a media type on `POST /artifacts`**: sharing the path means
  narrowing that operation to `consumes: application/json`, which turns every client posting a
  reference with no `Content-Type` into a 415. Own controller (`ArtifactFileController`) for the
  `MaxUploadSizeExceededException` scoping reason — **fourth instance** of that argument.
- **`ArtifactFileInput` has NO `kind`, `url` or `docPageId`.** `FILE` by construction, so a
  contradictory request is unconstructable from the generated types — `ArtifactInput.kind`'s
  narrowing argument taken one step further. `asRegistration()` widens it so **both create paths
  share one `validate`**; the only difference is a `hasUpload` boolean.
- **THE GATE IS `artifact:edit`, SCOPE-QUALIFIED — the same `writeGrantedByRowScope` the reference
  create uses, at a SECOND call site.** A plain `writeGranted` here compiles and passes everything
  but `Gates.anEngineerCannotUploadAtProjectScope` (mutation `FA-1`). Delete is
  `artifact:delete` (§124's key) and now guards the last copy of a file. Download costs nothing
  beyond the register read — no `attachment:read` was invented.
- **`Artifact.register` takes the id as a PARAMETER now.** The two rows reference each other, so one
  id must exist before either object does. The alternative — setting the column on the transient
  instance — is a trap: the same call on a *managed* instance compiles, runs and persists nothing
  (`updatable = false`), giving a `FILE` artifact with no file and no error.
- **Write order: attachment row → artifact row → bytes. Delete order: artifact row → attachment row
  → bytes.** The first two are the FK's direction (checked at statement time, so the other order is
  a 500 on every upload); the bytes are last in both for §67's non-rollback reason.
- **`ArtifactFiles`** is the assembly seam — `forArtifacts(rows)` batched for the register
  (**plural**, opposite call from `PageAttachments`, because a register renders dozens of rows at
  once) and `forArtifact(one)` for the download. It keys on the **artifact's own `attachment_id`**,
  not the owner side, so a second owner row could never be picked at random.
- **`ArtifactView.url` now carries a THIRD meaning** — the external link for `EXTERNAL_URL` and the
  **download path** for `FILE`, `null` for the other two. Server-built, so the SPA never composes
  the route. `filename`/`sizeBytes` were on the wire with no writer since the register shipped, so
  neither side changed shape.
- **The download is `Content-Disposition: attachment`, not `inline` — the only one in the product.**
  A page's image renders beside its paragraphs; an artifact is a held document reached from a list
  that names it.
- `Artifact`'s "four deliberately unmapped columns" is **three** now. The rule ("do not map a field
  this slice cannot set") stands; its condition expired — **fifth** blocker note to outlive its
  blocker.
- Fixture prefix **`4f`** (`ArtifactFileEndpointTest`, 16). **NO RED RUN** — stated because it is
  unusual, and the likely reason is that the two-rows-referencing-each-other problem was solved at
  design time rather than at compile time.

## §126 phase 2 — `documentation` DECLARES `storage`, AND THE WIRE SCHEMA IS NO LONGER CASE-SHAPED

**`documentation`'s `allowedDependencies` is `{"tenancy", "project", "identity", "storage"}`** — the
**second** module to declare `storage` after `repository`. What it asks of it is *hold these bytes
and give them back*, and the download is why `storage` is a foundation module rather than an event
peer: a request has to answer with the bytes.

- **The contract's `CaseAttachment` schema IS NOW `Attachment`**, serving `CASE`, `DOC_PAGE` and
  (phase 3) `ARTIFACT`. One schema because the shapes are identical field for field *and* meaning
  for meaning — only `url`'s value differs — and three identical generated types would re-create by
  hand, on the wire, the polymorphism-by-duplication `storage` removed in the code. The rename was
  free: nothing in `frontend/src/` named the old type, only the generated `schema.d.ts`.
  **`Evidence` stays separate** — it has a caption and it is a record rather than authored content,
  which is what decides the `OWN` arm on its delete.
- **ONE wire schema, TWO Java records** — `repository.CaseAttachmentView` and
  `documentation.DocPageAttachmentView`. Not shared, and there is nowhere to share it: the two
  modules cannot import each other, and `storage` (which both reach) declares `tenancy` alone and
  must not learn what a route or a `UserRef` is. `UserRefView` is already duplicated four ways for
  this reason. **A field added to one and not the other is a response that omits it on one owner,
  and nothing but the contract test observes that** — mutation `DA-9`.
- **`documentation.PageAttachments.forPage(projectId, pageId)`** is the assembly seam —
  `BlockLinks.forPage`'s shape, and the one place a page's file list is built. **Singular where
  `CaseRenderer.attachmentsFor` is plural, deliberately**: pages are rendered one at a time (the
  tree serves `DocPageSummaryView`, which has no file list), so a batch form would be a seam ahead
  of its consumer. Uploaders resolve through the existing `ArtifactPeople`.
- **`DocPageAttachmentService` + `DocPageAttachmentController`**, its own controller because
  multipart brings a `MaxUploadSizeExceededException` handler and an `@ExceptionHandler` is scoped
  to its controller — on `DocPageController` an oversized upload's 413 would answer a JSON `PATCH`.
  **`DocPageController.validationProblem` / `forbiddenProblem` went package-private** so the second
  controller reuses them rather than becoming the fourth hand-rolled problem envelope.
- **THE GATE IS `doc:edit` ON BOTH WRITES, NOT `doc:publish`.** An attachment is *content*, so
  whoever may rewrite the paragraphs may attach the diagram they describe; the other choice leaves a
  Test Engineer able to author a specification they cannot illustrate, and **every other doc write
  they make would keep working**, so nothing else in the suite would catch the drift. No `OWN` arm
  on the delete, for `CaseAttachmentService`'s reason. Download costs nothing beyond the page read.
- **NO `If-Match`, NO version row, NO status arm — three deliberate non-copies from the page's own
  writes.** Blocks are reconciled by id so a stale client carries stale ids; an attachment is
  addressed by its own id and never reconciled. `doc_page_version` snapshots blocks, so a version
  row here would be a history entry whose diff says nothing changed. And **an `ARCHIVED` page still
  accepts files** — `ARCHIVED` keeps links resolvable, and making it a retention control is a
  different decision (mutation `DA-5`).
- **`DocPage.attachments` is required and never null**, detail-only. `DocPageView.of` now has a
  4-arg form; the 2-arg create form passes `List.of()` because a page cannot have a file before it
  has an id. **`DocPageWriteService.edit` and `setStatus` RE-READ the attachments** — the `PATCH`
  response is what the SPA renders, so serving `[]` makes a save appear to delete every file until
  the next `GET` (mutation `DA-8`).
- **Two red runs, both the test's fault and both worth keeping:** (1) an `UMBRA`-owned
  `role_assignment` inserted inside the `ACME` context block — **RLS refused it**, 22/22 failed;
  seed each company's rows under its own context. (2) `"\211PNG"` is a Java **octal escape**
  (U+0089, two bytes in UTF-8), so a 4-character literal is 5 bytes. Also: the `DOC-n` counter is
  **per company** and every page any test in the class creates advances it, so a literal `DOC-1`
  was right on the first run and wrong on the second — read the key off the row.
- Fixture prefix **`df`** (`DocPageAttachmentEndpointTest`, 22), checked against every prefix in
  `src/test/java` first. It has a fifth persona, `OTHER_LEAD`, purely so the ungranted sibling
  project's page can be *authored* — a page in a project nobody holds cannot be created through the
  endpoint, and hand-seeding one produces a page shaped unlike every real one.
- **`ArchitectureRulesTest` does NOT guard a consumer's declaration.** `storageDependsOnTenancyAlone`
  checks `storage`'s own; widening `documentation`'s would pass every rule in the file. Recorded as
  gap `DA-14`, not as a check.

## §126 phase 1 — THE ATTACHMENT OWNER VOCABULARY IS A TYPE, AND THE PORT WAS RENAMED

**`EvidenceStore` is now `AttachmentStore`; `FilesystemEvidenceStore` is `FilesystemAttachmentStore`.**
It backs four owner types, not one. **The property key `tcms.storage.evidence.root` was deliberately
NOT renamed** — renaming it relocates the root on the next deploy and orphans every existing blob
(§107's evidence destruction, in config form). Do not "finish" the rename.

- **`storage.AttachmentOwner` is a public enum** — `CASE · EXECUTION · STEP_RESULT · DOC_PAGE ·
  ARTIFACT`. `name()` **is** the column value, and `CaseAttachmentEndpointTest.OwnerTypes` reads the
  `CHECK` out of the live database and asserts the two sets equal. Adding a constant without a
  migration fails the build.
- **`V39` dropped `STEP` from the `CHECK`** (#50). It is not a gap: a step's file belongs to the
  recorded result (`STEP_RESULT`), not to the case definition.
- **`Attachment` has five factories and they are still the only place an owner type is chosen** —
  `forExecution · forStepResult · forCase · forDocPage · forArtifact`. All five take a
  `storage.BlobUpload`, which is where the filename sanitizer, the SHA-256 and **`BlobUpload.key`**
  (the one place a storage key is built) now live. `execution.EvidenceUpload` is a thin
  `record EvidenceUpload(BlobUpload file, String caption)`.
- **`AttachmentRepository.findOwnedBy` is PLURAL and has no singular sibling, on purpose** — a
  per-owner read is an N+1 inside `CaseBulkService`'s 500-case loop. `findOwnedById(owner, ownerId,
  id)` is the scoped point lookup; both halves of its predicate are load-bearing.
- **`repository` declares `{"tenancy", "project", "identity", "storage"}`.** ~~`documentation` does
  **not** yet — phases 2 and 3 add it.~~ → **phase 2 added it**; see the section above.
- **The `CASE` family is `CaseAttachmentService` + `CaseAttachmentController`**, its own controller
  because multipart brings a `MaxUploadSizeExceededException` handler. Gate is **`case:edit` on both
  writes with no `OWN` arm** (the delete deliberately does not copy `evidence:delete`); the download
  costs nothing beyond the case read gate. **There is no `GET` collection** — the list is
  `TestCase.attachments`, resolved by `CaseRenderer.attachmentsFor` in two queries per batch.

## §125 — THERE IS AN ELEVENTH MODULE AND A FOURTH FOUNDATION MODULE

**`com.tcms.storage`.** Read this before assuming where a type lives: `Attachment`,
`AttachmentRepository`, `EvidenceStore`, `StoredBlob`, `FilesystemEvidenceStore`, `ReportStore`,
`FilesystemReportStore` and the new `FilesystemBlobStore` are **all in `storage`** — they were in
`execution` and `reporting` until 2026-08-07. `ReportBlob` **is deleted**; `ReportStore.open` returns
`StoredBlob`.

- **`ArchitectureRules.FOUNDATION_MODULES` is `{tenancy, identity, project, storage}`.** A
  cross-module edge may point at any of the four. `execution` and `reporting` declare
  `{"tenancy", "project", "identity", "storage"}`.
- **`storage` declares `{"tenancy"}` and there is an ArchUnit rule enforcing it from the code** —
  `ArchitectureRulesTest.storageDependsOnTenancyAlone`. Do not widen that declaration: it is legal
  under every other rule in the file (identity and project are foundation modules), and the rule is
  the only observer. The three properties that make a non-domain foundation module defensible are in
  the constant's javadoc; **a fifth foundation module is a decision, not an import.**
- **`Attachment`'s factories and getters are `public` now**, and the factories are the only place an
  `owner_type` string is ever spelled. #49 adds `forCase` / `forDocPage` / `forArtifact` **there**,
  not at call sites.
- **The two ports stay two types deliberately.** One `BlobStore` with two qualified beans was
  considered and refused: the wrong qualifier writes evidence under the reports root with nothing
  failing, where the wrong type does not compile. The shared **body** is `FilesystemBlobStore`,
  constructed (never injected) by each adapter with its own root and a `kind` label that must survive
  — it is what tells an operator which root failed.
- **The row/bytes ordering belongs to the caller, not to the port.** `execution.EvidenceService`
  writes rows first; `reporting.ReportJobRunner` writes bytes first; both arguments are in their own
  headers, and neither port has a method that would decide it for both.
- Plan §3's `shared — storage, audit, attachments, search` is **struck through**: `shared` is now
  `audit, search`. A foundation module may not be a grab bag.
- **Found by the move:** `FilesystemReportStore`'s traversal guard had never been asserted —
  `FilesystemEvidenceStoreTest` (now `FilesystemBlobStoreTest`) was the only test either adapter had.
  Two files in two packages are never read side by side.

**§124's three changes, all of them removals or first-consumers rather than new machinery:**

- **`AggregationPolicy` has FOUR values.** `BEST` is gone from **both** copies —
  `execution.AggregationPolicy` and `reporting.AggregationPolicy` — and
  `AggregateExecutionPolicyTest` pins the two name sets equal, which is what makes removing from one
  file impossible to land. An unrecognised policy is a **400 naming the four**, from
  `PlanController`'s message derived off `values()`; there is no fallback to `LATEST`.
- **`ExecutionService.record` asks `run:execute` INSIDE the verdict gate**, not before it —
  `projects.holds(userId, projectId, EXECUTE_RUN)` in the `writeGranted` callback, answering
  `Outcome.NotExecuting` (a **new sealed arm**, so `RunController`'s switch is exhaustive). Order is
  load-bearing: in front of the gate a Viewer gets advice to use an endpoint they also may not use.
  Automation Service is the only role `V3` withholds `run:execute` from, and `ExecutionEndpointTest`
  now carries a `ROBOT` persona (`e2003000-…-06`) for exactly that.
- **`ArtifactWriteService.delete` + `DELETE /projects/{id}/artifacts/{artifactId}`** — gated on
  `artifact:delete` (**not** `PERMISSION`, which is `artifact:edit`), unqualified per `V19`'s own
  comment. Two 409s via `Outcome.Undeletable(message)`: `write_once`, and
  `ArtifactRepository.isSupersedingSomething` (**native**, because `superseded_by_id` is deliberately
  unmapped on the entity). `findInProject(projectId, artifactId)` is the new lookup — `findById`
  would delete a sibling project's row through a gate that legitimately passed.
- **`Artifact`'s javadoc claim that "nothing targets an artifact" is struck through and corrected.**
  It described a self-FK from memory and nothing could disprove it while nothing could delete.

**Contract changes §121 phase 4 made — both on shapes, neither a new operation:**
- **`TestCase.flake`** (nullable, required) — **`CaseFlake` had NO READ PATH.** It was served only
  as `PUT …/quarantine`'s response for three phases, so the SPA could set a state it could not
  show. A write path's response looks like a read, which is why nobody noticed.
- **`CaseFlake.quarantinedBy` is a `UserRef`, was a bare `UUID`** — the **only** actor field on this
  API that was one, against ~25 that are not, and `CaseFlakeView`'s own javadoc asserted it followed
  the convention it broke. §100's rule again: *a comment stating a fact about another file is not a
  check.*

**New seams §121 phase 4 created — `repository`:**
- **`CaseRenderer.flakesFor(companyId, rows)`** — two queries for any batch size, and the **first
  caller `CaseFlakeRepository.findForCases` has ever had.** That method was written in phase 1 with
  no consumer at all: the *second* consumerless seam in that file, missed by phase 3 because phase 3
  was hunting the one whose javadoc named a caller. `CaseBulkService` renders up to 500 cases, so
  the batched form is what keeps the third lookup in that class from being N+1; the batch's map is
  threaded through `Context.flakes()` and populated only for `authored` commands.
- **`CaseRenderer.render(…, Map<UUID, CaseFlakeView>)`** is the new low-level overload; the 4-arg
  form resolves its own batch of one.
- **`CaseQuarantineService.Outcome.Done` now carries a `CaseFlakeView`, not the entity.** The decider
  is resolved **inside the service's transaction** — `CaseOwners` reads `app_user` through the
  tenancy filter, so resolving in the controller would fail *closed* and answer null on every
  successful quarantine, with nothing failing anywhere.
- `TestCaseDetailView` gained `flake`. **It therefore lands in the version snapshot**
  (`CaseVersions` serializes this whole view) — accepted, not unnoticed: `healthSignals` is derived
  and has been in that snapshot since M1, and the content **hash is over `CaseEdit`**, so a nightly
  recompute still cannot make a no-op save write a version. Making the snapshot authored-only is a
  separate slice; doing it here would break the response-is-the-snapshot invariant `CaseRenderer`
  exists to hold.

> **⚠ `Map.of().get(null)` THROWS NPE BY CONTRACT, AND THIS IS THE SECOND TIME IT HAS BITTEN (§38).**
> `flakesFor` short-circuited to `Map.of()` when no row in a batch named a decider, then looked up
> on a **nullable** column — so **every case detail 500'd whenever no case in the batch was
> quarantined**, which is the common path, while the batch that *did* contain one worked perfectly.
> `CaseOwners` carries a comment about exactly this and the new code copied its words, not its
> behaviour. Use `new HashMap<>()` on **both** arms whenever the lookup key can be null.

**New seams §121 phase 3 created — `work`:**
- `work.BoardFacts.quarantinedCases(projectIds)` — the **fourth** generator's fact, native SQL over
  `case_flake` joined to `test_case`. `BoardFacts` is still the one file in this module allowed to
  name a foreign table.
- `work.BoardService.PREFIX_QUARANTINE` (`quarantine:`) + `cardFor(QuarantinedCase…)`. **Not
  `case:`** — a case both flagged and quarantined would otherwise be two cards with one id.
- **`repository.CaseFlakeRepository.findQuarantined` IS DELETED**, and the reason generalises: its
  javadoc named §6l's fourth generator as its caller, and that generator lives in `work`, whose
  `allowedDependencies` are `tenancy`/`project`/`identity` — ArchUnit refuses the import. **A seam
  written ahead of its consumer is a guess about the consumer.** Its `ORDER BY flake_score` was
  wrong for the board too: `BoardService.sorted` re-sorts every card by (overdue, priority, id).
- **`QUARANTINE` is the seventh `WorkItem.kind` and the FIRST that needed a contract change.** §53
  declared six ahead of their generators; this is not one of them. It is deliberately not
  `CASE_MAINTENANCE`, because `BoardPage.describeCard` splits *that* kind's description on commas
  and looks each fragment up in the `HealthSignal` label map — a typed sentence renders as its own
  fragments joined by a middot, with nothing failing on either side.
- Card decisions worth not re-litigating: assignee is the **case owner** (the decider is named in
  the sentence, or the debt lands on whoever quarantined somebody else's test); `TODO`, computed;
  `allowedTransitions` empty; **this is the one derived card that does not clear itself**, because a
  quarantine is authored and a health signal self-heals.
- Fixture: `BoardEndpointTest.Quarantine` (5). **The phase's one red run was an id collision** —
  `…000c` was already `TEAM_ELSEWHERE` eighty lines away, every case helper is
  `ON CONFLICT DO NOTHING`, and the only red test was §84's team-board bound. Sixth instance of
  §19's family; **a collision fails somewhere else.**

**§122 CARRIED NO MIGRATION.** Schema head is still `V38`, `V39` next free, policy count 62 —
every table it touches (`app_user`, `instance_role_assignment`) has existed since `V1`.

**New seams §122 created — `provisioning`:**
- `provisioning.GrantInstanceAdminService.grant(email, actorUserId)` — **the only writer of
  `instance_role_assignment` in `src/main`.** Before this slice there were none: `V3` seeds
  `Role.INSTANCE_ADMIN_ID`, `PermissionEvaluator` resolves the SYSTEM term, the table has existed
  since `V1`, and only three *test* classes had ever called `InstanceRoleAssignment.forUser`.
  **Runs with NO `CompanyContext` open, deliberately** — both tables are on the ERD §0.1 exception
  list, `CompanyAwareTransactionManager.doBegin` treats a context-less transaction as legitimate,
  and opening one would imply an instance-wide power belongs to one client. Contrast
  `IssueInvitationService`, which must resolve its company id before opening the context.
- `provisioning.GrantInstanceAdminResult` — four outcomes. **`ALREADY_HELD` exits 0 where
  `IssueInvitationResult.ALREADY_ACTIVE` exits non-zero**; a grant is a *state*, an invitation is
  an *artifact*. `INVITED` is grantable (`provision-company` leaves its first admin `INVITED`, so
  refusing it makes the first operator uncreatable); `DEACTIVATED` is refused (inert today, widest
  power in the product on reactivation).
- `provisioning.GrantInstanceAdminCommand` — `ApplicationRunner`. **Its verb MUST be in
  `OperatorCommands`**: `TcmsApplication.main:36` reads that set before the context exists to pick
  `WebApplicationType.NONE`, and an unregistered verb boots a web server, so the command succeeds,
  prints, and then hangs for ever. One line, in a second file, invisible to a service-level test.

**§121 MOVED THE SCHEMA HEAD AND THE POLICY COUNT — `V38__case_flake.sql`, `V39` next free, and
policy count 61 → 62.** The first count move since `V31`: `V38` adds a **new company-owned table**,
where `V36`'s was instance-level and `V37`'s was a column on an already-policed one.
`SchemaConventionTest`'s pinned count (`SchemaConventionTest.java:270`) is what turns that into a
decision — it failed on arrival and had to be moved deliberately.

~~**§121 IS HALF SHIPPED. Phases 3 and 4 are NOT BUILT**~~ → **phase 3 shipped (above); only phase 4,
any frontend at all, is left.** One seam still has no caller and that is the state, not an
oversight: `CaseFlakeView` / the `CaseQuarantineInput` contract schema, for the SPA.

**New seams §121 created, in dependency order:**
- `repository.CaseFlake` — entity over `case_flake`. **The only half-derived, half-authored row in
  the product.** Two writers, kept apart by their `ON CONFLICT` / setter surfaces rather than by two
  tables. `unscored()` is the factory the quarantine path uses when the recompute has not run yet.
- `repository.CaseFlakeRepository` — **`@Transactional` WITHOUT `readOnly`**, unlike
  `CaseHealthRepository`: its owner writes through it, and `readOnly = true` would skip the flush so
  the write would silently do nothing while every read assertion still passed. `findScopedById`
  exists because `findById` bypasses the Hibernate company filter (`TestCaseRepository:45`).
- `repository.CaseQuarantineService` — the authored writer. Gated on **`case:edit`**, deliberately
  not `result:submit`; Automation Service does not hold it. `CaseController.quarantine` is the
  `PUT …/cases/{caseId}/quarantine` handler.
- `reporting.ExecutionSource.recomputeFlakeScores` — the derived writer, a **third** statement in
  `ExecutionFactRefreshService.refresh()`, in one transaction after the projection and
  `recomputeFailingRepeatedly`. Reads `execution_fact`, not `test_execution`.
- `reporting.ReportingProperties` gained `flakeWindow` (P90D) and `flakeMinimumTransitions` (4),
  both with compact-constructor bounds; `application.yml` carries the documented defaults.

**⚠ THERE ARE THREE IMPLEMENTATIONS OF §6l's FLAKINESS SENTENCE, AND ONLY TWO MAY AGREE.**
`execution.CaseHistoryService.flakiness` (§49, per-case timeline, Java over rows) and
`reporting.ExecutionSource.recomputeFlakeScores` (§121, SQL over the fact log) both answer *"how
flaky is this case"* and **must** agree on the predicate: `status_kind IN ('PASSED','FAILED')`.
`AggregateExecutionReport.inconsistentCases` (R7) answers a different question and uses
`counts_as_pass` — correctly. **`case_status.kind` and `case_status.counts_as_pass` are independent
columns**, agreeing on all six seeded defaults and diverging on a custom status, so this is a
divergence that ships. Do not "unify" the three.

**§120 MOVED THE SCHEMA HEAD — `V37__case_health_undeclared_automation.sql`** — and **policy count
stayed 61**: it adds one column and two partial indexes to `case_health`, which has been in `V1`'s
`company_owned` array since M0, so the policy is inherited. Five files touched in `repository`, one
new test class, one additive contract enum member. No new endpoint, no new query hook, no screen.
**§119 CARRIED NO MIGRATION** — four new `execution` files, seven new tests inside
`IngestionEndpointTest`, one contract addition (`GET /ingestions`, `GET /ingestions/{jobId}`,
`IngestionJobSummary`).
**§117 CARRIED NO MIGRATION** — sixteen new `execution` files, two test classes, and `V1`'s three
writerless tables went to **zero**.
**§116 is the first M3 slice and it MOVED THE SCHEMA HEAD** — `V36__api_token_lookup.sql`; policy
count stayed 61 (the new table is instance-level, no RLS) and it is the **twelfth** entry on ERD
§0.1's exception list.
**§115 added two files and one test class** — `DocumentationSource`, `CoverageMatrixReport`,
`CoverageMatrixReportTest` — plus contract prose. No migration, entity, endpoint or key.
**⚠ THIS HEADER WAS WRONG IN TWO PLACES AT §115 AND BOTH WERE STALE IN THE SAME DIRECTION** — §95's
"still owed (phases 2–4)" entry below is **closed**. The body claimed work that was already done,
which is the failure mode opposite to the one the trust-the-body rule guards against. Re-grep before
believing a "still owed".
**§114 (R8) touched three files** — `AggregateExecutionReport`, `ExecutionSource`,
`AggregateVersionDivergenceTest` — plus contract prose. **§112 (R7) touched the same three.**
**§101's backend half is phases 1, 2 and 4** — `V34` + `V35`, both rollup writers, the read switch.
**§99's backend half is phase 2 alone** — two request fields, one union query, no migration.
**§98 phase 2 was the backend half of the flattening** — `V33`, the entity, the two services, the
membership seams and a 35-file test-fixture sweep.
**§97 phases 4, 5 and 6 touched NO backend file** — the backend half of that slice is phases 3a–3c.
**Fingerprint files:** `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/gradle/wrapper/gradle-wrapper.properties`

> Authoritative docs win over this file. `CLAUDE.md`, `docs/HANDOFF.md` (an index — sections live in
> `docs/handoff/NN-slug.md`), and the specs listed in `CLAUDE.md`'s precedence table. This is a map
> for re-orienting fast, not a source of truth.

## Version lane

- **Java 21** — `build.gradle.kts:12`, `JavaLanguageVersion.of(21)`. Sealed types, records and
  pattern-matching switch are all in play and idiomatic here.
- **Spring Boot 3.5.6** / Hibernate 6 · **Gradle Kotlin DSL** with a version catalog
  (`gradle/libs.versions.toml`).
- **`-Werror`.** Suppressed lints in `build.gradle.kts` each carry a recorded reason. A new warning
  fails the build.

## Build, run & test

- **THE gate is `./gradlew build`** (compile with `-Werror` + all tests), from `backend/` — never the
  repo root. ~3 min locally. `test` alone is not the gate; `build` catches compile warnings.
- **Every test boots a real `postgres:17` via Testcontainers. Docker required, no H2 lane** — RLS,
  `ltree` and `citext` have no H2 equivalent. `systemProperty("api.version", …)` pins the Docker-29
  API floor (`build.gradle.kts:70`).
- Single class: `./gradlew test --tests 'com.tcms.tenancy.RlsIsolationTest'`.
- **`Clock.systemUTC()` on macOS yields only microsecond precision** (re-measured 2026-08-02:
  20000/20000 samples land on a whole microsecond), so any defect that depends on sub-microsecond
  nanoseconds is invisible to a local run and could only ever fail on Linux CI — which no longer runs
  tests. See `docs/handoff/58-*.md` and `60-*.md`. **A fix for this class is a no-op locally, so a
  green suite is consistent with both the fixed and the unfixed code**; assert the decision, not only
  the behaviour. `#confirmed`

## Layout

Spring Modulith **monolith**; modules are *packages* under `com.tcms`, not Gradle subprojects.
~~**Ten exist since §86**~~ → **THIRTEEN since §218**: `api · auth · documentation · execution ·
identity · integration · project · provisioning · reporting · repository · storage · tenancy · work`.
~~**`shared` is the only one on plan §3's list that still does not exist**, and the slice plan's
decision E declines to create it for two storage ports and an enum.~~ → **`shared` never arrived
under that name: §146 built `api` instead**, on §125's grounds that a module every other module may
depend on must not be a grab bag. `ModuleStructureTest` hard-codes the list, so a fourteenth is a
deliberate edit. `#confirmed`

- Cross-module FKs allowed **only** toward `tenancy` / `identity` / `project` — plus `storage`
  (§125) and `api` (§146), which are `ArchitectureRules.FOUNDATION_MODULES`' fourth and fifth and
  each carry a paragraph arguing the three properties they had to clear. Everything else
  crosses via domain events. **Enforced by ArchUnit** (`src/test/java/com/tcms/architecture/`), so
  the boundary is machine-checked. `#confirmed`
- `tenancy` is depended on by everything and depends on nothing.
- **Three modules are leaves and have a rule each saying so**: `provisioning`, `auth` and, since
  §218, `integration`. Modulith only catches a module *declaring* the edge; the rules also catch the
  composition root and a module widening its own declaration in the same commit as the import.
- No Domain/Application/Infrastructure layering — vertical slices per module, with
  `<X>Controller` / `<X>Service` / `<X>Repository` inside each.

## Seams to reuse

- **`${VAR:?msg}` IN SPRING CONFIG IS A DEFAULT, NOT A REQUIREMENT — §108, and it cost the
  production signing key.** This repo writes both Compose files and Spring config. In
  `compose*.yaml` the form means *"fail if unset"* and is used correctly on eight variables. In
  `application*.yml` **Spring has no such form**: it splits on the first `:` and takes the rest as
  the default value, so `${TCMS_JWT_SECRET:?a JWT signing secret of at least 32 characters is
  required}` silently resolved to the literal 59-character message — long enough to satisfy
  `@NotBlank @Size(min = 32)` on `JwtProperties` — and the deployed instance signed every session
  token with a string committed to this repository.

  **For a required property write `${VAR}` with no colon.** An unresolvable placeholder throws at
  bind time and the context does not start.

  Two things generalise beyond the one line. **`@DynamicPropertySource` hides shipped config**:
  `IntegrationTestBase` sets `tcms.auth.jwt.secret` directly, which replaces the placeholder, so
  1000+ green tests said nothing about the expression that actually ships — anything asserting a
  *resolved value* cannot see a defect in the *text*. And `ConfigConventionTest`
  (`src/test/java/com/tcms/config/`) is the answer: it parses `application*.yml` as source, boots
  no context, needs no database, strips comments first (the mail block legitimately quotes
  `${SMTP_HOST:-}` when explaining compose), and its negative case has been run. **Add config
  invariants there, not to a reading.** `#confirmed`

- **§120 — AUTOMATION DRIFT, AND `case_health`'s FOURTH AND FIFTH SIGNALS.**
  `V37` (**head; `V38` next free; policy count STILL 61**). Five files, all `repository`:
  `ExecutionFacts.recomputeAutomationDrift` (a **second statement**, not a second file — that class
  is the one place this module names an `execution` table), `HealthSignal.UNDECLARED_AUTOMATION`,
  `CaseHealth.undeclaredAutomation`, `CaseHealthProperties.staleAutomation`,
  `CaseHealthRecomputeService`. Tests: `CaseHealthRecomputeTest.AutomationDrift` (8, fixture `ce…`
  extended) and **`CaseHealthAttributesTest`** (2, **no database**).
  - **THE COLUMN WAS WRITERLESS FROM `V1` AND THE BLOCKER WAS REAL.** `stale_automation`'s predicate
    is *"no **ingested** result in N days"*, and until §117 nothing wrote
    `test_run.source = 'INGESTED'`. A computation over that selects the empty set in every
    deployment — §95's `usage_count` and §100's exit criteria. **Same family as §115: re-read a
    plan's "not covered" list against the code, third time.**
  - **BOTH SIGNALS READ ONE FACT ON TWO SIDES OF ONE CUTOFF**, so a case can **never** raise both —
    a property of the statement, not the schema, which is why they are one `INSERT` and not two
    jobs. `theTwoAreMutuallyExclusive` asserts it over the whole fixture.
  - **STALENESS REQUIRES A PREVIOUS INGESTED RESULT (`last_ci.at IS NOT NULL`).** "Stopped" presupposes
    started, and §6c makes `automationKey` optional *precisely so* a case can be `AUTOMATED` before
    its automation exists — the literal predicate fires an **ERROR** on every case the day a team
    declares intent. **The hole is stated**: `AUTOMATED` and never once reported raises nothing.
  - **THE MIRROR IS `<> 'AUTOMATED'`, NOT §6c's `MANUAL`.** `TO_BE_AUTOMATED` means the backlog is
    counting done work; `NOT_AUTOMATABLE` is the loudest, because coverage is
    `AUTOMATED / (total − NOT_AUTOMATABLE)` and a wrong one moves the **denominator**.
  - **THE MIRROR IS TIME-BOUNDED TOO, AND THAT WAS A REVERSAL.** Unbounded it can never go false —
    a case returned to manual after its automation was deleted stays flagged for ever, which is
    `HealthSignal`'s no-button-for-lying rule from the other side. One cutoff (#30's 14 days, and
    #34's "one figure to remember").
  - **NO `deleted_at` PREDICATE ON THE RUN, FOR AGREEMENT NOT CORRECTNESS.** The sibling statement
    counts every recorded execution; two neighbours disagreeing about what a result is surfaces as a
    case both `NEVER_EXECUTED` and `STALE_AUTOMATION`.
  - **`ON CONFLICT` NAMES THREE COLUMNS, AND THE RULE NOW BITES BOTH WAYS.**
    `reporting.ExecutionSource` writes `failing_repeatedly` into the **same rows** on its own
    schedule, so setting every column would blank a *live* sibling's work, not a hypothetical one's.
  - **A MUTATION ATTEMPTED THE WRONG WAY TAUGHT THE DURABLE THING.** Deleting `IS NOT NULL` outright
    reddens **all 19** — a NULL boolean into a `NOT NULL` column **aborts** the recompute, a crash
    that measures nothing. Mutating to the **rival** implementation (§6c read literally) reddens
    exactly one. **Mutate to the competing implementation, not to nothing.** `DR-1` (dropping
    `r.source`) reddens 5, and the extra four are the right four.
  - **`CaseHealthAttributesTest` DID NOT EXIST AND `HealthSignal`'s JAVADOC SAID IT DID** (§47 →
    §120). §100's rule exactly. `attribute()` is a **string** for Criteria, so a typo compiles,
    passes `-Werror` and `validate`, and throws only on the one path that filters — the chip, the
    tile and `signals()` all keep rendering off the `switch`.
  - **TWO STALE CLAIMS IN `HealthSignal`'s HEADER CORRECTED.** "Nothing populates it yet" (three
    computations had landed), and the `INCOMPLETE` paragraph: the **contract lists it and the SPA
    labels it**, so the two enums are not the same set — the wire is a superset by one member this
    server can never send. Left as it is; both tidyings are worse.
  - **FIVE SIGNALS STILL HAVE NO WRITER** — `OUTDATED_SPEC_CHANGED`, `STALE_APPROVAL`,
    `CONDITION_DRIFT`, `UNLINKED`, `ORPHANED`. Each is now the shape this slice was. `UNLINKED` is
    cheapest: `doc_link` has had a writer since §93.

- **§119 — THE INGESTION HISTORY READS.** `IngestionReadService` (`list`/`findOne`, both
  `readVisible` — membership, no new key), `IngestionJobSummary`, `IngestionJobRow`,
  `UnmatchedTests`. Two `GET`s on `IngestionController`; the second is the resource
  `POST /ingestions`' `Location` header has named since it shipped, at which there had never been a
  `GET`.

  **The list and the detail serve DIFFERENT shapes and must stay that way.**
  `IngestionJobRepository.findSummariesInProject` is a **JPQL constructor projection** into
  `IngestionJobRow`, so `raw_payload` — a `JSONB` blob, megabytes for a 5,000-test report — is never
  selected. `IngestionJobSummary` therefore has no `unmatched` field at all rather than an empty one:
  an empty array beside `unmatchedCount: 12` reads as *"the list was cut"*, which is the meaning that
  field is documented to carry. Reuniting them is a one-line temptation and it is mutation `IH-5`,
  which **no test can catch** — the check is the query log.

  `UnmatchedTests` re-reads the names out of the payload (`caseId` absent, or `recorded: false`) and
  is a **second implementation of `IngestionService.unmatchedIn`**, legal only because
  `IngestionEndpointTest.History.theDetailAgreesWithThePost` asserts the two against each other. It
  also repaired the **replay**, which served `unmatched: []` beside a non-zero count.
  `IngestionService.cap` is shared with it so the 50-name bound is not applied twice.

  `TestRunRepository.findRunKeys` is `findPlanKeys`' shape for run keys — one query per page, and
  `deletedAt IS NULL` so a deleted run yields a null key beside a non-null `runId`, which is what
  the summary tells "matched nothing" apart from "run deleted" by.

  **The `d7` fixture now seeds a SECOND PROJECT** (`SAVINGS`) and asserts that its job id is a 404
  through `findOneInProject`. That is the two-project fixture the project-predicate-over-RLS family
  has wanted since §115's CM-6; one of the seven files now has a test behind it, and the pattern to
  copy is `seedJobInSavings`.

- **§117 — CI RESULT INGESTION, AND `V1`'s LAST TWO WRITERLESS TABLES GET WRITERS.**
  **NO migration** (`V36` head, `V37` free, 61 policies). Sixteen files, all `execution`:
  `ReportFormat` · `IngestedOutcome` · `IngestedResult` · `ReportXml` · `ReportJson` ·
  `ReportParser` + four parsers + `ReportParsers` · `IngestionJob`/`IngestionStatus`/
  `IngestionJobRepository` · `AutomationKeyMapping`/`AutomationKeyMappingRepository` ·
  `AutomationMatcher` · `IngestionEdit` · `IngestionService` · `IngestionController` ·
  `IngestionJobView`/`UnmatchedTestView` · `AutomationMappingService`/`…Controller`/`…View`/
  `…Request`. Fixture prefix **`d7`** (`IngestionEndpointTest`, 21) plus **`ReportParserTest`**
  (17, **no database** — the parsers are pure).
  - **`ReportXml` IS THE ONLY `DocumentBuilderFactory` IN THE APPLICATION, and that is the whole
    defence.** This endpoint takes XML over the network from a credential holder; default JAXP
    inlines `file:///etc/tcms/tcms.env` into a test name, which is stored, matched and served back.
    A second construction site is the entire vulnerability and is what somebody adding a fifth
    format will write. **THE FLAG OVERLAP WAS MEASURED AND IT CORRECTED THE HEADER**: dropping
    `disallow-doctype-decl` leaves the external-entity test **GREEN** (the `external-*-entities`
    pair and `ACCESS_EXTERNAL_DTD` refuse the file read alone) and reddens only the
    billion-laughs test. Entity pair ⇒ disclosure; DOCTYPE ban ⇒ DoS. **Neither is redundant.**
  - **EACH PARSER HAS ONE SHAPE THAT READS A RED SUITE AS GREEN, and they are four different
    bugs.** JUnit: a pass says *nothing*, so the outcome is an ABSENCE — `getElementsByTagName`
    searches descendants, so a suite-level `<error>` lands on the first case. TestNG: an
    unrecognised `status` falling through to passed, and `is-config="true"` ingesting a
    `@BeforeSuite` as a test. Allure: `broken`/`unknown` are real statuses and neither is a pass.
    **Cucumber is the big one** — a scenario is the WORST of its steps, and "take the last step"
    reports a mid-way failure as skipped because Cucumber skips everything after a failure.
  - **`IngestedOutcome.severity()` HAS TWO CALLERS ON PURPOSE** — the Cucumber scenario reduction
    and `IngestionService`'s several-tests-one-case reduction. Same question at two altitudes; the
    copy is where one ranks a skip above a failure.
  - **THE LADDER IS THREE BATCHED PASSES, NEVER A CHAIN PER TEST.** 3 queries for a 5,000-test
    report, not 15,000 — the shape that works on every fixture and times out on the first customer.
    **Each rung is tested ALONE** (only case 1 has an `automation_key`, case 2 only a display key in
    its name, case 3 only a mapping): a ladder collapsed to rung 1 keeps its matched count and
    passes a report exercising all three. Mutation IN-3 confirms it.
  - **STEP 2's PATTERN IS FIXED — a stated departure from §6c's "configurable regex per
    project".** `\b([A-Z][A-Z0-9]{0,9}-\d+)\b`, looked up against **this project's**
    `test_case.key`. That bound is what makes a fixed pattern safe: a false extraction
    (`HTTP-404`) costs one `IN` entry and matches nothing, where a wrong *configurable* regex could
    match a real key. **A name yielding two distinct keys matches NEITHER.**
  - **MATCHING IS BOUNDED TO THE PROJECT though `automation_key` is company-unique.** A sibling
    project's case would put a `test_execution` and its `test_case` in two projects. Company scope
    still buys unambiguity, so the bound cannot silently pick the wrong one of two.
  - **NOTHING IS AUTO-CREATED FOR AN UNMATCHED TEST** (§6c's optional stub, declined): a CI job that
    can mint cases turns every test RENAME into a permanent duplicate — the identity problem
    `automationKey` exists to solve. **That is a design rather than a gap only because
    `automation_key_mapping` got its writer in the same slice**, and the response serves
    `normalizedName` so no client re-implements the one rule that must agree exactly.
  - **THE MAPPING WRITE COSTS `case:edit`, AND THAT IS THE FEATURE'S SECURITY PROPERTY.** §A6 gives
    Automation Service `result:submit` + `run:create` and NOT `case:edit`, so **a leaked token can
    post results and cannot re-point where anybody's results land** — otherwise it could bind every
    failing test to a throwaway case and launder a red suite green. No new key (§67, eleventh time).
    Re-confirming **MOVES** the row; a 409 makes correcting a mis-binding a delete-then-create with
    a silent non-matching window.
  - **`TestRun.create` GAINED A `source` PARAMETER AND ITS HEADER HAD ALREADY SPECIFIED THE RULE**:
    the DOOR the request came through, never the caller's role. `RunCreateService` passes `MANUAL`
    even for an Automation Service token; only `IngestionService` passes `INGESTED`.
    **`RunCreateService.createIngested` exists so ingestion grows NO second run-creation path** —
    a copy would be a second implementation of the pin.
  - **§116's SELF-INVOKED-`@Transactional` DEFECT WAS WRITTEN AND CORRECTED BEFORE IT RAN.** The
    parse must be outside the transaction (a megabyte of XML under a held connection), so the
    transaction starts mid-method — and a private `@Transactional` is not intercepted, which under
    `CompanyAwareTransactionManager` means no GUC and zero rows everywhere. Here the symptom is the
    idempotency lookup finding nothing, so **every** retry ingests twice. `TransactionTemplate`.
  - **A MALFORMED REPORT WRITES NO ROW**, because a `FAILED` row consumes the idempotency key and
    the corrected retry would replay the failure for ever. So `PENDING`/`PROCESSING`/`FAILED` have
    **no writer** in this lane — the async lane's, `CHECK` left wide (§93's reason, with the
    distinction noted: these name real states where `LinkSourceType`'s extra arms name nothing).
  - **A REPORT MATCHING NOTHING OPENS NO RUN** — a run of zero cases reads 0 of 0, 100%, complete
    (§99 one screen over), and it is the FIRST report a team posts that most often matches nothing.
  - **`CaseFacts` GREW THREE METHODS** (`caseIdsByAutomationKey`, `caseIdsByDisplayKey`,
    `caseKeys`) — the seam stays the one file naming a `repository` table. **The
    project-predicate-over-RLS gap is now SEVEN files** and is overdue; the fix is still one
    two-project fixture written once for the seam (§115's CM-6).
  - **EIGHT MUTATIONS WERE RUN — a stated §65 deviation, because both classes went green first
    time**, which is §114's own weakness. Each reddened its intended assertion and only that one,
    except IN-1, which corrected the code. Four more are queued. **No browser rows: there is no
    frontend surface**, and the two screens the slice names (ingestion history, unmatched-test
    binder) are recorded in the browser queue as unbuilt.

- **§116 — API TOKENS, AND THE FIRST CREDENTIAL RESOLVED BEFORE A COMPANY EXISTS.**
  `V36__api_token_lookup.sql` (**head; `V37` next free; policy count STILL 61** — the new table is
  instance-level). `ApiToken` · `ApiTokenLookup` · two repositories · `ApiTokenSecrets` ·
  `ApiTokenService` · `ApiTokenController` · `ApiTokenView` · `IssuedApiTokenView` ·
  `ApiTokenIssueRequest` · `TokenContext` (all `identity`), plus `ApiTokenAuthenticationService` /
  `ApiTokenAuthenticationFilter` and a `BearerTokenResolver` bean (`auth`). Fixture prefixes **`9d`**
  (`ApiTokenEndpointTest`, 26) and **`9e`** (`ApiTokenAuthenticationTest`, 13).
  - **`api_token` IS UNDER RLS AND THE CREDENTIAL ARRIVES BEFORE ANY CONTEXT.** That lookup returns
    zero rows — `V7`'s defect — so `api_token_lookup` is instance-level and holds a **digest, a
    token id and a company id and nothing else**. `V1`'s `api_token.token_hash` is **dropped**, not
    duplicated: one home for a credential digest. Lifecycle (`expires_at`, `revoked_at`) stays on
    the policed row and is checked **after** the context opens.
  - **THE CONTEXT MUST BE OPENED OUTSIDE THE TRANSACTION THAT READS THE POLICED ROW.**
    `CompanyAwareTransactionManager` sets the filter and the GUC in **`doBegin`**, so a transaction
    that *begins* without a context runs to completion without one however many `CompanyContext.open`
    calls happen inside it. The first draft was one `@Transactional` method and **every valid token
    401'd**; the fix is two `TransactionTemplate` calls (self-invocation would not have helped —
    `ReportJobRunner`'s argument). **This generalises to every future pre-auth lookup.**
  - **A TOKEN'S PERMISSIONS ARE AN INTERSECTION, IN `PermissionEvaluator` AND NOWHERE ELSE.**
    `cappedToToken` narrows the owner's answer by `RoleRepository.findHeldPermissionsForRole`, and
    `TokenContext`'s `projectId` is a hard bound applied **before** the project lookup. Never a
    union; never a second resolution beside the evaluator (`SessionTokens`' §6b argument).
    `PermissionQualifier.narrowedBy` is the **meet** to `strongest`'s join, and `OWN`∧`PLAN_SCOPE`
    answers `NONE` where the join **throws** — a narrowing that cannot be expressed has a safe
    answer and a widening does not.
  - **THE FILTER PRODUCES A `Jwt` VALUE**, carrying `sub` (the **owner**), `co`, and `tkn`. That is
    what lets every existing controller keep `@AuthenticationPrincipal Jwt` unchanged and lets
    `CompanyContextFilter` open the company by one path. It carries **no grant** — nothing signs it.
    `PermissionVersionFilter` branches on `tkn`: **no epoch** (the row is read every request), **yes
    account gate** (deactivation must kill issued credentials).
  - **WITHOUT THE `BearerTokenResolver` BEAN a valid token 401s before the filter is reached** — the
    resource server parses `tcms_…` as a JWT and fails. Right credential, wrong-credential symptom.
  - **`api_token.name`'s per-company uniqueness is named in ERD §0.1 point 3 and `V1` NEVER CREATED
    IT.** `SchemaConventionTest` asserts `company_id` leads every index *that exists*, which is
    silent about one that does not. `V36` adds it **partial on `revoked_at IS NULL`** so rotation can
    reuse the name. **Check for the missing index, not only the wrong one.**
  - `AdminReach` grew a fifth term, `issueTokens` — separate from `companyWide`/`manageTeams` for
    that record's own recorded reason. `token:issue`'s **first consumer**, the fifth permission to
    be correct-and-inert (§54, §62, §64, §69).
  - ~~**`ingestion_job` and `automation_key_mapping` STILL HAVE NO WRITER.** M3 slice 2.~~ →
    **closed by §117**, above. `V1`'s writerless tables are now zero.

- **§115 — THE COVERAGE MATRIX, AND `reporting`'s SECOND FOREIGN-MODULE SEAM.**
  `CoverageMatrixReport` (`type()` = `coverage_matrix`) + **`DocumentationSource`** (`doc_page` ·
  `doc_block` · `doc_link`). **No migration, no table, no column, no key**; contract change is prose
  (`gen:api` moved JSDoc, no type). `CoverageMatrixReportTest`, prefix **`c5`**, slug `cvm-`, 18
  tests. Requestable **by existing** — `ReportComputations` is a bean registry, so no controller,
  validation or list was edited.
  - **THE BLOCKER WAS GONE AND NOBODY HAD NOTICED.** M2.5's plan §8 lists the coverage matrix and
    doc-structured reports as blocked on *"`doc_link` having a writer, which it does not"*. §93's
    `BlockLinkService` is that writer, and `DocBlockView`'s header already carried the supersession.
    **Re-read a plan's own "not covered" list against the code before treating it as current.**
  - **ONE SEAM PER FOREIGN MODULE, NOT ONE PER MODULE.** `ExecutionSource` already names seven
    tables across three modules; a fourth module's three would make the file whose purpose is to keep
    the coupling reviewable unreviewable. **Case labels stayed on `ExecutionSource`** (`test_case` is
    `repository`'s and R7 already serves them) — a second copy would be one table read two ways in
    one module.
  - **THE PROJECT BOUND IS `findPage`'s ALONE.** `doc_block` and `doc_link` have no `project_id`, so
    the page is the only route to one. The other two methods take the id `findPage` returned and are
    package-private for that reason: **called with a page id from anywhere else they read a sibling
    project's document.** This is the **sixth** file layering a project predicate over RLS with
    **nothing checking any of them** (`PlanFacts`, `CatalogueFacts`, `ExecutionSource`×3). CM-6 is
    the third slice to record it as a gap; the fix is **one two-project fixture written once for the
    seam**, not a row per method.
  - **A STATEMENT IS A `list` BLOCK OR ANY BLOCK ALREADY CARRYING A `VERIFIES` LINK — never every
    block.** §93 made `level` a third arm on `list` so an outline is linkable per bullet, so the
    bullets are what a document intends to be verifiable. Every-block is `DocBlockView`'s
    zeroed-marker objection in an auditor's document: it marks a specification's prose as untested
    requirements. Consequence: **a page of prose reports 0 statements and an em-dash coverage**, not
    `0.0%`.
  - **`VERIFIES` ONLY.** `REFERENCES` is *"context, not coverage"* and `DERIVED_FROM` claims no
    proof; counting either is the exact false claim `LinkRelationship`'s header exists to prevent,
    and it is **one word** in the predicate. Also `source_type = 'TEST_CASE'`: the CHECK permits
    `SUITE`, which is a claim about a collection.
  - **THE LADDER IS PESSIMISTIC AND ITS TWO ABSENCES ARE DIFFERENT PROBLEMS.** Not covered →
    **Failing** (any linked case whose latest result is not a pass) → Not run → Verified. Failing
    outranks verified per §46; *nobody tested this* and *this fails* have different owners.
  - **THE POLICY IS FIXED TO `LATEST` AND SAID SO IN THE METADATA** (decision D). A coverage matrix
    asks *"is this verified now"*, which has one answer; `WORST` would print a failure fixed three
    weeks ago against a requirement. The `ORDER BY` is `resolvedCte`'s `LATEST` arm clause for clause
    (`NULLS LAST`, `execution_id` tiebreak) — **check both when either moves.**
  - **FIVE MUTATIONS WERE RUN — a stated §65 deviation, because the class went 18/18 GREEN FIRST
    TIME**, which is §114's own weakness. Each reddened the test written for it, three of them
    *alone*. `openDefects` (M4) and the doc **subtree** are named as not built. `DocBlockView.coverage`
    is **still absent and its reason has not moved.**

- **R8 (§114) — VERSION DIVERGENCE, AND M2.5's REPORTING PLAN IS NOW COMPLETE (R1–R8, F1–F2).**
  A sixth section on `AggregateExecutionReport`, over `selectedCte` for R7's reason exactly. **No
  migration, no key, contract change is prose** (`gen:api` moved 14 lines of JSDoc, no type);
  `AggregateVersionDivergenceTest`, prefix **`c4`**, slug `avd-`, 13 tests.
  - **THE PREDICATE IS `count(DISTINCT case_version_id) > 1` AND SAYS NOTHING ABOUT OUTCOMES**, so
    this section is strictly **wider** than R7's *Across versions* rows. A case that failed at v1 and
    again at v2 disagrees with nothing and is still two tests averaged as one — §6e's harm is the
    blending, not the disagreement.
  - **WHAT DIVERGENCE COSTS IS A PROPERTY OF THE POLICY, AND IT IS COMPUTED, NEVER ASSERTED.** Four
    different consequences (`ALL` blends · `LATEST` discards · `WORST`/`BEST` can keep a failure
    against rewritten text · `LATEST_PER_CONFIG` does both), so each row carries a `Counted in the
    totals` column — an `EXISTS` against `resolved` — and the narrative's closing sentence follows
    **`blendsVersions(rows)`, never `policy == ALL`**. A sentence keyed on the policy's name is a
    second implementation of the policy in English, where no reader can check it.
  - `versionDivergence` is **the one query in the class reading BOTH CTEs**: what diverged from
    `selected`, what the totals did with it from `resolved`.
  - **`ExecutionSource.caseVersionNumbers(projectId, versionIds)`** — the seam's third method,
    taking its table count to **seven**. `execution_fact` grows **no `case_version_no` column**:
    R7's mutability argument does not apply (the number is immutable), it is refused because a star
    schema duplicates the dimensions it aggregates *by* and nothing groups by version number.
  - **THIS SLICE HAS NO RED RUN** — tests written after the implementation, all 13 green first time.
    The substitute is structural (`theFixtureHolds` proves the resolved set is one row per case), and
    **R8-1 in the mutation queue is the only route to a measurement**. Seven mutation rows, four
    browser rows (`M6-30`…`M6-33`), none run.

- **R7 (§112) — WHICH CTE A SECTION READS IS THE WHOLE SLICE, AND `selectedCte` IS THE NEW SEAM.**
  `AggregateExecutionReport` grew three sections — *Inconsistent results*, *Manual and automated*,
  *First attempt and retries* — and `resolvedCte` was split so **`selectedCte`** (narrowed, **no
  policy applied**) is a named method. Three sections read each. **No migration, no key, no contract
  change beyond prose** (`gen:api` moved 19 lines of JSDoc and no type); `AggregateVariabilityTest`,
  prefix **`c3`**, slug `avr-`, 15 tests.
  - **A SECTION READING `resolvedCte` HERE IS PERMANENTLY EMPTY AND LOOKS FINE.** Under `LATEST`
    (the default) each case is one row: it cannot disagree with itself, its first attempt is its
    last, and its retries are already discarded. **The policy exists to hide exactly what these
    sections are about.** Grep for `selectedCte` before adding a fourth section and decide
    deliberately; the wrong choice is one word and silently ships a reassuring sentence.
  - **The inconsistency predicate is `passed > 0 AND failed > 0`, NOT the plan's
    `count(DISTINCT counts_as_pass) > 1`** — a recorded departure. The plan's shape lists a
    pass-then-BLOCKED case as flaky (§105's rule, one report over).
  - **A cell is (case, configuration, case version)** — §6p: alternation across configuration or
    version *"is just change, and reporting it as flake is a false signal"*. Used twice: the
    callout's `bool_or` per cell (the *Disagreement* column) and the retry section's partition. The
    retry counts therefore **do not sum to the distinct-case count**, which the column heading says.
  - **The split is a CROSS-TAB of two columns that both look like the answer** —
    `automation_status` is the **case's** (frozen, §86), `source` is the **result's**. Rows are
    **alphabetical**, because `AutomationStatus` (`repository`) and `RunSource` (`execution`) are
    both unimportable here; `statuses`' declaration-order rule is unavailable and alphabetical is
    what its argument actually needs.
  - **`ExecutionSource.caseLabels(projectId, ids)` closes the gap `RunSummaryReport`'s header has
    named since §87** (*"the per-case list waits for the slice that decides where case labels come
    from"*). In the seam beside `findRun`, same argument; **no `deleted_at` predicate**, bounded by
    `project_id` (the fourth file to layer that over RLS — and **untested here**, R7-6), and
    `execution_fact` deliberately grows **no `case_key` column**: both labels are mutable, and an
    append-only log would print names that no longer exist.

- **R6's AGGREGATE REPORT SHIPS THE AGGREGATION POLICY A SECOND TIME, IN SQL (phase 1).**
  `reporting.AggregateExecutionReport` (`type()` = `aggregate_execution`) + `AggregateSelection` +
  a duplicated `reporting.AggregationPolicy`. **No migration — `V35` still head, `V36` still free**;
  policy count **61**; no contract change in phase 1; no new key. It became requestable **by
  existing**: `ReportComputations` is a registry over every `ReportComputation` bean, so nothing in
  the controller, the validation or a list was edited — which is what R3 built that seam for.
  - **The enum is duplicated and the ALGORITHM is not** (slice-plan decision D). ERD §0.2 forbids
    importing `execution`'s, and decision E declines a `shared` module for one enum. The policy is
    resolved in **one window function over `execution_fact`**; `PlanStatisticsService.resolve` stays
    the only Java copy. The `ORDER BY` is written to line up with `resolve`'s comparator clause by
    clause and the class header carries the mapping table — **check it whenever either side moves.**
  - **`AggregateExecutionCrossCheckTest` is what makes the duplication legal**, asserting the two
    against **each other** over five policies. **Prefix `c1`, slug `aer-`.**
  - **A MUTANT FOUND THE FIXTURE VACUOUS AND THE REPAIR IS LOAD-BEARING.** Inverting `LATEST` to
    ASC — each case resolved to its *oldest* result — passed the whole class, because the fixture's
    two multi-result cases were exact mirrors (fail→pass, pass→fail) and reversing time swapped
    *which* case passed without moving a single total. `UNBLOCKED` (blocked→passed) is the row that
    breaks the symmetry. **Do not trim it.**
  - **THREE ASYMMETRIES WITH `PlanStatisticsService`, ALL DELIBERATE.** (1) the fact log has no
    *unrecorded* rows and `resolve` does, so the two agree on **recorded** results only; (2) `notRun`
    is scope-minus-touched and a star schema has no scope, so this report's denominator is
    *contributing executions*, named in the label and the narrative; (3) **`UUID` orders differently
    in Java (signed halves) and Postgres (unsigned bytes)**, so the `execution_id` tiebreak can pick
    different rows — reachable as a *count* only under `LATEST`/`LATEST_PER_CONFIG` with tied
    timestamps and differing statuses. Fixture timestamps are distinct for exactly that reason.
  - **`LEFT JOIN LATERAL unnest(...)` with an explicit empty-array arm** for failures-by-feature: a
    plain `unnest` drops the unclassified row and the section then reads as a project that
    classified everything — §96's `JOIN suite` defect in a second shape. Labels come from
    `ClassificationValueRepository` (`project` is an allowed dependency), **not** a SQL join, so
    `execution_fact` stays the only table the class names; `findAllById` on purpose, because a
    retired feature still named last quarter's failures.

- **THE M2 ROLLUPS HAVE WRITERS SINCE §101, AND THE TWO ARE NOT THE SAME SHAPE.**
  `reporting.RollupRefreshService` (a sibling of `ExecutionFactRefreshService`, driven by the same
  15-minute company loop in `ExecutionFactScheduler`, fact log **first**) runs four statements in
  `ExecutionSource`. **Migrations now run to `V35`; `V36` is the next free number.** Policy count
  still **61** — both tables were already in `V1`'s `company_owned` array.
  - `suite_run_summary` is **HISTORY**: `insertNewSuiteRunSummaries` (`ON CONFLICT DO NOTHING`,
    capturing `membership_snapshot` + `suite_version_no`) and `correctSuiteRunCounts` (tallying over
    **the row's own stored snapshot**). **The freeze is structural, not a set-list** — the correcting
    statement cannot reach the frozen columns. **Nothing deletes.** Re-deriving membership would
    rewrite a past run's pass rate the day somebody adds a tag (§6p open question #32).
  - `plan_snapshot` is **CURRENT STATE**: `refreshPlanSnapshot` (`DISTINCT ON` = the conflict target,
    `e.id` tiebreak, **every column moves** on conflict) **plus `prunePlanSnapshot`, which its sibling
    must never grow** — written as the insert's scope predicate negated, so the pair is total by
    construction. Without it a plan's totals stay too high for ever with no row that looks wrong.
  - **`ExecutionSource` holds a THIRD copy of `memberOf`'s three-arm union.** Held to
    `SuiteMembership` by `SuiteRunSummaryRefreshTest.Agreement`, through the `membership_snapshot`
    column the shipping statement writes — not through a resolution method added for the test.
- **`execution.RollupFacts` IS THE MODULE'S SECOND SEAM (§101)** — `suiteRunSummaries`,
  `runsWithoutSummary`, `planSnapshot`, `planTrend`. **The rule was never one seam per module but one
  per FOREIGN module**: `CaseFacts` reads `repository`, this reads `reporting`. **The `reporting` edge
  runs both ways at the TABLE level and in neither direction at the Java level**, which is the only
  level ArchUnit sees; `RollupAgreementTest` is what holds the two sides to each other.
  - **A ROLLUP IS USED ONLY WHEN IT IS COMPLETE, AND THAT IS A DEFECT REPAIR.** `servedFromRollup`
    (the policy) is **not sufficient** — it says what the rollup *can* serve, not what it *has* been
    written for. `snapshotIsComplete` is the second condition; without it a cold `plan_snapshot`
    reported **every case as not-run**, rendering as a plan nobody had started. Falling back to the
    live path is the safe direction: the failure it replaces is a wrong number, the cost is a slow
    correct one.
  - **The suite history is rollup ∪ TAIL.** `runsWithoutSummary` is not an optimisation — the rollup
    alone would **drop** a run recorded since the last tick, which is an absence rather than a stale
    number and something the live path never did.
  - **`plan_snapshot` serves 2 of 5 policies.** `LATEST` / `LATEST_PER_CONFIG` only; `WORST`, `BEST`
    and `ALL` need every execution and stay live, saying so with `computedAt: null`.
  - **Neither rollup may answer a denominator** — coverage, not-run and the scope size still come
    from `CaseFacts.casesInPlanScope` (§46, §6p).
  - **The unrecorded half stays live** (`TestExecutionRepository.findUnrecordedForPlan`). Bounded by
    (cases × configurations); dropping it makes a configuration with an opened-but-unrecorded run
    **vanish from `byConfiguration`**.
  - **The trend is written twice on purpose** — `plan_snapshot` is per case, a trend is per run, so
    the rollup path reads `execution_fact`. Held by the agreement test.
- **`computedAt` IS THE OLDEST `updated_at`, NEVER THE NEWEST (§101).** Both responses mix rolled-up
  rows with a live tail, so the newest is a claim true of one row and false of the others — false in
  the reassuring direction. Null on the wire means **computed live**, which is a positive statement;
  the contract marks it required for that reason. **Distinct from `PlanStatistics.stale`**: that is
  about the EXECUTIONS, this is about the ARITHMETIC.
- **A RUN'S SCOPE IS THREE ARMS SINCE §99 — `caseIds` ∪ `suiteIds` ∪ `tags`, RESOLVED ONCE.**
  `CaseFacts.caseIdsInRunScope(projectId, caseIds, suiteIds, tags)` is the whole resolution: one
  query, three `OR`s over one `test_case` scan, ordered by `key_seq`, **each arm appended to the SQL
  only when non-empty** (`IN ()` is a syntax error; `IN (NULL)` compiles and is never true). No
  migration — ~~`V33` is still the head, `V34` still free~~ → **`V35` is the head since §101**; policy count still 61.
  **THE SUITE ARM IS `memberOf` AND NEVER `c.suite_id IN (…)`** — a suite defined by a tag rule files
  nothing, so the column reading resolves it to the empty set and produces a run of zero cases that
  renders as 0 of 0, 100%, complete. Same class as §98's grid-vs-count defect, one screen over.
  **§82's approval gate needed no edit**: it binds on `edit.caseIds()`, the resolved union, so a new
  arm is gated by construction. Keep it that way — the moment anything gates a *request field*
  instead, every arm needs its own remembering.
  **The arms SUBSTITUTE for the plan's scope, never union with it** (`RunEdit`'s rule since M2).
  A run resolves **once**, at create; a plan's scope is references re-resolved on every read — the
  two are described in the same words and are not the same thing.
  **Unknown case/suite id = field error; unknown TAG = accepted** (no vocabulary to check against —
  `SuiteEdit`'s rule). The emptiness check is what catches a typo, and its message lands on **every
  arm the caller used**, `caseIds` only as the fallback for a body naming no scope at all.
  `RunEdit.MAX_TAG` is a third copy of `CaseEdit`'s cap, spelled out for `APPROVE_CASE`'s reason.
- **A SUITE IS FLAT SINCE §98 — `parent_id`, `path` and `suite_path_idx` ARE GONE (`V33`; ~~the
  head; `V34` next free~~ → **`V35` is the head since §101, `V36` next free**; policy count still 61).** A suite is a named collection, not a folder: no
  `Suite.getParentId()`/`getPath()`, no `parentId` on `SuiteView`/`SuiteWriteRequest`,
  `SuiteEdit.validateCreate`+`validateRename` collapsed to one `validate`, and
  `SuiteRepository.findLiveForProject` orders by **`keySeq`** (it ordered by `path` to make the flat
  payload constructible into a tree; what an order must still be is *total and stable*, which a name
  is not). `countLiveChildren` + `countLiveCasesInSubtree` collapsed to **`countLiveCasesFiledIn`**.
  **THE DELETE GATE IS THE FILED ARM ALONE AND THAT IS THE NARROWER OF TWO READINGS** — a case
  merely added or tag-matched lives elsewhere, so deleting the collection takes a claim and never a
  case.
  **THE DEFECT §98 FOUND PREDATES IT BY TWO SLICES: `caseCount` and the grid's `?suiteId=` filter
  answered different questions.** The count has been the §96 union since §96 while
  `CaseSpecifications.suiteScope` matched `suite_id IN (subtree)` — the FILED arm — so a tag-defined
  suite showed a count of twelve over a grid of none. `CaseScope` now carries **`memberCaseIds`**,
  resolved once by `CaseService` through `SuiteMembership.caseIdsIn`, and the predicate is on the
  case's own id. **Check both numbers together when either moves.**
  **`execution_fact.suite_path` OUTLIVED `suite.path` DELIBERATELY.** It is frozen history — where a
  case was filed *when it ran* — so `V33` touches no fact row and old rows keep dotted paths that
  describe a hierarchy that no longer exists. `ExecutionSource` writes **`('s' || s.key_seq)::ltree`**
  for new rows, which is exactly the path a *root* suite always had, and every suite is a root now.
  Do not "fix" a report that prints both shapes. The **`ltree` extension stays** — `doc_page.path`,
  `classification_value.path` and this column still use it.
  **`CaseFacts.inSubtreeOf` → `memberOf`, and `SuiteScopeAgreementTest` SURVIVES** — the §98 plan
  expected it retired, but the duplicated predicate is caused by ERD §0.2's module boundary, not by
  the subtree, so the two are now supposed to be *identical* rather than merely equivalent.
  `CaseConditions.ancestry` is a lookup rather than a walk: a suite's conditions reach the cases
  **filed** in it and deliberately **not** the other two arms.
- **A SUITE IS A RESOLVABLE COLLECTION SINCE §96 phase 1, AND A CASE MAY BE FILED NOWHERE.**
  ~~`V31__suite_membership.sql` (**head**, `V32` next free)~~ → ~~`V32` is the head since §97 phase
  3a~~ → ~~**`V33` is the head since §98; `V34` next free**~~ → **`V35` is the head since §101; `V36` next free. Policy count stays 61** — neither adds a table. `V31` (**policy count 59 →
  61**) creates
  `suite_case` (added individually) and `suite_tag_rule` (matched by tag, **unioned never
  intersected**), and relaxes `test_case.suite_id` to nullable — so membership is three arms:
  filed ∪ added ∪ tagged. `TestCase.suiteId`'s mapping lost its `nullable = false`.
  **THE LINE THAT IS SILENT WHEN WRONG IS `ExecutionSource`'s SUITE JOIN.** It is a `LEFT JOIN`
  since this phase, and `execution_fact.suite_id`/`suite_path` are nullable to match. With the inner
  spelling an unfiled case's **whole fact row** — not its suite columns — is never inserted: no
  error, a tick that logs success, and every report over the log quietly short. `RunSummaryReport`
  prints the NULL group as `RunSummaryReport.NOT_FILED` (`"(not filed)"`) rather than dropping it,
  which would be the same defect one layer up.
  `SuiteRepository.countLiveCasesBySuite` gained `AND tc.suite_id IS NOT NULL`: it counts what is
  **filed**, and the other two arms are resolved separately rather than summed into that number.
  ~~Still true after this phase: nothing can yet create an unfiled case through the API~~ →
  **phase 2 opened it.**
- **THE CASE SIDE OF §96 (phase 2) — `suiteId` IS OPTIONAL AND `tags` ARE WRITABLE.**
  `TestCaseInput.suiteId` is nullable (absent leaves the case where it is, **null unfiles it**);
  `TestCaseSummary.suiteId`/`suiteName` are nullable **together**, as are `PlanCaseRef`'s and
  `PlanStatistics.bySuite`'s three. `CaseUpdateRequest`'s refused list is down to **two** —
  `ownerId` and `automationTags` — and `automationTags` staying is deliberate: `case_automation_tag`
  is CI selection (§6c), a different table and a different job.
  New: `CaseTags` (reconcile, `CaseClassifications`' shape **without** the vocabulary bound, because
  a tag is free text and every tag a client omits is one it was shown), `CaseTagRepository.findForCase`,
  `CaseEdit.normalizeTags`, `CaseQuery.tags`/`unfiled`, `CaseSpecifications.tags`,
  `CaseScope.includeUnfiled`.
  **TAGS ARE NOT ON `CaseEdit` AND NOT IN `CaseVersions#hash`** — the slice's recorded departure
  from its plan line. `CaseEdit.Outcome.Valid` carries them **beside** the edit (null = "leave them
  alone"). Labelling a case `sprint-42` must not write a version whose diff says nothing about the
  test changing (§6j's empty-diff noise), and `CaseBulkService.AddTags` already said so by refusing
  to stamp `updatedAt`. It also makes §95's bulk-hash trap structurally unavailable.
  **TWO PLACES WHERE A NULLABLE `suite_id` SILENTLY DROPS ROWS, both fixed here and both found by a
  test rather than by reading**: `CaseSpecifications.suiteScope` (`IN (…)` is never true of NULL, so
  every unfiled case vanished from the grid) and `ProjectRepository.countLiveCasesByProject` (an
  inner `JOIN suite`, so the picker said 6 over a list of 7 — the very defect that method was
  written to fix, from the other side). `CaseFacts.casesInPlanScope` is a `LEFT JOIN` for the same
  reason. **Grep for `JOIN suite` before adding one.**
  Filters: `?tag=` repeats and **ANDs** (a grid narrows), which is deliberately the opposite of
  `suite_tag_rule`'s union (a collection widens); `?unfiled=true` is **ignored** beside `suiteId`
  rather than refused.
- **THE SUITE SIDE OF §96 (phase 3) — `SuiteMembership` IS THE ONE UNION.**
  `SuiteMembership.countsBySuite` / `caseIdsInSubtree` / `suitesByCase` (native SQL, explicit
  `company_id` **and** `project_id` predicates) resolve **filed ∪ added ∪ tagged**, deduped with
  `COUNT(DISTINCT c.id)` — a case reachable through two arms is one member.
  **`Suite.caseCount` is now resolved membership, not filed cases**, and the counts **no longer sum
  to the project's case count**: a case can be in three suites at once, so the client must say so
  rather than let a reader find it by subtraction.
  Contract: `Suite` gains required `tagRules` and `caseIds` (the **explicitly added** cases only —
  not the filed or tag-matched ones, because that list is what an editor reconciles); `SuiteInput`
  takes both, absent = "leave alone", `[]` = clear.
  New: `SuiteCase`/`SuiteTagRule` (+ `…Id`, `…Repository`), `SuiteMembershipWriter`,
  `SuiteEdit.normalizeTagRules`/`normalizeCaseIds`, `TestCaseRepository.liveIdsForProject`.
  **A suite may CLAIM a case and may never MOVE one** — filing is `test_case.suite_id` and belongs
  to the case's own write path; a suite edit that could re-file would let two screens write one
  column. `SuiteMembershipWriter` therefore touches two arms and never the third.
  ~~Still open after this phase: run scoping does not resolve the new arms~~ → **phase 4 closed it.**
- **RUN SCOPING RESOLVES THE UNION TOO (phase 4) — AND THE PREDICATE EXISTS TWICE ON PURPOSE.**
  `CaseFacts.inSubtreeOf(alias)` is the {@code execution} module's copy of the union, used by all
  four of its suite-expanding queries (`caseCountsByPlan`, `caseIdsInPlanScope`,
  `casesInPlanScope`, `caseIdsInSuiteSubtree`) via `"…%s…".formatted(inSubtreeOf("s"))`.
  ~~**This is what makes "run everything tagged smoke" work with no new mechanism** — such a run is
  a suite whose tag rule is `smoke`, scoped into a plan the way suites already are; there is no tag
  option on `plan_scope_item`.~~ → **superseded by §97 phase 3b (D8)**: `V32` gave
  `plan_scope_item` a `tag` arm, so there are now **two** ways to say "everything tagged smoke", and
  that cost was accepted rather than force a plan section through a whole reusable suite. The
  paragraph's other half stands — without `inSubtreeOf` a suite arm resolves to what is *filed*,
  which for a tag-defined suite is usually nothing.
  It duplicates `repository.SuiteMembership` because ERD §0.2 forbids the call, so the two are kept
  honest by **`SuiteScopeAgreementTest`** (prefix `5c`), which also pins the ONE intended
  disagreement: the seam **includes soft-deleted cases** (history must not shrink), the repository's
  union excludes them (the tree and grid must not show them).
  **Two traps that class paid for**: only one live member is *filed* in its fixture, so a
  filed-arm-only resolution reddens it — and calling a `CaseFacts` method straight from a test runs
  it **outside any transaction**, so `app.current_company` is unset and RLS answers zero rows. The
  first draft compared an empty list to an empty list and reported agreement; the fix is a
  `TransactionTemplate`, not just a `CompanyContext`.
- **A PLAN SCOPE LINE HAS THREE ARMS AND TWO LEVELS SINCE §97 phase 3b — AND THE PREDICATE IS ONE
  STRING.** `CaseFacts.scopeMatch()` is the case-or-suite-or-**tag** branch, interpolated into all
  **four** plan expansions (`caseCountsByPlan`, `caseIdsInPlanScope`, `casesInPlanScope`,
  `caseCountsBySection`). It wraps `inSubtreeOf("s")`, so the two predicates nest rather than
  compete. Before §97 the branch was two arms copied three times; the third arm is where copying
  stops being survivable, which is why it moved into a method.
  **Every arm is guarded by its own `IS NOT NULL`** — an empty section is stored as a row naming the
  section with all three targets null, and an unguarded `i.tag = pt.tag` on a NULL tag is *null*,
  not false. Same three-valued trap §96 found on the suite side, from the other direction.
  `PlanScope` is now `(suiteIds, caseIds, tags, sections)`; `PlanScope.Section` is
  `(name, position, suiteIds, caseIds, tags)`, plan-local and **id-less**, so `sections` present
  replaces the whole list. `allSuiteIds()`/`allCaseIds()` flatten both levels and are what
  `PlanEdit` live-id-checks — checking only the unsectioned group is §97's own version of the
  sibling-project hole.
  **The section name IS its identity** (storage keys membership on `section_name`), so duplicates
  are a 400: saved as two, they read back as one, silently merged.
  **`ck_plan_scope_target` is deliberately NOT a three-way exactly-one** — an empty section needs a
  targetless row that names it — so the rule is *at most one target, AND a targetless row must name
  a section*. `PlanScopeConstraintTest` (prefix **`5d`**) asserts both halves over plain JDBC, not
  through `PlanScopeItem`'s factories, which would pass with the constraint dropped outright.
- **`GET /projects/{projectId}/tags` — the picker list §96 shipped without.**
  `TagController`/`TagService`/`TagUsageView` + `CaseTagRepository.findTagUsage`, in `repository`,
  no new key (project visibility only, the eighth time that argument is made rather than a key
  invented), `StepSuggestionController`'s shape exactly.
  **`case_tag` has a `company_id` and NO `project_id`**, so the join to `TestCase` is the only thing
  scoping this — without it the endpoint serves a client's whole tag vocabulary across every project
  in their instance while every cross-company test in the suite stays green. `TagEndpointTest`
  (prefix **`6c`**) asserts it in both directions: the absent foreign tag *and* the uninflated count.
  **A picker's list, never a validator's** — a client may send a tag this never returned, which is
  how the first case to carry a new tag gets it.
- **THE CONDITION LIBRARY HAS WRITERS SINCE §95 phase 1, AND THE USAGE COUNT IS NOW TWO NUMBERS.**
  `ConditionService.create`/`update` + `POST`/`PUT /projects/{projectId}/conditions` +
  `ConditionEdit`/`ConditionWriteRequest`/`ConditionUsage` + `ConditionRepository.countCaseUsage`/
  `countSuiteUsage` + `ConditionWriteEndpointTest` (fixture prefix **`c9`**).
  **NO migration, NO table, NO column, NO new key** — policy count stays **59**, `V30` still head
  with `V31` free. `lock_version`, `created_by` and `updated_by` were all `V1` columns nobody had
  mapped.
  **This is the object §94 declined to make steps into.** A step is text *copied* from a corpus and
  carries no id; a condition is attached **by id** and an edit propagates everywhere. ~~Both
  libraries now have writers and they are deliberately opposite in kind.~~ → **§239: there is one
  library.** The shared-step half was retired because its by-id half never worked — nothing resolved
  an entry's body on any read path — so **`condition` is the product's ONLY shared authoring
  object**, and this paragraph is the definition of the surviving one rather than one side of a
  contrast.
  **`Condition.usage_count` is UNMAPPED and both counts are DERIVED**, two count queries (the
  retired `SharedStepService.rank`'s shape). The column read its `DEFAULT 0` on every row in every deployment,
  which was defensible while nothing consumed it and became indefensible the moment §95 made it an
  **impact warning**: a warning computed from a permanent zero is a confident wrong answer, not a
  cautious one.
  **THE decision is that it is TWO numbers that must never be added.** `usageCount` = distinct live
  cases attaching directly; `suiteUsageCount` = live suites attaching. A suite attachment inherits
  down the **whole ancestor chain** (`CaseConditions`' walk over `suite.path`), so its case reach is
  not computable here — the walk is `ltree`, HQL cannot express it, and a native query would drop
  the Hibernate company filter, the constraint every library read in this module records. Blending
  them would be a guess presented as a measurement **and would understate**, which is the direction
  an impact warning must never fail in. The contract had already posed this exact question on
  `/shared-steps` ("a design question rather than a join") and §95 answers it.
  **`lock_version` is mapped WITH `@Version`, from the entity's first writer** — §74's trap in its
  preventable form. Its two tests are a **pair**: `theVersionMovesOnEveryEdit` is what makes
  `aStaleIfMatchIsRefused` able to fail, since a version that never moves makes every `If-Match`
  match. `ApiProblem.stale("condition")`, not `conflict(title, detail)` — a lost update, whose advice
  ("reload and reapply") is the opposite of a state refusal's.
  **`findLiveInProject`, never `findById`** — the sibling-project row is in the same company, so RLS
  and the company filter both pass it; one test stands between a caller and editing a library they
  hold no grant on. Validation is **one method for create and edit** on purpose: a rule relaxed on
  the edit lets an entry reach, across every attached case, a state the create refused.
  **Live trap in the tests: these WRITE, and nothing rolls back a MockMvc request.** `ON CONFLICT DO
  NOTHING` left `lock_version` climbing across the class and six tests failed on legitimate 409s
  unrelated to their assertions; the seed is `DO UPDATE SET … lock_version = 0`.
  **Still owed (phases 2–4):** `CaseUpdateRequest` continues to **400 on `preconditions`/
  `postconditions`**, so nothing can attach a condition through the API yet; there is **no delete**;
  and the frontend has **no call site for the GET, POST or PUT**. `#confirmed`

- **THE STEP CORPUS (§94 phase 1).** `V30__test_step_action_trgm.sql` (**head; `V31` next free; NO
  new table, so the policy count stays 59**) + `StepSuggestionController`/`StepSuggestionService`/
  `StepSuggestionQuery`/`StepSuggestionView` + `TestStepRepository.findSuggestions` +
  `StepSuggestionEndpointTest` (fixture prefix **`c4`**).
  **`GET /projects/{projectId}/step-suggestions` is the editor's autocomplete corpus, and it is
  DERIVED, not curated** — a `GROUP BY (action, test_data, expected_result)` over the project's live
  cases, ranked by `COUNT(DISTINCT case_id)`. That is the deliberate correction to `shared_step`,
  which stayed empty because somebody had to populate it. **The two libraries now differ in kind:**
  a suggestion is **text copied** into the author's step and carries **no id at all**, so editing it
  later touches one case; a condition is **attached by id** and an edit propagates everywhere. The
  missing id is load-bearing and `StepSuggestionEndpointTest` asserts the exact field set to keep it
  missing.
  **`V30`'s index is on the EXPRESSION `lower(action)`, not on the column.** Postgres consults a
  functional index only for the expression it was built on, so `GIN (action gin_trgm_ops)` would be
  built, maintained on every write, and **never used** by the `LOWER(action) LIKE` predicate.
  **`shared_step_title_trgm_idx` and `condition_title_trgm_idx` (both V1) have exactly that gap
  today** — plain-column indexes under `LOWER(title)` queries — and were deliberately left alone;
  they are bounded to hundreds of curated rows, this corpus is not. `#confirmed`
  **Reuses rather than duplicates:** `ProjectService.readVisible` for the two-gate 404-never-403
  read (the corpus leaks *more* than a curated library, because nobody chose what went in), and the
  distinct-case aggregate shape the retired `SharedStepRepository.countUsage` shared. **HQL, never
  native** — a native query drops the Hibernate company filter, the constraint every library read
  here records.
  ~~**`StepSuggestionQuery` is the FOURTH copy of `escapeLike`** (`ConditionQuery`,
  `SharedStepQuery`, `CaseSpecifications`); the fifth should extract it.~~ → **§239: the THIRD** —
  `SharedStepQuery` went with the library, so the count went down before the fifth ever arrived.
  **§239 IS ALSO THE ANSWER TO WHY IT WAS NEVER EXTRACTED**: a copy is deleted by deleting the file
  that holds it, which is the same argument `IdempotencyKeyCapTest` makes and the same argument that
  test's own count proved when it went ten → nine in the same slice. `#confirmed`

- **THE PLAN'S DOCUMENT, AND `doc_link`'S FIRST WRITER (§93; adoption since §97 phase 3c).**
  `V29__doc_page_subject.sql` (**NO new table, so the policy count is unaffected**) +
  `DocSubject`/`DocSubjectType` + `PlanDocumentService`/`PlanDocumentController`/
  `PlanDocumentLinkInput` + `DocLink`/`DocLinkRepository`
  + `LinkSourceType`/`LinkRelationship` + `BlockLinks`/`BlockLinkService`/`BlockLinkView`/`BlockLinkInput`
  + `CatalogueFacts`. ~~**NO TESTS — the largest gap in the module**~~ → **closed**:
  `PlanDocumentEndpointTest` covers adoption, links, the two traceability 409s and `level`'s three
  arms.
  **A PLAN ADOPTS A PAGE; NOTHING HERE CREATES ONE (§97).** `PlanDocumentTemplate` and its `POST`
  are **deleted** — four of its five headings acquired real homes (this page, the `artifact`
  register, `scope_text`/`out_of_scope_text`, the plan's sectioned scope items), which left it
  seeding a document whose every section is authored elsewhere. `PUT` writes the page's
  `subject_type`/`subject_id` and **nothing on the plan**, hence `doc:edit`; `DELETE` clears them and
  **keeps the page**. So `DocPage.subjectType`/`subjectId` are **no longer `updatable = false`** —
  restore that annotation and every link answers **200 with a rendered page while the row never
  changes**, because Hibernate omits the columns from the UPDATE and the view is built from the
  in-memory entity. The guarantee it used to buy lives in `DocPage.linkTo`, which refuses a page
  that already has a subject: **re-pointing is unlink-then-link, two requests, two audit rows**
  (`doc_page.subject_linked` / `.subject_unlinked`, each carrying the subject, read *before* the
  columns are cleared on the unlink).
  **TWO 409s AND THEY ARE TWO THINGS** — *this plan already has one* (resolve on your plan) and
  *that page is another plan's* (resolve on the page) — checked in that order, because when both
  hold the caller can only act on the first. The page-side one **has no index behind it**:
  `uq_doc_page_subject` is on the *subject*, so a second plan adopting a taken page is a different
  key and the database permits it. The service check is the whole protection.
  `PlanFacts.findAnyPlanKeyInProject` is the one lookup here that does **not** filter `deleted_at`,
  because a soft-deleted plan still holds its page's slot and the refusal has to be able to name it.
  **The reference is on `doc_page`, opaque, because NEITHER side may hold a key**: `execution` and
  `documentation` may each depend only on tenancy/identity/project, so `test_plan.doc_page_id` is not
  a column anybody may add. §76's Review subject a second time. **The unique index is PARTIAL on
  `deleted_at IS NULL`** — a page's delete is soft, so a plain one makes a deleted document block its
  plan for ever, as a duplicate-key 500 naming an invisible row.
  **`doc_link` had no writer since `V1`, and three specified reads were waiting on it** —
  `DocBlockView`'s header said so in as many words, and `DocPageWriteService`'s two traceability
  `409`s **could not fire on any real request**. Both are live now. `DocBlock.links` is served;
  **`coverage` is still absent and its reason MOVED** (nothing here can *price* a link:
  `passed`/`failed`/`notRun` are two modules away and all five fields are required).
  **`CatalogueFacts` is `PlanFacts`' sibling** — one file per foreign module's tables, named after
  what it reads (`repository`: `test_case`, `suite`). Both layer a **project predicate over RLS**,
  which only scopes to the company; without it a sibling project's case links successfully.
  **`level` grew a THIRD ARM** — required on `heading`, optional on `list` (indent depth), refused on
  the rest. Forced by the outline: a `doc_link` targets a *block*, so a scenario a case can verify
  must BE a block, so one bullet is one block, so depth is a field.
  **`LinkSourceType` is narrower than the column on purpose** (`DEFECT`/`DOC_PAGE` have no writer);
  the `CHECK` stays wide, because one tightened and loosened again is a constraint nobody trusts.
  `#confirmed`

- **SAVED REPORT DEFINITIONS SINCE §92 (R5).** `ReportDefinitionRepository` +
  `ReportDefinitionService`/`ReportDefinitionController`/`ReportDefinitionView` + `ReportTypes`,
  and `ReportDefinition` gained a `create` factory. **`V28__report_artifact_definition_on_delete.sql`
  is the head migration, `V29` next free**; policy count unchanged at **59** (no new table).
  **Owner-only rows, and the repository is the enforcement**: it has *no* method able to find a
  definition the caller does not own (no `findById`, no `findByIdAndProjectId`), because
  resolve-then-check-the-owner is the shape that passes an access-control test one call site over.
  Somebody else's definition ⇒ **404** on the delete, **400 field error** on generate. All three
  operations cost **`report:view`** — including the two writes, because the row is the caller's own
  workspace and `report:export` would stop §6e's read-on-screen persona saving its own selection.
  `definitionId` on generate supplies the **type** and the **base** for `parameters`;
  `ReportParameters.merge` is **shallow** and the merged result is what the job stores.
  `ReportTypes` is the single "this server has no report called that" refusal, shared by save and
  generate.
- **THE REPORT PIPELINE IS END TO END SINCE §87 (R3).** `V27__report_job.sql` (policy count
  **58 → 59**, ~~head migration, `V28` next free~~ → **`V28` is the head since §92**) + `ReportJob`/`ReportArtifact`/`ReportDefinition` +
  `ReportJobRepository`/`ReportArtifactRepository` + `ReportStore`/`FilesystemReportStore` +
  `ReportModel` + `MarkdownReportRenderer`/`JsonReportRenderer` + `ReportRenderers`/`ReportComputations`
  + `RunSummaryReport` + `ReportJobService`/`ReportJobRunner`/`ReportJobScheduler`/`ReportReadService`
  + `ReportController`. **`reporting` now declares `{"tenancy", "project", "identity"}`** — the
  widening §86 named rather than pre-approved, made in **three places none of which the compiler
  checks**: the annotation, the package comment and `ModuleStructureTest`'s `@DisplayName` rationale.
  **A TABLE, NOT AN `@Async` DISPATCH** (decision B): an in-JVM dispatch loses every in-flight job on
  a restart with no symptom but a spinner, which gives back exactly what §6e's 202 bought.
  **The company loop is the only way the worker sees a row**: `report_job` is under
  `FORCE ROW LEVEL SECURITY`, so a global "find all pending jobs" returns **zero rows** and the tick
  logs success having done nothing, for ever. Three transactions per job (`TransactionTemplate`, not
  three `@Transactional` methods — those are self-invocations the proxy never sees); `attempts` moves
  on the **claim**, not on failure, or a process dying mid-run reads 0 for ever.
  **BYTES FIRST, ROW SECOND — the inverse of `EvidenceService`/§67, argued in `ReportJobRunner`'s
  header**: the job row already names the work so an orphan blob *is* findable, while a `COMPLETED`
  job whose download 404s is a client already told the report is ready.
  ~~**`ReportStore` is `EvidenceStore` copied, with the debt named at BOTH ends** (decision C / §58 E1)
  — `reporting` may not import `execution`, and a `shared` module for two interfaces and a record was
  refused (decision E, §76's argument).~~ → **§125: both ports moved to `storage`, the body is one
  `FilesystemBlobStore`, `ReportBlob` is gone. The ports stayed two types on purpose.** Its root is a separate property: reports are the first
  storage in the product that is **not backup-critical**, because every one is regenerable from
  `execution_fact`.
  **Every job writes TWO artifacts** (decision F) — what was asked for, and the JSON model the
  on-screen view will read, so screen and export are one model rendered twice; a JSON request writes
  **one** artifact with both columns naming it.
  **Request-time refusals vs late ones**: an unknown type and an unrenderable format are **400s**
  derived from the two registries (never a literal list); PDF says *"not installed on this server"*.
  A report's **own** parameters stay late — an unknown `runId` is a `FAILED` job carrying the
  computation's sentence. `#confirmed`
- **THE REPORT HEADER, AND THE THIRD RENDERER (§88 / R4).** `ReportHeader` + `ReportDocument` +
  `ReportHeaders` + `HtmlReportRenderer`; `ReportRenderer.render` now takes a **`ReportDocument`**,
  never a bare model. **No migration (`V27` still head, `V28` free), no table, no column, no key,
  policy count still 59; the contract change is prose only.** **The header is the JOB's knowledge**,
  so a computation cannot be asked for it — a report type that forgot to hand it back would produce a
  **headerless document that renders perfectly**; the pair is one argument so that "render the report
  without saying what it is of" is not a call anybody can make. The `JSON` artifact stores the
  document, so F2 renders the header the export printed. **`ReportDocument.headerBlock()` is the ONE
  assembly** — provenance, then selection, then the report's own metadata — and **the aggregation
  policy stays in the metadata deliberately**: only the report can answer it, and a header field
  would be a second home. **The selection is read off the STORED parameters**, never off what the
  computation used, or a filter the report ignored vanishes from the document; entries are prefixed,
  because a client's parameter could be called `Company`. **§6u branding is a display name only** —
  `logo_storage_key` and the two colour columns are unmapped `V1` columns with no writer (M6) and
  **no per-project override column exists**. **`HtmlReportRenderer` is hand-written, NOT Thymeleaf**
  (a recorded deviation from the plan): `text()` is the only path a string takes to the output and
  the test counts **19** escaped positions, which is stronger than a template engine and avoids
  installing view resolution into a REST API that has never had any. **Self-contained is asserted** —
  no script, link, img or absolute URL; charts are CSS bars scaled against the series' own max, and
  an all-zero series draws **no** bar (`width: NaN%` renders full-width). **HTML became requestable
  because a bean exists** — nothing in the controller, service or validation was edited. `#confirmed`
- **`ProjectService.readGranted` (§87) — the product's first read gated on a permission key beyond
  visibility.** Visibility still answers **404**; what answers **403** is a feature of a project the
  caller is demonstrably looking at. A distinct method rather than a flag on `writeGranted`, which
  would run a read in a read-write transaction and read as a write in the diff. `#confirmed`
- **`report:view` / `report:export` got their FIRST consumer in §87 — no new key**, the eighth
  instance of that shape. **And until §87 phase 6 both gates were DECORATIVE**: `V3` grants the two
  together to four of the six built-ins, and Automation Service (the fifth) holds no `project:read`,
  so it 404s on visibility one step earlier — **no persona the suite could build lacked either key**.
  `ReportEndpointTest` therefore inserts the suite's **first custom roles, and there are two**: one
  holding `report:view` and **not** `report:export` (which is what makes the two keys
  *distinguishable*), one holding **neither** (which is what keeps the *poll's* gate non-vacuous).
  Both hold `project:read`, so their refusals are 403 where a no-grant member gets 404.
  **The `@AfterAll` that deletes them is load-bearing**: `SeedDataTest.sixBuiltInRoles` asserts
  `SELECT name FROM role WHERE NOT built_in` is **empty**, so a leaked fixture role reddens an
  unrelated class in the full suite only, at a moving position — §19's class through a mechanism **no
  UUID prefix protects against**. Assignments delete first (`role_assignment.role_id` has no
  `ON DELETE CASCADE`; `role_permission` does). `#confirmed`
- **`execution_fact` HAS A WRITER since §86, and `reporting` is the module that had to exist for it.**
  `reporting/package-info.java` + `ExecutionSource` (the seam) + `ExecutionFactRefreshService` +
  `ExecutionFactScheduler` + `ReportingProperties`. **No migration (`V27` still free), no contract
  change, no key, no table, no column** — policy count stays **58**; `execution_fact` and
  `case_health` are both `V1` tables already on `V1`'s `company_owned` array.
  **The module had to be created before anything could be written**: the projection names
  `test_execution`/`test_run` (`execution`), `test_case`/`suite` (`repository`) and
  `case_status`/`case_classification` — three modules, and no existing module may write it, which is
  §81's argument arriving at a module that did not exist yet. `ExecutionSource` is the **fourth**
  `*Facts`-shaped seam (`CaseFacts` · `ExecutionFacts` · `BoardFacts` · this); nothing else in the
  package may name a foreign table. **The dashboard stays in `repository`** —
  `DashboardController`'s header already argued why and §86 does not overturn it.
  **`allowedDependencies = {"tenancy"}` ALONE**, narrower than the plan proposed and than every
  sibling: nothing here is reached through an endpoint, so there is no project gate and no person to
  name. `documentation`'s precedent in both directions — declaring the three it will eventually want
  makes the declaration a **forecast**, and a forecast cannot fail the build when it stops being
  true. ~~**R3 widens it**~~ → **widened in §87 to `{"tenancy", "project", "identity"}`**, and `V27`
  is no longer free: see the §87 entry at the top of this list.
  **THE decision is the `ON CONFLICT` set-list: seven columns move, fifteen do not.** Move =
  `status_kind`, `counts_as_pass`, `executed_at`, `executed_by`, `duration_ms`, `release_version`,
  `release_sort_key` (an amended verdict is corrected). **Frozen** = `automation_status`,
  `test_type_id`, `test_level_id`, `feature_ids`, **and `suite_id`/`suite_path`, which the plan did
  not name** — same argument, and freezing is the safe direction since a frozen dimension that should
  have moved is one statement to change while the reverse has already destroyed the history it
  rewrote. `EXCLUDED.*` everywhere is shorter, is what anybody would write, and re-files last
  quarter's results under this quarter's taxonomy every fifteen minutes. Star schema: a fact's
  dimensions are as-of the fact.
  **Idempotent recompute, NOT events** — zero `CompanyScopedEvent` implementations and no outbox
  consumer means an event-only log has **no rebuild path**, and a short log renders identically to a
  correct one. Deliberately the shape an event handler would call, so landing `ResultRecorded` adds a
  handler and changes nothing.
  **"Recorded" is the INNER JOIN to `case_status`** — `execution_fact.status_kind` is `NOT NULL`
  while `test_execution.status_id` is nullable, so the log structurally holds only recorded rows.
  **Consequence for every report downstream: coverage / not-run denominators can NEVER come from this
  table** (§46's finding arriving from the schema).
  **Soft deletes: the fact log KEEPS them, `case_health` does not.** Opposite calls, consistent —
  `V1` calls the log *append-only* and this writer never deletes, so filtering on insert would make
  its contents a function of when the job ran relative to the delete. History versus a worklist.
  **Live traps.** `max(uuid)` **does not exist in Postgres** (17 or otherwise) — the lateral uses
  `(array_agg(… ORDER BY …) FILTER (…))[1]`, with the `ORDER BY` so a relaxed SINGLE-cardinality
  trigger cannot make the statement non-idempotent. And **a fixture UUID prefix must be HEX**: `rf…`
  threw in the static initialiser and failed all eleven tests before one ran (§19's family, new
  mechanism). `#confirmed`
- **`case_health.failing_repeatedly` has a writer since §86 — signal 3 of 9, and the FIRST column of
  that table written by a module other than `repository`.** `ExecutionSource.recomputeFailingRepeatedly`,
  wired into the same scheduler tick. **`ExecutionFacts`' rule finally has a test**: *"a signal's
  owner writes its own column and no others; the row is shared, the columns are not"* — prescribed
  since §53 and unexercised until a second writer existed. The clobber would surface an hour after
  the slice ships, as a bug in `CaseHealthRecomputeService`, which would be innocent. `computed_at`
  is shared by both writers deliberately: it means *somebody looked*.
  **`status_kind = 'FAILED'`, NEVER `NOT counts_as_pass`** — `BLOCKED`/`SKIPPED`/`RETEST`/`UNTESTED`
  all satisfy the tempting spelling, so a case nobody could *run* would be reported as a broken test.
  **The window must be FULL** — compared to N, not to the rows found; the *all of what we have*
  reading lights the tile on the first failure a young project records. **N = 3**
  (`tcms.reporting.failing-repeatedly-window`), a COUNT not a `Duration`, and `ReportingProperties`
  **refuses to start below 2**. **Found and NOT fixed: `case_health` has no index on
  `failing_repeatedly`** while `V1` ships four partial indexes for other signals — the first tile
  that scans; an index is a migration and §86 has none. `#confirmed`
- **`ExecutionFactScheduler` is `CaseHealthScheduler`'s TWIN, and the company loop is not an
  optimisation.** Two schedulers rather than one because they write read models owned by different
  modules that may not call each other. A scheduled task starts with **no company**, so a global
  "project every pending execution" query sees **zero rows** under `V4`'s `nullif` and **logs a
  successful tick having done nothing** — invisible. `company` is not under RLS precisely so it can
  be read first. One transaction per company; **facts before signals in that order**, since the
  signal reads the log and reversing them makes it permanently one tick stale (and a stale boolean
  renders like a fresh one). `PT3M`/`PT15M` rather than case-health's `PT2M`/`PT1H` — a fact is stale
  the moment a result is recorded, and the offset keeps the two schedulers out of lockstep.
  **`tcms.reporting.enabled=false` in `IntegrationTestBase`**, and it matters more than case-health's:
  the log is append-only with frozen dimensions, so a row written early is **not** corrected by a
  later refresh. `#confirmed`
- **Tenancy is the invariant everything rests on.** `CompanyContext` is a `ThreadLocal` exposed only
  as a scope guard — `try (var ignored = CompanyContext.open(id))`, never a setter. Deliberately
  **not** `InheritableThreadLocal`. `#confirmed`
- **`CompanyAwareTransactionManager` hooks `doBegin`**, so the context must be opened **around** the
  transaction, never inside it. A context opened after the transaction began leaves the RLS GUC
  unset and every read returns **zero rows**. Canonical ordering:
  `CompanyProvisioningService.provision():154-161` — resolve the id, open the context, then
  `transactions.execute(...)`. `#confirmed`
- **A repository interface must carry `@Transactional(readOnly = true)`.** Spring Data only gives a
  transaction to the CRUD methods *inherited* from `SimpleJpaRepository`, never to derived or
  `@Query` methods *declared on the interface* — without it those run with no company context and
  return zero rows, fail-closed and silent. `RepositoryTransactionTest` derives the repository list
  from the live context, so a new repository that forgets fails the build. `#confirmed`
- **Flyway owns all DDL**; `hibernate.ddl-auto: validate`, `baseline-on-migrate: false`. Migrations
  at `src/main/resources/db/migration`. ~~**Head is `V27__report_job.sql` (§87)**~~ ~~→ head is
  `V29__doc_page_subject.sql` (§93); `V30` is the next free number~~ → **head is
  `V31__suite_membership.sql` (§96 phase 1); `V32` is the next free number**, and
  `SchemaConventionTest`'s policy count is **61** (`SchemaConventionTest.java:262`) — `V31` adds
  `suite_case` and `suite_tag_rule`.
  Booting the
  real context in a test is itself a schema-drift gate. `#confirmed`
  **`V23` is the first migration since `V1` to CREATE A TABLE**, which makes two things live that no
  previous migration had to think about: a new table's RLS policy must be written with **`V4`'s
  `nullif(current_setting(...), '')` predicate and not `V1`'s bare one** (a table born with the old
  spelling reintroduces the reset-GUC 500 on exactly that table), and `SchemaConventionTest`'s policy
  **count moves 56 → 57** — the count is what makes the predicate check non-vacuous, so a new
  company-owned table is meant to move it. No `GRANT` is needed: `deploy/postgres/initdb/10-tcms-roles.sql`
  sets `ALTER DEFAULT PRIVILEGES` before Flyway runs. `#confirmed`
- **Privilege split** — Flyway migrates as `tcms_owner`, the pool connects as `tcms_app`, neither is
  a superuser and neither holds `BYPASSRLS`. Mirrored in three places that must agree:
  `deploy/postgres/initdb/10-tcms-roles.sql` (deployment),
  `src/test/resources/db/testinit/tcms-roles.sql` (suite), and the Hetzner runbook (which now
  *runs* the first file rather than carrying a copy — a copy drifted, see E1). `#confirmed`
- **One `Clock` bean**, `TcmsApplication.java:52`, injected into ~10 services rather than
  `Instant.now()` at call sites. **Truncated to microseconds** —
  `Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))` — because that is what a Postgres
  `timestamptz` stores; an untruncated instant makes a value that is returned in memory and read back
  two different values (§60). **`ArchitectureRulesTest.nothingCallsNowDirectly` forbids
  `Instant.now()` and friends in production code**, so a new call site fails the build; the
  `Clock`-taking overloads are allowed. `ClockResolutionTest` (`com.tcms.ops`) pins the bean's
  identity, deliberately, because the behavioural assertion cannot fail on macOS. `#confirmed`
- **Permission qualifiers** — `role_permission.qualifier` restricts WHICH ROWS a grant covers:
  `ALL` · `OWN` (V12, by `created_by`) · `PLAN_SCOPE` (V19, by the row's scope). The evaluator answers
  the qualifier; the **gate** applies it to the row, through `project.AuthorshipGate` /
  `project.RowScopeGate` — the row's fact crosses into `identity` and a verdict comes back, so no
  module holds a second reading of what a qualifier means.
  **Since §65 `PermissionSet` IS a `key → qualifier` map and `PermissionEvaluator.qualifierOf` is a
  one-line delegation to `evaluate(...).qualifierOf(key)`** — one traversal, so the strongest-wins
  merge and the replace-don't-union boundary exist in exactly one place (`PermissionEvaluator.merge`).
  The three grant projections return `HeldPermissionRow` (key + qualifier) with `DISTINCT` over
  **both** columns; the four per-key qualifier queries were deleted and must not come back, since a
  second resolution of "which grant wins" is the defect that removed them. `PermissionSet.of` THROWS
  on a key qualified `NONE` — absent, never present-and-covering-nothing. `#confirmed`
- **`/me/permissions` is `[{key, qualifier}]`, not `[key]`** (§65), sorted by key. That is what lets
  the SPA disable a control a qualified grant cannot use, instead of letting the submit 403 —
  `useCan.canOnScope` mirrors `IdentityProjectAccess.canDoOnScope` arm for arm. `#confirmed` `PermissionQualifier.strongest` throws on
  `OWN`+`PLAN_SCOPE` (incomparable axes); `SeedDataTest` keeps that unreachable by asserting no key
  carries two different restrictive qualifiers. **A new qualifier value is a compile error at every
  consumer** — the switches are exhaustive under `-Werror`, deliberately. `#confirmed`
- **The bulk gate is the one permission resolved from the REQUEST, not fixed at the class.**
  `BulkOperations.permission` returns `case:approve` when `SET_STATUS` targets `APPROVED` and
  `case:edit` otherwise (roles spec's Review table; plan §6g's single gate). Sound only while
  approvers are a subset of editors, which `SeedDataTest.everyApproverIsAlsoAnEditor` derives from
  the catalogue rather than assuming. **`lifecycle_status` has exactly one writer**, `CaseBulkService`
  — `CaseUpdateRequest` omits it deliberately. `#confirmed`
- **`CaseEdit.of` reconstructs a stored case's authored content by round-tripping through
  `CaseEdit.validate`**, never by reading getters: a second normalizer makes every `CaseVersions`
  hash disagree and fills the version list with empty diffs. Used only by the two bulk actions whose
  fields the hash digests (`MOVE_TO_SUITE`, `SET_AUTOMATION_STATUS`), and pinned by a round-trip test
  in `CaseBulkEndpointTest`. `#confirmed`
- **`CaseRenderer`** is the one assembler of a case's shape — extracted from the byte-identical
  private copies in `CaseCreateService` and `CaseUpdateService`. A fourth writer uses it, never a
  fourth copy. `#confirmed`
- **Evidence bytes live outside Postgres — the first product state that does.** `EvidenceStore`
  (port, ~~`execution`~~ → **`storage` since §125**) + `FilesystemEvidenceStore` (adapter,
  `tcms.storage.evidence.root`, delegating to `FilesystemBlobStore`), per open question #28. **`EvidenceService` writes ROWS FIRST, BYTES SECOND** in both directions, because a
  filesystem does not roll back with the transaction: the surviving inconsistency must be a row
  whose blob is missing (a 404 an operator can find by joining), never a blob no row names
  (unreachable, uncollectable). **No test asserts the ordering** — it is held by a comment, and the
  mutation queue records that reversing it reddens nothing. `#confirmed`
- **The storage key is three server-generated UUIDs** (`company/execution/random`) and never the
  uploaded filename, which lives in `attachment.filename` — a column, not a path. The adapter
  re-checks the resolved path against the root anyway. `#confirmed`
- **`Attachment` and `Evidence` are mapped in `execution`, deliberately, not in a `storage`
  module.** `ModuleStructureTest` hard-codes the module list AND the tenancy/identity/project FK
  rule, so a new module needs plan §3 amended plus two architecture tests changed — for one caller.
  `Attachment.forExecution`/`forStepResult` mean no call site spells an `owner_type`, so the
  writers that must move on the day it does are the ones that file names. `#confirmed`
- **`spring.servlet.multipart` is now set (10MB/12MB)** — uploads proxy through the API because a
  filesystem adapter cannot sign a URL, so this is the only bound between one caller and the shared
  disk. Boot's default (1MB) would refuse an ordinary screenshot. Over the limit is a **413** from
  the container, before the handler — hence `EvidenceController#onTooLarge`, not an `Outcome`.
  `#confirmed`
- **The run has a LIFECYCLE since §72, and it is the only writer `test_run.status` has ever had.**
  `RunLifecycle` (pure, static — the transition table, `isClosed`, and the ONE copy of the
  frozen-run sentence) + `RunStatusService` + `PUT /runs/{runId}/status`, gated on **`run:close`**
  at PROJECT scope. **"Closed" is a status PAIR** (`COMPLETED` ∨ `ABORTED`), never a fifth value —
  so no migration, and every reader keeps one notion of "over". `TestRun.begin` is called on EVERY
  result and answers whether it was the first (self-guarding, so the caller holds no second copy of
  the test); `applyStatus` derives `completedAt` from the target **in both directions**, clearing it
  on a reopen. Reopen normalizes both live spellings by `startedAt`, so an `IN_PROGRESS` run with a
  null `started_at` is unreachable through the API. **No `If-Match`** — the target state is
  absolute, so there is no stale-read-derived value to lose (`DocPageWriteService#setStatus`'s
  call). `updated_by` is mapped for the first time. `#confirmed`
- **THE PLAN LADDER IS THREE STATES SINCE §97 (M7): `DRAFT → IN_REVIEW → APPROVED`, and that is the
  whole of it.** `PlanLifecycle` (pure, static — the transition table, `permissionFor`,
  `standingOf`) + `PlanStatusService` + `PlanDeleteService` + `PUT /plans/{planId}/status` +
  `DELETE /plans/{planId}`. **The gate is decided by the EDGE, not the target**: `plan:approve`
  grants the sign-off and **takes it back**, `plan:edit` carries the plan forward through the states
  nobody signed off — a target-keyed rule lets a Test Engineer rescind a lead's approval by asking
  for `DRAFT`, and reddens nothing else in the suite. Hence
  `ProjectService.writeGrantedOnTarget` (§69's seam, second consumer): the deciding fact is the
  STORED status. `APPROVED` is reachable only from `IN_REVIEW` (§6g's one gate) and
  `APPROVED → IN_REVIEW` is refused — a withdrawn sign-off returns the plan to its author.
  **The delete refuses 409 + count while a live run names the plan** (`findPlanKeys` is not filtered
  on `deleted_at`, so nothing breaks loudly), and such a plan now has **no terminal state**: it
  stays `APPROVED` for ever, because a plan is an outline and its execution state lives on its runs.
  `applyStatus` deliberately does **not** bump `currentVersionNo`. `plan:review` stays
  unconsumed on purpose — §6g puts peer review inside `IN_REVIEW` without moving the item, like
  `case:review` and `suite:review`. `#confirmed`

  **What §97 removed, because a reader will find the arguments elsewhere and think they still hold:**
  ~~`ACTIVE`~~ and ~~`CLOSED`~~ (they described a plan being *run*, which `test_run` already does),
  ~~`test_plan.closed_at`~~, ~~`PlanLifecycle.isClosed`~~ and the closed-plan freeze on
  `PATCH /plans/{planId}`, ~~`PlanLifecycle.startsWithoutApproval`~~ and §82's whole plan gate with
  its `project_settings.require_approved_plan_to_start` column and
  ~~`PlanStatusInput.overrideApprovalGate`~~, ~~`PlanDeleteService`'s delete-or-close partition~~,
  and the audit verbs ~~`plan.started`~~ / ~~`plan.reopened`~~ / ~~`plan.closed`~~ — which survive in
  `audit_event` on any deployment that wrote them and are now the **only** record of which releases
  were executed. `V32` maps every `ACTIVE`/`CLOSED` row to `APPROVED` **inside a per-company loop**,
  because `test_plan` is under `FORCE ROW LEVEL SECURITY` and a bare `UPDATE` in a migration touches
  zero rows and reports success (V7's shape). §6g's **case** gate, `requireApprovedCaseForRun`, is
  untouched and still binds on the resolved set at run-create time. `#confirmed`
- **The plan has an EDITOR since §74, and it is the first writer of `entry_criteria`,
  `exit_criteria` and `test_plan_version` alike.** `PlanUpdateService` + `PlanUpdateRequest` +
  `CriterionInput` + `PlanEdit.validateUpdate` + `PATCH /plans/{planId}`, gated on **`plan:edit`** at
  PROJECT scope with `If-Match` on `lockVersion`. Four seams to reuse rather than re-derive:
  **`PlanScope`** is the ONE normalizer of "the same scope" (sorted, deduped — `plan_scope_item` has
  no ordinal column, so any hash over insertion order can move without the plan changing);
  **`PlanSnapshot`** is what a version records, and it is *scope* per `V1`'s column comment and §6j —
  milestone, both criteria lists, both id sets, and deliberately **not** name/description (content),
  status (§73 already declined), `caseCount` (derived) or `configurations` (a *project's* list);
  **`PlanVersions`** is `CaseVersions`' shape with a real `decide` (a suite's could never take its
  other branch), so **a rename writes no version**; and **one value serves as both snapshot and
  hash**, unlike `CaseVersions`, because nothing reachable from `PlanSnapshot` is derived. **Absent /
  null / empty are three instructions split by KIND** — a scalar `null` clears, a list `null` leaves
  alone and `[]` clears (§68's `normalizeClassification` rule, now stated generally) — which is what
  keeps `TestPlan.create`'s *nobody has decided* apart from *deliberately none*. `PlanCriteria` owns
  the closed `METRICS` set and `isKnownOperator`, the latter **calling** `satisfies` rather than
  listing the operators twice. ~~A CLOSED plan refuses the edit (409), placed after the permission,
  before the `If-Match`, before validation (§72's order).~~ → **gone with the state in §97**: no plan
  is frozen against editing, and what protects a sign-off is §6g's edit-after-approval demotion.
  This endpoint has **one** 409 left, the stale `If-Match`. `#confirmed`
- **The REVIEW RECORD lives in `project`, and the placement is the design (§76).** `ReviewService` +
  `Review`/`ReviewParticipation` + `ReviewController` + `GET /projects/{id}/reviews?subjectType&subjectId`
  + `POST …/reviews/{id}/participations`. **A review's subject is an opaque triple** (`subject_type`,
  `subject_id`, `subject_version_no`, no FK), which is what lets ONE record serve cases, suites and
  plans — and three writers in TWO modules must reach it (`SuiteStatusService`, `CaseBulkService`,
  `PlanStatusService`), so it can only live where both may call. A `review` module is **refused**:
  `ArchitectureRules.FOUNDATION_MODULES` forbids naming anything but tenancy/identity/project in an
  `allowedDependencies`, so it would need the product's first `CompanyScopedEvent` (still zero
  implementations) with the event type in `tenancy`. **`ReviewService.moved` takes a
  `ReviewStanding`** (OUTSIDE_REVIEW · IN_REVIEW · APPROVED), never a status — each ladder maps its
  own enum (`LifecycleLadder.standingOf`, `PlanLifecycle.standingOf`) — and deliberately **not** where
  the artifact came FROM: the open review row is that fact and is the better one. Called INSIDE each
  ladder's transaction, so the status and the record commit together. **`V22`: a fourth state
  `WITHDRAWN`**, because the shipped ladders put `IN_REVIEW → DRAFT` on the EDIT key, so the submitter
  taking their own submission back walks the same edge as a colleague sending it back — derived from
  `actorId.equals(createdBy)`, never sent — plus a **partial** unique index (one OPEN review per
  subject; closed rows are the history). **The participation endpoint cannot write an `APPROVAL` row**:
  `APPROVED`/`REJECTED` are field errors, or the advisory key becomes a second door to the sign-off.
  Gate via `ProjectService.writeGrantedOnTarget` (§69's seam, **5th** consumer) on
  `ReviewSubjectType.reviewPermission()`, resolving through `findInProject` — `findById` is
  RLS-scoped to the COMPANY and accepts a sibling project's review. `#confirmed`
- **`review_comment` has a writer since §79, and `case:comment` its first consumer — asked for ALL
  THREE subject types.** `ReviewComment` + `ReviewCommentEdit` + `ReviewCommentRepository` +
  `ReviewCommentView`/`Request`/`ResolutionRequest`, `ReviewService.comment`/`resolveComment`,
  `POST …/reviews/{id}/comments` and `PUT …/comments/{id}/resolution`, **`V24`**.
  **The catalogue has ONE comment key where it has three review keys**, so
  `ReviewSubjectType.COMMENT_PERMISSION` is a static field rather than a third column on the
  constants — the asymmetry is the finding. Adding `suite:comment`/`plan:comment` was refused: a new
  key is seeded to nobody, so it is a 403 no administrator can grant their way out of (§67's
  `evidence:read` argument). The roles spec's Review table already calls it a tier — *"read an ITEM
  in full and leave threaded comments"*. `SeedDataTest.everyReviewerIsAlsoACommenter` **derives** the
  ladder that makes it sound (`everyApproverIsAlsoAnEditor`'s shape); when it fails, grant
  `case:comment`, do not delete the check.
  **RESOLVING costs `*:review`, not `case:comment`** — a statement about the review's progress
  belongs to the tier running it; *"the reviewing tier OR the comment's author"* (`evidence:delete`'s
  shape) was refused because its only new caller is a Viewer resolving their own feedback. The two
  gate mutations are a **pair**: each reddens exactly 1, and they are different tests.
  **Already-in-the-requested-state writes NOTHING** — the unconditional version re-stamps
  `resolved_at` and hands `resolved_by` to whoever clicked last, which was a live defect in this
  slice's first draft and is the only test of sixteen that failed on the first run.
  **Threads are ONE level deep**, the parent resolves through `ReviewCommentRepository.findInReview`
  (never `findById`, RLS-scoped to the company — §64/§68/§69's family), and both parent refusals
  carry the same sentence. `ReviewCommentView` is nested, so assembly lives on the server (§68's
  `buildValueTree`) and an orphaned reply surfaces as a root.
  **`anchor_type`/`anchor_id` stay NULL and unmapped**: validating a step id needs
  `repository.test_step`, which this module may not import, so it is a `PeopleDirectory`-shaped port
  or §64's unvalidated reference. **`refuseIfClosed` is the ONE spelling of "this review has
  ended"**, read by all FIVE write paths since §80 — the verdict endpoint's inline copy moved into it.

- **The reviewer's INBOX is a board generator, and its placement is the §81 finding.**
  `BoardFacts.pendingReviews` + `BoardService.cardFor(PendingReview…)` + `PREFIX_REVIEW`, in
  **`work`** — §6l's third derived-item generator, absent since §53 with the reason in the package
  comment. **No other module may write the query**: it names `review` + `review_assignment`
  (`project`), `team_membership` (`identity`) and `test_case`/`suite`/`test_plan` (`repository`,
  `execution`), and `project` — which owns the record — may depend on `tenancy` alone, which is why
  `ReviewController` refuses the queue in its own header. So §80's three options (fourth port / read
  model elsewhere / the domain event §76 refused) collapse to the module that already concentrates
  every foreign-table statement in one file. **A queue endpoint on `ReviewController` would have had
  to invent one of the three and would then serve a second "what is on my plate" list.**
  **A team is expanded at READ time**, the mirror of §80's write-time refusal and for its stated
  reason (*membership moves*): recomputed every load, so joining the team tomorrow finds the request
  waiting. `UNION` not `UNION ALL` (asked by name **and** through a team is asked once), and **one
  card per REVIEWER not per review** — `mine=true` filters by assignee, so a shared card would appear
  on nobody's board. **Three predicates, each removing a card that would never leave**: `state =
  'IN_REVIEW'` (unassigning is the only delete, so every request ever made is still in the table), no
  participation row by that person (the card leaves when they have **spoken**, not when they agreed),
  and `COALESCE(c.id, s.id, p.id) IS NOT NULL` — whose broken form yields a card with **no key and no
  title**, invisible to any `doesNotContain` assertion, hence the exact-set test.
  **`sourceType`/`sourceId` name the ARTIFACT, not the review**: those fields are *what the card
  opens*, and a review has no URL — §6g's panel renders on the artifact's page. The card id is
  `review:{reviewId}:{userId}`, the one derived id that is not prefix-plus-one-UUID, so `isDerived`'s
  prefix check is the **only** guard on it. **No contract change** — `kind: REVIEW` has been in
  `openapi.yaml` since §53, ahead of its generator, deliberately. `#confirmed`
- **The board is CROSS-PROJECT since §83, and `GET /work-items` with no `projectId` is the personal
  one.** `ProjectService.readAllVisible(userId, Function<List<Project>,T>)` + private
  `visibleProjects` (shared with `listVisible`), `WorkItemRepository.findLiveInProjects`, all three
  `BoardFacts` generators taking `Collection<UUID> projectIds`, `BoardService.personalBoard` +
  shared `present`. **No migration, no new table, no new permission key** — the gate is project
  visibility, which the caller already had.
  **`readAllVisible` is `readVisible`'s cross-project sibling and the product's first read that is
  not about *a* project**: no `Optional`, so no 404 — the caller named nothing that could fail to
  resolve, and a member with no grants gets `[]`. The read takes the **whole list** rather than being
  applied per project, which is what lets the board answer in one statement per source instead of one
  per project.
  **The empty collection is the ordinary path, not an edge**: `IN ()` is a syntax error rather than a
  predicate matching nothing, so `BoardService.assemble` returns before any query — the same guard
  `ProjectService.listVisible` states at its grouped query, and a member holding no grants is exactly
  who opens a personal board first. Every generator takes a **set** and there is deliberately **no
  single-project overload** (§58's E1: the copy that drifts is whichever board does not use it).
  **Each card is attributed by the ROW's `projectId`**, through a `Map<UUID,Project>` — the four
  sources have carried their own project since §53 and only now have a second reader; a generator
  reading the request's project would label every card with whichever project came first.
  **Omitting `projectId` with `mine=false` is a 400, never a silent narrowing** — §6l's cross-project
  board is the *personal* one, and forcing `mine` on would answer a request nobody made with a list
  the client cannot tell apart from a company holding only its own work.
  **Live trap for the next reader: the ANSWER is person-sized, the READ is not.** `present` filters
  after `assemble`, so a caller visible on every project loads that company's whole live work set —
  tasks, outstanding executions, flagged cases, open review requests, plus the people resolution —
  and keeps their own. §6l's *"cap derived generation with sensible limits"* therefore still has
  somewhere to land, on the generators rather than on the response. Not fixed in §83: pushing the
  filter down is what `present` refuses by name, and it would change the project board too.
  `#confirmed`
- **The board is §6l's THIRD one since §84, and `GET /work-items?teamId=…` is a third SELECTOR on
  one operation.** `BoardFacts.teamMembers`, `BoardService.teamBoard` + `forAssignees` +
  `present(cards, Set<UUID>)`, `WorkItemController.twoBoardsProblem`. **No migration (`V27` still
  free), no table, no key** — derived for the seventh time, and there is nothing for a key to protect
  since every card is one the caller could already see on a project board. §6l's three boards differ
  in *which cards are kept* and in nothing else, which is why a `/teams/{id}/work-items` route was
  refused.
  **THE decision: the bound is the CALLER's project visibility, never the team's.** Projects come
  from `readAllVisible` exactly as the personal board's do and the team contributes **only a set of
  assignees** — so *two people asking for the same team's board receive different boards*, stated in
  the contract as the design. *"Cards assigned to members of team T"* is one query, reads correctly,
  and renders another client's case key, title and project name to a reader who need not be on the
  team, need hold no grant, and need only guess a UUID. **§83's fixture cannot prove this** (its
  assignee is always the caller, so both bounds agree on every row); §84 adds `TEAMMATE`, granted
  where LEAD is not, and asserts a **pair** — LEAD's view is exactly `{TC-1,TC-10}`, TEAMMATE's view
  of the *same team* exactly `{TC-9,TC-11}` — the second half being what stops the first from passing
  because a fixture row silently failed to insert.
  **The expansion is at READ time and `TeamDirectory` was NOT widened.** §81's precedent, §80's
  reason (membership moves). The port's header forbids it answering *who may*; this is not that
  question, because visibility has already bounded the board before the set is applied. Its **`find`
  gets a second consumer** for existence — which is what makes an unknown team and another company's
  team **one 404** instead of a company-wide team-existence oracle (§6t). **Direct membership only,
  no hierarchy**, matching `pendingReviews`' flat join.
  **Four answers, two of them deliberately indistinguishable**: unknown team 404 · unreachable
  project 404 · no members `200 []` · members with no visible work `200 []`. **Their ordering is a
  trap with a test**: the empty-team shortcut lives INSIDE `forAssignees` (after the project gate),
  because hoisting it turns "empty team + unreachable project" into a 200 confirming the project
  exists. **`teamId`+`mine` is a 400** and **`teamId`+`projectId` is legal** (two axes, who and
  where — §6l's swimlanes-by-member). `present` filters over a **set** with the personal board as the
  one-element case, still **after** `assemble`, so §83's cost note is inherited unchanged.
  **`work_item.assignee_id` REFERENCES `app_user`** — §6l's team assignee for the unclaimed pool does
  not exist and is named not-built. **Live trap: the two boards are not protected by the same line** —
  dropping the grant filter from `ProjectService.visibleProjects` leaves every `Gates` test green,
  because a *named* project goes through `visibleProject`'s own copy of it. `#confirmed`
- **`review_assignment` is NEW in §80 (`V25`) — the first review table not already in `V1`.** That
  absence is itself the finding: `V1` designed the record of what happened and never the worklist of
  what is outstanding. `ReviewAssignment` + `ReviewAssigneeEdit` + `ReviewAssignmentRepository` +
  `ReviewAssigneeView`/`ReviewAssignmentRequest`, `ReviewService.assign`/`unassign`/`store`,
  `POST …/reviews/{id}/assignees` and `DELETE …/assignees/{assignmentId}`.
  **A worklist entry, NOT part of the audit record** — that is `review` + `review_participation` —
  and the three consequences ARE the design: unassigning is a **hard delete** (no tombstone; the
  opposite call from `ReviewComment`, for the opposite reason — a remark is something somebody said,
  a request is something somebody was asked), a deleted **team CASCADES** (§71's 409 deliberately NOT
  extended: what is lost is a request, not authority, and refusing would make a team undeletable
  until every review it was asked to look at had closed), and **re-asking writes nothing** (§79's
  re-stamp defect in the one place it recurs; the partial unique indexes are the backstop, the
  `findForUser`/`findForTeam` read is the check).
  Gate is `*:review` via `writeGrantedOnTarget` (§69's seam, **6th** consumer) — **no new key
  invented**, because one seeded to nobody is a 403 nobody can grant out of (§67's `evidence:read`,
  §79's `suite:comment`); `case:comment` would let a Viewer fill somebody
  else's queue and `*:approve` would confine the request to the tier it exists to prepare.
  **The assignee is validated on `ProjectAccess.canView`, NOT company membership** — §71's bar one
  table over and the wrong one here, because a colleague with no grant cannot open the artifact.
  `ReviewService` holds `ProjectAccess` directly for this, and only this: it asks about the
  **assignee**, never the caller. Both refusals carry one sentence (§6t). **The submitter is refused,
  derived from `standingLgtms` already skipping their own LGTM** rather than invented.
  Uniqueness is **two partial indexes**, because a plain four-column UNIQUE enforces nothing —
  every team row has a NULL user and NULL ≠ NULL. Policy count **57 → 58**;
  `ix_review_assignment_assignee_user` is added **before its query**, for §6l's queue. `#confirmed`
- **§6g's GATING RULE is the last part of the review workflow, it lives in `execution` (§82), and
  since §97 there is ONE of it.** `V26` added `require_approved_case_for_run` /
  ~~`require_approved_plan_to_start`~~ to `project_settings`; `V32` **drops the second column**, so
  `ReviewSettings` has one gating component and the one reader is `RunCreateService.gate`.
  ~~The plan rule binds on ONE EDGE, `DRAFT → ACTIVE` (`PlanLifecycle.startsWithoutApproval`), and
  the target-keyed reading is a live trap: refusing a reopen makes every closed plan on a gated
  project permanently un-reopenable.~~ → **moot rather than wrong since M7 removed the edge**, and
  kept here because it cost a slice to find: *a rule about a transition binds on the edge, not on
  the target*, and the naive reading passes every obvious test. **The case rule binds on the
  RESOLVED set** (`edit.caseIds()`, after the plan's
  scope is expanded) or naming a plan is a bypass costing no permission and leaving no trace, and
  **the whole run is refused, never trimmed** (this class's own partial-materialisation argument).
  **Neither is ANDed with `reviewEnabledFor`** — §77's "off is narrow" applies: gating reads the
  status ladder, which a project with review off still has, so it is its own opt-in and turning
  review off does not turn gating off. **The override invents NO KEY**: `case:approve`/`plan:approve`
  are *derived*, since their holder can reach the same state by approving first — so it widens
  nobody's reach and only changes the record (`approvalGateOverridden` on the audit row, written
  **only when the gate fired**). Explicit flag on the request, consulted only on the refusal path;
  a caller without the key gets the **same 409, not a 403**. `ApprovalGating.Verdict` shares the
  vocabulary and deliberately not the rule. **`CaseFacts.unapprovedCases` is the 11th crossing** and
  uses `<> 'APPROVED'`, not an enumeration — with **no test covering `DEPRECATED`**, which the
  mutation queue records. `#confirmed`
- **`ProjectService.holds` is the ONE "does this caller hold key K here" question (§82)** — not a
  gate, for the callers that ask a *second* permission after their own gate has passed, to shape a
  response rather than to authorise it. Three call sites: `CaseBulkService` (which refusal sentence)
  and the two §82 gates (may this request override). It was §75's private helper in
  `CaseBulkService`; §82 moved it up rather than let it become three byte-identical copies — §58's
  E1, prevented instead of mourned as it was for `PeopleDirectory` in §76. `false` for an
  unreachable project as well as a held-nothing caller, which is correct at all three. `#confirmed`
- **`TeamDirectory` is the THIRD port in `project` (§80)**, after `ProjectAccess` and
  `PeopleDirectory`, implemented by `identity.IdentityTeamDirectory`. Not a method on
  `PeopleDirectory`: that port's one rule is §6u's company-membership naming, for which a team has no
  analogue, so one interface would hold two unrelated resolution rules. **Two methods on purpose** —
  `resolve` tolerates a missing team (a render does), `find` is what a WRITE calls, because a write
  must refuse one or the product stores an unchecked `team_id` (§64), and "ask `resolve` with one id
  and check the map" is how a validation stops looking like one. It must never answer **membership**:
  §6g's "any member can action it" is `PermissionEvaluator`'s question (§71), and a team is
  deliberately not expanded into per-member rows at assignment time, because membership moves.
  **`find` has a SECOND consumer since §84** — `work.BoardService.teamBoard`, for existence, which is
  what makes an unknown team and another company's team one 404 rather than an existence oracle. The
  membership rule held under pressure: §84's team board expands in `BoardFacts.teamMembers`, not in a
  third method here.
  `#confirmed`
- **`project.PeopleDirectory` is the ONE resolution of "what is this person called" (§76).** Port in
  `project` (which may depend on `tenancy` only — its package comment prescribes exactly this),
  implemented by `identity.IdentityPeopleDirectory`; `ProjectAccess` is the first instance of that
  inversion. **`repository.CaseOwners` and `execution.ExecutionPeople` were byte-identical copies** of
  plan §6u's rule under a comment saying they must not disagree (§58 E1) and now delegate — what is
  left in each is the mapping onto its own module's `UserRefView`. The two null-key tests
  (`CaseOwnersNullKeyTest`, `PeopleResolutionNullKeyTest`) now pass a stub returning `Map.of()`, the
  worst case, and assert the wrapper still answers null-tolerantly (§38's 500). `#confirmed`
- **§6g's authoring ladder is ONE class since §75 — `repository.LifecycleLadder`** — read by
  `SuiteStatusService` *and* `CaseBulkService`, because `LifecycleStatus` is the status of a suite
  **or** a case (`V1` spells the identical four-value constraint on both tables) and §6g draws one
  diagram. Pure static: `decide(current, requested, noun)` → sealed `Decision`,
  `permissionFor(current, requested, Keys)` where `Keys` is a record so two adjacent key strings
  cannot be swapped unnoticed, and a written-out `rank`. **The plan's ladder is deliberately NOT
  folded in** — since §97 the two differ the other way round: `PlanStatus` has three values and
  this one has `DEPRECATED`, which a plan does not. Table:
  `IN_REVIEW` only from `DRAFT`; `APPROVED` only from `IN_REVIEW`; `DRAFT` and `DEPRECATED` from
  anywhere (a folder that cannot be deleted must still be retirable, or ordinary use reaches a dead
  end); a `DEPRECATED` artifact returns only via `DRAFT`. **`permissionFor` is decided by the EDGE**:
  the approve key covers granting the sign-off, retiring, and *taking either back*. `#confirmed`
- **`suite.lifecycle_status` has a writer since §75, and `suite:approve` its first Java consumer.**
  `SuiteStatusService` + `SuiteStatusRequest` + `Suite.applyStatus` + `PUT /suites/{suiteId}/status`,
  gated through `ProjectService.writeGrantedOnTarget` (§69's seam, 4th consumer) on
  `Keys("suite:manage", "suite:approve")` — and that pair **discriminates**, unlike §71's, because
  `V3` withholds approve from Test Engineer while granting manage. No `If-Match` (absolute target),
  parse before the gate with an unreadable status falling back to the LOWER key, already-there is a
  200 writing nothing, and **a status change writes no `suite_version` row and no bump** (a suite
  version is composition — §73's identical call on the plan). Six distinct audit verbs, because
  `DRAFT` is reached by three different acts. `SuiteWriteService`'s private `view` moved to
  **`SuiteService.viewOf`** so this service reuses it instead of becoming `CaseRenderer`'s fourth
  copy. `#confirmed`
- **`BulkOperations.permission` is the FLOOR, not the rule — the per-row edge check lives in
  `CaseBulkService.evaluateStatus`.** Until §75 the case gate was keyed on the *target*, so
  `case:approve` was asked only for a move INTO `APPROVED` and a Test Engineer could bulk-move two
  hundred approved cases to `DRAFT`, rescinding a lead's sign-off. `PlanLifecycle`'s §73 header named
  this class as the weaker shape and did not come back for it. Per-row rather than a higher floor,
  because one end of every edge is the row's stored status and a filtered selection mixes them;
  refused rows are `BulkVerdict.Skipped` with `leadOnly(...)`, never a 403 over the whole request.
  `holdsEdit` generalised to `holds(userId, projectId, key)`. **Three case transitions changed**:
  `APPROVED → IN_REVIEW` no longer exists, `DEPRECATED` returns only to `DRAFT`, and deprecating now
  costs `case:approve`. The **`OWN`-qualified deprecate** (an author retiring their own draft, via
  `AuthorshipGate`'s term) is named and not built. `#confirmed`
- **`lock_version` is mapped WITHOUT `@Version` on `TestRun` and `work.WorkItem`, and that is a live
  trap for the next writer.** §74 found it on `TestPlan`, where the column had read `0` on every plan
  since M2 slice 2 while the contract declared `lockVersion` *required* — so an `If-Match` check
  against it **could not fail**, there being no such thing as a stale 0. Ten other entities have the
  annotation. The two remaining are **latent** (neither serves `lockVersion`, nothing sends an
  `If-Match` for either) and the run one is **not a free repair**: `TestRun.begin` touches the run on
  every result recorded, so adding it would make two testers working one run collide on the product's
  most-used write. Sweep with: for each file mapping `lock_version`, check the 3 lines above it.
  `#confirmed`
- **The frozen-run rule is ONE rule over THREE writes** — result, evidence attach, evidence delete —
  which is exactly the condition §67 stated when it declined to implement `V1`'s "immutable once the
  run closes". Placement carries the reasoning and a mock or a sibling must match it: **after** the
  permission (a Viewer on a closed run still gets 403, not 409), **before** the `If-Match` (a stale
  header is fixable and this is not), and **before** authorship on the delete (a closed run refuses
  everybody including the author, so "this is not yours" would name a fix that does not exist).
  `Outcome.RunClosed` on both sealed interfaces made three switch arms a compile error. `#confirmed`
- **`ExecutionTargets` is the ONE resolution of the run/execution path** (§72), returning
  `(run, execution)`. It replaced byte-identical private `resolve` copies in `ExecutionService` and
  `EvidenceService` — a hand-maintained pair under a javadoc saying they were the same, which became
  a defect the moment both needed the run. Deliberately **not `@Transactional`**: `EvidenceService.open`
  calls it from a method that must not be in one, because it returns an open stream the controller
  writes to the response afterwards. `#confirmed`
- **`evidence:delete` is the second `OWN`-qualified consumer**, after `CaseDeleteService`, and the
  same shape: authorship applied AFTER the lookup, two 403s with different sentences, and the
  null-author arm refusing. Delete is **hard**, unlike a case's soft delete — a screenshot removed
  on request must actually leave the disk. `#confirmed`
- **`CaseRenderer` is the ONE assembler of a case's shape and now every path uses it.** It was
  extracted from the two write paths in §62; §68 found the **read** path
  (`CaseDetailService.detail`) was a fourth byte-identical copy that had never been moved onto it —
  invisible while the copies agreed, visible the moment a field was added to the view. A fifth
  writer delegates, never copies. `#confirmed`
- **The classification VOCABULARY has a writer since §69** — `ClassificationValueWriteService` +
  `ClassificationValueEdit` (`project`), and `V20`'s partial per-parent unique index behind it.
  **The gate is decided by the dimension row, in KEY and in SCOPE**: `project_id IS NULL` ⇒
  `company:vocabulary` at COMPANY scope, otherwise `project:settings` on the project. Hence
  `ProjectService.RequiredPermission` (key + scope) and **`ProjectService.writeGrantedOnTarget`**,
  the third write gate on that class — it resolves the target BEFORE asking any permission, because
  the permission to ask is a property of the target. The dimension resolves through
  `findForProject`, **never `findById`** (RLS scopes the latter to the company, not the project, so
  it accepts a sibling project's Feature dimension and every cross-company test still passes).
  `locked` is **not** read-only — it says whose the dimension is; three documents disagreed and the
  view's javadoc was the outlier. `ClassificationValue.create` derives the `ltree` label from the
  **id** (`v` + uuid hex), like `Suite.create` and for one more reason: `tcms_ltree_label` answers
  the empty string for a name with no ASCII alphanumerics, which an `ltree` refuses. `#confirmed`
- **The org chart has a writer since §71** — `TeamWriteService` + `TeamEdit` + `TeamController`
  (which also took `GET /teams` off `AdminController`, so one path family lives in one class), and
  `V21` behind it. **`company:teams` at COMPANY scope**, via a new `AdminReach.manageTeams()` term
  read off the same already-resolved company-wide `PermissionSet` — a separate term from
  `companyWide`, deliberately, though the seeded catalogue grants both to the same roles.
  **A team member must be a COMPANY member**: `team_membership.user_id` references instance-level
  `app_user` while `company_id` comes from the session, so a sibling company's account satisfies the
  FK, the company column and RLS — and the evaluator's five projections resolve teams through
  `team_membership` ALONE, never joining `company_membership`, while `AdminDirectoryService.refs`
  drops an id it cannot place. Unchecked, that is an **invisible grant**; hence a 404 and a test that
  asserts *no row was written*. `INVITED` counts, `REMOVED` does not.
  **Delete is refused (409 + counts) while grants or child teams exist** — `role_assignment.team_id`
  is `ON DELETE CASCADE`, so the delete would revoke silently; members do NOT block it.
  A team **may be reparented**, unlike a classification value, because the parent edge is org-chart
  structure and never a permission edge (§6k) — `TeamEdit.descends` walks upward with a visited set
  so a pre-existing cycle refuses rather than hangs. V2's `tcms_enforce_team_hierarchy` stays the
  backstop, never the answer. `#confirmed`
- **`SeedDataTest.teamManagementAndMemberManagementAreStillHeldTogether` records a COINCIDENCE, not
  an invariant.** `V3` grants `company:teams` and `company:members` to the same roles, so no persona
  the suite can build can tell the org-chart gate's key from its neighbour's — a mutation swapping
  them reddens nothing. When that check FAILS it is the good case: add the persona to
  `TeamEndpointTest.Gate`, do not re-align the seed. The frontend's `TeamsPage.test.tsx` carries the
  only test that can discriminate today, because it can mock a permission set the seed cannot
  produce. `#confirmed`
- **Classification writes** — `case_classification` got its first writer in §68.
  `ClassificationVocabulary` (built from the same `CaseRenderer#vocabularies` read the response
  needs, so validating costs no query) + `CaseClassifications` (the reconciler, `CaseSteps`'
  sibling). **The reconcile is BOUNDED by the vocabulary the caller was served**: a retired value's
  assignment is never returned by `GET` and is therefore never removed by a save that omits it,
  because a client cannot resubmit what it was never shown. Consequence, stated: a retired
  assignment is immutable through the API. `liveValueIdsOf` is the ONE implementation of "drop what
  the project no longer offers" and both the renderer and the validator's fallback read it — two
  copies would let the served ids differ from the preserved ids. **Delete is flushed BEFORE insert**
  or replacing a SINGLE axis's value hits V2's `23505` (Hibernate orders inserts first within a
  flush). `#confirmed`
- **`CaseEdit`'s `classificationValueIds` is SORTED and de-duplicated**, and that is a `CaseVersions#hash`
  requirement: the hash's determinism argument rests on there being no *set* in the record's shape.
  `CaseEdit.of` takes the case's stored ids so a bulk action's version row does not hash as though
  the case were unclassified. **`CaseUpdateRequest`'s refused six is now a refused FIVE.** `#confirmed`
- ~~**`ContractConventionTest.requiredPropertiesHaveAComponent`** resolves a schema's `required`
  through `allOf` and checks the serving record has a component for each … `RESPONSE_RECORDS` is a
  short explicit map.~~ → **RENAMED AND WIDENED BY §165 — the rule is
  `pairedSchemasAndRecordsNameTheSameMembers`, compares ALL properties in BOTH directions, and the
  map is 79 pairings rather than "a short explicit map". See the §165 section at the top.** What
  stands unchanged is why it exists: added in §68 after `TestCaseDetailView` was found to have
  omitted `createdBy` since §66, which made the case detail's `OWN`-qualified Delete refuse every
  caller their own case. That history is now in the new rule's failure message. `#confirmed`
- **Operator CLI**: `ApplicationRunner` beans that are inert unless their verb appears in the
  non-option args — `ProvisionCompanyCommand`, `IssueInvitationCommand`. The verb list lives in
  `OperatorCommands`, which `TcmsApplication.main` asks *before the context exists* to pick
  `WebApplicationType.NONE`; a CLI run under a servlet container succeeds and then never exits.
  Commands parse and report only — behaviour lives in a service. `#confirmed`
- **Invitations**: tokens are stored as `hex(sha256(secret))` only — `InvitationTokens`. A link that
  was never delivered is unrecoverable by anyone. `InvitationService.issueIfPending` deliberately
  leaves a live invitation alone (so a `provision-company` re-run cannot invalidate a link already
  sent); `InvitationService.reissue` deliberately replaces it (the operator explicitly asked).
  `#confirmed`
- **Exception handling / API style**: `@RestControllerAdvice` → RFC 7807 ProblemDetail. Unreachable
  resources answer **404, never 403**, identically, so a response cannot be used as an oracle
  (plan §6t).
- **Async**: `CompanyContextTaskDecorator` clears the context on every task so a handler that forgets
  fails closed; `CompanyScopedEvent` is the only thing a handler may re-establish from.

## Conventions observed

- Constructor injection, `final` fields, records for DTOs and results, `Optional` in return
  positions on service APIs.
- **Comments carry the *why*, and often the failure mode a rule prevents** — unusually dense, and
  load-bearing. Match that register; a new file with thin comments reads as foreign here.
- Test `@DisplayName`s are full sentences describing the *stake*, not the mechanic. Test classes own
  a **slug namespace** (`inv-`, `iss-`) and provision their own companies — the Testcontainers
  database is shared across the whole run.
- `System.out` for CLI output, the logger for everything else. **Never log a secret**, including
  invitation URLs.

## Known unknowns

- `src/test/` is the `qa-api-java` peer's domain; this agent only adds tests alongside code it wrote.
- Schema/DDL design (`docs/specs/*-erd.md`, RLS policy shape) is `data-architect`'s.
- **CI runs `./gradlew build -x test` since 2026-08-02 — no backend tests run on push.** `./gradlew build` locally is the only test gate; nothing downstream repeats it.
- ~~Two tests are `@Disabled`~~ **→ fixed and re-enabled 2026-08-02 (§60). No test is `@Disabled`;
  the suite runs 1078 with 0 skipped**, and the `skipped` count is the cheapest way to check that
  claim still holds. `#confirmed`
- **`/reports` is the only dead contract left** (§64 built the step library, §62 landed
  `cases/bulk`). It is **M2.5's**, not a slice's — `ExecutionFact` read model, `ReportModel`,
  Thymeleaf + headless-Chromium renderers, an async job pipeline, 7–8 weeks. Checked in §64 and
  deliberately not started. `#confirmed`
  **Decomposed 2026-08-04 into `docs/plans/2026-08-04-m2.5-reporting-slice-plan.md`** (10 slices,
  one migration). **R1+R2 landed in §86, R3 in §87, R4 in §88; R5–R8, F1 and F2 remain, and `/reports` (the saved-definition GET) is still a dead
  contract** — nothing in §86 is reached through an endpoint. Seven facts that pass verified against
  the code and that a reader should not re-derive (1, 2 and 6 have moved since; struck inline):
  1. ~~**There is no `com.tcms.reporting` package.**~~ → **created in §86** (see the seam above);
     ~~ten~~ **eleven** modules now exist and `ModuleStructureTest` hard-codes them. What is now
     true instead: **`shared` is the last of plan §3's modules that does not exist**, and decision E
     declined to create it for two storage ports and an enum — ~~which is still the answer~~ →
     **§125 built `storage` instead** and struck §3's `shared — storage, audit, attachments, search`
     down to `audit, search`. The two ports have a module; `shared` still does not, and now needs a
     reason of its own rather than inheriting theirs.
  2. **`suite_run_summary`, `plan_snapshot`, `report_definition` and `report_artifact` are in `V1`,
     RLS-forced, and have ZERO code** — no entity, no query, no writer. ~~`execution_fact` too~~ →
     **it got its first writer in §86**; ~~the other four are untouched~~ → **`report_artifact` got
     one in §87 and `report_definition` a full CRUD surface in §92**, so what is left with zero code
     is **`suite_run_summary` and `plan_snapshot`**. `case_health` is now written
     by **two** modules: `repository` owns `never_executed`/`long_unexecuted` (§53) and `reporting`
     owns `failing_repeatedly` (§86) — **3 of 9 signals**.
  3. **There is NO `report_job` table** — not in `V1`, not in the ERD. `report_artifact.storage_key`
     is `NOT NULL`, so an artifact row cannot precede its bytes and cannot serve as the job record.
     The 202-and-poll contract therefore has no home in the schema; that is the plan's only migration.
  4. **`ReportJob.downloadUrl` is required-and-nullable with no server, and there is no poll route
     and no artifact-content route in `openapi.yaml`.** §67's `Evidence.url` finding, one module over:
     the generate slice must land three operations or invent a URL.
  5. **`report:view` / `report:export` are seeded by `V3` to five of six built-in roles** — all but
     Automation Service — and have **zero Java consumers**. First-consumer pattern, 8th instance.
     **But the gate cannot be shown to discriminate**: there is no API-token authentication
     (`com.tcms.auth` never names `api_token`; that is M3), so no persona a test can build lacks
     either key and a gate mutation reddens nothing. §71's `company:teams` coincidence in a worse
     form. The way out is a custom `role` row — that table is instance-level with a global unique
     name, so a fixture may insert one.
  6. **`execution_fact.status_kind` is `NOT NULL` while `test_execution.status_id` is nullable**, so
     the fact log can structurally hold only RECORDED executions. Coverage denominators can never
     come from it — the §46 finding, arriving from the schema. **§86 made this the inner join to
     `case_status`** rather than a separate predicate, and R6 must be written around it.
     **Related, found in §86 and not built**: `execution_fact.executed_by` references `app_user`
     only, while `test_execution` also has `executed_by_token`. Harmless while token auth is M3 and
     nothing writes it — and the day it lands, every ingested result carries a null actor and §6e's
     per-tester throughput section silently excludes CI.
  7. **Naming collision, live**: `execution.PlanSnapshot` is §74's plan-version value object and has
     nothing to do with the `plan_snapshot` reporting TABLE, which has no code. A writer for the
     table must not reuse the name.
  Also: `case_health` has **ten** boolean columns and `HealthSignal` **nine** members — `incomplete`
  has no enum member and therefore no reader (unchanged by §86, which built `failing_repeatedly`).
  And `V1` ships **four** partial indexes on `case_health` (`outdated_spec_changed`,
  `stale_approval`, `never_executed`, `unlinked`) — **`failing_repeatedly` is the first signal with a
  writer and no index**, found in §86 and deliberately not fixed there, since an index is a
  migration and that slice has none. `doc_link` has no entity and no writer, which blocks
  the coverage matrix and doc-structured reports. `frontend/src/mocks/handlers/reporting.ts` answers
  `generate` with `id`/`requestedAt`/`status: 'QUEUED'` — three fields the contract does not declare,
  the last not in its enum — with no consumer. `#confirmed`
- **`ConditionRepository.findLibrary` orders by a column nothing maintains.** `usage_count` reads 0
  on every condition row, so its `ORDER BY usageCount DESC` has never ranked anything. §64 solved
  the same problem for shared steps by deriving the count; conditions were left alone because their
  usage spans `case_condition` AND `suite_condition` with inheritance on the suite side, which is a
  design question rather than a join. `#confirmed`

- ~~**`test_plan_version` is the only one of §6j's four versioned aggregates with no writer**, and
  `entry_criteria`/`exit_criteria` have no writer at all~~ → **all three closed in §74** (see the
  plan-editor seam above). What is now true instead: **`test_plan_version` has a writer and no
  reader** — nothing serves a plan's scope history, so the rows accumulate unread, which is the state
  `suite_version` was in for a long time. A plan timeline is the natural consumer and it is §6p's.
  `#confirmed`

- ~~**Four review-workflow permission keys still have no consumer in Java**~~ → **three got one in
  §76** (`case:review`, `suite:review`, `plan:review`) **and the fourth, `case:comment`, in §79** —
  the review block of the catalogue is fully consumed. ~~**Reviewer ASSIGNMENT is the only unbuilt
  half of §6g's apparatus**~~ → **built in §80**, and it needed **no new key** — one seeded to
  nobody is a 403 nobody can grant out of (§67/§79) — so asking costs the subject's `*:review`,
  §79's argument for resolving a thread: a statement about the review's progress. ~~What is now true
  instead: **§6g's apparatus is complete and has no INBOX.**~~ → **the inbox landed in §81**, as a
  **board generator** rather than as anything in `project` (see the seam above), which is the second
  of the three options and the only one that needed nothing new built; `ix_review_assignment_assignee_user`
  was added one slice ahead for exactly this query, so `V26` is still free. What is now true instead:
  ~~**the last unbuilt half of §6g is the GATING RULES**~~ → **built in §82** (see the seam above);
  **§6g is now COMPLETE — every part of the workflow has an implementation.** Also still unbuilt and
  deliberately so: **the board's other two generators**
  (`DOC_UPDATE` needs §6i's stale-case flagging, which nothing computes; `DEFECT` needs the local
  defect record, which nothing writes — a feature apiece, not a generator apiece); ~~**§6l's
  cross-project "My Work" board**, so the inbox is **per project**~~ → **built in §83** (see the seam
  above): the inbox is now **per project OR personal across every project the caller can see**, and
  the endpoint did not move. ~~**§6l's TEAM board — the only one of its three with no endpoint**~~ →
  **built in §84** (see the seam above), which **completes §6l's three boards**: one operation, three
  selectors, and the endpoint did not move again. **§6l's per-project generator toggles**; **WIP
  limits and configurable columns**; **§6l's cap on derived generation** (located three times now, on
  the generators, built zero); **a team assignee for §6l's unclaimed pool** (no such column —
  `work_item.assignee_id` references `app_user`, §84); and **notifications** — all four of §6g's
  events have a trigger and there is no channel — remain unbuilt. `#confirmed`

- **`audit_event.target_type` is spelled two ways and nothing reads it yet.** Every service writes
  the snake_case table name (`test_run`, `test_case`, `test_plan`, `doc_page`, …) except
  `ExecutionService`'s `"TEST_EXECUTION"` and `ArtifactService`'s `"ARTIFACT"`. Latent rather than
  live — `audit:view` and `project:audit` both have **zero consumers** — and found-not-fixed in §72,
  because changing it splits existing rows. It will present as an audit filter that silently matches
  nothing, which is §45's absent-row family. Sweep it when the audit screen is built. `#confirmed`

- **Per-project §6g policy lives in `project_settings` since §77, and `ProjectSettingsService.effective`
  is the ONE read.** `ProjectSettings` (entity, PK = `project_id`) + `ReviewSettings` (the value that
  crosses module boundaries) + `EditAfterApproval` + `ProjectSettingsService`/`Controller`/`View`/
  `Request`, **`V23`**. Four things had nowhere to live and all four were live defects: the opt-in
  (so §76's apparatus applied to every project unconditionally), `review.required_peer_reviews`
  (mapped, written from a column default, read by nothing), author-cannot-approve-own-work (§6g's
  *"single most valuable setting"*, absent), and the edit-after-approval mode (`ReviewPanel` rendered
  the Retain half; the Re-review half did not exist).
  **A typed table, not `project.settings JSONB`** — §74's closed-vocabulary argument, because a
  misspelled key in a blob reads identically to an unconfigured setting. The **ERD says `settings`
  belongs to `company`** and §6g says the opt-in is per project *and per artifact type*; settled with
  the user in favour of §6g, and `company.settings` is left unmapped and unclaimed.
  **`reviewEnabledFor` is a switch over `ReviewSubjectType`**, so a fourth type is a compile error.
  **"Off" is NARROW: it suppresses the reviewer apparatus and NOT the status ladder.** The wide
  reading would have removed approval from every existing project on upgrade day, since off is the
  default. For the same reason, switching review off does **not** close reviews already open — the
  guard in `ReviewService.moved` covers only the *opening* arm. `#confirmed`
- **`ReviewService.refusesEach` is the policy check, and `refuses` DELEGATES to it.** Asked by each
  ladder *before* it applies anything, returning the message to refuse with — a precondition
  discovered inside `moved` could only be enforced by throwing, and `moved` runs *after* the status
  is written. Batch-first because `CaseBulkService` evaluates up to 400 rows (`findOpenForSubjects` +
  one participations query), and because a single-item and a bulk copy of one policy is §58's E1 —
  the exact divergence §75 found between `BulkOperations.permission` and `PlanLifecycle`.
  **The peer count is read from `review.required_peer_reviews`, never the live setting** (raising the
  bar must not freeze reviews in flight), counts **each person's LATEST verdict** in Java (the trail
  is append-only so relenting is visible; `COUNT(DISTINCT user) WHERE LGTM` is the reading that makes
  it decorative), and **never counts the submitter's own LGTM** whatever the author rule says.
  `#confirmed`
- **`EditAfterApproval.RE_REVIEW`'s demotion is deliberately NOT gated on `*:approve`, and it looks
  like §75's defect reopened.** §75: leaving an approval is an approval act. This is not that, for
  three reasons that must hold together — nobody *requested* the transition (the caller asked to
  edit, which §6g permits, and the project declared in advance that editing invalidates approval);
  it is opt-in and off by default, so a `RETAIN` project keeps all of §75's protection; and it writes
  its **own audit verb** (`*.re_review_required`), never a field on the save's row. Gating it on the
  approve key would make an approved artifact **uneditable by the people §6g expects to edit it**.
  The queued mutation applying that gate is flagged as the most important row in the file: it is the
  obvious reading and it looks like a fix. Wired into `CaseUpdateService`, `SuiteWriteService`
  (rename only — the composition case is named and not built) and `PlanUpdateService`. `#confirmed`
- **The settings GET is gated on project VISIBILITY, the PUT on `project:settings`** — §69's key,
  second consumer. Not a relaxation: §6g's "a project that never turns it on should see no extra UI
  at all" is a decision every artifact screen makes for every reader, Viewer included, and none of
  them holds the write key. `effective` takes **no user and asks no permission** — every caller has
  already been through its own gate, and the value is the project's behaviour rather than something
  a caller may or may not see. `#confirmed`

## Session log

- 2026-08-15 · EXTEND · **notifications, phase 1 — the backend spine** (§218 phase 1;
  `backend/` only: `V54`, a new `integration` module, two producers in `execution` and
  `work`, three architecture-rule edits, and `IntegrationTestBase`. No contract, no
  frontend, no `packages/`). **The outbox's payload arm gets its first product callers
  since §131 built it**, and the debt three files had named — a producer-side and a
  consumer-side test pinning the payload — is paid twice over, plus a third end-to-end
  test because two pins updated in step redden neither.
  `ArchitectureRulesTest.UNEXERCISED_BY_DECISION` empties. `integration` is the thirteenth
  module, declares `{api, tenancy, project}` (NOT `identity` — `PeopleDirectory` is
  `project`'s port), is a leaf with its own rule, and may see neither `execution` nor
  `work`, which is what makes the outbox the only mechanism rather than one of several.
  `V54` drops `team.notification_channel`, a `V1` column writerless since M0; policies
  64 → 67. The first recoverable secret in the codebase: AES-GCM, key required with no
  default and **no colon**, never returned, never in a payload, never in either error
  column. `work.defect_created` deliberately NOT minted — no defect entity exists, and a
  writerless name in a foundation module is the shape this migration's own drop removes.
  Gates **2768 / 0 skipped / 0 failures** (+59, run locally on macOS — the Linux lane
  has not seen it yet). Phase 2 is the contract and the
  controller; the write service and its views are driven only by tests until then.
- 2026-08-12 · CHECK · **the query parameter nobody declared** (§176, one phase, `backend/` test code
  only; no migration, no contract change, nothing in `src/main`). §171's recorded gap, closed with one
  test method: `noHandlerBindsAnUndeclaredQueryParameter`. §171's stated reason for deferring was true
  and still is — `@RequestParam` cannot tell a form field from a query key — so the rule **skips**
  multipart operations rather than subtracting their declared parts, giving every bound name exactly
  one owner. Green on arrival; floor measured at 13 and left at 5. Three mutations, all run, `QP-2` an
  expected-green ownership row. Gates 2539/516/0 (+1 test).
- 2026-08-12 · EXTEND · **three declarations of one team** (§175, one phase, `backend/` + contract +
  both `schema.d.ts` copies; no migration, no frontend or `packages/` source). §147's oldest standing
  candidate, taken. `RoleAssignment.team` and `CurrentUser.teams[]` become `$ref`s to `TeamRef`
  (nothing on the wire moves at either), `CurrentUser.teams[].isLead` is **deleted** — no reader
  anywhere, confers nothing — and the three Java records collapse into `com.tcms.api.TeamRef`.
  `HOMONYMS` empties and stays; `INLINE_RECORDS` loses two. No production behaviour changed, no test
  added: the rules §147 and §170 built were enough to hold the result. Gates 2538/516/0, unchanged.
- 2026-08-11 · CHECK · **the two types that agreed by accident** (§164, one phase, `backend/` tests +
  a contract *comment*; no migration, no production Java, neither `schema.d.ts` moves). Closes the
  two gaps §163 named on its way out. `bothEnvelopesSerializeTheSameKeys` compares the two envelopes
  as **serialized keys** on equivalent refusals — the gap §162's queue excused as uncomparable,
  which was true statically and false of the bytes; `noRefusalBodyEchoesTheRequest` cannot cover it,
  being a **subset** rule both envelopes satisfy while sending different subsets.
  `theContractsProblemIsThisRecord` pins the schema to `api.Problem`'s components **both ways** —
  `Problem` was in neither `ContractEnumTest` nor `RESPONSE_RECORDS`. New fixture
  `/record-with-errors` so the validation shape is compared too. **Origin is worth knowing: §163 was
  written TWICE, concurrently, by two sessions that never saw each other** — both decided *suppress*,
  by different mechanisms, and the pull was a **clean fast-forward with no textual conflict**, git
  having nothing to say about a collision that was semantic. Wire outcomes were byte-identical; only
  the checks differed, and these two are the pushed variant's own named next work. Mutations `EN-1`,
  `EN-1b`, `EN-2` red then reverted — **`EN-1b` (a *declared* member on one envelope only) is the one
  that justifies the rules, being invisible to every pre-existing check in the file**; `EN-3` does
  not compile (~50 canonical-constructor call sites) and is recorded as abandoned. Also fixed
  `openapi.yaml` still naming `api.ProblemMediaType`, renamed by the same commit that wrote it.
  `./gradlew build` **2457 / 506 / 0**. `#confirmed`
- 2026-08-11 · FIX · **the request echo nobody wrote** (§163, one phase, `backend/` + contract + both
  `schema.d.ts` copies, no migration). Took §162's declared-but-unresolved `instance`. Measured
  first, and the obvious hypothesis (nothing writes it) was **false**: all 21 `ProblemDetail`
  controllers send it, written by Spring's `HttpEntityMethodProcessor` from `getRequestURI()`, while
  `setInstance` occurs zero times in `src/main` — a value no source scan can see. Suppressed rather
  than declared, so §146's no-request-echo rule holds on every writer; `ProblemMediaType` renamed
  `ProblemEnvelope` because it now decides two things. Three checks, two of them reading the server:
  a subset rule over the real serialization, an echo-name ban on the schema (so re-declaring is a
  decision), and a real-HTTP assertion on a controller-written 403. Mutation-verified in three
  directions.

- 2026-08-09 · EXTEND · **the seams only a test exercises** (§153, one phase, no migration).
  `everyPublicBeanMethodIsExercisedInProduction` asks §152's question from the other side and finds
  two: `Outbox.record` (kept by decision — deleting it makes three `V1` columns write-constant) and
  `ProvisionCompanyCommand.isRequestedBy` (deleted; its six-assertion test was the only coverage the
  non-web CLI decision had and it pinned the superseded copy — `OperatorCommandsTest` is that test
  now, over all three verbs). The population is pinned rather than emptied, so it fails on a
  disappearance too. Four javadocs corrected, including `OutboxMessage.payload`'s honesty mechanism
  that does not exist and `ArchitectureRulesTest`'s own "four properties" over thirteen rules.
  2407 tests, green.
- 2026-08-08 · EXTEND · **Automation Service non-assignable** (§134, `V41`, two phases). The role
  `V3` called token-only becomes `assignable = false`; `RoleGrantService.revoke` **stops** reading
  the flag and names the membership baseline, because reading it there strands every pre-`V41` grant
  behind a Revoke that 403s while the row keeps rendering. Existing grants kept, listed and
  revocable — the migration deletes nothing. Roles spec §A2's "the six are the complete assignable
  set" retired: five are. 2221 tests, green first run; no frontend code change.
- 2026-08-07 · EXTEND · **attachment owners, phase 1** (§126, M3 slice 10, decision round 3 step 6).
  `V39` narrows `attachment.owner_type` to five values (#50); `storage` gains `AttachmentOwner`,
  `BlobUpload` and three factories, and `EvidenceStore` is renamed `AttachmentStore` with its
  property key deliberately kept; `repository` gains the `CASE` family and `TestCase.attachments`.
  2050 tests. Phases 2–4 (`DOC_PAGE`, `ARTIFACT`, the frontend) are not built.
- 2026-08-07 · EXTEND · **the `storage` module** (§125, M3 slice 9, decision round 3 step 5). Two
  phases. `Attachment` + `AttachmentRepository` + both blob ports + both filesystem adapters move to
  `com.tcms.storage`; `FOUNDATION_MODULES` gains a fourth entry and a new ArchUnit rule
  (`storageDependsOnTenancyAlone`) guards the property that makes it legal; phase 2 deletes
  `ReportBlob` and collapses the duplicated adapter body into `FilesystemBlobStore`.
  **The find worth carrying forward:** a javadoc that names the *exact condition* for a future move
  (`Attachment`'s "a second caller to design against") and a factory pair that keeps a vocabulary out
  of call sites made this a package line and two modifiers — the opposite outcome from §121 phase 3's
  seam, which guessed at a consumer and had to be deleted. **Commit to a shape, leave the caller
  open.** Second find: two identical files in two packages are never read side by side, so the report
  adapter's traversal guard went untested from §87 to §125.
- 2026-08-07 · EXTEND · **grant-instance-admin** (§122, M3 slice 7). The operator tier's first and
  only writer. `GrantInstanceAdminService` / `…Result` / `…Command` in `provisioning`, plus the verb
  in `OperatorCommands`; `GrantInstanceAdminServiceTest` (7, `gia-` namespace). No migration, no
  HTTP surface, no screen — and none should exist until an instance-admin surface does
  (`RoleGrantService`'s own header). **The find worth carrying forward:** this is the third slice
  running where a thing the schema *and the docs* both describe turned out to have no writer at all
  (§120's `stale_automation`, §121's open question #24, now the whole SYSTEM tier). Ask it of any
  `V1` column before assuming it is live. Ten mutation rows queued, none run.
  **Written concurrently with §121's lane in the same tree** — `compileJava` went red on an
  untracked file that had not existed ninety seconds earlier, and the correct move was to stop
  rather than repair it. `#confirmed`
- 2026-08-07 · EXTEND · **The ingestion history reads** (§119 phase 1, M3 slice 4). Two `GET`s over
  `ingestion_job`; the detail is the resource `POST /ingestions`' `Location` has always named and
  never had. New: `IngestionReadService`, `IngestionJobSummary`, `IngestionJobRow`, `UnmatchedTests`;
  `findSummariesInProject` (constructor projection) and `findOneInProject` replace the caller-less
  `findByProjectIdOrderByCreatedAtDesc`; `TestRunRepository.findRunKeys`. **No migration, no key, no
  entity.** Gate: **1953 across 408 classes, 0 failures, 0 skipped** (1946/407 → 1953/408).
  **Two findings.** The replay served `unmatchedCount: N` beside `unmatched: []` — the names were
  computed once and only ever lived in the blob, and the caller who gets a replay is a CI *retry*.
  And `ReportFormat` names two unrelated things in one contract (CI dialect vs report export
  format), which surfaced as a duplicate identifier on the frontend and is aliased `IngestionFormat`
  there. 8 mutation + 11 browser rows queued, none run.

- 2026-08-06 · EXTEND · **Version divergence** (§114, **R8** — the last slice in M2.5's reporting
  plan). A sixth section on `AggregateExecutionReport` over `selectedCte`, one row per (case,
  version) for cases the selection ran at more than one version, plus a header flag printed even at
  zero. New: `ExecutionSource.caseVersionNumbers` (the seam's third method, seventh table),
  `AggregateVersionDivergenceTest` (13 tests, prefix `c4`). **No migration, no key, no entity, no
  frontend code**; contract change is prose. Gate: **1852 across 377 classes, 0 failures, 0 skipped**
  (1839/374 → 1852/377); frontend 958/958, run *after* the backend rather than beside it.
  **The finding is that what divergence costs is a property of the policy and differs in kind across
  the five**, so the row carries a computed `Counted in the totals` column and the narrative follows
  `blendsVersions(rows)` rather than the policy's name — prose keyed on a policy is a second
  implementation of it in a place no reader can check. **The slice has NO RED run** (tests written
  after the code, all green first time); recorded in §114 §5 and in mutation row R8-1, which is the
  only route to a measurement. 7 mutation + 4 browser rows queued, none run.
  Landed alongside a **concurrent strand's §113** (the deploy's second target), which is why this
  slice is §114: the number was taken between writing the section file and indexing it.
- 2026-08-05 · EXTEND · **Conditions get a writer** (§95 phase 1) — `POST` + `PUT
  /projects/{projectId}/conditions`, both on `library:manage`, the `PUT` with `If-Match` and a 409.
  New: `ConditionEdit`, `ConditionWriteRequest`, `ConditionUsage`, `ConditionWriteEndpointTest`
  (20 tests, prefix `c9` — computed free against the whole test tree rather than guessed, §19's
  defect having recurred five times); `Condition` gained a `create` factory, `applyEdit`, the four
  audit columns and `@Version`; `ConditionRepository` gained both count queries;
  `ConditionLibraryEndpointTest` gained 4 tests and had 2 reworked. **No migration, no table, no
  column, no new permission key** — policy count still 59, `V30` still head.
  **The finding is that `usageCount` was always zero and was one slice away from being an impact
  warning** — the column is now unmapped and both counts derived, and the count is deliberately
  **two numbers that must not be added** (see the seam). The contract had posed that exact question
  on `/shared-steps` and left it open; this answers it.
  `npm run gen:api` run and `npm run build` immediately caught the mock's condition fixtures missing
  both new required fields — the payoff of §79's no-cast rule, one build error rather than a page of
  runtime ones. Backend **1704/1704**, build clean; frontend build clean, lint 0, **805/805**.
  **Built and unverified** — 6 mutation rows queued, **zero run** (§65).

- 2026-08-05 · EXTEND · The step corpus (§94 phase 1) — backend 1649 → **1681** (+13, all in
  `StepSuggestionEndpointTest`, prefix `c4`). New: `V30__test_step_action_trgm.sql` (**head**, `V31`
  next free, **policy count still 59** — an index, no table), `StepSuggestionController`/`Service`/
  `Query`/`View`, `TestStepRepository.findSuggestions`. The editor's autocomplete corpus, **derived
  from every live step in the project rather than curated** — the correction to `shared_step`, which
  stayed empty because it needed populating. **Serves no id**: a suggestion is text copied, not an
  entity referenced, and the test pins the exact field set so it stays that way.
  **Finding, recorded and NOT fixed:** `V30` indexes the expression `lower(action)` because Postgres
  consults a functional index only for the expression it was built on — and `shared_step_title_trgm_idx`
  / `condition_title_trgm_idx` (V1) are both spelled on the bare column under `LOWER(title)` queries,
  so **neither is ever used**. Silent: right answers, maintained on every write, same seq scan.
  **Process error worth not repeating:** a second `./gradlew build` started while the first was in
  flight; both wrote `backend/build/` and the `Could not write XML test results` failures read as
  test failures. CLAUDE.md's one-build-one-daemon rule, hit directly.
  **Zero mutations run inline; 4 backend rows queued — built and unverified.**
- 2026-08-06 · EXTEND · The aggregate execution report (**R6 phase 1**) — the first report spanning
  more than one run, and the slice that spends decision D. New: `AggregateExecutionReport`,
  `AggregateSelection`, a duplicated `reporting.AggregationPolicy`; `ReportParameters` gains
  `idList` / `textList` / `optionalInstant`. **No migration (`V36` still free), no contract change,
  no key, policy count 61.** The report is registered by *existing* — `ReportComputations` is a
  registry, so no list was edited. `AggregateExecutionCrossCheckTest` (prefix `c1`) is the oracle;
  **two mutants were run and the second proved the fixture vacuous**, which is why `UNBLOCKED`
  exists. **§19's prefix collision, sixth instance**: `6a` is `CaseListEndpointTest`'s and the full
  suite went red in 64 tests of an unrelated class before the move to `c1`. Gate: **1806 across 363
  classes, 0 failures, 0 skipped** (1795 → 1806). ~~**Still owed by R6: the contract's `parameters`
  schema for this type**~~ → **shipped in phase 2.**
- 2026-08-06 · EXTEND · **R6 phases 2–4 (§111)** — the report becomes askable and its selection
  becomes tested. Phase 2: the contract names `aggregate_execution` and documents the selection
  (**prose only** — two descriptions and a `gen:api`; no schema, no operation, no field). Phase 3:
  `GenerateReportDialog` grows the second entry and its form, and **the run gate stops being
  global** — a project with no runs may ask for this report, asserted `toBeEnabled()` because the
  natural direction passes against the pre-R6 dialog. Phase 4: **`AggregateSelectionTest`**
  (prefix `c2`, slug `aes-`, 18 tests) closes the four queued mutation rows the oracle could not
  see, each re-run against its mutant. **The finding is R6-8 coming back GREEN**: the
  `CASE … ARRAY[NULL]::uuid[]` in `featureGroups` is *not* what keeps the unclassified row —
  `LEFT JOIN LATERAL … ON true` is, and `CROSS JOIN LATERAL` is the mutation that reddens. The
  javadoc claiming otherwise is struck through in place; **protect the word `LEFT`, not the
  `CASE`.** Also corrected: `AggregateSelection`'s *"a 400 at request time"* — a report's own
  parameters are refused by the **worker**, as a `FAILED` job, which is why the dialog makes the two
  reachable mistakes unexpressible. Gate: **1824 across 370 classes, 0 failures, 0 skipped**
  (1806/363 → 1824/370); frontend 958/958. **Nine mutations run across the three phases; 5 browser rows
  queued, none run.** R7/R8 remain unstarted.
- 2026-08-04 · EXTEND · The HTML renderer and the report header (§88) — **R4**, three phases, three
  commits, backend 1615 → **1628** (320 classes), frontend 705/705. New: `ReportHeader`,
  `ReportDocument`, `ReportHeaders`, `HtmlReportRenderer`; `ReportRenderer.render` takes a document;
  `ReportParameters.entries()`; the reporting mock serves HTML and a `{ header, report }` model.
  **No migration (`V27` still head), no table, no column, no key, policy count still 59, contract
  prose only.** Two recorded deviations from the plan: **no Thymeleaf** (the escaping guarantee is a
  counted test instead) and **no logo** (unmapped columns, no override column — branding is M6).
  **The one test that failed first time was the assertion, not the code.**
  **Zero mutations run inline; 9 backend rows and 5 browser rows queued — built and unverified.**
- 2026-08-04 · EXTEND · Saved report definitions (§92) — **R5**, three phases, backend
  1628 → **1649**. New: `V28__report_artifact_definition_on_delete.sql` (**head**, `V29` next free,
  policy count still 59), `ReportDefinitionRepository`/`Service`/`Controller`/`View`, `ReportTypes`,
  `ReportDefinition.create`, `definitionId` on `ReportJobService.request` (**signature changed** —
  `request(userId, projectId, definitionId, type, format, parameters)`), `ReportParameters.merge`,
  `openapi.yaml` **+2 operations** and a changed generate body (`required` is now `[format]`).
  **No new permission key, no new table, module declaration unmoved.**
  **The plan said R5 needs no migration and was wrong**: `report_artifact.definition_id` is a `V1`
  FK with the default `NO ACTION` while its `report_job` sibling is `ON DELETE SET NULL`, so deleting
  a *used* definition was a 500 on a route whose contract says 204.
  **Numbered §92 although its first two commits say §91** — a concurrent strand took the number
  mid-slice; see `docs/handoff/92-*.md` §0.
  **Zero mutations run inline; 10 backend rows and 5 browser rows queued — built and unverified.**
- 2026-08-04 · EXTEND · The report pipeline (§87) — **R3**, seven phases, seven commits, backend
  1553 → **1615**. New: `V27__report_job.sql` (policy count **58 → 59**, head migration, `V28` next
  free), three entities + two repositories, `ReportStore`/`FilesystemReportStore`, `ReportModel` +
  two renderers + two registries, `RunSummaryReport`, the service/runner/scheduler trio,
  `ReportReadService`, `ReportController`, `ProjectService.readGranted`, `openapi.yaml` **+2
  operations**, and the corrected reporting mock. `reporting` widened to
  `{"tenancy", "project", "identity"}`. **No new permission key.**
  **The finding is phase 6**: until it, both report gates were satisfied by an implementation with no
  gate, because no persona the suite could build lacked either key — fixed with the suite's **first
  two custom roles**, whose `@AfterAll` cleanup is itself load-bearing against `SeedDataTest`.
  **Zero mutations run inline; 11 backend rows and 8 browser rows queued — built and unverified.**
- 2026-08-04 · EXTEND · The fact log gets a writer (§86) — **R1+R2** of the M2.5 reporting plan, and
  the product's **tenth Modulith module**. New: `reporting/package-info.java`, `ExecutionSource`,
  `ExecutionFactRefreshService`, `ExecutionFactScheduler`, `ReportingProperties`;
  `ModuleStructureTest` 9 → 10 modules and its rationale rewritten; `IntegrationTestBase` gains
  `tcms.reporting.enabled=false`. **No migration (`V27` still free), no contract change, no key, no
  table, no column** — policy count unchanged at **58**, verified by listing the migration folder
  before writing any SQL rather than by trusting a document.
  **Three things the pass confirmed absent**: the `reporting` package (listed since M0, nine modules
  existed), a writer for `execution_fact` (five indexes, zero rows since M0), and a writer for
  `case_health.failing_repeatedly` (a tile since §47 and a board card since §53, both asserting
  *nothing is failing* on every deployment).
  **The slice is one decision — which columns an `ON CONFLICT` may touch.** Seven move, fifteen are
  frozen dimensions, and **`suite_id`/`suite_path` were added to the frozen set beyond what the plan
  named**, on the identical argument. **`allowedDependencies = {"tenancy"}` alone**, narrower than
  the plan proposed: a declaration is a fact, not a forecast, and R3 is named as what widens it.
  **`failing_repeatedly` is `FAILED`, not `NOT counts_as_pass`** (three BLOCKED runs are not a broken
  test), and the window must be **full** (N=3, refusing to start below 2).
  **Two defects the gate caught that reading could not**: `max(uuid)` does not exist in Postgres, and
  a fixture UUID prefix must be **hex** — `rf…` threw in the static initialiser and failed all eleven
  tests before one ran, §19's family with a new mechanism. **Found and not fixed**: `case_health` has
  no index on `failing_repeatedly` while `V1` ships four for other signals — the first tile that
  scans, and an index is a migration. Also named-not-built: `execution_fact.executed_by` cannot name
  an `api_token`, so M3's ingested results will carry a null actor and §6e's per-tester throughput
  will silently exclude CI. **Gate green, confirmed after the commit**: `./gradlew build` →
  **1530 → 1553** across 300 classes, 0 failures, 0 skipped, counted from the XML. `9d33f02` landed
  with that figure as arithmetic and the observation agreed. **The operational lesson: a subagent
  running the gate is a SECOND LANE in one tree**, and `CLAUDE.md`'s one-`build/`-one-daemon rule
  binds it exactly as it binds a concurrency worktree — six runs died on locks and daemon stops
  because the agent and the parent session both invoked `gradlew`, each `--stop` orphaning the
  other's test worker, which holds `output.bin` and poisons the next attempt. Diagnose by finding the
  holding process; do not retry. **Zero mutations run
  inline** (policy); 9 queued and 8 browser rows, the browser ones **all live-only** — the mock
  invents its own health rows and has never been fed by a fact log, so mock agreement is not evidence.

- 2026-08-04 · PLAN · Decomposed M2.5's **reporting half** into
  `docs/plans/2026-08-04-m2.5-reporting-slice-plan.md` — 10 slices (8 backend, 2 frontend), **one
  migration (`V27`, policy count 58 → 59)**, **no new permission key anywhere**. No code written.
  Seven verified state facts folded into Known unknowns above, of which three change what a reader
  would assume: **the `reporting` package does not exist**, **there is no `report_job` table
  anywhere**, and **the report gate cannot be made to discriminate without a custom test role**,
  because the only built-in role withheld `report:view` is Automation Service and token auth is M3.
  The plan's load-bearing decisions: `execution_fact` is written by an **idempotent recompute**
  (`CaseHealthRecomputeService`'s shape) and not by events, because with zero `CompanyScopedEvent`
  implementations and no outbox consumer an event-only fact log has **no rebuild path**; its
  `ON CONFLICT DO UPDATE` names only the **result** columns, since re-running would otherwise re-file
  historical results under the current taxonomy; the async worker is a **company loop over the
  un-RLS'd `company` table** (a global "find pending jobs" query sees zero rows under `V4`'s
  `nullif` and logs a successful tick having done nothing); output goes through a **second** storage
  port because `execution.EvidenceStore` is unreachable, with the E1 debt named rather than a
  `shared` module created; the aggregation **enum is duplicated and the algorithm is not**, with a
  cross-check test pinning the report against `PlanStatisticsService` for a single-plan selection;
  and the on-screen view reads a **stored `JSON` artifact** rather than a second preview code path.
  PDF, S3, scheduled reports, notifications, Mermaid, the coverage matrix and the evidence bundle are
  named as **not planned**, each with the missing infrastructure. Nothing in the codebase was
  changed; `V27` is still free.

- 2026-08-04 · EXTEND · §6l's team board (§84) — the last of its three with no way of being asked
  for, and a **third selector** on the operation that already serves the other two:
  `GET /work-items?teamId=…`. New: `BoardFacts.teamMembers`, `BoardService.teamBoard` +
  `forAssignees` + `present(cards, Set<UUID>)`, `WorkItemController.twoBoardsProblem`.
  **No migration (`V27` still free), no table, no key** — policy count unchanged at **58**.
  **The slice is one decision: the bound is the CALLER's project visibility, never the team's** —
  *"cards assigned to the team's members"* is one query, reads correctly, and renders another
  client's case key, title and project name to a reader who need not be on the team and need only
  guess a UUID. **§83's fixture could not have proved it** (its assignee is always the caller, so
  both bounds agree on every row), so §84 adds `TEAMMATE` — granted where LEAD is not — and asserts
  it as a **pair**, the second half being what stops the first from passing on a fixture row that
  silently failed to insert. `TeamDirectory` was **not** widened: its `find` gets a second consumer
  for existence, and the expansion is `BoardFacts.teamMembers`, at read time. **Nothing overturned**;
  three things checked rather than assumed, including that `work_item.assignee_id` references
  `app_user`, so §6l's team assignee for the unclaimed pool **does not exist** and is named not-built.
  Found and not fixed: **the two boards are not protected by the same line** — dropping the grant
  filter from `visibleProjects` leaves every `Gates` test green, because a named project goes through
  `visibleProject`'s own copy. All tests green on the first run. Backend 1513 → **1530** (17 in a new
  `BoardEndpointTest.TeamBoard`), 0 failures, 0 skipped. **Zero mutations run inline** (policy); six
  queued. `frontend/src/api/schema.d.ts` is **stale** — `openapi.yaml` changed and `npm run gen:api`
  belongs to the frontend half.

- 2026-08-04 · EXTEND · §6l's cross-project "My Work" board (§83) — `GET /work-items` with the
  project parameter left **off**, which is why §53 chose an instance-scoped path before the board
  existed. New: `ProjectService.readAllVisible` + private `visibleProjects`,
  `WorkItemRepository.findLiveInProjects`, set-taking `BoardFacts` generators,
  `BoardService.personalBoard` + `present`, `WorkItemController.unscopedBoardProblem`.
  **No migration (`V27` still free), no new key, no new table** — policy count unchanged at **58**.
  **The refusal is the feature**: no project and `mine=false` is a **400**, not a silent narrowing.
  **Two findings on review, neither a defect in behaviour**: the personal board's javadoc claimed a
  bound the code does not have — the *answer* is person-sized, the *read* is the whole visible
  company — corrected in `BoardService.personalBoard` and `WorkItemController.list` rather than
  restructured, since pushing `mine` into the four sources is what `present` refuses by name; and
  `visibleProjects` evaluates `canView` once per project in the company on every load. Backend
  1505 → **1513** (8 in a new `BoardEndpointTest.MyWork`), 0 failures, 0 skipped. **Zero mutations
  run inline** (policy). `frontend/src/api/schema.d.ts` is now **stale** — `openapi.yaml` changed and
  `npm run gen:api` belongs to the frontend half of this slice.

- 2026-08-04 · EXTEND · §6g's gating rules (§82) — the last unbuilt part of the review workflow, and
  a slice that built almost nothing new: **two boolean columns** (`V26`) and the two places that
  read them. New: `ApprovalGating`, `PlanLifecycle.startsWithoutApproval` +
  `APPROVAL_REQUIRED_MESSAGE`, `CaseFacts.unapprovedCases`, `ProjectService.holds`,
  `RunCreateService.Outcome.Refused` + `gate`, `PlanStatusService.moving` + `gate`,
  `overrideApprovalGate` on `TestRunInput`/`PlanStatusInput`. **The plan rule is an EDGE and the
  target-keyed reading strands every closed plan for ever**; **the case rule binds on the resolved
  set or the plan is the bypass**; **the override invents no key and is derived** (its holder can
  approve first, so it widens nothing and only changes the record). **Not ANDed with the review
  opt-in** — §77's "off is narrow". **Five backend tests red on the first run, four of them a
  fixture leak**: `project_settings` was not on `clearMutableRows`, so one test turned the gate on
  for every test after it and **three casualties predate the slice**. Policy count unchanged at
  **58**; `V27` is the next free number. Backend 1487 → **1505**. **Zero mutations run inline**
  (policy); ten queued, fifteen browser rows.

- 2026-08-03 · EXTEND · The reviewer's inbox (§81) — §6l's `REVIEW` generator, absent since §53,
  built in **`work`** because **no other module may write its query** (four tables, three modules).
  New: `BoardFacts.PendingReview`/`pendingReviews`, `BoardService.PREFIX_REVIEW` + the review card,
  `frontend/src/mocks/db/workItems.test.ts`. **No migration (`V26` free), no contract change** —
  `kind: REVIEW` went into `openapi.yaml` in §53 ahead of its generator. **A team is expanded at
  READ time**, the mirror of §80's write-time refusal, for the same stated reason. **The finding: the
  MOCK had shipped this feature since Phase 1 and wrong in four ways** — a card per case in
  `IN_REVIEW`, in the wrong column, assigned to the case's **owner** (the person the server refuses
  as an assignee), and never for a suite or a plan. §52's class in its most expensive form: the mock
  was agreeing with an *idea*, not with a server, and it looked finished, which is why nobody built
  the real one for 28 slices. **Second defect made live**: every card's key linked to a case search,
  so a plan review rendered "no cases match" — `linkFor` now keys on `sourceType`, with a suite going
  to the case list filtered to it because a suite has no page of its own. §79's cast repair carried
  to `BoardPage.test.tsx`. Backend 1476 → **1487**; frontend 640 → **649**. **Zero mutations run
  inline** (policy); nine queued, eleven browser rows, and §80's browser row 14 struck through
  because it recorded this absence as correct.

- 2026-08-02 · EXTEND · Reviewer assignment (§80) — the last unbuilt half of §6g's apparatus, and
  **the first review slice that needed a new table**: `review_assignment` is not in `V1`, because
  `V1` designed the record of what happened and never the worklist of what is outstanding. New:
  `ReviewAssignment`, `ReviewAssigneeEdit`, `ReviewAssignmentRepository`, `ReviewAssigneeView`,
  `ReviewAssignmentRequest`, `TeamDirectory` + `TeamRefView` + `identity.IdentityTeamDirectory`,
  `ReviewService.assign`/`unassign`/`store`/`addAssignee`/`removeAssignee`, two controller routes,
  **`V25`** (policy count 57 → 58). **The slice is one idea** — an assignment is a worklist entry and
  not part of the audit record — from which the hard delete, the team cascade (§71's 409 NOT
  extended) and the write-nothing re-ask all follow. **No new key invented** (one seeded to nobody is a 403
  nobody can grant out of), so asking costs `*:review`, and the two gate mutations are a **pair** proving it
  is neither the tier below nor the tier above. **The build caught the contract slip**:
  `ReviewAssignmentInput` shipped with no `required` array, and `ContractConventionTest` failed —
  both fields are required-and-nullable, `RoleAssignmentInput.projectId`'s shape, or "ask nobody"
  would be expressible only by omission. **Zero mutations run inline** (policy); eight queued.
  Backend 1458 → **1476**; frontend 627 → **640** (9 written, **4 generated** by §70's data-driven
  checks discovering the two new `api.*` call sites, `api.del` among them).

- 2026-08-02 · EXTEND · Review comments (§79) — `review_comment`'s first writer ever, and
  `case:comment`'s first consumer in Java, which empties the review block of the catalogue. New:
  `ReviewComment`, `ReviewCommentEdit`, `ReviewCommentRepository`, `ReviewCommentView`,
  `ReviewCommentRequest`, `ReviewCommentResolutionRequest`, `ReviewSubjectType.COMMENT_PERMISSION`,
  `ReviewService.comment`/`resolveComment`/`refuseIfClosed`/`unwrap`/`threads`,
  `ReviewController.respond` (three write paths, one mapping), `SeedDataTest.everyReviewerIsAlsoACommenter`,
  **`V24`**. **The slice is one decision**: the catalogue has one comment key and three review keys,
  and adding the two missing ones would seed keys granted to nobody — so `case:comment` is asked for
  all three subject types, and the ladder that makes that sound is a computed check. **Resolving
  costs `*:review` instead**, and the two gate mutations are a pair that redden one test each.
  **The defect: resolving twice re-stamped the record** and would have handed `resolved_by` to
  whoever clicked last — one of sixteen tests failed on the first run, because it was written from
  the contract sentence rather than the code. `V24` adds `resolved_by` (an act with no actor is not a
  record) and the read path's index; no new table, so the policy count stays 57. **Frontend finding:**
  `ReviewPanel.test.tsx`'s fixture ended `} as Review`, so a new required field reddened all eleven of
  its tests at runtime with zero type errors — §65's finding in a second file; the cast is gone.
  17 backend tests, 7 frontend, **0 mutations run inline** (policy, followed), 7 queued.
- 2026-08-02 · EXTEND · Per-project review settings (§77) — §6g's four settings get the row they have
  needed since the workflow was designed. New: `ProjectSettings`, `ReviewSettings`,
  `EditAfterApproval`, `ProjectSettingsRepository`/`Service`/`Controller`/`View`/`Request`,
  `ReviewService.refuses`/`refusesEach`/`reReviewOnEdit`, `ReviewRepository.findOpenForSubjects`,
  `Review.getRequiredPeerReviews`, **`V23`** (the first table created since `V1`).
  **The ERD/plan conflict was put to the user, not decided in a comment**: the ERD puts `settings` on
  `company`, §6g requires per-project-per-artifact-type. **The gate run produced a mutation result
  for free — 18 of 1419 reddened**, 17 of them `ReviewEndpointTest` finding zero reviews because its
  fixture creates projects by direct insert and therefore gets what a real fresh project gets: review
  off. §72's fixture rule from the other side — there the mock sat in a state no install produces,
  here the *tests* assumed a feature was on. The 18th was `SchemaConventionTest` 56 → 57, working as
  designed. 22 tests, **0 mutations run inline** (policy, followed), 11 queued. The MUI slot trap
  fired a **fourth** time (`inputProps` vs v7's `slotProps`) — `muiTestIds` owns the shape, not the
  prop name, which is the gap; caught by `npm run build`, not by the test runner.
- 2026-08-02 · EXTEND · The review record (§76) — `review` and `review_participation` get their first
  writer ever, and `case:review`/`suite:review`/`plan:review` their first consumers. New:
  `ReviewService`, `Review`, `ReviewParticipation`, `ReviewState`, `ReviewStanding`,
  `ReviewSubjectType`, `ReviewView`, `ReviewParticipationView`, `ReviewParticipationRequest`,
  `ReviewController`, `ReviewRepository`, `ReviewParticipationRepository`, `PeopleDirectory` +
  `IdentityPeopleDirectory`, `project.UserRefView`, `LifecycleLadder.standingOf`,
  `PlanLifecycle.standingOf`, `Suite.getCurrentVersionNo`, **`V22`**. **The placement decision is most
  of the slice**: three writers in two modules that cannot depend on each other, so the record lives
  in `project` and a `review` module is refused with its cost written down. **The E1 finding:** writing
  the third consumer of §6u's naming rule exposed `CaseOwners` and `ExecutionPeople` as byte-identical
  copies; both now delegate to the port. **`V22` adds `WITHDRAWN`** because a withdrawal is not a
  rejection, plus the partial one-open-review index. 22 tests, 4 backend mutations run (**1 / 2 / 1 /
  6**) and 3 frontend (1 each), 3 queued. **The frontend mutation that did not redden is the finding**:
  `waitFor(...toBeDisabled())` passes on `useCan`'s pending state (§66), so the test was rewritten as a
  positive assertion plus a barrier on `PermissionGate`'s denial wrapper.

- 2026-08-02 · EXTEND · The suite lifecycle (§75) — `suite.lifecycle_status` had exactly one writer
  (`Suite.create`, writing `DRAFT`), so three of four values were unreachable and every suite on
  every real instance has been a draft for ever; `Suite.applyEdit`'s javadoc named the slice. New:
  `LifecycleLadder` (shared by suite AND case), `SuiteStatusService`, `SuiteStatusRequest`,
  `Suite.applyStatus`, `SuiteService.viewOf`, `PUT /suites/{suiteId}/status`, six audit verbs,
  `BulkOperations.CASE_KEYS`, `CaseBulkService.leadOnly`/`holds`. **§74 named the reviewer apparatus
  as next and this deliberately is not it** — §28's re-derive rule found §6g's ladder has three
  subject types and the suite's was unbuilt. **The defect: `BulkOperations.permission` was keyed on
  the TARGET**, which §73 named as the weaker shape and left, so a Test Engineer could bulk-rescind a
  lead's approval on cases; fixed per row. **The mutation is the proof — dropping the unmaking arm
  reddens exactly 2 out of 112, one per aggregate.** Fixture finding in both directions: the backend
  fixture seeded every suite `APPROVED` while no real suite ever was, and the mock's fixtures have
  shown approved folders since Phase 1. 19 tests, 4 backend mutations run inline (**2 / 3 / 1 / 2**)
  and 2 frontend (1 each), 3 queued. **No migration — `V22` still free**, and the reviewer slice
  needs it (`project` has no `settings` column).
- 2026-08-02 · EXTEND · The plan editor (§74) — `entry_criteria`, `exit_criteria` and
  `test_plan_version` get their first writers at once, and a plan's scope becomes editable at all.
  New: `PlanUpdateService`, `PlanUpdateRequest`, `CriterionInput`, `PlanScope`, `PlanSnapshot`,
  `PlanVersions`, `TestPlanVersion` + repository, `PlanEdit.validateUpdate`,
  `PlanCriteria.METRICS`/`isKnownOperator`/`serialize`, `TestPlan.applyEdit`/`stampVersion`,
  `PlanScopeItemRepository.findForPlans`/`deleteForPlan`, `PATCH /plans/{planId}`,
  `PlanController#onWriteRace`, `suiteIds`/`caseIds` on the detail. **The orientation finding changed
  the shape before any Java**: the detail served `caseCount` — an expansion — and no scope ids, so
  the editor could not write back what it was served (§68's finding one aggregate over). **The defect
  found: `TestPlan` mapped `lock_version` with no `@Version`**, so it was `0` for ever and an
  `If-Match` could not fail; the sweep found `TestRun` and `work.WorkItem` the same, both left with a
  reason. 30 endpoint tests, 4 backend mutations run inline (**3 / 2 / 4 / 1** red) and 2 frontend
  (1 each) — the version mutation is the one to read, since bump-on-every-save leaves every scope and
  criteria test green. **No migration — `V22` still free.**
- 2026-08-02 · EXTEND · The plan lifecycle (§73) — `test_plan.status` had exactly one writer (the
  create factory, writing `DRAFT`), so four of five values were unreachable and `closed_at` was a
  `V1` column the entity did not map; `TestPlan`'s javadoc named the slice twice. New:
  `PlanLifecycle`, `PlanStatusRequest`, `PlanStatusService`, `PlanDeleteService`, two routes,
  `TestPlan.applyStatus`/`softDelete`, `TestRunRepository.countLiveByPlan`, seven audit actions.
  **Three permissions get their first Java consumer at once**; `plan:review` deliberately stays
  unconsumed. **The finding is the gate on the EDGE** — the obvious target-keyed reading passes an
  "an engineer cannot approve" test and still lets that engineer rescind a lead's approval; the
  mutation reddens **exactly 2** and nothing else notices. Two pre-existing frontend defects, both
  in `PlanListPage`: the colour map is the **sibling** of the one §72 fixed on the run list and
  §72's sweep never ran the grep, and the exit-criteria column read `!c.met` where null means *not
  evaluated*. 23 endpoint tests, 4 backend mutations run inline (2/3/1/1 red), 4 queued.
  **No migration — `V22` still free.**
- 2026-08-02 · EXTEND · The run lifecycle (§72) — `test_run.status` had been written exactly once
  in the product's life (to `NOT_STARTED`, by the factory), so every run on every real instance read
  "not started" for ever and `started_at`/`completed_at` were never filled. Three production
  javadocs named the slice by name, and `RunDetailPage` carried a comment stating a rule nothing
  implemented. New: `ExecutionTargets`, `RunLifecycle`, `RunStatusRequest`, `RunStatusService`,
  `TestRun.begin`/`applyStatus`/`isClosed`, `PUT /runs/{runId}/status`, `Outcome.RunClosed` ×2, four
  audit actions. **`run:close` is the fifth no-consumer permission and the FIRST that
  discriminates** — the gate mutation reddens exactly 1, where §71's could redden nothing.
  **Not §52's "mock agrees with itself"**: the mock had the same hole and looked right only because
  its fixtures ship runs already `IN_PROGRESS` — the general form is that **the mock's fixtures
  never sit in the state a fresh install produces**. Two pre-existing defects found: the run list's
  colour map was `Record<string, string>`, carried a `PLANNED` that is not a run status and was
  missing `NOT_STARTED` (so every real run rendered grey); and the audit `target_type` spelling
  above. 20 endpoint tests, 4 backend mutations run inline (1/2/2/1 red), 3 queued. **No migration —
  `V22` still free.**
- 2026-08-02 · EXTEND · Team management (§71) — `team`'s first writer after readers on three sides
  and none at all: `provision-company` seeds no team, `Team.create` had only test callers,
  `rename`/`reparent` had none, so `GET /teams` answered `[]` on every real instance and the grant
  path's team branch was unreachable. New: `TeamWriteService`, `TeamEdit`, `TeamInput`,
  `TeamMemberInput`, `TeamController`, `AdminDirectoryService.teamViewOf`/`renderTeams`,
  `AdminReach.manageTeams`, three repository counts/lookups, `V21`. `Team.create` gained a
  `description` parameter (4 test call sites) and `rename`/`reparent` were COLLAPSED into
  `applyEdit` — two mutators each stamping `updatedAt` for one PUT is a half-applied row waiting to
  happen. **`company:teams` got its first consumer in Java** — the fourth instance of that shape.
  **The finding is what the mutations could NOT do**: neither distinguishes `company:teams` from
  `company:members`, because `V3` grants both to the same roles; recorded as a computed check rather
  than papered over. 35 endpoint tests + 1 seed check, 2 mutations run inline (exactly 6 red / 2
  red), 3 queued. **`V21` drops a constraint and creates an index in its place** — the first
  migration to do that, so the queued in-place check is worth more than usual.
- 2026-08-02 · EXTEND · The classification vocabulary editor (§69) — `classification_value`'s first
  Java writer; the gate that depends on the dimension row; `V20`. New seams above. **Two orientation
  findings before any code**: `locked` meant three different things and one of them made
  `company:vocabulary` cover nothing; and `@ColumnTransformer` was missing on
  `ClassificationValue.path` exactly as `Suite.path`'s javadoc predicted by name. **First mutation
  reported 4 where the honest answer is 3** — a broken gate let one create succeed, so a second test
  failed on the duplicate check rather than the gate. Rename the fixtures apart before trusting a
  count.
- 2026-08-02 · EXTEND · Classification assignment (§68) — `case_classification`'s first writer after
  three readers and no writer since `V1`; the last of §5's three scheduled Phase-2 gaps and one of
  `CaseUpdateRequest`'s refused six. New: `ClassificationVocabulary`, `CaseClassifications`,
  `CaseClassification.of`, `CaseClassificationRepository.findForCase`,
  `TestCase.classificationValueIds` on the contract (detail only), `OpenApiContract.requiredOf`.
  **The orientation pass changed the shape twice**: the case served NAMES with no id anywhere, so
  the editor could not round-trip (hierarchical Feature ⇒ names are not unique); and the "leaf-only"
  rule the editor's comment used to justify keeping the field read-only **does not exist anywhere in
  the specs** — a parent is assignable. The real content is the reconcile's BOUND — see the seam
  above. **Two pre-existing defects found**: `TestCaseDetailView` never served `createdBy` (required
  via `allOf`), which inverted §66's `OWN` delete gate on the only screen that has one, now caught by
  a computed check; and `CaseDetailService` was a fourth copy of `CaseRenderer`. 18 endpoint tests,
  2 mutations run (4 red / 1 red), 4 queued. **No migration.**
- 2026-08-02 · EXTEND · Evidence attach and delete (§67) — `evidence` + `attachment` get their first
  entities and writers after being in `V1` from the beginning. New: `EvidenceStore` port,
  `FilesystemEvidenceStore`, `StoredBlob`, `Attachment`, `Evidence`, both repositories,
  `EvidenceUpload`, `EvidenceViews`, `EvidenceService`, `EvidenceController`; three routes under the
  run path (attach, delete, GET content). **The download route is not optional**: `Evidence.url` is a
  REQUIRED contract field that had no server, so attach alone would have had to invent it.
  **Rows-then-bytes ordering** is the slice's real content — see the seam above. `V1`'s
  "immutable once the run closes" is deliberately NOT implemented and the reason is in `Evidence`'s
  javadoc (`ExecutionService.record` already amends on a COMPLETED run, so gating evidence alone
  makes the screenshot stricter than the verdict). Adding `Outcome.Invalid` was a **compile error**
  in the delete handler's switch — the sealed-type property working. **No migration.**
  Four mutations run; the `OWN` one reddens exactly 2 while `deletesOwn` and the lead's test stay
  green. Tests added to `ExecutionEndpointTest` as an 8th `@Nested` class rather than a new file, to
  reuse its personas and avoid a 6th UUID-prefix collision.
- 2026-08-02 · EXTEND · Put permission qualifiers on the wire (§65), the rung §61 deferred. New:
  `HeldPermissionRow` projection, `MeController.HeldPermission`, `PermissionSet.qualifierOf/qualifiers`.
  **Collapsed `evaluate` and `qualifierOf` into one traversal** — the replace-don't-union boundary
  was written out twice and nothing failed if one copy was edited. **Deleted four repository
  queries** (`find*Qualifiers`); none had a direct test, which is why the duplication survived.
  `PermissionSet` is now a map and refuses a `NONE`-qualified key. Contract: `EffectivePermissions.
  permissions` items are `HeldPermission`, plus a `PermissionQualifier` enum with **no `NONE`**.
  New: `SeedDataTest`-style catalogue-derived agreement test over five personas × every key.
  **No migration.** Mutation-verified once (hard-coding `ALL` reddens exactly 8); two further
  mutations are QUEUED, not run — see `docs/verification/backend-mutation-checks.md`, the new
  standing policy that batches mutation checks instead of paying them per slice.
- ~~2026-08-02 · EXTEND · Built the shared-step library (§64), M1.5's other half.~~ → **RETIRED
  ENTIRE by §239 (audit C4)**: every class named below is deleted, both operations left the
  contract, and `sharedStepId` left `TestStep` and `TestStepInput`. Read the original below for what
  the `acceptableSharedStepIds` union was and why `CaseEdit.of` passed the stored half alone — the
  union is gone with the field, and the *reasoning* is the part §64's condition sibling still runs
  on. **Why it went**: `SharedStep.getBody()` had exactly ONE reader in the whole backend, this
  library's own list endpoint, so the reference resolved to nothing on every read path and editing
  an entry propagated nowhere while two SPA tooltips said it propagated everywhere. `shared_step`
  and `test_step.shared_step_id` are KEPT on disk, populated and unreachable; the DROP is owed to a
  later slice. Original entry: New: `SharedStep`
  entity over V1's table (`usage_count` deliberately NOT mapped), `SharedStepRepository`/`Service`/
  `Controller`/`View`/`Edit`/`Query`/`WriteRequest`/`Usage`, `GET` + `POST
  /projects/{id}/shared-steps`. **`usageCount` is DERIVED** — distinct live cases, two queries
  zipped and ranked in the service, because a denormalized counter must be recomputed by every path
  that rewrites a step list and a case edit replaces the list wholesale. **`library:manage` got its
  first consumer in Java.** Also a FIX: `CaseEdit.validate` gained `acceptableSharedStepIds` —
  `sharedStepId` had been persisted unchecked since M1, so a sibling project's id satisfied the FK
  and was stored while an unknown id was a 500. The rule is a UNION (live in project ∪ already
  referenced by this case), and `CaseEdit.of` passes the stored half ALONE so a bulk action over a
  case using a retired entry still reconstructs. Live pass found the emptiness check reporting "add
  at least one step" about a step that was submitted and rejected — predates the slice, swept across
  all three refusals. Fixture prefix `ab`. **No migration.** Mutation-verified twice.
- 2026-08-02 · EXTEND · Built `POST /cases/bulk` (§62), all eight declared actions — the last
  MSW-only surface with a live consumer. New: `BulkAction`/`BulkCommand` (sealed)/`BulkOperations`
  (pure parse + permission)/`BulkVerdict`/`BulkResultView`/`CaseBulkService`, `CaseRenderer`
  (extracted from two byte-identical private copies), `CaseEdit.of`, `TestCase` mutators for
  lifecycle/automation/owner/suite, `CaseTag.of` + `CaseCondition.of` (first writers of both tables),
  batch queries on `TestCaseRepository`/`CaseConditionRepository`, `ConditionRepository.findLiveInProject`.
  **`case:approve` got its first consumer in Java** and the gate is resolved from the parsed command;
  `SeedDataTest` gains the computed check behind that. **No migration.** Mutation-verified twice.
  §19's fixture-collision defect hit a fifth time (the `b0` prefix space was the board tests').
- 2026-08-02 · EXTEND · Built the artifact register in `documentation` (§61): `Artifact` entity over
  V1's table, `ArtifactRepository`/`Service`/`WriteService`/`Controller`, `ArtifactPeople` (the 4th
  `*People` display-name resolver) and `PlanFacts` (the 4th `*Facts` cross-module native-SQL reader,
  here into `execution.test_plan`). **Widened `documentation`'s allowedDependencies to include
  `identity`** and corrected its package comment, which claimed nothing in the module renders a
  person. **Touched the authorization core**: `V19` adds `PLAN_SCOPE` to `role_permission.qualifier`;
  `PermissionQualifier.strongest` is no longer an ordinal comparison and THROWS on the
  `OWN`/`PLAN_SCOPE` pair (they restrict different axes, so their union is inexpressible); new
  `ProjectAccess.canDoOnScope` + `ProjectService.writeGrantedByRowScope` + `project.RowScopeGate`
  mirror the authorship pair. New computed check in `SeedDataTest` keeps the throw unreachable.
  Mutation-verified. `ModuleStructureTest`'s hard-coded rationale updated.
- 2026-08-02 · FIX · Truncated the `Clock` bean to microseconds, closing §58's carried debt and
  re-enabling the two `@Disabled` invitation tests. Swept the class: `ExecutionService.record` and
  `SessionTokens.issue` both bypassed the bean with `Instant.now()` and now take it. Added
  `ClockResolutionTest` and `ArchitectureRulesTest.nothingCallsNowDirectly` (verified red by
  reintroducing the defect). Wrote the missing §59 and the new §60 handoff sections.

- **2026-08-17 (§241)** — Org chart backend, from `docs/plans/2026-08-15-org-chart-tasks.md`
  (T0–T4). Contract: `GET /api/v1/org-chart` + four schemas, no existing schema changed; fixed the
  false `company:teams` claim in `CurrentUser.teams`. Generalised both role-name queries to an id
  list as `UNION ALL` arms (adding `via`/`teamId`), extracted `EffectiveRoleNames`, and collapsed
  `ProjectAccess`'s two role-name methods into `effectiveProjectRoleNames` — the plan's placement
  would have broken the module graph. Added `OrgChartService`/`OrgChartController` and 44 tests
  across two classes. Ran two mutations to prove the checks fail (disclosure filter; §A1.1 union)
  and reverted both. Backend 3082/0/0 on shanks.
