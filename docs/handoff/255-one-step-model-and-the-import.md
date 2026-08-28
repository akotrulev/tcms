# §255 — one step model, and the `.feature` files that could finally come in

**The first `NNN-` plan pack in this repository, executed end to end: `docs/plans/001-unified-step-model/`
— blueprint, data model, API surface, integrations, validation catalog, a delivery plan of eight tasks
across four streams, and five `-explained.md` companions.** T1–T7 wrote the code; this section is T8.
Status, evidence and the twenty rows of decisions and deviations (D0–D19) live in that plan's §2, §3,
§6 and §7 and are not restated here — what follows is what the initiative *was*, what it corrected, and
what it did not finish.

✅ **GATED AND GREEN on `shanks`, 2026-08-28 — backend `3408 / 0 / 0`, frontend `1833 / 1833` across
132 files, packages `393` (138 client / 153 mcp / 102 cli), lint `0` warnings on both, `pack:release`
verified.** It did not start that way: the first pass could not compile on two of the three stacks and
the backend suite failed 31 arms. **Nine defects, and three of them were in shipped code.** See
**Gates** at the bottom for every one of them.

## The rule to carry

**A toggle between two representations of one thing may not delete the thing.**

Before this initiative, `stepFormat` was a switch between two *storages*. A `STRUCTURED` case kept its
procedure in `test_step` rows; a `GHERKIN` case kept it in `test_case.gherkin_source`, one text column.
So the editor's format toggle was not a view change — it was a migration, and it ran on save.
`toCasePayload` sent `steps: []` for a Gherkin case and `CaseEdit.validate` wrote `List.of()`, which
means switching an executed case to Gherkin and pressing Save **deleted every step row and with them
every id a `step_result` anchors to** — and answered 200 with a success toast over it. Nothing on
screen reported the loss, because from the toggle's point of view nothing had gone wrong: it had done
exactly what a format switch means when the format owns the storage.

The whole of the fix is that neither direction of the toggle now touches the list. `STRUCTURED →
GHERKIN` only **fills** keywords (`prefilledKeywords`, idempotent, never overwrites); `GHERKIN →
STRUCTURED` does nothing at all; `toCasePayload` sends the rows in both. There is no diff dialog in
either direction, and there is nothing for one to diff — which is the sentence that supersedes plan
§6a rule 1 (struck in place, at that line).

The generalization is the part worth keeping. **A representation switch is safe exactly when both
representations read the same rows, and a "format" that owns storage can never be one.** `TestStep`'s
own header calls the stable step id the most expensive property in the class; the blob model made
destroying it the default, and the row model made it impossible — which is also the argument against
the alternative the proposal itself refused (parse the blob on read into ephemeral steps: no stable
id, so a reworded `Then` silently reattributes every past screenshot).

**The second rule, and it is real too: a preview and the act it previews must be one rendering.**
`POST …/feature-imports` plans and applies through one method — `PREVIEW` returns the plan, `APPLY`
recomputes the identical plan *inside the transaction* and executes it — and `ImportPlanView` branches
on `mode` nowhere, because a second rendering keyed on the mode is how a preview starts describing
something the apply does not do. Beside it: **`REFUSE` is a row and never a status code**, which is
what makes Apply's enablement `totals.create + totals.update > 0` rather than a permission check. That
one expression is the only thing that makes both halves true at once — a `case:read`-only caller sees
the whole plan with Apply off, and forty importable scenarios import beside any number of refusals.

## What was actually broken

Plan §6a designed two first-class formats over **one** `test_step` table, and `V1` built that table
for both — `keyword` nullable with *"non-null identifies a Gherkin step"*, plus `docstring` and
`datatable` for the block arguments. None of it was ever written to. What shipped was a textarea:

- `test_step.keyword` had never held a value; neither had `docstring` or `datatable` (`V69`'s own
  header says so, which is why its `CHECK` constraint is trivially true of every stored row).
- There was no `cucumber`/`gherkin` dependency at all. The only "parser" in the product was a
  `/^(Given|When|Then…)/` regex in `CaseDetailPage.tsx`, for syntax colouring.
