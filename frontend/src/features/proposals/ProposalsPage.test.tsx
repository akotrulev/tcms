import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { AiProposalSummary } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject } from '../../test/fixtures'
import { ProposalsPage } from './ProposalsPage'

/**
 * The review queue — and the things about it no gate, type or lint rule can see.
 *
 *   1. **A verdict never renders without its source.** §6w.1a: an agent's claim is recorded, shown
 *      and attributed, and never counted as verified. A row reading "Grounded" alone is a claim
 *      promoted to a finding, on the one screen where somebody skims.
 *   2. **An agent-filed proposal is marked as such, and still names a person.** §6j — authorship is
 *      a person always, and on an agent-filed proposal that person is the token's owner.
 *   3. **The status filter lives in the URL** and an unrecognised one falls back rather than being
 *      sent, so a pasted link with a typo does not produce a 400 nobody can fix.
 */

const UNCHECKED: AiProposalSummary = {
  id: 'prop-1',
  title: 'Refund a settled payment',
  suiteId: null,
  verdict: 'UNVERIFIED',
  verdictSource: 'NONE',
  status: 'PENDING',
  createdAt: '2026-08-08T09:00:00Z',
  createdBy: { id: 'user-1', displayName: 'A. Author', email: 'a@acme.test', avatarUrl: null },
  filedByAgent: true,
  stepCount: 4,
}

/** The dangerous one: an agent asserting its own work is grounded. */
const CLAIMED: AiProposalSummary = {
  ...UNCHECKED,
  id: 'prop-2',
  title: 'Refund a partial payment',
  verdict: 'GROUNDED',
  verdictSource: 'EXTERNAL',
  filedByAgent: true,
}

/** Typed by a person in the browser — no token, so no robot. */
const BY_HAND: AiProposalSummary = {
  ...UNCHECKED,
  id: 'prop-3',
  title: 'Refund after chargeback',
  filedByAgent: false,
  stepCount: 0,
}

function mockApi(rows: AiProposalSummary[] = [UNCHECKED, CLAIMED, BY_HAND]) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['case:read', 'case:create'])
    if (path.includes('/ai-proposals')) {
      const status = new URL(path, 'http://x').searchParams.get('status')
      return {
        items: status === 'PENDING' ? rows : [],
        page: 0,
        size: 25,
        total: status === 'PENDING' ? rows.length : 0,
      }
    }
    if (path.startsWith('/projects/p1')) return aProject({ id: 'p1', key: 'OLB', name: 'Banking' })
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage(route = '/projects/p1/generated-cases') {
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route={route}>
      <Routes>
        <Route path="/projects/:projectId/generated-cases" element={<ProposalsPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('ProposalsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists the waiting proposals with their step counts', async () => {
    mockApi()
    renderPage()

    const rows = await screen.findAllByTestId('Proposals.Row')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('Refund a settled payment')).toBeInTheDocument()
    expect(within(rows[0]).getByText('4')).toBeInTheDocument()
  })

  it('NEVER shows a verdict without saying who claimed it', async () => {
    // THE HEADLINE. A chip reading "Grounded" beside no provenance is a third party's claim
    // rendered as this product's finding — §6w.1a's whole argument, arriving through the UI.
    mockApi()
    renderPage()

    const rows = await screen.findAllByTestId('Proposals.Row')

    expect(within(rows[1]).getByTestId('Proposals.Verdict')).toHaveTextContent('Grounded')
    expect(within(rows[1]).getByTestId('Proposals.VerdictSource')).toHaveTextContent(
      'Reported by the connecting agent',
    )
    // And the unchecked one says so as an instruction, not as a defect in the proposal.
    expect(within(rows[0]).getByTestId('Proposals.Verdict')).toHaveTextContent(
      'Not independently checked',
    )
    expect(within(rows[0]).getByTestId('Proposals.VerdictSource')).toHaveTextContent(
      'Nobody has checked this',
    )
  })

  it('marks an agent-filed proposal, and still names the person answerable', async () => {
    mockApi()
    renderPage()

    const rows = await screen.findAllByTestId('Proposals.Row')
    // §6j: the person is named on both, because a token's owner is who answers for it.
    expect(within(rows[0]).getByText('A. Author')).toBeInTheDocument()
    expect(within(rows[2]).getByText('A. Author')).toBeInTheDocument()
    // The robot marks the difference, and it is on exactly the agent-filed one.
    expect(within(rows[0]).getByTestId('SmartToyOutlinedIcon')).toBeInTheDocument()
    expect(within(rows[2]).queryByTestId('SmartToyOutlinedIcon')).toBeNull()
  })

  it('says how generation works when the queue is empty', async () => {
    // The normal state for most projects, and the only place the product explains the surface at
    // all. An apology here would leave a reader with nothing to do.
    mockApi([])
    renderPage()

    expect(await screen.findByText('No cases waiting for review')).toBeInTheDocument()
    expect(screen.getByText(/MCP server or the tcms CLI/)).toBeInTheDocument()
  })

  it('reads the status from the URL and asks the server for that one', async () => {
    const get = mockApi()
    renderPage('/projects/p1/generated-cases?status=REJECTED')

    expect(await screen.findByText('Nothing rejected yet')).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith(
      expect.stringContaining('status=REJECTED'),
      expect.anything(),
    )
  })

  it('falls back to PENDING for a status it does not recognise', async () => {
    // A pasted link with a typo must not produce a 400 the reader cannot see or fix.
    const get = mockApi()
    renderPage('/projects/p1/generated-cases?status=NONSENSE')

    expect(await screen.findAllByTestId('Proposals.Row')).toHaveLength(3)
    expect(get).not.toHaveBeenCalledWith(
      expect.stringContaining('NONSENSE'),
      expect.anything(),
    )
  })

  it('switches lists when a tab is chosen', async () => {
    const get = mockApi()
    renderPage()
    await screen.findAllByTestId('Proposals.Row')

    await userEvent.click(screen.getByRole('button', { name: /Accepted/ }))

    expect(await screen.findByText('Nothing accepted yet')).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith(
      expect.stringContaining('status=ACCEPTED'),
      expect.anything(),
    )
  })
})
