# §27. Backend M1, slice 6 — the case detail, and the SPA's order defect the mock could not show (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**. Closes
§26's next-step item 1: **`GET /projects/{projectId}/cases/{caseId}`** — the last read the
`repository` module owed the SPA, and the largest response in the product.

### The headline: §6f's condition inheritance is implemented, and it has exactly one implementation

The case detail is the first endpoint that had to *resolve* anything rather than filter it. Its
condition lists are assembled by walking the case's suite ancestry, and the order is `V1`'s own
comment made real: **inherited walking `suite.path` upward, outermost folder first, then the case's
own, deduped by library condition.** All of it lives in one class (`CaseConditions`) because a second
implementation of a resolution order is a second answer to "what must a tester do before step 1".

| Path | Purpose |
|---|---|
| `repository/CaseConditions` | **The §6f resolution order.** Ancestry walk, opt-outs, dedupe, both kinds. |
| `repository/CaseConditionRefView` | The contract's `CaseConditionRef`, from a case's viewpoint. |
| `repository/ConditionKind` | `PRE`/`POST` — what an *attachment* is, vs `ConditionType`'s what an entry *may be*. |
| `repository/CaseCondition · CaseConditionRepository` | The case's own attachments. |
| `repository/CaseConditionOptout · …Id · …Repository` | The opt-out beside the inherited marker. |
| `repository/TestStep · StepKeyword · TestStepView · TestStepRepository` | The steps, both formats. |
| `repository/CaseAutomationTag · …Repository` | Selection tags — *not* `case_tag`, per §6c. |
| `repository/CaseSummaries` | **Extracted from `CaseService`** — see below. |
| `repository/DimensionKeys` | The three dimension keys, now that there are two readers. |
| `repository/TestCaseDetailView · CaseDetailService` | The response and its service. |
| `repository/TestCaseRepository` | `findLiveInProject` — the second gate, *inside the query*. |
| `db/migration/V10__suite_condition_suite_index.sql` | The index V1 forgot, again. |
| `repository/CaseDetailEndpointTest` | **New, 39 tests.** |

### The grid's renderer is now shared, and that is the point of `CaseSummaries`

`TestCase` in the contract is `allOf: [TestCaseSummary, …]`, so the detail owes **every** field the
grid owes. Rebuilding that inside a detail service is how "a hundred consistent pages become a
hundred slightly different ones" — and the specific failure is quiet: the grid drops a **retired**
classification value, a second implementation renders it, and one case shows one Test Type in the
list and another on its own page. Nothing errors and neither screen reveals which is right. So the
rendering moved out of `CaseService` unchanged — same code, same comments — and both callers use it.
`CaseService` keeps only what a *list* has: scope resolution, paging, facets.

### Two path segments are two gates, and the second one is the one that is easy to miss

`ProjectService.readVisible` answers "may this caller see this project", borrowed unchanged as
always. `TestCaseRepository.findLiveInProject` answers the question a **two**-segment path raises
and a one-segment path does not: **is this case in that project.** Without it,
`/projects/{granted}/cases/{ungranted}` serves the ungranted case's full text — title, steps,
conditions, owner's name and email — and **every cross-company test in the suite still passes**,
because the row genuinely is in the caller's company. It is the §24/§25 persona-3 lesson one level
deeper: the leak is *inside* one tenant. Both failures, plus a soft-deleted case and a nonexistent
one, collapse into one bodiless 404 — measured at four bytes of nothing each, because a different
content length is a distinguishable answer too.

### Three decisions that are decisions, not omissions

1. **A condition attached to the case's *own* folder is `inherited: true`.** The case did not attach
   it, and editing it still reaches every sibling — which is exactly what the SPA's warning is for.
   Marking it local would remove the warning from the commonest case there is: the standing rule on
   the folder the author is looking at.
2. **The two kinds dedupe separately.** "Reset the test payment data" inherited as a `PRE` and
   attached by the case as its own `POST` is two actions at two times — precisely what a `BOTH`
   library entry exists for — and one shared dedupe silently drops the second.
3. **`severity` is nullable, and the contract was wrong.** `priority` is `NOT NULL DEFAULT 'MEDIUM'`
   and `severity` has no default at all — the schema saying they are different fields. `openapi.yaml`
   now says `nullable: true`; defaulting it to the priority would put a judgement on the wire that
   nobody made, indistinguishable from one that was. Same divergence, same resolution, as §25's
   `owner`.

### `V1` forgot an index on `suite_condition`, which is V9's omission one table over

`suite_condition` came out of `V1` with nothing but its primary key, while both queries against it
seek on `suite_id` — the suite tree's lookup (§24) and now the ancestry walk. Its sibling
`case_condition` got `case_condition_case_idx (case_id, kind, position)` in `V1` and this table got
nothing. `V10` mirrors it with `company_id` leading per ERD §0.1 point 3. **Not** partial: this table
has no `deleted_at` — detaching is a real `DELETE`, and retirement is a soft delete one level down on
`condition`, which is what V9's partial index is for. Verified with `EXPLAIN` against the running
compose stack: `Index Scan using suite_condition_suite_idx`.

### Mutation-verified — fifteen mutations, two survivors, and both were informative

