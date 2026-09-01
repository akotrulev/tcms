import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { useProject, usePublishRelease, useReadiness, useRelease } from '../../api/queries';
import type { Release, ReleaseReadiness } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Badge, Banner } from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { formatDate, formatDueIn } from '../../lib/format';
import { toast } from '../../notifications/toast';
import { BlockersPanel, EpicsPanel, ReadinessTiles, TestEvidencePanel } from './ReadinessPanels';
import { doneButFailing, releaseBadge } from './releaseBadge';

/**
 * `release.html` — the one screen that answers *can we ship 2.4?*
 *
 * <h2>Two reads, not one, and the header is drawn from the first</h2>
 *
 * `GET /releases/{id}` carries the release; `GET /releases/{id}/readiness` carries the answer. The
 * header, the target date and the publish button need only the first, so they render while the
 * second is still in flight — which is the ordinary case, because readiness is a query over the
 * project's issues, its links and its executions and the release is one row.
 *
 * The badge is the exception and sits inside the readiness region: it is `(status, verdict)`, and a
 * badge drawn from the status alone would read **Planned** for a moment and then change its mind —
 * on the one element of this page a reader is looking at to make a decision.
 *
 * <h2>`Mark released` is not gated on readiness, and confirms instead</h2>
 *
 * The contract is explicit: *"a team may ship a version with a known failure and record that they
 * did; a server that refused would be asserting a release policy this product has no way to know."*
 * So the button is enabled on `NOT_READY`, and the confirmation names what is outstanding rather
 * than refusing. It is gated on `release:publish` — a key `release:manage` does not imply (D-m).
 */

const TEST_ID = 'Release';

export function ReleaseDetailPage() {
  const { projectId = '', releaseId = '' } = useParams();
  const project = useProject(projectId);
  const release = useRelease(releaseId);
  const readiness = useReadiness(releaseId);

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title={release.data ? `Release ${release.data.version}` : 'Release'}
        documentTitle={release.data ? `Release ${release.data.version}` : 'Release'}
        subtitle={release.data?.description ?? undefined}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Releases', to: `/projects/${projectId}/releases` },
          { label: release.data?.version ?? 'Release' },
        ]}
        meta={
          release.data && (
            <span className="flex flex-wrap items-center gap-2">
              <ReadinessBadge readiness={readiness.data} release={release.data} />
              <TargetMeta release={release.data} />
            </span>
          )
        }
        actions={
          <>
            <Button size="sm" asChild>
              <RouterLink to={`/projects/${projectId}/plans`} data-testid={`${TEST_ID}.Plans`}>
                Test plan
              </RouterLink>
            </Button>
            <Button size="sm" asChild>
              <RouterLink to={`/projects/${projectId}/reports`} data-testid={`${TEST_ID}.Reports`}>
                Burn-up
              </RouterLink>
            </Button>
            {release.data && (
              <PublishButton projectId={projectId} release={release.data} readiness={readiness.data} />
            )}
          </>
        }
      />

      <DataRegion
        testId={`${TEST_ID}.Content`}
        state={{ ...readiness, refetch: () => void readiness.refetch() }}
      >
        {(data) => (
          <>
            <HeadlineBanner readiness={data} />

            <ReadinessTiles readiness={data} projectId={projectId} testId={`${TEST_ID}.Tile`} />

            <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
              <div className="flex min-w-0 flex-col gap-4">
                <BlockersPanel blockers={data.blockers} testId={`${TEST_ID}.Blockers`} />
                <EpicsPanel
                  version={data.version}
                  epics={data.epics}
                  testId={`${TEST_ID}.Epics`}
                />
              </div>
              <div className="flex min-w-0 flex-col gap-4">
                <TestEvidencePanel
                  tests={data.tests}
                  version={data.version}
                  projectId={projectId}
                  failedCaseKeys={data.blockers.flatMap((blocker) => blocker.failedCaseKeys)}
                  testId={`${TEST_ID}.Evidence`}
                />
              </div>
            </div>
          </>
        )}
      </DataRegion>
    </div>
  );
}

/**
 * The badge, which needs **both** reads.
 *
 * Until readiness lands there is no verdict, so a released release can still be badged from its
 * status alone and a planned one shows nothing rather than guessing. The alternative — badging
 * `PLANNED` while waiting — is a badge that changes its mind on the element the reader is deciding
 * from.
 */
function ReadinessBadge({
  release,
  readiness,
}: {
  release: Release;
  readiness: ReleaseReadiness | undefined;
}) {
  if (readiness === undefined) {
    return release.status === 'RELEASED' ? (
      <Badge tone="passed" data-testid={`${TEST_ID}.Badge`}>
        Released
      </Badge>
    ) : null;
  }
  const badge = releaseBadge(release.status, readiness.verdict);
  return (
    <Badge tone={badge.tone} data-testid={`${TEST_ID}.Badge`}>
      {badge.label}
    </Badge>
  );
}

/**
 * `target 18 Sep 2026 · in 17 days` — the mockup's chip, and the relative half is dropped once the
 * release has shipped: a countdown beside a released version is counting to a date that has stopped
 * mattering.
 *
 * The relative phrase is `formatDueIn` and **not `formatAge`**: `formatAge` reads a negative
 * interval as *"just now"*, so every future target rendered as `target 18 Sep 2026 · just now`.
 */
