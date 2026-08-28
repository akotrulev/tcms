# Unified step model & `.feature` import — API Surface Plan

**Date:** 2026-08-27 · **Written by:** `be-shared-api-surface-plan`
**Blueprint:** `./blueprint.md` · **Data model:** `./data-model.md`

This is a **delta** over `docs/specs/openapi.yaml` (base `/api/v1`, `openapi.yaml:43`), which is the contract and stays the contract: every change below is made **there first**, both `schema.d.ts` copies are regenerated (`frontend: npm run gen:api` → `src/api/schema.d.ts`; `packages/client: npm run gen:api` → `src/schema.ts`), and every new schema carries its `required` array down to the nested object. The existing conventions win everywhere; §1 records them rather than choosing.

## 1. API-wide contract (existing, `#observed`)

| Decision | Choice | Note |
|---|---|---|
| Base path & versioning | `/api/v1`, path-versioned | `openapi.yaml:42-43` |
| Auth scheme | Bearer JWT session; `sub` is the user id; company from the session, never the request | `SuiteController.java:131` |
| Permission model | Permission **keys** checked in services via `ProjectService.writeGranted(userId, projectId, "<key>", …)` / `readVisible(…)` (`SuiteWriteService.java:225`, `CaseDetailService.java:83`); the 55-key catalogue is roles spec §A3 | No new key |
| Error body | `application/problem+json`, one `Problem` schema `{type,title,status,detail,errors?}` (`openapi.yaml:7861-7917`, §162–§164). `errors` keyed by the **input schema's property name** (`openapi.yaml:7927`) | No `instance`, no path echo (§163) |
| Unknown fields | **Ignored** — Spring Boot's Jackson default; no `FAIL_ON_UNKNOWN_PROPERTIES` setting and no `@JsonIgnoreProperties` in `backend/src` (blueprint §10a Q6). Recorded, not changed | V1.5 policy: *ignore* |
| Field validation status | `400` with `errors` (`CaseEdit` collects every violation before returning — `CaseEdit.java:320-341`) | Section 2 of the catalog: 400, all fields at once |
| Not-found vs forbidden | A project the caller cannot see is a **404** (`readVisible` → `Optional.empty()`); a project the caller can see but lacks the key on is **403** (`ConditionService.java:229`) | V5.3: 404 for invisible, 403 for visible-but-forbidden |
| Casing / dates | `camelCase`; ISO 8601 UTC | unchanged |
| Idempotency | `Idempotency-Key` only on creates that mint one row (`POST /cases`, `POST /suites`, `POST /ingestions`); a write that creates no single row relies on determinism (§245) | Q5: the import has **no** key |
| Concurrency | `lockVersion` in the body on `TestCase`/`Condition` writes (`ConditionInput` description, `openapi.yaml:9760`) | The import goes through the services and honours it |
| List envelope | existing `items/page/pageSize/total` shape | No new list endpoint here |
| Text bodies | `text/plain; charset=utf-8` where the payload is a document (new here) | Two endpoints below |

## 2. Endpoint map (delta)

