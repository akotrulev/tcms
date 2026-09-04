import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { anAbsence } from '../../test/fixtures'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { AbsencePanel } from './AbsencePanel'

/**
 * 017 D-h, which is a decision about permissions that is visible in exactly one control.
 *
 * `staffing:absence` covers *"record absence on somebody else's behalf"* — so recording that **you**
 * are away on Thursday needs no key at all, and the Add control is enabled on your own page for
 * everybody. A product that made your own leave an administrative act would be a product where
 * nobody records absence, which would make every capacity figure in this module a lie by omission.
 *
 * The second claim is `member.html`'s own sentence, kept verbatim because 017 D-a is what makes it
 * literally true: capacity is derived on every read, so an absence recorded here is subtracted the
 * next time any surface asks. In a product that cached capacity the same sentence would be
 * aspirational.
 */

const HALF_DAY = anAbsence({ id: 'ab-1', personId: 'u-mira', dayFraction: 0.5 })

function renderPanel({
  isSelf,
  canRecordForOthers,
}: {
  isSelf: boolean
  canRecordForOthers: boolean
}) {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u-me">
      <AbsencePanel
        userId="u-mira"
        absences={[HALF_DAY]}
        isSelf={isSelf}
        canRecordForOthers={canRecordForOthers}
        reason="You don't have permission to record somebody else's absence in this company."
        testId="Absence"
      />
    </TestProviders>,
  )
}

describe('Recording absence', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('enables Add on your own page for somebody holding no staffing key at all', () => {
    renderPanel({ isSelf: true, canRecordForOthers: false })

    // 017 D-h. `isSelf` is enough on its own — a gate consulted first would be the product deciding
    // that recording your own leave is an administrative act.
    expect(screen.getByTestId('Absence.Add')).toBeEnabled()
  })

  it('disables Add on somebody else’s page without staffing:absence, with the reason', () => {
    renderPanel({ isSelf: false, canRecordForOthers: false })

    const add = screen.getByTestId('Absence.Add')
    expect(add).toBeDisabled()
    // §31 — disabled with the reason on the anchor, never hidden.
    expect(add.parentElement).toHaveAttribute(
      'title',
      "You don't have permission to record somebody else's absence in this company.",
    )
  })

  it('enables Add on somebody else’s page with staffing:absence', () => {
    renderPanel({ isSelf: false, canRecordForOthers: true })

    expect(screen.getByTestId('Absence.Add')).toBeEnabled()
  })

  it('reads a half day as a half day, and says what it costs everywhere', () => {
    renderPanel({ isSelf: true, canRecordForOthers: false })

    // 017 D-d: `dayFraction` is a number, so a half day is a value rather than a second table.
    expect(screen.getByTestId('Absence.ab-1.Fraction')).toHaveTextContent('half day')
    expect(screen.getByTestId('Absence.ab-1')).toHaveTextContent('2026-08-29')
    // `member.html`'s line, verbatim.
    expect(screen.getByTestId('Absence.Basis')).toHaveTextContent(
      'Capacity for those days is zero everywhere — staffing, utilisation and any plan built on them.',
    )
  })

  it('posts to the person the panel is about, not to the caller', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(anAbsence() as never)
    renderPanel({ isSelf: false, canRecordForOthers: true })

    await userEvent.click(screen.getByTestId('Absence.Add'))
    await userEvent.type(screen.getByTestId('Absence.Form.StartsOn'), '2026-09-10')
    await userEvent.type(screen.getByTestId('Absence.Form.EndsOn'), '2026-09-10')
    await userEvent.clear(screen.getByTestId('Absence.Form.Fraction'))
    await userEvent.type(screen.getByTestId('Absence.Form.Fraction'), '0.5')
    await userEvent.click(screen.getByTestId('Absence.Form.Save'))

    await waitFor(() => expect(post).toHaveBeenCalled())
    /*
      `useCreateAbsence` takes its id as a HOOK argument, so the path is the panel's subject and not
      whoever is signed in. A call-site id would be one a call site could get wrong — and getting it
      wrong here means recording somebody else's leave against yourself.
    */
    expect(post.mock.calls[0][0]).toBe('/staffing/people/u-mira/absences')
    expect(post.mock.calls[0][1]).toEqual({
      startsOn: '2026-09-10',
      endsOn: '2026-09-10',
      kind: 'LEAVE',
      dayFraction: 0.5,
      note: null,
    })
  })
})