function TargetMeta({ release }: { release: Release }) {
  if (release.status === 'RELEASED') {
    /*
      A plain span, not a `Chip`. `Chip`'s `onClick` is required because a chip is a value the
      *reader* owns and one with nothing behind it is a badge wearing the wrong component — and this
      is a date the system recorded. A `Chip` with a no-op handler is precisely the affordance defect
      that type exists to make unrepresentable.
    */
    return (
      <span data-testid={`${TEST_ID}.Released`} className="text-[11.5px] text-ink-3">
        {`released ${formatDate(release.releasedAt?.slice(0, 10) ?? null)}`}
      </span>
    );
  }
  if (!release.targetDate) {
    return (
      // Not omitted: a release with no target is a real and ordinary state (the form says so), and
      // an absent chip would read as a date that failed to load.
      <span data-testid={`${TEST_ID}.NoTarget`} className="text-[11.5px] text-ink-4">
        no target date
      </span>
    );
  }
  return (
    <span data-testid={`${TEST_ID}.Target`} className="text-[11.5px] text-ink-3">
      {`target ${formatDate(release.targetDate)} · ${formatDueIn(release.targetDate)}`}
    </span>
  );
}

/**
 * The red banner — **done, and its test says otherwise**, which is the sentence this whole module
 * exists to be able to write.
 *
 * Derived by `doneButFailing` rather than sent as prose, because both the story key and the case key
 * are links here and a server-assembled sentence cannot carry them.
 *
 * When no blocker is in that state the banner is absent rather than replaced by a green one: the
 * badge and the three tiles already say where the release stands, and a banner on every visit is
 * chrome the reader learns to skip past the one time it matters.
 */
function HeadlineBanner({ readiness }: { readiness: ReleaseReadiness }) {
  const failing = doneButFailing(readiness.blockers);
  if (!failing) return null;

  return (
    <div className="mb-4">
      <Banner tone="bad" data-testid={`${TEST_ID}.Headline`}>
        <div className="min-w-0">
          <p className="m-0 text-[13px] font-semibold text-ink">
            {`${readiness.version} is not ready.`}
          </p>
          <p className="mt-1 mb-0">
            <RouterLink
              to={`/issues/${encodeURIComponent(failing.key)}`}
              data-testid={`${TEST_ID}.Headline.Issue`}
              className="text-steel no-underline hover:underline"
            >
              <strong>{`${failing.key} · ${failing.title}`}</strong>
            </RouterLink>
            {' is marked done while '}
            {failing.failedCaseKeys.length === 1 ? 'its linked case ' : 'its linked cases '}
            <span className="identifier">{failing.failedCaseKeys.join(', ')}</span>
            {failing.failedCaseKeys.length === 1 ? ' failed' : ' failed'}
            {' in the latest run. Done and proven have to be the same answer.'}
          </p>
        </div>
      </Banner>
    </div>
  );
}

/**
 * `Mark released` — `release:publish`, and it confirms first.
 *
 * The confirmation is not a formality: publishing is idempotent server-side but it is the one write
 * on this screen that moves a state somebody else reads, and when the verdict is not `READY` the
 * dialog says what is outstanding. It still lets the person go ahead, because a team shipping a
 * version with a known failure and recording that they did is a real thing this product must not
 * refuse.
 *
 * Absent rather than disabled on an already-released release: the state is reached, so there is no
 * capability left to advertise. That is the one place §31's disable-don't-hide does not apply —
 * hiding it says nothing about the reader's access.
 */
function PublishButton({
  projectId,
  release,
  readiness,
}: {
  projectId: string;
  release: Release;
  readiness: ReleaseReadiness | undefined;
}) {
  const publish = usePublishRelease(projectId);
  const [asking, setAsking] = useState(false);

  if (release.status === 'RELEASED') return null;

  return (
    <>
      <PermissionGate projectId={projectId} permission="release:publish">
        {({ allowed }) => (
          <Button
            variant="primary"
            size="sm"
            disabled={!allowed || publish.isPending}
            onClick={() => setAsking(true)}
            data-testid={`${TEST_ID}.Publish`}
          >
            Mark released
          </Button>
        )}
      </PermissionGate>

      <Dialog open={asking} onOpenChange={setAsking}>
        <DialogContent data-testid={`${TEST_ID}.Publish.Confirm`}>
          <DialogHeader>
            <DialogTitle>{`Mark ${release.version} released?`}</DialogTitle>
            <DialogDescription>
              {readiness && readiness.verdict !== 'READY'
                ? `This version reads ${readiness.verdict === 'NOT_TESTED' ? 'not tested' : 'not ready'}`
                  + ` — ${readiness.stories.total - readiness.stories.done} of `
                  + `${readiness.stories.total} counted stories are not done. Recording a release`
                  + ' with known gaps is allowed; the readiness figures keep saying what they say.'
                : 'It stops appearing as upcoming work and the roadmap draws it as shipped.'}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button size="sm" onClick={() => setAsking(false)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              size="sm"
              disabled={publish.isPending}
              data-testid={`${TEST_ID}.Publish.Confirm.Yes`}
              onClick={async () => {
                await publish.mutateAsync(release.id);
                setAsking(false);
                toast(`${release.version} is released`, { variant: 'success' });
              }}
            >
              Mark released
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
