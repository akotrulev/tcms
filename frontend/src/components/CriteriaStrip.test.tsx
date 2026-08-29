import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Criterion } from '../api/types'
import { CriteriaStrip } from './CriteriaStrip'

/**
 * The strip replaced a chip that could say only "All met" or "N unmet" — and on the plan list,
 * where `GET /plans` evaluates nothing, the chip said **"All met"** about five gates nobody had
 * measured. That is the sentence these tests exist to make unrepeatable.
 *
 * The summary text is asserted rather than the marks, deliberately: the marks are `aria-hidden`
 * and 4px tall, so the summary is what a reader — and a screen reader — actually gets. If the
 * count is right the strip is right; if the count is wrong no colour saves it.
 */

const criterion = (met: boolean | null | undefined, label = 'Pass rate'): Criterion => ({
  metric: 'pass_rate',
  op: '>=',
  value: 95,
  label,
  met,
})

const renderStrip = (criteria: Criterion[]) =>
  render(
      <CriteriaStrip criteria={criteria} testId="Test.Criteria" />,
  )

describe('CriteriaStrip', () => {
  /** The defect the component was written to prevent, stated as the sentence it must not say. */
  it('never claims criteria are met when nothing evaluated them', () => {
    renderStrip([criterion(null), criterion(null), criterion(undefined)])

    expect(screen.getByTestId('Test.Criteria.Summary')).toHaveTextContent('3 not evaluated')
    expect(screen.queryByText(/met/)).not.toBeInTheDocument()
  })

  it('reads "n of m met" when every criterion was evaluated', () => {
    renderStrip([criterion(true), criterion(true), criterion(false)])

    expect(screen.getByTestId('Test.Criteria.Summary')).toHaveTextContent('2 of 3 met')
  })

  /*
    The mixed case is the one a naive summary gets wrong: with three evaluated (two met) and two
    unevaluated, "2 of 5 met" understates the plan — three of its gates are green out of the
    three anybody looked at. The denominator is what was measured; the remainder is stated
    separately rather than folded into a failure.
  */
  it('denominates on what was evaluated and states the rest separately', () => {
    renderStrip([criterion(true), criterion(true), criterion(false), criterion(null), criterion(null)])

    expect(screen.getByTestId('Test.Criteria.Summary')).toHaveTextContent(
      '2 of 3 met · 2 not evaluated',
    )
  })

  it('draws one mark per criterion, whatever their metrics', () => {
    // Two criteria sharing a metric — a plan may gate on pass_rate per configuration, which is
    // why the component keys on metric+index and not on the metric alone. Keyed on the metric,
    // React would render one mark here and the strip would undercount the plan's gates.
    renderStrip([criterion(true, 'Pass rate on critical'), criterion(false, 'Pass rate overall')])

    expect(screen.getAllByTestId('Test.Criteria.Mark')).toHaveLength(2)
  })

  it('says a plan has no criteria rather than rendering an empty strip', () => {
    renderStrip([])

    expect(screen.getByTestId('Test.Criteria.Empty')).toHaveTextContent('None set')
    expect(screen.queryByTestId('Test.Criteria.Mark')).not.toBeInTheDocument()
  })
})
