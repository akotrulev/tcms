import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiError, api } from '../api/client';
import type { AuthResult, CurrentUser } from '../api/types';
import { safeStorage } from '../lib/safeStorage';
import { AuthContext, type AuthValue, type SessionStatus } from './authContextValue';
import { publishSession, subscribeSession } from './sessionChannel';

const TOKEN_KEY = 'tcms.token';
const USER_KEY = 'tcms.user';

/**
 * The stored session, or null.
 *
 * A user without a token is not a session. A half-written pair is discarded rather than allowed
 * to render a signed-in shell that cannot make a single successful request.
 *
 * This is the *optimistic first paint* only — it exists so a reload shows a name without waiting
 * for a round trip. Whether the session is real is settled below, by the server.
 */
function readStoredSession(): CurrentUser | null {
  if (!safeStorage.get(TOKEN_KEY)) {
    safeStorage.remove(USER_KEY);
    return null;
  }
  return safeStorage.getJSON<CurrentUser>(USER_KEY);
}

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
  // Read once at mount rather than in render — storage access in render is a hydration
  // hazard, and safeStorage degrades to in-memory when localStorage throws.
  const [user, setUser] = useState<CurrentUser | null>(readStoredSession);

  const [status, setStatus] = useState<SessionStatus>(() =>
    safeStorage.get(TOKEN_KEY) ? 'restoring' : 'ready',
  );

  const clearSession = useCallback(() => {
    safeStorage.remove(TOKEN_KEY);
    safeStorage.remove(USER_KEY);
    setUser(null);
  }, []);

  /**
   * Bumped when another tab signs in, to re-run the confirmation below against the session it
   * just wrote. State rather than a ref because the effect has to *re-run*, and a nonce rather
   * than a boolean because two sign-ins in a row must both be confirmed.
   */
  const [restoreNonce, setRestoreNonce] = useState(0);

  useEffect(() => {
    // On mount, no token means no session to confirm. After a cross-tab sign-in it means the
    // opposite — see the note at the broadcast handler on why the write may not be visible yet.
    if (restoreNonce === 0 && !safeStorage.get(TOKEN_KEY)) return;

    // The ignore flag is the cleanup guard the data-fetching skill requires of any effect
    // fetch: this effect runs twice under StrictMode, and without it the abandoned first
    // response can write state after the second has already settled it.
    let ignore = false;

    void api
      .get<CurrentUser>('/me')
      .then((fresh) => {
        if (ignore) return;
        // Adopt the server's answer wholesale. A display name is per-membership (plan §6u) and
        // an admin may have changed it since sign-in — the cached copy is the stale one.
        safeStorage.setJSON(USER_KEY, fresh);
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
          // The token is already gone from shared storage, so this tab could not make a single
          // successful request even if it wanted to. Clearing sends it through `RequireAuth` to
          // the login screen with its location in navigation state.
          //
          // This is the one place the session ends without the user pressing anything *in this
          // tab* — and it is correct precisely because they pressed it in another one. It is not
          // the mid-session-401 case, where the ladder keeps the route mounted over unsaved work:
          // an expiry happened *to* the user, a sign-out is what they asked for.
          clearSession();
          return;
        }

        /*
          Someone signed in elsewhere. The token in shared storage is now theirs, so continuing
          to render the previous user's name over it is a false claim about who is looking at
          the data — and if it is a different person, the data is not theirs to see.

          **This deliberately does not adopt by reading storage, and that cost a browser pass to
          learn.** `localStorage` is shared between tabs but it is not synchronously consistent
          between the renderer *processes* they run in: a `BroadcastChannel` message routes
          through the browser process and can arrive before the sibling's view of the write has
          caught up. Reading storage here returned `null` on roughly half the runs, so the
          sibling adopted "no session" and sat on the login form holding a perfectly valid token.
          Nothing in the unit tests could see it — one jsdom process has one storage.

          So the session is re-confirmed against `/me` instead, which is the mechanism this
          provider already documents as correct ("restoring a session is a server question") and
          which is immune to the race by construction: the request is at least a macrotask away,
          it carries whatever token storage holds *at request time*, and the server's answer —
          not the browser's — decides who this is. A genuinely absent token 401s and clears,
          which is the same fail-closed outcome as before.
        */
        setStatus('restoring');
        setRestoreNonce((nonce) => nonce + 1);
      }),
    [clearSession],
  );

  const login = useCallback(async (email: string, password: string, rememberMe: boolean) => {
    const result = await api.post<AuthResult>('/auth/login', { email, password, rememberMe });
    safeStorage.set(TOKEN_KEY, result.token);
    safeStorage.setJSON(USER_KEY, result.user);
    setUser(result.user);
    setStatus('ready');
    // Published after storage is written, never before: a sibling adopts by *reading* storage,
    // so a broadcast that overtook the write would have it adopt the session it already had.
    publishSession({ type: 'signed-in', email: result.user.email });
    return result.user;
  }, []);

  const logout = useCallback(() => {
    clearSession();
    publishSession({ type: 'signed-out' });
    void api.post('/auth/logout').catch(() => {
      /* logging out locally is what matters; a failed server call must not trap the user */
    });
  }, [clearSession]);

  const value = useMemo<AuthValue>(
    () => ({ user, status, login, logout }),
    [user, status, login, logout],
  );

  return <AuthContext value={value}>{children}</AuthContext>;
}
