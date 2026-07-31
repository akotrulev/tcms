import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiError, api } from '../api/client';
import type { AuthResult, CurrentUser } from '../api/types';
import { safeStorage } from '../lib/safeStorage';
import { AuthContext, type AuthValue, type SessionStatus } from './authContextValue';

const TOKEN_KEY = 'tcms.token';
const USER_KEY = 'tcms.user';

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
  const [user, setUser] = useState<CurrentUser | null>(() => {
    // A user without a token is not a session. A half-written pair is discarded rather than
    // allowed to render a signed-in shell that cannot make a single successful request.
    if (!safeStorage.get(TOKEN_KEY)) {
      safeStorage.remove(USER_KEY);
      return null;
    }
    return safeStorage.getJSON<CurrentUser>(USER_KEY);
  });

  const [status, setStatus] = useState<SessionStatus>(() =>
    safeStorage.get(TOKEN_KEY) ? 'restoring' : 'ready',
  );

  const clearSession = useCallback(() => {
    safeStorage.remove(TOKEN_KEY);
    safeStorage.remove(USER_KEY);
    setUser(null);
  }, []);

  useEffect(() => {
    if (!safeStorage.get(TOKEN_KEY)) return;

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
  }, [clearSession]);

  const login = useCallback(async (email: string, password: string, rememberMe: boolean) => {
    const result = await api.post<AuthResult>('/auth/login', { email, password, rememberMe });
    safeStorage.set(TOKEN_KEY, result.token);
    safeStorage.setJSON(USER_KEY, result.user);
    setUser(result.user);
    setStatus('ready');
  }, []);

  const logout = useCallback(() => {
    clearSession();
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
