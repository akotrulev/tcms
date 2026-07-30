import { Box } from '@mui/material';
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
      <Box
        data-testid="Announcer"
        role="status"
        aria-live="polite"
        aria-atomic="true"
        sx={{
          // Visually hidden but still announced. `display: none` and `visibility: hidden` both
          // remove the node from the accessibility tree, which would silence it entirely.
          position: 'absolute',
          width: 1,
          height: 1,
          padding: 0,
          margin: -1,
          overflow: 'hidden',
          clip: 'rect(0 0 0 0)',
          clipPath: 'inset(50%)',
          whiteSpace: 'nowrap',
          border: 0,
        }}
      >
        {message}
      </Box>
    </AnnouncerContext>
  );
}
