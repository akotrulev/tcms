# §187 — the decline nobody could make, and the rule that said it was fine

*2026-08-13. Three phases. `backend/` + contract + `frontend/` + `packages/`. **No migration.***

§186 replaced itself on its way out:

> **`case_condition_optout` has no writer either.** This slice is the reason to say so: the toggle
> beside the inherited marker is the thing `SuiteConditions` rewrites in place to protect, and
> nothing in the product can create one. `§186 SQ-1` proves the protection works; nothing proves the
> protected thing is reachable.

It was right, and the table had more supporting cast than `suite_condition` did. `CaseConditionOptout`
is an entity with a hand-written `@IdClass`, a repository with a documented query, a filter in
`CaseConditions.withoutOptouts`, a paragraph in `CaseConditions`' class header ("Three ways a row
disappears, all deliberate"), an argued design note about being keyed on the attachment rather than
the library entry, an `ON DELETE CASCADE` in `V1` chosen to protect it, a `CaseConditionRefView`
javadoc pointing out that the client already holds the id it would need — and, in
`CaseCloneService`, a full paragraph of *prescribed behaviour for when it gets a writer*.

`new CaseConditionOptout(` occurred nowhere. `INSERT INTO case_condition_optout` occurred twice, both
in test fixtures.

---

## What shipped

**Phase 1 — the writer.** `declinedConditionIds` on `TestCaseInput`, validated by
`CaseEdit.normalizeDeclines` and applied by `CaseConditions.reconcileOptouts`;
`declinedPreconditions` / `declinedPostconditions` on `TestCase`; the clone rule `CaseCloneService`
had written down in §129; both `schema.d.ts` copies.

**Phase 2 — the toggle.** A switch beside the "From Payments" chip in the editor's condition panels,
and a "Turned off for this case" group on the read-only detail page.

**Phase 3 — the observer.** `everyEntityIsWritableByTheProduct`, and the measurement that says why
the existing rule could not be the one to catch this.

No migration in any of it. Every column this writes to has been there since M0 — the same character
as §186, one table along.

## Serving the declined row back is the design, not a nicety

A decline removes the attachment from `preconditions`. That removes the inherited marker. The toggle
sits *beside* the inherited marker. So the naïve version of this feature is **one-way from the only
screen that offers it** — an author turns a rule off and has no way, anywhere in the product, to turn
it back on.

Three shapes were available and only one survives the version snapshot:

| | What it does | Why not |
|---|---|---|
| A `declined` flag on `CaseConditionRef` | Keeps one list, marks the row | `TestCaseDetailView` **is** the version snapshot (`CaseVersions` serializes it whole) and `CaseFacts` pins a run's checklist out of that snapshot's `preconditions`. A flag puts declined rows into the checklist of every run started after the decline, distinguished by a boolean the pin's reader has never heard of. |
| A bare `declinedConditionIds` on the response | Cheapest | The editor cannot render a title from an id, and resolving one client-side means re-deriving §6f's order — the second implementation the shared layer exists to prevent. |
| **Two more lists** | `declinedPreconditions` / `declinedPostconditions` | What shipped. `preconditions` keeps meaning exactly what it meant — the procedure — and the rest is stated beside it. Split by kind because `CaseConditionRef` has no `kind` field, which is the contract's own reason for splitting the first two. |

The **input** is one array, not two, and for the opposite reason: a `suite_condition` id already
determines its kind, so splitting it would let a caller file an id under the wrong one.

## The hash has to be a function of the stored row, and the first cut was not

`CaseEdit` carries the declines, so they are in `CaseVersions#hash` — correct, because turning off
"must be logged in" changes the procedure a tester performs. That is the easy half.

The hard half is `reconcileOptouts`' removal bound. A decline whose `suite_condition` no longer
reaches the case — the case moved folders, or the library entry was retired — is **protected from
deletion**, exactly as `CaseConditions.reconcile` protects a retired entry's attachment: the client
was never shown it and therefore cannot resubmit it, so reading its absence as an instruction would
delete a decision on a save that never mentioned it.

Which means the client's set and the stored set are not the same population. The first cut therefore
answered **two different things about one stored state**:

| Request | First cut returned | |
|---|---|---|
| field absent | the whole stored set | |
| field sent unchanged | the visible subset | ← different value, same state |

Two spellings, two hashes, and a save that changed nothing writes a version whose diff is empty —
the recurring defect every sorting comment on `CaseEdit`'s fields exists to prevent, arrived at from
a direction none of them describes.

`normalizeDeclines` unions the invisible rows back in, so what is hashed is **what the case declines
after this save**. That is also what lets `CaseEdit.of` pass an empty declinable set and the whole
stored set and still agree with the edit path exactly — which matters, because the bulk path would
otherwise have to resolve a suite chain and a library *per case* to reproduce the restriction, and
any case it restricted differently would hash differently from the next ordinary save. `CaseBulkService`
has been warning about that shape on two other fields since §68 and §95.

## The create path accepts declines because of a caller written 58 sections ago

The first decision here was to **refuse** them on create: the SPA's create form reads its inherited
rows from `existing`, which does not exist yet, so it draws no marker and has nothing to toggle.
Refusing matched the only client exactly.

Then `CaseCloneService`'s header turned out to be a second client:

> Recorded rather than designed around: when it gets a writer, a clone landing in the source's suite
> should carry the opt-outs whose `suite_condition` belongs to that suite, and one landing elsewhere
> should carry none.

A clone **is** a create — that is the whole of §129's design — so the prescription is unbuildable
without the create path taking the field. And the alternative, having the clone insert the rows
behind the create path's back, records a v1 describing a case that declines nothing, which is the
hash trap above in its most avoidable form.

So the decision reversed, and the reason is worth keeping: *"no screen reaches it"* was true and
*"nothing reaches it"* was not. A paragraph of prescribed behaviour is a caller.

Its two halves coincide today and will not always. Since §98 flattened the tree a case's chain is
its own folder or nothing, so "attachments belonging to that suite" and "attachments this case
inherits" name the same set; `declinedAtSuite` keeps the narrower spelling, because that is the one
that stays right if inheritance ever widens.

## The rule that should have caught this ran green over it for thirty-one sections

