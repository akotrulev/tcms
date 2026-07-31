import { createContext } from 'react';
import type { CurrentUser } from '../api/types';

/**
 * The Context object and its consumer hook live apart from the provider component so the
 * provider file exports only a component — otherwise Fast Refresh stops working for it
 * (oxlint react/only-export-components).
 */

/**
 * `restoring` is the state that only exists once there is a real backend: a token in storage
 * is a *claim* to a session, not a session. It may have expired, or been revoked by a `pv`
 * bump, and only the server can say. Until `/me` answers, the app knows neither that it is
 * signed in nor that it is signed out — and rendering either answer is a guess that costs
 * something: guess "in" and every region 401s at once; guess "out" and a valid session is
 * bounced to the login screen on every reload.
 */
export type SessionStatus = 'restoring' | 'ready';

export interface AuthValue {
  user: CurrentUser | null;
  status: SessionStatus;
  login: (email: string, password: string, rememberMe: boolean) => Promise<void>;
  logout: () => void;
}

export const AuthContext = createContext<AuthValue | null>(null);
