import { FileChartColumn, RotateCcw, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  useDeleteReportDefinition,
  useProject,
  useReportDefinitions,
  useReportJobs,
} from '../../api/queries';
import type { ReportDefinition } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import {
  Banner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { humanize } from '../../lib/labels';
import { GenerateReportDialog } from './GenerateReportDialog';
import { ReportJobRow } from './ReportJobRow';

/**
 * §6e's reports screen — F1 of the M2.5 plan, and the first client for §87's pipeline.
 *
 * ## The screen used to have to remember what it asked for. It does not any more.
 *
 * ~~`report_job` rows are per-request and there is **no operation that lists them**: `POST
 * /reports/generate` hands back a job and `GET /reports/jobs/{jobId}` resolves one by id, and that
 * is the whole surface. So the set of jobs this screen shows is the set *this tab* created, held
 * in component state, and a reload loses the download links — the reports themselves are still
 * generated and their artifacts still exist, and nothing in this version can find them again.
 *
 * That is a real gap and it is stated here rather than papered over with copy that implies
 * otherwise (the card says it too). It is **not** worked around by writing job ids into the URL or
 * into storage: a list endpoint is the fix, it belongs to the server, and a client-side register of
 * ids would be a second, drifting copy of a table that already exists — the exact shape §81 found
 * when the mock invented a feature the server did not have.~~ → **§227 shipped the list endpoint
 * that paragraph named**, and this screen now reads `GET /reports/jobs`. The refusal to work around
 * it is the reason there is nothing to unwind here: the fix was one hook, because no client-side
 * register had to be dismantled first.
 *
 * ## Two panels, and the empty one is no longer a statement about the product
 *
 * ~~Saved definitions have a declared, served `GET` that answers `[]` on every deployment until R5
 * gives it a writer. The panel is built anyway and says exactly that — there is **no Save button**,
 * because `ui-rules` forbids an Add control whose endpoint does not exist.~~ → the writer landed in
 * §92 on the server and the control did not follow, so the panel went on describing a missing
 * feature for eight weeks after it existed. Saving now happens on the generate dialog (where the
 * settings being saved are composed), and each row here regenerates or deletes.
 *
 * ## What is gated and what is not
 *
 * **Generate** asks `report:export` and is disabled-with-a-reason (§31), never hidden. Row-level
 * **Generate** asks the same key for the same reason — regenerating a saved report is generating
 * one. **Delete** asks `report:view`, which reads as too weak and is the contract's decision: a
 * definition is the caller's own row, invisible to everyone else, so the key that lets somebody
 * save one is the key that lets them throw it away. The job list is gated on `report:view`, which
 * every seeded role but Automation Service holds, and that role is API-token only — so the panel
 * is not wrapped in a gate that would refuse nobody who can reach this page. **The rail item is
 * likewise ungated**, which is a deliberate departure from the plan's line ("rail item gated on
 * `report:view`"): no rail item in this app is gated, the `Review` item's own comment records why,
 * and a gate that refuses nobody is decorative until the day it silently is not.
 */
export function ReportsPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const definitions = useReportDefinitions(projectId);
  const jobs = useReportJobs(projectId);
  const remove = useDeleteReportDefinition(projectId);

  /**
   * What the dialog is open for: nothing, an ad-hoc report, or one saved definition.
   *
   * One piece of state rather than a boolean plus a definition, so "open for a definition" and
   * "open for nothing" cannot both be true — which is the state a boolean and a nullable row
   * reliably reach, and it renders as a regenerate dialog for the report somebody closed a moment
   * ago.
   */
  const [generating, setGenerating] = useState<'adhoc' | ReportDefinition | null>(null);
  const [deleting, setDeleting] = useState<ReportDefinition | null>(null);

  return (
    <div>
      <PageHeader
        testId="Reports.Header"
        title="Reports"
        documentTitle={project.data ? `Reports · ${project.data.name}` : 'Reports'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Reports' },
        ]}
        subtitle="Generated in the background and downloaded as a file. Every report carries a header saying what it covers, who asked for it and when."
        actions={
          <PermissionGate projectId={projectId} permission="report:export">
            {({ allowed }) => (
              <Button
                variant="primary"
                disabled={!allowed}
                onClick={() => setGenerating('adhoc')}
                data-testid="Reports.GenerateButton"
              >
                <FileChartColumn aria-hidden className="size-4" />
                Generate
              </Button>
            )}
          </PermissionGate>
        }
      />

      {generating && (
        <GenerateReportDialog
          open
          projectId={projectId}
          definition={generating === 'adhoc' ? undefined : generating}
          onClose={() => setGenerating(null)}
          /*
            The 202 is seeded into the cache and the list invalidated by the hook itself, so there
            is nothing for this screen to remember — which is the whole difference from the version
            that held job ids in component state.
          */
          onQueued={() => setGenerating(null)}
        />
      )}

      <section data-testid="Reports.JobsPanel" className="mb-6">
        <h2 className="mb-2 font-mono text-[11px] font-medium uppercase tracking-[.07em] text-ink-4">
          Generated reports
        </h2>
        <DataRegion
          testId="Reports.Jobs"
          state={{ ...jobs, refetch: () => void jobs.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No reports yet',
            body: 'Nothing has been generated for this project. Use Generate above — the report is produced in the background and appears here.',
          }}
        >
          {(rows) => (
            /*
              The same table CI ingestion draws, deliberately — render F pairs the two screens on
              purpose and the build had drawn this half as a stack of panels (renders audit 1.8).
              Same columns, same order, same header treatment; only the noun in the first column
              differs.
            */
            <Table data-testid="Reports.Jobs.List">
              <TableHead>
                <TableRow>
                  <TableHeaderCell>Report</TableHeaderCell>
                  <TableHeaderCell className="w-[120px]">Kind</TableHeaderCell>
                  <TableHeaderCell className="w-[150px]">State</TableHeaderCell>
                  <TableHeaderCell align="right" className="w-[100px]">
                    Size
                  </TableHeaderCell>
                  <TableHeaderCell className="w-[180px]">Generated</TableHeaderCell>
                  <TableHeaderCell align="right" className="w-[140px]">
                    <span className="sr-only">Download</span>
                  </TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((job) => (
                  <ReportJobRow key={job.jobId} projectId={projectId} job={job} />
                ))}
              </TableBody>
            </Table>
          )}
        </DataRegion>
      </section>

      {/*
        "My saved reports", not "Saved reports". `GET /reports/definitions` answers with the
        CALLER's rows and nobody else's — the contract says so in as many words, and warns that a
        list quietly meaning "mine" is indistinguishable from one meaning "everyone's". A lead who
        knows a colleague saved a report, and cannot find it here, reads an unqualified title as
        data loss. The `Owner` column was removed for the same reason rather than kept: it could
        only ever print the reader's own name, so it answered a question it appeared to ask.
      */}
      <section data-testid="Reports.DefinitionsPanel">
        <h2 className="mb-2 font-mono text-[11px] font-medium uppercase tracking-[.07em] text-ink-4">
          My saved reports
        </h2>
        <DataRegion
          testId="Reports.Definitions"
          state={{ ...definitions, refetch: () => void definitions.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'You have not saved any reports',
            body: 'A saved report keeps its settings so the same one can be regenerated next release. Name one under “Save these settings as” when you generate. Saved reports are yours alone — a colleague’s will not appear here.',
          }}
        >
          {(rows) => (
            <DefinitionTable
              rows={rows}
              projectId={projectId}
              onGenerate={setGenerating}
              onDelete={setDeleting}
            />
          )}
        </DataRegion>
      </section>

      {deleting && (
        <DeleteDefinitionDialog
          definition={deleting}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await remove.mutateAsync(deleting.id);
            setDeleting(null);
          }}
          pending={remove.isPending}
          error={remove.error}
          reset={remove.reset}
        />
      )}
    </div>
  );
}

