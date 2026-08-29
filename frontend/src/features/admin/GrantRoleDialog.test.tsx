import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { GrantRoleDialog } from './GrantRoleDialog'
import { heldPermissions } from '../../test/permissions'
import type { PermissionKey } from '../../api/types'
import { drivingAFailedWrite } from '../../test/renderFailures'

/**
 * The one dialog whose gate is chosen by its own form state.
 *
 * Three things are locked here, and each is invisible to the build and the linter:
 *
 *   1. **The role list follows the scope.** The server refuses a mismatch with a 400 on `roleId`
 *      rather than coercing it, so offering a COMPANY-scope role while a project is selected is
 *      offering a choice we know is refused. SYSTEM-scope roles are never offered at all — those
 *      grants live in `instance_role_assignment` and only the operator CLI writes them.
 *   2. **Changing the scope clears the role.** Without it, a role picked under the old scope
 *      survives into a submission the server rejects, with the error landing on a select the user
 *      did not touch.
 *   3. **The gate follows the scope too.** `company:members` for a company-wide grant,
 *      `project:members` for a project one — the mistake being that the two are answered by
 *      different endpoints' answers, so a single gate would silently ask the wrong question.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

const roles = [
  {
    id: 'role-instance',
    name: 'Instance Admin',
    scope: 'SYSTEM',
    builtIn: true,
    assignable: true,
    description: 'The operator.',
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
    id: 'role-viewer',
    name: 'Viewer',
    scope: 'PROJECT',
    builtIn: true,
    assignable: true,
    description: 'Read-only.',
    permissions: [],
  },
  {
    id: 'role-agent',
    name: 'Agent',
    scope: 'PROJECT',
    builtIn: true,
    // PROJECT-scope, so every scope test in this file passes it through. The server refuses every
    // grant of it regardless of subject — see the test below.
    assignable: false,
    description: 'Carried by an agent.',
    permissions: [],
  },
]

const users = [
  { id: 'user-1', displayName: 'Ana Petrova', email: 'ana@example.com', avatarUrl: null, status: 'ACTIVE', lastLoginAt: null, teams: [] },
]

const projects = [
  { id: PROJECT, key: 'OLB', name: 'Acme Online Banking', description: null, archived: false, caseCount: 0, myRole: 'Test Lead' },
]

/**
 * One mock for every read the dialog and its gate make, routed by path.
 *
 * `company` and `project` are supplied separately on purpose: the whole point of the scope-
 * dependent gate is that the two answers differ, and a single permission list would let this
 * suite pass while the dialog asked the wrong endpoint.
 */
function mockReads({ company, project }: { company: PermissionKey[]; project: PermissionKey[] }) {
  const read = async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return path.includes('projectId')
        ? heldPermissions(project, {}, PROJECT)
        : heldPermissions(company, {}, null)
    }
    if (path === '/roles') return roles
    if (path === '/users') return users
    if (path === '/teams') return []
    if (path === '/projects') return projects
    throw new Error(`unmocked read: ${path}`)
  }
  vi.spyOn(api, 'get').mockImplementation(read as never)
}

function renderDialog() {
  return render(
    <TestProviders client={createTestQueryClient()}>
      <GrantRoleDialog open onClose={() => {}} onGranted={() => {}} />
    </TestProviders>,
  )
}

/**
 * ~~MUI renders a select's options into a portal, so the listbox is found on the document.~~ →
 * **superseded (007 T15).** All three fields here are native selects, so the options are the box's
 * own children. Only these two helpers and one `closest()` in the assertion below changed; no
 * expectation in this file moved.
 */
async function chooseFrom(testId: string, optionName: string | RegExp) {
  const select = await screen.findByTestId(testId)
  await userEvent.selectOptions(
    select,
    await within(select).findByRole('option', { name: optionName }),
  )
}

/**
 * Awaited, not snapshotted. Every list here is served by a query, so the box paints with its
 * placeholder alone and fills in a tick later — a synchronous read returns `[]` for a control that
 * is about to be correct. Under MUI the click that opened the portal supplied that wait for free.
 */
async function optionNames(testId: string) {
  const select = await screen.findByTestId(testId)
  await waitFor(() => {
    expect(within(select).getAllByRole('option').length).toBeGreaterThan(1)
  })
  return within(select)
    .getAllByRole('option')
    .map((option) => option.textContent)
    .filter((name) => name !== 'Choose a role' && name !== 'Choose a person or a team')
}

