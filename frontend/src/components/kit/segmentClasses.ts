import { cn } from '../../lib/cn';

export const segmentTrackClass = 'inline-flex gap-0.5 rounded-lg bg-wash-1 p-0.5';

export function segmentItemClass(active: boolean): string {
  return cn(
    'flex h-7 items-center gap-1.5 rounded-md px-2.5 text-[12.5px] font-medium',
    'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]',
    active ? 'bg-raised text-ink font-semibold shadow-e1' : 'text-ink-3 hover:text-ink-2',
  );
}
