# 002 — Design audit, round 2: **UX / experience depth pass**

**Subject:** the TCMS web UI — one altitude, in depth
**Date:** 2026-08-27 (≈40 minutes after r1)
**Altitude:** `design/ux-review` only — Nielsen's 10 heuristics · flow friction · IA sanity
**Sibling of:** `002-design-audit-r1.md` (untouched except a header correction it now carries)
**Evidence:** `docs/audits/assets/002-design-audit/` — 21 archived captures + `MANIFEST.md`

---

## What r2 is, and is not

**It is not a remediation round.** Nothing has been fixed since r1 was filed. There is no
`Resolved` / `Still open` column in this document and no movement block, because there has been no
movement to report. A later reader who wants "what got fixed" wants **r3**, run in Mode B against
r1's IDs after work has actually landed.

**It is not a re-run of r1 at all.** r1 swept eleven altitudes and necessarily went broad. r2 goes
narrow: one altitude, all ten heuristics walked explicitly, journeys counted in steps, IA tested
against the domain model. Roughly a third of what follows did not appear in r1 in any form.

**It does not restate r1.** Where a finding is already r1's, it is **cited by ID and not repeated**.
Where r1 filed something under a visual-identity or token-system label that is really a usability
failure, it is **reclassified** — with the reasoning — rather than re-filed. Where r1 got a severity
wrong as judged from this altitude, it is **re-rated in both directions**; two findings go *down*.

**It is not a fresh browser session.** No page was driven for r2. Every pixel claim below was
re-read from the archived JPEG named beside it — not recalled — and every claim cites the filename
so a later round can check the work. Source was read only to *corroborate or explain something
already seen on screen*; where source and screen disagree about what a user experiences, **the
screen wins**, and two findings below exist only because that rule was applied.

**Read-only.** Nothing was changed but this file and r1's header.

---

## Evidence, and the correction folded in

| | |
|---|---|
| **Captures** | 21 unique, archived, re-read for this pass |
| **Widths** | **1568 · 1512 · 1440 · 900 · 501** — **five.** r1's header said four; **1512** was missing, and it carries `/`, `/my-work`, `/projects/1/cases` and the first overview shot. Corrected in r1's header this round. |
| **Colour scheme** | Dark only |
| **Tenant** | `Reflex` — 1 project, 1 case, **0 suites**, 2 runs, 2 work items |

**Two notes on the archive.** The manifest's widths come from JPEG headers and are authoritative
over any `resize_window` call in the transcript. And the `/board` and `/help` captures are **900 px**
— which is what r1 already recorded; an intermediate index guessed 1280 and **that guess was wrong,
not r1**. No r1 claim needs revisiting for width. One board finding, however, is visible *only*
because that shot is 900 px, and r1 missed it — **UX14**.

**Corrected count:** r1 says the case repository carries **28** filter controls. Recounted from
`07-project-cases-w1440.jpg`: search + Feature + Type + Level (4) · Status 4 · Automation 4 ·
Priority 4 · Health 11 · Tag field 1 · "Not filed" 1 = **29**. r1's E2/F1/D3 each say 28. The
number is one low; the findings stand.

---

## Coverage — what this evidence cannot answer

Stated before the findings, because a heuristic sweep that quietly infers the missing half is
worse than one that admits it. **Nothing below was inferred from source to fill a gap in the
pixels.**

### Heuristics assessable, partly assessable, and not

| # | Heuristic | Status | What is missing |
|---|---|---|---|
| 1 | Visibility of system status | **Partly** | The **static** half is assessable (you-are-here, counts, timestamps, empty/error states). The **in-flight** half is not: no write was ever performed, so optimistic updates, spinners, skeletons, progress and the ~4 s acknowledgement are **unassessed**. One accidental loading state was seen (`03`) and is filed; one sighting is not a survey. |
| 2 | Match between system and the real world | **Yes** | — |
| 3 | User control and freedom | **Partly** | **Navigational** freedom assessable and assessed. **Transactional** freedom — undo, unsaved-work guards, whether Cancel warns — is **unassessable**: no form was submitted and no destructive control fired. |
| 4 | Consistency and standards | **Yes** | — |
| 5 | Error prevention | **Partly** | Placement, weight and grouping of dangerous controls assessable. Whether a guard **actually fires** is not, for every control except `Delete`, where source (not pixels) shows a confirm dialog — which is why r1's D1 is re-rated *down* below rather than confirmed. |
| 6 | Recognition rather than recall | **Yes** | — |
| 7 | Flexibility and efficiency of use | **Largely not** | Exactly one key was pressed all round (`Escape`, which correctly closed the account menu). Keyboard shortcuts, tab order, bulk actions behind the grid's checkbox column, and URL-restorable filter state are **unassessed**. Only the *absence of a saved-view concept* is assessable, and that is filed as **UX13**. |
| 8 | Aesthetic and minimalist design | **Yes** | — |
| 9 | Help users recognize, diagnose and recover from errors | **Yes** — one real error was encountered | Only **one** error class was seen (a 4xx on a bad path param). 5xx, timeout, 401-recovery and 409-conflict copy exist in source and were **never rendered**; they are not assessed. |
| 10 | Help and documentation | **Yes** | — |

**Four heuristics — #1, #3, #5, #7 — are assessed on one half only.** Any claim that this product
"passes" them is not supported by this evidence.

### Journeys

