# 002 — Design audit, round 1

**Subject:** the TCMS web UI (SPA), all design altitudes
**Date:** 2026-08-27
**Mode:** A — BASELINE (first round under this ID)
**Auditor:** `design-auditor` (read-only on product code, design files and specs)

> **Read-only contract.** Nothing in the application, the database or the source was
> changed by this audit. No destructive control was fired, no form submitted, no
> native dialog triggered. This report **points**; `fe-react` fixes.

> ### Correction — 2026-08-27, after the captures were archived
>
> **Scope of this note: the evidence header and the counts below only. No finding in this
> file has been altered, re-rated, renumbered or removed.** Corrections that affect findings
> live in the sibling document `002-design-audit-r2-ux.md`, which supersedes nothing here and
> restates nothing — it records what a second pass changed.
>
> 1. **Widths: five, not four.** The header originally read *"4 widths (1568 · 1440 · 900 · 501)"*.
>    **1512** was omitted, and it is the width behind four captures — `/`, `/my-work`,
>    `/projects/1/cases` and the first project-overview shot. Corrected in the table below.
>    The per-screen widths in *Screens walked this round* were already right; only the summary
>    line was wrong.
> 2. **The filter-control count is 29, not 28.** Recounted from the pixels of
>    `07-project-cases-w1440.jpg`: search + 3 dropdowns (4) · Status 4 · Automation 4 ·
>    Priority 4 · Health 11 · Tag field 1 · "Not filed" 1. Findings **E2**, **F1** and **D3**
>    each say "28"; the number is one low. **The findings are otherwise unaffected** and are
>    left as written.
> 3. **The `/board` and `/help` captures are 900 px**, as this file already states. Noted only
>    because an intermediate index of the archive guessed 1280 for them; **that guess was wrong
>    and this file was right.** No claim resting on those shots needs revisiting for width —
>    though r2 does file one board finding that 900 px makes visible and r1 missed.
>
> **Evidence archive:** the 21 unique captures behind this report are now on disk at
> `docs/audits/assets/002-design-audit/`, with `MANIFEST.md` mapping each file to its route and
> its true pixel width read from the JPEG header. Widths there are authoritative over any
> resize call recorded in the transcript.

---

## Scope and evidence

