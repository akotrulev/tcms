# Delivery analytics (M9) Implementation Plan

**Status (filed 2026-10-05):** Delivered — module M9, done 2026-09-06 @ `ed299a79`. Unticked checkboxes below are not open work. Any `Status`, task table or checkbox below this line is the plan's history, not its state.

**Date:** 2026-09-06 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The plugin skills `CLAUDE.md`
> names are mandatory: `/be-java` + `be-java-database-code` + `be-java-unit-testing` for T2,
> `/fe-react` + `design-charts` + the `dataviz` skill for T3's four figures.

**Goal:** A PM opens one screen and reads whether the project is converging on a release — a burn-up
whose total-scope line **steps** where scope was added after the release was created, cycle time per
issue type, a cumulative flow, and estimate-versus-approved-hours by epic. **No velocity chart
exists anywhere**, because D6 removed the sprint it would have been measured over.

**Architecture:** Three synchronous, typed read endpoints in the existing `com.tcms.reporting`
module, each computed by one service over `issue_fact` and — for the burn-up baseline only — the
`release` table, both reached through a single new `reporting.DeliveryFacts` class in the
`work.BoardFacts` / `time.IssueFacts` / `billing.IssueSubjectFacts` pattern. **Nothing imports
`tracker`**, and nothing is added to the `ReportComputation` job pipeline (D-b). The fourth panel is
composed on the client from `time`'s own `GET /time/rollup/epics/{key}` — `reporting` never reads
`issue_time_rollup` (D-i). The frontend adds one route, one rail entry, one query module behind the
existing barrel, one fault arm and four figures drawn on the render kit's own tokens.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · **no migration** (D-a) · Postgres 17 with `FORCE ROW
LEVEL SECURITY` · React 19 · TanStack Query · Tailwind v4 + the render kit · inline SVG, no chart
library · Vitest + RTL · `@tcms/client`, `@tcms/cli`, `@tcms/mcp`.

**Module:** M9 of [009-project-management-expansion-modules.md](009-project-management-expansion-modules.md),
the **last** of the ten. **Depends on:** M3 — `done 2026-09-01 @ 55cb4bc0`, plan
[013-releases.md](013-releases.md); its status-table row reads `done`, **verified 2026-09-06 before
this plan was written**, as the prompt required. **No module depends on M9.**

**Tasks** — in the order they are done:

| # | Task | Stack | Agent | Deliverable | Depends on |
|---|---|---|---|---|---|
| 1 | T1 — The analytics contract, both generated clients, and the CLI/MCP verbs | `ts` | `tooling-engineer` | Three path items under a new `analytics` tag, every schema carrying its `required` array; both generated clients regenerated; a factory per report in both fixture files; the CLI's `analytics` family and one MCP tool; the surface doc's §3.12 and row 44. Every endpoint answers a real `404` until T2 | none |
| 2 | T2 — Burn-up, cycle time and cumulative flow in `reporting` | `be` | `be-java` | The three endpoints serve real rows; `DeliveryFacts` is the only file in `reporting` naming `issue_fact` or `release`; a unit test proves scope added after `release.created_at` is reported apart from original scope, one proves cycle time is per type, one proves an under-populated report answers `sufficient: false` with a reason rather than a flat series; `ReportingReadsAndNeverWritesTest` and `NoVelocityAnywhereTest` green | T1 |
| 3 | T3 — The Analytics screen, its four figures, the fault arm and the close-out | `fe` | `fe-react` | `/projects/:projectId/analytics` renders the four panels on the kit in both colour schemes; a too-little-data report renders its sentence, not an empty axis; `tcmsFaults.analytics('sparse')` drives it; the Analytics rail entry exists; `docs/features.md`, the MCP guide copy and plan 009's status table closed | T2 |

**Budget.** Three tasks, one stack and one session each, per `CLAUDE.md`'s task-cut rule. This is the
smallest module in the program and the cut is the plain one — contract, backend, frontend — with no
seam split inside a stack, because the backend is one service package over one projection table and
the frontend is one screen. T3 carries the documentation close-out, per rule 6 of *How a module's
plan is cut into tasks*: it is the only session that knows what actually landed.

**Estimates, counted rather than guessed.** T1 reads `openapi.yaml`'s `time` and `staffing` blocks
(~700 lines), both `fixtures.ts`, the CLI/MCP read files and the surface doc — ~55k. T2 reads this
plan's §T2, `ReadinessService`, `IssueFactProjection`, `time/IssueFacts.java`,
`billing/IssueSubjectFacts.java`, `StaffingReadsNoTimeEntryTest` and writes ~1,400 lines of Java plus
its tests — ~95k. T3 reads the mockup, `Figure.tsx`, `FigurePanel.tsx`, `StaffingPage.tsx` as the
nearest screen, `index.css`'s token block, `devFaults.ts` and writes ~1,100 lines of TSX — ~110k.
All three inside 150k.

---

## Prompts — one per task

One task is one session. Each block below is complete on its own — what to read, in what order, the
traps that specific task will hit, what to commit and which gate to run. **A prompt is living text:**
a task that lands something different from what this plan predicted rewrites the prompts below it
before it stops, in place, with no changelog appended.

Every task has landed. Its prompt blocks were deleted when the plan was filed (2026-10-05); they are in git history before that commit.

## Decisions

Each of these is a decision *and its reason*. Several look arbitrary until you read the failure mode
they prevent — which is why every prompt above tells its task to read this section in full.

### D-a — There is no migration, and that was verified against the directory rather than the table

Plan 009's M9 line says `db` — none, and adds the condition it depends on: *"'None' holds only
because M1 put `created_at` / `status_entered_at` / `done_at` / `release_assigned_at` on `issue_fact`
and M3 put `created_at` on `release` (review R12). If either was skipped, M9 takes the next free
version."* All five were checked in the migration files before this plan was written:

| Column | Where | Line |
|---|---|---|
| `issue_fact.created_at` | `V3__tracker_core.sql` | 338 |
| `issue_fact.status_entered_at` | `V3__tracker_core.sql` | 339 |
| `issue_fact.done_at` | `V3__tracker_core.sql` | 340 |
| `issue_fact.release_assigned_at` | `V3__tracker_core.sql` | 341 |
| `release.created_at` | `V6__release.sql` | 84 |

`V3`'s reference data seeds a `Done` status with `kind = 'DONE'` (line 538), and `issue_status.kind`
is `CHECK (kind IN ('NOT_STARTED','IN_PROGRESS','DONE'))` (line 80) — the second thing the prompt
asked to confirm before starting. **So M9 takes no version.** The next free one is `V13`
(`ls backend/src/main/resources/db/migration/` reads `V1`–`V12` as of 2026-09-06) and this module
leaves it free.

**R15's last word applies here and is honoured by having read the directory:** *"Nothing after M9 may
read a `V` from a table; read the directory."* This module is the one that ends the sequence, and it
ends it by needing nothing.

### D-b — Delivery analytics is a screen, not a report document — three synchronous endpoints, and no `ReportComputation`

Scope §821 says `reporting` gains *"new report types: completion, release burn-up, release readiness,
cycle time, CFD"* and that *"existing renderer/capability machinery is reused as-is"*. Read literally
that means three `ReportComputation` beans plugged into `ReportJobService`, and the screen would then
have to `POST …/reports/generate`, poll a job, and read the stored model. **Declined**, and the
argument on the other side is already written in this repo:
`ReportModel`'s own header says *"a synchronous 'preview' endpoint would be a second code path
producing a second answer, which is the drift the one-model architecture exists to prevent"*.

That paragraph is exactly right about what it is defending, and it is defending a *document*: a thing
somebody generates, downloads, files and reads three weeks later, where the screen and the export
must be the same bytes. Delivery analytics is not that. It has a **release picker** — a control whose
whole purpose is to change the answer in under a second — and a PM who must press *Generate* and
watch a spinner to find out whether 2.4 is converging will not open it twice.

So: **three plain `GET`s, three services, one code path each, and nothing registered in
`ReportComputations`.** The drift `ReportModel` warns about is avoided the other way — there is no
second path to disagree with, because the export (D-m) serialises the exact JSON the screen already
holds. What this costs, stated rather than discovered: **delivery analytics cannot be produced as an
HTML or Markdown artifact**, and a future requirement for one is a `ReportComputation` adapter over
these same services, written then, with the formatting done once inside the computation the way
`ReportModel` requires.

### D-c — Cycle time is measured from **created**, not from started, and the wire says which

The mockup's subtitle reads *"started → done, by type"*. **This product cannot compute that.** What
`issue_fact` carries is `created_at`, `status_entered_at` — *when the issue entered its **current**
status* — and `done_at`. For a completed issue, `status_entered_at` is when it entered the Done
status, which is approximately `done_at` and is not when work began. There is no status history to
reconstruct it from: `issue_transition` is the **workflow allow-list** (`PRIMARY KEY (from_status_id,
to_status_id)`, the legal moves), not a log of moves that happened, and `audit_event` belongs to
another module and carries no retention guarantee a report may depend on.

`docs/plans/implemented/009-execution-breakdown.md` `t45` predicted *"cycle time is per issue type from
`status_entered_at` and `done_at`"*. That is the shape this decision corrects: over the set of issues
that are done, that subtraction is approximately zero for every one of them.

**So the report measures `created_at → done_at` and calls it that**, on the wire as
`measuredFrom: "CREATED"` and on the screen as the panel's subtitle. It is the strict definition of
*lead time*; naming it "started → done" would be the class of defect `CLAUDE.md` records as *two
contradictory percentages on one screen* — a number whose label says it is a different number.

The `measuredFrom` enum has exactly one member. That is deliberate: it is the field that will carry
`STARTED` the day a status-history table exists, and a client written against it today keeps working.
An enum with a `STARTED` member nothing can produce would be the opposite — a promise in the schema.

### D-d — The cumulative flow has a fourth band, `BEFORE_CURRENT_STATUS`, and it is the honest one

Same missing history, different consequence. At a past date `T`, an issue's status is knowable only
when one of two things is true: it had already reached Done (`done_at <= T`, and `done_at` is *first
reached Done, never cleared by a reopen*), or it was already in the status it is in now
(`status_entered_at <= T`). Otherwise the issue existed and this product does not know where it was.

Two ways to draw that, and the tempting one is wrong:

| Option | What it does | Verdict |
|---|---|---|
| Fold the unknown into `NOT_STARTED` | Three bands, the mockup's legend, a chart that always looks complete | **Rejected.** It is a guess drawn as data, and it is *systematically* wrong in one direction: the further back you look, the more of the "not started" band is invention. |
| A fourth band, named | Four bands; the fourth is empty at the right-hand edge and grows leftward | **Taken.** |

The fourth band also *is* the too-little-data signal for this figure, at no extra cost: a project
whose issues all moved recently has a mostly-unknown left half, which is a true statement about what
can be said. `sufficient` goes false when the band exceeds a third of the issues at the oldest point,
so the screen stops drawing and says why.

**A status-history table would fix this properly and M9 does not add one** — the module's stated
scope is no migration (D-a) and the prompt's fourth done-criterion is *"the cumulative flow reads
from `issue_fact`'s status timestamps"*. Recorded here as the named fix rather than left for someone
to rediscover: `issue_status_history (issue_id, status_id, entered_at)`, written by
`IssueFactProjection`'s one writer, would make this band shrink to nothing going forward and would
still be empty for everything before the day it landed.

### D-e — `sufficient: false` is an answer with a reason, carried by all three reports

*"A report with too little data says so rather than drawing a flat line"* is one of the module's four
done-criteria, and a criterion that lives only in a screen is one a second screen forgets. So it is
in the contract: every one of the three reports carries

```
ReportSufficiency: { sufficient: boolean, reason: string|null, issuesConsidered: integer }
```

`reason` is null exactly when `sufficient` is true — a sentence for a reader, not a code, because
every case has a different fix and a client that must map a code to a sentence is a second place the
sentence can be wrong. The four cases the services raise:

| Report | Insufficient when | Reason reads |
|---|---|---|
| burn-up | no counted issue points at the release | *"No story-level issue is targeting this release yet."* |
| burn-up | every counted issue was assigned after `release.created_at` (D-n) | *"Every issue was assigned after this release was created — the original-scope baseline is the import, not a plan."* |
| cycle time | fewer than five completed issues in the window, in total | *"Only N issues were completed in the last D days — too few to report a median."* |
| flow | `BEFORE_CURRENT_STATUS` exceeds a third at the oldest point | *"More than a third of the issues at the start of this window have no recorded status for that date."* |

`sufficient: false` is a **200**, not a 422 and not an empty body. The caller asked a well-formed
question and got a true answer about what can be said.

### D-f — Scope-added is counted with `ReadinessService`'s exact predicate, so two screens agree

`ReadinessService.SCOPE_ADDED` already counts this for the release page's
`scopeAddedAfterCreation` figure:

```sql
WHERE f.project_id = :projectId AND f.release_id = :releaseId AND f.deleted_at IS NULL
  AND f.type_level = 'STORY' AND f.counts_toward_completion
  AND f.release_assigned_at > :createdAt
