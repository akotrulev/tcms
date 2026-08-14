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

**The orchestrator entry point is a slash command, not a skill.** ~~Frontend work →
`fe-react:fe-react`; backend work → `be-java:be-java`.~~ → **corrected 2026-08-14: neither is a
skill and neither ever was.** No `SKILL.md` in the library declares `name: fe-react` or
`name: be-java` — every skill the plugins ship is *prefixed* (`fe-react-basics`,
`be-java-database-code`, …). `fe-react` and `be-java` exist as a **command** and an **agent**, and
the command's whole job is to load the agent definition, which then names the phase skills. So
`Skill(fe-react:fe-react)` returns "Unknown skill" even when the plugins are loaded correctly, and
reading that failure as "the plugins aren't registered" sends you after the wrong defect.

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
unregisters all eight plugins — which is exactly what happened when this repo moved from
`~/Documents/tcms` to `~/RiderProjects/tcms` (fixed 2026-08-14, `--scope project`, which also bumped
the library `be1222c5e59d` → `0d8474f66507`). **`claude plugin list` will not show you this**: it
prints every row across every project, all `✔ enabled`, so it reads healthy from any directory. Use
`claude plugin list --json` and check `projectPath` against `pwd`. The tell is that it is not
skills-specific — `context7` is enabled in the same file, so its MCP tools go missing too.

## Commands

~~The two stacks are independent.~~ **There are THREE since §137.** Gradle lives in `backend/`, npm
in `frontend/`, and npm again in `packages/` — `@tcms/client` (the typed API client and command
core), `@tcms/cli` and, since §140, `@tcms/mcp` (the MCP server), under their own workspace root at
`packages/package.json`. Three packages, one stack. **No stack
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
# The TS client, the CLI (§137) and the MCP server (§140) — pure Node, no Docker, no backend
cd packages
npm install                       # one workspace root; `npm ci` in CI
npm run build                     # THE gate: tsc over source+tests, then emit. NOT
                                  # `--workspaces` — that runs them ALPHABETICALLY, so cli goes
                                  # first and typechecks against a client that has not been
                                  # emitted. It passed locally only on a leftover dist/ and CI's
                                  # packages job had never once been green (§139). The script
                                  # names the order client → cli → mcp; mcp is THIRD for the
                                  # same reason cli is second.
