import { useEffect, useRef, useState } from 'react';
import { Button } from './ui';
import { cn } from '../lib/cn';

/**
 * A value shown to be copied — the app's one clipboard affordance (§118).
 *
 * ## The value is always readable, and the button is an accelerator
 *
 * `navigator.clipboard` is **not available on an insecure origin**. This product is self-hosted;
 * the homelab target is reached over plain HTTP today and a client's first install will be too, so
 * on a real deployment `navigator.clipboard` is `undefined` and a copy button is the only thing on
 * screen that does nothing when pressed. A reveal that depends on it would be a screen that hands
 * over an unrecoverable credential and then refuses to give it to you.
 *
 * So the text is rendered, selectable, in full, and the button is a convenience over it. When the
 * clipboard is missing or rejects, the button says what to do instead rather than failing silently
 * — `error-handling`'s rule that a failure must be visible, applied to the smallest possible
 * surface.
 *
 * ## Three states, one live region
 *
 * Idle, copied, and unavailable are announced through a single `role="status"` node rather than by
 * swapping the button's label alone: a screen-reader user pressing Copy otherwise gets no
 * confirmation at all, because a re-labelled button they are still focused on is not re-announced
 * reliably. The confirmation clears itself after a few seconds so the region does not keep
 * claiming a copy that happened a minute ago.
 */
export function CopyField({
  label,
  value,
  testId,
}: {
  label: string;
  value: string;
  /** Prefix. The component composes `.Value`, `.Copy` and `.Status` onto it. */
  testId: string;
}) {
  const [outcome, setOutcome] = useState<'idle' | 'copied' | 'failed'>('idle');
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // Cleared on unmount, so a dialog closed a second after Copy does not set state on a component
  // that no longer exists.
  useEffect(() => () => clearTimeout(timer.current), []);

  const copy = async () => {
    clearTimeout(timer.current);
    // Checked rather than assumed: on an insecure origin the whole `clipboard` object is absent,
    // so this is a missing capability and not a rejected promise — there is nothing to catch.
    const clipboard = navigator.clipboard as Clipboard | undefined;
    if (!clipboard) {
      setOutcome('failed');
      return;
    }
    try {
      await clipboard.writeText(value);
      setOutcome('copied');
      timer.current = setTimeout(() => setOutcome('idle'), 4000);
    } catch {
      // Deliberately terminal — no retry, no toast. The value is on screen; the remedy is to
      // select it, and saying so is more useful than a second attempt that will fail the same way.
      setOutcome('failed');
    }
  };

  return (
    <div>
      <span className="mb-1 block text-xs text-ink-2">{label}</span>
      <div className="flex items-start gap-2">
        {/* `break-all` and `select-all`: a 64-character secret must wrap rather than scroll out
            of view, because the whole point of rendering it is that every character can be read
            and selected. */}
        <div
          className="identifier flex-1 rounded border border-line bg-field p-2.5 text-[13px] break-all select-all"
          data-testid={`${testId}.Value`}
        >
          {value}
        </div>
        <Button size="sm" onClick={() => void copy()} data-testid={`${testId}.Copy`}>
          Copy
        </Button>
      </div>
      <span
        role="status"
        data-testid={`${testId}.Status`}
        className={cn(
          'mt-1 block text-xs',
          outcome === 'failed' ? 'text-[color:var(--color-warning)]' : 'text-ink-2',
        )}
      >
        {outcome === 'copied'
          ? 'Copied.'
          : outcome === 'failed'
            ? 'Your browser blocked the clipboard — select the text above and copy it.'
            : ''}
      </span>
    </div>
  );
}
