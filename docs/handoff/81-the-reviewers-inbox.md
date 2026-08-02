# §81 — The reviewer's inbox: the query no module could write, and the mock that invented the feature

**Date:** 2026-08-03 · Backend (`work`) + frontend · no migration

§80 ended by naming this: *"nothing outside this panel surfaces a request, so a person asked to
review finds out by opening the artifact."* §6l names the same gap from the other end and has since
the plan was written — *"Review requested (§6g) → card for each reviewer"* — and `ReviewController`'s
own header records the refusal in the middle, declining "all reviews in this project" because the
queue *is a different question* and needs the subject **expanded**.

The slice is that expansion, and the interesting part is where it turned out to belong.

## The placement was already decided, three slices before the question was asked

§80 left three options on the table: a fourth port in `project`, a read model somewhere else, or the
domain event §76 refused to invent. It is the second, and not by a narrow margin — **the module was
already built, the card kind was already in the contract, and the package comment already said the
generator was missing and why.**

`work` is §6l's board. Its package comment has said since §53 that three of the five derived-item
generators are deliberately absent, `REVIEW` first among them, *"needs the review workflow (§6g)"* —
which §76–§80 then built. And the module's `allowedDependencies` are `tenancy`, `project` and
`identity`, with one class, `BoardFacts`, holding every statement that names a table belonging to a
module it may not import.

That matters because of what the query is:

```sql
review  ⋈  review_assignment          -- project
        ⋈  team_membership            -- identity
        ⋈  test_case | suite | test_plan   -- repository, execution
```

**Four tables, three modules, and no module may write it.** `project` owns the review record and may
depend on `tenancy` alone — it cannot see two of the three. `repository` and `execution` each hold
one third of the subject expansion and cannot see each other. `work` can see all of them, and
`BoardFacts` is the file where that permission is already concentrated. So the inbox is not merely
*conveniently* on the board; the board is the only place in this architecture where it can be
assembled without adding a port, a module or the product's first domain event.

The reverse reading is worth stating too, because it is what makes this a design rather than a
coincidence: **a queue endpoint on `ReviewController` would have had to invent one of those three**,
and would then have served a list that duplicates the board's — two screens answering "what is on my
plate", diverging on the first change to either.

## A team is expanded at read time, which is the mirror image of §80's refusal

§80 refused to expand a team into per-member rows when the request is **written**, and wrote down
why: *"membership moves"*. A stored expansion is a snapshot of who was on the team the day somebody
clicked, and it goes wrong silently and permanently.

The same fact makes expanding it on **read** correct. The card set is recomputed on every board load,
so somebody who joins the team tomorrow finds the request waiting and somebody who leaves stops
seeing it — which is §6g's *"any member of the team can action it"*, implemented rather than
declared. It is the derived-item rule doing exactly the work it was designed for: §6l's central
decision is that a projection's state is computed from its source on every read, and team membership
is part of that source.

Two rules follow, and each has a test:

- **`UNION`, not `UNION ALL`.** Somebody asked directly *and* through a team they belong to has been
  asked **once**. Two cards would be the board telling one person they owe two verdicts on one
  artifact.
- **One card per *reviewer*, not per review.** §6l's words. On the project board a review asked of a
  three-person team is three cards, one per person, and that is what makes a personal board possible
  at all — `mine=true` filters by assignee, so a shared card would appear on nobody's. Writing the
  set assertion against the project board is what surfaced this: the first version expected one
  `TS-1` card and found two, both correct.

## Three predicates, and each removes a card that would otherwise never leave

The generator's substance is which rows become a card, since the slice adds no gate and no key — the
board's existing read gate covers it, and §6g's tiers are already enforced everywhere a verdict can
be given.

