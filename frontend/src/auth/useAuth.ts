import { use } from 'react';
import { AuthContext, type AuthValue } from './authContextValue';

export function useAuth(): AuthValue {
  // React 19 `use()` — callable conditionally, unlike useContext.
  const ctx = use(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within <AuthProvider>');
  return ctx;
}

/**
 * The signed-in user's id, or `undefined` — **the one reader that does not require a provider**.
 *
 * `useCan` needs it for `case:delete`'s `OWN` arm, and `useCan` is called by `PermissionGate`,
 * which is called by a couple of dozen components. Reaching for {@link useAuth} there would make
 * every one of those — and every test that renders one — depend on `<AuthProvider>` in order to
 * answer a question that, for all but one permission, has nothing to do with who the caller is.
 * That is a large coupling increase bought for one arm of one grant, and it showed up immediately:
 * 73 tests went red on a throw, none of them about authorship.
 *
 * **`undefined` is a real answer here, not a swallowed error.** No session means no caller, and no
 * caller matches no author — `isTheAuthor` in `useCan.ts` fails that arm closed, which is the same
 * verdict the server gives a request it cannot authenticate. Every other arm of the gate is
 * unaffected, because none of them asks who anybody is.
 *
 * A missing provider in the *app* is a different matter and is not this function's to catch:
 * `RequireAuth` could not have let a route render without one, so there is no production state
 * where this returns `undefined` and the user is nonetheless signed in.
 */
export function useCurrentUserId(): string | undefined {
  return use(AuthContext)?.user?.id;
}
