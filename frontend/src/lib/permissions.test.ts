import { describe, expect, it } from 'vitest'
import type { PermissionKey } from '../api/types'
import {
  allPermissions,
  areaLabels,
  groupByArea,
  holdsEveryPermission,
  permissionAreas,
  permissionLabels,
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
