import { use } from 'react';
import { AnnouncerContext } from './announcerContextValue';

/**
 * Access to the app's single polite live region.
 *
 * Returns a no-op announcer rather than throwing when no provider is mounted. This is the one
 * place that leniency is right: a missing announcer degrades an announcement, while throwing
 * would take down a working page over an accessibility affordance — and it keeps components
 * renderable in isolation in a Vitest harness without wrapping every one of them.
 */
export function useAnnounce(): (message: string) => void {
  return use(AnnouncerContext)?.announce ?? noop;
}

function noop(): void {}
