# §85 — the team board's screen: the route that is not administration, and the 404 the server refuses to word

**Date:** 2026-08-04 · **Frontend** 682 → **705** · no contract change (the spec moved in §84;
this half ran `npm run gen:api`) · **no new permission key, no gate, no rail item**

§84 shipped `GET /api/v1/work-items?teamId={uuid}` and ended with *"the frontend has not been
touched"*. This is that half. It is the third caller of one `Board` component, the second route
outside the project frame, and — like its backend half — it needed **no new key and no new
concept**: what it needed was one hook, one screen, one link, and two decisions about where the
screen lives and what it is allowed to claim.

## The route: `/teams/:teamId/board`, and the argument is about `/admin`

Two questions had to be answered, and only the second was open.

**Not under `/projects/:projectId`** — §83's reason, unchanged and now applying for the second time.
With `projectId` omitted the cards span every project the reader can open, so any project in the URL
would be an arbitrary one: a breadcrumb naming a project most of the cards are not from, and a rail
switcher that appears to change a board it cannot change.

**Not under `/admin/teams` either, which is the call this slice makes.** That is the natural place —
the team list is the entry point, and `/admin/teams/:teamId/board` needs no new top-level segment.
It is wrong for a reason that only shows up when you write the gate: **every affordance on
`/admin/teams` is gated on `company:teams`, and this board is gated on nothing at all.** §6k makes
the org chart readable by any authenticated member of the company; §84 found there is no key to gate
a team board on and declined to invent one, for the seventh time in this project, because a key
seeded to nobody is a 403 no administrator could grant their way out of. A screen every member may
open, filed under a prefix that reads *administration*, tells the reader the opposite of what is
true — and the URL is the part of a screen people quote to each other in chat.

So it sits **beside** `/admin/teams`, not beneath it: `/teams/:teamId/board`.

That is deliberately the same shape as `/projects/:projectId/board`, and it is **not** the collision
§83 refused when it rejected `/board` for My Work. The defect there was one *name* for two scopes: a
bare `/board` beside `/projects/:projectId/board` leaves the reader to guess which is which. A board
whose subject is named by its own segment is unambiguous at both — `/teams/x/board` and
`/projects/y/board` read as the same noun about two different things, which is what they are.

**No rail item**, unlike My Work. A rail entry needs a team chosen before there is one, and the team
list is where a team is chosen — so the entry point is a **Board** link on every row of
`/admin/teams`, placed **outside** the `PermissionGate` that wraps Members / Edit / Delete rather
than inside it as a fourth button. That placement is the whole gating decision in one line of JSX,
and it is the natural mistake: as a fourth child it compiles, renders identically, and hands a work
view to the two roles that administer the org chart.

## What the screen is not allowed to say

The cards are bounded by the **reader's** project grants, so two people opening the same team board
legitimately see different sets. §84 states that as a design decision; on the client it becomes a
constraint on what may be *rendered*.

There is **no count of the team's work anywhere on this screen** — no "N items", no capacity figure,
no percentage. A number computed from a partial view reads as canonical and is wrong for everybody
except the person looking at it, and a lead who plans against it is planning against their own
access level. The subtitle says whose view it is instead: *"…in every project you can see. Somebody
with different project access will see a different set."* The per-column counts `Board` already draws
stay, because they count what is on screen and claim nothing else.

Two affordances are absent for the reasons the endpoint gives:

- **No filter chips.** `teamId` with `mine=true` is a **400** ("Choose one board"). The refusal is
  unreachable from a UI with no control that can express it — which was the question this slice was
  asked to decide, and the answer is that the client **cannot** express it, so there is no handling
  for it anywhere and none should be added. Worth noting *why* a chip would be especially bad here:
  §84 records that the 400 is **unobservable in the response body** for a caller who is on the team,
  since their own cards are a subset of the team's. A chip that produced it would look, to the person
  most likely to click it, like a filter that sometimes silently did nothing.
- **No project narrowing.** `teamId` + `projectId` **is** legal and is §6l's *"swimlanes by member"*.
  It is not built: a swimlane is a second axis on a grid that currently has one, and shipping the
  filter without the layout would be a project picker answering a question §6l did not ask. The mock
  answers the conjunction anyway (below), so the day a screen sends it is not the day to discover the
  mock had guessed.
