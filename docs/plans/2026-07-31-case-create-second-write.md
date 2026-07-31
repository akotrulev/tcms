# Case create — the second write Implementation Plan

**Date:** 2026-07-31 · **Written by:** `ep-writing-plans` (be-java, Mode B — extend)

> **For agentic workers:** REQUIRED SUB-SKILL: use `ep-executing-plans` (inline) to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve `POST /api/v1/projects/{projectId}/cases` — allocating the company's `TC-n` display
key from `key_sequence`, writing `test_case_version` **v1** in the same transaction, honouring the
`Idempotency-Key` the SPA has always sent, and auditing the creation.

**Architecture:** Reuse everything slice 7 built. `ProjectService.writeGranted` is the gate (with
`case:create` instead of `case:edit`), `CaseEdit.validate` is the validator, `CaseSteps.reconcile` is
the step writer, `CaseVersions` decides and writes the version, `AuditEvent` records it. Three things
are genuinely new: a company-scoped **key allocator** in `tenancy`, a `TestCase.create` **factory**,
and **server-side idempotency** for the create.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · Hibernate 6 · Flyway · Postgres 17 + FORCE RLS ·
Testcontainers · MockMvc.

---

## Why this slice, and not the two §28 named first

§28's own lesson says to re-derive the next slice from the code. Doing so:

| §28's next list | What the code says |
|---|---|
| 1. `GET …/versions` | **`useCaseVersions` still has zero consumers** — `CaseDetailPage` does not call it. The same dead-hook trap §28 caught in §27, one slice later. Serving it adds an endpoint no screen reads. |
| 2. `POST …/cases` | **`useCreateCase` is wired to a real screen** — `CaseEditorPage mode="create"` on route `cases/new`, reached from two places on `CaseListPage`. It is the only remaining case write with a live consumer. |
| 3. The `execution` module | A module, not a slice. |

So: item 2. It also makes item 1 worth building afterwards for a second reason — a case created here
has a **v1**, so `/versions` stops being a list whose first entry is always missing.

---

## Decisions this slice has to make, with the reasoning

### D1 — The permission is `case:create`, and its Automation Service qualifier is NOT implementable here

`case:create` is held by Instance Admin, Company Admin, Test Lead, Test Engineer and — **"quarantine
only"** — Automation Service (roles spec §A5 table, plan §6b, ERD §3). Viewer does not hold it.

The quarantine restriction **cannot be implemented in this slice and must not be faked**: there is no
quarantine folder in the schema — `suite` has no marker column, `project_configuration` names no
default suite, and the concept arrives with CI ingestion (plan §6, M3). `V3`'s own comment already
records it as "a service-layer rule: a permission cannot carry a folder".

So the honest position, recorded rather than glossed: **a holder of Automation Service can currently
create a case in any suite of a granted project.** It is not reachable through a client today — API
tokens are M3 and the only authenticator is a password login, so the role can only be attached by
hand-written SQL — and it is written into the handoff and the service javadoc as owed to the
ingestion slice, alongside the `OWN` qualifier still owed to `case:delete`. Inventing a
`suite.is_quarantine` column to gate a caller that cannot yet exist would be a schema change made to
satisfy a test rather than a product.

### D2 — Key allocation belongs in `tenancy`, allocates a RANGE, and fails loudly

`V5`'s header prescribes the statement, and prescribes it because the alternatives are wrong:

```sql
UPDATE key_sequence SET next_value = next_value + :n
 WHERE company_id = :c AND entity_type = :t
RETURNING prefix, next_value - :n;
```

- **In `tenancy`**, not `repository`: five entity types share the table (`CASE SUITE PLAN RUN
  DOC_PAGE`), so a copy per module is five allocators that can disagree about the racing statement.
  `tenancy` is depended on by everything and depends on nothing.
- **A range, `n` at a time**, even though this caller passes 1. ERD §5 and plan §6t both say bulk
  import and CI quarantine auto-create **must** reserve N in one statement. The SQL is byte-identical
  for n=1 and n=500, so building the range API now costs nothing and means the bulk slice cannot
  reach for `MAX(key_seq)+1`.
- **The prefix comes from the row**, not from Java. `TC-` is data (`V5` seeds it) and display keys are
  immutable by contract; a constant in code would be a second definition of the same string.
- **Zero rows updated must throw.** `UPDATE … RETURNING` matching nothing is an *empty result*, not
  an error — the exact shape `V5` says "invites the wrong repair". A company provisioned before V5
  and never topped up has no row, and the fix is re-running `provision-company`, so the exception says
  that.

### D3 — v1 is written explicitly, NOT through `CaseVersions.decide`

