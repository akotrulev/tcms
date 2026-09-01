import { Link as RouterLink, useParams } from 'react-router-dom';
import { CalendarRange } from 'lucide-react';
import { useProject, useReleases } from '../../api/queries';
import type { ReleaseSummary } from '../../api/types';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import {
  Badge,
  Banner,
  Table,
  TableBody,
  TableCell,
  TableFoot,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Button } from '../../components/ui';
import { formatDate, plural } from '../../lib/format';
import { releaseBadge } from './releaseBadge';

/**
 * `releases.html` — every release in the project, with the one answer per row.
 *
 * <h2>The Tests cell says `no run yet`, and that is the module's criterion (D-d)</h2>
 *
 * A version nothing has run against is not a version with no failures. `ReadinessSummary.hasRun` is
 * on the wire beside the verdict for exactly this, and `casesPassed` / `casesFailed` are **null
 * rather than zero** when it is false — so the cell renders the mockup's literal words instead of a
 * meter. `tcmsFaults.release('untested')` is the switch that makes the state reachable, and it
 * landed before this screen.
 *
 * A `?? 0` anywhere between that null and the bar would turn "nothing has run" into "nothing
 * failed", which is why the branch is on `hasRun` and never on a count.
 *
 * <h2>The Readiness column's vocabulary diverges from the mockup, deliberately (D-e)</h2>
 *
 * `releaseBadge` owns that decision and the reason is written there. Everything else on this screen
 * is the mockup's, down to the panel foot about `2.10` and the note about two projects shipping the
 * same version.
 *
 * <h2>Empty is the ordinary state on the day this ships (D14)</h2>
 *
 * Nothing is migrated into `release`, so the first row here is one somebody types — in every
 * instance, on merge day. The empty state says that in as many words rather than reading as a screen
 * that failed to load, and carries the create.
 */

const TEST_ID = 'Releases';

export function ReleaseListPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const releases = useReleases(projectId);

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Releases"
        documentTitle={project.data ? `Releases · ${project.data.name}` : 'Releases'}
        subtitle="A cadence without inventing sprints — there are none, and no velocity to measure over them"
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Releases' },
        ]}
        actions={
          <>
            {/*
              The timeline is a **view of the Work screen**, not a screen of its own (D34), so this
              is a link into `?view=timeline` rather than a route under releases. The mockup's button
              says "On the timeline" and lands exactly there.
            */}
            <Button size="sm" asChild>
              <RouterLink
                to={`/projects/${projectId}/work?view=timeline`}
                data-testid={`${TEST_ID}.Timeline`}
              >
                On the timeline
              </RouterLink>
            </Button>
            <NewReleaseButton projectId={projectId} testId={`${TEST_ID}.New`} />
          </>
        }
      />

      <section className="overflow-hidden rounded-[8px] border border-line bg-panel">
        <DataRegion
          testId={`${TEST_ID}.Content`}
          state={{ ...releases, refetch: () => void releases.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          skeleton={<GridSkeleton rows={5} />}
          empty={{
            kind: 'by-design',
            icon: CalendarRange,
            title: 'No releases yet',
            body:
              'Nothing is migrated into releases when this module ships — deliberately, because '
              + 'version strings across a real instance are not as clean as they look — so the '
              + 'first one is one you type. A version is all it needs.',
            action: <NewReleaseButton projectId={projectId} testId={`${TEST_ID}.Empty.New`} label="Create a release" />,
          }}
        >
          {(rows) => (
            <>
              <ScrollX>
                <Table data-testid={`${TEST_ID}.Table`}>
                  <TableHead>
                    <TableRow>
                      {/*
                        `sortDirection` with no `onSort`: the order is the server's computed version
                        key and there is no sort parameter on the endpoint, so this announces the
                        order without offering a control that would silently do nothing.
                      */}
                      <TableHeaderCell sortDirection="asc">Version</TableHeaderCell>
                      <TableHeaderCell>Target date</TableHeaderCell>
                      <TableHeaderCell>Description</TableHeaderCell>
                      <TableHeaderCell>Stories</TableHeaderCell>
                      <TableHeaderCell>Tests</TableHeaderCell>
                      <TableHeaderCell>Readiness</TableHeaderCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((release) => (
                      <ReleaseRow key={release.id} projectId={projectId} release={release} />
                    ))}
                  </TableBody>
                </Table>
              </ScrollX>

              <TableFoot
                data-testid={`${TEST_ID}.Foot`}
                summary={plural(rows.length, 'release')}
                sort="2.10 sorts after 2.5, not between 2.1 and 2.2 — versions are compared segment by segment, not as text"
              />
            </>
          )}
        </DataRegion>
      </section>

      {/*
        `releases.html`'s closing note, and it is the one thing on this screen a reader is most
        likely to assume the opposite of. D13: the version is unique within the PROJECT, so two
        projects shipping "2.4" hold two unrelated releases.
      */}
      <div className="mt-4">
        <Banner tone="steel" data-testid={`${TEST_ID}.Scope`}>
          A version shipped from two projects is <strong>two releases</strong>. A release belongs to
          one project, so &ldquo;2.4&rdquo; here and &ldquo;2.4&rdquo; in another project are
          unrelated and never merge in a report.
        </Banner>
      </div>
    </div>
  );
}

