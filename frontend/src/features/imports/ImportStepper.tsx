import { Fragment } from 'react';

import { cn } from '../../lib/cn';

/**
 * The four chips across the top of all three import mockups — *Connect & pick · Preview · Map
 * statuses · Report*.
 *
 * It is a progress indicator, not navigation: a step behind the current one is reached by the
 * screen's own Back control, which knows what to keep. A clickable chip here would let somebody
 * jump from mapping to connect and back, and the second thing they would find is a preview computed
 * against a connection they have since changed.
 *
 * `aria-current="step"` on the live chip and a visually-hidden word on the completed ones, because
 * the mockup's `✓` and its number are the whole distinction and neither reaches a screen reader.
 */
const STEPS = ['Connect & pick', 'Preview', 'Map statuses', 'Report'] as const;

export function ImportStepper({ current, testId }: { current: 1 | 2 | 3 | 4; testId: string }) {
  return (
    <ol
      data-testid={testId}
      className="mb-4 flex list-none flex-wrap items-center gap-2 p-0 text-[12px]"
    >
      {STEPS.map((label, index) => {
        const position = index + 1;
        const done = position < current;
        const live = position === current;
        return (
          <Fragment key={label}>
            {index > 0 && (
              <li aria-hidden className="text-ink-4">
                &rarr;
              </li>
            )}
            <li
              aria-current={live ? 'step' : undefined}
              data-testid={`${testId}.Step${position}`}
              className={cn(
                'flex items-center gap-2 rounded-md border px-2.5 py-1',
                live && 'border-[color:var(--color-given)] text-ink',
                done && 'border-line text-ink-2',
                !live && !done && 'border-line text-ink-4',
              )}
            >
              <span
                aria-hidden
                className="identifier inline-flex size-[17px] items-center justify-center rounded-full border border-line text-[10px]"
              >
                {done ? '✓' : position}
              </span>
              {label}
              {done && <span className="sr-only">(done)</span>}
            </li>
          </Fragment>
        );
      })}
    </ol>
  );
}
