# §66. The `OWN` qualifier reaches the row — the author on the wire, and the test that passed while pending

**Date:** 2026-08-02. **No migration; `V20` is still free.**

§65 put the *qualifier* on the wire and named this as the next rung: `case:delete`'s `OWN` grant was
serialized and **nothing read it**, so a Test Engineer saw Delete enabled on a colleague's case and
learned whose it was from a 403. This closes it.

## The rung was smaller than named, and the blocker was somewhere else

§65 described the work as "a sweep across the case grid, the case detail and the bulk dialog". The
orientation pass found that **only the case detail has a delete affordance at all** — the grid has
no delete, and `BulkActionDialog` offers eight actions, none of them a delete. So the sweep is one
call site.

The blocker was a different thing entirely, and it is the finding: **the server compares
`test_case.created_by`, and that column was not on the wire in any form.** `TestCaseSummary` carries
`owner`, which is a *different question* — who is responsible for the case now, not who wrote it —
and the two diverge the moment a case is reassigned. So the client could not ask the question the
server answers even in principle, whatever §65 had put in the permissions response. A slice that had
only wired `canOnAuthored` to `owner` would have compiled, rendered, and refused the wrong rows.

Hence `createdBy` on `TestCaseSummary` (nullable `UserRef`, required key), served from the **same
`CaseOwners` batch** as `owner`: the two id sets are unioned into one lookup, because they overlap
heavily in practice and a second call would double a two-query resolution to render one column —
the N+1 that looks harmless because each query is fast.

## `createdBy` is nullable, and that arm is the whole gate

`test_case.created_by` has no `NOT NULL` and no FK. A row written before authorship was recorded has
no author, and `IdentityProjectAccess.canDoOnAuthored` answers such a row `false` for an `OWN`
grant — *a grant covering "your own work" must not stretch to work nobody can attribute*.

So the client must be able to see the **absence** rather than infer it, and must not collapse it
into a match. `isTheAuthor` in `useCan.ts` is written out rather than left to `===` on possibly-
undefined values, because there are **two** absences in play — no author, and no session — and
loose equality would make them equal each other and hand an unauthenticated render a Delete button
on every unauthored case. Those are the oldest rows in an installation, so getting it wrong would
read as a defect in the import rather than in the gate.

## `useCan` needing the caller broke 73 tests, and the fix is the interesting part

The `OWN` arm compares the row's author against the caller, so `useGate` needs the signed-in user's
id. The first implementation read it through `useAuth()` — which **throws** without a provider — and
**73 tests went red at once**, none of them about authorship. `useCan` is called by
`PermissionGate`, which is called by a couple of dozen components; requiring `<AuthProvider>` in all
of them, and in every test that renders one, is a large coupling increase bought for one arm of one
grant.

`useCurrentUserId` is the answer: the same context read, **non-throwing**, returning `undefined`.
That is a real answer rather than a swallowed error — no session means no caller, no caller matches
no author, and the arm fails closed exactly as the server would for a request it cannot
authenticate. Every other arm of the gate is untouched, because none of them asks who anybody is.
A missing provider in the *app* remains impossible for a different reason: `RequireAuth` could not
have let a route render without one.

`TestProviders` gained an optional `userId` for the same reason, and its default — **omitted means
no session** — is what let the other 485 tests stay unchanged.

## `PermissionGate` asks about the row now, and refuses the incoherent question

`authorId` is optional; supplying it switches the verdict from `can` to `canOnAuthored`. Two
decisions:

- **`authorId !== undefined`, not truthiness.** `null` is a *supplied* author — the unauthored row —
  and it must reach the gate, which is the arm that refuses it. `authorId ? … : …` would have given
  every unauthored case the unqualified verdict, which is the exact defect the prop exists to close.
- **`authorId` with `projectId="company"` throws.** Authorship is a question about a row in a
  project and a company-scope grant has no row; one of the two arguments would have to be ignored,
  so neither is. The same call `IdentityProjectAccess` makes when a qualifier and a question do not
  meet, and unreachable for the same reason.

