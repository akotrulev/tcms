import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { aCaseSearchHit, aProject } from '../../test/fixtures'
import { createTestQueryClient } from '../../test/queryClient'
import { drivingAFailedRead } from '../../test/renderFailures'
import { TestProviders } from '../../test/TestProviders'
import { CaseSearchPage } from './CaseSearchPage'

/**
 * The cross-project case search screen (§249).
 *
 * The arms are the four places this screen can mislead: asking the server for a search it will
 * refuse, reporting a client-side count instead of the server's, rendering "no results" at somebody
 * who can see no projects, and losing the search when the URL is shared.
 */

const HITS = [
  aCaseSearchHit({ id: 'c1', key: 'TC-2', keySeq: 2, title: 'Partial refund', projectId: 'p1', projectKey: 'ALPHA', projectName: 'Alpha' }),
  aCaseSearchHit({ id: 'c2', key: 'TC-5', keySeq: 5, title: 'Refund reversal', projectId: 'p2', projectKey: 'BETA', projectName: 'Beta' }),
]

/**
 * Every read this screen makes.
 *
 * The search arm is anchored on the whole sub-resource so it is not swallowed by a `/cases` prefix
 * — the fixtures rule, and it matters here because the path IS a sub-resource of `/cases`.
 */
function mockReads({
  total = 2,
  items = HITS,
  projects = [aProject({ id: 'p1' }), aProject({ id: 'p2' })],
}: { total?: number; items?: typeof HITS; projects?: unknown[] } = {}) {
  const paths: string[] = []
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    paths.push(path)
    if (/^\/cases\/search(\?|$)/.test(path)) {
      return { items, page: 0, size: 25, total }
    }
    if (path === '/projects') return projects
    throw new Error(`unmocked read: ${path}`)
  }) as never)
  return paths
}

/**
 * The router's own query string, readable by an assertion.
 *
 * `TestProviders` mounts a `MemoryRouter`, which keeps the location in memory and never touches
 * `window.location` — so asserting against the address bar reads an empty string whatever the
 * screen did. This is what "the filters live in the URL" can actually be observed as here.
 */
function Where() {
  return <span data-testid="Where">{useLocation().search}</span>
}

function renderPage(route = '/search') {
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route={route}>
      <CaseSearchPage />
      <Where />
    </TestProviders>,
  )
}

describe('CaseSearchPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('asks for nothing until the search is one the server would answer', async () => {
    const paths = mockReads()
    renderPage()

    // The endpoint 400s a search naming neither q, ownerId nor tag. Rendering the box and then
    // being refused is `ui-rules` §4's render-then-reject, so the hook must not fire at all.
    await screen.findByTestId('CaseSearch.Prompt')
    expect(paths.filter((path) => path.startsWith('/cases/search'))).toHaveLength(0)
  })

  it('does not ask for a one-character search either', async () => {
    const paths = mockReads()
    renderPage('/search?q=r')

    await screen.findByTestId('CaseSearch.Prompt')
    expect(paths.filter((path) => path.startsWith('/cases/search'))).toHaveLength(0)
  })

  it('searches on a case key however short it is', async () => {
    // TC-4 is four characters but is a whole identifier, not a substring — the server exempts it
    // from the minimum and so must the client, or the most common real request is refused locally.
    const paths = mockReads()
    renderPage('/search?q=TC-4')

    await waitFor(() =>
      expect(paths.some((path) => path.startsWith('/cases/search'))).toBe(true),
    )
  })

  it('renders every hit with the project it belongs to', async () => {
    mockReads()
    renderPage('/search?q=refund')

    const rows = await screen.findAllByTestId('CaseSearch.Row')
    expect(rows).toHaveLength(2)
    // The project is on the row and not in a hideable column: the reader does not know which
    // project a hit is from, which is the premise of the screen.
    expect(within(rows[0]).getByText('ALPHA')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Alpha')).toBeInTheDocument()
    expect(within(rows[1]).getByText('BETA')).toBeInTheDocument()
  })

  it('links a hit into its own project, not into the one on screen', async () => {
    mockReads()
    renderPage('/search?q=refund')

    const links = await screen.findAllByTestId('CaseSearch.Row.Link')
    expect(links[0]).toHaveAttribute('href', '/projects/p1/cases/c1')
    expect(links[1]).toHaveAttribute('href', '/projects/p2/cases/c2')
  })

  it("reports the SERVER's total, not the number of rows on the page", async () => {
    // The whole point of §249 is that `total` is computed over the same narrowed set as the page.
    // A screen that counted `items.length` would report 2 of 40 as "2" and quietly discard the
    // one number the area exists to make trustworthy.
    mockReads({ total: 40 })
    renderPage('/search?q=refund')

    expect(await screen.findByTestId('CaseSearch.Count')).toHaveTextContent('40 matching cases')
  })

  it('distinguishes "nothing matched" from "you can see no projects"', async () => {
    // The same HTTP response — 200 with total 0 — and two different sentences. Getting this wrong
    // makes a permissions problem look like a product with no data in it.
    mockReads({ total: 0, items: [], projects: [] })
    renderPage('/search?q=refund')

    expect(await screen.findByTestId('CaseSearch.NoProjects')).toBeInTheDocument()
    expect(screen.queryByTestId('CaseSearch.NoMatch')).not.toBeInTheDocument()
  })

  it('says "no case matches" when the reader can open projects but nothing matched', async () => {
    mockReads({ total: 0, items: [] })
    renderPage('/search?q=refund')

    expect(await screen.findByTestId('CaseSearch.NoMatch')).toBeInTheDocument()
    expect(screen.queryByTestId('CaseSearch.NoProjects')).not.toBeInTheDocument()
  })

  it('keeps the search in the URL, so a result set can be shared', async () => {
    const user = userEvent.setup()
    mockReads()
    renderPage()

    await user.type(await screen.findByTestId('CaseSearch.Query'), 'refund')

    // Read back off the ROUTER rather than off component state: a search nobody can paste to a
    // colleague is half a feature, and this is the one screen whose whole output gets shared.
    await waitFor(() =>
      expect(screen.getByTestId('Where')).toHaveTextContent('q=refund'),
    )
  })

  it('renders the failure in place when the search fails', async () => {
    drivingAFailedRead()
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (/^\/cases\/search(\?|$)/.test(path)) throw new Error('boom')
      if (path === '/projects') return [aProject({ id: 'p1' })]
      throw new Error(`unmocked read: ${path}`)
    }) as never)
    renderPage('/search?q=refund')

    expect(await screen.findByTestId('CaseSearch.Error')).toBeInTheDocument()
  })
})
