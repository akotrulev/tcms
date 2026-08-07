import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as client from '../api/client'
import { api } from '../api/client'
import type { Attachment } from '../api/types'
import { TestProviders } from '../test/TestProviders'
import { createTestQueryClient } from '../test/queryClient'
import { heldPermissions } from '../test/permissions'
import { AttachmentsPanel } from './AttachmentList'
import { MAX_UPLOAD_BYTES } from '../lib/attachments'

/**
 * The shared attachment panel — and specifically the **three** things about it that no type, lint
 * rule or build can see.
 *
 * 1. **The delete gate is NOT author-qualified.** `EvidencePanel` beside it is (`evidence:delete`
 *    has an `OWN` arm), it is the closest existing pattern, and copying it here is the change a
 *    reviewer would make in the name of consistency. It would be wrong: a case attachment is part
 *    of the case definition, which `case:edit` already owns wholesale, so an authorship arm would
 *    make removing the screenshot stricter than removing the step that references it. Nothing
 *    on screen distinguishes the two implementations — the check is that a colleague's row and an
 *    unattributed row are **enabled**.
 * 2. **The download is a fetch, not an anchor.** An `<a href="/api/v1/…">` sends no credential in
 *    this app (the session is a bearer token) and answers 401 while rendering as a working link.
 *    Both the DOM shape and the transport are asserted, because either alone passes against the
 *    broken version: an anchor styled as a button still has no `href` problem until you follow it,
 *    and a `fetchArtifact` call proves nothing if the visible control is a second, plain link.
 * 3. **A file the server will refuse is refused before it is uploaded**, and the two refusals are
 *    different sentences. `lib/attachments.test.ts` owns the arithmetic; this owns the wiring.
 */

const PROJECT = 'p1'
const CALLER = '99999999-9999-4999-8999-999999999999'
const COLLEAGUE = '88888888-8888-4888-8888-888888888888'
const TEST_ID = 'CaseDetail.Attachments'

const person = (id: string) => ({
  id,
  displayName: id === CALLER ? 'Ana Petrova' : 'Marco Silva',
  email: `${id}@example.test`,
  avatarUrl: null,
})

function attachment(overrides: Partial<Attachment> = {}): Attachment {
  return {
    id: 'a1',
    filename: 'wire-diagram.png',
    contentType: 'image/png',
    sizeBytes: 184_320,
    url: `/api/v1/projects/${PROJECT}/cases/c1/attachments/a1/content`,
    uploadedAt: '2026-08-01T09:00:00Z',
    uploadedBy: person(CALLER),
    ...overrides,
  } as Attachment
}

function mockPermissions(keys: string[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(keys)
    return []
  }) as never)
}

// Typed to the props they satisfy, not to a bare `Mock`: `vi.fn()`'s inferred type is not
// assignable to a specific signature, and widening the prop instead would give up the check that
// this panel calls its callbacks with the right argument.
let onAttach: ReturnType<typeof vi.fn<(file: File) => void>>
let onDelete: ReturnType<typeof vi.fn<(attachmentId: string) => void>>

