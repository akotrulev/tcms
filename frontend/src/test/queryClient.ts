import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { reportClientError } from '../lib/reportClientError'

/**
 * A fresh, silent, retry-free `QueryClient` per test.
 *
 * Three deliberate differences from `app/queryClient.ts`, and each prevents a specific kind of
 * bad test rather than being generic hygiene:
 *
 *   - **`retry: false`.** The app's real policy retries transient failures with exponential
 *     backoff. Under test that turns "assert the failed state" into a multi-second wait, and
 *     then into a timeout — the test would be measuring the backoff schedule.
 *   - **`gcTime: Infinity` + a new client per test.** Sharing one client across tests leaks an
 *     answer from one test's `/me/permissions` into the next test's gate, which is the exact
 *     bug `useCan` exists to prevent and would then be unable to fail on.
 *   - **No notification handlers.** `app/queryClient.ts` raises a notification on every failure;
 *     wiring that here would require the notification provider in every test tree and assert
 *     nothing about the unit under test.
 *
 * ## The fourth difference used to be that nothing was reported at all
 *
 * The third bullet above said *"no global error handlers"* until §202, and that was one sentence
 * covering two different things. Raising a **notification** needs the provider and is rightly out;
 * **reporting to the sink** needs nothing at all, and its absence held a door shut that
 * `renderFailures.ts` was standing at.
 *
 * `DataRegion` renders `<testId>.Error` when a *fetch* fails, which is §198's defect one door over:
 * a secondary panel reading an endpoint the double has no arm for shows an alert while the test's
 * assertions, all aimed at the primary panel, pass. The app reports those (source `query`), so the
 * shape looked reachable from `renderFailures` — and was not, because no report was ever made under
 * test. §201 measured it: breaking one arm of `ApiTokensPage.test.tsx`'s double so its own
 * `throw new Error('unmocked read: …')` fired produced **no report at all**, and thirteen test files
 * wrote that throw to be loud.
 *
 * So the two caches below exist for exactly one consumer, `renderFailures.ts`, and they carry the
 * **same context the app carries** — the query key, the mutation key — because a failure message
 * that cannot name the request names nothing. `isAbort` is not filtered here: `reportClientError`
 * drops cancellations itself, and duplicating the guard would put a second copy of that decision in
 * the harness.
 */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        reportClientError(error, 'query', { queryKey: JSON.stringify(query.queryKey).slice(0, 200) })
      },
    }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        const key = mutation.options.mutationKey
        reportClientError(error, 'mutation', key ? { mutationKey: JSON.stringify(key).slice(0, 200) } : null)
      },
    }),
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity, staleTime: 0 },
      mutations: { retry: false },
    },
  })
}
