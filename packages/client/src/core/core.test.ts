import { readFileSync } from 'node:fs';
import { dirname, resolve as resolvePath } from 'node:path';
import { fileURLToPath } from 'node:url';

import { parse } from 'yaml';
import { describe, expect, it } from 'vitest';

import { createTcmsClient } from '../client.js';
import { TcmsApiError, TcmsClientError } from '../errors.js';
import { stubFetch } from '../test/stubFetch.js';
import { ifMatchFor, isVersionConflict, versionOf } from './concurrency.js';
import { KEY_PREFIXES, classify, isUuid, parseDisplayKey } from './identifiers.js';
import { MAX_IDEMPOTENCY_KEY, newIntentKey, requireIntentKey } from './idempotency.js';
import { DEFAULT_PAGE_SIZE, clampPageSize, eachItem, hasMore } from './paging.js';
import { resolveProject } from './project.js';
import { describeReach, describeRefusal } from './refusal.js';
import { resolveId } from './resolve.js';

const client = (fetch: typeof globalThis.fetch) =>
  createTcmsClient({
    baseUrl: 'https://tcms.example.com',
    token: 'tcms_abc',
    userAgent: 'tcms-cli/0.1.0',
    fetch,
  });

const UUID = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';

describe('display keys', () => {
  it('parses the five seeded prefixes and nothing else', () => {
    expect(parseDisplayKey('TC-42')?.entity).toBe('CASE');
    expect(parseDisplayKey('TS-1')?.entity).toBe('SUITE');
    expect(parseDisplayKey('TP-9')?.entity).toBe('PLAN');
    expect(parseDisplayKey('TR-3')?.entity).toBe('RUN');
    expect(parseDisplayKey('DOC-7')?.entity).toBe('DOC_PAGE');

    // The surface spec's §4.4 example. There is no SU- prefix and never was; a client that
    // accepted it would report "not found" for a key the user believed in.
    expect(parseDisplayKey('SU-7')).toBeNull();
  });

  it('normalises case but keeps the number verbatim', () => {
    expect(parseDisplayKey('tc-42')?.key).toBe('TC-42');
    expect(parseDisplayKey('TC-4200')?.number).toBe(4200);
  });

  it('refuses a zero-padded or zero key, which V5 never allocates', () => {
    expect(parseDisplayKey('TC-042')).toBeNull();
    expect(parseDisplayKey('TC-0')).toBeNull();
  });

  it('tells a UUID from a key', () => {
    expect(isUuid(UUID)).toBe(true);
    expect(isUuid('TC-42')).toBe(false);
    expect(classify(UUID).kind).toBe('uuid');
    expect(classify('TC-42', 'CASE').kind).toBe('key');
  });

  it('refuses a key of the wrong kind rather than looking it up', () => {
    // The typo with a plausible answer: TC-42 exists, and is not a suite.
    expect(() => classify('TC-42', 'SUITE')).toThrow(/is a case key, but a suite was expected/);
  });

  it('refuses a string that is neither, instead of letting the server answer 400', () => {
    expect(() => classify('smoke tests', 'CASE')).toThrow(TcmsClientError);
  });

  it('spells the prefixes as V5 seeds them', () => {
    expect(KEY_PREFIXES).toEqual({
      CASE: 'TC-', SUITE: 'TS-', PLAN: 'TP-', RUN: 'TR-', DOC_PAGE: 'DOC-',
    });
  });
});

describe('idempotency', () => {
  it('mints a distinct key per intent', () => {
    expect(newIntentKey()).not.toBe(newIntentKey());
  });

  it('refuses a blank or over-long caller key before the request rather than after it', () => {
    expect(() => requireIntentKey('  ')).toThrow(/cannot be blank/);
    expect(() => requireIntentKey('k'.repeat(MAX_IDEMPOTENCY_KEY + 1))).toThrow(/at most 200/);
    expect(requireIntentKey(' k '.trim())).toBe('k');
  });

  /*
    §136's finding, arriving from the client side: `MAX_IDEMPOTENCY_KEY = 200` is declared in
    seven backend services across four modules, and this is the eighth declaration — on the other
    side of the wire, where nothing on the server can see it drift. So it is read out of the
    contract rather than trusted.
  */
  it('matches the maxLength the contract declares', () => {
    const spec = parse(
      readFileSync(
        resolvePath(dirname(fileURLToPath(import.meta.url)), '../../../../docs/specs/openapi.yaml'),
        'utf8',
      ),
    ) as { components: { parameters: { IdempotencyKey: { schema: { maxLength: number } } } } };

    expect(spec.components.parameters.IdempotencyKey.schema.maxLength)
      .toBe(MAX_IDEMPOTENCY_KEY);
  });
});

