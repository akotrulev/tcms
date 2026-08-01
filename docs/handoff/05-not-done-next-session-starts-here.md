# §5. NOT done — next session starts here

### Phase 2 gate — PASSED (evidence)

`npm run build` clean · `npm run lint` 0 warnings · 206 `data-testid` sites, **0 collisions** (the six apparent duplicates are per-file `TestIdPrefix` constants resolving distinctly, or mutually exclusive render branches — see the audit command in §7) · no page-level horizontal overflow at 390 px (the DataGrid scrolls inside its own container) · console clean, no key/hydration warnings.

**Driven in a real browser end to end**, not just built: sign-in → project → dashboard tile → filtered list → case detail → edit → save (**title changed, v3 → v4, toast, list reflects it**) → run → execution → untick a precondition → Blocked dialog pre-filled with the unmet condition → record → **run counts recomputed** (Blocked 0→1, Untested 27→26, filter chips updated). Version pinning confirmed visually: the run still shows TC_0003 at **v3** after the case was edited to v4.

### Three defects found in the Phase 2 browser pass

1. **Theme toggle was a no-op on a system-dark machine.** It read `mode === 'dark'`, but the default mode is `system` — so the control offered "switch to dark" on an already-dark page and the first click only moved `system` to the equivalent explicit value. Now resolves via `systemMode`.
2. **Only the 60 px key cell in the case grid navigated**, not the title. The affordance technically existed but the row read as broken. The title is a `<Link>` now too.
3. **Plan statistics contradicted itself**: the headline showed 91.1% ("of executed") while the exit criterion showed 89.1% (of all in scope), with nothing explaining the difference — the exact ambiguity §6p exists to remove. The exit-criteria panel now states its denominator.

### Phase 3 — quality layers: DONE (2026-07-30)

Skills applied: `error-handling` (+ both `references/`), `accessibility`, `performance`, `seo`.

**The gap the handoff had not spotted, and the most severe one: there was not a single error boundary in the app**, while every heavy route is `lazy()`. Any render throw or failed chunk download blanked the entire SPA.

| Area | What shipped |
|---|---|
| Boundaries | `components/ErrorBoundaries.tsx` — root (outside the providers, so a theme/query-client throw is caught), per-route (**inside** the shell, so the rail survives and you can navigate away from a broken page; `resetKeys` on pathname), per-region. `DataRegion` wraps its own children in one. |
| Notifications | `notifications/` — top-right, **never auto-dismisses**, close button, copyable correlation id, deduped by `correlationId ?? code\|region` with a count, **rolled up by cause**, capped at 3 + "N more". Raised from **one** place (`QueryCache`/`MutationCache` `onError`); call sites with better copy opt out via `meta: { handled: true }`. notistack stays for success toasts only. |
| Retry | Was a blanket `retry: 1` that retried 4xx too. Now `isTransient()` (5xx/network/timeout/408/429 only), exponential backoff, **honours `Retry-After`**, stops after 3. Mutations never auto-retry. |
| Session | `auth/SessionRecoveryProvider` — a mid-session 401 raises **re-auth in place**; the route stays mounted and unsaved work survives. 403 never touches the session. |
| Version skew | `app/versionSkew.ts` + `VersionSkewWatcher` — `__BUILD_ID__` stamped by Vite, chunk-load errors recovered by a **`sessionStorage`-guarded single reload**, prompted (never forced) when work is unsaved. |
| Idempotency | `newIntentKey()` — one key **per user intent, not per attempt**, on creates. The mock handler genuinely dedupes on it, so the property is provable. |
| A11y | Skip link (first focusable), `<main tabIndex={-1}>` focused on route change but **not** on first load, one persistent polite live region announcing each page. |
| Reporting | `lib/reportClientError.ts` — one sink, rate-limited + deduped, fed by boundaries, `window.onerror`, `unhandledrejection`, and React 19's `onCaughtError`/`onRecoverableError`. |
| SEO | Correctly **scoped out**: an authenticated internal tool has no crawlable surface. Placeholder `<title>frontend</title>` fixed, `noindex` meta, `robots.txt` disallowing everything. Reasoning recorded in the files. |

**Files added in Phase 3**

