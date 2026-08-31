import { render, screen, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { BlockLink, DocPage, DocPageSummary } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, projectSettings } from '../../test/fixtures'
import { DocsPage } from './DocsPage'

/**
 * `documents.html`'s **"Linked from"** panel — the issues that build against this page (011 T6).
 *
 * The failure it exists to catch is not a missing feature. `BlockLink.sourceType` gained `ISSUE`
 * when `tracker` became a writer of `doc_link`, and the rail's existing section — headed *"Cases
 * linked here"* — was built from every link on every block. So an epic linking to a paragraph
 * would have been **counted and listed as a case**, under a heading saying so, with nothing
 * failing anywhere.
 *
 * The other half is `documents.html`'s opening state: *"Opened at §3.2 — the block ACME-101 links
 * to"*. That is what the epic's "Open at this block" button sends, and a link that arrives at the
 * top of a five-section page with nothing marked is a link that did not work.
 */

const PROJECT = 'p1'

const summary: DocPageSummary = {
  id: 'page-1',
  key: 'DOC-1',
  title: 'Checkout specification',
  slug: 'checkout-specification',
  parentId: null,
  status: 'PUBLISHED',
  updatedAt: '2026-08-01T00:00:00Z',
}

const caseLink: BlockLink = {
  id: 'link-case',
  sourceType: 'TEST_CASE',
  sourceId: 'case-9',
  sourceKey: 'TC-42',
  sourceTitle: 'Guest order, valid card',
  relationship: 'VERIFIES',
}

const issueLink: BlockLink = {
  id: 'link-issue',
  sourceType: 'ISSUE',
  sourceId: 'issue-1',
  sourceKey: 'ACME-101',
  sourceTitle: 'Checkout rebuild',
  relationship: 'DERIVED_FROM',
}

function page(links: BlockLink[]): DocPage {
  return {
    ...summary,
    blocks: [
      {
        id: 'block-heading',
        position: 0,
        blockType: 'heading',
        level: 2,
        text: '3. The guest flow',
        rows: null,
        attachmentId: null,
        links: [],
        coverage: null,
      },
      {
        id: 'block-1',
        position: 1,
        blockType: 'paragraph',
        level: null,
        text: 'A shopper who has never signed in must be able to complete an order.',
        rows: null,
        attachmentId: null,
        links,
        coverage: null,
      },
    ],
    attachments: [],
    currentVersionNo: 6,
    lockVersion: 1,
  }
}

function mockReads(links: BlockLink[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['project:read'])
    if (path.includes('/doc-pages/')) return page(links)
    if (path.endsWith('/doc-pages')) return [summary]
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Acme Storefront' })
    if (/^\/projects\/[^/]+\/settings$/.test(path)) return projectSettings({ projectId: PROJECT })
    return []
  }) as never)
}

function renderPage(search = '') {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/docs/page-1${search}`}>
      <Routes>
        <Route path="/projects/:projectId/docs/:pageId" element={<DocsPage />} />
      </Routes>
    </TestProviders>,
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('Linked from', () => {
  it('lists the issues that link into this page, with the section they landed on', async () => {
    mockReads([issueLink])
    renderPage()

    const panel = await screen.findByTestId('Docs.Content.LinkedFrom')
    const item = within(panel).getByTestId('Docs.Content.LinkedFrom.Item')
    expect(item).toHaveTextContent('ACME-101')
    expect(item).toHaveTextContent('Checkout rebuild')
    // The section is the nearest preceding heading — `BlockLink` carries none of its own, and
    // "which paragraph" is what a reader following a link needs.
    expect(item).toHaveTextContent('3. The guest flow')
    // By key, with no project segment: a key is unique in the only scope RLS lets a caller see.
    expect(item).toHaveAttribute('href', '/issues/ACME-101')
  })

  it('does not count an issue as a case', async () => {
    /*
      The defect this panel's existence repairs. The rail's "Cases linked here" was built from
      every link on every block, so an issue link would have been listed there — a count of
      coverage that included work that verifies nothing.
    */
    mockReads([caseLink, issueLink])
    renderPage()

    await screen.findByTestId('Docs.Content.LinkedFrom')
    const cases = screen.getByText('Cases linked here').closest('section')
    expect(cases).not.toBeNull()
    expect(cases).toHaveTextContent('TC-42')
    expect(cases).not.toHaveTextContent('ACME-101')
  })

  it('states the rule that connects this end to the issue’s own stale flag', async () => {
    // The same `stale` seen from the other end. The reader about to edit a paragraph is the one
    // who most needs to know what it costs.
    mockReads([issueLink])
    renderPage()

    expect(await screen.findByTestId('Docs.Content.LinkedFrom')).toHaveTextContent(
      'changed since linked',
    )
  })

  it('renders no panel at all when nothing links here', async () => {
    // Absent rather than an empty box: a "Linked from: none" card on a page nobody has built
    // against says nothing a reader can act on.
    mockReads([caseLink])
    renderPage()

    await screen.findByTestId('Docs.Content')
    expect(screen.queryByTestId('Docs.Content.LinkedFrom')).toBeNull()
  })
})

describe('Opening at a block', () => {
  it('marks the block the address named', async () => {
    // What the epic's "Open at this block" sends. Arriving at the top of a five-section page with
    // nothing marked is a link that did not work.
    mockReads([issueLink])
    renderPage('?block=block-1')

    const opened = await screen.findByTestId('Docs.Block.Opened')
    expect(opened).toHaveTextContent('A shopper who has never signed in')
    expect(within(opened).getByTestId('Docs.Block.Opened.Marker')).toBeInTheDocument()
  })

  it('marks nothing when the address names no block', async () => {
    mockReads([issueLink])
    renderPage()

    await screen.findByTestId('Docs.Content')
    expect(screen.queryByTestId('Docs.Block.Opened')).toBeNull()
  })
})
