# 022 — Agent-facing processes and the specification formatter

**Status (filed 2026-10-05):** Delivered — T1 `7e49c48c`, T2 `58f972b3`, T3a `22f118b9`, T3b `e784e811`, T4 `323625a9` (2026-09-07). Unverified: nothing in `packages/` has been run against a live instance; every gate there is a double compared to `openapi.yaml`. Any `Status`, task table or checkbox below this line is the plan's history, not its state.

**Date:** 2026-09-07 · **Design:** [`docs/specs/2026-09-07-agent-specification-formatter.md`](../../specs/2026-09-07-agent-specification-formatter.md) (read it first; §2–§5 are the spec, §7 is this table's source)

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

Every task has landed. Its prompt blocks were deleted when the plan was filed (2026-10-05); they are in git history before that commit.
