import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { useIssueDocLinks, useUpdateIssue } from '../../api/queries';
import type { Issue, IssueDocLink } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Badge } from '../../components/kit';
import { Button } from '../../components/ui';
import { cn } from '../../lib/cn';
import { describeError } from '../../lib/describeError';
import { linkRelationshipLabels } from '../../lib/labels';

/**
 * `epic.html`'s "Linked specification" — the two link cards, one of them stale.
 *
 * A normal card is the page, the section, the excerpt and a way through to the block. A stale one
 * gains a warning border, a *"changed since linked"* badge, and a **Re-confirm** control, under
 * the mockup's own sentence — rendered verbatim, because it is the whole explanation of what
 * `stale` means: *"The excerpt above is what you linked. Re-confirming replaces it with the
 * current text and clears this flag."*
 *
 * <h2>What "re-confirm" actually is, and why there is no endpoint for it</h2>
 *
 * ~~A `documentation` write, per 011 T6 Part B step 5.~~ **There is no such operation on the
 * contract**, and the reason is that `stale` is not a stored decision — it is a comparison.
 * `IssueDocLinkFacts` computes it as *the page's newest version against the issue's own
 * `updated_at`*, and its own javadoc names the consequence deliberately: *"a write that changes
 * nothing a reader cares about still moves `updated_at` and so forgives the flag"*.
 *
 * So re-confirming **is** an edit to the issue, and this button sends the smallest one there is —
 * `PATCH /issues/{key}` with an empty body, which the contract declares legal (`IssueUpdate`
 * requires nothing, and an absent key is "leave it"). That is not a trick standing in for a
 * missing endpoint; it is the mechanism, spelled as the one request that performs it.
 *
 * **Two things about this are unverified against a running server** and are recorded rather than
 * assumed: whether a no-op `PATCH` moves `updated_at` at all, and whether the projection
 * (`issue_doc_link` is rebuilt by an event, not on read) has caught up by the time the refetch
 * lands. If either is false the flag survives the press, which is why the control reports failure
 * and does not claim success on its own.
 */
export function LinkedSpecPanel({
  issue,
  testId = 'Issue.SpecLinks',
}: {
  issue: Issue;
  testId?: string;
}) {
  const links = useIssueDocLinks(issue.key);

  return (
    <Panel
      title="Linked specification"
      testId={testId}
      actions={
        links.data && links.data.length > 0 ? (
          <span className="text-[11.5px] text-ink-4">
            {`${links.data.length} ${links.data.length === 1 ? 'block' : 'blocks'}`}
          </span>
        ) : undefined
      }
    >
      <DataRegion
        testId={`${testId}.List`}
        state={{ ...links, refetch: () => void links.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'by-design',
          title: 'Nothing linked yet',
          /*
            The action is deliberately NOT offered here. `tracker` does not own `doc_link` and this
            endpoint is read-only; the link is made from the documentation page's own block, which
            is where the block a person means is on screen. An "Add a link" button here would open
            a picker asking which paragraph of which page — the question the other screen has
            already answered by being open at it.
          */
          body:
            'A link is made from the specification page, on the block it is about — open the page '
            + 'and use its Link control there.',
        }}
      >
        {(rows) => (
          <div className="flex flex-col gap-3">
            {rows.map((link) => (
              <SpecLinkCard key={link.id} link={link} issue={issue} testId={testId} />
            ))}
          </div>
        )}
      </DataRegion>
    </Panel>
  );
}

function SpecLinkCard({
  link,
  issue,
  testId,
}: {
  link: IssueDocLink;
  issue: Issue;
  testId: string;
}) {
  const reconfirm = useUpdateIssue(issue.key);
  const [error, setError] = useState<string | null>(null);

  const press = async () => {
    setError(null);
    try {
      // The empty edit — see this file's header. It is the request that moves `updated_at`, which
      // is what the staleness comparison is against.
      await reconfirm.mutateAsync({});
    } catch (failure) {
      setError(describeError(failure).body);
    }
  };

  return (
    <div
      data-testid={`${testId}.Item`}
      className={cn(
        'flex flex-col gap-2 rounded-md border p-3',
        link.stale
          ? 'border-[color:color-mix(in_oklab,var(--color-blocked)_34%,transparent)]'
          : 'border-line',
      )}
    >
      <div className="flex flex-wrap items-center gap-2">
        {link.pageId ? (
          <RouterLink
            to={`/projects/${issue.projectId}/docs/${link.pageId}`}
            data-testid={`${testId}.Item.Page`}
            className="text-[12.5px] font-semibold text-ink no-underline hover:text-steel hover:underline"
          >
            {link.pageTitle ?? link.pageKey ?? 'Specification'}
          </RouterLink>
        ) : (
          // An external target — a repository path, a URL, another tracker's issue. It has no page
          // to open at a block, and `stale` is always false for one.
          <span className="text-[12.5px] font-semibold text-ink">
            {link.externalUrl ?? 'External reference'}
          </span>
        )}
        {link.pageKey && <Identifier variant="muted">{link.pageKey}</Identifier>}
        <Badge tone="plain">{linkRelationshipLabels[link.relationship]}</Badge>
        {link.stale && (
          <Badge tone="blocked" data-testid={`${testId}.Item.Stale`}>
            changed since linked
          </Badge>
        )}
        <span className="flex-1" />
        {link.pageId && link.blockId && (
          <Button size="sm" asChild className="min-h-6 px-2 py-0.5 text-[11px]">
            <RouterLink
              // The page's own route, with the block as a query parameter — which is what the
              // "Linked from" panel on the other end reads to highlight it. The page id is a path
              // segment because it selects the document; the block narrows what is shown *in* it.
              to={`/projects/${issue.projectId}/docs/${link.pageId}?block=${link.blockId}`}
              data-testid={`${testId}.Item.Open`}
            >
              Open at this block
            </RouterLink>
          </Button>
        )}
      </div>

      {link.blockExcerpt && (
        <p
          data-testid={`${testId}.Item.Excerpt`}
          className={cn(
            'm-0 border-l-2 pl-3 text-[13px] leading-[1.6] text-ink-3',
            link.stale
              ? 'border-[color:color-mix(in_oklab,var(--color-blocked)_50%,transparent)]'
              : 'border-line',
          )}
        >
          {link.blockExcerpt}
        </p>
      )}

      {link.stale && (
        <div className="flex flex-col gap-1.5">
          <PermissionGate projectId={issue.projectId} permission="issue:edit">
            {({ allowed }) => (
              <Button
                size="sm"
                disabled={!allowed || reconfirm.isPending}
                onClick={() => void press()}
                data-testid={`${testId}.Item.Reconfirm`}
                className="w-fit min-h-6 px-2 py-0.5 text-[11px]"
              >
                Re-confirm
              </Button>
            )}
          </PermissionGate>
          {/*
            Verbatim from `epic.html`. It is the whole explanation of what `stale` means, and it is
            rendered rather than paraphrased because the paraphrase people reach for — "out of
            date" — says the link is wrong, when what it says is that nobody has read the new text.
          */}
          <p className="m-0 text-[11.5px] text-ink-4">
            The excerpt above is what you linked. Re-confirming replaces it with the current text
            and clears this flag.
          </p>
          {error && (
            <p role="alert" className="m-0 text-[11.5px] text-[color:var(--color-failed)]">
              {error}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
