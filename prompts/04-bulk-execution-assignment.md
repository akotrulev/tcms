# Slice 4 — Bulk execution assignment

**Lane:** backend + contract + frontend · **Migration:** none · **Depends on:** Slice 3 (and Slice 1 if it landed)

---

## Prompt

```text
Implement **Slice 4 — Bulk execution assignment** from `docs/plans/2026-08-18-unplanned-gaps.md`.

**First act: run `ep-writing-plans` on this slice alone** and save it as
`docs/plans/2026-08-18-gap-4-bulk-execution-assignment.md`. Then execute with
`ep-executing-plans`.

Read first: `docs/agent-context/be-java.md` and `docs/agent-context/fe-react.md`, then the Slice 4
section of the master plan. **`/be-java`** for the backend, **`/fe-react`** for the screen.

**What this slice is:** cases have `POST /projects/{projectId}/cases/bulk` (`openapi.yaml:1685`);
executions have only a per-execution assignee endpoint (`openapi.yaml:3740`). Splitting a 300-case
run across five testers is 300 operations. Add
`POST /projects/{projectId}/runs/{runId}/executions/bulk`, mirroring the cases-bulk request and
response shape.

**Include a round-robin distribute mode.** "Spread these 300 across these 5 people" is the actual
request behind this gap; a bulk endpoint that only sets one assignee for all of them does not
close it.

Frontend: a selection model and a bulk action bar on the run detail screen.

**Handoff:** next free § from `docs/HANDOFF.md`. Orientation caches in this slice's own commit.
```

---

## The two traps this slice turns on

**1. A bulk act writes one audit row per target, tied together by `bulk_operation_id`.**
That column exists on `audit_event` for exactly this. **If Slice 1 has landed, its screen must group
on it in this slice** — otherwise a 300-case assignment floods the audit log with 300 rows and makes
the feature you just shipped unreadable. This is the one cross-slice dependency in the plan; do not
leave it for later.

**2. The idempotency header applies.** A retried bulk assign must not reassign. Every create
endpoint here takes `Idempotency-Key` except one, and `writeConformance.test.ts` asserts that each
operation *declares* the header its kind implies — a mismatch there is how a retried 202 once queued
a report twice.

## Standing rules that bind this slice

- **Tests run on `shanks`** — backend build, frontend suite, packages build.
- **A test proving a partial failure does not half-apply.** That is the acceptance gate. Decide and
  record whether the endpoint is all-or-nothing or reports per-target outcomes — then test the one
  you chose.
- **Regenerate both `schema.d.ts` copies**; `required` arrays on every new schema, nested ones
  included.
- **Disable, don't hide** the bulk action bar when the caller lacks the permission.
- Verification rows into both queues; run nothing (§65).
- Stage only your files; other lanes are live in this tree.