| Path | Purpose |
|---|---|
| `src/components/ErrorBoundaries.tsx` | The three tiers + the shared stale-build fallback. |
| `src/lib/describeError.ts` | **One** place an error becomes user-facing copy. Both the inline region and the notification read from it, so a 503 is worded identically in both. Also owns `isTransient()`. |
| `src/lib/reportClientError.ts` | The single reporting sink. Rate-limited, deduped, drops `AbortError`. |
| `src/lib/globalErrorHandlers.ts` | `window.onerror` + `unhandledrejection` — the two paths boundaries cannot see. |
| `src/notifications/{NotificationsProvider,notificationsContextValue,useNotify}` | The persistent stack. Dedupe, roll-up, cap. |
| `src/auth/{SessionRecoveryProvider,sessionRecoveryContextValue,useSessionRecovery}` | The 401 ladder's rung 3, `useUnsavedWork`, and `SessionReplayBridge`. |
| `src/a11y/{AnnouncerProvider,announcerContextValue,useAnnounce,useRouteFocus}` | Live region + route-change focus. |
| `src/components/SkipToContentLink.tsx` | First focusable element in the DOM. |
| `src/app/queryClient.ts` | Retry policy + the one global raise point for query/mutation failures. |
| `src/app/versionSkew.ts` · `src/app/VersionSkewWatcher.tsx` | Chunk-error detection + the guarded reload; the proactive on-focus check. |
| `src/mocks/faults.ts` | Dev-only fault injection (below). |
| `src/build-env.d.ts` · `vite.config.ts` | `__BUILD_ID__`, and the recorded reasoning for the chunk-size limit. |
| `public/robots.txt` · `index.html` | The SEO subset, with why-the-rest-doesn't-apply written down. |

Modified: `Providers.tsx` (nesting order is load-bearing — see its header comment), `main.tsx`, `DataRegion.tsx`, `PageHeader.tsx`, `AppShell.tsx`, `api/client.ts`, `api/queries.ts`, `lib/format.ts`, `mocks/{db,handlers,browser}.ts`, `CaseEditorPage.tsx`.

**Fault injection — `src/mocks/faults.ts`.** None of the above can be triggered by using the app normally, so the mock API grew a console-driven fault switch: `tcmsFaults.status(503, 30)` · `.hang()` · `.expire()` · `.drift()` · `.only('/cases')` · `.off()`. Dev-only, tree-shaken from production. **Use it before claiming any resilience behaviour still works.**

**Verified in a real browser** (see §6 lesson 5), not just built: 503 → inline failed region + one notification while the search box, filters, suite tree and New case all keep rendering · load-then-fail-refetch → **19 rows kept, marked "Showing data from 11:25 UTC", retry offered, no failed body** · hung endpoint → leaves loading at the deadline · 401 with a half-typed case title → dialog in place, **title still there afterwards**, no redirect · one 503 across three regions → **one** notification reading "Occurred 3 times" · notification still present at **61 s** · retry from the notification → full recovery · route change → focus lands on `<main>`, announced.

### Three defects found in the Phase 3 browser pass

1. **`SessionRecoveryProvider` called `useQueryClient()` while mounted above `QueryClientProvider`** — it has to be above, because the query client's error handlers need `reportUnauthorized` at construction. Threw "No QueryClient set" and the root boundary caught it: **the app rendered nothing but the error page**. Fixed with a registration bridge (`SessionReplayBridge`) mounted below the client.
2. **`reportUnauthorized` closed over `user`**, so the query client captured it at mount when `user` was `null` — the guard would then have swallowed every 401 for the life of the session and the recovery dialog would never have appeared. Now reads a ref.
3. **A drifted response crashed the whole route** rather than the region, because `isEmpty(data)` — a predicate over the response body — was evaluated in `DataRegion` *outside* its own boundary. Moved into a child component.

### Phases 4–5 — next

> **→ superseded for Phase 4: it shipped in §36.** Vitest + RTL are installed and 203 tests are
> green and in CI. The target list below was the right one and was largely followed — read §36 for
> what was covered, what was deliberately left (`CaseSchema`'s refinements, still module-private in
> `CaseEditorPage.tsx`), and the two findings the tests produced. **Phase 5 is still not started.**

**Unblocked.** The §9 corrections landed, so Phase 4 tests will be written against shapes that survive. `lib/permissions.ts` and `lib/results.ts` are the highest-value targets — both pure and both load-bearing.

- **Phase 4:** Vitest + RTL. Nothing is installed yet. Highest-value targets are the logic-bearing units, not the markup: `useCaseFilters` (URL round-tripping), `lib/results` (denominator arithmetic), `lib/format` (`compareReleaseVersions`), `lib/describeError` (the retryable/isAuth matrix), `DataRegion`'s state matrix, `app/versionSkew` (the once-only guard), and the `CaseSchema` cross-field refinements.
- **Phase 5:** hostile self-review — `code-review` + `design/ui-code-audit` + `design/ux-review`, with the "remove one accessory" restraint check.

### Phase 3 items deliberately left open

