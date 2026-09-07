import { Link as RouterLink, useParams } from 'react-router-dom';

import { useImportRun, useProject } from '../../api/queries';
import type { ImportRun } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Badge, Banner } from '../../components/kit';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Button } from '../../components/ui';
import { formatCount, formatDateTime, plural } from '../../lib/format';
import {
  importProviderLabels,
  importRunStatusLabels,
  importRunStatusTones,
  issueStatusKindTones,
} from '../../lib/labels';
import { DroppedFieldTable } from './DroppedFieldTable';
import { ImportStepper } from './ImportStepper';
import { downloadDroppedFieldsCsv, downloadReportJson } from './downloadReport';

const TEST_ID = 'ImportReport';

/**
 * `import-report.html` — what one migration did, readable a year after the source system is gone.
 *
 * ## One read, and the test asserts there is no second
 *
 * Everything on this screen comes from `GET /imports/{id}`. That is the module's fourth done-when
 * and it is a real constraint rather than an efficiency: a report that resolved a status name, a
 * person or an issue key by asking somewhere else would work perfectly today and be a screen full
 * of dashes the year the Jira site is decommissioned and the mapped statuses have been renamed. So
 * the applied mapping carries the target's **name and kind as they were at run time** (D-g), the
 * people carry the source's own spelling, and the dropped rows carry the sentence they were given
 * — none of it looked up here. `ImportReportPage.test.tsx` fails the render on any fetch that is
 * not this one, which is the only way that stays true.
 *
 * The project read beside it is the breadcrumb's, not the report's: it is the shell's own name for
 * where the reader is, and every screen behind this rail has already fetched it.
 *
 * ## Three states, and two of them are not a report
 *
 * `RUNNING` shows progress and no tiles, because a tile reading `0` while a run is still reading
 * the source is a number that is wrong rather than small. `FAILED` shows the reason and the
 * sentence that the project is untouched — the write is one all-or-nothing transaction after the
 * read pass (D-b), so a failed run has every count at zero and an **empty** dropped list, and
 * drawing that as a report would claim a migration that brought across nothing and lost nothing.
 * Only `COMPLETED` is the mockup's screen.
 *
 * ## Two disagreements with the mockup, both recorded rather than invented around
 *
 * **The *Open question* panel is replaced, not ported.** It says sprint membership is *"undecided,
 * and recorded as undecided rather than quietly resolved"*, and review R8 decided it on 2026-08-30:
 * sprints are dropped and counted, with `labelPerSprint` offered as the thing kept in their place
 * (D-h). The panel becomes *Sprints* and says what **this run** did. That is the mockup being out
 * of date about a **decision** rather than about a layout, which is the one case where it does not
 * win — porting it would print an open question this product has closed.
 *
 * **The *Keys survived* panel has no search box and no sample issue.** The mockup draws
 * `PROJ-4812` with its title, its type icon and its status, and offers a search field over it.
 * `ImportRun` carries no issue — it is a report of a migration, not a list of what it made — so
 * those would be a screen inventing rows. What is left is the claim itself, which is the panel's
 * actual subject (D5: the keys are the reason the migration is worth taking), and a link into Work,
 * where the search that mockup drew already lives.
 */
