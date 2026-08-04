import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { Team } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { TeamsPage } from './TeamsPage'

/**
 * The org chart's write affordances — **§71's own test file, and it exists for §66's reason.**
 *
 * Nothing else proves any of the following, and each is invisible to `tsc` and to the linter:
 *
 *   1. **The three row actions and the opener are gated on `company:teams` at COMPANY scope.**
 *      A gate written against `company:members` — the permission every other button on this page
 *      asks for, sitting six lines away — compiles, renders identically, and hands the org chart
 *      to a role the roles spec never gave it. The seeded catalogue grants both to the same roles,
 *      so this is the *only* place the distinction can be observed at all: the server's own tests
 *      structurally cannot see it, which `SeedDataTest` now records.
 *   2. **A team the tree cannot reach from a root is still rendered.** §68's orphan rule, and it
 *      matters more here: the tree is the only place a team can be edited or deleted, so a team it
 *      silently drops is a team nobody can fix.
 *   3. **The 409's counts reach the screen.** The refusal, not the delete, is the feature.
 *
 * Every assertion about a *disabled* control waits for `permissionsResolved()` first. `useCan` is
 * fail-closed while pending, so without the barrier all of them pass in milliseconds against a page
 * with no gate in it at all — §66 found that by running the mutation, not by reading the test.
 */

const QA: Team = {
  id: 't-qa',
  parentTeamId: null,
  name: 'QA Chapter',
  description: 'Cross-squad quality practice',
  leads: [],
  members: [{ id: 'u1', displayName: 'Ada Byron', email: 'ada@example.com', avatarUrl: null }],
  projects: [],
}

const PAYMENTS: Team = {
  id: 't-payments',
  parentTeamId: 't-qa',
  name: 'Payments Squad',
  description: null,
  leads: [],
  members: [],
  projects: [{ projectId: 'p1', projectName: 'Acme Online Banking', roleName: 'Test Lead' }],
}

/** A team whose parent is not in the list — unreachable by a plain descent from the roots. */
const ORPHAN: Team = {
  id: 't-orphan',
  parentTeamId: 't-vanished',
  name: 'Orphaned Squad',
  description: null,
  leads: [],
  members: [],
  projects: [],
}

function mockApi(companyPermissions: string[], teams: Team[] = [QA, PAYMENTS]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      // Company scope: the page's gates all ask `projectId="company"`.
      return heldPermissions(companyPermissions, {}, null)
    }
    if (path.startsWith('/teams')) return teams
    if (path.startsWith('/users')) return []
    if (path.startsWith('/roles')) return []
    if (path.startsWith('/role-assignments')) return []
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    /* `userId` is required, not decorative: TeamsPage calls the THROWING `useAuth` for the
       self-removal guard, so rendering without a session is an error rather than a "no caller"
       answer — §66's coupling, in the one place on this page that still has it. */
    <TestProviders client={createTestQueryClient()} userId="u1">
      <TeamsPage />
    </TestProviders>,
  )
}

/**
 * Wait until the permission answers have landed.
 *
 * "People" is the barrier rather than any team control: it asks `company:members`, which every
 * persona in this file holds, so it flips exactly once the company answer resolves — and it stays
 * enabled in the case where every `company:teams` control must be disabled, which is precisely the
 * test that would otherwise pass vacuously.
 */
async function permissionsResolved() {
  await waitFor(() => expect(screen.getByTestId('Admin.Invite.Open')).toBeEnabled())
}

