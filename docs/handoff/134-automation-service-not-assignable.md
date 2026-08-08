# §134 — The role V3 called token-only, and the axis the flag should never have carried

**Date:** 2026-08-08 · **Two phases, one migration (`V41`), one contract change (description text
only)** · **Plan:** [`docs/plans/2026-08-08-automation-service-not-assignable.md`](../plans/2026-08-08-automation-service-not-assignable.md)

Re-derived from the code rather than from a plan's tail (§28, §29, §46) — though barely: §133 named
it on its way out, in its own "Not built, and why". The question it answers is the round's own —
*which of this schema's assertions has no enforcement* — and the answer was in a `V3` description
from M0: **"non-human, attached to API tokens, never people."** Every grant picker offered it,
`POST /role-assignments` accepted it, and the evaluator honoured it. **§100's rule, eighth
sighting.**

## What it does

`V41` sets `assignable = false` on `Automation Service`. Nothing else about the role changes: same
six keys, still the smallest role in the product, still issuable to a token, still listed by
`GET /roles` — because the token-issue form is now, as for `Agent`, the only place it is ever
chosen. The MCP plan's §2.4 decision that **the role stays and CI must not carry `Agent`** is
untouched.

**Existing grants are kept.** The migration deletes nothing (user decision, 2026-08-08): a `DELETE`
here removes somebody's access on a live instance with no actor and therefore no audit row it could
honestly write. They keep conferring what they conferred yesterday, stay listed, and are now
cleanable up by an administrator who decides they should go.

## THE FINDING: `V40`'s flag was carrying a fourth question, and this is the row where that shows

`V40` §2 says `assignable` drives *"the two `RoleGrantService` enforcement points"*, grant **and**
revoke. That reads as one rule. It is two, fused only because the single non-assignable role at the
time wanted the same answer in both directions:

| | Grant refused because | Revoke refused because |
|---|---|---|
| **Company Member** | granting by hand is a no-op on a row joining already wrote | **it removes a colleague's sight of the company**, repairable only by the operator CLI |
| **Agent** | nobody is ever an Agent | *nothing* — such a grant cannot exist, so the arm was never reached and its answer never tested |
| **Automation Service** | it is a credential's capability set | **the opposite: an existing grant is exactly what an administrator must be able to clean up** |

So `assignable` answers *"may `POST` create this"*. Refusing a **revoke** is about a consequence of
removal, and exactly one row has one — which makes revoke a **baseline-specific check**, the fourth
member of the family alongside `AdminDirectoryService`'s two filters and `ApiTokenService.validate`,
kept baseline-specific for the same reason each of those was.

**Left fused, this migration strands data.** `AdminDirectoryService` filters only `Company Member`
out of `GET /role-assignments`, so a pre-`V41` grant stays **on the screen**, carrying a Revoke
button that 403s forever with no message naming a role. That is §31's disable-don't-hide defect
reached from the server side: the affordance is live, the server refuses, and nothing fails. Both
sites now carry the comment refusing the re-unification, and `V41` §1 is the long form.

**One slice after `assignable` shipped, the flag had to be narrowed.** That is not an argument
against it — the grant path is the one that must generalise, and it did, at a cost of one line. It
is an argument about *when* a boolean earns its name: `V40` called it "assignable" and used it for
"assignable **or** revocable", and the second reading survived exactly as long as there was one row
to test it against.

## The second finding: §A2's careful sentence was guarding the wrong invariant

Roles spec §A2 has said **"these six are the complete set of *assignable* roles"** since M0. `V15`
was written to keep it literally true (`Company Member` is a seventh row, not one of the six), and
`V40` was too (`Agent` is an eighth). This slice makes it false, because Automation Service **is**
one of the six.

The sentence is retired rather than reworded. The care spent on it went to the wrong invariant:
what mattered was never *"the six are assignable"* but *"no role outside the catalogue is"* — and
that one is untouched. `SeedDataTest` now asserts both directions, and the second is the one worth
having: `theAssignableRolesAreTheFive` pins that **Instance Admin is among them**, because it is
`assignable = true` and still refused by this endpoint for a scope-shaped reason, and sweeping it in
with the two ceilings would put "Choose a role." on a refusal whose real message names where SYSTEM
grants live.

## No frontend code changed at all, and that is the §133 mechanism paying for itself

`GrantRoleDialog`, `RoleReference` and `ProjectAccessPage` have filtered on `assignable` since §133;
`IssueTokenDialog` already carried the comment refusing that filter. So the role left three screens
and stayed on the fourth with **no line edited** — one seeded boolean.

The only frontend edit is a **test fixture that had gone stale in a way nothing could catch**:
`IssueTokenDialog.test.tsx` declared Automation Service `assignable: true`, hand-written and true
when written. Its assertion is now the stronger one — the token form offers **both** non-assignable
roles — and `Automation Service` is what makes the tidy-up's cost concrete: unifying the four
filters does not merely hide an agent ceiling, it makes **CI credentials unobtainable**, which is
the failure that gets noticed.

## Also

- **No red run in either phase**, the second slice running to green in a row (§133 was the first),
  which is worth stating because it is not the norm here.
- **The policed-table count stays 62.** `role` is instance-level reference data on the ERD §0.1
  exception list.
- **The two persona tests were left alone.** `CaseQuarantineEndpointTest` and
  `CaseAttachmentEndpointTest` build "a person holding Automation Service" by saving a
  `RoleAssignment` directly, so they never touched the endpoint and did not go red. They now
  describe a state the product will not create afresh — which is legitimate, since legacy grants are
  exactly that state. The faithful version drives them through an **API token** carrying the role,
  and that is §133's `AG-9`: a missing test, not a mutation.

**Built and unverified** — 5 mutation rows `AS-1`…`AS-5` and 4 browser rows `A3-25`…`A3-28`, **none
run**, per §65. `AS-2` is the one to run first: its failure strands data on a live instance rather
than merely refusing something. `A3-27` needs a grant that already exists and therefore cannot be
created through the UI — the row says so rather than being quietly dropped.

**Gates.** `./gradlew build` BUILD SUCCESSFUL — **2221 tests across 472 result classes, 0 failures**
(§133's 2217). `npm run build` clean · `npm run lint` 0 warnings · `npm run test:run`
**1177/1177 across 90 files** (none added — one rewritten).
