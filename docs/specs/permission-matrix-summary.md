# Permission matrix — convenience copy

**Authoritative source is `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A3** — this is a
convenience copy, also in `README.md`. If they disagree the spec wins and the copies are the bug.
~~55 permissions, all seeded by `V3__seed_permissions_and_roles.sql`; no migration has added one since~~
→ **56 since 2026-08-28: `V71` added `doc:review`** (plan 005 S9b), the first key seeded after `V3` —
and `V51` **moved three grants between roles**, which is a different thing and the one this table
gets wrong most easily.
**Since §214 the spec's §A3 tables and the seeded rows are compared cell by cell, seven roles,
both ways** — `SeedDataTest.theSeededMatrixIsTheSpecsMatrix`, with `MATRIX_DIVERGENCE_BY_DECISION`
for the three that diverge on purpose (`company:read` seeded per role, where §A3's COMPANY table
has only `Every member`). **The copies in CLAUDE.md and README are compared by
`packages/client/src/test/matrixCopies.test.ts`** — it reads both tables and all three scopes of
§A3, compares every cell both ways including qualifiers. `SYSTEM` scope below is a sentence rather
than a table and is compared anyway.

Roles are ordered least-permissioned first; rows run broadest-held to narrowest. `✓` granted ·
`—` not granted · anything else is a qualifier that narrows the grant.

`PROJECT` scope:

| Permission | Viewer | Automation Service | Test Engineer | Test Lead | Company Admin | Instance Admin |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `case:read` · `project:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `case:comment` · `report:view` · `report:export` · `doc:read` · `artifact:read` | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `case:create` | — | quarantine only | ✓ | ✓ | ✓ | ✓ |
| `run:create` · `result:submit` · `evidence:attach` | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `suite:manage` · `case:edit` · `library:manage` | — | — | ✓ | ✓ | ✓ | ✓ |
| `case:review` · `suite:review` · `plan:review` · `doc:review` | — | — | ✓ | ✓ | ✓ | ✓ |
| `plan:create` · `plan:edit` · `plan:delete` · `run:execute` | — | — | ✓ | ✓ | ✓ | ✓ |
| `defect:create` · `workitem:manage` · `doc:edit` | — | — | ✓ | ✓ | ✓ | ✓ |
| `case:delete` · `evidence:delete` | — | — | ✓ | ✓ | ✓ | ✓ |
| `artifact:edit` | — | — | ✓ | ✓ | ✓ | ✓ |
| `suite:delete` · `artifact:delete` | — | — | ✓ | ✓ | ✓ | ✓ |
| `run:close` · `result:amend` · `doc:publish` | — | — | ✓ | ✓ | ✓ | ✓ |
| `case:approve` · `suite:approve` · `plan:approve` | — | — | — | ✓ | ✓ | ✓ |
| `project:audit` · `project:settings` · `project:members` · `integration:manage` | — | — | — | ✓ | ✓ | ✓ |

**The last two rows are the whole Test Engineer → Test Lead line since `V52` (§210)** — giving
approvals, and adding and managing permission on the project. Seven keys, and
`SeedDataTest.testEngineerToTestLeadBoundary` asserts that delta **exactly, in both directions**.

`COMPANY` scope — answered from the SYSTEM + COMPANY terms only, so a PROJECT-scope role contributes
nothing here. `company:read` reaches every member through the non-assignable **Company Member**
baseline role (`V15`), not through the six:

| Permission | Every member | Company Admin | Instance Admin |
|---|:--:|:--:|:--:|
| `company:read` | ✓ | ✓ | ✓ |
| `company:settings` · `company:members` · `company:teams` · `company:vocabulary` · `company:idp` | — | ✓ | ✓ |
| `project:create` · `token:issue` · `audit:view` | — | ✓ | ✓ |

`SYSTEM` scope, Instance Admin only: `company:create` · `company:delete` · `company:suspend` ·
`user:manage` · `role:manage` · `system:settings` · `instance:audit`.

Five things that change how the table reads:

