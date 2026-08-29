import { describe, expect, it } from 'vitest'

import { lifecycleVar, severityVar, statusVar } from './statusColors'

/**
 * The three enum→colour maps, checked for the two things their type cannot say.
 *
 * TypeScript already proves the maps are *complete* — `Record<Union, string>` makes a new enum
 * member a compile error until it has a colour, which is why nothing here asserts about keys. What
 * `string` cannot say is that a value is a custom-property *reference*: `var(--color-typo)` and
 * `#ff0000` both typecheck, and the module's header states the rule ("the values are custom-property
 * names, never hex") with nothing enforcing it. A hex here would freeze a status at one palette's
 * colour and survive the theme swap, which is the failure that reads as *nothing is wrong*.
 *
 * **Whether each name is actually declared is checked in `app/tokenSystem.test.ts`**, not here. That
 * needs `index.css` read as text, which needs `node:fs`, which needs `tsconfig.node.json` — and that
 * config cannot compile a file importing application source. So the split follows the configs: this
 * file holds what needs the values, that one holds what needs the stylesheet.
 */

const everyMapping: [map: string, member: string, value: string][] = [
  ...Object.entries(statusVar).map(([member, value]): [string, string, string] => ['statusVar', member, value]),
  ...Object.entries(lifecycleVar).map(([member, value]): [string, string, string] => ['lifecycleVar', member, value]),
  ...Object.entries(severityVar).map(([member, value]): [string, string, string] => ['severityVar', member, value]),
]

describe('statusColors names custom properties, never literals', () => {
  it.each(everyMapping)('%s.%s is a var() reference', (_map, _member, value) => {
    expect(value).toMatch(/^var\(--[a-z0-9-]+\)$/)
  })
})

describe('the three maps stay disjoint where their meanings are', () => {
  /*
    §183's finding, kept red-able: INFO had two colours because two files held the same mapping, and
    the tell was that one severity's value equalled a *different* severity's. A member that borrows
    a sibling's hue makes two claims read as one on screen, so each map's values are asserted
    distinct rather than merely declared.

    Across the maps they are deliberately NOT disjoint: `severityVar.ERROR` is `--color-failed` on
    purpose — a health signal at ERROR is meant to read as the failure colour.
  */
  it('gives each health severity its own hue', () => {
    expect(new Set(Object.values(severityVar)).size).toBe(Object.keys(severityVar).length)
  })

  it('gives each execution outcome its own hue', () => {
    expect(new Set(Object.values(statusVar)).size).toBe(Object.keys(statusVar).length)
  })

  it('gives each lifecycle status its own hue', () => {
    expect(new Set(Object.values(lifecycleVar)).size).toBe(Object.keys(lifecycleVar).length)
  })
})