1. **`review.state = 'IN_REVIEW'`.** Unassigning is the only thing that deletes a `review_assignment`
   row (§80's hard delete), so **every request ever made is still in the table**. Without this the
   board carries all of them for ever — the append-only-log failure §6l names for executions,
   arriving through a different door.
2. **No participation row by that person.** §6l's *"execution completed → card leaves"*, said about a
   verdict. Note what it does **not** say: the card leaves when they have **spoken**, not when they
   agreed. Somebody who requested changes has done what was asked of them and the ball is with the
   author; leaving the card up would make "changes requested" read as work the reviewer still owes,
   on the board they use to decide what to do next.
3. **The subject still exists.** Three `LEFT JOIN`s and a `COALESCE(...) IS NOT NULL`, because one
   query serves all three subject types precisely *because* the triple has no FK — so nothing in the
   schema removes a review when its case is soft-deleted, and only the join can.

The third is also the one that changed how it is tested. `doesNotContain("TC-8")` passes cleanly
against the broken version, because the review of a deleted case joins to three NULLs and produces a
card with **no key and no title** — a blank row on the board. The test asserts the **exact set**
instead, which is the only assertion that can see it.

## `sourceType` and `sourceId` name the artifact, not the review

The other two generators put the thing the card mirrors in those fields, and this one looks like it
breaks the pattern. It does not: what those fields have always been for is **what the card opens**,
and a review has no URL of its own — §6g's panel renders on the case's, suite's or plan's own page,
so navigating to the artifact *is* navigating to the review. A `sourceId` naming the review would be
an id the SPA can do nothing with, on the one card whose whole purpose is to be clicked.

The card's own identity keeps the review: `review:{reviewId}:{userId}`, because the same review is a
card for each reviewer and a board showing three of them needs three ids. That is the one derived id
that is **not** prefix-plus-one-UUID, so the `UUID.fromString`-throws accident that independently
guards the other two prefixes no longer applies — the prefix check in `isDerived` is the only thing
between a review card and a `PATCH`, and its own constant says so.

**And this is why the contract needed no change at all.** `kind: REVIEW` has been in `openapi.yaml`
since §53, added deliberately ahead of its generator — *"the contract's `kind` enum keeps all six so
the SPA's labels stay complete for the day they land"* — and `sourceType` is a free-form string. A
decision made twenty-eight slices earlier paid for itself here: no `openapi.yaml` edit, no
`gen:api`, no mock-handler signature change.

## The finding: the mock had shipped this feature, wrong, since Phase 1

`mocks/db/workItems.ts` already had a `fromReviews` generator. It drew a card for **every case whose
`lifecycleStatus` was `IN_REVIEW`**, in the **`IN_REVIEW` column**, assigned to the case's **owner**.

Every one of those was wrong against a server that produced **no review cards at all**:

- it drew a card for a review **nobody had been asked to do** — and being asked is the entire content
  of a queue, which is why §80 had to create a table for it;
- it put the card on the **owner's** board: the person whose work is under review, and precisely the
  person the server *refuses* to accept as an assignee (§80 refuses the submitter, derived from
  `standingLgtms` already skipping their own LGTM);
- it used a different column, so the board's shape differed as well as its contents;
- it never drew a suite or a plan, because it read a *case* column — two of §6g's three subject types
  were invisible.

So **mock mode has shown a reviewer inbox no deployment could produce, to the wrong person, from
Phase 1 to §80.** This is §52's "the mock agrees with itself" in its most expensive form: there, two
sides spelled one field differently; here the mock was not agreeing with a server at all, it was
agreeing with an *idea of the feature* — and it looked finished, which is exactly why nobody built
the real one for twenty-eight slices. Nothing could have caught it. `kind: REVIEW` is legitimately in
the contract, so both sides typechecked; the page test supplies its own props; and no handler test
existed, because the mock had never been treated as an implementation worth asserting.

Hence `mocks/db/workItems.test.ts`, the first test in this project that asserts the **mock's** rules
rather than a component's. Four assertions, and the one that matters reddens against the old
generator by name: *no card for TC-8*, a case that is `IN_REVIEW` and whose only review is closed
with nobody assigned.

The fixtures needed one addition and it is §72's rule applied rather than restated: the fixture set
carried a single open review, on a **plan**, so the case-link arm — the one repaired below — would
have been unexercised in the only mode anybody drives. A second open review on `TC-20`, asked of the
current user, is now there.

## The second finding: every card's link was a case search

`BoardPage` routed every linked card to `/cases?q={linkedKey}`. That was right while the only linked
cards were about cases. The inbox draws the first cards about **suites and plans**, and a review of
`TP-5` sent to a case search renders *"no cases match"* — which reads as the plan having been
deleted. A silent wrong answer on the one affordance the card exists to offer.

`linkFor` keys on `sourceType`: a case goes to the case detail, a plan to the plan detail, a suite to
**the case list filtered to it** — because a suite has no page of its own, and is the one subject
type §6g can review with no screen anywhere in the product. `EXECUTION` deliberately keeps the search
fallback: its `sourceId` names the execution, whose route needs a run id the card does not carry.

A third, smaller one in the same file: the refusal sentence called every non-execution derived card
*"a review"*, so a maintenance card told you to go and update a review that does not exist. Harmless
while reviews were not a card kind; wrong in both directions the moment they were. It is a
`Record<WorkItem['kind'], …>` now, so a new kind is a compile error rather than a wrong noun.

## §79's cast repair, carried one file further

`BoardPage.test.tsx`'s card factory ended `} as WorkItem` — the shape §79 deleted from
`ReviewPanel.test.tsx` after a new required field turned eleven tests red at runtime with zero type
errors, and §80 then measured as worth it (adding `assignees` became **one build error in one
place**). The factory now builds a `const base: WorkItem` on its own line and spreads the partial
over it, which typechecks with no cast and makes a new required field a compile error here too.

Not a refactor for its own sake: this file's factory covers sixteen tests, and the contract's
`WorkItem` is the shape every future generator extends.

## What is deliberately not built

- **The cross-project "My Work" board.** §6l calls it the view most people keep open all day, and
  says a per-project-only board fails the person working on three. It does not exist, so **the inbox
  is per project** — a person on three projects checks three boards. That bound is real and stated
  rather than discovered; the endpoint is already instance-scoped (`/work-items?projectId=…`), which
  is the shape that eventually drops the parameter.
- **Per-project generator toggles.** §6l says each generator is individually toggleable and that all
  of them on by default would bury the board. Three are on unconditionally. The review one is the
  first that a project can effectively turn off by other means — a project with review switched off
  (§77) opens no reviews and therefore has no review cards — which is the argument for leaving the
  toggles until a generator exists that a project cannot escape.
- **Drag-to-Done performing the approval.** §6l's eventual behaviour, and `allowedTransitions` is
  empty until it exists. Approving is a gated act with an `If-Match` behind it (§76), so accepting a
  drop here would be a second, unguarded door to the sign-off — exactly what the participation
  endpoint refuses to be, one module over.
- **Notifications.** §6g's four events have had triggers since §80 and there is still no channel.
  Mail is off by default (§59) and that decision is unchanged. The board is now the answer for a
  person who is *looking*; it is not the answer for a person who is not.

## Gates

`./gradlew build` — **1487 tests, 0 failures** (1476 → 1487: 11 written in `BoardEndpointTest`, one
of which absorbed an earlier absence assertion into an exact set). `npm run build` clean, `npm run lint` 0
warnings, `npm run test:run` **649/649** (640 → 649: 5 in `BoardPage.test.tsx`, 4 in the new
`mocks/db/workItems.test.ts`).

**No migration — `V26` is still free.** §80 added `ix_review_assignment_assignee_user` one slice
ahead of its query, saying so in the migration's own comment, and this is the query. The whole point
of that decision was that this slice would not open with a migration, and it did not.

One test failed on the first run and it was the assertion, not the code: the exact-set check was
written against the project board expecting one `TS-1` card and found two — LEAD's and VIEWER's,
both correct, because a team request is a card *per member*. Worth recording as the one place the
implementation was ahead of the person writing the test.

## Not browser-verified

The fifteenth slice in the deferred queue; 11 rows in `docs/verification/frontend-browser-checks.md`,
and **§80's row 14 is struck through there** — it recorded that nothing surfaces on the asked
person's board and called that correct; the expectation is now the opposite, so a pass on it would
mean the inbox is missing.

**Zero mutations run inline** — the policy, followed. Nine are queued in
`docs/verification/backend-mutation-checks.md`, seven backend and two frontend. The one to run first
is the team-expansion arm: VIEWER is named on no assignment row anywhere in the fixture, so if it
reddens nothing, a team request reaches nobody and §6g's "any member can action it" is unimplemented.

## What this leaves for the next slice, named rather than implied

**The gating rules.** §6g's *"require APPROVED before a case joins a run, or before a plan starts"* —
the last unbuilt part of the review workflow, and §6g itself calls them *"powerful and genuinely
obstructive"*. They now have everything they need: the ladders (§73–§75), the record (§76), the
per-project settings row to be optional in (§77), and a queue that shows people what is blocking
them. Their table of refusals is the slice.

**The two remaining board generators**, and they are not equal: `DOC_UPDATE` needs §6i's stale-case
flagging, which nothing computes, while `DEFECT` needs the local defect record, which nothing writes
— so both are a feature apiece rather than a generator apiece.

Still latent, unchanged: **`@Version` on `TestRun` and `work.WorkItem`** (§74),
**`project_settings.lock_version` with nothing sending an `If-Match`** (§77), and §79's residual —
**the closed-review path on the comment resolution endpoint still has no test of its own**.
