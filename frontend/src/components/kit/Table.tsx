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
  /**
   * The column's current sort, or `false` for a column that sorts but is not the one sorted by.
   * Omit entirely for a column that cannot be sorted — its presence is what puts `aria-sort` on
   * the header and makes the label a button.
   */
  sortDirection?: 'asc' | 'desc' | false;
  onSort?: () => void;
}

/**
 * A sortable head is a `<button>` inside the `<th>`, never a click handler on the `<th>` itself —
 * the latter is unreachable from the keyboard, and `aria-sort` on a cell nothing can operate
 * announces an order the reader cannot change. The arrow appears only on the column actually
 * sorted, as in the render: an arrow on every sortable head is ten arrows competing with the data.
 */
export function TableHeaderCell({
  align = 'left',
  sortDirection,
  onSort,
  children,
  className,
  ...rest
}: TableHeaderCellProps) {
  const sortable = sortDirection !== undefined;

  return (
    <th
      aria-sort={
        sortable ? (sortDirection === 'asc' ? 'ascending' : sortDirection === 'desc' ? 'descending' : 'none') : undefined
      }
      className={cn(
        'sticky top-0 whitespace-nowrap border-b border-line-strong bg-paper px-3.5 py-2.5',
        'font-mono text-[11px] font-medium uppercase tracking-[.07em] text-ink-3',
        align === 'right' ? 'text-right' : 'text-left',
        className,
      )}
      {...rest}
    >
      {onSort ? (
        <button
          type="button"
          onClick={onSort}
          className={cn(
            'inline-flex items-center gap-[5px] text-inherit uppercase tracking-[inherit] hover:text-ink-2',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-given',
          )}
        >
          {children}
          {sortDirection && (
            <span aria-hidden className="text-[10px] text-steel">
              {sortDirection === 'desc' ? '\u2193' : '\u2191'}
            </span>
          )}
        </button>
      ) : (
        children
      )}
    </th>
  );
}

export interface TableCellProps extends TdHTMLAttributes<HTMLTableCellElement> {
  align?: 'left' | 'right';
  variant?: 'default' | 'title';
  subtitle?: ReactNode;
  /**
   * Renders the cell as `<th scope="row">` — for a table whose FIRST COLUMN is the label and whose
   * second is the value, such as the report provenance block. Without it each line reads to a
   * screen reader as a loose pair of cells rather than as a labelled row, which is the whole
   * structure of that kind of table.
   */
  rowHeader?: boolean;
}

export function TableCell({
  align = 'left',
  variant = 'default',
  subtitle,
  rowHeader = false,
  children,
  className,
  ...rest
}: TableCellProps) {
  const wraps = variant === 'title' || subtitle != null;
  const Cell = rowHeader ? 'th' : 'td';

  return (
    <Cell
      scope={rowHeader ? 'row' : undefined}
      className={cn(
        'border-b border-line px-3.5 py-2.5 align-top text-ink-2 group-hover:bg-wash-1',
        wraps ? 'whitespace-normal' : 'whitespace-nowrap',
        align === 'right' ? 'text-right' : 'text-left',
        variant === 'title' && 'font-normal text-ink',
        className,
      )}
      {...rest}
    >
      {children}
      {subtitle && <span className="mt-0.5 block text-[11.5px] text-ink-4">{subtitle}</span>}
    </Cell>
  );
}
