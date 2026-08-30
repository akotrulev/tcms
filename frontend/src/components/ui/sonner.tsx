import { Toaster as SonnerToaster, type ToasterProps } from 'sonner';

function Toaster(props: ToasterProps) {
  return (
    <SonnerToaster
      className="toaster group"
      toastOptions={{
        classNames: {
          toast:
            'group toast bg-panel! text-ink! border! border-line! shadow-e3!',
          description: 'text-ink-3!',
          actionButton: 'bg-steel! text-[color:var(--color-on-action)]!',
          cancelButton: 'bg-wash-3! text-ink-2!',
        },
      }}
      {...props}
    />
  );
}

export { Toaster };
