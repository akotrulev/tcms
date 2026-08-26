# §254 — the surfaces' machine-readable half, and the five checks that were specified and not written

**A read-only review of `@tcms/mcp`, `@tcms/cli` and `@tcms/client` against the `tooling` plugin's
own capability-contract / MCP-builder / CLI-builder rules, executed as a slice.** The report is
`docs/audits/2026-08-26-mcp-cli-surface-review.md` — 19 confirmed defects, ranked, separated from
design opinions, every one cited to `file:line`. Everything below is what closing them cost and what
it turned up on the way.

## The verdict the review reached first, because it changes how to read the rest

The surface is **strong**: 24 tools over 128 REST paths cut by an argued rule, no
`execute(command)` tool, no `BaseTool` hierarchy, no registry framework (`tools.ts` is 29 lines),
and `writeConformance.test.ts` genuinely covers **every** declared arm on both surfaces and throws
rather than skips on one it does not recognise. The permission machinery is the best part of it and
has a computed check proving the descriptions do not overstate what a caller can do. What was wrong
concentrated in four places, and three of the four are the same shape: **something the core already
computes was thrown away at the boundary.**

## A1 — the security one. The HTTP transport authenticated `initialize` and nothing after it

`http.ts` read the bearer once, on the initialize path, and never bound the session to it. So a
request carrying a valid `mcp-session-id` and **no `Authorization` at all** drove that session —
every tool, as the original user, with their grants — and an unauthenticated `DELETE` terminated it,
because a `DELETE` reaches the transport through the existing-session branch and never passes the
POST guard. A session id is a **routing handle, not a credential**: it travels as a header on every
request and response, through the Caddy this deployment mandates, into proxy access logs.

`Live` now carries a SHA-256 of the token that opened it and every later request must present the
same one, compared with `timingSafeEqual` — the fingerprint is the one secret here and the session
id is the thing assumed public. Two tests: a live session refusing an unauthenticated call and a
different bearer, and an unauthenticated `DELETE` failing to kill a session. **The existing test file
covered the credential on the initialize path only**, which is why nothing found this.

`A18` rode along: sessions were pruned only by `onsessionclosed`, so a host that vanished without a
`DELETE` left a live `Server`, a `Reach` and a token-bound client resident for the process's life.
There is now a 30-minute idle sweep, **lazy** rather than on a timer so an idle process still exits.

## A2/A3/A4 — one classifier, two surfaces, and the thing that had been computed and discarded

The MCP returned prose with `isError: true` and nothing else. `ToolResult` **declared**
`structuredContent` and assigned it nowhere; no tool had an `outputSchema`. The CLI collapsed 403,
404, 409, 429, every 5xx and every timeout into **exit code 1**, and documented no exit codes at all.
So a pipeline could not tell *"rate limited, back off"* from *"this token will never be allowed to do
this"*, and a model deciding whether to retry had to regex an English sentence.

**The information was never missing.** `describeRefusal` already branched on 403/404/401/429, its
429 branch already parsed `Retry-After`, and `isTransient()`/`retryAfterMs()` had been exported from
the core all along **with no consumer**. `core/outcome.ts` is that branch, kept: a closed
`FailureCode` vocabulary, `retryable` **stated rather than inferred**, `retryAfterSeconds`,
`permission` copied only where `refusal.ts` says it may be named, `fields`, and `mayHaveWritten` for
the one failure whose result is unknown. The MCP renders it as `structuredContent` beside the
unchanged prose, under one `RESULT_SCHEMA` declared as every tool's `outputSchema`. The CLI renders
it as **ten exit codes** (`satisfies Record<FailureCode, number>`, so a new code is a compile error
until it has a number) and, under `--json`, as an error envelope on stdout where there had been
nothing at all.

**A4 is the one worth remembering.** Surface spec §6 has claimed since §140 that *"refusal copy …
comes from the core, so a CLI command and its MCP counterpart cannot disagree."* They disagreed:
`describeRefusal` had exactly **one** consumer and the CLI printed the server's raw `detail`. The
same 403 read *"this token's role does not hold `case:edit`. Ask a project administrator…"* through a
host and *"You do not have permission to do this."* in a terminal — on the surface a **person** uses
to find out what to ask for. The spec sentence is struck and rewritten rather than deleted.

