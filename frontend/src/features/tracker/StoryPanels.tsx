import { Link as RouterLink } from 'react-router-dom';
import type { Issue } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import {
  Banner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Button } from '../../components/ui';
import { plural } from '../../lib/format';
import { AcceptanceCriteria } from './AcceptanceCriteria';
import { LinkedSpecPanel } from './LinkedSpecPanel';
import { StatusBadge } from './StatusBadge';
import { TypeIcon } from './TypeIcon';

/**
 * `story.html`'s body — description, acceptance criteria, sub-tasks, and the closing rule.
 *
 * **What M1 owns and renders**, from the "Closing this story" panel: *"All 4 sub-tasks are done.
 * The story did **not** close itself. Closing is your decision — sub-tasks finishing is evidence,
 * not a trigger."* That is story H4's third criterion, and it is the one claim on that panel this
 * module makes.
 *
 * **What is absent (D-f):**
 *
 *   - the review-gate warning and the "Request approval to close" button — M5;
 *   - the whole "Time" panel and the `Logged` row — M6;
 *   - the Release row (M3) and the Labels row (M2);
 *   - the "3 proposed by claude-code" button on Sub-tasks — M5;
 *   - the **"Linked test cases" panel**, which is H7 and lands in M3's `t18`. Its absence is why
 *     the mockup's red footer — *"TC-311 failed, so 2.4 is not ready"* — does not render either:
 *     that sentence is a claim about a run this screen cannot see.
 */
export function StoryPanels({ issue }: { issue: Issue }) {
  const subTasks = issue.children;

  return (
    <>
      {issue.description && (
        <Panel title="Description" testId="Story.Description">
          <p className="m-0 text-[13px] leading-[1.6] whitespace-pre-wrap text-ink-2">
            {issue.description}
          </p>
        </Panel>
      )}

      {issue.acceptanceCriteria && issue.acceptanceCriteria.length > 0 && (
        <AcceptanceCriteria criteria={issue.acceptanceCriteria} testId="Story.Criteria" />
      )}

      <LinkedSpecPanel issue={issue} />

      <Panel
        title="Sub-tasks"
        testId="Story.SubTasks"
        actions={
          <span className="flex items-center gap-2">
            <span className="text-[11.5px] text-ink-4">
              {subTasks.length > 0 ? `${subTasks.length} · one owner each` : '0'}
            </span>
            <PermissionGate projectId={issue.projectId} permission="issue:create">
              {({ allowed }) =>
                allowed ? (
                  <Button size="sm" asChild className="min-h-6 px-2 py-0.5 text-[11px]">
                    <RouterLink
                      to={`/projects/${issue.projectId}/work/new`}
                      data-testid="Story.SubTasks.Add"
                    >
                      + Add sub-task
                    </RouterLink>
                  </Button>
                ) : (
                  <span />
                )
              }
            </PermissionGate>
          </span>
        }
      >
        {subTasks.length === 0 ? (
          <p className="m-0 text-[12.5px] text-ink-3" data-testid="Story.SubTasks.Empty">
            No sub-tasks. A story does not need any — breaking one down is for when a developer and
            a tester own different halves of it.
          </p>
        ) : (
          <>
            <ScrollX>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>Key</TableHeaderCell>
                    <TableHeaderCell>Task</TableHeaderCell>
                    <TableHeaderCell>Status</TableHeaderCell>
                    <TableHeaderCell>Owner</TableHeaderCell>
                    <TableHeaderCell align="right">Est</TableHeaderCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {subTasks.map((child) => (
                    <TableRow key={child.key} data-testid="Story.SubTasks.Row">
                      <TableCell>
                        <Identifier variant="muted">{child.key}</Identifier>
                      </TableCell>
                      <TableCell variant="title">
                        <span className="flex items-center gap-1.5">
                          <TypeIcon type={child.type} />
                          <RouterLink
                            to={`/issues/${encodeURIComponent(child.key)}`}
                            className="text-inherit no-underline hover:text-steel hover:underline"
                          >
                            {child.title}
                          </RouterLink>
                        </span>
                      </TableCell>
                      <TableCell>
                        <StatusBadge status={child.status} />
                      </TableCell>
                      <TableCell>
                        {child.assignee?.displayName ?? (
                          <span className="text-ink-4">Unassigned</span>
                        )}
                      </TableCell>
                      <TableCell align="right">
                        {child.estimateHours != null ? (
                          <Identifier variant="muted">{`${child.estimateHours}h`}</Identifier>
                        ) : (
                          <span className="text-ink-4">&mdash;</span>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </ScrollX>
            <p className="m-0 mt-2 text-[11.5px] text-ink-4">
              A developer and a tester own their own part of the same story. A sub-task cannot be
              detached into an orphan — it belongs to this story or it is deleted.
            </p>
          </>
        )}
      </Panel>
    </>
  );
}

/**
 * The rail panel that states story H4's third criterion.
 *
 * Rendered on the **rail** rather than in the body because it is about a decision the reader is
 * standing in front of, beside the transition menu that performs it. It says the same thing
 * whether the sub-tasks are all done or not — the difference is only which evidence it reports —
 * because the rule is that finishing sub-tasks is **evidence, not a trigger**, and a panel that
 * appeared only when they were all done would imply the opposite by its own absence.
 */
export function ClosingThisStory({ issue }: { issue: Issue }) {
  const subTasks = issue.children;
  const done = subTasks.filter((child) => child.status.kind === 'DONE').length;
  const allDone = subTasks.length > 0 && done === subTasks.length;

  return (
    <Panel title="Closing this story" testId="Story.Closing">
      <div className="flex flex-col gap-2.5">
        {subTasks.length > 0 && (
          <Banner tone={allDone ? 'steel' : 'neutral'} data-testid="Story.Closing.Evidence">
            <span>
              {allDone
                ? `All ${plural(subTasks.length, 'sub-task')} are done.`
                : `${done} of ${plural(subTasks.length, 'sub-task')} are done.`}
            </span>
          </Banner>
        )}
        <p className="m-0 text-[12.5px] leading-[1.55] text-ink-2">
          The story did <strong>not</strong> close itself. Closing is your decision — sub-tasks
          finishing is evidence, not a trigger.
        </p>
      </div>
    </Panel>
  );
}
