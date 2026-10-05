# Navigation & IA — current state, connection map, and a redesign brief

**Written 2026-09-06 for a redesign pass.** It is an *input document*, not a decision: it compiles
what the product has, how the screens reach each other, which navigation decisions are already
argued in the codebase (and therefore need a counter-argument rather than an edit), where the built
IA has drifted, and a set of redesign directions with their trade-offs.

Everything below was read out of the code and the specs on 2026-09-06 unless marked otherwise.
Line-level authority for each claim is in §12.

---

## 1. How to use this document

The redesign has to survive three things this product does that a generic TCMS does not:

1. **Two orthogonal scopes in one shell.** Every screen is *personal*, *project-scoped* or
   *company-scoped*, and the rail's whole job is to keep the reader clear on which. A project
   switcher that appears to change a company-wide list is the single failure mode the current IA
   was built to avoid, and half its recorded decisions are that argument.
2. **A permission model that changes the rail per person.** Nine roles, 89 permissions, and a
   standing *disable-don't-hide* rule with nine recorded exceptions. The rail a Test Engineer sees
   and the rail a Finance user sees are different objects.
3. **Multi-company is planned but not built.** M6 adds a company switcher and a `/c/{slug}` segment
   to every URL. A redesign that has no slot for it buys a second redesign.

**Precedence rule that already governs this repo:** where a mockup and the build disagree on *what
a screen shows*, the mockup wins; where they disagree on *what the system does*, the plan wins.
Where a build disagrees with a render, the **spec is amended** rather than the build. Redesign
proposals should say which of those they are invoking.

---

## 2. The product in one page

**Hierarchy (load-bearing):** Instance → **Company** (the isolation boundary; a client
organisation) → **Project** (a whole client product, not a feature area) → suites/plans/runs.
A suite is a *flat named collection*, never a folder.

**Two independent access gates:** company membership (authenticates, grants zero project
visibility), then an explicit project grant. Code checks permissions, never role names.

**Nine roles.** PROJECT scope: Viewer · Automation Service · Test Engineer · Project Manager ·
Test Lead. COMPANY scope: Account Manager · Finance · Company Admin. SYSTEM: Instance Admin.
Project Manager is **not** a rung on the Test Engineer→Test Lead ladder — they run delivery
(issues, board, releases, time, project staffing, project configuration) and author, approve and
execute nothing in the test repository. This matters for the rail: **the two heaviest users of the
product have almost disjoint destination sets.**

**Eleven functional areas**, in roughly the order they were built:
test repository (cases, suites, conditions, tags) · execution (plans, runs, executions, evidence,
defect links) · documentation & artifacts · reporting (report jobs) · delivery analytics ·
issue tracker (epics/stories/tasks, workflow, boards, backlog, labels) · releases ·
Jira/ADO import offramp · time (timesheets, approvals) · staffing (profiles, allocations,
absences, skills) · clients & billing (rate cards, budgets, invoices) · plus identity/admin
(teams, roles, tokens, SSO, audit) and AI surfaces (proposals, assistants, MCP/CLI).

---

## 3. The shell as built

