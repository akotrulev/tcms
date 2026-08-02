# §68. Classification assignment — the reconcile's bound, and two defects that were already shipped

**Date:** 2026-08-02 · **Migration:** none, `V20` still free · **Gates:** backend 1242 tests / 0 failures /
0 skipped, frontend 506/506 · **Not browser-verified** (§65 standing policy; rows queued in both files)

`case_classification` has been in `V1` since the beginning with **three readers and no writer** —
`CaseSummaries` renders it, `CaseSpecifications` filters on it, `DashboardTallies` counts it, and
nothing has ever written a row. That is `evidence`'s shape in §67 and `shared_step`'s in §64, a
third time, and it is the last of the three Phase-2 gaps §5 scheduled on 2026-08-01 (after suite
writes in §30 and evidence upload in §67) — the one §5 called the largest.

It was also one of `CaseUpdateRequest`'s **"refused six"**, whose javadoc has said since M1 that
each of those fields is *"a boundary a later slice deletes in one place"*. `CaseVersions`'s header
predicted the same slice by name: *"When a later slice makes conditions or classification editable,
they join `CaseEdit` to be validated at all — and they join the hash in the same commit."* Both
turned out to be exactly right, which is worth noting because the project's own experience (§28,
§46) is that a note written at the end of a slice usually decays.

## The orientation pass changed the shape twice, before any Java

### You could not write back what you were served

`TestCase` serves `features: string[]`, `testType: string` and `testLevel: string` — **names**, no
ids anywhere — while `TestCaseInput.classificationValueIds` takes uuids. There is no mapping
between them, and not for a fixable reason: the Feature dimension is **hierarchical**, so two leaves
under different parents may legitimately be called "Refunds", and the three company-level
vocabularies are renameable per company (they are locked against *deletion*, not renaming — see
`DimensionKeys`). A client resolving a name to an id would be guessing.

So the read side had to change first. `TestCase` now carries `classificationValueIds`, on the
**detail only**: names are for display and the grid needs nothing else, ids are what an editor
needs, and putting four ids on every row of a fifty-row page buys a consumer that does not exist.

The subtle part is which ids. `CaseSummaries.classify` **drops values absent from the live
vocabulary**, deliberately, so a retired value is never rendered as a current classification. If the
ids were served raw they would include a retired value the names had dropped, and the two halves of
one response would disagree about what the case is classified as. So the ids go through the same
filter — one implementation, `CaseClassifications#liveValueIdsOf` — and the contract can promise
they cannot disagree.

### There is no leaf-only rule

`CaseEditorPage` has rendered features as read-only chips for the whole life of the module, with a
comment stating why:

> Features are read-only here on purpose. The feature tree is a MULTI hierarchical dimension whose
> assignment has its own picker; rendering it as a flat select would let someone set **a parent
> where a leaf is required**.

**No such rule exists.** Not in the high-level plan, not in the ERD, not in the roles spec, not in
`V1`; `grep -i leaf` across all of them finds only §6p's count-rollup arithmetic. The nearest real
rule is §6q's — *selecting a feature includes its descendants by default* — which is about
**filtering**, and is if anything an argument that parents are meaningful things to assign.

What a flat select actually loses is the **parentage**: "Refunds" in a list of thirty strings, with
no indication that it sits under Payments. That is a real problem and it wants a tree. It is a much
smaller problem than the one recorded, and it had been blocking the field for two milestones.

§51 found the same shape in the evidence-upload gap — *"the blocker as written was the wrong shape:
it assumed the signed-URL path was required"*. Twice now, a scheduled gap's recorded justification
turned out to be an assumption nobody had gone back to check. **The orientation pass's third
question, after "does this have a consumer" (§28) and "is this still blocked" (§46), is now "is the
stated reason actually written down anywhere".**

## The reconcile's bound — the slice's real content

A classification value is retired by a **soft delete**, because cases already carry it and
executions already ran under it. Every read therefore drops it. Which means:

**A client is never shown a retired assignment, and so cannot resubmit one.**

If the reconcile treated *absent from the payload* as *delete it*, the first save of any case
carrying a retired value would silently discard it — a classification nobody chose to drop, removed
by a save that never mentioned it, and invisible afterwards because the thing that would have shown
it is the thing that was deleted.

So removal is **bounded by the vocabulary the caller was served**. Only assignments to values the
project still offers are candidates; everything else is left exactly as it is. The consequence is
stated in `CaseClassifications`' header rather than left to be discovered: a retired value's
assignment is **immutable through this API** — it cannot be added (validation refuses a value the
project does not offer) and cannot be removed (the bound does not reach it). It stops existing when
the value is restored and then deselected, or when the case row is deleted.

This is §64's rule about stored references — *retiring a shared step must not make every case using
it unsaveable* — arrived at from the **delete** side instead of the save side. Same soft delete,
same "references survive by design", opposite failure mode.

## Three refusals, one message

`ClassificationVocabulary` refuses a value for three different reasons and says the same sentence
for all of them. They are worth separating in prose even though the response does not:

