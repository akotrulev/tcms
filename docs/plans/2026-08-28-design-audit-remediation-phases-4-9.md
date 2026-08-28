# Design-audit remediation — phases 4–9 (handoff prompt)

Copy everything below the line into a fresh session.

---

Implement phases 4–9 of the design-audit remediation in this repo
(`~/RiderProjects/tcms`, branch `master`).

## Read first

- `CLAUDE.md` — project rules. Frontend work goes through the **`/fe-react`** command (it is a
  command, not a skill; `Skill(fe-react:fe-react)` returns "Unknown skill" by design).
- `docs/agent-context/fe-react.md` — the orientation cache. Read it before the source.
- `docs/audits/002-design-audit-issues-and-suggestions.md` — **the backlog you are implementing.**
  It consolidates rounds r1/r2/r3 and already applies every cross-round re-rate, so its severities
  are final. The three round reports (`002-design-audit-r1.md`, `-r2-ux.md`, `-r3-journeys.md`)
  carry the evidence for *why* each finding holds; screenshots are in
  `docs/audits/assets/002-design-audit/` and `assets/003-journeys/`.

**ID note:** r3 issued `J1`–`J17`, colliding with r1's iconography `J1`–`J4`. Everywhere outside
r3's own file, r3's are written `R3-J*`. Keep that convention.

## THE TESTING RULE FOR THIS RUN — read it twice

**Do not run the test suite between phases. Run the gates exactly once, after phase 9 is written.**

This overrides the usual per-phase gate. Reason: the suite takes ~5 minutes per run on `shanks` and
the previous session spent most of its time on repeated runs. During phases 4–9 you may use only:

- `npx tsc -b` (seconds) — after each phase, to keep the tree compiling.
- `npm run lint` (seconds) — cheap, run it freely.
- A **single** targeted `npx vitest run <one file>` when you have written a new test and want to
  know it is wired up. Not the suite, not a directory.

Then, once at the end, per the standing directive that gates run on `shanks` and not locally:

```bash
~/bin/tcms-remote frontend 'npm run build && npm run lint'
~/bin/tcms-remote frontend 'npm run test:run'
```

**Known flake, do not re-investigate from scratch.** `CaseSchema.test.ts` can fail ~12 assertions in
the *full* suite on shanks while passing alone (39/39) and passing in the full suite locally.
`caseSchema.ts` imports only `zod` and is unrelated to this work. `vitest.config.ts` documents this
class in its own comments — *"any slice that adds tests anywhere can redden a file it never
touched"* — and records the timeout being raised twice for it. If you hit it: run that file alone to
confirm, run the suite once more to see whether the failing set moves, and report it as
pre-existing. Do not chase it, and do not raise the timeout a third time without saying so.

## Starting state

Phases 1–3 are done. **Phases 1–2 are committed and pushed; phase 3 is written and uncommitted** —
commit it first, with the flake noted, before starting phase 4.

Already landed, so build on these rather than reinventing them:

| Seam | Where | What it now does |
|---|---|---|
| `plural(n, noun)` / `pluralNoun(n, noun)` | `frontend/src/lib/format.ts` | One place a count meets its noun; `plural` routes the number through `formatCount`. `pluralNoun` is for call sites that render the number separately (e.g. wrapped in `<Identifier>`). |
| `EM_DASH` | `lib/format.ts` (exported) | The one rendering of "no value". Never re-type `'—'`. |
| `describeError().title` | `lib/describeError.ts` | Now reaches the toast. `NotifyErrorInput.title` carries it. |
| `describeError().isAddressWrong` | `lib/describeError.ts` | True for 404s and for the server's contract-facing problems (`The request could not be read`, `No such endpoint`, …). Contract copy no longer reaches users. |
| `ProjectGuard` | `app/router.tsx` | Pathless layout route wrapping every `/projects/:projectId*` route; renders `NotFoundPage` when the address is wrong. |
| `--color-progress-done` | `index.css` | A job's lifecycle, never its outcome. `runStatusColors.COMPLETED` and `reportJobStatusColors.COMPLETED` use it. |
| `--color-viz-1` / `--color-viz-2` | `index.css` | Chart series only. One analytic hue at two steps — a categorical *hue* is impossible here, see below. |
| `themeTokenParity.test.ts` | `app/` | Pins `index.css` ↔ `theme.ts` by reading both as text; also asserts the new tiers never share a value with an outcome token. Registered in **both** `tsconfig.app.json`'s exclude and `tsconfig.node.json`'s include — those two lists are hand-maintained and must agree. |

**Why the viz tier is not a hue:** every candidate categorical pair measured under the ΔE 15
normal-vision floor against an existing status colour (amber vs any orange came out at 4.0), because
green, red, amber, purple and blue are all already spoken for. If you add chart colours, validate
with `node scripts/validate_palette.js "<hex,...>" --mode light|dark` from the bundled `dataviz`
skill rather than eyeballing, and load that skill before writing any chart code.

