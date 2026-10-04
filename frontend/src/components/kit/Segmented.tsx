import type { LucideIcon } from 'lucide-react';

import { segmentItemClass, segmentTrackClass } from './segmentClasses';

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
