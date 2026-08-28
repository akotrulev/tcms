import { describe, expect, it } from 'vitest'
import { ApiError, TimeoutError } from '../api/client'
import { describeError, isTransient } from './describeError'

/**
 * `describeError` and `isTransient` are the two places a failure's *policy* is decided, and
 * both are consumed by code that renders nothing on its own:
 *
 *   - `isTransient` IS the retry policy (`app/queryClient.ts`). Get it wrong in the permissive
 *     direction and a 403 is asked three times with backoff, so a permission error takes seven
 *     seconds to appear. Get it wrong in the restrictive direction and a 503 that would have
 *     succeeded on the second try becomes a failed region.
 *   - `describeError.retryable` decides whether `DataRegion` offers a "Try again" button, and
 *     `isAuth` decides whether a failure runs the session-recovery ladder instead of surfacing
 *     as an ordinary error.
 *
 * Neither is visible on screen as itself, which is why they are tested as a matrix rather than
 * through a component.
 */

const apiError = (status: number, detail = 'the server said so', title: string | null = null) =>
  new ApiError(status, detail, null, null, null, title)

describe('isTransient — the retry policy', () => {
  it.each([
    ['500 Internal Server Error', apiError(500)],
    ['502 Bad Gateway', apiError(502)],
    ['503 Service Unavailable', apiError(503)],
    ['504 Gateway Timeout', apiError(504)],
    ['408 Request Timeout', apiError(408)],
    ['429 Too Many Requests', apiError(429)],
  ])('retries %s', (_label, error) => {
    expect(isTransient(error)).toBe(true)
  })

  it('retries a client-side timeout, which is the failure most likely to succeed on a second try', () => {
    expect(isTransient(new TimeoutError(10_000))).toBe(true)
  })

  it("retries a fetch TypeError, the browser's way of saying the request never left", () => {
    expect(isTransient(new TypeError('Failed to fetch'))).toBe(true)
  })

  it.each([
    ['400 Bad Request', apiError(400)],
    ['401 Unauthorized', apiError(401)],
    ['403 Forbidden', apiError(403)],
    ['404 Not Found', apiError(404)],
    ['409 Conflict', apiError(409)],
    ['422 Unprocessable Entity', apiError(422)],
  ])('does NOT retry %s', (_label, error) => {
    expect(isTransient(error)).toBe(false)
  })

  /*
    The two boundaries the `>= 500 || 408 || 429` expression can be got wrong at, pinned
    explicitly: 407 and 428 sit one below each special case and must stay non-transient, and
    499/500 straddle the server-error boundary.
  */
  it.each([
    [407, false],
    [409, false],
    [428, false],
    [430, false],
    [499, false],
    [500, true],
  ])('treats %i as transient=%s', (status, expected) => {
    expect(isTransient(apiError(status))).toBe(expected)
  })

  it('does not retry something that is not an error at all', () => {
    expect(isTransient(undefined)).toBe(false)
    expect(isTransient(null)).toBe(false)
    expect(isTransient('a string')).toBe(false)
    expect(isTransient(new Error('a plain bug'))).toBe(false)
  })
})

