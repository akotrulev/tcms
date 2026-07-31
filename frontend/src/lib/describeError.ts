/**
 * The one place an error becomes user-facing copy.
 *
 * Both surfaces that show a failure read from here — the inline `DataRegion` state and the
 * persistent notification — so a 503 is worded the same way in both, and the rule that a raw
 * `error.message`, response body, or stack never reaches the screen is enforced in one file
 * instead of at every call site.
 *
 * `code` is what the notification stack groups on: it is the *cause*, so six regions failing on
 * one 503 collapse into a single message.
 */

import { ApiError, TimeoutError } from '../api/client';

export interface DescribedError {
  title: string;
  body: string;
  correlationId: string | null;
  /** Grouping cause for the notification roll-up — an HTTP status or an error name. */
  code: string;
  /**
   * Whether "try again" is honest. A 403 or a 404 will fail identically on the next click, and
   * offering a retry there just teaches the user the button is decorative.
   */
  retryable: boolean;
  /** A 401 is not an ordinary failure — it runs the session-recovery ladder instead. */
  isAuth: boolean;
}

export function describeError(error: unknown): DescribedError {
  if (error instanceof TimeoutError) {
    return {
      title: 'That took too long',
      body: 'The server did not respond in time. It may be under load — try again.',
      correlationId: null,
      code: 'timeout',
      // The one failure where retrying is genuinely likely to work.
      retryable: true,
      isAuth: false,
    };
  }

  if (error instanceof ApiError) {
    const shared = { correlationId: error.correlationId, code: String(error.status) };

    if (error.status === 401) {
      return {
        ...shared,
        title: 'Your session ended',
        body: 'Sign in again to continue — nothing you have typed has been lost.',
        retryable: false,
        isAuth: true,
      };
    }
    if (error.status === 403) {
      return {
        ...shared,
        title: "You don't have access to this",
        body: 'Ask a project lead to grant you the permission this view needs.',
        retryable: false,
        isAuth: false,
      };
    }
    if (error.status === 404) {
      return {
        ...shared,
        title: 'Not found',
        // A 404 detail names the missing thing, which is the useful part; it is server copy,
        // not an exception message, so it is safe to show.
        body: error.detail,
        retryable: false,
        isAuth: false,
      };
    }
    if (error.status === 409) {
      /*
        Not every 409 is a lost update, and the two want opposite advice.

        A stale write says "reload and reapply". A write refused because the target is not in a
        state that allows it — deleting a suite that still holds cases — is not fixed by reloading
        at all, and the server's `detail` is the only sentence that says what to do instead ("This
        suite still holds 42 cases. Move or delete them first."). Collapsing both into the
        lost-update wording threw that sentence away and told the user to reload, which changes
        nothing.

        The server distinguishes them by TITLE, so that is what is read here: the lost-update
        problem is the one it titles "Someone else saved first". Anything else keeps its own
        detail. Falling back to the reload copy when there is no detail keeps the previous
        behaviour for a bodiless 409.
      */
      const isLostUpdate = error.title === null || error.title === 'Someone else saved first';
      if (!isLostUpdate && error.detail) {
        return {
          ...shared,
          title: error.title,
          body: error.detail,
          retryable: false,
          isAuth: false,
        };
      }
      return {
        ...shared,
        title: 'Someone else changed this',
        body: 'This was updated while you were working on it. Reload to see their version.',
        retryable: false,
        isAuth: false,
      };
    }
    if (error.status === 429) {
      return {
        ...shared,
        title: 'Too many requests',
        body: 'The server asked us to slow down. It will retry shortly.',
        retryable: true,
        isAuth: false,
      };
    }
    if (error.status >= 500) {
      return {
        ...shared,
        title: "Couldn't load this",
        body: 'The server ran into a problem. Try again in a moment.',
        retryable: true,
        isAuth: false,
      };
    }
    // Remaining 4xx: the request was wrong. The server's own detail is the most useful thing
    // we have, and retrying an unchanged bad request will fail the same way.
    return { ...shared, title: "Couldn't load this", body: error.detail, retryable: false, isAuth: false };
  }

  // Anything else — a network fault, a drifted response that failed to parse, a bug. No detail
  // is safe to show, so it gets the generic body.
  return {
    title: "Couldn't load this",
    body: 'Check your connection and try again.',
    correlationId: null,
    code: error instanceof Error ? error.name : 'unknown',
    retryable: true,
    isAuth: false,
  };
}

/**
 * Whether a failure is worth retrying automatically.
 *
 * The blanket `retry: 1` this replaces retried 4xx too — a 403 got asked twice, doubling the
 * time to show a permission error that was never going to change. Retries are for faults that
 * are plausibly transient: the network, a cold start, a throttle, a timeout.
 */
export function isTransient(error: unknown): boolean {
  if (error instanceof TimeoutError) return true;
  if (error instanceof ApiError) {
    // 408 Request Timeout and 429 Too Many Requests are the two 4xx that mean "later".
    return error.status >= 500 || error.status === 408 || error.status === 429;
  }
  // A TypeError from fetch is the browser's way of saying the request never left.
  return error instanceof TypeError;
}
