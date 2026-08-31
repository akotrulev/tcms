# be-java — orientation cache

Baseline: 011 T4 (`937df5d4`), refreshed 2026-08-31.
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
  `V2__pm_foundation.sql`, `V3__tracker_core.sql`, `V4__retire_work_item.sql`. ~~head `V71` (B2)~~
  ~~the next number is `V2`~~ → the next number is **`V5`**. `out-of-order` is unset, so a lower
  number arriving after a higher one is silently not applied — `ls` the directory before writing.

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

## Conventions observed
- Records for views/requests, sealed `Outcome` interfaces per service, exhaustive switches in
  controllers. Decisions and their reasons live in Javadoc; superseded reasoning is struck through
  and kept. Every `Problem.errors` value is a sentence.
- Contract pairing: `ContractConventionTest.RESPONSE_RECORDS` / `INLINE_RECORDS` need an entry
  per new schema / inline object. `SeedDataTest` pins the catalogue size (56 since V71).

## Unit-test surface
- Almost everything is `@SpringBootTest` + Testcontainers (`IntegrationTestBase`); pure unit tests
  exist for arithmetic seams (`TokenRateLimiterTest`, `SignInLockoutTest`'s first two methods).
  Fixture UUIDs use a per-class prefix; check `grep` before claiming one.

## Known unknowns
- Whether `SignInThrottle` keyed on `getRemoteAddr()` behaves behind the lab ingress (prod sets
  `forward-headers-strategy: framework`).
- `ContractConventionTest.everyObjectSchemaIsPairedOrExempt` is red for B3/B4's schemas until
  those batches pair them.

## Session log
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
