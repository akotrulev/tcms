# Deferred mutation checks — the packages and frontend lane

**Split out of `backend-mutation-checks.md` on 2026-08-22**, by the decision recorded in
`docs/plans/2026-08-22-backend-mutation-backlog-run-plan.md` §9. These rows mutate
`packages/client`, `packages/cli`, `packages/mcp` or `frontend/src`. **None of them can be run by
`./gradlew test`** — they belong to `npm run test:run`, and carrying them in a queue whose whole
instruction is a Gradle scope made a backend pass filter them out by hand every time.

This is the second time this file has done that: its own header records the frontend rows of
§83–§96 being struck out of the 2026-08-05 pass and left in git history for whoever ran the other
suite. This time they are moved rather than struck, because a row nobody can find is a row nobody
runs.

**Ids are unchanged and are never renumbered** — they are quoted from handoff sections, so `TS-4`
means here exactly what it meant there. The backend file keeps a forwarding line where each section
stood.

## How to run this file

```bash
cd packages && npm run test:run --workspaces     # client / cli / mcp
cd frontend && npm run test:run                  # the SPA
```

Everything the backend file says about *reading* a result still applies, and is the reason these
rows exist rather than a green suite being taken at its word:

- **A mutation that reddens MORE than the row predicted is as much a finding as one that reddens
  fewer.** Too few means the test does not cover what it claims; too many means the tests are
  coupled to something other than what they name.
- **A row predicting zero is not a row to skip** unless it has *derived* that zero structurally —
  see the backend file's `DOCUMENTED GAPS` register for where that line is drawn.
- Revert by **exact path** before the next row.

---

### §137 — the TypeScript client and the CLI (`TS-1` … `TS-9`)

**A new stack, and the queue's first rows that are not `./gradlew`.** These mutate
`packages/client` and `packages/cli` and are verified with `cd packages && npm run test:run
--workspaces` — the scope column names the file, since the suite is ~0.5s whole and there is no
container to boot. Assertions live in `http.test.ts` (21), `client.test.ts` (10),
`schemaFreshness.test.ts` (4), `core/core.test.ts` (33) and `cli/src/main.test.ts` (14).

> **NONE RUN.** **`TS-4` first** — it is the only row whose failure is a security finding rather
> than a defect, and `TS-2` second, because its failure mode is a *silent* data loss on somebody
> else's edit.

| # | Mutation | Expected | Result |
|---|---|---|---|
| TS-1 | `http.ts` sends the outgoing request as `new Request(request, { headers, signal: deadline })` instead of a URL and an init | The shape the first draft had. The deadline fires and the copy's signal never does, so every request hangs until the peer gives up — the failure the budget exists to prevent, arriving through the budget. Should redden `becomes a TcmsTimeoutError that says the write may have landed`. **The row that pins the finding**; if it does not redden, the workaround is unnecessary and the comment in `http.ts` is wrong. | ⬜ NOT RUN |
| TS-2 | `concurrency.ts`'s `versionOf` returns `{ none: 'state-preconditioned' }` instead of throwing when the resource carries no `lockVersion` | Every edit whose read lacked a version silently becomes last-write-wins, overwriting a colleague's concurrent change with no error anywhere — the single worst thing this surface can do. Should redden `refuses to write when the read carried no version`. | ⬜ NOT RUN |
| TS-3 | `http.ts` drops the `if (perRequest.ifMatch !== undefined)` guard for a truthiness check | `lockVersion: 0` — a resource nobody has edited yet — stops sending `If-Match` while every other version keeps working, so the defect is invisible on any fixture that has been saved once. Should redden `sends If-Match for lockVersion 0, which a truthiness check would drop`. | ⬜ NOT RUN |
| TS-4 | `refusal.ts`'s 404 branch returns the 403 branch's message | The enumeration oracle §6t exists to prevent: a caller in another company learns that a row exists from a message naming the permission they lack. Should redden `says nothing about permissions or roles on a 404` and the CLI's `says nothing about permissions on a 404`. **Failure here is a security finding, not a defect.** | ⬜ NOT RUN |
| TS-5 | `identifiers.ts` adds `SU` to `KEY_PATTERN`, as the surface spec's §4.4 example implies | `TS-7` and `SU-7` both parse; the second resolves nothing and reports "not found" for a key the user believed in. Should redden `parses the five seeded prefixes and nothing else`. | ⬜ NOT RUN |
| TS-6 | `identifiers.ts`'s `classify` drops the `expected` mismatch check | `tcms case show TS-7` looks up a suite key in the case index and reports "no such case" — a typo with a plausible-looking answer. Should redden `refuses a key of the wrong kind rather than looking it up` and the CLI's `refuses a key of the wrong kind without making a request`. | ⬜ NOT RUN |
| TS-7 | `paging.ts`'s `eachItem` drops the `result.items.length === 0` exit | A server answering an empty page while claiming a larger total spins forever against an instance the client cannot fix. Should redden `stops on an empty page even when total claims more`. | ⬜ NOT RUN |
| TS-8 | `main.ts` returns `EXIT.failed` for a `UsageError` | A mistyped flag and a refused request become indistinguishable to a script, so a pipeline retries the one that will never succeed. Should redden `exits 2 when a show command is given no argument` and `exits 2 for a bad --timeout, not 1`. | ⬜ NOT RUN |
| TS-9 | Two concurrent invocations of a write carrying one `Idempotency-Key` | **Not a mutation — a missing test**, and the same gap `IK-1` records on the backend side, now with a client that can actually produce the race. Nothing in either stack drives two concurrent creates under one key. **Known gap.** | ⬜ NOT RUN |

