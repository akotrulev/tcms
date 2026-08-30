import { useEffect, type ReactNode } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
import type { CaseHistoryResponse } from '../../api/queries';
import type { ExecutionHistoryEntry, StatusKind, VersionMarker } from '../../api/types';
import { useCase, useCaseHistory } from '../../api/queries';
import { TopStripSlot } from '../../app/topStripSlot';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
  Banner,
  RailRow,
  Timeline,
  TimelineEvent,
  type BadgeTone,
} from '../../components/kit';
import { formatAge, formatCount, formatDateTime, formatDuration, formatPercent, formatRatio, percentOf, plural } from '../../lib/format';
import { statusLabels } from '../../lib/labels';

/** `.sec h3` from `renders/tcms.css` — the one spelling of a section heading on this screen. */
const SECTION_HEADING = 'm-0 mb-2.5 text-[11px] font-semibold tracking-[.08em] text-ink-3 uppercase';

/**
 * A verdict's badge tone. `WIP` has no result colour of its own — it is a run in flight rather
 * than an outcome — so it reads as the plain badge every other non-result does.
 */
const statusTone = {
  PASSED: 'passed',
  FAILED: 'failed',
  BLOCKED: 'blocked',
  SKIPPED: 'skipped',
  RETEST: 'retest',
  UNTESTED: 'untested',
  WIP: 'plain',
} as const satisfies Record<StatusKind, BadgeTone>;

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

  const testCase = useCase(projectId, caseId);
  const history = useCaseHistory(projectId, caseId, scope);

  /*
    The tab title and the arrival announcement, raised here because this screen has no
    `PageHeader` (007 T11, following T7): design language rule 1 puts the crumb and the lens in the
    top strip and gives the width to the timeline, and `renders/02-record-as-document.html` panel E
    draws no page heading at all.
  */
  const pageTitle = testCase.data ? `History · ${testCase.data.key}` : 'Execution history';
  const announce = useAnnounce();
  useEffect(() => {
    announce(`${pageTitle} page loaded`);
  }, [announce, pageTitle]);

  return (
    <div className="flex min-h-full flex-col">
      <title>{`${pageTitle} · TCMS`}</title>

      <TopStripSlot>
        <nav
          aria-label="Breadcrumb"
          data-testid="CaseHistory.Breadcrumbs"
          className="mr-auto hidden min-w-0 truncate text-[12.5px] text-ink-3 sm:block"
        >
          <RouterLink to={`/projects/${projectId}/cases`} className="hover:underline">
            Test cases
          </RouterLink>
          {' / '}
          <RouterLink
            to={`/projects/${projectId}/cases/${caseId}`}
            className="hover:underline"
            data-testid="CaseHistory.CaseLink"
          >
            {testCase.data?.key ?? 'Case'}
          </RouterLink>
          {' / '}
          <span className="font-medium text-ink-2">History</span>
        </nav>

        {/*
          THE SCOPE LENS. Both readings are legitimate — *current version* answers "is this test
          passing as it stands", *all versions* answers "has this area ever been stable" — so the
          pair sits in the strip as the render's `.lens`, and the choice stays in the URL where
          every other filter in this product lives.
        */}
        <div className="flex items-center gap-0.5 rounded-md border border-line p-0.5">
          {(
            [
              { value: 'CURRENT_VERSION' as const, label: 'Current version' },
              { value: 'ALL_VERSIONS' as const, label: 'All versions' },
            ]
          ).map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={scope === option.value}
              onClick={() => {
                const next = new URLSearchParams(params);
                next.set('scope', option.value);
                setParams(next, { replace: true });
              }}
              data-testid={`CaseHistory.Scope.${option.value}`}
              className={
                'rounded-sm px-2.5 py-1 text-[12px] focus-visible:outline-2 focus-visible:outline-offset-[-2px] ' +
                'focus-visible:outline-[color:var(--color-given)] ' +
                (scope === option.value ? 'bg-wash-3 text-ink' : 'text-ink-3 hover:text-ink-2')
              }
            >
              {option.label}
            </button>
          ))}
        </div>
      </TopStripSlot>

      <DataRegion testId="CaseHistory.Content" state={{ ...history, refetch: () => void history.refetch() }}>
        {(data) => <HistoryBody data={data} projectId={projectId} scope={scope} />}
      </DataRegion>
    </div>
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
    <div className="flex min-w-0 flex-1 flex-col lg:flex-row">
      <div className="min-w-0 flex-1 overflow-auto bg-paper px-4 py-6 md:px-7">
        <h2 className={SECTION_HEADING}>Timeline</h2>
        <HistoryTimeline entries={entries} markers={versionMarkers} projectId={projectId} />
      </div>

      <aside className="w-full shrink-0 border-line bg-panel lg:w-[300px] lg:border-l">
        <section className="border-b border-line px-4 py-4" data-testid="CaseHistory.Summary">
          <h2 className={SECTION_HEADING}>
            Execution history{' '}
            <span className="font-mono text-[10.5px] tracking-normal text-ink-4 normal-case">
              {plural(summary.executions, 'execution')}
            </span>
          </h2>

          <RailRow
            readOnly
            label="Pass rate"
            value={<span className="font-mono tabular-nums">{formatPercent(percentOf(summary.passed, summary.executions))}</span>}
          />
          <RailRow
            readOnly
            label="Failed"
            value={
              <span
                className="font-mono tabular-nums"
                style={summary.failed > 0 ? { color: 'var(--color-failed)' } : undefined}
              >
                {formatCount(summary.failed)}
              </span>
            }
          />
          {summary.flakinessScore !== null && summary.flakinessScore !== undefined && (
            <RailRow
              readOnly
              label="Flakiness"
              value={
                <span
                  className="font-mono tabular-nums"
                  title="How often the result changed between consecutive runs on the same version. High means the test is unreliable, not that the feature is."
                >
                  {formatRatio(summary.flakinessScore, 0)}
                </span>
              }
            />
          )}

          {/* The scope is stated as prose, because "3 executions, 1 passed" is only meaningful
              once the reader knows the 3 are all against v7 and not across every revision. */}
          <p
            className="mt-3 mb-0 text-[12px] leading-[1.55] text-ink-4"
            data-testid="CaseHistory.Scope.Notice"
          >
            Scope: {summary.scope}.{' '}
            {scope === 'CURRENT_VERSION'
              ? 'Only results recorded against the version currently saved.'
              : 'Results across every version of this case — the test itself changed during this period.'}
          </p>

          {summary.firstFailedAt && (
            <Banner tone="warn" className="mt-3" data-testid="CaseHistory.FirstFailure">
              <span>
                First failed {formatDateTime(summary.firstFailedAt)}
                {summary.firstFailedInRelease && (
                  <>
                    {' '}
                    in release <Identifier variant="muted">{summary.firstFailedInRelease}</Identifier>
                  </>
                )}
                .
              </span>
            </Banner>
          )}
        </section>
      </aside>
    </div>
  );
}

