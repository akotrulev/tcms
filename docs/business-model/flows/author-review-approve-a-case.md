# FLOW-002 — Author, review and approve a test case

**Created:** 2026-08-03 · **Owned by:** `ba-process-flow-diagram`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03
**Areas:** AREA-003 (Test Repository), AREA-005 (Review & Collaboration)
**Entities:** ENT-001 (case/suite lifecycle), ENT-004 (review record)

```mermaid
flowchart TD
    A([Test Engineer creates a case]) --> B[DRAFT, no open review]
    B --> C{Author edits further}
    C --> B
    B --> D[Author submits for review — needs case:edit]
    D --> E[IN_REVIEW; a review row opens]
    E --> F[Colleagues asked to review, optionally — case:review]
    F --> G[Peers give LGTM / request changes; anyone who can see it may comment — case:comment]
    G --> H{Lead decides — needs case:approve}
    H -->|Approve| I[APPROVED; review closes as approved]
    H -->|Send back| B2[DRAFT; review closes, not approved]
    I --> J{Later: needs another look}
    J -->|Lead/Admin returns to draft — case:approve, "unmaking"| B2
    B2 --> D
    I --> K{Retire the case}
    K -->|case:approve| L[DEPRECATED; any open review closes as OUTSIDE_REVIEW]
    B --> K
    L --> M[Return to DRAFT — case:edit]
    M --> D
```

`#observed` — every node is directly evidenced in `LifecycleLadder.java` (ENT-001) and
`ReviewStanding.java`/`ReviewController.java` (ENT-004, AREA-005); the composition into one
flow is this document's synthesis.

## Notes

- A Test Engineer can walk the whole left-hand loop (create → edit → submit → sent back →
  re-edit) using only `case:edit`. Every rightward move past IN_REVIEW into APPROVED, and every
  move back down out of APPROVED, costs `case:approve` (ENT-001 R1/R2).
- Suites follow the identical diagram with `suite:*` permissions substituted, and plans follow
  a *similar but not identical* one — see ENT-002 (plans also have an ACTIVE/CLOSED execution
  phase this diagram doesn't have).
- Deleting the case is a separate act from any state above it (`OWN`-qualified for the author,
  unqualified for a lead) — not modelled in this diagram; see AREA-003.
