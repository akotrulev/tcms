import { Link2 } from 'lucide-react';
import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { useIngestion } from '../../api/queries';
import type { IngestedOutcome, UnmatchedTest } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
  Banner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  type BadgeTone,
} from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Button } from '../../components/ui';
import { formatDateTime } from '../../lib/format';
import {
  ingestedOutcomeLabels,
  ingestionFormatLabels,
  ingestionStatusLabels,
} from '../../lib/labels';
import { BindTestDialog } from './BindTestDialog';
import { DownloadRawReportButton } from './DownloadRawReportButton';

/**
 * One posted report — **the unmatched-test binder**, and the screen §117 named on its way out.
 *
 * ## What this screen is for
 *
 * §6c offers a quarantine folder with auto-created case stubs for tests the ladder cannot place.
 * §117 declined it: a CI job that can mint cases turns every test *rename* into a permanent
 * duplicate of a case that already exists — the identity problem `automationKey` exists to solve,
 * reintroduced by the feature meant to use it. That refusal is a design and not a gap **only if a
 * person can do the binding**, which is this page.
 *
 * ## The outcome column is not decoration
 *
 * An unmatched **failure** is a red test nobody is being told about. An unmatched pass is only
 * unrecorded coverage. Both are rows to work through and they are not equally urgent, which is why
 * the contract carries `outcome` on `UnmatchedTest` at all — and why the failures are marked here
 * rather than left for the reader to notice.
 *
 * ## The name is shown, the normalized form is what is sent
 *
 * The row shows `className#name` as the report wrote it, because that is what a person recognises
 * from their test source. `POST /automation-mappings` takes the name **raw** and normalizes it
 * server-side; this screen never normalizes, because the rule has to agree exactly with the one the
 * parser applies and a second implementation is how every mapping silently stops matching.
 *
 * ## A capped list says so
 *
 * The server serves at most 50 names beside a true `unmatchedCount`. When they differ this page
 * says which, because a reader who binds all 50 and sees the count unchanged would otherwise
 * conclude the binding did not work.
 *
 * ## The posted file is downloadable, and that is the other half of binding
 *
 * The table says which names the server could not place. It cannot say whether a test the person
 * *expected* was in the report at all — a test that never ran is absent from `unmatched` and absent
 * from the run, and looks identical to one that was never written. `reportUrl` is the only way to
 * tell those apart, which is why the control sits on this screen rather than on the history list.
 */
