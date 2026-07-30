import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { api } from '../api/client';
import type { AuthResult, CurrentUser } from '../api/types';
import { safeStorage } from '../lib/safeStorage';
import { AuthContext, type AuthValue } from './authContextValue';

/**
 * Session state only — never server data (state-management skill). Cross-cutting and
 * rarely written, so Context is the right primitive; no store needed.
 *
 * The Context object and `useAuth` live in sibling files so this one exports only a
 * component, keeping Fast Refresh working.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  // Read once at mount rather than in render — storage access in render is a hydration
  // hazard, and safeStorage degrades to in-memory when localStorage throws.
  const [user, setUser] = useState<CurrentUser | null>(() =>
    safeStorage.getJSON<CurrentUser>('tcms.user'),
  );

  const login = useCallback(async (email: string, password: string, rememberMe: boolean) => {
    const result = await api.post<AuthResult>('/auth/login', { email, password, rememberMe });
    safeStorage.set('tcms.token', result.token);
    safeStorage.setJSON('tcms.user', result.user);
    setUser(result.user);
  }, []);

  const logout = useCallback(() => {
    safeStorage.remove('tcms.token');
    safeStorage.remove('tcms.user');
    setUser(null);
    void api.post('/auth/logout').catch(() => {
      /* logging out locally is what matters; a failed server call must not trap the user */
    });
  }, []);

  const value = useMemo<AuthValue>(() => ({ user, login, logout }), [user, login, logout]);

  return <AuthContext value={value}>{children}</AuthContext>;
}
