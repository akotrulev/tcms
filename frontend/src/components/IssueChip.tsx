import { ExternalLink } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import { Badge } from './kit';
import { Skeleton, Tooltip, TooltipContent, TooltipTrigger } from './ui';
import { useTrackerIssue } from '../api/queries';
import type { IssueLink } from '../api/types';
import {
  trackerProviderLabels,
  trackerStatusCategoryColors,
  trackerStatusCategoryLabels,
  trackerUnavailableReasons,
} from '../lib/labels';

/**
 * One link out to somebody else's tracker — the shared rendering of 004's two states (D16/D17).
 *
 * ## The rule this component exists to hold: no label is ever derived from the href
 *
 * A stored `external_url` is a **pointer** and is allowed to be historical. After a Jira project
 * move it names the old key, and the database cannot know that — Jira redirects the old key, so the
 * href still lands on the right issue, and a *label* parsed out of it would simply lie. Parsing
 * `PAY-991` out of a URL also needs a per-provider regex that fails on any URL shape nobody
 * anticipated, and it goes silently wrong after exactly the event id-only was chosen to survive.
 *
 * So the key on this chip is **only ever the one the tracker returned this request**, and when
 * there is none the chip names the stable id instead. That is the whole reason `IssueLink` carries
 * no key, no title and no status: they were fetched with one person's credential and none of them
 * is a fact this database may hold.
 *
 * ## Two states, and the fallback is deliberately not human-recognisable
 *
 * | Viewer | Chip |
 * |---|---|
 * | Connected, entitled | the **live** key, title and status category — `PAY-991 · Fix the rounding · In progress` |
 * | Not connected, or the tracker is down or refuses, or the binding was deleted (D18) | `Jira issue 10042 ↗` — the provider and the stable id, plus the href |
 *
 * The fallback names the id rather than saying a bare "Issue" because the id is the one string on
 * the row that is **never wrong**, and five external links on one case would otherwise render as
 * five identical chips with no way to tell them apart. It is not something anybody says out loud,
 * and that is the honest residual cost of id-only rather than something to engineer around: during
 * an outage, or before you connect, a list of issue links is harder to scan than it would have
 * been. For anyone who has connected — everyone the feature is for — it never appears.
 *
 * **It is not a confidentiality measure.** The key is in the href regardless, so withholding it
 * from the label protects nobody. The reason is truthfulness: a stale pointer that redirects still
 * gets you there, and a stale label just lies.
 *
 * ## Failure is a state here, never an error boundary
 *
 * A tracker that is down, a token that expired and an issue the viewer cannot see must all render
 * the case, the run and the panel — degraded, link still clickable. `useTrackerIssue` carries
 * `meta: { handled: true }` so none of it raises a notification either: nothing on this screen
 * failed.
 */
export function IssueChip({
  projectId,
  link,
  testId = 'IssueChip',
}: {
  projectId: string;
  link: IssueLink;
  testId?: string;
}) {
  /*
    `link.bindingId` is resolved at read time by matching `(provider, host)` against the project's
    current bindings, and is null when none matches any more — 004 D18, a binding somebody deleted.
    The hook is disabled on null rather than firing to discover it: there is nothing to preview
    *through*, and a request that could only fail is not a way to find out.
  */
  const preview = useTrackerIssue(projectId, link.bindingId, link.issueId);

  const providerLabel = (trackerProviderLabels as Record<string, string>)[link.provider]
    ?? link.provider;

  if (preview.isPending && link.bindingId !== null) {
    return <Skeleton className="h-6 w-[220px] rounded" data-testid={`${testId}.Loading`} />;
  }

  const issue = preview.data?.state === 'AVAILABLE' ? preview.data.issue : null;

  if (issue) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>
          {/* A span around the badge, not the badge itself: `asChild` clones a ref onto its
              child and `Badge` is a plain function component rendering a span, not a
              ref-forwarding element. `BoardPage`'s derived marker is the same shape. */}
          <span data-testid={`${testId}.Live`}>
          <Badge tone={trackerStatusCategoryColors[issue.statusCategory]}>
            <a
              href={issue.url}
              target="_blank"
              rel="noreferrer noopener"
              className="text-inherit no-underline hover:underline"
            >
              {/*
                `statusName` is the WORD and `statusCategory` is the colour — two different
                questions, and conflating them is what makes an unbounded enum look bounded. A
                project's status names are free text and per-project; the category is the bounded
                thing.
              */}
              {issue.key} · {issue.title} · {issue.statusName}
              <ExternalLink aria-hidden className="ml-1 inline size-3 align-text-bottom" />
            </a>
          </Badge>
          </span>
        </TooltipTrigger>
        {/*
          The bounded fact behind the unbounded word: "Pending triage" is this project's spelling
          of "To do", and the tooltip is where the reader can find that out without the chip
          pretending the free-text name is one of three.
        */}
        <TooltipContent>
          {`${trackerStatusCategoryLabels[issue.statusCategory]} in ${providerLabel}`}
        </TooltipContent>
      </Tooltip>
    );
  }

  /*
    Everything else is the degraded chip, and the *reason* differs even though the chip does not.
    `NOT_CONNECTED` is one click from being fixed and `UNAVAILABLE` is not, so they get different
    tooltips — which is exactly the distinction `tcmsFaults.tracker('403')` exists to drive.

    A failed request (the region-level `isError`, as opposed to a degraded 200) lands here too and
    is treated as `UNAVAILABLE`: from the reader's side there is no difference worth a third
    sentence, and inventing one would be this client interpreting somebody else's outage.
  */
  const reason =
    preview.data?.state === 'NOT_CONNECTED'
      ? trackerUnavailableReasons.NOT_CONNECTED
      : trackerUnavailableReasons.UNAVAILABLE;

  return (
    <div className="flex items-center gap-1.5">
      <Tooltip>
        <TooltipTrigger asChild>
          <span data-testid={`${testId}.Degraded`}>
          <Badge tone="plain">
            <a
              href={link.url}
              target="_blank"
              rel="noreferrer noopener"
              className="text-inherit no-underline hover:underline"
            >
              {providerLabel} issue {link.issueId}
              <ExternalLink aria-hidden className="ml-1 inline size-3 align-text-bottom" />
            </a>
          </Badge>
          </span>
        </TooltipTrigger>
        <TooltipContent>{reason}</TooltipContent>
      </Tooltip>
      {/*
        The call to action, offered **only for the one state the reader can do something about**.
        A viewer who has not connected is one screen away from a live chip; a viewer whose tracker
        is down or who may not see that issue is not, and offering them "connect your account"
        would send them to a page where everything already reads as connected.
      */}
      {preview.data?.state === 'NOT_CONNECTED' && (
        <RouterLink
          to="/account/trackers"
          className="text-xs text-steel underline-offset-4 hover:underline"
          data-testid={`${testId}.Connect`}
        >
          Connect
        </RouterLink>
      )}
    </div>
  );
}
