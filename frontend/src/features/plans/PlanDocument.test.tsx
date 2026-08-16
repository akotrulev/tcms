import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { DocPage, PermissionKey, TestPlan } from '../../api/types'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aDocPageSummary, aProject, planStatistics, projectSettings } from '../../test/fixtures'
import { PlanStatisticsPage } from './PlanStatisticsPage'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * The **Business requirements** tab (§97 phase 5, D4′) — a page the plan *adopts*, shown read-only.
 *
 * What is locked here:
 *
 *   1. **The empty state is a picker, not an error.** `usePlanDocument` turns the server's 404 into
 *      `null` precisely so a plan with no document — the ordinary first state — renders an
 *      invitation rather than a global "Something didn't load".
 *   2. **A page another plan documents is offered and disabled**, never filtered out (§31). Hidden,
 *      it reads as deleted, and the reader goes looking for it in the documentation tree.
 *   3. **The tab renders no editor.** §93's in-place block editing left with this phase; a text box
 *      here would be a second writer for one page, on its own lock version.
 *   4. **Unlink says it keeps the page**, because the destructive neighbour is one screen away and
 *      the two words are nearly the same.
 *   5. **The 409 lands in the panel**, not in a corner toast: it names the plan already holding the
 *      page, which is an answer about the row just chosen.
 */

const PROJECT = 'p1'
const PLAN = 'tp1'

function planBody(): TestPlan {
  return {
    id: PLAN,
    key: 'TP-4',
    keySeq: 4,
    name: 'Release 4.4 regression',
    status: 'DRAFT',
    milestoneName: null,
    milestoneDate: null,
    caseCount: 12,
    passRate: null,
    coverage: null,
    description: null,
    entryCriteria: [],
    exitCriteria: [],
    configurations: [],
    suiteIds: [],
    caseIds: [],
    tags: [],
    sections: [],
    scopeText: null,
    outOfScopeText: null,
    currentVersionNo: 1,
    lockVersion: 7,
    archived: false,
  }
}

const PAGES = [
  aDocPageSummary({ id: 'dp1', key: 'DOC-1', title: 'Payments v4 requirements', slug: 'payments-v4' }),
  // Spoken for by ANOTHER plan — the row assertion 2 is about.
  aDocPageSummary({
    id: 'dp2',
    key: 'DOC-2',
    title: 'Refunds v2 requirements',
    slug: 'refunds-v2',
    subjectType: 'TEST_PLAN',
    subjectId: 'tp-other',
  }),
]

const LINKED_PAGE: DocPage = {
  id: 'dp1',
  key: 'DOC-1',
  title: 'Payments v4 requirements',
  slug: 'payments-v4',
  status: 'PUBLISHED',
  parentId: null,
  subjectType: 'TEST_PLAN',
  subjectId: PLAN,
  currentVersionNo: 3,
  lockVersion: 2,
  updatedAt: '2026-08-01T09:00:00Z',
  blocks: [
    {
      id: 'b1',
      position: 0,
      blockType: 'heading',
      level: 1,
      text: 'Scope of the change',
      links: [],
      coverage: null,
    },
    {
      id: 'b2',
      position: 1,
      blockType: 'paragraph',
      level: null,
      text: 'Refunds move to the new ledger.',
      links: [],
      coverage: null,
    },
  ],
  attachments: [],
}

/**
 * @param document what `GET /plans/{id}/document` answers — a page, or a 404 for a plan with none.
 */
function mockReads(document: DocPage | null, permissions: PermissionKey[] = ['project:read', 'doc:edit']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes(`/plans/${PLAN}/document`)) {
      if (document) return document
      throw new ApiError(404, 'Not found', null, null, null, "That plan's document")
    }
    if (path.includes('/statistics')) return planStatistics({ planId: PLAN })
    if (path.includes('/doc-pages')) return PAGES
    if (path.includes(`/plans/${PLAN}`)) return planBody()
    if (path.includes('/settings')) return projectSettings({ projectId: PROJECT })
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Banking' })
    return []
  }) as never)
}

