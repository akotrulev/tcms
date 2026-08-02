# §76. The review record — the tables three ladders were written for, and the tier below approval

**Date:** 2026-08-02 · **Milestone:** M1.5 (reuse & review) · **Migration:** `V22` · next free is `V23`

`review` and `review_participation` have been in `V1` since the beginning with **no entity, no
native query and no writer of any kind**. §75 named this slice on its way out and named it
correctly, for once — its reasoning was not a "next steps" list but a derivation: after the suite's
ladder landed, **every item-side ladder existed** (a case's §62, a plan's §73, a suite's §75) and
what was left was what §6g puts *inside* `IN_REVIEW`.

Three permission keys get their first consumer in Java at once — `case:review`, `suite:review`,
`plan:review` — the **seventh** instance of the shape §71 turned into a search, and the first time
the keys were left unconsumed *deliberately*: §73 and §75 both recorded that peer review accumulates
on an item without moving it, so the keys belonged to an apparatus that did not exist. It exists now.

`case:comment` stays unconsumed, and that is also correct: `V3` grants it to **Viewer** and
`case:review` to Test Engineer and up. Those are §6g's three tiers — comment < review < approve —
and the comment tier needs `review_comment`, which this slice does not build.

---

## The placement decision, which is most of the slice's design

A review's subject is an **opaque triple**: `subject_type`, `subject_id`, `subject_version_no`, with
no foreign key (`V1` declares none) and no lookup. That is what makes the placement possible at all,
and the question it answers is not obvious:

**Three writers in two modules have to reach the record.** `SuiteStatusService` and
`CaseBulkService` are in `repository`; `PlanStatusService` is in `execution`. Those two modules may
not depend on each other, so a shared review record can only live where **both** may call — one of
the three foundation modules, `tenancy`, `identity` or `project`. Of those, `project` is the one a
review *is* scoped to: `review.project_id` is `NOT NULL`, every gate on it is a project-scope
permission, and its every foreign key (company, project, `app_user`) already points at exactly the
tables plan §3 permits.

**A `review` module of its own was considered and refused.** It needs plan §3's module list amended
and `ModuleStructureTest`'s expected list with it — and then still cannot be *called* by
`repository` or `execution`, because `ArchitectureRules.FOUNDATION_MODULES` forbids naming anything
else in their `allowedDependencies` ("adding a fourth foundation module is a decision, not an
import"). The remaining route is a domain event — the product's **first**; `CompanyScopedEvent` has
no implementations — whose type could live in neither end and would therefore land in `tenancy`, and
which would have to be a *synchronous in-transaction* listener anyway, because an approval whose
review row was lost is precisely the audit hole this record closes. §67 refused a `storage` module
on the same reasoning at smaller scale.

**What `project` costs is that it may depend on `tenancy` only** — its package comment is explicit,
and equally explicit about the remedy: *"if Modulith ever fails on this edge, the fix is a port,
never a wider allowedDependencies."* So names are resolved through `PeopleDirectory`, implemented by
`identity.IdentityPeopleDirectory`. `ProjectAccess` was the first instance of that inversion; this is
the second.

### And writing the third consumer of "what is this person called" found the first two

`repository.CaseOwners` and `execution.ExecutionPeople` were **byte-identical copies** of plan §6u's
naming rule — membership name wins, canonical name is the fallback for somebody offboarded — the
second carrying the comment *"the two classes must not disagree"*. §58's E1 exactly: two
hand-maintained copies under an instruction to keep them in step, surviving only because neither
module can import the other. Both now delegate to the port; what is left in each is the mapping onto
its own module's `UserRefView`, which is a shape rather than a rule.

`CaseOwners`' own header argued against the port shape — correctly, as far as it went: a module that
*may* depend on `identity` should not make `identity` depend on the test repository to hand back a
display name. What it could not know is that `project` would acquire a consumer of its own, at which
point the choice stopped being "port or direct call" and became **"one resolution or three"**.

---

## The rule: the ladder says where the artifact landed, and nothing else

`ReviewService.moved` takes a `ReviewStanding` — `OUTSIDE_REVIEW · IN_REVIEW · APPROVED` — never a
status. The three status enums live in modules `project` cannot import, and each ladder already owns
its own mapping (`LifecycleLadder.standingOf` for the case-and-suite enum, `PlanLifecycle.standingOf`
for the plan's, which has the `ACTIVE`/`CLOSED` arm the other two lack).

**It deliberately does not take where the artifact came *from*.** The open review row is that fact,
and it is the better one: a subject with no open review has nothing to close, whatever its previous
status suggested — and two adjacent same-typed enum parameters are a swap the compiler cannot see,
which is the reason `LifecycleLadder.Keys` is a record.

Three rules, and everything else writes nothing:

| Move | What happens |
|---|---|
| into `IN_REVIEW` | open a review pinned to the version submitted; an already-open one is left exactly as it is |
| out of `IN_REVIEW` → `APPROVED` | close it, and write the `APPROVAL`/`APPROVED` row naming the signer |
| out of `IN_REVIEW` → anything else | close it — **`WITHDRAWN`** if the person taking it back submitted it, **`REJECTED`** otherwise, with an `APPROVAL`/`REJECTED` row for the second |

A move between two states that are both outside review writes nothing, and neither does an approval
on an artifact with no open review. **The second is not defensive**: every artifact approved before
this slice has none, and inventing one retroactively would fabricate a review nobody held.

### `V22` adds a fourth state because a withdrawal is not a rejection

`V1` allowed `IN_REVIEW · APPROVED · REJECTED`, and §6g justifies all three. What `V1` could not
know is that the ladders shipped in §62/§73/§75 put `IN_REVIEW → DRAFT` on the **edit** key — §6g:
submitting for review "needs only edit rights" — so the same downward edge is walked by two
different acts: the submitter taking their own submission back, and a colleague sending it back.
Closing both as `REJECTED` writes **a rejection nobody performed** into the one record a team keeps
to show an auditor who said what.

The distinction is **derived, never sent**: `actorId.equals(review.getCreatedBy())`. A client that
could nominate it could record a rejection as a withdrawal. Written out rather than left to
`equals`, because two absences are in play (§66's shape) and an unattributable withdrawal must not
be credited to whoever is standing there.

`V22`'s other half is the **partial** unique index on `(company_id, subject_type, subject_id) WHERE
state = 'IN_REVIEW'`. The service already guarantees one open review per subject; two leads
submitting the same case in the same instant both read "none open" and both insert, after which no
close can reconcile them — one row stays `IN_REVIEW` for ever on an artifact that is not. §20's and
§21's reason in the same words: an application check with no constraint behind it is a check with no
backstop. **Partial**, because the closed rows are the subject's history and there may be any number.

---

## The advisory tier, and the door it must not be

`POST /projects/{id}/reviews/{reviewId}/participations` takes `LGTM` or `CHANGES_REQUESTED` and
**cannot write an `APPROVAL` row at all** — there is no code path from the endpoint to a state
change. `APPROVED` and `REJECTED` are refused as field errors rather than ignored: accepting either
would be a second, cheaper door to the sign-off §6g gates once, held by everyone with the *advisory*
key.

**The gate is decided by the row**, which is §69's `writeGrantedOnTarget` seam on its **fifth**
consumer: which of the three `*:review` keys this costs is a property of the review's subject type,
so the review resolves first and a review that does not resolve is 404 before any permission is
named. It resolves through `findInProject`, never `findById` — RLS scopes the latter to the
*company*, so a bare lookup accepts a **sibling project's** review, the same family as §64's
`sharedStepId`, §68's classification value and §69's dimension.

Participations are **append-only**, with no uniqueness on (review, user): somebody who requests
changes and later relents leaves both rows, in order. Overwriting would leave a record saying they
always agreed, which is the fact a review trail exists to keep.

`V1`'s column comment says only `APPROVAL` rows may transition the state, and that holds. Its second
half — that creating one requires `*:approve` — is **narrower than what shipped**, deliberately:
approving does cost the approve key, but sending an artifact back does not, because a reviewer who
may not decline is not reviewing anything. Recorded in the ERD rather than left to be rediscovered.

---

## What the record cannot say yet, stated rather than discovered

- **An artifact already `IN_REVIEW` has no row**, on every real instance, until it is re-submitted.
  The panel reads "Not in review" beside a status chip saying In review, and that is correct — the
  record starts when a writer exists. The mock's fixtures carry one artifact in each state for
  exactly this reason.
- **`required_peer_reviews` is mapped, written from the column default, and read by nothing.** §6g's
  "minimum peer reviews before approval is offered" needs the per-project setting that still has
  nowhere to live: `project` has no `settings` column, only `company` does.
- **No author-cannot-approve-own-work rule.** §6g calls it the most valuable setting here and says
  it defaults *on* — which makes it a change to three ladders, not to this record, and it needs the
  same absent setting. Nothing here was built in a way that setting would have to undo.
- **No reviewer assignment.** §6g wants a user *or a team* named as reviewer; `review_participation`
  records who did speak, not who was asked to.
- **No `review_comment`.** Threaded, anchored, resolvable — and the tier `case:comment` grants,
  which is why Viewer holds that key and not `case:review`.

---

## The frontend

`ReviewPanel` is **one component for all three subject types**, because the server keeps one record
for all three: §6g's table says a case, a suite and a plan differ in what review *means*, not in
what the record holds. It renders on the case detail (beside the version history) and on the plan
screen (below the report — most plans are never submitted, and an empty panel above the numbers
would be a permanent header for a feature most teams do not use).

Two decisions worth keeping:

- **It offers no approval, and that is asserted as an absence.** The sign-off lives where the ladder
  lives: the suite tree's menu, the plan header, the bulk dialog.
- **The staleness line is the panel's one piece of reasoning.** The server holds the version the
  review pinned; the client holds the version it is rendering; only the client has both. Without it
  a reviewer's LGTM means nothing after the next save.

The mock mirrors the server's three rules in **one** place (`mocks/db/reviews.ts`), called from all
three status handlers — a second copy inside the mock is how mock mode starts recording a withdrawal
as a rejection on one screen and not on another.

---

## Gates

`./gradlew build` — **1419 tests, 0 failures, 0 skipped** (1397 → 1419: 22 written). `npm run build`
clean, `npm run lint` 0 warnings, `npm run test:run` **610/610** (594 → 610: **11 written + 5
generated**, the 5 being 2 new `api.*` call sites × the 2 per-call-site contract checks plus 1 new
`qs(...)` site — §70's arithmetic, checked rather than noted, on its sixth opportunity).

**Seven mutations run, three queued — and running any of them was a policy breach.** §65's rule is
that mutation checks are deferred *in full*; §72–§75 each ran a handful inline and recorded them as
`✅ RUN`, which is what this slice followed, and the user corrected it on the day: **write the row,
run nothing**. Both queue headers and `CLAUDE.md` now say so. The results are kept below because
they are true and the queue would otherwise re-run them, not because running them was right.

Four backend and three frontend:

| Mutation | Red |
|---|---|
| `reviewPermission()` answers a key every role holds | **exactly 1** |
| the participation endpoint accepts `APPROVED`/`REJECTED` as verdicts | **exactly 2** |
| every send-back is a rejection (`withdrawnByItsSubmitter = false`) | **exactly 1** |
| `moved` never closes an open review | **exactly 6** |
| the panel's gate is hard-coded to `plan:review` | exactly 1 |
| the verdict form renders on a closed review | exactly 1 |
| the staleness warning renders unconditionally | exactly 1 |

**The fifth one is the one to read, and it failed to redden on the first attempt.** The test asserted
`waitFor(() => expect(button).toBeDisabled())` — which `useCan` satisfies *while pending*, before any
permission has resolved — so a panel with a hard-coded key passed it. §66's finding, in this file's
own tests, caught only by running the mutation. Rewritten as a **positive** assertion (`toBeEnabled`
cannot pass while pending) plus a negative one barriered on `PermissionGate`'s denial wrapper, whose
`role="none"` span appears only once the verdict genuinely resolved (§65's `Gate.pending` — "not yet"
is not "no").

---

## Not browser-verified

The eleventh slice in the deferred queue. Rows are in
`docs/verification/frontend-browser-checks.md`, and **row 1 is live-only and unrepeatable**: on a
real instance no artifact has a review row at all, including the ones already `IN_REVIEW`, and that
state disappears the moment anything is submitted.

---

## What this leaves for the next slice, named rather than implied

**The per-project settings row is now blocking three separate things at once**, which is a stronger
argument for it than it had before this slice: §6g's review opt-in (so a project that never turns
review on sees no panel), the author-cannot-approve-own-work rule, the minimum-peer-reviews count,
and the edit-after-approval mode (Retain + stale marker vs Re-review) that `ReviewPanel`'s warning
currently *describes* without anything acting on it. `project` has no `settings` column and
`company` does; that is a `V23` and a decision about which of the two owns each setting.

After that, `review_comment` and `case:comment` — the tier Viewer holds and cannot use — and
reviewer *assignment*, which is what turns "who spoke" into "who was asked" and is the half §6l's
board needs before a pending-review card can exist.

Still latent from §74: **`@Version` on `TestRun` and `work.WorkItem`**, and the run one is still not
a free repair.
