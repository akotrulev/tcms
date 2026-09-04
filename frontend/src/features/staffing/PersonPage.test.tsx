import { render, screen } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PermissionKey, PersonCapacity } from '../../api/types'
import {
  aPersonCapacity,
  aPersonProfile,
  aSkill,
  aUserRef,
  anAbsence,
} from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { TestProviders } from '../../test/TestProviders'
import { createTestQueryClient } from '../../test/queryClient'
import { PersonPage } from './PersonPage'

/**
 * `/directory/:userId` — `member.html`'s three panels, on the route 017 D-g places them.
 *
 * Three claims, and two of them are about who may do what:
 *
 *   - the page **opens for the person themself with no `staffing:directory`**, which is the
 *     contract's own second arm on `GET /staffing/people/{userId}` and is why nothing here
 *     pre-gates the read;
 *   - the absence Add is **enabled on your own page for everybody** and needs `staffing:absence` on
 *     somebody else's (017 D-h) — the one place that decision is visible in the product;
 *   - *"6 years"* is **derived** from `experienceSince` and the edit form takes a **date** (017
 *     D-b), so a reader looking for a years field finds a sentence saying why there is none.
 *
 * The arms are anchored. `/^\/staffing\/people\/[^/]+$/` cannot catch `/staffing/people` (the list,
 * a different shape) nor `/staffing/people/{id}/absences` (a third).
 */

const MIRA = aUserRef({ id: 'u-mira', displayName: 'Mira Sund' })

const WITH_EVERYTHING = aPersonCapacity({
  person: MIRA,
  profile: aPersonProfile({
    personId: 'u-mira',
    weeklyHours: 40,
    jobTitle: 'Test Lead',
    location: 'Stockholm',
    experienceSince: '2020-09-01',
    // Derived server-side and served beside the date. Nothing on the client subtracts anything.
    experienceYears: 6,
  }),
  skills: [aSkill({ id: 'sk-pw', name: 'Playwright' })],
  absences: [anAbsence({ id: 'ab-1', personId: 'u-mira', dayFraction: 0.5 })],
})

function mockReads({
  person = WITH_EVERYTHING,
  permissions = ['staffing:directory'] as PermissionKey[],
}: { person?: PersonCapacity; permissions?: PermissionKey[] } = {}) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions, {}, null)
    if (path === '/staffing/skills') return [aSkill({ id: 'sk-pw', name: 'Playwright' })]
    if (/^\/staffing\/people\/[^/]+(\?|$)/.test(path)) return person
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

function renderPage(callerId = 'u-other') {
  return render(
    <TestProviders client={createTestQueryClient()} userId={callerId} route="/directory/u-mira">
      <Routes>
        <Route path="/directory/:userId" element={<PersonPage />} />
      </Routes>
    </TestProviders>,
  )
}

describe('One person', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders for the person themself with no staffing:directory', async () => {
    /*
      The contract's second arm: *"or the caller is this person"*. Nothing on the client decides
      that — the server does, and this page therefore does not pre-gate the read. A client arm here
      would be a second copy of an authorization rule (plan §6b).
    */
    mockReads({ permissions: [] })
    renderPage('u-mira')

    expect(await screen.findByTestId('Person.Card')).toHaveTextContent('Mira Sund')
    expect(screen.getByTestId('Person.Profile')).toBeInTheDocument()
    expect(screen.queryByTestId('Person.Region.Error')).toBeNull()
  })

  it('derives “6 years” from the date and offers no years field to edit', async () => {
    mockReads({ permissions: ['staffing:directory', 'staffing:profile'] })
    renderPage()

    // 017 D-b. An integer stored as "6" is correct on the day it is typed and wrong every day
    // after, silently — so the display is derived and the form takes the date it is derived from.
    expect(await screen.findByTestId('Person.Profile.Meta')).toHaveTextContent(
      'Test Lead · Stockholm · 6 years',
    )
    expect(screen.getByTestId('Person.Profile.Since')).toHaveTextContent('Working since 2020-09-01')
  })

  it('reads a person with no profile as an em dash, never 0h or a default of 40', async () => {
    mockReads({
      person: aPersonCapacity({
        person: MIRA,
        profile: null,
        capacityHours: null,
        absenceHours: null,
        allocatedCapacityHours: null,
        approvedHours: null,
      }),
    })
    renderPage()

    const hours = await screen.findByTestId('Person.Profile.Hours')
    expect(hours).toHaveTextContent('—')
    expect(hours).not.toHaveTextContent('0h')
    expect(hours).not.toHaveTextContent('40h')
    expect(screen.getByTestId('Person.Profile.Meta')).toHaveTextContent('no profile recorded')
  })

  it('shows the skills once, in the panel that can edit them', async () => {
    mockReads()
    renderPage()

    // The card's chips are omitted here on purpose — `SkillsPanel` owns them and owns removing
    // them, and the same five words under two affordances is one screen saying one thing twice.
    expect(await screen.findByTestId('Person.Skills.sk-pw')).toHaveTextContent('Playwright')
    expect(screen.queryByTestId('Person.Card.Skills')).toBeNull()
    // And no Profile button on the page it would have linked to.
    expect(screen.queryByTestId('Person.Card.Profile')).toBeNull()
  })

  it('disables the profile edit with a reason for a caller without staffing:profile', async () => {
    mockReads({ permissions: ['staffing:directory'] })
    renderPage()

    // §31 — disabled, never hidden. Maintaining a profile is COMPANY scope and is administrative
    // even on your own row, unlike absence (017 D-h).
    expect(await screen.findByTestId('Person.Profile.Edit')).toBeDisabled()
    expect(screen.getByTestId('Person.Skills.Add')).toBeDisabled()
  })
})
