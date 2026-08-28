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
  /**
   * The address itself is wrong, not the data behind it — a 404, or one of the malformed-request
   * problems the server raises for an unparseable path parameter. A route that can say so should
   * render the not-found screen, which has a way out, rather than an error panel that does not.
   */
  isAddressWrong: boolean;
}

/**
 * `ProblemErrorAttributes` on the server words these for an API *caller* — "Check the request
 * against the schema in the API contract" is advice for somebody holding the OpenAPI document, and
 * it reached an end user verbatim on `/projects/1/cases` because the generic 4xx arm forwards
 * `error.detail`. The titles are a fixed set the backend ships, and matching on them is the same
 * device `describeError` already uses to tell the two kinds of 409 apart.
 */
const CONTRACT_FACING_PROBLEMS = new Set([
  'The request could not be read',
  'No such endpoint',
  'Method not allowed',
  'Not acceptable',
  'Unsupported content type',
]);

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
      isAddressWrong: false,
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
        isAddressWrong: false,
      };
    }
    if (error.status === 403) {
      return {
        ...shared,
        title: "You don't have access to this",
        body: 'Ask a project lead to grant you the permission this view needs.',
        retryable: false,
        isAuth: false,
        isAddressWrong: false,
      };
    }
    if (error.status === 404) {
      return {
        ...shared,
        title: 'Not found',
        /*
          ~~A 404 detail names the missing thing, which is the useful part; it is server copy,
          not an exception message, so it is safe to show.~~ → superseded 2026-08-09 (§148).

          THERE IS NO DETAIL ON A 404. Every 404 this API writes is bodiless — `notFound().build()`
          at all 94 sites, deliberately, because the only thing a body could add is WHICH of the
          path's ids failed to resolve, and concealing that is what the status is for (§6t). The
          comment above was written against `openapi.yaml`, which declared a `Problem` here until
          §148 and was simply wrong.

          So `error.detail` on this branch was never server copy. It was `client.ts`'s floor —
          `res.statusText`, i.e. the word "Not Found" under the heading "Not found", or the
          fallback sentence "The request failed (HTTP 404)." under it instead. A promise in the
          contract became a tautology on a user's screen, and nothing between the two could see it.

          The honest copy names the two states the server refuses to tell apart, since the reader
          is the one person who can distinguish them — they know whether they expected access.
        */
        body: 'It may have been deleted, or it may be in a project you cannot open.',
        retryable: false,
        isAuth: false,
        isAddressWrong: true,
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
          isAddressWrong: false,
        };
      }
      return {
        ...shared,
        title: 'Someone else changed this',
        body: 'This was updated while you were working on it. Reload to see their version.',
        retryable: false,
        isAuth: false,
        isAddressWrong: false,
      };
    }
    if (error.status === 429) {
      return {
        ...shared,
        title: 'Too many requests',
        body: 'The server asked us to slow down. It will retry shortly.',
        retryable: true,
        isAuth: false,
        isAddressWrong: false,
      };
    }
    if (error.status >= 500) {
      return {
        ...shared,
        title: "Couldn't load this",
        body: 'The server ran into a problem. Try again in a moment.',
        retryable: true,
        isAuth: false,
        isAddressWrong: false,
      };
    }
    /*
      A malformed-request problem is never the reader's fault and never their business. Its detail
      is written for whoever is holding the API contract, and on `/projects/1/cases` — an id that
      is not a UUID — it put "The body is not valid JSON ... Check the request against the schema
      in the API contract" on an end user's screen, twice. What actually happened, in their terms,
      is that the address is wrong.
    */
    if (error.title !== null && CONTRACT_FACING_PROBLEMS.has(error.title)) {
      return {
        ...shared,
        title: 'Not found',
        body: 'That address is not one this app can open. It may be mistyped, or a link that has gone stale.',
        retryable: false,
        isAuth: false,
        isAddressWrong: true,
      };
    }

    // Remaining 4xx: the request was wrong. The server's own detail is the most useful thing
    // we have, and retrying an unchanged bad request will fail the same way.
    return {
      ...shared,
      title: "Couldn't load this",
      body: error.detail,
      retryable: false,
      isAuth: false,
      isAddressWrong: false,
    };
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
    isAddressWrong: false,
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
