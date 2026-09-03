# Time — Implementation Plan (009 module M6)

**Date:** 2026-09-04 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** A person logs hours against an issue, a meeting and leave; submits a period; a PM approves
or rejects it with a note; approved hours appear on the story, the epic and the project. A rejected
period is editable again and a submitted one is not.

**Architecture:** A new Spring Modulith module `com.tcms.time`, whose `allowedDependencies` are the
five foundation modules and nothing else. It owns three tables — `time_entry`, `timesheet_period`
and `issue_time_rollup` — and reads `issue_fact` through exactly one class, `time.IssueFacts`, in
the `work.BoardFacts` idiom. **`issue_fact` is read and never written** (009 review R10, scope §3):
its one writer is `tracker.IssueFactProjection`, and a second writer is the failure the scope
document names by name. The rollup is maintained synchronously inside the approve/log transaction
because both sides of it belong to this module — there is no boundary to cross and therefore no
event (D-h below).

**Tech Stack:** Postgres 17 + Flyway (`V9`), Spring Boot 3.5.6 / Java 21, `openapi.yaml` + the two
generated clients, `@tcms/cli` + `@tcms/mcp`, Vite 8 / React 19 / TanStack Query / Tailwind v4 +
the render kit.

**Sources.** 009 plan §"M6 · M7 · M8" and §"What every module carries";
`docs/specs/2026-08-30-project-management-expansion-scope.md` §3, §6, §7;
stories T1–T3 in `docs/specs/2026-08-30-project-management-expansion-user-stories.md`;
mockups `timesheets.html`, `timesheet-approvals.html`, and the time figures on `story.html`,
`epic.html`, `work-table.html`, `my-work.html`, `settings-general.html`.

---

## Prompts — one per task

One task is one session, so each block below is what is pasted into a fresh session to run that
task, complete on its own. **They are living text**: a task that lands something different from what
this plan predicted rewrites the prompts below it before it stops — in place, no changelog appended.

Every block assumes the same two things and does not repeat them: read `CLAUDE.md` first, and run
the gates on `shanks` through `~/bin/tcms-remote` after reading
`docs/specs/shanks-runner-details.md` — `BUILD FAILED` with `Could not write XML test results` and
**zero** assertion failures is the fd limit, not a red suite.

### Prompt — T1 — The time contract, both clients, and the CLI/MCP verbs

````text
You are working in the tcms repo. Read CLAUDE.md first; the plugin skills it names are mandatory —
this task is the `ts` stack, so /tooling-engineer with tool-cli-builder and tool-mcp-builder.
Run task T1 of docs/plans/016-time.md.

Read, in this order: 016's header, its "Decisions this plan takes" section (all ten — D-b, D-d and
D-e decide what is in this contract and what is deliberately not), and its T1 block, which carries
the schemas and the paths in full. Then the neighbourhood you are editing: docs/specs/openapi.yaml
around the `tracker` tag at line 45 and the `/issues/{key}/doc-links` block, and both
contractBodies.ts copies. The mockups timesheets.html and timesheet-approvals.html are the source of
truth for what a screen needs from this contract; T1's schema block is what to type.

Depends on nothing. Nothing else may be in progress beside it — work in this repo is sequential.

Traps this task specifically will hit:
- **`required` does not descend.** Every schema gets its `required` array INCLUDING the inline
  nested objects — TimeEntry.subject, EpicTimeRollup.children[], TimesheetQueueEntry.projectTotals[].
  Without it every generated property is optional and both clients go soft.
- **There are TWO generated clients and they are not the same command.** `cd frontend && npm run
  gen:api` AND `cd packages/client && npm run gen:api`. Regenerate both or contractPairing.test.ts
  reddens on a pair that has drifted.
- **The marked region of contractBodies.ts is byte-identical across both copies.** Edit one, edit
  both, and paste rather than retype.
- **The CLI and MCP each have their own reads.ts/writes.ts.** Five verbs mirrored into both;
  writeConformance.test.ts in each package compares the write verbs against openapi.yaml and is the
  check that a mirror drifted.
- **The verbs 404 until T3.** That is the contract-first cost, and it is a real 404 from a path the
  server does not serve. Do NOT add a stub returning a fake success.
- **No verb on this surface returns a rate or a cost**, and the surface doc must say so — that is
  M8's, gated on billing:* and ratecard:read.

Gate: ~/bin/tcms-remote sync, then
  packages "npm run build && npm run lint --workspaces && npm run test:run --workspaces"
  frontend "npm run build"

Commit (one commit, `git add <paths>` only — never -A): openapi.yaml, the MCP surface doc, both
schema files, both contractBodies.ts, and the four cli/mcp source files. Message:
`feat(time): the time contract, both clients, and the CLI/MCP verbs`.

Done when: contractPairing.test.ts and both writeConformance.test.ts are green, and `node
cli/dist/main.js help` lists the five time verbs.
````

### Prompt — T2 — `V9`: `time_entry`, `timesheet_period`, `issue_time_rollup`

````text
You are working in the tcms repo. Read CLAUDE.md first; the plugin skills it names are mandatory —
this task is the database, so /data-architect with data-postgresql and data-migrations.
Run task T2 of docs/plans/016-time.md.

Read, in this order: 016's "The two numbers this plan takes" section, its decisions D-a, D-b, D-c,
D-g and D-h (they are why each table and each CHECK is shaped the way it is), and its T2 block,
which carries the migration in full. Then, as patterns:
backend/src/main/resources/db/migration/V8__agent.sql (the RLS DO-block and the "seed function
deliberately not touched" section) and V3__tracker_core.sql §5 (what a seed-function change looks
like when one IS needed). Then docs/specs/tenancy-invariants.md and the ERD's §0.1 company_id rule.

Depends on: T1.

Traps this task specifically will hit:
- **`ls backend/src/main/resources/db/migration/` BEFORE writing the file, every time.** The plan
  says V9 and the directory is the authority. `out-of-order` is unset in application.yml, so a
  reused number is SILENTLY not applied, and Testcontainers builds every test database from empty
  and can never show you the conflict. If anything above V8 has landed since, take the next free
  number and correct 009's table to it.
- **`time_entry.subject_id` carries NO FOREIGN KEY, and the reason goes in the comments.** That is
  the module rule (scope §3), not an omission — `tracker` is not a foundation module and never will
  be. State the cost too: a deleted issue can orphan an entry.
- **FORCE ROW LEVEL SECURITY, not merely ENABLE**, and the predicate is byte-identical to V1's.
  SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares the text and fails equally
  on an explicit WITH CHECK — a USING-only policy reuses USING as the insert check.
- **`company_id` leads every composite index.** SchemaConventionTest computes this; do not read the
  file and conclude it holds.
- **Do NOT restate tcms_seed_project_defaults.** Nothing here is a per-project default row —
  track_time is a column DEFAULT. Reproducing that function is the only way this file could break
  it, since CREATE OR REPLACE replaces the whole body, and an omitted block silently stops being
  seeded (ProjectKeySequenceSchemaTest is the check that one went missing).
- **No GRANT statements.** The two-role split grants tcms_app everything through
  ALTER DEFAULT PRIVILEGES, set up before Flyway runs.

Gate: ~/bin/tcms-remote sync, then
  backend "ulimit -n 65535; ./gradlew test --tests 'com.tcms.schema.*' --tests 'com.tcms.tenancy.*'"

Commit: V9__time.sql and the ERD amendment. Message:
`feat(time): V9 — time_entry, timesheet_period and the issue rollup`.

Done when: SchemaConventionTest is green, the three tables carry forced RLS, and the ERD records
subject_id as the fourth polymorphic reference in the schema with the orphan cost named.
````

### Prompt — T3 — The `time` module, the rollup, and the money-boundary negative test

````text
You are working in the tcms repo. Read CLAUDE.md first; the plugin skills it names are mandatory —
this task is the backend, so /be-java with be-java-database-code, be-java-unit-testing and
qa-permission-testing-java.
Run task T3 of docs/plans/016-time.md. This is the largest task in the plan.

Read, in this order: 016's decisions D-c, D-d, D-e, D-f, D-h and D-i (D-i decides who may approve a
period and is the one a reader will get wrong), then its T3 block, which carries the package-info,
the tests and the crossing class in full. Then, as patterns:
backend/src/main/java/com/tcms/work/BoardFacts.java (the read-a-foreign-table idiom this task's
IssueFacts is the fourth instance of), com/tcms/tracker/package-info.java (the module declaration
and the issue_fact trap stated from the other side), and any tracker service for the
permission-check and CompanyContext shape. Then V2__pm_foundation.sql's role grants — the comment
above the Test Lead INSERT flags D-f for this module by name.

Depends on: T2 (the tables) and T1 (the contract these controllers must match — do not invent a
response shape, read openapi.yaml).

Traps this task specifically will hit:
- **`issue_fact` is READ and NEVER written** (009 review R10, scope §3). One writer exists,
  tracker.IssueFactProjection. The whole crossing lives in ONE class, time.IssueFacts, and
  TimeWritesNoIssueFactTest asserts both halves — that nothing writes it, and that exactly one file
  names it. Write that test; do not assume it.
- **Every repository interface carries `@Transactional(readOnly = true)` ON THE INTERFACE.** Spring
  Data gives a transaction only to the inherited CRUD methods, so a derived or @Query method
  otherwise runs with no company set and every RLS-protected read returns ZERO ROWS — fail-closed
  and silent, with no error to attribute it to.
- **Company context comes from the authenticated session, never from a parameter, header or path
  segment.**
- **The money boundary needs a test asserting the NEGATIVE, and it is written now even though rate
  cards arrive in M8**, so M8 inherits it. Derive the money keys from the catalogue and assert
  their absence — do not read §A3 and conclude it holds. That reading is the pass that missed five
  join tables without company_id.
- **D-f: a Test Lead cannot approve a timesheet. Assert it; do not repair it.** Widening that grant
  would widen a grant nobody decided to widen.
- **The rollup is synchronous, inside the approval transaction (D-h).** Not an outbox event — this
  module owns both sides, and the outbox has a delivery floor around a minute while the story panel
  must be right the moment Approve is clicked.
- **ArchUnit**: allowedDependencies is the five foundation modules and nothing else. `identity` is
  asked what a person is called, never whether they may.
- **A comment must not claim somebody lacks a permission §A3 grants them** — tierClaims.test.ts
  fails the packages job on it.

Gate: ~/bin/tcms-remote sync, then backend "ulimit -n 65535; ./gradlew build".

Commit: backend/src/main/java/com/tcms/time, backend/src/test/java/com/tcms/time, and the
architecture test. Message:
`feat(time): the time module, the rollup projection, and the money boundary`.