**Not in this queue, deliberately:** the CLI's read commands against a live instance. That is
neither a mutation nor a browser lane, and the WIP plan's §9 puts rows here for *the agent-token
path and every write arm* — which is phase 7, not this slice. What it means in the meantime is
stated plainly in the handoff: **no command in `@tcms/cli` has been run against a real TCMS.**
Every test above drives a stubbed `fetch`, so the contract is enforced by the types and the
behaviour by the stubs, and the one thing neither can observe is the server disagreeing with its
own `openapi.yaml`.

### §138 — `ai_proposal`, the review boundary (`AP-1` … `AP-8`)

**The queue's first rows whose subject is a boundary rather than a field.** Scope is
`./gradlew test --tests 'com.tcms.repository.AiProposalEndpointTest'` unless a row says otherwise;
assertions live in that class's five nested groups.

> **NONE RUN.** **`AP-1` first** — it is the only row whose failure is a product-integrity finding
> rather than a defect: it is what stops any caller minting verified coverage.

| # | Mutation | Expected | Result |
|---|---|---|---|
| AP-1 | `AiProposalInput` gains a `verdictSource` field and `AiProposal.file` uses it | Any MCP client can send `verdictSource: TCMS_VERIFIED` and have TCMS report its own critic checked a case nothing checked — §6w.1a's whole argument, defeated by one field added "for symmetry". Should redden `a claimed source is ignored and derived instead`. **If it does not redden, the second barrier (`V43`'s CHECK) is carrying the rule alone and the test is not testing what it says.** | ⬜ NOT RUN |
| AP-2 | `V43`'s `verdict_source` CHECK gains `'TCMS_VERIFIED'` | Reddens nothing on its own — the second barrier is invisible while the first holds, which is why both exist. **Known gap made visible**: run it together with `AP-1` and the pair should still redden, because the derivation is what the test observes. | ⬜ NOT RUN |
| AP-3 | The file endpoint is gated on `case:read` instead of `case:create` | The client's read-only stakeholder can fill the reviewer's queue, and every read in this module would pass the same gate. Should redden `a Viewer is refused — proposing is creating, not reading`. | ⬜ NOT RUN |
| AP-4 | `suite_id` gets its foreign key back (`REFERENCES suite(id)`) | A proposal naming a suite id that does not exist becomes a **500 at file time** instead of a stored proposal a reviewer can reject — so a generation agent that hallucinated one id loses the batch. Should redden `A SUITE THAT NO LONGER EXISTS IS STILL FILED`. **This row is the record of a constraint that was written, failed this test, and was removed deliberately.** | ⬜ NOT RUN |
| AP-5 | `AiProposalService.store` validates the payload through `CaseEdit.validate` before storing | Two answers to "is this a legal case", given a week apart, and the file-time one refuses proposals the accept-time one would have taken. Reddens `A SUITE THAT NO LONGER EXISTS IS STILL FILED` and probably nothing else — **the rest of the cost is invisible to the suite**, which is why the decision is written in the service header rather than only here. | ⬜ NOT RUN |
| AP-6 | The replay lookup moves **after** `validate` | A retry whose payload drifted gets a 400 about fields on a proposal that already exists, sending a program to fix something it cannot see. Should redden `a retry whose payload drifted still replays rather than 400ing`. | ⬜ NOT RUN |
| AP-7 | `findByIdempotencyKey` drops its `projectId` predicate | A key reused across two projects of one company passes the lookup and then violates the index — a validated request that 500s. **No test covers this yet on this table**; the shape is `IK-4`'s and the same gap. **Known gap.** | ⬜ NOT RUN |
| AP-8 | `filedByAgent` is computed as `createdBy != null` rather than `createdTokenId != null` | Every proposal reads as agent-filed, including the ones a person typed — so the flag that changes how a reviewer reads a proposal says the same thing about all of them. Should redden `a proposal filed in a browser session is not filed by an agent`. | ⬜ NOT RUN |
| AP-9 | `AiProposalDecisionService.decide` drops the `TokenContext.currentOrNull() != null` branch | **The whole boundary.** An agent token files a proposal and accepts it in two calls, so the review surface is defeated by the thing it exists to review — and nothing in the permission model notices, because `Agent` holds `case:create` on purpose. Should redden `AN AGENT TOKEN CANNOT ACCEPT ITS OWN PROPOSAL` and the reject half of `a Viewer can decide nothing` stays green throughout, which is the point: **no permission test can see this.** | ⬜ NOT RUN |
| AP-10 | The credential branch moves **after** the proposal lookup | An agent learns which proposal ids exist from the 404-versus-403 difference — §6t's enumeration oracle arriving through the one endpoint that refuses it for an unrelated reason. **Reddens nothing**; the suite has no assertion that an agent gets the same answer for a real id and an invented one. **Known gap**, and the reason the ordering is written in the method's javadoc. | ⬜ NOT RUN |
| AP-11 | `acceptOne` inserts the case itself instead of calling `CaseCreateService` | A second definition of a legal case, a display key allocated outside `DisplayKeys`, no v1 version row, and a status a payload field could set — every §6w.6 guarantee that currently holds *by construction*. Should redden `a person accepts, and the case arrives through the ordinary create path` on the key, the status, or the owner. | ⬜ NOT RUN |
| AP-12 | The synthetic `Idempotency-Key` passed to the create path is replaced with `null` | Two reviewers accepting at the same moment create **two cases from one proposal**. Reddens nothing single-threaded — `accepting twice answers with the case` passes on the status check alone — so this is the same race gap as `IK-1`, one level up. **Known gap.** | ⬜ NOT RUN |
| AP-13 | `rejectOne` overwrites the note when the proposal is already `REJECTED` | A second click erases the reason the decision was actually made for, which is the one thing keeping the row was for. Should redden `rejecting twice keeps the FIRST note`. | ⬜ NOT RUN |
| AP-14 | The accept path stops writing `ai_proposal.accepted`, relying on `case.created` alone | The audit trail records that a case was created and never that it came from a model — so "which cases did an agent propose" is unanswerable a month later, and `verdict_source` is nowhere in the log. Should redden `both decisions are audited, and the accept records the provenance`. | ⬜ NOT RUN |


