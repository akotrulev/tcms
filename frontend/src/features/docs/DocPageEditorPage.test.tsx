import { render, screen, waitFor, within } from '@testing-library/react'
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
    // `links` and `coverage` joined DocBlock's required set in §168, when the coverage marker
    // stopped being a field nothing served — same mechanism as `attachments` below, one field and
    // 119 sections later. A heading gets `coverage: null` like everything else here: the server
    // does send a marker on a heading when one is linked, and it is the *page* that declines to
    // render it, which is a decision DocsPage owns rather than a shape the fixture should hide.
    {
      id: 'block-heading',
      position: 0,
      blockType: 'heading',
      level: 2,
      text: 'Full refudns',
      links: [],
      coverage: null,
    },
    {
      id: 'block-body',
      position: 1,
      blockType: 'paragraph',
      level: null,
      text: 'Within 5 days.',
      links: [],
      coverage: null,
    },
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

function renderEditor(subject: DocPage = page) {
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
          <DocPageEditor projectId="p1" page={subject} />
        </SnackbarProvider>
      </SessionRecoveryContext.Provider>
    </TestProviders>,
  )
}

/**
 * Opens a MUI select and picks an option.
 *
 * The `data-testid` sits on the hidden `<input>` (`slotProps.htmlInput`), which carries
 * `pointer-events: none` — so clicking it throws rather than opening the menu. The visible control
 * is the sibling `combobox`, and the options render into a portal on the document.
 */
async function choose(testId: string, optionName: string | RegExp) {
  const select = screen.getByTestId(testId).closest('.MuiInputBase-root')!
  await userEvent.click(within(select as HTMLElement).getByRole('combobox'))
  await userEvent.click(await screen.findByRole('option', { name: optionName }))
}

/** The body of the one PATCH the editor sent. */
function patchedBody(patch: ReturnType<typeof vi.spyOn>) {
  return (patch.mock.calls[0] as unknown[])[1] as {
    title: string
    blocks: {
      id?: string
      blockType: string
      level: number | null
      text: string
      rows: string[][] | null
      attachmentId: string | null
    }[]
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

/**
 * The three types this form could not author until #48 phase 3 — and the one it could read and
 * never write.
 *
 * A `table` and an `image` carry a payload beyond `text`, and the form had no control for either,
 * so the only way to put one on a page was a hand-written request. The **list** case is different
 * and worse: §93 widened the contract so a bullet could carry an indent depth, the reader has
 * rendered that indent ever since, and this form offered the Depth control to headings only — so
 * the field could be read and never authored. A round trip with one end missing.
 */
describe('DocPageEditorPage — the payload-carrying block types', () => {
  const withTable: DocPage = {
    ...page,
    blocks: [
      {
        id: 'block-table',
        position: 0,
        blockType: 'table',
        level: null,
        text: 'Transfer limits',
        rows: [
          ['Tier', 'Daily limit'],
          ['Basic', '1000'],
        ],
      },
    ],
  } as DocPage

  const withImage: DocPage = {
    ...page,
    blocks: [
      {
        id: 'block-image',
        position: 0,
        blockType: 'image',
        level: null,
        text: 'The checkout screen',
        attachmentId: 'file-1',
      },
    ],
    attachments: [
      {
        id: 'file-1',
        filename: 'wireframe.png',
        contentType: 'image/png',
        sizeBytes: 2048,
        url: '/api/v1/projects/p1/doc-pages/page-1/attachments/file-1/content',
        uploadedAt: '2026-08-01T09:00:00Z',
        uploadedBy: null,
      },
    ],
  } as DocPage

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('edits a cell in the grid and sends the whole table back as rows', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ ...withTable, currentVersionNo: 4 } as never)
    renderEditor(withTable)

    const cells = await screen.findAllByTestId('DocEditor.Block.Rows.Cell')
    await userEvent.clear(cells[3])
    await userEvent.type(cells[3], '2500')
    await userEvent.click(screen.getByTestId('DocEditor.Save'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    expect(patchedBody(patch).blocks[0].rows).toEqual([
      ['Tier', 'Daily limit'],
      ['Basic', '2500'],
    ])
  })

  it('ADDS A COLUMN TO EVERY ROW — a ragged table is unconstructable here', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ ...withTable, currentVersionNo: 4 } as never)
    renderEditor(withTable)

    await userEvent.click(await screen.findByTestId('DocEditor.Block.Rows.AddColumn'))
    await userEvent.click(screen.getByTestId('DocEditor.Save'))

    // The server refuses a ragged table by naming the short row, which is a message the author can
    // only act on by counting. Widening the whole grid at once means they never see it.
    await waitFor(() => expect(patch).toHaveBeenCalled())
    const rows = patchedBody(patch).blocks[0].rows ?? []
    expect(rows.map((row) => row.length)).toEqual([3, 3])
  })

  it('refuses to remove the header row, and says why', async () => {
    renderEditor(withTable)

    // The first row IS the header — there is no flag saying so, on the server or here, because a
    // boolean's two states look identical in an editor and differ only in what a screen reader
    // announces for every cell below.
    const removals = await screen.findAllByTestId('DocEditor.Block.Rows.RemoveRow')
    expect(removals[0]).toBeDisabled()
    expect(removals[1]).toBeEnabled()
  })

  it('SENDS A LIST BULLET’S INDENT — the half of §93 that was never authorable', async () => {
    const withList: DocPage = {
      ...page,
      blocks: [
        { id: 'block-list', position: 0, blockType: 'list', level: 1, text: 'The refund is partial.' },
      ],
    } as DocPage
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ ...withList, currentVersionNo: 4 } as never)
    renderEditor(withList)

    // The control exists for a list at all, which it did not before: the reader has indented
    // bullets by this field since §93 and nothing could set it.
    await screen.findByTestId('DocEditor.Block.Level')
    await choose('DocEditor.Block.Level', '3')
    await userEvent.click(screen.getByTestId('DocEditor.Save'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    expect(patchedBody(patch).blocks[0].level).toBe(3)
  })

  it('picks an image from the page’s own files and sends its id', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ ...withImage, currentVersionNo: 4 } as never)
    renderEditor(withImage)

    expect(await screen.findByTestId('DocEditor.Block.File')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('DocEditor.Save'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    expect(patchedBody(patch).blocks[0].attachmentId).toBe('file-1')
    // And the alt text is `text`, which the server refuses when blank.
    expect(patchedBody(patch).blocks[0].text).toBe('The checkout screen')
  })

  it('tells an author with no files where to attach one, instead of an empty picker', async () => {
    // A block names an attachment the page already owns, so the upload lives on the page's own
    // panel — which owns the size limit, the empty-file refusal and the 413. An upload control
    // here would be a second copy of all three.
    renderEditor({ ...withImage, attachments: [] } as DocPage)

    expect(await screen.findByTestId('DocEditor.Block.File.None')).toBeInTheDocument()
  })

  it('DROPS THE CELLS when a table is turned into a paragraph', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ ...withTable, currentVersionNo: 4 } as never)
    renderEditor(withTable)

    await screen.findByTestId('DocEditor.Block.Type')
    await choose('DocEditor.Block.Type', 'Paragraph')
    await userEvent.click(screen.getByTestId('DocEditor.Save'))

    // Every type-specific field is derived from the TYPE at submit, never from what the control
    // last held. Sending the rows anyway is a 400 the author cannot see the cause of, on an
    // ordinary thing to do while writing.
    await waitFor(() => expect(patch).toHaveBeenCalled())
    expect(patchedBody(patch).blocks[0].rows).toBeNull()
  })
})
