# Unplanned Gaps — Slice Plan

**Date:** 2026-08-18 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement a slice
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Each slice below is one Claude
> session.** Do not start a slice until the one before it has landed, unless the dependency note
> says otherwise.

**Goal:** Close the nine gaps recorded in `docs/features.md` § *Gaps Not Currently Planned*, in an
order where each slice ships working software on its own.

**Architecture:** Eight of the nine are additive — a new read surface, a new notifier, a new column,
a new endpoint — and none changes the tenancy, permission or contract machinery. They are sequenced
so the cheap high-value work lands first and the two that touch every file in the SPA land last,
when the surface they must sweep has stopped moving.

**Tech Stack:** Spring Boot 3.5.6 / Java 21 / Flyway / Postgres 17 · React 19 / TypeScript / MUI v7
/ TanStack Query · `openapi.yaml` as the contract, generated into both `schema.d.ts` copies.

---

## Scope Check — read this before starting

The `ep-writing-plans` skill requires a plan to cover **one** subsystem. This document deliberately
does not: it covers nine, because the request was for the *slicing and the order*. It is therefore
a **master plan**, and it is detailed to two different depths on purpose:

- **Slice 1 is fully specified** — files, contract fragment, code and tests — because it is next.
- **Slices 2–9 are scoped, not stepped**: goal, exact files, acceptance gate, dependencies and the
  traps already known. Writing bite-sized TDD steps for slice 8 today would be invention, not
  planning — the surface it sweeps will have moved by then.

**When you pick up slice N (N ≥ 2), your first act is to run `ep-writing-plans` on that slice alone**
and save the result as `docs/plans/2026-08-18-gap-N-<slug>.md`. This master plan is the input to
that, not a substitute for it.

## Order, and why it is this order

| # | Slice | Session size | Why here |
|---|---|---|---|
| 1 | Audit log reader | Full | Highest value per unit of work — the writing half already exists. Nothing depends on it, so it de-risks nothing else, but it is the cheapest thing that turns dead data into a feature. |
| 2 | Review-workflow notifications | Full | Second-highest value, and it needs no schema change — the notifier seam already ships two events. |
| 3 | Execution duration | Half | One column, one contract field, one form control. Must precede 4 so bulk assignment and duration land on one migration wave rather than two. |
| 4 | Bulk execution assignment | Full | Mirrors the existing `/cases/bulk` shape. After 3 so the execution write path is touched once. |
| 5 | Scheduled runs | Full | Needs 4's bulk-assignment path to be useful (a scheduled run nobody is assigned to is a dead run). |
| 6 | Cross-project case search | Full | Independent, but deliberately after the write-side work: it adds a company-scoped read path and is the one slice that can expose a tenancy defect, so it wants a quiet tree. |
| 7 | Company data export | Full | Reads every table. Wants to run last among the backend slices so it exports the columns 3 and 5 added. |
| 8 | Keyboard-driven execution | Full | Frontend only. After 3 and 4, whose controls it must bind. |
| 9 | Internationalisation groundwork | Full | Touches every user-facing string in the SPA. Must be last — any earlier and slices 1–8 add untranslated strings behind it. |

**Slices 1, 2, 6 and 7 are backend-led. Slices 8 and 9 are frontend-only.** Slices 3, 4 and 5 cross
both.

**Flyway numbering.** The head is `V67`. Slices needing a migration take, in order:
`V68` (slice 3, execution duration), `V69` (slice 5, run schedule). No other slice adds DDL.
`out-of-order` is unset, so these must land in this numeric order — if a slice is skipped, do
**not** renumber; leave the gap.

---

# Slice 1 — The audit log reader

**One session.** Backend + frontend + contract.

**Goal:** Make the audit trail readable. Two endpoints, two screens, no new writes and no schema
change.

