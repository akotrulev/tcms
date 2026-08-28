# 002 — Design audit: issues and suggestions

**Consolidated from** `002-design-audit-r1.md` (all altitudes), `002-design-audit-r2-ux.md`
(experience altitude, deeper), `002-design-audit-r3-journeys.md` (write journeys, live).
**Subject:** the TCMS web UI. **Instance:** `tcms.203-0-113-10.sslip.io`, company Reflex.
**Dates:** 2026-08-27 (r1, r2) · 2026-08-28 (r3).

This file is the **actionable extract** — one entry per unit of work, ordered by leverage, with the
suggestion attached. It carries no new findings and no new evidence. The three round reports remain
authoritative for *why* each finding holds and what was actually seen; this one is for scheduling
the work. Where a round re-rated or closed an earlier finding, **the correction is already applied
here** — severities below are final as of r3.

## Reading the IDs

| Prefix | Round | Notes |
|---|---|---|
| `A`–`J` | r1 | Lettered by altitude: A visual identity · B tokens · C IA · D heuristics · E flow · F responsiveness · G UI code · H copy · I charts · J iconography |
| `UX1`–`UX20` | r2 | Experience altitude |
| `R3-J1`–`R3-J17` | r3 | **Renamed here.** r3 issued `J1`–`J17`, which collides with r1's iconography findings `J1`–`J4`. Inside r3's own file they are `J*`; everywhere else use `R3-J*` |

## Corrections already applied

| Finding | Change | Round |
|---|---|---|
| **E4** — "does Cancel guard unsaved edits?" | **Closed as satisfied.** The case editor loses nothing: silent draft capture plus a *"Your unsaved changes from 1m ago"* Discard/Restore banner | r3 |
| **D3** — eleven Health signals unexplained | major → **minor**, tracked as **UX18**. `healthHelp` (`StatusChip.tsx:162`) explains all eleven in tooltips; r1 inferred absence without hovering a chip | r2 |
| **D1** — `Delete` red and top-level | major → **minor**, tracked as **UX10**. `CaseDeleteDialog.tsx:69` is a real confirm and `:77` records the delete is **soft** | r2 |
| **C6** — breadcrumb depth | minor → **major**. Third of three independent breadcrumb failures (with UX4, UX1) — a system failure, not a nit | r2 |
| **I4** — runs list double-encodes | **re-scoped to the list only.** The run *detail* page has a proper legend with counts | r3 |
| **UX3 / A1** — green `Completed` | severity held; **re-scoped** to specifically `COMPLETED → --color-passed`. `Not started` grey and `In progress` blue are correct | r3 |
| **D2** — `Archive project` | major, **held** — deliberately not lowered with D1; no confirm step was ever evidenced | r2 |
| **E5 · F3** — unverifiable in r1 | **resolved** by r3's live write journeys | r3 |

**Counts after corrections: 0 blocker · 24 major · 24 minor · 6 nit.** Plus **6 `Conforms`** recorded
as strengths and **6 deliberate conventions** noted as non-defects.

---

# The work, leverage-ordered

Ordered by user-task impact per unit of effort. Owner is `fe-react` throughout unless noted.

## 1 · `/search` renders three broken form controls — `R3-J16` · major

The three selects paint the label over the value: **"Anyomation"**, **"Anyrity"**. Confirmed at 1.5×
zoom. A top-level nav destination, visibly broken, unseen for two rounds because nobody opened it.

**Suggestion:** `displayEmpty` plus a shrunk label, or a `renderValue`. Smallest fix in the report
against the most obvious damage.

## 2 · A recorded verdict can be silently overwritten — `R3-J15` · major

No confirmation, no undo, no revision history — and the *stored* verdict is invisible on the panel
headed "Record the result": `Skipped` looked identical to an unselected option while the green
`Passed` was merely *suggested*. Test verdicts are evidence; this is the highest-stakes finding of
the engagement.

**Suggestion:** render the stored verdict distinctly from the suggestion, and note revisions.

## 3 · Split the status tier, add a lifecycle ramp and a viz tier — `A1` · `B1` · `B3` · `I1` · `UX3` · major