type TimelineRow =
  | { kind: 'execution'; at: string; entry: ExecutionHistoryEntry }
  | { kind: 'version'; at: string; marker: VersionMarker };

/**
 * Executions and version changes merged into one descending timeline. Interleaving them is
 * the whole point — two separate lists leave the reader correlating timestamps by hand,
 * which is exactly the work this screen exists to remove.
 *
 * It is `kit/Timeline` since 007 T11, which is the same one column, the same mono gutter and the
 * same 7px dot the suite history and the audit trail draw — design language §5's *"the eye learns
 * it once"*. What was here was this screen's own rail, connector and dot, built before there was a
 * kit to take them from.
 */
function HistoryTimeline({
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
      <p className="m-0 text-[13px] text-ink-3" data-testid="CaseHistory.Timeline.Empty">
        No executions recorded in this scope yet.
      </p>
    );
  }

  return (
    <Timeline data-testid="CaseHistory.Timeline">
      {rows.map((row) =>
        row.kind === 'version' ? (
          <VersionEvent key={`v${row.marker.versionNo}`} marker={row.marker} />
        ) : (
          <ExecutionEvent key={row.entry.executionId} entry={row.entry} projectId={projectId} />
        ),
      )}
    </Timeline>
  );
}

/** The mono stamp in the gutter: the age, with the exact moment on the title. */
function stamp(at: string): ReactNode {
  return <span title={formatDateTime(at)}>{formatAge(at)}</span>;
}

