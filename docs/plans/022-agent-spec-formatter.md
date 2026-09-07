# 022 — Agent-facing processes and the specification formatter

**Date:** 2026-09-07 · **Design:** [`docs/specs/2026-09-07-agent-specification-formatter.md`](../specs/2026-09-07-agent-specification-formatter.md) (read it first; §2–§5 are the spec, §7 is this table's source)

**Goal:** an agent connected over MCP or the CLI can take a client specification, write it as a page
in TCMS spec markdown, file proposals that name the blocks they verify, write a plan that adopts the
page, and have every step described to it by the server — with the links written when a person accepts.

| # | Stack | Agent | Deliverable | Depends on |
|---|---|---|---|---|
| T1 | contract | `be-java` | `AiProposalInput.verifies` / `AiProposal.verifies` / `ProposalLinkInput` in `openapi.yaml`, both generated copies, fixtures, `contractPairing` green | — |
| T2 | `be` | `be-java` | `V14`, the column, validation at accept, the event, the `documentation` handler that writes the links, ERD row, tests | T1 |
| T3a | `ts` | `tooling-engineer` | `@tcms/client` core: `core/markdown.ts`, `core/processes.ts`, the plan shape's six fields, `verifies` on the case shape | T1 |
| T3b | `ts` | `tooling-engineer` | MCP and CLI arms, `tcms_process`, prompts, `tcms://process/*`, `tcms spec import`, surface spec rows | T3a |
| T4 | `fe` + docs | `fe-react` | `ProposalPage` shows the claimed requirements; help guide; `features.md`; close-out | T3b |

Sequential. One agent at a time. Every gate on shanks through `~/bin/tcms-remote`; never a local
`npm run build` / `./gradlew build`.

## Prompts — one per task

### Common preamble (every prompt starts here)

Read, in this order: `CLAUDE.md`; `docs/specs/2026-09-07-agent-specification-formatter.md`;
`docs/specs/assets/spec-formatter/README.md` and the two `*.page.md` beside it; then only the files
your task names. Work on the current branch (`git rev-parse --abbrev-ref HEAD`), never create one.
Gates run on shanks: `~/bin/tcms-remote <subdir> <command>` (absolute path; it rsyncs the working
tree first). Read `docs/specs/shanks-runner-details.md` §"Five things that bite" — a backend
`BUILD FAILED` with `Could not write XML test results` and zero assertion failures is the fd limit;
re-run with `ulimit -n 65535;` prefixed. Do not spawn a second agent. When green, commit **only the
files you touched** (`git add <paths>`), message `feat(<area>): …`, ending with
`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Do not push. If you land something
different from what the design predicted, rewrite the prompts below yours in this file, in place.
Finish with: what landed, the gate output's last lines, and what is built but unverified.

### T1 — the contract

Stack: contract (`docs/specs/openapi.yaml` + both generated copies). Skill: `be-java`'s contract
conventions; no Java in this task.

1. In `openapi.yaml` add `ProposalLinkInput` (`required: [pageId, blockId]`; `pageId` uuid, `blockId`
   uuid, `relationship` enum `VERIFIES | DERIVED_FROM | REFERENCES` default `VERIFIES`) with a
   description that states the decision and its reason (design §5: stored with the proposal,
   validated at accept, written through the ordinary link path when a person accepts).
2. `AiProposalInput.verifies`: array of `ProposalLinkInput`, optional, absent = `[]`.
   `AiProposal.verifies`: same array, **required and never null** — a proposal filed before the field
   existed serves `[]`; add it to `AiProposal.required`. `AiProposalSummary` does not carry it.
3. On `POST …/ai-proposals/{proposalId}/accept` document the new `400`: a `verifies[n]` whose block
   is gone or belongs to another page of this project is a field error keyed `verifies[n].blockId`,
   in the same paragraph that already explains a deleted suite.
4. `cd frontend && npm run gen:api`; `cd packages/client && npm run gen:api`. Both copies, one commit.
5. Fixtures: `frontend/src/test/fixtures.ts` and `packages/client/src/test/fixtures.ts` — every
   `AiProposal` fixture gains `verifies: []`. `packages/client/src/test/shapesContract.test.ts` has an
   `UNOFFERED` map of contract fields no shape offers yet — add `AiProposalInput.verifies` there with a
   comment naming T3a as the task that removes it (015 T1 did the same for `IssueInput.agentEligible`).
   `contractBodies.ts` has a region byte-identical across both copies; if you touch it, touch both.
6. Gates: `~/bin/tcms-remote packages 'npm run build'`, `~/bin/tcms-remote packages 'npm run test:run --workspaces'`
   (this is what runs `schemaFreshness`, `contractPairing`, `citedFiles`), `~/bin/tcms-remote frontend 'npm run build'`,
   `~/bin/tcms-remote frontend 'npm run test:smoke'`.
7. Commit: `feat(contract): a proposal names the blocks it verifies`.

Traps: `required` does not descend — the new schema and any inline object need their own array.
Locate anchors by content, not line number.

### T2 — the backend

Stack: `be`. Skills: `be-java`, `be-java-database-code`, `data-migrations`.

Read: `backend/src/main/java/com/tcms/documentation/package-info.java` (the module rule),
`repository/AiProposal.java`, `AiProposalService.java`, `AiProposalDecisionService.java` (`accept`),
`AiProposalInput.java`, `AiProposalView.java`, `repository/DocumentationFacts.java` (the native-SQL
facts pattern, already in this package), `documentation/BlockLinkService.java`,
`tenancy/CompanyScopedEvent.java`, `tenancy/OutboxDispatcher.java`, one existing `OutboxHandler`
(`repository/HealthRecomputeOnLinksChanged.java`) and the `OutboxEvents` names class,
`backend/src/test/java/com/tcms/repository/AiProposalEndpointTest.java`.

**T1 left the backend red, deliberately, and clearing it is part of this task.** Two checks in
`backend/src/test/java/com/tcms/contract/ContractConventionTest.java` name the new contract members
and nothing serves them yet: `everyObjectSchemaIsPairedOrExempt` (the new `ProposalLinkInput` schema
has no record) and `pairedSchemasAndRecordsNameTheSameMembers` (`AiProposalInput.verifies` and
`AiProposal.verifies` are members `AiProposalInput`/`AiProposalView` do not declare). Step 2 below
fixes the second; the first needs a line in that file's `RESPONSE_RECORDS` map beside
`Map.entry("AiProposalInput", …)` — `Map.entry("ProposalLinkInput", List.of(com.tcms.repository.ProposalLink.class))`,
**one** record for both directions, since input and view carry the same three fields. Anything else
red in those two suites is yours.

1. `ls backend/src/main/resources/db/migration/` — the next number is one above the highest you see
   (`V13` is the head as of 2026-09-07, so `V14`). Add `V14__proposal_verifies.sql`: `ALTER TABLE ai_proposal ADD COLUMN
   verifies jsonb;` with a `COMMENT ON COLUMN` stating why it is a column and not part of `payload`
   (design §5). No RLS change — the table already has its policy.
2. Entity: `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "verifies", updatable = false) private String verifies;`
   beside `grounding`. Input and view carry `List<ProposalLink>` (`pageId`, `blockId`, `relationship`);
   the view serves `[]` for a null column, never null — `AiProposal.verifies` is in the contract's
   `required` array, so a null there is a body no client's generated type admits.
3. File: store as given, **unvalidated** — the review boundary's rule; only shape-check that each
   entry has both ids.
4. Accept, in `AiProposalDecisionService.accept`, **before** the case is created: resolve every target
   through a new query on `DocumentationFacts` (native SQL over `doc_block` joined to `doc_page`,
   project-scoped) and fail with the ordinary field-error 400 keyed `verifies[n].blockId` for any
   miss. Then create the case as today, then publish `repository.proposal_accepted`
   (`CompanyScopedEvent`, through the outbox like every other cross-module event) carrying
   `caseId` and the validated targets.
5. `documentation`: an `OutboxHandler` (`LinkAcceptedProposal`) that calls `BlockLinkService` once per
   target with `sourceType = TEST_CASE`. `BlockLinkService` already raises `links_changed`, which
   refreshes the case's `relatedDocuments` mirror — do not add a second path.
6. ERD (`docs/specs/2026-07-30-tcms-erd.md`): ~~the `ai_proposal` row gains `verifies`, with the
   reason.~~ **There is no `ai_proposal` row** — that document is the 2026-07-30 revision and
   predates the generation slice entirely, so §4 gained a whole `### ai_proposal` subsection
   (columns, then `verifies`' own argument) rather than a cell.
7. Tests: extend `AiProposalEndpointTest` — file with `verifies`, read it back, accept, then read the
   block and assert the link is there with `sourceType TEST_CASE` and the case key; file with a
   foreign block id and assert `400` on `verifies[0].blockId`; a proposal filed with no `verifies`
   accepts exactly as before. ArchUnit and `SchemaConventionTest` must stay green untouched.
8. Gate: `~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew build"`. Then
   `~/bin/tcms-remote frontend 'npm run build'` to prove the contract did not drift.
9. Commit: `feat(proposals): verifies is stored, checked on accept, and linked by documentation`.

Traps: `@Transactional(readOnly = true)` on any new repository interface, or RLS returns zero rows
silently. Company context in the handler comes from the event, never from a parameter. The
handler must be idempotent — linking twice answers the existing link, which `BlockLinkService`
already guarantees; lean on it.

### T3a — the client core

Stack: `ts`. Skill: `tooling-engineer` (`tool-capability-contract`).

Read: `packages/client/src/core/shapes.ts` (all of it — `FieldSpec`, `EntityShape`, `guideFor`,
`templateFor`, `violationsOfShape`), `core/rewrite.ts`, `core/proposal.ts`, `core/index.ts` or the
barrel that exports the core, `packages/client/src/test/shapesContract.test.ts`, `corpus.ts`.

1. `packages/client/src/core/markdown.ts`, pure, no I/O:
   - `blocksFromMarkdown(text, opts: { mode: 'create' | 'edit', current?: DocBlock[], dropLinked?: boolean })`
     → `{ blocks: DocBlockInput[], labels: Map<label, index>, warnings: string[] }` or throws a
     `MarkdownError` naming the line. Grammar exactly as design §2.2: headings 1–6; paragraphs; lists
     with 2-space nesting → `level`; fences → `code` (info string dropped); `Table: caption` line
     immediately before a GFM pipe table → `table` with the separator row dropped, the contract's
     bounds checked (≤100 rows, ≤12 columns, ≤500 chars, equal row lengths); `![alt](attachment:<uuid>)`
     → `image` on edit only; `{#…}` trailing anchor — UUID shape tested first and becomes `id`,
     otherwise a label (`[a-z0-9][a-z0-9-]*`, unique). Blockquotes, HTML, thematic breaks → refuse.
     Inline emphasis → pass through with a warning.
   - On `mode: 'edit'` with `current`: any current block with non-empty `links` whose id is absent
     from the new set → throw naming the block's text and `links[].sourceKey`, unless `dropLinked`.
   - `markdownFromBlocks(blocks: DocBlock[])` → text where every block ends with `{#<id>}`, tables
     get a `Table:` caption line, and `blocksFromMarkdown(markdownFromBlocks(b), {mode:'edit', current:b})`
     reproduces `b`'s type/text/level/rows/id — write that as the round-trip test, over a corpus that
     includes both `docs/specs/assets/spec-formatter/*.page.md` parsed in `create` mode.
   - `anchorsFor(labels, pageResponse)` → `Record<label, blockId>` by index.
2. `core/processes.ts`: `PROCESSES` — the five in design §3.2 (`ingest-spec`, `cover-spec`,
   `plan-spec`, `revise-spec`, `report-spec`), each `{ name, title, summary, arguments[], steps[] }`
   where a step names the MCP tool, the CLI spelling, the check after it and the trap; `processGuideFor`
   renders Markdown like `guideFor`; a test pins that every tool name a step mentions exists in a
   list the test imports from `@tcms/mcp`'s registry **by name only** — if that import is circular,
   pin against a string list here and let T3b add the cross-check in `mcp`.
3. Shapes: **`CASE_SHAPE.verifies.relationship` is a closed three-value enum** (`VERIFIES` ·
   `DERIVED_FROM` · `REFERENCES`, absent means `VERIFIES`) — T2 made a fourth value a `400` at file
   time, so a shape that offered it as a free string would let a proposal be refused for a field the
   guide said was open. `PLAN_SHAPE` gains `scopeText`, `outOfScopeText`, `tags` (set), `entryCriteria` /
   `exitCriteria` (`object[]` with `metric` enum, `op` enum, `value`, `label`), `sections`
   (`object[]`: `name`, `cases`, `suites`, `tags`). `CASE_SHAPE` gains `verifies` (`object[]`:
   `page` key, `block` uuid, `relationship`) scoped to the `propose` action — it is wrapper-level, not
   in `TestCaseInput`; see how `verdict` is scoped and do the same. `DOC_PAGE_SHAPE` gains `markdown`
   (string, note: instead of `blocks`; the grammar in one paragraph; points at the process).
   Remove the `AiProposalInput.verifies` `UNOFFERED` entry T1 added; `shapesContract` will tell you
   what else it now expects.
4. Export everything from the package barrel.
5. Gates: `~/bin/tcms-remote packages 'npm run build'`, `'npm run lint --workspaces'`,
   `'npm run test:run --workspaces'`.
6. Commit: `feat(client): TCMS spec markdown, the process catalogue, and the plan shape in full`.

Traps: `flagOf` derives CLI flags from field names — record a `cli: { flag }` where the shipped
spelling differs. Do not add a YAML dependency; the bundle (T3b) is JSON.

### T3b — the adapters

Stack: `ts`. Skill: `tooling-engineer` (`tool-mcp-builder`, `tool-cli-builder`).

Read: T3a's commit; `packages/mcp/src/writes.ts` (`tcms_write_doc_page`, `tcms_write_case`,
`tcms_write_plan`), `reads.ts` (`tcms_open_doc_page`), `shape.ts`, `server.ts` (capabilities,
resources), `tool.ts`, `tools.ts`; `packages/cli/src/commands.ts`, `writes.ts` (`doc create`,
`case file`, `plan create/edit`), `reads.ts` (`doc show`, `format`); the conformance tests
(`writeConformance.test.ts`, `guideCopies.test.ts`, `server.test.ts`) in both;
`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` §3.0a, §3.3, §6.

