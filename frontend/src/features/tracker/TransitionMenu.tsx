import { useState } from 'react';
import { ApiError } from '../../api/client';
import { useTransitionIssue } from '../../api/queries';
import type { Issue, IssueStatusRef, ProjectWorkflow } from '../../api/types';
import { useAnnounce } from '../../a11y/useAnnounce';
import { Banner } from '../../components/kit';
import { PermissionGate } from '../../components/PermissionGate';
import { Button } from '../../components/ui';
import { cn } from '../../lib/cn';
import { describeError } from '../../lib/describeError';
import { issueStatusKinds } from '../../lib/labels';
import { StatusBadge } from './StatusBadge';

/**
 * `task.html`'s "Where it can go" — **every status in the project, with the illegal ones disabled
 * and captioned.**
 *
 * The mockup's own footer is the requirement, verbatim: *"Disabled, not hidden — you can see the
 * move exists and why it is closed to you."* So this renders the **workflow's whole status set**
 * and not `Issue.allowedTransitions`, which is the legal subset. A list of only the legal moves
 * makes a configured workflow look like a broken one — the reader cannot tell "this project does
 * not allow that" from "this product cannot do that".
 *
 * That is the opposite call from the board's card menu, and the difference is which question each
 * one can answer. The board draws a union of several projects' workflows on the cross-project
 * boards, so "every status" there is not a set that means anything for one card. Here the project
 * is known — the screen reads its workflow — so every status is exactly the right list.
 *
 * <h2>An Epic gets no menu at all</h2>
 *
 * D31 derives an epic's status from its stories, so `allowedTransitions` is empty whatever the
 * target and the server refuses every move with a 409. A **disabled menu would read as a missing
 * permission** — the one thing this product's disable-don't-hide rule means everywhere else — so
 * the menu is absent and a sentence stands in its place. The absence plus the sentence reads as
 * what it is: not a thing you may not do, a thing nobody does.
 *
 * <h2>The refusal appears beside the picker, not as a toast</h2>
 *
 * The server's 409 **names the moves that are allowed**, and that sentence is the useful half. A
 * toast scrolls away and leaves the reader looking at a card they believe moved. It cannot be
 * produced by using the app — the picker only ever *offers* legal moves — which is why
 * `tcmsFaults.issue('conflict')` exists and why it was built before this screen.
 */
export function TransitionMenu({
  issue,
  workflow,
  testId = 'Issue.Transitions',
}: {
  issue: Issue;
  workflow: ProjectWorkflow | undefined;
  testId?: string;
}) {
  const announce = useAnnounce();
  const move = useTransitionIssue();
  const [refused, setRefused] = useState<string | null>(null);

  if (issue.type.level === 'EPIC') {
    return (
      <section data-testid={`${testId}.Derived`}>
        <p className="m-0 text-[12.5px] leading-[1.55] text-ink-2">
          This epic&rsquo;s status is <strong>derived from its stories</strong> and is not moved
          directly. Move the stories and it follows.
        </p>
      </section>
    );
  }

  const legal = new Map(issue.allowedTransitions.map((status) => [status.id, status]));

  const commit = async (target: IssueStatusRef) => {
    setRefused(null);
    try {
      await move.mutateAsync({ key: issue.key, statusId: target.id });
      announce(`${issue.key} moved to ${target.name}`);
    } catch (error) {
      const reason = error instanceof ApiError ? error.detail : describeError(error).body;
      setRefused(reason);
      announce(`${issue.key} did not move. ${reason}`);
    }
  };

  return (
    <section data-testid={testId} className="flex flex-col gap-2">
      {refused && (
        <div role="alert">
          <Banner tone="warn" data-testid={`${testId}.Refused`}>
            {refused}
          </Banner>
        </div>
      )}

      {(workflow?.statuses ?? []).map((status) => {
        const allowed = legal.has(status.id);
        const here = status.id === issue.status.id;

        return (
          <div
            key={status.id}
            data-testid={`${testId}.${status.id}`}
            className={cn(
              'flex items-center gap-2 rounded-[6px] border border-line px-2.5 py-1.5',
              !allowed && !here && 'opacity-55',
            )}
          >
            <StatusBadge status={status} />
            <span className="flex-1" />
            {here ? (
              <span className="text-[11.5px] text-ink-4">where it is</span>
            ) : allowed ? (
              <PermissionGate projectId={issue.projectId} permission="issue:edit">
                {({ allowed: mayMove }) => (
                  <Button
                    size="sm"
                    disabled={!mayMove || move.isPending}
                    onClick={() => void commit(status)}
                    data-testid={`${testId}.${status.id}.Move`}
                    className="min-h-6 px-2 py-0.5 text-[11px]"
                  >
                    Move here
                  </Button>
                )}
              </PermissionGate>
            ) : (
              /*
                The reason, in words, on the row itself — not a tooltip. A disabled control sets
                `disabled:pointer-events-none`, so no hover ever reaches a tooltip wrapped around
                one, which is the trap the board's card spent a paragraph on. A caption costs a
                line and is readable by everybody.
              */
              <span
                data-testid={`${testId}.${status.id}.Reason`}
                className="text-[11.5px] text-[color:var(--color-failed)]"
              >
                {`not from ${issue.status.name} (${issueStatusKinds[issue.status.kind]})`}
              </span>
            )}
          </div>
        );
      })}

      <p className="m-0 text-[11.5px] text-ink-4">
        Disabled, not hidden — you can see the move exists and why it is closed to you.
      </p>
    </section>
  );
}