**Why first:** `AuditEvent.java:220` records that `audit:view` and `project:audit` have *zero
consumers*. Every act in the product already writes a row; the permissions are seeded; the SPA
already carries the labels "view the project audit log" and "view the company audit log" in
`frontend/src/lib/permissions.ts:164,177`. Everything except the reader exists.

**Scope boundary — read this before widening the slice:**
- **In:** a project-scoped log (`project:audit`), a company-scoped log (`audit:view`), both
  read-only, both paged, both filterable by actor, action and date range.
- **Out:** the *instance* log (`instance:audit` over `InstanceAuditEvent`) — it is a different table
  with a different scope and no company context, and it belongs with the M6 operator console.
  Do not add it here. Say so in the handoff.
- **Out:** export. `report:export` is a different permission and a report type is a different slice.

## What already exists — do not rebuild it

`backend/src/main/java/com/tcms/tenancy/AuditEvent.java` is a JPA entity on `audit_event`, carrying
the company filter. Its columns, verified:

| Column | Java field | Note |
|---|---|---|
| `id` | `UUID id` | |
| `company_id` | `UUID companyId` | `@Filter` applies |
| `project_id` | `UUID projectId` | **nullable** — a company-scope act has none |
| `actor_user_id` | `UUID actorUserId` | nullable |
| `actor_token_id` | `UUID actorTokenId` | nullable — the act came from a token |
| `action` | `String action` | the verb |
| `target_type` | `String targetType` | |
| `target_ids` | `UUID[] targetIds` | array, `@JdbcTypeCode(SqlTypes.ARRAY)` |
| `changes` | `String changes` | JSON, nullable |
| `bulk_operation_id` | `UUID bulkOperationId` | nullable — ties one bulk act's rows together |
| `occurred_at` | `Instant occurredAt` | |

**Both actor columns are nullable and so is `project_id`.** A view that assumes an actor is a person
will NPE on a token-driven act, and a project filter that assumes `project_id` is present will hide
every company-scope row. Both are covered by tests below.

## Files

- Create: `backend/src/main/java/com/tcms/tenancy/AuditEventRepository.java`
- Create: `backend/src/main/java/com/tcms/tenancy/AuditLogService.java`
- Create: `backend/src/main/java/com/tcms/tenancy/AuditLogController.java`
- Create: `backend/src/main/java/com/tcms/tenancy/AuditEventView.java`
- Create: `backend/src/main/java/com/tcms/tenancy/AuditLogQuery.java`
- Create: `backend/src/test/java/com/tcms/tenancy/AuditLogReadTest.java`
- Modify: `docs/specs/openapi.yaml` — two paths, two schemas
- Create: `frontend/src/features/audit/AuditLogPage.tsx`
- Create: `frontend/src/features/audit/AuditLogPage.test.tsx`
- Create: `frontend/src/features/audit/auditLabels.ts`
- Modify: `frontend/src/api/queries/` — a read hook per endpoint
- Modify: `frontend/src/app/router.tsx` — two routes
- Modify: `frontend/src/api/schema.d.ts` **and** `packages/client/src/schema.d.ts` (both, generated)
- Modify: `docs/agent-context/be-java.md` and `fe-react.md` — seam entry, **in this slice's commit**
- Create: `docs/handoff/242-the-audit-log-reader.md` + one index row in `docs/HANDOFF.md`

## Phase A — the contract

- [ ] **Step 1: Add the two paths to `docs/specs/openapi.yaml`**

Add under the project-scoped paths, beside `/projects/{projectId}/cases/bulk`:

