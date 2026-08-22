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

**The orchestrator entry point is a slash command, not a skill.** No `SKILL.md` in the library
declares `name: fe-react` or `name: be-java` — every skill the plugins ship is *prefixed*
(`fe-react-basics`, `be-java-database-code`, …). `fe-react` and `be-java` exist as a **command** and
an **agent**, and the command's whole job is to load the agent definition, which then names the phase
skills. So `Skill(fe-react:fe-react)` returns "Unknown skill" even when the plugins are loaded
correctly, and reading that failure as "the plugins aren't registered" sends you after the wrong
defect.

- Frontend work → **`/fe-react`**, then load only the current phase's skills.
- Backend work → **`/be-java`**, plus the `be-java-database-code` skill for JPA and
  `data-postgresql` / `data-migrations` for schema and migrations.
- QA has no self-named command — its entries are per-lane (`/qa-api-java`, `/qa-ui-java`, …).
- Never improvise a pattern a skill owns. If a listed skill is missing on disk, say so and proceed
  with general rules — never substitute invented guidance.

**If none of the plugin skills are visible, check the install path before anything else.**
Enablement and installation are two different halves: `.claude/settings.json`'s `enabledPlugins` is
committed and travels with the repo, but `~/.claude/plugins/installed_plugins.json` keys every
installation on an **absolute `projectPath`** and does not. Moving the checkout therefore silently
unregisters all eight plugins. **`claude plugin list` will not show you this**: it prints every row
across every project, all `✔ enabled`, so it reads healthy from any directory. Use
`claude plugin list --json` and check `projectPath` against `pwd`. The tell is that it is not
skills-specific — `context7` is enabled in the same file, so its MCP tools go missing too.

## Commands

**There are THREE stacks since §137.** Gradle lives in `backend/`, npm in `frontend/`, and npm again
in `packages/` — `@tcms/client` (the typed API client and command core), `@tcms/cli` and, since §140,
`@tcms/mcp` (the MCP server), under their own workspace root at `packages/package.json`. Three
packages, one stack. **No stack builds from the repo root** — the only thing the root runs is
`docker compose`, which orchestrates the *system* and is therefore the one thing that legitimately
spans both.

```bash
# Local stack — Postgres 17 with the real two-role privilege split, + the backend image
cp .env.example .env         # required: compose fails fast rather than defaulting a password
docker compose up --build    # http://localhost:8080/actuator/health
docker compose down -v       # -v also drops the volume, so initdb re-runs the role script

# The operator CLI is the same image
docker compose run --rm backend provision-company --slug=acme --name="Acme" \
  --admin-email=ops@acme.test --admin-name="A. Admin"
```

There is no mock API. `npm run dev` proxies `/api` to `localhost:8080` and **requires
`docker compose up` at the repo root plus a provisioned company**. Sign in by redeeming the
`/accept-invitation` link `provision-company` prints; there is no "any password of 4+ chars".
Point it elsewhere with `VITE_API_PROXY_TARGET`. **After changing `.env.local`, kill vite and
`rm -rf node_modules/.vite`**: an in-place restart leaves a stale optimize-deps hash and the page
renders blank with an empty console.

```bash
# The TS client, the CLI (§137) and the MCP server (§140) — pure Node, no Docker, no backend
cd packages
npm install                       # one workspace root; `npm ci` in CI
npm run build                     # THE gate: tsc over source+tests, then emit. NOT `--workspaces`
                                  # — runs them ALPHABETICALLY, so the script names the order
                                  # client → cli → mcp.
npm run lint --workspaces         # oxlint, 0 warnings
npm run test:run --workspaces     # 341 tests (130 client / 82 cli / 129 mcp), ~1s — CI runs these
cd client && npm run gen:api      # THE SECOND COPY of schema.d.ts. Regenerate BOTH after any
                                  # openapi.yaml change; schemaFreshness.test.ts fails otherwise.
node cli/dist/main.js help        # TCMS_URL + TCMS_TOKEN drive it
node mcp/dist/main.js --help      # stdio by default; --http is the deployment shape.
                                  # Under stdio the credential is TCMS_TOKEN; under --http it
                                  # is each request's Authorization header, and the server is
                                  # built PER SESSION because its instructions carry that
                                  # caller's capability summary (§140). Put TLS in front of it.
```

```bash
# Backend — REQUIRES Docker; every test boots a real Postgres 17 via Testcontainers
cd backend
./gradlew build                                  # THE gate: compile with -Werror + all tests
./gradlew test
./gradlew test --tests 'com.tcms.tenancy.RlsIsolationTest'          # single class
./gradlew test --tests 'com.tcms.tenancy.RlsIsolationTest.methodName'  # single test
```

