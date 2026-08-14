import { readFileSync } from 'node:fs';
import { dirname, resolve as resolvePath } from 'node:path';
import { fileURLToPath } from 'node:url';

import { parse } from 'yaml';
import { describe, expect, it } from 'vitest';

import { createTcmsClient } from '../client.js';
import { TcmsApiError, TcmsClientError } from '../errors.js';
import { casePage, caseSummary, project, suite } from '../test/fixtures.js';
import { stubFetch } from '../test/stubFetch.js';
import { ifMatchFor, isVersionConflict, versionOf } from './concurrency.js';
import { KEY_PREFIXES, classify, isUuid, parseDisplayKey } from './identifiers.js';
import { MAX_IDEMPOTENCY_KEY, newIntentKey, requireIntentKey } from './idempotency.js';
import { createIntent, editIntent, requireExplicitVersion } from './mutation.js';
import { REQUIRED_CASE_FIELDS, requireProposableCase } from './proposal.js';
import { caseInputFrom, planInputFrom, suiteInputFrom } from './rewrite.js';
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

describe('mutation intents', () => {
  it('generates a key per create and validates one the caller supplied', () => {
    expect(createIntent().idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(createIntent({ key: '  batch-7  ' }).idempotencyKey).toBe('batch-7');
    expect(() => createIntent({ key: 'x'.repeat(MAX_IDEMPOTENCY_KEY + 1) })).toThrow(/at most/);
  });

  /*
    §204. The 200 arm of a create is the contract's way of saying the key had already done this and
    nothing was allocated by this request, and until this rung no consumer in `packages/` could read
    a success status at all.
  */
  it('reads a 200 on a create as a replay and a 201 as the create', () => {
    const created = createIntent();
    const replayed = createIntent();

    // Before the answer, and it must not be `undefined`: an intent that has not been carried out
    // has not been replayed either, and a caller printing a word wants a boolean.
    expect(created.wasReplay()).toBe(false);

    created.observeStatus?.(201);
    replayed.observeStatus?.(200);

    expect(created.wasReplay()).toBe(false);
    expect(replayed.wasReplay()).toBe(true);
  });

  /*
    Every create arm writes `tcms: { ...request, ...createIntent(…) }`, so the answer has to survive
    being spread into a fresh object. It does because the hook is a closure over the intent rather
    than a field on it — a field would be written onto the copy and read off the original.
  */
  it('still reports the replay when the options were spread into another object', () => {
    const intent = createIntent({ key: 'batch-7' });
    const request = { timeoutClass: 'interactive' as const };
    const sent = { ...request, ...intent };

    sent.observeStatus?.(200);

    expect(intent.wasReplay()).toBe(true);
    expect(sent.idempotencyKey).toBe('batch-7');
  });

  it('sends If-Match from an explicit version and from a fresh read alike', () => {
    expect(editIntent({ explicit: 3 }, 'rename suite').ifMatch).toBe('3');
    expect(editIntent({ fresh: { lockVersion: 3 } }, 'rename suite').ifMatch).toBe('3');
  });

  it('refuses an edit whose fresh read carried no version', () => {
    expect(() => editIntent({ fresh: { id: 'x' } }, 'rename suite')).toThrow(/last-write-wins/);
  });

  /*
    The slice's finding, asserted rather than commented. A whole-body edit's precondition is the
    version of the read its body came from, and reading a fresh one here would send an If-Match
    that matches a state the body was never based on — last-write-wins arriving through the
    header that prevents it. So the refusal is the behaviour, not a missing convenience.
  */
  it('refuses a whole-body edit that names no version, and says why', () => {
    expect(() => requireExplicitVersion(undefined, 'edit case from a file'))
      .toThrow(/silently overwrites whoever saved in between/);
  });

  it('takes a version of zero, which is a real version and a falsy number', () => {
    expect(requireExplicitVersion(0, 'edit case from a file')).toEqual({ explicit: 0 });
    expect(() => requireExplicitVersion(1.5, 'edit case from a file')).toThrow(/whole number/);
  });
});

/*
  §143's second defect, and the §48 rule applied to it: the three projections are a list of
  fields, so what has to be checked is the LIST, not each entry. A field added to `TestCaseInput`
  next year is either carried by the round-trip or deliberately excluded, and until somebody says
  which, this fails the build. Reading the projection carefully is what stopped being enough the
  moment the contract and the server disagreed for a year.
*/
describe('read-modify-write', () => {
  const spec = parse(
    readFileSync(
      resolvePath(dirname(fileURLToPath(import.meta.url)), '../../../../docs/specs/openapi.yaml'),
      'utf8',
    ),
  ) as { components: { schemas: Record<string, { properties: Record<string, unknown> }> } };

  const propertiesOf = (schema: string): string[] =>
    Object.keys(spec.components.schemas[schema]?.properties ?? {});

  const CASE = {
    title: 'Login works',
    suiteId: 's-1',
    description: 'Prose.',
    objective: null,
    expectedOutcome: null,
    stepFormat: 'STRUCTURED' as const,
    gherkinSource: null,
    priority: 'HIGH' as const,
    severity: 'LOW' as const,
    automationStatus: 'MANUAL' as const,
    automationKey: null,
    automationTags: ['@smoke'],
    notAutomatableReason: null,
    estimatedDurationSec: 90,
    ownerId: 'u-1',
    classificationValueIds: ['cv-1'],
    tags: ['smoke'],
    steps: [{
      id: 'st-1', position: 1, keyword: null, action: 'Sign in',
      testData: null, expectedResult: 'In.', sharedStepId: null,
    }],
  } as unknown as Parameters<typeof caseInputFrom>[0];

  it('carries every field of TestCaseInput a round-trip may send, and no other', () => {
    /*
      Each exclusion is a decision with a consequence, so each is named rather than filtered by
      shape. `ownerId` and `automationTags` are REFUSED by the PATCH with a 400 while being served
      by the GET, so echoing them turns every round-trip into a rejection. The conditions are
      lists, so absent leaves them alone, and the read serves a different schema from the write.
      `changeComment` is this edit's own annotation and belongs to nobody's previous save.

      `declinedConditionIds` (§187) is excluded on the weaker half of the conditions' argument and
      the exclusion is therefore the one to revisit first: it is a list, so absent leaves it alone,
      but unlike the two above its shape DOES line up with what the read serves. This assertion
      failed the moment the field appeared, which is the second time it has forced the decision to
      be made rather than defaulted — §186 was the first.

      **§189 revisited it and the exclusion stands**, which is the outcome worth recording because
      the sentence above predicted a reversal. Both callers grew a flag for the field, and a flag
      is a delta: it says what the caller asked for. This function says what an unchanged save
      sends, and for a list that is nothing. The two were only ever the same question if the
      projection is read as "the resource", which is what `suiteInputFrom` calls a base body and
      what the two exclusions above prove it is not.
    */
    const excluded = [
      'ownerId', 'automationTags', 'preconditions', 'postconditions', 'declinedConditionIds',
      'changeComment',
    ];
    const expected = propertiesOf('TestCaseInput').filter((field) => !excluded.includes(field));

    expect(expected.length).toBeGreaterThan(10);
    expect(Object.keys(caseInputFrom(CASE)).sort()).toEqual(expected.sort());
  });

  it('projects a step WITH its id, because an id-less step is a new step', () => {
    // Dropping the ids on a round-trip deletes every step and recreates it, which looks like
    // nothing happened and breaks every stepResult pinned to the old ones.
    expect(caseInputFrom(CASE).steps?.[0]?.id).toBe('st-1');
    // And not `position`: it is the array's order, and `TestStepInput` has no such field.
    expect(caseInputFrom(CASE).steps?.[0]).not.toHaveProperty('position');
  });

  it('omits an unset severity rather than sending a null the input cannot type', () => {
    // `TestCase.severity` is nullable and `TestCaseInput.severity` is not, so "unset" has no
    // spelling on the write and an absent scalar is what the server reads as null.
    const unset = { ...CASE, severity: null } as unknown as Parameters<typeof caseInputFrom>[0];
    expect(caseInputFrom(unset)).not.toHaveProperty('severity');
    expect(caseInputFrom(CASE).severity).toBe('LOW');
  });

  it('carries every field of SuiteInput a round-trip may send, and no other', () => {
    /*
      The two condition lists are excluded on `caseInputFrom`'s grounds exactly: they are lists, so
      absent leaves them alone, and the read serves `CaseConditionRef` where the write takes
      `CaseConditionInput`. §186 added them to `SuiteInput` — and this assertion is what forced the
      decision to be made rather than defaulted, because it FAILED the moment the field appeared.
      That is the whole reason it is written as "every field" rather than "these four".

      §189 gave both callers `--precondition` / `--postcondition`, so the sentence this comment
      used to carry — "what a CLI cannot yet do is change them" — is gone from `rewrite.ts`. The
      exclusion is not: the change arrives in the delta, where the caller's own library ids are,
      and a projection would still have nothing to build a `conditionId` from on an inline row.
    */
    const excluded = ['preconditions', 'postconditions'];
    const expected = propertiesOf('SuiteInput').filter((field) => !excluded.includes(field));

    const suite = {
      id: 's-1', key: 'TS-7', name: 'Smoke', description: null, caseCount: 0,
      tagRules: ['smoke'], caseIds: ['c-1'], lifecycleStatus: 'DRAFT',
      preconditions: [], postconditions: [],
      lockVersion: 2,
    } as unknown as Parameters<typeof suiteInputFrom>[0];

    expect(expected.length).toBeGreaterThan(3);
    expect(Object.keys(suiteInputFrom(suite)).sort()).toEqual(expected.sort());
  });

  it('carries every SCALAR of TestPlanUpdateInput and none of its lists', () => {
    /*
      The split is the server's: `PlanEdit.validateUpdate` resolves an absent list against what is
      stored and writes every scalar straight through. So the lists are genuinely partial here and
      the scalars are genuinely not — and `scopeText`/`outOfScopeText`, which no flag and no tool
      argument can reach, were emptied by EVERY plan edit either client ever made.
    */
    const lists = ['suiteIds', 'caseIds', 'tags', 'sections', 'entryCriteria', 'exitCriteria'];
    const scalars = propertiesOf('TestPlanUpdateInput').filter((field) => !lists.includes(field));

    const plan = {
      id: 'tp-1', key: 'TP-1', name: 'Release 4.2', status: 'DRAFT',
      milestoneName: 'GA', milestoneDate: '2026-09-01', caseCount: 0, passRate: null,
      coverage: null, description: null, entryCriteria: [], exitCriteria: [],
      configurations: [], suiteIds: [], caseIds: [], tags: [], sections: [],
      scopeText: 'What we will test.', outOfScopeText: null,
      currentVersionNo: 1, lockVersion: 7,
    } as unknown as Parameters<typeof planInputFrom>[0];

    expect(Object.keys(planInputFrom(plan)).sort()).toEqual(scalars.sort());
    expect(planInputFrom(plan).scopeText).toBe('What we will test.');
  });

  it('agrees with the contract about which four fields are required', () => {
    const required = (spec.components.schemas['TestCaseInput'] as unknown as { required: string[] })
      .required;
    expect([...REQUIRED_CASE_FIELDS].sort()).toEqual([...required].sort());
  });
});

/*
  The proposal check exists because the FILE-TIME endpoint validates nothing by design and the
  create rules run at accept — so a proposal missing a required field is filed 201, sits PENDING,
  and refuses the reviewer's accept on fields they cannot edit. §143 drove that whole path.
*/
describe('proposable cases', () => {
  const complete = {
    title: 'Login works',
    stepFormat: 'STRUCTURED',
    priority: 'HIGH',
    automationStatus: 'MANUAL',
    steps: [{ action: 'Sign in' }],
  };

  it('accepts a case the accept would take', () => {
    expect(() => requireProposableCase(complete, 'That case')).not.toThrow();
  });

  it('names every missing field at once, never the first', () => {
    // A model that fixes one field and refiles, three times, has left a person three proposals
    // to reject.
    let message = '';
    try {
      requireProposableCase({ title: 'Login works' }, 'That case');
    } catch (error) {
      message = (error as Error).message;
    }
    expect(message).toContain('stepFormat');
    expect(message).toContain('priority');
    expect(message).toContain('automationStatus');
  });

  it('reads an empty steps array as no steps, the way the server does', () => {
    expect(() => requireProposableCase({ ...complete, steps: [] }, 'That case'))
      .toThrow(/steps/);
  });

  it('asks for the content each format and automation status actually needs', () => {
    expect(() => requireProposableCase(
      { ...complete, stepFormat: 'GHERKIN', steps: undefined }, 'That case',
    )).toThrow(/gherkinSource/);

    expect(() => requireProposableCase(
      { ...complete, automationStatus: 'AUTOMATED' }, 'That case',
    )).toThrow(/automationKey/);

    expect(() => requireProposableCase(
      { ...complete, automationStatus: 'NOT_AUTOMATABLE' }, 'That case',
    )).toThrow(/notAutomatableReason/);
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

  /*
    The 429 branch, and the reason it is a branch at all: a rate limit whose refusal does not say
    when to retry produces the loop the limit exists to prevent. Before this existed a 429 fell
    through to `default:` and read as an unexplained failure — and an unexplained failure is the
    one a model retries.
  */
  it('turns a 429 into a wait, taken from Retry-After rather than from the prose', () => {
    const copy = describeRefusal(
      new TcmsApiError({
        status: 429, detail: 'server prose', method: 'GET', path: '/', retryAfter: '10',
      }),
      operation,
    );

    expect(copy.namesPermission).toBe(false);
    expect(copy.message).toContain('Wait 10 seconds');
    // The half a timed-out write cannot be told: the refusal is written before routing, so there
    // is no handler that half-ran and the same call may simply be repeated.
    expect(copy.message).toContain('Nothing was changed');
  });

  it('does not invent a wait the server did not state', () => {
    const copy = describeRefusal(
      new TcmsApiError({ status: 429, detail: 'x', method: 'GET', path: '/' }),
      operation,
    );

    // A number here would be obeyed, which makes an invented one worse than none.
    expect(copy.message).not.toMatch(/Wait \d/);
    expect(copy.message).toContain('Wait before trying again');
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
      body: casePage([caseSummary({ id: 'c-1', key: 'TC-42' })], { size: 1 }),
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
      body: [suite({ id: 's-1', key: 'TS-1' }), suite({ id: 's-7', key: 'TS-7' })],
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
    project({ id: 'p-1', key: 'OLB', name: 'Online Banking' }),
    project({ id: 'p-2', key: 'PAY', name: 'Payments' }),
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
