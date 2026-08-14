/**
 * The seam's own tests: it reports what it should, stays quiet on a body the server could send — or
 * accept — and still covers the suite it was installed for.
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
import type { Attachment } from '../api/types'
import {
  contractPathOf,
  declaredPathCount,
  requestBodyOf,
  responseSchemaOf,
  violationsOf,
} from '../api/contractBodies'
import { drainServedViolations, servingADriftedBody } from './contractDoubles'
import { aCase, aProject, aRun } from './fixtures'

/**
 * Every test file, as source, read through Vite rather than `node:fs` — the same reason
 * `contractBodies.ts` reads the spec that way: this file is compiled by `tsconfig.app.json`,
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

    // The request half has its own population and it is a third the size, so the read count above
    // would stay comfortable while every write double disappeared. Measured at 36 files (§199).
    const writing = files.filter(([, source]) => /vi\.spyOn\(api, '(post|patch|put)'/.test(source))
    expect(
      writing.length,
      'almost no test file drives a write through `vi.spyOn(api, …)` any more, so nothing compares '
        + 'the payloads this SPA composes against the contract.',
    ).toBeGreaterThanOrEqual(25)
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

/**
 * The other direction (§199): the payload the code under test handed the double, against what the
 * operation says it accepts.
 *
 * Every test here drives a **real** `api.post`/`patch`/`upload` through a real spy, for the reason
 * the read half does: a unit test of `violationsOf` alone would pass with the wrapper unplugged, and
 * the wrapper is the half that can silently stop reading the argument it reads.
 */
describe('the payload the caller sends', () => {
  it('reports a required field the payload leaves out, naming the operation', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(aCase())

    await api.post('/projects/p1/cases', { title: 'A case' })

    const served = drainServedViolations()
    expect(served.every((violation) => violation.direction === 'sent')).toBe(true)
    expect(served.map((violation) => violation.message)).toEqual([
      '$.stepFormat — required by the contract, absent from the payload this call sent',
      '$.priority — required by the contract, absent from the payload this call sent',
      '$.automationStatus — required by the contract, absent from the payload this call sent',
    ])
    expect(served.every((violation) => violation.url === '/projects/{projectId}/cases')).toBe(true)
  })

  it('reports a property the operation does not declare', async () => {
    vi.spyOn(api, 'patch').mockResolvedValue(aCase())

    // `ownerId` IS on `TestCaseInput`; `owner` is the read model's field, which is the mistake a
    // round-trip makes — echo the resource back and half of it is not an input at all.
    await api.patch('/projects/p1/cases/c1', {
      title: 'A case',
      stepFormat: 'STRUCTURED',
      priority: 'MEDIUM',
      automationStatus: 'MANUAL',
      owner: { id: 'u1', displayName: 'A. Tester' },
    })

    expect(drainServedViolations().map((violation) => violation.message)).toEqual([
      '$.owner — sent by this call, declared nowhere in the contract',
    ])
  })

  it('says nothing about a payload the server would accept', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(aCase())

    await api.post('/projects/p1/cases', {
      title: 'A case',
      stepFormat: 'STRUCTURED',
      priority: 'MEDIUM',
      automationStatus: 'MANUAL',
    })

    expect(drainServedViolations()).toEqual([])
  })

  /**
   * The check runs **before** the double does, so the error path of every mutation in the suite is
   * covered too — which is where a payload defect is most likely to hide, because a test asserting a
   * failure asserts nothing about what was sent.
   */
  it('checks the payload even when the double rejects', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(new Error('400'))

    await expect(api.post('/projects/p1/cases', { title: 'A case' })).rejects.toThrow('400')

    expect(drainServedViolations()).not.toHaveLength(0)
  })

  /**
   * `{}` and no argument at all are the same request — `JSON.stringify(body ?? {})` — so the empty
   * one is silence by decision rather than by oversight. `useAcceptProposal` posts `{}` to an
   * operation that declares no body, and telling it to send nothing would name a difference that
   * does not reach the wire.
   */
  it('reports a body sent where the operation declares none, and ignores an empty one', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(aCase())

    await api.post('/projects/p1/ai-proposals/ap1/accept', {})
    expect(drainServedViolations()).toEqual([])

    await api.post('/projects/p1/ai-proposals/ap1/accept', { note: 'why' })
    expect(drainServedViolations().map((violation) => violation.message)).toEqual([
      'the contract declares no request body for that operation, and this call sent one. The '
      + 'server reads none of it.',
    ])
  })

  /**
   * `api.get(path, options)` and `api.del(path, options)` carry `RequestOptions` where the writes
   * carry a body, and a seam that read position 1 without asking which verb it was would compare a
   * timeout budget against a schema — noise on the two most-used verbs in the suite.
   */
  it('does not read a read verb’s options as a payload', async () => {
    vi.spyOn(api, 'get').mockResolvedValue(aProject())
    vi.spyOn(api, 'del').mockResolvedValue(undefined)

    await api.get('/projects/p1', { timeoutMs: 1 })
    await api.del('/projects/p1/cases/c1', { timeoutMs: 1 })

    expect(drainServedViolations()).toEqual([])
  })

  it('leaves a multipart upload alone', async () => {
    const attachment: Attachment = {
      id: 'a1',
      filename: 'shot.png',
      contentType: 'image/png',
      sizeBytes: 12,
      url: '/api/v1/attachments/a1',
      uploadedAt: '2026-01-01T00:00:00Z',
      uploadedBy: null,
    }
    vi.spyOn(api, 'upload').mockResolvedValue(attachment)

    const form = new FormData()
    form.append('file', new Blob(['x']), 'shot.png')
    await api.upload('/projects/p1/cases/c1/attachments', form)

    expect(drainServedViolations()).toEqual([])
  })

  it('is suspended by the same escape hatch the served half uses', async () => {
    servingADriftedBody()
    vi.spyOn(api, 'post').mockResolvedValue(aCase())

    await api.post('/projects/p1/cases', { nothing: 'the server would accept' })

    expect(drainServedViolations()).toEqual([])
  })
})

