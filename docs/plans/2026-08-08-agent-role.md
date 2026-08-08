# §133 — the seventh role, and the axis `assignable` cannot carry alone

**Date:** 2026-08-08 · **Slice:** M3 / decision-round-4 era · **Source:**
[`2026-08-08-mcp-server-wip-plan.md`](2026-08-08-mcp-server-wip-plan.md) §2 and §7 phases **2 and 3**,
both marked `[SETTLED]`. §132 landed that plan's phase 1.

**Why this is not blocked by that plan's §8.** Its header says *"do not start phase work until §8 is
closed"*, and §8's own text narrows what is open: question 1 is *"the only genuinely blocking one"*
and it is about **the tool and command surface** (§6), which gates phases 4 onward. Phases 2 and 3
are the **role** and the **issue-path rule** — §2.5's whole argument is that the boundary must live
in the role rather than in the tool list, so the role cannot be derived from a tool list that does
not exist yet. Phase 1 was landed on the same reading.

---

## What this slice does

1. **Seeds `Agent`** — PROJECT scope, token-only, **Test Engineer minus `library:manage`, minus
   `case:delete`, minus `evidence:delete`** (MCP §2.2), and minus `company:read` (below).
2. **Adds `role.assignable`** so the two `RoleGrantService` enforcement sites ask the row instead of
   comparing against a UUID constant.
3. **Puts `assignable` on the wire** and adds `Agent` to `Role.name`'s closed enum, so the SPA's two
   role pickers and its two reference cards can tell "cannot be granted" from "wrong scope".
4. **Makes `expiresAt` required for `Agent` tokens**, with a default pre-filled in the form.

## The shape that decides the slice: two non-assignable roles that differ everywhere else

There is no `assignable` flag today; every exclusion of `Company Member` is a hardcoded
`Role.COMPANY_MEMBER_ID.equals(...)`, in **five** places. `Agent` is non-assignable **but issuable
and must be listed**, where `Company Member` is neither — so a single boolean cannot drive all five:

| Site | Today | After |
|---|---|---|
| `RoleGrantService:286` (revoke) | refuses `Company Member` | **asks `assignable`** — refuses both |
| `RoleGrantService:356` (grant) | refuses `Company Member` | **asks `assignable`** — refuses both |
| `AdminDirectoryService:352` (`GET /roles`) | filters `Company Member` out | **unchanged** — `Agent` must be listed, the token form offers it |
| `AdminDirectoryService:402` (`GET /role-assignments`) | filters its grants out | **unchanged** — an `Agent` grant to a person cannot exist |
| `ApiTokenService:253` (token issue) | refuses `Company Member` | **unchanged** — `Agent` is issuable; carries a comment forbidding the "cleanup" to `assignable` |

The third and fifth are the ones a tidy-up breaks: `WHERE assignable` on the directory filter hides
`Agent` from the token form, and `assignable` on the issue check makes an `Agent` token
unissuable — the one thing the role exists for.

## `Agent`'s grant, and the one line that is not in the MCP plan's table

23 keys, exactly MCP §2.2's kept list:

```
case:read · case:create · case:edit · suite:manage
doc:read · doc:edit
plan:create · plan:edit
run:create · run:execute · result:submit · evidence:attach
case:comment · case:review · suite:review · plan:review
report:view · report:export
artifact:read · artifact:edit (PLAN_SCOPE)
defect:create · workitem:manage
project:read
```

- **`artifact:edit` must reuse Test Engineer's exact `PLAN_SCOPE` spelling** (`V19`), or
  `SeedDataTest.noPermissionCarriesTwoDifferentRestrictiveQualifiers` fails — which is that test
  working.
- **`case:edit` is `ALL`, not `OWN`** — MCP §2.8.1. Editing anyone's case is what §1 asks for.
  `case:delete` is dropped rather than qualified precisely because `OWN` follows
  `api_token.owner_id`, so an `OWN` delete would let the agent delete cases *the human authored*.
- **`project:read` is the visibility precondition** (spec §A6, `V16`), not a capability. Without it
  every project-scoped path 404s and the role is inert over HTTP —
  `SeedDataTest.everyProjectScopeRoleHoldsTheVisibilityPrecondition` derives this.