```

The burn-up's chip reports the same number for the same release, so it uses the same predicate — the
counted set is **story-level issues whose type counts toward completion**, which excludes Epics (every
epic type carries `counts_toward_completion = false`), Spikes, and Tasks pointed at a release. That
last exclusion is a decision M3 already took and this module inherits rather than re-argues: a Task is
a breakdown *of* a story, so counting both counts the work twice, and counting the task instead would
make a release's total change with how finely somebody broke it down.

Two screens reporting different "scope added" figures for one release is precisely the defect
`CLAUDE.md` names as having typechecked and linted clean. The two are kept identical by copying the
predicate, not by a comment asking a future reader to.

`release_assigned_at` is stamped by `IssueFactProjection` at **projection** time, one outbox tick
after the assignment, floor one minute. So the measure is "assigned at least one tick after the
release row was created". `ReadinessService` says this in its javadoc; this module says it in the
same words rather than correcting for it, because a correction would need the assignment time, which
is the thing that does not exist.

### D-g — One class names the foreign tables: `reporting.DeliveryFacts`

`reporting` may import only the five foundation modules, so `tracker` is out — and the tables it needs
(`issue_fact` for everything, `release` for one timestamp and one version string) belong to `tracker`.
The pattern is settled and this is its **sixth** instance: `work.BoardFacts` reads `tracker`,
`execution.CaseFacts` reads `repository`, `tracker.release.ReleaseFacts` reads `execution`,
`time.IssueFacts` reads `tracker`, `billing.IssueSubjectFacts` reads `tracker`. `tracker`'s own
package-info states the rule in one line — *"the whole edge lives in exactly one class, which issues
SQL naming the foreign tables and returns records declared here"*.

`issue_fact` is a *projection built for exactly this*: its table comment says it exists *"for
reporting, time and billing to join WITHOUT importing tracker"*. `release` is not, and reading it is
the narrower crossing — one column (`created_at`) and one string (`version`) for the chart title. The
alternative, taking `release.created_at` as a request parameter from the client, was rejected outright:
a computation baseline supplied by the caller is a burn-up anyone can reshape by editing a query
string.

**`reporting` writes neither table**, and `ReportingReadsAndNeverWritesTest` asserts it over the
module's source text with comments stripped first — the same shape as `StaffingReadsNoTimeEntryTest`,
and the comment-stripping is load-bearing for the same reason: `package-info.java` argues for the rule
in prose and would otherwise report itself as the file breaking it.

### D-h — No velocity, and a test rather than a promise

D6 removed the sprint. There is no `sprint` table, no `sprint_issue`, no sprint-planning screen — so
there is nothing to measure velocity *over*, and a chart implying otherwise would be a lie about how
this team plans. The mockup states it on the screen itself, in the cycle-time panel's foot, and that
sentence ships verbatim.

The module's fourth done-criterion is *"no velocity chart exists anywhere"* — a property of the whole
tree, which no screen can assert about itself. So it is asserted, case-insensitively, over source
with comments and OpenAPI `description:` blocks stripped first, so the sentences that *explain* the
absence are not what fails.

**It is two files, and that is T2's finding rather than this plan's original shape.** The plan first
said one backend test scanning `backend/src/main`, `frontend/src`, `packages/*/src` and
`openapi.yaml`. That cannot be built: `backend/build.gradle.kts` declares its out-of-source test
inputs explicitly, and its own paragraph argues against adding `frontend/src` —

> A single FILE for the SPA and not its directory … `frontend/src` is another stack's whole source
> tree and declaring it would re-run every backend test on every unrelated component edit

— while a corpus that is *not* declared is worse than one that is wide: the rule then goes green by
cache the moment nothing else in the backend changed. So it splits along the seam everything else in
this repo splits on:

| File | Corpus | Why it can watch it cheaply |
|---|---|---|
| `backend/…/architecture/NoVelocityAnywhereTest.java` (T2) | `backend/src/main`, `docs/specs/openapi.yaml` | both already declared inputs of the backend `:test` task |
| `frontend/src/app/noVelocity.test.ts` (T3) | `frontend/src`, `packages/*/src` | the job that already owns both trees — `citedFiles.test.ts` reads across them in the same direction |

Two files, one rule, each declared where its corpus is cheap to watch. If a cadence is ever
introduced, **delete both in the commit that introduces it** rather than carving an exception into
either.

### D-i — Estimate accuracy is composed on the client, and `reporting` never reads `issue_time_rollup`

The mockup's fourth panel is Est / Logged / Drift per epic, footed *"Approved hours only — an
unapproved timesheet is not evidence."* Both halves already exist and neither is this module's:
`GET /time/rollup/epics/{key}` returns `{ epicKey, estimateHours, approvedHours, pendingHours,
children[] }`, and the epic list is the tracker's.

So **`reporting` computes nothing for this panel.** The screen fetches the epics, then one rollup per
epic through `useQueries`, and renders `(approved − estimate) / estimate`. The alternative — an
`analytics/estimate-accuracy` endpoint — would mean `reporting` reading `issue_time_rollup`, which
016 D-c placed off-limits to every module but `time` by design, or `reporting` re-deriving approved
hours from `time_entry`, which is a second definition of *approved* and the same failure in a
different table.

`approvedHours` and `pendingHours` stay apart, with nothing summing them (016 D-d). This panel counts
**approved** only, and says so where the reader can see it.

Cost, stated: N+1 requests for N epics on that panel. Accepted — the mockup shows three rows, a
project with forty epics is not the shape this screen is for, and the alternative is a bulk epic
endpoint in `time` that this module has no standing to add.

### D-j — Utilisation is M7's and does not appear here

Utilisation landed with M7 on 2026-09-04 (`44f8d39a`, plan 017): **approved hours ÷ allocated
capacity** (017 D-i), on `PersonCapacity.utilisationPercent`, served by `GET /staffing/capacity` and
`GET /staffing/people`, rendered on `/staffing`'s Utilisation panel **with that denominator printed on
the screen**, and **null rather than 0%** when allocated capacity for the window is zero, because a
person nobody has allocated has no utilisation.

`reports.html` does not draw it, and this module does not add it. A second utilisation on a second
screen — even one computed identically — is the two-contradictory-percentages failure waiting for its
first divergent edit. If it is wanted here later, this screen links to `/staffing`; it does not
restate the number.

### D-k — How each figure is drawn, given that this product has no categorical palette

`Figure.tsx`'s header is a constraint, not a style note: *"there is no categorical palette, by design
— the product's accents measured 2.5 ΔE apart under deuteranopia, indistinguishable, so a
three-series chart facets into three single-series ones instead of sharing an axis. No pie, donut or
gauge anywhere."* The mockup breaks it twice — two hue-coded lines on one burn-up axis, three hues in
the flow stack. The mockup is the source of truth for **the screen**; the render kit is the authority
on **how a series is drawn**. Reconciled figure by figure:

**The rule was re-derived rather than taken on trust, and it held.** T3 ran the tokens a three-band
stack would need through the `dataviz` skill's validator against this app's own surfaces, and
reproduced exactly the measurement `Figure.tsx` records:

| Candidate ramp | Adjacent ΔE (normal vision) | Lightest step vs surface |
|---|---|---|
| three steps of one hue, light | 9.2 at best inside the contrast band | 2.38:1 — under the 3:1 floor |
| three steps of one hue, dark | 13.0 | 1.87:1 — under the floor |
| three distinct hues | fails CVD separation outright — the reason there is no categorical palette | — |

Three bands cannot be both separable and legible on either surface. So:

| Figure | Mockup draws | This plan draws | Why it survives the rule |
|---|---|---|---|
| Burn-up | two lines, green + steel, on one axis | one **filled area** (done) inside one **bounding line** (total scope), the line dashed, plus the step where scope was added | Done ⊆ total is *containment*, not category. A reader who cannot separate the two steps of the hue still reads the gap, because one shape is inside the other — and the dash is the secondary encoding that survives greyscale. |
| Cycle time | four bars, four hues | four `Figure.HBar` rows, one series, label + value + `n=` per row | This is the facet the rule asks for, and the kit already has the component. Four types are four populations, not four slices of one. |
| Flow | three-hue stack | **four small multiples**, one band each, every one a single series in `--color-viz-1`; the fourth drawn as a 45° hatch in the rule colour | Faceting is what the kit prescribes and what the validator forces. It loses the stack's shared total and keeps the reading the mockup's own alt text describes — *"backlog shrinking, done growing, in-progress steady around five"* — which is four trajectories. |
| Estimate accuracy | a table | the same table, drift in `--color-failed` / `--color-passed` | Not a chart. Those are the outcome tier and that is correct: over budget is a bad outcome, and this is not a series. |

**The fourth flow band is a hatch and not a hue**, and that is the same decision one step further: a
fourth colour would say *a fourth status*, where the truth is *no information*. Texture is what the
skill offers for exactly this case, and it is also what survives forced-colours mode.

`vizTokens.ts` carries this argument beside the values, because the next person to look at
`reports.html` will see three colours and wonder why the app has one.

**Every colour is a CSS custom property.** `@theme inline` emits `var(--…)` rather than copying the
value, which is what makes the palette move with `[data-color-scheme]`; a literal hex in an SVG is a
chart that is correct in one scheme and unreadable in the other, and it typechecks.
`app/tokenSystem.test.ts` is the check.

### D-l — The rail entry is `Analytics`, at `/projects/:projectId/analytics`

The mockup's body carries `data-projnav="reports"` and its shell renders that slot as **Reports**. That
slot is taken: `/projects/:projectId/reports` is the shipped execution-reports screen —
definitions, jobs, artifacts — and `AppShell.tsx` already has its entry, with a paragraph explaining
why it is filed under Execution. **M9 does not remove or rename it**; nothing in plan 009 or the scope
document asks for that, and doing it silently would break every saved report definition's route.

So this screen is **Analytics**, a second entry in the same rail, filed under Project — what it is
*of* is the project's delivery, where Reports is *of* execution results. Recorded because the mockup
and the shipped app genuinely disagree here and the next reader deserves to know it was seen.

Ungated, like every other item in that rail: `report:view` is held by every project-scoped role that
renders a rail at all, so a gate would hide the entry from nobody while teaching that rail items come
and go by permission — the opposite of §31's disable-don't-hide.

### D-m — Export writes a CSV of the JSON already on screen

The mockup's header has an **Export** control. It does **not** call `POST …/reports/generate`: that
pipeline produces a report document from a `ReportModel` and this module registers no computation
(D-b), so pressing it would generate something else entirely.

Instead Export serialises **the exact response objects the panels are already rendering** to CSV,
client-side, one file per report in a small menu — and every file opens with a header line naming
what its numbers are *of*, including the server's insufficiency sentence when there is one. A CSV of
eleven rows with no denominator on it is the same defect as a figure with no subtitle, one file
format over. That is the strongest available form of *what you see
is what exports* — not two renderers over one model, but one set of numbers with no second reader at
all. It also needs no endpoint, no job row and no artifact storage.

What it costs: no PDF, no HTML, nothing filed on the server, and no export of a report the user has
not opened. All acceptable for a dashboard; none of it is what the report pipeline exists for.

### D-n — A Jira-imported project reads as all-scope-added, and the report says so

M4's importer creates the release row and then imports the issues, so every imported issue's
`release_assigned_at` lands after `release.created_at` and D-f's predicate counts **all** of it as
scope added after creation. That is literally true and completely useless: it renders a project that
added 100% of its scope late.

`ReadinessService` already has this behaviour and does not currently flag it. This report does:
`scopeAddedAfterCreation == countedTotal && countedTotal > 0` sets `sufficient: false` with the reason
in D-e's table, and the screen prints the sentence instead of the chart. It is the same mechanism as
every other insufficiency, which is why it costs one condition rather than a special case.

`tcmsFaults.analytics('all-late')` drives it, because no amount of clicking through a seeded instance
reaches it.

### D-o — `report:view`, and no permission-catalogue change at all

All three operations are gated on the existing `report:view` — held by Company Admin, Project Admin,
Test Lead, Test Engineer, Viewer and Automation Service, and by neither Account Manager nor Finance,
who hold no `project:read` either and deliberately (roles spec §A3, and the paragraph at its line 421).
A PM reading whether a release is converging is exactly the question that key names.

**So this module touches none of the three permission copies.** No `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`
edit, no `permission-matrix-summary.md`, no `README.md` — and `matrixCopies.test.ts` stays green by
not being provoked. Recorded because *"the module's slice of the catalogue, plus the roles spec and
both copies, all in one commit"* is on plan 009's per-module tax table, and the honest answer for M9
is *zero keys*, which a reader could otherwise mistake for an omission.

`tierClaims.test.ts` still applies: no comment in this module may claim somebody **lacks** a
permission §A3 grants them.

---

## File structure

**Backend** — one new package under the existing module. `reporting` gains no dependency; its
`allowedDependencies` is already `{api, tenancy, project, identity, storage}`.

| File | Responsibility |
|---|---|
| `backend/src/main/java/com/tcms/reporting/analytics/DeliveryFacts.java` | **The only file in `com.tcms.reporting` that names `issue_fact` or `release`** (D-g). Native SQL, returns records declared here. |
| `…/analytics/BurnUpService.java` | The weekly done/total series and the scope-added count (D-f), plus its two insufficiency cases. |
| `…/analytics/CycleTimeService.java` | Median and p90 of `created_at → done_at` per `type_name` (D-c). |
| `…/analytics/FlowService.java` | The four-band weekly stack (D-d). |
| `…/analytics/AnalyticsController.java` | Three `GET`s, gated through `ProjectService#readGranted` on `report:view` — 403 for a visible-but-ungranted project, 404 for an invisible one. |
| `…/analytics/AnalyticsViews.java` | The wire records: `BurnUpReport`, `BurnUpPoint`, `CycleTimeReport`, `CycleTimeBand`, `CumulativeFlowReport`, `CumulativeFlowPoint`, `ReportSufficiency`. |
| `…/analytics/package-info.java` | Why this package exists inside `reporting` and what it may not read. |

