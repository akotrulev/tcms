import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { BlockLink, DocPage, DocPageSummary } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { DocsPage } from './DocsPage'

/**
 * The block-link screen — §172, and the gap §169 recorded: `useLinkBlock` and `useUnlinkBlock`
 * existed with **no caller anywhere in `src/`**, so `doc_link` had an endpoint, a read model, a
 * coverage marker and a case-end panel, and no way to create one from the documentation side.
 *
 * Three of the five tests here are about the payload rather than the rendering, because the
 * rendering is the half a green build already covers: the component compiled and typechecked while
 * the hook it should have called had no caller at all.
 */

const PROJECT = 'p1'

const summary: DocPageSummary = {
  id: 'page-1',
  key: 'DOC-1',
  title: 'Refund policy',
  parentId: null,
  status: 'PUBLISHED',
  updatedAt: '2026-08-01T00:00:00Z',
} as DocPageSummary

const link: BlockLink = {
  id: 'link-1',
  sourceType: 'TEST_CASE',
  sourceId: 'case-9',
  sourceKey: 'TC-42',
  sourceTitle: 'Refund is refused after 30 days',
  relationship: 'REFERENCES',
}

function page(links: BlockLink[]): DocPage {
  return {
    ...summary,
    blocks: [
      {
        id: 'block-1',
        position: 1,
        blockType: 'paragraph',
        level: null,
        text: 'A refund is issued within 30 days of purchase.',
        rows: null,
        attachmentId: null,
        links,
        coverage: null,
      },
    ],
    attachments: [],
    currentVersionNo: 1,
    lockVersion: 1,
  } as unknown as DocPage
}

/**
 * The page is re-read after every write, so the reads are a function of a mutable variable rather
 * than a fixed value: `useLinkBlock` invalidates the page key, and a fixture that answered the same
 * body forever would let a hook that never invalidated pass this file.
 */
let served: BlockLink[] = []

function mockReads(keys: string[]) {
  served = []
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(keys)
    if (path.includes('/doc-pages/')) return page(served)
    if (path.endsWith('/doc-pages')) return [summary]
    if (path.includes('/cases')) {
      return {
        items: [{ id: 'case-9', key: 'TC-42', title: 'Refund is refused after 30 days' }],
        page: 0,
        size: 20,
        total: 1,
      }
    }
    if (path.endsWith('/suites')) return [{ id: 'suite-3', key: 'TS-3', name: 'Refunds' }]
    if (path.startsWith('/projects/')) return { id: PROJECT, name: 'Banking' }
    return []
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/docs/page-1`}>
      <Routes>
        <Route path="/projects/:projectId/docs/:pageId" element={<DocsPage />} />
      </Routes>
    </TestProviders>,
  )
}

/** `useCan` is fail-closed while pending, so every enabled assertion needs the answer to land. */
async function linkButtonEnabled() {
  await waitFor(() => expect(screen.getByTestId('Docs.Block.Links.AddButton')).toBeEnabled())
}

describe('a block carries the links that claim to verify it', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('names the source and what it claims, from the shared relationship map', async () => {
    mockReads(['project:read'])
    served = [link]
    renderPage()

    const item = await screen.findByTestId('Docs.Block.Links.Item')
    // "References", not REFERENCES: the three are not synonyms, and the wire value lower-cased is
    // what a second screen spells slightly differently.
    expect(item).toHaveTextContent('TC-42')
    expect(item).toHaveTextContent('Refund is refused after 30 days')
    expect(item).toHaveTextContent('References')
    expect(within(item).getByRole('link')).toHaveAttribute(
      'href',
      `/projects/${PROJECT}/cases/case-9`,
    )
  })

  it('offers no way to link or unlink without doc:edit', async () => {
    mockReads(['project:read'])
    served = [link]
    renderPage()

    await screen.findByTestId('Docs.Block.Links.Item')
    // Disabled rather than absent (§31) — a Viewer sees that the product does this.
    await waitFor(() => expect(screen.getByTestId('Docs.Block.Links.AddButton')).toBeDisabled())
    // Remove is absent rather than disabled, matching the case end's half of the same act.
    expect(screen.queryByTestId('Docs.Block.Links.Remove')).not.toBeInTheDocument()
  })

  it('posts the chosen case to the block it was opened from, and shows it', async () => {
    const user = userEvent.setup()
    mockReads(['project:read', 'doc:edit'])
    const post = vi.spyOn(api, 'post').mockImplementation((async () => {
      served = [link]
      return link
    }) as never)
    renderPage()
    await linkButtonEnabled()

    await user.click(screen.getByTestId('Docs.Block.Links.AddButton'))
    await user.click(await screen.findByRole('combobox', { name: 'Case' }))
    await user.click(await screen.findByRole('option', { name: /TC-42/ }))
    await user.click(screen.getByTestId('Docs.Block.LinkDialog.Confirm'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        `/projects/${PROJECT}/doc-pages/page-1/blocks/block-1/links`,
        { sourceType: 'TEST_CASE', sourceId: 'case-9', relationship: 'VERIFIES' },
      ),
    )
    // The block's own list, not the create's answer: `DocBlock.links` is read straight off
    // `doc_link` in the same request, so invalidating is right here and wrong on the case end,
    // where the panel is served from a projection an event refreshes (§169).
    expect(await screen.findByTestId('Docs.Block.Links.Item')).toHaveTextContent('TC-42')
  })

  it('sends the kind the toggle holds, not the picker that was touched last', async () => {
    // The defect the toggle exists to prevent: pick a case, change your mind, pick a suite. A form
    // inferring the kind from whichever control has a value would send TEST_CASE with a suite's id
    // — a real row of the wrong table, so the server's project check passes and the link points at
    // something nobody chose.
    const user = userEvent.setup()
    mockReads(['project:read', 'doc:edit'])
    const post = vi.spyOn(api, 'post').mockResolvedValue(link as never)
    renderPage()
    await linkButtonEnabled()

    await user.click(screen.getByTestId('Docs.Block.Links.AddButton'))
    await user.click(await screen.findByRole('combobox', { name: 'Case' }))
    await user.click(await screen.findByRole('option', { name: /TC-42/ }))
    await user.click(screen.getByTestId('Docs.Block.LinkDialog.Kind.SUITE'))
    await user.click(screen.getByRole('combobox', { name: 'Suite' }))
    await user.click(await screen.findByRole('option', { name: /TS-3/ }))
    await user.click(screen.getByTestId('Docs.Block.LinkDialog.Confirm'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(expect.any(String), {
        sourceType: 'SUITE',
        sourceId: 'suite-3',
        relationship: 'VERIFIES',
      }),
    )
  })

  it('withdraws a link by its own id, on the page rather than the block', async () => {
    const user = userEvent.setup()
    mockReads(['project:read', 'doc:edit'])
    served = [link]
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Docs.Block.Links.Remove')).toBeInTheDocument())
    await user.click(screen.getByTestId('Docs.Block.Links.Remove'))

    // `.../doc-pages/{pageId}/links/{linkId}` — the block id is not in the path, because a link
    // row is unique in the page and the delete names the row rather than re-deriving it.
    await waitFor(() =>
      expect(del).toHaveBeenCalledWith(`/projects/${PROJECT}/doc-pages/page-1/links/link-1`),
    )
  })
})
