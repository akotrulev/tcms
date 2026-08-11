# §163 — the property nobody wrote, and the decision §162 left declared

**2026-08-11 · one phase · `backend/` + contract + both `schema.d.ts` copies: no migration, no
frontend or `packages/` source. One wire property removed, from the majority of the refusals this
API can send.**

§162 declared `instance` optional in the contract and named the reconciliation as the next slice's
work: `ProblemDetail` writes the refused request's path as `instance`, and §146 had deliberately
removed `path` from the container's error body on the grounds that a refusal does not echo the
request back. Two positions, sixteen sections apart, that nobody had compared.

**The slice opened with a hypothesis that `instance` is written on zero refusals** — plausible
enough to be worth an hour: `setInstance` occurs nowhere in `backend/src`, there is no
`ResponseEntityExceptionHandler` subclass and no exception-handling `@RestControllerAdvice`, the
controllers build `ProblemDetail.forStatus(…)` and set only title and detail, and Spring's Jackson
mixin is `NON_EMPTY`. On that reading §162 had declared a property nothing writes — §156's
writerless shape, introduced by the slice auditing stale claims.

**It is false, and measuring it took four minutes.**

## What was measured

A throwaway standalone `MockMvc` — no context, no database — returning each envelope the way its
controllers return it, and printing the serialized body:

| fixture | body as actually serialized |
|---|---|
| `api.Problem.stale("case")` | `{"type","title","status","detail"}` |
| `ProblemDetail.forStatus(CONFLICT)` + title + detail | `{"type","title","status","detail",`**`"instance":"/detail"`**`}` |
| the same with `setProperty("errors", …)` | the five above **plus** `errors` |

Identical under `Accept: */*` and `Accept: application/json`. So `instance` is written, on **every
one of the twenty-one `ProblemDetail` controllers** — and on none of the other three writers: the
record's eight files (~50 sites), `ProblemWriter`'s filter 429, and the container's `/error`
dispatch all omit it.

**Nothing in this repository asks for it.** The writer is Spring Framework 6.2.11's own
return-value handling, confirmed against the bytecode rather than inferred:
`HttpEntityMethodProcessor` and `RequestResponseBodyMethodProcessor` each do
`if (detail.getInstance() == null) detail.setInstance(URI.create(request.getRequestURI()))` before
handing the body to a converter. Not `ResponseEntityExceptionHandler`, which is where the hypothesis
put it and which this application does not register at all —
`spring.mvc.problemdetails.enabled` appears in no config file, so Boot's own handler is off.

**That is why the hypothesis was reasonable and wrong in an instructive way.** Every instrument the
guess was built from reads `src/main`, and the property is not in `src/main` — it is added between
the controller and the converter. §108's lesson is that when the defect lives in the text, the check
must read the text; this is the same lesson from the other end, and the one this repo meets less
often: **when the value is written by something you did not write, only the value can be read.**

## The decision: suppress

