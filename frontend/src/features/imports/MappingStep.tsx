import { Link as RouterLink } from 'react-router-dom';

import type { ImportOptions, ImportPreview, ProjectWorkflow } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
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
import { Button, Checkbox } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { formatCount, plural } from '../../lib/format';
import { issueStatusKindTones } from '../../lib/labels';
import { mappingOrigin, unmappedRows } from './wizardState';

const TEST_ID = 'ImportMapping';

/**
 * `import-mapping.html` — every source status onto one of this project's own, and the two options.
 *
 * ## Confirm is disabled with the reason on it, and the reason is the mockup's own sentence
 *
 * *"Confirm stays disabled until every status has a target — an unmapped status would silently
 * become the default and put 87 issues in the wrong column."* That sentence is the whole
 * justification for the control being disabled rather than absent (§31, disable-don't-hide): a
 * person who cannot see the button cannot learn what would enable it, and the count of issues at
 * stake is what makes the refusal read as care rather than as an obstacle.
 *
 * **The server does not trust this screen**, and that is deliberate rather than redundant: `POST
 * /imports` answers `400` for an unmapped status. This is the check; the endpoint is the backstop.
 *
 * ## The trip to the workflow screen is a promise about state
 *
 * *"Add a status here if none of yours fits — the mapping picks it up on return."* The wizard
 * carries its whole state, connection included, through the history entry and restores it, and it
 * invalidates the workflow query so a status added over there appears in every picker here. A full
 * page load loses it — that is the cost of never storing the token, and the connect step's warning
 * is why it is acceptable rather than a bug.
 *
 * ## Two options, and each defaults the way its decision says
 *
 * `labelPerSprint` is **off**: a project with sixty sprints becomes sixty labels nobody chose, and
 * the label picker is a project-wide vocabulary (D-h). `Sprint` stays in the dropped list either
 * way, which the caption says, because a reader has to see both what was lost and what survived it.
 * `preserveBacklogOrder` is **on** for Jira (D-i), whose board ranking somebody maintained; off, the
 * report says the order is insertion order rather than letting it read as imported.
 */
