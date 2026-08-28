import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Suite } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { projectSettings } from '../../test/fixtures'
import { SuitePanel } from './SuitePanel'

/**
 * The selected suite's standing — §227 / audit A7.
 *
 * **What this closes is a rendering gap, not a permission one.** `suite:review`, `suite:approve`
 * and `reviewEnabledSuite` have existed since §74/§81 and the ladder has been drivable from the
 * rail's kebab for as long; what had no surface was the *result*. So the assertions here are about
 * the panel appearing at all, and about the one thing it must not do — claim a review exists on a
 * project that has review switched off for suites.
 *
 * The `reviewEnabledSuite: false` half is the one that would ship silently: `ReviewPanel` decides
 * that for itself, so a panel that rendered its own "Review" heading around it would show an empty
 * section on every project in the product that has not opted in.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const aSuiteRow = (over: Partial<Suite> = {}): Suite => {
  const row: Suite = {
    id: 'suite-1',
    key: 'TS-1',
    name: 'Payments',
    // B1's three arms (005 S1). Zero each — this file is about the panel, not membership.
    filedCount: 0,
    addedCount: 0,
    taggedCount: 0,
    tagRules: [],
    caseIds: [],
    caseCount: 12,
    lifecycleStatus: 'IN_REVIEW',
    preconditions: [],
    postconditions: [],
    lockVersion: 1,
    ...over,
  }
  return row
}

function mockReads(reviewEnabledSuite: boolean) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(['project:read', 'suite:review', 'suite:manage'])
    }
    if (path.includes('/settings')) return projectSettings({ projectId: PROJECT, reviewEnabledSuite })
    return []
  }) as never)
}

function renderPanel(suite: Suite = aSuiteRow()) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <SuitePanel projectId={PROJECT} suite={suite} />
    </TestProviders>,
  )
}

describe('SuitePanel', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('names the suite, shows where it is on the ladder, and links to its history', async () => {
    mockReads(true)
    renderPanel()

    expect(await screen.findByTestId('Cases.SuitePanel.Name')).toHaveTextContent('Payments')
    expect(screen.getByTestId('Cases.SuitePanel.Lifecycle')).toHaveTextContent(/review/i)
    // A real link, so middle-click and copy-link work — the same call the case detail makes.
    expect(screen.getByTestId('Cases.SuitePanel.HistoryLink')).toHaveAttribute(
      'href',
      `/projects/${PROJECT}/suites/suite-1/history`,
    )
  })

  it('renders the review thread when the project reviews suites', async () => {
    mockReads(true)
    renderPanel()

    // The whole point of A7: this panel existed for cases and plans and for no folder anywhere.
    expect(await screen.findByTestId('Review.Panel')).toBeInTheDocument()
  })

  it('renders no review section at all when the project does not review suites', async () => {
    mockReads(false)
    renderPanel()

    // The header still stands — the folder has a lifecycle either way — and the thread does not.
    // A panel that drew its own "Review" heading around ReviewPanel would show an empty section on
    // every project that has not opted in, which is most of them.
    await screen.findByTestId('Cases.SuitePanel.Name')
    expect(screen.queryByTestId('Review.Panel')).not.toBeInTheDocument()
  })

  it('says what the case count actually counts', async () => {
    mockReads(true)
    renderPanel()

    // The three arms overlap across suites, so a bare number beside a project total is the
    // subtraction the contract's own description warns a client not to invite.
    expect(await screen.findByTestId('Cases.SuitePanel')).toHaveTextContent(
      /12 cases filed, added or tagged here/i,
    )
  })
})