- A Gherkin case had zero step rows, therefore zero `step_result` anchors — no per-step verdict, no
  per-step evidence, no per-step comment. `TestExecutionView` documented that as intentional:
  *"Empty is a real state: a Gherkin case has no structured steps and the screen says so."*
- `CucumberReportParser` read per-step results out of a report and threw them away, because there was
  nothing to attach them to.
- `CaseCompleteness` could not find a `Then` without parsing Gherkin in SQL, so it took any non-empty
  blob as "says what should happen".

So a Gherkin case was a string with a chip on it: none of stable step ids, per-step results, evidence
anchoring, step suggestions, completeness or version snapshots applied to it. That is a **storage**
decision, not an editor one, and no amount of reskinning the textarea would have reached it.

## What the seven tasks did

- **T1 — the step model.** The contract, `V69`, the lens rules and the snapshot chain. `TestStep`
  gains `docString`/`dataTable` (`@JdbcTypeCode(SqlTypes.JSON)` on `List<List<String>>` — *not* an
  `AttributeConverter`, **D1**: a converter binds `varchar`, and Postgres will not implicitly cast
  that to `jsonb`), `test_case` gains `imported_source`/`imported_line`, `condition` gains
  `imported_key` and its partial unique index. `TestCaseInput` loses `gherkinSource`.
- **T2 — the carriers.** `STEP_FIELDS` in `@tcms/client`'s shape catalogue becomes the whole step
  shape for both lenses, `keyword` becomes `enumOf: 'StepKeyword'`, and the §254 pin learns to walk
  nested fields. No hand-written second copy: the CLI and the MCP server still render from the one
  catalogue.
- **T3 — the parser and its inverse.** `GherkinScenarios` (pure, static) over `io.cucumber:gherkin`
  **42.0.1** — fifteen majors past the version the blueprint inferred, and the version where a syntax
  error is a `ParseError` *envelope* rather than a package-private exception nobody can catch, which
  is what makes "a parser failure is never a 500" structural (**D5**). Two endpoints on
  `CaseController`: `POST …/parse-gherkin` and `GET …/{caseId}/feature`. Round trip is identity.
- **T4 — one editor, one renderer.** The rule above, plus `StepDataCell` (one control for
  `testData | docString | dataTable`, mode **derived** from the values and never stored),
  `PasteGherkinDialog`, `StepsPanel` as the single renderer with `GherkinView` demoted to a zero-row
  *"Not yet migrated"* fallback, and `api.postText`/`api.getText` — the client's first text-media
  verbs, both still through `request` so the timeout budget, the bearer token and the dev fault seam
  still apply.
- **T5 — `migrate-gherkin-source`.** The backfill, reconstructing each case's save through
  `CaseUpdateRequest.overlay` → `CaseEdit.validate` → `CaseSteps.reconcile` → `CaseVersions`, one
  transaction per case, minting a version authored by `--actor`. `gherkin_source` is deliberately
  **not** cleared: it is the proof the migration was faithful. The command lives in `com.tcms`, the
  composition root — **not** in `com.tcms.provisioning` (**D11**, answering OQ1 with an option the
  question did not have; two architecture rules refuse the edge the plan assumed, and nobody should
  tidy it back).
- **T6 — the import, server side.** `POST …/feature-imports`: suite per feature, **one library `PRE`
  condition per `Background:` step** (AM1), case per scenario keyed for Cucumber.
  `com.tcms.api.AutomationKeys` is the one spelling of `"<Feature>#<Scenario>"` in the product —
  in `api`, not in a `shared` module this repo has now refused by name three times (**D12**).
  Nothing is assembled here: every write goes through `CaseCreateService`/`CaseUpdateService`/
  `SuiteWriteService`/`ConditionService`, so an imported case is validated, versioned, audited and
  health-recomputed exactly as an authored one. Import never deletes; orphans are listed.
- **T7 — the import page.** Drop `.feature` files, read the plan, apply it. `Table` and not
  `DataGrid` (**D16** — §241 measured that DataGrid does not render in jsdom, and every criterion
  here is an assertion about what a row *says*). The caps refuse on `File.size` before a byte is
  read; the 500-scenario one is a **heuristic** and its sentence hedges on purpose (**D17**),
  because tightening it means parsing and the SPA does not parse.

