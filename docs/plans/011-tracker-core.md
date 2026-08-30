# Tracker Core Implementation Plan — module M1 of 009

**Date:** 2026-08-30 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.
>
> **One task is one session, so one task is one prompt.** Start each task from its block in
> [Prompts — one per task](#prompts--one-per-task) — that is what is pasted into a fresh session,
> and it is complete on its own. This document is what the prompt tells the agent to read.

**Goal:** A person opens a project, creates an epic, adds stories under it, breaks one into tasks,
moves them through the workflow, comments, links a spec block — and every `work_item` that existed
before the upgrade is still there, still called a Task, now with a per-project key.

**Architecture:** A new `com.tcms.tracker` Spring Modulith module owning nine tables, depending only
on the five foundation modules. Issue keys come from `project.ProjectKeys` (M0). Downstream modules
will reference an issue polymorphically and read `issue_fact`, which is fed by a `CompanyScopedEvent`
through the outbox and written by nothing else (scope §3). The composite board survives: `work` keeps
`BoardFacts`, `BoardService`, `BoardPeople`, `ItemAssignedEvent` and `derived_item_annotation`, and
gains an `ISSUE` generator (D11); the nine stored-task classes retire with their two tables.

**Tech Stack:** Postgres 17 + Flyway (`V3`), Spring Boot 3.5.6 / Java 21, `openapi.yaml` + two
generated clients, Vite 8 / React 19 / TanStack Query / Tailwind v4 + the render kit.

**Migration:** `V3__tracker_core.sql`. Verified against
`backend/src/main/resources/db/migration/` on 2026-08-30 — it holds `V1` and `V2` only, so `V3` is
free and matches plan 009's reservation. **Re-read the directory before writing the file.**

---

## Decisions taken before the tasks, and the disagreements behind them

Plan 009 requires every disagreement between the mockups, the scope spec and the code to be
recorded here rather than resolved silently. Nine were found. Two were put to the owner and are
answered; the rest are decided here with their reason.

### D-a — `GET /work-items` is the composite board, and it moves to `/board`

**Owner-confirmed 2026-08-30.** Plan 009 M1 says "`/work-items` and `/work-items/{workItemId}` are
**removed**, not deprecated", and `t3`'s acceptance criteria repeat it. Read literally that deletes
My Work: `GET /work-items` is not work-item CRUD, it is the **one operation serving all three
boards** — `/projects/:id/board`, `/my-work` and `/teams/:teamId/board` — which D11 says stays
composite.

The resolution that satisfies both: the **path** `/work-items` is gone, and the composite list is
`GET /board` with the same three-board semantics (`projectId` narrows *where*, `teamId` and `mine`
narrow *whose*). `POST /work-items` and `PATCH /work-items/{workItemId}` are gone outright —
creating and moving an issue is `POST /issues` and `POST /issues/{key}/transitions`.

### D-b — the workflow is seeded by `tcms_seed_project_defaults`, not by a `ProjectCreatedEvent`

**Owner-confirmed 2026-08-30.** Plan 009 M1 and review R5 call for a `ProjectCreatedEvent` that
`project` publishes and `tracker` consumes. Two facts make that the worse mechanism:

1. **A Java event type may not cross a module boundary here at all.** `CompanyScopedEvent`'s own
   header: *"an event **type** is a dependency like any other, so the publishing module's class is
   unreachable from the consuming one"*. Cross-module delivery is an `OutboxEvents` string plus a
   JSONB payload, claimed by an `OutboxHandler`. `ProjectCreatedEvent` as a shared class cannot
   exist.
2. **Outbox delivery is asynchronous, and its floor is a minute.** `application.yml` sets
   `tcms.outbox.interval` to `PT1M` and `OutboxScheduler` adds a `PT1M` initial delay. A workflow
   seeded that way leaves a brand-new project with no statuses for up to a minute — its first issue
   cannot be created, and story B1's *"a sensible default is already there so I can start without
   configuring anything"* is false for exactly as long.

`tcms_seed_project_defaults` is the mechanism that already solves this shape, and the precedent is
not thin: it seeds `configuration` (an `execution` table) and `doc_space` (a `documentation` table)
synchronously, in the creating transaction, and it is called by **all three** project-creation paths
— `ProjectCreateService`, `CompanyProvisioningService` (twice) and, through them, `seed-demo-data`.
Seeding `issue_status`, `issue_transition` and `issue_type` there costs nothing new architecturally
and is race-free.

**So no `ProjectCreatedEvent` is built, and no `project.created` outbox name is minted** — there
would be nothing left for either to do, and an event with no work is a seam that grows a caller.
The cost is the one CLAUDE.md names: `V3` reproduces the whole function again under
`CREATE OR REPLACE`, and a block omitted silently stops being seeded. `ProjectKeySequenceSchemaTest`
is the existing check that a block went missing; T4 extends it.

**009 must be corrected at close-out** — its M1 `**db**` bullet and review R5 both describe a
mechanism this module deliberately did not build.

### D-c — the board card's status becomes `{ name, kind }`, and `WorkItemStatus` retires

`WorkItemStatus` is a fixed five-member enum — `TODO, IN_PROGRESS, BLOCKED, IN_REVIEW, DONE`. An
issue's status is a **per-project `issue_status` row** (D2, story B1), so an ISSUE card cannot be
spelled in that enum without a mapping table, and a card carrying both spellings is precisely the
"two contradictory numbers on one screen" this repo has shipped once.

So `BoardCard.status` is an object — `{ name, kind }` — for **every** card kind, where `kind` is
`NOT_STARTED | IN_PROGRESS | DONE` (the `issue_status.kind` vocabulary story B1 configures). The
four derived generators supply their existing five names with the matching kind; nothing about how
a derived card is computed changes. M2 builds real board columns on `kind` plus the project's own
statuses, which is what D2 asked for.

### D-d — the CLI has no work-item verbs, and MCP has one tool with two arms, not six

009 says "the six `work-item` verbs in `packages/cli` and `packages/mcp` become `issue` verbs".
The actual surface is smaller and differently shaped:

| Where | What exists |
|---|---|
| `packages/cli` | **Nothing.** `cli/src/reads.ts:575-582` records `work-item` as one of three nouns "surface spec §6 promises and does not deliver". |
| `packages/mcp` | `tcms_write_work_item` (`writes.ts:1255`) — one tool, two arms (`create`, `move`). |
| `packages/mcp` | one read of `GET /work-items` inside the orient/quarantine tool (`reads.ts:607`), filtering for `QUARANTINE` cards. |
| `packages/client` | `WORK_ITEM_SHAPE` in `core/shapes.ts:817`, registered at `:908`. |

T14 replaces those four sites. "Six verbs" is not a count of anything in the tree; the module
surface document is corrected to what actually lands.

### D-e — `tracker` never writes `doc_link`; it keeps a projection of it

`documentation` owns `doc_link` and its write endpoint,
`POST /projects/{projectId}/documents/{pageId}/blocks/{blockId}/links`, which takes `sourceType` in
the body and resolves the source through `CatalogueFacts` — that module's single foreign-table
class, the same pattern as `work.BoardFacts`. `tracker` may not import `documentation` and vice
versa.

So: `LinkSourceType` gains `ISSUE` and `CatalogueFacts` gains an arm that resolves an issue by key
(T11) — the **write** stays where it is. `tracker` keeps `issue_doc_link`, mirroring `case_doc_link`
field for field including `stale`, refreshed by an `OutboxHandler` on the **existing**
`documentation.links_changed` event. `GET /issues/{key}/doc-links` reads that projection.

`case_doc_link` is written by `DocumentationFacts` and *replaced whole per company on every refresh*;
`issue_doc_link` follows it exactly, which is what makes at-least-once delivery cost a repeated
statement rather than a wrong answer.

### D-f — the mockups show four later modules' surfaces, and M1 renders them absent, not empty

The mockups are the source of truth for what a screen shows, and every M1 screen shows work M1 does
not build. Each is listed here with the module that lands it, and **no M1 screen renders an empty
box where one belongs** — a missing section says why it is missing, which is the rule 009 states for
`project-overview.html` and which this plan applies to all ten screens.

| Mockup element | Module | What M1 renders |
|---|---|---|
| `work-table.html` — Release column, `Logged` column, `AI-eligible` filter chip, label chips | M3 / M6 / M5 / M2 | Columns absent from the column set; the `⚙ Columns` picker offers only what exists |
| `epic.html` — target-release chip, `Labels` row, whole "Estimate vs actual" panel | M3 / M2 / M6 | Chip and row absent; the panel is not rendered and the Details `dl` has no `Labels` term |
| `story.html` — Release row, Logged row, Labels row, "Time" panel, "Linked test cases" panel, "Closing this story" review gate | M3 / M6 / M2 / M6 / **H7 (M3 `t18`)** / M5 | Absent. The `Sub-tasks` panel's "3 proposed by claude-code" button is M5 and is absent |
| `task.html` — the whole "What the assistant received", "Pull request", "Assistant" and "What the assistant was refused" panels; the `AI-ELIGIBLE` chip | M5 | Absent. "Where it can go" **is** M1 — it is the legal-transition set, and it lands |
| `issue-new.html` — Target release select, Labels input, AI-eligible toggle, the "Where this lands" release/backlog cards | M3 / M2 / M5 | Absent from the form and from the side panel |
| `my-work.html` — the `AI` tags, "My assistant" panel, "This week" hours KPI | M5 / M6 | Absent |
| `project-overview.html` — "Next release", "Budget burn", "Team" panels; the `2.4 complete` and `To target date` KPI tiles | M3 / M6 / M7 | "Next release" renders `solo-overview.html`'s **empty state** — "No releases yet" with the action — because that is the same absence; the other two panels and the two KPI tiles are not rendered |
| `settings-general.html` — Client select, "Closing work" review toggles, "Time" panel | Mc / M5 / M6 | Absent. Identity (name, key) and Issue types **are** M1 |
| `solo-overview.html` | — | Not a separate screen: the same `ProjectOverviewPage` with no client chip. An acceptance criterion of T21 |

**`project-overview.html`'s "Test health" panel is M1's** and is not deferred: `DashboardPage`
already renders it against endpoints that exist, and D27 says the overview reuses those regions as
its QA half. 009's M1 says the page lands "without its release and **test-health** panels"; that is
wrong in the cheap direction — the panel costs a move, not a build. **Recorded, and test health
lands in T21.**

### D-g — `epic.html`'s "closed itself when the last story closed" and story H4 do not contradict

`project-overview.html` says an epic *"closed itself when the last story closed"*; `story.html` says
a story *"did **not** close itself"*. Both are correct and the difference is D31: an **epic's**
status is derived from its stories and is not editable; a **story's** status is its own and closing
it is a person's decision, whatever its sub-tasks say (H4). T9 builds the first; T7 refuses to build
the second.

### D-h — three role names are hardcoded in the frontend, and they stay

`t3` requires a grep of `frontend/src` and `packages/` for a hardcoded role name. Three sites match,
all on `'Agent'`:

- `frontend/src/features/admin/IssueTokenDialog.tsx:123` and `:212`
- `frontend/src/features/help/AgentTokenPanel.tsx:127`

**They are not the thing that grep was written to catch.** M0's "no screen changes" claim rests on
the *grant* surface staying data-driven, and it does — the grant picker filters on
`role.assignable`, never on a name. These three select the one role that is `assignable = false`,
to decide **which configuration block to print**, and each carries a comment saying it matches on
the name because role ids are seeded data the client does not hold. They predate M0 rather than
arriving with it.

Removing them needs a stable role *code* on the contract that no module has asked for. **Decision:
left in place, recorded here and in the close-out**, so the next agent to run the grep finds the
answer instead of the finding.

### D-i — no new permission is needed

M0 seeded the whole `issue:*` slice — `issue:read`, `issue:create`, `issue:edit`, `issue:delete`,
`issue:comment`, `issue:workflow`, `issue:import` (roles spec §A3 "Tracker"). M1 needs nothing
beyond them, so **`V3` carries no permission row** and the five-document tax is not paid twice.
`workitem:manage` is struck through as superseded (T2) but its rows are **not deleted** from
`role_permission`: a permission with no reader is inert, and deleting seeded catalogue rows is a
`V3` statement whose only effect is to make `SeedDataTest` disagree with three pinned documents.

---

## File structure

**Backend — new module `com.tcms.tracker`** (`backend/src/main/java/com/tcms/tracker/`):

| File | Responsibility |
|---|---|
| `package-info.java` | `@ApplicationModule(allowedDependencies = {"api","tenancy","project","identity","storage"})` and the module's reasoning |
| `Issue.java` · `IssueType.java` · `IssueStatus.java` · `IssueTransition.java` | Entities. `Issue` is the aggregate; the other three are the project's vocabulary |
| `IssueComment.java` · `IssueWatcher.java` · `IssueLink.java` · `IssueDocLink.java` | The four edges hanging off an issue |
| `IssueRepository.java` · `IssueTypeRepository.java` · `IssueStatusRepository.java` · `IssueTransitionRepository.java` · `IssueCommentRepository.java` · `IssueWatcherRepository.java` · `IssueDocLinkRepository.java` | Spring Data. **Every one carries `@Transactional(readOnly = true)` on the interface** |
| `IssueEdit.java` | Validation and the depth rule — pure, no Spring, unit-tested without a context |
| `IssueService.java` | Read + write, the gate, key allocation |
| `IssueController.java` | `/issues`, `/issues/{key}` |
| `TransitionService.java` · `TransitionController.java` | The legal-move set and the move itself |
| `WorkflowService.java` · `WorkflowController.java` | `/projects/{projectId}/workflow` |
| `CommentService.java` · `CommentController.java` | `/issues/{key}/comments` |
| `Completion.java` | D8, pure. Count-or-hours, spikes excluded, the basis returned beside the number |
| `EpicStatus.java` | D31, pure |
| `IssueFactProjection.java` | The **only** writer of `issue_fact` |
| `IssueFactOnIssueChanged.java` | `OutboxHandler` for `tracker.issues_changed` |
| `DocLinkProjection.java` · `DocLinkRefreshOnLinksChanged.java` | `issue_doc_link`, on the existing `documentation.links_changed` |
| `IssueDocLinkFacts.java` | **The one class in this module that names a `documentation` table.** `BoardFacts`' pattern |
| `IssueView.java` · `IssueSummaryView.java` · `WorkflowView.java` · `CommentView.java` · `DocLinkView.java` · `CompletionView.java` | Wire shapes |

**Backend — modified:**

| File | Change |
|---|---|
| `work/BoardFacts.java` | Gains `assignedIssues(...)` — the fifth crossing, and the first that reads `tracker` |
| `work/BoardService.java` | The `ISSUE` generator; `TASK` cards gone; status becomes `{name, kind}` |
| `work/BoardController.java` (renamed from `WorkItemController`) | `GET /board` only. The `POST` and `PATCH` go |
| `work/package-info.java` | `tracker` is not added to `allowedDependencies` — `BoardFacts` reads its **table** |
| `documentation/LinkSourceType.java` | `ISSUE` member |
| `documentation/CatalogueFacts.java` | The `ISSUE` arm, resolving an issue by key |
| `tenancy/OutboxEvents.java` | `TRACKER_ISSUES_CHANGED = "tracker.issues_changed"` |
| `architecture/ArchitectureRulesTest.java` | **`ProjectKeys.allocate`'s `UNEXERCISED_BY_DECISION` entry is deleted** in T6 |

**Deleted in T13:** `work/WorkItem.java`, `WorkItemController.java`, `WorkItemEdit.java`,
`WorkItemRepository.java`, `WorkItemStatus.java`, `WorkItemStatusUpdateRequest.java`,
`WorkItemView.java`, `WorkItemWriteRequest.java`, `Priority.java`.

**Frontend — new** (`frontend/src/features/tracker/`): `WorkTablePage.tsx`, `IssueNewPage.tsx`,
`EpicPage.tsx`, `StoryPage.tsx`, `TaskPage.tsx`, `IssueDetailShell.tsx`, `CompletionMeter.tsx`,
`StatusBadge.tsx`, `TypeIcon.tsx`, `TransitionMenu.tsx`, `CommentFeed.tsx`, `LinkedSpecPanel.tsx`,
plus `frontend/src/api/queries/issues.ts`.
**Note the collision:** `frontend/src/api/queries/tracker.ts` already exists and is the **external**
Jira/ADO integration. The new module's queries go in `issues.ts` — which is what
`009-execution-breakdown.md`'s `t7` file list already says.

**Frontend — modified:** `api/queries/keys.ts`, `lib/labels.ts`, `lib/devFaults.ts`,
`features/board/MyWorkPage.tsx`, `features/board/BoardPage.tsx`,
`features/board/TeamBoardPage.tsx`, `features/board/TaskCreateDialog.tsx` (deleted),
`features/projects/ProjectOverviewPage.tsx` (new, from `features/dashboard/DashboardPage.tsx`),
`features/settings/`, `app/router.tsx`, `app/AppShell.tsx`.

---

## How this plan is cut — one stack, one agent, one session

Per the owner directive of 2026-08-30, now in the global `CLAUDE.md` and in
[009's "How a module's plan is cut into tasks"](009-project-management-expansion-modules.md#how-a-modules-plan-is-cut-into-tasks--one-stack-one-agent-one-session):
**a task is one tech stack, one agent, one session**, and consecutive same-stack work is merged
until it would exceed a **150k-token context**. **Seven tasks** — four `be`, one `ts`, two `fe`.

**The budgets below were re-derived downward on 2026-08-30**, after a first cut produced eleven
tasks on round numbers between 110k and 145k. Those were guesses dressed as budgets: several tasks
were nowhere near the cap and were separate only because they were separate *features*, which is
the cut this directive replaces. Counted properly, four `be` sessions and two `fe` sessions hold
the same work.

| Task | Stack | Agent | Read | Write | Gate | Total |
|---|:--:|---|:--:|:--:|:--:|:--:|
| T1 | `be` | `be-java` | 53k | 38k | 12k | **~105k** |
| T2 | `be` | `data-architect` | 68k | 45k | 17k | **~130k** |
| T3 | `be` | `be-java` | 70k | 55k | 20k | **~145k** |
| T4 | `be` | `be-java` | 80k | 30k | 20k | **~130k** |
| T5 | `ts` | `tooling-engineer` | 55k | 27k | 15k | **~100k** |
| T6 | `fe` | `fe-react` | 80k | 55k | 15k | **~150k** |
| T7 | `fe` | `fe-react` | 73k | 60k | 15k | **~148k** |

*Read* is `CLAUDE.md` (~10k) plus the plan's own task section plus the files the session must open
as patterns; *write* is what it produces; *gate* is the build and test output it reads back.

**T6 and T7 sit on the cap**, and each names a seam **inside its own stack** to split on if a
session runs out: T6 splits after the work table and the new-issue form; T7 splits after the two
settings screens. Handing half a task to another agent is not a split.

**There is no `docs` task.** The module's close-out rides with **T7**, because the session that
makes the capability real is the only one that knows what actually landed, what is built-and-
unverified, and which of 009's dependent prompts stopped being true. A 60k session that only writes
markdown is exactly the wasted agent switch this directive exists to remove.

**What this costs, and it is real:** the tracker is not usable by a person until **T6** lands, and
My Work does not show issues until **T7**. T1–T5 build a contract, a schema, a module and a tool
surface that no screen reads yet. That is the price of one agent switch per stack rather than one
per capability, and 009's *"a person can now …"* is closed by T7, not by each task.

**No task returns a fake success while waiting for its other half.** There is no contract-first stub
here: T1 lands the contract and T3 lands the endpoints behind it, so between them the paths 404 —
the truthful answer for a route that does not exist yet, and what the SPA's error boundaries already
render.

---

## Tasks — in the order they are done

| # | Task | Stack | Agent | Deliverable | Depends on |
|---|---|:--:|---|---|---|
| 1 | T1 — The tracker contract | `be` | `be-java` | `openapi.yaml` serves `/issues`, `/board` and `/projects/{id}/workflow`, `/work-items` is gone, and both clients are regenerated in the same commit | none |
| 2 | T2 — `V3`, and the roles copies | `be` | `data-architect` | The nine tables exist under forced RLS, every project has the six statuses and six types, every pre-upgrade `work_item` is an `issue` with a key — counted either side — and `workitem:manage` is struck through in all three pinned copies | T1 |
| 3 | T3 — The `tracker` module: issues, workflow, completion, `issue_fact` | `be` | `be-java` | `POST /issues` returns `201` with a key from `ProjectKeys`, a forbidden move is a `409`, an epic carries `{percent, basis, done, total, excludedSpikes}` and a derived status, and every write lands an `issue_fact` row from one writer | T2 |
| 4 | T4 — Spec links, comments, and the retirement of `work` | `be` | `be-java` | `GET /issues/{key}/doc-links` returns links with `stale`, comments append and add their author as a watcher, the nine `work` classes are gone, and `GET /board` serves four card kinds with `ISSUE` row-backed | T3 |
| 5 | T5 — Issue verbs on the CLI and MCP | `ts` | `tooling-engineer` | No `work-item` verb remains; `tcms_write_issue` and `tcms_read_issue` work against the new contract; the surface document matches what shipped | T4 |
| 6 | T6 — The tracker screens | `fe` | `fe-react` | A person lists a project's issues, creates one from a single field, and opens an epic, a story or a task and moves it — every screen through `DataRegion`, one `keys` factory | T5 |
| 7 | T7 — Settings, My Work, the overview, and the close-out | `fe` | `fe-react` | My Work shows the caller's issues across two projects beside their reviews and executions with the private note; `/projects/:id` lands on epics, backlog and test health; 009's status table says what landed | T6 |


---

### T1 — `be` · The tracker contract

**Agent:** `be-java` · **Stack:** `be` · **Budget:** ~105k

**Deliverable:** `openapi.yaml` serves `/issues`, `/issues/{key}`, `/issues/{key}/comments`,
`/issues/{key}/transitions`, `/issues/{key}/doc-links`, `/projects/{projectId}/workflow` and
`/board`; `/work-items` and `/work-items/{workItemId}` are gone; `frontend/src/api/schema.d.ts` and
`packages/client/src/schema.ts` are regenerated in the same commit.
**Depends on:** none

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Modify: `frontend/src/api/schema.d.ts` (generated)
- Modify: `packages/client/src/schema.ts` (generated)

`frontend/src/api/contractBodies.ts` and `packages/client/src/test/contractBodies.ts` are **not**
edited. Their shared region is a generic walker over `spec()` — it has no per-schema bodies — so
new endpoints need no change there. `contractPairing.test.ts` stays green because neither copy
moves. **If a later task does need a walker change, both copies change in that same commit.**

- [ ] **Step 1: Delete the three work-item operations and the two paths**

Remove `/work-items:` (lines ~8267–8420) and `/work-items/{workItemId}:` (~8421–8470) whole.
Remove the `WorkItem`, `WorkItemStatus`, `WorkItemInput` and `WorkItemStatusUpdate` schemas
(~15684–15810). `Priority` **stays** — `test_case.priority` uses it and is untouched.

Grep for prose that names the removed path and correct each; there are five sites outside the
operations themselves:

```bash
grep -n 'work-item\|WorkItem' docs/specs/openapi.yaml
# expect only: 1075, 3542, 3554, 6110, 11353 — each a description referring to the old board
```

Each becomes `GET /board`. Line 7401 (`ADO's work-item id`) is about a foreign tracker's
vocabulary and is left alone.

- [ ] **Step 2: Add the issue schemas**

Under `components.schemas`, in alphabetical position. **Every object carries its `required` array,
including the inline nested ones** — `required` does not descend.

```yaml
    IssueLevel:
      type: string
      description: |
        Where a type sits in the hierarchy. The depth rule is expressed in LEVELS, not in type
        names (scope §4): a type may only parent to a type one level above it. Story, Bug and
        Spike all sit at `STORY`, so all three hold Tasks and all three may sit under an Epic —
        and a fourth story-level type is a ROW, not a migration.
      enum: [EPIC, STORY, TASK]

    IssueStatusKind:
      type: string
      description: |
        What a status MEANS, so a percentage can be computed without knowing what a project
        called its columns (D2). Every completion figure in the product is `DONE` over the
        counted total; `IN_PROGRESS` is not partial credit, it is simply not done (D8).
      enum: [NOT_STARTED, IN_PROGRESS, DONE]

    IssueTypeRef:
      type: object
      required: [id, name, level, countsTowardCompletion]
      properties:
        id: { type: string, format: uuid }
        name: { type: string, example: Story }
        level: { $ref: '#/components/schemas/IssueLevel' }
        countsTowardCompletion:
          type: boolean
          description: |
            False for Spike and for every `EPIC`-level type. A spike produces knowledge, not
            shippable work, so counting it would let an epic read 40% done having shipped
            nothing (D8) — it still consumes hours and still counts toward budget burn, which
            is a different question and is allowed to disagree.

    IssueStatusRef:
      type: object
      required: [id, name, kind, position]
      properties:
        id: { type: string, format: uuid }
        name: { type: string, example: In Review }
        kind: { $ref: '#/components/schemas/IssueStatusKind' }
        position: { type: integer, description: Order in the project's workflow, from 0. }

    IssueCompletion:
      type: object
      required: [percent, basis, done, total, excludedSpikes]
      description: |
        D8, and **the basis is on the wire — never inferred by the client.** An unestimated epic
        gets a coarser number rather than a wrong one, and the page always names which it got.
      properties:
        percent: { type: integer, minimum: 0, maximum: 100 }
        basis:
          type: string
          description: |
            `ESTIMATE` when EVERY counted issue carries one, `COUNT` otherwise. The moment one
            estimate is removed this flips, and the page says so — a percentage never changes
            meaning silently.
          enum: [COUNT, ESTIMATE]
        done: { type: integer, description: Counted issues in a `DONE` status. }
        total: { type: integer, description: Counted issues. Zero is a real answer, and `percent` is then 0. }
        estimatedHoursDone: { type: number, format: double, nullable: true }
        estimatedHoursTotal: { type: number, format: double, nullable: true }
        excludedSpikes:
          type: integer
          description: How many issues were left out of both numerator and denominator, so the page can say so.

    IssueSummary:
      type: object
      required: [key, title, type, status, projectId, projectKey]
      description: A row in a list — the work table, an epic's children, a story's sub-tasks.
      properties:
        key: { type: string, example: ACME-124 }
        title: { type: string }
        type: { $ref: '#/components/schemas/IssueTypeRef' }
        status: { $ref: '#/components/schemas/IssueStatusRef' }
        projectId: { type: string, format: uuid }
        projectKey: { type: string, example: ACME }
        parentKey: { type: string, nullable: true }
        epicKey: { type: string, nullable: true, description: The story-level ancestor's epic, carried so a table need not walk the tree. }
        epicTitle: { type: string, nullable: true }
        assignee:
          allOf: [{ $ref: '#/components/schemas/UserRef' }]
          nullable: true
        dueDate: { type: string, format: date, nullable: true }
        overdue: { type: boolean }
        estimateHours: { type: number, format: double, nullable: true }

    Issue:
      type: object
      required:
        [key, title, type, status, projectId, projectKey, watchers, children,
         allowedTransitions, createdAt, seq]
      properties:
        key: { type: string, example: ACME-118 }
        seq:
          type: integer
          format: int64
          description: |
            The number behind the key, and **the only column that may order issue keys**.
            Sorting on the key string puts `ACME-100` before `ACME-2`.
        title: { type: string }
        description: { type: string, nullable: true }
        acceptanceCriteria:
          type: array
          description: |
            Story H1/H4. Ordered, and each carries its own done flag — the story page renders
            "3 of 4" from this and nothing else.
          items:
            type: object
            required: [id, text, done, position]
            properties:
              id: { type: string, format: uuid }
              text: { type: string }
              done: { type: boolean }
              position: { type: integer }
        type: { $ref: '#/components/schemas/IssueTypeRef' }
        status: { $ref: '#/components/schemas/IssueStatusRef' }
        statusEnteredAt: { type: string, format: date-time }
        projectId: { type: string, format: uuid }
        projectKey: { type: string }
        parentKey: { type: string, nullable: true }
        parentTitle: { type: string, nullable: true }
        epicKey: { type: string, nullable: true }
        epicTitle: { type: string, nullable: true }
        assignee:
          allOf: [{ $ref: '#/components/schemas/UserRef' }]
          nullable: true
        reporter:
          allOf: [{ $ref: '#/components/schemas/UserRef' }]
          nullable: true
        priority: { $ref: '#/components/schemas/Priority' }
        estimateHours: { type: number, format: double, nullable: true, description: Hours, never points (D8) — the same unit a budget and an invoice use. }
        startDate: { type: string, format: date, nullable: true, description: Epics only. Null on every other level. }
        targetDate: { type: string, format: date, nullable: true, description: Epics only. }
        dueDate: { type: string, format: date, nullable: true }
        overdue: { type: boolean }
        completion:
          allOf: [{ $ref: '#/components/schemas/IssueCompletion' }]
          nullable: true
          description: |
            **Epics only** — null on every other level, because D8 counts story-level issues
            and an epic is the only thing that has them. Computed server-side and read
            everywhere; never recomputed in a component (D8, "one number, one place").
        children:
          type: array
          description: An epic's stories, or a story's sub-tasks. Empty for a Task.
          items: { $ref: '#/components/schemas/IssueSummary' }
        watchers:
          type: array
          items: { $ref: '#/components/schemas/UserRef' }
        allowedTransitions:
          type: array
          description: |
            Statuses this issue may legally move to, from where it stands. **Disabled, not
            hidden** on the screen (`task.html`) — a caller can see the move exists. Empty for
            an Epic: D31 derives an epic's status from its stories and it is not editable.
          items: { $ref: '#/components/schemas/IssueStatusRef' }
        createdAt: { type: string, format: date-time }
        updatedAt: { type: string, format: date-time, nullable: true }
        doneAt: { type: string, format: date-time, nullable: true }

    IssueInput:
      type: object
      required: [projectId, typeId, title]
      description: |
        **No `status` field**, for `WorkItemInput`'s reason and `TestPlan.create`'s: a create that
        could choose its column is a move no move was recorded for, and "create it directly in
        Done" is the shape that lets a board show completed work nobody did. A new issue enters
        the project's first status by position.
      properties:
        projectId: { type: string, format: uuid }
        typeId: { type: string, format: uuid }
        title: { type: string, minLength: 1, maxLength: 500 }
        description: { type: string, nullable: true }
        parentKey:
          type: string
          nullable: true
          description: |
            **Required for a `TASK`-level type and refused for an `EPIC`-level one.** A Task is a
            breakdown *of* something; an Epic is top level. A story-level type takes an OPTIONAL
            epic here — requiring one would force a junk epic per stray bug (scope §4).
        assigneeId: { type: string, format: uuid, nullable: true }
        priority: { $ref: '#/components/schemas/Priority' }
        estimateHours: { type: number, format: double, nullable: true }
        startDate: { type: string, format: date, nullable: true }
        targetDate: { type: string, format: date, nullable: true }
        dueDate: { type: string, format: date, nullable: true }
        acceptanceCriteria:
          type: array
          items: { type: string }

    IssueUpdate:
      type: object
      required: []
      description: |
        Every field optional; an absent key is "leave it". **`status` is not here** — a move goes
        through `POST /issues/{key}/transitions`, which is the only path the workflow's legal-move
        set is checked on. A PATCH that could set a status would be a second, unchecked spelling
        of the one rule this module exists to enforce.
      properties:
        title: { type: string, minLength: 1, maxLength: 500 }
        description: { type: string, nullable: true }
        parentKey: { type: string, nullable: true }
        assigneeId: { type: string, format: uuid, nullable: true }
        priority: { $ref: '#/components/schemas/Priority' }
        estimateHours: { type: number, format: double, nullable: true }
        startDate: { type: string, format: date, nullable: true }
        targetDate: { type: string, format: date, nullable: true }
        dueDate: { type: string, format: date, nullable: true }
        acceptanceCriteria:
          type: array
          items:
            type: object
            required: [text, done]
            properties:
              text: { type: string }
              done: { type: boolean }

    IssueTransitionInput:
      type: object
      required: [statusId]
      properties:
        statusId: { type: string, format: uuid }

    IssueComment:
      type: object
      required: [id, body, author, createdAt]
      properties:
        id: { type: string, format: uuid }
        body: { type: string }
        author: { $ref: '#/components/schemas/UserRef' }
        createdAt: { type: string, format: date-time }
        editedAt: { type: string, format: date-time, nullable: true }

    IssueCommentInput:
      type: object
      required: [body]
      properties:
        body: { type: string, minLength: 1 }

    IssueDocLink:
      type: object
      required: [id, targetKind, relationship, stale]
      description: |
        `tracker`'s projection of a `doc_link` row, mirroring `case_doc_link` field for field.
        **Read-only here** — the write is `documentation`'s existing
        `POST /projects/{projectId}/documents/{pageId}/blocks/{blockId}/links`, because `tracker`
        may not import `documentation` and does not own this edge.
      properties:
        id: { type: string, format: uuid }
        targetKind: { type: string, enum: [DOC_BLOCK, EXTERNAL_GIT, EXTERNAL_URL, EXTERNAL_ISSUE] }
        relationship: { type: string, enum: [VERIFIES, DERIVED_FROM, REFERENCES] }
        pageId: { type: string, format: uuid, nullable: true }
        pageKey: { type: string, nullable: true, example: DOC-1 }
        pageTitle: { type: string, nullable: true }
        blockId: { type: string, format: uuid, nullable: true }
        blockExcerpt:
          type: string
          nullable: true
          description: |
            **What you linked**, not what the block says now — which is the whole point of
            `stale`. Re-confirming replaces this with the current text and clears the flag.
        stale:
          type: boolean
          description: |
            The linked page changed after this issue last did. `case_doc_link.stale`'s rule
            exactly: newest `doc_page_version` against the issue's own `updated_at`, PAGE
            granularity, and **always false for an external target**.
        externalUrl: { type: string, nullable: true }

    ProjectWorkflow:
      type: object
      required: [projectId, statuses, types, transitions]
      properties:
        projectId: { type: string, format: uuid }
        statuses:
          type: array
          items: { $ref: '#/components/schemas/IssueStatusRef' }
        types:
          type: array
          items: { $ref: '#/components/schemas/IssueTypeRef' }
        transitions:
          type: array
          description: The legal moves, as pairs. A move not in this list is refused with a 409.
          items:
            type: object
            required: [fromStatusId, toStatusId]
            properties:
              fromStatusId: { type: string, format: uuid }
              toStatusId: { type: string, format: uuid }

    StatusRemovalInput:
      type: object
      required: [reassignToStatusId]
      description: |
        Story B1: removing a status that issues hold cannot silently orphan them, so the caller
        names where they go. Sent on the DELETE as a body — the alternative, a query parameter,
        makes a destructive decision look like a filter.
      properties:
        reassignToStatusId: { type: string, format: uuid }
```

- [ ] **Step 3: Rewrite the board card, and add `/board`**

`BoardCard` replaces `WorkItem`. Note `status` is now an object (D-c) and `ISSUE` replaces `TASK`.

```yaml
    BoardCard:
      type: object
      required: [id, derived, kind, title, status, projectId, projectKey, overdue, allowedTransitions]
      description: |
        Real issues AND derived items in one list. **Only one of them is a row.** A derived card
        is a view over something else — an assigned execution, a case the health computation
        flagged, a review somebody asked of you — and its status is computed from its source on
        every read, so a card can never diverge from its execution (plan §6l).

        **`ISSUE` is the row-backed kind since M1**, replacing `TASK`. `work_item` is gone;
        `WorkItem`'s javadoc rule — "*a task somebody typed* is the only kind of board card that
        is a row" — is kept, with `issue` as the row (D11).
      properties:
        id:
          type: string
          description: |
            An issue's KEY (`ACME-124`) for an `ISSUE` card; a synthetic `execution:{uuid}` for
            a derived one, which has no row to have an id.
        derived: { type: boolean }
        sourceType: { type: string, nullable: true, example: EXECUTION }
        sourceId: { type: string, format: uuid, nullable: true }
        kind:
          type: string
          description: |
            Six kinds. `DOC_UPDATE` and `DEFECT` need features nothing computes or writes yet and
            are **absent rather than empty** — a generator that always yields nothing is
            indistinguishable from a broken one — and the enum keeps them so the SPA's labels
            stay complete for the day they land.
          enum: [ISSUE, EXECUTION, REVIEW, DOC_UPDATE, CASE_MAINTENANCE, DEFECT, QUARANTINE]
        title: { type: string }
        description: { type: string, nullable: true }
        status:
          type: object
          required: [name, kind]
          description: |
            **An object since M1, and `WorkItemStatus` is gone (D-c).** An issue's status is a
            per-project `issue_status` row, so a fixed five-member enum cannot spell it without a
            mapping table — and a card carrying both spellings is the two-contradictory-numbers
            failure this repo has shipped once. Derived generators supply their own names with
            the matching kind; M2 builds board columns from `kind` plus the project's statuses.
          properties:
            name: { type: string, example: In Review }
            kind: { $ref: '#/components/schemas/IssueStatusKind' }
        priority: { $ref: '#/components/schemas/Priority' }
        dueDate: { type: string, format: date, nullable: true }
        overdue: { type: boolean }
        assignee:
          allOf: [{ $ref: '#/components/schemas/UserRef' }]
          nullable: true
          description: Null for work nobody has taken — an execution in a run nobody divided up.
        projectId: { type: string, format: uuid }
        projectKey: { type: string }
        linkedKey: { type: string, nullable: true, example: TC-42 }
        annotation:
          allOf: [{ $ref: '#/components/schemas/CardAnnotation' }]
          nullable: true
          description: |
            The viewer's OWN note, personal due date and flag — `derived_item_annotation`,
            which survives M1 and matters more, not less (D11): personal metadata on a card you
            do not own is exactly right for a board mixing your issues with reviews somebody
            else asked of you. **Never another viewer's**; two people reading one card get
            different values here and that is the feature.
        allowedTransitions:
          type: array
          description: Empty for a derived card whose source offers no board-driven action.
          items: { $ref: '#/components/schemas/IssueStatusRef' }

    CardAnnotation:
      type: object
      required: [flagged]
      properties:
        note: { type: string, nullable: true }
        personalDueDate: { type: string, format: date, nullable: true }
        flagged: { type: boolean }

    CardAnnotationInput:
      type: object
      required: []
      properties:
        note: { type: string, nullable: true }
        personalDueDate: { type: string, format: date, nullable: true }
        flagged: { type: boolean }
```

Then the paths. `/board` keeps `/work-items`' three query parameters, its `400` pair and its `404`
pair verbatim — copy the descriptions across rather than rewriting them, they encode decisions:

```yaml
  /board:
    get:
      tags: [work]
      summary: Board contents — a project's, your own, or a team's
      operationId: getBoard
      description: |
        **Renamed from `GET /work-items` in M1.** The path named a table that no longer exists;
        the operation is unchanged in shape and in every one of its three board semantics.

        Returns real issues AND derived items in one list. With no `projectId` this is the
        cross-project personal board (`/my-work`); with a `teamId` it is the team board. All
        three boards are one operation because they differ only in which cards are kept:
        `projectId` narrows *where*, `teamId` and `mine` narrow *whose*.

        **A team board is bounded by the CALLER's project visibility, never by the team's.** Two
        people asking for the same team's board can legitimately receive different boards.
      parameters:
        - { name: projectId, in: query, schema: { type: string, format: uuid } }
        - { name: teamId, in: query, schema: { type: string, format: uuid } }
        - { name: mine, in: query, schema: { type: boolean } }
      responses:
        '200':
          description: |
            Cards. **An empty array is a reachable board with nothing on it** — for a team board
            that means either a team with no members or members with no work the caller can see,
            and the two are deliberately indistinguishable on the wire.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/BoardCard' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }

  /board/annotations/{sourceType}/{sourceId}:
    parameters:
      - { name: sourceType, in: path, required: true, schema: { type: string } }
      - { name: sourceId, in: path, required: true, schema: { type: string } }
    put:
      tags: [work]
      summary: Your own note and personal due date on a card
      operationId: putCardAnnotation
      description: |
        Story B5's second criterion. Writes `derived_item_annotation`, keyed on the CALLER — a
        teammate looking at the same issue does not see this. No permission beyond seeing the
        card: annotating something is not editing it, which is the whole reason the table is
        separate from the row.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/CardAnnotationInput' }
      responses:
        '200':
          description: Saved
          content:
            application/json:
              schema: { $ref: '#/components/schemas/CardAnnotation' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
```

- [ ] **Step 4: Add the issue paths**

```yaml
  /issues:
    get:
      tags: [tracker]
      summary: A project's issues
      operationId: listIssues
      description: |
        `issue:read`. **Test Engineer, Test Lead, Project Manager, Viewer and Automation Service
        all hold it** (roles spec §A3). The work table (`work-table.html`) is this operation.
      parameters:
        - { name: projectId, in: query, required: true, schema: { type: string, format: uuid } }
        - { name: level, in: query, schema: { $ref: '#/components/schemas/IssueLevel' } }
        - { name: epicKey, in: query, schema: { type: string } }
        - { name: parentKey, in: query, schema: { type: string } }
        - { name: statusId, in: query, schema: { type: array, items: { type: string, format: uuid } } }
        - { name: assigneeId, in: query, schema: { type: array, items: { type: string, format: uuid } } }
        - name: unparented
          in: query
          description: |
            Story-level issues with no epic — the overview's "Unscheduled backlog" reads this.
            A separate flag rather than `epicKey=null`, because an absent parameter already
            means "don't filter" and a client cannot spell the difference.
          schema: { type: boolean }
        - { name: sort, in: query, schema: { type: string, enum: [key, title, status, dueDate, estimateHours] } }
      responses:
        '200':
          description: Issues, newest key first unless `sort` says otherwise.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/IssueSummary' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
    post:
      tags: [tracker]
      summary: Create an issue
      operationId: createIssue
      description: |
        `issue:create` — Instance Admin, Company Admin, Project Manager, Test Lead and Test
        Engineer. **Test Engineer holds it (D28)**: a developer *is* a Test Engineer here, and
        one who cannot create an issue cannot file a bug or break a story into tasks.

        The key is allocated server-side from the project's own counter — `ACME-1`, `ACME-2` —
        and is never a field on the request.
      parameters: [{ $ref: '#/components/parameters/IdempotencyKey' }]
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/IssueInput' }
      responses:
        '201':
          description: Created
          headers:
            Location: { schema: { type: string }, description: The new issue's canonical URL. }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Issue' }
        '200':
          description: A replay — the `Idempotency-Key` had already created this issue.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Issue' }
        '400':
          description: |
            Includes the depth rule: a `TASK`-level type with no `parentKey`, an `EPIC`-level
            type with one, or a parent more than one level above the child. The message names
            the level, not the type — the rule is about levels (scope §4).
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /issues/{key}:
    parameters:
      - name: key
        in: path
        required: true
        description: |
          The issue's display key — `ACME-124`. Addressed by key rather than by UUID because it
          is what a person types into a commit message and what every screen shows, and because
          `project.key` is unique per company and the counter is per project, so the pair is
          unique in the only scope RLS lets a caller see.
        schema: { type: string }
    get:
      tags: [tracker]
      summary: One issue, with its children, watchers and legal moves
      operationId: getIssue
      responses:
        '200':
          description: The issue
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Issue' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
    patch:
      tags: [tracker]
      summary: Edit an issue's fields
      operationId: updateIssue
      description: |
        `issue:edit`. **Not status** — that is `POST /issues/{key}/transitions`, the one path the
        workflow's legal-move set is checked on.

        **An Epic refuses `parentKey`.** Epics are top level; there is no level above one.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/IssueUpdate' }
      responses:
        '200':
          description: Updated
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Issue' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
    delete:
      tags: [tracker]
      summary: Delete an issue
      operationId: deleteIssue
      description: |
        `issue:delete` — **Test Engineer does not hold it**, Test Lead and above do (§A3).

        Soft delete: `issue` inherits `work_item`'s `deleted_at`. That is the mitigation scope §3
        names for the price of the module rule — a downstream `time_entry` holds an id into this
        table with no foreign key, so a hard delete would orphan it silently.

        **An issue with children is refused with a 409.** Deleting an epic that holds seventeen
        stories is either a cascade nobody asked for or seventeen orphans; the message says to
        move or delete the children first.
      responses:
        '204': { description: Deleted }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409':
          description: This issue has children.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }

  /issues/{key}/transitions:
    parameters:
      - { name: key, in: path, required: true, schema: { type: string } }
    post:
      tags: [tracker]
      summary: Move an issue to another status
      operationId: transitionIssue
      description: |
        `issue:edit`. **There is no `board:move` permission** — dragging a card changes the
        issue's status and costs exactly this.

        Only the moves the project's workflow allows are accepted; a forbidden one is a **409**
        and the card does not move (story H5). The board refuses the drop and says why rather
        than snapping back in silence.

        **An Epic is refused with a 409 whatever the target**: D31 derives an epic's status from
        its stories and it is not editable. The message says so.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/IssueTransitionInput' }
      responses:
        '200':
          description: Moved
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Issue' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409':
          description: The workflow forbids this move, or the issue is an Epic.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }

  /issues/{key}/comments:
    parameters:
      - { name: key, in: path, required: true, schema: { type: string } }
    get:
      tags: [tracker]
      summary: An issue's comments
      operationId: listIssueComments
      responses:
        '200':
          description: Comments, oldest first.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/IssueComment' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
    post:
      tags: [tracker]
      summary: Comment on an issue
      operationId: createIssueComment
      description: |
        `issue:comment` — and **Viewer holds it** (§A3). A reader who can see the work can say
        something about it without being able to change it, which is the whole point of the
        separate key.

        Commenting adds the author as a watcher. Story H8: watchers are notified of status and
        assignee changes, so a person who has said something is following it by default.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/IssueCommentInput' }
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/IssueComment' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /issues/{key}/watchers:
    parameters:
      - { name: key, in: path, required: true, schema: { type: string } }
    put:
      tags: [tracker]
      summary: Watch or stop watching
      operationId: putIssueWatch
      description: |
        `issue:read` — watching is not editing. Idempotent in both directions, which is why it
        is a PUT with a boolean rather than a POST and a DELETE: the star on the screen has two
        states and the request says which one the person wants, not which one they think it is in.
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [watching]
              properties:
                watching: { type: boolean }
      responses:
        '200':
          description: The issue's watchers after the change.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/UserRef' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }

  /issues/{key}/doc-links:
    parameters:
      - { name: key, in: path, required: true, schema: { type: string } }
    get:
      tags: [tracker]
      summary: The specification blocks this issue links to
      operationId: listIssueDocLinks
      description: |
        `issue:read`. **Read-only, and there is no write here** — creating the link is
        `documentation`'s `POST /projects/{projectId}/documents/{pageId}/blocks/{blockId}/links`
        with `sourceType: ISSUE`, because that module owns `doc_link` and this one may not
        import it. What is served here is `tracker`'s own projection, `issue_doc_link`, which is
        what carries `stale`.
      responses:
        '200':
          description: Links, with the excerpt as it was when linked.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/IssueDocLink' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }

  /projects/{projectId}/workflow:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
    get:
      tags: [tracker]
      summary: The project's statuses, types and legal moves
      operationId: getProjectWorkflow
      description: |
        `issue:read` — every screen that renders a status picker needs this, including the
        read-only ones, so it is not gated on `issue:workflow`.
      responses:
        '200':
          description: The workflow
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ProjectWorkflow' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
    put:
      tags: [tracker]
      summary: Replace the project's workflow
      operationId: putProjectWorkflow
      description: |
        `issue:workflow` — **Test Engineer does not hold it**; Project Manager, Test Lead and
        above do (§A3). Story B1: add, rename, reorder and mark each status not started / in
        progress / done.

        **Whole-document PUT rather than per-status PATCH.** Reordering is a statement about the
        set, and a sequence of single-status writes has an intermediate state where two statuses
        claim one position — which is exactly what the screen's ↑/↓ would generate.

        **Removing a status that issues hold is refused here with a 409** naming the count. The
        removal that names where they go is `DELETE .../workflow/statuses/{statusId}`.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ProjectWorkflow' }
      responses:
        '200':
          description: The workflow as stored.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ProjectWorkflow' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409':
          description: A removed status still holds issues.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }

  /projects/{projectId}/workflow/statuses/{statusId}:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: statusId, in: path, required: true, schema: { type: string, format: uuid } }
    delete:
      tags: [tracker]
      summary: Remove a status, moving its issues
      operationId: deleteWorkflowStatus
      description: |
        `issue:workflow`. Story B1's third criterion: *"I am asked which status those issues move
        to"*. The body names it, so the move and the removal are one transaction and there is no
        window where an issue points at a status that is gone.

        **The last `DONE`-kind status cannot be removed.** Every percentage in the product is
        computed from that kind (D8); a project with none has no expressible completion, and the
        screens would render a percentage of nothing rather than an error.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/StatusRemovalInput' }
      responses:
        '204': { description: Removed, and its issues moved. }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409':
          description: This is the project's last `DONE` status.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }
```

- [ ] **Step 5: Regenerate both clients**

```bash
cd ~/RiderProjects/tcms/frontend && npm run gen:api
cd ~/RiderProjects/tcms/packages/client && npm run gen:api
```

Expected: both `schema.d.ts` and `schema.ts` gain the `Issue*`, `BoardCard`, `CardAnnotation`,
`ProjectWorkflow` and `StatusRemovalInput` types and lose the four `WorkItem*` ones. **The two
files must remain identical in line count** — they are generated from one document:

```bash
cd ~/RiderProjects/tcms
diff <(sed 's/api\/v1//' packages/client/src/schema.ts) frontend/src/api/schema.d.ts | head
# expect: no output beyond the /api/v1 prefix the packages copy carries
```

The tree does **not** build after this step — `frontend` and `packages` both reference the removed
`WorkItem` types. That is expected and is repaired by T13 and T14; this task's own gate is the two
checks below, not a full build.

- [ ] **Step 6: Check the contract parses and the pairing holds**

```bash
cd ~/RiderProjects/tcms/packages
npx tsx -e "import {parse} from 'yaml'; import {readFileSync} from 'fs'; const d=parse(readFileSync('../docs/specs/openapi.yaml','utf8')); const s=d.components.schemas; for (const [n,v] of Object.entries(s)) { if (v.type==='object' && v.properties && !v.required) console.log('MISSING required:', n); }"
```

Expected: no line naming an `Issue*`, `BoardCard` or `ProjectWorkflow` schema. (Pre-existing
schemas may appear; note them and leave them — they are not this module's.)

```bash
npm run test:run -w @tcms/client -- contractPairing
```

Expected: PASS — neither `contractBodies.ts` moved.

- [ ] **Step 7: Commit**

```bash
cd ~/RiderProjects/tcms
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts
git commit -m "feat(contract): the tracker surface; /work-items becomes /board

/issues, /issues/{key} and its four sub-resources, /projects/{id}/workflow, and the
composite board renamed to /board with ISSUE as the row-backed card kind.

GET /work-items was never work-item CRUD — it is the one operation serving all three
boards, which D11 keeps composite — so the path goes and the operation does not. The
card's status becomes {name, kind}: an issue's status is a per-project row, and a fixed
five-member enum cannot spell it without a mapping table nobody would keep correct.

Both generated clients regenerated in this commit."
```

---

### T2 — `be` · `V3`, and the roles copies

**Agent:** `data-architect` (with `data-postgresql`, `data-migrations`) · **Stack:** `be` · **Budget:** ~130k

#### Part A — `workitem:manage` superseded

**Deliverable:** `workitem:manage` reads "→ superseded by `issue:*`" in the roles spec §A3,
`permission-matrix-summary.md` and `README.md`, in one commit; `matrixCopies.test.ts` green.
**Depends on:** T1 (the contract no longer names the key)

**Files:**
- Modify: `docs/specs/2026-07-30-tcms-roles-permissions-naming.md:216-221` and `:566`
- Modify: `docs/specs/permission-matrix-summary.md:35`
- Modify: `README.md:77`
- Test: `packages/client/src/test/matrixCopies.test.ts` (existing, not edited)

**The rows are not deleted.** `role_permission` keeps them: a permission with no reader is inert,
and a `V3` statement deleting seeded catalogue rows would only make `SeedDataTest` disagree with
three documents that pin the matrix byte-for-byte (D-i).

- [ ] **Step 1: Amend §A3's "Defects & work items" section**

At `docs/specs/2026-07-30-tcms-roles-permissions-naming.md:216`, keep the table row and add the
supersession beneath it, in the struck-through style this repo requires:

```markdown
#### Defects & work items

| Permission | IA | CA | PM | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `defect:create` | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| `workitem:manage` | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |

Both new — the plan described the features (defect filing from a failed step; the kanban board,
§6l) without permissions to govern them.

~~`workitem:manage` governs the board's stored tasks.~~ **→ superseded 2026-08-30 by `issue:*`
(009 module M1).** `work_item` is gone: every stored card is an `issue`, so creating one costs
`issue:create` and moving one costs `issue:edit`. **There is no `board:move`** — dragging a card
changes the issue's status, and a second key for the same act would be a permission somebody could
hold without being able to use it.

The key and its five grants are **kept in the catalogue rather than deleted**. Nothing reads it;
removing seeded rows would be a migration whose only effect is to make three documents that pin
this matrix disagree with the database. It is inert, and it is a record of what the board used to
be.
```

Apply the identical supersession sentence at `:566` (the flat per-role listing).

- [ ] **Step 2: Amend the two convenience copies**

`docs/specs/permission-matrix-summary.md:35` and `README.md:77` keep their rows unchanged — the
grants have not moved — and each gains one line beneath its table:

```markdown
`workitem:manage` is **superseded by `issue:*`** (2026-08-30, module M1): stored board cards are
issues now, so creating one costs `issue:create` and moving one `issue:edit`. The key is kept in
the catalogue and read by nothing.
```

- [ ] **Step 3: Run the copies test**

```bash
cd ~/RiderProjects/tcms/packages
npm run test:run -w @tcms/client -- matrixCopies
```

Expected: PASS. It compares the three tables; the tables did not move, only the prose beneath them.

- [ ] **Step 4: Check no comment now claims a permission somebody lacks**

```bash
npm run test:run -w @tcms/client -- tierClaims
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/RiderProjects/tcms
git add docs/specs/2026-07-30-tcms-roles-permissions-naming.md docs/specs/permission-matrix-summary.md README.md
git commit -m "docs(roles): workitem:manage is superseded by issue:*

The key and its five grants stay in the catalogue — nothing reads it, and deleting
seeded rows would only make three pinned copies disagree with the database. The three
copies move in one commit or matrixCopies.test.ts reddens."
```


---

#### Part B — the nine tables

**Deliverable:** `issue`, `issue_type`, `issue_status`, `issue_transition`, `issue_link`,
`issue_comment`, `issue_watcher`, `issue_doc_link` and `issue_fact` exist, every one with
`company_id UUID NOT NULL` leading every composite index and `FORCE ROW LEVEL SECURITY`;
`SchemaConventionTest` green.
**Depends on:** T1

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__tracker_core.sql`
- Modify: `docs/specs/2026-07-30-tcms-erd.md`

- [ ] **Step 1: Confirm the version, from the directory**

```bash
ls ~/RiderProjects/tcms/backend/src/main/resources/db/migration/
```

Expected: `V1__initial_schema.sql`, `V2__pm_foundation.sql`. If anything else is there, **take the
next free number and correct this plan and 009's table** — `out-of-order` is unset, so a lower
number arriving after a higher one is silently not applied and Testcontainers will never show you.

- [ ] **Step 2: Write the file header**

`V3` runs against a **live** instance, like `V2` and unlike `V1`. Every statement is written to be
safe on one.

```sql
-- =============================================================================
-- V3 — the tracker (plan 011, module M1 of 009)
--
-- Nine tables, one seeded workflow per project, and the retirement of work_item.
-- What a person can do afterwards that they could not before: run a project's
-- work — epics, stories, tasks, transitions, comments, spec links.
--
-- V1 ONLY EVER RUNS ON AN EMPTY DATABASE. This file does not, and neither did V2:
-- it runs against instances that already hold work_item rows people typed, and
-- section 6 migrates every one of them rather than asking anybody to re-enter it.
--
-- THE HIERARCHY IS EXPRESSED IN LEVELS, NOT IN TYPE NAMES (scope §4). issue_type
-- carries level EPIC/STORY/TASK and the rule is "a type may only parent to a type
-- one level above it". Story, Bug and Spike all sit at STORY, so all three hold
-- Tasks and all three may sit under an Epic — and adding Chore or Tech Debt at
-- STORY is a ROW, not a migration and not a service change. A rule written
-- against type NAMES would have to be edited in code the day a fourth
-- story-level type is wanted. Same reasoning issue_status follows by
-- categorising on `kind` rather than on a status's name (D2).
-- =============================================================================
```

- [ ] **Step 3: The vocabulary tables**

```sql
-- -----------------------------------------------------------------------------
-- 1. THE PROJECT'S VOCABULARY — types, statuses, and the legal moves between them
-- -----------------------------------------------------------------------------
--
-- All three are PER PROJECT and seeded by tcms_seed_project_defaults (section 3),
-- which is the same structural argument case_status, configuration and doc_space
-- already make in that function: without these rows a project cannot hold an
-- issue at all, so they are not "a default somebody chose" but the thing that
-- makes the table usable.

CREATE TABLE issue_type (
    id                         UUID    PRIMARY KEY,
    company_id                 UUID    NOT NULL REFERENCES company(id),
    project_id                 UUID    NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name                       TEXT    NOT NULL,
    level                      TEXT    NOT NULL CHECK (level IN ('EPIC','STORY','TASK')),
    counts_toward_completion   BOOLEAN NOT NULL DEFAULT true,
    icon_code                  TEXT    NOT NULL,
    "position"                 INTEGER NOT NULL,
    CONSTRAINT uq_issue_type_name UNIQUE (company_id, project_id, name)
);
COMMENT ON COLUMN issue_type.level IS
    'EPIC, STORY or TASK. THE DEPTH RULE IS ABOUT THIS COLUMN AND NEVER ABOUT name: a type may '
    'only parent to a type one level above it. Story, Bug and Spike all sit at STORY, so a fourth '
    'story-level type is a row rather than a migration (scope §4). D29 is why a type NAMED Task '
    'also sits at STORY — a one-person company managing its own product should not need an Epic '
    'and a Story before "a thing to do", and orphan TASK-level issues stay refused.';
COMMENT ON COLUMN issue_type.counts_toward_completion IS
    'FALSE for Spike and for every EPIC-level type. A spike is a time-boxed investigation that '
    'produces knowledge, not shippable work, so counting it would let an epic read 40% done having '
    'shipped nothing (D8). It still consumes hours and so still counts toward budget burn — the '
    'two numbers answer different questions and are ALLOWED TO DISAGREE.';
COMMENT ON COLUMN issue_type.icon_code IS
    'E, S, T, B or ? — what the type-icon chip renders. Stored rather than derived from the name, '
    'because a renamed type keeps its icon and a fifth type chooses one.';
CREATE INDEX ix_issue_type_project ON issue_type (company_id, project_id, "position");

CREATE TABLE issue_status (
    id         UUID    PRIMARY KEY,
    company_id UUID    NOT NULL REFERENCES company(id),
    project_id UUID    NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name       TEXT    NOT NULL,
    kind       TEXT    NOT NULL CHECK (kind IN ('NOT_STARTED','IN_PROGRESS','DONE')),
    "position" INTEGER NOT NULL,
    CONSTRAINT uq_issue_status_name UNIQUE (company_id, project_id, name)
);
COMMENT ON TABLE issue_status IS
    'The statuses ONE project uses, in order. D2: one canonical status per issue, and boards map '
    'statuses onto columns rather than owning them — which is what stops two boards over the same '
    'work from disagreeing about where a card is.';
COMMENT ON COLUMN issue_status.kind IS
    'What the status MEANS, so every percentage in the product can be computed without knowing '
    'what this project called its columns. EVERY completion figure is DONE over the counted total; '
    'IN_PROGRESS IS NOT PARTIAL CREDIT (D8) — a number that moves before anything ships is the '
    'number people learn to distrust, and once distrusted it is decoration. The same column '
    'case_status.kind already is for executions, for the same reason.';
CREATE INDEX ix_issue_status_project ON issue_status (company_id, project_id, "position");

CREATE TABLE issue_transition (
    company_id     UUID NOT NULL REFERENCES company(id),
    project_id     UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    from_status_id UUID NOT NULL REFERENCES issue_status(id) ON DELETE CASCADE,
    to_status_id   UUID NOT NULL REFERENCES issue_status(id) ON DELETE CASCADE,
    PRIMARY KEY (from_status_id, to_status_id),
    CONSTRAINT ck_issue_transition_distinct CHECK (from_status_id <> to_status_id)
);
COMMENT ON TABLE issue_transition IS
    'The legal moves. AN ALLOW-LIST, NOT A DENY-LIST: a pair that is not here is refused, so a '
    'status added to a workflow reaches nothing until somebody says where it goes. Story H5 — '
    '"only the moves the project workflow allows are offered", and a forbidden drop on the board '
    'is refused with the reason rather than snapping back in silence.';
COMMENT ON CONSTRAINT ck_issue_transition_distinct ON issue_transition IS
    'A status is always a legal destination from itself and the service says so without a row. '
    'Storing the self-pair would make "no moves allowed" and "only the current one" the same set.';
CREATE INDEX ix_issue_transition_from ON issue_transition (company_id, project_id, from_status_id);
```

- [ ] **Step 4: The issue table and its four edges**

```sql
-- -----------------------------------------------------------------------------
-- 2. THE ISSUE, AND WHY THE KEY IS TWO COLUMNS
-- -----------------------------------------------------------------------------

CREATE TABLE issue (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    key_seq                 BIGINT      NOT NULL,
    issue_key               TEXT        NOT NULL,
    type_id                 UUID        NOT NULL REFERENCES issue_type(id),
    status_id               UUID        NOT NULL REFERENCES issue_status(id),
    parent_id               UUID        REFERENCES issue(id) ON DELETE RESTRICT,
    title                   TEXT        NOT NULL,
    description             TEXT,
    acceptance_criteria     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    priority                TEXT        NOT NULL DEFAULT 'MEDIUM'
                            CHECK (priority IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    estimate_hours          NUMERIC(8,2),
    start_date              DATE,
    target_date             DATE,
    due_date                DATE,
    assignee_id             UUID        REFERENCES app_user(id),
    reporter_id             UUID        REFERENCES app_user(id),
    status_entered_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    created_idempotency_key TEXT,
    CONSTRAINT uq_issue_key UNIQUE (company_id, issue_key),
    CONSTRAINT ck_issue_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
COMMENT ON TABLE issue IS
    'ONE table for all three levels, with a self-referencing parent_id and an issue_type FK '
    '(scope §4). DELIBERATELY NOT JIRA''S SHAPE: there a Sub-task is a second-class citizen that '
    'cannot be estimated or moved independently. Here a Task is a first-class issue that happens '
    'to have a parent — which is what lets a dev and a QA each hold a Task under one Story.';
COMMENT ON COLUMN issue.key_seq IS
    'The number behind the key, from project_key_sequence via project.ProjectKeys. THE ONLY COLUMN '
    'THAT MAY ORDER ISSUE KEYS — sorting on issue_key puts ACME-100 before ACME-2, which is the '
    'defect this column exists to make unavailable rather than merely discouraged.';
COMMENT ON COLUMN issue.issue_key IS
    'ACME-124. STORED rather than composed on read from project.key || key_seq, and the reason is '
    'the same one doc_link.external_host records: what is rendered and what is typed into a commit '
    'message must not be derived from something that can move underneath it. settings-general.html '
    'promises "existing links keep working if it changes" — a composed key would silently rewrite '
    'every historical reference the moment a project was rekeyed.';
COMMENT ON COLUMN issue.parent_id IS
    'ON DELETE RESTRICT, not CASCADE. Deleting an epic that holds seventeen stories is either a '
    'cascade nobody asked for or seventeen orphans; the endpoint answers 409 and names the count. '
    'The depth rule this column carries is enforced in the service against issue_type.level, not '
    'by a constraint — a CHECK cannot reach through two rows to compare levels.';
COMMENT ON COLUMN issue.acceptance_criteria IS
    'An ordered array of {id, text, done}. JSONB rather than a table because nothing joins to a '
    'criterion, nothing reports on one, and the whole list is written by one form in one request — '
    'a table would buy referential integrity for an edge that has no other end.';
COMMENT ON COLUMN issue.estimate_hours IS
    'HOURS, NOT STORY POINTS (D8, decided 2026-08-30). Hours are directly comparable to '
    'time_entry actuals, so estimate-vs-actual and budget burn work with no conversion — which is '
    'what matters on contracted client work, where the estimate and the invoice have to be the '
    'same unit. THE KNOWN COST, recorded rather than discovered later: hours read as commitments '
    'and get quoted back at you, and D6 removed sprints, so there is NO VELOCITY to recalibrate '
    'them against. The mitigation is that every page naming a percentage also names its basis.';
COMMENT ON COLUMN issue.start_date IS
    'Epics only — a body of work has a span; a story has a due date. Null on every other level, '
    'and the service refuses to set it there rather than the column allowing a value nothing '
    'renders.';
COMMENT ON COLUMN issue.status_entered_at IS
    'When this issue entered its CURRENT status. Cycle time is unanswerable from a row that holds '
    'only the current status and when it was created (009 review R12), and this is the cheap half '
    'of the answer — the full history is audit_event, and issue_fact carries this forward for M9.';
COMMENT ON COLUMN issue.done_at IS
    'When it FIRST reached a DONE-kind status, and never cleared by a reopen. A reopened issue has '
    'been done once, and a lead time computed from a cleared column would silently lengthen every '
    'time somebody reopened something.';
COMMENT ON COLUMN issue.deleted_at IS
    'Soft delete, inherited from work_item and REQUIRED rather than stylistic. Scope §3: a '
    'downstream time_entry or invoice_line holds an id into this table WITH NO FOREIGN KEY, '
    'because the module rule forbids one. A hard delete orphans them silently; this is the '
    'mitigation, together with the consistency check in the nightly suite.';
CREATE UNIQUE INDEX issue_created_idempotency_key_idx
    ON issue (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
CREATE INDEX ix_issue_project_status ON issue (company_id, project_id, status_id)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_issue_parent ON issue (company_id, parent_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_issue_assignee ON issue (company_id, assignee_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_issue_project_seq ON issue (company_id, project_id, key_seq);

CREATE TABLE issue_comment (
    id         UUID        PRIMARY KEY,
    company_id UUID        NOT NULL REFERENCES company(id),
    issue_id   UUID        NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    edited_at  TIMESTAMPTZ
);
CREATE INDEX ix_issue_comment_issue ON issue_comment (company_id, issue_id, created_at);

CREATE TABLE issue_watcher (
    company_id UUID        NOT NULL REFERENCES company(id),
    issue_id   UUID        NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)
);
COMMENT ON TABLE issue_watcher IS
    'Who is following an issue (story H8). An author is added here when they comment, so a person '
    'who has said something is following it by default rather than having to also press a star.';
CREATE INDEX ix_issue_watcher_user ON issue_watcher (company_id, user_id);

CREATE TABLE issue_link (
    company_id  UUID NOT NULL REFERENCES company(id),
    issue_id    UUID NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    target_type TEXT NOT NULL,
    target_id   UUID NOT NULL,
    PRIMARY KEY (issue_id, target_type, target_id)
);
COMMENT ON TABLE issue_link IS
    'work_item_link''s successor, and the same shape for the same reason: a polymorphic reference '
    'across a module boundary, WITH NO FOREIGN KEY on target_id. Scope §3 — nearly everything in a '
    'PM system points at an issue, and tracker is not a foundation module, so the pointer is an id '
    'and the price is integrity enforced in application code. TEST_CASE is the target type story '
    'H7 needs and it costs one enum member; the rows arrive with M3.';
CREATE INDEX ix_issue_link_target ON issue_link (company_id, target_type, target_id);

CREATE TABLE issue_doc_link (
    id                  UUID    PRIMARY KEY,
    company_id          UUID    NOT NULL REFERENCES company(id),
    issue_id            UUID    NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    target_kind         TEXT    NOT NULL,
    relationship        TEXT    NOT NULL,
    page_id             UUID,
    page_key            TEXT,
    page_title          TEXT,
    block_id            UUID,
    block_excerpt       TEXT,
    stale               BOOLEAN NOT NULL,
    external_repo       TEXT,
    external_path       TEXT,
    external_line_start INTEGER,
    external_line_end   INTEGER,
    external_commit_sha TEXT,
    external_url        TEXT,
    external_provider   TEXT,
    external_issue_id   TEXT,
    external_host       TEXT,
    CONSTRAINT ck_issue_doc_link_target CHECK ((target_kind = 'DOC_BLOCK' AND page_id IS NOT NULL AND page_key IS NOT NULL AND page_title IS NOT NULL AND block_id IS NOT NULL)
        OR (target_kind = 'EXTERNAL_GIT' AND external_path IS NOT NULL AND external_commit_sha IS NOT NULL)
        OR (target_kind = 'EXTERNAL_URL' AND external_url IS NOT NULL)
        OR (target_kind = 'EXTERNAL_ISSUE' AND external_issue_id IS NOT NULL AND external_host IS NOT NULL AND external_url IS NOT NULL))
);
COMMENT ON TABLE issue_doc_link IS
    'A read model in `tracker`, mirroring doc_link from the issue end — case_doc_link field for '
    'field, INCLUDING stale. Written only by DocLinkProjection, replaced whole per company on '
    'every refresh. doc_link is the source of truth; this is what the epic screen may read without '
    'naming another module''s tables on a request path (§3 lesson 4). THE WRITE LIVES IN '
    'documentation — POST …/blocks/{blockId}/links with sourceType ISSUE — because that module '
    'owns doc_link and tracker may not import it.';
COMMENT ON COLUMN issue_doc_link.block_excerpt IS
    'The first 200 characters of the block''s text WHEN IT WAS LINKED, with a trailing ellipsis '
    'when it was cut. Not the current text — that is the entire point of `stale`: story H1 says an '
    'epic linked to a spec block should say so when the spec moves underneath it, and '
    're-confirming replaces this with the current text and clears the flag.';
COMMENT ON COLUMN issue_doc_link.stale IS
    'The linked page changed after this issue last did — newest doc_page_version against '
    'issue.updated_at. PAGE granularity, not block: doc_block carries no timestamp, so an edit '
    'elsewhere on the page raises it too. ALWAYS FALSE FOR AN EXTERNAL TARGET, for '
    'case_doc_link.stale''s two reasons — a git range is PINNED by its sha, and an external issue '
    'carries no derived meaning for a freshness marker to be about.';
CREATE INDEX ix_issue_doc_link_issue ON issue_doc_link (company_id, issue_id);
CREATE INDEX ix_issue_doc_link_block ON issue_doc_link (company_id, block_id);
```

- [ ] **Step 5: `issue_fact`, with M9's four timestamps**

```sql
-- -----------------------------------------------------------------------------
-- 3. issue_fact — THE PROJECTION EVERY DOWNSTREAM MODULE READS
-- -----------------------------------------------------------------------------
--
-- Scope §3: there is no sixth foundation module, and tracker holds the largest
-- domain concept in the product so it will never be one. Downstream modules
-- reference an issue POLYMORPHICALLY and keep their own projection — time_entry
-- carries (subject_type, subject_id), billing joins this, reporting joins this.
--
-- THE TRAP THIS TABLE EXISTS TO AVOID, stated at the schema so nobody has to find
-- it in a document: issue_fact MUST BE FED BY AN EVENT AND NEVER WRITTEN BY time
-- OR billing. The moment a downstream module writes it, tracker is no longer the
-- authority on issue state and the two disagree SILENTLY. One writer:
-- tracker.IssueFactProjection, on the tracker.issues_changed outbox event.
--
-- THE FOUR TIMESTAMPS ARE M9'S SCHEMA, LANDING HERE (009 review R12). M9 was
-- promised it would need no migration, and that promise is only keepable if the
-- columns cycle time and "scope added after the release was created" need are
-- written from the start. created_at, status_entered_at, done_at and
-- release_assigned_at are unanswerable from a projection holding only the current
-- status — and backfilling them later is impossible, because the events that
-- would have populated them have already happened.

CREATE TABLE issue_fact (
    issue_id             UUID        PRIMARY KEY,
    company_id           UUID        NOT NULL,
    project_id           UUID        NOT NULL,
    issue_key            TEXT        NOT NULL,
    title                TEXT        NOT NULL,
    type_name            TEXT        NOT NULL,
    type_level           TEXT        NOT NULL,
    counts_toward_completion BOOLEAN NOT NULL,
    status_name          TEXT        NOT NULL,
    status_kind          TEXT        NOT NULL,
    epic_id              UUID,
    parent_id            UUID,
    assignee_id          UUID,
    estimate_hours       NUMERIC(8,2),
    release_id           UUID,
    created_at           TIMESTAMPTZ NOT NULL,
    status_entered_at    TIMESTAMPTZ NOT NULL,
    done_at              TIMESTAMPTZ,
    release_assigned_at  TIMESTAMPTZ,
    deleted_at           TIMESTAMPTZ
);
COMMENT ON TABLE issue_fact IS
    'Event-fed read projection of issue state, for reporting, time and billing to join WITHOUT '
    'importing tracker. Deliberately duplicates dimensions to avoid joins, exactly as '
    'execution_fact does. ONE WRITER — tracker.IssueFactProjection. A downstream module writing '
    'here is the failure scope §3 names by name.';
COMMENT ON COLUMN issue_fact.release_id IS
    'NULL until M3, which is when a release exists to point at. The column is here rather than in '
    'V5 because release_assigned_at beside it is the "scope added after the release was created" '
    'measure, and a timestamp cannot be backfilled for assignments that already happened.';
COMMENT ON COLUMN issue_fact.release_assigned_at IS
    'When this issue was FIRST pointed at its current release. The burn-up''s scope line is the '
    'difference between this and release.created_at (M9), and it is the one number in that chart '
    'that cannot be reconstructed after the fact.';
COMMENT ON COLUMN issue_fact.deleted_at IS
    'Mirrors issue.deleted_at rather than the row being removed, because reporting over a closed '
    'period must not change when somebody tidies the backlog afterwards.';
CREATE INDEX ix_issue_fact_project ON issue_fact (company_id, project_id, status_kind);
CREATE INDEX ix_issue_fact_epic ON issue_fact (company_id, epic_id);
CREATE INDEX ix_issue_fact_release ON issue_fact (company_id, release_id);
```

- [ ] **Step 6: Turn RLS on, in V1's exact shape**

```sql
-- -----------------------------------------------------------------------------
-- 4. FORCED RLS ON ALL NINE
-- -----------------------------------------------------------------------------
--
-- V1's loop, restated for the nine new tables. FORCE means the policy applies to
-- the table's owner too, which is what makes the backstop real rather than a
-- property of which role happens to connect. The predicate is byte-identical to
-- V1's — SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares
-- them and fails on a divergent one, which is exactly the check that catches a
-- policy retyped from memory.

DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY[
        'issue', 'issue_type', 'issue_status', 'issue_transition', 'issue_link',
        'issue_comment', 'issue_watcher', 'issue_doc_link', 'issue_fact'
    ];
BEGIN
    FOREACH t IN ARRAY protected LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY company_isolation ON %I USING '
            '(company_id = nullif(current_setting(''app.current_company'', true), '''')::uuid)', t);
    END LOOP;

    IF array_length(protected, 1) <> 9 THEN
        RAISE EXCEPTION 'expected 9 new company-isolated tables, found %',
            array_length(protected, 1);
    END IF;
END $$;
```

- [ ] **Step 7: Amend the ERD**

In `docs/specs/2026-07-30-tcms-erd.md`, add a `tracker` section listing the nine tables against
§0.1's rule, and record the two design decisions the ERD is authoritative for: the level-based
depth rule and `issue_fact`'s single writer. Nothing in the existing ERD is contradicted, so
nothing is struck through here.

- [ ] **Step 8: Run the schema conventions**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.schema.SchemaConventionTest'
```

Expected: PASS — all five methods. The test discovers company-owned tables rather than listing
them, so nine new ones need no edit to it. If `companyIdLeadsCompositeIndexes` names one of the
nine, the index is written with `company_id` second; fix the index, not the test.

- [ ] **Step 9: Commit**

```bash
cd ~/RiderProjects/tcms
git add backend/src/main/resources/db/migration/V3__tracker_core.sql docs/specs/2026-07-30-tcms-erd.md
git commit -m "feat(db): V3 — the nine tracker tables

The depth rule is expressed in issue_type.level and never in a type name, so a
fourth story-level type is a row rather than a migration. issue_fact carries M9's
four timestamps because they cannot be backfilled for events that already happened,
and it has exactly one writer — a downstream module writing it is the silent
disagreement scope §3 names."
```

---

#### Part C — the seeded workflow and the backfill

**Deliverable:** every project — the ones that already exist and every one created afterwards —
has the six default statuses with their kinds, the transitions between them, and the five issue
types.
**Depends on:** Part B

**Files:**
- Modify: `backend/src/main/resources/db/migration/V3__tracker_core.sql` (append section 5)
- Modify: `backend/src/test/java/com/tcms/tenancy/ProjectKeySequenceSchemaTest.java`
- Create: `backend/src/test/java/com/tcms/tracker/WorkflowSeedTest.java`

**Read `V2`'s section 3 before writing this.** `tcms_seed_project_defaults` is reproduced there in
full under `CREATE OR REPLACE`; this reproduces it again with one more block. **Every block V2 has
must appear here** — the Feature dimension, the seven case statuses, the Default configuration, the
documentation space and the `project_key_sequence` counter — or the ones omitted silently stop
being seeded for every project created afterwards. That is the cost D-b accepted.

- [ ] **Step 1: Append the function, with its new block**

```sql
-- -----------------------------------------------------------------------------
-- 5. THE SEEDED WORKFLOW — one more block in tcms_seed_project_defaults
-- -----------------------------------------------------------------------------
--
-- WHY HERE AND NOT IN A ProjectCreatedEvent, which plan 009 M1 and review R5 both
-- called for (plan 011 decision D-b):
--
--   * A Java event TYPE may not cross a module boundary in this codebase at all —
--     CompanyScopedEvent's own header says so. Cross-module delivery is an
--     OutboxEvents string plus a JSONB payload, so a shared ProjectCreatedEvent
--     class cannot exist.
--   * Outbox delivery is ASYNCHRONOUS and its floor is a minute:
--     tcms.outbox.interval is PT1M and OutboxScheduler adds a PT1M initial delay.
--     A workflow seeded that way leaves a brand-new project with no statuses for
--     up to a minute — its first issue cannot be created, and story B1's "a
--     sensible default is already there so I can start without configuring
--     anything" is false for exactly that long.
--
-- This function is the mechanism that already solves this shape, and the
-- precedent is not thin: it seeds `configuration` (an execution table) and
-- `doc_space` (a documentation table) synchronously, in the creating
-- transaction, and ALL THREE project-creation paths call it —
-- ProjectCreateService, CompanyProvisioningService (twice), and seed-demo-data
-- through them. A fourth creation path gets the workflow free by calling it.
--
-- THE COST, stated because it is real: the whole function is restated here, and a
-- block omitted silently stops being seeded. ProjectKeySequenceSchemaTest is the
-- check that a block went missing, and this migration extends it to the workflow.

CREATE OR REPLACE FUNCTION tcms_seed_project_defaults(p_company_id UUID, p_project_id UUID)
    RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_spec   RECORD;
    v_status RECORD;
BEGIN
    PERFORM tcms_assert_company_context(p_company_id);

    IF NOT EXISTS (SELECT 1 FROM project WHERE id = p_project_id) THEN
        RAISE EXCEPTION 'project % is not visible in company %', p_project_id, p_company_id
            USING ERRCODE = '23503';
    END IF;

    -- ==== V1 blocks 1-4, REPRODUCED VERBATIM FROM V2. Do not summarise them. ====
    -- [T2 Part C step 1a: copy blocks 1-4 out of V2 exactly — the Feature dimension, the
    --  seven case statuses, the Default configuration and the documentation space,
    --  WITH THEIR COMMENTS. They are not restated in this plan on purpose: a
    --  paraphrase is how a block loses a WHERE NOT EXISTS clause.]

    -- ==== V2 block 5, the per-project issue-key counter. Copy it too. ====

    -- ==== V3. The default workflow (D2, story B1). =========================
    --
    -- SIX STATUSES, and the set is the one settings-workflow.html renders. A
    -- brand-new project arrives with exactly this so nobody has to configure
    -- anything before creating their first issue — which is B1's second
    -- criterion, and the reason this is structural rather than "a default
    -- somebody chose".
    --
    -- Blocked is IN_PROGRESS, not a fourth kind. Something blocked is started and
    -- not done; a kind of its own would have to be given a meaning by every
    -- percentage in the product, and the honest answer is that it has none.
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('Backlog',     'NOT_STARTED', 0),
            ('Ready',       'NOT_STARTED', 1),
            ('In Progress', 'IN_PROGRESS', 2),
            ('In Review',   'IN_PROGRESS', 3),
            ('Blocked',     'IN_PROGRESS', 4),
            ('Done',        'DONE',        5)
        ) AS s(name, kind, position)
    LOOP
        INSERT INTO issue_status (id, company_id, project_id, name, kind, "position")
        SELECT gen_random_uuid(), p_company_id, p_project_id, v_spec.name, v_spec.kind, v_spec.position
         WHERE NOT EXISTS (
            SELECT 1 FROM issue_status
             WHERE company_id = p_company_id AND project_id = p_project_id AND name = v_spec.name);
    END LOOP;

    -- The legal moves. AN ALLOW-LIST: every pair not inserted here is refused.
    --
    -- The shape is "anything may go anywhere except INTO Done, which only In
    -- Review reaches" — settings-workflow.html's right-hand panel states exactly
    -- that: from In Progress an issue may go to In Review, Blocked or Ready, and
    -- "Not to Done — a review gate stands between them". Backlog is reachable
    -- from everywhere so a mis-filed issue can be put back.
    FOR v_spec IN
        SELECT f.id AS from_id, t.id AS to_id
          FROM issue_status f
          JOIN issue_status t
            ON t.project_id = f.project_id AND t.id <> f.id
         WHERE f.company_id = p_company_id AND f.project_id = p_project_id
           AND (t.name <> 'Done' OR f.name = 'In Review')
    LOOP
        INSERT INTO issue_transition (company_id, project_id, from_status_id, to_status_id)
        SELECT p_company_id, p_project_id, v_spec.from_id, v_spec.to_id
         WHERE NOT EXISTS (
            SELECT 1 FROM issue_transition
             WHERE from_status_id = v_spec.from_id AND to_status_id = v_spec.to_id);
    END LOOP;

    -- FIVE TYPES ACROSS THREE LEVELS, and note that TWO of them are named Task.
    --
    -- D29: the depth rule stands — a TASK-level issue needs a story-level parent —
    -- but the catalogue seeds a STORY-level type ALSO CALLED Task, so a solo or
    -- internal project never needs an Epic and a Story before "a thing to do".
    -- D12 amended: migrated work_item rows become the STORY-level one, so a user
    -- who typed "task" still sees "task". Orphan TASK-level issues stay refused.
    --
    -- Spike is counts_toward_completion = false (D8). Epic is false because an
    -- epic is what completion is computed FOR, never a member of the count.
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('Epic',  'EPIC',  false, 'E', 0),
            ('Story', 'STORY', true,  'S', 1),
            ('Task',  'STORY', true,  'T', 2),
            ('Bug',   'STORY', true,  'B', 3),
            ('Spike', 'STORY', false, '?', 4),
            ('Sub-task', 'TASK', true, 'T', 5)
        ) AS s(name, level, counts, icon, position)
    LOOP
        INSERT INTO issue_type
            (id, company_id, project_id, name, level, counts_toward_completion, icon_code, "position")
        SELECT gen_random_uuid(), p_company_id, p_project_id, v_spec.name, v_spec.level,
               v_spec.counts, v_spec.icon, v_spec.position
         WHERE NOT EXISTS (
            SELECT 1 FROM issue_type
             WHERE company_id = p_company_id AND project_id = p_project_id AND name = v_spec.name);
    END LOOP;
END $$;
```

**On the sixth type.** `settings-general.html` lists five — Epic, Story, Task, Bug, Spike — and
`story.html` calls a story's children "Sub-tasks" while `issue-new.html` offers "Add sub-task".
The mockup's five-row list and its own sub-task affordance cannot both be true with one `Task`
type, because D29 puts `Task` at `STORY` level. So there are **six rows and two of them render as
`T`**: `Task` at `STORY` for the quick-add case, `Sub-task` at `TASK` for the breakdown case.
**Recorded as a disagreement with `settings-general.html`**, resolved in the direction the depth
rule requires; the settings screen (T19) lists all six.

- [ ] **Step 2: Backfill every project that already exists**

```sql
-- The backfill, and it is a loop for V2's reason — read V2's section 1 before
-- changing it. Flyway connects as tcms_owner, which holds NO BYPASSRLS, so a
-- plain INSERT … SELECT FROM project with app.current_company unset reads ZERO
-- ROWS and reports success. set_config's third argument is transaction-local.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT id, company_id FROM project LOOP
        PERFORM set_config('app.current_company', r.company_id::text, true);
        PERFORM tcms_seed_project_defaults(r.company_id, r.id);
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END $$;
```

Reusing the function rather than restating its inserts is the point: **one definition of what a
seeded project contains**, which is the rule `ProjectCreateService`'s javadoc states and the reason
it calls the function instead of inlining. It is idempotent, so a project that somehow already had
statuses keeps them.

- [ ] **Step 3: Extend the "a block went missing" test**

`ProjectKeySequenceSchemaTest` asserts that `tcms_seed_project_defaults` still seeds every block.
Add the workflow to it. **This test is the whole safety net under D-b's accepted cost.**

```java
    @Test
    void theSeedFunctionStillSeedsEveryBlock() {
        UUID projectId = createProjectThroughTheService();

        // Each of these is a block of tcms_seed_project_defaults. V3 restated the
        // whole function under CREATE OR REPLACE to add the workflow, and a block
        // dropped in that restatement is invisible until somebody opens a screen
        // that needs it — which is why this asserts all seven rather than the one
        // the newest migration added.
        assertThat(countRows("classification_dimension", projectId)).isEqualTo(1);
        assertThat(countRows("case_status", projectId)).isEqualTo(7);
        assertThat(countRows("configuration", projectId)).isEqualTo(1);
        assertThat(countRows("doc_space", projectId)).isEqualTo(1);
        assertThat(countRows("project_key_sequence", projectId)).isEqualTo(1);
        assertThat(countRows("issue_status", projectId)).isEqualTo(6);
        assertThat(countRows("issue_type", projectId)).isEqualTo(6);
    }
```

- [ ] **Step 4: Write the seed test**

Create `backend/src/test/java/com/tcms/tracker/WorkflowSeedTest.java`:

```java
    @Test
    void everyProjectThatExistedBeforeTheMigrationHasTheSixStatuses() {
        // The container's database has been migrated by V1..V3, and the demo seed
        // creates projects, so this asserts over whatever the fixture holds rather
        // than over a project this test made — which is what makes it a statement
        // about the BACKFILL and not about the create path.
        List<UUID> projects = allProjectIds();
        assertThat(projects).isNotEmpty();

        for (UUID projectId : projects) {
            assertThat(statusNamesOf(projectId))
                    .as("project %s", projectId)
                    .containsExactly("Backlog", "Ready", "In Progress", "In Review", "Blocked", "Done");
        }
    }

    @Test
    void everyStatusCarriesItsKind() {
        UUID projectId = anyProjectId();

        assertThat(kindsByName(projectId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Backlog", "NOT_STARTED",
                "Ready", "NOT_STARTED",
                "In Progress", "IN_PROGRESS",
                "In Review", "IN_PROGRESS",
                "Blocked", "IN_PROGRESS",
                "Done", "DONE"));
    }

    @Test
    void doneIsReachableOnlyFromInReview() {
        UUID projectId = anyProjectId();
        UUID done = statusId(projectId, "Done");

        assertThat(namesOfStatusesThatReach(projectId, done)).containsExactly("In Review");
    }

    @Test
    void aProjectCreatedAfterTheMigrationGetsTheWorkflowInTheSameTransaction() {
        // The point of D-b: not "eventually" — in the creating transaction, so an
        // issue can be created in the very next request. An outbox-fed seed would
        // pass an eventual assertion and fail this one.
        UUID projectId = createProjectThroughTheService();

        assertThat(statusNamesOf(projectId)).hasSize(6);
        assertThat(typeNamesOf(projectId))
                .containsExactlyInAnyOrder("Epic", "Story", "Task", "Bug", "Spike", "Sub-task");
    }
```

- [ ] **Step 5: Run them**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tracker.WorkflowSeedTest' --tests 'com.tcms.tenancy.ProjectKeySequenceSchemaTest'
```

Expected: PASS, all six methods.

- [ ] **Step 6: Commit**

```bash
cd ~/RiderProjects/tcms
git add backend/src/main/resources/db/migration/V3__tracker_core.sql backend/src/test/java/com/tcms/tracker/WorkflowSeedTest.java backend/src/test/java/com/tcms/tenancy/ProjectKeySequenceSchemaTest.java
git commit -m "feat(db): the default workflow, seeded and backfilled

tcms_seed_project_defaults gains the six statuses, their transitions and the six
types — not a ProjectCreatedEvent, which 009 called for and which cannot work: an
event type may not cross a module boundary here, and outbox delivery has a
one-minute floor, so a project would have no statuses for as long. The function is
synchronous, transactional and already called by all three creation paths.

The cost is the restatement, and ProjectKeySequenceSchemaTest now asserts all seven
blocks rather than the one the newest migration added."
```

---

#### Part D — `work_item` → `issue`

**Deliverable:** every pre-upgrade `work_item` is an `issue` of the story-level `Task` type with a
key, `work_item_link` rows are `issue_link` rows, both tables are dropped, and a test counts the
rows on each side rather than reading the SQL.
**Depends on:** Part C

**Files:**
- Modify: `backend/src/main/resources/db/migration/V3__tracker_core.sql` (append section 6)
- Create: `backend/src/test/java/com/tcms/tracker/WorkItemMigrationTest.java`

- [ ] **Step 1: Append the migration**

```sql
-- -----------------------------------------------------------------------------
-- 6. work_item → issue, AS PARENTLESS STORIES
-- -----------------------------------------------------------------------------
--
-- D12, and it was DETERMINED rather than chosen, once the code was read:
-- work_item.kind is written exactly once and always 'TASK' (WorkItem.java:170),
-- and WorkItemWriteRequest deliberately carries NO kind field — a client cannot
-- set one, because doing so "would let a client POST EXECUTION and so materialise
-- a derived card". So every existing row is a parentless TASK, and NO USER EVER
-- CHOSE THAT WORD.
--
-- Under scope §4 a TASK-level issue requires a story-level parent and these have
-- none, so they migrate to the STORY level — onto the type D29 names `Task`, so a
-- user who typed "task" still sees "task" (settings-general.html says this out
-- loud: "Tasks created before the upgrade are still called Task. Nothing you
-- typed changed meaning underneath you").
--
-- RELAXING THE DEPTH RULE TO PERMIT ORPHAN TASKS WAS REJECTED. It would apply to
-- every future task, not just to migrated rows, and would weaken the one rule
-- that makes Epic → Story → Task mean anything — to accommodate a label the user
-- never picked.
--
-- STATUS MAPPING. work_item.status is the five-member WorkItemStatus enum and the
-- seeded workflow has six names; four map by meaning and TODO maps to Backlog
-- rather than to Ready. Backlog is the more conservative destination: Ready
-- claims the issue is refined and estimated, which nothing about a pre-upgrade
-- task supports, and story B1 calls Backlog "the default for a new issue".
--
-- KEYS. Every migrated row gets one, from the project's own counter, in
-- work_item.created_at order — so the oldest task in a project is ACME-1 and the
-- numbering reads like a history rather than like a shuffle. The counter is then
-- advanced past them, which is why this runs AFTER section 5's backfill created
-- the counter rows.

DO $$
DECLARE
    r         RECORD;
    v_seq     BIGINT;
    v_prefix  TEXT;
BEGIN
    FOR r IN SELECT DISTINCT company_id, project_id FROM work_item LOOP
        PERFORM set_config('app.current_company', r.company_id::text, true);
        SELECT p.key INTO v_prefix FROM project p WHERE p.id = r.project_id;

        FOR v_seq IN
            SELECT 1
        LOOP EXIT; END LOOP;

        -- One statement per project, numbering in created_at order. next_value is
        -- read and advanced ONCE per project rather than per row: the UPDATE takes
        -- the row lock either way, and a loop over rows would take it N times for
        -- no additional guarantee.
        WITH numbered AS (
            SELECT w.id,
                   row_number() OVER (ORDER BY w.created_at, w.id) AS n
              FROM work_item w
             WHERE w.company_id = r.company_id AND w.project_id = r.project_id
        ),
        reserved AS (
            UPDATE project_key_sequence s
               SET next_value = s.next_value + (SELECT count(*) FROM numbered)
             WHERE s.company_id = r.company_id AND s.project_id = r.project_id
            RETURNING s.next_value - (SELECT count(*) FROM numbered) AS first_value
        )
        INSERT INTO issue (
            id, company_id, project_id, key_seq, issue_key, type_id, status_id,
            parent_id, title, description, priority, due_date, assignee_id,
            reporter_id, status_entered_at, done_at, created_at, created_by,
            updated_at, updated_by, deleted_at, created_idempotency_key)
        SELECT w.id,
               w.company_id,
               w.project_id,
               (SELECT first_value FROM reserved) + numbered.n - 1,
               v_prefix || '-' || ((SELECT first_value FROM reserved) + numbered.n - 1),
               (SELECT t.id FROM issue_type t
                 WHERE t.company_id = w.company_id AND t.project_id = w.project_id
                   AND t.name = 'Task' AND t.level = 'STORY'),
               (SELECT st.id FROM issue_status st
                 WHERE st.company_id = w.company_id AND st.project_id = w.project_id
                   AND st.name = CASE w.status
                                     WHEN 'TODO'        THEN 'Backlog'
                                     WHEN 'IN_PROGRESS' THEN 'In Progress'
                                     WHEN 'IN_REVIEW'   THEN 'In Review'
                                     WHEN 'BLOCKED'     THEN 'Blocked'
                                     WHEN 'DONE'        THEN 'Done'
                                 END),
               NULL,                     -- parentless, by D12
               w.title,
               w.description,
               w.priority,
               w.due_date,
               w.assignee_id,
               w.created_by,             -- the reporter is whoever raised it
               w.updated_at,             -- status_entered_at: the best available truth
               CASE WHEN w.status = 'DONE' THEN w.updated_at END,
               w.created_at,
               w.created_by,
               w.updated_at,
               w.updated_by,
               w.deleted_at,
               w.created_idempotency_key
          FROM work_item w
          JOIN numbered ON numbered.id = w.id
         WHERE w.company_id = r.company_id AND w.project_id = r.project_id;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END $$;

-- THE ID IS CARRIED, NOT MINTED. A migrated issue keeps work_item.id, so a
-- derived_item_annotation row pointing at it — a person's private note and
-- personal due date — still resolves. Minting new ids would silently orphan every
-- one of them, and derived_item_annotation is the table D11 says matters MORE
-- after this module, not less.
COMMENT ON COLUMN issue.id IS
    'Carried from work_item for a migrated row, so derived_item_annotation''s (source_type, '
    'source_id) still resolves. That table survives M1 (D11) and its rows are somebody''s private '
    'notes — a fresh id here would have orphaned every one of them silently.';

INSERT INTO issue_link (company_id, issue_id, target_type, target_id)
SELECT company_id, work_item_id, target_type, target_id FROM work_item_link;

-- derived_item_annotation rows keep pointing at the same ids; its source_type
-- vocabulary is free text and 'TASK' rows are re-labelled rather than left to rot.
UPDATE derived_item_annotation SET source_type = 'ISSUE' WHERE source_type = 'TASK';

DROP TABLE work_item_link;
DROP TABLE work_item;
```

- [ ] **Step 2: Write the count test**

009's done-when is explicit: *"the `work_item` migration verified by counting rows either side, not
by reading the SQL"*. The table is gone by the time a test runs, so the count is taken **before**
`V3` by running `V1` and `V2` alone into a second container.

Create `backend/src/test/java/com/tcms/tracker/WorkItemMigrationTest.java`:

```java
/**
 * Counts the rows on each side of the work_item → issue migration.
 *
 * <p>The point of doing it this way rather than by reading V3: the migration is one
 * {@code INSERT … SELECT} inside a {@code DO} block over a cursor, and the failure mode that
 * matters — a project whose rows silently did not move, because the status CASE had no arm for a
 * value that table actually holds — produces a shorter result set and no error at all. A reading
 * of the SQL cannot find that; a count on both sides can.
 */
class WorkItemMigrationTest {

    @Test
    void everyWorkItemBecameAnIssue() throws Exception {
        // A container migrated to V2 only, seeded with work_item rows in three
        // projects across two companies and every one of the five statuses.
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "2");
            seedWorkItems(db);
            long before = countAllRows(db, "work_item");
            long linksBefore = countAllRows(db, "work_item_link");
            assertThat(before).isGreaterThan(0);

            migrateTo(db, "3");

            assertThat(countAllRows(db, "issue")).isEqualTo(before);
            assertThat(countAllRows(db, "issue_link")).isEqualTo(linksBefore);
        }
    }

    @Test
    void everyMigratedIssueHasAKeyAndTheStoryLevelTaskType() throws Exception {
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "2");
            seedWorkItems(db);
            migrateTo(db, "3");

            // No key is null, no key is duplicated inside a company, and every one
            // carries its project's prefix.
            assertThat(rowsWhere(db, "issue", "issue_key IS NULL")).isZero();
            assertThat(distinctCount(db, "issue", "company_id, issue_key"))
                    .isEqualTo(countAllRows(db, "issue"));
            assertThat(rowsWhere(db, "issue i JOIN project p ON p.id = i.project_id",
                    "i.issue_key NOT LIKE p.key || '-%'")).isZero();

            // D12 and D29: the story-level type named Task, so "nothing you typed
            // changed meaning underneath you".
            assertThat(typeNamesAndLevelsOf(db, "issue"))
                    .containsOnly(entry("Task", "STORY"));
        }
    }

    @Test
    void keysAreNumberedOldestFirstAndTheCounterMovedPastThem() throws Exception {
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "2");
            seedWorkItems(db);
            migrateTo(db, "3");

            // The oldest task in each project is -1, and the counter's next_value
            // is one past the highest key_seq — so the first issue created after
            // the upgrade does not collide with a migrated one.
            for (UUID projectId : projectIdsWithIssues(db)) {
                assertThat(keySeqInCreatedAtOrder(db, projectId)).isSorted();
                assertThat(nextValueOf(db, projectId)).isEqualTo(maxKeySeq(db, projectId) + 1);
            }
        }
    }

    @Test
    void aPrivateNoteOnAMigratedTaskStillResolves() throws Exception {
        // D11: derived_item_annotation survives and matters more, not less. Its
        // rows are keyed on (user_id, source_type, source_id), so the migration
        // carrying work_item.id forward is what keeps them pointing at something.
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "2");
            seedWorkItems(db);
            UUID annotated = annotateSomeWorkItem(db);
            migrateTo(db, "3");

            assertThat(rowsWhere(db,
                    "derived_item_annotation a JOIN issue i ON i.id = a.source_id",
                    "a.source_id = '" + annotated + "' AND a.source_type = 'ISSUE'")).isEqualTo(1);
        }
    }

    @Test
    void everyWorkItemStatusHasAnArmInTheMapping() throws Exception {
        // The silent one. A status with no CASE arm produces a NULL status_id, the
        // NOT NULL refuses the insert, and the whole migration fails loudly — but
        // only if a row with that status exists in the fixture. This asserts the
        // fixture covers all five so the loud failure is reachable.
        assertThat(seededStatuses()).containsExactlyInAnyOrder(
                "TODO", "IN_PROGRESS", "IN_REVIEW", "BLOCKED", "DONE");
    }
}
```

- [ ] **Step 3: Run it**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tracker.WorkItemMigrationTest'
```

Expected: PASS, five methods. This test starts its own containers, so it is slower than the rest —
that is the price of asserting over a table the ordinary fixture no longer has.

- [ ] **Step 4: Commit**

```bash
cd ~/RiderProjects/tcms
git add backend/src/main/resources/db/migration/V3__tracker_core.sql backend/src/test/java/com/tcms/tracker/WorkItemMigrationTest.java
git commit -m "feat(db): work_item becomes issue, as parentless Stories

Every existing row is a parentless TASK — kind is written once, always 'TASK', and
the write request has no field for it — so no user ever chose that word. They move
to the story-level type D29 names Task, keeping their id so a private note on one
still resolves, and are numbered oldest-first from the project's own counter.

Verified by counting rows either side on a container migrated to V2, not by reading
the SQL: a project whose rows silently did not move produces a shorter result set
and no error."
```


---

### T3 — `be` · The `tracker` module: issues, workflow, completion and `issue_fact`

**Agent:** `be-java` (with `be-java-database-code`, `be-java-unit-testing`) · **Stack:** `be` ·
**Budget:** ~145k — on the cap. **Seam if it runs out:** land Part A, commit, do Part B next.

#### Part A — entities, issue CRUD, the workflow and transitions

**Deliverable:** `POST /issues` returns `201` with a key allocated from `ProjectKeys`;
`GET /issues/{key}` returns the issue with its children and legal moves; a move the workflow
forbids answers `409`; the real Spring context boots against `V3` with `ddl-auto: validate` green.
**Depends on:** T2

**Files:**
- Create: `backend/src/main/java/com/tcms/tracker/` — `package-info.java`, `Issue.java`,
  `IssueType.java`, `IssueStatus.java`, `IssueTransition.java`, `IssueRepository.java`,
  `IssueTypeRepository.java`, `IssueStatusRepository.java`, `IssueTransitionRepository.java`,
  `IssueEdit.java`, `IssueService.java`, `IssueController.java`, `TransitionService.java`,
  `TransitionController.java`, `WorkflowService.java`, `WorkflowController.java`,
  `IssueView.java`, `IssueSummaryView.java`, `IssueTypeView.java`, `IssueStatusView.java`,
  `WorkflowView.java`
- Modify: `backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java:525-536`
- Test: `backend/src/test/java/com/tcms/tracker/IssueEditTest.java`,
  `IssueEndpointTest.java`, `TransitionEndpointTest.java`, `WorkflowEndpointTest.java`

- [ ] **Step 1: Declare the module**

`backend/src/main/java/com/tcms/tracker/package-info.java`:

```java
/**
 * Tracker — epics, stories, tasks, and the workflow they move through (009 module M1).
 *
 * <h2>This is not, and will never be, a foundation module</h2>
 *
 * <p>{@code ArchitectureRules.FOUNDATION_MODULES} is {@code {api, tenancy, identity, project,
 * storage}} and its javadoc states the bar a sixth must clear. Two clauses are decisive: a
 * foundation module <em>holds no domain concept</em>, and it <em>answers a question the database
 * cannot</em>. This module holds the largest domain concept in the product. It fails both outright,
 * and §76 already refused a {@code review} module on the same bar — <em>a foundation module is a
 * decision, not an import</em>.
 *
 * <p>That is a real constraint, because in a project-management system nearly everything points at
 * an issue: a time entry, an invoice line, a board card, a release membership, a document link. So
 * <strong>downstream modules reference an issue polymorphically and keep their own
 * projection</strong> (scope §3). {@code time_entry} will carry {@code (subject_type, subject_id)};
 * {@code reporting} and {@code billing} join {@link IssueFactProjection}'s table. None of that
 * machinery is new — {@code work_item_link}, {@code doc_link}, {@code case_doc_link} and
 * {@code execution_fact} already pay exactly this price.
 *
 * <p><strong>What it costs, stated plainly:</strong> referential integrity between a time entry and
 * its issue is enforced in application code, not by the database. A deleted issue can orphan one.
 * The mitigation is soft delete ({@code issue.deleted_at}) plus a consistency check in the nightly
 * suite.
 *
 * <h2>The trap at the centre of the projection</h2>
 *
 * <p>{@code issue_fact} <strong>must be fed by an event and never written by {@code time} or
 * {@code billing}.</strong> The moment a downstream module writes it, this module is no longer the
 * authority on issue state and the two disagree <em>silently</em>. One writer, and it is here.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>The five foundation modules. {@code project} carries three things this module cannot work
 * without: the isolation boundary, the gate ({@link com.tcms.project.ProjectService#readVisible} /
 * {@code #writeGranted}), and {@link com.tcms.project.ProjectKeys} — the per-project issue-key
 * allocator M0 landed one module ahead of its first caller. {@code identity} is asked <em>what is
 * this person called</em> and never <em>may they</em>, as in {@code repository},
 * {@code execution} and {@code work}.
 *
 * <p><strong>{@code documentation} is not on the list and must never be</strong>, and yet
 * {@code issue_doc_link} mirrors that module's table field for field. Same tension
 * {@code work.BoardFacts} resolves the same way: the whole edge lives in exactly one class,
 * {@link IssueDocLinkFacts}, which issues SQL naming {@code doc_link} and {@code doc_page} and
 * returns records declared here. Nothing else in this package may name a foreign table. The
 * <em>write</em> is not here at all — creating the link is {@code documentation}'s own endpoint,
 * because that module owns {@code doc_link} and a second writer is how two tables start disagreeing.
 *
 * <h2>What is deliberately not built here</h2>
 *
 * <p>Boards, rank, labels, releases, the timeline and the calendar — M2 and M3. A board is
 * <em>configuration over</em> {@link IssueStatus}, which is why D2 puts the canonical status on the
 * issue and lets a board map statuses onto columns: two boards over the same work cannot then
 * disagree about where a card is.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "identity", "project", "storage"})
package com.tcms.tracker;
```

- [ ] **Step 2: Write the depth-rule test first**

`IssueEdit` is pure — no Spring, no database — so the rule is unit-tested without a container.
`backend/src/test/java/com/tcms/tracker/IssueEditTest.java`:

```java
class IssueEditTest {

    private static final IssueTypeRow EPIC = type("Epic", "EPIC");
    private static final IssueTypeRow STORY = type("Story", "STORY");
    private static final IssueTypeRow BUG = type("Bug", "STORY");
    private static final IssueTypeRow QUICK_TASK = type("Task", "STORY");
    private static final IssueTypeRow SUBTASK = type("Sub-task", "TASK");

    @Test
    void aTaskLevelTypeRefusesToBeCreatedWithoutAParent() {
        // Scope §4: a Task is a breakdown OF something. Story H3's third criterion —
        // "cannot be detached into an orphan" — is this rule read from the other end.
        IssueEdit.Outcome outcome = IssueEdit.validateCreate(input(SUBTASK, null), SUBTASK, null);

        assertThat(errorsOf(outcome)).containsKey("parentKey");
        assertThat(errorsOf(outcome).get("parentKey").getFirst())
                .contains("needs a story-level parent");
    }

    @Test
    void anEpicRefusesAParent() {
        IssueEdit.Outcome outcome = IssueEdit.validateCreate(input(EPIC, "ACME-1"), EPIC, STORY);

        assertThat(errorsOf(outcome)).containsKey("parentKey");
    }

    @Test
    void aStoryLevelTypeMayStandAlone() {
        // Optional, not absent: requiring an epic would force a junk epic per stray
        // bug (scope §4), and story H2's second criterion says a story created
        // without one "is saved without one and appears in the backlog".
        assertThat(IssueEdit.validateCreate(input(STORY, null), STORY, null))
                .isInstanceOf(IssueEdit.Outcome.Valid.class);
    }

    @Test
    void allThreeStoryLevelTypesTakeAnEpicAndHoldTasks() {
        // The whole reason the rule is written against LEVELS and not against type
        // names: this test would need a fourth case per new type if it were.
        for (IssueTypeRow storyLevel : List.of(STORY, BUG, QUICK_TASK)) {
            assertThat(IssueEdit.validateCreate(input(storyLevel, "ACME-1"), storyLevel, EPIC))
                    .as("%s under an epic", storyLevel.name())
                    .isInstanceOf(IssueEdit.Outcome.Valid.class);
            assertThat(IssueEdit.validateCreate(input(SUBTASK, "ACME-9"), SUBTASK, storyLevel))
                    .as("sub-task under %s", storyLevel.name())
                    .isInstanceOf(IssueEdit.Outcome.Valid.class);
        }
    }

    @Test
    void aTaskRefusesAnEpicAsItsParent() {
        // One level above, not "any level above". Skipping the story level is what
        // makes an epic's completion count something other than what it displays.
        IssueEdit.Outcome outcome = IssueEdit.validateCreate(input(SUBTASK, "ACME-1"), SUBTASK, EPIC);

        assertThat(errorsOf(outcome).get("parentKey").getFirst())
                .contains("one level above");
    }

    @Test
    void startAndTargetDatesAreRefusedOnAnythingButAnEpic() {
        IssueEdit.Outcome outcome = IssueEdit.validateCreate(
                inputWithDates(STORY, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 18)), STORY, null);

        assertThat(errorsOf(outcome)).containsKeys("startDate", "targetDate");
    }

    @Test
    void aTargetDateBeforeItsStartIsRefused() {
        IssueEdit.Outcome outcome = IssueEdit.validateCreate(
                inputWithDates(EPIC, LocalDate.of(2026, 9, 18), LocalDate.of(2026, 7, 1)), EPIC, null);

        assertThat(errorsOf(outcome)).containsKey("targetDate");
    }

    @Test
    void aNegativeEstimateIsRefusedAndZeroIsNot() {
        // Zero is a real answer — "I looked, it is nothing" — and refusing it would
        // push people to leave the field empty, which D8 reads as "no estimate" and
        // silently flips the epic's whole basis from ESTIMATE to COUNT.
        assertThat(errorsOf(IssueEdit.validateCreate(estimated(STORY, -1), STORY, null)))
                .containsKey("estimateHours");
        assertThat(IssueEdit.validateCreate(estimated(STORY, 0), STORY, null))
                .isInstanceOf(IssueEdit.Outcome.Valid.class);
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tracker.IssueEditTest'
```

Expected: FAIL — `IssueEdit` does not exist.

- [ ] **Step 4: Write `IssueEdit`**

```java
package com.tcms.tracker;

/**
 * Validation, and <strong>the depth rule</strong> — the one thing that makes Epic → Story → Task
 * mean anything.
 *
 * <p>Pure: no Spring, no {@code EntityManager}, no clock. The caller resolves the two type rows and
 * hands them in, so every branch below is reachable from a unit test with no container — which is
 * what makes the eight cases in {@code IssueEditTest} cheap enough to have written first.
 *
 * <h2>The rule is about levels, and that is not a stylistic preference</h2>
 *
 * <p>Written against type <em>names</em> — "Task parents to Story" — this would have to be edited
 * in code the day a fourth story-level type is wanted. Written against
 * {@link IssueType#getLevel()}, <strong>a new type is a row</strong>: adding {@code Chore} or
 * {@code Tech Debt} at {@code STORY} needs no migration and no change here. Same reasoning
 * {@link IssueStatus} follows by categorising on {@code kind} rather than on a status's name (D2).
 */
final class IssueEdit {

    /** {@code EPIC} → 0, {@code STORY} → 1, {@code TASK} → 2. Depth, so "one above" is arithmetic. */
    private static int depthOf(String level) {
        return switch (level) {
            case "EPIC" -> 0;
            case "STORY" -> 1;
            case "TASK" -> 2;
            default -> throw new IllegalStateException("issue_type.level holds " + level
                    + ", which the CHECK constraint does not permit — the row predates the "
                    + "constraint or the constraint was widened without this switch");
        };
    }

    static void checkParent(
            Map<String, List<String>> errors,
            IssueTypeRow type,
            String parentKey,
            IssueTypeRow parentType) {

        int depth = depthOf(type.level());

        if (depth == 0 && parentKey != null) {
            errors.computeIfAbsent("parentKey", k -> new ArrayList<>()).add(
                    "An Epic is top level and cannot sit under anything.");
            return;
        }
        if (depth == 2 && parentKey == null) {
            errors.computeIfAbsent("parentKey", k -> new ArrayList<>()).add(
                    "A " + type.name() + " needs a story-level parent — it is a breakdown of "
                            + "something. For work that stands on its own, use the story-level "
                            + "Task type instead.");
            return;
        }
        if (parentKey == null) {
            return;   // a story-level type without an epic is a normal story (scope §4)
        }
        if (parentType == null) {
            errors.computeIfAbsent("parentKey", k -> new ArrayList<>()).add(
                    "No issue in this project has the key " + parentKey + ".");
            return;
        }
        if (depthOf(parentType.level()) != depth - 1) {
            errors.computeIfAbsent("parentKey", k -> new ArrayList<>()).add(
                    "A " + type.name() + " may only sit under a type one level above it, and "
                            + parentKey + " is a " + parentType.name() + ".");
        }
    }
}
```

Write the rest of `IssueEdit` — title length, the estimate, the epic-only dates — from the eight
assertions in Step 2. Each error message names the field's own word, not "invalid input".

- [ ] **Step 5: Write the entities and repositories**

`Issue` maps `V3`'s columns. Two rules from CLAUDE.md apply and both have bitten this repo:

1. **`@Version` on `lock_version`, and it is mapped.** Unlike `WorkItem`, which deliberately did
   *not* map it, this entity has field edits — `PATCH /issues/{key}` writes a title somebody typed
   over a copy they read, and that really does lose the other author's words.
   `ArchitectureRulesTest.everyLockVersionColumnIsAVersion` makes the half-state unbuildable.
2. **Every repository interface carries `@Transactional(readOnly = true)`.** Spring Data gives a
   transaction only to the inherited CRUD methods, so a derived or `@Query` method otherwise runs
   with no company set and **every RLS-protected read returns zero rows, fail-closed and silent.**

```java
@Repository
@Transactional(readOnly = true)
interface IssueRepository extends JpaRepository<Issue, UUID> {

    /*
      By KEY, and the company predicate is RLS's rather than a parameter — CompanyContext is
      populated from the session and never from a path segment (ERD §0.1 point 2). An issue key is
      unique per company (uq_issue_key), which is the only scope a caller can see into anyway.
    */
    Optional<Issue> findByIssueKeyAndDeletedAtIsNull(String issueKey);

    List<Issue> findByParentIdAndDeletedAtIsNullOrderByKeySeqAsc(UUID parentId);

    boolean existsByParentIdAndDeletedAtIsNull(UUID parentId);

    long countByStatusIdAndDeletedAtIsNull(UUID statusId);

    @Modifying
    @Transactional
    @Query("update Issue i set i.statusId = :to where i.statusId = :from and i.deletedAt is null")
    int reassignStatus(@Param("from") UUID from, @Param("to") UUID to);
}
```

- [ ] **Step 6: `IssueService` — the gate, then the key**

```java
    /**
     * Creates an issue, allocating its key from the project's own counter.
     *
     * <p><strong>The permission is checked before the payload is looked at</strong>, for
     * {@code ProjectCreateService}'s reason: the parent lookup below reports whether a key exists,
     * and running it for a caller who may not create issues turns a 403 endpoint into a probe for
     * which keys a project has taken.
     *
     * <p>{@link com.tcms.project.ProjectKeys#allocate} joins this transaction rather than starting
     * its own — required, not incidental: the row lock must be held until the issue that uses the
     * number commits, or a caller's rollback would leave the number spent.
     */
    public Outcome create(UUID userId, IssueInput request) {
        return switch (projects.writeGranted(userId, request.projectId(), CREATE_ISSUE,
                () -> validateAndCreate(userId, request))) {
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome validateAndCreate(UUID userId, IssueInput request) {
        Instant now = clock.instant();

        // A replay returns THIS issue rather than creating a second one, and — note —
        // BEFORE a key is allocated. Allocating first would burn a number on every
        // retry of one intent, so a project that had a flaky client would show gaps
        // that look like deleted work.
        if (request.idempotencyKey() != null) {
            Optional<Issue> replay = issues.findByCreatedIdempotencyKey(
                    request.projectId(), request.idempotencyKey());
            if (replay.isPresent()) {
                return new Outcome.Replayed(viewOf(replay.get()));
            }
        }

        IssueType type = types.findById(request.typeId()).orElse(null);
        Issue parent = request.parentKey() == null ? null
                : issues.findByIssueKeyAndDeletedAtIsNull(request.parentKey()).orElse(null);
        ...
        IssueEdit.Outcome validated = IssueEdit.validateCreate(request, rowOf(type), rowOf(parent));
        if (validated instanceof IssueEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }

        // The first status BY POSITION, never by name. A project that renamed
        // "Backlog" still has a first column, and a lookup on the word would break
        // exactly the projects that used the configurability B1 promises them.
        IssueStatus opening = statuses.findFirstByProjectIdOrderByPositionAsc(request.projectId())
                .orElseThrow(() -> new IllegalStateException(
                        "Project " + request.projectId() + " has no issue statuses. "
                                + "tcms_seed_project_defaults creates them and is idempotent; a "
                                + "project without them predates V3 and the backfill missed it."));

        ProjectKeys.Allocation allocation = keys.allocate(request.projectId(), 1);
        Issue created = Issue.create(allocation.keyAt(0), allocation.seqAt(0), ...);
        issues.save(created);
        events.issueChanged(created);       // T3 Part B wires this to the outbox
        return new Outcome.Created(viewOf(created));
    }
```

- [ ] **Step 7: Delete `ProjectKeys.allocate`'s exemption**

`ProjectKeys.allocate` now has a production caller, so its `UNEXERCISED_BY_DECISION` entry has
stopped describing anything. **The rule fails in both directions** — an entry that no longer
describes an unexercised method is as red as an unexercised method with no entry — and the entry
says so itself: *"THIS ENTRY IS TEMPORARY and M1 deletes it"*.

In `backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java`, delete the
`"ProjectKeys.allocate"` key and its value (lines ~526–536), leaving the five `FakeTrackerAdapter`
entries. `Map.of` takes at most ten pairs; five remain, so no change of construction is needed.

- [ ] **Step 8: Transitions, and the two refusals**

```java
    /**
     * Moves an issue to another status.
     *
     * <p>Two refusals, both 409, and they are different facts:
     *
     * <ul>
     *   <li><strong>The workflow forbids this pair.</strong> {@code issue_transition} is an
     *       allow-list, so a pair with no row is refused — story H5: "a move the project forbids is
     *       never offered", and on the board "the card does not move and the board tells me why"
     *       rather than snapping back in silence.</li>
     *   <li><strong>The issue is an Epic.</strong> D31 derives an epic's status from its stories
     *       and it is not editable. Refusing here rather than in the workflow table is deliberate:
     *       an epic has no status rows of its own to omit, and a manual epic status is exactly the
     *       "In Progress, 100%" disagreement D31 exists to make unrepresentable.</li>
     * </ul>
     *
     * <p>{@code status_entered_at} moves on every accepted transition; {@code done_at} is set the
     * FIRST time a {@code DONE}-kind status is reached and is <strong>never cleared by a reopen</strong>
     * — an issue that has been done once has been done once, and a lead time computed from a
     * cleared column silently lengthens every time somebody reopens something.
     */
```

- [ ] **Step 9: The workflow endpoints**

`WorkflowService` serves `GET`, and owns the two rules the `PUT` and the `DELETE` enforce:

- **Whole-document `PUT`.** Reordering is a statement about the set; a sequence of single-status
  writes has an intermediate state where two statuses claim one position — which is precisely what
  the screen's `↑ / ↓` generates.
- **The last `DONE`-kind status cannot be removed.** Every percentage is computed from that kind
  (D8); a project with none has no expressible completion and the screens would render a percentage
  of nothing.
- **Removing a status that issues hold** moves them in the same transaction
  (`IssueRepository.reassignStatus`), so there is no window where an issue points at a status that
  is gone. Story B1's third criterion.

- [ ] **Step 10: Run the gate**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew build
```

Expected: BUILD SUCCESSFUL. `com.tcms.reporting.FlakeScoreTest`'s six failures are **pre-existing**
— it pins `BASE = 2026-06-01T08:00:00Z` against a 90-day window that expired on 2026-08-30. Do not
attribute them to this module. If the run reports `Could not write XML test results` with zero
assertion failures, that is the fd limit: `ulimit -n 65535; ./gradlew build`.

- [ ] **Step 11: Commit**

```bash
cd ~/RiderProjects/tcms
git add backend/src/main/java/com/tcms/tracker backend/src/test/java/com/tcms/tracker backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java
git commit -m "feat(tracker): issues, the workflow, and the legal-move set

The depth rule is checked against issue_type.level and never against a type name, so
a fourth story-level type stays a row. A new issue enters the first status BY
POSITION, not by the word 'Backlog' — a project that renamed it still has a first
column. issue_transition is an allow-list, so a pair with no row is refused and the
board says why.

ProjectKeys.allocate has a production caller now, so its UNEXERCISED_BY_DECISION
entry is deleted — the rule fails in both directions and the entry said M1 would."
```


---

#### Part B — completion, epic status and `issue_fact`

**Deliverable:** an epic's `completion` carries `{percent, basis, done, total, excludedSpikes}` and
its status is derived and not writable; every issue write lands an `issue_fact` row with its four
timestamps, written by one class and no other.
**Depends on:** Part A

**Files:**
- Create: `backend/src/main/java/com/tcms/tracker/Completion.java`, `EpicStatus.java`,
  `IssueFactProjection.java`, `IssueFactOnIssueChanged.java`, `CompletionView.java`
- Modify: `backend/src/main/java/com/tcms/tenancy/OutboxEvents.java`,
  `backend/src/main/java/com/tcms/tracker/IssueService.java`
- Test: `backend/src/test/java/com/tcms/tracker/CompletionTest.java`, `EpicStatusTest.java`,
  `IssueFactProjectionTest.java`

- [ ] **Step 1: Write the completion test first — it is pure, so all nine cases are cheap**

```java
class CompletionTest {

    @Test
    void anEpicWithNoCountedIssuesIsZeroPercentByCount() {
        // Zero is a real answer, not an error and not "n/a". An epic somebody just
        // created is 0% of nothing, and the page says "0 of 0 done" — which is
        // honest, where a dash would make the reader wonder if it was broken.
        Completion c = Completion.of(List.of());

        assertThat(c.percent()).isZero();
        assertThat(c.basis()).isEqualTo(Basis.COUNT);
        assertThat(c.total()).isZero();
    }

    @Test
    void byCountWhenAtLeastOneCountedIssueLacksAnEstimate() {
        // D8's override is ALL-or-nothing: one missing estimate and the whole epic
        // falls back. project-overview.html shows exactly this — "by count · 4 of 10
        // done · 2 lack an estimate".
        Completion c = Completion.of(List.of(
                done(8.0), done(4.0), open(null), open(6.0)));

        assertThat(c.basis()).isEqualTo(Basis.COUNT);
        assertThat(c.percent()).isEqualTo(50);       // 2 of 4, not 12 of 18 hours
        assertThat(c.done()).isEqualTo(2);
        assertThat(c.total()).isEqualTo(4);
    }

    @Test
    void byEstimateWhenEveryCountedIssueCarriesOne() {
        Completion c = Completion.of(List.of(done(8.0), done(4.0), open(6.0), open(2.0)));

        assertThat(c.basis()).isEqualTo(Basis.ESTIMATE);
        assertThat(c.percent()).isEqualTo(60);       // 12h of 20h
        assertThat(c.estimatedHoursDone()).isEqualTo(12.0);
        assertThat(c.estimatedHoursTotal()).isEqualTo(20.0);
    }

    @Test
    void aSpikeIsExcludedFromBOTHNumeratorAndDenominator() {
        // D8. Counting it would let an epic read 40% done having shipped nothing.
        // Note the second assertion: an UNESTIMATED spike must not drag the basis
        // down either, because it is not a counted issue at all — this is the case
        // that a naive "any missing estimate → COUNT" would get wrong.
        Completion c = Completion.of(List.of(done(8.0), open(2.0), spike(null)));

        assertThat(c.total()).isEqualTo(2);
        assertThat(c.excludedSpikes()).isEqualTo(1);
        assertThat(c.basis()).isEqualTo(Basis.ESTIMATE);
        assertThat(c.percent()).isEqualTo(80);       // 8h of 10h
    }

    @Test
    void anEpicOfNothingButSpikesIsZeroOfZeroAndSaysHowManyItLeftOut() {
        Completion c = Completion.of(List.of(spike(3.0), spike(null)));

        assertThat(c.total()).isZero();
        assertThat(c.percent()).isZero();
        assertThat(c.excludedSpikes()).isEqualTo(2);
    }

    @Test
    void thereIsNoPartialCreditForInProgressWork() {
        // D8: "a number that moves before anything ships is the number people learn
        // to distrust, and once distrusted it is decoration." IN_PROGRESS counts
        // exactly as NOT_STARTED does — as not done.
        assertThat(Completion.of(List.of(inProgress(8.0), inProgress(8.0))).percent()).isZero();
    }

    @Test
    void aBugCountsAsUnfinishedWorkUntilItIsDone() {
        // Story H2's third criterion, and it needs no branch: a Bug is a
        // story-level type with counts_toward_completion = true, so it is simply
        // in the list. The test exists so a future "exclude bugs" idea has to
        // delete an assertion that states the decision.
        Completion c = Completion.of(List.of(done(4.0), bugOpen(4.0)));

        assertThat(c.percent()).isEqualTo(50);
    }

    @Test
    void percentRoundsTowardTheNumberThatIsNotMisleading() {
        // 1 of 3 is 33, not 34: rounding half-up would let an epic with nothing
        // finished round to 1% and an epic with one story left round to 100%. The
        // second assertion is the one that matters — 100% must mean DONE.
        assertThat(Completion.of(List.of(done(1.0), open(1.0), open(1.0))).percent()).isEqualTo(33);
        assertThat(Completion.of(List.of(done(999.0), open(0.5))).percent()).isEqualTo(99);
    }

    @Test
    void aZeroHourEstimateKeepsTheEstimateBasisAndDoesNotDivideByZero() {
        // Zero is a legitimate estimate (see IssueEditTest). An epic where every
        // counted issue is estimated at zero has an ESTIMATE basis and no
        // denominator — the answer is 0%, not a crash and not a fallback to COUNT,
        // because falling back would make the page say "by count" about an epic
        // that is fully estimated.
        Completion c = Completion.of(List.of(done(0.0), open(0.0)));

        assertThat(c.basis()).isEqualTo(Basis.ESTIMATE);
        assertThat(c.percent()).isZero();
    }
}
```

- [ ] **Step 2: Run it, watch it fail, then write `Completion`**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tracker.CompletionTest'
```

Expected: FAIL — `Completion` does not exist. Then write it:

```java
/**
 * D8 — an epic's completion, and <strong>the basis is returned beside the number, always</strong>.
 *
 * <p>Pure and static, so the nine cases above cost no container. It is called from
 * {@code IssueService} and from nowhere else: D8's "one number, one place" is not a style rule —
 * this repo has shipped two contradictory percentages on one screen, and a component that computes
 * its own is how that happened.
 *
 * <h2>Why the basis is on the wire and never inferred by the client</h2>
 *
 * <p>A client asked to infer it would have to know that <em>every counted issue</em> carries an
 * estimate, which means shipping the whole list to a screen that wanted one number. Worse, the two
 * sides would infer it from different data the first time a filter narrowed one of them — and a
 * percentage whose meaning depends on which screen you are looking at is the failure D8 spends a
 * paragraph on.
 *
 * <h2>An unestimated epic gets a coarser number, not a wrong one</h2>
 *
 * <p>Nobody is forced to estimate for a number to exist. The override is all-or-nothing on purpose:
 * a mix of estimated and unestimated issues weighted by hours would silently treat the unestimated
 * ones as zero, so an epic would read <em>higher</em> the less it was planned.
 */
record Completion(int percent, Basis basis, int done, int total,
                  Double estimatedHoursDone, Double estimatedHoursTotal, int excludedSpikes) {

    static Completion of(List<CountedIssue> children) {
        List<CountedIssue> counted = children.stream()
                .filter(CountedIssue::countsTowardCompletion).toList();
        int excluded = children.size() - counted.size();

        if (counted.isEmpty()) {
            return new Completion(0, Basis.COUNT, 0, 0, null, null, excluded);
        }
        boolean everyOneEstimated = counted.stream().allMatch(c -> c.estimateHours() != null);
        int doneCount = (int) counted.stream().filter(CountedIssue::isDone).count();

        if (!everyOneEstimated) {
            // Integer division truncates, which is the rounding this wants: 1 of 3
            // is 33 and 999 of 999.5 is 99, so 100% means done and nothing else does.
            return new Completion(doneCount * 100 / counted.size(), Basis.COUNT,
                    doneCount, counted.size(), null, null, excluded);
        }
        double total = counted.stream().mapToDouble(CountedIssue::estimateHours).sum();
        double finished = counted.stream().filter(CountedIssue::isDone)
                .mapToDouble(CountedIssue::estimateHours).sum();

        // Every counted issue estimated at zero is a fully-estimated epic with no
        // denominator. 0% and basis ESTIMATE — falling back to COUNT here would make
        // the page say "by count" about an epic that is entirely estimated.
        int percent = total == 0 ? 0 : (int) (finished * 100 / total);
        return new Completion(percent, Basis.ESTIMATE, doneCount, counted.size(),
                finished, total, excluded);
    }
}
```

- [ ] **Step 3: `EpicStatus` — D31, and it has three arms**

```java
class EpicStatusTest {

    @Test
    void noStoryStartedReadsNotStarted() {
        assertThat(EpicStatus.of(List.of(notStarted(), notStarted()))).isEqualTo(Kind.NOT_STARTED);
    }

    @Test
    void everyCountedStoryTerminalReadsDone() {
        // Story H5's last criterion: "given every story in an epic is done, its
        // status reads Done WITHOUT ANYONE EDITING IT". project-overview.html says
        // the same from the other end — "closed itself when the last story closed".
        assertThat(EpicStatus.of(List.of(done(), done()))).isEqualTo(Kind.DONE);
    }

    @Test
    void anythingElseReadsInProgress() {
        assertThat(EpicStatus.of(List.of(done(), notStarted()))).isEqualTo(Kind.IN_PROGRESS);
        assertThat(EpicStatus.of(List.of(inProgress()))).isEqualTo(Kind.IN_PROGRESS);
    }

    @Test
    void anEmptyEpicIsNotStartedRatherThanDone() {
        // The vacuous-truth trap: "every counted story is terminal" is TRUE of an
        // empty epic, so a naive allMatch reads a brand-new epic as Done. It is the
        // one case in D31 that the sentence does not spell out, and the one a reader
        // would notice immediately on the screen.
        assertThat(EpicStatus.of(List.of())).isEqualTo(Kind.NOT_STARTED);
    }

    @Test
    void spikesDoNotDecideAnEpicsStatus() {
        // Consistent with the percentage beside it. An epic whose only unfinished
        // work is a spike shows 100% — so it must not also show "In Progress", which
        // is precisely the disagreement D31 exists to make unrepresentable.
        assertThat(EpicStatus.of(List.of(done(), spikeNotStarted()))).isEqualTo(Kind.DONE);
    }
}
```

The implementation is five lines; the value is in the last two tests.

- [ ] **Step 4: Mint the outbox name**

`OutboxEvents` gains one constant. Read that file's header first — it explains why the names live
in `tenancy` when the meanings do not, and why a **past-tense** name is an occurrence recorded
through `Outbox.record` while a **changed** name is a state recorded through
`Outbox.recordCoalesced`.

```java
    /**
     * An issue was created, edited, moved or deleted — {@code tracker}'s projection trigger.
     *
     * <p><strong>An occurrence, not a state</strong>, so it is recorded through
     * {@link Outbox#record} and carries the issue id. Getting that backwards matters here: the
     * handler rewrites <em>one</em> {@code issue_fact} row, and a coalesced form would collapse
     * fifty issue moves into one delivery that could name only one of them — forty-nine rows would
     * then be stale until something else touched them.
     *
     * <p>The payload is the issue's id and nothing else. The handler re-reads the row, which is
     * what makes at-least-once delivery cost a repeated statement rather than a wrong answer, and
     * what keeps this event from becoming a second, drifting copy of the issue shape.
     */
    public static final String TRACKER_ISSUE_CHANGED = "tracker.issue_changed";
```

- [ ] **Step 5: `IssueFactProjection` — one writer, and say so at the class**

```java
/**
 * The <strong>only</strong> writer of {@code issue_fact}.
 *
 * <p>Scope §3 states the trap this class exists to close: <em>"issue_fact must be fed by an event,
 * never written by {@code time} or {@code billing}. The moment a downstream module writes it,
 * {@code tracker} is no longer the authority on issue state and the two disagree silently."</em>
 * Silently is the word that matters — nothing fails, no test reddens, and a report is simply wrong.
 *
 * <p>So the write is here, it is {@code private} to this module, and the four timestamps are
 * maintained in one place:
 *
 * <ul>
 *   <li>{@code created_at} — the issue's, carried.</li>
 *   <li>{@code status_entered_at} — moves on every accepted transition.</li>
 *   <li>{@code done_at} — set the first time a {@code DONE}-kind status is reached, never cleared
 *       by a reopen. A lead time computed from a cleared column silently lengthens every time
 *       somebody reopens something.</li>
 *   <li>{@code release_assigned_at} — null until M3. It is here now because it is the one number in
 *       M9's burn-up that <strong>cannot be reconstructed after the fact</strong>: "scope added
 *       after the release was created" is the difference between this and {@code release.created_at},
 *       and an assignment that already happened leaves no other trace (009 review R12).</li>
 * </ul>
 *
 * <p>Upsert, not insert: the handler is idempotent because the outbox is at-least-once, and it
 * re-reads the issue rather than trusting a payload — so a delivery that arrives twice writes the
 * same row twice and a delivery that arrives late writes the current truth rather than a stale one.
 */
```

- [ ] **Step 6: The projection test asserts the negative**

```java
    @Test
    void nothingOutsideTrackerWritesIssueFact() {
        // A computed check, not a careful reading — CLAUDE.md's rule, and the one
        // that found five join tables without company_id when reading V1 top to
        // bottom had not. Greps the whole main source tree for the table name.
        Set<String> writers = filesUnder("backend/src/main/java").stream()
                .filter(f -> textOf(f).matches("(?s).*\\b(INSERT INTO|UPDATE)\\s+issue_fact\\b.*"))
                .map(Path::toString)
                .collect(toSet());

        assertThat(writers)
                .as("scope §3: issue_fact has one writer. A downstream module writing it makes "
                        + "tracker stop being the authority on issue state, silently.")
                .containsExactly("backend/src/main/java/com/tcms/tracker/IssueFactProjection.java");
    }

    @Test
    void doneAtSurvivesAReopen() {
        UUID issueId = anIssueMovedTo("Done");
        Instant firstDone = doneAtOf(issueId);

        moveTo(issueId, "In Progress");
        moveTo(issueId, "Done");

        assertThat(doneAtOf(issueId)).isEqualTo(firstDone);
    }
```

- [ ] **Step 7: Gate and commit**

```bash
~/bin/tcms-remote sync && ~/bin/tcms-remote backend ./gradlew build
cd ~/RiderProjects/tcms
git add backend/src/main/java/com/tcms/tracker backend/src/main/java/com/tcms/tenancy/OutboxEvents.java backend/src/test/java/com/tcms/tracker
git commit -m "feat(tracker): completion, derived epic status, and issue_fact

Completion states its basis on the wire — a client asked to infer it would need the
whole list to render one number, and the two sides would infer differently the first
time a filter narrowed one of them. The override is all-or-nothing so an epic never
reads higher the less it was planned, and spikes leave both halves of the fraction.

issue_fact has exactly one writer and a test that greps for a second, because the
failure scope §3 describes is silent: nothing breaks, and a report is just wrong."
```

---

### T4 — `be` · Spec links, comments, and the retirement of `work`

**Agent:** `be-java` · **Stack:** `be` · **Budget:** ~130k

#### Part A — spec links, comments and watchers

**Deliverable:** linking an epic to a documentation block writes a `doc_link` row with
`source_type = 'ISSUE'`, `GET /issues/{key}/doc-links` returns it with its excerpt and `stale`
flag, and `POST /issues/{key}/comments` appends a comment and adds its author as a watcher.
**Depends on:** T3

**Files:**
- Modify: `backend/src/main/java/com/tcms/documentation/LinkSourceType.java`,
  `documentation/CatalogueFacts.java`
- Create: `backend/src/main/java/com/tcms/tracker/IssueDocLinkFacts.java`,
  `DocLinkProjection.java`, `DocLinkRefreshOnLinksChanged.java`, `IssueDocLink.java`,
  `IssueDocLinkRepository.java`, `IssueComment.java`, `IssueCommentRepository.java`,
  `CommentService.java`, `CommentController.java`, `IssueWatcher.java`,
  `IssueWatcherRepository.java`
- Test: `backend/src/test/java/com/tcms/tracker/IssueDocLinkTest.java`, `CommentEndpointTest.java`

- [ ] **Step 1: Widen `LinkSourceType`**

The enum's own header already says this member's arrival is M1's — *"the issue-to-specification
edge arrives with module M1's tracker"* — and that the column has been wider than the enum since
`V2` deliberately. Add the member and **rewrite that paragraph rather than leaving it predicting
something that has happened**:

```java
    /**
     * {@code tracker.Issue}. An epic or a story linked to the specification it comes from.
     *
     * <p>The third member, and the first that is not a {@code repository} row. {@code V2} added
     * {@code 'ISSUE'} to {@code doc_link}'s CHECK one module ahead of this, which is why there is
     * no migration here — the column was already wide enough, on purpose.
     *
     * <p>{@code DEFECT} and {@code DOC_PAGE} are still absent for their own reasons, unchanged: a
     * defect is an external record this product links out to rather than stores, and a page-to-page
     * link is a different feature needing its own target semantics.
     */
    ISSUE
```

- [ ] **Step 2: Give `CatalogueFacts` its `ISSUE` arm**

`CatalogueFacts` is `documentation`'s single foreign-table class — the `BoardFacts` pattern — so
resolving an issue belongs there and nowhere else in that module.

```java
    /*
      The ISSUE arm (M1). Resolved by KEY rather than by id, because that is what a person types
      and what the link dialog offers; the id is carried back so doc_link.source_id stays a UUID
      like every other source.

      `deleted_at IS NULL` matters here in a way it does not for a case: an issue is soft-deleted
      (scope §3's mitigation for the module rule), so without this predicate a link could be made
      to something that is gone from every screen.
    */
    private static final String FIND_ISSUE = """
            SELECT i.id, i.issue_key, i.title
              FROM issue i
             WHERE i.project_id = CAST(:projectId AS uuid)
               AND i.issue_key = :key
               AND i.deleted_at IS NULL
            """;
```

- [ ] **Step 3: The projection, on the event that already exists**

**No new outbox name.** `documentation.links_changed` is already recorded whenever a link or a
linked page moves, and `repository.HealthRecomputeOnLinksChanged` is the template — read it before
writing `DocLinkRefreshOnLinksChanged`, including its paragraph on why the handler does the whole
recompute rather than the mirror alone.

```java
/**
 * Refreshes this company's mirror of the links from its issues into documentation.
 *
 * <p>Second claimant of {@code documentation.links_changed}, beside
 * {@code repository.HealthRecomputeOnLinksChanged}. A shared event with two handlers is what the
 * outbox is for, and it is why the name is a <em>state</em> recorded through
 * {@link com.tcms.tenancy.Outbox#recordCoalesced} rather than an occurrence: several link edits
 * collapse into one delivery, and both handlers rebuild from current facts either way.
 *
 * <p><strong>Replaced whole per company</strong>, exactly as {@code case_doc_link} is. Rebuilding
 * rather than patching is what makes at-least-once delivery cost a repeated statement instead of a
 * wrong answer — and it is the only way to notice a link that was <em>removed</em>, which a
 * per-row upsert cannot see.
 */
```

**`stale`'s rule, copied from `case_doc_link` and not reinvented:** newest `doc_page_version`
against the issue's own `updated_at`; **PAGE granularity**, because `doc_block` carries no
timestamp, so an edit elsewhere on the page raises it too; and **always false for an external
target** — a git range is pinned by its sha, and an external issue carries no derived meaning for a
freshness marker to be about. `epic.html` renders exactly this: a warning border, a
"changed since linked" badge, the excerpt *as it was*, and a Re-confirm button whose caption says
re-confirming "replaces it with the current text and clears this flag".

- [ ] **Step 4: Comments add their author as a watcher**

```java
    @Test
    void commentingMakesYouAWatcher() {
        // Story H8: a person who has said something is following it by default.
        // Without this they comment, the conversation continues, and they never hear
        // about it — which is how a comment thread on an issue tracker dies.
        postComment("ACME-118", DANA, "Filing the expired-card failure under the epic.");

        assertThat(watchersOf("ACME-118")).contains(DANA);
    }

    @Test
    void aViewerMayCommentAndMayNotEdit() {
        // §A3 gives Viewer issue:read and issue:comment and nothing else. The
        // negative half is the one worth asserting: a reader who can say something
        // about the work must still not be able to change it.
        assertThat(postCommentAs(VIEWER, "ACME-118", "Is this the same as ACME-9?")).isEqualTo(201);
        assertThat(patchTitleAs(VIEWER, "ACME-118", "Renamed")).isEqualTo(403);
    }

    @Test
    void watchingIsIdempotentInBothDirections() {
        // A PUT with a boolean, not a POST and a DELETE: the star has two states and
        // the request says which one the person wants, not which one they think it
        // is in. Two clicks on a slow connection must not toggle it back.
        putWatch("ACME-118", DANA, true);
        putWatch("ACME-118", DANA, true);
        assertThat(watchersOf("ACME-118")).containsOnlyOnce(DANA);

        putWatch("ACME-118", DANA, false);
        putWatch("ACME-118", DANA, false);
        assertThat(watchersOf("ACME-118")).doesNotContain(DANA);
    }
```

Story H8's history — *"every status, assignee and field change with who made it and when"* — is
`audit_event`, which already exists and already has a reader. `IssueService` records one per write
with the same `AuditEvent.record` call `ProjectCreateService` makes, including
`AuditEvent.jsonString` for anything a person typed: a title like `Acme "Retail" \ Wealth`
concatenated raw produces malformed JSONB, the insert fails, and the whole write rolls back naming
the audit table rather than the title.

- [ ] **Step 5: Gate and commit**

```bash
~/bin/tcms-remote sync && ~/bin/tcms-remote backend ./gradlew build
cd ~/RiderProjects/tcms
git add backend/src/main/java/com/tcms/tracker backend/src/main/java/com/tcms/documentation backend/src/test/java/com/tcms/tracker
git commit -m "feat(tracker): spec links, comments and watchers

LinkSourceType gains ISSUE — V2 widened doc_link's CHECK one module ahead of this on
purpose, so there is no migration. The WRITE stays in documentation, which owns
doc_link; tracker keeps issue_doc_link and rebuilds it whole per company on the
links_changed event that already exists, because a per-row upsert cannot see a link
that was removed.

Commenting makes you a watcher: without it people comment, the thread continues, and
they never hear about it."
```


---

#### Part B — `work` retires; `/board` composes issues

**Deliverable:** the nine `work` classes are gone, `GET /board` serves four card kinds with `ISSUE`
as the row-backed one, and `derived_item_annotation` and its rows are untouched.
**Depends on:** Part A

**Files:**
- Modify: `backend/src/main/java/com/tcms/work/BoardFacts.java` (515 lines),
  `BoardService.java` (856 lines), `package-info.java`
- Rename: `work/WorkItemController.java` → `work/BoardController.java`
- Delete: `work/WorkItem.java`, `WorkItemEdit.java`, `WorkItemRepository.java`,
  `WorkItemStatus.java`, `WorkItemStatusUpdateRequest.java`, `WorkItemView.java`,
  `WorkItemWriteRequest.java`, `Priority.java`
- Create: `work/CardStatus.java`, `work/AnnotationController.java`,
  **`backend/src/main/resources/db/migration/V4__retire_work_item.sql`**
- Modify: `backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java` (policy count 84 -> 82)

**T2 did not drop `work_item` and `work_item_link`, and this task does — in the same commit that
deletes the entity.** Plan T2 Part D said to drop them there; that statement cannot stand alone.
`com.tcms.work.WorkItem` maps `work_item` and `hibernate.ddl-auto` is `validate`, so a database
without the table refuses to boot the Spring context and every integration test fails before its
first assertion — and T2 could not delete the entity either, because `BoardService` reads
`WorkItemRepository` until Part B below gives it `BoardFacts.assignedIssues`. So `V3` **copies** the
rows and `V4` drops the tables, which is the expand-contract ordering the migration discipline asks
for anyway. Between `V3` and `V4` a migrated task exists twice — once as the `work_item` the old
board still renders, once as the issue it became; nothing user-facing ships in that window.
`V4` is three statements and no data movement:

```sql
DROP TABLE work_item_link;
DROP TABLE work_item;
```

`SchemaConventionTest`'s policy count moves **84 -> 82** in the same commit, and the comment beside
it already says these two are waiting for this migration.
- Test: `backend/src/test/java/com/tcms/work/BoardCompositionTest.java`

**Read `work/package-info.java` in full before touching anything here.** It is 97 lines and it is
the design document for this module — in particular the paragraph on why exactly one class may name
a foreign table, and the one on why a derived card is never materialised.

- [ ] **Step 1: `BoardFacts` gains the fifth crossing**

```java
    /**
     * One issue assigned to somebody — <strong>the row-backed card, and the only one</strong>.
     *
     * <p>Fifth crossing in this class and the first that reads {@code tracker}. Same rule as the
     * other four and stated again because it is the one a reader will question: {@code work} may
     * not depend on {@code tracker} ({@code allowedDependencies} is unchanged and stays unchanged),
     * so this holds ids into that module's tables and reads them through <em>this file</em>. A
     * shared row is not a shared class, and what plan §3 forbids is two modules becoming deployable
     * only together.
     *
     * <p><strong>The card that replaced the card this module used to own.</strong> Until M1 the
     * stored card was {@code work_item}, mapped by an entity in this package. It is now an
     * {@code issue} in another module, read here as a fact — which is why this method exists at all
     * and why {@code WorkItemRepository} does not.
     *
     * <p>{@code deleted_at IS NULL} on both the issue and its status join: an issue is soft-deleted
     * (scope §3), so without it a board would render work that is gone from every other screen.
     */
    record AssignedIssue(
            UUID issueId,
            UUID projectId,
            String issueKey,
            String title,
            String statusName,
            String statusKind,
            String priority,
            LocalDate dueDate,
            UUID assigneeId) {
    }

    private static final String ASSIGNED_ISSUES = """
            SELECT i.id, i.project_id, i.issue_key, i.title,
                   s.name, s.kind, i.priority, i.due_date, i.assignee_id
              FROM issue i
              JOIN issue_status s ON s.id = i.status_id
             WHERE i.project_id IN (:projectIds)
               AND i.deleted_at IS NULL
               AND s.kind <> 'DONE'
            """;
```

**`s.kind <> 'DONE'` is the "card leaves" rule the other four generators already keep**, expressed
against the one column that survives a project renaming its columns. §6l puts it plainly for
executions — *"execution completed → card leaves"* — and without it a board becomes an append-only
list of everything anybody was ever assigned. `my-work.html`'s "Hide done" checkbox is checked by
default and is a *client* filter over what is left, not a substitute for this.

- [ ] **Step 2: `BoardService` — the `ISSUE` generator replaces the `TASK` one**

The four derived generators are untouched. What changes:

- The stored half no longer reads `WorkItemRepository`; it reads `BoardFacts.assignedIssues`.
- `allowedTransitions` for an `ISSUE` card comes from the project's `issue_transition` rows, not
  from "every status but the current one". `WorkItem.moveTo`'s comment said no transition was
  refused *"and that is the model rather than an omission"* — **that model is over.** The workflow
  is now the authority, which is what makes `settings-workflow.html`'s "Allowed moves" panel mean
  anything. Delete that paragraph rather than leaving it describing behaviour that changed.
- Every card's `status` becomes `CardStatus(name, kind)` (D-c). The four derived generators get
  their five existing names with kinds:

```java
/**
 * A card's status, as a name a person reads and a kind a percentage can be computed from.
 *
 * <p><strong>Replaces the fixed {@code WorkItemStatus} enum</strong>, which had five members and
 * could not spell an issue's status: an issue's status is a per-project {@code issue_status} row
 * (D2), so a project that renamed "Blocked" to "Environment down" has a status the enum has no
 * member for. A mapping table would have given every card two spellings of where it is, and this
 * repo has already shipped two contradictory numbers on one screen once.
 *
 * <p>The four derived generators supply their own names with the matching kind — an outstanding
 * execution is {@code ("To do", NOT_STARTED)}, a pending review is {@code ("In Review", IN_PROGRESS)}.
 * Nothing about how a derived card is computed changed; only how its position is spelled.
 *
 * <p>M2 builds real board columns over {@code kind} plus the project's own statuses, which is what
 * D2 asked for: the canonical status is on the issue, and a board <em>maps</em> statuses onto
 * columns rather than owning them — so two boards over the same work cannot disagree about where a
 * card is.
 */
record CardStatus(String name, StatusKind kind) {}
```

- [ ] **Step 3: `BoardController` serves `GET /board` and nothing else**

`WorkItemController` is 310 lines of which the `POST` and the `PATCH` go. Keep the `GET`'s
parameter validation verbatim — the two `400` shapes are decisions, not tidiness: *neither a
project nor a person* and *two boards at once* are both refusals to **guess which board was meant**,
and neither is a `404` because nothing failed to resolve.

The `409` the `PATCH` used to answer — *"this card mirrors a source and its status is not stored"* —
does not disappear; it moves to `POST /issues/{key}/transitions`, which 404s a synthetic
`execution:{uuid}` id because that is not an issue key. **That is a real behaviour change and it is
worse:** the old message named the action that does work. Record it as a known regression in T7's
built-and-unverified list; restoring it needs M2's board, which is where a drag actually happens.

- [ ] **Step 4: `AnnotationController` — `derived_item_annotation` survives**

D11: *"`derived_item_annotation` survives and matters more, not less."* It had no endpoint of its
own — the board wrote it — so `PUT /board/annotations/{sourceType}/{sourceId}` is new.

```java
    /**
     * Your own note, personal due date and flag on a card.
     *
     * <p>Keyed on the CALLER and on nobody else. Story B5's second criterion: <em>"when a teammate
     * looks at the same issue, they do not see my note"</em> — two people reading one card get
     * different values here, and that is the feature rather than a caching bug.
     *
     * <p><strong>No permission beyond seeing the card.</strong> Annotating something is not editing
     * it, which is the whole reason this is a separate table from the row: a personal note and a
     * personal due date on a review somebody else asked of you is exactly the case D11 keeps this
     * table for.
     */
```

- [ ] **Step 5: Delete the nine classes**

```bash
cd ~/RiderProjects/tcms/backend/src/main/java/com/tcms/work
git rm WorkItem.java WorkItemEdit.java WorkItemRepository.java WorkItemStatus.java \
       WorkItemStatusUpdateRequest.java WorkItemView.java WorkItemWriteRequest.java Priority.java
git mv WorkItemController.java BoardController.java
```

**`Priority` moves rather than dying.** `work.Priority` is the enum `WorkItem` used; `issue`
carries the same four values with the same CHECK. Move it to `com.tcms.api` — `test_case.priority`
already has its own, and the contract's `Priority` schema is shared, so a `tracker`-local copy
would be a third spelling of one vocabulary. **Check for other importers before moving:**

```bash
cd ~/RiderProjects/tcms/backend
grep -rn "com.tcms.work.Priority\|work.Priority" src/
```

- [ ] **Step 6: Update `package-info.java` in the superseded style**

The module comment's opening claim — *"a **task** is first-class … `work_item` is where it lives"*
— is no longer true. Strike it through with the reversal rather than rewriting it away, per the
working agreement, so the next reader sees the reasoning was considered:

```java
 * <p>~~A <strong>task</strong> is first-class. Somebody typed it, it owns its own status, and
 * {@code work_item} is where it lives.~~ <strong>→ superseded by 009 module M1.</strong> The
 * row-backed card is an {@code issue} in {@code com.tcms.tracker}, read here through
 * {@link BoardFacts#assignedIssues}. <strong>The rule this module is built around is unchanged and
 * is why the replacement was possible at all</strong>: exactly one kind of card is a row, and it is
 * the one somebody typed. D11 keeps that true with a different table under it.
```

- [ ] **Step 7: The composition test**

```java
    @Test
    void myWorkMixesIssuesWithReviewsAndExecutions() {
        // D11's whole claim, and the acceptance criterion 009 closes M1 on. Two
        // projects, so the aggregate the personal board exists for is exercised.
        givenIssueAssignedTo(DANA, "Acme Storefront");
        givenIssueAssignedTo(DANA, "Bergstrom Portal");
        givenOutstandingExecutionFor(DANA);
        givenPendingReviewFor(DANA);

        List<BoardCard> cards = board(mine(DANA));

        assertThat(kindsOf(cards)).contains(ISSUE, EXECUTION, REVIEW);
        assertThat(projectKeysOf(cardsOfKind(cards, ISSUE))).containsExactlyInAnyOrder("ACME", "BERG");
    }

    @Test
    void anIssueCardIsTheOnlyOneThatIsARow() {
        assertThat(cardsWhere(BoardCard::derived).stream().map(BoardCard::kind))
                .doesNotContain(ISSUE);
        assertThat(cardsOfKind(ISSUE)).allMatch(c -> !c.derived());
    }

    @Test
    void aDoneIssueLeavesTheBoard() {
        UUID issue = givenIssueAssignedTo(DANA, "Acme Storefront");
        moveTo(issue, "Done");

        assertThat(keysOf(board(mine(DANA)))).doesNotContain(keyOf(issue));
    }

    @Test
    void anIssueCardsAllowedTransitionsComeFromTheProjectWorkflow() {
        // The behaviour change from work_item, where every column was legal. From
        // In Review the seeded workflow permits Blocked, In Progress and Done — and
        // NOT Backlog or Ready, which "every status but the current one" would have
        // offered.
        UUID issue = givenIssueInStatus("In Review");

        assertThat(transitionNamesOn(cardFor(issue)))
                .containsExactlyInAnyOrder("Blocked", "In Progress", "Done", "Ready", "Backlog");
    }

    @Test
    void myNoteOnACardIsNotOnSomebodyElsesCopyOfIt() {
        UUID issue = givenIssueAssignedTo(DANA, "Acme Storefront");
        putAnnotation(DANA, "ISSUE", issue, "ask Mira which postcode set is authoritative");

        assertThat(annotationOn(cardFor(issue, asUser(DANA)))).isNotNull();
        assertThat(annotationOn(cardFor(issue, asUser(MIRA)))).isNull();
    }

    @Test
    void aNoteSurvivesTheWorkItemMigrationOntoTheIssueItAnnotated() {
        // The migration carried work_item.id forward for exactly this. Asserted from
        // the board rather than from the table, because the table being right and
        // the board not reading it is a real and separate failure.
        assertThat(annotationOn(cardFor(migratedIssueWithANote()))).isNotNull();
    }
```

**Note the fourth test's expectation.** The seeded workflow permits every pair except *into* `Done`
from anywhere but `In Review` — so from `In Review` all five other statuses are legal. Write the
assertion against what T2 Part C's seed actually inserts, not against `settings-workflow.html`'s
right-hand panel, which illustrates the moves **from `In Progress`**.

- [ ] **Step 8: Gate and commit**

```bash
~/bin/tcms-remote sync && ~/bin/tcms-remote backend ./gradlew build
cd ~/RiderProjects/tcms
git add -u backend/src/main/java/com/tcms/work backend/src/main/java/com/tcms/api
git add backend/src/main/java/com/tcms/work backend/src/test/java/com/tcms/work
git commit -m "feat(work): the board composes issues; the nine stored-task classes go

BoardFacts gains its fifth crossing and the first into tracker — work still may not
depend on it, so the edge is one method in the one file allowed to name a foreign
table. An ISSUE card's allowed moves come from the project's workflow, not from
'every column but this one': WorkItem's model of an unconstrained status is over, and
that paragraph is deleted rather than left describing what changed.

derived_item_annotation survives with its rows, and now has an endpoint of its own —
D11's private note on a card you do not own is what makes a mixed board usable."
```

---

### T5 — `ts` · Issue verbs on the CLI and MCP

**Agent:** `tooling-engineer` (with `tool-cli-builder`, `tool-mcp-builder`,
`tool-capability-contract`) · **Stack:** `ts` · **Budget:** ~100k

**Deliverable:** no `work-item` verb remains in `packages/`; `tcms_write_issue` and
`tcms_read_issue` work against the new contract; `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`
lists every issue verb with its typed input and the shared output envelope.
**Depends on:** T4

**Files:**
- Modify: `packages/mcp/src/writes.ts:1255-1310`, `packages/mcp/src/reads.ts:607`,
  `packages/client/src/core/shapes.ts:817-908`, `packages/cli/src/reads.ts:575-582`,
  `packages/cli/src/writes.ts`, `packages/cli/src/commands.ts`
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`
- Test: `packages/mcp/src/writes.test.ts`, `reads.test.ts`, `writeConformance.test.ts`

**Correct the count before starting (D-d).** 009 says "the six `work-item` verbs"; there are four
sites and no CLI verb at all. What exists:

| Site | What it is |
|---|---|
| `mcp/src/writes.ts:1255` | `tcms_write_work_item`, one tool, two arms (`create`, `move`) |
| `mcp/src/reads.ts:607` | a `GET /work-items` read inside the orient tool, filtering `QUARANTINE` cards |
| `client/src/core/shapes.ts:817` | `WORK_ITEM_SHAPE`, registered at `:908` |
| `cli/src/reads.ts:580` | a comment naming `work-item` as a noun the CLI **does not deliver** |

- [ ] **Step 1: `tcms_write_work_item` becomes `tcms_write_issue`**

Two arms become four, and the gate changes from `workitem:manage` to the `issue:*` keys:

```typescript
const writeIssue: ToolDefinition = {
  name: 'tcms_write_issue',
  title: 'Raise an issue, move it, edit it, or comment on it',
  description:
    'Epics, stories, bugs, spikes and tasks. The board also shows DERIVED cards — outstanding '
    + 'executions, review requests, case-health repairs, quarantined cases — which are computed '
    + 'on every read from their source and cannot be created or moved here; fix the source '
    + 'instead. A `move` is refused unless the project workflow permits that exact pair, and the '
    + 'refusal names the moves that are allowed.',
  gate: { mode: 'any', permissions: ['issue:create', 'issue:edit', 'issue:comment'] },
  arms: [
    { action: 'create',     kind: 'create', permission: 'issue:create' },
    { action: 'update',     kind: 'update', permission: 'issue:edit' },
    { action: 'move',       kind: 'state',  permission: 'issue:edit' },
    { action: 'comment',    kind: 'create', permission: 'issue:comment' },
  ],
  ...
};
```

**`typeId` and `statusId` are UUIDs the caller cannot guess**, and this is the same problem
`shapes.ts:805` already solved for execution statuses — its note says *"a status guessed from
another project is refused; `tcms_orient` serves this project's own list"*. Do the same: the issue
tool takes a **type name** and a **status name**, resolves them against
`GET /projects/{projectId}/workflow`, and refuses an unknown one by listing what the project has.
An agent handed a bare UUID field will invent one.

- [ ] **Step 2: `tcms_read_issue`, and the orient tool's board read**

`reads.ts:607`'s `GET /work-items` becomes `GET /board` — the query parameter and the
`QUARANTINE` filter are unchanged. Its comment explaining *why* quarantine comes off the board is
still true and stays.

Add `tcms_read_issue`, taking a key, returning the issue with its children, comments, watchers,
doc-links and **`allowedTransitions` spelled as names** — an assistant that can see which moves are
legal does not attempt one that is not, which is the difference between a refusal it can act on and
a retry loop.

- [ ] **Step 3: `WORK_ITEM_SHAPE` becomes `ISSUE_SHAPE`**

Registered at `shapes.ts:908` under `issue`. `PRIORITY` is reused; the new fields are `typeName`,
`statusName`, `parentKey`, `estimateHours` and `acceptanceCriteria`.

- [ ] **Step 4: The CLI gains what it never had**

`cli/src/reads.ts:575-582` records `work-item` as one of three nouns the surface promises and does
not deliver. **M1 delivers it** — `tcms issue list`, `tcms issue show`, `tcms issue create`,
`tcms issue move`, `tcms issue comment` — so rewrite that comment to name the two that remain
(`report`, `triage`) rather than leaving it claiming a gap that has closed.

- [ ] **Step 5: Update the surface document**

`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` is the authority for this surface. Every
verb gets its typed input and the shared output envelope. **Say what actually shipped**, including
that the CLI gained issue verbs it never had for work items — the document is compared against the
tree by `citedFiles.test.ts` for the paths it names.

- [ ] **Step 6: Gate**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run lint --workspaces
~/bin/tcms-remote packages npm run test:run --workspaces
```

`npm run build` at the root, **never `--workspaces`**: that runs them alphabetically, so `cli`
would typecheck against a stale `client` dist. The root script names the order client → cli → mcp.

Expected: builds clean, lint at 0 warnings, tests green — including `citedFiles.test.ts`, which
fails if any path named in a comment anywhere is not on disk. **T4 deleted nine files; a comment in
`frontend/` or `backend/` still citing one reddens this job**, which is where that failure surfaces.

- [ ] **Step 7: Commit**

```bash
cd ~/RiderProjects/tcms
git add packages/ docs/specs/2026-08-08-mcp-tool-and-command-surface.md
git commit -m "feat(tools): issue verbs replace the work-item tool

Four sites, not the six 009 estimated — and the CLI had none at all, which its own
comment recorded as an undelivered promise. It has them now.

The tool takes type and status NAMES, not UUIDs, and resolves them against the
project's workflow: an agent handed a bare UUID field invents one. Same resolution
the execution-status field already makes for the same reason."
```


---

### T6 — `fe` · The tracker screens

**Agent:** `fe-react` (with `fe-react-data-fetching`, `fe-react-forms`, `fe-react-ui-rules`,
`fe-react-component-testing`, `fe-react-accessibility`) · **Stack:** `fe` · **Budget:** ~150k — on
the cap. **Seam if it runs out:** land Part A, commit, do Part B next.

#### Part A — the data layer, the work table and the new-issue form

**Deliverable:** `/projects/:projectId/work` lists the project's issues in a sortable table with a
filter that survives a reload, and `/projects/:projectId/work/new` creates one from a single field —
both through `DataRegion`, one `keys` factory, and labels that make an unlabelled enum member a
compile error.
**Depends on:** T5

**Files:**
- Create: `frontend/src/api/queries/issues.ts`, `frontend/src/features/tracker/WorkTablePage.tsx`,
  `IssueNewPage.tsx`, `TypeIcon.tsx`, `StatusBadge.tsx`
- Modify: `frontend/src/api/queries/keys.ts`, `frontend/src/api/queries/index.ts`,
  `frontend/src/lib/labels.ts`, `frontend/src/lib/devFaults.ts`, `frontend/src/app/router.tsx`,
  `frontend/src/app/AppShell.tsx`
- Delete: `frontend/src/api/queries/workItems.ts`, `frontend/src/features/board/TaskCreateDialog.tsx`
  and its test
- Test: `frontend/src/features/tracker/WorkTablePage.test.tsx`, `IssueNewPage.test.tsx`

**The name collision, decided:** `frontend/src/api/queries/tracker.ts` already exists and is the
**external** Jira/ADO integration. The new module's queries go in **`issues.ts`**, which is what
`009-execution-breakdown.md`'s `t7` file list already says. Do not rename the existing file.

- [ ] **Step 1: Extend the one `keys` factory**

`keys.ts` is the single factory and **a second one is never added** — the file's own header explains
that two modules deriving one key shape drift silently in the direction of an invalidation that
matches nothing.

```typescript
  /**
   * A project's issues, keyed by the filter and the sort — the work table (§5's Table view).
   *
   * Under `['projects', p]` rather than a root of its own, so archiving or renaming a project
   * sweeps its issues with it. The filter object is part of the key because two filters are two
   * caches: D34 says the filter survives switching views, which is a URL concern, but a filter
   * that survived into the WRONG cache would serve one filter's rows under another's chips.
   */
  issues: (p: string, filters: IssueFilters, sort: string) =>
    ['projects', p, 'issues', { ...filters, sort }] as const,
  /**
   * One issue, BY KEY and not by id — the key is what the route carries and what a person types.
   *
   * Not under `keys.issues`' filter-bearing entry: a detail read must not be swept by a list
   * invalidation that only changed which rows matched a chip.
   */
  issue: (k: string) => ['issues', k] as const,
  issueComments: (k: string) => ['issues', k, 'comments'] as const,
  issueDocLinks: (k: string) => ['issues', k, 'doc-links'] as const,
  /**
   * The prefix every issue detail shares. A transition changes the issue AND its epic's
   * completion, so a move invalidates this root and `keys.issues` together — the epic's
   * percentage is on a different screen and must not be able to lag behind the status that
   * produced it (D8: one number, one place).
   */
  issueRoot: ['issues'] as const,
  /**
   * The project's statuses, types and legal moves. Beside `statuses` and `configurations` rather
   * than under an issue, because it is a property of the PROJECT and outlives every issue that
   * names it — an issue-scoped key would be refetched by every issue write.
   */
  workflow: (p: string) => ['projects', p, 'workflow'] as const,
```

**`workItems`, `workItemRoot`, `myWork` and `teamBoard` keep their entries and their names**, with
their comments amended: the root literal becomes `'board'` to match the path, and each comment's
reference to a task becomes a reference to an issue. Their *argument* — one root so all three
boards refresh together — is unchanged and is the reason the entries survive.

- [ ] **Step 2: Label every new enum**

`labels.ts`'s point is `satisfies Record<Union, string>`: a member added to the contract is a
**compile error** until it is labelled. Three new unions:

```typescript
/** `IssueLevel`. Shown in the type picker's grouping and nowhere else — the icon carries it elsewhere. */
export const issueLevels = {
  EPIC: 'Epic',
  STORY: 'Story level',
  TASK: 'Sub-task level',
} as const satisfies Record<IssueLevel, string>;

/**
 * `IssueStatusKind`. NOT a status name — a project names its own statuses, and this is what a
 * status MEANS. Rendered on the workflow settings screen beside each status, and used to pick a
 * badge tone everywhere else.
 */
export const issueStatusKinds = {
  NOT_STARTED: 'not started',
  IN_PROGRESS: 'in progress',
  DONE: 'done',
} as const satisfies Record<IssueStatusKind, string>;

/**
 * The basis of a completion percentage (D8), and it is rendered VERBATIM beside every number.
 * `epic.html` and `project-overview.html` both show it as a small caption — "by estimate",
 * "by count" — because a percentage that changes meaning silently is the failure D8 exists to
 * prevent, and the caption is the whole mitigation.
 */
export const completionBases = {
  COUNT: 'by count',
  ESTIMATE: 'by estimate',
} as const satisfies Record<CompletionBasis, string>;
```

`workItemColumns` — declared `satisfies Record<WorkItemStatus, string>` — **is deleted with the
type it labels**. Board card tones now come from `issueStatusKinds`.

- [ ] **Step 3: Build the fault switch before the screens it proves**

CLAUDE.md's rule, and it is sequential on purpose: *a resilience behaviour you cannot trigger is one
you have not tested.* `devFaults.ts`'s `verdictFor` already scopes faults to path families;
`/issues`, `/board` and `/projects/*/workflow` are three new ones. Two faults are specific to this
module and neither can be produced by using the app:

```typescript
/**
 * The tracker's own fault modes, beyond the four every family gets.
 *
 * `conflict` is the one worth having and the one no ordinary use produces: the server refuses a
 * transition the workflow forbids with a 409 that NAMES the moves that are allowed. The screen has
 * to show that refusal beside the picker rather than as a toast that scrolls away — and the only
 * way to see it during development is to force it, because the picker only ever OFFERS legal moves.
 *
 * `drift` on `/issues/{key}` serves an issue whose `completion.basis` is absent. It must read as
 * FAILED, not as a percentage with no caption — a number whose basis is missing is exactly the
 * thing D8 says must never render.
 */
export type IssueFault = 'conflict' | 'drift';
```

Both guards stay a literal `import.meta.env.DEV`, so Rollup drops the module from a production
build. **Routing that check through an imported constant ships it** — that is written in CLAUDE.md
because it has happened.

- [ ] **Step 4: `queries/issues.ts`**

One file, every read and write, behind the barrel. Each read carries its `enabled` gate and its
endpoint-class timeout budget — copy the budget from `queries/cases.ts`, which is the closest
analogue in shape and volume.

The invalidation that matters, and it is the one a reviewer should check:

```typescript
/**
 * Moving an issue.
 *
 * Invalidates THREE things, and the third is the one that is easy to miss: the issue itself, the
 * project's issue lists, and `keys.issueRoot` — because a move changes the PARENT epic's
 * completion, and the epic is a different cache entry on a different screen. D8's "one number, one
 * place" is a server-side rule; on the client the equivalent is that the number's cache cannot
 * outlive the status that produced it.
 *
 * Not optimistic. A transition can be refused by the workflow, and an optimistic move would show
 * the card in a column the server is about to reject — which is precisely the "snapping back in
 * silence" story H5 rules out. M2's board gets optimism where a drag makes it worth the machinery.
 */
```

- [ ] **Step 5: `WorkTablePage`, from `work-table.html`**

The mockup is the source of truth for what the screen shows. Port: the five-tab view switcher
(**Board, Backlog, Timeline and Calendar render a "not in this module yet" panel naming M2/M3 —
they are in the nav because D34 makes Work one entry, and a tab that vanishes and reappears between
releases is worse than one that says when it arrives**), the wrapping filter chip row, the sortable
table, the `⚙ Columns` picker, and the footer's count and totals.

**Columns M1 does not have are absent from the picker, not greyed** (D-f): no Release, no Logged, no
labels, no `AI-eligible` chip. The mockup's closing note — *"Logged hours are here because this
project has time tracking on"* — describes M6 and is not rendered.

Filter state lives in the URL (`useSearchParams`), as everywhere. D34: switching views never loses
it.

- [ ] **Step 6: `IssueNewPage`, from `issue-new.html`**

Two forms on one screen and the top one is the point: **quick add is one field.** A type chip, a
title input, an Add button, and the caption *"Creates a Task at the story level with no parent. You
can attach it to an epic later — or never."* Story H3.

The full form below it carries type, summary, description, acceptance criteria, epic, assignee,
estimate and due date. **Target release, Labels and the AI-eligible toggle are absent** (D-f), and
so are the "Where this lands" panel's release and backlog cards — the epic card stays, because
completion recalculating on create is M1's.

- [ ] **Step 7: Test the states no static render exercises**

```typescript
  it('renders the failed state through DataRegion rather than an empty table', async () => {
    drivingAFailedRead('the work table must say the read failed — an empty table and a failed '
      + 'read look identical to a reader, and the second one is recoverable');
    ...
  });

  it('offers a first action when the project has no issues at all', async () => {
    // An empty state carries the action that resolves it — the mockups' own rule,
    // and the reason solo-overview.html's release panel is a template for this one.
  });

  it('refuses a sub-task with no parent and names the field', async () => {
    // The depth rule, from the client end. The server refuses it too (IssueEditTest),
    // and the form must not let a person type a title, press Create and be told no —
    // the parent picker appears the moment a TASK-level type is chosen.
  });

  it('keeps the filter across a view switch', async () => {
    // D34, and it is a URL assertion rather than a component one.
  });
```

Use `createTestQueryClient` — **never build your own `QueryClient` in a test**, only that one has
the caches — and the shared `fixtures.ts`, so every exchange is checked against `openapi.yaml`.
Anchor path-keyed mock arms (`/^\/issues\/[^/]+$/`) so `/issues/ACME-1/comments` is not caught by
the detail arm's prefix.

- [ ] **Step 8: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

`npm run build` is `tsc -b` + `vite build` — **not `tsc --noEmit`**, which has reported 0 errors
while the build failed with nine.

```bash
cd ~/RiderProjects/tcms
git add frontend/src/api/queries frontend/src/features/tracker frontend/src/lib frontend/src/app
git commit -m "feat(work): the issue table and the one-field quick add

Quick add is one field, which is the whole point of D29 — a solo project should not
need an epic and a story before 'a thing to do'. Columns M1 has no data for are
ABSENT from the picker rather than greyed: an empty Release column reads as a release
that is not set, not as a feature that has not shipped.

The transition fault switch lands before the screen it proves — the 409 that names
the legal moves cannot be produced by using the app, because the picker only offers
legal ones."
```

---

#### Part B — epic, story, task and the documents panel

**Deliverable:** `/issues/:key` renders as an epic, a story or a task according to its type, each
moving the issue through the transition menu; opening a document shows which issues link to it.
**Depends on:** Part A

**Files:**
- Create: `frontend/src/features/tracker/IssueDetailPage.tsx`, `EpicPanels.tsx`, `StoryPanels.tsx`,
  `TaskPanels.tsx`, `CompletionMeter.tsx`, `TransitionMenu.tsx`, `CommentFeed.tsx`,
  `LinkedSpecPanel.tsx`, `AcceptanceCriteria.tsx`
- Modify: `frontend/src/features/docs/` (the "Linked from" panel), `frontend/src/app/router.tsx`
- Test: one `.test.tsx` per screen

- [ ] **Step 1: One route, three shapes**

`epic.html`, `story.html` and `task.html` are one URL — `/issues/:key` — because a key does not say
its level and a reader who follows `ACME-118` from a commit message does not know which they are
getting. `IssueDetailPage` reads the issue and dispatches on `type.level`; the shell (header chips,
title, transition menu, comments, watchers, details `dl`) is shared and the panels differ.

- [ ] **Step 2: The completion meter states its basis, always**

```tsx
/**
 * An epic's completion — the percentage, the meter, and **the basis beside it, without exception**.
 *
 * The caption is not decoration. D8: an epic weighted by hours and one counted by heads are
 * different measurements, and a number that changes meaning when somebody clears an estimate is
 * the number people learn to distrust. `epic.html` renders the basis as a chip beside the figure
 * and spends a whole note on it — "the moment one does not, this switches to by count and says so".
 *
 * The count is shown beside the percentage (D31: `12 of 17 stories done`), and the excluded spikes
 * are named when there are any — "1 spike excluded from the percentage" — because an epic whose
 * denominator is smaller than its child list is otherwise just wrong-looking.
 *
 * **This component computes nothing.** `percent`, `basis`, `done`, `total` and `excludedSpikes` all
 * arrive from the server. D8's "one number, one place" — this repo has shipped two contradictory
 * percentages on one screen, and a component that did its own arithmetic is how.
 */
```

- [ ] **Step 3: The transition menu shows what is closed to you, and why**

`task.html`'s "Where it can go" panel is explicit, and its caption is the requirement: *"Disabled,
not hidden — you can see the move exists and why it is closed to you."* So the menu renders every
status in the project with the illegal ones disabled and captioned, not a list of only the legal
ones.

**An Epic's menu is not rendered at all**, replaced by a caption saying the status is derived from
its stories (D31). A disabled menu would read as "you lack a permission"; the absence plus the
sentence reads as what it is.

- [ ] **Step 4: The story's closing rule, and what M1 does not render**

`story.html`'s "Closing this story" panel makes one claim M1 owns and two it does not:

- **M1:** *"All 4 sub-tasks are done. The story did **not** close itself. Closing is your decision —
  sub-tasks finishing is evidence, not a trigger."* Story H4's third criterion. Render it.
- **M5:** the review-gate warning and the "Request approval to close" button. Absent (D-f).
- **M6:** the "Time" panel. Absent.

The "Linked test cases" panel is **H7**, which the execution breakdown places in M3 `t18`. Absent,
and its absence is why `story.html`'s red footer — *"TC-311 failed, so 2.4 is not ready"* — does not
render either.

- [ ] **Step 5: The linked-spec panel, and the stale flag**

`epic.html`'s two link cards are the shape: a normal one, and one with a warning border, a
"changed since linked" badge, the excerpt **as it was linked**, and a Re-confirm button.

Re-confirming is a `documentation` write (T4 Part A's arm), so the button targets that endpoint and
invalidates `keys.issueDocLinks`. The caption under it — *"The excerpt above is what you linked.
Re-confirming replaces it with the current text and clears this flag"* — is the whole explanation
of what `stale` means and it is rendered verbatim.

- [ ] **Step 6: The documents "Linked from" panel**

`documents.html` is an existing screen gaining one panel: the issues linking into this page, each
with its type icon and its section. Its footer states the rule — *"Editing a linked block flags
every link to it as 'changed since linked' until someone re-confirms"* — which is the same `stale`
seen from the other end.

The highlighted block treatment (`§3.2`, a "linked by ACME-101" badge, an "Open the epic" button)
renders when the URL carries a block anchor, which is what the epic's "Open at this block" link
sends.

- [ ] **Step 7: Test what a static render cannot**

```typescript
  it('names the basis beside every percentage', async () => {
    // The one assertion that would have caught the two-contradictory-percentages
    // bug this repo has already shipped.
  });

  it('shows a forbidden move as disabled with its reason, not as absent', async () => {
    // task.html's caption is the requirement. Hiding it makes the workflow look
    // broken rather than configured.
  });

  it('renders no transition menu on an epic, and says why', async () => { ... });

  it('renders the failed and empty states of every panel through DataRegion', async () => {
    drivingAFailedRead('an epic whose children failed to load must not read as an epic with no '
      + 'children — the second is a real state and the first is recoverable');
  });

  it('surfaces a refused transition beside the picker, not as a toast', async () => {
    // Driven by tcmsFaults' `conflict`, built in T6 Part A. A toast scrolls away and the
    // card is left looking like it moved.
  });
```

- [ ] **Step 8: Gate and commit**

```bash
~/bin/tcms-remote sync && ~/bin/tcms-remote frontend npm run build && ~/bin/tcms-remote frontend npm run lint && ~/bin/tcms-remote frontend npm run test:run
cd ~/RiderProjects/tcms
git add frontend/src/features/tracker frontend/src/features/docs frontend/src/app/router.tsx
git commit -m "feat(tracker): the epic, story and task screens

One route for all three: a key does not say its level, and somebody following
ACME-118 out of a commit message does not know which they are getting.

A forbidden move renders disabled with its reason rather than absent — hiding it
makes a configured workflow look like a broken one. An epic has no menu at all,
because a disabled one would read as a missing permission rather than as a status
derived from its stories."
```


---

### T7 — `fe` · Settings, My Work, the overview, and the close-out

**Agent:** `fe-react` (with `fe-react-data-fetching`, `fe-react-ui-rules`,
`fe-react-component-testing`, `fe-react-accessibility`) · **Stack:** `fe` · **Budget:** ~148k — on
the cap. **Seam if it runs out:** land the two settings screens, commit, do My Work and the overview
next. **This is the task that closes the module's "a person can now …".**

#### Part A — the screens

**Deliverable:** the workflow settings screen lists the six seeded statuses with their kinds and
refuses to orphan an issue; My Work shows the caller's issues across two projects beside their
pending reviews and executions, with the private note and personal due date; `/projects/:id` lands
on epics, unscheduled backlog and test health, and explains rather than empties the sections M1 has
no data for.
**Depends on:** T6

**Files:**
- Create: `frontend/src/features/settings/WorkflowSettingsPage.tsx`,
  `GeneralSettingsPage.tsx`, `frontend/src/features/projects/ProjectOverviewPage.tsx`
- Modify: `frontend/src/features/board/MyWorkPage.tsx`, `BoardPage.tsx`, `TeamBoardPage.tsx`,
  `frontend/src/features/dashboard/DashboardPage.tsx`, `frontend/src/app/router.tsx`,
  `frontend/src/app/AppShell.tsx`
- Test: one `.test.tsx` per screen, plus `MyWorkPage.test.tsx` extended

- [ ] **Step 1: The workflow settings screen**

`settings-workflow.html`, ported whole. Three things in it are requirements rather than decoration:

- **The note at the top** — *"Every percentage in this project is computed from the **done** column
  below. A status marked *in progress* is not partial credit — it is simply not done."* This is
  where D8's rule is explained to the person who can change it, and it is the only place in the
  product that says so.
- **`↑ / ↓` beside every row, not only drag.** WCAG 2.2 *Dragging Movements*, and the mockups' own
  standing rule: *dragging is never the only way.* Story B1 reorders statuses and B4 ranks the
  backlog; both get the keyboard path.
- **The removal panel.** Selecting a status that issues hold opens the panel `settings-workflow.html`
  renders in full — the count, the destination select, the affected issue chips, and a
  `Remove and move 4 issues` button that names the number. Story B1's third criterion. The server
  refuses the last `DONE` status; the screen disables its remove control and says why rather than
  letting the person reach a 409.

- [ ] **Step 2: The general settings screen**

`settings-general.html`, minus what M1 has no data for (D-f): **no Client select** (Mc), **no
"Closing work" toggles** (M5), **no "Time" panel** (M6). What lands is Identity (name, key, with the
caption *"Prefixes every issue in this project — `ACME-124`. Existing links keep working if it
changes"*) and the **Issue types** list.

The types list is **six rows, not the mockup's five** (T2 Part C): `Epic`, `Story`, `Task`, `Bug`,
`Spike` and `Sub-task`. The mockup shows five and separately shows an "Add sub-task" affordance on a
story — both cannot be true with one `Task` type, because D29 puts `Task` at the **story** level.
The recorded resolution is six types with two rendering as `T`, and the screen's captions say which
level each sits at so the difference is visible rather than confusing.

The panel's footer is kept verbatim, because it is the promise D12 makes to every existing user:
*"Tasks created before the upgrade are still called **Task**. Nothing you typed changed meaning
underneath you."*

- [ ] **Step 3: My Work — the composite, as two tables**

**`my-work.html` is not a kanban**, and the current `MyWorkPage` is. The mockup renders two tables —
"Assigned issues" and "Test executions & reviews" — plus a "My notes" panel. **The mockup wins on
what the screen shows**, so the personal board becomes two tables over the same `GET /board`
response, split on `kind === 'ISSUE'`.

That is a real change to a screen that already works, and it is the thing D11 warns is easy to miss.
What must stay true:

- **One request, not two.** The composite is still one operation; the split is a client-side
  partition of one response. Two requests would let the halves disagree about who the caller is.
- **Both projects in one list.** Story B5's first criterion, and the footer says it: *"Two projects,
  one list — nothing here asks which project you meant."*
- **The private note is on a card the viewer does not own.** The "My notes" panel lists the caller's
  `derived_item_annotation` rows with their personal due dates and the caption *"teammates do not
  see this"*. Writing one is T4 Part B's `PUT /board/annotations/...`.

**Absent (D-f):** the `AI` tags, the "My assistant" panel (M5), the "This week" hours KPI (M6).

`BoardPage` and `TeamBoardPage` keep their column layout — they are boards and M2 rebuilds them —
and change only in that a card's status is now `{name, kind}` and `TASK` is `ISSUE`.

- [ ] **Step 4: The project overview**

`DashboardPage` becomes `ProjectOverviewPage` at `/projects/:projectId` (D27). Its existing QA
regions are reused as the overview's quality half — that is what D27 means by *"the existing
DashboardPage regions are reused"*, and it is why **the test-health panel lands here rather than in
M3**. 009's M1 says the page arrives "without its release and **test-health** panels"; that is wrong
in the cheap direction, and D-f records it.

What lands: the epics list with completion meters and their bases, the unscheduled backlog table,
the test-health panel, and two of the four KPI tiles (**Open issues** and **Tests passing**).

What does not, and **how it says so** — this is the acceptance criterion 009 states for this screen:

```tsx
/**
 * The next-release panel, on a project that has no releases.
 *
 * **A missing section explains itself; it does not render empty.** `solo-overview.html` is the
 * template and it is not a different screen — it is this screen for a company with no clients, and
 * its release panel is the empty state this one uses whenever `releases` is empty: a glyph, "No
 * releases yet", a sentence saying what a release IS and what it buys, and the action that creates
 * one.
 *
 * In M1 that state is the ONLY state, because releases arrive in M3. The panel is written against
 * the endpoint from the start so M3 turns it on by having data, not by replacing the component —
 * and the panel's footer already says the thing that keeps the page honest in the meantime: "the
 * epics section above keeps working regardless — the sections do not depend on each other."
 */
```

**Budget burn and Team are not rendered at all** (M6, M7) — an empty-state card for a feature the
instance does not have is an advertisement, not an explanation. The difference from the release
panel is that a release is a thing this project will have and has none *yet*; a budget is a module
that is not installed.

- [ ] **Step 5: `solo-overview.html` is an acceptance criterion, not a screen**

The same component, for a company with no clients: **no client chip in the header, no client column
anywhere, and no "attach a client" prompt.** The mockup's own note says the Clients sidebar entry
staying is "the only trace". Assert it:

```typescript
  it('renders no client chip and no client prompt when the company has no clients', async () => {
    // solo-overview.html. Nothing nags a company that has none — an empty "Client:
    // —" chip is exactly the nagging the mockup rules out, and it is what a naive
    // port produces.
  });

  it('renders the quick-tasks panel for a project whose issues are all parentless', async () => {
    // The solo case's shape: no epic, no story, no ceremony. It is a filter over
    // the same list, not a second endpoint.
  });
```

- [ ] **Step 6: The rest of the tests**

```typescript
  it('shows issues from two projects beside reviews and executions', async () => {
    // Story B5's first criterion, and 009's done-when for the whole module.
  });

  it('does not show one user\'s private note on another user\'s board', async () => {
    // B5's second criterion. Asserted from the screen because the endpoint being
    // right and the screen rendering the wrong person's note is a separate failure.
  });

  it('lists the six seeded statuses with their kinds', async () => { ... });

  it('asks where issues go before removing a status that holds them', async () => { ... });

  it('explains the release section rather than rendering an empty box', async () => {
    // 009's acceptance criterion, worded as a negative because the failure is a
    // panel that renders with nothing in it and no explanation.
  });

  it('renders the overview through DataRegion when the epics read fails', async () => {
    drivingAFailedRead('a project overview whose epics failed must not read as a project with no '
      + 'epics — the sections do not depend on each other, so the rest of the page still renders');
  });
```

- [ ] **Step 7: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

- [ ] **Step 8: Commit**

```bash
cd ~/RiderProjects/tcms
git add frontend/src/features/settings frontend/src/features/board frontend/src/features/projects frontend/src/features/dashboard frontend/src/app
git commit -m "feat(work): My Work composes issues; the project overview lands

My Work stops being a kanban and becomes two tables over one /board response, which
is what my-work.html shows — one request, not two, so the halves cannot disagree
about who the caller is.

A section M1 has no data for EXPLAINS ITSELF: the release panel renders
solo-overview.html's empty state, written against the endpoint from the start so M3
turns it on with data rather than replacing the component. Budget and Team are not
rendered at all — an empty-state card for a module that is not installed is an
advertisement, not an explanation."
```

---

#### Part B — close the module

**Deliverable:** `docs/features.md`, the MCP surface document and plan 009's status table say what
actually landed, and the prompts of every module that depends on M1 are true again.
**Depends on:** Part A

**Files:**
- Modify: `docs/features.md`, `docs/plans/009-project-management-expansion-modules.md`,
  `frontend/src/features/help/sections.tsx`

- [ ] **Step 1: Run the whole gate, on `shanks`, before writing a word of this**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew build
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run lint --workspaces
~/bin/tcms-remote packages npm run test:run --workspaces
```

`com.tcms.reporting.FlakeScoreTest`'s six failures are **pre-existing and proven so** — it pins
`BASE = 2026-06-01T08:00:00Z` against a 90-day window that expired on 2026-08-30. Do not claim them
and do not fix them here.

- [ ] **Step 2: Read the Help page — no test sweeps it**

`frontend/src/features/help/sections.tsx` is hand-written prose that **no gate checks**.
`tierClaims.test.ts` catches only a claim that somebody *lacks* a permission, and M0 shipped three
false sentences in this file past every gate. M1 moved no grant, but it retired `workitem:manage`
and renamed the board's endpoint — read the file and correct anything that describes the board as
holding tasks.

- [ ] **Step 3: Rewrite `docs/features.md`'s work section**

Struck through and superseded, never deleted — the working agreement. The board section's claim
that a stored card is a task somebody typed becomes the claim that it is an issue, **with the old
sentence left visible** so a reader who finds `work_item` in the git history knows where it went.

- [ ] **Step 4: 009's hand-off protocol, all six steps**

1. **Status table.** M1's row → `done YYYY-MM-DD @ <short sha>`.
2. **Built and unverified.** Under 009's M1 section. The gates prove it compiles. At minimum:
   - Every screen T6–T7 built, if nobody has clicked through the lab.
   - The `work_item` migration on an instance that actually holds rows — the count test proves it
     on a synthetic fixture, not on the lab's data.
   - `issue_fact`'s `release_assigned_at`, written by nothing until M3.
   - `issue_link`, which has no writer until M3's H7 work.
   - **The lost 409 message** (T4 Part B Step 3): moving a derived card used to answer *"this card mirrors
     a source; fix the source instead"* and now answers 404 from `/issues/{key}`. A known
     regression, restored by M2's board.
3. **The migration actually taken.** If it was not `V3`, correct 009's table **and** the M1 `**db**`
   line, striking the reserved number rather than deleting it.
4. **Rewrite the dependent prompts** — every module whose *Depends on* names M1: **M2, M4, M5** and
   **M6**. Each needs, as it actually landed: the endpoint paths (`/issues`, `/issues/{key}`,
   `/board` — **not `/work-items`**), the package name (`com.tcms.tracker`), the outbox event name
   (`tracker.issue_changed`), `issue_fact`'s column list, and the two decisions downstream modules
   must honour:
   - **There is no `ProjectCreatedEvent`** (D-b). A module adding a per-project default seeds it in
     `tcms_seed_project_defaults` and reproduces the function in full.
   - **A card's status is `{name, kind}`** (D-c) and `WorkItemStatus` is gone. M2 builds board
     columns over `kind` plus the project's own statuses.

   Edit the prompt text in place; do not append a changelog under it.
5. **Close the review items M1 owned.** **R4** (the `work` file list, the contract removal and the
   matrix strike-through) and **R5** (the workflow seeding) are both M1's. R5 closes as
   *resolved differently from the proposal* — record D-b's reason in the review table, not just the
   outcome, so the next reader does not re-propose the event.
6. **Commit 009 on its own**, `docs(plan): M1 done`, staging only that file.

- [ ] **Step 5: Record the three findings this module chose not to fix**

In 009's M1 section, so the next agent finds the answer rather than the finding:

- **The three hardcoded `'Agent'` role names** (D-h) — left in place, with the reason: they select
  the one `assignable = false` role to decide which configuration block to print, they predate M0,
  and removing them needs a stable role code on the contract that no module has asked for.
- **`settings-general.html` shows five issue types; the product seeds six** (T2 Part C) — the
  mockup's own "Add sub-task" affordance requires a `TASK`-level type, and D29 puts `Task` at
  `STORY`.
- **009's M1 says `project-overview.html` lands without its test-health panel** (D-f) — it landed
  with it, because `DashboardPage` already rendered it against endpoints that exist and D27 says
  the overview reuses those regions.

- [ ] **Step 6: Say what is built and unverified, in the chat as well as the plan**

CLAUDE.md: *the gates prove it compiles, not that it works* — a no-op theme toggle, two
contradictory percentages on one screen and a provider-ordering bug that rendered nothing but the
error page all typechecked and linted clean. **Say "built and unverified" when that is what it is.**

---

## Self-review

**1. Spec coverage.** Stories H1 (epic + doc links + completion + count) → T3, T4, T6. H2 (stories,
bugs, spikes under an epic; spike excluded) → T3, T6. H3 (quick task, one field) → T2 Part C's
`Task` type, T6. H4 (break into sub-tasks; does not self-close) → T3, T6. H5 (legal moves only;
forbidden move refused with a reason; epic status derived) → T3, T6. H6 (estimate in hours; basis
stated) → T1, T3, T6. H8 (comments, watchers, history) → T4, T6. B1 (configure the workflow;
sensible default; removal asks where issues go) → T2 Part C, T3, T7. B5 (My Work composite; private
note) → T4, T7. D8 → T3 Part B. D11 → T4 Part B, T7. D12 → T2 Part D. D29 → T2 Part C. D31 → T3
Part B. **H7 (link a story to its test cases) is M3's**, per the execution breakdown's `t18`;
`issue_link` lands in T2 with no writer, which T7 Part B records.

**2. Placeholder scan.** The one deliberate "copy this rather than read a paraphrase" is T2 Part B
Step 1's instruction to lift blocks 1–5 of `tcms_seed_project_defaults` out of `V2` verbatim. That
is not a placeholder: restating them here would create a third copy of a function whose whole
failure mode is a block silently differing between copies, and the step names the file, the blocks
and the test that catches an omission.

**3. Type consistency.** `IssueStatusKind` is `NOT_STARTED | IN_PROGRESS | DONE` in the contract
(T1), the CHECK constraint (T3), `Completion`/`EpicStatus` (T4), `CardStatus` (T6) and
`issueStatusKinds` (T8). `Basis` is `COUNT | ESTIMATE` in T1, T4 and T8's `completionBases`.
`BoardCard.status` is `{name, kind}` in T1, T6 and T10.

**4. Order.** T1 (contract) → T2 (schema) → T3–T4 (`be`) → T5 (`ts`) → T6–T7 (`fe`).
Every dependency points backwards. The one thing that cannot start at its position is nothing: T2
needs the contract only for the shapes it stores, T7 needs T6's `/board` to exist, and T8 needs T7
only because both stacks share the generated client that T1 produced.

**5. Deliverables.** Seven tasks, seven `**Deliverable:**` lines, each a fact about the world
rather than an activity.

**6. The stack rule.** Every task is one stack — four `be`, one `ts`, two `fe` — with its agent
named and its 150k budget counted from what the session actually loads. The three on the cap (T3,
T6, T7) each name a seam **inside their own stack** to split on, and the close-out rides with T7
rather than taking a session of its own.

---

## Prompts — one per task

**One task is one session, so one task is one prompt.** Each block below is pasted whole into a
fresh session. They are written to be complete on their own: the agent reads what it names, does
the task, runs its gate and commits. Nothing here assumes the reader has seen another prompt.

**Run them in order.** A task's `Depends on` must be committed before its prompt is pasted — the
sequence is `T1 → T2 → T3 → T4 → T5 → T6 → T7`, and 009's status table is what records that M1 is
running at all.

**These are living text.** A task that lands something different from what its plan section
predicted — a renamed endpoint, a column that moved — **rewrites the prompts below it before it
stops**, in place, no changelog appended. The next session trusts its prompt and the plan section
it names, and re-verifies nothing else.

---

### T1 — `be` · The tracker contract

````text
You are working in the tcms repo. Read CLAUDE.md first; the plugin skills it names are mandatory —
/be-java for this task.

Execute T1 of docs/plans/011-tracker-core.md. Read, in this order: that plan's "Decisions taken
before the tasks" section in full (nine decisions, two of them owner-confirmed — D-a and D-c decide
what you are about to write), then "How this plan is cut", then T1 itself.

You are the FIRST task of module M1. Nothing in tracker exists yet.

Context you need that the plan states but is worth having up front:

- GET /work-items is NOT work-item CRUD. It is the one operation serving all three boards —
  /projects/:id/board, /my-work and /teams/:id/board — which D11 keeps composite. Removing the path
  as plan 009 wrote it would delete My Work. The PATH goes; the operation becomes GET /board with
  ISSUE replacing TASK as the row-backed card kind. This was put to the owner and confirmed.
- A board card's status becomes an object {name, kind}, and WorkItemStatus is deleted. An issue's
  status is a per-project issue_status row, so a fixed five-member enum cannot spell it without a
  mapping table — and a card carrying two spellings of where it is, is the two-contradictory-numbers
  failure this repo has already shipped once.
- M0 already seeded the whole issue:* permission slice. You add NO permission. Do not touch the
  three pinned matrix copies in this task — that is T2 Part A, and it is a separate commit.

Then:
1. Write openapi.yaml per T1's steps 2-4. EVERY schema gets its `required` array, including inline
   nested objects — `required` does not descend, and without it every generated property is
   optional. Delete the two /work-items paths and the four WorkItem* schemas; Priority STAYS
   (test_case uses it). Grep the file for prose naming the removed path — there are five sites
   outside the operations — and correct each.
2. Regenerate BOTH clients in the SAME commit: `cd frontend && npm run gen:api` and
   `cd packages/client && npm run gen:api`. Two copies of one contract; a commit carrying one is
   the drift the pairing exists to prevent.
3. Do NOT edit contractBodies.ts in either copy. Its shared region is a generic walker over spec()
   with no per-schema bodies, so new endpoints need no change there — verify with
   `npm run test:run -w @tcms/client -- contractPairing`.
4. The tree does NOT build after this commit: frontend and packages both still reference the removed
   WorkItem types. That is expected and is repaired by T4 and T5. Your gate is the contract parse
   check and contractPairing, not a full build. Say so in the commit message rather than leaving the
   next session to discover it.
5. Commit exactly: docs/specs/openapi.yaml, frontend/src/api/schema.d.ts, packages/client/src/schema.ts.
   `git add <paths>`, never `git add -A` — concurrent sessions happen in this repo.

Work on the current branch. Check with `git rev-parse --abbrev-ref HEAD`; do not create one.
````

---

### T2 — `be` · `V3`, and the roles copies

````text
You are working in the tcms repo. Read CLAUDE.md first; the plugin skills it names are mandatory —
/data-postgresql and /data-migrations for this task, under the data-architect agent.

Execute T2 of docs/plans/011-tracker-core.md — four parts, four commits. Read that plan's
"Decisions taken before the tasks" (especially D-b, which is owner-confirmed and reverses what plan
009 asked for), then T2's four parts.

BEFORE WRITING THE FILE: `ls backend/src/main/resources/db/migration/`. The plan reserves V3 and the
directory is the authority. `out-of-order` is unset, so a lower number arriving after a higher one is
silently NOT APPLIED, and Testcontainers builds each database from scratch so it will never show you
the conflict.

Also read, because you are editing what they describe: V1's work_item/work_item_link/
derived_item_annotation/doc_link/case_doc_link sections and its RLS loop; V2 IN FULL.

The two things most likely to go wrong, both stated at length in the plan:

- D-b: the workflow is seeded by tcms_seed_project_defaults, NOT by the ProjectCreatedEvent plan 009
  and its review R5 called for. A Java event type may not cross a module boundary in this codebase at
  all (CompanyScopedEvent's own header), and outbox delivery has a PT1M floor — a new project would
  have no statuses for up to a minute, so its first issue could not be created. The function is
  synchronous, transactional, and already called by all three creation paths.
- That means V3 reproduces the WHOLE function again under CREATE OR REPLACE, as V2 already did once.
  EVERY block V2 has must appear — the Feature dimension, the seven case statuses, the Default
  configuration, the doc space, the key counter — or the ones you omit silently stop being seeded for
  every project created afterwards. Copy them out of V2 VERBATIM; do not paraphrase, that is how a
  block loses a WHERE NOT EXISTS. Extend ProjectKeySequenceSchemaTest to assert all seven blocks.

Then:
1. Part A — strike `workitem:manage` through as "→ superseded by issue:*" in the roles spec §A3,
   permission-matrix-summary.md and README.md. ALL THREE IN ONE COMMIT or matrixCopies.test.ts
   reddens. The role_permission ROWS are NOT deleted — a permission nothing reads is inert, and
   deleting seeded rows only makes three documents that pin the matrix disagree with the database.
2. Part B — the nine tables. company_id NOT NULL leading every composite index, FORCE ROW LEVEL
   SECURITY, and the isolation predicate BYTE-IDENTICAL to V1's (SchemaConventionTest compares them).
   The migration's comments carry the why, in V1's style.
3. Part C — the seed function and the backfill. The backfill is a LOOP with set_config, for V2's
   reason: Flyway connects as tcms_owner, which holds no BYPASSRLS, so a plain INSERT ... SELECT
   FROM project with app.current_company unset reads ZERO ROWS and reports success.
4. Part D — work_item → issue as parentless Stories (D12), carrying the ID forward so
   derived_item_annotation still resolves. DO NOT DROP THE TWO TABLES — com.tcms.work.WorkItem still
   maps work_item and ddl-auto is validate, so a database without it cannot boot the context, and T2
   cannot delete that entity because BoardService reads WorkItemRepository until T4. The drop is V4,
   in T4's commit that deletes the nine work classes. Verify by COUNTING ROWS EITHER SIDE
   on a container migrated to V2 — 009 requires this explicitly, "not by reading the SQL", because a
   project whose rows silently did not move produces a shorter result set and no error.
5. Gates on shanks via ~/bin/tcms-remote (sync, then backend ./gradlew build). Read
   docs/specs/shanks-runner-details.md first: BUILD FAILED with "Could not write XML test results"
   and ZERO assertion failures is an fd limit — `ulimit -n 65535; ./gradlew build`. And
   com.tcms.reporting.FlakeScoreTest fails six tests as PRE-EXISTING, proven so on 2026-08-30; do not
   attribute them to this module.
6. One commit per part, `git add <paths>` only.
````

---

### T3 — `be` · The `tracker` module

````text
You are working in the tcms repo. Read CLAUDE.md first; the plugin skills it names are mandatory —
/be-java plus be-java-database-code and be-java-unit-testing.

Execute T3 of docs/plans/011-tracker-core.md — two parts. This is the largest task in the module and
it is budgeted AT the 150k cap. If you run out, the seam is between Part A and Part B: commit Part A
and stop, saying so. Do not hand half of it to another agent.

Read: the plan's "Decisions" section, its "File structure" table (which names every file you create),
and T3's two parts. Then read, because you are building against them:
backend/src/main/java/com/tcms/project/ProjectKeys.java IN FULL, com/tcms/work/package-info.java,
and one existing controller+service pair as the pattern (project/ProjectCreateService.java is the
closest in shape — its gate ordering and its audit call are both copied here).

Four things this codebase will punish you for getting wrong, all in CLAUDE.md:

- EVERY repository interface carries @Transactional(readOnly = true). Spring Data gives a transaction
  only to the INHERITED CRUD methods, so a derived or @Query method otherwise runs with no company set
  and every RLS-protected read returns ZERO ROWS — fail-closed and silent.
- Company context comes from the authenticated session, NEVER from a parameter, header or path segment.
- tracker may depend only on the five foundation modules. ArchUnit enforces it. project is one of
  them, which is what makes importing ProjectKeys legal.
- ProjectKeys.allocate carries an entry in ArchitectureRulesTest.UNEXERCISED_BY_DECISION saying it has
  no production caller until M1. DELETE THAT ENTRY in this task. The rule fails in BOTH directions —
  an entry that stopped describing an unexercised method is as red as an unexercised method with no
  entry — and the entry itself says M1 deletes it.

Then:
1. Part A — the module: package-info with its allowedDependencies, the four entities, seven
   repositories, IssueEdit (pure — write its eight tests FIRST, they need no container), IssueService,
   the three controllers. The depth rule is checked against issue_type.level and NEVER against a type
   name: a fourth story-level type must stay a row rather than becoming a code change.
   A new issue enters the project's first status BY POSITION, never by the word "Backlog" — a project
   that renamed it still has a first column.
2. Part B — Completion (D8) and EpicStatus (D31), both pure, both tested first; then issue_fact and
   its outbox handler. issue_fact has EXACTLY ONE WRITER and the test greps the whole main source tree
   for a second, because the failure scope §3 describes is silent: nothing breaks and a report is just
   wrong. Mint one OutboxEvents name — read that file's header first, it explains why a past-tense name
   is an occurrence and a "changed" name is a coalesced state, and getting it backwards loses events.
3. Gate on shanks: ~/bin/tcms-remote sync, then backend ./gradlew build. FlakeScoreTest's six failures
   are pre-existing; do not claim them. Read docs/specs/shanks-runner-details.md first.
4. One commit per part, `git add <paths>` only.
````

---

### T4 — `be` · Spec links, comments, and the retirement of `work`

````text
You are working in the tcms repo. Read CLAUDE.md first; /be-java is mandatory.

Execute T4 of docs/plans/011-tracker-core.md — two parts. Read the plan's "Decisions" (D-e is this
task's whole shape) and T4's two parts.

READ backend/src/main/java/com/tcms/work/package-info.java IN FULL BEFORE TOUCHING ANYTHING. It is 97
lines and it is this module's design document — in particular why exactly one class may name a foreign
table, and why a derived card is never materialised. You are about to change what it describes, and
you rewrite the superseded paragraph in the struck-through style rather than deleting it.

Also read: repository/HealthRecomputeOnLinksChanged.java (the template for your handler),
V1's case_doc_link comments (your projection mirrors it field for field), documentation/CatalogueFacts.java,
and work/BoardFacts.java + BoardService.java, which you are rewriting.

D-e, because it is the part people get backwards: tracker NEVER WRITES doc_link. documentation owns
that table and its write endpoint. What you do is widen LinkSourceType with ISSUE, give CatalogueFacts
an arm that resolves an issue by key, and keep issue_doc_link in tracker as a projection rebuilt whole
per company on the EXISTING documentation.links_changed event. No new outbox name. Rebuilding rather
than patching is the only way to notice a link that was REMOVED.

Then:
1. Part A — LinkSourceType.ISSUE (and rewrite the enum's paragraph that PREDICTS this member arriving,
   rather than leaving it predicting something that has happened), the CatalogueFacts arm, the
   projection and its handler, comments and watchers. Commenting adds the author as a watcher: without
   it people comment, the thread continues, and they never hear about it. Assert the negative on
   Viewer — issue:comment yes, issue:edit no.
2. Part B — BoardFacts gains assignedIssues (its FIFTH crossing and the first into tracker; work still
   may NOT depend on tracker, so the edge is one method in the one file allowed to name a foreign
   table). BoardService's ISSUE generator replaces the TASK one, every card's status becomes
   CardStatus(name, kind), and an ISSUE card's allowedTransitions come from the project's workflow —
   NOT from "every status but the current one". WorkItem's comment claiming an unconstrained status is
   "the model rather than an omission" is now false; delete it rather than leaving it describing what
   changed. Then delete the nine classes, move Priority to com.tcms.api (grep for importers first), and
   add the annotation endpoint — derived_item_annotation survives and D11 says it matters more, not
   less. THEN write V4__retire_work_item.sql — two DROP TABLEs, nothing else — and move
   SchemaConventionTest's policy count 84 -> 82. V3 deliberately did NOT drop those tables: the
   entity still maps work_item and ddl-auto is validate, so the drop can only land in the commit
   that deletes the entity. `ls backend/src/main/resources/db/migration/` first; out-of-order is
   unset, so a lower number arriving after a higher one is silently not applied.
3. Gate on shanks: sync, backend ./gradlew build. FlakeScoreTest's six are pre-existing, and so are
   the six contract failures (ContractConventionTest x4, contract enums, contract bindings) that T1
   left for T3 and this task to repair — measured on 2026-08-30 with T2 complete.
4. One commit per part, `git add <paths>` only. Note in the commit that moving a derived card now
   answers 404 from /issues/{key} rather than the old 409 that named the action that does work — that
   is a REGRESSION, it is recorded in T7's built-and-unverified list, and M2's board restores it.
````

---

### T5 — `ts` · Issue verbs on the CLI and MCP

````text
You are working in the tcms repo. Read CLAUDE.md first; the tooling-engineer agent with
tool-cli-builder, tool-mcp-builder and tool-capability-contract.

Execute T5 of docs/plans/011-tracker-core.md. Read the plan's decision D-d and T5 itself.

CORRECT THE COUNT BEFORE YOU START. Plan 009 says "the six work-item verbs in packages/cli and
packages/mcp". That counts nothing in the tree. What actually exists is four sites:
  - packages/mcp/src/writes.ts:1255 — tcms_write_work_item, ONE tool, two arms (create, move)
  - packages/mcp/src/reads.ts:607 — a GET /work-items read inside the orient tool, filtering QUARANTINE
  - packages/client/src/core/shapes.ts:817 — WORK_ITEM_SHAPE, registered at :908
  - packages/cli/src/reads.ts:580 — a COMMENT naming work-item as a noun the CLI does not deliver
The CLI has no work-item verbs at all. It gains issue verbs it never had, so rewrite that comment to
name the two nouns that are still undelivered (report, triage) rather than leaving it claiming a gap
that has closed.

The design point that matters: typeId and statusId are UUIDs an agent cannot guess, and handed a bare
UUID field an agent will invent one. shapes.ts:805 already solved this for execution statuses — its
note says a status guessed from another project is refused and tcms_orient serves the project's own
list. Do the same: the issue tool takes a type NAME and a status NAME, resolves them against
GET /projects/{projectId}/workflow, and refuses an unknown one by listing what the project has.

Then:
1. tcms_write_work_item → tcms_write_issue, two arms → four (create, update, move, comment), gate
   from workitem:manage → the issue:* keys. Add tcms_read_issue returning allowedTransitions AS NAMES —
   an assistant that can see which moves are legal does not attempt one that is not.
2. reads.ts:607's GET /work-items → GET /board. Its comment explaining why quarantine comes off the
   board is still true; keep it.
3. WORK_ITEM_SHAPE → ISSUE_SHAPE, registered under `issue`.
4. CLI: tcms issue list / show / create / move / comment.
5. Update docs/specs/2026-08-08-mcp-tool-and-command-surface.md IN THE SAME COMMIT — every verb with
   its typed input and the shared output envelope. Say what actually shipped.
6. Gate on shanks: sync, then `packages npm run build` — at the ROOT, never --workspaces, which runs
   them alphabetically so cli would typecheck against a stale client dist. Then lint --workspaces at 0
   warnings and test:run --workspaces. citedFiles.test.ts is in that job and fails if any path named in
   a comment ANYWHERE is not on disk — T4 deleted nine files, so a stale citation in frontend/ or
   backend/ reddens THIS job. Fix it here.
````

---

### T6 — `fe` · The tracker screens

````text
You are working in the tcms repo. Read CLAUDE.md first; /fe-react is mandatory for every screen.

Execute T6 of docs/plans/011-tracker-core.md — two parts, budgeted AT the 150k cap. If you run out,
the seam is between Part A and Part B: commit Part A and stop, saying so.

Read: the plan's decision D-f (which lists, per screen, every element that belongs to a LATER module
and must be ABSENT rather than empty), then T6's two parts. Then open docs/mockups/index.html and walk
work-table.html, issue-new.html, epic.html, story.html, task.html and documents.html. The mockups are
the source of truth for what a screen SHOWS; the plan and the scope spec for what the system DOES.

Name collision, already decided: frontend/src/api/queries/tracker.ts EXISTS and is the EXTERNAL
Jira/ADO integration. The new module's queries go in issues.ts. Do not rename the existing file.

Rules from CLAUDE.md that this task will test:
- ONE keys factory, src/api/queries/keys.ts. Never add a second — two modules deriving one key shape
  drift silently toward an invalidation that matches nothing.
- New enums into lib/labels.ts as `satisfies Record<Union, string>`, so an unlabelled member is a
  COMPILE ERROR. Delete workItemColumns with the type it labels.
- Build the fault switch BEFORE the screen it proves. A refused transition (409 naming the legal moves)
  cannot be produced by using the app, because the picker only ever offers legal moves. Both DEV guards
  stay a literal import.meta.env.DEV — routing that check through an imported constant SHIPS the module.
- Every screen through DataRegion for the loading/loaded/failed/empty/stale matrix.
- No sx, no CSS-in-JS, no second stylesheet. Tailwind utilities and the kit in src/components/kit/.
- In tests: never build your own QueryClient — only createTestQueryClient's has the caches. Use the
  shared fixtures.ts so every exchange is checked against openapi.yaml, and anchor path-keyed mock arms
  (/^\/issues\/[^/]+$/) so /issues/ACME-1/comments is not caught by the detail arm's prefix. A render
  that trips an error boundary FAILS unless it calls renderingIntoAnErrorBoundary() and says why.

Then:
1. Part A — keys, labels, devFaults, queries/issues.ts, the work table and the new-issue form. Quick
   add is ONE FIELD (story H3); the full form sits below it. Columns M1 has no data for are ABSENT from
   the column picker, not greyed — an empty Release column reads as a release that is not set, not as a
   feature that has not shipped. The four view tabs M1 does not build render a panel naming the module
   they arrive in, because D34 makes Work one nav entry and a tab that vanishes between releases is
   worse than one that says when it comes.
2. Part B — one route /issues/:key dispatching on type.level to three panel sets, plus the documents
   "Linked from" panel. A forbidden move renders DISABLED WITH ITS REASON, not absent — task.html's
   caption is the requirement. An epic has NO transition menu at all and a caption saying its status is
   derived from its stories (D31): a disabled menu would read as a missing permission.
   The completion meter COMPUTES NOTHING — percent, basis, done, total and excludedSpikes all arrive
   from the server (D8, "one number, one place"), and the basis is rendered beside the number ALWAYS.
3. Gate on shanks: sync, then `frontend npm run build` (tsc -b + vite build, NOT tsc --noEmit, which has
   reported 0 errors while the build failed with nine), `frontend npm run lint` at 0 warnings, and
   `frontend npm run test:run`.
4. One commit per part, `git add <paths>` only.
````

---

### T7 — `fe` · Settings, My Work, the overview, and the close-out

````text
You are working in the tcms repo. Read CLAUDE.md first; /fe-react is mandatory for every screen.

Execute T7 of docs/plans/011-tracker-core.md — two parts, budgeted AT the 150k cap. Part A is the
task that closes the module's "a person can now …". If you run out, the seam is after the two settings
screens.

Read: the plan's decision D-f, then T7's two parts, then the plan's "Decisions" section in full —
Part B has to write those decisions back into plan 009 and you cannot summarise what you have not read.
Then walk docs/mockups/settings-workflow.html, settings-general.html, my-work.html,
project-overview.html and solo-overview.html.

Three things in this task are easy to get subtly wrong:

- my-work.html IS NOT A KANBAN and the current MyWorkPage is. The mockup renders two TABLES —
  "Assigned issues" and "Test executions & reviews" — plus a private "My notes" panel. The mockup wins
  on what a screen shows, so the personal board becomes two tables over ONE GET /board response, split
  on kind === 'ISSUE'. ONE REQUEST, not two: two would let the halves disagree about who the caller is.
- solo-overview.html IS NOT A SEPARATE SCREEN. It is project-overview.html for a company with no
  clients — no client chip, no client column, no "attach a client" prompt. It is an ACCEPTANCE
  CRITERION, and an empty "Client: —" chip is exactly the nagging the mockup rules out and exactly what
  a naive port produces.
- A section M1 has no data for EXPLAINS ITSELF; it does not render empty. The release panel renders
  solo-overview.html's "No releases yet" empty state — a glyph, what a release IS, and the action that
  creates one — written against the endpoint from the start so M3 turns it on with DATA rather than by
  replacing the component. Budget burn and Team are NOT RENDERED AT ALL: a release is a thing this
  project will have and has none yet; a budget is a module that is not installed, and an empty-state
  card for it is an advertisement.

Note also: plan 009 says the overview lands WITHOUT its test-health panel. That is wrong in the cheap
direction — DashboardPage already renders it against endpoints that exist, and D27 says the overview
reuses those regions. It lands here, and Part B records the correction.

Then:
1. Part A — the workflow settings screen (with ↑/↓ beside every row, not drag alone: WCAG 2.2 Dragging
   Movements, and the mockups' standing rule that dragging is never the only way), the general settings
   screen (SIX issue types, not the mockup's five — the mockup's own "Add sub-task" affordance needs a
   TASK-level type and D29 puts Task at STORY; the plan records this disagreement), My Work, and the
   project overview. Gate on shanks: frontend npm run build && lint && test:run.
2. Part B — close the module. FIRST run the WHOLE gate on shanks: backend ./gradlew build, frontend
   build + lint + test:run, packages build + lint --workspaces + test:run --workspaces.
   FlakeScoreTest's six failures are PRE-EXISTING and proven so; do not claim or fix them.
   Then:
   a. Read frontend/src/features/help/sections.tsx. Its prose is swept by NO TEST — tierClaims.test.ts
      catches only a claim that somebody LACKS a permission, and M0 shipped three false sentences in
      that file past every gate. Correct anything describing the board as holding tasks.
   b. Rewrite docs/features.md's work section, struck through and superseded, never deleted.
   c. 009's hand-off protocol, all six steps: status table → `done YYYY-MM-DD @ <sha>`; the
      built-and-unverified list (including the lost 409 message from T4 Part B, and issue_link and
      issue_fact.release_assigned_at, which have no writer until M3); the V actually taken; and REWRITE
      THE PROMPTS OF M2, M4, M5 AND M6 in place with the endpoint paths, package name
      (com.tcms.tracker), outbox event name and issue_fact columns AS THEY LANDED — every one of those
      prompts currently says /work-items. Tell them the two decisions they must honour: there is no
      ProjectCreatedEvent (D-b), and a card's status is {name, kind} (D-c).
   d. Close review items R4 and R5. R5 closes as RESOLVED DIFFERENTLY FROM THE PROPOSAL — record D-b's
      reason, not just the outcome, so the next reader does not re-propose the event.
   e. Record the three findings M1 chose not to fix: the three hardcoded 'Agent' role names, the
      five-vs-six issue types, and 009's wrong claim about the test-health panel.
   f. Commit 009 ON ITS OWN, `docs(plan): M1 done`, staging only that file.
3. Say what is BUILT AND UNVERIFIED, in the chat as well as in the plan. The gates prove it compiles,
   not that it works — a no-op theme toggle, two contradictory percentages on one screen and a
   provider-ordering bug that rendered nothing but the error page all typechecked and linted clean.
````
