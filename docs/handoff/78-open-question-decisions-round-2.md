# §78. Open-question decision round 2 — the list emptied, and the four decisions that cost something

**2026-08-02.** No code changed in this section. It records the second and final decision round over
`docs/plans/2026-07-30-tcms-high-level-plan.md` §8. **After it, §8 has no outstanding entries.**
§51 settled nine; this settles the remaining twenty-seven, plus the quota half of #28, plus five that
were not open at all.

## Five were not open, again

The same check §51 ran was run first, and it caught five more — one more round of *the orientation
pass's second question is "is this still blocked"*:

| # | Claimed | Actually |
|---|---|---|
| 14 | Edit-after-approval: Retain or Re-review? | **A per-project setting, built in §77**, defaulting to Retain + stale marker. |
| 21 | Should a project be able to require N peer reviews? | **Built in §77** — `required_peer_reviews`, plus `author_may_approve_own_work`. |
| 33 | Machine-evaluable entry/exit criteria, or phase 3? | **Built in §74**, over a closed metric vocabulary. |
| 18 | Requirements: doc blocks or a separate entity? | **Decided 2026-07-30, in this document's own Resolved table** — doc blocks, `Requirement` collapsed into §6i. A duplicate, never struck. |
| 7 | Concurrent execution — several testers on one run? | **Answered in §42, in code and in the ERD**, both citing the entry *by number*. Yes, concurrently; the per-execution lock is built, SSE was never needed. |

Five of thirty-two — and the two that close the round are the sharpest, in the same way from
opposite directions. **#18 was answered in the same file, forty lines above the place it kept being
asked.** **#7 was answered by the product**: `TestExecution.lockVersion` carries `@Version` under a
javadoc that says *"resolved open question #7"*, and the ERD's `test_execution` table writes
*"(resolved #7)"* in its notes column — two artifacts referencing the entry by number while the entry
itself stayed open for two milestones. **A cross-reference is not a strike-through**; nothing reads
back from the code to the question list, so the ordinary result of answering a question while
implementing it is that the question survives. The rate has not improved since §51's two of nine,
which is the argument for making the check part of the round rather than a thing a careful reader
might do.

