# TCMS — Session Handoff

**Date:** 2026-08-06 (latest section: **§101, the M2 rollups** — the two `V1` tables that had been
discussed in javadoc and written by nothing since M0, **six phases, six commits**, and the slice
that closes the finding which moved **M2 backwards, 90% → 85%** in round 3 of the scope assessment.
Nothing was un-built; the earlier figure was wrong, because round 2's check was a plain `grep` and
**a table named in a comment is not a table with a writer**. `V34__rollup_read_indexes.sql` and
`V35__rollup_freshness_columns.sql`, `V36` next free; policy count **unchanged at 61** — both tables
have been in `V1`'s `company_owned` array since M0, so they were protected for eight months before
there was a row to protect. **THE FIRST FINDING IS THAT `suite_run_summary` MUST FREEZE ITS
MEMBERSHIP.** A run's executions are fixed at create (§42), but *which suites claim those cases*
moves every time somebody adds a tag — so a recompute that re-derives membership rewrites last
March's pass rate, 43 → 44, **with both numbers looking equally correct and nobody able to name when
it changed**. §6p settled it as open question **#32** before the table had a writer, and
`membership_snapshot` is the column that exists to hold it: §86's frozen-dimension argument reaching
a second table. **The freeze is STRUCTURAL** — two statements, the correcting `UPDATE` tallying over
the row's own stored snapshot, so the frozen columns are unreachable from it rather than merely
omitted; §86 holds fifteen columns by a set-list a reviewer has to notice. **THE SECOND FINDING IS
THE ASYMMETRY**: `plan_snapshot` is `V1`'s *"CURRENT latest result per case per configuration"*, so
it needs the delete pass its sibling **must never grow** — a suite that stops claiming a case does not
un-run it, but a plan must not argue readiness from a case it no longer covers, and a recompute with
no prune leaves the totals permanently too high **with no row anywhere that looks wrong**. Two tables
one migration apart, discussed in one paragraph of §6p, and the same recompute written twice is wrong
for one of them. **THE THIRD FINDING IS THE ONE THE ORACLE TEST CAUGHT BEFORE IT SHIPPED, WHICH IS
WHAT IT WAS FOR.** Choosing the source from the aggregation policy alone is a claim about what the
rollup *can* serve, not what it *has* been written for: a plan whose first results landed since the
last tick — and **every plan on a deployment where the job has never run** — read an empty
`plan_snapshot` and reported **every case as not-run**. Not staleness: a plan that has been executed
reporting that nothing has been executed, **rendering exactly like a plan nobody has started** (§47,
§53, §72, §81's shape through a merely cold cache). The rollup is now used only when it covers every
in-scope case with a recorded result; **falling back is the safe direction** — the live path is the
specification and the failure it replaces is a *wrong* number, where the cost is a *slow correct*
one. `PlanStatisticsService`'s own header named this slice's specification in one sentence — *"the
migration path is to feed the rollup and read it here, with this class as the definition of what the
rollup must produce"* — which is why `RollupAgreementTest` asserts **equality between two
computations** rather than fresh expected numbers. **Two things the plan under-specified**, both
found in the building: `suite_run_summary` had no `last_executed_at`, the entry's **required** sort
key, and serving the suite history from the rollup alone would **DROP** a run recorded since the last
tick — an absence, not a stale number, met first by the person who has just finished the run (hence
rollup ∪ tail). **`execution.RollupFacts` is the module's SECOND seam** — the rule was never one seam
per module but one per **foreign** module — and the `reporting` edge now runs **both ways at the
table level and in neither direction at the Java level**, which is the only level ArchUnit checks;
the cost, that nothing verifies the two sides agree about a column, is closed by the agreement test
rather than by a comment. **`plan_snapshot` serves two of five policies**: `WORST`, `BEST` and `ALL`
each need every execution and stay live, asserted in **both** directions because serving `WORST` from
a latest-only table is wrong in the one direction nobody checks — it makes a plan look **better**
than it is. **Freshness is on the wire**: `computedAt`, required and nullable, and it is the
**OLDEST** `updated_at` rather than the newest, because both responses mix rolled-up rows with a live
tail and the newest would be false in the reassuring direction. **Two ages, two lines** — `stale` is
about the executions, `computedAt` about the arithmetic — and the contract's *"computed per request"*
clause is **struck through with its reversal** rather than deleted, because the distinction it drew
is now the reason there are two fields. Gates: backend **BUILD SUCCESSFUL — 1795 across 360 classes,
0 failures, 0 skipped** (1761 → 1795); frontend **919/919 across 67 files** (913 → 919, exactly the
six written — no `api.*` call site added, so the hygiene checks generate nothing). **Zero
verification run**: 9 browser rows, **all structurally live-only** because the mock truthfully
answers `computedAt: null` and mock mode can only ever render the "Computed now" branch, and 11
mutation rows. **Built and unverified.**
Before that: **§99, a run is scoped by suites, cases and tags** — M3 of the
organization-model restatement and the slice that **completes it**, **three phases, three commits**.
**No migration (`V33` still head, `V34` still free), no table, no column, no key, no route**, policy
count unchanged at **61**: the whole slice is two fields on `TestRunInput` and one query behind them,
unioned, deduped and resolved **once** at create. **THE FINDING IS THE SUITE ARM THAT READS RIGHT
AND RESOLVES TO NOTHING.** `c.suite_id IN (:suiteIds)` is one column comparison, is what the schema
looks like it wants, and against a suite defined by a **tag rule** — which files nothing — returns
the empty set: a run of zero cases, rendering as **0 of 0, 100%, complete**. §98's finding one screen
over, where the wrong answer is not a number but an empty checklist. The arm is `CaseFacts#memberOf`,
filed ∪ added ∪ tag-matched, the same predicate the plan's scope goes through — and the fixture's
**non-vacuity is asserted first and by its own test** (`paymentsFilesNothing`: PAYMENTS claims a case
it does not file), because without it every row below is equally true of the defective arm.
**ONE QUERY, NOT THREE LOOKUPS UNIONED IN JAVA**: three `OR`s over one `test_case` scan, so *live in
this project* is written once and the order is `key_seq` — and each arm is appended to the SQL only
when it has members, since `IN ()` is a syntax error and the shape that survives compilation,
`IN (NULL)`, is never true and reads as an arm that matched. **§82's GATE NEEDED NO EDIT AND THAT IS
THE PROPERTY WORTH KEEPING**: it binds on the resolved union, so both new arms are covered by
construction rather than by remembering — recorded as a property and not as coverage, because a
structurally-covered gate has no mutation that proves it. **The arms SUBSTITUTE for the plan's scope
and never add to it** (`RunEdit`'s existing rule widened from one arm to three), and this is where
"scope" stops meaning one thing: a run resolves **once** because its executions are written now,
where a plan's scope is references re-resolved on every read. **Three arms, three validations**: an
unknown case or suite **id** is a field error (a reference to something the caller has already seen),
an unknown **tag** is accepted (`SuiteEdit`'s reason — no vocabulary to check against), and what
catches the typo is the emptiness check. **The refusal now lands on EVERY arm the caller used** —
with three arms "the wrong field" stops being a guess between two, and a run scoped only by a
mistyped tag answered on `caseIds` sends somebody to a control they never touched. **`tags` unions
where `?tag=` intersects and both are right.** Frontend: the dialog's own header decision is
**overturned and struck through**, the plan becomes one of four ways to scope a run, and **THE
ASSERTION THAT HAD TO BE INVERTED IS THE FRONTEND'S FINDING** — `toBeDisabled()` on a plan-less
project passes against a dialog that never grew the arms, so it would have stayed green while the
feature was unreachable in exactly the project that needs it first (§66 from the other side). **The
hand-written `TestRunInput` failed exactly as its own comment predicted**: two properties added to
the spec, the generated type grew them, the copy did not — now `S['TestRunInput']` with **one
documented narrowing**, since `openapi-typescript` renders a `default:`-carrying property as
required, which is true of a response and false of a request body. **Found and not fixed**: the
mock's shared `resolveArms` excludes `DEPRECATED` and the server does not — a pre-existing
divergence inherited by reusing the resolver, queued rather than changed in a slice that does not own
the plan's scope. Gates: backend **BUILD SUCCESSFUL — 1761 across 348 classes, 0 failures, 0
skipped** (1746 → 1761), all 14 new tests passing first run; frontend **913/913 across 65 files**
(909 → 913), lint 0, entry bundle unchanged. **One test failed first run and it was the assertion,
not the code.** **Zero verification run**: 11 browser rows (M4-30…M4-40) and 7 mutation rows queued,
two recording checks weaker than they look. **Built and unverified.** **The organization-model
restatement is COMPLETE** — §97, §98, §99, nothing outstanding in that plan.
Before that: **§98, suites become flat** — M2 of the organization-model
restatement, **two phases, two commits**, and the slice that finishes what §96 started: a suite
stops being a folder in a tree and is only ever a **named collection**. **`V33__flatten_suites.sql`**
drops `suite.parent_id`, `suite.path` and `suite_path_idx`; `V34` next free; policy count unchanged
at **61**. **THE FINDING IS THAT THE COUNT AND THE GRID HAVE ANSWERED DIFFERENT QUESTIONS SINCE
§96.** Clicking a suite filtered on `suite_id IN (…)` — the *filed* arm — while the number beside it
was the resolved union, so a **tag-defined suite showed a count of two over a grid of none**, and
neither number looks wrong alone. It survived two slices because **every fixture suite was a folder
people filed into**, where the union and the filed arm answer identically; §98 makes a tag rule the
ordinary way to define a suite, so the defect would have moved from one fixture row to most of them.
Both ends now resolve through **one query returning ids** — `CaseScope.memberCaseIds` on the server,
`suiteMemberCaseIds` in the mock — and the new `mocks/db/suites.test.ts` asserts the **non-vacuity
first**, because without a suite that claims a case it does not file, every assertion below is
equally true of the defective implementation. **`execution_fact.suite_path` OUTLIVES `suite.path` ON
PURPOSE**: it is frozen history, so `V33` touches no fact row and old runs keep dotted paths
describing a hierarchy that no longer exists — correct, and not to be "fixed". New rows get
`'s' || key_seq`, **exactly the path a root suite always had**, and every suite is a root now. The
`ltree` extension stays — checked, not assumed: three other columns use it. **THE PLAN IS WRONG
ABOUT `SuiteScopeAgreementTest` AND THE CORRECTION IS THE USEFUL PART** — it expected the class
retired, but the duplicated predicate is **ERD §0.2's module boundary**, not the subtree, so the two
are now supposed to be *identical* rather than merely equivalent and the test gets **stricter**.
**Only the FILED arm blocks a delete**, and only it carries condition inheritance: a case added or
tag-matched lives elsewhere, so deleting the collection takes a claim and never a case. **"New suite
inside" is REMOVED, not disabled** — disable-don't-hide is about an action the *state* forbids, and
no state nests a suite. **The plan's five phases are two**: a contract-only commit cannot pass its
own gate, and `ddl-auto: validate` refuses a migration split from its entity — and the ordering is
client-first, because an extra field a client ignores beats a missing one it reads. The test sweep
touched **35 files** through a script that renumbers JDBC parameter indices (editing only the SQL
leaves every fixture compiling while binding the wrong values); **three fixtures needed judgement**,
all because they used nesting to reach cases. Gates: frontend **909/909 across 65 files**, lint 0;
backend **BUILD SUCCESSFUL — 1746 across 347 classes,
0 failures, 0 skipped** (1741 → 1746), counted from the XML. **Seventeen tests went red on the first
full run and every one asserted the tree** rather than a defect in the change; a run before them
reported **1672 failures of 1748** and was Testcontainers failing to launch under two overlapping
`gradlew` invocations — §86's lesson through a new door, and the reason a catastrophic count is a
signal to check the harness before reading an assertion. **Zero verification run**: 12 browser rows and 6 mutation rows
queued, plus a `V33` row on the Hetzner list. **Built and unverified.**
Before that: **§96, tags become real and a suite resolves from them** —
the last of the authoring rework's three slices, **six phases, six commits**, and the structural
one: a suite stops being a filing decision and becomes a **resolvable collection** — filed ∪ added ∪
tagged, unioned and deduped — while a case may be created carrying no suite at all.
**`V31__suite_membership.sql`** creates `suite_case` and `suite_tag_rule`, policy count **59 → 61**,
`V32` next free; contract gains two required fields on `Suite`, two query parameters, and makes three
shapes' suite fields **nullable in pairs**. **THE FINDING IS THAT A NULLABLE FOREIGN KEY DROPS ROWS
IN FOUR PLACES AND EVERY ONE IS SILENT.** `ExecutionSource`'s `JOIN suite` never inserts the **whole
fact row** for an unfiled case — not its suite columns, the row — so the log is short, the tick logs
success and every report is smaller than the truth. `CaseSpecifications`' `IN (…)` is never true of
NULL, so **every unfiled case is absent from the grid**, the one screen somebody would look for it
on, with a total that still counts it. `ProjectRepository`'s inner join made the picker say **6 over
a list of 7** — the very defect that method was written to fix, from the other side. And
`CaseFacts.casesInPlanScope` is the one plan-scope query of three that joins the case's own folder,
so a plan naming an unfiled case would count it and not list it. **The plan named the first; the
other three were found by tests rather than by reading**, one of them by the single assertion that
compares two screens' numbers — hence the standing rule now in the orientation cache: **grep for
`JOIN suite` before adding one.** Every NULL group downstream is a **row, never an omission**:
`(not filed)` in the report, three nulls together on `bySuite`, muted italic **Not filed** in the
grid. **THE UNION IS WRITTEN TWICE ON PURPOSE** — `SuiteMembership` for the tree and the grid,
`CaseFacts#inSubtreeOf` for plan scope and suite history — because ERD §0.2 lets `execution` read
the repository's tables only through its one seam class, and calling across would trade a duplicated
predicate for a dependency `ModuleStructureTest` refuses. **`SuiteScopeAgreementTest`** keeps them
honest and pins the ONE intended disagreement (the seam includes soft-deleted cases; history must
not shrink). Two repairs made that class non-vacuous: only **one of its four live members is filed**,
and it runs inside a **`TransactionTemplate`** — a `CaseFacts` method called straight from a test
runs outside any transaction, so `app.current_company` is unset and RLS answers zero rows, and the
first draft compared an empty list to an empty list and reported agreement. **TAGS ARE NOT AUTHORED
CONTENT, a recorded departure from the plan's own line**: they ride beside `CaseEdit` rather than on
it, so `CaseVersions#hash` never sees one — labelling a case `sprint-42` must not write a version
whose diff says nothing about the test changing (§6j), which `CaseBulkService.AddTags` had already
said by refusing to stamp `updatedAt`; it also makes §95's bulk-hash trap **structurally
unavailable**. **AND VERSUS OR POINTS OPPOSITE WAYS IN TWO PLACES AND BOTH ARE RIGHT**: the grid's
`?tag=` ANDs (a list is narrowed), a suite's `tagRules` union (a collection is assembled) — a two-tag
suite that silently held nothing would be indistinguishable from an empty project. **A suite may
CLAIM a case and may never MOVE one**: filing belongs to the case's write path, or two screens write
one column. **The counts stop summing to the project total** — a case can be in three folders — so
the contract, the tooltip and the handoff all say so rather than let a reader find it by
subtraction. **THE FRONTEND DEFECT THIS SLICE SHIPPED AND FIXED**: both tag controls replaced MUI's
`slotProps` to add a testid, which drops the `input.startAdornment` the **chips** live in — every
tag held, sent, and invisible, with build and lint green and the payload assertion passing; the fix
is `...params.slotProps` first, which the step-action `Autocomplete` had right since §94.
Gates: backend **1715 → 1741**, frontend **820 → 834** across 56 files, lint 0, bundle entry
unchanged. **Zero verification run**: 14 browser rows (74–87) and 20 mutation rows queued, one
naming a test that does not exist yet and two recording checks weaker than they look.
**§97 is unblocked** — it sat behind this slice only for `test_case.suite_id` going nullable.
**Built and unverified.**
Before that: **§95, conditions get a writer** — the library nothing could fill, the edit that
propagates everywhere with no prompt, and `usageCount` unmapped one slice before it became an impact
warning. See `docs/handoff/95-conditions-get-a-writer.md`.
Before that: **§90, the on-screen report view** — F2 of the M2.5 plan,
**three phases, three commits**, and the clause §6e states in one line: *"the on-screen report view
consumes the same model via JSON, so what you see is what exports."* **No migration (`V27` still
head, `V28` still free), no contract change, no `gen:api`, no new permission key, no backend file
touched.** Shipped **before R6** rather than after it, as the plan's line proposed: everything it
needs has existed since §88, and a `Ready` card that can only be downloaded is a screen with an
obvious missing verb. **THE FINDING IS THAT READING A REPORT ON SCREEN COSTS THE PERMISSION FOR
TAKING IT AWAY.** The stored model is an artifact like any other — `V27` widened
`report_artifact.format` to admit `JSON` precisely so it would be — so it is served through the
download route, which gates on **`report:export`**. §6e names two keys where one would do and the
difference is *may read on screen* versus *may carry off*; **§87 spent a whole phase on that split**,
building the suite's first two custom roles because no built-in persona lacked either key. The
persona that role describes can watch a job go `Queued → Building → Ready` and then be refused the
report it just watched being made: **the split works everywhere except the one screen it was written
for.** Not fixed here (the fix is the server's — serve the `JSON` artifact under `report:view`, or
give the view its own route) and deliberately **not papered over**: the page branches on 403 and says
the on-screen view needs the download's key, where `describeError`'s generic *"ask a project lead"*
would make a design defect read as an ordinary permission error. **THE MODEL IS THE ONE SHAPE IN THIS
CLIENT WITH NO GENERATED TYPE BEHIND IT**, and that is forced rather than chosen: the artifact route
is declared **binary** because it serves five formats' bytes, so a schema there would be a claim
about four things it is not, and the contract carries the shape in **prose** on `modelUrl`. Hence
**parsed, never cast** — a cast puts the drift three components deep, where a half-recognised
document renders as a report with **no sections**, which is exactly what a selection that matched
nothing looks like (§79's cast defect with nothing under the compiler's eye at all). The two
behaviours point **opposite ways on purpose**: unknown fields are **dropped** so a *newer*
deployment's artifact still opens (`JsonReportRenderer`'s own *"a file that outlives the deployment
that wrote it"*), and missing or mistyped known fields **throw** — `data-fetching`'s rule verbatim,
a parse failure is an error and never an emptiness. A table cell arriving as a **number** throws too,
which is `ReportModel`'s "every cell is a string" held at the only place this client can hold it.
**THE VIEW COMPUTES NOTHING, A RECORDED DEPARTURE FROM THE PLAN'S OWN F2 LINE**: it says to reuse
`lib/results.ts` for the count arithmetic, and **there is no arithmetic** — every cell and every
timestamp is pre-formatted, because formatting *once* is what makes "what you see is what exports" a
property rather than a promise, and a percentage recomputed here (even correctly, even through the
shared helper) is §65's "two contradictory percentages" across two formats of one report where
nobody can see both at once. **The one number is the bar width**, carrying both defects
`HtmlReportRenderer` fixed once — scaled to the largest value in its **series** and never a total (a
series need not be parts of a whole), and an **all-zero series draws no bar** rather than the
full-width one `width: NaN%` renders as. **The header block is reassembled ONCE and asserted as an
exact ordered list**: this screen is the **fourth renderer** of a block `ReportDocument.headerBlock()`
assembles so three formats cannot print three subsets, it is the one furthest from it, and the
natural spelling — `toContain` per label — reddens **nothing** when a provenance line goes missing.
**A ROUTE, NOT A DIALOG, AND IT IS NOT THE REGISTER §89 REFUSED**: `/projects/:projectId/reports/jobs/:jobId`
is the only durable handle a report has while nothing lists jobs (the cards are lost on reload), it
survives one and can be pasted to a colleague — and it names **one** job resolved by the server's own
job-by-id operation, where §89's refusal was about inventing a *list* the server does not have (§81).
The page is a **job first and a report second**: `useReportModel` is gated on `modelUrl`, so a
running job shows the job running instead of the eternal skeleton `data-fetching` names, and
`COMPLETED` with no model says so rather than spinning. **The download became a component** —
`DownloadReportButton`, extracted because F2 made it a second call site and two authenticated
downloads is two of everything (`ui-rules` §8) — and the card gained a **View report** `<Link>`,
keyed on `modelUrl` for the same reason the download keys on its own URL. **§89's untested guard is
closed**: F2 had to touch `fetchArtifact` anyway, so the shared half is `artifactResponse` and the
new test drives **both** readers over five refused URL shapes — a guard tested on one of them would
leave the newer reader, the one that runs **without a click**, unprotected. Gate: **build clean, lint
0, 765/765 across 51 files** (723 → 749 → 765); **no contract-hygiene tests were generated**, which
is a report about the checks rather than a gap, since `fetchArtifactJson` is not an `api.*` call
site. **Two of phase 2's sixteen failed first run and both were the assertion, not the code**;
phase 1's twenty-six all passed first time, stated plainly. **Found and not fixed**: the
`report:export` gate above, `EvidencePanel`'s anchor (§89's, unswept), still no job list, and
§86/§87's three open rows. **Zero mutations run inline**; **8 browser rows (26–33) and 6 frontend
mutation rows queued**, and §89's untested-guard row is **struck through** because this slice closed
it. **Built and unverified.**
Before that: **§89, the Reports screen** — F1 of the M2.5 plan,
**three phases, three commits**, and the first client §87's pipeline has ever had. **No migration
(`V27` still head, `V28` still free), no contract change, no `gen:api`, no new permission key, and
no backend file touched at all.** **THE FINDING IS THAT NEITHER OBVIOUS WAY TO DOWNLOAD WORKS AND
BOTH FAIL SILENTLY.** The session is a bearer token in a header — `SecurityConfiguration` is
`STATELESS` with no cookie anywhere, which its own comment says while explaining why CSRF is off —
so an `<a href="/api/v1/…">` sends **no credential** and answers **401**, rendering as a working
link; and `api.get` cannot take the URL either, because `request` prepends `BASE` while
`ReportJob.downloadUrl` already carries `/api/v1` (**the server composes it**, so the client holds
no second copy of the routing — §67 on `Evidence.url`), giving `/api/v1/api/v1/…` and a 404 that
reads as a missing report, and because `request` ends in `res.json()`, which parses a Markdown
report as JSON. Hence **`fetchArtifact`** (server's URL verbatim, blob + `Content-Disposition`) and
**`lib/download.ts`** for the DOM half. **Its same-origin guard is not defensive programming**: the
URL is data from the server and the caller's token goes with it, so an absolute one hands the token
to another host — **and it has no test, which the queue records as a row rather than implying
coverage**. **§87's browser row 8 is obsoleted by this** (*"follow `downloadUrl` in the address
bar → the file downloads"* is false) and restated as row 22 — the pair, address bar versus button,
is what makes the reason visible. **The sibling defect is named and not swept**: `EvidencePanel`
renders `Evidence.url` as a plain anchor and every evidence download in the product is a 401 in
live mode; its fix is a different rendering (evidence is `inline`, a report is `attachment`).
**THE SCREEN HAS TO REMEMBER WHAT IT ASKED FOR, BECAUSE NOTHING ELSE DOES** — the pipeline's whole
surface is POST-generate and GET-job-by-id, **there is no operation that lists jobs**, so the cards
are what *this tab* created and a reload loses the links while the reports and artifacts still
exist. Stated on the card, in the page comment and in a queue row rather than papered over, and
deliberately **not** worked around with job ids in the URL or in storage: a list endpoint is the
fix, it is the server's, and a client-side register is a second drifting copy of a table that
exists (§81). **Two open-set fields close for opposite reasons**: the report **type** is an open
string in the contract, so the dialog offers the set it can **build a parameter form for** — one —
and never a claim about what the server can compute; the **format** is offered **in full, PDF
included**, which looks like `ui-rules`' render-then-reject and is the contract's explicit design
(*"the enum is not narrowed to what is installed"*), because a client-side list of renderers is
wrong in **both** directions and the 400 field error was engineered to land on the picker naming
what the server can produce. **The run picker IS this report's parameters** — `runId` is
worker-validated, so omitting it buys a job that spins and then fails for a field the form could
have required; a generic parameter form is **named as not built**. **The rail item is ungated, a
deliberate departure from the plan's F1 line**: `V3` grants `report:view` to five of six built-ins
and the sixth is API-token only, so the gate hides the entry from **nobody who can reach the page**
while teaching that rail entries come and go by permission — the `Review` item's own argument.
Generate asks `report:export`, **disabled-with-reason, never hidden** (§31); the definitions panel
has **no Save control**, because R5 has not built the endpoint. **The mock gained a FAILURE, and
that is what makes `FAILED` reachable**: §88's mock completed unconditionally, so the one state the
plan names as this screen's characteristic defect could not be produced without a backend —
`advance` now resolves `parameters.runId` against `db.runs` and fails with the worker's sentence,
the server's own arrangement rather than an invention, and *"build the fault switch before the
feature it proves"* applied to a state machine. **TWO TESTS HAD TO BE REPAIRED BEFORE THEY MEANT
ANYTHING.** The poll test **was vacuous on its first draft** — it waited 250ms against a 2000ms
interval, so a poller that never stopped would not have fired either; it is now a **pair in one
test**, a RUNNING job whose request count must increase and a FAILED one whose count must not, the
running half proving the clock runs and the failed half proving the check exists. And the gate test
asserts **both directions** for §66's reason (§76's mutation that failed to redden, §80's four
first-run failures): `useCan` is fail-closed while pending, so `toBeDisabled()` passes against a
component with **no gate at all**, and the holder's `toBeEnabled()` is the barrier. Gate:
**build clean, lint 0, 723/723 across 47 files** (705 → 711 → 723 — the +6 is two contract-hygiene
checks over three new `api.*` call sites, which is the evidence they saw the new endpoints).
**All twelve new tests passed first time**, stated plainly rather than taken as evidence they can
fail — the poll test had also passed first time, in a form that could not fail. **Found and not
fixed**: `EvidencePanel`'s anchor, the untested same-origin guard, the missing job list, and
§86/§87's three open rows. **Zero mutations run inline**; **12 browser rows (14–25, the first in
that file that drive a screen) and 6 frontend mutation rows queued**, two of the latter recorded as
reddening **nothing**, which is the finding rather than a shortfall. **Built and unverified.**
Before that: **§88, the HTML renderer and the report header** — R4 of the
M2.5 plan, **three phases, three commits**, and the two halves had to land together: §6e's primary
format is the one a client is *sent*, so shipping it without the header §6e makes mandatory is the
one combination that produces a document somebody forwards with no provenance on it. **No migration
(`V27` still the head, `V28` still free), no table, no column, no key, policy count unchanged at 59;
the contract change is PROSE ONLY** — two descriptions and a `gen:api`. **`ReportModel`'s own javadoc
had already recorded what was missing and named this slice**, which is why the slice is *what the
header is* rather than *whether to have one*. **The header is the JOB's knowledge, so a computation
cannot be asked for it** — it does not know who requested the report, when the worker got to it, or
which company the deployment serves, and a report type that forgot to hand those back would produce a
**headerless document that renders perfectly**. Hence `ReportDocument(header, report)` and a renderer
that takes *it*: the rejected shape is a second `render(model, header)` parameter, which works and
leaves every renderer free to ignore it, where **one argument that IS the document makes "render the
report" and "render the report without saying what it is of" the same call**. It is also what the
`JSON` artifact stores, so F2 renders the header the export printed instead of assembling a second
one from the job row. **The aggregation policy is deliberately NOT a header field** — it is the one
item on §6e's header list only the report can answer (`run_summary` prints *"not applicable"* and
says why; R6 takes it as a parameter), and a field would be a **second place to put it**, which is how
one of the two comes to be blank; `ReportDocument.headerBlock()` is the single assembly — provenance,
then selection, then the report's own metadata — so three formats cannot print three subsets.
**The selection is what the CLIENT sent and never what the report understood**: a report ignores
parameters it does not know, so a header built from what was *used* silently drops the filter the
reader believes narrowed the numbers — indistinguishable today, because `run_summary` takes one
parameter and uses it, which is why the argument is in the code and the mutation row says so rather
than claiming a test. Entries are **prefixed**, because a parameter is named by the client and could
be called `Company`. **§6u branding is a display name, stated rather than skipped**: `logo_storage_key`
and the two colour columns are `V1` columns that are **unmapped and written by nothing** (branding is
M6) and **a per-project override column does not exist at all**, so the alternative is inventing a
migration for another milestone's feature. **Nothing in `ReportHeaders` can fail a report** — every
lookup falls back to a printable value, because a `FAILED` job carrying *"could not read the company"*
reads as the report itself being broken. **The HTML renderer is written by hand and NOT with
Thymeleaf, which is a deviation from the plan and is recorded as one**: the escaping guarantee is
available without the dependency because `text()` is the only way a string reaches the output and the
test asserts that over a model whose every field is hostile — **a stronger statement than "a template
engine was used"** — while the starter would install view resolution into a REST API that has never
had any, for one template nothing type-checks. **The escaping test is a COUNT (19, one per
interpolation)**, because `doesNotContain("<img")` passes cleanly against a renderer that escapes
*some* positions. **Self-contained is a requirement with a test**: no `<script>`, no `<link>`, no
`<img>`, no absolute URL — the reader is a client opening an attachment months later, possibly
offline, and this same file is what the PDF slice will hand to a browser, where an asset that fails to
load is a blank rectangle in a deliverable. Two chart calls with a defect apiece: a bar is scaled
against **the largest value in its series and never a total** (a series need not be parts of a whole),
and an **all-zero series draws no bar** rather than the full-width one `width: NaN%` renders as in
most browsers — a chart saying everything happened on a report where nothing did. **The format became
requestable because a bean exists**: nothing in the controller, the service or the validation was
edited, and R3's own assertion that `supports(HTML)` was `false` *"changes with the renderer, which is
the point"* is now `isTrue()` with the note kept. **The mock moved with the contract** — `FORMATS`
gained `HTML` as a registry rather than a literal, each format carries its own media type and
extension, HTML is served as a **whole document** rather than a fragment, and the model artifact
answers `{ header, report }`, because a mock still answering a bare `{ title, sections }` is a shape
F2 would be built against and the server does not serve (§81's and §87's finding, a third time).
**One test failed on the first run and it was the assertion, not the code**: `onerror=alert(1)` as
inert *text* is exactly what a report of a project somebody named that should show. Gate: **BUILD
SUCCESSFUL — 1628 tests across 320 classes, 0 failures, 0 skipped** (1615 → 1628), counted from the
XML; frontend 705/705, lint 0. **Found and not fixed**: `ReportHeaders` has no unit test of its own
(covered end-to-end, because every field it fills is a database read), and §86/§87's three open rows
are untouched — the unobservable bytes-first ordering, the two unasserted scheduler loops, and
`case_health`'s missing index. **Zero mutations run inline**; **9 backend rows and 5 browser rows
queued**, the browser ones including the check no unit test can make: open the saved HTML **with the
network off**. **Built and unverified.**
Before that: **§87, the report pipeline** — R3 of the M2.5 plan and the
largest slice in it: **seven phases, seven commits**, a request that writes a row, a worker that
turns it into bytes, and three endpoints to ask, watch and take away. **`V27__report_job.sql`**,
policy count **58 → 59**, contract **+2 operations**, **no new permission key** (`report:view` and
`report:export` get their first consumer since M0), `V28` next free. **`V1` designed what to compute
and what came out and never the record of a request in flight** — so `ReportJob.downloadUrl` was a
required contract field nothing could fill. **A table and not an `@Async` dispatch**, because an
in-JVM dispatch loses every in-flight job on a restart with no symptom but a spinner, giving back
exactly what §6e's 202 bought. **The company loop is not an optimisation but the only way the worker
sees a row at all**: `report_job` is under `FORCE ROW LEVEL SECURITY`, so a global "find all pending
jobs" returns **zero rows** and the tick logs success having done nothing, for ever — asserted with
two companies rather than trusted to a comment. **Bytes first, row second — the inverse of
`EvidenceService` and argued in the code**, because here the job row already names the work so an
orphan blob *is* findable, while a `COMPLETED` job whose download 404s is a client already told the
report is ready. **§86's `{"tenancy"}`-alone declaration widens to `{"tenancy", "project",
"identity"}`** — the widening §86 named rather than pre-approved, in **three places the compiler
catches none of**: the annotation, the package comment and `ModuleStructureTest`'s rationale.
**`ProjectService.readGranted` is the product's first read gated on a key beyond visibility**:
visibility still answers 404, and what answers 403 is a feature of a project the caller is
demonstrably looking at — telling them their own report request does not exist is false in the one
direction a reporting pipeline must never be wrong. **THE SLICE'S FINDING IS PHASE 6, AND IT IS WHY
IT WAS ITS OWN PHASE: until it landed both gates were decorative.** `V3` grants `report:view` and
`report:export` **together** to four of the six built-ins, and the fifth — Automation Service — holds
no `project:read`, so it 404s on visibility one step earlier and never reaches a permission check;
**no persona the suite could build lacked either key**, so every available gate assertion was
satisfied by an implementation with **no gate**. Hence the suite's **first custom roles, and there
are two of them**: one holding `report:view` and **not** `report:export` (§6e's split made
expressible — may read on screen, may not carry off), which is what makes the two keys
**distinguishable** in both directions, and one holding **neither**, which is what keeps the *poll's*
gate non-vacuous, since with only the first persona that check can be deleted outright and every
assertion stays green. Both hold `project:read`, so their refusals are **403** where a member with no
grant gets **404** — `readGranted`'s split asserted rather than described — and **the fixture proves
itself**: the reader's 200 on the poll is the evidence the custom role confers visibility *and* the
key. **The cleanup is load-bearing and has a mutation row of its own**: `SeedDataTest` asserts
`SELECT name FROM role WHERE NOT built_in` is **empty**, so a leaked fixture role reddens an
unrelated class **in the full suite only, at a position that moves** — §19's class through a
mechanism **no UUID prefix protects against**, because the collision is with an assertion about
*absence*. **The mock had shipped the feature wrong and nothing could see it**: `id` where the
contract says `jobId`, a `requestedAt` the contract never declared, and **`QUEUED`, never one of the
four values `status` has had** — §52's class and §81's finding, cheap only because it was corrected
**before** a screen depended on it. Two scoping tests need a caller holding **both** projects, because
`report_artifact` has no `project_id` and the job is the only row that scopes it: an id resolved
alone is served through a gate that passed for a *different* project, the defect that survives an
access-control test. **Found and not fixed**: the scheduler's company loop is asserted nowhere (§86's
identical hole, read the two rows together), the bytes-first ordering is unobservable without a store
that can fail, and `case_health` still has no index on `failing_repeatedly`. Gate: **BUILD
SUCCESSFUL — 1615 tests across 318 classes, 0 failures, 0 skipped** (1553 → 1615), counted from the
XML. **Phases 3, 4 and 6 each had every new test pass first time**, stated plainly rather than taken
as evidence they can fail. **Zero mutations run inline**; **11 backend rows and 8 browser rows
queued**, five of the browser rows console-driven because there is no screen yet. **Built and
unverified.**
Before that: **§86, the fact log gets a writer** — M2.5's reporting half
begins, R1+R2 of the ten-slice plan. It ships the product's **tenth Modulith module**, the first
writer `execution_fact` has ever had, and the third of `case_health`'s nine signals — **no
migration (`V27` still free), no contract change, no new key, no new table, no new column**, policy
count unchanged at **58**. **The `reporting` package did not exist**: `CLAUDE.md`, ERD §0.2 and the
orientation cache have listed it since M0 and nine modules existed, so `ModuleStructureTest`'s
hard-coded list moves 9 → 10 — the deliberate edit its own rationale asks for. It **had** to be
created before anything could be written: the projection names `test_execution`/`test_run`,
`test_case`/`suite` and `case_status`/`case_classification` — **three modules, and no existing module
may write it** (§81's argument arriving at a module that did not exist yet), so `ExecutionSource` is
the **fourth** `*Facts`-shaped seam. **The declaration is `{"tenancy"}` alone**, narrower than the
plan proposed and narrower than every sibling: nothing here is reached through an endpoint, so there
is no project gate to ask for and no person to name — `documentation`'s precedent, in both
directions, because declaring the three this module will *eventually* want makes the declaration a
**forecast**, and a forecast cannot fail the build when it stops being true (R3 widens it, and the
package comment and the test rationale both say so). **The slice's central decision is which columns
an `ON CONFLICT` may touch: seven move and fifteen do not.** The fact log is written by an
**idempotent recompute and not by events**, because with zero `CompanyScopedEvent` implementations
and no outbox consumer an event-only log has **no rebuild path** — and a short log renders
identically to a correct one, which this project has paid for four times (§47, §53, §72, §81). Given
a statement that reruns for ever, `EXCLUDED.*` everywhere is shorter, is what anybody would write,
and **retroactively re-files last quarter's results under this quarter's taxonomy** — "failures by
feature" would change its own history every fifteen minutes and nobody could name when. **`suite_id`
/ `suite_path` are frozen too, which the plan did not say**: same argument, and the safe direction,
since a frozen dimension that should have moved is one statement to change while the reverse has
already destroyed the history it rewrote. **Two soft-delete calls point opposite ways and are
consistent**: the fact log projects deleted cases and runs (`V1` calls it *append-only*, and
filtering on insert while never deleting makes the log's contents a function of *when the job ran*),
while `case_health` derives live cases only — history versus a worklist. **`failing_repeatedly` is
`FAILED`, never `NOT counts_as_pass`**: the tempting column is right there in the fact row and
`BLOCKED`/`SKIPPED`/`RETEST`/`UNTESTED` all satisfy it, so a case **blocked** three times running
would be reported as a broken test and send the lead to fix the wrong thing. **The window must be
FULL** (N=3, refusing to start below 2) — the *all of what we have* reading lights the tile on the
first failure a young project records, so "Needs attention" reads as a crisis on the day the project
was created. **It is the first time a second module writes `case_health`**, exercising a rule
`ExecutionFacts` has prescribed since §53 and nothing could fail on; the clobber would surface an
hour later as a bug in the *case-health* job, which would be innocent. **The company loop is not an
optimisation** — a global "project everything" query sees **zero rows** under `V4`'s `nullif` and
logs a successful tick having done nothing. Two things the gate caught that reading could not:
**`max(uuid)` does not exist in Postgres**, and **a fixture UUID prefix must be hex** — `rf…` threw
in the static initialiser and failed all eleven tests before one ran (§19's family, new mechanism).
Found and **not** fixed: **`case_health` has no index on `failing_repeatedly`** while `V1` ships four
partial indexes for other signals, so this is the first tile that scans — an index is a migration and
this slice has none. **The gate is green and was confirmed after the commit, not before it**:
`./gradlew build` → **BUILD SUCCESSFUL, 1530 → 1553 across 300 classes, 0 failures, 0 skipped**,
counted from the XML rather than the console. `9d33f02` landed with that number as *arithmetic*,
flagged as such in three places; the observation agreed with it. **The lesson is the reason it took
six attempts, and it is a rule this repo already had**: the subagent building the slice and the
session verifying it were **two `gradlew` invocations in one tree**, which `CLAUDE.md` forbids for
concurrency worktrees without noticing that **a subagent running the gate is a second lane**. Each
`--stop` orphans the other's test worker, which keeps running and holding `output.bin`, so every
retry dies on a lock the previous retry left and the true cause never appears in the error.
**Zero mutations run inline**; 9 backend rows and 8 browser rows
queued, the browser ones **all live-only** because the mock invents its own health rows and has never
been fed by a fact log.
Before that: **§85, the team board's screen** — §84's frontend half, and
§6l's three boards now have three screens. It needed **no key, no gate and no rail item**: one hook,
one screen, one link. **The route decision is about `/admin`, not about projects.** Not under
`/projects/:projectId` for §83's reason (with `projectId` omitted the cards span every project, so
any project in the URL is an arbitrary one) — but also **not under `/admin/teams`, which is the
natural place and is wrong**: every affordance there is gated on `company:teams` and **this board is
gated on nothing at all** (§6k: the org chart is readable by any member; §84 declined to invent a key
for the seventh time). A screen every member may open, filed under a prefix reading *administration*,
states the opposite of what is true — and the URL is the part people quote to each other. So
**`/teams/:teamId/board`, beside `/admin/teams` and not beneath it**, which is *not* the collision §83
refused for `/board`: a bare name for two scopes is ambiguous, a board named by its own subject
segment is not. **No rail item** (a rail entry needs a team chosen before there is one) — the entry is
a **Board** link per row on the team list, placed **outside** the `PermissionGate` wrapping
Members/Edit/Delete rather than inside it as a fourth button, which is the whole gating decision in
one line of JSX and the natural mistake. **What the screen may not say** is the other half: the cards
are bounded by the *reader's* grants, so **there is no count of the team's work anywhere** — a number
from a partial view reads as canonical and is wrong for everybody but its reader; the subtitle says
whose view it is instead. **No filter chips** — the client *cannot* express `teamId`+`mine` at all, so
there is no dead handling for the 400, and a chip would be worst for the person most likely to click
it, since §84 records the refusal is **unobservable in the body** for a caller on the team. **No
project narrowing**: `teamId`+`projectId` is legal and is §6l's *swimlanes by member*, which is a
**layout**, not a filter — named as not built. **The one thing the client must word itself is the
404**: the server's is empty-bodied on purpose (§6t), so `ApiError.detail` falls back to the status
text and `describeError` would render **"Not found — Not Found"**. `TeamBoardPage` handles it above
the region with a sentence and a **Back to teams** button, keyed on `status === 404` and never on a
server sentence (§82's rule), which is safe *here* for an extra reason: **this hook sends no
`projectId`**, so the other 404 arm is unreachable from the screen. It stays visibly distinct from
`200 []`, which is one sentence for both of §84's empty cases without guessing between them. The
team's **name** comes from `GET /teams` (no 403, readable by any member) and is deliberately **not**
in a `DataRegion` — a board titled "Team board" because the org chart failed beats one that refuses
to render. **`linkFor` was verified, not assumed** — the named risk, repaired in two consecutive
slices, and correct here *because §83 removed the route parameter entirely*: with one, this screen
would emit `/projects//cases/…`, having no project at all. **The mock's fixture had §84's own hole**:
every card was assigned to `users[0]` or `users[1]`, **both in Payments Squad**, so Checkout Squad's
board was empty and an implementation ignoring membership — or quietly answering the personal board —
passed everything. One OLB task assigned to **Priya Nair** (Checkout Squad, not the signed-in user)
turns it into a **pair**: Payments holds the caller's cards and not hers, Checkout holds hers and none
of theirs. It also un-vacuumed `personalBoard`'s own filter test, which had no foreign stored task to
exclude. `personalBoard` and `teamBoard` now share one `assemble()` — **the one place the visible
project set is named** (§58's E1). **What mock mode structurally cannot prove is stated rather than
inferred**: `db.projects` *is* the caller's visible set, so the reader-bound is structural there, and
browser rows 20–22 are live-only. The **build**, not the runner, found the one defect: a helper's
`map(item => item.assignee?.id)` gave a `Set<string | undefined>` comparing happily against a
`string[]` — twelve green tests, two type errors. **705/705** (682 → 705, three generated unattended
by §70's checks); **0 mutations run inline**, 25 browser rows and 6 frontend mutation rows queued.
Before that: **§84, the team board** — the last of §6l's three boards to
have no way of being asked for, and the slice that **completes them**: `GET /work-items?teamId=…`,
a **third selector on the operation that already serves the other two**, because §6l's three boards
differ in *which cards are kept* and in nothing else — same assembly, same shape, same sort, so a
`/teams/{id}/work-items` route would be a second door onto one read model with the difference
maintained by hand for ever. Like §83: **no migration (`V27` still free), no table, no permission
key, no new route** — the key is *derived*, for the seventh time, because one seeded to nobody is a
403 no administrator can grant out of, and there is nothing for it to protect: every card this board
returns is one the caller could already see on a project board. **The slice is one decision — the
bound is the CALLER's project visibility and never the team's.** *"Cards assigned to members of team
T"* is one query, reads correctly, and renders the **case key, title and project name of every
project any member was granted, to a reader granted none of them**: §83's leak one step out and
strictly worse, because there the leaked-to party was the assignee of their own work and here it is
a reader who need not be on the team, need hold no grant, and need only guess a UUID. So the projects
come from `readAllVisible` exactly as the personal board's do and the team contributes **nothing but
a set of assignees** — from which follows the sentence now in the contract: **two people asking for
the same team's board receive different boards, and that is the design.** **§83's fixture could not
have proved it**: in every row of it the assignee *is* the caller, so "bounded by the caller" and
"bounded by the team" answer identically and the leak passes every assertion. §84 adds a **team-mate
who can see a project the caller cannot** (`TEAMMATE`, granted where LEAD is not) and asserts it as a
**pair** — LEAD's view of the team is exactly `{TC-1, TC-10}`, `TEAMMATE`'s view of the *same team*
is exactly `{TC-9, TC-11}`, which is what stops the first from passing because a fixture row silently
failed to insert. **The expansion is at READ time in `work`** (§81's precedent, §80's reason —
membership moves) and **`TeamDirectory` was NOT widened**: its header forbids it answering *who may*,
and this is not that question — the caller's visibility has already bounded the board before the set
is applied. Its `find` gets a **second consumer** for existence instead, which is what makes an
unknown team and another company's team **one 404** rather than a company-wide team-existence oracle
(§6t) — the fixture puts LEAD in an *Umbra* team to prove membership is not what keeps them out.
**Four answers that look alike and are four things**: unknown team 404 · unreachable project 404 · a
team with no members `200 []` · members with no visible work `200 []`, the last two deliberately
indistinguishable. **And their ordering is a trap with a test**: answering an empty team *before* the
project gate is the obvious optimisation and turns "empty team + unreachable project" into a **200
that confirms the project exists**, so the shortcut lives inside the gated read. **`teamId` +
`mine=true` is a 400** (`twoBoardsProblem`, `unscopedBoardProblem`'s exact inverse) and the queue
records why it needs a test of its own: for a caller on the team the mutation is **unobservable in
the body**, since their cards are a subset of the team's. **`teamId` + `projectId` is legal** — §6l's
*"swimlanes by member"*, two axes (who, where) rather than two boards. `present` now filters over a
**set** with the personal board as the one-element case, still **after** `assemble`, so §83's
recorded cost is inherited unchanged. **Nothing was overturned**; three things were checked rather
than assumed, including that **`work_item.assignee_id` REFERENCES `app_user`** — §6l's team assignee
for the unclaimed pool **does not exist**, named as not-built rather than scoped out. Found and not
fixed: **the two boards are not protected by the same line** — dropping the grant filter from
`visibleProjects` leaves every `Gates` test green, because a *named* project goes through
`visibleProject`'s own copy. **Zero mutations run inline; six queued.** §6l's three boards are now
complete; what is left there is the **two remaining generators**, the **per-project toggles**, the
**cap**, and **notifications**. Before that: **§83, the cross-project board** — §6l's *"My Work"*, the view
it calls the one most people keep open all day, and the slice whose entire path work was **leaving a
parameter off**: §81 had already recorded that `/work-items?projectId=…` *"is the shape that
eventually drops the parameter"*, so there is no new route, no table, no key and **no migration —
`V27` is still free**. **The decision is that the aggregate is the PERSONAL board and the unfiltered
one is REFUSED**: §6l describes three boards and the cross-project one is the personal one, so no
`projectId` with `mine=false` is a **400, not a silently narrowed 200** — forcing the filter on
would answer with a list the client **cannot tell apart from a company holding only its caller's
work**. **Dropping the parameter removes the 404 that was implicitly bounding the board** (the
request names nothing that could be missing, so a member with no grants gets `200 []`), which is why
the bound has to become explicit: `ProjectService.readAllVisible`, `readVisible`'s plural, with
`visibleProjects` now shared with `listVisible` rather than written twice. **The failure it prevents
is a row in the fixture, not a hypothetical** — work can be assigned to somebody who cannot open its
project (this class's defect 4, from the write side, since §53), and a board built from *"cards
assigned to me"* rather than *"projects I can see"* renders that client's case key, title and
project name to the one person who will never report it. Hence three Acme projects, granted on two:
**with one reachable project, a board that quietly still reads a single project passes every
assertion in the class.** All four sources now take a **set** and the project board is the
one-element case — no per-project overload, because the copy that drifts is whichever board does not
exercise it — so six projects cost **4 statements, not 24**; the empty set is answered before any
query, since `IN ()` is a syntax error and a member with no grants is the *ordinary* first caller.
**The comments claimed a bound the code did not hold**: `present` filters *after* `assemble`, so the
400 bounds **the answer and not the work** — and §6l's cap, which open question #24 promoted from
prudence to a requirement, therefore lands on **the generators rather than the response**, located
here and deliberately not built. Frontend: **`/my-work`, the one feature route with no project
segment** (any project in that URL is an arbitrary one, naming a project most cards are not from;
`/admin/teams` is the precedent), reusing `Board`, which now takes no project because **neither
thing it used one for survived**. **Two repairs it could not have been written without.** `linkFor`
took the BOARD's project rather than the CARD's — §81 fixed *which screen* and left the project as
the route's, identical on any single-project board and, on the aggregate, a **404 on the one
affordance the card exists to offer**; §81's four link tests pass against the defect, and this is the
second repair that one function has needed in three slices. And **THE MOCK HAS BEEN STAMPING CARDS
WITH THE WRONG PROJECT SINCE PHASE 1**: `db.runs`/`db.cases`/`db.plans` carry no `projectId` (the
contract puts it in the path), so both generators iterated the whole store and labelled every card
with *whichever project was asked for* — the second project's board has been showing the first's
executions and flagged cases under its own key for the product's entire mock life, and what finally
disagreed was the aggregate, where one card appears twice **with the same derived id**. §81's
finding one slice later in the same file, and `mocks/db/workItems.test.ts` — still the only test
asserting the mock's own rules — gained seven. **Zero mutations run inline; twelve queued, 19 browser
rows.** Two to run first: dropping `visibleProjects`' grant filter (**exactly 1**, and it is the
security row) and `linkFor` reading the route again (**exactly 1** — *that "only one" is the finding,
not a shortfall*). Named next: the **two remaining board generators**, **§6l's team board** — now
the only one of its three with no endpoint — and **`/reports`/M2.5**. Before that: **§82, §6g's
gating rules** — the last line of §6g's mechanics
list, unimplemented since the plan was written, and the slice that built **almost nothing new**:
the ladders (§73/§75), the record (§76), the settings row (§77) and the board (§81) were all
already there, so what was missing was **two boolean columns and the two places that read them**.
**The slice is two decisions and both are about where a rule binds.** *(1)* **The plan rule is an
EDGE, not a target.** `PlanLifecycle` reaches `ACTIVE` from three states and only one is somebody
starting an unreviewed plan — the obvious *"target is ACTIVE ⇒ must be APPROVED"* reading compiles,
passes the draft test, the approved test, both override tests and the independence test, and makes
**every closed plan on a gated project permanently un-reopenable, by anybody, for ever**, because
`CLOSED` has no legal move back to `APPROVED` and `CLOSED → DRAFT` is refused outright. §68's and
§69's dead-end bound from a third direction, and **the code had drawn the line one slice before it
needed it**: §72 made `PlanStatusService` audit `CLOSED → ACTIVE` as `plan.reopened` and the others
as `plan.started`, so §6g's word *started* was already a defined term and `startsWithoutApproval`
re-uses it rather than forming a second opinion. Dropping the `current == DRAFT` half reddens
**exactly 1**. *(2)* **The case rule binds on the RESOLVED set**, or "create the run from the plan
instead" is a bypass that **costs no permission and leaves no trace**, on the path a release run is
most likely to take — and **the whole run is refused, never trimmed**, because a checklist quietly
shorter than the one asked for reports a pass rate over a denominator nobody chose
(`RunCreateService`'s own partial-materialisation argument, arriving through a door it did not
expect). **The override needed NO NEW KEY and the key is DERIVED rather than chosen**: §67's
`evidence:read`, §79's `suite:comment` and §80's assignment argument for the fourth time — a key
`V3` seeds to nobody is a 403 nobody can grant out of — so it costs the artifact's own
`case:approve`/`plan:approve`, whose holder **can reach the identical end state today by approving
first**. The override therefore widens nobody's reach; what it changes is the record, leaving the
cases honestly unapproved with `approvalGateOverridden` on the audit row where the workaround it
replaces **launders a sign-off nobody performed** into the history §6g exists to keep honest.
**Explicit, never automatic** (a lead silently exempted never learns their own project turned the
policy on), consulted **only where the gate would otherwise refuse**, so the flag can neither widen
reach nor turn an acceptable request into a refused one — and a caller sending it without the key
gets **the same 409, not a 403**, because they are authorized to make the request and merely not to
ignore the policy. **`ApprovalGating.Verdict` shares the VOCABULARY and deliberately not the rule**:
the two computations have nothing in common, and `overridden` means the gate **fired and was
overridden**, narrower than "the flag was sent" — writing it unconditionally records every ordinary
run as a policy bypass. **GATING IS NOT GOVERNED BY THE REVIEW OPT-IN, and that is §77's own rule
applied**: *"off" is narrow — it suppresses the apparatus and NOT the status ladder* — so a project
with review off still has the four statuses and can want approved-only runs without ever running a
peer review; ANDing them would disable the gate on exactly the projects that enabled it. Stated
rather than discovered: **turning review off does not turn gating off.** **`npm run build` caught
the mock dropping both fields**, and the same class is live in the form: the settings screen `PUT`s
all eight values onto a server that reads an absent boolean as `false`, so a field omitted from the
submit **switches that setting off on the next save** — an admin correcting the peer-review count
quietly reopens the gate, with a success message. **Five backend tests went red on the first run and
only one was about the code**: four were a fixture leak (`project_settings` was not on
`clearMutableRows`, so one test turned the gate on for every test after it — **three casualties
predate this slice and none is about gating**), the fifth a wrong persona (reopening is a move
*down*, so it costs `plan:approve`). **`ReviewSettingsPage` had NO TEST FILE AT ALL** — shipped that
way in §77, found while adding to it — and its load-bearing new test asserts the save body as an
**exact object**, because `toMatchObject` passes with a field silently dropped. **Found and not
fixed**: `TestRunInput` is hand-written rather than derived from the contract, so nothing
mechanically checks the request body; and `unapprovedCases` uses `<> 'APPROVED'` with **no test
covering `DEPRECATED`**, so the `IN ('DRAFT','IN_REVIEW')` enumeration passes the whole suite while
letting **retired content into a release run** — the mutation row says not to skip it for reddening
nothing, since that is what it reports. **`V26`; policy count unchanged at 58.** **Zero mutations
run inline; ten queued, fifteen browser rows.** **§6g is now COMPLETE** — every part of the workflow
has an implementation. Named next, and none of it review work: the **two remaining board
generators** (a feature apiece, not a generator apiece), **§6l's cross-project "My Work" board**,
and **`/reports`/M2.5**. Before that: **§81, the reviewer's inbox** — §80 ended by naming it and
§6l has named it since the plan was written (*"Review requested → card for each reviewer"*), while
`ReviewController` refuses the queue from the middle because it needs the subject **expanded**.
**The whole slice is where that expansion turned out to belong, and it was decided three slices
before the question was asked.** The query is `review ⋈ review_assignment` (`project`) `⋈
team_membership` (`identity`) `⋈ test_case | suite | test_plan` (`repository`, `execution`) — **four
tables, three modules, and no module may write it**: `project` owns the record and may depend on
`tenancy` alone, `repository` and `execution` hold one third of the expansion each and cannot see
each other. `work` may see all three and **`BoardFacts` is the file where that permission is already
concentrated**, so §80's three options (a fourth port, a read model elsewhere, the domain event §76
refused) collapse to the one that needed nothing built: the board's `REVIEW` generator, absent since
§53 *with the reason written in the package comment* — and the reverse reading is the design, since a
queue endpoint on `ReviewController` would have had to invent one of the three **and** would then
serve a second list answering "what is on my plate". **No contract change at all**: `kind: REVIEW`
went into `openapi.yaml` in §53 deliberately ahead of its generator, and a decision made 28 slices
earlier paid for itself. **A team is expanded at READ time, the mirror image of §80's refusal to
expand at write time** — §80's reason was *"membership moves"*, which is exactly what makes the
recomputed-every-read expansion correct, so §6g's *"any member of the team can action it"* is
implemented rather than declared; hence `UNION` not `UNION ALL` (asked directly *and* through a team
is asked **once**) and **one card per REVIEWER, not per review**, which is what makes a personal
board possible at all. **Three predicates and each removes a card that would never leave**: the
review's state (unassigning is the only delete, so **every request ever made is still in the
table**), no participation by that person (§6l's "card leaves", said about a verdict — and it leaves
when they have **spoken**, not when they agreed, or "changes requested" reads as work the reviewer
still owes), and the subject still existing (three `LEFT JOIN`s, because the triple has no FK — and
the broken version produces a card with **no key and no title**, which every `doesNotContain` passes
cleanly, so the test asserts an **exact set**). **`sourceType`/`sourceId` name the ARTIFACT, not the
review**, which looks like it breaks the other two generators' pattern and does not: those fields are
*what the card opens*, and a review has no URL of its own — §6g's panel renders on the artifact's
page. **THE FINDING IS THAT THE MOCK HAD SHIPPED THIS FEATURE, WRONG, SINCE PHASE 1**: `fromReviews`
drew a card for every case whose `lifecycleStatus` was `IN_REVIEW`, in the `IN_REVIEW` column,
assigned to the case's **owner** — a request **nobody had been asked to do**, on the board of the
person whose work is under review and whom the server *refuses* as an assignee (§80), in the wrong
column, and never for a suite or a plan because it read a *case* column. §52's "the mock agrees with
itself" in its most expensive form — the mock was not agreeing with a server, it was agreeing with an
**idea of the feature**, and it looked finished, which is why nobody built the real one for 28
slices. Nothing could catch it (the contract legitimately carries `kind: REVIEW`, the page test
supplies its own props), hence **`mocks/db/workItems.test.ts`, the first test that asserts the
MOCK's rules**. **The second defect the slice made live**: every card's key linked to a case search,
right while every linked card was about a case — a review of `TP-5` now renders *"no cases match"*,
which reads as the plan having been deleted; `linkFor` keys on `sourceType`, and **a suite goes to
the case list filtered to it because a suite has no page of its own**, the one subject type §6g can
review with no screen anywhere. §79's cast repair carried one file further (`} as WorkItem` gone from
a factory covering sixteen tests). **The one test that failed was the assertion, not the code** — the
exact-set check expected one `TS-1` card and found two, LEAD's and VIEWER's, both correct. **No
migration; `V26` is still free**, which is what §80's index-before-its-query was for. **Zero
mutations run inline; nine queued, eleven browser rows**, and **§80's row 14 is struck through**
because it recorded this absence as correct. Named next: **§6g's gating rules**, which now have
everything they need. Before that: **§80, reviewer assignment** — §6g's apparatus is
complete. `review_participation` has recorded who **spoke** since §76 and *nothing has ever recorded
who was **asked***, so the workflow §76–§79 built had **no inbox**: a review opens and the people who
should look at it find out through some other product. §6l names the same gap from the other end
(*"Review requested → card for each reviewer"*) and cannot draw a card for a request nothing stored;
`ReviewController`'s own header records the third sighting, refusing "all reviews in this project"
because the queue is *a different question*. **This is the first review slice that needed a NEW
TABLE** — §76, §77 and §79 each found theirs already in `V1` — and that absence is itself the
finding: **`V1` designed the record of what happened and never the worklist of what is
outstanding.** **The slice is one idea and everything follows from it: a worklist entry is NOT part
of the audit record.** That record is `review` + `review_participation`; this is a routing hint,
alive only while the review is open. So **unassigning is a HARD DELETE** — the opposite call from
`ReviewComment`, for the opposite reason, because a remark is something somebody *said* and a
request is something somebody was *asked*, and a trail of retracted requests reads as a history of
suspicion; **a deleted team CASCADES**, which is §71's 409 deliberately **not** extended, because
what is lost is a request rather than authority and refusing would make a team undeletable until
every review it was asked to look at had closed — a review whose only assignee is a dissolved team
being precisely the one that never closes on its own; and **re-asking somebody already asked writes
nothing**, §79's re-stamp defect in the one place it recurs, since an unconditional write hands
`assigned_by` to whoever clicked last. **No new key was invented** — one seeded to nobody is a
403 no administrator can grant their way out of, the argument §67 used against `evidence:read` and
§79 against `suite:comment` — so asking costs the subject's `*:review`, §79's argument for resolving
a thread. **The two cheap readings are both wrong and each
names a persona**: `case:comment` would let a Viewer, *often the client themselves*, put work on a
contributor's queue, and `*:approve` would confine the request to the very people whose sign-off it
exists to **prepare**. **The mutation is a PAIR and neither half means anything alone** — the
approve swap reddens exactly one test, the comment swap exactly one *different* test, because the
key must be neither the tier below nor the tier above; this is the shape §71 could not achieve for
`company:teams`, where `V3` grants both candidates to identical roles. **The assignee is validated
on project VISIBILITY, not company membership** — §71's bar one table over and the wrong one here,
because a colleague with no grant cannot open the artifact, so asking them produces a request that
sits on their list for ever; `assignee_user_id` references instance-level `app_user`, so a sibling
company's account satisfies the FK, the company column, RLS and every constraint (§64's family a
fifth time). Both refusals carry the **same sentence**, or the endpoint is an instance-wide account
oracle (§6t). **The submitter is refused and the rule is DERIVED, not invented**: `standingLgtms`
already skips the submitter's own LGTM, so such a request could never be discharged.
**`TeamDirectory` is the third port in `project`** and deliberately not a method on
`PeopleDirectory`, whose one rule is §6u's company-membership naming that a team has no analogue
for; it has **two** methods because a render tolerates a missing team and a **write must refuse
one** or the product stores an unchecked `team_id` (§64), and it must never answer membership — a
team is not expanded into per-member rows, because membership moves. **`V25`'s uniqueness is TWO
PARTIAL INDEXES**, because a plain four-column UNIQUE enforces nothing: every team row has a NULL
user and NULL ≠ NULL, so the same team could be asked a hundred times (§20's `COALESCE` trap solved
the other way). Policy count **57 → 58**, and a fourth index lands **before its query** so §6l's
queue does not open with a migration. **THE BUILD CAUGHT THE CONTRACT SLIP**:
`ReviewAssignmentInput` shipped with no `required` array and `ContractConventionTest` went red —
both fields are required-**and**-nullable (`RoleAssignmentInput.projectId`'s shape), or "ask this
person, nobody else" is expressible only by omission and a dropped field is indistinguishable from a
choice. **§79's cast repair paid off one slice later**: adding `assignees` to `Review` was **one
build error in one place**, where the same change under `as Review` turned eleven tests red at
runtime with zero type errors. **Four frontend tests failed on the first run and all four for §66's
reason** — `toBeEnabled()` on a MUI Select's **display div** is satisfied while `useCan` is still
pending, so the click landed on a disabled control; the barrier is now derived from the gate
(`useProjectMembers` is `enabled` on `allowed`, so the `/members` request firing IS the proof). A
fifth failed because MUI's default Chip delete icon carries no identifier. **A doc comment was false
when written** — it claimed the picker filtered the submitter and it did not — and was **implemented
rather than deleted**, because a picker that can produce a 400 is the one thing it must not be.
**Zero mutations run inline; eight queued, fourteen browser rows**, and rows 3–4 are live-only
because mock mode signs in as one persona holding every key. ~~**§6g's apparatus now has no INBOX, and
that is the next slice**: nothing outside the panel surfaces a request, and the queue needs the
subject **expanded**, which means reading `repository` and `execution` from `project` — a fourth
port, a read model elsewhere, or the domain event §76 refused.~~ → **built in §81, above, as the
second of those three, in the module that already held the edge.** Before that, and not summarised here
because that section did not prepend to this header: **§79, review comments** — the one comment key
where there are three review keys, and the resolution that named nobody; plus **§77, per-project
review settings** (`V23`) and **§78, open-question decision round 2**. Before
that: **§76, the review record** — `review` and
`review_participation` have been in `V1` since the beginning with **no entity, no query and no
writer of any kind**, and §75 named this slice on its way out by *deriving* it rather than listing
it: once the suite's ladder landed, **every item-side ladder existed** (case §62, plan §73, suite
§75) and what was left was what §6g puts *inside* `IN_REVIEW`. **Three permission keys get their
first consumer at once** — `case:review`, `suite:review`, `plan:review` — the seventh instance of
§71's search, and the first time keys were left unconsumed *deliberately*; `case:comment` stays
unconsumed and that is also correct, because `V3` grants it to **Viewer** and the review tier to
Test Engineer, which is §6g's three tiers made observable. **The placement decision is most of the
design.** A review's subject is an **opaque triple** with no FK, which is what lets one record serve
all three artifacts — and three writers in *two* modules must reach it (`SuiteStatusService` and
`CaseBulkService` in `repository`, `PlanStatusService` in `execution`), which cannot depend on each
other. So it lives in **`project`**: `review.project_id` is `NOT NULL`, every gate on it is
project-scope, and its every FK already points at the three tables plan §3 permits. **A `review`
module was considered and refused** — it needs plan §3 and `ModuleStructureTest` amended and *still*
could not be called by either module (a fourth foundation module is "a decision, not an import"),
leaving the product's first domain event, whose type could live in neither end. What `project` costs
is that it may depend on `tenancy` only, so names resolve through **`PeopleDirectory`**, the port its
own package comment prescribes. **And writing the third consumer of "what is this person called"
found the first two**: `CaseOwners` and `ExecutionPeople` were byte-identical copies of §6u's naming
rule, the second under a comment saying *"the two classes must not disagree"* — §58's E1, surviving
because neither module can import the other; both now delegate. **The ladder tells the record where
the artifact landed and nothing else** (`ReviewStanding`, three values) — and deliberately **not**
where it came from, because the open review row is that fact and is the better one. **`V22` adds a
fourth state because a withdrawal is not a rejection**: the shipped ladders put `IN_REVIEW → DRAFT`
on the *edit* key, so one edge is walked by two acts, and closing both as `REJECTED` writes a
rejection nobody performed into the record kept to show an auditor who said what; the distinction is
**derived, never sent**. Its other half is a **partial** unique index — one *open* review per
subject, while the closed rows are the history. **The participation endpoint cannot write an
`APPROVAL` row at all**: `APPROVED` and `REJECTED` are refused as field errors, because accepting
either is a second, cheaper door to the sign-off §6g gates once, held by everyone with the advisory
key; the gate is decided by the row (§69's seam, fifth consumer) and resolves through
`findInProject`, never `findById`, which would accept a **sibling project's** review. **The record
starts when a writer exists** — an artifact already `IN_REVIEW` has no row on any real instance until
it is re-submitted, so the panel reads "Not in review" beside a chip saying In review, correctly.
**Seven mutations run, three queued — and running them at all was a drift the user corrected the
same day**: §65 defers mutation checks *in full*, §72–§75's inline handfuls made the exception look
like the rule, and both queue headers plus `CLAUDE.md` now say **write the row, run nothing**. The
fifth is still the one to read: it **failed to redden**,
because `waitFor(...toBeDisabled())` is satisfied by `useCan`'s pending state, so a panel with a
hard-coded permission passed it. §66's finding inside this slice's own tests, caught only by running
the mutation. **`V23` is the next free number**, and the per-project settings row now blocks four
things at once. Before that: **§75, the suite lifecycle** — `suite.lifecycle_status` had
**exactly one writer**, `Suite.create` writing `DRAFT`, so three of four values were unreachable and
**every suite on every real instance has been a draft for its entire life**; `Suite.applyEdit`'s own
javadoc named the reason its mutator was absent and, by naming it, named this slice. §72's and §73's
finding one aggregate over. **§74 named the reviewer apparatus as next and this is deliberately not
that** — §28's rule applied, re-deriving from the code found §6g's ladder has *three* subject types
and the suite's had never been built, which is a better place to start the reviewer half from than
a `V22` and three tables. **The ladder is ONE ladder**: `LifecycleStatus` is the status of a suite
*or* a case, `V1` spells the identical constraint on both tables, so `LifecycleLadder` is read by
both writers rather than maintained twice (§58 E1); the *plan's* is deliberately not folded in,
because `ACTIVE`/`CLOSED` are an execution phase these four do not have. **And writing the second
consumer is what exposed the first one's defect.** `PlanLifecycle`'s header named
`BulkOperations.permission` as the weaker target-keyed shape when §73 derived the edge rule — and
then §73 fixed only plans. On a case it was **live**: `case:approve` was asked only for a move
*into* `APPROVED`, so a Test Engineer could select two hundred approved cases, ask for `DRAFT`, and
**rescind a lead's sign-off with the permission they use every day**, silently, through a dialog
built for that volume. **Leaving an approval is an approval act too** — three acts cost the approve
key (granting the sign-off, retiring, and taking either back), and the fix is **per row**, because
one end of every edge is the row's stored status and a filtered selection mixes statuses; raising
the floor instead would refuse an engineer submitting their own drafts, which §6g says needs only
edit rights. **The mutation is the proof**: dropping the unmaking arm reddens **exactly 2 out of
112**, one per aggregate — which is both the evidence that the two ladders are genuinely one and
the evidence that the case half was not hypothetical. **Three case transitions changed and each
reversed something recorded**: `APPROVED → IN_REVIEW` no longer exists (the test asserting it
justified itself in a comment that was wrong twice over), `DEPRECATED` returns only to `DRAFT`
(§6g's clone rule — approval attaches to reviewed content by a named person), and deprecating now
costs the approve key because retiring is the soft form of a delete that Test Engineer is withheld
from outright; the `OWN`-qualified refinement is named and not built. **The fixture finding runs
both ways at once**: the backend fixture hard-coded every seeded suite `APPROVED` while no real
suite has ever been anything but `DRAFT` (§72's rule, applied to a test fixture), *and* the mock's
fixtures have carried approved folders since Phase 1 — so **mock mode has been showing a signed-off
tree the product could not produce**, which is §74's exit criteria one aggregate over. A status
change writes **no** `suite_version` row (composition, not a verdict — §73's call); the read-back
helper moved to `SuiteService.viewOf` before a fourth `CaseRenderer`-style copy could exist. **Six
mutations run, three queued** — four backend (**2 / 3 / 1 / 2**) and two frontend, the latter not
optional because all 17 of that file's tests passed first time. **No migration; `V22` is still
free**, and the reviewer slice will need it. Before that: **§74, the plan editor** — **three things had never been
written by anything and all three are in one aggregate**: `entry_criteria` and `exit_criteria`, so
`PlanCriteria.evaluate` and the exit-criteria panel §46 built above it have **had nothing to evaluate
on any real instance for their entire lives**; `test_plan_version`, the last of §6j's four versioned
aggregates with no writer; and a plan's **scope after creation**, which could only be changed by
deleting the plan and losing its key, its runs and its history. **The orientation finding changed the
shape before any Java — you could not write back what you were served**: the detail served
`caseCount`, an *expansion*, and no scope ids at all, so a picker would have had to invent what it
submitted. §68's finding one aggregate over, arrived at the same way, and the rule it produced is now
general — *a field a `PATCH` accepts must be readable from the `GET` before it*. **A plan version is
its SCOPE**, which is what makes most saves write nothing: `V1`'s own column comment plus §6j, so a
**rename writes no version** (`suite_version`'s identical call) and `caseCount` and `configurations`
stay out of the snapshot because one is derived and the other is a *project's* list. **One value
serves as both snapshot and hash**, a deliberate divergence from `CaseVersions` — nothing in a plan's
scope moves on its own, so splitting them would be two definitions of "changed" maintained side by
side. **The mutation is the proof**: bump-on-every-save reddens **exactly 3** while every scope and
criteria test stays green. **Absent, null and empty are three instructions**, split by *kind* —
scalars clear on null, lists leave-alone on null and clear on `[]` (§68's rule, now stated
generally) — and that is what keeps `TestPlan.create`'s *nobody has decided* distinguishable from
*there are deliberately none*; reading it wrong reddens **4**, two of them the version tests, because
a rename would empty the scope. **The metric vocabulary is closed because a typo is invisible**: an
unrecognised metric renders *identically* to one the product cannot evaluate yet — both a gate with
no verdict — and a release meeting cannot tell them apart, so the check is at authoring time;
`isKnownOperator` **calls** the comparator rather than listing the six operators twice (§58 E1, §70).
**A closed plan refuses the edit with 409, giving `PlanLifecycle.isClosed` its first consumer** —
§73 wrote it and nothing called it — with §72's exact placement re-derived: after the permission, before
the `If-Match`, before validation. **The defect the slice found is the optimistic lock that could not
fail**: `TestPlan` mapped `lock_version` and **never annotated it `@Version`**, so it was `0` on every
plan since M2 slice 2 while the contract declared it *required* — an `If-Match` could not fail,
because there is no such thing as a stale 0. §73's own lesson applied: the sweep found **three**
entities missing it — `TestPlan`, `TestRun`, `work.WorkItem` — and only the first is fixed, because
neither of the others serves `lockVersion` and adding it to `TestRun` would make two testers recording
results on one run collide on the product's most-used write. **Six mutations run, four queued** —
four backend (exactly 3 / 2 / 4 / 1) and two frontend, the latter not optional because 8 of 10
frontend tests passed first time. **The MUI checkbox trap fired a third time** despite §71 extracting
a helper for it, because the dialog was written from `PlanCreateDialog`, which predates the helper and
still carries the bare spelling. **No migration; `V22` is still free.** Before that: **§73, the plan
lifecycle** — `PlanStatus` has five values,
`TestPlan.create` writes `DRAFT`, and **nothing else has ever written that column**, so four of the
five were unreachable and **no plan on any real instance has ever been in review, approved, active or
closed**; `test_plan.closed_at` was a `V1` column the entity did not even map. §72's finding one
aggregate over, found the same way, and named twice in `TestPlan`'s own javadoc — *"there is no
mutator, because `plan:edit` is a separate permission and a separate slice"*. **Three permissions get
their first consumer in Java at once** (`plan:edit`, `plan:approve`, `plan:delete`), the sixth
instance of that shape; **`plan:review` stays unconsumed and that is the correct answer**, because
§6g puts peer review *inside* `IN_REVIEW` accumulating on the item without moving it, so the key
belongs to the reviewer apparatus and not to a transition — as do `case:review` and `suite:review`.
**The slice is one decision: the gate is decided by the EDGE, not by the target.** The natural
reading of §6g — `plan:approve` guards the move *into* `APPROVED`, the shape `BulkOperations` uses
one module over — compiles, passes an "an engineer cannot approve" test, and **lets that same
engineer rescind a lead's sign-off by asking for `DRAFT`**, the button beside it. So `permissionFor`
takes both ends: `plan:approve` grants the sign-off, ends the plan, and **takes either back**;
`plan:edit` carries a plan forward through the states nobody has signed off. Roles spec §A4 principle
1 unchanged, and the reason the gate reads a **stored** fact — `writeGrantedOnTarget`, §69's seam,
second consumer. **The mutation is the proof**: dropping that arm reddens **exactly 2** while every
other gate assertion stays green, so the cheaper implementation would have shipped with a full class
of tests passing. **The ladder must not require approval**, because §6g says review is optional, off
by default, per project — and that setting does not exist yet — so `DRAFT → ACTIVE` is legal and
review is a detour; two "reachable only from" rules survive it, `APPROVED` only from `IN_REVIEW`
(§62's call) and `CLOSED` only from `ACTIVE` (a plan nobody started, recorded as a finished release,
is `RunLifecycle`'s 0%-executed run). **Delete and close partition the space**: `plan:delete` refuses
with 409 and the count while a live run names the plan — `findPlanKeys` is not filtered on
`deleted_at`, so nothing breaks loudly, the runs keep rendering `TP-3` and the link goes nowhere —
and the way out is `CLOSED`, not force. **Delete a plan nobody executed; close one somebody did.**
**Two pre-existing defects, both in one file, and the first is the lesson**: `PlanListPage`'s colour
map is the *sibling* of the defect §72 found on the run list and §72's sweep never reached it —
`Record<string, string>`, carrying an **`ARCHIVED`** that is not a plan status, **missing `IN_REVIEW`
and `APPROVED`**, with `status.toLowerCase()` beside it; a fix applied to one instance of a class
needs the grep that finds the others. And the exit-criteria column counted `!c.met`, where **null
means *not evaluated*, not *unmet*** — so a plan whose gates were green would have read "3 unmet",
unfireable only because `exit_criteria` has no writer, **which the next slice gives it**. The mock's
fixtures gained a `DRAFT` and an `IN_REVIEW` plan: §72's lesson applied rather than restated, since
every fixture plan was `ACTIVE` or `CLOSED` while every real plan was `DRAFT`. **Six mutations run,
four queued** — four backend (exactly 2 / 3 / 1 / 1) and two frontend, the latter not optional
because all 7 frontend tests passed first time. **No migration; `V22` is still free.** Before that:
**§72, the run lifecycle** — `test_run.status` had been
written **exactly once in the product's life**, to `NOT_STARTED` by `TestRun.create`, so on every
real instance **every run has always read "Not started"** — including after every one of its results
had been recorded — and `started_at`/`completed_at` were columns nothing ever filled. Three
production javadocs name this slice by name: `TestRun`'s *"Still no mutator: closing a run is
`run:close`, a separate slice"*, `Evidence`'s *"the invariant belongs with `run:close`"*, and
`RunDetailPage`'s comment stating the rule it renders — *"a run starts when somebody records the
first result"* — **which nothing implemented**. **Not §52's "mock agrees with itself"**: the mock had
the same hole, and looked right only because its fixtures ship runs already `IN_PROGRESS`, so a run
*created* in mock mode was as stuck as one created live. One shape, two implementations, both absent,
and a fixture hiding it from both — the check that would have caught it is that **the mock's fixtures
never sit in the state a fresh install produces**. **`run:close` is the fifth permission with no
consumer in Java and the first that DISCRIMINATES**: §71 had to record that its mutation could not be
written, because `V3` grants `company:teams` and `company:members` to the same roles — here `V3`
grants `run:close` to Lead and up and **withholds it from Test Engineer**, who holds `run:execute`
and `result:submit`, so the gate swap reddens **exactly 1**. **"Closed" is a status PAIR, not a fifth
value** — `V3` calls the permission *"Close or abort"*, two acts, and `RunStatus` already has two
terminal values whose difference its own javadoc explains; a fifth would give the product two ways to
say a run is over and force every reader to know both. Hence **no migration; `V22` is still free**.
**Starting is automatic, finishing is not, and the asymmetry is the gate**: the first result starts
the run (stamped with the *result's* instant, or a run sorts after the first result in it) and needs
no permission, while a run never completes itself — auto-completion would let a Test Engineer close a
run by finishing it, which is exactly the line §A4 draws. **Reopening exists because a mis-click must
be repairable** (§68's and §69's bound, from the other side) and its target is **derived, never
chosen**: both live spellings are normalized server-side, so a client cannot produce an `IN_PROGRESS`
run with a null `started_at`. Two transitions are refused and both refusals are the feature —
completing a run with nothing recorded (that is what ABORTED is for) and sliding from one ending to
the other (the audit trail would show one and the row another). **§67's deferral is closed on its own
stated terms**: it named exactly what had to be true first — a real closing act, and `record`
refusing to amend a closed run — so the freeze lands on **all three writes at once** rather than
making the screenshot stricter than the verdict. Three placements carry the reasoning: **after** the
permission (a Viewer still gets 403, not 409), **before** the `If-Match` (a stale header is fixable
and this is not), and **before** authorship on the delete (a closed run refuses *everybody, the
author included* — "this is not yours" would name a fix that does not exist). **The slice had to
delete a duplicate before it could start**: `ExecutionService#resolve` and `EvidenceService#resolve`
were byte-identical private copies under a javadoc saying they were the same, and both needed the
same change — the freeze must see the *run*, which the old signature threw away. §58's E1 and §70's
verb list again; hence `ExecutionTargets`. **Two pre-existing defects found.** `RunListPage`'s colour
map was typed `Record<string, string>` and had drifted precisely as `lib/labels.ts`'s header
predicts — it carried a **`PLANNED`** that is not a run status and was **missing `NOT_STARTED`**, so
on a real box *every run rendered grey*; both screens also spelled the status with a regex rather
than a label map. And `ExecutionService` writes its audit `target_type` as `"TEST_EXECUTION"` where
every sibling writes snake_case — **found and not fixed**, because nothing reads the column yet
(`audit:view` has no consumer) and changing it would split existing rows. **Seven mutations run,
three queued** — four backend (exactly 1 / 2 / 2 / 1) and three frontend, the last not optional
because **all 7 frontend tests passed first time**, which §66 established is the moment to distrust
them. Before that: **§71, team management** — `team` has been in `V1` from the
beginning with **readers on three sides and a writer on none**: `GET /teams` renders the org chart,
`RoleGrantService` accepts a team as a grant's subject, and `PermissionEvaluator` resolves a
caller's teams inside **every one of its five union queries** — and nothing could create one.
`provision-company` seeds no team, `Team.create` had **only test callers**, `rename`/`reparent` had
**none at all**. So on every real instance that has ever run `GET /teams` answered `[]`, and
`POST /role-assignments`' team branch — built, validated, tested, documented — **could not be
reached with a `teamId` that named anything**. **Mock mode looked finished the whole time**, because
the fixtures carry three teams with members, leads and project chips; §52's "mock agrees with
itself" class, and why this survived to §71. **`company:teams` gets its first consumer in Java** —
seeded by `V3`, granted since M0, never once asked: §62's `case:approve`, §64's `library:manage` and
§69's `company:vocabulary` for the **fourth time**, and the pattern is now reliable enough to be a
search. Five endpoints, gated at COMPANY scope. **`PUT` not `PATCH`, for `parentTeamId`** — an
absent field and an explicit `null` are the same bytes, so "move this to the root" would be
unreachable (§69's reason) — **and a team may actually be MOVED, which is where the two part
company**: §69 refused it because the values below a classification value are what cases are
classified by, while a team's parent edge is **org-chart structure and never a permission edge**
(§6k), so a move changes a picture and confers nothing. **The substance is the membership check.**
`team_membership.user_id` references instance-level `app_user` while `company_id` comes from the
session, so a **sibling company's account** satisfies the FK, the company column, RLS and every
constraint — §64's `sharedStepId` family one level up — and then two things happen at once: the
evaluator's five projections resolve teams through `team_membership` **alone**, never joining
`company_membership`, so that person holds whatever the team holds; while `listTeams` renders them
**nowhere**, because `refs` drops an id it cannot place. **The read side hides the evidence of the
write side, and an invisible grant is worse than a leaked name** — so the test asserts *404 **and no
row written***, which a status-only assertion would have missed. 404 rather than a field error, or
the endpoint is an instance-wide membership oracle (§6t); `INVITED` counts, `REMOVED` does not.
**Delete refuses and the refusal is the feature**: `role_assignment.team_id` is `ON DELETE CASCADE`,
so deleting a team holding grants would revoke every one **silently** — 409 with the count, §69's
live-children shape; child teams likewise, since `parent_team_id` has no cascade and the
alternative is a 500 or silently promoting somebody's sub-teams. Members do **not** block a delete.
**`V21` fixes a backstop that was strictly weaker than its check**: `V1`'s `uq_team_name` is
exact-match and had **never adjudicated a single insert**, while `TeamEdit` folds case — and an app
check stricter than its constraint is a check with no backstop, so two admins racing "QA" and "qa"
both win. **The mutation that cannot be written is the finding**: both were run inline (gate →
constant reddens **exactly 6**, dropping the membership check **exactly 2**), and **neither can tell
`company:teams` from `company:members`**, because `V3` grants both to the same roles and every
persona the suite can build holds both or neither. §61's "vacuously true" shape, recorded rather
than papered over — `SeedDataTest` now fails the day a role holds one without the other (**and that
is the good case**: add the persona to `TeamEndpointTest`, do not re-align the seed), while
`TeamsPage.test.tsx` carries the only test that can tell them apart today, because a frontend test
can mock a permission set the seed cannot produce. **§68's orphan rule matters more here than it did
there**: `TeamTree` descended from the roots, so an orphan or a cycle member was silently dropped —
cosmetic while read-only, and now the tree is the **only** place a team can be edited or deleted, so
a row it does not draw is a row nobody can fix. **A third copy of one helper was about to be
written** — `inputTestId`/`selectTestId` were already two, each carrying its reason in its own
comment (§58 E1's shape); both now import `lib/muiTestIds.ts`, and the trap fired again immediately
in a new way: the parent select's id went on MUI's **hidden native input**, so the first draft failed
with `pointer-events: none` on the affordance rather than on the assertion. Before that: **§70, the verb the contract checks could not see** — §69's
build-status note recorded four frontend tests it could not account for and left them for "one grep
next session". The grep found the author and, through it, a defect open since §53. The three
contract-hygiene checks are **data-driven** — one `it.each` case per `api.*` call site discovered
through the TypeScript compiler — so §69's three new endpoints generated tests nobody wrote; but the
arithmetic is **2 + 2, not 3 + 3**, and the missing third is the finding. Both checks carried their
own copy of the client's verb list, `['get','post','patch','put','delete']`, under the comment *"as
spelled on the client"* — and **the client spells it `del`**, plus an `upload` that was never in the
list. `grep -rn "api\.delete"` returns **nothing**: that string matched no call site on the day it
was written and none since, so **eight call sites were invisible to both checks** — every delete in
the product (`cases`, `suites`, `admin` ×2, `documentation`, `runs`, `projects`) and §67's evidence
upload. **Nothing could catch it, and that is the section**: both files guard against vacuity by
*count* (`>= 40`, `>= 25`) and by distinct-verb count (`>= 3`), and four verbs sail past all three.
A guard by count answers *"is this check still running?"* — it was, perfectly, over a client one
verb smaller than the one that ships. §48's rule arriving **inside the §48 family of checks**, and
§58's E1 shape (a hand-maintained copy of another module under an instruction to keep them in step,
already drifted; there too the fix was to delete the copy). The verb set is now **derived from
`client.ts`** through the program that was already being built, mapping each member of the exported
`api` object to the method it actually sends — and **the mapping matters as much as the
membership**, since path items are keyed by the OpenAPI spelling, so `del` must arrive as `delete`
or the repair only swaps "unchecked" for "compared against a method no path item can declare".
`unresolvedApiVerbs` is the other half: a verb whose method cannot be read is exported and asserted
empty, so an unfamiliar shape **fails** instead of narrowing the check silently. `apiResponseTypes`
also built its own second `ts.createProgram` and now shares the module's. **The sixteen newly
generated cases all pass** — the blind spot was real for eight slices and this time empty, which is
evidence about the contract's health rather than evidence the check did not matter. Both mutations
run inline (the slice *is* a check): the unmapped spelling reddens **exactly 8**, and a verb the
reader cannot parse reddens **exactly 2** — one guard per file, the property that did not exist
before. Class swept: `OPERATION_KEYS` describes the spec format rather than another module,
`ContractParameterBindingTest` reads the live `RequestMappingHandlerMapping`, and
`contractParameters.test.ts` keys off `qs(...)` rather than verbs — two instances, both fixed.
**No runtime code changed, so no browser row is owed**; the frontend queue records that decision
rather than leaving the slice absent. Before that: **§69, the classification vocabulary editor** — `classification_value`
had exactly one writer since `V1` and it was a SQL seed function, which creates the Feature dimension
**empty on purpose**; §68 built the case editor's tree picker against it and recorded what was left,
that the tree was **usable and not fillable**. §34's "provisioned company *plus hand-written SQL*"
one vocabulary over. **The orientation pass changed the shape twice before any Java.** First,
**`locked` meant three different things in three documents** — `V2`'s seed says *"a client editing
them is fine"*, the view's javadoc said *"the value list is not editable by the client"*, and the
roles spec grants `company:vocabulary` covering *"Test Type / Level / Phase values"*. Two of three
say editable, and the third makes a seeded, correctly-granted permission **cover nothing anybody can
do** — §62's `case:approve` a third time. Settled: `locked` says **whose the dimension is**, and the
reading that now has a consumer is *which permission at which scope*. Second, **`@ColumnTransformer`
was missing exactly as `Suite.path` predicted by name** — *"it will fail exactly like this on the day
something does"* — and today was that day. **The gate is decided by the row, in KEY and in SCOPE**:
`project:settings` on this project for the project's own Feature tree, `company:vocabulary`
**company-wide** for the three axes the company shares — two different *questions*, since asking a
company key at project scope is the shortcut roles spec §A3 records rejecting. `company:vocabulary`
gets its **first consumer in Java**. Hence `RequiredPermission` (key + scope) and
`ProjectService.writeGrantedOnTarget`, which resolves the target **before** asking anything, because
the permission to ask *is a property of the target* — a caller whose dimension does not resolve
cannot be told 403 without the server inventing which permission they failed. The dimension resolves
through `findForProject`, never `findById`: the latter is scoped by RLS to the **company** and not
the project, so it accepts a **sibling project's** Feature dimension and every cross-company test in
the suite still passes. **The `ltree` label is derived from the id, not the name** — `Suite.create`'s
decision plus one the suite tree does not have: `tcms_ltree_label` answers the **empty string** for
`支付`, and an empty label is not a valid `ltree` label, so a name-derived path fails the insert with
a message about the *path*. A Feature value is the client's own taxonomy in the client's own
language, which is exactly where that arrives first. **What it refuses is the feature**: a move
(`SuiteWriteService`'s reason plus "the values below this one are what cases are classified by"), a
change of dimension (a value that changes axis is a different value), and a retire with live children
(409 with the count — a cascade unclassifies every case beneath it and **none of those assignments is
restorable through the API**, §68's bound from the other end). A value merely *in use* is **not**
refused; that is what the soft delete is for. **`V20`** backs the sibling-name check with a
**partial, per-parent, case-insensitive** unique index — per-parent because §68 turned on two leaves
sharing a name under different parents, `COALESCE(parent_id, nil)` because NULL ≠ NULL would exempt
every root, partial because a retired name must not permanently block a new value. A **backstop, not
the check**. The screen is `/projects/:id/settings/classification`, all four dimensions with the
company's greyed and explained (§31), the tree from §68's `buildValueTree` — whose orphan-surfacing
matters here in mirror image, since **a value nothing renders is one nobody can retire**. The mock
moved `dimensions` into the store and `handlers/cases.ts` with it, or a value added in the editor
would be refused by the case editor as "not in the vocabulary". **Two test findings.** All 25 backend
tests passed first time, and the first mutation reported **4** where the honest answer is **3** — a
broken gate let one create *succeed*, so a second test failed on the *duplicate* check rather than
the gate; a mutation count that measures the fixture reads as extra confidence. And the frontend test
failed for §66's reason before it failed for a real one: rendered bare rather than through a
`<Route>`, `projectId` is `''`, `useCan` is disabled and permanently pending, so **every "disabled"
assertion would have passed with no gate in the code at all**. Four backend mutations run, three
queued; one frontend mutation run. Before that: **§68, classification assignment** — `case_classification` has
been in `V1` since the beginning with **three readers and no writer**, and it was one of
`CaseUpdateRequest`'s **"refused six"**, whose javadoc has said since M1 that each of them is *"a
boundary a later slice deletes in one place"*. `CaseVersions`'s header predicted the same slice by
name. It is the **last of §5's three scheduled Phase-2 gaps** and the one §5 called the largest.
**The orientation pass changed the shape twice before any Java.** First: **you could not write back
what you were served** — `TestCase` serves `features`/`testType`/`testLevel` as **names** with no id
anywhere, while the writer takes uuids, and there is no mapping, because the Feature dimension is
hierarchical (two leaves under different parents may share a name) and the company vocabularies are
renameable. Hence `classificationValueIds` on the detail, **filtered by the same live-vocabulary rule
`CaseSummaries` applies to the names** — one implementation, so the ids and names on one response
cannot disagree. Second: **there is no leaf-only rule.** The editor kept the field read-only for two
milestones on the stated grounds that a select *"would let someone set a parent where a leaf is
required"*; `grep -i leaf` across the plan, the ERD, the roles spec and `V1` finds only §6p's count
arithmetic, and §6q's descendant rule is about **filtering**. What a flat select really loses is the
parentage — a smaller problem, wanting a tree. §51's evidence blocker was wrong the same way, so
**the orientation pass's third question is now "is the stated reason actually written down
anywhere"**. **The slice's real content is the reconcile's bound**: a retired value is a soft delete,
so every read drops it, so a client is **never shown one and cannot resubmit it** — and a reconcile
reading "absent" as "delete" would silently discard a classification nobody chose to drop, on the
first save of any case carrying one. Removal is therefore bounded by the vocabulary the caller was
served, and the consequence is stated rather than discovered: a retired assignment is **immutable
through the API**. §64's rule about stored references, from the delete side. **The sibling project's
value is why validation exists at all** — it satisfies the FK, the company column, RLS and every
check `V2`'s trigger makes, so unchecked it is *stored*, and every later read resolves a
classification through a project the reader holds no grant on; the **third column in that family**
after `suiteId` and `sharedStepId`. Two values on a SINGLE axis are a **400, not the trigger's
`23505`** reaching the client as a 500; duplicates are de-duplicated rather than refused, because
`(case_id, value_id)` already says a case carries a value once. **The edit's list is sorted**, which
is a hash requirement — `CaseVersions#hash` rests its determinism on there being no *set* in the
shape — and `CaseEdit.of` carries the stored ids so a bulk action's version row does not hash as
though the case were unclassified (§62's mis-attribution defect, on a field that class never reads).
**Two pre-existing defects found on the way.** `TestCaseDetailView` **never served `createdBy`**,
though `TestCase` is an `allOf` of `TestCaseSummary` which requires it — and `CaseDetailPage` reads
it from that endpoint, so it was always null, null is *a supplied author matching nobody*, and
**Delete was disabled on every case for every `OWN`-qualified Test Engineer**: §66's headline feature
inverted on the only screen with a delete affordance, shipped through §67. Nothing could see it —
`tsc` allows optional chaining on a required field, the page's own test supplies a fixture that has
the key, and the detail endpoint's field test enumerated the required list **by hand**, written
before `createdBy` existed. That list is now a **computed check** resolving `required` through
`allOf`, verified by re-introducing the defect. And **`CaseDetailService` was a fourth copy of the
render `CaseRenderer` exists to prevent** — the `GET` path was never moved onto it, which is the
wrong way round since it is the render the writers exist to agree with; invisible while the copies
agreed, visible the moment a field was added. **The picker renders from `cardinality` and
`hierarchical`, never the dimension key**, and `buildValueTree` **surfaces orphans *and cycle
members* at the root** where `SuiteTree` drops the latter — in a picker, a value with no control on
screen is silently deselected by the next save. §62's lesson again in its own test: a bare
`data-testid` on a MUI `Checkbox` lands on the wrapper span where `toBeChecked` cannot see it. Four
mutations run, four queued. **No migration; `V20` is still free.** Before that: **§67, evidence attach and delete** — `evidence` and
`attachment` have been in `V1` since the beginning with **no reader and no writer**, and
`EvidenceView`'s own header said so: *"always an empty list in this slice"*. So the execution screen
has rendered a field the product could never populate for the whole life of the module. Both halves
land, and `evidence:delete` gets the **`OWN` consumer §66 named on its way out**. **The orientation
pass changed the slice's shape before any code**: `Evidence.url` is a **required** field with no
route behind it, so attach alone would have had to *invent a string* every consumer may dereference
— hence the download endpoint in the same commit, session-authenticated because a filesystem adapter
cannot sign a URL (#28/§51) and needing **no permission**, since the catalogue has no
`evidence:read` and inventing one is a 403 nobody can fix. **The port lives in `execution`, not a
`storage` module**: `ModuleStructureTest` hard-codes both the module list and the
tenancy/identity/project FK rule, so a new module means amending plan §3 and two architecture tests
to serve **one** caller — `forExecution`/`forStepResult` keep the eventual move honest, since no
call site spells an owner type. **The genuinely new problem is that a filesystem does not roll back
with the transaction** — the first product state outside Postgres. Rows first, bytes second, in both
directions, so every failure leaves the *recoverable* half: a row whose blob is missing 404s and can
be found by joining, where bytes-first leaves a blob **no row names** — unreachable and
uncollectable. **No test asserts either ordering** and that is queued as the mutation whose expected
result is *nothing reddens*. **`V1`'s "immutable once the run closes" is deliberately NOT
implemented**: there is no `CLOSED` status, `run:close` has no implementation, and
`ExecutionService.record` already amends results on a `COMPLETED` run — gating evidence alone would
make the screenshot **stricter than the verdict it documents**. **The contract defect `tsc` found**:
`Evidence.createdBy` was `required` and non-nullable while the server returns null for an
unattributable row — and that null *is* the whole `OWN` arm, so the declaration would have made the
SPA collapse "no author" into "some author"; now `allOf` + `nullable`, §66's shape. **`api.upload` is
a new client seam**: a `FormData` body must not carry a hand-set `Content-Type` or the multipart
boundary is lost, so the first upload would have been a **400 on a request that looks correct in
devtools** — and `spring.servlet.multipart` was **unset**, so Boot's 1 MB default would have refused
an ordinary screenshot (an unset limit is not "no limit", it is the wrong limit). Four mutations run;
the `OWN` one reddens **exactly 2** while `deletesOwn` and the lead's test stay green, which is the
proof they measure authorship rather than the gate. **Adding five frontend tests reddened a file this
slice never touched** — `DocPageEditorPage`'s two `userEvent.type` tests crossed the 20 s budget, so
the suite has a load-dependent cliff; raised to 30 s per §54's precedent and recorded in
`vitest.config.ts` as the **second raise for one cause**. **No migration; `V20` is still free.**
Before that: **§66, the `OWN` qualifier reaches the row** — §65's named next
rung, closed. `case:delete`'s `OWN` grant was serialized and **nothing read it**, so a Test Engineer
saw Delete enabled on a colleague's case and learned whose it was from a 403. **The rung was smaller
than §65 named and the blocker was elsewhere**: only the case detail has a delete affordance at all
(the grid has none, and none of the bulk dialog's eight actions is a delete), but the server compares
`test_case.created_by` and **that column was not on the wire in any form** — `TestCaseSummary`
carries `owner`, which is a *different question*, and the two diverge the moment a case is
reassigned. A slice that had wired the new gate to `owner` would have compiled, rendered, and refused
the wrong rows. Hence `createdBy`, served from the **same `CaseOwners` batch** as `owner` (the id
sets are unioned; a second call would double a two-query resolution to render one column). **The
nullable arm is the whole gate**: `created_by` has no `NOT NULL` and no FK, and the server answers a
null author `false` for `OWN` — *a grant covering "your own work" must not stretch to work nobody can
attribute* — so `isTheAuthor` is written out rather than left to `===`, because **two** absences are
in play (no author, no session) and loose equality would make them equal each other and hand an
unauthenticated render a Delete button on every unauthored case. **Reading the caller through
`useAuth()` broke 73 tests at once**, none about authorship: `useCan` is called by `PermissionGate`
which is called by two dozen components, so requiring `<AuthProvider>` everywhere is a large coupling
increase bought for one arm of one grant — hence `useCurrentUserId`, the same read, **non-throwing**,
whose `undefined` is a real answer (no caller matches no author) rather than a swallowed error.
`PermissionGate` gained `authorId` and two decisions: **`!== undefined` rather than truthiness**
(`null` is a *supplied* author and must reach the arm that refuses it), and it **throws** when
combined with `projectId="company"` — one of the two arguments would have to be ignored, so neither
is. **The finding is this slice's own test file**: `CaseDetailPage.test.tsx` exists because nothing
proved the page *passes* the author, and a call site that drops it falls back to the unqualified
verdict silently — and **the first draft passed all four assertions against exactly that mutation**,
in 44 ms, because `useCan` is fail-closed while pending and `waitFor(…toBeDisabled())` is satisfied
immediately by the loading state. §62's lesson one level in, caught by running the mutation rather
than by reading the test; hence a `permissionsResolved()` barrier. Four mutations run, one
(`createdBy` ← `owner`) reddening **exactly 4** backend tests — possible only because the fixture now
seeds the two columns **disagreeing**, §64's rule. **The mock stopped deferring the `OWN` refusal**,
a gap it had recorded in prose pending exactly these two halves. `npm run build` caught a type error
the test runner did not. **No migration; `V20` is still free.** Before that: **§65, permission qualifiers on the wire** — the rung §61 named
and deferred. `/me/permissions` was **keys-only**, so §31's disable-don't-hide was
**structurally unfollowable** for a qualified grant: a Test Engineer holds `artifact:edit` at
`PLAN_SCOPE`, the project-scope control enabled, and the server refused the submit — a defect
shipped knowingly in §61 with the mitigation written into three javadoc blocks. The response now
carries `[{key, qualifier}]`. **The real content is that two computations became one**:
`evaluate` and `qualifierOf` resolved the *same three terms* with the *same* replace-don't-union
boundary, written out twice, and **nothing failed if one copy was edited** — `qualifierOf`'s own
comment said *"it has to be the same or the two disagree"*, which is a rule maintained by a comment
saying it must be maintained. `PermissionSet` is now `key → qualifier` and `qualifierOf` is a
**delegation**; **four repository queries were deleted**, and none of them had a direct test, which
is why the duplication survived. `DISTINCT` covers **both** columns deliberately — one key can
legitimately return twice, and `DISTINCT ON (key)` would make **an authorization decision by row
order**. Two consequences stated rather than discovered: `strongest`'s throw on `OWN`+`PLAN_SCOPE`
now fires on **any** resolution rather than at one gate (correct — a key whose qualifier is
inexpressible has nothing honest to serialize), and **`NONE` is unrepresentable**, `PermissionSet.of`
throwing rather than holding a key that covers nothing. The wire shape **replaces** the key array
rather than adding a parallel map, so the compiler named every consumer: five, one real and four
test helpers. **The existing tests caught the slice's own defect** — the dialog moves the form off a
scope the caller may not use, and `canOnScope` is fail-closed **while pending**, so the first
implementation silently demoted *every* caller's default during the load; two unrelated pre-existing
tests went red because the form submitted at the wrong scope rather than showing anything. Hence
`Gate.pending` — *"not yet" is not "no"*. Also: five test files mock `/me/permissions` through an
`as never` cast, so the shape change broke them with **zero type errors**, surfacing as
`pointer-events: none` three assertions later; fixed by one shared `heldPermissions` helper.
**Two standing verification queues now exist** (`docs/verification/`) — browser passes and backend
mutation checks are **deferred and batched** at the user's direction, functionality first while
requirements move. The bar moved rather than dropped: a slice must still state the mutation and the
flow, in a file built to be run. **§65 is therefore NOT browser-verified**, and the highest-risk
queued check is not the feature — the response shape changed under **eleven** gated affordances at
once and `useCan` falls **open** on failure, so a missed consumer looks *normal*. The one mutation
run before the policy: hard-coding `ALL` reddens **exactly 8 tests** across both qualifier families
and nothing else. **No migration; `V20` is still free.** Before that: **§64, the shared-step library** — M1.5's other half, and the
last dead hook §61 named. `useSharedSteps` had **zero call sites** and the endpoint had **no
server**: dead on both sides at once, which is why neither noticed. The finding is that it was not
merely dead — **`TestStepInput.sharedStepId` has been accepted and persisted UNCHECKED since M1**,
beside a `suiteId` and a step `id` that are each validated against a set with a paragraph of javadoc
saying why. The identical argument was never made for the column between them, so an id **naming
nothing** answered **500** from the foreign key where a field error belongs, and an id naming a
**sibling project's** entry **satisfied the key and was stored** — after which every read of the case
resolves a library entry through a project the reader holds no grant on. The general shape: *a field
whose validator needs a table that does not exist yet gets no validator, and nothing goes back for
it.* The rule is a **UNION** — live in this project **or** already referenced by this case's stored
steps — and the second half is the subtle one: retiring an entry is a soft delete **because
references survive it**, so the naive check would make **every case using a retired entry
unsaveable**, which is worse than the defect it closes. `CaseEdit.of` passes the stored ids and **no
library at all**, or a bulk `MOVE_TO_SUITE` over such a case would be skipped as "cannot validate its
own content". The other decision is **`usageCount`, which is DERIVED**: the column exists, nothing
maintains it, and `ConditionRepository`'s `ORDER BY usageCount DESC` has therefore **never once
ranked anything**. Denormalizing needs every path that rewrites a step list to recompute it — and a
case edit **replaces the list wholesale** — so it drifts on the first path that forgets. The query
counts instead, over **distinct live cases** (§6e's rule about conflating counts) and the column is
**not mapped at all**. The mutation guard is in the fixture, not the assertion: `usage_count` is
seeded to values that **contradict** the references, because seeded to the derived answer both
implementations pass. **`library:manage` got its first consumer in Java** — §62's `case:approve`
shape again, three slices later. **The live pass found the defect no gate could**: the refusal was
correct (400, not 500) and carried a **second, contradictory message** — *"Add at least one step"* to
an author who had just added one — because `normalizeSteps` **drops** a step it has already
complained about, so the emptiness check read "nothing survived validation" as "nothing was sent". It
**predates the slice** (a blank action and an unknown step id are dropped the same way) and is swept
across all three. Live: a sibling project's real entry refused with one message, the picker reading
**"1 case"** while psql shows `usage_count = 0` on that row, and 0 client errors. **`/reports` is now
the only dead contract left, and it is M2.5's** — a 7–8 week milestone (`ExecutionFact`, `ReportModel`,
headless-Chromium renderers, an async job pipeline), checked and deliberately not started. **No
migration; `V20` is still free.** Before that: **§63, the project's own access screen.** Before that:
**§62, `cases/bulk`** — the last MSW-only surface with a live
consumer, closed. `BulkActionDialog` has called this endpoint since Phase 1 and **no controller had
ever served it**; all eight declared actions are now implemented. The finding is that **`SET_STATUS`
cannot be gated on `case:edit`**: the roles spec grants `case:approve` to Lead and up and explicitly
**not** to Test Engineer, and plan §6g says the state machine has exactly one gate held by
`*:approve` — so the obvious gate, the one every neighbouring write in the module uses, lets a Test
Engineer **approve two hundred cases in one click**, silently, because they hold the permission the
gate names. §61's shape again: a widening, not an outage. **`case:approve` had zero consumers in
Java**, seeded by `V3` and correctly granted since M0 and never once asked. Mutation-verified —
reverting the gate to a constant leaves 35 of 37 green and reddens exactly the two approve tests.
Two consequences: **`lifecycle_status` had no writer at all** before this (`CaseUpdateRequest` omits
it deliberately — *"a status an ordinary edit can set is an approval anyone grants themselves by
saving"*), so this endpoint is the product's whole approval path; and **`APPROVED` is reachable only
from `IN_REVIEW`**, or the gate would guard a door standing beside an open one. The second decision
is the version hash: `MOVE_TO_SUITE` and `SET_AUTOMATION_STATUS` change fields `CaseVersions.hash`
digests, so they **write version rows** — skipping that is silent and surfaces as the *next* person's
save being credited with the bulk change. `CaseEdit.of` reconstructs the edit by **round-tripping
through `CaseEdit.validate`** rather than reading the row's getters, because a second normalizer
would make every hash disagree; pinned by a round-trip test, since the failure is a hash equality no
reading finds. **The live pass found the defect every gate missed**: `PermissionGate` **cannot be used
inside a MUI `Select`** — `Select` clones its *direct* children to inject `role="option"` and
`data-value`, so the wrapped APPROVED item rendered as an inert `role="menuitem"` that was
**impossible to choose**, and a Company Admin holding `case:approve` could not approve anything.
The headline feature was unreachable with build, lint and 452 tests green. The component test passed
**for the wrong reason** — it looked the item up by `data-testid` and asked whether it was disabled,
and an element MUI never processed carries no `aria-disabled` either, so *"not disabled"* and *"not
an option at all"* read identically. Tests now assert through `role="option"` and count options
against `lifecycleLabels`. Also: the MSW mock **dropped ids it could not resolve** (`if (!row)
continue`), so a selection of 44 was accounted for as 42 — `changed + unchanged + skipped == total`
is now an invariant a test pins. `CaseRenderer` extracts the render method `CaseCreateService` and
`CaseUpdateService` each carried a byte-identical copy of, each arguing in javadoc against a third.
§19's fixture-collision defect appeared a **fifth** time. **No migration; `V20` is still free.**
Before that: **§61, the artifact register** — the nav rail has offered
"Artifacts" since Phase 1 and live mode had **nothing behind it**, so the link was dead on the
deployed box; there is a server there now, and a writer with it, because shipping the read alone is
what §54, §55, §56 and §57 each recorded as the mistake. The writer is why this touched the
**authorization core**. The roles spec has said `artifact:edit` is *"plan scope only"* for a Test
Engineer since M0, and V12's qualifier CHECK took `ALL` and `OWN` only — **and Test Engineer's grant
was `ALL`**, V12's backfill default, so this did *not* fail closed: a gate written against the old
data lets a Test Engineer rewrite the strategy every cycle inherits read-only (§6n). Hence **`V19`**,
and hence the interesting part: **`OWN` and `PLAN_SCOPE` do not compare** — one restricts by who
wrote the row, the other by what scope it sits at, so the union a caller holding both would have is
not expressible in one value. `strongest` was `Enum#compareTo` over a declaration order, which after
V19 is *an authorization decision taken by declaration order*; it is now written out and **throws**
on that pair, kept unreachable by a **computed `SeedDataTest` check** that no key carries two
different restrictive qualifiers. Before V19 that held **vacuously** — §50's shape again, a property
true by accident and invisible where it was written. Adding the value made `canDoOnAuthored`'s switch
non-exhaustive under `-Werror`, so the compiler made every consumer decide. **§31's
disable-don't-hide could not be followed** and that is recorded rather than papered over: a Test
Engineer *holds* `artifact:edit`, `/me/permissions` is **keys-only**, so the button enables and the
server answers that one refusal **with a body** naming `scope` — the sentence lands on the selector
the user is one choice from getting right, while a Viewer's stays bodiless. Putting qualifiers on the
wire is the real fix and is its own slice. `documentation` gained `identity` (the register renders
`updatedBy`), which **falsifies its own package comment** — "nothing here renders a person" was true
of the docs space and never of the register beside it; both it and `ModuleStructureTest`'s copy now
record the reversal. `execution` is still not a dependency: `PlanFacts` reads `test_plan` through one
file, the fourth `*Facts` instance. **The live pass proved it end to end** — `V19` applied
**v18 → v19 in place**, a Test Engineer was refused `PROJECT` scope with the message on the control
and accepted at `PLAN` scope, and psql showed her row exists **once**. **No defect on the primary
surface**, the second slice in eight. Before that: **§60, the clock that out-resolved its database** — §58's
carried debt is closed and **no test is `@Disabled` any more**: 1078 tests, 0 failures, **0 skipped**.
`TcmsApplication#clock()` is now `Clock.tick(Clock.systemUTC(), 1000ns)`, because every instant it
stamps is written to a Postgres `timestamptz` that stores microseconds while `Instant` carries
nanoseconds — so a value both *returned in memory* and *read back* was two different values, which is
what made `provision-company` print an expiry that was not the stored one. **The fix is a no-op on
macOS**, where `systemUTC()` already yields only microseconds (re-measured: 20000/20000 samples on a
whole microsecond), so `ClockResolutionTest` asserts what the bean *is* and not only what it does —
the behavioural round-trip assertion **passes on this machine with the defect present**, measured.
The sweep is the other half: `ExecutionService.record` (an `executedAt` both persisted and returned —
the identical defect) and `SessionTokens.issue` were calling `Instant.now()` and bypassing the bean
entirely, so `ArchitectureRulesTest.nothingCallsNowDirectly` now refuses a direct wall-clock read in
production code, **verified by reintroducing the defect and watching it go red**. §48's shape again:
a catalogue-wide invariant needs a computed check, and grep-at-review-time is exactly what missed
these two for the whole life of both modules. Before that: **§59, `issue-invitation`** — written up
this session, because it had shipped with **no handoff section at all** while the index still ended
at §58. Deploying found that an invitation raised from the UI on an instance with **no SMTP relay was
undeliverable by anybody**: the token is stored only as a digest, the response carries no URL, the
unconfigured mailer logs no link, and `provision-company` neither replaces an outstanding invitation
nor addresses anyone but the first admin — five correct decisions composing into a dead end. Hence
`InvitationService.reissue` and an operator CLI verb. **The same slice narrowed CI to
`./gradlew build -x test`** (~327s → ~60s), so **no machine anywhere runs the 1000+ integration tests
now** — which is precisely why §60 spent its effort on a rule rather than a fix. Before that:
**§58, the first real deployment** — the box exists.
Hetzner CX23 at `203.0.113.10` (Helsinki), Ubuntu 24.04.4, serving
`https://tcms.203-0-113-10.sslip.io` behind Caddy on a Let's Encrypt cert, with Postgres 17.10
host-native and the two-role split live: `tcms_owner` and `tcms_app` both `rolsuper=f`,
`rolbypassrls=f`, database owned by the migration role. **The product is now on it** (updated
2026-08-02): `0.1.1` deployed from GHCR, 18 migrations all `installed_by=tcms_owner`, the SPA behind
Caddy, mail relaying, and **every RLS row of the runbook's checklist green on the real box** —
`tcms_app` owns 0 objects, 56 tables FORCE RLS, a context-less `SELECT` returns 0 rows on both a
cold and a warm connection, and `ALTER TABLE … DISABLE ROW LEVEL SECURITY` as `tcms_app` is refused.
The runbook was authored 2026-07-30 and marked *not yet executed*; executing it found
**seven errors in it** (E1–E7, now folded into the procedure as traps A–E), and the shape of two of them is the finding: each presents as a symptom the
document already explains, attributed to the wrong cause. **E2** — `ufw` drops container→host
Postgres on `docker0`, presenting as the connection timeout §6 blames on a wrong bridge subnet (the
bridge was right). **E3** — §9's `log` block cannot start under the packaged Caddy unit's
`ProtectSystem=full` sandbox, and `caddy validate` says *"Valid configuration"*, and
`chown caddy:caddy` makes `sudo -u caddy touch` succeed while the service stays down; the fix is a
`LogsDirectory=` drop-in. **E1 is the one that generalises**: §6 carried a third copy of the
privilege-split SQL under the instruction *"keep the two in step, they are the same contract"* — and
it had already drifted, missing the `GRANT EXECUTE ON FUNCTIONS` that V7's `SECURITY DEFINER`
function needs. V1–V5 would still have come up green, because V7 re-grants itself; the *next* such
function would have failed at runtime, in a component with no test that could see it. The fix was to
**delete** §6's copy rather than correct it. Same family as §48. Before that: **§57, the
`documentation` module** — the biggest MSW-only surface, closed. `GET`/`POST /doc-pages`, `GET`/`PATCH`/`DELETE /doc-pages/{id}`,
`PUT /doc-pages/{id}/status` and **`V18`**, which seeds the `doc_space` that `doc_page.space_id` has
required since `V1` and **nothing had ever created** — the documentation module's `V5`, found by the
first code that needed it. Three sessions in a row (§54, §55, §56) recorded that these reads needed
a **writer** to be honest, and two earlier slices had already pointed here by name: `DisplayKeys`
reserves `DOC_PAGE` for a module that did not exist, and `TestCaseDetailView` omits
`relatedDocuments` *because* it did not. **The slice is one decision.** `V1` says `doc_block.id` is
immutable and never derived from content, because `doc_link` targets `(page_id, block_id)` and
requirements *are* blocks — so "replace the blocks" cannot be delete-and-recreate. That
implementation passes every assertion about text, order and type while **voiding every link on the
page silently**, since the links cascade rather than dangle. `PageContent` decides identity once: a
submitted block carrying an id **is** that block, an unknown id is a 400 rather than a create, and a
removed block something links to is a 409 raised *before* anything is written. The client is
load-bearing here — the server cannot refuse an id that was never *sent* — and the way it breaks is
a framework quirk: `useFieldArray` shadows a data property called `id` with its own render key, so
reading `field.id` submits a fresh uuid per block per save and passes any test that only checks the
text came back. Hence `blockId`, and payload assertions rather than render assertions. **`coverage`
is absent rather than zeroed** (the call `TestCaseDetailView` already made) — and the live pass found
the one place that was not honoured: the page-level chip read **"1 of 1 unlinked" on a page whose
heading demonstrably had a link**. Two more live-only defects: an empty documentation space rendered
a **permanent loading skeleton** (a disabled query is pending, and no mock fixture has an empty
space), and **a delete that SUCCEEDED reported "Not found"** because `removeQueries` on a key with a
mounted observer forces a refetch. Before that: **§56, removing a person** — `DELETE /users/{userId}`,
the membership goes `REMOVED` and nothing is deleted, the session epoch is bumped (the first caller
`AppUser.revokeSessions()` said did not exist), and the defect it found was **the token that outlived
the status that made it unusable** — the exact mirror of §55's dead link. Before that: **§55, inviting a colleague** — `POST /users`, so a company can
finally add a person to itself. Nine sessions named this and §54 sharpened it: the product could show
you who was on a project and let you staff one, and the only way to get a *person* in was an operator
with a psql prompt — §54's own live fixture needed three `INSERT`s. **No migration**; the tokens, the
mailer and the accept flow have been there since §18, and `InvitationService`'s javadoc already named
*"a future invite-a-colleague endpoint"*. So the slice is decisions, not plumbing: the response
**cannot say whether the account already existed** (`app_user` is instance-level with a unique email,
so a differing answer enumerates the instance one guess at a time), a `REMOVED` membership must be
reset or the product sends **a link it then rejects**, and the input carries **no `roleId`** because
inviting and granting are two acts with two gates. The mutation that proves the disclosure rule is
the non-obvious one — seeding the membership from `canonicalName` passes every fresh-account test,
because for a fresh account the two strings are equal, and fails only against a **reused** account.
**The live pass found the defect no gate could**: `emailed: false` has *three* causes and the body
expressed one, so on an install **with** a relay a second click told the administrator their mail was
broken — `issued` is now on the wire and the three-state test exists because the two-state one passed.
Found by clicking Send twice. Before that: **§54, the board's three gaps** — `GET /projects/{projectId}/members`, `Idempotency-Key` on `POST /work-items` with `V17` behind it, and `BLOCKED` as a real fifth column. The members endpoint is not plumbing: the server already validated `assigneeId` against *active member ∧ project grant*, so the picker had to offer **that same set** rather than one that agrees with it — the predicate now lives in one place and a test drives both sides of it. It cannot be a query over `role_assignment`: that finds only direct grants, so it silently omits everybody staffed through a **team** and every **Company Admin**, and answers 200 with a plausible list. `BLOCKED` needed the value, the label *and* the column together, because `satisfies readonly WorkItemStatus[]` rejects a non-status and **accepts a subset** — only one direction was ever checked. The live pass found **no defect**, the first slice in five where it did not; it did prove the whole predicate against a four-person fixture and `V17` migrating an already-at-v16 database in place. Along the way: three mock defects of the "mock agrees with itself" class, §19's fixture-collision defect a **third** time (the prefix *space* is hand-managed and `c0` was taken), and a **pre-existing suite-wide flake** — 1–6 random failures per full run, all reading "Test timed out in 5000ms" rather than an assertion, fixed by raising `testTimeout`; the first attempt raised RTL's `asyncUtilTimeout` to the same value and made it worse. Before that: **§53, the `work` module and the `case_health` writer** — the kanban board runs on a real server, `case_health` has its first writer after six slices of being read and never written, and §52's named next rung — *every URL the SPA calls resolves to a declared operation with that method* — is built and immediately caught the defect this slice opened with: `POST`/`PATCH /work-items` had been called since Phase 1 and **declared nowhere**. The live pass found the one thing no gate could — both derived card types rendered with **no reason on them**, because the server sent `description` and the card never rendered it. Before that: **§52, the suite execution timeline** — and it landed alongside a concurrent strand's **§51, the open-question decision round** — no code, nine of plan §8's outstanding questions settled, **two of which turned out to be answered already by the code and the contract**. ~~The next slice is **M2 execution writes**: recording a result is the last hop still running on MSW.~~ → **superseded in §52**: recording a result has been live since §38, and §52's live pass drove `PATCH /runs/{runId}/executions/{executionId}` eleven times against the real server to build its fixture. ~~The MSW-only surfaces are `documentation`, `reporting` and `work`~~ → **superseded in §53**: `work` went live in that slice, so the MSW-only surfaces are now `documentation` and `reporting` (`/doc-pages`, `/artifacts`, `/reports`, plus `cases/bulk`). → **superseded in §57**: `documentation` went live in that slice, so the MSW-only surfaces are now `reporting` and `artifacts` (`/artifacts`, `/reports`, `cases/bulk`, `shared-steps`). This is §46's "a 'Not done' note decays" a second time, inside a day. → **superseded in §61**: `/artifacts` went live in that slice, so what remains is **`cases/bulk`** (the only one with a live consumer — `BulkActionDialog`, and therefore the obvious next rung) plus **two dead ends** §61's orientation pass identified and did not touch: `/reports` has **no hook at all**, and `useSharedSteps` has **zero call sites** (§35's dead-hook shape again). Both should be **deleted or built** rather than left as contract that reads as implemented. → **superseded in §62**: `cases/bulk` went live in that slice, so **`reporting` is the last MSW-only surface** and it is one of the two dead ends — `/reports` still has no hook, and `useSharedSteps` still has no call site. Every contract path with a live consumer now reaches a real server. Before that: **§50, the query-parameter checks at both ends**. Before that: **§49, the case execution timeline**. Before that: **§48, the contract-hygiene computed checks**. Before that: **§47, the repository dashboard**. Before that: **the third and final parallel run** — §42 · §43 · §44, with §45
recording it **and the decision to stop running lanes**. Work is **sequential**. That was
re-opened on 2026-08-02 — a cap of 4 agents with a safety gate — and **reversed the same day**, not
on §45's cost argument but on a correctness incident: two agents in one tree produced **three silent
losses of committed work** (a rebase, an unrelated commit reverting a doc as collateral, a stale
re-application wiping a later change), with no conflict, no error and a clean `git status`
throughout. The failure mode is *silent*, so the price of getting coordination slightly wrong is not
a conflict to resolve but work that quietly disappears. The parallel plan's **§0** carries the
account; §1–§8 stand as analysis. The M3/M4 fan-outs in §5 remain the only shape that would justify
reopening it, and the bar is now "can a silent loss be **detected**", not "can the files be
de-conflicted".)
**State:** Planning and design complete **including multi-company tenancy**. **Backend M0 is COMPLETE**, and **M0.5 now has a usable login** (§18): invitation → activation → sign-in works end to end, `CompanyContext` finally has a production entry point, and the permission evaluator answers over HTTP. **The instance is reachable for the first time.** Frontend complete through **Phase 3** and aligned with the multi-company decisions (§9), and **the two halves have now met (§20)**: `VITE_API_MODE=live` points the SPA at the real backend through a dev proxy, `/accept-invitation` redeems a real CLI-issued link, and a real session survives a reload. **As of §25 the SPA's biggest screen renders live**: the case grid, its suite tree, its facet chips and its filters all run against the real API. **As of §26 that screen raises no errors at all** — its condition library is served, so nothing on it 404s. **As of §27 the case *detail* screen is live too, including §6f's suite-condition inheritance** — which makes the `repository` module's read surface complete. **As of §28 the product has its first WRITE**: editing a case is live end to end, `test_case_version` has a writer so §6j history actually accumulates, `audit_event` has its first row, and the write gate is a permission (`case:edit`) rather than the read gate every role holds. **As of §29 a case can be CREATED live**: `TC-n` is allocated from the company's `key_sequence` by the one non-racing statement, v1 is written with the case so a created case has no history gap, and the `Idempotency-Key` the SPA has sent since Phase 1 is finally read — a retry returns the case it already made. **As of §30 a provisioned company is finally usable without hand-written SQL**: suites can be created, renamed and deleted from the tree, both halves in one slice, so `useCreateSuite` — dead in the data module since Phase 1 — has its first consumer. **As of §31 the SPA finally asks what the caller may do**: `GET /me/permissions` — in the contract and implemented since the mock era, and never once called — now gates eleven write affordances, so an action the server will refuse renders disabled with a reason instead of inviting a 403. **As of §34 a company can create its own projects** — the blocker named in §31, §32 and §33 is closed, so "provisioned company" no longer means "provisioned company plus hand-written SQL". The gate is `project:create` at **COMPANY scope**, one level above the project, because there is no project grant for a project that does not exist yet. **As of §35 the oldest dead hook has a caller**: `useCaseVersions` was seven slices old with zero call sites, and the case detail now renders its authored history, gap and all. **As of §36 the frontend has a test runner and 203 tests** — Vitest + RTL, mutation-checked rather than watched to pass, and wired into CI at integration. **As of §38 the `execution` module exists at all** — plans, runs, executions and the recorded result are live, and the module crosses into `repository` through exactly one class that reads the *pinned snapshot* rather than the live case, so an execution shows what it ran. **As of §39 a company can staff its own projects**: `POST`/`DELETE /role-assignments` mean a colleague who signs in is no longer guaranteed an empty project list, which was the last blocker of the §34 shape. **As of §40 Phase 5 has finally been run** — the hostile review found four majors, all by driving the app and none by reading it, two of which had survived three previous browser passes. **As of §42 a run can be CREATED, which is what makes the product usable end to end**: `POST /plans` and `POST /runs` land, and creating a run resolves the plan's scope and writes one execution per case **pinned to that case's current version** — the first write of a `case_version_id`, and the thing that materialises the checklist a result is recorded against. It was driven live, the first live pass in three runs, and found two defects no gate could: a successful create navigating into an unimplemented route, and a `NOT_STARTED` run rendering "Still running" because until this slice **no run could reach that state**. **As of §43 `company:read` finally works at company scope** — a `Company Member` baseline granted on joining and backfilled, with the evaluator's scope model left alone. **As of §44 a sibling tab no longer lies about who is signed in**, focus revalidation is on with a written reason, and the case grid keeps its rows while you type. **As of §45 the same permission defect has been found twice in one day and turned into a computed check** (see the box below) — `V16` makes Automation Service reachable at all, which M3 ingestion depends on. **As of §46 plan statistics is live and the `execution` module is read-complete**: the endpoint §42's live pass navigated *into* and 404'd on now renders, so creating a plan lands on a working page. Two thirds of the blocker three sessions had recorded turned out to be already unblocked — coverage never needed a repository-wide denominator (§6p defines it over the plan's own scope) and `bySuite` had its seam since §24. **As of §47 the project's landing page is live and the `repository` module is read-complete**: the dashboard's tiles, breakdowns, coverage and activity chart all run against the real API, and building it found that the by-owner tiles emitted an `ownerId` the case list never bound — a link that silently opened *everyone's* cases under a heading naming one person. `documentation`, `reporting` and `work` writes still run on MSW. **As of §48 the contract is checked by machine rather than by reading** — the debt §42–§47 each recorded and none paid. Four computed checks over `openapi.yaml` found that **24 of 49 operations declared no 4xx at all** (so the generated types said a 401 could not happen on half the API) and **11 schemas declared no `required`**, flipping 29 properties from optional to required once repaired — including `PageMeta` and the case list's own `items`/`facets`. The fourth check is §47's own defect made mechanical, and its mutation left 26 of the other 27 dashboard tests green, which is the proof it was not already covered. **As of §49 the case execution timeline is live, which is the screen the version pin exists for**: the "Execution history" button the case detail has rendered since Phase 1 led to a route that 404'd, and now shows "green, green, *edited to v2*, red, red" — §6p's causation shape, driven live. Building it found **the same defect class §47 shipped and §48 made mechanical, in a place §48's check does not reach**: the SPA has always sent `?scope=CURRENT_VERSION`, the contract never declared it, and Spring silently ignores an unbound parameter — so the "Current version" chip would have answered with every version's results under a heading saying otherwise. It also found that `VersionMarker.author` was typed non-null while the column has no foreign key, and the tightened type failed the build on an **unguarded dereference that would have crashed the page**. §6p's "deleted runs must not orphan history" needed a new required field (`runDeleted`) to be expressible at all. **As of §50 the defect class §47 and §49 each named on the way out is machine-checked at both ends, and building it found the direction neither had described**: the two known cases were *SPA sends a key the contract does not declare*, and the symmetric one — *the contract declares a parameter no handler binds* — is worse, because there the reviewed spec, the generated types and autocomplete all agree with the caller while the server ignores it. Three live instances, one of them on **§49's own endpoint** (`releaseVersion` and `configurationId`, deleted rather than implemented since nothing sends them) and two created by a filter block written on the **path item**, which every operation under it inherits — so `POST /cases` declared the case list's whole fourteen-parameter query. The SPA-side check drives the **TypeScript compiler** rather than reading text, because the case list spreads `...filters` and those fourteen keys are a *type*. **No runtime code changed in this slice**; all three defects were latent in the contract and the generated types.
**As of §52 the suite execution timeline is live and the page finally has a way in** — nothing had linked to `/suites/{id}/history` since Phase 1. The orientation pass found the defect before any code: the SPA declared its own `{ runs: … }` response type at the call site while the contract said `entries`, and the MSW handler was written from the same hand-made shape, so **mock mode agreed with itself and live mode would have rendered nothing**. The lesson is that `api.get<T>` is an *assertion*, not a comparison — only a **generated** type puts the contract on the other side of it — and `apiResponseTypes.test.ts` now refuses an inline literal at all 45 `api.*` call sites. Driving it found two more, both shared rather than local: **every table in the app was clipped instead of scrollable** (`Panel`'s `overflow: hidden`, so all eight of them), and **signing out and in as somebody else in the same tab kept the previous user's data** — §44 built that protection and it ran for every tab except the one the user was looking at, because `BroadcastChannel` does not deliver to its sender.
**As of §55 a provisioned company can add its own people**, which closes the last leg of the staffing loop (§34 projects → §55 people → §39 grants → §54 the picker). `company:members` is defined as "invite, remove, set membership display names"; this is the first third, and **removing a person is still not possible over HTTP** — `CompanyMembership.remove()` and `rename()` have no caller, which is §28's dead-code question with one more instance. → **half superseded in §56**: `remove()` has a caller now (`DELETE /users/{userId}`); `rename()` still does not.
**As of §57 the `documentation` module exists and its reads are honest**, which closes the surface §54, §55 and §56 each recorded as the biggest MSW-only one. A specification page can be written, edited, published, archived and deleted against a real server; `V18` seeds the `doc_space` that `doc_page.space_id` has required since `V1` and nothing had ever created. The module's whole design is that **a block keeps its id through an edit** — links target `(page_id, block_id)`, so delete-and-recreate would void every case's traceability silently — and the refusal that protects it is built *before* anything can create a link, so the invariant precedes the data rather than being retrofitted onto it.
**Build status:** `backend` — `./gradlew build` clean, **1378 tests, 0 failures, 0 skipped against Testcontainers Postgres 17** (§74 added 30, §73 23, §72 23 — 20 written plus 3 the Modulith and contract checks derive, §71 36, §69 25, §68 19, §67 17, §66 4, §65 3, §64 43, §62 45, §61 33), migrating as a **non-superuser owner** (§19). The *skipped* count is worth reading: it was 2 from 2026-08-01 to 2026-08-02 while §58's clock defect was open, and §60 closed it. **Nothing but this local gate runs these tests** — CI has run `build -x test` since §59. `frontend` — `npm run build` clean, `npm run lint` 0 warnings, **`npm run test:run` 584/584** (§74 adds 10 written + **2 generated**, §73 7 written + 4 generated, §72 7 written + **2 generated** — the generated ones being 1 new `api.*` call site × the 2 per-call-site contract checks; §71 8 written + 10 generated; §70 16, all generated). **The generated arithmetic was checked rather than noted, which is §70's lesson applied on its first opportunity — and again in §72, where 1 new call site produced exactly 2 generated cases.** ~~four tests came from somewhere this slice did not count — noted rather than reconciled~~ → **reconciled in §70, and the answer was a defect**: the three contract-hygiene checks emit one `it.each` case per discovered `api.*` call site, so a slice that adds endpoints adds tests it did not write — but §69's *three* new endpoints produced only *four* cases across two checks, because the third was an `api.del` and **both checks were looking for `api.delete`, which has never existed**. Repairing the verb set added the sixteen cases above, covering eight call sites that had never been checked at all. **A test appearing without an author was worth the one grep**, and the general form is now recorded: in a suite with data-driven checks, an unexplained count *change* is a report about the checks themselves. **§67 raised `testTimeout` 20s → 30s, the SECOND raise for one cause**: adding five tests pushed `DocPageEditorPage`'s two `userEvent.type` tests over the budget — a file that slice never touched — so the suite has a **load-dependent cliff** and any slice adding tests anywhere can redden an unrelated file. §54's precedent, recorded in `vitest.config.ts` with the real fix named (those two tests type sentences character-by-character; `paste` would assert the same thing) and left out of scope. **Browser passes are DEFERRED since §65 — see the box above; §65, §66, §67, §68, §69, §71, §72, §73 and §74 are all built and NOT browser-verified.** §71's queue rows lead with the **live-only** ones, because mock mode has shown three teams since Phase 1 and live mode has shown **none, ever**. **Last driven in a real browser in LIVE mode in §62**, which found a defect **only the browser could see** — `PermissionGate` inside a MUI `Select` renders an item MUI never processes, so the APPROVED option was unselectable and a Company Admin could not approve anything, with every gate green. Before that, §61's pass found **no defect on the primary surface** and did prove the qualifier end to end: a Test Engineer refused `PROJECT` scope with the sentence on the "Belongs to" selector and accepted at `PLAN` scope, with psql confirming her row exists once. It also left two things **found and not fixed**, both recorded in §61: the register's plan filter is `useState` rather than the URL, so **a plan's register cannot be linked to** while every other filtered screen can; and ~~`issue-invitation` will re-invite an **ACTIVE** account~~ — **retracted the same session, there is no defect**: every path already refuses it, and the report confused instance-level `app_user.status` with per-company `company_membership.status`, which are routinely different because one person belongs to several companies (§6s). §61 keeps the mistake as the trap it is. (§57's live pass found three defects no gate could see — a permanent loading skeleton on an empty documentation space, a coverage chip claiming "1 of 1 unlinked" about a block that had a link, and a delete that succeeded reporting "Not found" — and proved the slice's central claim in psql: a typo fixed in a linked heading left the block id byte-identical and the `doc_link` intact.) (§56's live pass proved the removal, the revoked session and the cancelled invitation in psql; §55's found the three-state defect by clicking Send twice.) (§54's live pass was the one slice in five that found nothing.) (§53's live pass found the one no gate could see:  both derived card types rendered **without the reason the server was already sending**, because `WorkCard` never rendered `description` while the backend test asserting its contents passed.) (§52's live pass caught two on the sibling screen: every table in the app was clipped rather than scrollable, and a sign-out/sign-in in one tab kept the previous user's data.) (§49's live pass caught the copy defect on the sibling screen: every case timeline ended with "Edited to v1", a change nobody made, on the screen whose whole job is answering "what changed?".) (§47's live pass caught the sibling: MUI turns a unitless `minHeight: 1` into `100%`, so every zero-activity day drew a full-height bar.) **`docker compose up` serves provision → accept-in-the-browser → sign in against a non-superuser owner** (§18, §20), and its aggregate `/actuator/health` now reports **UP** — the `mail`-indicator defect §20 found is **fixed in §21**. CI runs both gates on GitHub Actions.
**Branch:** `master`. Per the git workflow, **work continues on the checked-out branch; do not branch per task** — and since §45 ended the parallel experiment, the concurrency-worktree exception should not be needed. The two worktrees §45 used were merged; prune anything left under `.claude/worktrees/`. Last commit: §74, the plan editor. **No migration in §59 or §60** — both are behaviour and configuration only. **Migrations now run to `V21`** — §71 added it: `uq_team_name` becomes a unique **index** over `(company_id, lower(name))`, replacing `V1`'s exact-match table constraint, which had **never adjudicated a single insert** because nothing could create a team. An application check stricter than its constraint is a check with no backstop. Before it, §69's `V20` added a partial, per-parent, case-insensitive unique index on `classification_value`. **`V22` is the next free number** — neither §73 nor §74 added one: §74's `entry_criteria`, `exit_criteria` and the whole `test_plan_version` table have been in `V1` from the beginning, the table simply gets its first entity and its first row, and putting `@Version` on `lock_version` changes no DDL. Before them, §72 added none either: `test_run.status`, `started_at`, `completed_at` and `updated_by` have all been in `V1` from the beginning, and `updated_by` is merely *mapped* for the first time. Before it, §61's `V19` taught `role_permission.qualifier` the `PLAN_SCOPE` value the roles spec has needed since M0. **Neither §62, §64, §65, §66, §67 nor §68 added a migration** — every column they touch already existed; §68's `case_classification` has been in `V1` since the beginning with three readers and no writer; §67's `evidence` and `attachment` tables the same, waiting for a first reader and writer; §66's `test_case.created_by` has been written since M1 and was simply never served; §64's `shared_step` table has been in `V1` since the beginning, waiting for its first reader and writer. `V19` was verified applying to an **already-migrated** database (v18 → v19, `installed_by=tcms_owner`) in §61's live pass, and `V18` the same way (v17 → v18, re-seeding 8 existing projects) in §57's — the one thing Testcontainers structurally cannot check, because it migrates a fresh container every time.

> **⚠ Browser passes and backend mutation checks are DEFERRED AND BATCHED — §65, standing.**
> Set 2026-08-02 at the user's direction, to deliver the remaining functionality first while
> requirements are still moving, then verify in one concentrated pass. Two queues:
> [`docs/verification/frontend-browser-checks.md`](verification/frontend-browser-checks.md) and
> [`docs/verification/backend-mutation-checks.md`](verification/backend-mutation-checks.md).
>
> **A slice must still state the mutation it would have run and the flow it would have driven** —
> appended to the right queue, in a form that can be executed later. A slice that lands without
> adding its rows has skipped the check outright, which is the thing batching is meant to avoid.
>
> **This reverses a standing rule and the risk is the reversal.** `CLAUDE.md`: *"the gates prove it
> compiles, not that it works."* The live pass has found a defect **no gate could see** in most
> slices since §42 — §62's unselectable option, §57's permanent skeleton, §53's reasonless card,
> §52's clipped tables, §64's contradictory messages. Deferring does not make those less likely; it
> makes them arrive **later and in a batch**. **Until a queue is run, the slices in it are built and
> unverified** — say so, rather than calling them done. §65 is the first, §66 the second,
> §67 the third, §68 the fourth, §69 the fifth, §71 the sixth, §72 the seventh, §73 the eighth,
> §74 the ninth, §75 the tenth; **§76 is the eleventh, and its first row is live-only AND
> unrepeatable** — on a real instance no artifact has a review row at all, including the ones
> already `IN_REVIEW`, and that state disappears the moment anything is submitted. §75's
> **live-only rows lead for §72's reason exactly** — every
> suite on a real instance is a draft and always has been, while the mock's fixtures have shipped
> approved folders since Phase 1, so mock mode will look unchanged by the slice and live mode
> changes completely. **§74's live-only rows lead for a NEW reason** — the mock's fixtures have
> carried hand-written exit criteria since Phase 1 while the columns had no writer at all, so **mock
> mode has been showing a feature the product did not have**. That is §52's "mock agrees with
> itself" from the opposite direction. §72's rows lead because its
> live-only rows lead for the INVERSE of §71's reason — the state it fixes is one **live mode has
> always been in and mock mode has essentially never been in**, because the mock's fixtures ship
> runs that already started. **§70 owes nothing** — it changed only the two
> contract checks and the module they share, which no bundle imports, so its queue row records that
> decision rather than leaving the slice silently absent. Both slices' queue rows name their **live-mode-only**
> checks up front — §67's because mock mode serves `url` as a `blob:` URI, §68's because the mock
> fixture has no *retired* classification value, so the rows covering the reconcile's bound cannot
> be driven in mock mode at all and it will look correct either way.

> **✅ The `company:read` gap is closed — §43** — by the baseline COMPANY-scope grant, a seventh
> built-in role (`Company Member`) that is deliberately **invisible on the wire**. The evaluator's
> scope model is unchanged; the rejected "held anywhere in this company" alternative is recorded in
> the roles spec §A3 with its reasoning.
>
> **⚠ It happened a second time the same day, and that is the lesson — §45.** Automation Service
> held its five §A6 capabilities and no `project:read`, and every project-scoped path asks
> `ProjectAccess.canView` — which *is* `project:read` — **before** asking the permission. So the
> role 404'd on everything and was inert over HTTP; `canView`'s own javadoc justified the method on
> a claim about the six roles that was **false when written**. Repaired by `V16`. **Both defects
> were invisible to every reading of `V3`, because the defect is an absent row.** The rule is now a
> computed check over the catalogue (`SeedDataTest`), and the repair for this family is always
> *grant the missing permission*, never *weaken the gate*.

> **⚠ A file-sync daemon on this machine creates `<name> 2.<ext>` conflict copies, and they BREAK
> THE BUILD — in TWO ways. The second one is a HANG, not an error (§45).** 105 `* 2.xml` copies in
> `backend/build/test-results/` are hashed by Gradle as task inputs and stall `:test` for 20 minutes
> with no output at all; `find build -name "* 2.*" -delete` and the same build took 1m49s. The
> original mode is a compile error: 14 were sitting in the tree at the start of §30 — `ProjectController 2.java` declares
> `public class ProjectController`, which is a hard `compileJava` error, so the backend did not
> compile on arrival. They are gitignored inside `build/` and untracked in `src/`, so **`git status`
> does not always show them and a clean `git status` does not mean a clean build.** They reappeared
> mid-session the moment a script bulk-deleted and recreated a directory. **Check
> `find . -name "* 2*" -not -path "./.git/*"` before trusting a build failure**, and prefer editing
> files in place over `rm -rf` + `cp -R` on a source directory.
>
> **⚠ The pattern above lost its trailing dot in §47, and that is the fix.** The command as
> originally written — `-name "* 2.*"` — **cannot match a conflict-copy DIRECTORY**, because
> directories have no extension. §47 hit the 10-minute hang with that check reporting zero matches,
> while `build/classes 2`, `build/generated 2`, `build/reports 2` and `build/resources 2` sat there;
> `jstack` on the daemon showed it parked in `DirectorySnapshotter`. `rm -rf build` fixed it.
> **Also:** two concurrent `./gradlew` invocations block on the build lock and emit *no output at
> all* while waiting, which is indistinguishable from this hang — check for a second gradle process
> before blaming the sync daemon.

> **✅ The silently-ignored query parameter is machine-checked at both ends — §50.** It had shipped
> twice (§47's dashboard tile opened everyone's cases under a heading naming one person; §49's
> "Current version" chip would have returned every version's results). Both sections ended by
> naming the same missing check and it is now built — **and it found the direction neither had
> thought of.** The two known cases were *SPA sends, contract does not declare*; the symmetric and
> worse case is **contract declares, handler does not bind**, because there every artifact agrees:
> the parameter is in the reviewed spec, generated into `schema.d.ts`, and offered by autocomplete.
> Three live instances, one of them on §49's own endpoint. `contractParameters.test.ts` drives the
> **TypeScript compiler**, not a regex — the case list spreads `...filters`, so its fourteen keys
> are a *type*, and a field added to `CaseFilters` is how the next one arrives.
>
> **A path item must declare no query parameters at all (§50).** They are inherited by every
> operation under it, so a filter block written once for the list also lands on the POST beside it.
> The rule is strict rather than "…that every operation binds" on purpose: the six benign cases are
> benign only because they have one operation each, and that accident is invisible where the
> parameter is written.
>
> **⚠ A "Not done" note decays, and §46 found one that was two-thirds wrong.** The statistics
> endpoint was recorded as blocked on a `reporting` module in the contract, in §38 and again in §45.
> By the time anyone checked, `bySuite` had had its seam since §24 and `coveragePercent` had **never**
> needed the repository-wide denominator it was credited with — §6p defines coverage over the plan's
> own scope. Three sessions inherited the claim without re-testing it. **The orientation pass's first
> question is "which of these has a consumer" (§28); its second is now "is this still blocked".**

> **⚠ Re-derive the next slice from the code, not from the last session's "Next" list — §28, and
> again in §29.** §27's three named next steps were all unsupportable; §28's own list then led with
> `/versions` again, and `useCaseVersions` **still has no consumer**. Twice in a row, the same way —
> so it is a property of writing that list at the end of a slice, not a mistake. The orientation
> pass is not optional, and its first question is *which of these has a consumer*. One grep each.

> **✅ The suite's superuser owner is gone — §19.** `IntegrationTestBase` now creates a **non-superuser
> `tcms_owner`** before Flyway, mirroring `deploy/postgres/initdb/10-tcms-roles.sql`, and `PrivilegeSplitTest`
> pins every property of the split — including that **`FORCE ROW LEVEL SECURITY` covers the owner**, the
> assertion the old harness structurally could not make and the one V7's HIGH defect turned on. The
> container's superuser is now used only for ground truth in assertions.


> **✅ The §10 concerns from the previous handoff are resolved.** The two uncommitted doc files are committed (`d08590b`) and `§6v` exists — the dangling reference is gone. §10 is retained as the record of that strand's decisions, not as an outstanding warning.
>
> **⚠ The DDL is no longer unexecuted — and running it found two defects.** See §11.
>
> **⚠ Every repository interface now needs `@Transactional`, and a test enforces it.** Without it a
> repository's *query methods* run outside any transaction, so `app.current_company` is never set and
> every RLS-protected read returns **zero rows**. Fail-closed, but it silently emptied the permission
> evaluator. See §15.

---

## How this handoff is organised

**Every section is its own file under [`docs/handoff/`](handoff/), numbered by its § number.**
A reference anywhere in this repo — `§6 lesson 5`, `handoff §31`, `see §19` — resolves by the
filename prefix. This file is the index and the current-state summary above; it holds no section
bodies.

**Writing a new section: add a file, do not append here.** `docs/handoff/NN-slug.md`, then one row
in the table below. That is the whole convention, and it exists so two sessions finishing slices
at the same time do not both edit one 276 KB file — which is exactly what §10 records going wrong.
See [`docs/plans/2026-07-31-parallel-execution-rework.md`](plans/2026-07-31-parallel-execution-rework.md).

### Standing sections — read these first

| § | Topic |
|---|---|
| [§0](handoff/00-plugin-skills-are-mandatory.md) | IMPORTANT — Use the installed plugin skills |
| [§1](handoff/01-what-this-project-is.md) | What this project is |
| [§2](handoff/02-artifacts-produced.md) | Artifacts produced |
| [§3](handoff/03-decisions-locked-in.md) | Decisions locked in (do not re-litigate without reason) |
| [§4](handoff/04-frontend-current-state.md) | Frontend — current state |
| [§5](handoff/05-not-done-next-session-starts-here.md) | NOT done — next session starts here |
| [§6](handoff/06-known-risks-and-lessons.md) | Known risks and open items |
| [§7](handoff/07-how-to-run.md) | How to run |
| [§8](handoff/08-working-agreement.md) | Working agreement for this project |
| [§9](handoff/09-frontend-multi-company-alignment.md) | Multi-company alignment of the frontend — DONE 2026-07-30 |
| [§10](handoff/10-the-second-concurrent-work-strand.md) | A SECOND, CONCURRENT WORK STRAND — read before editing the plan |

### The slice log — newest last

| § | Slice |
|---|---|
| [§11](handoff/11-m0-first-build.md) | Backend M0 — first build |
| [§12](handoff/12-m0-v2-and-v3.md) | Backend M0 — V2 and V3 |
| [§13](handoff/13-m05-identity-and-permission-evaluator.md) | Backend M0.5 — the identity module and the permission evaluator |
| [§14](handoff/14-m0-async-context-and-the-reset-guc-defect.md) | Backend M0 — the async-context harness, and the reset-GUC defect |
| [§15](handoff/15-m0-provision-company.md) | Backend M0 — `provision-company`, and the two defects writing it found |
| [§16](handoff/16-m0-archunit-and-modulith.md) | Backend M0 — ArchUnit + Modulith verification |
| [§17](handoff/17-m0-compose-ci-and-the-privilege-split.md) | Backend M0 — Docker Compose, CI, and the deployment privilege split |
| [§18](handoff/18-m05-invitation-activation-and-login.md) | Backend M0.5 — invitation, activation and local login |
| [§19](handoff/19-harness-stopped-migrating-as-superuser.md) | Backend — the test harness stopped migrating as a superuser |
| [§20](handoff/20-frontend-live-api-mode.md) | Frontend — the two halves meet: live API mode, real login, accept-invitation |
| [§21](handoff/21-health-stops-reporting-down.md) | Backend — the default install stops reporting itself DOWN |
| [§22](handoff/22-m1-s1-project-list-and-detail.md) | Backend M1, slice 1 — the project list and detail, and the second gate over HTTP |
| [§23](handoff/23-m1-s2-project-vocabularies-and-the-shared-gate.md) | Backend M1, slice 2 — the two project vocabularies, and the gate that is now shared |
| [§24](handoff/24-m1-s3-the-suite-tree.md) | Backend M1, slice 3 — the suite tree, and the `repository` module's first endpoint |
| [§25](handoff/25-m1-s4-the-case-list.md) | Backend M1, slice 4 — the case list, and the screen that finally renders live |
| [§26](handoff/26-m1-s5-the-condition-library.md) | Backend M1, slice 5 — the condition library, and the last error on the case list |
| [§27](handoff/27-m1-s6-the-case-detail.md) | Backend M1, slice 6 — the case detail, and the SPA's order defect the mock could not show |
| [§28](handoff/28-m1-s7-the-first-write.md) | Backend M1, slice 7 — the first write, and why §27's own next-step list was wrong |
| [§29](handoff/29-m1-s8-case-create-and-the-key-allocator.md) | Backend M1, slice 8 — case create, the key allocator, and v1 |
| [§30](handoff/30-m1-s9-suite-writes.md) | Backend M1, slice 9 — suite writes, and the first slice that shipped both halves |
| [§31](handoff/31-frontend-effective-permissions-disable-dont-hide.md) | Frontend — the caller's effective permissions, and disable-don't-hide everywhere at once |
| [§32](handoff/32-m1-s10-case-delete-and-the-own-qualifier.md) | Backend M1, slice 10 + frontend — case delete, and the `OWN` qualifier it forced |
| [§33](handoff/33-parallel-execution-rework.md) | The parallel-execution rework — the three write-conflict files, the handoff, and a second stack |
| [§34](handoff/34-m1-s11-project-creation.md) | Backend M1, slice 11 + frontend — project creation, and the gate one level above the project |
| [§35](handoff/35-m1-s12-case-versions.md) | Backend M1, slice 12 + frontend — case version history, and the oldest dead hook's first caller |
| [§36](handoff/36-frontend-phase-4-vitest-rtl.md) | Frontend Phase 4 — Vitest + RTL, and 203 tests that can go red |
| [§37](handoff/37-the-first-parallel-run.md) | The first parallel run — three lanes, what merged clean, and what integration had to fix |
| [§38](handoff/38-m2-s1-the-execution-module.md) | Backend M2, slice 1 + frontend — the `execution` module, and the seam it could not cross |
| [§39](handoff/39-m1-s13-role-grants.md) | Backend M1, slice 13 + frontend — role grants, and the `company:read` gap they exposed |
| [§40](handoff/40-frontend-phase-5-review.md) | Frontend Phase 5 — the hostile review, and four defects only driving it found |
| [§41](handoff/41-the-second-parallel-run.md) | The second parallel run — the holder rule that broke before launch, and the contract habit that is now three-for-three |
| [§42](handoff/42-m2-s2-plan-and-run-creation.md) | Backend M2, slice 2 + frontend — plan and run creation, and the two defects only the live pass found |
| [§43](handoff/43-the-company-read-scope-repair.md) | Backend — the `company:read` scope repair, and the seventh role that stays off the wire |
| [§44](handoff/44-frontend-resilience-residuals.md) | Frontend — the resilience residuals, and the sibling tab that lied about who was signed in |
| [§45](handoff/45-the-third-parallel-run-and-the-end-of-the-experiment.md) | The third parallel run — the same permission defect twice, and the decision to stop running lanes |
| [§46](handoff/46-m2-s3-plan-statistics.md) | Backend M2, slice 3 + frontend — plan statistics, and the panel that hid an environment |
| [§47](handoff/47-m1-s14-the-dashboard.md) | Backend M1, slice 14 + frontend — the repository dashboard, and the tile that linked to the wrong list |
| [§48](handoff/48-contract-hygiene-computed-checks.md) | The contract-hygiene computed checks — the debt five sessions recorded and none paid |
| [§49](handoff/49-m1-s15-the-case-timeline.md) | Backend M1, slice 15 + frontend — the case timeline, and the parameter nobody had declared |
| [§50](handoff/50-contract-parameter-binding-checks.md) | The parameter checks at both ends — the rung §47 and §49 each named on the way out |
| [§51](handoff/51-open-question-decisions.md) | The open-question decision round — nine settled, two of them by reading rather than deciding |
| [§52](handoff/52-m1-s16-the-suite-timeline.md) | Backend M1, slice 16 + frontend — the suite timeline, and the field the two sides spelled differently |
| [§53](handoff/53-m1-s17-the-work-module.md) | The `work` module + the `case_health` writer — the board runs on a server, and the check that would have caught it |
| [§54](handoff/54-m1-s18-project-members-and-the-boards-three-gaps.md) | The board's three gaps — project members, `V17` idempotency, the fifth column, and the flake that predated the slice |
| [§55](handoff/55-m1-s19-inviting-a-colleague.md) | Inviting a colleague — the write nine sessions named, and the field the live pass added |
| [§56](handoff/56-m1-s20-removing-a-person.md) | Removing a person — the half §55 named on its way out, and the token that outlived the status |
| [§57](handoff/57-m1-s21-the-documentation-module.md) | The `documentation` module — the biggest MSW-only surface, and the block id that must survive an edit |
| [§58](handoff/58-the-first-real-deployment.md) | The first real deployment — the box exists, and four things the runbook only learned by being run |
| [§59](handoff/59-issue-invitation-and-the-undeliverable-link.md) | `issue-invitation` — the link that was never emailed, the mail that stays off, and the CI lane that stopped running tests |
| [§60](handoff/60-the-clock-that-outresolved-its-database.md) | The clock that out-resolved its database — §58's carried debt closed, two call sites that bypassed the fix, and the rule that watches them |
| [§61](handoff/61-the-artifact-register-and-the-plan-scope-qualifier.md) | The artifact register — the dead link in the nav rail, and the qualifier the roles spec has had since M0 |
| [§62](handoff/62-cases-bulk-and-the-gate-that-depends-on-its-argument.md) | `cases/bulk` — the gate that depends on its argument, and the option that could not be chosen |
| [§63](handoff/63-project-access-and-the-password-managers.md) | The project's own access screen — the exemption a test moved into the helper, and telling the password managers |
| [§64](handoff/64-the-shared-step-library.md) | The shared-step library — the derived count, and the reference persisted unchecked since M1 |
| [§65](handoff/65-permission-qualifiers-on-the-wire.md) | Permission qualifiers on the wire — two computations become one, and the two verification queues |
| [§66](handoff/66-the-own-qualifier-reaches-the-row.md) | The `OWN` qualifier reaches the row — the author on the wire, and the test that passed while pending |
| [§67](handoff/67-evidence-attach-and-delete.md) | Evidence attach and delete — the port, the write ordering, and the invariant deliberately not implemented |
| [§68](handoff/68-classification-assignment.md) | Classification assignment — the reconcile's bound, the leaf rule that never existed, and the required field the detail never served |
| [§69](handoff/69-the-classification-vocabulary-writer.md) | The classification vocabulary editor — the gate the dimension decides, and the label that stopped spelling the name |
| [§70](handoff/70-the-verb-the-contract-checks-could-not-see.md) | The verb the contract checks could not see — `api.delete` never existed, and eight call sites went unchecked behind a guard that counts |
| [§71](handoff/71-team-management.md) | Team management — the permission with no consumer, and the membership that must be a membership |
| [§72](handoff/72-the-run-lifecycle.md) | The run lifecycle — the status nothing ever wrote, and the freeze §67 deferred |
| [§73](handoff/73-the-plan-lifecycle.md) | The plan lifecycle — the ladder nothing could climb, and the gate decided by the edge |
| [§74](handoff/74-the-plan-editor.md) | The plan editor — the gates nothing could author, and the optimistic lock that could not fail |
| [§75](handoff/75-the-suite-lifecycle.md) | The suite lifecycle — the last status nothing ever wrote, and the rescind §73 left on cases |
| [§76](handoff/76-the-review-record.md) | The review record — the tables three ladders were written for, and the tier below approval |
| [§77](handoff/77-per-project-review-settings.md) | Per-project review settings — the row four behaviours were waiting on, and the demotion that must not cost the approve key |
| [§78](handoff/78-open-question-decisions-round-2.md) | Open-question decision round 2 — §8 emptied, five that were answered by the product and never struck, and the chart that now depends on the S3 adapter |
| [§79](handoff/79-review-comments.md) | Review comments — the one comment key where there are three review keys, and the resolution that named nobody |
| [§80](handoff/80-reviewer-assignment.md) | Reviewer assignment — the request nothing recorded, and the table that is not part of the record |
| [§81](handoff/81-the-reviewers-inbox.md) | The reviewer's inbox — the query no module could write, and the mock that invented the feature |
| [§82](handoff/82-the-gating-rules.md) | §6g's gating rules — the edge that is not a target, and the override nobody had to invent |
| [§83](handoff/83-the-cross-project-board.md) | The cross-project board — the parameter that was left off, and the mock that had been showing the wrong project since Phase 1 |
| [§84](handoff/84-the-team-board.md) | The team board — the bound that is the reader's, and the third selector on one operation |
| [§85](handoff/85-the-team-board-screen.md) | The team board's screen — the route that is not administration, and the 404 the server refuses to word |
| [§86](handoff/86-the-fact-log-gets-a-writer.md) | The fact log gets a writer — the module that had to exist first, and the dimensions that must not move |
| [§87](handoff/87-the-report-pipeline.md) | The report pipeline — the job record, the second store, and the gates that could not fail |
| [§88](handoff/88-the-html-renderer-and-the-report-header.md) | The HTML renderer and the report header — the block a renderer cannot strip, and the template engine that was not taken |
| [§89](handoff/89-the-reports-screen.md) | The Reports screen — the download an anchor cannot make, and the job list the server cannot give it |
| [§90](handoff/90-the-on-screen-report-view.md) | The on-screen report view — the model with no generated type, and the permission that reads a report |
| [§91](handoff/91-the-way-back-to-the-picker.md) | The way back to the picker — a screen every session starts on that no screen linked to, and the rail that collapses |
| [§92](handoff/92-saved-report-definitions.md) | Saved report definitions — the row that is one person's, and the delete a foreign key would have refused |
| [§93](handoff/93-the-plan-document.md) | The test plan document — the half a plan is mostly made of, and the link four years of schema had no writer for |
| [§94](handoff/94-writing-steps.md) | Writing steps — the corpus that needs no curating, the index spelling that decides whether it works, and the refine auto-append would have broken **(built and unverified — 11 browser rows, 4 mutation rows, none run)** |
| [§95](handoff/95-conditions-get-a-writer.md) | Conditions get a writer — the library nothing could fill, the edit that propagates, and the usage count that was always zero one slice before it became an impact warning **(built and unverified — 12 browser rows, 9 mutation rows, none run; 3 of them are tests not yet written)** |
| [§96](handoff/96-tags-become-real.md) | Tags become real, and a suite resolves from them — the nullable key that drops rows in four places, the union written twice on purpose, and the tag that must not write a version **(built and unverified — 14 browser rows, 20 mutation rows, none run; one names a test not yet written, two record checks weaker than they look)** |
| [§97](handoff/97-the-plan-detail-rework.md) | The test plan detail becomes one form — the tabs that cannot be headings, the sections that resolve on every read, and the rename that would have moved a case out of its own section **(built and unverified — 32 browser rows written or restated across the M3 lane, plus 3a/3b's mutation rows, none run)** |
| [§98](handoff/98-suites-become-flat.md) | Suites become flat — the count and the grid that have answered different questions since §96, the frozen path that outlived the column it copied, and the agreement test the plan expected to retire **(built and unverified — 12 browser rows, 6 mutation rows plus a `V33` row on the Hetzner list, none run)** |
| [§99](handoff/99-a-run-is-scoped-by-suites-cases-and-tags.md) | A run is scoped by suites, cases and tags — the suite arm that reads right and resolves to nothing, the gate that needed no edit, and the hand-written type that failed exactly as its own comment predicted **(built and unverified — 11 browser rows, 7 mutation rows, none run; two record checks weaker than they look)** |
| [§100](handoff/100-the-design-comp-lands.md) | The design comp lands — the contract that declared a shape nobody served, the mock that evaluated gates the server never evaluates, and the bulk surface that re-asks every per-row rule **(browser pass RUN inline by direction — mock mode only; 3 rows still queued, one of them live-only)** |
| [§101](handoff/101-the-m2-rollups.md) | The M2 rollups — the membership that freezes, the prune its sibling must not have, and the cold cache that read as a plan nobody had started **(built and unverified — 9 browser rows, all structurally live-only, and 11 mutation rows, none run)** |
| [§102](handoff/102-tags-become-reusable.md) | Tags become reusable — the endpoint that had one consumer, the locator that had none, and the free text a suggestion list must not close **(built and unverified — 5 browser rows, none run; no mutation rows)** |
| [§103](handoff/103-the-references-show-their-cases.md) | The references show their cases — a live server-side resolution under each plan group's chips, one read per reference because they union and `?tag=` ANDs, and the `case.suiteId` filter it must never be **(built and unverified — 11 browser rows, none run; no mutation rows)** |
