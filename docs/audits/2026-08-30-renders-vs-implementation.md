# The renders vs. the implementation

**Date:** 2026-08-30 · **Kind:** read-only audit · **No implementation code was changed.**

Validates the shipped SPA against
[`docs/specs/2026-08-28-design-language.md`](../specs/2026-08-28-design-language.md), the nine
render families in [`docs/specs/assets/renders/`](../specs/assets/renders/), and the two specs that
settled the case editor ([`-case-authoring-redesign.md`](../specs/2026-08-28-case-authoring-redesign.md),
[`-rail.md`](../specs/2026-08-28-case-authoring-rail.md)).

Each finding separates **B** (*the build diverges from the render*) from **N** (*the render was
never implemented*), because plan 007 §4's rule — where the build disagrees with a render the SPEC
is amended — applies differently to the two. Every finding says which way I'd resolve it.

---

## 0. How this was run, and what the environment cost

The environment was brought up as instructed, and **the first two attempts produced a fixture that
does not match the one the brief describes**. This is worth recording because it will happen to the
next person.

`docker compose up -d` reused an image built 2026-08-29T18:26Z. Under that image `seed-demo-data`
exits 0, prints a success report, and silently creates **no test plans and no doc pages** — the
failures are `WARN` lines above the report, not errors:

```
DemoExecutionSeeder : Plan for 2026.8.0 was refused: Invalid {entryCriteria=['gte' is not a
  comparison this gate can make…], exitCriteria=[… 'open_critical_defects' is not a metric …]}
DemoWorkspaceSeeder : Doc page 'Test approach' was refused: Invalid {blocks[4].text=[Enter text.]}
```

The working tree's `DemoExecutionSeeder.java:247` passes `">="` and `"open_critical"` — the *correct*
values. So the image was stale relative to the tree even though no source file was newer than the
image's `Created` timestamp; that check does not detect it. After `docker compose build backend`,
`down -v`, `up -d`, `seed-demo-data`, the fixture matched the brief exactly (WEB 10 cases / 4 runs /
1 plan / 3 doc pages; MOB 4/1/1/2; EHR 3/1/1/2; PAY and LEG empty; LEG archived).

