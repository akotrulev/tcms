import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BUILD_ID } from '../lib/reportClientError'
import {
  clearSkewGuard,
  handlePossibleSkew,
  isChunkLoadError,
  reloadOntoCurrentBuild,
} from './versionSkew'

/**
 * The once-only reload guard.
 *
 * This is the single most dangerous piece of recovery code in the app, because getting it wrong
 * does not degrade the experience — it *removes* the app. An auto-reload with no guard, fired
 * against a build that is genuinely broken rather than merely stale, is an infinite reload loop:
 * the user cannot read the error, cannot navigate away, and cannot open devtools long enough to
 * see why. There is no state the UI can render its way out of.
 *
 * It is also invisible in development (Vite never deletes a chunk from under you) and completely
 * reliable in production, so a unit test is the only place the guard is ever exercised before a
 * deploy.
 */

const GUARD_KEY = 'tcms.skewReloadAttempted'

describe('isChunkLoadError — one condition, four spellings', () => {
  /*
    The message text differs by bundler and browser. Missing one spelling does not produce a
    visible bug; it produces a *silent* one — the skew goes unrecognised and the user gets a
    generic "Couldn't load this" on a nav item that has nothing wrong with it.
  */
  it.each([
    ['Rolldown/Vite', new Error('Failed to fetch dynamically imported module: /assets/x-abc.js')],
    ['Firefox', new Error('error loading dynamically imported module')],
    ['Safari', new Error('Importing a module script failed.')],
    ['webpack message', new Error('Loading chunk 42 failed.')],
  ])('recognises the %s wording', (_browser, error) => {
    expect(isChunkLoadError(error)).toBe(true)
  })

  it('recognises a ChunkLoadError by name regardless of its message', () => {
    const error = new Error('anything at all')
    error.name = 'ChunkLoadError'
    expect(isChunkLoadError(error)).toBe(true)
  })

  it('matches case-insensitively, because the wording is not ours to depend on', () => {
    expect(isChunkLoadError(new Error('FAILED TO FETCH DYNAMICALLY IMPORTED MODULE'))).toBe(true)
  })

  it('reads a non-Error throw as a string rather than crashing', () => {
    expect(isChunkLoadError('Failed to fetch dynamically imported module')).toBe(true)
    expect(isChunkLoadError({ message: 'nope' })).toBe(false)
  })

  it.each([
    ['an ordinary render bug', new TypeError("Cannot read properties of undefined (reading 'items')")],
    ['a network failure', new TypeError('Failed to fetch')],
    ['null', null],
    ['undefined', undefined],
    ['an empty string', ''],
  ])('does NOT treat %s as skew', (_label, value) => {
    // A false positive here reloads the tab on an ordinary bug — the recovery becomes the
    // defect. `Failed to fetch` is deliberately in this list: it is one word away from the
    // dynamic-import wording and means something entirely different.
    expect(isChunkLoadError(value)).toBe(false)
  })
})

describe('handlePossibleSkew — the decision, without navigating', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('passes an ordinary error straight through', () => {
    expect(handlePossibleSkew(new TypeError('a real bug'), false)).toEqual({ kind: 'not-skew' })
  })

  it('reloads once, and prompts every time after that', () => {
    const chunkError = new Error('Failed to fetch dynamically imported module')

    expect(handlePossibleSkew(chunkError, false)).toEqual({ kind: 'reloading' })
    // THE assertion this file exists for. A second 'reloading' here is an infinite loop in
    // production against a build that is broken rather than stale.
    expect(handlePossibleSkew(chunkError, false)).toEqual({ kind: 'prompt' })
    expect(handlePossibleSkew(chunkError, false)).toEqual({ kind: 'prompt' })
  })

  it('records the attempt against the current build, not a bare flag', () => {
    handlePossibleSkew(new Error('Failed to fetch dynamically imported module'), false)
    expect(sessionStorage.getItem(GUARD_KEY)).toBe(BUILD_ID)
  })

  it("gives a NEW build its own attempt, so one tab's first recovery is not its only one", () => {
    // A tab that recovered across yesterday's deploy must still recover across today's.
    sessionStorage.setItem(GUARD_KEY, 'some-older-build')
    expect(handlePossibleSkew(new Error('Loading chunk 7 failed.'), false)).toEqual({ kind: 'reloading' })
  })

  it('prompts rather than reloading when there is unsaved work, and spends no attempt', () => {
    const chunkError = new Error('Failed to fetch dynamically imported module')

    expect(handlePossibleSkew(chunkError, true)).toEqual({ kind: 'prompt' })
    expect(sessionStorage.getItem(GUARD_KEY)).toBeNull()
    // Same principle as the 401 ladder: a recovery that discards what the user typed is not a
    // recovery. And having declined to reload, the attempt must still be available afterwards.
    expect(handlePossibleSkew(chunkError, false)).toEqual({ kind: 'reloading' })
  })

  it('never spends an attempt on an error that is not skew', () => {
    handlePossibleSkew(new TypeError('an ordinary bug'), false)
    expect(sessionStorage.getItem(GUARD_KEY)).toBeNull()
  })
})

describe('the guard when sessionStorage is unavailable', () => {
  const brokenStorage = () => {
    throw new DOMException('The operation is insecure.', 'SecurityError')
  }

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('refuses to reload at all, because "at most once" cannot be promised', () => {
    // Private mode, a quota-exhausted profile, a locked-down browser. Without a durable guard
    // an auto-reload is unbounded, so the failure mode is chosen deliberately: a message the
    // user can act on, rather than a loop they cannot escape.
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(brokenStorage)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(brokenStorage)

    expect(handlePossibleSkew(new Error('Failed to fetch dynamically imported module'), false)).toEqual({
      kind: 'prompt',
    })
  })

  it('does not throw out of clearSkewGuard', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(brokenStorage)
    expect(() => clearSkewGuard()).not.toThrow()
  })
})

describe('clearSkewGuard', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('releases the attempt once the app has run successfully on this build', () => {
    handlePossibleSkew(new Error('Failed to fetch dynamically imported module'), false)
    expect(sessionStorage.getItem(GUARD_KEY)).toBe(BUILD_ID)

    clearSkewGuard()

    expect(sessionStorage.getItem(GUARD_KEY)).toBeNull()
    expect(handlePossibleSkew(new Error('Failed to fetch dynamically imported module'), false)).toEqual({
      kind: 'reloading',
    })
  })

  it("leaves another build's mark alone", () => {
    // Clearing indiscriminately would hand a second attempt to a build that already spent one.
    sessionStorage.setItem(GUARD_KEY, 'a-different-build')
    clearSkewGuard()
    expect(sessionStorage.getItem(GUARD_KEY)).toBe('a-different-build')
  })
})

describe('reloadOntoCurrentBuild', () => {
  it('marks the attempt BEFORE navigating, so the reloaded tab sees it spent', () => {
    // Order is the whole property: reload first and the new document starts with no mark, which
    // is the loop again by another route.
    const reload = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      reload,
    } as unknown as Location)

    sessionStorage.clear()
    reloadOntoCurrentBuild()

    expect(sessionStorage.getItem(GUARD_KEY)).toBe(BUILD_ID)
    expect(reload).toHaveBeenCalledOnce()
  })
})
