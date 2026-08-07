import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { DocPage } from '../../api/types'
import { SessionRecoveryContext } from '../../auth/sessionRecoveryContextValue'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { DocPageEditor } from './DocPageEditorPage'

/**
 * The documentation editor.
 *
 * **What this file is really for is the first test.** A block's id is what `doc_link` targets, so
 * an edit that fails to send an existing block's id makes the server issue a new one — a delete
 * and a create — and every case that verified that paragraph now verifies nothing, with nothing
 * reporting it. The client is the only thing that can prevent it, because from the server's side
 * "no id" is indistinguishable from a new block.
 *
 * The specific way it breaks is a framework quirk rather than a typo: `useFieldArray` puts its own
 * `id` on every entry of `fields`, shadowing a data property of the same name. Reading `field.id`
 * looks completely right, submits a fresh uuid for every block on every save, and passes any test
 * that only checks the text came back. So the assertion here is on the **payload**, and it is the
 * only place that mistake is visible.
 *
 * Also locked: the level rule (only a heading has a depth, so a paragraph must not send a stale
 * one), the `If-Match` precondition, and the two 409s landing on the banner rather than being
 * swallowed — the link refusal in particular is the most informative failure this form can get.
 */

const page: DocPage = {
  id: 'page-1',
  key: 'DOC-7',
  parentId: null,
  title: 'Refunds',
  slug: 'refunds-7',
  status: 'DRAFT',
  updatedAt: '2026-08-01T09:00:00Z',
  currentVersionNo: 3,
  lockVersion: 5,
  blocks: [
    { id: 'block-heading', position: 0, blockType: 'heading', level: 2, text: 'Full refudns' },
    { id: 'block-body', position: 1, blockType: 'paragraph', level: null, text: 'Within 5 days.' },
  ],
  // Required since #49 phase 2 gave `DOC_PAGE` a writer. The editor does not render files — that
  // is phase 4's screen — but the fixture has to be a page the server could actually send, and
  // `required` is what turned "this fixture is stale" into a compile error rather than a shape
  // only the browser would have disagreed with.
  attachments: [],
}

const sessionRecovery = {
  reportUnauthorized: () => {},
  registerUnsavedWork: () => () => {},
  hasUnsavedWork: () => false,
  registerReauthHandler: () => () => {},
}

function renderEditor() {
  return render(
    <TestProviders client={createTestQueryClient()} route="/projects/p1/docs/page-1/edit">
      {/*
        Two providers the shared harness deliberately does not carry: the editor registers unsaved
        work (which throws without the session-recovery context, by design) and announces a save.
        Stubbed rather than mounting the real ones — a test that fails because the notification
        stack changed is a test of the wrong thing.
      */}
      <SessionRecoveryContext.Provider value={sessionRecovery}>
        <SnackbarProvider>
          <DocPageEditor projectId="p1" page={page} />
        </SnackbarProvider>
      </SessionRecoveryContext.Provider>
    </TestProviders>,
  )
}

/** The body of the one PATCH the editor sent. */
function patchedBody(patch: ReturnType<typeof vi.spyOn>) {
  return (patch.mock.calls[0] as unknown[])[1] as {
    title: string
    blocks: { id?: string; blockType: string; level: number | null; text: string }[]
  }
}

