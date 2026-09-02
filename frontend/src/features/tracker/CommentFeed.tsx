import { useState } from 'react';
import { useCreateIssueComment, useIssueComments } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Button, Input } from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { commentAuthor, formatDateTime } from '../../lib/format';

/**
 * `story.html`'s and `task.html`'s "Comments & history" — the comments half.
 *
 * **The history half is not rendered**, and the absence is a decision rather than an omission: the
 * mockups' feed mixes comments with system entries (*"status In Progress → In Review"*,
 * *"assignee unassigned → Dana Petrov"*), and this module publishes no activity endpoint. A feed
 * that showed only comments under a heading promising history would be the empty-versus-missing
 * confusion this product's own rules exist to prevent, so the panel is named for what it holds.
 *
 * The placeholder is the mockup's and it states the consequence: **watchers are notified.**
 * Commenting also *adds* the author as a watcher (story H8), which is why the write invalidates
 * the issue and not only this list — the watchers row is on the body above.
 */
export function CommentFeed({
  issueKey,
  projectId,
  testId = 'Issue.Comments',
}: {
  issueKey: string;
  projectId: string;
  testId?: string;
}) {
  const comments = useIssueComments(issueKey);
  const post = useCreateIssueComment(issueKey);
  const [body, setBody] = useState('');
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (body.trim() === '') return;
    setError(null);
    try {
      await post.mutateAsync({ body: body.trim() });
      setBody('');
    } catch (failure) {
      setError(describeError(failure).body);
    }
  };

  return (
    <Panel title="Comments" testId={testId}>
      <DataRegion
        testId={`${testId}.List`}
        state={{ ...comments, refetch: () => void comments.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'by-design',
          title: 'No comments yet',
          body: 'Anyone who can see this issue can say something about it — including a Viewer.',
        }}
      >
        {(rows) => (
          <ol className="m-0 flex list-none flex-col gap-3.5 p-0">
            {rows.map((comment) => (
              <li key={comment.id} data-testid={`${testId}.Item`} className="min-w-0">
                <div className="flex flex-wrap items-baseline gap-2">
                  <strong className="text-[12.5px] font-semibold text-ink">
                    {commentAuthor(comment)}
                  </strong>
                  <span className="text-[11px] text-ink-4">{formatDateTime(comment.createdAt)}</span>
                  {comment.editedAt && <span className="text-[11px] text-ink-4">edited</span>}
                </div>
                <p className="m-0 mt-1 text-[12.5px] leading-[1.55] whitespace-pre-wrap text-ink-2">
                  {comment.body}
                </p>
              </li>
            ))}
          </ol>
        )}
      </DataRegion>

      <form onSubmit={submit} className="mt-4 border-t border-line pt-3">
        <div className="flex flex-wrap items-center gap-2">
          <Input
            aria-label="Write a comment"
            className="min-w-0 flex-1"
            placeholder="Write a comment — watchers are notified"
            value={body}
            data-testid={`${testId}.Body`}
            onChange={(event) => setBody(event.target.value)}
          />
          {/*
            `issue:comment`, which **Viewer holds** (roles spec §A3). A reader who can see the work
            can say something about it without being able to change it, which is the whole point of
            the separate key — so gating this on `issue:edit` would be a second, wrong copy of the
            catalogue.
          */}
          <PermissionGate projectId={projectId} permission="issue:comment">
            {({ allowed }) => (
              <Button
                type="submit"
                variant="primary"
                size="sm"
                disabled={!allowed || post.isPending}
                data-testid={`${testId}.Post`}
              >
                Comment
              </Button>
            )}
          </PermissionGate>
        </div>
        {error && (
          <p role="alert" className="m-0 mt-2 text-[12px] text-[color:var(--color-failed)]">
            {error}
          </p>
        )}
      </form>
    </Panel>
  );
}
