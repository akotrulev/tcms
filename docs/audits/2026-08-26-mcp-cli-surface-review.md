# MCP and CLI surface review — `@tcms/mcp`, `@tcms/cli`, `@tcms/client`

**Date:** 2026-08-26 · **Baseline commit:** `ecaf4c99` (clean tree) · **Written by:** `tooling-engineer`
**Method:** read-only. No test suite, gradle or npm gate was run — per CLAUDE.md's standing directive
gates run on `shanks` in one batched pass. Test *sources* are cited as evidence of what is and is
not asserted, never as evidence that they pass.

**Skills applied:** `tooling/_shared/capability-contract/SKILL.md` + `references/output-contract.md` ·
`tooling/skills/mcp-builder/SKILL.md` · `tooling/skills/cli-builder/SKILL.md` ·
`engineering-practices/agent-orientation-cache` · `engineering-practices/code-comments`.

**Orientation:** full — `docs/agent-context/` carries no `tooling-engineer.md`. Recovered instead
from `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` (the contract document, `RESUME`-grade
and unusually complete), `docs/handoff/140`, `docs/handoff/253`, `docs/specs/client-distribution.md`
and the three packages. Installed SDK re-probed: `@modelcontextprotocol/sdk` **1.30.0**
(`packages/package-lock.json:98`) — both `outputSchema` and `structuredContent` are available in
that version, which finding **A2** turns on.

---

## Verdict, in one paragraph

This is a **strong** surface and materially better than most. The contract document exists and is
authoritative; the tool count is an argued outcome rather than an RPC mirror of 128 REST paths;
there is no `execute(command)` tool, no `BaseTool` hierarchy, no registry abstraction
(`packages/mcp/src/tools.ts` is 29 lines); the per-session server is the right call and its
reasoning (`packages/mcp/src/session.ts:1-37`) is the best single argument in the repository;
`writeConformance.test.ts` genuinely covers **every** declared arm on both surfaces and fails rather
than skips on one it does not recognise. What is wrong is concentrated in four places: **one
authentication hole on the HTTP transport**, **no machine-readable output envelope on either
surface**, **the core's refusal vocabulary reaching only one of the two adapters**, and **the shape
catalogue being wired into one of its eight entities**, which moves the fifth-copy risk rather than
removing it.

Findings are ranked. Confirmed defects (**A**) are separated from design opinions (**B**). Section
**C** answers the six questions the review was scoped on directly, including the ones where the
answer is "this is fine".

---

## A. Confirmed defects

### A1 — MAJOR (security). The HTTP transport authenticates `initialize` and nothing after it

`packages/mcp/src/http.ts:78-88`:

```ts
const sessionId = header(req, 'mcp-session-id');
const existing = sessionId ? sessions.get(sessionId) : undefined;

if (existing) {
  const body = await bodyOf(req, res);
  if (res.headersSent) return;
  await existing.transport.handleRequest(req, res, body);
  return;
}
```

The bearer is read exactly once, on the initialize path (`packages/mcp/src/http.ts:103-104`), and is
never compared again. The session is **not bound to the credential that opened it**. Consequences,
all reachable:

- A request carrying a valid `mcp-session-id` and **no** `Authorization` header at all drives that
  session — every tool, as the original user, with their grants.
- A request carrying a *different* token and that session id is served by the first token's client.
- An unauthenticated `DELETE` with that session id terminates a live session (it falls into the
  `existing` branch and reaches `transport.handleRequest`, never the `req.method !== 'POST'` guard
  below it).

The mitigating fact is that the id is a `randomUUID` (`packages/mcp/src/http.ts:121`). The
aggravating one is that it is a **header on every request and response**, and this deployment
deliberately puts Caddy in front of the process (`packages/mcp/src/http.ts:8-15`,
`docs/specs/client-distribution.md`) — proxy access logs, host-side debug output and crash reports
are all ordinary places for it to land. A session id is a routing handle, not a credential.

The file's own doc comment reasons at length about credential handling
(`packages/mcp/src/http.ts:17-23`: *"it builds a client with it and asks TCMS who it is"*) and does
not mention that the asking happens once. `packages/mcp/src/http.test.ts:100-153` tests the
credential on the initialize path only; there is no case for "a live session, a different bearer".

**Fix:** carry the token (or a hash of it) on `Live`, and on every subsequent request compare it to
`bearer(req)`; `unauthorized(res)` on absence or mismatch. Three lines, one test.

### A2 — MAJOR. No output envelope, and no machine-readable error, on either surface

`packages/mcp/src/result.ts:35-37`:

```ts
export function ok(value: unknown): ToolResult {
  return { content: [{ type: 'text', text: JSON.stringify(value, null, 2) }] };
}
```

- `ToolResult` **declares** `structuredContent` at `packages/mcp/src/result.ts:23` and nothing in
  the package ever assigns it (grep across `packages/`: one hit, the declaration). It is dead.
- No tool declares an `outputSchema`; `describe()` at `packages/mcp/src/server.ts:180-187` emits
  `name`, `title`, `description`, `inputSchema`, `annotations`, `_meta` and nothing else.
- Failures (`packages/mcp/src/result.ts:61-90`) are **prose** in `content[0].text` with
  `isError: true`. There is no `code`, no `retryable`, no `field` path.

`references/output-contract.md` §1-2 asks for one envelope with an `ok` branch and a closed error
vocabulary whose `retryable` is *stated, not inferred* — because that field is the single thing that
keeps an agent out of a retry loop.