#7's own content is worth keeping out of the fine print, because the granularity is a decision and
not an accident: **exactly one optimistic lock per contended thing.** `TestExecution` has it,
`StepOutcome` deliberately does not (a second lock beneath the first would fail a save that the
caller's `If-Match` could not have anticipated), and `TestRun` deliberately does not — §74 swept for
missing `@Version`, fixed `TestPlan`, and left the run alone on the grounds that a run-level lock
makes two testers collide on the product's most-used write. The SSE half of the original entry is
**not built and not owed**: locking makes a concurrent run *correct*, a live channel would only make
it *pleasant*, and the cost of not having one is a 409 and a refetch on a rare true collision.

## The decisions

Most ratify the recommendation already in the entry. These four do not, or cost something:

| # | Decision | What it costs, stated rather than discovered |
|---|---|---|
| 29 | **Sign-off records are editable by a Test Lead** — not write-once | A sign-off becomes a *document that can be corrected*, not immutable evidence. Its trustworthiness now rests entirely on the audit entry, which makes that write load-bearing: **the edit must fail if the audit write fails**, never proceed past it. |
| 30 | **Thresholds stay instance-wide** (14 / 90 days) | Reverses §6o, which says both are project settings. A project whose suite runs quarterly is flagged permanently with no way to turn it off. A no-op against the code as it stands (§53 observed it is already instance-wide), and per-project later is an additive `project_settings` column, not a rework. |
| 2 | **Private, not published** | Removes the contributor population #43's public parser SPI was chosen to serve. The SPI survives on its other leg — the seam is worth keeping still for our own parsers — but its *stability-across-releases commitment is now owed to nobody* and must not be used to justify freezing an interface early. |
| 6 | **Compose now, Helm before v1.0** | The one with a real ordering consequence, below. |

The rest, compactly: **#3** import from TestRail + Zephyr + Xray through one adapter, moved into M4 ·
**#4** Jira Cloud only · **#5** small volume (<10k cases, <100 runs/day) — Postgres FTS, no search
engine, no queue · **#11** headless Chromium, 300MB accepted · **#13** reports 90 days, sign-off
reports forever · **#19** Git doc import moves up · **#22** keep every version forever · **#23**
change comments optional · **#24** every board generator on by default, plus a sixth added ·
**#25** tracker issues as read-only cards from cache · **#26** build the Git line-link, sha pinned ·
**#27** keep case-level `expectedOutcome` · **#28-quota** instance-wide soft limit + warning ·
**#31** render both denominators side by side · **#32** as-executed membership · **#34** 14 days ·
**#35** semver first-class, lexical fallback · **#37** **Keycloak** · **#38** invitation required ·
**#39** auto-link only on `email_verified` · **#40** no SAML · **#41** service account + per-user
OAuth opt-in · **#42** the four action-driving health signals · **#44** opinionated status mappings
with a review prompt.

## The five consequences the implementing slices must carry

1. **#6 reorders the storage work. `Helm` depends on the S3 adapter, not beside it.** #28's
   filesystem adapter is one mounted directory, which under Kubernetes is an RWO volume that pins
   the pod to a node and forbids a second replica. **The chart lands after the S3/MinIO adapter or
   it ships a single-replica deployment**, which is most of the reason to have a chart at all. This
   is the first thing in the plan that gives the deferred S3 adapter a deadline.

2. **#3 and #19 both inherit #9's identity rule, and it is the whole risk in each.** Without a
   stable foreign-id→case mapping the second TestRail import duplicates the suite; without a stable
   doc-block identity the second Git import duplicates the documentation space. §51 wrote this once
   for `.feature` files and it is now true in three places — **the rule is the feature, the parser is
   the easy half.** #3 has a second edge: custom fields are deferred to phase 2 (§6m), so a foreign
   custom field has nowhere to land and must be **reported as dropped, never silently discarded**.

3. **#24 turned into a capping question, and it was asked wrong the first time.** The option list
   offered "failed-result follow-ups" as one of §6l's five generators. **It is not one — §6l's
   examples are assigned execution, pending review, case-stale-by-doc-change and quarantined flaky
   test, four not five**, with linked tracker issues (#25) as the fifth thing that lands on a board.
   The error was caught by checking §6l after the answers came back rather than before writing the
   options, which is the wrong order and the reason this note exists.

   Re-asked and settled: **every generator is on by default, and failed-result follow-ups is
   recorded as a sixth generator added 2026-08-02**, not as a default that was already there. §6l's
   Examples row was updated to match. **With nothing opt-in, §6l's "cap derived generation with
   sensible limits" stops being prudence and becomes a requirement of the first board slice** —
   volume was the entire reason the original recommendation was "executions and reviews only".
   Three rules ride on it: failed-result cards **dedupe to one per case per run**, never one per
   failed step; the reviews generator **respects §77's per-project opt-in**, or a project with
   review off gets cards for an apparatus it disabled; and the **quarantined-flaky generator has no
   data until M3 ingestion exists**, so on-by-default means *on when it ships* rather than a source
   that is silently empty and mistaken for working.

4. **#31 is a §65 lesson, not a preference.** Rendering both denominators side by side was chosen
   over a default-plus-toggle because the defect being prevented is not picking the wrong
   denominator — it is **not knowing which one you are reading**, which is exactly the two
   contradictory percentages on one screen that §65 found. Both numbers get labels; `lib/results.ts`
   owns both computations, and a second implementation is how they start disagreeing.

5. **#42 leaves #30's 14-day number with nothing reading it.** The four v1 signals are outdated-spec,
   stale-approval, never-executed and unlinked; stale-automation is not among them. The threshold is
   decided and dormant — worth knowing before someone treats its existence as evidence the signal
   shipped.

## What #37 unblocks, and what it now owes

Keycloak was the expensive-to-defer one because M4 would otherwise build against an abstraction no
real issuer had exercised. Naming it does more than fix a claim mapping: **Keycloak self-hosts, so
it goes into compose as a real test target.** The alternative was an OIDC lane whose only counterparty
was a mock — which is the same defect class as a multi-company discriminator that has only ever held
one value (§0.1's harness argument), and it should be built with the same suspicion.

## Not verified, because there is nothing to verify

No code, no contract change, no migration. Nothing was added to either verification queue — the §65
policy covers behaviour that was built and not driven, and this section built nothing. The next
slice that implements one of these decisions carries the queue rows for it.
