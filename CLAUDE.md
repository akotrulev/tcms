# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read first

**`docs/agent-context/be-java.md` and `docs/agent-context/fe-react.md` come before everything else.**
They are the orientation cache (`ep-agent-orientation-cache`): version lane, layout, and the **seam
list** — what each shipped slice extracted, why, and the defect it prevents. Read the relevant one
before the handoff narrative and before the raw DDL: the narrative records what a slice *did*, the
cache records what the code *is*. Check its `Baseline commit:` header against `git log` and read the
delta — a lagging header is normal, so **trust the body over the header** and re-grep anything a
later commit could have moved. **Update it in the slice's own commit.**

`docs/HANDOFF.md` is the living session handoff — current state, what is done, what is next, and the
lessons this project has already paid for. Read it before starting work; update it when you finish.

**It is an index.** Each section lives in `docs/handoff/NN-slug.md`, numbered by its § number, so a
reference anywhere in this repo (`§6 lesson 5`, `HANDOFF §14`) resolves by the filename prefix.
**Finishing a slice means adding a file and one index row — never appending to `HANDOFF.md`.**

Authoritative documents, in precedence order where they overlap:

| Document | Authority over |
|---|---|
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | The permission catalogue, the six roles, grants, naming. The ERD defers to it. |
| `docs/specs/openapi.yaml` | The REST contract. Source of frontend types (`src/api/schema.d.ts`). |
| `docs/specs/2026-07-30-tcms-erd.md` | Entity design. **§0.1 is the `company_id` rule and the RLS exception list** — the most important part. |
| `docs/plans/2026-07-30-tcms-high-level-plan.md` | The master design doc: §6a–§6v decisions, milestones M0–M6, open questions. Section references (`§6p`) throughout the repo point here. |
| `docs/audits/` | Audit evidence, including the first real execution of V1 and the Kiwi TCMS gap analysis. |
| `docs/plans/2026-07-31-parallel-execution-rework.md` | What may run in parallel and what may not, the Flyway numbering rule, and the second-lane stack. Read it before starting concurrent work. |

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

The two stacks are independent. Gradle lives in `backend/`, npm in `frontend/`. **Neither stack
builds from the repo root** — the only thing the root runs is `docker compose`, which orchestrates
the *system* and is therefore the one thing that legitimately spans both.

```bash
# Local stack — Postgres 17 with the real two-role privilege split, + the backend image
cp .env.example .env         # required: compose fails fast rather than defaulting a password
docker compose up --build    # http://localhost:8080/actuator/health
docker compose down -v       # -v also drops the volume, so initdb re-runs the role script

# The operator CLI is the same image
docker compose run --rm backend provision-company --slug=acme --name="Acme" \
  --admin-email=ops@acme.test --admin-name="A. Admin"
```

The frontend is still **not** in compose, but ~~`npm run dev` with MSW needs no backend~~ →
**superseded 2026-08-06: there is no mock API.** The MSW layer (`src/mocks/`, ~9,300 lines of
handlers and fixtures) was early-phase scaffolding that let the SPA be built before the API
existed; with the API complete it was deleted outright, along with `VITE_API_MODE` — there is no
mode to choose any more. `npm run dev` proxies `/api` to `localhost:8080` and **requires
`docker compose up` at the repo root plus a provisioned company**. Sign in by redeeming the
`/accept-invitation` link `provision-company` prints; there is no "any password of 4+ chars".
Point it elsewhere with `VITE_API_PROXY_TARGET`. **After changing `.env.local`, kill vite and
`rm -rf node_modules/.vite`**: an in-place restart leaves a stale optimize-deps hash and the page
renders blank with an empty console.

```bash
# Backend — REQUIRES Docker; every test boots a real Postgres 17 via Testcontainers
cd backend
./gradlew build                                  # THE gate: compile with -Werror + all tests
                                                 # CI DOES NOT RUN THIS — see below. Running it
                                                 # locally before pushing is now the only gate.
./gradlew test
./gradlew test --tests 'com.tcms.tenancy.RlsIsolationTest'          # single class
./gradlew test --tests 'com.tcms.tenancy.RlsIsolationTest.methodName'  # single test
```