The information exists and is discarded at the boundary. `packages/client/src/core/refusal.ts:74-142`
already branches on 403 / 404 / 401 / 429 / default and knows the difference; the 429 branch even
computes the wait from `Retry-After` (`packages/client/src/core/refusal.ts:127-130`) and then
renders it into a sentence. `packages/client/src/errors.ts:143-147` exports `isTransient()`, and
`:127` exports `retryAfterMs()`. Both are unused by both adapters. So today a model distinguishing
"wait 30 seconds and retry" from "stop, this will never work" has to regex an English sentence that
`describeRefusal` composed from a branch it already took.

**This is the cheapest high-value fix in the review**, and it is additive: keep the prose exactly as
it is in `content[0].text` — it is good prose — and add
`structuredContent: { ok: false, code, retryable, retryAfterSeconds?, field? }` beside it. SDK 1.30
supports it. The same object is what `--json` should emit on the CLI side (see **A3**/**A4**).

Note the one place the surface got this right and should be the model for the rest:
`fieldDetail()` (`packages/mcp/src/result.ts:107-114`) appends the API's per-field rejection
messages, added because *"a model is told 'Check the highlighted fields.' and nothing else"*. That
is the correct instinct applied to one status code; the finding is that it was not generalised.

### A3 — MAJOR. The CLI collapses every server outcome into exit code 1

`packages/cli/src/main.ts:35`:

```ts
export const EXIT = { ok: 0, failed: 1, usage: 2, cancelled: 130 } as const;
```

`report()` (`packages/cli/src/main.ts:143-172`) returns `EXIT.failed` for a `TcmsApiError` — 403,
404, 409, 429 and every 5xx alike — and again for `TcmsTimeoutError` and `TcmsClientError`. The
skill's exit-code table (`cli-builder` Step 4) exists precisely to prevent this: a CI step or an
agent must be able to branch **without parsing output**. Today a pipeline cannot tell "rate limited,
back off" from "this token will never be allowed to do this", so it will either retry the
unretryable or give up on the transient.

`--help` (`packages/cli/src/main.ts:174-207`) documents no exit codes at all, so even the four that
do exist are undiscoverable. The doc comment at `:29-34` argues carefully for keeping `2` apart from
`1` and then stops one step short of the table that argument implies.

The inputs are already in the core: `isTransient()` and `retryAfterMs()`
(`packages/client/src/errors.ts:127-147`).

### A4 — MAJOR. The CLI never calls the shared refusal copy, contradicting the surface spec's own §6

Surface spec §6 (`docs/specs/2026-08-08-mcp-tool-and-command-surface.md`): *"Refusal copy, keys,
idempotency and `If-Match` all come from the core, so a CLI command and its MCP counterpart cannot
disagree about any of them."*

They disagree. `describeRefusal` is exported at `packages/client/src/index.ts:81` and imported by
**one** consumer, `packages/mcp/src/result.ts:18`. The CLI prints the server's raw `detail` instead
(`packages/cli/src/main.ts:153`). So for the same 403 with the same credential:

| | What the caller is told |
|---|---|
| MCP | *"Cannot edit this case: this token's role does not hold `case:edit`. Ask a project administrator for a role that does…"* |
| CLI | *"You do not have permission to do this."* — the fixed API body, naming nothing |

And for a 429 the MCP relays the `Retry-After` and says nothing was changed
(`packages/client/src/core/refusal.ts:107-136`); the CLI prints whatever `detail` happens to say.

This is not a security problem — the CLI says *less*, which is the safe direction, and §8.4's check
(the 404 branch naming no role) holds a fortiori. It is a **capability** problem: the CLI is the
surface a person uses to find out what to ask an administrator for, and it is the one that does not
say. The core was built for exactly this and one adapter does not call it.

### A5 — MAJOR. `tcms_review_verdict` names the wrong permission in every non-case refusal

`packages/mcp/src/writes.ts:1094-1121` — `openReview` resolves the subject from its display-key
prefix and returns the **subject's own** review key:

```ts
async function openReview(session: Session, key: string): Promise<{ id: string; subject: string }> {
  ...
  return { id: open.id, subject: subject.permission };
}
```

Nothing reads that `subject` field. Grep across `packages/mcp/src` finds no consumer. Both arms
hard-code the case key (`packages/mcp/src/writes.ts:1173-1174`):

```ts
{ action: 'verdict', kind: 'state', permission: 'case:review' },
{ action: 'resolve', kind: 'state', permission: 'case:review' },
```

and `packages/mcp/src/server.ts:319` feeds `arm.permission` straight into the refusal copy. So a
caller refused on `TP-1` is told to go and ask an administrator for **`case:review`** — the wrong
key, for the wrong artifact.

This is precisely the failure `Attempt.permission`'s own doc comment exists to prevent
(`packages/mcp/src/result.ts:42-49`: *"naming the tool's first gate key instead would send the user
to an administrator asking for the wrong thing"*), reproduced one level down: the arm is right and
the *subject within the arm* is not. `packages/mcp/src/server.test.ts:358` asserts the arm-level
rule and does not reach this case.

The tool is also the surface's one `mode: 'any'` gate (`packages/mcp/src/writes.ts:1171`), so it is
exactly the tool most likely to be listed for a caller holding one of the three keys and refused on
the other two.

**Fix:** the value is already computed and returned. Thread it — either let `run` throw a typed
error carrying it, or let `armFor` be given the resolved subject.

### A6 — MAJOR. Four different result shapes and two different has-more keys across the read tools

| Tool | Shape returned | "more" signalled by |
|---|---|---|
| `tcms_search_cases` (`reads.ts:167-190`) | `{ cases[], page, size, total, more }` | `more` |
| `tcms_list_suites` — list (`reads.ts:270`) | `{ items[], page, size, total, truncated }` | `truncated` |
| `tcms_list_suites` — one suite (`reads.ts:263-267`) | **the raw upstream `Suite`** | — |
| `tcms_list_library` (`reads.ts:311`) | `{ conditions: { items[], page, size, total, truncated } }` | nested one level deeper |
| `tcms_list_artifacts` / `doc_tree` / `list_plans` / `open_run` | `window()` at top level | `truncated` |
| `tcms_orient` (`reads.ts:78-104`) | bespoke object, `tagsTruncated` for one field | `tagsTruncated` |

`references/output-contract.md` §3: *"Pick one style for the whole surface. A mixed surface is a
permanent tax on every caller."* Every list this model pages costs it a shape it has to re-learn,
and the `more`/`truncated` split means the loop condition is not the same field twice in a row.

`window()` (`packages/mcp/src/reads.ts:32-43`) is the right helper and is applied inconsistently:
`tcms_list_library` wraps it in an extra key for no stated reason, and `tcms_search_cases` reimplements
its arithmetic under different names. The single-suite branch returning the raw upstream payload is
the `mcp-builder` anti-pattern *"Returning the raw upstream JSON"* verbatim.

The `truncated` naming is defended in the spec (§4.6 — *"implying a next page the endpoint does not
have would be worse"*) and that argument is good; it argues for `hasMore: false, truncated: true`,
not for two vocabularies.

### A7 — MEDIUM. The shape catalogue is wired into one of its eight entities

`packages/client/src/core/shapes.ts` declares eight entities (`:456` case, `:511` suite, `:571` plan,
`:610` docPage, `:658` run, `:706` result, `:732` workItem, `:750` artifact). Exactly one reaches an
adapter's declaration:

- MCP: `jsonSchemaFor(CASE_SHAPE, …)` at `packages/mcp/src/writes.ts:245` and `:270`. The other ten
  write tools hand-write their `inputSchema` inline.
- CLI: `optionSpecFor(CASE_SHAPE, 'edit')` at `packages/cli/src/writes.ts:231`. The other fifteen
  write commands hand-write their `OptionSpec`.

`shapesContract.test.ts` pins the catalogue to **`openapi.yaml`** and says so in its own header
(*"The pin that keeps `core/shapes.ts` from becoming the fifth copy"*, `:1-19`), asking five
questions of the contract. It asks **no** question of the seven adapters' hand-written schemas. So:

> `tcms_shape` can tell a model one thing about a suite and `tcms_write_suite`'s `inputSchema` can
> declare another, and no check in the repository fires.

§253's own rule is *"hand-written is fine; hand-written **and unpinned** is the defect."* The
catalogue→contract link is pinned. The catalogue→**surface** link — the one a model actually reads —
is not, for seven of eight entities. The fifth copy was not removed; it acquired a ninth sibling
that is authoritative in `tcms_shape` and advisory everywhere else.

This is not an argument against the slice, which found four real defects nothing else had (§253's
own list). It is an argument that the slice is **half wired**, and the unwired half is where a
disagreement is invisible.

**Cheapest closure that does not require rewriting ten tools:** a test that, for each entity with a
declared adapter surface, asserts every catalogue field marked `mcp: true` appears as a property of
the corresponding tool's `inputSchema`, and vice versa. That turns silence into a list, the same way
question four of `shapesContract.test.ts` did.

### A8 — MEDIUM. `case edit`'s flags come from the catalogue and its body does not

`packages/cli/src/writes.ts:231` spreads `optionSpecFor(CASE_SHAPE, 'edit')` into `options`.
`packages/cli/src/writes.ts:253-268` then reads the options back **by hand**, one named key at a
time (`text(options['title'])`, `nullableText(options['objective'])`, …).

The test that looks like it covers this does not:

```ts
// packages/cli/src/format.test.ts:92-96
const expected = { ...optionSpecFor(CASE_SHAPE, 'edit'), file: { type: 'string' } };
expect(command.options).toEqual(expected);
```

That compares the spread to itself. Consequence: a field added to `CASE_FIELDS` produces a `--flag`
that `parseArgs` accepts in strict mode, and a body builder that silently ignores it. The command
reports success having sent nothing for that field, and `requireSomethingToDo`
(`packages/cli/src/context.ts:211-217`) will not catch it whenever any other flag was also given.

That is §137/§139's silently-dropped-flag defect — the one whose fix motivated declaring
`OptionSpec` per command in the first place (`packages/cli/src/context.ts:56-64`) — with a new door
cut into it by the very slice that was closing the old one.

### A9 — MEDIUM. Surface spec §8's tests 1 and 2 were specified and do not exist

§8 asks for five computed checks. Three exist and are good (§8.3 header conformance, §8.4 refusal
copy, §8.5 the shape/contract pin). Two do not:

- **§8.1 gate conformance** — *"assert its declared permission set equals the permission set the
  wrapped endpoints actually enforce, derived from the code rather than from a list in this
  document."* Nothing in `packages/` or `backend/src/test` does this.
- **§8.2 the merge rule from the catalogue** — *"for every multi-arm tool, assert that no role in
  `role_permission` holds a strict subset of its arms' permissions."* The nearest thing,
  `packages/mcp/src/writes.test.ts:632`, asserts only that each arm's permission is inside **its own
  tool's** gate. That is self-consistency; the rule is about the role catalogue.

Eight tools gate `mode: 'all'` over two or three keys — `packages/mcp/src/writes.ts:230, 448, 587,
745, 870, 993, 1249, 1313, 1498`. I checked the current catalogue by hand
(`docs/specs/2026-07-30-tcms-roles-permissions-naming.md:141-241`): today every one of them is a
plain `✓` for Test Engineer and up, Viewer contributes nothing, and Automation Service is
non-assignable — so **every merge is currently legal** and this is a latent risk, not a live bug.

What makes it worth a finding is that the instrument exists and is unused.
`packages/client/src/test/roleMatrix.ts:206` `roleMatrix()` parses §A3's tables into
`Record<Role, Set<string>>`, and its only consumers are `matrixCopies.test.ts` and
`tierClaims.test.ts`. The §8.2 check is a dozen lines on top of a parser that is already written and
already trusted. Without it, a future migration that narrows one key removes a **whole tool** from
`tools/list` for every contributor — the exact failure §2's rule was written to prevent — and the
build stays green.

### A10 — MEDIUM. The CLI has no consent gate, no `--yes`, and no `--dry-run`

`packages/cli/src/writes.ts:764-784` — `doc unlink` declares `write: { kind: 'delete', permission:
'doc:edit' }` and removes the link with no prompt and no confirmation flag. There is no `--dry-run`
on any write command; `GLOBAL_OPTIONS` (`packages/cli/src/main.ts:37-43`) has neither.

The asymmetry with the MCP is stark and is worth stating precisely, because the MCP half is the
strong one. `withheldConsent` (`packages/mcp/src/server.ts:225-278`) **refuses outright** when the
client declares no elicitation capability — a genuinely good decision, correctly argued at
`:208-213` (*"a consent gate that silently degrades to 'no prompt available, carry on' is not a
gate"*). The same credential, over the same core, deletes silently from a terminal.

The surface spec is honest that the MCP gate is not a boundary on the credential
(`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` §3.3: *"Neither reaches raw REST"*), so this
is not a hole in the security model. It is a hole in the **CLI's** obligations: `cli-builder` Step 2
and Step 5 ask for an interactive confirmation bypassed by `--yes`, and `--dry-run` on every `write`
and `destructive` command. The CLI has one delete arm today and would need the machinery anyway
before the deletes §253 lists as un-built (`case delete`, `suite delete`, `plan delete`) land.

### A11 — MEDIUM. Enums are enumerated on the write side and left as prose on the read side

`packages/mcp/src/reads.ts:130-136`:

```ts
lifecycleStatus: { type: 'string', description: 'DRAFT, IN_REVIEW, APPROVED, DEPRECATED.' },
automationStatus: { type: 'string' },
priority: { type: 'string' },
healthSignal: { type: 'string', description: 'One of the repository health signals — see tcms_triage.' },
sort: { type: 'string' },
```

Also `packages/mcp/src/reads.ts:300` (`type: 'PRE or POST'` in prose) and `:333` (`scope: 'PROJECT
or PLAN'` in prose).

This is the defect §203 fixed on the write side and did not sweep. Its own comment records the shape
(`packages/mcp/src/writes.ts:1187-1198`): the `verdict` property told every caller to send
`APPROVED`, a member `ReviewParticipationInput` does not have, and *"the description was the whole of
the contract the caller had"*. The fix was stated as a rule — *"an MCP host validates the input
schema before the call, so a wrong member is now refused here rather than at the server"* — and then
applied to one property.

`automationStatus` and `priority` are worse than the rest, because the exact arrays already exist as
constants twenty files away (`packages/client/src/core/shapes.ts:180-183`) and are contract-pinned
there. `sort` has no stated vocabulary anywhere on the surface.

### A12 — MEDIUM. `tcms_report` is reachable only by guessing, and its output cannot be fetched

Three separate gaps compounding in one tool:

1. `parameters: { type: 'object', description: "The report's own parameters." }`
   (`packages/mcp/src/writes.ts:1540`) — an untyped object with no properties. The contract does
   declare it untyped and says why (`docs/specs/openapi.yaml:5720-5722`), so this mirrors a
   contract decision rather than inventing one.
2. **The `type` catalogue is discoverable nowhere on this surface.** `type` is deliberately an open
   string in the contract (`docs/specs/openapi.yaml:5729-5738`) and *"a type this server cannot
   compute is a 400 naming what it can"* — the server will list them, but only after a refusal.
   `GET …/reports/capabilities` answers **formats only** (`docs/specs/openapi.yaml:5907-5931`), and
   the tool's comment (`packages/mcp/src/writes.ts:1531-1535`) declines to wrap it as *"a tool for a
   sentence"* — correct about capabilities, and it leaves the type question unanswered.
3. `definitionId` (`packages/mcp/src/writes.ts:1539`) takes the id of a saved report definition, and
   **no tool or command lists definitions**. It is an argument nothing on the surface can produce —
   the same shape the spec itself excludes reviewer assignment for
   (`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` §3, amendment table).

Add the tool's own admission that the artifact cannot be retrieved (*"fetching it needs the same
credential and is not something a tool call can hand you"*, `packages/mcp/src/writes.ts:1494-1496`)
and `tcms_report` is a `report:export`-gated capability an agent can neither address correctly nor
read the result of. Honest, and still a dead end. The cheapest repair is one of: enumerate the three
registered types in the description, or fold a `types` field into `tcms_orient`'s answer.

### A13 — MEDIUM. An agent can file a proposal and can never learn its fate

`tcms_write_case`'s `propose` arm is the flagship write of the whole surface — the one create that
exists, deliberately routed through review (§5 of the surface spec). The server instructions tell the
model so (`packages/mcp/src/instructions.ts:97-100`: *"A generated case is filed as a proposal for a
person to accept — it never becomes a test case on its own"*).

`GET …/ai-proposals` (the review queue, `docs/specs/openapi.yaml:2555`) and
`GET …/ai-proposals/{proposalId}` (`:2651`) are on **neither** surface. There is no
`tcms_list_proposals` and no `tcms proposal list`. So the agent writes into a queue it cannot read,
cannot report to its user whether yesterday's twelve proposals were accepted or rejected, and cannot
learn from a rejection.

This is the clearest example of the review's question 6 — *anything exposed that is not usable end to
end*. Unlike the two exclusions the spec argues (reviewer assignment, evidence upload), this one is
neither argued nor recorded anywhere; it is an omission.

### A14 — MINOR. A wrong comment about the parser mode

`packages/cli/src/context.ts:213`:

> *"The CLI parses non-strictly on purpose (`main.ts` says why), which makes this the place that
> notices."*

`packages/cli/src/main.ts:76` is `strict: true`, and the comment directly above it
(`packages/cli/src/main.ts:66-74`) records strictness as the §139 **correction** of exactly the
opposite choice: *"`strict: false` was chosen so a command's flags would parse … and that is not
what it does."* So the comment states the reverse of the code and cites the file that refutes it.

Per `ep-code-comments` a comment that is **wrong** grades **major** as a comment finding; the
surrounding code is correct, so the fix is a deletion, not a rewrite. `requireSomethingToDo` earns
its doc comment on the empty-PATCH argument alone.

This is the only comment defect found. The comment discipline across all three packages is otherwise
unusually good — no inline step markers, no commented-out code, no `TODO`s, and the doc comments
carry genuine invariants and reversals rather than restating signatures. Several are longer than
this skill would normally allow and each of those is earning it.

### A15 — MINOR. The shipped user guide is one slice stale, and nothing pins it

`frontend/src/features/help/sections.tsx`:

- `:644-654` lists the CLI read commands and omits **`tcms format`**.
- `:783` lists the MCP read tools and `:788` the writes; **`tcms_shape` appears in neither**.

Both carriers shipped in §253 (`ecaf4c99`, `4d8601f2`, `2b2d7f70`) and the guide was not updated. The
consequence is small — a user does not learn about the two tools built specifically to stop an agent
guessing a format — and the pattern is the one CLAUDE.md names: *a catalogue-wide invariant needs a
computed check, not a careful reading.* This repo already has the instrument shape for it
(`packages/client/src/test/matrixCopies.test.ts` compares two prose tables against a spec, cell by
cell, both ways). The tool list is a shorter version of the same problem.

### A16 — MINOR. `tcms_shape` returns a failure as a success

`packages/mcp/src/shape.ts:85-92` returns `{ error: "No entity named '…'.", entities: [...] }`
through the normal `ok()` path, so `isError` is absent and the host renders a failure as data. The
`enum` on the input (`:50`) means a validating host will refuse it first, which is why this is minor
— but the server is explicitly written not to trust a peer's validation elsewhere
(`packages/mcp/src/input.ts:4-7`: *"a server that trusts a client's validation is trusting a peer it
did not write"*), and this is the one place it does.

### A17 — MINOR. The catalogue does not cover three of the eleven write tools

`SHAPES` has eight entities. `tcms_review_comment`, `tcms_review_verdict` and `tcms_report` have no
entry, so `tcms_shape` answers *"No entity named 'report'"* / *"'review'"* for three write tools —
including the one (**A12**) whose shape is hardest to guess. `tcms_shape`'s description
(`packages/mcp/src/shape.ts:38`) enumerates the eight and so does not *claim* coverage, which is why
this is minor rather than a false statement.

### A18 — MINOR. HTTP sessions are never expired or bounded

`packages/mcp/src/http.ts:66` — `sessions` is pruned only by `onsessionclosed` and
`transport.onclose` (`:123-127`). A host that vanishes without a `DELETE` leaves a live `Server`, a
`Reach` and a token-bound client resident for the lifetime of the process. On the box that is also
running Postgres (the note at `packages/mcp/src/http.ts:44-46` makes the same argument for
`MAX_BODY_BYTES`), an idle-session sweep is the same class of limit.

### A19 — MINOR. Missing CLI global flags

`packages/cli/src/main.ts:37-43` declares `project`, `json`, `url`, `timeout`, `help`. Absent:
`--version` (there is a `CLI_VERSION` at `packages/cli/src/config.ts:21` with no way to print it),
`--quiet`, `--verbose`, `--yes` (see **A10**). `cli-builder` Step 2 names these as the root set that
should be inherited by every command.

---

## B. Design opinions — recorded, not defects

These are places where I would have chosen differently or where the reasoning deserves to be
restated, not places where something is wrong.

- **The 24-tool workflow shape is right.** 128 REST paths (`docs/specs/openapi.yaml`) against 24
  tools, with the count derived from §2's one-permission-per-write-path rule rather than targeted, is
  the correct answer and is argued better than most contract documents manage. The `action`-enum arm
  pattern keeps the count down without becoming an `execute(command: string)` tool: every arm is a
  named enum member with its own typed properties and its own declared permission
  (`packages/mcp/src/writes.ts:231-235` is the canonical example). This passes the
  capability-contract skill's central test cleanly.

- **Simplicity: full marks.** `packages/mcp/src/tools.ts` is 29 lines and the registry is a literal
  array. There is no `BaseTool`, no factory, no plugin loader, no command registry framework. The
  `ToolDefinition` / `Command` pair (`packages/mcp/src/tool.ts:89-123`,
  `packages/cli/src/context.ts:68-90`) are data shapes that exist so the conformance tests can walk
  them — an abstraction that is *earned by a check*, which is the right reason to have one.

- **Per-session server construction is correct.** `packages/mcp/src/session.ts:1-37` argues it from
  the SDK's own constraint (`instructions` is a constructor argument, sent once, never re-sent) to a
  cross-tenant disclosure. That is the strongest single argument in the packages and I would not
  change it.

- **`tcms_shape` separate from `tcms_orient`** is right for the stated reason: orient opens with
  `session.projectId()` and the caller who most needs the format has not resolved a project yet
  (`packages/mcp/src/shape.ts:16-20`). The division of labour — orient owns the live vocabulary,
  shape owns the shape — is stated in both descriptions and is the correct cut.

- **Resources without `listChanged`, and unfiltered by permission**
  (`packages/mcp/src/server.ts:53-63`, `:94-100`). Both are argued and both are right. The second in
  particular: withholding a format document tells a caller nothing except that it must guess.

- **The CLI-as-subset asymmetry is a legitimate open decision**, and §253 already declares and checks
  it (`ActionSpec.cli` / `.mcp`, printed by `tcms format`). I would leave it. What I would *not*
  leave is that surface spec §6 claims the nouns match — *"`case`, `suite`, `plan`, `run`, `doc`,
  `review`, `work-item`, `report`, `triage`"* — and three of those nouns (`work-item`, `report`,
  `triage`) have **no CLI command at all**. That is a gap in the spec's own claim rather than in the
  granularity choice; the spec sentence should be struck and re-written, per the working agreement.

- **`--json` printing the response verbatim** (`packages/cli/src/output.ts:1-9`) is defended as
  avoiding *"a second schema, undocumented, that `jq` scripts would come to depend on"*. That
  argument is good and it is in tension with **A2**/**A3**: on failure, `--json` puts **nothing** on
  stdout and the error goes to stderr as prose, so an agent capturing `--json` sees an empty stdout
  and an ambiguous exit 1. A middle path exists — keep the success body verbatim under `data`, and
  emit the error envelope on failure — and it is a decision, not a defect. Worth taking explicitly
  rather than by default.

- **`tcms format` / `whoami` / `help` break `<bin> <noun> <verb>`.** Harmless, conventional, and I
  would keep them.

- **`tcms_orient`'s five-way `Promise.all`** (`packages/mcp/src/reads.ts:70-76`) fails whole on any
  one read, argued as *"a partial shape is worse than none"*. Correct, and worth noting that it means
  a caller whose token lacks one of the five constituent reads loses orient entirely rather than
  getting four fifths — which the `project:read` gate does not predict. Not a defect today; a thing
  to remember if any of the five ever grows a separate key.

---

## C. The six questions, answered directly

### 1. Tool/command surface design

**Agent-legible, not an RPC mirror.** 24 tools over 128 paths, cut by an argued rule, with
descriptions that route (three-line shape, naming the sibling they are confused with — e.g.
`packages/mcp/src/reads.ts:115-120` pointing at `tcms_open_case`, `packages/mcp/src/writes.ts:1127-1131`
pointing at `tcms_review_verdict`). Every description is inside the 2 KB host ceiling and a test
enforces it (`packages/mcp/src/writes.test.ts:698`). No flow is encoded as a mega-tool, per the
skill's Step 6 — flows live in descriptions and in the guide.

**Naming:** `tcms_<noun>_<verb>`, enforced by regex (`packages/mcp/src/writes.test.ts:686`). CLI is
`<noun> <verb>` and the nouns match — with the three-noun gap noted in **B**.

**The shape catalogue:** it removes real duplication for `case` and it has **not** become the fifth
copy *against the contract* — `shapesContract.test.ts` is a genuine, five-question, mostly-both-ways
pin, and §253 records that it was believed only after being broken three ways deliberately. But it is
wired into one of eight entities and nothing pins it to the seven adapters' hand-written schemas
(**A7**). Verdict: **half-landed** — a real improvement whose second half is where a disagreement
would be invisible.

### 2. Typed inputs and the output envelope

**Inputs:** good and improving. Every tool schema sets `additionalProperties: false` and a test
enforces it (`packages/mcp/src/writes.test.ts:739`). Every value is read through a coercing-nothing
reader (`packages/mcp/src/input.ts`), which is the right instinct. `nullableStr`/`nullableNum`
(`:60`, `:77`) keep absent and null apart, which most surfaces get wrong. Write-side enums are
enumerated. **Read-side enums are not** (**A11**), and `tcms_report.parameters` is an untyped object
(**A12**).

**Envelope:** absent. One shape, one `ok` branch, `structuredContent`, `outputSchema`, a closed error
code vocabulary, `retryable` — none of it exists on the MCP side (**A2**), the CLI has no envelope at
all and no exit-code table (**A3**), and the read tools disagree with each other about paging shape
(**A6**). **This is the largest single gap in the review**, and the fix is additive rather than a
rewrite: the error vocabulary is already computed inside `describeRefusal` and thrown away.

**Are failures actionable?** In prose, often yes and sometimes excellently — `fieldDetail`
(`packages/mcp/src/result.ts:107-114`), the timed-out-write copy (`:75-85`), the 429 copy
(`packages/client/src/core/refusal.ts:107-136`) all tell a model what to do next. In a form a model
can **branch** on without regexing English, no.

### 3. Capability/permission handling

The strongest part of the surface, with one bug.

- The per-session capability summary is real, budget-managed in **bytes not characters**
  (`packages/mcp/src/instructions.ts:39-47`), and degrades by dropping whole sections in a fixed
  order rather than truncating mid-key (`:156-172`). The "unavailable" section has a shortened
  fallback specifically because a credential holding almost nothing is the caller who most needs it
  and for whom it is longest (`:73-90`) — that is the kind of reasoning that only comes from having
  been bitten, and the comment says which audit bit it.
- The summary is **derived from the registry**, not written out (`:55-58`), so it and `tools/list`
  cannot disagree.
- `tools/list` is permission-filtered, an unlisted tool refuses without confirming it exists
  (`packages/mcp/src/server.ts:127-138`) — correctly refusing to become an enumeration oracle.
- The qualifier half is handled: a narrowly-held key is **listed with its narrowing composed into the
  description** rather than hidden (`packages/mcp/src/tool.ts:186-210`), which is the right call and
  is the surface spec's own amendment to §2.
- Refusal copy respects §4.5's 404-vs-403 boundary in one place
  (`packages/client/src/core/refusal.ts`) and `packages/mcp/src/server.test.ts:441` checks the silent
  branch.

**Do the descriptions overstate what a caller can do?** No — and there is a computed check for
exactly that claim (`packages/mcp/src/writes.test.ts:706-737`), which reads the roles spec §A3
through `roleMatrix` rather than a local constant, and whose failure message is written for the
person who will trip it. That check is the best single test in the packages.

**The bug:** **A5** — the per-subject review permission is computed, returned, and discarded, so
every non-case refusal on `tcms_review_verdict` names the wrong key.

### 4. Write safety, and does `writeConformance.test.ts` cover every arm?

**Yes — it genuinely does, on both surfaces**, and this was the claim I most expected to fail.

- `packages/mcp/src/writeConformance.test.ts:113-127`: an arm in the registry with no entry in
  `INVOCATIONS` **throws**, with a message explaining why skipping would make it a check on its own
  table. It then drives the arm through a recording `fetch`, resolves the contract operation **from
  the URL actually sent** (`:110-116` of the assertion block) rather than from a column, and checks
  the header **both ways** — a header sent where none is implied fails too (`WATCHED`, `:70`).
- The second question is the one that found something: *does the operation declare that header at
  all* — a create sending `Idempotency-Key` at an endpoint that discards it "looks correct from every
  angle and buys the caller nothing". One live entry remains (`CREATE_WITHOUT_A_KEY`,
  `packages/mcp/src/writeConformance.test.ts:88-98`, `tcms_write_artifact.edit`) with the cost
  written out, and the map is checked for members that stopped needing to be on it
  (`packages/cli/src/writeConformance.test.ts:150-155`).
