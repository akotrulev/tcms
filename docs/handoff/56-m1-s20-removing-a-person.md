# §56. Removing a person — the half §55 named on its way out, and the token that outlived the status

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential**, the eleventh slice since §45.

`DELETE /users/{userId}` exists. **No migration** — still `V17`; `CompanyMembership.remove()` has
been there since §18 with no caller, which is exactly the dead-code shape §28's orientation question
exists to catch.

## Why this one

§55 built the first third of `company:members` ("invite, remove, set membership display names",
roles spec §A3) and recorded the other two as Not done, with a sentence that chose this slice:
*"Offboarding is the half that matters for a departing contractor."* A product that can add a
person to a company and not remove one is not a staffing loop, it is half of one — and the manual
workaround is an operator with a psql prompt, which is the workaround §55 had just deleted for
invitations.

`documentation` is still the bigger MSW-only surface and still needs a writer before its reads are
honest (§54, §55). Unchanged, and still not this slice.

## The membership is removed, never the account

`app_user` is instance-level and one person legitimately belongs to several client companies
(plan §6s). Deleting the account would offboard them from **every other client on the instance** —
a write one company's administrator must not be able to make — and would orphan the authorship this
company's own history depends on.

So the status goes to `REMOVED` and nothing is deleted. This is what `CompanyMembership`'s header
has always promised and what nothing had yet exercised: membership is a **precondition**, so every
`role_assignment` they hold here goes inert in one status update rather than needing a hunt across
grants. The live pass proves the promise from the other side — Erik's `role_assignment` row is
still there after his removal.

The consequence is obvious in the ERD and surprising at an admin screen, so it is said in three
places that a reader might arrive at independently — the service header, the contract description,
and the dialog body: **re-inviting them restores their previous project access.**

## The session is revoked, and this is the call site `AppUser` said did not exist

`AppUser.revokeSessions()` names "a company membership change" as a caller that must bump the `pv`
epoch, and used to record that no such caller existed. It does now — the first one outside the
class.

**It is not the decision `RoleGrantService` took, and the difference is the point.** A revoked
grant is already effective on the target's next request, because the session token carries no grant
claims and `PermissionEvaluator` reads the database every time; bumping there would sign somebody
out of a session whose permissions are correct. Here the token itself is the problem: nothing in
the request path re-reads membership, so `CompanyContextFilter` would go on opening this company
for a person who is no longer in it, on a signed token valid until it lapses. Their permissions
resolve empty — but "authenticated inside a client's tenancy with an empty permission set" is not
what "removed" means, and it lasts for hours.

The epoch is per *account*, so this signs them out of their other companies too. Accepted
direction, not oversight: the cost is one sign-in where nothing about their access changed, and a
per-membership epoch would put a second revocation channel in the token for a case that resolves
itself in seconds.

## The defect this slice actually found: the token outlives the status that made it unusable

Removing somebody who has not accepted yet leaves a live activation link in their inbox.
`InvitationService#accept` refuses it while the membership is `REMOVED`, so the first reading is
that nothing is wrong.

It is wrong two steps later. A re-invite calls `CompanyMembership.reinvite()`, which puts the
status back to `INVITED` — and `issueIfPending` then finds the old invitation *outstanding*, mints
nothing, and reports that a link is already on its way. **The link that then works is the one
minted before the offboarding, and nobody was told.** So the row is deleted here and a re-invite
always mints fresh.

This is the exact mirror of §55's dead link: there the status made a minted token unusable, here
the token outlived the status that made it unusable. Two slices, one seam, opposite directions —
worth remembering that the pair is the shape, not either instance.

Deleted rather than consumed, deliberately: `consumedAt` means "somebody redeemed this", and
writing it here would put a redemption that never happened in the one table the accept path reads.

## A caller may not remove themselves, and that is the whole administrability rule

Refused as a field error on `userId`, the same shape and reason as `RoleGrantService.SelfLockout`:
the control that caused it is the row's own button.

It is also, deliberately, **not a count of administrators**. Only a holder of `company:members`
reaches this method and they cannot be the person leaving, so a company always retains at least the
administrator who ran the removal. No second implementation of "who administers this company", and
nothing to keep in step with the evaluator. The one exception is an Instance Admin, whose SYSTEM
grants need no membership here at all — they can empty a company and can also repair it, which is
what being an Instance Admin is.

## `204` whether they were a member or not

