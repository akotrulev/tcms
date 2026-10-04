# Navigation & IA — redesign proposal

**Written 2026-09-06** against the brief in
`docs/specs/2026-09-06-navigation-ia-redesign-brief.md`; **revised twice the same day** after
product-owner review. The focus is *the work to be done*: three boards (PM · Dev · QA) over one
workflow, rowed by epic with the stories and tasks inside, statuses that apply to epics, stories
and tasks alike, and the most-used screens at the top of a flat navigation. Timesheets leave the
rail for the user menu.

This is a *proposal*, not a plan. A plan (`docs/plans/`) follows once the shape is agreed.

**Precedence invoked.** The navigation is "what a screen shows", where the mockup wins over the
build (brief §1). The board and workflow changes in §2 are "what the system does" and need a
contract change; they are marked as such, and the decisions they reverse are in §9.

---

## ~~1. The rail~~ → superseded by plan 023 (D1, D3)

> **→ superseded by plan 023 (D1, D3)**, 2026-10-04. The rail, its three zones' placement, its collapsed state and the mobile drawer are replaced by one 48px top bar (launcher, project switcher, project tabs and More). `nav.ts`'s zones and their permission gates are unchanged. Kept struck through as the record.

Three scope zones, fixed order, never mixed. **No subject folders in the project zone** — the
~~Repository ▸ / Quality ▸~~ split of the first draft is withdrawn: it filed the most-used screens
one click down and named a distinction nobody working the board makes. The project zone is
**ordered by how often a screen is opened**, and the long tail is tabs on the screen it extends.

~~Timesheets~~ leaves the rail: it is a personal chore, not a place work is done, and it sits in
the user menu beside the other things that belong to the person (§4).

`[gate]` keeps the brief §7 rule and its nine exceptions exactly.

```
TCMS                                            wordmark · collapse toggle

YOU
  My work                                       /my-work            (badge: assigned + awaiting)
  Projects                                      /                   (was "All projects")

PROJECT  [ ACME · Acme Payments ▾ ]             ← zone header IS the project switcher
  Work                                          …/work              PM board · Dev board · QA board · Backlog · Table · Timeline · Calendar
  Test cases                                    …/cases             Cases · Suites · Proposals
  Test plans                                    …/plans
  Test runs                                     …/runs              Runs · Schedules · CI results
  Releases                                      …/releases
  Documents                                     …/docs              Pages · Artifacts
  Overview                                      /projects/:id       the dashboard — read, not worked
  Analytics                                     …/analytics         (page title: Delivery analytics)
  Test reports                                  …/reports
  Team                                          …/staffing          (was "Staffing")
  Settings                                      …/settings          one entry → landing page (§5)

COMPANY
  Org chart                                     /org-chart
  Directory                                     /directory
  Capacity                                      /staffing           (was "Staffing")
  Teams & roles                                 /admin/teams
  Clients                        [client:read]  /clients
  Billing                        [billing:read] /billing
  Administration ▸                              API tokens [token:issue] · Single sign-on [company:idp] · Company audit [audit:view]
```