```bash
# Frontend — REQUIRES the backend running (docker compose up); no mock lane
cd frontend
npm install
npm run dev          # http://localhost:5173 — proxies /api to localhost:8080
npm run build        # THE gate: tsc -b + vite build
npm run lint         # oxlint, must stay at 0 warnings — a gate, not advisory
npm run test:run     # Vitest + RTL, 1710 tests across 128 files (measured on shanks 2026-08-22) — also a gate
npm run gen:api      # regenerate src/api/schema.d.ts after ANY openapi.yaml change
                     # — AND packages/client's copy; the two are byte-identical and a test compares them
```

**Key test harness rules** (enforced by the suites, not just convention):

- **Contract conformance**: every exchange crossing a `fetch` double is compared against
  `openapi.yaml` — the body it SERVES, the payload it was CALLED WITH, and the STATUS. Use
  `src/test/fixtures.ts` (or `client/src/test/fixtures.ts` in packages), and anchor a path-keyed
  mock arm (`/^\/projects\/[^/]+$/`) so a sub-resource is not caught by a prefix. The comparison
  itself is `client/src/test/contractBodies.ts`, whose marked region is BYTE-IDENTICAL to
  `frontend/`'s copy and pinned by `contractPairing.test.ts` — edit one, edit both.
- **Error boundary failures**: a test whose render trips an error boundary FAILS unless it calls
  `renderingIntoAnErrorBoundary()` and says why. A test whose read or write fails also fails unless
  it calls `drivingAFailedRead()` or `drivingAFailedWrite()`. **Never build your own `QueryClient`
  in a test** — only `createTestQueryClient`'s has the caches. `renderFailures.test.tsx` sweeps
  every test source to keep that list empty.
- **Cited files**: `client/src/test/citedFiles.test.ts` fails if a path a comment names (across all
  four corpora: `packages/**`, `frontend/src/**`, `docs/specs/*.yaml`, `backend/src/**`) is not on
  disk. A stale citation in `frontend/` or `backend/` reddens the **packages** job.
- **Tier claims**: `client/src/test/tierClaims.test.ts` fails if a comment claims somebody LACKS a
  permission that the roles spec §A3 says they hold. Past tense is not a claim; `<del>` counts as
  strikethrough. It reads COMMENTS ONLY (`proseOf`).
- **Write conformance**: `writeConformance.test.ts` in cli/ and mcp/ drives every write arm and
  asserts both halves: the header the `kind` implies, and that the operation DECLARES that header.
- **Matrix copies**: `matrixCopies.test.ts` compares the permission tables in
  `docs/specs/permission-matrix-summary.md` and README against the roles spec §A3 every cell, both ways.

### Running the gates on `shanks` instead of locally (set up 2026-08-12)

**Tests are to be run on shanks, not locally — a standing directive, restated by the user
2026-08-15, not a suggestion.** Do not run `./gradlew build`/`test` or the npm suites on the Mac;
sync-and-run them on shanks via `~/bin/tcms-remote` as below. The one carve-out (same day, same
user): *a few* targeted tests may run locally **for debugging** — the gates themselves stay on
shanks.

**Write the code here, run the builds and tests on `shanks`.** There is no Claude Code setting for
this — the Bash tool always runs on the Mac — so it is a sync-and-run wrapper, `~/bin/tcms-remote`
**on the MacBook** (machine-local personal tooling, not in this repo; recreate it from this section
if it is missing). It rsyncs the local working tree — **uncommitted changes included** — to
`shanks:/home/akotrulev/builds/tcms`, then runs the command there over SSH.

```bash
~/bin/tcms-remote sync                        # sync only
~/bin/tcms-remote backend ./gradlew build     # sync, then run in backend/
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote shell                       # interactive shell in the remote tree
```

Details on the sync exclusions, the four gotchas (build-only tree, one-way sync, compose collision,
`~/bin` not on PATH), and the rsync rebuild recipe are in `docs/specs/shanks-runner-details.md`. The
env vars `TCMS_REMOTE_HOST` / `TCMS_LOCAL_DIR` / `TCMS_REMOTE_DIR` override defaults.

> **CI runs no backend tests.** `ci.yml` runs `./gradlew build -x test`. The nightly
> (`tests.yml`, 01:17 UTC, `[self-hosted, tcms-lab]`) runs the full build. Run gates before you
> push; `gh workflow run tests.yml` for on-demand.

