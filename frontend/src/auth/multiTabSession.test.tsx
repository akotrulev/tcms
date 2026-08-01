import { ThemeProvider } from '@mui/material/styles'
import { QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api/client'
import type { CurrentUser } from '../api/types'
import { theme } from '../app/theme'
import { createTestQueryClient } from '../test/queryClient'
import { AuthProvider } from './AuthContext'
import { SessionRecoveryProvider, SessionReplayBridge } from './SessionRecoveryProvider'
import { resetSessionChannelForTests, subscribeSession, type SessionBroadcast } from './sessionChannel'
import { useAuth } from './useAuth'
import { useSessionRecovery } from './useSessionRecovery'

/**
 * What a *sibling* tab does when the session changes somewhere else.
 *
 * §5 left this open and §40 sequenced it behind the refetch policy. The reason it is worth a test
 * rather than a browser pass alone is that the failure is silent in every one of its forms: a tab
 * that keeps rendering a signed-in shell over a token that no longer exists looks completely
 * normal until the first click, and a tab that keeps the *previous user's* cached answers over a
 * new user's token looks normal permanently.
 *
 * The providers are mounted in the real nesting order — Auth → SessionRecovery → QueryClient +
 * `SessionReplayBridge` — because the order is what makes the identity comparison in the bridge
 * legible at all (`AuthProvider` subscribes first and has already called `setUser` by the time
 * the bridge's listener runs). A rearranged tree would test a program that does not exist.
 */

const TOKEN_KEY = 'tcms.token'
const USER_KEY = 'tcms.user'

const alice: CurrentUser = {
  id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  email: 'alice@acme.test',
  displayName: 'Alice',
  isSystemAdmin: false,
  teams: [],
}

const bob: CurrentUser = {
  ...alice,
  id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  email: 'bob@acme.test',
  displayName: 'Bob',
}

/** Puts a session in shared storage — which is what another tab signing in actually does. */
function storeSession(user: CurrentUser) {
  localStorage.setItem(TOKEN_KEY, `token-for-${user.email}`)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

function clearStoredSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/**
 * The other tab. A second channel object on the same name is exactly what a second tab is.
 *
 * Delivery is awaited by *observing* it through a sentinel subscriber rather than by sleeping.
 * A fixed timeout passes on an idle machine and fails on a loaded one — which is how a suite
 * acquires the kind of flake nobody can reproduce. Subscribing last means the sentinel fires
 * after the providers' own listeners have run, so returning from here is a real guarantee that
 * the app has seen the message.
 */
async function fromAnotherTab(message: SessionBroadcast) {
  const channel = new BroadcastChannel('tcms.session')
  const seen = new Promise<void>((resolve) => {
    const unsubscribe = subscribeSession(() => {
      unsubscribe()
      resolve()
    })
  })
  channel.postMessage(message)
  await act(async () => {
    await seen
  })
  channel.close()
}

function Probe() {
  const { user, login, logout } = useAuth()
  const { reportUnauthorized } = useSessionRecovery()
  return (
    <div>
      <span data-testid="Probe.User">{user ? user.email : 'signed-out'}</span>
      <button data-testid="Probe.Expire" onClick={reportUnauthorized}>
        expire
      </button>
      {/* The same-tab transition. BroadcastChannel does not echo to its sender, so nothing the
          suite above exercises reaches this path. */}
      <button data-testid="Probe.SignOut" onClick={logout}>
        sign out
      </button>
      <button
        data-testid="Probe.SignInAsBob"
        onClick={() => void login(bob.email, 'password', false)}
      >
        sign in as Bob
      </button>
    </div>
  )
}

function renderTab() {
  const client = createTestQueryClient()
  const view = render(
    <ThemeProvider theme={theme}>
      <AuthProvider>
        <SessionRecoveryProvider>
          <QueryClientProvider client={client}>
            <SessionReplayBridge />
            <Probe />
          </QueryClientProvider>
        </SessionRecoveryProvider>
      </AuthProvider>
    </ThemeProvider>,
  )
  return { client, view }
}

beforeEach(() => {
  /*
    `/me` is where a session is settled — on mount, and again when another tab signs in. The stub
    answers the way the server does: the **token** decides who you are, and no token is a 401.

    Keying the answer off the token rather than off the cached user is not incidental. The
    cross-tab path deliberately stopped adopting from `localStorage` after a browser pass showed
    the broadcast overtaking the write between renderer processes; a stub that answered from the
    cached user would let that regression back in without failing.
  */
  vi.spyOn(api, 'get').mockImplementation(async (path: string) => {
    if (path !== '/me') throw new Error(`unexpected GET ${path}`)
    const token = localStorage.getItem(TOKEN_KEY)
    const owner = [alice, bob].find((candidate) => token === `token-for-${candidate.email}`)
    if (!owner) throw new ApiError(401, 'no session')
    return owner as never
  })
})

afterEach(() => {
  resetSessionChannelForTests()
  vi.restoreAllMocks()
})

describe('a session transition in another tab', () => {
  describe('sign-out', () => {
    it('stops this tab rendering as signed in', async () => {
      storeSession(alice)
      renderTab()
      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))

      // The signing-out tab has already removed the shared token; this tab is only being told.
      clearStoredSession()
      await fromAnotherTab({ type: 'signed-out' })

      // Not cosmetic: with the token gone this tab cannot make a single successful request, so
      // every control it is still rendering is a control that produces a 401.
      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('signed-out'))
    })

    it('empties the query cache, so the next session cannot read the last one’s answers', async () => {
      storeSession(alice)
      const { client } = renderTab()
      client.setQueryData(['projects'], [{ id: 'p1', name: 'Alice’s project' }])

      clearStoredSession()
      await fromAnotherTab({ type: 'signed-out' })

      await waitFor(() => expect(client.getQueryData(['projects'])).toBeUndefined())
    })

    it('closes a session-expired dialog rather than leaving it over the redirect', async () => {
      storeSession(alice)
      renderTab()
      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))

      screen.getByTestId('Probe.Expire').click()
      await waitFor(() => expect(screen.getByTestId('SessionExpired.Dialog')).toBeInTheDocument())

      clearStoredSession()
      await fromAnotherTab({ type: 'signed-out' })

      // The dialog is deliberately non-dismissible. Riding it over a navigation to the login
      // screen would leave a password prompt for a session that has ended on purpose.
      await waitFor(() => expect(screen.queryByTestId('SessionExpired.Dialog')).not.toBeInTheDocument())
    })
  })

  describe('sign-in as the same user — the tab settles', () => {
    it('dismisses the session-expired dialog instead of asking for the password twice', async () => {
      storeSession(alice)
      renderTab()
      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))

      screen.getByTestId('Probe.Expire').click()
      await waitFor(() => expect(screen.getByTestId('SessionExpired.Dialog')).toBeInTheDocument())

      // The user gave up on this tab, signed in on the other one, and came back.
      storeSession(alice)
      await fromAnotherTab({ type: 'signed-in', email: alice.email })

      await waitFor(() => expect(screen.queryByTestId('SessionExpired.Dialog')).not.toBeInTheDocument())
      expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test')
    })

    it('keeps the cache, because it is still this user’s', async () => {
      storeSession(alice)
      const { client } = renderTab()
      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))
      client.setQueryData(['projects'], [{ id: 'p1' }])

      await fromAnotherTab({ type: 'signed-in', email: alice.email })

      // Discarding it would be safe and wasteful: every mounted region would drop to a skeleton
      // for a session that never actually changed hands.
      expect(client.getQueryData(['projects'])).toEqual([{ id: 'p1' }])
    })
  })

  describe('sign-in as a different user — the sharp case', () => {
    it('adopts the new identity rather than rendering the previous user’s name over their token', async () => {
      storeSession(alice)
      renderTab()
      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))

      storeSession(bob)
      await fromAnotherTab({ type: 'signed-in', email: bob.email })

      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('bob@acme.test'))
    })

    it('empties the query cache, so Bob is not shown Alice’s answers', async () => {
      storeSession(alice)
      const { client } = renderTab()
      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))
      // Not only lists: `useCan` gates eleven write affordances on a cached permission answer.
      client.setQueryData(['projects'], [{ id: 'p1', name: 'Alice’s project' }])
      client.setQueryData(['me', 'permissions', 'p1'], { permissions: ['case:delete'] })

      storeSession(bob)
      await fromAnotherTab({ type: 'signed-in', email: bob.email })

      await waitFor(() => expect(client.getQueryData(['projects'])).toBeUndefined())
      expect(client.getQueryData(['me', 'permissions', 'p1'])).toBeUndefined()
    })
  })

  /**
   * The sibling adopts what the **server** says, not what its own copy of storage says — because
   * a `BroadcastChannel` message can outrun the sibling's view of the `localStorage` write that
   * preceded it (see the note in `AuthContext`). Both halves of that are pinned here.
   */
  describe('the identity comes from the server, not from this tab’s storage', () => {
    it('adopts the session the token belongs to, even where the cached user is someone else', async () => {
      storeSession(alice)
      renderTab()
      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))

      // The state a lagging renderer process is in: the new token has landed, the cached user
      // record it was written beside has not.
      localStorage.setItem(TOKEN_KEY, `token-for-${bob.email}`)
      await fromAnotherTab({ type: 'signed-in', email: bob.email })

      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('bob@acme.test'))
    })

    it('fails closed when the server rejects the session', async () => {
      storeSession(alice)
      renderTab()
      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))

      // No token at all — `safeStorage` degraded to an in-memory map, say. `/me` answers 401 and
      // the tab signs itself out rather than rendering a shell that cannot make a request.
      localStorage.removeItem(TOKEN_KEY)
      await fromAnotherTab({ type: 'signed-in', email: bob.email })

      await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('signed-out'))
    })
  })
})

