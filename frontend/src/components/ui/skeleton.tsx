import type { ComponentProps } from 'react';

import { cn } from '../../lib/cn';

function Skeleton({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      className={cn('animate-pulse rounded bg-wash-2 motion-reduce:animate-none', className)}
      {...props}
    />
  );
}

export { Skeleton };