| Part | Detail | File |
|---|---|---|
| Layout | Rail left, content right; the shell is a **layout route**, mounts once, survives navigation | `frontend/src/app/AppShell.tsx` |
| Rail width | `232px` expanded, `64px` collapsed (a 4px-grid amendment to the renders' 60px) | `AppShell.tsx:97,105` |
| Collapse | User preference in `localStorage` (`tcms.nav.railCollapsed`) **plus** three forced routes: `/cases/new`, `/cases/:id/edit`, `/runs/:runId/executions/:id`. Forcing does not overwrite the preference; the toggle is hidden where the route forced it | `AppShell.tsx:129-135` |
| Small screens | `hidden md:flex` rail; below `md` a `Dialog` drawer (`Nav.Drawer`) with the **same, always-expanded** content and no collapse toggle | `AppShell.tsx:786-798` |
| Header | Sticky, **fixed 52px** — the one measurement the whole shell is laid out against. Contents left→right: hamburger (<md) · **project switcher** · **case search field** · `TopStripTarget` (page commit-bar slot) · theme toggle · user menu | `AppShell.tsx:1245+` |
| Header slot | Pages portal their primary actions into the header via `topStripSlot` / `HeaderActions`; the group must not wrap (a wrapping group renders *through* the 52px header onto the page — audit finding 2.1) | `app/topStripSlot.tsx`, `app/HeaderActions.tsx` |
| Footer | Version + a single `Help & documentation` link | `AppShell.tsx:1400+` |
| Breadcrumbs | **Not a shell concern.** `components/PageHeader.tsx` renders `crumbs` per page; **44 files pass them**, the rest use the render kit's `.top` strip and raise their own `<title>` | `components/PageHeader.tsx` |
| Focus/a11y | `SkipToContentLink` first in DOM; route-change focus moved to `<main tabIndex={-1}>`; active rail item marked three ways (indicator, weight, `aria-current`) | `a11y/useRouteFocus.ts`, `AppShell.tsx:RailLink` |
| Error tiers | Root / route / region boundaries; the route boundary sits **inside** the shell so a broken page leaves the rail usable | `components/ErrorBoundaries.tsx` |

**Scale:** 76 routes, 65 lazily-loaded page chunks, ~100 page-level components, 42 screen families
in the design language's own map.

---

## 4. The navigation surfaces, exactly as built

### 4.1 The rail — verbatim structure

Order is top to bottom. `[gate]` means the entry is **hidden** without that permission.

**Wordmark row** (never scrolls) — `TCMS` → `/` · collapse toggle.

**Ungrouped block** (outside the project frame — nothing here is changed by the switcher):

| Entry | Route | Gate |
|---|---|---|
| My work | `/my-work` | — |
| Timesheets | `/timesheets` | — |
| Billing | `/billing` | `[billing:read]` COMPANY |
| All projects | `/` | — |

**Group `Repository`** — `Overview` (`/projects/:id`) · `Test cases` (`/cases`) ·
`Documentation` (`/docs`)

**Group `Execution`** — `Test plans` · `Test runs` · `Work` · `Releases` · `Staffing` ·
`Reports` · `Analytics` · `CI ingestion` (all `/projects/:id/…`)

**Group `Authoring`** — `Generated cases` (`/generated-cases`) — *one item*

**Group `Project`** — `Artifacts` · `Access & roles` · `General` · `Workflow` · `Boards` ·
`Repositories` · `Imports` `[issue:import]` · `Classification` · `Review` ·
`Notifications` `[integration:manage]` · `Issue tracker` `[integration:manage]` ·
`Audit trail` `[project:audit]`

**Group `Company`** — `Org chart` (`/org-chart`) · `Clients` `[client:read]` · `Staffing`
(`/staffing`) · `Directory` (`/directory`) · `Teams & roles` (`/admin/teams`) ·
`API tokens` `[token:issue]` · `Single sign-on` `[company:idp]` · `Audit trail` `[audit:view]`

**Totals: 27 entries minimum, 36 maximum, in one scrolling column.** Collapsed, group headings are
replaced by horizontal rules — so up to 36 unlabelled icons in one column, differentiated by five
divider lines.

### 4.2 The header

- **Project switcher** (`Select`, 168/212px) — moved here from the rail on 2026-08-29 so the frame
  is stated once at full width. Renders even with no projects to switch to.
- **Case search field** (`role="search"`, submits to `/search?q=`) — *cases only*, deliberately not
  scoped by the switcher beside it, no permission gate. Replaced a rail entry on 2026-08-29.
- **Top-strip slot** — where a page's Save / primary actions land.
- **Theme toggle**, **user menu**.

### 4.3 The user menu — the account scope

`Security` (`/account/security`) · `Tracker connections` (`/account/trackers`) ·
`AI assistants` (`/account/assistants`) · `Help & documentation` (`/help`) · `Sign out`.

Everything here is deliberately *not* in the rail: the rail is scoped by the project switcher and
these are not. A tracker credential and an AI assistant belong to a person, not a project.

### 4.4 In-page navigation (the second IA, largely invisible from the rail)

| Screen | Sub-navigation | Notes |
|---|---|---|
| **Work** (`/work`) | Five views in `?view=` — Board · Backlog · Table · Timeline · Calendar | One dataset, one shared URL filter (`useWorkFilter`). Rendered as a segmented strip, not tabs |
| Test cases | Suite tree sidebar + suite panel + filter bar; suites have **no route of their own** except `/suites/:id/history` | |
| Documentation | Page tree in a rail; `?linkCase=`, `#blockId` deep links | |
| Execution runner | Three layouts (Focus / Split / Grid) chosen in the URL; keyboard queue (`j`/`k`) | |
| Reports | Job cards → `/reports/jobs/:jobId` | |
| Generated cases | Status tabs in `?status=` | |
| Project settings | **No sub-navigation at all** — eight sibling rail entries | The mockup and two router comments both assume a `General · Workflow · Boards · Repositories · Imports` subnav row that does not exist in the build |
| Billing / Client / Person / Project overview | Panel stacks, no tabs | |

### 4.5 URL-state conventions (must survive any redesign)

- **Filter state lives in the URL** (`useSearchParams`), never in a store — so a filtered view is
  shareable and reload-safe. Work's five views share one filter *because* of this.
- Long forms get a route, not a modal (`/cases/:id/edit`, `/docs/:id/edit`, `/imports/new`).
- Issues are addressed by **display key** (`/issues/ACME-118`) with **no project segment** — the
  key is unique per company, and a reader following a key out of a commit message has no project id.
- Cross-project screens carry **no project segment** by design: `/my-work`, `/timesheets`,
  `/search`, `/teams/:id/board`, `/issues/:key`, `/staffing`, `/directory`, `/clients`, `/billing`,
  `/org-chart`, `/admin/*`, `/account/*`.

---

## 5. Complete screen inventory

Scope key: **P**ersonal · **Pr**oject · **C**ompany. Rail: ✓ has an entry, ✗ has none.

### 5.1 Personal / cross-project

| Route | Screen | Rail | Notes |
|---|---|:--:|---|
| `/` | Project picker (`ProjectsPage`) | ✓ All projects | Cards, not a table. Post-sign-in landing; fallback for an unseeable project |
| `/my-work` | My Work | ✓ | Two tables over one `GET /board?mine=true` — issues; executions & reviews. Private notes/due dates. **Not** a kanban (statuses differ per project) |
| `/timesheets` | Timesheet (own fortnight) | ✓ | Semi-monthly period; the only screen that creates a time entry |
| `/timesheets/approvals` | Approvals queue | ✗ | Linked from the timesheet. Costs `time:approve`; no rail badge (deliberate — no company-wide "where may I approve" read exists) |
| `/search` | Cross-project case search | ✗ | Reached **only** from the header field |
| `/issues/:key` | Issue detail — epic/story/task in one route | ✗ | Dispatches on `type.level` |
| `/teams/:teamId/board` | Team board | ✗ | Reached only from `/admin/teams`. Bounded by the *caller's* visibility |
| `/account/security` | Sign-in identities | menu | `?welcome=1` first-run arm |
| `/account/trackers` | Tracker connections | menu | Personal credential; the project holds the binding |
| `/account/assistants`, `/…/new` | AI assistants; connect | menu | Secret shown once — a route, never a dialog |
| `/help`, `/help/:sectionId` | User guide (11 sections) | menu + footer | `/help` redirects to section 1 |
| `/login`, `/accept-invitation`, `/sso/callback` | Threshold | public | No shell |

### 5.2 Project-scoped (`/projects/:projectId/…`)

| Path | Screen | Rail group | Notes |
|---|---|---|---|
| *(root)* | Project overview | Repository | Delivery half (epics + completion + unscheduled work + KPIs) over the quality half (`DashboardBody` regions). Budget burn panel when Billing is on |
| `/cases` | Case list + suite tree | Repository | 10-column table, health-signal filters |
| `/cases/new`, `/cases/:id/edit` | Case document editor | ✗ | **Forces the collapsed rail** |
| `/cases/:id` | Case detail (document + rail) | ✗ | Five header actions — the source of the header-wrap audit finding |
| `/cases/:id/history` | Case execution history | ✗ | Versions interleaved with results |
| `/cases/import` | `.feature` import | ✗ | From the case list only |
| `/suites/:suiteId/history` | Suite history | ✗ | **Suites have no list route** |
| `/docs`, `/docs/:pageId`, `/docs/:pageId/edit` | Documentation space | Repository | Per-block coverage markers; requirements *are* doc blocks |
| `/plans`, `/plans/:planId` | Plans; plan statistics | Execution | |
| `/runs`, `/runs/:runId` | Run list; run detail | Execution | Manual and ingested runs in one list, deliberately |
| `/runs/:runId/executions/:id` | The runner | ✗ | **Forces the collapsed rail**. Three layouts |
| `/run-schedules` | Run schedules | ✗ | **ORPHAN — no rail entry, no inbound link anywhere** |
| `/work`, `/work/new` | Work (5 views); quick add | Execution | |
| `/board` | Composite board (issues + executions + reviews + flagged cases) | ✗ | **ORPHAN — no rail entry, no inbound link.** Distinct from the project board inside Work |
| `/releases`, `/releases/new`, `/releases/:id` | Releases; readiness | Execution | Readiness answers "can we ship 2.4?" |
| `/staffing` | Project staffing | Execution | Names no other project, by design |
| `/reports`, `/reports/jobs/:jobId` | Report jobs; one report | Execution | Reports *of execution results* |
| `/analytics` | Delivery analytics (burn-up, cycle time, flow, estimate accuracy) | Execution | Reports *of delivery*. A recorded divergence from the mockup, which wanted one entry |
| `/ingestions`, `/ingestions/:jobId` | CI ingestion history; unmatched-test binder | Execution | |
| `/generated-cases`, `/…/:proposalId` | AI proposal queue; one proposal | Authoring | URL says `generated-cases`, API says `ai-proposals` |
| `/artifacts` | Artifact register | Project | |
| `/access` | Project access & roles | Project | Granted-here vs inherited-from-company |
| `/settings/general` | Identity, issue types, closing-work, time, budget, danger zone | Project | |
| `/settings/workflow` | Statuses, kinds, legal moves | Project | |
| `/settings/boards` | Boards, columns, coverage table, labels | Project | |
| `/settings/repositories` | Repository reference data | Project | Nothing is cloned or run |
| `/imports`, `/imports/new`, `/imports/:id` | Jira/ADO offramp: list, 4-step wizard, report | Project | Whole family gated `issue:import` |
| `/settings/classification` | Classification vocabulary | Project | |
| `/settings/review` | Review policy | Project | |
| `/settings/notifications` | Channels | Project | Reads cost `integration:manage` |
| `/settings/trackers` | Tracker binding | Project | Binding here, credential on the account |
| `/audit` | Project audit trail | Project | Hidden without `project:audit` |

### 5.3 Company-scoped

| Path | Screen | Rail | Notes |
|---|---|:--:|---|
| `/org-chart` | Org chart | ✓ | **Gated on nothing** — that is why it is not under `/admin` |
| `/clients`, `/clients/:id` | Clients; one client | ✓ (gated) | Zero clients is a first-class complete state |
| `/staffing` | Company capacity | ✓ | Refuses a PM by *naming the project screen they can use* |
| `/directory`, `/directory/:userId` | Skills directory; one person | ✓ | `/directory/:userId` is **the** person page — there is deliberately no member page under Teams |
| `/admin/teams` | Teams, roles, grants | ✓ | Company view of the access model |
| `/admin/tokens` | API tokens | ✓ (gated) | |
| `/admin/sso` | Identity providers | ✓ (gated) | |
| `/admin/audit` | Company audit trail | ✓ (gated) | Same component as the project trail |
| `/billing`, `/billing/invoices/:id` | Rate cards, budgets, invoices; one invoice | ✓ (gated) | |

---

## 6. How the screens connect

### 6.1 The five hubs

Everything with meaningful in-bound traffic funnels through five screens:

1. **Project overview** (`/projects/:id`) — links out to work, work/new, cases, plans, runs,
   releases (list, new, detail), issues by key, settings/general. It is the project's front door.
2. **Work** (`/work`) — five views, links to `/issues/:key` from every card/row, `/work/new`,
   `/settings/boards`.
3. **Case detail** — cases list, edit, history, doc pages (`#blockId`), doc-link flow
   (`/docs?linkCase=`), issue links.
4. **Issue detail** (`/issues/:key`) — parent/child/epic keys, `/projects/:id/work`, linked cases,
   linked spec blocks (`?block=`), repositories settings, release, time panels.
5. **Client** / **Billing** — clients ↔ invoices ↔ projects ↔ budgets, a closed money loop.

### 6.2 Full outbound link map (source screen → destinations)

```
ProjectOverview   → issues/:key, cases, plans, runs, releases, releases/:id, releases/new,
                    work, work/new, settings/general
WorkTable         → issues/:key, projects/:id, work/new
BoardCardFace/Swimlane/Backlog → issues/:key
ProjectBoardView  → settings/boards
BoardSettings     → work?view=board&board=:id
IssueDetail       → issues/:key, work            IssueNew    → issues/:key, work
StoryPanels       → issues/:key, work/new        TaskPanels  → issues/:key, settings/repositories, releases/:id
EpicPanels/TimePanels → issues/:key              TransitionMenu → settings/general
LinkedCasesPanel  → cases/:id                    LinkedSpecPanel → docs/:pageId(?block=)
CaseDetail        → cases, cases/:id, cases/:id/edit, cases/:id/history,
                    docs/:pageId#block, docs?linkCase=
CaseEditor        → cases, cases/:id, settings/classification
CaseGrid          → issues/:key, cases/:id       CaseList → cases/import
CaseSearch        → cases/:id (cross-project)    FeatureImport → cases, /search?q=
SuiteList/Panel   → suites/:id/history
CaseHistory       → cases, cases/:id, runs/:id   SuiteHistory → runs/:id
Docs              → issues/:key, projects/:id, cases/:id, cases/new?objective=, cases?q=,
                    docs, docs/:id, docs/:id/edit
PlanList          → plans/:id                    PlanStatistics → cases/:id, plans, plans/:id
PlanDocumentPanel → docs, docs/:id               ReviewSettings → plans/:id
RunList           → runs/:id                     RunDetail → plans/:id, runs/:id
ExecutionPage     → projects/:id, cases/:id, runs, runs/:id, runs/:id/executions/:id
ExecutionQueue    → runs/:id/executions/:id
Ingestions        → cases/:id, ingestions/:id, runs/:id      IngestionJob → runs/:id
Proposals         → generated-cases/:id          Proposal → projects/:id, cases/:id, generated-cases
Reports (JobRow)  → reports/jobs/:id             ReportView → reports    PinReport → artifacts
Analytics(Estimate)→ issues/:key
Releases          → releases/:id, releases/new, work?view=timeline
ReleaseDetail     → issues/:key, plans, reports  Readiness → issues/:key, plans, runs/:id
Roadmap/Calendar  → issues/:key, releases/:id, releases/new
Imports list      → imports/:id, imports/new, settings/trackers
ImportWizard      → imports/:id, settings/workflow (round-trip with state preserved)
ImportReport      → work
Artifacts         → docs/:id
Teams             → teams/:id/board
Directory         → directory/:userId            SkillsPanel → directory?skill=
Clients           → clients/:id                  ClientPage → projects/:id
ClientCreate      → clients/:id                  RateCardPanel → clients/:id
Billing/Draft/InvoiceList → billing/invoices/:id
Invoice           → clients/:id, projects/:id    BudgetPanel → projects/:id
EntryTable (time) → issues/:key
Dashboard tiles   → cases?automationStatus=…     (filter deep-links)
```

### 6.3 Entities behind the connections

`company → project → { suite, test_case (+versions, steps, conditions, classifications), test_plan
→ test_run → test_execution → step_result/evidence/defect_link, doc_page → doc_block (+doc_link
traceability edge), artifact, issue (+ board, backlog rank, label, release), import_run,
time_entry → timesheet_period, allocation/absence/person_profile, budget }`; company-level:
`client → rate_card → invoice → invoice_line`, `team`, `role_assignment`, `api_token`,
`identity_provider`, `audit_event`.

The traceability spine the UI exposes is: **doc block → case → execution → defect link → issue →
release readiness**, and separately **issue → time entry → approved hours → invoice line**. No
current screen shows either spine end to end.

---

## 7. Permission-driven visibility — the rule and its nine exceptions

**Standing rule (§31): disable, don't hide.** A control a person lacks the key for is visible and
disabled with the reason; a rail entry leads to a page the reader can *read*, and the page explains
its own refusal. Routes are **never** guarded by permission — a pasted link must land on a sentence
naming who holds the key, never on a 404 or a redirect, because that would be the client answering
an authorization question the server already answers.

**Nine entries break the rule and are hidden.** Each has a recorded argument; a redesign that
changes any of them needs to answer the argument, not the entry:

| Entry | Key | Recorded reason |
|---|---|---|
| Audit trail (project) | `project:audit` | Product-owner direction (§242): advertising an audit trail to somebody who may not read it invites the request the permission exists to refuse |
| Audit trail (company) | `audit:view` | The pair to the above; they must agree |
| Notifications | `integration:manage` | Reads are refused, so the page is 100% refusal — no read-only view exists |
| Issue tracker | `integration:manage` | Same module, same key, same argument |
| API tokens | `token:issue` | The read is *refused, not narrowed* — an empty array would be a claim about the company told to somebody who may not ask |
| Single sign-on | `company:idp` | Strongest case: a readable page would disclose issuer URLs, client ids and claimed domains |
| Imports | `issue:import` | The report names the source site, the connecting account and the operator |
| Clients | `client:read` | A Test Engineer has no business learning from a sidebar how many customers their employer has |
| Billing | `billing:read` | What a colleague costs is not something to advertise the existence of to the colleague |

The test that decides it: **can the page be read?** — not *can it be written*. `Review`,
`Access & roles`, `Workflow`, `Boards`, `Repositories`, `General`, `Staffing` and `Reports` are all
listed for everybody precisely because each renders read-only for a caller without the write key.

Both gates **fail closed while pending** (the entry appears when the answer arrives) and **fail open
when `/me/permissions` itself failed** (a wrongly-hidden entry takes an administrator's own screens
away with nothing on screen to say why).

---

## 8. What is wrong with the current IA

Findings, most structural first. Each names the constraint a fix has to clear.

1. **Up to 36 rail entries in one flat scroll, of which 8–12 are project settings.** No settings
   landing page and no sub-navigation exist, although both the mockup rail and two router comments
   assume a `General · Workflow · Boards · Repositories · Imports` subnav. The rail is doing a job
   a settings section should do.
2. **Two labels appear twice in one rail.** `Staffing` is both `Execution → /projects/:id/staffing`
   and `Company → /staffing`; `Audit trail` is both project and company. Identical words, different
   scope, ~15 rows apart — the exact ambiguity the Company/Project split exists to prevent.
3. **The group taxonomy is mixed.** `Repository`, `Execution` and `Authoring` name *subject matter*;
   `Project` and `Company` name *scope*. `Authoring` holds one item. Project `Staffing` sits under
   `Execution` while company `Staffing` sits under `Company`. `Analytics` is filed under `Execution`
   even though its own comment argues it belongs under `Project`.
4. **Three "work" surfaces with overlapping names and no shared entry point.** `My work`
   (personal, cross-project, two tables) · `Work` (project, five views) · the composite `Board`
   (`/projects/:id/board`, orphaned) · `Team board` (`/teams/:id/board`, reachable only from Teams).
5. **Two fully orphaned routes** — `/projects/:id/board` and `/projects/:id/run-schedules`: no rail
   entry, no inbound link from any screen. Run scheduling is a shipped feature that cannot be
   reached without typing a URL.
6. **Suites have no navigation presence.** They are a panel inside the case list; only
   `/suites/:id/history` is addressable. The mockup rail lists `Suites` as a peer of `Test Cases`.
7. **`Reports` and `Analytics` are adjacent entries answering "reports of what?" differently.**
   The mockup wanted one entry; the divergence is recorded (020 D-l) and unresolved.
8. **`Documentation` (project doc pages) vs `Help & documentation` (product guide)** — one shell
   naming two destinations with the same word. Partially mitigated by the second label; the
   collision itself is still there, and Help is only in the account menu and the footer.
9. **Search is cases-only.** The mockup's topbar promises *"Search issues, cases, docs"* with a `/`
   shortcut. Issues and docs have no cross-project search; the field's `aria-label` correctly says
   "Search test cases", which makes the shortfall honest rather than fixed.
10. **The collapsed rail loses the grouping.** 27–36 unlabelled icons in one column separated by
    five rules — the grouping is explicitly the thing that tells a reader Reports is about execution
    and Classification is about the project.
11. **Breadcrumbs are a per-page concern (44 files), not the shell's.** The mockup puts them in the
    topbar. The 52px header is already contested by the switcher, the search field and the page
    action slot — the audit measured a page's action group wrapping *through* the header onto the
    content.
12. **No company switcher slot.** M6 adds `/c/{slug}` to every URL and a company picker in the app
    bar. The header currently has no room and the rail has no zone for it.
13. **Mobile is the same 30+ item list in a drawer.** No prioritisation, no progressive disclosure.
14. **No personal home.** `/` is the project picker; a person who works across four projects has
    `My work` and nothing that aggregates releases, approvals or reviews awaiting them.
15. **The rail's shape changes per role with no explanation on screen.** Nine hidden entries mean
    two colleagues screen-sharing see structurally different navigation and nothing says why.
16. **Nothing surfaces counts.** No badge anywhere — deliberately for timesheet approvals (no
    company-wide read exists), but by omission for review requests, unmatched CI tests, failed
    ingestions and proposals waiting.

---

## 9. Decisions that are load-bearing — overturn only with an argument

These are argued in code comments or specs. A redesign may reverse any of them, but should reverse
them *knowingly* and write the reversal down struck-through beside the original (house rule).

| Decision | Why it exists |
|---|---|
| Cross-project screens carry no project segment | A project in the URL would be an arbitrary one, and the switcher would appear to change a list it cannot change |
| The project switcher lives in the header, not the rail | The header does not collapse to 64px, so the frame is stated once at full width beside the search that escapes it |
| Search is a header *field*, not a rail entry | The entry was a link to a screen whose first act was asking for a query |
| `/issues/:key` has no project segment | A key does not say its level, and a reader arriving from a commit message has no project id |
| One route, five views for Work | Five rail entries would be five names for one place, and the shared URL filter would be invisible between them |
| The rail collapses on the case editor and the runner | The reader is following a procedure at a measure; the rail is navigation they are demonstrably not doing |
| Routes are never permission-guarded | A guarded route is the client answering an authorization question the server already answers |
| Org chart lives at `/org-chart`, not `/admin/org-chart` | Every member may open it; filing it under a prefix that reads "administration" teaches the opposite of what is true |
| The team board is `/teams/:id/board`, not under `/admin` | Same argument; `/admin/teams` is gated on `company:teams` and the board is gated on nothing |
| There is no member page under Teams | The three staffing panels live on `/directory/:userId`; building a member page would have Staffing port Identity's screen |
| Account credentials (trackers, assistants, security) are in the user menu | They belong to a person; the rail is scoped by the project switcher |
| Help is in the account menu and footer, not the rail | The rail's every item is a *place in the product*; Help is about the product |
| No approvals badge on the rail | The count needs a read that 403s without a project-scope key, and there is no company-wide "where may I approve" question to ask |
| `/reports` vs `/analytics` are two entries | One is *of* execution results, the other *of* delivery convergence; the reports screen has saved definitions pointing at its route |

---

## 10. Redesign directions

Three coherent directions, then a recommendation. None is a decision — they are framings the
redesign should choose between or synthesise.

### Direction A — Scope-tiered rail with settings folded (smallest jump, closest to the mockup)

Three fixed zones, always in the same order, never mixed:

```
[ TCMS ▸ ]                        wordmark + collapse
── YOU ──────────────────────     My work · Timesheets · (Approvals) · Assigned reviews
── PROJECT: [ switcher ] ───      Overview · Work · Releases · Repository ▸ · Quality ▸ · Settings ▸
── COMPANY ──────────────────     Org chart · People ▸ · Clients · Billing · Administration ▸
```

- **`Settings ▸` becomes one entry** leading to `/projects/:id/settings`, a landing page with the
  subnav the mockup and the router comments already assume — General · Workflow · Boards ·
  Repositories · Imports · Classification · Review · Notifications · Issue tracker · Access · Audit.
  Removes 8–12 rail rows and gives the gated entries a home where their absence is legible.
- **`Repository ▸`** folds Test cases · Suites · Conditions · Documentation · Artifacts ·
  Generated cases (killing the one-item `Authoring` group and giving Suites a presence).
- **`Quality ▸`** folds Test plans · Test runs · Run schedules · CI ingestion · Reports · Analytics
  (rescuing the orphaned run schedules).
- **`People ▸`** folds Staffing · Directory · Teams & roles, and the project's own staffing is
  renamed **`Team`** or **`Who's on this`** under the project zone — killing the duplicate label.
- The project switcher moves *into the rail as the zone header* (or stays in the header and the zone
  header echoes it). This is the one reversal Direction A demands; the counter-argument is in §9.

**Cost:** one extra click to any settings screen. **Gain:** the rail becomes ~14 rows,
scope-legible at a glance, and legible when collapsed.

### Direction B — Two-level rail (icon rail + contextual panel)

A 64px icon rail of **scopes** (You · Project · Company · Admin) plus a 200px contextual panel
showing only that scope's destinations. The panel is what changes; the icon rail never does.

- Solves the collapsed-rail illegibility problem structurally (the icon rail *is* the collapsed
  state, and it stays labelled by scope).
- The natural home for the **M6 company switcher**: it becomes the top of the icon rail.
- Handles the Test Engineer / Project Manager disjoint-destination problem: each lands in the scope
  they live in and never scrolls past the other's screens.
- **Cost:** the widest visual change; the rail's total width grows; the "everything at once" quality
  the current rail has is lost, and discovery gets worse for occasional screens.

### Direction C — Slim rail + command palette + per-project index

Keep ~8 rail destinations (My work · Timesheets · All projects · Overview · Work · Repository ·
Quality · Settings). Move the long tail to:
- a **⌘K palette** over routes, projects, issues by key, cases by key, doc pages and people;
- a **project index screen** ("everything in this project") that is genuinely a page rather than a
  list of links the rail already shows.

Also upgrade the header search to the mockup's promise — issues, cases *and* docs, with the `/`
shortcut — which the palette can share a backend with.

**Cost:** discovery for people who do not learn a palette; needs new backend search surface for
issues and docs. **Gain:** scales to M6 and to whatever the next module adds without touching the
rail again.

### Recommended synthesis

**A as the structure, C as the accelerator, B's icon rail as the collapsed state.**

1. Three scope zones (A), with `Settings`, `Repository`, `Quality` and `People` as folders.
2. A real project settings landing with subnav — this alone removes a third of the rail.
3. A ⌘K palette and a genuinely global search (issues + cases + docs), replacing the cases-only
   field with something whose label can honestly say "Search".
4. When collapsed, show **zone icons with labels** rather than 36 unlabelled destination icons.
5. Fix the four concrete defects regardless of direction: the orphaned `/board` and
   `/run-schedules`, the duplicated `Staffing` and `Audit trail` labels, Suites having no presence,
   and breadcrumbs living in 44 pages instead of the shell.
6. Reserve a header slot for the M6 company switcher now, even if it renders nothing.

---

## 11. Constraints any implementation must clear

- **`openapi.yaml` is the contract**; two generated copies (`frontend/src/api/schema.d.ts`,
  `packages/client/src/schema.ts`) must be regenerated together. A nav redesign needing a new read
  (a global search, a badge count) is a contract change first.
- **Gates run on `shanks`**, not locally: `~/bin/tcms-remote frontend npm run test:run`,
  `… npm run build`, `… npm run lint`.
- **Tests that will redden on a rail change:** `src/app/AppShell.test.tsx` (24 assertions covering
  the collapse, the drawer, the credential entries, the Billing gate, the forced-collapse routes,
  the header switcher and search), `src/app/noVelocity.test.ts`,
  `packages/client/src/test/citedFiles.test.ts` (any file path named in a comment must exist —
  renaming `AppShell.tsx` or a page file reddens the **packages** job), `tierClaims.test.ts` (a
  comment claiming somebody lacks a permission is checked against the roles spec).
- **Test ids are locators**: `Nav.Rail`, `Nav.Drawer`, `Nav.Wordmark`, `Nav.CollapseToggle`,
  `Nav.ProjectSwitcher`, `Nav.HamburgerButton`, `Nav.Link.<LabelWithoutNonLetters>`,
  `TopBar.CaseSearch`, `TopBar.ThemeToggle`, `TopBar.UserMenuButton`, `TopBar.*Link`, `AppFooter`.
  Renaming a rail label renames its locator.
- **Styling:** Tailwind utilities only, tokens as CSS custom properties bridged by `@theme inline`;
  no `sx`, no CSS-in-JS, no second stylesheet. Rail rows are inset pills (`6px 10px`, r5, 9px gap);
  the active state in the kit is the tinted fill **alone** (the build additionally draws a 3px bar —
  a recorded styling divergence).
- **A11y floor:** three-way active marking, `aria-label` on every icon-only link, tooltips only in
  the collapsed rail, skip link first in the DOM, `prefers-reduced-motion` honoured on the width
  transition, and WCAG 2.2 *Dragging Movements* (every drag has a keyboard twin) wherever ordering
  appears.
- **Work is sequential in this repo** — one agent at a time; the shell, the router and 100 page
  files are one contended surface.

---

## 12. Sources

| Subject | Where |
|---|---|
| Rail structure, groups, gates, header, footer | `frontend/src/app/AppShell.tsx` (1432 lines, heavily annotated — `projectNav()` at 328, `RailContent` at 851, `TopBar` at 1245) |
| Route topology and per-route rationale | `frontend/src/app/router.tsx` (1366 lines, 76 routes) |
| Header action slot | `frontend/src/app/HeaderActions.tsx`, `frontend/src/app/topStripSlot.tsx` |
| Breadcrumbs / page titles | `frontend/src/components/PageHeader.tsx` (44 callers) |
| Product feature inventory | `docs/features.md` |
| Roles & permissions | `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A3; convenience copy `docs/specs/permission-matrix-summary.md` |
| Entity model | `docs/specs/2026-07-30-tcms-erd.md` |
| Design language (rail collapse §4, family map §8, open problems §9) | `docs/specs/2026-08-28-design-language.md` |
| Intended IA from the expansion mockups | `docs/mockups/assets/shell.js` (`buildRail`), `docs/mockups/README.md`, `docs/mockups/index.html` |
| Screen renders (pattern families) | `docs/specs/assets/renders/` |
| Prior audits incl. header-wrap and rail styling findings | `docs/audits/2026-08-30-renders-vs-implementation.md`, `…-styling.md`, `docs/audits/001-fe-react-conformance-r1.md` |
| Module plans that shaped the IA | `docs/plans/implemented/009`–`020` |

### The mockups' rail, for comparison

`docs/mockups/assets/shell.js` draws a materially simpler IA than the build:

```
My Work (7) · Projects
  └ [PROJECT KEY / name]
      Overview · Work · Releases · Documents · Test Cases · Suites · Plans · Runs · Reports · Settings
COMPANY   Clients · Timesheets (4) · Billing* · Staffing · Teams · Directory
ACCOUNT   My profile · Help
```

Ten project entries with **one `Settings`**, an inline project sub-tree under `Projects`,
breadcrumbs in the topbar, a global search reading *"Search issues, cases, docs  /"*, count badges
on My Work and Timesheets, and a role switcher for observing how the rail changes. `Billing*` is
absent — not greyed — for anyone but Finance and Company Admin. The build has none of the badges,
none of the breadcrumb placement, a cases-only search, and eight settings entries where the mockup
has one.
