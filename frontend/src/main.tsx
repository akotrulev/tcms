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
import { installFaultConsole } from './lib/devFaults';
import { installGlobalErrorHandlers } from './lib/globalErrorHandlers';
import { reportClientError } from './lib/reportClientError';

/**
 * Entry point.
 *
 * There is no mock API. Every request goes to the real backend — through the Vite dev proxy in
 * development (`VITE_API_PROXY_TARGET`, default `http://localhost:8080`), same-origin in
 * production. `npm run dev` therefore needs `docker compose up` and a provisioned company; see
 * `.env.example`.
 */

// Installed before render, so a fault during the very first paint is still reported. These cover
// the two paths React error boundaries cannot see: event handlers and promise rejections.
installGlobalErrorHandlers();

/*
  `window.tcmsFaults` — the console switches that drive the resilience behaviour (see
  `lib/devFaults.ts`). The `import.meta.env.DEV` test must stay a LITERAL here: Vite replaces it
  with `false` in place, so Rollup proves the call dead and drops the module with it. Behind an
  imported constant it cannot — routing an equivalent check through one shipped the whole former
  mock layer as a 485 kB production chunk, measured in the build output.
*/
if (import.meta.env.DEV) installFaultConsole();

// This build got far enough to execute, so any skew reload it performed has done its job.
// Releasing the guard here means a *future* deploy gets its own single reload attempt, rather
// than the first recovery in a tab's life being the only one it ever gets.
clearSkewGuard();

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
