import { ThemeProvider } from '@mui/material/styles'
import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { SocialIdentityList, SocialProvider } from '../../api/types'
import { theme } from '../../app/theme'
import {
  aSocialIdentity,
  aSocialIdentityList,
  aSocialProviderList,
  aSocialRedirect,
} from '../../test/fixtures'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedWrite } from '../../test/renderFailures'
import { AccountSecurityPage } from './AccountSecurityPage'

/**
 * The security page (V58): one row per configured provider, link by navigation, unlink behind
 * a confirmation, the first-run welcome behind `?welcome=1` (URL state, per the house rule),
 * and the `#linked=` acknowledgement behind router state.
 */

function renderSecurity({
  providers = ['GITHUB', 'GITLAB', 'GOOGLE'] as SocialProvider[],
  identities = aSocialIdentityList(),
  path = '/account/security',
  state = null as Record<string, unknown> | null,
}: {
  providers?: SocialProvider[]
  identities?: SocialIdentityList
  path?: string
  state?: Record<string, unknown> | null
} = {}) {
  vi.spyOn(api, 'get').mockImplementation((p) => {
    if (/^\/auth\/social\/providers$/.test(p)) {
      return Promise.resolve(aSocialProviderList({ providers }))
    }
    if (/^\/me\/social-identities$/.test(p)) {
      return Promise.resolve(identities)
    }
    throw new Error(`unmocked read: ${p}`)
  })
  return render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter initialEntries={[{ pathname: path.split('?')[0], search: path.split('?')[1] ? `?${path.split('?')[1]}` : '', state }]}>
          <Routes>
            <Route path="/account/security" element={<AccountSecurityPage />} />
            <Route path="/" element={<div data-testid="Landing.Default" />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  )
}

describe('AccountSecurityPage', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders a row per configured provider — linked ones dated with Unlink, others with Link', async () => {
    renderSecurity({
      identities: aSocialIdentityList({
        identities: [aSocialIdentity({ provider: 'GITHUB', emailAtLink: 'kim@github.test' })],
      }),
    })

    const rows = await screen.findAllByTestId('AccountSecurity.Row')
    expect(rows).toHaveLength(3)
    // Linked: the date through lib/format, the provenance email, and an Unlink.
    expect(rows[0]).toHaveTextContent('GitHub')
    expect(rows[0]).toHaveTextContent(/Linked/)
    expect(rows[0]).toHaveTextContent('kim@github.test')
    expect(within(rows[0]).getByTestId('AccountSecurity.Unlink')).toBeInTheDocument()
    // Unlinked: a Link button and no date.
    expect(rows[1]).toHaveTextContent('GitLab')
    expect(within(rows[1]).getByTestId('AccountSecurity.Link')).toBeInTheDocument()
  })

  it('Link POSTs for the URL and navigates the browser to it', async () => {
    const user = userEvent.setup()
    const post = vi.spyOn(api, 'post').mockResolvedValue(
      aSocialRedirect({ url: 'https://github.com/login/oauth/authorize?state=xyz' }),
    )
    const assign = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign,
    } as unknown as Location)

    renderSecurity({ identities: aSocialIdentityList({ identities: [] }) })
    await user.click((await screen.findAllByTestId('AccountSecurity.Link'))[0])

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/me/social-identities/GITHUB'),
    )
    await waitFor(() =>
      expect(assign).toHaveBeenCalledWith('https://github.com/login/oauth/authorize?state=xyz'),
    )
  })

  it('Unlink confirms first, then DELETEs and the list refetches', async () => {
    const user = userEvent.setup()
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined)

    renderSecurity({
      identities: aSocialIdentityList({ identities: [aSocialIdentity({ provider: 'GITHUB' })] }),
    })
    await user.click(await screen.findByTestId('AccountSecurity.Unlink'))

    // Nothing deleted yet — the dialog names the provider and asks.
    expect(del).not.toHaveBeenCalled()
    const dialog = screen.getByTestId('AccountSecurity.UnlinkDialog')
    expect(dialog).toHaveTextContent('Unlink GitHub?')

    await user.click(within(dialog).getByTestId('AccountSecurity.UnlinkConfirm'))
    await waitFor(() => expect(del).toHaveBeenCalledWith('/me/social-identities/GITHUB'))
  })

  it('a 409 on unlink stays on the dialog with the problem detail', async () => {
    const user = userEvent.setup()
    /*
      The refused write IS the subject: the last-credential guard's 409 must surface its
      remedy on the dialog, and nothing else on the page is under assertion.
    */
    drivingAFailedWrite()
    vi.spyOn(api, 'del').mockRejectedValue(
      new ApiError(
        409,
        "It is this account's only way to sign in. Set a password or link another provider first.",
        null,
        null,
        null,
        // The title is load-bearing: describeError reads it to tell this refusal apart from a
        // lost update, whose advice ("reload") would change nothing here.
        'This identity cannot be removed',
      ),
    )

    renderSecurity({
      identities: aSocialIdentityList({ identities: [aSocialIdentity({ provider: 'GITHUB' })] }),
    })
    await user.click(await screen.findByTestId('AccountSecurity.Unlink'))
    await user.click(screen.getByTestId('AccountSecurity.UnlinkConfirm'))

    const refusal = await screen.findByTestId('AccountSecurity.UnlinkError')
    expect(refusal).toHaveTextContent(/only way to sign in/)
  })

  it('arriving with location.state.linked acknowledges the new link by name', async () => {
    renderSecurity({ state: { linked: 'GITHUB' } })

    const acknowledgement = await screen.findByTestId('AccountSecurity.Linked')
    expect(acknowledgement).toHaveTextContent('GitHub is now linked')
  })

  it('?welcome=1 renders the skippable first-run intro; without it, neither intro nor Skip', async () => {
    const { unmount } = renderSecurity({ path: '/account/security?welcome=1' })

    expect(await screen.findByTestId('AccountSecurity.Welcome')).toHaveTextContent(/skip/i)
    expect(screen.getByTestId('AccountSecurity.Skip')).toBeInTheDocument()
    unmount()
    vi.restoreAllMocks()

    renderSecurity()
    await screen.findAllByTestId('AccountSecurity.Row')
    expect(screen.queryByTestId('AccountSecurity.Welcome')).not.toBeInTheDocument()
    expect(screen.queryByTestId('AccountSecurity.Skip')).not.toBeInTheDocument()
  })

  it('Skip navigates to the default landing', async () => {
    const user = userEvent.setup()
    renderSecurity({ path: '/account/security?welcome=1' })

    await user.click(await screen.findByTestId('AccountSecurity.Skip'))

    expect(await screen.findByTestId('Landing.Default')).toBeInTheDocument()
  })

  it('returning from a completed link shows the acknowledgement without the intro — the round-trip does not carry ?welcome=1', async () => {
    // Pinned as correct behaviour: the backend's link landing carries only #linked=, so the
    // intro is gone and the person continues via the app shell. The offer was taken.
    renderSecurity({ state: { linked: 'GITHUB' } })

    expect(await screen.findByTestId('AccountSecurity.Linked')).toBeInTheDocument()
    expect(screen.queryByTestId('AccountSecurity.Welcome')).not.toBeInTheDocument()
  })

  it('explains itself when no providers are configured', async () => {
    renderSecurity({ providers: [] })

    const empty = await screen.findByTestId('AccountSecurity.Providers.Empty')
    expect(empty).toHaveTextContent(/no social sign-in providers configured/i)
  })
})
