# §71. Team management — the permission with no consumer, and the membership that must be a membership

**Date:** 2026-08-02 · **Branch:** `master` · **Migration:** `V21` · **`V22` is the next free number.**

## What was actually missing

`team` has been in `V1` since the beginning with **readers on three sides and a writer on none**:

- `GET /teams` renders the org chart, and has since Phase 1;
- `RoleGrantService` accepts a team as a grant's subject, validated and tested;
- `PermissionEvaluator` resolves a caller's teams inside **every one of its five union queries**.

Nothing could create one. `provision-company` seeds no team, `Team.create` had **only test
callers**, and `rename`/`reparent` had **none at all** — they were written in anticipation and never
called. So on every real instance that has ever run, `GET /teams` answered `[]`, and
`POST /role-assignments`' team branch — a fully built, fully documented code path — **could not be
reached with a `teamId` that named anything**.

**Mock mode looked finished the whole time**, because `fixtures/core.ts` carries three teams with
members, leads and project chips. That is the "mock agrees with itself" class §52 named, and it is
why this survived to §71: the screen renders beautifully in the environment everybody develops in.

`company:teams` gets its **first consumer in Java**. Seeded by `V3`, granted to Company Admin and
Instance Admin since M0, and never once asked — §62's `case:approve`, §64's `library:manage` and
§69's `company:vocabulary` for the **fourth time**. The pattern is now reliable enough to be a
search: *a permission in the catalogue with no `grep` hit in `src/main` is a feature nobody built.*

## Five endpoints, and two decisions that are not obvious

`POST /teams` · `PUT`/`DELETE /teams/{teamId}` · `PUT`/`DELETE /teams/{teamId}/members/{userId}`.

**`PUT` rather than `PATCH`, for `parentTeamId`.** A record deserialized from JSON reads an absent
field and an explicit `null` identically, so under `PATCH` one of *"leave the parent alone"* and
*"move this to the root"* is unreachable. `PUT /projects/{id}/classification/values/{valueId}` took
the same decision in §69 for the same reason.

**And a team may actually be moved, which is where the two part company.** §69 *refused* to
reparent, because the values below a classification value are what authored cases are classified by,
so moving a subtree silently re-states what every case beneath it asserts. Nothing hangs off a
team's parent edge that way: it is **org-chart structure and never a permission edge** (plan §6k,
and `Team`'s own header) — grants do not cascade through it, so moving a team changes a picture and
confers nothing on anybody. The field that had to be refused there is the field this endpoint exists
for.

## The substance: a team membership must be a company membership

`team_membership.user_id` references `app_user`, which is **instance-level with no RLS**, while
`company_id` is taken from `CompanyContext`. So a **sibling company's user id** satisfies the
foreign key, the company column, the RLS policy and every constraint on the table. Unchecked, the
row is simply stored — the `sharedStepId` (§64) and `parentId` (§69) family, one level up.

What makes it worse than those is what happens next, in two directions at once:

- `RoleAssignmentRepository`'s five projections resolve a user's teams through `team_membership`
  **alone** and never join `company_membership`, so that person immediately holds whatever the team
  holds, in a company they are not a member of;
- `AdminDirectoryService.listTeams` renders them **nowhere**, because `refs` resolves names through
  company membership and **silently drops an id it cannot place**.

So the read side hides the evidence of the write side. **An invisible grant is worse than a leaked
name**, and a status-only test would not have caught it — hence the assertion is *"404, **and no row
was written**"*.

It is a **404 rather than a field error**: an endpoint that distinguished "not in this company" from
"no such account" is an instance-wide membership oracle for guessed uuids (§6t). `INVITED` counts
(onboarding does not wait for acceptance, which is what §55 built the invitation for) and `REMOVED`
does not (that status is exactly what `MembershipRemovalService` uses to make somebody's grants
inert, and re-admitting them through the org chart would undo it silently).

## Delete refuses, and the refusal is the feature

`role_assignment.team_id` is **`ON DELETE CASCADE`**. So deleting a team that holds grants would
revoke every one of them *silently* — and those grants are the entire reason a team is a grant
subject. Mass-revoking people's access as a side effect of tidying an org chart is precisely the
write an administrator must make deliberately: **409 with the count**, revoke first. §69's
live-children refusal is the same decision about a different cascade.

Child teams are refused too, in the other direction: `parent_team_id` has **no** cascade, so the
alternative to refusing is a foreign-key violation surfacing as a 500, or silently promoting
somebody else's sub-teams to the root.

A team with **members** is not refused — membership confers nothing on its own, so there is nothing
to revoke and nothing to warn about. The dialog says so, or an administrator empties the team first
for no reason.

