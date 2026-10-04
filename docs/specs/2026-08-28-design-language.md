# The design language, applied to the other forty-two screens

**Status:** proposed, nothing implemented · **Date:** 2026-08-28 · **Owner:** frontend

**Renders:** [`assets/renders/index.html`](assets/renders/index.html) — nine family files, every one
of the 42 remaining pages drawn. Shared kit: [`assets/renders/tcms.css`](assets/renders/tcms.css)
and [`assets/renders/shell.js`](assets/renders/shell.js). Open any of them in a browser; nothing
needs a server.

**Extends:** [`2026-08-28-case-authoring-redesign.md`](2026-08-28-case-authoring-redesign.md) and
[`2026-08-28-case-authoring-rail.md`](2026-08-28-case-authoring-rail.md), which settled the
direction on one screen. This document carries it to the rest and records the four decisions that
only became visible once it had to serve forty-two.

---

## 0. What this is, and what it is not

It is a **direction with the rules written down**, so that two people implementing two screens
produce screens that look related.

**The renders were opened in a browser at 1680px**, which the case render's caveat says was never
done. That pass found and fixed three layout defects the source could not have shown — see §9.5 —
and it is the reason the caveat below is narrower than the case render's. It is still not evidence
about the *product*: these are drawings with invented data, and nothing has been driven against a
real API, at 1280px, or with a fifty-step case.

Two things in it are *not* proposals. They are defects, found by computation rather than by reading,
and §6 states them with their numbers.

---

## 1. The tokens, and their relation to the app's

The render kit's palette is **the app's dark theme, tightened** — not a new one.
`frontend/src/index.css` already ships `--color-action-primary: #6ba8c4` and the IBM Plex pair; the
kit renames them for drawing and adds the four Gherkin keyword hues the case render introduced.

| Kit | App token | Note |
|---|---|---|
| `--ground` `#0a0e14` | *(new)* | the window behind the shell; the app has no equivalent yet |
| `--paper` `#11161e` | `--color-surface-sunken` `#0f141b` | the working surface — where the subject of the page lives |
| `--panel` `#141a23` | `--color-surface` `#151b24` | rails, cards, panels — chrome that frames the subject |
| `--field` `#0e141c` | *(new)* | recessed: inputs, code blocks, empty chart tracks |
| `--ink` … `--ink-4` | `--color-text{,-muted,-faint}` | **four weights, and never a fifth** — the app ships three |
| `--steel` `#6ba8c4` | `--color-action-primary` | identical. One accent, and it means *this instance's voice* |
| `--given/--when/--then` | *(new)* | `#7ec8a0` `#e0b95f` `#8fa9e0` |
| `--passed/--blocked/--retest` | `--color-passed` etc. | **deliberately the same hues as the keywords** — see rule 5 |

**The port is a mapping, not a translation.** Adding `--ground` and `--field` and splitting
`--color-text-faint` into two are the only token changes; everything else already exists.

## 2. The eight rules

Each is stated as the failure it prevents, because most of them look arbitrary otherwise.

1. **The subject of the page is the page.** Whatever the reader came for — a procedure, a result
   log, a table, a spec paragraph — gets the width and the ink. No card wrapping a table, no page
   heading repeating the top bar's active tab, no panel around the only thing on screen.
   *Prevents:* three borders before the first row of data.

2. **Chrome is a strip above and a strip below.** Filters in one row under the toolbar; a footer
   that states what is on screen and what the keyboard can do. Nothing else is chrome.
   *Prevents:* controls scattered into the content, so a reader hunts for the one they want.

3. **A quiet control is still a control.** Rail rows, chips and settings toggles may be visually
   silent at rest — no filled backgrounds, values right-aligned — but every one has a native role,
   an accessible name, keyboard operation and a visible focus ring. This is the rail spec's
   decision, generalised. *Prevents:* UX#117's clickable div, and UX#62's summary that is secretly
   a form.

4. **Nothing configured, nothing shown.** An empty vocabulary renders no control; one sentence and
   a link, once, for all of them together. *Prevents:* finding 3 of the case redesign — four dead
   selects and a line of copy describing the system's state to a reader it leaves stuck.

5. **One palette serves keywords and results.** `Given` is the green that `Passed` is; `When` is
   the amber that `Blocked` is; `Then` is the blue that `Retest` is. *Prevents:* two colour systems
   on one screen — the editor and the runner show the same steps, and a tester should not have to
   learn two meanings for green.