| | |
|---|---|
| **Evidence mode** | **LIVE** |
| **Target** | `https://tcms.203-0-113-10.sslip.io/` — the deployed instance, TCMS `0.9.0` (footer) |
| **Capture driver** | `claude-in-chrome` (Playwright MCP not connected this session) |
| **Environment / account** | **Production-shaped deployment**, driven inside the user's own authenticated Chrome session as `Aleksandar Kotrulev` / `alexandur.kotrulev@gmail.com`. Company `Reflex`, 1 project, 1 case, 2 runs, 2 board items. **This is real data on a real session** — the walk stopped at every irreversible control. |
| **Colour scheme captured** | **Dark** (the session's persisted preference). Light mode is source-read only. |
| **Widths captured** | **1568 · 1512 · 1440 · 900 · 501** CSS px — **five**, corrected 2026-08-27; see note above |
| **Stack** | Vite 8 · React 19 · TS strict · **MUI v9** (X DataGrid) · React Router 7 · TanStack Query. No Tailwind, no Next.js, no RSC. |
| **Token authority** | `frontend/src/index.css` (CSS custom properties) → `frontend/src/app/theme.ts` (`createTheme`, `cssVariables`) |
| **Prior rounds** | `docs/audits/001-frontend-audit-r1.md`, `docs/audits/2026-08-15-flow-review-and-improvements.md`. This is a **different subject** (design, not frontend-code conformance), hence a new permanent ID. |

### Screens walked this round

| Screen | Widths |
|---|---|
| `/` — Projects | 1512, 1440 |
| `/my-work` — My work board | 1512 |
| `/projects/:id` — Project overview (dashboard) | 1440 (2 scroll positions) |
| `/projects/:id/cases` — Test cases (list + filters + suites) | 1440 (2), 900, 501 (2) |
| `/projects/:id/cases/:caseId` — Case detail | 1440 (2 scroll positions) |
| `/projects/:id/cases/:caseId/edit` — Case edit | 1440 |
| `/projects/:id/runs` — Test runs | 1568 |
| `/projects/:id/reports` — Reports | 1568 |
| `/projects/:id/board` — Board | 900 |
| `/help` → `/help/getting-started` — Help | 900 |
| Account menu (header popover) | 900 |
| Left rail, scrolled to the `COMPANY` group | 900 |
| `/projects/1/cases` — **invalid project id**, error path | 1512 |

### Not reached — declared Unverifiable, not passed

| Not reached | Why | What would settle it |
|---|---|---|
| `/login`, `/accept-invitation`, `/sso/callback` | Reaching them requires **signing the user out of their real session**. Deliberately not done. | A second browser profile, or an incognito capture the user drives |
| Light colour scheme rendered | Toggling the theme writes the user's persisted preference | One capture of each key screen after a toggle the user consents to |
| Case **create**, run **create**, plan/suite/bulk **dialogs** | Every one of them is a form whose only exits are Save (a write) or Cancel; opening is safe, but several are gated behind a control that also writes | A seeded local `docker compose` instance |
| Execution screen (`/runs/:id/executions/:id`) — the **highest-frequency screen in a TCMS** | Reaching it means opening a run and recording a result against production data | Local instance. **This is the single biggest gap in this round's coverage.** |
| `/projects/:id/docs`, `/plans`, `/artifacts`, `/ingestions`, `/search`, `/org-chart`, `/admin/*`, `/access`, `/settings/*`, `/audit` | Time-boxed, or empty on this tenant | A later round on a seeded instance |
| Automated a11y scan (axe / Lighthouse) | **Deliberately out of lane** — belongs to qa `accessibility-testing` | See Handoffs |

---

## Applicability matrix

| § | Altitude | Verdict | Probe that decided it |
|---|---|---|---|
| **A** | Visual identity | **Applicable** | `index.css` states an explicit design thesis; live captures at 4 widths |
| **B** | Token system | **Applicable** | `index.css` + `theme.ts` + `lib/statusColors.ts` + `lib/labels.ts` colour maps |
| **C** | IA & shell | **Applicable** | `app/AppShell.tsx` nav map read; rail driven live at 3 widths |
| **D** | Usability heuristics | **Applicable** | LIVE, primary tasks walked |
| **E** | Flow friction | **Partially applicable** | Read paths walked; **write paths not walked** (production). Findings scoped accordingly |
| **F** | Responsiveness budget | **Applicable (widths) / Partial (feedback)** | 4 widths driven. Press-feedback and the ~4 s acknowledgement need write paths → deferred |
| **G** | UI code conformance | **Applicable** | Source read this round, `file:line` cited |
| **H** | Copy voice | **Applicable** | Live copy + `lib/labels.ts`, `lib/describeError.ts` |
| **I** | Charts & data display | **Applicable** | Overview renders 3 charts; runs list renders a result bar |
| **J** | Iconography & social imagery | **Applicable** | 112 icon imports enumerated |
| **K** | Brand fidelity | **N/A** | **No brand book exists in this repo.** `index.css`'s header states an invented identity and its rationale; **A judges it on its own stated terms**, which is the correct treatment and is what §A does below |
| — | `theme-presets` | **Never an audit lane** | — |
| — | `experience-plan.md` | **Absent** | `docs/plans/` holds no `NNN-*/experience-plan.md`. C and E are judged on `ux-patterns` / `ux-review` alone; **no finding is routed to `be-planner` this round** |

**Applicable: 10 · N/A: 1 (K) · Unknown: 0.**

---

## The headline

This is a **strong** design system being undermined at three specific seams, not a weak one.

The token file is one of the better ones I have read: it states a *thesis* ("status colour is
load-bearing DATA in a test management tool, so the brand palette deliberately retreats"),
authors dark mode as a **second palette rather than an inversion**, and `lib/statusColors.ts`
enforces enum→colour exhaustively at the type level. The copy voice is genuinely good — empty
states explain *why* ("A case needs a suite to live in"), `describeError.ts` refuses to leak a
stack trace, and `labels.ts` reasons about word choice ("Queued" over "Pending") in comments.
`format.ts` documents its UTC-everywhere rule and its reason; that is a **deliberate project
convention and is filed as such, not as a finding**.

The three seams where it comes apart:

1. **The status palette is doing five orthogonal jobs.** `--color-passed` simultaneously means
   *test passed*, *run completed*, *report ready* and *case approved*. On `/runs` this produces a
   green **Completed** chip on the same row as a fully-red result bar and **0%** pass rate. The
   thesis that spends the whole saturation budget on status is correct; the budget is being spent
   five times over.
2. **The Test cases screen — the app's front door — buries its own content.** 28 always-expanded
   filter controls push the first case row below the fold at every width captured, and at the
   `md` breakpoint (900 px) the shell takes 55% of the viewport and the grid gets ~330 px.
3. **One word, several concepts.** "Documentation" is two different destinations *visible on the
   same screen*; "Audit trail" is two nav rows; and "no value" is rendered four ways on a single
   case-detail page.

**Coverage caveat that must not be lost:** the execution screen — where a tester spends most of
their day — **was not seen this round**. Sections D, E and F carry a coverage hole exactly where a
TCMS is used hardest.

---

## Findings

**33 findings survived hostile self-review** (from 41 drafted; 8 cut for missing evidence,
neighbour ownership, or being deliberate conventions).
IDs are **permanent** — `<Section><n>` — and are reused, never renumbered, in every later round.

Severity: **blocker** — task cannot complete / data lost · **major** — heuristic clearly violated
on a primary flow, or a defect family across the app · **minor** · **nit**.

---

### A — Visual identity

> **Judgment.** The identity is deliberate, restrained and internally reasoned, and it survives
> dark mode intact — the recessive-brand thesis is the right call for this product and is
> visibly held: nothing on this instance uses steel blue on data. Where it fails is the other
> half of its own rule. The thesis reserves saturation for *status*, then lets four more
> vocabularies borrow the same six colours, and lets a status colour escape onto an action
> button. The result is not ugly; it is **ambiguous**, which for a triage tool is worse.

**A1 · major · "Completed" is green, on a run that failed everything.**
`/runs`, 1568 px: **TR-2** shows a `Completed` chip tinted `--color-passed` (green) on the same
row as a fully red result bar and a **0%** pass rate. A reader scanning the Status column for
"which runs are healthy" gets exactly the wrong answer from the colour, and the correcting
information is 400 px to the right. Root cause is B1; filed here because the *visible* damage is
identity-level.
*Fix direction:* give run lifecycle its own neutral/progress ramp — `design/visual-identity`,
applied by `fe-react`. **Siblings: B1, I1.**

**A2 · minor · A status colour is used for an action control.**
Case detail `TC-2`, right rail, 1440 px: the **Quarantine test** button is an outlined button in
amber. Amber is `--color-blocked` — a *test status*. `index.css:12-14` states the inverse rule
explicitly ("Brand steel is used for actions and active nav only — never on data"); the
symmetric half, *status colour is never used for an action*, is not held. It is also the only
amber control on the page, so it out-shouts the primary action.
*Fix direction:* actions draw from the action ramp; severity is communicated by placement and
copy — `design/visual-identity` → `fe-react`.

**A3 · minor · Two "filled steel" controls sit adjacent meaning different things.**
`/board`, 900 px: `Everyone` (a filter *state*) and `New task` (a primary *action*) are both
solid `--color-action-primary`, 40 px apart, with `Assigned to me` outlined between them. Fill
therefore encodes "selected" and "primary" simultaneously in one 250 px strip.
*Fix direction:* a segmented control should not borrow the primary-button fill —
`design/visual-identity` → `fe-react`.

**A4 · minor · Priority is a fifth colour encoding, introduced without a legend.**
Cases grid and case detail, 1440 px: `Medium` carries a small coloured vertical bar to its left,
unlabelled and unexplained. The reader is now tracking status colour, lifecycle colour, health
severity colour, run colour and priority colour — five vocabularies, one palette, no key
anywhere in the product.
*Fix direction:* either give priority a non-colour encoding (weight, position) or publish a key —
`design/visual-identity` → `fe-react`.

**A5 · nit · The rail's internal scrollbar is effectively invisible in dark mode.**
`/help`, 900 × 523: the rail scrolls internally and its thumb is a ~3 px bar barely distinguishable
from `--color-border`. See C4 for the consequence.

---

### B — Token system

> **Judgment.** Tier discipline is good, purpose-naming is good, there are **zero escaped
> primitives in components** (verified: the only hex outside `theme.ts` across all `.ts`/`.tsx`
> is a `placeholder="#b7791f"` in a colour-picker field, which is correct), and dark mode is a
> genuine swap rather than a filter. Two structural problems: the token file has **no viz tier
> at all**, so charts raid the status tier; and the one duplication the system knowingly accepts
> is the only duplication in this repo that **nothing checks**.

**B1 · major · The status tier is overloaded across five unrelated vocabularies.**
`frontend/src/lib/labels.ts:352-357` (`runStatusColors`), `:374-379` (`reportJobStatusColors`),
`:344-349` (`proposalVerdictColors`), `frontend/src/lib/statusColors.ts:22-30` (`statusVar`),
`:33-38` (`lifecycleVar`), `:64-69` (`severityVar`). Six tokens, five enums:

| Token | Means |
|---|---|
| `--color-passed` | test PASSED · run COMPLETED · report job COMPLETED · case APPROVED |
| `--color-failed` | test FAILED · run ABORTED · report FAILED · proposal HALLUCINATED · health ERROR |
| `--color-blocked` | test BLOCKED · case IN_REVIEW · health WARNING |
| `--color-skipped` | test SKIPPED · **"Modified" in the activity chart** · **"not automated"** |

Each individual map is well-built and exhaustively typed. The system-level fact — that one
colour carries four meanings — is not visible from inside any of them, which is why it survived.
Live consequence in A1.
*Fix direction:* one saturated tier for *execution outcome only*; a separate desaturated
lifecycle/progress tier — `design/design-tokens` → `fe-react`. **Siblings: A1, I1.**

**B2 · major · 30 hex literals are duplicated into `theme.ts` with no computed check.**
`frontend/src/app/theme.ts:18-60` restates every brand, status and lifecycle value from
`index.css:14-48` as a literal. The comment at `theme.ts:14-16` calls this "the one accepted
duplication… Any change must be made in both". **No test compares them** — verified by search
(`grep -rln "index.css" --include='*.test.*' frontend/src` → no results; no test file references
`index.css` at all).

This repo does not otherwise accept unchecked duplication: `packages/client/src/test/matrixCopies.test.ts`
pins the permission matrix copies, `contractPairing.test.ts` pins the byte-identical contract
region, `schemaFreshness.test.ts` pins the two `schema.d.ts` copies. And `CLAUDE.md`'s own working
agreement states that *"a schema-wide or catalogue-wide invariant needs a computed check, not a
careful reading."* This is a catalogue-wide invariant with a careful reading.

Compounding it: `index.css:9-10` claims the theme is *"bridged into"* the custom properties and
`theme.ts:5-6` claims it is *"sourced from"* them. Neither is true — it is mirrored by hand. A
future reader trusting either comment will change one file and ship a two-palette build in which
MUI-computed contrast text disagrees with token-driven surfaces, and every existing gate stays
green.
*Fix direction:* a test that parses `index.css` and asserts equality with `theme.ts`'s exports;
correct both comments to say *mirrored* — `design/design-tokens` → `fe-react`.

**B3 · major · There is no data-visualisation tier, so charts borrow the status tier.**
`index.css:14-70` defines brand, surface, text, status, semantic-feedback and lifecycle tiers —
and **no categorical/sequential viz ramp**. The consequence is B1's worst instance:
`frontend/src/features/dashboard/DashboardPage.tsx:534-535` legends the activity chart as
`Created → var(--color-info)` / `Modified → var(--color-skipped)`, and `:563-564` fills the bars
with the same two. `:500` fills the automation-coverage bars with `--color-success` /
`--color-skipped`. So on the project overview, **grey means "modified" and "not automated" on two
adjacent charts, while meaning "test skipped" everywhere else in the product.**
*Fix direction:* add a viz tier, disjoint from status by hue, before any further chart lands —
`design/charts` + `design/design-tokens` → `fe-react`. **Siblings: I1, I2.**

**B4 · minor · `EM_DASH` is exported and then re-typed as a literal at every call site.**
`frontend/src/lib/format.ts:45` defines it and `:267` exports it; **9 sites write `'—'` directly**
instead: `features/plans/PlanStatisticsPage.tsx:1002`, `features/admin/RoleAssignmentTable.tsx:131`,
`features/cases/CaseDetailPage.tsx:396`, `:896`, `features/history/SuiteHistoryPage.tsx:252`, `:254`,
`features/runs/RunListPage.tsx:170`, `features/proposals/ProposalPage.tsx:347`.
Search: `grep -rn "'—'" --include='*.tsx' frontend/src`. Harmless today; it is the mechanism by
which H1's four-way inconsistency became possible.
*Fix direction:* import the constant, or better a shared `<Empty/>` — `design/design-tokens` → `fe-react`.

---

### C — Information architecture & shell

> **Judgment.** The shell's *anatomy* is right — persistent rail, scope-grouped, project selector
> pinned, breadcrumb + h1 + subtitle on every page, skip-link present, footer with version. Nav
> depth is two, which is correct. What is wrong is **naming and ordering**: the rail contains two
> pairs of rows that are indistinguishable by label, and it orders the product's scopes
> backwards. Both are cheap to fix and both actively mislead today.

**C1 · major · "Documentation" is two different destinations, visible on one screen.**
`frontend/src/app/AppShell.tsx:205` puts **Documentation** in the rail's `REPOSITORY` group
→ `/projects/:id/docs` (project documentation pages). `AppShell.tsx:~1024` puts **Documentation**
in the footer → `/help` (the product user guide). Both are on screen simultaneously at every
width — confirmed live on `/help` at 900 px, where the rail's Documentation and the footer's
Documentation are 400 px apart and go to unrelated places.

The same page is then named a **third** way in the account menu — **"Help & documentation"** — and
a **fourth** by its own `<h1>`: **"Getting started"** (document title `Help — Getting started · TCMS`).
One concept, four names, and one of those names already belongs to something else.

The comment at `AppShell.tsx:990-993` records that the footer's Documentation link was restored
once it "has somewhere to point" — the review that restored it did not notice the rail already
owned the word. That is exactly the failure a single-altitude review produces.
*Fix direction:* the user guide becomes **Help** everywhere; the rail keeps **Documentation** for
project pages — `design/ux-patterns` + `design/ux-writing` → `fe-react`. **Siblings: H2, G1.**

**C2 · major · "Audit trail" is two nav rows, and "Access & roles" / "Teams & roles" share a label
shape and an icon.**
`AppShell.tsx:320-321` (project-scope `Audit trail` → `/projects/:id/audit`) and `:381-382`
(company-scope `Audit trail` → `/admin/audit`) are identically labelled and can appear in the same
rail, in different groups, for a user holding both permissions. Separately, `:276` **Access & roles**
(`GroupsOutlinedIcon`) and `:357` **Teams & roles** (`GroupsOutlinedIcon`) are near-synonymous
labels sharing **the same glyph** — nothing but group membership distinguishes them.
Not verified live: this account sees neither `Audit trail` row.
*Fix direction:* scope-qualify the labels ("Project audit trail" / "Company audit trail") and
split the icons — `design/ux-patterns` → `fe-react`. **Siblings: J2.**

**C3 · major · The rail orders scopes narrow-to-wide, inverting the product's own hierarchy.**
Rail order, captured at 900 px across two scroll positions:
`My work · Search cases · All projects` → **Project** selector → `REPOSITORY` → `EXECUTION` → `COMPANY`.
The product's stated hierarchy is **Instance → Company → Project → suites** (`CLAUDE.md`, and the
Help page's own "How the pieces nest" section, which introduces **Company** first). The nav puts
the *widest* scope last and below the fold. A user forming a mental model from the nav learns the
inverse of what the help text teaches them 400 px to the right.
*Fix direction:* `COMPANY` above the project selector, or a scope switcher — `design/ux-patterns` → `fe-react`.

**C4 · major · The `COMPANY` group is entirely below an invisible internal scroll at ordinary
laptop heights.**
`/help`, 900 × 523 viewport: the rail's top block (3 global links + the project selector +
group label) is **pinned**, leaving ~250 px for a list of up to ~16 rows. Five items — **Org chart ·
Teams & roles · API tokens · Single sign-on · Audit trail** — required 6 scroll ticks *inside the
rail* to reach, past an ~3 px scrollbar (A5). On this tenant the rail is already ~16 rows; a user
holding `Access & roles`, `Classification`, `Review`, `Notifications`, `Artifacts` and
`Generated cases` (all conditionally rendered, `AppShell.tsx:239-321`) sees ~22.
**A top-level navigation group that requires discovering a hidden scrollbar is not navigable.**
*Fix direction:* collapsible groups, or move company-scope out of the rail into the account menu —
`design/ux-patterns` → `fe-react`.

**C5 · major · Read and write views of a case group the same fields under different headings.**
Case detail (1440 px) groups **Suite** under a panel titled **Classification**. Case edit
(1440 px) groups **Suite** under a panel titled **Placement**, and has its own separate
**Classification** panel holding Priority. Same entity, same field, two IA schemes: a user who
learned where Suite lives on the detail page must re-hunt it in the editor.
*Fix direction:* one field-grouping model, shared by both views — `design/ux-patterns` → `fe-react`.
**Sibling: H3.**

**C6 · minor · Breadcrumb depth changes between sibling pages.**
Case detail: `Reflex / Test cases / Not filed / TC-2` (4 crumbs, project-rooted).
Case **edit**, one click later: `Test cases / TC-2 / Edit` (3 crumbs, **project crumb dropped**).
The you-are-here trail loses the project exactly when the user is furthest into a task.
*Fix direction:* one breadcrumb builder — `design/ux-patterns` → `fe-react`.

**C7 · minor · `/help` highlights nothing in the rail and has no rail entry.**
Captured at 900 px: on `/help/getting-started` no rail row is active. The user is on a page the
shell has no place for, reachable only from the footer or the account menu.
*Fix direction:* either give Help a rail row or render the shell without an orphaned active state —
`design/ux-patterns` → `fe-react`.

**C8 · minor · The project selector and project-scoped groups render on non-project routes.**
`/my-work`, `/search`, `/help` and (unverified) `/admin/*` all render the `Project: Reflex`
selector plus 8+ project-scoped links. On a global page these are eight links out of the page the
user just chose.
*Fix direction:* collapse the project section when the route is not project-scoped —
`design/ux-patterns` → `fe-react`.

---

### D — Usability heuristics (Nielsen, per primary task)

> **Judgment.** Visibility of system status, help-and-documentation, and match-to-real-world are
> genuinely well served — the Help section is real, empty states teach, and `DataRegion` gives
> every region a loading/failed/empty state. **Error prevention** is the weak heuristic: the two
> most destructive controls in the product are rendered as ordinary, always-visible, top-level
> actions, one of them in the only red the page contains. **Recognition over recall** is the
> second: the Health filter offers eleven pieces of unexplained domain jargon with no
> explanation anywhere on the screen.

**D1 · major · `Delete` is a top-level, always-visible, red action beside the primary action.**
Case detail `TC-2`, 1440 px, header action row:
`Execution history · Status ⌄ · Clone · **Edit case** (primary) · **Delete** (red)`.
Delete sits ~110 px from Edit with no separator, no overflow menu, and is **the only red element
on the page**, so it draws the eye first. The safe primary and the irreversible destructive share
a row and a hit-region neighbourhood.
*(The confirmation dialog behind it was deliberately not opened — production data. "The control
exists, is red, is top-level, and is adjacent to the primary" is the finding; whether the dialog
is adequate is deferred.)*
*Fix direction:* Delete moves into an overflow (`⋮`) menu, visually separated —
`design/ux-review` → `fe-react`.

**D2 · major · `Archive project` sits beside the project's primary action at equal weight.**
`/projects/:id` overview, 1440 px: `**Archive project**` (outlined) and `**Browse test cases**`
(filled primary) are adjacent in the page header. Archiving a project affects every case, suite,
plan and run inside it; browsing cases is the page's routine action. They are one mis-click apart
at comparable visual weight, on the landing screen of every project.
*Fix direction:* project lifecycle actions belong in project settings, not the overview header —
`design/ux-review` → `fe-react`.

**D3 · major · Eleven Health filters are unexplained domain jargon with no in-context help.**
`/projects/:id/cases`, 1440 px: `Linked spec changed · Approved before its last edit · Shared
condition changed · Never executed · Not run in 90 days · Automation stopped reporting · Not
linked to any spec · Failing repeatedly · Approved but unfinished · Owner has left · Automated,
but not marked so`. Several are not self-evident even to a test lead ("Approved before its last
edit", "Shared condition changed", "Automated, but not marked so"), no tooltip appeared on the
captures, and the Help page's contents list has no "Health" section. This is the app's most
distinctive feature presented as eleven riddles.
*Fix direction:* a tooltip per signal sourced from the same catalogue that words them, plus a
Help section — `design/ux-review` + `design/ux-writing` → `fe-react`.

**D4 · major · An invalid project id produces a developer-facing error instead of "not found".**
Navigated to `/projects/1/cases` (a plausible typo or a stale bookmark), 1512 px. The app rendered
**the full shell, the suites panel, all 28 filters and a "Loading…"**, then showed — twice, inline
and as a toast — *"The body is not valid JSON, or a field holds a value of the wrong type. Check
the request against the schema in the API contract."*

Three failures in one screen:
- The message is **about the wrong thing** — nothing was wrong with a *body*; a path segment
  failed to parse as a UUID.
- It is **addressed to an API integrator**, not the person who clicked a link. "the API contract"
  is not a thing an end user has.
- It **offers no way out** — no "Back to projects", no retry, no naming of what was not found.

Mechanism: `frontend/src/lib/describeError.ts:145` — the terminal 4xx branch passes the server's
`detail` through verbatim on the reasoning that *"the server's own detail is the most useful thing
we have"*. That reasoning holds for a *validation* 4xx and fails for a *parse* 4xx, and nothing
distinguishes them. The toast additionally reported **"Occurred 6 times"**, so six regions each
fired the same developer error.
*Fix direction:* an unparseable path parameter should route to the not-found screen; `describeError`
should not forward a `detail` for parse-class 400s — `design/ux-writing` → `fe-react`, with a
backend counterpart. **Siblings: H4, E1.**

**D5 · minor · Two "create a suite" controls, differently labelled, in one 260 px panel.**
`/projects/:id/cases`, 1440 px: the Suites panel header carries `+ New` and its empty state
carries `+ New suite` — same action, two labels, ~120 px apart, one text-button and one contained
button.
*Fix direction:* the empty state owns the call to action; drop the header duplicate —
`design/ux-review` → `fe-react`. **Sibling: H5.**

**D6 · minor · Filter chips carry a count when non-zero and nothing when zero, so "0" and
"unknown" are the same rendering.**
Cases filters, 1440 px: `Draft 1`, `Manual 1`, `Medium 1`, `Never executed 1` carry counts;
`In review`, `Approved`, `Critical`, `Owner has left` carry none. A reader cannot tell whether a
bare chip means "zero matches" or "not counted".
*Fix direction:* render `0`, or dim the chip — `design/ux-review` → `fe-react`.

**D7 · minor · Two card shapes on one board have different drag affordances and no way to tell
which is which.**
`/board`, 900 px: the `Task` card carries a `DragIndicator` handle; the `Maintenance / linked`
card does not. Both are the same size, colour and shape.
*Fix direction:* render the handle disabled with a reason rather than absent —
`design/ux-review` → `fe-react`.

---

### E — Flow friction

> **Judgment.** Scoped honestly: this round walked **read** flows only, because every write flow on
> this instance writes production data. Within that scope the dominant finding is one of *volume*
> — the primary browse task pays for a filter panel it did not ask for, at every width — and one
> of *dead ends*, where the app's own error path offers no exit. Undo-vs-confirm could not be
> judged without firing a destructive control, so it is Unverifiable and named as such.

**E1 · major · The error state is a dead end — no retry, no way back, on any surface.**
`/projects/1/cases`, 1512 px: the inline `DataRegion` failure and the toast both render title +
body + a close button. **Neither offers a "Try again" or a route out**, even though
`describeError.ts:22-26` computes a `retryable` flag *for exactly this purpose* and documents the
reason it exists. The user's only exit is the browser back button or the rail. On a 500 or a
timeout — both marked `retryable: true` at `:36` and `:128` — the honest, computed answer "you can
retry this" is available and not offered.
*Fix direction:* `DataRegion` renders a Retry control when `retryable`; the notification does too
— `design/ux-review` → `fe-react`. **Sibling: D4.**

**E2 · major · Reaching the case list costs a full screen of filter chrome at every width.**
Measured from the captures:

| Width | First case row appears at | Filter controls above it |
|---|---|---|
| 1440 px | ~460 px down — **below the fold** on a 606 px viewport | 28 |
| 900 px | below the fold | 28 |
| 501 px | ~1,050 px down — **~10 scroll ticks** | 28 |

The panel is **always expanded**, on a project with **one case**. The steps-to-goal for "look at
my cases" is: load → scroll past 4 dropdowns, 23 chips, a tag field and a helper line → read one
row. On mobile the *suites* panel (empty, ~220 px) stacks above the filters, so the content is
third in the reading order behind two navigational controls.
*Fix direction:* collapse the filter panel to a summarised bar by default, expand on demand;
move Suites below the list at narrow widths — `design/ux-review` + `design/ux-patterns` → `fe-react`.
**Sibling: F1.**

**E3 · minor · The board renders every possible transition as a permanent button on every card.**
`/board`, 900 px: each card carries four `→ <status>` buttons (`BoardPage.tsx:697`), wrapping to
two rows and consuming ~45% of the card. The **redundancy itself is correct and deliberate** —
`BoardPage.tsx:547` tooltips it as the keyboard/pointer-free equivalent of dragging, which is a
good accessibility decision and is **not** the finding. The finding is that the fallback is
rendered at full weight, permanently, N−1 times per card: on a 20-card board that is 80 buttons.
*Fix direction:* one "Move to ⌄" control per card, or reveal the buttons on focus/hover while
keeping them in the a11y tree — `design/ux-review` → `fe-react`.

**E4 · Unverifiable · Undo-over-confirm, and whether Cancel guards unsaved edits.**
Case edit, 1440 px: `Save changes` is correctly disabled until dirty, and `Cancel` sits beside it
as an unstyled text control. Whether Cancel warns on a dirty form, and whether any delete offers
undo rather than confirm, **could not be established without firing controls against production
data**.
*What would settle it:* the same two flows on a seeded local instance.

**E5 · Unverifiable · Steps-to-goal for the primary task of the product — record a result.**
The execution screen was not reached (see Scope). For a test management system this is *the*
flow, and this round cannot speak to it.
*What would settle it:* a seeded local instance with an in-progress run.

---

### F — Responsiveness budget

> **Judgment.** The narrow end is genuinely handled — at 501 px the rail collapses to a hamburger,
> panels stack, filters go full-width, the pagination control sheds its rows-per-page selector,
> and there is **no horizontal page scroll and no layout shift**. The `md` band is where it
> breaks, and it breaks badly: 900 px is the width of a tablet in landscape and a common
> split-screen, and there the shell keeps its full desktop footprint while the content region is
> squeezed to a quarter of the viewport. Press-feedback and the ~4 s acknowledgement are
> Unverifiable this round.

**F1 · major · At 900 px the shell takes 55% of the viewport and the data grid gets ~330 px.**
`/projects/:id/cases`, **900 × 606**, captured this round: the rail holds its full **232 px**, the
Suites panel holds its full **~260 px**, and the case grid is compressed to ~330 px showing only
**Key** and a clipped **Title** — Suite, Status, Priority and Automation are all off-screen. The
`Status` column, the whole point of the screen, is invisible. Beside it sits ~300 px of *empty*
suites panel.

The breakpoints exist (`index.css:78-81`, `app/breakpoints.ts`) and the narrow layout is good, so
this is a missing intermediate rule rather than an absent system.
*Fix direction:* collapse the rail to icons and stack the suites panel at `md`, not only at `sm` —
`design/ux-review` → `fe-react`. **Sibling: E2.**

**F2 · minor · The cases grid clips columns at 1440 px too.**
`/projects/:id/cases`, 1440 × 606: **Automation** is cut at the right edge with no scroll
affordance visible until the grid is hovered. `components/ScrollX.tsx:4-21` exists precisely for
this and documents having been written after the same defect was found on suite history at 810 px
— but its own docblock states the DataGrid "manages its own [scrolling], and must not be wrapped",
so the grid is outside the fix.
*Fix direction:* column priority / responsive column hiding on the DataGrid —
`design/ux-review` → `fe-react`.

**F3 · Unverifiable · Press feedback, skeletons, and the ~4 s acknowledgement.**
Only one loading state was observed live — a bare, unstyled **"Loading…"** text node on
`/projects/1/cases` at 1512 px, appearing beside a `DataRegion` that was simultaneously rendering
a styled failure. That single observation suggests at least one region loads outside `DataRegion`'s
matrix, but one sighting on an error path is a lead, not a finding.
*What would settle it:* a throttled-network capture on a local instance, plus the `tcmsFaults.hang()`
switch this repo already ships (`lib/devFaults.ts`) — which is exactly what it was built for.

---

### G — UI code conformance

> **Judgment.** Conformance is high and mostly needs no comment: components come from one library,
> `sx` is the single styling authority, there is no second `QueryClient`, no inline `<style>`, no
> `!important` outside the reduced-motion block, `:focus-visible` is themed globally
> (`theme.ts:110-115`), `.sr-only` is defined once, and `prefers-reduced-motion` is honoured. The
> shared layer this project mandates is real and used. The findings here are the two places where
> a module's **own stated contract** is contradicted by code elsewhere — which is the only class
> of code finding worth a design audit's attention, because it is the class that makes every
> other guarantee unreliable.

**G1 · major · The notification hardcodes its own error title, contradicting the "one place an
error becomes copy" contract.**
`frontend/src/notifications/NotificationsProvider.tsx:312` renders a literal
`"Something didn't load"` for every failure, ignoring `describeError`'s computed `title`.
`describeError.ts:3-6` states that *"Both surfaces that show a failure read from here — the inline
`DataRegion` state and the persistent notification — so a 503 is worded the same way in both."*

Observed live on `/projects/1/cases` at 1512 px: **inline said "Couldn't load this", the toast
said "Something didn't load"** — for the same error, on the same screen, at the same moment.
Worse for the branches with meaningful titles: a 403 reads **"You don't have access to this"**
inline and **"Something didn't load"** in the toast; a 401 reads **"Your session ended"** inline
and **"Something didn't load"** in the toast, which is actively misleading about what happened.
The mechanism that was supposed to prevent this is present, correct, and bypassed.
*Fix direction:* the toast renders `described.title` — `design/ux-writing` → `fe-react`.
**Siblings: H4, D4.**

**G2 · major · A `plural()` helper exists and 14 sites hardcode the plural noun instead.**
`frontend/src/lib/format.ts:227-230` defines `plural(n, noun)`, and its docblock explains it was
promoted out of `lib/flake.ts` in §194 *because a second consumer is the moment one canonical
format applies*. Fourteen sites did not get the message and write `{formatCount(n)} cases`:

`features/projects/ProjectsPage.tsx:135` · `features/plans/PlanCreateDialog.tsx:246` ·
`features/plans/ResolvedScope.tsx:96`, `:127` · `features/plans/PlanStatisticsPage.tsx:443`,
`:459`, `:469`, `:766` · `features/plans/PlanCasesTab.tsx:153`, `:363` ·
`features/cases/BulkActionDialog.tsx:571` · `features/runs/RunCreateDialog.tsx:418`, `:500` ·
`features/runs/ExecutionQueue.tsx:76`.

Search: `grep -rn "} cases" --include='*.tsx' frontend/src`.
**Confirmed live, twice:** the projects list card and the project overview header both read
**"1 cases"** on this instance (1512 px / 1440 px). Every one of these sites shows the bug the
moment its count is 1 — which for a new project, a new plan or a single-suite scope is the
*normal* case, so this is a defect family that fires hardest on first use.
*Fix direction:* call `plural()` — `design/ux-writing` → `fe-react`.

**G3 · nit · A stray double space before the separator in board and my-work cards.**
`frontend/src/features/board/BoardPage.tsx:650` renders `· {item.assignee.displayName}` preceded
by whitespace, producing **"Medium  · Aleksandar Kotrulev"**. Visible at 1512 px on `/my-work` and
900 px on `/board`.

---

### H — Copy voice

> **Judgment.** The best-executed altitude in the product, and it is not close. Labels are active,
> empty states explain the *why* rather than the *what*, error copy is written for a person
> (`"nothing you have typed has been lost"`), and `labels.ts` reasons in comments about word
> choice rather than defaulting to the enum name. The failures are all **consistency**, not
> voice: the same fact is worded differently in different places, and in two cases the copy makes
> a claim the product contradicts.

**H1 · major · Four different renderings of "no value", three of them on one screen.**
Case detail `TC-2`, 1440 px, simultaneously visible:

| Rendering | Field |
|---|---|
| **"Not set"** | Objective, Description, Expected outcome — `features/cases/CaseDetailPage.tsx:641` |
| **"—"** | Features — `CaseDetailPage.tsx:396`, and step cells at `:896` |
| **(nothing at all)** | **Suite** — while the breadcrumb four lines above says *"Not filed"* |
| **A sentence** | "Never executed." (Last result) · "No postconditions. Nothing needs cleaning up after this case." · "No files attached. Reference screenshots… go here." |

The sentences are *good* and should stay — they teach. The problem is the other three: a reader
scanning for gaps has to learn that blank, `—` and "Not set" all mean the same thing, and that
the Suite field's blank contradicts a breadcrumb on the same screen.
*Fix direction:* one `<EmptyValue>` component; keep prose only where it teaches —
`design/ux-writing` → `fe-react`. **Sibling: B4.**

**H2 · major · The user guide has four names.**
`Documentation` (footer) · `Help & documentation` (account menu) · `Help — Getting started`
(document title) · `Getting started` (`<h1>`) — and `Documentation` **already names a different
destination** in the rail. Filed at C1, which owns the fix; repeated here because the copy half is
separately actionable.
*Fix direction:* one name — `design/ux-writing` → `fe-react`. **Filed at: C1.**

**H3 · minor · A panel renames itself between read and write.**
Case detail: **"What this proves"**. Case edit, same content: **"What this case proves"**.
One concept, two names, one click apart.
*Fix direction:* pick one — `design/ux-writing` → `fe-react`. **Sibling: C5.**

**H4 · major · Server `detail` reaches the user unedited on parse-class errors.**
*"The body is not valid JSON, or a field holds a value of the wrong type. Check the request against
the schema in the API contract."* — shown to an end user, on screen, twice, at 1512 px.
`describeError.ts:145` forwards it. See D4 for the full analysis; filed here too because the
*copy rule* being broken ("an error says what happened and how to fix it, in the reader's terms")
is this section's.
*Fix direction:* parse-class 400s get authored copy — `design/ux-writing` → `fe-react`.
**Filed at: D4. Sibling: G1.**

**H5 · minor · An empty state asserts an invariant the same screen disproves.**
Suites panel, 1440 px: *"No suites yet. **A case needs a suite to live in**, so this is the first
thing to make."* On the same screen: a **"Not filed"** filter chip, a case whose Suite column
reads *Not filed*, and the case editor's own helper text — *"Unfiled. It still appears in the
grid…"* — which says the opposite. A new user is told their first mandatory step is one the
product does not require.
*Fix direction:* "Suites group related cases. Nothing has to be filed, but it helps once you have
more than a handful." — `design/ux-writing` → `fe-react`. **Sibling: D5.**

**H6 · minor · A helper sentence does not parse.**
Case edit, 1440 px, Suite field: *"Unfiled. It still appears in the grid, and any suite whose tags
it carries."* The second clause has no verb it can attach to; the intended meaning (a tag-matching
suite claims the case) is guessable but not stated.
*Fix direction:* rewrite — `design/ux-writing` → `fe-react`.

**H7 · nit · The same two health signals render as coloured chips in one place and muted plain
text in another.**
Case detail, 1440 px: `Never executed` (amber chip) + `Not linked to any spec` (blue chip).
`/my-work` and `/board`, same case: *"Never executed · Not linked to any spec"* as grey plain
text. Same data, two weights, so the board understates what the detail page flags.

---

### I — Charts & data display

> **Judgment.** Each chart answers exactly one question, which is the hard part and is done right —
> "how is authoring progressing", "how much is automated", "what happened lately". The axis
> restraint is defensible for a sparkline. But the **viz palette is not disjoint from the status
> palette** (B3), which for this product is the one rule that matters most: a reader who has
> learned six status colours reads them everywhere, including where they mean something else. And
> one chart currently states a number that contradicts the data beside it.

**I1 · major · Chart series are encoded in status colours.**
Project overview, 1440 px, scrolled:
- **Activity, last 30 days** — legend `Created` (blue) / `Modified` (grey).
  `DashboardPage.tsx:534-535`, `:563-564`. Grey is `--color-skipped`.
- **Automation coverage** — bars filled `--color-success` / `--color-skipped` (`:500`).
- **Authoring status** and **Needs attention** tiles carry left accent bars in lifecycle and
  health-severity colours.

Four visualisations on one screen, drawing from three status vocabularies, none of which mean here
what they mean in the grid the user just came from.
*Fix direction:* a dedicated categorical ramp (B3) — `design/charts` → `fe-react`. **Siblings: B1, B3.**

**I2 · major · "Automation coverage: 0.0%" is stated against a denominator the caption excludes.**
Project overview, 1440 px: the headline reads **0.0%**, sub-captioned *"of cases that can be
automated. Cases marked not automatable are excluded from the denominator."* The breakdown beside
it: Automated **0**, To be automated **0**, Manual **1**, Not automatable **0**. With the stated
exclusion the denominator is **1** and 0/1 = 0% — arithmetically fine — but the figure is rendered
to **one decimal place** on a population of one, which asserts a precision of ±0.05 cases. The
same screen elsewhere shows plain integers.
This is the family `CLAUDE.md` records as already having shipped once ("two contradictory
percentages on one screen").
*Fix direction:* suppress the decimal below a minimum denominator, or show `0 of 1` —
`design/charts` → `fe-react`.

**I3 · minor · The activity chart has no y-axis, no gridlines, no values and no hover label.**
1440 px: two dates at the extremes and 30 bars. A single bar reaches full height; the reader
cannot tell whether it represents 1 or 100. In a panel titled "Activity, last 30 days" on a
project overview, the *magnitude* is the question.
*Fix direction:* a max-value label, or a tooltip per day — `design/charts` → `fe-react`.

**I4 · minor · The runs list answers the same question twice, in two encodings, side by side.**
`/runs`, 1568 px: a **Result** column (a pass/fail bar, captioned "1 of 1 executed", with no key
for what the colours are) and a **Pass rate** column (`0%` / `100%`) are adjacent. The bar's
colours are the only thing carrying the pass/fail split and they are unlabelled.
*Fix direction:* merge into one column with the bar and the number — `design/charts` → `fe-react`.

**I5 · nit · Chart legend entries are rendered as underlined links.**
Automation coverage, 1440 px: `Automated` / `To be automated` / `Manual` / `Not automatable` are
underlined. They *are* links (they filter the case list), which is good, but underlining all four
inside a legend makes the legend read as body copy with links rather than as a key.

---

### J — Iconography & social imagery

> **Judgment.** One family, correctly chosen, correctly sized, and consistently paired with text —
> no icon in this app is the sole carrier of a meaning, which is the rule that matters. The
> failure is **weight discipline**: the codebase draws from both the filled and outlined variants
> of the same Material family, and for nine glyphs it imports *both variants of the same icon* at
> different call sites, so the same concept is drawn two ways in one product.

**J1 · major · Both Material weights are in use, and nine glyphs appear in both.**
112 icon imports across `frontend/src/**/*.tsx`; **70 outlined, 42 filled**.
Search: `grep -rho "icons-material/[A-Za-z]*" --include='*.tsx' frontend/src | sort -u`.

Same glyph, both variants imported:

| Concept | Variants in the codebase |
|---|---|
| Delete | `Delete` · `DeleteOutlined` · `DeleteOutlineOutlined` — **three** |
| Add | `Add` · `AddOutlined` |
| Edit | `Edit` · `EditOutlined` |
| Search | `Search` · `SearchOutlined` |
| Close | `Close` · `CloseOutlined` |
| Copy | `ContentCopy` · `ContentCopyOutlined` |
| History | `History` · `HistoryOutlined` |
| Sort arrows | `ArrowUpward`/`ArrowDownward` · `ArrowUpwardOutlined`/`ArrowDownwardOutlined` |
| Link | `Link` · `LinkOutlined` |

*Fix direction:* pick outlined (the majority, and the right register for a dense tool) and convert
the 42 — `design/iconography` → `fe-react`.

**J2 · minor · One glyph, two distinct concepts, both in the rail.**
`GroupsOutlinedIcon` is used for **Access & roles** (`AppShell.tsx:276`, project scope) and for
**Teams & roles** (`:357`, company scope). Same picture, near-identical label, different scope.
*Fix direction:* split the glyphs — `design/iconography` → `fe-react`. **Filed at: C2.**

**J3 · minor · An action button's icon describes the artifact, not the action.**
`/reports`, 1568 px: the primary **Generate** button carries `AssessmentOutlined` (a bar chart).
The action is "produce"; the icon says "chart".
*Fix direction:* an action glyph — `design/iconography` → `fe-react`.

**J4 · Unverifiable · Favicon, OG image and social preview.**
Not inspectable without fetching `index.html` and the asset directory, which was outside the walk.
*What would settle it:* read `frontend/index.html`'s `<head>` and `frontend/public/`.

---

## Cross-altitude reconciliation

Nine findings share a root cause with another section. Each is filed **once**, at the altitude
that owns the fix:

| Root cause | Filed at | Siblings named |
|---|---|---|
| Status tier overloaded across five enums | **B1** | A1 (visible damage), I1 (charts) |
| No viz tier in the token file | **B3** | I1, I2 |
| "Documentation" names two destinations | **C1** | H2 (copy), G1-adjacent |
| Server `detail` forwarded verbatim | **D4** | H4 (copy rule), E1 (dead end) |
| Filter panel volume | **E2** | F1 (at `md`) |
| Read/write field grouping diverges | **C5** | H3 (panel renamed) |
| `Groups` icon on two concepts | **C2** | J2 |
| `EM_DASH` re-typed as a literal | **B4** | H1 (empty-value inconsistency) |
| Rail scroll hides a top-level group | **C4** | A5 (invisible scrollbar) |

**Where two skills disagree.** None arose that needed adjudication this round: no Title-Case /
sentence-case conflict was found (the product is consistently sentence case), and no layout rule
from `visual-identity` contradicted `ux-patterns`.

### Deliberate conventions — **noted, not filed as violations**

- **UTC everywhere, always suffixed.** `lib/format.ts:6` states the rule and its reason
  ("mixing unlabeled UTC, local, and relative age is a correctness hazard"). Correct for a tool
  whose users read CI output across time zones. **Not a finding.**
- **Monospace tabular figures for every identifier** (`index.css:150-158`, `components/Identifier.tsx`).
  Distinctive and load-bearing — `TC-42` vs `TC-402` align in a column. **Not a finding.**
- **Colour is never the only signal** (`components/StatusChip.tsx:22-26`) — every chip carries text.
  Held everywhere observed. **Conforms.**
- **Flat suites, not a tree.** Matches the product model. **Conforms.**
- **`elevation: 0` + hairline borders instead of card shadows** (`theme.ts:88-95`) — a stated
  revision away from shadow-everywhere. Held. **Conforms.**
- **Redundant `→ status` buttons beside drag** (`BoardPage.tsx:547`) — a deliberate accessibility
  fallback. E3 objects only to its permanent full-weight rendering, not to its existence.

---

## Handoffs — routed away, not filed as findings

| Observation | Owner | Why not mine |
|---|---|---|
| Automated a11y scan: contrast ratios, landmark structure, ARIA correctness, tab order | qa **`accessibility-testing`** | This audit flags only what a scanner cannot see. **`--color-text-faint` `#8593a5` on `--color-surface` `#ffffff` measures ≈3.1:1** — below 4.5:1 for body text. Named as a lead for that scan, **not filed as a design finding.** |
| The backend returns a body-shaped parse error for a malformed **path** parameter | **`code-auditor`** / backend | The wording reaching the user is D4/H4 and is mine; the server choosing that problem type for a path failure is a contract question |
| `6` duplicate requests fired for one failed page load ("Occurred 6 times") | stack `/fe-react` audit | Query-fanout/code convention, not design |
| Whether the confirm dialogs behind `Delete` / `Archive project` are adequate | **`qa-test-case-designer`**, seeded by D1/D2 | Cannot be judged without firing them against production |
| Junk seed data (`asfasf`, `sfds`) on the deployed instance | not a design finding | Data, not design |

---

## Priority — leverage-ordered

Ordered by *user-task impact per unit of work*, not by severity alone.

| # | Finding | Sev | Why first | Owner |
|---|---|---|---|---|
| 1 | **G2** — `plural()` bypassed at 14 sites | major | 14 one-line edits kill a defect family that fires on **every new project's first screen**. Highest ratio in the report. | `fe-react` |
| 2 | **G1** — toast hardcodes its title | major | One line. Restores a contract the whole error system claims to hold; today a 401 says "Something didn't load". | `fe-react` |
| 3 | **E2 + F1** — filter volume, and the `md` layout | major | Two rules fix the app's most-visited screen at three widths. Biggest single win for the primary task. | `fe-react` |
| 4 | **B1 + B3 + A1 + I1** — split the status tier, add a viz tier | major | The largest *design* problem. A green "Completed" on a 0%-pass run is a correctness failure wearing a colour. Do the token work once; four findings close. | `fe-react` |
| 5 | **C1 + H2** — one name for the user guide | major | Rename + one nav row. Removes an ambiguity visible on every screen. | `fe-react` |
| 6 | **D1 + D2** — demote `Delete` and `Archive project` | major | Two irreversible controls out of two page headers. Cheap, and the exposure is permanent. | `fe-react` |
| 7 | **E1** — offer Retry where `retryable` is already computed | major | The flag exists and is documented; only the control is missing. | `fe-react` |
| 8 | **D4 + H4** — authored copy for parse-class errors | major | Removes "check the request against the schema in the API contract" from an end user's screen. | `fe-react` + backend |
| 9 | **B2** — pin the `theme.ts` ↔ `index.css` duplication | major | Prevents the whole colour system silently forking. Invisible today; unbounded later. | `fe-react` |
| 10 | **C3 + C4** — rail order and the hidden `COMPANY` group | major | An unreachable top-level group is a navigation failure, not a polish item. | `fe-react` |
| 11 | **H1 + B4** — one empty-value rendering | major | Four conventions → one; `<EmptyValue>` + `EM_DASH`. | `fe-react` |
| 12 | **D3** — explain the eleven Health signals | major | The product's most distinctive feature is currently unexplained. Larger copy job; high value. | `fe-react` |
| 13 | **C5 + H3** — align read/write field grouping | major | Structural; do it when the case editor is next touched. | `fe-react` |
| 14 | **J1** — one icon weight | major | 42 mechanical import swaps. Do it in one sweep or not at all. | `fe-react` |
| 15 | **C2 + J2** — scope-qualify duplicate nav labels | major | Cannot be verified live until an account holds both permissions. | `fe-react` |
| 16 | **I2, I3, I4, C6, C7, C8, D5, D6, D7, E3, F2, H5, H6** | minor | Batch behind the above. | `fe-react` |
| 17 | **A5, G3, H7, I5, J3** | nit | Opportunistic. | `fe-react` |

---

## Counts

| | |
|---|---|
| **Blocker** | **0** |
| **Major** | **19** — A1 · B1 · B2 · B3 · C1 · C2 · C3 · C4 · C5 · D1 · D2 · D3 · D4 · E1 · E2 · F1 · G1 · G2 · H1 · H2 · H4 · I1 · I2 · J1 |
| **Minor** | **19** |
| **Nit** | **5** |
| **Unverifiable** | **4** — E4 · E5 · F3 · J4 |
| **Total filed** | **33** (+4 Unverifiable = 37 entries) |

*(Cross-filed entries H2 and H4 are counted once, at C1 and D4 respectively.)*

**No blockers.** Nothing found this round prevents a user completing a task or loses their data.
The product is usable; it is inconsistent, and the inconsistencies are concentrated in three
mechanisms that each have a single owner.

---

## Round 2 — re-verify scope

Round 2 runs **Mode B**. IDs above are permanent and must not be renumbered. Nothing may be
marked **Resolved** on a commit message, a claim, or a green test — **only on a fresh trace this
round**. Every finding is prefixed `Resolved` / `Resolved (differently)` / `Still open` /
`Still open (regressed)` / `New`, and the round closes with a **Closed this round** block.

**Re-capture — screenshots from this round are not evidence for the next:**

| Screen | Width | Re-verifies |
|---|---|---|
| `/` and `/projects/:id` | 1440 | G2 ("1 cases"), D2, I2, I3, I1 |
| `/projects/:id/cases` | 1440 · **900** · 501 | E2, F1, F2, D3, D5, D6, H5 |
| `/projects/:id/cases/:id` | 1440 | D1, H1, A2, H7, A4 |
| `/projects/:id/cases/:id/edit` | 1440 | C5, C6, H3, H6 |
| `/projects/:id/runs` | 1440 | A1, I4 |
| `/projects/:id/board` | 1440 | E3, D7, A3, G3 |
| `/help` + account menu + rail scrolled | 1440 · **900 × 600** | C1, C3, C4, C7, A5 |
| `/projects/<invalid>/cases` | 1440 | D4, E1, G1, H4, F3 |

**Source re-reads:** `index.css` ↔ `theme.ts` (B2 — and whether a test now exists) ·
`lib/labels.ts` colour maps (B1) · `lib/statusColors.ts` (B1) · `DashboardPage.tsx:500,534-535,563-564`
(B3, I1) · `NotificationsProvider.tsx:312` (G1) · `grep -rn "} cases"` (G2) ·
`grep -rho "icons-material/[A-Za-z]*"` (J1) · `AppShell.tsx` nav map (C1, C2, C3) ·
`describeError.ts:145` (D4).

**Coverage to close in round 2 — the gaps that matter more than any finding above:**

1. **The execution screen** (`/runs/:id/executions/:id`) — the flow a tester lives in, unseen this
   round. Needs a seeded local instance with an in-progress run. **This is the top priority for
   round 2.** Closes E5, and re-opens D/E/F over the real primary task.
2. **Write flows** — case create, run create, the bulk-action dialog. Closes E4, F3.
3. **Auth screens** — `/login`, `/accept-invitation`, `/sso/callback`. First-run experience,
   entirely unaudited.
4. **Light colour scheme** — every finding above was captured in dark.
5. **Permission-gated surfaces** — `/admin/*`, `/access`, `/settings/*`, `/audit`. Needed to
   confirm C2, which cannot be seen on this account.

**Preferred driver for round 2:** Playwright MCP against `docker compose up` with a seeded
company. Every constraint that shaped this round — no writes, no destructive controls, no sign-out,
no theme toggle, no native dialogs — is a consequence of driving a real session against real data,
and disappears on a disposable local instance. **If the driver changes, round 2 must say so:** a
round captured against a seeded local database and one captured against this tenant are not
directly comparable evidence.

---

*Generated by `design-auditor`. Read-only: no product code, design file, spec, or application
state was modified.*
