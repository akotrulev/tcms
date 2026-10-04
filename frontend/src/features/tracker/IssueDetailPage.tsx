import { Link as RouterLink, useParams } from 'react-router-dom';
import {
  useIssue,
  useIssueTimeRollup,
  useProjectSettings,
  useProjectWorkflow,
  useSetIssueWatch,
} from '../../api/queries';
import { useCurrentUserId } from '../../auth/useAuth';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Badge } from '../../components/kit';
import { Button } from '../../components/ui';
import { formatDate, formatDateTime, formatHours } from '../../lib/format';
import { priorityLabels } from '../../lib/labels';
import { CommentFeed } from './CommentFeed';
import { EpicPanels } from './EpicPanels';
import { StatusBadge } from './StatusBadge';
import { StoryPanels, ClosingThisStory } from './StoryPanels';
import { EpicTimePanel, StoryTimePanel } from './TimePanels';
import { AssistantRailPanel, TaskPanels } from './TaskPanels';
import { TransitionMenu } from './TransitionMenu';
import { AiEligibleMarker } from './AiEligibleMarker';
import { TypeIcon } from './TypeIcon';

/**
 * `epic.html`, `story.html` and `task.html` — **one URL, three panel sets.**
 *
 * They are one route because **a key does not say its level.** Somebody following `ACME-118` out
 * of a commit message, a Slack line or a CLI verb does not know which of the three they are
 * getting, and three routes would mean the client had to learn the level before it could build the
 * address — a read to decide where to navigate, on the read it was navigating to.
 *
 * So `/issues/:key` reads the issue and dispatches on `type.level`. The **shell is shared** —
 * header chips, title, the transition menu, comments, watchers, the Details list — and only the
 * body panels differ, which is what keeps the three from drifting into three spellings of one
 * screen.
 *
 * **No project segment**, and that is the contract's own decision: `project.key` is unique per
 * company and the counter is per project, so the pair is unique in the only scope RLS lets a
 * caller see. A project id in the path would be a second, guessable copy of a fact the key
 * already carries.
 */

const TEST_ID = 'Issue';

export function IssueDetailPage() {
  const { key = '' } = useParams();
  const issue = useIssue(key);
  return (
    <div>
      {!issue.data && <title>{`${key} · TCMS`}</title>}

      <DataRegion
        testId={`${TEST_ID}.Content`}
        state={{ ...issue, refetch: () => void issue.refetch() }}
      >
        {(data) => <IssueBody issue={data} />}
      </DataRegion>
    </div>
  );
}