- `packages/cli/src/writeConformance.test.ts:91-99` does the same for all 16 write commands.

Two limits worth recording rather than fixing:

- The vacuity guards are `>=` (`>= 25` arms, `>= 14` writes), so a **removed** arm would not be
  noticed. Nothing can be *added* unasserted, which is the direction that matters.
- Only the recorded path is inspected. A handler that writes to a **second** endpoint beyond the one
  its invocation records is unchecked — `tcms_write_case.submit` posting to `/cases/bulk`
  (`packages/mcp/src/writes.ts:347`) is the live instance, and its kind (`state`) implies no headers,
  so nothing is currently missed.

**Consent semantics:** the MCP's gate is genuinely good and refuses rather than degrading
(`packages/mcp/src/server.ts:235-247`); it keys on `ToolArm.kind` so §211's three new arms were
covered with no gate code changed, and there is a test asserting exactly that
(`packages/mcp/src/server.test.ts:271`). The confirmation names the row via declared `targets`, after
a slice where it named nothing (`packages/mcp/src/tool.ts:64-78`). **The CLI has none of this**
(**A10**).

**Idempotency:** correct, and the salt reasoning (`packages/mcp/src/session.ts:148-160`) is right —
the bare JSON-RPC request id was a cross-session collision and the file says exactly how. The
honest limit is also stated: the key deduplicates a *transport* retry and not a *model* retry
(`:136-146`).

