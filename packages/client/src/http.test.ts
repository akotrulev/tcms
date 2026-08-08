import { describe, expect, it } from 'vitest';

import { TcmsApiError, TcmsClientError, TcmsTimeoutError, isTransient, retryAfterMs } from './errors.js';
import { createTransport, resolveBaseUrl, withOptions } from './http.js';
import { stubFetch } from './test/stubFetch.js';

const options = (fetch: typeof globalThis.fetch, overrides: Record<string, unknown> = {}) => ({
  baseUrl: 'https://tcms.example.com',
  token: 'tcms_abc',
  userAgent: 'tcms-cli/0.1.0',
  fetch,
  ...overrides,
});

describe('resolveBaseUrl', () => {
  it('composes the contract prefix onto an origin', () => {
    expect(resolveBaseUrl('https://tcms.example.com')).toBe('https://tcms.example.com/api/v1');
  });

  it('tolerates a trailing slash rather than composing //api/v1', () => {
    expect(resolveBaseUrl('https://tcms.example.com/')).toBe('https://tcms.example.com/api/v1');
  });

  it('keeps a path prefix, for an instance mounted under one', () => {
    expect(resolveBaseUrl('https://example.com/tcms/')).toBe('https://example.com/tcms/api/v1');
  });

  it('accepts http, for a local instance', () => {
    expect(resolveBaseUrl('http://localhost:8080')).toBe('http://localhost:8080/api/v1');
  });

  // The three below are the ones that matter: this function's output is where a bearer token
  // goes, so anything it accepts by accident is a credential sent somewhere nobody chose.
  it('refuses a non-URL', () => {
    expect(() => resolveBaseUrl('tcms.example.com')).toThrow(TcmsClientError);
  });

  it('refuses a scheme that is not http(s)', () => {
    expect(() => resolveBaseUrl('file:///etc/passwd')).toThrow(/Refusing to send a credential/);
  });

  it('refuses a URL carrying a query string', () => {
    expect(() => resolveBaseUrl('https://tcms.example.com/?x=1')).toThrow(/origin alone/);
  });
});

describe('the transport', () => {
  it('attaches the credential and the user agent to every request', async () => {
    const stub = stubFetch({ status: 200, body: { id: 'u1' } });
    await createTransport(options(stub.fetch)).fetch('https://tcms.example.com/api/v1/me');

    expect(stub.last.headers.get('authorization')).toBe('Bearer tcms_abc');
    expect(stub.last.headers.get('user-agent')).toBe('tcms-cli/0.1.0');
    expect(stub.last.headers.get('accept')).toBe('application/json');
  });

  it('reads a function token per request, so a rotated credential takes effect', async () => {
    const stub = stubFetch({ status: 200, body: {} }, { status: 200, body: {} });
    let current = 'tcms_first';
    const transport = createTransport(options(stub.fetch, { token: () => current }));

    await transport.fetch('https://tcms.example.com/api/v1/me');
    current = 'tcms_second';
    await transport.fetch('https://tcms.example.com/api/v1/me');

    expect(stub.calls.map((call) => call.headers.get('authorization'))).toEqual([
      'Bearer tcms_first',
      'Bearer tcms_second',
    ]);
  });

  it('refuses to send an empty credential rather than an anonymous request', async () => {
    const stub = stubFetch({ status: 200, body: {} });
    const transport = createTransport(options(stub.fetch, { token: '' }));

    await expect(transport.fetch('https://tcms.example.com/api/v1/me')).rejects.toThrow(
      TcmsClientError,
    );
    expect(stub.calls).toHaveLength(0);
  });

  it('sends Idempotency-Key and If-Match only when the caller supplied them', async () => {
    const stub = stubFetch({ status: 200, body: {} }, { status: 200, body: {} });
    const transport = createTransport(options(stub.fetch));

    // Through `withOptions`, which is how a caller attaches these — rather than a hand-spelled
    // literal, because a key that arrives under the wrong property name is a header that
    // silently does not arrive, and a test that spells it itself cannot notice.
    await transport.fetch(
      'https://tcms.example.com/api/v1/projects/p/cases',
      withOptions({ method: 'POST' }, { idempotencyKey: 'intent-1', ifMatch: 7 }),
    );
    await transport.fetch('https://tcms.example.com/api/v1/me');

    expect(stub.calls[0]?.headers.get('idempotency-key')).toBe('intent-1');
    expect(stub.calls[0]?.headers.get('if-match')).toBe('7');
    expect(stub.calls[1]?.headers.get('idempotency-key')).toBeNull();
    expect(stub.calls[1]?.headers.get('if-match')).toBeNull();
  });

  it('sends If-Match for lockVersion 0, which a truthiness check would drop', async () => {
    const stub = stubFetch({ status: 200, body: {} });
    await createTransport(options(stub.fetch)).fetch(
      'https://tcms.example.com/api/v1/x',
      withOptions({ method: 'PATCH' }, { ifMatch: 0 }),
    );

    expect(stub.last.headers.get('if-match')).toBe('0');
  });
});

