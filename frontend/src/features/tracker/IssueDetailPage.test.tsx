import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { IssueStatusRef } from '../../api/types'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { drivingAFailedRead, drivingAFailedWrite } from '../../test/renderFailures'
import {
  aProjectWorkflow,
  aStatusRef,
  aUserRef,
  anIssue,
  anIssueDocLink,
  anEpicTimeRollup,
  anIssueSummary,
  anIssueType,
  aTimeRollup,
  projectSettings,
} from '../../test/fixtures'
import { IssueDetailPage } from './IssueDetailPage'

/**
 * `/issues/:key` — one route, three panel sets, and the five claims a static render cannot make.
 *
 * 1. **The basis is beside every percentage** (D8). The one assertion that would have caught the
 *    two-contradictory-percentages bug this repo has already shipped.
 * 2. **A forbidden move is disabled WITH ITS REASON, not absent.** `task.html`'s caption is the
 *    requirement: hiding it makes a configured workflow look like a broken one.
 * 3. **An epic has no transition menu at all, and says why.** A disabled one would read as a
 *    missing permission (D31).
 * 4. **Every panel goes through `DataRegion`.** An epic whose children failed to load must not
 *    read as an epic with no children.
 * 5. **A refused transition surfaces beside the picker, not as a toast.** Driven by the 409 that
 *    `tcmsFaults.issue('conflict')` exists to produce by hand.
 */

const READY = aStatusRef({ id: 'st-ready', name: 'Ready', kind: 'NOT_STARTED', position: 0 })
const PROGRESS = aStatusRef({ id: 'st-prog', name: 'In Progress', kind: 'IN_PROGRESS', position: 1 })
const DONE = aStatusRef({ id: 'st-done', name: 'Done', kind: 'DONE', position: 2 })
const ALL: IssueStatusRef[] = [READY, PROGRESS, DONE]

const EPIC_TYPE = anIssueType({
  id: 'ty-epic',
  name: 'Epic',
  level: 'EPIC',
  countsTowardCompletion: false,
})
const STORY_TYPE = anIssueType({ id: 'ty-story', name: 'Story', level: 'STORY' })
const TASK_TYPE = anIssueType({ id: 'ty-task', name: 'Sub-task', level: 'TASK' })

type Arms = {
  issue?: unknown
  comments?: unknown
  docLinks?: unknown
  testCases?: unknown
  settings?: unknown
  rollup?: unknown
  epicRollup?: unknown
  failIssue?: boolean
  failDocLinks?: boolean
  failRollup?: boolean
}

/**
 * Path-keyed arms, **anchored** — `/^\/issues\/[^/]+$/` for the detail read, so
 * `/issues/ACME-1/comments` and `/issues/ACME-1/doc-links` are not swallowed by the detail arm's
 * prefix. That is the trap the harness rules name, and it is the one that would make every
 * sub-resource silently serve an issue.
 */
