# 003 — Case authoring redesign: plan of action (fe · be · db)

**Status:** proposed, nothing implemented · **Date:** 2026-08-28
**Answers:** [`docs/specs/2026-08-28-case-authoring-redesign.md`](../specs/2026-08-28-case-authoring-redesign.md)
**Initiative number:** `003-`. `002-` is claimed by the `test_case.gherkin_source` deleter (§255),
and **`V70` is reserved for it**. ~~Any migration here starts at `V71`.~~ → **grounded 2026-08-28
against [`004`](004-issue-tracker-integration.md) and [`005`](005-renders-backend-support.md): this
plan claims no Flyway number.** `V71` is 005's (slice S3) and `V72`–`V74` are 004's. Phases 1–2
carry no DDL, and the only migration named here belongs to Phase 3, which is **blocked** — a number
held for a phase that may never ship is the hole 005 §8.3 warns about: 004's `V72` lands first, and
the held number is then refused on every already-migrated database with a green build
(`out-of-order` is unset; Testcontainers cannot see it). If Phase 3 unblocks it takes the next free
number at the time it is written.
**Neighbours:** 005 holds `openapi.yaml` first (S1 + S3), then Phase 2 here, then 004. The order and
its reasons are in *Sequencing*; the refusal shape Phase 2 writes against comes from 005 S1.

## Decisions taken, 2026-08-28

| # | Decision | Effect |
|---|---|---|
| **D1** | **Write only. The Fields lens goes.** The spec's open problem 1, answered by choosing its first uncosted alternative. | The row editor is deleted, not kept in agreement. See *The cost of D1* — it is the largest section here, and the render does not draw it. |
| **D2** | **`POST /projects/{projectId}/cases/render-gherkin` ships in Phase 2.** | Contract change, both generated copies, and the Write surface works on *New test case* and on dirty edits. |
| **D3** | **One prose line under the title, and the columns stay.** Per the render. | No migration. `description` and `expectedOutcome` stop being authored and keep being rendered where they have a value. |
| **D4** | **Every test is written here; CI executes them.** No manual browser pass. | See *Verification* — what CI actually runs is narrower than "CI", and two risks become unobserved rather than mitigated. Stated, not worked around. |
| **D5** | **Existing test cases are HARD-purged on the lab and prod environments.** Confirmed 2026-08-28: a real `DELETE`, not the product's soft delete. | A data operation, not a code change, and **irreversible**. See *Clearing the environments*: it takes the execution history with it, it has no operator verb, and it empties the corpus the redesign's blank state is built on. ~~**Two answers still missing before it can run.**~~ → both answered 2026-08-28 (scope: all project content; method: the SQL purge, never a volume drop); the operation now lives in [`docs/runbooks/2026-08-28-purge-project-content.md`](../runbooks/2026-08-28-purge-project-content.md). |
| **D6** | **Preconditions are authored as the `Background:` block at the top of the document.** | Moves a rail row into the text. Carries a permission consequence (`library:manage`) and an unresolved question about inherited and declined conditions — see *Preconditions as `Background:`*. |

**D1 contradicts the render**, which draws a `Write | Fields` toggle in both frames. That toggle is
now dead and comes out of the render before anyone builds from it.

**D3, settled.** The field under the title is **`objective`** — *"objective" and "description"
were the same thing said two ways*, confirmed 2026-08-28. `description` and `expectedOutcome` are
the two columns that stop being authored, and both keep being rendered read-only wherever an old
case has a value. No migration; the render's backend note is the rule — *"keep the columns, stop
authoring into them"*.

---

## What this plan changes about the spec

The spec says phases 1–2 need no backend and no data change. **Phase 2 needs backend.** Three
corrections, each traced to code, plus one that only exists because of D1.

### C1 — There is no `steps → text` path for a case that is not yet saved

`useCaseFeature` is `GET /projects/{projectId}/cases/{caseId}/feature`
(`frontend/src/api/queries/cases.ts:309`), `enabled` on a caseId. The Write surface must render
text for *New test case*, which has no id, and for an existing case whose form is dirty — where
that endpoint answers the **last saved** text.

