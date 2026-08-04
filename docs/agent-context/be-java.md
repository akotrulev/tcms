<!-- orientation-cache v1 -->

# be-java — orientation cache (TCMS)

**Last verified:** 2026-08-04
**Baseline commit:** `3d33ad6`
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
  at `src/main/resources/db/migration`. **Head is `V26`; `V27` is the next free number.** Booting the
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
- **§6g's GATING RULES are the last part of the review workflow, and they live in `execution` (§82).**
  `V26` adds `require_approved_case_for_run` / `require_approved_plan_to_start` to
  `project_settings` (two columns, **no table — the policy count stays 58**), `ReviewSettings` gains
  two components, and the two readers are `RunCreateService.gate` and `PlanStatusService.gate`.
  **The plan rule binds on ONE EDGE, `DRAFT → ACTIVE`** — `PlanLifecycle.startsWithoutApproval` —
  and the target-keyed reading is a live trap: `ACTIVE` is also reached from `APPROVED` (the rule
  satisfied) and from `CLOSED` (**a reopen**), and refusing that makes every closed plan on a gated
  project **permanently un-reopenable**, since `CLOSED` has no legal move back to `APPROVED`. §72's
  `plan.reopened` / `plan.started` audit split had already defined *started* one slice before the
  gate needed it. **The case rule binds on the RESOLVED set** (`edit.caseIds()`, after the plan's
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
  `#confirmed`
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
  folded in** — `PlanStatus` has `ACTIVE`/`CLOSED`, an execution phase these four lack. Table:
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
  defect record, which nothing writes — a feature apiece, not a generator apiece), **§6l's
  cross-project "My Work" board**, so the inbox is **per project**; **§6l's per-project generator
  toggles**; and **notifications** — all four of §6g's events have a trigger and there is no channel. `#confirmed`

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
