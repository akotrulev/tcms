# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read first

`docs/HANDOFF.md` is the living session handoff — current state, what is done, what is next, and the
lessons this project has already paid for. Read it before starting work; update it when you finish.

Authoritative documents, in precedence order where they overlap:

| Document | Authority over |
|---|---|
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | The permission catalogue, the six roles, grants, naming. The ERD defers to it. |
| `docs/specs/openapi.yaml` | The REST contract. Source of frontend types (`src/api/schema.d.ts`) and mock handlers. |
| `docs/specs/2026-07-30-tcms-erd.md` | Entity design. **§0.1 is the `company_id` rule and the RLS exception list** — the most important part. |
| `docs/plans/2026-07-30-tcms-high-level-plan.md` | The master design doc: §6a–§6v decisions, milestones M0–M6, open questions. Section references (`§6p`) throughout the repo point here. |
| `docs/audits/` | Audit evidence, including the first real execution of V1 and the Kiwi TCMS gap analysis. |

Design decisions in the plan state *the decision and its reason*. Several look arbitrary until you
read the failure mode they prevent — read the relevant section before changing behaviour it covers.

## Plugin skills are mandatory, not decorative

This project is built through the installed `fe-react`, `be-java`, `data`, `qa-java`, `devops` and
`business-analysis` plugin skills (see `.claude/settings.json`). The work so far follows their phased
pipelines; continuing without them produces inconsistent output.

- Frontend work → `fe-react:fe-react`, then load only the current phase's skills.
- Backend work → `be-java:be-java`, plus `be-java:be-java-database-code` for JPA and
  `data:data-postgresql` / `data:data-migrations` for schema and migrations.
- Never improvise a pattern a skill owns. If a listed skill is missing on disk, say so and proceed
  with general rules — never substitute invented guidance.

## Commands

The two stacks are independent. Gradle lives in `backend/`, npm in `frontend/`. **Nothing at the repo
root is buildable or runnable.**

```bash
# Backend — REQUIRES Docker; every test boots a real Postgres 17 via Testcontainers
cd backend
./gradlew build                                  # THE gate: compile with -Werror + all tests
./gradlew test
./gradlew test --tests 'com.tcms.tenancy.RlsIsolationTest'          # single class
./gradlew test --tests 'com.tcms.tenancy.RlsIsolationTest.methodName'  # single test
```

```bash
# Frontend — no backend needed; MSW intercepts /api/v1 in dev
cd frontend
npm install
npm run dev          # http://localhost:5173 — sign in with any password of 4+ chars
npm run build        # THE gate: tsc -b + vite build
npm run lint         # oxlint, must stay at 0 warnings — a gate, not advisory
npm run gen:api      # regenerate src/api/schema.d.ts after ANY openapi.yaml change
```

There is deliberately **no H2 fallback**: RLS, `ltree` and `citext` have no H2 equivalent, so an
in-memory lane would test a schema the product never runs on. If Testcontainers reports
`Could not find a valid Docker environment` while `docker ps` works, that is the Docker-29 API
version floor, already pinned via `systemProperty("api.version", …)` in `build.gradle.kts`.

`npm run gen:api` runs `openapi-typescript` through `npx` on purpose — the Vite scaffold pins
TypeScript 6 and the tool peer-requires 5.x. Do not "fix" this by downgrading TypeScript.

No frontend test runner is installed yet (Vitest + RTL is the not-yet-started Phase 4).

### Driving resilience behaviour (dev only, from the browser console)

None of the error/retry/session machinery can be triggered by using the app normally:

```js
tcmsFaults.status(503, 30)  // every request 503s with Retry-After
tcmsFaults.hang()           // requests never answer — proves the timeout budget
tcmsFaults.expire()         // next request 401s once — proves in-place session recovery
tcmsFaults.drift()          // 200 with the wrong shape — must read as FAILED, not empty
tcmsFaults.only('/cases')   // scope to one endpoint family
tcmsFaults.off()
```

Faults live in module state, so a page reload clears them — arm the fault *after* load when testing a
failed refetch. Caught client errors are readable at `window.__tcmsErrors` in dev.

## Architecture

