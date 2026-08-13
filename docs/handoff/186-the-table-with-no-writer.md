# §186 — the table that shipped with a reader, an index and no writer

*2026-08-13. Three phases. `backend/` + contract + `frontend/` + `packages/`. **No migration.***

`suite_condition` has existed since M0. So has its `kind` column, its `V10` index, the resolution
order that reads it (`CaseConditions`, at length), the response shape (`SuiteConditionView`, with
two recorded contract gaps), and the condition library's `suiteUsageCount`. Nothing ever created a
row. `new SuiteCondition(` occurred nowhere in `backend/src/main`, `SuiteInput` had no field for it,
and `SuiteController`'s write surface was `POST` / `PATCH /{suiteId}` / `DELETE /{suiteId}` /
`PUT /{suiteId}/status`. The only rows in this repository were written by hand-rolled SQL in a read
test's fixture — a fixture doing the product's job, which is the shape §107 deleted the mock layer
over.

Two live browser passes filed it as a 🔴. §185 re-derived it rather than taking the pass's word,
confirmed it, and left it as *"the top of the next list"*.

---

## What shipped

**Phase 1 — the contract and the write path.** `SuiteInput` gains `preconditions` /
`postconditions` (`CaseConditionInput`, absent = leave alone, `[]` = detach), `SuiteConditions`
reconciles them inside both suite writes, and `Suite` serves `preconditions` / `postconditions`
where it served one `conditions`.

**Phase 2 — the form.** The suite create/rename dialog carries two `ConditionsPanel`s and the
library dialog behind them — `CaseEditorPage`'s arrangement, reused rather than re-derived.

**Phase 3 — the records.** This file, the two queues, both orientation caches.

No migration in any of it: every column and every `CHECK` this writes to has been there since M0.
That is the whole character of the slice — nothing was missing except the writer.

## The rename is a contract gap closing, not a tidy-up

`SuiteConditionView` recorded two gaps. The second was `kind`:

> The column exists, the distinction is real — a postcondition is cleanup, not setup — and it is
> mapped as of the case detail slice, which reads it. But `CaseConditionRef` carries no field for
> it, so this list still arrives undifferentiated. The case endpoint does not need one: it has
> *two arrays*, and which array a row is in says what the field would have. A suite has one array
> and therefore genuinely loses the distinction. Closing it is an `openapi.yaml` change with a
> consumer on the other side, **and the SPA's suite tree does not render conditions at all yet.**

The last clause was the blocker and **it expired on the same commit that closed the gap**: a form
that attaches a condition has to say which list it is attaching to, so the consumer arrives with
the writer. It is §160's shape — an argument that stayed live in the code long after its premise
moved — except that here the premise moved *because of this slice* rather than three slices earlier.

The fix is two arrays and **not** a `kind` field on `CaseConditionRef`, which would have been
redundant on every case row where two arrays already answer it. `conditions` is renamed rather than
kept beside them: it had exactly one consumer, a test fixture, and serving the same rows twice under
two names is §184's shape.

## THE FINDING — `CaseConditions.reconcile`'s central argument inverts here, and says so itself

`SuiteConditions` is `CaseConditions.reconcile` transferred: the retired bound, first-row-wins
dedupe, independent kinds, position from array order, rows rewritten in place. The case-side javadoc
explains why it rewrites in place and then volunteers that it did not have to:

> Nothing references `case_condition.id` — `case_condition_optout` keys on `suite_condition_id`,
> **not this table** — so a delete-and-reinsert would in fact be *safe* here.

That sentence names `suite_condition` as the table where it is **not** safe, and nothing had ever
had to act on it, because nothing wrote to that table.

`case_condition_optout (case_id, suite_condition_id)` is the per-case toggle beside the inherited
marker. It references `suite_condition(id)` `ON DELETE CASCADE`. So a reconcile that cleared the
list and reinserted it would, **on every save of the suite**, discard every opt-out every case had
recorded. A rename that changed nothing an author can see would quietly re-impose a condition twenty
cases had declined — and the rows proving they declined it would be gone, cascaded away by a foreign
key, with no error anywhere.

It is worth being precise about how invisible this would be. The lists come back in the right order.
The counts are right. The detach still works. Ten of the eleven new endpoint tests pass.

**Measured by deleting the fix.** With the reuse branch replaced by `null`, exactly one test fails —
`reorderingPreservesTheAttachmentRow` — and the other ten stay green.
`detachingTakesTheOptOutWithIt` pins the other half deliberately, because a reconcile that got
either rule wrong would still look correct against the other: a detach *must* take the opt-out with
it (there is nothing left to decline) and a reorder *must not*.

The likely future edit is a reader unifying the two reconciles into one shared method — and the
case-side javadoc would appear to agree with them. It is `§186 SQ-1` in the mutation queue for that
reason.

## The validation is a transfer, and the transfer is argued at the method

`SuiteEdit.normalizeConditions` is `CaseEdit.normalizeConditions`, six lines over a different stored
value. They could share one static method. They do not, and the reason is written at the method:
the shared version would take five parameters to say which entity it is validating for, and **the
rules being identical is the invariant** — `SuiteWriteEndpointTest` and `CaseUpdateEndpointTest`
each pin them against their own endpoint, so the day they diverge is a failing test rather than a
silent change to one caller's behaviour. The one place sharing would genuinely have paid, the error
strings, is handled by both tests asserting the same text.

## Two findings in phase 2, both about copy nothing could reach