```bash
# Frontend — REQUIRES the backend running (docker compose up) since 2026-08-06; no mock lane
cd frontend
npm install
npm run dev          # http://localhost:5173 — proxies /api to localhost:8080
                     # sign in by redeeming the /accept-invitation link provision-company prints
npm run build        # THE gate: tsc -b + vite build
npm run lint         # oxlint, must stay at 0 warnings — a gate, not advisory
npm run test:run     # Vitest + RTL, 954 tests — also a gate
npm run gen:api      # regenerate src/api/schema.d.ts after ANY openapi.yaml change
```

> **CI runs no backend tests** (changed 2026-08-02, for push-feedback speed: ~327s → ~60s).
> `.github/workflows/ci.yml` runs `./gradlew build -x test`, so `-Werror` and the Modulith wiring
> are still checked and nothing else is. ~~There is no nightly lane either.~~ → **one is written
> as of 2026-08-06 and is not yet live**: `.github/workflows/tests.yml` runs the full
> `./gradlew build` nightly on `[self-hosted, tcms-lab]` — the homelab box `shanks`, which is a
> *Linux* observer and therefore a differently-shaped gate, not merely a free one. **It is inert
> until that runner is registered** (`deploy/shanks/install-runner.sh`), so until then the
> sentence below still holds in full. **`./gradlew build` on your own machine is the only thing
> that runs the 1000+ integration tests** — run it before you push, because nothing downstream
> will.
>
> Know what your machine cannot see. On 2026-08-01 master was red for seven consecutive CI runs on
> a defect that **cannot fail on macOS**: the app returns an in-memory `Instant` (nanoseconds) where
> Postgres returns the round-trip (microseconds), and macOS's `Clock.systemUTC()` already yields
> only microseconds. Every local gate was green; CI was the only observer. Anything sensitive to
> Linux-vs-macOS now reaches master unobserved. `docs/handoff/58-the-first-real-deployment.md` has
> the full account, and `docs/plans/2026-08-01-tcms-homelab-target.md` §9 is the standing proposal
> for getting a Linux gate back.

There is deliberately **no H2 fallback**: RLS, `ltree` and `citext` have no H2 equivalent, so an
in-memory lane would test a schema the product never runs on. If Testcontainers reports
`Could not find a valid Docker environment` while `docker ps` works, that is the Docker-29 API
version floor, already pinned via `systemProperty("api.version", …)` in `build.gradle.kts`.

`npm run gen:api` runs `openapi-typescript` through `npx` on purpose — the Vite scaffold pins
TypeScript 6 and the tool peer-requires 5.x. Do not "fix" this by downgrading TypeScript.

~~No frontend test runner is installed yet (Vitest + RTL is the not-yet-started Phase 4).~~ →
**superseded.** Vitest + RTL are installed and green: 954 tests across 70 files, `npm run test:run`.
Config in `frontend/vitest.config.ts`, shared helpers in `src/test/`.

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

**These now fault the real backend.** They used to be an MSW handler, so they could only be driven
against the mock — the one place the failures they simulate could not actually occur. Since the
mock layer was deleted (2026-08-06) the seam sits in front of `fetch` in `src/api/client.ts`, fed
by `src/lib/devFaults.ts`, and everything downstream of the injected response is the production
path. **Both guards are a literal `import.meta.env.DEV`** so Rollup drops the module from a
production build; route that check through an imported constant and it ships, silently, exactly as
the mock layer once did as a 485 kB chunk. `deploy.yml`'s `web` job greps `dist/` for `tcmsFaults`
to catch the regression.

## Architecture

A self-hosted, Java test case management system serving **multiple client companies from one
instance**. One deployable: Spring Boot REST API + React SPA (eventually served from the boot jar).
Not a test runner (it ingests results), not a defect tracker (it links out), not public multi-tenant
SaaS (companies are provisioned by an operator script).

