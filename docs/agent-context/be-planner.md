<!-- orientation-cache v1 -->
# Agent orientation — be-planner

**Agent:** be-planner
**Written:** 2026-08-27
**Last verified:** 2026-08-28 (T8 of `001-unified-step-model` — **the initiative is EXECUTED, T1–T8, and
UNGATED and UNCOMMITTED**; handoff §255)
**Baseline commit:** `60233010` + T1–T8's uncommitted work — **trust the body over this header**
**Fingerprint:** backend/build.gradle.kts@627e4ffb (**stale — T3 added `io.cucumber:gherkin` 42.0.1 + 2 pins**), backend/gradle/wrapper/gradle-wrapper.properties@cc8f5de0, frontend/package.json@270af2a3, packages/package.json@80be6512, backend/src/main/resources/db/migration/(head **V69**; `V70` reserved for `002-`)

> Written and maintained by the `be-planner` agent per `ep-agent-orientation-cache`.
> A map of what this code **is** — never findings (`docs/audits/`), plans (`docs/plans/`), or specs (`docs/specs/`).
> Lines are `#confirmed` unless tagged `#inferred` or `#volatile`. Volatile lines are always re-probed.
> The deep maps are the peers' caches — `be-java.md` (backend seams, per-slice), `fe-react.md` (SPA), `tooling-engineer.md` (`packages/`). This file records only what a **planner** needs: the version lane, the gates a DoD must cite, the layout at module altitude, the conventions a plan must respect, and the plan-artifact inventory.

## Version lane

