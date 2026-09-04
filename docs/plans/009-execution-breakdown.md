# 009 — Execution breakdown: the project-management expansion, resequenced

The executable sequence for [009-project-management-expansion-modules.md](009-project-management-expansion-modules.md).
The plan document is the authority on *what* each module contains; this file is the authority on **order, ownership
and done-ness**. `Mc`'s nine-task detail lives in its own file —
[009-mc-clients-execution-breakdown.md](009-mc-clients-execution-breakdown.md).

Merged from two Mission Control runs planned independently on 2026-08-30 against stale worktrees:
`340eebc2-6ac8-482c-aead-ff165f20cf17` (the program) and `96e92e58-45e5-4550-954a-f59af7537661` (Mc in detail).
Both boards are abandoned.

**Implementation happens on `master`, in this checkout — not in a worktree.** Both runs targeted
`~/.mission-control/worktrees/…` on an `mc/…` branch. Neither target is used. Work continues on whatever branch is
checked out, per CLAUDE.md's *Work on the current branch. Do not create a new one.*

---

## Preconditions — verified 2026-08-30, all green

Both runs were planned against worktrees missing commits that exist here. **Every blocker they recorded is already
resolved in this checkout**, verified by reading the tree rather than by trusting either run:

| What a run claimed missing | Actually, here |
|---|---|
| `docs/mockups/` absent — *blocked 6 tasks across both runs* | **present, 51 files + `assets/`, all git-tracked.** `index.html` and every file named by a module section is there |
| `docs/specs/2026-08-30-project-management-expansion-scope.md` | present, tracked |
| `docs/specs/2026-08-30-project-management-expansion-user-stories.md` | present, tracked |
| `docs/plans/009-project-management-expansion-modules.md` | present, tracked |
| `docs/plans/010-*.md` (M0's own plan) | **`010-foundation.md` present** — so `011` is the next free plan number |
| M0 unverified | **done.** Plan 009's status table reads `done 2026-08-30 @ 24f997bb`, and its M0 section already carries the closure: the V actually taken, the built-and-unverified list, and the lab click-through |

Two corrections to the program run's own task data, applied throughout below:

- The contract-fixture file is **`frontend/src/api/contractBodies.ts`**, not `frontend/src/test/contractBodies.ts`.
  The Mc run had this right; the program run did not. Its pair is `packages/client/src/test/contractBodies.ts`.
- Plan numbers shift by one. The program run assigned `010`–`019`; `010` is taken by M0, so the sequence is
  **`011`–`020`**.

### ~~Phase 1 — Groundwork: restore the source documents, close M0~~ → superseded

The program run opened with two tasks: `t1` *Restore the expansion's source documents into the repo* (cancelled
mid-flight) and `t2` *Verify and close M0 in the status table*. **Both are dead.** The documents are present and
tracked, and M0 is closed in the plan. Recorded rather than deleted so the next reader knows the phase existed and
why it went — the run was reading a worktree, not this tree.

The one thing `t2` asked for that is still worth doing sits inside M1's first task, where it belongs: grep
`frontend/src` and `packages/` for a hardcoded role name, because M0's *no screen changes* claim depends on the
admin surface staying data-driven.

---

## Sequence

`M1 → M2 → M3 → M5 → M4 → Mc → M7 → M6 → M8 → M9`

**One lane, sequential.** Every module edits the same shared surfaces — `docs/specs/openapi.yaml`, both generated
clients, `contractBodies.ts` in both copies, `src/api/queries/keys.ts`, `lib/labels.ts`, `lib/permissions.ts`, the
router, `lib/devFaults.ts`, `docs/features.md` and the MCP surface document. Under the rule that tasks sharing files
share a lane, that is one lane. CLAUDE.md's *Running work in parallel — don't* — four attempts, four reversals, three
silent losses of committed work with a clean `git status` — points the same way.

This honours every *Depends on* in plan 009 (M8 after M6 + Mc, M9 after M3, M2 after M1) and puts the tracker spine
first, so there is something to look at early.

### Phase index, plan numbers and projected migrations

| # | Module | Plan | Migration | Tasks | A person can now … |
|---|---|---|---|---|---|
| 1 | **M1** — Tracker core | `011` | `V3` | 6 | A person runs a project's work — epics, stories, tasks, transitions, comments, spec links — and sees it on My Work; every pre-upgrade work_item survives as a Task with a key. |
| 2 | **M2** — Board & backlog | `012` | `V4` | 5 | A person works the board with swimlanes, ranks the backlog, and sets one filter that survives switching views. |
| 3 | **M3** — Releases, roadmap & readiness | `013` | `V5` | 5 | A person creates a release, targets issues at it, reads the roadmap, and gets one answer to 'can we ship 2.4?'. |
| 4 | **M5** — Agent execution | `014` | `V6` | 5 | A developer connects their own assistant, it fetches an AI-eligible task with full context, reports a PR and moves it to In Review — and is refused Done. |
| 5 | **M4** — Jira offramp | `015` | `V7` | 4 | An admin previews, maps statuses, imports a Jira project with keys intact, and keeps a report that outlives the source. |
| 6 | **Mc** — Clients | `016` | `V8` | 9 — [own file](009-mc-clients-execution-breakdown.md) | A company records a client and attaches projects to it — and with no clients, nothing client-shaped appears anywhere. |
| 7 | **M7** — Staffing | `017` | `V9` | 4 | A PM allocates people, sees capacity and over-allocation, and answers 'who is free next month with skill X'. |
| 8 | **M6** — Time | `018` | `V10` | 4 | A person logs hours against an issue, a meeting and leave, submits a period, and a PM approves or rejects it with a note. |
| 9 | **M8** — Billing | `019` | `V11` | 4 | Approved hours are priced by rate card, budget burn is visible, and a draft invoice is issued with lines that then lock. |
| 10 | **M9** — Delivery analytics | `020` | `none` | 3 | A PM reads a release burn-up that distinguishes scope added after the release was created, plus cycle time and cumulative flow. |

> ### The plan and migration columns are projections, not commitments
>
> `out-of-order` is unset in `application.yml`, so **a lower-numbered migration arriving after a higher one is
> silently not applied**, and Testcontainers builds each test database from scratch so no suite will ever show the
> conflict. The numbers above are what this order produces *if nothing is reordered, deferred or dropped*.
> **Every migration task re-reads `backend/src/main/resources/db/migration/` immediately before writing the file and
> takes the next free number**, and its closure commit corrects plan 009's table to the number actually taken. Same
> for plan numbers: take the next free `NNN` from `docs/plans/`, never renumber.
>
> Note the reordering already baked in here: plan 009's table reserves `V6` for M4 and `V7` for M5, but **M5 lands
> before M4**, so under this sequence M5 takes `V6` and M4 takes `V7`. Plan 009's table has been corrected.

---

## Tasks

Every task below is `backlog` — nothing has been started. Statuses from the two runs (`ready`, `blocked`) were
artefacts of the stale worktrees and are dropped.

## Phase 1 — M1: Tracker core

**Goal.** A person runs a project's work — epics, stories, tasks, transitions, comments, spec links — and sees it on My Work; every pre-upgrade work_item survives as a Task with a key.

**Sequential.** One lane: the contract commit must precede the backend that implements it, the migration must precede the entities that map it, and both frontend tasks share keys.ts, labels.ts and the router.