**`lib/conditions.ts`'s suite branch had never once rendered.** `impactSummary` reads
`suiteUsageCount`, which counts `suite_condition` rows; nothing could create one, so the count was
`0` in every deployment and the branch was dead the entire time it was wrong. It said *"every case
**beneath** inherits it"* — for eighty-eight slices after §98 removed the thing a case could be
beneath. It becomes reachable today, so it would have started being wrong **out loud**, on the
dialog somebody decides from before changing three hundred cases at once.

The module header was stale in the same direction and one layer deeper: it justified not publishing
a case-reach figure by *"the walk is over the suite tree … an `ltree` subtree query the library read
cannot express"*. There is no subtree and no walk. **The conclusion survives its reason** — a client
still cannot compute the reach, because `Suite.caseCount` is resolved membership across three arms
and only the *filing* arm inherits conditions — so it is corrected rather than deleted.

This is §156's all-clear in another costume: copy describing a state nothing can reach goes stale
silently, and the day the state becomes reachable is the day the copy starts lying. The signal is
the same one §158 found on `StatusChip`'s tooltip — prose that would have begun explaining the wrong
thing on the day the thing began happening.

**`api/queries/suites.ts` held a hand-written `SuiteInput`.** A structural copy of the schema since
Phase 2, correct for as long as nobody added a field. Phase 1 added two, and the copy went on typing
a payload the endpoint accepts as a payload it does not. It is `S['SuiteInput']` now.

It is §52's lesson on the **write** side, and it survived because the check §52 produced does not
look there: `apiResponseTypes.test.ts` refuses an inline literal at an `api.*` call site, and this
was a *named type used as an input*. Its comments moved to the contract, where the rules they
describe are decided — except `caseIds`, which the endpoint still takes, validates, and **no screen
still authors**. That is unchanged by this slice and is worth keeping named.

## What forced the `packages` decision instead of letting it default

`core.test.ts`'s *"carries every field of `SuiteInput`"* **failed the moment the field appeared** —
which is exactly why it is written as "every field" rather than "these four". The two lists are now
excluded from `suiteInputFrom` on `caseInputFrom`'s grounds: the read serves `CaseConditionRef`, the
write takes `CaseConditionInput`, so echoing the resource is not a thing that types. Absent leaves
them alone, which is correct for `tcms suite edit --name`. What a CLI cannot yet do is *change*
them; that wants a flag, not a projection, and it is named below rather than half-built.

## Gates

Run on `shanks`.

| Phase | Gate | Result |
|---|---|---|
| 1 | `./gradlew build` | **SUCCESSFUL — 2617 tests / 529 result classes / 0 failures** (+12 tests, +1 class: `SuiteWriteEndpointTest$Conditions`) |
| 1 | `packages`: build · 230 tests · lint | green |
| 2 | `frontend`: build · lint | green |
| 2 | `frontend`: `npm run test:run` | **1276 tests, ONE failure — and it is not this slice's** |

That failure is `CaseEditorPage.test.tsx > fills action, test data AND expected result from one
pick`, which asserts a half-typed value (`Refund` where `Refund the payment` was typed). It **fails
identically on the stashed tree at phase 1's commit** and passes when the file runs alone — measured
both ways rather than assumed, because "probably flaky" is how a real regression gets shipped. It is
the same file whose 239-second timeout left `master` red for 23 hours after §154's push. **It is a
standing flake in the frontend suite on this observer and it is not fixed.**

Rows `§186 SQ-1`…`SQ-5` recorded and **not run** (§65). `SQ-2` and `SQ-5` are recorded as **holes** —
mutations nothing reddens — and that was **measured**, not predicted: phase 3 applied both together
and the two suite classes stayed green. Writing "this is untested" is the honest form of a row whose
check does not exist yet.

Browser queue: `A1-2` rewritten (it asked whether a control existed; it drives one now), `M2-22`'s
fixture is authored through the product for the first time, `L4-19` gains the suite figure that had
never been non-zero, and `§186 A1-2a` / `A1-2b` are new. The 🔴 at the head of the file is struck.

**Built and unverified.**

## The next slice

§185's list, minus the item this slice took, plus what it opened:

- **resume the browser queue at `A2 — plans`** — unchanged, and the blocker that ended both previous
  sittings is fixed; the still-live `Fixture seeding (temp)` token wants revoking either way. The
  suite-condition rows above are now among the things a sitting can actually do.
- **`case_condition_optout` has no writer either.** This slice is the reason to say so: the toggle
  beside the inherited marker is the thing `SuiteConditions` rewrites in place to protect, and
  nothing in the product can create one. `§186 SQ-1` proves the protection works; nothing proves the
  protected thing is reachable. It is the same finding as this slice's, one table along, and it is
  now the only reader of `suite_condition.id`.
- **the CLI and MCP cannot change a suite's conditions.** `suiteInputFrom` drops them deliberately;
  a flag (`--precondition`, repeatable) is the shape, and §139's rule — a flag edit states its whole
  intent in its flags — says what it must send.
- the **read-only-archive** decision §159 did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- §184's named shape, untaken: **what the SPA computes from a contract enum that the server also
  computes**, derived rather than read for;
- §185's, untaken: **a rule that fails when a test reads a path outside `src/` that
  `build.gradle.kts` does not declare as an input.** The population was measured at exactly two and
  both are covered; nothing keeps it at two.

**And one this slice opened.** `V1`'s comment on `suite_condition` still reads *"Inherited by every
case beneath the suite. Resolution order: inherited (walking suite.path upward)…"* — describing the
tree §98 deleted. It is wrong in the same way `lib/conditions.ts` was wrong, in the one place this
project treats as authoritative for *why* a table is shaped as it is, and correcting it costs a
migration for a comment. Whether that is worth a migration is a decision, not an oversight.