MCP:
1. `tcms_write_doc_page`: `markdown` accepted instead of `blocks` on `create`/`edit` (both → refuse);
   `edit` reads the page first (it already does for `lockVersion`) and passes it as `current`;
   `dropLinked`; response carries `anchors`. New arm `adopt` (`doc:edit`, `kind: 'state'`):
   `{page, plan}` → `PUT /projects/{projectId}/plans/{planId}/document`, `{page, plan: null}` → `DELETE`.
2. `tcms_open_doc_page`: `as: 'blocks' | 'markdown'`, default `blocks`.
3. `tcms_write_case propose`: `verifies` → resolve each `page` key to its id, send `AiProposalInput.verifies`.
   **T2 landed one check the design did not predict:** `relationship` is validated **at file time**,
   not at accept — a value outside `VERIFIES | DERIVED_FROM | REFERENCES` is a `400` on
   `verifies[n].relationship` from `POST …/ai-proposals`, because a fixed three-word vocabulary is
   exactly what can be judged without the project's state. Send the wire spelling or omit the key;
   do not lower-case it. The block ids are still checked only at accept.
4. `tcms_write_plan`: the six fields on `create`/`edit`, through `core/rewrite.ts`'s split.
5. `tcms_process` — no gate, no project, same construction as `tcms_shape`; the registry count goes to 45.
6. `server.ts`: `prompts: {}` capability (no `listChanged`), `ListPrompts`/`GetPrompt` handlers
   rendering `PROCESSES` with their arguments; `tcms://process/{name}` resources beside the shapes.
