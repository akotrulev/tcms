import { CircleAlert, Info, TriangleAlert } from 'lucide-react';
import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * A strip of consequence, not a control — design language §5's tone mapping, ported verbatim from
 * `.banner`/`.banner.warn`/`.banner.bad`/`.banner.steel`: neutral for a consequence, amber for a
 * state that is currently wrong, red for one that has failed, steel for information the instance
 * itself is telling the reader (not a result).
 */
export type BannerTone = 'neutral' | 'warn' | 'bad' | 'steel';

export interface BannerProps {
  tone: BannerTone;
  children: ReactNode;
  className?: string;
  'data-testid'?: string;
}

const ICON: Record<BannerTone, typeof Info> = {
  neutral: Info,
  warn: TriangleAlert,
  bad: CircleAlert,
  steel: Info,
};

const TONE_STYLE: Partial<Record<BannerTone, { border: string; bg: string; icon: string }>> = {
  warn: {
    border: 'color-mix(in oklab, var(--color-blocked) 26%, transparent)',
    bg: 'color-mix(in oklab, var(--color-blocked) 7%, transparent)',
    icon: 'var(--color-blocked)',
  },
  bad: {
    border: 'color-mix(in oklab, var(--color-failed) 26%, transparent)',
    bg: 'color-mix(in oklab, var(--color-failed) 7%, transparent)',
    icon: 'var(--color-failed)',
  },
  steel: {
    border: 'color-mix(in oklab, var(--color-steel) 22%, transparent)',
    bg: 'color-mix(in oklab, var(--color-steel) 12%, transparent)',
    icon: 'var(--color-steel)',
  },
};

export function Banner({ tone, children, className, ...rest }: BannerProps) {
  const Icon = ICON[tone];
  const toneStyle = TONE_STYLE[tone];

  return (
    <div
      // The tone, as data. It is carried in `style` otherwise — a `color-mix()` a test cannot read
      // back and a class name it should not couple to — and the tone IS the claim on the two
      // banners whose whole point is that they are not ordinary information.
      data-tone={tone}
      style={
        toneStyle
          ? { borderColor: toneStyle.border, backgroundColor: toneStyle.bg }
          : undefined
      }
      className={cn(
        'flex items-start gap-[11px] rounded-lg border border-line bg-panel px-3.5 py-[11px]',
        'text-[13px] leading-[1.55] text-ink-2',
        className,
      )}
      {...rest}
    >
      <Icon
        aria-hidden
        style={toneStyle ? { color: toneStyle.icon } : undefined}
        className="mt-px size-4 shrink-0 text-ink-3"
      />
      {children}
    </div>
  );
}
