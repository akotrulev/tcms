import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Artifact } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { ArtifactTable } from './ArtifactTable'

/**
 * The register's three reachable kinds render **three different controls**, and one of them must
 * not be an anchor.
 *
 * This row exists because the table is the one place in the product where a link that leaves for
 * somebody else's site sits directly beside a link that fetches from this API with a bearer token.
 * An `EXTERNAL_URL` *is* an `<a href>` — it has to be — and a `FILE` copied from it would render
 * identically, look correct in a review, and answer **401** the moment anyone clicked it. Nothing
 * about the string in `url` says which of the two it is; `kind` does, and that is what this asserts.
 */

const base = {
  scope: 'PROJECT',
  category: 'SignOff',
  status: 'ACTIVE',
  inherited: false,
  description: null,
  docPageId: null,
  filename: null,
  sizeBytes: null,
  updatedBy: { id: 'u1', displayName: 'L. Lead', email: 'l@x.test', avatarUrl: null },
  updatedAt: '2026-08-02T00:00:00Z',
} as const

const rows = [
  {
    ...base,
    id: 'a-file',
    kind: 'FILE',
    title: 'Release 1 sign-off',
    filename: 'sign-off.pdf',
    sizeBytes: 82_000,
    url: '/api/v1/projects/p1/artifacts/a-file/content',
  },
  {
    ...base,
    id: 'a-link',
    kind: 'EXTERNAL_URL',
    title: 'Environment wiki',
    url: 'https://wiki.example.test/env',
  },
] as unknown as Artifact[]

function renderTable() {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <ArtifactTable rows={rows} showInheritance={false} projectId="p1" />
    </TestProviders>,
  )
}

describe('ArtifactTable', () => {
  it('downloads a FILE through a button and links an EXTERNAL_URL through an anchor', () => {
    renderTable()

    const download = screen.getByTestId('Artifacts.Row.Download')
    expect(download.tagName).toBe('BUTTON')
    expect(download).not.toHaveAttribute('href')

    // The other half of the pair, asserted in the same render: the external link keeps its `href`.
    // Without it this file would pass equally well against a table that turned BOTH into buttons,
    // which would break the one kind that genuinely is a hyperlink.
    expect(screen.getByTestId('Artifacts.Row.Link')).toHaveAttribute(
      'href',
      'https://wiki.example.test/env',
    )
  })

  it('names the uploaded file for a screen reader while showing the artifact title', () => {
    // Two different strings on purpose: the register lists documents by what they ARE, and what
    // lands in the downloads folder is what the uploader called the file.
    renderTable()

    const download = screen.getByTestId('Artifacts.Row.Download')
    expect(download).toHaveTextContent('Release 1 sign-off')
    expect(download).toHaveAccessibleName('Download sign-off.pdf')
  })
})
