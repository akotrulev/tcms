import { Link as RouterLink, useParams } from 'react-router-dom';

import { useImportRuns, useProject } from '../../api/queries';
import { DataRegion, EmptyState } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { SettingsSubnav } from '../settings/SettingsSubnav';
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
import { Button } from '../../components/ui';
import { formatDate, plural } from '../../lib/format';
import { importProviderLabels, importRunStatusLabels, importRunStatusTones } from '../../lib/labels';

const TEST_ID = 'Imports';

/**
 * `settings-import.html` — every migration this project has taken in, and the way to start another.
 *
 * ## The two sentences on this screen are the screen
 *
 * *"Reports are kept indefinitely"* beside the panel heading, and *"nothing here keeps syncing with
 * Jira afterwards"* at the foot. They are the module's whole proposition in the two places a reader
 * will actually meet it: the first is what makes the report worth opening a year later, and the
 * second is the misunderstanding this product is one careless screen away from — the tracker
 * adapters 004 shipped ARE an integration, this is not one, and a person who has used both would
 * have no way to tell them apart from a table of past runs.
 *
 * ## The button says *Start an import*, and it used to say *Import from Jira*
 *
 * D-k kept the mockup's *Import from Jira* deliberately, right up until Azure DevOps landed: a
 * button naming a product this instance could not yet import from would have been a promise, and
 * one naming two would have been a lie about which. T5 landed the ADO source and this screen is the
 * first one to exist since, so the rename lands here rather than in T5, which touched no screen.
 *
 * ## The empty state is not an empty table
 *
 * A project that has imported nothing is the state most readers arrive in, and a table with a
 * header row and no rows tells them nothing about what the screen is for. It gets the start button
 * instead — gated like the header's, because there is no read-only thing to do here.
 */
export function ImportsListPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const runs = useImportRuns(projectId);

  const startButton = (size: 'sm' | undefined) => (
    <PermissionGate projectId={projectId} permission="issue:import">
      {({ allowed, reason }) => (
        <Button
          variant="primary"
          size={size}
          asChild={allowed}
          disabled={!allowed}
          title={allowed ? undefined : reason}
          data-testid={`${TEST_ID}.Start${size === 'sm' ? '.Empty' : ''}`}
        >
          {allowed ? (
            <RouterLink to={`/projects/${projectId}/imports/new`}>Start an import</RouterLink>
          ) : (
            <span>Start an import</span>
          )}
        </Button>
      )}
    </PermissionGate>
  );

  return (
    <div>
      <PageHeader
        views={<SettingsSubnav projectId={projectId} />}
        testId={`${TEST_ID}.Header`}
        title="Imports"
        documentTitle={project.data ? `Imports · ${project.data.name}` : 'Imports'}
        subtitle="Bring a Jira or Azure DevOps project in, keeping its issue keys."
        actions={startButton(undefined)}
      />

      <div className="max-w-[1180px]">
        <Panel
          title="Past imports"
          testId={`${TEST_ID}.Panel`}
          actions={<span className="text-[11.5px] text-ink-4">reports are kept indefinitely</span>}
          dense
        >
          <DataRegion
            testId={`${TEST_ID}.Region`}
            state={{ ...runs, refetch: () => void runs.refetch() }}
          >
            {(rows) =>
              rows.length === 0 ? (
                <EmptyState
                  testId={`${TEST_ID}.Empty`}
                  title="No imports yet"
                  body="Nothing has been brought into this project from another tracker. An import keeps the source's issue keys, and its report is kept for as long as the project is."
                  action={startButton('sm')}
                />
              ) : (
                <Table data-testid={`${TEST_ID}.Table`}>
                  <TableHead>
                    <TableRow>
                      <TableHeaderCell>Source</TableHeaderCell>
                      <TableHeaderCell>When</TableHeaderCell>
                      <TableHeaderCell>Brought across</TableHeaderCell>
                      <TableHeaderCell>Dropped</TableHeaderCell>
                      <TableHeaderCell>Report</TableHeaderCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((run) => (
                      <TableRow key={run.id} data-testid={`${TEST_ID}.Row`}>
                        <TableCell>
                          <span className="flex flex-wrap items-center gap-1.5">
                            {importProviderLabels[run.provider]}
                            <Identifier variant="muted">{run.sourceProjectKey}</Identifier>
                            {/*
                              The status rides in the Source cell rather than taking a column of its
                              own: the mockup's row has five columns and every completed run wears
                              the same chip, so a sixth would be a column of one repeated word. What
                              a reader is scanning for is the run that did NOT complete.
                            */}
                            {run.status !== 'COMPLETED' && (
                              <Badge
                                tone={importRunStatusTones[run.status]}
                                data-testid={`${TEST_ID}.Row.Status`}
                              >
                                {importRunStatusLabels[run.status]}
                              </Badge>
                            )}
                          </span>
                        </TableCell>
                        <TableCell>{formatDate(run.startedAt)}</TableCell>
                        {/*
                          A FAILED run wrote nothing, so its three zeroes are not a small import —
                          they are the absence of one, and rendering them as counts would read as a
                          migration that brought across nothing rather than one that never ran.
                        */}
                        <TableCell>
                          {run.status === 'COMPLETED' ? (
                            <span className="text-ink-2">
                              {`${plural(run.issuesImported, 'issue')} · ${plural(run.statusesMapped, 'status')} · ${plural(run.labelsCreated, 'label')}`}
                            </span>
                          ) : (
                            <span className="text-ink-4">
                              {run.status === 'RUNNING' ? 'still reading the source' : 'nothing'}
                            </span>
                          )}
                        </TableCell>
                        <TableCell>
                          {run.droppedFieldCount > 0 ? (
                            <span className="text-[color:var(--color-blocked)]">
                              {plural(run.droppedFieldCount, 'field type')}
                            </span>
                          ) : (
                            <span className="text-ink-4">&mdash;</span>
                          )}
                        </TableCell>
                        <TableCell>
                          <Button size="sm" asChild>
                            <RouterLink
                              to={`/projects/${projectId}/imports/${run.id}`}
                              data-testid={`${TEST_ID}.Row.Open`}
                            >
                              Open report
                            </RouterLink>
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )
            }
          </DataRegion>
          <p className="m-0 border-t border-line px-3 py-2.5 text-[12px] leading-[1.55] text-ink-4">
            The dropped-field list from the preview is in the report, unchanged. It does not expire
            when the migration finishes.
          </p>
        </Panel>

        <div className="mt-4" data-testid={`${TEST_ID}.NotAnIntegration`}>
          <Banner tone="neutral">
            <span>
              <strong>Nothing here keeps syncing.</strong> An import is a migration that runs once
              and finishes. A change made in Jira afterwards does not arrive here, and a change made
              here does not go back — the per-project tracker binding under{' '}
              <RouterLink
                className="underline underline-offset-2"
                to={`/projects/${projectId}/settings/trackers`}
              >
                Issue tracker
              </RouterLink>{' '}
              is the separate thing that files issues outward.
            </span>
          </Banner>
        </div>
      </div>
    </div>
  );
}
