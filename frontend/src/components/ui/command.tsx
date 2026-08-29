import {
  Command as CommandPrimitive,
  CommandEmpty as CommandEmptyPrimitive,
  CommandGroup as CommandGroupPrimitive,
  CommandInput as CommandInputPrimitive,
  CommandItem as CommandItemPrimitive,
  CommandList as CommandListPrimitive,
  CommandSeparator as CommandSeparatorPrimitive,
} from 'cmdk';
import type { ComponentProps } from 'react';

import { cn } from '../../lib/cn';

function Command({ className, ...props }: ComponentProps<typeof CommandPrimitive>) {
  return (
    <CommandPrimitive
      className={cn(
        'flex w-full flex-col overflow-hidden rounded-md bg-panel text-ink',
        className,
      )}
      {...props}
    />
  );
}

function CommandInput({ className, ...props }: ComponentProps<typeof CommandInputPrimitive>) {
  return (
    <div className="flex items-center border-b border-white/8 px-3">
      <CommandInputPrimitive
        className={cn(
          'flex h-9 w-full bg-transparent py-2 text-[13px] text-ink outline-none ' +
            'placeholder:text-ink-4 disabled:cursor-not-allowed disabled:opacity-45',
          className,
        )}
        {...props}
      />
    </div>
  );
}

function CommandList({ className, ...props }: ComponentProps<typeof CommandListPrimitive>) {
  return (
    <CommandListPrimitive
      className={cn('max-h-72 overflow-y-auto overflow-x-hidden p-1', className)}
      {...props}
    />
  );
}

function CommandEmpty(props: ComponentProps<typeof CommandEmptyPrimitive>) {
  return <CommandEmptyPrimitive className="py-6 text-center text-[13px] text-ink-4" {...props} />;
}

function CommandGroup({ className, ...props }: ComponentProps<typeof CommandGroupPrimitive>) {
  return (
    <CommandGroupPrimitive
      className={cn(
        'overflow-hidden p-1 text-ink-2 ' +
          '[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5 ' +
          '[&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:font-medium ' +
          '[&_[cmdk-group-heading]]:text-ink-4',
        className,
      )}
      {...props}
    />
  );
}

function CommandItem({ className, ...props }: ComponentProps<typeof CommandItemPrimitive>) {
  return (
    <CommandItemPrimitive
      className={cn(
        'relative flex cursor-pointer select-none items-center gap-2 rounded-[4px] px-2 py-1.5 ' +
          'text-[13px] text-ink-2 outline-none data-[selected=true]:bg-white/5 ' +
          'data-[selected=true]:text-ink data-[disabled=true]:pointer-events-none ' +
          'data-[disabled=true]:opacity-45',
        className,
      )}
      {...props}
    />
  );
}

function CommandSeparator({ className, ...props }: ComponentProps<typeof CommandSeparatorPrimitive>) {
  return <CommandSeparatorPrimitive className={cn('my-1 h-px bg-white/8', className)} {...props} />;
}

export {
  Command,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
  CommandSeparator,
};