The hierarchy is load-bearing and easy to get wrong: **Instance → Company** (a separate client
organisation; the isolation boundary) **→ Project** (a whole client product, *not* a feature area)
**→ suites**. "Payments" is a Feature or a suite, never a project. **A suite is FLAT since §98** —
a named collection whose members are three arms (filed ∪ added ∪ tagged), not a folder in a tree.

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
   (`tcms_app`) that owns nothing and holds no `BYPASSRLS`. **Neither role in the test harness is a
   superuser either**: `IntegrationTestBase` creates a non-superuser `tcms_owner` *before* Flyway via
   `src/test/resources/db/testinit/tcms-roles.sql`, mirroring the deployment's ordering, and
   `PrivilegeSplitTest` pins every property of the split. The container's superuser is used only for
   ground truth in assertions. A suite that migrated as a superuser proves nothing — it had one, and
   that is precisely how V7's zero-rows-in-production defect got past 171 green tests.
   **The split is a deployment concern, not a test fixture**: `deploy/postgres/initdb/10-tcms-roles.sql`
   creates both roles for compose and the Hetzner runbook does the same on the VM, while
   `application.yml` gives Flyway its own `DB_MIGRATION_USER` so migrations run as the owner and the
   pool never does. Collapsing them into one role is the tempting simplification and it hands the
   application `DROP POLICY` on all 56 tables.

Two RLS traps, both silent when wrong: the owner bypasses plain `ENABLE` (hence `FORCE`), and an
unset GUC must yield **zero** rows, not all rows — `company_id = NULL` is never true. **There are two
spellings of "unset" and only one is NULL:** a GUC that was never set reads NULL, but once
`set_config` has run on a session — including the transaction-local form the transaction manager
issues — its reset value is `''` for the rest of that session, so every *pooled* connection is in the
second state. `V4` wraps the read in `nullif(…, '')` for exactly this; without it a context-less query
on a warm connection raises `invalid input syntax for type uuid` instead of returning nothing.
`RlsIsolationTest` covers both spellings and `SchemaConventionTest` pins the predicate on all 56
policies; a green suite alone does not.

**A repository interface must carry `@Transactional(readOnly = true)`.** Isolation is established in
`doBegin`, so it needs a transaction — and Spring Data gives one only to the CRUD methods *inherited*
from `SimpleJpaRepository` (`findById`, `count`, `save`), never to the derived and `@Query` methods
*declared on the interface*. Without the annotation those run outside any transaction, so
`app.current_company` is never set and every RLS-protected read returns **zero rows** while
`CompanyContext` holds the right company. Fail-closed, and it silently emptied the permission
evaluator. `RepositoryTransactionTest` reproduces it and derives the repository list from the live
context, so a new repository that forgets fails the build.

Companies are created by exactly one path: `CompanyProvisioningService` (the `provisioning` module),
driven by `java -jar tcms.jar provision-company`. It is idempotent — re-running is the supported way
to top up a company after a release adds a vocabulary or a display-key counter — and it never
*updates*. Only it may call `tcms_seed_company_defaults` / `tcms_seed_project_defaults`.

Async work is the other half: `CompanyContextTaskDecorator` clears the context on every task so a
handler that forgets fails closed, and `CompanyScopedEvent` is the only thing a handler may
re-establish from. `AsyncCompanyContextTest` covers ingestion, reporting, scheduling and event
handlers.

Access is two independent gates: company membership (authentication + zero project visibility), then
an explicit project grant. Three tiers — Instance Admin → Company Admin (a `COMPANY`-scope role
assignment, not a new tier) → regular user. Code checks **permissions, never role names**, and there
is no wildcard permission. SYSTEM-scope grants live in `instance_role_assignment`, a separate
instance-level table, because a nullable `company_id` under RLS is permanently unreachable.

#### The permission matrix

