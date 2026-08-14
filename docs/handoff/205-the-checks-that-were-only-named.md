# §205 — The checks that were only named, and the one that half existed

**Three phases, `packages/` plus three comment lines in `frontend/`: two new conformance files, a
new `Problem` check, a new repository-wide rule, one arm of one MCP tool, and eleven citations. No
migration, nothing in `backend/src`, no contract change and neither `schema` copy regenerated.**

Three phases rather than one, because each closes a different half of the same finding and each
lands green on its own gate: the check that half existed, the three names with nothing behind them,
and the rule that stops the sixth.

§204's second opened item, taken verbatim:

> **`WriteKind` says a create carries an `Idempotency-Key`, and sixteen operations of the seventeen
> say what a replay answers — but the registry cannot see which.** `writeConformance.test.ts` asserts
> each arm *sends* the header; nothing asserts the arm can *read* the answer.

**The first clause of the second sentence is false, and finding out how false took one `ls`.**
There is no `writeConformance.test.ts`. There never was. Four comments in `@tcms/cli` name it —
`commands.ts`, `context.ts`, `writes.ts`, `test/harness.ts` — and four more in `@tcms/mcp` name a
`conformance.test.ts`, which is the same check under a second invented name. §156's shape, one list
forward, for the third slice running.

## Phase 1 — what the two registries each said they checked

The CLI's check was **real and misfiled**: a `describe('header conformance')` sitting among forty
behaviour tests in `writes.test.ts`, walking `COMMANDS`, driving each declared write through a
recording `fetch` and reading the headers off what arrived. Everything the four comments claim, in a
file with another name.

The MCP's was **not real**. What sat there under that name reads the registry's *declarations*:

| what it asserts | what it reads |
|---|---|
| every arm's `kind` is one the table knows | `WRITE_TOOLS` |
| every arm's `permission` matches `^[a-z]+:[a-z]+$` | `WRITE_TOOLS` |
| every arm's permission is inside its tool's gate | `WRITE_TOOLS` |
| `armFor` routes an action to its own arm | `WRITE_TOOLS` |

Not one request. Its own file header said, in as many words:

> It is the same check the CLI runs over its command registry, **deliberately**: the two surfaces
> share a command core precisely so that a rule proved on one holds on the other, and **two
> conformance tests that disagreed about what a create is would be the thing that lets them drift.**

They disagreed, in exactly that way, from §140 to §205. Eight of the twenty-two arms — `run create`,
`run clone`, `work-item create`, `work-item move`, `report generate`, `review comment` and the two
`review verdict` arms — were driven by **nothing at all**; the other fourteen were covered by
hand-written per-tool assertions that happened to include a header on two of them.

**A declaration check cannot fail on the thing the declaration is for.** `kind: 'create'` and a
`run` that never calls `createIntent` are perfectly consistent with each other — the registry says
what the arm should do and the arm is the only thing that does it — so the two agree right up until
the request goes out without the header. That is the whole population the file exists over.

So both packages now have `writeConformance.test.ts`, the name everything already gave it, driving
every arm and failing on one it does not recognise. The MCP's covers 22 arms, the CLI's 16 commands.

### The second question, and the one thing it found

The rule was *did the arm send what its `kind` implies*. Writing it fresh made a second question
obvious: **does the operation it wrote to declare that header at all.**

Eleven operations in `openapi.yaml` declare `Idempotency-Key`. Seven of them are reached from the
MCP registry. The eighth create arm reaches one that does not:

> `tcms_report.generate` → `POST /projects/{projectId}/reports/generate`, which declares no such
> parameter, and whose `ReportController.generate` takes no `@RequestHeader` either. **It sent one
> anyway**, under an arm comment reading *"a generation allocates a job row, so it is a create and
> carries a key: an agent that retries a lost 202 must not queue the work twice."*

It queues it twice. The sentence described a protection nothing provided, and — this is the part
worth carrying — **sending the header is what made the shortfall invisible**. Every angle available
from inside the package agreed: the arm is declared a create, a create carries a key, the arm
carries one. The only reader that could disagree is the contract, and nothing had asked it. It is
also, precisely, the mistake the parameter's own description in `openapi.yaml` names: *"a key
generated per attempt is the mistake that makes the header look right and do nothing."*

Repaired by **not sending it**, and the arm stays `kind: 'create'`. Bending the declaration to fit
the check is the defect one layer up. `CREATE_WITHOUT_A_KEY` records the gap with what it costs a
caller; closing it is a column on `report_job`, a replay lookup and a contract change — a slice, not
a line.

Everything else was clean: **21 of 22 MCP arms and all 16 CLI commands already send exactly what
their kind implies**, so that half is a guard rather than a repair.

`declaredHeadersOf` joins `successStatusesOf` **outside** `contractBodies.ts`'s shared region, on
§204's rule: a question only one stack can ask does not go in it. The SPA's double replaces a method
on the `api` object and a header never reaches it — `frontend/src/api/client.ts` composes them
*inside* the call the double replaced.