One saturated palette does five orthogonal jobs: *test passed*, *run completed*, *report ready*,
*case approved*, *chart series*. The visible consequence on `/runs`: **TR-2 shows a green
`Completed` chip on the same row as an all-red result bar and a 0% pass rate.** The scan-read of the
runs list is wrong, and the correcting information is 400 px to the right.

**Suggestion:** one saturated tier for **execution outcome only**; a separate desaturated
lifecycle/progress tier; a categorical viz tier disjoint from status by hue, added before any
further chart lands. Do the token work once and five findings close. Re-scoped by r3: the defect is
specifically `COMPLETED → --color-passed`; `Not started` and `In progress` are already correct.

## 4 · The first-run flow contradicts itself — `R3-J1` + `R3-J3` · major

A brand-new empty suite renders the **no-results** state and offers **"Clear filters"** — a button
whose effect is to undo the suite. Then **New case**, launched from inside that suite, defaults to
**Unfiled**. A new user's first two minutes actively mislead.

**Suggestion:** an empty-suite state distinct from no-results, carrying the three membership arms;
and pre-fill Suite from the active `suiteId`. Two small fixes.

## 5 · No successful write is ever confirmed, anywhere — `R3-J6` · major

Four writes driven, zero acknowledgements. The notification system is wired only to failure.

**Suggestion:** a brief confirmation on write, through the notification channel that already exists.

## 6 · Four surfaces, four incompatible unsaved-work models — `R3-J7` · major

From autosave-and-announce (the case editor) down to `Esc` silently destroying a filled form (the
run dialog).

**Suggestion:** give the run dialog the editor's draft treatment. **The product already contains its
own fix** — see the strengths list below.

## 7 · False project context, and no recovery from a failed page — `UX1` · `UX6` · `E1` · `D4` · `H4` · `G1` · `UX16` · `UX17` · major

One bad URL (`/projects/1/cases`) produces: a complete, furnished, interactive project shell around
a project that does not exist; **three simultaneous error surfaces** reporting a count of six; the
raw server `detail` — *"The body is not valid JSON… Check the request against the schema in the API
contract"* — shown to an end user; a toast that hardcodes `"Something didn't load"` and discards
`describeError`'s computed title (so a 401 reads "Your session ended" inline and "Something didn't
load" in the toast); and **no retry and no way back**, while `retryable` is already computed and
unused. Eight findings, one screenshot.

**Suggestion:** a failed project-context load renders the not-found screen, not the project shell;
one surface owns the message; `DataRegion` and the notification both render Retry where `retryable`;
parse-class 400s get authored copy and `describeError` stops forwarding server `detail` for them
(backend counterpart needed); 4xx responses carry a correlation ID, or show a client-side reference.

## 8 · Backend module names are the user's navigation — `UX19` + `UX20` · major

The rail's two primary group headings, `REPOSITORY` and `EXECUTION`, are literally two Spring
Modulith module names — the server's module boundary served as the user's mental model. Meanwhile
the **company name appears nowhere in any of the 21 r1 captures**, while the help page teaches
Company as the first concept: the tenancy boundary is taught as primary and rendered as a settings
folder.

**Suggestion:** name the groups for the user's task — e.g. *Authoring* / *Running tests*. Surface the
company in the header or as the breadcrumb root. Two edits to the nav map and one header element —
the cheapest structural IA win available.

## 9 · My work promises executions and delivers a case page — `UX11` · major

"My work" advertises executions to record; its only link reaches a case page whose full action
inventory contains nothing that records one. This breaks the daily loop at its entry point.

**Suggestion:** an assigned execution links to its execution, not to its case.

## 10 · Saved views, and the 29-control filter panel — `UX13` · `E2` · `F1` · `UX7` · major

29 always-expanded filter controls push the first case row below the fold at **every** width. At
900 px the rail plus suites panel take 55% of the viewport, leaving the grid ~330 px and hiding the
Status column entirely. The surface with 29 filters has no saved views; the surface that needs them
least has them. The two "browse a project collection" surfaces have incompatible shapes.

