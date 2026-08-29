import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { safeStorage } from './safeStorage'

/**
 * The guarded storage wrapper, tested where it earns its existence: when `localStorage` throws.
 *
 * `localStorage` raises on quota, in Safari private mode, and in a blocked third-party context —
 * and a bare `setItem` in a render path takes the screen down with it. Every branch below is one
 * this module exists to absorb, and none of them is reachable through the app under jsdom, which
 * is why they are exercised here rather than through a component.
 *
 * The fallback is deliberately *in-memory*, not a silent no-op: within the session the value is
 * still readable, which is what keeps a case editor's draft alive on a machine where storage is
 * refused.
 */

const real = {
  getItem: Storage.prototype.getItem,
  setItem: Storage.prototype.setItem,
  removeItem: Storage.prototype.removeItem,
}

/** Makes every storage operation throw, the way a full quota or a blocked context does. */
function storageRefuses() {
  const boom = () => {
    throw new DOMException('QuotaExceededError')
  }
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(boom)
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(boom)
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(boom)
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
  Storage.prototype.getItem = real.getItem
  Storage.prototype.setItem = real.setItem
  Storage.prototype.removeItem = real.removeItem
  localStorage.clear()
})

describe('when localStorage works', () => {
  it('round-trips a string', () => {
    safeStorage.set('k', 'v')
    expect(safeStorage.get('k')).toBe('v')
  })

  it('reads an absent key as null rather than undefined', () => {
    expect(safeStorage.get('never-written')).toBeNull()
  })

  it('round-trips JSON', () => {
    safeStorage.setJSON('k', { a: 1 })
    expect(safeStorage.getJSON<{ a: number }>('k')).toEqual({ a: 1 })
  })

  it('discards a corrupt entry instead of throwing on every later read', () => {
    localStorage.setItem('k', '{not json')

    expect(safeStorage.getJSON('k')).toBeNull()
    // Removed, not merely reported: a value that can never parse would otherwise fail forever.
    expect(localStorage.getItem('k')).toBeNull()
  })

  it('removes', () => {
    safeStorage.set('k', 'v')
    safeStorage.remove('k')
    expect(safeStorage.get('k')).toBeNull()
  })
})

describe('when localStorage throws', () => {
  it('a write degrades to memory instead of propagating', () => {
    storageRefuses()

    expect(() => safeStorage.set('k', 'v')).not.toThrow()
    expect(safeStorage.get('k')).toBe('v')
  })

  it('a read of something never written is null, not a throw', () => {
    storageRefuses()

    expect(safeStorage.get('absent')).toBeNull()
  })

  it('a removal takes the value out of the memory fallback too', () => {
    storageRefuses()
    safeStorage.set('k', 'v')

    safeStorage.remove('k')

    expect(safeStorage.get('k')).toBeNull()
  })

  it('JSON degrades to memory as well', () => {
    storageRefuses()

    safeStorage.setJSON('k', { a: 1 })

    expect(safeStorage.getJSON<{ a: number }>('k')).toEqual({ a: 1 })
  })
})
