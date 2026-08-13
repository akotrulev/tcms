# §181 — The last writerless signal, and what "left the project" turned out to mean

**Two phases: `backend/`, then `frontend/`. No migration, no new table, no new column, no contract
change, no `packages/` source.** §180's named strongest candidate, taken as a decision: **`ORPHANED`
has a writer.** It is the tenth and last member of `HealthSignal` to get one, and the first that was
blocked on a sentence rather than on a schema, a module edge or a missing feature.

## What was actually blocking it, and for how long

`case_health` has had ten boolean columns since `V1`. §53 wrote the first two, §120 the automation
pair, §157 stale approval, §158 condition drift, §178 the two documentation signals. Each of those
five slices wrote the same sentence about what was left:

> `ORPHANED` needs a decision about what "left the project" means before it needs any code.

That sentence was **correct, and read four times as an estimate of cost.** The derivation this slice
adds is one class and one statement — a `LEFT JOIN` pair, shorter than `ApprovalFacts`' lateral. What
made it the last one standing was that nobody had answered the question, and a signal nobody can
define is not cheaper to build than one nobody can compute; it is a different kind of blocked, with
the same symptom in a tally. `ArchitectureRulesTest`'s entry for the column said exactly this in its
last clause, and the count above it — *"eight entries"*, *"seven entries"* — is what got read.

## The decision

> A case is orphaned when it **has** an owner and that owner is no longer somebody this company's
> administrator would see in the Members list: the membership row is missing or `REMOVED`, or the
> account behind it is `DEACTIVATED`.

**Membership, not grants.** The literal reading of plan §6o's *"owner deactivated or left the
project"* is the project one: the owner holds no `role_assignment` covering this case's project. It
is refused on this repository's oldest rule and on a straightforward wrongness:

- Answering it means re-deriving project visibility from grants, team grants, company-scope roles and
  SYSTEM-scope assignments — **a second `PermissionEvaluator`, in SQL, inside a scheduled job.** Its
  disagreements with the real one would surface as a chip nobody can explain, on a screen with no
  permission on it.
- It is *wrong* on the two owners it matters most for. **A Company Admin owns cases in projects they
  hold no project-scope grant for**, and so does an Instance Admin, whose access needs no row in this
  company at all. Both would be reported as having left the projects they administer.

Membership answers the same question without asking any of that, because `CompanyMembership`'s header
already made it the answer: *membership is a **precondition** for every permission, so a non-member
resolves to the empty permission set regardless of stale grants.* `MembershipRemovalService` is built
on that — offboarding is one status update and every grant goes inert without being deleted. So
"their grants were revoked" is not really a state this product has, and "they were offboarded" is one
column the derivation can read.

**It is the SQL spelling of the Members screen.** `AdminDirectoryService.listUsers` already answers
"can this person work here", and its javadoc enumerates three cases: `REMOVED` is *absent* from the
directory, `INVITED` reads `INVITED` whatever the account says, and otherwise the account's own status
shows through. The predicate is that list negated — absent, or present as deactivated. A second
definition of "still here" is a chip on the case grid and a row on the Members screen disagreeing
about one person, each internally consistent: **§180's defect exactly**, which lived only in the
transition between two screens.

Two narrowings, both decisions rather than omissions, both with a test:

- **An outstanding invitation is not an orphan.** "Not `ACTIVE`" is the shorter predicate and it
  reports a colleague being onboarded as somebody who left — for their whole first week, healing
  itself the day they arrive.
- **An unowned case is not an orphan.** `owner_id` is nullable and unowned cases are ordinary; a
  signal on each is `UNLINKED`'s volume failure with none of its traceability argument. "Nobody owns
  these" is a *population*, and the dashboard's by-owner breakdown is where a population belongs.

## Three things found by reading, not looked for

**The column's own javadoc had already answered the question, wrongly, in one clause.**
`CaseHealth.orphaned` read: *"No writer (§156) — outdatedSpecChanged's case; the cause is a case in no
suite and no plan."* That is not merely a different definition from plan §6o's and the SPA's — its
first half is a predicate **the schema forbids**: `test_case.suite_id` is `NOT NULL` and has been
since `V1`, so a case in no suite does not exist and never did. It sat at the column for five slices,
each of which said the blocker was a decision, while the file itself quietly held one. §162's finding
at a field: *a sentence naming the wrong mechanism reads as an account of the thing and is the absence
of one.*

**`AppUser.deactivate` has no production caller** — four callers, all tests. The account lifecycle is
a `user:manage` screen nobody has built and §6r's IdP-removed path is M4. The term is in the predicate
anyway: a term left out until its writer lands is a signal that silently stops covering a state on the
day the state becomes reachable, and nothing fails. One `OR` and one fixture now is cheaper than that.

**And the SPA's words named that unreachable half and only it.** `healthLabels.ORPHANED` read *"Owner
deactivated"*; the chip's sentence read *"This case's owner has been deactivated."* — precisely the one
of three terms no running deployment can produce. The state a deployment *does* reach, a contractor
offboarded at the end of an engagement, had no words at all. **This is the third tooltip in
`healthHelp` to be corrected by the slice that gave its signal a derivation**, after §158's
`CONDITION_DRIFT` and §178's `OUTDATED_SPEC_CHANGED`: three for three, and the general form is worth
having — **copy for a signal nothing can raise is copy nobody checks**, and it reads as a perfectly
sensible sentence right up until the day it starts appearing.

## The boundary test's shortage, and the map that moved to fill it

`CaseHealthRecomputeTest.Boundaries.otherSignalsAreNotClobbered` pins that the job's `ON CONFLICT`
clauses name only the columns they compute. It needs a witness: a `case_health` column the job must
leave alone. §157 took its first (`stale_approval`), §178 its second (`unlinked`), and the test wrote
down that `orphaned` was **the last one available** and that the slice deriving it would have to find
something structurally different.

