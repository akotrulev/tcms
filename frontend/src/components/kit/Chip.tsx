import { X } from 'lucide-react';
import type { ButtonHTMLAttributes, CSSProperties, ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * A value the reader owns — always a button (UX#117, design language §5).
 *
 * `onClick` is required rather than optional: a chip with nothing behind it is a badge wearing the
 * wrong component, and this type makes that state unrepresentable rather than reviewable.
 *
 * The focus ring is `--color-given`, not steel, matching `buttonVariants` and `RailRow` — a steel
 * ring around a steel-tinted `selected` chip is the one that disappears exactly when it is needed.
 */
export interface ChipProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onClick'> {
  onClick: () => void;
  selected?: boolean;
  onRemove?: () => void;
  variant?: 'add';
  children: ReactNode;
  'data-testid'?: string;
}

const FOCUS =
  'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]';

export function Chip({
  onClick,
  selected,
  onRemove,
  variant,
  children,
  className,
  ...rest
}: ChipProps) {
  const selectedStyle: CSSProperties | undefined = selected
    ? {
        borderColor: 'color-mix(in oklab, var(--color-steel) 22%, transparent)',
        backgroundColor: 'color-mix(in oklab, var(--color-steel) 12%, transparent)',
        color: 'var(--color-steel)',
      }
    : undefined;

  const pill = cn(
    'inline-flex items-center gap-1.5 rounded-[5px] border border-line px-2.5 py-[3px]',
    'font-sans text-xs text-ink-2',
    variant === 'add' && 'border-dashed text-ink-4',
    className,
  );

  if (!onRemove) {
    return (
      <button
        type="button"
        onClick={onClick}
        style={selectedStyle}
        className={cn(pill, 'hover:border-line-strong hover:bg-wash-1 hover:text-ink', FOCUS)}
        {...rest}
      >
        {children}
      </button>
    );
  }

  return (
    <span style={selectedStyle} className={pill}>
      <button type="button" onClick={onClick} className={cn('inline-flex items-center', FOCUS)} {...rest}>
        {children}
      </button>
      <button
        type="button"
        onClick={onRemove}
        aria-label="Remove"
        className={cn('text-ink-4 hover:text-ink', FOCUS)}
      >
        <X aria-hidden className="size-3" />
      </button>
    </span>
  );
}