Done when: a submitted period refuses an edit with 409 and a rejected one accepts it; a rejection
without a note is refused; approving posts hours onto the issue and rolls them to the epic;
TimeMoneyBoundaryTest and TimeWritesNoIssueFactTest are green; ArchUnit is green.
````

### Prompt — T4 — Timesheets and approvals

````text
You are working in the tcms repo. Read CLAUDE.md first; the plugin skills it names are mandatory —
this task is the SPA, so /fe-react with fe-react-forms, fe-react-data-fetching and
fe-react-component-testing.
Run task T4 of docs/plans/016-time.md.

Read, in this order: 016's decisions D-b, D-d, D-i and D-j (D-j is why a KPI tile is missing and
must stay missing), then its T4 block, which carries the keys, the labels and the fault arm in full.
Then walk the mockups — docs/mockups/timesheets.html and timesheet-approvals.html — in a browser
alongside docs/mockups/index.html. **The mockups are the source of truth for what these screens
show**; the plan and the scope spec for what the system does. Then, as patterns:
frontend/src/features/tracker/ for a ported screen on the kit, frontend/src/api/queries/issues.ts
for the hook shape, and frontend/src/app/AppShell.tsx's MY_WORK block for a nav item outside the
project frame.

Depends on: T3 — the endpoints must serve real data before a screen reads them. There is no mock
API lane in this repo.

Traps this task specifically will hit:
- **Build the fault switch BEFORE the feature it proves.** devFaults.ts gets the `isTimePath` arm
  in this task, not afterwards. A resilience behaviour you cannot trigger is one you have not
  tested. Both guards stay a literal `import.meta.env.DEV` — route that through an imported
  constant and the module ships to production.
- **ONE keys factory.** Add to frontend/src/api/queries/keys.ts. Never add a second, and never
  hand-spell a prefix a root helper exists for.
- **`npm run build`, not `tsc --noEmit`.** The latter has reported 0 errors while the build failed
  with nine, because `tsc -b` uses the stricter config.
- **Never build your own QueryClient in a test** — only createTestQueryClient's has the caches. A
  render that trips an error boundary FAILS unless the test calls renderingIntoAnErrorBoundary()
  and says why.
- **Every exchange crossing the fetch double is compared against openapi.yaml.** Use
  frontend/src/test/fixtures.ts and anchor path-keyed mock arms (/^\/timesheets\/periods\/[^/]+$/)
  so a sub-resource is not caught by a prefix.
- **D-j: ship three KPI tiles, not four.** Period capacity is M7's. Do not invent a capacity figure
  — that is the two-contradictory-percentages-on-one-screen failure.
- **Keep the mockup's "What you never see here" panel.** It is the screen saying out loud what
  scope §7 calls the sharpest boundary in the product.
- **Routes go outside the project frame**, beside /my-work: a period spans projects (D-i), and
  scoping it under the project switcher would state the opposite.
- **No `sx`, no CSS-in-JS, no second stylesheet.** Tailwind utilities and the kit; new enums into
  lib/labels.ts as `satisfies Record<Union, string>`.

Gate: ~/bin/tcms-remote sync, then frontend "npm run build && npm run lint && npm run test:run".

Commit: frontend/src/features/time, the time query module, index.ts, keys.ts, labels.ts,
devFaults.ts, router.tsx, AppShell.tsx. Message: `feat(time): timesheets and the approvals queue`.

Done when: a person logs against an issue, a meeting and leave from one screen; a submitted period
renders no Add form and no edit buttons; a rejected one renders its note and an editable form; and
rejecting requires a note before the dialog will submit.
````

### Prompt — T5 — Estimate vs actual, the four additive panels, and the close-out

````text
You are working in the tcms repo. Read CLAUDE.md first; the plugin skills it names are mandatory —
this task is the SPA plus the module's documentation close-out, so /fe-react.
Run task T5 of docs/plans/016-time.md. This is the last task in the module, and the close-out rides
with it rather than taking a session of its own — this session is the only one that knows what
actually landed and what is built-but-unverified.

Read, in this order: 016's decisions D-d, D-g and D-j, then its T5 block. Then the mockups for the
five screens you are touching ADDITIVELY — story.html (the Time panel and the `Logged` detail row),
epic.html (the estimate-vs-actual table), work-table.html (the `Logged` column and footer),
my-work.html (the This-week panel) and settings-general.html (the Time panel). Then 009's
"Prompt hand-off protocol", which is what step 8 executes.

Depends on: T4.

Traps this task specifically will hit:
- **All five panels are ABSENT rather than empty** — M1 rendered none of them. Adding them is
  additive; GeneralSettingsPage needs a NEW panel, not a stub filled in.
- **No screen renders a bare "hours" (D-d).** Approved and pending are different numbers and every
  figure says which it is. epic.html's panel is labelled "approved hours only" for this reason.
- **All five surfaces are behind `track_time` (D-g).** Off hides them; it deletes nothing.
- **Do NOT build settings-general.html's Budget row.** It reads "Edit in Billing" and is M8's.
- **D-j again: My Work gets the Logged figure alone — no Capacity tile, no meter.**
- **Each panel is its own DataRegion.** A failed rollup must not take the story page with it.
- **The close-out is four separate obligations and none is optional**: 009's status table set to
  `done <date> @ <short sha>`; a `**Built and unverified:**` list under the M6 section (the gates
  prove it compiles, not that it works — a no-op theme toggle and a provider-ordering bug that
  rendered nothing but the error page both typechecked and linted clean); the V actually taken,
  with the reserved V11 STRUCK THROUGH rather than deleted; and the M7 and M8 prompts rewritten IN
  PLACE with what landed — M8 gets the endpoint paths, `issue_time_rollup` as the projection's name,
  `time.RollupService` as its only writer, TimeMoneyBoundaryTest as the test it inherits and must
  not weaken, and D-i's approval rule; M7 gets D-j's two absent capacity figures as work M7
  completes. No changelog appended under either.
- **docs/features.md is struck through and superseded, never deleted**, so the next reader can see
  the old reasoning was considered.

Gates — all three stacks, because this is the module's last task:
  ~/bin/tcms-remote sync
  backend "ulimit -n 65535; ./gradlew build"
  frontend "npm run build && npm run lint && npm run test:run"
  packages "npm run build && npm run lint --workspaces && npm run test:run --workspaces"

Commits — TWO, in this order:
1. the frontend files plus docs/features.md
2. docs/plans/009-project-management-expansion-modules.md ALONE, message `docs(plan): M6 done`

Done when: approved hours appear on the story, the epic and the work table; the Track-time toggle
saves; every gate is green; and 009's status table, its M6 section and the M7/M8 prompts are true
again.
````

---

## The two numbers this plan takes, and why they are not the ones the prompt named

**Migration `V9`, not `V10` or `V11`.** `ls backend/src/main/resources/db/migration/` on 2026-09-04
returns `V1 … V8`, `V8__agent.sql` being M5's. **Mc and M7 have not started**, so the versions 009's
table reserved for them are unspent and M6 is next. This is the fourth correction to that column and
it has the cause the column's own warning names: a module that has not run has not spent its number.
`out-of-order` is unset in `application.yml`, so a reused number is *silently* not applied and
Testcontainers builds every test database from empty and can never show the conflict — which is why
T2 re-reads the directory again immediately before writing the file.

**Plan `016`, not `018`.** `docs/plans/` runs to `015`. Same cause: `009-execution-breakdown.md`
projects `016` for Mc and `017` for M7, and neither has been taken.

**M6 is running out of the breakdown's order** — the sequence there is
`… → Mc → M7 → M6 → M8 → M9` and M6 is starting seventh rather than eighth. This is legal and costs
nothing: the status-table gate is *Depends on*, M6 depends on **M1 alone**, and M1 reads
`done 2026-08-31 @ 8bf37408`. The only consequence is the two numbers above, and Mc/M7 taking
`V10`/`V11` and `017`/`018` when they run. T5's close-out corrects 009's table for M6 and leaves the
reservations below it as reservations.

---

## Decisions this plan takes

Each is a decision with a reason, in the house style. A downstream module inherits them.

**D-a — The period is semi-monthly, and nothing configures it.** `1–15` and `16–end of month`, which
is what `timesheets.html` ("period 1–15 September 2026") and `timesheet-approvals.html`
("Period 16–31 August 2026") both show. No mockup has a control that sets a cadence, and
`settings-general.html`'s Time panel has exactly two rows, neither of which is one. A configurable
cadence would be a settings surface with no mockup, a column no screen writes and a migration for
`Mc`-shaped generality nobody asked for — YAGNI. `time.Periods` derives `(startsOn, endsOn)` from a
date arithmetically; a period **row** is created lazily by the first write into it.

**D-b — `subject_type` has five members, and `TEST_RUN` is not one of them.**
`ISSUE · MEETING · LEAVE · TRAINING · INTERNAL`, which is exactly the `Against` select on
`timesheets.html`. **This contradicts `timesheet-approvals.html`**, whose Mira Sund table carries a
row logged against *"2.4 regression run"* linking to `test-runs.html`. The two mockups disagree and
the log form wins, because it is the only place an entry is *created*: a kind the approvals table can
display but the form cannot produce is a row no person can make. Recorded rather than resolved
silently, per the plan's precedence rule. Adding `TEST_RUN` later is one line of a CHECK constraint
and one member of an enum — the polymorphic column is already the right shape for it.

**D-c — The rollup table is `issue_time_rollup`, and `time` is its only writer.** It is at the
*issue* grain; the story, epic and project figures are aggregated at read time by joining
`issue_fact` for `epic_id` / `parent_id` / `project_id` / `counts_toward_completion`. Storing epic
and project grains as well would be three tables that can disagree with each other about the same
hours. The join is through `time.IssueFacts` — one class, raw SQL, records declared in `time`,
exactly as `work.BoardFacts` reads `tracker` and `execution.CaseFacts` reads `repository`. **Nothing
else in `com.tcms.time` may name a foreign table.**

**D-d — Approved and pending hours are both carried, and every screen labels which it shows.**
`epic.html` says *"approved hours only"* on its panel; `story.html` says `14h (approved)`;
`timesheets.html`'s own KPI says `Logged this period 21h` for a period nobody has approved. So one
number is not enough and the ambiguity is what the mockups are guarding against.
`issue_time_rollup` carries `approved_hours` and `pending_hours`; the rollup resource returns both;
no screen renders a bare "hours".

**D-e — No new permission keys.** M0's `V2` already seeded `time:log`, `time:read`, `time:edit`,
`time:approve` and granted them (Project Manager and Company Admin: all four; Test Lead and Test
Engineer: `time:log`). `permissions.ts`, the roles spec §A3, `permission-matrix-summary.md` and
`README.md` already carry all four. **`matrixCopies.test.ts` needs nothing from this module**, which
is the one line of the per-module tax M6 does not pay. Submitting your own period is `time:log`, not
a fifth key: it is the same act as logging, one step later.

