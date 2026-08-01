/**
 * One setup file, run before every test file.
 *
 * Everything here is a jsdom gap the application legitimately depends on, plus the matcher
 * import. Nothing about application behaviour is configured here — a setup file that quietly
 * changes how the app behaves under test is how a suite ends up green against a program that
 * does not exist.
 */
import '@testing-library/jest-dom/vitest'
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
 * jsdom implements neither `matchMedia` nor `ResizeObserver`, and MUI reaches for both — the
 * theme's `useMediaQuery` calls, and MUI X's tree/grid virtualization. Without these, rendering
 * anything from `@mui/material` throws before a single assertion runs.
 *
 * `matches: false` on every query is the honest default for a headless environment: it makes
 * `prefers-reduced-motion: reduce` read as *not* set, so a component that guards animation
 * behind it takes the same branch it takes for most users.
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
