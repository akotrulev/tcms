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