## The two premises the proposal got wrong

The proposal (`docs/plans/2026-08-27-unified-step-model.md`, now marked executed) was right about the
diagnosis and about the shape of the fix. Two of its mechanics were wrong, and both were corrected
during the pack rather than during the code:

1. **Inline conditions.** It mapped `Background:` to *"inline `PRE` conditions on every scenario of
   the feature — inline, not a library `condition`: no `library:manage` needed and no library entry
   minted per file."* That reads a table this product does not have any more: `case_condition.inline_body`
   is dead DDL and conditions have been **reference-only since §95**. So there is no inline field to
   write, and the import would have had nothing to attach. Amendment **AM1** replaced it with a
   library `PRE` condition **per Background step** — text-hash `imported_key`, deduplicated
   project-wide — which is why `Given I am signed in as a customer` across three files is *one*
   condition row and why the import needs `library:manage` after all. The permission the proposal
   said it was avoiding is the permission the corrected design requires, and `api-surface.md` §5.4's
   per-row `needs library:manage for the Background` refusal exists because of it.
2. **The pinned snapshot.** It said *"Execution: nothing structural — `stepResults[]` already anchors
   on `stepId`"*, with version snapshots listed as a thing to *confirm*. Execution does not read
   `test_step` at all: `ExecutionViews.steps` renders `CaseFacts.PinnedStep` out of the version
   snapshot JSON. Writing rows is therefore not enough — a migrated case executes per step only from
   its **next** version onwards, which is exactly why T5 mints a version rather than only writing
   rows, and why `--actor` must be able to *see* every project being migrated (a snapshot rendered
   without the grant records every classified case as unclassified).

## The three stated holes

Named here rather than left in a deviation table, because each is a thing a reader could otherwise
mistake for coverage:

- **`§255 FI-M11` — the likeliest real defect in the initiative.** `CaseUpdateRequest.overlay` takes
  the four contract-required fields **from the body as sent, not from the stored row**, so any update
  path that spells `Priority.MEDIUM` into `sent` silently resets the case's priority. `FeatureImportService`
  passes `existing.getPriority()` for exactly this reason — but **no fixture case has a priority other
  than `MEDIUM`**, so the mutation that breaks it predicts no red. It is a registered gap, not a
  covered case. The same trap is one line away for `severity` (`FI-M12`).
- **`§255 US-4` — a fixture no verification lane can author.** It needs a `GHERKIN` case with a
  `gherkin_source` blob and **zero step rows**: a case that predates T1. Nothing in the product can
  create one any more, and T5 is designed to remove the population. Driving it therefore has to happen
  on an instance *before* the backfill is run on it; the row says so.
- **`§255 FI-3` — a persona no seeded role provides (D15).** It needs an account holding
  `case:create`/`case:edit` and **not** `library:manage`, and the roles spec §A3 grants
  `suite:manage · case:edit · library:manage` on one row to every role that has any of them. The
  backend arm seeds a custom PROJECT-scope role by raw JDBC to get it; the browser row can only be
  driven if the sitting can grant one.

One more worth reading beside them: **nothing in the backend suite drives an ingestion against
imported cases** (**D14**). What is checked is that the two spellings of `automationKey` agree, from
both modules, over one shared fixture — `§255 FI-M1` and `FI-M2` are those rows. The end-to-end
`unmatched_count = 0` is browser row `§255 FI-1`, and it has not been run. Do not read the key
assertions as "the integration is proven".

## What is deliberately unfinished — initiative `002-`

`test_case.gherkin_source` **is still writerless by decision**, pinned in
`ArchitectureRulesTest.WRITERLESS_BY_DECISION` with `002-` named as its deleter. It is still read by
three things: the SPA's not-yet-migrated fallback (`TestCaseDetailView` serves it only for a zero-row
case), `CaseUpdateService.hasProcedure`, and `V2`'s `search_vector` **generated** column — which is
why dropping it is a migration and not a delete.

