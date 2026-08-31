# tooling-engineer — orientation cache

Baseline: 011 T5 (see the session log), refreshed 2026-08-31.
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
- Registries: `mcp/src/tools.ts` (`whoami` + `shape` + `READ_TOOLS` + `WRITE_TOOLS`) and
  `cli/src/commands.ts` (`READS` + `WRITES`). Everything else derives from those two lists.

## Seams to reuse
- **One shape catalogue, five renderers**: `client/src/core/shapes.ts`. `jsonSchemaFor` (MCP),
  `optionSpecFor` / `usageFor` / `flagOf` (CLI), `templateFor`, `guideFor`. A field added there
  reaches both adapters; a field hand-written on one is what `writes.test.ts` and `format.test.ts`
  exist to catch. `FieldSpec.type` gained `'number'` in 011 T5 for `estimateHours` (`format: double`).
- **Envelope**: reads answer `{items, page, size, total, hasMore, truncated}` (`reads.ts`'s `window`
  / `paged`); writes answer the contract resource; refusals are composed once in
  `client/src/core/outcome.ts` + `refusal.ts` and rendered by `mcp/src/result.ts`.
- **Write mechanics** are declared, never inferred: `ToolArm.kind` / `Command.write.kind` ∈
  create · edit · state · delete, and `client/src/core/mutation.ts` (`createIntent` / `editIntent`)
  is the only place a header is generated.
- **Paging**: `PAGING_PROPERTIES` + `clampPageSize` (default 25, max 200). Most list endpoints are
  unpaginated on the wire, so the window is client-side and bounds the *model's* context.
- **Display keys, never UUIDs**: `resolveId` / `classify` / `resolveProject` in
  `client/src/core/resolve.ts`. Per-project vocabulary (execution status, issue type, issue status)
  is taken as a **name** and resolved against the project's own list — `namedIn` in
  `mcp/src/writes.ts`, `namedInWorkflow` in `cli/src/writes.ts`.
- **Config**: `TCMS_URL` + `TCMS_TOKEN` (CLI: also `--url`, `--timeout`, `--project`). No config
  file, no keychain. The MCP HTTP transport takes the credential per request instead.
- Fixtures for every doubled resource: `client/src/test/fixtures.ts`, `Partial<T>` in and `T` out.

## Conventions observed
- `projectId` / `companyId` appear in **no** tool schema (§4.1); they come from the session.
- A tool's `gate` is a **listing** rule, never authorization — the server always decides.
  `mode: 'any'` is for a gate whose keys a real role holds only some of (`tcms_review_verdict`,
  `tcms_write_issue`), and such a tool carries a `narrow(held)` sentence.
- Every tool description ≤ 2 KB; `additionalProperties: false` on every input schema.
- A conformance table with no entry for a registry row **fails**, never skips —
  `INVOCATIONS` in both `writeConformance.test.ts` files, `TOOL_FOR` / `COMMAND_FOR`.
- A shortfall is recorded in a named map with what it costs (`CREATE_WITHOUT_A_KEY`, `UNOFFERED`),
  and each map is pinned both ways so a stale entry fails too.
- Superseded prose is struck through and kept. A path named in a comment must exist —
  `client/src/test/citedFiles.test.ts` scans `packages/**`, `frontend/src/**`, `docs/specs/*.yaml`
  and `backend/src/**`, so a stale citation in another stack reddens *this* job.
- `frontend/src/features/help/sections.tsx` is pinned by `guideCopies.test.ts` in **both** packages:
  its tool list, its command list and its `N tools` count. A registry change means editing it.

## Known unknowns
- `#volatile`: the installed MCP SDK version; the live API's actual behaviour (every check here
  reads `openapi.yaml`, not a running server).
- §8.1's permission check reads an operation's prose for the literal word `Requires`. The tracker
  operations do not use it, so that assertion is **skipped** for the four `tcms_write_issue` arms
  — recorded in the contract document §3.4.
- Nothing in `packages/` has been exercised against a live instance in this lane; every gate is a
  double compared to the contract.

## Session log
- 2026-08-31 · full · Created. 011 T5: `tcms_write_work_item` → `tcms_write_issue` (2 arms → 4),
  new `tcms_read_issue`, `WORK_ITEM_SHAPE` → `ISSUE_SHAPE`, `GET /work-items` → `GET /board`, five
  new CLI `issue` commands. Arrived **red** — 011 T1 had removed `WorkItem` from the contract and
  left `packages/` uncompiled against it.