`decide()` returns `currentVersionNo + 1`, and a fresh row's `current_version_no` is `NOT NULL
DEFAULT 1` — so a created case would be born at **v2** with no v1, which is the exact gap §28
documented for *pre-existing* cases and which has no excuse for a new one. `CaseVersions` gains
`initial(CaseEdit)` returning `Decision(1, true, hash)`; `write()` is reused unchanged, so the hash
stays computed in one place and a create's hash is comparable with the next edit's.

### D4 — The creator is the owner, and that is not "accepting a refused field"

`ownerId` stays on the refused six: a *caller-chosen* owner needs a membership check this slice does
not build. But a case with no owner at all is the awkward row the fixtures already carry (`§4`), and
authorship is what `case:delete`'s `OWN` qualifier will resolve against. So `owner_id`, `created_by`
and `created_at` are all set from the authenticated caller and the clock — server-assigned, never
request-supplied. `created_at`/`created_by` get mapped on `TestCase` for the first time, by the same
argument §28 used for `updated_by`: a row that says it was created without saying by whom is an audit
column that looks present and answers nothing.

### D5 — Idempotency is implemented, because the alternative is the lie §28 refused

The SPA has sent `Idempotency-Key` on this endpoint since Phase 1, with a comment explaining that one
key per mounted editor is what makes a retried Save not create a second case, and the **mock honours
it**. Nothing on the server reads it. Accepting a header and ignoring it is exactly the "accept and
silently drop" failure §28 rejected for the six unsupported fields, and it is worse here because the
mock *does* dedupe — so mock mode proves a property live mode does not have. Same class as the
`If-Match`-in-the-body divergence, one endpoint later.

**Stored on `test_case`, not in a general request table.** The key identifies the creation of one row,
so it lives on that row: no new table, no new RLS policy, no exception-list decision, and retention is
automatic. The cost is honest — a future create endpoint adds its own column — and generalising can
wait for the second caller.

Unique on **`(company_id, project_id, created_idempotency_key)`**, partial on
`created_idempotency_key IS NOT NULL`, and the lookup query matches that scope exactly. Company-wide
would make a key reused across projects a constraint violation (a 500), and a lookup narrower than its
constraint is the mistake `existsByAutomationKeyOtherThan`'s header already documents.

**Replay answers 200, a fresh create answers 201.** The mock already returns 200 on replay, so the two
modes agree, and the two statuses let a client tell "I made this" from "I already had".

### D6 — Four contract gaps to close in `openapi.yaml`

Read before coding, per the working agreement:

1. `POST …/cases` declares **only 201** — no 400, 403, 404. All three are reachable.
2. **No `Idempotency-Key` parameter anywhere in the spec**, while the client has sent it since Phase 1.
3. **No `Location` header** on a 201.
4. The path-level `parameters` block attaches every *search* query param (`page`, `size`, `q`, …) to
   the whole path, so they apply to `POST` too. A spec wart — recorded, not fixed here: moving them
   onto the `get` operation is a diff across the file's largest parameter block and touches the
   generated types for the list endpoint, which no part of this slice otherwise goes near.

---

## File structure

| Path | Responsibility |
|---|---|
**Create**
| `backend/src/main/resources/db/migration/V11__test_case_creation_idempotency.sql` | The column + the partial unique index, with the reasoning from D5. |
| `backend/src/main/java/com/tcms/tenancy/DisplayKeys.java` | The one company-scoped key allocator (D2). |
| `backend/src/main/java/com/tcms/repository/CaseCreateService.java` | The create orchestration; sealed `Outcome`. |
| `backend/src/test/java/com/tcms/tenancy/DisplayKeyAllocationTest.java` | Range allocation, no gaps, no duplicates, missing row throws. |
| `backend/src/test/java/com/tcms/repository/CaseCreateEndpointTest.java` | The endpoint, gate to audit row. |
**Modify**
| `backend/.../repository/TestCase.java` | `create(...)` factory; map `created_at`, `created_by`, `created_idempotency_key`. |
| `backend/.../repository/CaseVersions.java` | `initial(CaseEdit)` (D3). |
| `backend/.../repository/TestCaseRepository.java` | `existsByAutomationKey`, `findByProjectIdAndIdempotencyKey`. |
| `backend/.../repository/CaseController.java` | `@PostMapping`. |
| `backend/.../repository/ApiProblem.java` | `forbidden(String detail)` — the message differs per action. |
| `docs/specs/openapi.yaml` | D6 items 1–3. |
| `frontend/src/api/schema.d.ts` | Regenerated. |
| `frontend/src/mocks/handlers.ts` | Refuse the six fields on create; owner = creator. |
| `docs/HANDOFF.md` | §29. |

---

## Task 1 — The allocator

**Files:** Create `backend/src/main/java/com/tcms/tenancy/DisplayKeys.java`,
`backend/src/test/java/com/tcms/tenancy/DisplayKeyAllocationTest.java`

- [ ] **Step 1: Write the failing test** — a company seeded by `tcms_seed_company_defaults` allocates
  `TC-1` then `TC-2`; a range of 3 returns consecutive values and leaves `next_value` past them; an
  unseeded company throws `IllegalStateException`; two allocations never return the same value.

- [ ] **Step 2: Run it** — `./gradlew test --tests 'com.tcms.tenancy.DisplayKeyAllocationTest'`.
  Expected: FAIL, `DisplayKeys` does not exist.

- [ ] **Step 3: Implement.**

```java
@Component
public class DisplayKeys {

