import * as TooltipPrimitive from '@radix-ui/react-tooltip';
import type { ComponentProps } from 'react';

import { cn } from '../../lib/cn';

const TooltipProvider = TooltipPrimitive.Provider;
const TooltipTrigger = TooltipPrimitive.Trigger;

/**
 * Self-providing, unlike the raw Radix root.
 *
 * Radix throws *"`Tooltip` must be used within `TooltipProvider`"* when no provider is above it,
 * which MUI's tooltip never required. That difference is invisible on a page — the app has one
 * provider at the root — and fatal in a test, because ~100 test files render a component in
 * isolation rather than under the whole shell. Making the root carry its own provider means a
 * component with a tooltip in it can be rendered anywhere, which is the property the test suite
 * already assumes and the port must not take away.
 *
 * Nesting is explicitly supported: an inner provider simply wins for its subtree, so the root
 * provider still sets the shared open/close delay for everything under it.
 */
function Tooltip({ children, ...props }: ComponentProps<typeof TooltipPrimitive.Root>) {
  return (
    <TooltipPrimitive.Provider>
      <TooltipPrimitive.Root {...props}>{children}</TooltipPrimitive.Root>
    </TooltipPrimitive.Provider>
  );
}

function TooltipContent({
  className,
  sideOffset = 4,
  ...props
}: ComponentProps<typeof TooltipPrimitive.Content>) {
  return (
    <TooltipPrimitive.Portal>
      <TooltipPrimitive.Content
        sideOffset={sideOffset}
        className={cn(
          'z-50 rounded-sm border border-line bg-panel px-2 py-1 text-[12px] text-ink-2 ' +
            'shadow-e3',
          className,
        )}
        {...props}
      />
    </TooltipPrimitive.Portal>
  );
}

export { TooltipProvider, Tooltip, TooltipTrigger, TooltipContent };
