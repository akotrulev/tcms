# One step model — Gherkin as a property of a step, not a mode of a case

**Date:** 2026-08-27. **Status: EXECUTED 2026-08-28 by `docs/plans/001-unified-step-model/`** —
handoff [§255](../handoff/255-one-step-model-and-the-import.md), tasks T1–T8. This document is kept as
the argument that opened the initiative, not as a description of what was built: for that, read the
plan pack (§2 status, §7 the twenty deviations D0–D19) and §255. Supersedes the *storage* half of plan
§6a; keeps its four rules, and satisfies rule 3 (*"store the AST relationally and keep the canonical
`.feature` text"*) for the first time.

> **Two premises here are wrong and were corrected during planning — read them before trusting a
> mechanic below.**
>
> 1. **§3b's `Background:` → *inline* `PRE` conditions** (the mapping table, and *"Inline, not a
>    library `condition`: no `library:manage` needed"*). There is no inline field to write:
>    conditions have been **reference-only since §95** and `case_condition.inline_body` is dead DDL.
>    Amendment **AM1** replaced it with a **library `PRE` condition per Background step**, text-hash
>    `imported_key`, deduplicated project-wide — so the permission this section says it avoids is the
>    permission the shipped design requires, and it is a per-row refusal (`needs library:manage for
>    the Background`) rather than a silent one.
> 2. **§4's *"Execution: nothing structural"*, with version snapshots listed as a thing to
>    *confirm*.** Execution never reads `test_step`: `ExecutionViews.steps` renders
>    `CaseFacts.PinnedStep` out of the version snapshot JSON. Writing rows is therefore not enough —
>    a migrated case executes per step only from its **next** version onwards, which is why
>    `migrate-gherkin-source` mints a version rather than only writing rows, and why `--actor` must
>    be able to *see* every project being migrated.
>
> **The diagnosis in §1, and the refusal in §8, were both right and are what the initiative was
> built on.** Also note §7's gate column is not what happened: the whole initiative ran under the
> standing no-gates directive and **nothing in it has been compiled or run** (§255, *Gates*).

---

## 1. Why Gherkin feels "thrown in" — what actually shipped

Plan §6a designed two first-class formats over **one** `test_step` table. V1 built that table for
both: `keyword` nullable with *"non-null identifies a Gherkin step"*, plus `docstring` and
`datatable` for the block arguments. `io.cucumber:gherkin` was named as the parser.

What shipped is a different thing:

| §6a said | The code does |
|---|---|
| Store the AST relationally *and* the canonical text | `test_case.gherkin_source` — a textarea. `CaseEdit.validate` writes `List.of()` steps for a `GHERKIN` case (`CaseEdit.java:400`). `test_step.keyword` has **never been written**. |
| Parse server-side with the real parser | No `cucumber`/`gherkin` dependency in `build.gradle.kts`. The only "parser" is a `/^(Given\|When\|Then…)/` regex in `CaseDetailPage.tsx:823`, for syntax colouring. |
| Execution is format-agnostic; both render as a step list | A Gherkin case has zero `test_step` rows ⇒ zero `step_result` anchors. `TestExecutionView` says so: *"Empty is a real state: a Gherkin case has no structured steps and the screen says so."* No per-step verdict, no per-step evidence, no per-step comment. |
| `Then` rows are the assertions | `CaseCompleteness` cannot find a `Then` without parsing Gherkin in SQL, so it takes any non-empty blob as "says what should happen". |
| Per-step Cucumber mapping | `CucumberReportParser` reads per-step results and throws them away — there is nothing to attach them to. |

So the Gherkin case is a **string with a chip on it**. Nothing that makes a structured case a *test
case* — stable step ids, per-step results, evidence anchoring, step suggestions, completeness,
version snapshots of steps — applies to it. That is the disintegration the user is feeling, and it is
a storage decision, not an editor one. Reskinning the textarea will not fix it.

## 2. The proposal in one sentence

**Every case is an ordered list of step rows; a step may carry a Gherkin keyword; `stepFormat`
becomes the case's authoring lens and export eligibility, not where its steps live.**

The "mix" is literal: one step shape, and each field is legal in both lenses.

```
step = { keyword?: GIVEN|WHEN|THEN|AND|BUT
       , action:    text                      -- the step text in Gherkin; the action in structured
       , testData?: text                      -- structured data; OR
       , docString?: text                     -- Gherkin block argument (multi-line)
       , dataTable?: string[][]               -- Gherkin block argument (table)
       , expectedResult?: text }              -- structured check; on a THEN, an optional concrete observable
```

Rules that make the mix coherent rather than a union of two forms:

1. **`keyword` is the only thing the lens requires or forbids.** In the `GHERKIN` lens every step
   must have one and the first may not be `AND`/`BUT`. In the `STRUCTURED` lens it is optional —
   a team that likes "Given/When/Then" as a rhythm on numbered steps may use it.
2. **A `THEN` (or an `AND`/`BUT` continuing a `THEN`) is an assertion row.** Its `expectedResult`
   is *optional*: empty means "the step text is the check" (`Then the order shows as cancelled`);
   filled means "here is the concrete observable" (`…` + `status chip reads CANCELLED, red`). Same
   rendering on the execution screen as a structured step with an expected result. This is the one
   place Gherkin's implicit assertion and the structured explicit one meet, and it is where testers
   want both.
3. **`testData`, `docString`, `dataTable` are three spellings of "the data this step uses", at most
   one per step** (a check constraint). The editor shows one *Data* cell with a text / block / table
   mode. `docString`/`dataTable` are the two columns V1 already has and `TestStep.java`'s header
   says are *"waiting for the contract change that gives them somewhere to go"* — this is it.
4. **Lens switch is lossless, so it is a toggle, not a conversion.** `STRUCTURED → GHERKIN` asks
   for the missing keywords (pre-filled: first `GIVEN`, last step with an `expectedResult` → `THEN`,
   the rest `WHEN`/`AND`); `GHERKIN → STRUCTURED` changes nothing stored — the keyword just becomes
   a prefix. §6a rule 1 ("no lossy auto-conversion") stays true because there is no longer a
   lossy conversion to forbid; the lossiness was a property of the two-storage design.
   *§6a rule 1's diff dialog → superseded by this section.*
5. **Text in, text out — but never text as storage.** Authors who think in Gherkin keep a textarea:
   a *Paste Gherkin* action parses a scenario into rows (server-side, `io.cucumber:gherkin`), and a
   `GET …/cases/{id}/feature` renders the rows back as canonical `.feature` text. The text is a
   *view* of the case, so §6a rule 3's "canonical serialized text" is satisfied by derivation, and
   the Git-as-source-of-truth mode (§6a sync modes) has an import primitive when it is built.

### How the rest of a case maps

| Gherkin | TCMS field | Note |
|---|---|---|
| `Feature:` | Suite (§98) and/or Feature classification | Not stored per case. Export groups by suite. |
| `Scenario:` title | `title` | Already the case. |
| free text under `Scenario:` | `objective` / `description` | Already the case. |
| `Background:` | `case_condition` (`PRE`), inline or library | Already exists (§26). Paste maps Background steps to inline preconditions; export emits identical PRE blocks across a suite as one `Background`. |
| `Given/When/Then/And/But` | step rows with `keyword` | The point of this doc. |
| `"""` DocString, `\|` DataTable | `docString`, `dataTable` | Columns since V1, on the contract now. |
| `@tag` | `case_automation_tag` | Already the case. |
| `Scenario Outline` + `Examples` | `parameters[]` / `dataSets[]` (plan §6m) | **Not built for either format.** Parse refuses an Outline with a clear message until then; export never emits one. Out of scope here. |

## 3. Worked example

Authored in the Gherkin lens:

```
Scenario: Cancel an order that has not shipped
  Given a paid order in status PACKED
  When I cancel it from the order page
  Then the order shows as cancelled
    -> expected: status chip reads CANCELLED; "Cancel" button disabled
  And a refund of the full amount is queued
    """
    amount: 49.90 EUR
    """
```

Stored (one `test_case` row, four `test_step` rows, `step_format = GHERKIN`):

| pos | keyword | action | data | expected_result |
|---|---|---|---|---|
| 0 | GIVEN | a paid order in status PACKED | | |
| 1 | WHEN | I cancel it from the order page | | |
| 2 | THEN | the order shows as cancelled | | status chip reads CANCELLED; "Cancel" button disabled |
| 3 | AND | a refund of the full amount is queued | docString `amount: 49.90 EUR` | |

Execution screen: four rows, each with its own pass/fail, comment and evidence, anchored on stable
step ids — exactly what a structured case gets today. Rows 2–3 render as assertion rows. Switch the
lens to `STRUCTURED` and the same rows read as *1. Given a paid order… 2. When I cancel… 3. Then …
→ status chip reads…*. `GET …/feature` prints the block above, minus the `-> expected:` line (that
is rendered as a `# expected: …` comment so the round trip loses nothing).

## 3b. The reverse direction — importing `.feature` files

§2.5 turns *one scenario* into *one case's rows*. The import turns *files* into *suites and
cases*, and it is the same parser one altitude up. It is also the primitive under §6a's
"Git is the source of truth" mode — that mode is this endpoint driven by a webhook plus read-only
cases, and it stays one-directional.

### Mapping

| In the file | Becomes | Rule |
|---|---|---|
| one `.feature` file | — | Path is provenance only. A file holds one `Feature:`; several files per request. |
| `Feature:` name + description | **suite** (name, description) | Matched by name in the project; created if missing (`suite:manage`), or the caller points every file at one chosen suite. |
| `Rule:` (Gherkin 6) | nothing structural | Its scenarios join the feature's suite; the rule text is prefixed into each case's `objective`; its `Background` merges after the feature's. Named as a limitation in the preview. |
| `Background:` | inline `PRE` conditions on every scenario of the feature | Inline, not a library `condition`: no `library:manage` needed and no library entry minted per file. A "promote to library" is a later, human act. |
| `Scenario:` | **case** — `DRAFT`, `stepFormat = GHERKIN`, `automationStatus = AUTOMATED` | Title, description → `objective`, steps → rows (§2). |
| scenario | `automationKey = "<Feature name>#<Scenario name>"` | **The whole reason to import rather than paste.** `CucumberReportParser.fullName()` builds exactly this string, so the first Cucumber report a team posts matches every imported case — no `unmatched` list, no `POST /automation-mappings` round. |
| `@tags` on feature + scenario | `case_automation_tag` (without `@`) | Feature tags are inherited by every scenario, as Cucumber does. |
| `"""` / `\|` | `docString` / `dataTable` | §2.3. |
| `*` step keyword | previous keyword, `AND` if there is none | What the parser gives. |
| `# language:` | canonical enum | Keywords are stored as the enum, not the localised word; export renders `en`. |
| `Scenario Outline` + `Examples` | **refused, listed** | Until `dataSets` exist (plan §6m). The preview names the line. Expanding one case per `Examples` row is the interim option if a team needs it — it is what Cucumber's own report does — but it makes N cases out of one and is not the default. |

### Two calls, one plan

`POST /projects/{projectId}/feature-imports` with `{ files: [{ path, content }], suiteId?,
onExisting: SKIP | UPDATE, mode: PREVIEW | APPLY }`. The file travels as a string, on
`POST /ingestions`'s reasoning: the SPA reads the files (drop a folder), and there is no
`Content-Type` to sniff. `APPLY` requires `Idempotency-Key`, again as ingestion does, because a
replay of an import is a second copy of every scenario.

`PREVIEW` writes nothing and returns the plan; `APPLY` recomputes it and executes it **atomically**
— one transaction per request, nothing half-imported. Both return the same shape, one row per
scenario:

```
{ path, line, feature, scenario, automationKey,
  action: CREATE | UPDATE | SKIP | REFUSE,
  reason?: "exists (SKIP)" | "approved — not touched by UPDATE" | "Scenario Outline" |
           "automationKey already used by TC-412 in project X" | parser error text,
  caseKey? }                                  -- filled after APPLY
```

plus `suites: [{ name, action: CREATE | REUSE }]` and `orphans: [{ caseKey, automationKey }]` —
cases in the target suite whose `automationKey` is **not in the files**. Listed, never deleted: a
renamed scenario looks exactly like a deleted one plus a new one, and ingestion's own rule applies —
*a rename must not become a permanent duplicate*, so a human reconciles it (rename the case, or
confirm the retirement).

### Re-import

The match is `automationKey` equality (unique per company, `test_case_automation_key_idx`).

- `SKIP` (default): an existing case is left alone, listed.
- `UPDATE`: title, objective, tags, preconditions and steps are replaced **through `CaseEdit`** — no
  second assembler of a legal case — and step ids are kept by matching `(keyword, action)` in
  order, so an unchanged step keeps every result recorded against it (`TestStep.java`'s most
  expensive property). Only `DRAFT` / `IN_REVIEW` cases are updated; an `APPROVED` one is listed as
  skipped with the reason, because silently reopening a sign-off from a file drop is the shape §208
  exists to prevent. `DEPRECATED` likewise.
- A key that resolves to a case in **another project** of the company is a `REFUSE` with the case
  named — the same argument `suiteId`'s validator makes.

### Provenance — and it settles §4.3

`test_case.gherkin_source` is **renamed** `imported_source`, and `imported_path TEXT` is added
(`V70`). They hold the scenario's original text and file path at last import, are never written by
the editor, and are what lets a later preview say *"changed in the file since import"* versus
*"edited in TCMS since import"* — the only conflict the UPDATE policy actually needs to name. The
detail view shows *Imported from `features/orders.feature:12`* as a chip.

### Permissions

`case:create` for `CREATE`, `case:edit` for `UPDATE`, `suite:manage` for a `CREATE` suite;
`PREVIEW` needs the read the caller already has. The plan reports a `REFUSE` per row the caller
cannot perform rather than a 403 for the request. **Automation Service's `case:create` is
"quarantine only" and does not cover this** — a CI job that can import is the Git-source-of-truth
mode, which is a project setting with read-only cases, not a token with a wider grant.

## 4. What changes, by layer

### Contract (`openapi.yaml`) — the first commit

- `TestStep` / `TestStepInput`: add `docString` (string, nullable) and `dataTable`
  (`string[][]`, nullable). `keyword` gets the enum on the input too (today it is a bare string).
- `TestCaseInput`: `steps` required for **both** formats under `ProcedureRule.REQUIRED`;
  `gherkinSource` becomes write-ignored → removed from the input in the contract-cleanup commit
  (§4.3). `TestCase` keeps serving `gherkinSource` **derived** for one release so the SPA's
  existing readers do not break, then it goes.
- `POST /projects/{id}/cases/parse-gherkin` — body `text/plain`, returns `TestStepInput[]` plus
  `preconditions` for a Background, plus `title`/`objective` if a `Scenario:` line is present. Pure,
  persists nothing, 400 with the parser's line/column on syntax error, 400 on `Scenario Outline`.
- `GET /projects/{id}/cases/{caseId}/feature` — `text/plain`, the canonical rendering. Also the
  seed of the M4 `.feature` export (a suite-level `GET …/suites/{id}/feature` is a `for` loop over
  this).
- `POST /projects/{id}/feature-imports` — §3b. `FeatureImportRequest` / `FeatureImportPlan`
  schemas, with their `required` arrays down to the nested row.

### Backend

- `io.cucumber:gherkin` dependency (the official parser — §6a rule 3, never hand-roll).
  `GherkinScenarios` in `repository`: `parse(text) → List<StepEdit> + conditions + heading`,
  `render(case, steps, conditions) → String`. Round-trip test: parse ∘ render ∘ parse is identity
  on the AST.
- `FeatureImportService` (§3b) on top of it: plan, then apply through `SuiteService` and
  `CaseEdit`/`CaseUpdateService` — it assembles no case of its own. Tests: a fixture file with a
  Background, a Rule, tags at both levels, a DocString, a DataTable, an Outline (refused), a
  duplicate key in a sibling project (refused), an APPROVED case under UPDATE (skipped), and a
  step-id-preservation case (edit the `When`, re-import, the `Given`'s id is unchanged).
- `TestStep`: map `docstring`, `datatable` (`jsonb` → `String[][]` via a converter).
- `CaseEdit.validate`: the lens rules in §2.1 (keyword required / first not AND-BUT in `GHERKIN`;
  at most one of the three data spellings). Stop writing `gherkinSource`; stop clearing `steps` for
  `GHERKIN`. `ProcedureRule.DEFERRED` (§200 stubs) unchanged.
- `CaseCompleteness.recomputeIncomplete`: the `GHERKIN` branch goes. "Says what should happen" =
  `expected_outcome` non-empty **or** any step with non-empty `expected_result` **or** any step
  with `keyword = 'THEN'`. Reads no Gherkin in SQL — the thing that class refused to do is no longer
  asked of it.
- Migration **`V69`**: `ALTER TABLE test_step ADD CONSTRAINT ck_test_step_one_data CHECK
  (num_nonnulls(test_data, docstring, datatable) <= 1)`; nothing else. **No DDL drops
  `gherkin_source` in this slice** — expand first, contract in §4.3.
- Backfill, **not** a Flyway migration: an operator command `migrate-gherkin-source` (same image,
  same entry as `provision-company`), per company under `CompanyContext`, idempotent (skips a case
  that already has step rows), parses each `GHERKIN` case's `gherkin_source` into rows, and prints
  the cases whose text did not parse so a human fixes those by hand. A Java parser under FORCE RLS
  cannot run inside `flyway migrate` without the company context every V-script refuses to fake
  (the ERD's "per-company vocabularies are functions, not INSERTs" note explains why). Until the command has run, the detail
  and execution readers keep their existing `gherkinSource` fallback, so a deployed-but-not-backfilled
  instance loses nothing.
- Version snapshots: confirm `TestCaseVersion.snapshot` carries `keyword` (it should — `StepEdit`
  has it) and add `docString`/`dataTable`; `execution.StepFormat` unchanged.
- Execution: nothing structural — `stepResults[]` already anchors on `stepId`; Gherkin cases start
  producing rows and the *"empty is a real state"* paragraph in `TestExecutionView` gets deleted.
  `CucumberReportParser`'s per-step results gain a target (`position` + normalized text) — a
  follow-up, not this slice.

### SPA

- **One step editor.** The existing draggable row editor (§94) gains a keyword column: always shown
  in the `GHERKIN` lens (a select, `Given/When/Then/And/But`), shown-when-set in `STRUCTURED` (an
  "add keyword" affordance per row). The *Data* cell gets the text / block / table mode.
- **Paste Gherkin** button in both lenses → dialog with the textarea authors already have →
  `parse-gherkin` → rows appended/replaced (author's choice), Background → preconditions.
  Parser errors land on the dialog with line numbers. This is where `GHERKIN_TEMPLATE` moves.
- Format toggle → lens toggle with the pre-fill from §2.4; no diff dialog, nothing to lose.
- `CaseDetailPage.StepsPanel`: one renderer, keyword as a styled prefix, assertion rows for
  `THEN`. `GherkinView` and its regex go. A *View as .feature* action opens the `…/feature` text.
- Execution page: same — Gherkin cases just have rows now.
- `caseSchema.ts`: mirror the validation; `gherkinSource` leaves the form state.
- `packages/`: `tcms case edit`/MCP `tcms_shape` are rendered from the catalogue (§254) — the
  catalogue entry for a step changes, the carriers follow; both `schema.d.ts` copies regenerate.

### 4.3 Contract phase, one release later

`V70`: `gherkin_source` → `imported_source`, plus `imported_path` (§3b provenance). The editor
never writes either; the import writes both. `gherkinSource` leaves the contract; `importedFrom`
(`{ path, line }`, nullable) joins `TestCase`. `CaseCompleteness` already does not read it.

## 5. What this buys, concretely

- Gherkin cases get **per-step verdicts, comments and evidence** for the first time.
- `CaseCompleteness` and every future health signal stop special-casing a format.
- Step suggestions (`StepSuggestionEndpoint`) and any future step library apply to Gherkin steps.
- Cucumber ingestion has somewhere to put step results (§6a's "per-step mapping").
- `.feature` export (M4) becomes a renderer over rows instead of a concatenation of blobs, and
  **import is its inverse on the same parser** — a team with an existing automation repo drops the
  folder in and has a suite per feature, a case per scenario, and every case already keyed for
  the first Cucumber report.
- One editor, one renderer, one validator. The two-editor estimate in §6a's "impact" paragraph
  is retired.

## 6. What it does not do, said out loud

- **Scenario Outline / Examples** stay unbuilt (as they are for `dataSets` on structured cases).
  The parser refuses them with a message rather than flattening them.
- **Git sync modes** are still M4 and still one-directional per §6a — but the manual import is
  the primitive they will call, and the read-only-cases half is the only thing they add.
- **Import never deletes.** Orphans are listed; retiring them is a human act.
- Cucumber **per-step** result attachment is a follow-up; case-level ingestion is unchanged.
- The backfill is an operator action; an instance that deploys and does not run it keeps working on
  the fallback, and *reports that state* via the command's dry-run output.

## 7. Sequencing and gates

| Phase | Commit | Gate |
|---|---|---|
| 1 | `openapi.yaml` + both `schema.d.ts` + parser module + `V69` + `CaseEdit`/`CaseCompleteness` + backfill command | `./gradlew build` on shanks; contract tests; round-trip test |
| 2 | SPA editor/detail/execution + `caseSchema.ts` | `npm run build`, `lint`, `test:run` on shanks |
| 2b | `FeatureImportService` + endpoint + SPA import page (drop files → preview table → apply) | backend + frontend gates on shanks |
| 3 | `packages/` catalogue + carriers | `npm run build`, `test:run --workspaces`, `pack:release` |
| 4 | Docs: handoff §255, `be-java.md`/`fe-react.md` caches, §6a strike-throughs, verification-queue rows (§65) | — |

Browser and mutation checks are **queued, not run** (§65): rows for "paste a scenario, save,
execute, mark step 3 failed with evidence, reopen — evidence is on step 3", "toggle lens both ways
— nothing lost", "backfill a company with one unparsable case — it is listed, the rest migrate", "import a
folder of three features, post a Cucumber report for them — zero `unmatched`", "re-import with one
scenario renamed — orphan listed, nothing deleted".

## 8. The alternative I am not recommending, and why

*Keep the blob, parse it on read into ephemeral steps.* Cheaper by a phase, and wrong at the one
property `TestStep.java`'s header calls the most expensive in the class: ephemeral steps have no
stable id, so a `step_result` cannot anchor to one, and a reworded `Then` reattributes every past
screenshot. The row model exists precisely to make that impossible; the blob model makes it the
default.
