# §48. The contract-hygiene computed checks — the debt five sessions recorded and none paid

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential**, the third slice since §45.

`docs/specs/openapi.yaml` is the one authoritative artifact in this repo with **no computed check
over it at all**. Every other catalogue-wide invariant here is machine-checked — `company_id` by
`SchemaConventionTest`, the role grants by `SeedDataTest`, the module seams by ArchUnit — and each of
those exists because reading the artifact top to bottom missed violations that scripting the
comparison found in seconds.

The contract's checks were recorded as "Not done" in **§42, §43, §45, §46 and §47**. Each of those
slices hand-applied the rules to its own paths and moved on, which is what makes the note decay: the
rule was followed exactly where someone was already looking.

## Why this slice and not the next feature

The orientation pass asked §46's question — *is this still blocked?* — and §28's — *does it have a
consumer?* Neither applies to a build gate, so the tiebreak was §47's own closing argument, which had
become unarguable: **the `ownerId` defect it shipped is this exact shape.** An invariant across a
catalogue that reading cannot verify, caught only by driving the app, after review had passed it.

## What the checks found

Written first, run before any repair. They failed for the right reason:

| Check | Violations on arrival |
|---|---|
| Every operation declares at least one 4xx | **24 of 49 operations** |
| Every object schema with `properties` declares `required` | **11 schemas** |
| `nullable: true` is never a sibling of `$ref` | 0 — a regression guard (see below) |
| Every dashboard `filter` key is a declared `GET /cases` parameter | 0 — §47 had just fixed the one |

**24 of 49 is the number that justifies the slice.** Half the API declared that it could not answer
a client error. `schema.d.ts` is generated from this file, so an undeclared 401 was a response the
generated types said *could not happen* — on endpoints where it is the single most likely failure.

The 11 missing `required` arrays were worse in a quieter way, because `required` does not descend —
not into a nested object, not through an `allOf` branch, not into an array's `items`. **29 generated
properties flipped from optional to required** when they were added, including `PageMeta`'s
`page`/`size`/`total` and the case list's own `items`/`facets`: the biggest screen in the product was
typed such that no consumer could read a page total without a null check on a value every response
carries. The contract already held three hand-written comments about having hit exactly this
(`CurrentUser`, `TestCase`, `TestPlan`), each added after `tsc` caught it at one call site.

## Every response set came from the controller, not from a template

The repair is only worth having if it is true. Each operation's 4xx set was read off the
implementation rather than pattern-stamped:

- Project-scoped GETs got **401 + 404** because every one of them genuinely answers
  `ResponseEntity.notFound()` for an unreachable project — verified in `ProjectController`,
  `SuiteController`, `CaseController`, `ConditionController`, `DashboardController`.
- **`GET /role-assignments` got 401 only.** Its javadoc states outright that an unadministerable
  project answers `[]` rather than 404, *and cites the contract's silence as the reason*. Adding a
  404 would have made the contract disagree with a decision already argued in the code. This is the
  one place the mechanical repair would have been wrong, and it is why the sets were derived rather
  than generated.
- Writes (`cases/bulk`, `reports/generate`) got **400 + 401 + 403 + 404**, matching the gate the
  implemented sibling writes use.

## Two exceptions, both stated with their reason

The `required` check carries an exception list on the `SchemaConventionTest` model — **an entry means
"no property of this schema is always present"**, which is a strong claim and almost always wrong:

- **`CaseConditionInput`** — genuinely exclusive: a condition is *either* a library reference *or* an
  inline body. No property is common to both shapes.
- **The accept-invitation request body** — `password` is sent only when the invitee has no account
  yet. A returning user accepting an invitation to a second company sends an empty body
  **deliberately**, because holding an invitation must not confer the ability to overwrite an
  existing credential. `required: [password]` would have described the more dangerous of the two
  flows.

## The check that passed immediately, and why it was kept

`nullable: true` next to a `$ref` is **silently ignored** by the generator — the sibling is not
merged, so the property generates as non-nullable and the type claims a value the server sends as
null can never be null. Nothing fails at build time; it fails at one call site, at runtime.