| Service | Resource | Method | Path | Name | Description | Auth | Idempotent | Success |
|---|---|---|---|---|---|---|---|---|
| `repository` | Cases | POST | `/projects/{projectId}/cases` | Create case *(changed schema)* | `TestCaseInput` loses `gherkinSource`; `steps[]` items gain `docString`/`dataTable`, `keyword` becomes an enum | `case:create` | via key (unchanged) | 201 |
| `repository` | Cases | PATCH | `/projects/{projectId}/cases/{caseId}` | Update case *(changed schema)* | same | `case:edit` | yes | 200 |
| `repository` | Cases | GET | `/projects/{projectId}/cases/{caseId}` | Get case *(changed schema)* | `TestCase.steps[]` gain the two fields; `TestCase` gains `importedFrom`; `gherkinSource` stays, **deprecated** | `case:read` | yes | 200 |
| `repository` | Cases | POST | `/projects/{projectId}/cases/parse-gherkin` | **Parse Gherkin** | Text in, rows out; persists nothing | `case:read` (project-visible) | yes (pure) | 200 |
| `repository` | Cases | GET | `/projects/{projectId}/cases/{caseId}/feature` | **Render as `.feature`** | Canonical text for one case | `case:read` | yes | 200 |
| `repository` | Feature imports | POST | `/projects/{projectId}/feature-imports` | **Import feature files** | `PREVIEW` plans; `APPLY` plans and executes atomically | `case:read` to call; per-row `case:create` / `case:edit` / `suite:manage` / `library:manage` decide the rows | `PREVIEW` yes; `APPLY` **by determinism** (a replay does nothing and says so) | 200 |
| `execution` | Executions | GET | `/projects/{projectId}/runs/{runId}/executions/{executionId}` | Get execution *(changed schema)* | `stepResults[]` items gain `docString`/`dataTable` from the pinned snapshot | unchanged | yes | 200 |

Unchanged but **consumed** by this initiative's SPA work, listed so the consumer map is honest: `POST /projects/{projectId}/conditions` (`library:manage`, the paste dialog's "create precondition"), `GET /projects/{projectId}/step-suggestions` (now also fed by Gherkin rows).

## 3. Shared schemas (delta)

**`StepKeyword`** *(new schema; `TestStep.keyword` already inlines this enum — extract it so both step schemas and the catalogue reference one name)*
`enum: [GIVEN, WHEN, THEN, AND, BUT]`. Server pairing: `com.tcms.repository.StepKeyword` — add to `ContractEnumTest.OWNERS` (`ContractEnumTest.java:104`) or the build reddens.

**`TestStep`** (`openapi.yaml:9498`) — `required: [id, position, action, keyword, testData, expectedResult, docString, dataTable]` — every property present, nullable where a step may lack it, on `StepSuggestion`'s reasoning (`openapi.yaml:9527-9533`)

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | stable; unchanged |
| `position` | int | unchanged |
| `keyword` | `StepKeyword` \| null | unchanged values; now `$ref` |
| `action` | string | unchanged |
| `testData` | string \| null | unchanged |
| `expectedResult` | string \| null | unchanged |
| `docString` | string \| null | **new** ← `test_step.docstring` |
| `dataTable` | string[][] \| null | **new** ← `test_step.datatable`; `items: {type: array, items: {type: string}}`; at most one of `testData`/`docString`/`dataTable` is non-null |

**`TestStepInput`** (`openapi.yaml:9515`) — `required: [action]`

| Field | Type | Required | Constraints |
|---|---|---|---|
| `id` | uuid \| null | no | known id → update in place; unknown id → field error `steps` (unchanged, `CaseSteps.java:30-33`) |
| `keyword` | `StepKeyword` \| null | no | **now `$ref`** (was bare string). Binding stays a string on `CaseUpdateRequest.StepInput` so `CaseEdit.keyword(...)`'s field error (`CaseEdit.java:1042-1051`) is kept — the contract narrows, the server's message survives |
| `action` | string | yes | trimmed, non-blank (V2.9) |
| `testData` | string \| null | no | — |
| `expectedResult` | string \| null | no | — |
| `docString` | string \| null | no | ≤ 64 KiB (V2.3; a docstring larger than that is a file, not a step argument) |
| `dataTable` | string[][] \| null | no | ≤ 200 rows × ≤ 50 cells, each cell ≤ 4 KiB; an empty outer array is refused (`[]` is not a table — send null) |

**`TestCaseInput`** (`openapi.yaml:9345`) — `required: [title, stepFormat, priority, automationStatus]` unchanged; **`gherkinSource` removed**; the `steps` description changes to *"the procedure, in either lens; in `GHERKIN` every step needs a keyword and the first may not be And/But"*.

