# Board & backlog — module M2 implementation plan

**Date:** 2026-08-31 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Do not run two tasks in
> parallel** — one lane, decided 2026-08-30 (009, "What can run in parallel").
>
> **One task is one session, and every task has a paste-ready prompt** — see
> [Prompts — one per task](#prompts--one-per-task), which sits above the task blocks. Start a task
> from its prompt; take its code from its block. **The prompts are living text**: the agent that
> finishes a task rewrites the prompts below it before it stops, per
> [Task hand-off protocol](#task-hand-off-protocol), so the block you read is always the current one
> and never the one written on 2026-08-31.

**Goal:** A person works a project board with swimlanes by epic, moves a card by pointer or by
keyboard, sees a WIP breach reported rather than enforced, ranks the backlog, configures two boards
over the same work, and sets one filter that survives switching between Board, Backlog and Table.

**Architecture:** `tracker.board` is a **sub-package of `com.tcms.tracker`, not a module** (scope
§2) — a board is configuration over `IssueStatus`, and splitting it would put a projection on the
far side of an event boundary from the thing it projects. Six new tables under `V5`, a fractional
`NUMERIC` rank on `issue`, ten new endpoints, and four frontend tasks that **extend**
`features/board/cardMove.ts` and `boardKeyboard.ts` rather than forking them.

**Tech stack:** Postgres 17 + Flyway `V5`, Spring Boot 3.5.6 / Java 21, `openapi.yaml` + two
generated clients, Vite 8 / React 19 / TanStack Query / Tailwind v4 + the render kit, `@dnd-kit`
(already a dependency, used by the composite board).

---

## Two facts checked before this plan was written, and both change what it says

**1. The migration is `V5`, not `V4`.** The 009 status table reserves `V4` for M2 and the M2 prompt
repeats it. `backend/src/main/resources/db/migration/` holds `V1__initial_schema.sql`,
`V2__pm_foundation.sql`, `V3__tracker_core.sql` and **`V4__retire_work_item.sql`** — M1 took *two*
versions, not one, because dropping `work_item` could only land in the commit that deleted the
`@Entity` mapping it (`937df5d4`). `out-of-order` is unset, so a `V4` written here would be
**silently not applied** to any instance that already ran M1's, and Testcontainers would never show
it. **This module takes `V5`.** Every module below M2 in the 009 table shifts by one; the hand-off
in T9 corrects the table.

**2. The derived-card regression the M2 prompt assigns to this module is already repaired.** The
prompt says moving a derived card "now answers 404 from `/issues/{key}`". It does not:
`work.BoardService` gives every derived card an **empty `allowedTransitions`**, so
`resolveCardMove` returns `REFUSED` and `refusalFor`'s derived branch produces *"…mirrors an
execution, so it can't be moved to X. Update the execution and the card follows."* — and
`cardMove.test.ts:153` and `:195` pin both sentences. M1 fixed it before closing and the prompt was
not updated. **No task here restores it.** T6 adds the one thing genuinely missing: the same
guarantee on the *project* board, whose cards are all issues and therefore cannot be derived at all
— which is why that board's refusal set is a different one.

---

## Decisions this plan takes

### D-a — "Team board" now names two different things, and they are not the same screen

| | `work` composite board | `tracker.board` project board (new) |
|---|---|---|
| Endpoint | `GET /board` | `GET /boards/{boardId}/cards` |
| Cards | four kinds — `ISSUE`, `EXECUTION`, `REVIEW`, `CASE_MAINTENANCE` | issues only |
| Columns | assembled from the cards (`columnsFor`) | rows in `board_column` |
| "team board" means | *a team's* cards, across projects — `/teams/:teamId/board` | one of the project's `board` rows, e.g. "Team board" in `settings-boards.html` |
| Screen | `BoardPage.tsx`, `TeamBoardPage.tsx` | `ProjectBoardView.tsx` (new) |

**M2 changes neither of the existing two.** `/projects/:projectId/board` and `/teams/:teamId/board`
keep working exactly as they do. The M2 done-criterion *"a card moved on the team board is in the
matching column on the stakeholder board"* is about the **second** column of that table — the two
`board` rows `settings-boards.html` shows over one project — and this plan never uses the bare
phrase "team board" again for that reason.

**The new screen lives in `frontend/src/features/board/` anyway**, beside the composite one, because
`cardMove.ts` and `boardKeyboard.ts` live there and the prompt requires they be extended rather than
forked. A second directory would mean a second copy of the move decision, which is the one part of
drag-and-drop that can be *wrong*.

### D-b — The project board's columns come from the server; `columnsFor` stays for the composite board

`columnsFor` exists because *"no single request answers 'every status on this board'"* for the two
cross-project boards. For a project board one does: `board_column` is the answer, and it includes
columns holding statuses **no card currently has**, which `columnsFor` deliberately cannot draw. So
`cardMove.ts` gains a second entry point, `resolveColumnMove`, taking server columns; `columnsFor`
and `resolveCardMove` are untouched and keep their callers. One file, two boards, no fork.

### D-c — The status move is NOT optimistic; the rank move IS. This is not a contradiction

Scope §12.4 is verbatim binding: *"A drop that `issue_transition` forbids is refused at the drag,
with the reason on screen. It is never accepted optimistically and reverted."* M1 honoured it by
giving `useTransitionIssue` no optimistic patch.

The M2 prompt asks for *"optimistic reordering **on a fractional rank**"* — which is the **rank**
drag, not the status drag. The two are different operations with different failure modes:

| | Column move (status) | Rank move (order) |
|---|---|---|
| Legality known client-side? | **Yes** — `allowedTransitions` is on the card | No — the server owns the neighbours |
| Optimistic? | **No.** Refused at the drag per §12.4 | **Yes**, with snapshot rollback |
| Can the server still refuse? | Yes (race, permission) — the card simply does not move, and `describeError` says why | Yes (409 on a concurrent rebalance) — the row **snaps back** and says why |

**This is where the rollback machinery is earned**, exactly as M1's note says: a rank drag is worth
it because there is nothing the client could have checked first. A status drag is not, because there
is. `tcmsFaults.rank('conflict')` (T6) is the switch that proves the snap-back, and it lands
**before** the board that uses it.

### D-d — `issue.rank` is `NUMERIC`, spaced by 1024, rebalanced by the service

Postgres `NUMERIC` is arbitrary precision, so a midpoint between two neighbours always exists and
inserting one **never rewrites another row** — the acceptance criterion in `t10`. New issues take
`max(rank) + 1024`. A midpoint whose scale would exceed **12 fractional digits** triggers a
project-scoped rebalance to `1024, 2048, 3072…` in one `UPDATE … FROM (SELECT row_number() …)`.
LexoRank was considered and rejected: it has the same unbounded-growth property, needs a
string-collation guarantee this schema does not otherwise depend on, and is not orderable by an
index this codebase already knows how to write.

### D-e — Labels need no new permission

The catalogue (`2026-07-30-tcms-roles-permissions-naming.md` §A3) has `board:read`, `board:rank`
and `board:configure` and **no `label:*`** — M0 seeded exactly those three. Creating, renaming and
deleting a project's labels is **`board:configure`** (it is project configuration, beside columns
and WIP limits, which is what that key's own description already says). Putting a label *on* an
issue is **`issue:edit`** (it edits the issue). **No catalogue change, so no `matrixCopies.test.ts`
churn and no three-document edit.** This is the same argument §A3 line 260 makes for there being no
`board:move`: a second key for an act an existing key already covers is a permission somebody can
hold without being able to use it.

### D-f — Four disagreements with the mockups, all deferred to M3, none resolved silently

009's precedence rule: on **what a screen shows** the mockup wins; on **what the system does** the
plan and scope spec win. These four are the second kind — the screen is right and the data does not
exist yet.

| Mockup shows | M2 ships | Why |
|---|---|---|
| `work-backlog.html` groups rows under **"2.4 · target 18 Sep"** and **"Unscheduled"** | **one ranked list**, filtered | `release` is M3's table. `issue_fact.release_id` is NULL until then — M1 said so explicitly. |
| A **"Move to release ▾"** bulk action (story B4) | bulk **"Set epic"** and **"Assign"** only | Same. B4's third criterion is M3's. |
| An **`AI`** tag on cards, and an **"AI-eligible"** filter chip | neither | `issue.agent_eligible` is D30/M5. |
| A **`PR #482`** badge | not drawn | M5's forge adapter. `Logged` hours are M6's, and are not on the board at all. |

Story **B3's** swimlane list is *epic, assignee, label, release, priority*. **M2 ships four**;
`release` is added by M3, which is one entry in a `Record<Union, …>` and a compile error until it is
labelled — the mechanism `lib/labels.ts` exists for.

### D-g — The auto-claim is a direct call, not an event

*"A status created after the board exists"* (009 review R13): `WorkflowService` and
`tracker.board.BoardConfigService` are in the **same Spring Modulith module** — `board` is a
sub-package — so `WorkflowService` calls `BoardConfigService.claimNewStatus(...)` directly inside
the same transaction. No `OutboxEvents` string, no one-minute delivery floor, and no window in which
a status is unclaimed. **An unclaimed status is not a state the data can be in**, and this is what
makes that true rather than merely likely.

### D-h — `GET /boards/{boardId}` returns configuration; `GET /boards/{boardId}/cards` returns cards

The 009 contract line reads `/boards/{id}` without saying which. Two endpoints, because they have
two cache lifetimes: a board's columns change when somebody edits them, its cards change on every
transition anywhere in the project. One combined read would re-fetch the column layout on every
card move and make the layout flicker.

### D-i — Two names came out different from the plan's, and a check found each

Taken by T1. Both are recorded here rather than as a comment in the contract, because the prompts
below depend on them and a reader of the YAML alone would not know either was ever in question.

| Plan said | Landed | Found by |
|---|---|---|
| `operationId: getBoard` on `GET /boards/{boardId}` | **`getBoardConfig`** | reading the neighbouring contract — `GET /board`, `work`'s composite board, has held `getBoard` since M1 |
| `Label.colorToken` / `LabelInput.colorToken` | **`Label.color` / `LabelInput.color`** | `responseCredentials.test.ts`, red on the first run of the gate |

The second is the one worth keeping. `responseCredentials.test.ts` reddens on any **response**
property whose name contains `secret`, `password`, `passphrase`, `credential`, `privatekey`,
`apikey` or **`token`**, and its exemption list is a list of *disclosure decisions* — the session
itself, a one-time plaintext, four end-anchored characters of a webhook URL. A design token is not
one of those, so exempting it would have put a non-credential in a list whose header says every
entry is a decision about disclosure, and taught the next reader that the list is where you go to
make the suite green. This contract already spells a credential three ways on the `token` needle;
a fourth meaning of the word is exactly the ambiguity that check exists to keep out.

**The database column follows the wire**, so T2 writes `issue_label.color`, not `color_token` — the
argument the old name was carrying ("a token name, never a hex value") lives in the column comment,
where it always did, and is not weakened by losing the word.

### D-j — §2's rule splits the ten operations three ways on the agent surface

Taken by T1, writing §3.5 of the surface document. The permission table decides this and it does not
land where the plan's step 8 implied one tool would:

| Operations | Gate | Roles (§A3) | Lands as |
|---|---|---|---|
| the five reads | `board:read` | all seven | **`tcms_read_board`** — new tool 27 |
| `moveBoardCard`, `putIssueLabels` | `issue:edit` | Test Engineer and up | **two new arms of `tcms_write_issue`** |
| `rankIssue`, the three label writes | `board:rank` / `board:configure` | **Project Manager and up** | **`tcms_write_backlog`** — new tool 28 |

A single write tool is illegal under §2: `Agent ∩ Test Engineer` is a reachable intersection holding
`issue:edit` and neither of the other two, so it would be listed to a caller who 403s on two thirds
of it. `board:rank` and `board:configure` are granted to the same three roles, so those two may
merge and do — and that merge is re-checked, not assumed, the day either widens.

**`createBoard`, `updateBoard` and `deleteBoard` reach neither surface.** Defining a board's columns
is a screen with a status-claim rule the server enforces in a 422 naming which status was claimed
twice; an agent composing that array blind spends its turns on the refusal.

### D-k — `issue.rank` is filled by a trigger, not by a `DEFAULT` and not by a service

Taken by T2, and it is what lets the column be `NOT NULL` **two tasks before `RankService` exists**.
`Issue` is not remapped by T2 (that would be `be` work in a `data` task), so every insert Hibernate
makes omits `rank` — and with no default that is a constraint violation on every issue created
anywhere in the suite.

D-d says a new issue takes `max(rank) + 1024`. A column `DEFAULT` cannot express it, because it may
not read another row. The two alternatives were both worse:

| | Why not |
|---|---|
| A constant default (`0`, `1024`) | every new issue ties with every other, so a backlog ordered by `rank` is ordered by nothing — while still reading as ranked |
| Assign it in `IssueService` only | a rule that holds for the callers who remember. This schema already made the opposite choice twice, for the issue-key counter and the seeded workflow, and for the same reason |

So `tcms_issue_rank_default()` is a `BEFORE INSERT` trigger on `issue`. **Ties are possible and
accepted:** two inserts into one project in the same instant read the same maximum, `created_at`
breaks it, and nothing claims `rank` is unique. Taking a project-wide lock on every issue insert to
avoid one would cost more than it buys.

**T4 must not re-implement this.** `RankService.place` sets an explicit rank and the trigger stands
aside whenever one is supplied; what the trigger owns is the *creation* path, which `RankService`
never touches.

### D-l — the board follows the workflow, and the repair is in the database

Taken by T2, **found by the gate rather than by reading**: thirty-eight tests across
`WorkflowEndpointTest` and `ProjectCreateEndpointTest` went red on the first run, all with
`board_column_default_status_id_fkey`. `WorkflowService` hard-deletes an `issue_status` row when a
status is dropped from the workflow PUT, and the seeded board holds a column defaulting to it — so
with a plain `RESTRICT` a **board** refuses a **workflow** edit.

That is backwards. D2 puts the canonical status on the issue and leaves a board *mapping* statuses
onto columns, so the workflow is upstream and every board rearranges itself around a status leaving.
`tcms_board_follows_status_removal()` is a `BEFORE DELETE` trigger on `issue_status` that repoints a
column at the earliest status it **still** claims, and deletes the column only when the departing
status was the last thing it claimed — which for the seeded one-status-per-column board is every
time.

Two things this decision is *not*:

- **Not a contradiction of the plan's refusal of a trigger.** That refusal is about
  *validation* — a trigger asserting "every status is claimed" would fire mid-transaction while a
  board is rebuilt column by column and reject the legal intermediate state of a `PATCH`. This is
  *repair*, on a table a board `PATCH` does not write, so it cannot see that state at all.
- **Not `ON DELETE CASCADE`, which was the one-line alternative and is silently wrong.** A folded
  stakeholder column claiming In Progress, In Review and Blocked would **vanish** when In Progress
  was retired, taking two live statuses off the board with it and leaving them claimed by nothing.
  `BoardSchemaTest` pins both halves.

**T3 must not add a second repair in Java.** `claimNewStatus` (D-g) is still a direct call and still
T3's, because a status *arriving* has no database event to hang off; a status *leaving* does, and one
rule with two homes is how the two disagree.

### D-m — the backend suite cannot be green until T4, and nine failures are not the current task's

Recorded so no session below reads somebody else's red as its own. After T2, `./gradlew build`
reports **9 failures and no more**:

| Count | Class | Whose | Green again when |
|---|---|---|---|
| 3 | `ContractConventionTest`, `ContractEnumTest`, `ContractParameterBindingTest` | **T1's** | **T4** — the last of the fourteen handlers lands |
| 6 | `FlakeScoreTest` | **nobody in 012** | unknown — already red on `master` in the 2026-08-31 nightly, before T1 |

T1's gate was the packages build and the contract *suites*, which are a `packages` job. It never ran
the backend suite, and the backend has three checks that read `openapi.yaml` and compare it to the
server: fourteen operations are declared and no handler serves them, seventeen object schemas are
paired with no record, and one enum (`SwimlaneBy`) names no server type. **`ContractParameterBindingTest`
has deliberately no ignore list** — its own message says an operation that is "not built yet" is
exactly the finding — so the only repair is to implement the handlers, which is T3's and T4's whole
job. *Built and unverified* already says T1 describes a server nobody has run; this is the measured
form of it.

**A task below T2 verifies its own gate by the failure set shrinking, not by it being empty.** T3
should see the three contract failures narrow as its five board-configuration operations gain
handlers; T4 should see them reach zero. Six `FlakeScoreTest` failures survive all of it and belong
to another lane.

**Measured after T3** (`7c9883b9`, shanks, 3703 tests): **eight failures.** `ContractEnumTest` is
green — `SwimlaneBy` was the only enum in the set and T3's server type closed it. The two that
remain name T4's nine operations and T4's twelve schemas and nothing else; both lists are copied
into T4's prompt, so a name outside them is a regression rather than a leftover.

### D-n — T3's five names came out different from the plan's pseudo-code, and the house pattern is why

Taken by T3, and recorded here because T4 and T6 both bind to these.

| The T3 block wrote | Landed | Why |
|---|---|---|
| `throw new UnprocessableException(...)` | a sealed `BoardConfigService.Outcome` — `Unprocessable(title, detail)` · `Written(BoardView)` · `Deleted()` | **there is no `UnprocessableException` in this codebase.** Every service here returns a sealed `Outcome` inside a `GateResult`, so the controller's `switch` is exhaustive at compile time — the failure that shape exists to prevent is a fourth outcome added later and handled in two endpoints of three |
| `service.create(projectId, input)` | `create(userId, projectId, input)` → `GateResult<Outcome>` | `ProjectService.writeGranted(userId, projectId, key, fn)` is the gate, and it takes the caller. The same for `update(userId, boardId, …)` and `delete(userId, boardId)`; the two reads are `list(userId, projectId)` and `board(userId, boardId)`, both `Optional`, both through `readVisible` |
| the `BoardColumn` schema paired with the entity | a **second** record, `BoardColumnView` | `ContractConventionTest`'s own rule: pairing a schema to an entity is how a column becomes a field nobody meant to publish. `board.lock_version` is the instance — on the row, not on the wire |
| `BoardSwimlane` / `BoardSwimlaneId` | **not written** | `board_swimlane` is per-viewer collapse state and **the contract has no endpoint that writes it**. `ArchitectureRulesTest.everyEntityIsWritableByTheProduct` fails an entity whose table the product cannot put a row in, so mapping it two tasks before its writer would have reddened the gate. T7 renders lane collapse; whoever gives it a door writes the entity then |
| `BoardConfigControllerTest` | `BoardConfigEndpointTest` | every endpoint test in this repository is `*EndpointTest`, and that suffix is how somebody greps for them |

Two things the same session found that the next backend task will hit:

- **`TrackerEndpointTestBase` is `public` now.** A test in `com.tcms.tracker.board` is in a different
  package from the fixture plumbing it needs, and duplicating the company/user/grant/seed fixture is
  how two definitions of "a seeded project" start disagreeing.
- **A test calling a service in-process must open the company scope itself** —
  `try (var ignored = CompanyContext.open(companyId))`. A request arrives with one because the
  session establishes it; a direct call does not, and without it `app.current_company` is unset, RLS
  filters every row, and the gate answers *no such project*. Nine tests failed exactly that way
  before the guard went in, all of them reading as an authorization defect.

### D-o — a board `PATCH` parks its columns at negative positions before it renumbers them

Taken by T3. `uq_board_column_position (company_id, board_id, position)` is not deferrable and
Hibernate flushes its updates in an order of its own choosing, so rotating three columns by one place
collides with the index on whichever update goes first — **a legal reorder refused by a constraint
about a state it only passes through.** `BoardConfigService.rebuildColumns` therefore moves every
kept column to a negative position and flushes, then writes the final `0..n-1` and flushes again.

The alternative was deleting every column and re-inserting, which is one pass and loses the column
ids — and the contract says in as many words that a column carrying an id is *kept and updated*.
`reorderingColumnsKeepsTheirIdentity` is the test that would have gone green under the wrong one.

### D-p — restoring a project's workflow in a fixture must delete the board FIRST

Taken by T3, and it is a **standing trap for every test that edits a workflow**, not a T3 detail.

`tcms_seed_project_defaults`' board block is guarded by `NOT EXISTS (SELECT 1 FROM board …)`. Deleting
a project's `issue_status` rows fires `board_follows_status_removal`, which takes the columns with
them — so a fixture that deletes the statuses and re-seeds gets its six statuses back and a board
with **zero columns**, silently, because the guard sees the board row still there and skips.

`WorkflowEndpointTest.restoreWorkflow` has done exactly this since T2 and nothing noticed, because
nothing in that class looks at a board. It is why `BoardConfigService.claimNewStatus` skips a board
with no columns rather than indexing into an empty list. The fix in the two T3 fixtures that need it
is one line and an ordering: `DELETE FROM board` first, then the statuses, then the seed.

---

## File structure

**New backend — `backend/src/main/java/com/tcms/tracker/board/`** (one package, no module):

| File | Responsibility |
|---|---|
| `Board.java`, `BoardColumn.java`, `BoardColumnStatus.java`, `BoardColumnStatusId.java`, `BoardSwimlane.java` | JPA entities for the four config tables |
| `IssueLabel.java`, `IssueLabelAssignment.java`, `IssueLabelAssignmentId.java` | labels |
| `BoardRepository`, `BoardColumnRepository`, `BoardColumnStatusRepository`, `IssueLabelRepository`, `IssueLabelAssignmentRepository` | Spring Data, each `@Transactional(readOnly = true)` on the **interface** |
| `BoardConfigService.java` | create/edit/delete a board, its columns and their status claims; `claimNewStatus` (D-g) |
| `BoardCardService.java` | `GET /boards/{id}/cards` — cards grouped into columns and lanes, plus WIP breaches |
| `BoardMoveService.java` | `POST /boards/{id}/cards/{key}/move` — resolve the column's default status, delegate the transition, report the breach |
| `RankService.java` | midpoint allocation and project-scoped rebalance (D-d) |
| `LabelService.java` | label CRUD and per-issue assignment |
| `BoardConfigController`, `BoardCardController`, `BacklogController`, `RankController`, `LabelController` | the HTTP surface |
| `*View.java`, `*Input.java` records | wire shapes, mirroring `openapi.yaml` |

**New frontend** — all under `frontend/src/features/board/` and `frontend/src/features/settings/`:

| File | Responsibility |
|---|---|
| `api/queries/projectBoards.ts` | **not `boards.ts`** — `board.ts` already holds the composite board's three reads, and two files one letter apart is how a wrong import survives review |
| `features/board/ProjectBoardView.tsx` | the board inside the Work switcher |
| `features/board/Swimlane.tsx` | one lane: header with roll-up completion, collapse, its columns |
| `features/board/BoardCardFace.tsx` | the dense card face (§12.2) |
| `features/board/ColumnHeader.tsx` | name, count, WIP state |
| `features/board/MoveToMenu.tsx` | the `⋮ Move to…` every card carries |
| `features/board/BacklogView.tsx` | the ranked list with `↑ ↓` on every row |
| `features/board/useWorkFilter.ts` | the one filter, read from and written to the URL, shared by all three views |
| `features/settings/BoardSettingsPage.tsx`, `ColumnEditor.tsx` | `settings-boards.html` |

**Extended, never forked:** `features/board/cardMove.ts`, `features/board/boardKeyboard.ts`,
`lib/devFaults.ts`, `api/queries/keys.ts`, `lib/labels.ts`, `features/tracker/WorkTablePage.tsx`.

---

**Tasks** — in the order they are done:

| # | Task | Stack | Deliverable | Est. | Depends on |
|---|---|---|---|---|---|
| 1 | T1 — the board contract | `ts` | Ten endpoints in `openapi.yaml`, both clients regenerated, both `contractBodies` copies moved, `contractPairing.test.ts` green | ~95k | none |
| 2 | T2 — `V5` board schema | `data` | `V5__tracker_board.sql` applied; six tables, `issue.rank`, a default board seeded per project | ~95k | T1 |
| 3 | T3 — board configuration | `be` | `GET\|POST /projects/{id}/boards`, `GET\|PATCH\|DELETE /boards/{id}`; a status added to the workflow is claimed by every board | ~130k | T2 |
| 4 | T4 — cards, rank and labels | `be` | `GET /boards/{id}/cards` reports a WIP breach; `POST …/move`; `PUT /issues/{key}/rank`; the label endpoints | ~130k | T3 |
| 5 | T5 — board verbs on the CLI and MCP | `ts` | `tcms board`, `tcms backlog`, `tcms rank`, `tcms label` and their MCP tools, in the surface document | ~110k | T4 |
| 6 | T6 — the fault switch, the queries, and the move logic | `fe` | `tcmsFaults.rank('conflict')` and `.wip()`; `projectBoards.ts`; `resolveColumnMove` and swimlane-aware keyboard, unit-tested — **no screen** | ~110k | T4 |
| 7 | T7 — the project board | `fe` | `?view=board` renders swimlanes, WIP breaches and `⋮ Move to…`; the whole board works from the keyboard | ~145k | T6 |
| 8 | T8 — the backlog and one filter | `fe` | `?view=backlog` ranks by pointer and keyboard; a filter set on Board still applies on Table | ~120k | T7 |
| 9 | T9 — board settings and the close-out | `fe` | `settings-boards.html` ported; `docs/features.md`, the MCP surface and 009's status table closed out | ~100k | T8 |

**Total ≈ 1035k across nine sessions.** Four are `fe`, which is what *"the hardest frontend in the
expansion"* costs. The two `be` tasks split at **configuration vs. contents** — a single
`tracker.board` task estimated ~177k, over the 150k ceiling, and that is its natural seam.

**What this ordering costs, stated rather than discovered:** nothing a person can use exists until
**T7**. T3 and T4 ship endpoints with no screen; T5 ships verbs that work but that no UI calls. That
is the price of one agent switch per stack (009's "How a module's plan is cut into tasks", rule 6),
accepted deliberately. The tree builds and the suites are green at every one of the nine boundaries.

---

## Prompts — one per task

Each block below is what is pasted into a **fresh session** to run that task. They are written to be
complete on their own: what to read and in what order, the traps that specific task will hit, what
to commit, and which gate to run. Read the task block further down for the code; read the prompt for
what a session that has never seen this repository needs before it starts.

**The prompts are living text.** A task that lands something different from what this plan predicted
**rewrites the prompts below it before it stops** — in place, with no changelog appended — under
[Task hand-off protocol](#task-hand-off-protocol). The next session trusts its prompt and the task
block, and re-verifies nothing else.

### Task hand-off protocol

What the executing agent does **after** its task's gate is green and its commit is in, before it
stops. This is part of every task's definition of done.

1. **Tick the task's steps** in its block, and put `**Landed:** <short sha>` on the task beside its
   `Deliverable`.
2. **Rewrite the prompts below it that are now untrue** — a renamed file, a signature that came out
   different, a decision the task had to take, a trap it hit that the next one will hit too, an
   endpoint whose shape moved. Edit the prompt text **in place**. If nothing changed, say so in the
   commit message rather than touching a prompt.
3. **Record a surprise as a decision**, not as a comment in code nobody will find: add a `D-<letter>`
   to *Decisions this plan takes* above, and name it from the prompts that depend on it.
4. **Add to *Built and unverified*** anything the gate proved compiles and nobody has clicked.
5. **Commit the plan edit separately** — `docs(plan): 012 T<n> landed`, staging only this file. The
   code commit and the plan commit are two commits, so the code commit's diff is only code.

**Do not run two tasks in parallel.** One lane, decided 2026-08-30. Two agents in one tree produced
three silent losses of committed work in this repository, with no conflict, no error, and a clean
`git status`.

---

### T1 — the board contract

````text
You are working in the tcms repo, on the current branch (do not create one — check with
`git rev-parse --abbrev-ref HEAD`). Read CLAUDE.md first.

Run T1 of docs/plans/012-board-and-backlog.md — the board contract. Read the T1 task block for the
YAML; this prompt is what you need before you start.

READ, IN THIS ORDER:
  1. docs/plans/012-board-and-backlog.md — "Decisions this plan takes" (all of D-a..D-h) and the
     T1 block. D-a, D-e and D-h are the three the contract encodes.
  2. docs/specs/openapi.yaml lines 8268-8800 — /board, /issues, /issues/{key}/transitions,
     /projects/{projectId}/workflow. Every operation you add sits beside these and copies their
     conventions.
  3. docs/specs/2026-08-30-project-management-expansion-scope.md — D2 (one canonical status,
     columns claim statuses), §12.2 (labels, filter semantics), §12.4 (the drag rule).
  4. docs/specs/2026-08-30-project-management-expansion-user-stories.md — stories B2, B3, B4.

TRAPS THIS TASK WILL HIT:
  * `required` DOES NOT DESCEND. An inline nested object without its own `required` array makes
    every one of its properties optional in both generated clients, silently. Step 5 is a python
    script that checks this mechanically — run it, do not read for it. CLAUDE.md: reading V1 top to
    bottom missed five join tables; scripting the comparison found them.
  * There is NO `UnprocessableEntity` response component in this contract. A 422 is written inline
    with `application/problem+json` + the `Problem` schema. `Conflict`, `NotFound`, `Forbidden`,
    `Unauthorized` and `BadRequest` DO exist under `components/responses`.
  * The completion schema is called `IssueCompletion`, not `Completion`.
  * Do NOT put a `parameters:` block on a path item where a query parameter would be inherited by
    an operation that must not have it (§50) — /board carries a comment saying exactly this.
  * BOTH generated clients move in this commit: `cd frontend && npm run gen:api` and
    `cd packages/client && npm run gen:api`. Neither file is ever hand-edited. If the diff shows a
    schema you did not touch, the generator version moved — stop and say so.
  * The new card schema is `BoardIssueCard`, deliberately NOT `BoardCard`. `BoardCard` is `work`'s
    composite card with four `kind`s and a `derived` flag; every card on a project board is an issue
    with a real key (D-a).
  * `contractBodies.ts`'s SHARED REGION is generic contract-walking machinery, not per-endpoint
    bodies — adding endpoints usually needs NO edit there. If you do edit it, edit BOTH copies:
    frontend/src/api/contractBodies.ts and packages/client/src/test/contractBodies.ts are
    byte-identical between the markers and `contractPairing.test.ts` names the first line that
    differs.

GATE (on shanks, per the standing directive — read docs/specs/shanks-runner-details.md first):
  ~/bin/tcms-remote sync
  ~/bin/tcms-remote packages npm run build
  ~/bin/tcms-remote packages 'npm run test:run --workspaces'

COMMIT: `git add <paths>` only, never -A. openapi.yaml, both schema files, and the MCP surface doc.
Message in the T1 block, step 9.

CLOSE per "Task hand-off protocol" in the plan: tick the steps, record the sha, and rewrite T2's
prompt if the schema names or the endpoint shapes came out different from what T1 predicted.
````

### T2 — `V5` board schema

````text
You are working in the tcms repo, on the current branch. Read CLAUDE.md first, then run
/data-postgresql and /data-migrations — the plugin skills are mandatory, not decorative.

Run T2 of docs/plans/012-board-and-backlog.md — the V5 migration. Read the T2 task block for the
SQL; this prompt is what you need before you start.

READ, IN THIS ORDER:
  1. docs/plans/012-board-and-backlog.md — "Two facts checked before this plan was written"
     (the version is V5, and why), D-d (why rank is unconstrained NUMERIC), D-g, and the T2 block.
  2. backend/src/main/resources/db/migration/V3__tracker_core.sql — ALL of it, but especially
     lines 436-606, which is `tcms_seed_project_defaults`. You reproduce that function IN FULL.
  3. backend/src/main/resources/db/migration/V1__initial_schema.sql — its RLS block, which is the
     pattern the six new tables copy.
  4. docs/specs/tenancy-invariants.md — the two silent RLS traps.

TRAPS THIS TASK WILL HIT, AND THE FIRST TWO ARE SILENT:
  * THE FILE IS V5. `ls backend/src/main/resources/db/migration/` before you write anything. The
    009 table reserves V4 for this module and is WRONG — M1 took two versions. `out-of-order` is
    unset in application.yml, so a V4 written now is NEVER APPLIED on any instance that ran M1, and
    Testcontainers builds every test database from scratch so it will never show you the conflict.
  * `CREATE OR REPLACE FUNCTION tcms_seed_project_defaults` REPLACES THE WHOLE BODY. A block you
    omit stops being seeded for every project created from here on, with no error anywhere. There
    are SIX existing blocks — classification_dimension, case_status, configuration, doc_space,
    project_key_sequence, the six-status workflow — and you add a seventh. Confirm all six are
    present before you add yours. There is NO ProjectCreatedEvent and you must not build one
    (011 D-b).
  * The backfill loop must `set_config('app.current_company', c::text, true)` per company. Every
    INSERT is under FORCE ROW LEVEL SECURITY, so a loop that does not set it INSERTS NOTHING AND
    REPORTS SUCCESS. V3's own loop at the end of the file is written this way for that reason —
    copy its shape.
  * FORCE, not merely ENABLE. ENABLE alone leaves the table's owner exempt, and Flyway connects as
    `tcms_owner`.
  * `rank NUMERIC` with NO precision. `NUMERIC(12,4)` runs out of midpoints after ~14 inserts
    between one pair and then silently returns a rank equal to a neighbour's.
  * Every composite index LEADS with company_id — the RLS predicate is on it and an index that
    does not lead with it is not used by the filtered scan. `SchemaConventionTest` checks this.
  * `SchemaConventionTest` pins the forced-RLS policy count. V4 took it 84 -> 82; six tables here
    make it 88. Move the number AND the comment beside it.
  * THE LABEL COLOUR COLUMN IS `color`, NOT `color_token` (D-i). T1 renamed the wire property
    because `responseCredentials.test.ts` reddens on any response property containing `token`; the
    column follows the wire so JPA needs no @Column override and nobody has to remember which of
    the two spellings a given layer uses. The T2 block's SQL below is already corrected.

GATE (on shanks — read docs/specs/shanks-runner-details.md first):
  ~/bin/tcms-remote sync
  ~/bin/tcms-remote backend './gradlew build'
  BUILD FAILED with "Could not write XML test results" and ZERO assertion failures is an fd-limit
  problem, not a red suite. Re-run as `ulimit -n 65535; ./gradlew build`.

COMMIT: `git add <paths>` only. The migration, BoardSchemaTest, SchemaConventionTest, and the ERD.

CLOSE per "Task hand-off protocol". If the version you actually took is not V5, say so on the task
AND in T3's prompt — and in the 009 correction T9 will make.
````

### T3 — board configuration

````text
You are working in the tcms repo, on the current branch. Read CLAUDE.md first, then run /be-java
and be-java-database-code — the plugin skills are mandatory. (The orchestrator entry point is a
COMMAND, not a skill: `Skill(be-java:be-java)` is how you invoke it; a plugin that appears missing
is usually an install-path problem — `claude plugin list --json` and check `projectPath` against
`pwd`, because `claude plugin list` prints every row as enabled and will not show you this.)

Run T3 of docs/plans/012-board-and-backlog.md — board configuration. Read the T3 block for the code.

T2 LANDED `V5__tracker_board.sql`, NOT `V4`. The six tables exist, forced-RLS, with the default board
seeded per project. Two things came out of T2 that this task must not re-do — read D-k and D-l before
you write anything.

READ, IN THIS ORDER:
  1. docs/plans/012-board-and-backlog.md — D-a (two boards, two meanings), D-e (no new permission),
     D-g (the auto-claim is a direct call), D-k, D-l, D-m, and the T3 block.
  1b. backend/src/main/resources/db/migration/V5__tracker_board.sql — the tables you map. Section 1b
     is the trigger that already repairs a board when a status is deleted.
  2. backend/src/main/java/com/tcms/tracker/package-info.java — the module boundary. You do NOT
     change `allowedDependencies`. `board` is a SUB-PACKAGE of `tracker`, so nothing here crosses a
     module boundary at all.
  3. backend/src/main/java/com/tcms/tracker/WorkflowService.java — where `claimNewStatus` is wired
     in, and how a project-scoped configuration service is written here.
  4. backend/src/main/java/com/tcms/tracker/IssueStatusRepository.java — the repository pattern.
  5. docs/specs/2026-08-30-project-management-expansion-user-stories.md — story B2.
  6. 009-project-management-expansion-modules.md — the paragraph "A status created after the board
     exists (review R13)".

TRAPS THIS TASK WILL HIT:
  * THE BACKEND SUITE IS NOT GREEN WHEN YOU START, AND NINE FAILURES ARE NOT YOURS (D-m). Three
    contract tests (ContractConventionTest, ContractEnumTest, ContractParameterBindingTest) are red
    because T1 landed fourteen operations, seventeen object schemas and the SwimlaneBy enum that no
    handler, no record and no server type serves yet — ContractParameterBindingTest has DELIBERATELY
    no ignore list, so the only repair is to implement them, which is your five operations plus T4's
    nine. Six FlakeScoreTest failures were already red on master in the 2026-08-31 nightly and belong
    to nobody in this plan. VERIFY YOUR GATE BY THE FAILURE SET SHRINKING, not by it being empty:
    yours is green when nothing but those nine remains and the contract three name only T4's
    operations.
  * THE STATUS-REMOVAL REPAIR ALREADY EXISTS, IN THE DATABASE (D-l). V5's
    `board_follows_status_removal` trigger repoints a column at the earliest status it still claims
    and deletes the column only when the departing status was the last thing it claimed. DO NOT add
    a second repair in BoardConfigService — one rule with two homes is how the two disagree. What is
    still yours is `claimNewStatus` for a status ARRIVING (D-g), which has no database event to hang
    off.
  * `board_column.position` and `issue_status.position` are plain lower-case columns written quoted
    in the DDL. Map them with `@Column(name = "position")`, exactly as IssueStatus.java already does.
  * `Issue` GAINS NOTHING IN THIS TASK. `issue.rank` is NOT NULL and filled by the
    `issue_rank_default` trigger (D-k), so an insert that omits it still works; mapping the field is
    T4's, with RankService.
  * `@Transactional(readOnly = true)` GOES ON THE REPOSITORY INTERFACE. Spring Data gives a
    transaction only to the inherited CRUD methods; a derived or @Query method without it runs with
    no company set, the RLS policy sees an unset `app.current_company`, and EVERY READ RETURNS ZERO
    ROWS — fail-closed, silent, no exception to find it by. Every repository you create carries it.
  * "Every status claimed by exactly one column" is TWO rules and only one is a constraint. The
    unique index on board_column_status (board_id, status_id) rejects "claimed twice". "Claimed at
    least once" is a cross-table assertion, Postgres has no CHECK for it, and a trigger would fire
    mid-PATCH while a board is rebuilt column by column and reject a legal intermediate state. It
    is a SERVICE rule, and its message names the unclaimed statuses.
  * The auto-claim is a DIRECT CALL from WorkflowService, inside the same transaction — not an
    OutboxEvents hop. A Java event type may not cross a module boundary here at all, and outbox
    delivery has a one-minute floor during which the project would hold a status no column claims.
  * The same-kind fallback is the LAST column, not the first. Left-to-right is direction of travel;
    a new DONE status filed at the start of the workflow is worse than one filed late.
  * There is NO `board:move` permission and you must not add one (§A3 line 260). Configuration is
    `board:configure`, which M0 already seeded. Adding a permission means editing the roles spec,
    permission-matrix-summary.md and README.md in one commit or `matrixCopies.test.ts` reddens.
  * A comment claiming somebody LACKS a permission §A3 grants them fails the PACKAGES job
    (`tierClaims.test.ts`). A stale file path named in any comment fails it too (`citedFiles`).
  * ArchUnit must be green WITHOUT editing ArchitectureRules.FOUNDATION_MODULES. If it is not,
    tracker.board reached for a module tracker may not see — that is a defect in this task, not a
    case for a sixth foundation module.

GATE: ~/bin/tcms-remote sync && ~/bin/tcms-remote backend 'ulimit -n 65535; ./gradlew build'
      Nine pre-existing failures, and no more — see the D-m trap above. `BUILD FAILED` with
      "Could not write XML test results" and zero assertion failures is the fd limit, not a red suite.

COMMIT: `git add <paths>` only — the new package, WorkflowService.java, and the tests.

OUT OF SCOPE, and say so rather than pretending otherwise: no screen renders any of this. Cards,
rank and labels are T4; the board is not drawn until T7.

CLOSE per "Task hand-off protocol". If BoardConfigService's method names or BoardView's shape came
out different from the T1 contract, T4's and T6's prompts are the ones to fix.
````

### T4 — cards, rank and labels

````text
You are working in the tcms repo, on the current branch. Read CLAUDE.md first, then run /be-java,
be-java-database-code and be-java-unit-testing.

Run T4 of docs/plans/012-board-and-backlog.md — cards, rank and labels. Read the T4 block for the
code. Write RankServiceTest FIRST and watch it fail: the rank arithmetic is the part of this task
that can be subtly wrong rather than merely unwired.

READ, IN THIS ORDER:
  1. docs/plans/012-board-and-backlog.md — D-d (the rank), D-e (labels take no new permission),
     D-k (the rank DEFAULT is a trigger, and why RankService must not re-implement it), D-m (the
     failures that are not yours, and how many of them YOU are expected to clear), D-n (T3's names,
     which are the ones you bind to), D-o and D-p (two traps T3 hit that you will too), and the T4
     block.
  2. backend/src/main/java/com/tcms/tracker/TransitionService.java — you DELEGATE to it. Do not
     reimplement "may this issue go there"; two implementations of that question is how two
     answers to it start disagreeing.
  3. backend/src/main/java/com/tcms/tracker/board/BoardConfigService.java — T3's, for the column
     and claim repositories you read here, for the sealed-Outcome shape you copy, and for
     `viewOf`, which is already the "columns in position order, statuses in workflow order" read.
  4. docs/specs/2026-08-30-project-management-expansion-scope.md §12.2 (filter semantics, labels)
     and §12.4 (the drag rule — it constrains the CONTRACT you are serving).
  5. Stories B3 and B4.

TRAPS THIS TASK WILL HIT:
  * YOU ARE THE TASK THAT CLEARS THE CONTRACT SUITES (D-m). T3's gate on shanks measured it:
    3703 tests, EIGHT failures. ContractEnumTest went green when SwimlaneBy gained a server type,
    so what is left is ContractParameterBindingTest naming YOUR NINE OPERATIONS —
    GET /boards/{boardId}/cards, POST /boards/{boardId}/cards/{key}/move,
    GET /projects/{projectId}/backlog, PUT /issues/{key}/rank, the four /labels operations and
    PUT /issues/{key}/labels — and ContractConventionTest naming YOUR TWELVE SCHEMAS: BoardContents,
    BoardLane, WipBreach, BoardIssueCard, CardMoveInput, CardMoveResult, BacklogRow, RankInput,
    RankResult, Label, LabelInput, IssueLabelsInput. Both lists are copied from that run, so a name
    on either that is not in them is a regression you caused. YOUR GATE IS GREEN WHEN ONLY THE SIX
    FlakeScoreTest FAILURES REMAIN — those were red on master in the 2026-08-31 nightly and belong
    to no task in this plan.
  * PAIRING A SCHEMA IS AN EDIT TO A TEST FILE, and T3 is the worked example: a named schema goes in
    `ContractConventionTest.RESPONSE_RECORDS` as a class literal, an inline object in
    `INLINE_RECORDS`, and a named enum in `ContractEnumTest.OWNERS` as a fully-qualified string.
    A schema pairs with a VIEW RECORD and never with an entity — that rule is what made T3 write
    `BoardColumnView` beside the `BoardColumn` entity (D-n).
  * T3'S SERVICE DOES NOT THROW (D-n). `BoardConfigService` returns
    `GateResult<BoardConfigService.Outcome>` with a sealed `Outcome` of `Unprocessable(title,
    detail)` / `Written(BoardView)` / `Deleted()`, and every method takes the caller's userId first:
    `create(userId, projectId, input)`, `update(userId, boardId, input)`,
    `delete(userId, boardId)`, `list(userId, projectId)`, `board(userId, boardId)`. Follow that
    shape rather than the pseudo-code in the T4 block, which predates it.
  * A TEST THAT CALLS A SERVICE IN-PROCESS OPENS THE COMPANY SCOPE ITSELF —
    `try (var ignored = CompanyContext.open(companyId))`. Without it `app.current_company` is unset,
    RLS filters every row and the gate answers "no such project": nine of T3's tests failed exactly
    that way and every one of them read as an authorization defect (D-n).
  * A FIXTURE THAT RESTORES A WORKFLOW DELETES THE BOARD FIRST (D-p). The seed function's board
    block is guarded by NOT EXISTS, and deleting issue_status rows takes the columns with them via
    the V5 trigger — so "delete statuses, re-seed" leaves a board with zero columns, silently.
  * `TrackerEndpointTestBase` IS PUBLIC since T3, so a test in `com.tcms.tracker.board` extends it
    rather than restating the company / user / grant / seed fixture.
  * `issue.rank` IS ALREADY NOT NULL AND ALREADY FILLED ON INSERT, by V5's `issue_rank_default`
    trigger (D-k). It stands aside whenever an insert supplies a rank, so RankService.place just
    writes one — do NOT add a creation-path default in IssueService, and do NOT assume a new issue
    has no rank. The column is `NUMERIC` with no precision and is deliberately NOT unique: two
    concurrent creations in one project can tie, and `created_at` breaks it, so every ordered read
    is `ORDER BY rank, created_at, id` and never `ORDER BY rank` alone.
  * THE LABEL COLOUR COLUMN AND WIRE PROPERTY ARE BOTH `color` (D-i). There is no `color_token`
    anywhere; JPA needs no @Column override.
  * THE WIP LIMIT IS REPORTED, NEVER ENFORCED. A move that breaches it returns 200 with the breach
    in the body. Story B3: "the drop is accepted and the column header shows the breach". A limit
    that refused drops is a limit people route around by not using the board, and then the board
    stops describing the work.
  * A column at EXACTLY its limit is not in breach. Off-by-one here puts a permanent warning on any
    team that actually works to its limit, and a warning that is always on is one nobody reads.
  * A card ALREADY IN A STATUS THE TARGET COLUMN CLAIMS is not a move — return 200 and change
    nothing. Writing the column's default would silently walk In Review backwards to In Progress
    and look like the board undid a drag.
  * Rank is NEIGHBOUR-RELATIVE, never absolute. Two people dragging at once each computed a
    midpoint from a list they read seconds ago; sending the number races, sending the neighbours
    does not. Exactly one of afterKey/beforeKey, or 400.
  * `rebalanced: true` is not a failure and is easy to get wrong in the other direction — the
    client must DROP its optimistic order, because after a respace every other row's rank is stale.
  * The rebalance uses `row_number() OVER (ORDER BY rank, id)` — it is ORDER-PRESERVING. A respace
    that re-sorted would silently rearrange a backlog somebody spent an afternoon on.
  * A cross-project neighbour is a 400, not a best effort: rank is project-wide, so a midpoint
    against a number from another project's sequence lands the issue in an order nobody can explain.
  * An EPIC is not a backlog row (D31 derives its status from its stories), and a backlog listing
    both an epic and the stories under it double-counts the same work in the one view whose whole
    job is "what is next".
  * Labels: creating one is `board:configure`, putting one on an issue is `issue:edit` (D-e). No
    new permission — see T3's prompt for what adding one would cost.
  * Filter semantics are OR WITHIN a category, AND ACROSS categories. `labelId=a&labelId=b&
    assigneeId=c` is (a or b) and c.

GATE: ~/bin/tcms-remote sync && ~/bin/tcms-remote backend 'ulimit -n 65535; ./gradlew build'
      You start from EIGHT failures and green here means SIX — FlakeScoreTest only. (fd-limit note
      in T2.)

COMMIT: `git add <paths>` only — the new classes in tracker/board and their tests.

OUT OF SCOPE: no screen calls any of this. The board arrives in T7, the backlog in T8.

CLOSE per "Task hand-off protocol". T5's and T6's prompts both name endpoint shapes — if any moved,
fix them there before you stop.
````

### T5 — board verbs on the CLI and MCP

````text
You are working in the tcms repo, on the current branch. Read CLAUDE.md first, then run
/tooling-engineer (tool-cli-builder and tool-mcp-builder are its sub-skills).

Run T5 of docs/plans/012-board-and-backlog.md — the board verbs. Read the T5 block for the code.

READ, IN THIS ORDER:
  1. docs/plans/012-board-and-backlog.md — the T5 block, including the recorded deviation: the M2
     prompt puts the verbs in the contract commit; T1 landed the SURFACE DOC and this task lands
     the verbs, because folding them into T1 put that task at ~150k.
  2. docs/specs/2026-08-08-mcp-tool-and-command-surface.md — T1 wrote the rows you now implement.
     The document is the capability contract and it came first deliberately; you render it.
  3. packages/cli/src/reads.ts and writes.ts — M1's issue verbs, and `api.delWithBody`, which
     exists for the DELETE-with-a-body workflow endpoint and is the pattern for an unusual shape.
  4. packages/mcp/src/reads.ts, server.ts, instructions.ts.
  5. docs/specs/client-distribution.md — how these reach users (tarballs from /downloads, no
     registry), which is why `npm run pack:release` is part of the gate CI runs.

TRAPS THIS TASK WILL HIT:
  * `npm run build` AT THE PACKAGES ROOT, never `--workspaces`. The latter runs them
    ALPHABETICALLY, so `cli` typechecks against a stale `client` dist/ and the failure looks like a
    type error in code you did not write. The root script names the order client -> cli -> mcp.
  * `tcms board move` PRINTS the WIP breach and STILL EXITS 0. The move succeeded. A non-zero exit
    would make a breach fail somebody's script, which is story B3's rejected behaviour in its
    automation spelling.
  * `tcms rank` refuses --after with --before LOCALLY, without sending a request, so the message
    names the flags a person typed rather than a JSON field they never saw.
  * `citedFiles.test.ts` fails if a path named in ANY comment — packages/**, frontend/src/**,
    docs/specs/*.yaml, backend/src/** — is not on disk. A stale citation you add here reddens the
    packages job.
  * The MCP tool descriptions say what a tool is FOR, not what it calls. Add to instructions.ts the
    one sentence an assistant cannot infer: a board is a LENS, so moving a card changes the issue's
    status and every other board over that project shows the change.
  * THE SHAPE IS ALREADY DECIDED AND IT IS NOT ONE TOOL (D-j, and §3.5 of the surface doc, which
    T1 wrote). §2's rule splits the ten operations three ways: `tcms_read_board` (tool 27,
    `board:read`), `tcms_write_backlog` (tool 28, `board:rank` + `board:configure`), and TWO NEW
    ARMS on the existing `tcms_write_issue` — `move-card` and `label`, both `issue:edit`. Merging
    the write side into one tool is illegal: `Agent ∩ Test Engineer` holds `issue:edit` and neither
    of the other two, so the tool would be LISTED to a caller who 403s on two thirds of it.
  * THERE IS NO `tcms board create|edit|delete` ON EITHER SURFACE, deliberately (D-j). The eight
    CLI commands are exactly the ones §6's table names; do not add a ninth.
  * The label colour flag is `--color label-3` and the wire property is `color` (D-i). If you find
    `colorToken` anywhere, it is stale text this task should delete.

GATE (on shanks):
  ~/bin/tcms-remote sync
  ~/bin/tcms-remote packages npm run build
  ~/bin/tcms-remote packages 'npm run lint --workspaces && npm run test:run --workspaces'

COMMIT: `git add <paths>` only — packages/cli/src, packages/mcp/src, and the surface doc.

CLOSE per "Task hand-off protocol". Nothing downstream depends on this task's output; if you found
an endpoint that does not behave as T1's contract says, that is a T4 defect and T6's prompt needs
to know before it builds a client against the same shape.
````

### T6 — the fault switch, the queries, and the move logic

````text
You are working in the tcms repo, on the current branch. Read CLAUDE.md first, then run /fe-react
and fe-react-data-fetching. (The orchestrator is a COMMAND, not a skill — `Skill(fe-react:fe-react)`
returns "Unknown skill" is a documented trap and does NOT mean the plugins are unregistered.)

Run T6 of docs/plans/012-board-and-backlog.md — the fault switch, the queries and the move logic.
NO SCREEN RENDERS IN THIS TASK, deliberately: the fault switch lands BEFORE the feature it proves.

READ, IN THIS ORDER:
  1. docs/plans/012-board-and-backlog.md — D-a, D-b (why columnsFor stays), D-c (the optimistic
     split, and why it is not a contradiction), and the T6 block.
  2. docs/specs/2026-08-30-project-management-expansion-scope.md §12.4 — verbatim binding, and the
     reason the column move is NOT optimistic.
  3. frontend/src/features/board/cardMove.ts — ALL of it, including the header comment. You add a
     second entry point; you do NOT fork it and you do not change columnsFor or resolveCardMove.
  4. frontend/src/features/board/boardKeyboard.ts — same instruction.
  5. frontend/src/lib/devFaults.ts and frontend/src/api/client.ts — the seam in front of fetch.
  6. frontend/src/api/queries/keys.ts and frontend/src/api/queries/board.ts.
  7. CLAUDE.md's "Test-harness rules the suites enforce" — all four of them apply here.

TRAPS THIS TASK WILL HIT:
  * BOTH devFaults guards stay a LITERAL `import.meta.env.DEV`. Route the check through an imported
    constant and Rollup keeps the module in the production build — the fault console ships.
  * ONE `keys` factory, `src/api/queries/keys.ts`. NEVER add a second. While you are in it, rename
    `keys.workItems` to `keys.compositeBoard`: it names a table that has not existed since V4, and
    this task adds three keys with "board" in the name.
  * The new query module is `projectBoards.ts`, NOT `boards.ts`. `board.ts` already holds the
    COMPOSITE board's three reads — a different board, a different endpoint, a different card shape
    (D-a). Two modules one letter apart is how a wrong import survives review. The execution
    breakdown says `boards.ts`; it is wrong and this is the recorded correction.
  * `useMoveCard` IS NOT OPTIMISTIC and that is §12.4 verbatim. `useRankIssue` IS, with a snapshot
    rollback. These are different because legality of a STATUS change is on the card and checkable
    before the drag, and legality of a RANK change is the server's neighbours and is not.
  * NEVER build your own QueryClient in a test — only `createTestQueryClient`'s has the caches.
  * A test whose render trips an error boundary FAILS unless it calls `renderingIntoAnErrorBoundary()`
    / `drivingAFailedRead()` / `drivingAFailedWrite()` AND says why.
  * Every exchange crossing a fetch double is compared against openapi.yaml. Use
    frontend/src/test/fixtures.ts, and ANCHOR path-keyed mock arms (`/^\/boards\/[^/]+$/`) so a
    sub-resource is not caught by a prefix — /boards/{id} and /boards/{id}/cards are exactly the
    collision this rule exists for.
  * `npm run build` is `tsc -b` + vite build, NOT `tsc --noEmit` — the latter has reported 0 errors
    while the build failed with nine.
  * A new enum member goes in lib/labels.ts as `satisfies Record<Union, string>`, so M3 adding
    RELEASE to SwimlaneBy is a compile error until it is labelled. That is the mechanism, not an
    inconvenience.

GATE: ~/bin/tcms-remote sync && ~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'
  lint is oxlint at 0 warnings — a gate, not advisory.

COMMIT: `git add <paths>` only — devFaults, keys, projectBoards + its test, cardMove + its test,
boardKeyboard + its test, labels.ts, queries/index.ts.

OUT OF SCOPE: nothing renders. `?view=board` still shows M1's placeholder panel until T7.

CLOSE per "Task hand-off protocol". T7 and T8 both consume `resolveColumnMove` and `useRankIssue` —
if either signature came out different, fix both prompts before you stop.
````

### T7 — the project board

````text
You are working in the tcms repo, on the current branch. Read CLAUDE.md first, then run /fe-react,
fe-react-accessibility, fe-react-component-testing and fe-react-animation.

Run T7 of docs/plans/012-board-and-backlog.md — the project board. This is the hardest screen in
the whole expansion and the plan says so: swimlanes by epic, WIP breaches, keyboard parity with the
pointer, and a `⋮ Move to…` on every card.

READ, IN THIS ORDER:
  1. docs/mockups/work-board.html — THE SOURCE OF TRUTH FOR THE SCREEN. Walk it before writing a
     line. Open docs/mockups/index.html and see it in the flow. The mockups are static renders on
     the app's own tokens (assets/tcms-mock.css mirrors frontend/src/index.css), so this is a
     translation into the kit and TanStack Query, NOT a redesign.
  2. docs/plans/012-board-and-backlog.md — D-a, D-b, D-c, D-f (the four mockup elements that are
     deliberately NOT drawn), and the T7 block.
  3. frontend/src/features/board/BoardPage.tsx — the COMPOSITE board. Read it for how @dnd-kit is
     wired, where `announce` comes from, how a refusal is surfaced. DO NOT COPY IT into the new
     file; share the decision through cardMove.ts.
  4. frontend/src/features/tracker/WorkTablePage.tsx — especially VIEWS at line 70, whose `board`
     entry is the placeholder you replace, and the filter useMemo at ~line 148.
  5. frontend/src/features/board/cardMove.ts — T6's `resolveColumnMove` and `columnRefusalFor`.
  6. Story B3, and scope §12.2 ("design the card face deliberately rather than letting it accrete").

PRECEDENCE WHEN THE MOCKUP AND THE PLAN DISAGREE: on WHAT A SCREEN SHOWS — layout, copy, empty
state, the ⋮ Move to… and ↑ ↓ paths — the mockup wins. On WHAT THE SYSTEM DOES — permissions,
module boundaries, what is written where — the plan and the scope spec win. Record every
disagreement in the plan; never resolve one silently.

TRAPS THIS TASK WILL HIT:
  * FOUR THINGS THE MOCKUP DRAWS THAT YOU MUST NOT (D-f): the `AI` tag and the "AI-eligible" filter
    chip (issue.agent_eligible is M5), the `PR #482` badge (M5), and logged hours (M6). The mockup
    draws the finished product. Drawing them now needs fields nothing writes.
  * A column at exactly its limit is NOT in breach. `2 / 4` plain, `4 / 3 ⚠` warned, `—` for no
    limit. Three states, and the middle one is where the off-by-one lives.
  * EVERY card has a ⋮ Move to… — WCAG 2.2 Dragging Movements (2.5.7) and story B3's own
    non-functional requirement. The mockup says it in prose ON the board: "Dragging is never the
    only way", and names the second audience the criterion does not — this is also what a TOUCH
    user gets.
  * Illegal columns in the menu are DISABLED WITH THE REASON ON THEM, not hidden. A hidden control
    reads as "you may not", and a workflow with no In Progress -> Done transition is configuration,
    not a permission (§31 disable-don't-hide).
  * A collapsed lane KEEPS ITS PERCENTAGE. The mockup states it: "Nothing is hidden from the counts
    — only from the screen."
  * The "No epic" lane is LAST and is NOT an error — the mockup says "a story without an epic is a
    normal story, not an error". Do not style it as a warning.
  * Render the completion BASIS ("by estimate" / "by count · 4 of 10"). IssueCompletion.basis is on
    the wire deliberately; a percentage that changes meaning silently is D8's named failure, and
    two contradictory percentages on one screen is a defect this repo has shipped before.
  * `countsTowardCompletion: false` renders "not in %" — a Spike is on the board and out of the
    percentage, and a card that silently did not count makes the meter unexplainable.
  * TAILWIND UTILITIES ONLY. No `sx`, no CSS-in-JS, no second stylesheet. Preflight is on and is the
    only baseline, so a component sets its own type scale rather than inheriting one. Anything
    monospace goes through `Identifier`; enum labels through lib/labels.ts; the
    loading/loaded/failed/empty/stale matrix through `DataRegion`.
  * Chip rows WRAP and the board SCROLLS INSIDE ITSELF — the mockup, and the responsive rule: the
    page body must never scroll horizontally.
  * The keyboard test goes through the MENU, not through a simulated dnd-kit keyboard drag: the
    menu is the path a real keyboard user takes, and a jsdom drag asserts @dnd-kit's behaviour
    instead of yours.
  * Leave the Backlog, Timeline and Calendar placeholders in VIEWS alone. Backlog is T8's; Timeline
    and Calendar are M3's.

GATE: ~/bin/tcms-remote sync && ~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'

COMMIT: `git add <paths>` only — the five new components, the two test files, and WorkTablePage.tsx.

CLOSE per "Task hand-off protocol". T8 extracts `useWorkFilter` out of WorkTablePage and mounts
BacklogView beside your view — if you changed how the filter is read, or renamed anything in
ProjectBoardView that T8 imports, fix T8's prompt before you stop.
````

### T8 — the backlog and one filter

````text
You are working in the tcms repo, on the current branch. Read CLAUDE.md first, then run /fe-react,
fe-react-routing, fe-react-ui-rules and fe-react-component-testing.

Run T8 of docs/plans/012-board-and-backlog.md — the ranked backlog, and one filter across every
view. This is the task that makes D34 true on screen.

READ, IN THIS ORDER:
  1. docs/mockups/work-backlog.html — the source of truth for the screen. Note the panel line
     "drag the ⠿ handle, or use ↑ / ↓ — both persist", and that every row has BOTH.
  2. docs/mockups/work-table.html and work-board.html — the tabbed Work entry all three share, and
     the identical filter strip on each. One filter, in the URL, across every view.
  3. docs/plans/012-board-and-backlog.md — D-f (why there are no release sections), D-c (the rank
     drag is the one optimistic one), and the T8 block.
  4. frontend/src/features/tracker/WorkTablePage.tsx — the filter useMemo you are extracting. Its
     comment carries D34's reasoning; the comment MOVES WITH IT.
  5. frontend/src/api/queries/projectBoards.ts — T6's `useRankIssue`, with the optimistic patch and
     the snapshot rollback.
  6. Story B4, and scope D34.

TRAPS THIS TASK WILL HIT:
  * NO RELEASE SECTIONS AND NO "MOVE TO RELEASE" (D-f). The mockup groups rows under "2.4 · target
    18 Sep" and "Unscheduled"; `release` is M3's table and issue_fact.release_id is NULL until then,
    so the grouping would be one section called "Unscheduled" containing everything — which looks
    like a defect rather than an unbuilt feature. Bulk actions ship as Set epic and Assign only.
  * The filter lives in the URL and NOWHERE ELSE. Not useState, not a store: two views reading one
    filter cannot be a useState in either, and a store is server-state's filter living somewhere the
    URL cannot restore from a pasted link. `useSearchParams`, as everywhere in this SPA.
  * `view`, `sort`, `cols` and `boardId` are NOT part of the filter object. They are which view you
    are looking at, not which work — folding them in makes a filter that changes on a tab switch,
    which evicts every cached list on a tab switch.
  * The ↑ ↓ arrows end in the SAME mutation as the drag. An arrow taking a different path is the
    keyboard route quietly not getting the rollback — exactly the defect class "keyboard parity"
    exists to prevent, arriving through the fix for it.
  * Moving up = place BEFORE the row above; moving down = place AFTER the row below. NOT "swap
    ranks": a swap works for adjacent rows and silently does the wrong thing the first time
    somebody adds a multi-row move.
  * The row's number is `position` FROM THE SERVER, derived from rank order and never stored — a
    stored position is what the fractional rank exists to avoid.
  * Assert the shared filter through `window.location.search` AS WELL AS the screen. "Shareable as
    a URL" is the half a component test that only checked the chips would miss.
  * Tables scroll inside themselves (`overflow-x: auto` on their own container); the page body never
    scrolls horizontally.

GATE: ~/bin/tcms-remote sync && ~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'

COMMIT: `git add <paths>` only — BacklogView, useWorkFilter, their tests, ProjectBoardView.tsx and
WorkTablePage.tsx.

CLOSE per "Task hand-off protocol". T9 is the last task and carries the module close-out — if this
task deferred anything else to M3, add it to D-f so T9's rewrite of the M3 prompt picks it up.
````

### T9 — board settings and the close-out

````text
You are working in the tcms repo, on the current branch. Read CLAUDE.md first, then run /fe-react
and fe-react-component-testing.

Run T9 of docs/plans/012-board-and-backlog.md — board settings, and the close-out of module M2.
THIS IS THE LAST TASK IN THE MODULE and it carries the documentation, because this session is the
only one that knows what actually landed and what is built-but-unverified.

READ, IN THIS ORDER:
  1. docs/mockups/settings-boards.html — the source of truth. The coverage table at the bottom is
     the screen's point, not decoration.
  2. docs/plans/012-board-and-backlog.md — the T9 block, "Built and unverified", and every D-*
     decision, because you are about to write them into 009's prompts.
  3. docs/plans/009-project-management-expansion-modules.md — "Prompt hand-off protocol", and the
     M3 and M4 prompt blocks you rewrite.
  4. frontend/src/features/settings/ — the existing settings pages and their subnav.
  5. docs/features.md — the board section you rewrite.

TRAPS THIS TASK WILL HIT:
  * Add ONLY the Boards entry to the settings subnav. The mockup shows Repositories (M5) and
    Imports (M4) beside it; a nav link that leads nowhere is worse than an absent one.
  * The coverage table renders the invariant HOLDING and, when it does not, FAILING BY STATUS NAME,
    before the save is attempted — not as a 422 the reader has to decode. `ui-rules`: never render
    an action the backend will always reject.
  * The default-status control appears only on a column claiming MORE THAN ONE status. On a
    one-status column it has exactly one legal value; the mockup shows it only on the stakeholder
    board and says why in the team board's footer.
  * The last board's Delete is DISABLED WITH THE REASON, matching the server's 422 rather than
    discovering it.
  * Delete asks, and says a board is a view — "delete a board and no issue is lost". The
    destructive-sounding word is the one thing on this screen a person will hesitate over, and the
    answer is on the mockup.
  * docs/features.md: STRIKE THROUGH AND SUPERSEDE, never delete. The next reader must not think
    the old reasoning was never considered.
  * Say "built and unverified" where that is what it is. The gates prove it compiles, not that it
    works — this repository has shipped a no-op theme toggle, two contradictory percentages on one
    screen and a provider-ordering bug that rendered nothing but the error page, all green.

RUN ALL THREE GATES, because this is the last task:
  ~/bin/tcms-remote sync
  ~/bin/tcms-remote backend 'ulimit -n 65535; ./gradlew build'
  ~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'
  ~/bin/tcms-remote packages 'npm run build && npm run lint --workspaces && npm run test:run --workspaces'

THE 009 CLOSE-OUT — do every one of these, they are not optional:
  1. Status table: M2's row to `done YYYY-MM-DD @ <short sha>`.
  2. A `**Built and unverified:**` list under the M2 section — what the gates proved compiles and
     nobody has clicked.
  3. THE MIGRATION ACTUALLY TAKEN. `~~V4~~ → V5` in M2's row AND in its **db** line, struck through
     rather than replaced. THEN CORRECT EVERY ROW BELOW IT — M1 consumed V4 as well as V3, so
     M3 ~~V5~~→V6, M5 ~~V6~~→V7, M4 ~~V7~~→V8, Mc ~~V8~~→V9, M7 ~~V9~~→V10, M6 ~~V10~~→V11,
     M8 ~~V11~~→V12. Add a line to "The migration versions are a reservation, not a fact" saying
     this is the second time the column moved and the directory remains the authority.
  4. REWRITE THE M3 PROMPT: the endpoints as they landed; that SwimlaneBy gains RELEASE and
     lib/labels.ts makes that a compile error until labelled; that the backlog's release grouping
     and the "Move to release" bulk action are M3's to add to BacklogView.tsx; that issue.rank is
     project-wide, so a release-filtered backlog is a filter over ONE order and not a second one;
     and the V it takes. AMEND THE M4 PROMPT: issue_label now exists, so the Jira importer has
     somewhere to put labels and label-per-sprint is on offer as an import mapping. M4 depends on
     M1, so that one is a courtesy edit — say so in the commit message.
  5. CORRECT THE TWO FALSE CLAIMS IN THE M2 PROMPT ITSELF, in place, no changelog: the migration is
     V5 not V4, and the derived-card refusal is already repaired and pinned by cardMove.test.ts:153
     — it was never M2's to restore.
  6. CARRY D-k AND D-l INTO 009's M2 SECTION, because both are schema facts a later module will
     meet and neither is in 009 today: `issue.rank` is filled by the `issue_rank_default` trigger
     rather than by a service or a column default, and `board_follows_status_removal` is what keeps
     a board from vetoing a workflow edit. M3 adds a release and M4 imports labels; both touch
     tables these two triggers sit on.
  7. SIX `FlakeScoreTest` FAILURES ARE NOT M2's. They were red on master in the 2026-08-31 nightly,
     before T1. Say so in the M2 built-and-unverified list rather than letting the next module's
     first gate read them as inherited breakage — and do NOT report M2 as leaving a green backend
     suite if they are still there.
  8. Commit 009 in its OWN commit, `docs(plan): M2 done`, staging only that file.

THREE COMMITS, in this order: the code + features.md + the surface doc; then this plan's
built-and-unverified list; then 009. `git add <paths>` only, never -A.
````

---

## T1 — the board contract

**Deliverable:** Ten new operations exist in `docs/specs/openapi.yaml`, every schema carries its
`required` array including on inline nested objects; `frontend/src/api/schema.d.ts` and
`packages/client/src/schema.ts` are regenerated from it in the same commit; `contractPairing.test.ts`
and `packages/client` tests are green.
**Landed:** `eb10bc07` — with two names changed from the YAML below, both recorded as **D-i**.
**Stack:** `ts`
**Est. context:** ~95k — 1 large spec file modified (17k lines, ~600 added), 2 generated files, 2
contract-body copies checked, 1 surface doc, 3 spec sections cited, 2 skills
**Depends on:** none

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Regenerate: `frontend/src/api/schema.d.ts`, `packages/client/src/schema.ts`
- Modify if and only if new walker machinery is needed: `frontend/src/api/contractBodies.ts`,
  `packages/client/src/test/contractBodies.ts` (the `SHARED REGION` is generic — adding endpoints
  usually needs **no** edit here; `contractPairing.test.ts` is the gate either way)
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

- [x] **Step 1: Read the neighbouring contract before adding to it**

Read `docs/specs/openapi.yaml` lines **8268–8800** — `/board`, `/board/annotations/…`, `/issues`,
`/issues/{key}`, `/issues/{key}/transitions`, `/projects/{projectId}/workflow`. Every new operation
below sits in the `tracker` tag beside these and copies their conventions: a `description` that says
*why*, a 404 that does not distinguish absent-from-forbidden, and no `parameters` block on the path
item where a query parameter would be inherited by operations that must not have it (§50).

- [x] **Step 2: Add the six board-configuration schemas**

Append to `components.schemas`. **Every one carries `required`, and so does every inline object** —
`required` does not descend, and without it every generated property is optional.

```yaml
    BoardSummary:
      type: object
      required: [id, name, columnCount, swimlaneBy]
      properties:
        id: { type: string, format: uuid }
        name: { type: string }
        columnCount: { type: integer }
        swimlaneBy: { $ref: '#/components/schemas/SwimlaneBy' }

    SwimlaneBy:
      type: string
      description: |
        Story B3's grouping axis. `RELEASE` is **absent until M3** — a member whose data does not
        exist would be a grouping that silently yields one lane called "None", which reads as a
        broken board rather than an unbuilt feature.
      enum: [NONE, EPIC, ASSIGNEE, LABEL, PRIORITY]

    Board:
      type: object
      required: [id, projectId, name, swimlaneBy, columns]
      properties:
        id: { type: string, format: uuid }
        projectId: { type: string, format: uuid }
        name: { type: string }
        swimlaneBy: { $ref: '#/components/schemas/SwimlaneBy' }
        columns:
          type: array
          items: { $ref: '#/components/schemas/BoardColumn' }

    BoardColumn:
      type: object
      required: [id, name, position, statuses, defaultStatusId]
      properties:
        id: { type: string, format: uuid }
        name: { type: string }
        position: { type: integer }
        wipLimit:
          type: integer
          nullable: true
          description: Null is "no limit", which is not the same as zero.
        statuses:
          type: array
          description: |
            Every status this column claims, in workflow order. **Never empty** — a column claiming
            nothing is a column no card can ever be in, and the service refuses to create one.
          items: { $ref: '#/components/schemas/IssueStatusRef' }
        defaultStatusId:
          type: string
          format: uuid
          description: |
            D2: a card dropped into a column claiming more than one status takes **this** one.
            Always one of `statuses[].id`, enforced server-side.

    BoardInput:
      type: object
      required: [name, swimlaneBy, columns]
      properties:
        name: { type: string, minLength: 1, maxLength: 80 }
        swimlaneBy: { $ref: '#/components/schemas/SwimlaneBy' }
        columns:
          type: array
          minItems: 1
          items: { $ref: '#/components/schemas/BoardColumnInput' }

    BoardColumnInput:
      type: object
      required: [name, statusIds, defaultStatusId]
      properties:
        id:
          type: string
          format: uuid
          nullable: true
          description: Present to edit an existing column, absent to create one. Position is the
            array index, so reordering columns is reordering this array.
        name: { type: string, minLength: 1, maxLength: 40 }
        wipLimit: { type: integer, nullable: true, minimum: 1 }
        statusIds:
          type: array
          minItems: 1
          items: { type: string, format: uuid }
        defaultStatusId: { type: string, format: uuid }
```

- [x] **Step 3: Add the card, move, backlog, rank and label schemas**

```yaml
    BoardContents:
      type: object
      required: [boardId, swimlaneBy, lanes, breaches]
      properties:
        boardId: { type: string, format: uuid }
        swimlaneBy: { $ref: '#/components/schemas/SwimlaneBy' }
        lanes:
          type: array
          description: |
            One entry when `swimlaneBy` is `NONE`, whose `key` is null. The "no epic" lane is a
            real lane with a null `key` and is **last**, because a story without an epic is a
            normal story rather than an error (work-board.html says so on the screen).
          items: { $ref: '#/components/schemas/BoardLane' }
        breaches:
          type: array
          description: |
            The columns over their WIP limit **right now**, across every lane. Reported, never
            enforced — story B3: "the drop is accepted and the column header shows the breach".
            An empty array is the normal case.
          items: { $ref: '#/components/schemas/WipBreach' }

    BoardLane:
      type: object
      required: [key, label, cards, completion]
      properties:
        key:
          type: string
          nullable: true
          description: The epic key, assignee id, label id or priority — null for the catch-all lane.
        label: { type: string }
        cards:
          type: array
          items: { $ref: '#/components/schemas/BoardIssueCard' }
        completion:
          $ref: '#/components/schemas/IssueCompletion'
          description: |
            The lane's roll-up, so collapsing it to one card keeps its percentage (B3). Reuses M1's
            `IssueCompletion` — done over the counted total, with its **basis on the wire** (D8), so
            a lane header can say "by estimate" or "by count" as work-board.html does.

    WipBreach:
      type: object
      required: [columnId, limit, count]
      properties:
        columnId: { type: string, format: uuid }
        limit: { type: integer }
        count: { type: integer }

    BoardIssueCard:
      type: object
      required:
        [key, title, typeName, typeLevel, iconCode, countsTowardCompletion, columnId, status,
         rank, labels, allowedTransitions, subtaskTotal, subtaskDone]
      description: |
        **Not `BoardCard`.** That schema is `work`'s composite card and carries four `kind`s, a
        `derived` flag and a synthetic id. Every card here is an issue with a real key, so the
        fields that exist only to describe a derived card would be constant and misleading.
      properties:
        key: { type: string, description: 'The issue key — `ACME-124`.' }
        title: { type: string }
        typeName: { type: string }
        typeLevel: { $ref: '#/components/schemas/IssueLevel' }
        iconCode: { type: string }
        countsTowardCompletion:
          type: boolean
          description: False for a Spike, which the card face marks "not in %".
        columnId: { type: string, format: uuid }
        status: { $ref: '#/components/schemas/IssueStatusRef' }
        rank: { type: string, description: 'Decimal, as a string — see `RankResult.rank`.' }
        priority: { $ref: '#/components/schemas/Priority' }
        assignee: { $ref: '#/components/schemas/UserRef' }
        dueDate: { type: string, format: date, nullable: true }
        overdue: { type: boolean }
        estimateHours: { type: number, nullable: true }
        epicKey: { type: string, nullable: true }
        labels:
          type: array
          items: { $ref: '#/components/schemas/Label' }
        allowedTransitions:
          type: array
          description: |
            §12.4, verbatim and load-bearing: the legal set must be known **client-side before the
            drag starts**, so a forbidden drop is refused at the drag with the reason rather than
            accepted and reverted. Empty for an Epic (D31).
          items: { $ref: '#/components/schemas/IssueStatusRef' }
        subtaskTotal: { type: integer }
        subtaskDone: { type: integer, description: 'The card face reads "3/5 sub-tasks".' }

    CardMoveInput:
      type: object
      required: [toColumnId]
      properties:
        toColumnId: { type: string, format: uuid }

    CardMoveResult:
      type: object
      required: [card]
      properties:
        card: { $ref: '#/components/schemas/BoardIssueCard' }
        breach:
          $ref: '#/components/schemas/WipBreach'
          description: |
            Present when the move put the target column over its limit. **The move still happened**
            — this is a 200 carrying a fact, never a 4xx. A limit that refused drops would be a
            limit people route around by not using the board.

    BacklogRow:
      type: object
      required: [key, title, typeName, typeLevel, iconCode, position, rank, status, labels]
      properties:
        key: { type: string }
        title: { type: string }
        typeName: { type: string }
        typeLevel: { $ref: '#/components/schemas/IssueLevel' }
        iconCode: { type: string }
        position: { type: integer, description: 'The 1-based number the row shows. Derived from
          rank order, never stored.' }
        rank: { type: string }
        status: { $ref: '#/components/schemas/IssueStatusRef' }
        assignee: { $ref: '#/components/schemas/UserRef' }
        estimateHours: { type: number, nullable: true }
        epicKey: { type: string, nullable: true }
        labels:
          type: array
          items: { $ref: '#/components/schemas/Label' }

    RankInput:
      type: object
      description: |
        **Neighbour-relative, never an absolute rank.** Two people dragging at once each compute a
        midpoint from a list they read seconds ago; sending the number races, sending the
        neighbours does not. Exactly one of the two is required — both, or neither, is a 400.
      properties:
        afterKey:
          type: string
          nullable: true
          description: Place immediately after this issue. Null with `beforeKey` absent means "to
            the top".
        beforeKey:
          type: string
          nullable: true
          description: Place immediately before this issue.

    RankResult:
      type: object
      required: [key, rank, rebalanced]
      properties:
        key: { type: string }
        rank:
          type: string
          description: |
            A decimal **as a string**, not a `number`. `NUMERIC` is arbitrary precision and IEEE-754
            doubles are not: JSON.parse would silently round a rank the server distinguishes, and
            two cards would collide after a few hundred midpoint inserts. The client sorts on it and
            never does arithmetic with it.
        rebalanced:
          type: boolean
          description: |
            True when the gap ran out of precision and the project's ranks were respaced (D-d). The
            client refetches the list rather than trusting its own order.

    Label:
      type: object
      required: [id, name, color]
      properties:
        id: { type: string, format: uuid }
        name: { type: string }
        color:
          type: string
          description: |
            A design-token name (`label-1` … `label-10`), not a hex value. Status and label colour
            is data the palette owns, and a stored `#RRGGBB` is invisible in the other colour scheme
            — the same argument §6u already makes for status colours.

    LabelInput:
      type: object
      required: [name, color]
      properties:
        name: { type: string, minLength: 1, maxLength: 32 }
        color: { type: string }

    IssueLabelsInput:
      type: object
      required: [labelIds]
      properties:
        labelIds:
          type: array
          items: { type: string, format: uuid }
```

- [x] **Step 4: Add the ten operations**

Placed after `/projects/{projectId}/workflow/statuses/{statusId}`, in the `tracker` tag.

```yaml
  /projects/{projectId}/boards:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
    get:
      tags: [tracker]
      summary: The project's boards
      operationId: listBoards
      description: |
        Every board over this project, in name order. `board:read`, which every role holds — a board
        is a lens over work the caller can already see, so there is nothing here a project grant
        does not already imply.
      responses:
        '200':
          description: Boards. Never empty — `tcms_seed_project_defaults` gives every project one.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/BoardSummary' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
    post:
      tags: [tracker]
      summary: Create a board
      operationId: createBoard
      description: |
        `board:configure`. **Every status the project has must be claimed by exactly one column**
        (story B2) — the request is refused with 422 when a status is claimed twice or left
        unclaimed, naming which. Postgres has no cross-table CHECK, so this is a unique index on
        `board_column_status (board_id, status_id)` for the "twice" half plus this rule for the
        "not at all" half.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/BoardInput' }
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Board' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '422':
          description: A status is claimed twice, or left unclaimed, and the body names which.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }

  /boards/{boardId}:
    parameters:
      - { name: boardId, in: path, required: true, schema: { type: string, format: uuid } }
    get:
      tags: [tracker]
      summary: A board's columns and swimlane setting
      operationId: getBoardConfig
      description: |
        `getBoardConfig`, NOT `getBoard` — `GET /board` already owns that operationId (D-i).
        Configuration only — the cards are `GET /boards/{boardId}/cards` (D-h). Two reads because
        they have two lifetimes: columns change when somebody edits them, cards on every transition
        in the project, and one combined read would relayout the board on every card move.
      responses:
        '200':
          description: The board
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Board' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
    patch:
      tags: [tracker]
      summary: Rename a board, or redefine its columns
      operationId: updateBoard
      description: |
        `board:configure`. The `columns` array is a **replacement**, not a patch: a column with an
        `id` is kept and updated, one without is created, and one whose id is absent from the array
        is deleted. Position is the array index. Same 422 as create — every status claimed exactly
        once, and issues in a deleted column are **not** touched, because a column never owned them.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/BoardInput' }
      responses:
        '200':
          description: Updated
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Board' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '422':
          description: A status is claimed twice, or left unclaimed, and the body names which.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }
    delete:
      tags: [tracker]
      summary: Delete a board
      operationId: deleteBoard
      description: |
        `board:configure`. **No issue is lost** (story B2) — a board is a view and owned nothing.
        Refused with 422 when it is the project's last board: a project with no board cannot render
        `?view=board` at all, and an empty board picker is a dead end rather than a state.
      responses:
        '204': { description: Deleted }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '422':
          description: It is the project's last board.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }

  /boards/{boardId}/cards:
    parameters:
      - { name: boardId, in: path, required: true, schema: { type: string, format: uuid } }
    get:
      tags: [tracker]
      summary: The cards on a board, in lanes
      operationId: getBoardCards
      description: |
        The filter is D34's, and it is the **same query parameters the Table view sends** — one
        filter, five views, held in the URL. Semantics are Trello's and are worth copying exactly
        because they are learnable: **OR within a category, AND across categories** (§12.2). So
        `labelId=a&labelId=b&assigneeId=c` is *(a or b) and c*.
      parameters:
        - { name: labelId, in: query, schema: { type: array, items: { type: string, format: uuid } } }
        - { name: assigneeId, in: query, schema: { type: array, items: { type: string, format: uuid } } }
        - { name: epicKey, in: query, schema: { type: string } }
        - { name: level, in: query, schema: { $ref: '#/components/schemas/IssueLevel' } }
        - { name: priority, in: query, schema: { type: array, items: { $ref: '#/components/schemas/Priority' } } }
        - name: unparented
          in: query
          schema: { type: boolean }
          description: Only issues with no epic — the "No epic" lane, asked for on its own.
      responses:
        '200':
          description: Cards
          content:
            application/json:
              schema: { $ref: '#/components/schemas/BoardContents' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }

  /boards/{boardId}/cards/{key}/move:
    parameters:
      - { name: boardId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: key, in: path, required: true, schema: { type: string } }
    post:
      tags: [tracker]
      summary: Move a card to a column
      operationId: moveBoardCard
      description: |
        Costs **`issue:edit`, not a `board:move`** — §A3 is explicit that dragging a card changes
        the issue's status and that a second key for the same act is a permission somebody could
        hold without being able to use it.

        This is not `POST /issues/{key}/transitions` with extra steps. It does two things that one
        cannot: it resolves the **column's default status** (D2 — a stakeholder board's "In flight"
        claims three statuses and a drop into it must mean one of them), and it reports the WIP
        breach the move may have caused. A client doing this itself would have to know the column
        mapping to pick a status, which is the coupling the endpoint exists to remove.

        A move the workflow forbids is **409**, and its body names the legal targets — the same
        shape `/issues/{key}/transitions` answers with. In an ordinary session it is unreachable,
        because the picker only offers legal moves; `tcmsFaults.issue('conflict')` is how it is
        exercised.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/CardMoveInput' }
      responses:
        '200':
          description: |
            Moved. A `breach` here means the column is over its limit **and the card is in it** —
            story B3's "the drop is accepted and the column header shows the breach".
          content:
            application/json:
              schema: { $ref: '#/components/schemas/CardMoveResult' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }

  /projects/{projectId}/backlog:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
    get:
      tags: [tracker]
      summary: The project's backlog, in rank order
      operationId: getBacklog
      description: |
        Every issue at **STORY or TASK level** that is not `DONE`, in rank order — an Epic is not a
        backlog row (its status is derived from its stories, D31) and a done issue is not "what's
        next". Takes the same filter as `GET /boards/{boardId}/cards`.

        **Not grouped by release in M2.** `work-backlog.html` shows a "2.4 · target 18 Sep" section
        and an "Unscheduled" one; `release` is M3's table, so this returns one list and M3 adds the
        grouping (D-f).
      parameters:
        - { name: labelId, in: query, schema: { type: array, items: { type: string, format: uuid } } }
        - { name: assigneeId, in: query, schema: { type: array, items: { type: string, format: uuid } } }
        - { name: epicKey, in: query, schema: { type: string } }
        - { name: level, in: query, schema: { $ref: '#/components/schemas/IssueLevel' } }
        - { name: priority, in: query, schema: { type: array, items: { $ref: '#/components/schemas/Priority' } } }
        - { name: unparented, in: query, schema: { type: boolean } }
      responses:
        '200':
          description: Rows in rank order. An empty array is a project with nothing left to do.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/BacklogRow' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }

  /issues/{key}/rank:
    parameters:
      - { name: key, in: path, required: true, schema: { type: string } }
    put:
      tags: [tracker]
      summary: Place an issue between two neighbours
      operationId: rankIssue
      description: |
        `board:rank`. **The order persists for everyone** (story B4) — this is not a per-viewer sort.

        `PUT` rather than `PATCH` because it is idempotent in the sense that matters: sending the
        same neighbours twice lands the issue in the same place, whatever its rank was before.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RankInput' }
      responses:
        '200':
          description: Placed
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RankResult' }
        '400':
          description: Both neighbours given, or a neighbour in another project.
          $ref: '#/components/responses/BadRequest'
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409':
          description: |
            A neighbour moved between the read and the write. The client refetches — this is the
            failure `tcmsFaults.rank('conflict')` fabricates, and the one the optimistic rank drag
            snaps back from (D-c).
          $ref: '#/components/responses/Conflict'

  /projects/{projectId}/labels:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
    get:
      tags: [tracker]
      summary: The project's labels
      operationId: listLabels
      description: |
        Labels are the **informal, fast-moving** axis and are deliberately not a classification
        dimension — cases have `classification_dimension` and `case_tag` for taxonomy (§12.2).
      responses:
        '200':
          description: Labels, in name order
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/Label' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
    post:
      tags: [tracker]
      summary: Create a label
      operationId: createLabel
      description: |
        **`board:configure`** — creating a label is project configuration, beside columns and WIP
        limits, which is what that key's own description already says. There is no `label:*` in the
        catalogue and this module does not add one (D-e).
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/LabelInput' }
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Label' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }

  /labels/{labelId}:
    parameters:
      - { name: labelId, in: path, required: true, schema: { type: string, format: uuid } }
    patch:
      tags: [tracker]
      summary: Rename or recolour a label
      operationId: updateLabel
      description: '`board:configure` (D-e).'
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/LabelInput' }
      responses:
        '200':
          description: Updated
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Label' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }
    delete:
      tags: [tracker]
      summary: Delete a label
      operationId: deleteLabel
      description: |
        `board:configure`. Removes it from every issue holding it — an informal axis whose deletion
        needed a migration plan would not be informal. No confirmation state on the wire; the SPA
        asks, and says how many issues carry it.
      responses:
        '204': { description: Deleted }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /issues/{key}/labels:
    parameters:
      - { name: key, in: path, required: true, schema: { type: string } }
    put:
      tags: [tracker]
      summary: Replace an issue's labels
      operationId: putIssueLabels
      description: |
        **`issue:edit`** — putting a label on an issue edits the issue (D-e). A replacement rather
        than add/remove pairs: the SPA's control is a multi-select whose state is the whole set, and
        two endpoints for it would let a stale client remove a label somebody else had just added.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/IssueLabelsInput' }
      responses:
        '200':
          description: The issue's labels after the write
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/Label' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
```

- [x] **Step 5: Verify every `required` array is present, by script rather than by reading**

CLAUDE.md: *"A schema-wide or catalogue-wide invariant needs a computed check, not a careful
reading."* Reading V1 top to bottom missed five join tables; scripting it found them.

Run:
```bash
cd ~/RiderProjects/tcms
python3 - <<'PY'
import yaml, sys
d = yaml.safe_load(open('docs/specs/openapi.yaml'))
bad = []
def walk(node, where):
    if not isinstance(node, dict): return
    if node.get('type') == 'object' and 'properties' in node and 'required' not in node:
        bad.append(where)
    for k, v in node.items():
        if isinstance(v, dict): walk(v, f'{where}.{k}')
        elif isinstance(v, list):
            for i, item in enumerate(v): walk(item, f'{where}[{i}]')
for name, s in d['components']['schemas'].items():
    walk(s, name)
walk(d['paths'], 'paths')
print('\n'.join(bad) if bad else 'every object schema has required')
sys.exit(1 if bad else 0)
PY
```
Expected: `every object schema has required`. Any line printed is an inline object that needs one —
fix it and re-run. Pre-existing offenders outside the schemas this task added are **not** this
task's to fix; note them and move on.

- [x] **Step 6: Regenerate both clients**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

Both files are generated — **do not hand-edit either**. If `git diff` shows a change to a schema
this task did not touch, the generator version moved; stop and say so rather than committing it.

- [x] **Step 7: Run the packages gate on shanks**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run test:run --workspaces
```
Expected: green, `contractPairing.test.ts` included. `citedFiles.test.ts` fails if a path named in a
comment is not on disk — this task names `frontend/src/lib/devFaults.ts` and
`frontend/src/api/contractBodies.ts`, both of which exist.

- [x] **Step 8: Add the board verbs to the surface document**

In `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`, beside M1's issue verbs, add the rows
T5 will implement: `board list`, `board show`, `board move`, `backlog`, `rank`, `label list`,
`label add`, `label set`. **The document is written before the code** deliberately — it is the
capability contract, and T5 renders it.

- [x] **Step 9: Commit**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts \
        docs/specs/2026-08-08-mcp-tool-and-command-surface.md
git commit -m "feat(contract): boards, backlog rank and labels

Ten operations for 009 M2. Every schema carries its required array,
inline objects included, verified by script rather than by reading.
Both generated clients move in this commit."
```

---

## T2 — `V5` board schema

**Deliverable:** `V5__tracker_board.sql` exists and applies; `board`, `board_column`,
`board_column_status`, `board_swimlane`, `issue_label` and `issue_label_assignment` are created with
forced RLS; `issue.rank` exists and every existing issue holds one; `tcms_seed_project_defaults`
gives every project a six-column default board; `SchemaConventionTest` is green.
**Landed:** `94b3e5ae`
**Stack:** `data`
**Est. context:** ~95k — 1 migration created (~400 lines), 1 schema test created, `V3`'s 170-line
seed function read in full and reproduced, `SchemaConventionTest` read, the ERD modified, 2 skills
**Depends on:** T1 (the contract the tables must be able to serve)

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__tracker_board.sql`
- Create: `backend/src/test/java/com/tcms/tracker/board/BoardSchemaTest.java`
- Modify: `backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java` (the forced-RLS policy
  count) — **`tenancy`, not `schema`**, which is where this plan first looked
- Modify: `docs/specs/2026-07-30-tcms-erd.md`

- [x] **Step 1: Confirm the version, from the directory rather than from any table**

```bash
ls backend/src/main/resources/db/migration/
```
Expected: `V1__initial_schema.sql  V2__pm_foundation.sql  V3__tracker_core.sql  V4__retire_work_item.sql`

**The file you write is `V5`.** The 009 table reserves `V4` for this module and is wrong — M1 took
two versions. `out-of-order` is unset in `application.yml`, so a `V5`-numbered file is the only one
that will ever run on an instance that has M1. **If this listing shows a `V5` already**, somebody
else took it; take the next free one and say so in the hand-off.

- [x] **Step 2: Write the tables**

```sql
-- =============================================================================
-- V5 — the board (plan 012, module M2 of 009)
--
-- Six tables and one column. What a person can do afterwards that they could
-- not before: work a board with real columns, rank a backlog, and label an issue.
--
-- THE VERSION IS V5, NOT THE V4 THE 009 TABLE RESERVES. M1 took two versions —
-- V3 created `issue` and V4 dropped `work_item`, which could only land in the
-- commit that deleted the @Entity, because ddl-auto: validate refuses to boot a
-- context whose entity has no table. `out-of-order` is unset, so a V4 written
-- here would be silently skipped on every instance that ran M1, and
-- Testcontainers builds from empty and would never show it.
--
-- A BOARD IS A LENS, NOT A CONTAINER (scope §12.1). Nothing below has a foreign
-- key FROM `issue` TO a board, and that is the whole design: an issue appears on
-- every board whose filter matches it, at the column claiming its status.
-- Dropping a board loses no work because no board ever owned any. The one thing
-- an issue does gain here is `rank`, which is project-wide and not per-board —
-- two boards over one project cannot disagree about what is next, for the same
-- reason D2 gives for one canonical status.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. BOARDS AND THEIR COLUMNS
-- -----------------------------------------------------------------------------

CREATE TABLE board (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    project_id   UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,
    -- RELEASE is deliberately absent until M3. A swimlane axis whose column is
    -- NULL for every row renders one lane called "None", which reads as a broken
    -- board rather than as an unbuilt feature.
    swimlane_by  TEXT        NOT NULL DEFAULT 'NONE'
                 CHECK (swimlane_by IN ('NONE','EPIC','ASSIGNEE','LABEL','PRIORITY')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_board_name ON board (company_id, project_id, lower(name));
CREATE INDEX ix_board_project ON board (company_id, project_id);

CREATE TABLE board_column (
    id         UUID    PRIMARY KEY,
    company_id UUID    NOT NULL REFERENCES company(id),
    board_id   UUID    NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    name       TEXT    NOT NULL,
    position   INTEGER NOT NULL,
    -- NULL is "no limit", and it is NOT zero. A zero limit would mean a column
    -- permanently in breach, which the header would then shout about forever.
    wip_limit  INTEGER CHECK (wip_limit IS NULL OR wip_limit >= 1),
    -- The status a drop into this column MEANS (D2). NOT NULL: a column whose
    -- default is unset is a column a drag cannot resolve, and "pick one at
    -- random" is how a stakeholder board silently sets Blocked.
    default_status_id UUID NOT NULL REFERENCES issue_status(id)
);
CREATE UNIQUE INDEX uq_board_column_position ON board_column (company_id, board_id, position);
CREATE INDEX ix_board_column_board ON board_column (company_id, board_id);

-- THE CLAIM TABLE, AND THE HALF OF THE RULE POSTGRES CAN ENFORCE.
--
-- "Every status is claimed by exactly one column" (story B2, review R13) is two
-- statements and only one of them is expressible here:
--
--   * "at most one"  -> uq_board_column_status below, a real unique index.
--   * "at least one" -> a service rule. It is a cross-TABLE assertion (every row
--     of issue_status has a matching row here, per board) and Postgres has no
--     cross-table CHECK. A trigger was considered and refused: it would fire
--     mid-transaction while a board is being rebuilt column by column, so the
--     legal intermediate state of a PATCH would be rejected.
--
-- board_id is denormalised onto this row so the unique index can exist at all —
-- without it the constraint would have to span board_column, which a unique
-- index cannot do.
CREATE TABLE board_column_status (
    company_id UUID NOT NULL REFERENCES company(id),
    board_id   UUID NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    column_id  UUID NOT NULL REFERENCES board_column(id) ON DELETE CASCADE,
    status_id  UUID NOT NULL REFERENCES issue_status(id) ON DELETE CASCADE,
    PRIMARY KEY (company_id, column_id, status_id)
);
CREATE UNIQUE INDEX uq_board_column_status ON board_column_status (company_id, board_id, status_id);
CREATE INDEX ix_board_column_status_column ON board_column_status (company_id, column_id);

-- Per-board swimlane state that belongs to a PERSON, not to the board: which
-- lanes this viewer has collapsed. Separate from board.swimlane_by, which is the
-- board's own configuration, for derived_item_annotation's reason — collapsing a
-- lane is not editing the board, and a shared collapse state would mean one
-- person's tidying hides work from everybody else.
CREATE TABLE board_swimlane (
    company_id  UUID    NOT NULL REFERENCES company(id),
    board_id    UUID    NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    user_id     UUID    NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    lane_key    TEXT    NOT NULL,
    collapsed   BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (company_id, board_id, user_id, lane_key)
);

-- -----------------------------------------------------------------------------
-- 2. LABELS
-- -----------------------------------------------------------------------------
--
-- Trello's primary scanning device (§12.2), and DELIBERATELY NOT a taxonomy.
-- This project already has classification_dimension and case_tag for controlled
-- vocabulary; a label is the informal, fast-moving axis, which is why it is
-- project-scoped, needs no dimension, and is deleted without a migration plan.
--
-- color holds a DESIGN-TOKEN NAME, never a hex value. Status and label colour is
-- data the palette owns (§6u) — a stored #RRGGBB is invisible in the other
-- colour scheme, and this codebase swaps the whole palette on [data-color-scheme].

CREATE TABLE issue_label (
    id          UUID        PRIMARY KEY,
    company_id  UUID        NOT NULL REFERENCES company(id),
    project_id  UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name        TEXT        NOT NULL,
    color      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID
);
CREATE UNIQUE INDEX uq_issue_label_name ON issue_label (company_id, project_id, lower(name));

CREATE TABLE issue_label_assignment (
    company_id UUID        NOT NULL REFERENCES company(id),
    issue_id   UUID        NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    label_id   UUID        NOT NULL REFERENCES issue_label(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, issue_id, label_id)
);
CREATE INDEX ix_issue_label_assignment_label ON issue_label_assignment (company_id, label_id);

-- -----------------------------------------------------------------------------
-- 3. THE FRACTIONAL RANK
-- -----------------------------------------------------------------------------
--
-- NUMERIC WITHOUT A PRECISION, which in Postgres is arbitrary precision. That is
-- the whole point: a midpoint between two neighbours ALWAYS exists, so inserting
-- one row NEVER rewrites another — which an INTEGER position column cannot say,
-- and rewriting every row below the drop is how a backlog drag becomes a
-- thousand-row UPDATE that two people cannot do at once.
--
-- Growth is real and bounded by the service, not by the type: RankService
-- respaces the project at 1024, 2048, 3072 … when a midpoint would need more
-- than 12 fractional digits. LexoRank was considered and refused — same
-- unbounded growth, plus a string-collation dependency nothing else here has.
--
-- NOT NULL with a backfill, rather than nullable: a NULL rank sorts to one end
-- and the backlog would open with every pre-upgrade issue in a block, in
-- creation order, looking ranked when nobody ranked it.
ALTER TABLE issue ADD COLUMN rank NUMERIC;

-- THE BACKFILL IS A LOOP OVER COMPANY, and the bare UPDATE this plan first wrote
-- is the trap V3's own header names one table over. `issue` has carried FORCE ROW
-- LEVEL SECURITY since V3 and Flyway connects as tcms_owner, which holds no
-- BYPASSRLS — so a single UPDATE with app.current_company unset matches ZERO ROWS
-- and reports success. It would not even fail loudly afterwards on a database
-- whose `issue` table is empty, which every Testcontainers run is: the SET NOT
-- NULL below would pass and the defect would first appear on the one instance
-- that has data.
DO $$
DECLARE
    c UUID;
BEGIN
    FOR c IN SELECT id FROM company LOOP
        PERFORM set_config('app.current_company', c::text, true);
        UPDATE issue i
           SET rank = ranked.seq * 1024
          FROM (
            SELECT id, row_number() OVER (PARTITION BY project_id ORDER BY created_at, id) AS seq
              FROM issue
          ) ranked
         WHERE i.id = ranked.id AND i.rank IS NULL;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END $$;

ALTER TABLE issue ALTER COLUMN rank SET NOT NULL;

-- AND THE TRIGGER THAT MAKES `NOT NULL` SURVIVABLE TWO TASKS BEFORE RankService
-- (D-k). T2 does not remap `Issue`, so every insert Hibernate makes omits `rank`;
-- a column DEFAULT cannot read another row, and a constant one would make every
-- new issue tie with every other. Ties under concurrency are accepted and
-- created_at breaks them — nothing here claims rank is unique.
CREATE FUNCTION tcms_issue_rank_default() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.rank IS NULL THEN
        SELECT coalesce(max(i.rank), 0) + 1024 INTO NEW.rank
          FROM issue i
         WHERE i.company_id = NEW.company_id AND i.project_id = NEW.project_id;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER issue_rank_default
    BEFORE INSERT ON issue
    FOR EACH ROW EXECUTE FUNCTION tcms_issue_rank_default();

-- Leading company_id, as every composite index in this schema does — the RLS
-- predicate is on it and an index that does not lead with it is not used by the
-- filtered scan. SchemaConventionTest checks this mechanically.
CREATE INDEX ix_issue_rank ON issue (company_id, project_id, rank);
```

- [x] **Step 3: Add the RLS block**

Every one of the six tables is company-owned, so every one takes the full treatment. `V1`'s block is
the pattern; copy its exact shape — **and it is not the shape this plan first wrote.**
`SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate` pins all four of these, so a policy
retyped from memory reddens the suite rather than silently diverging:

| This plan first wrote | What the schema requires |
|---|---|
| `CREATE POLICY %1$I_company` | the policy is named **`company_isolation`**, on every table |
| `current_setting('app.current_company', true)::uuid` | **`nullif(…, '')::uuid`** — both spellings of "no company", never-set and reset-to-`''` on a recycled pooled connection, must collapse to NULL |
| an explicit `WITH CHECK` | **none.** A policy with `USING` and no `WITH CHECK` reuses `USING` as the insert check, so the read boundary *is* the write boundary and there is no second clause for somebody to relax later. The test fails on any policy that has one |
| `GRANT … TO tcms_app` | **no grant.** `deploy/postgres/initdb/10-tcms-roles.sql` sets `ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner`, so a table Flyway creates is granted before it exists. No migration in this repository issues a `GRANT` |

```sql
-- -----------------------------------------------------------------------------
-- 4. ROW LEVEL SECURITY — six more forced policies
-- -----------------------------------------------------------------------------
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing, but
-- FORCE is what makes the policy apply to a table's OWNER too, and tcms_owner is
-- what Flyway connects as. Without it a migration or an owner-run query reads
-- every company's rows, which is the trap docs/specs/tenancy-invariants.md names.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY[
        'board', 'board_column', 'board_column_status', 'board_swimlane',
        'issue_label', 'issue_label_assignment'
    ];
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

    IF array_length(protected, 1) <> 6 THEN
        RAISE EXCEPTION 'expected 6 new company-isolated tables, found %',
            array_length(protected, 1);
    END IF;
END $$;
```

- [x] **Step 4: Reproduce `tcms_seed_project_defaults` IN FULL and add the board block**

**This is the step the plan exists to make impossible to get wrong.** There is no
`ProjectCreatedEvent` and you must not build one (011 D-b). A per-project default is seeded here, and
`CREATE OR REPLACE FUNCTION` **replaces the whole body** — a block you leave out silently stops being
seeded for every project created from this migration onward, with no error anywhere.

- [x] Open `backend/src/main/resources/db/migration/V3__tracker_core.sql` and read lines **436–606**.
- [x] Copy the function **verbatim** into `V5`, from `CREATE OR REPLACE FUNCTION` to `END $$;`.
- [x] Confirm all six existing blocks are present before adding a seventh — there are **eight**
      `INSERT` blocks, not six, because the workflow is three of them:
      `classification_dimension` · `case_status` · `configuration` · `doc_space` ·
      `project_key_sequence` · the six-status default workflow.
- [x] Append the new block **inside** the function, before its `END`:

```sql
    -- V5. The project's default board (story B2, D2).
    --
    -- SEEDED, NOT OPTIONAL — the same structural argument the configuration and
    -- the doc space above make. `?view=board` has nothing to render without a
    -- board row, and "create your first board" as the entry to the main screen
    -- of the product is a configuration step nobody asked for. One column per
    -- status, which is settings-boards.html's "Team board": one status per column
    -- means a drop has no ambiguity about which status it means.
    --
    -- The STAKEHOLDER board is not seeded. It is the same six statuses folded
    -- into three columns, and which three is a decision about who is looking —
    -- exactly the "inventing the client's structure" mistake that keeps a Default
    -- *suite* out of this function. settings-boards.html is where it is made.
    -- Guarded by NOT EXISTS like every block above it, and that is what lets the
    -- backfill in step 5 CALL this function rather than restate its inserts.
    IF NOT EXISTS (SELECT 1 FROM board
                    WHERE company_id = p_company_id AND project_id = p_project_id) THEN
        v_board_id := gen_random_uuid();
        INSERT INTO board (id, company_id, project_id, name, swimlane_by)
        VALUES (v_board_id, p_company_id, p_project_id, 'Team board', 'EPIC');

        FOR v_status IN
            SELECT id, name, "position" FROM issue_status
            WHERE company_id = p_company_id AND project_id = p_project_id
            ORDER BY "position"
        LOOP
            v_column_id := gen_random_uuid();
            INSERT INTO board_column (id, company_id, board_id, name, "position",
                                      wip_limit, default_status_id)
            VALUES (v_column_id, p_company_id, v_board_id, v_status.name, v_status."position",
                    NULL, v_status.id);
            INSERT INTO board_column_status (company_id, board_id, column_id, status_id)
            VALUES (p_company_id, v_board_id, v_column_id, v_status.id);
        END LOOP;
    END IF;
```

Add to the function's `DECLARE`:
```sql
    v_board_id  UUID;
    v_column_id UUID;
    v_status    RECORD;
```

- [x] **Step 5: Backfill a board for every project that already exists**

The function seeds new projects. Existing ones need one too, and `V3` ends with exactly this shape
of loop — copy it. **Call the function; do not restate its inserts.** That is what the `NOT EXISTS`
guard in step 4 buys, it is the rule `V3`'s own backfill states ("one definition of what a seeded
project contains"), and a second copy of the board block is a second thing to keep in step with the
first.

```sql
-- -----------------------------------------------------------------------------
-- 6. A DEFAULT BOARD FOR EVERY PROJECT THAT ALREADY EXISTS
-- -----------------------------------------------------------------------------
--
-- set_config with is_local = true, and the company set per iteration: the seed
-- function asserts the company context and every INSERT it makes is under FORCE
-- ROW LEVEL SECURITY, so a loop that did not set it would read zero projects,
-- insert nothing, and report success. `company` is on the ERD §0.1 exception list
-- and carries no policy, so it can be walked.
DO $$
DECLARE
    c UUID;
    p UUID;
BEGIN
    FOR c IN SELECT id FROM company LOOP
        PERFORM set_config('app.current_company', c::text, true);
        FOR p IN SELECT id FROM project WHERE company_id = c LOOP
            PERFORM tcms_seed_project_defaults(c, p);
        END LOOP;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END $$;
```

- [x] **Step 5b: The board follows the workflow — the trigger the gate demanded (D-l)**

Not in this plan as written, and it is what thirty-eight red tests were about on the first run.
`WorkflowService` hard-deletes an `issue_status` row, and `board_column.default_status_id`'s plain
`RESTRICT` lets the board refuse it. Read D-l for why the repair is a trigger and why
`ON DELETE CASCADE` is the silently-wrong one-liner.

```sql
CREATE FUNCTION tcms_board_follows_status_removal() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    -- `status_id <> OLD.id` matters: this is a BEFORE trigger, so the cascade that
    -- removes the departing status's own claim rows has not run yet.
    UPDATE board_column c
       SET default_status_id = (
            SELECT s.id
              FROM board_column_status bcs
              JOIN issue_status s ON s.id = bcs.status_id
             WHERE bcs.column_id = c.id AND bcs.status_id <> OLD.id
             ORDER BY s."position"
             LIMIT 1)
     WHERE c.default_status_id = OLD.id
       AND EXISTS (SELECT 1 FROM board_column_status bcs
                    WHERE bcs.column_id = c.id AND bcs.status_id <> OLD.id);

    DELETE FROM board_column c WHERE c.default_status_id = OLD.id;

    RETURN OLD;
END $$;

CREATE TRIGGER board_follows_status_removal
    BEFORE DELETE ON issue_status
    FOR EACH ROW EXECUTE FUNCTION tcms_board_follows_status_removal();
```

- [x] **Step 6: Write the schema test**

**The base class is `com.tcms.IntegrationTestBase`, not `AbstractIntegrationTest`**, and it exposes
`superuserConnection()` / `appConnection(companyId)` rather than a `jdbc` field — read
`ProjectKeySequenceSchemaTest`, which is this class's nearest relative. **Read as the superuser:**
all six tables force RLS and `tcms_owner` holds no `BYPASSRLS`, so a count of zero from that role
means the policy is working rather than that the migration seeded nothing. Fixtures come from
`CompanyProvisioningService.provision(ProvisionCompanyRequest.withFirstProject(...))`, which is the
one path that both makes a project and calls the seed function.

```java
package com.tcms.tracker.board;

// A TEST, not a careful reading. CLAUDE.md: reading V1 top to bottom missed five
// join tables without company_id; scripting the comparison found them.
class BoardSchemaTest extends IntegrationTestBase {

    @Test
    void aSecondColumnCannotClaimAStatusTheFirstAlreadyHolds() {
        // uq_board_column_status is the half of "exactly one column" that Postgres
        // CAN enforce. The other half — "at least one" — is BoardConfigService's,
        // and T3 tests it there.
        UUID board = seedBoardWithOneColumnPerStatus();
        UUID otherColumn = insertColumn(board, "Duplicate");
        assertThatThrownBy(() -> claim(board, otherColumn, firstStatusId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyNewProjectGetsABoardWithOneColumnPerStatus() {
        // The seed function is CREATE OR REPLACE, so a block omitted from the
        // reproduction stops being seeded SILENTLY. This is the check that one
        // went missing — the same standing ProjectKeySequenceSchemaTest has.
        UUID project = createProject();
        assertThat(boardsOf(project)).hasSize(1);
        assertThat(columnsOf(boardsOf(project).get(0))).hasSize(6);
    }

    @Test
    void everyBlockOfTheSeedFunctionSurvivedTheReproduction() {
        // Named individually rather than counted: a count passes when one block
        // is dropped and another duplicated.
        UUID project = createProject();
        assertThat(classificationDimensionsOf(project)).isNotEmpty();
        assertThat(caseStatusesOf(project)).hasSize(7);
        assertThat(configurationsOf(project)).hasSize(1);
        assertThat(docSpacesOf(project)).hasSize(1);
        assertThat(keySequenceOf(project)).isPresent();
        assertThat(issueStatusesOf(project)).hasSize(6);
        assertThat(boardsOf(project)).hasSize(1);
    }

    @Test
    void aMidpointRankInsertsWithoutRewritingItsNeighbours() {
        UUID a = insertIssueWithRank("1024");
        UUID b = insertIssueWithRank("2048");
        jdbc.update("UPDATE issue SET rank = 1536 WHERE id = ?", insertIssue());
        assertThat(rankOf(a)).isEqualByComparingTo("1024");
        assertThat(rankOf(b)).isEqualByComparingTo("2048");
    }
}
```

- [x] **Step 7: Move the forced-RLS policy count**

`SchemaConventionTest` pins the number — in **`backend/src/test/java/com/tcms/tenancy/`**, not
`com/tcms/schema/`. `V4` took it from 84 to 82; six tables here make it **88**. Find the constant,
change it, and update the comment beside it to name `V5`.

- [x] **Step 8: Run the backend gate on shanks**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend './gradlew build'
```

Read `docs/specs/shanks-runner-details.md` first. **`BUILD FAILED` with `Could not write XML test
results` and zero assertion failures is an fd limit, not a red suite** — re-run as
`ulimit -n 65535; ./gradlew build`.

- [x] **Step 9: Update the ERD and commit**

Add the six tables to `docs/specs/2026-07-30-tcms-erd.md` and record `issue.rank` in its
`issue` section, with D-d's reasoning for `NUMERIC`.

```bash
git add backend/src/main/resources/db/migration/V5__tracker_board.sql \
        backend/src/test/java/com/tcms/tracker/board/BoardSchemaTest.java \
        backend/src/test/java/com/tcms/schema/SchemaConventionTest.java \
        docs/specs/2026-07-30-tcms-erd.md
git commit -m "feat(db): V5 — boards, columns, labels and the fractional rank

Six tables, issue.rank as arbitrary-precision NUMERIC, and a default board
seeded per project. V5 and not the V4 the 009 table reserves: M1 took two
versions, and out-of-order is unset."
```

---

## T3 — board configuration

**Landed:** `7c9883b9`
**Deliverable:** `GET|POST /projects/{projectId}/boards` and `GET|PATCH|DELETE /boards/{boardId}`
are served by `com.tcms.tracker.board`; a status added to a project's workflow is claimed by every
board of that project in the column holding the default status of the same `kind`, proven by a test;
ArchUnit is green with no change to `allowedDependencies`.
**Stack:** `be`
**Est. context:** ~130k — 14 files created, 1 modified, 4 existing services read as patterns,
3 test files, 2 skills, 3 spec sections
**Depends on:** T2 (the tables)

**Files:**
- Create: `backend/src/main/java/com/tcms/tracker/board/` — `Board.java`, `BoardColumn.java`,
  `BoardColumnStatus.java`, `BoardColumnStatusId.java`, `BoardSwimlane.java`,
  `BoardSwimlaneId.java`, `BoardRepository.java`, `BoardColumnRepository.java`,
  `BoardColumnStatusRepository.java`, `BoardConfigService.java`, `BoardConfigController.java`,
  `BoardView.java`, `BoardSummaryView.java`, `BoardInput.java`, `BoardColumnInput.java`,
  `SwimlaneBy.java`
- Modify: `backend/src/main/java/com/tcms/tracker/WorkflowService.java`
- Test: `backend/src/test/java/com/tcms/tracker/board/BoardConfigServiceTest.java`,
  `BoardConfigControllerTest.java`, `StatusClaimTest.java`

- [ ] **Step 1: Read the three patterns this package copies**

- `backend/src/main/java/com/tcms/tracker/WorkflowService.java` — how a project-scoped
  configuration service is written here, and where `claimNewStatus` must be called from.
- `backend/src/main/java/com/tcms/tracker/IssueStatusRepository.java` — **`@Transactional(readOnly
  = true)` is on the interface.** Spring Data gives a transaction only to inherited CRUD methods, so
  a derived or `@Query` method otherwise runs with no company set and every RLS-protected read
  returns **zero rows, fail-closed and silent.** Every repository this task creates carries it.
- `backend/src/main/java/com/tcms/tracker/package-info.java` — `allowedDependencies` is
  `{api, tenancy, identity, project, storage}` and **this task does not change it.** `board` is a
  sub-package of `tracker`, so nothing here crosses a module boundary at all.

- [ ] **Step 2: Write the failing test for the rule Postgres cannot express**

```java
package com.tcms.tracker.board;

class BoardConfigServiceTest extends AbstractIntegrationTest {

    @Test
    void refusesABoardThatLeavesAStatusUnclaimed() {
        // "Every status is claimed by exactly ONE column" (story B2) is two rules.
        // uq_board_column_status enforces "at most one" and BoardSchemaTest covers
        // it. THIS is the other half, and it is a service rule because it is a
        // cross-table assertion — every issue_status row has a match here — which
        // Postgres has no CHECK for. A trigger was refused: it would fire while a
        // PATCH is rebuilding a board column by column and reject the legal
        // intermediate state.
        UUID project = projectWithSixStatuses();
        BoardInput input = boardClaiming(project, allStatusesExcept("Blocked"));

        assertThatThrownBy(() -> service.create(project, input))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("Blocked");
    }

    @Test
    void refusesADefaultStatusTheColumnDoesNotClaim() {
        // D2: a drop into a column takes that column's default status. A default
        // outside the claim set would move a card to a status the column does not
        // show it in — it would vanish from the board on being dropped onto it.
        UUID project = projectWithSixStatuses();
        BoardInput input = oneColumnClaiming("Backlog").withDefault(statusId("Done"));

        assertThatThrownBy(() -> service.create(project, input))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void refusesDeletingTheProjectsLastBoard() {
        UUID project = projectWithSixStatuses();
        UUID onlyBoard = service.list(project).get(0).id();

        assertThatThrownBy(() -> service.delete(onlyBoard))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void deletingABoardLosesNoIssue() {
        // Story B2's fourth criterion, and §12.1's whole design: a board is a lens.
        UUID project = projectWithSixStatuses();
        UUID extra = service.create(project, coarseBoard(project)).id();
        long before = issueRepository.countByProject(project);

        service.delete(extra);

        assertThat(issueRepository.countByProject(project)).isEqualTo(before);
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.tracker.board.BoardConfigServiceTest'
```
Expected: FAIL — `BoardConfigService` does not exist. (Targeted tests may run locally for
debugging; **the gates stay on shanks.**)

- [ ] **Step 4: Write the entities and repositories**

```java
package com.tcms.tracker.board;

@Entity
@Table(name = "board")
public class Board {
    @Id private UUID id;
    @Column(name = "company_id", nullable = false) private UUID companyId;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "swimlane_by", nullable = false) private SwimlaneBy swimlaneBy;
    @Version @Column(name = "lock_version") private long lockVersion;
    // getters/setters as the sibling entities in com.tcms.tracker write them
}
```

```java
package com.tcms.tracker.board;

/**
 * Story B3's grouping axis.
 *
 * <p>{@code RELEASE} is <strong>absent until M3</strong>, and that is a decision rather than an
 * omission: {@code issue_fact.release_id} is NULL until that module writes it, so a release
 * swimlane would render every card in one lane called "None" — which reads as a broken board, not
 * as an unbuilt feature. M3 adds the member, and {@code lib/labels.ts} makes that a compile error
 * on the frontend until it is labelled.
 */
public enum SwimlaneBy { NONE, EPIC, ASSIGNEE, LABEL, PRIORITY }
```

```java
package com.tcms.tracker.board;

/**
 * {@code @Transactional(readOnly = true)} is on the INTERFACE and must stay there.
 *
 * <p>Spring Data gives a transaction only to the inherited CRUD methods. A derived or {@code @Query}
 * method on an interface without it runs with no company set, so the RLS policy sees an unset
 * {@code app.current_company} and every row is filtered out — <strong>zero rows, fail-closed and
 * silent.</strong> There is no exception and no error to find it by.
 */
@Transactional(readOnly = true)
public interface BoardRepository extends JpaRepository<Board, UUID> {
    List<Board> findByProjectIdOrderByNameAsc(UUID projectId);
    long countByProjectId(UUID projectId);
}
```

`BoardColumnRepository` and `BoardColumnStatusRepository` follow the same shape, each with the
annotation on the interface.

- [ ] **Step 5: Write `BoardConfigService`**

```java
package com.tcms.tracker.board;

@Service
public class BoardConfigService {

    private final ProjectService projects;          // the gate — readVisible / writeGranted
    private final IssueStatusRepository statuses;
    private final BoardRepository boards;
    private final BoardColumnRepository columns;
    private final BoardColumnStatusRepository claims;

    @Transactional
    public BoardView create(UUID projectId, BoardInput input) {
        projects.writeGranted(projectId, "board:configure");
        validateClaims(projectId, input);
        return write(new Board(UUID.randomUUID(), projectId), input);
    }

    @Transactional
    public BoardView update(UUID boardId, BoardInput input) {
        Board board = load(boardId);
        projects.writeGranted(board.getProjectId(), "board:configure");
        validateClaims(board.getProjectId(), input);
        return write(board, input);
    }

    @Transactional
    public void delete(UUID boardId) {
        Board board = load(boardId);
        projects.writeGranted(board.getProjectId(), "board:configure");
        // A project with no board cannot render ?view=board at all, and an empty
        // board picker is a dead end rather than a state the SPA can explain.
        if (boards.countByProjectId(board.getProjectId()) <= 1) {
            throw new UnprocessableException("A project keeps at least one board.");
        }
        boards.delete(board);   // ON DELETE CASCADE takes the columns and the claims.
                                // It takes NO issue: nothing references a board FROM issue.
    }

    /**
     * The half of "exactly one column per status" that Postgres cannot express.
     *
     * <p>The unique index on {@code board_column_status (board_id, status_id)} rejects a status
     * claimed twice. "Claimed at least once" is a statement about the absence of a row and is
     * therefore a cross-table assertion — no CHECK can hold it, and a trigger would fire mid-PATCH
     * while a board is being rebuilt column by column, rejecting a legal intermediate state.
     *
     * <p>The message names the unclaimed statuses, because "invalid board" sends somebody to count
     * six columns against six statuses by hand.
     */
    private void validateClaims(UUID projectId, BoardInput input) {
        Set<UUID> all = statuses.findByProjectId(projectId).stream()
                .map(IssueStatus::getId).collect(toSet());
        List<UUID> claimed = input.columns().stream()
                .flatMap(c -> c.statusIds().stream()).toList();

        Set<UUID> duplicated = claimed.stream()
                .filter(id -> Collections.frequency(claimed, id) > 1).collect(toSet());
        if (!duplicated.isEmpty()) {
            throw new UnprocessableException(
                    "Claimed by more than one column: " + names(duplicated));
        }
        Set<UUID> unclaimed = new HashSet<>(all);
        unclaimed.removeAll(claimed);
        if (!unclaimed.isEmpty()) {
            throw new UnprocessableException(
                    "No column claims: " + names(unclaimed));
        }
        Set<UUID> foreign = new HashSet<>(claimed);
        foreign.removeAll(all);
        if (!foreign.isEmpty()) {
            throw new UnprocessableException("Not a status of this project: " + foreign);
        }
        for (BoardColumnInput column : input.columns()) {
            // D2's default must be inside the claim set, or a drop into the column
            // moves the card to a status the column does not show — it disappears
            // from the board on being dropped onto it.
            if (!column.statusIds().contains(column.defaultStatusId())) {
                throw new UnprocessableException(
                        "Column '" + column.name() + "' defaults to a status it does not claim.");
            }
        }
    }
}
```

- [ ] **Step 6: Write the auto-claim, and wire it into `WorkflowService`**

```java
    /**
     * A status created after a board exists (009 review R13).
     *
     * <p><strong>An unclaimed status is not a state the data can be in</strong>, and this is what
     * makes that true rather than merely likely. The new status is claimed on <em>every</em> board
     * of the project, by the column that holds the default status of the same {@code kind} — a new
     * {@code IN_PROGRESS} status lands beside In Progress — and the settings screen shows it there
     * so it can be moved.
     *
     * <p><strong>Called directly, not through an event</strong> (D-g). {@code WorkflowService} and
     * this class are in the same Spring Modulith module — {@code board} is a sub-package of
     * {@code tracker}, not a module of its own — so this runs inside the caller's transaction. An
     * {@code OutboxEvents} hop would have a one-minute delivery floor, and for that minute the
     * project would have a status no column claims, which is the state this method exists to make
     * impossible.
     *
     * <p>The fallback when no column holds a same-kind default: the <em>last</em> column of the
     * board. Not the first — a board whose first column is Backlog would silently file a new DONE
     * status at the start of the workflow, and left-to-right is direction of travel (§12.2).
     */
    @Transactional
    public void claimNewStatus(UUID projectId, IssueStatus created) {
        for (Board board : boards.findByProjectIdOrderByNameAsc(projectId)) {
            List<BoardColumn> ordered = columns.findByBoardIdOrderByPositionAsc(board.getId());
            BoardColumn target = ordered.stream()
                    .filter(c -> kindOf(c.getDefaultStatusId()) == created.getKind())
                    .findFirst()
                    .orElse(ordered.get(ordered.size() - 1));
            claims.save(new BoardColumnStatus(board.getId(), target.getId(), created.getId()));
        }
    }
```

In `WorkflowService`, at the point a status is created by `PUT /projects/{projectId}/workflow`:

```java
        IssueStatus saved = statuses.save(status);
        // R13. Inside this transaction deliberately — see BoardConfigService#claimNewStatus.
        boardConfig.claimNewStatus(projectId, saved);
```

- [ ] **Step 7: Write the test that proves the auto-claim**

```java
package com.tcms.tracker.board;

class StatusClaimTest extends AbstractIntegrationTest {

    @Test
    void anInProgressStatusAddedLaterLandsBesideInProgressOnEveryBoard() {
        UUID project = projectWithSixStatuses();
        UUID coarse = boardConfig.create(project, threeColumnBoard(project)).id();   // Not started / In flight / Done

        workflowService.replace(project, workflowPlus("Awaiting QA", IssueStatusKind.IN_PROGRESS));

        // The seeded per-status board:
        assertThat(columnClaiming(defaultBoard(project), "Awaiting QA"))
                .isEqualTo("In Progress");
        // AND the coarse one — "every board of the project", not just the one being looked at.
        assertThat(columnClaiming(coarse, "Awaiting QA")).isEqualTo("In flight");
    }

    @Test
    void aStatusOfAKindNoColumnDefaultsToLandsInTheLastColumn() {
        // Not the first. Left-to-right is direction of travel, and a new DONE
        // status filed at the start of the workflow is worse than one filed late.
        UUID project = projectWithSixStatuses();
        UUID board = boardConfig.create(project, twoColumnBoardWithNoDoneDefault(project)).id();

        workflowService.replace(project, workflowPlus("Shipped", IssueStatusKind.DONE));

        assertThat(columnClaiming(board, "Shipped")).isEqualTo(lastColumnName(board));
    }

    @Test
    void noStatusIsEverUnclaimedAfterAWorkflowEdit() {
        // The invariant itself, computed rather than read — CLAUDE.md's rule.
        UUID project = projectWithSixStatuses();
        workflowService.replace(project, workflowPlus("Awaiting QA", IssueStatusKind.IN_PROGRESS));

        for (BoardSummaryView board : boardConfig.list(project)) {
            Set<UUID> claimed = claimsOf(board.id());
            Set<UUID> all = statusIdsOf(project);
            assertThat(claimed).containsExactlyInAnyOrderElementsOf(all);
        }
    }
}
```

- [ ] **Step 8: Write the controller**

```java
package com.tcms.tracker.board;

@RestController
public class BoardConfigController {

    @GetMapping("/projects/{projectId}/boards")
    public List<BoardSummaryView> list(@PathVariable UUID projectId) { … }

    @PostMapping("/projects/{projectId}/boards")
    @ResponseStatus(HttpStatus.CREATED)
    public BoardView create(@PathVariable UUID projectId, @RequestBody @Valid BoardInput in) { … }

    @GetMapping("/boards/{boardId}")
    public BoardView get(@PathVariable UUID boardId) { … }

    @PatchMapping("/boards/{boardId}")
    public BoardView update(@PathVariable UUID boardId, @RequestBody @Valid BoardInput in) { … }

    @DeleteMapping("/boards/{boardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID boardId) { … }
}
```

- [ ] **Step 9: Run the gate on shanks and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend './gradlew build'
```
ArchUnit must be green **without** editing `ArchitectureRules.FOUNDATION_MODULES` or
`tracker/package-info.java`. If it is not, `tracker.board` has reached for a module `tracker` may not
see — that is a defect in this task, not a case for a sixth foundation module.

```bash
git add backend/src/main/java/com/tcms/tracker/board/ \
        backend/src/main/java/com/tcms/tracker/WorkflowService.java \
        backend/src/test/java/com/tcms/tracker/board/
git commit -m "feat(board): board configuration, and the status claim rule

tracker.board as a sub-package, not a module. Every status claimed by
exactly one column: a unique index for 'at most one', a service rule for
'at least one', and a direct call from WorkflowService for a status added
after the board exists."
```

**Out of scope, and no screen shows any of this yet:** the cards, the move, the rank and the labels
are T4; the board is not rendered until T7.

---

## T4 — cards, rank and labels

**Deliverable:** `GET /boards/{boardId}/cards` returns lanes and reports WIP breaches;
`POST /boards/{boardId}/cards/{key}/move` resolves the column's default status and answers 200 with
the breach; `PUT /issues/{key}/rank` places an issue between two neighbours and rebalances when
precision runs out; `GET /projects/{projectId}/backlog` and the four label endpoints are served.
**Stack:** `be`
**Est. context:** ~130k — 14 files created, 4 read as patterns, 3 test files, 2 skills
**Depends on:** T3 (`board_column` is what a card is grouped into)

**Files:**
- Create: `backend/src/main/java/com/tcms/tracker/board/` — `BoardCardService.java`,
  `BoardMoveService.java`, `RankService.java`, `LabelService.java`, `BoardCardController.java`,
  `BacklogController.java`, `RankController.java`, `LabelController.java`, `IssueLabel.java`,
  `IssueLabelAssignment.java`, `IssueLabelAssignmentId.java`, `IssueLabelRepository.java`,
  `IssueLabelAssignmentRepository.java`, `BoardContentsView.java` (+ `BoardLaneView`,
  `BoardIssueCardView`, `WipBreachView`, `BacklogRowView`, `RankInput`, `RankResultView`,
  `LabelView`, `LabelInput`, `IssueLabelsInput`)
- Test: `backend/src/test/java/com/tcms/tracker/board/RankServiceTest.java`,
  `BoardCardServiceTest.java`, `BoardMoveServiceTest.java`

- [ ] **Step 1: Write the rank test first — it is the part that can be subtly wrong**

```java
package com.tcms.tracker.board;

class RankServiceTest extends AbstractIntegrationTest {

    @Test
    void insertingBetweenTheSameTwoNeighboursRepeatedlyNeverCollides() {
        // The acceptance criterion, and the reason the column is NUMERIC without a
        // precision: arbitrary precision means a midpoint ALWAYS exists. A fixed
        // NUMERIC(12,4) runs out after ~14 inserts between one pair and starts
        // silently returning a rank equal to a neighbour's.
        UUID a = issueWithRank("1024");
        UUID b = issueWithRank("2048");

        List<BigDecimal> seen = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID inserted = newIssue();
            RankResultView r = service.place(keyOf(inserted), between(keyOf(a), keyOf(b)));
            seen.add(new BigDecimal(r.rank()));
            b = inserted;                       // always squeeze against the newest
        }
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void rebalancingRespacesTheProjectAndSaysSo() {
        // Growth is bounded by the SERVICE, not by the type. Twelve fractional
        // digits is the trigger; the client is told, because after a respace its
        // own copy of every other row's rank is stale.
        UUID project = projectWithManyIssues();
        squeezeUntilPrecisionIsExhausted(project);

        RankResultView result = service.place(nextKey(), between(firstKey(), secondKey()));

        assertThat(result.rebalanced()).isTrue();
        assertThat(ranksOf(project)).allMatch(r -> r.scale() <= 0);   // whole numbers again
        assertThat(ranksOf(project)).isSorted();
    }

    @Test
    void placingAtTheTopNeedsNoNeighbourAtAll() {
        UUID top = issueWithRank("1024");
        RankResultView r = service.place(keyOf(newIssue()), toTheTop());
        assertThat(new BigDecimal(r.rank())).isLessThan(new BigDecimal("1024"));
    }

    @Test
    void refusesANeighbourInAnotherProject() {
        // Not merely wrong — a rank is project-wide, so a cross-project neighbour
        // would compute a midpoint against a number from a different sequence and
        // land the issue in an order nobody can explain.
        assertThatThrownBy(() -> service.place(keyInProjectA(), between(keyInProjectB(), null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesBothNeighboursAtOnce() {
        assertThatThrownBy(() -> service.place(someKey(), bothNeighbours()))
                .isInstanceOf(BadRequestException.class);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.tracker.board.RankServiceTest'
```
Expected: FAIL — `RankService` does not exist.

- [ ] **Step 3: Write `RankService`**

```java
package com.tcms.tracker.board;

/**
 * The fractional rank (D-d).
 *
 * <h2>Neighbour-relative, never absolute</h2>
 *
 * <p>The client sends the two issues the dragged row landed between, not a number. Two people
 * dragging at once each computed a midpoint from a list they read seconds ago, and sending the
 * number races while sending the neighbours does not — the server reads both ranks inside this
 * transaction.
 *
 * <h2>Why the column has no precision</h2>
 *
 * <p>Postgres {@code NUMERIC} without a precision is arbitrary precision, so a midpoint between two
 * neighbours <strong>always exists</strong> and inserting one row <strong>never rewrites
 * another</strong>. A fixed {@code NUMERIC(12,4)} runs out after roughly fourteen inserts between
 * one pair and then returns a rank equal to a neighbour's — silently, with no error, and two rows
 * whose order is decided by whatever the tiebreaker happens to be.
 *
 * <h2>Growth, and the one place it is bounded</h2>
 *
 * <p>Precision is not free: forty squeezes against the same neighbour produce a forty-digit
 * fraction. The bound is here rather than in the type — past {@link #MAX_SCALE} fractional digits,
 * the project is respaced to {@code 1024, 2048, 3072 …} in one statement and the caller is told, so
 * it can drop its own stale copy of every other row's rank.
 */
@Service
public class RankService {

    /** Twelve digits is ~40 squeezes against one neighbour, which no human backlog reaches. */
    static final int MAX_SCALE = 12;
    private static final BigDecimal SPACING = new BigDecimal("1024");

    @Transactional
    public RankResultView place(String key, RankInput input) {
        Issue issue = issues.byKey(key);
        projects.writeGranted(issue.getProjectId(), "board:rank");

        if (input.afterKey() != null && input.beforeKey() != null) {
            throw new BadRequestException("Send one neighbour, not both.");
        }
        BigDecimal lower = rankOf(input.afterKey(), issue.getProjectId());
        BigDecimal upper = rankOf(input.beforeKey(), issue.getProjectId());

        BigDecimal target = midpoint(lower, upper, issue.getProjectId());
        boolean rebalanced = false;
        if (target.scale() > MAX_SCALE) {
            rebalance(issue.getProjectId());
            rebalanced = true;
            target = midpoint(reread(input.afterKey()), reread(input.beforeKey()),
                              issue.getProjectId());
        }
        issue.setRank(target);
        return new RankResultView(key, target.toPlainString(), rebalanced);
    }

    private BigDecimal midpoint(BigDecimal lower, BigDecimal upper, UUID projectId) {
        if (lower == null && upper == null) return SPACING;
        if (lower == null) return upper.subtract(SPACING);        // to the top
        if (upper == null) return lower.add(SPACING);             // to the bottom
        return lower.add(upper).divide(TWO, MAX_SCALE + 4, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
    }

    /**
     * One statement, and it is the only thing in this class that touches a row the caller did not
     * name. {@code row_number()} over the existing order is what makes it order-preserving — a
     * respace that re-sorted would silently rearrange a backlog somebody spent an afternoon on.
     */
    private void rebalance(UUID projectId) {
        jdbc.update("""
            UPDATE issue i SET rank = ranked.seq * 1024
            FROM (SELECT id, row_number() OVER (ORDER BY rank, id) AS seq
                  FROM issue WHERE company_id = ? AND project_id = ?) ranked
            WHERE i.id = ranked.id
            """, CompanyContext.required(), projectId);
    }
}
```

- [ ] **Step 4: Write `BoardCardService` — lanes, and the breach**

```java
    /**
     * The cards, grouped into the board's columns and into lanes.
     *
     * <h2>The WIP breach is REPORTED, never enforced</h2>
     *
     * <p>Story B3: <em>"the drop is accepted and the column header shows the breach"</em>. This
     * returns every column currently over its limit, and {@link BoardMoveService} answers 200 with
     * the breach rather than refusing the move. A limit that refused drops is a limit people route
     * around by not using the board, and then the board stops describing the work — which costs
     * more than the limit was ever worth.
     *
     * <p>Counted across every lane, not per lane: a WIP limit is about how much is in flight, and
     * three cards in three epics is three cards in flight.
     *
     * <h2>Filter semantics are Trello's, exactly</h2>
     *
     * <p><strong>OR within a category, AND across categories</strong> (§12.2) — worth copying
     * precisely because it is learnable. {@code labelId=a&labelId=b&assigneeId=c} is
     * <em>(a or b) and c</em>.
     */
```

- [ ] **Step 5: Write `BoardMoveService`**

```java
    /**
     * Move a card to a column.
     *
     * <p>Costs {@code issue:edit}, and <strong>there is no {@code board:move}</strong> — §A3 says
     * so: a second key for an act an existing key already covers is a permission somebody could
     * hold without being able to use it.
     *
     * <p>Two things this does that {@code POST /issues/{key}/transitions} cannot, which is why it
     * exists rather than the client calling that one:
     *
     * <ol>
     *   <li><strong>It resolves the column's default status</strong> (D2). A stakeholder board's
     *       "In flight" claims In Progress, In Review and Blocked; a drop into it must mean exactly
     *       one of them, and which one is the column's decision rather than the client's.</li>
     *   <li><strong>It reports the WIP breach</strong> the move may have caused.</li>
     * </ol>
     *
     * <p>The transition itself is delegated to {@link com.tcms.tracker.TransitionService} — same
     * module, same validation, same audit row. A second implementation of "may this issue go
     * there" is how two answers to that question start disagreeing.
     *
     * <p><strong>Already in a status the column claims: nothing happens, and it is a 200.</strong>
     * Dragging a card from In Review to a coarse column that already claims In Review is not a
     * move, and writing the column's default would silently demote it to In Progress.
     */
    @Transactional
    public CardMoveResultView move(UUID boardId, String key, UUID toColumnId) {
        BoardColumn column = columns.require(boardId, toColumnId);
        Issue issue = issues.byKey(key);

        if (claims.claims(toColumnId, issue.getStatusId())) {
            return new CardMoveResultView(cardOf(issue), breachOf(column));
        }
        transitions.transition(key, new IssueTransitionInput(column.getDefaultStatusId()));
        return new CardMoveResultView(cardOf(issues.byKey(key)), breachOf(column));
    }
```

- [ ] **Step 6: Write the move tests**

```java
class BoardMoveServiceTest extends AbstractIntegrationTest {

    @Test
    void aDropIntoAColumnClaimingThreeStatusesTakesItsDefault() {
        // Story B2's second criterion, and D2's whole point.
        UUID coarse = coarseBoard();     // "In flight" claims In Progress + In Review + Blocked
        moveService.move(coarse, "ACME-201", columnId(coarse, "In flight"));
        assertThat(statusOf("ACME-201")).isEqualTo("In Progress");
    }

    @Test
    void aCardAlreadyInAStatusTheColumnClaimsIsNotDemoted() {
        // In Review is claimed by "In flight" whose default is In Progress. A move
        // that wrote the default would silently walk the card backwards.
        UUID coarse = coarseBoard();
        transitionTo("ACME-201", "In Review");
        moveService.move(coarse, "ACME-201", columnId(coarse, "In flight"));
        assertThat(statusOf("ACME-201")).isEqualTo("In Review");
    }

    @Test
    void aMoveOverTheWipLimitSucceedsAndReportsTheBreach() {
        // Reported, NOT enforced (story B3).
        UUID board = boardWithWipLimit("In Progress", 1);
        moveService.move(board, "ACME-201", columnId(board, "In Progress"));

        CardMoveResultView result =
                moveService.move(board, "ACME-202", columnId(board, "In Progress"));

        assertThat(statusOf("ACME-202")).isEqualTo("In Progress");   // it moved
        assertThat(result.breach()).isNotNull();
        assertThat(result.breach().count()).isEqualTo(2);
        assertThat(result.breach().limit()).isEqualTo(1);
    }

    @Test
    void aMoveTheWorkflowForbidsIs409AndNamesTheLegalTargets() {
        // Unreachable in an ordinary session — the picker only offers legal moves.
        // tcmsFaults.issue('conflict') is how the SPA exercises it (T6).
        assertThatThrownBy(() -> moveService.move(board(), "ACME-201", columnId(board(), "Done")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Ready");
    }

    @Test
    void aCardMovedOnOneBoardIsInTheMatchingColumnOnTheOther() {
        // THE module's done-criterion, as a test rather than as a click-through.
        // It holds because there is one status and two mappings of it (D2) — there
        // is no per-board state that could disagree.
        UUID fine = defaultBoard();      // one column per status
        UUID coarse = coarseBoard();     // three columns
        moveService.move(fine, "ACME-201", columnId(fine, "In Review"));

        BoardContentsView onCoarse = cardService.contents(coarse, noFilter());
        assertThat(columnOf(onCoarse, "ACME-201")).isEqualTo(columnId(coarse, "In flight"));
    }
}
```

- [ ] **Step 7: Write the label and backlog services and controllers**

`LabelService` — CRUD gated on **`board:configure`**, and `putIssueLabels` gated on **`issue:edit`**
(D-e). `BacklogController` serves `GET /projects/{projectId}/backlog`: STORY and TASK level, not
`DONE`, in `rank` order, taking the same filter as the board.

```java
    /**
     * An Epic is not a backlog row.
     *
     * <p>D31 derives an Epic's status from its stories, so it has nothing to be ranked *against* —
     * and a backlog listing both an epic and the stories under it double-counts the same work in
     * the one view whose whole job is "what is next".
     */
```

- [ ] **Step 8: Run the gate on shanks and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend './gradlew build'
```

```bash
git add backend/src/main/java/com/tcms/tracker/board/ backend/src/test/java/com/tcms/tracker/board/
git commit -m "feat(board): cards, the fractional rank, and labels

WIP limits report a breach rather than refusing a drop. A move resolves
the target column's default status (D2) and delegates the transition to
TransitionService rather than reimplementing the workflow check."
```

**Out of scope:** no screen calls any of this. The board arrives in T7, the backlog in T8.

---

## T5 — board verbs on the CLI and MCP

**Deliverable:** `tcms board list|show|move`, `tcms backlog`, `tcms rank`, `tcms label list|add|set`
work against a running instance, the same capabilities are MCP tools, and
`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` describes what shipped.
**Stack:** `ts`
**Est. context:** ~110k — 4 existing package files read, ~6 created, MCP tools, tests, 2 skills
**Depends on:** T4 (the endpoints the verbs call)

**Files:**
- Modify: `packages/cli/src/commands.ts`, `packages/cli/src/reads.ts`, `packages/cli/src/writes.ts`
- Modify: `packages/mcp/src/reads.ts`, `packages/mcp/src/server.ts`, `packages/mcp/src/instructions.ts`
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`
- Test: `packages/cli/src/writes.test.ts`, `packages/mcp/src/reads.test.ts`

> **A deviation from the M2 prompt, recorded rather than taken silently.** The prompt puts *"the
> CLI/MCP verbs and the surface doc"* inside the contract commit. T1 lands the **surface doc**; the
> **verbs** are here, because they are hand-written `packages/` code and folding them into T1 put
> that task at ~150k. `009-execution-breakdown.md`'s phase 2 has no task for them at all — an
> omission, since "What every module carries" makes the module's verbs part of its definition of
> done. This task closes that gap.

- [ ] **Step 1: Read how M1's issue verbs are written**

`packages/cli/src/reads.ts` and `writes.ts` — in particular `api.delWithBody`, which exists for
`DELETE /projects/{projectId}/workflow/statuses/{statusId}`, and is the pattern for any verb whose
shape is unusual. `packages/mcp/src/reads.ts` for how a read becomes a tool.

- [ ] **Step 2: Add the read verbs**

```ts
/**
 * `tcms board show <boardId>` — the cards, in lanes.
 *
 * Rendered as one line per card with its column, because the agent reading this is answering
 * "what is in flight" and a nested lane structure costs it a parse it does not need. The lane is a
 * column of the line, not a heading.
 */
export async function boardShow(ctx: Context, boardId: string, filter: BoardFilter) { … }
```

- [ ] **Step 3: Add the write verbs**

```ts
/**
 * `tcms rank <key> --after <key> | --before <key>`
 *
 * Exactly one of the two, mirroring the contract's own 400 — and the CLI refuses it locally rather
 * than sending a request it knows will fail, so the message names the flags a person typed rather
 * than a field name from a JSON body they never saw.
 */
export async function rank(ctx: Context, key: string, opts: { after?: string; before?: string }) {
  if ((opts.after && opts.before) || (!opts.after && !opts.before)) {
    throw new UsageError('Give exactly one of --after or --before.')
  }
  …
}
```

```ts
/**
 * `tcms board move <boardId> <key> --to <column>`
 *
 * **Prints the WIP breach when the response carries one, and still exits 0.** The move succeeded;
 * a non-zero exit would make a breach fail somebody's script, which is exactly the "limit that
 * people route around" story B3 rejects — in its automation spelling.
 */
```

- [ ] **Step 4: Register the MCP tools and write their instructions**

Each tool's description says what it is *for*, not what it *calls*. Add to `instructions.ts` the one
sentence an assistant needs and cannot infer: **a board is a lens, so moving a card changes the
issue's status and every other board over that project shows the change.**

- [ ] **Step 5: Test the two that can be wrong**

```ts
it('refuses --after and --before together without sending a request', async () => {
  const fetchSpy = vi.fn()
  await expect(rank(ctx(fetchSpy), 'ACME-1', { after: 'ACME-2', before: 'ACME-3' }))
    .rejects.toThrow(/exactly one/)
  expect(fetchSpy).not.toHaveBeenCalled()
})

it('exits 0 and prints the breach when a move goes over the WIP limit', async () => {
  const out = await run(['board', 'move', BOARD, 'ACME-1', '--to', 'In Progress'])
  expect(out.code).toBe(0)
  expect(out.stdout).toContain('over the limit')
})
```

- [ ] **Step 6: Run the gate on shanks and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages 'npm run lint --workspaces && npm run test:run --workspaces'
```
`npm run build` at the root and **not** `--workspaces` — that runs them alphabetically, so `cli`
would typecheck against a stale `client` dist.

```bash
git add packages/cli/src packages/mcp/src docs/specs/2026-08-08-mcp-tool-and-command-surface.md
git commit -m "feat(cli,mcp): board, backlog, rank and label verbs"
```

---

## T6 — the fault switch, the queries, and the move logic

**Deliverable:** `tcmsFaults.rank('conflict')` and `tcmsFaults.wip()` exist and fault the real
backend; `api/queries/projectBoards.ts` holds every board read and write with the optimistic rank
mutation and its snapshot rollback; `cardMove.ts` gains `resolveColumnMove` and `boardKeyboard.ts`
becomes swimlane-aware — **both extended, neither forked** — and all of it is unit-tested.
**No screen renders in this task.**
**Stack:** `fe`
**Est. context:** ~110k — 6 existing files read, 1 created, 4 modified, 3 test files, 2 skills
**Depends on:** T4

**Files:**
- Create: `frontend/src/api/queries/projectBoards.ts`
- Modify: `frontend/src/lib/devFaults.ts`, `frontend/src/api/queries/keys.ts`,
  `frontend/src/api/queries/index.ts`, `frontend/src/features/board/cardMove.ts`,
  `frontend/src/features/board/boardKeyboard.ts`, `frontend/src/lib/labels.ts`
- Test: `frontend/src/features/board/cardMove.test.ts` (extend),
  `frontend/src/features/board/boardKeyboard.test.ts` (extend),
  `frontend/src/api/queries/projectBoards.test.tsx` (new)

> **The fault switch lands before the feature it proves** — CLAUDE.md's working agreement, and the
> M2 prompt repeats it. A resilience behaviour you cannot trigger is one you have not tested, and
> neither a rank conflict nor a WIP breach can be produced by using the app normally.

- [ ] **Step 1: Extend `devFaults.ts`**

```ts
/** Which fabricated failure the rank endpoint answers with. */
export type RankFault = 'conflict' | 'rebalance'
```

```ts
/*
  `rank('conflict')` — PUT /issues/{key}/rank answers 409 once.

  Unreachable in an ordinary session: it needs a second person to move the
  neighbour between this client's read and its write, inside one drag. It is
  also the ONLY thing that proves the optimistic rank drag rolls back, which is
  the machinery M2 earned and M1 deliberately did not (D-c) — so without this
  switch the rollback ships untested and looks identical to a board that simply
  never fails.

  `rank('rebalance')` — 200 with `rebalanced: true`, which is not a failure and
  is easy to get wrong in the opposite direction: the client must DROP its
  optimistic order and refetch, because after a respace every other row's rank
  is stale. A client that keeps its patch here shows an order the server does
  not have, and nothing surfaces the disagreement.

  `wip()` — every move response carries a breach. Proves the column header
  reports it and that the card still lands, which is story B3's criterion and
  the one a real WIP limit only reaches after somebody drags three cards.
*/
```

Add to `FaultState`: `rank: RankFault | null` and `wip: boolean`. Add the two arms to
`verdictFor`/`maybeFault`, keyed on `PUT` to `/issues/…/rank` and `POST` to `/boards/…/move`. Add
`rank()` and `wip()` to the console object in `installFaultConsole`.

**Both guards stay a literal `import.meta.env.DEV`.** Route the check through an imported constant
and Rollup keeps the module in the production build.

- [ ] **Step 2: Add the keys, to the one factory**

In `keys.ts` — **never a second factory**:

```ts
  /**
   * A project's boards. Under the project so a board deleted or created sweeps the list.
   */
  boards: (p: string) => ['projects', p, 'boards'] as const,
  /**
   * One board's CONFIGURATION — its columns and swimlane setting.
   *
   * Deliberately not under `keys.boards`: editing one board's columns must not evict the list, and
   * the two have different lifetimes (D-h). Configuration changes when somebody edits it; cards
   * change on every transition anywhere in the project.
   */
  board: (id: string) => ['boards', id] as const,
  /**
   * One board's CARDS, keyed by the filter — two filters are two lists, and the URL is where the
   * filter lives, so this key is derived from it rather than from component state.
   */
  boardCards: (id: string, filters: WorkFilters) => ['boards', id, 'cards', filters] as const,
  backlog: (p: string, filters: WorkFilters) => ['projects', p, 'backlog', filters] as const,
  labels: (p: string) => ['projects', p, 'labels'] as const,
```

While here, rename `keys.workItems` to `keys.compositeBoard` — it names a table that has not existed
since `V4`, and this module is about to add three keys with "board" in the name.

- [ ] **Step 3: Write `projectBoards.ts`, with the one optimistic mutation in the module**

```ts
/**
 * The project board's reads and writes (012 M2).
 *
 * **Not `boards.ts`.** `board.ts` already holds the *composite* board's three reads — a different
 * board, a different endpoint and a different card shape (D-a). Two modules one letter apart is how
 * a wrong import survives review.
 */

/**
 * Move a card to a column. **Not optimistic**, and that is scope §12.4 verbatim: *"A drop that
 * `issue_transition` forbids is refused at the drag, with the reason on screen. It is never
 * accepted optimistically and reverted."*
 *
 * The legality is already known client-side — `allowedTransitions` is on the card — so
 * `resolveColumnMove` refuses an illegal drop before a request is made, and there is nothing left
 * for an optimistic patch to buy except the one failure mode §12.4 rules out. An accept-then-revert
 * reads as a bug every time and teaches people to distrust the board.
 */
export function useMoveCard(boardId: string) {
  return useMutation({
    mutationFn: (v: { key: string; toColumnId: string }) => …,
    onSuccess: (result) => {
      qc.invalidateQueries({ queryKey: keys.boardCardsRoot(boardId) })
      // Every board over this project shows the same statuses (D2), so one move
      // invalidates them all — this is what makes "moved here, matching column
      // there" true on screen and not only in the database.
      qc.invalidateQueries({ queryKey: keys.issuesRoot(projectId) })
      if (result.breach) notify.warning(wipMessage(result.breach))
    },
  })
}

/**
 * Place an issue between two neighbours. **Optimistic, with a snapshot rollback** — and this is the
 * one place in the product where that is right (D-c).
 *
 * The difference from the move above is not preference, it is information: legality of a *status*
 * change is on the card and can be checked before the drag; legality of a *rank* change is the
 * server's neighbours, which the client cannot know. So there is nothing to refuse up front, and a
 * rank drag with no optimistic patch is a row that hangs where it was for a round trip and then
 * jumps — which reads worse than a row that moves and, rarely, snaps back saying why.
 *
 * `rebalanced: true` **drops the patch instead of keeping it**: after a respace every other row's
 * rank is stale, so the optimistic order is no longer a prediction of anything.
 */
export function useRankIssue(projectId: string) {
  return useMutation({
    mutationFn: …,
    onMutate: async (v) => {
      await qc.cancelQueries({ queryKey: keys.backlogRoot(projectId) })
      const snapshot = qc.getQueriesData({ queryKey: keys.backlogRoot(projectId) })
      qc.setQueriesData({ queryKey: keys.backlogRoot(projectId) },
                        (rows) => reorder(rows, v))
      return { snapshot }
    },
    onError: (_e, _v, context) => {
      // The snap-back. tcmsFaults.rank('conflict') is what proves it runs.
      context?.snapshot.forEach(([key, data]) => qc.setQueryData(key, data))
    },
    onSuccess: (result) => {
      if (result.rebalanced) qc.invalidateQueries({ queryKey: keys.backlogRoot(projectId) })
    },
    onSettled: () => qc.invalidateQueries({ queryKey: keys.backlogRoot(projectId) }),
  })
}
```

Every call carries the per-endpoint-class timeout budget the module already threads through.

- [ ] **Step 4: Extend `cardMove.ts` — a second entry point, not a second file**

```ts
/**
 * A column of a **project** board — a `board_column` row, which is not what {@link BoardColumn} is.
 *
 * The difference is the whole of D-b. `BoardColumn` is assembled from the cards, because for the
 * two cross-project boards no single request answers "every status on this board". A project board
 * has such a request — `GET /boards/{id}` — and its answer includes columns holding statuses **no
 * card currently has**, which `columnsFor` structurally cannot produce.
 *
 * So both live here. `columnsFor` and {@link resolveCardMove} keep their callers and are untouched;
 * this is the second entry point, and a fork would have meant two copies of the one part of
 * drag-and-drop that can be wrong.
 */
export interface ServerColumn {
  id: string
  name: string
  position: number
  wipLimit: number | null
  statusIds: readonly string[]
  defaultStatusId: string
}

export type ColumnMove =
  | { outcome: 'NONE' }
  | { outcome: 'REFUSED'; reason: string }
  | { outcome: 'MOVE'; toColumnId: string }

/**
 * Where a finished drag on a project board lands.
 *
 * Four outcomes, and three of them are not moves — the same three {@link resolveCardMove} has, for
 * the same reasons. The fourth differs: a card is legal in a column when the column claims **any**
 * status the card may transition to, because the column's default is what the drop will mean and
 * the server picks it. Asking only about the default would refuse a drop into a coarse column whose
 * default is unreachable from here but one of whose other claims is not.
 *
 * **A card already in a status the target column claims is `NONE`, not `MOVE`.** Dragging In Review
 * into a coarse "In flight" that already contains it is not a move, and sending one would write the
 * column's default and silently walk the card backwards to In Progress. The server refuses to do
 * that too (T4) — this is the client half of the same rule, so the gesture does nothing visible
 * instead of round-tripping to be told nothing happened.
 */
export function resolveColumnMove(
  card: BoardIssueCard | null | undefined,
  over: string | number | null | undefined,
  columns: readonly ServerColumn[],
): ColumnMove {
  if (!card) return { outcome: 'NONE' }
  if (over === null || over === undefined) return { outcome: 'NONE' }

  const target = columns.find((column) => column.id === String(over))
  if (target === undefined) return { outcome: 'NONE' }
  if (target.id === card.columnId) return { outcome: 'NONE' }
  if (target.statusIds.includes(card.status.id)) return { outcome: 'NONE' }

  const reachable = card.allowedTransitions.some((s) => target.statusIds.includes(s.id))
  if (!reachable) return { outcome: 'REFUSED', reason: columnRefusalFor(card, target) }

  return { outcome: 'MOVE', toColumnId: target.id }
}

/**
 * Why a project-board drop was refused.
 *
 * Separate from `refusalFor`, which answers for the composite board and names derived sources —
 * **every card here is an issue with a real key and none of them can be derived**, so those
 * sentences would be unreachable and one of them would eventually be shown wrongly.
 */
export function columnRefusalFor(card: BoardIssueCard, target: ServerColumn): string {
  if (card.allowedTransitions.length === 0) {
    return `${card.title} can't be moved from the board. An epic's status is derived from its `
      + `stories — move those and this follows.`
  }
  return `${card.key} can't move to ${target.name} from ${card.status.name}. `
    + `The project's workflow decides which moves exist.`
}
```

- [ ] **Step 5: Extend `boardKeyboard.ts` for swimlanes**

```ts
/**
 * Left/right still jumps a whole column. **Up/down now jumps a whole swimlane** when the board has
 * more than one, instead of the stock 25-pixel nudge.
 *
 * The stock getter's 25px is what the original comment here calls "technically keyboard accessible
 * and not usable" — across a four-column board it is forty presses. On a board with eight epic
 * lanes it is worse in the other axis, and unlike columns the lanes are tall and variable, so the
 * count is unpredictable as well as large.
 *
 * With one lane, up/down keeps the stock nudge: it is then fine positioning within a column, which
 * is what it is good at.
 */
export function boardKeyboardCoordinates(
  columnOrder: readonly string[],
  laneOrder: readonly string[] = [],
): KeyboardCoordinateGetter
```

The existing single-argument callers keep working — `laneOrder` defaults to empty, and an empty lane
list is the "one lane" case.

- [ ] **Step 6: Extend the two test files**

```ts
describe('resolveColumnMove', () => {
  it('allows a drop into a coarse column when ANY claimed status is reachable', () => {
    // The stakeholder board's "In flight" claims In Progress + In Review + Blocked.
    // A card in Ready may reach In Progress but not Blocked. Asking only about the
    // column's DEFAULT would be right here by luck; asking about all three is right
    // on purpose, and this is the case that tells them apart.
    const card = issueCard({ status: ready, allowedTransitions: [inProgress] })
    expect(resolveColumnMove(card, 'in-flight', [inFlightColumn]))
      .toEqual({ outcome: 'MOVE', toColumnId: 'in-flight' })
  })

  it('does nothing when the target column already claims the card’s status', () => {
    // NOT a move. Sending one would write the column's default and demote In Review
    // to In Progress — silently, and looking like the board undid a drag.
    const card = issueCard({ status: inReview, allowedTransitions: [done] })
    expect(resolveColumnMove(card, 'in-flight', [inFlightColumn]))
      .toEqual({ outcome: 'NONE' })
  })

  it('refuses with the workflow’s sentence when nothing claimed is reachable', () => {
    const card = issueCard({ status: backlog, allowedTransitions: [ready] })
    const move = resolveColumnMove(card, 'done', [doneColumn])
    expect(move.outcome).toBe('REFUSED')
    expect(move.reason).toContain("workflow decides")
  })

  it('tells an epic its status comes from its stories', () => {
    const epic = issueCard({ typeLevel: 'EPIC', allowedTransitions: [] })
    expect(resolveColumnMove(epic, 'done', [doneColumn]).reason)
      .toContain('derived from its stories')
  })
})
```

Add to `boardKeyboard.test.ts` a case proving up/down crosses a lane with lanes present, and keeps
the 25px nudge with none.

- [ ] **Step 7: Write the query test — the rollback is the point**

Use `createTestQueryClient`. **Never build your own `QueryClient`** — only that one has the caches.
A test whose render trips an error boundary fails unless it calls `drivingAFailedWrite()` and says
why.

```tsx
it('snaps the row back and says why when the rank write conflicts', async () => {
  drivingAFailedWrite('a 409 from PUT /issues/{key}/rank is the concurrent-neighbour case, and '
    + 'the optimistic patch must be reverted rather than left showing an order the server refused')
  …
  expect(order()).toEqual(['ACME-1', 'ACME-2', 'ACME-3'])   // as it was before the drag
  expect(await screen.findByText(/moved by somebody else/i)).toBeInTheDocument()
})

it('drops the optimistic order rather than keeping it when the server rebalanced', async () => {
  // After a respace every other row's rank is stale, so the patch is no longer a
  // prediction of anything. Keeping it shows an order the server does not have.
  …
})
```

- [ ] **Step 8: Run the gate on shanks and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'
```
`npm run build` is `tsc -b` + `vite build`, **not** `tsc --noEmit` — the latter has reported 0 errors
while the build failed with nine.

```bash
git add frontend/src/lib/devFaults.ts frontend/src/api/queries/keys.ts \
        frontend/src/api/queries/projectBoards.ts frontend/src/api/queries/index.ts \
        frontend/src/features/board/cardMove.ts frontend/src/features/board/cardMove.test.ts \
        frontend/src/features/board/boardKeyboard.ts \
        frontend/src/features/board/boardKeyboard.test.ts \
        frontend/src/api/queries/projectBoards.test.tsx frontend/src/lib/labels.ts
git commit -m "feat(board): the fault switch, the board queries and the move decision

The switch before the feature it proves: tcmsFaults.rank('conflict') and
.wip(). cardMove.ts gains resolveColumnMove for server-defined columns and
keeps columnsFor for the composite board — extended, not forked."
```

**Out of scope:** nothing renders. `?view=board` still shows M1's placeholder panel until T7.

---

## T7 — the project board

**Deliverable:** `/projects/:projectId/work?view=board` renders `work-board.html` — swimlanes by
epic with a collapsible roll-up, the dense card face, a WIP breach in the column header, and a
`⋮ Move to…` on every card — and the whole board is operable from the keyboard alone.
**Stack:** `fe`
**Est. context:** ~145k — `BoardPage.tsx` (38 kB) and `WorkTablePage.tsx` read as patterns, 5
components created, 2 test files, 3 skills. **One deliverable, one stack, green at the boundary —
large because the screen is, not because two things were merged.**
**Depends on:** T6

**Files:**
- Create: `frontend/src/features/board/ProjectBoardView.tsx`, `Swimlane.tsx`, `BoardCardFace.tsx`,
  `ColumnHeader.tsx`, `MoveToMenu.tsx`
- Modify: `frontend/src/features/tracker/WorkTablePage.tsx` (the `board` entry in `VIEWS`)
- Test: `frontend/src/features/board/ProjectBoardView.test.tsx`,
  `frontend/src/features/board/MoveToMenu.test.tsx`

- [ ] **Step 1: Read the two patterns, and the mockup**

- `docs/mockups/work-board.html` — **the source of truth for the screen.** Every card has a
  `⋮ Move to…`; chip rows wrap; the board scrolls inside itself; the collapsed lane keeps its
  percentage and says *"Nothing is hidden from the counts — only from the screen"*; the "No epic"
  lane is last and says *"a story without an epic is a normal story, not an error"*.
- `frontend/src/features/board/BoardPage.tsx` — the composite board: how `@dnd-kit` is wired here,
  where `announce` comes from, how a refusal is surfaced. **Do not copy it into the new file** —
  read it for the wiring and share the decision through `cardMove.ts`.
- `frontend/src/features/tracker/WorkTablePage.tsx:70` — `VIEWS`, whose `board` entry is the
  placeholder this task replaces.

- [ ] **Step 2: Replace the `VIEWS` placeholder**

`VIEWS[0]` currently carries `module: 'M2'` and a sentence about the board arriving with the
delivery module. Change it to `{ id: 'board', label: 'Board', module: null, body: '', existing: null }`
and render `<ProjectBoardView />` for it. **Leave the other three placeholders alone** — Timeline and
Calendar are M3's, and Backlog is T8's.

- [ ] **Step 3: Write `ColumnHeader`, and get the breach right**

```tsx
/**
 * A column header: name, card count, and the WIP state.
 *
 * **The breach is reported, never enforced** (story B3) — the mockup states the rule on the screen:
 * *"The drop was accepted; the breach is the column's to show, not the card's to refuse."*
 *
 * Three states, and the middle one matters: no limit renders `—`, a limit under or at renders
 * `2 / 4`, and a limit exceeded renders `4 / 3 ⚠` with the warning styling. A column at exactly its
 * limit is **not** in breach; off-by-one here would put a permanent warning on any team that
 * actually works to its limit, and a warning that is always on is one nobody reads.
 */
export function ColumnHeader({ column, count, breach }: Props) {
  return (
    <div className="flex items-center gap-2 border-b border-line px-2 py-1.5">
      <span className="text-[12.5px] font-semibold">{column.name}</span>
      <span className="rounded bg-surface-2 px-1 text-[11px] text-ink-3">{count}</span>
      <span className="ml-auto">
        {column.wipLimit === null ? (
          <span className="text-[11px] text-ink-4">—</span>
        ) : breach ? (
          <span
            className="rounded bg-warn-soft px-1 text-[11px] font-medium text-warn"
            title="Work-in-progress limit exceeded"
          >
            {count} / {column.wipLimit} ⚠
          </span>
        ) : (
          <span className="text-[11px] text-ink-3">{count} / {column.wipLimit}</span>
        )}
      </span>
    </div>
  )
}
```

- [ ] **Step 4: Write `MoveToMenu` — the affordance WCAG 2.2 requires**

```tsx
/**
 * The `⋮ Move to…` every card carries.
 *
 * **WCAG 2.2 Dragging Movements (2.5.7)**, and story B3's own non-functional requirement: *"Every
 * board action is reachable from the keyboard, including moving a card between columns."* The
 * mockup makes the same point in prose on the board itself — *"Dragging is never the only way"* —
 * and adds the second audience the criterion does not name: **this is also what a touch user gets.**
 *
 * Every column is listed, and the illegal ones are **disabled with the reason on them** rather than
 * hidden (§31's disable-don't-hide). A hidden control reads as "you may not", and a workflow that
 * has no In Progress → Done transition is configuration, not a permission.
 */
export function MoveToMenu({ card, columns, onMove }: Props) {
  const immobile = card.allowedTransitions.length === 0
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label={`Move ${card.key}`} disabled={immobile}>
          ⋮
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent>
        {columns.map((column) => {
          const move = resolveColumnMove(card, column.id, columns)
          return (
            <DropdownMenuItem
              key={column.id}
              disabled={move.outcome !== 'MOVE'}
              onSelect={() => onMove(column.id)}
            >
              {column.name}
              {move.outcome === 'REFUSED' && (
                <span className="ml-2 text-[11px] text-ink-4">{move.reason}</span>
              )}
            </DropdownMenuItem>
          )
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
```

**Both affordances end in `resolveColumnMove`**, which is why a refusal reads identically however it
was attempted — the reason that function is a module and not a handler.

- [ ] **Step 5: Write `BoardCardFace`**

The mockup's card face, and §12.2's *"design the card face deliberately rather than letting it
accrete"*: type icon · key · `⋮` / title / labels · due date · sub-task badge · estimate · avatar.

```tsx
/**
 * What is on this card, and what is deliberately not.
 *
 * **Not here, and each has a module:** the `AI` tag (`issue.agent_eligible`, D30/M5), the `PR #482`
 * badge (M5's forge adapter), and logged hours (M6). The mockup draws all three because it draws
 * the finished product; drawing them now would need fields nothing writes (D-f).
 *
 * `countsTowardCompletion: false` renders "not in %" — a Spike is on the board and out of the
 * percentage, and a card that silently did not count would make the meter unexplainable.
 */
```

Chips wrap (`flex-wrap`), per the mockup. Anything monospace goes through `Identifier`; the status
label comes from `lib/labels.ts`; the due-date classes are `due-soon` / `due-over` driven by the
card's own `overdue` — computed server-side at read time, never stored, because a stored flag needs a
job to flip it and is wrong for a day at a time.

- [ ] **Step 6: Write `Swimlane`**

```tsx
/**
 * One lane — story B3's second and third criteria.
 *
 * **Collapsing hides the cards and keeps the number.** The mockup says so on the screen: *"Collapsed
 * to a single roll-up. Nothing is hidden from the counts — only from the screen."* The percentage
 * comes from the lane's own `completion`, which the server computed over every card in it, so a
 * collapsed lane and an expanded one cannot disagree.
 *
 * The **"No epic" lane is last and is not an error** — the mockup states it: *"a story without an
 * epic is a normal story"*. Sorting it first, or styling it as a warning, would make a normal state
 * look like a backlog of mistakes.
 *
 * Collapse state is per viewer (`board_swimlane`), not per board: collapsing a lane is not editing
 * the board, and a shared collapse would mean one person's tidying hides work from everybody else.
 */
```

The lane header carries the completion meter and its **basis** — `by estimate` or `by count · 4 of
10` — because `IssueCompletion.basis` is on the wire deliberately and a percentage that changes
meaning silently is D8's named failure.

- [ ] **Step 7: Write `ProjectBoardView`**

Owns the board picker, the grouping control, the `@dnd-kit` context, and the `DataRegion`
loading/loaded/failed/empty/stale matrix. Filter comes from the URL (T8 extracts the shared hook;
here it is read the way `WorkTablePage` already reads it).

```tsx
  /*
    The drag ends here, and the decision is `resolveColumnMove`'s so it can be asked directly —
    driving a real pointer drag through jsdom exercises @dnd-kit, not this.

    NOT OPTIMISTIC, and that is §12.4 verbatim (D-c): the legal set is on the card, so an illegal
    drop is refused here, before a request. There is nothing an optimistic patch buys except the
    accept-then-revert §12.4 rules out — the rank drag in T8 is the one that earns it, because
    there the legality is the server's neighbours and the client cannot know it.
  */
  const onDragEnd = ({ active, over }: DragEndEvent) => {
    const card = cards.find((c) => c.key === active.id)
    const move = resolveColumnMove(card, over?.id, columns)
    if (move.outcome === 'NONE') return
    if (move.outcome === 'REFUSED') { setRejected(move.reason); announce(move.reason); return }
    moveCard.mutate({ key: card!.key, toColumnId: move.toColumnId })
  }
```

- [ ] **Step 8: Write the tests**

```tsx
it('shows a WIP breach in the header and leaves the card in the column', async () => {
  // Story B3: "the drop is accepted and the column header shows the breach".
  // A test that asserted only the warning would pass on a board that refused the
  // drop, which is the opposite behaviour.
  …
  expect(within(inProgress()).getByText('4 / 3 ⚠')).toBeInTheDocument()
  expect(within(inProgress()).getByText('ACME-124')).toBeInTheDocument()
})

it('offers every column in ⋮ Move to…, disabling the ones the workflow forbids', async () => {
  await user.click(screen.getByRole('button', { name: 'Move ACME-201' }))
  expect(screen.getByRole('menuitem', { name: /Ready/ })).toBeEnabled()
  expect(screen.getByRole('menuitem', { name: /Done/ })).toBeDisabled()
})

it('moves a card between columns with the keyboard alone', async () => {
  // The non-functional requirement, tested through the MENU rather than through a
  // simulated dnd-kit keyboard drag: the menu is the path a real keyboard user
  // takes, and a jsdom drag would assert @dnd-kit's behaviour instead of ours.
  await user.tab(); await user.tab()
  await user.keyboard('{Enter}')
  await user.keyboard('{ArrowDown}{Enter}')
  expect(await screen.findByText(/Moved/)).toBeInTheDocument()
})

it('keeps a collapsed lane’s percentage on screen', async () => {
  await user.click(screen.getByRole('button', { name: /Collapse/ }))
  expect(screen.queryByText('ACME-201')).not.toBeInTheDocument()
  expect(screen.getByText('70%')).toBeInTheDocument()
})

it('puts the no-epic lane last and does not style it as a problem', async () => {
  const lanes = screen.getAllByRole('region', { name: /lane/i })
  expect(lanes.at(-1)).toHaveTextContent('No epic')
})
```

- [ ] **Step 9: Run the gate on shanks and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'
```

```bash
git add frontend/src/features/board/ProjectBoardView.tsx \
        frontend/src/features/board/Swimlane.tsx \
        frontend/src/features/board/BoardCardFace.tsx \
        frontend/src/features/board/ColumnHeader.tsx \
        frontend/src/features/board/MoveToMenu.tsx \
        frontend/src/features/board/ProjectBoardView.test.tsx \
        frontend/src/features/board/MoveToMenu.test.tsx \
        frontend/src/features/tracker/WorkTablePage.tsx
git commit -m "feat(board): the project board — swimlanes, WIP breaches, keyboard parity

Every drag has a ⋮ Move to… equivalent (WCAG 2.2 Dragging Movements). The
breach is reported in the column header and the drop still lands."
```

---

## T8 — the backlog and one filter

**Deliverable:** `?view=backlog` renders `work-backlog.html` as one ranked list where every row has
an `↑ ↓` pair and a drag handle, both persisting; and a filter set on Board is still applied after
switching to Table or Backlog, and is shareable as a URL.
**Stack:** `fe`
**Est. context:** ~120k — `WorkTablePage.tsx` read and modified, 2 components created, 1 hook, 3
test files, 3 skills
**Depends on:** T7

**Files:**
- Create: `frontend/src/features/board/BacklogView.tsx`, `frontend/src/features/board/useWorkFilter.ts`
- Modify: `frontend/src/features/tracker/WorkTablePage.tsx`,
  `frontend/src/features/board/ProjectBoardView.tsx`
- Test: `frontend/src/features/board/BacklogView.test.tsx`,
  `frontend/src/features/board/useWorkFilter.test.tsx`

- [ ] **Step 1: Extract `useWorkFilter` from `WorkTablePage`**

`WorkTablePage.tsx:148` already reads the filter out of the URL on every render *"rather than
mirrored into state"*, with D34's reasoning in the comment. Three views now need it, so it moves
into a hook — **the logic does not change**, and the comment moves with it.

```ts
/**
 * D34's one filter, for all five views.
 *
 * **In the URL, never in a store and never in `useState`.** Two views reading one filter cannot be
 * a `useState` in either of them, and a store would be server-state's filter living somewhere the
 * URL cannot restore from a pasted link. This hook is the only reader and the only writer.
 *
 * M2 adds `labelId` and `priority` to what M1 read. Both are repeated parameters and both are
 * **OR within themselves, AND against the others** — Trello's semantics, copied precisely because
 * they are learnable (§12.2).
 *
 * `view`, `sort`, `cols` and `boardId` are deliberately NOT part of this. They are which view you
 * are looking at, not which work you are looking at, and folding them in would make a filter object
 * that changes when you switch tabs — which would evict every cached list on a tab switch.
 */
export function useWorkFilter(): [WorkFilters, (mutate: (p: URLSearchParams) => void) => void]
```

- [ ] **Step 2: Point all three views at it**

`WorkTablePage` replaces its inline `useMemo`; `ProjectBoardView` and `BacklogView` call the hook.
**One `keys` factory, and the filter object is the key** — so switching from Board to Table with the
same filter is a cache hit on a *different* key with the same filter values, which is correct: they
are different resources.

- [ ] **Step 3: Write `BacklogView`**

```tsx
/**
 * The ranked backlog — story B4.
 *
 * **One list, not the mockup's release sections** (D-f). `work-backlog.html` groups under "2.4 ·
 * target 18 Sep" and "Unscheduled"; `release` is M3's table and `issue_fact.release_id` is NULL
 * until it lands, so the grouping would be one section called "Unscheduled" containing everything —
 * which looks like a defect rather than an unbuilt feature. M3 adds it, and the "Move to release"
 * bulk action with it.
 *
 * **Two ways to reorder and both persist**, which the mockup states on the panel: *"drag the ⠿
 * handle, or use ↑ / ↓ — both persist"*. The arrows are not a fallback: they are the better
 * keyboard path, and they end in the same mutation the drag does.
 *
 * The row's number is `position` from the server, derived from rank order and never stored — a
 * stored position is what the fractional rank exists to avoid (D-d).
 */
```

Bulk selection ships with **Set epic** and **Assign** only, both of which are M1 fields. The panel
header renders the mockup's "N selected" bar.

- [ ] **Step 4: Wire the `↑ ↓` pair to the same mutation as the drag**

```tsx
  /*
    Both affordances end in `useRankIssue`, so the optimistic patch, the snap-back and the
    rebalance handling are one implementation. An arrow that took a different path would be the
    keyboard route quietly not getting the rollback — which is exactly the class of defect
    "keyboard parity" is supposed to prevent, arriving through the fix for it.

    Moving up = place BEFORE the row above. Moving down = place AFTER the row below. Not "swap
    ranks": a swap works for adjacent rows and silently does the wrong thing the first time
    somebody adds a multi-row move.
  */
  const moveUp = (row: BacklogRow, index: number) => {
    if (index === 0) return
    rank.mutate({ key: row.key, beforeKey: rows[index - 1].key })
  }
```

- [ ] **Step 5: Test the criterion the module is closed on**

```tsx
it('keeps a filter set on Board when the view switches to Table', async () => {
  // D34's whole point, and the M2 done-criterion. Asserted through the URL as well
  // as the screen, because "shareable as a URL" is the half a component test that
  // only checked the chips would miss.
  render(<WorkTablePage />, { route: '/projects/p1/work?view=board&labelId=l1&assigneeId=u1' })
  await user.click(screen.getByRole('link', { name: 'Table' }))

  expect(window.location.search).toContain('labelId=l1')
  expect(window.location.search).toContain('assigneeId=u1')
  expect(screen.getByText(/checkout/)).toBeInTheDocument()
})

it('ranks a row from the keyboard, and it persists', async () => {
  await user.click(screen.getByRole('button', { name: 'Move ACME-122 up' }))
  await waitFor(() => expect(order()).toEqual(['ACME-122', 'ACME-121', 'ACME-131']))
  expect(fetchMock).toHaveBeenCalledWith(
    expect.stringContaining('/issues/ACME-122/rank'),
    expect.objectContaining({ method: 'PUT' }),
  )
})

it('snaps a dragged row back when the server refuses the rank', async () => {
  drivingAFailedWrite('the 409 tcmsFaults.rank("conflict") fabricates is the concurrent-neighbour '
    + 'case, and the optimistic patch must revert rather than leave an order the server refused')
  …
})

it('does not offer Move to release, which is M3’s', () => {
  expect(screen.queryByRole('button', { name: /Move to release/ })).not.toBeInTheDocument()
})
```

- [ ] **Step 6: Run the gate on shanks and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'
```

```bash
git add frontend/src/features/board/BacklogView.tsx \
        frontend/src/features/board/useWorkFilter.ts \
        frontend/src/features/board/BacklogView.test.tsx \
        frontend/src/features/board/useWorkFilter.test.tsx \
        frontend/src/features/board/ProjectBoardView.tsx \
        frontend/src/features/tracker/WorkTablePage.tsx
git commit -m "feat(board): the ranked backlog, and one filter across every view

The filter moves out of WorkTablePage into useWorkFilter, still in the URL
and still never in a store. ↑ ↓ and the drag handle end in one mutation, so
the keyboard route gets the same rollback."
```

---

## T9 — board settings and the close-out

**Deliverable:** `/projects/:projectId/settings/boards` renders `settings-boards.html` — both boards,
their column→status claims, the coverage table and its check — and a status added to the workflow is
visible in its auto-claimed column and movable from there; `docs/features.md`, the MCP surface
document and 009's status table are closed out.
**Stack:** `fe`
**Est. context:** ~100k — 2 components created, 2 test files, 5 documents modified, 2 skills
**Depends on:** T8

**Files:**
- Create: `frontend/src/features/settings/BoardSettingsPage.tsx`,
  `frontend/src/features/settings/ColumnEditor.tsx`
- Modify: `frontend/src/app/router.tsx`, `frontend/src/features/settings/` (the settings subnav)
- Test: `frontend/src/features/settings/BoardSettingsPage.test.tsx`
- Docs: `docs/features.md`, `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`,
  `docs/plans/009-project-management-expansion-modules.md`, `docs/plans/012-board-and-backlog.md`

- [ ] **Step 1: Add the route and the subnav entry**

`settings-boards.html`'s subnav is `General · Workflow · **Boards** · Repositories · Imports`.
Repositories is M5's and Imports is M4's — **add only Boards**, at the mockup's position.

- [ ] **Step 2: Write `ColumnEditor`**

```tsx
/**
 * One board's columns and what each claims.
 *
 * **The coverage check is the screen's point**, and the mockup renders it as a table with a green
 * footer: *"Every status is claimed by exactly one column on every board. A status claimed twice,
 * or not at all, is what this check exists to catch."* So the table is not decoration — it is the
 * only place a person can see the invariant holding, and it renders **red** when it does not,
 * naming the status, rather than letting the save fail with a 422 the reader has to decode.
 *
 * The default-status control appears on a column claiming **more than one** status and is hidden on
 * a column claiming one, where it has exactly one legal value. The mockup shows it only on the
 * stakeholder board for the same reason, with the team board's footer explaining it: *"One status
 * per column here, so a drop has no ambiguity about which status it means."*
 */
```

- [ ] **Step 3: Write `BoardSettingsPage`**

Both boards, each as a panel with **Open** and **Delete**; the coverage table below. Delete asks, and
says a board is a view — *"delete a board and no issue is lost"* — because the destructive-sounding
word is the one thing on this screen a person will hesitate over, and the answer is on the mockup.

The last board's Delete is **disabled with the reason on it**, matching the server's 422 rather than
discovering it: `ui-rules` — never render an action the backend will always reject.

- [ ] **Step 4: Test the R13 criterion**

```tsx
it('shows a newly added workflow status in its auto-claimed column, and lets it move', async () => {
  // 009 review R13, and the module's fifth done-criterion. The claim happens
  // server-side inside the workflow write (D-g); this asserts the screen SHOWS it
  // where it landed — an auto-claim nobody can see is one nobody can correct.
  render(<BoardSettingsPage />, { route: '/projects/p1/settings/boards' })

  expect(within(column('In Progress')).getByText('Awaiting QA')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /Move Awaiting QA/ }))
  await user.click(screen.getByRole('menuitem', { name: 'In Review' }))
  expect(within(column('In Review')).getByText('Awaiting QA')).toBeInTheDocument()
})

it('shows the coverage check failing, by status name, before the save is attempted', async () => {
  await user.click(screen.getByRole('button', { name: /Remove Blocked from In flight/ }))
  expect(screen.getByText(/No column claims: Blocked/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
})

it('disables Delete on the project’s only board and says why', () => {
  expect(screen.getByRole('button', { name: /Delete/ })).toBeDisabled()
  expect(screen.getByText(/keeps at least one board/)).toBeInTheDocument()
})
```

- [ ] **Step 5: Run every gate on shanks**

All three, because this is the last task in the module:

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend './gradlew build'
~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'
~/bin/tcms-remote packages 'npm run build && npm run lint --workspaces && npm run test:run --workspaces'
```

- [ ] **Step 6: Rewrite the `docs/features.md` section**

Struck through and superseded, **never deleted** — CLAUDE.md: *"write superseded decisions and their
reversal into the docs, so the next reader doesn't think the old reasoning was never considered."*
The board section currently describes the composite board as the only board; it becomes two, with
D-a's table saying which is which.

- [ ] **Step 7: Close 009 per the hand-off protocol** — its own commit, staging only that file

1. **Status table** — M2's row to `done 2026-08-31 @ <sha>`.
2. **Built and unverified** — the list from the section below, as it actually stands after the gates.
3. **The migration actually taken** — `~~V4~~ → V5` in M2's row **and** in its `**db**` line, struck
   through rather than replaced. **And correct every row below it**: M1 consumed `V4` as well as
   `V3`, so M3 `~~V5~~ → V6`, M5 `~~V6~~ → V7`, M4 `~~V7~~ → V8`, Mc `~~V8~~ → V9`,
   M7 `~~V9~~ → V10`, M6 `~~V10~~ → V11`, M8 `~~V11~~ → V12`. Add one line to *"The migration
   versions are a reservation, not a fact"* saying that this is the second time the column moved and
   that the directory remains the authority.
4. **Rewrite the dependent prompts** — M3's `Depends on` names M2, so its prompt block is rewritten
   with: the endpoints as they landed; that `SwimlaneBy` gains `RELEASE` and `lib/labels.ts` makes
   that a compile error until labelled; that the backlog's release grouping and the "Move to release"
   bulk action are M3's to add to `BacklogView.tsx`; that `issue.rank` exists and is project-wide, so
   a release-filtered backlog is a filter over one order and not a second one; and the `V` it takes.
   **M4's prompt is also rewritten** — `issue_label` now exists, so the Jira importer has somewhere
   to put labels, and label-per-sprint is on offer as an import mapping. M4's `Depends on` is M1, so
   this is a courtesy edit; say so in the commit message.
5. **Correct the two claims in the M2 prompt that this module found to be false** — that the
   migration is `V4`, and that the derived-card refusal is a regression to restore. Edit in place, no
   changelog.
6. **Commit** — `docs(plan): M2 done`, staging only `009-project-management-expansion-modules.md`.

- [ ] **Step 8: Commit the code, then the plan close-out, then 009**

```bash
git add frontend/src/features/settings/BoardSettingsPage.tsx \
        frontend/src/features/settings/ColumnEditor.tsx \
        frontend/src/features/settings/BoardSettingsPage.test.tsx \
        frontend/src/app/router.tsx docs/features.md \
        docs/specs/2026-08-08-mcp-tool-and-command-surface.md
git commit -m "feat(board): board settings, and the M2 close-out

The coverage table is where a person sees 'every status claimed by exactly
one column' holding — and where they see it failing, by name, before a save
is attempted rather than as a 422 to decode."

git add docs/plans/012-board-and-backlog.md
git commit -m "docs(plan): 012 built-and-unverified list"

git add docs/plans/009-project-management-expansion-modules.md
git commit -m "docs(plan): M2 done

V5, not the reserved V4 — M1 took two versions. Every V below M2 shifts by
one. M3's prompt rewritten; M4's amended for issue_label."
```

---

## Built and unverified

**Filled in by T9 against what actually happened. Written here now as the list to check, not as a
claim that any of it is true** — the gates prove it compiles, and this repository has shipped a
no-op theme toggle, two contradictory percentages on one screen and a provider-ordering bug that
rendered nothing but the error page, all of which typechecked and linted clean.

- [ ] **The ten operations describe a server nobody has run.** T1's gate is the packages build and
      the contract suites; they prove the document parses, that both clients generate from it, that
      every object schema carries `required`, that no operationId is duplicated and that no response
      property is credential-shaped. **Not one of them establishes that a handler could serve any of
      these shapes** — the first task that finds out is T3, and the first that finds out about the
      cards, the rank and the labels is T4. → **five of the ten are answered.** T3's
      `BoardConfigEndpointTest` drives all five configuration operations through MockMvc and gets
      the declared statuses back, 422 included and as `application/problem+json`. The other five are
      still this bullet.
- [ ] **A board created, renamed or re-columned by a person.** T3 proves the five operations answer
      and that a rotation keeps every column's id through the position-parking pass (D-o), but
      `settings-boards.html` is T9 — nobody has submitted one of these bodies from a form, and the
      one thing a form does that a test fixture does not is send a column list somebody built by
      hand.
- [ ] **A folded column's WIP limit round-tripping.** `wipLimit` is written, stored and served, and
      `null` means no limit; what nothing has done is set one and look at it. The breach arithmetic
      is T4's and the header that shows it is T7's, so the column is currently a number with no
      reader outside its own test.
- [ ] `V5` applied to an instance that **already holds issues** — the property Testcontainers
      structurally cannot show, because it always builds from empty, and the one the `rank` backfill
      and the `NOT NULL` that follows it carry the most risk on. T2 exercised the loop by hand
      against a throwaway Postgres 17 with `V1`–`V5` applied and four issues whose `rank` had been
      cleared: it numbered them `1024, 2048, 3072, 4096` in `created_at` order under `tcms_owner`
      with the GUC set per company. **That is not the same as the lab instance's own data**, and the
      backfill is the one statement in `V5` that cannot be re-run if it is wrong.
- [ ] The seeded board **seen on a screen**. `BoardSchemaTest` proves six columns each claiming one
      status exist in the database; nothing has rendered them, and nothing will until T7.
- [ ] A status retired through `PUT /projects/{id}/workflow` on a project whose board has a **folded**
      column. `BoardSchemaTest` builds that shape by hand and drives the delete straight at
      `issue_status`; the endpoint path has only been exercised against the seeded
      one-status-per-column board, which is the easy half of D-l.
- [ ] A card dragged on the seeded per-status board, then the same project's coarse board opened and
      the card found in the matching column. This is the module's headline criterion and a passing
      `BoardMoveServiceTest` is not the same as having seen it.
- [ ] A WIP limit actually breached by dragging a fourth card, rather than by `tcmsFaults.wip()`.
- [ ] A rank drag interrupted by a real concurrent edit, rather than by `tcmsFaults.rank('conflict')`.
- [ ] The whole board operated with **no pointer at all** — tab to a card, `⋮`, move, and back —
      which is the criterion, and which a component test asserting the menu's `disabled` attribute
      does not establish.
- [ ] A status added on `settings-workflow` and then seen on `settings-boards` in its auto-claimed
      column, on **both** boards.
- [ ] A rebalance actually triggered — 40+ squeezes against one neighbour — and the client's order
      seen to be correct after it.
- [ ] Whether the collapsed-lane percentage and the expanded lane's cards agree on a project with a
      Spike in it. Two contradictory percentages on one screen is a defect this repository has
      shipped before, and `countsTowardCompletion` is exactly where it would come from again.

---

## Self-review

**Spec coverage.** Story B2 → T3 (claims, default status, delete loses nothing) and T9 (the screen).
B3 → T7 (card face, swimlanes, WIP, keyboard) and T4 (the breach). B3's filter criteria → T8. B4 →
T4 (rank) and T8 (the screen), **minus** its release criteria, which D-f defers to M3 explicitly.
D2 → T1's `defaultStatusId` and T4's move. D34 → T8. §12.2's label model → T2/T4. §12.4 → D-c and
T6. Review R13 → T3's `claimNewStatus` and T9's test. The module tax: contract T1, both clients T1,
`contractBodies` T1, permissions **none needed** (D-e), frontend kit T7–T9, one `keys` factory T6,
fault switch T6, CLI+MCP T5, gates every task, `docs/features.md` T9.

**Order.** Every `Depends on` points backwards. T1 needs nothing. T2 needs the contract it serves.
T3 needs the tables. T4 needs T3's columns. T5, T6 need T4's endpoints. T7 needs T6's logic. T8
needs T7's view to switch away from. T9 needs T8.

**Stack.** T1 `ts`, T2 `data`, T3 `be`, T4 `be`, T5 `ts`, T6–T9 `fe`. No task's `Files` list spans
the backend and frontend trees. T1's list includes two *generated* frontend/packages files and no
hand-written frontend code.

**Sizing.** Every task is 95k–145k, inside the 80k–150k band. T7 at ~145k is the largest and is one
deliverable — its natural split point would be "the card face separately from the lanes", which was
rejected: the two are one screen and splitting them leaves a task boundary at which the board
renders cards in no lanes, which is not a green-tree state anyone would want to stop at.

**Known gap, stated rather than left to be found.** Nothing a person can use exists until **T7** —
T3, T4 and T5 ship an API and verbs with no screen. That is 009's rule 6 working as intended, and it
is the reason T3's and T4's `Out of scope` say so on the task rather than in a footnote.
