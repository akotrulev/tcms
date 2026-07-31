# §32. Backend M1, slice 10 + frontend — case delete, and the `OWN` qualifier it forced (2026-07-31)

Run through the **`be-java`** skill (Mode B — extend) plus `data:data-migrations` for V12, then the
frontend half. Plan: `docs/plans/2026-07-31-case-delete-and-the-own-qualifier.md`.

### The orientation pass agreed with §31's list — the first time in four slices

§31 said the slice owed both halves, and it did: no `DELETE` on `CaseController`, `useDeleteCase`
with zero call sites. What the pass *added* was the size of the third thing §31 mentioned in a
subordinate clause — "plus the `OWN` qualifier `case:delete` carries" — which turned out to be the
whole slice. The delete itself is 60 lines shaped exactly like the suite delete above it.

### `OWN` had no way to be expressed, and three files said so

Roles spec §A5: `case:delete` and `evidence:delete` are `OWN` for Test Engineer — authorship confers
full control, and a Test Engineer is never blocked from *producing*, only from unmaking **someone
else's** work. `PermissionEvaluator`, `ProjectAccess#canDo` and V3's own header each recorded the
qualifier as owed. Two implementations were available before this slice and both are wrong: grant
every Test Engineer the delete (they unmake colleagues' cases), or withhold it (an author cannot
remove their own mistake).

**V12 adds `role_permission.qualifier` (`'ALL' | 'OWN'`, default `'ALL'`)** and sets `OWN` on exactly
two seeded rows. Three decisions in it:

- **On the grant, not the permission.** `case:delete` is unqualified for a lead and `OWN` for an
  engineer — the same key, two answers, so the key cannot carry it.
- **Not `case:delete:own`.** V3 refused that before this slice existed: it doubles the catalogue and
  still cannot express "in any state".
- **Not "is the caller a Test Engineer".** Code checks permissions, never role names (§6b). A
  name-keyed rule mis-answers the first custom role, silently, in the direction that grants more.

**Strongest wins.** A Company Admin who also holds a project's Test Engineer grant keeps `ALL` —
`PermissionQualifier` is declared weakest-first so `compareTo` *is* the merge rule. The failure that
prevents: granting someone an *extra* role quietly taking capability away.

### Where the decision is made, and the one thing the type system does not enforce

`repository`'s package doc is absolute: *a permission check appearing anywhere in this package is a
defect regardless of what the dependency list permits.* So the row-level question crosses the
boundary as a **fact**, not a verdict:

```
CaseDeleteService → ProjectService.writeGrantedByAuthorship → AuthorshipGate.mayActOn(case.createdBy)
                                                            → ProjectAccess.canDoOnAuthored
                                                            → PermissionEvaluator.qualifierOf
```

The service supplies the row's author and is told yes or no; it never learns what its grant *is*, so
it has nothing to re-interpret and cannot grow a second reading of `OWN`. The rejected alternative —
returning the qualifier and letting each module compare — puts an authorization rule in every module
that owns a deletable row.

**The honest gap, recorded rather than papered over:** a callback that never calls `mayActOn` gets
the unqualified behaviour, and no type prevents that. Which is why the assertion lives *outside*, in
`CaseDeleteEndpointTest.engineerCannotDeleteSomeoneElses` — drop the qualifier and it is the only
test in the suite that goes red.

**A null `created_by` is not the caller's own.** Cases predating `POST` carry NULL; under `OWN` that
denies. Fail-open on exactly the rows nobody can vouch for is the wrong direction.

### Two 403s, deliberately worded differently

A Viewer holds nothing. A Test Engineer holds `case:delete` and is looking at a colleague's case.
Telling the second "you do not have permission to delete test cases in this project" sends someone
to ask an admin for a permission they already hold, about a case they are looking at, having deleted
three of their own that morning. `Outcome.NotTheAuthor` exists only to carry that sentence.