## `V21` — the backstop that was strictly weaker than the check

`V1` shipped `uq_team_name UNIQUE (company_id, name)`, exact-match, at a time when **nothing could
create a team** — so the constraint had never adjudicated a single insert. `TeamEdit` compares
case-insensitively, because "QA" and "qa" are the same team to everyone who reads an org chart.

An application check stricter than its constraint is **a check with no backstop**: two
administrators creating "QA" and "qa" at the same instant both read no conflict, both insert, and
the exact-match constraint lets both through. `V21` drops the constraint and creates a unique index
over `(company_id, lower(name))`. §69 landed `V20` for the identical reason one vocabulary over, and
§58's E1 is the general shape — two copies of one rule under an instruction to keep them in step.

## The mutation that cannot be written, and what was done instead

Both mutations were run inline. The gate reverted to a constant reddens **exactly 6** — every gate
test and nothing else. Dropping the membership check reddens **exactly 2** — the sibling company's
user and the removed member.

**Neither can distinguish `company:teams` from `company:members`.** `V3` grants both to exactly the
same roles, so every persona the suite can build holds both or neither: a mutation swapping one key
for the other reddens **nothing**. The distinction is real in the code and unobservable in the
seeded data — §61's "vacuously true" shape, where a property holds by accident and the accident is
invisible at the place the rule is written.

That is recorded rather than papered over, in two places:

- **`SeedDataTest.teamManagementAndMemberManagementAreStillHeldTogether`** — a computed check that
  fails the day a role holds one without the other. **When it fails, that is the good case**: the
  discrimination has become observable, and the repair is to add that persona to
  `TeamEndpointTest.Gate`, *not* to re-align the seed until the check passes again.
- **`TeamsPage.test.tsx`** carries the only test that can tell the keys apart today, because a
  frontend test can mock a permission set the seed cannot produce. Its headline case is a caller
  holding `company:members` and not `company:teams`, with the "Invite a person" button beside the
  org-chart controls staying **enabled** — which is what makes the two answers visibly different
  rather than merely both-disabled.

## Two things the frontend half is really about

**§68's orphan rule, and it matters more here.** `TeamTree` built its chart by descending from the
roots, so a team whose parent is missing — or two teams pointing at each other — was **silently
dropped**. Cosmetic while the list was read-only; not now, because **the tree is the only place a
team can be edited or deleted**, so a team it does not draw is a team nobody can fix, and the state
that most needs fixing is exactly the one that hides. Orphans and cycle members are surfaced at the
root, as `buildValueTree` does.

**A third copy of one helper was about to be written.** `data-testid` on a MUI slot needs a cast,
and `ClassificationPicker.inputTestId` and `CaseEditorPage.selectTestId` were already two copies of
it — each carrying the *reason* in its own comment, which is the shape §58's E1 and §70 both
punished. Both now import `lib/muiTestIds.ts`. The reason survives with them: a bare `data-testid`
on a MUI `Checkbox` lands on the **wrapper span**, where the checked state is unreadable, so a test
can find the element, click it, and assert nothing (§62, §68).

That trap fired again immediately and in a new way — the first draft put the parent select's test id
on `htmlInput`, which is MUI's **hidden native input**, and the test failed with
`pointer-events: none` on the *affordance* rather than on the assertion. `selectTestId` targets the
display element for exactly that reason.

## Gates

`./gradlew build` clean — **1302 tests, 0 failures, 0 skipped** (§71 adds 35 + 1). `npm run build`
clean, `npm run lint` 0 warnings, `npm run test:run` **552/552** (544 + the 8 new; and the 544 was
534 + **10 generated**, being 5 new `api.*` call sites × the 2 data-driven contract checks — §70's
lesson applied, the count change explained rather than noted).

**NOT browser-verified.** §65's standing policy; §71 is the sixth slice in the frontend queue. Its
rows lead with the **live-only** ones, because mock mode has shown three teams since Phase 1 and
live mode has shown none — the row that matters most is a colleague's project list changing when
they are put in a granted team, which is the path that has existed since M0 and could never be
exercised.

## Left for later, deliberately

- The carry-overs stand: the artifact register's plan filter is still `useState` rather than the URL
  (§61), `ConditionRepository`'s `ORDER BY usageCount` still ranks by a constant (§64), and
  `classification_dimension` still has no writer (§69).
- **`provision-company` still seeds no team**, and should not — inventing a client's org chart is
  §69's "inventing a client's feature taxonomy" argument, and the Feature dimension is seeded empty
  on purpose for it.
- `CompanyMembership.rename()` **still has no caller**, which is the last third of
  `company:members` ("invite, remove, set membership display names") and has been outstanding since
  §55 named it and §56 closed the middle third.
