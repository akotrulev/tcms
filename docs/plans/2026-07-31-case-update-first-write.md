# Backend M1, slice 7 — `PATCH /projects/{projectId}/cases/{caseId}`, the first write

**Date:** 2026-07-31 · **Mode:** B — extend · **Skill:** `be-java` (+ `database-code`)
**Supersedes** handoff §27's next-step ordering — see §0.

---

## 0. Why this slice, and not §27's items 1 and 2

§27 named `/cases/{caseId}/versions` and `/history` as next. Orientation found all three of its
premises wrong:

| Claim in §27 | What the code says |
|---|---|
| `/versions` is a read the SPA is waiting for | **`useCaseVersions` has no consumer.** Dead hook. |
| `/versions` is "the first thing that needs `test_case_version`" | True, and that is the problem: **nothing writes `test_case_version`** — no entity, no seed, no writer. The endpoint would answer `[]` for every case in the product while §27's detail endpoint already serves `currentVersionNo` from `test_case.current_version_no` (`NOT NULL DEFAULT 1`). One case would read "v7" on its detail page and "no versions" on its version list. |
| `/history` is a `repository` read | It is tagged `[repository, reporting]` and needs `test_execution` + `test_run` + `case_status` + `project_configuration` + defect links + evidence counts — **the whole `execution` module, which does not exist.** |
| `/shared-steps` (item 2) has a consumer | **`useSharedSteps` has no consumer either.** |

So the write comes first. It is §27's own item 3, and it is what makes `/versions` return data
worth serving. `/versions` becomes a small follow-on slice once this lands.

---

## 1. Scope

`PATCH /projects/{projectId}/cases/{caseId}` — the mutable surface the SPA's editor actually
submits, the version-writing path behind it, optimistic concurrency, and the write gate.

**In:** the editor's payload (title, suiteId, objective, description, expectedOutcome, priority,
severity, stepFormat, gherkinSource, automationStatus, automationKey, notAutomatableReason,
estimatedDurationSec, steps[], changeComment) · `test_case_version` writing with
skip-when-unchanged · `current_version_no` · `If-Match`/409 · `case:edit` gate with 403 ≠ 404 ·
field-keyed 400s · one `audit_event` row.

**Out, and explicitly refused rather than silently discarded (400):** `ownerId`,
`classificationValueIds`, `tags`, `automationTags`, `preconditions`, `postconditions`. All six are
on `TestCaseInput`, none is sent by any client, and each needs its own write path (cardinality
validation for classification, the inline-vs-library shape for conditions). **Absent means
unchanged — correct PATCH semantics; present means 400**, because accepting a field and dropping it
is the "shows success and discards the value" defect the mock's own handler comment names.

**Out entirely:** POST (create), DELETE (soft delete), the bulk endpoint, `/versions`, `/history`.

---

## 2. Three contract divergences this slice must resolve

1. **`If-Match` vs body `lockVersion`.** The contract specifies an `If-Match` header; the SPA and
   the mock both put `lockVersion` in the request body. Two mechanisms for one precondition is the
   smell. **Resolution: honour the contract.** A PATCH body should carry desired state, not a
   precondition about the state it read. The SPA and the mock are adapted in the same commit.
2. **No 403 exists in the contract.** Every response today is 200/400/401/404/409. A write needs
   403: a caller who can *read* the case but lacks `case:edit` already knows it exists, so a 404
   would be a lie that also breaks the editor's error copy. The 404-not-403 rule (§6t, §3 lesson
   13) is about concealing **existence** from someone who cannot see it — it does not apply once
   the caller has passed the read gate. **Resolution: add a `Forbidden` response** and attach it to
   this PATCH.