npm run lint --workspaces         # oxlint, 0 warnings
npm run test:run --workspaces     # ~~211~~ ~~241~~ ~~260~~ ~~269~~ 302 tests (119 client / 78 cli /
                                  # 105 mcp, measured on shanks 2026-08-14), ~1s — CI runs these,
                                  # unlike the backend's.
                                  # **Since §205 `writeConformance.test.ts` exists in cli/ and mcp/**
                                  # — eight comments had cited it (and a `conformance.test.ts`) since
                                  # §139/§140 and neither file was real; the MCP's registry check
                                  # read only its own declarations, so 8 of 22 write arms were driven
                                  # by nothing. It drives every arm and asserts BOTH halves: the
                                  # header the `kind` implies, and that the operation DECLARES that
                                  # header. That second half found `tcms_report.generate` sending an
                                  # `Idempotency-Key` to the one create endpoint of eleven that takes
                                  # none — a retried 202 queues the report twice.
                                  # **Also since §205: `client/src/test/citedFiles.test.ts` fails if
                                  # a path a comment names is not on disk.** A struck-through name is
                                  # excused — that notation already says it does not exist —
                                  # otherwise it goes in the by-decision map with a reason.
                                  # ~~It scans BOTH TypeScript stacks~~ → **§206: FOUR corpora** —
                                  # `packages/**`, `frontend/src/**`, `docs/specs/*.yaml` (the
                                  # contract's own prose, which `gen:api` copies verbatim into both
                                  # `schema` files) and **`backend/src/**`**, where javadoc cites
                                  # with `{@code}` — the form nothing resolves, unlike `{@link}`.
                                  # A trailing `/` makes it a directory citation, checked the same
                                  # way. NOTE the blast radius: a stale citation in `frontend/` or
                                  # `backend/` reddens the **packages** job. That is deliberate for
                                  # `backend/`: `:test`'s inputs are the classpath, so a rename
                                  # elsewhere changes no backend input and a Java rule would be
                                  # green by cache — measured, §206.
                                  # **Since §203 every exchange crossing a `fetch` double
                                  # here is compared against openapi.yaml — the body it SERVES and
                                  # the payload it was CALLED WITH** — §197–§199 arriving in this
                                  # stack, and it found 118 red of 241. The seam is
                                  # `client/src/test/contractExchange.ts`, called from all three
                                  # doubles (`stubFetch`, plus an `api(routes)` harness in each of
                                  # cli/ and mcp/); build a fourth and `contractExchange.test.ts`
                                  # fails naming the file. Use `client/src/test/fixtures.ts` — a
                                  # complete factory per resource — and mind WHICH SHAPE an endpoint
                                  # answers: a list is a `*Summary`, and serving the detail for one
                                  # is 24 undeclared properties a row. Only a **2xx** answer is
                                  # measured against the success schema, so a test driving a refusal
                                  # needs no hatch; the one hatch is `drivingTheTransport()`, for a
                                  # test whose subject is a header, a timeout or a cancellation.
                                  # **Since §204 the STATUS is compared too** — a 2xx a double
                                  # answers with must be one the operation declares. Population
                                  # zero: 16 creates declare 200 AND 201, because a replayed
                                  # Idempotency-Key answers 200, so a 200 on a create is fine.
                                  # The comparison itself is `client/src/test/contractBodies.ts`,
                                  # whose marked region is BYTE-IDENTICAL to `frontend/`'s copy and
                                  # pinned by `contractPairing.test.ts` — edit one, edit both.
                                  # `successStatusesOf` sits OUTSIDE that region on purpose: a
                                  # question only one stack can ask does not go in it (the SPA's
                                  # double answers `{ data }` and has no status). Same file, §204.
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
npm run test:run     # Vitest + RTL, ~~1203~~ ~~1251~~ ~~1276~~ ~~1285~~ ~~1314~~ ~~1320~~ ~~1330~~ ~~1335~~ 1339 tests
                     # (measured on shanks 2026-08-14) — also a gate. Since §197 the harness compares
                     # every body a mocked `api.*` call serves against openapi.yaml and fails the test
                     # that served a body the server could not send; since §198 it compares the VALUES
                     # too — enum members, types, nulls in non-nullable fields, `date-time` strings,
                     # and an object served where the contract declares an array. Use
                     # `src/test/fixtures.ts`, and anchor a path-keyed mock arm
                     # (`/^\/projects\/[^/]+$/`) so a sub-resource is not caught by a prefix.
                     # **Since §199 it compares the OTHER direction too**: the body a write double is
                     # CALLED with, against the operation's `requestBody`. That half reads production
                     # code — the payload is what `api/queries/` and the form composed — and the first
                     # thing it found was `useCreatePlanCase` sending two of the four fields
                     # `TestCaseInput` requires, so the plan tab's Save had 400ed since §97.
                     # **Since §201 a test whose render tripped an error boundary FAILS** unless it
                     # calls `renderingIntoAnErrorBoundary()` and says why — a boundary exists so a
                     # broken region stays invisible to the rest of the page, which is how 24 tests
                     # passed for years beside a panel showing "We couldn't display this content".
                     # ~~A region whose *fetch* failed is NOT covered: `createTestQueryClient`
                     # installs no error handlers.~~ → **superseded by §202: it IS covered.** That
                     # helper installs both caches now and they report to the same sink — reporting
                     # never needed the notification provider, only *raising* one did. So a test whose
                     # read or write failed fails too, unless it calls `drivingAFailedRead()` or
                     # `drivingAFailedWrite()` and says why. THREE hatches, not one, because 44 of the
                     # 61 opt-outs are a refused write and such a test says nothing about the panels
                     # beside the dialog. **Never build your own `QueryClient` in a test** — only that
                     # helper's has the caches, so a hand-built one is invisible to the check, and
                     # `renderFailures.test.tsx` sweeps every test source to keep that list empty.
                     # ~~`CaseEditorPage.test.tsx` has a load-dependent flake on shanks: one test
                     # asserts a half-typed value under full-suite load and passes when the file
                     # runs alone. Measured against a stashed tree in §186; NOT fixed.~~ →
                     # **fixed 2026-08-13** after it finally reddened CI (run 31731166477). It was
                     # never half-typed input: the step-suggestion debounce is 250ms of REAL time,
                     # so a loaded machine runs several debounce rounds per `user.type()`, each a new
                     # query key whose `data` is undefined until it resolves — the popup empties and
                     # the `<li>` a `findAllByTestId` just handed back is DETACHED. Clicking a
                     # detached node fires nothing, so the field keeps the typed text. Capturing a
                     # row and clicking it must be ONE retried step (`pickSuggestion`), not two
                     # awaited ones. Any test that finds a node in a debounce-fed popup and clicks
                     # it on the next line has the same defect.