Under D1 this is not a lens-switch inconvenience, it is the editor's only surface. Hence **D2**:
`POST /cases/render-gherkin`, symmetric with `parse-gherkin`, storing nothing, reusing
`GherkinScenarios.document` so client and server cannot disagree. The alternative — a second
renderer in the SPA — is the thing `featureFiles.ts` forbids, and it would drift into text that
silently does not match what saves.

### C2 — A client-side parser contradicts a standing rule

`frontend/src/features/cases/featureFiles.ts:16`: *"**No Gherkin parsing.** The one parser is
`GherkinScenarios` on the server (plan §6a rule 3), and `POST …/cases/parse-gherkin` exists
precisely so the SPA does not grow a second one."* Phase 2 adds a line scanner for colour and line
numbers. Defensible — colouring is not authority — but not silently:

1. `gherkinText.ts` carries a header saying it is a **presentation** scanner with no authority, and
   that the server's parse is what commits; and
2. the rule in `featureFiles.ts` is amended in place — *"→ amended by §003: a non-authoritative
   colouring scanner is allowed; a parser whose output is committed is not"* — per the working
   agreement on superseded decisions.

A `parse` endpoint that accepts a **fragment** would delete the scanner later. Follow-on, not
critical path.

### C3 — The text carries five of the sixteen fields

`GherkinScenarios.render` (`backend/…/repository/GherkinScenarios.java:611`) emits title, objective,
steps (with `# expected:` / `# data:` comments, docstrings and tables), automation tags,
preconditions as `Background:`, and postconditions as **comments the parser reads back as nothing**
— by design, stated at `GherkinScenarios.java:686`.

`caseSchema.ts` holds sixteen case-level fields. The text represents five: `title`, `objective`,
`tags`, steps, and preconditions. It represents none of `suiteId`, `priority`, `severity`,
`ownerId`, `lifecycleStatus`, `automationStatus`, `automationKey`, `notAutomatableReason`,
`estimatedDurationSec`, `classificationValueIds`, `postconditionIds`, `description`,
`expectedOutcome`.

So, unconditionally: **the commit from text is a merge over those five fields and never a form
replace.** §255's rule to carry — *a toggle between two representations of one thing may not delete
the thing* — applies whole, and under D1 there is no second representation to recover from. Two
traps in the same paragraph:

- The `Feature:` line renders the **suite name** (`GherkinScenarios.java:619`). Parsing it back must
  not touch `suiteId`. Render it as non-editable chrome rather than as a line the author can retype.
- `Background:` steps parse into library **`PRE` conditions to create** (AM1), which needs
  `library:manage`. An author without it editing a `Background:` block is refused **visibly**.

### C4 — Under D1, the parse refusal is the whole product

With the row editor gone, an author whose text will not parse has **no other way to save their
work**. `parse-gherkin`'s four refusals are the ordinary case, not a fault
(`CaseController.java:209`). The behaviour is therefore load-bearing and is specified, not left to
the component:

- The typed text is **never** replaced, reformatted or rolled back on a refusal.
- The server's sentence lands beside the text, on the line it names. **Grounded 2026-08-28: the
  line is not on the wire yet.** The four refusals carry a `type` per reason
  (`CaseController.java:264`) and the line only as prose inside `detail`; `Problem`
  (`openapi.yaml:8049`) has no structured line member. **005 S1 adds `Problem.line`** — nullable,
  set only by these four refusals — beside `permission` and `scope`, because S1 is the slice that
  reopens that schema's "one optional member" argument and it should be reopened once. The SPA
  reads `line` and never regexes it out of `detail`; until S1 lands the sentence sits under the
  text, unanchored.
- Save is refused while the text does not parse, and says which line — it does not save a partial
  parse and it does not silently keep the last good `steps`.
- The draft buffer stores **the raw text** alongside the payload, so a restore after a crash
  returns unparseable work exactly as typed rather than needing a server round trip to exist.

This is §258's rule as a design input: a refusal path that runs no code is no refusal path.

---

