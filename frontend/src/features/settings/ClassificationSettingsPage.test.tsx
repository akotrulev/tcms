import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { ClassificationDimension, PermissionKey } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { ClassificationSettingsPage } from './ClassificationSettingsPage'

/**
 * The vocabulary editor — and the one property no gate can see: **which permission each dimension's
 * controls ask for.**
 *
 * A page that gated everything on `project:settings` renders identically to this one for a Company
 * Admin, passes every "the Feature tree is editable" assertion, and hands a Test Lead an enabled
 * "Add value" on the company's Test Type axis — a control whose submit the server answers 403 to.
 * §31's disable-don't-hide is unfollowable by a page that asks the wrong question, so the tests
 * that matter here are the ones that render the *same* page for two callers.
 *
 * The second property is the tree. `buildValueTree` surfaces orphans and cycle members at the root
 * rather than dropping them, and its own test pins that; what this file pins is that the page uses
 * it — a nested value must appear once, with a control, or nobody can retire it.
 */

const PROJECT = 'p1'

const dimensions: ClassificationDimension[] = [
  {
    id: 'dim-feature',
    key: 'feature',
    name: 'Feature',
    cardinality: 'MULTI',
    hierarchical: true,
    locked: false,
    values: [
      { id: 'v-payments', parentId: null, name: 'Payments', path: 'vpayments', color: null },
      {
        id: 'v-refunds',
        parentId: 'v-payments',
        name: 'Refunds',
        path: 'vpayments.vrefunds',
        color: null,
      },
      // An orphan: its parent is not in the payload, which is what retiring a parent while its
      // children stay live produces. It must still be on screen with its own controls.
      { id: 'v-orphan', parentId: 'v-gone', name: 'Stranded', path: 'vgone.vorphan', color: null },
    ],
  },
  {
    id: 'dim-type',
    key: 'test_type',
    name: 'Test Type',
    cardinality: 'SINGLE',
    hierarchical: false,
    locked: true,
    values: [
      { id: 'v-functional', parentId: null, name: 'Functional', path: 'functional', color: null },
    ],
  },
]

/**
 * `GET /me/permissions` answers two different questions and this page asks both.
 *
 * With a `projectId` it is the project's grants; without one it is the SYSTEM + COMPANY terms.
 * Answering the same array to both is the shortcut roles spec §A3 records rejecting, and a mock
 * that did it here would make every assertion below pass whichever permission the page asked for.
 */