**Authoritative source is `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A3** — this is a
convenience copy, also in `README.md`. If they disagree the spec wins and the copies are the bug.
55 permissions, all seeded by `V3__seed_permissions_and_roles.sql`; no migration has added one since.

Roles are ordered least-permissioned first; rows run broadest-held to narrowest. `✓` granted ·
`—` not granted · anything else is a qualifier that narrows the grant.

`PROJECT` scope:

| Permission | Viewer | Automation Service | Test Engineer | Test Lead | Company Admin | Instance Admin |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `case:read` · `project:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `case:comment` · `report:view` · `report:export` · `doc:read` · `artifact:read` | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `case:create` | — | quarantine only | ✓ | ✓ | ✓ | ✓ |
| `run:create` · `result:submit` · `evidence:attach` | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `suite:manage` · `case:edit` · `library:manage` | — | — | ✓ | ✓ | ✓ | ✓ |
| `case:review` · `suite:review` · `plan:review` | — | — | ✓ | ✓ | ✓ | ✓ |
| `plan:create` · `plan:edit` · `run:execute` | — | — | ✓ | ✓ | ✓ | ✓ |
| `defect:create` · `workitem:manage` · `doc:edit` | — | — | ✓ | ✓ | ✓ | ✓ |
| `case:delete` · `evidence:delete` | — | — | OWN | ✓ | ✓ | ✓ |
| `artifact:edit` | — | — | plan scope only | ✓ | ✓ | ✓ |
| `case:approve` · `suite:approve` · `plan:approve` | — | — | — | ✓ | ✓ | ✓ |
| `suite:delete` · `plan:delete` · `artifact:delete` | — | — | — | ✓ | ✓ | ✓ |
| `run:close` · `result:amend` · `doc:publish` | — | — | — | ✓ | ✓ | ✓ |
| `project:audit` · `project:settings` · `project:members` · `integration:manage` | — | — | — | ✓ | ✓ | ✓ |

`COMPANY` scope — answered from the SYSTEM + COMPANY terms only, so a PROJECT-scope role contributes
nothing here. `company:read` reaches every member through the non-assignable **Company Member**
baseline role (`V15`), not through the six:

| Permission | Every member | Company Admin | Instance Admin |
|---|:--:|:--:|:--:|
| `company:read` | ✓ | ✓ | ✓ |
| `company:settings` · `company:members` · `company:teams` · `company:vocabulary` · `company:idp` | — | ✓ | ✓ |
| `project:create` · `token:issue` · `audit:view` | — | ✓ | ✓ |

`SYSTEM` scope, Instance Admin only: `company:create` · `company:delete` · `company:suspend` ·
`user:manage` · `role:manage` · `system:settings` · `instance:audit`.

Four things that change how the table reads:

- **The six are the complete assignable set** — seeded, immutable, **no custom roles, not even
  clone-and-edit**. A client needing something between two tiers gets the higher one.
- **An API token never confers its own permissions; it *caps* its owner's:**
  `evaluate(ownerId, projectId) ∩ permissionsOf(token.roleId) ∩ [the token's project]`. Intersection,
  never union — so a token exceeds neither its role nor its owner, and demoting a person shrinks
  every token they issued. `OWN` narrows through it too (`PermissionQualifier.narrowedBy`): a
  company-wide `ALL` capped by a role's `OWN` is `OWN`.
- **`case:comment` is one key covering cases, suites and plans.** `suite:comment` / `plan:comment`
  deliberately do not exist, and `evidence:read` was rejected on the same grounds: a key seeded to
  nobody is a 403 no administrator can grant their way out of. Resolving a thread is *not*
  commenting — it costs the subject's `*:review`.
- **Author-cannot-approve-own-work defaults on.**

### Backend

Spring Boot 3.5.6 + Java 21 + Gradle, rooted in `backend/`. Spring Modulith **monolith** whose
modules are Java *packages*, not Gradle subprojects: `tenancy · identity · project · storage ·
repository · execution · documentation · reporting · work · shared`. Cross-module FKs are allowed
only toward `tenancy`/`identity`/`project`/`storage`; everything else crosses via domain events,
enforced by ArchUnit. `tenancy` is depended on by everything and depends on nothing.

