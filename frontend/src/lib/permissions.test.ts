import { describe, expect, it } from 'vitest'

import matrixText from '../../../docs/specs/permission-matrix-summary.md?raw'
import type { PermissionKey } from '../api/types'
import {
  ROLE_NAMES,
  allPermissions,
  areaLabels,
  askableHoldersOf,
  disabledReason,
  groupByArea,
  holdersOf,
  holdsEveryPermission,
  listOfRoles,
  permissionAreas,
  permissionLabels,
  type RoleName,
} from './permissions'

/**
 * The permission catalogue.
 *
 * `satisfies Record<PermissionKey, …>` makes a *missing* entry a compile error, so this file
 * does not re-assert what the type system already enforces. What it asserts is the two things
 * the compiler cannot see:
 *
 *   1. **The derived claims.** `holdsEveryPermission` replaced a `'*'` sentinel (§6 lesson 10),
 *      and the whole point of the replacement is that it must *stop* returning true when the
 *      catalogue grows past a role's grant. That is behaviour, not a type.
 *   2. **The labels are usable in the sentence they are dropped into.** `useCan` builds
 *      "You don't have permission to ${label} in this project." A label that is a raw key, or
 *      that starts with a capital, or that ends in a full stop, typechecks and reads as a bug.
 */

describe('the catalogue is complete and mechanically checked', () => {
  it('has an area and a label for every permission, derived rather than asserted', () => {
    // §6 lesson 9: a catalogue-wide invariant needs a computed check, not a careful reading.
    for (const permission of allPermissions) {
      expect(permissionAreas[permission], `area for ${permission}`).toBeDefined()
      expect(permissionLabels[permission], `label for ${permission}`).toBeDefined()
    }
    expect(Object.keys(permissionLabels).sort()).toEqual([...allPermissions].sort())
  })

  it('has a display label for every area actually used', () => {
    const areasInUse = new Set(Object.values(permissionAreas))
    for (const area of areasInUse) {
      expect(areaLabels[area], `label for area ${area}`).toBeTruthy()
    }
  })

  it('contains no wildcard permission — there is no such thing in the model', () => {
    // The `'*'` sentinel this module exists to have removed (plan §6b: code checks permissions,
    // never role names, and there is no wildcard).
    expect(allPermissions).not.toContain('*')
    expect(allPermissions.every((p) => /^[a-z]+:[a-z]+$/.test(p))).toBe(true)
  })
})

describe('permissionLabels read as verb phrases in the sentence that uses them', () => {
  const sentence = (label: string) => `You don't have permission to ${label} in this project.`

  it('never leaks a raw permission key into the tooltip', () => {
    // "You don't have permission to library:manage" tells a person who already knows the
    // catalogue what they knew, and everyone else nothing.
    for (const permission of allPermissions) {
      expect(permissionLabels[permission], permission).not.toContain(':')
    }
  })

  it('starts lowercase and carries no terminal punctuation, so the sentence composes', () => {
    for (const permission of allPermissions) {
      const label = permissionLabels[permission]
      expect(label, permission).not.toMatch(/^[A-Z]/)
      expect(label, permission).not.toMatch(/[.!?]$/)
      expect(sentence(label), permission).not.toMatch(/\s{2,}/)
    }
  })

  it('gives every permission a distinct label, so two denials do not read identically', () => {
    const labels = Object.values(permissionLabels)
    expect(new Set(labels).size).toBe(labels.length)
  })
})

describe('holdsEveryPermission — derived, never a sentinel', () => {
  it('is true only for the whole catalogue', () => {
    expect(holdsEveryPermission(allPermissions)).toBe(true)
  })

  /*
    The exact regression the sentinel could not express. Drop ONE permission and the claim must
    collapse — a `'*'` in the array could not tell "holds everything" from "holds a permission
    that happens to be present".
  */
  it('goes false the moment a single permission is missing', () => {
    for (const omitted of ['case:read', 'instance:audit', 'suite:delete'] as PermissionKey[]) {
      const almost = allPermissions.filter((p) => p !== omitted)
      expect(holdsEveryPermission(almost), `without ${omitted}`).toBe(false)
    }
  })

  it('is false for an empty grant and unmoved by unknown extras', () => {
    expect(holdsEveryPermission([])).toBe(false)
    // A future permission the SPA has not been rebuilt for must not make an incomplete grant
    // read as complete.
    expect(holdsEveryPermission([...allPermissions.slice(1), 'future:thing' as PermissionKey])).toBe(false)
  })
})

