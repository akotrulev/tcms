# §162 — the media type nobody declared, and the two exemptions that excused nothing

**2026-08-11 · one phase · `backend/` + contract + both `schema.d.ts` copies: no migration, no
schema change to any *body*, no frontend or `packages/` source. One wire property moved, on every
refusal this API can send.**

§161 left a candidate on its way out and this is it: **the `*_BY_DECISION`-shaped populations that
are not in `ArchitectureRulesTest`** — `ContractConventionTest.NO_CLIENT_ERROR_POSSIBLE` and
`NO_ALWAYS_PRESENT_PROPERTY`, `ContractParameterBindingTest.DELIBERATELY_UNBOUND`,
`ErrorBodyPolicyTest`'s exemptions, and `ContractEnumTest`'s own three maps. Each is an argument a
green build re-affirms every run.

The audit found **two dead exemptions**. Then it found something else, which is most of this
section.

## The audit, first, because it is what was asked for

| population | verdict |
|---|---|
| `ContractEnumTest`'s `OWNERS` / `HOMONYMS` / `DIVERGENCE_BY_DECISION` | **hold** — all three are pinned in both directions already (§161), which is exactly the property the rest lacked |
| `ErrorBodyPolicyTest.NON_VALIDATION_ERRORS_SITES` · `BODILESS_FORBIDDEN_SITES` | **hold** — both assert `isEqualTo`, so a site that stops qualifying reddens |
| `ContractConventionTest.NO_CLIENT_ERROR_POSSIBLE` | **holds** — `POST /auth/logout` is still the only operation in the document declaring no 4xx |
| `ContractParameterBindingTest.DELIBERATELY_UNBOUND` | **empty**, and now pinned before its first entry rather than after its first stale one |
| `ContractConventionTest.NO_ALWAYS_PRESENT_PROPERTY` → `CaseConditionInput` | **DEAD** |
| `ErrorBodyPolicyTest.CONTAINER_ERROR_PATH` | **DEAD, and never described the right mechanism** |

**`CaseConditionInput`** was exempt from "every object schema declares `required`" because a case
condition was *"EITHER a library reference OR an inline body, never both and never neither"*, so no
property was common to both shapes. §95 ended that: only the reference form is accepted,
`inlineBody` exists solely so it can be **refused** with a field error, and the schema has carried
`required: [conditionId]` ever since. The row excused nothing for sixty-seven sections.

**`CONTAINER_ERROR_PATH`** is the more interesting one. It exempted `api/ProblemErrorAttributes.java`
from the `HttpStatus.NOT_FOUND` ban, on the grounds that the container's 404 legitimately carries a
`Problem`. That file **has never contained the string `HttpStatus.NOT_FOUND`** — it keys its curated
sentences by the int `404` and resolves with `HttpStatus.resolve(status)`. So the file does body a
404, legitimately, by a spelling the scan cannot see, while the exemption said it was permitted to
do so by one it does not use. *An ignore list naming the wrong mechanism reads as coverage and is
the absence of it.* Both are deleted; the ban now applies to every file.

The check is `ContractConventionTest.everyExemptionStillExemptsSomething`, plus its sibling for
`DELIBERATELY_UNBOUND`. **A one-directional ignore list cannot report a member that stopped needing
to be on it**: skipping something that would have passed anyway costs nothing and reports nothing.
That is the whole mechanism behind §160's twenty-nine-slice-old blocker and §161's claim that was
never true, and it is why `ArchitectureRulesTest`'s and `ContractEnumTest`'s maps — pinned both
ways — were the populations that held.

Also corrected: `ContractParameterBindingTest`'s skip still read *"not implemented — still MSW, and
not this check's business"*. The MSW premise was deleted with the mock layer in §107 and struck in
that class's own header in §145; **the inline copy survived both** and went on giving the retired
reason four lines from the strike-through.

## Then: this API has two error envelopes, and the contract described neither

The audit's last stop was `ErrorBodyPolicyTest`'s status sets, and they do not cover 403 (declared
80 times) or 413 (4). Asking why led to the media type, which **no file in `backend/src` had ever
mentioned** — main or test, the string `problem+json` occurred zero times.

Measured, not reasoned:

| envelope | files | goes out as |
|---|---|---|
| Spring's `ProblemDetail` | **21** | `application/problem+json` |
| `api.Problem` (the record) | **8** | `application/json` |
| `api.ProblemWriter` (the filter 429, and it sets the header by hand) | 1 | `application/json` |
| Boot's `/error` dispatch (§146 shaped the body; the mapping is hard-coded `produces`) | — | `application/json` |

`ProblemDetail`'s type is not negotiable, either: it wins against an explicit
`Accept: application/json`, which is what `@tcms/client` sends on every request.

**So the contract was wrong about 132 of its 176 declared error bodies, in both directions at
once.** 123 said `application/json` for a refusal that arrives as `problem+json`. One said
`problem+json` for a refusal that arrives as `application/json` — the case-attachment 413, the one
upload handler of four written with the record instead of the `ProblemDetail` its three siblings
use. The eight accurate declarations in the document were the eight somebody had actually looked at.

### Two headers each said theirs was the only envelope

`api.Problem`'s class header: *"the error envelope **every** refusal in this API answers with,
whether a controller wrote it or the container did."* The `api` module's charter — the document that
justified making it a **fifth foundation module** — repeats it. Meanwhile `RunController`,
`DocPageController` and nineteen others each carry a paragraph arguing for `ProblemDetail` because
it *"serializes to exactly the `Problem` the contract declares"*.