**Row count:** 20 for a caller holding every gate, 17 for a Test Engineer (16 if `Teams & roles`
keeps its `company:teams` gate, which the build has and the brief's §4.1 does not list), versus
27–36 flat today. One folder survives, `Administration ▸`, because its three children are all
gated and a closed folder is where their absence is least conspicuous.

**The project zone's order is usage order.** Work is first because the boards are where the day
starts and ends. The three test-repository screens follow. Overview drops to seventh: it is the
project's *dashboard*, read at stand-up, not the place work is done.

**The tail became tabs**, the pattern Work already uses for its views. A tab keeps its own URL,
so every pasted link still lands; the active tab is read from the route.

| Was a rail row | Now a tab on | Route (unchanged) |
|---|---|---|
| Suites | Test cases | `…/suites` (new list route — brief §8.6) |
| Proposed cases (was Generated cases) | Test cases | `…/generated-cases` |
| Run schedules (orphan) | Test runs | `…/run-schedules` |
| CI results (was CI ingestion) | Test runs | `…/ingestions` |
| Artifacts | Documents | `…/artifacts` |

### Collapsed state (64px)

20 icons at most, three zones separated by rules; the project zone header collapses to the
project key chip (`ACME`), which still opens the switcher. `Administration ▸` opens a labelled
flyout. The project rows are flat, so the collapsed rail is the same list in the same order.

### Mobile drawer

Three zone accordions; the zone holding the current route is open. `You` first.

---

## 2. The boards — PM, Dev and QA over one workflow

The part of the proposal that changes what the system does. Written against the board model that
exists (`Board` / `BoardColumn` / `BoardLane` in `openapi.yaml`, plan 012) and saying exactly
where it has to give.

### 2.1 What exists

- A **board is a lens, not a container**: any number per project, each a grouping axis
  (`swimlaneBy`) plus a mapping from the project's statuses onto columns. Deleting one loses no
  work.
- `swimlaneBy: EPIC` exists; a task lands in its story's epic's lane; *No epic* is last and not an
  error; a lane has a completion roll-up and collapses.
- Statuses are per project and editable (Settings → Workflow: statuses, kinds, legal moves).
  Kinds are `NOT_STARTED · IN_PROGRESS · DONE`, which is what completion is computed from.
- **Story B2** — every status must be claimed by exactly one column **of every board**. This is
  what stops a Dev board omitting `In test`, or a PM board omitting `In progress`, and is why one
  board grows to eight columns.
- **D31** — an **epic's status is derived** from its stories; on a board an epic is a card in no
  column with no allowed transitions. So an epic cannot be groomed on a board today.
- The Backlog view is a ranked list (no epics, no done work) with estimate, epic, release, labels,
  assignee — an *ordering* surface, not a status one.
- Stories carry acceptance criteria and an estimate. There is no "ready" flag.

### 2.2 Change one: a board claims a *subset*; the project's boards together claim everything

Reverse half of B2 (R7). *Claimed at most once per board* stays — it is a unique index and what
makes a card's column unambiguous. *Claimed by every board* goes:

- A status a board does not claim is a status **that board does not show**.
- The settings coverage table (`boardCoverage.ts`, on `/settings/boards`) changes from *is every
  status claimed here?* to **which boards show each status?** — one row per status, one column
  per board, and a warning row for a status no board claims. The seeded boards in §2.4 cover
  every status, so that row is empty on day one; it exists for the day somebody deletes a column.
- Auto-claim on a new status (012 D-g) becomes: the new status is claimed by **every board that
  claims the status it was inserted after**, in that board's column for it, until moved. A status
  created *off* the line (the workflow editor's "add a side status", how `Rework` and `Blocked`
  are made) names no neighbour, so it is claimed by **every board, as a new last column**, and
  the coverage table shows it there until somebody moves or unclaims it.

Contract impact, additive, **no migration**: remove the *unclaimed* 422 from `createBoard` /
`updateBoard`; `BoardSummary` gains `statusCount`; a coverage read returns the status × board
matrix.

### 2.3 Change two: one workflow for epics, stories and tasks

The statuses and the boards apply to **all three levels**. Reverse D31 (R10): an epic's status is
**stored and movable**, exactly like a story's or a task's. Its *completion* stays derived (done
over counted, with the basis on the wire) — that is a percentage, not a status, and the two were
only ever conflated because an epic had nothing else to show.

Seeded for a new project, eleven statuses in one line plus two off it:

```
New → Refining → Ready to develop → In progress → In review → Ready for test → In test → Ready to deploy → Done
        NOT_STARTED ─────────────┘  IN_PROGRESS ──────────────────────────────────────────┘  DONE

  Rework    IN_PROGRESS   legal from In review, In test, Ready to deploy; legal to In progress
  Blocked   IN_PROGRESS   legal from every non-DONE status; legal back to the status it came from
```

- `Ready to deploy` sits between `In test` and `Done`: tested, not shipped. Moving to `Done` is
  the deploy confirmation, and it is the PM board's column, because shipping is the PM/PO's call
  and the thing release readiness is computed over.
- `Blocked` is one status, `IN_PROGRESS` (blocked work is in flight and not done, so no
  completion figure changes). Any card can be blocked from any non-done status; the card
  **remembers where it came from** (`blockedFromStatusId`, one nullable column on `issue`, the
  one small migration this proposal needs) and `Unblock` puts it back there. Blocked is not a
  place work goes, it is a place work waits, so the return trip should cost one click and no
  decision.
- Kinds are unchanged, so every burn-up, cycle-time and flow figure keeps its meaning.
- An existing project keeps its workflow; the seed is for new projects. An existing project that
  wants this adds the statuses in Settings → Workflow, which already exists.

### 2.4 The three boards, and the hand-offs

Seeded for every new project, editable like any board:

```
PM BOARD   (PM / PO — grooming in, shipping out)
  ┌──────┬──────────┬──────────────────┬─────────────────┬──────┬─────────┐
  │ New  │ Refining │ Ready to develop │ Ready to deploy │ Done │ Blocked │
  └──────┴──────────┴──────────────────┴─────────────────┴──────┴─────────┘
                                                            ^ collapsed by default: a count strip

DEV BOARD
  ┌──────────────────┬─────────────────────┬────────────┬────────────────┬─────────┐
  │ Ready to develop │ In progress         │ In review  │ Ready for test │ Blocked │
  │                  │ (+ Rework)          │            │                │         │
  └──────────────────┴─────────────────────┴────────────┴────────────────┴─────────┘

QA BOARD
  ┌────────────────┬────────────┬──────────┬─────────────────┬─────────┐
  │ Ready for test │ In test    │ Rework   │ Ready to deploy │ Blocked │
  └────────────────┴────────────┴──────────┴─────────────────┴─────────┘
```

**Shared columns are the hand-offs** — the same status claimed by two boards, last column on one
and first on the next, so a drag on one board *is* the arrival on the other, with no second
action and nothing to notify:

| Status | On | Hand-off |
|---|---|---|
| Ready to develop | PM (last working column) · Dev (first) | PO → developer |
| Ready for test | Dev (last) · QA (first) | developer → tester |
| Ready to deploy | QA (last working column) · PM | tester → PO, who ships and moves it to Done |
| Rework | Dev (folded into In progress) · QA | tester → developer, the return path |
| Blocked | all three, last column | everyone's problem, visible wherever anyone looks |

- **`Done` is on the PM board only**, collapsed by default to a count strip (`Done · 41`), so the
  board does not fill with finished work and the deploy step stays a deliberate drag. `Done` is
  also where release readiness reads from, which is the PM's screen anyway.
- **`New` and `Refining` are on the PM board only.** Developers and testers see nothing until it
  is ready for them.
- Working columns per board, Blocked included: PM 5 (+ Done collapsed), Dev 5, QA 5. Every
  status is on at least one board, and the coverage table proves it.
- Legal moves seeded: 8 along the line, 4 for `Rework` (3 in, 1 out), 10 for `Blocked` (into it
  from every non-done status; out of it is the remembered return, not a listed move) — **22**.
- **Blocked on every board** shows the card with its `blocked from` chip, so a tester's blocked
  card on the Dev board reads *Blocked · from In test* and nobody on Dev mistakes it for theirs.
  Filter by *mine* / *this board's statuses* is the existing board filter.

### 2.5 Inside a lane: epic, stories, tasks — one rule at every level

A lane is an epic. Inside it, the same rule applies at each level: **an item whose children are
shown on this board is a pinned row header with its status chip; an item whose children are not
shown, or that has none, is a card in its status column.**

**Cards stack; rows are earned.** Every card item in the lane — the epic's own card and every
story that is a card here — stacks in the lane's **first band**, column by column, exactly as a
plain kanban lane does. A story gets a **row of its own only when its tasks are the cards on
this board** — any of them, even one, because "this story has one task here and it is blocked"
is exactly what the row says. So the PM board is mostly one dense band per epic (stories are
cards, tasks do not exist yet), and the Dev board is one band plus one row per story in flight.
A card-story never gets a row of its own.

**Layout rules an implementer copies:**
- **Column headers render once**, sticky under the filter bar, with the board-wide count, WIP
  limit and breach. Lanes below carry no headers of their own.
- **A story row's header is an indented sub-bar spanning the row** — key, title, `done/total`,
  status chip, `n on other boards` link, `⋯` — in the same idiom as the epic bar above it, one
  indent in. There is **no left gutter column**; the columns start at the lane's left edge on
  every board.
- **Empty cells are blank at rest.** A drop target outline appears only while a card is being
  dragged. No dash, no zero, no label.
- `Done` where collapsed is one narrow strip spanning the lane with the count and an expand
  affordance.

```
▾ ACME-101  Checkout redesign                                        38% by estimate · 5/13
     Ready to dev    In progress          In review        Ready for test     Blocked
     [S·150 lookup]  [E·101 Checkout]     [S·124 Saved]                                          ← first band: the
     [S·148 events]                                                                                 epic + card-stories
  ┌ ACME-118  Guest checkout   1/5 · 1 on QA board ────────────────────────────────────────────
  │  [T·122 copy]   [T·119 form] [T·121]                  [T·120 API]        [T·123 ⛔ from In progress]
  ├ ACME-130  Address book     0/2 ─────────────────────────────────────────────────────────────
  │                                                        [T·131] [T·132]
  └
▸ ACME-102  Payments v2   Refining · epic on PM board                  10% by count · 1/10
▸ No epic                                                                          — 3 stories
```

- **The epic** has its own card, drawn **in the lane header row**, in the column of its own
  status, draggable like any card. The header keeps its roll-up at the left, and the card sits in
  the grid row beside it, so the roll-up is never displaced by the card. On the PM board, an epic in `New` or `Refining` is therefore
  a card the PO grooms before any story exists under it. On Dev and QA the epic's status is
  usually not one those boards claim, so the header shows the chip and a link (`epic on PM board`)
  instead of a card.
- **A story** with tasks shown here is a pinned row header (key, title, `done/total` tasks,
  status chip, `Move to…` in its menu). A story with no tasks, or whose tasks are all on another
  board, is a card. So on the PM board stories are cards — tasks are created during refinement.
- **A task** is always a card.
- **An item drawn as a card has no pinned row header.** The pinned header exists only for an
  item whose children are the cards; the lane title bar already names the epic and carries its
  roll-up, so an epic that is a card on this board is named once in the bar and once in its
  column, never a third time at the left edge.
- A row appears on a board when the item *or any descendant* is in a status the board claims,
  showing only the descendants in this board's statuses. `n/m` counts done over all of them; a
  done task is in `Done`, which only the PM board shows, so a Dev or QA row's fraction is never
  full. The header links to the ones **this board does not show** (`1 on QA board`); because a
  status may be on two boards, "shown here" and "elsewhere" can overlap, and the link counts the
  not-shown-here set. When that set spans two boards the link reads `3 on other boards` and
  opens a popover naming each board with its count.
- A collapsed row registers no drop target, as a collapsed lane does today. WIP limits are still
  reported per column across every lane and row.

Contract impact, additive: `BoardIssueCard` gains `parentKey` and `blockedFromStatus`; an epic's
`allowedTransitions` stops being empty; `IssueInput` accepts a status for an epic; a card gains
`elsewhere: { boardId, boardName, count }[]` when it has descendants on another board.

### 2.6 What the PM board's card face shows

Readiness signals from fields that already exist, no new data: acceptance criteria present,
estimate set, epic set, target release, task count. A story in `Refining` with all four is what
"ready" looks like; the card says which are missing. A formal Definition-of-Ready checklist is
Phase 2 if the signals prove insufficient.

### 2.7 What the QA board's card face shows

The composite `/projects/:id/board` (the orphan in brief §8.5) already joins executions, reviews
and flagged cases to issues. Its QA half becomes the QA card face — linked cases and their latest
result (`4/6 passing · 1 blocked`), and `Run linked cases` opening the runner — and the composite
route is retired (R9). Phase 2: `BoardIssueCard.linkedCases` is a read the tracker module does
not own, so it is an event-fed projection. Flagged cases keep their existing homes: the case
list's health-signal filters and the Overview tiles that deep-link them.

### 2.8 Where the board picker lives, and what the Backlog is for

Work's segmented strip: **PM board · Dev board · QA board · Backlog · Table · Timeline ·
Calendar** — the project's boards in position order, `?view=board&board=<id>` as today. A project
that adds a fourth board gets a fourth segment. The Backlog is unchanged and is the *ordering*
view: the PM board says where a story is, the Backlog says in what order the ready ones get
pulled. Boards grouped by assignee, label, priority or release keep flat cards; the row structure
is specified for `EPIC` only.

---

## ~~3. The header (52px, unchanged height)~~ → superseded by plan 023 (D1, D3)

> **→ superseded by plan 023 (D1, D3)**, 2026-10-04. The header is now the 48px top bar, and the breadcrumb lives in each page's strip, ancestors only. Kept struck through as the record.

```
[≡]  [ Acme Ltd ]  Projects / ACME / Work / QA board          [ Search…  ⌘K ]  [page slot]  [◐]  [avatar]
      ^ company slot (M6)      ^ breadcrumbs (shell-owned)      ^ global search / palette
```

- **Company slot (new)** — reserved now, renders the company name as static text until M6.
- **Breadcrumbs move into the shell**, fed by route `handle.crumbs`; `PageHeader` keeps the title
  and the page's actions. The first crumb is the project (R1).
- **Project switcher leaves the header** (R1) — what pays for the breadcrumbs and the company slot.
- **Search becomes a palette** (⌘K and `/`), honest about scope by phase (§7).
- **Page slot** stays for the five document-mode screens (editors, runner, case detail, history,
  proposal), capped at one primary + one `⋯` overflow. No list screen portals into the header.

---

## 4. The user menu — the person's own things

```
Aleksandar K.  ·  Test Engineer on ACME, BETA
─────────────────────────────────────────
Timesheets                    /timesheets              ← moved from the rail
Timesheet approvals           /timesheets/approvals    (no count — the recorded decision stands)
─────────────────────────────────────────
Account ▸   Security · Tracker connections · AI assistants
Your access                   /account/access          (new)
Keyboard shortcuts
User guide                    /help                    (was "Help & documentation")
─────────────────────────────────────────
Sign out
```

The rule that decides what lives here: **it belongs to the person, not to a project or the
company.** A timesheet is the person's fortnight; the rail is scoped by the project switcher and
the timesheet is not. The approvals link is listed for everyone and the page explains its own
refusal, per the disable-don't-hide rule. **Your access** answers brief finding 15 without
un-hiding anything.

---

## 5. Project settings — one entry, a landing, a subnav

`/projects/:id/settings` is a real page: one card per section carrying one live fact.

```
General            ACME · 4 issue types · fortnightly time · budget on
Workflow           11 statuses · 3 kinds · 22 legal moves
Boards             PM (5+Done) · Dev (5) · QA (5) · every status covered
Code repositories  3 bound
Classification     5 vocabularies
Review policy      2 reviewers required on approve
Imports            last: Jira, 12 Aug                               [issue:import]
Notifications      2 channels                                       [integration:manage]
Issue tracker      Jira · bound to ACME-*                           [integration:manage]
Access & roles     14 members · 3 inherited from company
Project audit      last event 2 min ago                             [project:audit]
```

Every subpage carries the same subnav row. **No route moves.** Hidden entries stay hidden by the
same test: can the page be read?

---

## 6. The concrete defects (brief §8), fixed regardless

| Defect | Fix |
|---|---|
| `/run-schedules` orphan | `Schedules` tab on Test runs |
| `/board` composite orphan | Retired once §2.7 lands; until then linked from Overview |
| Duplicate `Staffing` | Project → **Team** · Company → **Capacity** |
| Duplicate `Audit trail` | Project → **Project audit** · Company → **Company audit** |
| Suites have no presence | `Suites` tab on Test cases; `…/suites` and `…/suites/:id` as routes |

---

## 7. Search and the palette

One control, `Search…  ⌘K`, also `/` outside an input.

**Phase 1 (no contract change):** cases (existing search), places (client-side index of the rail,
tabs, settings sections and the user menu), projects, key jump (`ACME-118` → the issue, `TC-118`
→ the case). Placeholder: `Search cases, or jump to…`.

**Phase 2:** issues and doc pages join after `GET /search` lands in `openapi.yaml`. Placeholder
becomes the mockup's `Search issues, cases, docs`. Locator `TopBar.CaseSearch` → `TopBar.Search`.

---

## 8. Naming, buttons, badges

### 8.1 Renames — only collisions and system words

| Today | Proposed | Reason |
|---|---|---|
| All projects | **Projects** | The mockup's word |
| Documentation | **Documents** | The mockup's word; kills the collision with Help |
| Help & documentation | **User guide** | It is the eleven-section guide |
| Staffing (project / company) | **Team** / **Capacity** | Duplicate label |
| Audit trail (project / company) | **Project audit** / **Company audit** | Duplicate label |
| Reports | **Test reports** | Answers "reports of what?" beside Analytics (closes 020 D-l by naming, not merging) |
| CI ingestion | **CI results** | The system's verb → the reader's noun |
| Repositories (settings) | **Code repositories** | Says what they are |
| Generated cases | **Proposals** (tab) | Says they are not in the repository yet |
| Review (settings) | **Review policy** | The page title already says it |
| Repository / Execution / Authoring / Project (groups) | *removed* | Subject headings inside a scope zone were the confusion |
| Board (single, seeded) | **PM board** / **Dev board** / **QA board** | Three boards, named by who moves the cards |
| To do (seeded status) | **Ready to develop** | It is the hand-off from grooming, and the name says who it is ready for |

### 8.2 Buttons and actions — one convention

- **One primary per screen**, top-right of the page header, verb + noun. More than two
  secondaries collapse into `⋯`. Case detail: `Edit` + `⋯ (History · Link to spec · Link issue ·
  Import)`.
- **Verb vocabulary:** `New …` for anything with its own route or document · `Add …` for a row
  that stays on the screen · `Invite a person` · `Connect …` for a credential or external system ·
  `Issue an API token` · `Start …` for a job the system runs · `Run` to execute now · `Link …` for
  a traceability edge. So `Create a release` → `New release`, `Generate one now` → `New proposal`.
- **On the boards:** `New issue` is the primary (level chosen in the dialog: epic, story, task).
  The card's `⋯` carries `Move to…` (the keyboard twin of the drag), `Block…` (asks for one line
  of reason), `Unblock` (on a blocked card; returns it to where it came from), `Add note`, `Open`.
  The QA card adds `Run linked cases` in Phase 2.
