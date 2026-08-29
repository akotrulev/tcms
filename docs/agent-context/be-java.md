# be-java — orientation cache

Baseline: commit after B1 (`3ba1555f`), refreshed 2026-08-28 during build batch B2.
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
- Flyway `backend/src/main/resources/db/migration` holds **one file**, `V1__initial_schema.sql`:
  V2–V75 were absorbed into it 2026-08-29 and deleted. ~~head `V71` (B2), `V72–V74` reserved~~ →
  superseded; the next number is **`V2`**. V1 only ever runs on an empty database.

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
- 2026-08-28 B2: Problem members, V71, doc:review, ReviewImpactSource, org-chart gaps,
  /me/password + sign-out-others, sign-in lockout. Built and unverified.
