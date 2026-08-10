# §157 — the first of §156's five, and the signal the panel and the banner already disagreed about

**2026-08-09 · one phase · `backend/` only: no migration, no contract change, no frontend or
`packages/` source.**

§156 ended by naming three things, in the order it found them, and the first was **the five health
derivations** — *"any one of them is a slice; all five is a milestone."* This is one of them:
`STALE_APPROVAL`, §6g's *"case edited since its approval"*.

It is the cheapest of the five and that is why it was taken first. `review` belongs to
`project`, which is a **foundation module every other module may depend on** (plan §3), so the
derivation needed no event, no seam through a forbidden edge and no schema change — one class, one
statement, one call. The four that remain each need a module that cannot be depended on so directly
(`documentation` for `unlinked` and `outdated_spec_changed`) or a comparison against a snapshot
(`condition_drift`), or they need a decision about what "left the project" means (`orphaned`).

## What was actually wrong, which is not what the tally said

`ArchitectureRulesTest.everyMappedColumnHasAWriter` reports a column with no writer, and that
description is true of `case_health.stale_approval` and is the least interesting thing about it.
The interesting thing is that **the product already computed this signal, on one screen, and had
since §76**:

> `movedOn = currentVersionNo !== undefined && currentVersionNo > review.subjectVersionNo`
> — `ReviewPanel.tsx`, rendering *"This case has changed since it was submitted — the review covers
> v1, and you are looking at v2."*

So a case could carry a stale-approval banner on its own detail page while the dashboard's health
panel counted zero stale approvals, the case grid's health facet offered a filter that returned
nothing, and the team board generated no card. Four surfaces, one question, and the only one that
answered it was the one nobody visits to find out *how many*.

That is why the derivation is a **version comparison and not a timestamp comparison**. The
timestamp spelling — `test_case.updated_at > review.approved_at` — is the one a reader will propose,
it is one column shorter, and it disagrees with the banner: a save whose content hash is unchanged
writes no new version (`CaseVersions.decide`) and still moves `updated_at`, so it would report an
approval as stale because somebody opened the case and pressed Save. A second definition of "edited
since" is a screen and a facet that disagree about the same case, which is the state this slice
found and not one to ship a second copy of.

## `ApprovalFacts`, and the seam that is not one

The class is `ExecutionFacts`' shape and deliberately says at its header that it is **not**
`ExecutionFacts`' kind. That class exists because ArchUnit *forbids* the `repository` ↔ `execution`
edge, so the one-file rule is what keeps the crossing findable; this one names a table in `project`,
which is a permitted dependency — `repository` already imports `ReviewStanding` and calls
`ReviewService` from two write paths. The single file is a convention here rather than a boundary,
and it is kept for the half of `ExecutionFacts`' argument that does apply: a second JPA entity
mapped over `review` would give `ddl-auto: validate` two sets of column expectations for one table.

`ReviewService` is right there and is not called, for the reason both statements in `ExecutionFacts`
are written the way they are: **the answer is never needed as a value.** One boolean per live case
per company, on an hourly job, is a fact to fold into the statement that consumes it rather than a
row per case to move through the application.

## Three decisions that are visible only if you look for them

**`max(subject_version_no)`, not the newest sign-off.** Reviews accumulate — approving does not
close the record, and a rescinded approval leaves its row standing — so a case can carry several
`APPROVED` rows. The question the signal asks is whether the content the case shows *now* has ever
been signed off, and the highest approved version answers it. Ordering by `approved_at` answers a
different question, and answers it wrongly in the one case the two differ: a review opened at v3,
left open through an edit to v4, approved after a separate review of v4 had already been signed.

**The case's own status is a term in the expression, not a filter in the `WHERE`.** This looks like
a style choice and is the difference between a signal that self-heals and one that cannot. As a
filter, every non-approved case would be skipped by the statement — so a case flagged stale and then
sent back to `DRAFT`, which is exactly what `EditAfterApproval.RE_REVIEW` does on every edit, would
keep a flag nothing could ever clear. `HealthSignal`'s "no button for lying" rule arriving from the
other side: the point of deriving these is that they go false when their cause does.

**Not gated on the project's review settings.** `ReviewService.moved` opens no review at all for a
project with §6g switched off for cases, so those projects have nothing for the statement to find
and report an honest zero without a policy predicate. Adding one would make the signal *disappear*
for a project that turns review off while approvals granted under it are still on the wall — the
record would still say v1 was signed and the case would still say v7. `ReviewPanel` consults no
setting either, which is the agreement that matters: the banner and the facet are one claim.

## What a zero means now, per signal

§156's finding was that `case_health` stopped being empty at §53, so a `false` on an existing row is
indistinguishable from a check that ran and passed — and five of the ten signals had never run.
**Four now.** The SPA's panel still says *"No health signal was raised"* rather than *"Nothing needs
attention"*, and it must go on saying so until the other four have writers: the sentence is narrowed
to a claim about the checks precisely because the SPA cannot know which signals the server derives,
and that is still true with six of ten rather than five.

