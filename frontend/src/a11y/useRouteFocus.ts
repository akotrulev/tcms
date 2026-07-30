import { useEffect, useRef, type RefObject } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * Restores what client-side routing takes away.
 *
 * A full page load moves focus to the top of the new document and the screen reader announces
 * it. A React Router navigation patches the DOM in place: **nothing is announced, and focus
 * stays where it was** — usually on a nav link that still exists, sometimes on an element that
 * has just been unmounted, in which case focus falls back to `<body>` and keyboard users lose
 * their place entirely. Either way a screen-reader user is left stranded on a page they were
 * never told they had arrived at.
 *
 * The fix is to move focus to the new page's `<main>` on every navigation but the first.
 *
 * Announcing the new page is deliberately *not* done here. This hook runs on the pathname
 * change, but heavy routes are `lazy()`-split, so at that moment the new page has not rendered
 * and its title is not yet known — reading `document.title` here would announce the *previous*
 * page. `PageHeader` announces instead, when it mounts and therefore knows its own title.
 */
export function useRouteFocus<T extends HTMLElement>(): RefObject<T | null> {
  const ref = useRef<T>(null);
  const { pathname } = useLocation();
  const isFirstRender = useRef(true);

  useEffect(() => {
    // Never steal focus on initial load — the user may already be heading for the skip link or
    // the browser's own UI, and yanking focus into the page on arrival is its own defect.
    if (isFirstRender.current) {
      isFirstRender.current = false;
      return;
    }
    ref.current?.focus();
  }, [pathname]);

  return ref;
}
