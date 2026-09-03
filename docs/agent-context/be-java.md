# be-java — orientation cache

Baseline: 015 T3, refreshed 2026-09-03.
Trust this as a lead, not evidence; `CLAUDE.md` and `docs/specs/*` outrank it.

## Version lane
- Java 21 (Gradle toolchain), Spring Boot 3.5.6, Hibernate 6, Gradle Kotlin DSL, `-Werror`.

## Build, run & test
- `cd backend && ./gradlew build` is the gate (Testcontainers Postgres 17; no H2). Run it on
  `shanks` via `~/bin/tcms-remote` — standing directive; `ulimit -n 65535` first.
- Batches under `docs/plans/006-build-batches.md` do **not** run gates until B7.

## Layout
- Spring Modulith monolith, modules are packages under `com.tcms`; foundation modules `api ·
  tenancy · identity · project · storage`, everything else crosses via `CompanyScopedEvent`.
  `project` imports only `api` + `tenancy`.
- Flyway `backend/src/main/resources/db/migration` holds **four files** — `V1__initial_schema.sql`
  (V2–V75 were absorbed into it 2026-08-29 and deleted; it only ever runs on an empty database),
  `V2__pm_foundation.sql`, `V3__tracker_core.sql`, `V4__retire_work_item.sql`,
  `V5__tracker_board.sql`, `V6__release.sql`, `V7__import.sql`, `V8__agent.sql`. ~~head `V71` (B2)~~
  ~~the next number is `V2`~~ ~~→ `V5`~~ ~~→ `V6`~~ ~~→ `V7`~~ ~~→ `V8`~~ → the next number is
  **`V9`**. `out-of-order` is unset, so a lower number arriving after a higher one is silently not
  applied — `ls` the directory before writing.

## Seams to reuse
- `ProjectService.readVisible / readGranted / writeGranted` → `GateResult` (Unreachable/Denied/Allowed).
- Error envelope: `api.Problem` (record, NON_NULL) and Spring `ProblemDetail`; both announced
  `application/problem+json` by `api.ProblemEnvelope`. 403s: `Problem.forbidden(detail, key, scope)`
  / `Problem.forbiddenDetail(...)` since B2. `ErrorBodyPolicyTest` pins members + spellings.
- SPI-in-foundation pattern: `project.ReviewSubjectDirectory` (impls in repository/execution/
  documentation) and `project.ReviewImpactSource` (B2; defaults answer 0/[], impls due in B3).
- In-memory throttles: `auth.TokenRateLimiter` (per token), `auth.BindThrottle` (LDAP),
  `auth.SignInThrottle` (per address, B2) — all on `tcms.rate-limit` / `Clock` bean.
- `identity.PermissionEvaluator` is the one reading of "what does X hold"; `OrgChartService.gaps`
  mirrors its replace-don't-union rule and must stay in step.
- Repositories must carry `@Transactional(readOnly = true)` (RLS fail-closed otherwise).
- `tracker.board` (012 T3) — `BoardConfigService` is the project-scoped configuration service and
  the pattern to copy for a **sub-package** service: sealed `Outcome`
  (`Unprocessable(title, detail)` / `Written(view)` / `Deleted()`) inside a `GateResult`, gate keyed
  on `board:configure`, both reads on visibility alone because `board:read` is universal.
  `claimNewStatus(projectId, IssueStatus)` is called **directly** from `WorkflowService` inside its
  transaction — a Java event may not cross a module boundary and the outbox has a one-minute floor.
- A board `PATCH` parks its columns at negative positions and flushes before renumbering:
  `uq_board_column_position` is not deferrable and Hibernate orders its own updates.
- `tracker.board` (012 T4) — four more services, same sealed-`Outcome`-inside-`GateResult` shape.
  `RankService.place(userId, key, RankInput)` owns the fractional rank and **only the move path**:
  the creation path is V5's `issue_rank_default` trigger and must not be re-implemented (D-k).
  `BoardCardService.contents/backlog` assemble every card face through one inner `Facts` object;
  `BoardMoveService` **delegates** the transition to `TransitionService` rather than re-checking the
  workflow. `LabelService` is `board:configure` for the label and `issue:edit` for putting one on an
  issue — no new permission (D-e).
