# DEC-002 — Which permission a review act needs

**Created:** 2026-08-03 · **Owned by:** `ba-decision-table`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03
**Area:** AREA-005 (Review & Collaboration)

**Hit policy: First applicable** (one act → one permission; the three subject types share one
answer per act, deliberately, so a single key covers cases, suites and plans alike).

## Inputs

- The act being attempted (comment / give a peer verdict / approve / resolve a thread / assign
  a reviewer / unassign)
- The subject type (case / suite / plan) — deliberately **does not change the answer**

## Rule table

| R | Act | Permission required | Subject type matters? |
|---|---|---|---|
| R1 | Leave a threaded comment | `case:comment` — **one key for all three subject types** | No |
| R2 | Give a formal peer verdict (LGTM / request changes) | `case:review` / `suite:review` / `plan:review` (per subject) | Yes — per-type key |
| R3 | Approve (sign off) | `case:approve` / `suite:approve` / `plan:approve` (per subject) | Yes — per-type key |
| R4 | Resolve or reopen a comment thread | The subject's **review** key (not the comment key) | Yes — per-type key |
| R5 | Assign or unassign a reviewer | The subject's **review** key (not a new "assign" key — none exists) | Yes — per-type key |

`#observed`: R1 and R4/R5's split is directly visible in
`ReviewController.java:73-116,247-256` (two different 403 sentences, proving the gates differ);
the per-subject-type review/approve keys are cross-checked against
`docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A3 "Review" table.

## Notes

- **R1 is one key covering three artifact types on purpose** — the alternative (separate
  `suite:comment`/`plan:comment` keys) was considered and rejected because a freshly-seeded
  catalogue would grant those keys to nobody, making commenting on a folder or a plan a 403 no
  administrator could grant their way out of. `#inferred` from orientation cache (§79,
  explicitly recorded there as the design rationale), consistent with the one-key evidence
  observed in the controller this session.
- **R4 and R5 are decided by "which tier is this act really a statement about," not by who
  performs it.** Resolving a thread is a judgement about the review's progress (review-tier),
  not a remark (comment-tier) — even though the person doing it might otherwise only hold
  comment rights. Asking someone to review is likewise gated on the review key rather than a
  new dedicated key, again to avoid seeding a permission nobody holds.
  `#inferred` from orientation cache (§79/§80), consistent with the observed controller
  behaviour (`ReviewController.java:118-165` uses the same `respond(...)` sentence as
  participation, not the comment sentence).
- **For this to be sound, every role holding any `*:review` key must also hold `case:comment`**
  — otherwise a reviewer could formally review something they are not permitted to comment on.
  `#inferred` from orientation cache, describing a computed test (`SeedDataTest`) that enforces
  this as an invariant over the seeded catalogue — not independently re-run this session.

## Open question

Should the ladder assumption above (every reviewer is also a commenter) ever be allowed to
break — e.g., a future role that reviews but explicitly may not converse? Not raised as a live
need anywhere in this trace; flagged only because the system currently treats it as a hard
invariant. `#inferred`
