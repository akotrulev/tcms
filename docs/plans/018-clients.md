# Clients (Mc) Implementation Plan

**Date:** 2026-09-05 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The plugin skills
> `CLAUDE.md` names are mandatory: `/be-java` + `be-java-database-code` for the backend,
> `data-postgresql` / `data-migrations` for T2, `/fe-react` for every screen.

**Goal:** A company records a client organisation and its contacts, attaches a project to it, and
the client field, filter and per-client project rollup appear where they are relevant — while a
company with **no** clients sees none of it and is never told it is missing one.

**Architecture:** A new Spring Modulith module `com.tcms.crm` owns `client` and `client_contact` and
every read that names a client. `project` is a foundation module and **may not depend on `crm`**, so
the project side is inverted the way `PeopleDirectory`, `TeamDirectory` and `TrackerBindingDirectory`
already are in that package: `project` declares `ClientDirectory`, `crm` implements it, and
`project` stores nothing but the `client_id` UUID that `V2__pm_foundation.sql` already shipped
without a constraint. The frontend adds one query module behind the existing barrel, three client
screens, and a client field on four project surfaces — each of which renders **nothing at all** when
the company has no clients.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · Flyway (`V11`) · Postgres 17 with `FORCE ROW LEVEL
SECURITY` · React 19 · TanStack Query · Tailwind v4 + the render kit · Vitest + RTL · `@tcms/client`,
`@tcms/cli`, `@tcms/mcp`.

**Module:** Mc of [009-project-management-expansion-modules.md](009-project-management-expansion-modules.md).
**Depends on:** M0 — which reads `done 2026-08-30 @ 24f997bb` in that plan's status table. That gate
is satisfied; no other module's is needed.

**Tasks** — in the order they are done:

| # | Task | Stack | Agent | Deliverable | Depends on |
|---|---|---|---|---|---|
| 1 | T1 — The client contract, both generated clients, and the CLI/MCP verbs | `ts` | `tooling-engineer` | Eight client path items in `openapi.yaml` with `required` on every schema; both generated clients regenerated; a factory per resource in both fixture files; four CLI commands and four MCP tools; the surface doc. Every endpoint answers a real `404` until T3 | none |
| 2 | T2 — `V11` — `client`, `client_contact`, and the `project.client_id` FK | `be` | `data-architect` | `V11__crm_clients.sql` applied; two tables with `FORCE` RLS; `project.client_id` gains `REFERENCES client(id)` and stays nullable; `SchemaConventionTest` 100 → 102 | T1 |
| 3 | T3 — The `crm` module, the `ClientDirectory` inversion, and the project's client | `be` | `be-java` | The eight endpoints serve real rows; a project attaches, changes and detaches a client; a contact creates no login; a Company Member holding `company:read` alone is refused every one of them | T2 |
| 4 | T4 — The clients fault arm, the query module, and the three client screens | `fe` | `fe-react` | `/clients`, `/clients/:clientId` and the create dialog render on the kit; with zero clients the list renders `clients-empty.html`'s state and offers the first one; `tcmsFaults.clients('none')` drives that state on a populated instance | T3 |
| 5 | T5 — The client on the project surfaces, its proven absence, and the close-out | `fe` | `fe-react` | The Client filter and per-project client on `/projects`, the Client field on project creation and General settings, the client chip on the project overview — each asserted **absent** with zero clients; `docs/features.md`, plan 009's status table and the M8 prompt closed | T4 |

**Budget.** Five tasks, one stack and one session each, per `CLAUDE.md`'s task-cut rule. T1 is the
largest read (`openapi.yaml` sections, `packages/cli/src/{reads,writes}.ts`,
`packages/mcp/src/{reads,writes}.ts`) and lands as one commit because plan 009 requires the contract
and both generated copies to be atomic. T3 and T5 are the two that could have overflowed; both were
measured against 016's and 017's equivalents, which landed at comparable size.

---

## Prompts — one per task

One task is one session. Each block below is complete on its own — what to read, in what order, the
traps that specific task will hit, what to commit and which gate to run. **A prompt is living text:**
a task that lands something different from what this plan predicted rewrites the prompts below it
before it stops, in place, with no changelog appended.

### Prompt — T1 — The client contract, both generated clients, and the CLI/MCP verbs

```
You are `tooling-engineer` working in the tcms repo. Read CLAUDE.md first.

Read, in this order: docs/plans/018-clients.md §T1 and its "Decisions" section — D-d, D-e, D-f and
D-m decide shapes you will otherwise get wrong · CLAUDE.md · docs/specs/openapi.yaml's `time` block
(the pattern for a whole new tag, added by 016 T1) and its Project / ProjectInput / ProjectSettings /
ProjectSettingsUpdate schemas around lines 11428-11930 · the /projects/{projectId}/settings and
/settings/review-impact path items around lines 962-1052 · packages/client/src/test/fixtures.ts
around `timesheetPeriod` (where bodies actually live) · packages/cli/src/reads.ts around `time list` ·
packages/mcp/src/reads.ts around `tcms_read_timesheet` ·
docs/specs/2026-07-30-tcms-roles-permissions-naming.md §A3 lines 362 and 376.

`git show --stat aaf6ca27` is the exact file set a contract commit in this repo touches. Yours is
that same set.

Deliver: eight client path items under a new `clients` tag plus the four project-side schema
additions, each schema carrying its `required` array; frontend/src/api/schema.d.ts and
packages/client/src/schema.ts regenerated from it; a factory per new resource in BOTH
`src/test/fixtures.ts` copies; four CLI commands and four MCP tools; and
docs/specs/2026-08-08-mcp-tool-and-command-surface.md §3 listing them. Every endpoint answers a real
404 from a path the server does not serve until T3 — contract-first, never a stub returning a fake
success.

Traps this task will hit:
- `required` does NOT descend. Every inline nested object needs its own array, and a schema without
  one makes every generated property optional — which typechecks and is wrong everywhere.
- **`contractBodies.ts` needs NO change, and both 016 T1 and 017 T1 recorded the same finding.** Both
  copies are the schema WALKER; the marked region is `violationsOf` and its helpers and holds no
  response bodies. Bodies are factories in `frontend/src/test/fixtures.ts` and
  `packages/client/src/test/fixtures.ts`, which are NOT byte-identical to each other — the frontend
  spells `aClient(overrides): Client` against a named type, the package
  `client(overrides): Schemas['Client']`.
- **`clientId` is already taken in this contract** for the OIDC identity-provider schemas
  (`com.tcms.identity.IdentityProvider`). Nothing you add may collide with it in the generated types
  or in the CLI verb table — this is why the CRM verbs are `client list` / `client show` and the
  schemas are `Client` / `ClientRef` / `ClientDetail` rather than anything spelled `clientId`-first.
- There are still TWO generated clients. `npm run gen:api` runs in `frontend/` and again in
  `packages/client/`; `schemaFreshness.test.ts` compares them and fails on a half-done pass.
- **`clientId` on `ProjectSettingsUpdate` is `required` AND nullable, and every other field's
  reasoning is the opposite of this one's.** Read D-d before writing it: `PUT …/settings` is a full
  replacement, `trackTime` is the field the server reads as `true` when omitted, and an omitted
  nullable id has no safe reading in either direction — so this one is refused when absent rather
  than defaulted, or the Review screen's save silently unbills a project.
- **The picker is NOT on `ProjectSettings`.** That document is readable by anyone who can see the
  project and says so in its own description; putting the client list on it hands every client to a
  Viewer. It is `GET /projects/{projectId}/settings/client-options`, gated on `project:settings` —
  read D-e.
- `declaredPermissionsOf` reads the gate out of the operation's **prose**, matching ``Requires `x:y` ``
  — a write operation whose description does not spell it that way silently asserts nothing in both
  `writeConformance.test.ts` files.
- **Add no permission key and move no grant.** `client:read/create/edit/delete` are seeded in
  `V2__pm_foundation.sql` lines 363-366 and already present in all five documents `SeedDataTest` and
  `matrixCopies.test.ts` compare. The `PermissionKey` enum is unchanged by this task.
- Nothing on this surface returns a rate, a cost or an hour. Say so in the MCP tool descriptions, as
  `tcms_read_timesheet` does.

When done: run `~/bin/tcms-remote sync` then `~/bin/tcms-remote packages npm run build`,
`~/bin/tcms-remote packages npm run test:run --workspaces` and `~/bin/tcms-remote frontend npm run build`.
Commit exactly the paths in §T1 step 9 as
`feat(crm): the client contract, both clients, and the CLI/MCP verbs`. Write the record back into §T1
of the plan, and rewrite the prompt blocks below yours if the operation names, paths or schema shapes
you landed differ from what they assume.
```

### Prompt — T2 — `V11`: `client`, `client_contact`, and the `project.client_id` FK

```
You are `data-architect` working in the tcms repo. Read CLAUDE.md first, then load
`data-migrations` and `data-postgresql`.

Read, in this order: docs/plans/018-clients.md §T2 and decisions D-a, D-k and D-l ·
backend/src/main/resources/db/migration/V10__staffing.sql in full — it is the house style for the
header comment, the company_isolation policy text and the GRANT · V2__pm_foundation.sql lines 295-310,
which is the column you are constraining and the comment explaining why it shipped without a
constraint · docs/specs/tenancy-invariants.md §0.1 and its two spellings of "unset GUC" ·
docs/specs/2026-07-30-tcms-erd.md §0.1 for the company_id rule and the RLS exception list.

FIRST COMMAND, before anything else: `ls backend/src/main/resources/db/migration/`. The plan says
V11 and the directory is the authority. This column has been corrected SIX times — q3 said V3, the
breakdown projected V8, plan 009 struck through V8 and V9 — and `out-of-order` is unset in
application.yml, so a reused number is SILENTLY not applied while Testcontainers builds every test
database from empty and can never show you the conflict.

Deliver: V11__crm_clients.sql creating `client` and `client_contact`, the FK on project.client_id,
both tables under FORCE ROW LEVEL SECURITY with the standard company_isolation policy, and
SchemaConventionTest's counts updated.

Traps this task will hit:
- **Copy the company_isolation policy text character for character out of V10.** There are two
  spellings of the unset-GUC case and only one fails closed; do not retype it from memory or from
  the plan document.
- `company_id UUID NOT NULL` on both tables, leading every composite index and every uniqueness
  constraint. `uq_client_name` is `(company_id, name)`, never `(name)`.
- **`project.client_id` stays NULLABLE forever and there is no synthetic "Internal" client row.**
  That row would put a fake customer on every internal project and make the one question the column
  exists to answer unanswerable (scope D1 rule 1).
- **The FK is `ON DELETE RESTRICT`, not CASCADE and not SET NULL.** A client with projects is
  refused, and ClientService answers 409 naming archiving before the database sees the statement.
- **The FK does not check the company.** It is on `id` alone and referential integrity is not subject
  to RLS, so a cross-company id passes it. There is no composite-FK precedent in this schema — do
  not invent one; the rule is enforced in the service in T3 (D-l). Write that into the comment.
- `client_contact` has NO credential, NO invitation, NO app_user link, and `email` is neither unique
  nor citext — two contacts at one client may share `invoices@`, and uniqueness here is the first
  step toward treating the row as an identity.
- `ExportCompanyService` needs NO edit: `ExportTables` derives the walked set from the tables
  carrying a company_isolation policy, so both tables are exported by construction. If
  `ExportTablesTest` fails, the policy is wrong — not the exporter.
- Carry the *why* in comments as V1, V2 and V10 do. This is the file the next reader learns the
  module's rules from.

When done: `~/bin/tcms-remote sync` then `~/bin/tcms-remote backend './gradlew test --tests "com.tcms.tenancy.*"'`.
`BUILD FAILED` with `Could not write XML test results` and ZERO assertion failures is an fd limit,
not a red suite — `ulimit -n 65535; ./gradlew build`. Read docs/specs/shanks-runner-details.md first.
Commit as `feat(crm): V11 — client, client_contact, and the project.client_id FK`, and correct §T2
and D-a of the plan if the directory gave you a number other than V11.
```

### Prompt — T3 — The `crm` module, the `ClientDirectory` inversion, and the project's client