CLI:
7. `tcms doc create --title T [--parent DOC-1] --from spec.md`, `tcms doc edit DOC-7 --from spec.md [--drop-linked]`
   (prints the anchors table), `tcms doc show DOC-7 --markdown`, `tcms doc adopt DOC-7 --plan TP-3 | --none`,
   `tcms case file … --verifies DOC-7:<blockId>` (repeatable), the six plan flags, `tcms process [name]`,
   `tcms spec import bundle.json` (page → anchors → proposals with labels resolved → plan → adopt; an
   `Idempotency-Key` per create derived from the bundle's content hash and the item's index through the
   core's generator; a step's refusal stops the run and prints what landed).
8. Surface spec: add §3.0b (`tcms_process` and prompts), the new arms under their tools' sections, the
   CLI rows in §6, and the count edits (44 → 45 in prose that states it).
9. **The one cross-stack edit, allowed here because a packages test pins it:** `guideCopies.test.ts`
   reads `frontend/src/features/help/sections.tsx` and will fail on the count and the missing
   `tcms_process`. Make the **minimal** text edit there — the number and the tool name in the
   Orientation list — and nothing else; T4 writes the prose. Then
   `~/bin/tcms-remote frontend 'npm run lint'` and `'npm run build'` must still pass.
10. Gates: `~/bin/tcms-remote packages 'npm run build'`, `'npm run lint --workspaces'`,
    `'npm run test:run --workspaces'`, `'npm run pack:release -- --version 0.0.0 --out /tmp/rel'`,
    plus the two frontend gates above.