Both were written in good faith and neither was checked. **§146 removed the copies that could not
*reach* a shared record and never touched the ones that had chosen a different one** — which is why
an extraction whose whole subject was "one error envelope" left two.

### Why nothing found it

It cost nothing at runtime, and that is the reason it lasted sixteen sections. Both TypeScript
consumers call `.json()` without consulting `Content-Type`; `@tcms/client`'s `problemFrom` and the
SPA's client both do. No endpoint test asserts a content type on a refusal. What was false was the
artifact everyone *else* generates from: `schema.d.ts` keys the payload under the media type, so a
consumer that parses by the one it was given — the only reason to publish a contract — finds
`never` where the document is.

This is §148's finding one property over. There the contract promised a body nothing wrote; here it
promised the right body under a type nothing sends.

## The repair: one type, decided in one place per path

`application/problem+json` is the answer — RFC 7807's, the majority of the server's already, and
what the contract's *accurate* entries said.

- **`api.ProblemMediaType`** (new, a `ResponseBodyAdvice`) sets it for the record. The record
  reaches a response at ~50 `.body(Problem.…)` sites across seven controllers; setting
  `.contentType(…)` at each is fifty chances to forget one and makes the refusal composed in two
  places, which is the argument `ProblemWriter` already makes for itself. Keying on the **type**
  means a ninth file gets it without knowing the class exists. It deliberately leaves `ProblemDetail`
  alone: Spring decides that one, and a second decider is how two spellings diverge again.
- The same advice covers **Boot's `/error` dispatch**, matched on the declaring class. Defining an
  `ErrorController` bean would switch Boot's off and put §143's and §146's ERROR-dispatch work
  behind a reimplementation; the body was already right and only the type it was announced under
  was not.
- **`ProblemWriter`** sets it directly — a filter's response reaches no advice.
- **`openapi.yaml`**: all 176, via the five shared component responses and nine inline ones.

### `instance` — declared, and the decision deliberately not taken

`ProblemDetail` also writes an **`instance`** property that the contract's `Problem` schema did not
declare: the path of the refused request, on 123 of 176 refusals, since the first `ProblemDetail`
controller. It is now declared optional — genuinely absent on the record's eight, not null.

**It is `path` under another name, and §146 deliberately removed `path` from the container's error
body** — `ErrorDispatchStatusTest` asserts its absence in as many words while `instance` carries the
identical string one layer over. The two positions coexisted because nobody compared them.
Reconciling them is a decision, not a cleanup: either suppress `instance` so the envelope matches
the record everywhere, or amend §146's reasoning. **Declared first, because whichever way it goes
the contract has to be true today** — which is §161's lesson about naming the direction, applied to
the thing §161's own audit was too narrow to see.

## The checks

- **`everyDeclaredErrorBodyIsProblemJson`** — contract side. Keyed on *"declares content at all"*
  rather than on the two hand-written status sets, which between them name six statuses and leave
  403 and 413 with no opinion at all. Nothing is outside it.
- **`bothEnvelopesWriteProblemJson`** — server side, a standalone `MockMvc` (no context, no
  database), both envelopes, and `Accept: application/json` exercised because a 406 is the obvious
  worry and Spring answers the problem type anyway.
- **`ErrorDispatchStatusTest.theBodyIsAProblem`** — the `/error` dispatch, over real HTTP, because
  the header is written on the ERROR dispatch and MockMvc does not run it.
- **`RateLimitTest.aTokenIsRefusedAtItsCeiling`** — the filter's 429, the one path where neither
  Spring nor the advice decides.

## Gates

`cd backend && ./gradlew build` — **SUCCESSFUL, 2452 tests / 506 classes / 0 failures** (+4, no new
class: the four rules land in three existing files). Frontend `npm run build` clean, `npm run lint`
0 warnings, `npm run test:run` **1214 across 93 files** (unchanged — no frontend source moved).
`packages/` built in order, lint clean, **229 tests**. Both `schema.d.ts` copies regenerated and
byte-identical to each other; their diff is the media-type keys and `instance`.

**All four writers were mutation-tested, both directions:** reverting one component response to
`application/json` reddens the contract rule; disabling the advice reddens `bothEnvelopesWriteProblemJson`
*and* the live `/error` assertion; reverting `ProblemWriter` reddens the 429. Re-adding
`CaseConditionInput` to `NO_ALWAYS_PRESENT_PROPERTY` reddens `everyExemptionStillExemptsSomething`.
All reverted.

## Queues

The `Content-Type` on every refusal changes, and no client reads it — which is a claim worth driving
rather than asserting. Rows recorded in `docs/verification/backend-mutation-checks.md`
(`§162 MT-1`…`§162 MT-4`) and `docs/verification/frontend-browser-checks.md` (`MT-1`). **None run.**

## The next slice

The exemption audit is finished and its rule is in place, so the shape §161 named is closed for the
contract tests. What it surfaced and did **not** take: **the `instance`-versus-`path` decision
above** — a live contradiction between §146's rule and twenty-one controllers' behaviour, now
declared honestly but not resolved.

Otherwise: the browser queue, `TeamRef`, the three remaining health derivations (two need
`documentation`, which `repository` may not depend on; `orphaned` needs a product decision first),
or the read-only-archive decision §159 deliberately did not take.
