# §146 — one error envelope, and the fifth foundation module it forced

**2026-08-09 · two phases · `backend/` + a document-level contract change (both `schema.d.ts`
copies regenerated). No migration, no frontend or packages source change.**

§145's live pass recorded a note, not a defect: a body Spring cannot read answered `400` with
`{timestamp, status, error, path}` — the container's shape, which this contract declares nowhere —
where every controller answers `Problem`. The status was right; §143 had fixed that. The shape was
the residue.

Closing it needed a caller with **no module to live in**, and that is what made this a slice about
the architecture rather than about an error body.

---

## Phase 1 — `com.tcms.api`, and why the name is not `shared`

The envelope existed in three spellings:

| Where | What |
|---|---|
| `repository.ApiProblem` | the real record — **package-private**, so six modules referred to it in *prose* and none could call it |
| `auth.InvitationController.ProblemBody` | the same record minus `errors` |
| `auth.RateLimitFilter` | a **JSON string literal**, four constants and a number |

The third is the one that shows the cost: a field renamed in the contract would have been caught
there by no compiler, no test that reads the record, and no reader not looking straight at it.

**Every one of those files already knew.** `ApiProblem`'s header called the duplication *"deliberate,
visible, and the extraction is the follow-up"*. `work.UserRefView` kept a **running tally** with the
rule attached: *"the next one should probably come with the extraction rather than with another
comment like this."* What none of them could do was decide, because the cost is a foundation entry
and §76's rule stands: **a new foundation module is a decision, not an import.** This is the
decision, and §146's error handler is the "next one" — a third caller, in no module at all.

**It is a fifth foundation module and it clears §125's bar in the strongest available form:**

- **it declares nothing** — `allowedDependencies = {}`, the spelling only `tenancy` uses.
  `storage` had to name `tenancy` because its table is company-owned; there is no table here, so
  this module cannot reach the isolation boundary, let alone become a second place authorization is
  decided;
- **it holds no domain concept and no behaviour beyond rendering** — four fields, a map, and a
  writer that turns one into bytes;
- **it answers a question no module can**: not *"one the database cannot"* (`storage`'s test) but
  the one underneath it — *whose is a shape the contract defines?* For eleven slices the answer was
  "whichever module noticed first, plus a comment explaining it could not import the last copy".

**Not called `shared`, and that is the same argument §125 made when it refused `shared` as the home
for the blob ports**: a module every other module may depend on must not be a grab bag. A package
named for its *accessibility* invites the next homeless class. The name states a charter narrow
enough to refuse things — **a type belongs here only if the contract defines it and more than one
module serves it** — and `ArchitectureRulesTest.apiDependsOnNothing` pins the load-bearing half from
the code, because Modulith enforces a declaration and has nothing to say about that declaration
being widened. One word — `"identity"` — would be legal under the foundation rule and pass every
other test in the class, and the commit after it writes `forbidden(user, permission)` and starts
publishing the permission catalogue to the caller not entitled to it, out of a record whose javadoc
says never to name the key.

## Phase 2 — the container's own failures

`ProblemErrorAttributes` replaces Boot's `DefaultErrorAttributes`, so `/error` renders `Problem`.
Five failures never reach a handler — unreadable body, malformed JSON, wrong `Content-Type`, wrong
method, unmapped path — and all five now answer the shape a controller would have.

**`detail` is a fixed sentence per status, never the exception's message.** The tempting
implementation forwards `getError().getMessage()`, which for the very request this slice was found
on names a Jackson type, a fully-qualified enum and a JSON pointer. The caller is a CI job or a
model: none of that is actionable and some of it is internal. `theDetailIsCuratedRatherThanForwarded`
asserts the negative directly.

**An empty 404 stays empty, and that is asserted rather than reasoned.** §6t's rule — ungranted,
another company's, and non-existent must be indistinguishable, *with no body* — survives because
`ResponseEntity.notFound().build()` writes its status directly and never calls `sendError`, so it
does not pass through this bean at all. The reasoning is the kind that is correct right up until
Boot changes how a bodiless response is committed, so `aControllersOwn404IsStillBodiless` pins it.

**All four new assertions live in `ErrorDispatchStatusTest`**, which is still the only class in the
suite on a real servlet container — for §143's reason, unchanged: MockMvc performs no ERROR
dispatch, so no other test in 2300 can observe any of this.

**Verified by mutation**: disabling the bean's `@Component` fails exactly the three body tests and
leaves the five status tests and the §6t guard green.

## The contract change is document-level

`components.responses.PreHandlerRefusal`, **referenced by no operation** — `TooManyRequests`' reason
one step later. These are properties of the *request*, not of an operation, and three of them happen
precisely when the API could not decide which operation was meant; a 405 enumerated on every path
would document a method that does not exist there. It records what was true before as well as what
is true now, because "the body used to be the container's" is the part a client author needs when
reading old integration code.

## Two findings on the way through

**The tally was wrong, in the direction that mattered.** `work.UserRefView` recorded
*"`ApiProblem` ×2, `UserRefView` ×3"* as the evidence for extracting. The real count when the module
was created was **six** — `identity`, `project`, `repository`, `documentation`, `execution`, `work`.
A hand-maintained count of copies drifts exactly the way the copies do, which is the thing it was
keeping records about. Same family as the `MAX_IDEMPOTENCY_KEY` finding in §136, and the same
repair: the count should be computed, not remembered.

**Five files claimed the home did not exist**, and this commit made all five false at once — the
§145 pattern, arriving one slice later and entirely predictably. Each now carries the supersession
and the accurate count.

## What was deliberately not done

**`UserRefView` was not migrated.** Six copies across six modules is its own slice, and §125's rule
is the reason to keep it out of this one: in a single diff an architectural move and anything else
are indistinguishable, and here the thing under review *was* the new module's charter. It is the
obvious next tenant — `UserRef` is a contract shape served by six modules, which is the charter
sentence almost verbatim — and it is now a migration rather than a decision.

**`NotFound` in the contract still over-promises**, and this slice did not touch it: it declares a
`Problem` body, while §6t's 404s are deliberately bodiless. That is the §145 class of defect
(contract says one thing, server does another) on a rule the server is right about, so the repair is
a contract edit with a decision attached — which 4xx bodies are promises and which are refusals to
speak — and not a change of behaviour. Recorded as the next contract-side candidate.

## Gates

`./gradlew build` **SUCCESSFUL, 2319 tests, 0 failures** (2314 at §145: +4 in
`ErrorDispatchStatusTest`, +1 in `ArchitectureRulesTest`).
`packages`: build clean, `oxlint` 0, **229 tests**. `frontend`: build clean, `oxlint` 0,
**1203 tests**. Both `schema.d.ts` copies regenerated.

## What is verified and what is not

The container behaviour is verified **on a real Tomcat** — that is the one thing in this slice that
could not be taken on trust, and it is the observer §143 had to introduce. Verified live against the
running instance as well: the `400`, `404` and `405` bodies were re-driven with `curl` after
rebuilding the image.

Not verified: nothing here reaches a screen, so the browser queue is unchanged. `A4-11`/`A4-12`,
`CR-1`…`CR-3` and `SD-2`…`SD-4` remain unrun, and `PR-1`…`PR-3` are added below.
