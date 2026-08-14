import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { CaseFlake, PermissionKey, TestCase } from '../../api/types'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { heldPermissions } from '../../test/permissions'
import { FlakePanel } from './FlakePanel'

/**
 * The flake panel and the quarantine action (§121 phase 4).
 *
 * **The assertions worth keeping are the three no-number states.** A panel that renders a null
 * score as `0` type-checks, lints, builds and looks entirely reasonable on screen — it just tells
 * a reader that a test nobody has enough evidence about is *evidently stable*, which is the exact
 * claim somebody uses to decide not to quarantine it. `lib/flake.test.ts` pins the arithmetic;
 * these pin that the panel renders each verdict as a distinguishable sentence rather than as three
 * versions of a blank.
 */

const PROJECT = 'p1'
const CALLER = '99999999-9999-4999-8999-999999999999'

function flakeRow(over: Partial<CaseFlake> = {}): CaseFlake {
  return {
    caseId: 'c1',
    executions: 20,
    transitions: 18,
    alternations: 4,
    cells: 2,
    flakeScore: 0.2222,
    lastAlternationAt: '2026-08-06T10:00:00Z',
    computedAt: '2026-08-07T02:00:00Z',
    quarantined: false,
    quarantinedAt: null,
    quarantinedBy: null,
    quarantineReason: null,
    ...over,
  }
}

/**
 * The case the panel hangs off. **Four fields were all this file needed and all it declared**,
 * behind an `as TestCase` that §196 removed — the panel reads `flake` and nothing else, and the
 * other thirty-three are here so that the day it reads a thirty-fourth this is a build error rather
 * than a `undefined` the assertion happens not to look at.
 */
function caseBody(flake: CaseFlake | null): TestCase {
  return {
    id: 'c1',
    key: 'TC-1',
    keySeq: 1,
    title: 'Full refund of a settled card payment succeeds',
    suiteId: 's1',
    suiteName: 'Refunds',
    priority: 'HIGH',
    lifecycleStatus: 'APPROVED',
    automationStatus: 'MANUAL',
    stepFormat: 'STRUCTURED',
    owner: null,
    createdBy: null,
    features: [],
    tags: [],
    healthSignals: [],
    lastResult: null,
    updatedAt: '2026-07-28T12:00:00Z',
    attachments: [],
    flake,
    classificationValueIds: [],
    description: null,
    objective: null,
    expectedOutcome: null,
    severity: null,
    estimatedDurationSec: null,
    automationTags: [],
    automationKey: null,
    notAutomatableReason: null,
    gherkinSource: null,
    steps: [],
    preconditions: [],
    postconditions: [],
    declinedPreconditions: [],
    declinedPostconditions: [],
    relatedDocuments: [],
    currentVersionNo: 1,
    lockVersion: 1,
  }
}

function mockPermissions(held: PermissionKey[] = ['case:read', 'case:edit']) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(held)
    return []
  }) as never)
}

function renderPanel(flake: CaseFlake | null) {
  return render(
    <TestProviders client={createTestQueryClient()} route={`/projects/${PROJECT}/cases/c1`} userId={CALLER}>
      <FlakePanel projectId={PROJECT} testCase={caseBody(flake)} />
    </TestProviders>,
  )
}

const quarantineButton = () => screen.getByTestId('CaseDetail.Flake.QuarantineButton')

/**
 * The permission answer is fail-closed while pending, so the action button is disabled on the first
 * render for a caller who is about to be allowed. Every "disabled" assertion below would pass
 * against that pending state and prove nothing — `CaseDetailPage.test.tsx` records the same trap.
 */
async function permissionsResolved() {
  await waitFor(() => expect(quarantineButton()).toBeEnabled())
}

describe('FlakePanel — three ways to have no number, and none of them is a blank', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockPermissions()
  })

  it('THE ONE THAT MATTERS: an unscored row does not read like a stable one', async () => {
    // `score ?? 0` is the defect, and this is the assertion that fails when somebody writes it.
    // The two must not render the same words, and the unscored one must show its counts.
    const { unmount } = renderPanel(flakeRow({ flakeScore: null, executions: 3, transitions: 2 }))

    expect(screen.getByTestId('CaseDetail.Flake.NoScore')).toHaveTextContent(
      'Not enough evidence to score.',
    )
    expect(screen.queryByTestId('CaseDetail.Flake.Band')).not.toBeInTheDocument()
    expect(screen.getByTestId('CaseDetail.Flake.Evidence')).toHaveTextContent('3 results')
    unmount()

    renderPanel(flakeRow({ flakeScore: 0, executions: 40, transitions: 38, alternations: 0 }))

    expect(screen.getByTestId('CaseDetail.Flake.Band')).toHaveTextContent('Stable')
    expect(screen.queryByTestId('CaseDetail.Flake.NoScore')).not.toBeInTheDocument()
  })

  it('says nobody has looked when there is no row at all', () => {
    // The third state, and the one above the other two. It carries no counts and no "computed at",
    // because there is no measurement to be current or stale about.
    renderPanel(null)

    expect(screen.getByTestId('CaseDetail.Flake.NoScore')).toHaveTextContent('Not assessed yet.')
    expect(screen.queryByTestId('CaseDetail.Flake.Evidence')).not.toBeInTheDocument()
    expect(screen.queryByTestId('CaseDetail.Flake.ComputedAt')).not.toBeInTheDocument()
  })

  it('renders a score with its band and the run it was drawn from', () => {
    renderPanel(flakeRow())

    expect(screen.getByTestId('CaseDetail.Flake.Band')).toHaveTextContent('Flaky')
    expect(screen.getByTestId('CaseDetail.Flake.Evidence')).toHaveTextContent(
      '4 switches between pass and fail across 18 repeat runs, in 2 configurations.',
    )
    expect(screen.getByTestId('CaseDetail.Flake.ComputedAt')).toBeInTheDocument()
  })
})

