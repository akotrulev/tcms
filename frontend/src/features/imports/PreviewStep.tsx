import { Link as RouterLink } from 'react-router-dom';

import type { ImportPreview } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import {
  Badge,
  Banner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Button } from '../../components/ui';
import { formatCount, plural } from '../../lib/format';
import { TypeIcon } from '../tracker/TypeIcon';
import { DroppedFieldTable } from './DroppedFieldTable';

const TEST_ID = 'ImportPreview';

/**
 * `import-preview.html` — what comes across, what does not, and the promise that nothing has
 * happened yet.
 *
 * ## The green banner is the mockup's copy, verbatim, and it is a test on the server
 *
 * *"No data has been written. Leaving this page now changes nothing in {project} — not one issue,
 * status or label."* `ImportPreviewWritesNothingTest` snapshots every company-owned table before and
 * after a preview and fails on any difference, so this is a claim with a gate behind it rather than
 * a reassuring sentence. It is worth saying that out loud here, because the one way this screen
 * could start lying is a future preview that "helpfully" created the labels it found.
 *
 * ## The state the mockup does not draw
 *
 * `import-preview.html` has a Continue button and no blocked state. The contract has `blockers`,
 * computed by the preview *rather than discovered at minute four of the run*, and a preview that
 * drew Continue over a `KEY_COLLISION` would be offering a button whose only outcome is a `400`.
 * So a non-empty `blockers` **replaces** Continue with the blocker's own `detail` — and for
 * `KEY_COLLISION` the offending keys are rendered as `Identifier` chips, because *"three keys
 * collide"* is not something a person can act on and `PROJ-4812` is.
 *
 * Recorded in the plan as a disagreement with the mockup rather than resolved silently: the mockup
 * is the authority on what the screen shows, and this is a state it does not have a drawing for.
 *
 * ## The Notes column is derived, not the mockup's prose
 *
 * The mockup writes a bespoke sentence per type — *"acceptance criteria from the description"*,
 * *"parent link preserved"*. `PreviewTypeRow` carries no such field, and inventing one per English
 * type name would be this screen asserting a fact the server does not hold and getting it wrong on
 * any project that does not spell its types in English (`TypeIcon` makes the same argument about
 * colour). What the row *does* carry is `countsTowardCompletion`, which is the one note that
 * changes what a reader should expect, so that is the note.
 */
