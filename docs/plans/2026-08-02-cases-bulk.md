# `POST /projects/{projectId}/cases/bulk` — implementation plan

**Date:** 2026-08-02 · **Slice:** the last MSW-only surface with a live consumer (§61's named next rung)
**Migration:** none — every column the eight actions touch already exists. `V20` stays free.

## Why this slice

`BulkActionDialog` has been wired into `CaseListPage` since Phase 1 and calls an endpoint **no
controller serves**. §61's orientation pass named it the obvious next rung: of the four contract
paths with no controller, `/artifacts` (done in §61) and `/cases/bulk` were the two with consumers.

## The three findings that shape it

### 1. `SET_STATUS` cannot be gated on `case:edit`

The roles spec's Review table grants `case:approve` to **Lead and up and explicitly not to Test
Engineer**, and plan §6g says *"the state machine has exactly one gate, held by `*:approve`"* and
*"Only Admin and Test Lead can perform the final transition into APPROVED"*. `case:approve` is
seeded in `V3` and has **zero consumers in Java** — so a bulk endpoint written against the obvious
permission hands every Test Engineer the power to approve 200 cases at once. §61's silent-widening
shape, and §6g names bulk-approve as the reason the feature exists at all.

**The rule, in two sentences:**

1. `APPROVED` is reachable **only from `IN_REVIEW`**, and that transition needs `case:approve`.
2. Every other transition among the four states needs `case:edit`.

That is §6g's diagram exactly. Everyone holding `case:approve` also holds `case:edit` (derived from
the catalogue in `SeedDataTest`, not assumed), so the gate asks for one key, not two.

### 2. `lifecycle_status` has no writer at all today

`CaseUpdateRequest` omits it deliberately — *"a status that an ordinary edit can set is an approval
anyone can grant themselves by saving"* (`TestCase:342`). So this endpoint is the **only** way a
case ever leaves `DRAFT`, which makes the gate above the whole product's approval gate, not a
convenience path beside one.

### 3. Two of the eight actions are inside the version content hash

`CaseVersions.hash` digests `CaseEdit`, which contains **`suiteId` and `automationStatus`**. So
`MOVE_TO_SUITE` and `SET_AUTOMATION_STATUS` change hashed content, and a bulk path that changed them
without writing a `test_case_version` row would leave the *next* single-case save computing a
different hash — **silently attributing the bulk change to whoever saved next**, in the table whose
whole job is answering "who changed this".

So those two write versions through the existing `CaseVersions`, which needs a `CaseEdit`
reconstructed from the live row. The reconstruction is the risk: if it does not reproduce
`validate`'s normalization byte-for-byte, every subsequent save writes a spurious version. It is
**pinned by a round-trip test** rather than by reading — bulk-move a case, then `PATCH` it unchanged,
and assert no second version appears.

The other six actions touch `lifecycleStatus`, `ownerId`, `case_tag` and `case_condition`, none of
which is in the hash, so they correctly write no version.

## Design

**Parse the untyped payload into a sealed command at the edge.** `BulkOperation.payload` is
`additionalProperties: true` — an untyped bag. Validating it once, up front, means the apply loop
works on typed data and a ninth action is a compile error at every consumer (the property §61 got
from making `canDoOnAuthored`'s switch non-exhaustive).

```
BulkCommand (sealed)
  SetStatus(LifecycleStatus)            SET_STATUS, and SUBMIT_FOR_REVIEW aliases to SetStatus(IN_REVIEW)
  SetAutomationStatus(status, reason)   in the version hash
  SetOwner(UUID)                        validated against the §54 member predicate
  MoveToSuite(UUID)                     in the version hash
  AddTags(List<String>)
  AttachConditions(UUID, ConditionKind)
  DetachConditions(UUID)
```

`SUBMIT_FOR_REVIEW` is kept on the wire because §6g uses the vocabulary, and **aliased at the parse
boundary** so there is one implementation of one state change, not two spellings that drift.

**Per case, a sealed `Verdict`:** `Changed` · `Unchanged` · `Skipped(reason)`. Every requested id
lands in exactly one bucket, so `changed + unchanged + skipped == total` is an invariant a test
pins — **the MSW mock violates it today** (`if (!row) continue` drops ids that are not in the
project, so a user who selects 44 gets a report accounting for 42).

**Preview and commit are the same code path** with a `dryRun` flag consulted only at the point of
mutation, so a preview cannot describe an operation the commit would not perform.

**One audit event for the whole operation**, naming every changed id (§6g: *"preview, transactional,
single audit event"*), returned as `auditEventId`. Null on a dry run.

## Order of work

| # | Phase | Gate |
|---|---|---|
| 1 | Contract: type the payload per action, declare the 400s, document the approve gate | `npm run gen:api` clean |
| 2 | Backend: `BulkCommand`, `BulkOperations` (parse/validate), `CaseRenderer` extraction, entity factories + mutators | `./gradlew build` |
| 3 | Backend: `CaseBulkService` + controller, audit, versions for the two hashed actions | `./gradlew build` |
| 4 | Tests: endpoint, the approve gate, the accounting invariant, the version round-trip | mutation-checked |
| 5 | Frontend: eight actions in the dialog, `case:approve` gating, mock parity | `npm run build` + lint + `test:run` |
| 6 | Live pass against the real backend, then the handoff section | browser + psql |

## Deliberately not built

- **The review workflow's opt-in project setting** (§6g: optional, off by default, per project). No
  project-settings mechanism exists. The four states exist on every project regardless — the column
  is `NOT NULL` and the dashboard tiles them — so what is deferred is the *reviewer apparatus*
  (assignment, LGTM, comments), not the states.
- **Author-cannot-approve-own-work** (§6g's *"single most valuable setting"*, default on). Needs the
  same settings mechanism; `AuthorshipGate` is the seam it would use.
- **`REJECTED`** — in §6g's diagram, not in `LifecycleStatus`. Reject is `IN_REVIEW → DRAFT`.
- **Deprecation as its own gate.** Whether `case:edit` should be enough to deprecate an APPROVED
  case is a real question the catalogue has no key for; §6g's "exactly one gate" is followed
  literally rather than inventing a permission.