---

# The work

Owner is `fe-react` throughout. Item numbers below are the section numbers in the backlog document.

## Phase 4 — write feedback (backlog items 2, 5, 6)

- **`R3-J15` · major** — a recorded verdict is overwritten with no confirmation, no undo and no
  revision history, and the *stored* verdict is invisible on the panel headed "Record the result"
  (`Skipped` looked identical to an unselected option while green `Passed` was only *suggested*).
  Render the stored verdict distinctly from the suggestion; note revisions. Test verdicts are
  evidence — this is the highest-stakes finding in the engagement.
- **`R3-J6` · major** — no successful write is confirmed anywhere. Four writes were driven and
  produced zero acknowledgements; the notification channel is wired only to failure. Add a brief
  confirmation on write through the channel that already exists.
- **`R3-J7` · major** — four write surfaces protect unsaved work four incompatible ways, from
  autosave-and-announce down to `Esc` silently destroying a filled form. Give the run dialog the
  case editor's draft treatment. **The product already contains the fix** — the editor's silent
  draft capture plus its *"Your unsaved changes from 1m ago"* Discard/Restore banner is the model
  (this is r1's `E4`, which r3 closed as *satisfied*).

## Phase 5 — the first-run contradiction (item 4)

- **`R3-J1` · major** — a brand-new empty suite renders the *no-results* state and offers
  **"Clear filters"**, a button whose effect is to undo the suite. Give an empty suite its own state,
  distinct from no-results, carrying the three membership arms.
- **`R3-J3` · major** — **New case** launched from inside a suite defaults to **Unfiled**. Pre-fill
  Suite from the active `suiteId`.

Related and cheap while you are here: **`H5`** (an empty state asserts an invariant the same screen
disproves) and **`D5`** (two differently-labelled "create a suite" controls in one 260 px panel).

## Phase 6 — IA and navigation (items 8, 11, 14, 16, 20)

- **`UX19` · major** — the rail's `REPOSITORY` / `EXECUTION` group headings are two Spring Modulith
  **module names**: the server's boundary served as the user's mental model. Name the groups for the
  user's task, e.g. *Authoring* / *Running tests*.
- **`UX20` · major** — the company name appears in none of the 21 r1 captures, while the help page
  teaches Company as the first concept. Surface it in the header or as the breadcrumb root.
- **`C6` + `UX4` + `UX1` · major** — breadcrumb integrity, three independent failures: depth changes
  between siblings; "Not filed" is rendered as a segment and a link, fabricating a container the
  domain does not have; a non-existent project is asserted (the last is already fixed by
  `ProjectGuard` — verify, don't redo). One breadcrumb builder; the crumb ends at `Test cases` when
  a case has no suite.
- **`C3` + `C4` · major** — the rail orders scopes narrow-to-wide, inverting the product's hierarchy,
  and `COMPANY` sits entirely below an invisible internal scroll at ordinary laptop heights.
  `COMPANY` above the project selector or a scope switcher; collapsible groups, or move company
  scope into the account menu. (`A5`, the near-invisible dark-mode scrollbar, is the same area.)
- **`C1` + `H2` · major** — the user guide has four names and "Documentation" is two destinations on
  one screen. **Help** everywhere; the rail keeps **Documentation** for project pages.
- **`C2` + `J2`(iconography) · major** — "Audit trail" is two nav rows; "Access & roles" / "Teams &
  roles" share a label shape and a glyph. Scope-qualify the labels and split the icons.
  **Not verifiable live until an account holds both permissions** — say so rather than claiming it.
- Batch here: **`C7`** (`/help` highlights nothing in the rail), **`C8`** (project-scoped groups
  render on non-project routes), **`UX9`** (rail and page disagree on a destination's name),
  **`UX12`** (one work item renders identically on two surfaces with no stated relationship).

## Phase 7 — collections and responsiveness (items 10, 12)

- **`UX13` · major** — the surface with **29** filter controls has no saved views; the surface that
  needs them least has them. Saved views on the repository first, **then** collapse the filter bar
  behind them. **This supersedes r1 `E2`'s fix direction** — read both before starting.
- **`E2` + `F1` · major** — 29 always-expanded controls push the first case row below the fold at
  every width; at 900 px the rail plus suites panel take 55% of the viewport, leaving the grid
  ~330 px and hiding the Status column. Collapse the rail to icons and stack the suites panel at
  `md`, not only at `sm`.
- **`UX14` · major** — the board shows 2 of its 5 columns at 900 px with no scroll affordance, and a
  card can be sent to a column the user cannot see. Horizontal scroll with a visible affordance, or
  a single-column grouped list below `lg`.
- **`UX7` · major** — the two "browse a project collection" surfaces have incompatible shapes. One
  collection-page pattern, filters as a progressive layer.
- Batch: **`F2`** (cases grid clips columns at 1440 px too), **`UX15`** (the projects list spends a
  full-width card on two facts).

## Phase 8 — copy and empty values (items 18, 19)

- **`H1` + `UX8` · major** — four renderings of "no value", three on one screen; three different
  empty-region treatments across four surfaces. One `<EmptyValue>` and one `<EmptyState>` with an
  optional action. `EM_DASH` and `B4` are already done — build on them.
- **`C5` + `H3` · major** — read and write views of a case group the same fields under different
  headings, and a panel renames itself between read and write. One field-grouping model shared by
  both views.
- Batch: **`H6`** (a helper sentence does not parse), **`D6`** (a filter chip shows a count when
  non-zero and nothing when zero, so "0" and "unknown" are the same), **`D7`** (two card shapes,
  different drag affordances, no way to tell which is which), **`UX5`** (the work-item vocabulary
  collides with two existing status vocabularies — rename away from the reserved words).

## Phase 9 — charts, icons, and the remaining batch (items 21–25)

- **`I2` · major** — "Automation coverage: 0.0%" is stated against a denominator the caption
  excludes. Suppress the decimal below a minimum denominator, or show `0 of 1`.
- **`J1`(iconography) · major** — both Material weights are in use and nine glyphs appear in both.
  Pick **outlined** and convert the 42, in one sweep or not at all.
- **`UX18` · minor** (was `D3`, re-rated) — help for the eleven Health signals exists **twice**
  (`healthHelp` tooltips at `StatusChip.tsx:162`, and the guide) and is reachable from neither the
  filter bar nor the contents list. Carry `healthHelp` onto the filter chips; promote "Health
  signals" to a top-level guide section.
- **`D2` · major** — `Archive project` sits beside the project's primary action at equal weight with
  **no confirm step evidenced**. Move project lifecycle actions into project settings.
  (`UX10`, formerly `D1`, is minor: `Delete` is already confirmed and *soft* — separate navigation
  from writes from destructive, `Delete` and `Clone` into an overflow.)
- Remaining minors: `I3` `I4` `I5` `UX2` `UX17` `R3-J5` `R3-J8` `R3-J9` `R3-J14` `R3-J17` `E3`.
- Remaining nits: `A2` `A3` `A4` `A5` `G3` `H7` `J3`(iconography).

Each is one line in the backlog document with its fix direction; read them there.

---

## Rules that bind

- **Work on `master`.** Do not create a branch. Another lane keeps a worktree at
  `.claude/worktrees/m6-multi-company/` and there is a `217-phase3-wip` stash belonging to a
  notifications lane — **leave both alone.**
- **Stage by path** (`git add <paths>`), never `git add -A`.
- **Commit per phase** — but since the suite runs only at the end, a phase commit here is
  "compiles, lints, and is written"; say so in the message rather than claiming a green gate.
  The final commit after the suite run is the one that records gate evidence.
- **Push after the lane.**
- **No comments in code you write** beyond a short doc comment where the name genuinely cannot
  carry the meaning — but note that this repo's existing style is heavily commented with the *why*;
  match the file you are in.
- **New display logic goes in the shared layer**, not the page: `lib/labels.ts`, `lib/format.ts`,
  `lib/results.ts`, `lib/permissions.ts`, `DataRegion`, `Identifier`. A second implementation of any
  of these is itself a finding.
- **`openapi.yaml` is the contract.** If a fix needs a shape change, change the spec, regenerate
  **both** copies of `schema.d.ts` (`frontend/` and `packages/client/`), and adapt both sides in one
  commit.
- **§65 still applies:** browser passes are deferred and batched. Every phase appends what it would
  have driven to `docs/verification/frontend-browser-checks.md` with a stable id. A phase that lands
  without adding those rows has skipped the check.
- **Update `docs/agent-context/fe-react.md`** in the same commit as the work it describes.

## Out of scope — do not attempt

- **`D4`/`H4` backend half** — the server returns a body-shaped parse error for a malformed *path*
  parameter. The frontend half is done; the contract question belongs to `code-auditor`/backend.
- **`UX17`** needs correlation IDs on 4xx responses — backend.
- **Sign-in / accept-invitation**, **light mode**, and the **5xx/timeout/401/409 copy** are still
  unassessed; the last needs `tcmsFaults`, which is dev-only, so it needs a local seeded instance.
- **`--color-approved`** is byte-identical to `--color-passed` — B1's fifth vocabulary. Deliberately
  left: unlike the `COMPLETED` chip it has no demonstrated damage and would restyle every approved
  case in the product. Raise it, do not silently change it.
- A pre-existing palette defect found while fixing B1 and **not** in the audit: `wip #2563eb` vs
  `retest #7c5cbf` is ΔE 12.2 in normal vision, under the 15 floor. Report it; do not fix it here.

## Report at the end

Findings fixed by ID, findings deliberately not fixed and why, the single gate result
(build / lint / suite with counts), whether the `CaseSchema` flake appeared, and the browser-check
rows you appended.