```yaml
  /projects/{projectId}/audit-events:
    get:
      operationId: listProjectAuditEvents
      summary: This project's audit trail, newest first
      description: |
        Costs `project:audit`. Rows are the acts recorded against this project.
        A company-scope act has no `projectId` and is NOT returned here — ask
        `/audit-events` for those.

        `actorUserId` and `actorTokenId` are both nullable and exactly one is set:
        an act arrives either from a signed-in person or from a token. A row with
        neither is a defect, not a shape a client must render.
      tags: [Audit]
      parameters:
        - $ref: '#/components/parameters/ProjectId'
        - name: actorUserId
          in: query
          required: false
          schema: { type: string, format: uuid }
        - name: action
          in: query
          required: false
          schema: { type: string }
        - name: occurredAfter
          in: query
          required: false
          schema: { type: string, format: date-time }
        - name: occurredBefore
          in: query
          required: false
          schema: { type: string, format: date-time }
        - name: page
          in: query
          required: false
          schema: { type: integer, minimum: 0, default: 0 }
        - name: size
          in: query
          required: false
          schema: { type: integer, minimum: 1, maximum: 200, default: 50 }
      responses:
        '200':
          description: The page of audit events.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/AuditEventPage' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /audit-events:
    get:
      operationId: listCompanyAuditEvents
      summary: The company's audit trail, newest first
      description: |
        Costs `audit:view` (COMPANY scope). Returns acts across the whole company,
        project-scoped ones included, so this is a superset of every project log
        the caller could read.
      tags: [Audit]
      parameters:
        - name: projectId
          in: query
          required: false
          schema: { type: string, format: uuid }
        - name: actorUserId
          in: query
          required: false
          schema: { type: string, format: uuid }
        - name: action
          in: query
          required: false
          schema: { type: string }
        - name: occurredAfter
          in: query
          required: false
          schema: { type: string, format: date-time }
        - name: occurredBefore
          in: query
          required: false
          schema: { type: string, format: date-time }
        - name: page
          in: query
          required: false
          schema: { type: integer, minimum: 0, default: 0 }
        - name: size
          in: query
          required: false
          schema: { type: integer, minimum: 1, maximum: 200, default: 50 }
      responses:
        '200':
          description: The page of audit events.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/AuditEventPage' }
        '403': { $ref: '#/components/responses/Forbidden' }
```

- [ ] **Step 2: Add the two schemas**

Under `components.schemas`. **Both `required` arrays are mandatory** — the working agreement says a
schema without one generates every property optional and no consumer can use it:

```yaml
    AuditEventPage:
      type: object
      required: [items, page, size, totalElements, totalPages]
      properties:
        items:
          type: array
          items: { $ref: '#/components/schemas/AuditEvent' }
        page: { type: integer }
        size: { type: integer }
        totalElements: { type: integer, format: int64 }
        totalPages: { type: integer }

    AuditEvent:
      type: object
      required:
        [id, projectId, actorUserId, actorUserName, actorTokenId, actorTokenName,
         action, targetType, targetIds, changes, bulkOperationId, occurredAt]
      properties:
        id: { type: string, format: uuid }
        projectId:
          type: string
          format: uuid
          nullable: true
          description: Null for a company-scope act.
        actorUserId: { type: string, format: uuid, nullable: true }
        actorUserName:
          type: string
          nullable: true
          description: |
            Resolved display name. Null when the actor is a token, and ALSO null
            when the person has since been removed — a log must survive its actors.
        actorTokenId: { type: string, format: uuid, nullable: true }
        actorTokenName: { type: string, nullable: true }
        action: { type: string }
        targetType: { type: string }
        targetIds:
          type: array
          items: { type: string, format: uuid }
        changes:
          type: string
          nullable: true
          description: JSON document of what changed, as recorded. Opaque to the client.
        bulkOperationId:
          type: string
          format: uuid
          nullable: true
          description: Set when this row is one of many from a single bulk act.
        occurredAt: { type: string, format: date-time }
```

- [ ] **Step 3: Regenerate both schema copies and confirm they are identical**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
diff ../../frontend/src/api/schema.d.ts src/schema.d.ts && echo IDENTICAL
```

Expected: `IDENTICAL`. If it differs, `schemaFreshness.test.ts` will fail — fix it now, not later.

- [ ] **Step 4: Commit the contract**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.d.ts
git commit -m "contract: audit log read endpoints"
```

## Phase B — the backend read path (TDD)