**`TestCase`** (`openapi.yaml:9120`) — adds `importedFrom` to `required` and properties:

| Field | Type | Notes |
|---|---|---|
| `importedFrom` | `ImportedFrom` \| null | `{ path: string, line: int }`, `required: [path, line]` ← `test_case.imported_path/imported_line`. Null unless imported |
| `gherkinSource` | string \| null | **unchanged wire shape, marked `deprecated: true`** with the description *"served for a case that has no step rows yet (not backfilled); null otherwise; leaves the contract in 002"*. Server: `null` when the case has ≥ 1 step row, else the stored blob |

**`StepResult`** (`openapi.yaml:11391`) — `required` gains `docString`, `dataTable`; both nullable; from the pinned snapshot (absent key in an old snapshot → null).

**`GherkinParseResult`** *(new)* — `required: [title, objective, tags, background, steps]`

*(Q7 note: a case's own preconditions and postconditions are the existing `TestCase.preconditions[]` / `.postconditions[]` — `openapi.yaml:9269-9272`, N each — and are unchanged by this initiative.)*

| Field | Type | Notes |
|---|---|---|
| `title` | string \| null | the `Scenario:` name, or null if the text starts at a step |
| `objective` | string \| null | the free text under `Scenario:` |
| `tags` | string[] | feature + scenario tags, without `@`; the SPA merges them into the form's tags |
| `background` | `ParsedBackgroundStep[]` | **one entry per Background step**, in order: `{ title: string, body: string[] }`, `required: [title, body]` — `title` = the step text with keyword, `body` = the step line plus its block lines, ready for `ConditionInput`. Empty array when there is no Background. **Nothing is created**; the SPA offers *Create precondition and attach* per step through the existing `POST …/conditions` (needs `library:manage`), attaching in order after the case's existing preconditions |
| `steps` | `TestStepInput[]` | ids all null; `keyword` set on every row; `# expected:` / `# data:` comment lines after a step populate `expectedResult` / `testData` |

**`FeatureImportRequest`** *(new)* — `required: [files, onExisting, mode]`

| Field | Type | Required | Constraints |
|---|---|---|---|
| `files` | `FeatureFile[]` | yes | 1–200 items; total content ≤ 8 MiB (under the 12 MB `max-request-size`, `application.yml:80`); paths unique within the request |
| `files[].path` | string | yes | 1–1024 chars, provenance only |
| `files[].content` | string | yes | ≤ 1 MiB |
| `suiteId` | uuid \| null | no | when set, **every** scenario files into it and no suite is matched or created; must be a live suite of this project (V5.1–V5.3 → field error `suiteId`) |
| `onExisting` | enum `SKIP` \| `UPDATE` | yes | DEC-1 |
| `mode` | enum `PREVIEW` \| `APPLY` | yes | — |

**`FeatureImportPlan`** *(new)* — `required: [mode, applied, scenarios, suites, conditions, orphans, totals]`

| Field | Type | Notes |
|---|---|---|
| `mode` | enum | echoes the request |
| `applied` | boolean | `false` for `PREVIEW`; `true` after a committed `APPLY` |
| `scenarios[]` | `ImportRow[]` | one per scenario **and one per file that did not parse** (with `scenario: null`) |
| `suites[]` | `SuiteAction[]` | `{ name, action: CREATE \| REUSE \| REFUSE, suiteId?, reason? }`, `required: [name, action]` |
| `conditions[]` | `ConditionAction[]` | **one per distinct Background step** across the request: `{ importedKey, title, action: CREATE \| UPDATE \| UNCHANGED \| REFUSE, conditionId?, reason? }`, `required: [importedKey, title, action]` (`UPDATE` only when the title was truncated and the full body differs — a hash key means the body itself cannot differ) |
| `orphans[]` | `Orphan[]` | `{ caseKey, automationKey }` — cases in the target suite(s) whose key is not in the files. Listed, never touched |
| `totals` | `ImportTotals` | `{ create, update, skip, refuse }` ints, `required` all four |

**`ImportRow`** — `required: [path, line, feature, scenario, automationKey, action, reason, caseKey, preconditions]`

| Field | Type | Notes |
|---|---|---|
| `path`, `line` | string, int \| null | `line` null for a whole-file refusal |
| `feature`, `scenario` | string \| null | — |
| `automationKey` | string \| null | `"<Feature>#<Scenario>"` |
| `action` | enum `CREATE` \| `UPDATE` \| `SKIP` \| `REFUSE` | DEC-1 |
| `reason` | string \| null | one of the fixed sentences in §5.4 |
| `caseKey` | string \| null | filled after `APPLY` (`TC-n`); for `SKIP`/`UPDATE` the existing case's key in both modes |
| `preconditions` | string[] | **the titles of the `PRE` conditions this scenario's case will carry after the row is applied, in position order** — the import-owned ones from the Background first, then (on `UPDATE`) the case's existing human-attached ones. Empty for a feature without a Background. `required` |

## 4. Validation & error contract

**Standard set:** `./validation-catalog.md` (the copy in this folder), applied API-wide as this API already applies it.

**This API's instantiation:**
- *Permission model:* keys per §1; the import checks **per row** and reports, and checks `case:read` (project visibility) once for the request.
- *Not-found vs forbidden:* 404 for a project the caller cannot see; 403 for a visible project without the key — **except the import**, where a missing write key is a `REFUSE` row, never a 403 (blueprint §3, DEC-1 R7).
- *Referential integrity (exists → visible → valid state):* `suiteId` on the import → field error `suiteId` with `CaseEdit`'s existing sentence for a suite outside the project's live set; a step `id` unknown to the case → field error `steps` (unchanged).
- *State transitions:* an import never moves `lifecycle_status`. `APPROVED`/`DEPRECATED` under `UPDATE` is a **`SKIP` row**, not a 409 — a deliberate departure from V5.4, because the request is a batch whose policy is *partial with per-row outcomes* (catalog §5 "batch requests state their policy").
- *Uniqueness → V6.1:* `test_case_automation_key_idx` — an `APPLY` that loses a race on the key rolls back the whole transaction and answers **409 `duplicate-automation-key`** naming the key; `condition_imported_key_idx` — same treatment, `duplicate-imported-key`; `uq_suite_key` — cannot fire (the service generates the key).
- *Batch policy:* `APPLY` is **all-or-nothing at the transaction level** (a constraint violation rolls everything back) and **partial at the plan level** (`REFUSE`/`SKIP` rows do not stop `CREATE`/`UPDATE` rows). Both are stated in the endpoint description.
- *Rate limiting:* the existing per-token limiter applies (§ rate-limiting plan); the import is one request regardless of file count, and no tighter limit is added.
- *Request size:* V1.4 → 413 at 12 MB (`application.yml:80`); the import's own 8 MiB content cap is a 400 field error on `files`, checked before parsing.

## 5. Endpoints

### 5.1 POST `/projects/{projectId}/cases/parse-gherkin` — Parse Gherkin

**Purpose:** turn pasted text into the rows the editor shows; a pure function.
**Auth:** `case:read` on a visible project (the same gate as `GET /cases`).
**Idempotency:** pure; nothing persists.
**Request:** `text/plain; charset=utf-8`, ≤ 256 KiB. Accepted shapes: a bare step list; a `Scenario:` block; a `Feature:` with a `Background:` and **exactly one** `Scenario:`.
**Response 200:** `GherkinParseResult` (§3).

**Errors:** standard set, plus —
| Status | Condition | `type` slug | `errors` key |
|---|---|---|---|
| 400 | syntax error | `gherkin-syntax` | `text`: `"Line 4, column 3: expected a step"` — the parser's line and column, its message verbatim |
| 400 | contains a `Scenario Outline` | `gherkin-outline` | `text`: `"Line 12: Scenario Outline is not supported yet — expand the examples into separate scenarios"` |
| 400 | more than one `Scenario:` | `gherkin-many-scenarios` | `text`: `"This text holds 3 scenarios; paste one, or import the file"` |
| 400 | no steps at all | `gherkin-empty` | `text`: `"No steps found"` |
| 413 | body over 256 KiB | `payload-too-large` | — |
| 415 | `Content-Type` is not `text/plain` | `unsupported-media-type` | — |

### 5.2 GET `/projects/{projectId}/cases/{caseId}/feature` — Render as `.feature`

**Purpose:** the canonical text view of one case's rows.
**Auth:** `case:read`.
**Response 200:** `text/plain; charset=utf-8`, `Content-Disposition: inline; filename="<caseKey>.feature"`. Rendering rules (the renderer is the inverse of §5.1 and the round-trip test asserts it):
- `@tag` lines from `case_automation_tag`, then `Feature: <suite name or the case key when unfiled>`.
- `Background:` when the case has any `PRE` condition: the conditions in `position` order, **one or more lines each** — an import-owned condition is one step (plus its block lines); a human-authored library condition with a multi-line body renders each of its lines in sequence. Every `PRE` attachment renders, owned or not.
- `POST` conditions have no Gherkin construct: they render as a **trailing comment block** after the last step — `# postconditions:` then `#   <line>` per body line per condition in `position` order — so nothing on the case is lost in the text. The parser treats them as comments (they do not come back as rows or as `background`), which the round-trip test states explicitly.
- Suite-level export (future, M4) folds identical leading `PRE` lists across a suite's cases into one `Background:`; out of scope here, recorded so the per-case rendering stays compatible with it.
- `Scenario: <title>` then the `objective` as the description block.
- One line per step: `<Keyword> <action>`; a null keyword renders as `*` (Gherkin's format-neutral keyword).
- `docString` → an indented `"""` block; `dataTable` → indented `|`-rows, cells escaped per the Gherkin spec (`\|`, `\\`, `\n`).
- `expectedResult` → a following comment line `  # expected: <text>`; `testData` → `  # data: <text>`. Comments are what the parser re-reads (§5.1), so nothing is lost.
- A case with **zero rows** and a non-null `gherkinSource` (not yet backfilled) renders the stored blob verbatim, so this endpoint is never empty for a legacy case.

**Errors:** standard set only. A `STRUCTURED` case renders (with `*`), so there is no 409.

### 5.3 Case create / update / get — changed schemas

Contract deltas are §3. Behaviour deltas in `CaseEdit.validate` (blueprint DEC-2, collected as field errors on `steps`):

| Rule | Lens | Message |
|---|---|---|
| R1 | `GHERKIN` | `"Step 3 needs a keyword."` |
| R2 | `GHERKIN` | `"The first step cannot be And/But."` |
| R3 | either | `"Step 2 has more than one data field — keep test data, a doc string, or a table."` |
| R4 | either | unchanged `ProcedureRule.REQUIRED` behaviour; the `GHERKIN`-branch sentence `"Write the scenario, or switch to structured steps."` (`CaseEdit.java:327-328`) is **deleted** and the `STRUCTURED` sentence `"Add at least one step, or switch to Gherkin."` (`:340-341`) becomes `"Add at least one step."` |

`GET` serves `gherkinSource` only for a case with zero rows (§3). `CaseCompleteness.recomputeIncomplete` drops its `GHERKIN` branch: *says what should happen* = `expected_outcome` non-empty **or** any step with non-empty `expected_result` **or** any step with `keyword = 'THEN'`.

### 5.4 POST `/projects/{projectId}/feature-imports` — Import feature files

**Purpose:** plan (and in `APPLY`, execute) the mapping in blueprint DEC-1 over a set of files.
**Auth:** `case:read` on a visible project to call at all. Per row: `CREATE` needs `case:create` (+ `suite:manage` if its suite is `CREATE`, + `library:manage` if its feature's condition is `CREATE`/`UPDATE`); `UPDATE` needs `case:edit` (+ `library:manage` likewise). A row the caller cannot perform is `REFUSE` with the key named.
**Idempotency:** none by header. `APPLY` recomputes the plan in the transaction; a replay finds every scenario by `automationKey` (`SKIP` or an identical `UPDATE` that mints no version because the content hash is unchanged — `CaseVersions` digest) and every condition by `imported_key` (`UNCHANGED`), and writes nothing. The response says so row by row.
**Transaction:** one, for the whole `APPLY`; in order: conditions → suites → cases (create/update through `CaseCreateService`/`CaseUpdateService`, which write `test_case_version` and `audit_event` as any edit does) → provenance columns. Nothing half-imported.

**Request body:** `FeatureImportRequest` (§3).

**Response 200:** `FeatureImportPlan` (§3), identical shape in both modes. `reason` sentences (fixed — clients may match on them; `type`-style stability is owed to these strings):

| Situation | `reason` |
|---|---|
| parser error (whole file) | the parser's `"Line n, column m: …"` |
| `Scenario Outline` | `"Scenario Outline is not supported yet"` |
| exists, `SKIP` | `"exists (SKIP)"` |
| exists, `UPDATE`, scenario text identical to `imported_source` | `"unchanged since import"` (action `SKIP`; the one reader of `imported_source`) |
| exists, `UPDATE`, `APPROVED` / `DEPRECATED` | `"approved — not touched by UPDATE"` / `"deprecated — not touched by UPDATE"` |
| key in another project | `"automationKey already used by TC-412 in project PAY"` |
| two suites match the feature name | `"two suites are named 'Orders' (SUI-3, SUI-9) — pass suiteId"` |
| caller lacks a key | `"needs case:create"` / `"needs suite:manage for suite 'Orders'"` / `"needs library:manage for the Background"` |
| condition title truncated | `"title truncated to 150 characters"` (on the `conditions[]` row; the scenario row proceeds) |
| caller lacks `library:manage` and the feature has a Background | `"needs library:manage for the Background"` on every scenario row of that feature (the whole scenario is `REFUSE`, not attached partially) |
| feature name empty | `"Feature has no name"` (whole file `REFUSE`) |

**Errors:** standard set, plus —
| Status | Condition | `type` slug |
|---|---|---|
| 400 | `files` empty, > 200, duplicate `path`, or total content > 8 MiB | `validation-failed`, `errors.files` |
| 400 | `suiteId` not a live suite of this project | `validation-failed`, `errors.suiteId` |
| 409 | `APPLY` lost a race on `test_case_automation_key_idx` or `condition_imported_key_idx` | `duplicate-automation-key` / `duplicate-imported-key` — whole request rolled back; `detail` names the key |

**Import semantics per row (from DEC-1, made concrete):**
- `CREATE`: `CaseCreateService` with `title` = scenario name, `objective` = scenario description (a `Rule:`'s text prefixed as `"Rule: <name>\n<rule description>\n\n"`), `stepFormat = GHERKIN`, `automationStatus = AUTOMATED`, `automationKey`, `priority`/`severity` = the project defaults the SPA's New Case form uses (`lib/caseDefaults.ts`), `suiteId` = the feature's suite, `steps` from the scenario, `preconditions` = [feature condition, rule condition?], `tags` = feature ∪ scenario tags; then `imported_*` set.
- `UPDATE`: first compare the scenario's text with `test_case.imported_source` — identical → `SKIP` `"unchanged since import"` (nothing written, no version); otherwise `CaseUpdateService` with the same fields; **step ids preserved** by matching `(keyword, action)` in order against the stored rows — a matched row keeps its id (and every `step_result` on it), an unmatched stored row is deleted, a new one inserted; `preconditions` replaced with the file's set; `title`, `objective`, `tags` replaced; `lifecycle_status`, `priority`, `severity`, classification **untouched**.
- Conditions (Q7): **one per distinct Background step** in the request — `ConditionService.create` with `title` = the step text with keyword, `type = PRE`, `body` = the step line plus its block lines, `imported_key` = the text hash (data-model §3); `UNCHANGED` when the key already exists. The same step text in two files is one condition. A `Rule:`'s Background steps follow the feature's in the attachment order.
- Attachments: `preconditions` = the file's owned set in Background order; on `UPDATE`, followed by the case's existing **non-owned** `PRE` attachments in their current relative order (ownership = the condition's `imported_key` is non-null). `postconditions` is **never sent** on `UPDATE` (null = leave alone) — a case's POST conditions survive any re-import untouched; no POST condition is ever created by the import.
- A Background step removed from the file: its attachment is removed from the cases the import updates; the library condition itself stays (a human retires it).
- Orphans: cases filed in the target suite(s) whose `automation_key` is non-null and absent from the files. Reported; never modified.

## 6. Pagination, filtering & sorting

No new list endpoint. `FeatureImportPlan.scenarios[]` is ordered **by file order, then line**, so a client can render it deterministically without paging (≤ 200 files × the scenarios in them; a request over 500 scenarios is refused — `errors.files`: `"more than 500 scenarios in one request"` — the same cap `POST /cases/bulk` uses).

## 7. Versioning & deprecation

Path-versioned `/api/v1`, unchanged. Two deprecations in this initiative, both **announced in the schema** (`deprecated: true` + description) rather than by headers, because this API's consumers are generated from `openapi.yaml`:
- `TestCase.gherkinSource` — read-only, served only for un-backfilled cases; **removed in `002-`**.
- `TestCaseInput.gherkinSource` — **removed now**. No client of ours sends it after T2 (blueprint Q6); a stray one is ignored by the binder.

`packages/` tarballs built before T2 that still send `gherkinSource` get it ignored and their case saved without a scenario — which is why T2 lands adjacent to T1 and `pack:release` runs in the same post-batch gate.

## 8. Outbound webhooks

None.

## 9. Consumers

| Consumer | Resources used | Notes |
|---|---|---|
| SPA — `features/cases/CaseEditorPage.tsx`, `caseSchema.ts` | create/update case (new step shape), parse-gherkin, `POST /conditions` (from the paste dialog) | T4 |
| SPA — `features/cases/CaseDetailPage.tsx` | get case (`importedFrom`, `gherkinSource` fallback), render `.feature` | T4 |
| SPA — `features/runs/ExecutionPage.tsx` | get execution (`docString`/`dataTable` on `stepResults[]`) | T4 |
| SPA — new `features/cases/FeatureImportPage.tsx` | feature-imports | T7 |
| `@tcms/client` catalogue → CLI `tcms case edit` / `tcms format`, MCP `tcms_shape` + the case tools | create/update case (new step shape) | T2 — must create and edit a Gherkin-lens case end to end |
| `CucumberReportParser` (`execution`) | none new — it already matches `automation_key`; imported cases are keyed for it | no change |
| `migrate-gherkin-source` (operator) | none — it calls the services directly, not the API | T5 |

## 10. Open questions

| # | Question | Blocks | Owner |
|---|---|---|---|
| A1 | The MCP/CLI have no **import** carrier in this initiative (blueprint D out-of-scope). Confirm that stays out until the SPA page has been driven once. | nothing | the human, before a `003-` |
| A2 | `dataTable` limits (200 × 50, 4 KiB cells) and the `docString` 64 KiB cap are my numbers, sized to keep a step a step. Overrule if a real table is larger. | nothing — they stand as assumptions | the human, before T1 |