describe('GrantRoleDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  /*
    Rewritten 2026-08-02, not deleted. It used to assert `['Company Admin']` — that a PROJECT-scope
    role could not be granted company-wide — which was the product's rule until roles spec §A1.1
    made such a grant mean "this role on every project unless that project says otherwise". The
    backend rewrote its own two pinning tests the same way in the same change, and the assertion
    that matters is still here: SYSTEM is never offered, because those grants live in
    `instance_role_assignment` and only the operator CLI writes them.
  */
  it('offers company and project roles at company scope — but never the SYSTEM one', async () => {
    mockReads({ company: ['company:members'], project: [] })
    renderDialog()
    await screen.findByTestId('Admin.Grant')

    const names = await optionNames('Admin.Grant.Role')
    expect(names).toEqual(['Company Admin', 'Viewer'])
    expect(names).not.toContain('Instance Admin')
  })

  it('warns that a project role granted company-wide reaches every project', async () => {
    mockReads({ company: ['company:members'], project: [] })
    renderDialog()
    await screen.findByTestId('Admin.Grant')

    await chooseFrom('Admin.Grant.Role', 'Viewer')

    // The consequence the two selects cannot show: the projects it reaches are not on this screen,
    // and §A1.1 is replace-not-union, so the sentence has to say both.
    const role = screen.getByTestId('Admin.Grant.Role').closest('div')!
    expect(role.textContent).toMatch(/every project that has not granted them a role of its own/i)
    expect(role.textContent).toMatch(/even when it confers less/i)
  })

  it('pins the scope and offers project roles only when opened from a project', async () => {
    mockReads({ company: ['company:members'], project: ['project:members'] })
    render(
      <TestProviders client={createTestQueryClient()}>
        <GrantRoleDialog open lockedProjectId={PROJECT} onClose={() => {}} onGranted={() => {}} />
      </TestProviders>,
    )
    await screen.findByTestId('Admin.Grant')

    // Disabled rather than absent: the scope is still what the grant is about, so the field states
    // it. And with a project pinned, Company Admin must not be offered — the server refuses it.
    expect(screen.getByTestId('Admin.Grant.Scope')).toBeDisabled()
    expect(await optionNames('Admin.Grant.Role')).toEqual(['Viewer'])
  })

  it('never offers a non-assignable role, at either scope — the axis scope does not cover', async () => {
    mockReads({ company: ['company:members'], project: ['project:members'] })
    renderDialog()
    await screen.findByTestId('Admin.Grant')

    /*
      Agent (V40) is PROJECT-scope, so it survives both branches of the scope filter and is on
      `GET /roles` because the token-issue form needs it there. Nobody is ever an Agent: the server
      refuses the grant for every subject, so offering it here is a control whose only outcome is a
      400 on a select the user was invited to use.
    */
    expect(await optionNames('Admin.Grant.Role')).not.toContain('Agent')

    await chooseFrom('Admin.Grant.Scope', 'Acme Online Banking')
    expect(await optionNames('Admin.Grant.Role')).not.toContain('Agent')
  })

  it('offers only PROJECT-scope roles once a project is chosen, and never the SYSTEM one', async () => {
    mockReads({ company: ['company:members'], project: ['project:members'] })
    renderDialog()
    await screen.findByTestId('Admin.Grant')

    await chooseFrom('Admin.Grant.Scope', 'Acme Online Banking')

    const names = await optionNames('Admin.Grant.Role')
    expect(names).toEqual(['Viewer'])
    expect(names).not.toContain('Instance Admin')
  })

  it('disables Grant with a reason when the caller may not staff the chosen scope', async () => {
    // A Test Lead: project:members on their own project, nothing company-wide. The company-wide
    // scope is the dialog's default, so this is the branch the mock persona actually renders.
    mockReads({ company: ['company:read'], project: ['project:members'] })
    renderDialog()

    const grant = await screen.findByTestId('Admin.Grant.Submit')
    await waitFor(() => expect(grant).toBeDisabled())
    await userEvent.hover(grant.parentElement!)
    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      "You don't have permission to manage company members in this company.",
    )
  })

  it('clears the chosen role when the scope changes, so a stale role cannot be submitted', async () => {
    mockReads({ company: ['company:members'], project: ['project:members'] })
    renderDialog()
    await screen.findByTestId('Admin.Grant')

    await chooseFrom('Admin.Grant.Role', 'Company Admin')
    expect(screen.getByTestId('Admin.Grant.Role')).toHaveValue('role-company')

    await chooseFrom('Admin.Grant.Scope', 'Acme Online Banking')

    expect(screen.getByTestId('Admin.Grant.Role')).toHaveValue('')
  })

  it('lands a server field error on the control that caused it', async () => {
    // The refused write is the subject: this asserts where its field error lands.
    drivingAFailedWrite()
    mockReads({ company: ['company:members'], project: ['project:members'] })
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError(400, 'Check the highlighted fields.', {
        userId: ['That person is not a member of this company.'],
      }),
    )
    renderDialog()
    await screen.findByTestId('Admin.Grant')

    await chooseFrom('Admin.Grant.Role', 'Company Admin')
    await chooseFrom('Admin.Grant.Subject', 'Ana Petrova')
    await userEvent.click(screen.getByTestId('Admin.Grant.Submit'))

    expect(
      await screen.findByText('That person is not a member of this company.'),
    ).toBeInTheDocument()
  })
})