There is no H2 fallback — RLS, `ltree` and `citext` have no equivalent. `npm run gen:api` runs
through `npx` on purpose (TypeScript 6 vs tool's 5.x peer-dep).

Vitest + RTL are installed and green. Config in `frontend/vitest.config.ts`, shared helpers in
`src/test/`.

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

**These fault the real backend.** The seam sits in front of `fetch` in `src/api/client.ts`, fed
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

`company_id UUID NOT NULL` on every company-owned table. Enforcement: `SchemaConventionTest`,
`CompanyContext` (ThreadLocal scope guard), `CompanyAwareTransactionManager` (RLS via
`FORCE ROW LEVEL SECURITY`). **A repository interface must carry `@Transactional(readOnly = true)`**
or derived queries return zero rows. Full details in `docs/specs/tenancy-invariants.md`.

Access is two independent gates: company membership (authentication + zero project visibility), then
an explicit project grant. Three tiers — Instance Admin → Company Admin (a `COMPANY`-scope role
assignment, not a new tier) → regular user. Code checks **permissions, never role names**, and there
is no wildcard permission. SYSTEM-scope grants live in `instance_role_assignment`, a separate
instance-level table, because a nullable `company_id` under RLS is permanently unreachable.

#### The permission matrix

The convenience copy of the permission matrix is in `docs/specs/permission-matrix-summary.md`;
the authoritative source is the roles spec §A3
(`docs/specs/2026-07-30-tcms-roles-permissions-naming.md`).

### Backend

Spring Boot 3.5.6 + Java 21 + Gradle, rooted in `backend/`. Spring Modulith **monolith** whose
modules are Java *packages*, not Gradle subprojects: `tenancy · identity · project · storage ·
repository · execution · documentation · reporting · work · shared`. Cross-module FKs are allowed
only toward `tenancy`/`identity`/`project`/`storage`; everything else crosses via domain events,
enforced by ArchUnit. `tenancy` is depended on by everything and depends on nothing.

Flyway owns all DDL (`hibernate.ddl-auto: validate`, `baseline-on-migrate: false` so V1's RLS block
always executes). `V1__initial_schema.sql` is 64 tables / 56 forced RLS policies and its comments
carry the *why*. Booting the real context in a test is itself a gate — `validate` catches entity
drift at startup.

Java compiles with `-Werror`; the suppressed lints in `build.gradle.kts` each have a recorded reason.

### Frontend

Vite 8 + React 19 + TypeScript strict + MUI v9 (with X DataGrid/TreeView). **No Next.js, no RSC** —
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
  after its gate passes (`./gradlew build` / `npm run build` + `npm run lint`; browser passes are
  **deferred to the verification queues since §65**, see the working agreement), so each commit is a
  state whose automated gates were actually run.
- **Concurrent sessions happen here** — `docs/HANDOFF.md` §10 records a second strand editing
  `docs/plans/` mid-session. Stage only the files your phase touched (`git add <paths>`, never
  `git add -A`), so another author's in-flight edits don't get interleaved into your diff. Re-read a
  shared doc before editing it.
- **A concurrency worktree is the one exception to "do not create a branch."** The rule above is
  about not branching per *task*. A second lane that touches the backend needs its own worktree,
  because one `backend/build/` and one Gradle daemon cannot serve two lanes. Say so before using it.
- **Push after each lane** (set 2026-08-10, at the user's direction). When a lane's work is
  committed, `git push` it — do not leave a lane's commits sitting only in the local worktree.
  **Know what the push does and does not buy.** `ci.yml` triggers on `push` to **`master`** and on
  pull requests targeting it, and `tests.yml` runs nightly on the **default branch** only — so
  pushing a worktree branch gets the commits off one machine and gates **nothing** until it is
  merged or a PR is opened. That is still worth doing (§0's three silent losses of committed work
  were all local), but a pushed lane branch is *backed up*, not *observed*, and saying otherwise is
  the shape §161 found. This does not license pushing a red gate — the phase commit rule above
  still binds, and what gets pushed is a state whose gates were run.

## Running work in parallel — don't

**Work is sequential.** One subagent at a time — ask before a second. Concurrent writers in one tree
lose committed work silently (three incidents in §37/§41/§45). If a second checkout is genuinely
needed, read `docs/specs/parallel-work-rules.md` first — it covers contract ownership, Flyway version
pre-assignment, UUID prefix collisions, and the lane-b stack.

## Working agreement

- **`openapi.yaml` is the contract.** Change it there, regenerate, then adapt both sides in one
  commit. Read it before coding against a shape — don't guess. **If you add a schema, add its
  `required` array**, including on inline nested objects: `required` does not descend, and without it
  every generated property is optional and no consumer can use it.
- **Verify with `npm run build`, not `tsc --noEmit`** — the latter has reported 0 errors while the
  build failed with nine. The gates prove it compiles, not that it works: a no-op theme toggle, two
  contradictory percentages on one screen, and a provider-ordering bug that rendered nothing but the
  error page all typechecked and linted clean.
- **Browser passes and backend mutation checks are DEFERRED AND BATCHED — §65, standing policy.**
  Set 2026-08-02 at the user's direction: ship the remaining functionality first while requirements
  are still moving, then verify in one concentrated pass. **A slice still records what it would have
  driven and what it would have mutated**, appended to the matching queue —
  `docs/verification/frontend-browser-checks.md` and `docs/verification/backend-mutation-checks.md`
  — in a form that can be executed later. Landing without adding those rows skips the check
  outright, which is what batching exists to avoid. **Deferred means ALL of them, restated
  2026-08-02**: do not run "just the cheap one" or "just the highest-signal one" during a slice.
  Write the row, run nothing. **A slice in an unrun queue is *built and
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
