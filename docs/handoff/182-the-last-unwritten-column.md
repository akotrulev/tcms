# §182 — The last unwritten column, and the witness that had to stop being a column

**Two phases: `backend/`, then `frontend/`. No migration, no new table, no new column, no contract
change, no `packages/` source.** §181's named next slice, taken as a decision: **`case_health.incomplete`
has a writer**, and it is the first member of `HealthSignal` whose *constant* and whose *derivation*
landed in the same slice — because until §182 there was no constant.

## What was blocking it, and it was neither of the two things the file said

`incomplete` is the last of the eleven booleans on `case_health` to get a derivation, and the only
one that was never a signal. The reason recorded at the field said, until §161:

> The contract's enum has no such member, and inventing one would put a value on the wire the SPA
> cannot label.

That was false the day it was written. `openapi.yaml` has declared `INCOMPLETE` since **2026-07-30**,
one day earlier; `lib/labels.ts` has labelled it for just as long; `StatusChip` has had a sentence
for it; both copies of `schema.d.ts` carry it. §161 found the error and corrected the *direction* —
closing this is a server change, not a wire change — and §181 restated the price precisely: one
class, one statement, no migration, no contract change. **Every clause of that prediction held.**

So the blocker went through three states and only the third was real: a contract problem that did not
exist, then an expensive decision, then a decision. What it cost was four slices of deferral.

## The decision

> An **approved** case is incomplete when it has no steps, or nothing anywhere in it says what
> should happen, or nobody has classified it.

**The gate is not a filter on the signal — it is the signal.** A draft missing things is a draft;
that is what the word means. Firing on drafts would raise an INFO chip on the whole of an active
project's authoring queue and report ordinary work in progress as a fault. `DEPRECATED` is excluded
for the mirror reason: a retired case is not going to be finished, and a chip asking somebody to
finish it is asking for work nobody wants done. What the signal names is a **sign-off that covered
unfinished work**, which is §6o's two-axis model at its narrowest — a stored status crossed with a
derived signal, the exact combination that section says the dashboard exists to surface.

Three terms, three separate answers to *"can somebody run this"*: steps are the instructions, an
expected result is how the tester knows whether it passed, a classification is what makes the case
findable and reportable. The signal does not say which is missing, and the tooltip says so — the chip
is a pointer at the case, and the case is where the gap is visible.

Two of the three are format-dependent, and that is the derivation's only real subtlety.

- **`step_format` makes `test_step` rows and `gherkin_source` alternatives** (plan §6a). A `GHERKIN`
  case legitimately has no steps at all, so the obvious spelling of "has steps" — a lookup in
  `test_step` — reports **every Gherkin case in the product** as unfinished. Not a near-miss: the
  whole repository of a Gherkin-first team.
- **A scenario settles the expected-result term for that format too**, and this is the one place the
  derivation deliberately says less than it could. A scenario's `Then` clause *is* its expected
  result, and finding it means parsing Gherkin — in SQL, in a scheduled job. That is `OwnerStanding`'s
  refusal one module over: a second implementation of something the product already does elsewhere,
  whose disagreements surface as a chip nobody can explain.
- **For a structured case, either spelling of "expected result" counts.** `test_case.expected_outcome`
  and `test_step.expected_result` answer different questions — the entity's own comment says so — and
  requiring *both* fires on almost every case in the product, because almost nobody fills in both,
  while requiring only the per-step one calls a house style a gap. The term asks whether a tester
  handed this case can tell a pass from a fail; one statement of that anywhere answers it.

**Classification means "any", because there is no such thing as a required dimension.**
`classification_dimension` has `cardinality`, `controlled`, `hierarchical` and `locked`, and no
`required` — so "missing its classification" has no per-project spelling to read. Inventing the
missing concept here would be a project setting implemented inside a health signal and configurable
nowhere.

## Two of the three terms have no reachable producer, and §181's precedent points the other way