**One open question the spec itself raises and I would escalate:** the consent gate is phrased around
deletes, and the surface's most destructive writes are tagged `edit` — every COMPLETE-SET property
discards what the caller omits (`blocks`, `tagRules`, `cases`, `tags`). A read-modify-write that
drops a paragraph arrives through that shape and through no gate. The spec records this as unsettled
(§3.3, closing paragraph). Given that `tcms_write_doc_page`'s `blocks` already **demands** an
explicit `lockVersion` for that exact reason (`packages/mcp/src/writes.ts:29-34`), extending the same
demand to the other three complete-set writes is the cheap half of an answer.

### 5. Auth and transport

**stdio: clean.** `TCMS_TOKEN` from the environment, never a tool argument; the probe happens
**before** the transport connects so a bad credential is a startup failure with a sentence rather
than a tool result (`packages/mcp/src/main.ts:115-123`); nothing is written to stdout and the module
header explains why in terms of what breaks (`:18-23`). No credential appears in any schema on either
surface. `--url` is a flag and the token is not, which is the correct split.

**`--http`: one defect and otherwise sound.** Loopback by default with a stated reason
(`packages/mcp/src/http.ts:37`, `:8-15`), TLS deliberately delegated to the fronting proxy, a body
cap that applies before any credential is checked (`:48`, and a test for it at
`packages/mcp/src/http.test.ts:225`), `WWW-Authenticate: Bearer` on the 401 so a host knows to
present a credential rather than assuming the server is broken (`:189-192`), and a revoked token
indistinguishable from an unknown one by construction (`:138-142`). SIGTERM handling with a bounded
unref'd timer (`packages/mcp/src/main.ts:99-105`).

