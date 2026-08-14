/**
 * Every response body a test double serves is compared against `openapi.yaml`, and a body the
 * server could not send fails the test that served it.
 *
 * ## Why this is a seam and not a helper the tests call
 *
 * The obvious shape is `mockGet('/cases', body)` — a helper each test opts into, typed or checked
 * inside. It was rejected for the reason this repo keeps re-learning: **a check a new file can skip
 * by not calling it is a hand-maintained list**, and the failure mode of a hand-maintained list is
 * silence (§196's two tsconfig lists, §162's exemptions, §53's undeclared operations). It would also
 * be a 76-file migration to install, which is a large diff whose only content is opting in.
 *
 * So the seam sits where the double is *created*. `vi.spyOn` is wrapped once, here, and any spy it
 * returns for a member of the `api` object gets its `mockImplementation` / `mockResolvedValue` /
 * `mockReturnValue` (and the `…Once` forms) wrapped so the value they hand back passes through
 * `contractResponses.ts` on the way out. Nothing in a test file changes, a test written tomorrow is
 * covered without being told to be, and the one thing that could quietly turn the instrument off —
 * the suite moving off `vi.spyOn(api, …)` — is what `contractDoubles.test.ts`'s population guard
 * watches.
 *
 * **The wrapper is confined to `api`.** Every other `vi.spyOn` in the suite — and there are many,
 * on `console`, on `window.location`, on module exports — is handed back untouched.
 *
 * ## Why the failure lands in `afterEach` rather than at the call
 *
 * Throwing from inside the double would be caught by the layer that called it: TanStack Query treats
 * a rejected fetch as a failed query and renders the region's error branch, so a bad fixture would
 * surface as a *component* that failed to load, three assertions away from the fixture — which is
 * the exact reading failure §65 and §196 both paid for. Collected and raised after the test, it
 * names the endpoint, the field and the test.
 *
 * ## The escape hatch, and why it is a function rather than a list
 *
 * `servingADriftedBody()` suspends the check for one test. It exists because a body the contract
 * cannot describe is *the subject* of a few tests — the client's own handling of a response whose
 * shape drifted is a behaviour this app has, and a test for it must be able to serve one. It is a
 * call inside the test that needs it, so the reason is written where the exception is taken and it
 * cannot silently widen to a file, a directory, or a pattern.
 */
import { afterEach, expect, vi } from 'vitest'
import { api } from '../api/client'
import { contractPathOf, responseSchemaOf, violationsOf } from '../api/contractResponses'

/** Client verb → the OpenAPI operation key it sends, as `contractIntrospection` derives it. */
const METHODS: Record<string, string> = {
  get: 'get',
  post: 'post',
  patch: 'patch',
  put: 'put',
  del: 'delete',
  upload: 'post',
}

export interface Served {
  verb: string
  url: string
  message: string
}

let violations: Served[] = []
let suspended = false

/**
 * Suspends the comparison for the remainder of the current test.
 *
 * For a test whose subject *is* a body the contract cannot describe — drift handling, a response
 * from an older deployment, a truncated payload. Name the reason at the call site.
 */
export function servingADriftedBody(): void {
  suspended = true
}

/**
 * Everything served since the last drain, cleared.
 *
 * `afterEach` below drains to decide whether to fail, and `contractDoubles.test.ts` drains to assert
 * *what* was reported — a pin that could not see the collection could only observe this seam by
 * failing, which is not something a green suite can contain.
 */
export function drainServedViolations(): Served[] {
  const served = violations
  violations = []
  return served
}

function check(verb: string, url: unknown, body: unknown): void {
  if (suspended || typeof url !== 'string' || body === undefined || body === null) return
  const method = METHODS[verb]
  if (!method) return

  const contractPath = contractPathOf(url)
  if (!contractPath) {
    violations.push({
      verb,
      url,
      message:
        'resolves to no path in openapi.yaml. Either the double answers a URL the SPA no longer '
        + 'calls, or the path was renamed on one side only.',
    })
    return
  }

  const schema = responseSchemaOf(method, contractPath)
  if (!schema) {
    violations.push({
      verb,
      url: contractPath,
      message:
        'the contract declares no JSON success body for that operation, and this double served one.',
    })
    return
  }

  for (const violation of violationsOf(schema, body)) {
    violations.push({ verb, url: contractPath, message: `${violation.where} — ${violation.message}` })
  }
}

