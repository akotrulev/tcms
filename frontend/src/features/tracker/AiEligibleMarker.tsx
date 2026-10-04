import { cn } from '../../lib/cn';

/**
 * The `AI-ELIGIBLE` marker — **one component for the task page, the work table and the board
 * card**, because three spellings of one flag is how two of them end up saying different things.
 *
 * It is the render's `.ai-tag`: the accent's own hue, at the smallest weight in the type scale, so
 * it reads as a marker on a row rather than as a status. A {@link Badge} would have been the
 * obvious reach and is the wrong one — the badge tones are the *result* vocabulary (passed, failed,
 * blocked), and borrowing one would put a test-result colour on a routing flag.
 *
 * **It renders nothing when the flag is false**, rather than a greyed "not eligible". The absence is
 * the statement: most issues in a project are not flagged, and a marker on every row that says so
 * is noise on the ninety per cent to inform the ten.
 *
 * `size` is the only prop beyond the flag, and it exists because the two surfaces genuinely differ:
 * a task page has room for the word and a board card has room for two letters. It is not a styling
 * hook — the colours and the shape are the same in both.
 */
export function AiEligibleMarker({
  eligible,
  size = 'compact',
  className,
  'data-testid': testId,
}: {
  eligible: boolean;
  /** `full` says `AI-ELIGIBLE` — the task page. `compact` says `AI` — a row or a card. */
  size?: 'full' | 'compact';
  className?: string;
  'data-testid'?: string;
}) {
  if (!eligible) return null;

  return (
    <span
      data-testid={testId}
      title="Flagged AI-eligible — it reaches the owner's assistant queue"
      className={cn(
        'inline-flex items-center rounded-sm border border-[color:var(--color-steel-line)] bg-[color:var(--color-steel-glow)]',
        'px-[5px] text-[10px] font-semibold tracking-[.04em] text-steel',
        className,
      )}
    >
      {size === 'full' ? 'AI-ELIGIBLE' : 'AI'}
    </span>
  );
}