function renderPanel(rows: Attachment[], props: { attaching?: boolean; attachError?: unknown } = {}) {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/cases/c1`} userId={CALLER}>
      <AttachmentsPanel
        projectId={PROJECT}
        permission="case:edit"
        attachments={rows}
        onAttach={onAttach}
        attaching={props.attaching ?? false}
        attachError={props.attachError ?? null}
        onDelete={onDelete}
        deleting={false}
        testId={TEST_ID}
        emptyBody="No files attached."
      />
    </TestProviders>,
  )
}

const deleteButtons = () => screen.getAllByTestId(`${TEST_ID}.Delete`)

/**
 * Wait until the permission answer has landed.
 *
 * `useCan` is fail-closed while pending, so every gated control is disabled on the first render
 * even for a caller who is about to be allowed — and `waitFor(...toBeDisabled())` is satisfied
 * instantly by that pending state. Attach is the barrier, exactly as in `EvidencePanel.test.tsx`.
 */
async function permissionsResolved() {
  await waitFor(() => expect(screen.getByTestId(`${TEST_ID}.Attach`)).toBeEnabled())
}

/** A `File` of a stated size without allocating it — `size` is read-only on the real thing. */
function sized(name: string, size: number): File {
  const file = new File(['x'], name, { type: 'image/png' })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

/** `fireEvent`, not `userEvent.upload`: the input is `hidden`, which pointer events cannot reach. */
function pick(file: File) {
  fireEvent.change(screen.getByTestId(`${TEST_ID}.Input`), { target: { files: [file] } })
}

beforeEach(() => {
  vi.restoreAllMocks()
  onAttach = vi.fn()
  onDelete = vi.fn()
  mockPermissions(['case:edit'])
})

describe('AttachmentsPanel — the delete gate reads the caller, never the row', () => {
  it('offers Delete on a colleagues attachment', async () => {
    // THE test of this file. `evidence:delete`'s OWN arm one component over would refuse this, and
    // the tidy change that introduces it passes every other assertion here.
    renderPanel([attachment({ uploadedBy: person(COLLEAGUE) })])
    await permissionsResolved()

    await waitFor(() => expect(deleteButtons()[0]).toBeEnabled())
  })

  it('offers Delete on an attachment with no recorded uploader', async () => {
    // `attachment.created_by` is nullable (V1 permits it; a future system actor would use it). An
    // OWN-qualified gate answers a null author `false`, so this row is the second half of the
    // check and the one an authorship arm would silently take away.
    renderPanel([attachment({ uploadedBy: null })])
    await permissionsResolved()

    await waitFor(() => expect(deleteButtons()[0]).toBeEnabled())
  })

  it('disables both controls for a caller without case:edit, and still shows the files', async () => {
    // Disable-don't-hide (§31). A reader who may not edit the case still needs the sample data
    // the steps depend on.
    mockPermissions([])
    renderPanel([attachment(), attachment({ id: 'a2', filename: 'sample.csv' })])

    expect(screen.getAllByTestId(`${TEST_ID}.Row`)).toHaveLength(2)
    await waitFor(() => expect(deleteButtons()[0]).toBeDisabled())
    expect(screen.getByTestId(`${TEST_ID}.Attach`)).toBeDisabled()
  })

  it('sends the row id to the caller rather than deleting anything itself', async () => {
    renderPanel([attachment({ id: 'a7' })])
    await permissionsResolved()
    await waitFor(() => expect(deleteButtons()[0]).toBeEnabled())

    await userEvent.click(deleteButtons()[0])

    expect(onDelete).toHaveBeenCalledWith('a7')
  })
})

describe('AttachmentsPanel — the download carries the credential', () => {
  it('renders the filename as a button and never as an anchor', async () => {
    // The DOM half. An anchor here would send no `Authorization` header and answer 401 while
    // looking exactly like this does.
    renderPanel([attachment()])

    const control = screen.getByTestId(`${TEST_ID}.Download`)
    expect(control.tagName).toBe('BUTTON')
    expect(control).not.toHaveAttribute('href')
    expect(screen.queryByRole('link', { name: /wire-diagram/i })).toBeNull()
  })

  it('fetches the bytes through the guarded reader and saves them under the served name', async () => {
    // The transport half. `fetchArtifact` is where the same-origin guard, the bearer token and the
    // 60s budget live; a component that reached for `fetch` directly would have none of them.
    const fetchArtifact = vi
      .spyOn(client, 'fetchArtifact')
      .mockResolvedValue({ blob: new Blob(['bytes']), filename: 'served-name.png' })
    const saved = vi.fn()
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      saved(this.download)
    })

    renderPanel([attachment()])
    await userEvent.click(screen.getByTestId(`${TEST_ID}.Download`))

    await waitFor(() => expect(fetchArtifact).toHaveBeenCalledWith(
      `/api/v1/projects/${PROJECT}/cases/c1/attachments/a1/content`,
    ))
    // The response's own name wins over the column — it is the server that knows what it served.
    await waitFor(() => expect(saved).toHaveBeenCalledWith('served-name.png'))
  })

  it('reports a failed download beside the row rather than as a corner toast', async () => {
    vi.spyOn(client, 'fetchArtifact').mockRejectedValue(new client.ApiError(404, 'Gone'))

    renderPanel([attachment()])
    await userEvent.click(screen.getByTestId(`${TEST_ID}.Download`))

    await waitFor(() =>
      expect(screen.getByTestId(`${TEST_ID}.Download.Error`)).toBeInTheDocument(),
    )
    // The row survives, so the reader can press it again.
    expect(screen.getByTestId(`${TEST_ID}.Row`)).toBeInTheDocument()
  })

  it('names the destination for a screen reader while keeping the filename visible', async () => {
    // WCAG 2.5.3: the visible label is contained in the accessible name, so voice control still
    // works on the words on screen.
    renderPanel([attachment()])

    const control = screen.getByTestId(`${TEST_ID}.Download`)
    expect(control).toHaveAccessibleName('Download wire-diagram.png')
    expect(control).toHaveTextContent('wire-diagram.png')
  })
})

describe('AttachmentsPanel — what it refuses before the server has to', () => {
  it('refuses a file over the instance limit and uploads nothing', async () => {
    renderPanel([])
    await permissionsResolved()

    pick(sized('huge.mov', MAX_UPLOAD_BYTES + 1))

    expect(screen.getByTestId(`${TEST_ID}.Refused`)).toHaveTextContent(/huge\.mov/)
    expect(onAttach).not.toHaveBeenCalled()
  })

  it('refuses an empty file with its own sentence', async () => {
    renderPanel([])
    await permissionsResolved()

    pick(sized('empty.txt', 0))

    expect(screen.getByTestId(`${TEST_ID}.Refused`)).toHaveTextContent(/empty/i)
    expect(onAttach).not.toHaveBeenCalled()
  })

  it('passes an acceptable file straight through', async () => {
    renderPanel([])
    await permissionsResolved()

    const file = sized('sample.csv', 2_048)
    pick(file)

    expect(onAttach).toHaveBeenCalledWith(file)
  })

  it('accepts the SAME file picked twice in a row', async () => {
    // The input's value is cleared on change so re-picking an identical file fires again — §67's
    // finding, and the first thing anyone does after a failed upload.
    renderPanel([])
    await permissionsResolved()

    const file = sized('sample.csv', 2_048)
    pick(file)
    pick(file)

    expect(onAttach).toHaveBeenCalledTimes(2)
  })
})

describe('AttachmentsPanel — the list itself', () => {
  it('renders the newest first, deterministically', async () => {
    // The server's order is not part of the contract, so an unsorted list re-orders itself on a
    // refetch and the row somebody was about to press moves.
    renderPanel([
      attachment({ id: 'old', filename: 'first.png', uploadedAt: '2026-07-01T00:00:00Z' }),
      attachment({ id: 'new', filename: 'latest.png', uploadedAt: '2026-08-05T00:00:00Z' }),
    ])

    const names = screen.getAllByTestId(`${TEST_ID}.Download`).map((el) => el.textContent)
    expect(names).toEqual(['latest.png', 'first.png'])
  })

  it('says what belongs here rather than showing an empty box', async () => {
    renderPanel([])

    expect(screen.getByTestId(`${TEST_ID}.Empty`)).toHaveTextContent('No files attached.')
    expect(screen.queryByTestId(`${TEST_ID}.List`)).toBeNull()
  })

  it('renders a failed upload in place', async () => {
    renderPanel([], { attachError: new client.ApiError(413, 'That file is too large.') })

    expect(screen.getByTestId(`${TEST_ID}.Error`)).toBeInTheDocument()
  })
})
