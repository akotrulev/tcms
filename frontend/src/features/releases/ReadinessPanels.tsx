import { Clock } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import type { ReleaseBlocker, ReleaseReadiness, ReleaseTests, RoadmapEpic } from '../../api/types';
import { EmptyState } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Button } from '../../components/ui';
import { formatAge, formatDateTime, plural } from '../../lib/format';
import { StatusBadge } from '../tracker/StatusBadge';
import { TypeIcon } from '../tracker/TypeIcon';
import { blockerVerdict } from './releaseBadge';

/**
 * `release.html`'s panels — the three tiles, the blockers table, the epics and the test evidence.
 *
 * Split out of `ReleaseDetailPage` because the page is otherwise a header, a banner and five
 * regions, and each of these panels has one rule worth reading on its own.
 *
 * <h2>The rule every panel here shares: `tests.hasRun` gates, counts never do (D-d)</h2>
 *
 * When it is false, `passed`, `failed`, `blocked`, `untested` and `withResult` are **null**, and the
 * two panels that would draw them render a sentence instead. `?? 0` appears only *inside* a
 * `hasRun` branch, never as the branch — a zero drawn as a bar is a clean release, and that is the
 * one claim this module must not make on a version nothing has run against.
 *
 * <h2>There is no `blocked` story count, and the mockup draws one</h2>
 *
 * `IssueStatusKind` has three members (D2), so "blocked" is a status *name* a project chose and not
 * a class the contract can count. `ReleaseStories` therefore carries `inProgress` and `notStarted`
 * and no third number, and the Stories tile lists those two. The blockers table below carries each
 * story's real status **by name**, which is where a reader sees the word "Blocked" — on the row that
 * actually holds it.
 */

/**
 * Stories · Linked test cases · Scope change.
 *
 * `Tile` from the kit is not used: it is a `<button>` that navigates to its rows, and none of these
 * three has rows to navigate to — the stories are the blockers table below, the cases are the
 * evidence panel, and the scope count has no list on the wire at all. A tile that looked clickable
 * and was not is the affordance defect `Badge` and `Chip` exist to keep apart.
 */
export function ReadinessTiles({
  readiness,
  projectId,
  testId,
}: {
  readiness: ReleaseReadiness;
  projectId: string;
  testId: string;
}) {
  const { stories, tests, scopeAddedAfterCreation } = readiness;

  return (
    <div className="mb-4 grid gap-3 md:grid-cols-3">
      <section
        data-testid={`${testId}.Stories`}
        className="rounded-lg border border-line bg-panel px-[15px] py-[13px]"
      >
        <TileHead label="Stories" value={`${stories.done} / ${stories.total}`} />
        <TallMeter
          segments={[
            { key: 'done', count: stories.done, color: 'var(--color-passed)' },
            { key: 'progress', count: stories.inProgress, color: 'var(--color-steel)' },
          ]}
          total={stories.total}
        />
        <TileFoot>
          <span>{`${stories.inProgress} in progress`}</span>
          <span>{`${stories.notStarted} not started`}</span>
          {stories.excludedSpikes > 0 && (
            // The denominator is smaller than the release's issue list, and an unexplained mismatch
            // is read as a wrong number (D8 — the same sentence the epic meter carries).
            <span data-testid={`${testId}.Stories.Excluded`}>
              {`${plural(stories.excludedSpikes, 'spike')} excluded`}
            </span>
          )}
        </TileFoot>
      </section>

      <section
        data-testid={`${testId}.Tests`}
        className="rounded-lg border border-line bg-panel px-[15px] py-[13px]"
      >
        <TileHead
          label="Linked test cases"
          value={tests.hasRun ? `${tests.passed ?? 0} / ${tests.linkedCases}` : 'no run yet'}
        />
        {tests.hasRun ? (
          <>
            <TallMeter
              segments={[
                { key: 'passed', count: tests.passed ?? 0, color: 'var(--color-passed)' },
                { key: 'failed', count: tests.failed ?? 0, color: 'var(--color-failed)' },
                { key: 'blocked', count: tests.blocked ?? 0, color: 'var(--color-blocked)' },
              ]}
              total={tests.linkedCases}
            />
            <TileFoot>
              <span>{`${tests.failed ?? 0} failed`}</span>
              {tests.latestExecutedAt && (
                <span title={formatDateTime(tests.latestExecutedAt)}>
                  {`latest run ${formatAge(tests.latestExecutedAt)}`}
                </span>
              )}
            </TileFoot>
          </>
        ) : (
          // The mockup's *"If nothing had run"* panel, in the place the meter would have been. It
          // replaces the bar rather than sitting beside it: a bar and this sentence together would
          // be the contradiction the sentence exists to prevent.
          <UntestedBlock
            projectId={projectId}
            version={readiness.version}
            testId={`${testId}.Tests.NoRun`}
            linkedCases={tests.linkedCases}
          />
        )}
      </section>

      <section
        data-testid={`${testId}.Scope`}
        className="rounded-lg border border-line bg-panel px-[15px] py-[13px]"
      >
        <TileHead label="Scope change" value={`+${scopeAddedAfterCreation}`} />
        <TallMeter
          segments={[
            {
              key: 'added',
              count: scopeAddedAfterCreation,
              color: 'var(--color-viz-2)',
            },
          ]}
          total={Math.max(stories.total, scopeAddedAfterCreation)}
        />
        <TileFoot>
          <span>
            {`${plural(scopeAddedAfterCreation, 'story')} added after the release was created`}
          </span>
          {/*
            D-f, and it is on screen rather than only in the plan: this is the one number here that
            comes from `issue_fact`, whose delivery has a one-minute floor. Every other figure on
            this page is a live `issue` read. A reader comparing this against the blockers table a
            second after somebody re-targeted a story deserves to know which of the two can lag.
          */}
          <span data-testid={`${testId}.Scope.AsOf`} className="italic">
            as of the last projection tick — up to a minute behind the counts beside it
          </span>
        </TileFoot>
      </section>
    </div>
  );
}