/**
 * The create, in both of the two places this screen offers it — the header and the empty state.
 *
 * One component because the gate is the fiddly half: `PermissionGate` renders its denied branch
 * inside a tooltip anchor, so the `asChild` link has to be swapped for a plain span when the answer
 * is no. Two call sites spelling that is two chances to ship a disabled-looking anchor that still
 * navigates.
 */
function NewReleaseButton({
  projectId,
  testId,
  label = '+ New release',
}: {
  projectId: string;
  testId: string;
  label?: string;
}) {
  return (
    <PermissionGate projectId={projectId} permission="release:manage">
      {({ allowed }) => (
        <Button variant="primary" size="sm" disabled={!allowed} asChild={allowed} data-testid={testId}>
          {allowed ? (
            <RouterLink to={`/projects/${projectId}/releases/new`}>{label}</RouterLink>
          ) : (
            <span>{label}</span>
          )}
        </Button>
      )}
    </PermissionGate>
  );
}

function ReleaseRow({ projectId, release }: { projectId: string; release: ReleaseSummary }) {
  const badge = releaseBadge(release.status, release.readiness.verdict);
  const { readiness } = release;

  return (
    // A released version is dimmed rather than moved or hidden, as the mockup dims 2.3 and 2.2:
    // shipped releases are the history the roadmap is read against, and a list that dropped them
    // would make a project look like it had never released anything.
    <TableRow data-testid={`${TEST_ID}.Row`} className={release.status === 'RELEASED' ? 'opacity-80' : undefined}>
      <TableCell variant="title">
        <RouterLink
          to={`/projects/${projectId}/releases/${release.id}`}
          data-testid={`${TEST_ID}.Row.Version`}
          className="font-mono text-inherit no-underline hover:text-steel hover:underline"
        >
          {release.version}
        </RouterLink>
      </TableCell>
      <TableCell>{formatDate(release.targetDate)}</TableCell>
      <TableCell>
        {release.description ? (
          <span className="text-ink-2">{release.description}</span>
        ) : (
          <span className="text-ink-4">&mdash;</span>
        )}
      </TableCell>
      <TableCell>
        <Meter
          testId={`${TEST_ID}.Row.Stories`}
          done={readiness.storiesDone}
          total={readiness.storiesTotal}
        />
      </TableCell>
      <TableCell>
        {readiness.hasRun ? (
          <Meter
            testId={`${TEST_ID}.Row.Tests`}
            done={readiness.casesPassed ?? 0}
            failed={readiness.casesFailed ?? 0}
            total={readiness.linkedCases}
          />
        ) : (
          // The mockup's words, and the module's acceptance criterion. NOT a 0/0 meter: an untested
          // version and a clean version are not the same claim (D-d).
          <span data-testid={`${TEST_ID}.Row.NoRun`} className="text-ink-4">
            no run yet
          </span>
        )}
      </TableCell>
      <TableCell>
        <Badge tone={badge.tone} data-testid={`${TEST_ID}.Row.Readiness`}>
          {badge.label}
        </Badge>
      </TableCell>
    </TableRow>
  );
}

/**
 * `n/m` and a 56px bar — the mockup's cell, twice per row.
 *
 * **Only reached when the numbers are real.** The Tests column branches on `hasRun` before this,
 * because a meter is a claim that the denominator was measured; the `?? 0`s at the call site are
 * inside that branch and not a substitute for it.
 *
 * `failed` is the second segment, in the failed tone, so a 61/63 row shows where the two went
 * rather than only that they are missing.
 */
function Meter({
  done,
  failed = 0,
  total,
  testId,
}: {
  done: number;
  failed?: number;
  total: number;
  testId: string;
}) {
  const pct = (count: number) => (total > 0 ? (count / total) * 100 : 0);

  return (
    <span data-testid={testId} className="flex items-center gap-2">
      <Identifier variant="muted">{`${done}/${total}`}</Identifier>
      <span aria-hidden className="flex h-1.5 w-14 flex-none overflow-hidden rounded-full bg-wash-3">
        <span
          className="block h-full bg-[color:var(--color-passed)]"
          style={{ width: `${pct(done)}%` }}
        />
        <span
          className="block h-full bg-[color:var(--color-failed)]"
          style={{ width: `${pct(failed)}%` }}
        />
      </span>
    </span>
  );
}
