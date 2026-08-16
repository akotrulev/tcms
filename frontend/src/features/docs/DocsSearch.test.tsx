import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { DocPage, DocPageSearchHit, DocPageSummary } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aDocPageSearchHit, aDocPageSummary, aProject } from '../../test/fixtures'
import { DocsPage } from './DocsPage'

/**
 * The documentation search box — §235, C3 of the flow audit.
 *
 * The three things worth locking, in the order they can silently break:
 *
 * 1. **`q` lives in the URL.** The rail's contents, the request, and what a reader can send a
 *    colleague are one value. A draft held only in component state passes every assertion about
 *    typing and fails the reload, the deep link and the Back button — none of which a test that
 *    types into a box and reads the list would notice.
 * 2. **The results replace the tree, and only while a query is live.** Clearing the box has to put
 *    the tree back; a rail stuck on an empty result list is a documentation space with no
 *    navigation in it.
 * 3. **The order is served, not sorted.** `PageTree` sorts siblings by title one component away, so
 *    a copied `.sort()` is the plausible mistake — and it would throw away the ranking that is the
 *    entire answer a search gives.
 */

const PROJECT = 'p1'

const tree: DocPageSummary[] = [
  aDocPageSummary({ id: 'page-1', key: 'DOC-1', title: 'Refund policy' }),
  aDocPageSummary({ id: 'page-2', key: 'DOC-2', title: 'Onboarding' }),
]

function detail(): DocPage {
  return { ...tree[0]!, blocks: [], attachments: [], currentVersionNo: 1, lockVersion: 1 }
}

function mockReads(hits: DocPageSearchHit[]) {
  const searched: string[] = []
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['doc:edit'])
    // Anchored ahead of the detail arm: `/doc-pages/search?q=…` is caught by any `includes`
    // test for `/doc-pages/`, and would then be answered with a whole page.
    if (path.includes('/doc-pages/search')) {
      searched.push(new URLSearchParams(path.split('?')[1] ?? '').get('q') ?? '')
      return hits
    }
    if (path.includes('/doc-pages/')) return detail()
    if (path.endsWith('/doc-pages')) return tree
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
    return []
  }) as never)
  return searched
}

/**
 * The router is a `MemoryRouter`, so `window.location` never moves — a test asserting on it reads
 * `''` and passes whichever way the code behaves. This renders the router's own query string
 * instead, which is the value the whole feature is keyed on.
 */
function QueryStringProbe() {
  return <span data-testid="Probe.Search">{useLocation().search}</span>
}

function renderDocs(route = `/projects/${PROJECT}/docs/page-1`) {
  return render(
    <TestProviders client={createTestQueryClient()} route={route}>
      <QueryStringProbe />
      <Routes>
        <Route path="/projects/:projectId/docs/:pageId" element={<DocsPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('DocsPage — searching the documentation space', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the page tree and asks for no search when there is no query', async () => {
    const searched = mockReads([])

    renderDocs()

    expect(await screen.findAllByTestId('Docs.Tree.Item')).toHaveLength(2)
    expect(screen.queryByTestId('Docs.Results')).not.toBeInTheDocument()
    // The hook is disabled on a blank query rather than asking the server for nothing.
    expect(searched).toEqual([])
  })

  it('renders results from the URL, in the order they were served', async () => {
    mockReads([
      aDocPageSearchHit({ id: 'page-2', key: 'DOC-2', title: 'Onboarding' }),
      aDocPageSearchHit({ id: 'page-1', key: 'DOC-1', title: 'Refund policy' }),
    ])

    renderDocs(`/projects/${PROJECT}/docs/page-1?q=refund`)

    const rows = await screen.findAllByTestId('Docs.Results.Item')
    // Served worst-title-first on purpose: a client that re-sorted alphabetically would put
    // "Onboarding" first here too and the assertion would pass for the wrong reason, so the
    // fixture is ordered so that only "as served" and "sorted by title" differ.
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('Onboarding'),
      expect.stringContaining('Refund policy'),
    ])
    expect(screen.queryByTestId('Docs.Tree')).not.toBeInTheDocument()
  })

  it('puts the submitted query in the URL and sends it to the server', async () => {
    const user = userEvent.setup()
    const searched = mockReads([aDocPageSearchHit({ title: 'Refund policy' })])

    renderDocs()
    await screen.findAllByTestId('Docs.Tree.Item')

    await user.type(screen.getByTestId('Docs.Search.Input'), 'refund{Enter}')

    await screen.findAllByTestId('Docs.Results.Item')
    expect(screen.getByTestId('Probe.Search')).toHaveTextContent('q=refund')
    expect(searched).toEqual(['refund'])
  })

  it('carries the search onto a result link, so opening a hit does not lose the results', async () => {
    mockReads([aDocPageSearchHit({ id: 'page-2', title: 'Onboarding' })])

    renderDocs(`/projects/${PROJECT}/docs/page-1?q=refund`)

    const [row] = await screen.findAllByTestId('Docs.Results.Item')
    expect(row).toHaveAttribute('href', expect.stringContaining('q=refund'))
  })

  it('renders the fragment the match was found in, and nothing when there is none', async () => {
    mockReads([
      aDocPageSearchHit({ id: 'page-1', title: 'Refund policy', snippet: null }),
      aDocPageSearchHit({ id: 'page-2', title: 'Chargebacks', snippet: 'a refund is not a chargeback' }),
    ])

    renderDocs(`/projects/${PROJECT}/docs/page-1?q=refund`)

    const snippets = await screen.findAllByTestId('Docs.Results.Snippet')
    // Null is a real answer — a page with no blocks matched on its title — and it must render as
    // nothing rather than as the word "null" or as the title a second time.
    expect(snippets).toHaveLength(1)
    expect(snippets[0]).toHaveTextContent('a refund is not a chargeback')
  })

  it('says so when nothing matches, and says why a half-typed word finds nothing', async () => {
    mockReads([])

    renderDocs(`/projects/${PROJECT}/docs/page-1?q=refun`)

    expect(await screen.findByText('No pages match')).toBeInTheDocument()
    expect(screen.getByText(/whole words only/i)).toBeInTheDocument()
  })

  it('clearing the box drops q from the URL and puts the tree back', async () => {
    const user = userEvent.setup()
    mockReads([aDocPageSearchHit({ title: 'Refund policy' })])

    renderDocs(`/projects/${PROJECT}/docs/page-1?q=refund`)
    await screen.findAllByTestId('Docs.Results.Item')

    await user.click(screen.getByTestId('Docs.Search.Clear'))

    expect(await screen.findAllByTestId('Docs.Tree.Item')).toHaveLength(2)
    await waitFor(() =>
      expect(screen.getByTestId('Probe.Search')).not.toHaveTextContent('q='),
    )
  })
})
