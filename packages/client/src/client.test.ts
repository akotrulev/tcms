import { describe, expect, expectTypeOf, it } from 'vitest';

import { createTcmsClient } from './client.js';
import { TcmsApiError } from './errors.js';
import { stubFetch } from './test/stubFetch.js';
import type { components } from './schema.js';

const client = (fetch: typeof globalThis.fetch) =>
  createTcmsClient({
    baseUrl: 'https://tcms.example.com',
    token: 'tcms_abc',
    userAgent: 'tcms-cli/0.1.0',
    fetch,
  });

describe('createTcmsClient', () => {
  it('composes the contract prefix and returns the response body', async () => {
    const stub = stubFetch({
      status: 200,
      body: { id: 'u-1', email: 'ana@acme.test', name: 'Ana', companyId: 'c-1' },
    });

    const me = await client(stub.fetch).GET('/me');

    expect(stub.last.url).toBe('https://tcms.example.com/api/v1/me');
    expect(me.email).toBe('ana@acme.test');
  });

  it('fills path parameters from the typed init', async () => {
    const stub = stubFetch({ status: 200, body: { id: 'p-1', key: 'OLB', name: 'Online Banking' } });

    await client(stub.fetch).GET('/projects/{projectId}', {
      params: { path: { projectId: 'p-1' } },
    });

    expect(stub.last.url).toBe('https://tcms.example.com/api/v1/projects/p-1');
  });

  it('serializes query parameters', async () => {
    const stub = stubFetch({ status: 200, body: { permissions: [] } });

    await client(stub.fetch).GET('/me/permissions', {
      params: { query: { projectId: 'p-1' } },
    });

    expect(stub.last.url).toBe('https://tcms.example.com/api/v1/me/permissions?projectId=p-1');
  });

  it('passes per-request options through to the transport without sending them as a body', async () => {
    const stub = stubFetch({ status: 201, body: { id: 's-1' } });

    await client(stub.fetch).POST('/projects/{projectId}/suites', {
      params: { path: { projectId: 'p-1' } },
      body: { name: 'Smoke' },
      tcms: { idempotencyKey: 'intent-1' },
    });

    expect(stub.last.headers.get('idempotency-key')).toBe('intent-1');
    // `tcms` must not reach the wire: openapi-fetch forwards unknown init members to fetch, so
    // an option spelled loosely would either be ignored or serialized into the request.
    expect(stub.last.body).toBe(JSON.stringify({ name: 'Smoke' }));
  });

  it('throws the refusal rather than answering a result tuple', async () => {
    const stub = stubFetch({
      status: 403,
      body: { type: 'about:blank', title: 'Forbidden', status: 403, detail: 'Not permitted.' },
    });

    await expect(client(stub.fetch).GET('/projects/{projectId}', {
      params: { path: { projectId: 'p-1' } },
    })).rejects.toBeInstanceOf(TcmsApiError);
  });

  it('answers undefined for a 204, which is what the deletes send', async () => {
    const stub = stubFetch({ status: 204 });

    const answer = await client(stub.fetch).DELETE('/api-tokens/{tokenId}', {
      params: { path: { tokenId: 't-1' } },
    });

    expect(answer).toBeUndefined();
  });

  it('exposes the resolved base URL, for the server-composed download URLs', () => {
    expect(client(stubFetch().fetch).baseUrl).toBe('https://tcms.example.com/api/v1');
  });
});

/**
 * The reason this package is generated rather than hand-written.
 *
 * `frontend/` spends ~450 lines of AST walking in `contractOperations.test.ts` and
 * `contractParameters.test.ts` reconstructing what each hand-built URL meant, in order to compare
 * it against the contract — because a template literal can name a path that does not exist and
 * nothing else notices. Here the same four questions are answered by the compiler, so these are
 * type assertions and there is no walker to keep teaching about new call shapes.
 */
describe('the contract is enforced by the type system', () => {
  const api = client(stubFetch().fetch);

  it('refuses a path the contract does not declare', () => {
    // @ts-expect-error — no such path in openapi.yaml
    expectTypeOf(api.GET).toBeCallableWith('/projects/{projectId}/testcases');
  });

  it('refuses a method the path does not declare', () => {
    // @ts-expect-error — /me is GET-only
    expectTypeOf(api.POST).toBeCallableWith('/me');
  });

  it('types the response from the contract, not from the call site', async () => {
    const stub = stubFetch({ status: 200, body: {} });
    const project = await client(stub.fetch).GET('/projects/{projectId}', {
      params: { path: { projectId: 'p-1' } },
    });

    expectTypeOf(project).toEqualTypeOf<components['schemas']['Project']>();
  });
});
