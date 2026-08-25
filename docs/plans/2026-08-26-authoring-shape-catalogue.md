# The authoring shape catalogue — one answer to "what does this object look like"

**Status:** proposed, 2026-08-26. Target handoff section: **§253**.
**Stacks:** `packages/` only (`@tcms/client`, `@tcms/cli`, `@tcms/mcp`). No backend, no frontend,
no contract change — `openapi.yaml` is read, never edited.

## 0. What started this

*"Can we introduce a skill for writing test cases in the correct format to be created via the MCP,
or add some data to the MCP so an agent can pick up the format and different objects"* — then,
widened: *"let's enrich all objects and actions that can be performed by the mcp and cli, so that
we have a consistent format across all."*

A repo-local Claude Code skill was considered and **rejected as the carrier**: it reaches only
sessions working inside this checkout, and the audience for the MCP surface is agents connecting to
a deployed instance who have never seen this repository. The format has to travel with the server.

## 1. What the reading found

`tcms_write_case`'s `testCase` argument is declared, in full, as:

```ts
testCase: { type: 'object', description: '…prose…' }
```

**No `properties`.** A model is given four required field names in English and must guess the rest.
What it cannot guess, and what nothing on the wire tells it:

| It must know | Where that lives today | What a model is told |
|---|---|---|
| the step shape — `{keyword, action, testData, expectedResult}`, `action` alone required | `openapi.yaml` `TestStepInput` | nothing |
| `tags` and `classificationValueIds` are a **complete set** — absent leaves alone, `[]` clears | `openapi.yaml`, two long descriptions | nothing |
| conditions are reference-only `{conditionId}`; an `inlineBody` is a 400 | `openapi.yaml` `CaseConditionInput` | nothing |
| `automationKey` required iff `AUTOMATED`; `notAutomatableReason` iff `NOT_AUTOMATABLE` | `core/proposal.ts:52`, after the guess | nothing |
| `declinedConditionIds` take **attachment** ids, not library condition ids | the `edit` arm's prose | only on `edit`, never on `propose` |

The same question — *what shape does this object take* — is answered in four places and no two of
them agree on how much:

- **MCP**: a hand-written `JsonSchema` per tool, prose-heavy, and for the case body, empty.
- **CLI**: an `OptionSpec` (`{type: 'string'|'boolean', multiple?}`) plus a freehand `usage` string.
  Types only — **no enums, no required-ness, no descriptions.**
- **`openapi.yaml`**: authoritative and complete, and deliberately *not* the source here.
  `tool.ts` argues it and is right: a tool argument takes display keys where the API takes UUIDs,
  flattens `CaseConditionInput` to bare ids, and is written for a model rather than for a person
  reading API docs. Deriving tool schemas from the contract produces a surface no caller on this
  transport can use.
- **`core/proposal.ts`**: the only genuinely *shared* fragment. It covers one entity, checks
  presence and nothing else, and exists because the contract itself was wrong for a year (§144).

So there is no single answer to "what fields does a plan take, which are enums, which are
complete-set, which are conditionally required", and the two adapters answer differently and
incompletely. That is the defect. Enriching `testCase` by hand would make it five places.

## 2. The proposal

**One catalogue in the command core, and every carrier is a rendering of it.**

New module `packages/client/src/core/shapes.ts`, exported from `@tcms/client`. It declares, per
writable entity, the fields **as this surface takes them** — display keys and all — with the
semantics the contract states in prose.

```ts
interface FieldSpec {
  name: string;
  type: 'string' | 'integer' | 'boolean' | 'string[]' | 'object[]';
  required?: 'always' | { when: { field: string; is: readonly string[] } };
  enum?: readonly string[];
  nullable?: boolean;
  /** Complete-set semantics: omitting leaves it alone, `[]` clears it. */
  set?: boolean;
  /** A display key is accepted here, and this is what one looks like — `TS-7`. */
  keyExample?: string;
  /** One line. Rendered into the tool schema and `--help` alike. */
  describe: string;
  /** The pitfall. Rendered into the long-form guide only — it will not fit in a tool schema. */
  note?: string;
  /** `steps`, `preconditions`. */
  fields?: readonly FieldSpec[];
}

interface EntityShape {
  entity: string;            // 'case'
  title: string;             // 'Test case'
  actions: readonly ActionSpec[];   // propose | edit | submit | delete, with their permissions
  fields: readonly FieldSpec[];
}
```

### 2.1 The renderers — also in the core, so the adapters cannot disagree

