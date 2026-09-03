import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import {
  aProject,
  aProjectWorkflow,
  aStatusRef,
  anImportBlocker,
  anImportPreview,
  anImportRun,
  aPreviewStatusRow,
  aSourceProject,
} from '../../test/fixtures'
import { ImportWizardPage } from './ImportWizardPage'

/**
 * The wizard, over its three steps.
 *
 * Four claims, and each is one the screens cannot make on their own:
 *
 * 1. **An unmapped row disables Confirm and says why**, with the count of issues at stake. The
 *    server refuses the same request with a `400`; this is the check and that is the backstop, so a
 *    test that only proved the server refuses would prove nothing about the screen.
 * 2. **A `KEY_COLLISION` blocker cannot continue**, and the offending keys are on screen. This is
 *    the state `import-preview.html` does not draw — the mockup has a Continue button and no
 *    blocked shape — so there is no drawing to compare it against and only the test holds it.
 * 3. **A refused source-project list renders the source system's own sentence and no dropdown.**
 *    Two halves. The sentence, because a `502` here is the one 5xx that is not ours and
 *    `describeError`'s *"the server ran into a problem"* would send the reader looking for a fault
 *    in this product (`sourceError.ts`). The absent picker, because a `<select>` with nothing in it
 *    says *this account can see no projects*, which is the one wrong thing to conclude from a
 *    rejected token.
 * 4. **Confirm sends every source status the preview listed**, which is what `POST /imports`
 *    requires and what the disabled state exists to guarantee.
 */

const PROJECT = 'p1'
const BACKLOG = aStatusRef({ id: 's1', name: 'Backlog', kind: 'NOT_STARTED', position: 0 })
const DONE = aStatusRef({ id: 's2', name: 'Done', kind: 'DONE', position: 1 })

const CONNECTED = { projects: [aSourceProject({ key: 'PROJ', name: 'Storefront', issueCount: 1482 })] }

/** A preview with one matched status and one the source has no equivalent for. */
function previewWithAnUnmappedStatus() {
  return anImportPreview({
    projectId: PROJECT,
    totals: {
      issues: 1482,
      labels: 214,
      comments: 4892,
      historyEntries: 11306,
      fixVersions: 38,
      sprintValues: 1204,
    },
    statuses: [
      aPreviewStatusRow({ sourceStatusName: 'To Do', count: 312, matched: true, suggestedStatusId: 's1' }),
      aPreviewStatusRow({ sourceStatusName: 'Awaiting Deploy', count: 87 }),
    ],
  })
}