| Path | Purpose |
|---|---|
| `V12__role_permission_own_qualifier.sql` | The column, the CHECK, and the two `OWN` rows. |
| `identity/PermissionQualifier.java` | **New.** `NONE < OWN < ALL`; ordering is the merge rule. |
| `identity/PermissionEvaluator#qualifierOf` | Re-checks all three gates; unknown key throws. |
| `project/AuthorshipGate.java` · `ProjectService#writeGrantedByAuthorship` | **New.** The port and the gate. |
| `repository/CaseDeleteService.java` · `CaseController#delete` | **New.** 204 / 404 / two 403s / audit. |
| `tenancy/AuditEvent#jsonString` | Moved off `SuiteWriteService` — the case delete needed the same escaper, and two copies diverge on the input neither was tested with. |
| `features/cases/CaseDeleteDialog.tsx` | **New.** Names the case; renders the refusal in place. |

### The SPA cannot see the qualifier, and that is §31's policy rather than a gap

`GET /me/permissions` answers keys, not qualifiers, so Delete is enabled for a Test Engineer on every
case and the `OWN` refusal arrives as a 403 in the dialog. §31's asymmetry applies unchanged: a wrong
*enable* costs one 403 the user is already looking at; a wrong *disable* tells an engineer they may
not do their job. Which makes rendering the refusal **in the dialog** load-bearing — a toast at the
corner, or a dialog that closes on error, and the only sentence that explains it is gone.

### The browser pass found a defect that had nothing to do with the delete

Deleting one case dropped the Payments folder's count by **two**. The cause was pre-existing and
visible on every load without being legible: `Suite.caseCount` is the suite's **own** cases
(`SuiteView` says so, and the SPA's tree rolls the subtree up), and the **mock** answered the
*subtree* count — so every parent rendered its descendants twice. Payments read 40 in a project with
27 cases. Fixed by splitting `suiteCaseCount` (subtree — still what the delete's emptiness check
asks) from `suiteOwnCaseCount` (the wire meaning). Counts now reconcile: 19 + 3 + 3 + 2 = 27.

A single delete is what made it legible: a number that is merely too large looks like data, and a
number that moves by two when one row leaves does not.

### Gate evidence

**Backend** — `./gradlew build` green. `CaseDeleteEndpointTest` is 20 assertions over HTTP against
real Postgres with RLS: both 403s and their differing messages, the orphan row, strongest-wins from a
Company Admin with an engineer grant, 404 for ungranted/foreign/absent projects and for a case in a
sibling project, second delete 404, soft delete verified in the row, audit `case.deleted` carrying a
title with a quote and a backslash, no audit row for a refusal. `PermissionEvaluatorTest` gained the
qualifier matrix — including that **`case:edit` is `ALL` for a Test Engineer**, pinning the
2026-07-31 reversal so restoring the superseded reading fails the build.

**Frontend** — `npm run build` clean, `npx oxlint` 0 warnings, console free of errors and MUI
warnings. Mock: Test Lead deletes TC-3 → dialog names the case → 204 → redirect to the list, 27 → 26,
Refunds 18 → 17, detail 404s; MOB (Viewer) → Delete **disabled** with "You don't have permission to
delete test cases in this project", and the mock's own `DELETE` answers 403 with the same sentence,
so the gate and the handler read one source.

**Not done: a live pass.** The endpoint is covered by integration tests against real Postgres, but
the SPA was not pointed at `docker compose` for this slice — a live delete needs a company with a
project, a suite and a case, which is still hand-written SQL (§31's finding 1, still open). Stated
rather than implied.

### Next

1. **A project-creation path** — now blocking twice over: §31 named it, and this slice skipped its
   live pass for the same reason. "Provisioned company" still means "provisioned company plus SQL".
2. **`GET …/cases/{caseId}/versions` with the detail page's history panel** — `useCaseVersions` is
   six slices old and still the oldest dead hook.
3. **The `execution` module** — unblocks `/history`, the one screen with a live link that 404s. It
   also owns `evidence:delete`, whose `OWN` qualifier V12 seeded and nothing reads.
4. Frontend **Phase 4 (Vitest + RTL)** — `useCan` remains the best first target.
5. **Board drag-and-drop is still ungated** (`workitem:manage`) — unchanged from §31.
