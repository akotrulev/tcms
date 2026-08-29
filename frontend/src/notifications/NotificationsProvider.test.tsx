import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationsProvider } from './NotificationsProvider';
import { useNotify } from './useNotify';

/**
 * The persistent half of the notification split — design language §7's *"a failure never
 * auto-dismisses"* on the surface `app/queryClient.ts` raises every read and write failure to.
 *
 * The rule is asserted on both halves deliberately. `toast.test.tsx` proves the transient surface
 * has no timer for a failure; this proves the persistent one has no timer at all, and that the
 * card carries the reference §7 requires — *"six hex characters and a timestamp… the difference
 * between a bug report and a description of a feeling"*.
 */
function Raiser() {
  const notify = useNotify();
  return (
    <button
      onClick={() =>
        notify.error({
          message: "Couldn't load this list.",
          code: 503,
          region: 'Cases',
          correlationId: '8c31f0',
        })
      }
    >
      raise
    </button>
  );
}

describe('the persistent failure surface', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does not auto-dismiss a failure, and names the reference to quote', () => {
    render(
      <NotificationsProvider>
        <Raiser />
      </NotificationsProvider>,
    );

    act(() => {
      screen.getByText('raise').click();
    });

    expect(screen.getByRole('alert')).toHaveTextContent("Couldn't load this list.");
    expect(screen.getByTestId('Notifications.Error.CorrelationId')).toHaveTextContent('8c31f0');

    act(() => {
      vi.advanceTimersByTime(30_000);
    });

    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});