```
You are `be-java` working in the tcms repo. Read CLAUDE.md first, then load
`be-java-database-code`.

Read, in this order: docs/plans/018-clients.md §T3 and decisions D-c, D-d, D-e, D-k, D-l, D-o ·
backend/src/main/java/com/tcms/project/TrackerBindingDirectory.java IN FULL — it is the pattern this
task performs and its Javadoc makes the argument · com/tcms/project/PeopleDirectory.java and
identity/IdentityPeopleDirectory.java (the same pattern, the other end) ·
com/tcms/staffing/{StaffingController,StaffingService}.java for the controller/service/gate shape ·
com/tcms/project/GateResult.java for the sealed outcome · com/tcms/project/ProjectService.java
around `readInCompany` / `writeInCompany` / `holdsInCompany` · the `time` module's
TimeMoneyBoundaryTest as the shape of the negative test you are writing.

Deliver: com.tcms.crm serving the eight endpoints T1 declared; project.ClientDirectory declared in
`project` and implemented in `crm`; client_id mapped on Project with a reader AND a writer; the
client accepted on create and on the settings document; and the negative test.

Traps this task will hit:
- **`project` may NOT import `crm`.** `crm` is not one of the five foundation modules (api, tenancy,
  identity, project, storage). The direction crm→project is fine; project→crm is the whole reason
  ClientDirectory exists. Do NOT propose a sixth foundation module — ArchitectureRules.FOUNDATION_MODULES'
  Javadoc states the bar, and adding one is a decision that needs a paragraph, not an import.
- **Do NOT map Client as a `@ManyToOne` on Project.** It would be the import above, and
  `ddl-auto: validate` would then check two mappings over one table — `execution.CaseFacts`' recorded
  reason for refusing that shape. `client_id` is a plain UUID column on Project.
- **Every repository INTERFACE carries `@Transactional(readOnly = true)`.** Spring Data gives a
  transaction only to the inherited CRUD methods; without it every derived and @Query method runs
  with no company set and every RLS-protected read returns ZERO ROWS — fail-closed and silent, and
  indistinguishable from a company with no clients, which is the one state this module must render
  precisely. This is the single most likely way to lose a day on this task.
- `Project.clientId` needs BOTH a getter and a setter. ArchitectureRulesTest sweeps @Entity fields
  for readerless AND writerless columns in both directions — that sweep is exactly why V2 left the
  column unmapped until now.
- **Check permissions, never role names.** There is no wildcard permission. Add no key (D-o).
- **The cross-company clientId is a 400, not a 500.** Read the client back through RLS before
  writing; an id in another company reads as empty and answers 400 with a field error on `clientId`.
  A constraint violation would be the wrong status and an oracle for another tenancy (D-l).
- **`DELETE /clients/{id}` answers 409 while a project references it** and names archiving. Do not
  null those projects' client_id on the way past.
- The negative test must ASSERT the refusal, not read roles spec §A3 and conclude it holds — §A3 is
  the source the seed is built from, so a test consulting it would agree with a seed that had
  inverted the grant. And the "creates no login" half is a claim about ABSENCE: assert over the
  identity tables directly (app_user, company_membership, invitation counts unchanged), because a
  service test cannot see an insert it does not make.
- Booting the real context is itself a gate — `hibernate.ddl-auto: validate` catches entity drift at
  startup. Java compiles with -Werror; any suppressed lint needs a recorded reason.

When done: `~/bin/tcms-remote sync` then `~/bin/tcms-remote backend './gradlew build'`. Watch for
ArchitectureRulesTest and SchemaConventionTest specifically. `BUILD FAILED` with `Could not write XML
test results` and zero assertion failures is an fd limit — read docs/specs/shanks-runner-details.md.
Commit as `feat(crm): the client module, the ClientDirectory inversion, and the project's client`,
staging only the paths in §T3. Write the record back into §T3, and rewrite T4's and T5's prompts if
the response shapes you landed differ from what T1 declared.
```

### Prompt — T4 — The clients fault arm, the query module, and the three client screens

```
You are `fe-react` working in the tcms repo. Read CLAUDE.md first, then run the /fe-react
orchestrator command — NOT `Skill(fe-react:fe-react)`, which returns "Unknown skill" even when the
plugins are loaded correctly.

Read, in this order: docs/plans/018-clients.md §T4 and decisions D-g, D-h, D-j, D-n · the mockups
docs/mockups/clients-empty.html, clients.html, client.html and client-new.html — they are the source
of truth for the screens · frontend/src/api/queries/staffing.ts as the query-module pattern ·
frontend/src/api/queries/keys.ts (the ONE factory) · frontend/src/lib/devFaults.ts, especially the
tracker and time arms and `verdictFor`'s ordering comments · frontend/src/app/AppShell.tsx around
line 570-600 (the company rail and the one gated entry) · frontend/src/app/router.tsx around the
/staffing and /admin/teams routes.

Deliver: the clients fault arm; src/api/queries/clients.ts behind the existing barrel; client keys in
keys.ts; the clients list, the client detail, the create dialog and the contacts panel; the routes
and the rail entry; component tests.

Traps this task will hit:
- **Build the fault switch BEFORE the screens it proves.** D-n: this module's acceptance criterion is
  an ABSENCE, and on a seeded instance it cannot be reached without deleting every client. The arm
  fabricates a population, not a failure — `'none'` answers [] to the list and to the picker.
- **Anchor the fault arm's path matches.** `/^\/clients$/`, never `startsWith('/clients')` — a prefix
  also catches `/clients/{clientId}` and would answer a ClientDetail read with an array. This is
  `isRankWrite`'s recorded reason and it has bitten three arms already.
- **Both devFaults guards stay a literal `import.meta.env.DEV`** so Rollup drops the module from a
  production build. Route that check through an imported constant and it ships.
- **`keys.ts` is the ONE factory. Never add a second.** Client options are keyed under
  `['projects', p, 'client-options']`, not under `clientsRoot` — read the comment in §T4 step 2 for
  why the invalidation decides that.
- **The empty state is the module, not a nicety.** Use `clients-empty.html`'s copy verbatim; it is
  the only trace a client-less company ever sees and its wording is the explanation.
- **Do NOT render the Hours to date / Last invoice columns, the three-tile row's hours and invoices
  tiles, the Hours-and-invoices table, or the Rate card panel** — all M8's (D-g). `BillingSlot` is
  the reserved region and reads no endpoint. **Do NOT render the Contracts panel** — it belongs to no
  module at all (D-h).
- **The rail entry IS gated, on `client:read`** — the exception to that rail's ungated default,
  following 005 C2's `token:issue` precedent. Carry the comment; an ungated entry contradicts the
  criterion that a Company Member holding only `company:read` sees no client surface anywhere.
- Routes go OUTSIDE ProjectGuard — these are COMPANY scope, like /staffing and /admin/teams.
- Render kit only: Tailwind utilities as the single authority, components from src/components/kit and
  src/components/ui. No `sx`, no CSS-in-JS, no second stylesheet. `DataRegion` for the
  loading/loaded/failed/empty/stale matrix; `Identifier` for anything monospace. New enums into
  lib/labels.ts as `satisfies Record<Union, string>`.
- **Never build your own QueryClient in a test** — only `createTestQueryClient`'s has the caches. A
  test whose render trips an error boundary FAILS unless it calls `renderingIntoAnErrorBoundary()`
  and says why; likewise `drivingAFailedRead()` / `drivingAFailedWrite()`.
- Anchor path-keyed mock arms (`/^\/clients\/[^/]+$/`) so a sub-resource is not caught by a prefix.

When done: `~/bin/tcms-remote sync` then `~/bin/tcms-remote frontend npm run build`,
`~/bin/tcms-remote frontend npm run lint` (a gate at 0 warnings, not advisory) and
`~/bin/tcms-remote frontend npm run test:run`. `npm run build` is the gate and NOT `tsc --noEmit` —
the latter has reported 0 errors while the build failed with nine. Commit as
`feat(crm): the clients fault arm, the query module, and the three client screens`, staging only §T4's
paths. Write the record back into §T4 and rewrite T5's prompt if what you landed differs.
```

### Prompt — T5 — The client on the project surfaces, its proven absence, and the close-out

```
You are `fe-react` working in the tcms repo. Read CLAUDE.md first, then run the /fe-react
orchestrator command.

Read, in this order: docs/plans/018-clients.md §T5 and decisions D-d, D-f, D-i · the mockups
docs/mockups/projects.html, project-new.html, settings-general.html, and — the acceptance criterion —
solo-overview.html read against project-overview.html, which are the same screen with and without a
client · frontend/src/features/settings/settingsUpdate.ts IN FULL, including its header ·
frontend/src/features/settings/GeneralSettingsPage.tsx's header table, which is where the "Client
select | Mc" row you are closing lives · frontend/src/features/projects/ProjectsPage.tsx's header
comment, which explains why it is a picker and not a grid · docs/plans/009-project-management-expansion-modules.md
"Prompt hand-off protocol".

Deliver: the Client filter and per-project client on /projects, the Client field on project creation
and on General settings, the client chip on the project overview, the absence tests, and the whole
close-out.

Traps this task will hit:
- **Edit `settingsUpdate.ts` FIRST, before the screen.** It is the one place the field list is
  written, and a field added to the contract and not added there is the failure that function exists
  to make impossible to have in only one of the two screens that PUT the document. `clientId` and not
  `client`: the read shape carries a ClientRef, the write carries the id.
- **`clientId` is the one field whose omission the server REFUSES rather than defaults** — the
  opposite of `trackTime` directly above it. Read D-d; sending `undefined` is a 400, and reading an
  absent value as "detach" is what that decision exists to prevent.
- **`/projects` is a card picker and stays one** (D-i). The mockup's table needs Lead, Next release,
  Open work and Completion — four fields no endpoint serves, on plan 008 T19's deferred list. Add the
  client to each card and a Client filter; do not rebuild the picker as a grid.
- **Filter state goes in the URL** via `useSearchParams`. This codebase's rule: component state sends
  a link that is not filtered.
- **Every one of the six surfaces renders NOTHING when the company has no clients** — not disabled,
  not "None", not an empty select. An empty "Client: —" is exactly the nagging solo-overview.html
  rules out, and GeneralSettingsPage.tsx's own table already says so.
- **Assert the absence explicitly, never as an untested implication of a conditional** — and assert
  the mirror too, so the conditional is proven in both directions rather than proven off. This is the
  module's real acceptance criterion; every one of these is a place a conditional could be written
  backwards and still typecheck, lint clean and render.
- Leave `settings-general.html`'s Budget row absent. It is M8's — no hours ceiling, no rate, no
  Billing screen — and 016 T5 correctly declined to render a number it could not source. Only
  re-point its cross-reference.
