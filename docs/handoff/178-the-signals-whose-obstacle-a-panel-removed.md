# §178 — the two signals whose obstacle a panel had already removed, and the chip that must not contradict the list beside it

**Two phases: `backend/` (production code and tests), then `frontend/` (copy and one test). No
migration, no new table, no new column, no contract change** — both columns have existed since `V1`,
both members have been in the contract's `HealthSignal` since M0, and this is the slice that gave
them a writer.

§177's named next slice, taken from the top of its list: **`OUTDATED_SPEC_CHANGED` / `UNLINKED`,
derivable from `case_doc_link`**. They are the third and fourth of §156's five writerless health
signals, and they were the two every slice since §158 had recorded as the hard ones.

## The finding: the obstacle was real, was named correctly, and had stopped existing

`HealthSignal`'s header has said since §158, in as many words, that these two *"need
`documentation`, which this module may not depend on, so those two are a seam and not merely a
statement"*. `CaseHealthRecomputeService` said the same. §165 and §160 had already found three sites
blaming a module for not existing — this is not that. **The constraint was true, is still true, and
was never the thing blocking these two.**

What removed it is §167, which built `case_doc_link` — a table in `repository` — for an unrelated
reason: `TestCase.relatedDocuments`, the case detail's Traceability panel. That mirror happens to
hold, per case, exactly the two facts these signals ask for: whether the case links to anything, and
whether anything it links to has moved. So the derivation is **one class and no seam**:
`DocumentationFacts` is still the only file in `repository` that names a `documentation` table, and
`SpecLinkage` names none.

The shape worth carrying: **a sentence naming a live constraint can go stale by something else being
built.** §160 and §165 found prose whose stated obstacle had been removed *by the thing it described*;
this one was removed by a slice that was not about it and did not notice. The instrument that would
have caught it does not exist — no rule asks "which recorded blocker is satisfied by the current
schema" — and §161's audit of `*_BY_DECISION` entries could not have found it either, because the
entry for these columns was correct about the module boundary and merely wrong about what the boundary
implied.

## THE DECISION: `UNLINKED` counts any link; `OUTDATED_SPEC_CHANGED` counts any relationship

Two calls in opposite directions, and they are the substance of the slice.

`doc_link.relationship` has three values and only one is a coverage claim.
`documentation.BlockCoverageView` and `reporting.CoverageMatrixReport` both count `VERIFIES` alone,
so the consistent-looking move is to filter here too: a case linked only by `DERIVED_FROM` or
`REFERENCES` contributes to no coverage marker anywhere, and by that reading is invisible to
traceability.

**Refused for `UNLINKED`, and the reason is where the question is asked from.** Those two sites answer
a question about a *statement* — does anything claim to verify this paragraph. This one answers a
question about a *case*, and it renders as a chip inches from the panel listing that case's links. A
chip reading "Unlinked" above a panel showing a link is not a strict reading of traceability; it is
the product disagreeing with itself on one screen, which is the defect §157 found between
`ReviewPanel`'s stale banner and the stale facet, and the exact thing §167's one-transaction
arrangement was built against. *"Linked, and nothing claims coverage"* is a real and narrower gap, it
is a different signal, and this enum has no member for it — which is the honest place to leave it
rather than lending it `UNLINKED`'s.

**Taken for `OUTDATED_SPEC_CHANGED`, which is the opposite call from the same argument read the other
way.** §6i's question is whether the *case* is now out of date, not whether a coverage number is. A
case *written from* a statement that has since been rewritten is exactly as wrong as one claiming to
verify it — the author read a paragraph that no longer says what it said. Narrowing this to `VERIFIES`
would silence an ERROR-severity signal on precisely the cases whose relationship records that a human
copied the text by hand.

Both are pinned by fixtures rather than by these paragraphs: `LINKED_BY_REFERENCE_ONLY` fails alone if
a later consistency edit filters the relationship on the `UNLINKED` term.

## It reads the mirror, and the strongest argument for that looks like a bug

`V46` §3 prescribed this a slice in advance — *"phase 2 of §167 derives that signal FROM THIS TABLE
rather than from `doc_link` a second time"* — and the reason is not proximity. The mirror's four
predicates already drop every row a reader must not see: a link into a soft-deleted page, a page of a
sibling project, a link off a soft-deleted case, and a suite's links rather than a case's. Deriving
from `doc_link` means writing all four again, in a second file, where they can come to disagree.

