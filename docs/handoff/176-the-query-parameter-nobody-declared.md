# §176 — the query parameter nobody declared, and the grid the four rules make

**One phase, `backend/` only, and test code at that: no migration, no contract change, no frontend
or `packages/` source, and nothing in `src/main` changed.** §175's named next slice, taken from the
top of its list, quoted verbatim:

> **the bound-and-undeclared query parameter**, §171's recorded gap

One test method — `ContractParameterBindingTest.noHandlerBindsAnUndeclaredQueryParameter` — plus the
javadoc that says which of four rules owns which of four positions. It is the cheapest slice this
repo has, for the second time running, and the reason it was worth doing rather than leaving
recorded is §171's own sentence: **a recorded gap and a closed one look identical from every green
build.**

## What §171 recorded, and why it recorded it instead of writing it

§171 closed the multipart pair and left one position open in as many words:

> The mirror of it stays open on purpose. `everyDeclaredQueryParameterIsBound` is one-directional
> (declared ⊆ bound) and its counterpart — *bound and undeclared* — is not written, because on these
> four operations it would report `caption` and `stepId`, which are form fields. A bound-and-undeclared
> query rule is a real check and it needs the media type in hand; it is a slice, not a line.

That is the rare case of a deferral whose stated reason was **true when written and still true
today**, which is worth naming because this repo has spent sixteen sections finding the other kind —
§160's expired blocker, §161's claim that was never true, §162's exemptions that excused nothing,
§172's queue row for an affordance that did not exist. `@RequestParam("caption")` binds the query
string key `caption` and the text form field `caption` identically; the annotation carries nothing
that tells them apart and neither does the handler. A rule reading the signature alone genuinely
cannot answer this question.

**The media type answers it, and the resolution is a skip rather than a subtraction.** On an
operation with a `multipart/form-data` body, *every* bound name belongs to
`noHandlerBindsAnUndeclaredFormPart` — declared or not — so the query rule skips those four
operations outright. The alternative, subtracting the declared parts, looks equivalent and is not: a
name declared on neither side would then be reported by **both** rules, under two different
diagnoses, and a failure message that names two repairs for one edit is how an author picks the
wrong one.

The mirror of that decision was already in the code. `noHandlerBindsAnUndeclaredFormPart` subtracts
the operation's declared query parameters, which §171 wrote as a decision about a day that had not
come. This is the same decision written from the other end, and the two together are what make each
name have exactly one owner.

## What the rule catches that nothing else could

The four rules in this class are two questions asked of two populations, and the grid is now
complete — it is in the class javadoc as a table so the next reader meets it before the methods:

| | query string | `multipart/form-data` |
|---|---|---|
| declared, unbound | `everyDeclaredQueryParameterIsBound` — a 200 answering a broader question | `everyDeclaredFormPartIsBound` — a 201 describing a dropped part |
| bound, undeclared | **`noHandlerBindsAnUndeclaredQueryParameter`** (this slice) — unreachable code | `noHandlerBindsAnUndeclaredFormPart` — unreachable code |

The row this slice fills is the one that **never fails at runtime**, and that is what makes it the
harder half to find. The parameter is absent from both copies of `schema.d.ts`, so no generated
client can send it; the argument is its default on every request that has ever been made; the branch
behind it has never executed. **From inside the code, a filter nobody can reach reads exactly like a
filter nobody happens to use.** It is the writerless shape this repo keeps meeting — §116's three
tables, §121's seam, §152's ten repository methods, §172's hook — arriving at a request parameter
instead of at a column.

Mutation `§176 QP-3` is what shows the boundary between the new rule and the old one honestly. A
*rename* was already half-covered: rename `mine` in the contract and the declared-and-unbound rule
reds, rename it in Java and the same rule reds from the other side. What had no observer at all is an
**addition** — a handler gaining a filter whose name never reaches `openapi.yaml` — and that is the
edit somebody actually makes.

## Green on arrival, and that is the expected outcome

All seven tests in the class pass unmutated: no handler in this codebase binds a query parameter the
contract does not declare. §171 shipped green for the same reason and said why — a check written
after its subject reports nothing on the day it lands, and what it buys is the next edit. §154, §156
and §161 shipped red; this family does not always, and a slice whose value is entirely in the future
has to be honest that it bought nothing today.

**The floor was measured rather than guessed.** Raising `assertThat(compared).isGreaterThan(5)` to
`500` temporarily printed **13** — thirteen non-multipart operations bind at least one query
parameter. That number is the tripwire's justification, and it is left at 5 rather than 13
deliberately: a floor tracking the exact count reddens on every legitimate deletion of a filter, and
what it teaches the next author is to edit the number.

## No ignore list, on purpose

`everyDeclaredQueryParameterIsBound` has `DELIBERATELY_UNBOUND` and a staleness rule guarding it,
because a declared parameter can legitimately be one the server answers identically with or without.
The new rule has **neither**, following `everyDeclaredOperationHasAHandler` and §171's three form
rules: there is no honest reason for a handler to bind a key no declared client can send. The two
repairs are declare it or delete it, and an ignore list is a third that fixes nothing — plus a second
empty map to keep honest, which §162's audit is the record of the cost of.

## Mutation-tested, in-slice, all three run

`§176 QP-1`…`QP-3` in
[`docs/verification/backend-mutation-checks.md`](../verification/backend-mutation-checks.md). Every
one is a check over a check — none exercises the product — so running them in-slice is §165's, §170's
and §171's precedent rather than the §65 exception §72–§76 wrongly took. All three mutate a file in
`src/main` or the contract, because the handler's signature and the document are the only two sides
of this comparison, and each was reverted in the same command.

`QP-2` is the ownership row and is deliberately **expected-green on the rule this slice added**: the
undeclared parameter goes on `EvidenceController.attach`, a multipart operation, and it must redden
the *form* rule alone. It did. If the query rule had reddened too, the skip is not a boundary; if the
form rule had stayed green, the skip is a hole and a dropped part on the four upload paths is
unobserved again.

## Gates

`./gradlew build` on `shanks` (the Linux lane's own box, per `CLAUDE.md`'s remote-gate section)
**SUCCESSFUL in 7m20s — 2539 tests across 516 result classes, 0 failures** (+1 test, no new result
class). No frontend or `packages/` change, so neither of those gates was in scope. **Nothing was
added to the browser queue**: there is no product behaviour here to drive, the same reason §170 and
§171 added none.

## The next slice

§175's list, minus the entry this one took. The browser queue is at the **bottom** at the user's
direction (2026-08-12).

- **Git traceability (M4)**, from §170's deletion of `IngestionRequest.branch`/`.commitSha`;
- **`OUTDATED_SPEC_CHANGED` / `UNLINKED`**, derivable from `case_doc_link`;
- the **read-only-archive** decision §159 deliberately did not take;
- **serving the relabel audit** — §174's recorded absence, if a link history is ever worth a screen;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument and nothing
  renders it; the question is which screen owes the person their own teams, not whether to delete it;
- **the bound-and-undeclared *form field* on a non-multipart operation** is *not* on this list, and
  the absence is deliberate: a `@RequestParam` on an operation with no form body is a query key by
  definition, so the position does not exist;
- **run the browser queue** — ~700 rows, thirteen lanes, one sitting, scoped in
  `frontend-browser-checks.md`.
