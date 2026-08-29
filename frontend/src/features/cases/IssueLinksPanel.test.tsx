import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey } from '../../api/types'
import { anIssueLink } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { IssueLinksPanel } from './IssueLinksPanel'

/**
 * The case's issue links (004 Phase 3).
 *
 * What this file is really testing is D18: **the rows are ours.** A binding deleted, a tracker
 * down, a token expired — none of it touches the list, the hrefs or the panel. Only what a chip
 * can *say* about a row depends on somebody else's server, and a test that let the two blur would
 * not notice a panel that started failing whenever Jira did.
 */

const PROJECT = 'p1'
const CASE = 'c1'

function mockApi(keys: PermissionKey[], links = [anIssueLink()], issueBody?: unknown) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(keys)
    if (path.endsWith('/issue-links')) return links
    if (path.includes('/tracker/issues')) {
      if (issueBody === undefined) throw new Error('the tracker is down')
      return issueBody
    }
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPanel() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/cases/${CASE}`}>
      <IssueLinksPanel projectId={PROJECT} caseId={CASE} />
    </TestProviders>,
  )
}

describe('IssueLinksPanel', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the links even when the binding is gone (D18)', async () => {
    mockApi(['case:read'], [anIssueLink({ bindingId: null })])
    renderPanel()

    expect(await screen.findByTestId('CaseDetail.IssueLinks.Row')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /10042/ })).toHaveAttribute(
      'href',
      'https://acme.atlassian.test/browse/PAY-991',
    )
  })

  it('says nothing is linked, rather than nothing loaded, when there are no rows', async () => {
    mockApi(['case:read'], [])
    renderPanel()
    expect(await screen.findByTestId('CaseDetail.IssueLinks.Region.Empty')).toBeInTheDocument()
  })

  it('disables linking for a caller without case:edit rather than hiding it', async () => {
    mockApi(['case:read'], [])
    renderPanel()
    expect(await screen.findByTestId('CaseDetail.IssueLinks.AddButton')).toBeDisabled()
  })

  it('offers the way to connect instead of a search box that can never answer', async () => {
    // The typeahead is the only way in — what gets stored is an id and a binding, neither of which
    // a person can type. A viewer who has not connected therefore cannot link, and the dialog says
    // so with the remedy rather than presenting a box that returns nothing for ever.
    mockApi(['case:read', 'case:edit'], [], { state: 'NOT_CONNECTED', issues: [] })
    renderPanel()

    // Enabled, not merely present: PermissionGate fails closed while `/me/permissions` is in
    // flight, so the button exists — and is disabled for a reason this test is not about — before
    // the answer lands.
    await waitFor(() =>
      expect(screen.getByTestId('CaseDetail.IssueLinks.AddButton')).toBeEnabled(),
    )
    await userEvent.click(screen.getByTestId('CaseDetail.IssueLinks.AddButton'))
    await userEvent.type(await screen.findByTestId('CaseDetail.IssueLinks.Search'), 'rounding')

    expect(await screen.findByTestId('CaseDetail.IssueLinks.Connect')).toBeInTheDocument()
  })
})