11. Commit: `feat(agent-surface): markdown pages, verifies on propose, adopt, the process catalogue, spec import`.

Traps: surface spec §2 — no arm spans a gate its tool's reachable roles do not all hold; `adopt` is
`doc:edit` and lives on the doc tool for that reason. Every create arm sends `Idempotency-Key`;
`writeConformance.test.ts` fails otherwise. Tool descriptions truncate at 2KB; the long form goes in
the resource.

### T4 — the reviewer's screen and the words

Stack: `fe` + docs. Skill: `fe-react` (`fe-react-data-fetching`, `fe-react-component-testing`).

Read: T3b's commit; `frontend/src/features/proposals/ProposalPage.tsx` and its test;
`frontend/src/api/queries/` (the doc-page read hook, and `keys.ts` — never add a second factory);
`frontend/src/features/help/sections.tsx` (the MCP section T3b touched minimally);
`docs/features.md` §Documentation; design §7.

**What T2 landed that this task renders.** `AiProposal.verifies` is served **as stored and
unresolved** — a target whose block was deleted is still listed, which is exactly why the *no longer
exists* state below is the point of the region rather than an edge case. The links appear on the case
a projection later (an outbox handler in `documentation`), not in the accept response, so a screen
that reads `relatedDocuments` straight after Accept legitimately sees nothing yet.

