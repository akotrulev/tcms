# §253 — One answer to "what shape is this"

**Stacks:** `packages/` only. No backend, no frontend, no contract change — `openapi.yaml` is read
and never edited. Plan: [`docs/plans/2026-08-26-authoring-shape-catalogue.md`](../plans/2026-08-26-authoring-shape-catalogue.md).

## What started it

*"Can we introduce a skill for writing test cases in the correct format to be created via the MCP,
or add some data to the MCP so an agent can pick up the format and different objects"* — then
widened to *"enrich all objects and actions that can be performed by the mcp and cli, so that we
have a consistent format across all."*

A repo-local Claude Code skill was **rejected as the carrier**, and the reason generalises: the
audience for the MCP surface is agents connecting to a deployed instance who have never seen this
repository. A skill reaches only sessions working inside this checkout. The format has to travel
with the server.

## The defect

`tcms_write_case`'s `testCase` argument was declared, in full, as `{ type: 'object' }` with a prose
description and **no `properties` at all**. A model was handed four field names in English and had to
guess the rest — the step shape, that `tags` and `classificationValueIds` are complete-set, that
conditions are reference-only, that `automationKey` is required when `AUTOMATED`.

**Enriching it by hand would have made a fifth copy.** *What shape does this object take* was already
answered in four places and no two agreed on how much: a hand-written `JsonSchema` per MCP tool; an
`OptionSpec` per CLI command carrying types and nothing else — **no enums, no required-ness, no
descriptions**; `openapi.yaml`; and `core/proposal.ts`, which covered one entity, checked presence,
and was the only genuinely shared fragment.

## What was built

One catalogue in `@tcms/client/core/shapes.ts` — eight entities, every field declared as **the
surface takes it**, display keys and all — plus six renderers, so every carrier is a rendering of one
source. MCP: the enriched `inputSchema` on every write tool, `tcms_shape`, and a `resources`
capability the server did not advertise at all. CLI: `tcms format`, and `case edit`'s options and
usage rendered rather than written out.

## THE RULE TO CARRY: hand-written is fine; hand-written *and unpinned* is the defect

The catalogue is deliberately **not** generated from `openapi.yaml` — `tool.ts` has the argument and
it is right, that a tool argument takes a display key where the API takes a UUID, so a generated
schema produces a surface no caller on either transport can use. That decision is only safe with
`shapesContract.test.ts` beside it, asking five questions of the contract, most of them **both
ways**.

It was believed only after being broken three ways on purpose — a dropped enum member, an
un-required field, an invented field — because seven green tests on a first run is the shape §223
recorded as *six of seven green against a server nothing had ever connected to*.

**The question that finds omissions rather than errors is the fourth**: every contract property is
either offered by the catalogue or listed in `UNOFFERED` with a reason. That is what turned a silence
into a list — a plan's `tags` reach neither adapter, and three `TestRunInput` fields cannot (a user
UUID nothing on this surface can obtain, project configuration §3.3 excludes, and an approval
override no credential here could exercise).

## Four things the catalogue found that nothing else had

1. **`automationKey` was on the CLI's `case edit` and not on the MCP's.** So an agent could set
   `automationStatus: AUTOMATED` with no way to send the key the server requires beside it — a
   guaranteed 400, in a tool nothing tested for it, because both adapters were hand-maintained and
   nothing compared them.
2. **`--not-automatable-reason` was missing from the CLI** while `--automation-status
   NOT_AUTOMATABLE` was accepted: the one spelling the server refuses was the only one the command
   could produce.
3. **`ArtifactInput.kind` is required by the contract and on neither adapter** — and that one is
   **correct**. `tcms_write_artifact` derives it from which payload was named, because a `kind`
   argument would be a second statement of something the arguments already make and the two can
   disagree. The check demanded the reason be written down rather than inferred.
