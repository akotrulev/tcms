/**
 * Every body that crosses a test double is compared against `openapi.yaml` — the response it serves
 * and, since §199, the request it was **called with** — and a body the server could not send, or
 * could not accept, fails the test.
 *
 * The comparison is `contractBodies.ts` and it asks six questions — two structural (§197) and four
 * about the **values** (§198). This file is only the seam that gets it a body to look at.
 *
 * ## The two directions are not the same kind of finding
 *
 * A **response** violation is a fixture defect: a body a test wrote, standing in for one the server
 * would send. A **request** violation is usually not test code at all — the payload arrives at the
 * double having been composed by `api/queries/`, a `useForm` submit handler and whatever the
 * component did to the form's values, so what is being compared is the thing the SPA would have put
 * on the wire. Same instrument, same six questions; the response half checks what the suite claims
 * about the server, and the request half checks what the product actually sends it.
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
 * `contractBodies.ts` on the way out. Nothing in a test file changes, a test written tomorrow is
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
 * `servingADriftedBody()` suspends the check for one test — **both directions**, since §199. It
 * exists because a body the contract cannot describe is *the subject* of a few tests: the client's
 * own handling of a response whose shape drifted is a behaviour this app has, and a test for it must
 * be able to serve one; a test that deliberately sends a payload the server refuses is the same
 * thing from the other end. It is a call inside the test that needs it, so the reason is written
 * where the exception is taken and it cannot silently widen to a file, a directory, or a pattern.
 */
import { afterEach, expect, vi } from 'vitest'
import { api } from '../api/client'
import type { Violation } from '../api/contractBodies'
import { contractPathOf, requestBodyOf, responseSchemaOf, violationsOf } from '../api/contractBodies'

/** Client verb → the OpenAPI operation key it sends, as `contractIntrospection` derives it. */
const METHODS: Record<string, string> = {
  get: 'get',
  post: 'post',
  patch: 'patch',
  put: 'put',
  del: 'delete',
  upload: 'post',
  postText: 'post',
  getText: 'get',
  postForText: 'post',
}

/**
 * The verbs whose **second argument is a body**, which is not the same list as the verbs that send
 * one. `api.get(path, options)` and `api.del(path, options)` take `RequestOptions` in that position,
 * so reading it as a payload would compare a timeout budget against a schema; `api.upload` really
 * does take a body there and it is a `FormData`, which `requestBodyOf` reports as multipart and this
 * seam therefore never walks.
 */
const SENDS_A_BODY = new Set(['post', 'patch', 'put', 'upload', 'postForText'])

/**
 * The verbs whose **success body is a document** rather than JSON (§255, and `postForText` since
 * 003 C1's `render-gherkin`).
 *
 * `responseSchemaOf` answers `undefined` for such an operation — correctly, since the contract
 * declares `text/plain` and no schema — and the report it would otherwise produce ("declares no
 * JSON success body") would be true of every one of these calls and useful about none of them.
 * What is still asked is the question that can fail: the URL must resolve to a declared path (the
 * check above this one), and the double must serve **text**. A double answering `.feature` with an
 * object is a test whose component would receive something the endpoint cannot send.
 *
 * `postForText` is in both this set and {@link SENDS_A_BODY}, which no other verb is: it is the one
 * operation whose request is JSON the contract describes and whose response is a document it
 * cannot. Both halves are therefore checked, each by the rule that applies to it.
 */
const SERVES_TEXT = new Set(['getText', 'postForText'])

