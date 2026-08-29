import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { AnnouncerContext, type AnnouncerValue } from './announcerContextValue';

/**
 * The app's single polite live region.
 *
 * Two details decide whether this works at all, and both are easy to get wrong:
 *
 *   1. **The region is in the DOM from first render, always.** Mounting a live region at the
 *      moment you want to announce something does not announce it — assistive technology has to
 *      be observing the node *before* its content changes. So the node is permanent and only
 *      its text content is updated.
 *   2. **`polite`, not `assertive`.** Routine updates must wait for a pause rather than
 *      interrupting whatever the user is reading. `assertive` is for errors, and errors already
 *      have their own `role="alert"` surface in the notification stack.
 */
export function AnnouncerProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState('');

  const announce = useCallback((next: string) => {
    // Re-announcing identical text is a no-op for most screen readers, because the DOM content
    // never actually changed. Clearing first forces the region to register a real change, so
    // "12 results" after a second filter that also matched 12 is still spoken.
    setMessage('');
    // A microtask is enough to produce two distinct commits without a visible delay.
    void Promise.resolve().then(() => setMessage(next));
  }, []);

  const value = useMemo<AnnouncerValue>(() => ({ announce }), [announce]);

  return (
    <AnnouncerContext value={value}>
      {children}
      {/*
        Visually hidden but still announced, via the ONE `.sr-only` rule in `index.css`.

        This used to be a hand-written copy of that rule in `sx`, and the copy was wrong in a way
        that is invisible to the type system and to every gate: MUI's `sx` runs `width`/`height`
        through the sizing transform, where a **number ≤ 1 means a fraction** — so `width: 1` and
        `height: 1` resolved to `100%`, not `1px`, and `margin: -1` to `-8px` of theme spacing.
        The region was therefore a full-viewport absolutely-positioned block, clipped so nobody
        could see it, adding ~900px of empty scrollable page below the footer on every screen.

        The lesson is the shared-layer rule in CLAUDE.md, not the units: a second implementation of
        something the shared layer already owns is where the drift lives.
      */}
      <div
        className="sr-only"
        data-testid="Announcer"
        role="status"
        aria-live="polite"
        aria-atomic="true"
      >
        {message}
      </div>
    </AnnouncerContext>
  );
}
