import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import { SessionRecoveryContext, type SessionRecoveryValue } from './sessionRecoveryContextValue';
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
        MUI's Dialog is used rather than a hand-rolled overlay because it already does focus
        trap, Esc handling, focus restore, and `aria-modal` correctly — and the accessibility
        rule is to take the library primitive over re-implementing the APG pattern.

        No `onClose`: this dialog is not dismissible by backdrop click or Esc. That is the one
        justified exception to "always offer a way out" — every request is failing until the
        session is restored, so dismissing it would leave the user clicking a dead page with no
        explanation. The way out is the explicit "Sign out" button.
      */}
      <Dialog
        open={open}
        maxWidth="xs"
        fullWidth
        aria-labelledby="session-expired-title"
        data-testid="SessionExpired.Dialog"
      >
        <form onSubmit={submit}>
          <DialogTitle id="session-expired-title" sx={{ fontSize: '1.125rem' }}>
            Your session has expired
          </DialogTitle>
          <DialogContent>
            <DialogContentText sx={{ mb: 2, fontSize: '0.875rem' }}>
              Sign in again to continue. <strong>Nothing you have typed has been lost</strong> —
              this page is still exactly where you left it.
            </DialogContentText>

            {user && (
              <Typography variant="body2" sx={{ mb: 2, color: 'text.secondary' }}>
                Signed in as {user.email}
              </Typography>
            )}

            {formError && (
              <Alert severity="error" sx={{ mb: 2 }} data-testid="SessionExpired.Error">
                {formError}
              </Alert>
            )}

            <TextField
              autoFocus
              fullWidth
              type="password"
              label="Password"
              size="small"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              disabled={submitting}
              data-testid="SessionExpired.Password"
              slotProps={{ htmlInput: { 'aria-label': 'Password' } }}
            />
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Stack direction="row" sx={{ gap: 1, width: '100%' }}>
              <Button size="small" onClick={signOut} data-testid="SessionExpired.SignOut">
                Sign out instead
              </Button>
              <Stack sx={{ flex: 1 }} />
              <Button
                type="submit"
                variant="contained"
                size="small"
                // The pending state IS the double-submit guard. Two sign-in attempts racing
                // would write two tokens and leave whichever lost the race in storage.
                disabled={submitting || password.length === 0}
                data-testid="SessionExpired.Submit"
              >
                {submitting ? 'Signing in…' : 'Continue working'}
              </Button>
            </Stack>
          </DialogActions>
        </form>
      </Dialog>
    </SessionRecoveryContext>
  );
}

/**
 * Registers the post-re-authentication replay.
 *
 * Mounted **inside** `QueryClientProvider`, which is the whole point: it is the one place that
 * can see both the query client and the session provider. Renders nothing.
 */
export function SessionReplayBridge() {
  const queryClient = useQueryClient();
  const { registerReauthHandler } = useSessionRecovery();

  useEffect(
    () => registerReauthHandler(() => queryClient.refetchQueries({ type: 'active' })),
    [queryClient, registerReauthHandler],
  );

  return null;
}
