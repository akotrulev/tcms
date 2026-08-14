import type { EffectivePermissions, PermissionKey } from '../api/types'

/**
 * A `GET /me/permissions` body, for tests that mock `api.get`.
 *
 * ## Why this is shared rather than written per file
 *
 * Six test files had their own copy of this literal, and §65 — which changed `permissions` from a
 * list of keys to a list of `{ key, qualifier }` — broke five of them **without a single type
 * error**, because every one of those mocks casts through `as never` to satisfy `vi.spyOn`. The
 * cast is necessary (the real `api.get` is generic over the response) and it is also exactly what
 * hid the change: the failures surfaced as `pointer-events: none` on a button three assertions
 * later, which reads as a UI defect rather than as a stale fixture.
 *
 * So the shape lives here once. A future change to the response is a compile error in this file
 * and nowhere else, which is the only place it can be fixed anyway.
 *
 * **Which is what the `as EffectivePermissions` on the return removed, and §196 deleted.** The
 * paragraph above describes a compile error the cast could not produce: it told the compiler the
 * object already was the response, so the one file this shape was centralised into was the one file
 * where the contract did not bind. `keys` is `PermissionKey[]` for the same reason — a misspelled
 * key used to be a `string` that granted nothing, and the failure surfaced as a disabled control.
 *
 * ## Qualifiers
 *
 * Keys are `ALL` unless named in `qualified` — the same list-only-the-exceptions rule the seeded
 * catalogue follows, so a test that does not care about qualifiers reads as though they did not
 * exist. Pass one to drive a restricted grant: `heldPermissions(['artifact:edit'], {
 * 'artifact:edit': 'PLAN_SCOPE' })` is a Test Engineer.
 */
export function heldPermissions(
  keys: PermissionKey[],
  qualified: Partial<Record<PermissionKey, 'OWN' | 'PLAN_SCOPE'>> = {},
  projectId: string | null = 'p1',
): EffectivePermissions {
  return {
    projectId,
    permissions: keys.map((key) => ({
      key,
      qualifier: qualified[key] ?? 'ALL',
    })),
  }
}
