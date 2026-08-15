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
  /**
   * Resolves with the signed-in user rather than `void` so the caller can label the credential it
   * offers the password manager with a display name. The provider's own `user` state is set by
   * this call, but a caller awaiting it still holds the pre-login render's closure, so reading it
   * from context instead would read `null`.
   */
  login: (email: string, password: string, rememberMe: boolean) => Promise<CurrentUser>;
  /**
   * Adopts a session this app did not mint the credential for — today, the one that arrives in the
   * `#token=` fragment after an external sign-in (§6r, §222).
   *
   * Separate from {@link login} rather than a second call path into it: `login` posts a password
   * and receives a user, this receives a token and must go and ask who it belongs to. What the two
   * share is everything after that, which is why the token is written in exactly one place either
   * way — a caller that reached storage directly would leave `user` null, `status` stale and
   * sibling tabs uncorrected.
   *
   * Rejects when the server will not confirm the token, having cleared it.
   */
  adoptSession: (token: string) => Promise<CurrentUser>;
  logout: () => void;
}

export const AuthContext = createContext<AuthValue | null>(null);
