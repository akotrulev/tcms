import { toast as sonner } from 'sonner';

/**
 * The transient notification surface — success, information and warnings, bottom-right, on a
 * timer. It is a module function rather than a hook on `NotificationsContext` because `sonner`'s
 * queue is itself a module singleton: only `<Toaster />` needs mounting, and a context wrapper
 * around a global would buy a provider every test has to mount for nothing.
 *
 * ~~notistack's `enqueueSnackbar`~~ → superseded by this; the split it enforced has not changed.
 * There are still two surfaces and the reason is still the one `Providers.tsx` gives: a failure
 * and a success cannot share one dismissal policy. What moved is where the policy lives. Under
 * notistack the `variant` a caller passed decided the timer, so `variant: 'error'` on a page was
 * an error that vanished after four seconds — design language §7's *"a failure never
 * auto-dismisses"* broken by a call site, silently. Here the mapping below is the only thing that
 * sets a duration, and the `error` arm has no timer to get wrong.
 *
 * Failures raised by the data layer do not come through here at all: `app/queryClient.ts` remains
 * the one place a read or write failure raises a notification, and it raises it on the persistent,
 * deduped, rolled-up surface in `NotificationsProvider`. This arm is for the failure a page
 * catches and handles itself — a rejected drag, a refused recording — which has a message but no
 * incident behind it.
 */
export type ToastVariant = 'success' | 'info' | 'warning' | 'error';

const AUTO_DISMISS_MS = 4000;

export function toast(message: string, options?: { variant?: ToastVariant }): void {
  const variant = options?.variant ?? 'info';

  if (variant === 'error') {
    sonner.error(message, {
      duration: Number.POSITIVE_INFINITY,
      closeButton: true,
      testId: 'Toast.Error',
    });
    return;
  }

  sonner[variant](message, { duration: AUTO_DISMISS_MS, testId: `Toast.${variant}` });
}
