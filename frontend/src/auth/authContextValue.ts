import { createContext } from 'react';
import type { CurrentUser } from '../api/types';

/**
 * The Context object and its consumer hook live apart from the provider component so the
 * provider file exports only a component — otherwise Fast Refresh stops working for it
 * (oxlint react/only-export-components).
 */
export interface AuthValue {
  user: CurrentUser | null;
  login: (email: string, password: string, rememberMe: boolean) => Promise<void>;
  logout: () => void;
}

export const AuthContext = createContext<AuthValue | null>(null);
