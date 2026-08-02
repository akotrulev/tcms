# §79. Review comments — the one key where there are three, and the resolution that named nobody

**Date:** 2026-08-02 · **Migration:** `V24` · **Next free:** `V25`

`review_comment` has been in `V1` since the beginning with **no entity, no query and no writer of
any kind** — the last of the three review tables in that state after §76 gave the other two theirs,
and the one §77 named first on its way out. `case:comment` was the last review permission with **no
consumer in Java**, which §76 and §77 both recorded as *correct* rather than as an omission: `V3`
grants it to **Viewer** while the review tier goes to Test Engineer and up, so it belongs to the
threads and not to a verdict. It has one now.

§6g says why the table exists in one sentence: *"Review without inline comments degrades into Slack
conversations that vanish."*

## The slice is one decision, and it is a permission the catalogue does not have

`*:review` and `*:approve` are **triples** — one key per artifact type. Commenting is **one key**,
named after the artifact it was first written for, and there is no `suite:comment` or
`plan:comment` anywhere in the catalogue. A review's subject is a case, a suite *or* a plan, so the
gate had to answer for all three, and there were only two honest answers:

1. **Add the two missing keys** and key the gate off the subject type, the shape
   `ReviewSubjectType.reviewPermission()` already has.
2. **Read `case:comment` as the tier it is**, and ask it whatever the subject.

The second, and the reason is that the first is a permission **seeded to nobody**. A new key in a
new migration is granted to no role on any existing instance, so commenting on a suite would be a
403 no administrator can grant their way out of — §67's argument against inventing an
`evidence:read`, and §69's against asking a company key at project scope. The reading is also what
the documents say: the roles spec's Review table lists the bottom tier as *"read **an item** in full
and leave threaded comments"* — an item, not a case — against `*:review` and `*:approve` spelled per
type, and §6b's note gives the reason the key exists at all, *"so a Viewer, including the client
themselves, can leave feedback without any write access"*. That is a tier, not a per-artifact
judgement.

**What must stay true for it to be sound is that the tiers are a ladder**: every role that may
review may also comment. That is a property of the seeded catalogue rather than of the line that
asserts it, so `SeedDataTest.everyReviewerIsAlsoACommenter` derives it —
`everyApproverIsAlsoAnEditor`'s shape, and the repair when it fails is to grant the missing
`case:comment`, never to delete the check.

## Resolving costs a different key, and the asymmetry is the design

Writing a remark costs `case:comment`. **Marking a thread dealt with costs the subject's
`*:review`.** Declaring a point addressed is a statement about the review's progress, which belongs
to the tier running the review.

The alternative considered was *"the reviewing tier **or** the comment's own author"* — the shape
`evidence:delete` has, and the one GitHub uses. Refused, because the only caller it adds is a
**Viewer resolving their own feedback**, and a client marking their own point dealt with takes it
off the reviewer's list without anybody having addressed it. A second door needs a caller it is
right for, and this one has none.

The two mutations are the proof and they are a **pair**: swapping either gate for the other's key
reddens **exactly one** test, and they are *different* tests. Both green under one key would mean
the panel's two controls are one control. Both are queued, not run.

**This is also why the two personas exist in `ReviewEndpointTest` at all.** The Viewer holds
`case:comment` and not `plan:review`, which is the only persona in the seeded catalogue that can
tell the keys apart — §71's finding from the good side, where a gate's mutation could redden nothing
because `V3` granted both keys to identical roles.

## The defect the slice shipped in its own first draft

**Resolving an already-resolved thread re-stamped it.** The first implementation wrote
unconditionally, so a second click moved `resolved_at` and handed `resolved_by` to whoever pressed
last — and the two columns exist precisely to record *who dealt with this and when*. A thread
resolved by the lead and re-confirmed by a passer-by would name the passer-by.

The test caught it on the first run, because it was written from the contract sentence
(*"setting a thread to the state it is already in writes nothing"*, `SuiteStatusService`'s call)
rather than from the code. **One of sixteen failed**, which is the useful ratio: §66's rule says all
green first time is the moment to distrust the tests, and a single red on a behaviour that was
written down before it was implemented is the opposite signal.

## `V24`: an act with no actor is not a record

`V1` gave the table `resolved_at` and **no `resolved_by`**. That is enough to grey a thread out and
not enough for the one thing this apparatus exists for — §6g's *"proving to an auditor that someone
competent signed off"*. Every other act in the record names its actor (`review.created_by`,
`approved_by`, `review_participation.user_id`); a thread marked dealt with by nobody is the one
entry an auditor cannot follow up.

The CHECK behind it is **one-directional on purpose**: a resolver with no timestamp is a
half-applied row and is refused, while a timestamp with no resolver is allowed, because that is what
an unattributable resolution looks like and `review.created_by` is nullable for the same reason.