describe('groupByArea', () => {
  it('skips areas the role holds nothing in, rather than rendering empty headings', () => {
    const groups = groupByArea(['case:read', 'case:edit', 'report:view'])
    expect(groups.map((g) => g.area)).toEqual(['repository', 'reporting'])
    expect(groups[0]?.permissions).toEqual(['case:read', 'case:edit'])
  })

  it('lists permissions in catalogue order, not in the order the server sent them', () => {
    // The server sorts its answer; the screen must still read in a stable, curated order, so a
    // role page does not reshuffle between loads.
    const shuffled: PermissionKey[] = ['case:edit', 'suite:manage', 'case:read']
    const groups = groupByArea(shuffled)
    expect(groups[0]?.permissions).toEqual(['suite:manage', 'case:read', 'case:edit'])
  })

  it('returns nothing for an empty grant', () => {
    expect(groupByArea([])).toEqual([])
  })

  it('ignores a permission that is not in the catalogue', () => {
    expect(groupByArea(['nonsense:key' as PermissionKey])).toEqual([])
  })

  it('accounts for every held permission exactly once across the groups', () => {
    const held = allPermissions
    const flattened = groupByArea(held).flatMap((g) => g.permissions)
    expect(flattened).toHaveLength(held.length)
    expect(new Set(flattened).size).toBe(held.length)
  })
})

/**
 * `permissionHolders` against the document it transcribes — the third link off roles spec §A3.
 *
 * The chain the matrix file's own header describes is
 * `migration → SeedDataTest → spec → the rules in this package`, and
 * `packages/client/src/test/matrixCopies.test.ts` is the link that compares §A3 to the two prose
 * copies of it. This is the same shape one stack over: a **third** copy of the matrix now exists,
 * in TypeScript, because a browser cannot read a markdown file — see `permissionHolders`' header
 * for why it is a copy rather than a read — and a copy nobody compares reads exactly like
 * agreement.
 *
 * It parses `docs/specs/permission-matrix-summary.md` rather than §A3 itself, deliberately. §A3 is
 * split across `#### ` sections with abbreviated column headings; the summary is one section with
 * the roles spelled out, it is already compared to §A3 cell by cell, both ways, by
 * `matrixCopies.test.ts` — and this rule failing for the *other* reason, because a prose copy
 * drifted, would point the reader at the wrong repair. So this compares a copy to a copy that is
 * itself pinned, and the two rules together bind this table to the spec.
 *
 * Read through Vite's `?raw` rather than `node:fs`, the seam `lib/caseDefaults.test.ts` uses and
 * for its reason: a `node:fs` read needs `tsconfig.node.json`, which cannot compile a file that
 * imports application source — and this one imports the table it is checking.
 *
 * **A cell is read as held or not held, and a qualifier is held.** §A3 writes some grants as a
 * narrowing phrase (`quarantine only`) rather than a `✓`, and the question this table answers —
 * who could do this for me — is answered yes by a narrowed grant. The qualifier itself is
 * deliberately not carried; see the table's header.
 */