**`storage` is a fourth foundation module and the newest (§125, decision #51)** — the `attachment`
row plus the two blob ports and their filesystem adapters. It exists because `execution` and
`reporting` both need bytes back *synchronously inside a request* and a download cannot be an event.
It is defensible as a foundation module only because it declares `tenancy` **alone**, holds no
domain concept, and answers a question the database cannot; `ArchitectureRulesTest` checks the first
of those from the code, because Modulith enforces a declaration and has nothing to say about that
declaration being widened. **A fifth foundation module is a decision, not an import.**

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
- ~~**Mock API is stateful** (`src/mocks/db.ts` + `handlers.ts`) — handlers read *and write* a
  cloned fixture store, so create/edit/result round-trips land in the list.~~ → **superseded
  2026-08-06: `src/mocks/` is deleted.** It was scaffolding for building the SPA ahead of the API,
  and it outlived its purpose — what it mostly bought by the end was a second implementation of the
  server's rules that could disagree with it (§52's `entries`/`runs`, §81's review cards, §98's
  suite arms: three defects that mock mode hid by agreeing with itself). Dev now runs against the
  real backend, so the round-trip being tested is the real one.
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
  after its gate passes (`./gradlew build` / `npm run build` + `npm run lint`; ~~+ the browser pass
  where the phase's work is visual~~ → **deferred to the verification queues since §65**, see the
  working agreement), so each commit is a state whose automated gates were actually run.
- **Concurrent sessions happen here** — `docs/HANDOFF.md` §10 records a second strand editing
  `docs/plans/` mid-session. Stage only the files your phase touched (`git add <paths>`, never
  `git add -A`), so another author's in-flight edits don't get interleaved into your diff. Re-read a
  shared doc before editing it.
- **A concurrency worktree is the one exception to "do not create a branch."** The rule above is
  about not branching per *task*. A second lane that touches the backend needs its own worktree,
  because one `backend/build/` and one Gradle daemon cannot serve two lanes. Say so before using it.

## Running work in parallel — don't

**Work is sequential.** Parallel lanes were tried three times (handoff §37, §41, §45) and
re-authorised once more on 2026-08-02 with a 4-agent cap, then reversed the same day. See
`docs/plans/2026-07-31-parallel-execution-rework.md` §0 for the incident that ended it: two agents in
one tree produced **three silent losses of committed work** — a rebase, an unrelated commit reverting
a doc as collateral, and a stale re-application wiping a later change — with no conflict, no error
and a clean `git status` throughout.

**One subagent at a time — ask before a second.** No agent may have more than one subagent running
concurrently. If a task genuinely needs two or more in parallel, say why it is safe (disjoint files,
no shared build directory, no shared contract or migration numbering) and **get the user's explicit
permission first**. Absent that permission, run them one after another. This is the same failure
mode as the lanes above: concurrent writers in one tree lose committed work silently.

The reworks that came out of those attempts shipped and are worth having on their own terms: the
per-section handoff, the split `api/queries/` and `mocks/` directories, and the second stack. (The
split `mocks/` directory went with the mock layer itself on 2026-08-06; the other two stand.)

**If a second checkout is ever genuinely needed** — the concurrency-worktree exception to "do not
branch per task" — these still bind, and are still not sufficient:

- **One contract holder.** `openapi.yaml` and `npm run gen:api` belong to exactly one lane;
  `schema.d.ts` is generated and two regenerations do not merge. Resolve the *contract*, then
  regenerate — never hand-merge the output.
- **Pre-assign Flyway versions, renumber at integration in landing order.** `out-of-order` is unset,
  so a `V13` landing after a `V14` is **refused on any already-migrated database**. Testcontainers
  cannot see this — it migrates a fresh container every time. Two lanes each adding a `V20__*.sql`
  produce two *filenames* and no conflict at all.
- **Pre-assign the fixture UUID prefix space.** Hand-managed, no check behind it, collided five
  times (§19 … §62). Two lanes take the same prefix and merge clean; `project_pkey` fails at runtime.