4. **The doc-page blocks shipped as `{ type, text }`** against a `DocBlockInput` that declares
   `blockType`. Mine, and live for one commit. **All four checks were blind to it because they read
   top-level properties only** — the nested shapes were invisible to every one of them. The check is
   now general over every `object[]` in every entity, keyed on a declared `itemsSchema`, and was
   confirmed by putting the bug back.

## Decisions worth not relitigating

- **`tcms_shape` is separate from `tcms_orient`, and the reason is scope, not taste.** Orient opens
  with `session.projectId()`. The caller who most needs the format is the one who has connected and
  not yet resolved a project. Orient owns the project's **live vocabulary**; this owns the **shape**;
  neither restates the other, and a test asserts it.
- **`tcms format` is declared `offline`** — a new flag on `Command`. It reaches no instance, so
  `main` must not demand a URL or a token for it. Every other command fails there correctly.
- **Nothing on an `edit` is required.** Both adapters read the row and send back what the caller did
  not name (the PATCH is not partial), so `bindsRequired` suppresses a mark that would otherwise make
  a person pass a field in order to leave it alone.
- **A `file` action renders JSON keys and a `flags` action renders flags.** `tcms case propose` takes
  `--file` and no per-field flag; the first draft listed `--title` under it, which is a help screen
  naming an option that does not exist. `verdict` stays a flag either way, because it rides
  `AiProposalInput`, not the case.
- **`steps` stays out of the JSON Schema `required` list.** A host validating it would reject a
  GHERKIN case, which is legal — the same trade `openapi.yaml` records making and declining, for the
  same reason.
- **Resources are advertised without `listChanged`** and are **not** permission-filtered. Both are
  reasoned in surface spec §3.0a.

## The plugin finding, which cost an hour and will cost it again

The `tool-mcp-builder`, `tool-cli-builder` and `tool-capability-contract` skills were invisible to
this session, and `.claude/settings.json` looked complete. They ship in a **separate `tooling`
plugin** that this repo's `enabledPlugins` did not list — not a path problem, not the
`installed_plugins.json` `projectPath` failure CLAUDE.md already documents, which was healthy. The
skills were on disk the whole time, at
`~/.claude/plugins/marketplaces/skills-library/tooling/skills/`. **`tooling@skills-library` is now
enabled**; the diagnosis that works is reading `.claude-plugin/marketplace.json` in the marketplace
checkout and comparing its plugin list against `enabledPlugins`.

Two of their rules are applied: every field description ends with its example, and every `--help`
carries one worked example that actually runs — positional included, which the first draft omitted.

## State

Gates on **shanks**, all three packages: build clean, lint **0 warnings / 0 errors**, tests
**367** (92 cli · 137 client · 138 mcp), up from 341.

**Built and NOT verified.** No live pass has been run against a real instance: `tcms_shape` has
never answered a real MCP host, `resources/list` has never been served to one, and `tcms format` has
been run only from `dist/` on this machine. Rows are owed and neither existing queue covers this
surface — the browser queue is a browser lane and the mutation queue is the backend's, and **this
slice writes nothing**, so its verification is a read-path pass with no queue to sit in. That gap is
recorded here rather than resolved.

## Not done, deliberately

**The CLI is a subset of the MCP surface** — 16 write commands against 27 arms. `case delete`,
`case submit`, `run clone`, `review verdict`, all of work items, all of artifacts and
`report generate` have no CLI command. This slice makes the asymmetry **declared and checked**
(`ActionSpec.cli` / `.mcp`, printed by `tcms format`) rather than discovered; closing it is a
separate slice with its own consent and output questions.

**`--file` is ragged and stays ragged.** It is on six of the ten body-carrying commands and missing
from `suite create`, `plan create`, `run create` and `result record`. `doc create` takes a whole body
and `suite create` does not, which reads as an accident — but adding it is a widening of the CLI
surface, so `mechanicsFor` derives `--idempotency-key` and `--lock-version` from the declared kind
(5/5 and 4/4 across the registry, surface spec §4.2 and §4.3 restated) and leaves `--file` alone.