A repeated request, a double-click, and an id that names nobody here all end in the state the
caller asked for. A member of *another* company is also not here — so isolation and idempotence
give one answer, and answering differently would turn this endpoint into a membership oracle for
guessed ids (§6t). Nothing is audited on the no-op path either: an audit row per repeated click
buries the removal that actually happened.

## The frontend

A fifth-column pattern, so §54's shape rather than a new one: one action per row, header naming the
column (`Membership`) and not the verb.

**Two independent reasons the button disables, with different sentences** (§31 is about the reason
as much as the state): not holding `company:members`, which `PermissionGate` answers, and naming
yourself, which only the session can answer. The server refuses the second with a 400 either way
and the dialog renders it — but a button that invites a refusal the client could have predicted is
what §31 exists to stop.

A modal rather than the immediate action the Revoke column uses, and not out of squeamishness:
revoking a grant takes away one project, this takes away the company. It follows
`CaseDeleteDialog` — it **names the person** rather than asking "are you sure", and the confirm
button is verb-labeled and danger-styled so title and button agree.

The body is two sentences because two things are surprising and neither follows from "Remove":
their session ends immediately (the answer to "are they out *now*?", which is what an administrator
offboarding somebody in a hurry is actually asking), and their roles are kept but stop applying.

`useRemoveUser` invalidates the role assignments and the project list as well as the directory,
**unlike `useInviteUser`** — an invitation conveys nothing, so nothing else can have changed; a
removal makes every grant that person holds inert, which is exactly an authorization change.

## The mock is honest about a branch it cannot render

`companyScopePermissions` is `['company:read']` and stays that way, so in mock mode every `Remove`
is disabled with its reason — the same honest persona that already leaves "Invite a person" and
"New project" disabled. The enabled path is exercised in live mode against a real Company Admin,
which is where §55 exercised the invite for the same reason. A fixture that granted it
unconditionally would be §31's defect in reverse: an affordance that can only ever render one of
its two branches.

The handler still implements the gate and the self-removal 400 rather than always answering 204 —
a mock that could not produce the refusal would make the branch unreachable in development, which
is how §55's three-state defect got as far as a live pass.

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL
           1021 tests, 0 failures, 0 skipped      (1002 + 19)
frontend:  npm run build   → ✓ built
           npm run lint    → oxlint, exit 0
           npm run test:run→ 24 files, 405 tests passed   (401 + 4)
live:      docker compose up (no migration — still V17)
           LIVE mode browser pass — both branches, see below
```

**Live pass, driven as a real Company Admin, verified in psql rather than on screen:**

| Claim | Evidence |
|---|---|
| Membership removed, account kept | `Nadia Newcomer` → `REMOVED`, `app_user` row intact |
| Session revoked | `permission_version` 0 → 1, on both removals |
| Invitation cancelled | invitation rows for the pending removal: 0 |
| Grants kept, not deleted | Erik's `role_assignment` still present after removal |
| Both branches audited | `wasPending: true/invitationCancelled: true` and `false`/`false` |
| No collateral | Dana untouched, `permission_version` still 0 |
| Self-disable | own row's button disabled, tooltip states why |

**Not driven live:** the self-removal 400 (the row's own button is disabled, which is the point —
the backend test covers it) and the 403 (no second persona was signed in), the same two gaps §55
recorded and for the same reasons.

## Not done

- **The third of `company:members` — setting membership display names — is still not possible.**
  `CompanyMembership.rename()` still has no caller. The dead-code shape §28 catches is now down to
  one instance from two.
- **`documentation` and `reporting` are still MSW-only** — `/doc-pages`, `/artifacts`, `/reports`,
  `cases/bulk`, `shared-steps`. `documentation` needs a **writer** to be honest (§54, §55).
- **Teams still have no write endpoints** (§39, §45–§55). A person can be added to and removed from
  the *company*; the org chart itself is still read-only.
- **The symmetric backend check — a declared operation no handler implements — is still not built**
  (§53, §54, §55). It would list exactly the surfaces above.
- **No fixture-id registry** (§54, §55).
- **A removed person is invisible rather than listed as removed.** `GET /users` omits them, so an
  administrator cannot see who left or re-invite them from the directory — the re-invite path is
  the invite dialog and the same email address. Defensible for now, and the first thing to want.
- **A case that moves between suites still takes its whole execution history with it** (§52).
