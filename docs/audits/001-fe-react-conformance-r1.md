# fe-react Conformance Audit — tcms `frontend/` — Round 1

**Date:** 2026-08-29 · **Branch:** `master` · **Commit:** `7818740c`
**Auditor:** `fe-react-standards-audit` (read-only — no product code was changed)

## 1. Detected lane

| | |
|---|---|
| React | **19** (`react@^19.2.7`, `react-dom@^19.2.7`) |
| Next.js | **n/a** — Vite SPA |
| Render target | **Vite 8 SPA**, React Router 7 (`react-router-dom@^7.18.2`) |
| React Compiler | **off** — no `babel-plugin-react-compiler`, no compiler entry in `vite.config.ts` |
| Component library | **shadcn/ui over Radix primitives** (`components/ui/`), plus a 16-component house kit (`components/kit/`) |
| Styling authority | **Tailwind v4, single authority** — one stylesheet (`src/index.css`), preflight on, no `sx`, no CSS-in-JS, no CSS Modules |
| TypeScript | `typescript@6.0.3` · **strict effectively ON** — see the version-gate note below |
| Package manager | **npm** (one `package-lock.json`) |
| Test runner | **Vitest 4 + RTL**, 146 test files |

**Version-gate note — the top false-positive avoided this round.** Neither `tsconfig.app.json`
nor `tsconfig.node.json` declares `"strict": true`, which reads as a section-S blocker until you
check the compiler. **TypeScript 6.0 changed `strict`'s default to `true`**
(`node_modules/typescript/lib/typescript.js:41431-41442`, `defaultValueDescription: true`)
`#confirmed`. The project is strict. It is recorded below as a **nit** (declare it, don't inherit
it), not as a violation. Likewise: no finding here flags a missing App-Router or RSC API, because
this is a Vite SPA.

Every finding below is judged against this lane.

## 2. Scope & coverage

- **In scope:** `frontend/` — `src/**` (424 `.ts`/`.tsx` files, ~125 800 lines incl. CSS), plus
  `package.json`, `tsconfig*.json`, `vite.config.ts`, `vitest*.config.ts`, `.oxlintrc.json`,
  `index.html`.
- **Excluded:** `node_modules/`, `dist/`, `public/`, and **`src/api/schema.d.ts`** (977 KB,
  generated from `docs/specs/openapi.yaml` by `npm run gen:api` — machine output, not authored
  source). Backend, `packages/`, and `docs/` are out of scope by the user's limiter, and were read
  only where they decide a frontend verdict (`docs/plans/007-render-design-implementation.md`,
  `.github/workflows/ci.yml`).
- **Files in scope:** 424 · **files read this round:** 34 (fully or in cited regions), on top of
  ~40 whole-tree greps.
- **Evidence mode:** **static only.** No build, `tsc`, lint, test run, or live app — the user did
  not opt in. `A1` is therefore Unverifiable rather than green.

## 3. Applicability matrix

| Section | Status | Decided by |
|---|---|---|
| A — Build health & version lane | **Applicable** | always |
| B — RSC & `'use client'` boundary | **N/A** | `ls app src/app pages` → none; `rg "^'use client'" src` → 0 hits. Vite SPA |
| C — Routing & navigation | **Applicable (partial)** | C2/C3/C5/C6 are `[App]` → N/A; C1/C4/C7/C8 apply |
| D — State ownership | **Applicable** | always |
| E — Data access & realtime | **Applicable (partial)** | E8/E9 `[realtime]` → N/A (`rg "new WebSocket\|EventSource\|socket.io" src` → 0); E13/E14 `[App]` → N/A |
| F — Forms & validation | **Applicable** | 27 `<form>`, 18 files using `useForm` |
| G — Component library & styling | **Applicable** | always |
| H — Responsive & motion | **Applicable (partial)** | H4 `[SSR]` → N/A (no SSR) |
| I — Accessibility | **Applicable** | always |
| J — Error resilience | **Applicable (partial)** | J2/J23 `[App]` → N/A |
| K — Performance | **Applicable (partial)** | K5 `[Compiler-on]` → N/A |
| L — SEO & discoverability | **N/A** | `[App]` only, and the product is an authenticated internal tool — `index.html:11` ships `noindex, nofollow` deliberately |
| M — Testability (`data-testid`) | **Applicable** | always |
| N — UI conventions | **Applicable** | always |
| O — Component & pure-logic tests | **Applicable** | Vitest configured; 146 test files |
| P — Localization | **N/A** | `rg "next-intl\|react-i18next\|i18next" package.json` → 0 hits; single-language product |
| Q — PWA / offline | **N/A** | `ls public/manifest* public/sw.js` → none; `rg "serwist\|vite-plugin-pwa\|workbox" package.json` → 0 hits |
| R — Auth wiring | **Applicable (adapted)** | No SDK: `rg "auth0\|next-auth\|cognito\|msal" package.json` → 0 hits. Auth is first-party bearer-token, so R1/R4/R5 are N/A but R2/R3/R6 still bind |
| S — Structure, TS strictness, secrets, comments | **Applicable** | always |

## 4. Conformance summary

| Section | Applicable checks | Conforming | Violated | Unverifiable |
|---|---|---|---|---|
| A — Build health & version lane | 7 | 6 | 0 | 1 |
| C — Routing & navigation | 4 | 2 | 1 | 1 |
| D — State ownership | 8 | 7 | 1 | 0 |
| E — Data access | 10 | 9 | 0 | 1 |
| F — Forms & validation | 6 | 4 | 1 | 1 |
| G — Component library & styling | 9 | 7 | 2 | 0 |
| H — Responsive & motion | 7 | 5 | 0 | 2 |
| I — Accessibility | 9 | 6 | 2 | 1 |
| J — Error resilience | 37 | 30 | 1 | 6 |
| K — Performance | 10 | 6 | 1 | 3 |
| M — Testability | 7 | 4 | 2 | 1 |
| N — UI conventions | 8 | 4 | 1 | 3 |
| O — Component & pure-logic tests | 10 | 4 | 2 | 4 |
| R — Auth wiring | 3 | 2 | 1 | 0 |
| S — Structure, TS, secrets, comments | 12 | 8 | 2 | 2 |
| **Total** | **147** | **104** | **17** | **26** |

