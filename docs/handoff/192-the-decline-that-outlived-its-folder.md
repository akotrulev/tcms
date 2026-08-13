# §192 — the decline that outlived its folder, and the clone that had already answered

**One phase, `backend/` + one migration (`V50`) + a contract *description* change + both
`schema.d.ts` copies. Nothing in `frontend/src` or `packages/`, and nothing on the wire moved.**
§190's list, taken from the first item an agent can do alone: §187's *"the inert decline is state no
screen can show and no test can reach."*

> **This was written as §191 and renumbered.** [§191](191-the-node-that-was-not-there-any-more.md)
> landed on `master` from another lane while this one was building — a CI red on
> `CaseEditorPage.test.tsx`, `frontend/` test code only, no overlap with anything here. The number
> moved rather than the other lane's, because that one was committed first. Nothing else about this
> slice changed; the ids minted below are `§192 DF-*` and were never published as anything else.

## The item was a state with no observer; the slice is the decision to stop producing it

§187 gave `case_condition_optout` its first writer and, in the same commit, its first way of
reaching a state nobody can see. `CaseConditions.reconcileOptouts` protects a decline whose
`suite_condition` no longer reaches the case, on the argument that the client was never shown the
row and therefore cannot resubmit it — `reconcile`'s retired-attachment rule, transferred. The slice
wrote the cost down itself, in the javadoc, in the imperative:

> Move the case back under *Billing* and a rule the author turned off six months ago is off again,
> with nothing on the screen having said so in between.

and named the two shapes that would close it: **a `GET` that reported them, or a migration that
swept them on a move** — *"and neither is obviously right."*

**Neither was taken.** The state has two causes and they are not the same kind of thing, so the
slice is one reversal and one refusal:

| Cause | §187 | §192 |
|---|---|---|
| The case **moved** to another folder | protected | **deleted, at the move** |
| The library entry was **retired** | protected | **protected** — unchanged |

## The clause the reversal turns on is §187's own

§187's argument against sweeping was one sentence: *"deleting declines on a move loses the decision
on an action that said nothing about conditions."* That is true of a save — which is the mutation
`§187 DC-1` describes, and it is still refused here. It is **not** true of a move. Since §98
flattened the tree, a case inherits from the folder it is **filed** in and from nothing else — not
from the suites it was added to, not from the ones a tag rule matches it into. Relocating a case is
therefore the one and only act that decides which standing rules govern it. A move says more about
a case's conditions than any other operation in the product.

**And the product had already answered this question, one path over and two sections earlier.**
`CaseCloneService`'s header has said since §129, and `CaseConditions.declinedAtSuite` has implemented
since §187, that a clone landing outside the source's folder carries **no** declines. So a case
moved into *Refunds* declined a rule that a copy of it — dropped into *Refunds* by the same author in
the same minute — did not. Two spellings of "this case is now in that folder", disagreeing. That is
§179/§180/§183's shape at the level of an *operation* rather than a field, and the fix is to take the
answer the clone already gives.

The asymmetry with retirement is the other half of the argument, and it is what keeps this from
being a general "sweep everything unreachable":

- **Retirement is reversible and is not about this case.** Restore the entry and the attachment, the
  decline and its toggle come back together. While it lasts, nothing renders the row — a withdrawn
  procedure served as current is the omission `SuiteConditionView` records — so the client cannot
  restate it, and reading its absence as an instruction deletes a decision nobody was asked about.
- **A move is a person's decision about this case**, made on a screen, with the folder's standing
  rules visible on it.

## Two bounds, and the order of the two `if`s is the rule

`reconcileOptouts` now carries both, and they answer opposite questions:

| Bound | Population | What it does |
|---|---|---|
| `retained` | every attachment reaching the case's **post-save** folder, **retired entries included** | outside it ⇒ **delete**, whatever the request said |
| `declinable` | the subset whose library entry is **live** — what a client can name | inside `retained` but outside this ⇒ **keep**, whatever the request omitted |

Both come from one query. `CaseConditions.reachingAt` returns `Reaching(all, declinable)` because
the two sets differ by exactly the retired entries and each bound wants a different one — computing
them separately would be two reads of one folder a few lines apart, which is the failure
`CaseUpdateService` resolves the vocabulary before validating to avoid.

**Retention is not an authoring act, and that is why it does not use the authoring bound.** A
decline whose entry was retired is still a decline of a rule this folder imposes. Swapping the two
arms — `declinable` first — deletes exactly the rows §187's bound exists for, and it looks like
tidying (`§192 DF-2`).

## The hash has to be narrowed BEFORE the version is decided, not after

§187's finding was that what is hashed must be *what the case declines after this save*, so that one
stored state cannot produce two hashes. §192 arrives at the same requirement from the direction §187
could not reach: a bound applied after `CaseVersions.decide` would record a version claiming a
decline the row no longer carries, and the very next save — changing nothing — would be credited
with un-declining it.

