import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Review } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { ReviewPanel } from './ReviewPanel'

/**
 * The review panel — §6g's **advisory** tier, and the three ways it must not overstate the record.
 *
 * The component's own risk is not the fetch. It is that a panel showing a review is one keystroke
 * away from being a panel that *grants* one:
 *
 *   1. **It must not offer approval.** The sign-off is a move on the artifact's own ladder, gated
 *      on `*:approve`; a button here would be a second door held by everyone with the advisory key.
 *      Asserted as an absence, which is the only way to assert it.
 *   2. **A closed review must not offer a verdict.** The server answers 409, so a form here is an
 *      affordance whose only outcome is a refusal — §31's disable-don't-hide, from the other end.
 *   3. **The version comparison is the panel's one piece of reasoning**, and it is the difference
 *      between a reviewer's LGTM meaning something and meaning nothing.
 *
 * The gate itself belongs to `PermissionGate`, which has its own tests; what is asserted here is
 * that this call site asks for the key belonging to the **subject type** rather than a fixed one —
 * the client half of the server's `ReviewSubjectType.reviewPermission`.
 */

const PROJECT = 'p1'

const person = (name: string) => ({
  id: `${name}-id`,
  displayName: name,
  email: `${name}@example.test`,
  avatarUrl: null,
})

function review(overrides: Partial<Review> = {}): Review {
  return {
    id: 'r1',
    subjectType: 'PLAN',
    subjectId: 'pl1',
    subjectVersionNo: 2,
    state: 'IN_REVIEW',
    submittedBy: person('Ana'),
    createdAt: '2026-07-29T11:20:00Z',
    approvedBy: null,
    approvedAt: null,
    participations: [],
    ...overrides,
  } as Review
}

function mockReads(reviews: Review[], keys: string[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(keys)
    if (path.includes('/reviews')) return reviews
    return []
  }) as never)
}

