import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, aRelease } from '../../test/fixtures'
import { ReleaseNewPage } from './ReleaseNewPage'

/**
 * `release-new.html`, and the three things on it that are requirements.
 *
 * 1. **A version is the only required field** — the form must not ask for a date or a description,
 *    because `ReleaseInput` does not.
 * 2. **The blank date is sent as `null`, not `""`.** An empty `<input type="date">` reads as an
 *    empty string, and `""` is a 400 on a field the person deliberately left alone. This is the one
 *    thing here a reader cannot see and a test can.
 * 3. **The "Not in this product" card is rendered.** It is where somebody looking for a sprint field
 *    finds out there is not one; a form that merely omitted the field would leave them hunting.
 */

const PROJECT = 'p1'

function mockReads() {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(['release:read', 'release:manage'], {}, PROJECT)
    }
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Acme Storefront' })
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/releases/new`}>
      <Routes>
        <Route path="/projects/:projectId/releases/new" element={<ReleaseNewPage />} />
        <Route path="/projects/:projectId/releases/:releaseId" element={<p>the release</p>} />
      </Routes>
    </TestProviders>,
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('the form', () => {
  it('creates from a version alone, and sends the blank date as null', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue(aRelease({ id: 'r-26', version: '2.6' }) as never)
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByTestId('ReleaseNew.Version'), '2.6')
    await user.click(screen.getByTestId('ReleaseNew.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0]?.[0]).toBe(`/projects/${PROJECT}/releases`)
    /*
      `null` and not `''`. The contract's `targetDate` is `format: date` and nullable; an empty
      string is neither, so this is the assertion that keeps a person who left the date alone from
      getting a 400 about it.
    */
    expect(post.mock.calls[0]?.[1]).toEqual({
      version: '2.6',
      targetDate: null,
      description: null,
    })
  })

  it('refuses an empty version on the field, without asking the server', async () => {
    mockReads()
    const post = vi.spyOn(api, 'post')
    const user = userEvent.setup()
    renderPage()

    // The submit is `release:manage`-gated and starts disabled, so clicking before the verdict
    // lands is a click on a `pointer-events: none` button — which looks exactly like a form that
    // refused nothing.
    await waitFor(() => expect(screen.getByTestId('ReleaseNew.Submit')).toBeEnabled())
    await user.click(screen.getByTestId('ReleaseNew.Submit'))

    expect(await screen.findByText('Give it a version.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('renders the 409 on the version field as the server’s own sentence', async () => {
    /*
      A duplicate version inside the project (D13, case-insensitively) is the one failure a person
      can act on from here, so it belongs under the box they would fix — not in a banner above a
      form whose other two fields were fine.
    */
    const { drivingAFailedWrite } = await import('../../test/renderFailures')
    // The subject: the create is refused as a conflict.
    drivingAFailedWrite()

    mockReads()
    vi.spyOn(api, 'post').mockRejectedValue(
      /*
        The title matters as much as the status: `describeError` reads *"Someone else saved first"*
        as the lost-update case and reloads; anything else keeps its own `detail`, which is the
        sentence that actually says what to do. A duplicate version is not a lost update.
      */
      new ApiError(409, 'Version 2.6 already exists in this project.', null, null, null, 'That version already exists'),
    )
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByTestId('ReleaseNew.Version'), '2.6')
    await user.click(screen.getByTestId('ReleaseNew.Submit'))

    expect(await screen.findByText(/already exists/i)).toBeInTheDocument()
    // Not a banner: the form-level slot must stay empty, or the refusal is stated twice.
    expect(screen.queryByTestId('ReleaseNew.Error')).not.toBeInTheDocument()
  })
})

describe('what this product does not have', () => {
  it('names the three absences verbatim', async () => {
    mockReads()
    renderPage()

    const card = await screen.findByTestId('ReleaseNew.NotHere')
    expect(card).toHaveTextContent('Sprints')
    expect(card).toHaveTextContent('Velocity and sprint burndown')
    expect(card).toHaveTextContent('A release shared between two projects')
    expect(card).toHaveTextContent('Planning has a cadence because releases have dates')
  })

  it('says a target date is optional and what happens without one', async () => {
    mockReads()
    renderPage()

    expect(
      await screen.findByText(/Without one the release still exists/),
    ).toBeInTheDocument()
  })
})
