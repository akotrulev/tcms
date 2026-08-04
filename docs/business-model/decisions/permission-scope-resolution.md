# DEC-001 — Which permission a caller effectively holds on a project

**Created:** 2026-08-03 · **Owned by:** `ba-decision-table`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03
**Area:** AREA-001 (Identity & Access); consumed by every other area's write gates

**Hit policy: First applicable, with union within a level** (not a simple DMN single-hit
table — the evaluator unions grants *within* a scope level, then lets a PROJECT-level grant
**override downward**, replacing rather than unioning, across levels).
`RoleGrantService.java:380-427` `#observed`; `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A1.1.

## Inputs

- Does the caller hold a SYSTEM-scope grant (Instance Admin)?
- Does the caller hold a COMPANY-scope grant (e.g. Company Admin) in this company?
- Does the caller hold a PROJECT-scope role **granted with no project** (company-wide,
  "inherited")?
- Does the caller hold a PROJECT-scope role **granted on this specific project**?

## Rule table

| R | SYSTEM grant | COMPANY grant | Company-wide PROJECT-scope grant | This-project grant | Effective permission on this project |
|---|:--:|:--:|:--:|:--:|---|
| R1 | ✓ | – | – | – | Everything (SYSTEM term is total) |
| R2 | – | ✓ | – | – | Everything COMPANY confers (e.g. Company Admin sees/edits every project) |
| R3 | – | – | none | none | Nothing (not even project visibility) |
| R4 | – | – | ✓ (e.g. Test Lead) | none | The inherited role's permissions apply as-is |
| R5 | – | – | ✓ (e.g. Test Lead) | ✓ (e.g. Viewer) | **The project-specific grant wins outright — replaces, does not union** — even though it grants *less* |
| R6 | – | ✓ (e.g. Company Admin) | – | ✓ (e.g. Viewer) | COMPANY-scope is **never overridden** downward — still full Company Admin access |

`#observed`, `RoleGrantService.java:380-427`, cross-checked against
`docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A1.1's own worked table.

## Notes

- **The switch is "does a grant naming this project exist," never "what does it confer."** A
  role granting *nothing* would otherwise fail to override at all, and a check phrased as "are
  there project permissions" would silently union instead of replace.
  `RoleGrantService.java:415-427` `#observed`.
- **Holding every project through inheritance is not the same as company-level authority.** A
  company-wide Test Lead can read/edit every project's content and still cannot create a
  project or change company settings — those questions are answered from the SYSTEM/COMPANY
  terms only, never derived from "holds this on every project."
  `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A1.1 point 3, referenced directly
  (spec text, not independently re-derived from code line-by-line this session).
- **Company membership by itself (no grant at all) conveys authentication and zero project
  visibility** — R3 above is the default state of an ordinary new member. `#observed`,
  `ProjectMembershipService.java:14-57`.

## Open question

Is the override-downward behaviour (R5) intuitive enough for administrators, or does it need a
warning in the UI when a project-specific grant would *reduce* someone's access below what
they'd otherwise inherit? Not covered by this trace (frontend behaviour). `#inferred`