- Run ALL THREE gates, not just the frontend: `citedFiles.test.ts` fails the PACKAGES job when a path
  named in a comment anywhere under frontend/src/** or backend/src/** is not on disk, and this task
  adds several such citations. `tierClaims.test.ts` fails if a comment claims somebody LACKS a
  permission §A3 grants them.

Close-out, per "Prompt hand-off protocol" — this is half the task, not an afterthought:
1. docs/features.md — the section Mc replaces, STRUCK THROUGH and superseded, never deleted.
2. Plan 009's status table — Mc to `done 2026-09-05 @ <short sha>`, with **V11** in the migration
   column and the reservations struck through rather than removed.
3. The built-and-unverified list, honestly. The gates prove it compiles, not that it works: a no-op
   theme toggle, two contradictory percentages on one screen and a provider-ordering bug that
   rendered nothing but the error page all typechecked and linted clean.
4. **Rewrite the M8 prompt in place** with the client endpoints as they landed and the `BillingSlot`
   seam as it landed — that ClientDetail carries no hours, that the hours-and-invoices table is M8's
   WHOLE table rather than three columns of one, that the Rate card and Contracts panels are
   unbuilt, and that V11 is spent so M8 takes whatever `ls` says on its own day.

When done: `~/bin/tcms-remote sync`, then backend `./gradlew build`, frontend `npm run build` +
`npm run lint` + `npm run test:run`, and packages `npm run build` + `npm run test:run --workspaces`.
Two commits: the code as `feat(crm): the client on the project surfaces, and its proven absence`,
then the docs as `docs(plan): Mc done — V11, the built-and-unverified list, and M8's rewritten prompt`.
```

---

## Decisions

Each states the decision **and** the failure it avoids. Several correct
[009-mc-clients-execution-breakdown.md](009-mc-clients-execution-breakdown.md), which was cut on
2026-08-30 against a worktree in which none of M1–M7 had landed; that file says so itself —
*"By the time Mc runs, five modules will have rewritten that prompt — trust the prompt, not this
file, on what endpoints and packages exist."*

### D-a — The migration is `V11`

`ls backend/src/main/resources/db/migration/` on 2026-09-05: `V1 … V10`, with `V9__time.sql` M6's
and `V10__staffing.sql` M7's. **So this module takes `V11`.**

Every other number in circulation for Mc is wrong and each is written down somewhere a reader will
find it: the breakdown's q3 chose `V3` (M1 took it), its *Position* table corrected that to a
projected `V8` (M4 took `V7`, M5 `V8`), and plan 009's own column struck through `V8` and `V9` before
giving up on arithmetic entirely. This is the **sixth** correction to that column in five weeks.

The failure it avoids is silent: `out-of-order` is unset in `application.yml`, so a lower-numbered
migration arriving after a higher one is **never applied**, and Testcontainers builds every test
database from empty — so no suite in this repository can show the conflict. Re-read the directory
immediately before writing the file, even though this decision names a number.

### D-b — The plan document is `018`

`016` is M6 (Time) and `017` is M7 (Staffing), both landed 2026-09-04. The breakdown's q2 chose
`011` and then projected `016`; both are taken. `018` is next free and is never renumbered.

### D-c — `crm` owns every read that names a client; `project` stores a bare UUID and declares an interface

A cross-module dependency may point only at the five foundation modules — `api`, `tenancy`,
`identity`, `project`, `storage` — and `crm` is not one of them. That direction is fine: **`crm` may
import `project`.** The problem is the reverse. `project.Project` needs to serve `client.name` on the
projects list and the overview chip, and `project` may not import `crm`.

The remedy is the one `com.tcms.project`'s own package already uses five times: **declare the
interface in the foundation module and let the downstream module supply it.**
`project.PeopleDirectory` and `project.TeamDirectory` are implemented by `identity`;
`project.TrackerBindingDirectory` by `integration`, whose Javadoc spells the argument out — *"A
cross-module dependency may point only at the five foundation modules, and `integration` is
emphatically not one … So the declaration lives here, in a module both callers already depend on."*

So: `project.ClientDirectory` (interface, in `project`), `crm.CrmClientDirectory` (implementation).
`Project.clientId` is mapped as a plain `UUID`, never as a `@ManyToOne` to `crm.Client` — an
association would be an import, and `ddl-auto: validate` would then check two mappings over one
table, which is `execution.CaseFacts`' recorded reason for not doing that.

**Rejected:** a `project.ClientFacts` native-SQL reader, on the `staffing.TimeFacts` model. That
pattern has five instances and every one of them points **downstream → upstream** (`work` → `tracker`,
`execution` → `repository`, `tracker.release` → `execution`, `time` → `tracker`, `staffing` → `time`).
`project` → `crm` is upstream → downstream, which ArchUnit cannot see because SQL is not a Java
dependency, and a rule that holds only because the checker is blind is a rule that will be broken
again by somebody who reads the code rather than this paragraph.

### D-d — ~~The client is written through the settings document~~ → **its own endpoint**

`GeneralSettingsPage.tsx` records it and the contract confirms it: *"There is no
`PATCH /projects/{projectId}` — the contract has a create, a detail read, an archive and a settings
document, and nothing that renames a project."* So the two write paths for a project's client are:

- **create** — `clientId` on `ProjectInput` (`POST /projects`, `project:create`), which is
  `project-new.html`'s Client field;
- **edit** — `clientId` on `ProjectSettingsUpdate` (`PUT /projects/{projectId}/settings`,
  `project:settings`), which is `settings-general.html`'s.

`project:settings` is exactly the key story C2 requires, so this needs no new gate.

> **Reversed in T3, before either half shipped.** The paragraph below is kept because its reasoning
> is right and its conclusion was wrong, and the difference is worth reading. The client is
> **`PUT /projects/{projectId}/client`** — one field on its own path, the shape
> `PUT /projects/{projectId}/archive` already uses. Why: the plan proposed marking `clientId`
> `required` so an omitted one would be a `400`, and **the server cannot deliver that**. Jackson
> gives an absent field and an explicit `null` the same Java value — the whole reason
> `api.PatchBody` exists here — so a record-bound `PUT` answers `200` and detaches where the
> contract promised a refusal. The screen most likely to do it is Review settings, which does not
> offer the field. A single-field endpoint has no absence to read, and the settings document stays
> what its own header says it is: the project's §6g review policy, which a client is not.

**The trap, and it is `settingsUpdate.ts`'s trap with a nastier payload.** `PUT …/settings` is a full
replacement, and that file's header already explains what an omitted field costs: *"a page that saves
one switch by sending one switch does not save one switch: it silently turns every other one off."*
For a nullable id there is no safe default in either direction — omitting it would **detach the
client** — so `clientId` is declared **`required` and nullable** in `ProjectSettingsUpdate`. Required
means a client that forgets it gets a `400` instead of silently unbilling a project, and
`fullSettingsUpdate` in `frontend/src/features/settings/settingsUpdate.ts` must list it, which is the
one place the field list is written.

### D-e — The picker options are their own endpoint; the current value rides `Project`

`GET /projects/{projectId}/settings` is **readable by anyone who can see the project** and says so in
its description — deliberately, so a Viewer can find out whether to render the review panel. Putting
the client *list* on that document would hand every client the company has to a Viewer.

So the picker is `GET /projects/{projectId}/settings/client-options`, gated on `project:settings`, a
sibling of the existing `/settings/review-impact`. It is served by a `crm` controller on a
project-scoped path — the path segment does not decide which module owns the handler, and `crm` may
import `project` to gate it.

The *current* client is a different question and a much cheaper one: it is on the overview chip and
the projects list, which everyone who can see the project already reads. It goes on **`Project`** and
on **`ProjectSettings`** as `client: ClientRef | null`. Both, not one: the settings `PUT` answers
`ProjectSettings`, and a form whose save response does not carry the field it just saved is a form
that has to invalidate and re-read to show what it did.

### D-f — The create dialog's picker reads `GET /clients`, and that is not an inconsistency

`POST /projects` costs `project:create`, held by Instance Admin and Company Admin
(roles spec §A3 line 362). `client:read` is held by Instance Admin, Company Admin, Account Manager
and Finance (line 376). **`project:create` holders are a subset of `client:read` holders**, so the
create dialog can read the company client list directly and no caller can reach that form without
being able to fill it.

The read is gated client-side with `useCan('client:read')` on `enabled` rather than being allowed to
`403`. A `403` here would raise a notification through `app/queryClient.ts` for a field the user was
never meant to see, which is a failure report about the product rather than about the request.

### D-g — Hours and invoices stay reserved for M8, and the reason has changed

`client.html` draws an **Hours and invoices to date** table — `Period · Project · Approved hours ·
Invoice · Status` — a tile reading *"6 Invoices · last issued 2 Sep"*, and a **Rate card** panel that
says *"Not visible to you"*. `clients.html` draws **Hours to date** beside **Last invoice**.

The breakdown's q4 reserved all of it for M8 because *"M6/M7/M8 [are] not yet built"*. **That premise
is now half wrong** — M6 landed 2026-09-04, `time_entry.project_id` exists and is indexed
`(company_id, project_id, spent_on)`, and a sixth `*Facts` class reading it would be routine. The
decision stands anyway, for a different reason:

- The hours table is **one table and M8 owns three of its five columns.** Building it with two and
  rebuilding it in M8 costs more than M8 building it once, and leaves a half-table in the interim
  that reads as a defect rather than as a boundary.
- Story C3 licenses the deferral in its own wording: *"its projects, contacts, and — **once time and
  billing exist** — hours and invoices to date."* Time exists; billing does not.
- Plan 009 names M8, not Mc, as `issue_time_rollup`'s second consumer.

So the client page ships the **projects rollup and the contacts**, and a named, documented region for
M8 — `ClientPage.tsx`'s `BillingSlot`, which renders the sentence explaining what will appear there
and reads no endpoint. Plan 009 forbids porting a screen a module has no endpoint for; a slot that
draws no data is not that, and it is what T5's close-out hands to M8.

The **Rate card** panel is not rendered at all, not even as "not visible to you": that string in the
mockup is the Account Manager's rendering of a panel that exists for Finance, and with no rate card
in the product it would be a promise about a screen nobody can reach.

### D-h — The Contracts panel is not built, and belongs to no module

`client.html` draws a **Contracts** panel with MSA and SOW rows carrying signed dates. There is no
table for it in Mc's schema (`client`, `client_contact`) and none in M8's declared schema
(`rate_card`, `rate_card_entry`, `budget`, `invoice`, `invoice_line`, `expense`). It is a mockup
element no module owns. Not rendered, and recorded here so the next reader does not assume it was
missed — the same treatment `GeneralSettingsPage.tsx` gave the mockup's two Identity inputs and its
assistant-close row.

### D-i — `/projects` is a card picker, not the table `projects.html` draws

`ProjectsPage.tsx` is *"A picker, not a grid"* — three cards, deliberately — and its header comment
records that the render's extra bars are not drawn because `Project` does not carry them. The
mockup's table has **Project · Key · Client · Lead · Next release · Open work · Completion**, four of
which are fields no endpoint serves and which sit on plan 008 T19's deferred list.

So Mc adds the **client to each project card** and a **Client filter** whose state lives in the URL.
It does not rebuild the picker as a table. The substance of story C3 — *"a Client column and a Client
filter are present"* — is delivered as the client being visible per project and filterable; the
column shape is not, and that is a disagreement with the mockup recorded rather than silently
resolved.

### D-j — The Clients rail entry is gated on `client:read`

Most of the company rail in `AppShell.tsx` is ungated on disable-don't-hide grounds. **This one is
not**, following the one existing exception: *"Company … ~~Shown to everyone, not gated~~ →
superseded 2026-08-28 (005 C2): gated on `token:issue`. `ApiTokenService` argues that the read is
refused rather than narrowed, because an empty array would be a claim about the company told to
somebody who may not ask; an entry into that refusal is the same claim one click earlier."*

The client list is the same shape of claim. Gating it also satisfies the acceptance criterion the
breakdown's `mc8` states — *"A Company Member holding only `company:read` sees no client surface
anywhere"* — which an ungated entry would contradict at the rail.

This does not weaken `clients-empty.html`'s promise that *"This page stays reachable so the first one
can be created."* Everybody who could create a client holds `client:read`; the entry stays for
exactly them.

### D-k — A client is archived, not deleted, once a project references it

`clients.html` filters on `Status: active`, so `client.status` is `ACTIVE | ARCHIVED`.
`DELETE /clients/{clientId}` exists — `client:delete` is seeded and a client recorded by mistake must
be removable — but it answers **`409`** when any project references the client, naming archiving as
the alternative. Nulling those projects' `client_id` on the way past would silently unbill live work,
and this repository already made the opposite decision for projects: *"Archiving hides the project
from lists and freezes its issues. Nothing is deleted."*

### D-l — A cross-company `clientId` is a `400`, not a foreign-key violation

`project.client_id REFERENCES client(id)` checks the row exists; **it does not check the row is in
this company**, because the FK is on `id` alone and referential integrity is not subject to RLS.
There is no composite-FK precedent in this schema — `V10` uses plain `REFERENCES` throughout — so
this is enforced the way every other cross-table rule here is: the **service** reads the client back
through RLS before writing, and a client id belonging to another company reads as empty and answers
`400` with a field error on `clientId`. A `500` from a constraint would be both the wrong status and
an oracle for what exists in another client's tenancy.

### D-m — `contractBodies.ts` holds no fixtures, and is not touched by this module

The breakdown's `mc2` instructs *"Add the client fixtures to the marked byte-identical region of
`contractBodies.ts`"*. **That is wrong about where fixtures live**, and 016's own contract commit
(`aaf6ca27`) recorded the same correction: *"contractBodies.ts is the schema walker and holds no
fixtures."* The shared region is a generic JSON-schema walker driven by `openapi.yaml`; it needs no
edit for a new resource and stays byte-identical across both copies by not being touched.

Fixtures go in `frontend/src/test/fixtures.ts` and `packages/client/src/test/fixtures.ts`.

### D-n — The fault arm this module needs is the empty case

`CLAUDE.md`: *"Build the fault switch before the feature it proves — a resilience behaviour you
cannot trigger is one you have not tested."* Mc's headline behaviour is not a failure mode, it is an
**absence**: with zero clients, six surfaces render nothing. On a `seed-demo-data` instance that
state cannot be reached without deleting every client, and on a fresh instance the populated state
cannot be reached without creating them.

So the arm is `tcmsFaults.clients('none' | 'some')`, which forces `GET /clients` and
`GET /projects/{id}/settings/client-options` to answer `[]` or a fabricated pair. It is what makes
`solo-overview.html` versus `project-overview.html` — an acceptance criterion of this module that
`docs/mockups/README`-adjacent prose calls *"the same screen, for the company that has no clients"* —
something a person can actually walk, in both directions, on one instance.

### D-o — No permission key is added, and none is re-granted

`client:read`, `client:create`, `client:edit` and `client:delete` are already seeded by
`V2__pm_foundation.sql` (lines 363–366) and are already present in all five documents
`SeedDataTest` and `matrixCopies.test.ts` compare. **There is no permission task in this module.**
Adding a key or moving a grant would mean five coordinated document edits and a byte-pinned test, for
nothing this module needs.

`V2`'s own comment records the grant that looks wrong and is not: *"Holds `client:read` and NOT
`client:edit`: an invoice has to name a client, and [editing the record is the Account Manager's
job]"* — that is Finance, and it stays that way.

---

## File structure

**New — `com.tcms.crm`** (T3), shaped like `com.tcms.staffing`:

| File | Responsibility |
|---|---|
| `package-info.java` | The module's charter: what it owns, why it is not a foundation module, and the one rule about logins |
| `Client.java` · `ClientContact.java` | The two entities |
| `ClientStatus.java` | `ACTIVE` · `ARCHIVED` (D-k) |
| `ClientRepository.java` · `ClientContactRepository.java` | Spring Data, each `@Transactional(readOnly = true)` on the **interface** |
| `ClientService.java` | CRUD, the `409` on delete, the `400` on a foreign client (D-l) |
| `ClientController.java` | The eight company-scope endpoints |
| `ProjectClientController.java` | `GET /projects/{projectId}/settings/client-options` — `crm`'s handler on a project path (D-e) |
| `CrmClientDirectory.java` | `implements project.ClientDirectory` (D-c) |
| `ClientView.java` · `ClientDetailView.java` · `ClientWriteRequest.java` · `ClientContactView.java` · `ClientContactWriteRequest.java` | The wire shapes |

**Modified — `com.tcms.project`** (T3): `ClientDirectory.java` (new interface), `Project.java`,
`ProjectView.java`, `ProjectWriteRequest.java`, `ProjectCreateService.java`,
`ProjectSettingsView.java`, `ProjectSettingsRequest.java`, `ProjectSettingsService.java`.

**New — `frontend/src/features/clients/`** (T4): `ClientsPage.tsx`, `ClientPage.tsx`,
`ClientCreateDialog.tsx`, `ClientContactsPanel.tsx`, `BillingSlot.tsx`, plus their tests.

---

## T1 — The client contract, both generated clients, and the CLI/MCP verbs

> **Landed 2026-09-05.** Deliverable met: eight path items, seven schemas plus four project-side
> additions, both clients regenerated, four CLI commands, two MCP tools (five write arms), the
> surface doc's new §3.10. Packages 170 + 138 + 234 green on shanks; frontend build, lint at 0
> warnings, and 2803 tests green.
>
> **Six corrections to what this task predicted**, written in place rather than appended:
>
> 1. **`settingsUpdate.ts` moved into this task from T5**, and it had to. Marking `clientId`
>    `required` made that file a **compile error** the moment the contract regenerated — which is
>    D-d working exactly as designed, one task earlier than the plan expected. The tree must build
>    at every task boundary, so the field list is edited here.
> 2. **Three payload-pinning tests moved in with it**, for the same reason: `ReviewSettingsPage`
>    and `GeneralSettingsPage` assert the whole `PUT` body field for field, so the new field made
>    them red. They are the tests that exist to catch exactly this, and they caught it.
> 3. **Neither create arm carries an `Idempotency-Key`**, and this was not foreseen. The parameter
>    deduplicates within `(company, project, key)` — its own description says *"scoped to the
>    project the request is addressed to"* — and a client is addressed to **no project**. So both
>    creates hit the wall `staffing absence` hit first, and both are recorded in
>    `CREATE_WITHOUT_A_KEY` in both packages with what the omission costs: `client create` is
>    protected by `uq_client_name` instead, and `contact-add` is protected by nothing, which is
>    stated rather than implied.
> 4. **`tcms_write_client` spans two permissions**, which no tool on that surface had done. §2 is
>    satisfied by the **arm table** — `record` costs `client:create`, the other four `client:edit` —
>    rather than by splitting the tool, because splitting would have produced a one-arm tool beside
>    a four-arm sibling over one noun. `narrow(held)` tells a credential holding one key which half
>    it reaches. Tool count 37 → **39**, recorded in `writes.test.ts`'s running argument.
> 5. **The `edit` arm reads before it writes.** `PUT /clients/{clientId}` is a full replacement, so
>    an arm sending only `status` would blank the location, industry and notes — the same
>    save-that-discards defect `settingsUpdate.ts` prevents one stack over.
> 6. **`frontend/src/features/help/sections.tsx` is part of a contract commit** and this task's file
>    list omitted it. The in-app guide is compared to both registries by `guideCopies.test.ts` in
>    **both** packages, so a verb absent from it reddens them; `git show --stat aaf6ca27` had it.
>
> **Confirmed as predicted:** `contractBodies.ts` needed no edit (D-m), no permission key was added
> or moved (D-o), and no CRM name collided with the OIDC `clientId` (the schemas are `Client` /
> `ClientRef` / `ClientDetail`, the verbs `client list` / `client show`).
>
> **Pre-existing and NOT this task's:** `CaseGrid.test.tsx` fails two selection tests on shanks.
> Verified by stashing this work, re-syncing and re-running: 2 failed / 13 passed on clean `master`.
> Untouched here, and out of this module's file set.

**Deliverable:** `docs/specs/openapi.yaml` carries eight client path items and the project-side
client fields, every schema with its `required` array; `frontend/src/api/schema.d.ts` and
`packages/client/src/schema.ts` are regenerated from it in the same commit; both fixture files carry
a factory per new resource; `packages/cli` exposes four `client` commands and `packages/mcp` four
tools; `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` lists them. Every new endpoint answers
a real `404` from a path the server does not serve — not a stub returning a fake success.
**Depends on:** none
**Stack:** `ts` · **Agent:** `tooling-engineer`

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Modify: `frontend/src/api/schema.d.ts` *(generated — `npm run gen:api` in `frontend/`)*
- Modify: `packages/client/src/schema.ts` *(generated — `npm run gen:api` in `packages/client/`)*
- Modify: `frontend/src/test/fixtures.ts`, `packages/client/src/test/fixtures.ts`
- Modify: `packages/cli/src/reads.ts`, `packages/cli/src/writes.ts`
- Modify: `packages/mcp/src/reads.ts`, `packages/mcp/src/writes.ts`
- Modify: `packages/cli/src/writeConformance.test.ts`, `packages/mcp/src/writeConformance.test.ts`
- Modify: `frontend/src/features/help/sections.tsx`
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

### Read first

`openapi.yaml`'s `ProjectSettings` (line ~11428), `ProjectSettingsUpdate` (~11538), `Project`
(~11861) and `ProjectInput` (~11899); the `/projects/{projectId}/settings` and
`/settings/review-impact` path items (~962–1052); and — as the shape to copy for a whole new tag —
the `time` block 016 added. `git show --stat aaf6ca27` is the exact file set a contract commit in
this repo touches, and this task's list is that one.

- [ ] **Step 1: Add the eight path items under a new `clients` tag**

Company scope throughout. `ClientId` joins `components/parameters` beside `ProjectId`.

```yaml
  /clients:
    get:
      tags: [clients]
      operationId: listClients
      summary: The company's client organisations
      description: |
        Needs `client:read` — Instance Admin, Company Admin, Account Manager and Finance
        (roles spec §A3). Finance holds the read and not the edit: an invoice has to name a
        client, and maintaining the record is the Account Manager's job.

        **An empty array is a first-class answer, not an empty state to be filled in later**
        (scope D1). A company building its own products never creates a client, and every
        client-shaped surface in the product is absent — not greyed, not empty — while this
        answers `[]`. This endpoint stays reachable so the first one can be created.
      parameters:
        - name: status
          in: query
          required: false
          schema: { type: string, enum: [ACTIVE, ARCHIVED, ALL], default: ACTIVE }
          description: |
            Defaults to `ACTIVE`, which is what `clients.html`'s filter opens on. `ALL` is what
            a picker asks for when it must still show the client a project is already attached
            to — archiving hides a client from the list, and a project pointing at one is not
            thereby pointing at nothing.
        - name: accountManagerId
          in: query
          required: false
          schema: { type: string, format: uuid }
      responses:
        '200':
          description: The clients, newest first
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/Client' } }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
    post:
      tags: [clients]
      operationId: createClient
      summary: Record a client organisation
      description: |
        Needs `client:create`. **A name is the only required field** (story C1): location,
        industry, account manager and notes are optional, and a client with a name and nothing
        else is a complete client.

        A client is a record, not a principal. Creating one creates no `app_user`, no
        `company_membership` and no invitation — see `ClientContactInput` for the same rule one
        level down.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ClientInput' }
      responses:
        '201':
          description: The client as created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Client' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }

  /clients/{clientId}:
    parameters: [{ $ref: '#/components/parameters/ClientId' }]
    get:
      tags: [clients]
      operationId: getClient
      summary: One client, its contacts and the projects attached to it
      description: |
        Needs `client:read`. The contacts and the project rollup arrive **inside this document**
        rather than as two more round trips: `client.html` renders all three together and
        neither is paged.

        **No hours and no invoice figures.** `client.html` draws them; they are M8's, and this
        module ships the client page with a named region reserved for them rather than a
        half-built table (plan 018 D-g).
      responses:
        '200':
          description: The client
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ClientDetail' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
    put:
      tags: [clients]
      operationId: updateClient
      summary: Edit a client
      description: Needs `client:edit`. A full replacement of the fields `ClientInput` carries.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ClientInput' }
      responses:
        '200':
          description: The client as it now stands
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Client' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
    delete:
      tags: [clients]
      operationId: deleteClient
      summary: Delete a client no project references
      description: |
        Needs `client:delete`.

        **`409` when any project references this client**, naming archiving as the alternative
        (plan 018 D-k). Nulling those projects' client on the way past would silently unbill
        live work, and this product already made the opposite call for projects: archiving
        hides and freezes, and nothing is deleted.
      responses:
        '204': { description: Deleted }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }

  /clients/{clientId}/contacts:
    parameters: [{ $ref: '#/components/parameters/ClientId' }]
    post:
      tags: [clients]
      operationId: addClientContact
      summary: Add a contact to a client
      description: |
        Needs `client:edit`. Contacts are read through `GET /clients/{clientId}`, which carries
        them; there is no list endpoint here because there is no second reader.

        **A contact never becomes a login** (scope D1, story C1). This creates a row of contact
        information — a name, an email, a phone, a role — and no `app_user`, no
        `company_membership`, no invitation and no authentication path. If that ever changes,
        D1 is reopened rather than extended: a client portal is a second authentication path
        and a real confidentiality boundary.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ClientContactInput' }
      responses:
        '201':
          description: The contact as created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ClientContact' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /clients/{clientId}/contacts/{contactId}:
    parameters:
      - { $ref: '#/components/parameters/ClientId' }
      - name: contactId
        in: path
        required: true
        schema: { type: string, format: uuid }
    put:
      tags: [clients]
      operationId: updateClientContact
      summary: Edit a contact
      description: Needs `client:edit`. Creates no login, for `addClientContact`'s reason.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ClientContactInput' }
      responses:
        '200':
          description: The contact as it now stands
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ClientContact' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
    delete:
      tags: [clients]
      operationId: deleteClientContact
      summary: Remove a contact
      description: Needs `client:edit`. Removes a record; there was never an account to disable.
      responses:
        '204': { description: Removed }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /projects/{projectId}/settings/client-options:
    parameters: [{ $ref: '#/components/parameters/ProjectId' }]
    get:
      tags: [clients]
      operationId: getProjectClientOptions
      summary: The clients this project may be attached to
      description: |
        Needs **`project:settings`** on this project — *not* `client:read` (scope D1, plan 010
        D-M0-9). Project Manager is PROJECT-scope, so a company-wide `client:read` would be an
        inert row for them; and granting `client:read` to the `Company Member` baseline would
        break the one sentence that role is defined by — "exactly `company:read` and nothing
        else".

        It is a **separate path from `GET …/settings`** because that document is readable by
        anyone who can see the project, deliberately, and the client list is not: an empty array
        is a claim about the company told to somebody who may not ask.

        Answers `[]` for a company with no clients, which is what makes the Client field absent
        rather than empty on `settings-general.html`.
      responses:
        '200':
          description: The pickable clients, plus any archived client this project already names
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/ClientRef' } }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
```

- [ ] **Step 2: Add the schemas — every one with its `required` array, inline objects included**

```yaml
    ClientRef:
      type: object
      required: [id, name]
      description: |
        A client named from somewhere that is not the client screen — a project card, the
        overview chip, a picker option. Two fields on purpose: anything more would make every
        project read carry a client record.
      properties:
        id: { type: string, format: uuid }
        name: { type: string }

    Client:
      type: object
      required: [id, name, status, projectCount, contactCount, createdAt]
      properties:
        id: { type: string, format: uuid }
        name: { type: string, maxLength: 200 }
        location: { type: string, nullable: true, maxLength: 200 }
        industry: { type: string, nullable: true, maxLength: 120 }
        notes: { type: string, nullable: true, maxLength: 4000 }
        status:
          type: string
          enum: [ACTIVE, ARCHIVED]
          description: |
            `ARCHIVED` hides the client from the default list and from the project picker —
            except on a project already attached to it, which keeps naming it. Archiving is what
            `DELETE` points at when a project references the client (plan 018 D-k).
        accountManager:
          allOf: [{ $ref: '#/components/schemas/UserRef' }]
          nullable: true
          description: |
            The company member who owns this relationship — `client-new.html`'s "— nobody yet —"
            is `null`, and it is the default. A client with no account manager is not
            incomplete.
        projectCount:
          type: integer
          description: |
            How many projects name this client, **archived ones included**. The count is what
            `DELETE` refuses on, so a count that quietly excluded archived projects would
            promise a delete the server then answers `409` to.
        contactCount: { type: integer }
        createdAt: { type: string, format: date-time }

    ClientInput:
      type: object
      required: [name]
      description: |
        The body of `POST /clients` and `PUT /clients/{clientId}`. **A name is the only required
        field** (story C1). `status` is absent on purpose: a client is created `ACTIVE`, and
        archiving is `PUT` with `status` — see below.
      properties:
        name: { type: string, minLength: 1, maxLength: 200 }
        location: { type: string, nullable: true, maxLength: 200 }
        industry: { type: string, nullable: true, maxLength: 120 }
        notes: { type: string, nullable: true, maxLength: 4000 }
        accountManagerId: { type: string, format: uuid, nullable: true }
        status:
          type: string
          enum: [ACTIVE, ARCHIVED]
          default: ACTIVE
          description: |
            Optional, and `ACTIVE` when omitted. This is the archive control: there is no
            `/clients/{id}/archive` sub-resource, because unlike a project's archive — which
            freezes issues and is a real state transition — a client's is one column and the
            edit form already submits every field.

    ClientDetail:
      type: object
      required: [client, contacts, projects]
      description: |
        `client.html` — the client, its contacts and the projects attached to it, in one
        document. The hours and invoice figures that mockup also draws are **not here**: they
        are M8's (plan 018 D-g).
      properties:
        client: { $ref: '#/components/schemas/Client' }
        contacts:
          type: array
          items: { $ref: '#/components/schemas/ClientContact' }
        projects:
          type: array
          items: { $ref: '#/components/schemas/ClientProject' }

    ClientProject:
      type: object
      required: [id, key, name, archived]
      description: |
        A project on the client page. Deliberately the four fields `Project` already carries and
        nothing more: `client.html` also draws Lead, Next release and Completion, none of which
        is on any project read shape today (they sit on plan 008 T19's deferred list), and a
        column over a number the response does not carry is the defect plan 009 §9.3 names.
      properties:
        id: { type: string, format: uuid }
        key: { type: string }
        name: { type: string }
        archived: { type: boolean }

    ClientContact:
      type: object
      required: [id, name]
      description: |
        Contact information, and **not an identity**. There is no `userId` here and there never
        will be one without reopening scope D1.
      properties:
        id: { type: string, format: uuid }
        name: { type: string, maxLength: 200 }
        role: { type: string, nullable: true, maxLength: 120 }
        email: { type: string, nullable: true, maxLength: 320 }
        phone: { type: string, nullable: true, maxLength: 60 }

    ClientContactInput:
      type: object
      required: [name]
      description: |
        A name is enough — `client.html`'s third contact has no phone, and that is a whole
        contact. **No credential field exists on this schema**, which is the contract's half of
        "a contact never becomes a login": there is nothing to send.
      properties:
        name: { type: string, minLength: 1, maxLength: 200 }
        role: { type: string, nullable: true, maxLength: 120 }
        email: { type: string, nullable: true, maxLength: 320 }
        phone: { type: string, nullable: true, maxLength: 60 }
```

- [ ] **Step 3: Add the client to the four project-side schemas**

`Project` and `ProjectSettings` gain a **read**; `ProjectInput` and `ProjectSettingsUpdate` gain a
**write**. Note the asymmetry in `required` and read D-d before touching `ProjectSettingsUpdate`.

```yaml
    # ── Project: add to `properties`. NOT added to `required` ──────────────────
        client:
          allOf: [{ $ref: '#/components/schemas/ClientRef' }]
          nullable: true
          description: |
            The client this project is for, or `null` for internal work (scope D1). **`null` is
            the common case and is not a deficiency** — the owner's own worked example is a
            company building its own products — so no client renders it as missing, and there is
            no synthetic "Internal" client row: that row would put a fake customer on every
            internal project and make "which projects are contracted work?" unanswerable.

            Absent from `required` because it is absent from every response a company with no
            clients ever gets. A reader that must distinguish "no client" from "clients are not
            a thing here" asks `GET /clients`.

    # ── ProjectInput: add to `properties`. NOT added to `required` ─────────────
        clientId:
          type: string
          format: uuid
          nullable: true
          description: |
            Optional on create — `project-new.html`'s Client field, which is itself rendered
            only when the company has at least one client. A client id belonging to another
            company answers `400` with a field error on `clientId`, never a `500` from the
            foreign key: the FK is on `id` alone and referential integrity is not subject to
            RLS, so the service reads the client back through RLS before writing (plan 018 D-l).

    # ── ProjectSettings: add to `properties`. NOT added to `required` ──────────
        client:
          allOf: [{ $ref: '#/components/schemas/ClientRef' }]
          nullable: true
          description: |
            The project's client, repeated here from `Project` so the `PUT` response carries the
            field it just saved. The **options** are not here: this document is readable by
            anyone who can see the project, and the client list is not — they are
            `GET /projects/{projectId}/settings/client-options`, gated on `project:settings`.

    # ── ProjectSettingsUpdate: add to `properties` AND to `required` ───────────
        clientId:
          type: string
          format: uuid
          nullable: true
          description: |
            **Required, and nullable** — the one field on this schema where omission has no safe
            reading. `PUT …/settings` is a full replacement, and `trackTime`'s note below
            records what an omitted field costs; for a nullable id there is no default that is
            right in both directions, and reading an absent `clientId` as "detach" would let a
            screen that saves a review toggle silently unbill a project. So a body without it is
            a `400`, and `frontend/src/features/settings/settingsUpdate.ts` — the one place the
            field list is written — lists it.
```

- [ ] **Step 4: Regenerate both clients and confirm they are in step**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

Both files must appear in `git status`. `packages/client/src/test/schemaFreshness.test.ts` is what
catches a forgotten half; run it now rather than at the gate:

```bash
cd packages && npm run build && npm run test:run --workspace @tcms/client
```

Expected: PASS, including `schemaFreshness` and `contractPairing`.

- [ ] **Step 5: Add a factory per new resource to both fixture files**

`frontend/src/test/fixtures.ts` and `packages/client/src/test/fixtures.ts`. **Do not touch
`contractBodies.ts`** — it is the schema walker and holds no fixtures (D-m).

```ts
export function aClient(over: Partial<Client> = {}): Client {
  return {
    id: 'c1000000-0000-4000-8000-000000000001',
    name: 'Acme Retail',
    location: 'Stockholm',
    industry: 'retail',
    notes: null,
    status: 'ACTIVE',
    accountManager: null,
    projectCount: 2,
    contactCount: 3,
    createdAt: '2026-03-04T09:00:00Z',
    ...over,
  };
}

export function aClientContact(over: Partial<ClientContact> = {}): ClientContact {
  return {
    id: 'c2000000-0000-4000-8000-000000000001',
    name: 'Hanna Lund',
    role: 'Product owner',
    email: 'hanna.lund@acme.test',
    phone: '+46 8 555 0142',
    ...over,
  };
}

export function aClientDetail(over: Partial<ClientDetail> = {}): ClientDetail {
  return { client: aClient(), contacts: [aClientContact()], projects: [], ...over };
}
```

- [ ] **Step 6: Add four CLI commands**

Reads in `packages/cli/src/reads.ts`, writes in `packages/cli/src/writes.ts`, matching the shape of
the `time list` / `time queue` verbs beside them.

```ts
{
  name: 'client list',
  summary: "The company's client organisations, and how many projects each has",
  usage: 'tcms client list [--status ACTIVE|ARCHIVED|ALL]',
  options: { status: { type: 'string' } },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const status = first(options['status']);
    const rows = await client.GET('/clients', {
      params: { query: status === undefined ? {} : { status } },
      tcms: request,
    });
    if (json) return out.json(rows);
    table(out, rows, [
      { header: 'NAME', value: (row) => show(row.name) },
      { header: 'STATUS', value: (row) => show(row.status) },
      { header: 'PROJECTS', value: (row) => show(row.projectCount) },
      { header: 'CONTACTS', value: (row) => show(row.contactCount) },
      { header: 'ID', value: (row) => show(row.id) },
    ]);
  },
},
```

`client show <id>` renders the detail's three parts; `client create --name` posts a `ClientInput`;
`client contact-add <clientId> --name` posts a `ClientContactInput`. **No `client delete` verb** —
the only delete this module has refuses on a referenced client and is a mistake-correction reachable
from the UI; a CLI verb for it would be an agent-drivable way to lose a record.

- [ ] **Step 7: Add four MCP tools and update the surface doc**

`tcms_list_clients`, `tcms_read_client`, `tcms_create_client`, `tcms_add_client_contact`. Add the
rows to §3's table in `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`, and the verbs to
`frontend/src/features/help/sections.tsx` — the in-app guide is compared to the registry by a test in
both client packages.

- [ ] **Step 8: Run the packages gate on shanks**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run test:run --workspaces
```

Expected: PASS, including `writeConformance`, `guideCopies`, `citedFiles`, `matrixCopies`,
`schemaFreshness` and `contractPairing`.

- [ ] **Step 9: Commit**

```bash
git add docs/specs/openapi.yaml docs/specs/2026-08-08-mcp-tool-and-command-surface.md \
        frontend/src/api/schema.d.ts frontend/src/test/fixtures.ts \
        frontend/src/features/help/sections.tsx \
        packages/client/src/schema.ts packages/client/src/test/fixtures.ts \
        packages/cli/src/reads.ts packages/cli/src/writes.ts packages/cli/src/writeConformance.test.ts \
        packages/mcp/src/reads.ts packages/mcp/src/writes.ts packages/mcp/src/writeConformance.test.ts
git commit -m "feat(crm): the client contract, both clients, and the CLI/MCP verbs"
```

---

## T2 — `V11` — `client`, `client_contact`, and the `project.client_id` FK

> **Landed 2026-09-05. The V actually taken is `V11`**, confirmed by `ls` on the day: `V1 … V10`.
> That matches D-a, which is the first time in six that this column's number survived contact with
> the directory — and it survived because M6 and M7 both landed before Mc rather than after it, as
> the confirmed order had them.
>
> **One correction, and it is the one this task's own step 2 warned about.** The policy text drafted
> in this plan was `current_setting('app.current_company')::uuid`. **That is the wrong spelling.**
> V10's — and V1's, and every policy in the schema —  is
> `nullif(current_setting('app.current_company', true), '')::uuid`, which is the fail-closed one:
> the `true` is `missing_ok`, so an unset GUC yields NULL rather than raising, and the `nullif`
> turns the empty string into NULL too. `SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate`
> compares the policy TEXT and would have failed on the draft. Copied character for character from
> V10, as step 2 instructed and as the plan draft itself did not.
>
> **Two more things the draft had wrong and V10 settled:** there is **no `WITH CHECK`** — a
> USING-only policy reuses USING as the insert check, so the read boundary *is* the write boundary,
> and an explicit `WITH CHECK` fails that same text comparison. And there is **no explicit `GRANT`**
> — `ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner` covers every new table in `public`.
>
> `SchemaConventionTest` 100 → **102**. `ExportTables.java` needed no edit, as predicted: the walked
> set is derived from the tables carrying a `company_isolation` policy, so both are exported by
> construction.
>
> Gates on shanks: the whole `com.tcms.tenancy.*` suite green, plus `ArchitectureRulesTest`,
> `ExportTablesTest` and `SeedDataTest`.

**Deliverable:** `V11__crm_clients.sql` creates `client` and `client_contact`, both with
`company_id UUID NOT NULL` leading every index and uniqueness constraint and both under
`FORCE ROW LEVEL SECURITY`; `project.client_id` gains `REFERENCES client(id)` and stays nullable;
`SchemaConventionTest` counts 102 tables where it counted 100.
**Depends on:** T1 (the shapes the columns must serve)
**Stack:** `be` · **Agent:** `data-architect`

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__crm_clients.sql`
- Modify: `backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java` *(the table/policy counts)*

- [ ] **Step 1: Re-read the directory and confirm the number**

```bash
ls backend/src/main/resources/db/migration/
```

Expected: `V1 … V10`, so the file is `V11`. **Do this even though D-a names the number** — the
directory is the authority, this column has been corrected six times, and a reused number is
*silently* not applied (`out-of-order` is unset) on every instance while every test database is built
from empty and can never show it.

- [ ] **Step 2: Write the migration**

```sql
-- =============================================================================
-- V11 — crm: clients and their contacts (plan 009 module Mc, plan 018)
--
-- THE VERSION IS V11, AND THE DIRECTORY IS WHAT SAYS SO. `ls` on 2026-09-05:
-- V1..V10. Every other number written down for this module is wrong and each
-- is somewhere a reader will find it: the execution breakdown's q3 chose V3
-- (M1 took it), its Position table projected V8 (M4 took V7, M5 took V8), and
-- 009's own column struck through V8 and V9 before giving up on arithmetic.
-- Sixth correction in five weeks. out-of-order is unset in application.yml, so
-- a reused number is SILENTLY not applied, and Testcontainers builds every test
-- database from empty and therefore cannot show the conflict.
--
-- TWO TABLES, AND NEITHER IS A PRINCIPAL. A client is a record describing an
-- organisation; a contact is a name, an email and a phone. No app_user, no
-- company_membership, no invitation, no credential column, no authentication
-- path (scope D1, confirmed 2026-08-30). This is what makes a Client safe as a
-- scope-LESS design rather than merely a cheap one: the only reason it would
-- have needed to become a level of the hierarchy is to gate a login, and there
-- is no login to gate. If that ever changes, D1 is REOPENED rather than
-- extended -- a client portal is a second authentication path and a real
-- confidentiality boundary, and bolting one onto a lookup table is how a
-- tenancy model gets quietly broken.
--
-- NO RATE, NO BUDGET, NO INVOICE. Those are M8's six tables, and the money
-- boundary is the sharpest in the expansion (scope §7): an Account Manager
-- knows the client and the hours and never what they cost. A rate column here
-- would put it one join from a reader this module gates on client:read.
-- =============================================================================

-- 1. CLIENT -- the company's own customer.
--
-- status is ACTIVE|ARCHIVED and not a delete flag. DELETE /clients/{id} exists
-- and answers 409 when a project references the client (018 D-k): nulling those
-- projects' client_id on the way past would silently unbill live work, and this
-- product already decided the other way for projects -- "archiving hides the
-- project from lists and freezes its issues. Nothing is deleted."
--
-- account_manager_id is NULLABLE and that is the default. client-new.html's
-- picker opens on "-- nobody yet --", and a client with no account manager is
-- not incomplete. ON DELETE SET NULL rather than RESTRICT: a person leaving the
-- company must not pin a client record open.
CREATE TABLE client (
    id                  UUID        PRIMARY KEY,
    company_id          UUID        NOT NULL REFERENCES company(id),
    name                TEXT        NOT NULL,
    location            TEXT,
    industry            TEXT,
    notes               TEXT,
    status              TEXT        NOT NULL DEFAULT 'ACTIVE',
    account_manager_id  UUID        REFERENCES app_user(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID,
    lock_version        BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_client_status CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_client_name   CHECK (length(btrim(name)) > 0),
    CONSTRAINT uq_client_name   UNIQUE (company_id, name)
);
COMMENT ON TABLE client IS
    'A company''s own customer -- an ATTRIBUTE of a project, never a level of the hierarchy '
    '(scope D1). It carries no isolation semantics and no permission scope: the two gates stay '
    'company filter then project grant, and a client is visible iff you can see a project that '
    'references it or you hold client:read at company scope. A third gate would have meant a new '
    'RoleAssignment target, a fourth term in evaluateCompanyWide and a second place authorization '
    'is decided -- and the rollups this feature wants need none of that.';
COMMENT ON COLUMN client.account_manager_id IS
    'Nullable, and null is the default rather than a gap -- client-new.html opens on '
    '"-- nobody yet --". SET NULL on the person''s deletion so a leaver cannot pin the record.';
CREATE INDEX ix_client_status  ON client (company_id, status, name);
CREATE INDEX ix_client_manager ON client (company_id, account_manager_id);

-- 2. CLIENT_CONTACT -- a person to talk to, and NOT a person who can log in.
--
-- Read the table comment above for why this is a rule rather than an omission.
-- There is no user_id, no password, no invitation token and no email
-- verification column -- and ClientContactInput in openapi.yaml has no
-- credential field either, so there is nothing to send even if a column
-- appeared. Both halves are asserted by CrmCreatesNoLoginTest.
--
-- email is NOT unique and NOT a citext: two contacts at one client may share an
-- inbox (invoices@), and a uniqueness constraint here would be the first step
-- toward treating this row as an identity.
CREATE TABLE client_contact (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    client_id    UUID        NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,
    role         TEXT,
    email        TEXT,
    phone        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_client_contact_name CHECK (length(btrim(name)) > 0)
);
COMMENT ON TABLE client_contact IS
    'Contact information, not an identity (scope D1). NO credential, NO invitation, NO app_user '
    'link -- and adding one reopens D1 rather than extending it.';
CREATE INDEX ix_client_contact_client ON client_contact (company_id, client_id, name);

-- 3. THE CONSTRAINT V2 SHIPPED THIS COLUMN WITHOUT.
--
-- V2 added project.client_id as UUID NULL with no REFERENCES and no entity
-- mapping, deliberately and in one breath: ArchitectureRulesTest sweeps @Entity
-- fields for readerless and writerless columns in BOTH directions, so a mapping
-- added by M0 would have been an entry Mc then had to remove. The constraint and
-- the mapping land together, here and in T3.
--
-- STILL NULLABLE, FOREVER, and there is no synthetic "Internal" client row to
-- make it NOT NULL (scope D1 rule 1). That row would put a fake customer on
-- every internal project, pollute every client rollup with a company billing
-- itself, and make "which projects are contracted work?" -- the question this
-- column exists to answer -- unanswerable.
--
-- RESTRICT rather than CASCADE or SET NULL: deleting a client that projects
-- point at is refused, and the service answers 409 naming archiving before the
-- database ever sees the statement (018 D-k). The FK is the backstop for a path
-- the service does not own.
--
-- NOTE WHAT THIS FK DOES NOT CHECK. It is on id alone, so it does not check the
-- client is in the same company -- referential integrity is not subject to RLS,
-- and a cross-company id would pass it. There is no composite-FK precedent in
-- this schema (V10 uses plain REFERENCES throughout), so that rule is enforced
-- where every other cross-table rule here is: ClientService reads the client
-- back through RLS before writing and answers 400 with a field error on
-- clientId. A 500 from a constraint would be both the wrong status and an
-- oracle for what exists in another client's tenancy (018 D-l).
ALTER TABLE project
    ADD CONSTRAINT fk_project_client FOREIGN KEY (client_id) REFERENCES client(id) ON DELETE RESTRICT;
CREATE INDEX ix_project_client ON project (company_id, client_id);

-- 4. RLS -- the standard company_isolation policy, FORCED on both.
--
-- The app connects as tcms_app, which owns nothing and holds no BYPASSRLS, so
-- FORCE is what makes the policy apply to the owner too. ExportCompanyService
-- needs NO change for either table: ExportTables derives the walked set from the
-- tables carrying a company_isolation policy, so a company-owned table with the
-- standard policy is exported by construction -- project_key_sequence arrived
-- that way in V2.
ALTER TABLE client         ENABLE ROW LEVEL SECURITY;
ALTER TABLE client         FORCE  ROW LEVEL SECURITY;
ALTER TABLE client_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE client_contact FORCE  ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON client
    USING (company_id = current_setting('app.current_company')::uuid)
    WITH CHECK (company_id = current_setting('app.current_company')::uuid);
CREATE POLICY company_isolation ON client_contact
    USING (company_id = current_setting('app.current_company')::uuid)
    WITH CHECK (company_id = current_setting('app.current_company')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON client, client_contact TO tcms_app;
```

> Before writing the `USING` clause, copy the **exact** text of an existing `company_isolation`
> policy out of `V10__staffing.sql`. `docs/specs/tenancy-invariants.md` records two spellings of
> "unset GUC" and only one of them fails closed; the block above is written from V10's, but confirm
> it character for character rather than trusting this document.

- [ ] **Step 3: Update the convention counts and run the schema tests**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend './gradlew test --tests "com.tcms.tenancy.*"'
```

Expected: PASS. `SchemaConventionTest` moves 100 → 102 tables and 74 + n → 76 + n forced policies;
if it fails on a count, update the count — if it fails on a *missing* `company_id` or a policy, the
migration is wrong, not the test.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V11__crm_clients.sql \
        backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java
git commit -m "feat(crm): V11 — client, client_contact, and the project.client_id FK"
```

---

## T3 — The `crm` module, the `ClientDirectory` inversion, and the project's client

> **Landed 2026-09-05.** `com.tcms.crm` — thirteen files — plus `project.ClientDirectory`,
> `api.ClientRef` and the project-side wiring. Gate on shanks: **`./gradlew build` SUCCESSFUL,
> 3947 tests passed, zero failures** — and no `Could not write XML test results` line, so the
> fd-limit shape `shanks-runner-details.md` warns about did not arise and the green is real.
>
> **The largest correction in the plan so far, and it reverses D-d.**
>
> **`clientId` is NOT on `ProjectSettingsUpdate`. It is `PUT /projects/{projectId}/client`.**
> T1 landed it on the settings document, with the contract declaring it `required` so that a body
> omitting it would be a `400`. Writing the server revealed that claim could not be honoured:
> **Jackson gives an absent field and an explicit `null` the same Java value**, which is the whole
> reason `api.PatchBody` exists in this codebase. So the server would have answered `200` and
> *detached the project* where the description promised a refusal — and the caller most likely to
> do it is the Review settings screen, which does not offer the field at all. A contract that lies
> is worse than the hazard it was written to prevent.
>
> Two ways out: put the settings `PUT` behind `PatchBody` so absence is observable, or give the
> field its own path. The second is right and the repo already had the precedent —
> `PUT /projects/{projectId}/archive`, whose contract note reads *"One field, and it is the target
> state rather than a verb."* One field on its own path **has no absence to read.** It also keeps
> the settings document what its own header says it is: the project's §6g review policy, which a
> client is not.
>
> D-d above is struck through and rewritten. `settingsUpdate.ts` and the three payload-pinning
> tests T1 changed are reverted to what they were.
>
> **Five more corrections:**
>
> 1. **`ClientRef` lives in `com.tcms.api`**, not in `crm` and not nested in `ProjectView`. Two
>    modules serve it and `project` may not import `crm`, so declaring it twice would be two records
>    over one wire shape — which `ArchitectureRulesTest`'s repeated-shape sweep catches. It sits
>    beside `UserRef`, which is in that package for exactly this reason.
> 2. **`ProjectService` and `ProjectCreateService` take `ObjectProvider<ClientDirectory>`**, not the
>    interface directly: `crm` is not on the classpath of every context that builds them, and a
>    missing implementation degrades to "no client on any project" — which is what a company with no
>    clients renders anyway, so the degraded state is the ordinary one rather than a broken screen.
> 3. **`ProjectService.viewOfChanged` became public.** `crm` writes `project.client_id` and must
>    answer with a `Project`; a second assembler there would be a second answer to "what is a Project
>    on the wire". Widening the visibility is cheaper than the drift, and safe — the method gates
>    nothing and is only reachable with an already-gated `Project`.
> 4. **`grantInCompany` moved from `StaffingTestBase` to `TrackerEndpointTestBase`.** `crm` is the
>    second module to need a company-wide grant; it is not staffing's shape, it is the company half
>    of `grantOnProject`.
> 5. **Four registry tests a new module must feed**, none of which this plan predicted, and each of
>    which said exactly what to do: `ModuleStructureTest` wanted `@ApplicationModule` on the
>    package-info **and** `crm` added to the module list with a paragraph; `ArchitectureRulesTest`
>    rejected `findByClientIdInOrderByNameAsc` as a declared query with no caller (deleted, per its
>    own instruction); `ContractConventionTest` wanted a pairing per schema. `crm` declares **four**
>    foundation modules, not five — `storage` is absent because nothing here has an attachment.
>
> **And one homonym, which the breakdown predicted under the wrong name.** It warned that `clientId`
> collides with the OIDC identity provider's field, and the CRM schemas were named to avoid it. The
> collision landed one noun over: `com.tcms.auth.SocialProperties.Client` is an **OAuth** client.
> Registered in `HOMONYMS` rather than renamed — neither name is wrong.
>
> **The one that cost the most time, and it was avoidable in one command.** `ClientEndpointTest` and
> `CrmCreatesNoLoginTest` first took UUID prefixes `fa` and `fb`. Both were **already in use** —
> `fa` is `PlanStatisticsEndpointTest`'s and `fb` is `ClassificationValueWriteEndpointTest`'s, and
> `fa001000-0000-4000-8000-000000000001` is literally that first class's project id. Every class
> passed in isolation and the suite failed with a `project_pkey` duplicate naming none of them,
> which is precisely the failure `StaffingTestBase`'s header describes. Now `fd` and `ff`, verified
> by grepping the tree — which is the check that should have been run before taking them, and is now
> written into both classes' headers.

**Deliverable:** the eight endpoints T1 declared serve real rows; a project attaches, changes and
detaches a client through create and through the settings document; a contact creates no `app_user`
row; and a Company Member holding `company:read` alone is refused every client endpoint — asserted,
not inferred from the matrix.
**Depends on:** T2 (the tables)
**Stack:** `be` · **Agent:** `be-java`, with `be-java-database-code`

**Files:**
- Create: the thirteen `backend/src/main/java/com/tcms/crm/*.java` in the file-structure table above
- Create: `backend/src/main/java/com/tcms/project/ClientDirectory.java`
- Modify: `backend/src/main/java/com/tcms/project/{Project,ProjectView,ProjectWriteRequest,ProjectCreateService,ProjectSettingsView,ProjectSettingsRequest,ProjectSettingsService}.java`
- Create: `backend/src/test/java/com/tcms/crm/{ClientServiceTest,ClientEndpointTest,CrmCreatesNoLoginTest}.java`
- Create: `backend/src/test/java/com/tcms/project/ProjectClientTest.java`

### Read first

`com.tcms.staffing/{StaffingController,StaffingService}.java` for the controller/service/gate shape;
`com.tcms.project/{TrackerBindingDirectory,PeopleDirectory}.java` for the inversion this task
performs; `com.tcms.project.GateResult` for the sealed outcome the controller switches over.

- [ ] **Step 1: Declare the interface in `project`**

```java
package com.tcms.project;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * <strong>What client is this, by name</strong> — asked by the module that stores the id and may
 * not see the module that owns the row.
 *
 * <h2>Why this declaration is here and not in {@code crm}</h2>
 *
 * <p>{@code project.client_id} has been a column since {@code V2}, and {@link ProjectView} must
 * serve the client's <em>name</em> — the projects list renders it per card and the overview draws it
 * as a chip. The row lives in {@code crm}, which is not one of the five foundation modules, so
 * {@code project} may not import it. The declaration therefore lives here, in a module the
 * implementor already depends on, and {@code crm} supplies it: the same arrangement as
 * {@link PeopleDirectory}, {@link TeamDirectory} and {@link TrackerBindingDirectory}, and the remedy
 * this package's comment prescribes.
 *
 * <p><strong>Not a second JPA mapping.</strong> {@link Project} maps {@code client_id} as a plain
 * {@code UUID} rather than as a {@code @ManyToOne} to {@code crm.Client} — an association would be
 * the import this interface exists to avoid, and {@code ddl-auto: validate} would then check two
 * mappings over one table, which is {@code execution.CaseFacts}' recorded reason for refusing that
 * shape.
 *
 * <h2>It resolves and does not authorize</h2>
 *
 * <p>The caller has already gated the project. This answers a factual question about a column in it,
 * under the caller's own transaction and therefore under RLS: a client in another company is not
 * visible here, which is why {@link #namesOf} may return a map smaller than the set it was given.
 */
