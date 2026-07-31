import { Box, CircularProgress, Stack, Typography } from '@mui/material';
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
    <Box
      component="main"
      role="status"
      aria-busy="true"
      data-testid="Session.Restoring"
      sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: 'background.default' }}
    >
      <Stack sx={{ alignItems: 'center', gap: 2 }}>
        {/* The wordmark, so the wait reads as the app starting rather than as a blank page. */}
        <Identifier variant="strong" sx={{ fontSize: '1.5rem', letterSpacing: '0.06em' }}>
          TCMS
        </Identifier>
        <CircularProgress size={20} aria-hidden />
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Restoring your session…
        </Typography>
      </Stack>
    </Box>
  );
}
