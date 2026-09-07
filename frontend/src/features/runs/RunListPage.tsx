import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import type { ResultCounts, RunStatus, TestRun } from '../../api/types';
import { useProject, useRuns } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
  ResultBar,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  type BadgeTone,
} from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { TabStrip } from '../../components/TabStrip';
import { TABS } from '../../app/nav';
import { PermissionGate } from '../../components/PermissionGate';
import { Button } from '../../components/ui';
import { RunCreateDialog } from './RunCreateDialog';
import { executedCount, passRate } from '../../lib/results';
import { EM_DASH, formatAge, formatCount, formatDateTime, formatDuration, formatPercent } from '../../lib/format';
import { runSourceLabels, runStatusLabels } from '../../lib/labels';
import { useAnnouncedResults } from '../../a11y/useAnnouncedResults';

/**
 * Test runs. A run is one execution pass of a plan on one configuration, and the two facts a
 * reader needs first are whether it finished and how it went — so status and the result bar
 * lead, and the metadata follows.
 *
 * Manual and ingested runs sit in the same list on purpose: a nightly CI run and a hand-driven
 * Safari sweep are both evidence about the same release, and splitting them into two screens
 * is how a team ends up with two different views of its own quality.
 */
export function RunListPage() {
  const { projectId = '' } = useParams();
  const navigate = useNavigate();
  const project = useProject(projectId);
  const runs = useRuns(projectId);
  useAnnouncedResults(
    runs,
    `${formatCount(runs.data?.length ?? 0)} ${(runs.data?.length ?? 0) === 1 ? 'run' : 'runs'} shown`,
  );
  const [creating, setCreating] = useState(false);

  return (
    <div>
      <PageHeader
        testId="Runs.Header"
        title="Test runs"
        documentTitle={project.data ? `Test runs · ${project.data.name}` : 'Test runs'}
        subtitle="Every pass over a plan, manual or ingested from CI."
        actions={
          /*
            `run:create` is the widest write gate in the product — every role but Viewer holds it,
            including Automation Service, because a CI job opens the run it is about to post into.
            Gated all the same: the one role that does not hold it is the one most likely to be
            looking at this screen.
          */
          <PermissionGate projectId={projectId} permission="run:create">
            {({ allowed }) => (
              <Button
                variant="primary"
                disabled={!allowed}
                onClick={() => setCreating(true)}
                data-testid="Runs.New"
              >
                New run
              </Button>
            )}
          </PermissionGate>
        }
      />

      {/*
        Runs · Schedules · CI results (021 §1). `Schedules` was an ORPHAN — a shipped feature with
        no rail entry and no inbound link from any screen, reachable only by typing the URL — and
        `CI results` was a rail row of its own. Both are views of "what has run here", so they are
        tabs on the screen they extend rather than two more rows in a rail.
      */}
      <TabStrip tabs={TABS.runs} projectId={projectId} label="Test run views" testId="Runs.Tabs" />

      {creating && (
        <RunCreateDialog
          projectId={projectId}
          open
          onClose={() => setCreating(false)}
          /* Straight to the checklist — the run was created in order to work through it. */
          onCreated={(run) => navigate(`/projects/${projectId}/runs/${run.id}`)}
        />
      )}

      <section data-testid="Runs.Panel">
        <DataRegion
          testId="Runs.List"
          state={{ ...runs, refetch: () => void runs.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No runs yet',
            body: 'Start a run against a plan, or point your CI job at the results endpoint.',
          }}
        >
          {(rows) => <RunTable rows={rows} projectId={projectId} />}
        </DataRegion>
      </section>
    </div>
  );
}

/**
 * A run's badge tone is its *result*, not its lifecycle — `renders/01-index-tables.html` frame C
 * draws two rows both reading **Complete**, one green and one red, and that is the point: design
 * language rule 5 gives results and keywords one palette, so a completed run whose executions
 * failed is the red that `Failed` is. Colouring both green would make the column say only "it
 * finished", which the Started column already implies.
 */
function statusTone(status: RunStatus, counts: ResultCounts): BadgeTone {
  if (status === 'IN_PROGRESS') return 'blocked';
  if (status === 'ABORTED') return 'skipped';
  if (status === 'COMPLETED') return counts.failed > 0 ? 'failed' : 'passed';
  return 'plain';
}