describe('FlakePanel — the quarantine record', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockPermissions()
  })

  const quarantined = flakeRow({
    quarantined: true,
    quarantinedAt: '2026-08-07T09:30:00Z',
    quarantinedBy: { id: 'u9', displayName: 'L. Lead', email: 'lead@x.test', avatarUrl: null },
    quarantineReason: 'Flaky on Firefox, races the login redirect',
  })

  it('renders the reason VERBATIM, commas and all', () => {
    // §121 phase 3's whole argument for `QUARANTINE` being its own work-item kind: a
    // `CASE_MAINTENANCE` description is split on commas and looked up code by code, so this
    // sentence would render as two fragments joined by a middot with nothing failing. The panel
    // must not acquire that habit from the board.
    renderPanel(quarantined)

    expect(screen.getByTestId('CaseDetail.Flake.Quarantined')).toHaveTextContent(
      'Flaky on Firefox, races the login redirect',
    )
  })

  it('names the decider, who is a person and not an id', () => {
    // `quarantinedBy` was a bare uuid until this slice — the only actor field on the API that was.
    renderPanel(quarantined)

    expect(screen.getByTestId('CaseDetail.Flake.Quarantined')).toHaveTextContent('by L. Lead')
  })

  it('survives an unresolvable decider without inventing one or dropping the date', () => {
    // The id resolves to no account — an offboarded colleague. `CaseOwners` answers null rather
    // than a made-up name, so the panel has to have a sentence for it.
    renderPanel(flakeRow({ ...quarantined, quarantinedBy: null }))

    const alert = screen.getByTestId('CaseDetail.Flake.Quarantined')
    expect(alert).toHaveTextContent('somebody no longer in this company')
    expect(alert).toHaveTextContent('2026')
  })

  it('offers the opposite direction on a quarantined case', async () => {
    renderPanel(quarantined)
    await permissionsResolved()

    expect(quarantineButton()).toHaveTextContent('Return to the gate')
  })

  it('disables the action rather than hiding it when case:edit is not held', async () => {
    // §31, disable-don't-hide. And `case:edit` specifically — the gate IS the slice: Automation
    // Service does not hold it, so a leaked CI token cannot quarantine the tests its own results
    // fail. A panel gated on `result:submit` would pass every other test in this file.
    vi.restoreAllMocks()
    mockPermissions(['case:read'])
    renderPanel(flakeRow())

    // Nothing distinguishes "denied" from "still pending" on screen, so the wait is on the answer
    // having had time to land rather than on a rendered state.
    await waitFor(() => expect(quarantineButton()).toBeDisabled())
    expect(quarantineButton()).toBeInTheDocument()
  })
})

describe('QuarantineDialog — the reason is required in one direction only', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockPermissions()
  })

  it('refuses a blank reason without sending a request', async () => {
    // The check exists on the server too, and that one binds. This one exists because the answer is
    // certain and a round trip to be told so is a worse version of the same message.
    const put = vi.spyOn(api, 'put').mockResolvedValue(flakeRow() as never)
    renderPanel(flakeRow())
    await permissionsResolved()

    await userEvent.click(quarantineButton())
    await userEvent.click(screen.getByTestId('CaseDetail.Quarantine.Confirm'))

    expect(put).not.toHaveBeenCalled()
    expect(screen.getByTestId('CaseDetail.Quarantine')).toHaveTextContent(
      'Say why this test is being quarantined.',
    )
  })

  it('sends the trimmed reason and the state, never a derived toggle', async () => {
    const put = vi
      .spyOn(api, 'put')
      .mockResolvedValue(flakeRow({ quarantined: true }) as never)
    renderPanel(flakeRow())
    await permissionsResolved()

    await userEvent.click(quarantineButton())
    await userEvent.type(
      within(screen.getByTestId('CaseDetail.Quarantine')).getByTestId('CaseDetail.Quarantine.Reason'),
      '  Races the login redirect  ',
    )
    await userEvent.click(screen.getByTestId('CaseDetail.Quarantine.Confirm'))

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith(`/projects/${PROJECT}/cases/c1/quarantine`, {
        quarantined: true,
        reason: 'Races the login redirect',
      }),
    )
  })

  it('releases with NO reason field at all, because the endpoint ignores it', async () => {
    // Sent as `{quarantined: false}` and not `{quarantined: false, reason: ''}` — a field the
    // contract documents as ignored is one the request should carry no opinion in.
    const put = vi.spyOn(api, 'put').mockResolvedValue(flakeRow() as never)
    renderPanel(flakeRow({ quarantined: true, quarantinedAt: '2026-08-07T09:30:00Z', quarantinedBy: null, quarantineReason: 'x' }))
    await permissionsResolved()

    await userEvent.click(quarantineButton())
    await userEvent.click(screen.getByTestId('CaseDetail.Quarantine.Confirm'))

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith(`/projects/${PROJECT}/cases/c1/quarantine`, {
        quarantined: false,
      }),
    )
  })

  it('has no reason field when releasing', async () => {
    renderPanel(flakeRow({ quarantined: true, quarantinedAt: '2026-08-07T09:30:00Z', quarantinedBy: null, quarantineReason: 'x' }))
    await permissionsResolved()

    await userEvent.click(quarantineButton())

    expect(screen.queryByTestId('CaseDetail.Quarantine.Reason')).not.toBeInTheDocument()
  })
})