6. **Mono carries identity and quantity; sans carries prose.** Keys, counts, durations, timestamps,
   references and shortcuts are IBM Plex Mono with `tabular-nums`. *Prevents:* a column of numbers
   that will not line up, and an id you cannot tell from a sentence.

7. **Disable, don't hide — and say who can.** A refused screen renders read-only with the
   permission's real id and **the name of somebody in this company who holds it**. A revoked token,
   a paused schedule and a deleted run keep their rows at reduced weight, with the date and reason.
   *Prevents:* teaching a Test Lead that this instance has no single sign-on, and making an audit
   impossible by tidying dead rows away.

8. **A count is a link to its rows.** Every aggregate, health signal and tile drills to the filtered
   table, and the URL carries the filter. *Prevents:* a dashboard nobody can act on.

## 3. Measure, and the four document surfaces

Four screens render prose or procedure, and they share a measure so a person who learns one has
learned all four: **74ch for a procedure** (case editor, case detail, execution, proposal, import
preview) and **70ch for prose** (documentation, doc editor, help). Titles at 24–25px / 620 weight,
a 26px mono gutter, one hairline rule under the title block.

Help is deliberately the fourth. It is written **in the product**, on the product's own document
surface, using the product's own chart components to explain the product — which is what keeps it
from drifting away from the thing it describes.

## ~~4. Where the rail collapses~~ → superseded (023 D1)

> **Superseded 2026-10-04 by plan 023 (D1) and §10 below.** There is no rail: one 48px top bar serves every
> route, the case editor and the execution runner included, so nothing collapses. Kept as the record of
> why the two routes were special.

