import { Button } from './ui';
import { cn } from '../lib/cn';
import { copyStatusText, useClipboardCopy } from '../lib/useClipboardCopy';

/**
 * A single value shown to be copied — a token, an id, an endpoint (§118).
 *
 * ## The value is always readable, and the button is an accelerator
 *
 * `navigator.clipboard` is **not available on an insecure origin**. This product is self-hosted;
 * the homelab target is reached over plain HTTP today and a client's first install will be too, so
 * on a real deployment `navigator.clipboard` is `undefined` and a copy button is the only thing on
 * screen that does nothing when pressed. A reveal that depends on it would be a screen that hands
 * over an unrecoverable credential and then refuses to give it to you.
 *
 * So the text is rendered, selectable, in full, and the button is a convenience over it. The state
 * machine behind it — including what happens when the clipboard is missing or rejects — lives in
 * `lib/useClipboardCopy.ts`, shared with {@link CopyBlock}; see that file for why each of the three
 * states behaves as it does.
 *
 * ## Three states, one live region
 *
 * Idle, copied and unavailable are announced through a single `role="status"` node rather than by
 * swapping the button's label alone: a screen-reader user pressing Copy otherwise gets no
 * confirmation at all, because a re-labelled button they are still focused on is not re-announced
 * reliably.
 *
 * **One value per field.** Anything multi-line — a configuration file, a block of commands —
 * belongs in {@link CopyBlock}, which keeps its own line breaks and monospace block rather than
 * squeezing them into a one-line field.
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
  const { outcome, copy } = useClipboardCopy(value);

  return (
    <div>
      <span className="mb-1 block text-[12px] text-ink-2">{label}</span>
      <div className="flex items-start gap-2">
        {/* `break-all` and `select-all`: a 64-character secret must wrap rather than scroll out
            of view, because the whole point of rendering it is that every character can be read
            and selected. */}
        <div
          className="identifier flex-1 rounded-sm border border-line bg-field p-2.5 text-[13px] break-all select-all"
          data-testid={`${testId}.Value`}
        >
          {value}
        </div>
        <Button size="sm" onClick={copy} data-testid={`${testId}.Copy`}>
          Copy
        </Button>
      </div>
      <span
        role="status"
        data-testid={`${testId}.Status`}
        className={cn(
          'mt-1 block text-[12px]',
          outcome === 'failed' ? 'text-[color:var(--color-warning)]' : 'text-ink-2',
        )}
      >
        {copyStatusText(outcome)}
      </span>
    </div>
  );
}
