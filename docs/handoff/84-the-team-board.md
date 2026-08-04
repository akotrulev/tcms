# §84 — the team board: the bound that is the reader's, and the third selector on one operation

**Date:** 2026-08-04 · **Backend** 1513 → **1530** ·
**no migration — `V27` is still free** · policy count unchanged at **58**

§83 built §6l's second board and named the third in its own "what this leaves" list. This is that
one, and it is the last of the three to get an endpoint:

> **Team board** — one team's work (§6b), swimlanes by member or by priority. *(§6l)*

The project board shipped in §53, the personal one in §83, and the team one is the only one of the
three that had no way to be asked for at all. Like §83 it needed **no migration, no table, no
permission key and no new route** — `V27` is still the next free Flyway version, and the policy
count is still 58. What it needed was one query, one generalised filter and one parameter.

## The shape: a third SELECTOR, not a third route

`GET /api/v1/work-items?teamId={uuid}`. §83 added the personal board by *leaving a parameter off*;
this adds the team board by putting one on, and the reason is the same in both directions —
**§6l's three boards differ in which cards are kept and in nothing else.** Same assembly, same
response shape, same sort. A `/teams/{id}/work-items` route would be a second door onto one read
model, and the difference between the two doors would have to be maintained for ever by hand.

So the operation now has three narrowing parameters along two axes:

| | narrows | |
|---|---|---|
| `projectId` | **where** | absent ⇒ every project the caller can see |
| `teamId` / `mine` | **whose** | one of them, never both |

That table is also the whole of the argument for which combinations are legal, below.

## The decision: the bound is the CALLER's project visibility, never the team's

This is the slice. Everything else follows from it.

