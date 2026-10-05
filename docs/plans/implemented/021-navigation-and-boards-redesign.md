# Navigation & boards redesign Implementation Plan

**Status (filed 2026-10-05):** Delivered — T1 `3b8c9891` through T8 `f9b0b01b` (2026-09-06 → 2026-09-07); every gate green on shanks. Unverified: nothing here has been operated in a browser — the long list is *Built but unverified*, below. The shell was later replaced by [023](023-one-design-language.md). Any `Status`, task table or checkbox below this line is the plan's history, not its state.

**Date:** 2026-09-06 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The plugin skills `CLAUDE.md`
> names are mandatory: `tooling-engineer` for T1; `/be-java` + `be-java-database-code` +
> `data-migrations` + `be-java-unit-testing` for T2–T4; `/fe-react` + `fe-react-ui-rules` +
> `fe-react-component-testing` for T5–T8. **Before any screen work, open the render it
> implements** in `docs/mockups/ia-redesign/` — those renders are the implementation guide and were
> checked pixel by pixel against the proposal; where a render and this plan disagree on what a
> screen *shows*, the render wins.

**Goal:** The work comes first. Three boards — PM · Dev · QA — over one eleven-status workflow that
applies to epics, stories and tasks alike, rowed by epic with the stories and tasks inside, with
shared columns as the hand-offs and a `Blocked` column everywhere; and a flat, usage-ordered
navigation with the boards at the top, one Settings entry, breadcrumbs in the shell, a palette
instead of a cases-only field, and Timesheets in the user menu.

**Architecture:** The contract changes first (T1) and every generated client with it. The backend
lands in three sessions over the existing `tracker` module: the schema and the board rules (T2), the
workflow semantics — stored epic status, the blocking status and its return trip (T3), then issue
search and the linked-cases summary on a card (T4). The frontend lands in four: the boards (T5), the
shell (T6), the palette / access page / breadcrumbs / button convention (T7), and the Phase-2
surfaces plus the documentation close-out (T8). **No route moves.** Every path in the brief's
inventory keeps its URL; only what leads to it changes.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · Flyway **V13** (D-a) · Postgres 17 with `FORCE ROW
LEVEL SECURITY` · React 19 · React Router 7 · TanStack Query · Tailwind v4 + the render kit ·
Vitest + RTL · `@tcms/client`, `@tcms/cli`, `@tcms/mcp`.

**Source documents:** `docs/specs/2026-09-06-navigation-ia-redesign-proposal.md` (THE spec — §1
rail, §2 boards, §3 header, §4 user menu, §5 settings, §7 palette, §8 naming/buttons/badges, §9 the
twelve reversals) · `docs/specs/2026-09-06-navigation-ia-redesign-brief.md` (why) ·
`docs/mockups/ia-redesign/` (twenty renders; `README.md` there lists the choices made while
rendering, and each is binding unless this plan says otherwise).

**Tasks** — in the order they are done:

| # | Task | Stack | Agent | Deliverable | Depends on |
|---|---|---|---|---|---|
| 1 | T1 — The contract: subset boards, coverage, stored epic status, blocking, card fields, issue search; both clients; the CLI/MCP board resolver | `ts` | `tooling-engineer` | `openapi.yaml` carries every shape in D-c…D-h; both `schema` copies regenerated; fixture factories in both copies; `boardOf` in the CLI and `tcms_read_board` resolve a board by column name; the surface doc updated. `GET …/boards/coverage` and `GET /issues/search` answer a real 404 until T2/T4 | none |
| 2 | T2 — V13, the seeded workflow and three boards, and the board rules | `be` | `be-java` | `V13__three_boards_and_blocking.sql` applied; a new project gets eleven statuses, 22 legal moves and PM/Dev/QA boards; `createBoard`/`updateBoard` accept a subset; `GET …/boards/coverage` serves the matrix; `BoardIssueCard` carries `parentKey` and `elsewhere`; `./gradlew build` green | T1 |
| 3 | T3 — Stored epic status, the blocking status, and the return trip | `be` | `be-java` | An epic transitions like a story; `Blocked` records `blocked_from_status_id` on entry and the origin is a legal target on the way out; `allowedTransitions` on an epic is non-empty; cards and issues carry `blockedFromStatus`; `./gradlew build` green | T2 |
| 4 | T4 — Issue search and the linked-cases summary on a card | `be` | `be-java` | `GET /issues/search?q=` answers issues by key or title across the caller's projects; `BoardIssueCard.linkedCases` is served from the same rows `listIssueTestCases` reads; `./gradlew build` green | T3 |
| 5 | T5 — The three boards on screen: segments, bands and story rows, the epic card, Blocked, the coverage matrix | `fe` | `fe-react` | `/projects/:id/work` shows `PM board · Dev board · QA board · Backlog · Table · Timeline · Calendar`; a lane is one band plus one sub-bar row per story whose tasks are cards; the epic is a card in its own status column; `Block…`/`Unblock` in the card menu; Done collapses to a strip; Settings → Boards draws the status × board matrix; `npm run build`, `lint`, `test:run` green | T4 |
| 6 | T6 — The shell: three zones, the switcher in the rail, the tabs, the renames, Timesheets in the menu, the Settings landing | `fe` | `fe-react` | The rail is `app/nav.ts`'s data, flat and usage-ordered; five former rail rows are tabs; every §8.1 rename applied including help copy; `/projects/:id/settings` is a landing with live facts and a subnav; `/projects/:id/suites` exists; the gates green | T5 |
| 7 | T7 — Breadcrumbs in the shell, the palette, Your access, the button convention | `fe` | `fe-react` | Route handles feed a shell breadcrumb and no page passes `crumbs`; `⌘K` / `/` opens a palette over cases, places, projects and key-jump; `/account/access` explains hidden entries; one primary + `⋯` on the five document-mode screens; the gates green | T6 |
| 8 | T8 — Search across issues and docs, the QA card face, the composite board retired, My work's strip, and the close-out | `fe` | `fe-react` | The palette groups issues, cases and docs; the QA card shows `n/m passing · k blocked` and `Run linked cases`; `/projects/:id/board` is gone and every link to it re-pointed; My work carries the *Awaiting you* strip; `docs/features.md`, the help sections, the proposal's §9 struck-through reversals in code comments, and the built-but-unverified list written | T7 |

**Budget.** Eight tasks, one stack and one session each. The backend is cut along the seam inside
`tracker` — schema + board rules (T2), transition semantics (T3), reads (T4) — because one session
over all of it reads `BoardConfigService`, `BoardCardService`, `TransitionService`,
`IssueService`, `WorkflowService`, a 300-line migration and nine test classes, which is past 150k
before a line is written. The frontend is cut boards / shell / cross-cutting / Phase-2 for the same
reason: `AppShell.tsx` (1,432 lines), `router.tsx` (1,366) and the six board files (~3,400) cannot
share a session with 44 pages losing their `crumbs` prop. Documentation rides with T8, the only
session that knows what landed.

**Estimates, counted rather than guessed.** T1: `openapi.yaml`'s tracker block (~2,400 lines), both
fixtures files, `reads.ts` in cli and mcp, the surface doc — ~70k. T2: V5's seed function, four
services, five tests, ~500 lines of SQL and ~600 of Java — ~110k. T3: `TransitionService`,
`IssueService` (transitions, derived status), `WorkflowService`, the entity, three tests — ~95k. T4:
`VerificationService`/`ReleaseFacts` as the pattern, one controller, one service, two tests — ~70k.
T5: the six board files, `BoardSettingsPage`, `boardCoverage.ts`, `WorkflowSettingsPage`, the
renders 12/13/14/15/18 — ~135k, the tightest; its seam if over is the coverage matrix +
workflow checkbox second. T6: `AppShell.tsx`, `router.tsx`, `AppShell.test.tsx`, the new
`nav.ts`, five tab strips, one landing page, help copy — ~130k. T7: the crumbs sweep (44 files, one
line each), palette, access page, five headers — ~120k. T8: palette results, card face, My work,
`features.md` — ~90k.

---

## Prompts — one per task

One task is one session. Each block below is complete on its own — what to read, in what order, the
traps that specific task will hit, what to commit and which gate to run. **A prompt is living text:**
a task that lands something different from what this plan predicted rewrites the prompts below it
before it stops, in place, with no changelog appended.

Every task has landed. Its prompt blocks were deleted when the plan was filed (2026-10-05); they are in git history before that commit.

## Decisions

### D-a — The migration is V13, because the directory says so

`ls backend/src/main/resources/db/migration/` on 2026-09-06: `V1` … `V12`. CLAUDE.md's "there is
exactly one migration" sentence describes the state on 2026-08-29 and is stale by eleven files;
the number is read from the directory, never derived from a document. `out-of-order` is unset, so a
lower number arriving late is never applied and Testcontainers cannot show it.

### D-b — Blocking is a flag on a status, not a fourth `IssueStatusKind`

`IssueStatusKind` is what every percentage, burn-up band and flow band is computed from, and
`Blocked` work is in flight and not done — it is `IN_PROGRESS` by every one of those measures. A
fourth kind would touch `labels.ts`, `IssueFactProjection`, the analytics bands and the Jira status
mapping to express a distinction none of them cares about. `issue_status.blocking BOOLEAN NOT NULL
DEFAULT false`, at most one per project (a partial unique index), is what "this is the status that
remembers where you came from" costs.

### D-c — Unblock is a transition to the origin, legal without a workflow row

A status's legal moves are `issue_transition` pairs. `Blocked` is entered from ten statuses, and
listing ten reverse pairs would make "back to where it was" indistinguishable from "to anywhere".
Instead `issue.blocked_from_status_id` is written on entry to a `blocking` status and cleared on
exit, and `TransitionService` treats a target equal to it as legal. The card's `allowedTransitions`
includes it, so the client refuses the drag at the drag as it does for every other move.

### D-d — An epic's status is stored; its completion stays derived (R10)

D31 conflated two facts. The percentage is honestly derived from counted stories and stays so —
`EpicStatus`/`Completion` keep that job. The status was derived only because an epic had no
lifecycle of its own; it has one now (New → Refining → Ready → … → Done), the PO moves it, and
"all stories done" is `allStoriesDone: true` on the card — a nudge, never a transition. The stored
`status_id` column already exists ("not editable and means nothing" — it means something now) so
no schema change is needed for this half.

### D-e — Coverage is a read that warns, not a rule that refuses (R7)

B2's two halves: *claimed at most once per board* is a unique index and stays; *claimed by every
board* goes, because it is the rule that forced every board to show the whole lifecycle. The
protection it gave — no card invisible everywhere — moves to `GET …/boards/coverage`, which the
settings screen renders as the status × board matrix with a warning row. The seeded boards cover
every status; the warning exists for the day somebody deletes a column.