- [ ] **Step 5: Write the failing test**

Create `backend/src/test/java/com/tcms/tenancy/AuditLogReadTest.java`. Extend
`IntegrationTestBase` — a real Postgres via Testcontainers, as every backend test here does.

```java
package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The audit log's first reader.
 *
 * <p>Three of these four exist because the table's nullable columns are the ones a naive reader
 * gets wrong: a token-driven act has no person, a company-scope act has no project, and a removed
 * person must not take their history with them.
 */
class AuditLogReadTest extends IntegrationTestBase {

    @Test
    void aProjectLogReturnsThatProjectsActsNewestFirst() {
        // Arrange: three acts on this project, one on another, written through the
        // existing audit writer — never by INSERT, or the test proves nothing about
        // what the product records.
        // Assert: three rows, ordered occurredAt DESC.
    }

    @Test
    void aCompanyScopeActIsAbsentFromEveryProjectLog() {
        // A row with project_id NULL must not appear in a project-scoped read.
        // The defect this catches is a filter written as `projectId = :id OR projectId IS NULL`.
    }

    @Test
    void aTokenDrivenActRendersWithNoPerson() {
        // actorUserId null, actorTokenId set. actorUserName must be null, NOT "Unknown",
        // and the read must not throw.
    }

    @Test
    void anActSurvivesTheRemovalOfItsActor() {
        // Deactivate the person, then read. The row is still returned; actorUserName
        // may be null. A log that loses rows when someone leaves is not a log.
    }

    @Test
    void aCallerWithoutProjectAuditIsRefused() {
        // Test Engineer holds no project:audit — expect 403, not an empty page.
        // An empty page would read as "nothing happened", which is a lie.
    }
}
```

- [ ] **Step 6: Run it and watch it fail**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tenancy.AuditLogReadTest'
```

Expected: FAIL — `AuditLogService` does not exist. **Tests run on `shanks`, not locally** — standing
directive. Do not run `./gradlew` on the Mac.

- [ ] **Step 7: Write `AuditEventRepository`**

Mirror `NotificationDeliveryRepository` exactly, including the annotation and the reason for it.

```java
package com.tcms.tenancy;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads over {@code audit_event}.
 *
 * <p>{@code @Transactional(readOnly = true)} is not decoration: isolation is established in
 * {@code doBegin}, so a derived or {@code @Query} method declared on this interface runs outside
 * any transaction without it, {@code app.current_company} is never set, and every read returns
 * ZERO ROWS while {@code CompanyContext} holds the right company. {@code RepositoryTransactionTest}
 * derives its list from the live context and will fail the build if this is dropped.
 */
