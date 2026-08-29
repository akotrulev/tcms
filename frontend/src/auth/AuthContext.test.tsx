import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api/client'
import { aCurrentUser } from '../test/fixtures'
import { accessToken } from './accessToken'
import { AuthProvider } from './AuthContext'
import { resetSessionChannelForTests, type SessionBroadcast } from './sessionChannel'
import { useAuth } from './useAuth'

/**
 * `adoptSession` — the second and last writer of `tcms.token` (§222).
 *
 * The three things asserted here are the three the callback page cannot check for itself, and each
 * of them is silent when wrong:
 *
 *   1. **The token is confirmed against `/me` before anything claims a session.** The fragment
 *      carries a token and no user, so a provider that trusted it would render a shell with no
 *      name in it and no way to get one.
 *   2. **A rejected `/me` clears the token.** Keeping a credential the server would not confirm is
 *      how an app renders signed-in chrome over a session that cannot make one successful request.
 *   3. **Sibling tabs are told.** `login` publishes and so must this, or the second tab goes on
 *      rendering a sign-in form beside a perfectly valid session.
 *
 * `renderHook` is not used: the value under test is on the provider's own context, and driving it
 * through a probe component is what `multiTabSession.test.tsx` does for the same tree.
 */


/** The provider's own context value, reachable from a test, plus the last error it threw. */
let adopt: (token: string) => Promise<unknown>
let adoptionError: unknown

function Probe() {
  const { user, adoptSession } = useAuth()
  adopt = (token: string) =>
    adoptSession(token).catch((error: unknown) => {
      adoptionError = error
      throw error
    })
  return <span data-testid="Probe.User">{user ? user.email : 'signed-out'}</span>
}

describe('adoptSession', () => {
  beforeEach(() => {
    localStorage.clear()
    adoptionError = undefined
    resetSessionChannelForTests()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('confirms the token against /me before it claims a session', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue(aCurrentUser({ email: 'kim@acme.test' }))

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    await act(async () => {
      await adopt('a-session-token')
    })

    expect(get).toHaveBeenCalledWith('/me')
    expect(accessToken.get()).toBe('a-session-token')
    await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('kim@acme.test'))
  })

  it('discards a token /me rejects rather than holding a credential it could not confirm', async () => {
    vi.spyOn(api, 'get').mockRejectedValue(new ApiError(401, ''))

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    await act(async () => {
      await expect(adopt('stale')).rejects.toBeInstanceOf(ApiError)
    })

    expect(adoptionError).toBeInstanceOf(ApiError)
    expect(screen.getByTestId('Probe.User')).toHaveTextContent('signed-out')
    expect(accessToken.get()).toBeNull()
  })

  it('tells sibling tabs, so a second tab does not keep rendering signed out', async () => {
    const published: SessionBroadcast[] = []
    vi.spyOn(api, 'get').mockResolvedValue(aCurrentUser({ email: 'kim@acme.test' }))
    /*
      A second channel object on the same name is exactly what a second tab is, and it is the only
      way to observe this: `publishSession` posts to the channel and a `BroadcastChannel` never
      echoes to its own sender, so an in-process subscriber sees nothing.
    */
    const sibling = new BroadcastChannel('tcms.session')
    sibling.onmessage = (event: MessageEvent<SessionBroadcast>) => published.push(event.data)

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    await act(async () => {
      await adopt('a-session-token')
    })

    await waitFor(() =>
      expect(published).toContainEqual({
        type: 'signed-in',
        email: 'kim@acme.test',
        token: 'a-session-token',
      }),
    )
    sibling.close()
  })
})
