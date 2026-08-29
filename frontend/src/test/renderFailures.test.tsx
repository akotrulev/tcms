/**
 * The seam's own tests: it sees a real boundary trip, it sees a real failed request, it tells the
 * three kinds apart, and each can be suspended by the test whose subject it is.
 *
 * ## Why every case here goes through the real thing
 *
 * `renderFailures.ts` reads `window.__tcmsErrors`, which is filled only on the `import.meta.env.DEV`
 * branch of `reportClientError`'s `send()`. A test that pushed a hand-made report onto that array
 * would pass with the whole path between a component throwing and the array being written unplugged —
 * and a check that reads an array nobody fills is indistinguishable, from every green run, from a
 * suite in which nothing ever throws. So each case below renders a component that throws, inside the
 * boundary the app really uses, or drives a real query through `createTestQueryClient`.
 *
 * That second half is not a rule for its own sake: until §202 the request cases in this file *were*
 * two hand-made reports, and they were hand-made **because the path did not exist** — the test client
 * installed no caches, so nothing under test could produce a `query` report at all. The pair of
 * `reportClientError` calls asserted a classification while the thing being classified was
 * unreachable, which is the vacuity this file's own header warns about, one door down.
 *
 * `unexcused` is the one thing here tested against reports made by hand, and deliberately: it is a
 * pure decision over a list, and the claim it carries — a hatch excuses its own kind and nothing
 * else — is not observable from a check that decides *not* to fail.
 */
import { describe, expect, it, vi } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { useEffect, type ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClientProvider, useMutation, useQuery } from '@tanstack/react-query'
import { RegionErrorBoundary, RouteErrorBoundary } from '../components/ErrorBoundaries'
import type { ClientErrorReport } from '../lib/reportClientError'
import {
  drainCollectedFailures,
  drivingAFailedRead,
  drivingAFailedWrite,
  renderingIntoAnErrorBoundary,
  unexcused,
} from './renderFailures'
import { createTestQueryClient } from './queryClient'

/**
 * Every test file, as source, read through Vite rather than `node:fs` — `contractDoubles.test.ts`'s
 * glob and its reason: this file is compiled by `tsconfig.app.json`, which must not have
 * `types: ["node"]`, and `import.meta.glob` needs neither.
 */