It is `incomplete` — the one `case_health` boolean the server's `HealthSignal` does not name, and now
the only one nothing writes. That makes it a *better* witness than the third option the note reached
for and rejected twice: `failing_repeatedly` belongs to `reporting`, and the objection was that
setting it here makes this test assert over another module's schedule. **A column with no owner has no
schedule to assert over.**

**And that had a consequence the slice did not go looking for.** Reading it means `signalsOf` selects
it, and `everyMappedColumnHasAReader` counts a test `SELECT` as a reader — deliberately, with
§132's `actorTokenId` as the precedent. So the rule reported `CaseHealth.incomplete`, correctly by its
own definition, and the entry left `READERLESS_BY_DECISION`. It is a **weaker reader than that rule
was written for**: the defect it hunts is an *unverified value*, something written on every row that
nothing checks, and this reader verifies no value — it asserts the column's *absence* of one. The
corpus is text and cannot tell. The entry was deleted rather than kept anyway, because an exemption
list that keeps a member after the rule stops reporting it is §162's one-directional ignore list,
which is the thing the map's both-ways shape exists to prevent. Nothing is undocumented by the
deletion: the argument is at the field and in `WRITERLESS_BY_DECISION`, where the column remains and
where being unwritten was always the load-bearing half.

**A member moving from "dead at both ends" to "dead at one end" is a fact neither rule could have
reported alone**, which is the clearest instance so far of why §154's and §156's rules are run as a
pair.

## Changed

**Phase 1 — `backend/`:**

- **`OwnerStanding`** (new) — one statement, the seventh in `recompute()`. Its position is *not*
  load-bearing, unlike §178's: it reads no row this job writes. The membership join carries
  `m.company_id = c.company_id` where every sibling statement relies on RLS alone — **cardinality,
  not isolation**: it is the first statement here joining a table where one person legitimately has
  many rows, so with RLS disabled (which `V1`'s header permits per deployment) an owner in three
  companies contributes three copies of every case they own and `ON CONFLICT DO UPDATE` refuses to
  affect a row twice. Loud, not silent, which is why it is a note rather than the correctness
  argument.
- `CaseHealthRecomputeService` — one field, one call, one log line, and the header's five-slice
  running commentary closed out.
- `CaseHealth`, `HealthSignal`, `DashboardService` — the counts, and the sections about a `false` that
  might mean "nobody looked". **That state lasted from §53 to §181 and applies to no column now**; the
  read path never learned to tell the two zeroes apart, and did not have to, because the job caught up.
- `ArchitectureRulesTest` — `CaseHealth.orphaned` out of `WRITERLESS_BY_DECISION` (seven entries, no
  health signal among them); `CaseHealth.incomplete` out of `READERLESS_BY_DECISION` (eleven).
- `CaseHealthRecomputeTest` — a `The owner check` group of eight, the `Boundaries` witness moved, and
  **the first people this class has ever seeded**: five accounts, four memberships, including one
  contractor who is `REMOVED` in Acme and `ACTIVE` in Umbra. Both people helpers `DO UPDATE` on
  conflict rather than `DO NOTHING`, because nothing deletes them between tests and the self-heal
  test mutates one on purpose — §19's fixture collision arriving through time rather than through an
  id. `companyOf(caseId)` replaces a two-way ternary that would have read the new Umbra case on
  Acme's connection and reported "no signals" as a pass.

**Phase 2 — `frontend/`:** `healthLabels.ORPHANED` → *"Owner has left"*, `healthHelp.ORPHANED` → the
sentence plus the action, and two tests in `HealthChip.test.tsx`. The tooltip is pinned as an
**absence** as well as a presence — "deactivated" must not be in it — because that word is what a later
tidy-up reaches for, being what the enum's name suggests, and re-adding it would satisfy a
`stringContaining` assertion.

## Gates

`./gradlew build` on `shanks` **SUCCESSFUL in 7m13s — 2582 tests / 522 classes / 0 failures** (+8
tests, +1 nested class). Frontend **1268 across 97 files in 175.8 s** (+2, no new file), `npm run
build` and `npm run lint` clean. `packages/` untouched and not re-run: no contract change, so its
`schema.d.ts` copies are byte-identical to before.

Rows `§181 OS-1`…`OS-4` and `OS-M1`…`OS-M6` **recorded and not run** (§65). **Built and unverified.**

`OS-4` is a row that cannot be driven and says so: the `DEACTIVATED` term has no writer in the
product, and the recommendation is to leave it to `CaseHealthRecomputeTest` until the `user:manage`
screen exists rather than drive it from `psql`, which would prove nothing about the product.

## The next slice

§180's list, minus the entry this one took, plus what this one found.

- **`CaseHealth.incomplete`** — now the **only** writerless column on the row and the only
  contract-only member of `HealthSignal`. Its decision is narrower than this one's: plan §6o says
  *"approved but missing steps, expected results, or classification"*, and all three terms are in
  this module. It is also, since this slice, the boundary test's only witness — so deriving it means
  finding a fourth one, and there is no fifth `case_health` column to find. **That is the thing to
  settle first**, and the honest options are a witness outside `case_health` or a different shape of
  assertion entirely;
- **quietening `INFO` on a chip** — §179's recorded reading-plan decision, still not taken, and now
  with a second INFO signal firing (`ORPHANED`) rather than one;
- **`severityRank` in `DashboardPage` is a second copy of the server's ordering** — §180 looked at it
  and left it, deliberately;
- the **read-only-archive** decision §159 did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- **run the browser queue** — ~727 rows, thirteen lanes, one sitting. At the **bottom** at the user's
  direction (2026-08-12).
