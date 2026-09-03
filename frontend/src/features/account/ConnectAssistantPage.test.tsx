import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { anAssistant, aProject } from '../../test/fixtures'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { ConnectAssistantPage } from './ConnectAssistantPage'

/**
 * `assistant-connect.html`, and **the token-shown-once path is the test the breakdown asks for.**
 *
 * The property being pinned is not "the secret renders" — it is that the secret exists *only* in
 * this component's state. So the second case unmounts the page and mounts a fresh one, which is
 * what navigating away and back does, and asserts the snippet is gone and the form is back.
 * A secret that survived that would mean it had reached the query cache, which is the one mistake
 * on this screen that is a real disclosure rather than a defect.
 */

const PROJECT = aProject({ id: 'p1', key: 'ACME', name: 'Acme Storefront' })

function mockReads() {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path === '/projects') return [PROJECT]
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage(client = createTestQueryClient()) {
  return render(
    <TestProviders client={client} route="/account/assistants/new">
      <ConnectAssistantPage />
    </TestProviders>,
  )
}

describe('Connecting an assistant', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sends the name and a null scope for “every project I can see”', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue({
      assistant: anAssistant(),
      secret: 'tcms_pat_secret',
    } as never)
    renderPage()

    await userEvent.click(screen.getByTestId('ConnectAssistant.Submit'))
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/me/assistants',
        { name: 'claude-code', projectId: null },
        expect.anything(),
      ),
    )
  })

  it('shows the secret once, and a fresh mount cannot get it back', async () => {
    mockReads()
    vi.spyOn(api, 'post').mockResolvedValue({
      assistant: anAssistant({ projectKey: 'ACME' }),
      secret: 'tcms_pat_7f3c9a21',
    } as never)

    // The same client across both mounts — the point is that the secret is not in the cache, so
    // sharing the cache is what makes the second assertion mean anything.
    const client = createTestQueryClient()
    const first = renderPage(client)

    await userEvent.click(screen.getByTestId('ConnectAssistant.Submit'))
    expect(await screen.findByTestId('ConnectAssistant.Command.Value')).toHaveTextContent(
      'tcms_pat_7f3c9a21',
    )
    expect(screen.getByTestId('ConnectAssistant.Warning')).toBeInTheDocument()

    first.unmount()
    renderPage(client)

    expect(screen.queryByTestId('ConnectAssistant.Command.Value')).toBeNull()
    expect(screen.getByTestId('ConnectAssistant.Form')).toBeInTheDocument()
  })

  it('scopes to one project when one is chosen', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue({
      assistant: anAssistant(),
      secret: 's',
    } as never)
    renderPage()

    await waitFor(() => expect(screen.getByRole('option', { name: 'Acme Storefront' })).toBeInTheDocument())
    await userEvent.selectOptions(screen.getByTestId('ConnectAssistant.Scope'), 'p1')
    await userEvent.click(screen.getByTestId('ConnectAssistant.Submit'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/me/assistants',
        { name: 'claude-code', projectId: 'p1' },
        expect.anything(),
      ),
    )
  })
})
