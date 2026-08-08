# Automation Service stops being grantable to a person — and the grant axis parts from the revoke axis

**Date:** 2026-08-08 · **Slice:** §134 · **Two phases, one migration (`V41`), one contract change
(description text only)** · Re-derived from the code, not from a plan's tail (§28, §29, §46).

**Where it came from.** [§133](../handoff/133-the-agent-role.md) named it on its way out: `V3` has
described Automation Service as *"non-human, attached to API tokens, never people"* since M0, and
nothing has ever refused such a grant — **§100's rule, eighth sighting: a javadoc or a description
asserting a property nothing enforces.** `V40` built the mechanism (`role.assignable`) for a
different role; this slice is the second row to use it, which is the test of whether the mechanism
generalised.

The MCP WIP plan's §2.4 settled that **the role stays** and that CI must not be folded onto `Agent`.
That decision is untouched here: Automation Service keeps its five capabilities plus `project:read`,
keeps being issuable to a token, and keeps being listed. What changes is only whether a *person* may
hold it.

---

## 1. The finding — `assignable` was answering a fourth question it should not

`V40`'s comment says the flag drives *the two `RoleGrantService` enforcement points*, grant **and**
revoke, and that reads as one rule. It is two, and they were fused only because the single
non-assignable role at the time happened to want the same answer in both directions:

| | Why grant is refused | Why revoke is refused |
|---|---|---|
| **Company Member** | granting it by hand is a no-op on a row joining already wrote | **removing it takes away a colleague's sight of the company**, repairable only by re-running the operator CLI |
| **Agent** | nobody is ever an Agent; the grant would confer a capability no evaluation path reads | *no reason of its own* — such a grant cannot exist, so the arm is unreached |
| **Automation Service** (this slice) | it is a credential's capability set, not a person's | **the opposite** — a grant that already exists is precisely what an administrator must be able to clean up |

So `assignable` answers *"may `POST /role-assignments` create this"*. The revoke refusal is about a
**consequence of removal**, and exactly one row has it. That makes revoke a **baseline-specific
check** — the fourth site in that family, alongside `AdminDirectoryService`'s two filters and
`ApiTokenService.validate`, and for the same reason each of those stayed: the two questions
coincided while there was one non-assignable role.

**Why this is the load-bearing part.** Without the split, flipping the flag strands every
person-held Automation Service grant that already exists: `AdminDirectoryService:418` filters only
`Company Member` out of `GET /role-assignments`, so the grant stays **on the screen** with a Revoke
button, and the button 403s forever. The product would show an administrator a mess it had just
made and no way to clear it. **User decision, 2026-08-08: keep existing grants, keep them
revocable.** No migration deletes data — a `DELETE` from a migration removes somebody's access on a
live instance with no audit row it could honestly write.

---

## 2. What §A2's sentence becomes

Roles spec §A2 says *"these six are the complete set of **assignable** roles in v1"*, and `V40` was
careful to keep it literally true. This slice makes it false: five of the six are grantable to a
person, and Automation Service is the exception. The six are still **the complete role set** — the
change is that "the six" and "the assignable set" stop being the same list, which is the honest
statement of what the product has done since M0 anyway.

`Instance Admin` is unaffected and stays `assignable = true`: it is refused by this endpoint for a
different, scope-shaped reason (SYSTEM grants live in `instance_role_assignment`), and conflating
the two would put the wrong message on the wrong refusal.

---

## 3. Phases

### Phase 1 — the migration, the split, the contract

- **`V41__automation_service_not_assignable.sql`** — `UPDATE role SET assignable = false` for
  `…-000000000005`; rewrite `role.assignable`'s column comment, which currently names two rows and
  the reason "carried by API tokens only" now covers two of the three. **No table, no policy** —
  `role` is instance-level reference data on the ERD §0.1 exception list, so the `FORCE ROW LEVEL
  SECURITY` count stays **62**.
- **`RoleGrantService.revoke`** — stops reading `Role::isAssignable`, compares against
  `Role.COMPANY_MEMBER_ID`, with the comment that refuses the re-unification. The fail-closed arm
  for an absent role row is kept and restated: a missing role is still refused.
- **`RoleGrantService.validate`** (grant) — **unchanged**. It already reads the flag; that is the
  whole point of `V40` and the reason this slice is small.
- **`Role.java`** — `AUTOMATION_SERVICE_ID` and the `assignable` field javadocs. The field's
  sentence *"and whether the matching `DELETE` may revoke such a grant"* is now wrong and is the
  thing this slice exists to correct.
- **Tests:** granting Automation Service to a person is a 400 on `roleId`; an Automation Service
  grant that already exists **is revocable** (204, row gone) and **is listed**; the baseline is
  still refused in both directions; `SeedDataTest.nonAssignableRolesAreExactlyTheTwo` becomes three
  and says which.
- **`openapi.yaml`** — `Role`'s description text ("Seven names, not six, and only six of them are
  assignable") and `assignable`'s own field text. Description-only, then `npm run gen:api`.

### Phase 2 — the documents

Roles spec §A2 + §A6 + the change log, `README.md` and `CLAUDE.md`'s matrix prose, the orientation
cache, `docs/handoff/134-*.md` + the index row, and the two verification queues.

---

## 4. What this slice does NOT do

- **No frontend code change.** `GrantRoleDialog`, `RoleReference` and `ProjectAccessPage` already
  filter on `assignable` (§133), and `IssueTokenDialog` already carries the comment refusing that
  filter. Automation Service leaves the grant pickers and stays in the token form with no line
  edited — which is the §133 mechanism paying for itself, and worth stating rather than assuming:
  the SPA fixtures in `GrantRoleDialog.test.tsx` and `IssueTokenDialog.test.tsx` are hand-written,
  so their `assignable` values must be re-checked against the new truth.
- **It does not rework the two persona tests.** `CaseQuarantineEndpointTest` and
  `CaseAttachmentEndpointTest` build "a person holding Automation Service" by saving a
  `RoleAssignment` directly, so they do not go red — they bypass the endpoint. They now describe a
  state the product will not create afresh, which is legitimate (legacy grants are exactly that
  state) but should be said in the class where it is relied on. The faithful version drives them
  through an **API token** carrying the role, which is §133's unbuilt `AG-9` — a missing test, not a
  mutation — and belongs with it.
- **It does not touch `PermissionEvaluator`.** An existing grant keeps conferring exactly what it
  conferred yesterday. That is the chosen behaviour, not an oversight.
