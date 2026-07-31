# §36. Frontend Phase 4 — Vitest + RTL, and the first tests this codebase has ever had (2026-07-31)

Run through the **`fe-react`** skill, Phase 4 (`fe-react-component-testing`). Executed as **lane C**
of a three-lane parallel run, in the worktree `tcms-lane-c` — the one backlog item
`docs/plans/2026-07-31-parallel-execution-rework.md` names as safe to run as a second lane, because
it adds files rather than editing them and touches no contract.

**Nothing was installed before this slice.** No `vitest`, no `@testing-library/*`, no `test` script.
Every gate this project has ever quoted — `npm run build`, `npm run lint`, a browser pass — proves
that the code compiles and that a human could drive it once. None of them can be run by a machine
against a *unit*, which is why five of the eleven defects §5, §31 and §32 record were found by a
person clicking, and why three of them had been on screen for weeks.

## Versions detected

React 19.2 · Vite 8.1.5 · **no Next.js, no RSC**, so the skill's async-Server-Component lane does not
apply and nothing here is deferred to E2E for that reason. Vitest 4.1.10 · RTL 16.3 · jsdom 28.

## What shipped

| Path | Purpose |
|---|---|
| `frontend/vitest.config.ts` | **New.** Merged into `vite.config.ts`, not folded into it — see below. |
| `frontend/src/test/setup.ts` | **New.** jest-dom matchers, the two jsdom gaps MUI needs, and the storage reset between files. |
| `frontend/src/test/queryClient.ts` | **New.** A fresh, retry-free `QueryClient` per test. |
| `frontend/src/test/TestProviders.tsx` | **New.** Router + real theme + query client. Deliberately *not* `app/Providers.tsx`. |
| `frontend/package.json` | `test` / `test:run` / `test:coverage`; seven dev dependencies. |
| `frontend/tsconfig.node.json` | `vitest.config.ts` added to `include`, so `tsc -b` typechecks it. |
| `src/lib/describeError.test.ts` | 41 — the retry matrix and the 409 split. |
| `src/lib/format.test.ts` | 43 — release ordering, the denominator guard, the age ladder. |
| `src/lib/results.test.ts` | 17 — the count arithmetic, including §32's defect as a property. |
| `src/lib/permissions.test.ts` | 14 — the catalogue, computed rather than read. |
| `src/app/versionSkew.test.ts` | 23 — the once-only reload guard. |
| `src/auth/useCan.test.tsx` | 12 — §31's three-state gate through the real query. |
| `src/components/PermissionGate.test.tsx` | 10 — both defects §31's browser pass found. |
| `src/components/DataRegion.test.tsx` | 19 — the loading/loaded/failed/empty/stale matrix. |
| `src/features/cases/useCaseFilters.test.tsx` | 17 — the URL round trip. |
| `src/features/cases/SuiteTree.test.tsx` | 7 — §32's double-count at its actual site. |

**203 tests, 10 files, green three runs in a row.** **No production source was modified.**

## The choices, and the failure mode each one prevents

**The test config is a separate file merged into the build config, not a `test` key inside it.**
Folding `test` into `vite.config.ts` requires importing `defineConfig` from `vitest/config`, which
makes the *production build* depend on the test runner: `npm ci --omit=dev` — what a release image
and the eventual boot-jar build do — would fail to resolve it before compiling a single module. The
failure mode is "the build works on every developer machine and nowhere else". Merging rather than
re-declaring keeps `plugins: [react()]` and the `__BUILD_ID__` define stated once; a second
hand-written `define` is where the value `versionSkew.ts` reads under test drifts from the value that
ships.

**jsdom, not happy-dom — and pinned to the 28 line.** happy-dom is meaningfully faster and at this
suite's size that buys under a second; not enough to trade for divergence from the DOM the app runs
in. `versionSkew` has a branch that fires when `sessionStorage` *throws*, and MUI reads computed
styles. The version pin is the interesting half: **jsdom 29+ requires node ≥ 22.13, and this repo
pins 22.12.0 in `.nvmrc` and `engines`.** jsdom 28's `engines` is `^20.19.0 || ^22.12.0 || >=24`,
which is the newest line that admits the node the project actually runs. Installing jsdom 30 first
produced an `EBADENGINE` warning and nothing else — it would have worked locally and been a coin
flip in CI. **Raising the node floor is a decision for CI and the Docker image, not something a test
runner gets to make on their behalf.**

**TypeScript 6 was not downgraded, and did not need to be.** The `npm run gen:api`-through-`npx`
workaround exists because `openapi-typescript` peer-requires TS 5.x; nothing in the Vitest stack
peer-depends on TypeScript at all, so the two problems are unrelated and the pin is untouched.

