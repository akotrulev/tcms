import type { LucideIcon } from 'lucide-react';

import { cn } from '../../lib/cn';

export const segmentTrackClass = 'inline-flex gap-0.5 rounded-lg bg-wash-1 p-0.5';

export function segmentItemClass(active: boolean): string {
  return cn(
    'flex h-7 items-center gap-1.5 rounded-md px-2.5 text-[12.5px] font-medium',
    'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]',
    active ? 'bg-raised text-ink font-semibold shadow-e1' : 'text-ink-3 hover:text-ink-2',
  );
}

export interface SegmentedOption<T extends string> {
  value: T;
  label: string;
  icon?: LucideIcon;
  testId?: string;
}

export interface SegmentedProps<T extends string> {
  label: string;
  options: SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
  testId: string;
}

export function Segmented<T extends string>({
  label,
  options,
  value,
  onChange,
  testId,
}: SegmentedProps<T>) {
  return (
    <div role="group" aria-label={label} data-testid={testId} className={segmentTrackClass}>
      {options.map((option) => {
        const active = option.value === value;
        const Icon = option.icon;
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(option.value)}
            data-testid={option.testId ?? `${testId}.${option.value}`}
            className={segmentItemClass(active)}
          >
            {Icon && <Icon aria-hidden className="size-3.5" />}
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
