import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AUTOSAVE_DELAY_MS, useCaseAutosave } from './useCaseAutosave'

/**
 * The autosave timer (§128).
 *
 * The behaviours here are all invisible to `tsc` and to a browser pass short enough to be worth
 * doing, because each one is about *when* a request is not made:
 *
 *   1. **Nothing is buffered while `enabled` is false.** This is the gate the editor holds shut
 *      until the restore prompt has been answered, and the consequence of it leaking is the whole
 *      point of the slice — the first keystroke overwrites the draft the editor is about to offer,
 *      so the user is shown a restore prompt for work that no longer exists. Nothing errors.
 *   2. **The window restarts on each change.** A typist produces one write, not one per character.
 *   3. **Identical content writes nothing.** Tabbing through a field, typing a character and
 *      deleting it, a re-render — none of them are edits.
 *   4. **A failure is not retried on its own, but does not suppress the next change.** The ref that
 *      tracks what the server holds is a claim about the server, so a failed write must clear it.
 */

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

/** Advances past the debounce window and lets the resulting promise settle. */
async function tick() {
  await act(async () => {
    vi.advanceTimersByTime(AUTOSAVE_DELAY_MS)
  })
}

describe('useCaseAutosave', () => {
  it('buffers once the window has passed', async () => {
    const save = vi.fn().mockResolvedValue({})
    const { result } = renderHook(() =>
      useCaseAutosave({ enabled: true, payload: { title: 'One' }, save }),
    )

    expect(save).not.toHaveBeenCalled()
    expect(result.current.status).toBe('pending')

    await tick()

    expect(save).toHaveBeenCalledExactlyOnceWith({ title: 'One' })
    expect(result.current.status).toBe('saved')
    expect(result.current.savedAt).not.toBeNull()
  })

  it('WRITES NOTHING while disabled — the restore-prompt gate', async () => {
    // THE test of this hook. Without the gate, the first keystroke buffers the saved case over the
    // draft the editor is a moment away from offering, and the restore prompt then hands back
    // content the author had already saved. See the hook's header for the five-step sequence.
    const save = vi.fn().mockResolvedValue({})
    renderHook(() => useCaseAutosave({ enabled: false, payload: { title: 'One' }, save }))

    await tick()

    expect(save).not.toHaveBeenCalled()
  })

  it('buffers the latest content once the gate opens, not the content it was holding', async () => {
    // The gate closing and reopening must not replay a stale payload — the author has been typing
    // the whole time the prompt was up.
    const save = vi.fn().mockResolvedValue({})
    const { rerender } = renderHook(
      ({ enabled, title }: { enabled: boolean; title: string }) =>
        useCaseAutosave({ enabled, payload: { title }, save }),
      { initialProps: { enabled: false, title: 'One' } },
    )

    rerender({ enabled: false, title: 'Two' })
    rerender({ enabled: true, title: 'Three' })
    await tick()

    expect(save).toHaveBeenCalledExactlyOnceWith({ title: 'Three' })
  })

  it('restarts the window on every change, so a typist produces one write', async () => {
    const save = vi.fn().mockResolvedValue({})
    const { rerender } = renderHook(
      ({ title }: { title: string }) => useCaseAutosave({ enabled: true, payload: { title }, save }),
      { initialProps: { title: 'R' } },
    )

    // Three "keystrokes", each well inside the window.
    for (const title of ['Re', 'Ref', 'Refu']) {
      await act(async () => {
        vi.advanceTimersByTime(AUTOSAVE_DELAY_MS / 2)
      })
      rerender({ title })
    }
    expect(save).not.toHaveBeenCalled()

    await tick()

    expect(save).toHaveBeenCalledExactlyOnceWith({ title: 'Refu' })
  })

  it('writes nothing when the content is unchanged', async () => {
    // Keyed on the serialized payload rather than on a render or a change count: a blur, a child
    // remounting, or a character typed and deleted are not edits.
    const save = vi.fn().mockResolvedValue({})
    const { rerender } = renderHook(
      ({ title }: { title: string }) => useCaseAutosave({ enabled: true, payload: { title }, save }),
      { initialProps: { title: 'One' } },
    )

    await tick()
    expect(save).toHaveBeenCalledTimes(1)

    // A fresh object with identical content — which is what `toCasePayload` produces every render.
    rerender({ title: 'One' })
    await tick()

    expect(save).toHaveBeenCalledTimes(1)
  })

  it('does not re-send while only the save callback identity changes', async () => {
    // `save` is a mutation callback with a new identity every render. An effect that depended on
    // it would restart the debounce on each of a typist's re-renders and never reach its deadline.
    const { rerender } = renderHook(
      ({ save }: { save: () => Promise<unknown> }) =>
        useCaseAutosave({ enabled: true, payload: { title: 'One' }, save }),
      { initialProps: { save: vi.fn().mockResolvedValue({}) } },
    )

    const second = vi.fn().mockResolvedValue({})
    rerender({ save: second })
    await tick()

    // Called once, through whichever callback was current when the deadline arrived.
    expect(second).toHaveBeenCalledTimes(1)
  })

  it('reports a failure and does not retry it by itself', async () => {
    const save = vi.fn().mockRejectedValue(new Error('nope'))
    const { result } = renderHook(() =>
      useCaseAutosave({ enabled: true, payload: { title: 'One' }, save }),
    )

    await tick()

    expect(result.current.status).toBe('failed')

    // No timer-driven retry: the work is in the form, which is the safest place it can be, and a
    // client retrying on a schedule would hammer a server that is already refusing.
    await tick()
    expect(save).toHaveBeenCalledTimes(1)
  })

  it('tries again on the next change after a failure', async () => {
    // The ref that tracks what the server holds is a claim about the server, so a failed write has
    // to clear it — otherwise the next edit is suppressed as already-buffered and the author's
    // work stops being backed up for the rest of the session, silently.
    const save = vi.fn().mockRejectedValueOnce(new Error('nope')).mockResolvedValue({})
    const { rerender, result } = renderHook(
      ({ title }: { title: string }) => useCaseAutosave({ enabled: true, payload: { title }, save }),
      { initialProps: { title: 'One' } },
    )

    await tick()
    expect(result.current.status).toBe('failed')

    rerender({ title: 'Two' })
    await tick()

    expect(save).toHaveBeenCalledTimes(2)
    expect(result.current.status).toBe('saved')
  })

  it('sends nothing after unmount', async () => {
    // Navigating away mid-window must not buffer from a screen that no longer exists.
    const save = vi.fn().mockResolvedValue({})
    const { unmount } = renderHook(() =>
      useCaseAutosave({ enabled: true, payload: { title: 'One' }, save }),
    )

    unmount()
    await tick()

    expect(save).not.toHaveBeenCalled()
  })
})