## The cost of D1 — retiring the Fields lens

The render draws a rail with six rows (Suite, Priority, Automation, Duration, Preconditions,
Classification). Eleven fields have to live somewhere and six of them are not in that rail.

### Fields that must find a home — decided

Settled by [`docs/specs/2026-08-28-case-authoring-rail.md`](../specs/2026-08-28-case-authoring-rail.md)
(the `ui-ux-pro-max` dataset, the same authority §258 was driven against). **Two fields needed a
home, not four** — an earlier reading of this section claimed `ownerId` and `lifecycleStatus` did,
and both were wrong.

| Field | Verdict |
|---|---|
| `suiteId` | **Rail** row 1, plus the non-editable `Feature:` chrome in the document |
| `priority` | **Rail** row 2 |
| `automationStatus` | **Rail** row 3 — a control, not a badge: it is required on every save and it gates two child fields |
| `automationKey` | **Rail**, inside the automation row's disclosure. Not its own row: an unbounded identifier cannot render whole on a 280px face |
| `estimatedDurationSec` | **Rail** row 4 |
| `preconditionIds` | **The document** — the `Background:` block at the top (D6). The rail keeps a read-only *inherited* count; see below |
| `postconditionIds` | **Rail** row 6 — **new, and the render draws no such row.** Gherkin emits them as comments the parser reads back as nothing by design (`GherkinScenarios.java:686`), so the text can never author them |
| `classificationValueIds` | **Rail** row 7, and **absent entirely** when every vocabulary is empty — finding 3 |
| `notAutomatableReason` | **Rail**, in the automation disclosure beside `automationKey` |
| `ownerId` | **Not the editor's.** The create path rejects it and the update path *refuses a change* (`CaseUpdateRequest.java:173`). It belongs to the detail page's ownership panel and the list's `SET_OWNER`. Putting it in the rail is new backend work, not a restoration |
| `lifecycleStatus` | **Not the editor's**, deliberately — `caseSchema.ts:11` states it: status moves through explicit review and approval actions, and a `<select>` would bypass the transition guards. It stays on `CaseDetailPage.tsx:568`'s `LifecycleMenu` |
| `severity` | **Remove the control, keep the mirror.** `severity: data.priority` (`caseSchema.ts:434`) and no frontend file reads it — but dropping it from the *payload* would leave the stored value stale, because `PATCH` is a merge |

**Seven rows, six without classification. The rail does not grow past what the render draws** — it
gains Postconditions and loses nothing.

### Preconditions as `Background:` (D6) — and the two things it drags in

The render already works this way and so does the contract: `render` emits
`bodies(view.preconditions())` as the `Background:` block (`GherkinScenarios.java:611`), and the
parser reads Background steps back as **one condition per step** (AM1). D6 makes that the authoring
path rather than an import artefact. The rail's Preconditions row stops being an editor.

**Two consequences, neither of them cosmetic.**

**1. Writing a `Background:` line needs `library:manage`.** A precondition is not free text — it is
a row in the project's condition library, attached by id (`preconditionIds` is an array of ids), and
creating one goes through `ProjectService#writeGranted` with `library:manage`
(`ConditionService.java:65`, `:188`). So under D6 an author who may write cases but not manage the
library **cannot type a `Background:` line**, in a surface where typing is the only interaction.
This is worse than it was: the row editor put condition creation behind a visible dialog, where a
refusal was legible.

The Write surface must therefore refuse *at the line*, not at save, and say which permission is
missing — and for a caller without the grant the `Background:` block is read-only chrome rather than
an invitation. **This is now the most likely way an author gets stuck**, and it needs an arm.

**2. The block already contains inherited conditions, and the text cannot say so.**
`TestCaseDetailView:66` — *"inherited entries first, marked"* — and `bodies()` takes only
`CaseConditionRefView::body`, so **the marking is lost in the text**. A case also carries
`declinedPreconditions` (§187): inherited entries this case has explicitly turned off, which have no
Gherkin spelling at all.

So a `Background:` block shows suite-inherited and case-own lines identically, and deleting a line
is ambiguous — detach an own condition, or *decline* an inherited one? Three options, and this plan
does not pick one:

