import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { DocLink, PermissionKey, TestCase } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, anExternalLink, projectSettings } from '../../test/fixtures'
import { CaseDetailPage } from './CaseDetailPage'

/**
 * The Traceability panel's three target kinds (§169).
 *
 * **Two of the three branches had never run against data.** `doc_link` designed `EXTERNAL_GIT` and
 * `EXTERNAL_URL` from M0 and nothing wrote either until §169, so the panel's external arm rendered
 * `externalRepo` alone — a blank line for a URL link, and for a git link a repository name with the
 * file, the range and the commit demoted to a caption. It typechecked the whole time, which is
 * §168's `CoverageMarker` one panel over and the reason these assert on rendered text.
 *
 * What each test is written to catch, rather than to describe:
 *
 *   - a git link whose pin is dropped, or whose range is rendered without it
 *   - a URL link rendered as text, or as an anchor without `rel="noopener"` — the href is a value
 *     somebody typed, and an opened tab can navigate this one back through `window.opener`
 *   - a renderer that branches on which field is populated instead of on `targetKind`
 *   - a Remove offered on a block link, which no endpoint can serve from the case end
 */

const PROJECT = 'p1'
const CALLER = '77777777-7777-4777-8777-777777777777'

const person = (id: string) => ({
  id,
  displayName: 'Ana Petrova',
  email: `${id}@example.test`,
  avatarUrl: null,
})

const blockLink: DocLink = {
  id: 'l-block',
  targetKind: 'DOC_BLOCK',
  relationship: 'VERIFIES',
  pageId: 'page-1',
  pageKey: 'DOC-1',
  pageTitle: 'Refund policy',
  blockId: 'block-1',
  blockExcerpt: 'A refund may be issued within 180 days.',
  externalRepo: null,
  externalPath: null,
  externalLineStart: null,
  externalLineEnd: null,
  externalCommitSha: null,
  externalUrl: null,
  stale: false,
}

const gitLink: DocLink = {
  id: 'l-git',
  targetKind: 'EXTERNAL_GIT',
  relationship: 'DERIVED_FROM',
  pageId: null,
  pageKey: null,
  pageTitle: null,
  blockId: null,
  blockExcerpt: null,
  externalRepo: 'acme/checkout-service',
  externalPath: 'src/Refund.java',
  externalLineStart: 40,
  externalLineEnd: 58,
  externalCommitSha: '4f9c2b1',
  externalUrl: null,
  stale: false,
}

const urlLink: DocLink = {
  id: 'l-url',
  targetKind: 'EXTERNAL_URL',
  relationship: 'REFERENCES',
  pageId: null,
  pageKey: null,
  pageTitle: null,
  blockId: null,
  blockExcerpt: null,
  externalRepo: null,
  externalPath: null,
  externalLineStart: null,
  externalLineEnd: null,
  externalCommitSha: null,
  externalUrl: 'https://specs.acme.test/refunds',
  stale: false,
}

function caseBody(links: DocLink[]): TestCase {
  return {
    id: 'c1',
    key: 'TC-1',
    keySeq: 1,
    title: 'Full refund of a settled card payment succeeds',
    suiteId: 's1',
    suiteName: 'Refunds',
    priority: 'HIGH',
    lifecycleStatus: 'APPROVED',
    automationStatus: 'MANUAL',
    stepFormat: 'STRUCTURED',
    owner: person(CALLER),
    createdBy: person(CALLER),
    testType: null,
    testLevel: null,
    features: [],
    classificationValueIds: [],
    tags: [],
    healthSignals: [],
    lastResult: null,
    updatedAt: '2026-07-28T12:00:00Z',
    description: null,
    objective: null,
    expectedOutcome: null,
    severity: null,
    estimatedDurationSec: null,
    automationTags: [],
    automationKey: null,
    notAutomatableReason: null,
    gherkinSource: null,
    steps: [],
    preconditions: [],
    postconditions: [],
    currentVersionNo: 1,
    lockVersion: 1,
    flake: null,
    attachments: [],
    relatedDocuments: links,
    declinedPreconditions: [],
    declinedPostconditions: [],
  }
}

