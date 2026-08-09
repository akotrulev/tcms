# §148 — the error bodies the contract promised and nothing ever wrote

**2026-08-09 · one phase · a contract change, no migration. `backend/` (tests only) +
`docs/specs/openapi.yaml` + `frontend/`: no backend production code changed at all.**

§146 recorded this on its way out: *"`NotFound` in the contract still over-promises … the repair is
a contract edit with a decision attached — which 4xx bodies are promises and which are refusals to
speak."* This is that edit, and the decision turned out to be worth more than the edit: **two of the
five reusable error responses are refusals to speak, and both had been declaring a body since M0.**

It is the §145 class of defect — the contract says one thing and the server does another — on a rule
the server is right about, for the fourth consecutive slice. What is new is where the divergence had
already been *noticed*: in three consumers, each of which worked around it silently.

---

## What the server actually writes

| Status | Written by | Body |
|---|---|---|
| `401` | the security chain — `HttpStatusEntryPoint`, or `PermissionVersionFilter.refuse` | **none**, on every endpoint |
| `404` | 94 sites, every one of them `ResponseEntity.notFound().build()` | **none** |
| `403` | 59 sites | a `Problem`, on 56 of them |
| `400` · `409` · `429` | throughout | a `Problem` |

And what `openapi.yaml` declared: a `Problem` on all 88 `401`s, on 107 `$ref: NotFound` plus 4
inline `404`s — and, on exactly **two** inline 404s, no body at all. **The document disagreed with
itself about the same status, 111 to 2, and nothing had ever compared them.** Same family as §136's
`MAX_IDEMPOTENCY_KEY` declared in seven services and §146's hand-kept `UserRefView` tally: a
catalogue-wide fact maintained by whoever remembered.

It disagreed with itself in prose too. The invitation family carries a path-level comment reading
*"EVERY failure is 404 with no body"* — correct, written by someone who had thought about it, two
thousand lines above a shared response asserting the opposite.

## The decision: which refusals may speak

A refusal carries a body **when the caller can act on what it says**, and not otherwise.

- **`404` — no body, ever.** The only thing a document here could usefully carry is *which* of the
  path's ids failed to resolve, and concealing that is the entire purpose of the status (§6t). A
  `Problem` on a 404 is either an oracle or a restatement of the status line.
- **`401` — no body, ever.** Four conditions reach it (no credential, unreadable, expired, a session
  the account has outlived) and they are deliberately not told apart. The remedy is the same for all
  four. One header is worth reading and stays: `PermissionVersionFilter` sends
  `WWW-Authenticate: Bearer error="invalid_token"`.
- **`403` — a body on 56 of 59 sites, and a client must tolerate its absence.** This refusal usually
  *has* something actionable to say (which permission, at what scope) and both non-SPA clients print
  its `detail`. §141's finding is the reason to prefer speaking: a refusal that does not say what to
  do next is the one a model retries.
- **`400`, `409`, `429` — always a body.** Unchanged; they exist to be read.

**The three bodiless 403s were not repaired, because each had already argued its case at the site**:
a report download is typed to a binary `Resource`, so a JSON document under that status is a shape
the caller did not ask for; and the two artifact refusals (register, unregister) have no field to
land a sentence on and nothing the caller can change about the request. What they lacked was any
statement of that in the contract — `Forbidden` now says the body is present except at three named
sites, and `ErrorBodyPolicyTest` pins the set so a fourth cannot join it quietly.

## Three consumers had already found this, and all three compensated

This is the part worth carrying forward. Nothing failed; three separate authors hit the same wall,
worked around it locally, and wrote the truth down where nobody was comparing it to the contract.

1. **`@tcms/client`.** `problemFrom` floors `detail` at `statusText` under a comment: *"it is empty
   over HTTP/2 and on any bodiless response, the API's own 401 among them."* Its own test says
   `// The API's own 401 is bodiless`. The client author knew; the contract did not.
2. **`frontend/src/api/client.ts`.** The identical floor, the identical comment, arrived at
   independently.
3. **`TeamBoardPage`.** Its header states that a 404 *"would render 'Not found — Not Found': the
   HTTP status text, which says nothing"*, and supplies a sentence of its own for that one screen.

Three workarounds are not three defects. They are one defect observed three times by people who had
no way to report it upward, because the artifact they would have reported it *against* is the one
that was wrong.

## The defect that reached a screen

`describeError`'s 404 branch returned `error.detail` under this comment:

> A 404 detail names the missing thing, which is the useful part; it is server copy, not an
> exception message, so it is safe to show.

**There is no detail.** So the body of the panel was whatever `client.ts` had floored it to: the
status text — the words "Not Found" under the heading "Not found" — or, when the origin sends no
status text, the sentence "The request failed (HTTP 404)." A promise in the contract became a
tautology on a user's screen, on every 404 in the SPA except the one board that had worked around it.

