# §80 — Reviewer assignment: the request nothing recorded, and the table that is not part of the record

**Backend + frontend, 2026-08-03.** §79 named this on its way out and §77 named it before that:
*"reviewer assignment is now the only unbuilt half of §6g's apparatus."* It is built.

## What was missing, and how the gap was visible from two directions

`review_participation` has recorded who **spoke** since §76. Nothing has ever recorded who was
**asked**. §6g's Mechanics list is explicit that these are two facts —

> **Reviewers** — assign a user *or* a team (§6b); any member of the team can action it.

— and the consequence of holding only the first is that the workflow §76–§79 built has **no inbox**:
a review opens, and the people who ought to look at it find out through some other product. §6l
states the same gap from the other end (*"Review requested (§6g) → card for each reviewer"*) and
cannot draw a card for a request nothing stored. `ReviewController`'s own header records the third
sighting — it refuses to serve "all reviews in this project" because *"the pending-reviews queue §6l
wants is a different question (mine, assigned, overdue)"*, a question that had no column behind it.

**This is the first review slice that needed a new table.** §76, §79 and §77 each found their table
already in `V1`; `review_assignment` is not there. That absence is itself the finding: `V1` designed
the record of what happened and never the worklist of what is outstanding, which is exactly the
distinction the rest of this section turns on.

## The slice's one idea: a worklist entry is not an audit record

§6g says a team wants review for *"proving to an auditor that someone competent signed off"*. That
record is `review` plus `review_participation` — who submitted, who spoke, who signed. This table is
the **other** thing: a routing hint, alive only while the review is open, saying whose queue the item
should appear in. Three decisions follow from it, and they are the migration:

- **Unassigning is a hard delete.** No `removed_at`, no tombstone. `ReviewComment` refuses a delete
  for the opposite reason and the pair is worth reading together: a remark is something somebody
  *said*, a request is something somebody was *asked*. Asking the wrong person and correcting it
  should leave nothing behind, because a trail of retracted requests reads as a history of suspicion.
- **A deleted team cascades.** §71 made deleting a team holding `role_assignment` rows a 409 with the
  count, because that cascade would silently revoke authority. **This is the case that rule does not
  cover**, and extending it would have been the easy wrong answer: what cascades away here is a
  request, not a grant, and the review stays visible, reviewable and approvable by exactly the same
  people afterwards. Refusing instead would make a team undeletable until every review it was asked
  to look at had closed — and a review whose only assignee is a dissolved team is precisely the one
  that will never close on its own. `TeamWriteService`'s refusal is deliberately **not** extended.
- **Re-asking somebody already asked writes nothing.** §79's resolution defect in the one place it
  would recur: an unconditional write re-stamps `assigned_by`, so the column kept to record who asked
  would name whoever clicked last rather than the person who decided.

## The gate: `*:review`, and no new key

**No key was invented**, on the standing argument: a new key is seeded to nobody, so on every
existing instance it is a 403 no administrator can grant their way out of — §67 refused
`evidence:read` on it and §79 refused `suite:comment`/`plan:comment`. So the question is which
existing key this is. Three readings, and the two cheap ones are both wrong in a way that names a
persona:

| Key | What it would mean | Why not |
|---|---|---|
| `case:comment` | The bottom tier may ask | A Viewer — *"often the client themselves"* — could put work on a contributor's queue. The one thing the commenting tier is defined not to do. |
| `*:approve` | Only Lead+ may ask | Confines the request to the people whose sign-off it exists to **prepare**. A Test Engineer could not ask a colleague to look at their own case before submitting it. |
| **`*:review`** | The reviewing tier may ask | Asking somebody to look at an artifact is a statement about the review's progress — §79's exact argument for resolving a thread. |

**The mutation is the proof and it is a pair**, queued rather than run: swapping to the approve key
reddens exactly one test (`ENGINEER` holds `plan:review` and not `plan:approve`), swapping to the
comment key reddens exactly one *different* test (the Viewer). Both must redden, because the key must
be neither the tier below nor the tier above — one mutation alone would leave half the claim untested.
This is the shape §71 could not achieve for `company:teams`, where `V3` grants both candidate keys to
identical roles; here the seeded catalogue separates them and the personas already existed in the
fixture.

The gate is decided by the **row** — `ProjectService.writeGrantedOnTarget`, §69's seam, sixth
consumer — so a review that does not resolve is 404 before any permission is named.

## The assignee is validated against project **visibility**, not company membership

`assignee_user_id` references instance-level `app_user`, so a **sibling company's** account satisfies
the foreign key, the company column, RLS and every constraint — §71's team-membership defect one
table over, and §64's family a fifth time. Unchecked, it is *stored*.

The bar is `ProjectAccess.canView`, and choosing it over §71's company-membership check is the
substance: a colleague in this company holding no grant on this project **cannot open the artifact**,
so asking them produces a request that sits on their list for ever. Requiring the assignee to hold
`*:review` was considered and refused — a grant can be given after the fact, and a check against it
would make the request stale the moment somebody's role changed; the assignment is a request, not an
authorization.

