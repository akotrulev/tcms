# Slice 10 — the caller's effective permissions, and disable-don't-hide everywhere at once

Date: 2026-07-31 · Skill: `fe-react` (Mode B — extend, re-entering Phase 2) · Handoff §30 "Next" item 1.

## The orientation pass changed the slice before it started, again

§30 named this item as **"the caller's effective permissions on a project, in the contract"** and
called it "one contract change". The orientation pass found that framing wrong in a useful way:

- **`GET /me/permissions` is already in `openapi.yaml`** (since the mock era) and **already
  implemented** — `MeController.permissions()`, with the three-question split (SYSTEM-only,
  SYSTEM+COMPANY, SYSTEM+COMPANY+PROJECT) and a comment explaining why there is no `userId`
  parameter. `AuthEndpointTest` covers it. The mock handler exists and was aligned in an earlier
  slice.
- **Nothing in the SPA has ever called it.** `queries.ts` has no hook, `keys` has no entry.

So this is not a contract slice with a UI tail. It is a **frontend slice** whose only contract work
is the `required` array the response schema never got — the working agreement's recurring lesson,
which is why the generated type currently reads `{ projectId?: string; permissions?: PermissionKey[] }`
and no consumer could use it without null-checking fields the server always sends.

## Scope

| Path | Change |
|---|---|
| `docs/specs/openapi.yaml` | Named `EffectivePermissions` schema **with `required`**; `projectId` nullable. |
| `frontend/src/api/schema.d.ts` | Regenerated. |
| `frontend/src/api/types.ts` | `EffectivePermissions` export. |
| `frontend/src/api/queries.ts` | `keys.permissions(projectId)` + `useMyPermissions(projectId)`. |
| `frontend/src/auth/useCan.ts` | **New.** The one place the loading/failed policy lives. |
| `frontend/src/components/PermissionGate.tsx` | **New.** The one place a denied action becomes a disabled control with a why-tooltip. |
| `frontend/src/lib/permissions.ts` | `permissionLabels` (`satisfies Record<PermissionKey, string>`) — the phrase a tooltip needs. |
| `features/cases/*`, `features/runs/ExecutionPage.tsx` | Every write affordance gated. |
| `frontend/src/mocks/handlers.ts` | Serve a role that is *not* full-access when asked, so the gate is provable in mock mode. |

## The three decisions worth reading

### 1. Unknown is DENIED, failed is ALLOWED — and the asymmetry is the point

Three states, not two, and they get different answers:

- **Loading** → disabled, tooltip "Checking your permissions…". A flash of disabled-then-enabled
  costs a grey moment. The other order — enabled-then-disabled — is the defect itself: the user
  clicks in the window before the answer lands and gets the 403 this slice exists to prevent.
- **Resolved** → the server's answer.
- **Failed** → **enabled**. Fail-open, deliberately, and it is the only rule here that looks wrong.
  Hidden is not protected (plan §6b): the server rejects independently, so a wrong *enable* costs a
  403 in a dialog — the exact behaviour the app has had since Phase 2 and survives fine. A wrong
  *disable* tells a Test Lead they may not do their job, with no retry path and no error on screen,
  because the failure is in a query nothing renders. The cheap failure is the enabled one.

### 2. The gate is one component, because a second one is how a hundred consistent pages drift

`PermissionGate` owns the whole mechanic: MUI swallows pointer events on a disabled control, so a
`Tooltip` around one never fires — the denied branch wraps the child in a span. Writing that inline
at eleven call sites is eleven chances to forget the span and ship a disabled button that explains
nothing. Same argument as `DataRegion` and `Identifier`.

### 3. Every write affordance in the app, not just the suite ones

§30 refused to gate suites alone precisely so this slice could sweep the class. Enumerated from the
mutation hooks in `queries.ts` and their consumers:

| Affordance | Permission |
|---|---|
| Suite tree: New · New suite inside · Rename | `suite:manage` |
| Suite tree: Delete | `suite:delete` |
| Case list: New case (header + empty state) | `case:create` |
| Case list: Edit selected (bulk) | `case:edit` |
| Case detail: Edit case | `case:edit` |
| Case editor: Save (create mode / edit mode) | `case:create` / `case:edit` |
| Execution: Passed · Failed · Blocked · Skipped | `result:submit` |

Not gated, and each for a reason: **board drag-and-drop** (`workitem:manage`) — the affordance is a
drag, not a control, and disabling it needs a different mechanic than a tooltip; noted rather than
half-done. **`useDeleteCase`, `useCreatePlan`, `useCreateRun`, `useCreateWorkItem`** have no
consumers — four more dead hooks alongside `useCaseVersions`, listed for the handoff rather than
given affordances they have not earned.

## Gate

`npm run build` clean · `npm run lint` 0 warnings · browser pass in **both** modes: mock (a
non-lead role, so the disabled branch is visible) and live against `docker compose` with a real
session, checking that a permission the caller *does* hold is enabled and one they do not is
disabled with the tooltip — and that the tooltip actually appears, which is the part a compile
cannot prove.