**Its unit test asserted the fiction and passed.** The fixture fed a detail reading
`'No test case TC-9001 in this project.'` and then asserted that exact string came back — a test
that supplies the thing it is checking for. That is what "the contract is the artifact everyone
trusts" costs: the comment, the test and the fixture were all written from `openapi.yaml`, and all
three were consistent with each other and with nothing else.

The copy now names the two states the server refuses to distinguish, which is honest and is also the
more useful sentence, because the reader is the one person who can tell them apart — they know
whether they expected access: *"It may have been deleted, or it may be in a project you cannot
open."*

## The check

**`ErrorBodyPolicyTest`** (`com.tcms.contract`, no Spring context, three tests):

| Test | Reads | Asserts |
|---|---|---|
| `bodilessStatusesDeclareNoContent` | the contract | no declared `401` or `404` carries `content`, resolving `$ref`s. Floor: >100 responses compared |
| `noHandlerWritesABodyOnA404` | every `.java` under `src/main` | every `notFound()` is followed by `.build()`, **and no file but `ProblemErrorAttributes` names `HttpStatus.NOT_FOUND` at all**. Floor: >80 sites |
| `bodilessForbiddenSitesArePinned` | the same | the set of bodiless 403 sites equals the documented three. Floor: >40 sites |

Plus one live assertion in the sweep that already existed:
**`EndpointAccessTest.everyOtherEndpointRequiresAuthentication` now also asserts the 401 body is
empty** — on every mapped endpoint, against a real dispatch. The 401 half needs no source scan
because that sweep is strictly better evidence, and it was already being performed.

### Why a source scan, in a repo that argues against them

`ContractParameterBindingTest` carries a section headed *"Why the live handler mapping rather than a
source scan"*, and it is right about bindings. **Body-presence is not visible from the mapping** —
not from the return type, not from any reflective view; whether a `switch` arm calls `.build()` or
`.body(…)` is decided in text and observable only by driving all 127 operations through every
refusal each can produce. §108 is the precedent and its general form is the rule: *when the defect
lives in the text, the check has to read the text.*

The scan knows one spelling of a 404, so **the other spelling is banned outright** rather than left
as the hole: `status(HttpStatus.NOT_FOUND).body(…)` writes the same status, carries a document, and
contains no `notFound()` to be found. `ProblemErrorAttributes` is the single exemption — it serves
`/error`, where the container has resolved a path with no operation and the `Problem` *is* the point
(§146, `PreHandlerRefusal`).

**Both new checks were run against the pre-fix tree and both go red there** — the contract check
reports the 111 declarations, and the 403 pin was verified by mutating `ArtifactController`'s two
bodiless arms to carry bodies. A check that has never failed is a check nobody has tested.

### One property of these checks worth knowing

`openapi.yaml` lives outside Gradle's input set, so **`:test` is UP-TO-DATE when only the contract
changes**. Editing the spec and re-running `./gradlew test` can silently execute nothing. Use
`--rerun-tasks`, or change a Java file too. This bit during the negative check and would bite anyone
verifying a contract-only edit.

## What was deliberately not done

- **The three bodiless 403s were left bodiless.** Each has a written argument at the site; changing
  behaviour here would be a different slice with a different subject, and this one is about the
  contract telling the truth about the behaviour that exists.
- **`Conflict`, `BadRequest` and `TooManyRequests` were not audited site-by-site.** Their bodies are
  the reason they exist and no site was found writing them empty, but the checks above do not pin
  that direction — a `.build()` on a 409 would pass today. Named here rather than assumed.
- **No `NotFound` schema was deleted.** `Problem` is still the 404's shape on the one path that has
  one, the container's.

## Gates

`./gradlew build` **SUCCESSFUL in 16m40s — 2323 tests across 490 result classes, 0 failures**
(§147: 2320. +3, all in `ErrorBodyPolicyTest`; `EndpointAccessTest` gained an assertion, not a test).
`packages`: build clean, `oxlint` 0, **229 tests** — unchanged, and the regenerated `schema.ts` is
its only diff.
`frontend`: build clean, `oxlint` 0, **1204 tests, and the cliff recurred** — two runs, 6 failures
in 2 files then 2 in 1, every one a 30 s timeout and never an assertion, in `CaseEditorPage` and
`PlanCases`. **Both pass 44/44 in 76 s run alone**, immediately after. That is §127's signature
exactly and the fifth run to reach it; the recorded remedy is still `paste`.
Both `schema.d.ts` copies regenerated.

## What is verified and what is not

Verified by gate: the contract now agrees with the server on both statuses, computed in both
directions, and the 401's empty body is asserted live on every mapped endpoint.

**Not verified:** the sentence a person reads. The 404 copy is a screen change and goes to the
browser queue as `EB-1`/`EB-2` in lane `L2`. Still unrun before it: `A4-11`/`A4-12`, `CR-1`…`CR-3`,
`SD-2`…`SD-4`, `PR-2`/`PR-3` and everything below §65.
