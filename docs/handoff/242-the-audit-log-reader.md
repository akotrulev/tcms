# §242 — The audit log reader

**Slice 1 of `docs/plans/2026-08-18-unplanned-gaps.md`.** Two endpoints, one screen behind two
routes, no migration, no permission seeded or moved, catalogue unchanged at 55.

## Gates — all green, after `shanks` came back mid-slice

| Gate | Result |
|---|---|
| `./gradlew build` | **BUILD SUCCESSFUL**, **3097** tests (3082 + this slice's 15) |
| `npm run test:run` (frontend) | **1602 passed**, 120 files (was 1582 / 119) |
| `npm run build` + `npm run lint` (frontend) | built; **0 warnings, 0 errors** |
| `npm run build` + `test:run --workspaces` (packages) | built; **336** — 78 cli / 130 client / 128 mcp |

> **`shanks` was unreachable for most of this session** — `shanks.local` and `clearstep` both failed
> to resolve, and the whole slice was written blind against it. It came back before the work was
> finished, so everything above was actually run. **The verification queue rows are still deferred
> by §65 and the slice is still built-and-unverified in that sense**; what changed is that the
> automated gates are no longer unrun.
>
> **Five of the six things the gates caught were invisible to a typecheck** — a local
> `tsc --noEmit` passed clean over the SPA while three of its tests failed. That is the working
> agreement's own warning arriving on schedule.

## What the gates caught, and the one worth carrying

**`(:instant IS NULL OR column >= :instant)` does not work in HQL, and the precedent that said it
would was narrower than it looked.**

The plan proposed `CAST(:after AS timestamp)`. The first implementation dropped the cast, on the
precedent of `TestRunRepository.findLiveInProject`, which uses exactly the bare idiom — **and
fourteen of this class's fifteen arms failed identically** with `could not determine data type of
parameter $7`. Hibernate infers a parameter's type from where it is used; in the `IS NULL` position
there is nothing to infer from, and for `UUID` and `String` the *second* occurrence settles it. For
an `Instant` it does not: Hibernate binds the parameter as `bytea`. Adding the cast back made it
worse and more legible at once — `cannot cast type bytea to timestamp with time zone`, because the
cast applies to a value already bound as the wrong type.

The fix is `coalesce(:after, e.occurredAt)`: the parameter sits in a typed context, so it binds as a
timestamp, and a null makes the predicate `occurredAt >= occurredAt`, true for every row because the
column is `NOT NULL`. Argued in full at the repository. **The generalisable lesson is not about
dates** — it is that the repository's house idiom had an unstated type precondition, and reading two
green call sites is not the same as checking whether the third shares it.

The other five:

- **`audit_event.actor_user_id` has a FOREIGN KEY to `app_user`.** The "unresolvable actor" arm
  invented an id and could not insert it. The corrected fixture is a real account with no membership
  — which is a *better* statement of the shape: the account always exists, the **membership** is
  what resolves the §6u name, and they are different tables at different scopes.
- **`contractParameters.test.ts` reads the `qs({…})` object statically.** `{...filters, projectId:
  undefined}` still *sends* `projectId` as far as that walker is concerned, and the project
  operation does not declare it. Both hooks now list their keys explicitly.
- **Gating the rail broke `AppShell.test.tsx`** — five arms. Its double answered `[]` to everything
  but `/projects`, and the shell now reads `/me/permissions`, where an array is not the object the
  contract declares. A consequence of the hide decision, and worth knowing before the next gate is
  added to the shell.
- **`responseCredentials.test.ts` caught `actorTokenId` and `actorTokenName`** on the `token`
  needle. Neither is a credential — the id is a primary key `ApiToken.id` already serves and the
  name is an administrator-chosen label, `ExecutionHistoryEntry.executedByToken`'s category exactly
  — so both are now in `BY_DECISION` with the reason.
- **Two test-side races.** `findByTestId` on the refusal panel resolved against the *pending* copy
  ("Checking your permissions…"), and `window.location.search` is empty under `TestProviders`'
  `MemoryRouter`, so the URL-round-trip assertion was replaced by one that renders a pre-narrowed
  route — which is the stronger claim anyway, since component state could not produce it.

## What this is

`AuditEvent.java:220` recorded that `audit:view` and `project:audit` had **zero consumers**. Every
act in the product had written a row since M0, the permissions were seeded, and the SPA already
carried the labels. Everything except the reader existed. Now:

- `GET /api/v1/projects/{projectId}/audit-events` — costs `project:audit`, one project's acts.
- `GET /api/v1/audit-events` — costs `audit:view` at COMPANY scope, the whole company's.
- `/projects/:projectId/audit` and `/admin/audit`, both served by one lazy `AuditLogPage`.

## Deliberately out of scope, and it must stay out

- **The instance trail.** `instance_audit_event` / `instance:audit` is a different table with no
  `company_id` on it, recording what the **operator** did across companies. It belongs with the M6
  operator console. It is absent from `openapi.yaml` on purpose and the contract says so where the
  company path is declared.
- **Export.** `report:export` is a different permission and a report type is a different slice.

## The five plan corrections, carried

The plan was written before the code was read. Five of its instructions were wrong; all five were
approved as corrections before Phase A and are implemented as corrected.

1. **The reader is in `identity`, not `tenancy`.** `tenancy/package-info.java` declares
   `allowedDependencies = {}`. A service there cannot reach `PermissionEvaluator`,
   `ProjectService.readGranted` or user-name resolution, and `ModuleStructureTest` would fail on the
   first import. `identity` is `{api, tenancy, project}` and reaches all three —
   `OrgChartService`/`OrgChartController` from §241 is the precedent. The **entity and its
   repository stay in `tenancy`**, because everything writes to them.
2. **`AuditEventRepository` already existed.** The plan said "create". It is public, carries
   `findByActionOrderByOccurredAtDesc` and a header arguing why `target_ids` containment is not
   queryable in HQL. It was **modified**: header kept, `@Transactional(readOnly = true)` kept, two
   paged `@Query` methods added.
3. **Both actor columns can be set on one row, and normally are.** The plan's contract fragment said
   "exactly one is set". `AuditEvent.record` fills `actorTokenId` from `TokenContext` **beside**
   `actorUserId`, never instead — the caller identity on a token request is the token's *owner*. The
   plan's `aTokenDrivenActRendersWithNoPerson` was re-aimed at
   `aTokenDrivenActNamesBothThePersonAndTheCredential`, and a separate arm covers the genuinely
   person-less case.
4. **Two entries left `ArchitectureRulesTest.READERLESS_BY_DECISION`** — `AuditEvent.actorTokenId`
   and `AuditEvent.bulkOperationId`. `AuditEventView` calls both getters, which gives the fields
   readers, and the rule asserts `containsExactlyInAnyOrderElementsOf`, so a stale exemption fails
   the build (§162). **`bulkOperationId` stays in the writerless map** — its entry was rewritten to
   say the reader arrived without the writer, and slice 4 is what deletes it.
5. **Paging reuses `PageMeta`**, in the `allOf: [PageMeta, {items}]` shape
   `/projects/{projectId}/cases` uses — not the plan's invented `AuditEventPage`.

## A sixth correction, found during this slice

**The plan (and the contract text Phase A wrote from it) claimed `actorUserName` goes null when the
person is removed. It does not, and should not.**

`AdminDirectoryService.peopleById` reads `memberships.findByCompanyId` **without filtering on
status**, and offboarding sets a membership to `REMOVED` rather than deleting the row. So a departed
colleague's acts still render under their name. That is the behaviour an audit trail wants — *who
did this* must not become unanswerable the day somebody leaves — so the **contract text was changed
to match the code**, not the other way round.

`actorUserName` is null in exactly two cases: `actorUserId` is null, or it names a membership that
no longer exists at all (an operator-level deletion). Same rule for `actorTokenName`, where
`findAllByIdIn` includes **revoked** tokens deliberately: an act by a withdrawn credential is
precisely the row somebody opens this screen to explain. `anOffboardedActorKeepsTheirName` and
`anUnresolvableActorLosesOnlyTheirName` are the two arms, and `§242 LG-M10` is the mutation.

**This changed the generated types**, because descriptions become JSDoc: both `schema.d.ts` copies
were regenerated and `diff` reports IDENTICAL. Note the client copy is **`packages/client/src/schema.ts`**,
not the `schema.d.ts` the plan names.

## The decisions worth reading before changing anything

### A refusal is 403 with a sentence, never an empty page

`AdminDirectoryService` does the opposite one module over — it answers an empty list rather than a
403, because its contract declares only 200. That is the wrong answer here and the difference is
what the surface *means*. An empty directory reads as "nobody is here", which is unhelpful. **An
empty audit trail reads as "nothing happened", which is a false statement about the past that the
reader has no way to detect**, on the one screen whose entire purpose is to be trusted about the
past. Both operations declare 403; both service methods return `GateResult.Denied`; the SPA renders
the refusal in words **and does not make the request at all**.

### The sort key is `(occurredAt DESC, id DESC)` and the second half is load-bearing

Every writer stamps a transaction's rows from one `Instant`, so a bulk act produces rows sharing
`occurred_at` to the microsecond. Without the tiebreaker Postgres may order them differently for
page 1's `LIMIT/OFFSET` and page 2's, and a row is served twice or not at all. On an audit trail
"not at all" is the failure the table exists to prevent. `theSortIsStableWhenTimestampsCollide`
covers it — see `§242 LG-M2` for why that mutation may read green on a small fixture anyway.

### The company trail is not narrowed to the caller's project grants

`audit:view` is held by Instance Admin and Company Admin only — the two roles who already staff
every project. Clipping the trail to the holder's own grants would make the company log unable to
answer the question it exists for. The project trail is the surface for a reader holding only a
project grant. `projectId` on the company path is a **filter, not a gate**: an unknown id matches
nothing rather than 404ing, or the query string becomes an oracle for which projects exist.

### The two nav entries are HIDDEN, and that breaks this rail's own rule on purpose

**Set at the product owner's direction during this slice.** `AppShell.tsx` argues five separate
times that rail items are never gated — §31's disable-don't-hide, and the stronger claim that hiding
an entry lies about the *product* ("this instance has no CI credentials") which is worse than
telling the truth about the reader's *access*. The audit trail is the one place that trade runs the
other way: the screen's subject is who did what, so advertising it to somebody who may not read it
invites the request the permission exists to refuse.

So `AuditReach` in `AppShell.tsx` is **the only permission gate in the rail**, it hides rather than
disables, and it fails closed while pending. The reasoning is written at the type so the next reader
does not find the five contrary comments first and "fix" it. `§242 LG-7` is the browser row, and it
says explicitly that finding these disabled-with-a-tooltip later would be a regression toward the
general rule rather than a repair.

### Deviations from the plan's frontend shape, both deliberate

- **`auditLabels.ts` in the feature folder → `lib/labels.ts`.** CLAUDE.md's working agreement puts
  display logic in the shared layer. The plan also asked for a map typed so an unlabelled action is
  a compile error; **that is impossible here** — `AuditEvent.action` is a bare `string` in the
  contract because the set grows with every slice that records something, so there is no union for a
  total map to be total against. `notificationEventLabel` one screen over is the same shape for the
  same reason, and `auditActionLabel` follows it: a curated map with a computed fallback, so an act
  a newer server records renders as "Suite condition attached" rather than blank.
- **MUI `Table` + `TablePagination`, not `DataGrid`.** The plan assumed DataGrid and lazy-loaded the
  route for that reason. The trail is read-only, has a fixed server-side sort and no selection, so
  DataGrid buys nothing and costs a sizable MUI X chunk — the route stays lazy regardless.
  **And §241 already measured the decisive reason: `DataGrid` does not render in jsdom at all** —
  its virtualization measures through a `ResizeObserver` the test shim never fires, so the scroller
  is 0×0 with no rows, and six arms of the org chart's suite failed on the DataGrid version before
  it was rewritten as a `Table`. `CaseListPage` is the repo's only DataGrid screen **and has no
  component test at all**, which is the same fact from the other side. So the twelve arms in
  `AuditLogPage.test.tsx` are only possible on a `Table`. The build ran: `CaseListPage` remains the
  largest chunk at 433 kB and this screen adds no comparable one.

## What is owed

- **An index.** The project trail's predicate is `(company_id, project_id, occurred_at DESC)` and
  `V1` has no such index — it indexes `target_type` and `actor_user_id`. This slice takes **no
  Flyway version on purpose**: the gaps plan reserves `V68` for slice 3 and `V69` for slice 5, and
  renumbering is refused. Recorded as `§242 LG-M14`, with §241 `IX-1`'s note attached — an index
  changes no result, only a plan, and nothing here asserts on a plan.
- **`§242 LG-M3`'s gap.** Nothing constructs a request sending `?projectId=<other>` to the *project*
  path. The service ignores it (it uses the gated project's own id), but no test proves that.
- **`§242 LG-5` is not drivable.** An act with `actorUserId` null has no producer in the product —
  every write path carries a caller. It becomes drivable with slice 5's scheduled runs.
- **Slice 4 must revisit this screen.** Bulk acts will write one `audit_event` row per target
  sharing a `bulk_operation_id`, and the reader must group them or a 300-case assignment floods the
  trail. The column is already on the wire so that grouping is not a contract change; the writerless
  exemption is what slice 4 deletes.

## Files

| Change | Path |
|---|---|
| Modified | `docs/specs/openapi.yaml` — `audit` tag, two paths, `AuditEvent` schema |
| Regenerated | `frontend/src/api/schema.d.ts`, `packages/client/src/schema.ts` (byte-identical) |
| Modified | `backend/…/tenancy/AuditEvent.java` — three stale claims corrected, one comment deleted |
| Modified | `backend/…/tenancy/AuditEventRepository.java` — two paged queries |
| Created | `backend/…/identity/{AuditLogQuery,AuditEventView,AuditEventPageView,AuditLogService,AuditLogController}.java` |
| Created | `backend/…/identity/AuditLogReadTest.java` — 14 arms, UUID prefix `0a` |
| Modified | `backend/…/architecture/ArchitectureRulesTest.java` — two exemptions dropped, one rewritten |
| Modified | `backend/…/contract/ContractConventionTest.java` — one named pairing, two inline |
| Created | `frontend/src/api/queries/audit.ts`, `frontend/src/features/audit/{AuditLogPage.tsx,useAuditFilters.ts,AuditLogPage.test.tsx}` |
| Modified | `frontend/src/api/{types.ts,queries/keys.ts,queries/index.ts}`, `src/lib/labels.ts`, `src/test/fixtures.ts` |
| Modified | `frontend/src/app/{router.tsx,AppShell.tsx}` |
| Appended | both `docs/verification/` queues — `§242 LG-1…10`, `§242 LG-M1…M14` |
