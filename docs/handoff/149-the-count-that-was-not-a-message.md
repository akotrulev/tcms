# §149 — the other half of §148's rule, and the count that was not a message

**2026-08-09 · one phase · a contract change, no migration. `backend/` (one production line and its
tests) + `docs/specs/openapi.yaml` + `frontend/` + both generated `schema.d.ts` copies.**

§148 pinned the two statuses that carry nothing and recorded, in as many words, what it had not
looked at: *"`Conflict`, `BadRequest` and `TooManyRequests` were not audited site-by-site and
nothing pins that direction — a `.build()` on a 409 would pass today."* This is that audit.

**The direction itself was already right** — every one of the 98 sites that answers 400, 409 or 422
writes a `Problem`, and every declared one promises a body. That half of the slice is pinning, not
repair. **What the audit found is one layer down: not whether there is a body, but whether what is
inside it is a message.**

---

## The finding: one key, two meanings, and only one of them is English

`Problem.errors` is `{name: [messages]}`. Four sites on this API build one from literals rather than
from a service's validation output, and two of them use the same key, `children`, for a delete that
is refused because rows still point at the target:

| Site | `errors.children` |
|---|---|
| `identity/TeamController` | `"3 teams sit below this one. Move or delete them first."` |
| `project/ProjectController` | `"3"` |

Both are 409s. Both are read by the same client code. **One is a sentence and the other is a
datum**, and nothing in the type says which — `array of string` describes both.

The classification site's javadoc had argued for it explicitly, and both halves of the argument were
wrong:

> *"The count is on the problem rather than in the sentence because the SPA renders it … and a
> number embedded in prose is one a client has to parse back out. `SuiteWriteService`'s `NotEmpty`
> does the same for a folder."*

`SuiteWriteService`'s `NotEmpty` does the **opposite**: it puts the count in the sentence, on
`detail`, and writes no `errors` at all. The precedent cited as agreement was the one site that
disagreed with it — the §100 shape this repo keeps meeting, a comment asserting the convention it
breaks, and the fourth sighting.

## Why "a client has to parse it back out" is the wrong client

It was true of the SPA and false of everything else, and the everything-else arrived after the
comment was written.

- `@tcms/cli` prints `  name: message` under a failure — §139.
- `@tcms/mcp` appends the same lines to what it hands a **model**, and its own javadoc explains why:
  *"Without this a model is told 'Check the highlighted fields.' and nothing else … Keys are spelled
  as on the input schema, so they name the tool's own argument — which is what makes this actionable
  rather than merely longer."* — §143.

Against the classification 409 that renders as **`children: 3`**: a key naming no argument, a value
naming no action. §141's rule is that a refusal which does not say what to do next is the one a
caller retries unchanged, and this is that refusal in its purest form — the caller is told a number.

**The SPA was the only reader that could recover the meaning, and only because that one dialog knew
which of the two conventions this one endpoint used.** `RetireValueDialog` composed
*"{n} value{s} sit under this one. Retire those first — …"* around the count in JSX, so on screen it
looked correct and nothing on that side could observe the divergence. (It also had a plural bug the
composition made possible: `1 value sit under this one`.)

**And the test pinned the wrong side, on both stacks.** `ClassificationValueWriteEndpointTest`
asserted `containsExactly("2")`, and the SPA test *supplied* `{ children: ['2'] }` and then asserted
the page showed `2` — §148's `describeError` shape exactly, one slice later: a fixture that invents
the payload it is checking cannot disagree with the server.

## What changed

- **`ProjectController.childrenProblem` sends a sentence**, with `TeamController`'s singular/plural
  pair. The count is still in it and still comes from the row.
- **`RetireValueDialog` renders it as it arrives** — `DeleteTeamDialog`'s shape, whose comment
  already said why: *"the server counted, and a second phrasing on this side is a copy that
  drifts."*
- **`Problem.errors`'s description is corrected.** It claimed the values are "the messages that
  field failed on, present only on a validation rejection". Both clauses were false: `errors` also
  appears on two 409s and two 403s, where the key names a blocking population rather than an input.
  The rule that actually holds across all of them is the one that had never been written down —
  **every value is a complete sentence any client may print verbatim.**
- The two 409 operation descriptions said their `errors` carried "the counts". One of them had
  always sent sentences. Both say sentences now.

## The checks

All five are in `ErrorBodyPolicyTest`, beside §148's three, and each carries a floor so a scan that
stops matching fails rather than passing over nothing.

| Test | Pins |
|---|---|
| `bodiedStatusesDeclareContent` | every declared 400/409/422 declares `content`, **plus the `TooManyRequests` component** — which no operation references on purpose (§141), so an operation walk alone would leave the one status a model most often meets unchecked |
| `noHandlerOmitsABodyOnABodiedStatus` | all 98 sites are followed by `.body(`, across six spellings |
| `noBackDoorSpellingOfABodiedStatus` | `status(409)`, `HttpStatusCode.valueOf`, `SC_CONFLICT` and friends are banned — §148's `HttpStatus.NOT_FOUND` ban generalised, because a scan matching six spellings leaves the seventh as its hole and then reports a clean run over code it did not read |
| `everyErrorsValueIsASentence` | a literal `errors` value must contain words. `List.of(Long.toString(n))` has no `"` in it; this is the check the slice exists for |
| `nonValidationErrorsSitesArePinned` | the four sites keying `errors` by a blocker are those four, so a fifth is a contract decision |

**Both new rules go red on the pre-fix tree** — verified by restoring the old
`List.of(Long.toString(…))` (the datum check fails, the other seven pass) and, separately, by
turning one `SuiteController` conflict into a `.build()` (the body check fails).

## Gates

`./gradlew build` — 2328 tests / 490 classes, 0 failures (+5 tests, no new class).
`frontend`: `npm run build` clean, `npm run lint` 0 warnings, `npm run test:run` **1198/1204 —
the six are the recorded cliff**, all in `CaseEditorPage.test.tsx`, every one a 30 s timeout and
never an assertion, in a 375 s loaded run; that file plus the one this slice changed pass **38/38 in
106 s** run alone. That is the **sixth** run to reach §127's recorded conclusion without `paste`
being done. `packages`: build clean, lint 0, 229 tests.

## What this did not audit

`errors` maps built by a **service** rather than at the controller — the validation path, which is
most of them. The rule is stated in the contract now and checked at the four literal sites; a
service that puts a bare number in a validation message would still pass. Making that mechanical
means reading through `Outcome.Invalid` in a dozen services, which is a wider slice than this one
and has no known instance behind it.
