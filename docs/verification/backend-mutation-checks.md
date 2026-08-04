# Deferred backend mutation checks — the queue

**Policy set 2026-08-02 (§65).** Mutation-verifying each slice inline costs a full
`./gradlew build` per mutation (~3 min each, often two or three per slice) and it was being paid
*during* feature work, where it is slowest and least useful. It is now **deferred and batched**:
a slice records the mutation it would have run, and this file is executed as one pass later.

> **⚠ ZERO mutations run inline. Restated by the user on 2026-08-02, after §76 ran seven.**
> §72–§76 each ran a handful during the slice and queued the rest, and the `✅ RUN` rows below made
> that look like the policy rather than a drift from it. It was a drift. **Every** mutation a slice
> identifies is written here and run in the batch pass — none is "cheap enough" to run inline, and
> the fact that a run one has occasionally found something (§76's frontend gate did) is not an
> argument for the exception: it is an argument for running this file, which would have found the
> same thing at the same cost, later.

**This does not lower the bar, it moves it.** The claim "this test would catch that defect" is still
required — it is just made checkable later instead of proven now. A slice that lands without adding
its row here has skipped the check outright, which is the thing the batching is meant to avoid.
See the sibling queue for the browser half: `frontend-browser-checks.md`.

**Why batching is cheap here specifically:** the mutations are independent, each is a one-line edit,
and the suite is the same 1200 tests every time. Running twelve mutations in one sitting costs
twelve builds either way — but done in a batch, none of them is blocking a feature.

## How to run this file

For each row: apply the mutation, run the named scope (never the whole suite — the row states which
tests must redden), confirm **exactly** the listed tests fail and nothing else, then revert.

```bash
cd backend
./gradlew test --tests '<the scope in the row>'
git checkout -- <the mutated file>          # revert before the next row
```

**A mutation that reddens MORE than the listed tests is as much a finding as one that reddens
fewer.** Too few means the test does not cover the behaviour it claims; too many means the tests are
coupled to something other than what they name, and the extra failures are the ones worth reading.

---

## §65 — permission qualifiers on the wire

| | |
|---|---|
| **Status** | ✅ **RUN AND PASSED 2026-08-02**, before the policy above existed — kept as the worked example of the format |
| **File** | `identity/PermissionEvaluator.java`, the `merge` helper |
| **Mutation** | `PermissionQualifier.ofStored(row.getQualifier())` → `PermissionQualifier.ALL` (stop reading the stored qualifier; grant every held key over every row) |
| **Scope** | `--tests 'com.tcms.identity.*' --tests 'com.tcms.documentation.ArtifactEndpointTest' --tests 'com.tcms.repository.CaseDeleteEndpointTest'` |

**Must redden — exactly these 8, and nothing else:**

- `ArtifactEndpointTest` › the same Test Engineer is REFUSED a PROJECT-scope artifact
- `ArtifactEndpointTest` › /me/permissions tells the SPA the grant is PLAN_SCOPE …
- `PermissionEvaluatorTest` › the qualifier follows the same override, not the stronger of the two
- `PermissionEvaluatorTest` › a Test Engineer's case:delete is OWN — the whole point of V12
- `CaseDeleteEndpointTest` › a Test Engineer may NOT delete a colleague's — THE assertion of this class
- `CaseDeleteEndpointTest` › a case with no recorded author is NOT the engineer's own
- `CaseDeleteEndpointTest` › the two refusals carry different messages
- `CaseDeleteEndpointTest` › a refused delete writes no audit row

**Observed:** exactly those 8, no others. The `OWN` and `PLAN_SCOPE` families both fail, which is the
property worth having — the mutation is one line in a shared helper, so a suite that only covered
one axis would have looked equally green here.

### Still owed for §65 — NOT yet run

| | |
|---|---|
| **File** | `identity/PermissionSet.java`, `of(...)` |
| **Mutation** | delete the `NONE` guard (accept a held key qualified to nothing) |
| **Scope** | `--tests 'com.tcms.identity.PermissionEvaluatorTest'` |
| **Must redden** | *a held permission is never qualified to NONE — that state cannot be built* — and only that |
| **Why it matters** | The wire contract says every entry in `permissions` is a real capability. Without the guard, a future term that resolves to `NONE` would serialize an entry the SPA reads as "held". |

| | |
|---|---|
| **File** | `identity/RoleAssignmentRepository.java`, `findProjectScopedHeldPermissions` |
| **Mutation** | `SELECT DISTINCT rp.permission_key, rp.qualifier` → `SELECT DISTINCT ON (rp.permission_key) rp.permission_key, rp.qualifier` (collapse to one row per key, letting row order pick the qualifier) |
| **Scope** | `--tests 'com.tcms.identity.PermissionEvaluatorTest'` |
| **Must redden** | *strongest wins — a Company Admin who is also a project's Test Engineer keeps ALL* |
| **Why it matters** | This is the failure that decides authorization by row order, and it is invisible in a green suite unless a persona genuinely holds one key through two grants of different strength. If **nothing** reddens, the fixture no longer has such a persona and the strongest-wins merge is untested. |

---

## §66 — the `OWN` qualifier reaches the row

### Run in the slice — the worked example

| | |
|---|---|
| **File** | `repository/CaseSummaries.java`, the `render` mapping |
| **Mutation** | `row.getCreatedBy()` → `row.getOwnerId()` for the `createdBy` field (serve the owner as the author) |
| **Scope** | `--tests 'com.tcms.repository.CaseListEndpointTest'` |

**Must redden — exactly these 4, and nothing else:**

- `Authorship` › createdBy is the author, not the owner, when the two differ
- `Authorship` › the author's name comes from the ACTIVE company's membership too
- `Authorship` › an unauthored case is listed with a null createdBy, not hidden
- `Authorship` › an owned-and-unauthored row proves the two columns are read separately

**Observed:** exactly those 4 of 58, no others.

**What makes it possible is the fixture, not the assertions.** `seedCases` now seeds `created_by`
disagreeing with `owner_id` on almost every row, plus one owned-and-unauthored row (TC-4) and one
authored-and-unowned row (TC-3). The two columns are equal on most *real* rows, so a fixture that
seeded them equal would let this mutation pass every assertion in the class.

### Still owed for §66 — NOT yet run

| | |
|---|---|
| **File** | `identity/IdentityProjectAccess.java`, `canDoOnAuthored` |
| **Mutation** | `case OWN -> userId.equals(authorId)` → `case OWN -> true` |
| **Scope** | `--tests 'com.tcms.repository.CaseDeleteEndpointTest'` |
| **Must redden** | *a Test Engineer may NOT delete a colleague's*, *a case with no recorded author is NOT the engineer's own*, *the two refusals carry different messages*, *a refused delete writes no audit row* |
| **Why it matters** | This is the server half of what §66 wired to the client. The client gate is an affordance layer and falls **open** on a failed permissions query, so this switch is the only thing actually refusing the write. If it stops refusing, every SPA-side test in this slice still passes. |

| | |
|---|---|
| **File** | `repository/CaseSummaries.java`, `peopleIds` |
| **Mutation** | drop the `row.getCreatedBy()` branch (resolve owners only) |
| **Scope** | `--tests 'com.tcms.repository.CaseListEndpointTest' --tests 'com.tcms.repository.CaseDetailEndpointTest'` |
| **Must redden** | the two `Authorship` tests that assert a **resolved** author (`createdBy is the author…`, `the author's name comes from the ACTIVE company's membership too`) |
| **Why it matters** | The failure mode is a `createdBy` that is silently `null` for an author nobody else on the page owns anything — so it would look exactly like the unauthored row, and the **client would disable Delete on cases the caller actually wrote**. A test asserting only "the key is present" would not see it. |

---

## §67 — evidence attach and delete

### Run in the slice — PASSED

| | |
|---|---|
| **File** | `execution/FilesystemEvidenceStore.java`, `resolve` |
| **Mutation** | delete the `!resolved.startsWith(root)` guard |
| **Scope** | `--tests 'com.tcms.execution.FilesystemEvidenceStoreTest'` |
| **Observed** | exactly 1 red — *a key climbing out of the storage root is refused* |

| | |
|---|---|
| **File** | `execution/EvidenceService.java`, `remove` |
| **Mutation** | `if (!authorship.mayActOn(evidence.getCreatedBy()))` → `if (false)` |
| **Scope** | `--tests 'com.tcms.execution.ExecutionEndpointTest'` |
| **Observed** | **exactly 2** red — *a tester cannot remove a colleague's evidence*, *evidence with no recorded author is nobody's own work*. `deletesOwn` and `aLeadRemovesAnyones` stayed **green**, which is the proof the tests assert authorship rather than the gate. Had those reddened too, the tests would have been measuring the wrong thing. |

| | |
|---|---|
| **File** | `execution/EvidenceViews.java`, `partition` |
| **Mutation** | `if (row.getStepResultId() == null)` → `if (true)` (every row execution-level) |
| **Scope** | `--tests 'com.tcms.execution.ExecutionEndpointTest'` |
| **Observed** | exactly 1 red — *evidence on a step renders on that step and not on the execution*. That test asserts **both** lists, because either assertion alone passes against an implementation that puts every row in both. |

### Still owed for §67 — NOT yet run

| | |
|---|---|
| **File** | `execution/EvidenceService.java`, `write` |
| **Mutation** | move `store.put(key, upload.bytes())` **above** the two `saveAndFlush` calls |
| **Scope** | `--tests 'com.tcms.execution.ExecutionEndpointTest'` |
| **Must redden** | **nothing — and that IS the finding.** The rows-then-bytes ordering is a *recovery* property, not a behavioural one: both orders serve an identical response on the happy path, and no test in the suite fails. It is asserted only by the comment in `EvidenceStore`. Running this mutation is how the next reader learns the ordering is unprotected, and the honest follow-up is a test that injects a store failure and asserts no orphan blob — which needs a fault-injecting `EvidenceStore`, i.e. its own slice. |

| | |
|---|---|
| **File** | `execution/EvidenceService.java`, `storageKey` |
| **Mutation** | append `+ "-" + upload.filename()` to the key |
| **Scope** | `--tests 'com.tcms.execution.FilesystemEvidenceStoreTest' --tests 'com.tcms.execution.ExecutionEndpointTest'` |
| **Must redden** | *the uploaded filename never becomes a path* — and if it does **not** redden, the traversal defence rests entirely on the adapter's `startsWith` check rather than on both layers, which is worth knowing before someone removes one of them. |

| | |
|---|---|
| **File** | `execution/EvidenceController.java`, `attach` |
| **Mutation** | drop `EvidenceUpload.sanitizeFilename(...)` and pass `file.getOriginalFilename()` raw |
| **Scope** | `--tests 'com.tcms.execution.ExecutionEndpointTest'` |
| **Must redden** | *the uploaded filename never becomes a path — a traversal name is stored as text* (expects `passwd`, would get `../../../etc/passwd`) |
| **Why it matters** | The filename reaches a `Content-Disposition` **header**. Sanitisation and `headerSafeFilename` are two different defences against two different things (a path-looking name vs. header injection) and only one of them has a test today. |

---

## §68 — classification assignment

### Run in the slice

| | |
|---|---|
| **File** | `repository/CaseEdit.java`, `normalizeClassification` |
| **Mutation** | drop the `vocabulary.dimensionOf(valueId) == null` refusal — accept any id |
| **Scope** | `--tests 'com.tcms.repository.CaseUpdateEndpointTest'` |
| **Observed** | exactly 4 red — the sibling-project value, the retired value, the unknown value, and the indistinguishable-refusal test. Nothing else moved, which is what says these measure the *vocabulary* check rather than the write path generally. |

| | |
|---|---|
| **File** | `contract/ContractConventionTest.java` (the new check itself, verified against the defect it was written for) |
| **Mutation** | delete the `createdBy` component from `TestCaseDetailView` — i.e. restore the state master shipped in §66 and §67 |
| **Scope** | `--tests 'com.tcms.contract.ContractConventionTest'` |
| **Observed** | exactly 1 red — `a response record has a component for every property its schema requires`. The other four contract checks stayed green, so the new one is not merely re-testing what was already covered. |

### Still owed for §68 — NOT yet run

| | |
|---|---|
| **File** | `repository/CaseClassifications.java`, `reconcile` |
| **Mutation** | delete the `if (!offered.contains(row.getValueId())) continue;` guard, so a retired assignment becomes a removal candidate |
| **Scope** | `--tests 'com.tcms.repository.CaseUpdateEndpointTest'` |
| **Must redden** | **exactly 1** — *a RETIRED value already on the case survives a save that cannot mention it*. If `a retired value is not SERVED` reddens too, the two tests are entangled and the survival test is reading the filter rather than the bound. |
| **Why it matters** | This is the slice's central decision and the only one whose failure is *silent in production*: the case saves, the response looks right, and a classification is gone with nothing on any screen having mentioned it. |

| | |
|---|---|
| **File** | `repository/CaseClassifications.java`, `reconcile` |
| **Mutation** | remove the `assignments.flush()` between the delete and the insert |
| **Scope** | `--tests 'com.tcms.repository.CaseUpdateEndpointTest'` |
| **Must redden** | **exactly 1** — *replacing the value on a SINGLE dimension works*, and it must fail with a **500** from V2's `23505`, not an assertion mismatch. Hibernate orders inserts before deletes within a flush, so without the explicit flush the trigger counts the outgoing value and refuses the incoming one. |
| **Why it matters** | Changing a case's Test Type is the single most ordinary thing this feature does, and the failure is a 500 rather than a wrong value. |

| | |
|---|---|
| **File** | `repository/CaseEdit.java`, the `classificationValueIds` component |
| **Mutation** | return `distinct.stream().toList()` instead of `.sorted().toList()` |
| **Scope** | `--tests 'com.tcms.repository.CaseUpdateEndpointTest'` |
| **Must redden** | **exactly 1** — *the same values in a different order write NO second version*. |
| **Why it matters** | `CaseVersions#hash` rests its whole determinism argument on there being no set in the hashed shape. If this does **not** redden, the version list is not actually protected from reorder churn and the sort is load-bearing only by accident. |

| | |
|---|---|
| **File** | `repository/CaseEdit.java`, `of` |
| **Mutation** | pass `Set.of()` instead of `storedClassificationValueIds` |
| **Scope** | `--tests 'com.tcms.repository.CaseBulkEndpointTest'` |
| **Must redden** | **unknown, and that is the point of running it.** The expectation is that the existing round-trip hash test catches it; if nothing reddens, then a bulk `MOVE_TO_SUITE` over a classified case writes a version hashing as though the case were unclassified, and the *next* ordinary save is credited with restoring a classification nobody touched — §62's mis-attribution defect, on a new field, with no test behind it. |

---

## §69 — the classification vocabulary editor

### Run in this slice

| | |
|---|---|
| **File** | `project/ClassificationValueWriteService.java`, `permissionFor` |
| **Mutation** | return `RequiredPermission.onProject("project:settings")` unconditionally — one gate for both kinds of dimension |
| **Scope** | `--tests 'com.tcms.project.ClassificationValueWriteEndpointTest'` |
| **Observed** | exactly **3** red — the create, the edit and the retire refusals on a company-level dimension. 24 green. **The first run of this mutation reddened 4**, and the fourth was the fixture's fault rather than the code's: a broken gate let the Test Lead's create *succeed*, so the Company Admin's create of the same name then failed the duplicate check. The two tests now use different names. A mutation count that measures the fixture is worse than no mutation, because it reads as extra confidence. |

| | |
|---|---|
| **File** | `project/ClassificationValue.java`, `create` |
| **Mutation** | derive the `ltree` label from the name (`lower` + non-alphanumerics to `_` + trim), i.e. what `tcms_ltree_label` does and what the seed uses |
| **Scope** | `--tests 'com.tcms.project.ClassificationValueWriteEndpointTest'` |
| **Observed** | exactly **1** red — *a name with no ASCII letters in it is created, and its path is valid*. The sanitizer answers the empty string for `支付`, and an empty label is not a valid `ltree` label, so the insert fails with a message about the path. |

| | |
|---|---|
| **File** | `project/ClassificationValueEdit.java`, `validParent` |
| **Mutation** | drop `&& candidate.getDimensionId().equals(dimensionId)` — accept any live value as a parent |
| **Scope** | `--tests 'com.tcms.project.ClassificationValueWriteEndpointTest'` |
| **Observed** | exactly **1** red — the wrong-axis parent. The sibling-project parent stayed green **and that is correct**: it is refused by the *scoping of the list* rather than by this predicate, so the two refusals are independent mechanisms with a test each. |

| | |
|---|---|
| **File** | `project/ClassificationValueEdit.java`, `refuseDuplicate` |
| **Mutation** | drop the `Objects.equals(candidate.getParentId(), parentId)` clause — uniqueness per dimension instead of per parent |
| **Scope** | `--tests 'com.tcms.project.ClassificationValueWriteEndpointTest'` |
| **Observed** | exactly **1** red — *the SAME name under a different parent is allowed*, which is the property §68 turned on when it had to start serving classification value ids. |

### Still owed for §69 — NOT yet run

| | |
|---|---|
| **File** | `db/migration/V20__classification_value_sibling_name_unique.sql` |
| **Mutation** | none — this is the **migration-against-an-already-migrated-database** check Testcontainers structurally cannot make, since it migrates a fresh container every time. Apply `V20` to the Hetzner box (at `V19`) and confirm `installed_by = tcms_owner`. |
| **Must show** | v19 → v20 in place, and the index present with `indpred` non-null (partial) and `company_id` leading. |
| **Why it matters** | A unique index applied to existing data fails the migration outright if any duplicate exists. The seed's `NOT EXISTS` guard should make that impossible — *should*. §57 and §61 each ran this check and each learned something. |

| | |
|---|---|
| **File** | `project/ClassificationValueWriteService.java`, `dimensionOf` |
| **Mutation** | resolve the dimension with `dimensions.findById(dimensionId)` instead of through `findForProject` |
| **Scope** | `--tests 'com.tcms.project.ClassificationValueWriteEndpointTest'` |
| **Must redden** | **exactly 1** — *a dimension of a project the caller cannot see is 404, not 403*. |
| **Why it matters** | `findById` is scoped by RLS to the company and **not** to the project, so it accepts a sibling project's Feature dimension: the caller would extend a taxonomy belonging to a project they may hold no grant on, gated on `project:settings` for the project in the path. Every cross-company test in the suite still passes, because the row is in the caller's own company — persona 3's shape again. |

| | |
|---|---|
| **File** | `project/ProjectService.java`, `writeGrantedOnTarget` |
| **Mutation** | move the `resolve` call **after** the permission check, asking a fixed permission first |
| **Scope** | `--tests 'com.tcms.project.ClassificationValueWriteEndpointTest'` |
| **Must redden** | at least the two 404-not-403 tests. It cannot even be written without inventing a permission to ask, which is the argument the method's javadoc makes — running it is how that argument gets checked rather than asserted. |

---

## §71 — team management, the org chart's first writer

Two mutations were **run inline** rather than queued, because the slice's whole claim is a gate and
a validation and there is nothing else to observe. Three are owed.

### Run during §71

| | |
|---|---|
| **File** | `identity/TeamWriteService.java`, all four gate sites |
| **Mutation** | replace `!reach.manageTeams()` with `false` — no gate at all |
| **Scope** | `--tests 'com.tcms.identity.TeamEndpointTest'` |
| **Observed** | exactly **6** red, and every one of them a gate test: the Test Lead refused a create, a delete and a member write; the plain member refused a create; the gate-runs-before-the-payload test; and the other company's admin. The other 29 stayed green. |

| | |
|---|---|
| **File** | `identity/TeamWriteService.java`, `putMember` |
| **Mutation** | replace `if (!isStaffable(companyId, userId))` with `if (userId == null)` — accept any account the FK accepts |
| **Scope** | `--tests 'com.tcms.identity.TeamEndpointTest'` |
| **Observed** | exactly **2** red — the sibling company's user and the REMOVED member. Nothing else moved, so the check discriminates on precisely the population it claims to. |

### Still owed for §71 — NOT yet run

| | |
|---|---|
| **File** | `db/migration/V21__team_name_case_insensitive_unique.sql` |
| **Mutation** | none — the **migration-against-an-already-migrated-database** check Testcontainers structurally cannot make. Apply `V21` to the Hetzner box (at `V20`) and confirm `installed_by = tcms_owner`. |
| **Must show** | v20 → v21 in place; `uq_team_name` present as a unique **index** over `(company_id, lower(name))` and no longer as a table constraint. |
| **Why it matters** | This one **drops a constraint and creates an index in its place**, which every earlier migration-in-place check did not do — and it fails outright if the existing data holds two teams whose names differ only in case. On a real box `team` is empty (nothing could ever write it), so the expected result is a clean no-op — which is exactly the kind of expectation worth confirming rather than assuming. |

| | |
|---|---|
| **File** | `identity/TeamEdit.java`, `descends` |
| **Mutation** | compare against direct children only — replace the upward walk with `parent.getParentTeamId().equals(team.getId())` |
| **Scope** | `--tests 'com.tcms.identity.TeamEndpointTest'` |
| **Must redden** | **exactly 1** — *a team cannot be moved under its own descendant*, which is deliberately written two levels deep. A one-level check passes the obvious test and severs a branch from the root on the real case. |

| | |
|---|---|
| **File** | `identity/TeamWriteService.java`, `delete` |
| **Mutation** | drop the `grants > 0` arm, keeping only `children > 0` |
| **Scope** | `--tests 'com.tcms.identity.TeamEndpointTest'` |
| **Must redden** | **exactly 2** — the 409-with-count test and the *"the grant is still there afterwards"* test. The second is the one that matters: without it a mutation that returned 409 and deleted anyway would still pass. |

### The mutation that CANNOT be written, and why it is recorded here

Swapping the gate's key from `company:teams` to `company:members` reddens **nothing**. `V3` grants
both to exactly the same roles, so every persona the suite can build holds both or neither: the
distinction is real in the code and unobservable in the seeded data.

That is not repaired by inventing a fixture role — it is recorded by
`SeedDataTest.teamManagementAndMemberManagementAreStillHeldTogether`, which fails the day a role
holds one without the other. **When it fails, that is the good case**: the discrimination has become
observable and the repair is to add that persona to `TeamEndpointTest.Gate`, not to re-align the
seed. The frontend carries the only test that can tell the two keys apart today
(`TeamsPage.test.tsx`, "refuses all four to a caller who administers members but not teams"),
because a test can mock a permission set the seed cannot produce.

---

## §72 — the run lifecycle

**Four mutations were run inline** and are recorded here as results rather than as work owed —
the slice's central claims are all gate-or-state claims, and a test suite that passes first time
over them is §66's warning, not evidence.

| | |
|---|---|
| **File** | `execution/RunStatusService.java` |
| **Mutation** | `CLOSE_RUN = "run:close"` → `"result:submit"` |
| **Result** | **RUN — reddens exactly 1**: *a Test Engineer may NOT end a run, and the run is untouched*. |
| **Why it is worth more than §71's** | This is the mutation §71 **could not write**. `V3` grants `run:close` to Lead and up and withholds it from Test Engineer while granting them `run:execute` and `result:submit`, so the key really is distinguishable by a persona the suite can build — where `company:teams` and `company:members` go to exactly the same roles and the equivalent swap reddens nothing. |

| | |
|---|---|
| **File** | `execution/ExecutionService.java`, `apply` |
| **Mutation** | delete the `run.isClosed()` arm |
| **Result** | **RUN — reddens exactly 2**: the closed-run refusal, and *reopening it lets the result through*. The second is the one that matters: it is what separates "the freeze works" from "this route is broken". |

| | |
|---|---|
| **File** | `execution/ExecutionService.java`, `apply` |
| **Mutation** | never call `run.begin(...)` |
| **Result** | **RUN — reddens exactly 2**: the first result starting the run, and the second result not re-starting it. |

| | |
|---|---|
| **File** | `execution/EvidenceService.java`, `remove` |
| **Mutation** | drop the freeze from the **delete** path only, keeping it on attach |
| **Result** | **RUN — reddens exactly 1**: *the AUTHOR cannot delete their own evidence once the run is closed*. The half-applied fix is the realistic mistake here — §67's whole argument was that a rule enforced on one of two paths reads as a bug on the other — so this is the mutation that proves both halves are held. |

### Queued — not yet run

| | |
|---|---|
| **File** | `execution/TestRun.java`, `applyStatus` |
| **Mutation** | keep `completedAt` on a reopen (`if (RunLifecycle.isClosed(target)) completedAt = now;` — set, never clear) |
| **Scope** | `--tests 'com.tcms.execution.ExecutionEndpointTest'` |
| **Must redden** | **exactly 1** — *reopening a completed run clears completedAt and keeps startedAt*. A live run carrying an end timestamp is §42's defect from the other side, and every duration the product ever computes reads that column. |

| | |
|---|---|
| **File** | `execution/RunLifecycle.java`, `decide` |
| **Mutation** | return `new Decision.Move(requested)` without normalizing the two live spellings |
| **Scope** | `--tests 'com.tcms.execution.ExecutionEndpointTest'` |
| **Must redden** | **exactly 1** — *reopening a run that never started returns it to NOT_STARTED, whatever was asked*. The row it produces (`IN_PROGRESS` with a null `started_at`) is one no other check in the suite forbids. |

| | |
|---|---|
| **Check** | migration-in-place |
| **Mutation** | — |
| **Must show** | **Nothing is owed. §72 adds no migration** — `test_run.status`, `started_at`, `completed_at` and `updated_by` have all been in `V1` from the beginning; `updated_by` is merely *mapped* for the first time. **`V22` is still free.** Recorded rather than omitted, because an absent row in this file reads as a check nobody thought about. |

---

## §73 — the plan lifecycle

`test_plan.status` had exactly one writer — the create factory, writing `DRAFT` — so four of five
values were unreachable and `closed_at` was a column nothing filled. `plan:edit`, `plan:approve` and
`plan:delete` had **zero consumers in Java**. Scope for every row below:
`--tests 'com.tcms.execution.PlanLifecycleEndpointTest'` (23 tests).

### Run in the slice — PASSED

| | |
|---|---|
| **File** | `execution/PlanLifecycle.java`, `permissionFor` |
| **Mutation** | drop the "unmaking" arm — decide the gate from the **target alone**, the reading of §6g a careful implementer would arrive at |
| **Result** | ✅ **RUN — reddens exactly 2**: *a Test Engineer cannot RESCIND an approval by asking for DRAFT*, and *nor un-start a plan somebody is executing against*. **This is the slice's central claim** and nothing else in the suite observes it: the mutated build still refuses the engineer the move *into* APPROVED, so a gate test written the obvious way passes on the defect. |

| | |
|---|---|
| **File** | `execution/PlanLifecycle.java` |
| **Mutation** | `APPROVE_PLAN = "plan:approve"` → `"plan:edit"` |
| **Result** | ✅ **RUN — reddens exactly 3**: the two above plus *a Test Engineer is refused the approval*. The keys **discriminate** — `V3` grants `plan:edit` to Test Engineer and withholds `plan:approve`, so unlike §71's pair a persona the suite can build tells them apart. |

| | |
|---|---|
| **File** | `execution/PlanDeleteService.java`, `apply` |
| **Mutation** | `if (liveRuns > 0)` → `if (false)` — count the runs and ignore the answer |
| **Result** | ✅ **RUN — reddens exactly 1**: *a plan with a live run is refused*. Worth stating what stays green: nothing else notices a plan being deleted out from under four runs, because `findPlanKeys` is not filtered on `deleted_at` and the runs keep rendering `TP-n`. |

| | |
|---|---|
| **File** | `execution/TestPlan.java`, `applyStatus` |
| **Mutation** | set `closedAt` on close and never clear it on reopen |
| **Result** | ✅ **RUN — reddens exactly 1**: *closing stamps closed_at, and reopening to ACTIVE clears it again*. §72 queued the identical mutation for `TestRun.completedAt` and it is **still queued** — the two rows should be run together. |

### Queued — not yet run

| | |
|---|---|
| **File** | `execution/PlanLifecycle.java`, `decide` |
| **Mutation** | allow `CLOSED` from any state (drop the `current == ACTIVE` test) |
| **Must redden** | **exactly 1** — *CLOSED is reachable only from ACTIVE*. The pair to watch: with this defect in, `PlanDeleteService`'s refusal and the ladder stop partitioning the space, and a plan nobody executed can be recorded as a finished release. |

| | |
|---|---|
| **File** | `execution/PlanStatusService.java`, `setStatus` |
| **Mutation** | resolve the permission from the request alone — pass `PlanLifecycle.EDIT_PLAN` unconditionally as the `RequiredPermission` |
| **Must redden** | **exactly 3** (the two edge tests + the approval refusal). Distinct from mutation 1 above: that one keeps the row in the decision and mis-reads it, this one **stops reading the row at all**, which is the shape a later refactor toward `writeGranted` would produce. |

| | |
|---|---|
| **File** | `execution/PlanStatusService.java`, `apply` |
| **Mutation** | return `Updated` for `Decision.Unchanged` **after** calling `move(...)` — i.e. treat a repeated click as a real move |
| **Must redden** | **exactly 1** — *setting the status a plan already has is 200 and writes NOTHING*. The visible symptom is an audit trail where the change that mattered is buried under rows recording that nothing did. |

| | |
|---|---|
| **Check** | migration-in-place |
| **Mutation** | — |
| **Must show** | **Nothing is owed. §73 adds no migration** — `status`, `closed_at`, `updated_by` and `deleted_at` have all been in `V1` from the beginning; `closed_at` and `updated_by` are merely *mapped* for the first time. **`V22` is still free.** |

---

## §74 — the plan editor

**Slice:** `PATCH /projects/{projectId}/plans/{planId}` — the first writer `entry_criteria`,
`exit_criteria` and `test_plan_version` have ever had, plus the scope on the detail response.

### Run in the slice — PASSED

| | |
|---|---|
| **File** | `execution/PlanVersions.java`, `decide` |
| **Mutation** | disable the unchanged-hash arm, so every save writes a version row |
| **Result** | ✅ **RUN — reddens exactly 3**: *renaming a plan writes NO version*, *saving the same scope twice writes nothing the second time*, and *the audit row says whether the SCOPE moved*. **This is the slice's central claim.** Every scope test and every criteria test stays green, so the cheaper "bump on save" implementation would have shipped with the rest of the class passing — and the damage is invisible for months, surfacing as a version history whose entries all have identical snapshots. |

| | |
|---|---|
| **File** | `execution/PlanUpdateService.java`, `apply` |
| **Mutation** | drop the `PlanLifecycle.isClosed` refusal |
| **Result** | ✅ **RUN — reddens exactly 2**: *editing a CLOSED plan is refused with 409* and *a closed plan refuses BEFORE If-Match*. The second is the placement, not the rule — it goes red only because the freeze sits above the precondition check. |

| | |
|---|---|
| **File** | `execution/PlanEdit.java`, `validateUpdate` |
| **Mutation** | read an absent list as *clear it* rather than *leave it alone* (both the scope and the criteria arms) |
| **Result** | ✅ **RUN — reddens exactly 4**, and the two unexpected ones are the finding: besides the two tests that name the rule, *renaming a plan writes NO version* and *the audit row says whether the SCOPE moved* also go red — because under this defect a rename **empties the scope**, which is a real change, which writes a version. The version tests are a second, independent detector of the same defect. |

| | |
|---|---|
| **File** | `execution/TestPlan.java` |
| **Mutation** | remove `@Version` from `lockVersion` — i.e. **re-introduce the defect this slice found** |
| **Result** | ✅ **RUN — reddens exactly 1**: *a matching If-Match saves; a stale one is 409*. Verified the repair rather than assumed it: before this slice the column was mapped without `@Version`, so `lockVersion` was `0` on every plan for ever and no `If-Match` could fail. |

### Queued — not yet run

| | |
|---|---|
| **File** | `execution/PlanUpdateService.java`, `apply` |
| **Mutation** | drop the `scopeItems.flush()` between the delete and the inserts |
| **Must redden** | **unknown, and that is why it is queued.** Hibernate orders inserts before deletes within a flush, so re-adding a scope item that is still present should collide on nothing (`plan_scope_item` has no unique constraint on `(plan_id, suite_id)` — which is itself worth checking). If **nothing** reddens, the flush is a comment rather than a mechanism, and the honest outcome is to say so. |

| | |
|---|---|
| **File** | `execution/PlanUpdateService.java`, `apply` |
| **Mutation** | resolve the live-id sets from the **submitted** ids only, dropping the union with the stored scope |
| **Must redden** | **0 with the current fixture, and the point is to add the test that makes it 1.** A suite deleted *after* it was added to a plan is carried forward unchecked by this defect; no test yet deletes a suite out from under a live plan. Write that test first, then run the mutation. |

| | |
|---|---|
| **File** | `execution/PlanCriteria.java`, `isKnownOperator` |
| **Mutation** | replace the `satisfies(0d, op, 0d) != null` delegation with a hard-coded `Set.of(">=", ">", "<=", "<")` — i.e. a second copy of the operator list, already missing two |
| **Must redden** | **exactly 1** — the `=`/`!=` arm of *an unusable operator and a criterion with no threshold are both 400* is not currently covered, so this may redden **0**. If it does, that is §70's finding again in a new place and the fix is a test that authors a `=` criterion, not a wider set. |

| | |
|---|---|
| **Check** | migration-in-place |
| **Mutation** | — |
| **Must show** | **Nothing is owed. §74 adds no migration** — `entry_criteria`, `exit_criteria` and the whole `test_plan_version` table have been in `V1` from the beginning; the table gets its first entity and its first row, and `@Version` on `lock_version` changes no DDL. **`V22` is still free.** |

---

## §75 — the suite lifecycle, and the case gate that was keyed on the target

**Six mutations run (four backend, two frontend), three queued.**

### Run inline

| | |
|---|---|
| **File** | `repository/LifecycleLadder.java`, `permissionFor` |
| **Mutation** | drop the `unmaking` arm — i.e. **restore the target-keyed rule this slice exists to correct** |
| **Result** | ✅ **RUN — reddens exactly 2 out of 112**, and the pair is the whole point: *an engineer cannot take a lead's approval back by asking for DRAFT* (suite) and *an engineer cannot pull an APPROVED case back — the row is skipped, not moved* (case). **The case one is the pre-existing defect, reproduced.** Everything else in both files stays green, which is why the weaker rule shipped in §62 and survived §73 naming it. |

| | |
|---|---|
| **File** | `repository/SuiteStatusService.java`, the `writeGrantedOnTarget` permission lambda |
| **Mutation** | replace `LifecycleLadder.permissionFor(...)` with the constant `suite:manage` |
| **Result** | ✅ **RUN — reddens exactly 3**: *an engineer cannot approve*, *…cannot take a lead's approval back*, *…cannot deprecate*. The gate **discriminates**, unlike §71's: `V3` withholds `suite:approve` from Test Engineer while granting `suite:manage`, so the persona that tells the two keys apart exists. |

| | |
|---|---|
| **File** | `repository/LifecycleLadder.java`, `decide` |
| **Mutation** | make `IN_REVIEW` reachable from every state |
| **Result** | ✅ **RUN — reddens exactly 1**: *review is reachable only from draft — an approved suite loses its approval first*. Low, and correctly so: this rule is new to the *case* side as well, but no case test authors an `APPROVED → IN_REVIEW` bulk move any more (the test that did was rewritten, since its own recorded reasoning was the thing §75 reversed). |

| | |
|---|---|
| **File** | `repository/CaseBulkService.java`, `evaluateStatus` |
| **Mutation** | delete the per-row `!approver && APPROVE_CASE.equals(permissionFor(...))` skip |
| **Result** | ✅ **RUN — reddens exactly 2**: *an engineer cannot pull an APPROVED case back* and *an engineer cannot deprecate*. The request-level gate stays green throughout, which is the evidence that the floor genuinely could not see these edges. |

| | |
|---|---|
| **File** | `frontend/src/features/cases/SuiteTree.tsx`, `suiteMoves` |
| **Mutation** | put `APPROVED → DRAFT` on `suite:manage` — the client's copy of the same defect |
| **Result** | ✅ **RUN — reddens exactly 1**. Worth having because the client's copy is a *disabling hint* the compiler cannot check: both keys are strings, so a wrong row is a control that enables and then 403s. |

| | |
|---|---|
| **File** | `frontend/src/features/cases/SuiteTree.tsx`, the node badge |
| **Mutation** | render the status badge unconditionally |
| **Result** | ✅ **RUN — reddens exactly 1**. Run because all 17 of that file's tests passed on the first attempt, which §66 established is the moment to distrust them. |

### Queued — not yet run

| | |
|---|---|
| **File** | `repository/LifecycleLadder.java`, `decide` |
| **Mutation** | let `DEPRECATED → APPROVED` through instead of refusing it |
| **Must redden** | **exactly 1** — *a deprecated suite comes back as a draft, never straight to approval*. On the **case** side this should redden **0**, because no bulk test moves a deprecated case at all. If it does redden 0 there, the honest fix is a case-side test, not a wider rule: the mock's `lifecycleLadder.ts` carries the same arm and nothing on that side would catch a divergence either. |

| | |
|---|---|
| **File** | `repository/CaseBulkService.java`, `run` |
| **Mutation** | resolve `approver` as `true` unconditionally rather than asking `holds(...)` |
| **Must redden** | **the same 2 as the fourth mutation above** — and the reason to run it separately is that it tests a *different* line. The fourth proves the check is consulted; this proves the fact it is consulted with is actually resolved from the caller, rather than from a constant that happens to be right for the lead persona. |

| | |
|---|---|
| **File** | `repository/SuiteStatusService.java`, `move` |
| **Mutation** | make the `Decision.Unchanged` arm fall through to `move(...)` |
| **Must redden** | **exactly 1** — *setting the status a suite already has writes nothing at all*, on the audit assertion rather than on the response, since the body is identical either way. That is the shape of the whole row: the screen cannot tell these two implementations apart. |

| | |
|---|---|
| **Check** | migration-in-place |
| **Mutation** | — |
| **Must show** | **Nothing is owed. §75 adds no migration** — `suite.lifecycle_status` and its four-value check constraint have been in `V1` from the beginning; this slice is the first thing to write to the column. **`V22` is still free**, and the reviewer-apparatus slice that follows will need it (`project` has no `settings` column for §6g's per-project opt-in). |

---

## §76 — the review record: the reviewer apparatus's first writer

**Four mutations run inline, three queued.** The slice's own tests all passed on the first run,
which §66 established is the moment to distrust them — and the first frontend mutation below found
exactly that: a `waitFor(...toBeDisabled())` satisfied by the pending state, proving nothing.

### Run

| | |
|---|---|
| **File** | `project/ReviewSubjectType.java`, `reviewPermission()` |
| **Mutation** | answer `project:read` — a key every role holds — instead of the subject type's own |
| **Result** | ✅ **RUN — reddens exactly 1**: *a Viewer holds case:comment and not plan:review, and is refused with 403*. Discriminating rather than vacuous, unlike §71's pair: `V3` grants Viewer the **comment** tier and withholds the **review** tier, which is §6g's three tiers made observable. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `parseVerdict` |
| **Mutation** | accept `APPROVED` and `REJECTED` as peer verdicts |
| **Result** | ✅ **RUN — reddens exactly 2**, one per refused verdict. This is the slice's own gate: a participation endpoint that took either would be a second, cheaper door to the sign-off §6g gates once. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `moved` |
| **Mutation** | `withdrawnByItsSubmitter = false` — treat every send-back as a rejection |
| **Result** | ✅ **RUN — reddens exactly 1**: *the submitter taking it back is a WITHDRAWAL, and writes no verdict*. The reason `V22` adds a fourth state rather than reusing `REJECTED`, in one assertion. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `moved` |
| **Mutation** | return early instead of closing an open review |
| **Result** | ✅ **RUN — reddens exactly 6** — every close, on all three subject types, plus the 409 on a closed review. The breadth is the point: the open/close pair is one rule and half of it failing is not a local defect. |

### Queued — not yet run

| | |
|---|---|
| **File** | `project/ReviewService.java`, `moved` |
| **Mutation** | re-stamp `subjectVersionNo` on the open review each time `moved` is called with `IN_REVIEW` |
| **Must redden** | **exactly 1** — *an artifact edited while in review keeps the version it was submitted at*. Worth running because the assertion is the panel's whole reasoning: a re-stamped version makes every review agree with its artifact by construction, and the screen looks *more* consistent, not less. |

| | |
|---|---|
| **File** | `project/ReviewRepository.java`, `findInProject` |
| **Mutation** | resolve through `findById` instead — RLS still scopes it to the company |
| **Must redden** | **exactly 1** — *a sibling project's review id is 404, not somebody else's review*. The §64/§68/§69 family: every cross-company test in the suite stays green, because the row is in the right company and the wrong project. |

| | |
|---|---|
| **Check** | `V22`'s partial unique index |
| **Mutation** | — (a race, not a code change) |
| **Must show** | Two concurrent `PUT …/status` submissions of one artifact: **one succeeds and one fails on `uq_review_open_per_subject`**, rather than both inserting. Not reproducible through the suite — it needs two interleaved transactions — and recorded rather than implied, because the application check (`findOpenForSubject`) is exactly the shape §69's and §71's migrations existed to back. |

| | |
|---|---|
| **Check** | migration-in-place |
| **Mutation** | — |
| **Must show** | **`V22` lands with this slice** (`V22__review_state_withdrawn_and_one_open_review.sql`) and is the first migration since `V21`. It drops and re-adds `review_state_check` and adds two indexes — no data rewrite, because the table has never held a row on any instance. **`V23` is the next free number.** |

---

## §77 — per-project review settings (`V23`)

**Written 2026-08-02. Nothing in this section was run inline** — the policy restated above, followed
this time.

One row in this section is unlike every other row in this file: the slice's *own gate run* produced
a mutation-shaped result for free. Enabling the opt-in with a default of **off** reddened **18 of
1419** tests at once, 17 of them in `ReviewEndpointTest`, because that class's fixture creates
projects by direct insert and therefore gets exactly what a real newly provisioned project gets. That
is the opt-in demonstrably working, and it is recorded here rather than as a mutation because it
cannot be re-run: the fixture now enables review, so the "before" state no longer exists.

### Queued — not yet run

| | |
|---|---|
| **File** | `project/ReviewService.java`, `moved` |
| **Mutation** | drop the `policy.reviewEnabledFor(subjectType)` condition from the opening arm, so a review opens whatever the setting says |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 3** — *with review off, submitting a plan opens no review at all*, *the opt-in is per artifact type, not per project*, and *with review off for plans, RE_REVIEW does not fire*. The third is the interesting one: it fails for a **second-order** reason (a review exists, so `reReviewOnEdit`'s other arm is reached), which is what makes it evidence the two settings are genuinely wired to the same switch. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `refusesEach` |
| **Mutation** | drop the author check — delete the `!policy.authorMayApproveOwnWork() && …` block |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *the submitter is refused their own sign-off*. **Only one**, and that is the property worth confirming: the two neighbouring tests (*a different lead may approve*, *turning the rule off lets the submitter approve*) must stay green, or they are measuring the gate rather than the rule. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `standingLgtms` |
| **Mutation** | count every `LGTM` row rather than each person's latest verdict — replace the per-user map with a filter over the whole trail |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *a peer who changes their mind stops counting*. This is the row most worth running: the cheap implementation passes *refused until enough peers have said LGTM* perfectly, and the trail is append-only precisely so that relenting is visible. A green suite under this mutation would mean the review trail is decorative. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `standingLgtms` |
| **Mutation** | stop excluding the submitter — delete the `row.getUserId().equals(submitters.get(...))` skip |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *the submitter's own LGTM does not count as a peer review*. Independent of the author-cannot-approve rule by construction (that test runs with the rule **off**), so a mutation reddening both would mean the two settings have been collapsed into one. |

| | |
|---|---|
| **File** | `project/Review.java`, `open` |
| **Mutation** | write `0` to `requiredPeerReviews` instead of the setting's value (the pre-`V23` behaviour) |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 3** — the three `PeerMinimum` tests that require a peer. *raising the minimum does not freeze a review already open* must stay **green**, because it asserts the opposite direction. |

| | |
|---|---|
| **File** | `project/Review.java`, `open` |
| **Mutation** | read the live setting in `refusesEach` instead of `review.getRequiredPeerReviews()` |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *raising the minimum does not freeze a review already open*. The whole reason the count is a column on `review` rather than only on `project_settings`. |

| | |
|---|---|
| **File** | `execution/PlanUpdateService.java` |
| **Mutation** | drop the `unApproves` demotion (delete the `plan.applyStatus(PlanStatus.DRAFT, …)` block) |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 2** — *RE_REVIEW returns an edited approved plan to draft* and *the demotion does NOT cost plan:approve*. *RETAIN leaves an approved plan approved* must stay green, which is what separates the two modes. |

| | |
|---|---|
| **File** | `execution/PlanUpdateService.java` |
| **Mutation** | gate the demotion on `plan:approve` — refuse the edit when the caller lacks it |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *the demotion does NOT cost plan:approve — an engineer's edit performs it*. **The single most important row in this section.** This mutation is the "obvious" reading, it looks like §75's rescind defect being closed, and it is wrong for the three reasons written on `EditAfterApproval.RE_REVIEW`; the test is the only thing standing between this slice and a future session "fixing" it. If this row reddens nothing, the test is not asserting authorship at all. |

| | |
|---|---|
| **File** | `project/ProjectSettingsService.java`, `effective` |
| **Mutation** | throw on a missing row instead of answering `ProjectSettings.defaults()` |
| **Scope** | `com.tcms.project.ReviewEndpointTest`, `com.tcms.project.ProjectEndpointTest` |
| **Must redden** | **unknown, and finding out is the point.** `V23` backfills every project and `ProjectCreateService` writes one, so the honest expectation is **nothing reddens** — which would mean the tolerance is untested and the argument for it (fail to the safe state, never 500 on a case list) rests on reading alone. A row that reddens tells us which path still produces a settings-less project. |

| | |
|---|---|
| **File** | `project/ProjectSettingsService.java`, `find` |
| **Mutation** | gate the read on `project:settings` (swap `readVisible` for `writeGranted`) |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *a Viewer can read them*. The one test standing behind §6g's "no extra UI at all" being implementable by a client rendering for a non-admin. |

| | |
|---|---|
| **Check** | migration-in-place |
| **Mutation** | — |
| **Must show** | **`V23` lands with this slice** and is the first migration since `V22`. It creates a table, one index, an RLS policy and **backfills one row per existing project** — the first migration in the product to create a table after `V1`, so the things to confirm on a real box are: the policy carries `V4`'s `nullif(...)` predicate and not `V1`'s (a table born with the old spelling reintroduces the reset-GUC 500 on exactly one table), the `tcms_app` default privileges actually covered the new table without an explicit `GRANT`, and the backfill left **exactly one row per project**. `SchemaConventionTest`'s policy count moves 56 → 57. **`V24` is the next free number.** |

---

## §79 — review comments (`V24`)

`review_comment` was the last of the three review tables with no entity, no query and no writer, and
`case:comment` the last review permission with no consumer in Java. What must be proved is the
**asymmetry**: writing a remark costs one key for all three subject types, and resolving one costs
the subject's own `*:review`. A gate that read the same key for both would pass most of these tests.

| | |
|---|---|
| **File** | `project/ReviewService.java`, `comment` |
| **Mutation** | ask `review.getSubjectType().reviewPermission()` instead of `ReviewSubjectType.COMMENT_PERMISSION` |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *a Viewer holding case:comment may comment on a PLAN review*. The one-key decision, and the Viewer is the only persona in the seeded catalogue that can tell the two keys apart. If this reddens nothing, the comment gate is untested and §6b's whole reason for `case:comment` — the client who reviews coverage without write access — is unreachable. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `resolveComment` |
| **Mutation** | ask `ReviewSubjectType.COMMENT_PERMISSION` instead of `reviewPermission()` |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *a Viewer cannot resolve*. The other half of the row above, and the pair is the point: each mutation reddens one test and they are different tests. Both green under one key would mean the panel's two controls are one control. |

| | |
|---|---|
| **File** | `project/ReviewCommentRepository.java`, `findInReview` |
| **Mutation** | drop the `c.reviewId = :reviewId` predicate (resolve a comment by id alone, RLS-scoped to the company) |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 2** — *a parent from another project's review is refused* and *a comment id from another review is 404*. §64's `sharedStepId` family, third instance; the second test also asserts the foreign row was **not** resolved, which a status-only assertion would have missed. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `applyResolution` |
| **Mutation** | drop the `comment.isResolved() != request.resolved()` guard (write unconditionally) |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *resolving an already-resolved thread is a 200 that re-stamps nothing*. **This mutation is the defect the slice actually shipped in its first draft** and the test caught it: an unconditional write re-stamps `resolved_at` and hands `resolved_by` to whoever clicked last, so `V24`'s two columns would name the second person to agree rather than the person who decided. |

| | |
|---|---|
| **File** | `project/ReviewCommentEdit.java`, `validate` |
| **Mutation** | accept a parent that is itself a reply (drop `\|\| !parent.isRoot()`) |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *a reply to a reply is a field error*. The depth rule is what makes `ReviewCommentView.replies` non-recursive in practice; without it the server stores a grandchild the renderer drops on the floor, which is a remark nobody can see or answer. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `addComment` / `applyResolution` |
| **Mutation** | remove the `refuseIfClosed` call from **both** comment paths |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *a closed review refuses the comment with 409*. Only one, because the resolution path's closed-review case is not covered by a test of its own; **that gap is the finding this row exists to surface**, and the repair is a test rather than a weaker mutation. |

| | |
|---|---|
| **File** | `schema/SeedDataTest.java` |
| **Mutation** | — (run `everyReviewerIsAlsoACommenter` after granting `plan:review` to a role without `case:comment` in a scratch migration, or simply read its failure message) |
| **Scope** | `com.tcms.schema.SeedDataTest` |
| **Must show** | the check is **not vacuous** — the query must select at least one role when the ladder is broken. §71's `company:teams` finding is the warning here: a computed check that can never select anything is a check nobody is running. |

| | |
|---|---|
| **Check** | migration-in-place |
| **Mutation** | — |
| **Must show** | **`V24` lands with this slice.** It adds a column, a CHECK and an index to a table that has never held a row on any real instance, so the in-place run should be uneventful — which is the thing to confirm rather than assume. `SchemaConventionTest`'s policy count stays **57** (no new table) and the new index must lead with `company_id`. **`V25` is the next free number.** |

---

## §80 — reviewer assignment

Eight rows, and the first two are the pair to run together: they are the slice's one gate decision,
and each is only meaningful beside the other.

| | |
|---|---|
| **File** | `project/ReviewService.java`, `assign` |
| **Mutation** | ask `"plan:approve"` (a constant, since the enum has no approve accessor) instead of `review.getSubjectType().reviewPermission()` |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *an engineer may ask — the request is the reviewing tier's, not the approver's*. `ENGINEER` holds `plan:review` and **not** `plan:approve`, which is what makes this discriminating rather than vacuous (§71's `company:teams` had no such persona). If it reddens nothing, the request has been quietly confined to the people whose sign-off it exists to prepare. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `assign` |
| **Mutation** | ask `ReviewSubjectType.COMMENT_PERMISSION` instead of `reviewPermission()` |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *a Viewer cannot ask — the bottom tier does not fill other people's queues*. The other end of the row above, and the pair is the point: the key must be **neither** the tier below nor the tier above. Both green under one key would mean the gate is not measuring anything. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `store` |
| **Mutation** | drop both `findForUser`/`findForTeam` guards and always `saveAndFlush` |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *asking somebody already asked writes nothing and keeps the original asker*. This is §79's resolution defect in the one place it would recur: an unconditional write re-stamps `assigned_by`, so the field kept to record who asked names whoever clicked last. Note the expected failure mode is a **constraint violation**, not a duplicate row — `V25`'s partial unique index is the backstop behind the check, and seeing it fire is confirmation the backstop is real. |

| | |
|---|---|
| **File** | `project/ReviewAssigneeEdit.java`, `validate` |
| **Mutation** | drop the `!userReachesProject` arm (accept any user id the FK admits) |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 2** — *a colleague with no grant on the project cannot be asked* and *a SIBLING COMPANY's account is refused with the same sentence, and stored never*. §71's defect one table over: `assignee_user_id` references instance-level `app_user`, so an outsider's id satisfies the FK, the company column, RLS and every constraint. Both tests assert **no row was written**, which a status-only assertion would have missed. |

| | |
|---|---|
| **File** | `project/ReviewAssigneeEdit.java`, `validate` |
| **Mutation** | drop the `userId.equals(review.getCreatedBy())` arm |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *the submitter cannot be asked — their own review would not count*. The rule is **derived** rather than invented: `standingLgtms` already skips the submitter's own LGTM, so without this arm the product stores a request that can never be discharged. If this reddens nothing, the derivation is not being checked and the two halves are free to drift apart. |

| | |
|---|---|
| **File** | `project/ReviewAssignmentRepository.java`, `findInReview` |
| **Mutation** | drop the `a.reviewId = :reviewId` predicate (resolve an assignment by id alone, RLS-scoped to the company) |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 1** — *an assignment id belonging to another review is 404, and deletes nothing*. §64's `sharedStepId` family a sixth time; the test asserts the foreign row **survived**, so a status-only assertion would have passed against a delete that happened anyway. |

| | |
|---|---|
| **File** | `project/ReviewService.java`, `addAssignee` / `removeAssignee` |
| **Mutation** | remove the `refuseIfClosed` call from **both** assignment paths |
| **Scope** | `com.tcms.project.ReviewEndpointTest` |
| **Must redden** | **exactly 2** — *a closed review takes no request* and *a closed review refuses the withdrawal too, and the row survives*. Two rather than §79's one, because both paths have a test of their own here; that asymmetry is deliberate and is the repair §79's sixth row asked for, applied to the new pair rather than retrofitted to the old one. |

| | |
|---|---|
| **Check** | migration-in-place, and the cascade |
| **Mutation** | — |
| **Must show** | **`V25` lands with this slice**, creating a table rather than altering one. `SchemaConventionTest`'s policy count moves **57 → 58** and both unique indexes plus the read index must lead with `company_id`. Separately confirm the cascade by hand on a migrated database: deleting a `team` row removes its `review_assignment` rows and **nothing else** — the decision §71's 409 is deliberately not extended to, and the one behaviour here that lives in the DDL rather than in Java. `deletingATeamCascadesOntoItsRequests` covers it, and this row is the reminder to look at the real box too. **`V26` is the next free number.** |

---

## §81 — the reviewer's inbox

Nine rows, seven backend and two frontend. The slice added no gate and no new key — the board's
read gate (`ProjectService.readVisible`) already covers it — so every row here is about **which rows
become a card**, which is the whole of the feature. `BoardFacts.pendingReviews` is one SQL statement
and six of the seven backend mutations are one predicate removed from it.

| | |
|---|---|
| **File** | `work/BoardFacts.java`, `pendingReviews` |
| **Mutation** | drop `AND r.state = 'IN_REVIEW'` |
| **Scope** | `com.tcms.work.BoardEndpointTest` |
| **Must redden** | **exactly 2** — *a review that is over leaves no card, though the request row survives it* and *the whole set: only live, unanswered requests…*. Unassigning is the only thing that deletes a `review_assignment` row (§80), so **every request ever made is still in the table**; without this predicate the board carries all of them for ever, which is the "append-only log" failure §6l names for executions arriving through a different generator. |

| | |
|---|---|
| **File** | `work/BoardFacts.java`, `pendingReviews` |
| **Mutation** | drop the `NOT EXISTS (SELECT 1 FROM review_participation …)` clause |
| **Scope** | `com.tcms.work.BoardEndpointTest` |
| **Must redden** | **exactly 2** — *a review I have already answered has left the board* and *the whole set…*. The fixture's verdict is `CHANGES_REQUESTED` on purpose: the rule is that the card leaves when the person has **spoken**, not when they agreed, and a mutation that only checked LGTM would pass against a suite whose fixture approved everything. |

| | |
|---|---|
| **File** | `work/BoardFacts.java`, `pendingReviews` |
| **Mutation** | delete the second arm of the `asked` CTE (the `team_membership` join), leaving only directly-named users |
| **Scope** | `com.tcms.work.BoardEndpointTest` |
| **Must redden** | **exactly 2** — *a review asked of a team is on every member's board, expanded at read time* and *the project board carries one card PER REVIEWER, not one per review*. This is the mirror of §80's refusal to expand at write time, so it is the row that proves the two halves are deliberate rather than inconsistent: **VIEWER is named on no assignment row anywhere**, so if this reddens nothing, a team request reaches nobody and §6g's "any member of the team can action it" is unimplemented. |

| | |
|---|---|
| **File** | `work/BoardFacts.java`, `pendingReviews` |
| **Mutation** | `UNION` → `UNION ALL` in the `asked` CTE |
| **Scope** | `com.tcms.work.BoardEndpointTest` |
| **Must redden** | **exactly 1** — *being asked twice — by name and through a team — is still one card*. Two cards would be the board telling one person they owe two verdicts on one artifact, and the duplicate ids would collide in React's keying as well. |

| | |
|---|---|
| **File** | `work/BoardFacts.java`, `pendingReviews` |
| **Mutation** | drop `AND COALESCE(c.id, s.id, p.id) IS NOT NULL` |
| **Scope** | `com.tcms.work.BoardEndpointTest` |
| **Must redden** | **exactly 1** — *the whole set: only live, unanswered requests…*, and **this row is why that test asserts a set rather than an absence.** With the guard gone, the review of the soft-deleted TC-8 joins to three NULLs and produces a card with **no key and no title** — which every `doesNotContain("TC-8")` in the class passes cleanly. A card that renders as an empty row is the failure, and only an exact set can see it. |

| | |
|---|---|
| **File** | `work/BoardService.java`, `cardFor(PendingReview…)` |
| **Mutation** | `review.subjectType()` / `review.subjectId()` → `"REVIEW"` / `review.reviewId()` |
| **Scope** | `com.tcms.work.BoardEndpointTest` |
| **Must redden** | **exactly 1** — *a review I have been asked for is a card naming the artifact, not the review*. The mutation is the **plausible** implementation — it is what the other two generators appear to do — and it compiles, renders and leaves the SPA with an id no route accepts, on the one card whose entire purpose is to be clicked. |

| | |
|---|---|
| **File** | `work/BoardService.java`, `isDerived` |
| **Mutation** | remove the `PREFIX_REVIEW` arm |
| **Scope** | `com.tcms.work.BoardEndpointTest` |
| **Must redden** | **exactly 1** — *a review card cannot be moved — the sign-off has one door and this is not it*, which flips 409 → 404. Worth running because a review card's id is the one derived id that is **not** prefix-plus-one-UUID, so the `UUID.fromString` accident that would also have caught the other two prefixes still applies here — and the resulting 404 ("that card is gone") invites a reload that brings it straight back. |

| | |
|---|---|
| **File** | `frontend/src/mocks/db/workItems.ts`, `reviewCards` |
| **Mutation** | drop the `review.participations.some(…)` skip |
| **Scope** | `npx vitest run src/mocks/db/workItems.test.ts` |
| **Must redden** | **exactly 1** — *expands a team at read time, and drops anybody who has already spoken*. The mock is the second implementation of this query and it is the one anybody actually drives; a rule enforced only on the server is a rule mock mode teaches people to disbelieve. |

| | |
|---|---|
| **File** | `frontend/src/features/board/BoardPage.tsx`, `linkFor` |
| **Mutation** | return the `search` fallback for every card (the pre-§81 behaviour) |
| **Scope** | `npx vitest run src/features/board` |
| **Must redden** | **exactly 3** — the case, plan and suite link tests. The pre-§81 code *is* this mutation, and it was correct until this slice drew the first cards about suites and plans: a review of `TP-5` sent to a case search renders "no cases match", which reads as the plan having been deleted. |

| | |
|---|---|
| **Check** | no migration |
| **Mutation** | — |
| **Must show** | **`V26` is still free.** The inbox needed no schema: §80 added `ix_review_assignment_assignee_user` one slice ahead of its query, for this query. Confirm it is the index the plan actually uses on a populated database (`EXPLAIN` the generated statement), because an index added before its consumer is an index nobody has ever seen chosen. |

## §82 — §6g's gating rules: the last unbuilt half of the review workflow

The two rules that close §6g's mechanics list. Both are **opt-in and off by default**, so every
mutation below needs the fixture to turn the setting on first — which is itself the first thing to
check, because a mutation measured on a project with the gate off reddens nothing and reads as
"the rule is not enforced" when it means "the rule was never asked".

**The first one to run is the plan edge.** It is the mutation an ordinary reading of §6g produces,
it looks like a simplification, and its consequence is unreachable by any test that does not think
to close a plan first.

| | |
|---|---|
| **File** | `execution/PlanLifecycle.java`, `startsWithoutApproval` |
| **Mutation** | drop the `current == DRAFT` half — `return target == PlanStatus.ACTIVE` |
| **Scope** | `com.tcms.execution.PlanLifecycleEndpointTest` |
| **Must redden** | **exactly 1** — *but a CLOSED plan still REOPENS*. This is the target-keyed reading of §6g and it is what somebody writes first: it passes the draft test, the approved test, both override tests and the independence test, and it makes **every closed plan on a gated project permanently un-reopenable by anybody**, because no legal move takes `CLOSED` back to `APPROVED`. The single reddened test is the whole guard. |

| | |
|---|---|
| **File** | `execution/RunCreateService.java`, `gate` |
| **Mutation** | ask `cases.unapprovedCases(projectId, request.caseIds())` instead of `edit.caseIds()` |
| **Scope** | `com.tcms.execution.RunCreateEndpointTest` |
| **Must redden** | **exactly 1** — *a run from a PLAN is gated too*. The explicit-list tests all still pass, so the gate looks enforced; what is gone is the rule on the path a release run actually takes. Nothing in the response distinguishes the two, and the bypass costs no permission and leaves no audit row. |

| | |
|---|---|
| **File** | `execution/RunCreateService.java`, `gate` |
| **Mutation** | drop the unapproved cases from `edit.caseIds()` and proceed, instead of refusing |
| **Scope** | `com.tcms.execution.RunCreateEndpointTest` |
| **Must redden** | **at least 3** — the refusal tests. Run it anyway for what it *doesn't* break: the trimmed run is created successfully, with a shorter checklist and a pass rate over a denominator nobody chose, which is `RunCreateService`'s own partial-materialisation argument arriving through a door it did not expect. |

| | |
|---|---|
| **File** | `execution/CaseFacts.java`, `unapprovedCases` |
| **Mutation** | `c.lifecycle_status IN ('DRAFT','IN_REVIEW')` instead of `<> 'APPROVED'` |
| **Scope** | `com.tcms.execution.RunCreateEndpointTest` |
| **Must redden** | **0 — and that is the finding to record.** No test seeds a `DEPRECATED` case into a gated run, so the enumeration passes the whole suite while letting **retired content into a release run**, which is exactly what the gate exists to stop. Either write that test while the mutation is applied, or accept that the `<> 'APPROVED'` spelling is held by a comment. Do not skip this row because it reddens nothing; that is what it is reporting. |

| | |
|---|---|
| **File** | `execution/ApprovalGating.java`, `Verdict.fired` |
| **Mutation** | `return new Verdict(null, true)` unconditionally |
| **Scope** | `com.tcms.execution.RunCreateEndpointTest`, `PlanLifecycleEndpointTest` |
| **Must redden** | **exactly 2** — one per aggregate (*an engineer asking to override is refused all the same*). Two, not one, is the evidence the shared record really is shared: a single red test would mean one of the two services had quietly grown its own copy of the rule. |

| | |
|---|---|
| **File** | `execution/RunCreateService.java` / `PlanStatusService.java`, the audit payload |
| **Mutation** | write `approvalGateOverridden` unconditionally rather than only when `overridden` |
| **Scope** | both endpoint tests |
| **Must redden** | **exactly 2** — *the flag on an UNGATED project is not an override*. The mutation is the tidier-looking code, and it records every ordinary run and every ordinary plan start as a policy bypass, which is precisely the signal §6g's word *documented* exists to keep meaningful. |

| | |
|---|---|
| **File** | `project/ReviewSettings.java` consumers — `RunCreateService.gate`, `PlanStatusService.gate` |
| **Mutation** | AND each gate with `reviewEnabledFor(...)` |
| **Scope** | both endpoint tests |
| **Must redden** | **exactly 2** — *gating binds with review switched OFF*. This is the §82 decision under test and the mutation is the intuitive reading (gating is part of §6g, review is off, therefore inert). It disables the gate on every project that never turned peer review on — most of them, and including every project that enabled gating for the reason it exists. |

| | |
|---|---|
| **File** | `project/ProjectService.java`, `holds` |
| **Mutation** | `return true` |
| **Scope** | `com.tcms.repository.CaseBulkEndpointTest`, both execution endpoint tests |
| **Must redden** | **at least 3**, spanning **two modules** — the two override-refusal tests plus `CaseBulkService`'s refusal wording. Worth running for the span rather than the count: §82 moved this method out of `CaseBulkService`, and the reason to keep it in one place is that three call sites depend on it answering the same question. |

| | |
|---|---|
| **Check** | the fixture's approve path |
| **Mutation** | in `RunCreateEndpointTest.approve`, set `lifecycle_status` by SQL instead of walking the bulk endpoint |
| **Must show** | **nothing reddens — which is why the SQL version must not be used.** The tests would then assert the gate against a column this fixture wrote, not against the state the product produces. Recorded so the shortcut is refused deliberately rather than found convenient later. |

| | |
|---|---|
| **Check** | `V26` and the policy count |
| **Mutation** | — |
| **Must show** | **58 policies, unchanged** — `V26` adds two columns and no table, so `SchemaConventionTest`'s count must not move. Confirm on a **migrated** database rather than a fresh Testcontainer: `V26` is an `ALTER`, and `NOT NULL DEFAULT false` on a populated `project_settings` is the one shape a fresh container cannot tell apart from a `CREATE`. |

---

## §83 — the cross-project "My Work" board (frontend half)

Five rows, **all frontend** — the backend half of §83 queues its own rows above/below with the
backend slice. Every row here is about a rule that is **invisible on a project board**, because on
one the route's project and the card's project are always the same value and the mock's fixtures put
almost everything in one project. That equality is what hid the `linkFor` defect for two slices.

| | |
|---|---|
| **File** | `frontend/src/features/board/BoardPage.tsx`, `linkFor` |
| **Mutation** | take the board's project as a parameter again and use it instead of `item.projectId` (the pre-§83 signature) |
| **Scope** | `npx vitest run src/features/board` |
| **Must redden** | **exactly 1** — *links into the project the CARD belongs to, not the one the board opened on* (`MyWorkPage.test.tsx`). **Exactly one is the finding, not a shortfall**: all four of §81's link tests pass against the mutation, because their fixture card's `projectId` is the same `'p1'` the board was handed. That is precisely why the defect survived §81 — it is unobservable on every board that existed then. |

| | |
|---|---|
| **File** | `frontend/src/api/queries/workItems.ts`, `useMyWork` |
| **Mutation** | send `qs({ mine: 'true', projectId })` for some project — or drop `mine` entirely |
| **Scope** | `npx vitest run src/features/board/MyWorkPage.test.tsx` |
| **Must redden** | **exactly 2** — *asks for the personal board and names no project* and *never sends an empty projectId*. Both arms matter and they fail differently on a real server: a project sent renders **one** project's board under a title promising all of them (a silent wrong answer), while `mine` dropped is a **400** rendered as an error page on the screen §6l expects somebody to keep open all day. |

| | |
|---|---|
| **File** | `frontend/src/mocks/db/workItems.ts`, `ownsTheRepository` |
| **Mutation** | `const ownsTheRepository = true` (the pre-§83 behaviour) |
| **Scope** | `npx vitest run src/mocks/db/workItems.test.ts` |
| **Must redden** | **exactly 2** — *draws none of them on a project that does not own the repository* and *is the union of the per-project boards, filtered*. The mutation **is** the code as it stood from Phase 1 to §83: `db.runs` and `db.cases` carry no `projectId`, so every project's board drew the same executions and the same flagged cases, stamped with whichever project was asked for. On MOB's board that was already a card whose key 404s; on the cross-project board it is the same card twice with the **same React key**. |

| | |
|---|---|
| **File** | `frontend/src/mocks/db/workItems.ts`, `personalBoard` |
| **Mutation** | drop the `.filter((item) => item.assignee?.id === userId)` |
| **Scope** | `npx vitest run src/mocks/db/workItems.test.ts` |
| **Must redden** | **exactly 2** — *keeps only the caller's own cards* and *is the union of the per-project boards, filtered*. The filter is the entire difference between §6l's personal board and "everything happening in the company", which is the request the server answers **400** to rather than serving. A mock that served it would make the 400 look like a bug in the server. |

| | |
|---|---|
| **File** | `frontend/src/mocks/handlers/workItems.ts`, `GET /work-items` |
| **Mutation** | restore the `?? projects[0].id` fallback for an unknown `projectId` |
| **Scope** | `npx vitest run src/features/board` |
| **Must show** | **nothing reddens — which is the row's point.** No test covers it, because no component asks for a project it cannot see; it is caught only by driving a stale bookmark in the browser (queued as a browser row). Recorded so the absence is deliberate: the fallback made the mock render *another project's* board under the id in the URL, every key on it linking back into a project that does not contain them, where the server answers 404. |
