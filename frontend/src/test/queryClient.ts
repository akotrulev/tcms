import { QueryClient } from '@tanstack/react-query'

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
 *   - **No global error handlers.** `app/queryClient.ts` raises a notification on every
 *     failure; wiring that here would require the notification provider in every test tree and
 *     assert nothing about the unit under test.
 */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity, staleTime: 0 },
      mutations: { retry: false },
    },
  })
}
