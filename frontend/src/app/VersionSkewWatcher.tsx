import { useEffect } from 'react';
import { useSessionRecovery } from '../auth/useSessionRecovery';
import { useNotify } from '../notifications/useNotify';
import { BUILD_ID } from '../lib/reportClientError';
import { reloadOntoCurrentBuild } from './versionSkew';

/**
 * Detects a deploy that landed under an open tab, and offers the reload *before* the user hits
 * a chunk that no longer exists.
 *
 * The boundaries already recover from a chunk-load failure after the fact. This is the proactive
 * half: TCMS is exactly the kind of tool people leave open for days — a dashboard on a second
 * monitor, a run in progress — so the first thing a user does after a deploy is often click a
 * lazy route and get a broken page. Checking on focus turns that into a prompt they chose.
 *
 * It only ever *prompts*. Reloading a tab out from under someone, when the current view is still
 * working perfectly, would be a worse bug than the one being prevented.
 */
export function VersionSkewWatcher() {
  const notify = useNotify();
  const { hasUnsavedWork } = useSessionRecovery();

  useEffect(() => {
    // In dev, `__BUILD_ID__` is a per-process constant and nothing is ever deleted from the
    // server, so the check has nothing true to say and would only produce noise on every focus.
    if (import.meta.env.DEV) return;

    let cancelled = false;

    async function checkForNewBuild() {
      try {
        // `index.html` is the one file whose URL is stable across deploys; the hashed asset it
        // references is what changes. `cache: 'no-store'` matters — a cached copy would report
        // the build this tab already has, forever.
        const response = await fetch('/index.html', { cache: 'no-store' });
        if (!response.ok || cancelled) return;
        const html = await response.text();

        // The entry chunk's hashed filename is the build fingerprint: if the server is now
        // serving a different one, this tab is running code the server has moved on from.
        const served = /assets\/index-([A-Za-z0-9_-]+)\.js/.exec(html)?.[1];
        const running = document.querySelector<HTMLScriptElement>('script[src*="assets/index-"]')?.src;
        if (!served || !running || cancelled) return;
        if (running.includes(served)) return;

        notify.prompt({
          // A stable id, so checking on every focus updates the one prompt rather than stacking
          // a new one each time the user comes back to the tab.
          id: 'version-skew',
          message: hasUnsavedWork()
            ? 'A new version of TCMS is available. Save your work first — reloading will discard unsaved changes.'
            : 'A new version of TCMS is available.',
          actionLabel: 'Reload',
          onAction: reloadOntoCurrentBuild,
        });
      } catch {
        // Offline, or the network is down. Not knowing whether a deploy happened is not itself
        // something to tell the user about.
      }
    }

    function onFocus() {
      void checkForNewBuild();
    }

    window.addEventListener('focus', onFocus);
    return () => {
      cancelled = true;
      window.removeEventListener('focus', onFocus);
    };
  }, [notify, hasUnsavedWork]);

  // Reported once so a fault from a stale tab can be correlated with the build it came from.
  useEffect(() => {
    if (import.meta.env.DEV) console.info(`[tcms] build ${BUILD_ID}`);
  }, []);

  return null;
}
