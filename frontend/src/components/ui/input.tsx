import type { ComponentProps } from 'react';

import { cn } from '../../lib/cn';

function Input({ className, ...props }: ComponentProps<'input'>) {
  return (
    <input
      className={cn(
        // `.fld` in the render kit: 8px radius, `5px 10px`, 12.5px, 30px min-height. The ink stays
        // `--ink` rather than the kit's `--ink-2`: `.fld` is a static mock of a control, and typed
        // input at the muted weight reads as placeholder text in a real one.
        'w-full min-h-[30px] rounded-[8px] border border-line bg-field px-2.5 py-[5px] text-[12.5px] text-ink ' +
          'placeholder:text-ink-4 ' +
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)] ' +
          'disabled:cursor-not-allowed disabled:opacity-45',
        className,
      )}
      {...props}
    />
  );
}

export { Input };