/** `release.html`'s **What stands between here and shipping** — the mockup's five columns. */
export function BlockersPanel({
  blockers,
  testId,
}: {
  blockers: readonly ReleaseBlocker[];
  testId: string;
}) {
  return (
    <Panel
      title="What stands between here and shipping"
      testId={testId}
      dense
      actions={<span className="text-[11.5px] text-ink-4">{plural(blockers.length, 'item')}</span>}
    >
      {blockers.length === 0 ? (
        <EmptyState
          testId={`${testId}.Empty`}
          kind="by-design"
          title="Nothing is outstanding"
          body="Every counted story is done and every linked case passed in the latest result recorded against this version."
        />
      ) : (
        <ScrollX>
          <Table data-testid={`${testId}.Table`}>
            <TableHead>
              <TableRow>
                <TableHeaderCell>Key</TableHeaderCell>
                <TableHeaderCell>Story</TableHeaderCell>
                <TableHeaderCell>Status</TableHeaderCell>
                <TableHeaderCell>Linked cases</TableHeaderCell>
                <TableHeaderCell>Verdict</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {blockers.map((blocker) => (
                <BlockerRow key={blocker.key} blocker={blocker} testId={testId} />
              ))}
            </TableBody>
          </Table>
        </ScrollX>
      )}
    </Panel>
  );
}

function BlockerRow({ blocker, testId }: { blocker: ReleaseBlocker; testId: string }) {
  const verdict = blockerVerdict(blocker);

  return (
    <TableRow
      data-testid={`${testId}.Row`}
      // The mockup tints the failing row. `color-mix` over the token rather than an alpha literal:
      // a `-white/` alpha in `src/` fails `tokenSystem.test.ts`, and a failed-tinted row has to be
      // visible over both palettes.
      style={
        verdict.failing
          ? { background: 'color-mix(in oklab, var(--color-failed) 8%, transparent)' }
          : undefined
      }
    >
      <TableCell>
        <Identifier variant="muted">{blocker.key}</Identifier>
      </TableCell>
      <TableCell variant="title">
        <span className="flex items-center gap-2">
          <TypeIcon type={blocker.type} />
          <RouterLink
            to={`/issues/${encodeURIComponent(blocker.key)}`}
            data-testid={`${testId}.Row.Title`}
            className="text-inherit no-underline hover:text-steel hover:underline"
          >
            {blocker.title}
          </RouterLink>
        </span>
      </TableCell>
      <TableCell>
        <StatusBadge status={blocker.status} data-testid={`${testId}.Row.Status`} />
      </TableCell>
      <TableCell>
        <span className="flex flex-wrap items-center gap-1.5">
          {blocker.casesPassed > 0 && <Badge tone="passed">{`${blocker.casesPassed} pass`}</Badge>}
          {blocker.casesFailed > 0 && <Badge tone="failed">{`${blocker.casesFailed} fail`}</Badge>}
          {blocker.casesBlocked > 0 && (
            <Badge tone="blocked">{`${blocker.casesBlocked} blocked`}</Badge>
          )}
          {blocker.casesUntested > 0 && (
            <Badge tone="untested">{`${blocker.casesUntested} untested`}</Badge>
          )}
          {/*
            No badge at all when nothing is linked, and the words say so. A `0 pass` badge would
            claim a measurement; "none linked" says the story has no proof attached, which is a
            different and more actionable fact — it is what the story's own Linked cases panel (T6)
            is for.
          */}
          {blocker.casesPassed +
            blocker.casesFailed +
            blocker.casesBlocked +
            blocker.casesUntested ===
            0 && (
            <span data-testid={`${testId}.Row.NoCases`} className="text-ink-4">
              none linked
            </span>
          )}
        </span>
      </TableCell>
      <TableCell>
        <span
          data-testid={`${testId}.Row.Verdict`}
          className="text-[12px]"
          style={verdict.failing ? { color: 'var(--color-failed)' } : undefined}
        >
          {verdict.failing ? verdict.text : <span className="text-ink-3">{verdict.text}</span>}
        </span>
      </TableCell>
    </TableRow>
  );
}