A self-hosted, Java test case management system serving **multiple client companies from one
instance**. One deployable: Spring Boot REST API + React SPA (eventually served from the boot jar).
Not a test runner (it ingests results), not a defect tracker (it links out), not public multi-tenant
SaaS (companies are provisioned by an operator script).

The hierarchy is load-bearing and easy to get wrong: **Instance → Company** (a separate client
organisation; the isolation boundary) **→ Project** (a whole client product, *not* a feature area)
**→ suite tree**. "Payments" is a Feature or a suite folder, never a project.

### Tenancy — the invariant everything else rests on

`company_id UUID NOT NULL` on every company-owned table, including join tables and version tables
(where it is actually forgotten), leading every composite index and every previously-global
uniqueness constraint. Enforcement is mechanical, in three layers:

1. **`SchemaConventionTest`** — asserts the rule over every table not on the ERD §0.1 exception list,
   so a new table fails the build until it complies. Adding to the exception list requires a decision.
2. **`CompanyContext`** (`backend/.../tenancy/`) — a `ThreadLocal` exposed only as a **scope guard**
   (`try (var ignored = CompanyContext.open(id))`), never a setter, so a company cannot outlive its
   block. Deliberately **not** `InheritableThreadLocal`: an `@Async` handler or scheduled job must
   re-establish context from its event envelope's `companyId`. Populated from the authenticated
   session — **never** from a request parameter, header, or path segment.
3. **`CompanyAwareTransactionManager`** — hooks `doBegin`, so the Hibernate filter and the
   `app.current_company` GUC are set for *every* transaction, not just web requests. Postgres RLS
   with `FORCE ROW LEVEL SECURITY` is the backstop; the app connects as an unprivileged role
   (`tcms_app`) that owns nothing and holds no `BYPASSRLS` — `IntegrationTestBase` sets up that
   privilege split, and a suite that migrated *and* queried as the superuser would prove nothing.

Two RLS traps, both silent when wrong: the owner bypasses plain `ENABLE` (hence `FORCE`), and an
unset GUC must yield **zero** rows, not all rows — `company_id = NULL` is never true. **There are two
spellings of "unset" and only one is NULL:** a GUC that was never set reads NULL, but once
`set_config` has run on a session — including the transaction-local form the transaction manager
issues — its reset value is `''` for the rest of that session, so every *pooled* connection is in the
second state. `V4` wraps the read in `nullif(…, '')` for exactly this; without it a context-less query
on a warm connection raises `invalid input syntax for type uuid` instead of returning nothing.
`RlsIsolationTest` covers both spellings and `SchemaConventionTest` pins the predicate on all 56
policies; a green suite alone does not.

Async work is the other half: `CompanyContextTaskDecorator` clears the context on every task so a
handler that forgets fails closed, and `CompanyScopedEvent` is the only thing a handler may
re-establish from. `AsyncCompanyContextTest` covers ingestion, reporting, scheduling and event
handlers.

Access is two independent gates: company membership (authentication + zero project visibility), then
an explicit project grant. Three tiers — Instance Admin → Company Admin (a `COMPANY`-scope role
assignment, not a new tier) → regular user. Code checks **permissions, never role names**, and there
is no wildcard permission. SYSTEM-scope grants live in `instance_role_assignment`, a separate
instance-level table, because a nullable `company_id` under RLS is permanently unreachable.

### Backend

Spring Boot 3.5.6 + Java 21 + Gradle, rooted in `backend/`. Spring Modulith **monolith** whose
modules are Java *packages*, not Gradle subprojects: `tenancy · identity · project · repository ·
execution · documentation · reporting · work · shared`. Cross-module FKs are allowed only toward
`tenancy`/`identity`/`project`; everything else crosses via domain events, enforced by ArchUnit.
`tenancy` is depended on by everything and depends on nothing.

Flyway owns all DDL (`hibernate.ddl-auto: validate`, `baseline-on-migrate: false` so V1's RLS block
always executes). `V1__initial_schema.sql` is 64 tables / 56 forced RLS policies and its comments
carry the *why*. Booting the real context in a test is itself a gate — `validate` catches entity
drift at startup.

Java compiles with `-Werror`; the suppressed lints in `build.gradle.kts` each have a recorded reason.

### Frontend