The two refusals carry the **same sentence** whether the id names nobody or names somebody
unreachable. Telling them apart makes the endpoint an oracle for which accounts exist on the
instance, answerable by anyone holding `*:review` on one project (§6t, and §79's identical call about
its two parent refusals). The test asserts the two messages are byte-identical rather than trusting
the source to keep them so.

**The submitter is refused, and the rule is derived rather than invented.** `standingLgtms` already
skips the submitter's own LGTM, on the stated ground that a peer review by the person who asked for
it is not a second opinion — so a request naming the submitter is one the product will ignore *by
construction*, an entry on a list that can never be discharged. Note this is the **submitter**, not
the artifact's author: on a case written by an engineer and put forward by their lead, the engineer
is a perfectly good reviewer and the lead is not.

## `TeamDirectory` — the third port, and why it is not a method on `PeopleDirectory`

`team` lives in `identity`; `project` may depend on `tenancy` only. The package's own comment
prescribes the remedy — *"if Modulith ever fails on this edge, the fix is a port, never a wider
allowedDependencies"* — and this is its third instance after `ProjectAccess` and `PeopleDirectory`.

Folding `resolveTeams` into `PeopleDirectory` was refused. That port's one rule is plan §6u's, that a
person's name is their **company membership's** name; a team has no membership row and no per-client
label, so one interface would hold two unrelated resolution rules and invite a caller to assume §6u
applies to both. Two small ports beat one that has to explain itself.

It has **two** methods on purpose. `resolve` tolerates a missing team (a render does); `find` is what
a **write** calls, because a write must refuse one or the product stores a `team_id` it never checked
— §64's `sharedStepId`, and "ask `resolve` with one id and check the map" is exactly how a validation
stops looking like one.

What the port must **not** answer is membership, and nothing asks. §6g's *"any member of the team can
action it"* is a statement about who may act, which `PermissionEvaluator` already answers by resolving
a caller's teams inside every one of its five union queries (§71). A second path expanding a team into
user ids would be the second authorization-resolution path plan §6b names, arriving in the module
least entitled to hold one — which is also why a team assignment is **not** expanded into per-member
rows at assignment time: membership moves, and expanding it would freeze the team as it was on the day
somebody clicked.

## `V25`, and the uniqueness that a plain constraint cannot express

One table with two nullable columns under `num_nonnulls(...) = 1` — `role_assignment`'s existing shape
for the identical "a user or a team" question, so this is the schema's own answer rather than a new
one. Two tables would mean a UNION at every read and a panel rendering two lists that mean one thing.

**The uniqueness is two partial indexes, not one plain one.** A plain
`UNIQUE (company_id, review_id, assignee_user_id, assignee_team_id)` enforces **nothing**: every team
row has a NULL user, NULL is never equal to NULL, so the same team could be asked a hundred times and
the constraint would admit all of them. `V20`'s `COALESCE(parent_id, nil)` is the same trap solved the
other way; here the two sides are genuinely independent, so one partial index per side is the honest
spelling. `company_id` leads all three indexes per ERD §0.1, which `SchemaConventionTest` fails the
build on. The policy count moves **57 → 58**.

A fourth index is added **before its query**: `(company_id, assignee_user_id)` for the
"what is on *this person's* list" scan §6l's board and the pending-review queue both need. It costs
one index on a table with tens of rows per project now, and a migration on the slice that can least
afford one later — the queue being the first thing that will be built on this table.

## The three findings

**The contract check caught a slip the whole rest of the pipeline was happy with.**
`ReviewAssignmentInput` shipped with **no `required` array** — deliberately, on the reasoning that
neither field is always present — and `ContractConventionTest` went red. It was right and the
reasoning was wrong: leaving them optional says *"sometimes absent"*, and the server cannot tell an
absent field from an explicit null, so **"ask this person and nobody else" would be expressible only
by omission** and a client that dropped a field by accident would be indistinguishable from one
making a choice. Both are now required **and** nullable — `RoleAssignmentInput.projectId`'s shape one
module over, and for its stated reason. Nothing else in the pipeline noticed: `npm run build`,
`npm run lint` and all 640 frontend tests were green against the wrong contract, because an
over-optional generated type is exactly the thing that compiles.

The gate also caught something about *this session*: `./gradlew build 2>&1 | tail -40` reports the
exit code of `tail`, not of gradle. The first "green" backend run was a **BUILD FAILED** read as a
pass. Run it bare, or append `; echo $?`.

**The frontend fixture caught the contract change exactly as §79 predicted it would.** §79 deleted
`} as Review` from `ReviewPanel.test.tsx`'s factory after adding `comments` turned all eleven of that
file's tests red at runtime with zero type errors. Adding `assignees` this time was **one build error
in one place**, before a single test ran. The repair paid for itself one slice later, which is worth
recording because the cost of that kind of change is usually argued about in the abstract.

