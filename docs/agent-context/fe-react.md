<!-- orientation-cache v1 -->

# fe-react — orientation cache (TCMS)

**Last verified:** 2026-08-02
**Baseline commit:** `34aa2a6`
**Fingerprint files:** `frontend/package.json`, `frontend/package-lock.json`, `frontend/tsconfig.json`,
`frontend/vite.config.ts`

> Authoritative docs win over this file. `CLAUDE.md`, `docs/HANDOFF.md` (an index — sections live in
> `docs/handoff/NN-slug.md`), and the specs in `CLAUDE.md`'s precedence table. This is a map for
> re-orienting fast, not a source of truth.

## Version lane

- **React 19 + TypeScript strict**, **Vite 8** SPA. **No Next.js, no RSC** — so there is no
  `'use client'` boundary anywhere and the skills' RSC lane does not apply. `#confirmed`
- **MUI v7** (with X DataGrid / TreeView), **React Router 7**, **TanStack Query v5**.
- Package manager **npm**. `erasableSyntaxOnly` is on, which forbids TS constructor parameter
  properties — see `api/client.ts:35` for the recorded workaround. `#confirmed`

## Build, run & test

- `npm run dev` — MSW mock mode by default, **no backend needed**; sign in with any 4+ char password.
- **`npm run build` is THE gate** (`tsc -b` + vite build). **Do not trust `tsc --noEmit`** — it has
  reported 0 errors while the build failed with nine (working agreement). `#confirmed`
- `npm run lint` — oxlint, must stay at **0 warnings**; it prints nothing when clean.
- `npm run test:run` — Vitest + RTL. **426/426** at this baseline.
- `npm run gen:api` — regenerate `src/api/schema.d.ts` after **any** `openapi.yaml` change. Runs
  through `npx` on purpose (the scaffold pins TS 6, the tool peer-requires 5.x); do not "fix" it.
- **Live mode:** `VITE_API_MODE=live` in `.env.local` proxies `/api` to `localhost:8080`.
  **After changing `.env.local`, kill vite and `rm -rf node_modules/.vite`** — an in-place restart
  leaves a stale optimize-deps hash and the page renders **blank with an empty console**. Hit and
  confirmed in §61's live pass; the remedy works. `#confirmed`

## Layout

`src/{api,app,auth,components,features,lib,mocks,notifications,a11y,test}`. Feature folders under
`features/<area>/`; routes are project-scoped under `/projects/:projectId/*` (`app/router.tsx`, lazy).

## Seams to reuse

- **TanStack Query owns all server state**; no server data in any client store. `src/api/queries/` is
  the data module — one `keys` factory (`queries/keys.ts`), per-endpoint timeout budgets. `#confirmed`
- **Client state:** session in Context; **filter state belongs in the URL** (`useSearchParams`).
  *Known deviation:* `features/artifacts/ArtifactsPage.tsx` keeps its plan filter in `useState`, so a
  plan's register cannot be linked to — recorded in handoff §61, not fixed. `#confirmed`
- **`app/Providers.tsx` nesting order is load-bearing** and documented in the file: the query client
  is constructed *innermost* because its global error handlers consume the notification and
  session-recovery providers.
