# 002 — Design audit, round 3: **the write journeys**

**Subject:** the TCMS web UI — the flows r1 and r2 could not reach
**Date:** 2026-08-28
**Altitude:** UX / experience, continued from r2 — plus the four heuristics that needed a write in flight
**Siblings:** `002-design-audit-r1.md` (all altitudes) · `002-design-audit-r2-ux.md` (UX depth, read-only)
**Evidence:** `docs/audits/assets/003-journeys/` — 24 captures + `MANIFEST.md`

---

## What r3 is, and is not

**It is the first round with write access.** r1 and r2 were read-only against a production-shaped
instance; that restriction came from the coordinator and the user has now lifted it. Every journey
r2 marked *unassessable for want of a write* was driven for real: a suite, a case, a run, and a
recorded test result.

**It is still not a remediation round.** Nothing from r1 or r2 has been fixed. There is no
`Resolved` column. r3 adds new findings (`J1`–`J17`) and **corrects earlier rounds in both
directions** — including two places where r2 was wrong and one where r1 was wrong.

**The headline is not what I expected.** Going in, the open question was whether the write paths
would be as weak as the error path r1 found. They are not. **The execution screen — the one screen
this audit had never seen, and the one a tester lives in — is the best-designed screen in the
product**, and the case editor's unsaved-work handling is better than the confirm dialog I was
looking for. The real damage is concentrated in three places: a first-run flow that contradicts
itself, a silent success model, and one straightforwardly broken screen.

**Read-only on code.** Nothing in the repository was changed but this file and the asset folder.

---

## Boundaries honoured

| Boundary | What I did |
|---|---|
| Create, don't destroy | Created 4 records. **Deleted nothing, archived nothing, and did not touch a single pre-existing record.** TC-2, TR-1, TR-2, TP-2, the existing documentation page and the real user rows in `/admin/teams` are exactly as found. |
| Name everything findable | Every record is prefixed **`[design-audit]`**. Full inventory at the end. |
| Don't sign out | Did not. **Sign-in / accept-invitation remains unassessable** and is still marked so. |
| Don't manufacture faults | `tcmsFaults` never touched. The only 4xx exercised were genuine empty-form submits. |

**One boundary I set myself:** I did not press **Complete run** or **Abort run** on TR-3, and did
not publish or archive the documentation page. Those are state transitions whose *design* I could
read from the disabled/enabled treatment without firing them, so firing them bought nothing.

---

## Coverage — closed, and still open

### Heuristics r2 could only half-assess

| # | r2 status | r3 status |
|---|---|---|
| **1** Visibility of system status | static half only | **Closed.** In-flight and post-write behaviour observed on 4 writes. → **J6**, **J15** |
| **3** User control and freedom | navigational half only | **Closed.** → **J7** (and a correction: r1 **E4** resolves as *satisfied*) |
| **5** Error prevention | "does a guard fire?" unknown | **Closed.** Guards fire, and they are good. → **J4**, **J11** |
| **7** Flexibility and efficiency | "largely unassessable" | **Closed, and it is a strength.** → **J13** |
| **9** Error recovery | one 4xx only | **Partly closed.** Two validation 4xx exercised. 5xx, timeout, 401, 409 **still never rendered.** |

### Journeys

