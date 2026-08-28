import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { StepDataCell, StepDataView, type StepData } from './StepDataCell'

/**
 * The one control that edits a step's three data spellings (§255).
 *
 * The arms are the properties `ck_test_step_one_data` and the round trip depend on, and each is
 * invisible to `tsc`:
 *
 *   1. **The mode is derived from the values**, so a row restored from a buffer opens in the mode
 *      its content implies rather than in whatever a fourth field remembered.
 *   2. **Switching clears the others.** This is the client half of DEC-2 R3, and it is what makes
 *      the state the server refuses unreachable rather than merely refused.
 *   3. **A table never becomes `[]`.** `[]` is not a table — the server reads it as null and the
 *      contract refuses it — so "remove the last row" stops at one.
 *   4. **Nothing is trimmed.** Leading whitespace in a cell and in a doc string is content in the
 *      Gherkin spec, and trimming it is what would make a rendered `.feature` differ from the file
 *      it was parsed from.
 */

const EMPTY: StepData = { testData: '', docString: null, dataTable: null }

function renderCell(value: StepData = EMPTY) {
  const onChange = vi.fn<(next: StepData) => void>()
  render(
    <TestProviders client={createTestQueryClient()}>
      <StepDataCell value={value} onChange={onChange} index={0} testIdPrefix="CaseEditor" />
    </TestProviders>,
  )
  return { onChange }
}

type OnChange = ReturnType<typeof vi.fn<(next: StepData) => void>>

/** The last value the cell handed back — what the form would be holding. */
const lastCall = (onChange: OnChange): StepData => onChange.mock.calls.at(-1)?.[0] as StepData

describe('StepDataCell — the mode is derived, never stored', () => {
  it('opens in text mode for a row with neither block argument', () => {
    renderCell()
    expect(screen.getByTestId('CaseEditor.Steps.Field.TestData')).toBeInTheDocument()
    expect(screen.queryByTestId('CaseEditor.Steps.Field.DocString')).not.toBeInTheDocument()
  })

  it('opens in doc-string mode for a row whose doc string is EMPTY but present', () => {
    // The distinction the whole design turns on: `''` is "in this mode and not written yet", and
    // `null` is "not in this mode". A cell that read truthiness here would reopen an empty doc
    // string as a test-data field and silently change what the row means.
    renderCell({ testData: '', docString: '', dataTable: null })
    expect(screen.getByTestId('CaseEditor.Steps.Field.DocString')).toBeInTheDocument()
  })

  it('opens in table mode for a row carrying a table', () => {
    renderCell({ testData: '', docString: null, dataTable: [['a', 'b']] })
    expect(screen.getAllByTestId('CaseEditor.Steps.Data.Table.Cell')).toHaveLength(2)
  })
})

describe('StepDataCell — switching mode clears the others', () => {
  it('clears test data when the author switches to a doc string', async () => {
    const { onChange } = renderCell({ testData: '120.00 EUR', docString: null, dataTable: null })

    await userEvent.click(screen.getByTestId('CaseEditor.Steps.Data.Mode.DOC'))

    expect(lastCall(onChange)).toEqual({ testData: '', docString: '', dataTable: null })
  })

  it('clears the doc string when the author switches to a table', async () => {
    const { onChange } = renderCell({ testData: '', docString: 'a body', dataTable: null })

    await userEvent.click(screen.getByTestId('CaseEditor.Steps.Data.Mode.TABLE'))

    expect(lastCall(onChange)).toEqual({ testData: '', docString: null, dataTable: [['']] })
  })

  it('starts a table at one cell rather than at `[]`', async () => {
    const { onChange } = renderCell()

    await userEvent.click(screen.getByTestId('CaseEditor.Steps.Data.Mode.TABLE'))

    // `[]` is not a table: the contract refuses it and the server reads it as null, so a mode
    // whose initial value was `[]` would be a mode the save silently leaves.
    expect(lastCall(onChange).dataTable).toEqual([['']])
  })

  it('keeps the doc string when the author switches away and back', async () => {
    // Switching is a mode change, not a delete: the cell hands back what it was given, so the
    // author's text is still there if they change their mind before saving.
    const { onChange } = renderCell({ testData: '', docString: 'a body', dataTable: null })

    await userEvent.click(screen.getByTestId('CaseEditor.Steps.Data.Mode.TEXT'))
    expect(lastCall(onChange)).toEqual({ testData: '', docString: null, dataTable: null })
  })
})

describe('StepDataCell — the table editor', () => {
  it('adds a row of the same width', async () => {
    const { onChange } = renderCell({ testData: '', docString: null, dataTable: [['a', 'b']] })

    await userEvent.click(screen.getByTestId('CaseEditor.Steps.Data.Table.AddRow'))

    expect(lastCall(onChange).dataTable).toEqual([
      ['a', 'b'],
      ['', ''],
    ])
  })

  it('adds a column to every row', async () => {
    const { onChange } = renderCell({ testData: '', docString: null, dataTable: [['a'], ['b']] })

    await userEvent.click(screen.getByTestId('CaseEditor.Steps.Data.Table.AddColumn'))

    expect(lastCall(onChange).dataTable).toEqual([
      ['a', ''],
      ['b', ''],
    ])
  })

  it('refuses to remove the last row or the last column', () => {
    renderCell({ testData: '', docString: null, dataTable: [['a']] })

    expect(screen.getByTestId('CaseEditor.Steps.Data.Table.RemoveRow')).toBeDisabled()
    expect(screen.getByTestId('CaseEditor.Steps.Data.Table.RemoveColumn')).toBeDisabled()
  })

  it('pads a ragged row before writing into it, rather than leaving a hole', async () => {
    // The contract says rows may be ragged and the parser never produces one, so the hazard is a
    // client writing `undefined` into the middle of a row nothing has a spelling for.
    const { onChange } = renderCell({ testData: '', docString: null, dataTable: [['a', 'b'], ['c']] })

    await userEvent.type(screen.getAllByTestId('CaseEditor.Steps.Data.Table.Cell')[3], 'd')

    expect(lastCall(onChange).dataTable).toEqual([
      ['a', 'b'],
      ['c', 'd'],
    ])
  })

  it('does not trim what is typed into a cell', async () => {
    // One character, because the parent here is a spy: the cell is a controlled input and its
    // value never comes back, so a multi-character `type` would only ever report the last one.
    // A single space is the whole assertion anyway — leading whitespace in a `|`-cell is content.
    const { onChange } = renderCell({ testData: '', docString: null, dataTable: [['']] })

    await userEvent.type(screen.getAllByTestId('CaseEditor.Steps.Data.Table.Cell')[0], ' ')

    expect(lastCall(onChange).dataTable).toEqual([[' ']])
  })
})

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
