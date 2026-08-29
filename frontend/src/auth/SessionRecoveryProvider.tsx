import { Banner } from '../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
} from '../components/ui';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import { SessionRecoveryContext, type SessionRecoveryValue } from './sessionRecoveryContextValue';
import { subscribeSession } from './sessionChannel';
import { useAuth } from './useAuth';
import { useSessionRecovery } from './useSessionRecovery';

/**
 * Mid-session 401 recovery — re-authenticate **in place**, never redirect off unsaved work.
 *
 * ## Which rung of the ladder this is, and why
 *
 * The full ladder is: silent refresh (single-flight) → replay once → re-auth in place →
 * redirect from a clean surface only.
 *
 * v1 has **no refresh endpoint**: authentication is local accounts issuing a JWT, and external
 * OIDC (which brings refresh tokens and rotation with it) ships in M4 alongside the
 * integrations. So rungs 1 and 2 have nothing to call and are deliberately not faked — a
 * "refresh" against an endpoint that does not exist would just add a guaranteed round trip
 * before every recovery.
 *
 * What is implemented is **rung 3, which is the rung teams skip and the one that actually saves
 * the work**. The route stays mounted, so the form, its typed values, the filters, and the
 * scroll position all survive; the user re-enters their password in a dialog; the queries that
 * failed are then refetched.
 *
 * When M4 lands, rungs 1–2 slot in *above* this without touching it: on 401, try the refresh
 * first, and fall through to `reportUnauthorized()` only when that fails. That is why this is a
 * provider with one entry point rather than logic inlined into the fetch wrapper.
 *
 * ## What does NOT come through here
 *
 * - **403.** A permission failure means *this user may not do this*. It renders a
 *   permission-denied body in the failing region, offers no retry, and must never touch the
 *   session — conflating the two is the classic "logged out for clicking an admin button" bug.
 * - **A 401 from anywhere that isn't our own API.** Only `/api/v1` responses reach this.
 */
export function SessionRecoveryProvider({ children }: { children: ReactNode }) {
  const { user, login, logout } = useAuth();

  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  /**
   * Unsaved-work registrations, keyed by an incrementing token so two dirty forms don't clobber
   * each other's entry. A ref rather than state: nothing renders from it, and a write must not
   * cause a re-render of the whole app on every keystroke that dirties a form.
   */
  const unsaved = useRef(new Map<number, string>());
  const nextToken = useRef(0);

  const registerUnsavedWork = useCallback((label: string) => {
    const token = nextToken.current++;
    unsaved.current.set(token, label);
    return () => {
      unsaved.current.delete(token);
    };
  }, []);

  const hasUnsavedWork = useCallback(() => unsaved.current.size > 0, []);

  /*
    What to run once the user is back in. Registered from below the QueryClientProvider by
    `SessionReplayBridge` — see the note on `registerReauthHandler`.
  */
  const reauthHandlers = useRef(new Set<() => void | Promise<void>>());

  const registerReauthHandler = useCallback((handler: () => void | Promise<void>) => {
    reauthHandlers.current.add(handler);
    return () => {
      reauthHandlers.current.delete(handler);
    };
  }, []);

  /*
    `user` is mirrored into a ref so `reportUnauthorized` can be a genuinely stable callback.

    This is not a micro-optimization. The query client captures this function once, at mount,
    when it builds its global error handlers — rebuilding the client to pick up a new identity
    would discard the entire query cache. A version that closed over `user` directly would be
    frozen at its mount-time value of `null`, and the guard below would then swallow every 401
    for the life of the session: the recovery dialog would never appear.
  */
  const userRef = useRef(user);
  userRef.current = user;

  const reportUnauthorized = useCallback(() => {
    // Only if we believed we had a session. A 401 while already signed out is the login screen
    // doing its job, not an expiry to recover from.
    if (!userRef.current) return;
    setOpen(true);
  }, []);

  /**
   * A sign-in on another tab settles this one.
   *
   * Without this, the multi-tab story is half-built: sign out in tab A and tab B corrects itself,
   * but sign back in and tab B sits on a modal password prompt for a session that is already
   * valid — the user types their password a second time to be told nothing was wrong. A sign-out
   * closes it too, because `RequireAuth` is about to navigate this tab to the login screen and a
   * non-dismissible dialog riding over that transition is the worse of the two things on screen.
   *
   * Only the dialog is closed here. Refetching, and the harder question of whether the cache is
   * still *this user's* to show, belong to `SessionReplayBridge`, which can see the query client
   * — and which runs after this, so the two never race.
   */
  useEffect(
    () =>
      subscribeSession(() => {
        setOpen(false);
        setPassword('');
        setFormError(null);
      }),
    [],
  );

  const value = useMemo<SessionRecoveryValue>(
    () => ({ reportUnauthorized, registerUnsavedWork, hasUnsavedWork, registerReauthHandler }),
    [reportUnauthorized, registerUnsavedWork, hasUnsavedWork, registerReauthHandler],
  );

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!user) return;

    setSubmitting(true);
    setFormError(null);
    try {
      await login(user.email, password, true);
      setOpen(false);
      setPassword('');
      // The queries that failed with a 401 are in an error state holding no data. Refetching
      // them is the replay: the user lands back on a working page rather than on a grid full of
      // "your session ended" panels they now have to dismiss one by one.
      await Promise.all([...reauthHandlers.current].map((handler) => handler()));
    } catch (error) {
      setFormError(
        error instanceof ApiError && error.status === 401
          ? 'That password was not accepted.'
          : "We couldn't sign you back in. Check your connection and try again.",
      );
    } finally {
      setSubmitting(false);
    }
  }

  /**
   * Signing out is the deliberate escape hatch, and it is the *only* path here that discards
   * work — so it is a button the user presses, never something that happens to them.
   */
  function signOut() {
    setOpen(false);
    setPassword('');
    logout();
  }

  return (
    <SessionRecoveryContext value={value}>
      {children}

      {/*
        No `onOpenChange`. This dialog is not dismissible — the session is gone, and every escape
        hatch it could offer is already on it as a labelled control ("Sign out instead"). Radix
        would otherwise close it on Escape or an outside click and leave the reader on a page whose
        every request 401s, with nothing on screen saying why.
      */}
      <Dialog open={open}>
        <DialogContent
          className="max-w-sm"
          aria-labelledby="session-expired-title"
          data-testid="SessionExpired.Dialog"
          onEscapeKeyDown={(event: KeyboardEvent) => event.preventDefault()}
          onInteractOutside={(event: Event) => event.preventDefault()}
        >
          <form onSubmit={submit}>
            <DialogHeader>
              <DialogTitle id="session-expired-title" className="text-lg">
                Your session has expired
              </DialogTitle>
            </DialogHeader>

            <p className="mt-4 mb-4 text-sm text-ink-2">
              Sign in again to continue. <strong>Nothing you have typed has been lost</strong> —
              this page is still exactly where you left it.
            </p>

            {user && <p className="mt-0 mb-4 text-sm text-ink-2">Signed in as {user.email}</p>}

            {formError && (
              <div className="mb-4" data-testid="SessionExpired.Error">
                <Banner tone="bad">
                  <span role="alert">{formError}</span>
                </Banner>
              </div>
            )}

            <Input
              autoFocus
              type="password"
              aria-label="Password"
              placeholder="Password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              disabled={submitting}
              data-testid="SessionExpired.Password"
              className="w-full"
            />

            <DialogFooter className="mt-5 justify-between">
              <Button
                type="button"
                variant="quiet"
                size="sm"
                onClick={signOut}
                data-testid="SessionExpired.SignOut"
              >
                Sign out instead
              </Button>
              <Button
                type="submit"
                variant="primary"
                size="sm"
                // The pending state IS the double-submit guard. Two sign-in attempts racing
                // would write two tokens and leave whichever lost the race in storage.
                disabled={submitting || password.length === 0}
                data-testid="SessionExpired.Submit"
              >
                {submitting ? 'Signing in…' : 'Continue working'}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </SessionRecoveryContext>
  );
}