- **A service reading an entity BEFORE its gate gets a DETACHED one.** `projects.writeGranted` opens
  the transaction, so a repository read outside it commits and detaches — `TransitionService` copes
  by re-`save`ing, and `RankService` re-reads inside the gate because `EntityManager.refresh` refuses
  a detached entity. Cost T4 one round trip to find.
- Raw SQL in this codebase is `EntityManager.createNativeQuery` — **there is no `JdbcTemplate`
  anywhere**, and the 012 plan's pseudo-code that uses one predates the check.
- `BigDecimal.divide(TWO)` with no scale argument is **exact** (halving always terminates in base
  ten) and never rounds into a collision. The rank's growth bound is `scale() > 12`, checked on the
  `stripTrailingZeros()`ed value so a whole number reads as scale ≤ 0.
- `project.PeopleDirectory` is the display-name seam reachable from a `tracker` sub-package;
  `tracker.TrackerPeople` is package-private and is not.

- `tracker.release` (013 T3) — the release module, and **`ReleaseFacts` is the ONE file in
  `tracker` naming `test_case`, `case_health`, `execution_fact` or `test_run`.**
  `repository.ExecutionFacts`'s rule, applied here: one file per module names foreign tables, so
  every crossing is findable by opening one class. Do not add a second, and do not add the
  "just fetch these rows" convenience method its header refuses.
- **`com.tcms.api.ReleaseSortKey`** — moved there from `com.tcms.execution` in 013 T3, public, three
  callers (`TestRun`, `TestExecution`, `Release`). **The two `READERLESS_BY_DECISION` entries stayed**
  against 009's prediction: the roadmap orders `release.sort_key`, a third column on a new table, so
  `test_run.release_sort_key` and `test_execution.release_sort_key` are still ordered by nothing.
  Removing either entry reddens `everyMappedColumnHasAReader` on a column nobody touched.
- **Readiness matches `execution_fact.release_version` as TEXT, never `test_run.release_id`**
  (013 D-b). D14 backfills nothing, so every pre-M3 run has a NULL FK and filtering on it would
  report a version with three hundred executions as untested. `test_run.release_id` exists, is
  maintained by a `BEFORE INSERT` trigger, is **not mapped** on `TestRun`, and has no reader.
- `ReleaseVerdict` is three members and `NOT_TESTED` is not `NOT_READY` with zero failures:
  `ReleaseTests`' counts are **boxed and null** when `hasRun` is false, because a zero drawn as a
  meter reads as a clean release.
- **`Map.of()` throws on `get(null)`** and it cost 013 T3 five red tests with a bare NPE from inside
  `ImmutableCollections` naming nothing about releases. Any map a caller looks a nullable id up in
  must be a `HashMap`. `PeopleDirectory`'s javadoc already recorded this as a 500 in §38.
- **`IssueEdit` is pure**, so a release's existence is checked in `IssueService.checkRelease` and not
  there. A cross-project release is a 400 on `releaseId`, never a silent null.

- `tracker.imports` (014 T3, the preview half) — the module's own copies of two `integration`
  classes, because `tracker` may not import it and neither promoting the guard to a sixth
  foundation module nor moving it into `api` clears those modules' own bars (D-c):
  `ImportUrlGuard` mirrors `integration.OutboundUrlGuard`'s rules **byte-for-byte between two
  markers**, pinned by `ImportUrlGuardMirrorsTheOutboundGuardTest` reading both files as text, and
  **both files are declared in `build.gradle.kts`'s `inputs.files`** or a comment-only edit leaves
  `:test` `UP-TO-DATE` and the rule green by cache. `ImportHttp` mirrors `TrackerClient` and has
  the same second, package-private `(RestClient, ImportUrlGuard)` constructor — the ONLY way
  `MockRestServiceServer` stays bound, since the production constructor installs the real request
  factory over the double's.
- **The URL guard is applied twice on purpose**: `ImportPreviewService.vet` checks the
  *connection* before a source is chosen, `ImportHttp.exchange` checks each *URL* it composes.
  Only the first is reachable from a source that composes no HTTP URL, which is every fake.
