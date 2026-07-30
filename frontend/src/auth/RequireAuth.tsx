import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './useAuth';

/**
 * The route gate. React Router's equivalent of the App Router's `middleware.ts`, and with
 * the same caveat: this is a **coarse gate, not authorization**. It only knows whether a
 * session exists in this browser. Per-project permissions are enforced by the API on every
 * request, and this component must never be treated as the thing that protects data.
 *
 * The attempted path travels in navigation state so sign-in returns the user where they
 * were headed rather than dumping them on the landing page.
 */
export function RequireAuth() {
  const { user } = useAuth();
  const location = useLocation();

  if (!user) {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
  }

  return <Outlet />;
}