export function ImportReportPage() {
  const { projectId = '', importRunId = '' } = useParams();
  const project = useProject(projectId);
  const run = useImportRun(importRunId);

  const projectName = project.data?.name ?? 'this project';

  return (
    <div>
      <DataRegion
        testId={`${TEST_ID}.Region`}
        state={{ ...run, refetch: () => void run.refetch() }}
      >
        {(current) => (
          <>
            <PageHeader
              testId={`${TEST_ID}.Header`}
              title={`Import report · ${current.sourceProjectKey} → ${projectName}`}
              documentTitle={`Import ${current.sourceProjectKey}`}
              subtitle="Kept indefinitely, and reachable from this project's Imports list long after the migration."
              actions={
                <>
                  {/*
                    Rendered here, from the run in the cache — there is no server artifact and no
                    second request. `downloadReport.ts` says why, and why that is a departure from
                    D15 rather than an omission.
                  */}
                  <Button
                    size="sm"
                    onClick={() => downloadReportJson(current)}
                    data-testid={`${TEST_ID}.DownloadJson`}
                  >
                    Download report
                  </Button>
                  {current.droppedFields.length > 0 && (
                    <Button
                      size="sm"
                      onClick={() => downloadDroppedFieldsCsv(current)}
                      data-testid={`${TEST_ID}.DownloadCsv`}
                    >
                      Dropped fields (CSV)
                    </Button>
                  )}
                  <Button size="sm" asChild>
                    <RouterLink to={`/projects/${projectId}/work`}>Open the board</RouterLink>
                  </Button>
                </>
              }
            />

            <ImportStepper current={4} testId={`${TEST_ID}.Steps`} />

            <div className="mb-4 flex flex-wrap items-center gap-2.5">
              <Badge tone={importRunStatusTones[current.status]} data-testid={`${TEST_ID}.Status`}>
                {importRunStatusLabels[current.status]}
              </Badge>
              <span className="text-[12px] text-ink-4">
                {formatDateTime(current.finishedAt ?? current.startedAt)}
              </span>
              <Identifier variant="muted">
                {`${importProviderLabels[current.provider]} · ${current.siteUrl}`}
              </Identifier>
              {/*
                Both nullable, and both are the state the report has to survive rather than an
                unusual one: `started_by` carries no foreign key, so an account that was deactivated
                after the migration is a run nobody is named for, and the account email is absent on
                the two providers that authenticate with a bare PAT.
              */}
              {current.startedBy && (
                <span className="text-[12px] text-ink-4" data-testid={`${TEST_ID}.StartedBy`}>
                  by {current.startedBy.displayName}
                </span>
              )}
              {current.accountEmail && (
                <Identifier variant="muted">{current.accountEmail}</Identifier>
              )}
            </div>

            {current.status === 'RUNNING' && <RunningNote run={current} />}

            {current.status === 'FAILED' && (
              <div role="alert" className="max-w-[1180px]">
                <Banner tone="bad" data-testid={`${TEST_ID}.Failure`}>
                  <div className="min-w-0">
                    <p className="m-0 text-[13px] font-semibold text-ink">
                      {current.failureReason ?? 'The import did not finish.'}
                    </p>
                    <p className="mt-1 mb-0">
                      Nothing was written. {projectName} is exactly as it was — the whole import is
                      one transaction, taken after the source has been read, so there is no
                      half-imported project to clean up. Start again when the cause is fixed.
                    </p>
                  </div>
                </Banner>
              </div>
            )}

            {current.status === 'COMPLETED' && (
              <CompletedReport run={current} projectId={projectId} projectName={projectName} />
            )}
          </>
        )}
      </DataRegion>
    </div>
  );
}

/**
 * The `RUNNING` body — progress, and no tiles.
 *
 * `useImportRun` is already polling and stops on the response that finishes the run, so this needs
 * no timer of its own. What it needs to say is the thing that makes the wait safe to walk away
 * from: nothing is written until the whole source project has been read.
 */
function RunningNote({ run }: { run: ImportRun }) {
  return (
    <div className="max-w-[1180px]" data-testid={`${TEST_ID}.Running`}>
      <Banner tone="neutral">
        <span>
          Reading <Identifier>{run.sourceProjectKey}</Identifier>. Nothing is written until the whole
          project has been read, so this page is safe to leave and come back to — it is the same
          address afterwards, and the report appears here when the run finishes.
        </span>
      </Banner>
    </div>
  );
}

