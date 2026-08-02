<!-- orientation-cache v1 -->

# fe-react — orientation cache (TCMS)

**Last verified:** 2026-08-02
**Baseline commit:** `30155fc`
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
  grant reads as fully held (see §61). `#confirmed`
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
- `cases/bulk` is the last MSW-only surface with a live consumer (`BulkActionDialog`). `/reports` has
  no hook and `useSharedSteps` has no call site — dead contract.
- Lighthouse / Core-Web-Vitals never measured (handoff §5); `CaseListPage` is over its route budget
  with the reasoning recorded in `vite.config.ts`.

## Session log

- 2026-08-02 · EXTEND · Added the artifact register's write half (§61): `useCreateArtifact`,
  `features/artifacts/ArtifactDialogs.tsx` (scope × kind cross-field form) + its 6 tests, the MSW
  `POST /artifacts` handler mirroring the PLAN_SCOPE qualifier, and the `docPageId` link that makes a
  documentation artifact reachable. Cache created this run.
