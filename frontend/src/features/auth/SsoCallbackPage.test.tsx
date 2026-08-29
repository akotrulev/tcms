import { render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { AuthContext, type AuthValue } from '../../auth/authContextValue'
import { aCurrentUser } from '../../test/fixtures'
import { SsoCallbackPage } from './SsoCallbackPage'
import { consumeSsoReturnPath, rememberSsoReturnPath } from './ssoReturnPath'

/**
 * The route an external sign-in lands on — and the five things about it that are silent when wrong.
 *
 * The destinations are **sentinels rather than the real pages**: what this component decides is
 * where to go and what to carry, and mounting `ProjectsPage` or `LoginPage` behind it would make
 * every assertion here depend on two screens that have their own tests and their own fetches.
 * `Landing.SsoFailed` is the sentinel for the one piece of state that crosses — `LoginPage` renders
 * the sentence, and `LoginPage.test.tsx` asserts the sentence.
 *
 * No `QueryClient` is built here and none is needed: this component reads the auth context and
 * calls one function. Nothing under it fetches.
 */

/** The fragment is on `window.location`, not on the router, so tests set it there. */
function landingOn(hash: string) {
  window.history.replaceState(null, '', `/sso/callback${hash}`)
}

function Landing() {
  const state = useLocation().state as { ssoFailed?: boolean } | null
  return (
    <div data-testid="Landing.SignedIn">{state?.ssoFailed ? 'sso-failed' : 'landed'}</div>
  )
}

function Security() {
  const state = useLocation().state as { linked?: string } | null
  return <div data-testid="Landing.Security">{state?.linked ?? 'no-marker'}</div>
}

function SignIn() {
  const state = useLocation().state as { ssoFailed?: boolean } | null
  return <div data-testid="Landing.SsoFailed">{state?.ssoFailed ? 'sso-failed' : 'no-state'}</div>
}

function renderCallback(adoptSession: AuthValue['adoptSession']) {
  const session: AuthValue = {
    user: null,
    status: 'ready',
    login: async () => aCurrentUser(),
    adoptSession,
    logout: () => {},
  }
  const tree: ReactNode = (
    <AuthContext value={session}>
      <MemoryRouter initialEntries={['/sso/callback']}>
        <Routes>
          <Route path="/sso/callback" element={<SsoCallbackPage />} />
          <Route path="/" element={<Landing />} />
          <Route path="/login" element={<SignIn />} />
          {/* Sentinel for the stashed deep link — see 'returns to the deep link' below. */}
          <Route path="/projects/p1/runs/r9" element={<div data-testid="Landing.DeepLink" />} />
          {/* Sentinel for a completed link — the security page has its own tests. */}
          <Route path="/account/security" element={<Security />} />
        </Routes>
      </MemoryRouter>
    </AuthContext>
  )
  return render(tree)
}

describe('SsoCallbackPage', () => {
  beforeEach(() => {
    landingOn('')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    window.history.replaceState(null, '', '/')
    sessionStorage.clear()
  })

  it('adopts the session in the fragment and lands on the app', async () => {
    landingOn('#token=a-session-token&expiresAt=2026-08-16T09%3A00%3A00Z')
    const adopt = vi.fn().mockResolvedValue(aCurrentUser())

    renderCallback(adopt)

    await waitFor(() => expect(adopt).toHaveBeenCalledWith('a-session-token'))
    expect(await screen.findByTestId('Landing.SignedIn')).toHaveTextContent('landed')
  })

  it('wipes the fragment, so a live session does not survive in the address bar', async () => {
    landingOn('#token=a-session-token')

    renderCallback(vi.fn().mockResolvedValue(aCurrentUser()))

    await waitFor(() => expect(window.location.hash).toBe(''))
  })

  it('adopts once when the effect runs twice', async () => {
    landingOn('#token=a-session-token')
    const adopt = vi.fn().mockResolvedValue(aCurrentUser())

    const { rerender } = renderCallback(adopt)
    /*
      A remount of the same element is what StrictMode's second effect pass looks like from here,
      and it is the case the two refs exist for: the fragment is already gone by now, so a page
      that read it in the effect would see an empty hash and report a failed sign-in.
    */
    rerender(<div />)

    await waitFor(() => expect(adopt).toHaveBeenCalledTimes(1))
  })

  it('sends the failure marker back to sign-in, carrying nothing about the cause', async () => {
    landingOn('#error=sso_failed')
    const adopt = vi.fn()

    renderCallback(adopt)

    expect(await screen.findByTestId('Landing.SsoFailed')).toHaveTextContent('sso-failed')
    // The marker is not read, only its absence-of-a-token. Nothing here can name a cause because
    // the server sent none (§6r).
    expect(adopt).not.toHaveBeenCalled()
  })

  it('treats an empty fragment as a failure rather than as a pending sign-in', async () => {
    landingOn('')
    const adopt = vi.fn()

    renderCallback(adopt)

    expect(await screen.findByTestId('Landing.SsoFailed')).toHaveTextContent('sso-failed')
    expect(adopt).not.toHaveBeenCalled()
  })

  it('sends a token the server would not confirm back to sign-in the same way', async () => {
    landingOn('#token=stale')
    const adopt = vi.fn().mockRejectedValue(new ApiError(401, ''))

    renderCallback(adopt)

    expect(await screen.findByTestId('Landing.SsoFailed')).toHaveTextContent('sso-failed')
  })

  it('returns to the deep link the sign-in screen stashed', async () => {
    // The auth gate's `from` cannot ride the router through the issuer round trip — the browser
    // leaves this app entirely — so LoginPage stashes it and this page consumes it.
    rememberSsoReturnPath('/projects/p1/runs/r9')
    landingOn('#token=a-session-token')

    renderCallback(vi.fn().mockResolvedValue(aCurrentUser()))

    expect(await screen.findByTestId('Landing.DeepLink')).toBeInTheDocument()
    // Consumed: a later sign-in with no stash of its own lands on the root, not on this one's.
    expect(consumeSsoReturnPath()).toBe('/')
  })

  it('discards the stash on a failed sign-in rather than saving it for a stranger', async () => {
    rememberSsoReturnPath('/projects/p1/runs/r9')
    landingOn('#error=sso_failed')

    renderCallback(vi.fn())

    expect(await screen.findByTestId('Landing.SsoFailed')).toHaveTextContent('sso-failed')
    expect(consumeSsoReturnPath()).toBe('/')
  })

  it('routes a completed link to the security page without touching the session', async () => {
    landingOn('#linked=GITHUB')
    const adopt = vi.fn()

    renderCallback(adopt)

    expect(await screen.findByTestId('Landing.Security')).toHaveTextContent('GITHUB')
    // A link is made by a signed-in browser; adopting here would replace a live session
    // with nothing.
    expect(adopt).not.toHaveBeenCalled()
  })
})
