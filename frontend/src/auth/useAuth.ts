import { use } from 'react';
import { AuthContext, type AuthValue } from './authContextValue';

export function useAuth(): AuthValue {
  // React 19 `use()` — callable conditionally, unlike useContext.
  const ctx = use(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within <AuthProvider>');
  return ctx;
}
