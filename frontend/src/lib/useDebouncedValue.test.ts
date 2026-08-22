import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDebouncedValue } from './useDebouncedValue'

/**
 * The one debounce (§250).
 *
 * Every arm here is about a request that must NOT be made, which is invisible to `tsc` and to any
 * browser pass short enough to be worth running: the screens that consume this hook look identical
 * whether it debounces or not, and the only difference is the request count.
 */

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useDebouncedValue', () => {
  it('returns the first value immediately, so nothing renders empty on mount', () => {
    const { result } = renderHook(() => useDebouncedValue('payment', 300))

    expect(result.current).toBe('payment')
  })

  it('withholds a change until the value has been still for the delay', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: 'pay' },
    })

    rerender({ value: 'payment' })
    expect(result.current).toBe('pay')

    act(() => {
      vi.advanceTimersByTime(299)
    })
    expect(result.current).toBe('pay')

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(result.current).toBe('payment')
  })

  it('settles once on the last value of a burst, not once per keystroke', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: '' },
    })

    for (const value of ['p', 'pa', 'pay', 'paym', 'payme', 'paymen', 'payment']) {
      rerender({ value })
      act(() => {
        vi.advanceTimersByTime(50)
      })
    }

    expect(result.current).toBe('')

    act(() => {
      vi.advanceTimersByTime(300)
    })
    expect(result.current).toBe('payment')
  })

  it('never settles on a value the caller has already moved off', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: 'a' },
    })

    rerender({ value: 'ab' })
    act(() => {
      vi.advanceTimersByTime(150)
    })
    rerender({ value: 'a' })

    act(() => {
      vi.advanceTimersByTime(300)
    })
    expect(result.current).toBe('a')
  })

  it('honours each caller’s own delay', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 250), {
      initialProps: { value: 'one' },
    })

    rerender({ value: 'two' })
    act(() => {
      vi.advanceTimersByTime(250)
    })

    expect(result.current).toBe('two')
  })
})