/** The epics targeting this release, each with the server's own completion percentage (D8). */
export function EpicsPanel({
  version,
  epics,
  testId,
}: {
  version: string;
  epics: readonly RoadmapEpic[];
  testId: string;
}) {
  return (
    <Panel title={`Epics in ${version}`} testId={testId}>
      {epics.length === 0 ? (
        <EmptyState
          testId={`${testId}.Empty`}
          kind="by-design"
          title="No epics point at this release"
          body="A story can target a release without its epic doing so. Set an epic's target release on the epic itself, and it appears here and on the roadmap."
        />
      ) : (
        <div className="flex flex-col gap-3">
          {epics.map((epic) => (
            <RouterLink
              key={epic.key}
              to={`/issues/${encodeURIComponent(epic.key)}`}
              data-testid={`${testId}.Epic`}
              className="flex items-center gap-3 rounded-md border border-line px-3 py-2.5 no-underline hover:border-line-strong"
            >
              <TypeIcon type={{ name: 'Epic', level: 'EPIC' }} />
              <span className="min-w-0 flex-1">
                <span className="mb-[5px] flex items-center gap-2">
                  <strong className="truncate text-[12.5px] text-ink">{epic.title}</strong>
                  <span className="flex-1" />
                  {/*
                    Null when the epic counts nothing — an epic of Spikes. The em dash is the honest
                    reading; a `0%` would say the work has not started.
                  */}
                  <Identifier variant="muted">
                    {epic.completionPercent === null || epic.completionPercent === undefined
                      ? '—'
                      : `${epic.completionPercent}%`}
                  </Identifier>
                </span>
                <span
                  aria-hidden
                  className="flex h-1.5 overflow-hidden rounded-full bg-wash-3"
                >
                  <span
                    className="block h-full bg-[color:var(--color-passed)]"
                    style={{ width: `${epic.completionPercent ?? 0}%` }}
                  />
                </span>
              </span>
            </RouterLink>
          ))}
        </div>
      )}
    </Panel>
  );
}

/**
 * `release.html`'s **Test evidence** — the run readiness was computed from, and its failures.
 *
 * The panel foot is the mockup's and it is the sentence that stops the whole number being
 * misunderstood: *readiness reads the latest result per case, not the best one*. A release cannot be
 * made green by re-running one case until it passes and leaving the others where they were.
 */
