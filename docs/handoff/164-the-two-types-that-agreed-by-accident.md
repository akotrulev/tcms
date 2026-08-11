# §164 — the two types that agreed by accident, and the schema in no pairing check

**2026-08-11 · one phase · `backend/` tests + a contract *comment*: no migration, no production
Java, no frontend or `packages/` source, and neither `schema.d.ts` moves. Two rules, both closing
gaps §163 named on its way out.**

## Where this slice came from, which is not where slices usually come from

**§163 was written twice, concurrently, by two sessions that never saw each other.** One committed
and pushed (`0d06cbb`); the other was still uncommitted in this working tree when the pull happened.
Both had opened from §162's declared-and-undecided `instance`, both measured the same thing, and
**both reached the same decision — suppress** — by different mechanisms: the pushed one renames
`ProblemMediaType` to `ProblemEnvelope` and clears the property there, the local one added a second
`ResponseBodyAdvice` called `ProblemInstancePolicy` beside it.

The pushed variant was taken whole, and the merge was a clean fast-forward with no textual conflict
at all — which is the thing worth recording. `git` had nothing to say here: the collision was
*semantic*, two implementations of one slice number, and the only reason it did not end as one
variant silently overwriting the other is that the diff was read before the pull. It is
`CLAUDE.md`'s "running work in parallel — don't" arriving through a door that section does not
name: not two agents in one tree, but two trees on one slice.

**Their wire outcomes were byte-identical** — both delete exactly `instance?: string | null` from
both `schema.d.ts` copies — so nothing shipped turned on the choice. What differed was the checks,
and that is what this slice is.

## What the pushed §163 named as its own next work, and did not do

Two things, both in its closing section, both quoted here because they are the whole scope:

> *"`Problem` is the one contract schema written by two Java types, and nothing compares their field
> names. … §163 has now removed the one member that differed, which makes the two types agree
> **today** with no check saying so."*

> *"The subset rule catches a property the server sends and the contract does not declare; it says
> nothing about a property the contract declares and one of the two types cannot produce, which is
> the §148 direction."*

The other §163 had already written both rules. So this slice is not a re-derivation — it is the
work that existed, ported onto `ProblemEnvelope`, and the reason it is a slice of its own rather
than an amend is that the commit it belongs to is pushed.

## The two rules

### `bothEnvelopesSerializeTheSameKeys`

The two envelopes compared **as serialized keys, on equivalent refusals**. §162's mutation queue
named this gap and gave the reason it stayed open: *"it is written by two Java types, only one of
which this project owns, so the record-component comparison has nothing to compare."* True of a
**static** comparison and false of the bytes, which is what a client receives — so the comparison
is made there, through the same standalone `MockMvc` fixture the file already stands up.

`noRefusalBodyEchoesTheRequest` does not cover this and cannot: it is a **subset** rule against the
contract, so both envelopes can satisfy it while sending *different subsets*. That is not
theoretical — it is exactly the mutation below.

Both shapes are compared, plain and validation, which needed a new fixture: `/record-with-errors`,
the record's `Problem.validation(…)`, as the counterpart to the existing `/detail-with-errors`.
`errors` is the one optional member, so it is the one a writer can set and its counterpart omit
while every other rule in the file stays green.

### `theContractsProblemIsThisRecord`

`Problem`'s declared properties against `com.tcms.api.Problem`'s record components, **both
directions**. §161's `ContractEnumTest` is this rule for the twenty enums;
`ContractConventionTest.RESPONSE_RECORDS` is it for four response schemas. **The envelope every
refusal in this API answers with was in neither** — which is how the schema grew a property no Java
type here declares and kept it for sixteen sections.

The two directions fail differently and that is why both are asserted. A property the contract has
and the record lacks is a promise nothing keeps — §148's defect. A component the record has and the
document lacks is a value on the wire no generated client has a name for, which is the direction
`instance` went.

## The mutations — run before this was written, per §161–§163