**The `002-` gate is operational, not technical: `migrate-gherkin-source` must have been run on every
instance, with a clean report.** Exit **3** is the signal — the run finished and some cases are still
on the blob. `V70` is reserved for it; head is `V69`. When it opens, `002-` drops `gherkin_source`
(re-creating `search_vector` without it), `test_step.shared_step_id` and `case_condition.inline_body`,
removes `TestCase.gherkinSource` from the contract, and retires the SPA fallback. Opening it is the
human's, after the backfill has run everywhere.

Also deliberately out: **Scenario Outline / Examples** stay unbuilt — the parser refuses them with a
message rather than flattening them. **Cucumber per-step result attachment** is a follow-up; ingestion
is still case-level. Git sync modes are still M4 and still one-directional per §6a — but the manual
import is now the primitive they will call, which is the other half of the §6a strike-through below.

## What §6a now says differently

Two sentences in `docs/plans/2026-07-30-tcms-high-level-plan.md` §6a are struck in place rather than
deleted, per the working agreement, so the next reader can see the reasoning was considered:

- **Rule 1's "convert format … shows a diff of what will be lost."** Superseded: `stepFormat` is a
  lens, both lenses are the same rows, and there is nothing to diff. The *rest* of rule 1 — never
  convert silently, never on save, no background auto-converter — is untouched and is the reason the
  toggle is now inert in both directions.
- **"`.feature` export/import is M4 scope, and only if a project picks a non-decoupled sync mode."**
  Superseded: both halves landed here, unconditionally and with no sync mode involved. Import is a
  manual, previewed, non-deleting act; export is `GET …/{caseId}/feature` on any case, structured
  ones included (they render with `*` steps).

## Gates

**Run on `shanks` 2026-08-28, and green on all three stacks:**

| Stack | Result |
|---|---|
| `backend` | `./gradlew build` — **3408 tests / 0 failed / 0 skipped** |
| `frontend` | `npm run build` clean · `npm run lint` **0 warnings, 0 errors** on 367 files · `npm run test:run` **1833 / 1833** across **132** files |
| `packages` | `npm run build` clean · lint **0/0** on all three · `npm run test:run` **393** (138 client · 153 mcp · 102 cli) · `pack:release` **verified both `npm i` and `npm i -g` for both tarballs** |

**It did not start there, and the gap is the point of this section.** Seven tasks were written under
the standing no-gates directive, and the first pass **failed to compile on two of the three stacks**
and then failed **31 backend arms**. Nine defects, in the order they surfaced:

| # | Where | What | Whose |
|---|---|---|---|
| 1 | `packages/mcp/src/gherkinLensCase.test.ts:182` | `as` on its own line. TypeScript's assertion operator has a **no-LineTerminator-before** restriction, so `expr ⏎ as T` is `TS1434`, not a style choice. | test |
| 2 | `CaseEditTest`, `FeatureImportTest` | `-Werror` + `[varargs]`. **`@SafeVarargs` promises the array does not ESCAPE**, so javac warns at any call that takes it — `List.of(steps)`, `Arrays.asList`, `Arrays.stream` and a private `asRows(steps)` alike. Proven with a three-method scratch file rather than guessed at, after the first fix moved the warning instead of removing it. Copying the array in a loop, in place, is the only form that compiles. `new Map[n]` was a second, plainer `[rawtypes]`. | test |
| 3 | `FeatureImportPage.tsx:19` | `@mui/icons-material/DeleteOutline` **does not exist**; the icon is `DeleteOutlineOutlined`, which is what the other nine imports of it in this SPA already say. | code |
| 4 | `CaseEditorPage.tsx` | **A step-suggestion pick wrote three sibling paths** (`steps.N.action/testData/expectedResult`) while the data cell's `Controller` subscribes to `steps.N`, so the pick never reached the box. And the deeper half: the corpus triple is a STRUCTURED step, so a pick onto a row holding a doc string or a table left **both** set — a row `V69`'s `num_nonnulls(test_data, docstring, datatable) <= 1` refuses. Now one write to the object plus one to `expectedResult`, which is an uncontrolled `register` input and needs its own exact path. | **shipped** |
| 5 | `CaseGherkinEndpointTest` | Fixture prefix **`e1` and project key `E1OLB` were already `ExternalLinkEndpointTest`'s** — same company UUID, same key, `uq_project_key`. Thirteen arms. Moved to `e3`/`E3OLB`. §19's collision defect, exactly as `docs/specs/parallel-work-rules.md` describes it. | test |
| 6 | `FeatureImportTest` | **D15's fixture role was seeded and never dropped.** `SeedDataTest` asserts instance-wide that `role WHERE NOT built_in` is empty; the leak reddened it in the full suite only, at a position that moves. `ReportEndpointTest` — the class D15 cites as its shape — documents that trap at length and has an `@AfterAll` for it; T6 copied the seeding half and not the teardown. | test |
| 7 | `FeatureImportTest` | `resetMutableRows` puts **every** key counter back to 1 while the tree fixture spells out `TS-1…TS-5`, so the first suite the import created asked for `TS-1` and the whole APPLY died on `uq_suite_key` — **fourteen arms**. The cases dodge it by living at `TC-900/901`; the suites could not. `SuiteWriteEndpointTest` moves the same counter for the same reason. | test |
| 8 | `FeatureImportController.raced` | The 409 handler treated **any** `DataIntegrityViolationException` as one of its two races and defaulted to *"another writer took one of these automation keys"* — so the `uq_suite_key` bug above was reported to the caller as a retryable race, with the stack that said why thrown away. **This is what hid #7 for a whole run.** It now matches the two index names and **rethrows** anything else. | **shipped** |
| 9 | `FeatureImportService` | **A `Background:` change could never detach a condition.** `imported_source` is cut from the SCENARIO's own lines (the AST has no verbatim form) and a `Background:` sits above the first scenario — so editing one leaves every scenario's text byte-identical, R6's short-circuit fires, and the case reports *"unchanged since import"* while carrying a precondition the file no longer states. R6 now compares the attached import-owned keys as well as the text. | **shipped** |