function mockReads(arms: Arms) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(['issue:read', 'issue:edit', 'issue:comment'])
    }
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) {
      return aProjectWorkflow({ statuses: ALL, types: [EPIC_TYPE, STORY_TYPE, TASK_TYPE] })
    }
    // 016 T5. The settings read is what the two time panels are gated on (D-g), and the two rollup
    // arms are what they render. Anchored ahead of the issue arms for the sub-resource reason the
    // header gives — and served on EVERY case, because an unmocked read here throws and the panels
    // are unconditional on a story and an epic.
    if (/^\/projects\/[^/]+\/settings$/.test(path)) {
      return arms.settings ?? projectSettings({ projectId: 'p1' })
    }
    if (/^\/time\/rollup\/issues\//.test(path)) {
      if (arms.failRollup) throw new Error('the rollup read failed')
      return arms.rollup ?? aTimeRollup()
    }
    if (/^\/time\/rollup\/epics\//.test(path)) {
      if (arms.failRollup) throw new Error('the rollup read failed')
      return arms.epicRollup ?? anEpicTimeRollup()
    }
    if (/^\/issues\/[^/]+\/comments$/.test(path)) return arms.comments ?? []
    // 013 T6's `LinkedCasesPanel`, on every story. Anchored ahead of the detail arm like its
    // siblings, or `/issues/ACME-118/test-cases` would be served an `Issue`.
    if (/^\/issues\/[^/]+\/test-cases$/.test(path)) return arms.testCases ?? []
    if (/^\/issues\/[^/]+\/doc-links$/.test(path)) {
      if (arms.failDocLinks) throw new Error('the doc links read failed')
      return arms.docLinks ?? []
    }
    if (/^\/issues\/[^/]+$/.test(path)) {
      if (arms.failIssue) throw new Error('the issue read failed')
      return arms.issue
    }
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage(key = 'ACME-101') {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/issues/${key}`}>
      <Routes>
        <Route path="/issues/:key" element={<IssueDetailPage />} />
      </Routes>
    </TestProviders>,
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('One route, three shapes', () => {
  it('renders the epic panels for an EPIC-level type', async () => {
    mockReads({
      issue: anIssue({
        key: 'ACME-101',
        title: 'Checkout rebuild',
        type: EPIC_TYPE,
        status: PROGRESS,
        allowedTransitions: [],
        completion: { percent: 70, basis: 'ESTIMATE', done: 12, total: 17, excludedSpikes: 1 },
      }),
    })
    renderPage()

    expect(await screen.findByTestId('Epic.Completion')).toBeInTheDocument()
    expect(screen.queryByTestId('Story.SubTasks')).toBeNull()
  })

  it('renders the story panels for a STORY-level type, whatever the type is CALLED', async () => {
    /*
      The dispatch is on `level`, not on the type's name: Story, Bug and Spike all sit at `STORY`,
      so a `Bug` gets the story screen — sub-tasks, acceptance criteria, the closing rule. A
      name-keyed dispatch would render a bug as a sub-task and nothing would fail.
    */
    mockReads({
      issue: anIssue({
        key: 'ACME-131',
        type: anIssueType({ id: 'ty-bug', name: 'Bug', level: 'STORY' }),
        status: READY,
      }),
    })
    renderPage('ACME-131')

    expect(await screen.findByTestId('Story.SubTasks')).toBeInTheDocument()
    expect(screen.queryByTestId('Epic.Completion')).toBeNull()
  })

  it('renders the task panels for a TASK-level type', async () => {
    mockReads({ issue: anIssue({ key: 'ACME-124', type: TASK_TYPE, status: PROGRESS }) })
    renderPage('ACME-124')

    expect(await screen.findByTestId('Task.Description')).toBeInTheDocument()
    expect(screen.queryByTestId('Story.SubTasks')).toBeNull()
  })
})

describe('The completion meter (D8)', () => {
  it('names the basis beside every percentage', async () => {
    /*
      The one assertion that would have caught the two-contradictory-percentages bug this repo has
      already shipped. An epic weighted by hours and one counted by heads are different
      measurements, and a number that changes meaning when somebody clears an estimate is the
      number people learn to distrust.
    */
    mockReads({
      issue: anIssue({
        type: EPIC_TYPE,
        allowedTransitions: [],
        completion: { percent: 70, basis: 'ESTIMATE', done: 12, total: 17, excludedSpikes: 1 },
      }),
    })
    renderPage()

    expect(await screen.findByTestId('Issue.Completion.Percent')).toHaveTextContent('70%')
    expect(screen.getByTestId('Issue.Completion.Basis')).toHaveTextContent('by estimate')
  })

  it('says by count when the server says by count, rather than assuming estimates', async () => {
    // The mockup's note assumes ESTIMATE. A hardcoded sentence would be the second contradictory
    // statement of one number that D8 exists to prevent.
    mockReads({
      issue: anIssue({
        type: EPIC_TYPE,
        allowedTransitions: [],
        completion: { percent: 40, basis: 'COUNT', done: 2, total: 5, excludedSpikes: 0 },
      }),
    })
    renderPage()

    expect(await screen.findByTestId('Issue.Completion.Basis')).toHaveTextContent('by count')
    expect(screen.getByTestId('Epic.Completion')).toHaveTextContent('by count')
  })

  it('names the excluded spikes, so the denominator is not simply smaller than the list', async () => {
    mockReads({
      issue: anIssue({
        type: EPIC_TYPE,
        allowedTransitions: [],
        completion: { percent: 70, basis: 'COUNT', done: 12, total: 17, excludedSpikes: 1 },
      }),
    })
    renderPage()

    expect(await screen.findByTestId('Issue.Completion.Excluded')).toHaveTextContent(
      '1 spike excluded',
    )
  })
})

describe('The transition menu', () => {
  it('shows a forbidden move as disabled with its reason, not as absent', async () => {
    /*
      `task.html`'s caption is the requirement — *"Disabled, not hidden — you can see the move
      exists and why it is closed to you."* Hiding it makes the workflow look broken rather than
      configured: the reader cannot tell "this project does not allow that" from "this product
      cannot do that".
    */
    mockReads({
      issue: anIssue({
        key: 'ACME-124',
        type: TASK_TYPE,
        status: PROGRESS,
        allowedTransitions: [READY],
      }),
    })
    renderPage('ACME-124')

    // Present — every status in the project is rendered, not only the legal ones.
    expect(await screen.findByTestId('Issue.Transitions.st-done')).toBeInTheDocument()
    // And it says why, in words on the row: a tooltip on a disabled control never opens.
    expect(screen.getByTestId('Issue.Transitions.st-done.Reason')).toHaveTextContent(
      'not from In Progress',
    )
    expect(screen.queryByTestId('Issue.Transitions.st-done.Move')).toBeNull()
    // The legal one is offered.
    expect(screen.getByTestId('Issue.Transitions.st-ready.Move')).toBeInTheDocument()
  })

  it('renders no transition menu on an epic, and says why', async () => {
    /*
      D31: an epic's status is derived from its stories. A **disabled** menu would read as a
      missing permission — which is what disable-don't-hide means everywhere else in this product
      — so the menu is absent and a sentence stands in its place.
    */
    mockReads({ issue: anIssue({ type: EPIC_TYPE, status: PROGRESS, allowedTransitions: [] }) })
    renderPage()

    const derived = await screen.findByTestId('Issue.Transitions.Derived')
    expect(derived).toHaveTextContent('derived from its stories')
    // Not one status row, disabled or otherwise.
    expect(screen.queryByTestId('Issue.Transitions.st-done')).toBeNull()
    expect(screen.queryByTestId('Issue.Transitions.st-ready.Move')).toBeNull()
  })

  it('surfaces a refused transition beside the picker, not as a toast', async () => {
    /*
      A toast scrolls away and the card is left looking like it moved. The 409 the workflow answers
      with **names the legal moves**, and that sentence is the useful half — which is why
      `tcmsFaults.issue('conflict')` exists: the picker only ever offers legal moves, so no
      ordinary session can produce this.
    */
    drivingAFailedWrite()
    mockReads({
      issue: anIssue({ key: 'ACME-124', type: TASK_TYPE, status: PROGRESS, allowedTransitions: [DONE] }),
    })
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(
        409,
        'This issue can only move to Blocked or In Review from where it stands.',
        null,
        null,
        null,
        'The workflow forbids this move',
      ),
    )

    renderPage('ACME-124')
    await userEvent.click(await screen.findByTestId('Issue.Transitions.st-done.Move'))

    const refusal = await screen.findByTestId('Issue.Transitions.Refused')
    expect(refusal).toHaveTextContent('can only move to Blocked or In Review')
    // Inside the picker's own region, not floating over the page.
    expect(within(screen.getByTestId('Issue.Transitions')).getByTestId('Issue.Transitions.Refused'))
      .toBeInTheDocument()
  })

  it('posts the status id the workflow named', async () => {
    mockReads({
      issue: anIssue({ key: 'ACME-124', type: TASK_TYPE, status: PROGRESS, allowedTransitions: [DONE] }),
    })
    const post = vi.spyOn(api, 'post').mockResolvedValue(anIssue({ key: 'ACME-124' }) as never)

    renderPage('ACME-124')
    await userEvent.click(await screen.findByTestId('Issue.Transitions.st-done.Move'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/issues/ACME-124/transitions',
        { statusId: 'st-done' },
        expect.anything(),
      ),
    )
  })
})

describe('The story’s closing rule (H4)', () => {
  it('says the story did not close itself even when every sub-task is done', async () => {
    // Story H4's third criterion. Sub-tasks finishing is **evidence, not a trigger**, and this is
    // the one claim on `story.html`'s "Closing this story" panel that M1 owns.
    mockReads({
      issue: anIssue({
        key: 'ACME-118',
        type: STORY_TYPE,
        status: PROGRESS,
        allowedTransitions: [DONE],
        children: [
          anIssueSummary({ key: 'ACME-124', type: TASK_TYPE, status: DONE }),
          anIssueSummary({ key: 'ACME-126', type: TASK_TYPE, status: DONE }),
        ],
      }),
    })
    renderPage('ACME-118')

    const panel = await screen.findByTestId('Story.Closing')
    expect(panel).toHaveTextContent('All 2 sub-tasks are done')
    expect(panel).toHaveTextContent('evidence, not a trigger')
  })

  it('renders none of the review gate or the time panel (D-f)', async () => {
    // M5 and M6. An empty "Time" panel on a product with no time tracking reads as a story nobody
    // has logged against, which is a statement about this story rather than about the build.
    mockReads({ issue: anIssue({ key: 'ACME-118', type: STORY_TYPE, status: PROGRESS }) })
    renderPage('ACME-118')

    await screen.findByTestId('Story.Closing')
    expect(screen.queryByText(/Request approval to close/)).toBeNull()
    expect(screen.queryByText(/Log time/)).toBeNull()
    /*
      ~~And no "Linked test cases" panel, which is M3's.~~ → **013 T6 landed it**, so the assertion
      is now the other way round: it is on the story unconditionally, because "no cases are linked"
      is the state it exists to make visible and a release reads that story as `NOT_TESTED`.
    */
    expect(screen.getByTestId('Story.LinkedCases')).toBeInTheDocument()
  })

  it('mounts the linked-cases panel on a story, empty and explaining what it is for', async () => {
    // The QA link's ONLY writer in the product (D-p). Without it every release reads NOT_TESTED
    // forever, which is why its absence-when-empty would be the wrong shape for this panel.
    mockReads({ issue: anIssue({ key: 'ACME-118', type: STORY_TYPE, status: PROGRESS }) })
    renderPage('ACME-118')

    const empty = await screen.findByTestId('Story.LinkedCases.Region.Empty')
    expect(empty.textContent).toContain('No cases linked')
    expect(empty.textContent).toContain('reads as untested rather than as passing')
  })
})

describe('The states no static render exercises', () => {
  it('renders the failed and empty states of every panel through DataRegion', async () => {
    // An epic whose children failed to load must not read as an epic with no children — the second
    // is a real state and the first is recoverable.
    drivingAFailedRead()
    mockReads({
      issue: anIssue({ type: EPIC_TYPE, status: PROGRESS, allowedTransitions: [] }),
      failDocLinks: true,
    })
    renderPage()

    // The spec-link panel says it failed; the rest of the page still renders.
    expect(await screen.findByTestId('Issue.SpecLinks.List.Error')).toBeInTheDocument()
    expect(screen.getByTestId('Issue.Details')).toBeInTheDocument()
  })

  it('reads a failed issue read as failed, not as an issue with nothing on it', async () => {
    drivingAFailedRead()
    mockReads({ failIssue: true })
    renderPage()

    expect(await screen.findByTestId('Issue.Content.Error')).toBeInTheDocument()
    expect(screen.queryByTestId('Issue.Details')).toBeNull()
  })
})

describe('The linked-spec panel and the stale flag', () => {
  it('flags a stale link, shows the excerpt, and explains what re-confirming does', async () => {
    /*
      `epic.html`'s second card. The caption is rendered verbatim because it is the whole
      explanation of what `stale` means — the paraphrase people reach for ("out of date") says the
      link is wrong, when what it says is that nobody has read the new text.
    */
    mockReads({
      issue: anIssue({ type: EPIC_TYPE, status: PROGRESS, allowedTransitions: [] }),
      docLinks: [
        anIssueDocLink({
          id: 'dl-1',
          pageId: 'page-1',
          pageKey: 'DOC-2',
          pageTitle: 'Payments integration notes',
          blockId: 'block-9',
          blockExcerpt: 'Card details are captured in an iframe the provider owns.',
          stale: true,
        }),
      ],
    })
    renderPage()

    const item = await screen.findByTestId('Issue.SpecLinks.Item')
    expect(within(item).getByTestId('Issue.SpecLinks.Item.Stale')).toHaveTextContent(
      'changed since linked',
    )
    expect(within(item).getByTestId('Issue.SpecLinks.Item.Excerpt')).toHaveTextContent('iframe')
    expect(item).toHaveTextContent('Re-confirming replaces it with the current text')
  })

  it('offers no re-confirm on a link that is not stale', async () => {
    // A control that is always there teaches nothing about the state it is for.
    mockReads({
      issue: anIssue({ type: EPIC_TYPE, status: PROGRESS, allowedTransitions: [] }),
      docLinks: [anIssueDocLink({ id: 'dl-1', pageId: 'page-1', blockId: 'block-9', stale: false })],
    })
    renderPage()

    await screen.findByTestId('Issue.SpecLinks.Item')
    expect(screen.queryByTestId('Issue.SpecLinks.Item.Reconfirm')).toBeNull()
  })

  it('re-confirms with the smallest edit there is, because that is what moves updated_at', async () => {
    /*
      There is no re-confirm endpoint and there should not be one: `stale` is a comparison, not a
      stored decision — the page's newest version against the ISSUE's own `updated_at`, which
      `IssueDocLinkFacts` states in its own javadoc along with the consequence *"a write that
      changes nothing a reader cares about still moves updated_at and so forgives the flag"*.
      So the empty `PATCH` is the mechanism, spelled as the one request that performs it.
    */
    mockReads({
      issue: anIssue({ key: 'ACME-101', type: EPIC_TYPE, status: PROGRESS, allowedTransitions: [] }),
      docLinks: [anIssueDocLink({ id: 'dl-1', pageId: 'page-1', blockId: 'block-9', stale: true })],
    })
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(anIssue({ key: 'ACME-101' }) as never)

    renderPage()
    await userEvent.click(await screen.findByTestId('Issue.SpecLinks.Item.Reconfirm'))

    await waitFor(() =>
      expect(patch).toHaveBeenCalledWith('/issues/ACME-101', {}, expect.anything()),
    )
  })

  it('sends "Open at this block" to the page WITH the block, which is what the docs end reads', async () => {
    mockReads({
      issue: anIssue({ type: EPIC_TYPE, status: PROGRESS, allowedTransitions: [] }),
      docLinks: [anIssueDocLink({ id: 'dl-1', pageId: 'page-7', blockId: 'block-9' })],
    })
    renderPage()

    expect(await screen.findByTestId('Issue.SpecLinks.Item.Open')).toHaveAttribute(
      'href',
      '/projects/p1/docs/page-7?block=block-9',
    )
  })
})

describe('Watching', () => {
  it('says which state the person WANTS, not which one they think it is in', async () => {
    // Idempotent in both directions, which is why the endpoint is a PUT with a boolean: a double
    // press cannot toggle twice.
    mockReads({
      issue: anIssue({ key: 'ACME-118', type: STORY_TYPE, watchers: [aUserRef({ id: 'u1' })] }),
    })
    const put = vi.spyOn(api, 'put').mockResolvedValue([] as never)

    render(
      <TestProviders client={createTestQueryClient()} route="/issues/ACME-118" userId="u1">
        <Routes>
          <Route path="/issues/:key" element={<IssueDetailPage />} />
        </Routes>
      </TestProviders>,
    )

    const button = await screen.findByTestId('Issue.Watch')
    expect(button).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(button)

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith('/issues/ACME-118/watchers', { watching: false }, expect.anything()),
    )
  })
})

/**
 * 016 M6's two panels on the two screens that carry them, and the three claims about them a static
 * render cannot make.
 *
 * 1. **No figure is a bare "hours"** (D-d). The `Logged` row in Details says `(approved)` and the
 *    epic panel says `approved hours only`, because approved and pending are different numbers and
 *    a reader cannot act on one that does not say which it is.
 * 2. **`trackTime` off removes them, and removes them rather than emptying them** (D-g). An empty
 *    Time panel would say this story has no hours; absence says this project does not work that way.
 * 3. **A failed rollup costs the panel and not the page.** Both hooks carry `meta.handled` and both
 *    panels are their own region, so the description, the sub-tasks and the transition menu are
 *    still on screen when the rollup 500s.
 */
describe('Time on the story and the epic (016 M6)', () => {
  it('labels the Details row (approved), because a bare "hours" is what D-d rules out', async () => {
    mockReads({
      issue: anIssue({ key: 'ACME-118', type: STORY_TYPE, status: PROGRESS, estimateHours: 16 }),
      rollup: aTimeRollup({ issueKey: 'ACME-118', estimateHours: 16, approvedHours: 14 }),
    })
    renderPage('ACME-118')

    expect(await screen.findByTestId('Issue.Details.Logged')).toHaveTextContent('14h')
    expect(await screen.findByTestId('Issue.Time.Approved')).toHaveTextContent('14h')
    expect(screen.getByText('(approved)')).toBeInTheDocument()
  })

  it('renders pending as its own figure and never folds it into the approved one', async () => {
    mockReads({
      issue: anIssue({ key: 'ACME-118', type: STORY_TYPE, status: PROGRESS }),
      rollup: aTimeRollup({
        issueKey: 'ACME-118',
        estimateHours: 16,
        approvedHours: 6,
        pendingHours: 4,
      }),
    })
    renderPage('ACME-118')

    expect(await screen.findByTestId('Issue.Time.Approved')).toHaveTextContent('6h')
    expect(screen.getByTestId('Issue.Time.Pending')).toHaveTextContent('4h')
    // The failure this pins: a panel that summed them would read 10h and be wrong on every screen
    // in the product at once.
    expect(screen.queryByText('10h')).toBeNull()
  })

  it('leaves the Pending row out entirely when there is none to report', async () => {
    mockReads({
      issue: anIssue({ key: 'ACME-118', type: STORY_TYPE, status: PROGRESS }),
      rollup: aTimeRollup({ issueKey: 'ACME-118', approvedHours: 6 }),
    })
    renderPage('ACME-118')

    await screen.findByTestId('Issue.Time.Approved')
    expect(screen.queryByTestId('Issue.Time.Pending')).toBeNull()
  })

  it('draws neither panel nor the Logged row when the project does not track time', async () => {
    mockReads({
      issue: anIssue({ key: 'ACME-118', type: STORY_TYPE, status: PROGRESS }),
      settings: projectSettings({ projectId: 'p1', trackTime: false }),
    })
    renderPage('ACME-118')

    // The story itself is still there — this is an absence, not a failure.
    await screen.findByTestId('Story.SubTasks')
    expect(screen.queryByTestId('Issue.Time')).toBeNull()
    expect(screen.queryByTestId('Issue.Details.Logged')).toBeNull()
  })

  it('gives the epic its estimate-vs-actual table, highlighting the over-estimate row', async () => {
    mockReads({
      issue: anIssue({ key: 'ACME-100', type: EPIC_TYPE, status: PROGRESS, allowedTransitions: [] }),
      epicRollup: anEpicTimeRollup({
        epicKey: 'ACME-100',
        estimateHours: 24,
        approvedHours: 21,
        children: [
          {
            issueKey: 'ACME-112',
            title: 'Cart survives a sign-in',
            estimateHours: 8,
            approvedHours: 7,
            overEstimate: false,
          },
          {
            issueKey: 'ACME-122',
            title: 'Card capture without a redirect',
            estimateHours: 16,
            approvedHours: 19,
            overEstimate: true,
          },
        ],
      }),
    })
    renderPage('ACME-100')

    const rows = await screen.findAllByTestId('Issue.Time.Epic.Row')
    expect(rows).toHaveLength(2)
    // The sign is written out, because a bare `3h` in a Drift column is ambiguous in the direction
    // that matters and colour alone cannot carry it.
    const drifts = screen.getAllByTestId('Issue.Time.Epic.Drift')
    expect(drifts[0]).toHaveTextContent('−1h')
    expect(drifts[1]).toHaveTextContent('+3h')
    expect(screen.getByTestId('Issue.Time.Epic.Foot')).toHaveTextContent('21h')
    expect(screen.getByText('approved hours only')).toBeInTheDocument()
  })

  it('gives an unestimated child no drift at all rather than a drift equal to its hours', async () => {
    mockReads({
      issue: anIssue({ key: 'ACME-100', type: EPIC_TYPE, status: PROGRESS, allowedTransitions: [] }),
      epicRollup: anEpicTimeRollup({
        epicKey: 'ACME-100',
        approvedHours: 5,
        children: [
          {
            issueKey: 'ACME-120',
            title: 'Address validation',
            estimateHours: null,
            approvedHours: 5,
            overEstimate: false,
          },
        ],
      }),
    })
    renderPage('ACME-100')

    await screen.findByTestId('Issue.Time.Epic.Row')
    // `+5h` beside a blank estimate would be a comparison against a number nobody made.
    expect(screen.queryByTestId('Issue.Time.Epic.Drift')).toBeNull()
  })

  it('keeps the story on screen when the rollup fails — the panel falls over on its own', async () => {
    drivingAFailedRead() // the rollup 500s; `tcmsFaults.only('/time/') + status(500)` is the dev switch
    mockReads({
      issue: anIssue({ key: 'ACME-118', type: STORY_TYPE, status: PROGRESS }),
      failRollup: true,
    })
    renderPage('ACME-118')

    expect(await screen.findByTestId('Story.SubTasks')).toBeInTheDocument()
    expect(await screen.findByTestId('Issue.Time.Region.Error')).toBeInTheDocument()
    expect(screen.queryByTestId('Issue.Details.Logged')).toBeNull()
  })
})
