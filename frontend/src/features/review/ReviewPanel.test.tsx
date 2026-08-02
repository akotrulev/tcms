import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Review, ReviewComment } from '../../api/types'
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

/**
 * A review, fully spelled out — and **no `as Review` cast**, which is the point of this comment.
 *
 * It used to end `} as Review`, and when `comments` was added to the contract in §79 every one of
 * this file's eleven tests went red at runtime (`comments.length` of `undefined`) with **zero type
 * errors**, because the cast told the compiler the object was already a `Review`. §65's finding, in
 * a second file: a fixture that asserts its own shape is a fixture that stops tracking the
 * contract. Without the cast, the next required field is a build failure here rather than eleven
 * confusing runtime failures.
 */
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
    assignees: [],
    participations: [],
    comments: [],
    ...overrides,
  }
}

/** One thread, spelled out for the same reason `review` is — no cast, so the contract binds. */
function thread(overrides: Partial<ReviewComment> = {}): ReviewComment {
  return {
    id: 'c1',
    author: person('Bo'),
    body: 'A remark',
    createdAt: '2026-07-29T14:06:00Z',
    resolvedAt: null,
    resolvedBy: null,
    replies: [],
    ...overrides,
  }
}

/**
 * The project's §6g settings, with review switched **on** — which every test below except
 * `hidesItselfWhenReviewIsOff` depends on.
 *
 * Worth stating why this is here at all: when the opt-in landed, all 11 of this file's tests went
 * red at once, because the panel correctly renders **nothing** for a project that has not enabled
 * review — and the unmatched-path arm of `mockReads` was answering `[]`, whose
 * `reviewEnabledPlan` is `undefined`. That is the feature working, and the fixture had to move to
 * the state a project that *turned review on* is in. The default-off state now has its own test
 * rather than being the accidental behaviour of a fallthrough.
 */
function settings(overrides: Record<string, unknown> = {}) {
  return {
    projectId: PROJECT,
    reviewEnabledCase: true,
    reviewEnabledSuite: true,
    reviewEnabledPlan: true,
    requiredPeerReviews: 0,
    authorMayApproveOwnWork: false,
    editAfterApproval: 'RETAIN',
    updatedBy: null,
    updatedAt: null,
    ...overrides,
  }
}

function mockReads(
  reviews: Review[],
  keys: string[],
  projectSettings: Record<string, unknown> = settings(),
  /**
   * What the assignment picker is offered.
   *
   * Defaulted to empty rather than to a fixture, so every test that does not care about the picker
   * keeps a picker with nothing in it — which is the state the panel must survive, and which the
   * unmatched-path `[]` arm was already producing before this parameter existed.
   */
  directory: { members?: unknown[]; teams?: unknown[] } = {},
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(keys)
    if (path.includes('/settings')) return projectSettings
    if (path.includes('/reviews')) return reviews
    if (path.includes('/members')) return directory.members ?? []
    if (path.startsWith('/teams')) return directory.teams ?? []
    return []
  }) as never)
}

/**
 * Waits until the assignment gate has resolved **true** — the barrier the picker tests need.
 *
 * `useProjectMembers` is called with the gate's own `allowed` as its `enabled`, so the request for
 * `/members` firing at all *is* the proof the permission came back held. Written this way because
 * the obvious barrier is wrong: the select's test id sits on MUI's display `div`, which is not a
 * form element, so `toBeEnabled()` on it is satisfied immediately and the click lands on a control
 * that is still disabled — §66's "passes against the pending state" in this file's own tests, which
 * is exactly how these four failed on the first run.
 */