describe('TeamsPage — the org chart becomes editable', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers every org-chart action to a caller holding company:teams', async () => {
    mockApi(['company:read', 'company:members', 'company:teams'])
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Admin.Teams.New')).toBeEnabled())
    const rows = await screen.findAllByTestId('Admin.Teams.Item')
    expect(within(rows[0]).getByTestId('Admin.Teams.Item.Edit')).toBeEnabled()
    expect(within(rows[0]).getByTestId('Admin.Teams.Item.Members')).toBeEnabled()
    expect(within(rows[0]).getByTestId('Admin.Teams.Item.Delete')).toBeEnabled()
  })

  it('refuses all four to a caller who administers members but not teams', async () => {
    // THE HEADLINE. This caller holds `company:members` — they can invite and remove people — and
    // not `company:teams`. A gate copied from the button beside it enables everything here.
    mockApi(['company:read', 'company:members'])
    renderPage()
    await permissionsResolved()

    expect(screen.getByTestId('Admin.Teams.New')).toBeDisabled()
    const rows = await screen.findAllByTestId('Admin.Teams.Item')
    expect(within(rows[0]).getByTestId('Admin.Teams.Item.Edit')).toBeDisabled()
    expect(within(rows[0]).getByTestId('Admin.Teams.Item.Members')).toBeDisabled()
    expect(within(rows[0]).getByTestId('Admin.Teams.Item.Delete')).toBeDisabled()
  })

  it('offers every team’s board to a caller who may administer none of them', async () => {
    /*
      §84's entry point, and the one affordance on this page with NO gate around it.

      A team board needs no permission — §84 declined to invent a key for it, because every card it
      can return is one the reader could already see on a project board, and a key seeded to nobody
      is a 403 no administrator could grant their way out of. Nor must the reader be on the team
      (§6k). So the natural mistake is to add it as a fourth button inside the `company:teams` gate
      six lines below, where it compiles, renders identically, and hands a work view to the two
      roles that administer the org chart.

      **The barrier is the disabled Edit button, not the link.** §66's trap and §80's variant: an
      anchor is not a form element, so `toBeEnabled()` on it passes instantly and would pass just
      as well while `useCan` is still pending — that is, against a page where the Board link *is*
      gated. Asserting the link's presence *at the moment the gate has resolved to "no"* is what
      makes it non-vacuous, and the disabled sibling is what proves the gate resolved.
    */
    mockApi(['company:read', 'company:members'])
    renderPage()
    await permissionsResolved()

    const rows = await screen.findAllByTestId('Admin.Teams.Item')
    expect(within(rows[0]).getByTestId('Admin.Teams.Item.Edit')).toBeDisabled()
    expect(within(rows[0]).getByTestId('Admin.Teams.Item.Board').getAttribute('href')).toBe(
      `/teams/${QA.id}/board`,
    )
  })

  it('renders a team whose parent is missing, instead of dropping it', async () => {
    // §68's rule. A descent from the roots never reaches this team, and the tree is the only place
    // it could be deleted — so dropping it makes the broken row permanent and invisible.
    mockApi(['company:read', 'company:members', 'company:teams'], [QA, ORPHAN])
    renderPage()

    const rows = await screen.findAllByTestId('Admin.Teams.Item')
    expect(rows).toHaveLength(2)
    expect(screen.getByText('Orphaned Squad')).toBeInTheDocument()
  })

  it('renders teams that point at each other, rather than looping or dropping both', async () => {
    const a: Team = { ...QA, id: 't-a', name: 'Team A', parentTeamId: 't-b' }
    const b: Team = { ...QA, id: 't-b', name: 'Team B', parentTeamId: 't-a' }
    mockApi(['company:read', 'company:members', 'company:teams'], [a, b])
    renderPage()

    // The API refuses cycles twice over, so anything that gets here came from elsewhere — and a
    // recursive walk over one does not terminate. Both are surfaced at the root.
    const rows = await screen.findAllByTestId('Admin.Teams.Item')
    expect(rows).toHaveLength(2)
  })

  it('shows the 409 blockers on the delete dialog rather than a generic failure', async () => {
    mockApi(['company:read', 'company:members', 'company:teams'])
    vi.spyOn(api, 'del').mockRejectedValue(
      new ApiError(409, 'Clear what still points at this team before deleting it.', {
        grants: ['3 role grants are made through this team. Revoke them first.'],
        children: ['1 team sits below this one. Move or delete it first.'],
      }),
    )
    renderPage()

    const rows = await screen.findAllByTestId('Admin.Teams.Item')
    await userEvent.click(within(rows[0]).getByTestId('Admin.Teams.Item.Delete'))
    await userEvent.click(await screen.findByTestId('Admin.TeamDelete.Confirm'))

    // The counts are the whole message: "could not delete" leaves the administrator hunting.
    const blocked = await screen.findByTestId('Admin.TeamDelete.Blocked')
    expect(blocked).toHaveTextContent('3 role grants')
    expect(blocked).toHaveTextContent('1 team sits below this one')
  })

  it('lands a duplicate-name refusal on the name input, not in a banner', async () => {
    mockApi(['company:read', 'company:members', 'company:teams'])
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Some of the details need correcting.', {
        name: ['This company already has a team called "QA Chapter".'],
      }),
    )
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Admin.Teams.New')).toBeEnabled())
    await userEvent.click(screen.getByTestId('Admin.Teams.New'))
    await userEvent.type(await screen.findByTestId('Admin.Team.Name'), 'qa chapter')
    await userEvent.click(screen.getByTestId('Admin.Team.Submit'))

    expect(await screen.findByText(/already has a team called "QA Chapter"/)).toBeInTheDocument()
    // Still open: a dialog that closed on a rejection would discard what the user typed.
    expect(screen.getByTestId('Admin.Team')).toBeInTheDocument()
  })

  it('sends description and parent as null rather than empty strings', async () => {
    mockApi(['company:read', 'company:members', 'company:teams'])
    const post = vi.spyOn(api, 'post').mockResolvedValue({ ...QA, id: 't-new' } as never)
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Admin.Teams.New')).toBeEnabled())
    await userEvent.click(screen.getByTestId('Admin.Teams.New'))
    await userEvent.type(await screen.findByTestId('Admin.Team.Name'), 'Platform')
    await userEvent.click(screen.getByTestId('Admin.Team.Submit'))

    // `''` would be a description that renders as a blank line and a parent the server reads as a
    // uuid it cannot find. The contract says both are nullable; this is where that is honoured.
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/teams', {
        name: 'Platform',
        description: null,
        parentTeamId: null,
      }),
    )
  })

  it('does not offer a team its own descendants as a parent', async () => {
    mockApi(['company:read', 'company:members', 'company:teams'])
    renderPage()

    const rows = await screen.findAllByTestId('Admin.Teams.Item')
    await userEvent.click(within(rows[0]).getByTestId('Admin.Teams.Item.Edit'))

    // Editing QA Chapter: Payments Squad sits under it, so offering it would put the one refusal
    // an administrator cannot reason about behind a submit.
    await userEvent.click(await screen.findByTestId('Admin.Team.Parent'))
    const options = await screen.findAllByRole('option')
    expect(options.map((option) => option.textContent)).toEqual([
      'No parent — a top-level team',
    ])
  })
})