- **`ImportSource` is the provider seam** — `projects()` · `snapshot()` · `supports(provider)`,
  and `ImportSources` filters the injected list on the third, so a new source is a new
  `@Component` and no edit. `FakeImportSource` (test tree, `Ordered.HIGHEST_PRECEDENCE`,
  `reset()` per `@BeforeEach`) is the fault switch: `failAfter(n)`, `rejecting(status)`,
  `paging(n)`.
- **The preview is three passes and the middle one holds no database connection** (D-b): the gate
  in `ProjectService.readGranted`'s transaction, the credential-carrying HTTP read with none open,
  then `assemble` in a read-only `TransactionTemplate`. **Not `@Transactional`** — it is a
  self-invocation the proxy does not see, and the annotation would be a boundary that silently is
  not there; `ReportJobRunner` is the precedent for the idiom.
- `ImportPreviewWritesNothingTest` is the module's done-when and the reusable helper: it lists
  company-owned tables from `information_schema.columns WHERE column_name = 'company_id'`, hashes
  each table with `md5(string_agg(t::text, '|' ORDER BY t::text))` as the **container superuser**
  (RLS would otherwise compare zero rows to zero rows), and carries its own not-vacuous test.
- `IssueRepository.findExistingKeys` is the one read there that **does not filter `deleted_at`** —
  `uq_issue_key` is `(company_id, issue_key)` with no predicate, so a deleted key is still taken.
- `ContractConventionTest.HOMONYMS` **is no longer empty**: `tracker.imports.SourceProject` is
  named for the `SourceProject` schema and `SourceProjectView` serves it.

- `identity.AssistantService` (015 T3) — **an assistant IS an `api_token` the caller owns carrying
  `Role.AGENT_ID`** (D26); there is no assistant table and no agent principal. `/me/assistants` needs
  **authentication only**, which is safe because `PermissionEvaluator` caps a token at the
  intersection of its role and its owner's grants. `ApiTokenService` grew two package-private
  ungated seams for it — `mintOwned` and `revokeOwned` — around the extracted `mint`/`withdraw`;
  **neither carries `@Transactional`**, deliberately: a non-public annotated method is a boundary
  the proxy may not honour, and every caller is already inside one.
- **`api_token_name_idx` is `(company_id, name)` among live rows**, so two developers cannot both
  call their assistant "claude-code". Surfaced as a field error through the shared validator rather
  than repaired — a per-owner name would be a `V9`.