function IssueBody({ issue }: { issue: ReturnType<typeof useIssue>['data'] & {} }) {
  const workflow = useProjectWorkflow(issue.projectId);
  const watch = useSetIssueWatch(issue.key);
  /*
    016 D-g's switch, read here rather than in the two time panels below so one answer gates both
    of them and the `Logged` row in Details. The settings GET is deliberately ungated on
    `project:settings` — the contract says so, for exactly this shape of question — so a Test
    Engineer reading a story gets an answer rather than a 403 that would hide the panel from the
    people it is for.

    **Withheld until that read answers, rather than defaulted to on.** A panel that appears and
    then vanishes on a project with tracking off is a layout shift and a wrong claim held for a
    beat, which is worse than a panel that arrives late. A read that FAILS falls back to on, because
    016 D-g's column default is on and hiding a feature because a second read broke is the worse of
    the two errors.
  */
  const settings = useProjectSettings(issue.projectId);
  const tracksTime = !settings.isPending && (settings.data?.trackTime ?? true);
  /*
    The same query the rail's Time panel mounts, so this costs no second request — it is read here
    only because the `Logged` row belongs in the Details list and TanStack answers both callers from
    one cache entry. It is a story-level figure: `story.html` is where the mockup puts the row.
  */
  const storyRollup = useIssueTimeRollup(
    issue.key,
    tracksTime && issue.type.level === 'STORY',
  );
  const me = useCurrentUserId();
  // The non-throwing reader: a test that mounts no auth provider gets `undefined`, which reads as
  // "not watching" rather than throwing on a screen that has nothing to do with the session.
  const watching = me !== undefined && issue.watchers.some((watcher) => watcher.id === me);

  return (
    <>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title={issue.title}
        documentTitle={`${issue.key} ${issue.title}`}
        announcement={`${issue.key} ${issue.title} loaded`}
        meta={
          <>
            <TypeIcon type={issue.type} data-testid={`${TEST_ID}.Header.Type`} />
            <Identifier variant="muted">{issue.key}</Identifier>
            <StatusBadge status={issue.status} data-testid={`${TEST_ID}.Header.Status`} />
            <AiEligibleMarker
              eligible={issue.agentEligible}
              size="full"
              data-testid={`${TEST_ID}.Header.AiEligible`}
            />
            {issue.overdue && <Badge tone="failed">overdue</Badge>}
            <nav
              aria-label="Issue ancestors"
              className="flex flex-wrap items-center gap-2 text-[12.5px]"
            >
              <RouterLink
                to={`/projects/${issue.projectId}/work`}
                className="text-ink-3 no-underline hover:text-ink-2"
                data-testid={`${TEST_ID}.Header.Breadcrumb`}
              >
                Work
              </RouterLink>
              {issue.parentKey && (
                <>
                  <span className="text-ink-4">/</span>
                  <RouterLink
                    to={`/issues/${encodeURIComponent(issue.parentKey)}`}
                    className="text-ink-3 no-underline hover:text-ink-2"
                    data-testid={`${TEST_ID}.Header.Parent`}
                  >
                    {issue.parentKey}
                  </RouterLink>
                </>
              )}
            </nav>
          </>
        }
        actions={
          <Button
            size="sm"
            disabled={watch.isPending}
            aria-pressed={watching}
            onClick={() => watch.mutate(!watching)}
            data-testid={`${TEST_ID}.Watch`}
          >
            {watching ? '★ Watching' : '☆ Watch'}
          </Button>
        }
      />


      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_300px]">
        <div className="flex flex-col gap-4">
          {/*
            The dispatch. Three panel sets, one shell — see this file's header for why the level
            is read here rather than encoded in three routes.
          */}
          {issue.type.level === 'EPIC' && <EpicPanels issue={issue} />}
          {issue.type.level === 'EPIC' && (
            <EpicTimePanel epicKey={issue.key} enabled={tracksTime} />
          )}
          {issue.type.level === 'STORY' && <StoryPanels issue={issue} />}
          {issue.type.level === 'TASK' && <TaskPanels issue={issue} />}

          <CommentFeed issueKey={issue.key} projectId={issue.projectId} testId={`${TEST_ID}.Comments`} />
        </div>

        <aside className="flex flex-col gap-4">
          <Panel title={issue.type.level === 'EPIC' ? 'Status' : 'Where it can go'} testId={`${TEST_ID}.Moves`}>
            <TransitionMenu issue={issue} workflow={workflow.data} testId={`${TEST_ID}.Transitions`} />
          </Panel>

          {issue.type.level === 'STORY' && <ClosingThisStory issue={issue} />}

          {issue.type.level === 'STORY' && (
            <StoryTimePanel issueKey={issue.key} enabled={tracksTime} />
          )}

          {/*
            The assistant panel is a TASK's, and it is where the flag is set as well as read: a
            sub-task is the unit an assistant is handed, which is why the switch is not offered on
            an epic or a story that would only ever pass it down.
          */}
          {issue.type.level === 'TASK' && <AssistantRailPanel issue={issue} />}

          <Panel title="Details" testId={`${TEST_ID}.Details`}>
            <dl className="m-0 grid grid-cols-[minmax(0,7rem)_minmax(0,1fr)] gap-x-3 gap-y-2 text-[12.5px]">
              <Term label="Type">
                <span className="flex items-center gap-1.5">
                  <TypeIcon type={issue.type} /> {issue.type.name}
                </span>
              </Term>
              <Term label="Status">
                <StatusBadge status={issue.status} />
              </Term>
              {issue.epicKey && (
                <Term label="Epic">
                  <RouterLink
                    to={`/issues/${encodeURIComponent(issue.epicKey)}`}
                    className="text-steel no-underline hover:underline"
                  >
                    {issue.epicTitle ?? issue.epicKey}
                  </RouterLink>
                </Term>
              )}
              <Term label="Assignee">
                {issue.assignee?.displayName ?? <span className="text-ink-4">Unassigned</span>}
              </Term>
              <Term label="Reporter">
                {issue.reporter?.displayName ?? <span className="text-ink-4">&mdash;</span>}
              </Term>
              {issue.priority && (
                <Term label="Priority">
                  <Badge tone="plain">{priorityLabels[issue.priority]}</Badge>
                </Term>
              )}
              {issue.estimateHours != null && (
                <Term label="Estimate">
                  <Identifier variant="muted">{formatHours(issue.estimateHours)}</Identifier>
                </Term>
              )}
              {/*
                `story.html`'s `14h (approved)`. The qualifier is not optional decoration — 016 D-d
                says no screen renders a bare "hours", because approved and pending are different
                numbers and a reader cannot act on one that does not say which it is.

                Absent rather than zero when the rollup has not answered: the panel beside it owns
                the failed state, and a `0h` here would be a claim nobody has made.
              */}
              {storyRollup.data && (
                <Term label="Logged">
                  <Identifier variant="muted" testId={`${TEST_ID}.Details.Logged`}>
                    {formatHours(storyRollup.data.approvedHours)}
                  </Identifier>
                  <span className="ml-1.5 text-[11.5px] text-ink-4">(approved)</span>
                </Term>
              )}
              {issue.startDate && <Term label="Start">{formatDate(issue.startDate)}</Term>}
              {issue.targetDate && <Term label="Target">{formatDate(issue.targetDate)}</Term>}
              {issue.dueDate && <Term label="Due">{formatDate(issue.dueDate)}</Term>}
              <Term label="Created">{formatDateTime(issue.createdAt)}</Term>
            </dl>
            {/*
              `epic.html`'s footer, kept because it answers the question the Details list otherwise
              invites: there is no project row, and this says why there does not need to be one.
            */}
            {issue.type.level === 'EPIC' && (
              <p className="m-0 mt-3 border-t border-line pt-2.5 text-[11.5px] text-ink-4">
                An epic belongs to exactly one project. There is no cross-project epic.
              </p>
            )}
          </Panel>

          <Panel
            title="Watchers"
            testId={`${TEST_ID}.Watchers`}
            actions={<span className="text-[11.5px] text-ink-4">{issue.watchers.length}</span>}
          >
            {issue.watchers.length === 0 ? (
              <p className="m-0 text-[12.5px] text-ink-3">
                Nobody yet. Commenting adds you — watchers are notified of status and assignee
                changes.
              </p>
            ) : (
              <div className="flex flex-wrap gap-1.5">
                {issue.watchers.map((watcher) => (
                  <span
                    key={watcher.id}
                    data-testid={`${TEST_ID}.Watchers.Item`}
                    className="rounded-md border border-line px-[9px] py-[3px] text-[12px] text-ink-2"
                  >
                    {watcher.displayName}
                  </span>
                ))}
              </div>
            )}
          </Panel>
        </aside>
      </div>
    </>
  );
}

/** One `dt`/`dd` pair. A component so the two halves cannot drift apart in the class list. */
function Term({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="font-mono text-[11px] tracking-[.06em] text-ink-4 uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-ink-2">{children}</dd>
    </>
  );
}
