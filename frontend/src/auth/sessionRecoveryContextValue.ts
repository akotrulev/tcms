import { createContext } from 'react';

/**
 * The seam between "a call came back 401" and "the UI does something about it".
 *
 * Kept as a Context rather than a module singleton because the recovery is a *rendered* overlay
 * with React state, and because a test needs to be able to mount a stub.
 */

export interface SessionRecoveryValue {
  /**
   * Called when one of our own API calls returns 401 mid-session.
   *
   * Does **not** navigate. It raises the in-place re-auth overlay, which is the whole point:
   * a redirect to `/login` unmounts the route and takes any half-filled form with it.
   */
  reportUnauthorized: () => void;

  /**
   * Registers that this surface holds work which would be lost by a navigation or reload —
   * a dirty form, a part-recorded execution. Returns an unregister function.
   *
   * The 401 ladder and the deploy-skew recovery both consult this before doing anything
   * destructive, which is the only reason either of them is safe to run automatically.
   */
  registerUnsavedWork: (label: string) => () => void;

  /** True while any surface has unsaved work registered. */
  hasUnsavedWork: () => boolean;

  /**
   * Registers what to do once the user has signed back in — in practice, refetching the queries
   * that failed with a 401. Returns an unregister function.
   *
   * This indirection resolves a genuine ordering conflict. `SessionRecoveryProvider` has to sit
   * **above** `QueryClientProvider`, because the query client's global error handlers need
   * `reportUnauthorized` at construction time. But the replay itself needs the query client,
   * which only exists **below**. Calling `useQueryClient()` in the provider therefore throws
   * "No QueryClient set" — so instead a small bridge component inside the provider registers the
   * replay, and the ordering works in both directions.
   */
  registerReauthHandler: (handler: () => void | Promise<void>) => () => void;
}

export const SessionRecoveryContext = createContext<SessionRecoveryValue | null>(null);
