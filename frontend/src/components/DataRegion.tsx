import type { ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { isAbort } from '../api/client';
import { describeError } from '../lib/describeError';
import { RegionErrorBoundary } from './ErrorBoundaries';
import { formatTimeOfDay } from '../lib/format';
import { Identifier } from './Identifier';
import { Banner } from './kit';
import { Button, Skeleton } from './ui';

/**
 * The three-state region — the single binding that gives every list, panel, and tile in the
 * app correct loading, failed, and empty behaviour.
 *
 * Two properties matter more than the markup:
 *
 *   1. **The region wraps only the response-dependent subtree.** A page's search bar,
 *      filters, and toolbar render *above* this, so a failed load empties the rows without
 *      unmounting the controls the user needs in order to retry or change the query.
 *   2. **A failed *refetch* keeps its data and marks it stale.** Throwing away rows you
 *      already have because a background refresh failed is strictly worse than showing them
 *      with an honest staleness marker.
 *
 * Because this is bound in one place, fixing it here fixes it everywhere (ui-rules §8).
 */

export interface DataRegionState<T> {
  data: T | undefined;
  isPending: boolean;
  isError: boolean;
  error: unknown;
  isFetching?: boolean;
  refetch?: () => void;
  /**
   * When this region's data last loaded successfully (TanStack Query's `dataUpdatedAt`).
   * The stale marker needs it: "showing older data" without saying *how* old leaves the user
   * unable to judge whether it still matters.
   */
  dataUpdatedAt?: number;
}

interface DataRegionProps<T> {
  /** Locator root; the states render as `<testId>.Loading` / `.Error` / `.Empty` / `.Stale`. */
  testId: string;
  state: DataRegionState<T>;
  /** True when the loaded data is present but contains nothing to show. */
  isEmpty?: (data: T) => boolean;
  empty?: EmptyStateProps;
  /** Skeleton shape — approximate the real layout so there's no shift when data lands. */
  skeleton?: ReactNode;
  children: (data: T) => ReactNode;
}

export function DataRegion<T>({
  testId,
  state,
  isEmpty,
  empty,
  skeleton,
  children,
}: DataRegionProps<T>) {
  const { data, isPending, isError, error, isFetching, refetch, dataUpdatedAt } = state;

  // A cancelled request is not a failure and must never be rendered as one. It happens on
  // every navigation and on every superseded filter change.
  const genuinelyFailed = isError && !isAbort(error);

  // First load failed, and there is nothing to fall back to.
  if (genuinelyFailed && data === undefined) {
    const { title, body, correlationId, retryable, isRefused } = describeError(error);
    // A retry is offered only where it could plausibly succeed. A 403 or 404 will fail the
    // same way on the next click, and a button that never works is worse than no button.
    const showRetry = !!refetch && retryable;
    return (
      <div className="my-2" data-testid={`${testId}.Error`}>
        <Banner tone={isRefused ? 'warn' : 'bad'}>
          <div className="min-w-0">
            <p className="m-0 text-sm font-semibold text-ink">{title}</p>
            <p className={showRetry || isRefused ? 'mt-1 mb-3' : 'mt-1 mb-0'}>{body}</p>
            {/*
              The Refused state's way out (005 C1b). `describeError` has already named the
              permission and said to ask a Company Admin; this is the link that lets them find one.

              **The org chart and nothing more specific.** C1b dropped the endpoint that would have
              answered "who can grant this here", so there is no named individual to offer — and
              the chart is a screen every member may open, which already discloses a company's
              administrators on `OrgChartPerson.companyRoles`. It is rendered as its own affordance
              rather than woven into the sentence so the notification stack, which shows the same
              copy with no link, is not showing a sentence with a hole in it.

              A `.Refused` testid of its own: the difference between this and any other failed
              region is the whole of 005 C1, and asserting on the alert alone cannot see it.
            */}
            {isRefused && (
              <p className="m-0 mb-3" data-testid={`${testId}.Refused`}>
                <RouterLink
                  to="/org-chart"
                  className="text-steel underline-offset-4 hover:underline"
                >
                  Find a Company Admin on the org chart
                </RouterLink>
              </p>
            )}
            {showRetry && (
              <Button
                size="sm"
                variant="danger"
                onClick={refetch}
                data-testid={`${testId}.Retry`}
              >
                Try again
              </Button>
            )}
            {correlationId && (
              <p className="mt-2 mb-0 text-xs text-ink-3">
                Reference <Identifier variant="muted">{correlationId}</Identifier>
              </p>
            )}
          </div>
        </Banner>
      </div>
    );
  }

  if (isPending || data === undefined) {
    return (
      <div data-testid={`${testId}.Loading`} aria-busy="true" aria-live="polite">
        {skeleton ?? <DefaultSkeleton />}
      </div>
    );
  }

  return (
    <div className="relative">
      {/*
        A refetch that failed: the data is still on screen, so say so rather than pretending
        the numbers are current. This is the state that most often goes unbuilt, and it's the
        one where a user makes a decision on stale figures without knowing it.
      */}
      {genuinelyFailed && (
        <div className="mb-2" data-testid={`${testId}.Stale`}>
          <Banner tone="warn">
            <div className="min-w-0">
              {/*
                Naming the time is the difference between an honest stale marker and a vague one.
                "Older data" gives the user nothing to judge; "as of 14:02 UTC" lets them decide
                whether it still supports the decision they were about to make.
              */}
              <p className="m-0">
                Showing data from {formatTimeOfDay(dataUpdatedAt)} — the latest refresh failed.
              </p>
              {refetch && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={refetch}
                  data-testid={`${testId}.Retry`}
                  className="mt-1"
                >
                  Try again
                </Button>
              )}
            </div>
          </Banner>
        </div>
      )}

      {/* A background refresh is a hairline progress hint, not a spinner that hides content. */}
      {isFetching && !genuinelyFailed && (
        <div
          data-testid={`${testId}.Refreshing`}
          aria-hidden="true"
          className="absolute inset-x-0 top-0 z-2 h-0.5 overflow-hidden bg-transparent"
        >
          <span className="absolute inset-0 origin-left bg-steel opacity-50 motion-safe:animate-[tcmsIndeterminate_1.1s_ease-in-out_infinite]" />
        </div>
      )}

      {/*
        The rendered data is wrapped in its own boundary.

        A 200 whose body has drifted — a field renamed, an object where an array was expected —
        does not fail the fetch, so it arrives here as "success" and throws when the children
        try to read it. Without this boundary that throw escapes to the *route* boundary and
        unmounts the whole page, taking the filters and the user's typed query with it: exactly
        the whole-page-for-one-region failure the scoping rule exists to prevent.

        Catching it here keeps the degradation where the data was, and it does so for every
        region in the app from one place.

        This is a backstop, not a substitute for validating responses at the fetch boundary.
        Parsing each response against a schema would categorize the failure properly (and catch
        drift that renders *without* throwing, which is the more dangerous case) — that belongs
        with the API client and is tracked as follow-up.
      */}
      <RegionErrorBoundary testId={testId} label="this content">
        <RegionContent data={data} isEmpty={isEmpty} empty={empty} testId={testId}>
          {children}
        </RegionContent>
      </RegionErrorBoundary>
    </div>
  );
}