- **retired since the editor loaded** — soft-deleted, so the foreign key still accepts it;
- **a sibling project's Feature value** — and this is the whole reason validation exists. It is in
  the same company, so it satisfies the FK, the `company_id` column, RLS, and every check `V2`'s
  `case_classification_integrity` trigger makes (company match, dimension match, cardinality). It
  would be **stored**, and every later read of the case would resolve a classification through a
  project the reader holds no grant on. This is the **third column in that family** — `suiteId` had
  the argument written out in M1, `sharedStepId` did not and was persisted unchecked until §64 went
  back for it, and this one is the last such field on the request;
- **naming nothing at all** — which the FK does refuse, at the cost of a 500 where a message on the
  picker belongs.

One message for all three because distinguishing them tells a caller that an id they may not use
nevertheless exists, which is the inference channel §6t closes, and because the caller's next action
is identical in every case. A test asserts the three responses are byte-equal.

**Two values on a SINGLE dimension is a 400, not the trigger's `23505`.** `V2` enforces cardinality
too and stays as the backstop — the app is not the only way a row could ever be written — but a
constraint violation reaching the client is a 500 on a form that has a perfectly good field to put a
sentence on. Duplicates, by contrast, are **de-duplicated rather than refused**: the primary key
`(case_id, value_id)` already says a case carries a value once or not at all, so refusing would be
this layer inventing a rule the schema does not have. A second *different* value on a SINGLE axis is
the opposite — two contradictory instructions with no way to choose between them.

## The hash, and two consequences worth stating

`CaseEdit` gained a component, so `CaseVersions#hash` digests it automatically. That was the
prediction. Two things follow that were not in the prediction:

**The list is sorted and de-duplicated by `validate`.** `hash`'s javadoc rests its whole determinism
argument on *"there is no map in the shape, which is what would otherwise make two equal edits hash
differently depending on iteration order"* — true for `steps`, where order **is** the content, and
false the moment a *set* arrives in a `List`. Unsorted, two saves choosing the same three values in
different orders would hash differently and fill the version list with entries whose diff is empty,
which is the exact noise §6j added the hash to keep out.

**`CaseEdit.of` had to grow a parameter.** The bulk actions change nothing about classification, and
that is precisely why: an edit reconstructed without the stored values hashes as though the case
were unclassified, so a bulk `MOVE_TO_SUITE` would write a version whose content disagrees with the
case, and the **next ordinary save** would be credited with restoring a classification nobody
touched. §62's mis-attribution defect, on a field that class never reads. It takes the stored ids
and passes `ClassificationVocabulary.none()`, which is never consulted because the reconstructed
request's classification is null — so `of` stays a pure function with no project read.

**One accepted consequence, recorded rather than discovered later:** adding a component to
`CaseEdit` changes the hash of *every* case, so the first save of each case after this deploy writes
a version even if nothing changed. That is unavoidable for any change to the hashed shape, it is
one version per case once, and the alternative is not hashing authored content. It is also arguably
correct: before this slice, **a save that changed only the classification wrote no version at all**,
because the snapshot carried it and the hash did not.

## Two defects found on the way, both already shipped

### `TestCaseDetailView` never served `createdBy`, and it inverted §66

`TestCase` is `allOf: [TestCaseSummary, …]`, and `TestCaseSummary` **requires** `createdBy`. §66 put
the author on the wire for the grid; `TestCaseDetailView` never grew the component, so
`GET /cases/{caseId}` has omitted the key since.

`CaseDetailPage.tsx:128` reads `data.createdBy?.id ?? null` — from exactly that endpoint. So it was
always `null`, and §66 defined `null` as *a supplied author who matches nobody*: **Delete was
disabled on every case, for every `OWN`-qualified Test Engineer**, including their own. The case
detail is the only screen in the product with a delete affordance. §66's headline feature was
shipped 180° wrong on the one surface that has it, and §67 shipped on top of it.

Nothing could see it:

- `tsc` allows optional chaining on a required field, so the generated type raised nothing;
- `CaseDetailPage.test.tsx` supplies a fixture that **has** the key, so §66's own new test file
  passed;
- `CaseDetailEndpointTest` asserts the required summary fields — from a **hand-written list**,
  written before `createdBy` existed and never revisited. The list is the defect.

The repair is the list becoming a computed check, per the working agreement's rule that *a
catalogue-wide invariant needs a computed check, not a careful reading*.
`ContractConventionTest.requiredPropertiesHaveAComponent` resolves a schema's `required` **through
`allOf`** — the traversal is the whole value, since a schema written as
`allOf: [Summary, {required: […]}]` states its obligations in two places no reader holds in mind at
once — and compares the union against the record's actual components. Verified by re-introducing the
defect: it reddens, alone, while the other four contract checks stay green.

### `CaseDetailService` was a fourth copy of the render

`CaseRenderer` exists because `CaseCreateService` and `CaseUpdateService` each carried a
byte-identical private copy, and `CaseBulkService` would have been a third. What nobody noticed is
that the **`GET` path was never moved onto it** — `CaseDetailService.detail` was a fourth copy, and
the oldest. That is the wrong way round: it is the render the writers exist to *agree with*.

