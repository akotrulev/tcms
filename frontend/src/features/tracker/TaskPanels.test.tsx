import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import {
  anAgentTaskContext,
  anIssue,
  anIssueType,
  aProjectRepository,
  aStatusRef,
} from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { AssistantRailPanel, TaskPanels } from './TaskPanels'

/**
 * `task.html`'s assistant half.
 *
 * The properties worth pinning are the two absences and one presence. **A task nobody flagged
 * fetches nothing and renders nothing extra** — the panels are conditional on the flag, not on the
 * module, so an ordinary sub-task looks exactly as it did before M5. **A project with no
 * repositories says so** rather than dropping the row, because "it checked out the wrong thing" and
 * "it was never told where the code is" are the two answers a reader is choosing between. And the
 * panel renders the **composite the assistant was handed**, not a reassembly of it, which is why
 * the moves come out of `context.issue.allowedTransitions`.
 */

const TASK = anIssue({
  key: 'ACME-124',
  title: 'Address validation',
  type: anIssueType({ name: 'Sub-task', level: 'TASK' }),
  agentEligible: true,
})

function mockReads(context: unknown) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read', 'issue:edit'])
    if (/^\/issues\/[^/]+\/agent-context$/.test(path)) return context
    if (/^\/issues\/[^/]+\/doc-links$/.test(path)) return []
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPanels(issue = TASK) {
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route={`/issues/${issue.key}`}>
      <TaskPanels issue={issue} />
    </TestProviders>,
  )
}

describe('What the assistant received', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('is not fetched or drawn for a task nobody flagged', async () => {
    const get = vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read'])
      if (/^\/issues\/[^/]+\/doc-links$/.test(path)) return []
      throw new Error(`unmocked read: ${path}`)
    }) as never)

    renderPanels(anIssue({ key: 'ACME-9', agentEligible: false }))

    await waitFor(() => expect(screen.getByTestId('Task.Description')).toBeInTheDocument())
    expect(screen.queryByTestId('Task.AgentContext')).toBeNull()
    expect(get.mock.calls.some(([path]) => String(path).includes('agent-context'))).toBe(false)
  })

  it('renders the story, its criteria, the repository and the build command it was sent', async () => {
    mockReads(
      anAgentTaskContext({
        issue: { ...TASK, allowedTransitions: [aStatusRef({ id: 's2', name: 'In Review' })] },
        story: {
          key: 'ACME-118',
          title: 'Guest checkout without an account',
          acceptanceCriteria: [{ text: 'Addresses are validated', done: false }],
        },
        epic: { key: 'ACME-90', title: 'Checkout', goal: 'Single-page checkout' },
        repositories: [aProjectRepository()],
      }),
    )
    renderPanels()

    expect(await screen.findByTestId('Task.AgentContext.Story')).toHaveTextContent('ACME-118')
    expect(screen.getByTestId('Task.AgentContext.Criterion')).toHaveTextContent(
      'Addresses are validated',
    )
    expect(screen.getByTestId('Task.AgentContext.Epic')).toHaveTextContent('Single-page checkout')
    expect(screen.getByTestId('Task.AgentContext.Build')).toHaveTextContent('npm ci')
    expect(screen.getByTestId('Task.AgentContext.Moves')).toHaveTextContent('In Review')
  })

  it('says a project lists no repository rather than dropping the row', async () => {
    mockReads(anAgentTaskContext({ issue: TASK, repositories: [] }))
    renderPanels()

    expect(await screen.findByTestId('Task.AgentContext.NoRepository')).toHaveTextContent(
      'whoever runs the assistant tells it where the code is',
    )
  })

  it('links the pull request out, and only when one came back', async () => {
    mockReads(anAgentTaskContext({ issue: TASK }))
    const { unmount } = renderPanels()
    await waitFor(() => expect(screen.queryByTestId('Task.PullRequest')).toBeNull())
    unmount()

    renderPanels({ ...TASK, prUrl: 'https://github.test/northwind/acme/pull/482' })
    const link = await screen.findByTestId('Task.PullRequest.Link')
    expect(link).toHaveAttribute('href', 'https://github.test/northwind/acme/pull/482')
    expect(link).toHaveAttribute('rel', 'noreferrer')
  })
})

describe('The AI-eligible switch', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('PATCHes the issue rather than going through a write path of its own', async () => {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read', 'issue:edit'])
      throw new Error(`unmocked read: ${path}`)
    }) as never)
    // The whole `Issue`, not `{}` — a PATCH answers with it, and a double returning an empty
    // object is eleven contract violations.
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(
      anIssue({ key: 'ACME-124', agentEligible: false }) as never,
    )

    render(
      <TestProviders client={createTestQueryClient()} userId="user-1" route="/issues/ACME-124">
        <AssistantRailPanel issue={TASK} />
      </TestProviders>,
    )

    await waitFor(() =>
      expect(screen.getByTestId('Task.Assistant.Toggle')).not.toBeDisabled(),
    )
    await userEvent.click(screen.getByTestId('Task.Assistant.Toggle'))

    await waitFor(() =>
      expect(patch).toHaveBeenCalledWith(
        '/issues/ACME-124',
        { agentEligible: false },
        expect.anything(),
      ),
    )
  })
})
