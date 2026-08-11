# §165 — the members nobody compared, and three arguments that blamed a module for not existing

**2026-08-11 · one phase · `backend/` + contract + both `schema.d.ts` copies: no migration, no
frontend or `packages/` source, one generated type changed.**

§164 left this on its way out and this is it: **`RESPONSE_RECORDS` is four entries and one
direction.** It mapped four schemas to four records and checked that every *required* property had a
component — so a component the schema does not declare passed, which is the direction `instance`
went, and every schema outside the four was unchecked in both.

The map is **79 pairings** now and the rule runs **both ways**. Its first run found three
divergences in a population of 78 name-matched pairs, and **75 of the 78 already agreed member for
member** — which is the result that made the generalisation cheap rather than the finding.

## The one the check was written to catch

**`TestPlan.keySeq` is on the wire and the contract declares it nowhere.** `TestPlanView` has
carried the component since §74; all seven operations returning a `TestPlan` send the key; no
generated client can name it. SERVER-ONLY is the dangerous direction for the reason §161 recorded at
the enums — Jackson writes the component's name, the generated type has no such property, and
TypeScript checks nothing at runtime, so the value sits in the body being invisible on both sides.

**§100 widened this exact schema by hand and missed it.** That slice found a live three-way
divergence on `TestPlan`, changed the list response from `TestPlanSummary` to the full `TestPlan`,
and wrote *"widening the declaration changes no server and no client; it makes the spec say what
three implementations already do"* — which was true, and left `keySeq` behind, because a
hand-comparison looks for the field somebody is arguing about (`exitCriteria`) and a both-ways rule
looks at all of them.

Declared, required, on `TestPlanSummary` where `TestCaseSummary.keySeq` has been since M0. Both
`schema.d.ts` copies gain `keySeq: number` and that is the slice's only generated-type change.

## The three that blamed a module for not existing

The other two divergences are contract-only — a declared property nothing sends, which costs a dead
branch rather than a wrong screen. Both were argued, and **the arguments had expired in the same
way §160's had**:

| where | said | actually |
|---|---|---|
| `TestCaseSummaryView` header | *"the `execution` module does not exist — nothing can record a result, so no case has one … it appears when `execution` does"* | `execution` is **119 files**; results recorded since §117, ingested from CI since §119 |
| `TestCaseDetailView` header ×2 | the same of `execution`, and of `documentation` that *"that module does not exist. Nothing can create a link, so no case has one"* | `documentation` is **57 files** including `DocLink`, `DocLinkRepository` and a `POST …/blocks/{blockId}/links` |
| `openapi.yaml`, `TestCase` | *"it is sourced from the `documentation` module, which does not exist"* | same |

**The live obstacle is the module boundary, and it was recorded correctly the whole time** — in
`repository/package-info.java`, which says outright that the rule keeping `suite_run_summary` a read
model in `reporting` is *"what keeps `TestCaseSummary.lastResult` off the wire today rather than
reaching for `test_execution` directly."* Two arguments for one absence, forty lines apart in the
same module, one live and one expired, and **the expired one is the one a reader of the field meets
first.**

This is §161's cost restated: an argument that misnames the obstacle deters the repair. *"The module
does not exist"* reads as **wait**; *"the module boundary forbids the join"* reads as **design a read
model** — §3 lesson 4's shape, which is a slice somebody can start today.

### What the stale arguments were covering, and it is not only prose

- **Every case in the product reads "Never run."** `CaseGrid`'s Last result column renders a missing
  `lastResult` as "Never run", the case detail page renders the same value the same way, and the
  server has never sent the key. The argument said that was "exactly true". It stopped being true
  when `execution` shipped, and nothing looked — §151's `release_sort_key` shape with the sign
  flipped: not a stored value nobody reads, a rendered value nobody feeds.
- **A doc link is never shown on the case it links to.** `POST …/blocks/{blockId}/links` creates
  one; `CaseDetailPage` reads `data.relatedDocuments ?? []` and renders an empty panel.
  `DocBlockView`'s header **struck** the "nothing can create a link" clause on its own side when
  that endpoint shipped. The `repository` side never heard — one claim, struck in one module and
  left standing in another, which is what a hand-maintained argument in two places costs.

Both are pinned in `MEMBER_DIVERGENCE_BY_DECISION` with the real obstacle and with what the gap
costs on screen. **Neither is closed here**: each needs a read model fed by events, which is a
behaviour change on a cross-module path and wants its own tests. They are the recorded next slices,
argued correctly for the first time.

### The control group

`DocBlock.coverage` is the fourth divergence and the one that **holds** — the only one of the four
whose argument was *revised when its constraint moved* rather than left to expire. Its header
strikes the old reason in place and states the live one. That is the same both-directions discipline
the maps in `ArchitectureRulesTest` and `ContractEnumTest` have, applied by hand in prose, and it is
why this slice reads as three findings rather than four.

## The check

`ContractConventionTest`, **+2 rules, −1, no new class**. No Spring context and no database, like
the rest of that file.

- **`everyNameMatchedRecordIsPairedOrExplained`** — the population rule, and the part that did not
  exist in any form. A production record whose simple name matches a named object schema (`Foo` or
  `FooView`) must be paired, recorded in `PAIRED_ELSEWHERE`, or recorded in `HOMONYMS`; a pairing
  naming a schema the contract does not declare fails too. The name match **proposes**; a line in
  the map is a human **accepting**, and the class literals then hold it — rename either side and the
  file stops compiling.
