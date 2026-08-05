# §97 — The test plan detail becomes one form, and a plan stops being a document

**Date:** 2026-08-05 · **Plan:** `docs/plans/2026-08-05-test-plan-detail-rework.md`
**Status: BUILD COMPLETE — EIGHT COMMITS, BUILT AND UNVERIFIED.** `eb548d2` (1), `31bd6a9` (2),
`923086c` (3a), `75cc7f9` (3b), `f0279de` (3c), `4b582ec` (4), `1b398fd` (5), `723ab2e` (6), plus
this one. Every gate is green and **not one queued check has been run**, per §65: the M3 lane of
`docs/verification/frontend-browser-checks.md` carries 51 rows, of which this slice wrote or
restated 32, and the backend rows added by 3a/3b sit in `backend-mutation-checks.md` unrun.

**`V32`** (the head; `V33` next free) — no new table, so the **RLS policy count stays at 61**.
Contract: `scopeText` / `outOfScopeText` on the plan, `PlanSection`, `GET /projects/{id}/tags`,
`PUT`/`DELETE` on the plan's document, the template `POST` **retired**, and the `status` enum
narrowed from five values to three. Frontend **834 → 915** across 65 files; backend counted at its
own phases.

## 0. What this slice is

§93 shipped a plan's narrative half as a Document tab: one flat list of text blocks, each saving on
blur, with an "Add a test case" bar under it that required a suite and created the case on the
click. The user reviewed it against a real test plan and gave four corrections, then restated what a
test plan *is* — and the restatement is the slice:

> A test plan outlines what must be completed for a feature to be considered done. It consists of
> name, business requirements (link to a document and displaying the document itself), test
> artefacts, Scope — free text, Out of scope, then a test case section split into sections by
> sub-feature. In each sub-feature the user can add a new test case (title only) **or** select
> existing ones by id or name, **or** add an entire suite, **or** add by feature tag.

That is **six semantically-typed sections, not a document with headings** — and four of the six
cannot be blocks in a page at all. So the plan detail became **one form with seven fixed tabs**
(Progress · Plan · Business requirements · Test artefacts · Scope · Out of scope · Test cases), one
Save, and one draft above them.

## 1. The decisions, and the two that were reversed in flight

| | Decision | Where it landed |
|---|---|---|
| **D1** | ~~Tabs derived from the document's level-1 headings~~ | **Superseded.** Four of seven tabs can never be blocks, so a deriving mechanism produces one seventh of the strip and hardcodes the rest. Deriving nothing is simpler than deriving one seventh — and D1's own failure mode (rename a magic heading, strand a control) is removed by removing the mechanism. |
| **D2** | A new case takes **no suite** — not a default, not a hidden picker: none | §96 made `test_case.suite_id` nullable; this is the slice that spends it. |
| **D3** | **One Save for the entire plan** | Phase 4's spine. |
| **D4′** | Business requirements is a **linked** page; the template is **retired** | Phases 1, 3c, 5. |
| **D6** | Scope / Out of scope are **columns**, not headings | `V32`; removes a third of D3's risk by collapsing two of three writes into the plan's own `PATCH`. |
| **D7** | ~~Sections are labels expanded at add time~~ | **Superseded by the TestNG validation.** See below. |
| **D7′** | Sections are **live selectors**, resolved at read time | `plan_scope_item` gains `section_name`, `ordinal` and a `tag` arm. |
| **D8** | "feature tag" means §96's `case_tag` | Forces `GET /projects/{id}/tags` into scope. |

**D7 → D7′ is the interesting reversal and the user made it, not the code.** D7 feared a *third*
copy of §96's resolution predicate in a *third* module. The fear did not survive contact with the
code: `CaseFacts` already lives in `com.tcms.execution` and already owns `casesInPlanScope`, so live
sections extend a method that exists, in the module that already holds the seam — the count of
copies stays at **two** and `SuiteScopeAgreementTest` keeps them honest exactly as it already did.
What decided it was that **TestNG's `<test>` block is this exact level**: a named subdivision
carrying its own class list and its own group filter, re-resolved on every run. The user validated
the whole model against TestNG and this is the one place the two disagreed. The analogy won.

