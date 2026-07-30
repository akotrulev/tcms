import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError, isAbort, retryAfterMs } from '../api/client';
import { describeError, isTransient } from '../lib/describeError';
import { reportClientError } from '../lib/reportClientError';
import type { NotifyValue } from '../notifications/notificationsContextValue';

/**
 * The query client, its retry policy, and the **one** place a failed request raises a
 * notification.
 *
 * ## Why the raise point is global
 *
 * Before this, four pages called `enqueueSnackbar` on their own and every other failure was
 * silent — a read that failed showed its inline region state and nothing else, so a user who had
 * scrolled past the region never learned anything had gone wrong. Wiring it on the caches means
 * one change covers every current and future query.
 *
 * The corollary matters just as much: a call site that raises its own, more specific message must
 * **opt out**, or the user gets two notifications for one failure. That is what
 * `meta: { handled: true }` is for.
 */

export interface QueryClientHandlers {
  notify: NotifyValue;
  /** Runs the session-recovery ladder. Called for a 401 only, never for a 403. */
  onUnauthorized: () => void;
}

/** Stop automatic retries after this many consecutive failures for one key. */
const MAX_RETRIES = 3;

export function createAppQueryClient(handlers: QueryClientHandlers): QueryClient {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (isAbort(error)) return;
        reportClientError(error, 'query', { queryKey: JSON.stringify(query.queryKey).slice(0, 200) });

        const described = describeError(error);

        // A 401 is not an ordinary failure — it is a session that ended, and it has its own
        // recovery. Notifying as well would put "couldn't load this" next to a dialog that
        // already explains the situation better.
        if (described.isAuth) {
          handlers.onUnauthorized();
          return;
        }

        // The call site is rendering its own message for this failure.
        if (query.meta?.handled) return;

        handlers.notify.error({
          message: described.body,
          code: described.code,
          correlationId: described.correlationId,
          region: typeof query.meta?.region === 'string' ? query.meta.region : undefined,
          // The inline region owns the primary retry affordance; the notification offers one too
          // only where retrying is honest, and where the group may span regions the user cannot
          // see from here.
          onRetry: described.retryable ? () => void query.fetch() : undefined,
        });
      },
    }),

    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (isAbort(error)) return;
        reportClientError(error, 'mutation');

        const described = describeError(error);
        if (described.isAuth) {
          handlers.onUnauthorized();
          return;
        }

        // A failed *write* is always the user's own action, so it is always surfaced — but a
        // page that can say something specific ("Couldn't move the ticket — it's back in
        // Review") says it better than this generic handler can, and opts out via meta.
        if (mutation.meta?.handled) return;

        // 422 field errors belong on the inputs, not in a corner of the screen. The form's own
        // `setError` path renders them; a notification here would leave the user hunting.
        if (error instanceof ApiError && error.hasFieldErrors) return;

        handlers.notify.error({
          message: described.body,
          code: described.code,
          correlationId: described.correlationId,
        });
      },
    }),

    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,

        /**
         * The retry policy this replaces was a blanket `retry: 1`, which retried everything —
         * including 4xx. A 403 was therefore requested twice, doubling the time to show a
         * permission error that was never going to change, and a 404 likewise.
         *
         * Retries are for faults that are plausibly transient, and they stop: a query left
         * auto-retrying through a twenty-minute outage burns battery, fills logs, and keeps
         * hammering a service that is already down. After `MAX_RETRIES` the region switches to
         * manual-retry-only, which is exactly what its failed state offers.
         */
        retry: (failureCount, error) => failureCount < MAX_RETRIES && isTransient(error),

        retryDelay: (attemptIndex, error) => {
          // When the server has said when to come back, obey it. Backing off less than a 429 or
          // 503 asked for is how a throttle becomes a ban.
          const serverAsked = retryAfterMs(error);
          if (serverAsked !== null) return Math.min(serverAsked, 60_000);
          return Math.min(1_000 * 2 ** attemptIndex, 30_000);
        },
      },

      mutations: {
        /**
         * Writes are never retried automatically. A retried POST that already succeeded on the
         * server creates a second record — and a timed-out write is exactly the case where it
         * looks safest and is not.
         *
         * Retrying a write is a decision the user makes, from a control that carries an
         * idempotency key for the intent (see `newIntentKey` in `api/client.ts`), so the server
         * can recognize the second attempt as the same one.
         */
        retry: false,
      },
    },
  });
}
