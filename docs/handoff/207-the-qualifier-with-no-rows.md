# §207 — The qualifier with no rows, and the exclusion that outlived its reason

**One phase.** `V51`, the roles spec, `openapi.yaml`, both generated `schema` copies, nine javadoc
sites and one comment block in `backend/src`, four prose sites in `frontend/src`, `README.md` and
`CLAUDE.md`. **No new permission** — the catalogue is still 55 keys — and **no code path was added
or deleted**: three grants moved, and everything else is the prose that argued for where they used
to be.

Not a defect. A **product requirement**, stated on 2026-08-14:

> CRUD on test plan, cases and steps can be done by any user that is not a viewer, no matter who
> first created it.

---

## What was actually there, which was less than the sentence implies

The requirement reads like a sweeping change and is mostly a confirmation. Before touching anything,
the surface it names had **exactly one authorship gate**:

| | before | after |
|---|---|---|
| `case:delete` | `OWN` for Test Engineer | ✓ |
| `evidence:delete` | `OWN` for Test Engineer | ✓ |
| `plan:delete` | **not held by Test Engineer at all** | ✓ |
| `case:create` · `case:edit` · `plan:create` · `plan:edit` | ✓, unqualified | unchanged |

**Steps have no permission key and never did.** A step is part of its case, written through
`CaseUpdateService`, gated by `case:edit`. Recorded because the next person to read the requirement
will go looking for a `step:*` key.

And the two halves are **different kinds of gate**, which is the distinction the whole slice turns
on:

- `case:delete` / `evidence:delete` were an **authorship** gate — the `OWN` qualifier, resolved
  against `created_by` at check time.
- `plan:delete` was a **tier** gate — Test Engineer simply did not hold the key, so there was no
  qualifier to remove.

`case:edit` was already de-qualified, on 2026-07-31, for the very argument this slice extends: *a
Test Engineer who spots a wrong expected result in a colleague's case could not fix it, in a product
whose entire subject is a shared test repository.* That reversal stopped at editing, on "editing is
producing, deleting is unmaking". **The distinction does not survive contact with the workflow** —
the duplicate a colleague left behind last quarter is precisely the row somebody else has to remove,
and routing every such cleanup through a lead is the friction §A3.5 exists to avoid. Soft delete,
which all of these tables have, is what keeps it recoverable. The qualifier was the wrong instrument
for that job.

---

## 1. `OWN` now has zero rows, and that is the interesting part

`V51` is the exact inverse of `V12`'s only `UPDATE`, plus one `INSERT`. After it **no grant in the
catalogue carries `OWN`** — while the value stays in the `CHECK` constraint, `PermissionQualifier.OWN`
stays in the enum, `IdentityProjectAccess.canDoOnAuthored` still resolves it, `CaseDeleteService`
still routes through the authorship gate, and the SPA still passes `authorId` at every delete site.

**Loaded and unused, deliberately.** Removing it was considered and refused:

1. **The qualifier is data.** Deleting the arm is what would make a future `OWN` grant fail **open**
   at whichever gate forgot it. Keeping it is what makes "a grant means what the row says" true.
2. `PLAN_SCOPE` (`V19`) is the same mechanism and *is* populated, so the column and the resolution
   seam are earning their keep regardless. Removal would save one enum member and one switch arm.
3. Rebuilding "only what you wrote" later costs a migration, a gate change and a contract change.
   Keeping it costs a comment.

**An empty set proves nothing on its own** — a seeding bug that dropped every `OWN` row and a
decision that removed the only two look identical from the database. So
`SeedDataTest.ownIsCarriedByNoGrant` asserts it in **both** directions: no grant carries `OWN`,
*and* the distinct qualifiers in play are exactly `ALL` and `PLAN_SCOPE`. Without that second half a
typo in the column name would make the first half pass.

---

## 2. The exclusion that outlived its own reason — and the test shape that caught it

This is the part worth carrying forward.

`Agent` (`V40`) is the token-only ceiling for MCP and CLI access. It **enumerates** its 23 keys, so
Test Engineer gaining `plan:delete` gave no agent anything. The data was never at risk.

Two things were:

**`V40` justified dropping `case:delete` and `evidence:delete` by an argument this migration deletes
the premise of** — *"`OWN` resolves against `created_by` while a token's owner is
`api_token.owner_id`, so the agent would delete what the human authored."* True, load-bearing, and
now meaningless: there is no `OWN`. The exclusion is correct and its stated reason evaporated
underneath it. **The decision was re-taken rather than assumed** (this is the point at which the user
was asked, and answered: *MCP can only create, read and edit, no delete*), and the new reason is the
plainer one that was always underneath — an agent creates, reads and edits; it does not delete.