@Transactional(readOnly = true)
interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * One project's acts, newest first.
     *
     * <p>{@code e.projectId = :projectId} and nothing else — a company-scope act carries a NULL
     * here and must NOT be folded in. Widening this to {@code OR e.projectId IS NULL} is the
     * defect {@code aCompanyScopeActIsAbsentFromEveryProjectLog} exists to catch.
     */
    @Query("""
            SELECT e FROM AuditEvent e
             WHERE e.projectId = :projectId
               AND (:actorUserId IS NULL OR e.actorUserId = :actorUserId)
               AND (:action IS NULL OR e.action = :action)
               AND (CAST(:after AS timestamp) IS NULL OR e.occurredAt >= :after)
               AND (CAST(:before AS timestamp) IS NULL OR e.occurredAt <= :before)
             ORDER BY e.occurredAt DESC, e.id DESC
            """)
    Page<AuditEvent> pageForProject(
            @Param("projectId") UUID projectId,
            @Param("actorUserId") UUID actorUserId,
            @Param("action") String action,
            @Param("after") Instant after,
            @Param("before") Instant before,
            Pageable pageable);

    /**
     * The company's acts, newest first — project-scoped rows included.
     *
     * <p>No {@code company_id} predicate: the {@code @Filter} on {@link AuditEvent} and the RLS
     * policy behind it are what scope this. Writing one by hand here would read as though the
     * filter were optional.
     */
    @Query("""
            SELECT e FROM AuditEvent e
             WHERE (:projectId IS NULL OR e.projectId = :projectId)
               AND (:actorUserId IS NULL OR e.actorUserId = :actorUserId)
               AND (:action IS NULL OR e.action = :action)
               AND (CAST(:after AS timestamp) IS NULL OR e.occurredAt >= :after)
               AND (CAST(:before AS timestamp) IS NULL OR e.occurredAt <= :before)
             ORDER BY e.occurredAt DESC, e.id DESC
            """)
    Page<AuditEvent> pageForCompany(
            @Param("projectId") UUID projectId,
            @Param("actorUserId") UUID actorUserId,
            @Param("action") String action,
            @Param("after") Instant after,
            @Param("before") Instant before,
            Pageable pageable);
}
```

**`ORDER BY occurredAt DESC, id DESC`** — the secondary key is not optional. Two acts in one
transaction share a timestamp to the microsecond, and an unstable sort makes page 2 repeat or skip
rows.

- [ ] **Step 8: Write `AuditLogQuery` and `AuditEventView`**

`AuditLogQuery` is the filter carrier — a record of the five nullable filters plus page and size,
so neither the service nor the controller grows a seven-argument signature.

`AuditEventView` is the wire shape. It must match the contract's `AuditEvent` schema property for
property, including both nullable actor names.

- [ ] **Step 9: Write `AuditLogService`**

Two methods, `forProject` and `forCompany`. Each gates first, reads second. Gate on
`project:audit` (PROJECT scope) and `audit:view` (COMPANY scope) respectively — check the
permission, **never a role name**. Resolve actor display names in one batched lookup keyed by the
distinct `actorUserId` values on the page, not per row.

- [ ] **Step 10: Write `AuditLogController`**

Mirror `NotificationChannelController`'s shape: `@AuthenticationPrincipal Jwt jwt`, `callerOf(jwt)`,
a `read(...)` wrapper for the outcome. Two `@GetMapping`s.

- [ ] **Step 11: Run the tests until green**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tenancy.AuditLogReadTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 12: Run the full backend gate**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected: BUILD SUCCESSFUL. `RepositoryTransactionTest` will fail here if step 7's annotation was
dropped, and `ArchitectureRulesTest` will fail if the new classes reach outside `tenancy`.

- [ ] **Step 13: Commit the backend**

```bash
git add backend/src/main/java/com/tcms/tenancy/ backend/src/test/java/com/tcms/tenancy/AuditLogReadTest.java
git commit -m "feat: audit log read path"
```

## Phase C — the screens

- [ ] **Step 14: Write the failing frontend test**

Create `frontend/src/features/audit/AuditLogPage.test.tsx`. Build fixtures with
`src/test/fixtures.ts` — the harness compares every mocked body against `openapi.yaml` and fails a
test that serves a shape the server could not send.

Cover: the table renders rows newest-first; a token-driven row renders without a person; the filter
controls write to the URL; an empty result reads as "no activity" and not as a failure.

**Do not build your own `QueryClient`** — use `createTestQueryClient`. Only that helper's caches are
visible to the render- and query-failure checks, and `renderFailures.test.tsx` sweeps every test
source to keep the opt-out list empty.

- [ ] **Step 15: Run it and watch it fail**

```bash
~/bin/tcms-remote frontend npm run test:run -- AuditLogPage
```

Expected: FAIL — the module does not exist.

- [ ] **Step 16: Build `AuditLogPage.tsx` and `auditLabels.ts`**

`auditLabels.ts` maps the `action` and `targetType` strings to human copy. Put it in the shared
layer per the working agreement — **new display logic goes in `lib/`-shaped modules, not the page**
— and type the map so an unlabelled action is a compile error rather than a raw verb leaking to
screen.

Use `DataRegion` for the loading/loaded/failed/empty/stale matrix and `Identifier` for the ids.
Filter state goes in the URL via `useSearchParams`, not component state.

- [ ] **Step 17: Add the two routes to `router.tsx`**

`/projects/:projectId/audit` and `/admin/audit`. Lazy-load both — the page pulls DataGrid.

- [ ] **Step 18: Add the read hooks**

One hook per endpoint in `frontend/src/api/queries/`, with the `keys` factory entry, the `enabled`
gate and a timeout budget. Follow the neighbouring hooks exactly.

- [ ] **Step 19: Gate the navigation entries on the permissions**

`useCan('project:audit')` and `useCan('audit:view')`. **Disable, do not hide** — the product's
standing rule.

- [ ] **Step 20: Run the frontend gates**

```bash
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
```

Expected: all green, lint at 0 warnings.

- [ ] **Step 21: Commit the frontend**

```bash
git add frontend/src/features/audit/ frontend/src/api/queries/ frontend/src/app/router.tsx
git commit -m "feat: audit log screens"
```

## Phase D — close the slice

- [ ] **Step 22: Update the orientation caches, in this slice's commit**

Add a seam entry to `docs/agent-context/be-java.md` and `docs/agent-context/fe-react.md`.
**Delete the "zero consumers" sentence at `AuditEvent.java:220`** — it will be false, and a stale
claim in a comment is exactly what `citedFiles`/`tierClaims` exist to prevent elsewhere.

- [ ] **Step 23: Add the verification queue rows**

Browser passes are **deferred and batched** (§65) — write the rows, run nothing. Append to
`docs/verification/frontend-browser-checks.md` in the lane whose state the check needs, with a
stable id, and to `docs/verification/backend-mutation-checks.md`.

**This slice is "built and unverified" until that queue runs. Describe it that way in the handoff.**

- [ ] **Step 24: Write the handoff section**

Create `docs/handoff/242-the-audit-log-reader.md` and add **one index row** to `docs/HANDOFF.md`.
Never append prose to `HANDOFF.md` itself.

Record: that the instance log was deliberately left out and why; that the actor columns are nullable
in two independent ways; and the paging sort key.

- [ ] **Step 25: Final gate and push**

```bash
~/bin/tcms-remote backend ./gradlew build
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build
git add docs/ && git commit -m "docs: audit log reader handoff and seams"
git push
```

---

# Slice 2 — Review-workflow notifications

**One session.** Backend-led, small frontend.

**Goal:** Fire notifications for the review workflow, so a review request reaches the reviewer.

**Depends on:** nothing. Can swap with slice 1 if you prefer.

**Events to add:** review-requested (to the named reviewer), review-approved and changes-requested
(to the submitter), and case-approved (to subscribers). Four notifiers.

**Files:** new `*Notifier` and `*Payload` pairs in
`backend/src/main/java/com/tcms/integration/`, mirroring `ItemAssignedNotifier` /
`ItemAssignedPayload` and `RunCompletedNotifier` / `RunCompletedPayload` — both already ship, so the
seam is proven. Extend `NotificationTexts` for the copy and
`NotificationSubscriptionView` for the new subscribable events.

**Traps already known:**
- Delivery is **at-least-once** and idempotency is the `(channelId, eventId)` unique constraint. A
  new notifier that does not supply a stable `eventId` will double-send on every retry.
- The review workflow raises domain events across a module boundary; `integration` must consume
  them as `CompanyScopedEvent` and re-establish context from the envelope. A handler that reads
  `CompanyContext` directly gets nothing — the decorator clears it on every task, deliberately.

**Acceptance gate:** `./gradlew build` green; a test per notifier proving a second delivery of the
same event is suppressed; queue rows written.

---

# Slice 3 — Execution duration

**Half a session.** Backend + contract + one form control. Pair it with slice 4 if you have room.

**Goal:** Record how long an execution took, so cycle effort can be measured.

**Migration:** `V68`. Add `duration_seconds INTEGER` to `test_execution` (nullable — a result
recorded without a stopwatch is ordinary, not an error). Include the `company_id` rule check: the
table already carries it, so this is a plain `ADD COLUMN`.

**Files:** the migration; the execution entity, input and view; `openapi.yaml`'s result-recording
request body and execution response; both `schema.d.ts` copies; the result form; the run rollup so a
run can total it.

**Traps:**
- `SchemaConventionTest` asserts the tenancy rule over every table — an `ADD COLUMN` will not trip
  it, but confirm the migration header states what the column is *for*, as every migration here does.
- The MCP's `tcms_record_result` and the CLI's `result record` both write this endpoint. Adding a
  required field would break both; keep it optional. `writeConformance.test.ts` drives every write
  arm and will catch a header change.

**Acceptance gate:** backend build green; `contractExchange` comparisons green in packages;
frontend suite green.

---

# Slice 4 — Bulk execution assignment

**One session.** Backend + contract + frontend.

**Goal:** Assign many executions to people in one act, the way `/projects/{projectId}/cases/bulk`
already does for cases.

**Depends on:** slice 3, only so the execution write path is opened once.

**Shape:** `POST /projects/{projectId}/runs/{runId}/executions/bulk`, mirroring the cases bulk
endpoint's request/response shape. Include a round-robin distribute mode — "spread these 300 across
these 5 people" is the actual request behind this gap.

**Files:** controller, service and input beside the existing per-execution assignee endpoint at
`openapi.yaml:3740`; the run detail screen's selection model and a bulk action bar.

**Traps:**
- Bulk acts write **one `audit_event` row per target sharing a `bulk_operation_id`** — the column
  exists for this. Slice 1's reader must group them, or a 300-case assignment floods the log with
  300 rows. **If slice 1 has landed, update its view in this slice.**
- The idempotency header applies. A retried bulk assign must not reassign.

**Acceptance gate:** backend build; a test proving a partial failure does not half-apply; queue rows.

---

# Slice 5 — Scheduled runs

**One session.**

**Goal:** Create a run automatically on a schedule from a saved scope.

**Migration:** `V69`. A `run_schedule` table — `company_id` first in every index per the tenancy
rule, project reference, the scope request (reuse the shape `V61` stores on `test_run`), a cron
expression, an enabled flag, and last-fired.

**Files:** the migration; a `RunScheduleService` and controller in `execution`; a `@Scheduled`
poller mirroring `OutboxScheduler`'s shape; a settings screen under the project.

**Traps:**
- The poller runs outside a web request, so it **must** open `CompanyContext` per schedule row from
  the row's own `company_id`. `AsyncCompanyContextTest` covers this family — add to it.
- Cron parsing belongs in one place. Do not scatter it.
- A schedule whose project was archived must not fire. Decide and record the behaviour.

**Acceptance gate:** backend build; a test proving a fire in company A cannot see company B's
schedules.

---

# Slice 6 — Cross-project case search

**One session.** Backend-led.

**Goal:** Search cases across every project the caller can see, in one query.

**Shape:** `GET /cases/search` — company-scoped, returning `projectId` and `projectName` on each hit.

**Why it is the riskiest read in this plan:** every other case read is scoped to one project and
gated by that project's grant. This one must return rows from *many* projects and filter to the
caller's grants **inside the query**, not after it — filtering in Java after an unrestricted read
means the count and the paging are computed over rows the caller may not see.

**Traps:**
- RLS scopes to the **company**, not the project. It will not save you here. The project-visibility
  narrowing is the application's job and this is exactly where it is easiest to get wrong.
- Add a test that a person granted one project of three sees hits from one project only — and assert
  on the **total count**, not just the page.

**Acceptance gate:** backend build; the grant-narrowing test above; a query plan check that the
search does not seq-scan (`V67` already fixed one seq scan on the authorization hot path — do not
add another).

---

# Slice 7 — Company data export

**One session.**

**Goal:** Export one company's data as a single downloadable archive, for offboarding and GDPR.

**Shape:** an operator CLI command — `export-company --slug=acme --out=...` — beside
`provision-company`, **not** an HTTP endpoint. The act is an operator's, it is long-running, and it
must be possible without a browser.

**Files:** a service in `provisioning`, a command registration beside the existing operator commands,
and the runbook note.

**Traps:**
- It reads every table, so it must run **inside one `CompanyContext`** and one read-only transaction,
  or a table read outside context returns zero rows and the export is silently short.
- Blobs (evidence, report artifacts, attachments) live on the filesystem, not in Postgres. An export
  that walks only tables produces a manifest referencing bytes it did not include.
- Decide and record whether the export is redacted. Password hashes and token secrets must not be
  in it.

**Acceptance gate:** a test that exports a seeded company and asserts row counts per table match a
direct count; and that company B's rows are absent.

---

# Slice 8 — Keyboard-driven execution

**One session.** Frontend only.

**Goal:** Record results without a pointer — the highest-volume repetitive task in the product.

**Depends on:** slices 3 and 4, whose controls it binds.

**Shape:** on the execution screen, single-key status entry, next/previous case, a key to focus the
notes field, and a discoverable shortcut overlay. Bind at the screen, not globally.

**Traps:**
- Shortcuts must not fire while a text field has focus. This is the defect every implementation of
  this feature ships first.
- Accessibility: every shortcut needs a visible, focusable equivalent — this is an accelerator, not
  the only route. Screen-reader users must be told the status changed, via a live region.
- Put the key map in one module so the overlay and the handler cannot disagree.

**Acceptance gate:** frontend suite, build and lint green; tests covering the typing-into-a-field
case explicitly.

---

# Slice 9 — Internationalisation groundwork

**One session for the groundwork. English stays the only locale.**

**Goal:** Route every user-facing string through a translation layer, so adding a locale later is
data rather than surgery.

**Why last:** it sweeps every screen. Run it earlier and slices 1–8 each add untranslated strings
behind it.

**Scope boundary:** ship the *mechanism* and the English catalogue only. **Do not translate
anything.** A second locale is a separate slice and probably a paid one.

**Shape:** an i18n provider in `app/Providers.tsx` — mind the nesting order, which is load-bearing
and documented in the file — a catalogue module, and a lint rule or test that fails on a bare string
literal in JSX so the sweep cannot silently regress.

**Traps:**
- `lib/labels.ts` already maps enums with `satisfies Record<Union, string>`, which makes a new enum
  member a compile error. **Preserve that property through the move** — losing it trades a
  compile-time guarantee for a runtime blank.
- Dates and numbers are `lib/format.ts`'s job. Do not let a second formatting path in.
- `lib/planScope.ts:78` warns against `localeCompare` for case keys. An i18n pass is exactly when
  someone "fixes" that sort. Do not.

**Acceptance gate:** frontend suite, build and lint green; the bare-literal check passing; a written
note in the handoff that no locale was added.

---

## Self-review

**Coverage.** All nine gaps in `docs/features.md` § *Gaps Not Currently Planned* have a slice:
audit log → 1, review notifications → 2, time tracking → 3, bulk assignment → 4, scheduled runs → 5,
cross-project search → 6, data export → 7, keyboard execution → 8, i18n → 9.

**Known softness, stated rather than hidden.** Slices 2–9 carry goal, files, traps and an acceptance
gate but not bite-sized TDD steps — deliberate, and the Scope Check at the top says why and what to
do about it. Slice 1 is the only one fully stepped.

**Flyway.** `V68` (slice 3) and `V69` (slice 5) are the only DDL, assigned in slice order. Nothing
else migrates.

**Cross-slice dependency to not forget:** slice 4 changes what slice 1's screen must render — the
`bulk_operation_id` grouping. It is written into slice 4's traps.