async function pickerReady() {
  await waitFor(() =>
    expect(
      vi.mocked(api.get).mock.calls.some((call) => String(call[0]).includes('/members')),
    ).toBe(true),
  )
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

  /*
    §6g's opening requirement: "a project that never turns it on should see no extra UI at all."
    The check lives in the panel rather than at its three call sites, so this is the test that a
    fourth screen mounting it cannot forget — and it asserts the panel's *root* is absent, not that
    the list is empty, because an empty panel headed "Review" is precisely the extra UI the
    requirement refuses.
  */
  it('renders nothing at all when the project has review switched off for this type', async () => {
    mockReads([review()], ['plan:review'], settings({ reviewEnabledPlan: false }))

    renderPanel()

    // Barriered on a sibling that DOES resolve, so this cannot pass merely by running before the
    // settings arrive — the failure §66 and §76 both caught, where an assertion is satisfied by a
    // pending state rather than by the answer.
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    await waitFor(() => {
      expect(screen.queryByTestId('Review.Panel')).not.toBeInTheDocument()
    })
    expect(screen.queryByTestId('Review.Current')).not.toBeInTheDocument()
  })

  it('renders the panel when the type is enabled even though a sibling type is not', async () => {
    // The opt-in is per artifact type, not per project — a project reviewing plans and not cases
    // must get the panel on one and not the other, and a check that read any-of would not.
    mockReads([review()], ['plan:review'], settings({ reviewEnabledCase: false }))

    renderPanel()

    expect(await screen.findByTestId('Review.Current')).toBeInTheDocument()
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

  /*
    §6g's threads, and the one thing this panel gets wrong most easily: the two affordances cost
    two DIFFERENT keys. Writing a remark costs `case:comment` on every subject type — the catalogue
    has one comment key where it has three review keys, because commenting is a tier — while
    resolving costs the subject's own `*:review`. A panel that asked one key for both would either
    hide commenting from the Viewer §6b built the key for, or offer a Viewer a resolve button whose
    only outcome is a 403.

    These are frontend tests because a frontend test can hold a permission set the seeded catalogue
    cannot produce (§71's finding): `case:comment` WITHOUT `plan:review` is a real Viewer, and
    `plan:review` without `case:comment` is a persona no role has — which is why the server's half
    is a computed check over the catalogue rather than a test.
  */
  it('lets a commenter write while refusing them the resolve control', async () => {
    mockReads(
      [review({ comments: [thread({ id: 'c1', body: 'Is settlement in scope?' })] })],
      ['case:comment'],
    )
    renderPanel()

    // A positive assertion first, so nothing here can be satisfied by `useCan`'s pending state —
    // §66's finding, and §76's repair of the same shape in this very file.
    await waitFor(() => expect(screen.getByTestId('Review.Comment.New.Body')).toBeEnabled())
    expect(screen.getByTestId('Review.Thread.Root')).toHaveTextContent('Is settlement in scope?')
    expect(screen.getByTestId('Review.Thread.ReplyToggle')).toBeEnabled()
    expect(screen.getByTestId('Review.Thread.Resolve')).toBeDisabled()
    // And the tier above it is refused too — the panel must not confuse "may comment" with "may
    // give a verdict", which is the same three-tier distinction one rung up.
    expect(screen.getByTestId('Review.Lgtm')).toBeDisabled()
  })

  it('lets a reviewer resolve, and shows who resolved a thread already dealt with', async () => {
    mockReads(
      [
        review({
          comments: [
            thread({
              id: 'c1',
              body: 'The exit criterion reads 0%.',
              resolvedAt: '2026-07-30T08:05:00Z',
              resolvedBy: person('Ana'),
            }),
          ],
        }),
      ],
      ['plan:review', 'case:comment'],
    )
    renderPanel()

    await waitFor(() => expect(screen.getByTestId('Review.Thread.Resolve')).toBeEnabled())
    expect(screen.getByTestId('Review.Thread.Resolve')).toHaveTextContent('Re-open')
    expect(screen.getByTestId('Review.Thread.Resolved')).toHaveTextContent('Resolved by Ana')
  })

  it('posts a comment with a null parent, and a reply with the thread root', async () => {
    mockReads([review({ comments: [thread({ id: 'c1', body: 'Root' })] })], ['case:comment'])
    const post = vi.spyOn(api, 'post').mockResolvedValue(review() as never)
    renderPanel()

    await waitFor(() => expect(screen.getByTestId('Review.Comment.New.Body')).toBeEnabled())
    await userEvent.type(screen.getByTestId('Review.Comment.New.Body'), 'A new point')
    await userEvent.click(screen.getByTestId('Review.Comment.New.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalledTimes(1))
    expect(post.mock.calls[0][0]).toBe(`/projects/${PROJECT}/reviews/r1/comments`)
    expect(post.mock.calls[0][1]).toEqual({ body: 'A new point', parentId: null })

    await userEvent.click(screen.getByTestId('Review.Thread.ReplyToggle'))
    await userEvent.type(screen.getByTestId('Review.Comment.Reply.Body'), 'Answering that')
    await userEvent.click(screen.getByTestId('Review.Comment.Reply.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalledTimes(2))
    expect(post.mock.calls[1][1]).toEqual({ body: 'Answering that', parentId: 'c1' })
  })

  it('renders a reply under its root and offers no reply-to-a-reply', async () => {
    // The server refuses a reply to a reply as a field error, so a control here would be an
    // affordance the product does not have — §31, from the side where the answer is "nobody may".
    mockReads(
      [
        review({
          comments: [
            thread({
              id: 'c1',
              body: 'Root',
              replies: [thread({ id: 'c2', body: 'A reply' })],
            }),
          ],
        }),
      ],
      ['case:comment'],
    )
    renderPanel()

    expect(await screen.findByTestId('Review.Thread.Replies')).toHaveTextContent('A reply')
    expect(screen.getAllByTestId('Review.Thread')).toHaveLength(1)
    expect(screen.getAllByTestId('Review.Thread.ReplyToggle')).toHaveLength(1)
  })

  it('offers no comment form once the review has ended, and drops the section when it is empty', async () => {
    mockReads(
      [review({ state: 'REJECTED', comments: [] })],
      ['case:comment', 'plan:review'],
    )
    renderPanel()

    await screen.findByTestId('Review.Current')
    // A closed review nobody discussed has nothing to show and nothing that can be added; a heading
    // over an empty list would be the "extra UI" §6g objects to, in miniature.
    expect(screen.queryByTestId('Review.Comments')).toBeNull()
    expect(screen.queryByTestId('Review.Comment.New')).toBeNull()
  })

  it('keeps a closed review’s conversation readable without offering a reply', async () => {
    mockReads(
      [review({ state: 'APPROVED', comments: [thread({ id: 'c1', body: 'Settled offline' })] })],
      ['case:comment', 'plan:review'],
    )
    renderPanel()

    expect(await screen.findByTestId('Review.Thread.Root')).toHaveTextContent('Settled offline')
    expect(screen.queryByTestId('Review.Thread.ReplyToggle')).toBeNull()
    expect(screen.queryByTestId('Review.Comment.New')).toBeNull()
  })

  it('keeps a comment whose author no longer resolves', async () => {
    mockReads([review({ comments: [thread({ id: 'c1', author: null })] })], ['case:comment'])
    renderPanel()

    expect(await screen.findByTestId('Review.Thread.Root')).toHaveTextContent('A former member')
  })

  /*
    Reviewer assignment — §6g's "assign a user or a team", and the half of the panel that says what
    is OUTSTANDING rather than what has happened. The risk here is the mirror of the one above: a
    panel that renders who was asked is one merge away from a panel that renders being asked and
    having spoken as the same fact, at which point an unanswered request is indistinguishable from
    somebody who was never asked — which is exactly the row a lead opens this panel to find.
  */

  it('renders a person, a team and an offboarded assignee as three requests', async () => {
    mockReads(
      [
        review({
          assignees: [
            { id: 'a1', user: person('Marco'), team: null, assignedBy: person('Ana'), assignedAt: '2026-07-29T11:22:00Z' },
            { id: 'a2', user: null, team: { id: 't1', name: 'QA Chapter' }, assignedBy: person('Ana'), assignedAt: '2026-07-29T11:22:00Z' },
            { id: 'a3', user: null, team: null, assignedBy: null, assignedAt: '2026-07-29T11:23:00Z' },
          ],
        }),
      ],
      ['plan:review'],
    )
    renderPanel()

    const chips = await screen.findAllByTestId('Review.Assignee')
    expect(chips).toHaveLength(3)
    expect(chips[0]).toHaveTextContent('Marco')
    expect(chips[1]).toHaveTextContent('QA Chapter')
    // Both null is a real row — the person asked has since been offboarded — and the request
    // stands. Dropping it would hide the one request nobody can discharge.
    expect(chips[2]).toHaveTextContent('A former member')
  })

  it('shows a request nobody has answered beside an empty verdict trail', async () => {
    mockReads(
      [
        review({
          assignees: [
            { id: 'a1', user: person('Marco'), team: null, assignedBy: person('Ana'), assignedAt: '2026-07-29T11:22:00Z' },
          ],
          participations: [],
        }),
      ],
      ['plan:review'],
    )
    renderPanel()

    // The two lists are separate on purpose. Merged, this row would render as "Marco, no verdict",
    // which is also what somebody who was never asked looks like.
    expect(await screen.findByTestId('Review.Assignees')).toHaveTextContent('Marco')
    expect(screen.getByTestId('Review.Trail.Empty')).toBeInTheDocument()
  })

  it('offers no way to ask or to withdraw without the review key', async () => {
    mockReads(
      [
        review({
          assignees: [
            { id: 'a1', user: person('Marco'), team: null, assignedBy: person('Ana'), assignedAt: '2026-07-29T11:22:00Z' },
          ],
        }),
      ],
      // case:comment and NOT plan:review — the Viewer, the persona the three tiers exist for. The
      // list stays readable; filling somebody else's queue is the reviewing tier's act.
      ['case:comment'],
    )
    renderPanel()

    await waitFor(() => expect(screen.getByTestId('Review.Assign.Submit')).toBeDisabled())
    expect(screen.getByTestId('Review.Assignee')).toHaveTextContent('Marco')
    expect(screen.queryByTestId('Review.Assignee.Remove')).toBeNull()
  })

  it('asks the selected person, and sends a userId rather than a teamId', async () => {
    mockReads([review()], ['plan:review'], settings(), {
      members: [person('Marco'), person('Bo')],
      teams: [{ id: 't1', name: 'QA Chapter' }],
    })
    const post = vi.spyOn(api, 'post').mockResolvedValue(review() as never)
    renderPanel()

    await pickerReady()
    await userEvent.click(screen.getByTestId('Review.Assign.Choice'))
    await userEvent.click(await screen.findByRole('option', { name: 'Marco' }))
    await userEvent.click(screen.getByTestId('Review.Assign.Submit'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        `/projects/${PROJECT}/reviews/r1/assignees`,
        { userId: 'Marco-id', teamId: null },
      ),
    )
  })

  it('asks the selected team, and sends a teamId rather than a userId', async () => {
    mockReads([review()], ['plan:review'], settings(), {
      members: [person('Marco')],
      teams: [{ id: 't1', name: 'QA Chapter' }],
    })
    const post = vi.spyOn(api, 'post').mockResolvedValue(review() as never)
    renderPanel()

    await pickerReady()
    await userEvent.click(screen.getByTestId('Review.Assign.Choice'))
    await userEvent.click(await screen.findByRole('option', { name: 'QA Chapter (team)' }))
    await userEvent.click(screen.getByTestId('Review.Assign.Submit'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        `/projects/${PROJECT}/reviews/r1/assignees`,
        { userId: null, teamId: 't1' },
      ),
    )
  })

  it('offers neither the submitter nor anybody already asked', async () => {
    mockReads(
      [
        review({
          // Ana submitted it; Marco has already been asked.
          assignees: [
            { id: 'a1', user: person('Marco'), team: null, assignedBy: person('Ana'), assignedAt: '2026-07-29T11:22:00Z' },
          ],
        }),
      ],
      ['plan:review'],
      settings(),
      { members: [person('Ana'), person('Marco'), person('Bo')], teams: [] },
    )
    renderPanel()

    await pickerReady()
    await userEvent.click(screen.getByTestId('Review.Assign.Choice'))

    // Ana is the server's field error — their own peer review would not count towards the
    // required minimum, so offering them is offering a request that can never be discharged.
    // Marco is already asked, and asking again writes nothing.
    expect(await screen.findByRole('option', { name: 'Bo' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Ana' })).toBeNull()
    expect(screen.queryByRole('option', { name: 'Marco' })).toBeNull()
  })

  it('withdraws a request through the assignment id, not the assignee id', async () => {
    mockReads(
      [
        review({
          assignees: [
            { id: 'a1', user: person('Marco'), team: null, assignedBy: person('Ana'), assignedAt: '2026-07-29T11:22:00Z' },
          ],
        }),
      ],
      ['plan:review'],
    )
    const del = vi.spyOn(api, 'del').mockResolvedValue(review() as never)
    renderPanel()

    // The same barrier the picker tests use, and it applies here because the chip's gate asks the
    // same key on the same project: the delete affordance exists only once `useCan` resolves TRUE,
    // and a `findBy` on the icon alone times out against the pending state rather than waiting for
    // it — which is how this test failed on its first run.
    await pickerReady()
    const chip = screen.getByTestId('Review.Assignee')
    await userEvent.click(within(chip).getByTestId('Review.Assignee.Remove'))

    // `a1`, not `Marco-id`: one person may be asked to review several artifacts, so an
    // assignee-keyed delete could name a pairing that never existed.
    await waitFor(() =>
      expect(del).toHaveBeenCalledWith(`/projects/${PROJECT}/reviews/r1/assignees/a1`),
    )
  })

  it('keeps a closed review’s requests readable and offers no picker', async () => {
    mockReads(
      [
        review({
          state: 'APPROVED',
          assignees: [
            { id: 'a1', user: person('Marco'), team: null, assignedBy: person('Ana'), assignedAt: '2026-07-29T11:22:00Z' },
          ],
        }),
      ],
      ['plan:review'],
    )
    renderPanel()

    expect(await screen.findByTestId('Review.Assignee')).toHaveTextContent('Marco')
    expect(screen.queryByTestId('Review.Assign.Submit')).toBeNull()
    // Nor a withdrawal: the server answers 409, so the affordance would only ever be a refusal.
    expect(screen.queryByTestId('Review.Assignee.Remove')).toBeNull()
  })

  it('drops the section entirely on a closed review nobody was asked to look at', async () => {
    mockReads([review({ state: 'REJECTED', assignees: [] })], ['plan:review'])
    renderPanel()

    await screen.findByTestId('Review.Current')
    expect(screen.queryByTestId('Review.Assignees')).toBeNull()
  })
})
