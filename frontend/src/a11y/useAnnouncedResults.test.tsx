import { renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { AnnouncerContext } from './announcerContextValue'
import { useAnnouncedResults } from './useAnnouncedResults'

/**
 * The list-result announcer (§250).
 *
 * Every arm is about WHEN it speaks, which no rendering test can see and no browser pass can prove
 * negatively: the screens look identical whether this announces once, twice or never.
 */

function withAnnouncer(announce: (message: string) => void) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <AnnouncerContext value={{ announce }}>{children}</AnnouncerContext>
  }
}

function drive(announce: (message: string) => void, message = '3 cases match the current filters') {
  return renderHook(
    ({ isFetching, isSuccess }) => useAnnouncedResults({ isFetching, isSuccess }, message),
    {
      wrapper: withAnnouncer(announce),
      initialProps: { isFetching: true, isSuccess: false },
    },
  )
}

describe('useAnnouncedResults', () => {
  it('says nothing on the first settle — arriving is the route announcement’s to report', () => {
    const announce = vi.fn()
    const { rerender } = drive(announce)

    rerender({ isFetching: false, isSuccess: true })

    expect(announce).not.toHaveBeenCalled()
  })

  it('announces the second settle, which is the first one the reader caused', () => {
    const announce = vi.fn()
    const { rerender } = drive(announce)

    rerender({ isFetching: false, isSuccess: true })
    rerender({ isFetching: true, isSuccess: true })
    rerender({ isFetching: false, isSuccess: true })

    expect(announce).toHaveBeenCalledTimes(1)
    expect(announce).toHaveBeenCalledWith('3 cases match the current filters')
  })

  it('says nothing while a fetch is still in flight', () => {
    const announce = vi.fn()
    const { rerender } = drive(announce)

    rerender({ isFetching: false, isSuccess: true })
    rerender({ isFetching: true, isSuccess: true })

    expect(announce).not.toHaveBeenCalled()
  })

  it('stays silent when the refetch failed — the error surface owns that, not the result region', () => {
    const announce = vi.fn()
    const { rerender } = drive(announce)

    rerender({ isFetching: false, isSuccess: true })
    rerender({ isFetching: true, isSuccess: false })
    rerender({ isFetching: false, isSuccess: false })

    expect(announce).not.toHaveBeenCalled()
  })

  it('announces the message current at the settle, not the one captured when the fetch began', () => {
    const announce = vi.fn()
    const { rerender } = renderHook(
      ({ isFetching, message }) =>
        useAnnouncedResults({ isFetching, isSuccess: true }, message),
      {
        wrapper: withAnnouncer(announce),
        initialProps: { isFetching: true, message: 'stale' },
      },
    )

    rerender({ isFetching: false, message: 'stale' })
    rerender({ isFetching: true, message: 'stale' })
    rerender({ isFetching: false, message: '12 cases match the current filters' })

    expect(announce).toHaveBeenCalledWith('12 cases match the current filters')
  })
})
