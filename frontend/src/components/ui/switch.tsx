import * as SwitchPrimitive from '@radix-ui/react-switch';
import type { ComponentProps } from 'react';

import { cn } from '../../lib/cn';

function Switch({ className, ...props }: ComponentProps<typeof SwitchPrimitive.Root>) {
  return (
    <SwitchPrimitive.Root
      className={cn(
        'peer inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full ' +
          'border border-line bg-wash-3 transition-colors ' +
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)] ' +
          'disabled:cursor-not-allowed disabled:opacity-45 data-[state=checked]:bg-steel',
        className,
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb
        className={cn(
          'pointer-events-none block size-3.5 translate-x-0.5 rounded-full bg-ground shadow ' +
            'transition-transform data-[state=checked]:translate-x-[18px]',
        )}
      />
    </SwitchPrimitive.Root>
  );
}

export { Switch };
