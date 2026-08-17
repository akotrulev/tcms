import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../client'
import { createTestQueryClient } from '../../test/queryClient'
import {
  aRoleAssignment,
  aTeam,
  aUserInvitation,
} from '../../test/fixtures'
import {
  useCreateTeam,
  useDeleteTeam,
  useGrantRole,
  useInviteUser,
  usePutTeamMember,
  useRemoveTeamMember,
  useRemoveUser,
  useRevokeRole,
  useUpdateTeam,
} from './admin'
import { keys } from './keys'

/**
 * `admin.ts` as source, read through Vite rather than `node:fs` — `contractDoubles.test.ts`'s glob
 * and its reason: this file is compiled by `tsconfig.app.json`, which must not have
 * `types: ["node"]`, and `import.meta.glob` needs neither.
 */
const adminSource = Object.values(
  import.meta.glob('./admin.ts', { query: '?raw', import: 'default', eager: true }),
)[0] as string

/**
 * **Nine arms, not one** — every staffing write invalidates `keys.orgChart`.
 *
 * The chart is a derived view of `teams`, `users`, `role-assignments` and `projects` at once, so
 * any write touching those four can move a cell in it. One test asserting "the invalidation exists"
 * would pass on eight of nine and the ninth is the defect: a stale chart in exactly the moment
 * somebody just restaffed a project, which is the moment they opened it. So each mutation gets its
 * own named arm — and because a tenth staffing write added later would have no arm here, the last
 * test derives the population from the module's own source rather than trusting this list.
 *
 * Driven through the hooks rather than a source scan: what matters is that the invalidation runs on
 * success, and a regex would pass on a `keys.orgChart` sitting in a comment or in the wrong branch.
 */
describe('every staffing write invalidates the org chart', () => {
  let client: QueryClient
  let invalidated: unknown[][]

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }

  beforeEach(() => {
    client = createTestQueryClient()
    invalidated = []
    const real = client.invalidateQueries.bind(client)
    vi.spyOn(client, 'invalidateQueries').mockImplementation((filters) => {
      invalidated.push((filters?.queryKey ?? []) as unknown[])
      return real(filters)
    })
  })

  /** Runs one mutation to success and answers what it invalidated. */
  async function drive<TVariables>(
    use: () => { mutate: (variables: TVariables) => void },
    variables: TVariables,
  ): Promise<unknown[][]> {
    const { result } = renderHook(use, { wrapper })
    result.current.mutate(variables)
    await waitFor(() => expect(invalidated.length).toBeGreaterThan(0))
    // The last invalidation is the one under test in every hook here, but the assertion below
    // looks at the whole list — a hook that invalidated the chart FIRST and something else after
    // is just as correct.
    return invalidated
  }

  function invalidatesTheChart(calls: unknown[][]): boolean {
    return calls.some((key) => JSON.stringify(key) === JSON.stringify(keys.orgChart))
  }

  it('useCreateTeam', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(aTeam() as never)

    expect(invalidatesTheChart(await drive(useCreateTeam, {
      name: 'QA', description: null, parentTeamId: null,
    }))).toBe(true)
  })

  it('useUpdateTeam', async () => {
    vi.spyOn(api, 'put').mockResolvedValue(aTeam() as never)

    expect(invalidatesTheChart(await drive(useUpdateTeam, {
      teamId: 't1',
      input: { name: 'QA', description: null, parentTeamId: null },
    }))).toBe(true)
  })

  it('useDeleteTeam', async () => {
    vi.spyOn(api, 'del').mockResolvedValue(undefined as never)

    expect(invalidatesTheChart(await drive(useDeleteTeam, 't1'))).toBe(true)
  })

  it('usePutTeamMember', async () => {
    vi.spyOn(api, 'put').mockResolvedValue(aTeam() as never)

    expect(invalidatesTheChart(await drive(usePutTeamMember, {
      teamId: 't1', userId: 'u1', input: { lead: false },
    }))).toBe(true)
  })

  it('useRemoveTeamMember', async () => {
    vi.spyOn(api, 'del').mockResolvedValue(undefined as never)

    expect(invalidatesTheChart(await drive(useRemoveTeamMember, {
      teamId: 't1', userId: 'u1',
    }))).toBe(true)
  })

  it('useInviteUser', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(aUserInvitation() as never)

    expect(invalidatesTheChart(await drive(useInviteUser, {
      email: 'new@acme.test', displayName: 'N. Person',
    }))).toBe(true)
  })

  it('useRemoveUser', async () => {
    vi.spyOn(api, 'del').mockResolvedValue(undefined as never)

    expect(invalidatesTheChart(await drive(useRemoveUser, 'u1'))).toBe(true)
  })

  it('useGrantRole', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(aRoleAssignment() as never)

    expect(invalidatesTheChart(await drive(useGrantRole, {
      roleId: 'r1', userId: 'u1', teamId: null, projectId: 'p1',
    }))).toBe(true)
  })

  it('useRevokeRole', async () => {
    vi.spyOn(api, 'del').mockResolvedValue(undefined as never)

    expect(invalidatesTheChart(await drive(useRevokeRole, 'ra1'))).toBe(true)
  })

  it('and a TENTH staffing write would have no arm above — so the list is derived, not trusted', () => {
    // The nine arms are a list somebody has to remember to extend. This one asks the module
    // instead: any `onSuccess` invalidating teams, users, role-assignments or projects is a
    // staffing write by definition, and must invalidate the chart in the same block.
    const blocks = adminSource.split(/^export function /m).slice(1)
    const missing: string[] = []

    for (const block of blocks) {
      if (!block.includes('onSuccess:')) continue
      const name = block.slice(0, block.indexOf('('))
      const onSuccess = block.slice(block.indexOf('onSuccess:'))
      const touchesStaffing = /keys\.(teams|users|roleAssignments|projects)\b/.test(onSuccess)
      if (touchesStaffing && !/keys\.orgChart\b/.test(onSuccess)) {
        missing.push(name)
      }
    }

    expect(missing, 'these invalidate a key the org chart is derived from and not the chart itself')
      .toEqual([])
  })
})