So the intersection lives in `CaseEdit.normalizeDeclines`, at the end, after §187's union:

```
stored declines after this save = (submitted ∪ stored-but-invisible) ∩ retained
```

**Validated against one folder, stored against another, and that is deliberate.** The client's set
describes the folder it was looking at, so a request that both moves the case and restates a decline
of the folder it is leaving is an ordinary **200** — refusing it would be the screen rejecting a
value it had itself just served, which is `§187 DC-4`'s mutation as a shipped behaviour. The decline
is simply not retained.

`CaseEdit.of` — the pure reconstruction, no queries — passes `Set.copyOf(storedDeclinedIds)`:
"retain all of it". It cannot pass `Set.of()` the way it passes an empty `declinable`, because that
argument is never consulted for a null request field and this one always is; an empty set there
would reconstruct every bulk-touched case as declining nothing. **The caller narrows instead**, and
the bulk path can afford to: a `MOVE_TO_SUITE` has **one** destination for the whole selection, so
the target folder's attachments are one query for four hundred cases.

## What shipped

- **`CaseConditions`** — `reachingAt` and the `Reaching` record replace both
  `declinableAttachmentIds` overloads; `reconcileOptouts` takes the retention bound.
- **`CaseUpdateService`** — resolves the destination's attachments only when `request.suiteId()`
  differs from the stored one, so the ordinary save still costs one query.
- **`CaseBulkService`** — `MOVE_TO_SUITE` reconciles the declines and **puts the narrowed list back
  into the context**, because `writeVersion` reconstructs the edit from it a few lines later. It is
  the first bulk command that *changes* one of the reconstructed fields rather than carrying it, so
  §95's and §187's warnings about a stale context finally have an instance.
- **`CaseCreateService`** — the same `Reaching`, read once and used three times. A create cannot
  move a case, so retention is a no-op there by construction; it still goes through the bound, so a
  clone landing in a folder that has since dropped the attachment its source declined does not carry
  a decline of a rule nothing imposes.
- **`V50__declines_follow_the_filing.sql`** — the sweep, and the comment `case_condition_optout`
  never had. No DDL.
- **The contract**, description-only: `TestCase.declinedPreconditions` (the paragraph that stated
  the old rule, struck in place), `TestCaseInput.declinedConditionIds`, and
  `BulkOperationPayload.suiteId`, which had said only *"must be a live suite in this project"* while
  being the one field in the schema that ends a stored decision.

## `V50` is a data migration in an RLS schema, which is a shape with a silent failure

`case_condition_optout`, `suite_condition` and `test_case` are all under `FORCE ROW LEVEL SECURITY`,
which covers the table owner Flyway runs as. **A `DELETE` issued with no `app.current_company` set
matches zero rows and reports success.** So the sweep is V15's/V18's/V44's loop over `company`,
fourth use, with the same `set_config(..., is_local => true)` and the same reset to `''` at the end.

It matters more here than it did in V44, which corrected a column nothing reads. This one removes
rows that change what a tester is asked to do, and a no-op reporting success would leave exactly the
state the slice is named for while every writer around it assumed the invariant held.

**Nothing in the build can observe it.** `IntegrationTestBase` migrates a fresh container every
time, so there is never a violating row for `V50` to find — recorded as `§192 DF-7`, a hole that
needs a hand-built database rather than a test.

## The invariant, stated once, in the place the DDL can hold it

    optout.suite_condition.suite_id = test_case.suite_id, for every row.

`V1` gave this table no comment at all, so the one thing about it that is not readable from its four
columns — **how long a row lives** — existed in Java only. §190 built `SchemaCommentTest` and made a
comment's identifiers checkable a day earlier; a table whose rule is a lifetime is exactly the kind
that should carry one, so `V50` writes it.

It is **not** a constraint. The invariant spans three tables, so expressing it in the schema means a
trigger on two of them or a denormalized `suite_id` on the optout row — and that would make an
ordinary reorder of a folder's conditions a write to this table, which is the churn §186's
rewrite-in-place reconcile exists to avoid.

Two states the invariant deliberately permits, both stated in the migration because a three-valued
comparison doing the right thing by accident and doing it by design read the same in a diff:

- **An unfiled case** (`suite_id IS NULL`, §96) inherits nothing and keeps no decline. `s.suite_id =
  c.suite_id` is already false for a NULL, so the predicate needs no arm for it.
- **A case whose suite was soft-deleted** keeps its declines: the attachment still hangs off the
  folder the case is filed in. The case is dropped from every list in the product while that is
  true, so sweeping would be deleting the decisions of a case nobody can see, on an event that may
  be undone.

## The fixture cost the mistake §187 warned about, one table over