**Four of the new frontend tests failed on the first run, and all four for §66's reason.** The barrier
was `expect(getByTestId('Review.Assign.Choice')).toBeEnabled()` — and the test id sits on MUI's Select
**display `div`**, which is not a form element, so `toBeEnabled()` is satisfied immediately and the
click landed on a control that was still disabled while `useCan` was pending. §71's hidden-native-input
trap and §66's passes-against-the-pending-state, meeting in one line. The barrier is now derived:
`useProjectMembers` is called with the gate's own `allowed` as its `enabled`, so **the request for
`/members` firing at all is the proof the permission came back held**. A fifth test failed for the
plainer version of the same thing — MUI's default Chip delete icon is an internal component with no
identifier, so a test could find the chip and have nothing to click; the icon is now passed explicitly
so it can carry one.

**A claim in a doc comment was false when written, and was fixed rather than deleted.** `Assignees`'
header said the picker filters the submitter out; the first draft filtered only people already asked.
Implementing it was the right repair — the server refuses the submitter, so offering them is offering
a 400, and a picker that can produce a field error is the one thing it must not be.

## The frontend

An **Asked to review** section above the verdict trail: chips for each request (person icon, group
icon for a team), each with a ✕ under the same `*:review` gate, and one select of project members and
teams plus an **Ask** button.

**Asked and spoke are two lists and are deliberately not merged.** The tidy-up is wrong in both
directions: somebody may give a verdict without ever being asked (§6g's peer review is open to the
tier, not to a nominated few), and a request nobody has answered is precisely the row a lead opens
this panel to find — merged, it renders as "a person with no verdict", which is also what somebody who
was never asked looks like.

The picker is built from `useProjectMembers`, the server's own *"people who can be given work in this
project"* list, which is a **subset** of the assignment rule (a Company Admin sees the project without
holding a grant on it, so they are assignable and not offered). A picker narrower than the rule never
produces a 400; the reverse would be a form a user fills in correctly and cannot submit.

The hook takes `{ userId } | { teamId }` as a **union**, so the server's one field error — naming
neither or both — is unrepresentable on the client. §31's disable-don't-hide at the level of the type,
the narrowing `useRecordReviewVerdict` already applies to its verdict.

The mock's fixtures carry the awkward set on purpose: a person who was asked **and** spoke, a team, a
person who has said nothing, and **no** assignees at all on the rejected case review — most reviews on
a real instance were never assigned to anybody, and a fixture set where every review has assignees is
§72's finding waiting to happen.

## Gates

`./gradlew build` — **1476 tests, 0 failures** (1458 → 1476: 17 written in `ReviewEndpointTest`,
plus one generated by the contract checks from the two new path items). `npm run build` clean,
`npm run lint` 0 warnings, `npm run test:run` **640/640** (627 → 640: **9 written, 4 generated**).

Those four are §70's machinery working unattended: the contract-hygiene checks discover one case per
`api.*` call site through the TypeScript compiler, so `useAssignReviewer` and `useUnassignReviewer`
produced two cases each — and the `api.del` one is checked only because §70 found that `api.delete`
never existed and eight call sites had been invisible.

**Zero mutations run inline** — the policy, followed. Eight are queued in
`docs/verification/backend-mutation-checks.md`, and the pair to run first is the two gate swaps,
because neither means anything without the other.

## Not browser-verified

The fourteenth slice in the deferred queue; 14 rows in `docs/verification/frontend-browser-checks.md`.
**Rows 3 and 4 are live-only and are the rows the slice turns on** — mock mode signs in as one persona
holding every key, so neither the Viewer who may not ask nor the Test Engineer who may can be seen
there. Run §77 row 2 first: every row presupposes a project with review switched on.

## What this leaves for the next slice, named rather than implied

**The reviewer's inbox.** §6l's *"Review requested (§6g) → card for each reviewer"* is now buildable
and is not built: nothing outside this panel surfaces a request, so a person asked to review finds out
by opening the artifact. `ReviewController` refuses "all reviews in this project" by name and says why
— the queue is a different question (mine, assigned, overdue) needing the subject **expanded**, which
is the interesting part: expanding a `(type, id)` triple means reading `repository` and `execution`
from `project`, which this module may not do. So the queue is either a fourth port, a read model
somewhere else, or the domain event §76 refused. `ix_review_assignment_assignee_user` exists so that
slice does not open with a migration.

**Notifications.** §6g's *"review requested / approved / rejected / comment added, through the §6b
channels"* has now had a trigger for every one of those four events since this slice, and there is no
channel. Mail is off by default (§59) and the decision to leave it off is unchanged.

**The gating rules** — require APPROVED before a case joins a run, or before a plan starts — remain
their own slice with their own table of refusals, and §6g itself calls them *"powerful and genuinely
obstructive"*.

Still latent, unchanged: **`@Version` on `TestRun` and `work.WorkItem`** (§74), and
**`project_settings.lock_version` with nothing sending an `If-Match`** (§77). And §79's own residual:
**the closed-review path on the comment resolution endpoint still has no test of its own** — the new
assignment pair has one on each side, which is the repair §79 asked for applied to the new code rather
than retrofitted to the old.
