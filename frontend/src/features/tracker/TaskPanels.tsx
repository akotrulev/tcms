import type { Issue } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { LinkedSpecPanel } from './LinkedSpecPanel';

/**
 * `task.html`'s body — and it is the shortest of the three by a long way, because almost
 * everything on that mockup belongs to M5.
 *
 * **Absent (D-f):** the whole "What the assistant received" panel, the "Pull request" panel, the
 * "Assistant" rail panel, "What the assistant was refused", and the `AI-ELIGIBLE` chip. Every one
 * of those is the assistant module, and none is rendered as an empty box — a "Pull request: none"
 * card on a product with no repository integration is an advertisement, not an explanation.
 *
 * **"Where it can go" IS M1's**, and it lands: it is the legal-transition set, which this module
 * computes and this contract publishes. It is {@link TransitionMenu}, rendered on the rail by the
 * page rather than here, because all three levels share it.
 *
 * What is left is a description and the spec links, which is what a sub-task actually is: the
 * story above it carries the acceptance criteria, and this carries one person's part of meeting
 * them.
 */
export function TaskPanels({ issue }: { issue: Issue }) {
  return (
    <>
      <Panel title="Description" testId="Task.Description">
        {issue.description ? (
          <p className="m-0 text-[13px] leading-[1.6] whitespace-pre-wrap text-ink-2">
            {issue.description}
          </p>
        ) : (
          <p className="m-0 text-[12.5px] text-ink-3" data-testid="Task.Description.Empty">
            No description. The acceptance criteria on{' '}
            {issue.parentKey ?? 'the parent story'} are what this is measured against.
          </p>
        )}
      </Panel>

      <LinkedSpecPanel issue={issue} />
    </>
  );
}