Its second-order benefit is worth naming: the plan had a whole section (§5a) managing the
awkwardness of a *live* suite reference sitting beside *snapshotted* sections. D7′ **dissolved the
question rather than answering it** — everything resolves at read time, so there is one behaviour on
the screen instead of two, and the two-group rendering that §5a called for was never built.

**The accepted cost, stated plainly:** a plan's contents can change between approval and execution.
Tag a case `checkout` and it joins every section claiming that tag. That is narrower than it first
looks — §82's `requireApprovedCaseForRun` resolves at *run-create* time over the resolved set, so an
unapproved case arriving by tag is still refused at the moment it would run. What goes stale is the
**plan's own approval**, and that is a sentence on the screen rather than a mechanism.

## 2. M7 rides along, and the reason is that the tab renders the ladder

The lifecycle reduction (`docs/plans/2026-08-05-organization-model-restatement.md`) is not
plan-detail work and the instinct is to give it a slice. It landed in phase 3a because **§97's Plan
tab renders the lifecycle**, `V32` was already being written, and building the tab against five
states and then cutting to three is the same screen written twice.

**`DRAFT → IN_REVIEW → APPROVED`, and that is the whole of it.** `ACTIVE` and `CLOSED` described a
plan being *run*, which `test_run` already does. Gone with them: `test_plan.closed_at`, the
closed-plan freeze on `PATCH`, §82's entire *plan* gate with its `project_settings` column and
`PlanStatusInput.overrideApprovalGate`, `PlanDeleteService`'s delete-or-close partition, and three
audit verbs. **The migration maps every `ACTIVE`/`CLOSED` row to `APPROVED` inside a per-company
loop**, because `test_plan` is under `FORCE ROW LEVEL SECURITY` and a bare `UPDATE` in a migration
touches zero rows and reports success — V7's shape, the defect that got past 171 green tests.

**Lossy and not reversible**, and the reader has to be told where the history went: the audit log's
`plan.started` / `.reopened` / `.closed` events survive on any deployment that wrote them and are
now the **only** record of which releases were executed.

## 3. The backend half, in one paragraph each

**`V32`** adds `scope_text` / `out_of_scope_text` to `test_plan` and `section_name` / `ordinal` /
`tag` to `plan_scope_item`, and widens `ck_plan_scope_target`. That constraint is **deliberately not
a three-way exactly-one**: an empty section is stored as a row that names the section with all three
targets null — an author names a sub-feature before filling it, and dropping empty ones on save
would delete the heading they just typed. So the rule is *at most one target, and a targetless row
must name a section*, and it has its own assertion rather than being trusted to review.

**`CaseFacts.scopeMatch()`** is the case-or-suite-**or-tag** branch written once and interpolated
into all four plan expansions. Before §97 it was two arms copied three times; the third arm is where
copying stops being survivable. **Every arm is guarded by its own `IS NOT NULL`** — an unguarded
`i.tag = pt.tag` against a NULL tag is *null*, not false, which is §96's three-valued trap arriving
from the other direction.

**A section's name is its identity.** Storage keys membership on `section_name` and there is no
section id, so `sections` present replaces the whole list and two sections of one name are a **400**
— saved as two, they read back as one, silently merged. That single fact propagates all the way to
the client, twice: it is why `planProblems` refuses a duplicate name before Save, and it is why a
rename has to move the draft's unsaved rows (§6).

**A plan adopts a page; nothing creates one.** `PUT .../document` writes the page's
`subject_type` / `subject_id` and **nothing on the plan** — hence `doc:edit`, not `plan:edit`, the
same act seen from the other end — and `DELETE` clears them and **keeps the page**. Three refusals,
three different things: the page is another plan's document (409 naming it), this plan already has
one (409, because relinking is deliberately two steps), the page is a sibling project's (**404**,
never 409, or the answer confirms a page the caller cannot see exists). `DocPage.subjectType` /
`subjectId` therefore stop being `updatable = false` — restore that annotation and every link
answers **200 with a rendered page while the row never changes**, because Hibernate omits the
columns from the UPDATE and the view is built from the in-memory entity.

## 4. The form spine (phase 4) — three decisions that decide whether a save is correct

**The draft lives above the tabs, and that reverses §93.** That screen unmounted the inactive tab,
arguing a draft surviving a tab switch is one the author cannot see they made. Under one Save the
argument inverts — unmounting *discards* what was typed — so the state is hoisted and **the banner
names which tabs are dirty** instead. The old argument was right about the risk and wrong about the
remedy. `PlanForm` is mounted on **every** tab including Progress, which is what makes that true
rather than claimed.

