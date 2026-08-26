/**
 * The read tools, asserted on **what they sent** as much as on what they answered.
 *
 * The first half is not ceremony. §139 found that every string flag on every CLI read command was
 * being dropped — the request was legal, the response was real, and only the filter was missing,
 * so the command answered the unfiltered list and exited 0. Nothing about that failure is
 * specific to `parseArgs`: a tool that reads `input['suite']` and forgets to put it in the query
 * fails the same way, silently, and hands a model a list it will treat as the answer. So every
 * filter here is asserted against the query string that left.
 */
import { describe, expect, it } from 'vitest';

import {
  caseDetail,
  caseHistory,
  casePage,
  caseStatus,
  caseSummary,
  dashboard,
  dimension,
  suite,
  workItem,
} from '../../client/src/test/fixtures.js';
import { READ_TOOLS } from './reads.js';
import type { ToolDefinition } from './tool.js';
import { PROJECT, agentRoutes, api, sessionWith, withApi, type Api } from './test/harness.js';

function tool(name: string): ToolDefinition {
  const found = READ_TOOLS.find((candidate) => candidate.name === name);
  if (!found) throw new Error(`No read tool ${name}`);
  return found;
}

async function run(
  stub: Api,
  name: string,
  input: Record<string, unknown> = {},
): Promise<Record<string, unknown>> {
  const session = await sessionWith(stub);
  return await withApi(stub, () => tool(name).run(session, input)) as Record<string, unknown>;
}

/** The query string of the last request whose path ends with `suffix`. */
function queryOf(stub: Api, suffix: string): URLSearchParams {
  const sent = [...stub.sent].reverse().find((request) => request.path.endsWith(suffix));
  if (!sent) throw new Error(`No request to a path ending '${suffix}'`);
  return new URLSearchParams(sent.search);
}

/*
  `stepFormat` was ~~`STEPS`~~ until §203. `StepFormat` is `STRUCTURED | GHERKIN` and has been since
  the schema was written; the shorter word was never a member and eight tests served it.
*/
const CASE_ROW = caseSummary({
  title: 'Login with a valid password',
  suiteName: 'Authentication',
  lifecycleStatus: 'APPROVED',
  automationStatus: 'AUTOMATED',
  tags: ['smoke'],
  updatedAt: '2026-08-01T10:00:00Z',
});

const SUITE_ROW = suite({
  key: 'TS-7',
  name: 'Authentication',
  caseCount: 12,
  lifecycleStatus: 'APPROVED',
  lockVersion: 3,
});

describe('tcms_orient', () => {
  it('answers the project shape from five reads in one call', async () => {
    const stub = api(agentRoutes({
      '/projects/p-1': { status: 200, body: { ...PROJECT, description: null } },
      '/classification': {
        status: 200,
        body: [dimension({
          id: 'd-1', key: 'feature', name: 'Feature', cardinality: 'MULTI', hierarchical: true,
          values: [{ id: 'v-1', parentId: null, name: 'Login', path: 'Access/Login', color: null }],
        })],
      },
      '/statuses': {
        status: 200,
        body: [caseStatus({ id: 'st-1', color: '#0a0' })],
      },
      '/suites': { status: 200, body: [SUITE_ROW] },
      '/tags': { status: 200, body: [{ tag: 'smoke', caseCount: 12 }] },
    }));

    const answer = await run(stub, 'tcms_orient');

    expect(answer['suites']).toEqual([
      { key: 'TS-7', name: 'Authentication', caseCount: 12, lifecycleStatus: 'APPROVED' },
    ]);
    // The path, not the bare name: two values called "Login" under different parents are two
    // different things, and a case naming one of them has to be able to say which.
    expect(answer['classification']).toEqual([
      { name: 'Feature', cardinality: 'MULTI', hierarchical: true, values: ['Access/Login'] },
    ]);
    expect(answer['tags']).toEqual(['smoke (12)']);
  });
});

