import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react';

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
 * **The message is tied to the control, not merely placed under it.** `role="alert"` fires once,
 * when the refusal appears; a reader who tabs back to the field a moment later gets nothing from
 * it. So the message carries an id derived from `htmlFor` and the control is given a matching
 * `aria-describedby` — which is what makes the field announce *why* it is invalid rather than only
 * *that* it is. Wired here by cloning the child rather than asked of ~40 call sites, because a
 * rule every caller has to remember is one some caller will not.
 *
 * ~~`features/cases/ConditionEditDialog.tsx` has a private copy of this, written in 007 T7 before
 * there was anywhere shared to put it.~~ → collapsed onto this in the audit-fix pass, along with
 * the copies in `BulkActionDialog` and `AssignExecutionsDialog`.
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
  const messageId = `${htmlFor}-message`;

  const only = Children.count(children) === 1 ? Children.only(children) : null;
  const described =
    message !== undefined && message !== false && only !== null && isValidElement(only)
      ? cloneElement(only as ReactElement<{ 'aria-describedby'?: string }>, {
          'aria-describedby':
            [(only.props as { 'aria-describedby'?: string })['aria-describedby'], messageId]
              .filter(Boolean)
              .join(' '),
        })
      : children;

  return (
    <div className={cn('min-w-0', className)}>
      <label htmlFor={htmlFor} className="mb-1 block text-[13px] font-medium text-ink-2">
        {label}
      </label>
      {described}
      {message && (
        <p
          id={messageId}
          role={error ? 'alert' : undefined}
          className={cn('m-0 mt-1 text-xs', error ? 'text-[color:var(--color-failed)]' : 'text-ink-4')}
        >
          {message}
        </p>
      )}
    </div>
  );
}
