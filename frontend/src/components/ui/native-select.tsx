import type { ComponentProps } from 'react';

import { cn } from '../../lib/cn';

/**
 * A one-of-N picker, on the platform's own control.
 *
 * `select.tsx` beside this is Radix's listbox, and it is the right component where the options are
 * rich — an avatar, a two-line row, a status dot. **These are not those.** Every picker on the
 * settings and administration screens chooses one word from a closed set of three or four, and for
 * that the native control is better on the axes that matter here: it is operable by keyboard and
 * by screen reader with no code of ours, it opens as the platform picker on a phone, and it
 * survives a jsdom render — `@radix-ui/react-select`'s trigger calls `hasPointerCapture`, which
 * jsdom does not implement, so a test that drives one of these has to reach past the component to
 * fake it.
 *
 * Styled as the render's `.fld` so it reads as a field rather than as a button.
 */
export function NativeSelect({ className, children, ...props }: ComponentProps<'select'>) {
  return (
    <select
      className={cn(
        'w-full rounded-[6px] border border-line bg-field px-2.5 py-1.5 text-[12.5px] text-ink ' +
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)] ' +
          'disabled:cursor-not-allowed disabled:opacity-45',
        className,
      )}
      {...props}
    >
      {children}
    </select>
  );
}