## A5 — the permission machinery's one bug, one level below where it was already fixed

`tcms_review_verdict` serves cases, suites and plans on an `any` gate — the surface's only one — and
both its arms hard-coded `permission: 'case:review'`. `openReview` **computed the subject's own
key and returned it, and nothing read it.** So a caller refused on `TP-1` was told to go and ask an
administrator for `case:review`: the wrong key, for the wrong artifact. That is exactly the failure
`Attempt.permission`'s own doc comment exists to prevent — *"naming the tool's first gate key would
send the user to an administrator asking for the wrong thing"* — reproduced one level down, on the
tool most likely to be listed for a caller holding one of its three keys and refused on the other
two. `ToolArm.permissionFor` is the fix, synchronous off the display key's prefix.

## The five checks that were specified and not written

**§8.1 gate conformance** and **§8.2 the merge rule** were specified in §140 and never built.
What existed asserted each arm's permission is inside **its own tool's** gate — self-consistency,
which a gate wrong in both places satisfies.

- §8.1 now reads the gate out of each operation's own prose in `openapi.yaml` (*"Requires
  `suite:manage`."*), resolving the operation from the URL the arm actually sent. Weaker than the
  Java annotation and the strongest source a TypeScript test can reach; what matters is that it is
  not a list kept beside the registry. **Its vacuity guard is a guard on the reader**, because prose
  can quietly stop answering and an assertion over an empty match set reads exactly like a pass.
- §8.2 runs on `roleMatrix()`, the §A3 parser two other tests already trust. **Every merge is legal
  today, which is why it was worth writing**: nothing fails now, and a migration narrowing one key
  would otherwise take a whole tool away from every contributor with the build staying green.

**Two more were added, and both found live defects.**

- **A7, the catalogue against the SURFACE.** §253 pinned `core/shapes.ts` to `openapi.yaml` and
  asked **nothing** of the eleven write tools, ten of which hand-write their `inputSchema`. So
  `tcms_shape` could describe a suite one way and `tcms_write_suite` another with nothing firing —
  §253's own rule (*hand-written is fine; hand-written and unpinned is the defect*) applied to one
  of eight entities. The new pin found **three disagreements that were shipped**: `tcms format plan`
  printed `--milestone-name` where the command takes `--milestone`, and the same for a run's
  `--release-version`/`--build-number`/`--commit-sha` — **flags the surface's own help screen told a
  user to type and `parseArgs` refuses in strict mode** — and `doc edit` had no `--parent` at all
  while `tcms_write_doc_page` accepted one. The catalogue now carries the shipped spellings
  (renaming a flag is a promise broken to whoever scripted against it) and `doc edit` gained
  `--parent` / `--root`.
- **A15, the shipped guide against both registries.** `frontend/src/features/help/` enumerates the
  tools and commands in prose and went stale **the slice it was written in**: `tcms_shape` and
  `tcms format` shipped in §253 and neither list nor the count beside them gained an entry.
  `guideCopies.test.ts`, one per package, compares prose to registry — both ways for the tools.

**A8** is the same family and the sharpest instance. `case edit`'s flags came from the catalogue and
its **body was still read back by hand**, one named key at a time; the test that looked like it
covered this compared `optionSpecFor(…)` to itself and could never fail. So a field added to
`CASE_FIELDS` produced a flag `parseArgs` accepts and a builder that silently ignores it — §137/§139's
silently-dropped-flag defect, with a new door cut into it by the slice that was closing the old one.
It is now driven with every catalogue flag and asserted to reach the wire.

## A13 and A12 — two capabilities that were exposed and not usable end to end

`tcms_write_case propose` is the flagship write and files into a queue **neither surface could
read**. `GET …/ai-proposals` was on no tool and no command, so an agent could not report whether
yesterday's proposals were accepted, could not learn from a rejection, and could not tell a duplicate
from a gap before filing again. Unlike the two exclusions the spec argues, this one was never a
decision. `tcms_list_proposals`, `tcms proposal list` and `tcms proposal show` close it. The tool is
gated with the sibling reads because the endpoint enforces **project visibility only** — a narrower
gate here would be this surface inventing one.