- `project.ProjectRepo` / `ProjectRepoRepository` / `ProjectRepoService` (015 T3) — the entity is
  **`ProjectRepo`, not `ProjectRepository`**, which is this package's Spring Data interface for
  `Project`. `primary` is computed from the lowest `position` and never stored. Two gates:
  `project:read` to list, `project:settings` to write — `ProjectSettingsService`'s asymmetry.
  `forProject(projectId)` is the ungated read for a caller already past its own gate
  (`ProjectSettingsService.effective`'s shape); `tracker.agent` is its one consumer.
- **D33's close guard lives in two places and nowhere else.**
  `ReviewSettings.issueCloseNeedsSecondPerson()` is the project half (`reviewEnabledIssue &&
  !authorMayApproveOwnWork`) and `IssueService.closeGuardBinds(policy, issue, userId)` is the whole
  reading, **public** because `tracker.board` is a sub-package. Four consumers subtract `DONE`-kind
  targets from the workflow set — never replace it: `TransitionService` (409 `CloseRefused` +
  an `issue.close_refused` audit row), `IssueService.allowedTransitions`,
  `tracker.board.BoardCardService.Facts`, and `work.BoardService.cardFor`. `work` may not import
  `tracker`, which is why the rule's project half lives on `ReviewSettings` in `project`.
- `IssueService.viewOf` and `BoardCardService.factsFor`/`factsOf` **now take a `userId`** — the
  projection is caller-scoped, so a card and its detail page cannot offer different moves.
- `tracker.agent` (015 T3) — `AgentContextService` composes `IssueService.find` (x3),
  `DocLinkService.list` and `ProjectRepoService.forProject`, and **adds no gate of its own**: the
  scope property (a token scoped to project A gets nothing from B) holds because every part goes
  through `readVisible`. A doc link with no captured excerpt is **dropped**, not served empty.
- `AuditEvent.jsonString(null)` **throws** (it reads the String's length). A nullable value in a
  changes document is the JSON literal `null`; 015 T3 shipped the NPE and its test caught it as a
  500 out of the audit writer on a PATCH about something else.

## Conventions observed
- A schema pairs with a **view record**, never an entity — `Label` → `LabelView`, not `IssueLabel`.
- Records for views/requests, sealed `Outcome` interfaces per service, exhaustive switches in
  controllers. Decisions and their reasons live in Javadoc; superseded reasoning is struck through
  and kept. Every `Problem.errors` value is a sentence.
- Contract pairing: `ContractConventionTest.RESPONSE_RECORDS` / `INLINE_RECORDS` need an entry
  per new schema / inline object. `SeedDataTest` pins the catalogue size (56 since V71).

## Unit-test surface
- Almost everything is `@SpringBootTest` + Testcontainers (`IntegrationTestBase`); pure unit tests
  exist for arithmetic seams (`TokenRateLimiterTest`, `SignInLockoutTest`'s first two methods).
  Fixture UUIDs use a per-class prefix; check `grep` before claiming one. Taken by 012 T3: `b4`,
  `b5`, `b6`. **The whole `b0`–`be` space is now taken**; 012 T4 took `bf1`/`bf2`/`bf3` under `bf`,
  which was the last free two-nibble prefix in `b`.
- `TrackerEndpointTestBase` is **public** since 012 T3 — company / user / membership / project
  grant / `tcms_seed_project_defaults`, reusable from `com.tcms.tracker.board`. Any class needing
  `mvc` also needs `@AutoConfigureMockMvc`, including one that only autowires the base.
- **A test calling a service in-process must open the company scope itself** —
  `try (var ignored = CompanyContext.open(companyId))`. Without it every RLS read is empty and the
  gate answers "no such project"; nine of T3's tests failed that way and all read as an
  authorization defect.
- **A fixture restoring a workflow deletes the project's `board` row FIRST.**
  `tcms_seed_project_defaults`' board block is guarded by `NOT EXISTS (SELECT 1 FROM board …)`, and
  deleting `issue_status` rows takes the columns with them via `board_follows_status_removal` — so
  "delete statuses, re-seed" silently leaves a board with zero columns.
  `WorkflowEndpointTest.restoreWorkflow` has done exactly this since T2 (012 D-p).

## Known unknowns
- Whether `SignInThrottle` keyed on `getRemoteAddr()` behaves behind the lab ingress (prod sets
  `forward-headers-strategy: framework`).
- ~~`ContractConventionTest` red for T4's twelve schemas and `ContractParameterBindingTest` for its
  nine operations~~ → **both green after 012 T4.** ~~The measured gate is now **6 failures, all
  `FlakeScoreTest`**, red on `master` since before 012 T1 and owned by no task in that plan. A
  seventh failure is yours.~~ → **014 T3's gate: 3790 tests, 4 failures, and `FlakeScoreTest` was
  not among them.** So those six are intermittent rather than standing red, and the rule of thumb
  is now the other way round: the same two contract suites are red across 014 T1→T3 naming ONLY
  T4's surface (T3 step 11 lists the four), and anything else is yours.
- Nothing compares a handler's status codes to the ones `openapi.yaml` declares. T4's label
  endpoints answer 400 on a blank name, which the contract does not list — invisible to every gate.

## Session log


- 2026-09-03 015 T3: `be`. The M5 backend. `identity`: `AssistantService` +
  `MeAssistantsController` + three records, and `ApiTokenService` split into gated and ungated
  halves. `project`: `ProjectRepo` and its five companions, plus `reviewEnabledIssue` threaded
  through `ProjectSettings`/`View`/`Request`/`Service` and **`ReviewSettings` as a ninth component
  that is NOT a `ReviewSubjectType`**. `tracker`: `agent_eligible` + `pr_url` on `Issue` and its
  four projections, the `agentEligible` list filter (tri-state, switched by a boolean like the
  collection filters), `TransitionService.Outcome.CloseRefused`, the caller-scoped
  `allowedTransitions` in three assemblies, and the `tracker.agent` sub-package. `work`:
  the fourth assembly. Five test classes (`MeAssistantsTest` `e5`, `ProjectRepoTest` `e6`,
  `AgentCloseRefusalTest` `e7`, `AgentContextScopeTest` `e8`, plus four tests on
  `IssueEndpointTest`), 45 new tests. Three things cost a round trip: **`Role.COMPANY_ADMIN_ID` is
  COMPANY scope and `tcms_role_assignment_scope_shape` refuses a project-scoped grant of it** (use
  Test Lead, which holds `project:settings`); **the seeded `Task` type is STORY level** (V3 §592) —
  the TASK-level type is `Sub-task`; and the `Criterion` schema is a test plan's EXIT criterion, so
  `AgentStoryContextView.Criterion` needed a `HOMONYMS` entry.


- 2026-09-02 014 T3: `be`. `com.tcms.tracker.imports` up to the preview — the `ImportSource` port
  and its nine records, `ImportUrlGuard` + its mirror test, `ImportHttp`, `JiraImportSource`
  (Cloud and DC behind one class, two auth headers), `DroppedFields`, `ImportPreviewService`,
  `ImportController`'s two read operations, `FakeImportSource`, and five test classes. One ArchUnit
  case naming R14. `tcms.tracker.imports` added to `application.yml`, three keys, all defaulted.
  Gate on shanks: 3790 tests, **4 failures, all naming T4's surface** — seven unpaired object
  schemas, `ImportRun.startedBy`, `IssueComment.externalAuthor`, and the three operations no
  handler serves. T3's step 11 predicted green and was wrong; the plan is corrected in place and
  T4's prompt now opens with the four.
- 2026-09-01 013 T3: `be`. `com.tcms.tracker.release` — 28 files: `Release` + `VerificationLink`
  (over `issue_link`, and **not** called `IssueLink`: `documentation.IssueLinkView` is the external
  Jira link and two types of that name one import apart is how a wrong import survives review),
  three repositories, four services (`ReleaseService`, `ReadinessService`, `RoadmapService`,
  `VerificationService`), three controllers, `ReleaseFacts`, and seventeen view/input records.
  `ReleaseSortKey` moved to `api`. `Issue`, `IssueEdit`, `IssueInput`, `IssueUpdate`, `IssueView`,
  `IssueSummaryView` and `BacklogRowView` all gained a release; `SwimlaneBy` gained `RELEASE` and
  `BoardCardService` its lane arm and two filter members; `IssueFactProjection` now writes
  `release_id` and `release_assigned_at`. Three test classes, 27 tests.
  **Gate on shanks: 3751 tests, BUILD SUCCESSFUL.** It first reported 6 failures, `FlakeScoreTest`
  only — inherited from 012 T4, and not a baseline to carry but a countdown that had gone off:
  `flake-window` is `P90D` measured back from `now()`, and that fixture's `BASE` was a hardcoded
  `2026-06-01`, so on 2026-08-30 its oldest three results aged out of the window and six assertions
  went three counts short at once. `BASE` is now relative to `now()`; the reasoning is in its
  javadoc. `flake-window` is the only DURATION window in `application.yml`
  (`failing-repeatedly-window` is a count of runs and `CaseHistoryService` has none), so no sibling
  fixture carries the same bomb. Six of this task's own failures were found and fixed first, and two are
  worth carrying: `Map.of().get(null)` throws (above), and `SchemaCommentTest` reads the *text* of
  every `COMMENT ON` and refused `sprint_issue` in a sentence explaining that no such table exists —
  a comment naming a thing to deny it is still a comment naming it.
  **No screen renders any of this**; the releases screens are T5 and the overview panels T6.
- 2026-08-31 012 T4: `be`, two commits. `com.tcms.tracker.board` gains four services
  (`RankService`, `BoardCardService`, `BoardMoveService`, `LabelService`), four controllers, two
  label entities + repositories and eleven view/input records — the nine T4 operations and the
  twelve T4 schemas. `Issue` gains a mapped `rank` and a `rankAt` mutator that stamps no
  `updated_at` (reordering a backlog is not an edit to the issues in it). `Completion`,
  `CountedIssue` and `EpicStatus` widened to **public** so a lane roll-up and an epic's board status
  go through D8's and D31's one implementation rather than a second one (D-s). Three decisions
  recorded: **D-q** — `openapi.yaml` contradicts itself on "neither neighbour", the 400 arm wins,
  the `afterKey` "means to the top" sentence is stale and was NOT edited because that regenerates
  both schema copies and is `ts` work; **D-r** — an Epic is a board card (derived status, empty
  `allowedTransitions`) and not a backlog row; **D-s** — the widening above. Gate on shanks:
  **3726 tests, 6 failures — `FlakeScoreTest` only**, so both contract suites cleared as planned.
  **No screen renders any of it**; the board is T7 and the backlog T8.
- 2026-08-31 012 T3: `be`, two commits. `com.tcms.tracker.board` — a **sub-package**, not a module,
  so `allowedDependencies` and `FOUNDATION_MODULES` are untouched and ArchUnit is green. Three
  entities (`Board`, `BoardColumn`, `BoardColumnStatus` + its `@IdClass`), three repositories, five
  view/input records, `SwimlaneBy`, `BoardConfigService` and `BoardConfigController` over
  `GET|POST /projects/{id}/boards` and `GET|PATCH|DELETE /boards/{id}`. `WorkflowService` gains one
  dependency and one call: every status it creates is claimed on every board of the project before
  the removals, flushed first because `board_column_status.status_id` is an FK and Hibernate orders
  inserts by entity type. **`BoardSwimlane` deliberately not written** — nothing writes
  `board_swimlane` until a screen does, and `everyEntityIsWritableByTheProduct` fails an entity
  whose table the product cannot put a row in. Contract pairings added for five schemas and the
  `SwimlaneBy` enum. Gate on shanks: 3703 tests, 8 failures — the two contract suites naming only
  T4's surface, plus `FlakeScoreTest`'s six. **No screen renders any of it**; cards, rank and labels
  are T4 and the board is not drawn until T7.
- 2026-08-31 011 T4: `be`, two commits. **Part A** — `documentation.LinkSourceType` gains `ISSUE`
  (no migration: `V2` widened `doc_link`'s CHECK ahead of it) and `CatalogueFacts` a third foreign
  table; `tracker` gains `issue_doc_link` as a projection rebuilt whole per company by
  `IssueDocLinkFacts` + `DocLinkProjection` on the **existing** `documentation.links_changed` event
  (second claimant, beside `repository.HealthRecomputeOnLinksChanged`), plus comments, watchers and
  `/issues/{key}/{comments,watchers,doc-links}`. **Part B** — `work_item` retires: `BoardFacts`
  gains `assignedIssues` + `legalMoves` (crossings 5 and 6, first into `tracker`), `BoardService`'s
  `ISSUE` generator replaces `TASK`, every card's status is `CardStatus(name, kind)`,
  `WorkItemController` → `BoardController` at `GET /board`, nine classes deleted, `V4` drops the two
  tables and re-issues four `COMMENT ON`s, policy count 84 → 82. New: `AnnotationController` over
  `derived_item_annotation`. `ItemAssignedEvent` moved to `tracker` with its producer.
  Gate on shanks: 3680 tests, 6 failures — `FlakeScoreTest`'s six, pre-existing since before T1.
- 2026-08-30 011 T1: contract-only. `/work-items` (+ its POST/PATCH) removed; `GET /board`,
  `PUT /board/annotations/{sourceType}/{sourceId}`, `/issues*` and `/projects/{id}/workflow*`
  added under a new `tracker` tag. `WorkItem`/`WorkItemStatus`/`WorkItemInput`/
  `WorkItemStatusUpdate` schemas gone; `BoardCard.status` is now `{name, kind}`. Both generated
  clients regenerated. **Tree does not build** — `frontend`/`packages` still reference the
  removed types (T4/T5/T6/T7 repair), and `ContractConventionTest` has no pairing entry for the
  ~19 new schemas.
- 2026-08-28 B2: Problem members, V71, doc:review, ReviewImpactSource, org-chart gaps,
  /me/password + sign-out-others, sign-in lockout. Built and unverified.