**Recommendation (outside this audit's scope to fix):** the seeder should exit non-zero, or at least
print refusals inside the report rather than only as `WARN`, when a documented fixture element is not
created. A demo seeder that reports success while producing an incomplete fixture sends the next
reader looking for a frontend bug, which is exactly what happened here.

Other method notes:

- Browser: Claude in Chrome, **1680px** viewport (`innerWidth` verified), dark scheme unless stated.
  The display is 1440px CSS wide, so the window was positioned partly offscreen to reach 1680; the
  usable height was 606–773px, so tall screens were audited by scrolling.
- **The 1280px pass** was run two ways and both agree. The window would not shrink below 1680 for
  most of the session, so the first pass constrained the document to 1280px. That is faithful here:
  the app uses `sm`/`md`/`lg`/`xl` and **no `2xl`**, so 1280 and 1680 share an identical breakpoint
  state and only available width differs. The window later did resize, and a real 1280×606 viewport
  reproduced the same results.
- One fixture mutation: recording a `Failed` verdict on TC-4 in TR-2, to reach the render's failure
  path (§3-B1). Re-seed to undo.

---

## 1. Index & table surfaces

### 1.1 The case-list filter strip is not a strip — **B** — High
`frontend/src/features/cases/CaseFilterBar.tsx`

Rule 2 is *"filters in one row under the toolbar"*, and the render draws exactly that: a search
field, the **applied** filters as removable chips (`Status · Approved ×`), then `Showing 86 of
1 284` and `Clear filters`.

The build renders roughly **480px of chrome above the first data row**: three full-width selects
(Feature / Type / Level), then four labelled chip groups (Status, Automation, Priority, Health)
listing **every available value** as a toggle — Health alone is eleven chips over two rows — then a
tag input and an explanatory sentence. Health's row is wider than the table's own Health column.

The render's chip means *"this filter is applied; click the × to remove it"*. The build's chip means
*"this value exists; click to apply"*. That also crosses §5's badge-vs-chip line in the other
direction, and it is why the strip cannot be one row: it scales with the vocabulary, not with what
the reader chose.

*Resolve:* build should move to the render. The same family already has the render's idiom —
see 1.2.

### 1.2 Two filter idioms inside one family — **B** — Medium
`CaseSearchPage.tsx` vs `CaseFilterBar.tsx`

`CaseSearch` draws three quiet chips in a single row (`Status` `Automation` `Priority`) — close to
the render. `CaseList`, the screen the render was actually drawn for, draws the block above. §8's
whole premise is that ten screens are one skeleton; here two screens of that skeleton disagree.

### 1.3 The case table starves the Title column — **B** — High
`frontend/src/features/cases/CaseGrid.tsx:414` (and `:130`)

§9.5's third named risk, **partially** fixed. `components/kit/Table.tsx` does the right thing —
`ScrollX`, cells do not wrap, title is the one exception — and the region genuinely scrolls. But:

| | Render | Build |
|---|---|---|
| table `min-width` | `1380px` | `1240px` (`CaseGrid.tsx:414`) |
| Title column | `min-width: 300px` | `meta.width: 320` — a hint, not a floor |
| sum of declared widths | — | **1588px** |

With `table-layout: auto` and 1588px of hints inside a 1240px floor, the browser squeezes the one
column that is allowed to wrap. Measured at 1680px: **Title = 137px**, Suite = 143px, Health = 182px.
Titles wrap to four and five lines while shorter columns keep their width, which is UX#113's
essential-text rule failed by layout — the exact defect §9.5 said was "most likely to already exist
in the shipped grid".

*Resolve:* raise `min-w` to at least the declared sum and give Title a real `min-width`.

### 1.4 Last result is text where the render draws a bar — **B** — Medium
`CaseGrid.tsx:189`

Render: a 56px stacked bar plus `7/8` in mono — §5's "proportion before number". Build: a status dot
plus `Passed 2m ago`. The product owns a conformant `kit/ResultBar`; this column does not use it.

### 1.5 Health is a chip where the render uses a badge — **B** — Low
`CaseGrid.tsx:226`

§5: a badge is state the system owns; a chip is a value the reader owns and is a button. Health is
computed by the server. The build renders outlined chips (`Not linked to any spec` `+1`).

### 1.6 Priority spends the reserved result palette — **B** — Medium
`frontend/src/components/StatusChip.tsx:138`

`PriorityLabel` paints CRITICAL with `--color-failed` and HIGH with `--color-blocked`. Rule 5 gives
those hues one meaning each, shared deliberately between Gherkin keywords and results — and on this
very row the Last result column is also using them. Two meanings for red in one row is what rule 5
exists to prevent. The render draws priority as plain text.

The component's own comment defends the choice ("a second saturated chip per row turns the table
into confetti"), which is a fair argument against a *chip* but not for reusing the result hues.

*Resolve:* the render. Plain text, or a hue outside the reserved set.

### 1.7 No table foot — **B** — Medium

Rule 2's footer *"states what is on screen and what the keyboard can do"*. The render's is
`86 cases · 12 selected · sorted by Updated, newest first` plus key hints. The build's footer is the
global `TCMS dev · Documentation` on every screen; the count (`10 cases`) floats above the table and
nothing states the sort or any shortcut. **The execution runner is the one screen that gets this
right** (`Recorded 0 of 3 steps` … `1–4 verdict · J/K case · N notes · ? help`), so the pattern
exists and is applied once out of forty-two.

### 1.8 Reports is drawn as cards, not the shared table — **N** — Medium
`frontend/src/features/reports/ReportsPage.tsx`

Render F draws Reports and CI ingestion as *"two tables about jobs, drawn the same way on purpose"*
— Report / Kind / State / Size / Generated / Download. CI ingestion **is** that table in the build.
Reports is a stack of panels with inline buttons. The deliberate pairing is lost.

### 1.9 Run schedules has no per-row switch — **N** — Medium
`frontend/src/features/runs/RunSchedulePage.tsx`

Render G's organising idea is stated in its caption: *"the only index whose rows have an on/off, so
the switch is the first column."* The build has no Active column; it has a page-level "Only active
schedules" toggle and per-row **Edit / Delete** buttons.

Two consequences: cadence is shown as the **raw cron string `0 30 2 * * *`** where the render writes
`Every day at 01:00 · Europe/Sofia`; and a **Delete** button on a schedule contradicts rule 7, whose
worked example is a paused schedule keeping its row at reduced weight with the date and reason.

### 1.10 The artifact table has no `<th>` — **B** — High (accessibility)
`frontend/src/features/artifacts/ArtifactTable.tsx:59-66`

The header row is built from `TableCell` inside `TableHead`, so it emits `<td>`, not `<th>`.
Verified in the DOM: every header cell is a `TD`, `text-transform: none`, `IBM Plex Sans` — against
`POSTED` / `KEY` / `TITLE` in mono uppercase everywhere else. Screen-reader users get no column
headers, and it is visibly the odd table out. `TableHeaderCell` exists and is what the other tables
use.

### 1.11 Artifacts: the render models a different product — **N/B** — Medium (spec)

Render H draws Artifacts as an empty state whose copy says artifacts *"arrive attached to an
execution or an ingested report; **there is nothing to upload here**"*. The build has a **Register**
button, a scope select, and two registered documents. The backend supports registration, so the
render was drawn against a wrong mental model.

*Resolve:* **amend the spec**, per 007 §4. This is the clearest case in the audit of the build being
right and the drawing being wrong.

### 1.12 Projects: two of three signals missing, and a dangling word — **B** — Medium
`frontend/src/features/projects/ProjectsPage.tsx:136` and `:141`

The render's picker gives each card **three** comparable bars: Cases, Automated, Last run. The build
gives one (Cases). It also has no `Current` badge on the active project and no `Show archived`
switch, both of which the render draws.

Separately, a real rendering bug: under the bar the card prints the bare word **"cases"**.
`ProjectsPage.tsx:141` renders `pluralNoun(project.caseCount, 'case')`, and `lib/format.ts:240`
returns *only the noun* — no number. The count is already on the bar, so the line was meant to read
`10 cases · Your role: …`; with `myRole` null for a Company Admin (correctly omitted, per the
comment there) all that survives is the noun.

### 1.13 Proposal tabs carry no counts — **B** — Low
`frontend/src/features/proposals/ProposalsPage.tsx`

Render E: *"a review queue, so the tabs carry counts and the default is what needs you"* —
`Waiting 6` · `Accepted 124` · `Rejected 31`. The build's tabs are bare labels. The default tab is
correct.

---

## 2. The record, read as a document

### 2.1 The case-detail action group overflows the header — **B** — High
`frontend/src/features/cases/CaseDetailPage.tsx:210` + `frontend/src/app/AppShell.tsx:985`

The shell's `<header>` is `h-[52px]`, fixed, and `AppShell.tsx:797` states the reason: *"The header
has a fixed height and does not collapse."* `CaseDetailPage.tsx:210` portals a
`flex flex-wrap items-center gap-2` group of five actions into it.

At 1680px they wrap to two rows. Measured: header `bottom: 52`, Delete button `bottom: 57.5` — the
second row sits **5.5px below the header's own bottom border**, on top of the content, and the
breadcrumb is truncated to `Test cases / Checko…` and partly covered.

This is not a case-detail bug alone; it is the shape of the slot. See 3.2 for the same defect on the
runner at 1280, where it is worse.

*Resolve:* the header must either grow with its content or the slot must collapse overflow into a
menu. The commit bar on the case editor fits and looks right, which is the pattern to match.

### 2.2 The run summary legend omits skipped — **B** — High
`frontend/src/features/runs/RunDetailPage.tsx:406-411` (same omission at `RunListPage.tsx:199`)

TR-1 has 10 results: 6 passed, 2 failed, 1 blocked, **1 skipped**, 0 not run. The bar is correct —
its `aria-label` reads `6 passed, 2 failed, 1 blocked, 1 skipped of 10` and it draws four segments.
The legend beneath it prints:

```
6 passed   2 failed   1 blocked   0 not run
```

Nine of ten. The fourth segment is visible in the bar with nothing in the legend naming it, and
`0 not run` invites the reader to conclude the list is complete. `PlanStatisticsPage` gets this
right on the same data (`Passed 5 · Failed 3 · Blocked 1 · Skipped 1 · Never run 0`), which is what
makes this a defect rather than a decision. This is the "two contradictory numbers on one screen"
class CLAUDE.md's working agreement warns about, and it reproduces in both colour schemes.

### 2.3 The right rail clips its own content — **B** — Medium
`CaseDetailPage.tsx`, `CaseEditorPage.tsx`, `ExecutionPage.tsx`

Three screens, same symptom at 1680px: the rail's value column is cut off at the viewport edge with
no scroll and no ellipsis — `Test method  checkout.saved_card.happy_pat`, `Automation  Automated ·
checkout.saved_card.hap`, and in the runner the Defects row's third button renders as `Lin`. Long
mono identifiers are exactly what rule 6 says will live in these rows.

### 2.4 Two rail idioms on one rail — **B** — Low
`CaseDetailPage.tsx`

`SUMMARY` / `LAST RESULT` / `AUTOMATION` / `CLASSIFICATION` are §5's quiet `kv` sections. Further
down the same rail, `Reliability` and `Version history` are bordered cards. Likewise in the document
column, Attachments and Issues are carded while Preconditions, Steps, Postconditions and
Traceability are not — rule 1's "no card wrapping" applied to four of six blocks.

### 2.5 The report preview is dark — **N** — Medium
`frontend/src/features/reports/ReportViewPage.tsx`

§9.1 opens with *"The report preview is the one light surface in a dark product"*, and gives the
reason: a PDF preview drawn dark misrepresents the file being downloaded. The build renders it in
the app's own scheme. It is listed in §9 as an open problem rather than a rule, so this is a
deliberate-looking gap rather than a slip — but the stated reason still holds.

Also on that screen: the scope prose sits in a key/value table forced to **1639px** inside a 1366px
region, so reading one sentence needs horizontal scrolling. §3 puts prose at 70ch.

### 2.6 What matched the render

CaseDetail's document + rail structure, the mono step gutter, the inherited-precondition banner,
`CaseHistory`'s single-column timeline with mono stamps, `IngestionJob`, and `Proposal` (document
left / evidence right / verdict last) all read as the renders draw them.