The expanded 232px rail — headings and order exactly as `AppShell.tsx:projectNav` has them — is the
default everywhere. It collapses to the ~~60px~~ **64px** icon rail (see §9.2 for why the
build's number wins) on **exactly two routes**:

- the case document editor (the case redesign's decision — the document wants the width), and
- **the execution runner** (this document's addition, for the same reason: the tester is reading a
  procedure, and the rail is navigation they are not doing).

Scoped to those routes, not global, and not a user preference on top of the existing one.

**Verified in the shipped build, 2026-08-30.** The renders audit measured `Nav.Rail` at **64px** on
the runner and the case editor — the amended §9.2 number, not the 60px the renders draw — and found
the collapse firing on all three of the patterns `AppShell.tsx` reads. This is the one place the
spec was amended in the build's favour and the build matches the amendment; it does not need
re-opening.

## 5. The component kit

Nine components carry all forty-two screens. They are drawn in the renders and defined in
`assets/renders/tcms.css`.

| Component | Used by | The rule it carries |
|---|---|---|
| Table | 10 index screens + 6 embedded | sticky mono heads, a quiet second line in the cell rather than a twelfth column (UX#113 / UX#111) |
| Result bar | tables, detail, analytics | proportion before number; see §6 |
| Badge vs chip | everywhere | a **badge** is state the system owns and is static; a **chip** is a value the reader owns and is a button (UX#114 / UX#117) |
| Rail section (`kv`) | every detail and editor | quiet at rest, bordered on hover, focus-ringed — rule 3 |
| Document surface | 4 screens | §3 |
| Timeline | case history, suite history, audit | one column, mono stamps in the gutter |
| Banner | ~20 screens | neutral for consequence, amber for a state that is currently wrong, red for one that has failed |
| State block | all 42 | §7 |
| Figure | 2 analytics screens | §6 |

## 6. Charts — what the validator decided, not what looked right

Built through the `dataviz` procedure: form first, colour last, palette **computed** rather than
judged. Two results changed the drawings, and both are defects in what ships today.

**`--color-skipped` and `--color-untested` are 10.7 ΔE apart in OKLab.** That is below the
normal-vision floor of 15 — a hard fail for a reader with full colour vision, before CVD is
considered — and today they sit adjacent in every stacked result bar in the product. Re-stepping
either was the wrong fix. *Untested is the absence of a result, not a result*, so it became the
**empty track**. That deletes the failing pair rather than papering over it, and the four remaining
fills pass: worst adjacent 9.4 ΔE deutan, 17.3 normal. `--color-untested` survives only as the ink
of the words "Never run".

**The product's accents cannot form a categorical series set.** Steel + orchid + periwinkle measured
**2.5 ΔE under deuteranopia** — indistinguishable. So *Activity, last 30 days* is drawn as three
small multiples (one series each, no legend, no shared colour) rather than one three-series chart.
The form heuristic reached the same answer independently.

**Consequently: TCMS has no categorical palette, by design.** Every figure in the product is either
one series (magnitude, in steel) or a status stack (reserved colours). If a future chart genuinely
needs two or three series, the validated order is **steel `#6ba8c4` → amber `#e0b95f` → orchid
`#c48ec4`** (17.6 ΔE protan, 20.5 normal at the worst adjacent pair); beyond three, facet instead.

Also applied: a 2px surface gap between stacked segments and 3px ends (abutting fills of similar
lightness read as one segment at 6px tall); no pie, donut or gauge anywhere; a crosshair and tooltip
on the one line chart by default; y axes start at zero and the render says so where a reader could
not otherwise tell.

**One stated divergence.** The validator's *lightness band* and *chroma floor* checks fail against
this palette, and are not being "fixed". They are calibrated for a saturated categorical set on a
light surface; TCMS is deliberately low-chroma on a dark ground because it is read for hours. The
two checks that gate legibility — CVD separation and the normal-vision floor — pass, and those are
the ones acted on above.

### These numbers are per scheme — amended 2026-08-30

~~The palette validates once.~~ → **superseded.** Everything above was derived for the **dark**
palette. The light one was never put through the same check, and the 2026-08-30 renders audit (6.1)
measured it failing on **two of three adjacent pairs**: `failed | blocked` at 12.6 and
`blocked | skipped` at 13.0, against the floor of 15. In light mode the TR-1 bar drew
`rgb(189,58,43)` beside `rgb(138,95,13)` — red beside brown, reading as one warm band.

Three things follow, and they are the rule rather than the fix:

1. **Every scheme is validated separately.** §1 and this section are written in dark-theme terms
   throughout; read them as *"the dark palette"* wherever they say *"the palette"*.
2. **A light palette has a second constraint the dark one does not.** These tokens are used as
   **text** as well as fills — the run detail's legend colours its words with them — so a light
   value must also clear 4.5:1 against `--color-surface-sunken`. That is what makes the light set
   hard: the obvious brighter amber clears 15 ΔE and falls to 3.33:1. `--color-blocked` was
   re-stepped to `#623c00` (18.9 / 15.9 ΔE, 8.26:1), and `--color-when` with it, because rule 5
   makes `When` and `Blocked` one hue and that is a claim about meaning.
3. **The check is computed, not read.** `frontend/src/app/statusPalette.test.ts` reads the
   stylesheet and measures both schemes, so a third one cannot repeat the omission. It was verified
   by breaking it — restoring `#8a5f0d` reddens exactly the two pairs named above.

### One figure per panel stays carded — considered 2026-08-30

The same audit's 6.4 (Low) asked for the dashboard's figures to lose their borders, reading rule 1
as forbidding them. **Not applied.** Rule 1's subject is *the only thing on a screen*; seven figures
are seven things, and the border is where one denominator stops and the next begins. The audit's own
6.2 — two of those figures shipping with no legend — is evidence on that side. The reasoning is in
`components/kit/FigurePanel.tsx`, where the next reader will be standing.

## 7. Six states, not five

`DataRegion` owns loading / loaded / empty / failed / stale. This adds **refused**, and it is not an
error: neutral colour, no retry button, and the name of someone who can grant the permission.
Retrying a permission you do not hold is not a recovery path, and drawing it red teaches people the
product is broken when it is working as configured.

The load-bearing one is that **failed and empty are drawn to be unmistakable** — different icon,
colour, verb and offer. A 200 with the wrong shape rendering as *no results* is the quietest
data-loss bug a client can have, and it is exactly what `tcmsFaults.drift()` exists to prove. Empty
counts what its filters hid (*1 284 cases exist. Three filters are hiding all of them*) and offers
their removal in place. Every failure carries a six-character reference and a timestamp.

Notifications: success may auto-dismiss on 3–5 s; **a failure never does**, and an error names the
field and links to it.

## 8. The family map — all 42

| # | Family | Pages |
|---|---|---|
| 1 | Index & table | CaseList · CaseSearch · RunList · PlanList · Proposals · Reports · Ingestions · RunSchedule · Artifacts · Projects |
| 2 | Record as document | CaseDetail · RunDetail · IngestionJob · Proposal · CaseHistory · SuiteHistory · ReportView |
| 3 | Execution | Execution |
| 4 | Board & people | Board · MyWork · TeamBoard · OrgChart |
| 5 | Authoring | Docs · DocPageEditor · FeatureImport |
| 6 | Analytics | Dashboard · PlanStatistics |
| 7 | Settings & admin | Review · Classification · Notifications · ProjectAccess · Teams · ApiTokens · SsoProviders · AuditLog · AccountSecurity |
| 8 | Threshold | Login · AcceptInvitation · SsoCallback |
| 9 | States | NotFound · Help · (the matrix, inherited by all 42) |

Grouped by **job, not rail position**: ten screens are the same table with different columns, and a
file per page would have drawn that skeleton ten times and let the ten drift.

### Three renders the build disproved — amended 2026-08-30

Plan 007 §4's rule: where the build disagrees with a render, the **spec** is amended. Three qualify,
from the renders audit of that date.

**Artifacts is a registrable catalogue, not a read-only sink.** ~~Render H draws Artifacts as an
empty state whose copy reads *"artifacts arrive attached to an execution or an ingested report;
there is nothing to upload here"*.~~ → **superseded (audit 1.11).** The backend has supported
registration since M0 and the build ships a Register button, a scope select and registered
documents. The render was drawn against a wrong mental model of the feature, and the audit calls
this the clearest case in it of the build being right. Family H's entry is the build's.

**The runner has three layouts, not two.** ~~*"Focus and Split are the two layouts."*~~ →
**superseded (audit 3.4).** Focus, Split and **Grid** ship, Grid is used, and it is the only mode
that can take a verdict on many cases without opening each. The render's note predates it.

**The runner's duration is a field, not a live timer.** ~~The render shows a counter (`101 seconds ·
Running`, *"Counting since you opened the case"*).~~ → **superseded (audit 3.4).** The build shows
an optional box captioned *"Leave it empty if you did not time this run."* A counter that starts
when a tab opens measures the tab, not the test — a tester who opened the case, went to a meeting
and came back would record ninety minutes for a two-minute check — and a wrong duration recorded
automatically is worse than an absent one recorded honestly.

**And one control the render draws that this product cannot honestly offer.** The runner's top
strip has no **Pause run**: the run lifecycle is `NOT_STARTED → IN_PROGRESS → COMPLETED → ABORTED`
with no paused state, so the control would either lie or abort the run under a gentler word. What
the strip does carry, from the same render, is the elapsed time — driven by the run's `startedAt`,
never by when the tab opened, for the reason the timer above was rejected.

## 9. Open problems — unsolved, not oversights

1. **The report preview is the one light surface in a dark product.** Deliberate — a PDF preview
   drawn dark misrepresents the file being downloaded — but it is a second palette to maintain, and
   nothing here says what happens when the app grows a light theme and the two collide.

2. ~~**Two rail widths is a layout the shell does not have.** Collapsing on two routes means the
   shell reads the route, which `AppShell` does not do today. The case redesign named this and did
   not cost it; adding a second route does not make it cheaper.~~ **→ superseded — closed by plan
   007 T5.** `AppShell` reads the route now and collapses on **three** patterns, not two: §4's two
   screens, one of which is reachable at two URLs. The cost the paragraph was worried about was
   real and was paid once, in the shell, rather than per screen.

   **The collapsed rail is 64px, not the 60px the renders draw.** That is a deliberate amendment to
   the spec rather than a defect in the build, per 007 §4's rule that where the build disagrees with
   a render the *spec* is amended: 64 is a multiple of the 4px grid the rest of the shell sits on,
   and it leaves a 40px icon target 12px of side padding, which 60 does not.

3. **Nine families is nine files to keep in agreement.** The shared kit helps and does not solve it:
   a component changed in `tcms.css` silently changes nine renders, which is the point, but a
   component changed in *one* render's local `<style>` block drifts and nothing catches it. There is
   no test here, and there cannot easily be one — these are drawings.

4. **The health-signal copy in the renders is invented.** *"Disagreed with themselves in 3 of the
   last 5 runs"* reads well and is not necessarily how `FAILING_REPEATEDLY` is actually computed.
   Every such sentence needs checking against the backend before it ships as UI copy.

5. **The renders have been looked at; the product has not.** Opening them at 1680px found three
   defects invisible in the source, all now fixed and all worth knowing before this is built:

   - **Every file was missing `<meta charset>`**, so every em dash, `·` and `Δ` rendered as
     mojibake. Trivial, and it would have been shipped.
   - **The magnitude-bar label column was fixed at 130px**, which starved the track to ~40px inside
     a three-across figure — the bars were unreadable pills. Now `minmax(0,88px)`.
   - **The ten-column case table squeezed rather than scrolled**, breaking `TC-194` across two
     lines and an owner's name across three. That is UX#113's essential-text rule failed *by
     layout* rather than by truncation, and it is the defect most likely to already exist in the
     shipped grid. The fix is the product's own `ScrollX` pattern: cells do not wrap, the region
     scrolls, and the title cell is the one exception.

   Still unchecked and most likely to break first: the org-chart matrix at five projects, the board
   at five columns on a 1280px screen, and the execution runner's three-pane split.

## 10. Amended 2026-10-04 — one bar, one strip, one ladder (plan 023)

The eight rules of §2 all still hold. Plan 023 (`docs/plans/implemented/023-one-design-language.md`) adds
eleven, A1–A11. A1 retires §4; A6 and the radius table replace the spacing and corner values stated
earlier; the card of A10 and the shell of A1 are specified in that plan's §3 and §6.

- **A1 — No rail.** One 48px top bar (§3) on every authenticated screen. Navigation is the bar's
  tabs and menus; the page below is the work.
- **A2 — One strip opens every page.** `PageStrip` (§4) sits directly under the top bar and is
  sticky beneath it. It holds the trail, the title, the views, the filter and the actions. Nothing
  else sits between the top bar and the content. This is rule 2's "a strip above", made literal.
- **A3 — The surface ladder.** The canvas is `bg-paper`. A panel (a column, a side panel, a section
  of a settings page) is `bg-panel border border-line rounded-xl`. A card on a panel (a board card, a
  tile) is `bg-raised border border-line rounded-lg shadow-e1`. An overlay (menu, popover, dialog,
  select content) is `bg-raised border border-line rounded-xl shadow-e3`. The top bar is `bg-bar`.
  `DocumentSurface` stays on `bg-paper` with no border: the document is the page.
- **A4 — Radius roles on Tailwind's own scale.** No `rounded-[…]` literal anywhere (§5.2):

  | Role | Utility | px |
  |---|---|---|
  | bar segment, meter | `rounded-xs` | 2 |
  | badge, tag, kbd | `rounded-sm` | 4 |
  | segment item, menu item, small icon button | `rounded-md` | 6 |
  | button, input, select, card, banner | `rounded-lg` | 8 |
  | panel, column, overlay | `rounded-xl` | 12 |
  | chip, avatar, count pill | `rounded-full` | — |

- **A5 — Type in the chrome.** Strip title 17px / 650 / `-0.015em`; trail 12px `text-ink-3`;
  subtitle 12.5px `text-ink-3`, `max-w-[74ch]`; panel heading 13px / 600. Document titles inside a
  `DocumentSurface` keep §3's 24–25px. Rule 6 (mono for identity and quantity) is unchanged.
- **A6 — Gutter.** 16px from `md`, 12px below it. ~~22px, the render kit's one spatial constant~~ →
  superseded by D14.
- **A7 — Menus.** Radix `DropdownMenu` or `Popover` only. A group heading is mono 10.5px uppercase
  `tracking-[.08em] text-ink-3`. An item is 13px `px-2.5 py-1.5 rounded-md`; the current item carries
  a check and `aria-current`/`aria-checked`. A footnote is 11.5px `text-ink-3` over a `border-t
  border-line`.
- **A8 — One segmented control.** Track `inline-flex gap-0.5 rounded-lg bg-wash-1 p-0.5`. Item
  `flex h-7 items-center gap-1.5 rounded-md px-2.5 text-[12.5px] font-medium`. Active item
  `bg-raised text-ink font-semibold shadow-e1`; inactive `text-ink-3 hover:text-ink-2`. Three
  semantics share this one look (§5.4).
- **A9 — Filters behind one button.** A "Filter" button opens a popover holding every picker; its
  count is the number of applied filters. Search stays inline beside it. Applied filters stay visible
  as chips after the button. The OR-within / AND-across sentence is the popover's footnote.
- **A10 — The card.** One card shape on every board (§6.1). Identity first, test result last; labels
  are a 7px dot plus the name. No tilt, no coloured fill.
- **A11 — Colour.** The thesis is unchanged: steel only for actions and the current place; status hues
  only for data. No coloured canvases (D5). A label's colour appears only as its dot.