Four mutations, each reverted:

| mutation | result |
|---|---|
| `suite create` drops `createIntent` | red, naming the arm, the header and the endpoint |
| `plan edit` drops `editIntent` | red, `if-match` |
| a new `report.schedule` arm with no `INVOCATIONS` entry | throws, naming the arm |
| the report key put back | red, `sent [idempotency-key]` where none is declared |

## Phase 2 — the other three names

`problemShape.test.ts` is cited by `client/src/errors.ts`, whose header gives the argument for
`TcmsApiError` being a near-copy of the SPA's `ApiError` rather than an import — a browser client
and a Node one share the wire format and nothing else — and then says:

> The one thing that must not diverge is the *reading* of that format, so `problemShape.test.ts`
> derives the field list from the contract rather than from either copy.

**They had already diverged.** `Problem` declares five properties and requires four.
`frontend/src/api/client.ts` reads `detail`, `title`, `errors` and the status, and **drops `type`**,
which is one of the required ones. `TcmsApiError` carries it. Neither stack *reads* the value
anywhere: here it is on the published surface of `@tcms/client`, which is a caller's to use; over
there it reaches nothing.

The new file pins **this** side both ways — `READS` names each contract property and the member that
carries it, and a property the document adds or drops fails the build — and states the divergence
rather than enforcing it. Enforcing it is §203's copied-region-plus-pairing-test, and a
five-property object does not earn that; adding a readerless field to the SPA to close it is §154's
and §165's shape. `FROM_THE_TRANSPORT` records the one deliberate exception: `status` comes from the
response and not from the body, driven with a document claiming `400` inside a `503`, which is what
a proxy that rewrote one and forwarded the other produces.

`idempotency.test.ts` was the cheapest find of the slice. **The check is real** — `core/core.test.ts`'s
*"matches the maxLength the contract declares"*, reading `MAX_IDEMPOTENCY_KEY` out of `openapi.yaml`
exactly as the comment promises. Only the filename was invented. The pointer is a test name now,
because `core/` is tested as one file.

`schemas.ts` never existed, and the sentence pointing at it — *"hand-written rather than derived —
see `schemas.ts`"* — was standing in for a decision nobody had written down. Written down: **a tool
argument is not a contract schema.** It takes display keys where the API takes UUIDs, flattens
`CaseConditionInput` to bare ids, and is described for a model rather than for a person reading API
docs, so deriving these would produce a surface no caller on this transport can use. What must agree
with the contract is what goes *out*, and §203's exchange check is what asks that.

## Phase 3 — the rule

The deliverable, and it is the rule rather than the five repairs under it (§152). Phases 1 and 2
resolved names one at a time; this is what stops the sixth.

`citedFiles.test.ts` scans every hand-written `.ts` under `packages/` and every `.ts`/`.tsx` under
`frontend/src` — **350 files, 140 distinct citations** — for a backtick-quoted path with a known
extension, and asserts the file exists.

**Backticks are the whole heuristic and they earn it.** This repository writes every path in a
comment as code, so quoting one *is* the act of claiming it is real; an unquoted filename in a
sentence is prose about a hypothetical.