**Findings:** 18 total — 0 blocker · **10 major** · 6 minor · 2 nit. (18 findings against 17
violated checks: `S1` is a nit recorded on a check that *conforms*, because strict mode is inherited
rather than declared.)
**Overall conformance:** 104/147 applicable checks (**71 %**); 104/121 of the checks actually
settled this round (**86 %**).

---

## 5. Findings by section

### A — Build health, version lane & migration hygiene

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| A1 | — | Builds and type-checks clean | **Unverifiable this round** — static-only, no opt-in run. `.github/workflows/ci.yml:126-143` runs `build + lint + smoke` for the frontend on every push to `master` `#confirmed`, so a red arrival state would be visible in Actions | Opt in to `npm run build && npm run lint && npm run test:run` on `shanks` if an arrival-state claim is wanted — `project-setup` |

**Rollup — A.** The lane is coherent and current: React 19 throughout with no `forwardRef` anywhere
(`rg "forwardRef" src` → 0 hits) and no down-level idiom left behind, Vite 8, Node pinned by
`.nvmrc` and floored at 22.12 in `engines`, one lockfile, and the frontend gated in CI on every
push. Nothing here is stranded or half-migrated — the MUI→Tailwind port (007 T16) removed the old
dependency set cleanly, and the only residue it left is in *prose*, which is section S's problem
rather than this one. The single open row is arrival state, and it costs one opt-in command.

### C — Routing & navigation

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| C1 | **major** (8 sites) | In-app navigation is a real link | `features/cases/CaseListPage.tsx:103,116,265`, `features/cases/CaseDetailPage.tsx:1240`, `features/docs/DocsPage.tsx:837`, `features/docs/DocPageEditorPage.tsx:303`, `features/runs/ExecutionPage.tsx:1225`, `features/account/AccountSecurityPage.tsx:111` — all `onClick={() => navigate('…')}` on a `<Button>` for ordinary navigation `#confirmed`. The codebase already states the rule against itself at `features/reports/ReportJobCard.tsx:153`: *"The view is a real `<Link>`, not an `onClick` navigation: it earns middle-click, ⌘-click"*, and uses `<RouterLink>`/`<NavLink>` at 92 other call sites (`rg -o "<RouterLink\|<NavLink" src`) `#confirmed` | Convert to `<Button asChild><RouterLink to=…>` — the pattern `ReportJobCard.tsx:165` already demonstrates — `routing` |
| C7 | — | Dynamic segments typed and guarded | **Unverifiable** — not traced this round | Read the `useParams` consumers on `/cases/:caseId`, `/runs/:runId`, `/docs/:pageId` for a missing-record branch — `routing` |

**Rollup — C.** One router tree, one file, code-split at 39 lazy boundaries, and a topology whose
reasoning is written down where it was decided (`app/router.tsx:25-37`). The only routing defect is
a consistency one, and it is unusually cheap to argue: the project has already written the correct
rule into a comment and followed it 92 times, so the eight `navigate()` buttons are drift rather
than a competing convention. Fixing them is mechanical and closes the section.

### D — State ownership

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| D3 | **major** | No set-state-from-props effect | `features/cases/CaseEditorPage.tsx:623-643` seeds the editor's document text from the fetched `existing` case inside a `useEffect`, latched by a `seeded` flag; `:644-647` does the same for create mode `#confirmed`. `app/router.tsx:441-444` mounts `<CaseEditorPage mode="edit" />` on `/projects/:projectId/cases/:caseId/edit` with **no `key`** `#confirmed`, and React Router reuses the component instance across a param change. A client-side move from case A's editor to case B's therefore leaves `seeded === true` and `documentText` holding A's Gherkin, while `textKey` at `:602` flips to B — so the debounced buffer write at `:607-610` **persists case A's text under case B's local-draft key** | `key={caseId}` on the route element (or on the editor), then take the seed from a `useState` initializer / render-time derivation instead of the effect+flag — `state-management` |

**Rollup — D.** State ownership is the strongest section in the audit: no client store exists at all
(`rg "zustand\|redux\|createSlice" src` → 0 hits), so the "server data in a client store" failure is
structurally impossible; no context provider allocates its value inline (`rg "Provider value={{" src`
→ 0 hits); every module-level `let` is a genuine singleton latch (rate-limit window, install-once
flag, `BroadcastChannel` handle) rather than user data. The one violation is a real data-crossing
bug rather than a style call, and its fix is a single `key` prop — which is also the fix React's own
docs prescribe for exactly this shape.

### E — Data access & realtime transport

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| E10 | — | Responses cannot land out of order | **Unverifiable** — `signal` is threaded through every `queryFn` in all 18 domain modules (`rg -c signal src/api/queries/*.ts`) `#confirmed`, but I did not compare all ~221 `queryKey`s against their `queryFn` arguments | Script the comparison — derive every key's parameter set from `keys.ts` and diff it against the args each `queryFn` closes over — `data-fetching` |

**Rollup — E.** One lane, enforced: 221 `useQuery` / 129 `useMutation` and exactly **one** raw
`fetch` in the whole tree (`app/VersionSkewWatcher.tsx:35`, polling `/index.html` for a deploy —
correctly outside the query layer). All HTTP goes through `api/client.ts`; there are zero empty
catches (`rg "catch\s*\{\s*\}" src` → 0 hits outside tests); 51 `enabled:` gates sit on the
dependent queries; `Promise.all` appears four times and every member is co-critical. This section
needs no work — the one open row is a verification gap on my side, not a suspected defect.