- **`components/PermissionGate.tsx`** is the one place a lacked permission becomes a **disabled
  control with a why-tooltip** (§31, disable-don't-hide). Render prop, not `cloneElement`; the denied
  branch wraps in a span because MUI kills pointer events on a disabled control. **It gates on a
  permission KEY only** — `/me/permissions` carries no qualifiers, so a scope- or author-restricted
  grant reads as fully held (see §61). **It must NOT be used inside a MUI `Select`** — `Select`
  clones its *direct* children to inject `role="option"`, `data-value` and the selection handler, so
  a wrapped `MenuItem` renders as an inert `role="menuitem"` that cannot be chosen at all, and
  carries no `aria-disabled` either, so a testid-based test reads it as "enabled". Inside a `Select`,
  read `useCan(projectId)` in the component body and pass a plain `disabled` prop
  (`features/cases/BulkActionDialog.tsx` sets the pattern). Cost the whole approve feature in §62,
  with every gate green. `#confirmed`
- **Forms:** react-hook-form + zod via `zodResolver`; each dialog owns an `applyServerError` that maps
  the RFC-7807 `errors` map onto its fields and falls back to a root alert via `describeError`.
  `mode: 'onBlur'`; **focus on open, never `reset(defaults)`** — a reset keyed on an object literal
  re-runs each render and wipes a failed submit's errors. `#confirmed`
- **`data-testid` goes on the INPUT, not the wrapper**: `slotProps={{ htmlInput: { 'data-testid': … } }}`.
  Putting it on the `TextField` lands it on the root div and `userEvent.type` silently does nothing —
  cost six tests failing for one reason in §61. Tests reach a MUI select's combobox via
  `.closest('.MuiInputBase-root')` then `getByRole('combobox')`. `#confirmed`
- **Errors are centralized:** `lib/describeError.ts` (copy + `isTransient()`), `app/queryClient.ts`
  (the single raise point; opt out per-call with `meta: { handled: true }`),
  `components/ErrorBoundaries.tsx` (root / route / region tiers). `DataRegion` owns the
  loading/loaded/failed/empty/stale matrix.
- **Shared display layer, not per-page:** `lib/labels.ts` (each `satisfies Record<Union,string>`, so a
  new enum member is a compile error until labeled), `lib/format.ts`, `lib/results.ts`,
  `lib/permissions.ts`, `Identifier` for monospace, `ScrollX` for wide content.
- **Mock API is stateful** — `mocks/db/` + `mocks/handlers/*.ts` read *and write* a cloned fixture
  store, so round-trips land in lists. Handlers are written from the **server's** rules, not from what
  one client sends (§52). Resets on reload.
- **Contract-hygiene tests are generated per operation** — `api/contractOperations.test.ts`,
  `contractParameters.test.ts` (drives the TS compiler, not a regex), `apiResponseTypes.test.ts`
  (refuses an inline literal at any `api.*` call site). A new endpoint is covered automatically.
- **Fault injection** (dev only, browser console): `tcmsFaults.status/hang/expire/drift/only/off`.
  Faults live in module state, so arm them *after* load.

## Conventions observed

- Comments carry the **why**, and often the failure mode a rule prevents — unusually dense and
  load-bearing. Match that register.
- Test files state, in a header, the specific failures they exist to catch, and are **mutation-checked
  rather than watched to pass**.
- MUI `theme` + `sx` as the single styling authority. **No Tailwind.** Tokens are CSS custom
  properties in `index.css` bridged into `createTheme`.

## Known unknowns

- E2E / page objects are the `qa-ui-typescript` peer's; none exist here.
- **`/reports` is the only dead contract left** — no hook at all, and it belongs to **M2.5**
  (`ExecutionFact`, `ReportModel`, renderers, a job pipeline), so it is a milestone rather than a
  slice. §64 built the step library and gave `useSharedSteps` its first caller.
- Lighthouse / Core-Web-Vitals never measured (handoff §5); `CaseListPage` is over its route budget
  with the reasoning recorded in `vite.config.ts`.

## Session log

- 2026-08-02 · EXTEND · Built `SharedStepPicker` (§64) — the first consumer `useSharedSteps` has ever
  had, reached from the case editor's step list, plus `useCreateSharedStep`. Inserting sets the
  step's `action` to the entry's TITLE and its `sharedStepId` to the id: a shared block is a
  reference resolved on read, so copying the body in yields a step that looks identical and stops
  tracking its source. The picker is **not** permission-gated (reading needs only the project
  grant); the inline create is, on `library:manage`. **Two mock defects**: the shared-step fixtures
  carried usageCount 48/33/91 while no fixture case referenced any entry — the handler now derives
  the count through `caseDetail`, since a summary row carries no steps — and `GET /shared-steps`
  alone did not `requireAuth`. A fixture case now references an entry, so the detail page's "Shared"
  chip is drawable in mock mode for the first time. **Harness trap**: `PermissionGate` swaps the DOM
  node between its denied (tooltip-wrapped) and allowed (bare) branches, so a held reference never
  becomes enabled — re-query inside `waitFor`; and since the gate is fail-closed while
  `/me/permissions` is in flight, a "disabled" assertion is only non-vacuous when paired with an
  enabling one. `#confirmed`
- 2026-08-02 · EXTEND · Extended `BulkActionDialog` to all eight bulk actions (§62), typed
  `BulkInput`/`payload` from the contract (both were `string`/`Record<string,unknown>`), and brought
  the MSW handler into agreement with the new server — it had **dropped unresolvable ids** so the
  three buckets did not add up to `total`, and answered "not supported yet" for six actions. Added
  `BulkActionDialog.test.tsx` (13 tests). **The live pass found the approve option was unselectable**
  because `PermissionGate` wrapped a `Select` child — see the seam note; tests now assert through
  `role="option"` and count options against `lifecycleLabels` rather than reading a testid.
- 2026-08-02 · EXTEND · Added the artifact register's write half (§61): `useCreateArtifact`,
  `features/artifacts/ArtifactDialogs.tsx` (scope × kind cross-field form) + its 6 tests, the MSW
  `POST /artifacts` handler mirroring the PLAN_SCOPE qualifier, and the `docPageId` link that makes a
  documentation artifact reachable. Cache created this run.