**D-f — Test Lead cannot approve a timesheet, and this plan does not widen the grant.** `V2`'s own
comment above the Test Lead INSERT flags exactly this for M6: *"a project staffed with a Test Lead
and no Project Manager cannot configure a board, cut a release or approve a timesheet — only Company
Admin can. That is what D3 says."* It is a consequence of D3 that was decided and recorded, not an
oversight, and repairing it here would widen a grant nobody decided to widen. T3's permission test
asserts the boundary as it stands.

**D-g — `project_settings.track_time BOOLEAN NOT NULL DEFAULT true`.** The toggle on
`settings-general.html`, shown `aria-checked="true"`. Default **true**, not false: `work-table.html`
says *"Logged hours are here because this project has time tracking on"*, and a product whose
timesheet is empty on every project until somebody finds a switch is a feature nobody discovers.
Off hides the `Logged` column, the story/epic time panels and the issue from the log form's picker;
it does not delete entries already recorded.

**D-h — The rollup is maintained synchronously, not through the outbox.** `time` owns both the
entries and the rollup, so there is no module boundary and therefore no `CompanyScopedEvent` — the
outbox exists to cross boundaries. It also has a delivery floor of about a minute, and the story
panel must be correct the moment the approver clicks Approve. The write is inside the same
transaction as the approval.

**D-i — A period is approved whole, by anyone holding `time:approve` on a project it has entries
against.** A period is per person per date range and **spans projects** — the mockup's Mira Sund
carries Acme Storefront, Acme Loyalty and a project-less Meeting in one submission — while
`time:approve` is a PROJECT-scope grant. So the queue shows a submitted period to a PM when at least
one of its entries names a project that PM may approve for, and approving it approves the whole
period, including entries against projects they do not hold. **That is the sharp edge, stated rather
than hidden**: the alternative — per-project partial approval — is a state the mockup has no way to
render (its Approve button is one button per person) and would make "a period is locked" untrue for
half a timesheet. Company Admin holds `time:approve` company-wide and is unaffected.

**D-j — Capacity is absent, not zero, until M7 lands.** `timesheets.html` shows a
`Period capacity 64h` KPI and `my-work.html` a `Capacity 32h` figure beside Logged. Capacity is
computed from `person_profile`, `allocation` and `absence` — all three are M7's tables, and M7 has
not started. **The KPI row on Timesheets ships with three tiles, not four, and My Work's This-week
panel ships with Logged alone and no meter**, because a capacity figure this module would have to
invent is the "two contradictory percentages on one screen" failure CLAUDE.md records. T5's close-out
writes the two panels into M7's prompt as work M7 completes.

---

