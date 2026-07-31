# §9. Multi-company alignment of the frontend — DONE 2026-07-30

The frontend was written against the pre-multi-company design. It built clean the whole time, so it was *silently* out of date — the harder kind to notice. This is now corrected. Scope was **corrections only**: no company switcher, no `/c/{slug}` routing, no per-company branding (those wait for backend M6). Routes stayed project-scoped by decision.

Run through the `fe-react` skill (Mode B — extend), phases: contract → types → data → display.

### What landed

| Change | Where |
|---|---|
| Display keys `TC_0042` → **`TC-1`**, company-scoped, no zero-padding | `mocks/db.ts` allocator · 93 literals swept across `fixtures/{core,cases,reporting}.ts`, `index.css`, `Identifier.tsx`, `CaseFilterBar.tsx`, `PlanStatisticsPage.tsx` |
| **Executions lost their display key entirely** — reached via run or case | `openapi.yaml` (`TestExecutionSummary.key`, `ExecutionHistoryEntry.executionKey` removed) · `db.ts` · `ExecutionPage.tsx` · `RunDetailPage.tsx` |
| Role set 5 → **6** (Instance Admin + Company Admin), `COMPANY` scope | `openapi.yaml` `Role` · `fixtures/core.ts` |
| **`PermissionKey` as a 55-member contract enum** | `openapi.yaml` — the compiler now rejects any stale permission string |
| **`lib/permissions.ts` — new.** Catalogue as runtime data, exhaustive via `satisfies Record<PermissionKey, Area>` | mirrors the `lib/labels.ts` convention |
| Viewer is the default grant; projects are whole client products (`OLB`, `MOB`) | `fixtures/core.ts` |
| `automationKey` documented company-unique; `Project.key` pattern `^[A-Z0-9]{2,10}$`; `CaseId` param **UUID-only** | `openapi.yaml` |
| Route justification corrected — the old one was falsified | `app/router.tsx` |

### Two findings beyond the brief

1. **The `'*'` permission wildcard was a fiction.** The fixture gave Instance Admin `permissions: ['*']`; no such permission exists in the model (§6b — code checks permissions, never role names, and there is no wildcard). Worse than cosmetic: a sentinel keeps reading as "every permission" after a permission is *added that the role does not hold*. Coverage is now **derived** by comparing against the catalogue, so a gap renders as a gap. `TeamsPage` also groups permissions by area, which is what makes a 55-item list legible.
2. **`ExecutionHistoryEntry.executionKey` had no consumer** — a contract field nothing could populate once executions lost their key. Removed rather than left dangling.

### Gate evidence

`npm run build` clean · `npm run lint` 0 warnings · **36/36 assertions** in a throwaway `tsx` script (catalogue integrity, the three Test Engineer → Test Lead boundary lines, Company Admin holds no SYSTEM permission, grouping lossless for all six roles, key format, Viewer default) · 0 new testid collisions (the 6 pre-existing are the documented `TestIdPrefix` constants).

### ⚠ The browser pass did NOT happen

The Chrome extension was not connected. Per this project's own lessons 5–7, **the gates prove compilation, not correctness** — and this slice changed a table's column structure (`RunDetailPage` lost its "Execution" column, row→detail moved onto the title) and added grouped permission rendering. Both typecheck while potentially looking broken.

**Before trusting this visually, run `npm run dev` and check:** `/projects/{id}/runs/{runId}` (the executions table) and Teams & roles (the six roles, Instance Admin showing "every permission (55)", others grouped by area).

### Still deferred to M6

Company switcher · `/c/{slug}` routing and session switching · per-company branding (theme derived from two brand colours with contrast validation; status colours stay fixed) · invitation flow · per-membership display names · route flattening (now *possible* since keys are unambiguous, deliberately not done so the URL shape is designed once).
