# §193 — the decline that ended without a sentence, and the context that answered its own question

**Two phases. Phase 1 `backend/` + the contract + both `schema.d.ts` copies; phase 2 `frontend/`
only. No migration, nothing in `packages/`, and no behaviour of the write itself changed — the
operation this slice is about does exactly what §192 shipped, and now says so.**

§192's own closing item, taken as a decision: *"a decline now ends without anybody being told."*

## The candidate this slice did **not** take was already done

§192's next-slice list carried *"the CLI and MCP cannot decline a condition either — §187 named it,
§189 took the **ids** half and not this one. `caseInputFrom` still drops `declinedConditionIds`, so
`tcms case edit` cannot state a decline and cannot clear one."* **It is false, and it was false when
it was written.** `cli/src/writes.ts` declares `--decline` (multiple, complete-set, with the
attachment-versus-library-id paragraph above it) and `mcp/src/writes.ts` declares
`declinedConditions` — both landed in `88b13ef`, *§189 phase 1*, the commit the sentence credits with
taking only the other half. `caseInputFrom` does still drop the field, and the drop is correct for
the reason `core/rewrite.ts` had already written down two slices earlier: **a projection and a delta
answer different questions**, and a list is left alone by an unchanged round-trip.

So the bullet described a live gap out of a true fact about a *different* function. §156's shape,
one list forward: the claim was carried from §190's list into §192's without re-reading the code
under it, which is the exact motion those two sections exist to warn about. Measured in one grep
before any code was written, and the list below is corrected rather than re-copied.

## The item: §192 removed a state and left the event invisible

§192's argument was that a decline is an exception to a rule the case's **folder** imposes, so
moving the case ends the declines the destination does not impose. That is right, and it made
**relocation the one operation in the product that ends a stored decision**. The response to it was:

```json
{"dryRun": false, "total": 400, "changed": ["TC-1", … ], "unchanged": [], "skipped": []}
```