## File structure

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V9__time.sql` | The three tables, the `project_settings` column, the forced RLS block |
| `backend/src/main/java/com/tcms/time/package-info.java` | The module declaration and why it is not a foundation module |
| `…/time/TimeEntry.java`, `TimesheetPeriod.java`, `IssueTimeRollup.java` | Entities |
| `…/time/TimeEntryRepository.java`, `TimesheetPeriodRepository.java`, `IssueTimeRollupRepository.java` | Spring Data, each `@Transactional(readOnly = true)` on the interface |
| `…/time/IssueFacts.java` | **The one class that names `issue_fact`.** Read-only SQL |
| `…/time/Periods.java` | D-a's semi-monthly arithmetic, and nothing else |
| `…/time/TimeEntryService.java` | Log, amend, delete; the editability rule |
| `…/time/TimesheetService.java` | Submit, approve, reject-with-note, withdraw; the queue |
| `…/time/RollupService.java` | Maintains `issue_time_rollup`; serves the three rollup grains |
| `…/time/TimesheetController.java`, `TimeEntryController.java`, `RollupController.java` | The endpoints |
| `…/time/*View.java`, `*Input.java` | Wire records |
| `frontend/src/features/time/` | `TimesheetPage.tsx`, `ApprovalsPage.tsx` and their components |
| `frontend/src/api/queries/time.ts` | Every time hook, behind the `index.ts` barrel |
| `packages/cli/src/reads.ts`, `writes.ts` · `packages/mcp/src/reads.ts`, `writes.ts` | The five verbs |

---

**Tasks** — in the order they are done. One stack, one agent, one session each, per CLAUDE.md.

| # | Task | Stack | Agent | Deliverable | Depends on |
|---|---|---|---|---|---|
| 1 | T1 — The time contract, both clients, and the CLI/MCP verbs | `ts` | `tooling-engineer` | `openapi.yaml` carries ten time path items — eleven operations — with `required` on every schema; both generated clients and both `contractBodies.ts` copies move with it; `time log/list/submit/approve/rollup` exist on the CLI and MCP and are in the surface doc | none |
| 2 | T2 — `V9`: `time_entry`, `timesheet_period`, `issue_time_rollup` | `be` (db) | `data-architect` | `V9__time.sql` applied; three tables with `company_id NOT NULL` and forced RLS, `project_settings.track_time`; `SchemaConventionTest` green | T1 |
| 3 | T3 — The `time` module, the rollup, and the money-boundary negative test | `be` | `be-java` | The eleven operations serve real data; a submitted period refuses edits and a rejected one accepts them; a test asserts a Test Engineer **lacks** `ratecard:read` and every `billing:*`; a test asserts `time` never writes `issue_fact`; ArchUnit green | T2 |
| 4 | T4 — Timesheets and approvals | `fe` | `fe-react` | `/timesheets` and `/timesheets/approvals` render; a person logs against an issue, a meeting and leave, submits, and a PM approves or rejects with a note | T3 |
| 5 | T5 — Estimate vs actual, the four additive panels, and the close-out | `fe` + `docs` | `fe-react` | Approved hours appear on the story, the epic, the work table and the project settings; `docs/features.md`, 009's status table and the M7/M8 prompts are closed out | T4 |

**150k budget, stated per task.** T1 ≈ 95k (`openapi.yaml` is 19k lines but only the tracker
neighbourhood is read; both `schema.ts` regenerations are tool output, not read). T2 ≈ 60k (`V8` and
`V3` as patterns, `SchemaConventionTest`). T3 ≈ 130k — the largest, and the reason T4/T5 are split
off. T4 ≈ 120k (two new screens, the kit, `keys.ts`, `labels.ts`, `devFaults.ts`). T5 ≈ 110k (five
existing screens touched additively, plus the docs close-out riding with it per 009's rule 6).

---

### T1 — The time contract, both clients, and the CLI/MCP verbs

**Deliverable:** `docs/specs/openapi.yaml` carries the ten `time` path items — eleven operations — and their schemas, every
schema with its `required` array including on inline nested objects; `frontend/src/api/schema.d.ts`
and `packages/client/src/schema.ts` are regenerated from it; the marked region of both
`contractBodies.ts` copies carries the new fixtures byte-identically; `time log`, `time list`,
`time submit`, `time approve` and `time rollup` exist on both the CLI and the MCP server and are
documented in the surface doc. One commit.
**Depends on:** none

**Stack:** `ts` · **Agent:** `tooling-engineer` · **Skills:** `tool-cli-builder`, `tool-mcp-builder`

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Modify: `frontend/src/api/schema.d.ts` (generated — `cd frontend && npm run gen:api`)
- Modify: `packages/client/src/schema.ts` (generated — `cd packages/client && npm run gen:api`)
- Modify: `frontend/src/api/contractBodies.ts`
- Modify: `packages/client/src/test/contractBodies.ts`
- Modify: `packages/cli/src/reads.ts`, `packages/cli/src/writes.ts`
- Modify: `packages/mcp/src/reads.ts`, `packages/mcp/src/writes.ts`
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

**The contract, in full.** Eleven operations over ten path items, under a new `time` tag (add `- name: time` to the `tags:`
block at `openapi.yaml:45`, after `tracker`):

| Method | Path | Permission | Purpose |
|---|---|---|---|
| `GET` | `/timesheets/periods/current` | `time:log` | My open period, its entries and its totals |
| `GET` | `/timesheets/periods` | `time:log` | My periods, newest first — the "Previous period" panel |
| `GET` | `/timesheets/periods/{periodId}` | `time:log` / `time:read` | One period with its entries |
| `POST` | `/timesheets/entries` | `time:log` | Log an entry |
| `PATCH` | `/timesheets/entries/{entryId}` | `time:log` / `time:edit` | Amend one |
| `DELETE` | `/timesheets/entries/{entryId}` | `time:log` / `time:edit` | Remove one |
| `POST` | `/timesheets/periods/{periodId}/submit` | `time:log` | Submit for approval |
| `GET` | `/timesheets/approvals` | `time:approve` | The queue for a period |
| `POST` | `/timesheets/periods/{periodId}/decision` | `time:approve` | Approve · reject-with-note · withdraw a rejection |
| `GET` | `/time/rollup/issues/{key}` | `issue:read` | Estimate vs approved vs pending for one issue |
| `GET` | `/time/rollup/epics/{key}` | `issue:read` | The epic panel: totals and a row per counted child |

There is deliberately **no** `/time/rollup/projects/{id}`: the project figure the mockups show is
`settings-general.html`'s budget row, which is M8's, and `work-table.html`'s footer total, which the
issue list already carries per row. A third grain would be a resource with one caller.

- [ ] **Step 1: Add the tag and the schemas**

In `openapi.yaml`, after the `tracker` tag at line 57 add `  - name: time`. Then, in
`components.schemas`, add — note `required` on every object including the nested `subject`:

```yaml
    TimeSubjectType:
      type: string
      description: >
        What an entry is logged against (016 D-b). Five members, and TEST_RUN is deliberately not
        one: timesheet-approvals.html displays a row against a test run, but timesheets.html — the
        only screen that CREATES an entry — cannot produce one. Adding it later is one CHECK member.
      enum: [ISSUE, MEETING, LEAVE, TRAINING, INTERNAL]

    TimesheetState:
      type: string
      description: >
        OPEN is editable by its owner. SUBMITTED is editable by nobody. APPROVED is final.
        REJECTED is editable again, and carries the note that sent it back.
      enum: [OPEN, SUBMITTED, APPROVED, REJECTED]

    TimeEntry:
      type: object
      required: [id, periodId, subjectType, spentOn, hours, editable]
      properties:
        id: { type: string, format: uuid }
        periodId: { type: string, format: uuid }
        subjectType: { $ref: '#/components/schemas/TimeSubjectType' }
        subject:
          type: object
          description: Present only when subjectType is ISSUE. Denormalised from issue_fact.
          required: [issueId, issueKey, title, typeLevel, projectId, projectName]
          properties:
            issueId: { type: string, format: uuid }
            issueKey: { type: string }
            title: { type: string }
            typeLevel: { type: string, enum: [EPIC, STORY, TASK] }
            projectId: { type: string, format: uuid }
            projectName: { type: string }
        spentOn: { type: string, format: date }
        hours: { type: number, format: double }
        note: { type: string, nullable: true }
        editable:
          type: boolean
          description: >
            False whenever the owning period is SUBMITTED or APPROVED. Served rather than derived
            on the client so one rule has one home.

    TimeEntryInput:
      type: object
      required: [subjectType, spentOn, hours]
      properties:
        subjectType: { $ref: '#/components/schemas/TimeSubjectType' }
        issueKey:
          type: string
          description: Required when subjectType is ISSUE, and refused otherwise.
        spentOn: { type: string, format: date }
        hours: { type: number, format: double, minimum: 0.25, maximum: 24 }
        note: { type: string, nullable: true }

    TimesheetPeriod:
      type: object
      required: [id, personId, personName, startsOn, endsOn, state, loggedHours, approvedHours, editable, entries]
      properties:
        id: { type: string, format: uuid }
        personId: { type: string, format: uuid }
        personName: { type: string }
        startsOn: { type: string, format: date }
        endsOn: { type: string, format: date }
        state: { $ref: '#/components/schemas/TimesheetState' }
        loggedHours: { type: number, format: double }
        approvedHours:
          type: number
          format: double
          description: Zero unless state is APPROVED. Carried separately from loggedHours per D-d.
        submittedAt: { type: string, format: date-time, nullable: true }
        decidedAt: { type: string, format: date-time, nullable: true }
        decidedByName: { type: string, nullable: true }
        decisionNote:
          type: string
          nullable: true
          description: The rejector's note, shown at the top of the unlocked period. Never cleared.
        editable: { type: boolean }
        entries:
          type: array
          items: { $ref: '#/components/schemas/TimeEntry' }

    TimesheetDecisionInput:
      type: object
      required: [decision]
      properties:
        decision: { type: string, enum: [APPROVE, REJECT, WITHDRAW_REJECTION] }
        note:
          type: string
          description: Required when decision is REJECT. A rejection is never silent (T2's AC).

    TimeRollup:
      type: object
      required: [issueKey, estimateHours, approvedHours, pendingHours]
      properties:
        issueKey: { type: string }
        estimateHours:
          type: number
          format: double
          nullable: true
          description: Read from issue_fact.estimate_hours. Null when nobody estimated.
        approvedHours: { type: number, format: double }
        pendingHours:
          type: number
          format: double
          description: Logged but not yet approved. Rendered separately, never summed into approved.

    EpicTimeRollup:
      type: object
      required: [epicKey, estimateHours, approvedHours, pendingHours, children]
      properties:
        epicKey: { type: string }
        estimateHours: { type: number, format: double }
        approvedHours: { type: number, format: double }
        pendingHours: { type: number, format: double }
        children:
          type: array
          items:
            type: object
            required: [issueKey, title, estimateHours, approvedHours, overEstimate]
            properties:
              issueKey: { type: string }
              title: { type: string }
              estimateHours: { type: number, format: double, nullable: true }
              approvedHours: { type: number, format: double }
              overEstimate:
                type: boolean
                description: approvedHours > estimateHours. The highlighted row on epic.html.

    TimesheetQueueEntry:
      type: object
      required: [period, projectTotals]
      properties:
        period: { $ref: '#/components/schemas/TimesheetPeriod' }
        projectTotals:
          type: array
          items:
            type: object
            required: [projectName, hours]
            properties:
              projectName:
                type: string
                description: The literal "—" is not used; a project-less total omits this row.
              hours: { type: number, format: double }
```

- [ ] **Step 2: Add the ten path items**

Insert after the `/issues/{key}/doc-links` block. Each carries `tags: [time]`, an `operationId`, and
the standard `401`/`403`/`404` responses this file already uses (copy the response block from
`/issues/{key}`). The bodies:

```yaml
  /timesheets/periods/current:
    get:
      tags: [time]
      operationId: getCurrentTimesheetPeriod
      summary: The caller's open period for a date, created lazily if it has no rows yet.
      parameters:
        - name: on
          in: query
          schema: { type: string, format: date }
          description: Any date inside the wanted period. Defaults to today. D-a derives the bounds.
      responses:
        '200':
          description: The period
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TimesheetPeriod' }

  /timesheets/periods:
    get:
      tags: [time]
      operationId: listTimesheetPeriods
      summary: The caller's periods, newest first.
      parameters:
        - { name: limit, in: query, schema: { type: integer, default: 6, maximum: 24 } }
      responses:
        '200':
          description: Periods
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/TimesheetPeriod' }

  /timesheets/periods/{periodId}:
    get:
      tags: [time]
      operationId: getTimesheetPeriod
      parameters:
        - { name: periodId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '200':
          description: The period
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TimesheetPeriod' }

  /timesheets/entries:
    post:
      tags: [time]
      operationId: logTime
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/TimeEntryInput' }
      responses:
        '201':
          description: The entry
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TimeEntry' }

  /timesheets/entries/{entryId}:
    patch:
      tags: [time]
      operationId: amendTimeEntry
      parameters:
        - { name: entryId, in: path, required: true, schema: { type: string, format: uuid } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/TimeEntryInput' }
      responses:
        '200':
          description: The amended entry
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TimeEntry' }
    delete:
      tags: [time]
      operationId: deleteTimeEntry
      parameters:
        - { name: entryId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '204': { description: Deleted }

  /timesheets/periods/{periodId}/submit:
    post:
      tags: [time]
      operationId: submitTimesheet
      parameters:
        - { name: periodId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '200':
          description: The submitted period, now uneditable
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TimesheetPeriod' }

  /timesheets/approvals:
    get:
      tags: [time]
      operationId: listTimesheetApprovals
      summary: Every period in the queue for a date range, per D-i.
      parameters:
        - name: on
          in: query
          schema: { type: string, format: date }
          description: Any date inside the wanted period.
      responses:
        '200':
          description: The queue
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/TimesheetQueueEntry' }

  /timesheets/periods/{periodId}/decision:
    post:
      tags: [time]
      operationId: decideTimesheet
      parameters:
        - { name: periodId, in: path, required: true, schema: { type: string, format: uuid } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/TimesheetDecisionInput' }
      responses:
        '200':
          description: The decided period
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TimesheetPeriod' }

  /time/rollup/issues/{key}:
    get:
      tags: [time]
      operationId: getIssueTimeRollup
      parameters:
        - { name: key, in: path, required: true, schema: { type: string } }
      responses:
        '200':
          description: The rollup
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TimeRollup' }

  /time/rollup/epics/{key}:
    get:
      tags: [time]
      operationId: getEpicTimeRollup
      parameters:
        - { name: key, in: path, required: true, schema: { type: string } }
      responses:
        '200':
          description: The epic rollup
          content:
            application/json:
              schema: { $ref: '#/components/schemas/EpicTimeRollup' }
```

- [ ] **Step 3: Regenerate both clients**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

Expected: `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts` both gain the eleven
operations. **Both, or the pair has drifted** — `contractPairing.test.ts` is what catches it.

- [ ] **Step 4: Add the fixtures to the marked region of both `contractBodies.ts`**

The marked region is byte-identical across the two copies. Paste the same text into both:

```ts
export const timesheetPeriodBody = {
  id: '00000000-0000-4000-8000-00000000e001',
  personId: '00000000-0000-4000-8000-00000000e002',
  personName: 'Dana Petrov',
  startsOn: '2026-09-01',
  endsOn: '2026-09-15',
  state: 'OPEN',
  loggedHours: 21,
  approvedHours: 0,
  submittedAt: null,
  decidedAt: null,
  decidedByName: null,
  decisionNote: null,
  editable: true,
  entries: [
    {
      id: '00000000-0000-4000-8000-00000000e003',
      periodId: '00000000-0000-4000-8000-00000000e001',
      subjectType: 'ISSUE',
      subject: {
        issueId: '00000000-0000-4000-8000-00000000e004',
        issueKey: 'ACME-124',
        title: 'Address validation',
        typeLevel: 'TASK',
        projectId: '00000000-0000-4000-8000-00000000e005',
        projectName: 'Acme Storefront',
      },
      spentOn: '2026-09-01',
      hours: 2,
      note: 'carrier list spike-out',
      editable: true,
    },
    {
      id: '00000000-0000-4000-8000-00000000e006',
      periodId: '00000000-0000-4000-8000-00000000e001',
      subjectType: 'MEETING',
      spentOn: '2026-09-01',
      hours: 1,
      note: '2.4 readiness review',
      editable: true,
    },
  ],
} as const;

export const timeRollupBody = {
  issueKey: 'ACME-118',
  estimateHours: 16,
  approvedHours: 14,
  pendingHours: 0,
} as const;

export const epicTimeRollupBody = {
  epicKey: 'ACME-100',
  estimateHours: 306,
  approvedHours: 241,
  pendingHours: 0,
  children: [
    { issueKey: 'ACME-112', title: 'Cart survives a sign-in', estimateHours: 8, approvedHours: 7, overEstimate: false },
    { issueKey: 'ACME-122', title: 'Card capture without a redirect', estimateHours: 16, approvedHours: 19, overEstimate: true },
  ],
} as const;
```

- [ ] **Step 5: Add the CLI verbs**

In `packages/cli/src/reads.ts`, beside `issue list` / `issue show`:

```ts
{
  name: 'time list',
  summary: 'Your timesheet for a period — entries, totals and whether it is still editable.',
  args: [],
  options: [{ name: 'on', description: 'Any date inside the period. Defaults to today.' }],
  async run(context) {
    const on = context.options.on;
    return context.api.get('/timesheets/periods/current', on ? { on } : {});
  },
},
{
  name: 'time rollup',
  summary: 'Estimate against approved and pending hours for an issue or an epic.',
  args: [{ name: 'issue', description: 'An issue key, e.g. ACME-118.' }],
  options: [{ name: 'epic', description: 'Read the epic grain instead, with a row per child.', flag: true }],
  async run(context) {
    const key = requireArg(context, 'issue');
    const grain = context.options.epic ? 'epics' : 'issues';
    return context.api.get(`/time/rollup/${grain}/${encodeURIComponent(key)}`);
  },
},
```

In `packages/cli/src/writes.ts`, following the shape of `issue comment` at line 1365:

```ts
{
  name: 'time log',
  summary: 'Log hours against an issue, a meeting, leave, training or internal work.',
  args: [],
  options: [
    { name: 'issue', description: 'An issue key. Omit it and pass --against for non-issue work.' },
    { name: 'against', description: 'MEETING | LEAVE | TRAINING | INTERNAL. Defaults to ISSUE.' },
    { name: 'hours', description: 'Decimal hours, 0.25 to 24.', required: true },
    { name: 'on', description: 'The date worked. Defaults to today.' },
    { name: 'note', description: 'Optional.' },
  ],
  write: { kind: 'create', permission: 'time:log' },
  async run(context) {
    const subjectType = (context.options.against ?? 'ISSUE').toUpperCase();
    return context.api.post('/timesheets/entries', {
      subjectType,
      issueKey: subjectType === 'ISSUE' ? requireOption(context, 'issue') : undefined,
      hours: Number(requireOption(context, 'hours')),
      spentOn: context.options.on ?? new Date().toISOString().slice(0, 10),
      note: context.options.note,
    });
  },
},
{
  name: 'time submit',
  summary: 'Submit your period for approval. It stops being editable.',
  args: [{ name: 'period', description: 'The period id, from `time list`.' }],
  write: { kind: 'state', permission: 'time:log' },
  async run(context) {
    const id = requireArg(context, 'period');
    return context.api.post(`/timesheets/periods/${encodeURIComponent(id)}/submit`, {});
  },
},
{
  name: 'time approve',
  summary: 'Approve a submitted period, or reject it with a note that goes back to its owner.',
  args: [{ name: 'period', description: 'The period id.' }],
  options: [{ name: 'reject', description: 'Reject instead of approving. Requires --note.', flag: true }],
  write: { kind: 'state', permission: 'time:approve' },
  async run(context) {
    const id = requireArg(context, 'period');
    const reject = Boolean(context.options.reject);
    return context.api.post(`/timesheets/periods/${encodeURIComponent(id)}/decision`, {
      decision: reject ? 'REJECT' : 'APPROVE',
      note: reject ? requireOption(context, 'note') : undefined,
    });
  },
},
```

Mirror all five into `packages/mcp/src/reads.ts` and `writes.ts` in that package's own tool shape —
`writeConformance.test.ts` in both packages compares the write verbs against `openapi.yaml` and is
the check that a mirrored verb drifted.

- [ ] **Step 6: Document the surface**

Add a `## Time` section to `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` listing the five
verbs, their permissions and the one thing an agent must not expect: **no verb on this surface
returns a rate or a cost**, and none ever will — that is M8's surface, gated on `billing:*`.

- [ ] **Step 7: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages "npm run build && npm run lint --workspaces && npm run test:run --workspaces"
~/bin/tcms-remote frontend "npm run build"
```

Expected: green. `contractPairing.test.ts` and both `writeConformance.test.ts` are the ones that
matter here.

```bash
git add docs/specs/openapi.yaml docs/specs/2026-08-08-mcp-tool-and-command-surface.md \
        frontend/src/api/schema.d.ts frontend/src/api/contractBodies.ts \
        packages/client/src/schema.ts packages/client/src/test/contractBodies.ts \
        packages/cli/src/reads.ts packages/cli/src/writes.ts \
        packages/mcp/src/reads.ts packages/mcp/src/writes.ts
git commit -m "feat(time): the time contract, both clients, and the CLI/MCP verbs"
```

**Note for the executor:** the CLI verbs 404 until T3 lands. That is the contract-first cost 009's
rule 5 names, and it is a real 404 from a path the server does not serve — not a stub returning a
fake success.

---

### T2 — `V9`: `time_entry`, `timesheet_period`, `issue_time_rollup`

**Deliverable:** `V9__time.sql` exists and applies; `time_entry`, `timesheet_period` and
`issue_time_rollup` each carry `company_id UUID NOT NULL` leading every composite index and a forced
RLS policy whose predicate is byte-identical to V1's; `project_settings.track_time` exists defaulting
true; `SchemaConventionTest` and `ProjectKeySequenceSchemaTest` are green; the ERD records the
polymorphic subject and why it has no FK.
**Depends on:** T1 (nothing structural — the order is 009's "contract and schema first")

**Stack:** `be` (db) · **Agent:** `data-architect` · **Skills:** `data-postgresql`, `data-migrations`

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__time.sql`
- Modify: `docs/specs/2026-07-30-tcms-erd.md`

- [ ] **Step 1: Re-read the directory and confirm the number**

```bash
ls backend/src/main/resources/db/migration/
```

Expected: `V1 … V8`. **If anything above `V8` is there, a lane landed since this plan was written —
take the next free number and correct 009's table to it.** `out-of-order` is unset, a reused number
is silently skipped, and Testcontainers cannot show you the conflict.

- [ ] **Step 2: Write the migration**

```sql
-- =============================================================================
-- V9 — time (plan 009 module M6, plan 016)
--
-- THE VERSION IS V9, AND THE DIRECTORY IS WHAT SAYS SO. 009's table reserved V8,
-- then V10, then V11 for this module; all three were arithmetic over an assumed
-- landing order. Re-read on 2026-09-04: V1..V8, V8 being M5's. Mc and M7 have
-- NOT started, so the numbers reserved for them are unspent and this is V9.
-- `out-of-order` is unset in application.yml, so a reused number is SILENTLY not
-- applied, and Testcontainers builds every test database from empty and cannot
-- show the conflict.
--
-- Three tables and one column. NO rate, NO cost, NO invoice: that is M8, and the
-- boundary is the sharpest one in the expansion (scope §7).
-- =============================================================================

-- 1. TIMESHEET_PERIOD — one per person per half-month (016 D-a).
--
-- The cadence is 1-15 and 16-end-of-month, and it is NOT configurable: no mockup
-- has a control that sets one, and a column no screen writes is a migration for
-- generality nobody asked for. time.Periods derives the bounds arithmetically;
-- a row here is created lazily by the first write into the period.
--
-- The state machine is OPEN -> SUBMITTED -> APPROVED | REJECTED, and REJECTED
-- returns to editable WITHOUT returning to OPEN, so the note that sent it back
-- survives on the row and is shown at the top of the unlocked period. A
-- rejection is never silent and never a deletion (timesheet-approvals.html).
CREATE TABLE timesheet_period (
    id            UUID        PRIMARY KEY,
    company_id    UUID        NOT NULL REFERENCES company(id),
    person_id     UUID        NOT NULL REFERENCES app_user(id),
    starts_on     DATE        NOT NULL,
    ends_on       DATE        NOT NULL,
    state         TEXT        NOT NULL DEFAULT 'OPEN',
    submitted_at  TIMESTAMPTZ,
    decided_at    TIMESTAMPTZ,
    decided_by    UUID        REFERENCES app_user(id),
    decision_note TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    lock_version  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_timesheet_period UNIQUE (company_id, person_id, starts_on),
    CONSTRAINT ck_timesheet_period_state
        CHECK (state IN ('OPEN','SUBMITTED','APPROVED','REJECTED')),
    CONSTRAINT ck_timesheet_period_bounds CHECK (ends_on >= starts_on),
    CONSTRAINT ck_timesheet_period_note
        CHECK (state <> 'REJECTED' OR decision_note IS NOT NULL)
);
COMMENT ON TABLE timesheet_period IS
    'One person''s half-month of time (016 D-a). SPANS PROJECTS deliberately — a period is a '
    'person''s fortnight, not a project''s, which is why 016 D-i has to say who may approve one.';
COMMENT ON COLUMN timesheet_period.decision_note IS
    'The rejector''s note. NOT cleared when the period is re-submitted: the engineer needs to see '
    'what they were asked to fix while they fix it, and the history of why a fortnight moved twice '
    'is worth more than a tidy row. ck_timesheet_period_note makes a silent rejection impossible.';
CREATE INDEX ix_timesheet_period_person
    ON timesheet_period (company_id, person_id, starts_on DESC);
CREATE INDEX ix_timesheet_period_queue
    ON timesheet_period (company_id, starts_on, state);

-- 2. TIME_ENTRY — the polymorphic one (scope §3).
--
-- subject_type + subject_id, and DELIBERATELY NO FOREIGN KEY on subject_id.
-- ArchUnit permits a cross-module dependency only toward the five foundation
-- modules, and `tracker` is not one and will never be one: it holds the largest
-- domain concept in the product and fails both clauses of the bar. So a module
-- downstream of an issue references it polymorphically and keeps its own
-- projection. work_item_link, doc_link and case_doc_link already pay exactly
-- this price.
--
-- WHAT IT COSTS, STATED PLAINLY: referential integrity between an entry and its
-- issue is enforced in application code, not here. A deleted issue can orphan an
-- entry. The mitigation is soft delete (issue_fact.deleted_at mirrors
-- issue.deleted_at) plus the nightly consistency check.
--
-- project_id, by contrast, IS a real FK: `project` is a foundation module, the
-- approvals queue groups by it (016 D-i) and the work table totals by it.
CREATE TABLE time_entry (
    id           UUID          PRIMARY KEY,
    company_id   UUID          NOT NULL REFERENCES company(id),
    period_id    UUID          NOT NULL REFERENCES timesheet_period(id) ON DELETE CASCADE,
    person_id    UUID          NOT NULL REFERENCES app_user(id),
    subject_type TEXT          NOT NULL,
    subject_id   UUID,
    project_id   UUID          REFERENCES project(id),
    spent_on     DATE          NOT NULL,
    hours        NUMERIC(5,2)  NOT NULL,
    note         TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_time_entry_subject_type
        CHECK (subject_type IN ('ISSUE','MEETING','LEAVE','TRAINING','INTERNAL')),
    CONSTRAINT ck_time_entry_subject_shape CHECK (
        (subject_type =  'ISSUE' AND subject_id IS NOT NULL AND project_id IS NOT NULL) OR
        (subject_type <> 'ISSUE' AND subject_id IS     NULL AND project_id IS     NULL)),
    CONSTRAINT ck_time_entry_hours CHECK (hours > 0 AND hours <= 24)
);
COMMENT ON TABLE time_entry IS
    'Hours, never points (009 D8) — the same unit a budget and an invoice use, which is the whole '
    'reason an estimate is expressible here. NO RATE COLUMN AND NO COST COLUMN, in this table or '
    'any other this module owns: what an hour is worth is M8''s, gated on billing:* and '
    'ratecard:read, and a Test Engineer with time:log must be unable to reach it.';
COMMENT ON COLUMN time_entry.subject_type IS
    'Five members (016 D-b). TEST_RUN is deliberately not one: timesheet-approvals.html displays a '
    'row logged against a test run, but timesheets.html — the only screen that CREATES an entry — '
    'cannot produce one, and a kind no person can make is a kind that does not exist. The two '
    'mockups disagree and the form wins. Adding it later is one member of this CHECK.';
COMMENT ON COLUMN time_entry.subject_id IS
    'NO FOREIGN KEY, and that is the module rule rather than an omission — see the table''s block '
    'comment above. NULL for every non-ISSUE kind, enforced by ck_time_entry_subject_shape.';
CREATE INDEX ix_time_entry_period ON time_entry (company_id, period_id, spent_on);
CREATE INDEX ix_time_entry_subject ON time_entry (company_id, subject_type, subject_id);
CREATE INDEX ix_time_entry_project ON time_entry (company_id, project_id, spent_on);

-- 3. ISSUE_TIME_ROLLUP — this module's own projection, at the ISSUE grain.
--
-- ONE WRITER, AND IT IS time.RollupService. Note which table this is NOT:
-- issue_fact has exactly one writer, tracker.IssueFactProjection, and a
-- downstream module writing THERE is the failure scope §3 names by name (009
-- review R10). This module READS issue_fact — through one class,
-- time.IssueFacts — and writes only here.
--
-- The story, epic and project figures are aggregated at READ time by joining
-- issue_fact for epic_id / parent_id / project_id. Three stored grains would be
-- three tables that can disagree about the same hours.
--
-- Approved and pending are separate columns (016 D-d) because epic.html says
-- "approved hours only", story.html says "14h (approved)" and timesheets.html's
-- own KPI counts hours nobody has approved. One number could not say all three.
CREATE TABLE issue_time_rollup (
    issue_id       UUID          PRIMARY KEY,
    company_id     UUID          NOT NULL REFERENCES company(id),
    project_id     UUID          NOT NULL REFERENCES project(id),
    approved_hours NUMERIC(10,2) NOT NULL DEFAULT 0,
    pending_hours  NUMERIC(10,2) NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_issue_time_rollup_nonneg
        CHECK (approved_hours >= 0 AND pending_hours >= 0)
);
COMMENT ON TABLE issue_time_rollup IS
    'time''s own projection of hours per issue. ONE WRITER — time.RollupService. Maintained '
    'SYNCHRONOUSLY inside the log/approve transaction rather than through the outbox (016 D-h): '
    'this module owns both sides, so there is no boundary to cross, and the outbox has a delivery '
    'floor around a minute while the story panel must be right the moment Approve is clicked.';
CREATE INDEX ix_issue_time_rollup_project ON issue_time_rollup (company_id, project_id);

-- 4. PROJECT_SETTINGS — the time-tracking toggle (016 D-g).
--
-- DEFAULT TRUE, not false. work-table.html says "Logged hours are here because
-- this project has time tracking on", and a product whose timesheet is empty on
-- every project until somebody finds a switch is a feature nobody discovers.
-- Turning it off hides the Logged column, the story and epic time panels and the
-- project's issues in the log form's picker; it deletes nothing already recorded.
ALTER TABLE project_settings
    ADD COLUMN track_time BOOLEAN NOT NULL DEFAULT true;
COMMENT ON COLUMN project_settings.track_time IS
    '016 D-g — "Track time on this project" (settings-general.html). ON by default. Off hides the '
    'surfaces; it never deletes an entry, because hours somebody worked are not a display setting.';

-- 5. ROW LEVEL SECURITY — three more forced policies, predicate byte-identical
--    to V1 (SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate
--    compares it, and fails equally on an explicit WITH CHECK — a USING-only
--    policy reuses USING as the insert check, so the read boundary IS the write
--    boundary).
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to a table's OWNER too, and
-- tcms_owner is what Flyway connects as.
--
-- project_settings already carries its V1 policy; a column added to a protected
-- table is protected by it.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['timesheet_period','time_entry','issue_time_rollup'];
BEGIN
    FOREACH t IN ARRAY protected LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_name = t) THEN
            RAISE EXCEPTION 'the RLS list names a table that does not exist: %', t;
        END IF;
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY company_isolation ON %I USING '
            '(company_id = nullif(current_setting(''app.current_company'', true), '''')::uuid)', t);
    END LOOP;

    IF array_length(protected, 1) <> 3 THEN
        RAISE EXCEPTION 'expected 3 new company-isolated tables, found %', array_length(protected, 1);
    END IF;
END $$;

-- 6. tcms_seed_project_defaults IS DELIBERATELY NOT TOUCHED.
--
-- Nothing here is a per-project default row. track_time is a DEFAULT on a
-- column of a table the project already gets, and a project with no time
-- entries and no period is the correct empty state. The function is not
-- restated "to be safe" — reproducing it is the only way this file could break
-- it, since CREATE OR REPLACE FUNCTION replaces the whole body (V3's §5, V8's
-- §5), and a block omitted from a reproduction silently stops being seeded.
--
-- The app role (tcms_app) needs no explicit GRANT: the two-role split grants it
-- SELECT/INSERT/UPDATE/DELETE on every new table in schema public through
-- ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner, set up before Flyway runs
-- (deploy/postgres/initdb/10-tcms-roles.sql).
```

- [ ] **Step 3: Run the schema gate**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew test --tests 'com.tcms.schema.*' --tests 'com.tcms.tenancy.*'"
```

Expected: green. `SchemaConventionTest` checks `company_id NOT NULL`, that it leads every composite
index, and that every policy carries the exact isolation predicate.

- [ ] **Step 4: Amend the ERD**

Add a `### Time (016)` section to `docs/specs/2026-07-30-tcms-erd.md` describing the three tables,
and state under §0.1's `company_id` rule that `time_entry.subject_id` is the fourth polymorphic
reference in the schema — after `work_item_link.target_id`, `doc_link.source_id` and `review`'s
subject triple — carrying no FK for the module-boundary reason, with the orphan cost named.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V9__time.sql docs/specs/2026-07-30-tcms-erd.md
git commit -m "feat(time): V9 — time_entry, timesheet_period and the issue rollup"
```

---

### T3 — The `time` module, the rollup, and the money-boundary negative test

**Deliverable:** All eleven operations from T1 serve real data. A person logs against an issue, a
meeting and leave; submits; a holder of `time:approve` approves or rejects with a note; a rejected
period accepts edits again and a submitted one refuses them with `409`. A test asserts a Test
Engineer **lacks** `ratecard:read` and every `billing:*` key. A test asserts no code path in
`com.tcms.time` writes `issue_fact`. `./gradlew build` green, ArchUnit included.
**Depends on:** T2 (the tables), T1 (the contract these controllers must match)

**Stack:** `be` · **Agent:** `be-java` · **Skills:** `be-java-database-code`,
`be-java-unit-testing`, `qa-permission-testing-java`

**Files:**
- Create: `backend/src/main/java/com/tcms/time/` (the classes in the file-structure table above)
- Create: `backend/src/test/java/com/tcms/time/`
- Modify: `backend/src/test/java/com/tcms/architecture/ArchitectureRules.java` (register the module)

- [ ] **Step 1: Declare the module**

`backend/src/main/java/com/tcms/time/package-info.java`:

```java
/**
 * Time — hours logged against an issue, a meeting or leave, and the periods they are approved in
 * (009 module M6, plan 016).
 *
 * <h2>What this module may not do, stated first because it is the failure the scope names</h2>
 *
 * <p>It <strong>reads {@code issue_fact} and never writes it</strong> (009 review R10, scope §3).
 * That table has exactly one writer, {@code tracker.IssueFactProjection}, fed by the
 * {@code tracker.issues_changed} outbox event. The moment a downstream module writes it,
 * {@code tracker} is no longer the authority on issue state and the two disagree <em>silently</em>
 * — no error, no empty screen, just two numbers that stop matching.
 *
 * <p>The whole crossing lives in {@link com.tcms.time.IssueFacts}, which issues read-only SQL
 * naming {@code issue_fact} and returns records declared here. <strong>Nothing else in this package
 * may name a foreign table.</strong> Third instance of a pattern with a name in this codebase:
 * {@code work.BoardFacts} reads {@code tracker}, {@code execution.CaseFacts} reads
 * {@code repository}.
 *
 * <h2>The other thing it may not do</h2>
 *
 * <p><strong>No rate, no cost, no invoice value — not in a table, a record, a response or a log
 * line.</strong> Scope §7 calls money the sharpest boundary in the expansion: {@code billing:*} and
 * {@code ratecard:read} are Finance and Company Admin only, and a Test Engineer with
 * {@code time:log} logs hours and cannot see what they cost. {@code TimeMoneyBoundaryTest} asserts
 * the negative rather than reading the matrix and concluding it holds, and it is written here,
 * before M8 exists, so M8 inherits it.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>The five foundation modules. {@code project} carries the isolation boundary and the visibility
 * gate ({@link com.tcms.project.ProjectService#readVisible}); {@code identity} is asked <em>what is
 * this person called</em> and never <em>may they</em>, as in {@code repository}, {@code execution}
 * and {@code work}.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "identity", "project", "storage"})
package com.tcms.time;
```

- [ ] **Step 2: Write `Periods` and its test first (TDD)**

`backend/src/test/java/com/tcms/time/PeriodsTest.java`:

```java
class PeriodsTest {
    @Test
    void firstHalfOfTheMonthRunsToTheFifteenth() {
        var p = Periods.containing(LocalDate.of(2026, 9, 7));
        assertThat(p.startsOn()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(p.endsOn()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    void secondHalfRunsToTheLastDayOfTheMonth() {
        var p = Periods.containing(LocalDate.of(2026, 2, 20));
        assertThat(p.startsOn()).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(p.endsOn()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void theFifteenthIsInTheFirstHalfAndTheSixteenthInTheSecond() {
        assertThat(Periods.containing(LocalDate.of(2026, 9, 15)).startsOn().getDayOfMonth()).isEqualTo(1);
        assertThat(Periods.containing(LocalDate.of(2026, 9, 16)).startsOn().getDayOfMonth()).isEqualTo(16);
    }
}
```

Run it, watch it fail, then write:

```java
/** 016 D-a — the half-month cadence, derived rather than configured. */
record PeriodBounds(LocalDate startsOn, LocalDate endsOn) {}

