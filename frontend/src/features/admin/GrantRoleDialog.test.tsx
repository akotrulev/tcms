import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { GrantRoleDialog } from './GrantRoleDialog'

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
    description: 'The operator.',
    permissions: [],
  },
  {
    id: 'role-company',
    name: 'Company Admin',
    scope: 'COMPANY',
    builtIn: true,
    description: 'Everything in this company.',
    permissions: [],
  },
  {
    id: 'role-viewer',
    name: 'Viewer',
    scope: 'PROJECT',
    builtIn: true,
    description: 'Read-only.',
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
function mockReads({ company, project }: { company: string[]; project: string[] }) {
  const read = async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return path.includes('projectId')
        ? { projectId: PROJECT, permissions: project }
        : { projectId: null, permissions: company }
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

/** MUI renders a select's options into a portal, so the listbox is found on the document. */
async function chooseFrom(testId: string, optionName: string | RegExp) {
  const select = screen.getByTestId(testId).closest('.MuiInputBase-root')!
  await userEvent.click(within(select as HTMLElement).getByRole('combobox'))
  await userEvent.click(await screen.findByRole('option', { name: optionName }))
}

async function optionNames(testId: string) {
  const select = screen.getByTestId(testId).closest('.MuiInputBase-root')!
  await userEvent.click(within(select as HTMLElement).getByRole('combobox'))
  const listbox = await screen.findByRole('listbox')
  return within(listbox)
    .getAllByRole('option')
    .map((option) => option.textContent)
}

describe('GrantRoleDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('offers only COMPANY-scope roles while the scope is the whole company', async () => {
    mockReads({ company: ['company:members'], project: [] })
    renderDialog()
    await screen.findByTestId('Admin.Grant')

    expect(await optionNames('Admin.Grant.Role')).toEqual(['Company Admin'])
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
