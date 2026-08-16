import { ThemeProvider } from '@mui/material/styles'
import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { SocialProvider } from '../../api/types'
import { AuthContext, type AuthValue } from '../../auth/authContextValue'
import { theme } from '../../app/theme'
import {
  aCurrentUser,
  anInvitationPreview,
  aSocialProviderList,
} from '../../test/fixtures'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { AcceptInvitationPage } from './AcceptInvitationPage'

/**
 * The register → signed-in → security-page hop-removal (V58, the plan's Task 12).
 *
 * The accept endpoint still answers 204 with no session — the boundary under pin here is that
 * the SPA composes accept + the ordinary `POST /auth/login` through the SAME `login` seam the
 * sign-in screen uses, and only when the acceptance created the credential. The destinations
 * are sentinels: what this page decides is where a fresh account lands and what a failed
 * auto-login carries, and mounting the real pages would tie these tests to screens with their
 * own tests and their own fetches.
 */

function Landing() {
  return <div data-testid="Landing.App" />
}

function Security() {
  return <div data-testid="Landing.Security" />
}

function SignIn() {
  const state = useLocation().state as { acceptedEmail?: string; from?: string } | null
  return (
    <div data-testid="Landing.SignIn">
      {state?.acceptedEmail ?? 'no-email'}:{state?.from ?? 'no-from'}
    </div>
  )
}

function renderAccept(
  login: AuthValue['login'],
  socialProviders: SocialProvider[],
  preview = anInvitationPreview(),
) {
  vi.spyOn(api, 'get').mockImplementation((path) => {
    if (/^\/auth\/social\/providers$/.test(path)) {
      return Promise.resolve(aSocialProviderList({ providers: socialProviders }))
    }
    if (/^\/auth\/invitations\/[^/]+$/.test(path)) {
      return Promise.resolve(preview)
    }
    throw new Error(`unmocked read: ${path}`)
  })
  const session: AuthValue = {
    user: null,
    status: 'ready',
    login,
    adoptSession: async () => aCurrentUser(),
    logout: () => {},
  }
  return render(
    <AuthContext value={session}>
      <ThemeProvider theme={theme}>
        <QueryClientProvider client={createTestQueryClient()}>
          <MemoryRouter initialEntries={['/accept-invitation?token=the-secret']}>
            <Routes>
              <Route path="/accept-invitation" element={<AcceptInvitationPage />} />
              <Route path="/" element={<Landing />} />
              <Route path="/account/security" element={<Security />} />
              <Route path="/login" element={<SignIn />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </ThemeProvider>
    </AuthContext>,
  )
}

/** Fills the two password fields and submits — the acceptance that creates a credential. */
async function acceptWithPassword(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText(/^password/i), 'correct-horse-battery')
  await user.type(screen.getByLabelText(/confirm password/i), 'correct-horse-battery')
  await user.click(screen.getByTestId('AcceptInvitation.SubmitButton'))
}

describe('AcceptInvitationPage — auto-login after acceptance (V58)', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('signs the new account in through the login seam and lands on the security page welcome', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'post').mockResolvedValue(undefined)
    const login = vi.fn().mockResolvedValue(aCurrentUser())

    renderAccept(login, ['GITHUB'])
    await acceptWithPassword(user)

    await waitFor(() =>
      expect(login).toHaveBeenCalledWith('invitee@acme.test', 'correct-horse-battery', true),
    )
    // ?welcome=1 travels in the URL (house rule: filter state lives there), so the sentinel
    // route receiving the navigation is the assertion that the destination was the security
    // page; the page's own welcome-mode tests read the param.
    expect(await screen.findByTestId('Landing.Security')).toBeInTheDocument()
  })

  it('still auto-logs-in with no providers configured, landing on the app — the hop is gone either way', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'post').mockResolvedValue(undefined)
    const login = vi.fn().mockResolvedValue(aCurrentUser())

    renderAccept(login, [])
    await acceptWithPassword(user)

    await waitFor(() => expect(login).toHaveBeenCalled())
    expect(await screen.findByTestId('Landing.App')).toBeInTheDocument()
  })

  it('falls back to the sign-in page carrying the destination when the auto-login fails', async () => {
    const user = userEvent.setup()
    /*
      The failed write IS the subject: the login round-trip refusing is the branch under test,
      and the assertion is that acceptance still reads as success — the ordinary sign-in form,
      the address prefilled, the welcome destination preserved, no scarier state.
    */
    drivingAFailedWrite()
    vi.spyOn(api, 'post').mockResolvedValue(undefined)
    const login = vi.fn().mockRejectedValue(new Error('refused'))

    renderAccept(login, ['GITHUB'])
    await acceptWithPassword(user)

    const signIn = await screen.findByTestId('Landing.SignIn')
    expect(signIn).toHaveTextContent('invitee@acme.test:/account/security?welcome=1')
  })

  it('attempts no auto-login for an existing account joining a second company', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'post').mockResolvedValue(undefined)
    const login = vi.fn()

    renderAccept(login, ['GITHUB'], anInvitationPreview({ passwordRequired: false }))
    await user.click(await screen.findByTestId('AcceptInvitation.SubmitButton'))

    // No password was typed, so there is nothing to auto-login with (regression pin).
    const signIn = await screen.findByTestId('Landing.SignIn')
    expect(signIn).toHaveTextContent('invitee@acme.test:no-from')
    expect(login).not.toHaveBeenCalled()
  })
})
