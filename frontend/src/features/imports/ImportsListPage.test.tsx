import { render, screen, within } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { aProject, anImportRunSummary } from '../../test/fixtures'
import { ImportsListPage } from './ImportsListPage'

/**
 * `settings-import.html`, and the three things a table of past runs cannot say for itself.
 *
 * 1. **An empty project gets the start button, never a bare empty table.** This is the state most
 *    readers arrive in, and a header row over nothing tells them neither what the screen is for nor
 *    what to do about it.
 * 2. **The list is `GET /imports?projectId=…`** — not `/projects/{id}/imports`, which is what the
 *    plan's sketch said and T1 did not build. Asserted on the path, because the two are
 *    indistinguishable from the screen.
 * 3. **Without `issue:import` the start button is disabled with a reason** rather than absent
 *    (§31). The rail entry is the other half of D-e and goes the other way — it is *un-listed*,
 *    because there is no read-only view of an import at all; a reader who reached this URL anyway
 *    should still be told the capability exists.
 */

const PROJECT = 'p1'

function mockReads(permissions: string[], runs: unknown[] = []) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(permissions as never, {}, PROJECT)
    }
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: PROJECT, name: 'Acme Storefront' })
    if (path.startsWith('/imports?')) return runs
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/imports`}>
      <Routes>
        <Route path="/projects/:projectId/imports" element={<ImportsListPage />} />
      </Routes>
    </TestProviders>,
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('the imports list', () => {
  it('reads the project’s runs from the query-string endpoint', async () => {
    const get = mockReads(
      ['issue:read', 'issue:import'],
      [anImportRunSummary({ id: 'ir-1', sourceProjectKey: 'PROJ', issuesImported: 1482, statusesMapped: 6, labelsCreated: 214, droppedFieldCount: 7 })],
    )
    renderPage()

    const row = await screen.findByTestId('Imports.Row')
    expect(row).toHaveTextContent('PROJ')
    // `formatCount` pins `en-US`, so the separator is a comma here and in the browser alike.
    expect(row).toHaveTextContent('1,482 issues · 6 statuses · 214 labels')
    expect(row).toHaveTextContent('7 field types')
    expect(within(row).getByTestId('Imports.Row.Open')).toHaveAttribute(
      'href',
      `/projects/${PROJECT}/imports/ir-1`,
    )

    /*
      The endpoint T1 actually built. `/projects/{id}/imports` is what this plan's own sketch said,
      and a screen calling it would 404 against every real server while every mock in this file
      happily answered it.
    */
    expect(get.mock.calls.some(([path]) => String(path).startsWith('/imports?projectId='))).toBe(
      true,
    )
  })

  it('offers the start button instead of an empty table', async () => {
    mockReads(['issue:read', 'issue:import'])
    renderPage()

    expect(await screen.findByTestId('Imports.Empty')).toHaveTextContent('No imports yet')
    expect(screen.queryByTestId('Imports.Table')).not.toBeInTheDocument()
    expect(screen.getByTestId('Imports.Start.Empty')).toBeInTheDocument()
  })

  it('says nothing keeps syncing, which is the misunderstanding the screen exists next to', async () => {
    mockReads(['issue:read', 'issue:import'])
    renderPage()

    expect(await screen.findByTestId('Imports.NotAnIntegration')).toHaveTextContent(
      /Nothing here keeps syncing/,
    )
  })

  it('disables the start button for a caller without issue:import, and keeps it on screen', async () => {
    mockReads(['issue:read'])
    renderPage()

    // §31: disable, don't hide. The rail entry is hidden for the same caller (D-e) and this is not
    // a contradiction — the entry is a claim about where to go, this is a claim about what exists.
    expect(await screen.findByTestId('Imports.Start')).toBeDisabled()
  })

  it('does not render a failed run as a small import', async () => {
    mockReads(
      ['issue:read', 'issue:import'],
      [anImportRunSummary({ id: 'ir-2', status: 'FAILED' })],
    )
    renderPage()

    const row = await screen.findByTestId('Imports.Row')
    // Every count on a FAILED summary is zero because nothing was written, and "0 issues · 0
    // statuses · 0 labels" reads as a migration that brought nothing across rather than one that
    // never ran.
    expect(row).not.toHaveTextContent('0 issues')
    expect(within(row).getByTestId('Imports.Row.Status')).toHaveTextContent(
      'Failed — nothing was imported',
    )
  })
})
