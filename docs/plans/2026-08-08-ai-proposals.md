# AI proposals — the boundary that protects the repository

**Date:** 2026-08-08 · **Slice:** §138 · **Milestone:** the MCP WIP plan's **phase 6** ·
**Migration:** `V43` · **Contract change:** additive (five operations, four schemas)

> This is the plan for one slice. The decisions it settles live here; the narrative of what building
> it found lives in `docs/handoff/138-*.md`.

---

## 0. What this is, in one sentence

§6w.6 states the requirement and has since 2026-07-31: **"Nothing generated writes to `test_case`
without a human accepting it."** There was no table for a generated case to land in, so the only
shapes a generation tool could take were *write a case* or *do nothing* — and the MCP tool surface
spec (§135, §5) records the consequence in as many words: **`tcms_write_case`'s create arm is the
one blocked arm, and it is blocked on this.**

The MCP WIP plan §1.3 settled the split on 2026-08-08: **an edit to existing content writes straight
through; a newly generated case lands as a proposal.** That keeps §6w.6's guard exactly where it was
written for without making a one-word correction cost a review round-trip.

## 1. Phases

| # | What | Gate |
|---|---|---|
| 1 | `V43` — the `ai_proposal` table; the entity, three enums, the repository; `SchemaConventionTest` 62 → **63** | `./gradlew build` |
| 2 | The contract and the three read/write endpoints: file a proposal, list the queue, open one | `./gradlew build` + `npm run gen:api` ×2 |
| 3 | **Accept and reject** — accept through `CaseCreateService`, and the gate that says a credential may not decide | `./gradlew build` |
| 4 | The review surface in the SPA | `npm run build` + `lint` + `test:run` |

## 2. The decisions

### 2.1 It is not `test_case_draft`, and the refusal is §1.3's, in writing

§6j's autosave buffer already stores an unsaved case as JSONB and it is the obvious thing to reuse.
Three properties differ and each is load-bearing:

- A draft is keyed on **the case**; a proposal **has no case**, which is what makes it a proposal.
- A draft is **cleared on save**; a proposal's decision is **kept**, because "this profile's
  proposals are rejected four times in five" is a measurable fact and rejections that vanish make it
  unknowable (§6w.6).
- `TestCaseDraft`'s own javadoc **forbids a promote endpoint** — *"a second write path into
  `test_case` that skipped `CaseEdit`'s validation"*. That argument binds here unchanged, and it is
  why accept goes through `CaseCreateService`.

### 2.2 Accept goes through the existing create path — no exceptions clause anywhere

Per §6w.6. The payload is stored **in the `CaseUpdateRequest` shape**, which is what makes this
possible without a translation layer: accept hands the stored body to `CaseCreateService` and gets
the same validator, the same `TC-n` allocation, the same v1 version row and the same audit line. An
accepted proposal is therefore **indistinguishable from a case authored any other way**, so §6g's
review workflow applies to it with nothing added — *there is no path from a model to `Approved`*
because there is no path from a model to a case.

Authorship is the **accepting person**. §6j's guarantee is untouched.

### 2.3 A credential may not decide its own proposal — and this is not expressible as a permission

**The finding of the slice.** The obvious gate on accept is `case:create`, and it is the wrong one:
`Agent` holds `case:create` (WIP plan §2.2 keeps it *because the accept path needs it*), so an
agent token would be able to file a proposal and accept it in two calls — the review surface
defeated by the thing it exists to review.

No permission fixes this, and the reason is structural: **a token's effective set is its owner's set
intersected with its role's**, so every permission a token holds is one its owner holds. Permissions
cannot distinguish *Ana* from *Ana's agent* — that is the whole design of the intersection (§2.2,
Decision C). The only fact that separates them is **that a token is in play**, and that fact became
readable outside `identity` exactly one slice ago: §132 moved `TokenContext` to `tenancy` so
`AuditEvent` could attribute to it.

So the gate is `case:create` **and** `TokenContext.currentOrNull() == null`, with its own refusal
shape and its own copy. This is the second reader of that context for a reason other than the
permission bound, and `TokenContext`'s header already names the condition — *"it stops holding the
moment anything reads this context to grant rather than to bound"* — which reading it **to refuse**
also meets.

Rejecting is gated identically. A token that could clear the queue could hide its own bad proposals.

### 2.4 `verdict_source` is derived by the server and has no request field

