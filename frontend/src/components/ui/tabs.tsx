import * as TabsPrimitive from '@radix-ui/react-tabs';
import type { ComponentProps } from 'react';

import { cn } from '../../lib/cn';

const Tabs = TabsPrimitive.Root;

function TabsList({ className, ...props }: ComponentProps<typeof TabsPrimitive.List>) {
  return (
    <TabsPrimitive.List
      className={cn('inline-flex gap-0.5 rounded-md bg-wash-1 p-0.5', className)}
      {...props}
    />
  );
}

function TabsTrigger({ className, ...props }: ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      className={cn(
        'inline-flex cursor-pointer items-center justify-center rounded-[4px] px-3 py-1 ' +
          'text-[13px] font-medium text-ink-3 transition-colors ' +
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)] ' +
          'disabled:pointer-events-none disabled:opacity-45 ' +
          'data-[state=active]:bg-panel data-[state=active]:text-ink data-[state=active]:shadow',
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