| Journey | r2 | r3 |
|---|---|---|
| Create a suite | unassessable | **Driven** → J1, J2 |
| Create a case + validation | unassessable | **Driven** → J3, J4, J5 |
| Create a run | unassessable | **Driven** → J7, J8 |
| Run detail `/runs/:id` | never opened | **Driven** → J9 (largely positive) |
| **Record a result** | never opened | **Driven** → J10–J15 |
| project → suite → case | unassessable (0 suites) | **Driven** → J1, J2 |
| Edit & save, unsaved-work path | unassessable | **Driven** → J7, and r1 E4 corrected |
| `/search` | never opened | **Driven** → **J16**, the round's clearest defect |
| docs, admin | never opened | **Driven** → J17 |
| Sign in / accept invitation | unassessable | **Still unassessable** — would require signing out |
| Light mode | unassessable | **Still unassessable** — would rewrite the user's persisted preference |
| artifacts, ingestion, settings/*, plans, org-chart | unassessable | **Still unreached** — time-boxed |
| 5xx / timeout / 401 / 409 error copy | unassessable | **Still unassessable without fault injection**, which is dev-only |

---

# Findings

Seventeen new findings, **`J1`–`J17`**, permanent. r1's section J is *iconography* (`J1`–`J4`);
r3's prefix is `J` for *journey*. Disambiguate by round — *r1 J1* vs *r3 J1*.

> **A note on what is *not* here.** Six candidate findings were dropped after checking. Each is
> recorded in **Not filed, and why** below, because a later round will meet the same temptations.

---

## The first-run flow contradicts itself

**J1 · major · A brand-new empty suite renders the *no-results* state, and offers a button that
undoes the suite.**
`05-empty-suite-no-cases-match-w1097.jpg`. I created suite **TS-3**, the app auto-selected it —
good — and the case region then said:

> **No cases match**
> Nothing in this project matches the current filters.
> **[ Clear filters ]**

Three things are wrong at once. The suite is not *filtered*, it is **new and empty**, and those are
different states with different remedies. The copy blames the user's filters for a condition the
user did not cause. And the only offered action, **Clear filters**, deselects the suite they just
made — the button undoes the work that produced the screen.

What the moment needs is the one thing absent: a route into any of the three membership arms —
file a case here, add an existing case, or set a tag rule. The product explains those arms in five
places (**J2**) and offers none of them at the only moment they are actionable.
*Heuristic: #1, #2, #9.*
*Fix direction:* an empty-suite state distinct from no-results, carrying the three arms —
`design/ux-patterns` + `design/ux-writing` → `fe-react`.

**J2 · Conforms — and the premise behind the question was wrong.**
r3 was asked whether the UI teaches the three membership arms *(filed ∪ added ∪ tagged)* or assumes
the spec has been read. **It teaches them, in five separate places**, in the user's language:

| Where | Words |
|---|---|
| New-suite dialog (`02`) | "Conditions are inherited by the cases **filed** in this suite. A case added individually, or matched by a tag rule, does not pick them up." |
| Tag-rules helper (`02`) | "Any case carrying ANY of these belongs to this suite, wherever it is filed." |
| Cases page subtitle (`04`) | "Scoped to [design-audit] Deposits — cases filed here, added here, or carrying one of its tags." |
| Suite header count (`04`, `10`) | "0 / 1 case **filed, added or tagged** here" |
| New-run dialog (`12`) | "A suite's whole membership — filed, added, and claimed by its tags." |

The count phrasing is the neatest of these: it restates the model every time it states a number.
**No finding.** The gap is not teaching, it is **J1** — the one screen where the concept becomes
actionable offers no action.

**J3 · major · The case created from inside a suite defaults to *Unfiled*.**
Captured across `04` → `06`. Standing in the suite-scoped view of TS-3 (`?suiteId=…`), I pressed
**New case**. The Placement panel's Suite field was **empty**, helper reading *"Unfiled."*

So the app's own first-run path — create a suite, look at it, add a case — produces a case that
does **not** appear in the suite the user is looking at. I had to set the suite by hand. Combined
with **J1**, a new user's first two minutes are: make a suite → be told their filters are wrong →
add a case → find it isn't there.
*Heuristic: #3 (the system discards context the user established), #7.*
*Fix direction:* pre-fill Suite from the active `suiteId` — `design/ux-patterns` → `fe-react`.

---

## Validation and error prevention — better than r1 and r2 assumed

**J4 · Conforms · Client-side validation is prompt, well-worded, and moves focus.**
Two empty submits driven:

- New suite (`03`): field outlined red, label reddened, inline **"Enter a name."**
- New case (`07`): same treatment, **"Enter a title."**, and **focus moved into the field** (caret
  visible at `x≈397`).

No network round-trip, no toast, no modal. The copy is an instruction, not a complaint — not "This
field is required". This is heuristic **#5** done properly, and it is worth recording as a strength
because r1 and r2 could only say "a guard exists in source".

**J5 · minor · A multi-error form reports its errors a screen apart, with no summary.**
The empty case submit produced **two** errors: **"Enter a title."** at the top (`07`) and
**"Add at least one step, or switch to Gherkin."** in a red panel far below the fold. Only the
first was visible. There is no error summary at the page head and no count, so the user fixes the
title, presses Create again, and meets a second error they had no way to anticipate.

The two are also presented differently — an inline field message versus a full-width alert panel —
so one form uses two error idioms.
*Heuristic: #9, #4.*
*Fix direction:* an error summary listing every problem, linked to its field — `design/ux-review` → `fe-react`.

**J6 · major · A successful write is never confirmed. Anywhere.**
Four writes driven — suite created, case created, run started, result recorded — and **not one
produced a success message**. `09-case-created-TC-3-no-toast-w1473.jpg` is the case: TC-3 exists,
the URL changed, and nothing on screen says so.

The app has a full notification system with grouping, counts and correlation IDs, and it is wired
**only to failure** (`NotificationsProvider.tsx:312` hardcodes an error title — r1 **G1**). So the
product can tell you six times that something broke and cannot tell you once that something worked.

The user's only evidence of success is a navigation. That is usually enough — until it isn't: when
the write is a **result recorded against a test**, the tester needs to know it landed, and the run
page is a click away. This is the acknowledgement half of heuristic #1 that r2 explicitly could not
assess.
*Heuristic: #1.*
*Fix direction:* a brief confirmation on write, through the notification channel that already
exists — `design/ux-review` → `fe-react`.

---

## Unsaved work — four surfaces, four different models

This is the sharpest consistency finding of the round, and it **overturns r1 E4**.

**J7 · major · The same product protects unsaved work four incompatible ways.**

| Surface | Behaviour | Evidence |
|---|---|---|
| **Case create** | **Autosaves a draft** and says so: *"Backup saved 05:32 UTC. This is not the case itself — use Create case to publish your work."* | `08` |
| **Case edit** | No warning on Cancel — **but nothing is lost.** Returning shows a banner: *"Your unsaved changes from 1m ago / You left work on this case without saving it."* with **Discard** and **Restore**. | `23` |
| **Run create dialog** | **`Esc` destroys a filled-in form instantly.** No confirm, no draft, no recovery. I lost a completed form this way and had to re-enter it. | observed; dialog gone, fields empty |
| **Execution screen** | Navigation **blocked** with a tooltip: *"Record or clear this result first — moving on would discard what you have marked here."* | `16` |

Four surfaces, four models: autosave-and-announce, autosave-and-offer-restore, silent destruction,
and blocked navigation. The first two are excellent. The fourth is good. **The third is the
outlier, and the product already contains the machinery to fix it.**

> **Correction to r1 E4 (*"Unverifiable — whether Cancel guards unsaved edits"*).** Now settled, and
> the answer is the opposite of the implied concern: the case editor has **no** Cancel warning and
> **loses nothing**. Silent draft capture plus an explicit restore banner is a *better* pattern than
> a confirm dialog — it never interrupts and never loses. **r1 E4 closes as satisfied.**

*Heuristic: #3, #4.*
*Fix direction:* give the run dialog the editor's draft treatment — `design/ux-patterns` → `fe-react`.

**J8 · minor · The run dialog offers four ways to scope a run and never says how they combine.**
`12`: **Plan**, **Suites**, **Tags** and **Cases** are all optional and independent. Nothing states
whether they union or intersect, or that at least one is needed.

The dialog partly redeems this with a genuinely sophisticated touch — **selecting a suite rewrites
the Plan helper text** from *"Every case in the plan's scope becomes a row to record against"* to
**"Recorded as this run's plan. Its scope is not used — the choices below are."** That is adaptive
copy resolving a real ambiguity at the moment it arises, and it deserves credit. It just leaves the
other three uncombined.
*Heuristic: #6.*
*Fix direction:* a live "N cases in scope" preview — `design/ux-review` → `fe-react`.

---

## The run detail page — r2's "half that matters", and it is good

**J9 · major (one defect, in an otherwise strong page).**
`13-run-TR-3-detail-w1473.jpg`. r2 flagged this page as the unopened half of the "understand a run's
outcome" journey. Opened, it is strong:

- **The result bar finally has a legend** — `Passed 0 · Failed 0 · Blocked 0 · Skipped 0 ·
  Untested 1`, coloured dots with counts. The runs *list* has no such key (r1 **I4**), so the
  weakness is the list, not the detail. **This narrows r1 I4.**
- Two framed percentages with explicit denominators: *"— passed, of 0 executed"* / *"0.0% executed,
  of 1 in scope"*.
- **"Created from 1 suite — as they resolved at the time."** One clause telling the user the
  membership was snapshotted, not live. Load-bearing and easy to omit.
- **"Nothing has failed so far — but the run is not finished, so this is not the final answer."**
  The best sentence in the product. It distinguishes *nothing failed* from *nothing failed yet*,
  which is precisely the epistemic trap a test report sets.
- Run lifecycle colours are **correct** here: `Not started` grey, `In progress` blue. So r2's
  **UX3** is narrower than "run status borrows the result palette" — it is specifically that
  `COMPLETED` maps to `--color-passed`.

**The defect: "What failed" is unreliable when case and step verdicts diverge.**
I marked step 1 **Passed** and the case **Failed** — a divergence the execution screen explicitly
invites (*"Record whichever verdict is right"*). The panel then read (`18`):

> **What failed** — 1 case failed
> TC-3 … **0 of 1 steps failed**

"0 of 1 steps failed" under a heading "What failed", for a case that failed. Literally true and
useless for triage. Later, with the case **Skipped**, the same panel read *"Every case in this run
produced a result and none of them failed"* — technically true and misleading: a skipped case is
not evidence of success, and the sentence reads as reassurance.
*Heuristic: #2, #1.*
*Fix direction:* say *why* — "failed at case level; no step was marked failed" — and exclude
skipped cases from the reassurance sentence — `design/ux-writing` → `fe-react`.

---

## The execution screen — the best screen in the product

**J10 · Conforms · Three purpose-built layout modes, described by situation rather than feature.**
`14`. **Focus / Split / Grid**, with accessible names that name the user's circumstance:

- *"Focus — one case at a time, nothing else on screen"*
- *"Split — the run's cases beside the one you are running"*
- *"Grid — every case in one table, **for a pass you already know the answers to**"*

That last clause describes a real testing situation — the re-run where you are confirming, not
discovering. Someone who has actually run tests wrote this.

**J11 · Conforms · Disabled controls explain themselves, everywhere.**
Three instances captured: *"Record a result for this step before attaching evidence to it"* (`14`),
*"Record or clear this result first — moving on would discard what you have marked here"* (`16`),
and the edit page's save button whose accessible name is literally **"Nothing has changed yet"**.
A disabled control that states its unlock condition is applied consistently across this codebase,
and it is the single most user-respecting thing in the UI.

**J12 · Conforms · The verdict is suggested, never assumed.**
`16`: *"Every step is marked, and they point at Passed. Record whichever verdict is right."* with
**Passed** rendered prominently and annotated **`suggested`**. The system shares its inference and
leaves the judgement with the tester — exactly right for a system of record.

**J13 · Conforms · Keyboard accelerators, and a stated principle.**
`17`. This closes heuristic **#7**, which r2 called "largely unassessable":

> "Every one of these has a button on the screen too — **they are a faster route, never the only
> one.** Nothing here fires while you are typing in a box."

`P F B S` for the verdicts, `J`/`K` next/previous, `N` for notes, `?` for the list, and `Esc` to
*"leave the notes box, so the keys above work again"* — which anticipates the exact confusion the
design creates. The `B` entry is domain-aware: *"Blocked — opens the precondition checklist, which
is what Blocked always asks for."*

This also **reframes a criticism I was about to file.** The step buttons are single letters
`P F B S` (`14`) with no on-screen legend, which looks like recall-over-recognition — but they are
**mnemonics for the shortcuts**, not abbreviations. The finding shrinks to J14.

**J14 · minor · The `P F B S` mnemonics have no on-screen legend until the user finds `?`.**
A first-time sighted user meets four unlabelled letter buttons; a screen-reader user hears
"Mark step 1 as Passed". **Assistive-technology users are better served than sighted ones here**,
an unusual inversion and the reason this is worth fixing rather than ignoring. Note also that the
same four values get two radically different treatments on one screen: 28-px letters for steps,
full-width labelled buttons for the case verdict.
*Heuristic: #6, #4.*
*Fix direction:* a one-line key beside "Mark each step as you go" — `design/ux-review` → `fe-react`.

**J15 · major · A recorded result can be overwritten with no confirmation and no undo, and the
stored verdict is invisible on the panel that sets it.**
Click-verified: with **Skipped** stored, I clicked **Passed** and it was overwritten immediately —
no confirm, no undo, no "changed from Skipped" note. Nothing in the UI records that a verdict was
revised.

Compounding it, `16` shows that with Skipped stored, `Skipped` rendered as a plain grey outline
**identical to an unselected option**, while `Passed` was the loudest element on the panel because
it was *suggested*. On the screen whose heading is **"Record the result"**, the recorded result
cannot be read.

In a system of record a test verdict is evidence. Overwriting evidence silently, with no visible
current value and no history, is the most consequential thing found this round.
*Heuristic: #1, #3.*
*Fix direction:* show the stored verdict distinctly from the suggestion; note revisions —
`design/ux-review` → `fe-react`.

> **Deliberately not filed as a keystroke hazard.** While attempting to type a note, my keystrokes
> were consumed by the global shortcut handler and changed the verdict several times. **I am not
> filing that against the app.** The same `type` action also failed to enter text into a second
> multiline field on a different page, while single-line inputs worked throughout — so the extension
> is not delivering keys to the focused element, and the app's stated guard ("nothing fires while
> you are typing in a box") was very likely never exercised. **Open question for r4, to be settled
> with a real keyboard.** What survives is the click-verified half above.

---

## The rest of the product

**J16 · major · `/search` renders three broken form controls.**
`19` and the 1.5× zoom `20`. All three filter selects paint their **label on top of their value**:

| Renders as | Should be |
|---|---|
| `Aňůtus` | `Status` / `Any` |
| `Anyomation` | `Automation` / `Any` |
| `Anyrity` | `Priority` / `Any` |

The MUI `InputLabel` is not shrinking to its notched position, so label and value occupy the same
line. Confirmed at zoom — not a capture artifact. This is a **top-level navigation destination**
(rail item 2 of 3) with three unreadable controls, and it is the most straightforwardly broken thing
found across all three rounds. It went unseen for two rounds only because `/search` was never opened.
*Heuristic: #1, #4.*
*Fix direction:* the selects need `displayEmpty` plus a shrunk label, or a `renderValue` —
`design/ux-review` → `fe-react`.

**J17 · minor · Lifecycle chips are capitalised on cases and lowercase on documentation, and the
docs action row breaks.**
`21`: the documentation page renders a **lowercase `draft`** chip where cases and suites render
**`Draft`** (`04`, `09`). `lib/labels.ts` is meant to be the single source of enum copy, so a second
casing implies a second implementation — which this project's own rules call a finding in itself.
On the same panel the action row wraps: `Edit · Publish · Archive` on line one and **`Delete` alone
on line two**, giving the destructive action its own line and more visual weight than the primary.
*Heuristic: #4.*

**Also observed, attributed to earlier IDs rather than re-filed:**
- `/admin/teams` shows breadcrumb `Administration / Teams & roles` with a **non-navigable root**
  naming a page that does not exist, while the rail calls the same scope **Company** and the URL
  says `/admin` — **a third name for one scope**. Strengthens **r2 UX20**.
- The rail still shows the full project section on company-level admin pages — **r1 C8**, confirmed.
- `/` still reads **"1 cases"** (`01`) — **r1 G2**, confirmed unfixed.
- The execution breadcrumb reads `Reflex / Test runs / **Run** / TC-3` — a generic word where every
  other crumb names its record, so it cannot tell you *which* run. A fifth breadcrumb inconsistency;
  strengthens **r2 UX4 + r1 C6**.

---

# Corrections to earlier rounds

r2's two downward re-rates came from checking an assumed absence. r3 continues that, in both
directions.

| ID | Was | Now | Why |
|---|---|---|---|
| **r1 E4** — "does Cancel guard unsaved edits?" | Unverifiable | **Satisfied — closed** | The editor loses nothing: silent draft + Discard/Restore banner (`23`). Better than the confirm dialog the finding implied. |
| **r2 UX3** — run status borrows the result palette | major | **major, re-scoped** | Not the whole palette: `Not started` and `In progress` are correctly distinct (`13`). The defect is specifically `COMPLETED → --color-passed`. |
| **r1 I4** — runs list answers one question twice | minor | **minor, re-scoped to the list** | The run *detail* has a proper legend with counts (`13`). Only the list lacks a key. |
| **r2 #5** "whether guards fire is unassessable" | — | **Closed: they fire, and they are good** | `03`, `07`, `16`. |
| **r2 #7** "largely unassessable" | — | **Closed: a product strength** | `17`. |
| **r2 #3** transactional freedom | unassessable | **Closed** | J7. |
| **r3's own brief** — "does the UI teach the suite arms?" | assumed possibly untaught | **Taught in five places** | J2. |

---

# Not filed, and why

| Candidate | Why not |
|---|---|
| "23 filter chips and run links have no accessible name" | **`read_page` artifact.** Checking one link with `filter:all` showed `generic "TR-2"` inside it — the tool does not compute names from descendant content; browsers do. |
| "Suite `<select>` has no accessible name" | Same tool doubt. Plausibly real, but the tool is demonstrably unreliable here — **routed to qa `accessibility-testing`** for an axe run rather than filed. |
| "Attach to this step stays disabled after a step result" | **Wrong.** It was enabled on the next visit; the condition re-evaluates. |
| "The create page has no breadcrumb" | **Wrong.** It renders late — `Test cases / New`, 2 crumbs (`08`). |
| Repaint glitch during scroll | Settled on every follow-up capture; indistinguishable from capture timing. |
| Verdict changed by stray keystrokes | Cannot separate a broken app guard from the extension mis-delivering keys. **Open question for r4.** |

---

# Priority

| # | Item | Sev | Why |
|---|---|---|---|
| 1 | **J16** — `/search` selects unreadable | major | A top-level destination is visibly broken. Smallest fix, most obvious damage. |
| 2 | **J15** — verdicts overwritten silently, current value invisible | major | Test verdicts are evidence. Highest-stakes finding of the round. |
| 3 | **J1 + J3** — the first-run contradiction | major | A new user's first two minutes actively mislead. Two small fixes. |
| 4 | **J6** — no success confirmation anywhere | major | The channel exists and is wired only to failure. |
| 5 | **J7** — give the run dialog the editor's draft model | major | The product already contains the fix. |
| 6 | **J9** — "What failed" under divergence | major | The panel is consulted precisely when something went wrong. |
| 7 | **J5, J8, J14, J17** | minor | Batch. |
| 8 | Carried: r2 **UX3** (re-scoped), r1 **G2**, r1 **C8** | — | Unchanged from earlier rounds. |

## Counts

| | |
|---|---|
| **New in r3** | **17** (`J1`–`J17`) — of which **6 are `Conforms`**, recorded as strengths |
| **Blocker** | **0** |
| **Major** | **7** — J1 · J3 · J6 · J7 · J9 · J15 · J16 |
| **Minor** | **4** — J5 · J8 · J14 · J17 |
| **Conforms** | **6** — J2 · J4 · J10 · J11 · J12 · J13 |
| **Earlier findings corrected** | **4** (1 closed as satisfied, 3 re-scoped) |
| **Candidates dropped after checking** | **6** |

**Still no blockers, across three rounds.** And the balance has shifted: r3 found more to praise
than to fix on the screens that matter most. The execution screen, the run detail page and the case
editor are genuinely well designed. The damage is at the **edges** — the first run, the moments
after a write, and one screen nobody had opened.

---

# What r4 needs

1. **A real keyboard**, to settle whether the shortcut guard holds while typing (**J15**'s open half).
2. **A seeded local instance** — light mode, sign-in and accept-invitation are unassessable against a
   live session and will stay that way.
3. **Fault injection** for the 5xx / timeout / 401 / 409 error copy, which exists in source and has
   never rendered in three rounds.
4. **The unreached surfaces**: artifacts, ingestion, `settings/*`, plans, org-chart.
5. **Multi-case runs** — every execution finding here comes from a run of one. `J`/`K` navigation,
   Grid mode and the execution queue were never exercised at realistic size.

---

# Cleanup inventory

**Four records created, all prefixed `[design-audit]`. Nothing else was created, modified or
deleted.** All live in project **Reflex** (`11e23d67-b1bd-4caf-9842-bdf0a893a442`).

| # | Type | Key | Title | Id |
|---|---|---|---|---|
| 1 | Suite | **TS-3** | `[design-audit] Deposits` | `d1d6cc4f-2bd4-4204-a1fa-2dc9b0db9775` |
| 2 | Test case | **TC-3** | `[design-audit] User can deposit via SEPA transfer` | `bc4623fe-9b2f-4cc8-b802-1fc17b85e5b7` |
| 3 | Test run | **TR-3** | `[design-audit] Deposits smoke pass` | `55c577c0-ad3a-4791-847b-570743614b65` |
| 4 | Execution | — | TC-3 within TR-3 | `16c7ff62-aaab-48f3-af58-208d4468a097` |

**URLs** (prefix every path with `https://tcms.203-0-113-10.sslip.io`):

- TS-3 — `/projects/11e23d67-b1bd-4caf-9842-bdf0a893a442/cases?suiteId=d1d6cc4f-2bd4-4204-a1fa-2dc9b0db9775`
- TC-3 — `/projects/11e23d67-b1bd-4caf-9842-bdf0a893a442/cases/bc4623fe-9b2f-4cc8-b802-1fc17b85e5b7`
- TR-3 — `/projects/11e23d67-b1bd-4caf-9842-bdf0a893a442/runs/55c577c0-ad3a-4791-847b-570743614b65`
- Execution — `/projects/11e23d67-b1bd-4caf-9842-bdf0a893a442/runs/55c577c0-ad3a-4791-847b-570743614b65/executions/16c7ff62-aaab-48f3-af58-208d4468a097`

**State left behind**

- **TC-3** — one step, lifecycle `Draft`, filed in TS-3, **v1**. The trial edit was discarded, so no
  v2 was minted.
- **TS-3** — one case, no tag rules, no conditions, lifecycle `Draft`.
- **TR-3** — **In progress**, 1/1 executed, result **Passed** (`24`). Deliberately *not* completed
  or aborted.
- The execution carries **no note** — the note text never reached the field (tooling; see manifest).
- **No draft left dangling**: the editor's unsaved-changes draft was explicitly **Discarded**.

**Suggested removal order** (child first, so nothing is orphaned):
`4` execution → `3` TR-3 → `2` TC-3 → `1` TS-3.

Deleting TR-3 removes the execution with it. Case deletion in this product is **soft**, and past
runs keep their results (`CaseDeleteDialog.tsx:77`), so removing TC-3 will not erase TR-3's history
— if a clean slate is wanted, delete the run first.

**Pre-existing records deliberately untouched:** TC-2, TR-1, TR-2, TP-2, DOC-1/TP-1, the two work
items on `/board` and `/my-work`, and every row in `/admin/teams`.

---

*Round 3 of `docs/audits/002-design-audit-r1.md`. Write-enabled journey pass; not a remediation
round. No product code, spec or design file was modified.*
