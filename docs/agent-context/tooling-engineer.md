# tooling-engineer — orientation cache

Baseline: `e784e811` (022 T3b), refreshed 2026-09-07.
Trust this as a lead, not evidence; `CLAUDE.md`, `docs/specs/openapi.yaml` and
`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` outrank it.

## Version lane
- Node ≥ 22.12, TypeScript `~6.0.2`, `strict`. `@modelcontextprotocol/sdk` **^1.30.0** (`#volatile`
  — a lockfile bump moves it with nothing else moving; re-read `packages/mcp/package.json`).
- No CLI parser library: `node:util` `parseArgs` in **strict** mode, fed by a per-command
  `OptionSpec`. An undeclared flag is a usage error, not a silent `true`.
- Lint is `oxlint` at 0 warnings; tests are Vitest. No Docker, no backend needed.

## Build, run & test
- `cd packages && npm run build` is the gate. **Never `--workspaces`** — that runs alphabetically,
  so `cli` typechecks against a stale `client` dist. The root script names client → cli → mcp.
- `npm run lint --workspaces`, `npm run test:run --workspaces`,
  `npm run pack:release -- --version 0.0.0 --out /tmp/rel` (a CI gate).
- Run all of it on `shanks` through `~/bin/tcms-remote` — standing directive. Targeted
  `npx vitest run <file>` locally is for debugging only.
- Smoke by hand: `node cli/dist/main.js help`, `node cli/dist/main.js format <entity>`,
  `node mcp/dist/main.js --help`. `tcms format` is the one offline command.

## Layout
- One npm workspace root at `packages/`, deliberately **not** the repo root. Three packages:
  `@tcms/client` (generated `schema.ts` + the shared core), `@tcms/cli`, `@tcms/mcp`.
- The contract document for this surface is
  `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` — §3 is the tool table, §3.4 the issue
  verbs, §6 the CLI, §8 what the checks are.
- The **core** is `client/src/core/`: one file per concern, no folders under it. 022 T3a added
  `markdown.ts` (TCMS spec markdown, both directions) and `processes.ts` (the five processes),
  both pure and both exported from `client/src/index.ts`.
- `mcp/src/process.ts` is the `tcms_process` tool, built exactly like `shape.ts`; `mcp/src/server.ts`
  holds the `prompts` capability, `ListPrompts`/`GetPrompt`, and the `tcms://process/{name}`
  resources beside `tcms://shape/{entity}`.
- Registries: `mcp/src/tools.ts` (`whoami` + `shape` + `READ_TOOLS` + `WRITE_TOOLS`) and
  `cli/src/commands.ts` (`READS` + `WRITES`). Everything else derives from those two lists — **and
  that is load-bearing rather than tidy**: 012 T5 added 8 commands and 2 tools, and neither
  `commands.ts` nor `server.ts` needed a line, consent prompt for the new delete arm included.
- Sizes as of 022 T3b: **45 tools** (21 read + 20 write + whoami + shape + **process**) and **94
  CLI commands**. Three places carry a count and all three are pinned: `writes.test.ts`'s
  `is 45 tools`, `sections.tsx`'s `45 tools` prose, and §6/§3 of the surface document. `sections.tsx` also carries
  three GROUP headings (`Orientation — 4`, `Reading — 21`, `Writing — 20`) that **nothing pins** —
  the test compares the names and the total, so a heading contradicting its own list ships green.
  012 did it and so did some slice between 016 and 022: `Writing` said 19 over a list of 20 until
  022 T3b counted them. **Count the list items by hand every time**; note also that the guide's
  three groups are NOT the registry's split (`tcms_report` is under Writing, `tcms_triage` under
  Reading), so `READ_TOOLS.length` is 22 while the guide's Reading list is 21. **§3's TABLE is not pinned either**: 013 T4 moved the
  heading's count to 30 and never added rows 29 and 30, and it sat wrong for two slices; 016 T1
  added them.
- **`findCommand` tries three words, two, then one.** `issue cases set` is the only three-word name
  and `issue cases` is a real command, so shortest-first would resolve the write to the read with
  `set` as the issue key. `guideCopies.test.ts`'s parse has the same ceiling and had to move with it.

