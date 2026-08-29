/**
 * One setup file, run before every test file.
 *
 * Everything here is a jsdom gap the application legitimately depends on, plus the matcher
 * import. Nothing about application behaviour is configured here — a setup file that quietly
 * changes how the app behaves under test is how a suite ends up green against a program that
 * does not exist.
 *
 * `contractDoubles` and `renderFailures` are the two imports that do more than fill a gap, and they
 * are here *because* of the rule above rather than despite it: neither changes anything about the
 * application, and between them they change what a green test is allowed to mean. The first compares
 * every body a mocked `api.*` call serves — and every payload it is called with — against
 * `openapi.yaml`; the second fails a test whose render tripped an error boundary, which is the same
 * gap one layer down, because a boundary exists precisely so that a broken region stays invisible to
 * the rest of the page. A suite green against a program that does not exist is what an unchecked
 * fixture buys, and a suite green beside a panel that never rendered is what a caught throw buys.
 * Both have to be installed globally — a check each file opts into is a hand-maintained list — so
 * this is the only place either can go; see those modules' headers.
 */
import '@testing-library/jest-dom/vitest'
import './contractDoubles'
import './renderFailures'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'

/**
 * RTL's per-wait timeout, raised from its 1000 ms default — the smaller half of §54's flake fix.
 *
 * The larger half is `testTimeout` in `vitest.config.ts`, whose comment carries the diagnosis.
 * This one has to stay **well below** it: `asyncUtilTimeout` is the budget for a single `waitFor`,
 * and setting the two equal (the first attempt) lets one wait consume the whole test, turning an
 * assertion failure into a bare "Test timed out" that names nothing. Kept at a quarter of the test
 * budget so a wait that genuinely never resolves still reports itself as the wait that failed.
 */
configure({ asyncUtilTimeout: 5000 })

/**
 * ## Which locator a test reaches for, and why this suite's ratio is what it is
 *
 * `data-testid` is the handshake this repo runs on: QA's page objects locate testid-first, and
 * `components/kit` and every feature screen are tagged for that contract. Inside RTL it is the
 * *last* resort rather than the first, and the two facts are not in tension — they are about
 * different readers. An E2E suite needs a locator that survives a copy change; a component test is
 * the place where the copy and the role ARE the subject.
 *
 * **The rule: an interactive element is queried by `getByRole(..., { name })`; a container or a
 * region is queried by its testid.** A button, link, checkbox, textbox, option or menu item has an
 * accessible name, and asking for it asserts that name for free. A panel, a row set, a data region's
 * loading/failed/empty/stale branch, and the notification surface have no role worth naming — their
 * testid is the honest locator.
 *
 * This is written down because the suite's history runs the other way: at the audit that raised it
 * the ratio was 1037 `getByTestId` to 69 `getByRole`, and the cost was legible — the dead `Escape`
 * handlers on five hand-rolled popups (audit I5) were invisible to 146 test files, because a test
 * that finds a control by testid never asks whether a keyboard user could have found it at all. The
 * ratio is not being swept; a rewrite of a thousand passing assertions buys churn. It moves when a
 * file is opened for another reason, and new tests start on the right side of it.
 */

/**
 * jsdom implements neither `matchMedia` nor `ResizeObserver`, and the app reaches for both.
 *
 * ~~MUI reaches for both — the theme's `useMediaQuery` calls, and MUI X's tree/grid
 * virtualization. Without these, rendering anything from `@mui/material` throws before a single
 * assertion runs.~~ → the *callers* changed in T16 and the polyfills did not: `matchMedia` is now
 * `app/colorScheme.tsx` resolving `system` to light or dark, and `ResizeObserver` is Radix's
 * popper measuring its trigger. `colorScheme.tsx` also guards `matchMedia`'s absence itself — that
 * is a property of the provider rather than a reason to drop this, because a component that only
 * *reads* the scheme should not have to.
 *
 * This is deliberately a polyfill for a missing browser API and **not** a stub for something the
 * application does under test — `ReviewPanel`, `LinkBlockDialog` and `kit/Combobox` all record the
 * same rule from the other side: `@radix-ui/react-select` is not used, because bending the harness
 * to suit it would be stubbing `hasPointerCapture`, which is a behaviour and not an environment.
 *
 * `matches: false` on every query is the honest default for a headless environment: it makes
 * `prefers-reduced-motion: reduce` read as *not* set, so a component that guards animation
 * behind it takes the same branch it takes for most users. It also makes the colour scheme resolve
 * to light under test, which is why no suite has to mount a provider to get a stable palette.
 */
beforeEach(() => {
  if (!window.matchMedia) {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      configurable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }),
    })
  }

  if (!('ResizeObserver' in window)) {
    Object.defineProperty(window, 'ResizeObserver', {
      writable: true,
      configurable: true,
      value: class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    })
  }
})

afterEach(() => {
  cleanup()
  // `sessionStorage` is module-adjacent state that outlives a test: `versionSkew`'s once-only
  // guard writes a key there, and a leaked key makes the NEXT test's first skew look like a
  // second attempt. Cleared here rather than in one file, because that is exactly the kind of
  // cross-file order dependence that only shows up in CI.
  try {
    sessionStorage.clear()
    localStorage.clear()
  } catch {
    /* storage disabled in this test; nothing to clear */
  }
  vi.useRealTimers()
})
