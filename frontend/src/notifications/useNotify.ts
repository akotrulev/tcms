import { use } from 'react';
import { NotificationsContext, type NotifyValue } from './notificationsContextValue';

/**
 * The only way a feature raises an error notification. Features never mount their own portal —
 * one stack, mounted once at the root, or the cap and the roll-up cannot be enforced.
 *
 * React 19 `use()` rather than `useContext`, matching `useAuth`.
 */
export function useNotify(): NotifyValue {
  const value = use(NotificationsContext);
  if (!value) {
    throw new Error('useNotify must be used inside <NotificationsProvider>.');
  }
  return value;
}
