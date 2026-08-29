import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.ts'

/**
 * The test runner's config is SEPARATE from `vite.config.ts`, and merged into it.
 *
 * The tempting arrangement is one file — import `defineConfig` from `vitest/config` in
 * `vite.config.ts` and add a `test` key. It is one file fewer and it makes the production
 * build depend on the test runner: `vite build` loads the config first, so `npm ci --omit=dev`
 * (which is what a release image and a boot-jar build do) would fail to resolve `vitest/config`
 * before a single module was compiled. The failure mode is "the build works on every developer
 * machine and nowhere else", which is the worst kind.
 *
 * Merging rather than re-declaring is the other half: `plugins: [react()]` and the
 * `__BUILD_ID__` define are stated once, in `vite.config.ts`. `versionSkew.ts` reads
 * `BUILD_ID`, so a second hand-written `define` here would be a place for the value under test
 * to drift away from the value that ships.
 */
export default mergeConfig(
  viteConfig,
  defineConfig({
    server: { fs: { allow: ['..'] } },
    test: {
      /**
       * jsdom, not happy-dom, for three specific reasons rather than habit:
       *
       *   1. **`sessionStorage` with a real quota/exception surface.** `app/versionSkew.ts`
       *      has a branch that fires when storage *throws* (private mode), and its answer
       *      there — refuse to reload — is the one that prevents an infinite reload loop.
       *      Testing it needs a storage implementation that behaves like the browser's.
       *   2. **Radix's popper measures layout.** ~~`DataRegion`, `PermissionGate` and the suite
       *      tree render real MUI, which reads computed styles and (via Popper/Tooltip)
       *      layout.~~ → T16 removed MUI, and the requirement survived its reason: the dialogs,
       *      dropdowns and tooltips are Radix now, and Radix reads `ResizeObserver` and computed
       *      styles to place a popper. `src/test/setup.ts` polyfills the first; the second needs
       *      an environment that has one.
       *   3. **It is the reference implementation.** happy-dom is meaningfully faster, and at
       *      this suite's size that buys under a second — not enough to trade for divergence
       *      from the DOM the app actually runs in.
       *
       * Pinned to jsdom 28: it is the newest line whose `engines` still admits the node
       * version this repo pins in `.nvmrc` (22.12.0). jsdom 29+ requires 22.13+, and raising
       * the node floor is a decision for CI and the Docker image, not for a test runner.
       */
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      /**
       * No `globals: true`. Every test imports `describe`/`it`/`expect` from `vitest`
       * explicitly, so the file typechecks under `tsconfig.app.json` without adding
       * `vitest/globals` to its `types` array — which would put the runner's globals in scope
       * for application source too, where `expect` being defined is not a property anyone
       * should be able to rely on.
       */
      globals: false,
      include: ['src/**/*.test.{ts,tsx}'],
      /** Spies and mock call history reset between tests — leaked history is order-dependent flake. */
      restoreMocks: true,
      clearMocks: true,
      /**
       * Raised from vitest's 5 s default, to fix a real flake §54 diagnosed.
       *
       * The suite was failing 1–6 tests per full run at random — `GrantRoleDialog`, `SuiteTree`,
       * `RunCreateDialog`, `PermissionGate`, spread across files that share nothing but this
       * environment — while every one of them passed alone. Reproduced on a tree with §54's own
       * new tests removed, so it predates them.
       *
       * The cause is duration, not correctness: a `userEvent` test that types a sentence and then
       * drives a popover is dozens of real-timer round trips through React's scheduler, and
       * with files running in parallel on a busy machine the slowest of them crosses five seconds.
       * The failure says so — *Test timed out in 5000ms*, never an assertion.
       *
       * **The first attempt raised RTL's `asyncUtilTimeout` instead and made it worse**, which is
       * worth recording: that is the budget for one `waitFor`, so setting it to the same 5 s as
       * the test budget let a single wait consume the entire test. Two timeouts, and the failing
       * message named which one.
       *
       * A budget for waiting is not an assertion. A genuinely broken expectation still fails, just
       * later. What this stops is a green program being reported red because the machine was busy —
       * which costs more than the seconds it saves, since the usual response to an intermittent red
       * is to re-run until it passes, and that habit is what hides a real intermittent defect when
       * one finally arrives.
       */
      /*
       * **Raised 20s → 30s in the evidence slice, and the recurrence is the point.**
       *
       * Adding five tests elsewhere in the suite pushed `DocPageEditorPage`'s two `userEvent.type`
       * tests over the 20 s budget — the same two, always, and always *Test timed out*, never an
       * assertion. They pass alone in under 15 s for the whole file. So the suite has a load-
       * dependent cliff and this number is how far the cliff is from the slowest test, which means
       * **any slice that adds tests anywhere can redden a file it never touched.**
       *
       * Raising it again is the recorded remedy and it is still treating the symptom. ~~The real fix
       * is that two tests type whole sentences character-by-character through React's scheduler:
       * `userEvent.type(el, text)` is dozens of real-timer round trips, and `paste` or a direct
       * change event would assert the same thing in a fraction of the time.~~ That is a change to
       * `DocPageEditorPage.test.tsx`'s approach rather than to a budget, it is out of the evidence
       * slice's scope, and it is written down here so the next person raising this number sees that
       * it has now been raised twice for one cause.
       *
       * **→ superseded: that remedy was finally measured, and it is not one.** `CaseEditorPage.test.tsx`
       * timed out on master twice in one evening, so the levers this comment recommends were tried
       * against it before its budget was raised: `delay: null` on all 34 setups moved the file
       * 108 s → 98 s, and replacing every `user.type` with `click` + `paste` moved it 108 s → 95 s
       * while breaking two tests. The premise is what was wrong — a keystroke into that screen's
       * step field costs ~190 ms of which **~16 ms is React**, so it was never "round trips through
       * React's scheduler"; it is jsdom event dispatch and userEvent's own per-key bookkeeping, and
       * nothing a test file writes moves it. **So this number stays at 30 s and the one file that
       * cannot live inside it carries its own `vi.setConfig` instead** — the measurements are in its
       * header. What is still open is the other reading of the same evidence: the runner is 3.7×
       * slower than a laptop for the same file, and if a timeout ever lands on a file that is *not*
       * that one, the answer is capping vitest's worker count, not a fourth raise here.
       */
      testTimeout: 30_000,
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html'],
        include: ['src/**/*.{ts,tsx}'],
        exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/api/schema.d.ts'],
      },
    },
  }),
)