Two arms were **wrong about the product rather than about the fixture**, and both were rewritten to
what the spec says:

- **`aLocalEditIsOverwritten` asserted `UPDATE`; the answer is `SKIP`.** `api-surface.md` §5.4 is
  explicit — an unchanged file is `SKIP "unchanged since import"`, nothing written, no version — and
  the arm's own comment argued for `SKIP` one line above an assertion demanding the opposite. The
  consequence is worth stating because a reader will expect the reverse: **the import is not a
  continuous mirror.** A human's rename stands until the *scenario's own text* changes, and then it
  is overwritten wholesale. The alternative — comparing against the case — re-writes and re-versions
  every locally-edited case on every nightly re-import of a folder nobody touched. The arm is now two:
  the survival, and `aLocalEditIsOverwrittenWhenTheFileMoves` for the other side of it.
- **`theSnapshotCarriesTheSteps` matched `"keyword":"GIVEN"` against `snapshot::text`** — a `jsonb`
  column, rendered by PostgreSQL as `"keyword": "GIVEN"`, with the space. The assertion could not
  have passed whatever the backfill wrote. It parses the JSON now.
- **`de-duplicates identical refusals`** used three 2 MiB files and asked for the 8 MiB total refusal:
  6 MiB never reaches it. Five files, and the per-file count asserted too.

**The one carve-out from the ungated period, stated at the time (D8):** T3's parser and renderer were
compiled and driven in a **scratchpad copy** against the real `gherkin` jar. It found two defects
inspection had not, both now queue rows (`§255 GP-M4`, `GP-M5`). Everything else in T1–T7 reached this
pass having never executed — and #4, #8 and #9 are what that cost.

## The queue — 116 mutation rows RUN, 7 browser rows still owed

**Every mutation row this initiative wrote was executed on `shanks` on 2026-08-28**, against the
green baseline above: the mutation applied to the working tree, the named classes run, the result
recorded in the row's own last column, the mutation reverted, and the tree re-gated green at the end
(backend `3408 / 0`, packages `393`). The seven **browser** rows are unchanged — they need a running
instance and a human, and they remain owed.