`changed` is true and complete, and it is an answer to the question the caller asked. The question
they did not ask — *what did that cost?* — had no answer anywhere: not in the response, not on the
list page, not in the audit row (`{"changed":400,"suiteId":"…"}`), and not in a version list, whose
rows carry a number, an author and a comment and **not** the snapshot that would show the difference
(`EntityVersionView`: *"the snapshot is what a diff view reads, through an endpoint that does not
exist yet"*). §187 wrote the cost of the state it created into its own javadoc; §192 deleted the
state and inherited the sentence, in the imperative, one clause over.

**The invisibility moved from the row to the event, and this slice moves it to the screen.**

## Of §192's two candidate shapes, one is a field and one is a subsystem

| Shape | §192's words | Verdict |
|---|---|---|
| A count on the bulk result | *"3 of 400 cases lost a condition exception"* | **Taken** — as **keys**, not a count |
| An entry in the case's own history | *"`test_case_version` already carries the data"* | **Refused, and it already half-exists** |

The history half is refused because it is not a slice about telling anybody anything. The version
row is *already written* — the move's hash differs by exactly this — and it is already invisible for
a reason that has nothing to do with declines: **no endpoint serves a snapshot.** What that shape
actually asks for is a version *diff* view, which is a read model, an endpoint, a screen and a
decision about what a diff of two resolved cases even is. Building it as a footnote to a condition
exception would be the smallest possible motivation for the largest thing on the list.

And the bulk result is the right place on its own terms, for a reason the version list can never
have: **the preview.** The one moment this can be told to the person while they can still decline is
before the commit, and this endpoint is the only writer in the product that has such a moment.

## Keys, not counts — and not a fourth bucket

`BulkResultView`'s header has argued since §6f that every outcome is a **list of display keys**,
because *"a bare `affected: 38` cannot be rendered as a preview at all"*. §192's sentence proposed a
count; the class's own rule says the count is derived from the list, so the field is
`declinesEnded: string[]` and the dialog's number is `.length`.

What it is **not** is a fourth outcome bucket. `changed`, `unchanged` and `skipped` partition the
selection; `declinesEnded` is a **subset of `changed`**. Taking those keys out of `changed` would be
false — they changed, in exactly the way the caller asked — and a client deriving "38 will change"
from list lengths would start under-reporting. Both the contract and the record header say this in
the same words, and the test that pins it moves two cases and expects one key.

## The order of two adjacent statements is the whole rule, and the gate found it

The report is computed in the loop, from the same `Context` the mutation narrows — one projection,
`Context.retainedDeclinesOf`, read by the write (which rows survive) and by the response (how many
did not). Two expressions of that rule a hundred lines apart is the duplication §192 spent a section
removing at the level of *operations*; it would be back at the level of a *list*.

**But §192 also made `MOVE_TO_SUITE` the first bulk command that writes one of the reconstructed
fields back into the context** — `context.declines().put(row.getId(), surviving)`, so `writeVersion`
hashes what the row now carries. So after `mutate`, the stored list **is** the retained list, and the
question "did this case lose anything" answers *no* — on every commit, and only on a commit.

That is not a hazard this section is describing from the outside. **It was the state of the code at
the first run of the gate:** `theMoveReportsTheDeclineItEnds` red with an empty array,
`thePreviewReportsTheDeclineBeforeItEndsIt` **green beside it**, 2645 of 2646 passing. A field that
is correct on the preview and empty on the commit is the worst of the three available states — the
dialog warns, the operation proceeds, and the record of what it cost is a `[]` that reads as
"nothing was lost". The call is now above `if (!dryRun)`, the reason is in a comment at the call site
rather than only on the method, and `§193 LD-1` is the mutation.

**Two test arms for one behaviour, and neither is ceremony**: exactly one of them can see this.

## What shipped

- **`BulkResultView`** — `declinesEnded`, and the header paragraph separating the three buckets from
  the one annotation.
- **`CaseBulkService`** — `Context.retainedDeclinesOf` (the one projection), `endsADecline` (the
  guard: `retainedAtTarget` is empty for the seven non-move commands because *nobody asked*, not
  because the folder imposes nothing), the call above the mutation, and `mutate` reading the shared
  projection instead of narrowing locally.
- **The contract** — `BulkResult.declinesEnded`, `required`, described as a subset of `changed` and
  as *populated on the preview*, which is the property a client may rely on.
- **`BulkActionDialog`** — a *"Losing a condition exception"* group in the preview, naming the cases
  and stating the consequence in a sentence (*"moving them back will not bring it back"*), and the
  alert severity raised to **warning**: a skip is something the operation will not do, this is
  something it will do that nobody asked for.
- **`CaseListPage`** — `1 lost a condition exception` in the commit snackbar, as a **warning**. It
  is the only record of the event that survives the dialog closing.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `./gradlew build` | ✅ SUCCESSFUL — **RED first**, see above |
| tests | **2646 · 531 classes · 0 failures** (+3, no new class) |
| `frontend`: `npm run build` · `npm run lint` · tests | ✅ green |
| `packages`: `npm run build` · lint · tests | ✅ green |

`packages/` is rebuilt for `schema.d.ts` alone, as in §192: no hand-written line there changed, and
`schemaFreshness.test.ts` compares the two copies byte for byte.

## Verification

**Four mutation rows, `§193 LD-1`…`LD-4`**, all **NOT RUN** (§65). `LD-3` and `LD-4` are **holes**
and say so: the first because two expressions that agree today cannot be made to disagree by a
mutation, the second because **the retention half of the bound has no witness at all** — since §98 a
suite is flat, so a stored decline always names the departed folder's attachment and no pair of
folders can share one. Today `declinesEnded` and *"this case declined something and moved"* are the
same set, and only the first is the sentence the product means.

**Two browser rows added**, `§193 M2-22j` (the preview names it, and the preview writes nothing) and
`§193 M2-22k` (the snackbar is a warning), both in lane `M2` beside §187's and §192's decline rows,
where the fixture already exists.

**Built and unverified.**

## The next slice

§192's list, corrected (the CLI/MCP decline item is struck — see the top of this section) and minus
what this took:

- **resume the browser queue at `A2 — plans`** — unchanged since §186, and now carrying eleven `M2`
  rows including this slice's two. `§192 M2-22f` and `§193 M2-22j` are the two the live pass would
  have caught the last two slices' subjects with.
- **a third `packages/` live pass** — §189's two write arms and three read commands are all
  stub-observed, and `§189 CN-3` cannot be closed by a stub.
- the **read-only-archive** decision §159 did not take; **serving the relabel audit** (§174);
  **`CurrentUser.teams` has no reader** (§175); **a run has no edit endpoint at all** (§177);
  §184's *what the SPA computes from a contract enum the server also computes*; §185's *a rule that
  fails when a test reads a path outside `src/` that `build.gradle.kts` does not declare as an
  input*; §188's `= null` writer-rule gap and its `releaseSortKey` observation; §189's *a proposal
  cannot carry a decline*; §190's *whether "a comment whose argument expired" has any computable
  form at all*.

**And two this slice opened.**

**The snapshot has no reader.** Refusing the history shape above is a decision to keep a version
list that can say *when* and *who* and never *what* — over a table whose entire purpose is that past
claims stay true, on 500-odd rows per project. `EntityVersionView` has recorded the gap since §28 as
*"an endpoint that does not exist yet"*, and every slice since has had a reason not to be the one.
It is now also the reason a case's own history cannot answer the question this slice answered on the
bulk result.

**The single-case move says nothing either, and it is not obvious that it should.** `PATCH /cases/…`
answers with the whole case, so a client that re-reads the response *can* see the decline is gone —
but the SPA's editor does not say so, and there is no equivalent of a preview on that path. Either
the response grows a field the bulk result now has, or the editor's suite picker warns before the
save, or the honest answer is that a single case is a screen the author is looking at and the bulk
action is 400 rows they are not. Three shapes, and unlike §192's pair this one has a cheap arm — the
picker warning — that could be wrong for a good reason: it fires on a move the author is making
deliberately, one case at a time, which is the definition of a dialog people learn to click through.
