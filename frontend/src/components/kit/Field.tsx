import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * A label above its control, its refusal — or its help — beneath it. One column, always.
 *
 * The forms rule the whole product follows: labels above, one column, the message that belongs to
 * a field rendered under that field rather than collected into a summary the reader has to map
 * back onto the boxes.
 *
 * **`error` wins over `help` and announces itself.** A refusal rendered in the same slot as the
 * hint is the shape that keeps the layout still when a save comes back 400, and `role="alert"` is
 * what makes it reach somebody who is not looking at the field they just left.
 *
 * `features/cases/ConditionEditDialog.tsx` has a private copy of this, written in 007 T7 before
 * there was anywhere shared to put it. This is the same component; that one should collapse onto
 * it the next time the file is open.
 */
export interface FieldProps {
  label: ReactNode;
  htmlFor: string;
  help?: ReactNode;
  error?: ReactNode;
  children: ReactNode;
  className?: string;
}

export function Field({ label, htmlFor, help, error, children, className }: FieldProps) {
  const message = error ?? help;

  return (
    <div className={cn('min-w-0', className)}>
      <label htmlFor={htmlFor} className="mb-1 block text-[13px] font-medium text-ink-2">
        {label}
      </label>
      {children}
      {message && (
        <p
          role={error ? 'alert' : undefined}
          className={cn('m-0 mt-1 text-xs', error ? 'text-[color:var(--color-failed)]' : 'text-ink-4')}
        >
          {message}
        </p>
      )}
    </div>
  );
}