describe('describeError — the copy and the two flags that drive behaviour', () => {
  it('marks only a 401 as an auth failure, so a 403 never touches the session', () => {
    expect(describeError(apiError(401)).isAuth).toBe(true)
    expect(describeError(apiError(403)).isAuth).toBe(false)
    expect(describeError(apiError(500)).isAuth).toBe(false)
    expect(describeError(new TimeoutError(10_000)).isAuth).toBe(false)
  })

  it.each([
    ['a timeout', new TimeoutError(10_000), true],
    ['a 500', apiError(500), true],
    ['a 429', apiError(429), true],
    ['a 403', apiError(403), false],
    ['a 404', apiError(404), false],
    ['a 409', apiError(409), false],
    ['a 400', apiError(400), false],
    ['a 401', apiError(401), false],
  ])('offers a retry for %s: %s', (_label, error, expected) => {
    expect(describeError(error).retryable).toBe(expected)
  })

  it('groups by cause, so several regions failing on one 503 collapse into one notification', () => {
    // The notification stack dedupes on `code`. Two different regions failing the same way must
    // produce the same code, and two different causes must not.
    expect(describeError(apiError(503)).code).toBe('503')
    expect(describeError(apiError(503, 'a different sentence')).code).toBe('503')
    expect(describeError(apiError(500)).code).not.toBe(describeError(apiError(503)).code)
    expect(describeError(new TimeoutError(3_000)).code).toBe('timeout')
  })

  it('carries the correlation id through, and nulls it where there is none', () => {
    const withId = new ApiError(500, 'boom', null, 'corr-123')
    expect(describeError(withId).correlationId).toBe('corr-123')
    expect(describeError(new TimeoutError(1_000)).correlationId).toBeNull()
    expect(describeError(new Error('bug')).correlationId).toBeNull()
  })

  /*
    §32's 409 split, in the two forms that want opposite advice. This is the assertion that
    fails if someone "simplifies" the 409 branch back to one message.
  */
  describe('the 409 that is not a lost update', () => {
    it('keeps the server sentence when the conflict is a state refusal', () => {
      const stateRefusal = apiError(
        409,
        'This suite still holds 42 cases. Move or delete them first.',
        'Suite is not empty',
      )
      const described = describeError(stateRefusal)
      expect(described.title).toBe('Suite is not empty')
      expect(described.body).toBe('This suite still holds 42 cases. Move or delete them first.')
      // Telling the user to reload here would be advice that changes nothing.
      expect(described.body).not.toMatch(/reload/i)
    })

    it('uses the lost-update copy when the server titles it as one', () => {
      const lostUpdate = apiError(409, 'version 4 is stale', 'Someone else saved first')
      expect(describeError(lostUpdate).body).toMatch(/Reload to see their version/i)
    })

    it('falls back to the lost-update copy for a 409 with no title', () => {
      expect(describeError(apiError(409, 'conflict', null)).body).toMatch(/Reload/i)
    })

    it('falls back to the lost-update copy for a titled 409 with no detail', () => {
      expect(describeError(apiError(409, '', 'Some other conflict')).body).toMatch(/Reload/i)
    })
  })

  /*
    ~~shows the server detail for a 404, because it names the missing thing~~ → superseded
    2026-08-09 (§148). The old assertion's own fixture was the tell: it fed a detail reading
    "No test case TC-9001 in this project." and no 404 this API writes has ever carried one, or
    could — every 404 is `notFound().build()`, and naming the missing thing is precisely what
    §6t forbids. The test passed because it supplied the sentence it then asserted.

    What reached the screen instead was `client.ts`'s floor: the status text, so "Not Found"
    under the heading "Not found". The copy is fixed and does not read the wire at all.
  */
  it('does not read a detail on a 404 — the API never sends one', () => {
    expect(describeError(apiError(404, 'Not Found')).body).toBe(
      'It may have been deleted, or it may be in a project you cannot open.',
    )
  })

  it('says the same thing even if a 404 somehow arrives with a body', () => {
    expect(describeError(apiError(404, 'No test case TC-9001 in this project.')).body).not.toContain(
      'TC-9001',
    )
  })

  /*
    The rule the whole module exists to enforce: a raw exception message, response body, or
    stack must never reach the screen. A 500's detail is server prose we did not author and a
    bug's message is our own stack trace — neither is shown.
  */
  it('never leaks an unrecognised error message into the body', () => {
    const leaky = new Error('TypeError: cannot read properties of undefined (reading "items")')
    const described = describeError(leaky)
    expect(described.body).toBe('Check your connection and try again.')
    expect(described.body).not.toContain('undefined')
  })

  it('never leaks a 5xx response body into the body copy', () => {
    const described = describeError(apiError(500, 'org.postgresql.util.PSQLException: relation ...'))
    expect(described.body).not.toContain('PSQLException')
  })

  it('describes a non-Error throw without crashing', () => {
    const described = describeError('a bare string was thrown')
    expect(described.title).toBeTruthy()
    expect(described.code).toBe('unknown')
    expect(described.retryable).toBe(true)
  })

  it('always produces a non-empty title and body, for every input it can receive', () => {
    const inputs: unknown[] = [
      new TimeoutError(1),
      new Error('x'),
      'string',
      null,
      undefined,
      { not: 'an error' },
      ...[400, 401, 403, 404, 409, 418, 422, 429, 500, 503].map((s) => apiError(s)),
    ]
    for (const input of inputs) {
      const described = describeError(input)
      expect(described.title.length, `title for ${String(input)}`).toBeGreaterThan(0)
      expect(described.body.length, `body for ${String(input)}`).toBeGreaterThan(0)
    }
  })
})

describe('a wrong address is told apart from a failure', () => {
  /**
   * `/projects/1/cases` — an id that is not a UUID — reached the reader as "The body is not valid
   * JSON, or a field holds a value of the wrong type. Check the request against the schema in the
   * API contract." That is `ProblemErrorAttributes`' copy for whoever is holding the OpenAPI
   * document, and the generic 4xx arm forwarded it verbatim.
   */
  it.each([
    ['a malformed path parameter', apiError(400, 'The body is not valid JSON, or a field holds a value of the wrong type. Check the request against the schema in the API contract.', 'The request could not be read')],
    ['an unmapped path', apiError(404, 'There is no operation at this path.', 'No such endpoint')],
    ['a wrong method', apiError(405, 'That HTTP method is not supported at this path.', 'Method not allowed')],
  ])('%s never shows the contract copy, and marks the address wrong', (_label, error) => {
    const described = describeError(error);
    expect(described.isAddressWrong).toBe(true);
    expect(described.body).not.toContain('API contract');
    expect(described.body).not.toContain('JSON');
    expect(described.retryable).toBe(false);
  });

  it('marks an ordinary 404 as a wrong address so the route can offer a way out', () => {
    expect(describeError(apiError(404)).isAddressWrong).toBe(true);
  });

  it.each([
    ['a 500', apiError(500)],
    ['a 403', apiError(403)],
    ['a 409', apiError(409)],
  ])('does not divert %s — the resource exists, the request failed', (_label, error) => {
    expect(describeError(error).isAddressWrong).toBe(false);
  });

  /**
   * A 400 that is NOT one of the contract-facing problems is a real rejection of a real request,
   * and its detail is the server telling the user what it objected to. Diverting that to
   * "not found" would hide the only useful sentence.
   */
  it('keeps the detail of a 400 the server wrote for the user', () => {
    const described = describeError(apiError(400, 'A run cannot start with no cases in scope.', 'Empty scope'));
    expect(described.isAddressWrong).toBe(false);
    expect(described.body).toBe('A run cannot start with no cases in scope.');
  });
});
