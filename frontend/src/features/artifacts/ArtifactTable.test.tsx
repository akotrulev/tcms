import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Artifact, PermissionKey } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { anArtifact } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
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

/**
 * The row actions read `artifact:edit` at the ROW's scope, so every render in this file issues the
 * permission query — see §202: a test whose read fails now fails, rather than quietly rendering the
 * fail-closed state and asserting against it.
 */
function mockPermissions(
  permissions: PermissionKey[],
  qualified: Record<string, 'OWN' | 'PLAN_SCOPE'> = {},
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, qualified)
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderTable(given: Artifact[] = rows) {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <ArtifactTable rows={given} showInheritance={false} projectId="p1" />
    </TestProviders>,
  )
}

describe('ArtifactTable', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockPermissions(['artifact:edit'])
  })

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

  /*
    §209 — the two acts, and the gate read at the row's own scope.

    The register is the one table in the product holding rows at two scopes side by side, and
    `artifact:edit` is qualified by exactly that. Asking the plain key would enable the control on
    every row for a Test Engineer and turn each project-scope click into a 403 — `ui-rules` §4's
    renders-then-rejects.
  */
  describe('Row actions', () => {
    const superseded = anArtifact({
      id: 'a-old',
      title: 'Sign-off v1',
      status: 'SUPERSEDED',
      supersededById: 'a-new',
    })
    const replacement = anArtifact({ id: 'a-new', title: 'Sign-off v2' })
    const planRow = anArtifact({ id: 'a-plan', scope: 'PLAN', title: 'Cycle snapshot' })

    it('archives an ACTIVE row and offers Restore as a second, disabled item', async () => {
      const put = vi
        .spyOn(api, 'put')
        .mockResolvedValue(anArtifact({ id: 'a-new', status: 'ARCHIVED' }) as never)
      renderTable([replacement])

      // The gate is fail-closed while the permission query is in flight, so a pending verdict and
      // a refused one render identically — §208's rule for the same shape one module over.
      await waitFor(() => expect(screen.getByTestId('Artifacts.Row.Actions')).toBeEnabled())
      await userEvent.click(screen.getByTestId('Artifacts.Row.Actions'))
      // Both items are rendered, always. One control flipping between Archive and Restore is the
      // meaning-swapping toggle `ui-rules` §4 refuses; the state decides which is live.
      expect(await screen.findByTestId('Artifacts.Row.Restore')).toHaveAttribute('aria-disabled', 'true')
      await userEvent.click(screen.getByTestId('Artifacts.Row.Archive'))

      await waitFor(() =>
        expect(put).toHaveBeenCalledWith('/projects/p1/artifacts/a-new/status', {
          status: 'ARCHIVED',
        }),
      )
    })

    it('names what replaced a superseded row, and refuses both acts on it', async () => {
      renderTable([superseded, replacement])

      const chip = screen.getAllByTestId('Artifacts.Row.Status')[0]
      expect(chip).toHaveTextContent('Superseded')
      // Titles, never ids: the pointer is a uuid and the reader needs the document's name. It is
      // resolved from the rows already on screen, which the same-scope rule guarantees.
      await userEvent.hover(chip)
      expect(await screen.findByRole('tooltip')).toHaveTextContent('Sign-off v2')

      const actions = screen.getAllByTestId('Artifacts.Row.Actions')
      expect(actions.find((button) => button.getAttribute('aria-label')?.includes('Sign-off v1')))
        .toBeDisabled()
    })

    it('disables the actions for a PROJECT row when the grant is PLAN_SCOPE, and not for a plan row', async () => {
      mockPermissions(['artifact:edit'], { 'artifact:edit': 'PLAN_SCOPE' })
      renderTable([replacement, planRow])

      const byRow = (title: string) =>
        screen
          .getAllByTestId('Artifacts.Row.Actions')
          .find((button) => button.getAttribute('aria-label')?.includes(title))!

      await waitFor(() => expect(byRow('Cycle snapshot')).toBeEnabled())
      expect(byRow('Sign-off v2'))
        .toBeDisabled()
    })
  })
})
