# §77. Per-project review settings — the row four behaviours were waiting on, and the demotion that must not cost the approve key

**Date:** 2026-08-02 · **Migration:** `V23` · **Next free:** `V24`

§76 named this slice on its way out, and named it as *four things blocked at once*. Re-deriving from
the code (§28's rule) confirmed all four and found a fifth thing nobody had written down: **the ERD
and the plan disagree about where these settings live.**

## What was actually broken

Every one of these was live, shipped, and silent:

| | The state before this slice |
|---|---|
| **The opt-in** | §6g's first sentence is that review is *"enabled per project and per artifact type, off by default"*. There was nowhere to record that, so §76's apparatus applied to **every project unconditionally** from the day it shipped. |
| **`required_peer_reviews`** | A column on every `review` row, written from the database default by `Review.open`, **read by nothing**. `Review`'s own javadoc said so and named this migration as what it was waiting for. |
| **Author cannot approve own work** | §6g calls it *"the single most valuable setting here"*. The product did not have it: whoever submitted an artifact could sign it off themselves. |
| **Edit after approval** | `ReviewPanel` has rendered the *Retain* half — the stale marker — since §76, while the *Re-review* half it is implicitly the alternative to **did not exist**. |

## The decision that was not mine to make: where the settings live

**ERD line 101** gives `company` a `settings JSONB` described as *"per-company policy: review gating,
edit-after-approval mode, health-signal thresholds"*. **Plan §6g** says the opt-in is per project
**and per artifact type**. A company-level column cannot express that — it is the wrong grain, not a
smaller version of the right one — and `CLAUDE.md` gives the ERD authority over entity design, so
this was put to the user rather than decided in a comment.

**Settled: a typed `project_settings` table.** `company.settings` is left exactly as it was — still
unmapped, still with no reader and no writer — and this slice does not claim it.

**Typed columns rather than a JSONB blob**, which is §74's closed-metric-vocabulary argument in a
second place: a misspelled key in a settings blob reads *identically* to a setting nobody configured,
because both are "absent, use the default". `reviewEnabledCase` vs `review_enabled_case` would be a
silent no-op that a release meeting cannot tell from a deliberate choice. Typed columns put that in
front of `ddl-auto: validate` and `SchemaConventionTest`, where it is a failed boot. The cost is that
a fifth setting is a migration — and a new *review subject type* is a migration anyway, because it
needs a ladder of its own, so the grain is honest.

`ReviewSettings.reviewEnabledFor` is a **switch over `ReviewSubjectType`**, not three public
booleans a caller picks from, so a fourth subject type is a compile error under `-Werror` rather than
a silently unreviewed artifact.

## The narrow reading of "off", and why the wide one would have been a catastrophe

**Turning review off suppresses the reviewer apparatus and does not touch the status ladder.** No
review row is opened, no peer verdict can be recorded, no panel renders — and `DRAFT → IN_REVIEW →
APPROVED` keeps working exactly as it has since M1.

The wide reading — review off means `IN_REVIEW` and `APPROVED` are unavailable — is superficially
tidier and would have been a **data-destroying default**. Review is off by default, so on the day
this shipped every existing project would have lost the ability to approve anything, and §62's whole
approval path would have gone dark on every real instance at once. `offDoesNotWithdrawTheLadder`
is the test that pins it.

The same reading decides the mirror case: **switching review off does not close reviews that are
already open.** Those rows record something that happened; a project changing its policy has not
caused everyone who was asked to withdraw. So the guard in `ReviewService.moved` covers only the
**opening** arm.

## The one that looks like a regression and is not

`EditAfterApproval.RE_REVIEW` means an edit to an approved artifact returns it to `DRAFT`. **The
demotion is not gated on `*:approve`** — and §75 established, correctly, that *leaving* an approval
is an approval act, because a Test Engineer must not be able to rescind a lead's sign-off with the
key they use every day. This looks exactly like that hole reopened. Three things have to hold
together for it not to be:

1. **Nobody requested the transition.** §75's defect was a caller *asking for* `DRAFT` on an approved
   artifact and being charged the edit key. Here the caller asked to edit, which §6g says they may,
   and the project declared in advance that editing invalidates approval. Charging the approve key
   would not make the act safer — it would make an approved artifact **uneditable by the people §6g
   expects to edit it**, inverting the setting into a lock.
2. **It is opt-in and off by default.** A project on `RETAIN` is in exactly §75's world with all of
   §75's protection. Turning this on is a project saying its approvals are version-specific — §6g's
   *"strict; right for regulated work"*.
3. **It is recorded.** Its own audit verb (`plan.re_review_required`, `case.re_review_required`,
   `suite.re_review_required`), never a field on the save's own row, because "case.updated" is the
   most common action in the table and an approval that ended is what somebody comes looking for.

What it genuinely costs is that a Test Engineer can end an approval by editing. **That is the
setting's purpose, not a side effect**, and a project that does not want it has it off. The queued
mutation that gates the demotion on `plan:approve` is flagged as the most important row in the
section: it is the "obvious" reading, it looks like a fix, and the test is the only thing standing
between this slice and a future session applying it.

**Scope stated rather than discovered:** the suite arm covers the suite's own edit (the rename). It
deliberately does **not** fire when a case moves into or out of an approved folder, which also
changes what §6g says a suite review is about — its composition. That needs every case write to know
which suites it just invalidated, and the demotion arriving as a side effect of editing a *different*
artifact. Named, not half-built.

## The gate asymmetry, which is a feature

`PUT` needs `project:settings`; **`GET` needs only project visibility.** Not a relaxation — it is
what makes §6g's first requirement implementable. *"A project that never turns it on should see no
extra UI at all"* is a decision every artifact screen makes for **every reader**, a Viewer included,
and none of them holds `project:settings`. Gating the read on the write key leaves the SPA unable to
find out whether to render the panel, and both ways out are worse: render it always (the requirement,
unimplemented) or infer it from a 409 after somebody clicks. Nothing here is sensitive in the way the
key suggests.

## Two seams, and one of them is why the bulk path is two queries

- **`ProjectSettingsService.effective`** is the one place any consumer learns a project's policy,
  including across module boundaries (`repository` and `execution` both call it). It **tolerates a
  missing row and answers the defaults** — `V23` backfills every project and `ProjectCreateService`
  writes one, so the empty case should be unreachable, but the safe answer is "review off, nothing
  extra rendered" and the unsafe one is a 500 on a case list.
- **`ReviewService.refusesEach`** is the batch, and `refuses` **delegates to it** rather than
  carrying a second copy of the two rules. `CaseBulkService` evaluates up to four hundred cases, and
  asking per row is two queries per case; more importantly, a single-item and a bulk implementation
  of one policy is §58's E1 and is precisely the class of divergence §75 found between
  `BulkOperations.permission` and `PlanLifecycle`.

**The peer count is read from the `review`, never the live setting.** That is the whole reason the
column is on that table: a lead raising the bar from 2 to 4 must not freeze every review already in
flight, whose reviewers were asked under the old rule and some of whom have already spoken.

**It counts each person's *latest* verdict**, in Java rather than SQL, because
`ReviewParticipation` is append-only precisely so relenting is visible in both directions. A
`COUNT(DISTINCT user_id) WHERE verdict = 'LGTM'` counts somebody who said LGTM and then requested
changes — the reading that makes a review trail decorative. The submitter's own LGTM never counts,
whatever the author rule says: the two settings answer different questions, and a peer review by the
person who asked for it is not a second opinion.

## The gate run produced a mutation result for free

**Adding the opt-in reddened 18 of 1419 tests at once**, and 17 of them were the finding rather than
the bug: every test in `ReviewEndpointTest` went red because that class creates projects by direct
insert and therefore gets exactly what a **real newly provisioned project** gets — review off. The
apparatus §76 built correctly stopped existing.

That is §72's fixture rule arriving from the other side. §72's version is *the mock's fixtures must
not sit in a state a fresh install cannot produce*; here the fixture was in exactly the state a fresh
install produces, and it was the **tests** that assumed the feature was on. The fixture now enables
review explicitly and says why, and the two *policy* settings are left permissive there on purpose —
those tests are about the record, and most of them have one persona submit and then approve, which
the default author rule refuses. The policy settings have their own nested classes that turn them on.

The 18th was `SchemaConventionTest`'s policy count, 56 → 57, which is the mechanical check working:
a new company-owned table moves the number deliberately, because the count is what makes the
predicate check non-vacuous.

**`V23`'s policy is spelled with `V4`'s `nullif(...)` predicate, not `V1`'s.** A table created after
`V4` and born with the old spelling would reintroduce, on exactly one table, the reset-GUC 500 that
`V4` exists to close.

## The MUI slot trap, fourth occurrence

`ReviewSettingsPage` was first written with `inputProps=` — MUI's pre-v7 spelling — because it was
written from the shape in my head rather than from a v7 sibling. §71 extracted `lib/muiTestIds.ts`
to end this, §74 recorded it firing a third time through `PlanCreateDialog`, and it fired again here.
`muiTestIds` owns the *shape*; the **prop name is the part it cannot own**, which is the gap. Caught
by `npm run build`, not by the test runner — the working agreement's rule, holding.

## Gates

`./gradlew build` — **1441 tests, 0 failures** (1419 → 1441: 22 written). `npm run build` clean,
`npm run lint` 0 warnings, `npm run test:run` **616/616** (614 → 616: 2 written, plus the 11
repaired above).

**Zero mutations run inline** — the policy as restated on 2026-08-02, followed. Eleven are queued in
`docs/verification/backend-mutation-checks.md`, and the one to run first is the `plan:approve` gate
on the RE_REVIEW demotion.

**All 22 new backend tests passed first time**, which §66 established is the moment to distrust them
rather than the moment to relax — and which is now an argument for running the queue, not for making
an exception to it.

## Not browser-verified

The twelfth slice in the deferred queue; 17 rows in
`docs/verification/frontend-browser-checks.md`. **Row 1 is live-only and unrepeatable**: on a real
instance, the first observable effect of this slice is that §76's panel vanishes from every screen
until somebody turns review on, and that state disappears the moment row 2 runs. Every row in §76's
block now presupposes an enabled project, so the two blocks must be run §77-row-2 first.

## What this leaves for the next slice, named rather than implied

**`review_comment` is still schema with no code**, and `case:comment` is still the one review
permission with no consumer — correctly, since `V3` grants it to **Viewer** while the review tier
goes to Test Engineer and up, which is §6g's three tiers. Threaded, resolvable, anchored comments are
what that table is for, and the panel already has the trail to hang them on.

**Reviewer *assignment* is the other half** — `review_participation` records who *spoke*, not who was
*asked*. §6g wants a user *or a team* assignable, and it is what §6l's board needs before a
pending-review card can exist.

**Two settings §6g names are deliberately not built**: the gating rules (require APPROVED before a
case joins a run, or before a plan starts), which §6g itself calls *"powerful and genuinely
obstructive"* and requires a documented lead override for. That is its own slice and its own table
of refusals.

Still latent from §74: **`@Version` on `TestRun` and `work.WorkItem`**, and the run one is still not
a free repair. New and latent from here: **`project_settings.lock_version` is maintained and nothing
sends an `If-Match`**, so two admins saving the form race silently — annotated now precisely so that
slice does not first have to discover the column was reading `0` for ever.