describe('DocPageEditorPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sends every existing block back with its own id, however much the text changed', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ ...page, currentVersionNo: 4 } as never)
    renderEditor()

    const texts = await screen.findAllByTestId('DocEditor.Block.Text')
    await userEvent.clear(texts[0])
    await userEvent.type(texts[0], 'Full refunds')

    await userEvent.click(screen.getByTestId('DocEditor.Save'))
    await waitFor(() => expect(patch).toHaveBeenCalled())

    const body = patchedBody(patch)

    // THE assertion. Read `field.id` instead of the data id and both of these become uuids the
    // server has never seen — which it answers 400 to, but only because it happens to refuse an
    // unknown id. Omit the id entirely and it answers 201-shaped success while quietly voiding
    // every link on the page.
    expect(body.blocks[0].id).toBe('block-heading')
    expect(body.blocks[1].id).toBe('block-body')
    expect(body.blocks[0].text).toBe('Full refunds')
  })

  it('sends no id for a block added here, because the server issues it', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(page as never)
    renderEditor()

    await userEvent.click(await screen.findByTestId('DocEditor.AddBlock'))
    const texts = await screen.findAllByTestId('DocEditor.Block.Text')
    await userEvent.type(texts[texts.length - 1], 'A new paragraph.')
    await userEvent.click(screen.getByTestId('DocEditor.Save'))
    await waitFor(() => expect(patch).toHaveBeenCalled())

    const body = patchedBody(patch)
    expect(body.blocks).toHaveLength(3)
    // Absent, not null: "no id" without asserting one. The server treats both the same, and this
    // is the shape that says what is meant.
    expect(body.blocks[2]).not.toHaveProperty('id')
    expect(body.blocks[0].id).toBe('block-heading')
  })

  it('drops a block from the payload when it is removed, and keeps the others identified', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(page as never)
    renderEditor()

    const removes = await screen.findAllByTestId('DocEditor.Block.Remove')
    await userEvent.click(removes[0])
    await userEvent.click(screen.getByTestId('DocEditor.Save'))
    await waitFor(() => expect(patch).toHaveBeenCalled())

    const body = patchedBody(patch)
    expect(body.blocks).toHaveLength(1)
    // The surviving block keeps its identity. A removal that re-identified the rest would break
    // links on paragraphs nobody touched — the collateral this whole design refuses.
    expect(body.blocks[0].id).toBe('block-body')
  })

  it('sends a depth only for a heading, and none once the type changes away', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(page as never)
    renderEditor()

    await waitFor(() => expect(screen.getAllByTestId('DocEditor.Block')).toHaveLength(2))

    // The heading starts at H2; the paragraph beside it has no depth control at all.
    expect(screen.getAllByTestId('DocEditor.Block.Level')).toHaveLength(1)

    await userEvent.click(screen.getByTestId('DocEditor.Save'))
    await waitFor(() => expect(patch).toHaveBeenCalled())

    const body = patchedBody(patch)
    expect(body.blocks[0].level).toBe(2)
    // The server refuses a level on a non-heading rather than ignoring it, so a stale value here
    // is a 400 on a field the user cannot see.
    expect(body.blocks[1].level).toBeNull()
  })

  it('sends the lockVersion it read as If-Match', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(page as never)
    renderEditor()

    await userEvent.click(await screen.findByTestId('DocEditor.Save'))
    await waitFor(() => expect(patch).toHaveBeenCalled())

    // The header, never the body — a body field would be a value on the input schema that must
    // never persist (§52's suite rename is the precedent for getting this wrong).
    expect(patch.mock.calls[0][2]).toMatchObject({ headers: { 'If-Match': '5' } })
    expect(patchedBody(patch)).not.toHaveProperty('lockVersion')
  })

  it('renders the link refusal on the form rather than swallowing it', async () => {
    vi.spyOn(api, 'patch').mockRejectedValue(
      // The TITLE matters as much as the detail. `describeError` tells the two 409s apart by it:
      // untitled, this would have rendered as the lost-update copy — "reload to see their
      // version" — which is wrong advice for a refusal nobody else caused, on a page that is
      // already current. Constructing the error the way the server actually answers is what
      // makes this test about the form rather than about the error mapper.
      new ApiError(
        409,
        '3 links point at content you are removing, and deleting it would break them silently. ' +
          'Remove the links first, or archive the page instead — an archived page keeps its ' +
          'links resolvable.',
        null,
        null,
        null,
        'Something still links here',
      ),
    )
    renderEditor()

    const removes = await screen.findAllByTestId('DocEditor.Block.Remove')
    await userEvent.click(removes[1])
    await userEvent.click(screen.getByTestId('DocEditor.Save'))

    // The server's own sentence: it is the only one that names the action that does work, and
    // that action — Archive — is on the page the user came from.
    expect(await screen.findByTestId('DocEditor.Error')).toHaveTextContent(/archive the page/i)
  })

  it('lands a block-keyed field error on the block it is about', async () => {
    vi.spyOn(api, 'patch').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        'blocks[1].text': ['Enter text.'],
      }),
    )
    renderEditor()

    await userEvent.click(await screen.findByTestId('DocEditor.Save'))

    // On the row, not as one banner saying a block somewhere is wrong — a specification page can
    // be fifty blocks long.
    expect(await screen.findByText('Enter text.')).toBeInTheDocument()
    expect(screen.queryByTestId('DocEditor.Error')).not.toBeInTheDocument()
  })
})
