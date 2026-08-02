# Deferred backend mutation checks — the queue

**Policy set 2026-08-02 (§65).** Mutation-verifying each slice inline costs a full
`./gradlew build` per mutation (~3 min each, often two or three per slice) and it was being paid
*during* feature work, where it is slowest and least useful. It is now **deferred and batched**:
a slice records the mutation it would have run, and this file is executed as one pass later.

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
