import { QueryClientProvider } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { AnnouncerProvider } from '../a11y/AnnouncerProvider';
import { AuthProvider } from '../auth/AuthContext';
import { SessionRecoveryProvider, SessionReplayBridge } from '../auth/SessionRecoveryProvider';
import { useSessionRecovery } from '../auth/useSessionRecovery';
import { Toaster } from '../components/ui';
import { NotificationsProvider } from '../notifications/NotificationsProvider';
import { useNotify } from '../notifications/useNotify';
import { createAppQueryClient } from './queryClient';
import { ColorSchemeProvider } from './colorScheme';
import { VersionSkewWatcher } from './VersionSkewWatcher';

/**
 * All providers mount once, here. A Vite SPA has no RSC boundary, so this is simply the root
 * wrapper — but the "providers once at the root, never per page" rule still holds.
 *
 * The nesting order is load-bearing:
 *
 *   ColorScheme → Announcer → Notifications → Auth → SessionRecovery → QueryClient
 *
 * ~~`ThemeProvider theme={theme} defaultMode="system"` + `CssBaseline`~~ → superseded in T16.
 * `ColorSchemeProvider` stands in exactly their position and keeps the one behaviour they carried
 * that nothing else could: it writes the resolved scheme onto `<html>`, which is what `index.css`'s
 * dark palette selects on and what a Radix dialog portalled into `document.body` needs in order to
 * be painted at all. Preflight replaced the rest of `CssBaseline`.
 *
 * The query client is created **innermost** because its global error handlers have to raise
 * notifications and trigger session recovery, so both of those must already be above it.
 * Building the client in this component instead would place it above its own dependencies,
 * where `useNotify()` does not resolve.
 */
export function Providers({ children }: { children: ReactNode }) {
  return (
    <ColorSchemeProvider>
      {/*
        Success and informational toasts are `sonner`, anchored bottom-right, where auto-dismiss
        is correct behaviour — ~~notistack's `SnackbarProvider`~~ sat in exactly this position for
        exactly this reason. Failures do NOT come through here: they go to
        `NotificationsProvider`, top-right, which never auto-dismisses. That split is the point:
        one provider cannot be both auto-hiding and persistent, and an error notification that
        vanishes after four seconds is an unreported bug.

        `<Toaster />` is a sibling rather than a wrapper because sonner's queue is a module
        singleton (`notifications/toast.ts` calls into it directly); the component only renders
        it. Nothing below needs to be inside it, and it stays under the colour-scheme provider
        because it draws app chrome.
      */}
      <Toaster position="bottom-right" visibleToasts={3} theme="system" />
      <AnnouncerProvider>
        <NotificationsProvider>
          <AuthProvider>
            <SessionRecoveryProvider>
              <QueryProvider>{children}</QueryProvider>
            </SessionRecoveryProvider>
          </AuthProvider>
        </NotificationsProvider>
      </AnnouncerProvider>
    </ColorSchemeProvider>
  );
}

/**
 * Split out purely so the query client is constructed *below* the notification and session
 * providers, and can therefore consume their hooks.
 */
function QueryProvider({ children }: { children: ReactNode }) {
  const notify = useNotify();
  const { reportUnauthorized } = useSessionRecovery();

  /*
    Created once, in state, so a hot reload doesn't discard the cache on every render.

    The handlers are read from a lazily-initialized closure rather than being re-bound when they
    change. Both are stable (`useNotify`'s value is memoized and `reportUnauthorized` is a
    `useCallback`), and rebuilding the QueryClient to pick up a new handler identity would throw
    away every cached query — a far worse outcome than a slightly stale closure.
  */
  const [queryClient] = useState(() =>
    createAppQueryClient({ notify, onUnauthorized: reportUnauthorized }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      {/* Both need to see the query client AND the session provider, so they mount here. */}
      <SessionReplayBridge />
      <VersionSkewWatcher />
      {children}
    </QueryClientProvider>
  );
}
