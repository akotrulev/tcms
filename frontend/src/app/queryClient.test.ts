import { describe, expect, it } from 'vitest';

import { ApiError } from '../api/client';
import { RETRY_BUDGET_MS, retryDelay, shouldRetry } from './queryClient';

/**
 * The retry policy, exercised directly.
 *
 * It is exported for exactly that reason. As a closure on the client it could only be driven by
 * standing up a query and waiting out its real delays — which is how the 2026-08-30 renders audit's
 * finding 9.3 shipped with a green suite: with `tcmsFaults.status(503, 30)` armed, three retries
 * each obeying a thirty-second `Retry-After` kept the Board in its **loading skeleton for 56+
 * seconds**, with no failed state, no stale marker, no message and zero notifications. §7's point is
 * that the six states must be unmistakable, and there the loading state was indistinguishable from
 * a permanently broken one.
 */
const transient = (retryAfterSeconds?: number) =>
  new ApiError(503, 'unavailable', null, null, retryAfterSeconds ? String(retryAfterSeconds) : null);

describe('the retry budget', () => {
  it('does not wait silently when the server asks for longer than the budget', () => {
    // The finding, in one assertion. `Retry-After: 30` is still obeyed — nothing is re-sent early —
    // but it is no longer WAITED ON: the region goes to FAILED, which offers a manual retry and can
    // quote the server's own number.
    expect(shouldRetry(0, transient(30))).toBe(false);
  });

  it('still retries a transient failure the server said nothing about', () => {
    expect(shouldRetry(0, transient())).toBe(true);
    expect(shouldRetry(1, transient())).toBe(true);
  });

  it('still stops after three attempts', () => {
    expect(shouldRetry(3, transient())).toBe(false);
  });

  it('obeys a short Retry-After, because a throttle must not become a ban', () => {
    expect(shouldRetry(0, transient(2))).toBe(true);
    expect(retryDelay(0, transient(2))).toBe(2_000);
  });

  it('never retries something that was never going to change', () => {
    // The policy this replaced was a blanket `retry: 1`, which asked a 403 twice.
    expect(shouldRetry(0, new ApiError(403, 'forbidden'))).toBe(false);
    expect(shouldRetry(0, new ApiError(404, 'not found'))).toBe(false);
  });

  it('spends at most the budget across every attempt it will make', () => {
    const total = [0, 1, 2].reduce((sum, attempt) => sum + retryDelay(attempt, transient()), 0);
    expect(total).toBeLessThanOrEqual(RETRY_BUDGET_MS);
  });
});
