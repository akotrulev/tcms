# tooling-engineer — orientation cache

Baseline: `a7be32cc` (012 T5), refreshed 2026-08-31.
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
  `cli/src/commands.ts` (`READS` + `WRITES`). Everything else derives from those two lists — **and
  that is load-bearing rather than tidy**: 012 T5 added 8 commands and 2 tools, and neither
  `commands.ts` nor `server.ts` needed a line, consent prompt for the new delete arm included.
- Sizes as of 012 T5: **28 tools** (12 read + 14 write + whoami + shape) and **43 CLI commands**.
  Three places carry a count and all three are pinned: `writes.test.ts`'s `is 28 tools`,
  `sections.tsx`'s `28 tools` prose, and §6/§3 of the surface document.

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
  tracker nor the **board** operations use it, so that assertion is **skipped** for the six
  `tcms_write_issue` arms and all four of `tcms_write_backlog`'s — recorded in the contract
  document §3.4 and §3.5.
- `PATCH /labels/{labelId}` declares no `If-Match` and `Label` carries no `lockVersion`, so
  `tcms_write_backlog.label-update` is in `CREATE_WITHOUT_A_KEY` with what it costs.
- Nothing in `packages/` has been exercised against a live instance in this lane; every gate is a
  double compared to the contract.

## Session log
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