**Comparisons run against the seed, never the live query.** A background refetch would otherwise
change what "dirty" means under a typing author — a field they edited could stop being dirty because
somebody else saved the same value — and the `lockVersion` they save against would silently become
the newer one, which is optimistic concurrency switched off at exactly the moment it matters. Held
this way, a concurrent edit is a **409 that says reload**.

**`planPayload` sends only the dirty tabs' fields.** Every list on `PATCH /plans` is three-state —
absent leaves it alone, `[]` clears it — so a whole-draft payload is a full replace dressed as a
patch, and would empty whatever half of the scope the form could not display. `PlanEditDialog`
avoided that by hand, one field at a time; here it is computed and unit-tested.

The save is **two endpoints in a fixed order**: `POST /cases` ×N, then the `PATCH` carrying the ids
just created. A case that exists outside a plan is findable and re-addable; a scope entry naming a
case that was never created cannot exist. **Three outcomes, not two** — `saved`, `casesFailed`
(stopped partway, names the row), `planFailed` (**the cases are loose in the project** and are named
by key, or the author presses Save again and cannot account for them).

**`useCreatePlanCase` is the third idempotency shape in `cases.ts` and the key is the ROW's.**
Per-hook (`useCreateCase`) makes the server read rows 2…N as replays of row 1 — five titles typed,
one case created, four scope entries pointing at it, and nothing on screen that looks wrong.
Per-call gives up the retry protection a two-step save needs most.

## 5. The tabs (phase 5) — two writers become one, three times over

**`PlanEditDialog` is deleted, not left compiling.** Every control it carried is on the Plan tab,
bound to the draft — and the dialog would have been the *divergent* second implementation, since it
wrote its own `PATCH` on its own lock version while the form writes an ordered pair against the
seed. The header's Edit button went with it. `PlanEdit.test.tsx` was **rewritten rather than
deleted**: every assertion in it is about the *payload* (`caseIds` absent rather than `[]`, no
`met`/`actual`, the number coercion, the derived label), and the payload outlived the dialog.

**Two refusal paths, and keeping them apart is the design.** `planProblems` is what the client is
certain about on its own — a blank plan name, a nameless section, two sections of one name — and
disables Save **with its reason and the tab holding it**, because an author who cleared the name on
Plan and typed prose on Scope is otherwise met by a button that does nothing. Everything else is the
server's, arriving as `fieldErrors` and landing on the control it names; the two criteria lists have
no text field of their own, so each renders its own alert rather than letting an unknown metric
vanish.

**The requirements tab stopped being an editor.** One page, one writer: prose is written at
`/docs/{pageId}/edit` under `doc:edit`, and a second editor here would carry its own lock version
and its own idea of what a block list is. What the tab offers instead is the way *to* that editor,
plus adopt and release. The picker **offers** a page another plan documents and disables it with the
reason — filtered out, it reads as deleted and the reader goes hunting in the tree.

**The Add-a-case bar is gone with `useCreateDraftCase`, its only caller.** It contradicted three of
this slice's decisions at once: it required a suite (D2 says none), it created the case on the click
(§5 says nothing is created until Save), and it filed the plan's cases as bullets in a document
rather than in the plan's own scope. The key-per-submit argument is kept as a struck-through comment
because the shape recurs.

**A window opened and closed inside one phase, and it is recorded rather than hidden:** deleting the
dialog took the suite picker with it, so between phase 5 and phase 6 a plan's suite scope could be
read and not changed. Deliberate — a folder list on the *Plan* tab would be a second place to write
one column, one phase before the tab that owns it landed.

## 6. The Test cases tab (phase 6) — everything is a reference

A group — the unsectioned one, then the sections in author order — holds **case ids, suite ids and
tags, never cases**, and its membership is a query the server re-runs on every read. So the screen
shows what *defines* a group and puts the count beside it rather than instead of it.

**The rename defect, found while writing the helper rather than after it.** `NewCaseRow.section` is
the section's *name*, because that is what the server keys a section by. A rename that touched only
the section list would strand every row authored under it: they would still name the old heading,
and `planPayload` would file their cases in the **unsectioned** group — the case the author put
under "Navigation" landing outside every section, with nothing on screen saying so. `renameSection`
migrates the rows and has the unit test that names it.