## Seams to reuse
- **TCMS spec markdown** — `client/src/core/markdown.ts`, the only text↔`DocBlockInput[]`
  converter and deliberately not an endpoint. `blocksFromMarkdown(text, {mode, current?,
  dropLinked?})` → `{blocks, labels, warnings}` or throws `MarkdownError` (extends
  `TcmsClientError`, so `describeFailure` maps it to `invalid_input`) naming the line;
  `markdownFromBlocks(blocks)`; `anchorsFor(labels, page)` resolves a label to a block id **by
  index**, because the response is the page in the order the blocks were sent. The `{#…}` anchor is
  a UUID (an existing block's id, kept) or a label (a new block); the UUID test runs first and that
  order is the rule. The linked-block refusal lives here, not behind a prompt: an edit that drops a
  block cases claim is refused naming the block and the `sourceKey`s, unless `dropLinked`.
- **The process catalogue** — `client/src/core/processes.ts`: `PROCESSES` (five), `processNamed`,
  `processGuideFor` (long form, rendered like `guideFor`), `processCatalogue` (one line each).
  A step carries `does · tool? · cli? · permission? · check · trap?`; `cli` absent means **the CLI
  genuinely has no such command** — `tcms_orient` and `tcms_report` have none — and the renderer
  says so rather than leaving the step looking complete.
- **One shape catalogue, five renderers**: `client/src/core/shapes.ts`. `jsonSchemaFor` (MCP),
  `optionSpecFor` / `usageFor` / `flagOf` (CLI), `templateFor`, `guideFor`. A field added there
  reaches both adapters; a field hand-written on one is what `writes.test.ts` and `format.test.ts`
  exist to catch. `FieldSpec.type` gained `'number'` in 011 T5 for `estimateHours` (`format: double`).
- **Envelope**: reads answer `{items, page, size, total, hasMore, truncated}` (`reads.ts`'s `window`
  / `paged`); writes answer the contract resource; refusals are composed once in
  `client/src/core/outcome.ts` + `refusal.ts` and rendered by `mcp/src/result.ts`.
- **The markdown route through both adapters** — MCP `tcms_write_doc_page {markdown}` and CLI
  `doc create/edit --from`. The MCP argument takes the TEXT and the CLI flag takes a **PATH**
  (`-` is stdin, `readTextFile` in `cli/src/context.ts`); that asymmetry is the medium, not an
  inconsistency. Both refuse `markdown` + `blocks` together, both answer/print **`anchors`**
  (label → block id, the only place a label's id exists), and both read the page first on an edit
  — for the linked-block refusal **and** for `DocPageInput.title`, which the contract requires of
  any body edit (`core/rewrite.ts`'s `docPageInputFrom`, added by 022 T3b). **The version still
  does not come from that read**; `--lock-version` / `lockVersion` is still demanded.
- **`verifies` on a proposal**: MCP takes `[{page, block, relationship?}]` and resolves `page`;
  the CLI takes `--verifies DOC-7:<blockId>`, repeatable, and files `VERIFIES` only. The block id
  is **not** checked client-side (it is checked at accept); `relationship` **is**, against the
  catalogue's enum, because a fixed three-word vocabulary needs no project state to judge.
- **`spec import`** (`cli/src/writes.ts`) is the surface's only composite write and is CLI-only by
  decision (design §6). Its `Idempotency-Key`s are `sha256(bundle)[0..32]` + `-page` / `-case-<i>`
  / `-plan`, so an unchanged re-run replays; a refusal prints the ledger of what landed to stderr
  and rethrows so `main` maps the exit code.
- **Write mechanics** are declared, never inferred: `ToolArm.kind` / `Command.write.kind` ∈
  create · edit · state · delete, and `client/src/core/mutation.ts` (`createIntent` / `editIntent`)
  is the only place a header is generated.
- **Paging**: `PAGING_PROPERTIES` + `clampPageSize` (default 25, max 200). Most list endpoints are
  unpaginated on the wire, so the window is client-side and bounds the *model's* context.
- **Release version → id**: `releaseOf` (`cli/src/reads.ts`) and `releaseIn` (`mcp/src/reads.ts`),
  same arrangement, and the refusal lists the project's versions. Every release verb but `create`
  goes through it, because `release.id` is a UUID and every command is spelled by version.
- **Board name → id**: `boardOf` (`cli/src/reads.ts`) and `boardIn` (`mcp/src/reads.ts`), each
  imported by its own package's `writes.ts` rather than copied. Optional — one board is defaulted,
  two or more is a refusal listing them (plan 012 D-t). Same shape for labels: `labelIds` /
  `labelIdsIn` resolve names against `GET …/labels`, and an assignee name against
  `GET …/members` (`project:read`; `/users` is off this surface per §3.3).
- **Display keys, never UUIDs**: `resolveId` / `classify` / `resolveProject` in
  `client/src/core/resolve.ts`. Per-project vocabulary (execution status, issue type, issue status)
  is taken as a **name** and resolved against the project's own list — `namedIn` in
  `mcp/src/writes.ts`, `namedInWorkflow` in `cli/src/writes.ts`.
- **Config**: `TCMS_URL` + `TCMS_TOKEN` (CLI: also `--url`, `--timeout`, `--project`). No config
  file, no keychain. The MCP HTTP transport takes the credential per request instead.
- Fixtures for every doubled resource: `client/src/test/fixtures.ts`, `Partial<T>` in and `T` out.
  **`contractBodies.ts` is NOT that file** — it is the schema walker, its marked region is
  `violationsOf` and friends, and it holds no response bodies at all. Plan 016 T1's own prompt said
  to put fixtures in its shared region; there is nowhere in it for one to go.
- **A read tool may be `any`-gated too.** `tcms_read_timesheet` (016 T1) is the first: `time:log`
  reads your own period, `time:approve` reads the approvals queue, and one noun with two audiences
  is one tool with a `narrow(held)` sentence rather than two tools differing by a boolean.

## Conventions observed
- `projectId` / `companyId` appear in **no** tool schema (§4.1); they come from the session.
- A tool's `gate` is a **listing** rule, never authorization — the server always decides.
  `mode: 'any'` is for a gate whose keys a real role holds only some of (`tcms_review_verdict`,
  `tcms_write_issue`), and such a tool carries a `narrow(held)` sentence.
- Every tool description ≤ 2 KB; `additionalProperties: false` on every input schema.
- A conformance table with no entry for a registry row **fails**, never skips —
  `INVOCATIONS` in both `writeConformance.test.ts` files, `TOOL_FOR` / `COMMAND_FOR`.
- **The catalogue↔surface pins are what a catalogue-before-adapter task collides with.**
  `mcp/src/writes.test.ts` ("declares every field the catalogue offers") and
  `cli/src/format.test.ts` ("gives every field a flag the command actually takes") both go red the
  moment a `FieldSpec` has no argument behind it. ~~022 T3a's `PENDING_ON_THE_SURFACE` maps~~ →
  **both deleted by 022 T3b**; the two checks are now bare `expect(gaps).toEqual([])`. **A field
  added to `shapes.ts` reddens both adapters' suites in the same run**; budget for it.
- **`contractExchange` checks the body a command SENT, not only the one a stub served**, and it is
  the check that finds an adapter composing something the contract refuses. 022 T3b hit it twice on
  its first run: `DocPageInput.title` missing from a whole-body page edit (a defect predating the
  slice, on both surfaces, invisible because no test drove such an edit to the `PATCH`), and a
  `relationship` outside the enum being forwarded rather than refused.
- A shortfall is recorded in a named map with what it costs (`CREATE_WITHOUT_A_KEY`, `UNOFFERED`),
  and each map is pinned both ways so a stale entry fails too. **`UNOFFERED` is the one that finds
  a contract amendment nobody wired**: `shapesContract.test.ts` fails when a property of a shape's
  contract schema is on neither adapter, and it is what caught `IssueInput.releaseId` in 013 T4.
- **`ToolArm.kind: 'delete'` is the consent gate and nothing else checks it.** `delete` and `state`
  both imply no header, so `writeConformance.test.ts` passes either way; `annotationsFor` reads the
  kind for `destructiveHint` and `server.ts` refuses when there is no channel to ask on, and the
  CLI's `main.ts` raises its confirmation off the same field. A delete declared `state` ships with
  no prompt and nothing red. Plan 013 T4 spelled both release deletes `state`; T4 corrected it and
  wrote a test per surface that asserts the kind directly.
- **`ReadinessSummary.casesPassed`/`casesFailed` are null, not zero, when `hasRun` is false**, and
  every rendering has to keep them that way — `tcms release list` prints the words `no run yet`. A
  `?? 0` between the wire and a cell turns *nothing has been tested* into *nothing failed*, and no
  type in this tree would notice. The `readinessSummary()` fixture defaults to the untested state
  for exactly that reason.
- Superseded prose is struck through and kept. A path named in a comment must exist —
  `client/src/test/citedFiles.test.ts` scans `packages/**`, `frontend/src/**`, `docs/specs/*.yaml`
  and `backend/src/**`, so a stale citation in another stack reddens *this* job.
- `frontend/src/features/help/sections.tsx` is pinned by `guideCopies.test.ts` in **both** packages:
  its tool list, its command list and its `N tools` count. A registry change means editing it —
  measured in 012 T5, where it was one of three files outside `packages/*/src` that a registry
  change forced. The other two: `client/src/test/fixtures.ts` (a factory per resource, because
  §203's exchange check compares every doubled body to `openapi.yaml`) and `mcp/src/server.test.ts`
  (§213's exact `tools/list` comparison — its expected side is now `TOOLS.filter(isListed)`, since
  `tcms_write_backlog` is the first tool an `Agent` credential is correctly not shown).
- **A success can carry bad news and must still be a success.** `tcms board move` prints a WIP
  breach and exits 0; `tcms rank` prints a rebalance and exits 0; both reach the model as data on
  the MCP side. A non-zero exit would make a WIP limit something a CI step fails on.
- **Cross-field input rules are refused locally, before the request** — `rank`'s exactly-one-of
  `--after`/`--before`, `doc link`'s exactly-one-of `--case`/`--suite`. The endpoint answers 400
  either way; what is gained is a message naming the flags a person typed rather than the JSON
  fields they never composed.

## Known unknowns
- `#volatile`: the installed MCP SDK version; the live API's actual behaviour (every check here
  reads `openapi.yaml`, not a running server).
- §8.1's permission check reads an operation's prose for the literal word `Requires`. Neither the
  tracker nor the **board** nor the **release** operations use it, so that assertion is **skipped**
  for the eight `tcms_write_issue` arms, all four of `tcms_write_backlog`'s and all four of
  `tcms_write_release`'s — recorded in the contract document §3.4, §3.5 and §3.6.
- `PATCH /labels/{labelId}` declares no `If-Match` and `Label` carries no `lockVersion`, so
  `tcms_write_backlog.label-update` is in `CREATE_WITHOUT_A_KEY` with what it costs. **013 T4 added
  two more of the same shape** — `tcms_write_release.update` and `tcms_write_issue.release`, both
  `PATCH`es with no version on the wire — so the map now has five members across the two packages
  and the pattern is the contract's rather than any one endpoint's.
- `writes.test.ts`'s merge rule (`ASSIGNABLE`) still lists four roles and **omits `PM`**, which `V2`
  made assignable. It is inert today because the check intersects each role with `AGENT_KEYS` and no
  Agent credential holds a `board:*` or `release:*` write key, so the loop skips every multi-key
  gate. Left alone in 013 T4 rather than widened blind; it is the thing to look at first the day an
  Agent grant reaches one of those keys.
- Nothing in `packages/` has been exercised against a live instance in this lane; every gate is a
  double compared to the contract.

## Session log
- 2026-10-04 · 023 A1: project colour in `openapi.yaml` (`ProjectColor`, `setProjectColor`), both schema copies regenerated, both `Project` fixtures carry `color`; `UNOFFERED` covers only catalogued schemas, no project entity, nothing added.
- 2026-09-07 · delta (baseline `6a8db463` had been amended to `22f118b9`; the only tree difference
  was this file, so effectively TRUST) · 022 T3b: the adapters. **45 tools** (`tcms_process`),
  **94 CLI commands** (`process`, `doc adopt`, `spec import`), the `prompts` capability with
  `tcms://process/*` beside the shapes, `markdown`/`anchors`/`dropLinked`/`adopt` on
  `tcms_write_doc_page`, `as: 'markdown'` on `tcms_open_doc_page`, `verifies` on `case propose`,
  and the plan's six document fields on both surfaces. Both `PENDING_ON_THE_SURFACE` maps deleted.
  Arrived **green** (595: cli 182 · client 170 · mcp 243); left green at **642** (cli 203 ·
  client 170 · mcp 269). Four things the plan did not predict, three found by an existing check:
  `ProposalLinkInput.relationship` is **required** in the generated type (`openapi-typescript`
  reads a default as always-present), so it is sent explicitly rather than omitted — `doc link`'s
  precedent; `contractExchange` then refused a value outside the enum, so the client **validates
  `relationship` locally** rather than forwarding a typo; the same check found
  `DocPageInput.title` missing from every whole-body page edit either adapter has ever made, which
  is a defect older than this slice and is repaired by a fourth projection in `core/rewrite.ts`
  (`docPageInputFrom` — the one `packages/client` core change this task made, and it is the shared
  rule rather than an adapter's); and `writes.test.ts`'s existing lockVersion test needed a page
  read stubbed because that repair added one. Nothing here has been run against a live instance.
- 2026-09-07 · delta (contract doc, `openapi.yaml`, generated schema, both adapters' `writes.ts` /
  `reads.ts`, fixtures and `shapesContract.test.ts` had moved under 017–022 T2; `core/shapes.ts`
  had not) · 022 T3a: `core/markdown.ts`, `core/processes.ts`, `CASE_SHAPE.verifies`, the plan
  shape's six document-body fields, `DOC_PAGE_SHAPE.markdown`. Arrived **green** (563 packages
  tests: cli 182 · client 138 · mcp 243); left green at **595** (client 170). Four things the plan
  did not predict, three of them found by an existing check: the two catalogue↔surface pins cannot
  stay green while the catalogue leads the adapters, so both gained a
  `PENDING_ON_THE_SURFACE` map (T3b deletes them); `shapesContract`'s **nested** pin compared the
  surface name to the contract's and had to learn `FieldSpec.contract` the way `contractSiteOf`
  already had, or `verifies[].page` could not render to `pageId`; the CLI command is `case propose`
  and both the plan and design §4.3 called it `case file`; and `tcms_orient` / `tcms_report` reach
  **no** CLI command, so two process steps are MCP-only. `usageFor`'s "cannot be spelled in argv"
  line indented each name rather than the line, which was invisible until the plan had three such
  fields. Verified by hand as well as by suite: both worked examples in
  `docs/specs/assets/spec-formatter/` parse to exactly the labelled-block counts their README
  claims (52 and 32) and round-trip identically. **Nothing here is reachable from either adapter
  until T3b** — the catalogue describes fields no tool yet accepts, which is what the two maps say.
- 2026-09-04 · delta (contract doc, `openapi.yaml`, generated schema, `shapes.ts`, both registries
  and `sections.tsx` had all moved under 014 and 015; the cache's baseline predated both) · 016 T1:
  the time verbs. **Six** CLI commands (`time list|queue|rollup|log|submit|approve` — the plan said
  five and had no way to obtain another person's period id, so `time queue` was added) and **four**
  MCP tools (`tcms_read_timesheet`, `tcms_read_time_rollup`, `tcms_write_timesheet`,
  `tcms_approve_timesheet`). Arrived **green** (528 packages tests); left green at 528. Three things
  the plan did not predict, and all three were the existing checks rather than reading: the fixtures
  do not live in `contractBodies.ts`, `guideCopies.test.ts` forced `sections.tsx` again, and §3's
  table in the surface doc had been two rows short since 013 T4. **The endpoints 404 until 016 T3
  lands** — contract-first, no stub, nothing here has been run against a live instance.
- 2026-09-01 · delta (contract doc + `openapi.yaml` + generated schema; no hand-written
  `packages/*/src` file had moved) · 013 T4: the release verbs. **Ten** CLI commands
  (`release list|show|create|edit|publish|delete`, `roadmap`, `issue cases`, `issue release`,
  `issue cases set` — the plan's deliverable line said eight and its own Step 2 spelled ten),
  `tcms_read_release`, `tcms_write_release`, and `cases` + `release` arms on `tcms_write_issue`.
  Arrived **green** (500 packages tests); left green at 500. Three things the plan did not predict
  and all three were found by an existing check rather than by reading: `findCommand`'s two-word
  ceiling, `shapesContract.test.ts`'s `UNOFFERED` on `IssueInput.releaseId`, and that the release
  deletes had to be `kind: 'delete'`. Nothing here has been run against a live instance.
- 2026-08-31 · delta (contract doc + generated schema) · 012 T5: the board verbs. 8 CLI commands
  (`board list|show|move`, `backlog`, `rank`, `label list|add|set`), `tcms_read_board`,
  `tcms_write_backlog`, and `move-card` + `label` arms on `tcms_write_issue` — the three-way split
  §2's rule forces (D-j). Arrived **green**; left green, 448 packages tests. **A second agent was
  running 012 T6 in the same checkout throughout**, with its work staged in the index; both commits
  used `git commit --only -- <paths>` and nothing was lost, but that is the arrangement CLAUDE.md
  forbids and it cost one red `citedFiles` run on the other lane's uncommitted comment.
- 2026-08-31 · full · Created. 011 T5: `tcms_write_work_item` → `tcms_write_issue` (2 arms → 4),
  new `tcms_read_issue`, `WORK_ITEM_SHAPE` → `ISSUE_SHAPE`, `GET /work-items` → `GET /board`, five
  new CLI `issue` commands. Arrived **red** — 011 T1 had removed `WorkItem` from the contract and
  left `packages/` uncompiled against it.