### F — Forms & validation

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| F3 | **minor** (shared primitive) | Field errors are associated with fields | `components/kit/Field.tsx:36-45` renders the error into a `<p role="alert">` carrying **no `id`**, and the control it labels gets no `aria-describedby` `#confirmed`. Across the app there are 74 `aria-invalid` attributes but only **5** `aria-describedby`, all five in the two auth pages that hand-roll their own ids (`features/auth/LoginPage.tsx:317,350`, `features/auth/AcceptInvitationPage.tsx:308,322,357`) `#confirmed`. `role="alert"` announces the message when it appears, so nothing is silent — but a user who tabs back to the field afterwards is told the field is invalid and never told why | Give `Field` a derived `${htmlFor}-message` id on the `<p>` and pass `aria-describedby` down to the control (a render-prop or `cloneElement`); the auth pages then collapse onto it — `forms` |
| F6 | — | Controlled/uncontrolled is explicit | **Unverifiable** — not traced across all 27 forms | Read each `<form>`'s inputs for a `value` without `onChange`, or both `value` and `defaultValue` — `forms` |

**Rollup — F.** React Hook Form + Zod is the standard and is genuinely applied (18 files, 121
`setError` calls mapping server refusals back onto fields), and the "labels above, one column,
message under the field it belongs to" rule is carried by a shared primitive rather than by
discipline. The single gap is that the primitive stops one attribute short of the association it
was built to provide — which makes it the cheapest fix in the report, since one component closes
every form in the app at once.

### G — Component library & styling authority

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| **G7** | **major** (277 sites / 86 files) | Dark mode is an authored second palette | The project authors **both** palettes — a light one on bare `:root` (`index.css:88-120`: `--color-surface: #ffffff`, `--color-text: #1b2a41`) and a dark one under `[data-color-scheme='dark']` (`index.css:204-268`) `#confirmed` — and light is the **default**: `app/colorScheme.tsx:66-69` resolves to `'light'` whenever `matchMedia` is absent, and `:root` is what applies when the attribute says `light`. But the T16 port wrote **dark-ground alpha literals** into the class lists instead of tokens: **191** `border-white/N`, **64** `bg-white/N`, **22** `hover:bg-white/N` across **86 files** (`rg -o "border-white/[0-9]+\|bg-white/[0-9]+\|hover:bg-white/[0-9]+" src --glob '!*.test.*'`) `#confirmed`. On the white `--color-surface` those borders are white-on-white — invisible structure — and the hover states give no feedback. The navigation rail is worse: `app/AppShell.tsx:542` hardcodes `bg-[#0c1119]` while its contents use `text-ink`, which in light mode is `#1b2a41` — dark navy on near-black `#confirmed`. Nothing checks this: `app/tokenSystem.test.ts` verifies that every bridged Tailwind name resolves to a declared custom property, not that components *use* them. **Context, not excuse:** `docs/plans/007-render-design-implementation.md:119-124` (D1) decides dark is authoritative, light is "derived and kept", and *"if light drifts from the renders, that is accepted and recorded"* — but D1 also states *"the app ships a working theme toggle today"*, and invisible borders are not drift in hue, they are structure that stops rendering. See **Q-1** | Replace the alpha literals with the existing tokens (`border-border`, `bg-surface-raised`, `hover:bg-surface-sunken`) — 916 correct token uses already exist to pattern-match against — and extend `tokenSystem.test.ts` with a grep gate that fails on `-white/` in `src/**`. Or, if Q-1 answers "dark-only", delete the light palette and the toggle — `styling` |
| G4 | **minor** (3 sites) | No colour literals in components | `app/AppShell.tsx:542` `bg-[#0c1119]`; `components/ui/sonner.tsx:12` and `components/ui/checkbox.tsx:23` both `text-[#08121a]` — the "ink on accent" colour, which `--color-on-action` already names (`index.css:104`, `:212`) `#confirmed`. Excluded as legitimate: `ClassificationValueDialog.tsx:129` (a placeholder *string*) and `ThresholdLayout.tsx:18` (prose) | Point the three at `--color-on-action` / a new `--color-rail` — `design-tokens` |

**Rollup — G.** Structurally this section is exemplary, and 007 T16 is why: one styling authority
with preflight on, one stylesheet whose non-token content is four global rules
(`index.css:278-353`), zero `sx`/`styled`/CSS-Modules anywhere, one component library, functional
tokens named by purpose, and a dark palette that is genuinely authored per-pair rather than
inverted. The failure is not architectural — it is that the *port* reached for `white/8` where the
architecture it was building provides `border-border`, 277 times, and the token test that exists
checks the wrong end of the pipe. Both findings are the same defect at different scales, and both
close with one sweep plus one gate. The prior question is Q-1, because the answer decides whether
the sweep is a fix or a deletion.

### H — Responsive & motion

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| H5 | — | No horizontal overflow / fixed-dimension regions | **Unverifiable** statically | A viewport pass at 360 px, or an opt-in run — `responsive-design` |
| H6 | — | Visual reorder never breaks focus order | **Unverifiable** statically | Tab-order pass on the reordered regions — `accessibility` |

**Rollup — H.** One breakpoint set, stated once and cross-checked: `index.css:189-193` declares
`--bp-*`, `:67-70` declares Tailwind's `--breakpoint-*` at the same values, and `app/breakpoints.ts`
mirrors them for the QA suite — with `app/tokenSystem.test.ts:179` pinning `md: 900` so a stray
Tailwind `md:` cannot mean something else. Motion has a hard floor: the global
`@media (prefers-reduced-motion: reduce)` block at `index.css:344-353` covers every animation and
transition in the app, and the handful of components that animate also opt out locally
(`motion-reduce:transition-none`). No JS animation library is installed. Nothing to fix; the two
open rows need eyes on a viewport, not more reading.

