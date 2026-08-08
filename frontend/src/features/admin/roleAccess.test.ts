import { describe, expect, it } from 'vitest'
import type { Role, RoleAssignment, Team } from '../../api/types'
import { isInherited, isOverriddenHere, namesProject, overriddenUserIds } from './roleAccess'

/**
 * The client's second implementation of roles spec §A1.1, and the reason it is tested at this
 * level rather than through the page.
 *
 * The rule — *a grant naming the project replaces the company-wide one outright, even when it
 * confers less* — is arithmetic, and every way of getting it wrong produces a page that renders
 * perfectly and says something false. Two of the three properties below cannot be seen by looking
 * at the screen at all:
 *
 *   - a **union** instead of a replace shows both roles and reads as generosity;
 *   - a check written over **what a grant confers** rather than whether it exists undoes every
 *     restriction, and a role granting nothing fails to override at all;
 *   - forgetting **team grants** answers with a plausible list that silently omits everybody
 *     staffed through the org chart — §54's defect, exactly.
 */

const PROJECT = 'p-1'
const OTHER = 'p-2'

const role = (scope: Role['scope'], name: string): Role => ({
  id: `role-${name}`,
  name: name as Role['name'],
  scope,
  builtIn: true,
  assignable: true,
  description: '',
  permissions: [],
})

const TEST_LEAD = role('PROJECT', 'Test Lead')
const VIEWER = role('PROJECT', 'Viewer')
const COMPANY_ADMIN = role('COMPANY', 'Company Admin')

const person = (id: string) => ({ id, displayName: id, email: `${id}@x.test`, avatarUrl: null })

const grant = (
  id: string,
  r: Role,
  subject: { user?: string; team?: { id: string; name: string } },
  projectId: string | null,
): RoleAssignment => ({
  id,
  role: r,
  user: subject.user ? person(subject.user) : null,
  team: subject.team ?? null,
  projectId,
  projectName: projectId,
  grantedAt: '2026-01-01T00:00:00Z',
})

const squad: Team = {
  id: 't-1',
  parentTeamId: null,
  name: 'Payments Squad',
  description: null,
  leads: [],
  members: [person('ana'), person('marco')],
  projects: [],
}

describe('roleAccess', () => {
  it('separates a grant naming the project from one naming none', () => {
    expect(namesProject(grant('g1', VIEWER, { user: 'ana' }, PROJECT), PROJECT)).toBe(true)
    expect(namesProject(grant('g2', VIEWER, { user: 'ana' }, OTHER), PROJECT)).toBe(false)
    expect(namesProject(grant('g3', VIEWER, { user: 'ana' }, null), PROJECT)).toBe(false)
  })

  it('treats only a PROJECT-scope grant with no project as inherited', () => {
    // The distinction `projectId === null` alone cannot make, and the reason the backend split its
    // own queries on `role.scope`: both populations live under a null project id with opposite
    // override semantics.
    expect(isInherited(grant('g1', TEST_LEAD, { user: 'ana' }, null))).toBe(true)
    expect(isInherited(grant('g2', COMPANY_ADMIN, { user: 'ana' }, null))).toBe(false)
    expect(isInherited(grant('g3', TEST_LEAD, { user: 'ana' }, PROJECT))).toBe(false)
  })

  it('overrides a person staffed through a team, not just one granted directly', () => {
    // §54's defect made an assertion: a set built from user grants alone omits Ana and Marco and
    // still looks like an answer.
    const grants = [grant('g1', TEST_LEAD, { team: { id: 't-1', name: 'Payments Squad' } }, PROJECT)]

    expect([...overriddenUserIds(grants, [squad], PROJECT)].sort()).toEqual(['ana', 'marco'])
  })

  it('ignores a team grant that names a different project', () => {
    const grants = [grant('g1', TEST_LEAD, { team: { id: 't-1', name: 'Payments Squad' } }, OTHER)]

    expect(overriddenUserIds(grants, [squad], PROJECT).size).toBe(0)
  })

  it('overrides on the existence of a project grant, never on what it confers', () => {
    // Viewer confers strictly less than the inherited Test Lead, and it still wins. A check that
    // compared permissions — or that asked "do they have project permissions" — would union the
    // two here and silently keep the Test Lead alive.
    const inherited = grant('g1', TEST_LEAD, { user: 'marco' }, null)
    const here = grant('g2', VIEWER, { user: 'marco' }, PROJECT)
    const all = [inherited, here]
    const overridden = overriddenUserIds(all, [], PROJECT)

    expect(isOverriddenHere(inherited, overridden, all, PROJECT)).toBe(true)
  })

  it('leaves an inherited grant live on a project that granted that person nothing', () => {
    const inherited = grant('g1', TEST_LEAD, { user: 'marco' }, null)
    const elsewhere = grant('g2', VIEWER, { user: 'marco' }, OTHER)
    const all = [inherited, elsewhere]

    expect(isOverriddenHere(inherited, overriddenUserIds(all, [], PROJECT), all, PROJECT)).toBe(
      false,
    )
  })

  it('never marks a COMPANY-scope grant as replaced, even for an overridden person', () => {
    /*
      §A1.1 exemption 2, and the privilege inversion it exists to stop: without it, anyone holding
      `project:members` could grant the company's administrator Viewer on a project and have the
      screen agree that their Company Admin had been replaced. Tom is genuinely in the overridden
      set here — the assertion is that membership of that set is not enough.
    */
    const admin = grant('g1', COMPANY_ADMIN, { user: 'tom' }, null)
    const demotion = grant('g2', VIEWER, { user: 'tom' }, PROJECT)
    const all = [admin, demotion]
    const overridden = overriddenUserIds(all, [], PROJECT)

    expect(overridden.has('tom')).toBe(true)
    expect(isOverriddenHere(admin, overridden, all, PROJECT)).toBe(false)
  })

  it('marks an inherited team grant only when that team is granted on the project', () => {
    const inherited = grant('g1', TEST_LEAD, { team: { id: 't-1', name: 'Payments Squad' } }, null)
    const hereSameTeam = grant('g2', VIEWER, { team: { id: 't-1', name: 'Payments Squad' } }, PROJECT)
    const hereOtherTeam = grant('g3', VIEWER, { team: { id: 't-9', name: 'Other' } }, PROJECT)

    const withSame = [inherited, hereSameTeam]
    expect(
      isOverriddenHere(inherited, overriddenUserIds(withSame, [squad], PROJECT), withSame, PROJECT),
    ).toBe(true)

    // A different team's grant covers some of these people and not others — "partly replaced" is a
    // verdict the row has no honest way to render, so it is not claimed.
    const withOther = [inherited, hereOtherTeam]
    expect(
      isOverriddenHere(inherited, overriddenUserIds(withOther, [squad], PROJECT), withOther, PROJECT),
    ).toBe(false)
  })
})