/**
 * Registers the post-re-authentication replay, and settles the query cache after a session
 * transition made in another tab.
 *
 * Mounted **inside** `QueryClientProvider`, which is the whole point: it is the one place that
 * can see both the query client and the session provider. Renders nothing.
 */
export function SessionReplayBridge() {
  const queryClient = useQueryClient();
  const { registerReauthHandler } = useSessionRecovery();
  const { user } = useAuth();

  useEffect(
    () => registerReauthHandler(() => queryClient.refetchQueries({ type: 'active' })),
    [queryClient, registerReauthHandler],
  );

  /*
    Who this tab believed was signed in *before* the message arrived.

    Written during render, so at message time it still holds the pre-transition value even though
    `AuthProvider` — which subscribes first, being mounted above — has already called `setUser`.
    That is the whole reason the comparison lives here and not there: React has not re-rendered
    yet, so this is the only place both identities are legible at once.
  */
  const previousEmail = useRef<string | null>(null);
  previousEmail.current = user?.email ?? null;

  /*
    The same transition, in the tab that PERFORMED it — which the subscription below cannot see.

    `BroadcastChannel` deliberately does not echo to its sender (`sessionChannel` says so), so
    every word of the comment on that handler applies here and none of it was running for the one
    tab that mattered most. Signing out and signing in as somebody else in the same tab left the
    previous user's project list, their name and their permission answers on screen, served from a
    cache nothing had invalidated. Found in §52's live pass: a fresh sign-in rendered the
    *previous* company's project, and only a reload corrected it.

    Cleared on a *change of identity*, not on every identity: the first non-null value is this
    tab's own sign-in or a restored session, where the cache is either empty or already this
    user's. The ref is written in the effect rather than during render, so it holds what was true
    at the last commit.
  */
  const cachedFor = useRef<string | null>(null);
  useEffect(() => {
    const current = user?.email ?? null;
    if (cachedFor.current !== null && cachedFor.current !== current) {
      queryClient.clear();
    }
    cachedFor.current = current;
  }, [user, queryClient]);

  useEffect(
    () =>
      subscribeSession((message) => {
        // A different person is now holding this browser's token. Every cached answer was
        // computed for the previous user under their grants, and TanStack would happily render
        // it: the project list, their name in the rail, the permission answers `useCan` gates on.
        // This is the one cross-tab case that is a data-exposure bug and not a cosmetic one.
        const identityChanged =
          message.type === 'signed-out' || message.email !== previousEmail.current;

        if (identityChanged) {
          queryClient.clear();
          return;
        }

        // Same user signing back in — most often this tab's own expired session being renewed
        // from a sibling. The cache is still theirs, so it is refreshed rather than discarded,
        // which is the same replay `reportUnauthorized`'s dialog performs.
        void queryClient.refetchQueries({ type: 'active' });
      }),
    [queryClient],
  );

  return null;
}
