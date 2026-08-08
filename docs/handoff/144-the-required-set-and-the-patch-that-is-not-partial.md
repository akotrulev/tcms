# §144 — the required set the contract had wrong, and the `PATCH` that is not partial

**2026-08-08 · `master` · one phase · `docs/specs/openapi.yaml`, `backend/`, `frontend/`,
`packages/` — a contract change, no migration.**

The two defects §143 recorded and did not fix. They are one slice because both turn on the same
thing: **`TestCaseInput` declared `required: [title]` from M0 while `CaseEdit.validate` required
four**, and every consequence of that runs through a client the SPA is not.

Fixing them turned up **two more instances of the same defect that §143's live pass never drove**,
and one of those loses data silently.

---

## The contract was wrong, and the direction it was wrong in is the whole story

`CaseEdit.validate` refuses `title`, `stepFormat`, `priority` and `automationStatus` on create
*and* on edit, deliberately, each with its reason written beside it. The schema said one. Both
copies of `schema.d.ts` therefore typed three mandatory fields as **optional**, so a caller that
omitted them compiled, typechecked, linted, and 400ed at runtime.

A contract that requires *more* than the server produces callers who send a field nobody wanted.
A contract that requires *less* produces callers that cannot write at all — and produces them
silently, through a generator, in every language anybody ever points at the file.

`required` is now the four. Both `gen:api` runs were re-run; a frontend test fixture stopped
compiling, which is the first thing in this repo that ever objected:
`ProposalPage.test.tsx`'s `PENDING` proposal carried `title` and `objective` and nothing else, so
the SPA's own test asserted the shape of a proposal that could only ever be Rejected.

**Four, and not the other four.** `steps` is required only for `STRUCTURED`, `gherkinSource` only
for `GHERKIN`, `automationKey` only for `AUTOMATED`, `notAutomatableReason` only for
`NOT_AUTOMATABLE`. Stating those in the schema needs a `oneOf` splitting the shape in four for four
fields. They stay optional on the contract and refused on the server, which is the honest
arrangement rather than a complete one, and the schema says so.

### The check that would have caught it, which nothing in this repo was

`CaseUpdateEndpointTest.contractRequiredMatchesWhatTheServerEnforces` drops each editable field in
turn from a payload the server accepts, and asserts `TestCaseInput.required` names **exactly** what
it then refuses. It fails in both directions.

**This is the first test here that compares `openapi.yaml` to the server's behaviour rather than to
itself.** `ContractConventionTest` reads the contract against its own rules — it would happily
approve a `required` array naming one field or ten. Every endpoint test sends a complete payload,
which is precisely why 2302 of them never noticed. `OpenApiContract.requiredOf` went from
package-private to public for this one caller, and the reason is in its javadoc.

The §48 rule, restated one more time: a catalogue-wide invariant needs a computed check. This one
was two catalogues that had to agree, and nobody had written the comparison.

---

## `PATCH` here is partial by verb only, and three clients were sending deltas

`CaseUpdateRequest`'s header has said it since M0: Jackson gives absent and null the same Java
value, so the endpoint cannot tell *"leave `objective` alone"* from *"clear it"* and resolves both
as **clear it**. The SPA satisfies that by construction — its editor submits every field it renders
on every save — so the server has never been wrong about it and has had exactly one client that
could tell.

`@tcms/client`'s new **`core/rewrite.ts`** is the read-modify-write both other clients now use:
read the resource, project it back into its own input shape, lay the caller's change on top. Three
projections, and the file carries the argument for each exclusion.

**It does not reopen the `If-Match` hole `core/mutation.ts` closed.** That hole is a body composed
from an *earlier* read carrying a *fresh* version — the precondition asserts a state the body was
never based on. Here the base and the version come from the **same** read, one millisecond before
the write. The call sites pass `() => current` for exactly that reason, and the `--file` / `blocks`
route still demands an explicit `lockVersion`.

What it does change, and it is worth stating: a concurrent edit to a field the caller never named
is now overwritten with the value this read saw, rather than left alone. The alternative was
clearing it outright, and `If-Match` still refuses the whole write if anything moved in between.

### Two more instances, neither of which §143 drove

- **`suite edit`** — `SuiteInput` requires `name`. Editing only the tag rules was a 400 about a
  field nobody mentioned; editing only the name **emptied the description**.
