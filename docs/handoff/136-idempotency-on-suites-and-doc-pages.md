# §136 — the header two endpoints had refused in writing, and the premise that expired

**Date:** 2026-08-08 · **Branch:** `master` · **One phase, one migration (`V42`), one contract
change.**

Takes the first of the two prerequisites [§135](135-the-tool-and-command-surface.md) identified —
`Idempotency-Key` on `POST …/suites` and `POST …/doc-pages`, which gates two of the twenty-one MCP
tools. The MCP WIP plan's §6.1 had already audited the gap and filed it as *"add before the CLI
write phase"*; §135 corrected that to **a prerequisite of two tools**, and this is it.

It is not the shape the plan implied. The gap was not an omission.

---

## 1. Both endpoints refused this header, and the refusal was argued

`SuiteWriteService#create` has carried this since §29:

> *"No `Idempotency-Key`, unlike case creation, and the difference is not an omission. A duplicate
> case is expensive to spot — the grid shows two rows with different keys and identical titles among
> hundreds. A duplicate folder is visible in the tree the moment the create returns, next to its
> twin, and is removed by the delete this same slice ships."*

`DocPageWriteService#create` copied it. So this slice is a **reversal of a recorded decision**, not
the filling of a hole — and the interesting part is that the decision was correct when written and
is not being called wrong.

**Its premise expired, in two separate ways.**

- It reasons *entirely from a person looking at a screen*. "Visible", "next to its twin", "is
  removed by the delete" — every clause assumes an observer who notices the duplicate and a hand
  that corrects it. §135's tool surface adds a caller with neither. An agent that times out and
  retries has no correction loop at all, which is the whole reason the header exists.
- **§98 deleted the tree the sentence names.** A duplicate in a flat list of two hundred named
  collections is not visible the moment anything returns. That half had been false for thirty-eight
  sections and nobody read it, because nothing depended on it being true.

Both javadocs are struck through in place with a pointer to `V42`, per the working agreement. The
old text stays: the next reader should not conclude the argument was never considered.

## 2. What a duplicate actually costs, which is not uniform

V14 and V17 ranked the earlier four. This slice adds the cheapest and the most expensive of the six
in one migration.

A duplicate **suite** is the mildest: nothing is filed in it, and the delete this module already
ships removes it.

A duplicate **doc page** is the worst of the six, and the reason is structural rather than
cosmetic. **A page is a link target.** `doc_link` points at `(page_id, block_id)`, and ERD resolved
question #18 makes a `doc_block` this product's entire model of a requirement — there is
deliberately no `requirement` table, which is the finding §135 turned on. So two copies of one
specification are two sets of block ids, and traceability then splits across them: half the cases
verify the statement on one copy, half on the other, and a coverage query over either is silently
short. **Deleting the loser does not merge the links back — it destroys them.** It is the one
duplicate in this family that a person cannot clean up by pressing delete.

That the weakest instance of the copied argument sat on the endpoint where it mattered most is the
ordinary cost of copying a reason instead of re-deriving it.

## 3. The generalisation is refused for the fourth time, and the reason has not moved

`V11` invited exactly this moment: *"a second create endpoint adds its own column rather than
reusing a mechanism … the generalisation can wait for the second, which will then have two real
shapes to generalise from."* `V14` was the second, `V17` the third and it declined; this is the
fourth, at **six columns across six tables**, and it declines again on `V17`'s decisive argument:

> a general `idempotent_request` table would have to store the **rendered response**, because a
> general mechanism cannot re-render one it does not understand — and a stored response is a second
> copy of client content that goes stale the moment the row it describes is edited.

Nothing in that is a function of the count. The column form re-renders from the live row through
the same assembler the `GET` uses, so a replay and a reload cannot disagree. `DocPageEndpointTest`'s
`aReplayCarriesTheBlocks` is the assertion that pins it.

**`V42` adds no table and no policy** — both tables are already company-owned and already forced —
so the count stays at **62**.

## 4. The constant that is copied seven times, and the check that replaces the symbol

`MAX_IDEMPOTENCY_KEY = 200` is now declared in **seven services across four Modulith modules**
(`repository` ×2, `execution` ×3, `documentation`, `work`). Five of those copies predate this slice
and nothing had ever compared them.

