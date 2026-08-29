import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { IssueTokenDialog } from './IssueTokenDialog'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * Issuing a credential — the app's only screen that displays a secret, and the only one where
 * getting the *lifecycle* wrong destroys something unrecoverable.
 *
 * Five things are locked here, none of them visible to `tsc` or the linter:
 *
 *   1. **The secret is rendered, once, and the dialog does not close on success.** A refactor that
 *      tidies `onClose()` into the happy path — the shape every other create dialog here has —
 *      throws away a value nothing can produce again, with a success toast on top.
 *   2. **The secret never reaches the query cache.** An "avoid a refetch" `setQueryData` would put
 *      a plaintext credential into the store the whole app reads.
 *   3. **The role list is `scope !== 'SYSTEM'`, and NOT the grant dialog's scope-dependent
 *      filter.** Copying the neighbour hides roles the server accepts.
 *   4. **A blank expiry is sent as `null`, and a filled one as an instant.** Posting the browser's
 *      local wall-clock string expires the token by the zone offset, silently.
 *   5. **Server field errors land on the input that caused them**, not in a banner.
 */

const ROLES = [
  {
    id: 'role-instance',
    name: 'Instance Admin',
    scope: 'SYSTEM',
    builtIn: true,
    assignable: true,
    description: 'The whole instance.',
    permissions: [],
  },
  {
    id: 'role-company',
    name: 'Company Admin',
    scope: 'COMPANY',
    builtIn: true,
    assignable: true,
    description: 'Everything in this company.',
    permissions: [],
  },
  {
    id: 'role-automation',
    name: 'Automation Service',
    scope: 'PROJECT',
    builtIn: true,
    // False since V41 — the CI ceiling stopped being grantable to a person, and this fixture said
    // `true` for as long as the server did. It is the SECOND role no grant picker may show and
    // this form must, which is what turns the assertion below from a statement about Agent into a
    // statement about the rule.
    assignable: false,
    description: 'Posts results. Cannot edit a case.',
    permissions: [],
  },
  {
    id: 'role-agent',
    name: 'Agent',
    scope: 'PROJECT',
    builtIn: true,
    assignable: false,
    description: 'Carried by an agent. Cannot approve, delete or publish.',
    permissions: [],
  },
]

const PROJECTS = [
  {
    id: 'p1',
    key: 'OLB',
    name: 'Acme Online Banking',
    description: null,
    archived: false,
    caseCount: 0,
    myRole: 'Test Lead',
  },
]

const ISSUED = {
  token: {
    id: 'tok-1',
    name: 'Nightly regression',
    tokenPrefix: 'tcms_abc123',
    ownerId: 'user-1',
    roleId: 'role-automation',
    roleName: 'Automation Service',
    projectId: null,
    expiresAt: null,
    lastUsedAt: null,
    revokedAt: null,
    createdAt: '2026-08-07T09:00:00Z',
  },
  secret: 'tcms_abc123_the_only_time_this_is_ever_shown',
}