---

### §139 — the CLI's write arms (`CW-1` … `CW-12`)

**The second batch of rows that are not `./gradlew`**, on `TS-*`'s terms: mutate `packages/`, run
`cd packages && npm run test:run --workspaces`, and name the file in the scope column since the
whole suite is under a second. Assertions live in `cli/src/writes.test.ts` (48 across five groups)
and `client/src/core/core.test.ts`'s `mutation intents` (5).

> **NONE RUN.** **`CW-1` first** — it is the row that pins this slice's whole finding, and its
> failure mode is a *silent* loss of somebody else's work. `CW-5` second, for the same reason one
> noun over.

| # | Mutation | Expected | Result |
|---|---|---|---|
| CW-1 | `writes.ts`'s `versionFor` drops the `fromFile` branch and always reads a fresh version | The finding, defeated. A `--file` edit built from a read at version 3 sends `If-Match: 5`, the server accepts, and whoever saved at 4 loses their words — last-write-wins arriving **through** the header that prevents it, with a green build and a 200. Should redden `refuses a --file edit that names no --lock-version` and `refuses a whole-body edit that names no version, and says why`. **If it does not redden, the rule is a comment and not a behaviour.** | ⬜ NOT RUN |
| CW-2 | `context.ts`'s `strictNumber` returns `undefined` for an unparseable value instead of throwing | `--lock-version latest` falls through to a *fresh read* — the typo silently selects the unsafe path, which is `CW-1` reached by accident rather than by design. Should redden `refuses a --lock-version that is not a number, rather than falling back to a fresh read`. | ⬜ NOT RUN |
| CW-3 | `main.ts` restores `strict: false` and stops merging `command.options` | §137's live defect, restored. Every string flag on every command parses as `true` and its argument becomes a positional, so `case list --q login` answers the unfiltered list and `case edit --title x` sends `{}`. Should redden `sends the filter values, rather than parsing them as booleans`, `exits 2 rather than sending an empty body`, and most of `writes.test.ts`. **The row that pins a defect that shipped and was invisible for two slices.** | ⬜ NOT RUN |
| CW-4 | `context.ts`'s `requireSomethingToDo` returns the body instead of throwing | A `PATCH` of `{}` is legal by the contract, so a command whose flags were all wrong answers 200 and reports a save. Should redden `exits 2 rather than sending an empty body that reports success`. | ⬜ NOT RUN |
| CW-5 | `docEdit` sends `blocks: page.blocks` alongside the title, "so the page round-trips" | The contract's rule is that `blocks` is the whole page and an **omitted block is deleted** — the helpful round-trip is one stale read away from replacing somebody's specification with an older copy of it. Should redden `retitles without sending blocks, which would empty the page`. **The one mutation in this queue that destroys authored content rather than a field.** | ⬜ NOT RUN |
| CW-6 | `present()` keeps `undefined` values instead of dropping them | Every unnamed field is sent as `null`, so `case edit --title x` clears the description, the objective, the owner and the automation key. `PATCH`'s absent-versus-null split is the whole protection and this removes it. Should redden most of `case edit` and `suite writes`. | ⬜ NOT RUN |
| CW-7 | `nullableText` maps `''` to `undefined` rather than `null` | The clearing form stops clearing: `--description ''` becomes "leave it alone", silently, and a user who cannot empty a field has no other spelling to reach for. Should redden `leaves unnamed fields alone, clears an empty one, and files by suite key`. | ⬜ NOT RUN |
| CW-8 | `caseProposal` defaults `verdict` to `UNVERIFIED` when none was given | Absent and `UNVERIFIED` are different claims — nobody asserted anything, against an agent asserting it had not checked — and they differ in `verdictSource`, which §6w.1a is built on. Every silent proposal becomes a claim. Should redden `sends no verdict when none was given`. | ⬜ NOT RUN |
| CW-9 | `suiteStatus` filters the target state against a client-side ladder | A second copy of §6g's ladder, in a client versioned independently of the instance it points at — so a Test Lead's token is refused a move the server would have allowed, by the tool rather than by the rule. Should redden `does not filter the ladder client-side`. | ⬜ NOT RUN |
| CW-10 | `planEdit` stops threading the plan's current name through a name-less edit | `TestPlanUpdateInput` requires `name`, so `--description`-only becomes a 400 about a field the user never mentioned. Should redden `carries the plan's current name through an edit that does not mention it`. | ⬜ NOT RUN |
| CW-11 | `resultRecord` takes the `lockVersion` off the run detail's execution summary instead of the execution's own detail | `TestExecutionSummary` carries no `lockVersion` at all, so this is `versionOf` throwing — a loud failure, which is the right one. Should redden `finds the execution by case key and the status by name`. **Predicted to redden; the row exists because the summary/detail split is easy to miss and the cheap read is the wrong one.** | ⬜ NOT RUN |
| CW-12 | `reviewComment` picks `reviews[0]` instead of the first `IN_REVIEW` one | A comment aimed at a closed review earns a 409 the user cannot act on — and re-submitting opens a *new* record, so the row it aimed at will never accept one. Should redden `refuses a closed review here, rather than earning a 409 from the server`. | ⬜ NOT RUN |

**The gap §137 recorded is now wider and is still the most important thing in this section:**
**no `@tcms/cli` command has been run against a real TCMS**, and there are sixteen write commands
in that state. Every row above mutates code observed only by a stubbed `fetch`. The live pass this
needs is one setup — `docker compose up`, `provision-company`, issue an `Agent` token, `TCMS_URL`
and `TCMS_TOKEN` — after which every read command from §137 and every write command here is one
invocation. It is not a browser lane and not a mutation, so neither queue owns it; it is named in
the handoff as its own outstanding item.


---

### §140 — the MCP server (`MS-1` … `MS-10`)

**The third batch that is not `./gradlew`**, on `TS-*`'s and `CW-*`'s terms: mutate `packages/`, run
`cd packages && npm run test:run --workspaces`, and name the file in the scope column since the
whole suite is under a second. Assertions live in `mcp/src/session.test.ts` (15),
`mcp/src/reads.test.ts` (11), `mcp/src/writes.test.ts` (27) and `mcp/src/server.test.ts` +
`mcp/src/http.test.ts` (20).

> **NONE RUN.** **`MS-1` first** — it is the row that pins this slice's whole finding, and its
> failure mode is one company's session shape disclosed to another through a field that reads like
> documentation. **`MS-4` second**, because its failure is §6t's enumeration oracle.

| # | Mutation | Expected | Result |
|---|---|---|---|
| MS-1 | `http.ts` hoists `buildServer(session)` out of the request handler into a module-level singleton built from the first session | The finding, defeated. `instructions` is a constructor argument sent once during `initialize`, so every later caller receives the **first** caller's capability summary — their name, email, project key and reach — and a filtered tool list computed from somebody else's permissions. Should redden `gives each caller their own server, with their own capability summary` in `http.test.ts`. **Nothing else would fail: every tool still works and the protocol is still correct.** | ⬜ NOT RUN |
| MS-2 | `session.ts`'s `probe` sends `projectId` to `GET /me/permissions` even when no project resolved | A token that can see three projects reports the PROJECT terms of whichever one it happened to pick, so `tools/list` offers write tools for a project the session is not bound to and every call 404s. The contract is explicit that the two answers are different sets rather than one filtered. Should redden `does not choose between several projects, and says how to bind one`. | ⬜ NOT RUN |
| MS-3 | `session.ts` drops `qualifiers` and keeps only the keys | §65's SPA defect, one client over: a tool held at `OWN` or `PLAN_SCOPE` lists with a description claiming the whole scope, and the model offers an operation the server refuses per row — the refuse-and-retry loop the filtering exists to prevent. Should redden `keeps the qualifier, not only the key` and `lists a narrowly-held tool and says what it is narrowed to`. | ⬜ NOT RUN |
| MS-4 | `server.ts`'s `CallTool` handler answers an unlisted tool with the tool's gate permission instead of "no tool named X" | A hidden tool confirms its own existence, and the permission it names tells the caller what the instance's model looks like — the enumeration §6w.3's hiding removes, restored through the refusal. Should redden `refuses a hidden tool without confirming that it exists`. | ⬜ NOT RUN |
| MS-5 | `result.ts`'s `failed` returns the raw `error.detail` for every status instead of routing through `describeRefusal` | The 404 branch stops being the silent one. The server's own 404 detail can name the thing that was not found, which is the difference between "no such case, or it is not visible" and confirming the row exists to a caller in another company. Should redden `names nothing on the 404 branch` — **the one row here whose failure is a security finding rather than a defect.** | ⬜ NOT RUN |
| MS-6 | `server.ts`'s dispatcher passes `tool.gate.permissions[0]` instead of the resolved arm's permission | A refused edit on `tcms_write_case` reports `case:create`, so the user asks an administrator for a permission they already hold and the grant that would fix it is never requested. Should redden `names the permission of the ARM that ran, not the tool's first gate key`. | ⬜ NOT RUN |
| MS-7 | `writes.ts` replaces `requireExplicitVersion` on the `blocks` arm with the ordinary `versionFor` | §139's finding at the one place it bites here. A `blocks` array is the whole page and its content came from an earlier read, so a fresh version asserts a state that body was never based on: the server accepts, and whoever saved in between loses their paragraphs. Should redden `demands the caller's own lockVersion when replacing blocks`. **The only mutation in this section that destroys authored content.** | ⬜ NOT RUN |
| MS-8 | `writes.ts`'s `recordResult` adds `evidence:attach` to its gate "for symmetry with the spec" | A caller holding `run:execute` and `result:submit` without `evidence:attach` loses the tool entirely — hidden by a permission it never exercises, which is §2's failure arriving through the declaration rather than the merge. Should redden `does not declare evidence:attach, because it cannot attach evidence`. | ⬜ NOT RUN |
| MS-9 | `writes.ts`'s `openReview` picks `reviews[0]` instead of the first `IN_REVIEW` one | `CW-12` one surface over: a verdict aimed at a closed review earns a 409 the caller cannot act on, and re-submitting opens a *new* record, so the row it aimed at will never accept one. Should redden `acts on the OPEN review, not the newest one` and `explains that a closed review cannot be joined`. | ⬜ NOT RUN |
| MS-10 | `instructions.ts`'s `fit` truncates the joined string at `INSTRUCTIONS_BUDGET` instead of dropping whole sections | The summary ends mid-permission-key, and a half-written key is what a model hands the user as the thing to ask an administrator for. Reddens **nothing** — no assertion reads the tail of a truncated summary, only its byte length. **Known gap**, and the reason the section-dropping order is written out in the function's own comment. | ⬜ NOT RUN |

**The gap is three packages wide now.** `MS-*`, `CW-*` and `TS-*` all mutate code observed only by a
stubbed `fetch`. One live setup closes all three — `docker compose up`, `provision-company`, an
`Agent` token, `TCMS_URL`/`TCMS_TOKEN`, then the CLI's commands and an MCP host pointed at
`tcms-mcp --http`. It is neither a browser lane nor a mutation, so neither queue owns it; it is
named in the handoff as its own outstanding item.


---

## §189 — the two ids neither machine surface could name

**Seven rows, none run** — §65. Ids are `§189 CN-*`; `CN` is unused across both queue files and
every handoff section (grepped before minting it, per the §154 banner — `AG`, `CD` and `RL` all
collided because two lanes independently reached for the obvious initials).

**Every row here mutates `packages/` and runs as `cd packages && npm run test:run --workspaces`**,
so this block joins `§141 RL-7`/`RL-8`, `MS-*`, `CW-*` and `TS-*` in being observed only by a
stubbed `fetch`. What that costs is stated in `§141`'s block and holds unchanged here: a stub
answers 200 to whatever arrives, so **not one of these rows can tell a wrong id from a right one**.
The server is where an attachment id is validated against the attachments that reach a case, and
`CN-3` is the row that says so.

**`CN-1` is the row this block exists for.** The two ids look interchangeable — both UUIDs, one
column apart in the two tables this slice prints — and the whole of the slice's care is keeping
them apart at four call sites and two schemas. Swapping them is the edit a reader makes while
"tidying", it types, and the failure it produces is a field error at a terminal rather than
anything a build sees.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§189 CN-1` | In `cli/src/reads.ts`, print `entry.row.conditionId` in `case show`'s CONDITION column (the library id in the attachment's place) | `prints each condition with its attachment id, and says which are declined` | **The row the block is named for.** The column would print an id that is real, resolves to a real row, and is refused by `--decline` — or, worse, is *accepted* by `suite edit --precondition`, where it means something else entirely. A green build means the read half of this slice is asserted by shape rather than by value. |
| `§189 CN-2` | In `cli/src/writes.ts`, make `conditionsFrom` return the bare ids (`completeSet(value)`) instead of wrapping them | `attaches conditions by library id, wrapped as the input the write takes` | The wire shape stops being `CaseConditionInput`. The server answers a field error naming a shape no CLI flag mentions, and the flag has never once worked — §143's defect exactly, in a client whose type assertion (`as SuiteInput`) is on the merged body and therefore cannot see it. |
| `§189 CN-3` | In `mcp/src/writes.ts`, read `strings(input, 'declinedConditions')` into `preconditions` instead of `declinedConditionIds` | *nothing* — a **hole**, and stated as one | The two fields take the same JSON type and the stub accepts either, so declining a condition would *attach* it — the exact inversion of the caller's intent — and every test here would stay green. Only the server can refuse it: `CaseConditionInput` requires an object, so this is a 400 on the first live call. **This is the row that says what a stubbed surface cannot check**, and it is the reason the live-pass row below exists. |
| `§189 CN-4` | In `cli/src/writes.ts`, have `completeSet` keep the empty strings rather than filtering them | `re-imposes every declined condition on --decline ""` and `detaches every condition on --precondition ""` | `--decline ''` would send `['']`, which is a field error rather than the clearing form — so the one spelling a shell has for "empty this list" would be the one spelling that fails, on all four complete-set flags at once. |
| `§189 CN-5` | In `client/src/core/rewrite.ts`, add `declinedConditionIds: [...current.declinedPreconditions, ...current.declinedPostconditions].map((ref) => ref.id)` to `caseInputFrom` | `carries every field of TestCaseInput a round-trip may send, and no other`, and `says nothing about declines when no flag named them, on a case that has one` | The reversal §189 considered and refused. Every `--title` edit would state the complete set of an author's opt-outs as though the caller had named them; harmless while the read's ids are exactly the declinable ones, and a claim nobody made either way. The `excluded` list in `core.test.ts` is what makes it a decision rather than a default. |
| `§189 CN-6` | In `cli/src/reads.ts`, merge the declined arrays into the live ones in `case show` (one list, no STATE column) | *nothing* — the **second hole** | The two assertions are `toMatch(/sc-2.*declined/)` and `/sc-1.*in force/`, which a merged list with a marker column still satisfies. What the mutation destroys is the *reading*: a declined condition is not part of what the case does, and a single list is one careless glance from saying it is. Recorded rather than faked — the alternative is asserting on a table's layout, which is how a test starts refusing a better one. |
| `§189 CN-7` | In `mcp/src/writes.ts`, drop the `preconditions`/`postconditions` properties from `tcms_write_suite`'s `inputSchema` while leaving the `run` body reading them | *nothing* | `additionalProperties: false` is declared and the SDK validates against it — but these tests call the tool's `run` directly, so the schema is never applied. The arm keeps working here and is refused at the boundary in the field: a tool whose schema and body disagree is invisible to every test in this package. **The same gap `MS-*` records, at a new door.** |

