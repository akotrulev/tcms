<!-- orientation-cache v1 -->

# be-java — orientation cache (TCMS)

**Last verified:** 2026-08-01
**Baseline commit:** `a52fcab113125d855179a3080c26a4b97b54a8a0`
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
- **`Clock.systemUTC()` on macOS yields only microsecond precision**, so any defect that depends on
  sub-microsecond nanoseconds is invisible to a local run and fails only on Linux CI. This is not
  hypothetical — see `docs/handoff/58-*.md`. `#confirmed`

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
  at `src/main/resources/db/migration`. **Head is `V18`; `V19` is the next free number.** Booting the
  real context in a test is itself a schema-drift gate. `#confirmed`
- **Privilege split** — Flyway migrates as `tcms_owner`, the pool connects as `tcms_app`, neither is
  a superuser and neither holds `BYPASSRLS`. Mirrored in three places that must agree:
  `deploy/postgres/initdb/10-tcms-roles.sql` (deployment),
  `src/test/resources/db/testinit/tcms-roles.sql` (suite), and the Hetzner runbook (which now
  *runs* the first file rather than carrying a copy — a copy drifted, see E1). `#confirmed`
- **One `Clock` bean**, `TcmsApplication.java:49`, injected into ~10 services rather than
  `Instant.now()` at call sites. `#confirmed`
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
- **Two tests are `@Disabled`** — `InvitationServiceTest.reRunLeavesTheLiveLinkAlone` and
  `UserInvitationEndpointTest.secondInviteIsUnchanged`. A real defect, not flakes: the app returns an
  in-memory `Instant` (nanoseconds) where Postgres returns the round-trip (microseconds). One-line
  fix at `TcmsApplication.java:49` — `Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))` — **not
  yet applied**. `#confirmed`
- `reporting` module is still MSW-only on the frontend; no backend implementation yet.

## Session log

- 2026-08-01 · EXTEND · Added the `issue-invitation` operator CLI + `InvitationService.reissue`, so a
  UI-raised invitation is deliverable on an instance with no SMTP relay. Created `OperatorCommands`
  to hold the verb list once. Cache created this run.