const sessionRecovery = {
  reportUnauthorized: () => {},
  registerUnsavedWork: () => () => {},
  hasUnsavedWork: () => false,
  registerReauthHandler: () => () => {},
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/plans/${PLAN}?tab=requirements`}
    >
      <SessionRecoveryContext.Provider value={sessionRecovery}>
        <Routes>
          <Route path="/projects/:projectId/plans/:planId" element={<PlanStatisticsPage />} />
        </Routes>
      </SessionRecoveryContext.Provider>
    </TestProviders>,
  )
}

describe('the Business requirements tab', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('invites a link when the plan has no document, rather than reporting a failure', async () => {
    mockReads(null)
    renderPage()

    expect(await screen.findByTestId('PlanDocument.Empty')).toBeInTheDocument()
    expect(screen.getByTestId('PlanDocument.PagePicker')).toBeInTheDocument()
  })

  it('offers a page another plan documents, disabled, rather than hiding it', async () => {
    mockReads(null)
    renderPage()

    await screen.findByTestId('PlanDocument.PagePicker')
    await userEvent.click(screen.getByRole('combobox', { name: /page/i }))

    const spokenFor = await screen.findByRole('option', { name: /Refunds v2 requirements/ })
    expect(spokenFor).toHaveAttribute('aria-disabled', 'true')
    // The free one is the pair that makes the assertion above mean "disabled" and not "the list
    // renders nothing selectable".
    expect(screen.getByRole('option', { name: /Payments v4 requirements/ }))
      .not.toHaveAttribute('aria-disabled', 'true')
  })

  it('adopts the chosen page by id', async () => {
    mockReads(null)
    const put = vi.spyOn(api, 'put').mockResolvedValue(LINKED_PAGE as never)
    renderPage()

    await screen.findByTestId('PlanDocument.PagePicker')
    await userEvent.click(screen.getByRole('combobox', { name: /page/i }))
    await userEvent.click(await screen.findByRole('option', { name: /Payments v4 requirements/ }))
    await waitFor(() => expect(screen.getByTestId('PlanDocument.Link')).toBeEnabled())
    await userEvent.click(screen.getByTestId('PlanDocument.Link'))

    await waitFor(() => expect(put).toHaveBeenCalled())
    const [path, body] = put.mock.calls[0] as unknown[]
    expect(path).toBe(`/projects/${PROJECT}/plans/${PLAN}/document`)
    expect(body).toEqual({ pageId: 'dp1' })
  })

  it('renders the 409 in the panel, naming what refused it', async () => {
    // The refused write is the subject: the 409's sentence is what is being rendered.
    drivingAFailedWrite()
    mockReads(null)
    vi.spyOn(api, 'put').mockRejectedValue(
      new ApiError(
        409,
        'That page is already the document of TP-9. Unlink it there first.',
        null,
        null,
        null,
        'Already linked',
      ),
    )
    renderPage()

    await screen.findByTestId('PlanDocument.PagePicker')
    await userEvent.click(screen.getByRole('combobox', { name: /page/i }))
    await userEvent.click(await screen.findByRole('option', { name: /Payments v4 requirements/ }))
    await waitFor(() => expect(screen.getByTestId('PlanDocument.Link')).toBeEnabled())
    await userEvent.click(screen.getByTestId('PlanDocument.Link'))

    expect(await screen.findByTestId('PlanDocument.LinkError')).toHaveTextContent(/TP-9/)
  })

  it('shows the linked page read-only, with no way to edit it here', async () => {
    mockReads(LINKED_PAGE)
    renderPage()

    expect(await screen.findByText('Refunds move to the new ledger.')).toBeInTheDocument()
    // §93's editor is gone: a text box on this tab would be a second writer for one page, with its
    // own lock version. `queryAllBy` because the tab strip and the header hold no inputs either —
    // the assertion is that the DOCUMENT renders none.
    const body = screen.getByTestId('PlanDocument.Body')
    expect(body.querySelectorAll('input, textarea')).toHaveLength(0)
    expect(screen.getByTestId('PlanDocument.OpenPage')).toHaveAttribute(
      'href',
      `/projects/${PROJECT}/docs/dp1`,
    )
  })

  it('says the unlink keeps the page, and sends a DELETE', async () => {
    mockReads(LINKED_PAGE)
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPage()

    const unlink = await screen.findByTestId('PlanDocument.Unlink')
    // The wording is the assertion. Delete is one screen away and does something else entirely, and
    // a reader who mistakes the two loses a page's whole history.
    expect(unlink).toHaveTextContent(/keeps the page/i)

    await waitFor(() => expect(unlink).toBeEnabled())
    await userEvent.click(unlink)

    await waitFor(() => expect(del).toHaveBeenCalledWith(`/projects/${PROJECT}/plans/${PLAN}/document`))
  })

  it('disables both writes for a reader without doc:edit', async () => {
    mockReads(LINKED_PAGE, ['project:read'])
    renderPage()

    await waitFor(() => expect(screen.getByTestId('PlanDocument.Unlink')).toBeDisabled())
    // Still readable — the page is the point of the tab, and gating the whole body on a write key
    // would hide a specification from everybody who only reads specifications.
    expect(screen.getByText('Refunds move to the new ledger.')).toBeInTheDocument()
  })
})