    public record Allocation(String prefix, long firstValue, int count) {
        public long seqAt(int index) { /* bounds-checked */ }
        public String keyAt(int index) { return prefix + seqAt(index); }
    }

    private final EntityManager entityManager;

    @Transactional
    public Allocation allocate(EntityType type, int count) {
        // UPDATE key_sequence SET next_value = next_value + :n
        //  WHERE company_id = :c AND entity_type = :t RETURNING prefix, next_value - :n
        // Empty result -> IllegalStateException naming provision-company as the repair.
    }

    public enum EntityType { CASE, SUITE, PLAN, RUN, DOC_PAGE }
}
```

`EntityType` is an enum rather than a `String`: the column has a `CHECK` constraint listing exactly
these five, and a typo'd string would match zero rows and read as "this company is unprovisioned".

- [ ] **Step 4: Run the test** — expect PASS.

- [ ] **Step 5: Commit** `backend/.../tenancy/DisplayKeys.java` + its test.

## Task 2 — The idempotency column

**Files:** Create `V11__test_case_creation_idempotency.sql`; modify `TestCase.java`

- [ ] **Step 1** Write the migration: `ALTER TABLE test_case ADD COLUMN created_idempotency_key
  TEXT;` plus the partial unique index on `(company_id, project_id, created_idempotency_key)`, with a
  `COMMENT` carrying D5's reasoning.
- [ ] **Step 2** Map it on `TestCase` as `updatable = false`, alongside `createdAt`/`createdBy`.
- [ ] **Step 3** `./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest'` — expect PASS
  (the index leads with `company_id`; `ddl-auto: validate` proves the mapping).
- [ ] **Step 4** Commit.

## Task 3 — The factory and `initial()`

**Files:** Modify `TestCase.java`, `CaseVersions.java`, `TestCaseRepository.java`

- [ ] **Step 1** `static TestCase create(UUID projectId, String key, long keySeq, CaseEdit edit,
  UUID creatorId, String idempotencyKey, Instant now)` — `companyId` from `CompanyContext.require()`
  (never an argument, ERD §0.1 point 2), `lifecycleStatus = DRAFT`, `currentVersionNo = 1`,
  `ownerId = creatorId`, then delegate the content fields to `applyEdit`.
- [ ] **Step 2** `CaseVersions.initial(CaseEdit edit)` → `new Decision(1, true, hash(edit))`.
- [ ] **Step 3** `TestCaseRepository.existsByAutomationKey(String)` (no exclusion — there is no case
  to exclude) and `findByProjectIdAndIdempotencyKey(UUID, String)`, both `@Query`.
- [ ] **Step 4** `./gradlew build` — expect clean.
- [ ] **Step 5** Commit.

## Task 4 — The service and the endpoint

**Files:** Create `CaseCreateService.java`; modify `CaseController.java`, `ApiProblem.java`

- [ ] **Step 1: Write the failing endpoint test** (`CaseCreateEndpointTest`), fixture shaped like
  `CaseUpdateEndpointTest`'s — own company/user ids, `tcms_seed_company_defaults` called so
  `key_sequence` exists, `resetMutableRows()` between tests. Assertions listed in Task 5.
- [ ] **Step 2: Run it** — expect FAIL (405, no POST mapping).
- [ ] **Step 3: Implement `CaseCreateService`.**

```java
public sealed interface Outcome {
    record NotFound() implements Outcome {}
    record Forbidden() implements Outcome {}
    record Invalid(Map<String, List<String>> errors) implements Outcome {}
    record Created(TestCaseDetailView view) implements Outcome {}
    record Replayed(TestCaseDetailView view) implements Outcome {}
}
```