function mockApi(onProject: PermissionKey[], inCompany: PermissionKey[]) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions?')) return heldPermissions(onProject, {}, PROJECT)
    if (path.startsWith('/me/permissions')) return heldPermissions(inCompany, {}, null)
    if (path.endsWith('/classification')) return dimensions
    return { id: PROJECT, key: 'OLB', name: 'Online Banking' }
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/settings/classification`}
    >
      {/* Through a Route, not rendered bare: the page reads `projectId` from `useParams`, and a
          bare render gives it `''` — which makes `useCan` disabled, permanently pending, and
          fail-closed. Every "disabled" assertion below would then pass without the gate existing. */}
      <Routes>
        <Route
          path="/projects/:projectId/settings/classification"
          element={<ClassificationSettingsPage />}
        />
      </Routes>
    </TestProviders>,
  )
}

/**
 * Wait until the permission answers have landed — **without this every "disabled" assertion below
 * passes for the wrong reason.**
 *
 * `useCan` is fail-closed while pending, so every control is disabled on the first render even for
 * a caller who is about to be allowed. The Feature Add button is the barrier: it is enabled for
 * both personas these tests use, so it flips exactly once the project answer resolves. §66's
 * lesson, which was found by running the mutation rather than by reading the test.
 */
async function permissionsResolved() {
  await waitFor(() => expect(screen.getByTestId('Classification.feature.Add')).toBeEnabled())
}

describe('ClassificationSettingsPage — the gate each dimension asks for', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lets a Test Lead edit the project’s Feature tree', async () => {
    mockApi(['project:settings'], ['company:read'])
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Classification.feature.Add')).toBeEnabled())
    expect(screen.getByTestId('Classification.Value.v-payments.Edit')).toBeEnabled()
    expect(screen.getByTestId('Classification.Value.v-payments.Retire')).toBeEnabled()
  })

  it('refuses the same Test Lead the company’s Test Type axis', async () => {
    // The headline. One page, one caller, two dimensions, two answers — and a page that asked
    // `project:settings` for both would enable this button and let the server refuse the submit.
    mockApi(['project:settings'], ['company:read'])
    renderPage()
    await permissionsResolved()

    expect(screen.getByTestId('Classification.test_type.Add')).toBeDisabled()
    expect(screen.getByTestId('Classification.Value.v-functional.Edit')).toBeDisabled()
  })

  it('lets a Company Admin edit both', async () => {
    // The guard. Without it the assertions above hold equally well against a page that disables
    // the company axes for EVERYBODY, which would leave `company:vocabulary` unreachable in the
    // product — the state this whole slice exists to end.
    mockApi(['project:settings'], ['company:read', 'company:vocabulary'])
    renderPage()
    await permissionsResolved()

    await waitFor(() =>
      expect(screen.getByTestId('Classification.test_type.Add')).toBeEnabled(),
    )
    expect(screen.getByTestId('Classification.Value.v-functional.Edit')).toBeEnabled()
  })

  it('shows a caller who can edit nothing the whole vocabulary anyway', async () => {
    // Disable-don't-hide (§31). A Test Engineer sees the axes their cases are classified on and
    // learns the capability exists; hiding them would say the product cannot do it.
    mockApi(['case:read'], ['company:read'])
    renderPage()

    await waitFor(() => expect(screen.getByTestId('Classification.feature.Add')).toBeDisabled())
    expect(screen.getByTestId('Classification.Value.v-payments.Edit')).toBeDisabled()
    expect(screen.getByText('Payments')).toBeInTheDocument()
  })

  it('renders a nested value, and one whose parent is missing, exactly once each', async () => {
    mockApi(['project:settings'], ['company:read'])
    renderPage()
    await permissionsResolved()

    // "Stranded" is the orphan. Dropped, it would be a value nobody can retire — and the retire
    // button is the only way back from the state that produced it.
    expect(screen.getAllByText('Stranded')).toHaveLength(1)
    expect(screen.getByTestId('Classification.Value.v-orphan.Retire')).toBeEnabled()
    expect(screen.getAllByText('Refunds')).toHaveLength(1)
  })

  it('offers "add underneath" only on a hierarchical dimension', async () => {
    // A flat dimension would accept a parent — nothing in the schema forbids it — and the product
    // has no way to render the result, so the control that would create it does not exist.
    mockApi(['project:settings'], ['company:read', 'company:vocabulary'])
    renderPage()
    await permissionsResolved()

    expect(screen.getByTestId('Classification.Value.v-payments.AddChild')).toBeInTheDocument()
    expect(screen.queryByTestId('Classification.Value.v-functional.AddChild')).toBeNull()
  })
})

describe('ClassificationSettingsPage — retiring', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('says what a retire does before doing it', async () => {
    mockApi(['project:settings'], ['company:read'])
    renderPage()
    await permissionsResolved()

    await userEvent.click(screen.getByTestId('Classification.Value.v-refunds.Retire'))

    // The verb does not carry either half: nothing is deleted, and the cases already classified
    // with it keep it. An administrator who assumes otherwise will not retire anything.
    expect(screen.getByRole('dialog')).toHaveTextContent(/nothing is deleted/i)
    expect(screen.getByRole('dialog')).toHaveTextContent(/past runs still resolve it/i)
  })

  it('keeps the dialog open and shows the server sentence when the value still has children', async () => {
    mockApi(['project:settings'], ['company:read'])
    // The payload the server actually sends since §149 — a sentence, not the bare `'2'` this
    // fixture used to supply and then assert. That pairing is what let the page and the API
    // disagree in the first place: the dialog composed words around a count, so no test on this
    // side could notice that the CLI and the MCP server were printing the count alone.
    vi.spyOn(api, 'del').mockRejectedValue(
      new ApiError(409, 'Retire the values underneath this one first.', {
        children: ['2 values sit under this one. Retire those first — retiring a whole branch at once would unclassify every case beneath it with nothing on screen naming them.'],
      }),
    )
    renderPage()
    await permissionsResolved()

    await userEvent.click(screen.getByTestId('Classification.Value.v-payments.Retire'))
    await userEvent.click(screen.getByTestId('Classification.Retire.Confirm'))

    // Not a toast over a closed dialog, and not an optimistic removal that puts the row back: the
    // refusal is the one thing the user needs to read, and it names how many rows are in the way.
    const blocked = await screen.findByTestId('Classification.Retire.Blocked')
    expect(blocked).toHaveTextContent('2 values sit under this one')
    expect(blocked).toHaveTextContent(/retire those first/i)
    expect(screen.getByTestId('Classification.Retire.Confirm')).toBeInTheDocument()
  })
})