**`globals: false`.** Every test imports `describe`/`it`/`expect` from `vitest`. The alternative
requires adding `vitest/globals` to `tsconfig.app.json`'s `types`, which puts the runner's globals in
scope for **application** source too — and `expect` being defined in a component file is not a
property anyone should be able to lean on.

**Test files live beside their subjects, inside `src`, so `tsc -b` typechecks them.** A type error in
a test is a build failure, not a surprise the next person running the suite discovers. They are not
imported by the entry, so they are not bundled — measured below rather than assumed.

**`app/Providers.tsx` is not reused as the test wrapper.** Its nesting order is load-bearing (§6
lesson 6), and mounting all of it around a unit test drags in the notification stack, the announcer,
the session-recovery dialog and the real retry policy. A unit test that goes red because the
notification provider changed is a test of the wrong thing. `TestProviders` is router + the **real**
theme + a query client, and no more. The real theme rather than a bare `createTheme()`, because
component code reads project tokens — a default theme makes a missing one render fine under test and
blank in the app.

**`useCan` is tested through the real `useMyPermissions`, with `api.get` stubbed at the module
boundary.** Stubbing the *hook* would have been easier and would have tested nothing: two of the
three states the gate must answer for are produced by TanStack Query, not by `useCan` — a disabled
query (`enabled: !!projectId`) reports `isPending` forever, and a failure resolves to `isError` with
`data === undefined`, which is the fall-open path. A mocked hook lets a test assert whatever it likes
about "no project" without ever touching the wiring that produces it.

## The tests were mutation-checked, not just watched to pass

A green test that cannot go red is worse than no test, because it makes the suite *look* covered.
Two of the least obvious assertions were verified by breaking the code under them and confirming the
failure, then restoring it:

| Mutation | Result |
|---|---|
| `useCaseFilters`: `{ replace: true }` → `false` on `setFilter` | 1 failed — and landed on `?q=re`, exactly the letter-by-letter back button the assertion predicts. |
| `SuiteTree`: `totalCases: own + children` → `totalCases: own` | 4 failed, including the 27-case reconciliation. |

`git status` was checked clean on both files afterwards.

## What the tests found

**No new product defect.** That is worth stating plainly rather than dressing up: the units chosen
were the ones that had already been through a browser pass, and they behaved. Three findings of other
kinds, in descending order of how much they matter:

1. **`SuiteTree` silently drops a suite whose `parentId` names a suite absent from the payload.**
   `buildTree` buckets by parent and walks from `null`, so an orphan is never reached — it renders
   nowhere, with no empty state and no error, and its cases are unreachable through the tree.
   **Not a live defect:** the endpoint returns a project's whole suite list, so the input cannot
   occur today. It is pinned as a **characterisation test** (`drops a suite whose parent is absent
   from the payload (documented, not endorsed)`) because the assumption is invisible in the code.
   The day the suite list is paginated, filtered, or served mid-way through a parent's soft delete,
   that test flips from documenting a shape to failing on a real one — which is the moment somebody
   has to decide whether orphans are dropped or surfaced at the root. Rendering the *right* answer
   is not this slice's job; making the wrong one impossible to ship unnoticed is.
2. **`PermissionGate` remounts its child across the denied → allowed transition.** The denied branch
   nests the control in a Tooltip anchor and the allowed branch does not, so a reference captured
   while the permission query is pending is detached by the time the control is enabled. Harmless in
   the app (nothing holds a ref across it), and a trap for every future test of a gated control:
   **re-query inside `waitFor`, never capture once.** Written into the test as a comment at the point
   it bites.
3. **`SuiteTree`'s expansion is controlled from the selection**, so a node deeper than the first
   level is not in the DOM until something below it is selected. Correct behaviour, and it means a
   three-level assertion has to pass `selectedSuiteId` to reach the leaf at all.

## What is *not* covered, and why — read this before writing the next test

- **`CaseSchema`'s cross-field refinements are NOT tested**, and they were on the target list.
  `CaseSchema` is module-private inside `CaseEditorPage.tsx`. Reaching it needs either a one-line
  `export`, or rendering the whole editor (suites + classification queries + router params +
  snackbar). Lane A and lane B were editing the case feature folder concurrently, and this lane's
  rule was "avoid editing production source", so it was left. **The follow-up is exactly one line —
  `const CaseSchema` → `export const CaseSchema` in `CaseEditorPage.tsx`** — and the four refinements
  are worth it: GHERKIN without a scenario, STRUCTURED with zero steps, NOT_AUTOMATABLE without a
  reason, AUTOMATED without a key. All four are server-enforced too, so a divergence is a form that
  submits and gets rejected.