**Suggestion:** saved views on the repository first, then collapse the filter bar behind them
(**this supersedes r1 E2's fix direction**). Collapse the rail to icons and stack the suites panel
at `md`, not only at `sm`. One collection-page pattern with filters as a progressive layer.

## 11 · Breadcrumb integrity — three independent failures — `C6` + `UX4` + `UX1` · major

Depth changes between sibling pages (`C6`); "Not filed" is rendered as a breadcrumb segment and a
link, fabricating a container the domain does not have (`UX4`); and a non-existent project is
asserted as real (`UX1`). You-are-here is unreliable in three distinct ways.

**Suggestion:** one breadcrumb builder. The crumb ends at `Test cases` when a case has no suite.
Fix as one job.

## 12 · The board hides 3 of its 5 columns at 900 px — `UX14` · major

No scroll affordance, nothing indicating the other three exist — and a card can still be sent to a
column the user cannot see.

**Suggestion:** horizontal scroll with a visible affordance, or collapse to a single-column grouped
list below `lg`.

## 13 · `plural()` exists and 14 sites hardcode the noun — `G2` · major

`lib/format.ts:227` was promoted out of `flake.ts` for exactly this purpose. The projects list and
the project overview both read **"1 cases"** in production — on every new project's first screen.

**Suggestion:** call `plural()`. 14 one-line edits; the highest fix-value-per-line in the report.

## 14 · The user guide has four names — `C1` + `H2` · major

"Documentation" is two different destinations, visible on one screen.

**Suggestion:** the user guide becomes **Help** everywhere; the rail keeps **Documentation** for
project pages. Rename plus one nav row.

## 15 · Two irreversible controls in two page headers — `D2` · major · `UX10` (was `D1`) · minor

`Archive project` sits beside the project's primary action at equal weight, with **no confirm step
evidenced in pixels or source**. The case-detail header packs four classes of action into one
undifferentiated row.

**Suggestion:** project lifecycle actions move into project settings. Separate navigation from
writes from destructive; `Delete` and `Clone` into an overflow. Note `Delete` itself is **already**
confirmed and soft — it was over-rated in r1.

## 16 · The rail's `COMPANY` group is unreachable — `C3` + `C4` · major

The rail orders scopes narrow-to-wide, inverting the product's own hierarchy, and the `COMPANY`
group sits entirely below an invisible internal scroll at ordinary laptop heights (its scrollbar
thumb is a ~3 px bar barely distinguishable from `--color-border` in dark mode — `A5`).

**Suggestion:** `COMPANY` above the project selector, or a scope switcher; collapsible groups, or
move company scope into the account menu.

## 17 · Pin the token duplication — `B2` · major

30 hex literals are duplicated from `index.css` into `theme.ts` with no computed check. Invisible
today; unbounded later — this is the failure mode CLAUDE.md already names for schema-wide invariants.

**Suggestion:** a test that parses `index.css` and asserts equality with `theme.ts`'s exports, and
correct both comments to say *mirrored*.

## 18 · One rendering of "absent" — `H1` + `B4` + `UX8` · major

Four different renderings of "no value", three of them on one screen; `EM_DASH` is exported and then
re-typed as a literal at every call site; three different empty-region treatments across four
surfaces.

**Suggestion:** one `<EmptyValue>` component and one `<EmptyState>` with an optional action. Keep
prose only where it teaches.

## 19 · Read and write views of a case disagree — `C5` + `H3` · major

The same fields are grouped under different headings between the two most-used views of the core
entity, and a panel renames itself between read and write.

**Suggestion:** one field-grouping model shared by both views. Do it when the case editor is next
touched.

## 20 · Scope-qualify duplicate nav labels — `C2` + `J2` · major

"Audit trail" is two nav rows; "Access & roles" / "Teams & roles" share a label shape and a glyph.

**Suggestion:** scope-qualify ("Project audit trail" / "Company audit trail") and split the icons.
**Cannot be verified live until an account holds both permissions.**

## 21 · One icon weight — `J1` (iconography) · major

Both Material weights are in use and nine glyphs appear in both.

**Suggestion:** pick outlined — the majority, and the right register for a dense tool — and convert
the 42. One sweep or not at all.

## 22 · Charts — `I2` · major · `I3` · `I4` · `I5` · minor/nit

"Automation coverage: 0.0%" is stated against a denominator the caption excludes. The activity chart
has no y-axis, gridlines, values or hover label. The runs **list** answers one question twice in two
encodings (the run *detail* page does this correctly and is not at fault).

**Suggestion:** suppress the decimal below a minimum denominator or show `0 of 1`; a max-value label
or a per-day tooltip; merge the duplicated list column into one bar-plus-number. Legend entries
should not render as underlined links.

## 23 · Explain the eleven Health signals — `UX18` (was `D3`) · minor

Help exists **twice** — `healthHelp` tooltips and a guide section — and is reachable from neither the
filter bar nor the contents list. The product's most distinctive feature is effectively unexplained
at the moment the question arises.

**Suggestion:** carry `healthHelp` onto the filter chips; promote "Health signals" to a top-level
guide section.

## 24 · Remaining minors — batch

`UX2` loading and error render simultaneously in one column (route it through `DataRegion`) ·
`UX5` work-item vocabulary collides with two existing status vocabularies (rename away from the
reserved words) · `UX9` rail and page disagree on a destination's name · `UX12` one work item renders
identically on two surfaces with no stated relationship · `UX15` the projects list spends a
full-width card on two facts · `UX17` the diagnose affordance did not appear on the one error a user
hit · `C7` `/help` highlights nothing in the rail · `C8` project-scoped groups render on
non-project routes · `D5` two differently-labelled "create a suite" controls in one 260 px panel ·
`D6` a filter chip shows a count when non-zero and nothing when zero, so "0" and "unknown" are the
same · `D7` two card shapes with different drag affordances and no way to tell which is which ·
`E3` the board renders every possible transition as a permanent button on every card ·
`F2` the cases grid clips columns at 1440 px · `H5` an empty state asserts an invariant the same
screen disproves · `H6` a helper sentence does not parse · `R3-J5` a multi-error form reports its
errors a screen apart with no summary · `R3-J8` the run dialog offers four ways to scope a run and
never says how they combine · `R3-J9` the run detail page's "What failed" panel does not say *why*
under divergence · `R3-J14` the `P F B S` mnemonics have no on-screen legend until the user finds `?` ·
`R3-J17` lifecycle chips are capitalised on cases and lowercase on documentation.

## 25 · Nits — opportunistic

`A2` a status colour on an action control (amber `Quarantine test`) · `A3` two "filled steel"
controls 40 px apart meaning "selected" and "primary" · `A4` priority is a fifth colour encoding with
no legend · `A5` the rail scrollbar is invisible in dark mode · `G3` a stray double space before the
separator on board and my-work cards · `H7` two health signals render as chips in one place and muted
text in another · `I5` chart legend entries as underlined links · `J3` (iconography) an action
button's icon describes the artifact, not the action.

---

# Confirmed strengths — do not regress

r3 filed six findings as **`Conforms`**. They are recorded because a later change could silently
remove them.

| ID | What holds |
|---|---|
| `R3-J2` | The three suite membership arms *(filed ∪ added ∪ tagged)* are taught in **five separate places**, in the user's language. The premise that the UI assumes the spec has been read was wrong |
| `R3-J4` | Client-side validation is prompt, well-worded, and moves focus |
| `R3-J10` | The execution screen has three purpose-built layout modes, described **by situation** rather than by feature |
| `R3-J11` | Disabled controls explain themselves, everywhere |
| `R3-J12` | The verdict is **suggested, never assumed** — judgement stays with the tester |
| `R3-J13` | Keyboard accelerators, with a dialog stating the principle: *"a faster route, never the only one"* |

Also holding, and closed rather than filed: **`E4`** — the case editor captures drafts silently and
offers *"Your unsaved changes from 1m ago"* with Discard/Restore. This is the model item 6 should
propagate to the run dialog.

**The balance is worth stating plainly.** The never-audited write paths were expected to be the weak
half and are the strong half: the execution screen, the run detail page and the case editor are the
best-designed surfaces in the product. The damage is at the **edges** — the first run, the moments
after a write, and one screen nobody had opened.

# Deliberate conventions — not defects

Noted so a later reader does not "fix" them: UTC everywhere with an explicit suffix
(`lib/format.ts:6`) · monospace tabular figures for identifiers (`Identifier.tsx`) · colour is never
the only signal (`StatusChip.tsx:22-26`) · flat suites, matching the product model · `elevation: 0`
with hairline borders instead of shadows (`theme.ts:88-95`) · redundant `→ status` buttons beside
drag as an accessibility fallback (`BoardPage.tsx:547` — `E3` objects only to their permanent
full-weight rendering, not their existence).

# Routed elsewhere — not design findings

| Observation | Owner |
|---|---|
| `--color-text-faint` `#8593a5` on white measures **≈3.1:1**, below the 4.5:1 floor — plus landmarks, ARIA, tab order | qa `accessibility-testing` |
| The backend returns a body-shaped parse error for a malformed **path** parameter | `code-auditor` / backend |
| **6** duplicate requests fired for one failed page load | stack `/fe-react` audit |
| Whether the confirm dialogs behind `Delete` / `Archive project` are adequate | `qa-test-case-designer` |
| Junk seed data (`asfasf`, `sfds`) on the deployed instance | data, not design |

# Still open

**Unassessable without new access:**

- **Sign-in / accept-invitation** — needs credentials; signing out would end the session with no way back.
- **Light mode** — would rewrite the user's persisted preference.
- **5xx / timeout / 401 / 409 copy** — exists in source, never rendered. Needs `tcmsFaults`, which is dev-only, so this needs a **local seeded instance**, not the deployed one.
- **Artifacts · ingestion · settings · plans · org-chart** — never visited.
- **Multi-case runs** — every execution finding above comes from a run of one.
- **`C2` / `J2`** — needs an account holding both project and company permissions.
- **`J4` (iconography)** — favicon, OG image and social preview were never checked; they need the served `index.html` and a link-unfurl, not a screenshot.

**Open question for r4:** during r3 the auditor's typing was consumed by the global shortcut handler
and rewrote a verdict — but the same action failed on a second multiline field while single-line
inputs worked. **A broken app guard and the browser extension mis-delivering keys cannot be separated
from the evidence available.** Settle it with a real keyboard before filing anything against the app.

# Cleanup — records created on the live instance

r3 created four records in project **Reflex**, all prefixed `[design-audit]`. **Nothing pre-existing
was created, modified or deleted.**

| # | Type | Key | Id |
|---|---|---|---|
| 1 | Suite | **TS-3** `[design-audit] Deposits` | `d1d6cc4f-2bd4-4204-a1fa-2dc9b0db9775` |
| 2 | Case | **TC-3** `[design-audit] User can deposit via SEPA transfer` | `bc4623fe-9b2f-4cc8-b802-1fc17b85e5b7` |
| 3 | Run | **TR-3** `[design-audit] Deposits smoke pass` | `55c577c0-ad3a-4791-847b-570743614b65` |
| 4 | Execution | TC-3 within TR-3 | `16c7ff62-aaab-48f3-af58-208d4468a097` |

Remove child-first: **4 → 3 → 2 → 1**. Deleting TR-3 takes the execution with it. Case deletion is
**soft** and past runs keep their results, so delete the run first for a clean slate. TR-3 was left
**In progress**; the editor draft was explicitly Discarded, so nothing is dangling.

---

# Evidence

| Round | Captures |
|---|---|
| r1 / r2 | `assets/002-design-audit/` — 21 images + `MANIFEST.md`, at 1512 · 1568 · 1440 · 900 · 501 px |
| r3 | `assets/003-journeys/` — 24 images + `MANIFEST.md` |

Nothing in this file is verified beyond what those captures and the cited source lines show. Per the
§65 working agreement these rounds are **built and unverified** work products: the browser passes
that would confirm each fix belong in `docs/verification/frontend-browser-checks.md`, and no row has
been added there yet.
