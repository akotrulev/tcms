# 009 — The project-management expansion, as vertical modules

**Status (filed 2026-10-05):** Delivered — every module is `done` in the status table below: M0 `24f997bb` (plan 010) through M9 `ed299a79` (plan 020), with Mc `c7e504ff` (plan 018). Each module's own plan, filed beside this one, carries its built-but-unverified list. Any `Status`, task table or checkbox below this line is the plan's history, not its state.

**Date:** 2026-08-30 · **Written by:** `ep-writing-plans`

> **For agentic workers:** this document is a **roadmap of modules, not a task list.** Do not execute
> it. Each module gets its own bite-sized plan — `docs/plans/010-…` onward, one per module, written
> with `ep-writing-plans` when that module starts and executed with `ep-executing-plans`. The skill's
> own Scope Check requires this: *"If the spec covers multiple independent subsystems … suggest
> breaking this into separate plans — one per subsystem. Each plan should produce working, testable
> software on its own."* Eleven subsystems, eleven plans. **Start a module from its prompt** in
> [Prompts — one per module](#prompts--one-per-module), and finish it with the
> [hand-off protocol](#prompt-hand-off-protocol) — the status table there is the only record of
> what is done. **The UI source of truth is `docs/mockups/`.**
>
> **The executable sequence lives in [009-execution-breakdown.md](009-execution-breakdown.md)** —
> which module runs when, cut into agent-bound tasks with acceptance criteria and file lists. `Mc`'s
> task cut is in [009-mc-clients-execution-breakdown.md](009-mc-clients-execution-breakdown.md).
> Those two files own **order and task shape**; this one owns **content and the status table**. Start
> a module from its prompt here; take its task list from there.

**Goal:** Deliver the expansion in `docs/specs/2026-08-30-project-management-expansion-scope.md` as
modules that are **complete on arrival** — each one lands its own migration, its own endpoints and
its own screens, and is usable by a person the day it merges.

**Architecture:** Unchanged from the scope document. One deployable, Spring Modulith packages under
`com.tcms`, five foundation modules and no sixth, downstream modules referencing an issue
polymorphically and keeping their own event-fed projection (§3). What changes here is only **the
order the work is cut in.**

**Tech stack:** Postgres 17 + Flyway (one migration per module), Spring Boot 3.5.6 / Java 21,
`openapi.yaml` + two generated clients, Vite 8 / React 19 / TanStack Query / Tailwind v4 + the kit.

---

## What this changes about §9, and why

§9 of the scope document cuts the work **horizontally**: `P0` lands schema and roles for the whole
expansion with *"nothing user-visible"*, and the client CRUD screens wait several phases behind the
column they read. That ordering is defensible — it was written to make the expensive-to-retrofit
columns cheap — but it has one property the owner has now ruled out: **for the length of P0 there is
nothing to look at, and for the length of P1 there is a tracker with no board.**

This plan cuts **vertically** instead. Each module below is db + backend + contract + frontend for
one feature, and the phrase that ends every module is *"a person can now …"*.

~~§9's phase table~~ → **superseded by this document.** §9 has been amended to point here; its
priority ordering (must / should / nice-to-have) and its migration-version discipline survive
intact, because neither had anything to do with the horizontal cut.

**What did not survive:** `P0`'s claim that the *client tables* must land early. `project.client_id`
is one nullable column on one existing table — adding it later costs a two-line migration, not a
retrofit across 28 tables. The genuinely expensive-to-change things are the per-project key counter and the
permission catalogue, and those are what M0 keeps. ~~`key_sequence`'s primary key~~ → review R1:
the counter is a new table, and `key_sequence` is not touched.

**One module is still deliberately UI-less.** M0 is a permission catalogue, one new counter table,
one CHECK and one nullable column. There is no way to make that a feature, and spreading the role seed across eight modules
would mean eight edits to three documents that a test pins byte-for-byte. It is one week, once.

---

## The modules — in the order they are done

| # | Module | Migration | A person can now … | Depends on | Status |
|---|---|---|---|---|---|
| **M0** | Foundation | `V2` | *(nothing — see above)* | none | done 2026-08-30 @ `24f997bb` |
| **M1** | Tracker core | `V3` | run a project's work — epics, stories, tasks, statuses, comments, spec links — and see it all on My Work | M0 | done 2026-08-31 @ `8bf37408` |
| **M2** | Board & backlog | ~~`V4`~~ → `V5` | work the board, rank the backlog, filter once across every view | M1 | done 2026-09-01 @ `95c9960b` |
| **M3** | Releases, roadmap & readiness | ~~`V5`~~ → `V6` | plan versions, see the roadmap, and answer "can we ship 2.4?" | M2 | done 2026-09-01 @ `55cb4bc0` |
| **M4** | Jira offramp | ~~`V6`~~ → ~~`V7`~~ → ~~`V8`~~ → `V7` | move a Jira project in, keys intact, with a preview and a report | M1 | done 2026-09-03 @ `ba0031bb` — plan 014 |
| **M5** | Agent execution | ~~`V7`~~ → ~~`V6`~~ → ~~`V7`~~ → `V8` | connect their own assistant and have it work a task, but never close it | M1 | done 2026-09-04 @ `4f3bbf8b` — plan 015 |
| **M6** | Time | ~~`V8`~~ → ~~`V10`~~ → ~~`V11`~~ → `V9` | log hours, submit a period, and have a PM approve it | M1 | done 2026-09-04 @ `3471358c` — plan 016 |
| **M7** | Staffing | ~~`V9` (unchanged)~~ → **`V10` — taken 2026-09-04** | allocate people, see capacity, and find who is free | ~~M6~~ → **M0** (review R11) | done 2026-09-04 @ `44f8d39a` — plan [017-staffing.md](017-staffing.md) |
| **M8** | Billing | ~~`V10`~~ → ~~`V11`~~ → ~~whatever `ls` says on the day~~ → **`V12` — taken 2026-09-05** | price approved time and issue an invoice | M6 · Mc — **both `done`** | done 2026-09-06 @ `c58384b5` — plan [019-billing.md](019-billing.md) |
| **M9** | Delivery analytics | **none — and none was taken** | read burn-up, cycle time and flow | M3 | done 2026-09-06 @ `ed299a79` — plan [020-delivery-analytics.md](020-delivery-analytics.md) |
| **Mc** | Clients | ~~`V8`~~ → ~~`V9`~~ → ~~whatever `ls` says on the day~~ → **`V11` — taken 2026-09-05** | record a client and attach projects to it | M0 | done 2026-09-05 @ `c7e504ff` — plan [018-clients.md](018-clients.md) |

**The `V` column was re-derived on 2026-08-30** from the confirmed landing order in
[009-execution-breakdown.md](009-execution-breakdown.md) — `M1 → M2 → M3 → M5 → M4 → Mc → M7 → M6 → M8 → M9`.
The strikethroughs are what this table reserved when it was written in module-number order, before
M5 was placed ahead of M4 and `Mc` sixth. **These are still reservations** — see the section below.

**Status** is one of `not started` · `in progress — plan 0NN` · `done YYYY-MM-DD @ <sha>` ·
`deferred`. The agent that finishes a module writes it, per the
[hand-off protocol](#prompt-hand-off-protocol) below; nobody else does. The status table is the
gate: a prompt's *Depends on* modules must read `done` here before the prompt is run.

**The "Depends on" column is the dependency graph, not the schedule.** Which modules may actually
run beside each other — by *file*, which is stricter than by dependency — is in
[What can run in parallel](#what-can-run-in-parallel) below.

**M9 needs no migration.** Every report reads `issue_fact` and `execution_fact`, both of which exist
by M3. If that turns out to be wrong, it takes the next free version at the time — it does not
reserve one now on the chance.

**Mc is deferrable and sits anywhere after M0** — build it the week a first real client exists.
Everything client-shaped in M8 depends on it, and nothing else does.

### The migration versions are a reservation, not a fact

`out-of-order` is unset in `application.yml`, so **a lower-numbered migration arriving after a higher
one is silently not applied**, and Testcontainers builds each test database from scratch so it will
never show you the conflict. The column above is valid *only while the order above holds*. Reorder
two modules, defer one, or slip `Mc` in between, and the module that starts second takes **the next
free number at the time it starts** — not the number this table reserved for it. Re-read
`backend/src/main/resources/db/migration/` before writing the file, every time.

**And it moved a second time, on 2026-09-01, for a reason the paragraph above does not cover.** M2
took `V5`, not the `V4` this table reserved for it, because **M1 consumed two versions rather than
one** — `V3__tracker_core.sql` and `V4__retire_work_item.sql` — so every number below M2 shifted by
one as well. Nothing was reordered and nothing was deferred: the column was simply arithmetic over
an assumption that each module spends exactly one migration, and a module may spend two. That is the
second correction to this column in a month, from two unrelated causes, and it is why the rule below
is not a caution but an instruction. **`backend/src/main/resources/db/migration/` is the authority.**
`ls` it before writing the file, every time; do not derive the number from this table.

**And a third time, on 2026-09-01, from the cause the paragraph above did not predict either — a
module simply not having started.** M4 was begun before M5, against the confirmed order, so M4 takes
**`V7`** and M5 takes **`V8`**: `ls` on that day showed `V1 … V6`, `V6__release.sql` being M3's, and
`V7` was therefore free. The table had reserved `V8` for M4 on the assumption that M5's `V7` was
already spent. It was not. Three corrections in a month, from three unrelated causes — a reorder, a
module spending two versions, and a module not having run — and every one of them would have been a
silently unapplied migration on an already-migrated database, because `out-of-order` is unset and
Testcontainers builds each test database from scratch. **Read the directory. Every time.**

**And a fourth time, on 2026-09-04, from the same cause as the third read forward instead of
back — two modules ahead of M6 in the confirmed order simply not having run.** M6 takes **`V9`**,
not the `V11` this table reserved for it: the order says `… Mc → M7 → M6 …`, and **neither Mc nor M7
has started**, so the two versions they would have spent were free. `ls` on the day showed
`V1 … V8`, `V8__agent_execution.sql` being M5's, and `V9` was therefore next. Four corrections in
five weeks, from four unrelated causes — a reorder, a module spending two versions, a module not
having run *before* the one that needed the number, and two modules not having run. **The number in
this column has been wrong more often than it has been right. Read the directory.**

**The plan numbers shifted with it.** `014` is M4's (`014-jira-offramp.md`), not M5's; the projection
below was made in the confirmed order and a number is taken next-free at start time, never
renumbered. So the sequence now runs `011` M1, `012` M2, `013` M3, **`014` M4**, `015` M5, then
`016` Mc, `017` M7, `018` M6, `019` M8, `020` M9 — still projected for everything after `014`.

**The order was confirmed on 2026-08-30** — `M1 → M2 → M3 → M5 → M4 → Mc → M7 → M6 → M8 → M9`, one
lane, in [009-execution-breakdown.md](009-execution-breakdown.md) — and the `V` column above was
re-derived from it. That does not promote the column from reservation to fact. Defer a module, stop
after one, or hoist `Mc` (which plan-wise may sit anywhere after M0), and every number below the
change is wrong. The directory is the authority. The same rule governs plan numbers: `010` is M0's
(`010-foundation.md`), so the sequence runs `011` M1, `012` M2, `013` M3, `014` M5, `015` M4,
`016` Mc, `017` M7, `018` M6, `019` M8, `020` M9 — **projected**, taken next-free at start time,
never renumbered.

---

## What every module carries — the tax, per module rather than per phase

§8 of the scope document itemises the cross-cutting cost of the whole expansion. Cut vertically, each
module pays its own share, and **each of these is part of the module's definition of done**, not a
cleanup pass afterwards:

| Surface | Per-module obligation |
|---|---|
| `docs/specs/openapi.yaml` | The module's endpoints and schemas. **Every schema gets its `required` array, including on inline nested objects** — `required` does not descend. |
| Generated clients | **Both** — `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts` — regenerated in the same commit as the contract change. |
| `contractBodies.ts` | The marked region is byte-identical across both copies, pinned by `contractPairing.test.ts`. Edit one, edit both. |
| Permissions | The module's slice of the catalogue, plus the roles spec, `permission-matrix-summary.md` and `README.md` — all three in one commit, or `matrixCopies.test.ts` reddens. |
| `tierClaims.test.ts` | Any comment claiming somebody lacks a permission §A3 grants them fails the **packages** job. Written for the six-role model; three roles are new. |
| Frontend | Screens on the render kit only. No `sx`, no CSS-in-JS, no second stylesheet. New enums into `lib/labels.ts` as `satisfies Record<Union, string>`. |
| Frontend data | One `keys` factory, `src/api/queries/keys.ts`. **Never add a second.** Timeout budget per endpoint class. |
| Fault switch | *Build the switch before the feature it proves* — a module with new failure modes extends `src/lib/devFaults.ts` in the same module. |
| CLI + MCP | The module's verbs, and `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` updated with them. |
| Gates | `./gradlew build`, `npm run build && npm run lint && npm run test:run`, `npm run build` in `packages/` — **run on `shanks` via `~/bin/tcms-remote`**, per the standing directive. |
| `docs/features.md` | The section the module replaces, rewritten — struck through and superseded, not deleted. |
| Task cut | **One stack, one agent, one session, 150k-token budget** — see [How a module's plan is cut into tasks](#how-a-modules-plan-is-cut-into-tasks--one-stack-one-agent-one-session). A task mixing `be` and `fe` is two tasks. |

**The frontend reference is `docs/mockups/`.** Each module below names the mockup files it ports.
Four are named by no module on purpose: `test-suites.html`, `test-plans.html` and `test-runs.html`
render screens this expansion does not change — only `test-cases.html` gains a column, in M3 — and
`solo-overview.html` is not a screen at all but the **client-less rendering of
`project-overview.html`**, which is an acceptance criterion of M1, M3 and `Mc` rather than a build
target of any of them.
They are static renders on the app's own tokens, so porting is a translation into the kit and
TanStack Query, not a redesign. **No module ports a screen it has no endpoint for** — the
non-functional-frontend option was considered and rejected here: this repo has no mock API lane, and
fixtures inside components would have to be ripped back out of every screen.

---

## M0 — Foundation

**Deliverable:** `V2` applied; a per-project issue counter exists for every project and is seeded
for every new one; `doc_link` accepts an `'ISSUE'` source; `project.client_id` exists and is
nullable; the three new roles and the full ~91-permission catalogue are seeded and documented.
`./gradlew build` green. **No screen changes.**
**Depends on:** none

**db** — `V2__pm_foundation.sql`
- ~~`key_sequence` primary key `(company_id, entity_type)` → `(company_id, entity_type, project_id)`,
  `project_id` nullable for the five existing company-scoped types~~ → **superseded (review R1).**
  A primary key cannot contain a nullable column, so that statement never ran. Instead:
  **`key_sequence` is not touched at all.** A new table owned by the `project` module,
  `project_key_sequence (company_id, project_id, next_value)` with PK `(company_id, project_id)`,
  carries the per-project issue counter; the prefix is `project.key`, which already exists with
  `uq_project_key` and `ck_project_key_format`, so no prefix column. The same one-statement
  `UPDATE … RETURNING` allocator, one row per project instead of per company. `V2` backfills a row
  for every existing project. There is now no PK change on a live table, and the five existing
  company-scoped types keep the allocator, the seed function and the seven readers they have today
  (`tenancy/DisplayKeys.java` — whose `EntityType` enum stays at five members —
  `CompanyProvisioningService`, `ExportCompanyService`, `SeedDemoDataCommand`, `ProjectEdit`,
  `RunEdit`, `PlanCreateService`).
- `doc_link.source_type` CHECK gains `'ISSUE'`.
- `project.client_id UUID NULL` — **the column only, no `REFERENCES`.** The `client` table belongs
  to `Mc`, so `Mc` adds the FK constraint when the table exists; the column ships nullable and
  unread. (Review R3: an earlier draft wrote `REFERENCES client(id)` in the same sentence as "the
  FK is added by Mc".)
- Permission rows and the three role grants: Project Manager (project scope), Account Manager and
  Finance (company scope).

**be** — `identity` seeds the roles and permissions. `project` gains the `project_key_sequence`
row on create (in `ProjectCreateService`) and the allocator `tracker` will call — `project` is a
foundation module, so that import is legal. `ExportCompanyService` exports the new table beside
`key_sequence`. No new module.

**contract** — no endpoints. `Role.name` is an `enum` in `openapi.yaml` (the "seven names" note
on the `Role` schema), so it gains three members and both generated clients move.

**docs, all in one commit** — `2026-07-30-tcms-roles-permissions-naming.md` §A3,
`permission-matrix-summary.md`, `README.md`, and the `2026-07-30-tcms-erd.md` §366 key-design
amendment in the superseded style.

**Why "no screen changes" is a claim and not a hope — verified before this plan was written.** The
admin surface is already data-driven: `frontend/src/features/admin/RoleAssignmentTable.tsx` and
`GrantRoleDialog.tsx` render whatever `GET /role-assignments` returns, and
`features/admin/roleAccess.ts` branches on `role.scope` rather than on any role's name. So seeding
Project Manager at `PROJECT` scope and Account Manager and Finance at `COMPANY` scope makes all
three **grantable through the existing screens with no frontend work** — which is what
`teams.html` and `member.html` in the mockups show. Confirm this still holds before starting; if a
role name has since been hardcoded anywhere, that hardcoding is M0's first task.

**Done when:** `SchemaConventionTest`, `matrixCopies.test.ts` and `tierClaims.test.ts` are green, a
company still provisions, a new project gets its counter row, the three roles are grantable from
the existing member screen, and `git diff --stat frontend/src` names **only**
`frontend/src/api/schema.d.ts` — ~~is empty~~ (review R2: the contract's role enum moves the
generated file, so "empty" was never achievable; any hand-written file in the diff is the
hardcoding the paragraph above warns about).

**Not in this module:** the `client` table, any issue table, any new screen.

**The migration actually taken is `V2`**, as the table reserved: the directory held
`V1__initial_schema.sql` and nothing else when plan 010 T2 checked it. No correction needed here or
in the `**db**` line above.

**Built and unverified:** the 33 new permission rows exist and are granted, and ~~**no code reads
any of them**~~ — the first evaluation site for `issue:*` arrives with M1, for `board:*` with M2,
and for `billing:*` with M8. → **all three arrived**, the last of them on 2026-09-06; every one of
the 33 is now evaluated by a running endpoint, and the seven money keys are asserted as a *lack* by
two tests rather than read off the matrix (019 D-n). A grant that is never evaluated is a grant nobody can tell is inverted, which
is the same standing this repository already gives `doc:read` and `artifact:read` (§A3, flow audit
C8). `ProjectKeys` is tested against a real Postgres but has **no production caller** until M1's
issue creation — it carries an expiring entry in `ArchitectureRulesTest.UNEXERCISED_BY_DECISION`
that **M1 deletes**. `doc_link.source_type = 'ISSUE'` is accepted by the CHECK and written by
nothing; `project.client_id` exists, is nullable, is unmapped and is read by nothing until Mc.

**What was actually clicked through, and what was not.** `V2` applied to the lab's **live,
already-populated** database (v1 → v2) and the app started behind `hibernate.ddl-auto: validate` —
which is the one property Testcontainers cannot demonstrate, because it always builds from empty,
and it is the property `V2` carries the most risk on. On the lab, **Project Manager** is offered in
the project-scope grant picker and **Account Manager**, **Finance** and **Project Manager** in the
company-scope one, each with the id `V2` seeds, and Help → Roles and access renders all ten role
columns and all seven new areas live from the instance. **No grant was actually submitted** — the
check stopped at the picker. That click-through is also what found three false sentences on the
Help page that every gate passed (`24f997bb`); `tierClaims.test.ts` cannot catch them, because it
sweeps claims that somebody *lacks* a permission and one of the three was a positive list that had
gone short.

**A pre-existing red, unrelated to M0 and not repaired by it.** `com.tcms.reporting.FlakeScoreTest`
fails six tests on `shanks` as of 2026-08-30 — proven pre-existing by running it against a pristine
export of the tree without `V2`. It pins `BASE = 2026-06-01T08:00:00Z` against the shipped **90-day**
flake window, so the fixture expired on 2026-08-30; the 06:37 UTC nightly was the last green one.
The repair is that test's, not this module's: make `BASE` relative to the clock, or freeze the clock.
`./gradlew build` on `shanks` is therefore **3586 of 3592 green**, with those six the only failures.

---

## M1 — Tracker core

**Deliverable:** A person opens a project, creates an epic, adds stories under it, breaks one into
tasks, moves them through the workflow, comments, and sees the epic's completion update — and every
`work_item` that existed before the upgrade is still there, still called a Task, now with a key.
**Depends on:** M0 (`key_sequence`, `doc_link`, the `issue:*` permissions)

**db** — `V3__tracker_core.sql`
- `issue`, `issue_type` (with `level` CHECK `EPIC·STORY·TASK`), `issue_status` (with `kind`),
  `issue_transition`, `issue_link`, `issue_comment`, `issue_watcher`, `issue_doc_link`, `issue_fact`.
- Default workflow seeded per project: Backlog · Ready · In Progress · In Review · Blocked · Done.
  **Two seeding paths, both in this module (review R5):** `V3` backfills the workflow for every
  project that already exists, and ~~`project` publishes a new `ProjectCreatedEvent`
  (a `CompanyScopedEvent`; none exists today — `ProjectCreateService` writes an audit row and
  nothing else) which `tracker` consumes to seed the workflow for every project created after.
  `project` cannot import `tracker`, so the event is the only legal direction.~~ →
  **superseded 2026-08-31 (011 D-b): no event was built and none must be.** A `CompanyScopedEvent`'s
  own header says an event *type* may not cross a module boundary, so the shared class cannot exist;
  and outbox delivery has a one-minute floor, which would leave a brand-new project unable to hold
  its first issue for that long. Both paths are `tcms_seed_project_defaults`, synchronous, in the
  creating transaction — see the close-out below.
- Story-level type named `Task` per D29, so a solo project never needs a Story first.
- **`work_item` → `issue` migration** as parentless Stories per D12, then **part of** the `work`
  module retires. By file (review R4): `WorkItem`, `WorkItemController`, `WorkItemEdit`,
  `WorkItemRepository`, `WorkItemStatus`, `WorkItemStatusUpdateRequest`, `WorkItemView`,
  `WorkItemWriteRequest` and `Priority` go, with the `work_item` and `work_item_link` tables
  (`work_item_link` → `issue_link`). `BoardFacts`, `BoardService`, `BoardPeople`,
  `ItemAssignedEvent` and `derived_item_annotation` stay — see D11 below. In the contract,
  `/work-items` and `/work-items/{workItemId}` are **removed**, not deprecated (there is no
  external consumer of this contract), and the six `work-item` verbs in `packages/cli` and
  `packages/mcp` become `issue` verbs in the same commit. `workitem:manage` is struck through
  "→ superseded by `issue:*`" in the roles spec, `permission-matrix-summary.md` and `README.md`
  together, or `matrixCopies.test.ts` reddens.
- `issue_fact` carries `created_at`, `status_entered_at`, `done_at` and `release_assigned_at`
  beside the fields §3 sketches. **This is M9's schema, landing here (review R12):** cycle time
  and "scope added after the release was created" are unanswerable from a projection that holds
  only the current status, and the alternative is M9 taking a migration it was promised it would
  not need.
- Every table `company_id NOT NULL`, leading every composite index, forced RLS.

**be** — new `tracker` module. Issue CRUD, per-project key allocation through `project`'s
`project_key_sequence` allocator (M0), the level/parent depth rule,
the legal-transition set, completion computed per D8 (count, or hours when every story has an
estimate — and the basis on the wire, never inferred by the client), epic status derived per D31,
`issue_doc_link` staleness fed by the `HealthRecomputeOnLinksChanged` template, `issue_fact`
published by a `CompanyScopedEvent` and **written by nothing else**.

**D11 lands here, and it is easy to miss because it is a change to a screen that already works.**
`/my-work` and `/teams/:teamId/board` are **composite** and stay composite: `work.BoardFacts` gains
an `ISSUE` generator beside `EXECUTION`, `CASE_MAINTENANCE` and `REVIEW` — it does not retire with
the rest of the `work` module. An issue becomes the fourth card kind, and the row-backed one.
`derived_item_annotation` survives and matters more, not less: a personal note and a personal due
date on a card you do not own is exactly right for a board mixing your issues with reviews somebody
else asked of you. Replacing that composite with an issues-only list would narrow the one screen
that already answers *"what is on my plate"*.

**contract** — `/issues`, `/issues/{key}`, `/issues/{key}/comments`, `/issues/{key}/transitions`,
`/issues/{key}/doc-links`, `/projects/{id}/workflow`.

**fe** — ports `work-table.html`, `epic.html`, `story.html`, `task.html`, `issue-new.html`,
`documents.html`, `settings-workflow.html`, `settings-general.html`, and extends the existing
My Work screen to `my-work.html` — issues beside executions and reviews, with the private note and
personal due date. ~~`project-overview.html` lands here **without** its release and test-health
panels — those are M3~~ → **wrong, corrected 2026-08-31 (011 D-f): the test-health panel landed
here.** `DashboardPage` already rendered it against endpoints that exist, and D27 says the overview
reuses those regions. Only the **release** panel is absent, and the page is built so a missing
section explains itself rather than rendering empty. `my-work.html` is **two tables, not a kanban**
— see the close-out below.

**Done when:** a person runs a project's work in the UI with no board, and My Work shows their
issues across two projects beside their pending reviews and executions; `./gradlew build` green on
`shanks`; the `work_item` migration verified by counting rows either side, not by reading the SQL.

**Not in this module:** boards, rank, labels, releases, timeline, calendar.

---

### M1 closed — 2026-08-31 @ `8bf37408`, plan [011-tracker-core.md](011-tracker-core.md)

**The migration actually taken is `V3`**, as reserved. `V3__tracker_core.sql` — nine tables, the
default workflow and six issue types seeded through `tcms_seed_project_defaults`, and the
`work_item` → `issue` backfill. Nothing in the table below it needed correcting.

**Seven tasks, one stack each:** T1 the contract, T2 `V3` + the roles copies, T3 the `tracker`
module, T4 spec links + comments + the retirement of `work`, T5 the CLI/MCP issue verbs, T6 the
issue screens, T7 settings + My Work + the overview + this close-out.

#### Two of this plan's own claims about M1 were wrong, and are corrected here

1. **`ProjectCreatedEvent` was not built, and must not be.** See the `**db**` line above, now
   struck through, and review R5. The reason is in 011 D-b and it is not a preference: a
   `CompanyScopedEvent`'s own header says an event **type** may not cross a module boundary, so
   `ProjectCreatedEvent` as a shared class cannot exist; and outbox delivery has a **one-minute
   floor** (`tcms.outbox.interval: PT1M` plus `OutboxScheduler`'s `PT1M` initial delay), so a
   workflow seeded that way leaves a brand-new project with no statuses — and therefore unable to
   hold its first issue — for up to a minute. Story B1's *"a sensible default is already there"* is
   false for exactly that long. `tcms_seed_project_defaults` seeds `issue_status`, `issue_transition`
   and `issue_type` synchronously in the creating transaction, from all three project-creation paths.
2. **`project-overview.html` landed WITH its test-health panel.** The M1 section above says the page
   arrives "without its release and **test-health** panels". That was wrong in the cheap direction:
   `DashboardPage` already rendered those numbers against endpoints that exist, and D27 says the
   overview reuses those regions as its quality half — so the panel cost a move, not a build. The
   release panel is the one that is genuinely absent, and it explains itself.

#### Built and unverified

The gates prove it compiles, not that it works. Backend `./gradlew build` on `shanks`: **3680 tests,
6 failed — all six in `com.tcms.reporting.FlakeScoreTest`, pre-existing and independently confirmed**
at `668fd608` (before any T7 change) in CI run 33366513189, where the same six and nothing else
failed. It pins `BASE = 2026-06-01T08:00:00Z` against a 90-day window that expired on 2026-08-30.
Frontend: build clean, lint 0 warnings, **2313 tests in 167 files pass**. Packages: build clean,
lint 0 warnings, 414 tests pass.

What that leaves unverified:

- **Every screen T6 and T7 built.** Nobody has clicked through the lab. Ten screens, and the three
  the gates are least able to speak for are the workflow settings screen's removal panel (the count
  and the affected-issue chips are a live `GET /issues?statusId=…`, exercised only against a double),
  the epics list on the overview (one `GET /issues/{key}` per epic through `useQueries`, so its
  combined pending/failed states are the states a real server's latency actually produces), and the
  private-note dialog (`PUT /board/annotations/ISSUE/{issueId}` has never been sent to a running
  server).
- **The `work_item` migration on an instance that actually holds rows.** The count test proves it on
  a synthetic fixture, not on the lab's data.
- **`issue_fact.release_assigned_at`** — the column is written by nothing until M3, which is when a
  release exists to point at. `issue_fact.release_id` beside it is in the same state.
- **`issue_link`** — the table exists (`work_item_link` became it) and has **no writer until M3's H7
  work**. A row in it today would have to have been migrated.
- **The lost 409 message** (T4 Part B step 3). Moving a *derived* card used to answer *"this card
  mirrors a source; fix the source instead"*. It now answers **404 from `/issues/{key}`**, because a
  derived card's id is not an issue key. A known regression, restored by M2's board, and the reason
  it is not repaired here is that the board is the screen that produces the drop and M2 rebuilds it.
- **The one-request split on My Work is asserted against a double, not a server.** The test pins the
  whole list of paths requested, which is the strongest available statement that there is one read;
  what it cannot show is that a real `GET /board?mine=true` answers both halves in one response
  quickly enough to be the screen somebody keeps open all day.
- **`api.delWithBody` has never sent a real request.** It is the one DELETE in the product carrying a
  payload (the workflow status removal), and `fetch` with a `DELETE` body is a shape no other call
  site exercises.

#### Findings this module chose NOT to fix

Recorded here so the next agent finds the answer rather than the finding.

- **The three hardcoded `'Agent'` role names** (011 D-h) — `frontend/src/features/admin/IssueTokenDialog.tsx:123`
  and `:212`, `frontend/src/features/help/AgentTokenPanel.tsx:127`. **Left in place.** They are not
  what the M1 prompt's grep was written to catch: they select the one role that is
  `assignable = false`, to decide **which configuration block to print**, and each carries a comment
  saying it matches on the name because role ids are seeded data the client does not hold. The grant
  picker itself filters on `role.assignable` and never on a name, so M0's "no screen changes" claim
  stands. Removing them needs a stable role **code** on the contract that no module has asked for.
- **`settings-general.html` shows five issue types; the product seeds six** (011 T2 Part C). The
  mockup's own "Add sub-task" affordance on a story requires a `TASK`-level type, and D29 puts `Task`
  at `STORY` — so both cannot be true with one Task type. Six rows land, and
  `GeneralSettingsPage` names the **level** of each so `Task` and `Sub-task` are distinguishable.
- **This plan's M1 section said the overview lands without its test-health panel.** Corrected above;
  the section's own text is struck through rather than rewritten.
- **`Sub-task` renders as `S`, not the `T` the plan predicted.** `V3` seeds its `icon_code` as `'T'`
  ("two of them render as `T`"), but `IssueTypeView` deliberately omits `iconCode` — the contract
  declares four members and `ContractConventionTest` compares both directions — so `TypeIcon` derives
  the glyph from the type's initial. Not fixed: putting `iconCode` on the wire is a contract change
  for one glyph, and the level badge beside the name already answers the question the glyph would.
- **The project's name and key have no write on the contract**, so `GeneralSettingsPage`'s Identity
  panel is read-only and the mockup's two inputs and its Save button are absent. The key is
  documented as **immutable**, which contradicts the mockup's caption *"Existing links keep working
  if it changes"*; the screen keeps the true half of that sentence and states the immutability
  instead. A rename endpoint is nobody's module today.
- **`BoardCard` carries no `estimateHours`**, so `my-work.html`'s "Est" column is absent. An `Est`
  column reading `—` on every row is worse than no column; widening the board's card is a contract
  change no module has asked for.
- **The overview's "Tests passing" tile is denominated over cases carrying a Feature**, because there
  is no project-wide latest-result-per-case count on the contract — the only rows available are
  `RepositoryDashboard.byLastRun`, which is per Feature. The tile therefore **says what it counted**,
  and reads the same rows as the `By last run` figure below it so the two cannot disagree. When no
  case with a feature has a result the tile is not rendered at all rather than reading `0%`.
- **Two `tracker` contract-vs-code disagreements found while building T6, both backend, both left**:
  `IssueDocLink.blockExcerpt` is documented as *"what you linked, not what the block says now"* while
  `IssueDocLinkFacts.refreshIssueDocLinks` re-inserts the projection from the block's **current**
  text; and there is no re-confirm operation on the contract (correct — `stale` is a comparison, not
  a stored decision), so `LinkedSpecPanel` sends an empty `PATCH /issues/{key}` instead.

---

## M2 — Board & backlog

**Deliverable:** A person works a board with swimlanes by epic, drags a card or moves it from the
keyboard, sees a WIP breach, ranks the backlog, and sets one filter that survives switching to Table.
**Depends on:** M1

**db** — ~~`V4__tracker_board.sql`~~ → **`V5__tracker_board.sql`**, because M1 spent two versions
(`V3__tracker_core.sql` and `V4__retire_work_item.sql`) where this table budgeted one: `board`,
`board_column`, `board_column_status` (every status claimed by exactly one column, enforced),
`board_swimlane`, `issue_label`, `issue_label_assignment`, and `issue.rank` as a fractional rank.

**Two triggers a later module will meet, and neither was in this plan when it was written** (012
D-k, D-l). Both sit on tables M3 and M4 write:

- **`issue.rank` is filled by `tcms_issue_rank_default()`, a `BEFORE INSERT` trigger on `issue`** —
  not by a column `DEFAULT` and not by a service. A new issue takes `max(rank) + 1024` **within its
  project**, which a `DEFAULT` cannot express because it may not read another row, and which
  assigning in `IssueService` would make a rule that holds only for the callers who remember. The
  trigger stands aside whenever an explicit rank is supplied, which is how `RankService.place` works
  without re-implementing it. **Ties are possible and accepted** — two inserts into one project in
  the same instant read the same maximum, `created_at` breaks it, and nothing claims `rank` is
  unique. **An importer inserting a thousand issues gets a thousand ranks and no ordering intent**,
  which is M4's to decide about rather than discover.
- **`tcms_board_follows_status_removal()`, a `BEFORE DELETE` trigger on `issue_status`, is what
  keeps a board from vetoing a workflow edit.** It was found by the gate, not by reading: thirty-
  eight tests went red on `board_column_default_status_id_fkey` the first time a status was dropped
  from a workflow `PUT` while a seeded board's column defaulted to it — a plain `RESTRICT` makes a
  **board** refuse a **workflow** change, which is backwards under D2, where the workflow is upstream
  and a board is only a mapping. The trigger repoints a column at the earliest status it **still**
  claims, and deletes the column only when the departing status was the last thing it claimed. It is
  deliberately **not** `ON DELETE CASCADE`: a folded column claiming In Progress, In Review and
  Blocked would vanish when In Progress was retired, taking two live statuses off the board with it.

**be** — `tracker.board` package, not a module (§2). Column→status claims with a per-column default
for the coarse board, WIP limits that **report a breach rather than refusing a drop**, rank
rebalancing, filter state as query parameters.

**A status created after the board exists (review R13):** "every status claimed by exactly one
column" is a unique index on `board_column_status (board_id, status_id)` plus a service rule, not a
cross-table CHECK — Postgres has none. When the workflow gains a status, the service claims it for
every board of the project in the column that holds the default status of the same `kind`
(a new `IN_PROGRESS`-kind status lands beside In Progress), and the settings screen shows it there
so it can be moved. An unclaimed status is not a state the data can be in.

**contract** — `/projects/{id}/boards`, `/boards/{id}`, `/boards/{id}/cards/{key}/move`,
`/projects/{id}/backlog`, `/issues/{key}/rank`.

**fe** — ports `work-board.html`, `work-backlog.html`, `settings-boards.html`. Extends the Work
entry to the tabbed shape of D34 with the filter in the URL per the frontend rules.

The board is **the hardest frontend in the whole expansion** and its own plan must say so:
optimistic reordering on a fractional rank, swimlanes, WIP limits, and keyboard parity with the
pointer. `frontend/src/features/board/cardMove.ts` and `boardKeyboard.ts` already exist and are the
starting point. Every drag has a `⋮ Move to…` equivalent — WCAG 2.2 *Dragging Movements*, and story
B3's own non-functional requirement.

**Done when:** a card moved on the team board is in the matching column on the stakeholder board,
and both are reachable with the keyboard alone.

**Not in this module:** releases, the timeline, the calendar.

**Built and unverified.** The gates prove it compiles. The full list is in
[012-board-and-backlog.md](012-board-and-backlog.md#built-and-unverified) and is deliberately long;
what a module starting after this one needs to know is:

- **The backend suite is NOT green, and six failures are inherited rather than M2's.**
  `./gradlew build` on shanks: **3726 tests completed, 6 failed**, every one of them in
  `com.tcms.reporting.FlakeScoreTest` — red on master in the **2026-08-31 nightly, before M2's first
  task**, and no commit in this module touched anything under `reporting`. Do not read them as
  breakage from here; do not report them as fixed either.
- **Frontend and packages are green** — 2458 tests over 173 files, and 448 over 27, with
  `npm run build`, `tsc -b` and oxlint at zero warnings on both.
- **Three screens shipped and none of them has been opened in a browser**: the project board, the
  backlog and the board settings page. In particular **no drag on either draggable screen has ever
  run** — every ranking and column assertion goes through the menu or the `↑ ↓` buttons, which prove
  the *decision* and cannot prove that `@dnd-kit` hands it the id the reader thinks they are on.
- **Five of the ten board operations have never been driven over HTTP.** T3 drives the five
  configuration ones through MockMvc; the cards, the move, the rank and the labels are called in
  process, which proves the arithmetic and nothing about routing, query-string binding or status
  codes.
- **`V5` has never been applied to a database that already holds issues.** Testcontainers builds
  from empty every time and structurally cannot show it, and the `rank` backfill is the one statement
  in the file that cannot be re-run if it is wrong. It was exercised by hand against a throwaway
  Postgres 17; that is not the lab instance's data.
- **The eight CLI commands and two MCP tools have never reached a running instance.** Their gate
  compares every exchange to `openapi.yaml`, which is the contract's shape and not a handler's answer.
- **A contract gap left open**: a blank or over-long label name answers **400** with
  `Problem.validation`, which is **not declared on any of the four label operations** (they list only
  401/403/404/409). Nothing compares a handler's status codes to the declared set, so this is
  invisible to every gate. Whoever next owns `openapi.yaml` owns it.

---

## M3 — Releases, roadmap & readiness

**Deliverable:** A person creates a release, targets epics and stories at it, reads the roadmap, and
gets one answer to "can we ship 2.4?" — stories done **and** linked test cases passing.
**Depends on:** M2

**db** — ~~`V5__release.sql`~~ → **`V6__release.sql`** (the column shifted on 2026-09-01; the directory is the authority): `release` (project-scoped per D13, with `created_at` — the burn-up's
"scope added after the release was created" baseline, review R12), `issue.release_id` nullable FK —
**no join table**, per D6 — `test_run.release_id UUID NULL REFERENCES release(id) ON DELETE SET NULL`
beside the existing `release_version` text. **That is a real FK from an `execution` table into a
`tracker` table, and it is allowed (review R6):** ArchUnit governs Java imports, not DDL, and §3's
"no FK" cost applies to *polymorphic* references, which this is not. `execution` never imports
`tracker`; it holds a UUID and the database keeps it honest.

**be** — release CRUD ordered by `ReleaseSortKey` so `1.10` follows `1.9`. **`ReleaseSortKey` lives
in `execution` today** (`backend/src/main/java/com/tcms/execution/ReleaseSortKey.java`), and
`tracker → execution` is a forbidden crossing (review R6). It is a pure value type with no domain
concept of its own, so **this module moves it to `api`** beside `AutomationKeys` and `PatchBody`,
and the two `execution` readers (`TestRun`, `TestExecution`) follow the import. Story↔test-case
links as `issue_link` with target type `TEST_CASE`; readiness joining `execution_fact` on the latest
result per case; burn-up and completion report types in `reporting`.
**`release_sort_key` comes off `READERLESS_BY_DECISION` in the same commit that first reads it**, or
`ArchitectureRulesTest` fails.

**contract** — `/projects/{id}/releases`, `/releases/{id}`, `/releases/{id}/readiness`,
`/projects/{id}/roadmap`, `/issues/{key}/test-cases`.

**fe** — ports `releases.html`, `release-new.html`, `release.html`, `work-timeline.html`,
`work-calendar.html`, and the release + test-health panels of `project-overview.html` that M1 left
out. `test-cases.html` gains its "verifies which stories" column.

**No backfill (D14).** Nothing is migrated into `release`: `test_run.release_version`,
`release_sort_key` and `test_plan.milestone_name` keep working exactly as they do. **Expect the
roadmap to be empty on the day this merges** and readiness to have no history until somebody creates
releases by hand — that is the trade for having no migration to get wrong, and this module's own
plan must put it in front of whoever demos it rather than letting an empty timeline read as a bug.

**Done when:** a release with a done story whose linked case failed reads **not ready** and names
that story; a release with no run says so rather than showing zero failures.

**Not in this module:** analytics beyond readiness and burn-up (M9).

---

## M4 — Jira offramp

**Deliverable:** An admin connects Jira, previews exactly what will and will not come across, maps
every status, confirms, and finds `PROJ-4812` still called `PROJ-4812` — with a report they can
reopen a year later.
**Depends on:** M1 (M2 and M3 are **not** prerequisites; an import lands issues, and a board is a
view over them)

**db** — ~~`V6__import.sql`~~ → ~~`V8__import.sql`~~ → **`V7__import.sql`** (M5 had **not** run when
M4 started, so `V7` was free and M5 takes `V8`; the directory is the authority and this is the third
correction to that column — see the reservation section): `import_run` and `import_dropped_field`,
so the report outlives the migration.

**be** — ~~the existing Jira/ADO adapters gain an **importer** direction~~ → **superseded (review
R7).** `integration/tracker/JiraAdapter` and `AzureDevOpsAdapter` are outbound *defect-link*
clients — single-issue lookup and create, a status category — and an importer needs paging,
comments, changelog and user resolution, none of which they have; more decisively, the importer
**writes `issue` rows**, and `integration → tracker` is a forbidden crossing in either direction.
So the importer is a **`tracker.imports` sub-package with its own read client** for Jira Cloud/DC
and Azure DevOps. The outbound adapters are **not deleted and not changed** — a client mid-migration
still links defects out. Dry-run preview writing nothing (D32), a status-mapping step that **refuses
to confirm while one status is unmapped**, original keys preserved (D5), standard fields only with
every dropped field counted (D15).

~~**Still open, for this module's plan:** whether the importer reuses the per-person credential in
`integration.tracker.TrackerIdentity` / `TrackerCredential`. Reusing it is the same forbidden
crossing; the alternative is `import-connect.html` taking the connection for the run and storing it
on `import_run`, encrypted the way `TrackerCredential` is. The mockup already shows the form.~~ →
**closed (014 D-a): neither.** The connection is entered per run and **persisted nowhere** —
`import_run` has no secret column. The alternative this paragraph expected was refused for a reason
it did not see: `SecretCipher` is in `integration` too, so encrypting onto `import_run` meant a
second AES-GCM key user rather than a way around the crossing. See R14 in the review table.

**contract** — `/imports/preview`, `/imports`, `/imports/{id}`.

**fe** — ports `import-connect.html`, `import-preview.html`, `import-mapping.html`,
`import-report.html`, `settings-import.html`.

**Done when:** a preview leaves the project byte-identical, and the dropped-field list in the report
matches the preview's word for word.

~~**Open, and to be closed by this module's own plan:** what becomes of Jira sprint membership — a
label per sprint, or dropped and reported. Scope §10.~~ → **closed (review R8):** sprint membership
is **dropped and counted in the report** like any other non-standard field. A label per sprint would
make M4 depend on M2's `issue_label`, and the point of M4 depending on M1 alone is that an import
can land before there is a board. If M2 has already merged when M4's plan is written, that plan may
*offer* label-per-sprint as an option in the mapping step; it is never required.

**Landed 2026-09-03 at `ba0031bb`**, plan [014-jira-offramp.md](014-jira-offramp.md), migration
`V7__import.sql`, in eight tasks. R14 is closed (D-a): the connection is entered per run and
persisted nowhere. R8's option was taken — `labelPerSprint` is offered on the mapping step and
defaults off, M2 having landed first — and the `Sprint` row stays in the dropped list either way.

**Built and unverified:**

- **No gate has ever dialled a real Jira site.** Every backend test drives `FakeImportSource`, a
  recorded-shape double behind the `ImportSource` port. `JiraImportSourceTest` and
  `AzureDevOpsImportSourceTest` exercise the two real clients against **recorded JSON**, so what is
  proven is that this product parses the shapes it was shown — not that a live Jira Cloud site, a
  Data Center install behind a corporate proxy, or an Azure DevOps organisation serves them. Paging
  past the first page, a rate-limited response, a 10 000-issue project and a token with partial
  project visibility are all unexercised. **This is the module's largest unverified claim** and it
  is the one that cannot be closed without a real site.
- **Nobody has run a migration in a browser.** The four screens are asserted in RTL against mocked
  responses. The wizard's round trip to the workflow screen and back with a half-made mapping intact
  (014 T7) is proven by a test that calls `navigate(-1)` in a `MemoryRouter`; a real browser Back
  button has not been pressed on it.
- **The report's own promise is tested, not aged.** `ImportReportPage.test.tsx` fails the render on
  any fetch that is not `GET /imports/{id}`, which is the checkable half of *"readable a year later
  with the source system gone"*. The other half — that the row is still there and still legible
  after a year of schema changes — is a claim only time settles.
- **The stuck-run reaper has not fired in anger.** `ImportReaperTest` drives it directly. No run has
  been stranded by a real server restart mid-import and found `FAILED` with its sentence afterwards.
- **The all-or-nothing write is proven on a fake failure.** `ImportWriterTest` fails the transaction
  deliberately and asserts the project is unchanged. A source that dies at issue 900 of 1 482 has
  not been staged.
- **The client-side download has produced no file a person opened.** `downloadReport.test.ts` covers
  the CSV's quoting; `saveBlob` is the shared anchor dance and is not exercised by a test with a
  filesystem behind it. Nobody has opened the CSV in a spreadsheet.
- **The frontend suite was already red when this module started and still is** — 38 cases across
  `board/BacklogView`, `cases/CaseGrid`, `dashboard/TileLabels` and `projects/ProjectOverviewPage`,
  none of them touched by M4. `npm run build` and `npm run lint` are green, as are the backend and
  `packages` gates in full. Inherited and recorded here rather than adopted.

The gates prove it compiles. They do not prove a migration works.

---

## M5 — Agent execution

**Deliverable:** A developer connects their own assistant from their profile, flags a task
AI-eligible, and the assistant fetches it with its story, acceptance criteria, epic goal, release,
spec excerpts and build command, reports a PR back, and moves it to In Review — **and is refused
Done, with the reason.**
**Depends on:** M1. `release` in the fetch payload is a **nullable** field of the contract from the
first commit, absent until M3 has landed and the task's story is targeted at one (review R9) —
M5 does not wait for M3.

**db** — ~~`V7__agent.sql`~~ → ~~`V7__agent.sql` still, by two corrections cancelling~~ →
**`V8__agent.sql`** — M5 moved ahead of M4 (down to `V6`), then every number below M2 moved up one
(back to `V7`), and then **M4 ran first anyway and took `V7`** (2026-09-01). Read the directory
rather than this sentence: `project_repository` as reference data (D16), `issue.pr_url`,
`issue.agent_eligible` (D30), `review_enabled_issue` **defaulting to `false`** (D33).

**be** — the legal-transition set on the wire so the caller learns what it may do; the issue tool
surface on MCP and CLI; a profile token that **is** the identity — **no agent principal, no
orchestrator, no runner, no forge adapter, no model credential** (D25, D26). Actions attribute to
`<person>, via <assistant>`.

**contract** — `/me/assistants`, `/issues/{key}/transitions` gains its allowed-moves projection,
`/projects/{id}/repositories`.

**Landed 2026-09-04 @ `4f3bbf8b`, plan `015-agent-execution.md`, five tasks.** `V8__agent.sql` as
reserved — the directory was re-read on 2026-09-03 and held `V1 … V7`, `V7__import.sql` being M4's.
No roles-spec, permission-matrix or README churn: `Agent` already held every key this module needs,
`/me/assistants` costs authentication alone, and `review_enabled_issue` is a **setting**, not a
permission.

**Two deliberate narrowings of D10 §3, taken rather than discovered.** The "refuse Done" gate is the
**terminal-transition self-approval guard**, not the full review module: no `review` row is opened
for an issue, no reviewer is assigned and no verdict is recorded. It binds when
`review_enabled_issue` ∧ ¬`author_may_approve_own_work` ∧ caller-is-assignee, so **an assistant is
bound exactly as its owner would be and there is no token-specific rule** — which is what lets a
one-person company with the gate off close its own work (A6). And `project_repository` is reference
data nothing else in the product reads.

**Smoked against a running instance in T4** (`acffef5e`, transcript in that commit body): a real
`/me/assistants` token on both adapters — the queue, the context read, `attach-pr`, the 409 with its
sentence, and A6's same-move-succeeds with the gate off.

**Built and unverified.** ~~The refuse-to-close on a real project with the gate on, and the
agent-context fetch through a real Agent token~~ → both smoked in T4, above. What is left is the
whole of **T5's frontend**: nothing in it has been seen in a browser — 24 component tests, a green
`tsc -b` and oxlint at zero, none of which is a renderer. Specifically: the connect flow end to end
against a live `/me/assistants` (the token-shown-once path is asserted through a mocked `api.post`,
so the seam itself is what the tests do not exercise), the agent-context panel against a real
composite, and D33's gate biting through this UI rather than through a tool call.

**A5 — propose-then-accept — is NOT built, and is deferred rather than omitted.** An assistant
cannot propose a breakdown of a story into sub-tasks for a person to accept. D21 calls for
`tracker`'s own `issue_proposal` — its own table, because a shared row is not a shared class — and
neither the table nor an endpoint exists; the on-wire `/projects/{id}/ai-proposals` family is
`repository`'s **test-case** proposals, a different subject in a different module, and pointing the
screen at it would have been the wrong one. `story-proposal.html` is therefore unported: every arm
of it would be disabled, and a routed dead screen is worse than an absent one. Recorded as not built
in `docs/features.md`, in those words. It needs a follow-on slice: the table, the endpoint, and then
the screen.

**One open backend defect, carried out of T4 and NOT fixed here.** `POST /issues` with
`agentEligible: true` answers **500** (`rank=NULL` on the post-insert flush), which also breaks
`seed-demo-data` and therefore blocks building a demo instance. It is not M5's deliverable and it is
open.

**Nothing downstream is rewritten:** no module's *Depends on* names M5. M3 shows
`done 2026-09-01 @ 55cb4bc0`, so the R9 note about populating `issue.release_id` is unnecessary —
the release fact is populated and `AgentTaskContext.issue.release` carries it.

**No R-number taken.**

**fe** — ports `profile-assistants.html`, `assistant-connect.html`, `story-proposal.html`,
`settings-repositories.html`, the AI-eligible toggle and marker on cards and lists, and the
"Connect an AI assistant" entry on `help.html`.

**Done when:** a scoped assistant asking for another project's tasks receives nothing from it; a
close attempt is refused and the refusal is on the issue's history.

---

## M6 — Time · M7 — Staffing · M8 — Billing

Each one is a module on the same shape. Their own plans carry the detail; what matters at this
altitude is the boundary between them.

| | **M6 Time** (~~`V8`~~ → `V9`) | **M7 Staffing** (~~`V9`~~ → `V10`) | **M8 Billing** (~~`V10`~~ → ~~`V11`?~~ → `V12`) |
|---|---|---|---|
| **db** | ~~`V8`~~ → **`V9`** (Mc and M7 unspent). `time_entry` (`subject_type` + `subject_id` — an issue, a meeting, leave, training or admin: **five** members, and `TEST_RUN` is deliberately not one), `timesheet_period`, and **`issue_time_rollup`** — the projection, which the table below did not name. Plus `project_settings.track_time` and, added by T5, `project_settings.trackTime` on the wire | ~~`V9`~~ → **`V10`, landed 2026-09-04** — the version the directory gave matched the reservation for the first time in five, because M6 spent `V9` and nothing else had moved. `person_profile`, `skill`, `person_skill`, `allocation`, `absence`; five `FORCE` policies, `SchemaConventionTest` 95 → 100. **No capacity column**: capacity is derived on every read (017 D-a). **M8's `V10` above is therefore spent** — Mc is still unstarted, so M8 takes whatever `ls` says on its own day and does not do this arithmetic in advance | ~~`V10`~~ → ~~`V11`?~~ → **`V12`, landed 2026-09-05** — `ls` showed `V1 … V11` on the day, Mc having spent `V11` the day before. `rate_card`, `rate_card_entry`, `budget`, `invoice`, `invoice_line`, `expense` — and **`invoice_line_entry`**, a **seventh** table this row did not name (019 D-b): billing may not write a column on `time_entry`, so *which approved hours are already on an issued invoice* is a billing-owned join with no foreign key to the second side, and it is the only thing standing between this module and double-billing. Seven `FORCE` policies, `SchemaConventionTest` 102 → 109 |
| **be** | log, submit, approve, reject-with-note; approved hours roll up to story, epic, project as a **`time`-owned projection joined to `issue_fact`** — `time` reads `issue_fact` and never writes it (review R10; the trap scope §3 names) | capacity, allocation percentage, over-allocation flag, absence zeroing capacity everywhere | approved time priced by rate card, budget burn, draft and issue an invoice, lines lock on issue |
| **fe** | `timesheets.html`, `timesheet-approvals.html`, **and the five additive surfaces T5 added**: the story's Time panel and its `Logged` detail row, the epic's estimate-vs-actual table, the work table's `Logged` column and footer, My Work's This-period figure, and the Track-time toggle on General settings | ~~`staffing.html`, `directory.html`~~ → **four screens**: `/staffing` and `/directory` (COMPANY, `staffing:directory`), `/projects/:projectId/staffing` (PROJECT, `staffing:read` / `staffing:allocate`) and `/directory/:userId`, which carries `member.html`'s Profile / Skills / Absence panels — **there is no member page under Teams and M7 built none** (017 D-g). Plus the two figures M6 left absent, completed by T6 | `billing.html`, `invoice.html`, and the client page's hours-and-invoices table — **all three, plus three figures on screens other modules own**: the client page's two KPI tiles and its rate-card panel, the project overview's Budget burn panel, and General settings' Budget row. The **Billing rail entry is absent** rather than disabled for a caller without `billing:read` (019 D-o), the fifth recorded exception to this app's disable-don't-hide rule |
| **Depends on** | M1 | ~~M6~~ → **M0** (review R11: profiles, skills, allocation, absence and capacity read no time entry — allocation is against a project, which is foundation. *Utilisation*, allocated vs logged, is the one figure that needs M6, and it is out of M7 unless M6 has landed) | M6 · Mc |
| **A person can now** | record where the day went and have it approved — **landed 2026-09-04 @ `3471358c`, plan [016-time.md](016-time.md)**, closure below | answer "who is free, and when" — **landed 2026-09-04 @ `44f8d39a`, plan [017-staffing.md](017-staffing.md)**, closure below | turn approved hours into an invoice — **landed 2026-09-06 @ `c58384b5`, plan [019-billing.md](019-billing.md)**, closure below |

**The sharpest boundary in the expansion is money, and it needs a test asserting the negative.**
`billing:*` and `ratecard:read` are Finance and Company Admin only. A Test Engineer with `time:log`
logs hours and cannot see what they cost; an Account Manager sees the client and the hours and never
a rate. Assert that they *lack* it — do not read the matrix carefully and conclude it holds.

**M8 excludes** fixed-fee and retainer invoicing (D24), and never bills AI-assistant usage — it is a
cost, not a line.

### M6 closed — 2026-09-04 @ `3471358c`, plan [016-time.md](016-time.md)

**The migration actually taken is `V9`**, not the `V11` this table reserved — struck through above
rather than deleted. The reason is the reservation warning's own, arriving for the fourth time:
**neither Mc nor M7 has started**, so the two versions the confirmed order spends before M6 were
free. `ls` on the day showed `V1 … V8`.

**Five tasks, one stack each:** T1 the contract + both clients + the CLI/MCP verbs, T2 `V9`, T3 the
`time` module and the money-boundary negative test, T4 the timesheet and approvals screens, T5 the
five additive surfaces and this close-out.

#### What T5 found that this plan and 016 both had wrong

1. **`trackTime` was never put on the wire, and T5 had to add it.** 016 D-g decided the switch and
   T2 landed `project_settings.track_time`, T3 mapped it on the entity, and `TimeEntryService` reads
   it — but no task ever added it to `ProjectSettings` or `ProjectSettingsUpdate`, and
   `ProjectSettings.java` said so outright: *"It has no setter yet because the toggle's contract
   field and its `PATCH` arrive with the settings screen."* T5 was cut as `fe` + `docs`, so closing
   it meant taking a contract + `be` slice inside a frontend session — **a deliberate departure from
   CLAUDE.md's one-stack rule, taken on the user's decision rather than silently.** The alternative
   was shipping a module whose central decision was unenforceable and whose toggle did not exist.
2. **`IssueSummary` carries no logged hours**, so `work-table.html`'s `Logged` column had no source
   either — and the epic-rollup operation's own description claimed *"a work-table footer the issue
   list already carries per row"*, which was false. Closed by a **new bulk operation**,
   `GET /time/rollup/issues?key=…`, served by `time` from its own projection. Chosen over adding
   `approvedHours` to `IssueSummary` because that would make `tracker` — a leaf — read another
   leaf's projection on every work-table page load, whether or not the column is drawn.
3. **The five surfaces are not all behind `trackTime`, and D-g does not say they are.** D-g names
   the `Logged` column, the story and epic panels, and the log form's picker. **My Work's
   This-period figure is deliberately ungated**: it is a *person's* fortnight and a period spans
   projects (D-i), while `trackTime` is one project's decision, and the screen names no project at
   all. A prompt line saying "all five surfaces" was narrowed to what the decision actually says.

#### Built and unverified

The gates prove it compiles, not that it works. Backend `./gradlew build` on `shanks`: **3901 tests
green**. Frontend: build clean, lint 0 warnings, **2705 tests in 194 files, 2 failing**. Packages:
build clean, lint 0 warnings, tests green.

- **The two failures are `src/features/cases/CaseGrid.test.tsx` and they predate this module** —
  *survives paging* and *select-all reaches the page*, both `Number of calls: 0` after a
  `userEvent.click` on the Radix `Checkbox`. Verified failing on a clean checkout at `90667f1e`
  with the working tree stashed. Not absorbed here; whoever opens that file next owns it.
- **Every screen T4 and T5 built.** Nobody has clicked through the lab. Seven surfaces, and the
  three the gates can say least about are the **approvals queue** (`POST .../decision` has never
  been sent to a running server, so the approve-then-rollup path is proven only by
  `TimeRollupTest` at the service level), the **work table's `Logged` column** (its bulk read is one
  request whose keys change with every filter, and only a double has answered it), and the
  **Track-time toggle** (the `PUT` is exercised by `TrackTimeSettingTest` and by an RTL double, and
  by nothing in between).
- **`GET /time/rollup/issues` has never served a real page of fifty keys.** Its 200-key cap, its
  per-project gate loop and its drop-the-unreadable-key rule are asserted at the service level in
  `TimeRollupTest` and nowhere against HTTP.
- **`Idempotency-Key` on `POST /timesheets/entries` is declared and not implemented.** `V9` gave
  `time_entry` no column to store a key, so a retry after a timeout logs the hour twice. Stated in
  `TimeEntryController`'s header rather than papered over; the fix is a column and a lookup.
- **`issue_time_rollup` has no reader outside `time`**, by design (D-c). M8 is its second consumer
  and the first from outside; until then the money boundary is proven only as a negative.
- **The rail's waiting-count badge was deliberately not built**, and the argument is in
  `AppShell.tsx`'s `TIMESHEETS` comment: the count needs `GET /timesheets/approvals`, which 403s for
  anyone without `time:approve`, and `time:approve` is PROJECT-scope with no company-wide "where may
  I approve" read on the contract. The count is on the approvals screen's own subtitle instead.
  Revisit if the contract grows that read — not by making the rail guess.
- **`Approve all` and `Remind` from `timesheet-approvals.html` are absent**, with the reason in
  `ApprovalsPage.tsx`'s header: no batch operation on the contract for the first, no endpoint at all
  for the second.
- **Capacity is absent, not zero** (D-j). The timesheet's KPI row ships with three tiles rather than
  the mockup's four, and My Work's This-period panel ships with Logged alone and no meter. Both are
  M7's to complete, and M7's prompt below now says so.

---

### M7 closed — 2026-09-04 @ `44f8d39a`, plan [017-staffing.md](017-staffing.md)

**The migration actually taken is `V10`**, which is what this table reserved — the first time in
five that the reservation and the directory agreed, and only because M6 spent `V9` and nothing else
had moved. `ls` on the day showed `V1 … V9`. The strikethrough above is kept: the number was
re-derived, not inherited.

**Six tasks, one stack each:** T1 the contract + both clients + the CLI/MCP verbs, T2 `V10` and the
five tables, T3 the `staffing` module (capacity, over-allocation, absence, utilisation, and the
demo-data seeding), T4 the two company screens, T5 the project screen and the person page, T6 the
two capacity figures M6 left absent and this close-out.

#### The two capacity figures M6 left absent — **both completed**

016 D-j removed the timesheet's fourth KPI tile and the Capacity half of My Work's This-period
panel because no figure existed that `time` would not have been inventing. Both are built:

- **`/timesheets` shows four tiles** — Logged · Period capacity · Approved · Status — in a
  `md:grid-cols-4`, with capacity second where `timesheets.html` draws it.
- **My Work's This-period panel shows Capacity beside Logged**, with the meter under them at
  `logged / capacity`, clamped at 100% and re-toned above it.

**The caveat is D-j's own refusal, and it is preserved rather than removed:** where the caller has
no `person_profile` row both surfaces read `—` and *no profile recorded*, never `0h` and never a
defaulted 40, and **the meter is absent for that person** rather than drawn against a made-up
denominator. That is a real state, not a missing screen.

Both read `GET /staffing/capacity/me?from&to` over the **loaded period's own bounds** (016 D-a —
`features/time/periods.ts` stays navigation and labelling only), and each puts that read in its
**own nested `DataRegion`** (017 D-m), so a staffing outage empties the capacity figure and leaves
the logged hours, which `time` served correctly, on screen.

#### Utilisation landed

**`009-execution-breakdown.md` t32's *"No endpoint in this contract reads a time entry"* is
superseded** and is struck through there. It was written on 2026-08-30, when the confirmed order put
M6 *after* M7; M6 landed first, so the condition it guarded is gone. `PersonCapacity.approvedHours`
and `utilisationPercent` are served, computed as **approved hours ÷ allocated capacity** (017 D-i),
and read `time_entry` / `timesheet_period` through `com.tcms.staffing.TimeFacts` — native SQL,
read-only, the fifth instance of the named cross-module read pattern, pinned from both ends by
`StaffingReadsNoTimeEntryTest`. The M9 prompt below says so, so M9's analytics does not build a
second utilisation.

#### Built and unverified

The gates prove it compiles, not that it works. On `shanks` at T6: frontend build clean, lint 0
warnings, **2805 tests in 202 files, 2 failing**; packages build clean, tests green; backend
`./gradlew build` **`UP-TO-DATE`** — T6 changed no backend source and none of the out-of-source
inputs `build.gradle.kts` declares, so the last real backend run of this module is T3's.

- **The two failures are `src/features/cases/CaseGrid.test.tsx` and they predate this module** —
  the same two 016 recorded, *survives paging* and *select-all reaches the page*, both
  `Number of calls: 0` after a `userEvent.click` on the Radix `Checkbox`. Not absorbed here either.
- **No screen in this module has been opened against a running instance.** All six — `/staffing`,
  `/directory`, `/directory/:userId`, `/projects/:projectId/staffing`, the timesheet's capacity tile
  and My Work's meter — are proved by RTL against doubles and by the contract checks only.
- **T4's and T5's records both say `seed-demo-data` writes no row into `V10`'s five tables. That is
  wrong, and this is the correction.** `DemoWorkspaceSeeder.seedStaffing` landed with T3 (commit
  `b14428fa`) and writes, per project, three profiles with different contracted hours, a skill
  vocabulary, two allocations that put two people over 100% across two projects, and one half-day
  absence. Both frontend tasks stated the absence from the frontend, and neither checked the
  seeder. What is true is the sentence they were reaching for: **nobody has run it and looked.**
- **Every write is proved against doubles and the contract checks only.** No allocation, absence,
  profile or skill has been written to a real database *from this UI* — the create, the patch, the
  two deletes and the two `PUT`s included.
- **`GET /staffing/capacity` has never served a company of eleven people over three months against
  a real database.** The five staffing tests are service-level over `StaffingTestBase`; there is no
  endpoint test for this module, so the controller, its gates and its serialization are exercised
  only by the contract conformance checks and by `ContractConventionTest` reading the spec.
- **`TimeFacts.approvedHoursBy` has one caller and no test that returns a row.** `StaffingService`
  calls it; the SQL executes in `AbsenceZeroesCapacityTest` and `OverAllocationTest`, and in both it
  matches nothing — one asserts `utilisationPercent` is zero and the other that it is null.
  **A non-zero utilisation has never been computed**, in a test or against an instance. The two
  architecture assertions are over file *text*, not over the join.
- **`tcmsFaults.staffing('no-profile' | 'over-allocated')` is how the no-profile state is looked at**
  — no fixture and no seeded instance produces the five-null body. It deliberately does **not** reach
  `/projects/{id}/staffing`, whose `ProjectStaffingView` carries no profile; a fault for that screen
  is a new predicate, not a widened prefix.
- **The lab was not seeded and no real allocation was made** during this module. The demo seeder
  above is the route to both, and running it is the first thing a verification pass should do.

### M8 closed — 2026-09-06 @ `c58384b5`, plan [019-billing.md](019-billing.md)

**The migration actually taken is `V12`**, which is what this table's third reservation guessed and
what `ls` showed on the day — Mc spent `V11` on 2026-09-05 and nothing else had moved. The
strikethroughs above are kept: the number was **re-derived from the directory**, not inherited, and
019 D-a records that T2's first command was the `ls` and that the directory wins over the plan if
they disagree. **This column has now been re-derived six times and been wrong five.** R15 stays
open by design.

**Seven tables, not the six this table named.** `invoice_line_entry` is the seventh (019 D-b) and it
is not a convenience: *"which approved hours are already on an issued invoice"* has no other place
to live, and without it the module double-bills. It is a billing-owned join with **no foreign key**
to `time_entry`, the polymorphic crossing scope §3 prescribes.

**Six tasks, one stack each:** T1 the contract + both generated clients + the CLI/MCP verbs, T2
`V12` and the seven tables, T3 rate cards, pricing, budgets and expenses, T4 the invoice lifecycle
and the two negatives, T5 the rail entry and the two billing screens, T6 the client table, the two
budget figures and this close-out.

#### The two figures other modules left absent — **both completed**

016 T5 left `settings-general.html`'s Budget row unbuilt and 011 T7 left the overview's Budget burn
panel unbuilt, each because the module that owned the number did not exist. Both are built, and
**both assertions of their absence were reversed in place rather than deleted** — struck through
with the reversal beside them, in this repo's superseded style, so the next reader can see the old
reasoning was considered rather than missed.

The rule that made those absences right is now the rule that constrains what landed: **neither
surface carries a currency, ever.** `GET /projects/{projectId}/budget-burn` is gated on
`project:read` and its schema has no `amount` and no `currency` member **at all** (019 D-h), which
is what makes it safe for the one reader who may call no other endpoint in this module. A
`MONEY`-basis budget renders as a percentage alone; where the client has no rate card the burn is
`null`, which reads as **unknown** and never as zero.

#### The money boundary, asserted as a lack rather than read off the matrix

The paragraph above this section asked for a test that asserts the negative, and there are two:
`BillingMoneyBoundaryTest` derives its paths **from the contract** and asserts a Test Engineer is
refused every one, and `TimeMoneyBoundaryTest` was **extended rather than rewritten** (019 D-n) with
an arm asserting an Account Manager holds `client:read` and not one of the seven money keys. The
third statement of the same boundary is on the client screen and is the server's: `amountsVisible`
is a field on the payload, not a decision the browser makes, because a `null` amount that means
either *no invoice* or *not your business* is a lie a renderer cannot detect (019 D-i).

#### Built and unverified

The gates prove it compiles and that the assertions written for it hold. They do not prove it works.
On `shanks` at T6: **frontend build clean, lint 0 warnings, 2939 tests in 212 files, 2 failing.**
Backend and packages last ran at T4 and T1; T5 and T6 changed no backend source and none of the
out-of-source inputs `build.gradle.kts` declares.

- **The two failures are `src/features/cases/CaseGrid.test.tsx` and they predate this module** —
  *survives paging* and *select-all reaches the page*, the same two 016 and 017 both recorded, both
  `Number of calls: 0` after a `userEvent.click` on the Radix `Checkbox`. **Not absorbed.** Three
  modules have now declined to absorb them; they are a real defect in that grid's selection or in
  its test, and nobody has opened it.
- **No screen in this module has been opened against a running instance.** `/billing`,
  `/billing/invoices/:invoiceId`, the client page's table and rate-card panel, the project
  overview's Budget burn panel and General settings' Budget row are proved by RTL against doubles
  and by the contract conformance checks only.
- **`seed-demo-data` was extended and RUN at T4** — `DemoWorkspaceSeeder.seedBilling` writes rate
  cards, budgets and two invoices, and T4's step 8 required running it rather than only writing it.
  **Nobody has signed in afterwards and looked at the result**, which is the difference between a
  seeder that executes and a screen that renders what it wrote.
- **The concurrent-draft race in 019 D-c has never been executed by two callers.** A draft is a live
  computation over approved hours, and two people drafting the same client and period at the same
  moment is a case no test drives — the immutability proof issues, doubles the rate card and
  re-reads, which is a sequence, not a race.
- **`Preview PDF` does not exist and there is no expenses screen**, both deliberately (019 D-j,
  D-q). Expenses are reachable from the CLI and MCP; no mockup draws a panel, and plan 009's rule is
  that no module ports a screen it has no endpoint for — this is that rule in the other direction,
  recorded so the absence is not later read as unfinished work.
- **No invoice has been issued from the UI against a real database.** The lock on issue is proved at
  the service level; the button that fires it is proved against a double.

---

## M9 — Delivery analytics

**Deliverable:** A PM reads a release burn-up that distinguishes scope added after the release was
created, and cycle time per issue type.
**Depends on:** M3
**db** — none. Reads `issue_fact` and `execution_fact`. **"None" holds only because M1 put
`created_at` / `status_entered_at` / `done_at` / `release_assigned_at` on `issue_fact` and M3 put
`created_at` on `release`** (review R12). If either was skipped, M9 takes the next free version.
**fe** — ports `reports.html`.
**Excludes velocity.** D6 removed the sprint it would measure; there is nothing to compute it over
and a chart implying otherwise would be a lie about how the team plans.

### M9 closed — 2026-09-06 @ `ed299a79`, plan [020-delivery-analytics.md](020-delivery-analytics.md)

**The last module of the expansion, and it took no migration.** The condition this document set —
*"'None' holds only because M1 put `created_at` / `status_entered_at` / `done_at` /
`release_assigned_at` on `issue_fact` and M3 put `created_at` on `release`"* — was checked against
the migration files before the plan was written, not assumed: all four are in `V3` (lines 338-341)
and `release.created_at` is in `V6` (line 84), and `V3` seeds a `Done` status with `kind = 'DONE'`.
The next free version is `V13` and M9 leaves it free. **The reservation was right for once**, and it
is the only row in this table's `V` column that never needed correcting.

**Three tasks, three commits:** `a8a04ff8` the contract and both clients, `ae3e45d7` the reporting
package, `ed299a79` the screen. Gates on shanks: backend `BUILD SUCCESSFUL`, 4029 tests; packages
build + lint + 560 tests; frontend build + lint + 2956 of 2958 — the two failures are
`CaseGrid.test.tsx`'s selection pair and are **pre-existing**, verified by stashing every file of
the frontend task and re-running them at `ae3e45d7`. (`FlakeScoreTest`, red since 2026-08-30, now
passes: its 90-day window has moved back over the fixture.)

**Four decisions the mockup and this document did not predict**, each recorded in 020's own
Decisions section with the measurement or the failure behind it:

- **Cycle time is measured from *created*, not *started*** (020 D-c). This product records no moment
  at which work began — `issue_transition` is the workflow allow-list, not a history — so
  `status_entered_at` on a completed issue is when it entered *Done*, and the subtraction the
  execution breakdown's `t45` predicted yields approximately zero for every issue. `measuredFrom` is
  on the wire and on the screen, with one enum member.
- **The cumulative flow has a fourth band** (020 D-d) — issues that existed on a past date whose
  status *on that date* is recorded nowhere. Folding it into "not started" would draw a guess as
  data, systematically wrong the further back the window reaches.
- **The flow is faceted rather than stacked** (020 D-k). The three-hue stack `reports.html` draws
  could not be built: running the tokens it needs through the `dataviz` validator against this app's
  surfaces reproduced the exact measurement `Figure.tsx` records — three steps of one hue reach at
  best ΔE 9.2 inside the contrast band, and three distinct hues fail CVD separation outright.
- **The rail entry is `Analytics`, not `Reports`** (020 D-l). The mockup's `data-projnav` slot is
  occupied by the shipped execution-reports screen, which nothing in this document asked M9 to
  remove.

**Built and unverified.** The gates prove it compiles and that its assertions hold; **nobody has
opened this screen.** The backend was not answering on `localhost:8080` at close-out and this app
has no mock API lane, so the click-through step of the module's own plan did not happen. What that
leaves unconfirmed, listed rather than glossed:

- **Every one of the four panels, in both colour schemes.** Each figure's fills and strokes are
  `var(--…)` and `tokenSystem.test.ts` covers the token system, but nothing in this repo renders an
  SVG and looks at it — that check is the SVG-shaped hole in it, and `vizTokens.ts` says so.
- **The two fault arms.** `tcmsFaults.analytics('sparse')` and `('all-late')` are unit-tested
  through the page's own doubles but have never been typed into a browser console.
- **The Export menu.** The CSV builders are pure and the download path is three lines of DOM; no
  file has actually been saved from a running app.
- **The estimate-accuracy panel against real epics.** Its N+1 shape (one rollup request per epic) has
  only been exercised against an empty epic list.
- **The rail entry's position and the screen's layout at narrow widths.** The grid is
  `lg:grid-cols-2` and has not been looked at below that breakpoint.

**Nothing downstream to rewrite.** No module's *Depends on* names M9 — it is the last of the ten —
so step 4 of the hand-off protocol is satisfied by saying so rather than by an edit. Review row R15
was closed by M8 and stays closed; M9 is its final piece of evidence, having read the directory,
found `V1`–`V12`, and needed none.

---

## Mc — Clients *(deferrable — build it the week the first real client exists)*

**Deliverable:** A company records a client, adds contacts, attaches a project to it, and the Client
column, filter and per-client rollups appear where they are relevant.
**Depends on:** M0
**Migration:** the **next free version at the time it starts** — see the reservation warning above.

**db** — `client`, `client_contact`; `project.client_id`'s FK constraint added to the column M0 shipped.
**be** — new `crm` module. Client CRUD, contacts that never become logins.
**fe** — ports `clients.html`, `client.html`, `client-new.html`, and the Client column and filter on
`projects.html` and the Client field on `project-new.html` / `settings-general.html`.

**The empty case is the acceptance criterion, not a nicety.** With no clients: no Client column, no
Client filter, no Client field, no "attach a client" prompt, and nothing marking a project as missing
one. `clients-empty.html` is the only trace, and it exists so the first one can be created.
Test that absence — a company building its own products is the owner's own case.

---

## How a module's plan is cut into tasks — one stack, one agent, one session

*Added 2026-08-30 by owner directive, and it supersedes nothing above — it constrains the shape of
each module's own plan, which this document never specified.* The same rule is in the owner's global
`CLAUDE.md`, so it applies to every plan in every project; it is restated here because a module plan
is written from this document and should not have to go looking.

**A task is one tech stack, one agent, one session.**

1. **Never mix stacks in one task.** A task is `be` (backend / database / migrations), `fe` (the
   SPA), `ts` (the shared packages — `@tcms/client`, `@tcms/cli`, `@tcms/mcp`) or `docs`. A task
   needing two of those is two tasks.
2. **Every task names its stack and its agent** on the task itself, beside its deliverable.
3. **Fill the session, then stop.** Merge consecutive same-stack work into one task until it would
   exceed a **150k-token context**, then start the next. The goal is the *fewest sessions*, not the
   smallest tasks — an agent switch costs a whole context reload, and twenty small tasks re-read
   the same files twenty times.
4. **Budget the 150k before writing the task, and show the budget in the plan.** Count the plan
   section, `CLAUDE.md`, the pattern files the session must read, the files it writes and the build
   output it reads back. Over budget: split **along a seam inside the same stack**, never by handing
   half to another one.
5. **The tree still builds at every task boundary**, and each task still states one deliverable.
6. **Order:** the contract and the schema first (both stacks bind to them), then `be`, then `ts`,
   then `fe`. **The documentation close-out rides with the last task rather than taking a session of
   its own** — that session is the only one that knows what actually landed and what is
   built-and-unverified, and a session that only writes markdown is the wasted agent switch this
   rule exists to remove.
7. **One task is one session, so the module plan carries one paste-ready prompt per task**, in a
   `## Prompts — one per task` section, each complete on its own and naming what to read, what to
   commit and which gate to run. This document's per-*module* prompts stay what they are — they
   start a module; the module's own per-*task* prompts run it.

**What this costs, accepted deliberately:** no user-facing capability works until both its `be` and
its `fe` task have landed, so a module cannot be stopped mid-feature and still be demonstrated.
That is the price of one agent switch per stack instead of one per capability. The module's own
*deliverable* is unchanged — "a person can now …" is still what closes it, and it is still the last
task in the module that makes it true.

**`009-execution-breakdown.md` already cuts this way** — `t3`–`t8` are contract → `data-architect`
→ `be-java` → `tooling-engineer` → `fe-react` × 2, one agent each. What this section adds is that
the *module plan* below it must do the same, rather than cutting by capability and leaving the
stack switches inside a task.

---

## How a module is executed

1. **Write its plan.** `ep-writing-plans` → `docs/plans/0NN-<module>.md`, flat `T1…Tn`, one
   deliverable each, actual code in every step. Take the next free `NNN`; never renumber.
   **Cut the tasks one stack / one agent / one session** per the section above — a task mixing
   `be` and `fe` is two tasks, and consecutive same-stack work is merged until it would exceed
   150k tokens.
2. **Check the migration number against the directory**, not against this document's table.
3. **Contract first, in one commit** — `openapi.yaml`, both generated clients, `contractBodies.ts`
   in both copies.
4. **Execute** with `ep-executing-plans`. Commit at the end of each task, staging only that task's
   files — `git add <paths>`, never `git add -A`, because concurrent sessions happen in this repo.
5. **Gates on `shanks`**, per the standing directive: `~/bin/tcms-remote sync` then
   `backend ./gradlew build`, `frontend npm run test:run`, `packages npm run build`. Read
   `docs/specs/shanks-runner-details.md` first — `BUILD FAILED` with `Could not write XML test
   results` and zero assertion failures is an fd limit, not a red suite.
6. **Close the module's docs** — `docs/features.md`, the MCP surface, the roles copies.
7. **Say what is built and unverified.** The gates prove it compiles, not that it works: a no-op
   theme toggle, two contradictory percentages on one screen and a provider-ordering bug that
   rendered nothing but the error page all typechecked and linted clean.
8. **Close this document** per the [hand-off protocol](#prompt-hand-off-protocol): status table,
   the V actually taken, and the dependent prompts rewritten. Its own commit.

**One module at a time, by default.** Work in this repo is sequential — parallel lanes were tried
four times and reversed, and two agents in one tree produced three silent losses of committed work
with no conflict and a clean `git status`. If a second lane is genuinely wanted, read
`docs/specs/parallel-work-rules.md` first, then [What can run in parallel](#what-can-run-in-parallel)
below for which modules are disjoint *by file* — and note that **two modules can never run in
parallel without pre-assigning their migration versions**, which is exactly the trap the
reservation warning above describes.

---

## Prompts — one per module

Each block below is what is pasted into a fresh session to start that module. They are written to
be **complete on their own**: the agent reads the plan section, the decisions, the stories and the
mockups it names, writes the module's own plan, executes it, and closes by editing *this* document.
**The prompts are living text** — the agent that finishes a module rewrites the prompts of the
modules that depend on it (see the protocol), so the block here is always the current one, not the
one written on 2026-08-30.

**The UI source of truth is `docs/mockups/`.** Open `docs/mockups/index.html` and walk the flow
before writing a screen. The mockups are static renders on the app's own tokens
(`assets/tcms-mock.css` mirrors `frontend/src/index.css`), so a screen is a translation into the kit
and TanStack Query, not a redesign. The rule of precedence when they disagree: **on what a screen
shows — layout, copy, empty state, the `⋮ Move to…` / `↑ ↓` keyboard paths — the mockup wins; on
what the system does — permissions, module boundaries, what is written where — this plan and the
scope spec win.** Every disagreement is recorded in the module's own plan, not resolved silently.

Every task has landed. Its prompt blocks were deleted when the plan was filed (2026-10-05); they are in git history before that commit.

## What can run in parallel

*Added 2026-08-30 from the review below.* The dependency graph is
`M0 → M1 → {M2 → M3 → M9} · {M4} · {M5} · {M6 → M8}`, with `M7` and `Mc` after M0 and `M8` after
M6 + Mc. **Independent in the graph is not the same as safe beside each other**: M2, M3, M4 and M5
all edit `tracker`'s `Issue` entity and service (M2 adds `rank`, M5 adds `pr_url` and
`agent_eligible`, M4 writes issues, M3 adds `release_id`), so they serialise regardless of what the
table says. What is disjoint by *module and file*:

| Safe beside each other | Why |
|---|---|
| **M1 ‖ Mc** | `tracker` vs the new `crm`; Mc's only overlap is the projects list screen and `project`'s edit form |
| **M2 ‖ M6**, **M3 ‖ M6**, **M3 ‖ M7** | `tracker.board` / release vs `time` / `staffing`; `time` only *reads* `issue_fact` |
| **M4 ‖ M7**, **M5 ‖ M7** | `staffing` touches no `tracker` file |
| **M9 ‖ M8** | `reporting` reads vs `billing` writes |

**Never beside each other:** M0 and anything (it edits the three pinned matrix copies and the
`Role` enum); M2 / M3 / M4 / M5 with one another; M8 before Mc has landed.

### ~~Two lanes~~ → **one lane. Decided 2026-08-30.**

The two-lane shape below was offered and **declined**. Both lanes provably touch `openapi.yaml`, both
generated clients, `contractBodies.ts` ×2, `keys.ts`, `labels.ts`, `permissions.ts`, the router,
`devFaults.ts`, `docs/features.md` and the MCP surface doc — the list this section itself ends with —
so under the rule that tasks sharing files share a lane, it is one lane. CLAUDE.md's *Running work in
parallel — don't* is the standing directive, earned from four attempts, four reversals and three
silent losses of committed work with a clean `git status`.

**The order actually being executed is `M1 → M2 → M3 → M5 → M4 → Mc → M7 → M6 → M8 → M9`**, one lane,
in [009-execution-breakdown.md](009-execution-breakdown.md). Cost accepted: wall-clock is the sum of
every module, and M8 — which needs both M6 and `Mc` — sits near the end. The analysis below is kept
because it is still the correct answer to *which modules are disjoint by file*, which is what a
reader needs the day this is revisited.

~~**The practical shape is two lanes at most, in two checkouts:**~~

- **Lane A — the tracker spine:** `M0 → M1 → M2 → M3 → M5 → M4 → M9`.
- **Lane B — people and money:** `Mc → M7 → M6 → M8`, starting once M0 has merged; M6 waits for M1,
  M8 for M6 and Mc.

Conditions, all from `parallel-work-rules.md` and none of them optional: one holder of
`openapi.yaml` and `npm run gen:api` per commit (two regenerations do not merge); the fixture UUID
prefix space pre-assigned; **migration versions assigned in landing order at integration, not from
this document's table** — the moment lane B lands anything before M3, the ~~`V8 / V9 / V10`~~ →
`V11 / V10 / V12` reserved for M6–M8 above are wrong, and a lower number arriving after a higher one is refused on every
already-migrated database while Testcontainers stays green.

**Files both lanes touch no matter what**, so a merge is expected on each: `src/api/queries/keys.ts`,
`lib/labels.ts`, `lib/permissions.ts`, the router and navigation, `lib/devFaults.ts`,
`contractBodies.ts` in both copies, `docs/features.md`, and the MCP surface document.

---

## Review of 2026-08-30 — what was found, and what became of it

The plan was checked against the code before any module plan was written. Each item is marked
where it was resolved above; the two that need the owner or a module plan are named as such.

| # | Finding | Status |
|---|---|---|
| R1 | M0's `key_sequence` PK `(company_id, entity_type, project_id)` with `project_id` nullable is invalid — Postgres rejects a nullable PK column. Seven Java readers and the V1 seed function were also unlisted. | **Resolved** — new `project_key_sequence` table owned by `project`; `key_sequence` untouched. M0 db + be. |
| R2 | M0's "`git diff --stat frontend/src` is empty" contradicted "both generated clients move" — `schema.d.ts` is under `frontend/src`, and `Role.name` is a contract enum. | **Resolved** — done-when names the one generated file. |
| R3 | M0's `project.client_id` bullet said `REFERENCES client(id)` and "the FK is added by Mc" in one sentence. | **Resolved** — column only, no `REFERENCES`. |
| R4 | M1 said "the `work` module retires" and "`BoardFacts` does not retire" with no file list; nothing said what happens to `/work-items`, its CLI/MCP verbs, or `workitem:manage` in the three pinned copies. | **Closed 2026-08-31 by M1** (`8bf37408`). The nine files went, `work_item` and `work_item_link` with them (`work_item_link` → `issue_link`); `BoardFacts`, `BoardService`, `BoardPeople`, `ItemAssignedEvent` and `derived_item_annotation` stayed and `WorkItemController` became `BoardController`. The **path** `/work-items` is gone and the composite list is `GET /board` — 011 D-a, because `GET /work-items` was never work-item CRUD, it was the one operation serving all three boards, and deleting it literally would have deleted My Work. `POST` and `PATCH /work-items` are gone outright. The "six `work-item` verbs in `packages/cli` and `packages/mcp`" was **not a count of anything in the tree** (011 D-d): the CLI had none, MCP had one tool with two arms, plus one read inside the orient/quarantine tool and `WORK_ITEM_SHAPE` in the client — four sites, all replaced. `workitem:manage` is struck through in the roles spec, `permission-matrix-summary.md` and `README.md` together; its `role_permission` rows are **not deleted**, because a permission with no reader is inert and deleting seeded catalogue rows is a statement whose only effect is to make `SeedDataTest` disagree with three pinned documents (011 D-i). |
| R5 | M1 seeds the workflow "per project" but `project` cannot import `tracker`, and no project-created event exists. | **Closed 2026-08-31 by M1 (`8bf37408`) — RESOLVED DIFFERENTLY FROM THE PROPOSAL, and the reason matters more than the outcome.** The proposal was `V3` backfill **plus a new `ProjectCreatedEvent`** that `project` publishes and `tracker` consumes. **No event was built, and none should be** (011 D-b). Two facts, either of which is sufficient: (1) `CompanyScopedEvent`'s own header says *"an event **type** is a dependency like any other, so the publishing module's class is unreachable from the consuming one"* — cross-module delivery is an `OutboxEvents` string plus a JSONB payload claimed by an `OutboxHandler`, so `ProjectCreatedEvent` **as a shared class cannot exist**; (2) outbox delivery is asynchronous with a **one-minute floor** (`tcms.outbox.interval: PT1M`, plus `OutboxScheduler`'s `PT1M` initial delay), so a workflow seeded that way leaves a brand-new project with no statuses — and therefore unable to create its first issue — for up to a minute, making story B1's *"a sensible default is already there so I can start without configuring anything"* false for exactly that long. `tcms_seed_project_defaults` is the mechanism that already solves this shape and the precedent is not thin: it seeds `configuration` (an `execution` table) and `doc_space` (a `documentation` table) synchronously, in the creating transaction, and it is called by all three project-creation paths. **So no `project.created` outbox name was minted either** — an event with no work is a seam that grows a caller. The cost, accepted: `V3` reproduces the whole function again under `CREATE OR REPLACE`, and a block omitted silently stops being seeded; `ProjectKeySequenceSchemaTest` is the check. **Do not re-propose the event.** |
| R6 | M3: `ReleaseSortKey` lives in `execution`, so `tracker` importing it is a forbidden crossing; `test_run.release_id` as a hard FK looked like it broke §3. | **Resolved** — `ReleaseSortKey` moves to `api`; the FK is kept, with the reason. |
| R7 | M4's "existing Jira/ADO adapters gain an importer direction" — they are outbound single-issue clients, and an importer in `integration` writing `issue` is a forbidden crossing. | **Resolved** — importer is `tracker.imports` with its own read client; adapters untouched. |
| R8 | M4's dependency on M1 alone held only if Jira sprints are not turned into labels (M2). | **Resolved** — sprints dropped and reported; label-per-sprint optional if M2 already exists. |
| R9 | M5's fetch payload included `release`, which is M3. | **Resolved** — nullable from the first contract. |
| R10 | M6 "roll up … via `issue_fact`" read as `time` writing `issue_fact` — the trap §3 forbids. | **Resolved, and built (M6, 2026-09-04)** — the projection is `issue_time_rollup` and `time.RollupService` is its only writer. The crossing is one file, `time.IssueFacts`, read-only native SQL, and **`TimeWritesNoIssueFactTest` asserts both halves**: that nothing in `com.tcms.time` writes the table, and that no second file in the module names it. It strips comments before it reads, because `time/package-info.java` argues for the rule in prose and names the table repeatedly. |
| R11 | M7 depended on M6 for nothing it reads. | **Closed 2026-09-04 with M7** — M7 depended on M0; utilisation stayed out unless M6 landed. M6 landed first, so utilisation was **in** and shipped as `approved hours ÷ allocated capacity` (017 D-i), reading `time` through `staffing.TimeFacts`. The two capacity figures M6 left absent (016 D-j) — the fourth KPI tile on `/timesheets` and the Capacity half of My Work's This-period panel — were **both completed** by 017 T6, with D-j's no-profile em dash preserved. |
| R12 | M9 "needs no migration" was only true if M1/M3 stored timestamps `issue_fact` was not sketched with. | **Resolved** — four timestamps on `issue_fact` in M1, `release.created_at` in M3. |
| R13 | M2 did not say what happens to a workflow status added after a board exists. | **Resolved, and built (M2, 2026-09-01)** — auto-claimed by the same-`kind` default column; unique index, not a CHECK. The claim is a **direct call** from `WorkflowService` into `BoardConfigService.claimNewStatus`, same module and same transaction, not an event (012 D-g): an unclaimed status is not a state the data can be in, rather than one it is briefly in. `settings-boards` shows the claim where it landed and lets it be moved. **Unverified end to end** — the screen's test seeds the claim by hand; nothing has driven the workflow `PUT` and read the board back. |
| R14 | M4: whether the importer may reuse `integration.tracker`'s per-person credential. | **Closed (014 D-a, 2026-09-02)** — neither of the two the review named. The connection is entered per run, carried in the request body, held for the life of the run and **written nowhere**: `import_run` has no secret column at all. Reuse was refused because it does not compile — `tracker`'s `allowedDependencies` does not name `integration`, and `ArchitectureRulesTest` is a hard gate with no exception list. Encrypting it onto `import_run`, which is the option this plan expected to be taken, was refused because `SecretCipher` is in `integration` too: taking it meant a **second AES-GCM key user**, which is the rotation defect that class's own header names. It costs re-typing the token after a browser reload, and `import-connect.html` already promised exactly this under the field. |
| R15 | The migration numbers ~~`V8 / V9 / V10`~~ → `V11 / V10 / V12` for M6–M8 are wrong the moment a second lane lands anything out of table order. | **Open by design, and it has now bitten FOUR times with no second lane at all** — M5 placed ahead of M4 (2026-08-30); M1 spending two versions rather than one (2026-09-01); M4 begun before M5, so `V7` was free when the table had spent it (2026-09-01); and **M6 taking `V9` rather than the reserved `V11` (2026-09-04), because neither Mc nor M7 — both ahead of it in the confirmed order — had started.** Four causes, none of them a second lane. The reservation warning covers it; the parallel section restates it because that is where it bites hardest. **Closed 2026-09-06 by M8, the last module this row reserved a number for** — and closed by the *practice* rather than by the arithmetic ever coming right: Mc took `V11` and M8 took `V12`, both re-derived from `ls` on the day, and every module plan since 016 has made that `ls` its first command. The three reservations here ended `V9 / V10 / V12`, two of three wrong. **Nothing after M9 may read a `V` from a table; read the directory.** |

---

## What this plan does not claim

- **Not that the modules are equal in size.** M2 is the hardest and probably the longest; M0 and M9
  are the shortest. The cut is by *what a person can do*, not by effort.
- **Not that the estimates are here.** There are none in this document on purpose — a module's own
  plan sizes it once its tasks exist, and a number written now would be a guess dressed as a plan.
- **Not that §9 was wrong.** It optimised for cheap schema; this optimises for a working product at
  every boundary. Both are defensible, and §9's reasoning is preserved rather than deleted so the
  next reader can see it was considered.
- **Not that the mockups are the design.** They are a reference on the right tokens. A screen is
  finished when it is on the kit, wired to real endpoints, and handles the loading / loaded / failed
  / empty / stale matrix through `DataRegion` — which no static render exercises.
