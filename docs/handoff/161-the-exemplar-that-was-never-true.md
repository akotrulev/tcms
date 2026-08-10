# §161 — the exemplar that was never true, and the nineteen enums nobody compared

**2026-08-10 · one phase · `backend/` + a contract *description* change: no migration, no schema
change on the wire, both `schema.d.ts` copies regenerated (comments only), no frontend or
`packages/` source.**

§160 left a candidate on its way out and this is it: **which `*_BY_DECISION` entries cite a
constraint the code no longer has.** It is §154's and §156's question asked of their own
justifications, and §160 was the instance that prompted it — `IngestionJob.rawStorageKey` had said
"there is no adapter" for twenty-nine slices after `storage` built one.

All twenty-three entries across the three maps were re-derived against the code.
**Twenty-two hold.** The twenty-third does not, and it is the one the rule's own javadoc calls
*"the entry to copy… the shape the other eleven are held to"*.

## The finding

`READERLESS_BY_DECISION`'s reference entry, `CaseHealth.incomplete`, argued:

> mapped so `ddl-auto: validate` has the whole row in view, and deliberately absent from
> `HealthSignal` — **the contract's enum has no such member and inventing one puts a value on the
> wire the SPA cannot label.**

Every clause after the dash is false, and not by expiry:

| claim | actually |
|---|---|
| the contract's enum has no such member | `openapi.yaml`'s `HealthSignal` has listed `INCOMPLETE` since **2026-07-30** |
| the SPA cannot label it | `lib/labels.ts:463` — *"Missing required fields"*; `StatusChip.tsx:185` has its sentence |
| inventing one puts a value on the wire | both copies of `schema.d.ts` have carried it all along |

The comment was written on **2026-07-31**, one day *after* the contract already had the member.
So unlike §160's, this constraint did not expire — **it was never true**, and §154 promoted it to
the exemplar twelve other arguments were written against.

**The direction is the whole cost.** Stated as a contract problem, closing `incomplete` reads as a
wire change: two generated copies, every client, a decision nobody takes casually. It is not one.
The contract is paid for and always was; what is missing is a **server constant and a derivation**,
and before the derivation a product decision about what "incomplete" means for a case —
`orphaned`'s shape, not `staleApproval`'s. An argument that misnames which side of a boundary the
work is on does not merely fail to help, it deters the repair.

The corrected entry, its twin in `WRITERLESS_BY_DECISION`, and the field javadoc all three now say
the same thing: **absent from the *server's* `HealthSignal` because nothing derives it.**

## The check: `ContractEnumTest`, and the one enum of twenty that already had it

Correcting three comments repairs one instance of a class of defect, which is the move this
repository has stopped accepting. So the deliverable is the computed check:
`contract/ContractEnumTest`, three rules, no Spring context and no database — one YAML parse and
one bytecode import.

**It already existed, for one enum of twenty, and had since M0.**
`SeedDataTest.catalogueMatchesTheContract` compares `PermissionKey`'s 55 members against the rows
`V3` seeds, both ways, and its reason is not about permissions at all: a generated union is
*closed*, so a value the server can produce and the contract does not declare is a value no client
can name. That argument covers every enum on the wire. The one that had a check was the one whose
members live in a table — which is to say the one where somebody had already been bitten.

The three rules:

1. **`everyContractEnumHasAnOwner`** — the contract's twenty named enum schemas and the `OWNERS`
   map are the same set, both ways. **This map is the correspondence itself, and before this slice
   it was written down nowhere**: nothing in either artifact said which server type serves
   `LifecycleStatus`, and nothing failed when one drifted.
2. **`everySameNamedProductionEnumIsClaimedOrExplained`** — a production enum carrying a contract
   enum's name is one of that schema's owners, or a recorded homonym.
3. **`contractEnumsAndTheirServerTypesAgree`** — the members match, except for
   `DIVERGENCE_BY_DECISION`, whose entries **must still diverge** or the build fails: the same
   both-directions property as the maps this slice audited, so an argument cannot outlive its gap.

### Four schemas have two owners, and that is the modulith's shape

`Priority`, `StepFormat` and `AggregationPolicy` each exist **twice** — `repository`/`work`,
`repository`/`execution`, `reporting`/`execution` — because a feature module may not import
another's types. One contract schema, two enums, and they have to stay identical to each other
*and* to it. Listing both copies is what makes the rule see the drift; a rule that resolved one
owner by name would compare whichever the importer returned first.