**Module plan:** `docs/plans/011-…md` (next free at start time) · **Migration:** projected `V3`

### `t3` · M1 — module plan and the tracker contract commit

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `ep-writing-plans`, `be-shared-api-surface-plan`, `ep-code-comments` | `t2` |

Write docs/plans/0NN-tracker-core.md (next free NNN) covering the work_item→issue migration, the file-by-file retirement of work/, the ProjectCreatedEvent seam, the V-backfill of the default workflow, issue_fact's four timestamps and BoardFacts' ISSUE generator. Then land the contract in one commit: /issues, /issues/{key}, /issues/{key}/comments, /issues/{key}/transitions, /issues/{key}/doc-links, /projects/{id}/workflow; remove /work-items and /work-items/{workItemId}; regenerate both clients and edit the marked contractBodies region in both copies.

**Added from the superseded groundwork phase:** before anything else, grep `frontend/src` and `packages/` for each of the eleven role names. M0's *no screen changes* claim rests on the admin surface staying data-driven; if a role name has been hardcoded behaviourally since, removing it is M1's first task.

**Acceptance criteria**

- [ ] Every new schema has its required array, including on inline nested objects
- [ ] frontend/src/api/schema.d.ts and packages/client/src/schema.ts are regenerated in the same commit as openapi.yaml
- [ ] contractPairing.test.ts is green — the marked region is byte-identical across both copies
- [ ] /work-items and /work-items/{workItemId} are gone from openapi.yaml, not deprecated
- [ ] workitem:manage is struck through '→ superseded by issue:*' in the roles spec, permission-matrix-summary.md and README.md in one commit
- [ ] A grep of `frontend/src` and `packages/` finds no role name hardcoded behaviourally, or each one found is fixed in this module

**Files**

- `docs/plans/011-tracker-core.md`
- `docs/specs/openapi.yaml`
- `frontend/src/api/schema.d.ts`
- `packages/client/src/schema.ts`
- `frontend/src/api/contractBodies.ts`
- `packages/client/src/test/contractBodies.ts`
- `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`
- `docs/specs/permission-matrix-summary.md`
- `README.md`

### `t4` · M1 — V3 tracker schema and the work_item migration

| Agent | Skills | Depends on |
|---|---|---|
| `data-architect` | `data-postgresql`, `data-migrations`, `data-schema-design` | `t3` |

