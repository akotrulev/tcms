import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as client from '../api/client'
import type { Attachment, DocBlock } from '../api/types'
import { TestProviders } from '../test/TestProviders'
import { createTestQueryClient } from '../test/queryClient'
import { DocBlockBody } from './DocBlockBody'
import { drivingAFailedRead } from '../test/renderFailures'

/**
 * The one renderer for all six block types, and specifically the four things about it that no
 * type, lint rule or build can see.
 *
 * 1. **Four of the six types had no branch at all.** `paragraph`, `code`, `table` and `image` fell
 *    through to a plain paragraph, so a stored table rendered as its caption and a stored image
 *    rendered as its alt text. Every one of those cases *type-checked perfectly* — `text` is a
 *    required string on every block — which is exactly why the gap survived from M1 to #48.
 * 2. **An image is fetched, never `<img src={url}>`.** The session is a bearer token this client
 *    sets per request, so a browser-initiated request to `/api/v1/…` carries no credential and
 *    answers 401 — rendering as a broken image with every gate green. Both halves are asserted,
 *    because either alone passes against the broken version: the `src` being a `blob:` URL proves
 *    nothing if the fetch was unguarded, and a `fetchArtifact` call proves nothing if the visible
 *    element points somewhere else.
 * 3. **A table is a real `<table>` with a real header row.** `getByRole('columnheader')` is the
 *    assertion, not a text match: a `<td>` containing the word "Tier" renders identically and
 *    announces as an ordinary cell, which is the difference between a table a screen-reader user
 *    can navigate and a grid of unlabelled values.
 * 4. **The `default` arm is reachable and must stay.** A block type this bundle does not know still
 *    has words in it, and the server is free to be a version ahead.
 */

const PAGE_URL = '/api/v1/projects/p1/doc-pages/d1/attachments/a1/content'

function block(overrides: Partial<DocBlock> = {}): DocBlock {
  return {
    id: 'b1',
    position: 0,
    blockType: 'paragraph',
    level: null,
    text: 'A refund is issued within five days.',
    rows: null,
    attachmentId: null,
    links: [],
    coverage: null,
    ...overrides,
  }
}

function file(overrides: Partial<Attachment> = {}): Attachment {
  return {
    id: 'a1',
    filename: 'wireframe.png',
    contentType: 'image/png',
    sizeBytes: 1024,
    url: PAGE_URL,
    uploadedAt: '2026-08-01T09:00:00Z',
    uploadedBy: null,
    ...overrides,
  }
}

function renderBlock(one: DocBlock, attachments: Attachment[] = []) {
  return render(
    <TestProviders client={createTestQueryClient()} route="/projects/p1/docs/d1">
      <DocBlockBody block={one} attachments={attachments} testId="Docs.Block" />
    </TestProviders>,
  )
}