- **The six are the complete role set** — seeded, immutable, **no custom roles, not even
  clone-and-edit**. A client needing something between two tiers gets the higher one. **`V41`: five
  are assignable.** `Automation Service` is a CI credential's capability set, which `V3` has said
  since M0 and nothing enforced until then.
- **THREE seeded rows have `role.assignable = false` and only two of them are outside the six**
  (`V40`, `V41`).
  `Company Member` (`V15`) is the `company:read` baseline joining confers; **`Agent`** (`V40`) is
  the role a token carries for agent access — **`V52` (§210): it is Test Engineer ENTIRE, minus
  `company:read` alone**, derived from Test Engineer's rows *in the migration* so the two cannot
  drift, and asserted by `SeedDataTest.agentIsTestEngineerMinusNothingItCouldUse`. **That last key
  is not a ceiling** — it is unreachable for a credential with no membership row
  (`evaluateCompanyWide` reads the SYSTEM and COMPANY terms only, and a token has no `V15` baseline),
  which is the §A6 reachability rule pointing the other way and which
  `companyReadIsAlwaysExplicit` caught on the migration's first run.
  **What replaced the ceiling is a consent prompt on delete arms in `packages/mcp/src/server.ts`** —
  `destructiveHint` so a host prompts, plus an elicitation that **refuses when the client cannot be
  asked**. Know its reach before relying on it: it binds the MCP transport only, and the same `tcms_`
  bearer deletes over `curl` with nobody to ask. Roles spec §A6a carries that objection, raised
  before the change and struck through as *superseded rather than wrong*.
  **`Automation Service` is the third (`V41`) and behaves like `Agent`.**
  **Assignability, issuability, listedness and revocability are FOUR different questions** — the two
  ceilings are non-assignable but issuable **and listed**, so `GET /roles` must keep returning them,
  and only `RoleGrantService`'s **grant** path may read the flag. `V40` §2 and `V41` §1 name the four
  sites that must stay baseline-specific; converting them is a one-line "cleanup" that makes a role
  unissuable — or, on the revoke path, strands every pre-existing grant behind a Revoke that 403s.
- **An API token never confers its own permissions; it *caps* its owner's:**
  `evaluate(ownerId, projectId) ∩ permissionsOf(token.roleId) ∩ [the token's project]`. Intersection,
  never union — so a token exceeds neither its role nor its owner, and demoting a person shrinks
  every token they issued.
- **NO GRANT CARRIES ANY QUALIFIER — the column is `'ALL'` on every row** (`OWN` emptied by `V51`
  §207, `PLAN_SCOPE` by `V52` §210). CRUD on everything in the project belongs to every non-Viewer
  contributor **no matter who first created the row or what kind of thing it is**. The mechanism is
  kept **deliberately unpopulated**: the qualifier is *data*, so deleting
  `PermissionQualifier.OWN`/`PLAN_SCOPE` or `IdentityProjectAccess.canDoOnAuthored` is what would
  make a future qualified grant fail **open**. Read roles spec §A5 before proposing deletion.
  `SeedDataTest.everyQualifierIsAll` pins the column in both directions, `ownIsCarriedByNoGrant`
  still pins the value, so the emptiness reads as a decision and not a seeding bug. The authorship
  *plumbing* on both sides stays wired for the same reason — `CaseDeleteService`'s gate, its
  `NotTheAuthor` refusal, `useCan.canOnAuthored`, and the `authorId` every `PermissionGate` delete
  site passes. **What is left of the tier is approvals and project configuration, seven keys** (§A4).
  Author-cannot-approve-own-work is untouched — approving is not CRUD.
- **`case:comment` is one key covering cases, suites and plans.** `suite:comment` / `plan:comment`
  deliberately do not exist, and `evidence:read` was rejected on the same grounds: a key seeded to
  nobody is a 403 no administrator can grant their way out of. Resolving a thread is *not*
  commenting — it costs the subject's `*:review`.
- **Author-cannot-approve-own-work defaults on.**
