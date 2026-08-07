# §121. Flake detection and quarantine — the third implementation of one sentence, and the gate that is the slice

**M3 slice 6, phases 1 and 2 of 4.** The plan's milestone table says what M3 is:

> **M3 — Automation ingestion** | Result parsers, automation-key matching, API tokens, **quarantine
> flow** | 2–3 weeks

Parsers and matching shipped in §117, tokens in §116 and §118. **The quarantine flow was the one
item left, and it had no schema at all.**

## The re-derivation, and the fourth time this has happened

§120 closed by naming five candidates. None of them was picked from that list, because §28/§29/§46's
rule is to ask the code instead — and the code answered with something the list did not contain:
§6l names **four** derived-item generators and `BoardFacts` implements **three**. The missing one is
*"quarantined flaky test"*, and its card source has been silently empty for the entire life of the
board.

Open question #24 recorded the blocker in as many words on 2026-08-02:

> "The quarantined-flaky generator has **no data until M3 ingestion exists**. On-by-default here
> means on **when it ships**, not a card source that is silently empty and mistaken for working."

§117 removed that blocker. **This is the fourth time a plan's own "blocked" note has outlived its
blocker** — §115's coverage matrix (§93 had unblocked it two slices earlier and nobody noticed),
§120's drift pair, §120's own re-reading of #42, and now this. The rule has earned a stronger form:
*a "not covered" list is a claim about the past; re-derive it against the code before treating it as
current.*

## The finding that changed the code: this is the THIRD implementation of §6l's sentence

§6l states flakiness once:

> "Pass/fail alternation **within a single version and configuration**, which is the only scoping
> under which flakiness means anything. Alternation across versions is just change, and reporting it
> as flake is a false signal."

`execution.CaseHistoryService.flakiness` has computed exactly that since **§49**, for §6p's per-case
timeline, under the same name. R7's inconsistency callout computes something adjacent over the same
rows. This slice was written as a third.

The first draft took R7's spelling — `counts_as_pass` for the pass side — because R7's callout is
the statement it most needs to agree with, and that is what the slice plan's D2 argued. **It was
wrong, and the way it was wrong is the durable part**: `case_status.kind` and
`case_status.counts_as_pass` are *independent columns*. A project that defines "Passed with known
issues" as `kind = 'RETEST', counts_as_pass = true` makes the two spellings disagree — that row is a
pass to one and not a verdict at all to the other.

**On the six seeded defaults they agree exactly.** So the divergence is invisible in every test, in
every demo, and in every project that has not customised its status vocabulary — and it appears in
the first one that has, as a case page and a worklist quoting different flakiness for the same test
with nothing to say which is right. It would have shipped.

The resolution is `status_kind IN ('PASSED','FAILED')`, matching `CaseHistoryService`, and the
reason is about *which question is being answered* rather than which column is nearer: two
computations that both answer **"how flaky is this case"** must not disagree, while R7's callout
answers **"did this case both pass and fail inside this selection"**, where `counts_as_pass` is the
spelling consistent with the pass rate printed beside it. `FlakeScoreTest` seeds a `RETEST`-kind
status that counts as a pass — the only row in the suite that can tell the spellings apart — rather
than a paragraph promising they agree.

The two *remaining* differences from `CaseHistoryService` are deliberate and are differences of
**scope, not of rule**: that one is unbounded and scores from a single pair because it renders the
number directly above the sequence it came from, where a reader can see it was drawn from two
results. This one is windowed and refuses to score below the floor, because a worklist row carries
no such context.

## The gate is the slice

An endpoint about CI results looks like it should be gated on `result:submit` — the key the caller
posting those results obviously holds. **That is exactly wrong, and it is a one-word change.**

Automation Service is deliberately the smallest role in the product because a leaked CI token is the
realistic breach. A token that can post results **and** quarantine the tests those results fail can
turn a red suite green with nobody involved. `case:edit` is an existing catalogue key that means the
right thing — quarantining changes how a case is treated — and that Automation Service does not
hold.

§117 made the same call for `automation_key_mapping`'s writer. Together they are what makes *"a
leaked token cannot launder a red suite into a green one"* a **design** rather than a hope: both
capabilities sit behind a key the role does not have. **No `case:quarantine` key was invented** —
the roles spec is authoritative and adding one is a decision that belongs in that document.

`FQ-3` measures it: the wrong gate reddens exactly one test, the one written for it, and every other
test in the class survives.

## One table, two halves, and the rule biting inside it

`case_flake` is the first table in the product whose row is half **derived** and half **authored**.
They cannot share `case_health`, whose own comment forbids it — *"signals are DERIVED, never
authored … no 'mark as not-stale' button exists to lie with"*.

The boundary is the rule the read models already run on, from `repository.ExecutionFacts`: *a
signal's owner writes its own column and no others; the row is shared, the columns are not.* §120 was
the first slice where it bound two **live** writers; here it binds **two writers of one row**. The
recompute names seven columns and can never release a quarantine; the quarantine write names four and
can never rewrite the evidence its decision was made on. An `EXCLUDED.*` set-list would release every
quarantined test on the next tick — silently, in the reassuring direction, presenting as a board whose
quarantine column empties itself overnight with no action recorded by anyone.

**Quarantine is authored, not derived from a threshold**, and that is not a lapse from §6o. A score
crossing a line is a *claim* and §6o forbids a human overriding one; quarantining is a *decision* —
"stop gating on this test". It is also the only version in which the board card means anything: a
card derived from the score would vanish the moment the test stopped flaking, taking the debt with it
while the test was still excluded.

