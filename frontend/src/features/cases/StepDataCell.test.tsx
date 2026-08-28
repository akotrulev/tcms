import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StepDataView } from './StepDataCell'

/**
 * The read-only half, which is all that is left of this module.
 *
 * ~~The mode switch, the table editor and the "switching clears the others" arms.~~ → deleted with
 * `StepDataCell` in §003 Phase 2: there is no step-row surface to host a three-way switch, and the
 * three spellings are typed as text in the document now. What those tests proved about the
 * database rule they enforced is proved where the rule now lives — `CaseSchema.test.ts`'s R3 arm,
 * and the server's parser, which cannot produce two spellings from one step's text.
 *
 * `StepDataView` is unchanged and is rendered by two screens that were never part of that surface.
 */

describe('StepDataView — the read-only half both screens share', () => {
  it('renders nothing at all for a step with neither block argument', () => {
    const { container } = render(<StepDataView docString={null} dataTable={null} testId="X" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing for an empty table, rather than an empty grid', () => {
    const { container } = render(<StepDataView docString={null} dataTable={[]} testId="X" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders a doc string with its whitespace intact', () => {
    render(<StepDataView docString={'  indented\nline'} dataTable={null} testId="X" />)
    expect(screen.getByTestId('X.DocString')).toHaveTextContent('indented')
  })

  it('renders every row of a table', () => {
    render(
      <StepDataView
        docString={null}
        dataTable={[
          ['account', 'amount'],
          ['merchant', '-120.00'],
        ]}
        testId="X"
      />,
    )
    expect(screen.getAllByTestId('X.Table.Row')).toHaveLength(2)
    expect(screen.getByText('-120.00')).toBeInTheDocument()
  })

  it('renders both when a snapshot somehow carries both', () => {
    // A pinned snapshot is history and is not re-validated, so a version written by a build that
    // allowed both must not make this component choose one and hide the other.
    render(<StepDataView docString={'body'} dataTable={[['a']]} testId="X" />)
    expect(screen.getByTestId('X.DocString')).toBeInTheDocument()
    expect(screen.getByTestId('X.Table')).toBeInTheDocument()
  })
})
