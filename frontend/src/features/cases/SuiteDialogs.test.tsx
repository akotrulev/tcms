import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { CaseConditionRef, Condition, Suite, TagUsage } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { CreateSuiteDialog, RenameSuiteDialog } from './SuiteDialogs'
import { drivingAFailedWrite } from '../../test/renderFailures'

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
  preconditions: [],
  postconditions: [],
  lockVersion: 3,
}

function mockReads(tags: TagUsage[] = [], conditions: Condition[] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.includes('/tags')) return tags
    if (path.includes('/conditions')) return conditions
    return []
  }) as never)
}

const LOGIN: Condition = {
  id: 'cond-1',
  title: 'Log in as standard user',
  type: 'BOTH',
  body: ['Open the application'],
  usageCount: 3,
  suiteUsageCount: 0,
  lockVersion: 0,
}

const CLEANUP: Condition = { ...LOGIN, id: 'cond-2', title: 'Reset the payment data' }

/** A served attachment — the read shape, which is NOT the shape the write takes. */
function attached(condition: Condition, id: string): CaseConditionRef {
  return {
    id,
    position: 0,
    conditionId: condition.id,
    title: condition.title,
    body: condition.body,
    inline: false,
    inherited: false,
    inheritedFrom: null,
  }
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

function renderRename(override: Partial<Suite> = {}) {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/cases`}>
      <RenameSuiteDialog
        open
        onClose={() => {}}
        projectId={PROJECT}
        suite={{ ...suite, ...override }}
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

/**
 * The suite's own conditions (§186) — the first write path `suite_condition` has ever had.
 *
 * The table shipped in M0 with its `kind` column, an index, a resolution order that reads it and
 * a `suiteUsageCount` on the library. Nothing could create a row: the only ones in this repository
 * were written by hand-rolled SQL in a backend fixture. So the failures worth pinning here are the
 * three that are silent, and they are `tagRules`' three with one addition — the payload SHAPE,
 * because the read serves `CaseConditionRef` and the write takes `CaseConditionInput`, and sending
 * the wrong one is a 400 about a field the author never typed in.
 */
describe('SuiteDialogs — conditions', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sends CaseConditionInput, not the refs it was seeded with', async () => {
    // The shape, which is the one failure a fixture cannot catch by agreeing with itself: the
    // form holds ids because order is the content, and the payload is `{conditionId}` objects.
    const user = userEvent.setup()
    mockReads([], [LOGIN, CLEANUP])
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(suite as never)
    renderRename({ preconditions: [attached(LOGIN, 'sc-1')], postconditions: [] })

    await waitFor(() => expect(screen.getByTestId('Suites.Dialog.Submit')).toBeEnabled())
    await user.click(screen.getByTestId('Suites.Dialog.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patch.mock.calls[0][1] as {
      preconditions: { conditionId: string }[]
      postconditions: unknown[]
    }
    expect(body.preconditions).toEqual([{ conditionId: 'cond-1' }])
    expect(body.postconditions).toEqual([])
  })

  it('seeds the stored attachments on a rename, so an untouched save preserves them', async () => {
    // `tagRules`' rule on the list where getting it wrong is worst: a rename that defaulted these
    // to [] would detach a standing procedure every case filed in the suite depends on, and the
    // save would report success.
    mockReads([], [LOGIN, CLEANUP])
    renderRename({
      preconditions: [attached(LOGIN, 'sc-1')],
      postconditions: [attached(CLEANUP, 'sc-2')],
    })

    expect(await screen.findByText('Log in as standard user')).toBeVisible()
    expect(await screen.findByText('Reset the payment data')).toBeVisible()
  })

  it('sends EMPTY lists when the last condition is detached, rather than omitting them', async () => {
    // The one the server cannot recover from, exactly as for `tagRules`: absent means "leave them
    // alone", so an omitted field makes detaching the last condition a no-op reported as a save.
    const user = userEvent.setup()
    mockReads([], [LOGIN])
    const patch = vi.spyOn(api, 'patch').mockResolvedValue(suite as never)
    renderRename({ preconditions: [attached(LOGIN, 'sc-1')], postconditions: [] })

    await user.click(await screen.findByTestId('Suites.Dialog.Preconditions.Remove'))
    await user.click(screen.getByTestId('Suites.Dialog.Submit'))

    await waitFor(() => expect(patch).toHaveBeenCalled())
    const body = patch.mock.calls[0][1] as { preconditions: unknown[]; postconditions: unknown[] }
    expect(body.preconditions).toEqual([])
    expect(body.postconditions).toEqual([])
  })

  it('a create sends both lists, so a suite can be born with its procedure', async () => {
    const user = userEvent.setup()
    mockReads([], [LOGIN, CLEANUP])
    const post = vi.spyOn(api, 'post').mockResolvedValue(suite as never)
    renderCreate()

    await user.type(screen.getByTestId('Suites.Dialog.Name'), 'Guarded')
    await user.click(screen.getByTestId('Suites.Dialog.Postconditions.Attach'))
    await user.click(await screen.findByText('Reset the payment data'))
    await user.click(screen.getByTestId('Suites.Dialog.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const body = post.mock.calls[0][1] as {
      preconditions: unknown[]
      postconditions: { conditionId: string }[]
    }
    expect(body.preconditions).toEqual([])
    expect(body.postconditions).toEqual([{ conditionId: 'cond-2' }])
  })

  it('lands a rejected condition on its own panel, not in the form-level alert', async () => {
    // The refused write is the subject: this asserts which panel the error lands on.
    drivingAFailedWrite()
    // The server reports under `preconditions` and the form holds `preconditionIds`. Without the
    // mapping the message would go to the root alert — visible, but not beside the list it is
    // about, on a dialog that now has three lists.
    const user = userEvent.setup()
    mockReads([], [LOGIN])
    vi.spyOn(api, 'patch').mockRejectedValue(
      new ApiError(400, 'Bad request', {
        preconditions: ['That condition is not one this project offers.'],
      }),
    )
    renderRename({ preconditions: [attached(LOGIN, 'sc-1')], postconditions: [] })

    await waitFor(() => expect(screen.getByTestId('Suites.Dialog.Submit')).toBeEnabled())
    await user.click(screen.getByTestId('Suites.Dialog.Submit'))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That condition is not one this project offers.',
    )
    expect(screen.queryByTestId('Suites.Dialog.Error')).toBeNull()
  })
})