§6w.1a is categorical: an agent's asserted verdict is recorded, shown, attributed — and **never
counted as verified**, because TCMS cannot see that agent's critic, its isolation or its prompt.
Two barriers, not one:

1. There is **no request field**. `AiProposal.file` computes `NONE` when nothing was asserted and
   `EXTERNAL` when anything was, in the one statement where the rule is expressible.
2. `V43`'s `CHECK` **omits `TCMS_VERIFIED` entirely**, so the value is unreachable even if a future
   caller path forgets barrier 1.

### 2.5 Two enum values are missing on purpose — `V39`'s rule, applied at authoring time

`V39` (#50) narrowed `attachment.owner_type` to the values that have writers. Same rule here, from
the start rather than four milestones later:

| Absent | Why | When it arrives |
|---|---|---|
| `verdict_source = TCMS_VERIFIED` | TCMS calls no model in this milestone — the plan defers that behind BYO-agent generation on purpose (*"before TCMS ever calls a model itself"*). A value with no writer is a claim waiting for whoever first guesses the string. | With the isolated critic |
| `status = SUPERSEDED` | Nothing displaces anything: proposals are filed one at a time and decided one at a time. A status no code can set is a filter option that always returns nothing. | With the generation job |

### 2.6 Two of §6w.6's own columns are not built — the sixth §6w reversal

§6w.6 sketches `ai_proposal (company_id, project_id, job_id, …)` with a `criterion_id`.

- **`job_id`** — `ai_generation_job` does not exist and is deferred with the rest of TCMS-calls-a-model.
- **`criterion_id`** — **there is no criterion table and there never will be.** §135 re-read the
  documentation module and found ERD resolved question #18 had already decided it: a requirement
  **is** a `doc_block`. A FK from `repository` into `documentation` is refused by ArchUnit besides.
  What a proposal claims to verify travels in `grounding`, where the per-block findings already are.

§135 recorded five §6w reversals; this is the sixth, and it is the same shape as the fifth
(`via = MCP` written against a table with no `via` column): §6w specifies against a schema that was
later decided differently, so **re-read the module before implementing a §6w column list.**

### 2.7 `Idempotency-Key` — the seventh column in the family, and the question re-asked

`V11`/`V14`/`V17`/`V42` put the column on six tables. This is the seventh, so
`IdempotencyKeyCapTest.EXPECTED_DECLARATIONS` moves 7 → 8 — and the orientation cache says that edit
**is the prompt to re-ask whether the generalisation should be taken.** Re-asked, and refused for
`V17`'s reason, which has not weakened: a general `idempotent_request` table must store the
**rendered response**, and a stored response goes stale the moment the row it describes is edited.
The column form re-renders through the same assembler the `GET` uses.

What is new here is that this is the **first one whose caller has no screen at all** — a generation
agent that times out mid-batch retries the batch, and without the column every retry files a second
copy of every proposal it had already filed, which the *reviewer* pays for one duplicate at a time.

### 2.8 No `PATCH` on a proposal

A reviewer who wants changes rejects it, or accepts and then edits the case through the ordinary
path — the path that validates, versions and audits. This is what makes `title` safe to denormalise
out of `payload` for the queue: the payload is write-once, so the copy cannot drift.

## 3. Permissions

| Operation | Permission | Extra gate |
|---|---|---|
| `POST …/ai-proposals` (file) | `case:create` | — (this is the arm an agent is *for*) |
| `GET …/ai-proposals` (queue) | `case:read` | — |
| `GET …/ai-proposals/{id}` | `case:read` | — |
| `POST …/ai-proposals/{id}/accept` | `case:create` | **no token may decide** (§2.3) |
| `POST …/ai-proposals/{id}/reject` | `case:create` | **no token may decide** (§2.3) |

No new permission key. §6w's own rule and this repo's: a key seeded to nobody is a 403 no
administrator can grant their way out of.

## 4. Standing obligations (WIP plan §9)

- **§65** — rows in `docs/verification/backend-mutation-checks.md` for every write here, and rows in
  `docs/verification/frontend-browser-checks.md` for the review surface. Landing without them skips
  the check outright.
- **Contract first** — `openapi.yaml`, then `npm run gen:api` in **both** `frontend/` and
  `packages/client/`; `schemaFreshness.test.ts` fails otherwise.
- **Orientation cache** — `docs/agent-context/be-java.md` and `fe-react.md` in the slice's own commit.
