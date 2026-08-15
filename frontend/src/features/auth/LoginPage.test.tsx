import { ThemeProvider } from '@mui/material/styles'
import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { theme } from '../../app/theme'
import { AuthContext, type AuthValue } from '../../auth/authContextValue'
import { aCurrentUser, anSsoProviderChoice } from '../../test/fixtures'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { LoginPage } from './LoginPage'
import { consumeSsoReturnPath } from './ssoReturnPath'

/**
 * The sign-in screen's federated half (§222), and four of these five assert **an absence**.
 *
 * That is the shape of the requirement rather than a thin test: `POST /auth/providers` answers 200
 * with an empty list for an unclaimed domain, a disabled provider and a malformed address alike,
 * because a sign-in surface must not be an oracle for which companies exist on this instance (§6r).
 * The server pays for that property on every call; a screen that rendered "no single sign-on for
 * that domain" would give it straight back, and nothing but a test can see the difference between
 * a page that stayed silent and a page that never asked.
 *
 * The fifth is the one that fails loudly instead of silently: the authorize endpoint answers a 302
 * to a **third-party origin**, so it must be reached by navigating and never by `fetch`.
 */

const PROVIDER_ID = 'f2000000-0000-4000-8000-000000000001'

function renderLogin(
  locationState?: Record<string, unknown>,
  login: AuthValue['login'] = async () => aCurrentUser(),
) {
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
          <MemoryRouter initialEntries={[{ pathname: '/login', state: locationState ?? null }]}>
            <Routes>
              <Route path="/login" element={<LoginPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </ThemeProvider>
    </AuthContext>,
  )
}

/** Types a whole address and leaves the field, which is what drives discovery. */
async function enterAddress(user: ReturnType<typeof userEvent.setup>, address: string) {
  await user.type(screen.getByTestId('Login.EmailField'), address)
  await user.tab()
}

describe('LoginPage — single sign-on', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('offers a button per provider once the address is complete', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'post').mockResolvedValue({
      providers: [anSsoProviderChoice({ displayName: 'Acme SSO' })],
    })

    renderLogin()
    await enterAddress(user, 'kim@acme.test')

    expect(
      await screen.findByRole('button', { name: /continue with acme sso/i }),
    ).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/auth/providers', { email: 'kim@acme.test' })
  })

  it('says nothing at all when the domain reaches no provider', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'post').mockResolvedValue({ providers: [] })

    renderLogin()
    await enterAddress(user, 'kim@nowhere.test')

    await waitFor(() => expect(api.post).toHaveBeenCalled())
    expect(screen.queryByTestId('Login.Providers')).not.toBeInTheDocument()
    // The property the server pays for: an empty answer must be indistinguishable from no answer.
    expect(screen.queryByText(/single sign-on|no provider|not available/i)).not.toBeInTheDocument()
  })

  it('says nothing when discovery fails either', async () => {
    const user = userEvent.setup()
    /*
      The failed write IS the subject: discovery is a mutation, and what this asserts is that its
      failure reaches the screen as nothing at all. A refusal and an empty list must be
      indistinguishable, so the failure has to be driven rather than avoided.
    */
    drivingAFailedWrite()
    vi.spyOn(api, 'post').mockRejectedValue(new ApiError(500, ''))

    renderLogin()
    await enterAddress(user, 'kim@acme.test')

    await waitFor(() => expect(api.post).toHaveBeenCalled())
    expect(screen.queryByTestId('Login.Providers')).not.toBeInTheDocument()
  })

  it('does not ask before there is a whole address to ask about', async () => {
    const user = userEvent.setup()
    const post = vi.spyOn(api, 'post').mockResolvedValue({ providers: [] })

    renderLogin()
    await enterAddress(user, 'kim')

    expect(post).not.toHaveBeenCalled()
  })

  it('navigates the browser to the authorize endpoint rather than fetching it', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'post').mockResolvedValue({
      providers: [anSsoProviderChoice({ id: PROVIDER_ID })],
    })
    const assign = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign,
    } as unknown as Location)

    renderLogin()
    await enterAddress(user, 'kim@acme.test')
    await user.click(await screen.findByTestId('Login.Provider'))

    // Against the API origin, and with no `fetch` anywhere near it: the endpoint answers 302 to the
    // issuer, which a fetch would follow into a CORS failure.
    expect(assign).toHaveBeenCalledWith(`/api/v1/auth/sso/${PROVIDER_ID}/authorize`)
  })

  it('renders the single sign-on failure as one sentence naming no cause', () => {
    renderLogin({ ssoFailed: true })

    const alert = screen.getByTestId('Login.Error')
    expect(alert).toHaveTextContent("We couldn't sign you in with single sign-on.")
    expect(alert).not.toHaveTextContent(/provider|account|disabled|expired/i)
  })

  it('asks for a prefilled address without waiting for a blur that never comes', async () => {
    // The just-accepted invitee arrives with their email already in the field and no reason to
    // ever leave it — blur alone would make them the one person never shown their buttons.
    vi.spyOn(api, 'post').mockResolvedValue({
      providers: [anSsoProviderChoice({ displayName: 'Acme SSO' })],
    })

    renderLogin({ acceptedEmail: 'kim@acme.test' })

    expect(
      await screen.findByRole('button', { name: /continue with acme sso/i }),
    ).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(api.post).toHaveBeenCalledWith('/auth/providers', { email: 'kim@acme.test' })
  })

  it('takes the buttons down when the address is edited', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'post').mockResolvedValue({
      providers: [anSsoProviderChoice({ displayName: 'Acme SSO' })],
    })

    renderLogin()
    await enterAddress(user, 'kim@acme.test')
    await screen.findByTestId('Login.Providers')

    // A different address is now in the field; a button still offering the old one's providers
    // would start a sign-in nobody asked for. The next blur asks again.
    await user.type(screen.getByTestId('Login.EmailField'), 'x')

    await waitFor(() => expect(screen.queryByTestId('Login.Providers')).not.toBeInTheDocument())
  })

  it('asks after a rejected local sign-in, which is how an autofilled address gets its buttons', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'post').mockResolvedValue({
      providers: [anSsoProviderChoice({ displayName: 'Acme SSO' })],
    })

    // A password manager fills both fields and submits — no blur ever fires. At a company whose
    // accounts exist only through its issuer, this 401 is the person's everyday experience.
    renderLogin(undefined, async () => {
      throw new ApiError(401, '')
    })
    await user.type(screen.getByTestId('Login.EmailField'), 'kim@acme.test')
    await user.type(screen.getByTestId('Login.PasswordField'), 'not-their-password{enter}')

    expect(
      await screen.findByRole('button', { name: /continue with acme sso/i }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('Login.Error')).toBeInTheDocument()
  })

  it('stashes the deep link before handing the browser to the issuer', async () => {
    const user = userEvent.setup()
    vi.spyOn(api, 'post').mockResolvedValue({
      providers: [anSsoProviderChoice({ id: PROVIDER_ID })],
    })
    const assign = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign,
    } as unknown as Location)

    renderLogin({ from: '/projects/p1/runs/r9' })
    await enterAddress(user, 'kim@acme.test')
    await user.click(await screen.findByTestId('Login.Provider'))

    // The router state dies with the navigation to the issuer; sessionStorage is what carries the
    // auth gate's `from` across the round trip, for `SsoCallbackPage` to consume.
    expect(consumeSsoReturnPath()).toBe('/projects/p1/runs/r9')
    expect(assign).toHaveBeenCalled()
  })
})
