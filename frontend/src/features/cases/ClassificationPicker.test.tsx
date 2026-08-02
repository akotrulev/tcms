import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ClassificationDimension } from '../../api/types'
import { ClassificationPicker } from './ClassificationPicker'

/**
 * The picker's contract with the form: one flat array of ids, edited by four independent controls.
 *
 * That shape is where the defect lives. Each control knows only its own dimension, so the naive
 * `onChange(myValues)` drops every other dimension's selection — and it does so invisibly, because
 * the control the user just used shows exactly what they expect. The assertions below therefore
 * always check the WHOLE array, never just the value that was clicked.
 */

const TEST_TYPE: ClassificationDimension = {
  id: 'd-type',
  key: 'test_type',
  name: 'Test Type',
  cardinality: 'SINGLE',
  hierarchical: false,
  locked: true,
  values: [
    { id: 'v-functional', parentId: null, name: 'Functional', path: 'Functional', color: null },
    { id: 'v-security', parentId: null, name: 'Security', path: 'Security', color: null },
  ],
}

const FEATURE: ClassificationDimension = {
  id: 'd-feature',
  key: 'feature',
  name: 'Feature',
  cardinality: 'MULTI',
  hierarchical: true,
  locked: false,
  values: [
    { id: 'v-payments', parentId: null, name: 'Payments', path: 'Payments', color: null },
    { id: 'v-refunds', parentId: 'v-payments', name: 'Refunds', path: 'Payments.Refunds', color: null },
  ],
}

const PHASE: ClassificationDimension = {
  id: 'd-phase',
  key: 'execution_phase',
  name: 'Execution Phase',
  cardinality: 'MULTI',
  hierarchical: false,
  locked: true,
  values: [
    { id: 'v-smoke', parentId: null, name: 'Smoke', path: 'Smoke', color: null },
    { id: 'v-regression', parentId: null, name: 'Regression', path: 'Regression', color: null },
  ],
}

function renderPicker(selected: string[], dimensions = [TEST_TYPE, FEATURE, PHASE]) {
  const onChange = vi.fn()
  render(
    <ClassificationPicker
      dimensions={dimensions}
      selected={selected}
      onChange={onChange}
      testIdPrefix="Editor"
    />,
  )
  return onChange
}

/** The single argument the component handed back, as a set-comparable sorted array. */
function lastSelection(onChange: ReturnType<typeof vi.fn>): string[] {
  const calls = onChange.mock.calls
  expect(calls.length).toBeGreaterThan(0)
  return [...(calls[calls.length - 1][0] as string[])].sort()
}

describe('ClassificationPicker', () => {
  it('renders a control for every dimension the project offers', () => {
    renderPicker([])

    expect(screen.getByTestId('Editor.Dimension.test_type')).toBeInTheDocument()
    expect(screen.getByTestId('Editor.Dimension.feature')).toBeInTheDocument()
    expect(screen.getByTestId('Editor.Dimension.execution_phase')).toBeInTheDocument()
  })

  it('ticks a MULTI value and keeps every other dimension selected', async () => {
    // The whole-array property. A control that returned only its own dimension's values would pass
    // an assertion on 'v-smoke' alone and silently clear the Test Type and the Feature.
    const onChange = renderPicker(['v-functional', 'v-refunds'])

    await userEvent.click(screen.getByTestId('Editor.Dimension.execution_phase.v-smoke'))

    expect(lastSelection(onChange)).toEqual(['v-functional', 'v-refunds', 'v-smoke'])
  })

  it('unticks a MULTI value and keeps the rest', async () => {
    const onChange = renderPicker(['v-functional', 'v-smoke', 'v-regression'])

    await userEvent.click(screen.getByTestId('Editor.Dimension.execution_phase.v-smoke'))

    expect(lastSelection(onChange)).toEqual(['v-functional', 'v-regression'])
  })

  it('REPLACES the value on a SINGLE dimension rather than adding a second', async () => {
    // Cardinality is enforced by the control's shape — a select takes one value — so choosing
    // Security must not leave Functional behind. If it did, the server would answer 400 on a
    // choice the UI presented as ordinary.
    const onChange = renderPicker(['v-functional', 'v-smoke'])

    await userEvent.click(screen.getByTestId('Editor.Dimension.test_type'))
    await userEvent.click(await screen.findByRole('option', { name: 'Security' }))

    expect(lastSelection(onChange)).toEqual(['v-security', 'v-smoke'])
  })

  it('offers a way back to unclassified on a SINGLE dimension', async () => {
    // `test_case` has no NOT NULL for any of this. A picker with no path to "unset" makes the
    // first accidental choice permanent.
    const onChange = renderPicker(['v-functional'])

    await userEvent.click(screen.getByTestId('Editor.Dimension.test_type'))
    await userEvent.click(await screen.findByRole('option', { name: 'None' }))

    expect(onChange.mock.calls[onChange.mock.calls.length - 1][0]).toEqual([])
  })

  it('lets a PARENT feature be selected, not only a leaf', async () => {
    // No leaf-only rule exists in the plan, the ERD, the roles spec or V1 — the editor asserted one
    // for two milestones and used it to justify keeping the whole field read-only.
    const onChange = renderPicker([])

    await userEvent.click(screen.getByTestId('Classification.Value.v-payments'))

    expect(lastSelection(onChange)).toEqual(['v-payments'])
  })

  it('shows a nested feature under its parent', () => {
    renderPicker([])

    expect(screen.getByTestId('Classification.Value.v-refunds')).toBeInTheDocument()
  })

  it('reflects what the case already carries', () => {
    renderPicker(['v-refunds', 'v-smoke'])

    expect(screen.getByTestId('Classification.Value.v-refunds')).toBeChecked()
    expect(screen.getByTestId('Editor.Dimension.execution_phase.v-smoke')).toBeChecked()
    expect(screen.getByTestId('Editor.Dimension.execution_phase.v-regression')).not.toBeChecked()
  })

  it('says an empty vocabulary is empty rather than rendering a dead control', () => {
    // The normal state of a freshly seeded project, which the contract says outright. An empty
    // select reads as a failed load, and hiding the dimension makes "no values yet"
    // indistinguishable from "this project has no Feature dimension".
    renderPicker([], [{ ...FEATURE, values: [] }])

    expect(screen.getByText(/this vocabulary is empty/i)).toBeInTheDocument()
  })
})
