<!-- orientation-cache v1 -->

# be-java — orientation cache (TCMS)

**Last verified:** 2026-08-02
**Baseline commit:** `37dc3a6`
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

Spring Modulith **monolith**; modules are *packages* under `com.tcms`, not Gradle subprojects:
`tenancy · identity · project · repository · execution · documentation · reporting · work ·
provisioning · auth · shared`.

- Cross-module FKs allowed **only** toward `tenancy` / `identity` / `project`; everything else
  crosses via domain events. **Enforced by ArchUnit** (`src/test/java/com/tcms/architecture/`), so
  the boundary is machine-checked. `#confirmed`
- `tenancy` is depended on by everything and depends on nothing.
- No Domain/Application/Infrastructure layering — vertical slices per module, with
  `<X>Controller` / `<X>Service` / `<X>Repository` inside each.

## Seams to reuse

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
  at `src/main/resources/db/migration`. **Head is `V21`; `V22` is the next free number.** Booting the
  real context in a test is itself a schema-drift gate. `#confirmed`
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
  (port, `execution`) + `FilesystemEvidenceStore` (adapter, `tcms.storage.evidence.root`), per open
  question #28. **`EvidenceService` writes ROWS FIRST, BYTES SECOND** in both directions, because a
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
- **The PLAN has a LIFECYCLE since §73, and it is the only writer `test_plan.status` has ever had
  besides the factory.** `PlanLifecycle` (pure, static — the transition table, `permissionFor`,
  `isClosed`) + `PlanStatusService` + `PlanDeleteService` + `PUT /plans/{planId}/status` +
  `DELETE /plans/{planId}`. **The gate is decided by the EDGE, not the target**: `plan:approve`
  grants the sign-off, ends the plan and **takes either back**, `plan:edit` carries it forward
  through the states nobody signed off — a target-keyed rule lets a Test Engineer rescind a lead's
  approval by asking for `DRAFT`, and reddens nothing else in the suite. Hence
  `ProjectService.writeGrantedOnTarget` (§69's seam, second consumer): the deciding fact is the
  STORED status. **The ladder does not require approval** — §6g says review is optional and off by
  default and that setting does not exist, so `DRAFT → ACTIVE` is legal; `APPROVED` is reachable only
  from `IN_REVIEW` and `CLOSED` only from `ACTIVE`. **Delete and close partition the space**: the
  delete refuses 409 + count while a live run names the plan (`findPlanKeys` is not filtered on
  `deleted_at`, so nothing breaks loudly), and `CLOSED` is the answer for a plan that WAS executed.
  `closed_at` and `updated_by` are mapped for the first time; `applyStatus` derives `closed_at` in
  both directions and deliberately does **not** bump `currentVersionNo`. `plan:review` stays
  unconsumed on purpose — §6g puts peer review inside `IN_REVIEW` without moving the item, like
  `case:review` and `suite:review`. `#confirmed`
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
  listing the operators twice. **A CLOSED plan refuses the edit (409) and that is
  `PlanLifecycle.isClosed`'s first consumer** — §73 wrote it and `TestPlan.applyStatus` compares the
  enum inline — placed after the permission, before the `If-Match`, before validation (§72's order).
  `#confirmed`
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
- **`ContractConventionTest.requiredPropertiesHaveAComponent`** resolves a schema's `required`
  through `allOf` and checks the serving record has a component for each. Added in §68 after
  `TestCaseDetailView` was found to have omitted `createdBy` since §66 — which made the case
  detail's `OWN`-qualified Delete refuse every caller their own case. `RESPONSE_RECORDS` is a short
  explicit map; add a pair when a new view backs a contract schema. `#confirmed`
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

- **Four review-workflow permission keys still have no consumer in Java** — `case:review`,
  `suite:review`, `plan:review`, `case:comment` — and §73 recorded that this is **correct rather than
  an omission**: §6g puts peer review *inside* `IN_REVIEW`, accumulating on the item without moving
  it, so those keys belong to comments/LGTM/request-changes, which do not exist. `review_comment` and
  `review_participation` are two of the eleven tables with schema and no code. The per-project
  review opt-in setting §6g requires does not exist either, and `PlanLifecycle`'s header leans on its
  absence to justify `DRAFT → ACTIVE`. **This is the largest coherent gap left in this module family**
  and the "permission with no consumer" search will keep pointing at it. `#confirmed`

- **`audit_event.target_type` is spelled two ways and nothing reads it yet.** Every service writes
  the snake_case table name (`test_run`, `test_case`, `test_plan`, `doc_page`, …) except
  `ExecutionService`'s `"TEST_EXECUTION"` and `ArtifactService`'s `"ARTIFACT"`. Latent rather than
  live — `audit:view` and `project:audit` both have **zero consumers** — and found-not-fixed in §72,
  because changing it splits existing rows. It will present as an audit filter that silently matches
  nothing, which is §45's absent-row family. Sweep it when the audit screen is built. `#confirmed`

## Session log

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
- 2026-08-02 · EXTEND · Built the shared-step library (§64), M1.5's other half. New: `SharedStep`
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
