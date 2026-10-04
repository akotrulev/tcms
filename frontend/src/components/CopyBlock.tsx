import { Button } from './ui';
import { ScrollX } from './ScrollX';
import { cn } from '../lib/cn';
import { copyStatusText, useClipboardCopy } from '../lib/useClipboardCopy';

/**
 * A multi-line block — a configuration file, an install command — with one button that takes the
 * whole thing.
 *
 * ## Why this is not a prop on the help page's `Terminal`
 *
 * `Terminal` is a prose primitive: it renders a block of text inside the guide's document surface
 * and knows nothing about the app. This one is a component with state and a clipboard, and it is
 * used on two very different screens — the guide, and the reveal step of the issue-token dialog,
 * where the block carries a credential that exists for one render. Putting a button and a live
 * region inside a prose primitive would give every command block in the guide a control it does
 * not need, and would make `prose.tsx` — deliberately thin, deliberately stateless — the owner of
 * a browser capability.
 *
 * ## The text is selectable and complete, exactly as in `CopyField`
 *
 * Same reason, and it matters more here: on an insecure origin — which is what a self-hosted first
 * install is — there is no clipboard at all, and a config block that can only be *copied* would be
 * a config a user cannot obtain. Lines are never wrapped soft: a broken command fails when pasted,
 * and a wrapped JSON block hides which brace closed what, so long lines scroll.
 */
export function CopyBlock({
  label,
  value,
  testId,
}: {
  /** What the block is, in two or three words. Also names the button for a screen reader. */
  label: string;
  value: string;
  /** Prefix. The component composes `.Value`, `.Copy` and `.Status` onto it. */
  testId: string;
}) {
  const { outcome, copy } = useClipboardCopy(value);

  return (
    <div className="mb-4" data-testid={testId}>
      <div className="rounded-lg border border-line bg-field">
        <div className="flex items-center justify-between gap-3 border-b border-line px-3 py-1.5">
          <span className="font-mono text-[11px] font-medium tracking-[.07em] text-ink-4 uppercase">
            {label}
          </span>
          <Button
            size="sm"
            variant="quiet"
            onClick={copy}
            aria-label={`Copy ${label}`}
            data-testid={`${testId}.Copy`}
          >
            Copy
          </Button>
        </div>
        <ScrollX>
          <pre
            className="m-0 px-4 py-3 font-mono text-[12.5px] leading-[1.6] text-ink-2 select-all"
            data-testid={`${testId}.Value`}
          >
            {value}
          </pre>
        </ScrollX>
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