So: **a case whose only link points into a page somebody deleted reports `UNLINKED`.** It has a
`doc_link` row. Nothing renders on its panel, so nothing is what the chip agrees with; the alternative
is a case the product tells you is traced, to a destination it will not show you. Three tests
(`aLinkIntoADeletedPageIsNoLinkAtAll`, `aSiblingProjectsPageIsNoLinkEither`,
`aSuitesLinkDoesNotCoverItsCases`) look like duplicates of the mirror group above them and are not:
they assert the **signal**, so the day somebody rewrites the derivation against `doc_link` to save a
join, all three go red.

## The statement's POSITION is load-bearing, which nothing else in this class is

`SpecLinkage` runs **sixth**, after `DocumentationFacts.refreshCaseDocLinks()`, in the same
transaction. One transaction is what makes the panel and the chip one answer; the **order** is what
makes it *this tick's*. Ordered before the mirror it would derive both signals from the previous
tick's rows, so a case linked a minute ago keeps its `UNLINKED` chip for an hour while its own panel —
refreshed by the statement that ran after it — already lists the link.

`aLinkMadeSinceTheLastTickCountsOnThisTick` is the only assertion in the class that can fail on a
statement's position rather than its content, and it needed a fixture trick to be honest: it *makes* a
link between two recomputes. Nothing in this class rolls back between tests, so that link outlives the
test — hence `LATE_LINK`, a named id `seed()` deletes, because a random one would have left every
"this case is unlinked" assertion depending on JUnit's method order. §19's fixture collision arriving
through time instead of through an id.

Two smaller calls, both at the statement:

- **One lateral aggregate, not two `EXISTS` subqueries.** Equivalent under one snapshot; the aggregate
  says what is true of the design — these are two readings of one question, computed from one pass
  over one row set. `bool_or` over no rows is NULL (hence the `coalesce`); `count(*)` is 0, so the
  `UNLINKED` term needs none.
- **An external target clears one signal and can never raise the other.** A case pinned to a file at a
  commit is not invisible to traceability, and `stale` is the literal `false` on that arm (`V47` §3),
  so nothing this database can see moves it. The asymmetry is two answers, not an omission.

## What the test rewrite cost, and why it was not narrowed

**`unlinked` fires on every case nobody has linked** — in `CaseHealthRecomputeTest` that is nearly all
of them, and in a young project nearly all of a company's (plan §6o's own mock-up: `Unlinked 156`
beside `Approved 891`; the severity is INFO for exactly that reason). So about thirty existing
`containsExactly` assertions gained a member. Written out rather than filtered, on the rule the
stale-approval fixtures already state about `never_executed`: an assertion that quietly ignored a
sibling signal would pass against an implementation raising it wrongly.

**`Boundaries.otherSignalsAreNotClobbered` had to move for the second time, and for the same cause.**
It proves the `ON CONFLICT` clauses own their columns individually by setting a signal this job does
not compute — `stale_approval` until §157 gave that one a writer *in this very service*, then
`unlinked`, which §178 has now done the same to. **The population it draws from is `orphaned` and
nothing else.** The slice that derives `orphaned` cannot replace this with a third column; it needs a
structurally different assertion, most likely `reporting`'s `failing_repeatedly` and the
other-module's-schedule problem that argument has dodged twice. Recorded at the test, because the next
author meets the shortage rather than the history.

## The frontend half: one tooltip that was wrong in three ways, and one divergence left alone

`OUTDATED_SPEC_CHANGED`'s tooltip read *"The documentation block this case verifies has changed since
the case was last edited."* Every clause was wrong, and none of it was visible while nothing could
raise the signal — §158's `CONDITION_DRIFT` correction arriving one signal later, for the same reason:

- **"verifies"** — every relationship counts, per the decision above;
- **"block"** — the comparison is the **page's** newest version (`V46` §4), so editing an unrelated
  paragraph on the same page marks the link stale. That is the finest granularity the schema supports;
- **"last edited"** — the newest case *version*, so assigning an owner or moving a suite does not
  forgive a spec change.

It reads *"A specification page this case links to has changed since the case was last saved."* and
`HealthChip.test.tsx` pins it, because the regression is a tidy rewrite toward the wording the enum's
name suggests. `UNLINKED`'s sentence was checked and left: it is accurate, and it is the same sentence
the Traceability panel's own empty state uses on the same screen — which is the agreement §167 built
the mirror for.