- Java 21 toolchain (`backend/build.gradle.kts:12`), Spring Boot 3.5.6 + Spring Modulith, Gradle, `-Werror` (`CLAUDE.md` Backend)
- PostgreSQL 17 (`compose.yaml:32`), Flyway head **`V69__step_data_and_import_provenance.sql`** (§255 T1) → **`V70` is RESERVED for initiative `002-`, do not claim it**; `ddl-auto: validate`, `baseline-on-migrate: false`; forced RLS on every company-owned table
- Frontend: Vite 8, React 19.2, TS strict, MUI 9.2, TanStack Query, React Router 7 (`frontend/package.json`)
- `packages/`: `@tcms/client` / `@tcms/cli` / `@tcms/mcp`, Node ≥ 22.12 (`packages/package.json:8`), carriers rendered from the §254 shape catalogue (`packages/client/src/core/shapes.ts`)
- Auth: JWT session; permission **keys** checked in services (constants like `CaseCreateService.java:89`), 55-key catalogue in `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A3; no wildcard, no role-name checks

## Build, run & test

- Gates (the DoD lines every task cites): backend `cd backend && ./gradlew build` · frontend `npm run build && npm run lint && npm run test:run` · packages `npm run build && npm run lint --workspaces && npm run test:run --workspaces && npm run pack:release -- --version 0.0.0 --out /tmp/rel`
- **Every gate runs on `shanks` via `~/bin/tcms-remote <backend|frontend|packages> <cmd>`, never locally, and never by an implementing subagent** — one post-batch pass (standing directive, `CLAUDE.md`; memory `subagents-never-run-tests`)
- `openapi.yaml` change ⇒ regenerate **both** `schema.d.ts` copies (`frontend: npm run gen:api`; `packages/client: npm run gen:api`); `schemaFreshness.test.ts` fails otherwise
- Browser + mutation checks are **deferred to queues** (§65): `docs/verification/frontend-browser-checks.md` (13 ordered lanes, stable ids) and `docs/verification/backend-mutation-checks.md` (pass CLOSED 2026-08-23; rows still appended)
- CI (`ci.yml`) runs `./gradlew build -x test`; nightly `tests.yml` runs the full build on `shanks`

## Layout

- `backend/src/main/java/com/tcms/{tenancy,identity,project,storage,repository,execution,documentation,reporting,work,shared,provisioning,api}` — Modulith modules as packages; cross-module FKs only toward `tenancy/identity/project/storage`, else domain events (ArchUnit)
- `backend/src/main/resources/db/migration/` — Flyway, `V1` = 64 tables / 56 RLS policies; one version per slice, pre-assigned when lanes run concurrently (`docs/specs/parallel-work-rules.md`)
- `frontend/src/features/<area>/` (cases, runs, plans, review, …); `src/api/queries.ts` is the single data module; `src/lib/{labels,format,permissions}.ts` the shared display layer
- `packages/{client,cli,mcp}/src` — one workspace root; `client/src/core/shapes.ts` is the catalogue the CLI/MCP carriers render from
- `docs/plans/` — date-prefixed slice plans (2026-07-30 … 2026-08-27); **`NNN-` folders start at `001-unified-step-model/`** (this agent's convention); `docs/handoff/NN-slug.md` per shipped slice (head **§255**), indexed by `docs/HANDOFF.md`; `docs/specs/openapi.yaml` is the contract; `docs/specs/2026-07-30-tcms-erd.md` §0.1 the tenancy rule

## Seams to reuse

- **Case writes have one assembler**: `repository/CaseEdit.validate` (`CaseEdit.java:~300-400`) → `CaseSteps` (update-by-id, never replace; `CaseSteps.java:15-33`) → `CaseVersions` (snapshot per save, digest-deduplicated). `ProcedureRule.REQUIRED|DEFERRED` (`CaseEdit.java:144-210`) decides when a procedure must be stated. A plan that creates or updates cases routes through `CaseCreateService` / `CaseUpdateService`; a second assembler is the defect §64/§200 exist to prevent
- **`test_step`** (`V1:593-612`, `V69`): `id` stable (step_result FKs it), `keyword` nullable enum-checked, `docstring TEXT` + `datatable JSONB` are **mapped and written since §255 T1** (`@JdbcTypeCode(SqlTypes.JSON)` on `List<List<String>>` — **never** an `AttributeConverter`, §255 D1) under a `num_nonnulls(test_data, docstring, datatable) <= 1` check; `shared_step_id` is dead since §239 and is `002-`'s to drop
- **Execution renders the pinned version snapshot**, not live `test_step` (`execution/ExecutionViews.java:128-160`, `CaseFacts.PinnedStep`); anything a plan wants a tester to see must be in a version
- **Conditions are reference-only since §95** (`openapi.yaml:9806`, `CaseConditionInput.required: [conditionId]`); `case_condition.inline_body` (`V1:621`) is dead DDL
- **Idempotency in this repo** = unique `idempotency_key` on a **created row** (`ingestion_job` `V1:1349`, `test_case` `V11`, suites via `CaseController.IDEMPOTENCY_KEY`); a write that creates no single row uses determinism instead (§245)
- **Operator CLI verbs** are `ApplicationRunner`s selected by `args.getNonOptionArgs()`; four are in `provisioning/` (`ExportCompanyCommand.java:58`, `ProvisionCompanyCommand`, `IssueInvitationCommand`, `GrantInstanceAdminCommand`) and the **fifth is in `com.tcms`, the composition root** — `MigrateGherkinSourceCommand`, because its work belongs in `repository` and `provisioning → repository` is refused by **two** rules (`ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation` *and* `ModuleStructureTest.declarationsNameOnlyTheFoundationModules`); §255 **D11**, and `OperatorCommands.MIGRATE_GHERKIN_SOURCE` is the one PUBLIC verb constant. **A plan that adds a verb needing a non-foundation module takes the root, not an exemption.** A company-scoped verb opens `CompanyContext` **outside** the transaction (`ExportCompanyCommand.java:134`, §246)
- **Suites**: `uq_suite_key (company_id, key)` only — `suite.name` is not unique (`V1:421-442`); `SuiteWriteService.create` takes an `Idempotency-Key` (`SuiteController.java:129-140`)
- **Automation keys**: `test_case_automation_key_idx` unique per company (`V1:542`); `CucumberReportParser.fullName()` = `"<Feature>#<Scenario>"` (`CucumberReportParser.java:48`); `CaseEdit` refuses a taken key (`CaseEdit.java:365`)
- **A case carries N PRE and N POST conditions**: `case_condition (kind PRE|POST, position)` + `case_condition_case_idx` (`V1:614-630`); `CaseUpdateRequest.preconditions/postconditions` (`:107-108`, null = leave alone, [] = clear); `TestCase.preconditions[]/postconditions[]` (`openapi.yaml:9269-9272`); editor `ConditionsPanel.tsx:97-101` two panels
- **Conditions are reference-only** and library writes need `library:manage` via `ProjectService.writeGranted` (`ConditionService.java:31,65,196,247`); `ConditionWriteRequest(title, type, body: List<String>)`; title ≤ 150 (`openapi.yaml:9766`)
- **`test_case.search_vector` is a GENERATED column** whose expression reads `gherkin_source` (`V2:70-76`) — a drop of that column must re-create it
- **Contract-enum pairing**: `backend/src/test/java/com/tcms/contract/ContractEnumTest.java` `OWNERS` map — a new enum on the contract needs its server pairing listed or the build reddens
- **Error envelope**: `ProblemDetail`/`api.Problem` unified as `problem+json` (§162); refusals key field errors by the input schema's property name (`openapi.yaml:7927`)
- **Verification queue row shape**: stable id quoted from the handoff §, lane by *state needed*, fixture named from the pass charter (`frontend-browser-checks.md` §Run order)

## Conventions observed

- Work is **sequential**; one subagent at a time; a concurrent backend lane needs its own worktree (`CLAUDE.md` "Running work in parallel")
- Commit per phase after its gate; stage only touched paths; push after each lane; never branch per task
- A schema/catalogue-wide invariant is a **computed** check (`SchemaConventionTest`, `ContractEnumTest`, `shapesContract.test.ts`, `matrixCopies.test.ts`) — a plan that adds an enum, a table or a catalogue field names the check it must satisfy
- Superseded decisions are struck through in place with "→ superseded", never deleted
- Every slice ends with a `docs/handoff/NNN-slug.md` + one index row, and the agent caches updated **in the same commit**
- Permission prose like "quarantine only" for Automation Service is **not mechanised** (`CaseCreateService.java:44-57`) — plans must not invent a qualifier for it

## Plan artifacts

- `docs/plans/001-unified-step-model/` — **EXECUTED. Blueprint approved 2026-08-27** (decisions in §10a); full detail pack written the same day: `data-model.md` (V69 only), `api-surface.md` (+ `validation-catalog.md` copy), `integrations.md`, `delivery-plan.md` (T1–T8, streams `backend`/`packages`/`spa`/`docs`), `HANDOFF.md`, five `-explained.md` companions. Source proposal: `docs/plans/2026-08-27-unified-step-model.md`, now marked executed with **its two wrong premises named on it** (inline conditions; the pinned snapshot). **`NNN` high-water mark: 001. Task frontier: T8 — every task is done; the initiative is complete and the plan is closed.** Record: `docs/handoff/255-one-step-model-and-the-import.md`. Amended 2026-08-27 (Q7 → **AM1**: one library PRE condition per Background step, text-hash `imported_key`, POST never touched by import; `delivery-plan.md` §8). OQ1–OQ3 all answered in-flight, **OQ1 with an option it did not offer** (D11). Flyway: T1 took `V69` and **T4–T7 took none** (D13). **✅ GATED GREEN on `shanks` 2026-08-28 (backend 3408/0, frontend 1833/1833 over 132 files, packages 393, lint 0/0, `pack:release` verified) and ALL 116 MUTATION ROWS EXECUTED — 7 browser rows still owed. UNCOMMITTED.** Reaching green cost **nine fixes**: two stacks would not compile and 31 backend arms failed, and **three of the nine were in shipped code**. The mutation pass then found **four rows whose named guard does not exist** (`BF-M1`/`BF-M2`, `FI-M20`, `FI-M31`, `FI-M35`). A follow-up **`002-`** (contraction: drop `gherkin_source` + re-create `V2`'s `search_vector` without it, `shared_step_id`, `case_condition.inline_body`; remove `TestCase.gherkinSource` from the contract; retire the SPA fallback) is named in `delivery-plan.md` §5, holds `V70`, and is gated on **`migrate-gherkin-source` having been run on every instance with a clean report** — an operational precondition no task can satisfy; the human opens it
- **What the 001 pack taught this agent, for the next one:** (1) a task's *file list* is a hypothesis — four of the twenty deviations are a named file that was not the right one (D3, D7, D9, D19), and two are a file the plan said to create that already existed or must not exist (D12, D13); state the seam a task must reach, not only the path. (2) An **inferred dependency version** is `#volatile` and re-probing is the executor's job (D5). (3) A plan that assumes a **persona** must check the roles spec §A3 rows actually seed it — D15 cost a hand-seeded JDBC role and a browser row that may not be drivable at all. (4) A migration split across two tasks "to keep a rule quiet" is the tail wagging the schema (D13). (5) The **no-gates directive changes what a plan can promise**: a criterion that can only be met by watching something go red (a policy count literal, a lint warning, an exemption map that flips) is one nobody in the batch can observe — say so in the task rather than in the retrospective (D19)
- Executors this repo binds: `be-java`, `fe-react`, and **`tooling-engineer`** (the `tooling` plugin's agent, decided at the 001 gate) for `packages/`
- Predecessor per-slice plans are date-prefixed and executed by `be-java`/`fe-react` directly; none carries a `delivery-plan.md`/`HANDOFF.md` in this agent's format

## Known unknowns

- Unknown-field handling is Spring Boot's Jackson default (ignore) — no `FAIL_ON_UNKNOWN_PROPERTIES` setting or `@JsonIgnoreProperties` in `backend/src` `#confirmed` by grep 2026-08-27; re-grep if a `Jackson2ObjectMapperBuilderCustomizer` ever appears
- ~~`io.cucumber:gherkin` pin (27.0.0 at planning time) `#volatile` — re-probe at task time~~ → **resolved at T3: `42.0.1`**, fifteen majors past the inferred version, with `io.cucumber:messages` **34.2.1** and `org.jspecify:jspecify` **1.0.1** pinned beside it because gherkin declares both as version **ranges** (§255 D5). The lesson for a plan: an inferred dependency version is `#volatile` and the re-probe is the task's, not the plan's
- Green-on-arrival not checked this run (planning only; gates run on `shanks`, not here)
- `docs/business-model/` does not exist — no BA KB to link `FLOW-`/`DEC-` ids to; models live inline in the blueprint

## Session log

- 2026-08-28 · GATE+MUTATE · the post-batch `shanks` pass and all 116 §255 mutation rows, run in-session at the user's direction (deviation **D21**). Nine fixes to reach green; 99 of 116 rows behaved as written. **The lesson for a plan: a mutation row written by an agent that has not run the suite is a HYPOTHESIS — 17 of 116 were wrong about the suite they described, and four named a guard that does not exist.**
- 2026-08-28 · VERIFY · T8 of `001-unified-step-model` executed by `be-java` (docs only, no gate owed and none run): handoff §255 + index row, §6a's two strike-throughs, the proposal marked executed, `CLAUDE.md`'s fifth operator verb, this cache. Initiative closed. **Finding recorded, not fixed:** `docs/agent-context/tooling-engineer.md` carries a current `§255 T2` header and six inline references but **no `## §255` section of its own** — T2's to write, in T2's commit
- 2026-08-27 · AMEND · Q7 (N pre/post conditions) applied across the 001 pack; four companions regenerated; N-pre/N-post seam added
- 2026-08-27 · FULL · first orientation; DETAIL run for `001-unified-step-model` — blueprint written and approved the same day, detail pack + companions written, Step 7 clean; cache created
