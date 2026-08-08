import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { AiProposal } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { ProposalPage } from './ProposalPage'

/**
 * The decision screen — where §6w.6's boundary is actually enforced by a person clicking.
 *
 * The four things here that no gate can see:
 *
 *   1. **A 400 from accept is normal and must be rendered in full.** The payload was stored
 *      unvalidated on purpose, so accept is where "is this a legal case" is finally asked, against
 *      the project as it is now. Swallowed into a toast, the reviewer is told "something went
 *      wrong" over a message that says exactly what is wrong — and Reject, which is their next
 *      move, must stay live beside it.
 *   2. **Grounding is rendered as text, never as markup.** It comes from a third-party agent.
 *   3. **A proposal whose payload can no longer be read is still decidable.** An error page there
 *      leaves the row in the queue with nothing anybody can do about it.
 *   4. **Both decisions are disabled without `case:create`, not hidden** (§31), while the proposal
 *      itself stays readable.
 */

const PENDING: AiProposal = {
  id: 'prop-1',
  projectId: 'p1',
  suiteId: null,
  title: 'Refund a settled payment',
  testCase: {
    title: 'Refund a settled payment',
    objective: 'Prove a settled payment can be refunded in full',
    // The four the accept demands. They were absent, and typechecked, because the contract said
    // `required: [title]` while the server had required four since M0 — so this fixture asserted
    // the shape of a proposal that could only ever be Rejected. §144 fixed the contract; this is
    // the compile error that came out of it.
    stepFormat: 'STRUCTURED',
    priority: 'HIGH',
    automationStatus: 'MANUAL',
    steps: [
      { action: 'Open the settled order', expectedResult: 'The order opens' },
      { action: 'Refund it in full', expectedResult: 'The payment shows as refunded' },
    ],
  },
  verdict: 'PARTIAL',
  verdictSource: 'EXTERNAL',
  grounding: { unverified_claims: ['the refund window is 30 days'] },
  status: 'PENDING',
  createdAt: '2026-08-08T09:00:00Z',
  createdBy: { id: 'user-2', displayName: 'A. Author', email: 'a@acme.test', avatarUrl: null },
  filedByAgent: true,
  decidedAt: null,
  decidedBy: null,
  decisionNote: null,
  acceptedCase: null,
} as AiProposal

function mockApi(proposal: AiProposal = PENDING, permissions = ['case:read', 'case:create']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes('/ai-proposals/')) return proposal
    if (path.startsWith('/projects/p1')) return { id: 'p1', key: 'OLB', name: 'Banking' }
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

/**
 * The control, once the permission query has answered.
 *
 * `findBy*` resolves the moment the element exists, and a gated control exists **disabled** while
 * `GET /me/permissions` is in flight — `useCan`'s pending state is a refusal, deliberately. Clicking
 * it then does nothing and the failure surfaces as `pointer-events: none`, which reads as a UI
 * defect rather than as a test that raced the gate. `src/test/permissions.ts` records the same trap
 * arriving from the fixture side.
 */
async function enabled(testId: string): Promise<HTMLElement> {
  await screen.findByTestId(testId)
  // RE-QUERIED on every attempt, not captured once. `PermissionGate` renders the denied branch
  // inside a wrapper span and the allowed branch bare, so the button is a DIFFERENT node before and
  // after the permission answer lands — a captured reference stays disabled for ever and the test
  // fails on a gate that is working.
  await waitFor(() => expect(screen.getByTestId(testId)).toBeEnabled())
  return screen.getByTestId(testId)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      userId="user-1"
      route="/projects/p1/generated-cases/prop-1"
    >
      <Routes>
        <Route
          path="/projects/:projectId/generated-cases/:proposalId"
          element={<ProposalPage />}
        />
      </Routes>
    </TestProviders>,
  )
}

