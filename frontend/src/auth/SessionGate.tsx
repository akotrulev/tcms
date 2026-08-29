import type { ReactNode } from 'react';
import { Identifier } from '../components/Identifier';
import { useAuth } from './useAuth';

/**
 * Holds the router until the stored session has been confirmed against `/me`.
 *
 * Without this the app renders optimistically from the cached user, every route fires its
 * queries with a token the server has already rejected, and the user meets the
 * session-expired dialog — raised by a dozen parallel 401s — before the page has finished
 * painting. Waiting one round trip removes that state entirely.
 *
 * The wait only happens when a token exists. A visitor with no session (including someone
 * arriving on `/login` or `/accept-invitation`) never sees this: `status` is already `ready`
 * on the first render, so there is no flash and no added latency on the public routes.
 */
export function SessionGate({ children }: { children: ReactNode }) {
  const { status } = useAuth();

  if (status === 'ready') return children;

  return (
    <main
      role="status"
      aria-busy="true"
      data-testid="Session.Restoring"
      className="grid min-h-screen place-items-center bg-ground"
    >
      <div className="flex flex-col items-center gap-4">
        {/* The wordmark, so the wait reads as the app starting rather than as a blank page. */}
        <Identifier variant="strong" className="text-2xl tracking-[0.06em]">
          TCMS
        </Identifier>
        {/*
          ~~MUI's `CircularProgress`~~ → a bordered ring with `animate-spin`, T16. It is the one
          spinner left in the app: everything else waits behind a skeleton, and this is the case
          where there is no layout yet to approximate the shape of. `motion-safe:` so it obeys the
          reduced-motion block in `index.css`; without it the ring simply sits still, which is
          correct — the `role="status"` and `aria-busy` above are what actually announce the wait.
        */}
        <span
          aria-hidden
          className="size-5 rounded-full border-2 border-line-strong border-t-steel motion-safe:animate-spin"
        />
        <p className="m-0 text-sm text-ink-2">Restoring your session…</p>
      </div>
    </main>
  );
}