§156 wrote `everyMappedColumnHasAWriter` for precisely this shape. It has never flagged
`case_condition_optout`, and the reason is its corpus: `SOURCE_ROOTS` and `SQL_ROOTS` both include
`src/test`, so the two fixtures' `INSERT INTO case_condition_optout (company_id, case_id,
suite_condition_id)` make all three columns read as written.

This is **§153's finding one level down**. That slice established that a test caller is a consumer
for a *read* seam and never for a *write* one; a table is the largest write seam there is.

The obvious repair — narrow the corpus to `src/main` — was **measured rather than proposed**, and it
is not the fix:

| Rule | With `src/test` | `src/main` only | Of the delta |
|---|---|---|---|
| `everyMappedColumnHasAWriter` | 6 | 22 | **15 are `lockVersion`** — `@Version`, written by Hibernate, whose assignment is in no source file and is definitionally not a finding. **One is real**: `DocPageVersion.changeComment`. |
| `everyMappedColumnHasAReader` | 11 | 18 | **all seven are real**, including `TestRun.releaseSortKey` and `TestExecution.releaseSortKey` — so **§151 built that sort key and nothing in the product has ever read it back**. |

Eight genuine findings needing eight decisions is a slice, not a tail. It is recorded as a candidate
below rather than laundered through a by-decision map here.

At **table** granularity none of it arises: an entity is writable if any field is assigned in
`src/main` or its table is named by an `INSERT` there, so a `@Version` column carries no weight and
the answer is one bit per table. That is §116's question at §116's own granularity — *"three `V1`
tables had never had a writer"* — asked by a rule instead of by a person who happened to be looking.

**Its first run returned five and four were the rule's own fault.** `permission` and `role` are
seeded by `V3`, `configuration` and `doc_space` by `V1`'s seed functions; migrations are main sources
and are in the corpus now. The survivor is `CaseAutomationTag`, whose argument was already written at
`CaseUpdateRequest`: `automationTags` is still **refused with a 400**, and building the reconcile
quietly would make that refusal a lie.

`case_condition_optout` left the list before the list existed. The rule is green **because** phase 1
landed.

## The fixture cost two rounds of the same mistake

Phase 1's endpoint tests hung a `suite_condition` off `ConditionWriteEndpointTest`'s shared folder.
Five tests went red — four in `Attaching`, one in `Editing` — because a suite-level attachment is
inherited by every case filed beneath it, so `preconditions` grew by one in every assertion in the
class. Moving the "this case does not inherit that" fixture to a *second* folder reddened the same
five again, because the shared case is filed in the first one.

The failure is the feature. A fixture whose blast radius is "everything filed under here" is the one
shape that cannot be shared, and §187's is a suite, a case and an empty third folder of its own.

## Gates

Run on `shanks`.

| Phase | Gate | Result |
|---|---|---|
| 1 | `./gradlew build` | **SUCCESSFUL — 2630 tests / 530 result classes / 0 failures** (+13 tests, +1 class: `ConditionWriteEndpointTest$Declining`) |
| 1 | `packages`: build · 230 tests · lint | green |
| 1 | `frontend`: build | green (two fixtures gained the two new required arrays — §167's `tsc`-not-runtime rule, second instance) |
| 2 | `frontend`: build · lint 0 warnings | green |
| 2 | `frontend`: `npm run test:run` | **1282 tests / 97 files / 0 failures** (+9) |
| 3 | `./gradlew build` | **SUCCESSFUL — 2631 tests / 530 classes / 0 failures** |

**§186's recorded flake passed.** `CaseEditorPage.test.tsx > fills action, test data AND expected
result from one pick` was green in the full-suite run above. One green run does not disprove a
load-dependent flake, and nothing about this slice touches it; it is still not fixed.

Rows `§187 DC-1`…`DC-6` and `§187 M2-22a`…`M2-22g` recorded and **not run** (§65). Four of the six
mutation rows are recorded as **holes** — `DC-1`, `DC-3`, `DC-4`, `DC-5` — and all four fail for one
reason: the states they break need a decline plus a move, or a decline plus a bulk action, and no
fixture reaches either. `DC-6`'s expected result is a **green** build on the older rule, which is the
blindness this slice is named for, reproducible on demand.

**Built and unverified.**

## The next slice

§186's list, minus the item this slice took, plus what it opened:

- **the eight findings phase 3 measured and did not take** — seven readerless columns and one
  writerless, visible only once the column rules' corpus excludes `src/test`. `TestRun.releaseSortKey`
  is the one to read first: §151 shipped that column *as a fix* and nothing in the product consults
  it, so the repair and the mirror defect landed in the same slice. Taking this means narrowing the
  corpus, excluding `@Version` from the writer rule, and answering all eight — it is a slice, and
  the numbers are above so it can be scoped before it is started.
- **the CLI and MCP cannot decline a condition either**, and now cannot change a suite's conditions
  *or* a case's declines. `caseInputFrom` drops `declinedConditionIds` on the **weaker** half of the
  conditions' argument — the shapes here do line up, so "it does not type" is unavailable and the
  whole of it is that absent leaves them alone. That makes it the first exclusion in `rewrite.ts` to
  revisit.
- **resume the browser queue at `A2 — plans`** — unchanged from §186, and the queue now carries
  seven more rows in `M2` that need the same suite-condition fixture `§186 A1-2` authors.
- the **read-only-archive** decision §159 did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- §184's named shape, untaken: **what the SPA computes from a contract enum that the server also
  computes**, derived rather than read for;
- §185's, untaken: **a rule that fails when a test reads a path outside `src/` that
  `build.gradle.kts` does not declare as an input**;
- §186's, untaken: **`V1`'s comment on `suite_condition` still describes the tree §98 deleted**, and
  correcting it costs a migration for a comment.

**And one this slice opened.** `case_condition_optout` is now writable, and the *inert* decline —
one whose attachment no longer reaches the case — is state no screen can show and no test can reach.
`DC-1` and `DC-3` are both holes for that reason, and `§187 M2-22f` is the only observer of it
anywhere. The decision to protect those rows is argued and I would take it again; what is missing is
a way to *see* one. A `GET` that reported them, or a migration that swept them on a move, are the two
shapes, and neither is obviously right.