function CompletedReport({
  run,
  projectId,
  projectName,
}: {
  run: ImportRun;
  projectId: string;
  projectName: string;
}) {
  return (
    <div className="max-w-[1180px]">
      <div className="mb-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4" data-testid={`${TEST_ID}.Tiles`}>
        <Kpi label="Issues imported" value={run.issuesImported} detail="keys unchanged" />
        <Kpi
          label="Releases"
          value={run.releasesCreated}
          detail="from the source's fix versions"
        />
        <Kpi
          label="Comments"
          value={run.commentsImported}
          detail={`${plural(run.people.filter((person) => person.matched).length, 'author')} matched`}
        />
        <Kpi
          label="Values dropped"
          value={run.droppedValueCount}
          detail={plural(run.droppedFields.length, 'field type')}
          alert
        />
      </div>

      <div className="grid gap-3.5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex flex-col gap-3.5">
          <Panel title="Keys survived" testId={`${TEST_ID}.Keys`}>
            <div className="flex flex-col gap-2.5">
              {/*
                `steel`, not a green one: the kit's four tones are neutral, warn, bad and steel, and
                there is deliberately no "good" — an affirmative banner is an emphasis, and this
                claim is the reason the migration was worth taking (D5) rather than a warning.
              */}
              <Banner tone="steel">
                <span>
                  Every {run.sourceProjectKey} key resolves in {projectName}.{' '}
                  <Identifier>{`${run.sourceProjectKey}-4812`}</Identifier> is still{' '}
                  <Identifier>{`${run.sourceProjectKey}-4812`}</Identifier>, so a commit message, a
                  pull request title or a year of Slack history still points at the right issue.
                </span>
              </Banner>
              <p className="m-0 text-[12px] text-ink-4">
                <RouterLink
                  className="underline underline-offset-2"
                  to={`/projects/${projectId}/work`}
                >
                  Search this project&rsquo;s work
                </RouterLink>{' '}
                by any of those keys.
              </p>
            </div>
          </Panel>

          <Panel
            title="Dropped fields"
            testId={`${TEST_ID}.Dropped`}
            actions={
              <span className="text-[11.5px] text-ink-4">the same list the preview showed</span>
            }
            dense
          >
            {/*
              The preview step's component, not a copy of it (D-f). The list is asserted equal
              element for element on the wire; one component is what keeps that true on screen.
            */}
            <DroppedFieldTable fields={run.droppedFields} testId={`${TEST_ID}.Dropped`} />
            <p className="m-0 border-t border-line px-3 py-2.5 text-[12px] leading-[1.55] text-[color:var(--color-blocked)]">
              <strong>Not recoverable from this product.</strong> This report says so a year later,
              in the same words the preview used.
            </p>
          </Panel>

          <Panel title="Status mapping applied" testId={`${TEST_ID}.Mapping`} dense>
            <Table data-testid={`${TEST_ID}.Mapping.Table`}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>Source status</TableHeaderCell>
                  <TableHeaderCell>Became</TableHeaderCell>
                  <TableHeaderCell align="right">Issues</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {run.statusMapping.map((entry) => (
                  <TableRow key={entry.sourceStatusName} data-testid={`${TEST_ID}.Mapping.Row`}>
                    <TableCell>
                      <Identifier variant="muted">{entry.sourceStatusName}</Identifier>
                    </TableCell>
                    {/*
                      The NAME on the row, never a lookup against the project's current workflow. A
                      status can be deleted or renamed after the migration and the report still has
                      to say what this run actually did (D-g) — which is also why there is no link
                      to the status here.
                    */}
                    <TableCell>
                      <Badge tone={issueStatusKindTones[entry.targetStatusKind]}>
                        {entry.targetStatusName}
                      </Badge>
                    </TableCell>
                    <TableCell align="right">
                      <Identifier variant="muted">{formatCount(entry.issueCount)}</Identifier>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Panel>
        </div>

        <div className="flex flex-col gap-3.5">
          <Panel title="People matched" testId={`${TEST_ID}.People`}>
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {run.people.map((person) => (
                <li
                  key={person.sourceName}
                  className="flex items-center gap-2 text-[12.5px]"
                  data-testid={`${TEST_ID}.People.Row`}
                >
                  <span className="min-w-0 flex-1 truncate">
                    {person.matched ? (person.userName ?? person.sourceName) : person.sourceName}
                  </span>
                  <Badge tone={person.matched ? 'passed' : 'plain'}>
                    {person.matched ? 'matched' : 'kept as text'}
                  </Badge>
                </li>
              ))}
            </ul>
            <p className="m-0 mt-2.5 border-t border-line pt-2.5 text-[11.5px] leading-[1.5] text-ink-4">
              An unmatched author is preserved as text on the comment rather than reassigned to
              somebody who did not write it.
            </p>
          </Panel>

          {/*
            The mockup's *Open question* panel, replaced. R8 decided it on 2026-08-30 and this says
            what THIS run did — see the file header for why a decided question is the one thing a
            mockup does not get to keep.
          */}
          <Panel title="Sprints" testId={`${TEST_ID}.Sprints`}>
            <p className="m-0 text-[12.5px] leading-[1.55] text-ink-2">
              {run.labelPerSprint ? (
                <>
                  <strong>Kept as labels.</strong> One label per distinct sprint name, one
                  assignment per issue, coloured round-robin over this product&rsquo;s ten label
                  colours — the source&rsquo;s own colours are hex and a label here takes a design
                  token, so they could not come across. A label carries no dates, capacity or board:
                  the sprint row in the dropped list above holds the number of values, and it stayed
                  there for that reason.
                </>
              ) : (
                <>
                  <strong>Dropped and counted.</strong> Releases replace sprints in this product
                  (D6), and this run was not asked to keep one label per sprint — so sprint
                  membership is gone, with its value count on the sprint row in the dropped list
                  above. It cannot be recovered from here.
                </>
              )}
            </p>
          </Panel>

          <Panel title="Backlog order" testId={`${TEST_ID}.Order`}>
            <p className="m-0 text-[12.5px] leading-[1.55] text-ink-2">
              {run.backlogOrderPreserved ? (
                <>
                  <strong>Imported.</strong> The backlog is in the source&rsquo;s own order, so the
                  ranking somebody spent a year making still means what it meant.
                </>
              ) : (
                <>
                  <strong>Insertion order.</strong> This run was not asked to keep the source&rsquo;s
                  backlog order, so the ranking below the board is the order the issues happened to
                  be written in — chosen by nobody. Reorder the backlog before reading anything into
                  it.
                </>
              )}
            </p>
          </Panel>
        </div>
      </div>
    </div>
  );
}

/**
 * One KPI.
 *
 * The kit's `Tile` is not used, for `ReadinessPanels`' reason: it is a `<button>` that navigates to
 * its rows, and none of these four has rows to navigate to — the dropped values are the table
 * below, and there is no list of "the comments this import made" anywhere on the wire. A tile that
 * looked clickable and was not is the affordance defect `Badge` and `Chip` exist to keep apart.
 */
function Kpi({
  label,
  value,
  detail,
  alert,
}: {
  label: string;
  value: number;
  detail: string;
  alert?: boolean;
}) {
  return (
    <section
      data-testid={`${TEST_ID}.Tile.${label.replaceAll(' ', '')}`}
      className="rounded-[8px] border border-line bg-panel px-[15px] py-[13px]"
    >
      <span className="mb-1.5 block text-[11.5px] text-ink-3">{label}</span>
      <span
        style={alert && value > 0 ? { color: 'var(--color-blocked)' } : undefined}
        className="block font-mono text-[26px] leading-[1.1] font-medium tracking-[-.02em] tabular-nums text-ink"
      >
        {formatCount(value)}
      </span>
      <span className="mt-[5px] block text-[11.5px] text-ink-4">{detail}</span>
    </section>
  );
}
