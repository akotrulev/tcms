# Case delete, and the `OWN` qualifier the roles spec has owed since M0

**Slice:** Backend M1 slice 10 + the frontend half.
**Date:** 2026-07-31.
**Run through:** `be-java` (Mode B — extend), `data:data-migrations` for V12.

## The premise, checked before it was believed

Handoff §31's next-step list said `DELETE /cases/{caseId}` "has no consumer *and* no endpoint, so
the slice owes both halves". Verified rather than assumed (§28/§29/§31's recurring lesson):

| Claim | State on arrival |
|---|---|
| No endpoint | **True.** `CaseController` serves `GET`, `GET /{caseId}`, `POST`, `PATCH`. No `DELETE`. |
| No consumer | **True.** `useDeleteCase` exists in `queries.ts` with zero call sites. |
| It's in the contract | **Partly** — `openapi.yaml` declares `delete:` with a bare `'204'` and *no* 403/404. |
| The `OWN` qualifier exists | **No.** `PermissionEvaluator`, `ProjectAccess#canDo` and V3's header each record it as owed. |

So the slice is: one endpoint, and the authorization primitive it is the first caller of.

## The hard part is not the delete, it is `OWN`

Roles spec §A5 and the permission table: `case:delete` is `✓` for Instance Admin, Company Admin and
Test Lead, and **`OWN` for Test Engineer** — authorship confers full control of that case, and a
Test Engineer is never blocked from *producing*, only from unmaking **someone else's** work.

The qualifier cannot be resolved from anything that exists today:

- It is **not** a separate `case:delete:own` key — V3's header refuses that explicitly (it doubles
  the catalogue and still cannot express "in any state").
- It **must not** be resolved by asking which role granted it. Code checks permissions, never role
  names (plan §6b) — a name-keyed rule mis-answers for the first custom role anyone adds, which is
  handoff §6 lesson 10 in a different costume.

### Decision: the qualifier is a column on `role_permission`

`V12` adds `qualifier TEXT NOT NULL DEFAULT 'ALL' CHECK (qualifier IN ('ALL','OWN'))` and sets it to
`OWN` on exactly two rows — Test Engineer's `case:delete` and `evidence:delete`. Everything else
keeps the default, including the Instance Admin grant that is *derived* from the catalogue.

Why the grant table and not the permission table: the qualifier is a property of **this role's
grant of** the permission, not of the permission. `case:delete` is unqualified for a Test Lead and
`OWN` for a Test Engineer — the same key, two answers, so the key cannot carry it.

**Strongest wins.** A caller holding the same permission twice — directly and through a team, or
PROJECT and COMPANY scope — resolves to `ALL` if any grant is `ALL`, `OWN` if only `OWN`s, `NONE` if
none. A Company Admin who is also a project's Test Engineer is not demoted by the weaker grant.

The migration carries DDL and three rows of reference DML in one file, against
`data:data-migrations`' "no DDL + DML in one file" rule. The rule's reasons — rolling deploys and
long backfills — do not apply: `role_permission` is instance-level reference data seeded by V3, the
table holds ~200 rows, and this deploys as one jar. The deviation is recorded in the file header.

### Where the decision is made

`repository`'s `package-info` is explicit: *"A permission check appearing anywhere in this package is
a defect regardless of what this list permits."* So `CaseDeleteService` must not ask "is my grant
`OWN`?" — it hands the row's author to a gate that `project` owns:

```
ProjectService.writeGrantedByAuthorship(userId, projectId, permission, (project, authorship) -> …)
                                                                        └── authorship.mayActOn(authorId)
```

`repository` looks the case up (it owns the row), then asks `mayActOn(case.createdBy)`. The verdict
is computed in `identity`, from `PermissionEvaluator.qualifierOf`. The chain is:

```
CaseDeleteService → ProjectService (gate) → ProjectAccess → IdentityProjectAccess
                                                          → PermissionEvaluator.qualifierOf
                                                          → RoleAssignmentRepository (SQL, native)
```

`createdBy` may be NULL (rows seeded before authorship was recorded). Under `OWN`, NULL is **not**
the caller — deny. The alternative fails open on exactly the rows nobody can vouch for.

## The endpoint

`DELETE /api/v1/projects/{projectId}/cases/{caseId}` → `204`, no body.

| Situation | Answer |
|---|---|
| Project unreachable — absent, other company, no grant | `404`, empty body (§6t) |
| Case not in that project / already soft-deleted | `404`, empty body |
| Readable, no `case:delete` (Viewer) | `403` + problem body naming no permission key |
| Readable, `case:delete` is `OWN`, caller is not the author | `403` + a *different* message |
| Otherwise | `204`, `deleted_at` set, `audit_event` row `case.deleted` |

Soft delete, like the suite above it: past runs pin the version they executed and must keep
resolving what they covered (§3 lesson 1). The row stays; the reads already exclude it
(`CaseSpecifications` and `findLiveInProject` both filter `deletedAt IS NULL`), and the display key
and automation key stay reserved — deliberately, per `TestCaseRepository`'s header.

**No `If-Match`.** The suite delete has none either, and the precondition would be answering a
question nobody asked: a lost update matters when two edits interleave, and there is no "later
delete" to lose. Deleting a case someone else just edited deletes the case they edited.

**No cascade, in either direction.** Steps, conditions, tags and attachments hang off the case and
stay; nothing walks them. `suite:delete` already refuses to cascade into cases for the stronger
version of this reason.

## Deliberately out of scope

1. **The SPA cannot see the qualifier.** `GET /me/permissions` returns keys, and a Test Engineer
   holding `OWN` looks identical to a Test Lead holding `ALL`. So the Delete button is enabled for
   both, and a Test Engineer pressing it on a colleague's case gets one 403 in a dialog. That is
   §31's stated policy (a wrong *enable* costs a 403; a wrong *disable* tells someone they may not
   do their job) applied unchanged. Carrying qualifiers on `EffectivePermissions` is its own
   contract slice and would also need the case's author on the wire.
2. **`evidence:delete`** is marked `OWN` by the same migration and gated by nothing yet — the
   `execution` module does not exist. The data is correct in advance; no code reads it.
3. **Bulk delete** from the case list. `BulkResult` is in the contract; one case at a time first.
4. **Undelete.** Soft delete makes it possible and nothing exposes it.

## Phases

| Phase | Content | Gate |
|---|---|---|
| A | V12, the qualifier through evaluator + gate, `CaseDeleteService`, the controller verb, openapi | `./gradlew build` |
| B | The SPA's Delete affordance, the confirm dialog, the mock handler | `npm run build` + `npm run lint` + a browser pass in both modes |
