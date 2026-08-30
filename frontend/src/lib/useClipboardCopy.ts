import { useEffect, useRef, useState } from 'react';

/**
 * The clipboard, as a three-state machine — shared by every copy affordance in the app.
 *
 * Extracted from `CopyField` when a second one appeared (`CopyBlock`, for the MCP configuration),
 * because the part worth getting right is not the button:
 *
 *   - **`navigator.clipboard` is absent on an insecure origin.** This product is self-hosted and
 *     the first install of one is reached over plain HTTP, so the whole object is `undefined` —
 *     a missing capability, not a rejected promise, and nothing to `catch`. A copy button that
 *     depends on it is the one control on screen that does nothing when pressed.
 *   - **A rejection is terminal.** No retry and no toast: the value is on screen, the remedy is to
 *     select it, and saying so is worth more than a second attempt that fails the same way.
 *   - **The confirmation expires.** A `role="status"` node that keeps claiming a copy from a
 *     minute ago is lying to the one user who cannot see the value was already taken.
 *
 * The timer is cleared on unmount, so a dialog closed a second after Copy does not set state on a
 * component that is gone.
 */
export type CopyOutcome = 'idle' | 'copied' | 'failed';

/** How long the confirmation stands before the live region goes quiet again. */
const CONFIRMATION_MS = 4000;

export function useClipboardCopy(value: string): {
  outcome: CopyOutcome;
  copy: () => void;
} {
  const [outcome, setOutcome] = useState<CopyOutcome>('idle');
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => () => clearTimeout(timer.current), []);

  const copy = () => {
    clearTimeout(timer.current);
    const clipboard = navigator.clipboard as Clipboard | undefined;
    if (!clipboard) {
      setOutcome('failed');
      return;
    }
    void clipboard
      .writeText(value)
      .then(() => {
        setOutcome('copied');
        timer.current = setTimeout(() => setOutcome('idle'), CONFIRMATION_MS);
      })
      .catch(() => setOutcome('failed'));
  };

  return { outcome, copy };
}

/**
 * What the live region says. Held beside the hook so both affordances announce a failure
 * identically — the sentence names the fallback, because the fallback is the whole reason the
 * value is rendered as selectable text.
 */
export function copyStatusText(outcome: CopyOutcome): string {
  switch (outcome) {
    case 'copied':
      return 'Copied.';
    case 'failed':
      return 'Your browser blocked the clipboard — select the text and copy it.';
    case 'idle':
      return '';
  }
}