public interface ClientDirectory {

    /**
     * The name of each client id that names a visible, in-company client.
     *
     * <p>An id with no entry is a client this caller cannot see, and the caller renders no client
     * rather than an id — which is the same thing a project with a {@code null} column renders, and
     * deliberately so: neither is a deficiency and neither is marked as one.
     */
    Map<UUID, String> namesOf(Collection<UUID> clientIds);

    /** Whether this id names a client in the current company — the write-path check behind 018 D-l. */
    boolean existsInCompany(UUID clientId);
}
```

- [ ] **Step 2: Write the `crm` package charter**

`backend/src/main/java/com/tcms/crm/package-info.java`:

```java
/**
 * <strong>Clients</strong> — the company's own customers, their contacts, and every read that names
 * one.
 *
 * <h2>Not a foundation module, and it does not need to be</h2>
 *
 * <p>The five are {@code api}, {@code tenancy}, {@code identity}, {@code project} and
 * {@code storage}. This module <em>imports</em> {@code project} freely — that direction is allowed —
 * and nothing imports this one. Where {@code project} needs a client's name it asks
 * {@link com.tcms.project.ClientDirectory}, which this module implements: the declaration sits in
 * the foundation module and the answer comes from here.
 *
 * <h2>A client is a record, not a principal</h2>
 *
 * <p>No {@code app_user}, no {@code company_membership}, no invitation, no credential and no
 * authentication path — for the client and for every contact under it (scope D1, confirmed
 * 2026-08-30). {@code CrmCreatesNoLoginTest} asserts both halves: that no write here touches an
 * identity table, and that no schema this module serves carries a credential field.
 *
 * <p>If a client portal is ever wanted, <strong>D1 is reopened rather than extended.</strong> It is
 * a second authentication path and a real confidentiality boundary, and bolting one onto a lookup
 * table is how a tenancy model gets quietly broken.
 *
 * <h2>A client carries no scope</h2>
 *
 * <p>The two gates stay exactly as the roles spec §A1 states them — company filter, then project
 * grant. A client is visible iff you hold {@code client:read} at company scope, or you can see a
 * project that references it. There is no third gate, no new {@code RoleAssignment} target and no
 * fourth term in {@code evaluateCompanyWide}.
 *
 * <h2>No money</h2>
 *
 * <p>Rate cards, budgets and invoices are M8's. Nothing here can answer what an hour is worth, and
 * {@code client.html}'s hours-and-invoices table ships as a reserved region rather than as a
 * half-built one (plan 018 D-g).
 */