This contract has been repaired for it **three times by hand** — `TestCaseSummary.owner`,
`CaseVersion.author` (§35), `Execution.assignee`/`status` — each with a comment explaining the fix
and none of them preventing the next occurrence. The check finds nothing today, which per TDD means
it proves nothing until it can go red, so it was **mutation-verified rather than trusted**.

## The §47-shaped check, and the evidence it is not redundant

`DashboardEndpointTest` gained the check §47 said would have caught its own defect: every `filter`
key any tile or segment emits must be a query parameter `GET /cases` declares. Spring **ignores** an
unbound query parameter, so an undeclared key opens an unfiltered superset under a heading naming the
filter — correct number on the tile, wrong list behind it, nothing thrown.

It reads the emitted keys from a live response rather than from a list written in the test: a literal
list is a second place to update, and it agrees with a wrong implementation the day someone adds a
key to only one of them.

**The mutation proved it is not covered by what was already there.** Removing `ownerId` from the
contract failed the new check and left **26 of the other 27** dashboard tests passing. The existing
count-vs-link assertions structurally cannot catch this: they compare a tile's count to the list its
filter returns, and those agree perfectly whenever the fixture has one owner, one feature, or one of
anything — which is the normal state of a freshly provisioned project.

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL in 2m 13s
           873 tests, 0 failures, 0 skipped        (869 + 4)
frontend:  npm run gen:api → regenerated, 74 insertions / 30 deletions
           npm run build   → ✓ built in 581ms
           npm run lint    → oxlint, exit 0, no output
           npm run test:run→ 16 files, 261 tests passed  (unchanged)
```

**All four checks mutation-verified**, each killed by re-introducing the defect it names: dropping
one `'401'`, dropping `PageMeta`'s `required`, writing a `nullable: true` beside a `$ref`, and
deleting the `ownerId` parameter.

The frontend needed **no source changes** — the tightened types only *removed* obligations, and the
mock handlers are typed through `api/types.ts` back to the generated schema, so a fixture that failed
to emit a newly-required field would have failed `npm run build`. None did.

## Deliberate limits, stated

- **The contract declares `openapi: 3.1.0` while using 3.0's `nullable: true` — 143 times.** In 3.1
  `nullable` is not a keyword at all; strict tooling ignores it. `openapi-typescript` honours it in
  compatibility mode, which is why every nullable field generates correctly today and why this has
  never surfaced. **Left alone on purpose**: the 3.1 spelling is `type: [string, 'null']`, converting
  143 sites is a diff no reviewer can read, and it would land in the same commit as checks whose
  whole value is being reviewable. It is a slice, not a footnote — and until it is done, the
  `nullable`-beside-`$ref` check is what keeps the compatibility path honest.
- **"At least one 4xx" is a floor, not a specification.** The check cannot tell that a path declaring
  only 400 is missing its 401 — it verifies the *catalogue* rule, and the per-operation set is still
  a judgment call made against the controller. The next rung is asserting the declared set against
  the statuses the endpoint tests actually observe, which needs every endpoint tested for every
  status first.
- **`Problem.errors` stayed optional rather than becoming nullable-and-required.** `ApiProblem` is
  serialized `NON_NULL`, so a 404 omits the key rather than sending null — optional is what the
  server does, and "always present, sometimes null" would have been the wrong description.

## Not done

- **`documentation`, `reporting` and `work` writes still run on MSW** — `/doc-pages`, `/artifacts`,
  `/reports`, `/work-items`, plus `cases/bulk`, `cases/{id}/history` and `suites/{id}/history`.
  Unchanged by this slice, though all of them now declare their errors.
- **Teams and memberships** remain the next write surface with no endpoints (§39, §45, §46, §47).
- **`data-testid` on MUI `TextField` lands on the root `<div>`** (§44) — untouched.
- **No browser pass this slice, and it needs none**: nothing rendered changed. The frontend diff is
  the generated `schema.d.ts` and nothing else, which the three frontend gates cover completely.
  Recorded explicitly because "driven in a browser" is otherwise this project's standing expectation
  (§6 lesson 5) and its absence should read as a decision, not an omission.