It stayed invisible because **a duplicate that agrees is indistinguishable from a delegation**. It
became visible the moment a field was added to the view, which is the only moment it ever could —
and that is precisely the failure `CaseRenderer`'s header describes: one writer serving a field
`GET` does not, so a case shows one value on save and another on reload, with neither screen
revealing which is stored. Now a delegation.

## The picker

`ClassificationPicker` renders from `cardinality` and `hierarchical` rather than from the dimension
key, so a client adding a fifth dimension gets a usable control without the file changing:

| cardinality | hierarchical | control |
|---|---|---|
| SINGLE | no | a select, with a **None** option |
| MULTI | no | checkboxes |
| MULTI | yes | a tree of checkboxes — the Feature dimension |
| SINGLE | yes | a tree of radios — not a combination the seed creates, rendered honestly rather than thrown on |

**Cardinality is enforced by the control's shape, not by validation.** A `Zod` refinement would need
the vocabulary to know which dimensions are SINGLE, which would stop the schema being static — and
more to the point, a checkbox list plus a refinement lets someone tick two boxes and then tells them
off for it. The **None** option exists because nothing about a case's classification is `NOT NULL`
and a picker with no path back makes the first accidental choice permanent.

**`buildValueTree` lives in `lib/`, not the component file.** Exporting a function beside a
component defeats Fast Refresh and lints as a warning, and the lint gate is 0 — which is the trade
`caseSchema.ts`'s own header records having made badly enough that its four refinements went
untested through Phase 4.

It **surfaces orphans _and the members of a `parentId` cycle_ at the root**, where `SuiteTree` stops
the walk and lets a cycle's members fall out. That difference is deliberate and it is the interesting
part: falling out of a *navigation* tree is a folder you cannot click, and falling out of a *picker*
is worse, because the editor submits the complete set of ids — so a value with no control on screen
is **silently deselected by the next save**. Invisible data loss on a form nobody touched. The
property is therefore unconditional: *every input value appears exactly once in the output, whatever
the parent links say*, which is what makes it worth asserting at all. Orphans are reachable today —
`parent_id` is a plain nullable column and the server serves live values only, so retiring a parent
while its children stay live produces exactly that payload.

**The test found its own defect the §62 way.** `data-testid` on a MUI `Checkbox` lands on the
wrapper span, where the checked state is not readable — a click test passes and asserts *nothing*
about selection. The testids moved onto the `input` slot.

**The mock enforces the server's rules rather than storing what it is given.** A mock that merely
stopped refusing the field would let mock mode save a payload live mode answers 400 to, which is the
divergence the refused-fields comment beside it exists to prevent, arriving through the other door.
`buildCaseDetail` also **derives** its ids from the summary's names, so no fixture case can carry
ids and names that disagree — a state the server cannot produce, and §64's "the mock agrees with
itself" defect if a fixture could.

## Mutations run

| Mutation | Result |
|---|---|
| Accept any id in `normalizeClassification` (drop the vocabulary check) | exactly **4** red — sibling-project, retired, unknown, and the indistinguishable-refusal test |
| Delete `createdBy` from `TestCaseDetailView` (restore what master shipped) | exactly **1** red — the new computed check; the other four contract checks green |
| `ClassificationPicker`: each control returns only its own dimension's values | exactly **3** red — the three whole-array tests |
| `buildValueTree`: the naive bucket-and-walk, no sweep | exactly **3** red — the three emit-everything tests |

Four more are **queued and not run**, in `docs/verification/backend-mutation-checks.md`. The one
that matters most is the reconcile's bound: its failure mode is a save that succeeds, returns a
correct-looking response, and drops a classification with nothing on any screen mentioning it.

## Left for later, deliberately

- **The refused five.** `ownerId` needs §54's project-membership predicate (which exists);
  `tags`/`automationTags` are the same write path twice more over free-text join tables;
  `preconditions`/`postconditions` are the library-versus-inline shape and are their own design
  problem. The boundary is now five fields wide, and `CaseUpdateRequest`'s header records that it
  was six.
- **Nothing manages the vocabulary itself.** A client cannot add a Feature value through the
  product — `classification_value` still has no writer, so the Feature tree of a freshly provisioned
  project is empty and stays empty. This slice makes the tree *usable* and not *fillable*, which is
  the more urgent half only because assignment was the gap on the schedule. `ClassificationValueRepository`'s
  own javadoc already anticipates the writer ("sibling ordering becomes the writer's problem when a
  writer exists, since nothing allocates positions there yet").
- **`usage_count` on `classification_value`** is not touched. Unlike §64's shared steps and
  `ConditionRepository`'s carry-over, nothing orders by it here, so there is no defect to close —
  noted only so the next reader does not go looking.
- **`ConditionRepository`'s `ORDER BY usageCount` still ranks by a constant** (§64's carry-over).
- **The artifact register's plan filter is `useState`, not the URL** (§61's carry-over).
