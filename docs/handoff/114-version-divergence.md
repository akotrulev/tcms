# §114 — version divergence, the column that is computed rather than claimed, and the RED run this slice does not have

**M2.5's R8, and the last reporting slice in the plan.** §6e's reason for it is one sentence:
*"otherwise you are averaging results for two different tests presented as one."* The aggregate
execution report gains a sixth section listing the cases the selection executed at more than one
version of their own text, and a header-block flag carrying the count.

Backend only. **No migration** (`V35` still head, `V36` still free), policy count still **61**, no
new permission key, no new endpoint, no frontend code. The contract change is **prose** on the
`aggregate_execution` bullet, so `npm run gen:api` moved fourteen lines of JSDoc and no type. The
section became requestable by existing — R3's `ReportComputations` registry and F2's model-driven
view render whatever a computation returns, which is now the third slice in a row to land that way.

---

## 1. It reads the un-collapsed rows, which is R7's finding arriving exactly where R7 said it would

§112 closed by naming this: *"R7's cell — (case, configuration, case version) — is already the unit
it needs: `selectedCte` projects `case_version_id`, and the inconsistency callout's Across versions
row is a first, coarse instance of exactly the divergence R8 has to flag."* That held. The trap held
too, and it is the same one: under `LATEST` — the default — each case resolves to **one row**, one
row pins **one version**, and a divergence section computed over the resolved set reports **no
divergence in any project, for ever**. It would print *"every case was executed at one version"* over
a repository that edits its tests weekly.

So `versionDivergence` reads `selectedCte`, and R7's split is what made that a one-word decision
instead of a rewrite. The section list is now three over `selected` (inconsistency, divergence,
retries) and four over `resolved`.

## 2. The finding: what divergence *costs* is a property of the policy, and it can be computed instead of asserted

The obvious section is a list of divergent cases and a sentence saying the totals blend them. That
sentence is **false under the default policy**, and the four policies do not differ in degree:

- Under `ALL` every version's results reach the totals, so the headline is §6e's sentence literally.
- Under `LATEST` one version survives per case: the totals blend nothing, and the older version's
  results are in **no** figure in the report. That is not harmless, it is a different fact.
- Under `WORST` / `BEST` the surviving row is chosen by severity **across** versions, so a failure
  recorded against text that has since been rewritten can outrank a pass against the current text.
- Under `LATEST_PER_CONFIG` both happen at once — one version per configuration, and two
  configurations that pinned different versions blend after all.

Writing that as a `switch` on the policy would be a **second implementation of the policy, in
English, in the one place a reader has no way to check it** — decision D's E1 argument in the shape
it takes when nobody notices, because prose does not compile.

So each row carries a **computed** last column — `Counted` / `Not counted`, an `EXISTS` against the
resolved set — and the narrative's closing sentence is chosen by whether any case has *more than one*
counted version, which is what "the totals blend versions" actually consists of. `versionDivergence`
is consequently the only query in the class that reads **both** CTEs, and that is the point of it:
what diverged comes from `selected`, what the totals did with it comes from `resolved`.

`blendsVersions` therefore is **not** `policy == ALL`, and `PER_CONFIG` is the fixture row that
tells the two apart. Mutation R8-4 is that check, and its expected blast radius is a single
assertion — which is the correct outcome, not a weak one.

## 3. Divergence is wider than the callout, and the predicate says nothing about outcomes

The inconsistency callout lists a case only when it both passed **and** failed, so its *Across
versions* rows are a **strict subset** of these. A case that failed at v1 and failed again at v2
disagrees with nothing and is still two different tests averaged into one figure — §6e's harm is the
blending, not the disagreement. The predicate is `count(DISTINCT case_version_id) > 1` and nothing
else, and `STILL_FAILING` is the fixture row that discriminates: written over the callout's rows
instead, it is absent and the section is a duplicate of the section above it.

## 4. The version number is a label on a dimension, not a dimension — so `execution_fact` grows no column