describe('concurrency', () => {
  it('reads lockVersion off a resource that was just read', () => {
    expect(versionOf({ id: 'x', lockVersion: 4 }, 'update case')).toEqual({ version: 4 });
    expect(ifMatchFor({ version: 4 }, 'update case')).toBe('4');
  });

  it('sends If-Match for version 0', () => {
    expect(ifMatchFor({ version: 0 }, 'update case')).toBe('0');
  });

  it('refuses to write when the read carried no version, rather than silently clobbering', () => {
    expect(() => versionOf({ id: 'x' }, 'update case')).toThrow(/last-write-wins/);
  });

  it('lets a state-preconditioned transition declare that it has none', () => {
    // `PUT …/runs/{id}/status` carries no lockVersion by contract — a different thing from
    // forgetting, and a tool description has to be able to say which.
    expect(ifMatchFor({ none: 'state-preconditioned' }, 'close run')).toBeUndefined();
  });

  it('recognises the conflict an agent must re-read after', () => {
    expect(isVersionConflict(new TcmsApiError({
      status: 409, detail: 'x', method: 'PATCH', path: '/',
    }))).toBe(true);
    expect(isVersionConflict(new TcmsApiError({
      status: 403, detail: 'x', method: 'PATCH', path: '/',
    }))).toBe(false);
  });
});

describe('refusal copy', () => {
  const operation = { what: 'approve this case', permission: 'case:approve' };

  it('names the missing permission on a 403, which is past the read gate', () => {
    const copy = describeRefusal(
      new TcmsApiError({ status: 403, detail: 'x', method: 'POST', path: '/' }),
      operation,
    );

    expect(copy.namesPermission).toBe(true);
    expect(copy.message).toContain('case:approve');
  });

  /*
    The one test whose failure is a security finding rather than a defect (spec §8.4). A 404 is
    "no such thing, or you cannot see it" and the two must stay indistinguishable, or this
    surface becomes the enumeration oracle §6t exists to prevent.
  */
  it('says nothing about permissions or roles on a 404', () => {
    const copy = describeRefusal(
      new TcmsApiError({ status: 404, detail: 'x', method: 'GET', path: '/' }),
      operation,
    );

    expect(copy.namesPermission).toBe(false);
    expect(copy.message).not.toMatch(/case:approve|permission|Viewer|Test Lead|Test Engineer|Admin/);
  });

  it('says nothing about permissions when the transport failed', () => {
    const copy = describeRefusal(new Error('network down'), operation);
    expect(copy.namesPermission).toBe(false);
    expect(copy.message).not.toContain('case:approve');
  });

  it('does not assert a project exists when the permission answer was empty', () => {
    // `GET /me/permissions` answers [] identically for another company's project and for one
    // that does not exist, so "you hold nothing here" would assert that "here" is somewhere.
    const summary = describeReach([], true);
    expect(summary).toMatch(/does not exist, or this token cannot see it/);
  });
});