`ReportFormat` is the case that proves the point and is **not** a copy: `execution.ReportFormat` is
the dialect CI *posted* (JUnit, TestNG, Allure, Cucumber) and `reporting.ReportFormat` is what a
report's bytes *are* (PDF, MD, HTML, CSV, XLSX, JSON). Disjoint sets, one word of context between
them, and the contract's `ReportFormat` is the first. `HOMONYMS` holds the second with the sentence
that tells them apart, and that entry is checked for staleness too.

### The two divergences, and why the direction is recorded

- **`HealthSignal.INCOMPLETE` — contract-only, deliberately.** Above.
- **`PermissionQualifier.NONE` — server-only, which is the dangerous direction**, and safe here for
  a reason the *code* enforces rather than states: `PermissionSet`'s constructor **throws** if a key
  is put in the held map qualified `NONE`. A permission the caller lacks is an absent entry, never a
  present one covering nothing, so the constant cannot reach a serializer.

Server-only is the defect and contract-only is a promise. Jackson writes a constant's name; the
generated union does not contain it; TypeScript checks nothing at runtime, so it arrives as a string
outside the union and falls out of every `satisfies Record<Union, …>` lookup as `undefined` —
a blank cell or a crash, and nothing throws on either side. Contract-only costs a dead branch.
**Recording which is which is what §161 is about**, because that is the sentence the next author
acts on.

## Three things the check got wrong before it was right

- **Its first run reported a divergence that was not one.** `execution.AggregationPolicy` declares
  `public static final AggregationPolicy DEFAULT = LATEST` — an alias for the contract's
  `default: LATEST` — and "static field of the enum's own type" counted it as a constant. Jackson
  writes the name of the constant it points at, so the rule's *first* finding would have been a
  demand to declare a member no server can send. Fixed by requiring the `ACC_ENUM` flag. Fifth
  slice running where the check had the defect it was written to find.
- **Component-level schemas only, and the narrowing is measured.** The document holds **44** more
  enums inline on a property. They are the same kind of value, but they have no name to correspond
  by: the owner map would be 64 hand-written lines and two thirds guesses about which field an
  anonymous list belongs to. A named schema is a named server type and a named generated union.
- **Neither `DIVERGENCE_BY_DECISION` value spells a column name, and that is load-bearing.**
  `everyMappedColumnHasAReader` counts a whole-word column name in any file's *string literals* as a
  reader, comments stripped first — so an argument for a column being dead, written as a literal in
  a new file, marks it alive in the old rule and makes its `READERLESS_BY_DECISION` entry vanish.
  §154 hit this and fixed it by excluding its own file from its own corpus; the shape is general,
  and **the second file to write about a dead column is the one that discovers that.** The
  identifiers live in the javadoc, where the corpus cannot see them.

## The gate was 1.6 MB of heap from hanging, and the hang reports nothing

Adding the third rule did not fail the build. **It made the build never return.**

Gradle gives a test worker **512m** by default and nothing here had ever set otherwise. What lives
in that heap is mostly not the product: `ArchitectureRulesTest` holds **two** ArchUnit class graphs
in `static final` fields, so they live as long as the JVM, and `ContractEnumTest` made a third.
The measurement, taken from the hung worker with `jcmd GC.heap_info`:

```
garbage-first heap   total 524288K, used 522685K
```

**99.7%, and the failure mode is the point.** G1 does not throw `OutOfMemoryError` here. Every test
passes, the worker finishes them, and then parks for ever in `MessageHub.stop` with the collector
spinning at 90% CPU across six GC threads — a build with **zero failing tests that never returns and
prints nothing**, because the output is buffered until a completion that does not come. It survived
one 20-minute run being blamed on CPU contention, and was only identified after a heap dump of a
process that looked idle.

Two repairs, and both belong here rather than one:

- **`maxHeapSize = "2g"`** on the test task, with the measurement in the comment. 512m for a suite
  that boots several Spring contexts, a Testcontainers client and three class graphs was a cliff
  every slice has been walking closer to; the ceiling gets nearer as the codebase grows, and the
  first symptom is a hang rather than an error.
- **`ContractEnumTest` imports in `@BeforeAll` and releases in `@AfterAll`.** The heap is 2g now and
  it would fit either way — it is still wrong to keep the largest object a test allocates alive for
  two thousand tests with no use for it. The two in `ArchitectureRulesTest` are left alone: they are
  used by rules spread across that class and shortening their life is that file's decision.

**This is the second time this repository has found a defect whose whole cost is that nothing
reports it** — §108's `${VAR:?}` read as a default, and now a gate that passes by never finishing.
A green build and a build that has not ended are not distinguishable from a scrollback.

## The contract's own stale enumeration, removed rather than updated