- **Disabled-with-reason**, one sentence shape, tooltip + `aria-describedby`: *"Needs Approve
  time, held by Test Lead and Project Manager."*
- **Destructive:** red, in `⋯` or the danger zone, confirm names the noun (`Delete run`), never OK.

### 8.3 Badges — only where a read already exists

| Badge | Read |
|---|---|
| My work | `GET /board?mine=true` — the *awaiting you* count, not a total |
| Test cases → Proposals tab | pending proposals |
| Test runs → CI results tab | unmatched tests in the last ingestion |
| Dev board segment | cards in `Ready to develop` |
| QA board segment | cards in `Ready for test` |
| PM board segment | cards in `Ready to deploy` |
| Timesheet approvals | **none** — the recorded decision stands |

Each board's badge is *what is waiting at its door*. Blocked counts show on the column header
of every board, not on the segment.

### 8.4 My work as the personal home

`/my-work` is the post-sign-in landing for anyone with a project grant; `/` stays the picker. It
gains an **Awaiting you** strip: reviews assigned · cards at your door on each board where you
hold the moving key · your blocked cards · proposals waiting · a plain `Timesheet approvals` link.

---

## 9. Decisions reversed — the counter-arguments (brief §9, plan 012)

House rule: struck through beside the original when implemented.

