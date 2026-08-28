# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A self-hosted, Java test case management system serving **multiple client companies from one
instance**. One deployable: Spring Boot REST API + React SPA. Not a test runner (it ingests
results), not a defect tracker (it links out), not public multi-tenant SaaS (companies are
provisioned by an operator command).

**The hierarchy is load-bearing:** Instance → **Company** (a separate client organisation; the
isolation boundary) → **Project** (a whole client product, *not* a feature area) → suites.
"Payments" is a Feature or a suite, never a project. A suite is **flat** — a named collection whose
members are three arms (filed ∪ added ∪ tagged), not a folder in a tree.

## Authoritative documents

Precedence order where they overlap. Read the relevant one before coding against a shape — don't
guess, and don't let code and spec drift: several of these are compared against the code by tests.

| Document | Authority over |
|---|---|
| `docs/specs/openapi.yaml` | The REST contract. Source of **two** generated copies: `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts`. |
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | The permission catalogue, the six roles, grants, naming. §A3 is the matrix; the ERD defers to it. |
| `docs/specs/2026-07-30-tcms-erd.md` | Entity design. **§0.1 is the `company_id` rule and the RLS exception list.** |
| `docs/specs/tenancy-invariants.md` | How isolation is actually enforced, in three layers, and the two silent RLS traps. |
| `docs/specs/permission-matrix-summary.md` | Convenience copy of §A3 (so is `README.md`); both are compared to the spec by `packages/client/src/test/matrixCopies.test.ts`. |
| `docs/specs/parallel-work-rules.md` | What may run concurrently and what may not. Read before any second lane. |
| `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` | The CLI/MCP capability surface. |
| `docs/specs/client-distribution.md` | How `@tcms/cli` and `@tcms/mcp` reach users (tarballs from the instance's `/downloads`, no registry). |
| `docs/specs/shanks-runner-details.md` | The remote gate runner — read before running builds on it. |
| `docs/features.md` | The product's feature inventory, written for a reader deciding what it does. |
| `docs/business-model/INDEX.md` | Reverse-engineered business model: 6 areas, entity lifecycles, flows, decisions. |

Design decisions state *the decision and its reason*. Several look arbitrary until you read the
failure mode they prevent — read the relevant section before changing behaviour it covers.

## Commands

**Three stacks, and no stack builds from the repo root.** Gradle in `backend/`, npm in `frontend/`,
npm again in `packages/` (`@tcms/client`, `@tcms/cli`, `@tcms/mcp` under their own workspace root).
The only thing the root runs is `docker compose`, which orchestrates the *system*.

```bash
# Local stack — Postgres 17 with the real two-role privilege split, Keycloak, + the backend image
cp .env.example .env         # required: compose fails fast rather than defaulting a password
docker compose up --build    # http://localhost:8080/actuator/health
docker compose down -v       # -v also drops pgdata + tcmsblobs, so initdb re-runs the role script

# The operator CLI is the same image — five verbs (see provisioning/OperatorCommands.java)
docker compose run --rm backend provision-company --slug=acme --name="Acme" \
  --admin-email=ops@acme.test --admin-name="A. Admin"
```

```bash
# Backend — REQUIRES Docker; every test boots a real Postgres 17 via Testcontainers.
# There is no H2 lane: RLS, ltree and citext have no H2 equivalent.
cd backend
./gradlew build                                     # THE gate: compile with -Werror + all tests
./gradlew test --tests 'com.tcms.tenancy.RlsIsolationTest'             # single class
./gradlew test --tests 'com.tcms.tenancy.RlsIsolationTest.methodName'  # single test
```

```bash
# Frontend — REQUIRES the backend running; there is NO mock API lane
cd frontend
npm install
npm run dev          # http://localhost:5173, proxies /api to localhost:8080 (VITE_API_PROXY_TARGET)
npm run build        # THE gate: tsc -b + vite build. NOT `tsc --noEmit` — it has reported 0 errors
                     # while the build failed with nine, because `tsc -b` uses the stricter config.
npm run lint         # oxlint at 0 warnings — a gate, not advisory
npm run test:run     # Vitest + RTL (full suite); `npm run test:smoke` is what CI runs per push
npm run gen:api      # regenerate src/api/schema.d.ts after ANY openapi.yaml change
```

```bash
# The TS client, the CLI and the MCP server — pure Node, no Docker, no backend
cd packages
npm install                       # one workspace root; `npm ci` in CI
npm run build                     # THE gate. NOT `--workspaces`: that runs them ALPHABETICALLY, so
                                  # cli would typecheck against a stale client dist/. The root
                                  # script names the order client → cli → mcp.
npm run lint --workspaces         # oxlint, 0 warnings
npm run test:run --workspaces
npm run pack:release -- --version 0.0.0 --out /tmp/rel   # the tarballs users install; CI runs this
cd client && npm run gen:api      # THE SECOND COPY of the contract — regenerate BOTH
node cli/dist/main.js help        # TCMS_URL + TCMS_TOKEN drive it
node mcp/dist/main.js --help      # stdio by default (TCMS_TOKEN); --http is the deployment shape,
                                  # where the credential is each request's Authorization header
```

Sign in by redeeming the `/accept-invitation` link `provision-company` prints — there is no
"any password of 4+ chars". After changing `frontend/.env.local`, kill vite and
`rm -rf node_modules/.vite`: an in-place restart leaves a stale optimize-deps hash and renders a
blank page with an empty console.

### Run the gates on `shanks`, not locally

**Standing user directive.** Write the code here; run `./gradlew build` and the npm suites on
`shanks` through `~/bin/tcms-remote` (machine-local tooling on the MacBook, not in this repo — it
rsyncs the working tree, uncommitted changes included, then runs the command over SSH). Targeted
tests may run locally *for debugging*; the gates themselves stay on shanks.

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew build
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build
```

Read `docs/specs/shanks-runner-details.md` first — especially that `BUILD FAILED` with
`Could not write XML test results` and **zero** assertion failures is an fd-limit problem, not a red
suite (`ulimit -n 65535; ./gradlew build`).

### What CI does and does not run

- `ci.yml` (push/PR to master, self-hosted `[self-hosted, tcms-lab]`): backend **compile only**,
  `./gradlew build -x test`; frontend build + lint + **smoke** tests; packages build + lint + full
  tests + the release-pack gate. A push to master then continuously delivers to the **lab**.
- `tests.yml` (nightly 01:17 UTC): the full backend suite on Linux. `gh workflow run tests.yml` for
  on demand. This is the only machine-run of the backend tests.
- `deploy.yml`: builds and ships both halves. Production is dispatch-only with a real semver.

So the backend suite gates nothing before master. Run it before you push.

### Test-harness rules the suites enforce

- **Contract conformance**: every exchange crossing a `fetch` double is compared against
  `openapi.yaml` — the body served, the payload sent, and the status. Use `frontend/src/test/fixtures.ts`
  (or `packages/client/src/test/fixtures.ts`), and anchor path-keyed mock arms (`/^\/projects\/[^/]+$/`)
  so a sub-resource isn't caught by a prefix. `contractBodies.ts` has a marked region that is
  byte-identical across both copies and pinned by `contractPairing.test.ts` — edit one, edit both.
- **Error-boundary failures**: a test whose render trips an error boundary FAILS unless it calls
  `renderingIntoAnErrorBoundary()` and says why; likewise `drivingAFailedRead()` / `drivingAFailedWrite()`.
  **Never build your own `QueryClient` in a test** — only `createTestQueryClient`'s has the caches.
- **Cited files**: `packages/client/src/test/citedFiles.test.ts` fails if a path named in a comment
  anywhere (`packages/**`, `frontend/src/**`, `docs/specs/*.yaml`, `backend/src/**`) is not on disk.
  A stale citation in `frontend/` or `backend/` reddens the **packages** job.
- **Tier claims**: `tierClaims.test.ts` fails if a comment claims somebody LACKS a permission the
  roles spec §A3 says they hold. Comments only; past tense and strikethrough are not claims.
- **Out-of-source inputs**: `backend/build.gradle.kts` declares `Dockerfile`, `compose.yaml`,
  `deploy/`, `docs/specs/` and `frontend/src/lib/permissions.ts` as test inputs, because several
  backend tests assert over source *text*. A new such read must add its own line, or the rule is
  green by cache.

### Driving resilience behaviour (dev only, browser console)

None of the error/retry/session machinery can be triggered by using the app normally:

```js
tcmsFaults.status(503, 30)  // every request 503s with Retry-After
tcmsFaults.hang()           // requests never answer — proves the timeout budget
tcmsFaults.expire()         // next request 401s once — proves in-place session recovery
tcmsFaults.drift()          // 200 with the wrong shape — must read as FAILED, not empty
tcmsFaults.only('/cases')   // scope to one endpoint family
tcmsFaults.off()
```

These fault the **real** backend: the seam sits in front of `fetch` in `src/api/client.ts`, fed by
`src/lib/devFaults.ts`. Both guards are a literal `import.meta.env.DEV` so Rollup drops the module
from a production build — route that check through an imported constant and it ships. Faults live in
module state, so a reload clears them. Caught client errors are at `window.__tcmsErrors` in dev.

## Architecture

### Tenancy — the invariant everything else rests on

`company_id UUID NOT NULL` on every company-owned table, leading every composite index and every
previously-global uniqueness constraint. Three enforcement layers: `SchemaConventionTest`,
`CompanyContext` (a `ThreadLocal` exposed only as a scope guard, never a setter, deliberately **not**
inheritable), and `CompanyAwareTransactionManager` (hooks `doBegin`, so the Hibernate filter and the
`app.current_company` GUC are set for *every* transaction). Postgres RLS with
`FORCE ROW LEVEL SECURITY` is the backstop; the app connects as `tcms_app`, which owns nothing and
holds no `BYPASSRLS`, while Flyway connects as `tcms_owner`.

**A repository interface must carry `@Transactional(readOnly = true)`** — Spring Data gives a
transaction only to the inherited CRUD methods, so derived and `@Query` methods otherwise run with no
company set and every RLS-protected read returns **zero rows**, fail-closed and silent.
Company context is populated from the authenticated session, **never** from a parameter, header or
path segment. Async work re-establishes it from a `CompanyScopedEvent` only. Full detail —
including the two spellings of "unset GUC" — in `docs/specs/tenancy-invariants.md`.

Access is two independent gates: company membership (authentication + zero project visibility), then
an explicit project grant. Code checks **permissions, never role names**; there is no wildcard
permission. SYSTEM-scope grants live in `instance_role_assignment`, a separate instance-level table,
because a nullable `company_id` under RLS is permanently unreachable.

### Backend

Spring Boot 3.5.6 + Java 21 + Gradle, rooted in `backend/`. A Spring Modulith **monolith** whose
modules are Java *packages* under `com.tcms`, not Gradle subprojects: `api · tenancy · identity ·
project · storage · repository · execution · documentation · reporting · work · integration · auth ·
provisioning`.

**Cross-module dependencies may point only at the five foundation modules** — `api`, `tenancy`,
`identity`, `project`, `storage` — and everything else crosses via a `CompanyScopedEvent`. ArchUnit
enforces it (`backend/src/test/java/com/tcms/architecture/`). The Javadoc on
`ArchitectureRules.FOUNDATION_MODULES` states the bar a proposed sixth must clear; adding one is a
decision that needs a paragraph, not an import.

Flyway owns all DDL (`hibernate.ddl-auto: validate`, `baseline-on-migrate: false` so V1's RLS block
always executes). `V1__initial_schema.sql` is 64 tables / 56 forced RLS policies, and its comments
carry the *why*. Booting the real context in a test is itself a gate — `validate` catches entity
drift at startup. Java compiles with `-Werror`; each suppressed lint in `build.gradle.kts` has a
recorded reason.

### Frontend

Vite 8 + React 19 + TypeScript strict + MUI v9 (X DataGrid / TreeView / date-pickers).
**No Next.js, no RSC** — no `'use client'` boundary exists, so the skills' RSC lane does not apply.
React Router 7; project-scoped routes under `/projects/:projectId/*`.

- **TanStack Query owns all server state.** No server data in any client store.
  `src/api/queries/` is the single data module — one file per domain behind a barrel, every
  read/write hook, **one** `keys` factory in `keys.ts` (never add a second), `enabled` gates, and a
  per-endpoint-class timeout budget threaded through every call.
- **Client state:** session in Context; **filter state lives in the URL** (`useSearchParams`).
- **Styling:** MUI `theme` + `sx` as the single authority. No Tailwind. Tokens are CSS custom
  properties in `index.css` bridged into `createTheme`.
- **`src/app/Providers.tsx` nesting order is load-bearing** and documented in the file: the query
  client is constructed *innermost* because its global error handlers consume the notification and
  session-recovery providers.
- **Errors are centralized:** `lib/describeError.ts` is the one place an error becomes user-facing
  copy (and owns `isTransient()`); `app/queryClient.ts` is the one place a failure raises a
  notification; `components/ErrorBoundaries.tsx` provides the root/route/region tiers.
- **New display logic goes in the shared layer, not the page**: `lib/labels.ts` for enums (each
  `satisfies Record<Union, string>`, so a new member is a compile error until labeled),
  `lib/format.ts`, `lib/results.ts`, `lib/permissions.ts`, `DataRegion` for the
  loading/loaded/failed/empty/stale matrix, `Identifier` for anything monospace.

## Working agreement

- **`openapi.yaml` is the contract.** Change it there, regenerate **both** copies, then adapt both
  sides in one commit. **If you add a schema, add its `required` array**, including on inline nested
  objects — `required` does not descend, and without it every generated property is optional.
- **A schema-wide or catalogue-wide invariant needs a computed check, not a careful reading.**
  Reading V1 top to bottom missed five join tables without `company_id`; scripting the comparison
  found them. Derive "holds every permission" from the catalogue rather than storing a `'*'` sentinel.
- **`${VAR:?msg}` and `${VAR:-x}` mean nothing to Spring.** They are shell/Compose forms — correct
  in `compose*.yaml`, silently a *default* in `application*.yml`, where Spring splits on the first
  `:` and takes the rest verbatim. **A required Spring property is `${VAR}` with no colon.** This
  signed production sessions with a string from this repo for a year, past a correct comment, a real
  `@Size(min = 32)` and a thousand green tests, because every one of those checks a resolved value
  and the defect was in the *text*. `ConfigConventionTest` is the check that reads the text.
- **Build the fault switch before the feature it proves.** A resilience behaviour you cannot trigger
  is one you have not tested.
- **Measure bundle changes before believing them.** Splitting DataGrid into its own chunk looked
  obviously right and made total JS ~40% worse by defeating tree-shaking.
- The gates prove it compiles, not that it works: a no-op theme toggle, two contradictory
  percentages on one screen, and a provider-ordering bug that rendered nothing but the error page all
  typechecked and linted clean. Say "built and unverified" when that is what it is.
- **Write superseded decisions *and* their reversal into the docs**, struck through with
  "→ superseded", so the next reader doesn't think the old reasoning was never considered.
- Save generated `.md` artifacts into this project under `docs/specs/` etc. — never to a global
  directory.

## Git workflow

- **Work on the current branch. Do not create a new one.** Feature work continues on whatever is
  checked out, including `master`. Check with `git rev-parse --abbrev-ref HEAD` rather than assuming.
- **Commit at the end of each phase**, after its gate passes — so each commit is a state whose
  automated gates were actually run.
- **Stage only the files your phase touched** (`git add <paths>`, never `git add -A`): concurrent
  sessions happen in this repo, and re-read a shared doc before editing it.
- **Push after each lane.** Know what it buys: `ci.yml` triggers on push to `master` and on PRs
  targeting it, and `tests.yml` runs on the default branch only — so a pushed lane branch is
  *backed up*, not *observed*.

## Running work in parallel — don't

**Work is sequential.** Parallel lanes were tried four times and reversed; two agents in one tree
produced three silent losses of committed work with no conflict, no error and a clean `git status`.
**One subagent at a time — ask before a second**, and say why it is safe (disjoint files, no shared
build directory, no shared contract or migration numbering). A second checkout is the one exception
to "do not branch per task"; read `docs/specs/parallel-work-rules.md` first — contract ownership,
Flyway version pre-assignment (`out-of-order` is unset, and Testcontainers cannot see the conflict),
fixture UUID prefix space, and the lane-b stack (`.env.lane-b`, ports 6432 / 9080 / 6173).

## Plugin skills are mandatory, not decorative

This project is built through the `fe-react`, `be-java`, `data`, `qa-java`, `devops` and
`business-analysis` plugin skills (see `.claude/settings.json`). Frontend work → **`/fe-react`**;
backend work → **`/be-java`**, plus `be-java-database-code` for JPA and `data-postgresql` /
`data-migrations` for schema and migrations; QA has no self-named command — its entries are per-lane
(`/qa-api-java`, `/qa-ui-java`, …). The orchestrator entry point is a **command**, not a skill:
`Skill(fe-react:fe-react)` returns "Unknown skill" even when the plugins are loaded correctly, and
reading that as "the plugins aren't registered" sends you after the wrong defect. Never improvise a
pattern a skill owns; if a listed skill is missing on disk, say so and proceed with general rules.

**If none of the plugin skills are visible, check the install path first.** `.claude/settings.json`'s
`enabledPlugins` is committed, but `~/.claude/plugins/installed_plugins.json` keys every installation
on an absolute `projectPath` and does not — so moving the checkout silently unregisters all of them.
`claude plugin list` prints every row across every project as `✔ enabled` and will not show you this;
use `claude plugin list --json` and check `projectPath` against `pwd`.