| # | mutation | result |
|---|---|---|
| `§164 EN-1` | `setProperty("traceId", …)` on the `/detail` fixture — an **undeclared** member on one envelope | red: **`bothEnvelopesSerializeTheSameKeys`** *and* `noRefusalBodyEchoesTheRequest` |
| `§164 EN-1b` | `setProperty("errors", …)` on `/detail` — a **declared** member, on one envelope only | red: **`bothEnvelopesSerializeTheSameKeys` alone**; every other rule green |
| `§164 EN-2` | `traceId` added to `Problem`'s properties in `openapi.yaml` | red: **`theContractsProblemIsThisRecord` alone** |

**EN-1b is the one that justifies the test existing.** EN-1 is caught twice, so on its own it would
not show that the new rule earns its place; EN-1b is a divergence between the two envelopes that
every pre-existing rule in the file passes, because `errors` *is* declared and each envelope's
members remain a subset of what the contract allows. That is the shape `instance` had, minus the
part that made it visible.

**`§164 EN-3` was attempted and abandoned, and is recorded rather than quietly dropped.** The
intended reverse mutation — add a component to `api.Problem` — does not compile: the canonical
constructor is called at ~50 sites, so the record cannot grow a component without every one of them
being edited first. The direction is not left unproven, it is proven by something stronger than a
mutation: set equality is symmetric, so EN-2 fires the same assertion the reverse case would, and
the compiler refuses the reverse case outright before any test runs.

All three reverted; `git diff` after the run is the two files this slice changes and nothing else.

## Also

`openapi.yaml`'s `Problem` still said **`api.ProblemMediaType` makes it one type** — a class the
same commit had renamed to `ProblemEnvelope`. A stale name left by the rename, in the document that
is the contract's own account of the decision. Corrected, and the comment now names both new rules,
so the next person adding a property to that schema reads why the record has to change with it.

## Gates

`cd backend && ./gradlew build` — **SUCCESSFUL (9m56s), 2457 tests / 506 classes / 0 failures**
(§163: 2455 / 506; **+2, no new class** — both rules land in `ErrorBodyPolicyTest`). Counted off
`build/test-results/test`, not off the console summary.

**Neither `schema.d.ts` is regenerated and neither needed to be**: this slice's only `openapi.yaml`
edit is `#` comments, which `openapi-typescript` does not read — nothing under `properties`,
`required` or any `description` moved. `frontend` and `packages` are untouched for the same reason,
so their gates are unrun and are not claimed here.

## Queues

Nothing reaches a screen or changes a byte on the wire: both rules are checks over things that
already hold, and the only product-facing edit is a YAML comment. **No new rows** in
`docs/verification/frontend-browser-checks.md` — the §163 rows already queued cover the wire change
this slice is asserting, and adding a row for an assertion would put an unrunnable line in a queue
whose value is that every line can be executed. The mutation table above is this slice's evidence
and lives here rather than in `backend-mutation-checks.md`, which is where §161–§163 put theirs.

## The next slice

`ContractConventionTest.RESPONSE_RECORDS` is **four entries and one direction**, and this slice
sharpens rather than answers the question §163 raised about it. It maps four schemas to four records
and checks that every *required* property has a component — so a component the schema does not
declare passes, which is the direction `instance` went, and every schema outside the four is
unchecked in both. `Problem` now has its own two-way rule; the other schemas do not, and the
argument in that file's comment — that the pairing "is a fact only a human knows" — is true of the
*pairing* and says nothing about the *size of the set*. Generalising it is a slice.

Otherwise unchanged from §163: the browser queue, `TeamRef` (two copies — `project.TeamRefView`,
`identity.RoleAssignmentView.TeamRef`), the three remaining health derivations (two need
`documentation`, which `repository` may not depend on; `orphaned` needs a product decision first),
or the read-only archive decision §159 deliberately did not take.

**One process item, and it is the most portable thing here.** Two sessions produced two §163s and
the numbering could not tell them apart, because a section number is claimed by writing the file and
nothing publishes the claim until it is pushed. The collision was caught by reading a diff. It would
not have been caught by `git`.