Vite 8 + React 19 + TypeScript strict + MUI v7 (with X DataGrid/TreeView). **No Next.js, no RSC** —
so no `'use client'` boundary exists and the skills' RSC lane does not apply. React Router 7,
project-scoped routes under `/projects/:projectId/*`.

- **TanStack Query owns all server state.** No server data in any client store. `src/api/queries.ts`
  is the single data module — every read/write hook, one `keys` factory, `enabled` gates, per-endpoint
  timeout budgets.
- **Client state:** session in Context; **filter state lives in the URL** (`useSearchParams`).
- **Styling:** MUI `theme` + `sx` as the single authority. No Tailwind. Tokens are CSS custom
  properties in `index.css` bridged into `createTheme`.
- **`src/app/Providers.tsx` nesting order is load-bearing** and documented in the file: the query
  client is constructed *innermost* because its global error handlers consume the notification and
  session-recovery providers.
- **Mock API is stateful** (`src/mocks/db.ts` + `handlers.ts`) — handlers read *and write* a cloned
  fixture store, so create/edit/result round-trips land in the list. Fixtures deliberately carry the
  awkward rows the UI must handle. Resets on reload.
- **Errors are centralized:** `lib/describeError.ts` is the one place an error becomes user-facing
  copy (and owns `isTransient()`); `app/queryClient.ts` is the one place a failure raises a
  notification; `components/ErrorBoundaries.tsx` provides the root/route/region tiers.

**New display logic goes in the shared layer, not the page**: `lib/labels.ts` for enums (each
`satisfies Record<Union, string>`, so a new enum member is a compile error until labeled),
`lib/format.ts` for formats, `lib/results.ts` for count arithmetic, `lib/permissions.ts` for the
permission catalogue, `DataRegion` for the loading/loaded/failed/empty/stale matrix, `Identifier` for
anything monospace. A second implementation of any of these is how a hundred consistent pages become
a hundred slightly different ones.

## Git workflow

- **Work on the current branch. Do not create a new one.** Feature work continues on whatever branch
  is already checked out — including `master` — rather than branching per task. Check with
  `git rev-parse --abbrev-ref HEAD` rather than assuming; a concurrent session may have merged and
  switched branches since the last handoff was written.
- **Commit at the end of each phase**, not once at the end of the whole slice. A phase's commit lands
  after its gate passes (`./gradlew build` / `npm run build` + `npm run lint` + the browser pass where
  the phase's work is visual), so each commit is a state that was actually verified.
- **Concurrent sessions happen here** — `docs/HANDOFF.md` §10 records a second strand editing
  `docs/plans/` mid-session. Stage only the files your phase touched (`git add <paths>`, never
  `git add -A`), so another author's in-flight edits don't get interleaved into your diff. Re-read a
  shared doc before editing it.

## Working agreement

- **`openapi.yaml` is the contract.** Change it there, regenerate, then adapt both sides in one
  commit. Read it before coding against a shape — don't guess. **If you add a schema, add its
  `required` array**, including on inline nested objects: `required` does not descend, and without it
  every generated property is optional and no consumer can use it.
- **Verify with `npm run build`, not `tsc --noEmit`** — the latter has reported 0 errors while the
  build failed with nine. Then **run the app.** The gates prove it compiles, not that it works: a
  no-op theme toggle, two contradictory percentages on one screen, and a provider-ordering bug that
  rendered nothing but the error page all typechecked and linted clean.
- **A schema-wide or catalogue-wide invariant needs a computed check, not a careful reading.**
  Reading V1 top to bottom missed five join tables without `company_id`; scripting the comparison
  found them. Same family: derive "holds every permission" from the catalogue rather than storing a
  `'*'` sentinel.
- **Build the fault switch before the feature it proves.** A resilience behaviour you cannot trigger
  is one you have not tested.
- **Measure bundle changes before believing them.** Splitting DataGrid into its own chunk looked
  obviously right and made total JS ~40% worse by defeating tree-shaking.
- **Write superseded decisions *and* their reversal into the docs**, struck through with
  "→ superseded", so the next reader doesn't think the old reasoning was never considered.
- Save generated `.md` artifacts into this project under `docs/plans/`, `docs/specs/`,
  `docs/audits/` — never to a global directory.