describe('ProposalPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the proposed case beside the grounding evidence', async () => {
    // §6w.6: "the review UI shows the grounding evidence alongside the proposal so the reviewer is
    // reviewing against the spec, not against their memory of it".
    mockApi()
    renderPage()

    expect(await screen.findByTestId('Proposal.Objective')).toHaveTextContent(
      'Prove a settled payment can be refunded in full',
    )
    expect(screen.getAllByTestId('Proposal.Step')).toHaveLength(2)
    expect(screen.getByTestId('Proposal.Grounding')).toHaveTextContent('the refund window is 30 days')
  })

  it('says nobody checked it when no grounding was sent', async () => {
    mockApi({ ...PENDING, grounding: null, verdict: 'UNVERIFIED', verdictSource: 'NONE' })
    renderPage()

    expect(await screen.findByText(/Nobody has checked this proposal/)).toBeInTheDocument()
  })

  it('RENDERS THE FIELD ERRORS FROM A REFUSED ACCEPT, and keeps Reject live', async () => {
    // THE HEADLINE. The payload is stored unvalidated on purpose, so this 400 is the designed
    // outcome for a proposal whose suite was deleted after it was filed — and the errors are the
    // most useful thing on the screen, because the reviewer's next move is Reject.
    mockApi()
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Some fields need attention', {
        suiteId: ['That suite is not in this project.'],
      }),
    )
    renderPage()

    await userEvent.click(await enabled('Proposal.Accept'))

    const errors = await screen.findByTestId('Proposal.AcceptErrors')
    expect(errors).toHaveTextContent('suiteId')
    expect(errors).toHaveTextContent('That suite is not in this project.')
    expect(screen.getByTestId('Proposal.Reject')).toBeEnabled()
  })

  it('accepts and sends the reviewer to the case that was created', async () => {
    mockApi()
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue({ id: 'case-9', key: 'TC-9' } as never)
    renderPage()

    await userEvent.click(await enabled('Proposal.Accept'))

    expect(post).toHaveBeenCalledWith('/projects/p1/ai-proposals/prop-1/accept', {})
  })

  it('sends a blank rejection note as null, not an empty string', async () => {
    // The contract's field is nullable, and '' is a reason somebody typed nothing into.
    mockApi()
    const post = vi.spyOn(api, 'post').mockResolvedValue(PENDING as never)
    renderPage()

    await userEvent.click(await enabled('Proposal.Reject'))

    expect(post).toHaveBeenCalledWith('/projects/p1/ai-proposals/prop-1/reject', { note: null })
  })

  it('sends the note when one is typed', async () => {
    mockApi()
    const post = vi.spyOn(api, 'post').mockResolvedValue(PENDING as never)
    renderPage()

    const note = await screen.findByTestId('Proposal.Note')
    // `paste` rather than `type`, per vitest.config.ts's note on the timeout cliff: typing a
    // sentence character by character is what puts a file on that list.
    await enabled('Proposal.Reject')
    await userEvent.click(note.querySelector('textarea') as HTMLElement)
    await userEvent.paste('Invented an endpoint')
    await userEvent.click(screen.getByTestId('Proposal.Reject'))

    expect(post).toHaveBeenCalledWith('/projects/p1/ai-proposals/prop-1/reject', {
      note: 'Invented an endpoint',
    })
  })

  it('disables both decisions without case:create, rather than hiding them', async () => {
    // §31, and the split that matters: the proposal is readable by anyone who may read cases.
    mockApi(PENDING, ['case:read'])
    renderPage()

    expect(await screen.findByTestId('Proposal.Accept')).toBeDisabled()
    expect(screen.getByTestId('Proposal.Reject')).toBeDisabled()
    expect(screen.getByTestId('Proposal.Objective')).toBeInTheDocument()
  })

  it('offers no decision at all once one has been made, and links to the case', async () => {
    mockApi({
      ...PENDING,
      status: 'ACCEPTED',
      decidedBy: { id: 'user-1', displayName: 'R. Reviewer', email: 'r@acme.test', avatarUrl: null },
      decidedAt: '2026-08-08T10:00:00Z',
      acceptedCase: { id: 'case-9', key: 'TC-9' },
    } as AiProposal)
    renderPage()

    expect(await screen.findByTestId('Proposal.AcceptedCase')).toHaveTextContent('TC-9')
    expect(screen.queryByTestId('Proposal.Accept')).toBeNull()
    expect(screen.queryByTestId('Proposal.Reject')).toBeNull()
  })

  it('says the case is gone when an accepted proposal names one that was deleted', async () => {
    // The decision stands: deleting the case is a second decision, not an undo of the first.
    mockApi({
      ...PENDING,
      status: 'ACCEPTED',
      decidedBy: { id: 'user-1', displayName: 'R. Reviewer', email: 'r@acme.test', avatarUrl: null },
      decidedAt: '2026-08-08T10:00:00Z',
      acceptedCase: null,
    } as AiProposal)
    renderPage()

    expect(await screen.findByTestId('Proposal.Accepted')).toHaveTextContent(
      'has since been deleted',
    )
  })

  it('keeps an unreadable payload decidable rather than showing an error page', async () => {
    mockApi({ ...PENDING, testCase: null } as AiProposal)
    renderPage()

    expect(await screen.findByTestId('Proposal.Unreadable')).toBeInTheDocument()
    // Still decidable: an unreadable payload is precisely the proposal that has to be rejectable.
    await enabled('Proposal.Reject')
  })
})
