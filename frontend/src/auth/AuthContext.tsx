import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiError, api } from '../api/client';
import type { AuthResult, CurrentUser } from '../api/types';
import { accessToken } from './accessToken';
import { AuthContext, type AuthValue, type SessionStatus } from './authContextValue';
import { publishSession, subscribeSession } from './sessionChannel';

/**
 * Session state only — never server data (state-management skill). Cross-cutting and
 * rarely written, so Context is the right primitive; no store needed.
 *
 * The Context object and `useAuth` live in sibling files so this one exports only a
 * component, keeping Fast Refresh working.
 *
 * ## Restoring a session is a server question
 *
 * The stored user is a **cache of the last `/me`**, not the session itself. It exists so the
 * first paint has a name without waiting for a round trip; the token is what authenticates,
 * and whether that token is still good is a fact only the server holds — it expires (8h/30d),
 * and a `pv` bump revokes every session for the account at once.
 *
 * So on mount, when a token is present, this provider asks, and the app waits for the answer
 * rather than rendering a session it has not confirmed. Against MSW the check was pointless —
 * the mock never expires anything, which is why it did not exist. Against the real API it is
 * the difference between a reload landing where you left off and a reload landing on a dozen
 * simultaneous session-expired dialogs.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  /*
    A fresh tab starts signed out, because the token lives in memory and this module was just
    evaluated — see `accessToken.ts` for the trade and what M4 changes about it. So there is no
    stored user to paint optimistically any more; the name arrives with `/me` or not at all.

    The mount-time confirmation below is kept rather than deleted even though a real page load
    can never satisfy it: it is the seam a recovered token comes back through, and it is what
    M4's refresh rung will hand a session to. Costing one branch to keep the restore path alive
    is cheaper than rediscovering it.
  */
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [status, setStatus] = useState<SessionStatus>(() =>
    accessToken.get() ? 'restoring' : 'ready',
  );

  const clearSession = useCallback(() => {
    accessToken.clear();
    setUser(null);
  }, []);

  /**
   * Bumped when another tab signs in, to re-run the confirmation below against the session it
   * just wrote. State rather than a ref because the effect has to *re-run*, and a nonce rather
   * than a boolean because two sign-ins in a row must both be confirmed.
   */
  const [restoreNonce, setRestoreNonce] = useState(0);

  useEffect(() => {
    // No token, nothing to confirm. `restoreNonce` re-runs this when a sibling tab hands one over.
    if (!accessToken.get()) return;

    // The ignore flag is the cleanup guard the data-fetching skill requires of any effect
    // fetch: this effect runs twice under StrictMode, and without it the abandoned first
    // response can write state after the second has already settled it.
    let ignore = false;

    void api
      .get<CurrentUser>('/me')
      .then((fresh) => {
        if (ignore) return;
        // Adopt the server's answer wholesale — a display name is per-membership (plan §6u) and
        // an admin may have changed it since sign-in.
        setUser(fresh);
      })
      .catch((error: unknown) => {
        if (ignore) return;
        // 401 is the only answer that means "this session is over". A 500, a timeout, or a
        // backend that is still starting must NOT sign the user out — that would turn a
        // thirty-second restart into everyone losing their place, and the next real request
        // surfaces the failure honestly through the ordinary error path anyway.
        if (error instanceof ApiError && error.status === 401) clearSession();
      })
      .finally(() => {
        if (!ignore) setStatus('ready');
      });

    return () => {
      ignore = true;
    };
  }, [clearSession, restoreNonce]);

  /**
   * A session transition made in another tab.
   *
   * Both branches are corrections of a lie this tab would otherwise keep telling, and neither
   * re-publishes — the channel excludes the sender, but a listener that broadcast what it just
   * received would still ping-pong between three tabs.
   */
  useEffect(
    () =>
      subscribeSession((message) => {
        if (message.type === 'signed-out') {
          // This tab holds its own copy of the token, so clearing is what actually ends the
          // session here. It sends this tab through `RequireAuth` to the login screen with its
          // location in navigation state.
          //
          // This is the one place the session ends without the user pressing anything *in this
          // tab* — and it is correct precisely because they pressed it in another one. It is not
          // the mid-session-401 case, where the ladder keeps the route mounted over unsaved work:
          // an expiry happened *to* the user, a sign-out is what they asked for.
          clearSession();
          return;
        }

        /*
          Someone signed in elsewhere, and the message carries the token because this tab has no
          copy of its own to read — see `accessToken.ts`.

          ~~**This deliberately does not adopt by reading storage, and that cost a browser pass to
          learn.** `localStorage` is shared between tabs but it is not synchronously consistent
          between the renderer *processes* they run in: a `BroadcastChannel` message routes
          through the browser process and can arrive before the sibling's view of the write has
          caught up. Reading storage here returned `null` on roughly half the runs.~~ → the race
          is gone with the shared copy it was about: the credential arrives *in* the message, so
          there is no second view of it to be stale.

          The session is still re-confirmed against `/me` rather than trusted, which is the
          mechanism this provider already documents as correct ("restoring a session is a server
          question"): the sign-in may be a different person, and the server's answer — not the
          broadcast — decides whose name and whose projects this tab renders. A token the server
          rejects 401s and clears, the same fail-closed outcome as before.
        */
        accessToken.set(message.token);
        setStatus('restoring');
        setRestoreNonce((nonce) => nonce + 1);
      }),
    [clearSession],
  );

  const login = useCallback(async (email: string, password: string, rememberMe: boolean) => {
    const result = await api.post<AuthResult>('/auth/login', { email, password, rememberMe });
    accessToken.set(result.token);
    setUser(result.user);
    setStatus('ready');
    // The token travels with the message: siblings hold no copy of their own, so this is the
    // only thing that gives them one.
    publishSession({ type: 'signed-in', email: result.user.email, token: result.token });
    return result.user;
  }, []);

  /**
   * The external-sign-in landing (§6r, §222). The fragment carries a token and nothing else, so
   * unlike `login` this has to ask the server who it is — which is the mechanism this provider
   * already calls correct above: restoring a session is a server question.
   *
   * A rejected `/me` **clears the token**, and unlike the mount-time confirmation it clears on any
   * failure rather than on a 401 alone. The asymmetry is deliberate: there, a token already in
   * storage is a session the user had, and a backend restart must not sign everybody out; here
   * there is nothing to preserve — a credential that has never once been confirmed buys a shell
   * that cannot make a single successful request, and the caller can start the sign-in again.
   *
   * The fragment also carries `expiresAt`, and nothing reads it — here or anywhere. The session's
   * end is the server's to enforce (8h/30d, plus the `pv` epoch); a second copy of it on the client
   * is a number that drifts on clock skew and that nothing acts on.
   */
  const adoptSession = useCallback(
    async (token: string) => {
      accessToken.set(token);
      setStatus('restoring');
      try {
        const fresh = await api.get<CurrentUser>('/me');
        setUser(fresh);
        publishSession({ type: 'signed-in', email: fresh.email, token });
        return fresh;
      } catch (error) {
        clearSession();
        throw error;
      } finally {
        setStatus('ready');
      }
    },
    [clearSession],
  );

  const logout = useCallback(() => {
    clearSession();
    publishSession({ type: 'signed-out' });
    void api.post('/auth/logout').catch(() => {
      /* logging out locally is what matters; a failed server call must not trap the user */
    });
  }, [clearSession]);

  const value = useMemo<AuthValue>(
    () => ({ user, status, login, adoptSession, logout }),
    [user, status, login, adoptSession, logout],
  );

  return <AuthContext value={value}>{children}</AuthContext>;
}