export interface Served {
  verb: string
  url: string
  message: string
  /** Which half of the exchange this is, for the report and for the seam's own tests. */
  direction: 'served' | 'sent'
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

function checkServed(verb: string, url: unknown, body: unknown): void {
  if (suspended || typeof url !== 'string' || body === undefined || body === null) return
  const method = METHODS[verb]
  if (!method) return

  const contractPath = contractPathOf(url)
  if (!contractPath) {
    violations.push({
      verb,
      url,
      direction: 'served',
      message:
        'resolves to no path in openapi.yaml. Either the double answers a URL the SPA no longer '
        + 'calls, or the path was renamed on one side only.',
    })
    return
  }

  const schema = responseSchemaOf(method, contractPath)
  if (!schema) {
    if (SERVES_TEXT.has(verb)) {
      if (typeof body !== 'string') {
        violations.push({
          verb,
          url: contractPath,
          direction: 'served',
          message:
            'the contract serves that operation as text/plain, and this double served '
            + `${typeof body === 'object' ? 'an object' : typeof body}.`,
        })
      }
      return
    }
    violations.push({
      verb,
      url: contractPath,
      direction: 'served',
      message:
        'the contract declares no JSON success body for that operation, and this double served one.',
    })
    return
  }

  report(verb, contractPath, 'served', violationsOf(schema, body, 'served'))
}

/**
 * The payload the code under test handed the double, against what the operation says it accepts.
 *
 * Called **before** the double's own implementation runs, so a test whose double rejects — the error
 * path of every mutation in the suite — still has the payload it sent compared. What is checked is
 * what `client.ts` would have serialized, which is why an absent body is walked as `{}`: `post` and
 * its two siblings send `JSON.stringify(body ?? {})`, so a call that passes nothing to an operation
 * requiring a body does not send nothing, it sends an empty object the server refuses with a 400.
 */
function checkSent(verb: string, url: unknown, body: unknown): void {
  if (suspended || typeof url !== 'string' || !SENDS_A_BODY.has(verb)) return
  const method = METHODS[verb]
  if (!method) return

  const contractPath = contractPathOf(url)
  // A URL that resolves to nothing, and a method the path does not declare, are both already
  // reported — by the response half above and by `contractOperations.test.ts`, which reads the call
  // sites statically. Saying it twice would name one defect as two.
  if (!contractPath) return
  const declared = requestBodyOf(method, contractPath)

  if (declared.kind === 'none') {
    // `{}` and nothing at all are the same request — `JSON.stringify(body ?? {})` — so an operation
    // that takes no body is only being sent one when the object has something in it. Reporting the
    // empty case would report a call that is byte-identical to the one it is telling you to write.
    if (body !== undefined && !isEmptyObject(body)) {
      violations.push({
        verb,
        url: contractPath,
        direction: 'sent',
        message:
          'the contract declares no request body for that operation, and this call sent one. The '
          + 'server reads none of it.',
      })
    }
    return
  }
  if (declared.kind !== 'json') return

  report(verb, contractPath, 'sent', violationsOf(declared.schema, body ?? {}, 'sent'))
}

function isEmptyObject(body: unknown): boolean {
  return typeof body === 'object' && body !== null && !Array.isArray(body) && Object.keys(body).length === 0
}

function report(verb: string, contractPath: string, direction: 'served' | 'sent', found: Violation[]): void {
  for (const violation of found) {
    violations.push({ verb, url: contractPath, direction, message: `${violation.where} — ${violation.message}` })
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
      checkSent(verb, args[0], args[1])
      const body = await fn(...args)
      checkServed(verb, args[0], body)
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

  const lines = served
    .map((violation) =>
      `  ${violation.direction === 'sent' ? 'sent to' : 'served for'} `
      + `${violation.verb.toUpperCase()} ${violation.url}\n    ${violation.message}`)
    .join('\n')

  const sent = served.filter((violation) => violation.direction === 'sent').length

  expect.fail(
    `This test moved ${served.length} ${served.length === 1 ? 'body' : 'bodies'} the server could `
      + `not ${sent === served.length ? 'accept' : sent === 0 ? 'send' : 'send or accept'}:\n\n`
      + `${lines}\n\n`
      + 'A double casts through `as never` to satisfy the generic client, so nothing compares what '
      + 'it serves against the contract except this. A missing required field is a component one '
      + 'commit away from a TypeError (§195); a field the contract does not declare is usually a '
      + 'double answering a different endpoint, or a vocabulary the product removed (§98); and a '
      + 'value the contract does not admit — an enum member that is not one, a null in a field the '
      + 'server always fills — is a screen rendering something the product cannot produce (§198).\n\n'
      + 'A **sent** body is the other direction and usually not a fixture at all: it is what '
      + '`api/queries/` and the form above it composed, so the repair is normally in the code under '
      + 'test rather than in this file. A property the contract does not declare is one the server '
      + 'discards without complaint (§199).\n\n'
      + 'Repair the fixture — `src/test/fixtures.ts` has a complete factory for most shapes. If the '
      + 'contract is the side that is wrong, change it and regenerate both `schema.d.ts` copies. If '
      + 'the drifted body IS the subject of this test, call `servingADriftedBody()` and say why.',
  )
})