export function MappingStep({
  projectId,
  preview,
  workflow,
  mapping,
  options,
  confirming,
  onMap,
  onOptions,
  onBack,
  onConfirm,
  onEditWorkflow,
}: {
  projectId: string;
  preview: ImportPreview;
  workflow: ProjectWorkflow;
  mapping: Record<string, string>;
  options: ImportOptions;
  confirming: boolean;
  onMap: (sourceStatusName: string, targetStatusId: string) => void;
  onOptions: (next: ImportOptions) => void;
  onBack: () => void;
  onConfirm: () => void;
  onEditWorkflow: () => void;
}) {
  const unmapped = unmappedRows(preview, mapping);
  const issuesAtRisk = unmapped.reduce((sum, row) => sum + row.count, 0);

  /*
    The mockup's own sentence, with its two numbers filled in. It is used twice — as the banner and
    as the disabled control's `title` — from one expression, because the two saying slightly
    different things is how a reader ends up believing the more forgiving one.
  */
  const reason =
    unmapped.length === 0
      ? undefined
      : `An unmapped status would silently become the default and put ${plural(issuesAtRisk, 'issue')} in the wrong column.`;

  const perStatusCount = new Map<string, number>();
  for (const row of preview.statuses) {
    const target = mapping[row.sourceStatusName];
    if (target) perStatusCount.set(target, (perStatusCount.get(target) ?? 0) + row.count);
  }

  return (
    <div className="flex flex-col gap-4">
      {unmapped.length > 0 && (
        <div role="alert">
          <Banner tone="bad" data-testid={`${TEST_ID}.Unmapped`}>
            <span>
              <strong>
                {unmapped.length === 1
                  ? `One source status is not mapped: “${unmapped[0]?.sourceStatusName}”.`
                  : `${plural(unmapped.length, 'source status')} are not mapped.`}
              </strong>{' '}
              Confirm stays disabled until every status has a target — {reason?.toLowerCase()}
            </span>
          </Banner>
        </div>
      )}

      <div className="grid max-w-[1180px] gap-3.5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex flex-col gap-3.5">
          <Panel
            title="Source status → this project’s workflow"
            testId={`${TEST_ID}.Statuses`}
            actions={
              <span className="text-[11.5px] text-ink-4">
                {`${plural(preview.statuses.length, 'status')} · ${formatCount(preview.statuses.length - unmapped.length)} mapped`}
              </span>
            }
          >
            <Table data-testid={`${TEST_ID}.Table`}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>Source status</TableHeaderCell>
                  <TableHeaderCell align="right">Issues</TableHeaderCell>
                  <TableHeaderCell>Becomes</TableHeaderCell>
                  <TableHeaderCell>How</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {preview.statuses.map((row) => {
                  const target = mapping[row.sourceStatusName] ?? '';
                  const origin = mappingOrigin(row, target);
                  const selectId = `${TEST_ID}-target-${row.sourceStatusName.replace(/\s+/g, '-')}`;
                  return (
                    <TableRow key={row.sourceStatusName} data-testid={`${TEST_ID}.Row`}>
                      <TableCell>
                        <Identifier>{row.sourceStatusName}</Identifier>
                      </TableCell>
                      <TableCell align="right">
                        <Identifier variant="muted">{formatCount(row.count)}</Identifier>
                      </TableCell>
                      <TableCell>
                        <label className="sr-only" htmlFor={selectId}>
                          {`What “${row.sourceStatusName}” becomes`}
                        </label>
                        <NativeSelect
                          id={selectId}
                          value={target}
                          className="min-w-[170px]"
                          data-testid={`${TEST_ID}.Row.Target`}
                          onChange={(event) => onMap(row.sourceStatusName, event.target.value)}
                        >
                          <option value="">— pick a status —</option>
                          {workflow.statuses.map((status) => (
                            <option key={status.id} value={status.id}>
                              {status.name}
                            </option>
                          ))}
                        </NativeSelect>
                      </TableCell>
                      <TableCell>
                        {origin === 'unmapped' && (
                          <Badge tone="failed" data-testid={`${TEST_ID}.Row.Unmapped`}>
                            unmapped
                          </Badge>
                        )}
                        {origin === 'matched' && <Badge tone="steel">name matched</Badge>}
                        {origin === 'chosen' && <Badge tone="plain">you chose this</Badge>}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </Panel>

          <Panel title="Two choices this import makes for you" testId={`${TEST_ID}.Options`}>
            <div className="flex flex-col gap-3">
              <label className="flex cursor-pointer items-start gap-2.5">
                <Checkbox
                  className="mt-0.5"
                  checked={options.labelPerSprint}
                  aria-label="Keep sprint membership as a label"
                  data-testid={`${TEST_ID}.LabelPerSprint`}
                  onCheckedChange={(next: boolean) =>
                    onOptions({ ...options, labelPerSprint: next })
                  }
                />
                <span className="text-[12.5px] leading-[1.55] text-ink-2">
                  <strong className="text-ink">Keep sprint membership as a label.</strong> One label
                  per distinct sprint name — {plural(preview.totals.sprintValues, 'value')} across
                  this project. Off by default: sixty sprints become sixty labels nobody chose, and
                  the label list is the whole project&rsquo;s vocabulary.{' '}
                  <em>Sprint stays in the dropped list either way</em>, with its reason adjusted to
                  say what was kept in its place.
                </span>
              </label>

              <label className="flex cursor-pointer items-start gap-2.5">
                <Checkbox
                  className="mt-0.5"
                  checked={options.preserveBacklogOrder}
                  aria-label="Keep the source backlog order"
                  data-testid={`${TEST_ID}.PreserveBacklogOrder`}
                  onCheckedChange={(next: boolean) =>
                    onOptions({ ...options, preserveBacklogOrder: next })
                  }
                />
                <span className="text-[12.5px] leading-[1.55] text-ink-2">
                  <strong className="text-ink">Keep the source backlog order.</strong> Without it
                  the issues arrive in insertion order — an order nobody chose — and the report says
                  so rather than letting it read as imported.
                </span>
              </label>
            </div>
          </Panel>
        </div>

        <div className="flex flex-col gap-3.5">
          <Panel title="Your workflow" testId={`${TEST_ID}.Workflow`}>
            <div className="flex flex-col gap-[7px]">
              {workflow.statuses.map((status) => (
                <div key={status.id} className="flex items-center gap-2">
                  <Badge tone={issueStatusKindTones[status.kind]}>{status.name}</Badge>
                  <span className="flex-1" />
                  <Identifier variant="muted" className="text-[11px]">
                    {formatCount(perStatusCount.get(status.id) ?? 0)}
                  </Identifier>
                </div>
              ))}
              <Button
                size="sm"
                className="mt-1.5"
                onClick={onEditWorkflow}
                data-testid={`${TEST_ID}.EditWorkflow`}
              >
                Edit the workflow first
              </Button>
            </div>
            <p className="m-0 mt-2.5 border-t border-line pt-2.5 text-[11.5px] leading-[1.55] text-ink-4">
              Add a status there if none of yours fits — the mapping picks it up on return. Your
              connection is kept for the trip, but not across a page reload.
            </p>
          </Panel>
        </div>
      </div>

      <div className="flex max-w-[1180px] flex-wrap items-center gap-2 border-t border-line pt-3">
        <Button size="sm" onClick={onBack} data-testid={`${TEST_ID}.Back`}>
          ← Back to preview
        </Button>
        <Button size="sm" asChild>
          <RouterLink to={`/projects/${projectId}/imports`} data-testid={`${TEST_ID}.Cancel`}>
            Cancel
          </RouterLink>
        </Button>
        <span className="flex-1" />
        {unmapped.length > 0 && (
          <span
            className="text-[12px] text-[color:var(--color-failed)]"
            data-testid={`${TEST_ID}.UnmappedCount`}
          >
            {`${formatCount(unmapped.length)} unmapped`}
          </span>
        )}
        <PermissionGate projectId={projectId} permission="issue:import">
          {({ allowed, reason: refusal }) => (
            <Button
              size="sm"
              variant="primary"
              disabled={!allowed || unmapped.length > 0 || confirming}
              /*
                The reason on the control itself, which is what makes disabling it honest. A
                disabled button with no title is a dead end; this one says what would make it live.
              */
              title={allowed ? reason : refusal}
              onClick={onConfirm}
              data-testid={`${TEST_ID}.Confirm`}
            >
              {confirming ? 'Starting…' : 'Confirm import'}
            </Button>
          )}
        </PermissionGate>
      </div>
    </div>
  );
}
