import { isChunkLoadError } from '../app/versionSkew';
import { reportClientError } from './reportClientError';

/**
 * The last-resort listeners, for the two failure paths React error boundaries cannot see:
 *
 *   - **Event handlers.** A throw inside an `onClick` is not part of a render, so no boundary
 *     catches it. It surfaces as `window.onerror` — or, with no listener, as nothing at all.
 *   - **Unhandled promise rejections.** A floating `void somePromise()` that rejects, or an
 *     `async` handler nobody awaited, produces an `unhandledrejection` event and is otherwise
 *     completely silent.
 *
 * Both are exactly the faults that get reported as "it just didn't do anything", because from
 * the user's side that is precisely what happened.
 *
 * These only *report* — they never render. Anything that needs a UI response has already been
 * handled by a boundary or by the query client's error handlers.
 */

let installed = false;

export function installGlobalErrorHandlers(): void {
  // Guarded because Vite's HMR can re-execute a module. Two installs would double every report.
  if (installed) return;
  installed = true;

  window.addEventListener('error', (event) => {
    // A failed dynamic import arrives here too, when the failure happens outside a render that a
    // boundary would have caught. It is not a bug in our code — it is a stale build. The
    // boundaries own the recovery; here it just needs to be recorded as what it actually is.
    reportClientError(
      event.error ?? new Error(event.message),
      'window-error',
      isChunkLoadError(event.error) ? { kind: 'chunk-load' } : null,
    );
  });

  window.addEventListener('unhandledrejection', (event) => {
    reportClientError(
      event.reason,
      'unhandled-rejection',
      isChunkLoadError(event.reason) ? { kind: 'chunk-load' } : null,
    );
  });
}