function renderPanel(currentVersionNo?: number, subjectType: 'PLAN' | 'CASE' = 'PLAN') {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/plans/pl1`}>
      <ReviewPanel
        projectId={PROJECT}
        subjectType={subjectType}
        subjectId="pl1"
        currentVersionNo={currentVersionNo}
      />
    </TestProviders>,
  )
}

describe('ReviewPanel — the record, and the sign-off it must not offer', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the open review, its submitter and the trail', async () => {
    mockReads(
      [
        review({
          participations: [
            {
              id: 'pp1',
              user: person('Marco'),
              kind: 'PEER_REVIEW',
              verdict: 'CHANGES_REQUESTED',
              comment: 'The settlement suite is missing.',
              createdAt: '2026-07-29T14:05:00Z',
            },
          ],
        }),
      ],
      ['plan:review'],
    )
    renderPanel()

    expect(await screen.findByTestId('Review.State')).toHaveTextContent('In review')
    expect(screen.getByTestId('Review.Current')).toHaveTextContent('Submitted by Ana')
    expect(screen.getByTestId('Review.Trail')).toHaveTextContent('Changes requested')
    expect(screen.getByTestId('Review.Trail')).toHaveTextContent('Marco')
  })

  it('never offers an approval, however much the caller holds', async () => {
    // plan:approve is deliberately in the held set: the assertion is about the PANEL, not about
    // the gate. §6g's state machine has exactly one gate and it is not this endpoint, so an
    // Approve button here would be reachable by everyone who can already sign off — and, worse,
    // by everyone who cannot, since this panel's own gate asks for the advisory key.
    mockReads([review()], ['plan:review', 'plan:approve', 'plan:edit'])
    renderPanel()

    await screen.findByTestId('Review.Lgtm')
    expect(screen.queryByRole('button', { name: /approve/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /reject/i })).toBeNull()
  })

  it('asks for the subject type’s own key — a case panel enables on case:review alone', async () => {
    // The positive half, and it is positive on purpose. `useCan` is fail-closed WHILE PENDING, so
    // `waitFor(() => expect(...).toBeDisabled())` is satisfied by the very first render and proves
    // nothing (§66 — the first draft of this file passed the mutation that hard-codes the key).
    // `toBeEnabled` cannot pass while pending, so a panel asking any other key fails here.
    mockReads([review({ subjectType: 'CASE', subjectId: 'pl1' })], ['case:review'])
    renderPanel(undefined, 'CASE')

    await waitFor(() => expect(screen.getByTestId('Review.Lgtm')).toBeEnabled())
  })

  it('a plan panel enables on plan:review alone — the other end of the same rule', async () => {
    mockReads([review()], ['plan:review'])
    renderPanel()

    await waitFor(() => expect(screen.getByTestId('Review.Lgtm')).toBeEnabled())
  })

  it('refuses the verdict to a caller holding the other subject type’s key', async () => {
    mockReads([review({ subjectType: 'CASE', subjectId: 'pl1' })], ['plan:review'])
    renderPanel(undefined, 'CASE')

    // The barrier: PermissionGate wraps a DENIED control in a `role="none"` span carrying the
    // refusal, and `reason` is undefined while the answer is still pending (§65's Gate.pending —
    // "not yet" is not "no"). So the wrapper appearing is proof the verdict actually resolved,
    // which `toBeDisabled` on its own could never be.
    await waitFor(() =>
      expect(screen.getByTestId('Review.Lgtm').closest('[role="none"]')).not.toBeNull(),
    )
    expect(screen.getByTestId('Review.Lgtm')).toBeDisabled()
    expect(screen.getByTestId('Review.RequestChanges')).toBeDisabled()
  })

  it('posts the verdict with the comment, and only the two advisory values exist', async () => {
    mockReads([review()], ['plan:review'])
    const post = vi.spyOn(api, 'post').mockResolvedValue(review() as never)
    renderPanel()

    await waitFor(() => expect(screen.getByTestId('Review.Lgtm')).toBeEnabled())
    await userEvent.type(screen.getByTestId('Review.Comment'), 'Scope looks right')
    await userEvent.click(screen.getByTestId('Review.Lgtm'))

    await waitFor(() => expect(post).toHaveBeenCalledTimes(1))
    expect(post.mock.calls[0][0]).toBe(`/projects/${PROJECT}/reviews/r1/participations`)
    expect(post.mock.calls[0][1]).toEqual({ verdict: 'LGTM', comment: 'Scope looks right' })
  })

  it('offers no verdict form once the review has ended', async () => {
    mockReads(
      [review({ state: 'APPROVED', approvedBy: person('Ana'), approvedAt: '2026-07-30T09:00:00Z' })],
      ['plan:review'],
    )
    renderPanel()

    expect(await screen.findByTestId('Review.Approval')).toHaveTextContent('Signed off by Ana')
    expect(screen.queryByTestId('Review.VerdictForm')).toBeNull()
  })

  it('says so when the artifact has moved past the version under review', async () => {
    mockReads([review({ subjectVersionNo: 2 })], ['plan:review'])
    renderPanel(4)

    expect(await screen.findByTestId('Review.Stale')).toHaveTextContent('v2')
    expect(screen.getByTestId('Review.Stale')).toHaveTextContent('v4')
  })

  it('does not claim staleness when the artifact is still at the reviewed version', async () => {
    mockReads([review({ subjectVersionNo: 2 })], ['plan:review'])
    renderPanel(2)

    await screen.findByTestId('Review.Current')
    expect(screen.queryByTestId('Review.Stale')).toBeNull()
  })

  it('reads "not in review" as a state rather than as a failure', async () => {
    // Review is optional and off by default (§6g), so most artifacts have no record at all and an
    // empty array is the ordinary answer — including for every artifact that was already in review
    // before the record existed.
    mockReads([], ['plan:review'])
    renderPanel()

    expect(await screen.findByText(/Not in review/)).toBeInTheDocument()
    expect(screen.queryByTestId('Review.VerdictForm')).toBeNull()
  })

  it('keeps a review whose author no longer resolves', async () => {
    // `submittedBy` is nullable because the account may have been offboarded; the record stands.
    mockReads([review({ submittedBy: null })], ['plan:review'])
    renderPanel()

    expect(await screen.findByTestId('Review.Current')).toHaveTextContent('a former member')
  })
})