describe('what an operation declares it accepts', () => {
  it('tells apart JSON, multipart, no body, and no such operation', () => {
    expect(requestBodyOf('post', '/projects/{projectId}/cases').kind).toBe('json')
    expect(requestBodyOf('post', '/projects/{projectId}/cases/{caseId}/attachments').kind).toBe('multipart')
    expect(requestBodyOf('get', '/projects/{projectId}/cases').kind).toBe('none')
    // The path exists and the method does not — `delete` is declared here, `put` is not.
    expect(requestBodyOf('put', '/projects/{projectId}/cases/{caseId}').kind).toBe('no-operation')
  })

  /**
   * A `PATCH` on this API is not a delta, and the required check therefore applies to one. The
   * contract says so on this very operation — Jackson gives absent and null the same Java value, so
   * a partial patch clears every field it omits.
   */
  it('holds a PATCH to the same required set as its POST', () => {
    const patch = requestBodyOf('patch', '/projects/{projectId}/cases/{caseId}')
    expect(patch.kind).toBe('json')
    const found = violationsOf(
      patch.kind === 'json' ? patch.schema : {},
      { title: 'A case' },
      'sent',
    )
    expect(found.map((violation) => violation.where)).toEqual([
      '$.stepFormat',
      '$.priority',
      '$.automationStatus',
    ])
    expect(found[0]?.message).toContain('the payload this call sent')
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

/**
 * The value questions (§198), which the structural pass could not ask.
 *
 * Every body below is built by spreading a factory and overriding one field, so the object handed to
 * `violationsOf` is a plain literal rather than a cast — §196's rule — and the thing being asserted
 * is the only thing that differs from a body the server could send.
 */
describe('the value the double serves', () => {
  const project = responseSchemaOf('get', '/projects/{projectId}') as Record<string, unknown>
  const run = responseSchemaOf('post', '/projects/{projectId}/runs') as Record<string, unknown>
  const summary = responseSchemaOf('get', '/projects/{projectId}/cases/{caseId}') as Record<string, unknown>

  it('reports an enum member the contract does not list', () => {
    // The instance the rung was named for: `status: 'ACTIVE'` is not a `RunStatus`, and it sat in a
    // fixture for four slices because nothing compared a value to anything.
    expect(violationsOf(run, { ...aRun(), status: 'ACTIVE' })).toEqual([
      {
        where: '$.status',
        message: expect.stringContaining('not one of the members the contract lists') as unknown as string,
      },
    ])
  })

  it('reports a null in a field the contract does not mark nullable', () => {
    // Not the required check one field over: `'name' in body` is true, so every structural question
    // this module asks is satisfied by a body that nulls what the server always fills.
    expect(violationsOf(project, { ...aProject(), name: null })).toEqual([
      { where: '$.name', message: expect.stringContaining('null, where the contract declares no') as unknown as string },
    ])
  })

  it('reports a value of the wrong type', () => {
    expect(violationsOf(project, { ...aProject(), caseCount: '4' })).toEqual([
      { where: '$.caseCount', message: 'the string "4", where the contract declares `integer`.' },
    ])
    expect(violationsOf(project, { ...aProject(), archived: 'false' })).toEqual([
      { where: '$.archived', message: 'the string "false", where the contract declares `boolean`.' },
    ])
  })

  /**
   * The hole §197's structural pass had, and the only thing this slice found in the tree: a
   * `type: array` operation answered with an object was **skipped**, because the walker's array
   * branch returned as soon as the value was not one. Ten files answered `/projects/{id}/statuses`,
   * `/projects/{id}/reviews` and `/cases/{id}/versions` with the *project* body — §197's own
   * ordering defect, on the endpoints where it could not be seen.
   */
  it('reports an object served where the contract declares an array', () => {
    const statuses = responseSchemaOf('get', '/projects/{projectId}/statuses') as Record<string, unknown>
    expect(violationsOf(statuses, aProject())).toEqual([
      { where: '$', message: 'an object, where the contract declares `array`.' },
    ])
  })

  it('reports a date-time that is not one, and accepts one that is', () => {
    expect(violationsOf(summary, { ...aCase(), updatedAt: '2026-01-01' })).toEqual([
      { where: '$.updatedAt', message: expect.stringContaining('not the `date-time`') as unknown as string },
    ])
    // The shape is right and the month is not, which is why `Date.parse` runs as well as the regex.
    expect(violationsOf(summary, { ...aCase(), updatedAt: '2026-13-01T00:00:00Z' })).toHaveLength(1)
    expect(violationsOf(summary, { ...aCase(), updatedAt: '2026-01-01T00:00:00.123+02:00' })).toEqual([])
  })

  /**
   * The decision, pinned so that adding a `format: uuid` rule later reddens here rather than in 208
   * fixtures at once. `'p1'` is not a UUID and is not going to become one: an id in this suite is
   * something a reader recognises across four files, and the SPA treats it as opaque.
   */
  it('says nothing about an id that is not a UUID', () => {
    expect(violationsOf(project, aProject())).toEqual([])
  })
})
