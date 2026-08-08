# CLI writes — the MCP plan's phase 7

**Date:** 2026-08-08 · **Slice:** §139 · **Stack:** `packages/` only — no migration, no contract
change, no backend or frontend code.

Phase 7 of [`2026-08-08-mcp-server-wip-plan.md`](2026-08-08-mcp-server-wip-plan.md): *CLI writes —
edits direct, creates as proposals* (§1.3, `[SETTLED]`). Both arms are buildable: §136 added
`Idempotency-Key` to the two creates that lacked it, §137 built and tested the write machinery with
nothing calling it, and §138 shipped `POST …/ai-proposals`, which is the create arm.

The surface is [`../specs/2026-08-08-mcp-tool-and-command-surface.md`](../specs/2026-08-08-mcp-tool-and-command-surface.md)
§6 — `tcms <noun> <verb>`, nouns matching the MCP tool families, one command per operation.

## 0. The finding this slice is organised around

**An `If-Match` the client read for itself is only honest when the request is not a delta.**

Every edit needs a `lockVersion`, and the obvious convenience is for the command to read it: `tcms
case edit TC-42 --title 'x'` should not make a person run `case show` first. That is safe, and the
reason it is safe is narrow — a **flag edit states its whole intent in the flags**. `PATCH` reads an
absent key as *leave it alone*, so `--title` is a request to change the title and nothing else; a
version read one millisecond earlier is a truthful precondition, and a concurrent edit to another
field is not a conflict at all.

**`--file` inverts that, and it inverts it silently.** A whole-body edit is produced from an earlier
read — the caller pulled `case show --json`, changed three fields, and is sending the result. Its
precondition is *the version that read returned*, which may be several saves ago. A command that
auto-reads the version there would send an `If-Match` that matches, the server would accept, and the
concurrent author's words would be gone: **last-write-wins, arriving through the header that exists
to prevent it, with every party's code looking correct.**

So the rule, and it is a type-level one rather than a convention:

| Edit shape | `If-Match` |
|---|---|
| Flag edit (`--title`, `--priority`, …) | read for the caller, immediately before the write |
| `--file` (a body the caller composed) | **`--lock-version` is required**; the command refuses without it |

`--lock-version` overrides the read in both shapes, which is what a script that already holds the
version wants.

## 1. Scope — the write commands

Mirroring the write tools of the surface spec §3, restricted to what the REST contract offers.
Nouns are the read commands' nouns.

**Phase 1 — the core, and the case arms**

| Command | Endpoint | Shape |
|---|---|---|
| `case edit KEY` | `PATCH …/cases/{caseId}` | edit — `If-Match` |
| `case propose` | `POST …/ai-proposals` | create — `Idempotency-Key` |

`case create` **does not exist and must not**: §1.3 is settled, generation files a proposal and a
person accepts it. A person authoring a case by hand uses the SPA. That absence is the feature, and
it is spelled as *no command* rather than as a command that refuses — the surface spec §3.3's rule
for the tool layer, applied one client over.

`case accept` and `case reject` also do not exist. The accept gate is `case:create` **and no
`TokenContext`** (§138), and every request this CLI makes carries a token, so an accept command
would 403 on every invocation it could ever make. It is the one arm this surface is structurally
incapable of.

**Phase 2 — suite and doc**

| Command | Endpoint | Shape |
|---|---|---|
| `suite create` | `POST …/suites` | create |
| `suite edit KEY` | `PATCH …/suites/{suiteId}` | edit |
| `suite status KEY` | `PUT …/suites/{suiteId}/status` | state — no `If-Match`, by the contract |
| `doc create` | `POST …/doc-pages` | create |
| `doc edit KEY` | `PATCH …/doc-pages/{pageId}` | edit |
| `doc link KEY` | `POST …/doc-pages/{pageId}/blocks/{blockId}/links` | create — no key on the contract |
| `doc unlink KEY` | `DELETE …/doc-pages/{pageId}/links/{linkId}` | delete |

**Phase 3 — plan, run, result, review**

| Command | Endpoint | Shape |
|---|---|---|
| `plan create` | `POST …/plans` | create |
| `plan edit KEY` | `PATCH …/plans/{planId}` | edit |
| `plan status KEY` | `PUT …/plans/{planId}/status` | state |
| `run create` | `POST …/runs` | create |
| `run status KEY` | `PUT …/runs/{runId}/status` | state |
| `result record KEY CASEKEY` | `PATCH …/runs/{runId}/executions/{executionId}` | edit |
| `review comment` | `POST …/reviews/{reviewId}/comments` | create — no key on the contract |

The state commands accept **every value the contract accepts**, not only the two the `Agent` role
can reach. The CLI is a person's tool as much as an agent's, and a token issued under Test Lead
holds `suite:approve`; filtering the list client-side would be a second copy of §6g's ladder, which
each status endpoint's own description already refuses for the same reason.

## 2. The computed check

Surface spec §8.3: *assert every create arm sends a key and every edit arm sends `If-Match`, over
the registry rather than per tool*. So `Command` gains a `write` declaration — `create` / `edit` /
`state` / `delete` plus the permission the surface declares — and one test drives **every** write
command through a recording `fetch` and asserts the header its declaration implies. A new write
command that forgets fails the build rather than being found by an agent.

This is the same shape as `RepositoryTransactionTest` deriving its repository list from the live
context, and the reason is §108's: a check that reads a list somebody maintains is a check on the
list.

## 3. What stays out

- **`POST …/cases/bulk`.** The spec keeps it in the CLI (§3.3) and it is genuinely useful there —
  but its gate is decided by the payload, so it wants a command per operation, and that is a slice.
- **Evidence upload** (`POST …/evidence`), which is multipart and needs a file-reading path this
  package does not have.
- **Report generation** (`tcms_report`), which is a poll loop rather than a write.
- **Everything gated by a permission `Agent` does not hold** *and* that a person would reach through
  the SPA — approve, delete, project settings, the library writes.

None of these are refusals in principle; they are the next slice's, and the plan's phases 8–10 come
first.

## 4. Verification (§65)

Rows go in [`../verification/backend-mutation-checks.md`](../verification/backend-mutation-checks.md)
— every write arm, against a live instance with an issued token. Nothing goes in the browser queue:
a CLI is not a browser lane (surface spec §9).

**The gap §137 opened widens again**: no `@tcms/cli` command has been run against a real TCMS, and
this slice adds writes to the set. Every test drives a stubbed `fetch`, which is the same shape of
observer as the mock layer §107 deleted.