Order inside the gated block, and each position is load-bearing:
1. **Idempotency lookup first** — before validation, so a retry of a create that succeeded returns
   the case even if the payload has since drifted.
2. Validate through `CaseEdit.validate(request, liveSuiteIds, Set.of(), existsByAutomationKey)`. The
   empty step-id set means a submitted step `id` is refused — on a create it can only name another
   case's step.
3. Allocate the key.
4. `TestCase.create` → `saveAndFlush`.
5. `CaseSteps.reconcile`.
6. `CaseVersions.initial` → `write` with the rendered view.
7. `audit_event`, action `case.created`.

- [ ] **Step 4** `@PostMapping` on `CaseController`, reading `@RequestHeader(name = "Idempotency-Key",
  required = false)`; `Created` → 201 + `Location`, `Replayed` → 200.
- [ ] **Step 5** Run the test — expect PASS. Then `./gradlew build`.
- [ ] **Step 6** Commit.

## Task 5 — The assertions that have to exist

Written out because they are the slice's real specification:

- [ ] Lead / Engineer create → **201**, body has a server-allocated `TC-n`, `currentVersionNo` 1,
  `lifecycleStatus` `DRAFT`, owner = creator, `Location` names the new id.
- [ ] **Viewer → 403** after a **200** on the list, body names the action and never `case:create`.
- [ ] Persona 3 (member, no grant) → **404, 0 bytes**. Another client's project → **404**.
  Unauthenticated → **401**.
- [ ] Keys are **sequential per company and shared across projects** — a case in project A then one
  in project B get `TC-1` and `TC-2`; the other company independently starts at `TC-1`.
- [ ] `key_seq` is stored and equals the numeric part (the ordering column, §3 lesson 6).
- [ ] **v1 exists**, hash matches the edit, `pinned` false, author recorded; a subsequent PATCH of the
  same case writes **v2** — the created case has **no gap**.
- [ ] Audit row `case.created`, one target, actor = creator, project set.
- [ ] Same `Idempotency-Key` twice → **200 the second time, one case, one version, one audit row**.
  A different key with the same body → two cases.
- [ ] Blank title → 400 keyed `title`; a suite from another project → 400 keyed `suiteId`; any of the
  six refused fields → 400 keyed on that field; a submitted step `id` → 400.
- [ ] A duplicate `automationKey` → 400 keyed `automationKey`, not a 500.
- [ ] Steps are stored with positions from payload order and fresh ids.

## Task 6 — Contract, mock, and the browser pass

- [ ] **Step 1** `openapi.yaml`: add `Idempotency-Key` to `components.parameters`, add `200`/`400`/
  `403`/`404` and the `Location` header to the POST, then `cd frontend && npm run gen:api`.
- [ ] **Step 2** `frontend/src/mocks/handlers.ts`: refuse the six fields on create with the same
  field-keyed 400 and the same message, and default the owner to the signed-in user.
- [ ] **Step 3** `npm run build` + `npm run lint` — expect clean, 0 warnings.
- [ ] **Step 4** **Run it.** `docker compose up`, two provisioned companies, live mode: create a case
  from `cases/new`, confirm the allocated key in the grid and the detail page, confirm v1, PATCH it to
  v2, and confirm a Viewer's create is refused. Record what the browser did *not* verify.
- [ ] **Step 5** Mutation-verify: drop the permission (Viewer may create), check permission before
  visibility, allocate with `MAX(key_seq)+1`, skip the v1 write, number the first version 2, ignore
  the idempotency key, look the key up without the project predicate, take `position` from the
  request, set `ownerId` from the body.
- [ ] **Step 6** `docs/HANDOFF.md` §29, then commit.

---

## Self-review

**Spec coverage:** D1–D6 each map to a task (D1→4, D2→1, D3→3, D4→3, D5→2+4, D6→6). The five
assertion families in `CaseUpdateEndpointTest`'s header — gate, steps, versioning, staleness, refused
fields — all recur in Task 5 except staleness, which has no meaning on a create (there is no prior
state to be stale against) and is deliberately absent.

**Placeholders:** none — every step names its files, its command, and its expected result.

**Type consistency:** `Allocation`/`EntityType` (Task 1) are used unchanged in Task 4 step 3;
`Outcome.Created`/`Replayed` map to the two statuses in step 4; `initial(CaseEdit)` returns the same
`Decision` record `write()` already takes.

**Deliberately out of scope:** `DELETE` (needs the `OWN` qualifier), the bulk endpoint, the quarantine
restriction (D1), `GET …/versions` (no consumer — but now worth building), the `created_by` FK gap
(§28 finding 1), and the path-level parameter wart (D6.4).
