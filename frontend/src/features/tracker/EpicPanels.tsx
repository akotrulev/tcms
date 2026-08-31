import { Link as RouterLink } from 'react-router-dom';
import type { Issue } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
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
import { completionBases } from '../../lib/labels';
import { CompletionMeter } from './CompletionMeter';
import { LinkedSpecPanel } from './LinkedSpecPanel';
import { StatusBadge } from './StatusBadge';
import { TypeIcon } from './TypeIcon';

/**
 * `epic.html`'s body — completion, the goal, the linked specification, and the children.
 *
 * **Absent (D-f):** the target-release chip (M3), the `Labels` row in Details (M2), and the whole
 * "Estimate vs actual" panel (M6). None renders as an empty box: an epic with a blank Release
 * reads as an epic nobody scheduled, which is a statement about this epic in a product that has
 * no releases at all.
 *
 * The **note under the meter is rendered from the basis the server sent**, not written as a fixed
 * sentence about estimates. The mockup's note assumes `ESTIMATE`; an epic counted by heads needs
 * the same explanation with the other word in it, and a hardcoded one would be the second
 * contradictory statement of one number that D8 exists to prevent.
 */
export function EpicPanels({ issue }: { issue: Issue }) {
  return (
    <>
      {issue.completion && (
        <Panel testId="Epic.Completion">
          <CompletionMeter completion={issue.completion} childNoun="story" />
          <Banner tone="steel" className="mt-3.5">
            <span>
              Weighted <strong>{completionBases[issue.completion.basis]}</strong>
              {issue.completion.basis === 'ESTIMATE'
                ? ' because every story under this epic has an estimate. The moment one does not, this switches to by count and says so — a percentage never changes meaning silently.'
                : ' because at least one story under this epic has no estimate. It switches to by estimate the moment they all do, and says so — a percentage never changes meaning silently.'}
            </span>
          </Banner>
        </Panel>
      )}

      {issue.description && (
        <Panel title="Goal" testId="Epic.Goal">
          <p className="m-0 text-[13px] leading-[1.6] whitespace-pre-wrap text-ink-2">
            {issue.description}
          </p>
        </Panel>
      )}

      <LinkedSpecPanel issue={issue} />

      <Panel
        title="Stories, bugs and spikes"
        testId="Epic.Children"
        actions={<span className="text-[11.5px] text-ink-4">{issue.children.length}</span>}
      >
        {issue.children.length === 0 ? (
          <p className="m-0 text-[12.5px] text-ink-3" data-testid="Epic.Children.Empty">
            Nothing under this epic yet. An epic with no stories reads 0% because there is nothing
            to have finished — not because the work has not started.
          </p>
        ) : (
          <ScrollX>
            <Table>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>Key</TableHeaderCell>
                  <TableHeaderCell>Summary</TableHeaderCell>
                  <TableHeaderCell>Status</TableHeaderCell>
                  <TableHeaderCell>Assignee</TableHeaderCell>
                  <TableHeaderCell align="right">Est</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {issue.children.map((child) => (
                  <TableRow key={child.key} data-testid="Epic.Children.Row">
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
                      {/*
                        Said on the row rather than only in the meter's footnote: a reader counting
                        seventeen rows against a denominator of sixteen has to be able to see which
                        row is the one left out.
                      */}
                      {!child.type.countsTowardCompletion && (
                        <span className="mt-0.5 block text-[11px] text-ink-4">
                          {`${child.type.name.toLowerCase()} · excluded from completion`}
                        </span>
                      )}
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={child.status} />
                    </TableCell>
                    <TableCell>
                      {child.assignee?.displayName ?? <span className="text-ink-4">Unassigned</span>}
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
        )}
      </Panel>
    </>
  );
}