function mockReads() {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(['token:issue'], {}, null)
    if (path.startsWith('/roles')) return ROLES
    if (path.startsWith('/projects')) return PROJECTS
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

let onClose: () => void
let client: ReturnType<typeof createTestQueryClient>

function renderDialog() {
  client = createTestQueryClient()
  return render(
    <TestProviders client={client} userId="user-1">
      <IssueTokenDialog open onClose={onClose} />
    </TestProviders>,
  )
}

/**
 * ~~MUI renders a select's options into a portal, so the listbox is found on the document.~~ →
 * **superseded (007 T15).** These are native selects, so the options are the box's own children.
 * Only these two helpers changed; no expectation in this file moved.
 */
async function chooseFrom(testId: string, optionName: string | RegExp) {
  const select = screen.getByTestId(testId)
  await userEvent.selectOptions(select, within(select).getByRole('option', { name: optionName }))
}

async function optionNames(testId: string) {
  return within(screen.getByTestId(testId))
    .getAllByRole('option')
    .map((option) => option.textContent)
}

describe('IssueTokenDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    onClose = vi.fn()
    mockReads()
  })

  it('shows the secret and stays open, because nothing can show it again', async () => {
    // THE HEADLINE. Closing on success is what every other create dialog on this screen does and
    // it is exactly wrong here.
    vi.spyOn(api, 'post').mockResolvedValue(ISSUED)
    renderDialog()

    await userEvent.type(screen.getByTestId('Admin.TokenIssue.Name'), 'Nightly regression')
    await chooseFrom('Admin.TokenIssue.Role', 'Automation Service')
    await userEvent.click(screen.getByTestId('Admin.TokenIssue.Submit'))

    expect(await screen.findByTestId('Admin.TokenIssue.Secret.Value.Value')).toHaveTextContent(
      ISSUED.secret,
    )
    expect(screen.getByTestId('Admin.TokenIssue.Secret.Warning')).toHaveTextContent(
      /only time this token is shown/i,
    )
    expect(onClose).not.toHaveBeenCalled()
    // And the form is gone — a secret rendered beside a live form invites a second submit that
    // replaces the value on screen with another unrecoverable one.
    expect(screen.queryByTestId('Admin.TokenIssue.Submit')).not.toBeInTheDocument()
  })

  it('never writes the secret into the query cache', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(ISSUED)
    renderDialog()

    await userEvent.type(screen.getByTestId('Admin.TokenIssue.Name'), 'Nightly regression')
    await chooseFrom('Admin.TokenIssue.Role', 'Automation Service')
    await userEvent.click(screen.getByTestId('Admin.TokenIssue.Submit'))
    await screen.findByTestId('Admin.TokenIssue.Secret.Value.Value')

    // Every entry, not just the token key: the point is that the plaintext is nowhere in the store
    // the whole app reads, however it might have got there.
    const cached = JSON.stringify(
      client
        .getQueryCache()
        .getAll()
        .map((entry) => entry.state.data),
    )
    expect(cached).not.toContain(ISSUED.secret)
  })

  it('offers every role the server accepts and refuses the SYSTEM-scope one', async () => {
    renderDialog()

    // Company Admin is offered even though this dialog can pin the token to a project — a token's
    // project is a ceiling on where it works, not a statement about the role's scope, and
    // `GrantRoleDialog`'s filter would have hidden it.
    const names = await optionNames('Admin.TokenIssue.Role')
    expect(names).toEqual(['Company Admin', 'Automation Service', 'Agent'])
    expect(names).not.toContain('Instance Admin')
  })

  it('offers BOTH roles every grant picker hides — the one form where either is chosen', async () => {
    renderDialog()

    /*
      `assignable: false` on both, and offered anyway. Agent (V40) and Automation Service (V41) are
      ceilings an API token carries and nobody is ever either of them, so `GrantRoleDialog` and
      `RoleReference` drop them — and copying that filter into this dialog, which reads the same
      endpoint and looks like it wants the same predicate, would make both roles unobtainable
      through the product with nothing else changing. This assertion is what fails if somebody
      tidies the three filters into one.

      Automation Service is the one that makes the cost concrete: it is a CI credential, so the
      tidy-up would break issuing the token that ingests every result in the product.
    */
    expect(await optionNames('Admin.TokenIssue.Role')).toEqual(
      expect.arrayContaining(['Agent', 'Automation Service']),
    )
  })

  it('pre-fills an expiry when Agent is chosen, because the server requires one', async () => {
    renderDialog()

    const expires = screen.getByTestId('Admin.TokenIssue.Expires') as HTMLInputElement
    expect(expires.value).toBe('')

    await chooseFrom('Admin.TokenIssue.Role', 'Agent')

    // `datetime-local`, so the browser's own wall clock — an ISO string here would show the user a
    // UTC time labelled as theirs, which is the defect `toInstant` exists to prevent on the way
    // out.
    expect(expires.value).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/)
    expect(new Date(expires.value).getTime()).toBeGreaterThan(Date.now())
  })

  it('never overwrites an expiry the user typed', async () => {
    renderDialog()

    const expires = screen.getByTestId('Admin.TokenIssue.Expires')
    fireEvent.change(expires, { target: { value: '2027-01-01T09:00' } })
    await chooseFrom('Admin.TokenIssue.Role', 'Agent')

    // The default fills a blank field and nothing else. Replacing a date somebody chose — on a
    // re-pick, or on switching away and back — swaps a decision for a default with no signal.
    expect((expires as HTMLInputElement).value).toBe('2027-01-01T09:00')
  })

  it('sends a null expiry when the field is left empty', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(ISSUED)
    renderDialog()

    await userEvent.type(screen.getByTestId('Admin.TokenIssue.Name'), 'Nightly regression')
    await chooseFrom('Admin.TokenIssue.Role', 'Automation Service')
    await userEvent.click(screen.getByTestId('Admin.TokenIssue.Submit'))

    // An exact body, not `toMatchObject`: a dropped field passes that and a `projectId` silently
    // omitted would pin nothing while the form said it had.
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api-tokens', {
        name: 'Nightly regression',
        roleId: 'role-automation',
        projectId: null,
        expiresAt: null,
      }),
    )
  })

  it('sends a filled expiry as an instant, not as the local wall clock', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(ISSUED)
    renderDialog()

    await userEvent.type(screen.getByTestId('Admin.TokenIssue.Name'), 'Nightly regression')
    await chooseFrom('Admin.TokenIssue.Role', 'Automation Service')
    await chooseFrom('Admin.TokenIssue.Project', 'Acme Online Banking')
    await userEvent.type(screen.getByTestId('Admin.TokenIssue.Expires'), '2026-09-01T14:00')
    await userEvent.click(screen.getByTestId('Admin.TokenIssue.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = vi.mocked(post).mock.calls[0][1] as { expiresAt: string; projectId: string }
    expect(body.projectId).toBe('p1')
    // The value the browser gives is `2026-09-01T14:00` with no zone. Sent verbatim it would be
    // read as UTC, so a token issued in a +03:00 zone would die three hours early with nothing on
    // either side saying why. Asserting the round trip rather than a literal keeps this true in
    // whatever zone the suite runs.
    expect(body.expiresAt).toBe(new Date('2026-09-01T14:00').toISOString())
    expect(body.expiresAt).toMatch(/Z$/)
  })

  it('lands a server field error on the input that caused it', async () => {
    // The refused write is the subject: this asserts where its field error lands.
    drivingAFailedWrite()
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        name: ['This company already has a live token called "Nightly regression".'],
      }),
    )
    renderDialog()

    await userEvent.type(screen.getByTestId('Admin.TokenIssue.Name'), 'Nightly regression')
    await chooseFrom('Admin.TokenIssue.Role', 'Automation Service')
    await userEvent.click(screen.getByTestId('Admin.TokenIssue.Submit'))

    expect(await screen.findByText(/already has a live token/i)).toBeInTheDocument()
    expect(screen.queryByTestId('Admin.TokenIssue.Error')).not.toBeInTheDocument()
    // Still on the form, with what they typed — a refusal that dropped them into the reveal with
    // no secret would be the worst possible reading of "the write finished".
    expect(screen.getByTestId('Admin.TokenIssue.Submit')).toBeInTheDocument()
  })

  it('puts a refusal with no field in the form-level alert', async () => {
    // The refused write is the subject: a refusal naming no field still has to be shown.
    drivingAFailedWrite()
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(403, 'You do not have permission to issue API tokens here.'),
    )
    renderDialog()

    await userEvent.type(screen.getByTestId('Admin.TokenIssue.Name'), 'Nightly regression')
    await chooseFrom('Admin.TokenIssue.Role', 'Automation Service')
    await userEvent.click(screen.getByTestId('Admin.TokenIssue.Submit'))

    expect(await screen.findByTestId('Admin.TokenIssue.Error')).toBeInTheDocument()
  })

  it('says up front that the token cannot exceed the issuer', async () => {
    // The intersection is the one rule an administrator cannot infer from the selects: picking
    // Company Admin does not produce a Company Admin token if the issuer's own grants stop short.
    renderDialog()
    expect(await screen.findByTestId('Admin.TokenIssue.Hint')).toHaveTextContent(
      /never exceeds your own access/i,
    )
  })
})