`CaseEdit.validate` refuses a `STRUCTURED` case with no steps and a `GHERKIN` case with no scenario,
on **both** the create and the update path — and every other way a case comes into existence goes
through that same validation, which `CaseCloneService` and `AiProposalDecisionService` each say in
their own headers for the same reason (*a second assembler of a legal case is the one that diverges*).
So a saved case with no steps is not a state this product can currently produce. Only the
classification term fires today.

The terms are in the predicate anyway, on §181's argument — with **one difference worth naming,
because it makes the case stronger rather than weaker.** §181's `DEACTIVATED` term was missing a
*writer*. These are refused by a *validator*, and **a validator is a rule about new writes that says
nothing about rows already stored.** The import path §6t will need, a repair script, a future format,
or a relaxation of that rule in a slice thinking about something else — each produces the state
without touching `CaseCompleteness`, and a term left out until then is a signal that silently stops
covering it on the day it becomes reachable.

## The boundary test had to change shape, not witness

`CaseHealthRecomputeTest.Boundaries.otherSignalsAreNotClobbered` pins that each `ON CONFLICT` names
only the columns its statement computes, and it proved this with a **witness**: one `case_health`
column nothing writes, set by hand, still set after a tick. The witness moved three times —
`stale_approval` (§157), `unlinked` (§178), `orphaned` (§181) — each time because the slice that
moved it had given the previous one a writer. §181 took the last non-signal column, `incomplete`, and
wrote down that there was no fifth.

This slice derives that one, so the population is not merely exhausted, it is **closed**: no future
slice can restore a witness by leaving a column alone. The answer is to stop needing one. Each of the
seven statements is now driven on its own — `TransactionTemplate` plus `CompanyContext.open`, because
outside a transaction the GUC is unset and `V4`'s `nullif` makes that match *zero* rows, so the
statement would report success having written nothing and every assertion about what it left alone
would pass — and the property is read off its siblings' columns. `RECENT` is the subject because it
raises exactly one signal, so the surviving set is one expression: everything the statement does not
own, plus `unlinked`.

**It asserts something strictly stronger than the witness ever did.** The old shape watched a column
*outside* all seven statements, so it could not have noticed **two statements inside the job both
writing one column**. The new shape fails on that before any statement runs, because the seven
`owns` lists must partition the columns this job derives. `§182 CC-M6` is the mutation that says so,
and it was unexpressible a slice ago.

The reporting-owned `failing_repeatedly` is still not the answer, and the reason has changed: it is
no longer that asserting over another module's schedule is wrong, it is that one borrowed column
tests one statement's blast radius and this tests all seven.

## The words, and the fourth instance of the same finding

`healthLabels.INCOMPLETE` read *"Missing required fields"* and `healthHelp.INCOMPLETE` read
*"Required fields are missing."* Both were wrong twice over, and only the derivation could show it:

- **Nothing is required.** The case saved. All three of the server's terms are optional columns, and
  a case can miss every one of them and be perfectly fine.
- **The gate is missing from the words, and the gate is the whole meaning.** A DRAFT missing all
  three raises nothing at all, so the old label described a state no chip is ever raised for.

`healthLabels.INCOMPLETE` is **"Approved but unfinished"**, and the tooltip names the three terms
plus the action — *open it to see which*, deliberately, because the signal does not say which and a
tooltip that guessed would be wrong on two cases out of three. The absence of the word "required" is
pinned as well as the presence of the sign-off, for §181's reason: it is what the enum's own name
suggests and exactly what a later tidy-up reaches for.

**This is the fourth tooltip in `healthHelp` corrected by the slice that gave its signal a
derivation** — after §158's `CONDITION_DRIFT`, §178's `OUTDATED_SPEC_CHANGED` and §181's `ORPHANED`.
Four for four is no longer a run of coincidences: *copy for a signal nothing can raise is copy nobody
checks*, and it reads as a perfectly sensible sentence right up until the day it starts appearing.

