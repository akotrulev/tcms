import { createContext } from 'react';

/**
 * The Context object for the live-region announcer, split from the provider component for the
 * same Fast Refresh reason as `authContextValue.ts` and `notificationsContextValue.ts`.
 */
export interface AnnouncerValue {
  /**
   * Announces a message in the app's single polite live region, without moving focus.
   *
   * Focus answers "where am I"; this answers "something just happened" — a page changed,
   * 12 results loaded, a filter matched nothing, a result was recorded. Sighted users get these
   * from the screen changing; without a live region, screen-reader users get nothing at all.
   */
  announce: (message: string) => void;
}

export const AnnouncerContext = createContext<AnnouncerValue | null>(null);
