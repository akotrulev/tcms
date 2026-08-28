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
   heading repeating the rail's active item, no panel around the only thing on screen.
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

## 4. Where the rail collapses

The expanded 232px rail — headings and order exactly as `AppShell.tsx:projectNav` has them — is the
default everywhere. It collapses to the 60px icon rail on **exactly two routes**:

- the case document editor (the case redesign's decision — the document wants the width), and
- **the execution runner** (this document's addition, for the same reason: the tester is reading a
  procedure, and the rail is navigation they are not doing).

Scoped to those routes, not global, and not a user preference on top of the existing one.

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

## 9. Open problems — unsolved, not oversights

1. **The report preview is the one light surface in a dark product.** Deliberate — a PDF preview
   drawn dark misrepresents the file being downloaded — but it is a second palette to maintain, and
   nothing here says what happens when the app grows a light theme and the two collide.

2. **Two rail widths is a layout the shell does not have.** Collapsing on two routes means the shell
   reads the route, which `AppShell` does not do today. The case redesign named this and did not
   cost it; adding a second route does not make it cheaper.

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