**The live-pass row this slice leaves.** `CN-3`'s hole is not closeable here: the check it wants is
one real `tcms_write_case` `action: edit` carrying `declinedConditions`, against an instance where
the case actually inherits a suite condition — the fixture `§186 A1-2` authors on the browser side,
which is the same state. It belongs to the next `packages/` live pass
(`docs/verification/2026-08-09-packages-live-pass-round-2.md` is the most recent), alongside one
`tcms suite edit --precondition` against a real library id. Both are **NOT RUN**, and neither has
ever been executed by anything: these two arms have existed for one commit.


---

# TS rows that stayed in the backend file, and why

Four sections moved whole because every row in them is TypeScript. These rows are TypeScript too,
but they sit **inside sections whose other rows are backend** — extracting a single row would break
the narrative the section is written as, so they stay where they are and are indexed here instead.

| Row | Lives in | Target |
|---|---|---|
| `§141 RL-7` · `§141 RL-8` | §141 — rate limiting | `packages/client`'s `describeRefusal` — the `429` branch and its `retryAfterMs` fallback |
| `TA-9` | §132 phase 2 | `CaseHistoryPage.authorLine` |
| `§244 ED-M8` · `§244 ED-M9` | §244 amendment | `ExecutionPage`'s verdict-button `disabled`, `ExecutionGrid.mark`'s `durationMs` |
| `DR-10` | §120 — automation drift | `StatusChip.tsx` — **already run**, reddened exactly one test |
| `R6-10` … `R6-13` | §16 — R6 phase 3's dialog | `GenerateReportDialog.tsx` — **all four already run** |

Run them with this file's lane, record the result in the section where the row lives.