| Renderer | Feeds |
|---|---|
| `jsonSchemaFor(shape, action)` | the MCP tool's `inputSchema` object fragment |
| `optionSpecFor(shape, action)` | the CLI command's `OptionSpec` |
| `usageFor(shape, action)` | the CLI command's `usage` / `--help` body |
| `templateFor(shape)` | a filled worked skeleton — the `tcms_shape` tool, `tcms format` |
| `guideFor(shape)` | long-form markdown — the MCP **resource**, `tcms format --long` |
| `validateShape(shape, payload)` | generalizes `requireProposableCase` to every entity |

### 2.2 The check that stops this becoming a fifth copy

This is the part that matters, and the repo has paid for its absence five times
(CLAUDE.md: *a schema-wide invariant needs a computed check, not a careful reading*).

`shapes.contract.test.ts` parses `docs/specs/openapi.yaml` — the same move
`tierClaims.test.ts` and `matrixCopies.test.ts` already make against the roles spec — and asserts,
per entity, **both ways**:

1. every `enum` in the catalogue is exactly the contract's enum for that property;
2. every `required: 'always'` field is in the contract's `required` array, and vice versa;
3. every catalogue field names a real contract property, **or** is declared
   `surfaceOnly` with a reason (the display-key substitutions — `suite` for `suiteId` — which are
   the whole reason the catalogue is not generated from the contract).

That makes the catalogue **derived-and-checked** rather than a second source of truth. A contract
change that moves an enum reddens `packages/`, which is the existing behaviour of
`schemaFreshness.test.ts` and `citedFiles.test.ts` and the reason both work.

### 2.3 The three MCP carriers

1. **Enriched input schemas.** `testCase` and every other object argument become real nested JSON
   Schema. Costs no extra call: every host feeds `inputSchema` to the model at call time. Does not
   touch the 2KB *description* ceiling — that test measures `description` only.
2. **`tcms_shape`** — a new read tool, gate `none`, **no endpoint** (so §3.1's "no third composite"
   rule is not engaged: it fans out to zero reads, not two). Takes an optional `entity`; returns
   the template plus the action list. Live project vocabulary is *not* duplicated here —
   `tcms_orient` owns it, and the tool's description says so.
3. **`resources` capability** — `tcms://shape/{entity}`, serving `guideFor`. New protocol surface;
   `server.ts` currently advertises `tools` alone. Hosts vary in whether they surface resources,
   which is exactly why it is the third carrier and not the first.

### 2.4 The CLI carriers

4. **`tcms format <entity>` / `tcms shape <entity>`** — the same template and guide at a terminal.
5. **`OptionSpec` and `usage` rendered from the catalogue**, replacing the hand-written pairs. This
   is where the CLI gains enums and required-ness it has never had, and where a misspelled enum
   value becomes a usage error instead of a 400.
6. **`--file` payloads validated through `validateShape`**, generalizing the one check that exists.

## 3. What this also exposes, and what to do about it

**The CLI is a subset of the MCP surface and nothing says so.** 16 write commands against 27 MCP
arms. Missing from the CLI entirely: `case delete`, `case submit`, `suite delete`, `plan delete`,
`doc publish`, `run clone`, `review verdict`, `review resolve`, `work item` create/move, `artifact`
register/edit/status/delete, `report generate`.

**This plan does not close that gap** — it is a different slice with its own consent, key-handling
and output questions, and folding it in would triple the size. What this plan does is make the
asymmetry *visible*: `ActionSpec` declares which adapters expose each action, and a check reports
the difference rather than leaving it to be discovered. Closing it is a follow-up, and the
catalogue is what makes closing it cheap.

## 4. Phases

Each lands its own commit after its gate (`cd packages && npm run build && npm run lint --workspaces
&& npm run test:run --workspaces`), run on **shanks** via `~/bin/tcms-remote`, never locally.

| # | Phase | Gate |
|---|---|---|
| 1 | `core/shapes.ts` + renderers + `shapes.contract.test.ts`, **`case` only** | client suite |
| 2 | MCP carriers 1–3 for `case`; `tcms_shape`; `resources` capability; tool count 23 → 24 | mcp suite |
| 3 | CLI carriers 4–6 for `case` | cli suite |
| 4 | Extend the catalogue to the remaining entities and sweep both adapters | all three |
| 5 | Docs: surface spec §3/§3.1/§8 amendments, handoff `docs/handoff/253-*.md` + index row, `docs/agent-context/be-java.md` untouched (no backend change), verification queue rows | — |