Take the next free V from the migration directory (the plan reserves V3). Create issue, issue_type, issue_status, issue_transition, issue_link, issue_comment, issue_watcher, issue_doc_link and issue_fact — every table company_id NOT NULL leading every composite index, with FORCE ROW LEVEL SECURITY. issue_fact carries created_at, status_entered_at, done_at and release_assigned_at (M9's schema, landing here). Backfill the six-status default workflow for every existing project, and migrate work_item rows to parentless Stories with work_item_link → issue_link, then drop work_item and work_item_link.

**Acceptance criteria**

- [ ] SchemaConventionTest is green — every new table has company_id NOT NULL leading each composite index, and forced RLS
- [ ] A test counts work_item rows before and issue rows after and asserts equality, rather than reading the SQL
- [ ] Every project that existed before the migration has the six default statuses, and issue_status.kind is set on each
- [ ] The migration file's comments carry the why, in the style of V1
- [ ] derived_item_annotation and its rows are untouched

**Files**

- `backend/src/main/resources/db/migration/V3__tracker_core.sql`
- `backend/src/test/java/com/tcms/schema/`
- `docs/specs/2026-07-30-tcms-erd.md`

### `t5` · M1 — the tracker module, and the retirement of work/

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `be-java-database-code`, `be-java-unit-testing`, `be-java-pattern-writer` | `t4` |

New com.tcms.tracker module: issue CRUD, per-project key allocation through project's project_key_sequence allocator, the level/parent depth rule, the legal-transition set, completion per D8 with the basis on the wire, epic status derived per D31, issue_doc_link staleness, and issue_fact written only from a CompanyScopedEvent. project publishes a new ProjectCreatedEvent that tracker consumes to seed the workflow — project cannot import tracker, so the event is the only legal direction. Delete WorkItem, WorkItemController, WorkItemEdit, WorkItemRepository, WorkItemStatus, WorkItemStatusUpdateRequest, WorkItemView, WorkItemWriteRequest and Priority; keep BoardFacts, BoardService, BoardPeople and ItemAssignedEvent, and add the ISSUE generator to BoardFacts so My Work stays composite.

**Acceptance criteria**

- [ ] Every tracker repository interface carries @Transactional(readOnly = true)
- [ ] ArchUnit is green — tracker depends only on the five foundation modules, and nothing imports tracker
- [ ] BoardFacts emits ISSUE cards beside EXECUTION, CASE_MAINTENANCE and REVIEW, and the nine listed work classes are gone
- [ ] Unit tests cover the depth rule, the legal-transition set, and completion by count and by hours with the basis reported
- [ ] The context boots against the real schema — hibernate ddl-auto validate finds no entity drift

**Files**

- `backend/src/main/java/com/tcms/tracker/`
- `backend/src/main/java/com/tcms/work/`
- `backend/src/main/java/com/tcms/project/`
- `backend/src/test/java/com/tcms/tracker/`
- `backend/src/test/java/com/tcms/architecture/`

### `t6` · M1 — issue verbs on the CLI and MCP

| Agent | Skills | Depends on |
|---|---|---|
| `tooling-engineer` | `tool-cli-builder`, `tool-mcp-builder`, `tool-capability-contract` | `t5` |

Replace the six work-item verbs in packages/cli and packages/mcp with issue verbs against the new contract, and update docs/specs/2026-08-08-mcp-tool-and-command-surface.md in the same commit.

**Acceptance criteria**

- [ ] No work-item verb remains in packages/cli or packages/mcp
- [ ] The MCP surface document lists every issue verb with its typed input and the shared output envelope
- [ ] packages builds in the named order client → cli → mcp, lint at 0 warnings, tests green
- [ ] citedFiles.test.ts is green

**Files**

- `packages/cli/src/`
- `packages/mcp/src/`
- `packages/client/src/`
- `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

### `t7` · M1 — issue screens: work table, epic, story, task, new issue, documents

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-data-fetching`, `fe-react-forms`, `fe-react-ui-rules`, `fe-react-component-testing` | `t5` |

Port work-table.html, epic.html, story.html, task.html, issue-new.html and documents.html onto the render kit and TanStack Query. One keys factory entry set in src/api/queries/keys.ts, new enums into lib/labels.ts as satisfies Record<Union, string>, every screen through DataRegion for the loading/loaded/failed/empty/stale matrix, and the devFaults switch for the new failure modes built before the screen it proves.

**Acceptance criteria**

- [ ] No sx, no CSS-in-JS, no second stylesheet — component styles live in the class list
- [ ] New enums are in lib/labels.ts with satisfies Record<Union, string>, so an unlabeled member is a compile error
- [ ] Only one keys factory exists; every read has its enabled gate and its endpoint-class timeout budget
- [ ] Vitest + RTL cover each screen's failed and empty states through DataRegion, using createTestQueryClient and the shared fixtures
- [ ] npm run build (tsc -b + vite build) and npm run lint at 0 warnings

**Files**

- `frontend/src/features/tracker/`
- `frontend/src/api/queries/issues.ts`
- `frontend/src/api/queries/keys.ts`
- `frontend/src/lib/labels.ts`
- `frontend/src/lib/devFaults.ts`

### `t8` · M1 — My Work, project overview and the workflow/general settings screens

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-data-fetching`, `fe-react-ui-rules`, `fe-react-component-testing`, `fe-react-accessibility` | `t7` |

Extend the existing My Work screen to my-work.html — issues beside executions and reviews, with the private note and personal due date from derived_item_annotation. Port settings-workflow.html and settings-general.html, and project-overview.html without its release and test-health panels: the page is built so a missing section explains itself rather than rendering empty, and solo-overview.html is the same page for a company with no clients.

**Acceptance criteria**

- [ ] My Work shows a person's issues across two projects beside their pending reviews and executions
- [ ] The private note and personal due date work on a card the viewer does not own
- [ ] project-overview.html renders with the release and test-health sections explaining that releases do not exist yet, not as empty boxes
- [ ] The workflow settings screen lists the six seeded statuses with their kinds and allows renaming
- [ ] Vitest + RTL cover the composite board's mixed card kinds; build and lint green

**Files**

- `frontend/src/features/board/MyWorkPage.tsx`
- `frontend/src/features/board/`
- `frontend/src/features/projects/`
- `frontend/src/features/settings/`
- `frontend/src/api/queries/keys.ts`
- `frontend/src/lib/labels.ts`

---

## Phase 2 — M2: Board & backlog

**Goal.** A person works the board with swimlanes, ranks the backlog, and sets one filter that survives switching views.

**Sequential.** One lane: contract → migration → tracker.board service → screens, and the two frontend tasks share the Work tab shell, keys.ts and the filter hook.

**Module plan:** `docs/plans/012-…md` (next free at start time) · **Migration:** projected `V4`

### `t9` · M2 — module plan and the board contract commit

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `ep-writing-plans`, `be-shared-api-surface-plan` | `t8` |

Write docs/plans/0NN-board-and-backlog.md, naming this as the hardest frontend in the expansion. Land the contract in one commit: /projects/{id}/boards, /boards/{id}, /boards/{id}/cards/{key}/move, /projects/{id}/backlog, /issues/{key}/rank, plus label endpoints; regenerate both clients and both contractBodies copies, and add the board verbs to the CLI/MCP surface document.

**Acceptance criteria**

- [ ] Every schema carries its required array, inline objects included
- [ ] The move endpoint's response reports a WIP breach rather than refusing the move
- [ ] Both generated clients and both contractBodies copies move in the same commit; contractPairing.test.ts green

**Files**

- `docs/plans/012-board-and-backlog.md`
- `docs/specs/openapi.yaml`
- `frontend/src/api/schema.d.ts`
- `packages/client/src/schema.ts`
- `frontend/src/api/contractBodies.ts`
- `packages/client/src/test/contractBodies.ts`
- `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

### `t10` · M2 — V4 board, labels and fractional rank

| Agent | Skills | Depends on |
|---|---|---|
| `data-architect` | `data-postgresql`, `data-migrations` | `t9` |

Next free V (reserved V4): board, board_column, board_column_status with a unique index on (board_id, status_id), board_swimlane, issue_label, issue_label_assignment, and issue.rank as a fractional rank. A cross-table CHECK is not available in Postgres, so exactly-one-column-per-status is the unique index plus a service rule.

**Acceptance criteria**

- [ ] SchemaConventionTest green — company_id NOT NULL leading every composite index, forced RLS on every new table
- [ ] The unique index on board_column_status (board_id, status_id) exists and a test proves a second claim is rejected
- [ ] issue.rank is a type that supports insertion between two neighbours without rewriting the column

**Files**

- `backend/src/main/resources/db/migration/V4__tracker_board.sql`
- `backend/src/test/java/com/tcms/schema/`
- `docs/specs/2026-07-30-tcms-erd.md`

### `t11` · M2 — tracker.board: column claims, WIP limits, rank rebalancing

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `be-java-database-code`, `be-java-unit-testing` | `t10` |

A tracker.board sub-package, not a module. Column→status claims with a per-column default for the coarse board; WIP limits that report a breach rather than refusing a drop; rank rebalancing on a fractional rank; filter state read from query parameters. A status created after a board exists is auto-claimed by the column holding the default status of the same kind, so an unclaimed status is not a state the data can be in.

**Acceptance criteria**

- [ ] Adding an IN_PROGRESS-kind status to a project's workflow claims it in every board's In Progress column, proven by a test
- [ ] A move that breaches a WIP limit succeeds and reports the breach
- [ ] Rank rebalancing is covered by a unit test that inserts repeatedly between the same two neighbours
- [ ] ArchUnit green — board stays inside tracker and imports only the foundation modules

**Files**

- `backend/src/main/java/com/tcms/tracker/board/`
- `backend/src/test/java/com/tcms/tracker/board/`

### `t12` · M2 — the board: swimlanes, optimistic reorder, keyboard parity

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-accessibility`, `fe-react-data-fetching`, `fe-react-component-testing`, `fe-react-animation` | `t11` |

Port work-board.html. Extend frontend/src/features/board/cardMove.ts and boardKeyboard.ts rather than forking them: optimistic reordering on the fractional rank, swimlanes by epic, WIP breach shown not enforced, and every drag with a ⋮ Move to… equivalent per WCAG 2.2 Dragging Movements. The devFaults switch for a failed move — the card snaps back and says why — lands before the board.

**Acceptance criteria**

- [ ] Every card exposes a ⋮ Move to… path and every rankable row an ↑ ↓ pair; the whole board is workable from the keyboard alone
- [ ] A failed move rolls the optimistic reorder back and surfaces the reason through describeError
- [ ] A card moved on the team board appears in the matching column of the stakeholder board
- [ ] cardMove.ts and boardKeyboard.ts are extended, not duplicated, and their existing tests still pass
- [ ] build and lint green; RTL covers the breach, the failure and the keyboard path

**Files**

- `frontend/src/features/board/cardMove.ts`
- `frontend/src/features/board/boardKeyboard.ts`
- `frontend/src/features/board/BoardPage.tsx`
- `frontend/src/features/board/TeamBoardPage.tsx`
- `frontend/src/api/queries/boards.ts`
- `frontend/src/lib/devFaults.ts`

### `t13` · M2 — backlog, board settings, and one filter across every view

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-routing`, `fe-react-ui-rules`, `fe-react-component-testing` | `t12` |

Port work-backlog.html and settings-boards.html, and take the Work entry to the tabbed shape of D34 with the filter held in the URL via useSearchParams so it survives switching between Board, Table and Backlog.

**Acceptance criteria**

- [ ] A filter set on Board is still applied after switching to Table, and is shareable as a URL
- [ ] Backlog rows rank from the keyboard as well as by pointer
- [ ] The board settings screen shows a newly added workflow status in its auto-claimed column and allows moving it
- [ ] No filter state is duplicated into a client store; build, lint and tests green

**Files**

- `frontend/src/features/board/`
- `frontend/src/features/tracker/`
- `frontend/src/features/settings/`
- `frontend/src/api/queries/keys.ts`

---

## Phase 3 — M3: Releases, roadmap & readiness

**Goal.** A person creates a release, targets issues at it, reads the roadmap, and gets one answer to 'can we ship 2.4?'.

**Sequential.** One lane: the ReleaseSortKey move to api touches execution's readers, which the release service then imports; both frontend tasks share the project-overview page and keys.ts.

**Module plan:** `docs/plans/013-…md` (next free at start time) · **Migration:** projected `V5`

### `t14` · M3 — module plan and the release contract commit

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `ep-writing-plans`, `be-shared-api-surface-plan` | `t13` |

Write docs/plans/0NN-releases.md. Land the contract: /projects/{id}/releases, /releases/{id}, /releases/{id}/readiness, /projects/{id}/roadmap, /issues/{key}/test-cases; both clients, both contractBodies copies, CLI/MCP verbs and the surface document.

**Acceptance criteria**

- [ ] Readiness responses distinguish 'not ready, and here is the story' from 'no run has happened'
- [ ] Every schema has its required array; contractPairing.test.ts green
- [ ] The plan records that there is no backfill (D14) and that the roadmap is empty on merge day

**Files**

- `docs/plans/013-releases.md`
- `docs/specs/openapi.yaml`
- `frontend/src/api/schema.d.ts`
- `packages/client/src/schema.ts`
- `frontend/src/api/contractBodies.ts`
- `packages/client/src/test/contractBodies.ts`
- `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

### `t15` · M3 — V5 release, issue.release_id and test_run.release_id

| Agent | Skills | Depends on |
|---|---|---|
| `data-architect` | `data-postgresql`, `data-migrations` | `t14` |

Next free V (reserved V5): release, project-scoped per D13 and carrying created_at as the burn-up's baseline; issue.release_id nullable FK with no join table per D6; test_run.release_id UUID NULL REFERENCES release(id) ON DELETE SET NULL beside the existing release_version text. The FK from an execution table into a tracker table is allowed — ArchUnit governs Java imports, not DDL. Nothing is migrated into release.

**Acceptance criteria**

- [ ] release carries created_at and is project-scoped; SchemaConventionTest green
- [ ] test_run.release_version and test_plan.milestone_name are untouched and still work
- [ ] No backfill statement exists in the migration

**Files**

- `backend/src/main/resources/db/migration/V5__release.sql`
- `backend/src/test/java/com/tcms/schema/`
- `docs/specs/2026-07-30-tcms-erd.md`

### `t16` · M3 — release service, readiness, and the ReleaseSortKey move to api

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `be-java-database-code`, `be-java-unit-testing`, `be-java-code-review` | `t15` |

Move ReleaseSortKey from execution to api beside AutomationKeys and PatchBody, and follow the import in TestRun and TestExecution — tracker → execution is a forbidden crossing. Release CRUD ordered by that key so 1.10 follows 1.9; story↔test-case links as issue_link with target type TEST_CASE; readiness joining execution_fact on the latest result per case; burn-up and completion report types in reporting. release_sort_key comes off READERLESS_BY_DECISION in the same commit that first reads it.

**Acceptance criteria**

- [ ] ArchUnit and ArchitectureRulesTest green, including the READERLESS_BY_DECISION change
- [ ] A release with a done story whose linked case failed reads not ready and names that story
- [ ] A release with no run reports that, rather than zero failures
- [ ] 1.10 sorts after 1.9, covered by a unit test

**Files**

- `backend/src/main/java/com/tcms/tracker/release/`
- `backend/src/main/java/com/tcms/api/ReleaseSortKey.java`
- `backend/src/main/java/com/tcms/execution/`
- `backend/src/main/java/com/tcms/reporting/`
- `backend/src/test/java/com/tcms/tracker/`
- `backend/src/test/java/com/tcms/architecture/`

### `t17` · M3 — releases, roadmap, timeline and calendar screens

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-data-fetching`, `fe-react-ui-rules`, `fe-react-component-testing` | `t16` |

Port releases.html, release-new.html, release.html, work-timeline.html and work-calendar.html. Because there is no backfill, the empty roadmap on merge day is an acceptance criterion: it explains itself and carries the action that resolves it.

**Acceptance criteria**

- [ ] The empty roadmap explains why it is empty and offers 'create a release', rather than reading as a bug
- [ ] The release page shows readiness with the blocking stories named
- [ ] Timeline and calendar are reachable and usable from the keyboard
- [ ] build, lint and tests green

**Files**

- `frontend/src/features/releases/`
- `frontend/src/api/queries/releases.ts`
- `frontend/src/api/queries/keys.ts`
- `frontend/src/lib/labels.ts`

### `t18` · M3 — the overview panels M1 left out, and the test-case verification column

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-ui-rules`, `fe-react-data-fetching`, `fe-react-component-testing` | `t17` |

Add the release and test-health panels to project-overview.html that M1 deliberately omitted, and give test-cases.html its 'verifies which stories' column fed by issue_link with target type TEST_CASE.

**Acceptance criteria**

- [ ] The overview's release and test-health panels replace the explanatory placeholders M1 shipped
- [ ] solo-overview.html still renders correctly for a company with no clients
- [ ] The test-cases column links to the stories a case verifies and is empty, not broken, when there are none
- [ ] build, lint and tests green

**Files**

- `frontend/src/features/projects/`
- `frontend/src/features/cases/`
- `frontend/src/api/queries/keys.ts`

---

## Phase 4 — M5: Agent execution

**Goal.** A developer connects their own assistant, it fetches an AI-eligible task with full context, reports a PR and moves it to In Review — and is refused Done.

**Sequential.** One lane: the transition projection lands in the contract, is implemented in tracker, then consumed by both the tool surface and the screens.

**Module plan:** `docs/plans/014-…md` (next free at start time) · **Migration:** projected `V6`

### `t19` · M5 — module plan and the agent-execution contract commit

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `ep-writing-plans`, `be-shared-api-surface-plan` | `t18` |

Write docs/plans/0NN-agent-execution.md. Land the contract: /me/assistants, /projects/{id}/repositories, and the allowed-moves projection on /issues/{key}/transitions so a caller learns what it may do. The release field of the fetch payload is nullable from the first commit — M5 does not wait for M3, and it is populated once a task's story is targeted at a release.

**Acceptance criteria**

- [ ] The fetch payload carries story, acceptance criteria, epic goal, release (nullable), spec excerpts and build command, each with its required array
- [ ] The transitions response enumerates allowed moves rather than the caller inferring them
- [ ] Both clients and both contractBodies copies move in the same commit

**Files**

- `docs/plans/014-agent-execution.md`
- `docs/specs/openapi.yaml`
- `frontend/src/api/schema.d.ts`
- `packages/client/src/schema.ts`
- `frontend/src/api/contractBodies.ts`
- `packages/client/src/test/contractBodies.ts`

### `t20` · M5 — V7 project_repository, pr_url, agent_eligible, review_enabled_issue

| Agent | Skills | Depends on |
|---|---|---|
| `data-architect` | `data-postgresql`, `data-migrations` | `t19` |

Next free V (reserved V7): project_repository as reference data per D16, issue.pr_url, issue.agent_eligible per D30, and review_enabled_issue defaulting to false per D33.

**Acceptance criteria**

- [ ] SchemaConventionTest green on the new table
- [ ] review_enabled_issue defaults to false, so a one-person company can still close its own work
- [ ] agent_eligible defaults to false on every existing issue

**Files**

- `backend/src/main/resources/db/migration/V7__agent.sql`
- `backend/src/test/java/com/tcms/schema/`
- `docs/specs/2026-07-30-tcms-erd.md`

### `t21` · M5 — assistant tokens, scoped fetch, and the refusal to close

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `be-java-database-code`, `be-java-unit-testing`, `be-java-code-review` | `t20` |

A profile API token is the identity — no agent principal, no orchestrator, no runner, no forge adapter, no model credential (D25, D26). Actions attribute to '<person>, via <assistant>'. An assistant may move a task to In Review and must be refused Done, with the reason recorded on the issue's history.

**Acceptance criteria**

- [ ] A scoped assistant requesting another project's tasks receives nothing from it — asserted as an absence
- [ ] A close attempt is refused with a reason, and the refusal appears on the issue's history
- [ ] Attribution reads '<person>, via <assistant>' on every action the assistant takes
- [ ] A one-person company with review_enabled_issue false can still close its own work

**Files**

- `backend/src/main/java/com/tcms/tracker/agent/`
- `backend/src/main/java/com/tcms/identity/`
- `backend/src/test/java/com/tcms/tracker/`

### `t22` · M5 — the issue tool surface on MCP and CLI

| Agent | Skills | Depends on |
|---|---|---|
| `tooling-engineer` | `tool-mcp-builder`, `tool-cli-builder`, `tool-capability-contract` | `t21` |

Here the tool surface is the feature, not the tax. Build the issue verbs an assistant drives — fetch a task with its context, report a PR, request a transition — with typed inputs, the shared output envelope and actionable errors, and update docs/specs/2026-08-08-mcp-tool-and-command-surface.md.

**Acceptance criteria**

- [ ] Each tool has a JSON schema for its input and returns the shared envelope
- [ ] A refused transition returns an actionable error naming the allowed moves
- [ ] The surface document lists every new verb; citedFiles.test.ts green
- [ ] packages build in order, lint at 0 warnings, tests green

**Files**

- `packages/mcp/src/`
- `packages/cli/src/`
- `packages/client/src/`
- `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

### `t23` · M5 — assistant profile, repositories, and the AI-eligible marker

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-forms`, `fe-react-ui-rules`, `fe-react-component-testing` | `t22` |

Port profile-assistants.html, assistant-connect.html, story-proposal.html and settings-repositories.html, add the AI-eligible toggle and its marker to the task page, work table and board card, and add the 'Connect an AI assistant' entry to help.html.

**Acceptance criteria**

- [ ] A token is shown once on creation and never again, and revoking one is reachable from the profile
- [ ] The AI-eligible marker appears on the task page, the work table row and the board card from one shared component
- [ ] build, lint and tests green; RTL covers the token-shown-once path

**Files**

- `frontend/src/features/account/`
- `frontend/src/features/settings/`
- `frontend/src/features/tracker/`
- `frontend/src/features/board/`
- `frontend/src/api/queries/keys.ts`

---

## Phase 5 — M4: Jira offramp

**Goal.** An admin previews, maps statuses, imports a Jira project with keys intact, and keeps a report that outlives the source.

**Sequential.** One lane: the importer's own read client and the import_run tables precede the wizard screens that drive them.

**Module plan:** `docs/plans/015-…md` (next free at start time) · **Migration:** projected `V7`

### `t24` · M4 — module plan, the R14 credential decision, and the import contract

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `ep-writing-plans`, `be-shared-api-surface-plan`, `be-shared-integration-plan` | `t23` |

Write docs/plans/0NN-jira-offramp.md and close review R14 in it with the reason: whether the importer reuses integration.tracker's per-person credential (a forbidden module crossing) or takes the connection per run and stores it encrypted on import_run, which is what import-connect.html already shows. Land the contract: /imports/preview, /imports, /imports/{id}.

**Acceptance criteria**

- [ ] R14 is closed in the module plan with the decision and its reason, and the review table in plan 009 records it
- [ ] Every schema has its required array; both clients and both contractBodies copies move together
- [ ] The preview response and the report response share the dropped-field shape, so they cannot disagree

**Files**

- `docs/plans/015-jira-offramp.md`
- `docs/specs/openapi.yaml`
- `frontend/src/api/schema.d.ts`
- `packages/client/src/schema.ts`
- `frontend/src/api/contractBodies.ts`
- `packages/client/src/test/contractBodies.ts`
- `docs/plans/009-project-management-expansion-modules.md`

### `t25` · M4 — V6 import_run and import_dropped_field

| Agent | Skills | Depends on |
|---|---|---|
| `data-architect` | `data-postgresql`, `data-migrations` | `t24` |

Next free V (reserved V6): import_run and import_dropped_field, so the report outlives the migration and can be reopened a year later when the source system is gone.

**Acceptance criteria**

- [ ] SchemaConventionTest green; both tables company_id NOT NULL with forced RLS
- [ ] import_dropped_field stores the counted field names and counts, not a rendered string
- [ ] If R14 chose per-run credentials, the column is encrypted the way TrackerCredential is

**Files**

- `backend/src/main/resources/db/migration/V6__import.sql`
- `backend/src/test/java/com/tcms/schema/`
- `docs/specs/2026-07-30-tcms-erd.md`

### `t26` · M4 — tracker.imports: its own read client, dry-run preview, status mapping

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `be-java-database-code`, `be-java-unit-testing`, `be-java-pattern-writer` | `t25` |

A tracker.imports sub-package with its own read client for Jira Cloud/DC and Azure DevOps — paging, comments, changelog and user resolution. The outbound adapters in integration/tracker are neither reused nor changed; a client mid-migration still links defects out. Dry-run preview writes nothing (D32); the mapping step refuses to confirm while one status is unmapped; original keys are preserved (D5); standard fields only, with every dropped field counted. Sprint membership is dropped and reported. The devFaults switch for a Jira that stops answering mid-import lands before the importer.

**Acceptance criteria**

- [ ] A test diffs the project before and after a preview and asserts it is byte-identical
- [ ] Confirm is refused while any source status is unmapped
- [ ] PROJ-4812 is still PROJ-4812 after import
- [ ] The dropped-field list in the report matches the preview's word for word, proven by comparing the two responses in a test
- [ ] ArchUnit green — integration is untouched and nothing crosses into it

**Files**

- `backend/src/main/java/com/tcms/tracker/imports/`
- `backend/src/test/java/com/tcms/tracker/imports/`
- `backend/src/test/java/com/tcms/architecture/`

### `t27` · M4 — the import wizard screens

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-forms`, `fe-react-data-fetching`, `fe-react-component-testing`, `fe-react-error-handling` | `t26` |

Port import-connect.html → import-preview.html → import-mapping.html → import-report.html, plus settings-import.html, as one flow that can be abandoned and resumed at the mapping step.

**Acceptance criteria**

- [ ] The mapping step's confirm control is disabled with a reason while a status is unmapped
- [ ] A report opens from settings-import.html long after the run, with no call to the source system
- [ ] A source that stops answering mid-import surfaces through describeError, exercised by the devFaults switch
- [ ] build, lint and tests green

**Files**

- `frontend/src/features/imports/`
- `frontend/src/features/settings/`
- `frontend/src/api/queries/keys.ts`
- `frontend/src/lib/devFaults.ts`

---

## Phase 6 — Mc: Clients

**Goal.** A company records a client and attaches projects to it — and with no clients, nothing client-shaped appears anywhere.

**Sequential.** One lane: the crm module and the project.client_id FK precede the Client column, filter and field that read them.

**Module plan:** `docs/plans/016-…md` (next free at start time) · **Migration:** projected `V8`

### Superseded here — see the Mc file

The program run cut Mc into four tasks (`t28`–`t31`). The Mc run cut the same module into **nine**, and did it
against this checkout's real file paths — it caught the `clientId` collision with the OIDC identity-provider
schemas, the absent `GeneralSettingsPage`, and the picker riding `project:settings` rather than `client:read`.
**[009-mc-clients-execution-breakdown.md](009-mc-clients-execution-breakdown.md) is the authority for this
phase.** Its nine tasks, in order:

| Task | Agent | Title |
|---|---|---|
| `mc1` | `be-planner` | Write the Mc module plan document |
| `mc2` | `be-java` | Contract first: openapi.yaml, both generated clients, contractBodies x2, CLI and MCP verbs |
| `mc3` | `data-architect` | Flyway migration: client, client_contact, and the project.client_id FK |
| `mc4` | `be-java` | New crm module: client CRUD and contacts |
| `mc5` | `be-java` | Project integration: map clientId and serve the picker from project settings |
| `mc6` | `fe-react` | Fault switch, clients query module, and labels |
| `mc7` | `fe-react` | Client screens: list, detail, create, and the empty state |
| `mc8` | `fe-react` | Client column, filter and field on project surfaces — and their proven absence |
| `mc9` | `be-documenter` | Close the module docs and the hand-off protocol |

The four superseded program tasks were: *module plan and the client contract commit* (`t28`), *client,
`client_contact`, and the `project.client_id` FK* (`t29`), *the crm module* (`t30`), *client screens, and the
absence when there are none* (`t31`). Nothing in them is lost — each maps onto one or more of the nine.

**Two decisions the Mc run took are reversed by this sequence** and are recorded in its file: Mc does **not**
take `V3` (M1 does), and Mc does **not** take plan `011` (M1 does).

---

## Phase 7 — M7: Staffing

**Goal.** A PM allocates people, sees capacity and over-allocation, and answers 'who is free next month with skill X'.

**Sequential.** One lane: contract → migration → staffing service → the screens that read it.
**Executed as SIX tasks, not four** — plan 017 split the screens in two for context budget (`T4` the
two company screens, `T5` the project screen and the person page) and gave `T6` the two capacity
figures M6 left absent plus the close-out, per the task-cutting rule that documentation rides with
the last task.

**Module plan:** [`docs/plans/017-staffing.md`](017-staffing.md) · **Migration:** ~~projected `V9`~~
→ **`V10`, taken 2026-09-04** — M6 landed ahead of this phase and spent `V9`.

### `t32` · M7 — module plan and the staffing contract commit

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `ep-writing-plans`, `be-shared-api-surface-plan` | `t31` |

Write docs/plans/0NN-staffing.md. M7 depends on M0 only; ~~utilisation (allocated vs logged) stays out until M6 has landed.~~ → **M6 landed on 2026-09-04, before this phase ran, so utilisation was IN** (017 D-i). Land the contract for profiles, skills, allocations and absences with both clients and both contractBodies copies.

**Acceptance criteria**

- [x] ~~No endpoint in this contract reads a time entry~~ → **superseded 2026-09-04.** This criterion
      was written on 2026-08-30, when the confirmed order put M6 *after* M7 and review R11 had just
      carved utilisation out of this module because M6 might not exist. M6 landed first, so the
      condition it guarded is gone: `GET /staffing/capacity` and `GET /staffing/people` serve
      `approvedHours` and `utilisationPercent`, read from `time_entry` / `timesheet_period` through
      `com.tcms.staffing.TimeFacts` — native SQL, read-only, one file, pinned both ways by
      `StaffingReadsNoTimeEntryTest`
- [x] Every schema has its required array; contractPairing.test.ts green
- [x] ~~The plan records that utilisation is deferred to after M6~~ → the plan records the opposite,
      in 017 D-i and in its "What this module was told" correction 2

**Files**

- `docs/plans/017-staffing.md`
- `docs/specs/openapi.yaml`
- `frontend/src/api/schema.d.ts`
- `packages/client/src/schema.ts`
- `frontend/src/api/contractBodies.ts`
- `packages/client/src/test/contractBodies.ts`
- `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

### `t33` · M7 — person_profile, skill, person_skill, allocation, absence

| Agent | Skills | Depends on |
|---|---|---|
| `data-architect` | `data-postgresql`, `data-migrations` | `t32` |

Next free V from the directory (~~the plan reserves V9~~ → **`V10` was taken on 2026-09-04**; M6 spent `V9` by landing ahead of this phase, and Mc is still unstarted). Allocation is against a project, which is a foundation module.

**Acceptance criteria**

- [x] SchemaConventionTest green on all five tables (95 → 100)
- [x] The migration takes the next free V and plan 009's table is corrected — `V10`
- [x] allocation carries a percentage and a date range that a query can overlap-check

**Files**

- `backend/src/main/resources/db/migration/`
- `backend/src/test/java/com/tcms/schema/`
- `docs/specs/2026-07-30-tcms-erd.md`

### `t34` · M7 — the staffing module: capacity, over-allocation, absence

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `be-java-database-code`, `be-java-unit-testing` | `t33` |

New com.tcms.staffing module: capacity, allocation percentage, an over-allocation flag, and absence zeroing capacity everywhere it is shown.

**Acceptance criteria**

- [ ] Allocating one person to two projects totalling 101% raises the over-allocation flag, covered by a unit test
- [ ] An absence zeroes that week's capacity in every calculation that reports it, not just on one screen
- [ ] 'Who is free next month with skill X' is answerable from one query
- [ ] ArchUnit green

**Files**

- `backend/src/main/java/com/tcms/staffing/`
- `backend/src/test/java/com/tcms/staffing/`

### `t35` · M7 — staffing and directory screens

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-data-fetching`, `fe-react-ui-rules`, `fe-react-component-testing` | `t34` |

Port staffing.html and directory.html, and surface profiles and skills where teams.html and member.html already show a person. **Landed as FOUR screens over two tasks** (017 T4 and T5, split for context budget): `/staffing` and `/directory` under `staffing:directory`, `/projects/:projectId/staffing` under the PROJECT pair, and `/directory/:userId` carrying `member.html`'s three panels — ~~a member page under `/admin/teams/:userId`~~ does not exist in this app and M7 built none (017 D-g).

**Acceptance criteria**

- [x] The over-allocation flag is visible on the staffing screen at 101%
- [x] An absent week reads as zero capacity on every screen that shows capacity
- [x] The directory answers 'free next month with skill X' through URL-held filters
- [x] build, lint and tests green — with the two pre-existing `CaseGrid.test.tsx` failures named and
      not absorbed, and **nothing opened against a running instance**; see plan 009's M7 closure

**Files**

- `frontend/src/features/staffing/`
- ~~`frontend/src/features/admin/`~~ → **untouched.** `teams.html`'s members table has no skill
  column and no capacity figure, so the mockup this task was told to walk answers that profiles and
  skills surface on the *person*, not in the list (017 D-g)
- `frontend/src/api/queries/keys.ts`
- `frontend/src/lib/labels.ts`
- **T6 also:** `frontend/src/features/time/TimesheetPage.tsx`,
  `frontend/src/features/board/MyWorkPage.tsx` and their tests — the two figures 016 D-j left absent

---

## Phase 8 — M6: Time

**Goal.** A person logs hours against an issue, a meeting and leave, submits a period, and a PM approves or rejects it with a note.

**Sequential.** One lane: the time-owned rollup projection reads issue_fact and must exist before the estimate-vs-actual figures on the story and epic screens.

**Module plan:** `docs/plans/018-…md` (next free at start time) · **Migration:** projected `V10`

### `t36` · M6 — module plan and the time contract commit

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `ep-writing-plans`, `be-shared-api-surface-plan` | `t35` |

Write docs/plans/0NN-time.md. The rollup is a time-owned projection that READS issue_fact and never writes it — that is the trap §3 names. Land the contract for logging, submitting, approving and rejecting-with-a-note, plus the rollup figures, with both clients and both contractBodies copies.

**Acceptance criteria**

- [ ] No endpoint in this contract exposes a rate or a cost
- [ ] Every schema has its required array; contractPairing.test.ts green
- [ ] The rollup is presented as its own resource, not as a field of an issue write

**Files**

- `docs/plans/018-time.md`
- `docs/specs/openapi.yaml`
- `frontend/src/api/schema.d.ts`
- `packages/client/src/schema.ts`
- `frontend/src/api/contractBodies.ts`
- `packages/client/src/test/contractBodies.ts`
- `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

### `t37` · M6 — time_entry and timesheet_period

| Agent | Skills | Depends on |
|---|---|---|
| `data-architect` | `data-postgresql`, `data-migrations` | `t36` |

Next free V from the directory (reserved V8, and it will not be that by now). time_entry carries subject_type + subject_id so it can hang off an issue, a meeting or leave; timesheet_period carries the submit/approve state.

**Acceptance criteria**

- [ ] SchemaConventionTest green; both tables company_id NOT NULL with forced RLS
- [ ] The polymorphic subject has no FK, and the reason is in the migration's comments
- [ ] The migration takes the next free V and plan 009's table is corrected

**Files**

- `backend/src/main/resources/db/migration/`
- `backend/src/test/java/com/tcms/schema/`
- `docs/specs/2026-07-30-tcms-erd.md`

### `t38` · M6 — the time module, the rollup projection, and the money-boundary negative test

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `be-java-database-code`, `be-java-unit-testing`, `qa-permission-testing-java` | `t37` |

New com.tcms.time module: log, submit, approve, reject-with-note. Approved hours roll up to story, epic and project as a time-owned projection that joins issue_fact and never writes it. Write the negative permission test now even though rates arrive in M8, so M8 inherits it: a Test Engineer with time:log logs hours and cannot read a rate.

**Acceptance criteria**

- [ ] A test asserts a Test Engineer LACKS ratecard:read and billing:*, rather than reading the matrix and concluding it holds
- [ ] A submitted period is not editable; a rejected one is
- [ ] issue_fact is proven unwritten by the time module — asserted, not assumed
- [ ] ArchUnit green; repository interfaces carry @Transactional(readOnly = true)

**Files**

- `backend/src/main/java/com/tcms/time/`
- `backend/src/test/java/com/tcms/time/`
- `backend/src/test/java/com/tcms/architecture/`

### `t39` · M6 — timesheets, approvals, and estimate-vs-actual

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-forms`, `fe-react-data-fetching`, `fe-react-component-testing` | `t38` |

Port timesheets.html and timesheet-approvals.html, and add the estimate-vs-actual figures to the story and epic pages fed by the rollup projection.

**Acceptance criteria**

- [ ] A person logs time against an issue, a meeting and leave from one screen
- [ ] A rejected period returns to editable with the note visible; a submitted one is read-only
- [ ] Approved hours appear on the story, epic and project, and the basis is labelled
- [ ] build, lint and tests green

**Files**

- `frontend/src/features/time/`
- `frontend/src/features/tracker/`
- `frontend/src/api/queries/keys.ts`
- `frontend/src/lib/labels.ts`

---

## Phase 9 — M8: Billing

**Goal.** Approved hours are priced by rate card, budget burn is visible, and a draft invoice is issued with lines that then lock.

**Sequential.** One lane: rate cards and pricing precede the invoice screens, and the negative permission tests extend the ones M6 wrote in the same suite.

**Module plan:** `docs/plans/019-…md` (next free at start time) · **Migration:** projected `V11`

### `t40` · M8 — module plan and the billing contract commit

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `ep-writing-plans`, `be-shared-api-surface-plan` | `t39` |

Write docs/plans/0NN-billing.md. Time and materials only (D24); AI-assistant usage is a cost, never a line. Land the contract for rate cards, budgets, invoices, lines and expenses, with both clients and both contractBodies copies.

**Acceptance criteria**

- [ ] Every schema has its required array; contractPairing.test.ts green
- [ ] No fixed-fee or retainer shape appears anywhere in the contract
- [ ] The permission requirement on every billing endpoint is billing:* or ratecard:read

**Files**

- `docs/plans/019-billing.md`
- `docs/specs/openapi.yaml`
- `frontend/src/api/schema.d.ts`
- `packages/client/src/schema.ts`
- `frontend/src/api/contractBodies.ts`
- `packages/client/src/test/contractBodies.ts`
- `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

### `t41` · M8 — rate_card, budget, invoice, invoice_line, expense

| Agent | Skills | Depends on |
|---|---|---|
| `data-architect` | `data-postgresql`, `data-migrations` | `t40` |

Next free V from the directory (reserved V10). rate_card and rate_card_entry, budget, invoice, invoice_line and expense, with invoice_line carrying the priced snapshot so issuing can lock it.

**Acceptance criteria**

- [ ] SchemaConventionTest green on every new table
- [ ] invoice_line stores the rate applied at issue time, not a reference resolved later
- [ ] The migration takes the next free V and plan 009's table is corrected

**Files**

- `backend/src/main/resources/db/migration/`
- `backend/src/test/java/com/tcms/schema/`
- `docs/specs/2026-07-30-tcms-erd.md`

### `t42` · M8 — the billing module and the two negative permission tests

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `be-java-database-code`, `be-java-unit-testing`, `qa-permission-testing-java` | `t41` |

New com.tcms.billing module: approved time priced by rate card, budget burn, draft and issue an invoice, lines immutable once issued. Extend the negative test M6 wrote — a Test Engineer cannot see what their hours cost, and an Account Manager sees the client and the hours and never a rate. Assert the lack; do not read the matrix and conclude it holds.

**Acceptance criteria**

- [ ] Both negative permission tests are green and assert absence of a permission, not presence of a role
- [ ] An issued invoice's lines are immutable, proven by a rejected update
- [ ] AI-assistant usage never appears as an invoice line
- [ ] ArchUnit green; billing reads time's projection and does not write it

**Files**

- `backend/src/main/java/com/tcms/billing/`
- `backend/src/test/java/com/tcms/billing/`
- `backend/src/test/java/com/tcms/time/`

### `t43` · M8 — billing and invoice screens, and the client hours table

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `fe-react-ui-rules`, `fe-react-data-fetching`, `fe-react-component-testing` | `t42` |

Port billing.html and invoice.html, and add the hours-and-invoices table to the client page. Billing is ABSENT from the sidebar for everyone but Finance and Company Admin — absent, not greyed out.

**Acceptance criteria**

- [ ] A test asserts the Billing nav entry does not render for a Test Engineer or an Account Manager
- [ ] An issued invoice renders read-only with no edit affordances
- [ ] Budget burn is visible against the budget, with the basis labelled
- [ ] build, lint and tests green

**Files**

- `frontend/src/features/billing/`
- `frontend/src/features/clients/`
- `frontend/src/lib/permissions.ts`
- `frontend/src/api/queries/keys.ts`

---

## Phase 10 — M9: Delivery analytics

**Goal.** A PM reads a release burn-up that distinguishes scope added after the release was created, plus cycle time and cumulative flow.

**Sequential.** One lane: the report types must exist before the charts that render them; both touch the contract and keys.ts.

**Module plan:** `docs/plans/020-…md` (next free at start time) · **Migration:** projected `none`

### `t44` · M9 — module plan, timestamp verification, and the reporting contract

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `ep-writing-plans`, `be-shared-api-surface-plan` | `t43` |

Write docs/plans/0NN-delivery-analytics.md. First verify that issue_fact really carries created_at, status_entered_at, done_at and release_assigned_at and that release carries created_at — 'no migration' is true only because M1 and M3 put them there. If one is missing, M9 takes the next free V and the plan says so. Land the reporting contract with both clients and both contractBodies copies.

**Acceptance criteria**

- [ ] The four issue_fact columns and release.created_at are confirmed present, or a migration task is added to the module plan
- [ ] Every schema has its required array; contractPairing.test.ts green
- [ ] No velocity endpoint exists

**Files**

- `docs/plans/020-delivery-analytics.md`
- `docs/specs/openapi.yaml`
- `frontend/src/api/schema.d.ts`
- `packages/client/src/schema.ts`
- `frontend/src/api/contractBodies.ts`
- `packages/client/src/test/contractBodies.ts`
- `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

### `t45` · M9 — burn-up, cycle time and cumulative flow in reporting

| Agent | Skills | Depends on |
|---|---|---|
| `be-java` | `be-java-database-code`, `be-java-unit-testing` | `t44` |

Report types in com.tcms.reporting reading issue_fact and execution_fact; nothing imports tracker. The burn-up distinguishes scope added after the release was created by comparing issue_fact.release_assigned_at with release.created_at; cycle time is per issue type from status_entered_at and done_at; cumulative flow reads the status timestamps.

**Acceptance criteria**

- [ ] A unit test proves scope added after the release's created_at is reported separately from original scope
- [ ] Cycle time is reported per issue type, not as one aggregate
- [ ] A report with too little data reports that, rather than returning a flat series
- [ ] ArchUnit green — reporting does not import tracker

**Files**

- `backend/src/main/java/com/tcms/reporting/`
- `backend/src/test/java/com/tcms/reporting/`
- `backend/src/test/java/com/tcms/architecture/`

### `t46` · M9 — the reports screen and its charts

| Agent | Skills | Depends on |
|---|---|---|
| `fe-react` | `design-charts`, `fe-react-data-fetching`, `fe-react-component-testing` | `t45` |

Port reports.html. Every chart reads correctly in light and dark on the app's own tokens — the palette moves with data-color-scheme because @theme inline emits var(--…) — and a report with too little data says so rather than drawing a flat line.

**Acceptance criteria**

- [ ] Charts use the token palette through CSS custom properties, and are legible in both colour schemes
- [ ] A too-little-data report renders its explanation, not an empty axis
- [ ] No velocity chart exists anywhere in the app
- [ ] build, lint and tests green

**Files**

- `frontend/src/features/reports/`
- `frontend/src/api/queries/keys.ts`
- `frontend/src/lib/labels.ts`

---

## Decisions carried over from the runs

### Q — One lane or two?  **One lane.**

Plan 009 offers a Lane A (tracker spine) / Lane B (people and money) split across two checkouts. Declined. Both lanes
provably touch `openapi.yaml`, both generated clients, `contractBodies.ts` ×2, `keys.ts`, `labels.ts`,
`permissions.ts`, the router, `devFaults.ts`, `docs/features.md` and the MCP surface doc. Two lanes would also need
the lane-b stack (`.env.lane-b`, ports 6432 / 9080 / 6173), a pre-assigned fixture UUID prefix space, one holder of
`openapi.yaml` and `gen:api` per commit, and migration versions assigned in landing order — and the moment lane B
lands anything early, the reserved V-numbers are wrong on every already-migrated database while Testcontainers stays
green. Cost accepted: wall-clock is the sum of every module, and M8 (which needs M6 and Mc) sits near the end.

### Q — Where does Mc sit?  **Seventh, after M4.**

Confirmed 2026-08-30. The Mc run was planned on the premise that Mc lands next and therefore takes `V3` and plan
`011`; that premise is reversed. Hoisting Mc — which would have taken it off M8's critical path — was considered and
declined in favour of the tracker spine landing first. Plan 009's *"Mc is deferrable and sits anywhere after M0"*
still holds if that is revisited; moving it changes only the projected V and plan numbers, which every task re-reads
from disk anyway.

### Q — Is the whole ten-module program in scope?  **Plan all ten, stop after any module.**

Phases are hard barriers. The dependency order and the migration sequence are decided once and stay coherent; later
modules will be planned against a repo that has moved, so their prompts need refreshing — which step 4 of plan 009's
hand-off protocol already requires.

### Q — R14: may the Jira importer reuse `integration.tracker`'s per-person credential?  **No — per-run credential.**

The connection is entered per import run and stored encrypted on `import_run`, the way `TrackerCredential` is
encrypted. Reusing `TrackerIdentity` from `tracker.imports` is a forbidden module crossing in the direction ArchUnit
enforces, and lifting a credential type into a foundation module is the decision the `ArchitectureRules` Javadoc says
needs a paragraph, not an import. This also matches what `import-connect.html` already shows. Cost: the operator
re-enters a token they already gave the integration, and there is a second encryption call site to keep correct.
`t24` closes R14 in plan 009's review table.

---

## Running a task from here

1. Confirm the branch: `git rev-parse --abbrev-ref HEAD`. Work continues on it — no new branch, no worktree.
2. Start the module from **its prompt block** in plan 009, not from this file — the prompts are living text and are
   rewritten by the module before them. This file gives the order and the task cut; the prompt gives the reading list.
3. Dispatch the task's `agent` with its `skills`, handing it the intent, the acceptance criteria and the file list.
4. **One subagent at a time.** A second needs a fresh decision and a stated reason it is safe.
5. Gates run on `shanks`, never locally — `~/bin/tcms-remote sync`, then `backend ./gradlew build`,
   `frontend npm run test:run`, `packages npm run build`. Read `docs/specs/shanks-runner-details.md` first:
   `BUILD FAILED` with `Could not write XML test results` and zero assertion failures is an fd-limit problem.
6. Commit at the end of each phase, after its gate passes, staging only that phase's files.
7. Close the module per plan 009's **hand-off protocol**: status table, built-and-unverified list, the V actually
   taken, rewrite the dependent prompts, close the review items the module owned.

## Known pre-existing red, unrelated to this program

`com.tcms.reporting.FlakeScoreTest` fails six tests on `shanks` as of 2026-08-30 — proven pre-existing. It pins
`BASE = 2026-06-01T08:00:00Z` against the shipped 90-day flake window, so the fixture expired on 2026-08-30.
`./gradlew build` is **3586 of 3592 green** with those six the only failures. Do not attribute them to a module, and
do not let a module's closure claim them as its own.