| Journey | Status |
|---|---|
| Find a case | **Assessed** |
| Read a case | **Assessed** |
| Understand a run's outcome | **Assessed to the list; not past it.** `/runs/:id` was never opened, so drilling from a failed run to the failing case is unassessed — and that is the half that matters |
| Navigate project → suite → case | **Unassessable.** The tenant has **zero suites**. Only the empty state and the breadcrumb's suite slot are assessable |
| Recover from an error | **Assessed** — and it is the worst result in this report |
| Pick up my work | **Assessed** across `02` + `09` |
| Find help | **Assessed** |
| **Record a result** | **Unassessable — the execution screen was never captured.** This is where a tester spends the day |
| Create anything | **Unassessable** — every create path is a write |
| Sign in / accept an invitation | **Unassessable** — never captured |
| Search cases (`/search`) | **Unassessable** — never opened |
| Docs, admin, settings, access, artifacts, ingestion | **Unassessable** |

**No amount of re-reading closes these.** They need a seeded local instance.

---

# 1 · The heuristic sweep

Twenty new findings, IDs `UX1`–`UX20`, permanent and **non-colliding with r1's** `A*`–`J*`.
Each names the heuristic it violates. Where a heuristic is satisfied, that is stated briefly and
the sweep moves on — a clean heuristic is a result.

---

## #1 — Visibility of system status

> **Satisfied, and worth naming:** every page carries breadcrumb + `<h1>` + a subtitle that says
> what the page is for; the active rail row is unambiguous; the grid states `1 case` and
> `1–1 of 1`; the overview stamps `Counted at 2026-08-27 19:31 UTC`; the case editor shows
> **`v1 → v2`** before you type, which tells the user a *new version* is about to exist rather
> than an overwrite (`11-case-edit-w1440.jpg`). That last one is a genuinely good piece of status
> design. **Violated** in one severe way and two small ones.

**UX1 · major · The shell renders a complete, furnished, interactive project context around a
project that does not exist.**
`03-project-1-cases-w1512.jpg`. The URL is `/projects/1/cases`; there is no project `1`. The page
nonetheless renders:

- a breadcrumb reading **`Project / Test cases`** — a generic fallback where every other screen
  names the project (`Reflex / Test cases` in `07`);
- the rail's project selector reading **`Reflex`** — *a different project from the one in the URL*;
- the rail's **`Test cases`** row **highlighted as the active location**;
- `<h1>` **Test cases** and the subtitle *"Everything in this project. Pick a suite to narrow it,
  or filter below"* — asserting a project, its suites and its cases;
- all **29** filter controls, rendered and interactive.

Three of the shell's four you-are-here mechanisms — breadcrumb, selector, active row — are
simultaneously *populated and wrong*, and they disagree with each other. The user is told they are
in Reflex, browsing its test cases, with 29 ways to filter them. The only contradicting evidence is
an error message about JSON.

This is worse than r1's **D4** (which is about the error *copy*) and worse than r1's **E1** (the
missing retry). Those are failures to explain a problem; **this is the shell actively asserting a
false state**, and it is what makes the recovery failure in **UX6** dangerous rather than merely
annoying.
*Heuristic: #1, and #3 by consequence.*
*Fix direction:* a failed project-context load renders the not-found screen, not the project shell
— `design/ux-patterns` → `fe-react`.

**UX2 · minor · "Loading…" and "Couldn't load this" render at the same moment in the same column.**
`03`: a bare, unstyled **`Loading…`** text node sits at y≈450, directly above a styled
`DataRegion` failure panel at y≈490 — while a third copy of the same error already fills the
suites panel to the left. The page says *it is still working* and *it has failed* simultaneously.
The bare text also does not match `DataRegion`'s loading treatment used elsewhere, so at least one
region loads outside the shared state matrix.
*Heuristic: #1.*
*Fix direction:* route the region through `DataRegion` — `design/ux-review` → `fe-react`.

**UX3 · major · The runs list's Status column cannot distinguish a total success from a total
failure.**
`12-project-runs-w1568.jpg`. Two rows:

| Run | Status chip | Result bar | Pass rate |
|---|---|---|---|
| **TR-2** | **`Completed`, green** | **fully red** | **0%** |
| **TR-1** | **`Completed`, green** | fully green | 100% |

The Status chips are **pixel-identical**. The column a reader scans first to answer *"is anything
wrong?"* returns the same green answer for a run where everything passed and a run where
everything failed. The correcting evidence is 400 px to the right, in two columns that r1's **I4**
separately notes are redundant with each other.