Phase 1 is the one that decides whether the rest is worth doing: if the contract-conformance check
cannot be written cleanly, the catalogue is a second source of truth and the plan should stop.

## 5. Verification (§65)

Browser: **none** — no frontend change. Backend mutation checks: **none** — no backend change.
Rows are still owed for the MCP and CLI surfaces, which neither queue covers today; Phase 5 records
what a live pass would drive (`tcms_shape` against a real instance, a `resources/list` from a host
that surfaces them, `tcms format case` at a terminal) rather than running it.

## 6. Open questions — RESOLVED 2026-08-26, before implementation

### Q1. `tcms_shape`, and it is a separate tool rather than part of `tcms_orient`

The surface's verbs are `whoami · orient · search · open · list · doc_tree · triage · write ·
record · review · report`. No existing verb covers "tell me the shape of the thing I am about to
write", so any name is new; `tcms_shape` is picked because it is a noun that cannot be read as an
imperative (`tcms_format` reads as *format this text*).

**The decisive argument is not the name, it is that it must work with no project in scope.**
`tcms_orient` opens with `session.projectId()` and every one of its five reads is project-scoped.
`tcms_shape` has **no gate and calls no endpoint**, so an agent that has connected but has not yet
resolved a project can still learn the format — which is exactly the moment it needs it. Folding
this into `orient` would make the one tool that must work early depend on project scope.

It also keeps §3.1 unengaged: that rule governs tools fanning out to *more than one* read.
`tcms_shape` fans out to zero.

### Q2. `guideFor` goes in the core

The core already composes user-facing prose — `describeReach` and `describeRefusal` are sentences,
not data — so this is not a new kind of thing for it to hold. The deciding argument is the
divergence it prevents: the MCP resource and `tcms format --long` must serve the *same* guide, and
there is no shared layer below the core in which to put it. Two renderers would be the exact defect
the core exists to prevent.

Bounded, so this does not become a rendering library in `@tcms/client`: `guideFor` emits structured
markdown from structured data. **No terminal width, no colour, no styling** — the CLI's `output.ts`
stays the place presentation happens, and may decorate what it is handed.

### Q3. `optionSpecFor` CANNOT fully replace the hand-written specs, and the residue is systematic

Walking all sixteen CLI write commands, the flags with no entity field behind them fall into two
classes, and only one of them is ragged.

**Class 1 — write mechanics, and they are a pure function of `WriteDeclaration.kind`:**

| `kind` | Mechanics flags | Holds |
|---|---|---|
| `create` | `--idempotency-key` | **5/5** |
| `edit` | `--lock-version` | **4/4** |
| `state`, `delete` | none | **7/7** |

That is not a coincidence, it is §4.2 and §4.3 restated — the same two rules
`writeConformance.test.ts` already asserts over both registries. So mechanics flags are **derived
from the kind the command already declares**, not listed per command, and the conformance check
gains a third question it can ask of the same declaration.

**Class 2 — spelling, which the catalogue carries as a per-field CLI binding:**

- kebab-case: `--expected-outcome` ↔ `expectedOutcome`
- singular flag, plural field, `multiple: true`: `--tag` ↔ `tags`, `--decline` ↔
  `declinedConditionIds`, `--case` ↔ `caseIds`, `--precondition` ↔ `preconditions`
- **negation**: `--unfile` is the CLI's second flag for `suiteId: null`. `writes.ts:395` already
  records why the two adapters differ here — *"the CLI needed two flags to express this; here it is
  the shape the caller already sent"* — because JSON can say `null` and `parseArgs` cannot. The
  field carries `cli: { flag: 'suite', negate: 'unfile' }` and both spellings render from it.

**The one genuinely ragged flag is `--file`, and this slice does not straighten it.** It is on
`case propose`, `case edit`, `suite edit`, `doc create`, `doc edit`, `plan edit` — and missing from
`suite create`, `plan create`, `run create` and `result record`. `doc create` takes a whole body and
`suite create` does not, which reads as an accident rather than a decision. Adding it where it is
missing is a **widening of the CLI surface**, with its own questions (a `--file` create still needs
its idempotency key; a `--file` edit carries its own version and `notRead` exists for exactly that),
and it is not what "enrich the format" was asked for. So `--file` stays declared per command, the
catalogue records which commands have it, and a check **reports** the four that do not rather than
silently adding them. Closing it is a follow-up.
