# FLOW-001 — Provision a company, invite, and get staffed on a project

**Created:** 2026-08-03 · **Owned by:** `ba-process-flow-diagram`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03
**Areas:** AREA-001 (Identity & Access), AREA-002 (Company & Project Administration)

## Happy path + the branches that actually exist in the code

```mermaid
flowchart TD
    A([Operator runs provision-company]) --> B[Company + vocabularies + counters created]
    B --> C[First project created, if requested]
    C --> D[First Company Admin: account + INVITED membership + grant]
    D --> E[Invitation link emailed AND printed to console]
    E --> F{Invitee opens the link}
    F -->|Token unknown/expired/reused| G([404 — no detail, same as "never existed"])
    F -->|Valid| H[Preview: email, display name, password required?]
    H --> I{Account already has a password?}
    I -->|No| J[Invitee sets a password]
    I -->|Yes, from another company| K[Invitee accepts with no password]
    J --> L[Membership ACTIVE; account ACTIVE; company:read baseline granted]
    K --> L
    L --> M([Invitee signs in])
    M --> N{Company Admin grants a role}
    N -->|PROJECT scope, named project| O[Staffed on that one project]
    N -->|PROJECT scope, no project| P[Staffed on every project in the company, overridable per-project]
    N -->|COMPANY scope| Q[Company-wide authority, e.g. a second Company Admin]
    O --> R([Can now see and act on the project per their role])
    P --> R
    Q --> R
```

`#observed` for every node except the two branch outcomes under N (P and Q), which are
`#inferred` compositions of DEC-001's rule table applied to this flow — the individual facts
are independently observed (`RoleGrantService.java`), the sequencing here is this document's
own synthesis.

## Key business rules this flow depends on

- A re-run of provisioning is safe and never overwrites or renames anything — see AREA-002.
- An invitee cannot use this flow to overwrite an existing account's password — see AREA-001.
- Being staffed (a grant) is a separate, later step from being a company member — the two
  gates of AREA-001's access model.

## Failure branches

- Unknown/expired/reused token → 404, indistinguishable from "never existed" (AREA-001).
- Password rejected by policy → 422, with the specific reason (AREA-001).
- A colleague's grant request naming an unreachable project → 404 before any permission is
  even evaluated (AREA-001).