r1 filed this as **A1**, a *visual-identity* finding about palette discipline. That is the correct
root cause and the wrong altitude for judging its cost: this is Nielsen #1, it is the most
consequential single defect in the product, and it deserves to be read as a usability failure that
happens to be implemented as a colour choice. **Reclassified and re-prioritised below.**
*Heuristic: #1, and #4 — "Completed" is styled in the palette that means "passed" elsewhere.*
*Fix direction:* lifecycle gets a non-outcome ramp (r1 **B1**/**B3**) — `design/design-tokens` +
`design/charts` → `fe-react`.

---

## #2 — Match between system and the real world

> **Satisfied, and unusually well:** `What this proves` as a panel title instead of "Metadata";
> report jobs that read **Queued / Building / Ready** rather than PENDING/RUNNING/COMPLETED;
> `took 1h 41m`; the runs subtitle *"Every pass over a plan, manual or ingested from CI"*; and a
> help page that opens by defining the domain in the user's terms
> (`20-help-getting-started-w900.jpg`). The vocabulary is the work of someone who thought about the
> reader. **Violated** where the system's internal notion of "absent" is presented as a thing.

**UX4 · major · "Not filed" is rendered as a breadcrumb segment and a link — fabricating a
container the domain model does not have.**
`09-case-detail-w1440.jpg`, breadcrumb: **`Reflex / Test cases / Not filed / TC-2`**, with
`Not filed` **underlined as a link**, in the slot every other case would use for its suite.

Suites are **flat** in this product, and this case has **none**. "Not filed" is not a suite, not a
place, and not a container — it is the *absence* of one. The breadcrumb presents it in the exact
position, styling and affordance as a real container, so the user's mental model acquires a
top-level bucket called "Not filed" that does not exist. That the crumb is *navigable* makes it
worse, not better: following it teaches that the bucket is real.

The same absence is rendered three other ways on the same screens: **italic `Not filed`** in the
grid's Suite column (`08-project-cases-w1440.jpg`), a **completely blank** Suite row in the detail
page's Classification panel (`09`), and the word **"Unfiled"** in the editor's helper text
(`11`). Four renderings, one of which invents a place.
*Heuristic: #2, and #4 — and it is the sharpest instance of r1's **H1**.*
*Fix direction:* the crumb ends at `Test cases` when a case has no suite; one rendering of absent —
`design/ux-patterns` + `design/ux-writing` → `fe-react`.

**UX5 · minor · The work-item vocabulary collides head-on with two existing status vocabularies.**
`02-my-work-w1512.jpg` and `21-project-board-w900.jpg` show work-item columns
**To do · In progress · Blocked · In review · Done**. But **`Blocked`** is already an execution
status (`--color-blocked`, one of the six on every result chip) and **`In review`** is already a
case *lifecycle* status (visible as a filter chip in `07`). Two words carry two meanings each, in a
product that already runs five status vocabularies (r1 **B1**) — this is the sixth.

A user reading "Blocked 0" on My work cannot tell without checking whether it counts blocked
*executions* or blocked *tasks*.
*Heuristic: #2 and #4.*
*Fix direction:* rename the work-item states away from the reserved words — `design/ux-writing` → `fe-react`.

---

## #3 — User control and freedom

> **Assessed on the navigational half only** — see Coverage. **Satisfied there:** breadcrumbs on
> every page, a persistent rail, `Escape` closes the account menu, `Cancel` is present on the
> editor and `Save changes` is correctly disabled until dirty (`11`), which prevents a no-op
> write. **Violated** in one way that the evidence makes unusually concrete.

**UX6 · major · There is no in-app recovery path from a failed page, and the affordances that
look like one silently change project.**
`03`. Inventory of every exit on the failed screen:

| Exit | What it does |
|---|---|
| The toast's **×** | Dismisses the message. The page stays broken. |
| **Retry** | **Does not exist** — though `describeError.ts:22-26` computes a `retryable` flag for exactly this (r1 **E1**) |
| **Back to projects** / any in-page escape | **Does not exist** |
| The rail's `Test cases`, `Overview`, `Test runs`… | Navigate to **Reflex** — the project named in the selector, *not* the project in the URL (**UX1**). The user believes they are recovering *within* their current context; they are being moved to a different one, with no announcement |
| Browser **back** | Works — but it is browser chrome, not the application |

So: **zero in-app recovery paths that preserve the user's intent**, and the most obvious-looking
ones perform a silent context switch. r1's **E1** identified the missing Retry. The deeper finding
is that the *whole* exit inventory is empty, and that **UX1**'s false context turns the remaining
affordances into a trap.
*Heuristic: #3, compounded by #1.*
*Fix direction:* not-found screen with a real exit (**UX1**), plus Retry where `retryable`
(r1 **E1**) — `design/ux-review` → `fe-react`.

---

## #4 — Consistency and standards

> **The heuristic r1 fragmented most.** r1 filed six findings — **H1** (empty values), **H3**
> (panel renamed), **H7** (chips vs text), **C5** (read/write grouping), **C6** (breadcrumb depth),
> **B4** (`EM_DASH`) — under *copy*, *IA* and *tokens*. Every one of them is Nielsen #4. They are
> **not re-filed**; they are reclassified in §4 below. Three consistency failures are new, and they
> are structural rather than cosmetic.

**UX7 · major · The two "browse a project collection" surfaces have incompatible shapes.**
Compare `07-project-cases-w1440.jpg` with `12-project-runs-w1568.jpg` — both are *"list the things
of type X in this project"*:

| | `/cases` | `/runs` |
|---|---|---|
| Filter controls | **29** | **0** |
| Sidebar | Suites panel, ~260 px | none |
| Row density | grid, checkbox column, pagination | plain table, no pagination |
| Reaching row 1 | below the fold at every width | immediately visible |

A user who learns to work the repository learns nothing transferable about runs, and vice versa.
Neither shape is wrong on its own; having both, for the same *kind* of task, is. And the asymmetry
runs backwards: the collection with **two rows** has no filters, the one with **one row** has 29.
*Heuristic: #4, and #7 by consequence.*
*Fix direction:* one collection-page pattern with filters as a progressive layer (**UX13**) —
`design/ux-patterns` → `fe-react`.

**UX8 · minor · Three different empty-region treatments, across four surfaces.**

| Treatment | Where |
|---|---|
| Left-aligned prose **+ a call-to-action button**, inside a bordered panel | Suites panel, `07` |
| **Centre-aligned** prose, no action, in a ~200 px-tall panel | `13-project-reports-w1568.jpg` — both *"No reports yet"* and *"You have not saved any reports"* |
| Bare left-aligned **`Nothing here`**, no panel, no action | My work's four empty columns, `02` |

`02` renders the third variant **four times side by side**, so the emptiest screen uses the
tersest treatment and fills ~75% of the viewport with it.
*Heuristic: #4.*
*Fix direction:* one `<EmptyState>` with optional action — `design/ux-patterns` → `fe-react`.

**UX9 · nit · The rail and the page disagree on the name of the same destination.**
`01-root-w1512.jpg`: the active rail row reads **`All projects`**; the `<h1>` of the page it
just loaded reads **`Projects`**.
*Heuristic: #4.*

---

## #5 — Error prevention

> **Assessed on placement and weight only** — whether a guard fires is unassessable from pixels
> (Coverage). This is where r2 **corrects r1 downward.** r1's **D1** rated `Delete`'s placement
> *major* while explicitly declining to open the dialog. Source read this round settles it:
> `CaseDeleteDialog.tsx:69` is a real confirm step, and `:77` records that the copy deliberately
> avoids *"this cannot be undone"* **because the delete is soft and past runs keep their results**.
> A reversible action behind a confirm, thoughtfully worded, is not a major error-prevention
> failure. **D1 is re-rated to minor.** The placement finding survives in a narrower form:

**UX10 · minor · The case-detail header packs four classes of action into one undifferentiated row.**
`09`, header right, left to right:

| Control | Class | Treatment |
|---|---|---|
| `Execution history` | navigation | icon + text |
| `Status ⌄` | **write** (lifecycle change) | icon + text |
| `Clone` | **write** (creates an entity) | icon + text |
| `Edit case` | navigation | **filled primary** |
| `Delete` | **destructive** | icon + text, **red** |

Five controls, four behavioural classes, **three** visual treatments — and the mapping between them
carries no information. Two writes are styled identically to a read-only link; the one *navigation*
action gets the primary fill; the destructive one is distinguished only by hue, which is also the
single reddest element on the page and therefore the first thing the eye lands on.
*Heuristic: #5, and #4.*
*Fix direction:* separate navigation from writes from destructive; `Delete` and `Clone` into an
overflow — `design/ux-review` → `fe-react`.

> **Still open and still major: r1's D2** (`Archive project` beside `Browse test cases` on
> `04/05-project-w*.jpg`). Unlike `Delete`, **no confirm step was evidenced** for it in pixels or
> source this round. It keeps its severity pending verification, and that verification is a
> round-3 item.

---

## #6 — Recognition rather than recall

> **Satisfied in one respect worth crediting:** the `Identifier` treatment — monospace, tabular
> figures — makes `TC-2` and `TC-402` distinguishable at a glance and aligns keys down a column
> (`07`, `08`, `12`). That is a real recognition win, deliberately designed. **Violated** by a
> promise the product makes and does not keep.

**UX11 · major · "My work" promises executions to record, and dead-ends at a page that cannot
record one.**
Chain, evidenced across two captures:

1. `02-my-work-w1512.jpg` — the page subtitle: *"Everything assigned to you, across every project
   you can see — **executions to record**, reviews you have been asked for, and your own tasks."*
2. The single card's **only link** is the underlined key **`TC-2`**.
3. `09-case-detail-w1440.jpg` — where that link goes. Its complete action inventory is
   `Execution history` (read-only), `Status ⌄` (lifecycle, not result), `Clone`, `Edit case`,
   `Delete`. **There is no control that records an execution result.** The right rail's
   `Last result` panel says *"Never executed."* and offers nothing.

So the product's daily-loop entry point advertises "executions to record" and routes to a screen
that cannot record one. The user must recall — unaided — that results are recorded inside a *run*,
navigate to `Test runs`, find or create the right run, and locate the case inside it.

**Scope, stated honestly:** the card's four `→ status` buttons were never pressed, and the
execution screen was never captured, so *the path that does work* is unassessed. The finding is the
**promise/delivery mismatch between two screens that were both captured** — not a claim that
recording a result is impossible.
*Heuristic: #6, and #2 — the page names a task it does not lead to.*
*Fix direction:* an assigned execution links to its execution, not to the case —
`design/ux-review` → `fe-react`.

**UX12 · minor · One work item renders identically on two surfaces with no stated relationship.**
`02` (My work, `/my-work`) and `21` (Board, `/projects/:id/board`) render **the same TC-2 card** —
same `Maintenance` + `linked` chips, same health line, same `Medium · Aleksandar Kotrulev`, same
four `→` buttons. The two pages describe themselves differently (My work: *"across every project"*;
Board: *"Tasks alongside the work the repository already knows about"*) and Board adds an
`Everyone / Assigned to me` toggle, but nothing on either screen says the two are views of one
queue. A user who acts on the card in one place has no way to know it is the same item they saw in
the other.
*Heuristic: #6 and #4.*
*Fix direction:* state the relationship, or make Board the project-scoped filter of My work
explicitly — `design/ux-patterns` → `fe-react`.

---

## #7 — Flexibility and efficiency of use

> **Largely unassessable** — one key pressed all round (Coverage). What *is* assessable is
> structural, and it answers the coordinator's question about r1's E2/F1 directly.

**UX13 · major · The surface with 29 filters has no saved views; the surface that needs them least
has them.**
`07-project-cases-w1440.jpg`: 29 filter controls, no mechanism to name or recall a combination —
confirmed in source (`features/cases/` contains `CaseFilterBar.tsx` and `useCaseFilters.ts`;
searching the whole feature for `saved view` / `savedFilter` / `savedSearch` returns **nothing**).
`13-project-reports-w1568.jpg`: an entire panel titled **`My saved reports`**, with copy explaining
that *"A saved report keeps its settings so the same one can be regenerated next release."*

The product **has** the saved-definition pattern, understands it well enough to write good copy
about it, and applied it to reports rather than to the repository. Every visit to `/cases`
therefore costs a re-specification of the same filter combination from 29 controls.

**Is r1's E2/F1 an IA failure or a progressive-disclosure failure? Both, in a specific order.** At
the surface it is **progressive disclosure**: nothing is misfiled, it is simply all shown at once,
and collapsing the panel would fix the immediate density problem (r1 **E2**). Underneath it is an
**IA gap**: there is no concept of *"a view of the repository"* in the information architecture, so
the filter bar is the only vocabulary the user has for "the cases I care about", and it cannot be
named, saved, shared or returned to. Collapsing the panel without adding the concept just hides the
problem behind one more click. **Fix the IA gap and the disclosure problem largely dissolves** —
the default becomes a named view, and the 29 controls become the thing you open to *build* one.
*Heuristic: #7, and #8 by consequence.*
*Fix direction:* saved views on the repository, then collapse the filter bar behind them —
`design/ux-patterns` → `fe-react`. **Supersedes the fix direction on r1 E2.**

---

## #8 — Aesthetic and minimalist design

> **An important distinction for this product:** the visual identity is genuinely restrained (r1
> §A said so, and the captures bear it out — no ornament, no gradients, hairline borders, one
> accent). The minimalism failures here are **density of controls**, not decoration, so the usual
> remedy — remove ornament — does not apply. There is nothing decorative to remove; there are
> too many live controls per unit of information.

**UX14 · major · At 900 px the board shows 2 of its 5 columns, with nothing indicating the other
3 exist.**
`21-project-board-w900.jpg` renders exactly two columns — **`To do 1`** and **`In progress 1`** —
occupying the full content width and clipped at the right edge. The work-item model has **five**
states, evidenced on the very same card: its `→` buttons offer `To do`, `Blocked`, `In review` and
`Done`, and `02` renders all five columns at 1512 px.

So at 900 px: three columns are off-screen, **no horizontal scrollbar is visible**, no pagination,
no column-count indicator, no "3 more" affordance. A user at this width has no way to learn that
`Blocked`, `In review` and `Done` columns exist — and a card can still be *sent* to them by a `→`
button, at which point it vanishes to a place the user cannot see.

**This is new, and r1 missed it** — precisely because r1 read this shot as a board-styling capture
and did not count columns against the state model. It is the one finding the 900 px width makes
visible.
*Heuristic: #8, #1 and #3.*
*Fix direction:* horizontal scroll with a visible affordance, or collapse to a single-column
grouped list below `lg` — `design/ux-review` → `fe-react`.

**UX15 · minor · The projects list spends a full-width card on two facts.**
`01-root-w1512.jpg`: a ~1,450 × 100 px bordered card conveys `RF`, `Reflex`, and `1 cases`. At ten
projects this is ten screens of scrolling for a list that is a table. It also renders the
pluralisation bug (r1 **G2**) at maximum size, in the first thing a user sees after signing in.
*Heuristic: #8.*
*Fix direction:* a dense list or table with the card reserved for a genuine dashboard —
`design/ux-patterns` → `fe-react`.

---

## #9 — Help users recognize, diagnose and recover from errors

> **The heuristic r1 spread thinnest** — r1's **D4** (developer copy), **H4** (copy rule),
> **G1** (toast title disagreement) and **E1** (no retry) are four labels on one heuristic
> failure, seen in one screenshot. They are reclassified below, not re-filed. Two things in `03`
> are new. **Note the assessment is on one error class only** (Coverage) — 5xx, timeout, 401 and
> 409 copy exists in source and was never rendered.

**UX16 · major · One bad URL produces three simultaneous error surfaces and a reported count of six.**
`03-project-1-cases-w1512.jpg` shows, at one moment:

1. an inline `DataRegion` failure **inside the Suites panel** (left);
2. an inline `DataRegion` failure **in the main content region** (below the filters);
3. a **toast** (top right), which additionally reports **`Occurred 6 times`**.

Same sentence, three placements, plus a multiplier. The user must work out that this is **one**
broken thing, not three or six. The notification layer's grouping logic — which
`describeError.ts` documents as existing so that *"six regions failing on one 503 collapse into a
single message"* — did its job **within** the toast and then the toast was shown *alongside* the
two inline copies it was meant to replace. The de-duplication is real; it is applied one layer too
low.
*Heuristic: #9.*
*Fix direction:* when a page-level context load fails, one surface owns the message —
`design/ux-review` → `fe-react`.

**UX17 · minor · The diagnose affordance that exists did not appear on the one error a user hit.**
`NotificationsProvider.tsx` renders a **`Reference`** correlation ID with a copy button — a
genuinely good affordance for a self-hosted product where the user and the operator may be the same
person reading server logs. In `03` the toast shows title, body, `Occurred 6 times` and a close
button — **no Reference row**, because the failure carried no correlation ID. So the one error
actually encountered this round is also the one a user could not report precisely.
*Heuristic: #9.*
*Fix direction:* ensure 4xx responses carry a correlation ID, or show a client-side reference —
`design/ux-review` → `fe-react` + backend.

---

## #10 — Help and documentation

> **The best-served heuristic in the product, and it should be said plainly:** an in-product user
> guide of **11 sections** (`sections.tsx`), reachable without leaving the app, opening with a
> domain model in the user's language and a role matrix read live from the server rather than
> transcribed (`20-help-getting-started-w900.jpg`). Most products of this size ship nothing.
> **Violated** only in the connective tissue.

**UX18 · minor · Health signals are documented twice, and neither is reachable from where the
question arises.**
This **corrects r1's D3**, which rated the eleven Health signals *major* on the grounds that they
were "eleven riddles" with "no tooltip… and no Help section". Both halves were too strong:

- `components/StatusChip.tsx:162` defines `healthHelp: Record<HealthSignal, string>` — a real,
  carefully-revised explanation for **every one** of the eleven — and `:291` renders it in a
  `<Tooltip>`. The comment at `:157-160` states the intent: *"The tooltip carries the explanation
  because the label alone can't say why it matters."*
- The guide **does** cover them, as a subsection **"Keeping cases healthy"** inside *Writing test
  cases* (`sections.tsx:299`), with further mentions at `:268`, `:322`, `:480`.

What is true is narrower. The tooltip lives on the **HealthChip** — the coloured chips on
`09`'s case detail. The **filter chips** on `07` are a different rendering
(`CaseFilterBar.tsx:48` maps `healthLabels` into plain `<Chip>`s with no `Tooltip` in that file),
and **whether they carry any explanation was never verified — no chip was hovered all round.**
Meanwhile the guide's subsection is buried under a section titled *Writing test cases*, which is
not where a user filtering a repository would look, and no help affordance on the filter bar points
to it.

So: **the explanation exists twice and is discoverable from neither the filter bar nor the
contents list.** That is a minor connective failure, not a major documentation gap.
*Heuristic: #10.*
*Fix direction:* carry `healthHelp` onto the filter chips; promote "Health signals" to a
top-level guide section — `design/ux-writing` → `fe-react`. **r1's D3 is re-rated major → minor.**

---

# 2 · Flow friction, measured

Counted from the captures. **Steps = user actions**, not page loads.

### Journey A — "find a case" · **assessed**

| Path | Steps | Notes |
|---|---|---|
| Returning user (project already selected in the rail) | **1 click** (rail `Test cases`) + **1 scroll** | Efficient. The persistent rail is doing real work here — credit it |
| Cold, via the projects list | **3 clicks** — card (`01`) → overview (`05`) → `Browse test cases` | The **overview is an obligatory interstitial**: clicking a project card lands on a dashboard, not on the work |
| Then, to the first case row | **+1 scroll at every width** | `07`: the grid *header* sits at y≈577 in a 606 px viewport — the first data row is **below the fold**. At 501 px (`14`/`15`) it is **~10 scroll ticks** down |

**Dead ends:** none. **Backtracking:** the overview interstitial. **Re-derived context:** the 29
filter controls, re-specified every visit (**UX13**).

### Journey B — "read a case" · **assessed**

**1 click** from the grid. Friction on arrival (`09`), all of it comprehension cost:

- `TC-2` printed **twice** on screen (breadcrumb, and the identifier line under the `<h1>`);
- **four** renderings of "absent" visible at once — `Not set` ×3, `—`, **blank** (Suite), and the
  sentence *"Never executed."* (r1 **H1**, sharpened by **UX4**);
- the Suite row is **blank** while the breadcrumb four lines above says **`Not filed`**;
- five right-rail panels the reader must learn the contents of — and the editor renames one of them
  (r1 **C5**).

**Dead ends:** none. **Re-derived context:** field locations, on every switch between reading and
editing.

### Journey C — "understand a run's outcome" · **assessed to the list only**

**1 click** to `/runs`. Then it stops, for two different reasons:

1. **The list actively misleads** — `12`: identical green `Completed` chips on a 0% run and a 100%
   run (**UX3**). The scan-read is wrong before any drill-down begins.
2. **The drill-down is unassessed** — `/runs/:id` was never opened. Getting from *"TR-2 failed"* to
   *"this case failed, here is why"* is the half that matters, and this evidence cannot speak to it.

**Redundancy observed:** `Result` (a bar, unlabelled colours) and `Pass rate` (a number) are
adjacent columns answering one question (r1 **I4**).

### Journey D — "project → suite → case" · **unassessable**

The tenant has **zero suites** (`07`). Only two things are assessable:

- The suites panel is a **flat list** with an `All cases` row and no tree — **the IA correctly
  honours the flat-suite domain model.** Credit it.
- The empty state misinforms: *"A case needs a suite to live in, so this is the first thing to
  make"* — contradicted on the same screen by the `Not filed` filter chip, the grid's italic
  `Not filed` value, and the editor's *"Unfiled. It still appears in the grid…"* (r1 **H5**).

**Real suite navigation, filtering by suite, and the suite→case path remain unassessed.**

### Journey E — "recover from an error" · **assessed — the worst result in this report**

| Measure | Result |
|---|---|
| In-app recovery paths | **0** |
| Paths that *look* like recovery but silently switch project | **8** (every rail project link) |
| Retry control | absent, though `retryable` is computed (r1 **E1**) |
| Error surfaces for one fault | **3**, plus `Occurred 6 times` (**UX16**) |
| Correct information about where the user is | **0 of 3** you-are-here mechanisms (**UX1**) |
| Working exit | browser **back** — outside the application |

**Steps-to-goal for "get back to something that works": undefined**, because no in-app path exists.
This is r1's D4/H4/E1 seed grown into its full shape: the copy failure (D4) is the *least* serious
part.

### Journey F — "pick up my work" · **assessed**

`02` → card → `TC-2` → `09`. The page promises **"executions to record"**; the only link reaches a
screen with no control that records one (**UX11**). Four columns of the five are empty and say
`Nothing here` (**UX8**), so ~75% of the viewport is spent on absence.

### Journey G — "find help" · **assessed**

Reachable **only** from the footer link labelled `Documentation` or the account menu labelled
`Help & documentation` — and `Documentation` **already names a different destination** in the rail
(r1 **C1**). Once there, no rail row is active (r1 **C7**): the user is somewhere the shell has no
place for. The guide itself is good.

---

# 3 · Information architecture

### Does the navigation model match the domain model?

**Domain:** Instance → **Company** → **Project** → **suites (flat)**.
**Navigation** (`AppShell.tsx`, confirmed in `01`, `07`, `20`):
global links → **Project** selector → **`Repository`** → **`Execution`** → *(conditionally
`Authoring`, `Project`)* → **`Company`**.

| Domain fact | Navigation | Verdict |
|---|---|---|
| Suites are **flat** | Flat list, `All cases` + siblings, no TreeView (`07`) | ✅ **Matches** |
| Project is a scope | Selector + scoped groups | ✅ **Matches** |
| Company is the **isolation boundary** | A nav *group* only — see **UX20** | ❌ |
| Company contains projects | Company group sits **last**, below the fold | ❌ r1 **C3** |
| A case may have **no suite** | Breadcrumb fabricates a `Not filed` container | ❌ **UX4** |

### Where the IA leaks implementation into the user's mental model

**UX19 · major · The rail's two primary group headings are the backend's module names.**
`AppShell.tsx:201` `heading: 'Repository'` · `:209` `heading: 'Execution'` — rendered as
`REPOSITORY` and `EXECUTION` in every capture (`01`, `07`, `09`, `12`, `21`).

`CLAUDE.md` lists the Spring Modulith modules: *tenancy · identity · project · storage ·
**repository** · **execution** · documentation · reporting · work · shared*. **The two user-facing
navigation groups are two backend module names**, and two more headings (`Project`,
`Authoring`/documentation) shadow others in the same list.

This is textbook implementation leaking into IA. A tester does not have "a repository" and "an
execution" — they have *the tests I write* and *the tests I run*. The words are not wrong so much
as **not the user's**: `Repository` is a version-control term to most engineers, and it here
contains `Overview`, `Test cases` and `Documentation`, of which only one is a repository of
anything. The product's *own help page* frames the domain in better language
(`20`: "How the pieces nest"), so the vocabulary already exists one click away.

That the server module boundary and the navigation grouping coincide is convenient for the team and
invisible to the user as a *rationale* — they see two nouns that do not describe their day.
*Fix direction:* name the groups for the user's task — e.g. *Authoring* / *Running tests* —
`design/ux-patterns` → `fe-react`.

**UX20 · major · Company is a navigation group but never a place.**
Checked across all 21 captures: **the company's name appears nowhere in the UI.** Not in the header
(which shows `TCMS` and the user), not in any breadcrumb (every one starts at the **project** —
`Reflex / Test cases`, `01`/`07`/`09`/`12`/`21`), not on the projects list (`01`: `Projects` /
*"Pick a project to work in."*). There is a `Company` **group** in the rail holding
`Org chart`, `Teams & roles`, `API tokens`, `Single sign-on`, `Audit trail` — five *settings*
destinations — but **no company page, no company crumb, no company identity**.

Meanwhile the help page teaches Company **first**, as the concept everything else hangs from
(`20`): *"Company — Your organisation. It is the isolation boundary — nothing you do is visible to
another company on this instance."*

So the product's most important structural concept — the tenancy boundary this entire codebase is
organised around — is **taught as primary in the documentation and rendered as a settings folder in
the shell.** For a self-hosted multi-company instance, a user who cannot see which company they are
operating in cannot verify the isolation guarantee they were just promised.
*Fix direction:* surface the company in the header or as the breadcrumb root —
`design/ux-patterns` → `fe-react`. **Compounds r1 C3.**

### Progressive disclosure vs IA — the verdict on r1's E2/F1

**Both, in a definite order: a progressive-disclosure failure at the surface, caused by an IA gap
underneath.** Full reasoning at **UX13**; the short form is that collapsing the filter panel treats
the symptom, and adding *saved views* to the repository's IA removes the reason the panel is
permanent. **Fix the IA first.**

---

# 4 · Relation to r1 — reclassified, re-rated, corrected

**No r1 finding is re-filed.** This table is the whole of r2's commentary on r1.

### Reclassified — right finding, wrong altitude for judging its cost

| r1 ID | r1 filed it as | Really | Why it matters |
|---|---|---|---|
| **A1** | Visual identity — palette discipline | **Nielsen #1 + #4** (→ **UX3**) | Filed as a colour choice, it reads as polish. Filed as a status-visibility failure, it is the most consequential defect in the product |
| **B1 · B3 · I1** | Token system / charts | **Nielsen #4** | Consistency failures whose *mechanism* is tokens. The token fix is right; the severity should be read from the confusion, not the palette |
| **H1** | Copy voice | **Nielsen #4 + #6** | Four renderings of "absent" is a recall tax, not a wording preference |
| **C5** | IA | **Nielsen #4 + #6** | Breaks knowledge transfer between the two most-used views of the core entity |
| **H3 · H7 · B4** | Copy / tokens | **Nielsen #4** | Three more instances of the same heuristic |
| **D4 · H4 · G1 · E1** | Copy · copy · code · flow | **Nielsen #9, all four** | One heuristic failure, one screenshot, four labels. r2 files the two things they missed: **UX16**, **UX17** |
| **E2 · F1** | Flow / responsiveness | **#7 + #8, over an IA gap** | See **UX13** |

### Re-rated

| r1 ID | r1 | r2 | Reason |
|---|---|---|---|
| **D3** — eleven Health signals | **major** | **minor** → **UX18** | **r1 overreached.** `healthHelp` (`StatusChip.tsx:162`) explains all eleven in tooltips; the guide covers them at `sections.tsx:299`. r1 inferred "no help" from "no tooltip appeared in my captures" — but **no chip was ever hovered**. The real finding is narrower: help exists twice and is reachable from neither the filter bar nor the contents list |
| **D1** — `Delete` red, top-level | **major** | **minor** → **UX10** | **r1 overreached, honestly.** r1 scoped itself to placement and declined to open the dialog — correct discipline. Source now shows `CaseDeleteDialog.tsx:69` is a real confirm and `:77` records the delete is **soft**, deliberately not claiming irreversibility. Reversible + confirmed ≠ major |
| **C6** — breadcrumb depth | **minor** | **major** | Not one defect but the third of **three independent breadcrumb failures**: depth changes between siblings (**C6**), a container is fabricated (**UX4**), and a false project is asserted (**UX1**). The breadcrumb is unreliable as a you-are-here mechanism in three distinct ways — that is a system failure, not a nit |
| **A1** | major | **major, and #1 by leverage** | Severity unchanged; priority raised from r1's #4. See **UX3** |
| **D2** — `Archive project` | major | **major, held** | Deliberately *not* lowered alongside D1: no confirm step was evidenced for it in pixels or source. Verify in r3 |

### Factually corrected

| Claim | r1 | Corrected |
|---|---|---|
| Widths captured | "4 widths" | **5** — 1512 omitted. Fixed in r1's header this round |
| Filter controls on `/cases` | "28" | **29** (recounted from `07`) — affects the prose of E2/F1/D3 only |
| `/board`, `/help` widths | 900 | **900 — r1 was right**; an intermediate index guessed 1280 and was wrong |

---

# 5 · Priority — UX altitude only

Re-ordered for *user-task impact*, and it differs from r1's list because r1 ranked across eleven
altitudes.

| # | Item | Sev | Why here |
|---|---|---|---|
| 1 | **UX3** / r1 **A1** — green `Completed` on a 0% run | major | The primary scan-read of the runs list is **wrong**. Highest-consequence defect in the product; r1 ranked it 4th by bundling it with token work. Rank it first, fix it via the token work |
| 2 | **UX1 + UX6** — false project context, zero recovery paths | major | The shell asserts a project that does not exist, then offers eight exits that silently switch project. Fix together; r1's **E1** (Retry) is a sub-item |
| 3 | **UX11** — My work promises executions, delivers a case page | major | Breaks the daily loop at its entry point |
| 4 | **UX19 + UX20** — backend module names as nav groups; Company invisible | major | Two edits to the nav map and one header element. Cheapest structural IA win available |
| 5 | **UX13** — saved views on the repository | major | Removes the *reason* the 29-control panel is permanent. Supersedes r1 E2's fix direction |
| 6 | **UX14** — board shows 2 of 5 columns at 900 px | major | Cards can be sent to columns the user cannot see |
| 7 | **UX4** + r1 **C6** — breadcrumb integrity (3 defects) | major | You-are-here is unreliable three ways; fix as one job |
| 8 | **UX16 + UX17** — one fault, three surfaces, no reference | major | Comprehension cost on every error |
| 9 | **UX7** — two incompatible collection-page shapes | major | Structural; do it when saved views land (#5) |
| 10 | r1 **D2** — `Archive project` placement | major | **Verify the confirm first**, then rank |
| 11 | **UX5 · UX10 · UX12 · UX15 · UX18 · UX8** | minor | Batch |
| 12 | **UX2 · UX9** | minor / nit | Opportunistic |

---

# 6 · Counts

| | |
|---|---|
| **New in r2** | **20** — `UX1`–`UX20` |
| **Blocker** | **0** |
| **Major** | **11** — UX1 · UX3 · UX4 · UX6 · UX7 · UX11 · UX13 · UX14 · UX16 · UX19 · UX20 |
| **Minor** | **8** — UX2 · UX5 · UX8 · UX10 · UX12 · UX15 · UX17 · UX18 |
| **Nit** | **1** — UX9 |
| **r1 findings reclassified** | **13** (across 7 rows) |
| **r1 findings re-rated** | **4** — 2 **down** (D3, D1), 1 **up** (C6), 1 re-prioritised (A1) |
| **r1 facts corrected** | **3** (widths, control count, and one correction that was not needed) |
| **Heuristics fully assessed** | **6 of 10** — #2 #4 #6 #8 #9 #10 |
| **Heuristics half-assessed** | **4** — #1 #3 #5 #7 |
| **Journeys assessed** | **6** · **partly 1** · **unassessable 6** |

*(11 + 8 + 1 = 20. **UX10** and **UX18** are the two carried down from r1's D1 and D3 and are
filed here at their corrected minor severity, which is why r1's major count does not simply grow.)*

**Still no blockers.** Nothing found across both rounds stops a user completing a task they can
reach or destroys their data. The product is usable and thoughtfully built; the failures are
**structural consistency** and **one broken promise** in the daily loop.

---

# 7 · What round 3 must do

**r3 is the first round that can be Mode B**, because it is the first that can compare against
remediation. It needs what neither r1 nor r2 had:

1. **A seeded local instance** (`docker compose up` + `provision-company`), driven by **Playwright**.
   Every constraint that shaped both rounds — no writes, no destructive controls, no sign-out, no
   theme toggle, no hovering a chip that might fire something — is a consequence of driving a real
   session against real data, and all of it disappears locally.
2. **The execution screen.** Unassessed twice now. It closes r1 **E5**, settles **UX11**, and is the
   only way to assess the in-flight half of heuristic **#1**.
3. **Data that exercises the IA:** ≥3 suites, ≥3 projects, ≥20 cases, a failed run with several
   failing cases. Zero suites made journey **D** unassessable; one case made every density judgement
   a lower bound.
4. **The four half-assessed heuristics** — #1 in-flight, #3 transactional, #5 whether guards fire,
   #7 keyboard and bulk. Specifically: **hover a Health filter chip** (settles **UX18**), **open
   `Archive project`** (settles r1 **D2**), **press Cancel on a dirty editor** (settles r1 **E4**).
5. **Light mode and the auth screens** — the first-run experience is entirely unaudited across both
   rounds.

**Carry forward unchanged:** r1's IDs `A*`–`J*` and r2's `UX*` are permanent. r3 renumbers nothing,
and marks nothing `Resolved` without a fresh trace.

---

*Round 2 of `docs/audits/002-design-audit-r1.md`. Depth pass at the UX altitude; not a remediation
round. Read-only — nothing was changed but this file and r1's evidence header.*
