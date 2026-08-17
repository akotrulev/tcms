import { describe, expect, it } from 'vitest'
import { flattenTeamTree } from './teamTree'
import { aTeam } from '../../test/fixtures'

/**
 * The two cases the extraction exists to preserve. A plain descent from the roots passes every
 * healthy-data test here and drops exactly the rows nobody can otherwise fix.
 */
describe('flattenTeamTree', () => {
  it('indents children under their parent and sorts each level by name', () => {
    const rows = flattenTeamTree([
      aTeam({ id: 'c', name: 'Zeta', parentTeamId: 'a' }),
      aTeam({ id: 'b', name: 'Beta' }),
      aTeam({ id: 'a', name: 'Alpha' }),
      aTeam({ id: 'd', name: 'Aardvark', parentTeamId: 'a' }),
    ])

    expect(rows.map((row) => [row.team.name, row.depth])).toEqual([
      ['Alpha', 0],
      ['Aardvark', 1],
      ['Zeta', 1],
      ['Beta', 0],
    ])
  })

  it('surfaces an orphan at the root rather than dropping it', () => {
    const rows = flattenTeamTree([
      aTeam({ id: 'a', name: 'Alpha' }),
      aTeam({ id: 'orphan', name: 'Orphan', parentTeamId: 'deleted-parent' }),
    ])

    expect(rows.map((row) => [row.team.id, row.depth])).toEqual([
      ['a', 0],
      ['orphan', 0],
    ])
  })

  it('surfaces both members of a cycle at the root rather than dropping both', () => {
    const rows = flattenTeamTree([
      aTeam({ id: 'x', name: 'Ex', parentTeamId: 'y' }),
      aTeam({ id: 'y', name: 'Why', parentTeamId: 'x' }),
    ])

    expect(rows.map((row) => [row.team.id, row.depth])).toEqual([
      ['x', 0],
      ['y', 0],
    ])
  })

  it('surfaces a self-parented team at the root', () => {
    expect(flattenTeamTree([aTeam({ id: 's', name: 'Self', parentTeamId: 's' })])).toEqual([
      { team: expect.objectContaining({ id: 's' }), depth: 0 },
    ])
  })

  it('draws the healthy subtree under a team whose sibling is an orphan', () => {
    const rows = flattenTeamTree([
      aTeam({ id: 'root', name: 'Root' }),
      aTeam({ id: 'kid', name: 'Kid', parentTeamId: 'root' }),
      aTeam({ id: 'lost', name: 'Lost', parentTeamId: 'gone' }),
    ])

    expect(rows.map((row) => [row.team.id, row.depth])).toEqual([
      ['lost', 0],
      ['root', 0],
      ['kid', 1],
    ])
  })

  it('is empty for no teams', () => {
    expect(flattenTeamTree([])).toEqual([])
  })
})