The choice was between suppressing `instance` (§146's position, applied everywhere) and amending
§146's reasoning (declare the echo legitimate and add it to the other three writers). Three of this
repo's own rules point the same way:

- **§162's one-envelope rule.** The whole of the previous slice was that a property of a refusal
  must not depend on which of four writers produced it. `instance` was exactly that asymmetry, one
  property over. *Uniform* is the requirement; which way it goes uniform is this decision.
- **The other direction costs three new echoes to publish nothing.** `api.Problem` would gain a
  component no factory can fill — a record cannot know the request — `ProblemWriter` would compose
  the path by hand in a filter, which is the hand-written JSON §146 deleted arriving back, and
  `ProblemErrorAttributes` would have to re-add the exact key §146 removed. Three writers, for a
  value the caller already has: it is the URL it just sent.
- **§141's test for what belongs in a refusal.** A refusal that does not say what to do next is the
  one a model retries unchanged. `detail` says what to do, `errors` says which field. `instance`
  says where the caller just was, and is the only member of this envelope carrying nothing the
  caller lacks.

Nothing reads it, either: `instance` appears in no consumer in `frontend/src` or `packages/*/src`
outside the generated schema. That is not the argument — §162's media type had no reader and was
still worth fixing — but it does bound the risk to zero.

**`api.ProblemMediaType` is renamed `api.ProblemEnvelope`** and clears it. A class that strips a
property under a name saying it decides a header is §152's shape — a javadoc, or here a name,
revised into being wrong by the commit that widened what it does. The advice already runs on every
controller response and is already the one place this API's refusals are finished; it must run
*after* the return-value handler for the clear to work, and it does, because
`HttpEntityMethodProcessor` sets `instance` and then calls `writeWithMessageConverters`.

**This is not a second decider for a value Spring decides.** The advice still leaves
`ProblemDetail`'s *media type* to Spring, for §162's reason — there Spring is consistent across both
envelopes, and a second decider is how two spellings diverge. `instance` is the opposite case:
Spring decides it for one envelope only.

## The checks — three, and two of them read the server

- **`ErrorBodyPolicyTest.noRefusalBodyEchoesTheRequest`** — the server half, off the real
  serialization. It has to be: the property is written between the controller and the converter, so
  **no scan of `src/main` can see it**, which is precisely what made the opening hypothesis predict
  zero and get twenty-one. Asserted as a **subset** of what `openapi.yaml`'s `Problem` declares
  rather than as a ban on two names, so the rule generalises to any undeclared property arriving on
  a refusal — §148's and §162's family of defect stated once, in the direction neither covered.
- **`ErrorBodyPolicyTest.theProblemSchemaDeclaresNoRequestEcho`** — the contract half, and it exists
  because the server rule is a subset check: a future slice could make it pass by *re-declaring* the
  property, which is the edit this one reversed. With both, re-adding `instance` costs a red run in
  the file that argues why. The banned list is a family (`instance`, `path`, `uri`, `url`,
  `requestUri`, `requestUrl`, `resource`) for `noBackDoorSpellingOfABodiedStatus`'s reason: a rule
  that knows one spelling leaves the others as its hole.
- **`ErrorDispatchStatusTest.aControllerProblemDetailCarriesNoInstance`** — a real controller's
  refusal over real HTTP, on the one class in the suite with a servlet container. The standalone
  fixture proves the mechanism; this proves it on a body that came back over a socket, from one of
  the twenty-one. Its sibling `theBodyIsAProblem` now asserts the absence of `instance` in the same
  words it has asserted the absence of `path` since §146 — which is the whole point of the slice:
  one rule, one wording, every writer.

## Two things were already broken on arrival, and one of them had to be fixed to have a gate at all

**`ErrorBodyPolicyTest` was red on `master` before this slice touched it**, on two rules, on this
machine, deterministically — verified by stashing the whole slice and running the class at `b17b34f`.
`moduleRelative` builds its key with `Path.toString()`, which is **platform-shaped**: on Windows it
returns `reporting\ReportController.java` while `BODILESS_FORBIDDEN_SITES` and
`NON_VALIDATION_ERRORS_SITES` are written with `/`. So `bodilessForbiddenSitesArePinned` (§148) and
`nonValidationErrorsSitesArePinned` (§149) have **never once passed on a Windows checkout**, each
failing on a set whose members differ from the expected ones by one character apiece.

**It is §58's platform lesson running backwards, and it is worth as much.** §58 says the local gate
cannot see a Linux-only failure. This is a *Windows-only* failure the Linux nightly cannot see
either — so `master` is green on the only observer that runs the suite, while `./gradlew build`,
which `CLAUDE.md` calls the one gate a human runs before pushing, **has been red on arrival for
every slice since §149 on the machine this repo is developed on**. A gate that is always red is a
gate nobody reads, which is why §162 could report *"SUCCESSFUL, 2452 tests / 0 failures"* for a run
that cannot have been green here. Fixed in one character (`.replace('\\', '/')`) with the reasoning
in the javadoc, because there was otherwise no way to demonstrate this slice's own gate.

**Second, not fixed and not this slice's:** `frontend/src/features/cases/CaseEditorPage.test.tsx` is
**non-deterministically flaky** on this machine. Three runs of the identical file at the identical
commit produced 5, then 2, then 1 failures, each a different subset, every one a timeout at 12–39
seconds on a step-list or autocomplete interaction. It is not §163's: a `.d.ts` is erased before
Vitest runs, and stashing the regenerated schema changed nothing but which tests happened to time
out. Recorded here rather than absorbed. (`frontend/node_modules` and `packages/node_modules` were
both absent on arrival too — `@dnd-kit` had never been installed — so `npm install` is a
prerequisite for either gate and `package-lock.json`'s incidental pruning was reverted.)

## Gates

`cd backend && ./gradlew build` — **SUCCESSFUL, 2455 tests / 506 classes / 0 failures** (§162: 2452
/ 506; +3, no new class — two rules in `ErrorBodyPolicyTest`, one in `ErrorDispatchStatusTest`).
Both `schema.d.ts` copies regenerated and byte-identical to each other; their only diff against
§162 is the removal of the `instance` property. `frontend`: build clean, `npm run lint` **0
warnings**, `npm run test:run` **1214 across 93 files** with the flake above (7 → 1 failures across
runs, all in one file, none in a file this slice touches). `packages/`: built in order, lint clean,
**229 tests** — run because `client/src/schema.ts` is regenerated, and `schemaFreshness.test.ts` is
what proves the two copies still match.

**The three new rules were mutation-tested, both directions, before this was written** — §161's and
§162's precedent, and for their reason: each mutates a *check* rather than the product, so there is
no screen and no request, and a rule shipped without seeing it fail is the rule §162 exists because
of. Recorded as `§163 EC-1`…`EC-3` in `docs/verification/backend-mutation-checks.md`, all reverted.

## Queues

The `Content-Type` row §162 queued (`MT-1`) now has a companion: the same refusals lose a property,
and nothing should notice. Recorded as `§163 EC-1` in
`docs/verification/frontend-browser-checks.md`. **Not run**, per §65.

## The next slice

§162's named candidate is closed. What this one surfaced and did not take: **`Problem` is the one
contract schema written by two Java types, and nothing compares their field names.**
`ContractConventionTest.RESPONSE_RECORDS` does that job for four response schemas and `Problem` is
not one of them — §162 recorded the gap and declared `instance` instead of closing it, and §163 has
now removed the one member that differed, which makes the two types agree *today* with no check
saying so. The subset rule above catches a property the server sends and the contract does not
declare; it says nothing about a property the contract declares and one of the two types cannot
produce, which is the §148 direction.

Otherwise: the browser queue, `TeamRef`, the three remaining health derivations (two need
`documentation`, which `repository` may not depend on; `orphaned` needs a product decision first),
or the read-only-archive decision §159 deliberately did not take.