| | |
|---|---|
| **(a) Own conditions only in the block; inherited stay in the rail** | Unambiguous, and the reason the rail keeps a row. But the rendered `.feature` stops being what a tester actually runs — the thing §255 built the renderer to be. |
| **(b) Both in the block, inherited as `#` comments** | The text stays complete and honest. Deleting a comment line is a decline. Needs a comment convention the parser learns — backend work. |
| **(c) Both as plain steps, deletion of an inherited one = decline** | Reads best, and it is the option where a fat-fingered deletion silently opts a case out of a suite-wide precondition. |

**Decided 2026-08-28: (a).** Own conditions in the block, inherited stay in the rail. It is the
only option that ships without new parser behaviour, and the only one where no keystroke can
silently opt a case out of a suite-wide precondition.

**The cost is real and goes on the screen, not just in this file:** the rendered `.feature` omits
inherited setup, so it stops being exactly what a tester runs — which is what §255 built the
renderer to be. The `Background:` block carries a line saying so, and the rail's inherited count
links to what is missing. Revisit (b) — inherited as `#` comments, deletion meaning decline — when
the fragment parser lands, since that is the follow-on that makes it cheap.

**Postconditions are unaffected and stay in the rail.** Gherkin has no construct for them; the
renderer emits them as trailing comments the parser reads back as nothing, by design
(`GherkinScenarios.java:686`).

### The rail is an editing surface, and the render's label is wrong

The render calls it *"a read-only summary you click to change"* and builds the rows as `div.kv`
spans (`case-authoring-redesign.html:212`). That is UX#117 *Compact Control Semantics* — Critical,
*"don't use a clickable div"* — with UX#62 (*inputs that look like plain text*) and UX#114 beside
it. Every row is a real focusable control with a native role, quiet at rest and obvious on hover and
focus.

**The failure this prevents:** the `NOT_AUTOMATABLE` refusal landing on a field inside something the
author reads as a summary. And `ui-reasoning.csv` row 16 makes keyboard shortcuts a *must_have* for
a productivity tool — a rail nobody can Tab into fails that outright.

**Row count is not the constraint; value width is.** There is no row-count rule in the dataset and
none was invented. What bounds the rail is that only short enumerable values sit on its face;
unbounded ones — the automation key, the condition lists, the classification chips — render as a
count plus a disclosure.

**A properties sheet was considered and rejected.** At 1280px the document column is already ~700px
against a 65–75ch readable measure of ~640–700px, so the rail is not stealing width the document
would use (arithmetic unverified — no browser has been driven); and a conditionally-required
field's error cannot live behind a closed surface. Phase 1's corpus panel needs the rail anyway, so
a sheet would add a third surface rather than remove a second.

### The blank state needs the rail too — and a bug is in the way

Frame B draws no rail. A new case still needs a suite, so the rail is present with defaults and the
Suite row prefills from context.

**That does not work today.** `CaseListPage.tsx:98` and `:247` navigate to `/cases/new` carrying no
suite even when one is selected — UX#106, *Redundant Entry*. A live defect, not a design question,
and Phase 1 fixes it since Phase 1 owns the blank state.

### The render needs redrawing in four places

Before anyone builds from it: delete the dead `Write | Fields` toggle; redraw the rail rows as
controls and drop the *"read-only summary"* caption (`:314`); add the Postconditions row; give frame
B a rail and take `@suite:refunds` out of its tag row (that syntax is blocked Phase 3).

### Code that is deleted, and its tests

`SortableStep`, `StepActionField`, `StepKeywordCell` (`CaseEditorPage.tsx`), `StepDataCell.tsx`,
`stepReorder.ts`, and the drag-and-drop sensors. `CaseEditorPage.tsx` is 2105 lines and most of the
step machinery goes with them. Check whether `@dnd-kit` is still used elsewhere before dropping the
dependency, and **measure the bundle rather than assuming a win** — the working agreement's
DataGrid lesson.

