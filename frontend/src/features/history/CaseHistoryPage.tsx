import { Alert, Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import type { CaseHistoryResponse } from '../../api/queries';
import type { ExecutionHistoryEntry, VersionMarker } from '../../api/types';
import { useCase, useCaseHistory, useProject } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { StatusChip } from '../../components/StatusChip';
import { formatCount, formatDateTime, formatDuration, formatPercent, percentOf } from '../../lib/format';

/**
 * Execution history for one case (plan §6p).
 *
 * The design problem this screen solves: a case that has failed for five nights running looks
 * identical to a case that was edited and *then* started failing — unless the timeline shows
 * the edit. So version changes are interleaved into the run history as markers. The shape
 * "green, green, **v5 edited**, red, red, red" answers "what changed?" without opening a diff,
 * and that is the single most common question asked of a failing test.
 *
 * The scope toggle exists because both readings are legitimate. *Current version* answers "is
 * this test passing as it stands"; *all versions* answers "has this area ever been stable".
 * Mixing them silently — reporting a pass rate over results from four different revisions of
 * the test — is the misreading the toggle prevents.
 */
export function CaseHistoryPage() {
  const { projectId = '', caseId = '' } = useParams();
  const [params, setParams] = useSearchParams();

  const scope = (params.get('scope') ?? 'CURRENT_VERSION') as 'CURRENT_VERSION' | 'ALL_VERSIONS';

  const project = useProject(projectId);
  const testCase = useCase(projectId, caseId);
  const history = useCaseHistory(projectId, caseId, scope);

  return (
    <Box>
      <PageHeader
        testId="CaseHistory.Header"
        title="Execution history"
        documentTitle={testCase.data ? `History · ${testCase.data.key}` : 'Execution history'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test cases', to: `/projects/${projectId}/cases` },
          { label: testCase.data?.key ?? 'Case', to: `/projects/${projectId}/cases/${caseId}` },
          { label: 'History' },
        ]}
        meta={
          testCase.data && (
            <>
              <RouterLink to={`/projects/${projectId}/cases/${caseId}`} data-testid="CaseHistory.CaseLink">
                <Identifier variant="strong">{testCase.data.key}</Identifier>
              </RouterLink>
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                {testCase.data.title}
              </Typography>
            </>
          )
        }
        actions={
          <Stack direction="row" sx={{ gap: 0.5 }}>
            {(
              [
                { value: 'CURRENT_VERSION' as const, label: 'Current version' },
                { value: 'ALL_VERSIONS' as const, label: 'All versions' },
              ]
            ).map((option) => (
              <Chip
                key={option.value}
                label={option.label}
                onClick={() => {
                  const next = new URLSearchParams(params);
                  next.set('scope', option.value);
                  setParams(next, { replace: true });
                }}
                variant={scope === option.value ? 'filled' : 'outlined'}
                color={scope === option.value ? 'primary' : 'default'}
                aria-pressed={scope === option.value}
                data-testid={`CaseHistory.Scope.${option.value}`}
                sx={{ cursor: 'pointer' }}
              />
            ))}
          </Stack>
        }
      />

      <DataRegion testId="CaseHistory.Content" state={{ ...history, refetch: () => void history.refetch() }}>
        {(data) => <HistoryBody data={data} projectId={projectId} scope={scope} />}
      </DataRegion>
    </Box>
  );
}

function HistoryBody({
  data,
  projectId,
  scope,
}: {
  data: CaseHistoryResponse;
  projectId: string;
  scope: 'CURRENT_VERSION' | 'ALL_VERSIONS';
}) {
  const { summary, entries, versionMarkers } = data;

  return (
    <Stack sx={{ gap: 3 }}>
      {/* The scope is stated as prose, because "3 executions, 1 passed" is only meaningful
          once the reader knows the 3 are all against v7 and not across every revision. */}
      <Panel testId="CaseHistory.Summary">
        <Stack direction="row" sx={{ gap: 4, flexWrap: 'wrap', mb: 1.5 }}>
          <Metric
            value={formatPercent(percentOf(summary.passed, summary.executions))}
            label={`passed, of ${formatCount(summary.executions)} executions`}
          />
          <Metric value={formatCount(summary.failed)} label="failed" />
          {summary.flakinessScore !== null && summary.flakinessScore !== undefined && (
            <Metric
              value={formatPercent(summary.flakinessScore * 100, 0)}
              label="flakiness"
              help="How often the result changed between consecutive runs on the same version. High means the test is unreliable, not that the feature is."
            />
          )}
        </Stack>
        <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid="CaseHistory.Scope.Notice">
          Scope: {summary.scope}.{' '}
          {scope === 'CURRENT_VERSION'
            ? 'Only results recorded against the version currently saved.'
            : 'Results across every version of this case — the test itself changed during this period.'}
        </Typography>

        {summary.firstFailedAt && (
          <Alert severity="warning" sx={{ mt: 2 }} data-testid="CaseHistory.FirstFailure">
            <Typography variant="body2">
              First failed {formatDateTime(summary.firstFailedAt)}
              {summary.firstFailedInRelease && (
                <>
                  {' '}
                  in release <Identifier variant="muted">{summary.firstFailedInRelease}</Identifier>
                </>
              )}
              .
            </Typography>
          </Alert>
        )}
      </Panel>

      <Panel title="Timeline" testId="CaseHistory.Timeline" dense>
        <Box sx={{ p: 2 }}>
          <Timeline entries={entries} markers={versionMarkers} projectId={projectId} />
        </Box>
      </Panel>
    </Stack>
  );
}