3. **`case:edit` is `OWN`-qualified in one place and not in two others.** The permission table says
   plain ✓ for Test Engineer; §A5 lists `case:edit` among the `OWN`-qualified; §A3's own stated
   principle ("a Test Engineer is never blocked from *producing*, only from unmaking **someone
   else's** work") names only `case:delete` and `evidence:delete`. Two of three readings agree, and
   the third contradicts the principle it sits under — a TCMS where a Test Engineer cannot fix a
   colleague's case is not a collaborative repository. **Resolution: `case:edit` is unqualified.**
   §A5 is corrected, with the superseded reading struck through per the working agreement. The
   `OWN` qualifier stays deferred and still owed to `case:delete`.

---

## 3. Steps

### 3a. Contract first
- `openapi.yaml`: add `responses.Forbidden`; attach `403` to the PATCH. Regenerate
  `frontend/src/api/schema.d.ts`.
- Correct §A5 of the roles spec (divergence 3), struck-through.

### 3b. The write gate
- `ProjectAccess` gains `boolean canDo(UUID userId, UUID projectId, String permission)`.
  `canView` becomes its `project:read` specialization, so there stays **one** implementation of
  "may they?" delegating to `PermissionEvaluator`.
- `ProjectService` gains `writeGranted(userId, projectId, permission, fn)` returning a tri-state
  (unreachable → 404 · reachable-but-ungranted → 403 · granted → the result), because a write has
  an outcome a two-state `Optional` cannot carry.

### 3c. Persistence
- `TestCaseVersion` entity + `TestCaseVersionRepository` — the table's first Java writer.
- `TestCase` gains mutators (its header already says this is what the write brings) plus
  `updated_by`, currently unmapped.
- No migration expected. **Two things to verify:** whether `uq_case_version (case_id, version_no)`
  satisfies `SchemaConventionTest`'s "`company_id` leads every composite index" rule — it does not
  lead it — and whether the version read needs an index beyond that unique constraint.

### 3d. The version-writing path — one class, `CaseVersions`
The §6j rules, in one place because a second answer to "was this a new version" is a second answer
to what history contains:
- full resolved snapshot into `snapshot` JSONB (V1's comment: not a delta),
- `content_hash` over the snapshot; **unchanged hash writes no version** and does not bump
  `current_version_no`,
- `lock_version` bumps regardless (it tracks writes, not authored versions — the entity's own
  header draws this distinction and the two must not be conflated),
- `changeComment` optional by design.

### 3e. Steps, and the property that makes reordering safe
`test_step.id` is stable and `step_result` FKs it (§3 lesson 4). So the update is a **reconcile,
not a delete-and-reinsert**: a step arriving with an `id` is updated in place, one without is
inserted, one absent from the payload is deleted. A wholesale replace would reassign every id and
orphan every past step result — the most expensive defect available in this slice, and it is the
easy implementation.

### 3f. Validation → field-keyed 400
The editor's four cross-field rules are re-enforced server-side (GHERKIN needs `gherkinSource`,
STRUCTURED needs ≥1 step, NOT_AUTOMATABLE needs a reason, AUTOMATED needs a key), plus the suite
must be live and in this project. `Problem.errors` keyed by field so the SPA maps them to inputs.

### 3g. Audit
One `audit_event` row in the same transaction. The table's comment says "emitted from domain
events" — that is about ingestion and jobs being audited identically, not about asynchrony, and an
audit row that can commit while the change rolls back (or vice versa) is worse than a synchronous
write. Recorded on the writer.

### 3h. Tests — `CaseUpdateEndpointTest`
Both gates, 403 ≠ 404, cross-company, the version skip-when-unchanged property, step reconcile
preserving ids, 409 on a stale `If-Match`, each 400, the audit row, the six refused fields.
**Mutation-verified**, per every prior slice.

### 3i. SPA + mock
`If-Match` header instead of body `lockVersion`; mock handler reads the header. Then the browser
pass in live mode: edit a case, see v→v+1, save twice with no change and see the version *not*
advance, and force a 409.

---

## 4. Gates
`./gradlew build` clean with `-Werror` · `npm run build` clean · `npm run lint` 0 warnings ·
driven live against `docker compose up` with two provisioned companies · mutation table recorded.

---

## 5. Findings to record in the handoff regardless of outcome

1. **No `created_by` column anywhere in the schema has an FK to `app_user`** — 23 columns, zero
   references. Handoff §3 lesson 10 claims this "already holds"; it does not. Fixing it is its own
   migration and needs an `ON DELETE` decision under "suspend, never delete".
2. **The `/history` response's inline object has no `required` array** — lesson §6.3 recurring.
   `CaseHistoryResponse` is hand-written in `queries.ts` to work around it.
3. `useCaseVersions` and `useSharedSteps` are dead hooks.
