# Unified step model & `.feature` import — Handoff

**Updated:** 2026-08-28 · **Updated by:** `be-java` (T8, then the gate + mutation pass) · **Plan:** `./delivery-plan.md`
**Repo:** `~/RiderProjects/tcms` · **Branch:** `master` · **Baseline commit:** `60233010` + T1–T8's uncommitted work

> Progress, evidence, decisions and deviations live in `delivery-plan.md` §2, §3 and §6–7.
> This file only says what to do next. Current position: **T1–T8 done, GATED GREEN on `shanks`, all
> 116 mutation rows executed — and uncommitted.** Seven browser rows are the only verification owed.

## Next task

**None. The initiative is complete.** All eight tasks are done; T8 wrote the record
(`docs/handoff/255-one-step-model-and-the-import.md`, one index row in `docs/HANDOFF.md`, §6a's two
strike-throughs, the proposal marked executed, `CLAUDE.md`'s fifth operator verb,
`docs/agent-context/be-planner.md`). There is no ninth task and no follow-up inside this pack.

**What is still owed, and by whom:**

1. ~~The human's post-batch `shanks` pass~~ → **RUN 2026-08-28, and green: backend `3408 / 0 / 0`,
   frontend `1833 / 1833` across 132 files, packages `393` (138 client / 153 mcp / 102 cli), lint
   `0` warnings on both, `pack:release` verified.** It took nine fixes to get there — two of the
   three stacks would not compile and the backend suite failed 31 arms. Three of the nine were in
   shipped code (the step-suggestion pick, `FeatureImportController.raced`, and the `Background:`
   detach that R6's short-circuit made unreachable); two test arms asserted the opposite of
   `api-surface.md` §5.4 and were rewritten to it. All of it is in §255's *Gates* section.

2. ~~123 queued verification rows~~ → **the 116 mutation rows were all EXECUTED**, mutate-run-record-
   revert, with the tree re-gated green afterwards. 99 behaved exactly as written; the results are in
   each row's own last column. **Seven browser rows remain owed** (`§255 US-1…US-4`, `FI-1…FI-3`) —
   they need a running instance and a human.

3. **The commit.** T1–T8, the nine gate fixes and the annotated queues are all uncommitted on
   `master`. `docs/audits/002-design-audit-r*.md` and `docs/audits/assets/` are untracked work from a
   **different strand** — stage only this initiative's paths, never `git add -A`.

4. **Four holes the mutation pass turned up, in the order worth closing them:**
   - **`FI-M35` — nothing asserts an imported case is FILED.** Drop the created suite's id and every
     case is created unfiled; every count still holds. One assertion closes it.
   - **`FI-M20` — the per-row permission order has no test.** Its arm previews `catalogue.feature`,
     which has no `Background:`. Preview `orders` as `viewer` instead.
   - **`FI-M31` — the orphan filter is unexercised.** Assert orphans after an `APPLY`.
   - **`BF-M1`/`BF-M2` — nothing drives `MigrateGherkinSourceCommand.run`.** The helper opens the
     `CompanyContext` itself and `run` ends in `System.exit`. §246's ordering rule is untested, and
     closing it means extracting the body the way `Resolution` and `exitCodeOf` were extracted.

   And **`FI-M11` is now measured as uncovered** — the priority reset. Its closure is one fixture
   case with a priority other than `MEDIUM`.

5. **Initiative `002-` — the human's to open, and NOT yet.** Its precondition is operational:
   `migrate-gherkin-source` run on **every** instance with a clean report (exit 0; exit 3 means
   cases are still on the blob — and **`BF-M18` confirmed that exit code has no test**). Only then
   does `002-` drop `test_case.gherkin_source` (re-creating `V2`'s `search_vector` without it),
   `test_step.shared_step_id` and `case_condition.inline_body`, remove `TestCase.gherkinSource` from
   the contract and retire the SPA's not-yet-migrated fallback. **`V70` is reserved**; head is `V69`.

6. **One finding, recorded and deliberately not fixed (D20):** `docs/agent-context/tooling-engineer.md`
   carries a current `§255 T2` header and six inline `§255` references, but **no `## §255` section of
   its own**. T8 may not write it — that cache belongs to T2's task and its own commit.

## Blocked

| Task | Blocked on | Owner | Since |
|---|---|---|---|
| — | nothing | — | — |

## Watch out for

- **All eight tasks are done and NONE of the seven code ones has been compiled or run.** No
  `./gradlew build`, no `./gradlew test`, no npm suite, no `npm run lint`. **Twelve** brand-new test
  files across the three stacks have never executed. A post-batch pass should expect **backend,
  packages AND frontend green** — T4 repaired D2, so the frontend gate can be green for the first
  time in this initiative. Details and the exact per-file counts are under *Next task* above.
- **T7 touched three files outside its own feature directory, and two of them are load-bearing
  everywhere.** `components/PermissionGate.tsx` and `auth/useCan.ts` gained the **any-of** form
  (deviation **D18**) — `Gate.canAny`/`reasonAny`, and `permission` widened to
  `PermissionKey | PermissionKey[]`. The single-key path is deliberately unchanged and still routes
  to `can`/`reason` rather than through a one-element array, so the hundred existing call sites keep
  the exact verdict they had; **if `PermissionGate.test.tsx` reddens anywhere but its two new arms,
  that assumption is what broke.**
- **The import screen is `Table`, not `DataGrid`, and that is deviation D16 rather than an
  oversight.** §241 measured that DataGrid does not render in jsdom. Do not "upgrade" it: every one
  of T7's criteria is an assertion about a row, and a DataGrid makes all of them inspection-only.
- **The pure half of the import screen is `features/cases/featureFiles.ts`, not the page** (**D19**),
  and it is there for a gate rather than for taste: `react/only-export-components` is a warning and
  `npm run lint` is a gate at **zero** warnings, so a `.tsx` exporting `FeatureImportPage` may not
  also export `capsRefusals`. Moving them back is a red lint nobody in this batch could have seen.
- **`countScenarios` is a heuristic and its refusal sentence hedges on purpose** (**D17**). It
  over-counts a file that writes `Scenario:` inside a doc string, so it can refuse a folder the
  server would have taken. Tightening it means parsing, and the SPA does not parse — that is what
  `POST …/parse-gherkin` is for.
- **Apply's enablement is `totals.create + totals.update > 0` and must not become a permission
  check.** That one expression is what makes a `case:read`-only caller's all-refused plan render
  with Apply off *and* forty importable scenarios import beside two refusals. A `PermissionGate`
  around the button would have to guess which, and would be wrong in the direction that hides work
  from somebody who can do it.
- **`ImportRow.reason` is rendered verbatim and must never go through `describeError`.** The
  sentences are contract, listed on the property in `openapi.yaml`, and clients may match on them;
  `describeError` is for failures, and an import that refuses a row succeeded. The one that names a
  permission key is the only place a caller can read which key they need.
- **`FeatureImportPage`'s `DataRegion` is mounted conditionally, and it has to be.** `DataRegion`
  reads an undefined `data` as "still loading", which is right for a query and wrong for a mutation
  nobody has pressed — an unconditional region shows a skeleton over an empty screen from the moment
  the page opens. The condition is `isPending || plan !== null || unfielded`.

- **T6 is the largest untested surface in the initiative and it is worth saying where the risk
  sits.** `FeatureImportTest` is 26 arms against a fixture of eight `.feature` files, a custom role,
  five suites and two seeded cases, none of which has ever been loaded. The arms most likely to need
  repair on the first shanks pass are the ones whose expectations are exact strings computed from
  the fixture's line numbering (`.provenanceIsRecorded` asserts `imported_line = 12`) and the ones
  that count rows (`.theFolderIsImported` expects `2 + 4` cases, 4 conditions, 9 attachments, 7
  suites). If one of those is off by the fixture rather than by the code, the number is the thing to
  correct — the argument at each assertion says which.
- **`condition_imported_key_idx` and `condition.imported_key` were already in `V69`.** T6 takes no
  migration; head is still `V69` and **`V70` is still reserved for `002-`**. `HANDOFF`'s previous
  "T6 takes the migration T5 did not" was read off the delivery plan's file list rather than off the
  migration on disk — deviation **D13**.
- **`AutomationKeys` is in `com.tcms.api`, and a `com.tcms.shared` must not be created for it** —
  deviation **D12**. `api/package-info.java` has a section headed *"Why it is not called `shared`"*;
  §125 refused the same module by name. `IngestedResult.fullName` calls it, so there is exactly one
  spelling of `"<Feature>#<Scenario>"` in the product, and `AutomationKeysTest` in `execution` is
  what holds the two modules to it.
- **T5 answered OQ1 with an option the question did not have — deviation D11.** The command lives
  in `com.tcms` (the composition root) and NOT in `com.tcms.provisioning`; `GherkinBackfill` is in
  `repository` as planned. Nobody should "tidy" the command back into `provisioning`: the edge is
  refused by `ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation` **and**
  `ModuleStructureTest.declarationsNameOnlyTheFoundationModules`, and `repository` must not become
  a foundation module because `execution` may not depend on it. `§255 BF-M14` is the mutation row.
- **`OperatorCommands.MIGRATE_GHERKIN_SOURCE` is the one PUBLIC verb constant.** The other four are
  package-private because their runners are in the same package. A sixth verb whose runner is in
  `provisioning` should stay package-private.
- **Nothing asserts the exit code of a real run (`§255 BF-M18`).** `report` is private and `run`
  ends in `System.exit`. `002-`'s release gate is "the report shows zero cases left on the blob",
  and exit **3** is how a script reads that — the arithmetic behind it (`stillOnTheBlob()`) IS
  asserted, only the mapping is not. Closing it means extracting `exitCodeOf(GherkinBackfillReport)`
  the way `Resolution` was extracted from `resolve`.
- **`--actor` must be able to SEE every project being migrated, not merely be a member.**
  `CaseRenderer.vocabularies` is a gated read, and a snapshot rendered without the grant records
  every classified case as unclassified. Cases in an unreachable project are listed, not migrated.
  A Company Admin satisfies both gates; the usage text says so.
- **The gate that will fail first if T4 got something wrong is `npm run build`, not `test:run`.**
  `StepSchema` gained three required fields, so every step literal in the suite is a compile error
  until it carries them; the ones this task found are repaired, and a missed one is a `tsc` failure
  with the file named.
- **`citedFiles.test.ts` reads `frontend/src/**` from the PACKAGES workspace.** A stale path cited
  in a frontend comment reddens the packages job, not the frontend one. T4 added exactly one new
  citation (`vitest.config.ts`).
- **`test_case.gherkin_source` is still writerless by decision**, pinned in
  `ArchitectureRulesTest.WRITERLESS_BY_DECISION` with `002-` as its named deleter. **T5 does not
  clear it** and T6 must not either: the `002-` contraction reads it as proof of coverage
  (arm `.theBlobSurvives`, mutation `§255 BF-M23`).
- **T5 is the only thing that can empty the population the SPA's fallback serves.** `StepsPanel`
  shows a *"Not yet migrated — ask your operator to run `migrate-gherkin-source`"* note for a case
  with zero rows and a blob, and browser row `§255 US-4` needs exactly such a case — which no
  verification lane can author, and which T5 is designed to remove. Driving `US-4` therefore has to
  happen on an instance **before** the backfill is run on it; the row says so.
- **Execution renders the pinned snapshot, not `test_step`** (`ExecutionViews.steps` ←
  `CaseFacts.PinnedStep` ← the snapshot JSON). A migrated case only executes per step from its
  **new** version onwards, which is why T5 mints one rather than only writing rows.
- **Rendering a case whose `PRE` condition body is prose produces text the parser will not read
  back.** That is the spec (`api-surface.md` §5.2), not a defect.
- **`io.cucumber:gherkin` is pinned at 42.0.1** with `messages` and `jspecify` beside it, because
  gherkin declares both as version **ranges**. Deviation **D5**.
- **`DataTableConverter` was planned and deliberately not built** — deviation **D1**. Do not add one.
- **`CaseUpdateRequest.overlay` takes the four contract-REQUIRED fields from the body as sent, NOT
  from the stored row.** So an update path that spells `Priority.MEDIUM` into `sent` resets the
  case's priority, silently, on every re-import. `FeatureImportService` passes
  `existing.getPriority()` for exactly this reason and `§255 FI-M11` is the mutation row — a
  **registered gap**, because no fixture case has a priority other than `MEDIUM` and the mutation
  therefore predicts no red. The same trap is one line away for `severity` (`FI-M12`).
- **`postconditions: null` is what makes a POST condition survive a re-import.** Null is "leave them
  alone" and `[]` is "detach them all" — one character apart on `CaseUpdateRequest`, and the
  difference is whether an import silently strips every postcondition a human attached to every case
  it touches. Catalog addendum I.3, arm `.humanConditionsSurvive`, mutation `§255 FI-M13`.
- **Ownership is `condition.imported_key` being non-null and nothing else.** A re-import replaces
  only the `PRE` attachments whose condition has one; a human-attached `PRE` comes back after the
  owned set, and a `POST` is never sent. Two mutations bracket it (`FI-M14`, `FI-M15`).
- **The plan's per-row permission order was changed and the change is load-bearing.** The row's OWN
  key (`case:create` / `case:edit`) is checked BEFORE the Background's `library:manage`, so a
  `case:read`-only caller is told the thing they need first. `§255 FI-M20` is the row that moves it
  back, and `TheGate.aReadOnlyCallerGetsAPlan` is what reddens.
- **Nothing in the backend suite drives an ingestion against imported cases** — deviation **D14**.
  The two spellings of `automationKey` are checked from both modules over one fixture
  (`gherkin/import/report.json`); the end-to-end `unmatched_count = 0` is browser row `§255 FI-1`,
  which T7 already owns. Do not read the passing key assertions as "the integration is proven".
- **The 409 handler on `FeatureImportController` is unexercised** (`§255 FI-M41`). Two concurrent
  `APPLY`s against one project is not a shape this harness can stage, so both `type` slugs are
  inspection only.

## Repo state on last check

| Check | Result | When |
|---|---|---|
| Build | ✅ **green on `shanks`** — backend `./gradlew build`, frontend `build`+`lint`, packages `build`+`lint`+`pack:release` | 2026-08-28 |
| Tests | ✅ **backend 3408 / 0 · frontend 1833 / 1833 (132 files) · packages 393** — after nine fixes; the first pass would not compile on two stacks and failed 31 arms | 2026-08-28 |
| Migrations | head `V69__step_data_and_import_provenance.sql`; `V70` reserved for `002-`. **T4, T5, T6 and T7 all take none** — `V69` already carried `condition.imported_key` and its index (D13) | 2026-08-28 |
| Contract | changed **three** times (T1, T3, T6 — one path, eight schemas); both copies regenerated after T6 and byte-identical (`diff` run, it generates and does not test). **T7 opened no contract file and regenerated nothing** | 2026-08-28 |
| Uncommitted work | the plan pack, `docs/agent-context/be-planner.md`, and all of T1–**T8**. `docs/audits/002-design-audit-r*.md` + `docs/audits/assets/` are untracked work from **another strand** — do not stage them | 2026-08-28 |
| Verification | **116 mutation rows RUN** (99 as written · 25 count-corrected · 18 gaps confirmed · 4 refuted · 3 corrected · 2 unreachable); **7 browser rows still owed** | 2026-08-28 |
| Record | `docs/handoff/255-one-step-model-and-the-import.md` + one `docs/HANDOFF.md` index row; §6a struck at `:327` and `:345`; proposal marked EXECUTED | 2026-08-28 |

> Re-verify before trusting it — this is working-tree state, and it goes stale fastest.

## How to verify current state

```bash
git status --short
ls backend/src/main/resources/db/migration | sort -V | tail -1
diff frontend/src/api/schema.d.ts packages/client/src/schema.ts   # must be identical
grep -rn gherkinSource frontend/src --include='*.tsx' --include='*.ts' | grep -v schema.d.ts
                                                   # read-side + comments only; no form state
ls backend/src/main/java/com/tcms/*.java           # TcmsApplication + MigrateGherkinSourceCommand
                                                   # — two files, deliberately (D11)
~/bin/tcms-remote frontend npm run build           # on shanks — the human runs this, not a subagent
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote backend ./gradlew build
~/bin/tcms-remote packages npm run build
```
