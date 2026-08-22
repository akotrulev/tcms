import { useEffect, useRef } from 'react';
import { useAnnounce } from './useAnnounce';

/**
 * Announces the outcome of a list that refilled in place, once per refill.
 *
 * A filtered list is the one screen change a screen-reader user is told nothing about: the route
 * does not change, so `useRouteFocus` says nothing, and the rows are replaced under a heading that
 * still reads the same. Errors in this app are announced (`role="alert"`), results were not — so
 * typing into a filter produced silence whether it matched two thousand rows or none.
 *
 * Keyed on the **fetch settling**, not on the count changing: two different filters can both match
 * twenty rows, and a count-keyed announcement would stay silent on exactly the change the reader
 * asked for. The first settle is deliberately skipped — arriving on the page is the route
 * announcement's to report, and saying both is how a live region becomes noise people turn off.
 */
export function useAnnouncedResults(
  state: { isFetching: boolean; isSuccess: boolean },
  message: string,
): void {
  const announce = useAnnounce();
  const wasFetching = useRef(false);
  const hasReported = useRef(false);
  const latest = useRef(message);
  latest.current = message;

  useEffect(() => {
    const settled = wasFetching.current && !state.isFetching;
    wasFetching.current = state.isFetching;

    if (!settled || !state.isSuccess) return;
    if (!hasReported.current) {
      hasReported.current = true;
      return;
    }
    announce(latest.current);
  }, [state.isFetching, state.isSuccess, announce]);
}
