/**
 * The seam's own tests: it reports what it should, stays quiet on a body the server could send, and
 * still covers the suite it was installed for.
 *
 * ## Why a seam needs a vacuity guard more than a check does
 *
 * `contractDoubles.ts` is invisible when it is working — a green suite is what both "every body is
 * correct" and "nothing was ever compared" look like. Three things would turn it off silently: the
 * spec failing to load through the `?raw` import, `vi.spyOn` being replaced by some other way of
 * building a double, or the wrapper losing track of the `api` object. The first two are pinned here
 * by measurement rather than by argument; the third is what `reports an incomplete body` covers, by
 * driving a real spy through a real `api.get`.
 *
 * The population guard is the one that will fail first, and it is meant to: the day the suite stops
 * mocking through `vi.spyOn(api, …)`, this seam covers nothing and says nothing, and a count read
 * off the tree is the only thing that can notice.
 */
import { describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { contractPathOf, declaredPathCount, responseSchemaOf, violationsOf } from '../api/contractResponses'
import { drainServedViolations, servingADriftedBody } from './contractDoubles'
import { aProject } from './fixtures'

/**
 * Every test file, as source, read through Vite rather than `node:fs` — the same reason
 * `contractResponses.ts` reads the spec that way: this file is compiled by `tsconfig.app.json`,
 * which must not have `types: ["node"]`, and `import.meta.glob` needs neither.
 */
const testSources = import.meta.glob('../**/*.test.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

describe('the contract-doubles seam', () => {
  it('loaded the contract it compares against', () => {
    expect(
      declaredPathCount(),
      'openapi.yaml parsed to almost no paths — the `?raw` import or `server.fs.allow` is broken, '
        + 'and every body this seam checks would resolve to no operation',
    ).toBeGreaterThan(80)

    expect(contractPathOf('/projects/p1/settings')).toBe('/projects/{projectId}/settings')
    // The ordering rule, pinned: a URL that two declared paths could accept goes to the one that
    // leaves fewer segments free, or `/projects/{projectId}` would swallow every sub-resource.
    expect(contractPathOf('/projects/p1')).toBe('/projects/{projectId}')
    expect(contractPathOf('/projects/p1/cases?page=2')).toBe('/projects/{projectId}/cases')
    expect(contractPathOf('/nothing-declared')).toBeUndefined()
  })

  it('still covers the suite it was installed for', () => {
    const files = Object.entries(testSources)
    expect(
      files.length,
      'no test sources found — the glob is relative to this file and the suite moved',
    ).toBeGreaterThan(80)

    const mocking = files.filter(([, source]) => source.includes("vi.spyOn(api, '"))
    expect(
      mocking.length,
      'almost no test file builds a double with `vi.spyOn(api, …)` any more. This seam only sees '
        + 'doubles made that way, so it now covers nothing while reporting nothing. Teach '
        + 'contractDoubles.ts about whatever replaced it.',
    ).toBeGreaterThanOrEqual(45)
  })

  it('reports an incomplete body, naming the field and the operation', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ id: 'p1', key: 'PRJ', name: 'Banking' })

    await api.get('/projects/p1')

    const served = drainServedViolations()
    expect(served.map((violation) => violation.message)).toEqual([
      '$.archived — required by the contract, absent from the body this double served',
      '$.caseCount — required by the contract, absent from the body this double served',
      '$.myRole — required by the contract, absent from the body this double served',
    ])
    expect(served.every((violation) => violation.url === '/projects/{projectId}')).toBe(true)
  })

  /**
   * The half of the rule that a missing-field check alone would not have: a body carrying a field
   * the contract does not declare. Nine files served the *project* for `/projects/{id}/settings`,
   * and it is this question rather than the required one that says what happened — the missing
   * fields alone read as an incomplete fixture, `id` and `name` arriving as well say it is the
   * wrong shape entirely.
   */
  it('reports a property the contract does not declare', async () => {
    vi.spyOn(api, 'get').mockResolvedValue(aProject())

    await api.get('/projects/p1/settings')

    expect(drainServedViolations().map((violation) => violation.message)).toContain(
      '$.id — served by this double, declared nowhere in the contract',
    )
  })

  it('says nothing about a body the server could send', async () => {
    vi.spyOn(api, 'get').mockResolvedValue(aProject())

    await api.get('/projects/p1')

    expect(drainServedViolations()).toEqual([])
  })

  it('reports a URL that resolves to no operation at all', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ anything: true })

    await api.get('/projects/p1/nothing-declared')

    expect(drainServedViolations()[0]?.message).toContain('resolves to no path in openapi.yaml')
  })

  it('hands the caller back exactly what the double resolved with', async () => {
    const body = aProject({ name: 'Banking' })
    const get = vi.spyOn(api, 'get').mockResolvedValue(body)

    expect(await api.get('/projects/p1')).toBe(body)
    expect(get).toHaveBeenCalledWith('/projects/p1')
  })

  it('drives a per-path implementation, and reports the body that path served', async () => {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path.startsWith('/projects/p1/settings')) return { projectId: 'p1' }
      return aProject()
    }) as never)

    expect(await api.get<{ id: string }>('/projects/p1')).toEqual(aProject())
    await api.get('/projects/p1/settings')

    const served = drainServedViolations()
    expect(served).not.toHaveLength(0)
    expect(served.every((violation) => violation.url === '/projects/{projectId}/settings')).toBe(true)
  })

  it('is suspended for a test whose subject is a drifted body', async () => {
    servingADriftedBody()
    vi.spyOn(api, 'get').mockResolvedValue({ nothing: 'the server would send' })

    await api.get('/projects/p1')

    expect(drainServedViolations()).toEqual([])
  })
})

describe('the comparison itself', () => {
  const project = responseSchemaOf('get', '/projects/{projectId}')

  it('reads required through an allOf rather than off the first branch', () => {
    // The list response is `allOf: [PageMeta, { required: [items, facets] }]`, and a reader that
    // took only the first branch would report `items` as undeclared and miss `page` entirely.
    const cases = responseSchemaOf('get', '/projects/{projectId}/cases')
    expect(cases).toBeDefined()
    const missing = violationsOf(cases as Record<string, unknown>, { items: [] }).map((v) => v.where)
    expect(missing).toContain('$.facets')
    expect(missing).toContain('$.page')
    expect(missing).not.toContain('$.items')
  })

  it('says nothing about a null where the contract allows one', () => {
    expect(violationsOf(project as Record<string, unknown>, aProject({ myRole: null }))).toEqual([])
  })

  it('walks into arrays and names the element that is wrong', () => {
    const suites = responseSchemaOf('get', '/projects/{projectId}/suites')
    const found = violationsOf(suites as Record<string, unknown>, [{ id: 's1', key: 'TS-1', name: 'A' }])
    expect(found.map((violation) => violation.where)).toContain('$[0].caseCount')
  })

  it('leaves a string map alone', () => {
    // `Configuration.attributes` is `additionalProperties`, so it declares no property names and
    // the undeclared-property question does not apply to it.
    const run = responseSchemaOf('post', '/projects/{projectId}/runs')
    expect(run).toBeDefined()
    expect(violationsOf(run as Record<string, unknown>, { counts: {} })
      .filter((violation) => violation.where.startsWith('$.configuration.attributes')))
      .toEqual([])
  })
})