The tempting fix is a shared constant, and **the only module all four may import is `tenancy`** —
which everything depends on and which depends on nothing. §125 set the bar for that module when it
extracted `storage`, and refused §3's own proposed `shared` on the grounds that a module every other
module may depend on must not be a grab bag. Spending that decision on an HTTP header's length cap
is the cheapest possible use of it.

So the copies stay and the invariant becomes a computed check —
**`IdempotencyKeyCapTest`**, which reads the source text, boots no Spring context and needs no
database, exactly as `ConfigConventionTest` does for `application*.yml`. Three assertions: every
declaration agrees, there are exactly seven of them, and **the number the contract publishes
(`maxLength: 200`) is the number the code enforces**. The third is the one that would have caught
the drift nobody could see: a generated client trims to the contract's number, so a contract
promising more than the server stores produces a 400 the client's own types say is impossible.

**Mutation `IK-6` is what makes this argument falsifiable** rather than a preference — if changing
one of the seven caps does not redden the test, the reasoning above is wrong and the symbol should
be extracted after all.

## 5. Three smaller findings

- **The plan-document path passes `null` on purpose.** `DocPageWriteService#writeSubjectPage`
  creates a page on a plan's behalf, has no header to carry, and is already deduplicated by
  `uq_doc_page_subject` — a *stronger* constraint than the one `V42` adds. Recorded at the call
  site, because a null that looks like an omission gets "fixed".
- **The sealed `Outcome` did the work.** Adding `Replayed` broke both controllers' exhaustive
  switches at compile time, which is the entire reason those switches enumerate their success arms
  instead of defaulting. Neither could have shipped a replay silently mapped as a problem.
- **The shared `IdempotencyKey` parameter said "the case"** in `openapi.yaml` — written for the one
  endpoint that had it, then reused by six others across `V14`, `V17` and this slice without anyone
  re-reading its prose. Corrected to "the resource".

## 6. Also here: the stale claim §135 found on its way past

`documentation/package-info.java`'s "What is deliberately not built" still said **`doc_link` has no
writer**, in the present tense, stale since §93 — while `DocBlockView`'s header recorded the
supersession correctly. §135 declined to fix it (that slice shipped no code) and filed it for the
first implementation phase. This is it: struck through, with what is *still* absent (`coverage`, for
§81's reason) separated from what stopped being absent forty sections ago.

It is the same shape as the four documents that called the Linux gate inert for two days — a
supersession written somewhere true and not everywhere the claim appears — and this one was worse
placed, because a package comment is the file an agent reads *first* to orient in a module.

## 7. Gates

`./gradlew build` — **BUILD SUCCESSFUL in 11m59s, 2242 tests across 475 result classes, 0
failures** (+21 tests, +3 classes over §134's 2221/472). `npm run build` clean, `npm run lint` 0
warnings.

`npm run test:run` came back **1172 passed / 5 failed**, and the five are the **known load-dependent
cliff** rather than anything this slice did: all five are in `CaseEditorPage.test.tsx` in a 342 s
run, and **that file alone passes 30/30 in 125 s**, verified immediately afterwards. This slice
changed no frontend code — its only frontend artifact is the regenerated `schema.d.ts`. Worth
recording that §130 did **not** take that file off the list, as the handoff's box implied: it made
it faster, not safe, and this is the third run to land on `paste` as the remedy without it being
done.

## 8. Obligations

- **§65.** Seven mutation rows appended, `IK-1` … `IK-7`, none run. Two are recorded **known gaps**:
  nothing in this repo drives two concurrent creates carrying one key (true of all six tables since
  `V11`), and the doc-page class has no second granted project, so its scope test crosses companies
  rather than projects.
- **No frontend row, and no frontend change.** The SPA does not send the header on these two
  endpoints and this slice deliberately does not make it: the old argument still holds *for a person
  at a screen*, and adding a key per mounted dialog is a separate, visible change. Nothing the
  browser queue could drive behaves differently.

## 9. Next

The second of §135's two cheap prerequisites is now done (the `doc_link` comment), so what remains
is the MCP WIP plan's **phase 4 — the shared TS client generated from `openapi.yaml`** — with
eighteen of the twenty-one tools buildable against today's contract and **twenty** now that these
two are unblocked. `tcms_write_case`'s create arm is still the one waiting on phase 6's
`ai_proposal`.