- **No "New task"**, as on My Work: a create needs a project this screen does not have.

## The one thing the client has to word itself: the 404

The server's 404 here is **empty-bodied and indistinguishable across its causes on purpose** —
unknown team, another company's team, unreachable project are one answer, or the operation becomes a
company-wide team-existence oracle answerable with a guessed UUID (§6t).

That is right on the wire and useless on screen. `ApiError.detail` falls back to the HTTP status
text when there is no body, so `describeError` would render **"Not found — Not Found"**: a sentence
that says nothing and looks like a bug in our own code.

So `TeamBoardPage` handles the 404 itself, above the `DataRegion`, with a sentence and a way out:
*"No such team — this team doesn't exist, or it belongs to another company. It may have been deleted
since this link was made."*, plus a **Back to teams** button. A stale link is by far the likeliest
cause, and the way out of a stale link is the list.

It keys on `status === 404` and **never on matching a server sentence** — §82's rule from
`RunCreateDialog`'s 409, and here it is safe for an extra reason worth stating: **this hook sends no
`projectId`**, so the operation's other 404 arm cannot be reached from this screen. The client is
entitled to name the team as the missing thing because the team is the only thing it named.

And it has to stay **visibly distinct from `200 []`**, which is the pair §84 built into the contract:
a team with no members and a team whose members have no work this reader can see are deliberately
the same answer, and both are a *reachable* board. The empty state says so in one sentence for both,
without guessing between them — "this team has no members" is a specific, checkable, frequently
wrong thing to put on a screen.

The team's **name** comes from `GET /teams`, which declares no 403 and is readable by any member
(§6k) — a title fetched through an endpoint stricter than the board would leave the screen nameless
for exactly the reader §84 says may open it. It is deliberately **not** in a `DataRegion`: a board
that renders under "Team board" because the org chart failed is strictly better than one that refuses
to render, and a team the list does not contain falls through to the fallback rather than asserting
anything — the board's own 404 is what says "no such team", and two components answering that
question is how they come to disagree.

## `linkFor` was verified, not assumed — and it is right

The brief for this slice named it as the risk: `linkFor` has needed repair in **two consecutive
slices** (§81 fixed *which screen* a card opens, §83 fixed *whose project* — it was reading the
board's rather than the card's, a 404 on the one affordance a card exists to offer). This is the
second cross-project board, so it is the second screen that would expose that class.

**It is correct, and it is correct for the right reason:** §83 removed the route parameter entirely
rather than passing the right one down, so `linkFor(item)` reads `item.projectId` and there is no
route for it to disagree with. Had it still taken a project, this screen would have produced
`/projects//cases/…` — it has no project at all. Verified with its own test on this board rather than
inherited from §83's, because §81's four link tests all passed against §83's defect.

## The mock: two bounds, one of which mock mode structurally cannot hold

`mocks/db/workItems.test.ts` — still the only test asserting the *mock's* own rules, and the file
that has now caught a defect in this module in two separate slices — gained the team board's.