const testSources = import.meta.glob('../**/*.test.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

function Throws(): ReactNode {
  throw new TypeError("Cannot read properties of undefined (reading 'map')")
}

/**
 * React logs a caught error through `console.error` on its own, above and beyond the app's sink.
 * Silenced per render rather than globally, so a case that throws where it did not mean to still
 * reports itself.
 */
function renderThrowing(tree: ReactNode) {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
  render(
      <MemoryRouter>{tree}</MemoryRouter>,
  )
  consoleError.mockRestore()
}

/**
 * The reports a render produced, once it has produced any.
 *
 * A failed request is asynchronous — the cache's `onError` runs a tick after the render returns — so
 * unlike a boundary trip it cannot be drained on the next line. Draining inside the `waitFor` is
 * safe because a drain of nothing clears nothing.
 */
async function collectedAfterAWait(): Promise<ClientErrorReport[]> {
  let reports: ClientErrorReport[] = []
  await waitFor(() => {
    reports = drainCollectedFailures()
    expect(reports.length).toBeGreaterThan(0)
  })
  return reports
}

/** The sink's DEV branch also logs; silenced so a deliberate failure is not read as suite noise. */
function renderQuerying(tree: ReactNode) {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  render(<QueryClientProvider client={createTestQueryClient()}>{tree}</QueryClientProvider>)
}

function FailingRead(): ReactNode {
  useQuery({ queryKey: ['cases', 'p1'], queryFn: () => Promise.reject(new Error('500')) })
  return <div data-testid="Rows" />
}

function FailingWrite(): ReactNode {
  const save = useMutation({ mutationKey: ['cases', 'create'], mutationFn: () => Promise.reject(new Error('409')) })
  const { mutate } = save
  useEffect(() => {
    mutate()
  }, [mutate])
  return null
}

describe('the render-failure seam', () => {
  it('sees a region catch a throw, and names the region', () => {
    renderingIntoAnErrorBoundary()

    renderThrowing(
      <RegionErrorBoundary testId="CaseVersions" label="this content">
        <Throws />
      </RegionErrorBoundary>,
    )

    const trips = drainCollectedFailures()
    expect(trips).toHaveLength(1)
    expect(trips[0]?.source).toBe('region-boundary')
    // The `testId` is what makes the failure point at a panel rather than at a page, and it is the
    // one thing the DOM would not have given back after a boundary reset.
    expect(trips[0]?.context?.region).toBe('CaseVersions')
    expect(trips[0]?.message).toContain('TypeError')
  })

  it('sees the route tier too, and carries the path', () => {
    renderingIntoAnErrorBoundary()

    renderThrowing(
      <RouteErrorBoundary>
        <Throws />
      </RouteErrorBoundary>,
    )

    const trips = drainCollectedFailures()
    expect(trips).toHaveLength(1)
    expect(trips[0]?.source).toBe('route-boundary')
    expect(trips[0]?.context?.path).toBe('/')
  })

  /**
   * The half §201 could not reach. The read below fails through the real test query client, so what
   * is being pinned is the whole path — cache `onError` → `reportClientError` → `__tcmsErrors` — and
   * not a classification of a report nothing produces.
   */
  it('sees a read that failed, and names the query key', async () => {
    // The failed read is the subject: this test exists to assert that the seam collects one.
    drivingAFailedRead()

    renderQuerying(<FailingRead />)

    const reports = await collectedAfterAWait()
    expect(reports).toHaveLength(1)
    expect(reports[0]?.source).toBe('query')
    // The key is what makes the failure name a panel rather than a page, exactly as `context.region`
    // does for a boundary trip.
    expect(reports[0]?.context?.queryKey).toBe('["cases","p1"]')
  })

  it('sees a write that was refused', async () => {
    // The refused write is the subject: this test exists to assert that the seam collects one.
    drivingAFailedWrite()

    renderQuerying(<FailingWrite />)

    const reports = await collectedAfterAWait()
    expect(reports).toHaveLength(1)
    expect(reports[0]?.source).toBe('mutation')
    expect(reports[0]?.context?.mutationKey).toBe('["cases","create"]')
  })

  /**
   * The reason there are three hatches and not one. 44 of the 61 tests §202 measured drive a refused
   * **write**, and none of them says anything about whether a panel beside the dialog was allowed to
   * fail its **read** — which is where §198's defect lived. A single hatch would have let the largest
   * group of exceptions in the suite disarm the check in the place it was written for.
   */
  it('excuses only the kind whose hatch was called', () => {
    const reports = [
      { source: 'query', message: 'read' },
      { source: 'mutation', message: 'write' },
      { source: 'region-boundary', message: 'throw' },
    ] as ClientErrorReport[]

    const kept = unexcused(reports, { render: false, query: false, mutation: true })

    expect(kept.map((report) => report.source)).toEqual(['query', 'region-boundary'])
  })

  /**
   * The vacuity guard, which is `contractDoubles.test.ts`'s and is here for a reason this slice
   * created: the request half sees a failure **only** through the caches `createTestQueryClient`
   * installs, so a test file that builds its own `new QueryClient(…)` is invisible to it — silently,
   * and looking exactly like a file in which nothing failed.
   *
   * Today there is no such file: the app's client and the test's are the only two constructed
   * anywhere, which is itself the thing being pinned. The count is the other half — the day the suite
   * stops going through this helper, the check covers nothing and says nothing.
   */
  it('still covers the suite it was installed for', () => {
    const files = Object.entries(testSources)
    expect(
      files.length,
      'no test sources found — the glob is relative to this file and the suite moved',
    ).toBeGreaterThan(80)

    const throughTheHelper = files.filter(([, source]) => source.includes('createTestQueryClient'))
    expect(
      throughTheHelper.length,
      'almost no test file gets its QueryClient from `createTestQueryClient` any more. The request '
        + 'half of this check reports through the caches that helper installs, so it now covers '
        + 'nothing while reporting nothing.',
    ).toBeGreaterThanOrEqual(50)

    // This file is excluded from its own sweep for the obvious reason: the needle appears in the
    // line below, so a source scan that included it would report the check as the violation.
    const ownClient = files.filter(
      ([path, source]) => !path.endsWith('renderFailures.test.tsx') && source.includes('new QueryClient('),
    )
    expect(
      ownClient.map(([path]) => path),
      'a test file constructs its own QueryClient. It will have no error caches, so every read and '
        + 'write that fails under it is invisible to this check — use `createTestQueryClient`.',
    ).toEqual([])
  })

  /** The three sources a component test cannot produce, and therefore must not be failed for. */
  it('says nothing about the sources only the app shell wires', () => {
    const reports = [
      { source: 'window-error', message: 'x' },
      { source: 'unhandled-rejection', message: 'x' },
      { source: 'recoverable-render', message: 'x' },
    ] as ClientErrorReport[]

    expect(unexcused(reports, { render: false, query: false, mutation: false })).toEqual([])
  })

  it('is quiet when nothing threw', () => {
    renderThrowing(
      <RegionErrorBoundary testId="CaseVersions" label="this content">
        <div data-testid="Rows" />
      </RegionErrorBoundary>,
    )

    expect(drainCollectedFailures()).toEqual([])
  })

  /**
   * The sink dedupes on `source:name:message` for the life of the module, so without the
   * `beforeEach` reset this seam installs, the *second* test in a file to hit the same broken panel
   * would collect nothing — the check would redden whichever test ran first and call every later one
   * clean, which is the exact reading failure it exists to end. The first case in this file threw the
   * same `TypeError` from the same tier; this one proves it is still reported.
   */
  it('reports a trip a previous test in this file already reported', () => {
    renderingIntoAnErrorBoundary()

    renderThrowing(
      <RegionErrorBoundary testId="CaseVersions" label="this content">
        <Throws />
      </RegionErrorBoundary>,
    )

    expect(drainCollectedFailures()).toHaveLength(1)
  })
})
