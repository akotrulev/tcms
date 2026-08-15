import { Box, CircularProgress, Stack, Typography } from '@mui/material';
import { useEffect, useRef, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { Identifier } from '../../components/Identifier';
import { consumeSsoReturnPath } from './ssoReturnPath';

/**
 * Where an external sign-in lands (§6r, §222).
 *
 * The backend redirects here with the session in the URL **fragment** — `#token=…&expiresAt=…` — or
 * with `#error=sso_failed`. A fragment is never sent to a server, so the token does not reach this
 * instance's access log, the reverse proxy's, or the `Referer` of the first outbound link this app
 * renders. That property is the reason the flow ends in a route rather than in a redirect the SPA
 * never sees, and it is why the first thing this component does is take the value out of the URL.
 *
 * ## Three things here are not stylistic
 *
 * **The fragment is read during the first render, not in the effect.** The effect wipes it, and
 * React runs effects twice in development — an effect that read *and* wiped would see nothing the
 * second time and report a failure for a sign-in that worked. A second ref guards the adoption, so
 * a double-invoked effect adopts once.
 *
 * **`expiresAt` is parsed and ignored.** It is in the fragment because `AuthResult` carries it and
 * both sign-in paths mint the same session. Nothing in this app has ever stored a session expiry:
 * the server enforces it (8h/30d, plus the `pv` epoch), and a client-side copy is a number that
 * drifts on clock skew and that nothing acts on.
 *
 * **Every failure is one sentence, and this page does not write it.** Disabled provider, expired
 * attempt, replayed `state`, unverified email, deactivated account, no invitation — the server
 * deliberately sends one opaque marker for all of them (§6r). The refusal is rendered by the
 * sign-in screen, which is where somebody who could not get in needs to be anyway.
 */
export function SsoCallbackPage() {
  const { adoptSession } = useAuth();

  /** Captured before anything can wipe it. See the header. */
  const fragment = useRef(new URLSearchParams(window.location.hash.replace(/^#/, '')));
  const started = useRef(false);
  /*
    Where the sign-in returns to — the deep link `LoginPage` stashed before handing the browser to
    the issuer, `/` when nothing was. Consumed inside the `started` guard rather than during render,
    because consuming removes it and a double-invoked render would read it gone.
  */
  const returnTo = useRef('/');
  const [outcome, setOutcome] = useState<'pending' | 'signed-in' | 'failed'>('pending');

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    returnTo.current = consumeSsoReturnPath();

    // Out of the address bar first, and unconditionally: a live session survives a copy-paste, a
    // screen share and the browser's own history until it is gone from here.
    window.history.replaceState(null, '', window.location.pathname);

    const token = fragment.current.get('token');
    if (!token) {
      // Covers `#error=sso_failed` and an empty fragment alike. An empty one is not a pending
      // sign-in — nothing else is coming, because the redirect that would have carried a token has
      // already happened.
      setOutcome('failed');
      return;
    }

    void adoptSession(token).then(
      () => setOutcome('signed-in'),
      () => setOutcome('failed'),
    );
  }, [adoptSession]);

  if (outcome === 'signed-in') return <Navigate to={returnTo.current} replace />;

  if (outcome === 'failed') {
    // `replace`, so the browser's back button does not return to a callback whose `state` the
    // server has already consumed — that lands on a second, identical failure and reads as a loop.
    return <Navigate to="/login" replace state={{ ssoFailed: true }} />;
  }

  return (
    <Box
      component="main"
      role="status"
      aria-busy="true"
      data-testid="Sso.Completing"
      sx={{
        minHeight: '100%',
        display: 'grid',
        placeItems: 'center',
        px: 2,
        py: 6,
        bgcolor: 'background.default',
      }}
    >
      <title>Signing in · TCMS</title>
      <Stack sx={{ alignItems: 'center', gap: 2 }}>
        <Identifier variant="strong" sx={{ fontSize: '1.5rem', letterSpacing: '0.06em' }}>
          TCMS
        </Identifier>
        <CircularProgress size={20} aria-hidden />
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Signing you in…
        </Typography>
      </Stack>
    </Box>
  );
}