function RunTable({ rows, projectId }: { rows: TestRun[]; projectId: string }) {
  return (
    <Table data-testid="Runs.Grid">
      <TableHead>
        <TableRow>
          <TableHeaderCell>Run</TableHeaderCell>
          <TableHeaderCell className="w-[120px]">Status</TableHeaderCell>
          <TableHeaderCell className="w-[160px]">Configuration</TableHeaderCell>
          <TableHeaderCell className="w-[130px]">Release</TableHeaderCell>
          <TableHeaderCell className="w-[180px]">Result</TableHeaderCell>
          <TableHeaderCell align="right" className="w-[90px]">
            Pass rate
          </TableHeaderCell>
          <TableHeaderCell className="w-[130px]">Started</TableHeaderCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map((run) => {
          const executed = executedCount(run.counts);
          return (
            <TableRow key={run.id} data-testid="Runs.Row">
              <TableCell
                variant="title"
                subtitle={
                  <>
                    <RouterLink
                      to={`/projects/${projectId}/runs/${run.id}`}
                      className="text-inherit hover:text-ink"
                      data-testid="Runs.Row.Link"
                    >
                      <Identifier variant="muted">{run.key}</Identifier>
                    </RouterLink>
                    {run.planKey && (
                      <>
                        {' \u00b7 '}
                        <Identifier variant="muted">{run.planKey}</Identifier>
                      </>
                    )}
                    {' \u00b7 '}
                    {/* Where the results came from changes how much a reader trusts them and
                        who to ask about a failure, so it's shown rather than implied. */}
                    {runSourceLabels[run.source]}
                  </>
                }
              >
                <span data-testid="Runs.Row.Name">{run.name}</span>
              </TableCell>
              <TableCell>
                <Badge tone={statusTone(run.status, run.counts)} data-testid="Runs.Row.Status">
                  {runStatusLabels[run.status]}
                </Badge>
              </TableCell>
              <TableCell className="text-[12px]">{run.configuration?.name ?? EM_DASH}</TableCell>
              <TableCell className="text-[12px]">
                {run.releaseVersion ? (
                  <>
                    <Identifier>{run.releaseVersion}</Identifier>
                    {run.buildNumber && (
                      <span className="block text-ink-4">
                        <Identifier variant="muted">{run.buildNumber}</Identifier>
                      </span>
                    )}
                  </>
                ) : (
                  <span className="text-ink-4">{EM_DASH}</span>
                )}
              </TableCell>
              <TableCell>
                {/*
                  Proportion first, number second — the bar carries its own screen-reader label, so
                  the tooltip `Tooltip` used to hold is the `title` on the counts beside it rather
                  than a component per cell.
                */}
                <span
                  data-testid="Runs.Row.ResultBar"
                  /*
                    Every segment the bar can draw, in the bar's fill order — `skipped` included.
                    The same omission as the run detail's legend (renders audit 2.2): the bar drew
                    a fourth segment that this title never named, and ending on "not run" reads as
                    a complete account.
                  */
                  title={`${formatCount(run.counts.passed)} passed \u00b7 ${formatCount(run.counts.failed)} failed \u00b7 ${formatCount(
                    run.counts.blocked,
                  )} blocked \u00b7 ${formatCount(run.counts.skipped)} skipped \u00b7 ${formatCount(
                    run.counts.notRun,
                  )} not run`}
                >
                  <ResultBar
                    passed={run.counts.passed}
                    failed={run.counts.failed}
                    blocked={run.counts.blocked}
                    skipped={run.counts.skipped}
                    untested={run.counts.notRun}
                    className="w-[96px] min-w-0"
                  />
                  <span className="mt-1 block font-mono text-[11.5px] tabular-nums text-ink-4">
                    {formatCount(executed)} of {formatCount(run.counts.total)} executed
                  </span>
                </span>
              </TableCell>
              <TableCell align="right">
                {/* Of what ran — an in-progress run at 12 passed of 15 executed is at 80%,
                    not at 29% of its 42-case scope. Both readings are available on the run. */}
                <Identifier>{formatPercent(passRate(run.counts, 'EXECUTED'), 0)}</Identifier>
              </TableCell>
              <TableCell className="text-[12px] text-ink-4">
                <span title={formatDateTime(run.startedAt)}>{formatAge(run.startedAt)}</span>
                {run.completedAt && run.startedAt && (
                  <span className="block">
                    took{' '}
                    <Identifier variant="muted">
                      {formatDuration(new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime())}
                    </Identifier>
                  </span>
                )}
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
