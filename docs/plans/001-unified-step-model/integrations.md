# Unified step model & `.feature` import — Integration Plan

**Date:** 2026-08-27 · **Written by:** `be-shared-integration-plan`
**Blueprint:** `./blueprint.md` · **API surface:** `./api-surface.md`

This initiative adds **one** dependency — an in-process parsing library — and gives an **existing** inbound integration (Cucumber JSON reports) something to match against. No new network path, no credential, no config key, no environment. `devops-engineer` is not routed to; §7 says so.

## 1. Dependency register

| # | System | What it does for us | Direction | Protocol | Criticality | Account owner | Status |
|---|---|---|---|---|---|---|---|
| X1 | `io.cucumber:gherkin` (Maven Central) | Parses `.feature` text to the Gherkin AST; the only Gherkin parser this repo will use (plan §6a rule 3) | outbound — a **compile-time library**, in-process, no network at runtime | Java API | **critical** for B, C, D (parse and render fail without it); **irrelevant** to A (the step model needs no parser) | the repo (`backend/build.gradle.kts`) | pin `27.0.0` `#inferred` from Maven Central at planning time — re-probed at T3 |
| X2 | Cucumber JSON reports posted by a team's CI | Already ingested by `POST /projects/{projectId}/ingestions` (`CucumberReportParser`); the import keys cases so those reports match | inbound (unchanged) | REST, `Idempotency-Key` (`IngestionController.java:119`) | **degraded** — the import works without any report ever arriving; a report is what makes the keys pay off | the team posting it | exists `#observed` |

## 2. Dependencies

### X1 — `io.cucumber:gherkin` (parser library)

**Purpose:** parse Gherkin text (paste, backfill, import) into an AST the `repository` module walks, and validate syntax with line/column errors.
**Direction:** library, in-process · **Criticality:** critical for B/C/D
**Docs:** `https://github.com/cucumber/gherkin` (the `java/` module) · **Version pinned to:** `27.0.0` — the pure parser artifact `io.cucumber:gherkin`, **not** `io.cucumber:cucumber-gherkin` (that is the Cucumber-JVM runtime and pulls the whole framework). Confirm at T3 with `./gradlew dependencies --configuration runtimeClasspath | grep cucumber` that exactly one `io.cucumber` artifact appears (plus its `messages` transitive).

**Auth** — none.
**Configuration** — none. No key, no property. Dialect is fixed to `en` for **rendering**; **parsing** honours a `# language:` header because the AST keywords are normalised (blueprint D mapping table).
**Environments** — identical everywhere; it is a jar.
**Limits & budget:** parse cost is linear in text size; the caps in `api-surface.md` (256 KiB paste, 1 MiB per file, 8 MiB per import request, 500 scenarios) bound it. No timeout is owed — parsing 8 MiB is sub-second on the target box.
**Resilience** — a parser exception is a **400 with line/column** (api-surface §5.1) or a `REFUSE` row (§5.4), never a 500: every `ParserException` is caught at the boundary of `GherkinScenarios.parse` and re-raised as the module's own typed error. An AST shape the walker does not expect (a new Gherkin construct after an upgrade) is a `REFUSE` row naming the line, not a crash — the walker is written over the *documented* node types (`Feature`, `Rule`, `Background`, `Scenario`, `Step`, `DocString`, `DataTable`, `Examples`, `Tag`, `Comment`) and refuses anything else.
**Data crossing the boundary:** the caller's text and nothing else. Stays in-process.
**Local dev & test** — nothing to fake: the real parser runs in every test. The **round-trip test** (`parse ∘ render ∘ parse` is identity on the rows) and a fixture `.feature` file with every construct (tags at both levels, `Background`, `Rule`, `DocString`, `DataTable`, an `Outline`, a `# language:` header, `*` steps, `# expected:`/`# data:` comments) are the contract with the library. A library **major** bump is its own task with that fixture as its gate.
**Licence:** MIT — no obligation beyond attribution in the jar's own manifest.
**Open questions:** none.

### X2 — Cucumber JSON reports (existing inbound)

**Purpose:** unchanged — CI posts a report; `CucumberReportParser.fullName()` builds `"<Feature name>#<Scenario name>"` (`CucumberReportParser.java:48`) and the ingestion matches it against `test_case.automation_key`.
**What this initiative changes:** nothing on the wire. The import writes exactly that string as the key, so the first report matches without an `automation-mappings` round.
**The contract between D and X2 that must not drift:** the key format is defined **once**, in `execution` (`fullName()`), and the import must call the same function rather than re-spell it. Because `repository` may not depend on `execution` (Modulith rules; `repository/package-info.java`), the format is lifted to `shared` as a one-method `AutomationKeys.cucumber(feature, scenario)` that both modules call — a **T6 file**. A test in `execution` asserts `fullName()` equals it on the fixture report.
**Resilience, auth, limits** — unchanged; owned by the ingestion slice (§M3).
**Open questions:** none.

## 3. Secrets checklist

| Secret | Store | Environments | Provisioned by | Needed by task | Rotation | Status |
|---|---|---|---|---|---|---|
| — | — | — | — | — | — | **None.** No credential is introduced |

## 4. Failure matrix

| System | If it is slow | If it is down | If it returns garbage | User-visible effect |
|---|---|---|---|---|
| X1 parser | cannot be — in-process, bounded input | cannot be — a missing jar fails the build, not the runtime | an unexpected AST node → `REFUSE` row / 400 naming the line; a thrown `ParserException` → 400 `gherkin-syntax` with line:col | the paste dialog shows the line; the import preview shows the file as refused; the backfill lists the case. Nothing is written |
| X2 Cucumber report | unchanged (ingestion) | unchanged — the import does not wait for one | unchanged — `unmatched` rows in the ingestion job | imported cases simply have no results yet |

## 5. Blocking unknowns

| # | Unknown | Owner | Needed by | Blocks tasks |
|---|---|---|---|---|
| — | none — nothing here needs a human to obtain anything | — | — | — |

The one human-gated step in the whole initiative is **operational, not provisioning**: running `migrate-gherkin-source` on each instance after T5 ships. It gates initiative `002-`, not any task here (blueprint Q2), and is recorded in `delivery-plan.md` §5.

## 6. Local development

A developer runs everything with nothing provisioned: `docker compose up --build` at the repo root (Postgres 17 + the backend image), `npm run dev` in `frontend/`. The parser is a jar; the import is driven from the SPA by dropping local files; the backfill is `docker compose run --rm backend migrate-gherkin-source --slug=acme --actor=ops@acme.test --dry-run` against the locally provisioned company. Tests boot a real Postgres via Testcontainers and the real parser — **on `shanks`**, per the standing directive; nothing here changes that.

## 7. Routed to `devops-engineer`

| Need | Why |
|---|---|
| none | No secret, no network path, no environment, no image change beyond a jar already resolved at build time. The `shanks` deployment picks the new image up as any slice does |