function Metric({ value, label, help }: { value: string; label: string; help?: string }) {
  const content = (
    <Box>
      <Typography className="identifier" sx={{ fontSize: '1.75rem', fontWeight: 600, lineHeight: 1 }}>
        {value}
      </Typography>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        {label}
      </Typography>
    </Box>
  );
  return help ? <Tooltip title={help}>{content}</Tooltip> : content;
}

type TimelineRow =
  | { kind: 'execution'; at: string; entry: ExecutionHistoryEntry }
  | { kind: 'version'; at: string; marker: VersionMarker };

/**
 * Executions and version changes merged into one descending timeline. Interleaving them is
 * the whole point — two separate lists leave the reader correlating timestamps by hand,
 * which is exactly the work this screen exists to remove.
 */
function Timeline({
  entries,
  markers,
  projectId,
}: {
  entries: ExecutionHistoryEntry[];
  markers: VersionMarker[];
  projectId: string;
}) {
  const rows: TimelineRow[] = [
    ...entries.map((entry) => ({ kind: 'execution' as const, at: entry.executedAt, entry })),
    ...markers.map((marker) => ({ kind: 'version' as const, at: marker.occurredAt, marker })),
  ].sort((a, b) => b.at.localeCompare(a.at));

  if (rows.length === 0) {
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid="CaseHistory.Timeline.Empty">
        No executions recorded in this scope yet.
      </Typography>
    );
  }

  return (
    <Stack sx={{ gap: 0 }}>
      {rows.map((row, index) =>
        row.kind === 'version' ? (
          <VersionRow key={`v${row.marker.versionNo}`} marker={row.marker} last={index === rows.length - 1} />
        ) : (
          <ExecutionRow
            key={row.entry.executionId}
            entry={row.entry}
            projectId={projectId}
            last={index === rows.length - 1}
          />
        ),
      )}
    </Stack>
  );
}

/** The rail that makes the two row types read as one sequence. */
function Rail({ color, last, filled }: { color: string; last: boolean; filled: boolean }) {
  return (
    <Stack sx={{ alignItems: 'center', width: 24, flexShrink: 0 }}>
      <Box
        aria-hidden="true"
        sx={{
          width: 10,
          height: 10,
          borderRadius: '50%',
          mt: 0.5,
          flexShrink: 0,
          bgcolor: filled ? color : 'background.paper',
          border: `2px solid ${color}`,
        }}
      />
      {!last && <Box aria-hidden="true" sx={{ width: 2, flex: 1, minHeight: 24, bgcolor: 'divider' }} />}
    </Stack>
  );
}

function VersionRow({ marker, last }: { marker: VersionMarker; last: boolean }) {
  return (
    <Stack direction="row" sx={{ gap: 1.5 }} data-testid="CaseHistory.Timeline.VersionMarker">
      <Rail color="var(--color-action-primary)" last={last} filled={false} />
      <Box sx={{ pb: 2.5, flex: 1, minWidth: 0 }}>
        <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
          {/* v1 is the case being authored, not edited — since §29 a created case gets its v1
              written alongside it. "Edited to v1" put a change at the bottom of every timeline
              that nobody made, which is the opposite of what a reader scanning for "what changed?"
              needs. Caught in the live browser pass, not by any gate. */}
          <Chip
            size="small"
            label={marker.versionNo === 1 ? 'Created at v1' : `Edited to v${marker.versionNo}`}
            sx={{
              bgcolor: 'color-mix(in srgb, var(--color-action-primary) 12%, transparent)',
              color: 'var(--color-action-primary)',
              border: '1px solid color-mix(in srgb, var(--color-action-primary) 40%, transparent)',
              fontWeight: 600,
            }}
          />
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {/* `author` is genuinely nullable: test_case_version.created_by has no foreign key, so
                "nobody was recorded" and "the account is gone" are both real rows. Naming an author
                anyway would put a person's name on the one table whose purpose is that past claims
                stay true — so the edit is reported without one. */}
            {marker.author?.displayName ?? 'Unknown author'} · {formatDateTime(marker.occurredAt)}
          </Typography>
        </Stack>
        {marker.changeComment && (
          <Typography variant="body2" sx={{ mt: 0.5, color: 'text.secondary' }}>
            {marker.changeComment}
          </Typography>
        )}
      </Box>
    </Stack>
  );
}

/**
 * Who recorded this verdict — a credential's name, a person's, or neither.
 *
 * The verb still comes from the run's source, because "ingested" and "recorded" are genuinely
 * different events; the *name* comes from whichever actor field is populated. `executedByToken` is
 * the token's name (the server resolves the id), and it is null both for a person's verdict and for
 * an ingestion posted with a session rather than a credential — the second is why 'CI' is still the
 * fallback on an INGESTED row and not a bug.
 */