export function IngestionJobPage() {
  const { projectId = '', jobId = '' } = useParams();
  const [binding, setBinding] = useState<UnmatchedTest | null>(null);

  /*
    ~~`useProject`, for the project's name in this screen's own breadcrumb.~~ →
    **superseded (021 R2): the shell names the project**, from the read it already makes
    for the rail's switcher — so this page's copy of that read went with its trail.
  */
  const job = useIngestion(projectId, jobId);

  const posted = job.data ? formatDateTime(job.data.createdAt) : '';

  return (
    <div>
      <PageHeader
        testId="Ingestion.Header"
        title={posted ? `Report posted ${posted}` : 'Posted report'}
        documentTitle={posted ? `Ingestion · ${posted}` : 'Ingestion'}
        subtitle="What this report matched, and the tests it could not place. Binding a test name here makes every later build find it."
        meta={
          job.data ? (
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="plain">{ingestionStatusLabels[job.data.status]}</Badge>
              <Badge tone="plain">{ingestionFormatLabels[job.data.format]}</Badge>
              <span className="text-[13px] text-ink-3">
                <span className="font-mono tabular-nums">{job.data.matchedCount}</span> matched ·{' '}
                <span className="font-mono tabular-nums">{job.data.unmatchedCount}</span> unmatched
              </span>
              {job.data.runId && job.data.runKey && (
                <RouterLink
                  to={`/projects/${projectId}/runs/${job.data.runId}`}
                  data-testid="Ingestion.RunLink"
                >
                  <Identifier>{job.data.runKey}</Identifier>
                </RouterLink>
              )}
              {/*
                In the header rather than in the panel below, because the panel is a DataRegion that
                renders nothing when every test matched — and a report where everything matched is
                exactly when somebody wants to check what was actually posted. The control renders
                itself away when `reportUrl` is null, which is every job from before uploads were
                kept.
              */}
              <DownloadRawReportButton job={job.data} testId="Ingestion.DownloadReport" />
            </div>
          ) : undefined
        }
      />

      <section data-testid="Ingestion.Panel">
        <DataRegion
          testId="Ingestion.Unmatched"
          state={{ ...job, refetch: () => void job.refetch() }}
          isEmpty={(one) => one.unmatchedCount === 0}
          empty={{
            kind: 'no-records',
            title: 'Every test found a case',
            body: 'Nothing in this report needs binding.',
          }}
        >
          {(one) => (
            <div>
              {/*
                A report that matched NOTHING is its own state and is worth saying out loud: no run
                was opened, which is a designed outcome rather than a failure, and it is the shape
                of the FIRST report most teams post — while they are still deciding whether the
                integration works at all.
              */}
              {one.runId === null && (
                <Banner tone="steel" className="mb-4" data-testid="Ingestion.NoRun">
                  <span>
                    Nothing in this report matched a case, so no run was opened — a run of zero
                    cases would report itself complete. Bind these tests and post again.
                  </span>
                </Banner>
              )}

              {one.unmatched.length < one.unmatchedCount && (
                <Banner tone="warn" className="mb-4" data-testid="Ingestion.Capped">
                  <span>
                    Showing the first {one.unmatched.length} of {one.unmatchedCount} unmatched
                    tests. Bind these and post the report again to see the rest.
                  </span>
                </Banner>
              )}

              <Table data-testid="Ingestion.UnmatchedGrid">
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>Reported test</TableHeaderCell>
                    <TableHeaderCell className="w-[130px]">Reported</TableHeaderCell>
                    <TableHeaderCell align="right" className="w-[150px]" />
                  </TableRow>
                </TableHead>
                <TableBody>
                  {one.unmatched.map((test) => (
                    <TableRow key={test.normalizedName} data-testid="Ingestion.UnmatchedRow">
                      <TableCell>
                        <Identifier>{test.name}</Identifier>
                      </TableCell>
                      <TableCell>
                        <Outcome outcome={test.outcome} />
                      </TableCell>
                      <TableCell align="right">
                        {/*
                          `case:edit`, disabled with the reason rather than hidden (§31). It is
                          the same key that writes a case's automationKey, and Automation Service
                          deliberately does not hold it — so a leaked CI token cannot re-point
                          where anybody's results land.
                        */}
                        <PermissionGate projectId={projectId} permission="case:edit">
                          {({ allowed, reason }) => (
                            <Button
                              size="sm"
                              disabled={!allowed}
                              title={allowed ? 'Say which case this test verifies' : reason}
                              onClick={() => setBinding(test)}
                              data-testid="Ingestion.Bind"
                            >
                              <Link2 aria-hidden className="size-3.5" />
                              Bind
                            </Button>
                          )}
                        </PermissionGate>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </DataRegion>
      </section>

      {binding && (
        <BindTestDialog
          open
          projectId={projectId}
          test={binding}
          onClose={() => setBinding(null)}
        />
      )}
    </div>
  );
}

/**
 * What the runner said, with the failures marked.
 *
 * `ERRORED` is kept distinct from `FAILED` rather than folded into it: the two land on the same
 * `StatusKind`, but a report separating `<failure>` from `<error>` is telling you that one test
 * lost an assertion and another threw before it could make one — usually the environment rather
 * than the thing under test. On the screen that shows what was *reported*, that distinction is the
 * whole point.
 */
const outcomeTone = {
  PASSED: 'passed',
  FAILED: 'failed',
  ERRORED: 'failed',
  SKIPPED: 'skipped',
} as const satisfies Record<IngestedOutcome, BadgeTone>;

function Outcome({ outcome }: { outcome: UnmatchedTest['outcome'] }) {
  return <Badge tone={outcomeTone[outcome]}>{ingestedOutcomeLabels[outcome]}</Badge>;
}
