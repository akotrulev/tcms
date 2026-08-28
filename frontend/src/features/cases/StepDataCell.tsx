import {
  Box,
  Button,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import RemoveIcon from '@mui/icons-material/Remove';
import { ScrollX } from '../../components/ScrollX';

/**
 * The one component that renders and edits a step's data, in all three of its spellings (§255).
 *
 * ## Why one component and not three fields side by side
 *
 * `test_step` carries `test_data`, `docstring` and `datatable`, and `ck_test_step_one_data` says at
 * most one of them is non-null. Three controls on a row would let an author fill two and discover
 * the rule at the server — and the sentence they would get back names a step number, not a field,
 * because the server collects everything under `steps`. A mode switch makes the constraint the
 * shape of the control: the state where two are set is not reachable, so the rule needs no message.
 *
 * ## The mode is DERIVED, never stored
 *
 * `docString === null` means "not in doc-string mode"; `''` means "in it, and empty". Same for
 * `dataTable`. A fourth field holding the mode beside the content would be a second answer to one
 * question, and the two disagree the first time a buffered draft is restored from a payload that
 * carries only the content — the draft would come back in text mode with a doc string it does not
 * render. `caseSchema`'s `StepSchema.docString` states the same rule from the form's end.
 *
 * ## Switching clears the others, and that is the client half of DEC-2 R3
 *
 * It is also why R3's refinement in `caseSchema` is unreachable through this screen and kept
 * anyway: a restored draft can carry a shape this control cannot produce.
 */

/** A step's data, as the form holds it — see the module header for what the two nulls mean. */
export type StepData = {
  testData: string;
  docString: string | null;
  dataTable: string[][] | null;
};

type Mode = 'TEXT' | 'DOC' | 'TABLE';

const MODE_LABELS: Record<Mode, string> = {
  TEXT: 'Test data',
  DOC: 'Doc string',
  TABLE: 'Table',
};

/**
 * Which spelling this step is using.
 *
 * Doc string is asked about before table for no reason other than determinism: a row carrying both
 * is a shape the control cannot produce and the schema refuses, so the tie-break never runs on a
 * value an author made — but a reader of a restored draft needs the answer to be the same twice.
 */
function modeOf(value: StepData): Mode {
  if (value.docString !== null) return 'DOC';
  if (value.dataTable !== null) return 'TABLE';
  return 'TEXT';
}

/** A one-cell, one-row table — what "switch to table" starts from, since `[]` is not a table. */
const EMPTY_TABLE: string[][] = [['']];

export function StepDataCell({
  value,
  onChange,
  index,
  testIdPrefix,
  error,
}: {
  value: StepData;
  onChange: (next: StepData) => void;
  /** The row's position in the editor's list, for the field labels a screen reader reads. */
  index: number;
  testIdPrefix: string;
  /** The schema's R3 sentence, when a restored draft carried more than one spelling. */
  error?: string;
}) {
  const mode = modeOf(value);

  const switchTo = (next: Mode) => {
    // Every branch spells all three fields, rather than spreading `value` and overriding one. The
    // spread is how the field the author is switching AWAY from survives the switch, which is the
    // one thing this control exists to make impossible.
    if (next === 'TEXT') onChange({ testData: value.testData, docString: null, dataTable: null });
    if (next === 'DOC') onChange({ testData: '', docString: value.docString ?? '', dataTable: null });
    if (next === 'TABLE') {
      onChange({ testData: '', docString: null, dataTable: value.dataTable ?? EMPTY_TABLE });
    }
  };

  const table = value.dataTable ?? EMPTY_TABLE;
  const columns = Math.max(1, ...table.map((row) => row.length));

  const editCell = (rowIndex: number, columnIndex: number, cell: string) =>
    onChange({
      ...value,
      dataTable: table.map((row, r) =>
        r === rowIndex
          ? // Padded to the full width before writing: rows may legitimately be ragged (the
            // contract says so), and writing into a cell past the end of a short row would
            // otherwise produce a `undefined` hole no renderer has a spelling for.
            Array.from({ length: columns }, (_cell, c) => (c === columnIndex ? cell : (row[c] ?? '')))
          : row,
      ),
    });

  return (
    <Stack sx={{ gap: 1 }} data-testid={`${testIdPrefix}.Steps.Data`}>
      <Stack direction="row" sx={{ gap: 1, alignItems: 'center' }}>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={mode}
          onChange={(_event, next: Mode | null) => {
            // MUI sends null when the selected button is pressed again. Re-selecting the mode you
            // are in is a no-op, not a fourth mode.
            if (next) switchTo(next);
          }}
          aria-label={`Data for step ${index + 1}`}
        >
          {(['TEXT', 'DOC', 'TABLE'] as const).map((option) => (
            <ToggleButton
              key={option}
              value={option}
              data-testid={`${testIdPrefix}.Steps.Data.Mode.${option}`}
            >
              {MODE_LABELS[option]}
            </ToggleButton>
          ))}
        </ToggleButtonGroup>
      </Stack>

      {mode === 'TEXT' && (
        <TextField
          label="Test data"
          fullWidth
          multiline
          value={value.testData}
          onChange={(event) => onChange({ ...value, testData: event.target.value })}
          error={!!error}
          helperText={error}
          slotProps={{
            htmlInput: { 'data-testid': `${testIdPrefix}.Steps.Field.TestData` },
            formHelperText: { role: error ? 'alert' : undefined },
          }}
        />
      )}

      {mode === 'DOC' && (
        <TextField
          label="Doc string"
          fullWidth
          multiline
          minRows={3}
          value={value.docString ?? ''}
          onChange={(event) => onChange({ ...value, docString: event.target.value })}
          error={!!error}
          helperText={error ?? 'Rendered as a """ block. Line breaks and indentation are kept exactly.'}
          slotProps={{
            htmlInput: {
              'data-testid': `${testIdPrefix}.Steps.Field.DocString`,
              style: { fontFamily: 'var(--font-mono)', fontSize: '0.8125rem' },
            },
            formHelperText: { role: error ? 'alert' : undefined },
          }}
        />
      )}

      {mode === 'TABLE' && (
        <Stack sx={{ gap: 1 }}>
          <ScrollX>
            <Table size="small" data-testid={`${testIdPrefix}.Steps.Data.Table`}>
              <TableBody>
                {table.map((row, rowIndex) => (
                  // Keyed by position, which is the one place in this app that is right: a table
                  // row here has no identity of its own — it IS its position — and the cells are
                  // controlled inputs whose value comes from the form on every render.
                  <TableRow key={rowIndex}>
                    {Array.from({ length: columns }, (_cell, columnIndex) => (
                      <TableCell key={columnIndex} sx={{ p: 0.5 }}>
                        <TextField
                          size="small"
                          fullWidth
                          value={row[columnIndex] ?? ''}
                          onChange={(event) => editCell(rowIndex, columnIndex, event.target.value)}
                          aria-label={`Row ${rowIndex + 1} column ${columnIndex + 1}`}
                          slotProps={{
                            htmlInput: {
                              'data-testid': `${testIdPrefix}.Steps.Data.Table.Cell`,
                              style: { fontFamily: 'var(--font-mono)', fontSize: '0.8125rem' },
                            },
                          }}
                        />
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </ScrollX>
          <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
            <Button
              size="small"
              startIcon={<AddIcon />}
              onClick={() =>
                onChange({ ...value, dataTable: [...table, Array.from({ length: columns }, () => '')] })
              }
              data-testid={`${testIdPrefix}.Steps.Data.Table.AddRow`}
            >
              Row
            </Button>
            <Tooltip title="A table needs at least one row. Switch to Test data to remove it.">
              <Box component="span">
                <IconButton
                  size="small"
                  disabled={table.length <= 1}
                  onClick={() => onChange({ ...value, dataTable: table.slice(0, -1) })}
                  aria-label="Remove the last row"
                  data-testid={`${testIdPrefix}.Steps.Data.Table.RemoveRow`}
                >
                  <RemoveIcon fontSize="small" />
                </IconButton>
              </Box>
            </Tooltip>
            <Button
              size="small"
              startIcon={<AddIcon />}
              onClick={() =>
                onChange({
                  ...value,
                  dataTable: table.map((row) =>
                    Array.from({ length: columns + 1 }, (_cell, c) => row[c] ?? ''),
                  ),
                })
              }
              data-testid={`${testIdPrefix}.Steps.Data.Table.AddColumn`}
            >
              Column
            </Button>
            <Tooltip title="A table needs at least one column.">
              <Box component="span">
                <IconButton
                  size="small"
                  disabled={columns <= 1}
                  onClick={() =>
                    onChange({ ...value, dataTable: table.map((row) => row.slice(0, columns - 1)) })
                  }
                  aria-label="Remove the last column"
                  data-testid={`${testIdPrefix}.Steps.Data.Table.RemoveColumn`}
                >
                  <RemoveIcon fontSize="small" />
                </IconButton>
              </Box>
            </Tooltip>
          </Stack>
          {error && (
            <Typography variant="caption" role="alert" sx={{ color: 'error.main' }}>
              {error}
            </Typography>
          )}
        </Stack>
      )}
    </Stack>
  );
}

/**
 * The same two block arguments, read-only — the case detail's step table and the execution
 * checklist both render this.
 *
 * **One renderer, for the shared layer's usual reason**, and a sharper one here than usual: the two
 * screens show the same step to the person who wrote it and the person running it, and a table
 * whose cells wrap differently between them is a tester and an author disagreeing about what the
 * data says. It lives beside the editor rather than in `components/` because it renders exactly the
 * thing the cell above edits, and the pair has to move together.
 */
export function StepDataView({
  docString,
  dataTable,
  testId,
}: {
  docString: string | null | undefined;
  dataTable: string[][] | null | undefined;
  testId: string;
}) {
  // Truthiness, not a null check, and on the read side only: the server trims a blank doc string to
  // null, so `''` here is a value nothing can have produced — and an empty bordered box under a
  // step reads as content that failed to render.
  const hasTable = !!dataTable && dataTable.length > 0;
  if (!docString && !hasTable) return null;

  return (
    <Stack sx={{ gap: 1, mt: 0.5 }}>
      {docString && (
        <Box
          component="pre"
          data-testid={`${testId}.DocString`}
          sx={{
            m: 0,
            p: 1.5,
            // Whitespace is content in a `"""` block, so it is preserved and the box scrolls
            // rather than wrapping — a re-wrapped doc string is a different payload.
            whiteSpace: 'pre',
            overflowX: 'auto',
            fontFamily: 'var(--font-mono)',
            fontSize: '0.8125rem',
            lineHeight: 1.7,
            bgcolor: 'background.default',
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 1,
          }}
        >
          {docString}
        </Box>
      )}
      {hasTable && dataTable && (
        <ScrollX>
          <Table size="small" data-testid={`${testId}.Table`}>
            <TableBody>
              {dataTable.map((row, rowIndex) => (
                <TableRow key={rowIndex} data-testid={`${testId}.Table.Row`}>
                  {row.map((cell, columnIndex) => (
                    <TableCell
                      key={columnIndex}
                      sx={{ fontFamily: 'var(--font-mono)', fontSize: '0.8125rem' }}
                    >
                      {cell}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </ScrollX>
      )}
    </Stack>
  );
}
