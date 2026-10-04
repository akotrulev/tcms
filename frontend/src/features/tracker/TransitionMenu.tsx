import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
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

  /*
    Which of the two refusals a disabled row is, told apart without a second read.

    `allowedTransitions` is **caller-scoped** (015 D33): under "Require review to close issues" the
    assignee's own DONE moves are dropped from it, while the project's workflow still declares the
    edge. So a status the workflow permits and this set does not is the close guard biting, and a
    status the workflow does not permit is the ordinary workflow refusal. Reading the pair is what
    lets the row say *needs a second person* rather than the flatly wrong *not from In Progress*,
    and it costs nothing: the workflow document is already on screen for the list itself.
  */
  const workflowAllows = (toStatusId: string) =>
    (workflow?.transitions ?? []).some(
      (edge) => edge.fromStatusId === issue.status.id && edge.toStatusId === toStatusId,
    );

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
              'flex items-center gap-2 rounded-md border border-line px-2.5 py-1.5',
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
                {workflowAllows(status.id)
                  ? 'needs a second person'
                  : `not from ${issue.status.name} (${issueStatusKinds[issue.status.kind]})`}
              </span>
            )}
          </div>
        );
      })}

      <p className="m-0 text-[11.5px] text-ink-4">
        Disabled, not hidden — you can see the move exists and why it is closed to you.
      </p>

      {/*
        `task.html`'s own footnote, and it is worth the line because it names the setting rather
        than the rule: a reader looking at *needs a second person* has no other way to learn that
        it is a per-project switch somebody chose, not something the product does everywhere.
      */}
      {(workflow?.statuses ?? []).some(
        (status) =>
          status.kind === 'DONE' && !legal.has(status.id) && workflowAllows(status.id),
      ) && (
        <p className="m-0 text-[11.5px] text-ink-4" data-testid={`${testId}.CloseGate`}>
          Closing this is held by{' '}
          <RouterLink
            to={`/projects/${issue.projectId}/settings/general`}
            className="text-steel no-underline hover:underline"
          >
            Require review to close issues
          </RouterLink>
          . On a project with that gate off, the same move would be allowed.
        </p>
      )}
    </section>
  );
}
