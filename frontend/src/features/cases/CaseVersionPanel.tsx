import { Badge } from '../../components/kit';
import { Tooltip, TooltipContent, TooltipTrigger } from '../../components/ui';
import { Pin } from 'lucide-react';
import { cn } from '../../lib/cn';
import type { CaseVersion } from '../../api/queries';
import { useCaseVersions } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { formatAge, formatDateTime } from '../../lib/format';

/**
 * Who changed this case, when, and why — the panel beside the case, not a page of its own.
 *
 * It is deliberately **not** the execution history (`/history`, the "Execution history" button in
 * the header). That one answers *which runs executed which version* and lives on its own screen
 * because it is a timeline with filters. This answers *what did the authors do to this case*, which
 * is reference material a reader consults while looking at the case — the same argument the detail
 * page's own layout comment makes for putting classification in the sidebar rather than in the
 * reading path.
 *
 * ### The list is the server's, in the server's order
 *
 * Newest first, exactly as received, and never re-sorted here. `versionNo` is the sort key the
 * server used and sorting again locally is the shape of the defect §27 found on this very page,
 * where re-sorting the condition lists by `position` destroyed a resolution order the server had
 * just computed. There is nothing to gain and one way to be wrong.
 */
export function CaseVersionPanel({
  projectId,
  caseId,
  currentVersionNo,
}: {
  projectId: string;
  caseId: string;
  /** From the case itself, so "Current" marks the version the page is actually rendering. */
  currentVersionNo: number | undefined;
}) {
  const versions = useCaseVersions(projectId, caseId);

  return (
    <Panel title="Version history" testId="CaseDetail.Versions">
      <DataRegion
        testId="CaseDetail.Versions.Region"
        state={{ ...versions, refetch: () => void versions.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          title: 'No recorded history',
          /*
            Not an error, and not "nothing here". A case authored before this installation started
            recording versions has no rows at all, and the server answers 200 with an empty array
            rather than a 404 for exactly that reason. Saying WHY it is empty is the difference
            between a reader concluding "history is broken" and concluding "history starts now".
          */
          body: 'This case has not been edited since version history was recorded. Its next save will be its first recorded version.',
        }}
      >
        {(rows) => (
          <div className="flex flex-col gap-3">
            <div
              className="flex max-h-80 flex-col gap-3 overflow-y-auto"
              data-testid="CaseDetail.Versions.List"
            >
              {rows.map((version) => (
                <VersionRow
                  key={version.versionNo}
                  version={version}
                  isCurrent={version.versionNo === currentVersionNo}
                />
              ))}
            </div>
            <EarliestRecorded oldest={rows[rows.length - 1]} />
          </div>
        )}
      </DataRegion>
    </Panel>
  );
}

function VersionRow({ version, isCurrent }: { version: CaseVersion; isCurrent: boolean }) {
  return (
    <div
      data-testid="CaseDetail.Versions.Item"
      className={cn('border-l-2 pl-3', isCurrent ? 'border-steel' : 'border-line')}
    >
      <div className="mb-0.5 flex flex-wrap items-center gap-1.5">
        <Identifier variant="strong">v{version.versionNo}</Identifier>
        {isCurrent && (
          <Badge tone="steel" data-testid="CaseDetail.Versions.Current">
            Current
          </Badge>
        )}
        {/*
          A pinned version is referenced by a run, an approval or a generated report, which makes it
          permanently exempt from retention. Worth its own marker because it is the answer to "why
          can I not prune this" — and because a reader deleting old versions needs to see which ones
          a past report still depends on before, not after.
        */}
        {version.pinned && (
          <Tooltip>
            <TooltipTrigger asChild>
              <span data-testid="CaseDetail.Versions.Pinned">
                <Badge tone="plain">
                  <Pin aria-hidden className="size-3" />
                  Pinned
                </Badge>
              </span>
            </TooltipTrigger>
            <TooltipContent>
              A run, approval or report still references this version, so it is never pruned.
            </TooltipContent>
          </Tooltip>
        )}
      </div>

      <p className={cn('m-0 text-sm', version.changeComment ? 'text-ink' : 'text-ink-2')}>
        {/*
          `changeComment` is nullable and most saves have none. "No comment" rather than a blank
          line: an empty row reads as a rendering failure, and the reader cannot tell whether the
          author wrote nothing or the panel lost it.
        */}
        {version.changeComment ?? 'No comment'}
      </p>

      <span className="block text-xs text-ink-2">
        {/*
          `createdBy` is null when the author cannot be named — an imported version, or one whose
          recorded account no longer resolves. The contract says this field is always present and
          the data says otherwise (see `CaseVersion`); rendering "Unknown author" is the only honest
          answer, and inventing a name here would be a false claim about who changed a test.
        */}
        {version.createdBy?.displayName ?? 'Unknown author'}
        {' · '}
        <Tooltip>
          <TooltipTrigger asChild>
            <span>{formatAge(version.createdAt)}</span>
          </TooltipTrigger>
          <TooltipContent>{formatDateTime(version.createdAt)}</TooltipContent>
        </Tooltip>
      </span>
    </div>
  );
}

/**
 * The v1 gap, said out loud.
 *
 * A case that predates the write path has no v1 and never will: writing one would claim the edited
 * text was the original, and backfilling one from the current row would assert that the pre-edit
 * content *was* the post-edit content — in the one table whose purpose is that past claims stay
 * true. So the server serves the gap, and this line is what turns a gap a reader would take for a
 * bug into a fact about when this installation started recording.
 */
function EarliestRecorded({ oldest }: { oldest: CaseVersion | undefined }) {
  if (!oldest || oldest.versionNo === 1) return null;

  return (
    <p
      className="m-0 border-t border-line pt-3 text-xs text-ink-2"
      data-testid="CaseDetail.Versions.EarliestRecorded"
    >
      v{oldest.versionNo} is the earliest recorded version. This case is older than its history —
      what it said before then was not kept.
    </p>
  );
}
