<!-- orientation-cache v1 -->

# be-java — orientation cache (TCMS)

**Last verified:** 2026-08-02
**Baseline commit:** `f551971`
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
  at `src/main/resources/db/migration`. **Head is `V20`; `V21` is the next free number.** Booting the
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

## Session log

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
- 2026-08-01 · EXTEND · Added the `issue-invitation` operator CLI + `InvitationService.reissue`, so a
  UI-raised invitation is deliverable on an instance with no SMTP relay. Created `OperatorCommands`
  to hold the verb list once. Cache created this run.