export function PreviewStep({
  projectId,
  projectName,
  preview,
  onBack,
  onContinue,
}: {
  projectId: string;
  projectName: string;
  preview: ImportPreview;
  onBack: () => void;
  onContinue: () => void;
}) {
  const droppedValues = preview.droppedFields.reduce((sum, field) => sum + field.valueCount, 0);
  const blocked = preview.blockers.length > 0;

  return (
    <div className="flex flex-col gap-4">
      <Banner tone="neutral" data-testid={`${TEST_ID}.NothingWritten`}>
        <span>
          <strong>No data has been written.</strong> Leaving this page now changes nothing in{' '}
          {projectName} — not one issue, status or label.
        </span>
      </Banner>

      {preview.blockers.map((blocker) => (
        <div role="alert" key={`${blocker.kind}-${blocker.detail}`}>
          <Banner tone="bad" data-testid={`${TEST_ID}.Blocker`}>
            <div className="min-w-0">
              <p className="m-0">{blocker.detail}</p>
              {blocker.keys && blocker.keys.length > 0 && (
                <div
                  className="mt-2 flex flex-wrap gap-1.5"
                  data-testid={`${TEST_ID}.Blocker.Keys`}
                >
                  {blocker.keys.map((key) => (
                    <Identifier
                      key={key}
                      className="rounded-md border border-line bg-paper px-1.5 py-0.5 text-[11.5px]"
                    >
                      {key}
                    </Identifier>
                  ))}
                </div>
              )}
            </div>
          </Banner>
        </div>
      ))}

      <div className="grid max-w-[1180px] gap-3.5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex flex-col gap-3.5">
          <Panel
            title="What comes across"
            testId={`${TEST_ID}.Types`}
            actions={<span className="text-[11.5px] text-ink-4">{plural(preview.totals.issues, 'issue')}</span>}
          >
            <Table data-testid={`${TEST_ID}.Types.Table`}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>Source type</TableHeaderCell>
                  <TableHeaderCell>Becomes</TableHeaderCell>
                  <TableHeaderCell align="right">Count</TableHeaderCell>
                  <TableHeaderCell>Notes</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {preview.types.map((row) => (
                  <TableRow key={row.sourceTypeName} data-testid={`${TEST_ID}.Types.Row`}>
                    <TableCell>{row.sourceTypeName}</TableCell>
                    <TableCell>
                      <span className="flex items-center gap-1.5">
                        <TypeIcon
                          type={{
                            name: row.targetTypeName,
                            level: row.targetLevel,
                            countsTowardCompletion: row.countsTowardCompletion,
                          }}
                        />
                        {row.targetTypeName}
                      </span>
                    </TableCell>
                    <TableCell align="right">
                      <Identifier variant="muted">{formatCount(row.count)}</Identifier>
                    </TableCell>
                    <TableCell>
                      <span className="text-ink-4">
                        {row.countsTowardCompletion
                          ? 'counts as work in its epic'
                          : 'excluded from completion'}
                      </span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <p className="m-0 mt-2.5 border-t border-line pt-2.5 text-[12px] text-ink-4">
              Also arriving: {plural(preview.totals.labels, 'label')} ·{' '}
              {plural(preview.totals.comments, 'comment')} ·{' '}
              {plural(preview.totals.historyEntries, 'history entry')} ·{' '}
              {plural(preview.totals.fixVersions, 'fix version')} → releases
            </p>
          </Panel>

          <Panel
            title="What will be dropped"
            testId={`${TEST_ID}.Dropped`}
            actions={
              <Badge tone="blocked" data-testid={`${TEST_ID}.Dropped.Count`}>
                {`${plural(preview.droppedFields.length, 'field type')} · ${plural(droppedValues, 'value')}`}
              </Badge>
            }
          >
            {/*
              The report renders this same component from the same shape (D-f). It is not a
              coincidence worth preserving by hand: the two lists are asserted equal element for
              element on the server, and two tables would let a word diverge in a `<td>` where no
              gate is looking.
            */}
            <DroppedFieldTable
              fields={preview.droppedFields}
              testId={`${TEST_ID}.Dropped`}
            />
            <p className="m-0 mt-2.5 border-t border-line pt-2.5 text-[12px] leading-[1.55] text-[color:var(--color-blocked)]">
              <strong>
                These values are not imported and cannot be recovered from this product later.
              </strong>{' '}
              Export them from Jira first if you need them. This same list is kept in the import
              report.
            </p>
          </Panel>
        </div>

        <div className="flex flex-col gap-3.5">
          <Panel title="Keys" testId={`${TEST_ID}.Keys`}>
            <div className="flex flex-col gap-2.5 text-[12.5px] leading-[1.6] text-ink-2">
              <p className="m-0">
                <Identifier>{`${preview.sourceKeyPrefix}-4812`}</Identifier> stays{' '}
                <Identifier>{`${preview.sourceKeyPrefix}-4812`}</Identifier>. Commit messages, PR
                titles and Slack history keep pointing at the right thing.
              </p>
              <p className="m-0 text-[11.5px] leading-[1.5] text-ink-4">
                {projectName}&rsquo;s own key stays{' '}
                <Identifier variant="muted">{preview.projectKeyPrefix}</Identifier>. Both prefixes
                resolve in this project afterwards.
              </p>
            </div>
          </Panel>

          <Panel title="Untouched" testId={`${TEST_ID}.Untouched`}>
            <ul className="m-0 flex list-none flex-col gap-[7px] p-0 text-[12.5px] text-ink-2">
              {[
                `Existing ${preview.projectKeyPrefix} issues`,
                'Test cases, suites, plans and runs',
                'Documents',
                'Time entries and approvals',
              ].map((untouched) => (
                <li key={untouched} className="flex items-start gap-2">
                  <span aria-hidden className="text-[color:var(--color-passed)]">
                    ✓
                  </span>
                  {untouched}
                </li>
              ))}
            </ul>
          </Panel>
        </div>
      </div>

      <div className="flex max-w-[1180px] items-center gap-2 border-t border-line pt-3">
        <Button size="sm" onClick={onBack} data-testid={`${TEST_ID}.Back`}>
          ← Back
        </Button>
        <Button size="sm" asChild>
          <RouterLink to={`/projects/${projectId}/imports`} data-testid={`${TEST_ID}.Cancel`}>
            Cancel
          </RouterLink>
        </Button>
        <span className="flex-1" />
        {/*
          Replaced rather than disabled. §31's disable-don't-hide is about a control somebody else
          could use — a permission they lack, a state they can leave — and it is right there. A
          blocker is neither: no press of this button can succeed for anybody, and a greyed control
          would invite a person to hunt for the permission that would ungrey it.
        */}
        {blocked ? (
          <span className="text-[12px] text-[color:var(--color-failed)]" data-testid={`${TEST_ID}.CannotContinue`}>
            This import cannot proceed.
          </span>
        ) : (
          <Button
            size="sm"
            variant="primary"
            onClick={onContinue}
            data-testid={`${TEST_ID}.Continue`}
          >
            Continue to status mapping →
          </Button>
        )}
      </div>
    </div>
  );
}