export function TestEvidencePanel({
  tests,
  version,
  projectId,
  failedCaseKeys,
  testId,
}: {
  tests: ReleaseTests;
  version: string;
  projectId: string;
  /** Collected from the blockers, because `ReleaseTests` carries a count and not the keys. */
  failedCaseKeys: readonly string[];
  testId: string;
}) {
  if (!tests.hasRun) {
    return (
      <Panel title="Test evidence" testId={testId}>
        <UntestedBlock
          projectId={projectId}
          version={version}
          testId={`${testId}.NoRun`}
          linkedCases={tests.linkedCases}
        />
      </Panel>
    );
  }

  return (
    <Panel title="Test evidence" testId={testId}>
      <dl className="m-0 grid grid-cols-[minmax(0,96px)_minmax(0,1fr)] gap-x-3 gap-y-2 text-[12.5px]">
        <dt className="text-ink-4">Latest run</dt>
        <dd className="m-0 text-ink-2">
          {tests.latestRunId ? (
            <RouterLink
              to={`/projects/${projectId}/runs/${tests.latestRunId}`}
              data-testid={`${testId}.Run`}
              className="text-steel no-underline hover:underline"
            >
              {tests.latestRunName ?? 'the run'}
            </RouterLink>
          ) : (
            <span className="text-ink-4">&mdash;</span>
          )}
          {tests.latestExecutedAt && (
            <span className="text-ink-4">{` · ${formatAge(tests.latestExecutedAt)}`}</span>
          )}
        </dd>

        <dt className="text-ink-4">Executed by</dt>
        <dd className="m-0 text-ink-2">
          {tests.executedBy?.displayName ?? <span className="text-ink-4">&mdash;</span>}
        </dd>

        <dt className="text-ink-4">Cases linked</dt>
        <dd className="m-0 text-ink-2">
          {`${plural(tests.linkedCases, 'case')}, ${tests.withResult ?? 0} with a result`}
        </dd>

        <dt className="text-ink-4">Failed</dt>
        <dd className="m-0">
          {failedCaseKeys.length > 0 ? (
            <span data-testid={`${testId}.Failed`} style={{ color: 'var(--color-failed)' }}>
              {failedCaseKeys.join(', ')}
            </span>
          ) : (
            <span className="text-ink-4">none</span>
          )}
        </dd>
      </dl>

      {tests.latestRunId && (
        <div className="mt-3">
          <Button size="sm" asChild>
            <RouterLink to={`/projects/${projectId}/runs/${tests.latestRunId}`}>
              Open the run
            </RouterLink>
          </Button>
        </div>
      )}

      <p className="mt-3 mb-0 border-t border-line pt-2.5 text-[11.5px] text-ink-4">
        Readiness reads the <em>latest</em> result per case, not the best one.
      </p>
    </Panel>
  );
}

/**
 * *"No test run against 2.5"* — the mockup's own words, in both places `hasRun` is false.
 *
 * One component, two call sites, because the sentence is the requirement: *"an untested version and
 * a clean version are not the same claim"*. Two copies would let one of them drift into "0 failed".
 */
function UntestedBlock({
  projectId,
  version,
  linkedCases,
  testId,
}: {
  projectId: string;
  version: string;
  linkedCases: number;
  testId: string;
}) {
  return (
    <EmptyState
      testId={testId}
      kind="by-design"
      icon={Clock}
      title={`No test run against ${version}`}
      body={
        linkedCases > 0
          ? `${plural(linkedCases, 'case')} are linked to this release's stories and none has a `
            + 'result recorded against this version. That is not zero failures — it is no evidence.'
          : 'Nothing is linked to this release’s stories yet, so there is nothing to run. '
            + 'Link cases from a story to give this version something to prove.'
      }
      action={
        <Button size="sm" asChild>
          <RouterLink to={`/projects/${projectId}/plans`} data-testid={`${testId}.Plan`}>
            Plan a run
          </RouterLink>
        </Button>
      }
    />
  );
}

function TileHead({ label, value }: { label: string; value: string }) {
  return (
    <div className="mb-2 flex items-center justify-between gap-2">
      <strong className="text-[12.5px] text-ink">{label}</strong>
      <Identifier variant="muted">{value}</Identifier>
    </div>
  );
}

function TileFoot({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-2 flex flex-wrap gap-x-2.5 gap-y-1 text-[11.5px] text-ink-4">{children}</div>
  );
}

/** The tiles' 9px stacked bar. Segments are drawn in order and never sum past the total. */
function TallMeter({
  segments,
  total,
}: {
  segments: readonly { key: string; count: number; color: string }[];
  total: number;
}) {
  return (
    <span aria-hidden className="flex h-[9px] overflow-hidden rounded-full bg-wash-2">
      {segments
        .filter((segment) => segment.count > 0)
        .map((segment) => (
          <span
            key={segment.key}
            className="block h-full"
            style={{
              width: `${total > 0 ? (segment.count / total) * 100 : 0}%`,
              backgroundColor: segment.color,
            }}
          />
        ))}
    </span>
  );
}