describe('paging', () => {
  it('defaults to 25 and clamps a nonsense size', () => {
    expect(clampPageSize(undefined)).toBe(DEFAULT_PAGE_SIZE);
    expect(clampPageSize(0)).toBe(DEFAULT_PAGE_SIZE);
    expect(clampPageSize(10_000)).toBe(200);
    expect(clampPageSize(50)).toBe(50);
  });

  it('knows when a page has more after it', () => {
    expect(hasMore({ page: 0, size: 25, total: 30 })).toBe(true);
    expect(hasMore({ page: 1, size: 25, total: 30 })).toBe(false);
  });

  it('walks pages up to the caller-stated limit', async () => {
    const pages = [
      { items: [1, 2, 3], meta: { page: 0, size: 3, total: 7 } },
      { items: [4, 5, 6], meta: { page: 1, size: 3, total: 7 } },
      { items: [7], meta: { page: 2, size: 3, total: 7 } },
    ];

    const seen: number[] = [];
    for await (const item of eachItem((page) => Promise.resolve(pages[page]!), 5, 3)) {
      seen.push(item);
    }

    expect(seen).toEqual([1, 2, 3, 4, 5]);
  });

  it('stops on an empty page even when total claims more, rather than looping forever', async () => {
    const seen: number[] = [];
    for await (const item of eachItem(
      () => Promise.resolve({ items: [] as number[], meta: { page: 0, size: 25, total: 999 } }),
      100,
    )) {
      seen.push(item);
    }

    expect(seen).toEqual([]);
  });
});

describe('resolveId', () => {
  it('returns a UUID untouched, without a request', async () => {
    const stub = stubFetch();
    const id = await resolveId({
      client: client(stub.fetch), projectId: 'p-1', value: UUID, entity: 'CASE',
    });

    expect(id).toBe(UUID);
    expect(stub.calls).toHaveLength(0);
  });

  it('resolves a case key through the q short-circuit', async () => {
    const stub = stubFetch({
      status: 200,
      body: { page: 0, size: 1, total: 1, facets: [], items: [{ id: 'c-1', key: 'TC-42' }] },
    });

    const id = await resolveId({
      client: client(stub.fetch), projectId: 'p-1', value: 'TC-42', entity: 'CASE',
    });

    expect(id).toBe('c-1');
    expect(stub.last.url).toContain('q=TC-42');
  });

  it('resolves a suite key from the unpaginated list', async () => {
    const stub = stubFetch({
      status: 200,
      body: [{ id: 's-1', key: 'TS-1' }, { id: 's-7', key: 'TS-7' }],
    });

    const id = await resolveId({
      client: client(stub.fetch), projectId: 'p-1', value: 'TS-7', entity: 'SUITE',
    });

    expect(id).toBe('s-7');
  });

  it('does not distinguish "no such key" from "not visible to you"', async () => {
    const stub = stubFetch({ status: 200, body: [] });

    await expect(resolveId({
      client: client(stub.fetch), projectId: 'p-1', value: 'TS-7', entity: 'SUITE',
    })).rejects.toThrow(/No suite TS-7, or it is not visible\./);
  });
});

describe('resolveProject', () => {
  const projects = [
    { id: 'p-1', key: 'OLB', name: 'Online Banking' },
    { id: 'p-2', key: 'PAY', name: 'Payments' },
  ];

  it('infers the project when exactly one is visible', async () => {
    const stub = stubFetch({ status: 200, body: [projects[0]] });
    await expect(resolveProject({ client: client(stub.fetch) })).resolves.toMatchObject({
      id: 'p-1', key: 'OLB',
    });
  });

  it('requires --project when more than one is visible', async () => {
    const stub = stubFetch({ status: 200, body: projects });
    await expect(resolveProject({ client: client(stub.fetch) }))
      .rejects.toThrow(/needs --project. One of: OLB, PAY/);
  });

  it('matches --project on the key a person types, case-insensitively', async () => {
    const stub = stubFetch({ status: 200, body: projects });
    await expect(resolveProject({ client: client(stub.fetch), requested: 'pay' }))
      .resolves.toMatchObject({ id: 'p-2' });
  });

  it('matches --project on a UUID too', async () => {
    const stub = stubFetch({ status: 200, body: projects });
    await expect(resolveProject({ client: client(stub.fetch), requested: 'p-2' }))
      .resolves.toMatchObject({ key: 'PAY' });
  });

  it('lists what is visible when the requested project is not', async () => {
    const stub = stubFetch({ status: 200, body: projects });
    await expect(resolveProject({ client: client(stub.fetch), requested: 'XYZ' }))
      .rejects.toThrow(/Visible: OLB, PAY/);
  });

  it('explains an empty list rather than reporting "not found"', async () => {
    const stub = stubFetch({ status: 200, body: [] });
    await expect(resolveProject({ client: client(stub.fetch) }))
      .rejects.toThrow(/can see no projects/);
  });
});
