import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { Evidence, PermissionKey } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { StepEvidence } from './StepEvidence'

/**
 * Step-level evidence (audit C9) — the affordance that finally sends a `stepId`.
 *
 * The whole path existed before this: `EvidenceController` has taken the form part since §72,
 * `useAttachEvidence` has forwarded it since the hook was written, and `StepResult.evidence` has
 * served the result back the whole time. `EvidencePanel` contained the string `stepId` zero times,
 * so the only caller never used any of it.
 *
 * **The load-bearing test is the last one**, and it is not about the upload. The server resolves
 * `stepId` against `step_outcome` and answers a 400 when the step has no recorded result; the marks
 * on the runner are local until Submit. So an affordance keyed on the local mark would 400 every
 * time, and the one keyed on the server's `statusId` cannot.
 */

const PROJECT = 'p1'
const CALLER = '99999999-9999-4999-8999-999999999999'
const STEP = '11111111-1111-4111-8111-111111111111'

function evidence(id = 'e1'): Evidence {
  return {
    id,
    filename: 'step-3-failure.png',
    contentType: 'image/png',
    sizeBytes: 184_320,
    url: `/api/v1/projects/${PROJECT}/runs/r1/executions/x1/evidence/${id}/content`,
    thumbnailUrl: null,
    caption: null,
    createdBy: null,
    createdAt: '2026-07-28T09:14:00Z',
  }
}

function mockPermissions(keys: PermissionKey[] = ['evidence:attach', 'evidence:delete']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) {
      return heldPermissions(keys)
    }
    return []
  }) as never)
}

function renderStrip({
  rows = [] as Evidence[],
  recorded = true,
  frozen = false,
} = {}) {
  return render(
    <TestProviders
      client={createTestQueryClient()}
      route={`/projects/${PROJECT}/runs/r1/executions/x1`}
      userId={CALLER}
    >
      <StepEvidence
        projectId={PROJECT}
        runId="r1"
        executionId="x1"
        stepId={STEP}
        evidence={rows}
        recorded={recorded}
        frozen={frozen}
      />
    </TestProviders>,
  )
}

const attach = () => screen.getByTestId('Execution.Steps.Row.Evidence.Attach')

describe('StepEvidence', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the files already attached to THIS step', async () => {
    mockPermissions()
    renderStrip({ rows: [evidence()] })

    const link = await screen.findByTestId('Execution.Steps.Row.Evidence.Link')
    expect(link).toHaveTextContent('step-3-failure.png')
  })

  it('sends the stepId with the upload — the whole point of the slice', async () => {
    mockPermissions()
    const upload = vi.spyOn(api, 'upload').mockResolvedValue(evidence() as never)
    renderStrip()
    await waitFor(() => expect(attach()).toBeEnabled())

    const file = new File(['bytes'], 'step-3-failure.png', { type: 'image/png' })
    await userEvent.upload(screen.getByTestId('Execution.Steps.Row.Evidence.Input'), file)

    await waitFor(() => expect(upload).toHaveBeenCalled())
    const form = upload.mock.calls[0][1] as FormData
    expect(form.get('stepId')).toBe(STEP)
    expect((form.get('file') as File).name).toBe('step-3-failure.png')
  })

  it('removes a file by id', async () => {
    mockPermissions()
    const del = vi.spyOn(api, 'del').mockResolvedValue(undefined as never)
    renderStrip({ rows: [evidence()] })

    await waitFor(() =>
      expect(screen.getByTestId('Execution.Steps.Row.Evidence.Remove')).toBeEnabled(),
    )
    await userEvent.click(screen.getByTestId('Execution.Steps.Row.Evidence.Remove'))

    await waitFor(() =>
      expect(del).toHaveBeenCalledWith(
        `/projects/${PROJECT}/runs/r1/executions/x1/evidence/e1`,
      ),
    )
  })

  it('refuses the upload without evidence:attach', async () => {
    mockPermissions([])
    renderStrip()

    await waitFor(() => expect(attach()).toBeDisabled())
  })

  it('freezes the upload on a closed run', async () => {
    mockPermissions()
    renderStrip({ frozen: true })

    await waitFor(() => expect(attach()).toBeDisabled())
  })

  it('refuses — and SAYS WHY — on a step the server has no result for', async () => {
    // A guaranteed 400 otherwise: the server resolves stepId against step_outcome, and a step
    // nobody has submitted a verdict for has no row to attach to. The sentence is the server's.
    mockPermissions()
    renderStrip({ recorded: false })

    await waitFor(() => expect(attach()).toBeDisabled())
    expect(
      screen.getByText('Record a result for this step before attaching evidence to it.'),
    ).toBeInTheDocument()
  })
})
