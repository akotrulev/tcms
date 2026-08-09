# §147 — one record for a person, and the tally that stopped being kept by hand

**2026-08-09 · one phase · `backend/` only. No migration, no contract change, no frontend or
packages source change.**

§146 created `com.tcms.api` for the error envelope and named its next tenant in the same breath:
`UserRefView`, six copies of one contract shape, *"a migration rather than a decision"*. This is
that migration. It is deliberately dull — the decision was taken and reviewed last slice, and §125's
rule is why it was not taken in the same diff.

What is not dull is the second half. §146's finding was not about `Problem` or about `UserRef`; it
was that the **evidence for extracting had been kept by hand and was wrong**. That is repaired here,
because an extraction fixes the copies that exist and a computed check fixes the ones that do not
exist yet.

---

## What moved

`com.tcms.api.UserRef` — `UserRef` in `openapi.yaml`, `(id, displayName, email, avatarUrl)`.

| Module | Was |
|---|---|
| `identity` · `project` · `repository` · `documentation` · `execution` · `work` | its own `UserRefView`, byte-identical to the other five |
| `reporting` | no copy — it imported `project.UserRefView` |

Seven consumers, which is the strongest reading of the charter's *"more than one module serves
it"* this codebase has. 55 files, 127 references, six deletions.

**The duplication was forced rather than chosen, and every one of the six headers said so.** Three
of them asked the next reader to keep a count so the extraction would have evidence behind it. That
is the part worth carrying forward: the copies were not carelessness, they were the recorded
consequence of "modules are packages with declared dependencies" plus no home for a shape that
belongs to neither module in any given pair.

Six package declarations gained `"api"` (`identity`, `project`, `documentation`, `execution`,
`work`, `reporting`; `repository` and `auth` already had it from §146).

## The loop that looked like pure ceremony, and the assertion that said otherwise

`repository.CaseOwners#resolve` and `execution.ExecutionPeople#resolve` each held a loop that read
`project.UserRefView` out of the `PeopleDirectory` port and wrote the same four fields into their
own `UserRefView`. **With one type, those loops copy a record onto itself**, so both were written as
`return people.resolve(companyId, userIds);` — which is the obvious form, and it is wrong.

**The red run is the finding.** `CaseOwnersNullKeyTest` and `PeopleResolutionNullKeyTest` — two
tests, one per module, because neither package can see the other — hand each method a stub directory
that returns `Map.of()` and assert the result still answers `null` to `get(null)`. Both went red.
The loop was doing a second job nothing in its name or its comment said: **it was also the §38
guard**, and the guard was against the *port* misbehaving, not against the caller.

So both methods are `new HashMap<>(people.resolve(companyId, userIds))`, with the reason in the
comment. `PeopleDirectory#resolve` does *contract* a mutable, never-`Map.of()` result and
`IdentityPeopleDirectory` honours it on both paths — but these two classes are where §38's 500
actually happened, and the property whose absence is invisible until the page with nobody named on
it is not one to take another module's word for. One allocation per page.

**This is the argument for keeping a refactor inside the suite's reach rather than reasoning it
safe.** The first version of this section, written before the run, said the delegation was safe
*because the port contracts it* — true, and it would still have shipped a regression the moment any
other implementation of that interface appeared. Two tests written for a different slice's defect
are what turned a correct-sounding sentence into a red bar.

Both classes stay. They are the seam that keeps §6u reachable from exactly one place per module —
§76's whole point — and their headers already recorded that they are *"the mapping and not the
rule"*. There is no mapping left; there is a seam and a guard.

## The tally, computed

`ArchitectureRulesTest.noRecordOutsideApiRepeatsAnApiShape`: for every record in `com.tcms.api`,
no record in any other module may have the same components. **Matched on shape, not on name** — all
six copies were called `UserRefView`, but the copy this prevents is the *next* one, which will be
called `Person` or `OwnerRef` or `CommenterView` because whoever writes it will not have read the
file that says not to. Components are compared as a set of `name:type` pairs, so a reordered copy
still matches.

**It is narrow on purpose, and the narrowness was measured rather than assumed.** The tempting rule
is "no two records anywhere share a shape". Scripted over `src/main` first, that rule reports **fifty**
`Invalid(Map<String, List<String>> errors)` records — one per service's sealed `Outcome` — plus a
dozen `Refused(String message)`, and it would have to grow an ignore list on the day it was written,
which is §145's finding about `ContractParameterBindingTest` arriving before the check even ships.
So the rule fires only once a shape has been given a home in `api`, which is exactly when a copy
becomes indefensible.

Verified by mutation: a seventh copy under a different name (`work.OwnerRef`) fails it; removing
that file makes it green again.

## Three refusals, recorded where the next reader will look

The charter earns its keep by refusing things, and this slice is the first evidence that it does:

- **`reporting.AggregationPolicy` stays a second copy of a contract enum.** `reporting` and
  `execution` do not serve one shape — they keep two vocabularies that `AggregateExecutionPolicyTest`
  pins equal, and merging them would leave the cross-check comparing a thing to itself. Its header
  carried the old three-family tally (`ApiProblem` ×2, `UserRefView` ×3) and now carries the
  supersession and the refusal.
- **`documentation.DocPageAttachmentView` and `repository.CaseAttachmentView` stay apart**, though
  they agree field for field. The *contract* defines two schemas that happen to agree; collapsing
  them in Java would be a contract change wearing an extraction's clothes, and the decision about
  whether this API has one attachment shape or three belongs in `openapi.yaml`.
- **`project.TeamRefView` and `identity.RoleAssignmentView.TeamRef` are two copies of `TeamRef`**
  and were left alone. This is the next tenant by the same argument that made `UserRef` one, but two
  copies is not six and the shape (`id`, `name`) is small enough that the extraction should be taken
  when a third consumer appears rather than on the strength of this slice's momentum. **It is now
  the kind of thing that will be found by grep rather than by a comment**, which was the point.

## Two stale notes, both made false by §146 rather than by this slice

`ModuleStructureTest`'s two failure messages still said the permitted targets were
*"tenancy, identity, project and (since §125) storage"* and that **"adding a FIFTH foundation module
is a decision"** — written before §146 took the fifth. Nothing failed: these strings are read only by
whoever breaks the rule, which is precisely the reader they would have misinformed. This is the
tenth blocker-or-permission note in this repo to outlive its premise, and the second in three
slices to be found by a slice that had to touch the file for another reason.

`identity.AdminDirectoryService#peopleById` cited *"the codebase already keeps a running tally of
forced copies of this shape"* as the reason not to write a second §6u implementation. The tally is
gone; the reason is not, and it is a better one than the tally was — no architecture test can see a
second implementation of a **rule**, only of a shape.

## Gates

`./gradlew build` **SUCCESSFUL, 2320 tests, 0 failures** (2319 at §146: +1, the shape rule).
No frontend or packages change, so their gates are §146's.

## What is verified and what is not

**Nothing here changes behaviour**, which is the claim a refactor has to earn rather than assert.
What earns it: `hibernate.ddl-auto: validate` plus the full endpoint suite renders every one of the
seven consumers' responses, so a field lost in the move is a red test rather than a quiet null; and
the two delegations are covered by the case-grid, run-detail, review-panel and board tests that
already assert an owner's name, an executor's name and a commenter's name on the wire.

No new browser rows and no new mutation rows: no screen, no request and no stored byte differs.
`A4-11`/`A4-12`, `CR-1`…`CR-3`, `SD-2`…`SD-4` and `PR-2`/`PR-3` remain unrun from earlier slices.