function mockReads() {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(['issue:read', 'issue:import'], {}, PROJECT)
    }
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Acme Storefront' })
    if (/^\/projects\/[^/]+\/workflow$/.test(path)) {
      return aProjectWorkflow({ projectId: PROJECT, statuses: [BACKLOG, DONE] })
    }
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/imports/new`}>
      <Routes>
        <Route path="/projects/:projectId/imports/new" element={<ImportWizardPage />} />
        <Route path="/projects/:projectId/imports/:importRunId" element={<p>the run page</p>} />
        <Route path="/projects/:projectId/settings/workflow" element={<TheWorkflowScreen />} />
      </Routes>
    </TestProviders>,
  )
}

/**
 * A stand-in for `WorkflowSettingsPage` with the one affordance the wizard's promise depends on.
 *
 * The real screen has no Back button — the browser's is what a person uses — and `MemoryRouter` has
 * no browser, so `navigate(-1)` is how a test drives the same history move. That is the whole of
 * what is being checked: the wizard put its state on the entry the back move lands on.
 */
function TheWorkflowScreen() {
  const navigate = useNavigate()
  return (
    <button type="button" onClick={() => navigate(-1)}>
      the workflow screen
    </button>
  )
}

/** Fills the connect form and walks to the preview, which every test past the first needs. */
async function reachThePreview(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByTestId('ImportConnect.Site'), 'https://northwind.atlassian.net')
  await user.type(screen.getByTestId('ImportConnect.Email'), 'ops@northwind.test')
  await user.type(screen.getByTestId('ImportConnect.Token'), 'a-read-only-token')
  await user.click(screen.getByTestId('ImportConnect.Connect'))

  await screen.findByTestId('ImportConnect.SourceProject')
  await user.selectOptions(screen.getByTestId('ImportConnect.SourceProject'), 'PROJ')
  await user.click(screen.getByTestId('ImportConnect.RunPreview'))
  await screen.findByTestId('ImportPreview.NothingWritten')
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('connect', () => {
  it('reports a rejected credential under the fields, and offers no empty dropdown', async () => {
    const { drivingAFailedWrite } = await import('../../test/renderFailures')
    /*
      The subject: the source-project list is refused. It is `drivingAFailedWrite` and not
      `drivingAFailedRead` because the operation is a `useMutation` — a `POST` whose body carries the
      API token, which must never become a query key (`api/queries/imports.ts`). The harness
      classifies by mechanism; the behaviour under test is still a read that failed.
    */
    drivingAFailedWrite()
    mockReads()
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(
        502,
        'The source system rejected the credential. It may have expired or been revoked.',
        null,
        null,
        null,
        'The source system did not answer',
      ),
    )
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByTestId('ImportConnect.Site'), 'https://northwind.atlassian.net')
    await user.type(screen.getByTestId('ImportConnect.Email'), 'ops@northwind.test')
    await user.type(screen.getByTestId('ImportConnect.Token'), 'a-revoked-token')
    await user.click(screen.getByTestId('ImportConnect.Connect'))

    expect(await screen.findByTestId('ImportConnect.ConnectError')).toHaveTextContent(/revoked/)
    /*
      The picker must not appear at all. A `<select>` with no options says "this account can see no
      projects", which is a different fact from "this token was refused" and the one a person would
      act on wrongly.
    */
    expect(screen.queryByTestId('ImportConnect.SourceProject')).not.toBeInTheDocument()
    expect(screen.queryByTestId('ImportConnect.Connected')).not.toBeInTheDocument()
  })

  it('refuses a plain-HTTP site without asking the source', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post')
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByTestId('ImportConnect.Site'), 'http://northwind.example')
    await user.type(screen.getByTestId('ImportConnect.Email'), 'ops@northwind.test')
    await user.type(screen.getByTestId('ImportConnect.Token'), 'a-token')
    await user.click(screen.getByTestId('ImportConnect.Connect'))

    expect(await screen.findByText(/HTTPS only/)).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })
})

describe('preview', () => {
  it('says nothing has been written, and never sends the token in a URL', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post').mockImplementation((async (path: string) => {
      if (path === '/imports/source-projects') return CONNECTED
      if (path === '/imports/preview') return previewWithAnUnmappedStatus()
      throw new Error(`unmocked write: ${path}`)
    }) as never)
    const user = userEvent.setup()
    renderPage()

    await reachThePreview(user)

    expect(screen.getByTestId('ImportPreview.NothingWritten')).toHaveTextContent(
      /No data has been written/,
    )
    /*
      D-a's cost, made checkable: both credential-carrying operations are POSTs with the token in the
      body. A token in a query string is in the access log, the proxy log and the browser history.
    */
    for (const [path] of post.mock.calls) {
      expect(path).not.toContain('?')
    }
    expect(post.mock.calls.map(([path]) => path)).toEqual([
      '/imports/source-projects',
      '/imports/preview',
    ])
  })

  it('replaces Continue with the blocker, and names the colliding keys', async () => {
    mockReads()
    vi.spyOn(api, 'post').mockImplementation((async (path: string) => {
      if (path === '/imports/source-projects') return CONNECTED
      if (path === '/imports/preview') {
        return anImportPreview({
          projectId: PROJECT,
          blockers: [
            anImportBlocker({
              detail: 'Three keys in PROJ already exist in this company.',
              keys: ['PROJ-14', 'PROJ-4812'],
            }),
          ],
        })
      }
      throw new Error(`unmocked write: ${path}`)
    }) as never)
    const user = userEvent.setup()
    renderPage()

    await reachThePreview(user)

    const blocker = await screen.findByTestId('ImportPreview.Blocker')
    expect(blocker).toHaveTextContent(/already exist/)
    // The keys as chips, because "three keys collide" is not something a person can act on.
    expect(within(screen.getByTestId('ImportPreview.Blocker.Keys')).getByText('PROJ-4812')).toBeInTheDocument()

    // Replaced, not disabled: no press of Continue could succeed for anybody.
    expect(screen.queryByTestId('ImportPreview.Continue')).not.toBeInTheDocument()
    expect(screen.getByTestId('ImportPreview.CannotContinue')).toBeInTheDocument()
  })
})

describe('mapping', () => {
  function mockTheWalk() {
    mockReads()
    return vi.spyOn(api, 'post').mockImplementation((async (path: string) => {
      if (path === '/imports/source-projects') return CONNECTED
      if (path === '/imports/preview') return previewWithAnUnmappedStatus()
      if (path === '/imports') return anImportRun({ id: 'ir-9', projectId: PROJECT, status: 'RUNNING' })
      throw new Error(`unmocked write: ${path}`)
    }) as never)
  }

  it('disables Confirm while a status is unmapped, and says what it would cost', async () => {
    mockTheWalk()
    const user = userEvent.setup()
    renderPage()

    await reachThePreview(user)
    await user.click(screen.getByTestId('ImportPreview.Continue'))

    const confirm = await screen.findByTestId('ImportMapping.Confirm')
    expect(confirm).toBeDisabled()
    /*
      The reason on the control itself. A disabled button with no title is a dead end; the number is
      what makes the refusal read as care — those 87 issues would have landed in the wrong column.
    */
    expect(confirm).toHaveAttribute('title', expect.stringContaining('87 issues'))
    expect(screen.getByTestId('ImportMapping.UnmappedCount')).toHaveTextContent('1 unmapped')
    expect(screen.getByTestId('ImportMapping.Unmapped')).toHaveTextContent(/Awaiting Deploy/)
  })

  it('confirms once every status has a target, sending all of them', async () => {
    const post = mockTheWalk()
    const user = userEvent.setup()
    renderPage()

    await reachThePreview(user)
    await user.click(screen.getByTestId('ImportPreview.Continue'))

    const targets = await screen.findAllByTestId('ImportMapping.Row.Target')
    // The matched row arrives pre-filled; only the unmapped one needs a person.
    expect(targets[0]).toHaveValue('s1')
    await user.selectOptions(targets[1] as HTMLSelectElement, 's2')

    const confirm = screen.getByTestId('ImportMapping.Confirm')
    await waitFor(() => expect(confirm).toBeEnabled())
    await user.click(confirm)

    await waitFor(() => expect(post).toHaveBeenCalledWith('/imports', expect.anything(), expect.anything()))
    const [, body, options] = post.mock.calls.find(([path]) => path === '/imports') ?? []
    /*
      Every source status the preview listed, or the server answers 400 — which is why the mapping is
      built from `preview.statuses` rather than from the entries a person happened to touch.
    */
    expect(body).toMatchObject({
      projectId: PROJECT,
      sourceProjectKey: 'PROJ',
      statusMapping: [
        { sourceStatusName: 'To Do', targetStatusId: 's1' },
        { sourceStatusName: 'Awaiting Deploy', targetStatusId: 's2' },
      ],
      options: { labelPerSprint: false, preserveBacklogOrder: true },
    })
    // One key per intent, so a confirm that timed out is safe to press again.
    expect((options as { idempotencyKey?: string } | undefined)?.idempotencyKey).toBeTruthy()

    expect(await screen.findByText('the run page')).toBeInTheDocument()
  })

  it('keeps the connection and the mapping across the trip to the workflow screen', async () => {
    mockTheWalk()
    const user = userEvent.setup()
    renderPage()

    await reachThePreview(user)
    await user.click(screen.getByTestId('ImportPreview.Continue'))
    await user.click(await screen.findByTestId('ImportMapping.EditWorkflow'))

    /*
      Back, the way the mockup means it — *"the mapping picks it up on return"*. The wizard put its
      state on the entry Back returns to before pushing the workflow route, so what comes back is the
      mapping step and not an empty connect form. A person who left to add "Awaiting Deploy" and
      returned to re-type a 40-character token has been punished for following the screen's advice.
    */
    await user.click(await screen.findByText('the workflow screen'))

    expect(await screen.findByTestId('ImportMapping.Confirm')).toBeInTheDocument()
    expect(screen.getByTestId('ImportMapping.Unmapped')).toHaveTextContent(/Awaiting Deploy/)
  })
})
