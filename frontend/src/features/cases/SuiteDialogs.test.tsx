import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Suite, TagUsage } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { CreateSuiteDialog, RenameSuiteDialog } from './SuiteDialogs'

/**
 * The suite form's **tag rules** — the membership arm that turns a folder into a query (§96).
 *
 * This file did not exist before this slice, which is the same gap §82 found on
 * `ReviewSettingsPage`: a dialog that has shipped three writes with no test of its own. It is
 * created here for the field whose failures are all silent:
 *
 *   1. **A rename must always SEND the rules.** The server reads an absent `tagRules` as "leave
 *      them alone", so a form that omitted the field could never clear the last rule — the save
 *      would report success and the collection would keep holding cases with nothing on screen
 *      saying why.
 *   2. **A rename must SEED them.** Sending the field while defaulting it to `[]` is worse than
 *      omitting it: every rename would silently empty the suite.
 *   3. **A duplicate is not a second chip.** `suite_tag_rule`'s key is the whole row, so `smoke`
 *      twice is the same rule, not an error about a chip you cannot see twice.
 */

const PROJECT = 'p-1'

const suite: Suite = {
  id: 's-1',
  key: 'TS-1',
  name: 'Payments',
  description: null,
  caseCount: 4,
  tagRules: ['smoke'],
  caseIds: [],
  lifecycleStatus: 'DRAFT',
  conditions: [],
  lockVersion: 3,
}

function mockReads(tags: TagUsage[] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) =>
    path.includes('/tags') ? tags : []) as never)
}

function renderCreate() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/cases`}>
      <CreateSuiteDialog
        open
        onClose={() => {}}
        projectId={PROJECT}
        onCreated={() => {}}
      />
    </TestProviders>,
  )
}

function renderRename() {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/cases`}>
      <RenameSuiteDialog
        open
        onClose={() => {}}
        projectId={PROJECT}
        suite={suite}
        onRenamed={() => {}}
      />
    </TestProviders>,
  )
}

describe('SuiteDialogs — tag rules', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('creates a suite with the rules it was given, de-duplicated', async () => {
    const user = userEvent.setup()
    mockReads()
    const post = vi.spyOn(api, 'post').mockResolvedValue(suite as never)
    renderCreate()

    await user.type(screen.getByTestId('Suites.Dialog.Name'), 'Smoke')
    await user.type(screen.getByTestId('Suites.Dialog.TagRules'), 'smoke{Enter}  smoke  {Enter}')
    await user.click(screen.getByTestId('Suites.Dialog.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = post.mock.calls[0][1] as { tagRules: string[] }
    expect(body.tagRules).toEqual(['smoke'])
  })

  it('suggests the project tags as rules, and still accepts one no case carries yet', async () => {
    const user = userEvent.setup()
    mockReads([
      { tag: 'smoke', caseCount: 9 },
      { tag: 'regression', caseCount: 4 },
    ])
    const post = vi.spyOn(api, 'post').mockResolvedValue(suite as never)
    renderCreate()

    await user.type(screen.getByTestId('Suites.Dialog.Name'), 'Smoke')
    await user.type(screen.getByTestId('Suites.Dialog.TagRules'), 'smo')
    await user.click(await screen.findByRole('option', { name: 'smoke' }))

    /*
      A RULE MAY NAME A TAG NOTHING CARRIES YET, and that is not an edge case — it is how a suite
      gets written before the cases it will hold. Suggesting from the tags in use must not narrow
      what a rule may say, so `freeSolo` stays and this half of the test is what keeps it.
    */
    await user.type(screen.getByTestId('Suites.Dialog.TagRules'), 'not-yet-used{Enter}')
    await user.click(screen.getByTestId('Suites.Dialog.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = post.mock.calls[0][1] as { tagRules: string[] }
    expect(body.tagRules).toEqual(['smoke', 'not-yet-used'])
  })

  it('seeds the stored rules on a rename, and sends them back unchanged', async () => {
    // Sending the field while defaulting it to [] is worse than omitting it: every rename would
    // silently empty a collection somebody assembled.
    const user = userEvent.setup()
    mockReads()
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(suite as never)
    renderRename()

    expect(await screen.findByText('smoke')).toBeVisible()

    await user.clear(screen.getByTestId('Suites.Dialog.Name'))
    await user.type(screen.getByTestId('Suites.Dialog.Name'), 'Payments renamed')
    await user.click(screen.getByTestId('Suites.Dialog.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patch.mock.calls[0][1] as { tagRules: string[] }
    expect(body.tagRules).toEqual(['smoke'])
  })

  it('sends an EMPTY list when the last rule is removed, rather than omitting it', async () => {
    // The one the server cannot recover from: absent means "leave them alone", so an omitted
    // field makes clearing the last rule a save that reports success and changes nothing.
    const user = userEvent.setup()
    mockReads()
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(suite as never)
    renderRename()

    await user.click(await screen.findByTestId('CancelIcon'))
    await user.click(screen.getByTestId('Suites.Dialog.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patch.mock.calls[0][1] as { tagRules: string[] }
    expect(body.tagRules).toEqual([])
  })
})