`execution_fact.case_version_id` is **THE PIN** in `V1`'s own words, and it is a UUID. *"Executed at
6f2c… and at a1b9…"* tells a reader that two versions exist and nothing about which came first,
which is the only fact that makes the divergence readable. `ExecutionSource.caseVersionNumbers` is
the answer — in the seam beside `caseLabels` and `findRun`, for the reason that file's header gives,
and it takes the seam's table count from six to seven.

The tempting alternative is a `case_version_no` column on the fact log, and R7's argument against a
`case_key` column does **not** transfer cleanly: a version number is immutable, so freezing it would
be honest. The reason it is still refused is the other half — **a star schema duplicates the
dimensions it aggregates *by*, and nothing groups by version number.** The id is the dimension; the
number is a label on it. Same `project_id` bound and same absence of a `deleted_at` predicate as its
two siblings, for the same two reasons, and **nothing tests either** (mutation R8-7): this is now the
fifth file layering that predicate with no test behind any of them, and the row that closes it is one
two-project fixture written for the seam rather than per method.

## 5. What this slice does not have, said plainly: there was no RED run

§112 recorded its RED run — twelve of fifteen tests failing before the sections existed — and named
the three that passed as carrying no evidence. **This slice has none of that.** The tests were
written after the implementation and every one of the thirteen passed on its first execution.

What stands in for it is structural: `theFixtureHolds` asserts five executions under `LATEST` over
five cases, so the resolved set holds exactly one row per case, so the divergence predicate over it
is unsatisfiable and the section would be empty. That argument is sound, and it is an argument rather
than a measurement. **R8-1 in the mutation queue is the only thing that can turn it into one**, and
it carries more weight here than R7-1 did in its own slice. Recorded rather than glossed, because a
suite whose tests have never been observed failing is exactly the state that looks identical to a
suite that works.

## 6. Gates

- Backend: `./gradlew build` — **BUILD SUCCESSFUL in 4m33s, 1852 tests across 377 result classes, 0
  failures, 0 skipped** (1839/374 → 1852/377: the 13 of `AggregateVersionDivergenceTest`, whose two
  nested classes are two further result files).
- Frontend: `npm run build` clean, `npm run lint` 0 warnings, `npm run test:run` **958/958** —
  unchanged, as expected: the only frontend file touched is generated JSDoc. The two suites were run
  **sequentially**, per §112's caveat about CPU contention.

## 7. Verification

**Zero rows run**, per §65. Four browser rows queued — `M6-30`…`M6-33`: the section listing a real
re-versioned case under the default policy with one row counted and one not, the closing sentence
following the policy from `LATEST` to `ALL`, the header flag reading **0** rather than vanishing on a
project that has edited nothing, and the six-column table checked for clipping beside R7's
seven-column one plus screen-vs-export (decision F). **`M6-30` needs data no lane creates** — a case
edited *between* two runs — and says so rather than assuming the `A3` charter already produces it.

**Seven mutation rows queued, none run**, as §18 of `backend-mutation-checks.md`. R8-1 is the one
that matters and, given §5 above, is the slice's only route to evidence that the section reads what
it claims to. R8-5's second half may come back green by luck — the fixture's version ids are
`gen_random_uuid()` — and the queue says that a green there is an unrepeatable test rather than a
surviving mutant.

**Built and unverified** in the browser.

## 8. Where this leaves M2.5

**The reporting lane's plan is complete**: R1–R8 and F1–F2 are all shipped. What the plan explicitly
does *not* cover is unchanged and still listed in its §8 — PDF rendering, S3/MinIO, scheduled
reports, notifications, doc-structured reports and the coverage matrix, the evidence bundle, release
comparison and trend, `CSV`/`XLSX`, and cross-project reporting (its open question 4). None of those
became closer by R8 landing; the next slice comes from re-deriving against the code, not from this
plan's tail (§28, §29, §46).
