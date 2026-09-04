import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, PersonCapacity, Skill } from '../../api/types'
import { aPersonCapacity, aSkill, aUserRef } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { DirectoryPage } from './DirectoryPage'

/**
 * `directory.html` — and the acceptance criterion is not "the filters work", it is that
 * **"who is free next month with skill X" is answerable through filters held in the URL**, so the
 * answer can be sent to whoever asked.
 *
 * That is what the round-trip test below asserts in both directions: a URL renders the filter, and
 * a filter writes the URL. A filter in component state would pass a naive "clicking narrows the
 * list" test and fail the property the screen exists for.
 *
 * The arms are anchored — `/^\/staffing\/people$/` cannot catch `/staffing/people/{userId}`, and
 * `/staffing/skills` sits under the same prefix and answers a `Skill[]` where the list answers
 * `PersonCapacity[]`.
 */

const PLAYWRIGHT = aSkill({ id: 'sk-pw', name: 'Playwright' })
const JAVA = aSkill({ id: 'sk-java', name: 'Java' })

const MIRA = aPersonCapacity({
  person: aUserRef({ id: 'u-mira', displayName: 'Mira Sund' }),
  skills: [PLAYWRIGHT, JAVA],
  peakPercent: 130,
  allocatedPercent: 130,
  overAllocated: true,
  freePercent: 0,
  freeFrom: null,
})

const TOMAS = aPersonCapacity({
  person: aUserRef({ id: 'u-tomas', displayName: 'Tomas Ek' }),
  skills: [PLAYWRIGHT],
  allocatedPercent: 40,
  freePercent: 60,
  freeFrom: '2026-10-01',
  allocations: [
    {
      projectId: 'p1',
      projectKey: 'ACME',
      projectName: 'Acme Storefront',
      percent: 40,
      startsOn: '2026-09-01',
      endsOn: '2026-09-30',
    },
  ],
})

/**
 * The list read is keyed on its query string, so the double answers per filter set — which is what
 * makes the "3 of 11" denominator visible here at all: the unfiltered read is the SAME operation
 * with the skill and date parameters dropped.
 */