describe('a refusal', () => {
  it('becomes a TcmsApiError carrying the problem document', async () => {
    const stub = stubFetch({
      status: 400,
      body: {
        type: 'about:blank',
        title: 'Validation failed',
        status: 400,
        detail: 'The case could not be saved.',
        errors: { gherkinSource: ['must not be blank'] },
      },
      headers: { 'x-correlation-id': 'cid-9' },
    });

    const error = await createTransport(options(stub.fetch))
      .fetch('https://tcms.example.com/api/v1/projects/p/cases', { method: 'POST' })
      .catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(TcmsApiError);
    const api = error as TcmsApiError;
    expect(api.status).toBe(400);
    expect(api.detail).toBe('The case could not be saved.');
    expect(api.title).toBe('Validation failed');
    expect(api.correlationId).toBe('cid-9');
    expect(api.hasFieldErrors).toBe(true);
    expect(api.fieldErrors).toEqual({ gherkinSource: ['must not be blank'] });
    // The message names the request, so a CLI printing the error alone still says what failed.
    expect(api.method).toBe('POST');
    expect(api.path).toBe('/api/v1/projects/p/cases');
  });

  it('falls back to a sentence when the body is not a problem document', async () => {
    // The API's own 401 is bodiless, and `statusText` is empty over HTTP/2 — the case that
    // leaves `detail` as '' and prints a blank line where the reason should be.
    const stub = stubFetch({ status: 401, text: '' });

    const error = (await createTransport(options(stub.fetch))
      .fetch('https://tcms.example.com/api/v1/me')
      .catch((caught: unknown) => caught)) as TcmsApiError;

    expect(error.detail).toBe('The request failed (HTTP 401).');
    expect(error.title).toBeNull();
  });

  it('survives a proxy answering HTML', async () => {
    const stub = stubFetch({
      status: 502,
      text: '<html><body>Bad Gateway</body></html>',
      headers: { 'content-type': 'text/html' },
    });

    const error = (await createTransport(options(stub.fetch))
      .fetch('https://tcms.example.com/api/v1/me')
      .catch((caught: unknown) => caught)) as TcmsApiError;

    expect(error.status).toBe(502);
    expect(error.detail).not.toContain('<html>');
  });

  it('carries Retry-After in both of its spellings', async () => {
    const seconds = new TcmsApiError({
      status: 503, detail: 'x', method: 'GET', path: '/', retryAfter: '30',
    });
    expect(retryAfterMs(seconds)).toBe(30_000);

    const date = new TcmsApiError({
      status: 503,
      detail: 'x',
      method: 'GET',
      path: '/',
      retryAfter: new Date(Date.now() - 60_000).toUTCString(),
    });
    // A clock-skewed client computes a negative delay from the date form; floored, not trusted.
    expect(retryAfterMs(date)).toBe(0);
  });
});

describe('a request that does not answer', () => {
  it('becomes a TcmsTimeoutError that says the write may have landed', async () => {
    const stub = stubFetch({ hangs: true });
    const transport = createTransport(options(stub.fetch));

    const error = (await transport
      .fetch(
        'https://tcms.example.com/api/v1/projects/p/cases',
        withOptions({ method: 'POST' }, { timeoutMs: 50 }),
      )
      .catch((caught: unknown) => caught)) as Error;

    expect(error).toBeInstanceOf(TcmsTimeoutError);
    expect(error.message).toMatch(/may still have been applied/);
    expect(isTransient(error)).toBe(true);
  });

  it('leaves a caller cancellation as an abort, which is not a failure', async () => {
    const stub = stubFetch({ hangs: true });
    const controller = new AbortController();
    const transport = createTransport(options(stub.fetch));

    const pending = transport.fetch(
      'https://tcms.example.com/api/v1/me',
      withOptions({}, { signal: controller.signal }),
    );
    controller.abort();

    const error = (await pending.catch((caught: unknown) => caught)) as Error;
    expect(error).not.toBeInstanceOf(TcmsTimeoutError);
    expect(error.name).toBe('AbortError');
  });
});

describe('isTransient', () => {
  const at = (status: number) =>
    new TcmsApiError({ status, detail: 'x', method: 'GET', path: '/' });

  it('is true for 429 and 5xx', () => {
    expect(isTransient(at(429))).toBe(true);
    expect(isTransient(at(503))).toBe(true);
  });

  // The one worth pinning: a 409 is either a concurrent edit or a forbidden state, and both need
  // the caller to re-read. An agent that retries it loops.
  it('is false for a 409', () => {
    expect(isTransient(at(409))).toBe(false);
  });

  it('is false for 4xx refusals and for a non-error', () => {
    expect(isTransient(at(403))).toBe(false);
    expect(isTransient(new Error('boom'))).toBe(false);
  });
});
