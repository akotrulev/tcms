# §170 — the population the rule could not see, and the two inputs nothing read

**One phase, `backend/` only: no migration, no contract change, no frontend or `packages/` source,
and two components deleted from one production record.** §169's named next slice — *"the inline
object-schema population §165 named on its way out — shapes declared on a property rather than as a
named schema, which `objectSchemas()` skips and no pairing rule sees. That population is larger than
the named one."*

Both halves of that sentence turned out to be wrong in the same direction, and the correction is the
slice. The inline population is **25 shapes**, not larger than the named one. The population nothing
compared was **45 named schemas** — a third of the contract's object shapes — and they were invisible
not because they were anonymous but because of how the rule that guards them asks its question.

## The finding is that the pairing rule proposed rather than enumerated

`everyNameMatchedRecordIsPairedOrExplained` reads as a population rule and is not one. It reports a
schema only when `candidatesFor` finds a production record called `Foo` or `FooView` — so the set it
can speak about is *schemas whose server type happens to share their name*, and a schema whose record
is named anything else was invisible to it **and to the member comparison beside it**, which iterates
the pairing map rather than the contract.

That was 45 of 129 object schemas, and the misses are not a random third. They are almost exactly the
write side:

| Contract schema | The record binding it |
|---|---|
| `TestCaseInput` | `CaseUpdateRequest` |
| `SuiteInput` | `SuiteWriteRequest` |
| `TestRunInput` | `RunWriteRequest` |
| `RoleAssignmentInput` | `RoleGrantRequest` |
| `RepositoryDashboard` | `DashboardView` |

**The naming convention this codebase uses on purpose is what hid them.** A request body's schema is
named for the wire and its record is named for the act, which is the right name in each place; the
rule then silently had nothing to say about either. And the write side is where the consequence is
worse than a dead branch in a client: an undeclared *response* property is a value no generated type
can name, while an undeclared *request* component is a value Jackson silently binds and something
may act on.

`everyObjectSchemaIsPairedOrExempt` asks the whole question — for every object schema, a pairing or a
recorded reason — and `RESPONSE_RECORDS` went from 79 entries to 125.

## What the enlarged comparison found: two inputs nothing read

Across 45 new pairings, **one** divergence: `IngestionRequest` carries `branch` and `commitSha`,
`IngestionInput` declares neither, and **nothing in the backend reads either component.** Not
`IngestionService`, not `IngestionEdit`; `TestRun` does not even map the `test_run.branch` and
`test_run.commit_sha` columns `V1` created. A CI job setting them got a 2xx and a run with no branch
on it — the worst of the three available behaviours, since refusing would have been honest and
storing would have been useful.

**Both components are deleted and the columns stay.** This is not the feature being cancelled: Git
traceability is M4 (plan §3 phase 2; §6q's table lists `commitSha` / `branch` beside `releaseVersion`
as the field naming which code ran, and the ERD annotates both columns with that milestone). The
slice that builds it declares the two properties, maps the two columns and adds the components back
*in the same commit*, at which point they are read. Deleting them changes no behaviour today — Spring
Boot leaves `FAIL_ON_UNKNOWN_PROPERTIES` off, so a CI job still posting `branch` still gets its 2xx
and the value is still dropped. **What changes is that the drop is no longer disguised as a
binding.**

## The exemptions, and why there are only two kinds

Two named schemas are paired with nothing, and both are the same shape: **reachable only as an
`allOf` branch**. `PageMeta` is `$ref`'d twice, both times as the first branch of a paged list
response; `TestPlanSummary` has been `TestPlan`'s first branch and nothing else since §100 widened
the plan list from the summary to the whole plan. Nothing sends either one alone, so no record's
components are *its* members — the composition is what travels, and `propertiesOf` already compares
that. Pairing a branch with the record serving the composition would report every property of the
*other* branches as SERVER-ONLY: a rule generating its own false positives.

`BRANCH_ONLY_SCHEMAS` is therefore two entries and **the build re-derives the property rather than
trusting the sentence** — `schemasReferencedOutsideAllOf()` reads the document, and the day something
`$ref`s one of them as a whole body the exemption reports itself as inert. That is §161's rule
applied at the point of writing a new ignore list instead of two slices later.

There is deliberately no third escape hatch. Every other object schema in this contract is something
a handler returns or a handler binds, and if one genuinely is not, that is a schema nothing
implements and **the schema is the finding**.

## Phase 2 — the inline half, keyed by location

`objectSchemas()` stops at `components.schemas` and states why: *"an anonymous shape has no name to
correspond by, so there is nothing for a pairing map to key on."* True of the name, false of the
correspondence. `TestCaseSummary.lastResult` has a **location**, the location is as stable as the
schema around it, and `LastResultView` serves it exactly as if it had been named.

`inlineObjectSchemas()` returns 25 locations — 15 properties or array items of a named schema, 10
inline request or response bodies keyed by operation (`POST /auth/login (request)`,
`GET /projects/{projectId}/cases (200)`). Nineteen are paired, two through package-private owners,
and the four remaining are `multipart/form-data` forms.

