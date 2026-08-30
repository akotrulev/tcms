import * as TabsPrimitive from '@radix-ui/react-tabs';
import type { ComponentProps } from 'react';

import { cn } from '../../lib/cn';

const Tabs = TabsPrimitive.Root;

function TabsList({ className, ...props }: ComponentProps<typeof TabsPrimitive.List>) {
  return (
    <TabsPrimitive.List
      className={cn('inline-flex gap-0.5 rounded-[8px] bg-wash-1 p-0.5', className)}
      {...props}
    />
  );
}

function TabsTrigger({ className, ...props }: ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      className={cn(
        // `.lens` in the render kit: 8px track, 6px item, 12px type, `4px 11px`, and the active
        // item lifts on `--e1` rather than on Tailwind's default shadow-e1.
        'inline-flex cursor-pointer items-center justify-center rounded-md px-[11px] py-1 ' +
          'text-[12px] font-medium text-ink-3 transition-colors ' +
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)] ' +
          'disabled:pointer-events-none disabled:opacity-45 ' +
          'data-[state=active]:bg-panel data-[state=active]:text-ink data-[state=active]:shadow-e1',
        className,
      )}
      {...props}
    />
  );
}

function TabsContent({ className, ...props }: ComponentProps<typeof TabsPrimitive.Content>) {
  return (
    <TabsPrimitive.Content
      className={cn(
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
        className,
      )}
      {...props}
    />
  );
}

export { Tabs, TabsList, TabsTrigger, TabsContent };