| # | Decision | Reversal | Counter-argument |
|---|---|---|---|
| R1 | ~~Project switcher in the header because the rail collapses to 64px~~ | ~~Switcher is the **Project zone header**~~ → **superseded by plan 023 (D1, D3):** the switcher is in the top bar | The frame is still stated at full width by the breadcrumb's first segment, and the collapsed zone header is the project key chip, which still opens the switcher |
| R2 | Breadcrumbs are per-page | Shell-owned from route handles | R1 and the capped page slot free the header; 44 files passing crumbs is 44 chances to disagree with the rail |
| R3 | Search is a cases-only field | A palette, honest by phase | *Field not rail entry* stands; only scope changes |
| R4 | Generated cases are their own group (§138) | A `Proposals` tab on Test cases | The label and the screen's banner deny membership before anyone clicks |
| R5 | `Reports` and `Analytics` are two adjacent entries | Two entries, one renamed | Kept two; `Test reports` says what it is of |
| R6 | Help is `Help & documentation` | `User guide` | Placement stands; only the collision goes |
| R7 | **B2:** every status claimed by exactly one column of every board (012) | A board claims a subset; the project's boards together claim every status | B2 protected against a card no board shows. The coverage table keeps that protection as a reported warning at the one place boards are configured, while a board is allowed to show one team's part of the lifecycle. The half of B2 that is a real invariant (once per board) stays a unique index |
| R8 | Overview is the first project entry | Seventh | It is the dashboard, read rather than worked; the six rows above it are where the day is spent |
| R9 | The composite `/board` is its own screen | Retired into the QA card face (Phase 2) | Its issue half duplicates the Dev board and its QA half is what a tester wants on the card |
| **R10** | **D31:** an epic's status is derived from its stories; an epic is a card in no column | An epic's status is **stored and movable**; its *completion* stays derived | D31 conflated two things. The percentage is honestly derived and stays so. The status was derived only because an epic had no lifecycle of its own — and it does: it is written, refined, made ready, shipped. Grooming an epic on a board needs a status the PO can move, and "all stories done" is a *nudge* on the epic card, not an automatic transition, so nothing ships without a person's drag |
| **R11** | Timesheets is a rail entry (ungrouped, outside the project frame) | User menu | The rail's rule is *a place in the product scoped by the switcher or the company*; a timesheet is the person's own fortnight, which is the user menu's rule. The brief's own §4.3 states that rule for credentials; the timesheet fails the same test |
| **R12** | `Done` is a Dev/QA column | `Done` is on the PM board only, collapsed to a count | Tested is not shipped. `Ready to deploy` is the tester's last word; the deploy confirmation is the PO's, and it is the figure release readiness reads |

