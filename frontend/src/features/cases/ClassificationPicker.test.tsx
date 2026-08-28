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

function renderPicker(
  selected: string[],
  dimensions = [TEST_TYPE, FEATURE, PHASE],
  emptyNotice?: (empty: ClassificationDimension[]) => React.ReactNode,
) {
  const onChange = vi.fn()
  render(
    <ClassificationPicker
      dimensions={dimensions}
      selected={selected}
      onChange={onChange}
      testIdPrefix="Editor"
      emptyNotice={emptyNotice}
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

  /*
    ~~An empty vocabulary says so, per dimension.~~ → **003 Phase 1, finding 3.** A freshly
    provisioned project has four dimensions and no values in any of them, so the old treatment drew
    four labelled controls nobody could use and four copies of one sentence, in a 320px rail. An
    empty dimension now renders nothing, and the page says the one thing worth saying.
  */
  it('renders NO control for an empty vocabulary', () => {
    renderPicker([], [{ ...FEATURE, values: [] }])
    expect(screen.queryByTestId('Editor.Dimension.feature')).not.toBeInTheDocument()
  })

  it('renders nothing at all when EVERY dimension is empty, and hands them to the notice together', () => {
    const empties: ClassificationDimension[] = []
    renderPicker(
      [],
      [
        { ...TEST_TYPE, values: [] },
        { ...FEATURE, values: [] },
        { ...PHASE, values: [] },
      ],
      (empty) => {
        empties.push(...empty)
        return <span>Nothing configured, nothing shown.</span>
      },
    )

    expect(screen.queryByTestId('Editor.Dimension.test_type')).not.toBeInTheDocument()
    expect(screen.queryByTestId('Editor.Dimension.feature')).not.toBeInTheDocument()
    expect(screen.queryByTestId('Editor.Dimension.execution_phase')).not.toBeInTheDocument()
    // ONE notice for all three, which is the whole point of the slot taking the list.
    expect(screen.getAllByText('Nothing configured, nothing shown.')).toHaveLength(1)
    expect(empties.map((dimension) => dimension.key)).toEqual([
      'test_type',
      'feature',
      'execution_phase',
    ])
  })

  it('renders the dimensions that have values and names only the ones that do not', () => {
    renderPicker(
      [],
      [TEST_TYPE, { ...FEATURE, values: [] }, PHASE],
      (empty) => <span>{empty.map((dimension) => dimension.name).join(', ')} has no values yet.</span>,
    )

    expect(screen.getByTestId('Editor.Dimension.test_type')).toBeInTheDocument()
    expect(screen.getByTestId('Editor.Dimension.execution_phase')).toBeInTheDocument()
    expect(screen.getByText('Feature has no values yet.')).toBeInTheDocument()
  })

  it('renders no notice at all when every dimension has values', () => {
    renderPicker([], [TEST_TYPE, PHASE], () => <span>should not appear</span>)
    expect(screen.queryByTestId('Editor.Classification.EmptyNotice')).not.toBeInTheDocument()
  })
})