## The decisions worth knowing before touching this

**A cell is `(case, configuration, case version)`, and it is R7's cell.** Three ways to be wrong,
each with a different harm and each with its own test: a change between versions is a test catching a
regression (quarantining it sends somebody to silence the test that found the bug); a difference
between configurations is *"fails only on Safari"*, a real fixable defect; and a blocked run between
two passes is a statement about the environment.

**Non-outcomes are removed BEFORE the sequence is formed.** Not ignored — removed, so two passes with
a blocked run between them are *adjacent*. Leaving them in and treating "not a pass" as the other
value scores a perfectly stable test at `0.8889`.

**`transitions`, not `executions`, is the denominator.** A case with six results across six cells has
six executions and zero transitions: nothing in it ever ran twice under the same conditions. Dividing
by the execution count scores it `0.0` — *evidently stable* — on evidence that says nothing, and it
then looks exactly like the cases that really are stable.

**The score is NULL below the evidence floor, and that is a different claim from 0.** Two results
that went pass then fail score a perfect `1.0` on any ratio definition. `0` means *evidently stable*;
null means *not enough evidence to say*. The counts ship either way, so a screen can render "3
results, not enough to score" rather than an unexplained blank — §95's `usage_count` and §100's exit
criteria, in the place it costs most, because the number this would invent is the one a human uses to
decide whether to quarantine.

**The window is bounded, for §120's reason from the other direction.** An unbounded score can never
go false.

**No `source` predicate**, deliberately unlike §120's pair: a manual test that alternates on one
configuration at one version is genuinely flaky, and R7's callout has none either.

**Nothing downstream reads the flag.** Quarantine is recorded and surfaced; it does not exclude the
case from a run, a report, or a pass rate. Silently moving a pass rate's denominator is how a green
number stops meaning anything (§6e), so when gating reads this column it must say so on the face of
the report — a separate slice, with §6e's discipline.

## Two defects the tests caught rather than a reviewer

- **`audit_event.changes` is JSONB.** A bare string is a 500 at flush time, not a compile error.
- **`findById` bypasses the Hibernate company filter.** `TestCaseRepository.findLiveInProject`'s
  javadoc says so in as many words, and the first draft did it twice. RLS still covers it — which is
  precisely why it would never have been noticed. `CaseFlakeRepository.findScopedById` exists for
  this.

A third was caught by the *schema*: `case_flake_quarantine_complete` refused this slice's own test
fixture, which quarantined with a null `quarantined_by` because no `app_user` row existed. The
constraint proved it does something before any production caller reached it.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **BUILD SUCCESSFUL — 2003 tests across 424 result classes, 0 failures** (§120's baseline was 1963/410; +15 flake, +18 quarantine, +2 the schema count) |
| `cd frontend && npm run build` | clean |
| `npm run lint` | 0 warnings |

Schema head is **`V38__case_flake.sql`**; **`V39` is the next free number**. Policy count goes
**61 → 62** — a new company-owned table, unlike `V37`, which added a column to an already-policed
one. `SchemaConventionTest`'s pinned count is what made that a decision rather than a side effect.

## Verified how

**Three mutations RUN and stated as a §65 deviation** (`FQ-1`, `FQ-2`, `FQ-3`), the same shape §115,
§117 and §120 each recorded and for the same reason — both classes went green first time. Eleven more
queued. Two of the queued rows say they **may redden nothing**, which would be the finding: `FQ-10`
(the `execution_id` tie-break, unobservable because the fixture spaces results a day apart) and
`FQ-14` (the filter-versus-RLS layering, where a suite that cannot see the difference is exactly why
the rule is written down).

**No browser rows, and that is a statement rather than an omission** — §116's and §117's shape. There
is no screen. The queue records what phase 4's rows will name, including the one thing the flake
panel must not do: render a null score as `0`, which passes every type check.

## What is NOT done — this slice is HALF SHIPPED

**Phases 3 and 4 are not built**, and the slice must be described that way:

- **Phase 3 — the board's fourth generator.** `BoardFacts` still implements three of §6l's four, and
  the quarantined-flaky card source is still silently empty. `CaseFlakeRepository.findQuarantined`
  exists and has **no caller** — which is the writerless-table shape this slice was started to fix,
  reproduced one level up. Note `work_item.kind` has no `CHECK` and derived cards are not rows, so
  this needs no migration.
- **Phase 4 — the frontend.** No SPA consumer for `PUT …/quarantine` or for `CaseFlake`. The
  contract and `schema.d.ts` carry both.

## What is next

- **Phase 3, then phase 4** — above. The endpoint without a screen is §117's position exactly, and
  §118 is what it looked like to close.
- **`lastUsedAt` needs a writer** (§118), still — named for the fourth slice running now. §28's rule
  says that is a reason to check whether it is blocked rather than to repeat it: it is not.
- **Async ingestion** — `PENDING`/`PROCESSING`/`FAILED` and `raw_storage_key`, which §117 left
  writerless on purpose. Note it re-opens §117's idempotency-key decision, so it is a design slice
  rather than a plumbing one.
- **Five `case_health` signals still have no writer** — `OUTDATED_SPEC_CHANGED`, `STALE_APPROVAL`,
  `CONDITION_DRIFT`, `UNLINKED`, `ORPHANED`. `UNLINKED` remains the cheapest.
- **Gating on the quarantine flag**, with §6e's discipline about a denominator that moved.