| Queue | Rows | Verdicts |
|---|---|---|
| `backend-mutation-checks.md` | 108 | 54 confirmed as written · 23 count-corrected · 15 gaps that hold · **4 refuted** · 1 corrected · 1 not reachable · 1 not expressible |
| `packages-and-frontend-mutation-checks.md` | 8 | 4 confirmed · 2 broader than written · 1 gap holds · 1 not a mutation (`PK-M8`, needs a live instance) |
| `frontend-browser-checks.md` | 7 | **still owed** — `§255 US-1…US-4`, `FI-1…FI-3` |

**Ninety-nine of the 116 behaved as written.** That is the useful headline: the rows were authored
by agents that had never run a line of the code they described, and the great majority of them named
a real property and the arm that holds it. The value is in the seventeen that did not.

**Four rows are REFUTED — each names a guard that does not exist:**

- **`BF-M1` — nothing anywhere drives `MigrateGherkinSourceCommand.run`.** The row calls it the
  loudest arm in the class. `MigrateGherkinSourceTest`'s own helper opens the `CompanyContext` and
  calls `backfill.run` directly, and `run` ends in `System.exit`, which no in-process test survives.
  So §246's context-outside-the-transaction rule — the thing the row is *about* — has no test.
  **`BF-M2` is unreachable for the same reason** and is recorded as such rather than as a pass.
- **`FI-M20` — the per-row permission order has no test.** `HANDOFF` calls the order load-bearing
  (the row's own key before the Background's `library:manage`) and names
  `TheGate.aReadOnlyCallerGetsAPlan` as its guard. That arm previews `catalogue.feature`, which has
  **no `Background:`** — so `ownedKeys` is empty and the order is unobservable. Closure: preview a
  feature that *has* a Background, as `viewer`.
- **`FI-M31` — the orphan filter is unexercised.** `orphansAreListed` previews against a suite
  holding one case the files do not name, so `!named.contains(...)` removes nothing whether it is
  there or not. Closure: assert orphans after an `APPLY`.
- **`FI-M35` — nothing asserts that an imported case is FILED.** Drop the created suite's id and
  every case is created unfiled; all the row counts still hold. This is the one to close first: it
  is the difference between an import that works and one that silently produces a flat pile.

**Three more corrections worth carrying:**

- **`GP-M7`: `everyConstructSurvivesTheRoundTrip` is an idempotence check, not a fidelity one.** It
  compares `parse∘render∘parse` against `parse`, so a keyword the parser drops is dropped identically
  on both sides and the fingerprints agree. Fidelity is the `Constructs` arms' job — `GP-M23`
  measured that at 8 of them, not the 9 the row claimed.
- **`SM-M9`'s "opposite directions" pair does not exist.** `anUnmigratedScenarioIsIncomplete`'s
  fixture carries no `expected_outcome`, so a different disjunct holds it incomplete whatever the
  step/blob term says. The guard is one-sided.
- **`FI-M32` is better covered than its author believed** — it predicted no red and got three, all
  of them the row-count arms it correctly said would be the only things to catch it.

**Fifteen registered gaps were confirmed as genuinely open**, and two matter beyond their rows:
**`FI-M11`** — a re-import resetting every case's priority to `MEDIUM` — is now *measured* as
uncovered, and the closure is one fixture case with a non-`MEDIUM` priority; and **`BF-M18`**, the
exit code that is `002-`'s release signal, has no test at all.

**Two traps for whoever re-runs any of this.** `@tcms/cli` and `@tcms/mcp` consume `@tcms/client`
through its built `dist`, so a mutation to `client/src` reports a **false green** until the client is
re-emitted — `PK-M1` passed that way once before the rebuild was added. And a **deletion** mutation
(`GP-M23`) needs `rsync --delete` to reach the runner at all; without it the row reports a silent 0.

## One finding, recorded and not fixed

`docs/agent-context/be-java.md` and `fe-react.md` each carry a dedicated `## §255` section (two, in
`fe-react`'s case: T7's and T4's) with a current header line.
**`docs/agent-context/tooling-engineer.md` carries a current `§255 T2` header line and six inline
`§255` references woven into its existing sections, but no `## §255` section of its own.** T8 may not
write that block — the cache is T2's to update, in T2's own commit — so it is stated here as a finding
rather than repaired. `docs/agent-context/be-planner.md`, which is new and untracked, records the
initiative as executed with task frontier T8 and `NNN` high-water mark **001**.