package com.tcms.crm;
```

- [ ] **Step 3: Write the entities, the enum and the two repositories**

Both repository **interfaces** carry `@Transactional(readOnly = true)`. Without it Spring Data gives
a transaction only to the inherited CRUD methods, so every derived and `@Query` method runs with no
company set and every RLS-protected read returns **zero rows** — fail-closed and silent.

```java
package com.tcms.crm;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>{@code @Transactional(readOnly = true)} on the interface is load-bearing.</strong> Spring
 * Data gives a transaction only to the inherited CRUD methods; a derived method without it runs with
 * no company on the connection, and every RLS-protected read then returns zero rows — fail-closed,
 * silent, and indistinguishable from a company with no clients, which is the one state this module
 * must render precisely.
 */
@Transactional(readOnly = true)
public interface ClientRepository extends JpaRepository<Client, UUID> {

    List<Client> findByStatusOrderByNameAsc(ClientStatus status);

    List<Client> findAllByOrderByNameAsc();

    /** How many projects name this client — the {@code 409} on delete reads it (018 D-k). */
    @Query("SELECT COUNT(p) FROM Project p WHERE p.clientId = :clientId")
    long projectCount(UUID clientId);
}
```

- [ ] **Step 4: Write `ClientService` — the gate, the `409` and the `400`**

```java
    private static final String READ = "client:read";
    private static final String CREATE = "client:create";
    private static final String EDIT = "client:edit";
    private static final String DELETE = "client:delete";

    /**
     * {@code GET /clients} — {@code client:read} at company scope.
     *
     * <p><strong>An empty list is an answer, not an empty state.</strong> A company building its own
     * products never creates a client, and every client-shaped surface is absent while this returns
     * nothing (scope D1 rule 2). Nothing here nags, blocks or degrades because of it.
     */
    @Transactional(readOnly = true)
    public GateResult<List<ClientView>> list(UUID userId, ClientStatus status, UUID managerId) {
        return projects.readInCompany(userId, READ, () -> view(rowsFor(status, managerId)));
    }

    /**
     * {@code DELETE /clients/{clientId}} — refused while a project names it (018 D-k).
     *
     * <p>Nulling those projects' {@code client_id} on the way past would silently unbill live work.
     * The conflict names archiving, which is the same shape of answer a project's own archive gives:
     * hide and freeze, delete nothing.
     */
    @Transactional
    public GateResult<Outcome<Void>> delete(UUID userId, UUID clientId) {
        return projects.writeInCompany(userId, DELETE, () -> {
            /*
              A client this caller cannot see is Unreachable and answers 404, not a fourth Outcome
              member: GateResult already carries that state, and folding "no such row" into the
              write outcome would give the sealed set a member every other service has to handle.
            */
            Client row = clients.findById(clientId).orElse(null);
            if (row == null) return null;   // the gate maps a null value to Unreachable → 404
            if (clients.projectCount(clientId) > 0) {
                return new Outcome.Conflict<>(
                        "The client still has projects",
                        "Detach or archive its projects first, or archive the client instead — "
                                + "deleting it would leave that work with no customer.");
            }
            clients.delete(row);
            return new Outcome.Removed<>();
        });
    }
