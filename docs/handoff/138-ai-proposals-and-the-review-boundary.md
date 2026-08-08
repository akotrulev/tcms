# §138 — the review boundary, and the gate no permission can express

**Date:** 2026-08-08 · **Branch:** `master` · **Four phases, landed in three commits** ·
**Migration:** `V43` · **Contract change:** additive (five operations, four schemas) ·
**Plan:** [`docs/plans/2026-08-08-ai-proposals.md`](../plans/2026-08-08-ai-proposals.md)

The MCP WIP plan's **phase 6**. §6w.6 has said since 2026-07-31 that *"nothing generated writes to
`test_case` without a human accepting it"*, and there was no table for a generated case to land in —
so the only shapes a generation tool could take were *write a case* or *do nothing*. §135's tool
surface records the consequence exactly: `tcms_write_case`'s create arm is **the one blocked arm**,
and it was blocked on this.

---

## What shipped

| Phase | What | Commit |
|---|---|---|
| 1 | `V43` — `ai_proposal`; the entity, three enums, the repository; policy count **62 → 63** | `274913b` |
| 2+3 | Five operations: file, queue, one, **accept**, **reject** — and the accept path through `CaseCreateService` | `d4c5e03` |
| 4 | The review surface: the queue, the decision screen, `VerdictChip` | this commit |

Phases 2 and 3 landed together deliberately: phase 3's code was written while phase 2's gate was
running, so one `./gradlew build` covers exactly the committed state. A phase commit whose gate was
not run over its own tree is the thing the working agreement exists to prevent, and splitting it
after the fact would have produced one.

---

## The finding: a credential may not decide, and that is not a permission

The obvious gate on accept is `case:create`. It is **not sufficient**, and the reason is worth the
paragraph:

**The `Agent` role holds `case:create`.** The WIP plan §2.2 put it there on purpose — *"because the
accept path needs it"* — so an agent token can file a proposal and, with a permission-only gate,
accept it in the next call. The review surface defeated by the thing it exists to review.

