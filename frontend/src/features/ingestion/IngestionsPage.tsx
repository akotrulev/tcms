import { Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import {
  useAutomationMappings,
  useIngestions,
  useProject,
} from '../../api/queries';
import type { AutomationMapping, IngestionStatus } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
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
import { EM_DASH, formatDateTime } from '../../lib/format';
import { ingestionFormatLabels, ingestionStatusLabels } from '../../lib/labels';
import { UnbindMappingDialog } from './UnbindMappingDialog';

/**
 * **CI ingestion** — what has been posted to this project, and the test names somebody has bound.
 *
 * §117 built the endpoint a CI job posts to and closed by saying that declining §6c's auto-created
 * case stubs *"is only defensible for a caller who can read JSON"* until this screen exists. It is
 * the reason the ladder's fourth rung — a test that matched nothing — is a design and not a gap.
 *
 * ## Two regions, two gates, and they must not be collapsed into one
 *
 * The **history** needs only project membership: anybody who may read this project's runs may read
 * how their verdicts got there. The **mappings** are `case:read` to list and `case:edit` to change,
 * because confirming that a test name means a case decides where that test's results land.
 *
 * They are on one screen because they are one job — *"my tests are not landing anywhere"* — and
 * they are two `DataRegion`s because they can fail independently: a Test Engineer with no
 * `case:read` (there is no such role today, but the gate is the server's to change) gets the
 * history and a refusal in the second panel, not a broken screen.
 *
 * ## The empty state is the one that matters
 *
 * A project with no ingestions is the **normal** state for most of this product's life, and the
 * empty text is the only place the product ever says how to start posting. It names the endpoint
 * rather than apologising, because the reader of an empty ingestion history is somebody setting CI
 * up.
 */
export function IngestionsPage() {
  const { projectId = '' } = useParams();
  const [unbinding, setUnbinding] = useState<AutomationMapping | null>(null);

  const project = useProject(projectId);
  const jobs = useIngestions(projectId);
  const mappings = useAutomationMappings(projectId);

  return (
    <div>
      {/*
        ~~`CI ingestion`~~ → **`CI results`** (021 §8.1). The system's verb becomes the reader's
        noun: nobody goes looking for an ingestion, they go looking for the results their pipeline
        posted. The route keeps `/ingestions`, which is the endpoint's word and belongs in the
        contract rather than in a breadcrumb.
      */}
      <PageHeader
        testId="Ingestions.Header"
        title="CI results"
        documentTitle={project.data ? `CI results · ${project.data.name}` : 'CI results'}
        subtitle="Reports your pipeline has posted, and what each one matched. A test that matched nothing recorded no result — bind it to a case here and every later build finds it."
        views={<TabStrip tabs={TABS.runs} projectId={projectId} label="Test run views" testId="Runs.Tabs" />}
      />

      <section data-testid="Ingestions.Panel" className="mb-6">
        <DataRegion
          testId="Ingestions.List"
          state={{ ...jobs, refetch: () => void jobs.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No reports posted yet',
            body: 'A CI job posts its results to POST /projects/…/ingestions with an API token. Issue one under Teams & roles → API tokens, and give it the Automation Service role.',
          }}
        >
          {(rows) => (
            <Table data-testid="Ingestions.Grid">
              <TableHead>
                <TableRow>
                  <TableHeaderCell className="w-[180px]">Posted</TableHeaderCell>
                  <TableHeaderCell className="w-[140px]">Format</TableHeaderCell>
                  <TableHeaderCell className="w-[120px]">State</TableHeaderCell>
                  <TableHeaderCell align="right" className="w-[110px]">
                    Matched
                  </TableHeaderCell>
                  <TableHeaderCell align="right" className="w-[130px]">
                    Unmatched
                  </TableHeaderCell>
                  <TableHeaderCell>Run</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((job) => (
                  <TableRow key={job.id} data-testid="Ingestions.Row">
                    <TableCell>
                      {/*
                        The link is on the timestamp because it is the row's identity — a job has
                        no name and its id is a UUID nobody reads. `formatDateTime` gives the
                        reader something to match against their build log.
                      */}
                      <RouterLink
                        to={`/projects/${projectId}/ingestions/${job.id}`}
                        className="font-mono text-[12px] tabular-nums text-steel underline-offset-4 hover:underline"
                        data-testid="Ingestions.Open"
                      >
                        {formatDateTime(job.createdAt)}
                      </RouterLink>
                    </TableCell>
                    <TableCell>{ingestionFormatLabels[job.format]}</TableCell>
                    <TableCell>
                      <Badge tone={STATE_TONE[job.status]}>{ingestionStatusLabels[job.status]}</Badge>
                    </TableCell>
                    <TableCell align="right" className="font-mono tabular-nums">
                      {job.matchedCount}
                    </TableCell>
                    <TableCell align="right" className="font-mono tabular-nums">
                      {/*
                        Design language rule 8 — *a count is a link to its rows*. The unmatched
                        tests are listed on the job itself, so the number is the way in rather than
                        a figure the reader has to go looking for.

                        NOT coloured as an error, and zero is not a link. An unmatched test is not a
                        failure — it is a result with nowhere to go, which is a thing to act on; and
                        a link to an empty list is a promise this row cannot keep.
                      */}
                      {job.unmatchedCount > 0 ? (
                        <RouterLink
                          to={`/projects/${projectId}/ingestions/${job.id}`}
                          className="font-medium text-steel underline-offset-4 hover:underline"
                          data-testid="Ingestions.Row.Unmatched"
                        >
                          {job.unmatchedCount}
                        </RouterLink>
                      ) : (
                        job.unmatchedCount
                      )}
                    </TableCell>
                    <TableCell>
                      <RunCell projectId={projectId} runId={job.runId} runKey={job.runKey} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DataRegion>
      </section>

      <section data-testid="Ingestions.MappingsPanel">
        <h2 className="mb-2 font-mono text-[11px] font-medium uppercase tracking-[.07em] text-ink-4">
          Test name mappings
        </h2>
        <p className="mb-3 text-[13px] text-ink-4">
          A confirmed binding survives renaming the case, the suite and the project — it is a fact
          about the pair. Only renaming the test itself breaks it.
        </p>
        <DataRegion
          testId="Ingestions.Mappings"
          state={{ ...mappings, refetch: () => void mappings.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'Nothing bound by hand',
            body: 'Open a report above and bind the tests it could not place. Cases that carry an automation key, or whose key appears in the test name, need no binding.',
          }}
        >
          {(rows) => (
            <Table data-testid="Ingestions.MappingsGrid">
              <TableHead>
                <TableRow>
                  <TableHeaderCell>Test</TableHeaderCell>
                  <TableHeaderCell className="w-[140px]">Case</TableHeaderCell>
                  <TableHeaderCell className="w-[180px]">Confirmed</TableHeaderCell>
                  <TableHeaderCell className="w-[60px]" />
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((mapping) => (
                  <TableRow key={mapping.id} data-testid="Ingestions.MappingRow">
                    <TableCell>
                      <Identifier>{mapping.testName}</Identifier>
                    </TableCell>
                    <TableCell>
                      {mapping.caseKey ? (
                        <RouterLink
                          to={`/projects/${projectId}/cases/${mapping.caseId}`}
                          className="text-steel underline-offset-4 hover:underline"
                        >
                          <Identifier>{mapping.caseKey}</Identifier>
                        </RouterLink>
                      ) : (
                        /*
                          The case was deleted. The row is deliberately still here and says so
                          rather than being filtered out: this binding still consumes the test
                          name, so every build still resolves that test to a case that is gone —
                          and hiding the row would hide the only thing that can be removed to fix
                          it.
                        */
                        <span
                          className="text-ink-4"
                          title="The case this points at has been deleted, so results for this test land nowhere. Remove the mapping."
                        >
                          Deleted case
                        </span>
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-[12px] tabular-nums text-ink-4">
                      {formatDateTime(mapping.confirmedAt)}
                    </TableCell>
                    <TableCell align="right">
                      {/* Disable-don't-hide (§31): the row is readable by anyone who can read
                          cases, and only `case:edit` may unbind. */}
                      <PermissionGate projectId={projectId} permission="case:edit">
                        {({ allowed, reason }) => (
                          <Button
                            variant="quiet"
                            size="icon"
                            disabled={!allowed}
                            onClick={() => setUnbinding(mapping)}
                            aria-label={`Remove the binding for ${mapping.testName}`}
                            title={allowed ? 'Remove this binding' : reason}
                            data-testid="Ingestions.Unbind"
                          >
                            <Trash2 aria-hidden className="size-4" />
                          </Button>
                        )}
                      </PermissionGate>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DataRegion>
      </section>

      {unbinding && (
        <UnbindMappingDialog
          open
          projectId={projectId}
          mapping={unbinding}
          onClose={() => setUnbinding(null)}
        />
      )}
    </div>
  );
}

/**
 * The ladder's four rungs in the results palette — design language rule 5. `PROCESSING` is amber
 * because a report still being read is a state that is not yet right, which is what amber means
 * everywhere else in this product; it is not a warning about the report.
 */
const STATE_TONE = {
  PENDING: 'plain',
  PROCESSING: 'blocked',
  COMPLETED: 'passed',
  FAILED: 'failed',
} as const satisfies Record<IngestionStatus, BadgeTone>;

/**
 * The run cell — and the three states it has to tell apart.
 *
 * `runId` null is *"this report matched nothing, so no run was opened"*, which is a designed
 * outcome and not an error: a run of zero cases would report 0 of 0, 100%, complete. `runId` with
 * no `runKey` is a run that has since been **deleted** — the server withholds the key rather than
 * offering a link that 404s. Only the third state is a link.
 *
 * Collapsing the first two into one dash is the tempting version, and it would tell somebody whose
 * run was deleted that their report matched nothing.
 */
function RunCell({
  projectId,
  runId,
  runKey,
}: {
  projectId: string;
  runId: string | null;
  runKey: string | null;
}) {
  if (!runId) {
    return (
      <span className="flex items-center gap-1.5">
        <span className="text-ink-4">No run</span>
        <span
          className="text-ink-4"
          title="Nothing in this report matched a case, so no run was opened. A run of zero cases would report itself complete."
        >
          {EM_DASH}
        </span>
      </span>
    );
  }
  if (!runKey) {
    return <span className="text-ink-4">Run deleted</span>;
  }
  return (
    <RouterLink
      to={`/projects/${projectId}/runs/${runId}`}
      className="text-steel underline-offset-4 hover:underline"
    >
      <Identifier>{runKey}</Identifier>
    </RouterLink>
  );
}
