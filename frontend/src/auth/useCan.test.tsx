import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api/client'
import type { EffectivePermissions, PermissionKey } from '../api/types'
import { TestProviders } from '../test/TestProviders'
import { createTestQueryClient } from '../test/queryClient'
import { useCan } from './useCan'

/**
 * The effective-permission gate (§31), which decides eleven write affordances.
 *
 * This is tested through the REAL `useMyPermissions` — with `api.get` stubbed at the module
 * boundary rather than the hook stubbed at the React boundary — because two of the three states
 * the gate must answer for are produced by TanStack Query rather than by `useCan`'s own code:
 *
 *   - **A disabled query reports `isPending`.** `useMyPermissions` gates itself on `enabled:
 *     !!projectId`, and a disabled query is `pending` forever. Mocking the hook would let a test
 *     assert whatever it liked about "no project" without ever touching the wiring that produces
 *     it.
 *   - **A failure resolves to `isError` with `data === undefined`**, which is the fall-open path.
 *
 * The asymmetry under test — loading denies, failure allows — is the part that looks wrong and
 * is not. A wrong *disable* tells a Test Lead they may not do their job, with no error visible
 * anywhere, because the failure is in a query that renders nothing.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111'

function wrapper(children: ReactNode) {
  const client = createTestQueryClient()
  return <TestProviders client={client}>{children}</TestProviders>
}

const answer = (permissions: PermissionKey[]): EffectivePermissions => ({
  projectId: PROJECT,
  permissions,
})

/** Resolves only when the test says so, so the pending state can be observed rather than raced. */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('useCan', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  describe('while the answer is still in flight', () => {
    it('denies, and says so, rather than flashing an enabled control', () => {
      // Disabled-then-enabled costs a grey moment. Enabled-then-disabled is the defect itself:
      // the click lands in the window before the answer does.
      const pending = deferred<EffectivePermissions>()
      vi.spyOn(api, 'get').mockReturnValue(pending.promise)

      const { result } = renderHook(() => useCan(PROJECT), { wrapper: ({ children }) => wrapper(children) })

      expect(result.current.can('case:create')).toBe(false)
      expect(result.current.reason('case:create')).toBe('Checking your permissions…')
    })

    it('flips to allowed once the answer lands', async () => {
      const pending = deferred<EffectivePermissions>()
      vi.spyOn(api, 'get').mockReturnValue(pending.promise)

      const { result } = renderHook(() => useCan(PROJECT), { wrapper: ({ children }) => wrapper(children) })
      expect(result.current.can('case:create')).toBe(false)

      pending.resolve(answer(['case:read', 'case:create']))

      await waitFor(() => expect(result.current.can('case:create')).toBe(true))
      expect(result.current.reason('case:create')).toBeUndefined()
    })
  })

  describe('once the server has answered', () => {
    it('allows exactly what was granted and denies the rest', async () => {
      vi.spyOn(api, 'get').mockResolvedValue(answer(['case:read', 'case:edit', 'suite:manage']))

      const { result } = renderHook(() => useCan(PROJECT), { wrapper: ({ children }) => wrapper(children) })
      await waitFor(() => expect(result.current.can('case:read')).toBe(true))

      expect(result.current.can('case:edit')).toBe(true)
      expect(result.current.can('suite:manage')).toBe(true)
      expect(result.current.can('case:delete')).toBe(false)
      expect(result.current.can('suite:delete')).toBe(false)
      expect(result.current.can('result:submit')).toBe(false)
    })

    it('explains a denial in the words of the affordance, not the catalogue', async () => {
      vi.spyOn(api, 'get').mockResolvedValue(answer(['case:read']))

      const { result } = renderHook(() => useCan(PROJECT), { wrapper: ({ children }) => wrapper(children) })
      await waitFor(() => expect(result.current.can('case:read')).toBe(true))

      // The tooltip §31 put on eleven controls. A key in this sentence would be a bug the
      // compiler cannot see.
      expect(result.current.reason('case:delete')).toBe(
        "You don't have permission to delete test cases in this project.",
      )
      expect(result.current.reason('case:delete')).not.toContain('case:delete')
      expect(result.current.reason('case:read')).toBeUndefined()
    })

    /*
      An empty grant is a REAL answer, not a missing one — a company member with no grant on this
      project. It must deny everything, which is the opposite of what the failure path does, and
      the two are one falsy check apart in the implementation.
    */
    it('denies everything on an empty grant, and does not mistake it for a failure', async () => {
      vi.spyOn(api, 'get').mockResolvedValue(answer([]))

      const { result } = renderHook(() => useCan(PROJECT), { wrapper: ({ children }) => wrapper(children) })
      // Waiting on "reason is defined" would pass instantly against the *pending* copy — the two
      // states are told apart only by which sentence it is.
      await waitFor(() =>
        expect(result.current.reason('case:read')).not.toBe('Checking your permissions…'),
      )

      expect(result.current.can('case:read')).toBe(false)
      expect(result.current.can('case:create')).toBe(false)
      expect(result.current.reason('case:create')).toBe(
        "You don't have permission to create test cases in this project.",
      )
    })
  })

  describe('when the answer cannot be obtained — the deliberate asymmetry', () => {
    it.each([
      ['a 500', new ApiError(500, 'boom')],
      ['a 403 on the permissions endpoint itself', new ApiError(403, 'no')],
      ['a network fault', new TypeError('Failed to fetch')],
    ])('falls OPEN on %s, so nobody is told they may not do their job', async (_label, error) => {
      vi.spyOn(api, 'get').mockRejectedValue(error)

      const { result } = renderHook(() => useCan(PROJECT), { wrapper: ({ children }) => wrapper(children) })

      await waitFor(() => expect(result.current.can('case:delete')).toBe(true))
      // Enabled AND unexplained: there is nothing to say, and a tooltip on an enabled control
      // would be noise.
      expect(result.current.reason('case:delete')).toBeUndefined()
      expect(result.current.can('company:settings')).toBe(true)
    })

    it('costs at most one 403 in a dialog, which is how the app behaved before the gate existed', async () => {
      vi.spyOn(api, 'get').mockRejectedValue(new ApiError(503, 'unavailable'))

      const { result } = renderHook(() => useCan(PROJECT), { wrapper: ({ children }) => wrapper(children) })
      await waitFor(() => expect(result.current.can('result:submit')).toBe(true))

      // The server is still the gate. Nothing here is a security control (§31), and asserting
      // that it fails open is asserting the design rather than an accident.
      expect(result.current.can('instance:audit')).toBe(true)
    })
  })

  describe('when there is no project yet', () => {
    it('denies without asking, because there is nothing to be permitted in', () => {
      const get = vi.spyOn(api, 'get')

      const { result } = renderHook(() => useCan(undefined), { wrapper: ({ children }) => wrapper(children) })

      expect(result.current.can('case:create')).toBe(false)
      expect(result.current.reason('case:create')).toBe('Checking your permissions…')
      // `enabled: !!projectId` — the request must not be made at all, or every screen that
      // renders before its route params resolve fires a doomed call.
      expect(get).not.toHaveBeenCalled()
    })
  })

  describe('the answer is per project', () => {
    it('does not let one project’s grant decide another project’s buttons', async () => {
      // The same person is a Test Lead on one project and a Viewer on the next. A single
      // ['permissions'] cache key would carry the first answer into the second screen.
      const other = '22222222-2222-2222-2222-222222222222'
      vi.spyOn(api, 'get').mockImplementation((path: string) =>
        Promise.resolve(
          path.includes(PROJECT) ? answer(['case:create', 'case:read']) : answer(['case:read']),
        ) as Promise<never>,
      )

      const client = createTestQueryClient()
      const shared = ({ children }: { children: ReactNode }) => (
        <TestProviders client={client}>{children}</TestProviders>
      )

      const lead = renderHook(() => useCan(PROJECT), { wrapper: shared })
      await waitFor(() => expect(lead.result.current.can('case:create')).toBe(true))

      const viewer = renderHook(() => useCan(other), { wrapper: shared })
      await waitFor(() => expect(viewer.result.current.can('case:read')).toBe(true))

      expect(viewer.result.current.can('case:create')).toBe(false)
      expect(lead.result.current.can('case:create')).toBe(true)
    })

    it('sends the project id as a query parameter, never as a path segment', async () => {
      const get = vi.spyOn(api, 'get').mockResolvedValue(answer(['case:read']))

      const { result } = renderHook(() => useCan(PROJECT), { wrapper: ({ children }) => wrapper(children) })
      await waitFor(() => expect(result.current.can('case:read')).toBe(true))

      expect(get).toHaveBeenCalledWith(`/me/permissions?projectId=${PROJECT}`, expect.anything())
    })
  })
})