function VersionEvent({ marker }: { marker: VersionMarker }) {
  return (
    <TimelineEvent
      when={stamp(marker.occurredAt)}
      highlighted
      data-testid="CaseHistory.Timeline.VersionMarker"
      detail={marker.changeComment ?? undefined}
    >
      {/* v1 is the case being authored, not edited — since §29 a created case gets its v1
          written alongside it. "Edited to v1" put a change at the bottom of every timeline
          that nobody made, which is the opposite of what a reader scanning for "what changed?"
          needs. Caught in the live browser pass, not by any gate. */}
      {/* `author` is genuinely nullable: test_case_version.created_by has no foreign key, so
          "nobody was recorded" and "the account is gone" are both real rows. Naming an author
          anyway would put a person's name on the one table whose purpose is that past claims
          stay true — so the edit is reported without one. */}
      <strong className="font-medium text-ink">
        {marker.author?.displayName ?? 'Unknown author'}
      </strong>{' '}
      {marker.versionNo === 1 ? 'created it' : 'edited the case'}
      <Badge tone="steel" className="ml-1.5">
        v{marker.versionNo}
      </Badge>
    </TimelineEvent>
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
  // A boolean about INGESTION, not a label — which is why §247 left it alone while replacing the
  // run list's ternary with `runSourceLabels`. A `SCHEDULED` result reads correctly here: it was
  // not ingested, and whoever recorded the verdict is a person the run's origin says nothing about.
  const ingested = entry.source === 'INGESTED';
  if (entry.executedByToken) {
    return `${ingested ? 'ingested by' : 'recorded by'} ${entry.executedByToken}`;
  }
  return ingested ? 'ingested by CI' : `by ${entry.executedBy?.displayName ?? 'unknown'}`;
}

function ExecutionEvent({
  entry,
  projectId,
}: {
  entry: ExecutionHistoryEntry;
  projectId: string;
}) {
  return (
    <TimelineEvent
      when={stamp(entry.executedAt)}
      data-testid="CaseHistory.Timeline.Execution"
      detail={
        <>
          {/* A CI token and a person are different kinds of author, and knowing which one
              recorded a result changes who you ask about it.

              It branches on the ACTOR, not on the run's source — which is a correction. The
              source is a property of the run and the actor is a property of this verdict, and
              the two came apart the moment executed_by_token got a writer: Automation Service
              holds result:submit, so a credential can post a single verdict into a MANUAL run,
              and keying off the source rendered that as "by <the owner's name>" — a person's
              name on a machine's claim, which is the one thing attribution must not do. */}
          {authorLine(entry)}
          {entry.configuration && <> · {entry.configuration}</>}
          {entry.releaseVersion && (
            <>
              {' · release '}
              <Identifier variant="muted">{entry.releaseVersion}</Identifier>
              {entry.buildNumber && (
                <>
                  {' build '}
                  <Identifier variant="muted">{entry.buildNumber}</Identifier>
                </>
              )}
            </>
          )}
          {entry.durationMs !== null && entry.durationMs !== undefined && (
            <>
              {' · '}
              <Identifier variant="muted">{formatDuration(entry.durationMs)}</Identifier>
            </>
          )}
          {entry.evidenceCount > 0 && (
            <>
              {' · '}
              {formatCount(entry.evidenceCount)}{' '}
              {entry.evidenceCount === 1 ? 'attachment' : 'attachments'}
            </>
          )}
          {entry.defects.length > 0 && (
            <span className="mt-1 flex flex-wrap gap-2">
              {entry.defects.map((defect) => (
                <a
                  key={defect.id}
                  href={defect.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-[11.5px] text-steel underline-offset-4 hover:underline"
                  data-testid="CaseHistory.Timeline.DefectLink"
                >
                  <Identifier>{defect.externalKey}</Identifier>
                  {defect.statusCache && <span className="ml-1 text-ink-4">{defect.statusCache}</span>}
                  <span aria-hidden="true" className="ml-1 text-ink-4">
                    ↗
                  </span>
                  <span className="sr-only">(opens in a new tab)</span>
                </a>
              ))}
            </span>
          )}
        </>
      }
    >
      Executed in{' '}
      {/* A withdrawn run keeps its result on this timeline — §6p, "the entry marked rather than
          vanishing" — but the run itself answers 404, so the key is rendered as plain text
          instead of a link. Offering navigation that lands on a not-found page reads as a
          broken app rather than as a deleted run, which is why the badge beside it says which. */}
      {entry.runDeleted ? (
        <span title="This run was deleted. The result is kept, because history that quietly loses rows is worse than no history.">
          <Identifier variant="muted">{entry.runKey}</Identifier>
          <Badge tone="plain" className="ml-1.5" data-testid="CaseHistory.Timeline.RunDeleted">
            Run deleted
          </Badge>
        </span>
      ) : (
        <RouterLink
          to={`/projects/${projectId}/runs/${entry.runId}`}
          className="text-steel underline-offset-4 hover:underline"
          data-testid="CaseHistory.Timeline.RunLink"
        >
          <Identifier>{entry.runKey}</Identifier>
        </RouterLink>
      )}{' '}
      <span className="text-ink-3">{entry.runName}</span>
      {entry.statusKind && (
        <>
          {' — '}
          <Badge tone={statusTone[entry.statusKind]} data-testid="CaseHistory.Timeline.Status">
            {statusLabels[entry.statusKind]}
          </Badge>
        </>
      )}
      {/* Which version ran. On the all-versions view this is what stops a reader from
          attributing an old failure to the current text. */}
      <span className="ml-1.5" title="The case version this run executed">
        <Identifier variant="muted">v{entry.caseVersionNo}</Identifier>
      </span>
    </TimelineEvent>
  );
}
