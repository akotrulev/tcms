import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { createTestQueryClient } from '../test/queryClient'
import { heldPermissions } from '../test/permissions'
import { TestProviders } from '../test/TestProviders'
import {
  aCaseSearchHit,
  aDocPageSearchHit,
  aProject,
  anIssueSearchHit,
} from '../test/fixtures'
import { useLocation } from 'react-router-dom'
import { CommandPalette } from './CommandPalette'

/**
 * The palette's **Phase 2** fan-out (021 §7 Phase 2, R3, T8) — issues, cases and docs.
 *
 * The Phase-1 behaviour (opening, the `/` guard, places, gating, the key jump, the `/search`
 * floor) is pinned in `AppShell.test.tsx`, where it is exercised through the shell that owns the
 * shortcut. What is here is what T8 added, and each case is a way the fan-out is most often
 * shipped subtly wrong:
 *
 * 1. **Three groups from three reads, not one merged list.** A palette that flattened them would
 *    put a doc page between two issues with no way to tell which kind a row is.
 * 2. **The docs read is per project and is not fanned out over the reader's projects.** The
 *    request must carry the ACTIVE project and no other, which is the assertion a merged loop
 *    cannot pass.
 * 3. **No active project is a stated absence, not a silent one.** A reader on `/my-work` who types
 *    a word and sees no docs must be told docs are searched inside a project — otherwise the
 *    honest answer "we did not look" is indistinguishable from "there is nothing".
 * 4. **The placeholder names what the field covers.** It is the only thing a reader is told before
 *    they type, and it was accurate for a cases-only palette right up until it was not.
 */

const OLB = '11111111-1111-4111-8111-111111111111'

/**
 * Records every path requested, and answers each search arm with its own shape.
 *
 * Anchored arms, and `/issues/search` is tested before `/cases/search` for no ordering reason —
 * the two share no prefix. The doc arm is the one that must NOT be matched loosely: a
 * `path.includes('/doc-pages')` would also catch the page detail read, which is a different shape.
 */
function mockReads(
  paths: string[] = [],
  arms: {
    issues?: unknown[]
    cases?: unknown[]
    docs?: unknown[]
  } = {},
) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    paths.push(path)
    if (path.startsWith('/me/permissions')) return heldPermissions(['project:read', 'issue:read'])
    if (path.startsWith('/issues/search')) return arms.issues ?? []
    if (path.startsWith('/cases/search')) return { items: arms.cases ?? [], page: 0, size: 6, total: 0 }
    if (/^\/projects\/[^/]+\/doc-pages\/search/.test(path)) return arms.docs ?? []
    if (path === '/projects') return [aProject({ id: OLB, key: 'OLB', name: 'Online Banking' })]
    return []
  }) as never)
  return paths
}

function renderPalette(projectId: string | undefined) {
  return render(
    <TestProviders client={createTestQueryClient()} userId="user-1">
      <CommandPalette projectId={projectId} />
    </TestProviders>,
  )
}