## The test that passed while pending — §62's lesson, committed in this slice's own test file

`PermissionGate` had tests for the verdict; **nothing proved the page passed the author**. A call
site that drops `authorId` falls back to the unqualified verdict *silently*, because an omitted prop
correctly means "this permission is not author-qualified" for every other gate in the app. So
`CaseDetailPage.test.tsx` exists for the wiring, and the mutation is deleting `authorId` from the
JSX.

**The first draft of that file passed all four assertions against exactly that mutation.** `useCan`
is fail-closed while pending, so Delete is disabled on the first render for a caller who is about to
be *allowed* — and `waitFor(() => expect(…).toBeDisabled())` is satisfied immediately by the pending
state. The assertions were about the loading spinner, not about authorship, and they ran in 44 ms.

Hence `permissionsResolved()`: wait for the **Edit** button to be enabled — `case:edit` is
unqualified in the fixtures, so it flips only once the answer lands — and *then* assert Delete.
This is §62's finding one level in: a component test that passes for the wrong reason. It was caught
by running the mutation rather than by reading the test.

## Mutations run

| Mutation | Result |
|---|---|
| `CaseSummaries` renders `createdBy` from `getOwnerId()` | **exactly 4** backend tests red, all in the new `Authorship` block, nothing else |
| `isTheAuthor` → `callerId == authorId` (loose) | 1 red — the no-session case, the only place the two absences meet |
| The `OWN` arm returns `true` (authorship ignored) | 5 red across `useCan` and `PermissionGate` |
| `authorId` deleted from `CaseDetailPage`'s JSX | 2 red — **0 before the pending barrier was added** |

The backend fixture is what makes the first one possible: `CaseListEndpointTest.seedCases` now
seeds `created_by` **disagreeing with `owner_id` on almost every row**, plus one owned-and-
unauthored row and one authored-and-unowned row. They are equal on most *real* rows, which is
precisely why seeding them equal would let `createdBy: owner` pass every assertion. §64's rule.

## The mock stopped deferring the refusal

`handlers/cases.ts` carried a written note that the `OWN` refusal was **not modelled**, because the
only fact available was `owner` and *refusing on a different fact than the server uses teaches the
wrong rule more convincingly than not modelling it at all*. Both halves have now landed, so the
handler reads `createdBy` exactly as `CaseDeleteService` reads `created_by`, after the lookup and
in the same order. Without it the mock would agree with itself in the worst way: a disabled button
and an accepted write.

The fixtures follow the same guard — `authorOf` defaults a seed's author to **the next user, not the
owner**, so the two columns are visibly different everywhere and a client reading the wrong one
shows up on the first screen rather than on the first 403. Three seeds are authored by
`currentUser` and one is `null`, so mock mode renders both branches and the unauthored row.

## Gates

- `backend` — `./gradlew build` clean, **1206 tests, 0 failures, 0 skipped** (+4).
- `frontend` — `npm run build` clean, `npm run lint` 0 warnings, **`npm run test:run` 485/485**
  (+15). The build caught a type error the test runner did not: `heldPermissions` accepts only the
  *exception* qualifiers, so passing `'ALL'` explicitly is refused by the type. `CLAUDE.md`'s rule
  about verifying with `npm run build`, earning itself again.
- **Not driven in a browser** — §65's standing policy. Rows are queued in both verification files.

## Left for later, deliberately

- **`evidence:delete` is also `OWN`-qualified** (roles spec §A5, mirrored in the mock's
  `QUALIFIED_GRANTS`) and **has no consumer at all** — evidence upload is still unbuilt (§5). When
  it lands, its delete affordance needs `canOnAuthored` and the evidence row's author, exactly as
  this one does. The machinery is now in place; only the call site is missing.
- **`ConditionRepository`'s `ORDER BY usageCount` still ranks by a constant** (§64's carry-over).
- **The artifact register's plan filter is `useState`, not the URL** (§61's carry-over).