### D-f — Work's segments are the project's boards in position order

`board` gains a `position` column in V13 (it had none; boards listed alphabetically, which
would put Dev before PM) and `listBoards` orders by it. The strip lists them, then the four fixed
views. A project that adds a fourth board gets a fourth segment; a project with one board (every
existing project) shows one. Nothing hardcodes "PM", "Dev" or "QA" — those are the seeded names.

### D-g — Breadcrumbs come from route metadata, and the router is not rewritten to get them

`router.tsx` uses `<Routes>`, not `createBrowserRouter`, so `useMatches()` and `handle` are
unavailable. Converting the router is a change to 76 routes for one feature. A `routeMeta.ts` map
keyed by path pattern, resolved with `matchPath` against the location, yields the same crumb trail
from one file and lets `PageHeader` drop its `crumbs` prop.

### D-h — The rail is data, in one file

`app/nav.ts` exports `NAV: Zone[]` — zones → items/folders, each with `label`, `to`, `icon`, an
optional `gate` (a permission key), `end`. `AppShell` renders it; the palette's "places" index is
generated from it; `AppShell.test.tsx` asserts against it. A label lives in one place, which is
what the twelve renames need.

### D-i — The CLI resolves a board by column name when three exist

`boardOf` refused a moveless `board move` on a multi-board project. Three seeded boards make every
project multi-board. `board move KEY --column NAME` without `--board` now finds the boards whose
column is named NAME: exactly one → use it; several → `UsageError` naming them; none → the existing
error. A move is a status change, so the column names the status, and the board is derivable.

### D-j — Search fans out client-side; there is no `/search` aggregate

Cases (`/cases/search`) and doc pages (`/projects/{id}/doc-pages/search`) already have endpoints in
their own modules. An aggregate would import three non-foundation modules, which ArchUnit refuses.
`GET /issues/search` is the one missing read; the palette calls three and groups.

### D-k — `linkedCases` is the read `listIssueTestCases` already makes, batched

`VerificationService.list` reads `VerificationLinkRepository` + `ReleaseFacts.lastResultsOf`, both
inside `tracker`. The card summary is the same two reads over every issue on a board in one
batch — no projection table, no event, no migration. The proposal's "event-fed projection" line was
written before this read was found and is superseded.

### D-o — The seeded workflow has 21 moves, and Done is terminal (found building T2)

Two corrections to §2.3/§2.4's arithmetic and one consequence of it, recorded rather than fixed
because both follow from rules the proposal states unambiguously.

**Twenty-one, not twenty-two.** The tally reads "10 into Blocked" from "into it from every non-done
status", and there are ten non-done statuses — but one of them *is* Blocked, and
`ck_issue_transition_distinct` refuses a self-pair. 8 along the line + 4 for Rework + 9 into Blocked
= 21. The rule is the proposal's; only the count was off by the status counting itself as its own
source.

**Done is terminal, so nothing can be reopened through the API.** The line runs one way, Rework is
reached only from In review / In test / Ready to deploy, and Blocked only from a non-DONE status —
so no seeded pair leaves Done. V5's six-status workflow allowed it (everything reached Backlog, so
a mis-closed issue could be put back), and this is a real capability the seed drops. Implemented as
enumerated; a project that wants a reopen adds the pair in Settings → Workflow, which already
exists. **Whether to seed one is a product call and is open.**

### D-l — Done is on the PM board only, collapsed (R12); the seed is for new projects

Tested is not shipped. `Ready to deploy` is the tester's last word; the PO's drag to `Done` is the
deploy confirmation and the figure readiness reads. Existing projects keep their six statuses and
their `Team board`; the eleven-status seed and the three boards are `tcms_seed_project_defaults`'s
for projects created after V13. An existing project adds statuses and boards in Settings, where it
already can.

### D-m — V13 back-fills `blocking` on an existing 'Blocked' status, nothing else

The six-status seed has a `Blocked`. Without the flag, an existing project's blocked cards would
never remember their origin. V13 sets `blocking = true` where `name = 'Blocked'` per company, with
V5 §6's per-company GUC loop, asserting the count. No other existing data changes.

### D-p — §2.6 ships **three** readiness signals, because the card carries no release (found building T5)

§2.6 names five (acceptance criteria, estimate, epic, target release, task count). The task count is
the earned row's `n/m`, which every sub-bar already carries. **Target release is not on
`BoardIssueCard`** — T1 added six card fields and a release was not among them — so the fourth chip
has nothing behind it. A `— release` would be a *claim* that the issue has no release, which the
payload cannot know from a field it does not carry, and that is the dishonest reading the whole
"says what is missing" idea exists to avoid. Three chips ship; the fourth needs
`BoardIssueCard.release` on the contract, which is a `be` task, not a screen decision.

The **epic's** third signal counts stories (`✓ 8 stories` from `subtaskTotal`) rather than reading
`epicKey`, which `18-pm-board.html` also draws: an epic has no epic, so `— epic` on one would be a
permanent, unfixable mark on every epic on the board.

**Which boards show the signals is read off the board's SHAPE, never its name** —
`cardMove.ts::groomsWork`: *two or more columns whose every status is `NOT_STARTED`*. Nothing on the
contract says which board is "the PM board", and matching on the name would be a guess about a
project's own vocabulary — the mistake `columnsFor` has a paragraph about. On §2.4's seeded three it
selects PM (New · Refining · Ready to develop) and neither Dev (Ready to develop alone) nor QA
(none), which is exactly what renders 18, 12 and 13 draw.

### D-q — A lane bar cannot name the board its epic is on (found building T5)

`18-pm-board.html` draws `Refining · epic on Dev board →` on the bar of a lane whose epic is in a
status this board does not claim. **Neither half is reachable.** `BoardLane` carries a key, a label,
its cards and a completion — no status; and an epic whose status this board does not claim is not in
the payload at all, because `BoardCardService.assemble` skips a card whose status maps to no column
*before* the lanes are built (that is R7 working correctly). So there is no status to draw a chip
from and no `elsewhere` to name a board with.

T5 renders the honest half: `epic not on this board →`, linking to the epic. Restoring the render's
sentence needs `BoardLane` to carry the epic's `IssueStatusRef` and the boards claiming it — an
additive contract change and a `be` task. Recorded rather than guessed at, because a hard-coded
"Dev board" would be correct only on the seeded project.

### D-r — `Run linked cases` goes to the issue, not to a run-list filter (found building T8)

§T8 named the action's target as `…/runs?issue=<key>`, *"the existing run-list filter"*. **There is
no such filter.** `RunListPage` reads no search parameter at all, and `GET /projects/{id}/runs`
takes an optional `planId` and nothing else — so a link carrying `?issue=` would be dropped and the
reader would land on every run in the project, which is the swallowed-404 defect in its quietest
form. The action therefore links to `/issues/{key}`, whose **Linked test cases** panel holds those
cases, their latest results and the keys the five-number summary cannot carry.

Closing this properly is a `be` task and then a `fe` one: an `issue` filter on `GET /runs` (or a
`POST` that creates a run scoped to an issue's linked cases, which is what the words *Run linked
cases* actually promise). Recorded rather than guessed at.

### D-n — The composite board retires in T8, after its halves have homes

Its issue half is the Dev board; its executions/reviews half is the QA card face (T4/T8); flagged
cases keep the case list's health filters and the Overview tiles. The route and `BoardPage`'s arm
go in T8, once every link has somewhere to point.

