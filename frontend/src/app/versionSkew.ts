/**
 * Deploy version skew — the tab that outlived its build.
 *
 * Every route in this app is `lazy()`-split, which is what makes this a real defect rather
 * than a theoretical one: a tab left open across a deploy still holds the previous build's
 * chunk map, so the next navigation requests a hashed chunk the deploy deleted. The user
 * clicks a nav item that has nothing wrong with it and gets a blank region.
 *
 * It is invisible in development — Vite never deletes a chunk from under you — and completely
 * reliable in production, which is why it normally ships.
 *
 * Two rules make the recovery safe rather than a new bug:
 *
 *   1. **The reload is guarded by `sessionStorage`.** An unguarded auto-reload against a build
 *      that is genuinely broken is an infinite reload loop — strictly worse than the error it
 *      replaces. One attempt per tab, then show the failure and stop.
 *   2. **Unsaved work is prompted, never reloaded over.** Same principle as the 401 ladder:
 *      a recovery that discards what the user typed is not a recovery.
 */

import { BUILD_ID } from '../lib/reportClientError';

const RELOAD_GUARD_KEY = 'tcms.skewReloadAttempted';

/**
 * True when an error is a failed dynamic import rather than a fault in our own code.
 *
 * The message text differs by bundler and browser, which is why this matches several forms
 * rather than one: Rolldown/Vite emit "Failed to fetch dynamically imported module", webpack
 * emits a `ChunkLoadError`, and Safari words its import failure differently again.
 */
export function isChunkLoadError(error: unknown): boolean {
  if (!error) return false;
  const name = error instanceof Error ? error.name : '';
  if (name === 'ChunkLoadError') return true;

  const message = error instanceof Error ? error.message : String(error);
  return (
    /Failed to fetch dynamically imported module/i.test(message) ||
    /error loading dynamically imported module/i.test(message) ||
    /Importing a module script failed/i.test(message) ||
    /Loading chunk \S+ failed/i.test(message)
  );
}

/** Whether this tab has already spent its one reload attempt. */
function alreadyAttempted(): boolean {
  try {
    return sessionStorage.getItem(RELOAD_GUARD_KEY) === BUILD_ID;
  } catch {
    // Private mode / quota. Without a guard we cannot promise "at most once", so we must not
    // reload at all — a loop is worse than a message.
    return true;
  }
}

function markAttempted(): void {
  try {
    sessionStorage.setItem(RELOAD_GUARD_KEY, BUILD_ID);
  } catch {
    /* guarded above; nothing to do */
  }
}

/**
 * Clears the guard once the app has run successfully on the current build, so a *future*
 * deploy gets its own reload attempt. Without this, the first skew recovery in a tab's life
 * would be the only one it ever gets.
 */
export function clearSkewGuard(): void {
  try {
    if (sessionStorage.getItem(RELOAD_GUARD_KEY) === BUILD_ID) {
      sessionStorage.removeItem(RELOAD_GUARD_KEY);
    }
  } catch {
    /* nothing to clear */
  }
}

export type SkewOutcome =
  /** Not a skew error at all — the caller should handle it as an ordinary failure. */
  | { kind: 'not-skew' }
  /** Reloading now; the caller should render nothing further. */
  | { kind: 'reloading' }
  /** Recovery needs the user's consent (unsaved work) or the one attempt is already spent. */
  | { kind: 'prompt' };

/**
 * Decides what to do about a possible skew error.
 *
 * `hasUnsavedWork` is passed in rather than read here because only the app knows it — an open
 * dirty form, a pending mutation, a half-recorded execution.
 */
export function handlePossibleSkew(error: unknown, hasUnsavedWork: boolean): SkewOutcome {
  if (!isChunkLoadError(error)) return { kind: 'not-skew' };

  if (hasUnsavedWork || alreadyAttempted()) return { kind: 'prompt' };

  markAttempted();
  return { kind: 'reloading' };
}

/** The reload itself, kept separate so the decision above stays testable without navigating. */
export function reloadOntoCurrentBuild(): void {
  markAttempted();
  window.location.reload();
}