- **No `company:read`, and that is deliberate.** All four other PROJECT-scope roles except
  Automation Service hold it because *they are held by members* and `V3` says so in as many words. A
  token is not a member: `V15` exists because a PROJECT-scope role's `company:read` is unreachable
  from `evaluateCompanyWide` anyway, and Automation Service — the other token-only role — does not
  hold it. `automationServiceIsTheSmallestRole` still passes: its five keys stay a strict subset.

## Phases

### Phase 1 — the migration, the entity, the two enforcement sites, the spec

- `V40__agent_role_and_assignable.sql`: `ALTER TABLE role ADD COLUMN assignable BOOLEAN NOT NULL
  DEFAULT true`; `false` for `Company Member`; seed `Agent`
  (`10000000-0000-4000-8000-000000000008`, PROJECT, `built_in`, `assignable = false`) and its 23
  grants; `UPDATE` the `artifact:edit` row to `PLAN_SCOPE`. **`role` is instance-level** (ERD §0.1),
  so no `company_id` and no RLS — the policed count stays **62**.
- `Role.AGENT_ID` + `assignable` field/getter.
- `RoleGrantService` lines 286 and 356 read the row.
- `ApiTokenService.validate`'s check gains the do-not-clean-this-up comment.
- `SeedDataTest`: `sixBuiltInRoles` becomes eight rows deliberately, plus a new
  `theAgentRoleIsTestEngineerMinusThree` derived from the two grants rather than enumerated, and a
  new `nonAssignableRolesAreExactlyTheTwo`.
- Spec §A2 (the non-assignable table gains a row + the prose), new §A6a *"Agent — the exact grant"*
  mirroring §A6, change log. `README.md` and `CLAUDE.md` get the delta sentence, not a fourth
  enumeration — a fourth copy of 23 keys is a fourth thing that can drift.

**Gate:** `cd backend && ./gradlew build`.

### Phase 2 — the contract, and the reference card whose own reason generalises

- `openapi.yaml`: `Agent` joins `Role.name`'s enum; `Role.assignable` added **to `required`**;
  prose. `npm run gen:api`.
- `RoleView.assignable`, filled by `roleCatalogue()`.
- SPA: `GrantRoleDialog.grantableRoles` filters `role.assignable`; **`RoleReference` filters
  non-assignable too** — its own javadoc says offering a reference card *"next to a Grant button
  that will refuse it is the mis-teaching"*, an argument written about **scope** that holds
  identically for **assignability**, and `Agent` is the first role where the two axes disagree.
  `IssueTokenDialog` is deliberately **unchanged**: its `scope !== 'SYSTEM'` filter must keep
  offering `Agent`.

**Gate:** `./gradlew build`, `npm run build`, `npm run lint`, `npm run test:run`.

### Phase 3 — expiry, the first per-role rule in the issue path

- `ApiTokenService.validate`: `expiresAt` is **required when `roleId` is `Agent`** (MCP §2.6a). It
  is nullable for every other role for the reason `ApiToken` already records — *"a credential that
  dies mid-release"* is the CI failure — but an agent token sits on a laptop, where the opposite
  risk dominates and an optional field is a field nobody sets. **In the service, not the form**:
  §2.5's rule is that a boundary drawn in a client is not a boundary.
- `IssueTokenDialog` pre-fills a default expiry when `Agent` is selected, and says why.
- Contract prose on `expiresAt`.

**Gate:** both stacks.

## Deliberately not in this slice

- **`Automation Service` stays assignable to a person.** Its description has said *"attached to API
  tokens, never people"* since `V3` and nothing has ever refused such a grant — §100's rule, an
  eighth sighting. Flipping it is a **behaviour change for an existing role** with a live-data
  question behind it (what happens to a grant that already exists), and it belongs in its own slice
  with that question answered. Recorded here so it is not re-found as new.
- **The per-token permission picker** (MCP §8.3) — a future refinement with its own join table.
- **Any CLI, MCP server or tool surface.** Blocked on that plan's §8 question 1.

## Verification — §65

Mutation rows `AG-1`…`AG-n` in `docs/verification/backend-mutation-checks.md`; browser rows in the
live pass's admin lane for the two pickers and the expiry default. **None run.**