`V24` also adds the read path's index — `(company_id, review_id, created_at)`, the one
`review_participation` got in `V22` and this table could not, because nothing read it. No new table,
so `SchemaConventionTest`'s policy count stays **57**.

## Threads are one level deep, and the parent is validated through the review

`parentId` names the root a reply answers, resolved through
`ReviewCommentRepository.findInReview` — **never `findById`**, which RLS scopes to the *company* and
which therefore accepts a comment on a **sibling project's** review. §64's `sharedStepId`, §68's
classification value and §69's dimension, the same family a fourth time, and here the consequence is
a remark rendering inside a conversation its author never saw. Both refusals — a parent in another
review, and a parent that is itself a reply — are field errors carrying the **same sentence**,
because telling them apart tells a caller that the id they guessed names a real comment somewhere
they cannot see.

Depth is bounded because a tree has no bottom to render and no natural place to resolve. Resolution
is a property of the thread, so resolving a **reply** is a field error rather than a silent no-op.
`ReviewCommentView` is nested — roots carrying their own replies — so the assembly rule lives on the
server rather than in every client, which is §68's `buildValueTree` lesson: two implementations of
one such rule disagreed about orphans. The renderer **surfaces an orphaned reply as a root** for the
same reason it mattered there — a remark nothing draws is one nobody can answer or resolve — though
nothing today can produce one.

## What is deliberately not built

**The anchor.** §6g asks for comments *"anchored to a specific step or field where possible"* and
`V1` has the columns; they stay NULL on every row and are not mapped. The only candidate anchor is a
case's step, whose ids live in `repository.test_step` — a table `project` may not import, for the
reason §76 refused a `review` module. That leaves a port in the `PeopleDirectory` shape (honest,
and real machinery for a display aid) or an id nothing validates, which is **§64's defect written
down in advance**. The port is named in `ReviewComment`'s header so the slice that wants inline
anchors does not start from the cheap version.

**Retracting.** There is no delete and no edit of a comment's body, and `body` is
`updatable = false`. What a retraction needs is a *tombstone* — the row stays, the body is replaced,
the replies survive — because removing a root would orphan every reply under it. Named in
`ReviewCommentEdit`, not built.

**A closed review takes no writes**, comments included: 409, in the one sentence `refuseIfClosed`
now holds for all three write paths. The verdict endpoint's copy of it moved there in the same
commit — §58's E1 at sentence scale, where a drifted copy tells one caller to resubmit and another
nothing.

## The frontend finding: a cast that outlived its contract

`ReviewPanel.test.tsx`'s fixture factory ended `} as Review`. Adding `comments` to the contract
turned **all eleven of that file's tests red at runtime** — `comments.length` of `undefined` — with
**zero type errors**, because the cast told the compiler the object was already a `Review`. §65's
finding in a second file (there it was five files mocking `/me/permissions` through `as never`), and
the repair is the same: delete the cast and let the return type bind, so the next required field is
a build failure in one place rather than eleven confusing runtime ones.

Also swept: the panel's empty trail read **"Nobody has commented yet"** — wording that named the
feature below it two slices before that feature existed. It says *"Nobody has given a verdict yet"*
now, and the comment section has its own empty state.

## Gates

`./gradlew build` — **1458 tests, 0 failures** (1441 → 1458: 17 written, 16 endpoint + 1 seed).
`npm run build` clean, `npm run lint` 0 warnings, `npm run test:run` **627/627** (620 → 627: 7
written, plus the 13 repaired above).

**Zero mutations run inline** — the policy, followed. Seven are queued in
`docs/verification/backend-mutation-checks.md`, and the pair to run first is the two gate swaps,
because they only mean anything together.

## Not browser-verified

The thirteenth slice in the deferred queue; 13 rows in `docs/verification/frontend-browser-checks.md`.
**Row 2 is live-only and is the row the slice turns on** — mock mode signs in as one persona holding
every key, so the Viewer who may write and may not resolve cannot be seen there at all. Run §77 row 2
first: every row here presupposes a project with review switched on.

## What this leaves for the next slice, named rather than implied

**Reviewer *assignment* is now the only unbuilt half of §6g's apparatus.** `review_participation`
records who *spoke*, never who was *asked*; §6g wants a user **or a team** assignable, and it is what
§6l's board needs before a pending-review card can exist. §77 named it and it is still true.

**The gating rules** — require APPROVED before a case joins a run, or before a plan starts — remain
their own slice with their own table of refusals, and §6g itself calls them *"powerful and genuinely
obstructive"*.

**The closed-review path on the resolution endpoint has no test of its own**, which the sixth queued
mutation is written to surface rather than hide: it reddens one where it should redden two. The
repair is a test, not a weaker mutation.

Still latent, unchanged: **`@Version` on `TestRun` and `work.WorkItem`** (§74), and
**`project_settings.lock_version` with nothing sending an `If-Match`** (§77).