The three step-data modalities survive as text: a docstring is `"""`, a table is `|…|`, and
`testData` is the `# data:` comment. `V69`'s `num_nonnulls` check still enforces "exactly one" on
the server, so the parse cannot produce a row it refuses.

### The population D1 cannot ignore — and D5 does not remove

A case with **no step rows and a stored `gherkin_source`** renders that blob **verbatim**
(`GherkinScenarios.java:611`) — the un-migrated population §255 left behind, with `002-` named as
its deleter. Under the Fields lens those cases were editable as rows; under D1 the only editor is
one that parses.

**D5 does not delete this branch from the code.** It clears two environments. §255's own note says
the `migrate-gherkin-source` gate is operational *"on every instance"*, and this is a self-hosted
product — an operator's instance is not reachable by a `DELETE` run against ours. So the refusal
stays:

- The Write surface refuses to save such a case until it round-trips, and says so — the same
  sentence `migrate-gherkin-source` exists to make unnecessary. It does not silently reparse
  somebody's blob into rows on their first edit.

What D5 does buy is that **no case on lab or prod will exercise it**, so the arm is a unit test and
never a support incident on our own two boxes.

### What is honestly lost

Authors who do not write Gherkin lose their editor in one release, with no fallback if the document
surface disappoints at real content length. That is the trade D1 accepts; it is recorded here so
the reversal, if it comes, is not read as an oversight.

---

## Phase 1 — empty vocabularies, the blank state, the standing corpus

Fixes findings 3 and 4. **Frontend only. No contract change, no backend, no migration.**

| Change | File |
|---|---|
| A dimension whose `values` is empty renders **no control**. One sentence and one link to Classification, once, for all empty dimensions together — the render's *"Nothing configured, nothing shown"* panel. | `ClassificationPicker.tsx`, replacing the per-dimension copy at `:145` |
| The picker gains an `emptyNotice` slot so the page owns the sentence and the picker stays a control | `ClassificationPicker.tsx`, `CaseEditorPage.tsx` |
| *New test case* renders three starts: **Write it**, **Start from a case** (most recent by `updatedAt` from `useCases`), **Paste a `.feature`** (`PasteGherkinDialog`, promoted out of its button) | `CaseEditorPage.tsx` |
| A standing corpus panel in the rail — *"steps this project uses"* — ranked by use count, insert on click | `CaseEditorPage.tsx` |
| `Given / When / Then` ghost text on an empty case | `CaseEditorPage.tsx` |
| **Carry the selected suite into New case.** `/cases/new` is navigated to with no suite even when one is selected — UX#106 *Redundant Entry*, a live defect. Phase 1 owns the blank state, so it fixes it. | `CaseListPage.tsx:98`, `:247` |

### The corpus panel needs no backend — checked

A blank `q` is already the read the panel wants. `StepSuggestionQuery` normalizes blank to `null`
and `needle()` answers `'%'`; the service caps at `MAX_SUGGESTIONS = 50` as its own decision, with
no `limit` on the contract (`StepSuggestionService.java:47`); and `TestStepRepository.java:113`
already orders `COUNT(DISTINCT caseId) DESC, action ASC` — which is *ranked by use count*.

**Do not relax `useStepSuggestions`'s `enabled: q.trim().length > 0` gate.** Its header
(`api/queries/library.ts:65`) argues for it: eight mounted step rows would fire eight reads of a
thousand-row list. Add a separate `useStepCorpus(projectId)` with its own key
(`keys.stepCorpus`), `staleTime: 60_000`, and **one mount per page** in the rail. Amend the
`enabled` comment in place to name the new hook, so the next reader does not think the rule was
forgotten.

### Tests (written here, run in CI)

- `ClassificationPicker.test.tsx` — all dimensions empty; some empty; none empty.
- `CaseEditorPage.test.tsx` — the three starts render on `create` and not on `edit`; *Start from a
  case* prefills from the most recent; the corpus panel inserts a step on click; the corpus read
  fires **once** per page mount, not once per step.
- Contract conformance is automatic: every fetch double is compared against `openapi.yaml`.