Every other §9 decision holds: no project segment on cross-project routes, `/issues/:key`
without a project, one Work route with its views, forced collapse on the editor and runner,
routes never permission-guarded, `/org-chart` and `/teams/:id/board` outside `/admin`, no member
page under Teams, credentials in the user menu, no approvals badge.

---

## 10. Phasing (stack-cut, per the house task rule)

| Phase | Stack | Contract / schema change | Contents |
|---|---|---|---|
| 1 | `be` | R7 (drop the *unclaimed* 422, neighbour auto-claim, `statusCount`, coverage read); R10 (epic status stored, `allowedTransitions` on epics, `IssueInput.status` for epics); `parentKey`, `blockedFromStatus`, `elsewhere` on `BoardIssueCard`; **V2 migration:** `issue.blocked_from_status_id` nullable FK; seed the eleven-status workflow and the three boards for new projects | The three boards exist and are addressable |
| 1 | `ts` | regenerate `packages/client` | — |
| 1 | `fe` (boards) | none beyond the above | PM/Dev/QA segments, epic card in the lane header, story rows, Blocked column with `Block…`/`Unblock`, Done count strip, the coverage matrix, readiness signals on the PM card |
| 1 | `fe` (shell) | none | Nav model as data (`app/nav.ts`), the flat usage-ordered project zone, the five tabs, every rename, Timesheets into the user menu, settings landing + subnav, breadcrumbs from route handles, company slot, button convention, Phase-1 palette, `Your access` |
| 2 | `be` → `ts` → `fe` | `GET /search`; `linkedCases` on `BoardIssueCard` (event-fed projection); Definition-of-Ready if wanted | Global search; the QA card face; retire the composite board |
| 3 | M6 | `/c/{slug}` | Company switcher fills the reserved slot |

Tests to rewrite rather than patch: `app/AppShell.test.tsx` (locators follow the labels;
`Nav.ProjectSwitcher` moves inside `Nav.Rail`; `Nav.Link.Timesheets` becomes a `TopBar.*Link`),
`ProjectBoardView.test.tsx` and `Swimlane.tsx`'s tests (rows, the epic card), `boardCoverage.ts`'s
tests (the matrix), `BoardSettingsPage.test.tsx`, `app/noVelocity.test.ts`, and on the backend the
board and workflow service tests that assert the *unclaimed* 422 and the empty epic transitions.
**No page file is renamed** in Phase 1, so `citedFiles.test.ts` stays green.

---

## 11. What this does not do

- Does not merge Test reports and Analytics.
- Does not add an approvals badge.
- Does not move any route. Every path in the brief's §5 inventory keeps its URL.
- Does not change an existing project's workflow or boards; the seed is for new projects, and an
  existing project adds statuses and boards in Settings, where it already can.
- Does not automate any transition. "All stories done" nudges the epic; a person moves it.
- Does not touch the threshold screens (`/login`, `/accept-invitation`, `/sso/callback`).