final class Periods {
    static PeriodBounds containing(LocalDate date) {
        return date.getDayOfMonth() <= 15
                ? new PeriodBounds(date.withDayOfMonth(1), date.withDayOfMonth(15))
                : new PeriodBounds(date.withDayOfMonth(16), date.withDayOfMonth(date.lengthOfMonth()));
    }
    private Periods() {}
}
```

- [ ] **Step 3: The entities and repositories**

Standard JPA, `lock_version` mapped as `@Version`. **Every repository interface carries
`@Transactional(readOnly = true)` on the interface, not only on methods** — Spring Data gives a
transaction only to the inherited CRUD methods, so a derived or `@Query` method otherwise runs with
no company set and every RLS-protected read returns zero rows, fail-closed and silent.

```java
@Repository
@Transactional(readOnly = true)
public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID> {
    List<TimeEntry> findByPeriodIdOrderBySpentOnDesc(UUID periodId);

    @Query("select coalesce(sum(e.hours), 0) from TimeEntry e where e.periodId = :periodId")
    BigDecimal totalHours(@Param("periodId") UUID periodId);
}
```

- [ ] **Step 4: `IssueFacts` — the one crossing**

```java
/**
 * <strong>The one place in this module that names {@code issue_fact}.</strong>
 *
 * <p>Read-only, and that is the module's central rule rather than a property of this class: the
 * table has one writer, {@code tracker.IssueFactProjection}. See {@code package-info}.
 */