---

## 3. Execution

### 3.1 §4's rail collapse is correct — **conformant**

Measured `Nav.Rail` width on the runner and the case editor: **64px** — the amended §9.2 number, not
the 60px the renders draw. `AppShell.tsx` reads the route and collapses on three patterns. This is
the one place the spec was amended in the build's favour and the build matches the amendment.

### 3.2 The runner's three-pane split breaks at 1280 — **B** — High
§9.5's third named risk, **confirmed**

At 1280px the top strip collapses: the `Case 5 of 10 · TC-5 v1` indicator is clipped off the **top**
of the viewport, and the Focus/Split/Grid control wraps to a second row that spills below the
header's bottom border onto the content. Same mechanism as 2.1 — `flex-wrap` inside `h-[52px]`.

Measured on the same screen:

| | 1680px | 1280px |
|---|---|---|
| procedure column | 666px = **74ch** | 360px = **40ch** |

§3's measure **is** implemented (`max-w-[74ch]`, verified on the element) and is exact at 1680. But
`max-width` is a cap, not a floor, and the three-pane grid squeezes past it. Below roughly 1500px
the runner stops being a 74ch document surface, which is the premise §4 uses to justify collapsing
the rail there in the first place.

### 3.3 Recording a failure has no failure panel — **N** — High
`frontend/src/features/runs/ExecutionPage.tsx`

