import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Toaster } from '../components/ui';
import { toast } from './toast';

/**
 * Design language §7: *"success may auto-dismiss on 3–5 s; **a failure never does**"*.
 *
 * This is the rule no type can carry. On the superseded surface the timer came from the provider
 * and the `variant` came from the call site, so nothing connected the two — a page could raise
 * `variant: 'error'` onto a four-second auto-hide and every gate would stay green. `toast.ts`'s
 * mapping is now the only thing that sets a duration, and this says so out loud.
 *
 * Thirty seconds of fake time is well past any plausible auto-dismiss *and* past sonner's exit
 * animation, so a toast still in the DOM after it is one with no timer at all rather than a slow
 * one. `shouldAdvanceTime` is on because sonner renders a toast a tick after it is raised, and a
 * frozen clock never reaches that tick.
 */
describe('the transient notification surface', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does not auto-dismiss a failure', async () => {
    render(<Toaster />);
    toast("Couldn't record the result. Check your connection and try again.", { variant: 'error' });

    expect(await screen.findByTestId('Toast.Error')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(30_000);
    });

    expect(screen.getByTestId('Toast.Error')).toBeInTheDocument();
  });

  it('auto-dismisses a success', async () => {
    render(<Toaster />);
    toast('Created TC-194', { variant: 'success' });

    expect(await screen.findByTestId('Toast.success')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(30_000);
    });

    expect(screen.queryByTestId('Toast.success')).not.toBeInTheDocument();
  });
});