/**
 * The same transition, performed by THIS tab — the half the suite above structurally cannot reach.
 *
 * `BroadcastChannel` never delivers a message to its sender, so every protection in
 * `SessionReplayBridge`'s subscriber was running for every tab except the one the user was
 * actually looking at. §52's live pass found it the obvious way: sign out, sign in as somebody
 * from another company, and the *previous* company's project list was still on screen — served
 * from a cache nothing had invalidated, and corrected only by a reload.
 */
describe('a session transition in THIS tab', () => {
  beforeEach(() => {
    // `login` posts; the stub writes the token the way the real endpoint's caller does.
    vi.spyOn(api, 'post').mockImplementation(async (path: string) => {
      if (path === '/auth/logout') return undefined as never
      if (path !== '/auth/login') throw new Error(`unexpected POST ${path}`)
      return { token: `token-for-${bob.email}`, user: bob } as never
    })
  })

  it('empties the query cache when a different person signs in here', async () => {
    storeSession(alice)
    const { client } = renderTab()
    await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))

    client.setQueryData(['projects'], [{ id: 'p1', name: 'Alice’s project' }])

    await act(async () => {
      screen.getByTestId('Probe.SignOut').click()
    })
    await act(async () => {
      screen.getByTestId('Probe.SignInAsBob').click()
    })

    await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('bob@acme.test'))
    expect(
      client.getQueryData(['projects']),
      'Bob is looking at Alice’s project list — the cache outlived the identity it was fetched for',
    ).toBeUndefined()
  })

  it('empties it on sign-out alone, without waiting for the next sign-in', async () => {
    storeSession(alice)
    const { client } = renderTab()
    await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))

    client.setQueryData(['projects'], [{ id: 'p1', name: 'Alice’s project' }])

    await act(async () => {
      screen.getByTestId('Probe.SignOut').click()
    })

    await waitFor(() => expect(client.getQueryData(['projects'])).toBeUndefined())
  })

  it('keeps the cache through a restore that lands on the SAME identity', async () => {
    // The guard against over-correcting: clearing on every identity value rather than on a
    // change would discard a legitimate cache each time the session settles on mount.
    storeSession(alice)
    const { client } = renderTab()
    await waitFor(() => expect(screen.getByTestId('Probe.User')).toHaveTextContent('alice@acme.test'))

    client.setQueryData(['projects'], [{ id: 'p1', name: 'Alice’s project' }])
    await act(async () => {
      await Promise.resolve()
    })

    expect(client.getQueryData(['projects'])).toBeDefined()
  })
})