@Component
class IssueFacts {
    private final EntityManager em;

    IssueFacts(EntityManager em) { this.em = em; }

    /** The subject of an entry, resolved from a key. Empty when the key names nothing visible. */
    Optional<IssueSubject> byKey(String issueKey) {
        var rows = em.createNativeQuery("""
                SELECT f.issue_id, f.issue_key, f.title, f.type_level, f.project_id, p.name,
                       f.estimate_hours
                  FROM issue_fact f
                  JOIN project p ON p.id = f.project_id
                 WHERE f.issue_key = :key AND f.deleted_at IS NULL
                """).setParameter("key", issueKey).getResultList();
        return rows.stream().findFirst().map(IssueFacts::toSubject);
    }

    /**
     * The counted children of an epic, for the estimate-vs-actual table.
     *
     * <p>{@code counts_toward_completion} is how a spike is excluded without importing
     * {@code tracker}, and {@code type_level} is how the epic grain is reached — both are columns
     * M1 put on the projection for exactly this caller.
     */
    List<EpicChildFact> epicChildren(UUID epicId) {
        var rows = em.createNativeQuery("""
                SELECT f.issue_key, f.title, f.estimate_hours,
                       COALESCE(r.approved_hours, 0), COALESCE(r.pending_hours, 0)
                  FROM issue_fact f
                  LEFT JOIN issue_time_rollup r ON r.issue_id = f.issue_id
                 WHERE f.epic_id = :epicId
                   AND f.counts_toward_completion
                   AND f.deleted_at IS NULL
                 ORDER BY f.issue_key
                """).setParameter("epicId", epicId).getResultList();
        return rows.stream().map(IssueFacts::toEpicChild).toList();
    }
}
```

- [ ] **Step 5: The editability rule, test first**

`backend/src/test/java/com/tcms/time/TimesheetLifecycleTest.java` — a real Spring context over
Testcontainers, the house shape:

```java
@Test
void aSubmittedPeriodRefusesAnEdit() {
    var period = given.openPeriodFor(dana);
    given.entryIn(period, 4.0);
    timesheets.submit(period.getId());

    assertThatThrownBy(() -> entries.log(new TimeEntryInput("MEETING", null, today, 1.0, null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("submitted");
}

@Test
void aRejectedPeriodIsEditableAgainAndKeepsItsNote() {
    var period = given.submittedPeriodFor(dana);
    timesheets.decide(period.getId(), new TimesheetDecisionInput("REJECT", "re-point ACME-140"));

    var reloaded = timesheets.get(period.getId());
    assertThat(reloaded.state()).isEqualTo("REJECTED");
    assertThat(reloaded.editable()).isTrue();
    assertThat(reloaded.decisionNote()).isEqualTo("re-point ACME-140");

    entries.log(new TimeEntryInput("MEETING", null, today, 1.0, null));  // does not throw
}

@Test
void aRejectionWithoutANoteIsRefused() {
    var period = given.submittedPeriodFor(dana);
    assertThatThrownBy(() ->
            timesheets.decide(period.getId(), new TimesheetDecisionInput("REJECT", null)))
            .isInstanceOf(ValidationException.class);
}
```

- [ ] **Step 6: The rollup, test first**

```java
@Test
void approvingPostsHoursOntoTheIssueAndRollsUpToTheEpic() {
    var story = given.issueFact("ACME-118", EPIC_ID, 16.0);
    var period = given.openPeriodFor(dana);
    entries.log(new TimeEntryInput("ISSUE", "ACME-118", today, 14.0, null));

    assertThat(rollups.forIssue("ACME-118").approvedHours()).isZero();
    assertThat(rollups.forIssue("ACME-118").pendingHours()).isEqualByComparingTo("14.00");

    timesheets.submit(period.getId());
    asPm(() -> timesheets.decide(period.getId(), approve()));

    var after = rollups.forIssue("ACME-118");
    assertThat(after.approvedHours()).isEqualByComparingTo("14.00");
    assertThat(after.pendingHours()).isZero();
    assertThat(after.estimateHours()).isEqualByComparingTo("16.00");

    assertThat(rollups.forEpic("ACME-100").approvedHours()).isEqualByComparingTo("14.00");
}
```

`RollupService.apply` recomputes `issue_time_rollup` for every issue an entry in the period names,
inside the approval transaction (D-h).

- [ ] **Step 7: The money-boundary negative test — write it now, M8 inherits it**

`backend/src/test/java/com/tcms/time/TimeMoneyBoundaryTest.java`:

```java
/**
 * Scope §7's sharpest boundary, asserted as a NEGATIVE.
 *
 * <p>The working agreement's rule applies literally here: <em>a catalogue-wide invariant needs a
 * computed check, not a careful reading.</em> Reading §A3 and concluding that Test Engineer lacks
 * the money keys is exactly the pass that missed five join tables without {@code company_id}. So
 * this derives the money keys from the catalogue and asserts their absence.
 *
 * <p><strong>Written before M8 exists, deliberately.</strong> When rate cards arrive, this test is
 * already standing and M8 inherits a boundary it cannot quietly widen.
 */
class TimeMoneyBoundaryTest extends AbstractIntegrationTest {

    private static final Set<String> MONEY_KEYS = Set.of(
            "ratecard:read", "ratecard:manage",
            "billing:read", "billing:budget", "billing:expense", "billing:invoice", "billing:issue");

    @Test
    void everyMoneyKeyInTheCatalogueIsCoveredByThisTest() {
        var fromCatalogue = permissions.allKeys().stream()
                .filter(k -> k.startsWith("billing:") || k.startsWith("ratecard:"))
                .collect(toSet());
        assertThat(fromCatalogue)
                .as("a money key was added to the catalogue and this test did not move with it")
                .isEqualTo(MONEY_KEYS);
    }

    @Test
    void aTestEngineerHoldsTimeLogAndNotOneMoneyKey() {
        var engineer = given.member(TEST_ENGINEER).grantedOn(project);

        assertThat(permissions.of(engineer)).contains("time:log");
        assertThat(permissions.of(engineer)).doesNotContainAnyElementsOf(MONEY_KEYS);
    }

    @Test
    void aTestEngineerLogsHoursAndSeesNoCostAnywhereInTheResponse() {
        var engineer = given.member(TEST_ENGINEER).grantedOn(project);
        as(engineer, () -> {
            entries.log(new TimeEntryInput("ISSUE", "ACME-118", today, 4.0, null));
            var body = json(timesheets.current());
            assertThat(body.toLowerCase())
                    .as("the time surface must never carry a money word")
                    .doesNotContain("rate").doesNotContain("cost")
                    .doesNotContain("amount").doesNotContain("currency");
        });
    }

    /**
     * 016 D-f. Test Lead holds time:log and NOT time:approve — a consequence of D3 that V2's own
     * comment flagged for this module. It is asserted rather than repaired: widening the grant here
     * would widen a grant nobody decided to widen.
     */
    @Test
    void aTestLeadCannotApproveATimesheet() {
        var lead = given.member(TEST_LEAD).grantedOn(project);
        var period = given.submittedPeriodFor(dana);
        as(lead, () -> assertThatThrownBy(() -> timesheets.decide(period.getId(), approve()))
                .isInstanceOf(ForbiddenException.class));
    }
}
```

- [ ] **Step 8: Assert `issue_fact` is never written**

`backend/src/test/java/com/tcms/architecture/TimeWritesNoIssueFactTest.java`:

```java
/**
 * 009 review R10, asserted rather than assumed.
 *
 * <p>Reads the module's own source text, because the defect this guards against compiles, passes
 * every functional test and produces two silently disagreeing numbers — the same class of failure
 * as the {@code ${VAR:-x}} defect, where every check inspected a resolved value and the fault was
 * in the text.
 */
@Test
void noClassInTimeWritesIssueFact() throws IOException {
    var offenders = Files.walk(Path.of("src/main/java/com/tcms/time"))
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> {
                var text = readString(p).toUpperCase(Locale.ROOT);
                return text.contains("INSERT INTO ISSUE_FACT")
                    || text.contains("UPDATE ISSUE_FACT")
                    || text.contains("DELETE FROM ISSUE_FACT");
            }).toList();

    assertThat(offenders)
            .as("issue_fact has exactly one writer, tracker.IssueFactProjection (scope §3)")
            .isEmpty();
}

/** And the read crossing lives in exactly one file. */
@Test
void onlyIssueFactsNamesTheProjection() throws IOException {
    var namers = Files.walk(Path.of("src/main/java/com/tcms/time"))
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> readString(p).contains("issue_fact"))
            .map(p -> p.getFileName().toString())
            .toList();

    assertThat(namers).containsExactly("IssueFacts.java");
}
```

**`backend/build.gradle.kts` already declares `backend/src/**` as a test input** for the cited-files
rule, so this source-text read needs no new declaration — confirm before relying on it, because a
rule that is green by cache is worse than no rule.

- [ ] **Step 9: Controllers, then the full gate**

Controllers are thin: resolve the caller, check the permission, delegate. The queue endpoint
implements D-i — a period is visible to a caller holding `time:approve` on at least one project its
entries name, and Company Admin sees all of them.

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew build"
```

Expected: `BUILD SUCCESSFUL`. **If it says `BUILD FAILED` with `Could not write XML test results`
and zero assertion failures, that is the fd limit, not a red suite** — check
`grep -c ' FAILED$'` excluding `> Task` lines before believing it, and re-run with the `ulimit`
already in the command above.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/tcms/time backend/src/test/java/com/tcms/time \
        backend/src/test/java/com/tcms/architecture
git commit -m "feat(time): the time module, the rollup projection, and the money boundary"
```

---

### T4 — Timesheets and approvals

**Deliverable:** `/timesheets` and `/timesheets/approvals` render on the render kit. A person logs
an entry against an issue, a meeting and leave from one screen; submits the period and watches it
become read-only; a PM approves one and rejects another with a note, and the note is visible on the
rejected period. `npm run build`, `npm run lint` and `npm run test:run` green.
**Depends on:** T3

**Stack:** `fe` · **Agent:** `fe-react` · **Skills:** `fe-react-forms`, `fe-react-data-fetching`,
`fe-react-component-testing`

**Files:**
- Create: `frontend/src/features/time/TimesheetPage.tsx`, `ApprovalsPage.tsx`,
  `EntryForm.tsx`, `EntryTable.tsx`, `RejectDialog.tsx`, and their `.test.tsx` files
- Create: `frontend/src/api/queries/time.ts`
- Modify: `frontend/src/api/queries/index.ts`, `keys.ts`
- Modify: `frontend/src/lib/labels.ts`, `frontend/src/lib/devFaults.ts`
- Modify: `frontend/src/app/router.tsx`, `frontend/src/app/AppShell.tsx`

- [ ] **Step 1: The keys — into the one factory, never a second**

In `frontend/src/api/queries/keys.ts`, beside `imports`:

```ts
  /*
    Time (016).

    `currentPeriod` is keyed by the *date* rather than by a period id, because the screen opens on
    "now" and the id is something the answer contains — keying by id would need a round trip to
    build the key for the first render. `approvals` is keyed by the same date for the same reason
    and is deliberately NOT under `periods`: a PM approving somebody else's fortnight must not
    invalidate their own, and the two lists answer different permissions.
  */
  time: {
    currentPeriod: (on: string) => ['time', 'periods', 'current', on] as const,
    periods: ['time', 'periods'] as const,
    period: (id: string) => ['time', 'periods', id] as const,
    approvals: (on: string) => ['time', 'approvals', on] as const,
    issueRollup: (key: string) => ['time', 'rollup', 'issue', key] as const,
    epicRollup: (key: string) => ['time', 'rollup', 'epic', key] as const,
  },

  /** The prefix root for every time list a write must refresh (§250's argument). */
  timeRoot: ['time'] as const,
```

- [ ] **Step 2: The labels — `satisfies Record<Union, string>` so a new member is a compile error**

In `frontend/src/lib/labels.ts`:

```ts
/** 016 D-b. Five members; TEST_RUN is deliberately absent — the log form cannot produce one. */
export const timeSubjectLabels = {
  ISSUE: 'Issue',
  MEETING: 'Meeting',
  LEAVE: 'Leave',
  TRAINING: 'Training',
  INTERNAL: 'Internal / admin',
} satisfies Record<TimeSubjectType, string>;

export const timesheetStateLabels = {
  OPEN: 'Open',
  SUBMITTED: 'Submitted',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
} satisfies Record<TimesheetState, string>;
```

- [ ] **Step 3: The fault switch, before the feature it proves**

In `frontend/src/lib/devFaults.ts`, beside `isIssuePath`:

```ts
/**
 * Which requests the time arm applies to — the two path families 016 added.
 *
 * The rollup reads are included on purpose: the estimate-vs-actual panel is a *region* inside a
 * screen whose other regions must keep rendering when it fails, and a failure it cannot be given is
 * a DataRegion nobody has watched fall over.
 */
function isTimePath(path: string): boolean {
  return path.startsWith('/timesheets') || path.startsWith('/time/');
}
```

Register the arm so `tcmsFaults.only('/timesheets')` scopes to it, following the `issue` arm's
registration exactly.

- [ ] **Step 4: The hooks**

`frontend/src/api/queries/time.ts` — every read and write, with the timeout budget per endpoint
class this repo threads through every call. The write hooks invalidate `keys.timeRoot` plus, for a
decision, `keys.time.approvals(on)`:

```ts
export function useLogTime() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: TimeEntryInput) => api.post('/timesheets/entries', input, WRITE_TIMEOUT),
    onSuccess: () => { void client.invalidateQueries({ queryKey: keys.timeRoot }); },
  });
}
```

- [ ] **Step 5: The two screens**

`TimesheetPage.tsx` ports `timesheets.html`: the header with the period picker and Submit; the KPI
row — **three tiles, not four** (D-j: Logged, Approved-from-previous, Status; Period capacity is
M7's and is absent rather than invented); the Entries table wrapped in a `DataRegion`; the Add-an-
entry form whose `Against` select drives whether the Issue field is shown; and the right column's
Previous-period panel, which renders the rejection note in the kit's warning note when the previous
period is `REJECTED`.

The mockup's *"What you never see here"* panel is **kept, verbatim in substance**: it is the screen
saying out loud what §7 calls the sharpest boundary in the product, and it is the only place a user
is told the omission is deliberate rather than missing.

`ApprovalsPage.tsx` ports `timesheet-approvals.html`: one panel per person, `Approve` and
`Reject…`, the rejected panel carrying its note and a `Withdraw rejection`, and the not-submitted
panel showing hours-so-far greyed with no approve action — *"an unfinished period is not something
to approve."*

- [ ] **Step 6: Route and rail**

Two routes outside the project frame, beside `/my-work`, because a period spans projects (D-i) and
scoping it under the project switcher would state the opposite:

```tsx
<Route path="/timesheets" element={<Suspense fallback={<RouteFallback />}><TimesheetPage /></Suspense>} />
<Route path="/timesheets/approvals" element={<Suspense fallback={<RouteFallback />}><ApprovalsPage /></Suspense>} />
```

In `AppShell.tsx`, a `TIMESHEETS` nav item in the ungrouped block beside `MY_WORK`, carrying the
waiting-count badge only for a caller holding `time:approve` — the mockup's `SEES_APPROVALS` gate.

- [ ] **Step 7: The component tests**

Use `createTestQueryClient` — **never build your own `QueryClient`**, only that one has the caches.
Cover: the form hides the Issue field for a non-issue category; a `SUBMITTED` period renders no Add
form and no edit buttons; a `REJECTED` period renders the note and an editable form; rejecting
requires a note before the dialog will submit.

- [ ] **Step 8: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend "npm run build && npm run lint && npm run test:run"
```

