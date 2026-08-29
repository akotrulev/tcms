import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { AuthContext, type AuthValue } from '../auth/authContextValue'
import type { CurrentUser } from '../api/types'

/**
 * The smallest provider tree a component under test can need — and no more.
 *
 * `app/Providers.tsx` is deliberately NOT reused here. Its nesting order is load-bearing
 * (§6 lesson 6: the query client is constructed innermost because its error handlers consume
 * the notification and session-recovery providers), and mounting all of it around a unit test
 * would drag in the notification stack, the announcer, the session recovery dialog and the real
 * retry policy. A unit test that fails because the notification provider changed is a test of
 * the wrong thing.
 *
 * ## ~~The REAL theme is used rather than a bare `createTheme()`~~ → no theme provider at all (T16)
 *
 * ~~because component code reads `theme.palette.*` and `statusColors` — a default theme would make
 * a missing token render fine under test and blank in the app.~~ The concern was real and its
 * answer is now structural: there is no theme *object*. Colour reaches a component as a Tailwind
 * utility compiled against `index.css`'s custom properties, so a component under test resolves the
 * same token the app does with nothing mounted around it — and jsdom applies no stylesheet either
 * way, so a wrapper here would prove nothing it used to prove.
 */
export function TestProviders({
  client,
  route = '/',
  userId,
  children,
}: {
  client: QueryClient
  /** Initial URL. Only matters for components that read the router — `useCaseFilters`, links. */
  route?: string
  /**
   * The signed-in user's id, for the one gate that compares it: `case:delete`'s `OWN` qualifier
   * asks whether the caller wrote the row.
   *
   * **Omitted is the default and answers "no session"**, which is why the other 470 tests needed no
   * change when authorship landed — `useCan` reads this through the non-throwing
   * `useCurrentUserId`, so a component that has nothing to do with authorship does not have to
   * mount an auth provider to render. A test that asserts an author-gated affordance must pass it,
   * or it is asserting the fail-closed arm by accident.
   */
  userId?: string
  children: ReactNode
}) {
  const tree = (
    <MemoryRouter initialEntries={[route]}>
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    </MemoryRouter>
  )

  if (userId === undefined) return tree

  /*
    Only the `id` carries meaning — nothing under test reads the rest, and a plausible display name
    would invite a test to assert against a fixture no screen actually receives this way. So the
    other four required fields are deliberately *inert* values rather than absent ones.

    They used to be absent, behind an `as CurrentUser` that §196 removed. That cast was the one
    place in the harness where the session could disagree with `CurrentUser` and nothing would say
    so: `teams` is a required array, so a component that mapped it would have thrown here on a
    session the server can never send — and `isSystemAdmin` absent is falsy, which is an *answer*
    to an authorisation question that nobody wrote down.
  */
  const user: CurrentUser = {
    id: userId,
    displayName: 'Test user',
    email: 'test-user@example.test',
    isSystemAdmin: false,
    // True, like the fixture: an account that reached a screen has a way in, and the
    // "set a password" branch (005 S9a) is the exception that passes its own.
    hasPassword: true,
    teams: [],
  }
  const session: AuthValue = {
    user,
    status: 'ready',
    // Resolves with the same stub user the provider exposes: `login` returns the signed-in user so
    // the caller can label the credential it offers the password manager.
    login: async () => user,
    // Resolves with the same stub user for `login`'s reason. A test whose subject IS the adoption
    // supplies its own double rather than asserting against this one.
    adoptSession: async () => user,
    logout: () => {},
  }
  return <AuthContext value={session}>{tree}</AuthContext>
}