1. `ProposalPage`: a *Verifies* region listing each `verifies` entry as page key + block text
   (read the page through the existing query hook; one read per distinct page), `relationship` as a
   label from `lib/labels.ts`, and a *no longer exists* state for a block the page does not contain —
   so the reviewer sees the accept-time 400 coming. `DataRegion` for the loading/failed/empty matrix;
   empty when `verifies` is `[]`. RTL test with a contract-conformant fixture — the SPA has no
   `AiProposal` fixture in `src/test/fixtures.ts`, so the one to extend is the typed `PENDING`
   literal at the top of `ProposalPage.test.tsx`, which T1 already gave `verifies: []`.
2. Help guide: the MCP section describes the five processes (one line each, from the catalogue's
   summaries — do not paraphrase them into a third copy), `tcms_process` and the prompts, and the
   count T3b set.
3. `docs/features.md` §Documentation: the page as a specification, labels, the round trip, and that a
   proposal names the requirements it verifies and is linked on acceptance.
4. Design spec §7: mark each task landed with its commit hash; note anything built but unverified.
5. Gates: `~/bin/tcms-remote frontend 'npm run build'`, `'npm run lint'`, `'npm run test:run'`,
   and `~/bin/tcms-remote packages 'npm run test:run --workspaces'` (the guide pin).
6. Commit: `feat(proposals): the requirements a proposal claims, on the reviewer's screen; docs close-out`.

Traps: never build a `QueryClient` in a test; a test that trips an error boundary must say so. Filter
state lives in the URL. No `sx`, no second stylesheet.