**Landed in T8.** The route, its `routeMeta` entry and `export function BoardPage` are gone;
`export function Board` — the composite renderer — stays, because `/teams/:teamId/board` renders it
and a team's board genuinely mixes all four card kinds across projects. **Nothing had to be
re-pointed:** the only inbound references to `/projects/:id/board` were `router.tsx`,
`routeMeta.ts` and three code comments; `app/nav.ts` never listed it (T6's rail leads with `Work`),
and the Dashboard link the T8 prompt anticipated does not exist — 012 T7 had already removed the
`M2` panel that carried it, and `WorkTablePage.test.tsx` records that removal. The comments now
carry the strike-through instead.

---

## File structure

**Contract (T1)** — `docs/specs/openapi.yaml`; `frontend/src/api/schema.d.ts` and
`packages/client/src/schema.ts` (generated); `frontend/src/test/fixtures.ts`,
`packages/client/src/test/fixtures.ts`; `packages/cli/src/reads.ts` (`boardOf`),
`packages/cli/src/reads.test.ts`, `packages/mcp/src/reads.ts`;
`docs/specs/2026-08-08-mcp-tool-and-command-surface.md`.

**Backend (T2–T4)** — `backend/src/main/resources/db/migration/V13__three_boards_and_blocking.sql`;
`tracker/IssueStatus.java`, `Issue.java`, `IssueStatusView.java`, `WorkflowService.java`,
`TransitionService.java`, `IssueService.java`, `IssueView.java`, `EpicStatus.java`,
`IssueController.java`; `tracker/board/BoardConfigService.java`, `BoardCoverageService.java` (new),
`BoardCoverageView.java` (new), `BoardConfigController.java`, `BoardSummaryView.java`,
`BoardCardService.java`, `BoardIssueCardView.java`; `tracker/IssueSearchService.java` (new),
`IssueSearchHitView.java` (new); tests under `backend/src/test/java/com/tcms/tracker/**`.

**Frontend boards (T5)** — `features/board/BoardPage.tsx`, `ProjectBoardView.tsx`, `Swimlane.tsx`
(→ lane with band + rows), `StoryRow.tsx` (new), `EpicCard.tsx` (new), `BoardCardFace.tsx`,
`MoveToMenu.tsx`, `BlockDialog.tsx` (new), `cardMove.ts`; `features/settings/BoardSettingsPage.tsx`,
`boardCoverage.ts`, `CoverageMatrix.tsx` (new), `WorkflowSettingsPage.tsx`;
`api/queries/projectBoards.ts`, `keys.ts`; `lib/labels.ts`; tests beside each.

**Frontend shell (T6)** — `app/nav.ts` (new), `AppShell.tsx`, `AppShell.test.tsx`, `router.tsx`;
`components/TabStrip.tsx` (new); `features/settings/SettingsLandingPage.tsx` (new),
`SettingsSubnav.tsx` (new); `features/cases/SuiteListPage.tsx` (new); `features/help/sections.tsx`;
every settings page (subnav), `CaseListPage`, `RunListPage`, `DocsPage`, `ArtifactsPage`,
`ProposalsPage`, `RunSchedulesPage`, `IngestionsPage` (a tab strip each).

**Frontend cross-cutting (T7)** — `app/routeMeta.ts` (new), `app/ShellBreadcrumbs.tsx` (new),
`app/CommandPalette.tsx` (new), `app/HeaderActions.tsx`; `components/PageHeader.tsx` and its 44
callers; `features/account/AccessPage.tsx` (new); `lib/permissions.ts` (+ `holdersOf`,
`disabledReason`); the five document-mode pages.

**Frontend Phase 2 + docs (T8)** — `app/CommandPalette.tsx`, `features/board/BoardCardFace.tsx`,
`BoardPage.tsx`, `MyWorkPage.tsx`, `router.tsx` (route removal); `docs/features.md`,
`features/help/sections.tsx`, this plan's close-out section.

---

## T1 — The contract, both clients, the CLI/MCP board resolver

**Deliverable:** `openapi.yaml` carries every shape below; both generated copies regenerated; a
fixture factory per new schema in both copies; the CLI's `boardOf` resolves a board by column;
`tcms_read_board`'s description names three boards; surface doc §6 and the tool table updated.
`GET /projects/{projectId}/boards/coverage` and `GET /issues/search` return a real 404 until T2/T4.
**Depends on:** none

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Generate: `frontend/src/api/schema.d.ts`, `packages/client/src/schema.ts`
- Modify: `frontend/src/test/fixtures.ts`, `packages/client/src/test/fixtures.ts`
- Modify: `packages/cli/src/reads.ts:951-985`, `packages/cli/src/reads.test.ts`
- Modify: `packages/mcp/src/reads.ts:936-960`
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

- [ ] **Step 1: The contract changes — add to `openapi.yaml`**

Under `IssueStatusRef`, add `blocking` and make it required:

```yaml
    IssueStatusRef:
      type: object
      required: [id, name, kind, position, blocking]
      properties:
        id: { type: string, format: uuid }
        name: { type: string, example: In Review }
        kind: { $ref: '#/components/schemas/IssueStatusKind' }
        position: { type: integer, description: Order in the project's workflow, from 0. }
        blocking:
          type: boolean
          description: |
            021 D-b. The one status per project that remembers where a card came from: entering
            it records `blockedFromStatus` on the issue, and that origin is a legal target on the
            way out even when no `transitions` pair lists it (D-c). At most one status per project
            may carry `true`; `putProjectWorkflow` refuses a second with 422.
```

Under `BoardSummary`, add `statusCount` (required):

```yaml
        statusCount:
          type: integer
          description: |
            How many of the project's statuses this board claims — the picker reads "5 of 11".
            A board claims a SUBSET since 021 R7; see `getBoardCoverage`.
```

After `/projects/{projectId}/boards/{boardId}/cards` add:

```yaml
  /projects/{projectId}/boards/coverage:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
    get:
      tags: [tracker]
      summary: Which boards show each status
      operationId: getBoardCoverage
      description: |
        `board:read`. 021 R7 — ~~every status must be claimed by exactly one column of every
        board~~ → superseded: a board claims a subset, and this read is what replaced the rule.
        One row per status in workflow order, each naming the boards (and the column on each)
        that claim it. `uncovered` lists statuses no board claims — a card there is on no board,
        which is the failure the old rule refused and this read reports instead. Empty for a
        freshly seeded project.
      responses:
        '200':
          description: The status × board matrix
          content:
            application/json:
              schema: { $ref: '#/components/schemas/BoardCoverage' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
```

And the schemas, beside `WipBreach`:

```yaml
    BoardCoverage:
      type: object
      required: [projectId, rows, uncovered]
      properties:
        projectId: { type: string, format: uuid }
        rows:
          type: array
          items: { $ref: '#/components/schemas/BoardCoverageRow' }
        uncovered:
          type: array
          items: { $ref: '#/components/schemas/IssueStatusRef' }

    BoardCoverageRow:
      type: object
      required: [status, claims]
      properties:
        status: { $ref: '#/components/schemas/IssueStatusRef' }
        claims:
          type: array
          description: In board position order. Empty when the status is uncovered.
          items: { $ref: '#/components/schemas/BoardClaim' }

    BoardClaim:
      type: object
      required: [boardId, boardName, columnId, columnName]
      properties:
        boardId: { type: string, format: uuid }
        boardName: { type: string }
        columnId: { type: string, format: uuid }
        columnName: { type: string }

    CardElsewhere:
      type: object
      required: [boardId, boardName, count]
      description: |
        021 §2.5. For a row header: the descendants this board does NOT show, grouped by the
        board that does. Because a status may be on two boards, "shown here" and "elsewhere"
        can overlap; this counts the not-shown-here set, never total minus shown.
      properties:
        boardId: { type: string, format: uuid }
        boardName: { type: string }
        count: { type: integer }

    LinkedCasesSummary:
      type: object
      required: [total, passing, failing, blocked, notRun]
      description: |
        021 D-k. The roll-up of `listIssueTestCases` for this card — the same `case_health`
        rows, so a card and the story's own panel cannot disagree. An epic rolls up its stories;
        a task carries its parent story's.
      properties:
        total: { type: integer }
        passing: { type: integer }
        failing: { type: integer }
        blocked: { type: integer }
        notRun: { type: integer }
```

In `BoardIssueCard`, add to `required`: `parentKey, blockedFromStatus, elsewhere, linkedCases,
allStoriesDone, hasAcceptanceCriteria`; and the properties:

```yaml
        parentKey:
          type: string
          nullable: true
          description: The story a task belongs to; the epic a story belongs to; null for an epic.
        blockedFromStatus:
          allOf: [{ $ref: '#/components/schemas/IssueStatusRef' }]
          nullable: true
          description: Set while the issue is in the project's `blocking` status (D-b/D-c).
        elsewhere:
          type: array
          items: { $ref: '#/components/schemas/CardElsewhere' }
        linkedCases:
          allOf: [{ $ref: '#/components/schemas/LinkedCasesSummary' }]
          nullable: true
          description: Null until served (021 T4) and null for a card with no linked cases.
        allStoriesDone:
          type: boolean
          description: |
            021 D-d. True on an EPIC whose every counted story is DONE while the epic itself is
            not — the card says so. A nudge to the PO; the server never moves an epic on its own.
        hasAcceptanceCriteria:
          type: boolean
          description: |
            021 §2.6 — the PM board's readiness signal. True when `acceptanceCriteria` is
            non-empty; the array itself stays off the card (it is the detail's).
```

Replace the `allowedTransitions` description's "Empty for an Epic (D31)" with:
"~~Empty for an Epic (D31)~~ → superseded (021 R10): an epic's status is stored and it moves like
a story. From a `blocking` status the origin is included even when no `transitions` pair lists it."

In `Issue`, add `blockedFromStatus` (nullable, required) with the same shape and description as
on the card.

In `createBoard` and `updateBoard` descriptions, strike the unclaimed sentence:
"~~**Every status the project has must be claimed by exactly one column** (story B2) — the
request is refused with 422 when a status is claimed twice or left unclaimed~~ → superseded
(021 R7): a status is claimed **at most once** per board (422 when claimed twice, or when a
column claims nothing); a status left unclaimed is simply not shown on this board, and
`getBoardCoverage` reports it." Update the 422 response descriptions to match.

In `transitionIssue`, strike the epic paragraph and add:
"~~**An Epic is refused with a 409 whatever the target**~~ → superseded (021 R10). An epic moves
like any issue. Entering the `blocking` status records the origin; from it, the origin is a legal
target without a `transitions` row (D-c). A `blocking` target from a DONE status is refused
(409)."

Add `/issues/search` after `/issues/{key}`:

```yaml
  /issues/search:
    get:
      tags: [tracker]
      summary: Issues by key prefix or title, across the caller's projects
      operationId: searchIssues
      description: |
        `issue:read`. 021 D-j — the palette's issue group. Key-prefix matches first (`ACME-1`
        finds ACME-1, ACME-10 …), then title, case-insensitive, over every project the caller may
        read. At most `limit` rows; the server caps at 20 when omitted (no `default:` here —
        openapi-typescript would make the parameter required).
      parameters:
        - { name: q, in: query, required: true, schema: { type: string, minLength: 1 } }
        - { name: limit, in: query, schema: { type: integer, minimum: 1, maximum: 50 } }
      responses:
        '200':
          description: Best first
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/IssueSearchHit' }
        '401': { $ref: '#/components/responses/Unauthorized' }
```

```yaml
    IssueSearchHit:
      type: object
      required: [key, title, typeLevel, projectId, projectName, status]
      properties:
        key: { type: string }
        title: { type: string }
        typeLevel: { $ref: '#/components/schemas/IssueLevel' }
        projectId: { type: string, format: uuid }
        projectName: { type: string }
        status: { $ref: '#/components/schemas/IssueStatusRef' }
```

- [ ] **Step 2: Regenerate both copies**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```
Expected: both `schema` files change; `git diff --stat` shows exactly two generated files.

- [ ] **Step 3: Fixtures — both copies**

In both `fixtures.ts`, extend `issueStatusRef()` with `blocking: false`, `boardSummary()` with
`statusCount`, `boardIssueCard()` with the five new fields (`parentKey: null`,
`blockedFromStatus: null`, `elsewhere: []`, `linkedCases: null`, `allStoriesDone: false`, `hasAcceptanceCriteria: false`), and add:

```ts
export function boardCoverage(overrides: Partial<BoardCoverage> = {}): BoardCoverage {
  const status = issueStatusRef();
  return {
    projectId: PROJECT_ID,
    rows: [{ status, claims: [{ boardId: BOARD_ID, boardName: 'Dev board', columnId: COLUMN_ID, columnName: status.name }] }],
    uncovered: [],
    ...overrides,
  };
}

export function issueSearchHit(overrides: Partial<IssueSearchHit> = {}): IssueSearchHit {
  return {
    key: 'ACME-118', title: 'Guest checkout', typeLevel: 'STORY',
    projectId: PROJECT_ID, projectName: 'Acme Payments', status: issueStatusRef(),
    ...overrides,
  };
}
```

- [ ] **Step 4: The CLI board resolver (D-i) — failing test first**

In `packages/cli/src/reads.test.ts`, beside the existing `boardOf` cases:

```ts
it('finds the one board whose column is named, when no --board is given', async () => {
  const stub = stubFetch({
    '/projects/{projectId}/boards': [boardSummary({ id: 'b1', name: 'Dev board' }), boardSummary({ id: 'b2', name: 'QA board' })],
    '/projects/{projectId}/boards/b1': boardConfig({ id: 'b1', columns: [column({ name: 'In review' })] }),
    '/projects/{projectId}/boards/b2': boardConfig({ id: 'b2', columns: [column({ name: 'In test' })] }),
  });
  const chosen = await boardOf(context(stub), undefined, 'In test');
  expect(chosen.id).toBe('b2');
});

it('refuses when two boards share the column name, naming both', async () => {
  // both boards carry a `Blocked` column
  await expect(boardOf(context(stub), undefined, 'Blocked'))
    .rejects.toThrow("Two boards have a column 'Blocked': Dev board, QA board — name one with --board.");
});
```

Run: `cd packages && npm run test:run -w cli -- reads.test.ts` — expected FAIL (third argument
unknown).

- [ ] **Step 5: Implement `boardOf(context, requested, column?)`**

```ts
export async function boardOf(
  context: CommandContext, requested: string | undefined, column?: string,
): Promise<{ id: string; name: string }> {
  const boards = await context.client.GET('/projects/{projectId}/boards', { … });
  if (requested !== undefined) { /* unchanged */ }
  if (boards.length === 1) return boards[0];
  if (column !== undefined) {
    const configs = await Promise.all(boards.map((b) => context.client.GET('/projects/{projectId}/boards/{boardId}', { … })));
    const owning = boards.filter((_, i) => configs[i].columns.some((c) => c.name.toLowerCase() === column.toLowerCase()));
    if (owning.length === 1) return owning[0];
    if (owning.length > 1) {
      throw new UsageError(`Two boards have a column '${column}': ${owning.map((b) => b.name).join(', ')} — name one with --board.`);
    }
  }
  throw new UsageError(`This project has ${String(boards.length)} boards — name one with --board: ${boards.map((row) => row.name).join(', ')}.`);
}
```

Pass `first(options['column'])` from `board move` in `writes.ts:1412`. Run the test — PASS.

- [ ] **Step 6: MCP and the surface doc**

`tcms_read_board`'s description: "A project has several boards (PM, Dev, QA are seeded); name one
with `board`, or omit it to read the first." Surface doc §6: the three boards, `--column` resolving
the board, and a new row for `issues search` (CLI: `issues search <q>`; MCP: `tcms_read_issues`
gains `q`).

- [ ] **Step 7: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run lint --workspaces
~/bin/tcms-remote packages npm run test:run --workspaces
~/bin/tcms-remote frontend npm run build
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts \
  frontend/src/test/fixtures.ts packages/client/src/test/fixtures.ts packages/cli/src/reads.ts \
  packages/cli/src/reads.test.ts packages/cli/src/writes.ts packages/mcp/src/reads.ts \
  docs/specs/2026-08-08-mcp-tool-and-command-surface.md
git commit -m "feat(tracker): the three-board contract — subset boards, coverage, stored epic status, blocking, issue search — both clients, and the CLI board resolver"
```

---

## T2 — V13, the seeded workflow and three boards, and the board rules

**Deliverable:** `V13__three_boards_and_blocking.sql` applied; a new project gets eleven statuses,
22 legal moves and three boards; `createBoard`/`updateBoard` accept a subset; the coverage read
serves the matrix; `BoardSummary.statusCount`, `BoardIssueCard.parentKey`, `elsewhere` and
`hasAcceptanceCriteria` served; boards listed in position order;
`./gradlew build` green with the rewritten tests.
**Depends on:** T1 (the schemas)

**Files:**
- Create: `backend/src/main/resources/db/migration/V13__three_boards_and_blocking.sql`
- Modify: `backend/src/main/java/com/tcms/tracker/IssueStatus.java`, `Issue.java`
- Modify: `tracker/board/BoardConfigService.java`, `BoardConfigController.java`, `BoardSummaryView.java`, `BoardCardService.java`, `BoardIssueCardView.java`
- Create: `tracker/board/BoardCoverageService.java`, `BoardCoverageView.java`
- Test: `backend/src/test/java/com/tcms/tracker/board/BoardConfigServiceTest.java`, `BoardConfigEndpointTest.java`, `StatusClaimTest.java`, `BoardCardServiceTest.java`, `BoardCoverageTest.java` (new), `tracker/WorkflowSeedTest.java`

- [ ] **Step 1: The migration**

```sql
-- =============================================================================
-- V13 — three boards over one workflow, and a status that remembers (plan 021)
--
-- THE VERSION IS V13, AND THE DIRECTORY IS WHAT SAYS SO. `ls` on 2026-09-06: V1..V12.
--
-- TWO COLUMNS. issue_status.blocking (021 D-b): the one status per project whose
-- entry records where the card came from. issue.blocked_from_status_id (D-c): that
-- origin, cleared on exit; a legal target from the blocking status without an
-- issue_transition row, which TransitionService enforces.
--
-- THE SEED FUNCTION IS REPLACED, NOT PATCHED. tcms_seed_project_defaults now
-- seeds eleven statuses, twenty-two moves and three boards (PM · Dev · QA) with
-- shared columns as the hand-offs (021 §2.4). Existing projects keep their six
-- statuses and their Team board (D-l); the only back-fill flags their 'Blocked'.
-- =============================================================================

ALTER TABLE issue_status ADD COLUMN blocking BOOLEAN NOT NULL DEFAULT false;
CREATE UNIQUE INDEX uq_issue_status_one_blocking
    ON issue_status (company_id, project_id) WHERE blocking;
COMMENT ON COLUMN issue_status.blocking IS
    'At most one per project. Entering it records issue.blocked_from_status_id (021 D-b).';

ALTER TABLE issue ADD COLUMN blocked_from_status_id UUID REFERENCES issue_status(id);
COMMENT ON COLUMN issue.blocked_from_status_id IS
    'Where the issue was before the blocking status; null otherwise. Unblock returns here (021 D-c).';

CREATE OR REPLACE FUNCTION tcms_seed_project_defaults(p_company_id UUID, p_project_id UUID)
    RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_spec RECORD;
    v_board RECORD;
    v_board_id UUID;
    v_column_id UUID;
    v_col RECORD;
BEGIN
    -- [Sections 1–4 of V5's body — classification dimension, case statuses, the Default
    --  configuration, the doc space, the key sequence — copied VERBATIM from V5.]

    -- 5. THE WORKFLOW — eleven statuses (021 §2.3). Idempotent per name, like V3's.
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('New',              'NOT_STARTED', 0,  false),
            ('Refining',         'NOT_STARTED', 1,  false),
            ('Ready to develop', 'NOT_STARTED', 2,  false),
            ('In progress',      'IN_PROGRESS', 3,  false),
            ('In review',        'IN_PROGRESS', 4,  false),
            ('Ready for test',   'IN_PROGRESS', 5,  false),
            ('In test',          'IN_PROGRESS', 6,  false),
            ('Ready to deploy',  'IN_PROGRESS', 7,  false),
            ('Done',             'DONE',        8,  false),
            ('Rework',           'IN_PROGRESS', 9,  false),
            ('Blocked',          'IN_PROGRESS', 10, true)
        ) AS s(name, kind, position, blocking)
    LOOP
        INSERT INTO issue_status (id, company_id, project_id, name, kind, "position", blocking)
        SELECT gen_random_uuid(), p_company_id, p_project_id, v_spec.name, v_spec.kind, v_spec.position, v_spec.blocking
        WHERE NOT EXISTS (SELECT 1 FROM issue_status
                           WHERE company_id = p_company_id AND project_id = p_project_id AND name = v_spec.name);
    END LOOP;

    -- The 22 legal moves: 8 along the line, 4 for Rework, 10 into Blocked. Out of Blocked is
    -- the remembered origin, not a row (D-c).
    FOR v_spec IN
        SELECT f.id AS from_id, t.id AS to_id
          FROM issue_status f
          JOIN issue_status t ON t.company_id = f.company_id AND t.project_id = f.project_id
         WHERE f.company_id = p_company_id AND f.project_id = p_project_id
           AND (
                 (t."position" = f."position" + 1 AND f."position" <= 7)                      -- the line
              OR (t.name = 'Rework'      AND f.name IN ('In review','In test','Ready to deploy'))
              OR (f.name = 'Rework'      AND t.name = 'In progress')
              OR (t.name = 'Blocked'     AND f.kind <> 'DONE' AND f.name <> 'Blocked')
           )
    LOOP
        INSERT INTO issue_transition (company_id, project_id, from_status_id, to_status_id)
        SELECT p_company_id, p_project_id, v_spec.from_id, v_spec.to_id
        WHERE NOT EXISTS (SELECT 1 FROM issue_transition
                           WHERE from_status_id = v_spec.from_id AND to_status_id = v_spec.to_id);
    END LOOP;

    -- [Section: issue types — copied VERBATIM from V5.]

    -- 6. THREE BOARDS (021 §2.4). Column = (name, statuses...). Shared columns are hand-offs.
    IF NOT EXISTS (SELECT 1 FROM board WHERE company_id = p_company_id AND project_id = p_project_id) THEN
        FOR v_board IN
            SELECT * FROM (VALUES
                ('PM board',  0, ARRAY[
                    ARRAY['New','New'], ARRAY['Refining','Refining'],
                    ARRAY['Ready to develop','Ready to develop'], ARRAY['Ready to deploy','Ready to deploy'],
                    ARRAY['Done','Done'], ARRAY['Blocked','Blocked']]),
                ('Dev board', 1, ARRAY[
                    ARRAY['Ready to develop','Ready to develop'],
                    ARRAY['In progress','In progress','Rework'],
                    ARRAY['In review','In review'], ARRAY['Ready for test','Ready for test'],
                    ARRAY['Blocked','Blocked']]),
                ('QA board',  2, ARRAY[
                    ARRAY['Ready for test','Ready for test'], ARRAY['In test','In test'],
                    ARRAY['Rework','Rework'], ARRAY['Ready to deploy','Ready to deploy'],
                    ARRAY['Blocked','Blocked']])
            ) AS b(name, pos, columns)
        LOOP
            v_board_id := gen_random_uuid();
            INSERT INTO board (id, company_id, project_id, name, swimlane_by, "position")
            VALUES (v_board_id, p_company_id, p_project_id, v_board.name, 'EPIC', v_board.pos);
            -- columns[i][1] is the column name and its DEFAULT status; columns[i][2..] the claimed statuses
            FOR i IN 1 .. array_length(v_board.columns, 1) LOOP
                v_column_id := gen_random_uuid();
                INSERT INTO board_column (id, company_id, board_id, name, "position", wip_limit, default_status_id)
                SELECT v_column_id, p_company_id, v_board_id, v_board.columns[i][1], i - 1, NULL, s.id
                  FROM issue_status s
                 WHERE s.company_id = p_company_id AND s.project_id = p_project_id AND s.name = v_board.columns[i][2];
                INSERT INTO board_column_status (company_id, board_id, column_id, status_id)
                SELECT p_company_id, v_board_id, v_column_id, s.id
                  FROM issue_status s
                 WHERE s.company_id = p_company_id AND s.project_id = p_project_id
                   AND s.name = ANY (v_board.columns[i][2:]);
            END LOOP;
        END LOOP;
    END IF;
END $$;

-- 7. BACK-FILL: flag every existing project's 'Blocked' (D-m), per company under RLS,
--    exactly V5 §6's loop, asserting the count.
DO $$
DECLARE v_company RECORD; v_flagged INT := 0; v_expected INT;
BEGIN
    SELECT count(*) INTO v_expected FROM issue_status WHERE name = 'Blocked';  -- as owner, pre-RLS count
    FOR v_company IN SELECT id FROM company LOOP
        PERFORM set_config('app.current_company', v_company.id::text, true);
        UPDATE issue_status SET blocking = true WHERE company_id = v_company.id AND name = 'Blocked';
        GET DIAGNOSTICS v_flagged = v_flagged + ROW_COUNT;
    END LOOP;
    IF v_flagged <> v_expected THEN
        RAISE EXCEPTION 'V13 back-fill flagged % of % Blocked statuses', v_flagged, v_expected;
    END IF;
END $$;
```

**`board` has no `position` column** (V5 lines 27–45: `name`, `swimlane_by`, a unique index on
`lower(name)`; `BoardRepository.findByProjectIdOrderByNameAsc`). Alphabetical order would list
Dev · PM · QA, so V13 adds it — put this before the function:

```sql
ALTER TABLE board ADD COLUMN "position" INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN board."position" IS
    'Left-to-right order in the Work strip (021 D-f). A created board takes max+1; nothing reorders yet.';
```

and T2 changes the repository to `findByProjectIdOrderByPositionAscNameAsc` and
`BoardConfigService.create` to assign `max(position) + 1`. An existing project's single board
keeps `0`. Note the `columns` 2-D array: Postgres arrays are rectangular, so
pad the shorter inner arrays to the widest (three) with a repeated name — `ANY` tolerates the
duplicate — or use a `jsonb` literal instead. Say which in the migration comment.

- [ ] **Step 2: Failing tests first**

`WorkflowSeedTest`: replace the six-name expectation with the eleven in position order and
`blocking` true only on 'Blocked'. `BoardCoverageTest` (new, boots the context):

```java
@Test @DisplayName("a freshly seeded project covers every status across its three boards")
void seededProjectIsFullyCovered() {
    BoardCoverageView coverage = coverageService.coverageOf(LEAD, projectId);
    assertThat(coverage.uncovered()).isEmpty();
    assertThat(coverage.rows()).hasSize(11);
    assertThat(rowFor(coverage, "Ready for test").claims()).extracting(BoardClaimView::boardName)
        .containsExactly("Dev board", "QA board");
    assertThat(rowFor(coverage, "Blocked").claims()).hasSize(3);
}

@Test @DisplayName("deleting the QA board's In test column leaves In test uncovered, and coverage says so")
void uncoveredIsReported() { /* update QA board without the column; assert uncovered == [In test] */ }
```

`BoardConfigServiceTest`: rename "a board that leaves a status unclaimed is refused" to "a board
may leave a status unclaimed" asserting the save succeeds and `statusCount` drops. `StatusClaimTest`:
"a status inserted after In review is claimed by every board claiming In review, in that column";
"a side status with no neighbour lands on every board as a new last column". `BoardCardServiceTest`:
"a task card carries its story's key as parentKey"; "a story row on the Dev board counts its
In-test tasks under elsewhere for the QA board, and not under total minus shown".

Run: `./gradlew test --tests 'com.tcms.tracker.board.*'` locally for debugging — expected FAIL.

- [ ] **Step 3: Implement**

`BoardConfigService.validate`: delete the `unclaimed` branch (lines ~361–367); keep `claimedTwice`
and empty-column refusals. `claimNewStatus(projectId, created, insertedAfter)`: for each board, if
a column claims `insertedAfter`, add `created` to that column; if `insertedAfter` is null, add a
new last column named `created.getName()` claiming it, on every board. `WorkflowService` passes
the neighbour (the status at `position - 1`, or null for a side status).

`BoardCoverageService.coverageOf(userId, projectId)`: statuses in position order × boards in
position order × `board_column_status` → rows; `uncovered` = statuses with no claim.
`BoardConfigController`: `@GetMapping("/coverage")` — **declare it before `/{boardId}`** so the
literal segment is not swallowed (the doc-page controller's own trap, line ~106 there).

`BoardSummaryView` + `statusCount` (a count over `board_column_status` per board, one query).
`BoardCardService`: `parentKey` from `Issue.parent_id` → key; `elsewhere` computed after lane
assembly: for each story on this board, its tasks whose status is not claimed here, grouped by the
other boards claiming that status.

- [ ] **Step 4: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend 'ulimit -n 65535; ./gradlew build'
git add backend/src/main/resources/db/migration/V13__three_boards_and_blocking.sql backend/src/main/java/com/tcms/tracker backend/src/test/java/com/tcms/tracker
git commit -m "feat(tracker): V13 — eleven statuses, three seeded boards, subset coverage"
```

---

## T3 — Stored epic status, the blocking status, and the return trip

**Deliverable:** An epic transitions like a story and its `allowedTransitions` is the workflow's;
an epic's SHOWN status is its stored one everywhere (`IssueService`, `BoardCardService`,
`IssueFactProjection`); `putProjectWorkflow` refuses two blocking statuses; removing a status
rewrites origins; `./gradlew build` green.
~~entering the `blocking` status records `blocked_from_status_id`; from it, the origin is a legal
target; `blockedFromStatus` and `allStoriesDone` are served~~ → **landed in T2**, because three
contract gates refuse a schema with no server behind it and one of them has no ignore list. See
T3's prompt.
**Depends on:** T2 (the columns, and D-c whole)

**Files:**
- Modify: `tracker/TransitionService.java:138-200`, `IssueService.java:600-760`, `WorkflowService.java`, `EpicStatus.java`, `IssueView.java`, `IssueStatusView.java`, `board/BoardCardService.java`, `board/BoardIssueCardView.java`, `IssueFactProjection.java`
- Test: `tracker/TransitionEndpointTest.java`, `IssueEndpointTest.java:209`, `EpicStatusTest.java`, `WorkflowEndpointTest.java`, `IssueFactProjectionTest.java`, `board/BoardCardServiceTest.java`

- [x] **Step 1: Failing tests**

`TransitionEndpointTest`:

```java
@Test @DisplayName("an epic moves along the workflow like a story (021 R10)")
void epicMoves() { /* POST transitions on an epic to Refining → 200, status Refining */ }

@Test @DisplayName("entering Blocked remembers the origin, and the origin is a legal way out")
void blockedRoundTrip() {
    // story in In progress → POST Blocked → 200, blockedFromStatus == In progress
    // GET → allowedTransitions contains In progress although no transition row says so
    // POST In progress → 200, blockedFromStatus == null
}

@Test @DisplayName("Blocked from Done is refused")
void blockedFromDoneRefused() { /* 409 */ }
```

`IssueEndpointTest:209` inverts: `allowedTransitions` on an epic is NOT empty.
`WorkflowEndpointTest`: "two blocking statuses is a 422 naming both". `IssueFactProjectionTest`:
"an epic's fact row carries its own stored kind". `BoardCardServiceTest`: "an epic whose counted
stories are all done reads allStoriesDone while it is not itself done".

- [x] **Step 2: Implement**

`TransitionService.apply`: remove the `EPIC → Outcome.DerivedStatus()` early return. After the
unknown-target check:

```java
boolean leavingBlocking = current.isBlocking();
boolean listed = transitions.exists(project.getId(), current.getId(), targetStatusId);
boolean returningHome = leavingBlocking && targetStatusId.equals(issue.getBlockedFromStatusId());
if (!listed && !returningHome) return new Outcome.Forbidden(current, target.get());
if (target.get().isBlocking() && current.getKind() == IssueStatusKind.DONE) return new Outcome.Forbidden(current, target.get());
if (target.get().isBlocking()) issue.setBlockedFromStatusId(current.getId());
if (leavingBlocking) issue.setBlockedFromStatusId(null);
```

`IssueService`: the `isEpic ? derivedStatus(...) : stored` branch becomes `stored` for every level;
`allowedTransitions` drops the EPIC empty-return and appends `blockedFromStatus` when the current
status is blocking. `EpicStatus` keeps `of(counted)` for the completion; add
`allStoriesDone(counted)` for the nudge. `WorkflowService.put`: count `blocking` > 1 → 422 naming
them. `removeStatus`: `UPDATE issue SET blocked_from_status_id = :target WHERE blocked_from_status_id = :removed`.
`IssueFactProjection`: the epic's kind is `issue.status.kind`.

- [x] **Step 3: Gate and commit** — `./gradlew build` green on shanks in 11m26s.

```bash
~/bin/tcms-remote sync && ~/bin/tcms-remote backend 'ulimit -n 65535; ./gradlew build'
git add backend/src/main/java/com/tcms/tracker backend/src/test/java/com/tcms/tracker
git commit -m "feat(tracker): epics move like stories; Blocked remembers where it came from"
```

**Three things landed differently from the shape above.**

1. **The PUT had to WRITE `blocking`, not merely count it.** `applyReplace` ignored the field
   entirely, so "count `blocking` > 1 → 422" would have guarded a value no caller could set and
   the count would have been permanently zero. `IssueStatus.create`/`redefine` now carry the flag,
   and every flag is cleared and flushed BEFORE any is set — `uq_issue_status_one_blocking` is a
   plain partial index rather than a deferred constraint, so a document moving the flag from a
   later status to an earlier one would otherwise flush the arriving `true` against the departing
   one and answer a legal document with a 500.
2. **`putProjectWorkflow` declared no 422**, although `IssueStatusRef.blocking`'s own description
   already promised one. Added to the operation and both generated copies regenerated (+13 lines
   each, purely additive — no client code reads that body). One contract edit inside a `be` task,
   taken rather than left, because the alternative is a contract that contradicts itself.
3. **`BoardCardService.allowedTransitions` was missing the remembered origin.** T2 added it to
   `IssueService` only, so the card named `Blocked · from In progress` and offered no way back —
   §12.4 the wrong way round, a drag the client refuses and the server accepts. Both now read one
   `blockedOriginOf`, so the chip and the offer cannot disagree.

**And one thing deliberately NOT done.** `work`'s composite `/board` still gives an epic an empty
`allowedTransitions` — it is now the only surface that does. Left alone: the contract's `BoardCard`
still declares it, and D-n retires the whole screen in T8, so changing it is a contract edit for a
screen being deleted. Recorded in `work/BoardService`'s javadoc and in `BoardCompositionTest` so it
reads as a decision. The cost while it lives is a stale client-side refusal, never a wrong write —
`POST /issues/{key}/transitions` is the only writer and it accepts the move.

---

## T4 — Issue search and the linked-cases summary on a card

**Deliverable:** `BoardIssueCard.linkedCases` populated from the reads `listIssueTestCases`
already makes, batched per board read; `./gradlew build` green.
~~`GET /issues/search?q=` answers key-prefix then title matches across the caller's projects~~ →
**landed in T2** (`tracker/IssueSearchService.java`), for the same reason D-c did: T1's contract
declared the operation and `ContractParameterBindingTest` refuses a declared operation no handler
serves, deliberately without an ignore list. T4 may still replace its Java-side filter with one
`project_id IN (…)` query — see the prompt.
**Depends on:** T3 (the card view)

**Files:** ~~as predicted~~ → what landed, the search half having gone in T2:
- Create: `tracker/release/LinkedCaseFacts.java` — the batched read, and it is in `release` rather
  than `board` because `ReleaseFacts` is the one file in `tracker` that names a table this module
  does not own. `board` asks a question and gets plain values back; it never names `case_health`.
- Modify: `board/BoardCardService.java`, `BoardIssueCardView.java`, `LinkedCasesSummaryView.java`
- Test: `board/BoardCardServiceTest.java`, `board/BoardMoveServiceTest.java`
- ~~`tracker/IssueSearchService.java`, `IssueSearchHitView.java`, `IssueController.java`,
  `IssueRepository.java`, `IssueSearchTest.java`~~ → T2's, and its Java-side filter was **left as
  it is**: the cross-project ordering is project name → key length → key, moving it into SQL moves
  it into the database's collation, and there is no size pressure to pay that for. The header on
  `IssueSearchService` already records the decision and the shape to move to.

**Still owed, and it is a `ts` task rather than this one.** `openapi.yaml` still describes
`linkedCases` as *"Null until served (021 T4)"*, and that sentence is now false — the null means
*none linked* and nothing else. Correcting it means `npm run gen:api` in **both** copies, which is
the `ts` stack, so a `be` task cannot make the edit without shipping a cross-stack commit. The two
Java records carry the corrected reading with the old one struck through.

- [x] **Step 1: Failing tests**

```java
@Test @DisplayName("ACME-1 finds ACME-1 and ACME-10 by key before any title match")
void keyPrefixFirst() { … }
@Test @DisplayName("a key from another company matches nothing")
void rlsHolds() { /* two companies, the way RlsIsolationTest does it */ }
@Test @DisplayName("a task card carries its story's linked-case summary; an epic rolls its stories up")
void linkedCasesSummary() { … }
```

- [x] **Step 2: Implement**

`IssueRepository`:
```java
@Query("select i from Issue i where i.deletedAt is null and (upper(i.issueKey) like upper(concat(:q, '%')) or upper(i.title) like upper(concat('%', :q, '%'))) order by case when upper(i.issueKey) like upper(concat(:q, '%')) then 0 else 1 end, i.issueKey")
List<Issue> search(@Param("q") String q, Pageable page);
```
(the interface already carries `@Transactional(readOnly = true)` — verify). `IssueSearchService`
filters by the caller's readable projects using the predicate `BoardCardService` uses for
`mine=true`. `BoardCardService`: after cards are built, `links.findByIssueIdIn(ids)` →
`facts.lastResultsOf(caseIds)` → per issue `{ total, passing, failing, blocked, notRun }` by
`StatusKind`; tasks take their parent's; ~~epics sum their stories'~~ → **epics UNION their
subtree's**. Summing was wrong and the test says so: one case can verify two stories of the same
epic, and adding the two summaries reports five cases where four exist — a total a reader cannot
reconcile against the stories it came from. The set is case ids, unioned; a TASK borrows its
parent's whole subtree, because linking is done on the story (H7) and the QA board is made of task
cards. Bucketing is `documentation.BlockCoverageView`'s rule over the same rows: only `PASSED`,
`FAILED` and `BLOCKED` are verdicts, so `SKIPPED`, `RETEST`, `UNTESTED`, `WIP` and the
never-executed all land in `notRun` and the four parts always sum to `total`. Null rather than a
zeroed summary when nothing is linked. The batch hangs off `factsFor`, so `BoardMoveService`'s
answer carries it too and the drag cannot lose it.

- [x] **Step 3: Gate and commit**

```bash
~/bin/tcms-remote sync && ~/bin/tcms-remote backend 'ulimit -n 65535; ./gradlew build'
git add backend/src/main/java/com/tcms/tracker backend/src/test/java/com/tcms/tracker
git commit -m "feat(tracker): issue search, and linked cases on the board card"
```

---

## T5 — The three boards on screen

**Deliverable:** Work's strip is the project's boards in position order + Backlog · Table ·
Timeline · Calendar; an EPIC lane renders as headers-once, one band (epic card + card-stories),
one indented sub-bar row per story whose tasks are cards here; `Blocked` column with the origin
chip; `Block…` / `Unblock` in the card menu; `Done` collapsed to a per-lane count strip; the
`also on` chip and `n on other boards` link; the `allStoriesDone` nudge; PM readiness signals;
Settings → Boards draws the coverage matrix; Settings → Workflow has the blocking radio; the
frontend gates green.
**Depends on:** T4 (every card field served)

**Files** (as landed; the plan's guesses are struck):
- Modify: `features/tracker/WorkTablePage.tsx` (the strip — ~~`BoardPage.tsx`~~, which is the
  *composite* board and owns no Work strip), `features/board/ProjectBoardView.tsx`, `Swimlane.tsx`,
  `BoardCardFace.tsx`, `MoveToMenu.tsx`, `cardMove.ts`, `api/queries/issues.ts`
  (`useTransitionIssue` now sweeps `boardCardsRoot`; its own comment had claimed it did).
  ~~`useWorkFilter.ts`~~ — untouched: `CARRIED` already carries `board`, which is what the segments
  write.
- Create: `features/board/laneRows.ts` (+ `.test.ts`) — the band/row division, pure, and
  `ProjectBoardView` rather than `Swimlane` calls it because the keyboard's band order is built from
  the same division. ~~`StoryRow.tsx`~~ lives inside `Swimlane.tsx` with the cell it shares a grid
  with; ~~`EpicCard.tsx`~~ was **not created** — the epic card is `BoardCardFace` with a border
  emphasis, and a second card renderer is the divergence `ui-rules` §8 exists to refuse.
  `BlockDialog.tsx`, `ReadinessSignals.tsx` as planned.
- Modify: `features/settings/BoardSettingsPage.tsx`, `boardCoverage.ts` (+ `unclaimedNote`),
  `ColumnEditor.tsx` (its header argued the retired rule), `WorkflowSettingsPage.tsx`
- Create: `features/settings/CoverageMatrix.tsx`
- Modify: `api/queries/projectBoards.ts` (+ `useBoardCoverage`), `api/queries/keys.ts`
  (+ `boardCoverage`), `api/queries/index.ts`
- Test: beside each; `ProjectBoardView.test.tsx` gains the band/row/Blocked/elsewhere cases,
  `WorkTablePage.test.tsx` the strip and the door badge, `BoardSettingsPage.test.tsx`'s
  refusal test **inverts** (unclaiming saves now), `WorkflowSettingsPage.test.tsx` gains the radio

- [x] **Step 1: Failing tests (RTL), the ones that pin the render's rules**

```tsx
it('draws the column headers once, above the lanes', …)                       // 18-pm-board
it('puts the epic card in its own status column inside the lane header band', …)
it('gives a story a sub-bar row only when its tasks are cards on this board', …)
it('never gives a card-story a row of its own', …)
it('shows an epic the board does not claim as a chip and a link, not a card', …)  // 13-qa-board
it('collapses a claimed Done column to a per-lane count strip', …)
it('offers Unblock on a blocked card and moves it to blockedFromStatus', …)
it('counts elsewhere from the card, and names two boards in a popover', …)
it('draws the coverage matrix and warns on an uncovered status without refusing the save', …) // 15
```

Use `createTestQueryClient` and the fixtures' `boardIssueCard({ parentKey, typeLevel })`; the
lane layout is asserted through `data-testid="Board.Lane.<key>.Band"` and
`Board.Lane.<key>.Row.<storyKey>` — locators from the render's structure.

- [x] **Step 2: Implement the lane**

`Swimlane.tsx` becomes the lane container: `<LaneBar>` (epic bar: key, title, status chip or epic
card slot, `epic on <board>` link when unclaimed, roll-up), then `<Band>` (a CSS grid with the
board's track list — `grid-template-columns: repeat(N, var(--col))` — holding `EpicCard` and
card-stories by column), then one `<StoryRow>` per story with visible task cards: an indented
sub-bar spanning `grid-column: 1 / -1` (key, title, `done/total`, status chip, elsewhere link,
`⋯`), then the task cards by column. Column headers render once in `ProjectBoardView` with
`position: sticky; top: 0` inside the board's own horizontal scroller. Empty cells render nothing
at rest; `isOver` from dnd-kit adds the outline class.

Grouping rule, in `laneRows.ts` (pure, unit-tested):

```ts
export function laneRowsOf(lane: BoardLane, claimed: ReadonlySet<string>): { band: BoardIssueCard[]; rows: StoryRow[] } {
  const tasksByStory = groupBy(lane.cards.filter((c) => c.typeLevel === 'TASK'), (c) => c.parentKey);
  const rows = lane.cards
    .filter((c) => c.typeLevel === 'STORY' && (tasksByStory.get(c.key)?.length ?? 0) > 0)
    .map((story) => ({ story, tasks: tasksByStory.get(story.key)! }));
  const rowKeys = new Set(rows.map((r) => r.story.key));
  const band = lane.cards.filter((c) => c.typeLevel === 'EPIC' ? claimed.has(c.status.id) : c.typeLevel === 'STORY' && !rowKeys.has(c.key));
  return { band, rows };
}
```

`Done` strip: a column whose every claimed status is `DONE`-kind renders as `<DoneStrip count>`
spanning the lane, expandable per lane (local state, like collapse).

- [x] **Step 3: The card menu and the dialogs**

`MoveToMenu` gains `Block…` (opens `BlockDialog`: one-line reason → `POST comments` then
`transitionIssue` to the status in `allowedTransitions` with `blocking: true`) and `Unblock`
(`transitionIssue` to `card.blockedFromStatus.id`) — both present only when the transition is in
`allowedTransitions`, disabled with the reason otherwise. `BoardCardFace` gains: the
`⛔ from <status>` chip, `also on <board>` chips from `elsewhere`, `ReadinessSignals` (`✓ AC` from `hasAcceptanceCriteria`, `✓ est` from `estimateHours`,
`✓ epic` from `epicKey`, `✓ release` from `release` — each `—` when absent), the
`allStoriesDone` nudge line.

- [x] **Step 4: Settings**

`boardCoverage.ts`: `coverageOf` keeps `claimedTwice` and `empty` as refusals; `unclaimed` is
returned but `holds` ignores it. `CoverageMatrix` renders `useBoardCoverage(projectId)` — rows ×
boards, `✓` where claimed, the `uncovered` list as a warn `Banner`. `WorkflowSettingsPage`: one
radio group column `Blocking`, at most one checked, sent as `blocking` on each status.

- [x] **Step 5: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build && ~/bin/tcms-remote frontend npm run lint && ~/bin/tcms-remote frontend npm run test:run
git add frontend/src/features/board frontend/src/features/settings frontend/src/api/queries frontend/src/lib/labels.ts
git commit -m "feat(board): PM · Dev · QA — bands, story rows, the epic card, Blocked, the coverage matrix"
```

---

## T6 — The shell

**Deliverable:** The rail renders `app/nav.ts` — three zones, flat usage-ordered project rows,
the one `Administration ▸` folder, the switcher as the Project zone header; Timesheets and
approvals in the user menu; every §8.1 rename applied in UI and help copy; tab strips on Test
cases, Test runs and Documents; `/projects/:id/suites` and `/suites/:suiteId` routed; the
Settings landing and subnav; the company slot; `AppShell.test.tsx` rewritten; all gates green.
**Depends on:** T5 (the Work strip the tabs pattern mirrors)

**Files:**
- Create: `app/nav.ts`, `components/TabStrip.tsx`, `features/settings/SettingsLandingPage.tsx`, `SettingsSubnav.tsx`, `features/cases/SuiteListPage.tsx`
- Modify: `app/AppShell.tsx`, `app/AppShell.test.tsx`, `app/router.tsx`, `features/help/sections.tsx`, the eight settings pages, `CaseListPage`, `RunListPage`, the docs/artifacts/proposals/schedules/ingestions pages

- [x] **Step 1: `nav.ts` — the data, transcribed from `shell-v2.js`'s `NAV`**

Three shapes differ from this plan's first sketch, and `nav.ts`'s own header argues each:

1. **`icon` is a `LucideIcon` component, not a JSX element** — the sketch wrote
   `<UserCheck className="size-4"/>`, which would make the file `.tsx` and put a rendering
   decision (the size class) in the model. The rail supplies it.
2. **`gate` carries its scope** (`{ key, scope: 'project' | 'company' }`) — `AppShell` reads two
   different endpoints for a verdict, and a bare key cannot say which one answers it.
3. **`to` is `(projectId: string) => string`**, so a row that ignores its argument is visibly a
   row outside the project frame.

And one decision was taken against §1: **the Project zone has no `needs: 'project:read'`.** It is
drawn when a project resolves, which is the same answer for the roles §1 cares about — Finance and
Account Manager get nothing from `GET /projects`, so no project resolves — arrived at without a
round trip. Asking the permission would hide all eleven rows on first paint and reveal them a
request later, because `can()` fails closed while pending and the project gate cannot even be
requested until `/projects` has named the active project.

```ts
export interface NavGate { key: PermissionKey; scope: 'project' | 'company' }
export interface NavItem { label: string; to: (p: string) => string; icon: LucideIcon; gate?: NavGate; end?: boolean }
export interface NavFolder { label: string; icon: LucideIcon; items: NavItem[] }
export type NavEntry = NavItem | NavFolder
export function isFolder(entry: NavEntry): entry is NavFolder
export interface NavZone { id: 'you' | 'project' | 'company'; label: string; switcher?: boolean; needs?: NavGate; entries: NavEntry[] }

export const NAV: NavZone[]                          // you (2) · project (11, switcher) · company (6 + Administration ▸ 3)
export interface SettingsSection { label: string; to: (p: string) => string; icon: LucideIcon; gate?: NavGate }
export const SETTINGS_SECTIONS: readonly SettingsSection[]   // 11, gates all PROJECT scope
export interface TabEntry { label: string; to: (p: string) => string; end?: boolean }
export const TABS: Record<'cases' | 'runs' | 'docs', readonly TabEntry[]>
```

- [ ] **Step 2: Failing tests — rewrite `AppShell.test.tsx` against `NAV`**

Keep the suite's shape (collapse, drawer, gates, forced routes, header slot) and change the
subjects: `Nav.Link.Projects`; the switcher `within(rail())`; `TopBar.TimesheetsLink` in the user
menu and `Nav.Link.Timesheets` absent; the order of the project zone equals `NAV[1].entries`'
labels; `Administration` absent for a caller with none of its three keys and present with any
one; the company slot renders the company name.

- [ ] **Step 3: Implement the shell, the tabs, the landing, the suites route**

`AppShell`: replace `projectNav()`, `MY_WORK`, `ALL_PROJECTS`, `TIMESHEETS`, `BILLING` with a
render over `NAV`, applying `gate` exactly as before (`permissions.includes(gate)`, pending →
hidden, failed read → shown). `ProjectSwitcher` moves into `RailContent` as the Project zone's
header; collapsed, it is the key chip. `TopBar`: the company slot (`<span data-testid="TopBar.CompanySlot">{company.name}</span>`), then the crumbs slot (empty until T7), search, page slot, theme, menu. User menu order per §4.

`TabStrip` — a segmented `<nav aria-label>` of `NavLink`s, active by route. Mounted at the top of
each of the eight tabbed pages. `SuiteListPage` — the suite tree as a page, each row linking to
`…/suites/:suiteId`, which renders `CaseListPage` with `suiteId` preselected (the existing panel's
own state, read from the param).

`SettingsLandingPage` — `SETTINGS_SECTIONS` filtered by gate, each a `DataRegion` card over its
own existing read: workflow (`statuses.length` statuses · transitions), boards (`useBoards` names
+ coverage `uncovered.length === 0 ? 'every status covered' : `${n} uncovered``), members,
imports (last run), notifications (channel count), audit (last event). `SettingsSubnav` on every
settings page, under the `PageHeader`.

Renames: the labels in `nav.ts`; `help/sections.tsx` lines 273, 343–346, 535; the `<title>`s of
the renamed pages; `Help & documentation` → `User guide` in the menu and the footer.

- [x] **Step 4: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build && ~/bin/tcms-remote frontend npm run lint && ~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run test:run --workspaces
git add frontend/src/app frontend/src/components/TabStrip.tsx frontend/src/features
git commit -m "feat(shell): three zones, usage-ordered project rows, tabs, the settings landing, Timesheets in the menu"
```

---

## T7 — Breadcrumbs in the shell, the palette, Your access, the button convention

**Deliverable:** `app/routeMeta.ts` yields a crumb trail for every route and `ShellBreadcrumbs`
renders it in the header; `PageHeader` has no `crumbs` prop and no caller passes one; `⌘K` / `/`
opens `CommandPalette` over cases, places, projects and key-jump; `/account/access` lists roles
and hidden entries with holders; the five document-mode screens carry one primary + `⋯`; the
disabled-reason sentence is one helper; gates green.
**Depends on:** T6 (`nav.ts` is the places index; the header has the crumbs slot)

**Files:**
- Create: `app/routeMeta.ts`, `app/ShellBreadcrumbs.tsx`, `app/CommandPalette.tsx`, `features/account/AccessPage.tsx`
- Modify: `components/PageHeader.tsx`, its 44 callers, `app/AppShell.tsx`, `app/HeaderActions.tsx`, `app/router.tsx`, `lib/permissions.ts`, `CaseDetailPage.tsx`, `CaseEditorPage.tsx`, `CaseHistoryPage.tsx`, `ExecutionPage.tsx`, `ProposalPage.tsx`, `ReleasesPage` (`Create a release` → `New release`), `ProposalsPage` (`Generate one now` → `New proposal`)
- Test: `app/routeMeta.test.ts`, `app/CommandPalette.test.tsx`, `features/account/AccessPage.test.tsx`, `lib/permissions.test.ts` (+ `holdersOf` vs the matrix copy)

- [ ] **Step 1: `routeMeta.ts`**

```ts
export interface Crumb { label: string; to?: string }
type CrumbsOf = (params: Record<string, string>, ctx: { projectName?: string }) => Crumb[];
const project = (p: string, name?: string): Crumb[] => [{ label: 'Projects', to: '/' }, { label: name ?? p, to: `/projects/${p}` }];
export const ROUTE_META: Record<string, CrumbsOf> = {
  '/projects/:projectId': (q, c) => project(q.projectId, c.projectName),
  '/projects/:projectId/work': (q, c) => [...project(q.projectId, c.projectName), { label: 'Work' }],
  '/projects/:projectId/cases/:caseId': (q, c) => [...project(q.projectId, c.projectName), { label: 'Test cases', to: `/projects/${q.projectId}/cases` }, { label: q.caseId }],
  // … one entry per <Route path> in router.tsx (76). routeMeta.test.ts asserts the key set equals the route set.
};
export function crumbsFor(pathname: string, ctx: { projectName?: string }): Crumb[] {
  for (const [pattern, fn] of Object.entries(ROUTE_META)) {
    const m = matchPath({ path: pattern, end: true }, pathname);
    if (m) return fn(m.params as Record<string, string>, ctx);
  }
  return [];
}
```

`routeMeta.test.ts` reads `router.tsx`'s source, extracts every `path="…"`, and asserts
`Object.keys(ROUTE_META)` equals that set — so a new route without crumbs is a red test, not a
missing breadcrumb.

- [ ] **Step 2: The palette**

`CommandPalette` — a `Dialog`, `role="combobox"` input, listbox groups: **Jump to** (key-jump when
`q` matches `/^[A-Z][A-Z0-9]+-\d+$/` → `/issues/:key`; `/^TC-\d+$/` → the case), **Cases**
(`/cases/search?q=`, debounced 200ms, `enabled: q.length >= 2`), **Places** (a static index built
from `NAV`, `SETTINGS_SECTIONS`, `TABS` and the user menu, filtered client-side), **Projects**
(`useProjects()` filtered). Enter on the input with no selection navigates to `/search?q=`.
Keyboard: `⌘K`/`Ctrl-K` anywhere; `/` when `document.activeElement` is not an input, textarea or
contenteditable. Placeholder `Search cases, or jump to…`. Test id `TopBar.Search`.

- [ ] **Step 3: Your access, the button convention, the helper**

`lib/permissions.ts`:
```ts
export function disabledReason(permission: Permission): string {
  return `Needs ${permissionLabels[permission]}, held by ${holdersOf(permission).join(' and ')}.`;
}
export function holdersOf(permission: Permission): RoleName[] { /* the §A3 table, transcribed */ }
```
`permissions.test.ts`: `holdersOf` for every permission equals the row in
`docs/specs/permission-matrix-summary.md` (parse the table the way `matrixCopies.test.ts` does).
`AccessPage`: `useMyPermissions` per project the caller can see + the company-wide set → "You are
<roles> on <project>", then the nine hidden entries with `holdersOf`.

Case detail: `Edit` primary; `⋯` → History · Link to spec · Link issue · Import. `HeaderActions`:
dev-only `console.error` when given more than two children.

- [ ] **Step 4: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build && ~/bin/tcms-remote frontend npm run lint && ~/bin/tcms-remote frontend npm run test:run
git add frontend/src/app frontend/src/components/PageHeader.tsx frontend/src/features frontend/src/lib/permissions.ts frontend/src/lib/permissions.test.ts
git commit -m "feat(shell): breadcrumbs from route meta, the ⌘K palette, Your access, one primary per screen"
```

---

## T8 — Search across issues and docs, the QA card face, the composite board retired, My work, the close-out

**Deliverable:** The palette groups issues, cases and docs with the placeholder `Search issues,
cases, docs`; the QA card face shows `n/m passing · k blocked` and `Run linked cases` when
`linkedCases` is non-null; `/projects/:projectId/board` and `BoardPage`'s composite arm are gone
and every link re-pointed; door badges on every board segment; My work's *Awaiting you* strip;
`docs/features.md` and the help sections rewritten; the twelve reversals struck-through where
argued; a built-but-unverified list appended to this plan.
**Depends on:** T7 (the palette)

**Files:**
- Modify: `app/CommandPalette.tsx`, `features/board/BoardCardFace.tsx`, `BoardPage.tsx`, `BoardPage.test.tsx`, `MyWorkPage.tsx`, `features/dashboard/DashboardPage.tsx`, `app/router.tsx`, `app/routeMeta.ts`, `docs/features.md`, `features/help/sections.tsx`, this plan

- [x] **Step 1: Failing tests**

`CommandPalette.test.tsx`: "groups issues from /issues/search and docs from the active project's
doc-page search, and omits the docs group with a sentence when no project is active".
`BoardCardFace.test.tsx`: "renders the linked-cases line only when the summary is non-null".
`BoardPage.test.tsx`: the composite cases removed; "the Work board view still renders at
?view=board". `MyWorkPage.test.tsx`: the strip's five items.

- [x] **Step 2: Implement**

Palette: three queries in parallel, grouped **Issues · Cases · Docs · Places · Projects**;
placeholder per §7 Phase 2. Card face: `linkedCases` line + `Run linked cases` (links to the
story's plan runner, `…/runs?issue=<key>` — the existing run-list filter). Router: delete the
`/projects/:projectId/board` route; `routeMeta.test.ts` forces its key out. Dashboard: `Open
today's board` → `…/work?view=board`. Door badges: for each `BoardSummary`, `useBoardCards(id)`
with `enabled: true`, counting cards whose `columnId` is the first column — shown on inactive
segments once loaded. My work: the strip over reads the page already makes plus per-project
board reads for projects in the caller's list.

- [x] **Step 3: Close-out**

`docs/features.md`: §Boards and the Backlog rewritten for three boards, the coverage read, epics
that move, Blocked; a §Navigation paragraph (zones, tabs, palette, user menu). `help/sections.tsx`:
the same. Confirm every `→ superseded (021 R<n>)` strike-through exists where §9 says it is
argued (`grep -rn "021 R" frontend/src backend/src docs/specs/openapi.yaml` lists twelve). Append
to this plan:

See **Built but unverified** at the end of this document.

- [ ] **Step 4: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build && ~/bin/tcms-remote frontend npm run lint && ~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run test:run --workspaces
git add frontend/src docs/features.md docs/plans/implemented/021-navigation-and-boards-redesign.md
git commit -m "feat(shell): grouped search, the QA card face, the composite board retired, My work's strip; docs close-out"
```

---

## Definition of done

- A new project shows three boards; a story dragged to `Ready for test` on Dev is in QA's first
  column with no second action; a card blocked from `In test` returns there on `Unblock`; an epic
  in `Refining` is a card the PO drags on the PM board.
- The rail is 20 rows at most, flat in the project zone, with the boards' screen first;
  Timesheets is in the user menu; every URL in the brief's §5 inventory still resolves.
- `⌘K` finds an issue by key, a case by title, a doc page in the active project and any place in
  the product.
- Every gate green on `shanks`: `./gradlew build`, frontend build + lint + test:run, packages
  build + lint + test:run.
- `docs/features.md` describes the product as built; the twelve reversals are struck through where
  they were argued; the built-but-unverified list names what a browser has yet to show.

---

## Built but unverified

Written by T8, the only session that saw what actually landed. Everything below **compiles, lints
and passes its tests on `shanks`**; none of it has been operated in a browser, and the gates cannot
see any of it. The list is deliberately long — the gates prove it compiles, not that it works, and
this repo has shipped a no-op theme toggle, two contradictory percentages on one screen and a
provider-ordering bug that rendered nothing but the error page, all typechecking and linting clean.

### From T8 (this session)

- **The palette's three groups have never been seen together.** The tests assert the groups, the
  per-project docs read and the no-project sentence; nobody has watched five groups (`Issues ·
  Cases · Docs in <project> · Places · Projects`) plus the `Jump to` row and the `Search` floor row
  fit in a 560px dialog at `top-[12%]` without scrolling the first group off. If they do not, the
  fix is a shorter `limit` per server group, not a scroll.
- **The docs group's heading is the project's NAME**, taken from the project list. On a company
  whose project names are long, `Docs in Payments Platform Modernisation` is a heading wider than
  the dialog. Untested at that width.
- **The linked-cases line has never been drawn on a real card.** The line adds a third row to a
  card face that already carries a title, readiness signals and a wrapping chip strip; whether a QA
  board column stays scannable with it is a judgement nobody has made with cards on screen.
  `Run linked cases` is a plain link in that row and not a button — its hit area at touch size is
  unmeasured.
- **`0/6 run` on a real payload.** Every case in `linkedCases.test.ts` is a hand-built summary. No
  card served by T4's projection has been looked at, so the assumption that the five counts sum to
  `total` is the contract's word and not an observation.
- **Door badges on every segment: the request volume is not measured.** `useBoardDoors` fires two
  reads per board once the strip has a board list — six requests on a seeded three-board project,
  on every Work view including Table, Timeline and Calendar. That is the design (the counts must be
  readable *without* switching), and the cost has been reasoned about rather than watched in a
  network panel.
- **My work's *Awaiting you* strip has the largest fan-out in the app and has never been run
  against a seeded instance.** For a reader with work in two projects with three boards each it is
  2 board-list reads + 12 board reads + 2 proposal reads on top of the personal board. `seed-demo-data`
  is the way to see it; nobody has.
- **The strip's board tiles are drawn per board, where render 07 draws one.** §8.4's "boards where
  you hold the moving key" is derived from `allowedTransitions` being non-empty on a card at the
  door, because the contract carries **no board-scoped permission** — `issue:edit` is project-wide.
  So a Test Engineer with `issue:edit` sees a tile per board of that project with work at its door,
  and the render's single tile is not reproducible from any data the server sends. Recorded rather
  than faked; it closes the day a board-scoped grant exists.
- **The strip is bounded to the projects the reader has work in**, not `GET /projects`. A board
  whose door has work waiting for the team, in a project this person currently has nothing assigned
  in, is therefore **not** on the strip. This is a deliberate trade against N×M requests on the one
  screen somebody keeps open all day, and it is the item on this list most likely to be argued
  with once somebody uses it.
- **The retirement of `/projects/:id/board` has not been walked.** `routeMeta.test.ts` proves the
  key is gone and `BoardPage.test.tsx` proves the module no longer exports a page, but nobody has
  typed the old URL into a running app to see what the router does with it (it falls through to the
  catch-all, which is the intended answer and is unobserved).
- **`Run linked cases` promises more than it delivers** — see **D-r**. It opens the issue's Linked
  test cases panel; it does not start a run. The words are the render's and the destination is the
  honest one available today.
- **`docs/features.md`'s new §Navigation and the rewritten §Boards, and `help/sections.tsx`'s
  rewritten navigation and board definitions, are prose about screens no browser has shown.** They
  describe the code as written, which is not the same claim as describing the product as used.
- **Two pre-existing failures in `CaseGrid.test.tsx`** (`select-all reaches the page…` and
  `onSelectionChange` with a row not on this page) are red on `master` **without** any of this
  task's changes, verified by stashing. They are not T8's and are not fixed here.

### From T7, still true

- The `⌘K` palette's `/` guard is covered for `<input>`; the `contenteditable` arm (the case
  editor's document surface) is asserted through `isContentEditable` and has not been typed into.
- `Your access` has not been read by somebody who was actually missing an entry.

### From T6 (the shell), stated because the gates cannot see any of it

- **The company slot is reserved and empty, and it will stay empty until the contract names a
  company.** §3 asks for the company name as static text; nothing on the wire carries it. The
  element, its locator and its place in the header are real; the text is not. This is the one
  item of T6's deliverable that did not land, and it needs one field on `GET /me` from the backend
  side before any frontend task can finish it.
- Nothing in T6 has been seen in a browser: the collapsed rail's `Administration` flyout, the key
  chip opening the switcher at 64px, the settings landing at eleven cards on a wide screen and at
  one column on a phone, the three tab strips wrapping, and the mobile drawer — which renders the
  three zones as the same flat lists the desktop rail does, **not** as §1's zone accordions. That
  divergence is deliberate for now (the drawer shares `RailContent`, and splitting it is a second
  component to keep in step) and is the smallest thing on this list.
- ~~The user menu is §4 minus two rows: `Your access` and `Keyboard shortcuts` are T7's.~~ →
  `Your access` landed in T7. `Keyboard shortcuts` still has no page and no menu row, and a menu
  entry pointing at a route that does not exist is worse than an absent one.

### From T5 (the boards), still unverified

- The sticky column header inside the board's own scroller; the `Done` strip expanding; `Block…`
  on a touch device; the coverage matrix at eleven statuses × three boards on a narrow screen.

### Contract gaps recorded, not guessed at

Three, each a `be` task and none of them a frontend one:

- **D-p** — `BoardIssueCard` carries no target release, so the PM card draws **three** readiness
  signals where §2.6 asks for four. The fourth chip's place on the card face is decided and empty.
- **D-q** — `BoardLane` carries no epic status, so a lane bar reads `epic not on this board` and
  cannot name which board it *is* on. The named link's place is decided and empty.
- **D-r** — no issue filter on the run list, so `Run linked cases` opens the issue rather than a
  filtered runner.

Two more, outside this plan's stacks:

- `tcms issue search "<q>"` (surface doc §6 — **singular noun**, not the plan's `issues search`)
  and the MCP's `tcms_read_issue {q}` beside it are **not built**. They are a `ts` task of their
  own; T8 is `fe` and did not open `packages/`.