- **The suite is not wired into CI.** `.github/workflows/ci.yml` still ends with a comment reserving
  the test step for the `qa-cicd-typescript` peer, and that boundary was respected rather than
  quietly crossed. **A suite nothing runs is a suite that rots**, so this is the single most urgent
  follow-up: a `- name: Test` / `run: npm run test:run` step after Lint, plus whatever JUnit reporter
  the peer wants. The stale "not-yet-started Phase 4" comment on line 112 goes with it.
- **No MSW in the test runner.** `src/mocks/handlers.ts` is browser-oriented and stateful, and
  wiring `setupServer` would couple unit tests to fixture data that exists to be *awkward*. Stubbing
  `api.get` per test keeps each test's input visible in the test. Worth revisiting only if a slice
  needs to exercise `queries.ts` itself.
- **Untested by design:** `queryClient.ts`'s retry wiring (its policy, `isTransient`, is covered),
  the notification stack, `AppShell`, and every markup-only component. Snapshot tests of markup were
  not written and should not be.

## Gate evidence

```
$ npm run build
> tsc -b && vite build
vite v8.1.5 building client environment for production...
✓ 1709 modules transformed.
dist/assets/CaseListPage-0VtxR2Zm.js   535.21 kB │ gzip: 157.65 kB
✓ built in 478ms

$ npm run lint          # oxlint
Found 0 warnings and 0 errors.
Finished in 17ms on 130 files with 104 rules using 8 threads.

$ npm run test:run
 RUN  v4.1.10
 Test Files  10 passed (10)
      Tests  203 passed (203)
   Duration  6.76s
```

Run three consecutive times: 203/203 each time, no order dependence, no flake.

**Coverage on the units this slice targeted** (`npm run test:coverage`; the repo-wide figure is
19.2% and is not a number to manage):

| Unit | Stmts | Branch |
|---|---|---|
| `lib/describeError.ts` | 100 | 100 |
| `lib/permissions.ts` | 100 | 100 |
| `lib/results.ts` | 100 | 100 |
| `auth/useCan.ts` | 100 | 100 |
| `components/PermissionGate.tsx` | 100 | 100 |
| `app/versionSkew.ts` | 100 | 100 |
| `features/cases/useCaseFilters.ts` | 98.1 | 93.3 |
| `lib/format.ts` | 95.3 | 95.0 |
| `components/DataRegion.tsx` | 87.5 | 89.5 |
| `features/cases/SuiteTree.tsx` | 56.5 | 60.4 |

### Bundle impact — measured before and after, per §6 lesson 8

Built at `af399a2` with a clean tree, saved `dist`, then built again after the change:

| | Before | After |
|---|---|---|
| JS chunks | 46 | 46 |
| Total raw JS | 1480 kB | 1480 kB |
| Sum of gzipped chunks | 450,840 B | 450,846 B |

**Every chunk is byte-identical in raw size** (`diff` of per-chunk byte counts: no differences).
Dev dependencies and `*.test.ts` files that nothing imports do not reach the graph, which is the
expected answer — and it is stated here as a measurement rather than as reasoning, because §6 lesson
8 is about exactly the case where the reasoning was right-sounding and wrong. The 6-byte gzip delta
is `__BUILD_ID__`, which is `local-${Date.now().toString(36)}` and differs between any two builds.

## Two lessons for §6

13. **A green suite is not evidence until you have seen it go red.** Two assertions in this slice
    were verified by breaking the production code under them and confirming the exact predicted
    failure. One of the two — the `replace: true` on `setFilter` — would have passed against a
    `push` for the first four assertions in the same test and only failed on the fifth; without the
    mutation check there was no way to know which of them was doing the work. Same family as lesson
    9: the invariant needs computing, not asserting.
14. **A dependency's `engines` range is a decision about the whole project, not about the tool.**
    jsdom 30 installed with a single `EBADENGINE` warning against this repo's pinned node and would
    have run locally. Reading the range and stepping back two majors cost five minutes; discovering
    it from a CI failure on somebody else's branch would have cost more, and "fixing" it by bumping
    `.nvmrc` would have changed the node the backend image, the Docker build and every developer
    shell run — to make a test runner happy.

## Next

1. **Wire `npm run test:run` into `.github/workflows/ci.yml`** (`qa-cicd-typescript`). Everything
   below is worth less than this.
2. **Export `CaseSchema` and test its four refinements** — one line of production change, four
   cross-field rules that today are only proved by a person filling in a form.
3. `lib/labels.ts`, `ResultBar` / `PassRateSummary` (both denominators on one screen, §5's third
   Phase 2 defect), `StatusChip` (colour is never the only signal), and the notification stack's
   dedupe/roll-up/cap — the next tier of logic-bearing units, in that order.
4. Frontend **Phase 5** — hostile self-review, unchanged from §5.