**`plan:delete` was excluded only by inheritance.** `V40` listed it under *"never held, inherited
from Test Engineer's own boundary"* — which excludes a key for exactly as long as the contributor
role does not hold it. The moment Test Engineer gained it, that clause stopped excluding anything.
`Agent` is Test Engineer **minus five** from here on, and `plan:delete` had to be promoted into the
explicit drop table.

**Nothing about that promotion was noticed by reading.** It failed the build, because
`agentIsTestEngineerMinusThree` was written as a **delta** check and not a subset check, for a reason
its own comment stated in 2026-08-08:

> a permission added to Test Engineer by a future migration lands in a credential's ceiling if this
> is a subset check, and shows up here as a failure if it is a delta check.

That is the check working, two sections and six days later, on the exact scenario it named. **A
subset check would have stayed green while a security-relevant justification quietly became
false** — no widened grant, no failing test, and a spec paragraph that had stopped being true. It is
now `agentIsTestEngineerMinusFive`.

**The lesson, generalised:** when a rule is stated as *"X minus the things that…"*, assert the
**delta**, never the subset. The subset version cannot distinguish "still correct" from "correct for
a reason that no longer exists."

`Automation Service` (`V41`) is unaffected — it enumerates its six keys and derives nothing from Test
Engineer.

---

## 3. A known incoherence, left standing and written down

**A Test Engineer may now delete a colleague's case and may not deprecate it.**

`LifecycleLadder.permissionFor` charges the **approve** key for the move to `DEPRECATED`, and its
argument was: *retiring is the soft form of a delete, and the delete is Lead-only (`suite:delete`
outright, `case:delete` `OWN`-qualified), so leaving the retire at the edit key would route around
both.* Half that premise is gone. On a **case** the argument now runs backwards — the stronger act
(delete) is cheaper than the weaker one (retire). On a **suite** it still holds, because
`suite:delete` is unchanged.

**Not repaired here, and that is a scope decision rather than an oversight.** The requirement is
about CRUD; moving this gate is a change to the *approval ladder*, which was explicitly out of
scope (author-cannot-approve-own-work stays on, and approving is not CRUD). The repair, when
somebody takes it, is to decide whether retiring is an edit or an approval **on its own terms**
rather than by reference to what a delete costs. Recorded in the javadoc as well, so the surviving
prose is not read as a live argument.

---

## 4. What did not move, each with a reason

| Stayed | Why |
|---|---|
| `suite:delete` · `artifact:delete` | **Shared structure, not authored content.** A suite is the flat collection many people file into (§98); a project-scope artifact is inherited read-only by every plan (§6n). Removing either changes something other people's work hangs off. |
| `run:close` · `result:amend` | **Judgements about the work**, not CRUD on an artifact. |
| Author-cannot-approve-own-work | Authorship *does* still decide one thing, and this is it. The whole point of a second signature is that it is somebody else's. |
| `Agent` · `Automation Service` | Gained nothing. See §2. |
| `createdBy` on the wire | Stopped being load-bearing for authorization; **stays** as a display field. The author is not the owner and the two diverge on reassignment; `CaseVersionPanel` renders it. |

§A3.5's principle was **rewritten, not deleted**: "creative vs destructive" became "content vs shared
structure and judgement". The old framing failed in a specific way — "destructive" collapsed
*unmaking a colleague* (the qualifier's job) with *unmaking shared structure* (the tier's job), so
removing the first read as abandoning the second. The tier line is untouched.

---

## 5. Applied migrations are immutable, so three files still argue the old position

`V12` (which introduced the qualifier and argued it at length), `V40` and `V3`'s section 3 all still
state the pre-`V51` model. **They are not edited.** Flyway checksums an applied migration, so editing
one breaks every already-migrated database — `V12` has exactly one commit in its whole history, and
that is the convention, not an accident.

They are history in the same way `V1`'s `suite_condition` comment is history after `V49`. `V51`'s
header is where a reader arriving at `V12` by grep gets sent, and the roles spec wins wherever they
disagree. The one thing that *could* be restated was restated the way `V49` did it: a fresh
`COMMENT ON COLUMN role_permission.qualifier`, since `COMMENT ON` is replace-only and `\d+` prints
the newest.

---

## 6. The frontend changed no behaviour at all

Worth stating plainly, because it looks like it should have. Every delete affordance gates through
`PermissionGate` on the key, with `authorId` passed alongside; with an `ALL` qualifier the authorship
term is a no-op, so the buttons simply enable. `plan:delete` needed nothing — `PlanStatisticsPage`
already gated on the key, so the grant landing made the button live.

