<!-- orientation-cache v1 -->

# fe-react — orientation cache (TCMS)

**Last verified:** 2026-08-02
**Baseline commit:** `f551971`
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
- `npm run test:run` — Vitest + RTL. **490/490** at this baseline. **`testTimeout` is 30s since §67 — the second raise for one cause.** The suite has a load-dependent cliff: adding five tests anywhere pushed `DocPageEditorPage`'s two `userEvent.type` tests over the old 20s budget, in a file that slice never touched, always as *Test timed out* and never an assertion. The real fix (those tests type sentences character-by-character; `paste` asserts the same thing) is named in `vitest.config.ts` and not done. `#confirmed`
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
  permission KEY only.** ~~`/me/permissions` carries no qualifiers, so a scope- or author-restricted
  grant reads as fully held (§61)~~ → **superseded in §65: the response is now `[{key, qualifier}]`.**
  `PermissionGate` still asks the key alone, which is right for it — a *qualified* affordance asks
  `useCan(projectId).canOnScope(key, rowIsPlanScoped)` in the component body, mirroring the server's
  `canDoOnScope` arm for arm rather than reinterpreting the qualifier. `Gate.pending` is exposed for
  the one thing the fail-closed verdicts cannot express: **"not yet" is not "no"**, and using a
  pending verdict to *act* (rather than to disable) demotes every caller during the load — which is
  exactly what shipped into §65's first draft and what two unrelated tests caught.
  **Mocking `/me/permissions` in a test: use `src/test/permissions.ts`'s `heldPermissions()`.**
  The spies cast through `as never`, so a response-shape change breaks them with **zero type
  errors**, surfacing as `pointer-events: none` three assertions later. `#confirmed`
- **It must NOT be used inside a MUI `Select`** — `Select`
  clones its *direct* children to inject `role="option"`, `data-value` and the selection handler, so
  a wrapped `MenuItem` renders as an inert `role="menuitem"` that cannot be chosen at all, and
  carries no `aria-disabled` either, so a testid-based test reads it as "enabled". Inside a `Select`,
  read `useCan(projectId)` in the component body and pass a plain `disabled` prop
  (`features/cases/BulkActionDialog.tsx` sets the pattern). Cost the whole approve feature in §62,
  with every gate green. `#confirmed`
- **`api.upload` is the multipart seam** (`api/client.ts`), separate from `api.post` on purpose:
  a `FormData` body must **not** carry a hand-set `Content-Type` — the browser generates the
  boundary, and setting the header loses it, after which the server cannot parse a single part and
  answers 400 for a request that looks correct in devtools. `request` now omits the JSON
  content-type when `init.body instanceof FormData`. Uploads default to the **long** timeout
  budget, not `interactive`. `#confirmed`
- **`EvidencePanel`** (`features/runs/`) is the second author-qualified call site after
  `CaseDetailPage`, and the pattern to copy: `authorId={item.createdBy?.id ?? null}` — **`?? null`,
  never `?? undefined`**, since `PermissionGate` switches on `!== undefined` and `undefined` means
  "not author-qualified", which hands every row the unqualified verdict. Its test uses a
  `permissionsResolved()` barrier for §66's reason: `useCan` is fail-closed while pending, so
  `waitFor(toBeDisabled)` is satisfied by the loading state and proves nothing. `#confirmed`
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

- 2026-08-02 · EXTEND · The classification vocabulary editor (§69) — new route
  `/projects/:projectId/settings/classification` (`features/settings/`), **Classification** in the
  rail's Project group, three new hooks in `api/queries/projects.ts`. **Every gated control reads
  the dimension's `locked` flag to choose WHICH permission to ask, at which scope** —
  `company:vocabulary` via `useCanInCompany` for the company's axes, `project:settings` via
  `useCan` for the project's Feature tree — so `PermissionGate`'s `projectId="company"` literal has
  its second consumer after the project picker. Reuses §68's `buildValueTree`, where the
  orphan-surfacing matters in mirror image: **a value nothing renders is one nobody can retire.**
  The mock's `dimensions` moved from the fixture into `db` and `handlers/cases.ts` moved with it,
  or a value added in the editor is refused by the case editor as "not in the vocabulary".
  **The test failed for §66's reason before it failed for a real one**: rendered bare instead of
  through a `<Route>`, `projectId` is `''`, `useCan` is disabled and permanently pending, and every
  "disabled" assertion passes with no gate in the code at all. Render page tests through a `Route`
  and hold a `permissionsResolved()` barrier. `#confirmed`
- 2026-08-02 · EXTEND · The classification picker (§68) — the last of §5's three scheduled Phase-2
  gaps, and the largest. New: `features/cases/ClassificationPicker.tsx` + its test,
  `lib/classification.ts` (`buildValueTree`) + its test. `caseSchema` gained
  `classificationValueIds`; `CaseEditorPage`'s Classification panel replaces read-only feature chips
  with a control per dimension, inside its own `DataRegion` so a failed vocabulary read leaves the
  rest of the editor saveable. **The control shape is derived from `cardinality` + `hierarchical`,
  never from the dimension key**, so a fifth dimension needs no code. **The read-only comment's
  justification was false**: no leaf-only rule exists in any spec, and a parent IS assignable.
  `buildValueTree` lives in `lib/` because exporting a function beside a component defeats Fast
  Refresh (the `caseSchema.ts` trade) and it **surfaces orphans AND cycle members at the root**
  where `SuiteTree` drops cycle members — in a picker, a value with no control on screen is silently
  deselected by the next save. Mock now **enforces** the server's three rules rather than storing
  what it is given, and `buildCaseDetail` DERIVES its ids from the summary's names so no fixture can
  carry ids and names that disagree. §62's lesson again in the test: a bare `data-testid` on a MUI
  `Checkbox` lands on the wrapper span where `toBeChecked` cannot see it — testids moved to the
  `input` slot. Two mutations run, 3 red each. **Browser pass DEFERRED**; rows 8–10 are live-only
  (the mock has no retired values). 506/506.
- 2026-08-02 · EXTEND · The evidence UI (§67). New: `features/runs/EvidencePanel.tsx` + its test,
  `useAttachEvidence`/`useDeleteEvidence`/`MAX_EVIDENCE_BYTES` in `api/queries/runs.ts`, and
  **`api.upload`** in `api/client.ts` (see the seam above — the Content-Type rule is the one that
  would have failed silently). Mock: `handlers/runs.ts` gained all three evidence routes and
  **models the `OWN` refusal**, reading `createdBy` exactly as the server does; `db/executions.ts`
  seeds three evidence rows per FAILED execution whose authors **disagree** with `executedBy`, one
  of them with a null author. **Contract defect found by `tsc`**: `Evidence.createdBy` was
  non-nullable while the null author is the whole `OWN` arm — now `allOf` + `nullable`.
  Mutation-verified: deleting `authorId` from the JSX reddens exactly the two authorship tests.
  `testTimeout` raised to 30s — see Build, run & test.
- 2026-08-02 · EXTEND · Consumed the §65 qualifier wire shape. `useCan` gains `canOnScope` /
  `reasonOnScope` / `pending`; `ArtifactDialogs` disables the PROJECT scope option for a
  `PLAN_SCOPE` grant (disabled on the `MenuItem`, never wrapped — §62's rule). New shared
  `src/test/permissions.ts`. Mock handlers gained `holdsOn` / `qualifierOn` and the fixture carries
  the *qualified* grants, so mock mode cannot show a control live mode disables. **Browser pass
  DEFERRED** under the new standing policy — queued in `docs/verification/frontend-browser-checks.md`;
  §65 is built and not browser-verified. 470/470.

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