Render B is the runner's most-specified moment: *"what '2' opens — the only modal-ish moment, and it
is a rail section, not a dialog"*. It replaces the rail's recording section in place with
**Failed at step 4**, a required *"What actually happened"* field, and an inline refusal — the
render's own note says *"A failure without a description is refused, and says so."*

In the build, clicking **Failed** records the verdict immediately, navigates away to the run, and
raises a toast `Recorded Failed for TC-4`. No description is asked for and none is refused.

The record it produced is visible on the run detail two lines later:

> TC-4 Search results honour the price filter and the sort together
> **0 of 3 steps failed · 3 not marked**

A case marked Failed with zero failed steps, no reason and no defect — the precise outcome the
render's refusal was designed to prevent, reachable in one click.

### 3.4 Smaller runner divergences — **N** — Low/Medium

- **The queue is a plain list.** The render's left rail carries a result bar for the whole run and a
  per-case status dot and duration (`● TC-188 Refund a settled… 3m 04s`). The build lists titles and
  keys only, so the queue does not say what has been recorded.
- **No elapsed time and no "Pause run"** in the top strip; the render has both.
- **The duration field is not a timer.** The render shows a live counter (`101 seconds · Running`,
  *"Counting since you opened the case"*); the build shows an empty box captioned *"Optional. Leave
  it empty if you did not time this run."*