**No frontend test failed either**, and the reason is a small piece of good luck worth noticing:
`useCan.test.tsx`, `PermissionGate.test.tsx`, `CaseDetailPage.test.tsx` and `EvidencePanel.test.tsx`
all **mock** the qualifier (`{ 'case:delete': 'OWN' }`) rather than reading the seeded catalogue. So
they still exercise the retained mechanism, which is exactly the coverage it needs now that no
seeded role reaches it. Their headers say so, so the next reader does not conclude the catalogue
still has an `OWN` grant.

---

## 7. Contract prose is code here

Five description blocks in `openapi.yaml` described the authorship behaviour — `deleteCase`,
`deleteEvidence`, `deletePlan`, the suite delete (two paragraphs of it) and the case-attachment
endpoint. Those
are **copied verbatim into both `schema` files** by `gen:api`, so this is not a documentation edit
that can be deferred: both copies were regenerated and they stay byte-identical, or
`schemaFreshness.test.ts` fails.

---

## Files

| File | What |
|---|---|
| `backend/src/main/resources/db/migration/V51__crud_is_not_authorship_scoped.sql` | **New.** Two `UPDATE`s → `ALL`, one `INSERT` of `plan:delete`, one restated column comment. Data only. |
| `backend/src/test/java/com/tcms/schema/SeedDataTest.java` | `ownIsCarriedByNoGrant` (new, both directions); `agentIsTestEngineerMinusThree` → `…MinusFive`; the Lead-only line loses `plan:delete` and gains a positive assertion of the three V51 grants. |
| `backend/src/test/java/com/tcms/repository/CaseDeleteEndpointTest.java` | The `Authorship` class inverted — a Test Engineer now deletes a colleague's case, an unattributed case is ordinary, and a new test pins that the removal is attributed to the **deleter** (`updated_by`) while `created_by` keeps the author. The orphaned `patchTitle` helper is gone. |
| `backend/src/main/java/com/tcms/repository/CaseDeleteService.java` | Gate unchanged. Header re-based; `NotTheAuthor` documented as retained-but-unreachable. |
| `backend/src/main/java/com/tcms/identity/IdentityProjectAccess.java` | The `OWN` arm documented as unreachable-by-data, with the fail-open argument for keeping it. |
| `…/CaseController.java` · `TestCase.java` · `TestCaseSummaryView.java` · `TestCaseDetailView.java` · `CaseCreateService.java` · `CaseAttachmentService.java` · `SuiteWriteService.java` · `SuiteVersions.java` · `LifecycleLadder.java` | Prose only. `LifecycleLadder` is the one that records a live incoherence rather than a superseded sentence (§3). |
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | **Authoritative.** §A2, §A3 (two rows + the notation legend), §A3.5 rewritten, §A5 rewritten, §A6a re-based to minus-five, one decision-log row. |
| `docs/specs/openapi.yaml` + both `schema` copies | Five description blocks (six hunks); regenerated in both stacks. |
| `frontend/src/auth/useCan.ts` · `CaseDetailPage.tsx` · `CaseDeleteDialog.tsx` · `EvidencePanel.tsx` · two test headers | Prose only. No behaviour change. |
| `README.md` · `CLAUDE.md` | The two matrix copies, the `Agent` bullet, and a new bullet on the empty `OWN` population. |

## Left open

- **The `DEPRECATED` gate** (§3). Named, argued, not fixed.
- **`suite:delete` and `artifact:delete`** remain Lead-only. If the intent was "contributors delete
  anything", these are the two rows to revisit — but they were deliberately excluded here, and
  §A3.5 now carries a reason rather than an inherited habit.
- **The verification queues get eight rows, none run** (§65). `§207 AQ-1..4` in the browser queue —
  the Delete a contributor could not press, on the two screens that now offer it; the lane needs
  **two accounts**, because a Delete that works on your own case proves nothing here. `§207 DQ-1..4`
  in the mutation queue, of which **`DQ-3` is the one that matters**: nothing currently proves the
  *retained* `OWN` machinery still works from seeded data — every test that exercises it mocks the
  qualifier — so `DQ-3` reintroduces an `OWN` grant and requires the endpoint to refuse with the
  authorship sentence, **without a code change**. That is §207's retention argument, and it is
  currently an assertion rather than a measurement. `DQ-4` records the gap from the other side: with
  no `OWN` grant, deleting `CaseDeleteService`'s authorship check reddens **nothing**.
