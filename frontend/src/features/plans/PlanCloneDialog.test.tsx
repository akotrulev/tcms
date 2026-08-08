import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import type { TestPlan } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { PlanCloneDialog } from './PlanCloneDialog'

/**
 * Cloning a plan (§129 phase 3).
 *
 * **The assertion that matters is the path**, and it looks trivial until you know what the other
 * spelling costs: `POST /plans` with the copied fields is the *obvious* implementation, it
 * type-checks, it answers 201, and it silently drops the sections, the tag arm, both prose fields
 * and both criteria lists — because `TestPlanInput` carries six fields and a plan detail is twelve.
 * A test asserting only "a plan was created" passes against that version.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

function sourcePlan(over: Partial<TestPlan> = {}): TestPlan {
  return { id: 'plan-1', key: 'TP-3', name: 'Regression 4.3', ...over } as TestPlan
}

function renderDialog(onCloned = vi.fn()) {
  render(
    <TestProviders client={createTestQueryClient()}>
      <PlanCloneDialog
        projectId={PROJECT}
        plan={sourcePlan()}
        open
        onClose={() => {}}
        onCloned={onCloned}
      />
    </TestProviders>,
  )
  return onCloned
}

describe('PlanCloneDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('suggests the name and posts to the clone endpoint, not to the create', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ id: 'plan-2', key: 'TP-4' } as never)
    const onCloned = renderDialog()

    expect(await screen.findByTestId('PlanStats.Clone.Name')).toHaveValue('Copy of Regression 4.3')

    const name = screen.getByTestId('PlanStats.Clone.Name')
    await userEvent.clear(name)
    await userEvent.type(name, 'Regression 4.4')
    await userEvent.click(screen.getByTestId('PlanStats.Clone.Submit'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    const [path, body] = post.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe(`/projects/${PROJECT}/plans/plan-1/clone`)
    // The name and NOTHING else: everything a plan consists of is read from the source server-side,
    // and a body carrying a copied scope would be this client's resolution of it, at this instant.
    expect(body).toEqual({ name: 'Regression 4.4' })
    await waitFor(() => expect(onCloned).toHaveBeenCalled())
  })

  it('refuses an empty name without asking the server', async () => {
    const post = vi.spyOn(api, 'post')
    renderDialog()

    await userEvent.clear(await screen.findByTestId('PlanStats.Clone.Name'))
    await userEvent.click(screen.getByTestId('PlanStats.Clone.Submit'))

    expect(await screen.findByText('Enter a name for the copy.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('lands a name error on the field and a 403 in the banner', async () => {
    vi.spyOn(api, 'post').mockRejectedValueOnce(
      new ApiError(400, 'Check the highlighted fields.', { name: ['That name is already used.'] }),
    )
    renderDialog()

    await screen.findByTestId('PlanStats.Clone.Name')
    await userEvent.click(screen.getByTestId('PlanStats.Clone.Submit'))
    expect(await screen.findByText('That name is already used.')).toBeInTheDocument()
    expect(screen.queryByTestId('PlanStats.Clone.Error')).not.toBeInTheDocument()

    vi.spyOn(api, 'post').mockRejectedValueOnce(new ApiError(403, 'You cannot create plans here.'))
    await userEvent.click(screen.getByTestId('PlanStats.Clone.Submit'))
    expect(await screen.findByTestId('PlanStats.Clone.Error')).toBeInTheDocument()
  })

  it('says the copy starts as a draft — the one fact that surprises a reader', async () => {
    renderDialog()
    expect(await screen.findByText(/starts as a draft/i)).toBeInTheDocument()
  })
})