**Gate:** `frontend` build + lint + `test:run`. Commit at the end of the phase.
Independently revertable, and it is the phase that fixes two findings on its own.

---

## Phase 2 — the Write surface

### Backend

0. **Start from 005's landed contract.** S1 and S3 are merged and green before this phase opens
   `openapi.yaml`; re-run `npm run gen:api` in both places against it and take the output
   wholesale — never hand-merge `schema.d.ts` (`parallel-work-rules.md`, 005 §8.5). The C4
   refusal bodies are written against the S1 `Problem` (`permission`, `scope`, `line`), not the
   current one.
1. **`POST /projects/{projectId}/cases/render-gherkin`** — `consumes: application/json` (the
   `CaseUpdateRequest` shape the editor already holds), `produces: text/plain`, body is
   `GherkinScenarios.document(...)` over it.
   - **Read gate**, like `parse-gherkin`: it writes nothing, so it takes no write permission and
     answers the same bodiless 404 for a caller with no grant.
   - The `Feature:` name resolves from `suiteId` server-side, falling back to the case key, so an
     unsaved case renders the header a saved one does.
   - The same 256 KiB cap and the same `payload-too-large` Problem as `parse-gherkin`.
2. `docs/specs/openapi.yaml` — the path, and **`required` on every schema and every inline nested
   object**; `required` does not descend.
3. Regenerate **both** copies: `frontend npm run gen:api` and `packages/client npm run gen:api`.
4. `@tcms/client` gains the wrapper. CLI/MCP only if
   `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` calls for it — **default no**.

**Backend tests:** `render(request) → parse → render` is stable, sitting beside
`everyConstructSurvivesTheRoundTrip` — which §255 established is an *idempotence* check, not a
fidelity one; do not restate its claim. Plus the read-gate arm and the size refusal.

### Database

**None.** No column, no index, no migration. Nothing in this phase persists anything new.

### Frontend

New: `features/cases/CaseDocument.tsx`, `features/cases/gherkinText.ts`. `CaseEditorPage.tsx` loses
the row editor (see *The cost of D1*) and keeps the header, the commit bar, the draft machinery and
the rail.

- **The text is the source of truth.** `steps` are derived. Commit on blur and on save. Never
  re-render the textarea from parsed state per keystroke — that is what jumps the caret.
- **The commit merges five fields** (C3) and touches nothing else, ever.
- `gherkinText.ts` is a colouring scanner — keyword, sentence, docstring, table — with the header C2
  requires. It never commits.
- Mirrored `<pre>` behind a transparent `<textarea>`, sharing font metrics, padding, wrapping and
  line-height. **Unmeasured** — see *Verification*.
- **Corpus completion under the caret — a search, and nothing more.** `useStepSuggestions`
  unchanged: `LIKE '%fragment%'` over `test_step.action`, lowercased and escaped
  (`StepSuggestionQuery.needle()`), ordered `COUNT(DISTINCT caseId) DESC, action ASC`
  (`TestStepRepository.java:113`), capped at 50, same 250 ms debounce, same typeahead budget. The
  render's *"41 cases"* is that `COUNT`. **No backend work.**
- **One mirror, not two.** The row editor got its popup position free from MUI's `Autocomplete`,
  which anchors to a real input. A textarea has nothing to anchor to, so the caret's pixel position
  has to be computed — by a hidden mirror with identical font metrics, which is the **same
  technique as the syntax-colour `<pre>`**. Build it once and read both the colouring and the popup
  position off it. Two mirrors will drift and the popup will land a line off the caret.
- The icon rail collapses **on this route only**, not globally.
- The refusal behaviour of C4, exactly as written there.

### Tests (written here, run in CI)

- `gherkinText.test.ts` — the scanner, pure, per construct.
- `CaseDocument.test.tsx`:
  - a commit from text leaves `expectedOutcome`, `description`, `priority`, `suiteId`,
    `classificationValueIds` and `postconditionIds` untouched — **the C3 arm, and the one that
    matters most**;
  - a parse refusal preserves the typed text byte for byte and refuses Save with the line named;
  - a `Feature:` edit does not change `suiteId`;
  - a `Background:` edit without `library:manage` is refused visibly;
  - a case with `gherkin_source` and no steps is refused with the migration sentence;
  - render → edit nothing → save leaves a case with postconditions and a docstring byte-identical.
