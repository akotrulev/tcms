/**
 * The access token, held in memory for the life of the tab and never written to storage.
 *
 * ## Why this is a module and not state
 *
 * `api/client.ts` attaches the credential to every request, and it is not a component — it cannot
 * read a context. A module variable is the only seam both sides can reach, and the token is a
 * genuine per-tab singleton rather than shared *user data*, so it is the one shape the
 * "no mutable module state" rule exempts.
 *
 * ## What moving it out of `localStorage` costs, and what it buys
 *
 * It buys the obvious thing: a token at rest in `localStorage` is readable by any script that
 * achieves execution on this origin, at any later time, including long after the injection that
 * planted it. In memory it dies with the tab.
 *
 * It costs a reload. There is no refresh endpoint yet — external OIDC and its rotation land with
 * backend M4 — so a token this app cannot re-mint is a token a reload cannot recover, and every
 * refresh, restored tab and new window starts at the login form. That is the accepted trade, and
 * it is what `SessionRecoveryProvider`'s ladder is already shaped to absorb: rungs 1–2 slot in
 * above it when M4 lands, and this file is where the recovered token will be put.
 *
 * Cross-tab sign-in still works, and deliberately not through storage: `sessionChannel` carries
 * the token on the `signed-in` message. A `BroadcastChannel` is same-origin like `localStorage`
 * is, so this is no weaker against a script already running here — but the message is transient,
 * so a script that arrives *afterwards* has nothing to read.
 */

let token: string | null = null;

export const accessToken = {
  get: (): string | null => token,
  set: (next: string): void => {
    token = next;
  },
  clear: (): void => {
    token = null;
  },
};