What did change on screen, with no frontend code in this commit, is that three surfaces written
generic over `HealthSignal` in M0 start producing output they never could:

- `DashboardService.health` renders a **Stale approval** tile (WARNING, so below any ERROR tile).
- The case grid's health facet returns a non-empty set for `healthSignal=STALE_APPROVAL`.
- `work.BoardFacts.casesNeedingAttention` produces stale-approval cards — and its own javadoc had
  *predicted* this in as many words: *"including them costs nothing and means the board gains
  stale-approval and spec-changed cards on the day those computations land, with no change here."*
  It was right, the file is unchanged, and the sentence is now struck through with the date it came
  true.

## Documents corrected rather than added to

`CaseHealth`'s class header, `HealthSignal`'s, `CaseHealthRecomputeService`'s, `DashboardService`'s
and `BoardFacts`' each counted the written signals, and each is corrected in this commit — the
counting sentences are five/four, six/ten, nine, and they move together or they start disagreeing
one slice later, which is §145's lesson and its eighth instance.

**One of them was already wrong before this slice touched it.** `CaseHealth.computedAt`'s field
javadoc still read *"Nothing writes it yet, so the aggregate over an empty table is null"* — the
§47 sentence §156 was entirely about, left standing fifty lines below the class header §156
rewrote. Two javadocs in one file disagreeing about the same column, which is the exact defect §156
found in §154's own by-decision map and recorded as its cautionary tale. The family's slices keep
being the place the family's defect shows up.

## The word "execution-driven" is gone from the recompute service

`CaseHealthRecomputeService`'s first sentence described "the execution-driven health signals", which
was accurate for four of four. `STALE_APPROVAL` is derived from `review` and a version number and
has nothing to do with running anything, so the sentence is not qualified but replaced: what these
signals have in common is that **this module can see their cause** — through `ExecutionFacts` for
the four behind a forbidden module edge, through `ApprovalFacts` for the one behind a permitted one.
The third statement runs in the same transaction as the other two, for the reason already written
there: split up, a company whose second transaction failed would carry half a tick's signals
stamped with the whole tick's `computed_at`.

## The test that had to move, and why that is the finding in miniature

`CaseHealthRecomputeTest`'s `signals this job does not compute survive a recompute` proved that the
`ON CONFLICT` clauses name individual columns rather than the row — and it proved it **using
`stale_approval`**, chosen in §53 precisely because nothing wrote it. Giving that column a writer in
this service invalidated the test's fixture, not its point. It uses `unlinked` now, which is the
same shape (read by the facet, the chip and the board; written by nothing in any module).
`failing_repeatedly` would be the stronger choice — it has a real writer, in `reporting` — and is
deliberately not used, because setting it here would make this test assert over another module's
schedule.

The general form: **a test that demonstrates a hazard by borrowing an empty column has a dependency
on that column staying empty**, and nothing states it. There are three more such columns left.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL in 8m06s — 2420 tests across 502 result classes, 0 failures.** §157 adds **+7 tests, +1 result class**: `CaseHealthRecomputeTest$StaleApproval`, whose six tests report as their own class, plus one changed test in `Boundaries`. |
| `cd frontend && npm run build` / `lint` / `test:run` | unchanged — not run. No frontend source is in this commit. |
| `packages/` | unchanged — not run. |

`SA-1` was **run** as a mutation during the slice and reddened exactly the one test it predicted;
`SA-2`…`SA-6` are recorded and not run. One browser block, `SA-B1`…`SA-B3`, in lane 4 beside
`HP-1`…`HP-3` — and unlike §156's, **it needs a fixture the pass must author**, because a review row
requires §6g switched on, a `SUBMIT_FOR_REVIEW` before the approval (going straight to `APPROVED`
writes no review at all), and a second account to approve with, since `authorMayApproveOwnWork`
defaults to false. All three are written into the queue block, because a fixture that skips any of
them produces a case the signal cannot see and a row that reads as a failure.

## What is next

Unchanged in kind: the browser queue, `TeamRef`, another re-derivation, or the unrun mutation rows.
§156's list, minus what this slice took:

1. **The four remaining health derivations** — `unlinked` and `outdated_spec_changed`
   (`documentation`), `condition_drift` (`repository`, against the snapshot), `orphaned`
   (`identity`/`project`, and the only one of the five that needs a *decision* first: "owner left
   the project" is the permission model's question, and answering it in SQL here would be a second
   implementation of it).
2. **An archive operation for a project**, or the decision not to have one — `archived` is a
   required contract field with no way to become true.
3. **`IngestionJob.rawStorageKey`**, still: §67's port plus a read operation, and known since §156
   to have no writer either.