- **No runtime response validation.** A 200 whose body has drifted is currently caught by the region boundary — the page degrades correctly — but it is *not* categorized, and drift that renders **without throwing** (a renamed optional field silently reading `undefined`) is not caught at all. The real fix is parsing each response at the fetch boundary; that is `data-fetching`'s territory and roughly 40 schemas, so it is its own slice. **This is the largest remaining resilience gap.**
- **`CaseListPage` stays at 527 kB raw / 155 kB gzip**, over the per-route budget. Measured alternative: forcing MUI X DataGrid into its own `manualChunks` entry defeated tree-shaking — the page fell to 72 kB but the grid chunk came out at 728 kB and total JS went from ~909 kB to ~1275 kB, with no cache reuse since `CaseGrid.tsx` is the only consumer. Reverted; `chunkSizeWarningLimit` raised to 600 with the reasoning in `vite.config.ts`. The entry chunk is **124 kB gzip, inside budget**, and the grid is behind a lazy route. The real lever is dropping DataGrid for a plain table — a Phase 5 conversation.
- **Multi-tab session broadcast** (`BroadcastChannel`) — signing out in one tab does not yet update its siblings. **→ scheduled 2026-08-01 (§51): no longer "deliberately left".** Queued behind the M2 execution-writes slice. The smallest of the three; §44 already fixed the sibling tab lying about *who* is signed in, so this is the remaining half.
- **`useUnsavedWork` is wired only to the case editor.** The execution screen and board should register too.
- **No Lighthouse/CWV measurement.** Bundle budgets were checked; LCP/INP/CLS were not measured, and that needs the app served rather than in dev.

### Known gaps deliberately left in Phase 2 scope

- **Evidence upload** is modelled in the contract and rendered where present, but there is no upload control on the execution screen. ~~Needs the signed-URL path, not a form POST.~~ **→ unblocked and scheduled 2026-08-01 (§51).** Open question #28 resolved as **a storage port with a filesystem adapter**, S3 later — which means there is **no signed URL**, so the upload *does* proxy through the API and needs a request-size limit. The blocker as written was the wrong shape: it assumed the signed-URL path was required, when what was actually missing was a decision about where bytes land.
- **Case create/edit does not assign features** — the feature dimension is MULTI + hierarchical and needs its own tree picker; the editor shows existing assignments read-only rather than offering a flat select that could set a parent where a leaf is required. **→ scheduled 2026-08-01 (§51).** The largest of the three scheduled gaps, and the one with a real design question inside it: the picker must make a leaf selectable and a parent not, which is why the flat select was rejected rather than merely unbuilt.
- **Suite create/rename UI** — the handler and mutation hook exist, the tree has no affordance yet.
- **Review/approve actions** are deliberately absent from the editor (status is not an editable dropdown, per §3); the actions themselves are not built.
- **SSE / live updates** (open question #7 resolved as "yes, with live updates") — not wired. The `realtime` skill was not loaded because nothing pushes yet. Snapshot→live handoff is a Phase 3+ task.

### Backend — M0 STARTED (2026-07-30), see §11 for what shipped and what remains

**Done:** Gradle + Spring Boot 3.5 + Java 21 scaffold (rooted in `backend/`, not the repo root), Flyway running V1 against Testcontainers Postgres 17, the `tenancy` module (`CompanyContext` + Hibernate filter + RLS GUC per transaction), the `project` vertical slice, **the schema test**, and **the two-company isolation harness**. 13/13 green at the time, mutation-verified — see §12–§14 for what landed after.

**Still not started** — everything below, plus: the `identity` module, Docker Compose, CI, ArchUnit/Modulith verification, `provision-company`, the third and fourth harness personas, and the async-context test.

- `V2__triggers_and_seed.sql`: `search_vector` generated columns, SINGLE-cardinality classification trigger, team **cycle + company-consistency** trigger, and all seed data — **the revised permission catalogue** (per `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`, which is authoritative), **the six** built-in roles, **per-company** classification dimensions, default case statuses, default configuration per project.
- **The `provision-company` CLI command** — an M0 deliverable, not an M6 one. Creates the company, seeds its vocabularies/statuses/settings, creates the first Company Admin as an invitation, writes an instance audit event. Tested and idempotent.
- **The M0 isolation harness** — the highest-value backend deliverable and easy to postpone by mistake:
  - paired-fixture suite: two companies with deliberately identical-looking data (same project key, same display keys, same team names, same user email in both), every read endpoint exercised as company A asserting no company-B row, name, id or count appears;
  - a **third persona**: a company member with *no project grant*, asserting they see nothing — those rows *are* in the caller's company, so the cross-company assertions structurally cannot catch that leak;
  - a **cross-company user**: Company Admin in A, Test Engineer in B, asserting no permission bleed across a company switch (where a stale cache would show);
  - a **schema test**: every table not on the §0.1 exception list has `company_id NOT NULL` leading its indexes — this is what catches new tables a year from now;
  - an **async-context test**: ingestion, report generation, a scheduled purge and an event handler each run with a foreign company active.
- Gradle multi-module setup, Spring Boot app, Testcontainers.
- **Run V1 against a real Postgres.** It has never executed — see §6.
