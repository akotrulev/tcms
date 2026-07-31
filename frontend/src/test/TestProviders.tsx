import { ThemeProvider } from '@mui/material/styles'
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { theme } from '../app/theme'

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
 * The REAL theme is used rather than a bare `createTheme()`, because component code reads
 * `theme.palette.*` and `statusColors` — a default theme would make a missing token render
 * fine under test and blank in the app.
 */
export function TestProviders({
  client,
  route = '/',
  children,
}: {
  client: QueryClient
  /** Initial URL. Only matters for components that read the router — `useCaseFilters`, links. */
  route?: string
  children: ReactNode
}) {
  return (
    <MemoryRouter initialEntries={[route]}>
      <ThemeProvider theme={theme}>
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      </ThemeProvider>
    </MemoryRouter>
  )
}