function authorLine(entry: ExecutionHistoryEntry): string {
  const ingested = entry.source === 'INGESTED';
  if (entry.executedByToken) {
    return `${ingested ? 'ingested by' : 'recorded by'} ${entry.executedByToken}`;
  }
  return ingested ? 'ingested by CI' : `by ${entry.executedBy?.displayName ?? 'unknown'}`;
}

function ExecutionRow({
  entry,
  projectId,
  last,
}: {
  entry: ExecutionHistoryEntry;
  projectId: string;
  last: boolean;
}) {
  return (
    <Stack direction="row" sx={{ gap: 1.5 }} data-testid="CaseHistory.Timeline.Execution">
      <Rail color={entry.statusColor} last={last} filled />
      <Box sx={{ pb: 2.5, flex: 1, minWidth: 0 }}>
        <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
          {entry.statusKind && <StatusChip kind={entry.statusKind} testId="CaseHistory.Timeline.Status" />}
          {/* A withdrawn run keeps its result on this timeline — §6p, "the entry marked rather than
              vanishing" — but the run itself answers 404, so the key is rendered as plain text
              instead of a link. Offering navigation that lands on a not-found page reads as a
              broken app rather than as a deleted run, which is why the chip beside it says which. */}
          {entry.runDeleted ? (
            <Tooltip title="This run was deleted. The result is kept, because history that quietly loses rows is worse than no history.">
              <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
                <Identifier variant="muted">{entry.runKey}</Identifier>
                <Chip
                  size="small"
                  label="Run deleted"
                  data-testid="CaseHistory.Timeline.RunDeleted"
                  sx={{ height: 20, fontSize: '0.6875rem', color: 'text.secondary' }}
                />
              </Box>
            </Tooltip>
          ) : (
            <RouterLink to={`/projects/${projectId}/runs/${entry.runId}`} data-testid="CaseHistory.Timeline.RunLink">
              <Identifier>{entry.runKey}</Identifier>
            </RouterLink>
          )}
          <Typography variant="body2" sx={{ color: 'text.secondary', minWidth: 0 }}>
            {entry.runName}
          </Typography>
          {/* Which version ran. On the all-versions view this is what stops a reader from
              attributing an old failure to the current text. */}
          <Tooltip title="The case version this run executed">
            <Box>
              <Identifier variant="muted">v{entry.caseVersionNo}</Identifier>
            </Box>
          </Tooltip>
        </Stack>

        <Stack direction="row" sx={{ gap: 2, flexWrap: 'wrap', mt: 0.5 }}>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {formatDateTime(entry.executedAt)}
          </Typography>
          {entry.releaseVersion && (
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              release <Identifier variant="muted">{entry.releaseVersion}</Identifier>
              {entry.buildNumber && (
                <>
                  {' '}
                  build <Identifier variant="muted">{entry.buildNumber}</Identifier>
                </>
              )}
            </Typography>
          )}
          {entry.configuration && (
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              {entry.configuration}
            </Typography>
          )}
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {/* A CI token and a person are different kinds of author, and knowing which one
                recorded a result changes who you ask about it.

                It branches on the ACTOR, not on the run's source — which is a correction. The
                source is a property of the run and the actor is a property of this verdict, and
                the two came apart the moment executed_by_token got a writer: Automation Service
                holds result:submit, so a credential can post a single verdict into a MANUAL run,
                and keying off the source rendered that as "by <the owner's name>" — a person's
                name on a machine's claim, which is the one thing attribution must not do. */}
            {authorLine(entry)}
          </Typography>
          {entry.durationMs !== null && entry.durationMs !== undefined && (
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              <Identifier variant="muted">{formatDuration(entry.durationMs)}</Identifier>
            </Typography>
          )}
        </Stack>

        {entry.defects.length > 0 && (
          <Stack direction="row" sx={{ gap: 1, mt: 0.5, flexWrap: 'wrap' }}>
            {entry.defects.map((defect) => (
              <Box
                key={defect.id}
                component="a"
                href={defect.url}
                target="_blank"
                rel="noopener"
                sx={{ fontSize: '0.75rem' }}
                data-testid="CaseHistory.Timeline.DefectLink"
              >
                <Identifier>{defect.externalKey}</Identifier>
                {defect.statusCache && (
                  <Box component="span" sx={{ color: 'text.secondary', ml: 0.5 }}>
                    {defect.statusCache}
                  </Box>
                )}
                <Box component="span" aria-hidden="true" sx={{ ml: 0.5, color: 'text.secondary' }}>
                  ↗
                </Box>
                <Box component="span" className="sr-only">
                  (opens in a new tab)
                </Box>
              </Box>
            ))}
          </Stack>
        )}

        {entry.evidenceCount > 0 && (
          <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mt: 0.25 }}>
            {formatCount(entry.evidenceCount)} {entry.evidenceCount === 1 ? 'attachment' : 'attachments'}
          </Typography>
        )}
      </Box>
    </Stack>
  );
}