- **`plan edit`** — this arm already threaded `name` through from its own read and was right to.
  What it did not thread was every *other* scalar, and `PlanEdit.validateUpdate` writes each one
  straight through while resolving only the **lists** against what is stored. So an edit naming the
  milestone emptied the description, and **`scopeText` / `outOfScopeText` — reachable from no flag
  and no tool argument — were emptied by every plan edit either client has ever made.** Silent data
  loss on a command that reported success, which is worse than the 400 that started this.

That arm's comment said `TestPlanUpdateInput` requires `name` *"alone among the edit shapes on this
surface"*. It is not alone; `SuiteInput` requires one too and `TestCaseInput` requires four. The
sentence was written while looking at one schema.

**Two endpoints were checked and deliberately left alone.** `DocPageInput` — `blocks` absent means
*leave alone* server-side (`DocPageWriteService` guards on `blocks() != null`) and `title` is the
only other editable field, which a title-only edit supplies. `ExecutionResultInput` — recording a
result is a whole statement rather than an edit to one: the verdict and its note are submitted
together and *no note* is a legitimate answer.

---

## The proposal that could never be accepted, refused where somebody can act on it

`POST …/ai-proposals` stores its payload **unvalidated by design** — §138's boundary keeps
generated content out of `test_case` until a person accepts — and the create rules run at *accept*.
So a proposal missing a required field is filed **201**, sits `PENDING`, and answers the reviewer's
Accept with a 400 on fields that are not on the proposal and cannot be edited there. Their only
move is Reject, for a case whose content is fine.

Fixing the contract only helps a caller that reads it. `core/proposal.ts` refuses an unacceptable
proposal at the **CLI and MCP boundary**, where the author is still holding the thing:

- **presence only, deliberately** — which *values* are legal is the server's to say, and a second
  copy of `CaseEdit.validate` here is a second answer waiting to diverge;
- **every missing field at once**, never the first — a model that fixes one and refiles, three
  times, has left a person three proposals to reject;
- the conditional four are checked too, because each is decidable from the payload alone.

`tcms_write_case`'s tool description now names all of them, and its `priority` / `severity` /
`automationStatus` properties carry real `enum`s. The old description said *"title (required),
description, objective, expectedOutcome, priority, severity, steps…"* and did not mention
`stepFormat` or `automationStatus` at all.

---

## What the tests were worth, and what had to be corrected in them

**The package suites passed the whole way through this defect**, because a stub answers 200 to
whatever arrives. Three fixtures were false and had to be fixed before the new assertions meant
anything:

- `CASE`/`CASE_DETAIL` were four fields where the resource has thirty, so a fixture missing
  everything the server requires agreed with a client that sent nothing;
- `automationStatus: 'NONE'` and `stepFormat: 'STEPS'` appeared in two MCP fixtures and **neither
  is a value this API has** — `NONE` is the literal string §143 sent at a real server to find the
  401;
- the `ProposalPage` fixture above.

The new client-side check derives the projected field list **from the contract**: a field added to
`TestCaseInput` later fails the build until somebody decides whether the round-trip carries it.
That is the same shape as the backend check and for the same reason.

## Gates

`./gradlew build` **SUCCESSFUL**, 2308 tests, 0 failures. `packages`: build clean, `oxlint` 0,
**229 tests** (66 + 85 + 78). `frontend`: `npm run build` clean, `oxlint` 0, **1203 tests**.

## What is verified and what is not

**Nothing here is verified live.** Every gate above drives a stub, a jsdom or a MockMvc — the
observer class §143 had to step outside of to find these defects in the first place, so the fix for
a defect found live is not verified by the gates that missed it. Rows added:

- **`LP-1` … `LP-8`** in [`docs/verification/2026-08-08-packages-live-pass.md`](../verification/2026-08-08-packages-live-pass.md)
  — the eight things a real instance has to be asked. `LP-4` is the plan's prose surviving a
  milestone edit; `LP-8` is the concurrent edit that must 409.
- **`CR-1` … `CR-3`** in [`docs/verification/backend-mutation-checks.md`](../verification/backend-mutation-checks.md)
  — `CR-1` is the original defect restored.
- **`A4-11`, `A4-12`** in [`docs/verification/frontend-browser-checks.md`](../verification/frontend-browser-checks.md)
  — a person completing the file → PENDING → Accept path, which has never once completed.

**§138's charter in the browser queue was amended, and the amendment is itself a finding.** Its
third fixture proposal was `{"title": …, "suiteId": …}` — written from the contract — so when
`A4-4` finally ran it would have produced a 400 naming four fields, three of them nothing to do
with the suite, and the row would have read as a pass. It now carries the four. It must still be
filed with `curl`: **the CLI can no longer produce that fixture, and should not be able to.**