A team board built from *"cards assigned to members of team T"* is one query, reads correctly, and
is what anybody would write first. What it renders is the **case key, the title and the project name
of every project any member was granted** — to a reader granted none of them. It is §83's defect one
step out and strictly worse: there the leak was to the *assignee* of work in a project they could not
open (this class's defect 4, from the write side, since §53); here the leaked-to party is the
**reader**, who need not be on the team, need not hold any grant, and need only guess a UUID.

So the projects come from `ProjectService.readAllVisible` exactly as the personal board's do, and the
team contributes **nothing but a set of assignees**. `BoardService.teamBoard` resolves the team,
expands it, and then hands the member set to the same filter `mine=true` uses.

**The consequence is stated rather than discovered: two people asking for the same team's board
receive different boards.** That is correct, it is in the contract, and it is not a defect to report.
A team board is *"this team's work, as much of it as you are entitled to see"*. The alternative is a
cross-project leak with a team id for a key.

### The fixture is what makes that provable, and §83's was not enough

§83's lesson applies verbatim and one level up. Its own fixture — three Acme projects, LEAD granted
on two — proves *"the caller's own work outside their grants stays off"*, but it cannot tell
"bounded by the caller" apart from "bounded by the team", because in every row the assignee **is**
the caller. Both bounds give the identical answer, and the leak passes every assertion.

What §84 needed is a **team-mate who can see a project the caller cannot**: `TEAMMATE`, on
`DEV_TEAM` with LEAD, granted on `OTHER_PROJECT` where LEAD holds nothing, with an outstanding
execution there (`TC-11`). And the test comes in a **pair**, which is the part worth copying:

- LEAD's view of `DEV_TEAM` must be exactly `{TC-1, TC-10}` — `TC-11` is hidden.
- `TEAMMATE`'s view of the *same team* must be exactly `{TC-9, TC-11}` — the two cards in the one
  project they can open, `TC-9` being **LEAD's** work in it.

The second half is what stops the first from being vacuous. Without it, a fixture row that silently
failed to insert would make the security assertion pass for the wrong reason — and this class has
already lost a slice to exactly that shape (§19's shared slug, recorded in its own header).

`DEV_TEAM` is deliberately a **second** team rather than more members on §81's `TEAM`: that team is
the subject of `oneCardPerReviewer`, which asserts the *exact* reviewer set on the suite review, so
adding a member would have reddened a passing test for a reason with nothing to do with reviewers.

## A team is expanded at READ time, in `work`, and the port is not widened

§81's precedent, unchanged, and §80's reason unchanged with it — **membership moves**. §80 refused to
expand a team into per-member rows when a review request is *written*, because a stored expansion is
a snapshot of who was on the team the day somebody clicked. The same fact makes expanding at read
time correct: the board is recomputed on every load, so somebody who joins this morning sees the
team's work this morning.

The expansion is `BoardFacts.teamMembers`, beside `pendingReviews`, which already joins
`team_membership` for the same reason. **`TeamDirectory` was not widened**, and its header is
emphatic about why it must not be: it answers *"what team is this"* and never *"who may"*, because a
second expansion of a team into user ids, in the module least entitled to hold one, is plan §6b's
second authorization path. This expansion is **not that question** — nothing here decides what
anybody may do; the caller's own visibility has already bounded the board before the set is applied,
and what the team decides is only *which cards to keep*.

What `TeamDirectory` **is** used for is existence — `find(companyId, teamId)`, its second method,
whose javadoc calls it "the check a write performs before storing the reference". A read that must
refuse an unknown team has the identical need, so this is its second consumer and it needed no new
method.

**Direct membership only, no hierarchy.** `team` has a parent edge (§6k) and a board that walked it
would give a parent team every child team's work — a different board, and one §6l does not describe.
`pendingReviews` joins flat, so two readings of "on the team" in one file would be worse than either
reading. Named in the code rather than left implicit, because it is the first thing somebody will ask
for.

## Four answers that look alike on the wire, and are four different things

| request | answer | why not the others |
|---|---|---|
| unknown `teamId`, or another company's | **404** | `TeamDirectory.find` is company-scoped, so the two are one answer — otherwise this operation is a **company-wide team-existence oracle** answerable by anybody with a session and a guessed UUID (§6t). The fixture puts LEAD in an *Umbra* team to prove membership is not what keeps them out: `team_membership.user_id` references instance-level `app_user`, so the row inserts happily (§71's invisible-grant family). |
| `projectId` the caller cannot reach | **404** | `readVisible`, unchanged. The team narrows the answer; it never widens the gate. |
| a team with **no members** | **200 []** | Reachable, and empty — §83's distinction. |
| members with **no visible work** | **200 []** | Deliberately indistinguishable from the row above: both are "this team has nothing for you", and a client able to tell them apart would be reading team membership out of a board. |

**The ordering of the last two against the second is a trap, and it has a test.** Answering an empty
team *before* the project gate runs is the obvious optimisation — there is nothing to assemble either
way — and it turns "empty team + unreachable project" into a **200 that confirms the project
exists**. So the shortcut lives inside the gated read (`forAssignees`), never in front of it.

The empty set is answered before any board query for §83's instinct rather than its literal reason:
this filter runs in Java, so there is no `IN ()` syntax error waiting: what there is instead is
assembling a company's entire live work set in order to keep none of it, on the request a
newly-created team makes first.

## `teamId` + `mine` is a 400; `teamId` + `projectId` is legal

`unscopedBoardProblem`'s exact inverse, and both are refusals to *guess*:

- **`teamId` with `mine=true` → 400** (`twoBoardsProblem`). They select two different boards. The
  intersection is a fourth board nobody asked for — my own work minus anything the team does not
  hold, which is my own work again if I am on the team and **always empty** if I am not — and the
  client cannot tell which it received. Note what this means for the mutation queue: the refusal is
  **unobservable in the body** for a caller who is on the team, because their cards are a subset of
  the team's. It exists only as a status code, which is why it has a test of its own.
- **`teamId` with `projectId` → legal.** Not the same mistake: §6l asks for a team board with
  *"swimlanes by member"*, which is a team's work inside one project. The two narrow along different
  axes (who, and where) rather than naming two boards, so the conjunction is a coherent request with
  an obvious answer.

## No new permission key, and no membership requirement

The pattern this project has now used seven times: **derive the key, never invent one.** A key
invented here would be seeded to nobody and therefore a 403 no administrator could grant their way
out of (§67's `evidence:read`, §79's `suite:comment`, §80's assignment gate). There is also nothing
for it to protect — every card this board can return is one the caller could already see on a project
board, which is the same argument §83 made for the personal one.

Nor must the caller **belong** to the team, for §6k's reason: *"the chart itself is readable by any
authenticated member of that company"*. A team board a lead's manager cannot open is a board for
nobody, and the work on it is bounded by the reader's own grants either way — which is what makes
that safe rather than lax.

## `present` now filters over a SET, and the personal board is the one-element case

`BoardService.present` filtered on a single `userId`. It now takes a `Set<UUID>` of assignees, with
the `mine` form delegating — `BoardFacts`' project-set decision (§83) said about people, and for its
reason: a second predicate for "is this one of the team's" would be the copy that drifts, in the
direction nobody tests, since only one board would exercise each.

**The filter stays after `assemble`**, and its header's argument is intact and untouched: pushing it
down means four predicates to keep in step, and the one that gets forgotten silently shows somebody
else's work — a leak of attention rather than of data, so nothing would ever fail. The team board
therefore inherits both the reuse *and* §83's recorded cost: **the answer is team-sized, the read is
not.** §6l's *"cap derived generation with sensible limits"* still lands on the generators rather
than the response, and is still not built.

## Overturned: nothing. Confirmed against the code, one by one

Every decision carried into this slice survived contact with the code. Three were checked and are
worth recording as checked rather than assumed:

- **`work_item.assignee_id` REFERENCES `app_user`** (`V1`:1117). §6l's model line says *"assignee —
  user (or team, for an unclaimed pool)"*, and **the team half does not exist**: there is no
  `assignee_team_id`, no column that could hold one, and `WorkItemEdit` validates a single user.
  **Named as not built rather than scoped out silently** — it is a schema change plus a card-shape
  change plus a picker, and it would give the team board a second, structurally different source. It
  is not a team-board follow-up; it is §6l's unclaimed pool, which nothing else asks for either.
- **The team query is company-bounded by RLS, not by assumption.** `team_membership` carries
  `company_id` and is on `V1`'s policy list, so the statement runs inside the caller's transaction
  with `app.current_company` set — and the team id has *additionally* been resolved in this company
  before the query is reached, so a foreign team never gets there. The Umbra-team test pins the
  outer half; §83's `otherCompaniesNeverAggregate` pins the inner.
- **`V27` is still free** and the policy count is still 58. Checked, and queued as a check to
  re-confirm on a migrated database.

## Found and not fixed

- **The two boards are not protected by the same line, and the mutation queue records it.** Dropping
  the grant filter from `ProjectService.visibleProjects` reddens the personal and team boards and
  leaves every `Gates` test green, because a *named* `projectId` goes through `visibleProject`, which
  has its own copy of the filter. Two methods, one rule, and §58's E1 says which way that ends. Not
  touched here: they are both in `ProjectService`, they are both one line, and merging them is a
  change to every project-scoped read in the product rather than to this slice.
- **`visibleProjects` still evaluates `canView` once per project in the company on every load**,
  §83's note unchanged, and the team board is a third caller of it.
- **`audit_event.target_type`'s two spellings** (§72) and **`ConditionRepository.findLibrary`'s
  unmaintained `usage_count`** (§64) are both still there and still nobody's slice.

## What this leaves

**§6l's three boards are now complete** — personal, team and project, one operation, one shape.
What remains in §6l:

- **The other two generators.** `DOC_UPDATE` needs §6i's stale-case flagging, which nothing computes;
  `DEFECT` needs the local defect record, which nothing writes. A feature apiece, not a generator
  apiece.
- **Per-project generator toggles**, and **WIP limits / configurable columns**.
- **§6l's cap on derived generation** (open question #24 promoted it from prudence to a requirement).
  It has been located three times now — on the generators, not the response — and built zero.
- **A team assignee for the unclaimed pool**, above.
- **Notifications** — all four of §6g's events have a trigger and there is no channel.

And beyond §6l, unchanged: **`/reports` / M2.5**, the only dead contract left.

**The frontend has not been touched.** `docs/specs/openapi.yaml` changed, so
`frontend/src/api/schema.d.ts` is **stale** and `npm run gen:api` belongs to the frontend half of
this slice — the single-contract-holder rule.

**Zero mutations run inline** (§65 policy, followed). **Six queued**, in
`docs/verification/backend-mutation-checks.md`; the first is the security row and is the one to run
first.