function mockReads({
  matching,
  everybody,
  skills = [PLAYWRIGHT, JAVA],
  permissions = ['staffing:directory'] as PermissionKey[],
}: {
  matching: PersonCapacity[]
  everybody?: PersonCapacity[]
  skills?: Skill[]
  permissions?: PermissionKey[]
}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    if (path === '/staffing/skills') return skills
    if (/^\/staffing\/people(\?|$)/.test(path)) {
      const filtered = path.includes('skill=') || path.includes('freeFrom=')
      return filtered ? matching : (everybody ?? matching)
    }
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage(route = '/directory') {
  return render(
    <TestProviders client={createTestQueryClient()} userId="u1" route={route}>
      <Routes>
        <Route path="/directory" element={<DirectoryPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('Skills directory', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('answers “who is free with skill X” from the URL alone', async () => {
    mockReads({ matching: [TOMAS], everybody: [MIRA, TOMAS] })
    renderPage('/directory?skill=sk-pw&freeFrom=2026-10-01')

    // The filter came out of the URL, so the same link in somebody else's browser is the same
    // question — which is the property the criterion is actually about.
    expect(await screen.findByTestId('Directory.Filters.Applied.skill:sk-pw')).toHaveTextContent(
      'Skill · Playwright',
    )
    expect(screen.getByTestId('Directory.Filters.Applied.freeFrom')).toHaveTextContent(
      'Free from · 2026-10-01',
    )
    expect(screen.getByTestId('Directory.Person.u-tomas')).toBeInTheDocument()
    expect(screen.queryByTestId('Directory.Person.u-mira')).toBeNull()

    // And the request carried both, ANDed by the server rather than filtered here.
    const paths = vi.mocked(api.get).mock.calls.map(([path]) => String(path))
    expect(paths.some((path) => path.includes('skill=sk-pw') && path.includes('freeFrom=2026-10-01'))).toBe(
      true,
    )
  })

  it('round-trips a skill chosen in the picker back out through the URL', async () => {
    mockReads({ matching: [TOMAS], everybody: [MIRA, TOMAS] })
    renderPage()

    await screen.findByTestId('Directory.Person.u-mira')
    await userEvent.selectOptions(screen.getByTestId('Directory.Filters.Skill'), 'sk-java')

    // The chip is the URL rendered back, not a second copy of the choice held beside it.
    expect(await screen.findByTestId('Directory.Filters.Applied.skill:sk-java')).toHaveTextContent(
      'Skill · Java',
    )
    await waitFor(() => {
      const paths = vi.mocked(api.get).mock.calls.map(([path]) => String(path))
      expect(paths.some((path) => path.includes('skill=sk-java'))).toBe(true)
    })
  })

  it('counts the matches against the whole company, from a second unfiltered read', async () => {
    /*
      The plan predicted this count was served. It is not — `GET /staffing/people` answers a bare
      array — so the denominator is the same operation with the filters dropped. Counting the
      filtered array would have given "3 of 3", which is not a denominator at all.
    */
    mockReads({ matching: [TOMAS], everybody: [MIRA, TOMAS] })
    renderPage('/directory?skill=sk-pw')

    expect(await screen.findByTestId('Directory.Filters')).toHaveTextContent('1 of 2 people match')
  })

  it('heads an over-allocated person with what they have, which is no room', async () => {
    mockReads({ matching: [MIRA, TOMAS] })
    renderPage()

    // The mockup's asymmetry, and it is right: somebody reading this screen wants room, so the
    // headline is how much — and "none, and here is why" for the person who has none.
    expect(await screen.findByTestId('Directory.Person.u-mira.Allocated')).toHaveTextContent(
      '130% allocated',
    )
    expect(screen.getByTestId('Directory.Person.u-tomas.Free')).toHaveTextContent('60% free')
    expect(screen.getByTestId('Directory.Person.u-tomas.FreeFrom')).toHaveTextContent(
      'Has room from 2026-10-01',
    )
    expect(screen.getByTestId('Directory.Person.u-mira.FreeFrom')).toHaveTextContent('Fully booked')
  })

  it('reads a person with no profile as an em dash, never 0h', async () => {
    const noProfile = aPersonCapacity({
      person: aUserRef({ id: 'u-new', displayName: 'New Starter' }),
      profile: null,
      capacityHours: null,
      absenceHours: null,
      allocatedCapacityHours: null,
      approvedHours: null,
      skills: [PLAYWRIGHT],
    })
    mockReads({ matching: [noProfile] })
    renderPage()

    const meta = await screen.findByTestId('Directory.Person.u-new.Profile.Meta')
    expect(meta).toHaveTextContent('no profile recorded')
    expect(meta).not.toHaveTextContent('0h')
    // And not the failed state — the read answered.
    expect(screen.queryByTestId('Directory.Region.Error')).toBeNull()
  })

  it('offers Profile disabled, because /directory/:userId does not exist yet', async () => {
    mockReads({ matching: [TOMAS] })
    renderPage()

    // A button linking to a route that is not there is the fake success plan 009 forbids; hiding
    // it would say this product has no person page (§31).
    expect(await screen.findByTestId('Directory.Person.u-tomas.Profile')).toBeDisabled()
  })

  it('refuses a caller without staffing:directory with copy naming the project screen', async () => {
    mockReads({ matching: [], permissions: ['staffing:read'] })
    renderPage()

    // Awaited on the copy, for `StaffingPage.test.tsx`'s reason: the panel is on screen from the
    // first frame carrying *Checking your permissions…*, because `useCan` fails closed while
    // pending and this screen renders that state in the same element.
    await screen.findByText(/The company directory is for Account Managers and Finance/)
    expect(screen.getByTestId('Directory.DeniedText')).toHaveTextContent('open that project’s Staffing')
    expect(screen.queryByTestId('Directory.Filters')).toBeNull()
  })

  it('tells an over-filtered empty list apart from an empty company', async () => {
    mockReads({ matching: [], everybody: [MIRA, TOMAS] })
    renderPage('/directory?skill=sk-java')

    const empty = await screen.findByTestId('Directory.Region.Empty')
    expect(empty).toHaveTextContent('Nobody matches')
    // The count of what the filters are hiding — the half every empty state in this product was
    // found to be missing.
    expect(empty).toHaveTextContent('2 people exist')
  })
})