```

The `400` on a foreign `clientId` lives in `ProjectSettingsService` and `ProjectCreateService`, not
here — step 6.

- [ ] **Step 5: Implement `ClientDirectory` in `crm`**

```java
package com.tcms.crm;

import com.tcms.project.ClientDirectory;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code crm}'s half of the inversion — see {@link ClientDirectory} for why the declaration is in
 * {@code project} and the answer is here.
 *
 * <p>Runs inside the caller's transaction, so {@code app.current_company} is already on the
 * connection and RLS applies: a client in another company is simply not found, and the caller
 * renders no client rather than an id.
 */
@Component
@Transactional(readOnly = true)
class CrmClientDirectory implements ClientDirectory {

    private final ClientRepository clients;

    CrmClientDirectory(ClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public Map<UUID, String> namesOf(Collection<UUID> clientIds) {
        if (clientIds.isEmpty()) return Map.of();
        return clients.findAllById(clientIds).stream()
                .collect(Collectors.toMap(Client::getId, Client::getName, (a, b) -> a));
    }

    @Override
    public boolean existsInCompany(UUID clientId) {
        return clients.findById(clientId).isPresent();
    }
}
```

- [ ] **Step 6: Map `client_id` on `Project` and wire the two write paths**

`Project.java` gains `@Column(name = "client_id") private UUID clientId;` with a getter **and** a
setter — `ArchitectureRulesTest` sweeps `@Entity` fields for readerless and writerless columns in
both directions, which is exactly why `V2` left it unmapped until now.

`ProjectSettingsService` and `ProjectCreateService` share the validation:

```java
    /**
     * The client this write names, or a field error — never a foreign-key violation.
     *
     * <p>{@code fk_project_client} is on {@code id} alone and referential integrity is not subject
     * to RLS, so an id belonging to another company would <em>pass</em> it. Reading the client back
     * through RLS is what turns that into a {@code 400} on the right field, and it is the same
     * layered arrangement every other cross-table rule in this schema uses (018 D-l). A {@code 500}
     * from a constraint would be both the wrong status and an oracle for what exists in another
     * client's tenancy.
     */
    private Optional<Map<String, List<String>>> rejectUnknownClient(UUID clientId) {
        if (clientId == null || clientDirectory.existsInCompany(clientId)) {
            return Optional.empty();
        }
        return Optional.of(Map.of("clientId", List.of("That client does not exist.")));
    }
```

`ProjectSettingsRequest` gains `clientId` as a **required, nullable** field — required in the
contract sense (a body without the key is a `400`), because an omitted nullable id has no safe
reading and "absent means detach" would let the Review screen's save unbill a project (D-d).

- [ ] **Step 7: Write the negative test — assert the refusal, do not infer it**

```java
/**
 * A Company Member holds <strong>exactly {@code company:read} and nothing else</strong> — the one
 * sentence that role is defined by. This asserts they are refused every client endpoint, rather than
 * reading roles spec §A3 and concluding it holds: §A3 is the source the seed is built from, so a
 * test that consults it would agree with a seed that had inverted the grant.
 *
 * <p>The same shape as {@code TimeMoneyBoundaryTest}, which M6 wrote for the money boundary one
 * module over. Fixture UUID prefix {@code fa} is this class's.
 */
@Test
void companyMemberIsRefusedEveryClientEndpoint() {
    UUID member = seedCompanyMemberHoldingOnlyCompanyRead();

    assertThat(get("/api/v1/clients", member).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/clients/" + acmeId, member).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(post("/api/v1/clients", member, aClientBody()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(put("/api/v1/clients/" + acmeId, member, aClientBody()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(delete("/api/v1/clients/" + acmeId, member).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(post("/api/v1/clients/" + acmeId + "/contacts", member, aContactBody())
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
}

/**
 * Adding a contact creates a row of contact information and nothing else — asserted over the
 * identity tables directly, because "no login" is a claim about what is <em>absent</em> and a
 * service test cannot see an insert it does not make.
 */
@Test
void addingAContactCreatesNoIdentity() {
    long usersBefore = countRows("app_user");
    long membershipsBefore = countRows("company_membership");

    addContact(acmeId, "Hanna Lund", "hanna.lund@acme.test");

    assertThat(countRows("app_user")).isEqualTo(usersBefore);
    assertThat(countRows("company_membership")).isEqualTo(membershipsBefore);
    assertThat(countRows("invitation")).isEqualTo(invitationsBefore);
}
```

- [ ] **Step 8: Run the backend gate on shanks**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend './gradlew build'
```

Expected: PASS, including `ArchitectureRulesTest` (nothing in `crm` is imported by a foundation
module; every `@Entity` field has a reader and a writer), `SchemaConventionTest`, and the real
context boot that `ddl-auto: validate` turns into a gate.

> `BUILD FAILED` with `Could not write XML test results` and **zero** assertion failures is an
> fd-limit problem, not a red suite: `ulimit -n 65535; ./gradlew build`. Read
> `docs/specs/shanks-runner-details.md` before the first run.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/tcms/crm backend/src/test/java/com/tcms/crm \
        backend/src/main/java/com/tcms/project backend/src/test/java/com/tcms/project/ProjectClientTest.java
git commit -m "feat(crm): the client module, the ClientDirectory inversion, and the project's client"
```

---

## T4 — The clients fault arm, the query module, and the three client screens

> **Landed 2026-09-05.** `features/clients/` (five components), `api/queries/clients.ts` with nine
> hooks, the client keys, the `clients` fault arm, two label maps, both routes and the rail entry.
> Gates on shanks: **build ✓, lint 0 warnings/0 errors, 2828 tests passed**; packages
> 170 + 138 + 234 green (the contract moved). The 2 `CaseGrid.test.tsx` failures are the
> pre-existing pair verified against clean `master` in T1.
>
> **Four corrections:**
>
> 1. **`ClientInput.status` lost its `default: ACTIVE` keyword.** `openapi-typescript` emits a
>    property carrying one as **required** — `ProjectSettingsUpdate`'s six booleans are the standing
>    proof, which is why `settingsUpdate.ts` sends every one. Here it made a create that omits
>    `status` a compile error in both clients and would have forced a cast saying the opposite of
>    what the server does. The default is a server behaviour stated in prose now, not a schema
>    keyword that changes a type.
> 2. **No tile row on the client page.** The mockup draws three and two of them are M8's, leaving
>    Projects alone — and a row of one tile reads as two that failed to load. The count moved into
>    the panel title, where it is a fact about the list under it. (`kit.Tile` is also a `<button>`
>    requiring `onClick`, and a tile with a no-op handler is the action-that-does-nothing `ui-rules`
>    §4 forbids.)
> 3. **The Account manager filter and picker are absent**, and this is a missing read rather than a
>    decision against them. The endpoint takes `accountManagerId` and the create schema takes it,
>    but populating either needs the company member list, which is `staffing:directory`'s — a key a
>    Company Admin holding `client:create` need not hold. An empty picker is worse than none.
> 4. **`ClientCreateDialog` navigates to the new client rather than back to the list**, because the
>    next thing a person does after recording one is add a contact or attach a project, and both
>    live there.
>
> **The plan predicted the fault arm would need `only('/clients')` narrowing on top of the four
> generic faults; it does not.** The arm is scoped by an anchored path test like every arm since the
> tracker's, and `only()` still narrows it further for free.

**Deliverable:** `/clients`, `/clients/:clientId` and the create dialog render on the render kit;
with zero clients the list renders `clients-empty.html`'s state and offers the first client; and
`tcmsFaults.clients('none')` drives that state on an instance that has clients.
**Depends on:** T3 (real endpoints — there is no mock API lane in this repo)
**Stack:** `fe` · **Agent:** `fe-react`

**Files:**
- Modify: `frontend/src/lib/devFaults.ts`
- Create: `frontend/src/api/queries/clients.ts`
- Modify: `frontend/src/api/queries/keys.ts`, `frontend/src/api/queries/index.ts`, `frontend/src/lib/labels.ts`
- Create: `frontend/src/features/clients/{ClientsPage,ClientPage,ClientCreateDialog,ClientContactsPanel,BillingSlot}.tsx` and `{ClientsPage,ClientPage}.test.tsx`
- Modify: `frontend/src/app/router.tsx`, `frontend/src/app/AppShell.tsx`

- [ ] **Step 1: Build the fault switch first (D-n)**

```ts
/**
 * The clients arm — **the module's acceptance criterion is an absence, and this is what makes it
 * walkable.**
 *
 * Every other arm in this file fabricates a *failure*. This one fabricates a *population*, because
 * what Mc has to get right is not an error path: it is that six surfaces render nothing at all when
 * the company has no clients (scope D1 rule 3). On a `seed-demo-data` instance that state cannot be
 * reached without deleting every client, and on a fresh instance the populated state cannot be
 * reached without creating them — so neither half of the criterion could be looked at before this.
 *
 * `'none'` answers `[]` to the client list and to the project picker; `'some'` answers a fabricated
 * pair. Both are `200`s carrying a state rather than failures, so they are `body` verdicts, which is
 * the shape the tracker arm already established for a degraded answer.
 *
 * The four generic faults still apply here through `only('/clients')` — this arm is about the two
 * states that are not faults at all.
 */
export type ClientsFault = 'none' | 'some';

function isClientListRead(path: string, method: string): boolean {
  const bare = withoutQuery(path);
  return method === 'GET'
    && (bare === '/clients' || /^\/projects\/[^/]+\/settings\/client-options$/.test(bare));
}
```

Anchor both arms. `/^\/clients$/` and not `startsWith('/clients')`, for `isRankWrite`'s recorded
reason: a prefix would also catch `/clients/{clientId}`, and serving `[]` there would answer a
`ClientDetail` read with an array.

Both guards stay a literal `import.meta.env.DEV` so Rollup drops the module from a production build.

- [ ] **Step 2: Add the keys and the query module**

`keys.ts` gains one nested object — **never a second factory**:

```ts
  clientsRoot: ['clients'] as const,
  clients: {
    list: (status: string, managerId: string) => ['clients', 'list', status, managerId] as const,
    one: (clientId: string) => ['clients', clientId] as const,
    /*
      Keyed under the PROJECT, not under `clientsRoot`, and the choice is the invalidation: a client
      created from the project settings picker must refresh that picker, and a project's settings
      subtree is already swept by every project-scoped write. Under `clientsRoot` it would be
      refreshed by client writes and by nothing the project does.
    */
    optionsFor: (projectId: string) => ['projects', projectId, 'client-options'] as const,
  },
```

`clients.ts` exports `useClients`, `useClient`, `useProjectClientOptions`, `useCreateClient`,
`useUpdateClient`, `useDeleteClient`, `useAddClientContact`, `useUpdateClientContact`,
`useDeleteClientContact` — each with a `TIMEOUTS` budget and a `signal`, every write sweeping
`keys.clientsRoot` **and** `['projects']` (a client's name is on every project card, and a client
archived or renamed changes both lists).

`useProjectClientOptions` is gated: `enabled: can('project:settings', projectId)`, so a Viewer's
settings read never fires a `403` the notification layer would surface (D-f's argument, one screen
over).

- [ ] **Step 3: The three screens, on the kit only**

`ClientsPage.tsx` — `clients.html`. `DataRegion` for the loading/loaded/failed/empty/stale matrix;
the **empty** arm is `clients-empty.html` and carries its copy verbatim, because that page is the
only trace of this module a client-less company ever sees and its wording is the explanation:

> *A client is an organisation your company delivers software for. Recording one lets a project say
> who it is for, so hours, budgets and — later — invoices roll up to the right customer. A client
> never signs in and never sees anything here.*
>
> *If you build your own products, you may never need one. Nothing in the product will ask you
> again.*

The **Hours to date** and **Last invoice** columns the mockup draws are not rendered (D-g).

`ClientPage.tsx` — `client.html`: the header, the three tiles reduced to **Projects** (the other two
are hours and invoices), the projects table at the four fields `ClientProject` carries,
`ClientContactsPanel`, and `BillingSlot`. **No Rate card panel and no Contracts panel** (D-g, D-h).

```tsx
/**
 * The region M8 fills — `client.html`'s "Hours and invoices to date" table and its two tiles.
 *
 * It draws no data and reads no endpoint, which is what keeps it inside plan 009's rule that no
 * module ports a screen it has no endpoint for. What it is instead is the seam: M8 lands its table
 * here, and this component's existence is what let plan 018's close-out hand M8 a defined slot
 * rather than an unresolved layout decision three modules later.
 *
 * **Why not half the table now.** M6 landed, so approved hours per project are reachable — the
 * columns are `Period · Project · Approved hours · Invoice · Status` and M8 owns three of the five.
 * Building two of them now and rebuilding the table in M8 costs more than M8 building it once, and
 * leaves a half-table that reads as a defect rather than as a boundary (plan 018 D-g). Story C3
 * words the deferral itself: "and — once time and billing exist — hours and invoices to date."
 */
export function BillingSlot() {
```

`ClientCreateDialog.tsx` — `client-new.html`. **Name is the only required field**, and the form's
own copy says so; the three "Optional, and separate" blocks are rendered as the mockup's explanatory
footer, not as inputs, because contacts are added on the client's page and rate cards do not exist.

- [ ] **Step 4: Route and rail**

Routes go in `app/router.tsx` **outside `ProjectGuard`** — these are COMPANY scope — following the
`/admin/teams` and `/staffing` precedent. The rail entry in `AppShell.tsx` is gated on
`client:read`, which is the exception to that rail's ungated default and needs the comment D-j
carries.

- [ ] **Step 5: Tests**

`createTestQueryClient` only — never build a `QueryClient` in a test, it is the one with the caches.
Declare intent with `drivingAFailedRead()` / `renderingIntoAnErrorBoundary()` where a test trips
those paths.

```tsx
it('offers the first client rather than an empty table when the company has none', async () => {
  serve({ '/clients': [] });
  renderWithProviders(<ClientsPage />);

  expect(await screen.findByText(/No clients yet/i)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /Add client/i })).toBeEnabled();
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
});
```

- [ ] **Step 6: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

`npm run build` is the gate and not `tsc --noEmit` — the latter has reported 0 errors while the build
failed with nine, because `tsc -b` uses the stricter config. Lint is a gate at **0 warnings**.

```bash
git add frontend/src/lib/devFaults.ts frontend/src/api/queries frontend/src/lib/labels.ts \
        frontend/src/features/clients frontend/src/app/router.tsx frontend/src/app/AppShell.tsx
git commit -m "feat(crm): the clients fault arm, the query module, and the three client screens"
```

---

## T5 — The client on the project surfaces, its proven absence, and the close-out

**Deliverable:** the Client filter and the per-project client on `/projects`, the Client field on
project creation and on General settings, and the client chip on the project overview — each of them
asserted **absent** when the company has no clients; and `docs/features.md`, plan 009's status table
and the M8 prompt closed per the hand-off protocol.
**Depends on:** T4 (the query module every one of these reads)
**Stack:** `fe` · **Agent:** `fe-react`

**Files:**
- Modify: `frontend/src/features/projects/{ProjectsPage,ProjectCreateDialog,ProjectOverviewPage}.tsx` + `{ProjectsPage,ProjectOverviewPage}.test.tsx`
- Modify: `frontend/src/features/settings/{GeneralSettingsPage.tsx,GeneralSettingsPage.test.tsx,settingsUpdate.ts}`
- Modify: `docs/features.md`, `docs/plans/009-project-management-expansion-modules.md`, `docs/plans/018-clients.md`

- [ ] **Step 1: `settingsUpdate.ts` — the field list, first**

Do this before the screen. It is the one place the field list is written, and a field added to the
contract and not added here is the failure that function exists to make impossible to have in only
one of the two screens that `PUT` the document.

```ts
  return {
    reviewEnabledCase: next.reviewEnabledCase,
    // … the eight existing fields, unchanged …
    trackTime: next.trackTime,
    /*
      `clientId` and not `client`: the read shape carries a `ClientRef` and the write carries the id.

      It is REQUIRED in the contract and nullable, which is the one field on this document where
      omission has no safe reading — see 018 D-d. `trackTime` above is the field the server reads as
      `true` when absent; this one the server refuses when absent, because "absent means detach"
      would let the Review screen's save quietly unbill a project, and there is no default that is
      right in both directions.
    */
    clientId: next.client?.id ?? null,
  };
```

- [ ] **Step 2: The Client field on General settings**

A new `ClientPanel` beside `TimePanel`, and the page's header table loses its `| Client select | Mc |`
row — struck through and pointed at this plan, the way that table already records M5's and M6's
arrivals rather than deleting them.

**Rendered only when `useProjectClientOptions` returns a non-empty list.** Not disabled, not showing
"None" alone: an empty "Client: —" is exactly the nagging `solo-overview.html` rules out, and
`GeneralSettingsPage.tsx`'s own table already says so — *"the control could only ever say 'None'."*

- [ ] **Step 3: Add the mockup's Budget row, still absent, now pointing here**

`settings-general.html`'s Time panel draws `Budget · 600h · Edit in Billing`. It is M8's — there is
no hours ceiling, no rate and no Billing screen — and 016 T5 correctly declined to render a number it
could not source. Leave it absent and leave that comment where it is; this task only re-points its
cross-reference now that Mc has landed and M8 is next.

- [ ] **Step 4: The projects list — client per card, and a Client filter in the URL**

`ProjectsPage` is a card picker and stays one (D-i). Each card gains the client's name where the
project has one; the filter is `?client=<id>` via `useSearchParams`, which is this codebase's rule
for filter state — somebody who filtered to Acme sends the link on, and component state sends a list
that is not filtered.

Both the filter control and the per-card client render **only when the company has at least one
client**.

- [ ] **Step 5: The overview chip**

`ProjectOverviewPage` draws `Client: Acme Retail` beside `Lead:` when `project.client` is present,
and nothing when it is not. This is the `solo-overview.html` versus `project-overview.html`
criterion, and it is one conditional over the field `Project` now carries.

- [ ] **Step 6: Assert the absence — the module's real acceptance criterion**

Explicit assertions, never an untested implication of a conditional.

```tsx
describe('a company with no clients', () => {
  /*
    Six absences, asserted rather than implied. This is the module's acceptance criterion and not a
    nicety: the owner's own worked example is a company building its own products, so this is the
    common case rather than an edge — and every one of these is a place a conditional could be
    written backwards and still typecheck, lint clean and render.
  */
  beforeEach(() => serve({ '/clients': [], '/projects/p1/settings/client-options': [] }));

  it('shows no Client filter and no client on any project card', async () => {
    renderWithProviders(<ProjectsPage />);
    await screen.findByText('Acme Storefront');
    expect(screen.queryByLabelText(/Client/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/None \(internal\)/i)).not.toBeInTheDocument();
  });

  it('shows no Client field on project creation', async () => {
    renderWithProviders(<ProjectCreateDialog open onClose={vi.fn()} />);
    await screen.findByLabelText(/Project name/i);
    expect(screen.queryByLabelText(/Client/i)).not.toBeInTheDocument();
  });

  it('shows no Client panel in General settings', async () => {
    renderWithProviders(<GeneralSettingsPage />, { route: '/projects/p1/settings/general' });
    await screen.findByTestId('GeneralSettings.Identity.Panel');
    expect(screen.queryByTestId('GeneralSettings.Client.Panel')).not.toBeInTheDocument();
  });

  it('shows no client chip on the project overview', async () => {
    renderWithProviders(<ProjectOverviewPage />, { route: '/projects/p1' });
    await screen.findByText('Acme Storefront');
    expect(screen.queryByText(/^Client:/)).not.toBeInTheDocument();
  });

  it('marks no project as missing a client', async () => {
    renderWithProviders(<ProjectsPage />);
    await screen.findByText('Acme Storefront');
    expect(screen.queryByText(/attach a client/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/no client/i)).not.toBeInTheDocument();
  });
});
```

And the mirror, so the conditional is proven in both directions rather than proven off:

```tsx
it('shows the Client filter and the per-card client once one client exists', async () => {
  serve({ '/clients': [aClient()] });
  renderWithProviders(<ProjectsPage />);
  expect(await screen.findByLabelText(/Client/i)).toBeInTheDocument();
  expect(screen.getByText('Acme Retail')).toBeInTheDocument();
});
```

- [ ] **Step 7: Run all three gates on shanks**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend './gradlew build'
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run test:run --workspaces
```

All three, not just the frontend: `citedFiles.test.ts` fails the **packages** job when a path named
in a comment anywhere under `frontend/src/**` or `backend/src/**` is not on disk, and this task adds
several such citations.

- [ ] **Step 8: Close the docs**

1. `docs/features.md` — rewrite the section Mc replaces, **struck through and superseded, not
   deleted**. The paragraph at line 68 (*"A project belonging to a company with no clients is the
   same screen with no client chip…"*) becomes a statement of what the product now does rather than a
   promise, and a Clients section joins it.
2. Plan 009's status table — `Mc` to `done 2026-09-05 @ <short sha>`, **`V11`** in the migration
   column with the reservations struck through, and the same for the plan-number projection.
3. The **built-and-unverified list** — honestly, distinguishing what the gates proved from what was
   never exercised. The gates prove it compiles, not that it works.
4. **Rewrite the M8 prompt in place** with the client endpoints as they landed and the
   `BillingSlot` seam as it landed — including that `ClientDetail` carries no hours, that the hours
   table is M8's whole table rather than three columns of one, and that `V11` is spent so M8 takes
   whatever `ls` says on its day.

- [ ] **Step 9: Commit the code, then the docs, separately**

```bash
git add frontend/src/features/projects frontend/src/features/settings
git commit -m "feat(crm): the client on the project surfaces, and its proven absence"

git add docs/features.md docs/plans/009-project-management-expansion-modules.md docs/plans/018-clients.md
git commit -m "docs(plan): Mc done — V11, the built-and-unverified list, and M8's rewritten prompt"
```

---

## Close-out — filled in by T5

**Status:** not started.
**Migration actually taken:** `V11` *(confirm against the directory in T2 and correct here if it moved)*.
**Built and unverified:** *(T5 writes this — the gates prove it compiles, not that it works)*.