function mockReads(links: DocLink[], permissions: PermissionKey[] = ['case:read', 'case:edit']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    if (path.includes('/cases/')) return caseBody(links)
    if (path.includes('/settings')) return projectSettings({ projectId: PROJECT })
    if (path.startsWith('/projects/')) return aProject({ id: PROJECT, name: 'Banking' })
    return []
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/cases/c1`}
      userId={CALLER}
    >
      <Routes>
        <Route path="/projects/:projectId/cases/:caseId" element={<CaseDetailPage />} />
      </Routes>
    </TestProviders>,
  )
}

const panel = async () => await screen.findByTestId('CaseDetail.DocLinks')

describe('The Traceability panel renders all three target kinds', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders a git link as one identifier — repository, file, range and pin', async () => {
    mockReads([gitLink])
    renderPage()

    const rendered = await screen.findByTestId('CaseDetail.DocLinks.ExternalGit')
    expect(rendered).toHaveTextContent('acme/checkout-service')
    // The range and the commit in the SAME element as the path: the pin is what makes the range
    // mean anything six months later, and a caption reads as a footnote (#26).
    expect(rendered).toHaveTextContent('src/Refund.java:40–58')
    expect(rendered).toHaveTextContent('4f9c2b1')
  })

  it('renders a whole-file git link without an empty range', async () => {
    mockReads([{ ...gitLink, externalLineStart: null, externalLineEnd: null }])
    renderPage()

    const rendered = await screen.findByTestId('CaseDetail.DocLinks.ExternalGit')
    expect(rendered).toHaveTextContent('src/Refund.java')
    expect(rendered.textContent).not.toContain(':')
    expect(rendered.textContent).not.toContain('–')
  })

  it('renders a single-line git link as one number, not a range onto itself', async () => {
    mockReads([{ ...gitLink, externalLineStart: 40, externalLineEnd: 40 }])
    renderPage()

    const rendered = await screen.findByTestId('CaseDetail.DocLinks.ExternalGit')
    expect(rendered).toHaveTextContent('src/Refund.java:40')
    expect(rendered.textContent).not.toContain('40–40')
  })

  it('renders a URL link as an anchor that cannot reach back into this tab', async () => {
    mockReads([urlLink])
    renderPage()

    const anchor = await screen.findByTestId('CaseDetail.DocLinks.ExternalUrl')
    expect(anchor).toHaveAttribute('href', 'https://specs.acme.test/refunds')
    expect(anchor).toHaveAttribute('target', '_blank')
    // Without `noopener` the opened page can navigate this one through `window.opener`, and this
    // href is a value somebody typed into a form.
    expect(anchor.getAttribute('rel')).toContain('noopener')
  })

  it('renders each kind by targetKind, not by which field happens to be set', async () => {
    // A git row with no repository — refused by the write path, and the shape a renderer branching
    // on `externalRepo` would draw as a page reference with no page.
    mockReads([{ ...gitLink, externalRepo: null }])
    renderPage()

    expect(await screen.findByTestId('CaseDetail.DocLinks.ExternalGit')).toHaveTextContent(
      'src/Refund.java',
    )
    expect(screen.queryByTestId('CaseDetail.DocLinks.PageLink')).toBeNull()
  })

  it('offers Remove on external links and never on a block link', async () => {
    mockReads([blockLink, gitLink])
    renderPage()

    await panel()
    // One Remove for two links: the block link is withdrawn on the documentation page under
    // doc:edit, and no endpoint removes one from the case end.
    await waitFor(() =>
      expect(screen.getAllByTestId('CaseDetail.DocLinks.Remove')).toHaveLength(1),
    )
  })

  it('offers the link action to a case editor and disables it for everyone else', async () => {
    mockReads([], ['case:read'])
    renderPage()

    const add = await screen.findByTestId('CaseDetail.DocLinks.AddButton')
    // Disabled rather than hidden (§31) — a Viewer should be able to see the product can do this.
    await waitFor(() => expect(add).toBeDisabled())
  })

  it('sends only the chosen kind’s fields, with the relationship the author picked', async () => {
    mockReads([])
    const post = vi
      .spyOn(api, 'post')
      .mockResolvedValue({
        id: 'l-new',
        targetKind: 'EXTERNAL_URL',
        relationship: 'REFERENCES',
        externalUrl: 'https://specs.acme.test/refunds',
      } as never)
    renderPage()

    const user = userEvent.setup()
    await waitFor(async () =>
      expect(await screen.findByTestId('CaseDetail.DocLinks.AddButton')).toBeEnabled(),
    )
    await user.click(screen.getByTestId('CaseDetail.DocLinks.AddButton'))
    await user.click(await screen.findByTestId('CaseDetail.ExternalLink.Url'))
    await user.type(
      screen.getByTestId('CaseDetail.ExternalLink.Address'),
      'https://specs.acme.test/refunds',
    )
    await user.click(screen.getByTestId('CaseDetail.ExternalLink.Confirm'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [path, body] = post.mock.calls[0]
    expect(path).toBe(`/projects/${PROJECT}/cases/c1/external-links`)
    // No repository coordinates on a URL link: the server refuses the combination rather than
    // ignoring it, so a value typed under the other kind and left in the payload is a 400 on a
    // field that is no longer on screen.
    expect(body).toEqual({
      targetKind: 'EXTERNAL_URL',
      relationship: 'VERIFIES',
      url: 'https://specs.acme.test/refunds',
    })
  })

  it('keeps a removed link off the panel, without waiting for the projection', async () => {
    // The other direction of the same problem: the mirror still holds the row for up to the
    // outbox interval, so a refetch on success puts the withdrawn claim straight back.
    mockReads([urlLink])
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPage()

    const user = userEvent.setup()
    const remove = await screen.findByTestId('CaseDetail.DocLinks.Remove')
    await waitFor(() => expect(remove).toBeEnabled())
    await user.click(remove)
    // §173 — the click raises the confirmation; the withdrawal is the confirm.
    await user.click(await screen.findByTestId('CaseDetail.DocLinks.Unlink.Confirm'))

    await waitFor(() => expect(del).toHaveBeenCalledWith(
      `/projects/${PROJECT}/cases/c1/external-links/l-url`,
    ))
    await waitFor(() => expect(screen.queryByTestId('CaseDetail.DocLinks.ExternalUrl')).toBeNull())
  })

  /**
   * §173 — the same confirmation as the documentation end, from the other side of one act.
   *
   * The pair of assertions that matters is *which words* each end shows: the dialog is shared and
   * the naming is the caller's, so a screen that passed the wrong thing would still render a
   * perfectly well-formed confirmation about the wrong object.
   */
  it('names the git target in words and sends nothing until it is confirmed', async () => {
    mockReads([gitLink])
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPage()

    const user = userEvent.setup()
    const remove = await screen.findByTestId('CaseDetail.DocLinks.Remove')
    await waitFor(() => expect(remove).toBeEnabled())
    await user.click(remove)

    // The text form of what the row renders as markup — one identifier, pin included. A link that
    // navigates away does not belong inside a confirmation, which is why this is not the anchor.
    expect(await screen.findByTestId('CaseDetail.DocLinks.Unlink.Target')).toHaveTextContent(
      'acme/checkout-service · src/Refund.java:40–58 at 4f9c2b1',
    )
    expect(del).not.toHaveBeenCalled()
  })

  /**
   * §174 — the case end of the correction, and the one asymmetry that survives on purpose.
   */
  it('corrects an external link\u2019s claim in place', async () => {
    mockReads([gitLink])
    // `ExternalLink`, which is what the endpoint answers — **not** the `DocLink` this double used
    // to hand back. The two differ by the whole page/block half and by `stale`, and the difference
    // is load-bearing: `useRelabelCaseExternalLink` puts the response into the cached case through
    // `asDocLink`, so a fixture that was already a `DocLink` was testing the one path that adapter
    // does not exist for.
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(
      anExternalLink({
        id: gitLink.id,
        targetKind: 'EXTERNAL_GIT',
        relationship: 'VERIFIES',
        externalRepo: gitLink.externalRepo,
        externalPath: gitLink.externalPath,
        externalLineStart: gitLink.externalLineStart,
        externalLineEnd: gitLink.externalLineEnd,
        externalCommitSha: gitLink.externalCommitSha,
      }) as never,
    )
    renderPage()

    const user = userEvent.setup()
    // Re-queried inside the waitFor rather than captured once: the control renders immediately
    // (disabled, because `useCan` is fail-closed while pending) and React replaces the node when
    // the answer lands, so a captured reference is a detached element that stays disabled for ever.
    await waitFor(() =>
      expect(screen.getByTestId('CaseDetail.DocLinks.Relationship')).toBeEnabled(),
    )
    await user.click(screen.getByTestId('CaseDetail.DocLinks.Relationship'))
    await user.click(await screen.findByTestId('CaseDetail.DocLinks.Relationship.VERIFIES'))

    await waitFor(() =>
      expect(patch).toHaveBeenCalledWith(
        `/projects/${PROJECT}/cases/c1/external-links/l-git`,
        { relationship: 'VERIFIES' },
      ),
    )
  })

  it('leaves a block link\u2019s claim to the documentation page', async () => {
    // The surviving asymmetry, and it is the same one that gives a block link no Remove here: the
    // block end is gated on doc:edit and this panel holds case:edit, so the control is disabled
    // rather than absent (§31) and the reader is told the claim is editable somewhere.
    mockReads([blockLink])
    renderPage()

    await waitFor(() =>
      expect(screen.getByTestId('CaseDetail.DocLinks.Relationship')).toBeDisabled(),
    )
  })

  it('sends nothing when the confirmation is cancelled, and keeps the row', async () => {
    mockReads([urlLink])
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderPage()

    const user = userEvent.setup()
    const remove = await screen.findByTestId('CaseDetail.DocLinks.Remove')
    await waitFor(() => expect(remove).toBeEnabled())
    await user.click(remove)
    await user.click(await screen.findByTestId('CaseDetail.DocLinks.Unlink.Cancel'))

    await waitFor(() =>
      expect(screen.queryByTestId('CaseDetail.DocLinks.Unlink')).not.toBeInTheDocument(),
    )
    expect(del).not.toHaveBeenCalled()
    expect(screen.getByTestId('CaseDetail.DocLinks.ExternalUrl')).toBeInTheDocument()
  })

  it('shows the new link straight away, without waiting for the projection', async () => {
    // relatedDocuments is served from case_doc_link, refreshed on an event — so a plain invalidate
    // refetches the same list without the link somebody just made, for up to the outbox interval.
    mockReads([])
    vi.spyOn(api, 'post').mockResolvedValue({
      id: 'l-new',
      targetKind: 'EXTERNAL_URL',
      relationship: 'VERIFIES',
      externalUrl: 'https://specs.acme.test/refunds',
    } as never)
    renderPage()

    const user = userEvent.setup()
    await waitFor(async () =>
      expect(await screen.findByTestId('CaseDetail.DocLinks.AddButton')).toBeEnabled(),
    )
    await user.click(screen.getByTestId('CaseDetail.DocLinks.AddButton'))
    await user.click(await screen.findByTestId('CaseDetail.ExternalLink.Url'))
    await user.type(
      screen.getByTestId('CaseDetail.ExternalLink.Address'),
      'https://specs.acme.test/refunds',
    )
    await user.click(screen.getByTestId('CaseDetail.ExternalLink.Confirm'))

    expect(await screen.findByTestId('CaseDetail.DocLinks.ExternalUrl')).toHaveAttribute(
      'href',
      'https://specs.acme.test/refunds',
    )
  })
})
