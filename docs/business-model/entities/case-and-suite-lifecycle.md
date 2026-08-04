# ENT-001 — Test Case / Suite Authoring Lifecycle

**Created:** 2026-08-03 · **Owned by:** `ba-entity-state-grid`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03
**Areas:** AREA-003 (Test Repository), consumed by AREA-005 (Review & Collaboration)

One shared four-state ladder governs both a test case's and a test suite's authoring status
(`LifecycleLadder.java:1-245`). Both entities share the identical rule; case-specific and
suite-specific wording only differ by the noun substituted into the refusal message.

## States

`DRAFT` · `IN_REVIEW` · `APPROVED` · `DEPRECATED`

## State × event grid

| Current \ Requested | DRAFT | IN_REVIEW | APPROVED | DEPRECATED |
|---|---|---|---|---|
| **DRAFT** | Unchanged | Move — needs `*:edit` | Refused ("submit for review first") | Move — needs `*:approve` |
| **IN_REVIEW** | Move — needs `*:approve` (leaving review is "unmaking") | Unchanged | Move — needs `*:approve` | Move — needs `*:approve` |
| **APPROVED** | Move — needs `*:approve` ("unmaking") | Refused ("return to draft first") | Unchanged | Move — needs `*:approve` |
| **DEPRECATED** | Move — needs `*:edit` | Refused ("return to draft first, then review again") | Refused (same) | Unchanged |

`#observed`, `LifecycleLadder.java:134-203`.

## Rules

- **R1 — Submitting for review costs only edit rights.** `DRAFT → IN_REVIEW` needs `*:edit`.
  `LifecycleLadder.java:170-179` `#observed`.
- **R2 — Approving, retiring, or reversing either costs approval rights.** Moving to `APPROVED`
  or `DEPRECATED`, or moving *down* out of a state at or above `APPROVED`, all need `*:approve`.
  This is what stops a contributor from bulk-rescinding a lead's sign-off using their everyday
  edit permission. `LifecycleLadder.java:34-46,196-203` `#observed`.
- **R3 — A deprecated item can only return via DRAFT**, never straight back into review or
  approval — carrying an old approval onto unreviewed content would be a laundered sign-off.
  `LifecycleLadder.java:122-127` `#observed`.
- **R4 — Repeating the current state is a no-op**, not an error and not an audited event (200,
  nothing stamped). `LifecycleLadder.java:74-82` `#observed`.
- **R5 — An illegal-but-approval-shaped move is refused for permission before it is refused for
  state** — a caller lacking `*:approve` sees 403, not a 409 describing a ladder they may not
  climb anyway. `LifecycleLadder.java:170-177` `#observed`.

## Business intent

`#inferred` — the ladder exists to make "approved" mean something specific: content a named
person actually reviewed, immediately before the state that follows it. Every refusal exists to
close a specific route around that (bulk-rescinding a sign-off; reviving a retired item without
re-review). Confidence is high — this reasoning is stated directly in the code's own comments,
not conjectured by this trace.