- **`pairedSchemasAndRecordsNameTheSameMembers`** — the two-way member comparison, with
  `MEMBER_DIVERGENCE_BY_DECISION` pinned in **both** directions: an entry whose divergence has been
  closed fails the build. That property is why `ArchitectureRulesTest`'s and `ContractEnumTest`'s
  maps did not rot while §162's one-directional exemptions did.
- **`requiredPropertiesHaveAComponent` is deleted, strictly contained in the second.** A required
  property with no component is a schema-only member. §66's history — `createdBy` absent from
  `TestCaseDetailView` for two slices, an absent key read as `null`, a Test Engineer refused their
  own case — is kept verbatim in the new rule's failure message, because that is the sentence the
  next author needs and the rule name is not.

`OpenApiContract` gains `propertiesOf` (the `allOf`-resolving superset of `requiredOf`) and
`objectSchemas`, narrowed to **component-level** for `enumSchemas`' measured reason: the document
holds many more object shapes inline on a property, and an anonymous shape has no name to correspond
by.

### It reads source text for names, and does not import a class graph

The population rule needs *names*, not types, so it scans `src/main/java` for `record X(` rather
than importing an ArchUnit graph. That is not thrift: a graph of this codebase is the largest single
object a test here allocates, and a **third** one held live is what took the worker's 512m heap to
522685K and hung the build with nothing failing and nothing printed (§161). The map's values are
class literals, so the half that matters is checked by the compiler and not by the scan.

### Two owners this file cannot name, and the visibility was left alone

`BulkResultView` and `ReportDefinitionView` are package-private. Neither argues for it and both look
incidental beside their public siblings — **and widening a production type so a test can name it is
a production change made for a test**, which this slice is not about. They are resolved by name in
`PACKAGE_PRIVATE_OWNERS`, and the cost is stated rather than hidden: those two lose the compile-time
half of the guarantee. A rename still fails, as a `ClassNotFoundException` rather than a compiler
error, which is later and noisier and is the whole difference.

## `TeamRef` is three types, not two — and §147's next-slice argument does not transfer

`TeamRef` is the entry that proves the pairing is human knowledge: **three production records carry
the name and only one serves the schema.**

| record | serves | shape |
|---|---|---|
| `project.TeamRefView` | the `TeamRef` schema, via its single `$ref` (`ReviewAssignee.team`) | `{id, name}` |
| `identity.RoleAssignmentView.TeamRef` | `RoleAssignment.team`, an **inline** object | `{id, name}` |
| `auth.CurrentUserService.TeamRef` | `CurrentUser.teams`, also inline | **`{id, name, isLead}`** |

§147 named `TeamRef` as a consolidation candidate on the grounds that *"it is two copies … the same
argument that moved `UserRef`"*. There are three, they do not all serve the same schema, and one is
**not the same shape**. Collapsing the first two is a *contract* change before it is a Java one —
the inline object would have to become a `$ref` — and the third has a component the schema does not
declare, which any consolidation has to answer for. The standing candidate keeps its number and
loses its stated size; the two homonyms carry the sentence that tells them apart, and those entries
are themselves checked for staleness.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL (8m 09s), 2458 tests / 506 classes / 0 failures / 0 skipped.** §164 was 2457 / 506, so this is **+1 net and no new class**: two rules added, one deleted. Counted off `build/test-results/test`, not off the console summary. |
| `frontend`: `npm run build` · `npm run lint` · `npm run test:run` | clean · **0 warnings** · **1214 across 93 files** (unchanged — no frontend source moved) |
| `packages`: `npm run build` (in order) · lint · `test:run --workspaces` | clean · clean · **229** (66 + 85 + 78) |
| both `schema.d.ts` | regenerated, **byte-identical to each other**, diff is `keySeq: number` on `TestPlanSummary` and nothing else |

**Both directions of both rules were mutation-tested before this was written**, and all four are in
the queue below with their results. The one worth naming here is `§165 RC-1`: reverting the `keySeq`
declaration reproduces the slice's own finding as `SERVER-ONLY`, which is the property §154's
headline row still lacks.

## Queues

Two rows reach a screen and **neither is closed by this slice**, so they are recorded as what a
later pass must observe rather than as work done: the case grid's "Never run" and the case detail's
empty documents panel are both *correct renderings of a key the server does not send*, and a browser
pass that does not know that will read them as working. See
`docs/verification/frontend-browser-checks.md` (`§165 RC-1`, `§165 RC-2`) and
`docs/verification/backend-mutation-checks.md` (`§165 RC-1`…`RC-4`). **None run** beyond the four
mutations, which are checks over checks and are marked as such.

## The next slice

The two product gaps this slice named and did not close, now argued at the right obstacle:
**`TestCaseSummary.lastResult`** and **`TestCase.relatedDocuments`**, each a read model in
`repository` fed by an event from `execution` / `documentation` (§3 lesson 4). `lastResult` is the
larger of the two and the one a user meets on every case row.

Otherwise unchanged: the browser queue, `TeamRef` (three types — see above, and the argument for
consolidating it is now weaker than §147 recorded), the three remaining health derivations, or the
read-only-archive decision §159 deliberately did not take.

**What this slice did not examine** is the same question one level out again: `RESPONSE_RECORDS`
covers the **named** object schemas, and `objectSchemas()` deliberately skips every shape declared
**inline on a property**. Two of the three `TeamRef` records serve one of those, and nothing compares
them to anything. That population is larger than the named one and has no name to key on, which is
exactly the argument `enumSchemas()` made about its own 44 inline enums — true of the *keying*, and
it says nothing about whether the drift is there.