beforeEach(() => {
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:rendered')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('DocBlockBody', () => {
  it('renders a paragraph with the newlines the author typed', () => {
    renderBlock(block({ text: 'First line.\nSecond line.' }))

    // `pre-wrap`, not a collapsed run: the editor stores the newlines, so a renderer that dropped
    // them would show a different document from the one being edited two clicks away.
    const paragraph = screen.getByText(/First line/)
    expect(paragraph).toHaveStyle({ whiteSpace: 'pre-wrap' })
  })

  it('renders a code block as preformatted source, not as prose', () => {
    const { container } = renderBlock(
      block({ blockType: 'code', text: 'GET /api/v1/cases\nAccept: application/json' }),
    )

    // The <pre>/<code> pair is what announces this as source. A monospace font on a <p> looks
    // identical and tells a screen reader nothing — which is what the documentation page did.
    expect(container.querySelector('pre > code')).not.toBeNull()
    expect(screen.getByTestId('Docs.Block.Code')).toHaveTextContent('GET /api/v1/cases')
  })

  it('indents a list bullet by its level, on BOTH screens (they used to disagree)', () => {
    renderBlock(block({ blockType: 'list', level: 3, text: 'The refund is partial.' }))

    const bullet = screen.getByTestId('Docs.Block.Bullet')
    expect(bullet).toBeInTheDocument()

    /*
      `level` doing its second job (§93). The plan panel indented and the documentation page did
      not — one stored outline, two readings, nothing failing.

      The assertion is on the `calc()` MUI v7 emits rather than on `48px`, because jsdom does not
      resolve `var(--mui-spacing)` and a resolved-pixel assertion silently compares against an
      empty string. The multiplier IS the depth arithmetic — 3 → (3-1)*3 = 6 spacing units.
    */
    expect(getComputedStyle(bullet).paddingLeft).toBe('calc(6 * var(--mui-spacing))')
  })

  it('renders a list bullet with no level at depth 1 rather than crashing', () => {
    // Every list block written before §93 reads back with `level: null`.
    renderBlock(block({ blockType: 'list', level: null, text: 'An unindented bullet.' }))

    expect(screen.getByTestId('Docs.Block.Bullet')).toHaveStyle({ paddingLeft: '0px' })
  })

  it('renders a table with a real header row and the caption as a <caption>', () => {
    renderBlock(
      block({
        blockType: 'table',
        text: 'Transfer limits by tier',
        rows: [
          ['Tier', 'Daily limit'],
          ['Basic', '1000'],
        ],
      }),
    )

    // `columnheader`, not `getByText('Tier')`: a <td> holding the same word renders identically
    // and announces as an ordinary cell, so a text assertion passes against the broken version.
    expect(screen.getByRole('columnheader', { name: 'Tier' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Daily limit' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Basic' })).toBeInTheDocument()

    // The caption is the table's accessible name, which is what a screen reader announces on
    // arrival. A paragraph above the table is a separate thing that merely sits nearby.
    expect(screen.getByRole('table', { name: 'Transfer limits by tier' })).toBeInTheDocument()
  })

  it('says a table has no rows rather than rendering a caption above nothing', () => {
    // The server refuses an empty table, so this is a block written by something else. A caption
    // alone reads as a table still loading.
    renderBlock(block({ blockType: 'table', text: 'Limits', rows: [] }))

    expect(screen.getByTestId('Docs.Block.Table.Empty')).toBeInTheDocument()
  })

  it('FETCHES an image through the guarded reader and shows it from an object URL', async () => {
    const fetchArtifact = vi
      .spyOn(client, 'fetchArtifact')
      .mockResolvedValue({ blob: new Blob(['bytes']), filename: 'wireframe.png' })

    renderBlock(
      block({ blockType: 'image', text: 'The checkout screen', attachmentId: 'a1' }),
      [file()],
    )

    // The transport half: `fetchArtifact` owns the same-origin guard, the bearer token and the
    // budget. A component reaching for `<img src>` would have none of them and would 401.
    await waitFor(() => expect(fetchArtifact).toHaveBeenCalledWith(PAGE_URL, expect.anything()))

    // And the DOM half. Either assertion alone passes against the broken version.
    const image = await screen.findByTestId('Docs.Block.Image')
    expect(image).toHaveAttribute('src', 'blob:rendered')
    expect(image).toHaveAttribute('alt', 'The checkout screen')
  })

  it('offers the download when the image cannot be rendered, instead of an apology', async () => {
    // The failed blob read is the subject: the download offer is what replaces the image.
    drivingAFailedRead()
    vi.spyOn(client, 'fetchArtifact').mockRejectedValue(new Error('nope'))

    renderBlock(
      block({ blockType: 'image', text: 'The checkout screen', attachmentId: 'a1' }),
      [file()],
    )

    // The bytes may be perfectly fine and unrenderable — a TIFF, a PDF somebody attached as a
    // diagram. Offering the file is a better answer than reporting a failure and stopping.
    expect(await screen.findByTestId('Docs.Block.Image.Error')).toBeInTheDocument()
    expect(screen.getByTestId('Docs.Block.Image.Download')).toBeInTheDocument()
  })

  it('names a missing attachment rather than rendering nothing', () => {
    // Both ends are guarded on the server, so this should be unreachable — which is precisely why
    // it must be visible. A block that silently renders nothing is how a broken guard stays unseen.
    renderBlock(
      block({ blockType: 'image', text: 'The checkout screen', attachmentId: 'gone' }),
      [file()],
    )

    expect(screen.getByTestId('Docs.Block.Image.Missing')).toHaveTextContent('The checkout screen')
  })

  it('renders the words of a block type this build does not know', () => {
    // The server may be a version ahead of this bundle — `BlockContent` carries
    // `@JsonIgnoreProperties(ignoreUnknown = true)` for the mirror-image reason. A gap where a
    // requirement should be is worse than an unstyled paragraph.
    renderBlock({ ...block(), blockType: 'diagram' as DocBlock['blockType'], text: 'Sequence A' })

    expect(screen.getByText('Sequence A')).toBeInTheDocument()
  })
})