**The traversal's one non-obvious rule is what makes it a 25-entry population instead of a 55-entry
one.** This contract writes every nullable reference as `allOf: [{$ref: UserRef}], nullable: true` —
`nullableRefIsWrappedInAllOf` requires it — so a check that judged by the properties a node
*resolves* to sees `UserRef`'s four members at twenty-two positions and demands a record for each of
them. They are one schema referenced twenty-two times. The test is therefore what a node **and its
inline branches** declare *themselves*; a paged response passes it on its second branch, and its
recorded member set is still the union through `allOf`, because the record serving it carries the
paging fields too.

The inline comparison found **no** divergences. That is the count left after §164–§169 spent six
slices closing this defect class where the rule could see it, and the three worst instances of it —
`lastResult`, `coverage`, `relatedDocuments` — were all at inline positions, found one at a time by
hand.

Two claims elsewhere in the file were disproved on the way and corrected where they were found:

1. **`RESPONSE_RECORDS`' list value** — *"a schema can legitimately be served by two records that
   must stay identical … none does today — that is a result, not an assumption."* `Attachment` does,
   and did on the day that sentence was written: `repository.CaseAttachmentView` and
   `documentation.DocPageAttachmentView` are seven identical components in two modules that may not
   import each other. The claim was made without looking, because the schema was outside the
   name-matched population.
2. **`HOMONYMS`' two `TeamRef` entries** — both said the record serves an inline shape *and is
   therefore compared to nothing*. Both are now compared at their locations. An entry there still
   means "this record does not serve the schema its name matches", which is the ambiguity the map
   exists to resolve; it no longer means the record is unchecked.

## One comparison, two rules

`compareMembers` is called by both rules. Two implementations of *"do these name the same members"*
is how the two answers come to differ — `CLAUDE.md`'s shared-layer rule, arriving at a test — and
`MEMBER_DIVERGENCE_BY_DECISION` stays **one map** for the same reason: an entry is an argument about
a member, and that argument does not change with whether the shape around it has a name.
`isInlineKey` splits it by the shape of the key rather than by a second map.

The map is still `Map.of()`, now across both populations and 144 pairings.

## Tests

- **`ContractConventionTest`** — +3: `everyObjectSchemaIsPairedOrExempt`,
  `everyInlineShapeIsPairedOrExempt`, `inlineShapesAndTheirRecordsNameTheSameMembers`. The existing
  member rule was refactored onto `compareMembers` and otherwise unchanged.
  `everyExemptionStillExemptsSomething` gains the `BRANCH_ONLY_SCHEMAS` arm.
- **`OpenApiContract`** — +3 methods: `inlineObjectSchemas()`, `schemasReferencedOutsideAllOf()`,
  `declaresPropertiesItself()`. No existing method changed.
- No production test changed: deleting `IngestionRequest`'s two components broke nothing, because
  nothing constructed the record and no test body carried either field.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL, 2528 tests / 515 classes / 0 failures.** §169 was 2525 / 515 — **+3 and no new file** |
| `frontend` · `packages` | untouched — no contract change, so neither `schema.d.ts` moves |

## Queues

**Mutations are RECORDED AND NOT RUN for the product one; the rule mutations follow §165's
precedent** — a mutation that only breaks a rule does not exercise the product, so running it is not
the §65 exception §72–§76 wrongly took. `backend-mutation-checks.md` gains `§170 PR-1`…`§170 PR-4`, three of them run.

**`PR-2` was wrong and that is what running it bought.** It predicted two rules reddening; only the
member comparison did. `BRANCH_ONLY_SCHEMAS` re-derives whether anything `$ref`s a schema whole, and
pairing it does not change that — so a schema claimed by a pairing *and* excused as branch-only
passed the accounting rule, which unions the three maps, while the two claims contradicted each
other. `everyObjectSchemaIsPairedOrExempt` now asserts the three are **disjoint**, and the re-run
reddens both. A rule written and mutated in the same slice is the cheapest place to find this; the
alternative is a later reader trusting an exemption that a pairing had already overruled.

## The next slice

- the **browser queue**, still never run since §110;
- **`TeamRef`** — three types, two of them a contract change before they are a Java one, and §170
  narrowed the question: the two inline shapes are now *compared*, so what is left is whether the
  three should be one, not whether they agree;
- **the multipart forms** — `BOUND_AS_REQUEST_PARAMETERS` is four entries, and the check they forgo
  is real: nothing compares a form's declared parts to the `@RequestParam` names the handler binds,
  which is the shape `ContractParameterBindingTest` already fixes for query parameters one media
  type over;
- **Git traceability (M4)** — now with a named starting point: two properties, two columns, two
  components, one commit;
- **`OUTDATED_SPEC_CHANGED` / `UNLINKED`**, derivable from `case_doc_link`;
- the **read-only-archive** decision §159 deliberately did not take;
- **`useLinkBlock`'s missing screen** — a hook with no caller, §169's second recorded gap.