`teamBoard(teamId, projectId?)` mirrors `BoardService.teamBoard`: the team contributes **nothing but
a set of assignees**, membership is expanded at **read** time and **direct members only** (a parent
team inheriting its children's work is a different board, and one §6l does not describe), the filter
runs **after** assembly, and an unknown team returns `undefined` — a 404 — where a team with no
members returns `[]`. The empty-member shortcut lives **inside** the resolved team and **after** the
handler's project gate, which is §84's trap: answering an empty team first turns "empty team +
unreachable project" into a 200 that confirms the project exists.

`personalBoard` and `teamBoard` now share one `assemble()`, which is **the one place the visible
project set is named**. Two copies of that expression is the one that drifts (§58's E1), and it
drifts in the direction where a board quietly grows a project.

### The fixture hole, which was the same one §84 found on the server

Every card in this fixture set was assigned to `users[0]` (the signed-in user) or `users[1]` — and
**both are in Payments Squad**. So *Checkout Squad*'s board was empty, and an implementation that
ignored team membership entirely, or that quietly answered the personal board, would have passed
every assertion anybody thought to write: the team bound and the personal bound gave the same answer.
That is §84's own finding one layer up, and the frontend fixture had the identical hole.

So the fixture gains **one OLB task assigned to Priya Nair (`users[2]`)** — in Checkout Squad, not in
Payments Squad, and **not the signed-in user** — and the assertions come as a **pair**, each half
stopping the other being vacuous:

- Payments Squad's board holds the signed-in user's cards and **not** Priya's.
- Checkout Squad's board holds Priya's and **none** of theirs.

It also stopped `personalBoard`'s *"keeps only the caller's own cards"* being vacuous over stored
tasks, which it was: there had been no foreign one.

### What mock mode structurally cannot prove, stated rather than left to be inferred

**The reader-bound.** `db.projects` **is** the signed-in user's visible set in mock mode — the
fixture carries no project they are barred from — so the server's central decision, that a team board
is bounded by the *reader* and never by the team, is **structural here rather than demonstrated**.
That is §83's recorded limit for the personal board, unchanged, and it is now load-bearing for two
screens. What the fixture does prove is the other bound (membership), that the assembly reads no
project outside the visible list, and that naming a project outside it reaches nothing. The rest is
**browser queue rows 20–22, live only**, and row 20 is the highest-risk row in this slice.

## Found already wrong, and fixed

Nothing was found broken. Two things are worth recording as *checked* rather than assumed:

- **`linkFor`**, above — the named risk, verified correct with a test on the board that would expose
  it rather than trusted from §83.
- **The mock's `GET /work-items` refusals.** Both of §84's are mirrored even though `useTeamBoard`
  can send neither, because a rule only the current client happens to respect is a rule the next
  client breaks (§52). The `teamId` + `projectId` conjunction is answered for the same reason.

One thing was found by the **build** rather than by the runner, which is the working agreement's rule
in miniature: a test helper built its assignee set with `map(item => item.assignee?.id)`, giving a
`Set<string | undefined>` that compares happily against a `string[]` at runtime. All twelve tests
passed; `npm run build` reported two type errors. The runner proves the behaviour, the build proves
the assertions are about what they claim to be.

## Found and not fixed

- **§6l's swimlanes by member.** The `teamId` + `projectId` conjunction is legal, is answered by the
  mock, and has no screen. Named above as a layout rather than a filter.
- **A team assignee for the unclaimed pool** — §84's note, unchanged: `work_item.assignee_id`
  references `app_user`, so the team half of §6l's *"assignee — user (or team)"* does not exist and
  nothing here renders one.
- **`ArtifactsPage`'s plan filter still lives in `useState`** (§61) rather than the URL. Untouched,
  and now the only known deviation from the URL-filter rule.

## Gates

`npm run gen:api` first — §84 changed `openapi.yaml` and left `schema.d.ts` stale, per the
single-contract-holder rule. `npm run build` **clean**, `npm run lint` **0 warnings**,
`npm run test:run` **705/705** (682 → 705: 12 `TeamBoardPage.test.tsx`, 7 in
`mocks/db/workItems.test.ts`, 1 in `TeamsPage.test.tsx`, and **3 generated unattended** by §70's
contract-hygiene checks for the new `api.get` call site — the count moving is a report about those
checks, not a surprise).

## Not browser-verified

The eighteenth slice in the deferred queue. **25 rows** appended to
`docs/verification/frontend-browser-checks.md`, six of them live-only — and the live-only ones are
the ones that matter most here, because the reader-bound is unreachable in mock mode by construction.

**Zero mutations run inline** (§65 policy, followed). **Six frontend rows** queued in
`docs/verification/backend-mutation-checks.md`; the first two are a pair, and the second — deleting
Priya's fixture task — is the one that shows how narrow the margin was: with that row gone, exactly
one assertion reddens, and the team bound becomes untestable again.

## What this leaves

§6l's three boards now have three screens. What remains in §6l is unchanged from §84's list:

- **The other two generators** — `DOC_UPDATE` needs §6i's stale-case flagging, which nothing
  computes; `DEFECT` needs the local defect record, which nothing writes. A feature apiece.
- **Per-project generator toggles**, **WIP limits / configurable columns**, and **§6l's cap on
  derived generation** (located on the generators three times now, built zero times).
- **Swimlanes by member**, above — now the only §6l board behaviour with a server that answers it
  and no client that asks.
- **Notifications** — all four of §6g's events have a trigger and there is no channel.

And beyond §6l: **`/reports` / M2.5**, still the only dead contract, and still a milestone rather
than a slice.
