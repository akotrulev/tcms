<!-- orientation-cache v1 -->

# tooling-engineer — orientation cache (TCMS)

**Last verified:** 2026-08-27 (**§255 T2** — the carriers moved onto the unified step model;
plan pack at `docs/plans/001-unified-step-model/`). Previous pass: §254, the surface review executed
as a slice, report at `docs/audits/2026-08-26-mcp-cli-surface-review.md`. **Baseline commit:** see
`git log`; **trust the body over this header** and re-grep anything a later commit could have moved.

Seeded by §254 because the review that produced it had to recover its orientation from scratch —
there was no cache — and the next run should not pay for that twice.

## Where the surface is

| | |
|---|---|
| The contract document | `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` — **authoritative and unusually complete.** §2 is the one-permission-per-write-path rule the tool count is derived from, §3.3 the consent boundary, §4.5 the refusal boundary, §4.6 the output budgets, §6 the CLI, §8 the seven computed checks. |
| The REST contract | `docs/specs/openapi.yaml`. Both surfaces are **hand-written against it, never generated** — a tool argument takes a display key where the API takes a UUID — which is why every slice here adds a pin rather than a generator. |
| Packages | `packages/{client,cli,mcp}`, one workspace root at `packages/package.json`. **No stack builds from the repo root.** |
| Distribution | `docs/specs/client-distribution.md` — no registry holds these; the instance serves tarballs at `/downloads`, and `pack:release` bundles `@tcms/client`. |

## Seams — what each thing is the ONE place for

- **`client/src/core/shapes.ts`** — the shape catalogue (§253). Eight entities, six renderers. The
  one answer to *what fields does this object take*. `READ_ENUMS` beside it is the same thing for
  the values a caller **filters** by. `spellableInArgv` is the one place that answers *can argv
  carry this* — `object[]` and, since §255, `string[][]`.
- **`STEP_FIELDS` in that file is the whole step shape, for BOTH lenses (§255).** A GHERKIN case is
  step rows with a `keyword`, not a text blob: `TestCaseInput.gherkinSource` is gone, `steps` is
  required under either `stepFormat`, and `docString` / `dataTable` are the two Gherkin block
  arguments. Every carrier renders from here — there is no second copy of the step shape in `cli/`
  or `mcp/`, and adding one is the defect.
- **`client/src/core/refusal.ts`** — the *sentence*, and §4.5's 404-vs-403 boundary. Its failure is
  a security finding, not a defect.
- **`client/src/core/outcome.ts`** (§254) — the *classification*: a closed `FailureCode`, with
  `retryable` **stated rather than inferred**. Both adapters render it; neither composes its own.
  The MCP as `structuredContent` under `RESULT_SCHEMA`, the CLI as ten exit codes.
- **`client/src/core/mutation.ts`** — `Idempotency-Key` / `If-Match` per write kind.
- **`mcp/src/session.ts`** — why the server is built **per session**: `instructions` is a
  constructor argument, sent once and never re-sent, and it carries that caller's capability
  summary. The strongest single argument in the packages; do not collapse it into a shared server.
- **`mcp/src/tool.ts`** — `ToolDefinition` / `ToolArm`. `kind` drives the consent gate, `targets`
  the confirmation's wording, `permissionFor` the per-input permission (§254, `tcms_review_verdict`).
- **`mcp/src/instructions.ts`** — the capability summary, budgeted in **bytes**, degrading by
  dropping whole sections rather than truncating mid-key.

## The checks, and what each one would catch

`writeConformance.test.ts` (one per package) — §8.1 gates and §8.3 headers, both resolved from the
URL the arm **actually sent**. `writes.test.ts` — registry declarations, §8.2's merge rule against
`roleMatrix()`, and the catalogue↔tool-schema pin. `format.test.ts` — the catalogue↔command-flag
pin. `shapesContract.test.ts` — catalogue↔`openapi.yaml`, five questions, mostly both ways; the enum
pin walks NESTED fields since §255, which is what pins `steps[].keyword` to `StepKeyword`.
`gherkinLensCase.test.ts` (one per adapter) — create → edit → read of a Gherkin case through a
contract-conformant `fetch` double, and the negative assertion that no payload carries the retired
scenario field.
`guideCopies.test.ts` (one per package) — the shipped `/help` prose against both registries.
`citedFiles.test.ts` / `tierClaims.test.ts` / `matrixCopies.test.ts` — cross-corpus prose checks
that redden the **packages** job from `frontend/` or `backend/` edits.

## Conventions that are not obvious

- **Every tool description is truncated at 2 KB by the host**, and a test measures all of them. The
  last sentence is where caveats go, so a long description loses exactly the part that matters.
- **`tools/list` is permission-filtered**, and an unlisted tool refuses **without confirming it
  exists** — it must not become an enumeration oracle.
- **A narrowly-held key is listed with its narrowing composed into the description**, not hidden:
  hiding would withhold something the caller can do.
- **Nothing on an `edit` is required** — both adapters read the row and send back what was not
  named (`core/rewrite.ts`), because the PATCH is partial by verb only.
- **The CLI is a declared subset of the MCP surface**, per-action in `ActionSpec.cli` / `.mcp` and
  printed by `tcms format`. Do not "fix" the asymmetry by inventing commands.

## Volatile

- `@modelcontextprotocol/sdk` **1.30.0** (`packages/package-lock.json`). `outputSchema` and
  `structuredContent` both require ≥ 1.13; §254 depends on them.
- Environment: `TCMS_URL`, `TCMS_TOKEN`, `TCMS_PROJECT`. Under `--http` the credential is each
  request's `Authorization` header instead, **re-checked per request since §254**.

## Known unknowns

- **Nothing on this surface has ever been driven by a real MCP host.** §253 recorded it and §254
  did not change it: `tcms_shape`, `resources/list` and now `structuredContent` are all built and
  unverified. §255 corrected half the second sentence — the packages DO have a mutation queue,
  `docs/verification/packages-and-frontend-mutation-checks.md`, split out on 2026-08-22; what they
  still have no lane for is a live host, and `§254 H-9` sits in the browser queue rather than one
  of their own.
- **The new step shape has never reached a real server either** (§255 T2). Both adapters are driven
  against a stubbed `fetch`, which answers 200 to whatever arrives — so a payload this surface
  composes wrongly for `docString` / `dataTable` would look identical here. `T3` is the parser and
  `T7` the round trip; neither had landed when this was written.
- `tcms_list_suites` still returns a bare upstream `Suite` on its single-suite branch — the
  `mcp-builder` anti-pattern by the letter, left because splitting a detail read out is a tool-count
  and gate decision.