| Mutation | Result |
|---|---|
| Ancestry walked nearest-first (`.reversed()` dropped) | **Killed** |
| `projectId` predicate removed from `findLiveInProject` | **Killed** |
| `deletedAt IS NULL` removed from `findLiveInProject` | **Killed** |
| Gate replaced by unconditional project visibility | **Killed** |
| Opt-outs ignored | **Killed** |
| The two kinds share one dedupe set | **Killed** |
| Inherited entries no longer marked `inherited` | **Killed** |
| Own attachments emitted before inherited ones | **Killed** |
| Retired library entries served as current | **Killed** |
| `ORDER BY tag` dropped from automation tags | **Killed** |
| `severity` defaulted to the priority when unset | **Killed** |
| `@Enumerated(STRING)` dropped from `CaseCondition.kind` | **Killed** |
| Inline attachments no longer excluded (suite side) | **Killed** — by §24's repository-level test |
| Inline attachments no longer excluded (case side) | **Survived → test added → killed** |
| `ORDER BY position` dropped from the step query | **Survived, structurally — documented** |

- *Inline exclusion.* An inline row has a null `conditionId`, the resolver looks it up with
  `resolved.get(null)`, and `HashMap` answers null — so the row drops out **whether or not the query
  ever excluded it**. Every endpoint assertion stays green. §24 had already found this on the suite
  side and answered it with an assertion against the *repository*; the case side needed the same one,
  and mutation is what showed that the two guards are not interchangeable.
- *Step ordering.* The fixture writes the steps at positions 3, 1, 2 in that physical order
  specifically so an unordered query would come back wrong — and with the clause removed the
  response still arrives 1, 2, 3, because the plan is an index scan on `test_step_case_idx` whose
  second column *is* `position`. No fixture can beat that. The clause stays because **an index is not
  a contract**: replace it, or let the planner pick a bitmap scan once a case is large enough, and
  every past screenshot is filed against a list that reads in a different order than it was written.
  Recorded on `TestStepRepository` rather than left as a passing clause nobody can account for.

### The browser pass found a real defect, and it was in the SPA

**`CaseDetailPage` re-sorted both condition lists by `position` — destroying the resolution order the
server had just computed.** `position` is sibling order *within one suite* and says nothing about
which suite comes first, so the render interleaved depths: a rule set three folders down appeared
ahead of the one it depends on. The same trap `SuiteRepository` records for the flat suite list,
where ordering by position lets a child precede its own parent.

**It was invisible against the mock and could only be invisible there**: the mock hands out one flat
ascending sequence with the inherited entry first, so position order and resolution order agree in
mock mode and *only* in mock mode. Lesson §6.5 again — the gates prove it compiles, the mock proves
the shape, and neither proves the order. Fixed by rendering the server's order, with the reasoning in
the file. (The steps sort stays: all steps belong to one case, so `position` is a total order there.)

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **376 tests** (337 + 39). `npm run build` clean,
`npm run lint` 0 warnings.

Then **driven live** against `docker compose up`, two provisioned companies, and a three-deep suite
chain seeded through the **app role** under RLS:

| Assertion | Result |
|---|---|
| The full case | 200 — every summary and detail field, both version numbers distinct (v7 / lock 3) |
| Precondition order | cookie banner → log in (both *Payments*) → seed payment (*Refunds*) → reset (*Partial refunds*) → the case's own, unmarked |
| The opted-out entry | absent from TC-1 — **and present on TC-2**, which inherits the same condition from a second attachment |
| The retired entry, the inline entries | absent, all three |
| Postconditions | inherited *Payments* entry, then the case's own `BOTH` entry — the separate dedupe, visible |
| Steps | 1, 2, 3 despite being written 3, 1, 2; no keywords on a structured case |
| The unauthored case | 200 — "Not set", "No steps written yet", "Unassigned", empty arrays |
| Another client's case through this client's path | **404, 0 bytes** |
| Another client's case through its own path | **404, 0 bytes** |
| The other client's admin reading this client's case | **404, 0 bytes** |
| `RIVAL CO CONFIDENTIAL` in any response of this client's | **0 occurrences** |
| Unauthenticated | 401 |
| `EXPLAIN` on the ancestry query | `Index Scan using suite_condition_suite_idx` |

And **in the browser**, in live mode: the detail page renders the whole case with the inherited
markers naming their folders, a 404 degrades to an inline "Not found" region with the rail intact and
one notification (no blank page), and the console carries nothing but the expected client-error
report for that 404.

### Deliberately not done

- **No writes.** No PATCH, no DELETE, no create — so `lockVersion` is served and never yet checked,
  and the inline-attachment gap stays unresolvable (nothing can create one).
- **`relatedDocuments` and `lastResult` are omitted**, not served as `[]`/`null`: both are sourced
  from modules that do not exist (`documentation`, `execution`). Neither is contract-required and the
  SPA already renders their absence correctly. Same call as §25's `lastResult`.
- **`docstring` / `datatable` on a step, and `parameter_bindings` on an attachment** — real columns
  with no field on the contract's shapes. Mapping them would put values on an entity nothing can
  serialize.
- **`GET /projects/{projectId}/shared-steps`** — still the sibling library, still unbuilt; it is what
  would give `TestStep.sharedStepId` a name rather than a bare id.
- **`kind` on `CaseConditionRef` for the *suite* endpoint** — narrower now and still open: a case has
  two arrays, so which array a row is in says what the field would have; a suite has one and
  genuinely loses the distinction.

### Next

1. **`GET /projects/{projectId}/cases/{caseId}/versions` and `/history`** — the two reads still
   hanging off the case detail screen, and the first thing that needs `test_case_version`.
2. **`GET /projects/{projectId}/shared-steps`** — small, and it finishes the two reusable libraries.
3. **The first write.** `PATCH /projects/{projectId}/cases/{caseId}` is the natural one: it makes
   `lockVersion` mean something, forces the version-writing path (§3 lesson 5), and is the only thing
   that can answer the inline-condition question.
4. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
5. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation,
   and the `case_status.color` migration alongside the status editor.