**The escape hatch is the repository's own notation.** A superseded claim here is struck through, and
a struck-through filename is therefore already a statement that it does not exist — so those spans
are excused with no second mechanism. `CITED_BUT_ABSENT_BY_DECISION` carries only what strikethrough
cannot say: three present-tense *"this project has no such file"* sentences (`error.tsx`,
`global-error.tsx`, `middleware.ts` — Next.js tiers a Vite SPA does not have), one citation into a
dependency's own source (TanStack Query's `queryObserver.ts`), and one file whose deletion is the
subject (the mock layer's `injection.ts`). Pinned both ways per §162.

**One check, not a pair.** `contractBodies.ts` had to be copied into both stacks because a Vite
`?raw` import and two npm roots make an import impossible *in production code*; this is a test
reading files off disk, and both CI jobs check the repository out whole. A second copy of a
twenty-line scanner is the §196 shape the pairing test exists to punish. What it costs is stated:
**a broken citation in `frontend/` reddens the `packages` job.**

The strip is where this could go silently dark, and §156 already paid for exactly that — a fixture's
unterminated `UPDATE` swallowed a file's literals and hid two members of the population the check
was written to find. **The guard is on the symptom, not on marker parity**, and the reason is worth
keeping: a parity rule cannot be satisfied by a file that writes *about* the notation an odd number
of times — this one — so the check would have had to exempt itself from its own corpus, which is
§154's defect verbatim. What is asserted instead is that excused spans cover under 40% of a file.

### What the rule found that was nobody's from this slice

| citation | citer | truth |
|---|---|---|
| `contractResponses.ts` | `frontend/src/test/contractDoubles.ts` | renamed to `contractBodies.ts` in §199; that file's own header has the rename struck through and this citation was missed |
| `renderFailures.test.ts` (×2) | `frontend/src/test/renderFailures.ts` | the file is `.tsx` |

And one it deliberately did **not** find: `boardColumns.test.ts`, cited by `openapi.yaml`'s own prose
for `WorkItemStatus` — *"`boardColumns.test.ts` now derives the board's columns from this enum so the
next member cannot arrive without one"* — which does not exist either. It is out of this corpus
because the only files carrying that prose in a scanned tree are the two **generated** `schema`
copies, and policing the contract's prose is a different rule with a different corpus. Recorded
below rather than quietly in scope.

Mutations, each reverted: a bogus citation added to a frontend query file (reported with its citer),
an unterminated span in `mcp/src/writes.ts` (reported as *88% of this file*), and a by-decision entry
naming a file that exists (*"take the entry out"*).

## Gates

All on `shanks`.

| phase | gate |
|---|---|
| 1 | packages build ✅ in order · lint ✅ 0 ×3 · **294** ✅ (78 cli / 111 client / 105 mcp, +25) |
| 2 | packages build ✅ · lint ✅ 0 ×3 · **299** ✅ (78 / 116 / 105, +5) |
| 3 | packages build ✅ · lint ✅ 0 ×3 · **302** ✅ (78 / 119 / 105, +3) · frontend build ✅ · lint ✅ 0 · **1339 across 100** ✅ unchanged |

`backend/` deliberately not run: nothing under `backend/src` moved.

## Verification

**One row, and it goes where §203's and §204's went** — `packages/` still has no persistent queue, so
it sits here beside `§203 PL-1`, `§204 PL-2` and `§204 PL-3`, all still ⬜ NOT RUN.

> **`§205 PL-4`** — against a real instance, call `tcms_report` with `action: 'generate'` twice, and
> confirm two things: that the generation still succeeds with no `Idempotency-Key` (the regression
> this slice could introduce), and that the second call returns a **different** `jobId` from the
> first (the gap this slice recorded rather than closed). The second half is the assertion — the
> whole argument for `CREATE_WITHOUT_A_KEY`'s one entry is that a retry queues the work twice, and
> that is a claim about a server, which only a server can settle. ⬜ NOT RUN

No browser row: neither a CLI nor an MCP server is a browser lane (surface spec §9). No backend
mutation row — nothing in `backend/` moved. The three frontend edits are comment text.

## The next slice

§204's list, minus what this took, plus what this opened.

**Opened here:**

- **`openapi.yaml`'s own prose cites files and nothing checks them.** `boardColumns.test.ts` is one
  and does not exist; the contract is 550 kB of description text and nobody has ever asked the
  question of it. It is the same rule with a third corpus, and the corpus is the one whose prose the
  most consumers read — both generated `schema` copies carry it verbatim into two stacks.
- **`backend/` is the fourth corpus and needs a different scanner.** Javadoc cites with `{@link}`
  and `{@code}`, not backticks, and `{@link}` is already compiler-checked while `{@code Foo.java}`
  is not. Given §152, §160 and §165 each found a load-bearing false sentence in a javadoc, the
  population there is the one most likely to be non-empty.
- **`tcms_report.generate` has no replay protection and the contract offers none.** The measurement
  is `§205 PL-4`; the fix is a column on `report_job`, a replay lookup, and `IdempotencyKey` on the
  operation. It is the only create on either machine surface in that state.
- **`Problem.type` is required, sent on every refusal, and read by nothing on either stack.** The
  SPA has no field for it; `@tcms/client` has one with no reader. Deciding whether `describeError`
  can do something with a problem *kind* that it cannot do with a status is the question, and
  §165's rule says the answer arrives with a consumer or not at all.

**Carried forward:** `observeStatus` has one reader and fifteen create arms ignore it, eight of them
by decision (§204); `Location` on the contract's 201s is unlooked-at (§204); the two `api(routes)`
harnesses are still near-copies and the MCP one's header still says it *is* the CLI's (§203); two
fixture modules over one contract and nothing pairs them (§203); nothing counts how many tests mount
a boundary (§201); `app/queryClient.ts` and `test/queryClient.ts` both report and nothing pairs them
(§202); a hatch is a per-test declaration and nothing reads the 44 that say *"this test refuses a
write"* (§202); resume the browser queue at `A2 — plans`; **a third `packages/` live pass**, now
carrying `§203 PL-1`, `§204 PL-2`, `§204 PL-3` and `§205 PL-4`; the snapshot has no reader (§193); a
clone cannot file the copy nowhere (§195); nothing pairs the two tsconfig lists (§196); the
undeclared-property question is off under `additionalProperties` (§198); `NEEDS_A_SAVE` has no
producer and never had a test (§200); a stub is `INCOMPLETE` only once approved and nothing tells its
author before then (§200); the read-only archive decision §159 did not take; serving the relabel
audit (§174); `CurrentUser.teams` has no reader (§175); a run has no edit endpoint (§177); §184's,
§185's, §188's, §189's and §190's items.