/**
 * Everything that actually *reads* the response, kept in a child component so it renders inside
 * the boundary above.
 *
 * `isEmpty` is the reason this exists rather than being inlined. It is a predicate over the
 * response body (`(paged) => paged.items.length === 0`), so a drifted shape throws *there* —
 * and evaluated in the parent it would throw outside the boundary and escape to the route,
 * blanking the page. One component boundary moves it inside.
 */
function RegionContent<T>({
  data,
  isEmpty,
  empty,
  testId,
  children,
}: {
  data: T;
  isEmpty?: (data: T) => boolean;
  empty?: EmptyStateProps;
  testId: string;
  children: (data: T) => ReactNode;
}) {
  return (isEmpty?.(data) ?? false) ? (
    <EmptyState testId={`${testId}.Empty`} {...empty} />
  ) : (
    children(data)
  );
}

function DefaultSkeleton() {
  return (
    <div className="flex flex-col gap-2 py-2">
      {[0, 1, 2, 3, 4].map((i) => (
        <Skeleton key={i} className="h-9 rounded" />
      ))}
    </div>
  );
}

/** Skeleton shaped like a grid, so rows don't jump when the real ones arrive. */
export function GridSkeleton({ rows = 8 }: { rows?: number }) {
  return (
    <div className="flex flex-col gap-1 p-2">
      <Skeleton className="h-10 rounded" />
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-11 rounded" />
      ))}
    </div>
  );
}

// ------------------------------------------------------------------ empty state ----

export interface EmptyStateProps {
  /**
   * Which kind of emptiness this is. The three read differently because they need
   * different things from the user, and one generic "No data" message for all three is
   * how an empty grid becomes indistinguishable from a broken one.
   */
  kind?: 'no-records' | 'no-filter-match' | 'by-design';
  title?: string;
  body?: string;
  action?: ReactNode;
  /** Shown for `no-filter-match` so the user can escape a filter that found nothing. */
  onClearFilters?: () => void;
  testId?: string;
}

export function EmptyState({
  kind = 'no-records',
  title,
  body,
  action,
  onClearFilters,
  testId = 'EmptyState',
}: EmptyStateProps) {
  const copy =
    kind === 'no-filter-match'
      ? {
          title: title ?? 'No matches',
          body: body ?? 'Nothing matches the current filters. Clear them to see everything again.',
        }
      : kind === 'by-design'
        ? { title: title ?? 'Nothing here', body: body ?? '' }
        : { title: title ?? 'Nothing yet', body: body ?? '' };

  return (
    <div data-testid={testId} className="px-6 py-12 text-center text-ink-2">
      <h3 className="m-0 mb-1 text-[15px] font-semibold text-ink">{copy.title}</h3>
      {copy.body && <p className="mx-auto mt-0 mb-0 max-w-[46ch] text-sm">{copy.body}</p>}
      {(action || onClearFilters) && (
        <div className="mt-4 flex justify-center gap-2">
          {action}
          {onClearFilters && (
            <Button size="sm" onClick={onClearFilters} data-testid={`${testId}.ClearFilters`}>
              Clear filters
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