npm run gen:api      # regenerate src/api/schema.d.ts after ANY openapi.yaml change
                     # — AND packages/client's copy; the two are byte-identical and a test compares them
```

### Running the gates on `shanks` instead of locally (set up 2026-08-12)

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

Verified end-to-end on 2026-08-12: `./gradlew build -x test` **6s** (the Gradle cache is warm from
the Actions runner), `./gradlew test --tests RlsIsolationTest` **44s** — so Testcontainers and
Docker 29.7.2 work there, GUC-recycling trap included — and the frontend suite **1251 tests green**
in 231s after an `npm ci`.

This is a **differently-shaped observer, and that is the point**: gates run on shanks are the same
Linux shape as `tests.yml`, so the microsecond-vs-nanosecond class of defect that kept master red
for seven CI runs on 2026-08-01 — and *cannot* fail on macOS — is now catchable before the push
rather than the next morning. It does not replace the nightly; it front-runs it.

Four things that bite:

- **It uses a build-only tree, deliberately: `~/builds/tcms`, never `~/projects/tcms`.** That second
  path is the Remote Control lane's checkout and is usually live on its own branch (on 2026-08-12 it
  was `docs/multi-app-hosting-plan` at `7ba9255`, ahead of local master). Building into it is exactly
  the two-writers-one-tree failure that ended parallel lanes. **Never edit anything under
  `~/builds/tcms`** — the sync is `--delete` and will overwrite it.
- **Sync is one-way, Mac → shanks.** `backend/build/`, `dist/` and every `node_modules/` are excluded,
  so outputs stay on shanks and nothing comes back. Pull an artifact explicitly when you need one.
- **`docker compose` from that tree collides.** Compose derives its project name from the directory,
  so it would be `tcms` — the same name as the stack already running on shanks. Use
  `docker compose -p tcms-build`, or keep compose local.
- **`~/bin` is not on the Mac's PATH.** Invoke it by absolute path.

The whole wrapper is `rsync -az --delete` plus one `ssh`, so it is a few lines to rebuild on a new
machine. What it excludes — and must keep excluding, or the sync clobbers the remote's caches and
takes minutes instead of seconds — is `.git/`, `.gradle/`, `.idea/`, `.DS_Store`, `backend/build/`,
`frontend/node_modules/`, `frontend/dist/`, `packages/node_modules/`, `packages/*/node_modules/`
and `packages/*/dist/`. `.env` is **not** excluded: compose on the remote needs it. Host, local dir
and remote dir are overridable via `TCMS_REMOTE_HOST` / `TCMS_LOCAL_DIR` / `TCMS_REMOTE_DIR`.

`shanks` is also the `tcms-lab` Actions runner, so a heavy local gate can land on top of a CI job.
Both fit (6 cores / 14 GB), but a slow run is not necessarily a hung one.

> **CI runs no backend tests** (changed 2026-08-02, for push-feedback speed: ~327s → ~60s).
> `.github/workflows/ci.yml` runs `./gradlew build -x test`, so `-Werror` and the Modulith wiring
> are still checked and nothing else is. ~~There is no nightly lane either.~~ ~~→ one is written as
> of 2026-08-06 and is **not yet live** … it is inert until that runner is registered~~ →
> **superseded 2026-08-08: THE NIGHTLY LINUX LANE IS LIVE.** `.github/workflows/tests.yml` runs the
> full `./gradlew build` at 01:17 UTC on `[self-hosted, tcms-lab]` — the homelab box `shanks`,
> registered and online since **2026-08-06**, which is a *Linux* observer and therefore a
> differently-shaped gate rather than merely a free one. It has run the suite twice and passed both
> times. Every document in this repo went on calling it inert for two days after it started working;
> if you are about to repeat that claim, check `gh run list --workflow=tests.yml` first.
>
> **This does not make your own `./gradlew build` optional.** The nightly observes the default
> branch once a day, so between your push and 01:17 UTC nothing downstream runs a single backend
> test — `ci.yml` is still `-x test`. Run it before you push; the Linux lane tells you the next
> morning what your machine structurally cannot see, which is a second observer and not a
> substitute. `gh workflow run tests.yml` is the on-demand form when you want it sooner.
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
**superseded.** Vitest + RTL are installed and green: 1203 tests across 92 files, `npm run test:run`.
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
55 permissions, all seeded by `V3__seed_permissions_and_roles.sql`; no migration has added one since —
though `V51` **moved three grants between roles**, which is a different thing and the one this table
gets wrong most easily.

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
| `plan:create` · `plan:edit` · `plan:delete` · `run:execute` | — | — | ✓ | ✓ | ✓ | ✓ |
| `defect:create` · `workitem:manage` · `doc:edit` | — | — | ✓ | ✓ | ✓ | ✓ |
| `case:delete` · `evidence:delete` | — | — | ✓ | ✓ | ✓ | ✓ |
| `artifact:edit` | — | — | plan scope only | ✓ | ✓ | ✓ |
| `case:approve` · `suite:approve` · `plan:approve` | — | — | — | ✓ | ✓ | ✓ |
| `suite:delete` · `artifact:delete` | — | — | — | ✓ | ✓ | ✓ |
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

Five things that change how the table reads:

- **The six are the complete role set** — seeded, immutable, **no custom roles, not even
  clone-and-edit**. A client needing something between two tiers gets the higher one. ~~The six are
  the complete *assignable* set~~ → **`V41`: five of them are.** `Automation Service` is a CI
  credential's capability set, which `V3` has said since M0 and nothing enforced until then.
- **THREE seeded rows have `role.assignable = false` and only two of them are outside the six**
  (`V40`, `V41`).
  `Company Member` (`V15`) is the `company:read` baseline joining confers; **`Agent`** (`V40`) is
  the token-only ceiling for agent access — **Test Engineer minus `library:manage`, `case:delete`,
  `evidence:delete`, `plan:delete` and `company:read`** (~~four~~ **five since `V51`**), enumerated
  once in the roles spec **§A6a** and derived from the catalogue by
  `SeedDataTest.agentIsTestEngineerMinusFive`, not copied into a fourth table. `library:manage` is
  dropped because §A3 gates it by an *impact modal* an API caller never sees; ~~the two deletes
  because `OWN` follows `api_token.owner_id`, so an agent would delete what the human authored~~ →
  **that premise died with `V51` — the three deletes are dropped because agent access creates, reads
  and edits and does NOT delete.** `plan:delete` had to be *promoted* into that list the moment Test
  Engineer gained it: it had been excluded only by inheritance, and the delta check is what caught
  it — a subset check would have stayed green while the stated reason evaporated.
  **`Automation Service` is the third (`V41`) and behaves like `Agent`.**
  **Assignability, issuability, listedness and revocability are FOUR different questions** — the two
  ceilings are non-assignable but issuable **and listed**, so `GET /roles` must keep returning them,
  and only `RoleGrantService`'s **grant** path may read the flag. `V40` §2 and `V41` §1 name the four
  sites that must stay baseline-specific; converting them is a one-line "cleanup" that makes a role
  unissuable — or, on the revoke path, strands every pre-existing grant behind a Revoke that 403s.
- **An API token never confers its own permissions; it *caps* its owner's:**
  `evaluate(ownerId, projectId) ∩ permissionsOf(token.roleId) ∩ [the token's project]`. Intersection,
  never union — so a token exceeds neither its role nor its owner, and demoting a person shrinks
  every token they issued. `OWN` narrows through it too (`PermissionQualifier.narrowedBy`): a
  company-wide `ALL` capped by a role's `OWN` is `OWN`. **Nothing carries `OWN` since `V51`** — see
  the bullet below.
- **NO GRANT IS `OWN`-QUALIFIED (`V51`, §207).** CRUD on plans, cases and steps belongs to every
  non-Viewer contributor **no matter who first created the row** — so `case:delete` and
  `evidence:delete` lost the qualifier and Test Engineer gained `plan:delete`. The mechanism is kept
  **deliberately unpopulated**: the qualifier is *data*, so deleting `PermissionQualifier.OWN` or
  `IdentityProjectAccess.canDoOnAuthored` is what would make a future `OWN` grant fail **open**, and
  `PLAN_SCOPE` keeps the same column and seam in use anyway. `SeedDataTest.ownIsCarriedByNoGrant`
  pins the empty population in both directions so it reads as a decision, not a seeding bug. The
  authorship *plumbing* on both sides stays wired for the same reason — `CaseDeleteService`'s gate,
  its `NotTheAuthor` refusal, `useCan.canOnAuthored`, and the `authorId` every `PermissionGate`
  delete site passes. **`suite:delete` / `artifact:delete` stay Lead-only (shared structure, not
  authored content) and so do `run:close` / `result:amend` (judgements about the work), and
  author-cannot-approve-own-work is untouched — approving is not CRUD.**
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