**Deleting a section asks what happens to its references** (keep them in the plan · remove them from
the plan) instead of guessing. The two answers differ by whether those cases stay in the plan at
all — an author tidying headings means one, an author cutting scope means the other — and the plan's
total moves in one case and not the other, on a screen where the counts deliberately do not sum.

**A served count is hidden the moment the draft moves.** Every count here is the server's arithmetic
over the references *it* holds; left on screen beside a section the author has just filled it is
wrong in the direction of the work they just did. An alert says it is recomputed on save, which is a
smaller claim and a true one.

**The new-case rows are §94's step list one screen over**: the group always ends in a blank row,
typing in the last appends the next while the caret stays where it is, and **nothing is created
until Save**. `ensureTrailingBlank` returns the **same object** when the invariant already holds,
which is what lets `PlanForm` restore it from an effect without looping — it has to be restored in
three situations and only one of them is the first render (a save reseeds, Discard reseeds, a
section can be added at any time).

**The counts do not sum, and the tooltip says so.** A case can be named directly in *Create* and
arrive through a suite in *Navigation*; the plan's total dedupes and the sections' do not. §96 paid
for this lesson one slice earlier: a reader who can subtract will report a bug in a correct number
unless the screen says which is which.

## 7. Things that had to be checked rather than assumed

- **The mock had to move with the contract, a fourth time** (§52, §81, §87). Its `PUT` answers
  **both** 409s as different sentences, because a mock with one generic conflict lets a client ship
  one message for two situations; its `DELETE` clears the subject columns and keeps the summary in
  the tree, or an unlink would look like a delete in the one environment the screen is developed in.
- **A captured DOM node goes stale across a permission verdict, and it cost an hour.**
  `PermissionGate`'s denied branch wraps its control in a tooltip span, so when the answer lands
  React mints a **new** button and `waitFor(() => expect(captured).toBeEnabled())` waits for ever on
  a detached one. It reads exactly like a gate stuck pending. Re-query inside `waitFor`. Its sibling
  cost three first-run failures in phase 6: `userEvent.type` into an input that is disabled for the
  *pending* verdict does nothing, silently.
- **The build, not the runner, found phase 4's one defect** — a section fixture missing `position`:
  29 green tests, one type error. §85 verbatim.

## 8. Found and not fixed

- **The case-id chips resolve through a client-side join over the case list's first 200 rows.** The
  contract has no by-ids read, so an id outside that page renders as *"a case outside the loaded
  list"* — removable, but unnamed. The fix is a server-side read, not a bigger page.
- **Three older tag controls still pass `options={[]}`** — the case editor's, `SuiteDialogs`'
  `tagRules`, `BulkActionDialog`'s. `GET /projects/{id}/tags` exists since phase 3b and has a
  consumer since phase 6; their comments claimed no endpoint existed, so the **comments were
  corrected** rather than three screens this slice does not own being changed.
- **A generic report of what a plan's approval means once its scope can move** is a sentence on the
  screen and nothing more. D7′'s accepted cost, restated here so it is not rediscovered as a bug.

## 9. What is NOT verified

Everything. Per §65 the browser pass is deferred and batched, and this slice ran **none** of it:

- **`frontend-browser-checks.md`, M3 lane.** Phase 6 added M3-41…M3-51; phase 5 added M3-32…M3-40
  and struck through or restated M3-2 / 10 / 11 / 16 / 17 / 18 / 19 / 20, which §93 and the earlier
  §97 phases had written against controls this slice deleted. Two rows are **live-only** (a Test
  Engineer's scope-qualified `artifact:edit`, and `case:create` withheld), because mock mode grants
  everything.
- **`backend-mutation-checks.md`** carries 3a's and 3b's rows, unrun.
- **The queue file itself was not committed by phases 5 and 6.** A concurrent lane held it in a
  mid-rewrite state in the working tree (it restructured ~30 per-slice blocks into ordered lanes),
  and staging it would have carried that lane's uncommitted work into these diffs — the collateral
  `CLAUDE.md`'s parallel-execution incident is about. The rows are written into the file and land
  with theirs. **If they do not, the rows are lost**, and that is the one thing to check first.

**The slice is built and unverified, and must be described that way rather than as done.**