**And reading that file found a divergence this slice deliberately did not fix.** `SEVERE_SIGNALS` in
`StatusChip.tsx` is a hand-kept second copy of the server's `HealthSignal.Severity`, and it says so in
its own comment — *"if a signal ever renders here with a severity beside it, this list should go rather
than be kept in step"*. **The two copies disagree about two members**: the server's ERROR set is
`OUTDATED_SPEC_CHANGED`, `CONDITION_DRIFT`, `STALE_AUTOMATION`, and the list carries
`FAILING_REPEATEDLY` in place of `CONDITION_DRIFT`. So the same case is red on a dashboard tile and
amber on its own chip. Recorded at the site and not changed here: editing either member alters how a
signal this slice did not derive is rendered, and the honest fix is a *comparison* rather than an edit
— which means deciding whether severity belongs on the wire for a bare signal, and that deletes the
list. It is the nearest standing candidate this slice names.

## Changed

**Phase 1** — `SpecLinkage` (new); `CaseHealthRecomputeService` (the sixth statement, the ordering
comment, three counts); `HealthSignal` (two constant javadocs, two counts, the struck prediction);
`CaseHealth` (header + both field javadocs); `CaseHealthRepository` (the "no writer" claim, stale
since §53); `HealthRecomputeOnLinksChanged` (a header written one slice ahead of being true);
`work.BoardFacts` (the fourth payout on a bet made in §53, diff of zero for the third time);
`ArchitectureRulesTest` (`WRITERLESS_BY_DECISION` 10 → 8, and the note that the list now ends on its
hard member); `CaseHealthRecomputeTest` (+12 tests in a new `LinkageSignals` group, three fixtures,
`LATE_LINK`, `linkTo`, and ~30 assertions gaining `unlinked`).

**Phase 2** — `components/StatusChip.tsx` (the rewritten tooltip, the checked one, the recorded
divergence); `components/HealthChip.test.tsx` (+1 test).

## Gates

**Run on `shanks`** per `CLAUDE.md`'s remote-gate section.

Backend `./gradlew build` on `shanks` **SUCCESSFUL in 7m13s — 2565 tests across 519 result classes, 0
failures, 0 errors** (+12 tests, +1 result class: `CaseHealthRecomputeTest$LinkageSignals`). **Run
twice, and the second run is the one these figures are read off** — the first predated the
`DERIVED_FROM` fixture correction that writing mutation row `SL-M3` turned up, so it was a gate on a
state this slice does not ship. Frontend `npm run test:run` on `shanks` **1260 passed / 1260 across 96
files in 183.7s** (+1 test, no new file) — **and §129's timeout cliff did not recur**, in a run fast
enough that it would not be expected to. `npm run build` and `npm run lint` clean locally. `packages/` untouched — no contract change, so
neither `schema.d.ts` copy moved.

Browser rows `§178 SL-1`…`§178 SL-6` and mutations `§178 SL-M1`…`§178 SL-M5` **recorded and not run**
(§65 — this slice is the ordinary case for that policy, like §177). `§167 DL-2`'s expectation is
**revised in place, id kept**: it said the chip should agree with the panel's marker *"once
`OUTDATED_SPEC_CHANGED` has a derivation"*, and that condition is now met. **Built and unverified.**

## The next slice

§177's list, minus the entry this one took, plus the one this one found.

- **`SEVERE_SIGNALS` versus `HealthSignal.Severity`** — two members disagree, found above and
  deliberately not fixed. The question is whether a bare signal should carry its severity on the wire,
  which deletes the second copy rather than correcting it;
- **`ORPHANED`, the last writerless health signal** — and unlike the four that closed, it needs a
  decision before it needs a statement: what "the owner left the project" means when a grant is
  revoked, a membership is deactivated, or a team is dissolved. It is also the column
  `Boundaries.otherSignalsAreNotClobbered` currently depends on, so that slice pays for a new
  assertion as well;
- **`CaseHealth.incomplete`** — writerless *and* readerless, on the wire since 2026-07-30, and
  `ORPHANED`'s shape: a decision about what "incomplete" means for a case;
- the **read-only-archive** decision §159 deliberately did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- **run the browser queue** — ~715 rows, thirteen lanes, one sitting, scoped in
  `frontend-browser-checks.md`. At the **bottom** at the user's direction (2026-08-12).