## Changed

**Phase 1 — `backend/`:**

- **`CaseCompleteness`** (new) — one statement, the eighth in `recompute()`. Position not
  load-bearing (it reads no row this job writes). Both subqueries are `EXISTS` semi-joins rather than
  joins, which is **the opposite call from §181's and for the same reason stated the other way
  round**: a semi-join cannot duplicate a row however many steps or classifications a case has, so
  the cardinality failure `OwnerStanding` carries a company predicate to prevent is unreachable here
  rather than guarded against. Neither carries a company predicate, and both are keyed on `case_id`,
  which is unique across the instance.
- **`HealthSignal.INCOMPLETE`** — appended last, for `UNDECLARED_AUTOMATION`'s reason
  (`CaseSearch.tallyHealth` reads its aggregates back **by position**). `Severity.INFO`, by this
  enum's own split: nothing is broken and nothing is decaying, something is missing.
- `CaseHealth`, `CaseHealthRecomputeService`, `DashboardService` — the counts, and the sections that
  said `incomplete` was a column and not a signal. **The two counts on `CaseHealth`'s header have
  converged**: eleven booleans, eleven signals.
- `ArchitectureRulesTest` — `CaseHealth.incomplete` out of `WRITERLESS_BY_DECISION` (six entries, **no
  `case_health` column in the map at all**). It is now in neither by-decision map, having spent one
  slice in each and one slice in both — the third time this one column has moved between them
  without the column changing, which is the plainest argument there is for running §154's and §156's
  rules as a pair.
- `ContractEnumTest` — `HealthSignal.INCOMPLETE` out of `DIVERGENCE_BY_DECISION`, which is down to
  its one **server-only** entry. There is no contract-only member left.
- `RaisedSignalTest` — `DECIDED` is `Map.ofEntries`; `Map.of` runs out at ten and this was the
  eleventh. The mechanical fix (drop a member to fit) is silent, and `containsOnlyKeys` is the only
  thing that would say so.
- `CaseHealthRecomputeTest` — a `Completeness` group of eleven, `Boundaries` rewritten to the
  per-statement shape, and the first fixtures in this class made of a case's **own authored
  content**: a MULTI dimension, one value, steps with and without expected results, two Gherkin
  cases. The five APPROVED cases of the stale-approval block now raise `incomplete` and their
  assertions say so rather than filtering it out — §178's call, for §178's reason.

**Phase 2 — `frontend/`:** `healthLabels.INCOMPLETE` → *"Approved but unfinished"*,
`healthHelp.INCOMPLETE` → the three terms plus the action, and two tests in `HealthChip.test.tsx`.

## Gates

`./gradlew build` on `shanks`, frontend `npm run test:run` / `build` / `lint` — see the commit
messages for the measured numbers.

Rows `§182 CC-1`…`CC-4` and `CC-M1`…`CC-M6` **recorded and not run** (§65). **Built and unverified.**

## The next slice

§181's list, minus the entry this one took.

- **quietening `INFO` on a chip** — §179's recorded reading-plan decision, still not taken, and now
  with a **third** INFO signal firing (`INCOMPLETE` joins `UNLINKED` and `ORPHANED`). Two slices in a
  row have added one, which is the argument's second half arriving on its own;
- **`severityRank` in `DashboardPage` is a second copy of the server's ordering** — §180 looked at it
  and left it, deliberately;
- the **read-only-archive** decision §159 did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- **run the browser queue** — ~731 rows, thirteen lanes, one sitting. At the **bottom** at the user's
  direction (2026-08-12).

Worth noting what this slice ends: **`case_health` has no open work left.** Every column is derived,
every signal has a writer, neither by-decision map names one, and the boundary assertion no longer
depends on there being an unwritten column to borrow. Five slices' worth of "one left" sentences are
spent.