- The harness rules apply: no hand-built `QueryClient`, and `renderingIntoAnErrorBoundary()` /
  `drivingAFailedWrite()` where a test trips one on purpose.

**Gate:** all three stacks — the contract moved, so `packages` is in scope.

---

## Phase 3 — metadata as tags · **BLOCKED** (stub)

Gated on the spec's open problem 2 (`@suite:refunds`, `@priority:medium` as namespaced tags), which
this plan does not solve. **Cut to a stub 2026-08-28**: it claims no Flyway number, ships nothing, and
its territory overlaps 005's deferred N14 (feature-import per-step diff) and N16 — so it is revisited
with those, not on its own. The two facts worth keeping in front of whoever reopens it:

- Four validation targets hide behind one syntax — free-text `case_tag`, the `suiteId` foreign key,
  the CHECK-constrained `priority`, and per-project classification rows — so `@priority:urgnet`
  needs a server-side refusal per unknown namespace and per unknown value, plus a `case_tag.tag`
  check and a backfill decision (the migration takes the next free number when written, never `V70`).
- Render emits `view.automationTags()`, not the case's tags (`GherkinScenarios.java:621`), and
  `feature-imports` matches `imported_source` against the rendered text — **metadata in the text
  could make every imported case report as changed.** That is the N14 overlap.

The tag row in the render ships in Phase 2 as **today's free-text tags**. The namespaced syntax does
not.

---

## Clearing the environments (D5) — pointer, and what lands here

**Moved 2026-08-28** to [`docs/runbooks/2026-08-28-purge-project-content.md`](../runbooks/2026-08-28-purge-project-content.md)
beside its script: it is *not a code change and not part of any phase*, and it was a third of this
document. What the runbook holds: why D5 is not the product's soft delete; the two foreign keys that
make a case purge a purge of the execution history; the settled scope (all project content — companies,
projects, accounts and vocabularies survive; the SQL purge, never a volume drop, because
`app_user.id` is the JWT subject); the kept/purged split of all 83 live tables, derived by script; the
three traps (keep one suite per project, reset `key_sequence`, clear `tcmsblobs`); order; and
`export-company` first. Nothing has been run; the script ends in `ROLLBACK`.

Two consequences land on this plan and stay here:

### The consequence that lands on the redesign

**The step corpus is derived from live cases.** `TestStepRepository.java:113` counts steps across
the project's cases; there is no curated table behind it — §239 retired the one there was.

So on any environment where the cases are cleared, `step-suggestions` answers **nothing**. The
render's *"312 known steps"* panel is empty, the completion under the caret offers nothing, and
finding 4 — *the blank state teaches nothing* — is back on day one, for the exact reason the
redesign named it.

That is not an argument against D5. It is two requirements it creates:

1. **The corpus panel needs a real empty state**, and it must read as *"this project has no steps
   yet"*, never as a failed read. `DataRegion`'s empty arm, with a sentence that says the corpus
   fills itself as cases are written.
2. **Order the work — decided 2026-08-28: purge first, then Phase 1.** The corpus panel and the
   three starts arrive into an already-empty instance, so *"no steps yet"* is the honest first
   impression rather than a regression somebody reports. It also means the empty-state branch — the
   one most likely to be wrong — is exercised from the first day rather than the day the data goes.
   The order that was rejected is the middle one: shipping the standing corpus panel in the same
   week the corpus is emptied.

### What D5 does buy this plan

If the lab and prod cases are gone, then on those two boxes: no case carries a legacy
`expectedResult`, none carries a `description` or `expectedOutcome`, and none is in the
un-migrated `gherkin_source` population. **None of that removes the code branches** — this is a
self-hosted product and §255's backfill gate is stated as running *"on every instance"*, so an
operator's data is not reachable by a delete against ours. The branches stay; what changes is that
our two environments stop exercising them.