/**
 * The confirmation for a hard delete.
 *
 * `ui-rules` §4 takes no exception for a small destructive action, and this one earns the rule on
 * the consequence a reader is most likely to have backwards: deleting a saved report does **not**
 * delete the reports it produced. `report_job.definition_id` and `report_artifact.definition_id`
 * are both `ON DELETE SET NULL`, so those jobs simply become indistinguishable from ad-hoc ones —
 * which is what they now are.
 *
 * Local rather than `UnlinkDialog`, whose title is "Remove this link?" and whose whole shape is
 * about traceability. Reusing it would put the wrong noun on the one screen that has to be exact
 * about which of two things is being destroyed.
 */
function DeleteDefinitionDialog({
  definition,
  onClose,
  onConfirm,
  pending,
  error,
  reset,
}: {
  definition: ReportDefinition;
  onClose: () => void;
  onConfirm: () => void;
  pending: boolean;
  error: unknown;
  /** The mutation's `reset`, so a reopen does not show the previous refusal. */
  reset: () => void;
}) {
  useEffect(() => {
    reset();
  }, [definition.id, reset]);

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-w-sm" data-testid="Reports.Definitions.Confirm">
        <DialogHeader>
          <DialogTitle>Delete this saved report?</DialogTitle>
          <DialogDescription data-testid="Reports.Definitions.Confirm.Target">
            “{definition.name}” — {humanize(definition.type)}.
          </DialogDescription>
        </DialogHeader>
        <p className="mt-3 text-[13px] leading-[1.55] text-ink-3">
          The reports it has already produced stay where they are and remain downloadable. Only the
          saved settings go, and they cannot be recovered.
        </p>
        {error != null && (
          <Banner tone="bad" className="mt-3" data-testid="Reports.Definitions.Confirm.Error">
            <span role="alert">{describeError(error).body}</span>
          </Banner>
        )}
        <DialogFooter>
          <Button onClick={onClose} data-testid="Reports.Definitions.Confirm.Cancel">
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={pending}
            onClick={onConfirm}
            data-testid="Reports.Definitions.Confirm.Submit"
          >
            {pending ? 'Deleting…' : 'Delete'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function DefinitionTable({
  rows,
  projectId,
  onGenerate,
  onDelete,
}: {
  rows: ReportDefinition[];
  projectId: string;
  onGenerate: (definition: ReportDefinition) => void;
  onDelete: (definition: ReportDefinition) => void;
}) {
  // By name, which is how somebody looks for one. A unique tiebreaker on the id, so two
  // definitions sharing a name cannot swap places between renders.
  const sorted = [...rows].sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));

  return (
    <Table data-testid="Reports.Definitions.Grid">
      <TableHead>
        <TableRow>
          <TableHeaderCell>Report</TableHeaderCell>
          <TableHeaderCell className="w-[200px]">Kind</TableHeaderCell>
          <TableHeaderCell align="right" className="w-[110px]">
            Actions
          </TableHeaderCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {sorted.map((definition) => (
          <TableRow key={definition.id} data-testid="Reports.Definitions.Row">
            <TableCell variant="title" className="font-medium">
              {definition.name}
            </TableCell>
            <TableCell className="text-[12px] text-ink-4">{humanize(definition.type)}</TableCell>
            <TableCell align="right">
              {/*
                Disabled-with-a-reason rather than hidden (§31), and the two controls ask different
                keys — see the page header. ~~A tooltip on a disabled MUI button needs a wrapping
                span, or the tooltip never fires and the reason is unreachable.~~ → superseded: the
                reason is the native `title`, which a disabled control still shows and which needs
                no wrapper.
              */}
              <PermissionGate projectId={projectId} permission="report:export">
                {({ allowed }) => (
                  <Button
                    variant="quiet"
                    size="icon"
                    disabled={!allowed}
                    onClick={() => onGenerate(definition)}
                    aria-label={`Generate ${definition.name}`}
                    title={
                      allowed
                        ? 'Generate this report again'
                        : 'Generating a report needs report:export on this project.'
                    }
                    data-testid="Reports.Definitions.Generate"
                  >
                    <RotateCcw aria-hidden className="size-4" />
                  </Button>
                )}
              </PermissionGate>
              <PermissionGate projectId={projectId} permission="report:view">
                {({ allowed }) => (
                  <Button
                    variant="quiet"
                    size="icon"
                    disabled={!allowed}
                    onClick={() => onDelete(definition)}
                    aria-label={`Delete ${definition.name}`}
                    title={
                      allowed
                        ? 'Delete this saved report'
                        : 'Deleting a saved report needs report:view on this project.'
                    }
                    data-testid="Reports.Definitions.Delete"
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
  );
}