- **No preconditions banner** in the runner, though CaseDetail has one.
- **Three layouts, not two** (Focus/Split/**Grid**). The render's own note says *"Focus and Split are
  the two layouts."* → *amend the spec.*

---

## 4. Board & people

### 4.1 The board at five columns on 1280 — **B** — Medium
§9.5's second named risk, **confirmed as degradation, not breakage**

Five columns still fit with no horizontal scroll, but each card's content comes apart: badges wrap to
their own row, the four move-buttons stack three deep, titles wrap to three lines, and a one-line
task becomes a ~290px card. The metadata line breaks after its separator, leaving a **dangling `·`**
at the end of a line (`Aiko Tanaka ·` / `due 2026-09-13`, and `Marco Ferreira ·` with nothing after
it in DONE).

### 4.2 WCAG 2.2's drag rule is satisfied — **conformant**

The render names the board as *"the only drag surface, so the only one where WCAG 2.2's drag rule
bites."* Every card carries explicit `→ To do` / `→ In progress` / `→ Blocked` / `→ In review` /
`→ Done` buttons alongside the drag handle. The cost is card height (4.1).

### 4.3 Org chart — **conformant**, with one unverifiable risk

Every cell names which of the three arms produced it (`Test Engineer / Direct grant`,
`Test Lead / Company-wide default`), em dash for none, and the Gaps panel is a good addition the
render does not have. Column headers are rotated to vertical text, which suggests the horizontal
budget is already tight at four projects. **§9.5's first named risk — the matrix at five projects —
could not be checked**: this fixture gives Northwind four.

---

## 5. Authoring

### 5.1 The doc editor gives every block a toolbar — **B** — Medium
`frontend/src/features/docs/DocPageEditorPage.tsx`

Render B's caption is the decision: *"blocks get a **gutter, not a toolbar each** — the case editor's
move, applied to a block model."* The build has the gutter (drag handle, `H`/`P` type letter) **and**
a full toolbar row under every block: a type select, a level select, up, down, delete. On a
five-block page that is five toolbars, which is the thing the render ruled out by name.

### 5.2 Feature import is one column, not three — **B** — Low
`frontend/src/features/cases/FeatureImportPage.tsx`

Render C: *"files left, what will happen centre, what it costs right."* The build stacks the three
vertically. The principles survive — `Nothing is written until you import`, Preview before Import,
the path stored as provenance — and those are the load-bearing part.

### 5.3 Documentation puts its chrome in the reading column — **B** — Low

`Edit` / `Unpublish` / `Archive` / `Delete` sit between the page title and the first block, inside
the document surface. Rule 2: chrome is a strip above and a strip below. The tested/not-tested
signal (`Not tested · no case links to this paragraph · Write one`) is exactly right.

---

## 6. Analytics

### 6.1 The light scheme's status palette was never put through §6's validator — **B** — High

This is the audit's most consequential finding, and it is computed rather than judged, per §6's own
method and CLAUDE.md's rule that a palette-wide invariant needs a computed check.

`index.css` defines **two** status palettes — light at `:169-173`, dark at `:298-302`. §6's numbers
were derived for the dark set only. Reproducing §6's metric (Euclidean distance in OKLab ×100)
against the dark palette returns `skipped`↔`untested` = **11.3** (§6 states 10.7) and
`failed`↔`blocked` = **17.3** (§6 states 17.3 exactly), so the metric matches the one the spec used
and the light numbers below are directly comparable.

`ResultBar`'s fill order is fixed — passed, failed, blocked, skipped — so the adjacent pairs are:

| adjacent pair | dark ΔE | light ΔE |
|---|---|---|
| passed \| failed | 22.9 | 24.3 |
| **failed \| blocked** | **17.3** ✓ | **12.6** ✗ |
| blocked \| skipped | 19.8 | 13.0 ✗ |
| *(skipped ↔ untested)* | *11.3* | *2.5* |

**Against §6's own normal-vision floor of 15, the light scheme fails on two adjacent pairs where the
dark scheme passes on all three.** Verified rendering, not just in the stylesheet: in light mode the
TR-1 bar draws `rgb(189,58,43)` next to `rgb(138,95,13)` — red beside brown, and they read as one
warm band. The `skipped`↔`untested` collapse to 2.5 is mitigated by the same fix §6 already made
(untested is the empty track, so they are never adjacent fills), but nothing mitigates `failed |
blocked`.

Nothing tests this. `kitRules.test.tsx` counts the fills; no check measures them, and none measures
them per scheme.

*Resolve:* re-step the light `--color-blocked` (or `--color-failed`) to clear 15 ΔE, and add the
computed check as a test so a third scheme cannot repeat it. Note also that §1 and §6 are written
entirely in dark-theme terms — the spec should say the validation applies per scheme.

### 6.2 Two of three dashboard breakdowns have no legend — **B** — Medium
`frontend/src/features/dashboard/DashboardPage.tsx:253` (the one that has one)

`By last run` passes `legend={LAST_RUN_LEGEND}`. `By feature` and `By owner` — both four-colour
lifecycle stacks (Draft / In review / Approved / Deprecated) — pass none, so the reader sees amber,
green and periwinkle with no key. Each segment does carry an `aria-label` and a `title`, but a
hover-only key is not a legend, and it is not available to a touch reader at all. The mechanism is
already there; two call sites do not use it.

### 6.3 §6's chart rules that do hold — **conformant**

Verified in the DOM rather than by eye, and all correct:

- **2px segment gaps and 3px ends** — `gap: 2px` measured on the run bar; `gap-0.5` in `ResultBar`,
  `Figure` and the dashboard's breakdown rows alike.
- **Untested is the empty track** — `ResultBar`'s `FILLS` has four entries and `untested` is not one
  of them; the track shows through at `--color-field`. `DashboardPage` does the same for `NEVER_RUN`
  with `transparent`, and the legend says so in words.
- **No pie, donut or gauge** anywhere.
- **No categorical palette.** Activity is drawn as two same-hue magnitude rows, not a two-series
  chart with two colours.
- **Y axes start at zero and say so** — PlanStatistics prints *"The vertical axis starts at 0, not at
  the lowest point. A truncated axis turns a six-point recovery into a cliff."*
- **Rule 8, a count is a link to its rows** — every breakdown segment is a `RouterLink` carrying the
  filter.

### 6.4 Figures are carded — **B** — Low

Every figure on the Dashboard sits in a bordered panel. Rule 1 says no panel around the subject of
the page; on a screen that is nothing *but* figures, seven borders is what rule 1 describes.

### 6.5 Health-signal copy (§9.4) — **verified, not a finding**

§9.4 warns the renders' health copy is invented. The build does not carry it. Where the build makes
a claim it cannot support it says so instead: *"Not enough evidence to score. 3 results, 2 repeat
runs under identical conditions — too few to score"*, and on the plan gate *"not evaluated: nothing
in TCMS measures this yet"*. That is the honest version of what §9.4 was worried about.

---

## 7. Settings & administration

### 7.1 A refusal names the permission but not a person — **N** — High
`frontend/src/features/admin/ApiTokensPage.tsx` (as `viewer@northwind.test`)

Rule 7 and §7 both state the same requirement twice: *"the permission's real id **and the name of
somebody in this company who holds it**"*, and *"the name of someone who can grant the permission"*.

The build delivers most of it and stops one clause short:

> ⓘ Issuing API tokens needs `token:issue`, which you do not hold, so this list is not shown.
> **A company administrator can issue one for you.**

Neutral colour, no retry button, the real permission id in mono, and `Issue a token` **disabled
rather than hidden** (verified: one disabled button on the page) — four of five requirements met.
But "a company administrator" is a role, not a name. Dana Whitfield is a Company Admin in this
company and the org chart already knows it.

### 7.2 Review settings — **conformant, and the best example of rule 7's spirit**

Every toggle states its blast radius against live data: *"0 in review right now. Turning this off
leaves them where they stand — nothing is discarded, and the Draft / In review / Approved statuses
go on working."* Exactly render A.

### 7.3 API tokens, classification, access — **conformant**

Columns, mono prefixes, ownership and expiry all match render E. Revoked and paused rows at reduced
weight could not be checked — the fixture seeds none.

---

## 8. The threshold

### 8.1 Sign-in asks for the password before discovering the method — **N** — Medium
`frontend/src/features/auth/LoginPage.tsx`

Render A's caption: *"the SSO discovery that fires on the email, **before a password is ever asked
for**"*, and render B is the password path as a second frame. The build shows email and password
together in one form, so the email never decides the method.

The rest of the threshold is right: no rail, no project, the identity panel at more weight than
anywhere inside, and *"An administrator has to invite you — this instance has no sign-up."*

**AcceptInvitation and SsoCallback could not be checked** — see §10.

---

## 9. States, errors & help

### 9.1 `drift()` reads as FAILED, not empty — **conformant on the load-bearing rule**

§7's most important requirement holds. A 200 with the wrong shape does **not** render as "no
results".

### 9.2 …but it fails through the error boundary, with no reference and no timestamp — **B** — Medium
`frontend/src/components/ErrorBoundaries.tsx`

What actually happens is that the wrong shape throws (`TypeError: suites.data?.find is not a
function`, captured in `window.__tcmsErrors`) and the **route** error boundary catches it, replacing
the whole page:

> **This page didn't load** — Something went wrong rendering this page, and the problem has been
> reported. … `Try again`

Two gaps against §7. It is not `DataRegion`'s FAILED state inside the region, so the filters and the
page frame are lost along with the data; and §7 requires *"Every failure carries a six-character
reference and a timestamp"* — this carries neither, so a reader who reports it has nothing to quote.

### 9.3 A persistent 503 never leaves the loading skeleton — **B** — High
`frontend/src/app/queryClient.ts`, `frontend/src/components/DataRegion.tsx`

With `tcmsFaults.status(503, 30)` armed, the Board sat in its loading skeleton for **56+ seconds**
with no failed state, no stale marker, no message and **zero notifications** (`document
.querySelectorAll('[data-sonner-toast]').length === 0`).

§7's whole point is that the six states must be unmistakable. Here the loading state is
indistinguishable from a permanently broken one, and a reader has no way to tell the difference from
a slow request. The skeleton is also five horizontal bars where the content is five columns, so it
does not even predict the shape.

### 9.4 The empty state does not count what its filters hid — **B** — Medium
`CaseSearchPage.tsx`, `PlanListPage.tsx`

§7: *"Empty counts what its filters hid (1 284 cases exist. Three filters are hiding all of them)
and offers their removal in place."* The build offers the removal (`Clear filters`) but never the
count — `No case matches / Try fewer words, or a case key such as TC-412.` A reader cannot tell an
empty project from an over-filtered one.

### 9.5 An empty project shows every dead filter — **B** — High
`CaseFilterBar.tsx` — rule 4, and finding 3 of the case redesign, reproduced

Rule 4 is *"Nothing configured, nothing shown"*, and it is stated as preventing *"finding 3 of the
case redesign — four dead selects and a line of copy describing the system's state to a reader it
leaves stuck."*

On PAY (0 cases, 0 suites) the case list renders **three empty selects** (Feature — project-scoped
and empty — plus Type and Level), **four full chip groups with every value and no counts**, a tag
input, and its explanatory sentence. Below all of it: `0 cases`, then nothing. No empty state block,
no icon, no offer.

The same screen proves the pattern is understood: the suites panel beside it says *"No suites yet. A
case needs a suite to live in, so this is the first thing to make."* with a `New suite` button. Rule
4 is applied to the suites panel and to nothing else on the screen.

### 9.6 NotFound and Help — **conformant**

NotFound gives an icon, one action, and a genuinely useful sentence about per-project keys. Help is
long-form reading on the document surface with a contents rail, as §3 describes.

---

## 10. What I could not check, and why

| Not checked | Why |
|---|---|
| **§9.5 risk 1 — the org chart at five projects** | The fixture gives Northwind four. Rotated column headers at four suggest the budget is tight, but the named case is unreachable. |
| **A fifty-step case** | The brief says so; longest seeded case is 5 steps. §3's 74ch measure was verified statically instead. |
| **A five-project org chart / large-corpus behaviour generally** | Same fixture limit. Nothing here is evidence about 1 284 cases; the render's own numbers are invented. |
| **AcceptInvitation, SsoCallback** (family 8 C and D) | No SMTP relay is configured, so `seed-demo-data` logs that every invitation was **not** emailed and only a token digest is stored — the link cannot be recovered or reprinted. Reaching these needs `issue-invitation` with a relay, or a hand-built token. |
| **Revoked tokens, paused schedules, archived doc pages at reduced weight** (rule 7) | The fixture seeds none in those states. |
| **`Unmatched` as a link** (render F) | The seeded ingestion has 0 unmatched. |
| **The end-of-queue runner screen** (render C) | Requires finishing a run; not done, to limit fixture mutation. |
| **MyWork as "the same board with one filter"** (render B) | Every seeded account I checked has nothing assigned, so only its empty state was reachable. |
| **`tcmsFaults.hang()`** | 9.3 already establishes that a stalled request never leaves the skeleton; `hang()` exercises the same path. |
| **The light scheme against the renders** | The renders and §1 are written entirely in dark-theme terms; §9.1 says explicitly that nothing states what happens when the app grows a light theme. 6.1 is measured against §6's own numeric rule, which is scheme-independent. |
| **Print / PDF output** | Out of scope for a browser pass. |

---

## 10a. Round 2 — what was fixed, 2026-08-30

Worked through [`docs/plans/008-render-audit-remediation.md`](../plans/008-render-audit-remediation.md),
nineteen tasks, one commit each. **Every finding is accounted for below**: fixed, amended in the
spec, or open with a stated reason. Nothing was closed by deciding it did not matter.

| # | Severity | Round 2 | Where |
|---|---|---|---|
| §0 seeder reports success on an incomplete fixture | method | **fixed** | `seed-demo-data` exits `3` and prints a `Refusals` block |
| 1.1 filter strip is not a strip | High | **fixed** | choosing moved into a closed menu per facet; the chips are the applied set |
| 1.2 two filter idioms in one family | Medium | **fixed** | `CaseFilterBar` now draws what `CaseSearchPage` already did |
| 1.3 the table starves the Title column | High | **fixed** | floor raised to the declared sum (1588); Title has a real `min-width` |
| 1.4 last result is text where the render draws a bar | Medium | **open — contract** | `TestCaseSummary.lastResult` carries one status and no counts; a bar over it would claim a proportion nobody measured. Reason is in the cell. |
| 1.5 health is a chip where the render uses a badge | Low | **fixed** | tinted like every other badge; it was already not operable |
| 1.6 priority spends the reserved result palette | Medium | **fixed** | weight and `--color-steel`; a test asserts it stays out of the reserved set |
| 1.7 no table foot | Medium | **fixed** | `kit/TableFoot`; the case grid is its first consumer, and its sort clause is read from the table's own state |
| 1.8 Reports is cards, not the shared table | Medium | **fixed** | the same jobs table CI ingestion draws; the FAILED sentence rides in the title cell's subtitle |
| 1.9 run schedules has no per-row switch | Medium | **fixed** | switch first, cadence read out loud by `lib/cron.ts`, row Delete moved into the dialog |
| 1.10 the artifact table has no `<th>` | High (a11y) | **fixed** | `TableHeaderCell`, with a test that counts `th` and `td` |
| 1.11 Artifacts models a different product | Medium | **amended in spec** | §8: the render was drawn against a wrong mental model |
| 1.12 projects: two of three signals, a dangling word | Medium | **partly fixed** | the dangling noun and the archived switch are fixed; the two extra bars need fields `ProjectSummary` does not carry, and `Current` would have to invent a current project on a route with no `:projectId`. Both reasons are in the file. |
| 1.13 proposal tabs carry no counts | Low | **fixed** | from the same three queries the tabs run |
| 2.1 the action group overflows the header | High | **fixed** | `HeaderActions` (no wrap, `min-w-0`); the three secondary actions are icon-only |
| 2.2 the run legend omits skipped | High | **fixed** | both screens; the test asserts the SUM, so a sixth status fails it |
| 2.3 the right rail clips its own content | Medium | **fixed** | `RailRow` truncates with a `title` |
| 2.4 two rail idioms on one rail | Low | **fixed** | `Panel` gained a `plain` variant; the four blocks pass it |
| 2.5 the report preview is dark, prose at 1639px | Medium | **fixed** | `data-color-scheme="light"` on the subtree; `index.css` writes its light palette under `:root, [data-color-scheme='light']` so a nested island needs no second copy of the hexes |
| 3.1 the 64px rail | conformant | **recorded** | §4 now says it was verified in the shipped build |
| 3.2 the runner breaks at 1280 | High | **fixed** | container query, not a media query: at 1280 the same `lg:` state applies whether the queue is beside it or not |
| 3.3 recording a failure has no failure panel | High | **fixed — re-diagnosed** | the panel, the required field and the refusal all existed; the gate keyed on a STEP. Two paths existed and only one carried it. |
| 3.4 smaller runner divergences | Low/Med | **fixed / amended** | the queue's status dot existed with **no size, no colour and no class** and rendered nothing; the run bar and per-case duration are added; elapsed time added; the timer, the third layout and Pause run are spec amendments |
| 4.1 the board at five columns on 1280 | Medium | **fixed** | one move menu; `joinMeta` cannot emit a dangling separator |
| 4.2 WCAG 2.2's drag rule | conformant | **preserved** | a test asserts every destination is reachable without a drag |
| 5.1 the doc editor gives every block a toolbar | Medium | **fixed** | every action moved into the gutter the render already drew |
| 5.2 feature import is one column | Low | **fixed** | three columns from `lg` |
| 5.3 documentation chrome in the reading column | Low | **fixed** | moved to the identity strip; the tested/not-tested signal stays on its paragraph |
| 6.1 the light palette was never validated | High | **fixed** | `#623c00` (18.9 / 15.9 ΔE, 8.26:1 as text); `statusPalette.test.ts` measures both schemes |
| 6.2 two breakdowns have no legend | Medium | **fixed** | derived from the same maps the bars read |
| 6.4 figures are carded | Low | **considered, not applied** | rule 1's subject is the ONLY thing on a screen; seven figures are seven things. Reasoning in `FigurePanel.tsx` and §6. |
| 7.1 a refusal names no person | High | **fixed** | `GET /permissions/{permission}/grantees` — gated on membership, display names only, capped at three |
| 8.1 sign-in asks for the password first | Medium | **fixed — re-diagnosed** | discovery was implemented; the field mounted too early |
| 9.2 no reference, no timestamp on a failure | Medium | **fixed** | all three tiers; the alphabet drops the glyphs that get misread aloud |
| 9.3 a persistent 503 never leaves the skeleton | High | **fixed** | an 8s retry budget. `Retry-After` is still obeyed; what stops is waiting on it in silence. |
| 9.4 empty does not count what its filters hid | Medium | **partly fixed** | `EmptyState` writes the sentence and the case list passes the project's own count; cross-project search passes neither, deliberately — no endpoint serves a corpus total across projects |
| 9.5 an empty project shows every dead filter | High | **fixed** | one sentence, and a test that asserts no control renders |

### Two findings this round re-diagnosed

Both were read as missing features and were smaller and more precise than that. Recording it because
the difference is what a next reader needs:

- **3.3.** The failure panel, its required description and its inline refusal were all implemented.
  The gate was `failedStep !== null` — a *step* marked Failed — so pressing the *case* verdict with
  nothing marked walked past it. Two paths reached the verdict and only the keyboard one carried the
  refusal; the buttons expressed it in `disabled`, which cannot say *"press this to open the panel"*.
- **8.1.** SSO discovery fires on the email and has since the screen was built. The password field
  was mounted from the first paint, so the address never visibly decided anything.

### One finding whose cause was worse than the write-up

**3.4, the queue's missing status dot.** The audit read it as *"the build lists titles and keys
only"*. The dot was already there — a `<span>` with a `data-testid`, an `aria-label` and **no size,
no colour and no class at all**. It rendered nothing. A screen reader was told every row's status
and a sighted reader was told none, which is the inverse of the usual failure and invisible to every
gate: the element existed, the test id resolved, the label was correct.

### Still unchecked, and unchanged from §10

None of §10's list was addressed and none of it should be read as closed: the org chart at five
projects, a fifty-step case, `AcceptInvitation` / `SsoCallback`, revoked tokens and paused schedules
at reduced weight, `Unmatched` as a link, the end-of-queue runner screen, and MyWork with anything
assigned. Every one is a **fixture** limit rather than a code question. Extending the demo fixture to
reach them is its own piece of work — one the §0 seeder fix makes safe to attempt, because a seeder
that silently produces less than it promises is what made the current fixture untrustworthy.

**A browser pass has not been re-run.** Everything above is verified by the automated gates and by
reading the code; the measured, at-two-widths, in-both-schemes walk that produced round 1 is still
outstanding and is the only thing that can confirm the layout findings (1.3, 2.1, 3.2, 4.1) in the
pixels rather than in the mechanism.

---

## 11. Summary

**High** — 1.1 filter strip · 1.3 title column · 1.10 missing `<th>` · 2.1 header overflow ·
2.2 legend omits skipped · 3.2 runner at 1280 · 3.3 no failure panel · 6.1 light palette fails ΔE ·
7.1 refusal names no person · 9.3 eternal skeleton · 9.5 rule 4 on an empty project

**Medium** — 1.2 · 1.4 · 1.6 · 1.7 · 1.8 · 1.9 · 1.11 · 1.12 · 2.3 · 2.5 · 4.1 · 5.1 · 6.2 · 8.1 ·
9.2 · 9.4

**Low** — 1.5 · 1.13 · 2.4 · 3.4 · 5.2 · 5.3 · 6.4

**Spec should be amended, not the build (007 §4)** — the collapsed rail at 64px (already amended,
and the build matches); Artifacts as a registrable catalogue (1.11); the runner's third layout
(3.4); and §1/§6 should state that the colour rules apply **per scheme** (6.1).

**Conformant, and worth saying so** — the 74ch measure at 1680; the 64px rail on all three routes;
every §6 chart rule except the light palette; `drift()` reading as FAILED; in-place session recovery
on `expire()`; the board's drag alternatives; Review settings' blast-radius copy; the case editor,
which is the closest screen in the product to its render.
