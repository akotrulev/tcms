import type { ComponentProps } from 'react';

import { cn } from '../../lib/cn';

function Textarea({ className, ...props }: ComponentProps<'textarea'>) {
  return (
    <textarea
      className={cn(
        'w-full rounded-[6px] border border-line bg-field px-3 py-1.5 text-[13px] text-ink ' +
          'placeholder:text-ink-4 ' +
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)] ' +
          'disabled:cursor-not-allowed disabled:opacity-45',
        className,
      )}
      {...props}
    />
  );
}

export { Textarea };
