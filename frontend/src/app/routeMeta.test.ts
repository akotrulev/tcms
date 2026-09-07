import { describe, expect, it } from 'vitest'

import routerSource from './router.tsx?raw'
import { ROUTE_META, crumbsFor, type Crumb } from './routeMeta'

/**
 * `routeMeta.ts` against `router.tsx` — the rule that makes D-g's map as safe as route metadata.
 *
 * The proposal puts the trail on the route (`handle: { crumbs }`), where a route without one is
 * visible at the point somebody adds it. A map keyed by path pattern has no such property on its
 * own: a new `<Route>` lands in one file and the trail is silently absent in another, and the only
 * symptom is a header that goes blank on one screen. **This is that property, bought back.** It
 * reads `router.tsx`'s source, extracts every `path="…"`, and compares the two sets both ways.
 *
 * Reading the source rather than importing the router is deliberate. Importing it would mount
 * React, `lazy()` every screen, and answer a question about text with a render — and the thing
 * being asserted IS the text, because the map is keyed by the literal a human typed in each place.
 *
 * Read through Vite's `?raw` rather than `node:fs`, which is the seam `lib/caseDefaults.test.ts`
 * and `api/contractBodies.ts` use and for their reason: a `node:fs` read needs
 * `tsconfig.node.json`, and that config cannot compile a file that imports application source —
 * which this one does, because the map it is checking is application source.
 */

/**
 * The two catch-alls, which are patterns rather than places.
 *
 * `path="*"` is declared twice — once inside the shell for an unmatched authenticated URL and once
 * outside it for an unmatched public one — and neither is a destination with a trail. A crumb map
 * entry for `*` would also be a live hazard rather than dead weight: `matchPath('*', …)` matches
 * every location, so it would answer for routes that have their own.
 */
const NOT_PLACES = new Set(['*'])

function declaredPaths(): Set<string> {
  const paths = [...routerSource.matchAll(/\bpath="([^"]+)"/g)].map((match) => match[1] as string)
  expect(
    paths.length,
    'No `path="…"` found in router.tsx, so this rule compared an empty set and would pass on any '
      + 'crumb map at all. The routes moved or changed shape — follow them here.',
  ).toBeGreaterThan(50)
  return new Set(paths.filter((path) => !NOT_PLACES.has(path)))
}

describe('every route has a breadcrumb trail', () => {
  it('declares exactly the routes router.tsx declares, both ways', () => {
    const declared = declaredPaths()
    const mapped = new Set(Object.keys(ROUTE_META))

    const missing = [...declared].filter((path) => !mapped.has(path)).sort()
    const extra = [...mapped].filter((path) => !declared.has(path)).sort()

    expect(
      missing.join('\n'),
      `${missing.length} routes with no breadcrumb trail. The header would render nothing above `
        + 'these screens, which is the failure `handle: { crumbs }` on the route would have made '
        + `impossible — add each to ROUTE_META (\`none\` if it is chromeless):\n${missing.join('\n')}`,
    ).toBe('')

    expect(
      extra.join('\n'),
      `${extra.length} trails for routes that no longer exist. A stale pattern is not inert: it is `
        + 'tried against every location, and a pattern left behind by a rename can match a URL its '
        + `successor was meant to answer:\n${extra.join('\n')}`,
    ).toBe('')
  })

  it('resolves a static segment before a dynamic one that would also match', () => {
    /*
      The ordering hazard `crumbsFor` ranks around: `new` is a legal value for `:caseId`, so both
      patterns match this URL and reading the map in declaration order would send the New case
      screen the trail of a case detail. Asserted rather than trusted, because the map is an object
      literal and object literals get sorted.
    */
    const trail = crumbsFor('/projects/p1/cases/new', { projectKey: 'ACME' })
    expect(trail.at(-1)?.label).toBe('New case')

    expect(crumbsFor('/projects/p1/cases/c9', { projectKey: 'ACME' }).at(-1)?.label).toBe('Case')
    expect(crumbsFor('/projects/p1/releases/new', {}).at(-1)?.label).toBe('New release')
    expect(crumbsFor('/projects/p1/imports/new', {}).at(-1)?.label).toBe('Start an import')
  })

  it('leads every project trail with Projects and the project key', () => {
    const trail = crumbsFor('/projects/p1/work', { projectKey: 'ACME', projectName: 'Acme Payments' })

    expect(trail.map((crumb: Crumb) => crumb.label)).toEqual(['Projects', 'ACME', 'Work'])
    expect(trail[0]?.to).toBe('/')
    expect(trail[1]?.to).toBe('/projects/p1')
    // The name is the hover, so a two-letter key is not the only thing on screen naming the frame.
    expect(trail[1]?.title).toBe('Acme Payments')
  })

  it('falls back to the name, then to a word, while the project read is in flight', () => {
    expect(crumbsFor('/projects/p1/work', { projectName: 'Acme' })[1]?.label).toBe('Acme')
    // Never the raw id: a UUID in a breadcrumb is worse than the word it replaces.
    expect(crumbsFor('/projects/p1/work', {})[1]?.label).toBe('Project')
  })

  it('never marks a middle crumb as a destination it cannot reach', () => {
    // `Company` and `Account` are grouping segments — real, and not pages. A crumb with a `to`
    // that goes nowhere is the defect this asserts against.
    const trail = crumbsFor('/admin/tokens', {})
    expect(trail.map((crumb: Crumb) => crumb.label)).toEqual(['Company', 'Administration', 'API tokens'])
    expect(trail.every((crumb: Crumb) => crumb.to === undefined)).toBe(true)
  })

  it('names an issue by its key, which is the one identifier the URL carries', () => {
    expect(crumbsFor('/issues/ACME-118', {})).toEqual([{ label: 'ACME-118' }])
  })

  it('gives the three public routes no trail, and an unmatched URL none either', () => {
    expect(crumbsFor('/login', {})).toEqual([])
    expect(crumbsFor('/accept-invitation', {})).toEqual([])
    expect(crumbsFor('/sso/callback', {})).toEqual([])
    expect(crumbsFor('/nothing/here/at/all', {})).toEqual([])
  })

  it('files every settings section under Settings, including the three outside /settings/', () => {
    // §5's "no route moves": `Imports`, `Access & roles` and `Project audit` predate the prefix.
    for (const [path, label] of [
      ['/projects/p1/settings/general', 'General'],
      ['/projects/p1/imports', 'Imports'],
      ['/projects/p1/access', 'Access & roles'],
      ['/projects/p1/audit', 'Project audit'],
    ] as const) {
      const trail = crumbsFor(path, { projectKey: 'ACME' })
      expect(trail.map((crumb: Crumb) => crumb.label), path).toEqual([
        'Projects',
        'ACME',
        'Settings',
        label,
      ])
    }
  })
})