describe('tcms_search_cases', () => {
  it('puts every filter it was given into the query', async () => {
    const stub = api(agentRoutes({
      '/suites': { status: 200, body: [SUITE_ROW] },
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 25 }) },
    }));

    await run(stub, 'tcms_search_cases', {
      q: 'login', suite: 'TS-7', tag: ['smoke', 'slow'], unfiled: false,
      lifecycleStatus: 'APPROVED', priority: 'HIGH', size: 10,
    });

    const query = queryOf(stub, '/cases');
    expect(query.get('q')).toBe('login');
    // Resolved through the core, so the tool takes the key a person and a model both use and the
    // server takes the id it requires.
    expect(query.get('suiteId')).toBe('s-1');
    expect(query.getAll('tag')).toEqual(['smoke', 'slow']);
    expect(query.get('lifecycleStatus')).toBe('APPROVED');
    expect(query.get('priority')).toBe('HIGH');
    expect(query.get('size')).toBe('10');
  });

  it('sends no filter it was not given', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([], { size: 25 }) },
    }));

    await run(stub, 'tcms_search_cases', {});

    const query = queryOf(stub, '/cases');
    expect(query.has('q')).toBe(false);
    expect(query.has('unfiled')).toBe(false);
    // The two the surface always states, because a page nobody bounded is the 25K-token failure.
    expect(query.get('page')).toBe('0');
    expect(query.get('size')).toBe('25');
  });

  it('reports whether more matched than came back', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 25, total: 812 }) },
    }));

    const answer = await run(stub, 'tcms_search_cases', {});
    expect(answer['total']).toBe(812);
    expect(answer['hasMore']).toBe(true);
  });

  it('flattens a health signal to its code, keeping the order the server sent', async () => {
    // The wire carries {signal, severity} pairs since §179 and this surface sends the codes: the
    // severity is a word per signal per case for a ranking the ORDER already carries (the server
    // sorts worst first), and this block's whole rule is that a field costing tokens on every case
    // has to earn them. The failure it guards is the one §140 found on `whoami` — an object where a
    // string was meant reaches a model as `[object Object]` and nothing throws.
    const stub = api(agentRoutes({
      '/cases': {
        status: 200,
        body: casePage([caseSummary({
          ...CASE_ROW,
          healthSignals: [
            { signal: 'STALE_AUTOMATION', severity: 'ERROR' },
            { signal: 'UNLINKED', severity: 'INFO' },
          ],
        })], { size: 25 }),
      },
    }));

    const answer = await run(stub, 'tcms_search_cases', {});
    expect((answer['items'] as { healthSignals: string[] }[])[0]?.healthSignals)
      .toEqual(['STALE_AUTOMATION', 'UNLINKED']);
  });
});

describe('tcms_open_case', () => {
  it('reads only the detail unless history or versions were asked for', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      '/cases/c-1': { status: 200, body: caseDetail({ ...CASE_ROW, lockVersion: 4 }) },
    }));

    const answer = await run(stub, 'tcms_open_case', { case: 'TC-42' });

    expect(answer['lockVersion']).toBe(4);
    expect(answer['history']).toBeUndefined();
    expect(stub.sent.some((request) => request.path.endsWith('/history'))).toBe(false);
  });

  it('fans out when they were', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      '/cases/c-1': { status: 200, body: caseDetail({ ...CASE_ROW, lockVersion: 4 }) },
      '/history': { status: 200, body: caseHistory() },
      '/versions': { status: 200, body: [] },
    }));

    const answer = await run(stub, 'tcms_open_case', { case: 'TC-42', history: true, versions: true });

    expect(answer['history']).toBeDefined();
    expect(answer['versions']).toBeDefined();
  });

  it('is one of exactly two tools allowed above the default result ceiling', () => {
    const raised = READ_TOOLS.filter((candidate) => candidate.meta?.['anthropic/maxResultSizeChars']);
    expect(raised.map((candidate) => candidate.name).sort())
      .toEqual(['tcms_open_case', 'tcms_open_doc_page']);
  });
});

describe('the four lists the contract does not paginate', () => {
  it('windows them client-side and says how many there were', async () => {
    const rows = Array.from({ length: 60 }, (_, index) =>
      suite({ ...SUITE_ROW, id: `s-${index}`, key: `TS-${index}` }));
    const stub = api(agentRoutes({ '/suites': { status: 200, body: rows } }));

    const answer = await run(stub, 'tcms_list_suites', { size: 10, page: 1 }) as {
      items: unknown[]; total: number; truncated: boolean;
    };

    expect(answer.items).toHaveLength(10);
    expect(answer.total).toBe(60);
    // The honest field: the *server* sent all sixty, and this bounded what the model sees. A
    // `more`-style flag would imply a next page the endpoint does not have.
    expect(answer.truncated).toBe(true);
  });
});

describe('tcms_triage', () => {
  it('answers the dashboard and the quarantined cases, and asks for no more than that', async () => {
    const stub = api(agentRoutes({
      '/dashboard': { status: 200, body: dashboard() },
      '/work-items': {
        status: 200,
        body: [
          workItem({ id: 'w-1', kind: 'QUARANTINE', title: 'TC-42 is quarantined', description: 'Flaky since 2026-07' }),
          workItem({ id: 'w-2', kind: 'REVIEW', title: 'Review TS-7', description: '' }),
        ],
      },
    }));

    const answer = await run(stub, 'tcms_triage', {});

    expect(answer['dashboard']).toBeDefined();
    expect(answer['quarantined']).toEqual([
      { title: 'TC-42 is quarantined', description: 'Flaky since 2026-07', status: 'TODO' },
    ]);
    expect(answer['ingestions']).toBeUndefined();
    expect(stub.sent.some((request) => request.path.endsWith('/ingestions'))).toBe(false);
  });
});

describe('the read surface as a whole', () => {
  it('declares no write arms — a read tool that grew one would have to say so', () => {
    for (const candidate of READ_TOOLS) expect(candidate.arms).toBeUndefined();
  });

  it('takes no project or company argument, per §4.1', () => {
    for (const candidate of READ_TOOLS) {
      expect(Object.keys(candidate.inputSchema.properties)).not.toContain('projectId');
      expect(Object.keys(candidate.inputSchema.properties)).not.toContain('companyId');
    }
  });
});
