import '@fontsource/ibm-plex-sans/400.css';
import '@fontsource/ibm-plex-sans/500.css';
import '@fontsource/ibm-plex-sans/600.css';
import '@fontsource/ibm-plex-mono/400.css';
import '@fontsource/ibm-plex-mono/600.css';
import './index.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { Providers } from './app/Providers';
import { AppRouter } from './app/router';
import { clearSkewGuard } from './app/versionSkew';
import { RootErrorBoundary } from './components/ErrorBoundaries';
import { installGlobalErrorHandlers } from './lib/globalErrorHandlers';
import { reportClientError } from './lib/reportClientError';

/**
 * Entry point.
 *
 * MSW starts before the app renders, and only in development. The `await` here is the point:
 * mounting first and starting the worker second gives a race where the app's opening
 * requests slip past the interceptor and fail against a server that doesn't exist yet.
 *
 * The dynamic import keeps the mock handlers and their fixtures out of the production
 * bundle entirely — `import.meta.env.DEV` is statically false in a production build, so the
 * whole branch is tree-shaken away.
 */
async function startMockApi(): Promise<void> {
  if (!import.meta.env.DEV) return;

  const { worker } = await import('./mocks/browser');
  await worker.start({
    // The app only ever calls /api/v1; anything else (fonts, HMR, source maps) should pass
    // through silently rather than filling the console with warnings.
    onUnhandledRequest: 'bypass',
    quiet: false,
  });
}

// Installed before render, so a fault during the very first paint is still reported. These cover
// the two paths React error boundaries cannot see: event handlers and promise rejections.
installGlobalErrorHandlers();

// This build got far enough to execute, so any skew reload it performed has done its job.
// Releasing the guard here means a *future* deploy gets its own single reload attempt, rather
// than the first recovery in a tab's life being the only one it ever gets.
clearSkewGuard();

void startMockApi().then(() => {
  createRoot(document.getElementById('root')!, {
    /**
     * React 19 root-level error reporting. Both hooks matter, and both are easy to miss.
     *
     * `onCaughtError` centralizes what an error boundary caught, so reporting lives in one place
     * instead of being duplicated into every boundary's `onError`.
     *
     * `onRecoverableError` catches what React *silently survives* — most importantly a hydration
     * mismatch, which it repairs by re-rendering on the client while logging to a console nobody
     * has open. That is a genuine correctness signal (something browser-only was read during
     * render), and without this hook it never reaches the logs at all.
     */
    onCaughtError: (error) => reportClientError(error, 'root-boundary'),
    onRecoverableError: (error) => reportClientError(error, 'recoverable-render'),
  }).render(
    <StrictMode>
      {/*
        The root boundary sits OUTSIDE the providers on purpose: a throw from the theme, the
        query client, or the session provider has nothing above it to catch, and that is exactly
        the failure that would otherwise leave a permanently blank page.
      */}
      <RootErrorBoundary>
        <BrowserRouter>
          <Providers>
            <AppRouter />
          </Providers>
        </BrowserRouter>
      </RootErrorBoundary>
    </StrictMode>,
  );
});
