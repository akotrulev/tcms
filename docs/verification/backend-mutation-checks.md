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
