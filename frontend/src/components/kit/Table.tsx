import type { HTMLAttributes, ReactNode, TdHTMLAttributes, ThHTMLAttributes } from 'react';

import { cn } from '../../lib/cn';
import { ScrollX } from '../ScrollX';

/**
 * The index-screen table — sticky mono heads, a quiet second line in the cell instead of a
 * twelfth column (UX#113 / UX#111).
 *
 * Cells do not wrap; the REGION scrolls — `.tbl`'s own comment names the failure this prevents: a
 * ten-column table squeezed into its container breaks `TC-194` across two lines and an owner's name
 * across three. The product already owns the horizontal-scroll pattern in `ScrollX`, so `Table`
 * wraps in it rather than growing a second `overflow-x` implementation. `TableCell`'s `variant="title"`
 * and `subtitle` are the one deliberate exception to no-wrap: the title column is prose, and the
 * quiet second line is what a reader is scanning instead of a wider grid.
 */
export interface TableProps {
  children: ReactNode;
  className?: string;
  'data-testid'?: string;
}

export function Table({ children, className, ...rest }: TableProps) {
  return (
    <ScrollX>
      <table className={cn('w-full min-w-[680px] border-collapse text-[13px]', className)} {...rest}>
        {children}
      </table>
    </ScrollX>
  );
}

export function TableHead({ children, className }: { children: ReactNode; className?: string }) {
  return <thead className={className}>{children}</thead>;
}

export function TableBody({ children, className }: { children: ReactNode; className?: string }) {
  return <tbody className={className}>{children}</tbody>;
}

export interface TableRowProps extends HTMLAttributes<HTMLTableRowElement> {
  selected?: boolean;
  children: ReactNode;
  'data-testid'?: string;
}

export function TableRow({ selected, children, className, style, ...rest }: TableRowProps) {
  return (
    <tr
      className={cn('group', className)}
      style={{
        ...(selected ? { backgroundColor: 'color-mix(in oklab, var(--color-steel) 12%, transparent)' } : undefined),
        ...style,
      }}
      {...rest}
    >
      {children}
    </tr>
  );
}

export interface TableHeaderCellProps extends ThHTMLAttributes<HTMLTableCellElement> {
  align?: 'left' | 'right';
  sortable?: boolean;
}

export function TableHeaderCell({ align = 'left', sortable, children, className, ...rest }: TableHeaderCellProps) {
  return (
    <th
      className={cn(
        'sticky top-0 whitespace-nowrap border-b border-white/14 bg-paper px-3.5 py-2.5',
        'font-mono text-[11px] font-medium uppercase tracking-[.07em] text-ink-3',
        align === 'right' ? 'text-right' : 'text-left',
        className,
      )}
      {...rest}
    >
      {children}
      {sortable && (
        <span aria-hidden className="ml-[5px] text-[10px] text-steel">
          ↓
        </span>
      )}
    </th>
  );
}

export interface TableCellProps extends TdHTMLAttributes<HTMLTableCellElement> {
  align?: 'left' | 'right';
  variant?: 'default' | 'title';
  subtitle?: ReactNode;
}

export function TableCell({ align = 'left', variant = 'default', subtitle, children, className, ...rest }: TableCellProps) {
  const wraps = variant === 'title' || subtitle != null;

  return (
    <td
      className={cn(
        'border-b border-white/8 px-3.5 py-2.5 align-top text-ink-2 group-hover:bg-white/[2.5%]',
        wraps ? 'whitespace-normal' : 'whitespace-nowrap',
        align === 'right' ? 'text-right' : 'text-left',
        variant === 'title' && 'font-normal text-ink',
        className,
      )}
      {...rest}
    >
      {children}
      {subtitle && <span className="mt-0.5 block text-[11.5px] text-ink-4">{subtitle}</span>}
    </td>
  );
}