`tcms_report` was worse: `type` is an open string whose catalogue lived **only inside a refusal**
(named *after* the caller guessed wrong), and `definitionId` took an id nothing on either surface
could produce. This is the one finding whose fix reached the backend: `GET …/reports/capabilities`
now answers `types` beside `formats`, derived from the `ReportComputation` registry rather than
written down — and `tcms_report` gained `capabilities` and `definitions` read actions. **The two
fields get opposite treatment on purpose**: a format is filtered against the contract's askable enum
because the wire declares three and a deployment renders two, while a type's whole set *is* whichever
computations are registered, so filtering it would be the copy `ReportTypes` refuses to keep.

## A6 — four list shapes and two has-more keys

`tcms_search_cases` answered `{ cases, page, size, total, more }`, `tcms_list_suites`
`{ items, …, truncated }`, `tcms_list_library` the same nested a level deeper under `conditions`, and
the single-suite branch a bare upstream object. Every list a model paged cost it a shape to re-learn
and the loop condition was not the same field twice in a row. One envelope now, `items` everywhere,
and **`hasMore` and `truncated` kept apart rather than merged** — §4.6's argument is that four of
these endpoints do not paginate at all, so `hasMore` means *ask for page + 1* and `truncated` means
*the endpoint handed over everything and this tool cut it*.

## A11 — the enums that were enumerated on the write side and left as prose on the read side

§203 fixed this on the write side, stated it as a rule (*a host validates the input schema before the
call, so a wrong member is refused here rather than at the server*), and applied it to one property.
The read filters stayed prose — and prose is not a weaker spelling of an enum, it is an **unpinned**
one, which had already drifted: the condition-library filter was documented as *"PRE or POST"* while
the contract has taken `[PRE, POST, BOTH]` all along, so the one value that narrows nothing was
undocumented on the only surface offering it. `READ_ENUMS` in the catalogue, pinned to `openapi.yaml`
by the same check that pins the write side. `sort` stays open, because the contract declares
`field,dir` with no enum — what a caller was missing there is the **form**, and it now says so.

## The rest

**A10** — the CLI had no consent gate, no `--yes`, no `--dry-run`, and deleted silently from a
terminal while the MCP **refused outright** when a host declared no elicitation capability. That MCP
decision is the good one and its argument is quoted into the CLI's: *a consent gate that silently
degrades to "no prompt available, carry on" is not a gate.* A dry run stops at dispatch, so it
promises only the thing it can promise honestly — **nothing was sent**. **A19** added `--version`,
`--quiet`; `--verbose` was deliberately **not** added, because this client has no second level of
detail and a flag that changes nothing is worse than its absence. **A16** — `tcms_shape` returned a
mistyped entity as a *success* carrying an `error` key, in the one file whose header says a server
trusting a peer's validation is trusting a peer it did not write. **A14** — one comment stating the
reverse of its code (`context.ts` claimed the CLI parses non-strictly and cited the file that
records the opposite as a §139 correction); deleted, not rewritten. **A17** — the three write tools
the catalogue does not cover are now a **stated exclusion with a reason each**, and `tcms_shape`
routes those words to the tools that own them.

## Gates

Run on `shanks`. **Packages: build clean, lint 0/0, 383 tests (97 cli · 138 client · 148 mcp)**, up
from 367. Frontend and backend as recorded in the index row.

## Not done, deliberately

- **`tcms_list_suites` still returns a bare upstream `Suite`** on its single-suite branch. It is the
  `mcp-builder` anti-pattern by the letter, and splitting a detail read out of a list tool is a tool
  count and a gate decision rather than a shape fix. Recorded rather than done.
- **`report` stays out of the shape catalogue.** Its body is an inline schema on
  `POST …/reports/generate` with no component name, and every pin resolves a named one; extracting it
  is a contract change whose only driver is that sentence.
- **This slice is built and NOT verified.** Two queue rows were written and neither was run: `§254
  RC-M1`/`RC-M2` in the mutation queue for the backend field, and `§254 H-9` in the browser queue for
  the guide's two lists and the new global flags. §253's own note stands — `tcms_shape` has still
  never answered a real MCP host, and neither has `structuredContent`.