async function open(projectId: string | undefined, query: string) {
  const user = userEvent.setup()
  renderPalette(projectId)
  await user.click(screen.getByTestId('TopBar.Search'))
  await user.type(await screen.findByTestId('Palette.Input'), query)
  return user
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('The palette, searching issues, cases and docs', () => {
  it('labels the trigger as a jump, and the dialog names all three kinds it searches', async () => {
    const user = userEvent.setup()
    mockReads()
    renderPalette(OLB)

    // The bar's trigger (023 §3 row 7) says what the control does; the dialog's input still names
    // the three reads it searches.
    expect(screen.getByTestId('TopBar.Search')).toHaveTextContent('Search or jump to…')
    expect(screen.getByTestId('TopBar.Search')).toHaveAccessibleName('Search or jump to…')
    await user.click(screen.getByTestId('TopBar.Search'))
    expect(await screen.findByTestId('Palette.Input')).toHaveAttribute('placeholder', 'Search issues, cases, docs')
  })

  it('groups issues, cases and the active project’s docs, each under its own heading', async () => {
    mockReads([], {
      issues: [anIssueSearchHit({ key: 'ACME-141', title: 'Receipt PDF for a partial refund' })],
      cases: [aCaseSearchHit({ key: 'TC-518', title: 'Refund excludes shipping' })],
      docs: [aDocPageSearchHit({ id: 'page-1', key: 'DOC-4', title: 'Refund policy' })],
    })

    await open(OLB, 'refund')

    /*
      Asserted through the group each row is IN, not merely that the text is on screen. A flattened
      list would satisfy three `getByText`s and still be the defect: the groups are what tell a
      reader whether `Refund policy` is a story, a case or a page before they press Enter.
    */
    const list = within(screen.getByTestId('Palette'))
    await waitFor(() => expect(list.getByText('Receipt PDF for a partial refund')).toBeInTheDocument())

    const issues = list.getByRole('group', { name: 'Issues' })
    expect(within(issues).getByText('ACME-141')).toBeInTheDocument()

    expect(within(list.getByRole('group', { name: 'Cases' })).getByText('TC-518')).toBeInTheDocument()

    // The docs heading names the project, because the group's scope is narrower than the field's.
    const docs = list.getByRole('group', { name: 'Docs in Online Banking' })
    expect(within(docs).getByText('Refund policy')).toBeInTheDocument()
  })

  it('asks the ACTIVE project for docs and no other project at all', async () => {
    const paths = mockReads([], { docs: [aDocPageSearchHit()] })

    await open(OLB, 'refund')

    await waitFor(() =>
      expect(paths.some((path) => path.startsWith(`/projects/${OLB}/doc-pages/search`))).toBe(true),
    )
    /*
      **The fan-out that must not exist.** `/projects` is read for the switcher's group and for the
      docs heading's project name, so the palette KNOWS the reader's other projects — which is
      exactly what makes a loop over them the tempting implementation. One doc search per project
      per keystroke is the request storm this asserts against.
    */
    const docSearches = paths.filter((path) => path.includes('/doc-pages/search'))
    expect(new Set(docSearches.map((path) => path.split('/doc-pages')[0]))).toEqual(
      new Set([`/projects/${OLB}`]),
    )
  })

  it('says where docs are searched from when no project is in the frame, and asks for none', async () => {
    const paths = mockReads([], { issues: [anIssueSearchHit({ title: 'Receipt PDF' })] })

    await open(undefined, 'receipt')

    // The issue search still runs — it is cross-project by construction and needs no frame.
    await waitFor(() => expect(screen.getByText('Receipt PDF')).toBeInTheDocument())

    // An empty group with a heading claims a search ran. This is a sentence instead.
    expect(screen.getByTestId('Palette.NoProjectDocs')).toHaveTextContent(
      /Doc pages are searched inside one project/,
    )
    expect(paths.filter((path) => path.includes('/doc-pages'))).toEqual([])
  })

  it('sends an issue row to the issue’s own page — no project segment', async () => {
    mockReads([], {
      issues: [anIssueSearchHit({ key: 'ACME-141', title: 'Receipt PDF for a partial refund' })],
    })
    const user = userEvent.setup()
    render(
      <TestProviders client={createTestQueryClient()} userId="user-1">
        <CommandPalette projectId={OLB} />
        <Probe />
      </TestProviders>,
    )

    await user.click(screen.getByTestId('TopBar.Search'))
    await user.type(await screen.findByTestId('Palette.Input'), 'receipt')
    await user.click(await screen.findByText('Receipt PDF for a partial refund'))

    /*
      `/issues/:key` and not `/projects/{id}/issues/:key`. The hit carries a `projectId`, so the
      wrong route is one field away and would 404 — which is why the row's target is asserted
      rather than assumed from the group it is in.
    */
    await waitFor(() => expect(probed).toBe('/issues/ACME-141'))
  })
})

let probed = ''

/** Reports where the palette navigated to, the way `AppShell.test.tsx`'s probe does. */
function Probe() {
  probed = useLocation().pathname
  return null
}