### I — Accessibility

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| **I4** | **major** (5 sites) | Composite widgets carry roles *and* keyboard behaviour | Five hand-rolled listbox/menu popups implement the roles but not the pattern: `components/kit/Combobox.tsx:105-131`, `components/kit/MultiPicker.tsx:152-188`, `features/review/ReviewPanel.tsx:491-510`, `features/docs/LinkBlockDialog.tsx:238,304`, `features/cases/CaseDetailPage.tsx:706-714` `#confirmed`. None implements ArrowDown/ArrowUp traversal or `aria-activedescendant`; the options are reachable only by Tab, one stop at a time, through a list that can hold every case in a project | Route these through the installed `@radix-ui/react-popover` + a roving-focus list, or add arrow-key handling and `aria-activedescendant` to `kit/Combobox` and collapse the private copies onto it (the code already asks for this at `Combobox.tsx:29-32`) — `accessibility` |
| **I5** | **major** (5 sites) | Focus managed on popups | In four of those five the `Escape` handler is bound to the **popup element, which never receives focus**: `Combobox.tsx:110-113`, `ReviewPanel.tsx:496-498`, `LinkBlockDialog.tsx:242-244` and `:308-310`, `CaseDetailPage.tsx:710-712` all attach `onKeyDown` to the `role="listbox"`/`role="menu"` `<div>`, which is a **sibling** of the focused trigger inside a shared `<div className="relative">` — so the keydown bubbles to the wrapper, never through the handler `#confirmed`. Nothing moves focus into the popup on open, so pressing Escape after opening does nothing and the only exit is Tab-through or a mouse click on the scrim. `MultiPicker.tsx:143` gets it right by binding Escape to the **input** — the same fix, already present in the same directory. Compounding it, `CaseDetailPage.tsx:706-710` carries a comment asserting the opposite (see **S12**) | Move the `Escape` handler onto the trigger/input (MultiPicker's shape), or move focus into the popup on open and restore it on close — `accessibility` |
| I6 | — | Colour is not the only state signifier | **Unverifiable** — `lib/statusColors.ts` and the `Badge`/`Chip`/`StatusChip` set were not read for a non-colour signifier | Read `components/StatusChip.tsx` and `kit/Badge.tsx` for an icon/text pair alongside the hue — `accessibility` |

**Rollup — I.** The foundations are unusually deliberate: a skip link, a `<main>` that is a
programmatic focus target with the reason for `tabIndex={-1}` written beside it
(`AppShell.tsx:571-576`), a route-change announcer (`a11y/`), one `.sr-only` rule, icon buttons with
`aria-label`, and a kit test that pins "a badge is not operable, a chip is a real button"
(`kit/kitRules.test.tsx:20-38`). The `<div onClick>` hits are all `aria-hidden` dismissal scrims,
which is correct. Both findings are the same story: six popups were hand-rolled during the T16 port
because Radix's `Select` calls `hasPointerCapture` and jsdom does not implement it — a *test-harness*
constraint that has now shaped a *keyboard* behaviour. That trade is defensible and recorded
(`test/setup.ts:48-52`); shipping a dead Escape handler is not. One fix to `kit/Combobox` plus
collapsing the two acknowledged private copies closes most of both rows.

### J — Error resilience

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| J35a | **minor** (4 modules) | Storage access is guarded *and degrades* | `lib/safeStorage.ts` exists precisely to fall back to an in-memory `Map` when storage throws, and 6 modules use it. Four others hand-roll their own `try/catch` and **swallow instead of degrading**: `features/cases/caseTextBuffer.ts:65-72`, `features/cases/useNewCaseDraft.ts:73-96`, `features/auth/ssoReturnPath.ts:24-40`, `app/versionSkew.ts:47-77` `#confirmed`. Nothing crashes — each catch is deliberate and carries its reason — but in Safari private mode the case editor's crash buffer silently never persists, where `safeStorage` would have kept it for the session. `versionSkew.ts:47-55` is the justified exception and says so ("without a guard we cannot promise *at most once*, so we must not reload at all") | Route the two `features/cases/` buffers through `safeStorage.getJSON`/`setJSON`; leave `versionSkew` and `ssoReturnPath` (both want failure, not fallback) and record why — `state-management` |
| J17 | — | Actions unsafe on stale data are disabled while stale | **Unverifiable** — `DataRegion` renders the stale marker; I did not check whether value-bearing regions also gate their write action on it | Read the stale branch of `PlanStatisticsPage` / `ExecutionPage` for a disabled write — `error-handling` |
| J31 | — | Bulk writes report per item | **Unverifiable** — bulk mutation handlers not traced | Read the batch handlers in `api/queries/cases.ts` (bulk tag/status) — `error-handling` |
| J33 | — | Notification volume is capped and rolled up | **Unverifiable** — `NotificationsProvider.tsx` not read for a stack cap | Read `notifications/NotificationsProvider.tsx` for a cap/grouping rule — `error-handling` |
| J34 | — | Third-party failures contained | **Unverifiable** — no `<Script>`/`<iframe>`/flag provider found (`rg "next/script\|<iframe\|useFlag\|LaunchDarkly\|OpenFeature" src` → 0 hits), so this may be vacuous | Confirm there is no embed surface at all; if so it closes as N/A — `error-handling` |
| J36 | — | Sessions synced across tabs | **Likely conforming, not fully traced.** `auth/sessionChannel.ts` implements a `BroadcastChannel` and `SessionRecoveryProvider.tsx:312-333` clears the query cache on a cross-tab identity change `#confirmed`; I did not verify the sign-out path end to end | Read `auth/multiTabSession.test.tsx` (it exists, 14 KB) — `error-handling` |
| J37 | — | A missing record reads as "gone", not "broken" | **Unverifiable** — 404 handling on detail routes not traced | Read a detail page's error branch for a 404-specific state — `error-handling` |

**Rollup — J.** This is the section the codebase was clearly built around, and it holds up under a
hostile read. Every request carries a composed timeout that preserves the caller's cancel signal
(`api/client.ts:189-190`, `AbortSignal.any([external, deadline])` — the J13 trap, avoided).
`components/DataRegion.tsx` is a real five-state region: it distinguishes pending from failed from
empty, treats `AbortError` as not-a-failure (`:66`), and **keeps data on screen through a failed
refetch with a timestamped stale marker** (`:125`, `:138-145`) — so J1, J8 and J15 all pass, and
`rg "if (isError) return"` finds **zero** whole-page bailouts. The 401 ladder re-authenticates in
place behind a non-dismissible dialog rather than redirecting over unsaved work
(`SessionRecoveryProvider.tsx:20-47`, `:175-195`). Deploy skew is detected by build id and the
reload is guarded once-only, with the guard failing *closed* (`app/versionSkew.ts:47-55`).
`Retry-After` is parsed in both its legal forms (`client.ts:148-156`). `Idempotency-Key` is present
on the writes that need it and its *absence* is argued where it is absent (`cases.ts:395-402`,
`integration.ts:125`). One minor finding, and six rows I simply did not reach — which is where a
second round should start, not with a suspicion.

### K — Performance

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| K1 | **major** (1 real site of 14) | Keys are stable domain ids | `features/plans/PlanFieldsTab.tsx:205-211` keys an **editable, mutable** list of exit criteria by array index while the row is edited through `update(index, …)` — removing a middle row makes React reuse the removed row's DOM and input state for its successor `#confirmed`. The other 13 `key={index}` hits are legitimate and were read: skeleton placeholders (`DataRegion.tsx:238,250`, `kit/StateBlock.tsx:121`, `kit/Figure.tsx:109`), and read-only renders of parsed text with no identity and no reordering (`ProposalPage.tsx:372`, `PasteGherkinDialog.tsx:180,203`, `CaseDocument.tsx:532`, `CaseDetailPage.tsx:1093-1095`, `DocBlockBody.tsx:187`, `help/prose.tsx:47,110`) | Give each criterion row a client-side id at creation and key on it — `performance` |
| K4 | — | Large lists virtualized | **Unverifiable** — row counts are a runtime property | A production row-count figure for the case grid, or an opt-in run — `performance` |
| K9 | — | Images optimized | **Unverifiable** — not swept | `rg "<img" src` and read for `loading`/dimensions — `performance` |
| K10 | — | No render-time expensive work | **Unverifiable** — hot component bodies not profiled | Read `CaseListPage` / `OrgChartMatrix` render bodies, or opt in to a profile — `performance` |

**Rollup — K.** Code splitting is deliberate and measured: 39 `lazy()` route boundaries, and
`vite.config.ts` records an experiment where forcing a manual chunk made total JS ~40 % *worse* —
the discipline the plugin asks for, kept in the file where the setting lives. No component is
declared inside another (`rg` for the shape → 0 hits), no hand-rolled memo bloat, no derived state
mirrored into a second `useState`. One genuine key defect among fourteen leads, which is a good
signal-to-noise ratio for this check. The three open rows all want a runtime, not a reader.

### M — Testability (`data-testid`)

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| **M4** | **major** (5 sites) | Row locators are static | Database UUIDs are interpolated into testids at `features/settings/ClassificationSettingsPage.tsx:294,317,329,340` (`Classification.Value.${value.id}`) and `features/board/TaskCreateDialog.tsx:321` (`${TestId}.Assignee.${person.id}`) `#confirmed` — a locator that changes with every seed, environment and re-provision, which is exactly the E2E flake M4 exists to prevent. Every other composed testid in the tree keys on a stable slug (`${TestId}.Format.${value}`, `Classification.${dimension.key}`, `Nav.Link.${label}`) and is correct | Key on the value's stable `key`/slug, or on its position within a named parent region — `test-ids` |
| M3 | **minor** | No locator collisions | `Cases.SuitePanel` names two different components: `features/cases/CaseListPage.tsx:131` (the suite **tree sidebar** on the list page) and `features/cases/SuitePanel.tsx:47` (the suite **detail header panel**) `#confirmed`. They do not co-render, so this is not a runtime ambiguity — but one locator meaning two screens is a trap for whoever writes the E2E. The other three duplicates are fine and were read: `CaseDetail.EditButton` and `PlanStats.SaveReport` are mutually exclusive branches of one control; `Nav.ProjectSwitcher` is the collapsed/expanded pair of one switcher | Rename the sidebar to `Cases.SuiteTree` — `test-ids` |
| M7 | — | No wrapper components exist only to carry testids | **Unverifiable** — thin wrappers not audited | Read `components/kit/` for a component whose only addition is a testid — `test-ids` |

**Rollup — M.** Coverage and naming are genuinely excellent — 676 literal plus 794 composed
testids, and **every single one** is PascalCase dot-separated (`rg -o 'data-testid="[^"]+"' | rg -v "^[A-Z]"`
→ 0 hits), which is rarer than it sounds. The section's two failures are narrow and mechanical, and
they share a root: five composed ids reached for an identity that is a *record's* rather than the
*UI's*. Both are a rename away, and both should go to `qa-ui-typescript` as the
`missing-testids.md` handshake input.

### N — UI conventions

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| N4 | **nit** (10 sites) | Links open same-tab by default; `_blank` carries `rel` | All ten `target="_blank"` sites are external issue-tracker / artifact URLs and all carry a `rel` — but in four different spellings: `noreferrer noopener` (`IssueChip.tsx:93,143`), `noopener` (`PlanStatisticsPage.tsx:1378`, `RunDetailPage.tsx:622`, `CaseHistoryPage.tsx:388`, `ArtifactTable.tsx:89`, `DefectPanel.tsx:256`), `noreferrer` (`EvidencePanel.tsx:173`, `StepEvidence.tsx:110`), `noopener noreferrer` (`CaseDetailPage.tsx:1388`) `#confirmed`. Every spelling is safe; none carries a "opens in a new tab" cue for a screen-reader user | Settle on `rel="noopener noreferrer"` and add a visually-hidden "(opens in a new tab)" to the shared external-link affordance — `ui-rules` |
| N2 | — | Deterministic sort with a tiebreaker | **Unverifiable** — ordering is server-side; not traced | Read the list endpoints' `sort` parameters against the backend's ordering — `ui-rules` |
| N5 | — | Surface chosen deliberately | **Unverifiable** — modal contents not audited for trapped multi-step flows | Read the larger dialogs (`GenerateReportDialog`, `TaskCreateDialog`) — `design-ux-patterns` |
| N8 | — | Copy is consistent and active-voice | **Unverifiable** — a design-altitude question | `design-ux-writing` / `design-auditor` |

**Rollup — N.** The conventions this section protects are already carried by shared modules rather
than by memory: `lib/labels.ts` humanizes every enum behind `satisfies Record<Union, string>` so a
new member is a *compile error* until it is labeled, `lib/format.ts` owns dates and numbers,
`components/Identifier.tsx` owns anything monospace, `DataRegion` owns the empty state, and
destructive actions go through `UnlinkDialog`/`DeleteTeamDialog` naming their target — there is no
`window.confirm` in the tree. The only finding is cosmetic. The three open rows are design-altitude
questions that belong to `design-auditor`, not here.

### O — Component & pure-logic tests

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| **O8** | **major** (9 modules) | Non-rendering logic has pure-logic tests | Nine `src/lib/` modules ship with no `*.test.ts` beside them (`for f in src/lib/*.ts; do [ -f "${f%.ts}.test.ts" ] \|\| echo $f; done`) `#confirmed`: `safeStorage.ts`, `statusColors.ts`, `caseDefaults.ts`, `download.ts`, `reportClientError.ts`, `globalErrorHandlers.ts`, `labels.ts`, `cn.ts`, `devFaults.ts`. The one that matters most is **`safeStorage.ts`** — its entire purpose is behaviour *when `localStorage` throws* (quota, Safari private mode, blocked third-party context), the fallback-to-`Map` and warn-once paths are branches nothing exercises, and six modules including `api/client.ts` and `auth/AuthContext.tsx` depend on it. `statusColors.ts`, `caseDefaults.ts` and `download.ts` carry real branching too. Fairly excluded: `cn.ts` (a two-line `clsx`+`twMerge` compose), `devFaults.ts` (dev-only, dropped from production builds), and `labels.ts`, whose completeness is already a *compile-time* guarantee via `satisfies Record<Union, string>` | Add `safeStorage.test.ts` first (a throwing storage stub, three branches), then `statusColors` / `caseDefaults` / `download` — `component-testing` |
| O4 | **minor** (12:1) | Queries prefer role/label over testid | Component tests use `getByTestId` **1040** times against `getByRole` **85** (`rg -o "getByTestId\|getByRole" src --glob '*.test.tsx'`) `#confirmed`. `data-testid` is the QA/E2E handshake this repo runs on (section M), which makes it the right locator *there* — but inside RTL it decouples the unit test from the accessibility tree, so the dead-`Escape`/keyboard defects in section I are exactly the class of bug this suite cannot see. Some tests do it right (`kit/kitRules.test.tsx:20-38` queries by role and name throughout). See **Q-2** | Prefer `getByRole(..., { name })` for interactive elements in new tests and when touching old ones; keep testids for container/region anchors — `component-testing` |
| O6 | — | No conditional logic driving assertions | **Unverifiable** — not swept | `rg "\bif\s*\(" --glob '*.test.{ts,tsx}' src` and read the hits — `component-testing` |
| O9 | — | Pure-logic tests don't render | **Unverifiable** — not swept | `rg "render\(" --glob '*.test.ts' src` — `component-testing` |
| O10 | — | Schema assertions use path/code, not message | **Unverifiable** — not swept | Read the Zod-schema tests' assertions — `component-testing` |
| O11 | — | Browser seams injected, not global | **Unverifiable** — not cross-referenced | Compare `Date.now()`/`window.` readers against which units are tested — `component-testing` |

**Rollup — O.** 146 test files with an unusually strict harness behind them: every mocked exchange
is validated against `openapi.yaml`, a render that trips an error boundary **fails** unless the test
declares it meant to (`test/renderFailures.ts`), a shared `createTestQueryClient` is mandatory, and
there are zero snapshot-only tests and effectively no timer-driven waits. The gap is not depth, it
is *shape*: the suite is overwhelmingly component-level and testid-driven, so pure-logic seams go
uncovered and accessibility-tree regressions are invisible to it. Those two facts are why sections I
and O share a cause — and why `safeStorage.test.ts` is worth more than its size suggests.

### R — Auth wiring *(adapted — no SDK installed)*

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| R2 | **major** *(demoted from the checklist's blocker — see note)* | Tokens never in `localStorage` | `auth/AuthContext.tsx:157` writes the session token with `safeStorage.set(TOKEN_KEY, …)`, which is `localStorage` (`lib/safeStorage.ts:29-31`), and `api/client.ts:198` reads it back for every request `#confirmed`. Any XSS in the SPA reads a full session token. **Why major, not blocker:** the checklist's blocker assumes an SDK lane offering in-memory storage plus refresh-token rotation. None exists here — there is no refresh token at all, and `auth/SessionRecoveryProvider.tsx:38` records the ladder as deferred (*"When M4 lands, rungs 1–2 slot in above this"*). So this is a known architectural position with a named successor, not an oversight; it is a real exposure that no fix available today closes cleanly. See **Q-3** | Move the access token to memory and put the durable half in an `HttpOnly; SameSite` cookie when the M4 refresh ladder lands; until then this belongs to `qa-security` to price rather than to `fe-react` to patch — `auth/auth0-concepts` |

**Rollup — R.** Authorization itself is done right: the UI checks *permissions* and never role names
(`lib/permissions.ts`, `auth/useCan.ts`), the backend enforces independently through RLS and project
grants so no client gate is load-bearing, and `useCan.ts:28,93` records the deliberate
"enable-and-let-it-403 beats hide" reasoning. Logout is thorough — `SessionRecoveryProvider.tsx:304-311`
clears the whole query cache on an identity change and `:312-333` does the same across tabs over a
`BroadcastChannel`, closing the "previous company's project still on screen" hole its own comment
describes. The single finding is the token's resting place, and it is a roadmap item with an owner,
not drift. It should be answered (Q-3) rather than quietly fixed.

### S — Project structure, TS strictness, secrets & comments

| ID | Sev | Check | Evidence | Fix direction (owner) |
|---|---|---|---|---|
| **S12** | **major** (5 sites) | Surviving comments are true and load-bearing | Five comments make **present-tense** claims about MUI, which 007 T16 removed — the comment is the defect, and deletion is the fix. `auth/SessionRecoveryProvider.tsx:174-177` — *"MUI's Dialog is used rather than a hand-rolled overlay"* — sits directly above a Radix `<Dialog>` (`:190`), and the *next* comment block (`:184-189`) already describes that Radix dialog correctly, so the file contradicts itself in eight lines `#confirmed`. `app/router.tsx:35-37` — *"each pull in a sizable chunk of MUI X … should not pay for the DataGrid on first load"* — is the justification for 39 `lazy()` boundaries and is now false `#confirmed`. `vite.config.ts:80-96` justifies `chunkSizeWarningLimit: 600` entirely with measured DataGrid figures (528 kB, 728 kB, 1275 kB) that no longer describe any build `#confirmed`. `vitest.config.ts:31-33` and `:65` justify `environment: 'jsdom'` and the timer strategy with *"`DataRegion`, `PermissionGate` and the suite tree render real MUI"* `#confirmed`. `app/breakpoints.ts:2-6` instructs the reader that *"changing a value here means changing it in the MUI theme too"* — there is no theme `#confirmed`. **Contrast:** `test/setup.ts:38-46`, `index.css:4-10`, `index.css:81-84`, `lib/labels.ts:1070`, `notifications/toast.ts:9` all handle the same transition correctly — struck through with `→ superseded`, then re-stated — which is this repo's own documented convention and shows the five above are misses, not a different style | Delete or re-derive each; `vite.config.ts`'s limit needs a *current* measurement or it should return to the default — `code-review` / `ep-code-comments` |
| S7 | **minor** | Lint **and format** configured and enforced | `.oxlintrc.json` is real and enforced (`npm run lint` at 0 warnings, gated in `ci.yml:126`) `#confirmed`, but **no formatter is configured**: `ls .prettierrc* .editorconfig` → none, and `package.json` has no `format` script `#confirmed`. The drift is visible — `features/plans/PlanFieldsTab.tsx:199-203` has a `<Banner>` at column 0 inside an indented `<div>` | Add Prettier (or oxlint's formatter) and a `format:check` step beside `lint` in CI — `project-setup` |
| S1 | **nit** | `strict: true` in `tsconfig.json` | Strict is **on**, but only by inheritance: `tsconfig.app.json` and `tsconfig.node.json` both omit `"strict"`, and TS 6.0 defaults it to `true` (`node_modules/typescript/lib/typescript.js:41431-41442`) `#confirmed`. Sibling projects declare it explicitly (`packages/{client,cli,mcp}/tsconfig.json`) `#confirmed`, so the frontend is the odd one out and a TS downgrade would silently relax it | Add `"strict": true` beside the other `/* Linting */` flags in both configs — `project-setup` |
| S4 | — | Variant props are discriminated unions | **Unverifiable** — `rg "variant\??:\s*string"` → 0 hits, but the kit's variant props were not individually read | Read `kit/Badge`, `kit/Chip`, `kit/Banner` prop types — `component-writer` |
| S10 | — | No inline comments inside bodies / JSX | **Unverifiable, and deliberately not guessed.** This codebase comments at a *far* higher density than the plugin's default, and does so as a documented house style — the comments carry decisions and their reversals rather than restating code. Separating a genuine S10 violation from that convention needs a read of every hit, which I did not do | Read the `rg -n "^\s+//\|\{/\*" src` hits that sit inside component bodies, judging each against `ep-code-comments`; expect most to be legitimate here — `ep-code-comments` |

**Rollup — S.** Hygiene is strong on every mechanical axis: no `any` in product code (all 45 hits are
prose or narrow `as unknown as` window casts), exactly one lint suppression and it carries a
four-line justification (`features/docs/LinkBlockDialog.tsx:148-153`), no `console.log`, no committed
secrets, no raw `process.env`, one lockfile, and a clean feature-folder layout. The real finding is
S12, and it is worth more than its severity suggests: this project's comments are load-bearing —
they are where decisions and their reversals live, and CLAUDE.md makes writing the reversal an
explicit rule — so a comment that still argues from a deleted dependency does active harm. Four of
the five are *justifications for settings that are still in force*, which means the next person to
touch `chunkSizeWarningLimit`, the `lazy()` boundaries, or the jsdom environment will reason from a
premise that no longer exists.

---

## 6. Defect families

| Family | Head | Members | Sites | One fix closes |
|---|---|---|---|---|
| **The T16 dark-ground port** — literals written where the token system it was building provides names | **G7** | G4 | 280 | Light mode becomes usable; the palette swap on `[data-color-scheme]` reaches every surface; one grep gate prevents recurrence |
| **The T16 prose residue** — comments still arguing from the removed dependency | **S12** | — | 5 | Five settings regain a true justification |
| **The hand-rolled popup** — six listbox/menu widgets written around a jsdom constraint | **I4** | I5, and the two private copies the code itself flags (`Combobox.tsx:29-32`, `kit/Field.tsx:16-18`) | 5 | Escape works, arrow keys work, two duplicate implementations disappear |
| **Testid identity drift** — a record's id used where the UI's is wanted | **M4** | M3 | 6 | E2E locators stop depending on the seed |

## 7. Priority — leverage-ordered

| # | Item | Sev | Why first | Effort |
|---|---|---|---|---|
| 1 | **Q-1**, then **G7** (family head) | major | Everything else in G waits on one answer. If light ships, one sweep over 86 files closes 280 sites and makes a whole palette usable; if it doesn't, deleting the light palette and the toggle closes them by removal. Either way the *next* action is a `tokenSystem.test.ts` gate on `-white/`, which is what stops round 2 finding the same thing | M–L |
| 2 | **D3** | major | A `key={caseId}` on one route element closes a bug that writes one case's authoring text into another case's local buffer. Smallest change with the highest correctness payoff in the report | S |
| 3 | **I4 + I5** (family head) | major | One fix to `kit/Combobox` — Escape onto the input, arrow-key traversal, `aria-activedescendant` — then collapse the two private copies the code already asks to collapse. Closes 5 sites and removes 2 duplicate implementations | M |
| 4 | **S12** | major | Deletions, no risk, and it stops four still-live settings being defended by reasoning about a deleted dependency. Cheapest major in the list | S |
| 5 | **R2** → **Q-3** | major | Not a patch — a decision to price. Hand to `qa-security` with the M4 refresh ladder in view rather than attempting a partial fix | — |
| 6 | **M4**, **K1** | major | Both are one-line identity swaps (record id → stable slug; array index → row id) that remove a flake class and an input-state-carry-over bug | S |
| 7 | **O8** (`safeStorage.test.ts` first) | major | The one untested module whose entire contract is behaviour under a throw, and six modules depend on it | S |
| 8 | **C1** | major | Mechanical: 8 buttons to `<Button asChild><RouterLink>`, following the pattern the repo already documents at `ReportJobCard.tsx:165` | S |
| 9 | **F3** | minor | One shared primitive (`kit/Field`) closes every form in the app | S |
| 10 | **G4, M3, O4, S7, S1, J35a, N4** | minor/nit | Cleanups; take them when the file is open for one of the above | S |

## 8. Noted deviations (not findings)

| # | Deviation | Where | Question for the owner |
|---|---|---|---|
| D-1 | Vite SPA + React Router 7 rather than the plugin's Next App Router default | app-wide `#confirmed` | None — a deliberate, coherent choice. Recorded so a later round doesn't flag missing RSC/App-Router APIs. Sections B, L, P, Q are N/A for this reason |
| D-2 | `oxlint` rather than ESLint | `.oxlintrc.json`, `ci.yml:126` `#confirmed` | None — enforced at 0 warnings, which is what S7 asks for. Only the *formatter* half is missing (S7) |
| D-3 | 007 D1: dark is the authored palette, light is derived, drift is accepted | `docs/plans/007-render-design-implementation.md:119-124` `#confirmed` | Recorded so G7 is read against it. G7 argues the outcome exceeded what D1 scoped — see Q-1 |
| D-4 | `@radix-ui/react-select` deliberately avoided in three places; comboboxes hand-rolled instead | `test/setup.ts:48-52`, `kit/Combobox.tsx:9-16`, `ReviewPanel`, `LinkBlockDialog` `#confirmed` | The reason (jsdom lacks `hasPointerCapture`, and the harness must not be bent to suit the app) is sound and consistently applied. I4/I5 are about what the hand-rolled replacements then *omitted*, not about the decision |
| D-5 | Comment density far above the plugin's default, with superseded decisions kept and struck through | app-wide; the rule is in `CLAUDE.md` `#confirmed` | None — it is a house convention with a stated rationale, which is why S10 is left Unverifiable rather than guessed at, and why S12 (comments that are *false*, not merely plentiful) is the finding that matters |

## 9. Open questions

| # | Question | Blocks | Needs |
|---|---|---|---|
| Q-1 | **Is light mode a supported ship target, or has the product become dark-only in practice?** 007 D1 keeps light and the toggle; 280 class literals assume a dark ground. Both cannot be true | G7, G4 — and whether the fix is a token sweep or deleting the light palette + toggle | Owner's answer |
| Q-2 | Is `getByTestId`-first inside RTL a deliberate house convention, or drift from the E2E handshake? | O4's severity, and whether new tests should query by role | Owner's answer |
| Q-3 | Is a bearer token in `localStorage` the accepted position until the M4 refresh ladder lands, and is that trade written down anywhere outside a code comment? | R2 — whether it is a finding to fix or a risk to record | Owner's answer + `qa-security` |
| Q-4 | Should `vite.config.ts`'s `chunkSizeWarningLimit: 600` stay, now that the DataGrid it was raised for is gone? | Part of S12 — the comment is false either way, but the *setting* needs a current measurement | A post-T16 `npm run build` size report |

## 10. Unverifiable

| ID | Check | Why | What would settle it |
|---|---|---|---|
| A1 | Build / typecheck / lint green | Static-only; no opt-in run | `~/bin/tcms-remote frontend npm run build && npm run lint && npm run test:run` |
| C7, F6, I6, N2, N5, N8, S4, S10 | Per-site reads not performed | Breadth traded for depth elsewhere | Targeted reads named in each row |
| E10 | Query key ⊇ query args | ~221 keys, not exhaustively diffed | A script comparing `keys.ts` entries against each `queryFn`'s closed-over args |
| J17, J31, J33, J34, J36, J37 | Per-feature resilience rows | The shared seams (`client.ts`, `DataRegion`, `queryClient`, `SessionRecoveryProvider`) were read and conform; the per-feature handlers were not | Reads named in each row — start here in round 2 |
| K4, K9, K10 | Virtualization, images, render cost | Runtime properties | A production row-count figure, or an opt-in profile/build |
| O6, O9, O10, O11 | Test-shape rows | Not swept | The greps named in each row |

## 11. Handoff

- **Fixes:** `fe-react` agent, Mode FIX. Work §7 top-down — but **answer Q-1 before touching G7**,
  because the answer decides whether the fix is a sweep or a deletion.
- **Testids (M3, M4):** feed to `qa-ui-typescript` as the `missing-testids.md` handshake input.
- **Security-flavoured (R2):** `qa-security` owns pricing the localStorage-token exposure against
  the deferred M4 refresh ladder. Not a `fe-react` patch.
- **Accessibility (I4, I5):** static findings only. `qa-ui-typescript` should prove them with axe +
  a keyboard-only pass — the dead `Escape` handler in particular is a behaviour, not a lint.
- **Design altitude (N5, N8, and the light-palette question behind G7):** `design-auditor` /
  `design-ux-review` — this audit judged conformance, not craft.
- **Next round:** re-run this skill. It enters Mode B, reuses every ID above verbatim, and should
  open by closing the 26 Unverifiable rows — the six J rows first, since that is where the codebase
  is strongest and a regression there would cost the most.
