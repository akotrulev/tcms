import { Table, TableBody, TableCell, TableRow } from '../../components/kit';
import { ScrollX } from '../../components/ScrollX';

/**
 * A step's two block arguments, rendered — and, until §003, edited.
 *
 * ## ~~The one component that renders and edits a step's data, in all three of its spellings.~~
 *
 * → **The editor half was deleted in §003 Phase 2**, with the step-row surface it belonged to.
 * `StepDataCell` was a three-way mode switch on a row of a step list, and under D1 there is no
 * step list: the three spellings are typed **as text** in the document — a doc string is `"""`, a
 * table is `|…|`, and `testData` is the `# data:` comment the renderer emits — and the server's
 * parser is what turns them into the three columns.
 *
 * The reasoning the switch existed for is kept here rather than deleted with it, because the
 * constraint it enforced has not gone anywhere. `test_step` carries `test_data`, `docstring` and
 * `datatable`, and `V69`'s `ck_test_step_one_data` says at most one of them is non-null; the mode
 * switch made the two-at-once state unreachable, and what makes it unreachable now is that
 * `GherkinScenarios` cannot produce it from one step's text. `caseSchema`'s R3 refinement stays
 * for the same reason it always had — a restored draft is unvalidated and may carry a shape no
 * control could produce.
 *
 * `StepDataView` below is what survived, and it was always the half two other screens used. The
 * `StepData` shape went with the editor — nothing binds a step's three columns as one value any
 * more, because nothing edits them as one control.
 */

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
    <div className="mt-1 flex flex-col gap-2">
      {docString && (
        // Whitespace is content in a `"""` block, so `whitespace-pre` preserves it and the box
        // scrolls rather than wrapping — a re-wrapped doc string is a different payload.
        <pre
          data-testid={`${testId}.DocString`}
          className="m-0 overflow-x-auto rounded border border-white/8 bg-ground p-3 font-mono text-[13px] leading-[1.7] whitespace-pre text-ink"
        >
          {docString}
        </pre>
      )}
      {hasTable && dataTable && (
        <ScrollX>
          <Table data-testid={`${testId}.Table`}>
            <TableBody>
              {dataTable.map((row, rowIndex) => (
                <TableRow key={rowIndex} data-testid={`${testId}.Table.Row`}>
                  {row.map((cell, columnIndex) => (
                    <TableCell key={columnIndex} className="font-mono text-[13px]">
                      {cell}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </ScrollX>
      )}
    </div>
  );
}