```bash
git add frontend/src/features/time frontend/src/api/queries/time.ts \
        frontend/src/api/queries/index.ts frontend/src/api/queries/keys.ts \
        frontend/src/lib/labels.ts frontend/src/lib/devFaults.ts \
        frontend/src/app/router.tsx frontend/src/app/AppShell.tsx
git commit -m "feat(time): timesheets and the approvals queue"
```

---

### T5 — Estimate vs actual, the four additive panels, and the close-out

**Deliverable:** The story page carries its Time panel and its `Logged` detail row; the epic page
carries the estimate-vs-actual table with over-estimate rows highlighted; the work table carries the
`Logged` column and its footer total; My Work carries the This-week Logged figure; project settings
carries the Track-time toggle. `docs/features.md`, 009's status table, and the M7 and M8 prompts are
closed out. All gates green.
**Depends on:** T4

**Stack:** `fe` + `docs` (the close-out rides with the last task, per 009's rule 6) ·
**Agent:** `fe-react`

**Files:**
- Modify: `frontend/src/features/tracker/` — the story, epic and work-table screens
- Modify: `frontend/src/features/dashboard/` — My Work
- Modify: `frontend/src/features/settings/` — the general settings page
- Modify: `docs/features.md`, `docs/plans/009-project-management-expansion-modules.md`

- [ ] **Step 1: The story Time panel**

A new panel in the story page's right column, fed by `useIssueRollup(key)` inside a `DataRegion` so
a failed rollup does not take the story with it. Two rows and a meter, plus the log link and the
one-line money disclaimer the mockup carries. **Rendered only when the project has `track_time`**
(D-g). The `Logged` row in the detail list carries the `(approved)` qualifier, because D-d says no
screen renders a bare "hours".

- [ ] **Step 2: The epic estimate-vs-actual table**

Fed by `useEpicRollup(key)`. Columns Story · Estimate · Logged · Drift · meter; the row highlighted
when `overEstimate`; the footer carrying both totals. Drift is `approved − estimate`, positive
rendered in the danger token and negative in the success token, exactly as the mockup does.

- [ ] **Step 3: The `Logged` column on the work table**

Additive column plus the footer total, both behind `track_time`. Keep the mockup's footnote —
*"What an hour costs is not on this screen for anyone"* — for the reason step 5 of T4 gives.

- [ ] **Step 4: My Work's This-week figure**

`Logged` alone, **no Capacity tile and no meter** (D-j), plus the `Open my timesheet →` link. A
capacity figure this module would have to invent is the contradictory-percentages failure.

- [ ] **Step 5: The settings Track-time toggle**

One `setrow` in a new `Time` panel on the general settings page, wired to `project_settings`. The
mockup's Budget row is **not** built — it reads `Edit in Billing` and is M8's.

- [ ] **Step 6: Gates, all three stacks**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew build"
~/bin/tcms-remote frontend "npm run build && npm run lint && npm run test:run"
~/bin/tcms-remote packages "npm run build && npm run lint --workspaces && npm run test:run --workspaces"
```

- [ ] **Step 7: `docs/features.md`**

Rewrite the time section — struck through and superseded, never deleted, so the next reader can see
the old reasoning was considered.

- [ ] **Step 8: The 009 close-out, its own commit**

Per the hand-off protocol:

1. **Status table** — M6's row to `done 2026-09-0N @ <short sha>`.
2. **The V actually taken** — strike the reserved `V11` and write `V9`, with the reason (Mc and M7
   unspent), in both the table and the module's `db` line.
3. **Built and unverified** — a `**Built and unverified:**` list under the M6 section.
4. **Rewrite the dependent prompts.** M8's prompt gains: the endpoint paths as they landed,
   `issue_time_rollup` as the projection's name and `time.RollupService` as its only writer,
   `TimeMoneyBoundaryTest` as the test M8 inherits and must not weaken, and D-i's approval rule.
   M7's prompt gains D-j: the two capacity figures M6 left absent — the fourth KPI tile on
   Timesheets and the Capacity half of My Work's This-week panel — are M7's to complete, and they
   are *absent*, not zero.
5. **Commit this document alone.**

```bash
git add docs/plans/009-project-management-expansion-modules.md
git commit -m "docs(plan): M6 done"
```

---

## Definition of done

- [ ] A person logs time against an issue, a meeting and leave
- [ ] They submit a period, and it stops being editable
- [ ] A PM approves one and rejects another with a note
- [ ] The rejected period is editable again, with the note visible; the submitted one is not
- [ ] Approved hours appear on the story, the epic and the project
- [ ] A Test Engineer with `time:log` logs hours and cannot read a rate — asserted as a negative
- [ ] `issue_fact` is proven unwritten by `time`, and named in exactly one file
- [ ] All three gates green on `shanks`
