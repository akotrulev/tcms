# Slice 1 — The audit log reader

**Lane:** backend + contract + frontend · **Migration:** none · **Depends on:** nothing

---

## Prompt

```text
Implement **Slice 1 — The audit log reader** from `docs/plans/2026-08-18-unplanned-gaps.md` using
the `ep-executing-plans` skill. This slice is **fully stepped in the plan** (Steps 1–25) — do not
re-plan it, execute it.

Read first, in this order: `docs/agent-context/be-java.md` and `docs/agent-context/fe-react.md`
(the orientation caches — trust the body over the `Baseline commit:` header), then the Slice 1
section of the plan. Backend work goes through **`/be-java`**; frontend work through
**`/fe-react`**. Neither is a skill — both are commands that load an agent definition.

**What this slice is:** the audit trail already writes a row for every act and nothing can read
it. `AuditEvent.java:220` says so outright: *"`audit:view` and `project:audit` still have zero
consumers."* Both permissions are seeded and `frontend/src/lib/permissions.ts:164,177` already
carries their UI labels. You are building the reader — two endpoints, two screens, no new writes
and no schema change.

**Scope boundary — do not widen it.** The *instance* log (`instance:audit` over
`InstanceAuditEvent`) is out: different table, different scope, no company context, and it belongs
with the M6 operator console. Export is out — `report:export` is a different permission and a
report type is a different slice. Say in the handoff that both were left out on purpose.

**Handoff:** this is **§242** — create `docs/handoff/242-the-audit-log-reader.md` plus **one index
row** in `docs/HANDOFF.md`. Never append prose to `HANDOFF.md` itself. Update both orientation
caches **in this slice's own commit**, and delete the now-false "zero consumers" sentence at
`AuditEvent.java:220`.

Checkpoint after each phase (A contract, B backend, C screens, D close).
```

---

## The three traps this slice turns on

**1. Three columns are nullable and a naive reader gets all three wrong.**
Verified from `backend/src/main/java/com/tcms/tenancy/AuditEvent.java`:

- `project_id` is **null for a company-scope act**. A project filter written as
  `projectId = :id OR projectId IS NULL` folds every company-scope row into every project's log.
- `actor_user_id` and `actor_token_id` are each nullable and exactly one is set — an act comes
  either from a person or from a token. A view that assumes a person NPEs on CI traffic.
- An actor can be **removed** after the act. The row must survive them; the display name goes null.
  A log that loses rows when someone leaves is not a log.

**2. `@Transactional(readOnly = true)` on the repository interface is load-bearing.**
Isolation is established in `doBegin`, so it needs a transaction — and Spring Data gives one only to
the CRUD methods *inherited* from `SimpleJpaRepository`, never to the `@Query` methods *declared* on
the interface. Without the annotation `app.current_company` is never set and every read returns
**zero rows** while `CompanyContext` holds the right company. Fail-closed and silent.
`RepositoryTransactionTest` derives its list from the live context and will fail the build.

**3. The paging sort needs a secondary key.** `ORDER BY occurredAt DESC, id DESC`. Two acts in one
transaction share a timestamp to the microsecond; an unstable sort makes page 2 repeat or skip rows.

## Standing rules that bind this slice

- **Tests run on `shanks`, not locally** — `~/bin/tcms-remote backend ./gradlew build`,
  `~/bin/tcms-remote frontend npm run test:run`. A few targeted tests may run locally for debugging;
  the gates do not.
- **Regenerate both `schema.d.ts` copies** after the `openapi.yaml` change — `frontend/src/api/` and
  `packages/client/src/`. They are byte-identical and `schemaFreshness.test.ts` compares them.
- **Add a `required` array to every new schema**, including nested objects. Without one every
  generated property is optional and no consumer can use it.
- **Browser passes are deferred and batched (§65).** Write the rows into
  `docs/verification/frontend-browser-checks.md` and `backend-mutation-checks.md` and **run
  nothing**. The slice is *built and unverified* until that queue runs — describe it that way.
- **Work on the current branch**; do not create one. Stage only the files this phase touched
  (`git add <paths>`, never `git add -A`) — there are other lanes' edits in this tree.
- **Disable, don't hide.** Gate the nav entries on `useCan('project:audit')` / `useCan('audit:view')`
  and render them disabled, not absent.