**The defect is A1** — the bearer is checked on `initialize` and never again, so the session id alone
authorises every subsequent call. Plus **A18**, no session expiry.

**One thing to flag for the operator docs rather than the code:** `docs/specs/client-distribution.md`
tells a user to point a host at `https://tcms.example.com/mcp` and *"send the API token as the
bearer"*. That is correct, and given **A1** the deployment's Caddy access-log configuration should be
checked for whether it records request headers before this is widely used.

### 6. Gaps and dead ends

**Exposed but not usable end to end:**

| | |
|---|---|
| `tcms_report` | type catalogue undiscoverable, `definitionId` unobtainable, artifact unfetchable (**A12**) |
| `tcms_write_case propose` | the proposal queue is on neither surface, so the agent never learns the outcome (**A13**) |

**Needed and on neither surface:**

- `GET …/ai-proposals` and `…/{proposalId}` — the review queue (**A13**). Unargued omission.
- Report definitions (`definitionId`'s source) and the report type catalogue (**A12**).
- The three CLI nouns §6 promises and does not deliver — `work-item`, `report`, `triage` — plus
  `review verdict`, `run clone`, `case/suite/plan delete`, `doc publish`, `case submit`. §253
  declares this asymmetry and checks it (`ActionSpec.cli`), so it is a **recorded** gap, not a
  discovered one; only the spec's own "nouns match exactly" sentence is now false.

**Absent for stated and correct reasons** — I checked each and agree: evidence upload and
`evidence:delete` (multipart, and a delete offered to the one actor that can never create the thing);
reviewer assignment (needs a `userId` this surface cannot obtain, by `V40` §6's deliberate withholding
of `company:read`); `POST …/cases/bulk` (gate decided by the payload, so `tools/list` cannot filter
it); library writes (`library:manage` is gated by an impact modal an API caller never sees); anything
under `/users`, `/teams`, `/roles`, `/api-tokens`; `POST /ingestions`. The `UNOFFERED` list in
`core/shapes.ts` extends the same discipline to individual fields, which is the right instinct.

---

## D. Suggested order of work

Nothing here is a rewrite. In descending value per hour:

1. **A1** — bind the HTTP session to its bearer. Three lines and a test. Do this first.
2. **A5** — thread the resolved subject permission into the refusal. The value is already computed.
3. **A2** — add `structuredContent` beside the existing prose, with `code` / `retryable` /
   `field` from `describeRefusal`'s branch. Additive; nothing existing changes.
4. **A3** + **A4** — the CLI's exit-code table and the CLI calling `describeRefusal`. Same sitting;
   both consume what **A2** exposes.
5. **A6** — one paging shape, one has-more key, across the read tools.
6. **A7** — the catalogue↔adapter pin, as a listing test rather than a rewiring of ten tools.
7. **A11**, **A8**, **A9**, **A10** — enums, the `case edit` body pin, §8.1/§8.2, CLI consent.
8. **A13**, **A12** — the proposal queue and the report dead end. These need a scope decision, not
   just an implementation.
9. **A14**–**A19** — the minors. **A14** (the wrong comment) is a one-line deletion.

## E. Notes on process

- **Verification standing:** §253 records itself as *"Built and NOT verified"* — `tcms_shape` has
  never answered a real MCP host and `resources/list` has never been served to one. That is
  unchanged by this review, which ran nothing. Several findings above (**A1**, **A6**, **A11**) are
  the kind a live pass would have surfaced, which is an argument for the read-path pass §253 says it
  is owed and has no queue for.
- **Orientation cache:** this run's brief permitted exactly one written file, so
  `docs/agent-context/tooling-engineer.md` was **not** created. It should be, on the next run that is
  allowed to write: the seams worth recording are the contract document's path, the `tcms_` prefix,
  the (absent) envelope, the paging split between `window()` and `search_cases`, `TCMS_URL` /
  `TCMS_TOKEN` / `TCMS_PROJECT`, SDK 1.30.0 as `#volatile`, and the two `writeConformance.test.ts`
  entry points.
- **Where a skill and this repo's rules conflict:** none material. The one near-miss is
  `ep-code-comments` versus this repository's house style of long, argument-carrying module headers.
  Those headers are load-bearing here — they record superseded decisions and their reversals, which
  is a working-agreement requirement — and I graded them as earning their place rather than as
  violations. The only comment finding is **A14**, which is wrong rather than merely long.