**Tests**

| File | Responsibility |
|---|---|
| `backend/src/test/java/com/tcms/reporting/analytics/BurnUpServiceTest.java` | Scope added after `created_at` is reported apart from original scope; the all-late case answers `sufficient: false`. |
| `…/analytics/CycleTimeServiceTest.java` | Bands are per type, never one aggregate; fewer than five completions is insufficient. |
| `…/analytics/FlowServiceTest.java` | The fourth band is populated for an issue whose `status_entered_at` is after the boundary, and empty at the newest point. |
| `…/analytics/AnalyticsIsolationTest.java` | Another company's issues are absent from all three, RLS-backed. |
| `backend/src/test/java/com/tcms/architecture/ReportingReadsAndNeverWritesTest.java` | `reporting` names `issue_fact`/`release` in exactly one file and writes neither. |
| `backend/src/test/java/com/tcms/architecture/NoVelocityAnywhereTest.java` | D-h, over the whole tree. |

**Frontend**

| File | Responsibility |
|---|---|
| `frontend/src/features/analytics/AnalyticsPage.tsx` | The screen: header, release picker, export menu, the 2×2 grid. |
| `…/analytics/BurnUpFigure.tsx` | Area-in-line burn-up (D-k), its three chips, its foot. |
| `…/analytics/CycleTimeFigure.tsx` | Four `HBar` rows, the `measuredFrom` subtitle, the no-velocity foot. |
| `…/analytics/FlowFigure.tsx` | Four-band stack, four-entry legend in stack order. |
| `…/analytics/EstimateAccuracyPanel.tsx` | Epics × `GET /time/rollup/epics/{key}` (D-i). |
| `…/analytics/exportCsv.ts` | D-m. |
| `frontend/src/api/queries/analytics.ts` | The three hooks, `TIMEOUTS.long`. |

**Modified:** `frontend/src/api/queries/keys.ts` (three keys, one factory), `…/queries/index.ts`
(barrel), `frontend/src/lib/devFaults.ts` (the `analytics` arm), `frontend/src/lib/labels.ts`
(`CycleTimeMeasuredFrom` and the four flow bands, each `satisfies Record<Union, string>`),
`frontend/src/app/router.tsx`, `frontend/src/app/AppShell.tsx`.

---

## T1 — The analytics contract, both generated clients, and the CLI/MCP verbs

**Deliverable:** Three path items under a new `analytics` tag in `docs/specs/openapi.yaml`, every
schema carrying its own `required` array; `frontend/src/api/schema.d.ts` and
`packages/client/src/schema.ts` regenerated from it in the same commit; a factory per report in both
`fixtures.ts` copies; the CLI's `analytics` command family and the `tcms_read_analytics` MCP tool;
`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` §3.12 and row 44. Every endpoint answers a
real `404` from a path the server does not serve — never a stub returning a fake success.
**Depends on:** none
**Stack:** `ts` · **Agent:** `tooling-engineer`

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Modify: `frontend/src/api/schema.d.ts` (generated — `npm run gen:api`)
- Modify: `packages/client/src/schema.ts` (generated — `cd packages/client && npm run gen:api`)
- Modify: `frontend/src/test/fixtures.ts`, `packages/client/src/test/fixtures.ts`
- Modify: `packages/cli/src/reads.ts`, `packages/mcp/src/reads.ts`
- Modify: `frontend/src/features/help/sections.tsx`
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

- [ ] **Step 1: Add the three path items**