---

## Verification — what D4 actually buys

Every test above is written here. What executes them, precisely:

| Lane | On push to master | Nightly (`tests.yml`) |
|---|---|---|
| Backend | **compile only** — `./gradlew build -x test` | full suite |
| Frontend | **smoke only** — `vitest.smoke.config.ts` excludes `src/features/**` | full suite |
| Packages | build + lint + full tests + `pack:release` | — |

**Both of this plan's test files land in lanes that no push runs.** `CaseDocument.test.tsx` and the
Phase 1 arms live under `src/features/cases/`, which the smoke config excludes by name
(`CaseEditorPage.test.tsx` alone is 372s of 943s). The `render-gherkin` backend arms are in the
suite `ci.yml` skips with `-x test`.

So under D4, after each phase: **`gh workflow run tests.yml`**, and read it before calling the phase
done. Without that, a phase can be merged with its own tests never having executed once.

**Two risks are now unobserved rather than mitigated**, and neither is a test failure that will tell
you:

1. **The mirrored `<pre>` at real content length.** jsdom has no layout — §258 recorded that two of
   its own browser rows exist for exactly this reason. No test in this plan can see the colour drift
   off the text on a fifty-step case, or the surface at 1280px. If it disappoints, the fallback is
   monospace with a line-number gutter and no colour, which fixes finding 1 on its own and carries
   no layout risk.
2. **Ten browser rows stay owed** — `§256 GK-1…GK-5`, `§258 CE-1…CE-5` — and D1 deletes the screen
   half of them were written against. Strike those as superseded rather than leaving them to look
   open.

---

## Sequencing

> **Build order superseded 2026-08-28** by [`006-build-batches.md`](006-build-batches.md): one operator, sequential batches by agent and by files touched, one contract wave, gates run once at the end. The table below remains the record of *why* the pieces depend on each other.

**Sequential.** Phase 1 → Phase 2. Phase 3 does not start.

**Across the three plans, grounded 2026-08-28** — one order, each step with the reason it cannot
move:

| | Step | Why it sits here |
|---|---|---|
| 1 | **The D5 purge** (lab, then prod) | Decided above: the corpus panel and the three starts arrive into an already-empty instance. A data operation with no code dependency, so nothing waits on it and it waits on nothing. |
| 2 | **005 S1 → S3** | S1 is one contract wave (the `Problem` shape — `permission`, `scope`, `line` — the C4 refusals are written against; the free wire fields, among them `TestCaseSummary`, which `CaseListPage` — a Phase 1 file — renders; the two doc corrections), so 003 regenerates once. S3 lands `V71`, after which no number claimed by a neighbour can be refused. 005 §8.3 states the same constraint from its side. |
| 3 | **Phase 1** | Frontend only. Could technically precede S1, but S1's `TestCaseSummary` change would then reach `CaseListPage` twice. |
| 4 | **Phase 2** | Holds `openapi.yaml`. Regenerates from 005's landed contract; never merges the generated files. |
| 5 | **004 Phase 0 → 3** | Takes the contract after Phase 2 releases it. Its Phase 3 links from `CaseDetailPage` and writes `case_doc_link` — the case surfaces Phase 2 is reshaping — so it goes after, not between. Its `V72` cannot be written before 005's `V71` has landed (step 2). |
| — | **005 S5–S10** | 005 §8.3 allows interleaving; **recommended not to** — every one touches the contract, so each interleave is another regeneration for the plan in flight. After 004 unless product priority says otherwise. |

Per `docs/specs/parallel-work-rules.md` and the project's standing rule: **one subagent at a time,
and ask before a second.** Phase 2 holds `openapi.yaml`, so no second lane may touch the contract or
a Flyway number while it runs. This plan claims no Flyway number (header), so it never holds one.

**Commit at the end of each phase**, staging only that phase's files. Work continues on the current
branch.

## What this plan does not claim

Nothing is built. The document surface is unproven at real content length, D1 removes the fallback
that would have absorbed that, and the honest phrase for what Phase 2 will produce until someone
opens it in a browser is **built and unverified**.