**No permission fixes it.** A token's effective set is `evaluate(owner) ∩ permissionsOf(role) ∩
[project]`, so **every permission a token holds is one its owner holds**. Permissions cannot
distinguish Ana from Ana's agent; that is the entire design of the intersection (Decision C). The
only fact separating them is *that a token is in play*.

**And that fact became readable outside `identity` exactly one slice ago.** §132 moved
`TokenContext` to `tenancy` so `AuditEvent` could attribute to it. `TokenContext`'s own header names
the condition under which reading it stops being a mere bound — *"the moment anything reads this
context to grant rather than to bound"* — and this is the **third** thing to meet it, from the
direction the sentence did not anticipate: reading it to **refuse**.

So both decisions are gated on `case:create` **and** `TokenContext.currentOrNull() == null`, with
their own outcome and their own copy. Telling an agent it lacks a permission would be *false* — its
owner holds it — and would send whoever is debugging that agent to widen a grant that changes
nothing.

The check runs **before** the proposal is looked up. After it, an agent learns which proposal ids
exist from the difference between 404 and 403, which is §6t's enumeration oracle arriving through the
one endpoint that refuses for an unrelated reason. Nothing tests that ordering; `AP-10` is the row.

---

## Three more things the code found

### 1. The `suite_id` foreign key had to go — written, tested, removed

`V43` shipped with `suite_id UUID REFERENCES suite(id)`, which looked obviously right and failed the
slice's own test. An FK answers *"is this suite real"* at **file** time, as a
`DataIntegrityViolationException` — and that is precisely the question **accept** exists to answer,
a week later, as field errors on the suite picker. With it, an agent that hallucinated one id loses
its whole batch to a 500; without it, the proposal lands and a reviewer reads it and rejects it.

This is the schema meeting the endpoint's central decision: **the payload is stored unvalidated**.
Validating at file time as well means answering the same question twice, days apart, against a
project whose suites and vocabularies have moved — and only the accept-time answer creates anything.
`AP-4` and `AP-5` are the rows; the migration carries the long form.

### 2. Two enum values are absent, at authoring time rather than four milestones later

`V39` (#50) narrowed `attachment.owner_type` to the values that have writers. The same rule applied
here from the start:

- **`verdict_source` has no `TCMS_VERIFIED`.** It means TCMS ran the isolated critic itself, and
  TCMS calls no model in this milestone. Seeding the value makes the headline coverage number
  inflatable by whichever caller first guesses the string, which is what §6w.1a spends its length
  preventing. It is the **second** barrier: the first is that `AiProposalInput` has no
  `verdictSource` field at all, and the column is derived from whether a verdict was claimed.
- **`status` has no `SUPERSEDED`.** Nothing displaces a proposal. A status no code can set is a
  filter option that always returns nothing.

### 3. §6w.6's own column list is two columns wrong — the sixth §6w reversal

`job_id` names a table that does not exist and is deferred with the rest of TCMS-calls-a-model.
`criterion_id` names a table that **does not exist and never will**: §135 re-read the documentation
module and found ERD resolved question #18 had already decided a requirement **is** a `doc_block`,
and a FK from `repository` into `documentation` is refused by ArchUnit besides. What a proposal
claims to verify travels in `grounding`.

§135 recorded five §6w reversals. This is the sixth and it has the fifth's shape exactly
(`via = MCP`, written against a table with no `via` column): **§6w specifies against a schema that
was later decided differently, so re-read the module before implementing one of its column lists.**

---

## Smaller notes

- **`MAX_IDEMPOTENCY_KEY` is declared eight times now.** The orientation cache said the eighth edit
  *"is the prompt to re-ask the question"*. Re-asked, refused on `V17`'s argument — which turns on
  what the alternative must **store** (a rendered response, which goes stale) rather than on the
  count. What is new is the caller: **the first of the eight with no screen at all**, so nobody
  notices the duplicate it prevents except the reviewer, one row at a time.
- **Accept is idempotent through the create path's own key** — a synthetic `ai-proposal:<id>` — so
  two reviewers pressing Accept at the same moment produce one case. Nothing tests the race
  (`AP-12`), which is `IK-1`'s gap one level up.
- **`ProposalRejection.note` is required-and-nullable.** `ContractConventionTest` refused the schema
  without a `required` array; the fix is the better shape, because it leaves one wire form instead
  of three (no body / empty body / null field) that the server would have to agree about with every
  client separately.
- **The rejection note is optional where a quarantine reason is mandatory**, and the difference is
  what the row goes on to mean: a quarantine is live debt somebody must clear and cannot without
  knowing why; a rejected proposal is finished, and the note improves a signal rather than
  unblocking a person.
- **The frontend cannot file a proposal**, and that is the feature. Filing is an agent's arm.
- **`VerdictChip` takes both props and cannot render one without the other** — the client's half of
  §6w.1a, enforced by making the honest rendering the only rendering available. No green in the
  palette either: green is what this product uses for something it verified.
- **A red run in phase 4 was the permission gate, not the gate's logic.** `findByTestId` resolves the
  moment a gated control exists, and `PermissionGate` renders it *disabled* while
  `/me/permissions` is in flight — then swaps it for a **different node** when the answer lands, so
  a captured reference stays disabled for ever and the failure reads as `pointer-events: none`.
  `src/test/permissions.ts` records the same trap arriving from the fixture side.

---

## Gates

| Gate | Result |
|---|---|
| `cd backend && ./gradlew build` | **BUILD SUCCESSFUL in 5m28s — 2284 tests across 482 result classes, 0 failures** (+42 tests, +7 classes over §136's 2242/475) |
| `cd frontend && npm run build` | clean, after `npm run gen:api` |
| `npm run lint` | 0 warnings |
| `npm run test:run` | **1203 passed / 1203 across 92 files, in 145 s** — +26 over §136's 1177, and **no cliff in this run**: the five `CaseEditorPage` timeouts §136 saw did not recur. Read it off a run. |
| `cd packages && npm run build/lint/test:run --workspaces` | clean · 0 warnings · 82 passed |

Both `schema.d.ts` copies regenerated and byte-identical.

---

## Verification — nothing here is verified

- **`AP-1` … `AP-14`** in [`backend-mutation-checks.md`](../verification/backend-mutation-checks.md).
  **`AP-1` first** (the derived `verdict_source`) and **`AP-9`** (the credential gate) — the two
  whose failure is a product-integrity finding rather than a defect.
- **`A4-1` … `A4-10`** in [`frontend-browser-checks.md`](../verification/frontend-browser-checks.md),
  in the **authoring** lane, with the three-proposal fixture the charter has to author by `POST`
  with an `Agent` token. `A4-4` is the row that observes the file-time/accept-time asymmetry in a
  browser.
- **`AP-9` has no browser row and cannot have one.** The SPA has no token-authenticated session, so
  the caller the refusal applies to cannot exist there. It is a `curl` row.

**This slice is built and unverified**, like everything below §65.

---

## What is next

The WIP plan's **phase 7 — CLI writes**: edits direct, creates as proposals. Both arms are now
buildable — the create arm was waiting on this table, and the core's write machinery
(`Idempotency-Key`, `If-Match`, the concurrency types) has been built and tested since §137 with
nothing calling it. After that, phase 8 (the MCP server itself), 9 (rate limiting) and 10 (deploy).

**The gap §137 opened is still open and this slice widened it**: no `@tcms/cli` command has been run
against a real TCMS, and now there is a REST surface an agent is meant to reach that nothing has
reached. It needs one live pass — `docker compose up`, `provision-company`, issue an `Agent` token,
file the three proposals `A4`'s fixture describes, and drive the browser rows against them. That
pass would satisfy both queues at once for this slice, which is the first time the two have had a
shared setup.