- **One writer for `docs/agent-context/*.md`.** Two agents of the same type write the same file.

A second stack for a concurrent browser pass is `cp .env.lane-b.example .env.lane-b`,
`docker compose --env-file .env.lane-b up`, and `npm run dev:lane-b` (6432 / 9080 / 6173).

## Working agreement

- **`openapi.yaml` is the contract.** Change it there, regenerate, then adapt both sides in one
  commit. Read it before coding against a shape — don't guess. **If you add a schema, add its
  `required` array**, including on inline nested objects: `required` does not descend, and without it
  every generated property is optional and no consumer can use it.
- **Verify with `npm run build`, not `tsc --noEmit`** — the latter has reported 0 errors while the
  build failed with nine. ~~Then **run the app.**~~ **→ deferred and batched since §65** (see below).
  The gates prove it compiles, not that it works: a no-op theme toggle, two contradictory
  percentages on one screen, and a provider-ordering bug that rendered nothing but the error page
  all typechecked and linted clean.
- **Browser passes and backend mutation checks are DEFERRED AND BATCHED — §65, standing policy.**
  Set 2026-08-02 at the user's direction: ship the remaining functionality first while requirements
  are still moving, then verify in one concentrated pass. **A slice still records what it would have
  driven and what it would have mutated**, appended to the matching queue —
  `docs/verification/frontend-browser-checks.md` and `docs/verification/backend-mutation-checks.md`
  — in a form that can be executed later. Landing without adding those rows skips the check
  outright, which is what batching exists to avoid. **Deferred means ALL of them, restated
  2026-08-02**: do not run "just the cheap one" or "just the highest-signal one" during a slice.
  §72–§76 each ran a handful inline and recorded them as `✅ RUN` in the queue, which made the
  exception look like the rule; it was not. Write the row, run nothing. **A slice in an unrun queue is *built and
  unverified*, and must be described that way rather than as done.**
  **The browser queue is ONE LIVE PASS of thirteen ordered lanes since §109** — there is no mock lane
  and no mode to choose. A new row goes in the lane whose *state* it needs, names the fixture the
  pass's own charter authors, and **keeps a stable id**: ids are quoted from handoff sections, so
  they are never renumbered. A row that needs data no lane creates must say what is missing and what
  the options are, because "not driven, and here is why" is a legitimate outcome and a silent skip is
  not.
- **A schema-wide or catalogue-wide invariant needs a computed check, not a careful reading.**
  Reading V1 top to bottom missed five join tables without `company_id`; scripting the comparison
  found them. Same family: derive "holds every permission" from the catalogue rather than storing a
  `'*'` sentinel. **And §108, the most expensive instance**: `${TCMS_JWT_SECRET:?…}` in
  `application.yml` looked like a hard requirement, was read by Spring as a *default*, and signed
  production's sessions with a string from this repo for a year — past a correct comment, a real
  `@Size(min = 32)`, and 1000+ green tests. Every one of those checks a *resolved value*; the
  defect was in the *text*. `ConfigConventionTest` is the check that reads the text.
- **`${VAR:?msg}` and `${VAR:-x}` mean nothing to Spring.** They are shell/Compose forms. This repo
  writes both, four directories apart: correct in `compose*.yaml`, silently a default in
  `application*.yml`, where Spring splits on the first `:` and takes the rest verbatim. **A required
  Spring property is `${VAR}` with no colon** — an unresolvable placeholder throws at bind time.
- **Build the fault switch before the feature it proves.** A resilience behaviour you cannot trigger
  is one you have not tested.
- **Measure bundle changes before believing them.** Splitting DataGrid into its own chunk looked
  obviously right and made total JS ~40% worse by defeating tree-shaking.
- **Write superseded decisions *and* their reversal into the docs**, struck through with
  "→ superseded", so the next reader doesn't think the old reasoning was never considered.
- Save generated `.md` artifacts into this project under `docs/plans/`, `docs/specs/`,
  `docs/audits/` — never to a global directory.
