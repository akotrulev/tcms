import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { aProject, aProjectWorkflow, anIssue, anIssueType } from '../../test/fixtures'
import { IssueNewPage } from './IssueNewPage'

/**
 * `issue-new.html`, and the three claims a static render cannot make.
 *
 * 1. **Quick add is ONE FIELD** (story H3, D29). A solo project should not need an epic and a
 *    story before "a thing to do", so pressing Add with a title and nothing else has to produce
 *    an issue — and the request must carry exactly a type and a title.
 * 2. **A sub-task with no parent is refused HERE, naming the field.** The server refuses it too,
 *    with a 400 naming the level; the form must not let somebody type a title, press Create and
 *    be told no.
 * 3. **Target release, Labels and the AI-eligible toggle are ABSENT** (D-f), and so are the "Where
 *    this lands" release and backlog cards.
 */

const STORY = anIssueType({ id: 'ty-story', name: 'Story', level: 'STORY' })
const SUBTASK = anIssueType({ id: 'ty-sub', name: 'Sub-task', level: 'TASK' })
const EPIC = anIssueType({
  id: 'ty-epic',
  name: 'Epic',
  level: 'EPIC',
  countsTowardCompletion: false,
})

function Address() {
  const location = useLocation()
  return <span data-testid="Address">{location.pathname}</span>
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route="/projects/p1/work/new">
      <Routes>
        <Route
          path="/projects/:projectId/work/new"
          element={
            <>
              <IssueNewPage />
              <Address />
            </>
          }
        />
        <Route path="/issues/:key" element={<Address />} />
      </Routes>
    </TestProviders>,
  )
}

function mockReads() {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['issue:read', 'issue:create'])
    // Anchored, so `/projects/p1/workflow` is not caught by the project arm's prefix.
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) {
      return aProjectWorkflow({ types: [STORY, SUBTASK, EPIC] })
    }
    if (/^\/projects\/[^/]+\/members$/.test(path)) return []
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: 'p1', name: 'Acme Storefront' })
    if (path.startsWith('/issues')) return []
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('Quick add is one field (story H3)', () => {
  it('creates from a title alone, sending only the type and the title', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue(anIssue({ key: 'ACME-140' }) as never)

    renderPage()
    await userEvent.type(await screen.findByTestId('IssueNew.Quick.Title'), 'Renew the cert')
    await userEvent.click(screen.getByTestId('IssueNew.Quick.Add'))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/issues',
        // No parent, no epic, no assignee, no estimate. The whole of D29: a quick add that
        // quietly attached the issue to something would be a decision nobody made.
        { typeId: 'ty-story', title: 'Renew the cert', projectId: 'p1' },
        expect.anything(),
      ),
    )
  })

  it('says what pressing Add will produce, in the project’s own words', async () => {
    // The type is a ROW, not an enum — a caption naming a hardcoded "Task" would be wrong on any
    // project that renamed it, and the create would fail on one that removed it.
    mockReads()
    renderPage()

    expect(await screen.findByTestId('IssueNew.Quick.Type')).toHaveTextContent('Story')
    expect(screen.getByTestId('IssueNew.Quick')).toHaveTextContent(
      'Creates a Story at the story level with no parent',
    )
  })

  it('lands on the new issue rather than back where it started', async () => {
    mockReads()
    vi.spyOn(api, 'post').mockResolvedValue(anIssue({ key: 'ACME-140' }) as never)

    renderPage()
    await userEvent.type(await screen.findByTestId('IssueNew.Quick.Title'), 'Renew the cert')
    await userEvent.click(screen.getByTestId('IssueNew.Quick.Add'))

    // The key is allocated server-side and is never a field on the request, so the create's own
    // answer is the only place the caller learns where to go.
    await waitFor(() => expect(screen.getByTestId('Address')).toHaveTextContent('/issues/ACME-140'))
  })
})

describe('The depth rule, from the client end', () => {
  it('refuses a sub-task with no parent and names the field', async () => {
    /*
      The server refuses it too (a 400 naming the level), and the form must not let a person type
      a title, press Create and be told no — the parent picker appears the moment a TASK-level
      type is chosen.
    */
    mockReads()
    const post = vi.spyOn(api, 'post')

    renderPage()
    await userEvent.click(await screen.findByTestId('IssueNew.Type.ty-sub'))
    await userEvent.type(screen.getByTestId('IssueNew.Title'), 'Validate the postcode')
    await userEvent.click(screen.getByTestId('IssueNew.Create'))

    expect(await screen.findByText(/A sub-task belongs to a story/)).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('offers no parent field at all on an epic, which has no level above it', async () => {
    // Refused by the server for the mirror reason. A disabled "not applicable" control would be a
    // control explaining a rule instead of the rule simply not applying.
    mockReads()
    renderPage()

    await userEvent.click(await screen.findByTestId('IssueNew.Type.ty-epic'))
    expect(screen.queryByTestId('IssueNew.Parent')).toBeNull()
    expect(screen.queryByTestId('IssueNew.Epic')).toBeNull()
  })

  it('offers an OPTIONAL epic on a story, because requiring one forces a junk epic per stray bug', () => {
    mockReads()
    renderPage()

    return waitFor(() => expect(screen.getByTestId('IssueNew.Epic')).toBeInTheDocument())
  })
})

describe('What is absent rather than empty (D-f)', () => {
  it('renders no target release, no labels and no AI-eligible toggle', async () => {
    /*
      Those belong to M3, M2 and M5. An empty Release select reads as a release that is not set,
      not as a feature that has not shipped — which is why the rule here is absence rather than
      this product's usual disable-don't-hide.
    */
    mockReads()
    renderPage()

    await screen.findByTestId('IssueNew.Full')
    expect(screen.queryByLabelText(/release/i)).toBeNull()
    expect(screen.queryByLabelText(/labels/i)).toBeNull()
    expect(screen.queryByRole('switch', { name: /ai-eligible/i })).toBeNull()
  })

  it('keeps the epic card in "Where this lands" and drops the release and backlog cards', async () => {
    // The epic card is M1's own behaviour — completion recalculating on create — and is the thing
    // a person choosing an epic is actually deciding about.
    mockReads()
    renderPage()

    const lands = await screen.findByTestId('IssueNew.Lands')
    expect(lands).toHaveTextContent('completion')
    expect(lands).not.toHaveTextContent('readiness')
    expect(lands).not.toHaveTextContent('Backlog')
  })
})
