import { safeStorage } from '../lib/safeStorage';

/**
 * The access token, held in memory and mirrored into `localStorage` so it survives a reload and
 * is there for a tab that opens later.
 *
 * ## Why this is a module and not state
 *
 * `api/client.ts` attaches the credential to every request, and it is not a component — it cannot
 * read a context. A module variable is the only seam both sides can reach, and the token is a
 * genuine per-tab singleton rather than shared *user data*, so it is the one shape the
 * "no mutable module state" rule exempts.
 *
 * ## ~~What moving it out of `localStorage` costs, and what it buys~~ → superseded
 *
 * ~~It buys the obvious thing: a token at rest in `localStorage` is readable by any script that
 * achieves execution on this origin, at any later time, including long after the injection that
 * planted it. In memory it dies with the tab.~~
 *
 * ~~It costs a reload. There is no refresh endpoint yet — external OIDC and its rotation land with
 * backend M4 — so a token this app cannot re-mint is a token a reload cannot recover, and every
 * refresh, restored tab and new window starts at the login form. That is the accepted trade.~~
 *
 * The trade was reversed on the user's instruction: with no refresh endpoint, "dies with the tab"
 * in practice meant *every new tab and every refresh lands on the login form*, which is what the
 * memory-only shape actually cost day to day. So the token is persisted again, and the XSS
 * exposure it re-opens is accepted deliberately rather than by omission — a script that achieves
 * execution on this origin can read this key at any later time, so it is the CSP and the input
 * paths, not this module, that carry the weight of preventing that.
 *
 * Storage is only a **mirror**. The in-memory copy stays authoritative for reads, so a
 * `localStorage` that throws (quota, Safari private mode, a blocked third-party context) degrades
 * exactly to the old behaviour — a working session for the life of the tab — rather than to a
 * broken one. `safeStorage` is what makes that true; it never throws at this caller.
 *
 * Whether the persisted token is still *valid* is unchanged and still a server question:
 * `AuthContext` confirms it against `/me` on mount and clears it on a 401. Persisting a credential
 * does not persist a session.
 *
 * Cross-tab sign-in still carries the token on `sessionChannel`'s `signed-in` message rather than
 * asking siblings to re-read storage — a `BroadcastChannel` message routes through the browser
 * process and can beat the sibling renderer's view of the write, which read as `null` on roughly
 * half the runs when it was tried.
 */

const STORAGE_KEY = 'tcms-access-token';

/**
 * Seeded from storage at module evaluation — i.e. before `AuthProvider` first renders, which is
 * what lets its mount-time `/me` confirmation see a token at all on a fresh page load.
 */
let token: string | null = safeStorage.get(STORAGE_KEY);

export const accessToken = {
  get: (): string | null => token,
  set: (next: string): void => {
    token = next;
    safeStorage.set(STORAGE_KEY, next);
  },
  clear: (): void => {
    token = null;
    safeStorage.remove(STORAGE_KEY);
  },
};