`HealthSignal`'s description said **"Five of these have a writer"** and named them. §157 gave
`STALE_APPROVAL` one and §158 gave `CONDITION_DRIFT` one, and neither updated it — so for two
slices the contract told every client that two signals *"are computed by nothing and are therefore
never sent"* while the server was sending both.

The enumeration is **deleted, not corrected**. It was a second copy of a list the server already
keeps mechanically, in `ArchitectureRulesTest.WRITERLESS_BY_DECISION`, which fails the build when a
member gains or loses a derivation. The rule a client actually acts on — *treat an absent signal as
"nobody has looked", not as "not raised"* — is the same whichever members are derived, and it stays.
A copy of a computed list, in the document nobody recompiles, is how it goes wrong.

## Also corrected, in the file whose subject is tallies

`CaseHealth`'s class header opened *"Nine booleans, one per `HealthSignal`"*. It counted the
**signals** (nine, then) and called the answer the number of **columns**, which was already ten;
`V37`'s `UNDECLARED_AUTOMATION` moved both and neither was corrected. It is **eleven and ten** now,
and the header says which is which. Fourth slice running with a hand-kept count to fix in this
family, and the second where the count was in the same file as the finding.

## What holds

The twenty-two entries whose constraints were re-derived and still stand, for the next reader who
would otherwise re-derive them:

- `AppUser.locale` / `timezone` — still written only as constants by `invite`, still read nowhere.
- `DocPage.spaceId` — `DocSpace`'s header still says the space "is not on the wire and has no
  endpoint", and the contract still declares no `/spaces`.
- `SuiteCase.addedAt` / `addedBy`, `CompanyMembership.invitedAt` / `invitedBy` — no reader; the
  reader they want is an administrative column and therefore still a contract change.
- `CompanyMembership.avatarUrl` — `api.UserRef`'s *"null for everyone today"* is still true.
- `DocBlock.anchorSlug` — the field, its mapping and its getter are still its only mentions.
- `ReportDefinition.scheduleCron` / `sharedWithTeamId` — scheduled reports still need the
  notification channel plan §8 blocks them on, and `create` still takes no parameter for either.
- `AuditEvent.bulkOperationId` — three mentions, no fourth; the audit trail still has no screen.
- `IngestionJob.error` — **nothing writes `FAILED`**: `IngestionJob.completed` is the only status
  writer in the module.
- `Outbox.record` — both handlers of the only event type are still whole-company recomputes taking
  no arguments.
- `CaseHealth.outdatedSpecChanged` / `orphaned` / `unlinked` — still three, still argued at the
  header, and two of them still need `documentation`, which `repository` may not depend on.

## Gates

`cd backend && ./gradlew build` — **SUCCESSFUL, 2448 tests / 506 classes / 0 failures** (+3,
+1 class: `ContractEnumTest`). Frontend `npm run build` clean, `npm run lint` 0 warnings,
`npm run test:run` **1214 across 93 files** (unchanged — no frontend source moved). `packages/`
built in order, lint clean, **229 tests** run because `client/src/schema.ts` is regenerated.
Both `schema.d.ts` copies are byte-identical to each other and their diff is **comments only** —
no generated type changed.

**Both directions of the new rule were mutation-tested before the prose was written:** adding
`MARKDOWN` to `execution.StepFormat` reddens `contractEnumsAndTheirServerTypesAgree` as SERVER-ONLY
on one of two copies, and deleting `INCOMPLETE` from the contract's enum reddens the *healed* arm —
the entry describing a divergence that no longer exists. Both reverted.

## Queues

Nothing in this slice changes runtime behaviour: no endpoint, no schema, no query, no component.
The rows are recorded as such rather than invented — see `docs/verification/backend-mutation-checks.md`
(`CE-M1`…`CE-M3`) and `docs/verification/frontend-browser-checks.md` (`CE-1`). **None run.**

## The next slice

The audit that produced this one is finished: the three by-decision maps are re-derived and only the
exemplar was wrong. What it did **not** examine is the same question one level out — **the
`*_BY_DECISION`-shaped populations that are not in `ArchitectureRulesTest`**:
`ContractConventionTest.NO_CLIENT_ERROR_POSSIBLE`, `ContractParameterBindingTest.DELIBERATELY_UNBOUND`
(empty today), `ErrorBodyPolicyTest`'s exemptions, and now this file's own three maps. Each is an
argument a green build re-affirms every run, which is precisely the property that let §160's and
this one's survive.

Otherwise: the browser queue, `TeamRef`, the three remaining health derivations, or the
read-only-archive decision §159 deliberately did not take.