`ConditionWriteEndpointTest`'s `testCase` helper was `ON CONFLICT (id) DO NOTHING`. The rows outlive
the method — nothing rolls back a MockMvc request — so **the first test that moved `DECLINE_CASE`
left it filed in `NEUTRAL_SUITE` for every test after it**, and what a case may decline is resolved
from the folder it is stored in. Three tests in the class went red with a legitimate 400 about a
rule the case no longer inherited, two of them written eight sections earlier.

That is the same hazard the `condition` helper's `DO UPDATE … lock_version = 0` was already written
for, in the same file, with the argument spelled out above it. The filing is now reset the same way.

`CaseBulkEndpointTest` paid the other half: its `resetMutableRows` deletes `condition`, and a
`suite_condition` referencing one is a foreign key in the way. The delete goes there rather than
into the seeder — and it cascades the optout, which is why the decline is re-seeded every time
rather than once.

Both are §187's fixture lesson at one remove: a suite-level attachment is inherited by every case
**filed** in the suite, so `QUARANTINE` is a folder of its own with one case in it, and nothing in
`CaseBulkEndpointTest`'s four `PAYMENTS` cases changed.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses:

| | |
|---|---|
| `./gradlew build` | ✅ **SUCCESSFUL in 7m24s** |
| tests | **2643 · 531 classes · 0 failures** (+7) |
| `packages`: `npm run build` · 232 tests | ✅ green |
| `frontend`: `npm run build` | ✅ green |

**The other two stacks were rebuilt even though no hand-written line in either changed**, because
both `schema.d.ts` copies did. The diff is **comments only** — a description moves no type — and
that is exactly why running them is worth the four minutes rather than reasoning about it:
`schemaFreshness.test.ts` compares the two copies byte for byte, so a regeneration run in one
directory and not the other is a red build and not a stale comment.

## Verification

**Seven mutation rows, `§192 DF-1`…`DF-7`** — `docs/verification/backend-mutation-checks.md`, all
**NOT RUN** (§65). `DF-7` is a **hole**: `V50`'s subject is a `DO $$` block over rows no fresh
container has.

**Three of §187's five holes are closed by this slice's fixtures** — `DC-1`, `DC-3` and `DC-5` each
said *"needs a decline, then a move, and no test performs one"*. Their mutations are not re-usable
as written, because §192 changed what they mutate; `DF-1`…`DF-4` are the replacements. `DC-4` is
still a hole for the reason it gives, and `DF-3` is what finally makes it observable.

**Browser queue: `§187 M2-22f` is REWRITTEN rather than renumbered** — its expected result was the
behaviour this slice reverses, so leaving it would have made the queue a record of a rule the
product no longer has. Two rows added: `§192 M2-22h` (move + restate in one save is a 200) and
`§192 M2-22i` (the retired half, which is now the *only* invisible decline left).

**Built and unverified.**

## The next slice

§190's list, minus what this took (§191 took nothing off it — it was a CI red, not a candidate):

- **resume the browser queue at `A2 — plans`** — unchanged since §186, and still carrying the seven
  `M2` rows that need the suite-condition fixture `§186 A1-2` authors. It now also carries the three
  rows above, and `M2-22f` is the one the live pass would have caught this slice's subject with.
- **a third `packages/` live pass** — §189's two write arms and three read commands are all
  stub-observed, and `§189 CN-3` cannot be closed by a stub.
- **the CLI and MCP cannot decline a condition either** — §187 named it, §189 took the *ids* half and
  not this one. `caseInputFrom` still drops `declinedConditionIds`, so `tcms case edit` cannot state
  a decline and cannot clear one.
- the **read-only-archive** decision §159 did not take; **serving the relabel audit** (§174);
  **`CurrentUser.teams` has no reader** (§175); **a run has no edit endpoint at all** (§177);
  §184's *what the SPA computes from a contract enum the server also computes*; §185's *a rule that
  fails when a test reads a path outside `src/` that `build.gradle.kts` does not declare as an
  input*; §188's `= null` writer-rule gap and its `releaseSortKey` observation; §189's *a proposal
  cannot carry a decline*; §190's *whether "a comment whose argument expired" has any computable
  form at all* — **"it does not, and here is why" is a legitimate answer**.

**And one this slice opened.** **A decline now ends without anybody being told.** Moving a case is
one click in a bulk action over four hundred rows, and the response says `changed` and nothing else;
the author who turned that rule off six months ago finds out by reading the case. §187's protection
was wrong about *what to keep* and right about *what is invisible* — the invisibility moved from the
row to the **event**. The two shapes are a count on the bulk result (*"3 of 400 cases lost a
condition exception"*) and an entry in the case's own history, which `test_case_version` already
carries the data for since the move writes a version whose hash differs by exactly this. Neither is
obviously right, which is the sentence §187 ended on — and the reason to record it rather than pick
one in the same slice that decided the semantics.
