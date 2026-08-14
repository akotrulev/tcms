import { render, screen, waitFor } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { DocPage, DocPageSummary, PermissionKey } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject } from '../../test/fixtures'
import { DocsPage } from './DocsPage'

/**
 * The documentation space's **attachment panel wiring** — #49 phase 2.
 *
 * `AttachmentList.test.tsx` proves the panel gates on whatever key it is handed. This proves the
 * page hands it `doc:edit`, and the wrong answer is the plausible one: this screen already renders
 * a `doc:publish` gate three buttons away, and a Test Engineer holds the first and **not** the
 * second (`PageActions`' own header says so). A panel that copied the neighbouring gate would
 * compile, render, and refuse a diagram to exactly the role that writes the specification.
 *
 * So the fixture is a caller with `doc:edit` and no `doc:publish`: Publish is disabled and Attach
 * is not, in one render. Only the right key produces both.
 */

const PROJECT = 'p1'

const summary: DocPageSummary = {
  id: 'page-1',
  key: 'DOC-1',
  title: 'Refund policy',
  slug: 'refund-policy',
  parentId: null,
  status: 'DRAFT',
  updatedAt: '2026-08-01T00:00:00Z',
}

function page(attachments: DocPage['attachments']): DocPage {
  return {
    ...summary,
    blocks: [],
    attachments,
    currentVersionNo: 1,
    lockVersion: 1,
  }
}

function mockReads(keys: PermissionKey[], attachments: DocPage['attachments'] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(keys)
    if (path.includes('/doc-pages/')) return page(attachments)
    if (path.endsWith('/doc-pages')) return [summary]
    if (path.startsWith('/projects/')) return aProject({ id: PROJECT, name: 'Banking' })
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

/**
 * Wait for the permission answer. `useCan` is fail-closed while pending, so every disabled
 * assertion below is satisfied by the pending state alone without this. Edit is the barrier —
 * `doc:edit` is granted in both fixtures here, so it flips exactly once the answer lands.
 */
async function permissionsResolved() {
  await waitFor(() => expect(screen.getByTestId('Docs.Content.Edit')).toBeEnabled())
}

describe('DocsPage — a page carries files, gated on doc:edit', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers Attach to an author who may not publish', async () => {
    mockReads(['project:read', 'doc:edit'])
    renderPage()
    await permissionsResolved()

    await waitFor(() => expect(screen.getByTestId('Docs.Attachments.Attach')).toBeEnabled())
    // Same render, same caller, opposite verdict — which is what proves the two gates read
    // different keys rather than sharing one by accident.
    expect(screen.getByTestId('Docs.Content.Status.PUBLISHED')).toBeDisabled()
  })

  it('refuses Attach to a reader with neither key', async () => {
    // The guard: without it the test above passes against a panel gated on nothing.
    mockReads(['project:read'])
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Docs.Attachments')).toBeInTheDocument())
    expect(screen.getByTestId('Docs.Attachments.Attach')).toBeDisabled()
  })

  it('lists the page files below the prose rather than inside it', async () => {
    // The panel is its own region, not a block in the flow: a file list dropped between paragraphs
    // is a file list a reader scanning for the next paragraph has to step over.
    mockReads(['project:read', 'doc:edit'], [
      {
        id: 'att-1',
        filename: 'refund-flow.svg',
        contentType: 'image/svg+xml',
        sizeBytes: 4_096,
        url: `/api/v1/projects/${PROJECT}/doc-pages/page-1/attachments/att-1/content`,
        uploadedAt: '2026-08-02T00:00:00Z',
        uploadedBy: null,
      },
    ] as DocPage['attachments'])
    renderPage()

    await waitFor(() =>
      expect(screen.getByTestId('Docs.Attachments.Download')).toHaveTextContent('refund-flow.svg'),
    )
    expect(screen.getByTestId('Docs.Attachments.Row')).toBeInTheDocument()
  })
})