describe('permissionHolders is roles spec §A3', () => {
  const MATRIX = 'docs/specs/permission-matrix-summary.md'

  /** §A3's spelling of "not granted". Everything else in a cell is a grant, narrowed or not. */
  const NOT_GRANTED = '—'
  const KEY = /`([a-z]+:[a-z]+)`/g

  /**
   * The matrix section, bounded by its own heading depth — `matrixCopies.test.ts`' `matrixSection`,
   * which is the parse this rule is deliberately a sibling of rather than a second invention.
   */
  function matrixSection(): string[] {
    const heading = /^(#{1,6}) Permission matrix — convenience copy\s*$/
    const lines = matrixText.split('\n')
    const start = lines.findIndex((line) => heading.test(line))
    expect(
      start,
      `${MATRIX} has no "Permission matrix — convenience copy" heading, so this rule read no `
        + 'matrix at all and would pass on an empty comparison. The section was renamed — follow '
        + 'it here.',
    ).toBeGreaterThanOrEqual(0)
    const depth = (heading.exec(lines[start] as string)?.[1] ?? '#').length
    const rest = lines.slice(start + 1)
    const end = rest.findIndex((line) => {
      const next = /^(#{1,6}) /.exec(line)
      return next !== null && (next[1] as string).length <= depth
    })
    return end < 0 ? rest : rest.slice(0, end)
  }

  /** Every key the summary prints, mapped to the roles whose cell is not `—`. */
  function specHolders(): Map<string, string[]> {
    const section = matrixSection()
    const holders = new Map<string, string[]>()

    let headings: string[] = []
    for (const line of section) {
      if (!line.startsWith('|')) {
        headings = []
        continue
      }
      const cells = line.split('|').slice(1, -1).map((cell) => cell.trim())
      if (cells[0] === 'Permission') {
        headings = cells
        continue
      }
      if (headings.length === 0 || cells.every((cell) => /^:?-+:?$/.test(cell))) continue

      const held = headings
        .map((heading, column) => ({ heading, cell: cells[column] ?? '' }))
        .filter(({ cell }, column) => column > 0 && cell !== NOT_GRANTED)
        .map(({ heading }) => heading)
      for (const match of (cells[0] ?? '').matchAll(KEY)) holders.set(match[1] as string, held)
    }

    /*
      The `SYSTEM` scope is a sentence rather than a table — seven keys held by one role would be a
      three-column grid of ✓ — so it is declared here rather than excused, exactly as
      `matrixCopies.test.ts` declares it. Leaving it out would leave seven catalogue keys with no
      comparison at all, which is the failure mode this whole file is about.
    */
    const sentence = /`SYSTEM` scope, Instance Admin only:([^.]*)\./.exec(section.join('\n'))?.[1]
    expect(
      sentence,
      `${MATRIX} no longer states the SYSTEM scope as a sentence, which is where this rule reads `
        + 'the seven keys that copy writes as prose. If it became a table, drop this arm — the '
        + 'table parse above already covers it.',
    ).toBeDefined()
    for (const match of (sentence ?? '').matchAll(KEY)) {
      holders.set(match[1] as string, ['Instance Admin'])
    }

    return holders
  }

  it('records the same holders as the matrix, for every permission, both ways', () => {
    const spec = specHolders()

    const missing = allPermissions.filter((key) => !spec.has(key)).sort()
    const extra = [...spec.keys()].filter((key) => !(allPermissions as string[]).includes(key)).sort()

    expect(
      missing.join(', '),
      `${missing.length} catalogue permissions the matrix does not print, so this rule compared `
        + `nothing for them: ${missing.join(', ')}`,
    ).toBe('')
    expect(
      extra.join(', '),
      `${extra.length} permissions the matrix prints that this catalogue does not declare: `
        + `${extra.join(', ')}. The contract's union is authoritative for the key SET.`,
    ).toBe('')

    const disagreements: string[] = []
    for (const permission of allPermissions) {
      const declared = (spec.get(permission) ?? []).join(', ')
      const transcribed = holdersOf(permission).join(', ')
      if (declared === transcribed) continue
      disagreements.push(`  ${permission}: §A3 says [${declared}] and permissionHolders says [${transcribed}]`)
    }

    expect(
      disagreements.join('\n'),
      `${disagreements.length} permissions where this file's transcription of §A3 and the matrix `
        + `disagree:\n\n${disagreements.join('\n')}\n\nThe spec wins. If the grant genuinely `
        + 'moved, it moved in a migration, and `SeedDataTest.theSeededMatrixIsTheSpecsMatrix` is '
        + 'what settles the spec against the rows Postgres holds. The ORDER matters too — it is '
        + '§A3\'s column order, least-permissioned first, which is what makes the first name in a '
        + '"held by" sentence the cheapest grant that would unblock the reader.',
    ).toBe('')
  })

  it('names every role in the list, so a new column cannot be silently uncompared', () => {
    const named = new Set<string>()
    for (const permission of allPermissions) for (const role of holdersOf(permission)) named.add(role)

    /*
      Both ways. A role in ROLE_NAMES that holds nothing would be a column this rule never reads,
      and a holder outside ROLE_NAMES would not typecheck — but the second half is asserted anyway
      because `satisfies` is checked at build time and this suite is what runs on a push.
    */
    expect([...named].sort()).toEqual([...ROLE_NAMES].sort())
  })
})

/**
 * The one disabled-reason sentence (021 §8.2).
 *
 * Its shape is a product decision, so it is asserted rather than left to whoever next edits the
 * template: the capability first, then who to ask, one sentence, one full stop.
 */
describe('disabledReason', () => {
  it('names the capability and who holds it, in one sentence', () => {
    expect(disabledReason('case:approve')).toBe(
      'Needs Approve test cases, held by Test Lead and Company Admin.',
    )
  })

  it('drops Instance Admin from a company-internal ask', () => {
    // §A3 has four holders; the reader can act on three of them.
    expect(disabledReason('issue:import')).toBe(
      'Needs Import issues from another tracker, held by Project Manager, Test Lead and Company Admin.',
    )
    expect(askableHoldersOf('issue:import')).not.toContain('Instance Admin')
  })

  it('keeps Instance Admin when it is the only holder, rather than saying nobody', () => {
    expect(askableHoldersOf('system:settings')).toEqual(['Instance Admin'])
    expect(disabledReason('system:settings')).toBe(
      'Needs Change instance settings, held by Instance Admin.',
    )
  })

  it.each(allPermissions)('%s reads as a whole sentence', (permission) => {
    const sentence = disabledReason(permission)
    expect(sentence.startsWith('Needs ')).toBe(true)
    expect(sentence.endsWith('.')).toBe(true)
    // The failure this catches is a label or a holder list arriving as `undefined` inside the
    // template, which is a string and would otherwise render on a real tooltip.
    expect(sentence).not.toContain('undefined')
    expect(sentence).not.toContain('held by nobody')
  })
})

describe('listOfRoles', () => {
  it.each([
    [[], 'nobody'],
    [['Test Lead'], 'Test Lead'],
    [['Test Lead', 'Company Admin'], 'Test Lead and Company Admin'],
    [
      ['Project Manager', 'Test Lead', 'Company Admin'],
      'Project Manager, Test Lead and Company Admin',
    ],
  ] as const)('spells %j as %s', (roles, expected) => {
    expect(listOfRoles(roles as unknown as RoleName[])).toBe(expected)
  })
})