Append to `docs/specs/openapi.yaml`'s paths, after the `billing` block. Note there is **no
`default:` keyword** anywhere below — `openapi-typescript` emits a defaulted property as *required*,
which turns every caller that omits it into a compile error (018 T1's finding, restated by 019 T1).

```yaml
  # --------------------------------------------------------- analytics ----
  # Plan 020 (M9). Three operations, three path items, tagged `analytics`, ALL READS and all gated
  # on the existing `report:view` (020 D-o) — this module adds no permission key, so the three
  # matrix copies are deliberately untouched.
  #
  # These are NOT report jobs. `reporting`'s `ReportComputation` pipeline produces documents you
  # generate, poll for and download; these are a dashboard with a release picker, answered
  # synchronously, registered in no computation registry (020 D-b).
  /projects/{projectId}/analytics/burn-up:
    parameters: [{ $ref: '#/components/parameters/ProjectId' }]
    get:
      tags: [analytics]
      operationId: getBurnUp
      summary: Done against total scope over time, for one release
      description: |
        Requires `report:view`. `reports.html`'s first panel, and the one figure in this product
        that distinguishes **scope added after the release was created** — the difference between
        `issue_fact.release_assigned_at` and `release.created_at`, which is the one number in the
        chart that cannot be reconstructed after the fact (009 review R12).

        The counted set is **story-level issues whose type counts toward completion** — identical to
        the release page's `scopeAddedAfterCreation` predicate, so the two screens cannot report
        different numbers for one release (020 D-f). A Task pointed at a release counts for nothing;
        an Epic is excluded by the same flag.
      parameters:
        - name: releaseId
          in: query
          required: true
          schema: { type: string, format: uuid }
          description: |
            Required rather than optional-meaning-latest. "The latest release" is a different
            question with its own answer on the roadmap, and a chart that silently changes which
            release it is about is the defect a picker exists to prevent.
      responses:
        '200':
          description: The burn-up, or a sufficiency answer saying why there is not one.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/BurnUpReport' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /projects/{projectId}/analytics/cycle-time:
    parameters: [{ $ref: '#/components/parameters/ProjectId' }]
    get:
      tags: [analytics]
      operationId: getCycleTime
      summary: How long issues take to finish, per issue type
      description: |
        Requires `report:view`. `reports.html`'s second panel, one band per issue type — **per type
        and never as one aggregate**, because a median over stories and tasks together is a number
        about neither.

        **Measured from `created_at`, not from when work started**, and `measuredFrom` says so on
        every response (020 D-c). This product has no status history — `issue_transition` is the
        workflow allow-list, not a log — so `status_entered_at` on a completed issue is when it
        entered the *Done* status, not when somebody picked it up. Reporting that subtraction as
        "started → done" would print approximately zero for every issue under a label claiming
        otherwise.
      parameters:
        - name: days
          in: query
          required: false
          schema: { type: integer, enum: [30, 90, 180] }
          description: |
            The window, ending now. Omitted means 90 — the server's fallback, stated here rather
            than as a `default:` keyword, which the generator would turn into a required property.
      responses:
        '200':
          description: One band per type that has a completed issue in the window.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/CycleTimeReport' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /projects/{projectId}/analytics/flow:
    parameters: [{ $ref: '#/components/parameters/ProjectId' }]
    get:
      tags: [analytics]
      operationId: getCumulativeFlow
      summary: Issues per status band, weekly
      description: |
        Requires `report:view`. `reports.html`'s third panel.

        **Four bands, not three** (020 D-d). At a past date an issue's band is knowable only when it
        had already reached done, or was already in the status it is in now. Otherwise it existed
        and this product does not know where it was — that is `BEFORE_CURRENT_STATUS`, which is
        empty at the newest point by construction and grows going backwards. Folding it into
        `NOT_STARTED` would draw a guess as data, systematically wrong in one direction.
      parameters:
        - name: weeks
          in: query
          required: false
          schema: { type: integer, enum: [8, 12, 26] }
          description: The window in whole weeks, ending this week. Omitted means 12.
      responses:
        '200':
          description: One point per week, oldest first.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/CumulativeFlowReport' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
```

- [ ] **Step 2: Add the seven schemas — every one with its own `required` array**

`required` does **not** descend, so `ReportSufficiency` needs its own even though it is only ever
nested. Append to `components/schemas`:

```yaml
    # ---------------------------------------------------------- analytics ----
    # Plan 020 (M9). Seven schemas. `ReportSufficiency` is nested in all three reports and carries
    # its own `required` array, because `required` does not descend and a schema without one makes
    # every generated property optional — which typechecks and is wrong everywhere.
    ReportSufficiency:
      type: object
      required: [sufficient, reason, issuesConsidered]
      description: |
        Whether there is enough data to draw this, and if not, why — **an answer, not an error**
        (020 D-e). A well-formed question about a project with three issues deserves a true
        statement about what can be said, at 200, not a 422 and not an empty series that renders as
        a flat line at zero.
      properties:
        sufficient: { type: boolean }
        reason:
          type: string
          nullable: true
          description: |
            A sentence for a reader, null exactly when `sufficient` is true. Prose rather than a
            code: every case has a different fix, and a client mapping codes to sentences is a
            second place the sentence can be wrong.
        issuesConsidered:
          type: integer
          description: How many non-deleted issues the window actually contained.

    BurnUpPoint:
      type: object
      required: [date, done, totalScope]
      properties:
        date: { type: string, format: date }
        done:
          type: integer
          description: Counted issues whose `done_at` is on or before this date.
        totalScope:
          type: integer
          description: |
            Counted issues whose `release_assigned_at` is on or before this date. The **step** in
            this series is scope added after the release was created — visible, never absorbed into
            the denominator silently.

    BurnUpReport:
      type: object
      required:
        [releaseId, releaseVersion, releaseCreatedAt, countedTotal, doneTotal,
         scopeAddedAfterCreation, points, sufficiency]
      properties:
        releaseId: { type: string, format: uuid }
        releaseVersion: { type: string, example: '2.4' }
        releaseCreatedAt:
          type: string
          format: date-time
          description: The baseline every "added after" comparison is against (009 review R12).
        countedTotal: { type: integer }
        doneTotal: { type: integer }
        scopeAddedAfterCreation:
          type: integer
          description: |
            Counted issues whose `release_assigned_at` is later than `releaseCreatedAt`. **The same
            predicate the release readiness page uses** (020 D-f) — the two must not disagree about
            one release. `release_assigned_at` is stamped at projection time, one outbox tick after
            the assignment, so this means "assigned at least one tick after the release was
            created"; there is no other source for it at all.
        points:
          type: array
          items: { $ref: '#/components/schemas/BurnUpPoint' }
          description: Weekly, oldest first. Empty when `sufficiency.sufficient` is false.
        sufficiency: { $ref: '#/components/schemas/ReportSufficiency' }

    CycleTimeMeasuredFrom:
      type: string
      description: |
        Which boundary the elapsed time is measured from. **One member today, deliberately** (020
        D-c): `STARTED` is what this becomes the day a status-history table exists, and an enum with
        a member nothing can produce would be a promise in the schema rather than a fact about the
        data.
      enum: [CREATED]

    CycleTimeBand:
      type: object
      required: [typeName, count, medianDays, p90Days]
      properties:
        typeName: { type: string, example: Story }
        count: { type: integer, description: Completed issues of this type in the window. }
        medianDays: { type: number, format: double }
        p90Days: { type: number, format: double }

    CycleTimeReport:
      type: object
      required: [measuredFrom, windowDays, bands, sufficiency]
      properties:
        measuredFrom: { $ref: '#/components/schemas/CycleTimeMeasuredFrom' }
        windowDays: { type: integer, example: 90 }
        bands:
          type: array
          items: { $ref: '#/components/schemas/CycleTimeBand' }
          description: |
            One per type with at least one completion in the window, ordered by `count` descending
            then `typeName`. A thin type is **returned with its `count`**, not hidden — the reader
            decides whether four issues is enough, and `sufficiency` names the thin ones.
        sufficiency: { $ref: '#/components/schemas/ReportSufficiency' }

    CumulativeFlowPoint:
      type: object
      required: [date, notStarted, inFlight, done, beforeCurrentStatus]
      properties:
        date: { type: string, format: date }
        notStarted: { type: integer }
        inFlight: { type: integer }
        done: { type: integer }
        beforeCurrentStatus:
          type: integer
          description: |
            Issues that existed on this date but whose status on it is not recorded anywhere (020
            D-d). Zero at the newest point by construction; it grows going backwards. This is the
            band that keeps the chart honest and it must not be folded into `notStarted`.

    CumulativeFlowReport:
      type: object
      required: [windowWeeks, points, sufficiency]
      properties:
        windowWeeks: { type: integer, example: 12 }
        points:
          type: array
          items: { $ref: '#/components/schemas/CumulativeFlowPoint' }
          description: Weekly, oldest first. Empty when `sufficiency.sufficient` is false.
        sufficiency: { $ref: '#/components/schemas/ReportSufficiency' }
```

- [ ] **Step 3: Regenerate BOTH clients**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

Expected: `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts` both gain the three
operations and seven schemas. **Both, in this commit.** One regenerated copy and one stale one is
the drift `contractPairing.test.ts` and the two contract-conformance harnesses exist to catch, and
they catch it in the *other* package's job.

- [ ] **Step 4: Add a factory per report to BOTH `fixtures.ts` copies**

`contractBodies.ts` needs **no** change — 016 T1, 017 T1, 018 T1 and 019 T1 all recorded this. Both
copies are the schema *walker*; the marked byte-identical region is `violationsOf` and its helpers
and holds no response bodies. Bodies are factories in `frontend/src/test/fixtures.ts` and
`packages/client/src/test/fixtures.ts`, which are **not** byte-identical to each other.

Add to each, in that file's own idiom:

```ts
export const sufficiency = (over: Partial<ReportSufficiency> = {}): ReportSufficiency => ({
  sufficient: true,
  reason: null,
  issuesConsidered: 27,
  ...over,
});

export const burnUpReport = (over: Partial<BurnUpReport> = {}): BurnUpReport => ({
  releaseId: '00000000-0000-4000-8000-0000000009a1',
  releaseVersion: '2.4',
  releaseCreatedAt: '2026-07-01T09:00:00Z',
  countedTotal: 27,
  doneTotal: 21,
  scopeAddedAfterCreation: 4,
  points: [
    { date: '2026-07-01', done: 0, totalScope: 23 },
    { date: '2026-08-12', done: 12, totalScope: 27 },
    { date: '2026-09-02', done: 21, totalScope: 27 },
  ],
  sufficiency: sufficiency(),
  ...over,
});

export const cycleTimeReport = (over: Partial<CycleTimeReport> = {}): CycleTimeReport => ({
  measuredFrom: 'CREATED',
  windowDays: 90,
  bands: [
    { typeName: 'Story', count: 18, medianDays: 6.2, p90Days: 14 },
    { typeName: 'Task', count: 41, medianDays: 1.8, p90Days: 4 },
    { typeName: 'Bug', count: 9, medianDays: 3.1, p90Days: 9 },
    { typeName: 'Spike', count: 3, medianDays: 4, p90Days: 7 },
  ],
  sufficiency: sufficiency({ issuesConsidered: 71 }),
  ...over,
});

export const cumulativeFlowReport = (
  over: Partial<CumulativeFlowReport> = {},
): CumulativeFlowReport => ({
  windowWeeks: 12,
  points: [
    { date: '2026-06-15', notStarted: 14, inFlight: 4, done: 2, beforeCurrentStatus: 7 },
    { date: '2026-08-03', notStarted: 9, inFlight: 5, done: 11, beforeCurrentStatus: 2 },
    { date: '2026-09-01', notStarted: 3, inFlight: 5, done: 19, beforeCurrentStatus: 0 },
  ],
  sufficiency: sufficiency(),
  ...over,
});
```

- [ ] **Step 5: The CLI's `analytics` family**

In `packages/cli/src/reads.ts`, beside the `billing` family:

```ts
{
  name: 'analytics burn-up',
  summary: 'Done against total scope for one release, and what was added after it was created',
  args: [
    { name: '--project', required: true },
    { name: '--release', required: true, help: 'release id' },
  ],
  run: (c, a) => c.get('/projects/{projectId}/analytics/burn-up', {
    path: { projectId: a.project }, query: { releaseId: a.release },
  }),
},
{
  name: 'analytics cycle-time',
  summary: 'Median and p90 days from creation to done, per issue type',
  args: [
    { name: '--project', required: true },
    { name: '--days', required: false, help: '30 | 90 | 180 (default 90)' },
  ],
  run: (c, a) => c.get('/projects/{projectId}/analytics/cycle-time', {
    path: { projectId: a.project }, query: a.days ? { days: Number(a.days) } : {},
  }),
},
{
  name: 'analytics flow',
  summary: 'Issues per status band, weekly',
  args: [
    { name: '--project', required: true },
    { name: '--weeks', required: false, help: '8 | 12 | 26 (default 12)' },
  ],
  run: (c, a) => c.get('/projects/{projectId}/analytics/flow', {
    path: { projectId: a.project }, query: a.weeks ? { weeks: Number(a.weeks) } : {},
  }),
},
```

Match the surrounding file's actual command-descriptor shape rather than this sketch's field names if
they differ — read `reads.ts` first. **No write commands**: every operation in this module is a read,
so nothing goes in `writes.ts` and nothing needs a `writeConformance.test.ts` `INVOCATIONS` entry.

- [ ] **Step 6: One MCP tool**

`tcms_read_analytics`, gate `report:view`, three arms (`burn-up`, `cycle-time`, `flow`). One tool and
not three: §2's rule is workflow-shaped tools, and *"how is this release going"* is one workflow. The
arm table in §3.12 is what satisfies §2, exactly as it did for `tcms_read_billing`.

- [ ] **Step 7: The guide copy and the two counts**

`frontend/src/features/help/sections.tsx` — `43 tools` becomes `44 tools` at both line 846 and line
893, and `tcms_read_analytics` joins the tool list. `guideCopies.test.ts` in **both** `cli` and `mcp`
reads this frontend file; a tool absent from it reddens the packages job.

- [ ] **Step 8: The surface doc — §3.12, row 44, and the stale heading**

Add row 44 to the tool table:

```
| 44 | `tcms_read_analytics` | `report:view` | `GET …/analytics/burn-up`, `…/cycle-time`, `…/flow` | 020 T1 — see §3.12 |
```

Add §3.12 in §3.11's shape, with the arm table and these three notes: that every operation is a read
so there is no write tool; that `measuredFrom` is on the wire because the product cannot measure from
`started` (D-c); and that there is no velocity verb and never will be (D6).

Then fix the §3 heading, which 019 left behind: it reads `~~21~~ ~~26~~ ~~28~~ ~~30~~ ~~34~~ ~~37~~
39 tools` while its own table has 43 rows. Make it `~~21~~ ~~26~~ ~~28~~ ~~30~~ ~~34~~ ~~37~~ ~~39~~
~~43~~ 44 tools` — struck through rather than corrected, because a struck number is how this repo
records that a count was once believed.

- [ ] **Step 9: Gate, on shanks**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run lint --workspaces
~/bin/tcms-remote packages npm run test:run --workspaces
~/bin/tcms-remote frontend npm run build
```

Expected: green. `packages npm run build` runs client → cli → mcp **in that order** — never
`--workspaces`, which runs them alphabetically and typechecks `cli` against a stale `client/dist`.

- [ ] **Step 10: Commit**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts frontend/src/api/types.ts \
        packages/client/src/schema.ts frontend/src/test/fixtures.ts \
        packages/client/src/test/fixtures.ts packages/cli/src packages/mcp/src \
        frontend/src/features/help/sections.tsx \
        docs/specs/2026-08-08-mcp-tool-and-command-surface.md \
        docs/plans/implemented/020-delivery-analytics.md
git commit -m "feat(analytics): the delivery-analytics contract, both clients, and the read verbs"
```

**Acceptance criteria**

- [ ] The four `issue_fact` columns and `release.created_at` are confirmed present (D-a) — done
      before this plan was written, and no migration task exists in it
- [ ] Every schema has its own `required` array, including `ReportSufficiency`; `contractPairing.test.ts` green
- [ ] No `default:` keyword on `days` or `weeks`
- [ ] No velocity endpoint, schema, enum member or CLI verb exists
- [ ] Both generated clients regenerated in this commit
- [ ] The tool count reads 44 in all three places

---

## T2 — Burn-up, cycle time and cumulative flow in `reporting`

**Deliverable:** The three T1 endpoints serve real rows from `issue_fact`; `DeliveryFacts` is the
only file in `com.tcms.reporting` naming `issue_fact` or `release`; a unit test proves scope added
after `release.created_at` is reported apart from original scope, one proves cycle time is per issue
type rather than one aggregate, one proves an under-populated report answers `sufficient: false`
with a reason rather than a flat series; `ReportingReadsAndNeverWritesTest` and
`NoVelocityAnywhereTest` are green.
**Depends on:** T1 (the contract the views serialise to)
**Stack:** `be` · **Agent:** `be-java`

**Files:**
- Create: `backend/src/main/java/com/tcms/reporting/analytics/{DeliveryFacts,BurnUpService,CycleTimeService,FlowService,AnalyticsController,AnalyticsViews,package-info}.java`
- Create: `backend/src/test/java/com/tcms/reporting/analytics/{BurnUpServiceTest,CycleTimeServiceTest,FlowServiceTest,AnalyticsIsolationTest}.java`
- Create: `backend/src/test/java/com/tcms/architecture/{ReportingReadsAndNeverWritesTest,NoVelocityAnywhereTest}.java`
- Modify: `backend/src/main/java/com/tcms/reporting/package-info.java` (one paragraph — what this package may not read)
- Modify: `backend/src/test/java/com/tcms/contract/ContractConventionTest.java` (seven pairings), `…/ContractEnumTest.java` (one enum owner)
- Create: `backend/src/test/java/com/tcms/reporting/analytics/AnalyticsFacts.java` (the `DeliveryFacts` fake the three unit tests drive)

- [ ] **Step 1: The wire records**

`AnalyticsViews.java` — plain records, one per T1 schema. No entity leaks out of the service layer.

```java
package com.tcms.reporting.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The three reports as they go on the wire — 020 T1's schemas, one record each.
 *
 * <p>{@link Sufficiency} is nested in all three and is an <strong>answer, not an error</strong>
 * (020 D-e): a well-formed question about a project with three issues deserves a true statement
 * about what can be said, at 200. {@code reason} is null exactly when {@code sufficient} is true.
 */
final class AnalyticsViews {

    private AnalyticsViews() {}

    record Sufficiency(boolean sufficient, String reason, int issuesConsidered) {
        static Sufficiency ok(int considered) {
            return new Sufficiency(true, null, considered);
        }

        static Sufficiency no(String reason, int considered) {
            return new Sufficiency(false, reason, considered);
        }
    }

    record BurnUpPoint(LocalDate date, int done, int totalScope) {}

    record BurnUpReport(
            UUID releaseId,
            String releaseVersion,
            Instant releaseCreatedAt,
            int countedTotal,
            int doneTotal,
            int scopeAddedAfterCreation,
            List<BurnUpPoint> points,
            Sufficiency sufficiency) {}

    /**
     * One member, deliberately (020 D-c). {@code STARTED} is what this becomes the day a
     * status-history table exists; an enum member nothing can produce would be a promise in the
     * schema rather than a fact about the data.
     */
    enum MeasuredFrom {
        CREATED
    }

    record CycleTimeBand(String typeName, int count, double medianDays, double p90Days) {}

    record CycleTimeReport(
            MeasuredFrom measuredFrom,
            int windowDays,
            List<CycleTimeBand> bands,
            Sufficiency sufficiency) {}

    record CumulativeFlowPoint(
            LocalDate date, int notStarted, int inFlight, int done, int beforeCurrentStatus) {}

    record CumulativeFlowReport(
            int windowWeeks, List<CumulativeFlowPoint> points, Sufficiency sufficiency) {}
}
```

- [ ] **Step 2: `DeliveryFacts` — the one class that names the foreign tables**

```java
package com.tcms.reporting.analytics;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The only file in {@code com.tcms.reporting} that names {@code issue_fact} or
 * {@code release}</strong> — 020 D-g, and the sixth instance of a pattern this codebase has settled
 * on: {@code work.BoardFacts} reads {@code tracker}, {@code execution.CaseFacts} reads
 * {@code repository}, {@code tracker.release.ReleaseFacts} reads {@code execution},
 * {@code time.IssueFacts} and {@code billing.IssueSubjectFacts} both read {@code tracker}.
 *
 * <p>{@code reporting}'s {@code allowedDependencies} names the five foundation modules, so the Java
 * half of the rule is enforced by ArchUnit and Modulith. <strong>The table half is invisible to
 * both</strong> — SQL is not a Java dependency — which is why
 * {@code ReportingReadsAndNeverWritesTest} reads this module's source text instead.
 *
 * <p>{@code issue_fact} is a projection built for exactly this; its own table comment says it exists
 * "for reporting, time and billing to join WITHOUT importing tracker". {@code release} is not, and
 * two columns of it are read here: {@code created_at}, the burn-up's baseline, and {@code version},
 * the chart's title. Taking the baseline from the request instead was rejected outright — a
 * computation baseline supplied by the caller is a burn-up anyone can reshape with a query string.
 *
 * <p><strong>Every arm carries {@code deleted_at IS NULL}.</strong> {@code issue_fact} mirrors
 * {@code issue.deleted_at} rather than removing the row, precisely so reporting over a closed period
 * does not change when somebody tidies the backlog afterwards. Omitting it makes every historical
 * point drift.
 */
@Component
class DeliveryFacts {

    /** A release's identity and its burn-up baseline. */
    record ReleaseBaseline(UUID releaseId, String version, Instant createdAt) {}

    /** One counted issue in a release, reduced to the three moments the burn-up needs. */
    record ReleaseIssue(Instant assignedAt, Instant doneAt) {}

    /** One completed issue, reduced to its type and its elapsed days. */
    record CompletedIssue(String typeName, double elapsedDays) {}

    /** One issue's three timestamps and its current band, for the flow's weekly classification. */
    record FlowIssue(Instant createdAt, Instant statusEnteredAt, Instant doneAt, String statusKind) {}

    /**
     * The counted set, and it is <strong>identical to {@code ReadinessService.SCOPE_ADDED}'s</strong>
     * (020 D-f): story-level issues whose type counts toward completion. An Epic is excluded by the
     * flag — every epic type carries {@code counts_toward_completion = false} — and a Task pointed
     * at a release is excluded because a Task is a breakdown OF a story, so counting both counts the
     * work twice. Two screens reporting different "scope added" figures for one release is the
     * defect this copy exists to prevent.
     */
    private static final String COUNTED = "f.type_level = 'STORY' AND f.counts_toward_completion";

    private final EntityManager entities;

    DeliveryFacts(EntityManager entities) {
        this.entities = entities;
    }

    Optional<ReleaseBaseline> release(UUID projectId, UUID releaseId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT r.id, r.version, r.created_at
                          FROM release r
                         WHERE r.id = :releaseId AND r.project_id = :projectId
                        """)
                .setParameter("releaseId", releaseId)
                .setParameter("projectId", projectId)
                .getResultList();
        return rows.stream()
                .findFirst()
                .map(r -> new ReleaseBaseline(
                        (UUID) r[0], (String) r[1], ((java.sql.Timestamp) r[2]).toInstant()));
    }

    List<ReleaseIssue> releaseScope(UUID projectId, UUID releaseId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT f.release_assigned_at, f.done_at
                          FROM issue_fact f
                         WHERE f.project_id = :projectId
                           AND f.release_id = :releaseId
                           AND f.deleted_at IS NULL
                           AND """ + COUNTED)
                .setParameter("projectId", projectId)
                .setParameter("releaseId", releaseId)
                .getResultList();

        List<ReleaseIssue> issues = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            issues.add(new ReleaseIssue(instant(row[0]), instant(row[1])));
        }
        return issues;
    }

    /**
     * Completed issues in the window, with their elapsed time in days.
     *
     * <p><strong>{@code created_at} and not a "started" moment</strong> (020 D-c). There is no
     * status history in this product: {@code issue_transition} is the workflow ALLOW-LIST, and
     * {@code status_entered_at} on a completed issue is when it entered the <em>Done</em> status.
     * Subtracting that from {@code done_at} yields approximately zero for every issue, under a label
     * claiming it is how long the work took.
     *
     * <p>The subtraction is done in SQL rather than in Java because {@code EXTRACT(EPOCH FROM …)}
     * is exact over {@code timestamptz} and does not depend on the JVM's zone.
     */
    List<CompletedIssue> completedSince(UUID projectId, Instant since) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT f.type_name,
                               EXTRACT(EPOCH FROM (f.done_at - f.created_at)) / 86400.0
                          FROM issue_fact f
                         WHERE f.project_id = :projectId
                           AND f.deleted_at IS NULL
                           AND f.done_at IS NOT NULL
                           AND f.done_at >= :since
                         ORDER BY f.type_name
                        """)
                .setParameter("projectId", projectId)
                .setParameter("since", since)
                .getResultList();

        List<CompletedIssue> completed = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            completed.add(new CompletedIssue(
                    (String) row[0], ((BigDecimal) row[1]).doubleValue()));
        }
        return completed;
    }

    /**
     * Every issue that existed by the end of the window, with the three timestamps the flow's
     * classification needs. One query for the whole chart — a loop of one query per week is 26 round
     * trips per page load.
     */
    List<FlowIssue> flowPopulation(UUID projectId, Instant createdBefore) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT f.created_at, f.status_entered_at, f.done_at, f.status_kind
                          FROM issue_fact f
                         WHERE f.project_id = :projectId
                           AND f.deleted_at IS NULL
                           AND f.created_at <= :createdBefore
                        """)
                .setParameter("projectId", projectId)
                .setParameter("createdBefore", createdBefore)
                .getResultList();

        List<FlowIssue> population = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            population.add(new FlowIssue(
                    instant(row[0]), instant(row[1]), instant(row[2]), (String) row[3]));
        }
        return population;
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((java.sql.Timestamp) value).toInstant();
    }
}
```

- [ ] **Step 3: `BurnUpService`**

```java
package com.tcms.reporting.analytics;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.reporting.analytics.AnalyticsViews.BurnUpPoint;
import com.tcms.reporting.analytics.AnalyticsViews.BurnUpReport;
import com.tcms.reporting.analytics.AnalyticsViews.Sufficiency;
import com.tcms.reporting.analytics.DeliveryFacts.ReleaseBaseline;
import com.tcms.reporting.analytics.DeliveryFacts.ReleaseIssue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Are we converging on 2.4?" — done against total scope over time, with the step where scope was
 * added after the release was created.
 *
 * <p><strong>That step is the point of the chart</strong> (009 review R12). A burn-up whose
 * denominator grows silently reports a team going backwards for no visible reason; this one shows
 * where the scope arrived, and {@code scopeAddedAfterCreation} names how much.
 */
@Service
public class BurnUpService {

    private static final String READ = "report:view";

    private final ProjectService projects;
    private final DeliveryFacts facts;

    BurnUpService(ProjectService projects, DeliveryFacts facts) {
        this.projects = projects;
        this.facts = facts;
    }

    /**
     * <p><strong>{@code readGranted}, not {@code readVisible}</strong> — and {@link GateResult}'s
     * own javadoc names {@code report:view} as the first permission this distinction was built for.
     * A caller who can see the project but lacks the key gets 403, because 404 would tell somebody
     * looking at the project that it had vanished; a caller who cannot see it gets 404, because
     * telling those two apart makes the endpoint an oracle for another company's tenancy. Every
     * read in {@code reporting} already goes through it — copy {@code ReportReadService}'s
     * {@code gate(...)} collapse rather than inventing a second shape.
     */
    @Transactional(readOnly = true)
    public GateResult<BurnUpReport> of(UUID userId, UUID projectId, UUID releaseId) {
        return projects.readGranted(userId, projectId, READ, project -> compute(projectId, releaseId));
    }

    private BurnUpReport compute(UUID projectId, UUID releaseId) {
        // A release id that names nothing in this project is Unreachable rather than an exception:
        // the picker's option list came from this project, so the only way to get here is a hand-
        // written query string, and 404 is what that deserves.
        ReleaseBaseline release = facts.release(projectId, releaseId).orElse(null);
        if (release == null) {
            return null;
        }

        List<ReleaseIssue> scope = facts.releaseScope(projectId, releaseId);
        int counted = scope.size();
        int done = (int) scope.stream().filter(i -> i.doneAt() != null).count();
        int addedLate = (int) scope.stream()
                .filter(i -> i.assignedAt() != null && i.assignedAt().isAfter(release.createdAt()))
                .count();

        Sufficiency sufficiency = sufficiencyOf(counted, addedLate);
        List<BurnUpPoint> points = sufficiency.sufficient()
                ? weekly(release.createdAt(), scope)
                : List.of();

        return new BurnUpReport(
                release.releaseId(), release.version(), release.createdAt(),
                counted, done, addedLate, points, sufficiency);
    }

    // `compute` returning null for an unknown release is collapsed to a 404 by the controller, the
    // same way `ReportReadService` collapses its own Optional-returning reads. Do not throw here:
    // an exception inside the gate's Function unwinds the read-only transaction the gate opened.

    /**
     * Two ways this report has nothing to say, and both are 200s (020 D-e).
     *
     * <p>The second is the imported-project case (020 D-n): M4 creates the release row and then
     * imports, so every imported issue's {@code release_assigned_at} lands after
     * {@code release.created_at} and the predicate counts all of it as late. That is literally true
     * and completely useless — it renders a project that added 100% of its scope after planning —
     * so the report says what happened instead of drawing it.
     */
    private static Sufficiency sufficiencyOf(int counted, int addedLate) {
        if (counted == 0) {
            return Sufficiency.no("No story-level issue is targeting this release yet.", 0);
        }
        if (addedLate == counted) {
            return Sufficiency.no(
                    "Every issue was assigned after this release was created — the original-scope "
                            + "baseline is the import, not a plan.",
                    counted);
        }
        return Sufficiency.ok(counted);
    }

    /**
     * One point per week from the release's creation to now, inclusive of both ends.
     *
     * <p>Computed in Java over rows already in memory rather than as N queries: the population is
     * one release's counted issues, which is tens, and the alternative is a query per week.
     */
    private static List<BurnUpPoint> weekly(Instant from, List<ReleaseIssue> scope) {
        LocalDate start = LocalDate.ofInstant(from, ZoneOffset.UTC);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<BurnUpPoint> points = new ArrayList<>();
        for (LocalDate at = start; !at.isAfter(today); at = at.plusWeeks(1)) {
            points.add(pointAt(at, scope));
        }
        // The final partial week is included so the chart's right-hand edge is today rather than
        // up to six days ago — a burn-up that stops before now is one a reader distrusts.
        if (points.get(points.size() - 1).date().isBefore(today)) {
            points.add(pointAt(today, scope));
        }
        return List.copyOf(points);
    }

    private static BurnUpPoint pointAt(LocalDate at, List<ReleaseIssue> scope) {
        Instant end = at.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        int total = 0;
        int done = 0;
        for (ReleaseIssue issue : scope) {
            if (issue.assignedAt() != null && issue.assignedAt().isBefore(end)) {
                total++;
                if (issue.doneAt() != null && issue.doneAt().isBefore(end)) {
                    done++;
                }
            }
        }
        return new BurnUpPoint(at, done, total);
    }
}
```

- [ ] **Step 4: `CycleTimeService`**

```java
package com.tcms.reporting.analytics;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.reporting.analytics.AnalyticsViews.CycleTimeBand;
import com.tcms.reporting.analytics.AnalyticsViews.CycleTimeReport;
import com.tcms.reporting.analytics.AnalyticsViews.MeasuredFrom;
import com.tcms.reporting.analytics.AnalyticsViews.Sufficiency;
import com.tcms.reporting.analytics.DeliveryFacts.CompletedIssue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How long issues take to finish, <strong>per type and never as one aggregate</strong> — a median
 * over stories and tasks together is a number about neither, and the mockup's four rows are four
 * different questions.
 *
 * <p><strong>Measured from creation</strong> (020 D-c), and {@link MeasuredFrom} says so on every
 * response rather than a comment saying it here.
 */
@Service
public class CycleTimeService {

    private static final String READ = "report:view";

    /** Below this, a median is a coincidence rather than a measurement. */
    private static final int ENOUGH = 5;

    static final int DEFAULT_WINDOW_DAYS = 90;

    private final ProjectService projects;
    private final DeliveryFacts facts;

    CycleTimeService(ProjectService projects, DeliveryFacts facts) {
        this.projects = projects;
        this.facts = facts;
    }

    @Transactional(readOnly = true)
    public GateResult<CycleTimeReport> of(UUID userId, UUID projectId, int windowDays) {
        return projects.readGranted(
                userId, projectId, READ, project -> compute(projectId, windowDays));
    }

    private CycleTimeReport compute(UUID projectId, int windowDays) {
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));
        List<CompletedIssue> completed = facts.completedSince(projectId, since);

        Map<String, List<Double>> byType = completed.stream().collect(Collectors.groupingBy(
                CompletedIssue::typeName,
                Collectors.mapping(CompletedIssue::elapsedDays, Collectors.toList())));

        List<CycleTimeBand> bands = new ArrayList<>();
        byType.forEach((type, days) -> bands.add(
                new CycleTimeBand(type, days.size(), percentile(days, 50), percentile(days, 90))));
        // Biggest population first, then alphabetical — deterministic, because a report whose row
        // order changes between two identical requests is one nobody trusts.
        bands.sort(Comparator.comparingInt(CycleTimeBand::count).reversed()
                .thenComparing(CycleTimeBand::typeName));

        return new CycleTimeReport(
                MeasuredFrom.CREATED, windowDays, List.copyOf(bands),
                sufficiencyOf(completed.size(), windowDays));
    }

    private static Sufficiency sufficiencyOf(int completed, int windowDays) {
        if (completed < ENOUGH) {
            return Sufficiency.no(
                    "Only " + completed + " issue" + (completed == 1 ? " was" : "s were")
                            + " completed in the last " + windowDays
                            + " days — too few to report a median.",
                    completed);
        }
        return Sufficiency.ok(completed);
    }

    /**
     * Nearest-rank percentile over a copy, sorted here.
     *
     * <p>Nearest-rank rather than interpolated: every value in the result is a duration some issue
     * actually took, so "the p90 is 14 days" names a real issue somebody can go and look at. An
     * interpolated 13.7 names nothing.
     */
    private static double percentile(List<Double> values, int p) {
        List<Double> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
```

- [ ] **Step 5: `FlowService` — and the fourth band**

```java
package com.tcms.reporting.analytics;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.reporting.analytics.AnalyticsViews.CumulativeFlowPoint;
import com.tcms.reporting.analytics.AnalyticsViews.CumulativeFlowReport;
import com.tcms.reporting.analytics.AnalyticsViews.Sufficiency;
import com.tcms.reporting.analytics.DeliveryFacts.FlowIssue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues per status band, weekly — and the band that makes it honest.
 *
 * <h2>Why there are four bands and not three (020 D-d)</h2>
 *
 * <p>At a past date an issue's band is knowable only when one of two things holds: it had already
 * reached done ({@code done_at <= T}, and {@code done_at} is <em>first</em> reached done, never
 * cleared by a reopen), or it was already in the status it is in now
 * ({@code status_entered_at <= T}). Otherwise it existed and this product does not know where it
 * was, because {@code issue_transition} is the workflow allow-list rather than a log of moves.
 *
 * <p>Folding that into {@code notStarted} would draw a guess as data, and it would be
 * <em>systematically</em> wrong in one direction: the further back the chart looks, the more of the
 * "not started" band is invention. So it is its own band, empty at the newest point by construction
 * and growing leftward — which is also, at no extra cost, this figure's too-little-data signal.
 */
@Service
public class FlowService {

    private static final String READ = "report:view";

    static final int DEFAULT_WINDOW_WEEKS = 12;

    /** Above this share of unknowns at the oldest point, the chart says so instead of drawing. */
    private static final double TOO_MUCH_UNKNOWN = 1.0 / 3.0;

    private final ProjectService projects;
    private final DeliveryFacts facts;

    FlowService(ProjectService projects, DeliveryFacts facts) {
        this.projects = projects;
        this.facts = facts;
    }

    @Transactional(readOnly = true)
    public GateResult<CumulativeFlowReport> of(UUID userId, UUID projectId, int windowWeeks) {
        return projects.readGranted(
                userId, projectId, READ, project -> compute(projectId, windowWeeks));
    }

    private CumulativeFlowReport compute(UUID projectId, int windowWeeks) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusWeeks(windowWeeks - 1L);
        List<FlowIssue> population = facts.flowPopulation(
                projectId, today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        List<CumulativeFlowPoint> points = new ArrayList<>();
        for (LocalDate at = start; !at.isAfter(today); at = at.plusWeeks(1)) {
            points.add(pointAt(at, population));
        }

        return new CumulativeFlowReport(
                windowWeeks, List.copyOf(points),
                sufficiencyOf(points.get(0), population.size()));
    }

    private static CumulativeFlowPoint pointAt(LocalDate at, List<FlowIssue> population) {
        Instant end = at.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        int notStarted = 0;
        int inFlight = 0;
        int done = 0;
        int unknown = 0;

        for (FlowIssue issue : population) {
            if (!issue.createdAt().isBefore(end)) {
                continue;
            }
            if (issue.doneAt() != null && issue.doneAt().isBefore(end)) {
                done++;
            } else if (issue.statusEnteredAt() != null && issue.statusEnteredAt().isBefore(end)) {
                // `issue_status.kind` is CHECK-constrained to these three, so the else is DONE —
                // and a DONE-kind issue with no done_at is a reopened one whose done_at is later,
                // caught by the branch above rather than here.
                if ("IN_PROGRESS".equals(issue.statusKind())) {
                    inFlight++;
                } else {
                    notStarted++;
                }
            } else {
                unknown++;
            }
        }
        return new CumulativeFlowPoint(at, notStarted, inFlight, done, unknown);
    }

    private static Sufficiency sufficiencyOf(CumulativeFlowPoint oldest, int considered) {
        int atOldest = oldest.notStarted() + oldest.inFlight() + oldest.done()
                + oldest.beforeCurrentStatus();
        if (considered == 0) {
            return Sufficiency.no("This project has no issues yet.", 0);
        }
        if (atOldest > 0 && (double) oldest.beforeCurrentStatus() / atOldest > TOO_MUCH_UNKNOWN) {
            return Sufficiency.no(
                    "More than a third of the issues at the start of this window have no recorded "
                            + "status for that date.",
                    considered);
        }
        return Sufficiency.ok(considered);
    }
}
```

> **`points` is still returned when `sufficient` is false for the flow**, unlike the burn-up: the
> reason is *about* the shape of the points, and a screen that wants to show the sentence over a
> greyed chart has what it needs. T3 renders the sentence and not the chart; the contract permits
> either.

- [ ] **Step 6: `AnalyticsController`**

Three `GET`s, matching T1's paths and query parameters, each delegating to its service and collapsing
the returned `GateResult` the way `ReportReadService` already does — `Allowed` with a null payload is
the unknown-release 404. The window fallbacks live here (`days` → 90, `weeks` → 12) and are validated against
T1's enums — an out-of-enum value is a 400 with the allowed set in the message, not a silent clamp.

- [ ] **Step 7: The three named unit tests**

`BurnUpServiceTest` — the acceptance criterion, written first:

```java
@Test
@DisplayName("scope added after the release was created is reported apart from original scope")
void scopeAddedLateIsSeparate() {
    Instant created = Instant.parse("2026-07-01T09:00:00Z");
    List<ReleaseIssue> scope = List.of(
            new ReleaseIssue(created.minus(Duration.ofHours(1)), null),   // original, open
            new ReleaseIssue(created.plus(Duration.ofMinutes(5)), null),  // original, within a tick
            new ReleaseIssue(created.plusSeconds(0), null),               // original, exactly at
            new ReleaseIssue(created.plus(Duration.ofDays(42)), null));   // ADDED LATE

    BurnUpReport report = reportOver(created, scope);

    assertThat(report.countedTotal()).isEqualTo(4);
    assertThat(report.scopeAddedAfterCreation()).isEqualTo(2);
}
```

Note what that fixture pins: the comparison is **strictly after**, so an issue assigned in the same
instant is original scope — matching `ReadinessService.SCOPE_ADDED`'s `>` exactly (020 D-f). The
"within a tick" row is late, and that is correct and deliberate: `release_assigned_at` is stamped at
projection time, so a five-minute gap is a real assignment made after the release existed.

`CycleTimeServiceTest`:

```java
@Test
@DisplayName("cycle time is reported per issue type, not as one aggregate")
void bandsArePerType() {
    CycleTimeReport report = reportOver(List.of(
            new CompletedIssue("Story", 6.2), new CompletedIssue("Story", 8.0),
            new CompletedIssue("Task", 1.8), new CompletedIssue("Task", 2.0),
            new CompletedIssue("Bug", 3.1), new CompletedIssue("Bug", 4.0)));

    assertThat(report.bands()).extracting(CycleTimeBand::typeName)
            .containsExactly("Bug", "Story", "Task");   // count desc, then alphabetical
    assertThat(report.bands()).hasSize(3);
    assertThat(report.measuredFrom()).isEqualTo(MeasuredFrom.CREATED);
}

@Test
@DisplayName("fewer than five completions answers sufficient:false with a reason, not a flat series")
void tooLittleDataSaysSo() {
    CycleTimeReport report = reportOver(List.of(
            new CompletedIssue("Story", 6.2), new CompletedIssue("Task", 1.8)));

    assertThat(report.sufficiency().sufficient()).isFalse();
    assertThat(report.sufficiency().reason())
            .isEqualTo("Only 2 issues were completed in the last 90 days — "
                    + "too few to report a median.");
}
```

`FlowServiceTest`:

```java
@Test
@DisplayName("an issue whose status was entered after the boundary lands in the fourth band")
void unknownStatusIsItsOwnBand() {
    LocalDate boundary = LocalDate.now(ZoneOffset.UTC).minusWeeks(8);
    FlowIssue movedRecently = new FlowIssue(
            boundary.minusDays(30).atStartOfDay(ZoneOffset.UTC).toInstant(),  // created before
            Instant.now(),                                                    // moved yesterday
            null, "IN_PROGRESS");

    CumulativeFlowReport report = reportOver(List.of(movedRecently), 12);

    assertThat(report.points().get(0).beforeCurrentStatus()).isEqualTo(1);
    assertThat(report.points().get(0).notStarted()).isZero();
    // Empty at the newest point by construction, which is the property that makes the band honest.
    assertThat(report.points().get(report.points().size() - 1).beforeCurrentStatus()).isZero();
}
```

`AnalyticsIsolationTest` boots the real context and asserts another company's issues are absent from
all three — RLS-backed, Testcontainers, the way every other isolation test in this repo works.

- [ ] **Step 8: `ReportingReadsAndNeverWritesTest`**

Copy `StaffingReadsNoTimeEntryTest`'s shape exactly: read `com.tcms.reporting`'s source text, **strip
comments first** (`package-info.java` argues for the rule in prose and would otherwise report itself
— §152's trap, found once inside the check written to end it), assert that `issue_fact` and `release`
appear in `DeliveryFacts.java` and nowhere else in the module, and that no `INSERT`, `UPDATE` or
`DELETE` in the module names either.

Match the table name on **token boundaries, not as a substring** — 019 D-w's finding: `release`
substring-matches `release_version`, `release_sort_key` and `release_assigned_at`, three columns
`reporting` legitimately reads on tables it owns.

- [ ] **Step 9: `NoVelocityAnywhereTest`**

```java
@Test
@DisplayName("no velocity chart, endpoint, verb or field exists anywhere (scope D6)")
void noVelocity() {
    List<String> hits = sourcesUnder(
                    Path.of("backend/src/main"), Path.of("frontend/src"),
                    Path.of("packages/cli/src"), Path.of("packages/mcp/src"),
                    Path.of("packages/client/src"), Path.of("docs/specs/openapi.yaml"))
            .flatMap(NoVelocityAnywhereTest::velocityMentionsOutsideProse)
            .toList();

    assertThat(hits)
            .as("D6 removed the sprint velocity would be measured over. A chart implying "
                    + "otherwise is a lie about how this team plans.")
            .isEmpty();
}
```

`velocityMentionsOutsideProse` strips Java/TS comments and OpenAPI `description:` blocks before
matching, case-insensitively, so the sentences that *say the product has none* are not hits. Add
`docs/specs/openapi.yaml` to `backend/build.gradle.kts`'s declared test inputs **only if it is not
already there** — it is, under the `docs/specs/` entry, but check rather than assume: a new
out-of-source read that is not declared makes the rule green by cache.

- [ ] **Step 10: Gate, on shanks**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew build
```

Expected: green except the six pre-existing `com.tcms.reporting.FlakeScoreTest` failures — an expired
90-day fixture window, proven pre-existing on 2026-08-30, `3586 of 3592` green. Do not attribute them
to this module. `BUILD FAILED` with `Could not write XML test results` and **zero** assertion failures
is an fd-limit problem: `ulimit -n 65535; ./gradlew build`.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/tcms/reporting/analytics \
        backend/src/main/java/com/tcms/reporting/package-info.java \
        backend/src/test/java/com/tcms/reporting/analytics \
        backend/src/test/java/com/tcms/architecture/ReportingReadsAndNeverWritesTest.java \
        backend/src/test/java/com/tcms/architecture/NoVelocityAnywhereTest.java \
        docs/plans/implemented/020-delivery-analytics.md
git commit -m "feat(analytics): burn-up, cycle time and flow over issue_fact"
```

**Acceptance criteria**

- [ ] A unit test proves scope added after the release's `created_at` is reported separately from original scope
- [ ] Cycle time is reported per issue type, not as one aggregate
- [ ] A report with too little data reports that, rather than returning a flat series
- [ ] The flow's fourth band is populated and is zero at the newest point
- [ ] ArchUnit green — `reporting` does not import `tracker`; `DeliveryFacts` is the only file naming `issue_fact` or `release`
- [ ] `NoVelocityAnywhereTest` green over the whole tree

---

## T3 — The Analytics screen, its four figures, the fault arm and the close-out

**Deliverable:** `/projects/:projectId/analytics` renders reports.html's four panels on the render
kit, correct in light and dark on the app's own tokens; a report whose `sufficiency.sufficient` is
false renders its sentence rather than an empty axis; `tcmsFaults.analytics('sparse')` and
`('all-late')` drive both insufficiency paths; the Analytics rail entry exists; `docs/features.md`
and plan 009's status table are closed.
**Depends on:** T2 (the three endpoints answering with real rows)
**Stack:** `fe` · **Agent:** `fe-react`

**Files:**
- Create: `frontend/src/features/analytics/{AnalyticsPage,BurnUpFigure,CycleTimeFigure,FlowFigure,EstimateAccuracyPanel}.tsx`, `…/exportCsv.ts`
- Create: `frontend/src/features/analytics/{AnalyticsPage,BurnUpFigure,FlowFigure}.test.tsx`
- Create: `frontend/src/app/noVelocity.test.ts` (the frontend half of D-h)
- Create: `frontend/src/api/queries/analytics.ts`
- Modify: `frontend/src/api/queries/{keys,index}.ts`, `frontend/src/lib/{devFaults,labels}.ts`, `frontend/src/app/{router,AppShell}.tsx`
- Modify: `docs/features.md`, `docs/plans/implemented/009-project-management-expansion-modules.md`

- [ ] **Step 1: The fault arm, before the feature it proves**

`CLAUDE.md`: *build the fault switch before the feature it proves — a resilience behaviour you
cannot trigger is one you have not tested*. Neither insufficiency path is reachable by clicking
through a seeded instance, which is exactly why they exist. In `frontend/src/lib/devFaults.ts`,
beside `BillingFault`:

```ts
/**
 * The two answers this screen cannot be shown by using the app.
 *
 * `sparse` forces `sufficiency.sufficient` false on all three reports, which is the difference
 * between a panel that says why it cannot draw and a flat line at zero — the failure 020 D-e
 * exists to prevent. `all-late` forces the imported-project case (020 D-n): every issue assigned
 * after the release was created, which is literally true of every Jira import and completely
 * useless as a chart.
 */
export type AnalyticsFault = 'sparse' | 'all-late';
```

Wire it in `maybeFault` and `verdictFor` for `/analytics/`, and add `analytics(kind)` to the console
API. Both guards stay a literal `import.meta.env.DEV` — route that check through an imported constant
and Rollup keeps the module in the production build.

- [ ] **Step 2: The three keys, on the one factory**

In `frontend/src/api/queries/keys.ts`. **Never add a second factory.**

```ts
  /*
    020 (M9). Under the project prefix, so a project switch drops all three, and each carries its
    own window: the release picker and the 30/90/180 selector are what a reader changes, and a key
    that ignored them would serve the previous answer while the control said otherwise.
  */
  analyticsBurnUp: (p: string, releaseId: string) =>
    ['projects', p, 'analytics', 'burn-up', releaseId] as const,
  analyticsCycleTime: (p: string, days: number) =>
    ['projects', p, 'analytics', 'cycle-time', days] as const,
  analyticsFlow: (p: string, weeks: number) =>
    ['projects', p, 'analytics', 'flow', weeks] as const,
```

- [ ] **Step 3: The query module**

`frontend/src/api/queries/analytics.ts`, exported through the existing barrel. `TIMEOUTS.long` for
all three — these are aggregations over a projection, not typeahead. `enabled` on the burn-up gates
on a chosen `releaseId`.

```ts
export function useBurnUp(projectId: string, releaseId: string | null) {
  return useQuery({
    queryKey: keys.analyticsBurnUp(projectId, releaseId ?? ''),
    enabled: releaseId != null,
    queryFn: ({ signal }) =>
      api.get<BurnUpReport>(`${base(projectId)}/analytics/burn-up${qs({ releaseId })}`, {
        signal,
        timeoutMs: TIMEOUTS.long,
      }),
  });
}
```

`useCycleTime(projectId, days)` and `useFlow(projectId, weeks)` follow the same shape with no
`enabled` gate — both have a server-side fallback, so they answer without a selection.

- [ ] **Step 4: The burn-up figure — area inside a line**

The mockup draws two hue-coded polylines on one axis. `Figure.tsx`'s header forbids that: *there is
no categorical palette, by design — the accents measured 2.5 ΔE apart under deuteranopia*. D-k's
resolution, and it is a better chart as well as a compliant one:

```tsx
/*
  Done is a FILLED AREA; total scope is the LINE that bounds it. Done ⊆ total, so the reading is
  containment rather than category — a reader who cannot separate the two hues still reads the gap,
  because one shape is inside the other. That is what lets this figure share an axis at all under
  the kit's no-categorical-palette rule (020 D-k).

  Every colour is a custom property. `@theme inline` emits `var(--…)` rather than copying the value,
  which is what moves the whole palette on `[data-color-scheme]`; a literal hex here is a chart that
  is correct in one scheme and unreadable in the other, and it typechecks. `app/tokenSystem.test.ts`
  is the check.
*/
<svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={describeBurnUp(report)}>
  <path d={areaPath(report.points, (p) => p.done)} fill="var(--color-success)" opacity={0.28} />
  <path d={linePath(report.points, (p) => p.done)} fill="none"
        stroke="var(--color-success)" strokeWidth={2.5} />
  <path d={linePath(report.points, (p) => p.totalScope)} fill="none"
        stroke="var(--color-viz-2)" strokeWidth={2} strokeDasharray="4 3" />
</svg>
```

`aria-label` is a sentence, not a list of numbers — the mockup's own is the model: *"Burn-up chart:
total scope rising from 23 to 27 stories, done rising from 0 to 21 over ten weeks"*. Build it from
the report, so it cannot go stale.

The three chips below it are the mockup's, verbatim in structure: `Done · {doneTotal}`,
`Total scope · {countedTotal}`, and `+{scopeAddedAfterCreation} added after the release was created`
— the third rendered only when that count is above zero, because `+0 added` is a sentence about
nothing. The panel foot keeps the mockup's sentence: *"Scope added after 2.4 was created is the step
in the dashed line — visible, not absorbed into the denominator silently."*

- [ ] **Step 5: Cycle time — four `HBar` rows, and the subtitle that says what it measured**

```tsx
<FigurePanel
  title="Cycle time"
  subtitle={`${MEASURED_FROM[report.measuredFrom]} → done, by type, last ${report.windowDays} days`}
>
  {report.bands.map((band) => (
    <HBar
      key={band.typeName}
      label={band.typeName}
      fraction={band.medianDays / longestMedian}
      value={`${band.medianDays.toFixed(1)} d · ${band.p90Days.toFixed(0)} d p90`}
    />
  ))}
</FigurePanel>
```

`MEASURED_FROM` goes in `lib/labels.ts` as `satisfies Record<CycleTimeMeasuredFrom, string>` — `{
CREATED: 'Created' }` — so the day the enum gains `STARTED`, an unlabelled member is a compile error
rather than a blank on the screen.

**The subtitle is the correction the mockup needs** (D-c). It reads *created → done*, because that is
what was measured; `FigurePanel`'s subtitle is where every figure in this product states what its
number is *of*, and this one states its bounds. The panel foot keeps the mockup's velocity sentence
word for word:

> No velocity chart — there are no sprints to measure it over, and inventing one would report a
> number nobody planned against.

- [ ] **Step 6: Flow — four bands, legend in stack order**

Stacked areas on the progress ramp, bottom to top: `--color-viz-2` (not started) → `--color-wip` (in
flight) → `--color-success` (done), with `beforeCurrentStatus` drawn above them in `--color-rule`
with a diagonal hatch `<pattern>`, so it reads as *absent information* rather than as a fourth
status. The legend lists all four in stack order. The panel foot:

> The fourth band is issues that existed then whose status on that date is not recorded — this
> product has no status history. It is empty at the right-hand edge and grows going back.

- [ ] **Step 7: Estimate accuracy — composed here, from `time`'s endpoint**

```tsx
/*
  020 D-i. `reporting` computes nothing for this panel: the estimate and the approved hours both
  live in `time`'s own rollup, and an analytics endpoint for them would mean `reporting` reading
  `issue_time_rollup` — which 016 D-c placed off-limits to every module but `time` — or re-deriving
  "approved", which is a second definition of the word.

  APPROVED HOURS ONLY. `approvedHours` and `pendingHours` are separate on the wire and nothing in
  this product sums them (016 D-d); an unapproved timesheet is not evidence, and the panel foot says
  so where the reader can see it.

  N+1 by design and by measurement: the mockup shows three rows, and the alternative is a bulk epic
  endpoint in `time` that this module has no standing to add.
*/
const rollups = useQueries({
  queries: epics.map((epic) => epicTimeRollupQuery(epic.key)),
});
```

Drift is `(approvedHours - estimateHours) / estimateHours`, rendered in `--color-success` when
negative and `--color-failed` when positive. **An epic with no estimate renders no drift** — an em
dash, never `0%`: zero is the claim that the estimate was exactly right.

- [ ] **Step 8: Insufficiency, on every panel**

```tsx
if (!report.sufficiency.sufficient) {
  return (
    <FigurePanel title={title} subtitle={subtitle}>
      <StateBlock kind="empty" title={report.sufficiency.reason ?? 'Not enough data yet.'} />
    </FigurePanel>
  );
}
```

No axis, no grid, no zero series. A flat line at zero is the lie this criterion exists to stop, and
it is the module's third done-criterion. Confirm `StateBlock`'s `empty` arm renders a plain sentence
in this position; if its `title` is styled as a heading rather than prose, use the arm that reads as
a sentence rather than restyling `StateBlock` from a feature folder.

- [ ] **Step 9: The route and the rail entry**

`router.tsx`: a lazy route at `/projects/:projectId/analytics`, in the project segment beside
`reports`. `AppShell.tsx`: an **Analytics** entry, filed under Project — D-l records why it is not
called Reports and does not take that slot, and why it is ungated like every other item in that rail.

- [ ] **Step 10: Tests**

`AnalyticsPage.test.tsx` — renders all four panels from the fixtures T1 added; asserts the burn-up's
third chip is absent when `scopeAddedAfterCreation` is 0 and present when it is 4; asserts the
cycle-time subtitle contains `created → done`; asserts an insufficient report renders its `reason`
and **no** `<svg>`. `FlowFigure.test.tsx` asserts four legend entries.

Use `createTestQueryClient` — **never build your own `QueryClient` in a test**, only that one has the
caches. A render that trips an error boundary FAILS unless the test calls
`renderingIntoAnErrorBoundary()` and says why; likewise `drivingAFailedRead()`.

`frontend/src/app/noVelocity.test.ts` is the other half of D-h: read every `.ts`/`.tsx` under
`frontend/src` and `../packages/*/src`, strip `/* */` and `//` comments, and assert no
case-insensitive `velocity` survives. Assert the corpus is non-empty first — an empty walk and a
clean tree look identical from the rule itself, which is how a source-text check passes silently the
day a layout moves.

- [ ] **Step 11: Gate, on shanks**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

`npm run build` is `tsc -b` + vite build and is THE gate — not `tsc --noEmit`, which has reported 0
errors while the build failed with nine.

- [ ] **Step 12: Look at it, in both colour schemes**

The gates prove it compiles, not that it works: a no-op theme toggle, two contradictory percentages
on one screen and a provider-ordering bug that rendered nothing but the error page all typechecked and
linted clean. Open `/projects/:projectId/analytics` in the running app, toggle light and dark, and
check each figure's fills and strokes actually move. Whatever is *not* clicked through goes in the
built-and-unverified list — that list is the deliverable of this step, not an admission.

- [ ] **Step 13: Commit the screen**

```bash
git add frontend/src/features/analytics frontend/src/api/queries/analytics.ts \
        frontend/src/api/queries/index.ts frontend/src/api/queries/keys.ts \
        frontend/src/lib/devFaults.ts frontend/src/lib/labels.ts \
        frontend/src/app/router.tsx frontend/src/app/AppShell.tsx \
        docs/plans/implemented/020-delivery-analytics.md
git commit -m "feat(analytics): the delivery-analytics screen and its four figures"
```

- [ ] **Step 14: Close the module, and the program**

Per plan 009's [hand-off protocol](009-project-management-expansion-modules.md#prompt-hand-off-protocol):

1. `docs/features.md` — the delivery-analytics section, written for a reader deciding what the
   product does. Struck through and superseded where it replaces something, never deleted. Phase 3's
   analytics line is the one this closes.
2. Plan 009's status table: M9's row to `done 2026-09-06 @ <short sha>`.
3. Under plan 009's M9 section: the `**Built and unverified:**` list, and **the migration actually
   taken — none** (D-a), which is what the table already reserved, so nothing is struck through.
4. **No module depends on M9**, so there is no dependent prompt to rewrite. Say that in the commit
   message rather than touching one — step 4 of the protocol requires the statement, not the edit.
5. Review row R15 was closed by M8 on 2026-09-06. Do not reopen it. M9 is the row's own final
   evidence: it read the directory, found `V1`–`V12`, and needed none.

```bash
git add docs/features.md docs/plans/implemented/009-project-management-expansion-modules.md
git commit -m "docs(plan): M9 done — the last module, and what is built but unverified"
```

**Acceptance criteria**

- [ ] Charts use the token palette through CSS custom properties and are legible in both colour schemes
- [ ] A too-little-data report renders its explanation, not an empty axis
- [ ] The release burn-up distinguishes scope added after the release was created
- [ ] Cycle time is shown per issue type, and the screen says it is measured from creation
- [ ] The cumulative flow reads from `issue_fact`'s status timestamps, with the fourth band named
- [ ] No velocity chart exists anywhere in the app, asserted by `noVelocity.test.ts` over
      `frontend/src` and `packages/*/src` — D-h's other half
- [ ] build, lint and tests green on shanks

---

## Definition of done

The module is done when all four of the prompt's criteria hold, each checkable by someone who did
not do the work:

1. **A release burn-up distinguishes scope added after the release was created** — `BurnUpReport`
   carries `scopeAddedAfterCreation`, counted with `ReadinessService`'s exact predicate (D-f), the
   total-scope line steps where it arrived, and `BurnUpServiceTest.scopeAddedLateIsSeparate` pins it.
2. **Cycle time is shown per issue type** — one `CycleTimeBand` per type, never one aggregate, and
   the screen says the bounds it measured (D-c).
3. **The cumulative flow reads from `issue_fact`'s status timestamps** — `created_at`,
   `status_entered_at`, `done_at` and `status_kind`, with the unknowable classified into its own
   fourth band rather than guessed (D-d).
4. **No velocity chart exists anywhere** — asserted over the whole tree by two tests rather than
   promised in a comment: `NoVelocityAnywhereTest` over the backend and the contract, and
   `noVelocity.test.ts` over the SPA and the shared packages (D-h).

Plus the module tax plan 009 charges every module: both generated clients regenerated in the contract
commit, factories in both fixture copies, the CLI and MCP verbs with the surface doc, the fault arm
built before the behaviour it proves, `docs/features.md` rewritten, and the gates run **on shanks**.
The permission row of that table is satisfied by **zero keys** (D-o), which is an answer and not an
omission.