type AnyFn = (...args: unknown[]) => unknown

/**
 * The four pairs of a Vitest spy this seam replaces — as much of `MockInstance` as it touches, and
 * written out rather than reached for from the runner's types because every one of them is generic
 * over a client that is itself generic, which is the reason the doubles cast through `as never` in
 * the first place.
 */
interface Spy {
  mockImplementation: (fn: AnyFn) => unknown
  mockImplementationOnce: (fn: AnyFn) => unknown
  mockResolvedValue: (value: unknown) => unknown
  mockResolvedValueOnce: (value: unknown) => unknown
  mockReturnValue: (value: unknown) => unknown
  mockReturnValueOnce: (value: unknown) => unknown
}

function instrument(spy: Spy, verb: string): void {
  // Captured before anything is replaced, so the wrappers below drive the real implementations
  // rather than each other — `mockResolvedValue` is expressed as a `mockImplementation`, and
  // wrapping the already-wrapped one would double-report every body.
  const implementation: AnyFn = spy.mockImplementation as AnyFn
  const implementationOnce: AnyFn = spy.mockImplementationOnce as AnyFn

  const wrapImplementation = (original: AnyFn) => (fn: AnyFn) =>
    original.call(spy, async (...args: unknown[]) => {
      const body = await fn(...args)
      check(verb, args[0], body)
      return body
    })

  // `mockResolvedValue(x)` and `mockReturnValue(promise)` have no argument list to read the URL
  // from, so both become an implementation that ignores its arguments except to report them.
  const wrapValue = (original: AnyFn) => (value: unknown) =>
    wrapImplementation(original)((() => value) as AnyFn)

  spy.mockImplementation = wrapImplementation(implementation)
  spy.mockImplementationOnce = wrapImplementation(implementationOnce)
  spy.mockResolvedValue = wrapValue(implementation)
  spy.mockResolvedValueOnce = wrapValue(implementationOnce)
  spy.mockReturnValue = wrapValue(implementation)
  spy.mockReturnValueOnce = wrapValue(implementationOnce)
}

/*
 * Every argument is forwarded, not just the two this seam reads. `vi.spyOn(window, 'location',
 * 'get')` is the accessor form and dropping its third argument turns it into an attempt to spy on
 * an object, which is an error — the suite has one of those and it failed the moment this wrapper
 * was installed.
 */
const spyOn = vi.spyOn.bind(vi) as AnyFn
;(vi as unknown as { spyOn: unknown }).spyOn = (...args: unknown[]) => {
  const spy = spyOn(...args)
  if (args[0] === api && typeof args[1] === 'string') instrument(spy as Spy, args[1])
  return spy
}

afterEach(() => {
  const served = drainServedViolations()
  suspended = false

  if (served.length === 0) return

  const report = served
    .map((violation) => `  ${violation.verb.toUpperCase()} ${violation.url}\n    ${violation.message}`)
    .join('\n')

  expect.fail(
    `This test served ${served.length} response ${served.length === 1 ? 'body' : 'bodies'} the `
      + 'server could not send:\n\n'
      + `${report}\n\n`
      + 'A double casts through `as never` to satisfy the generic client, so nothing compares what '
      + 'it serves against the contract except this. A missing required field is a component one '
      + 'commit away from a TypeError (§195), and a field the contract does not declare is usually '
      + 'a double answering a different endpoint, or a vocabulary the product removed (§98).\n\n'
      + 'Repair the fixture — `src/test/fixtures.ts` has a complete factory for most shapes. If the '
      + 'contract is the side that is wrong, change it and regenerate both `schema.d.ts` copies. If '
      + 'the drifted body IS the subject of this test, call `servingADriftedBody()` and say why.',
  )
})
