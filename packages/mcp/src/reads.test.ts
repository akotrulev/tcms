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
  backlogRow,
  board,
  boardCard,
  boardContents,
  boardIssueCard,
  boardLane,
  boardSummary,
  label,
  userRef,
  wipBreach,
  issue,
  issueComment,
  issueDocLink,
  issueStatus,
  issueSummary,
  issueType,
  readinessSummary,
  releaseReadiness,
  releaseSummary,
  releaseTests,
  roadmap,
  roadmapEpic,
  roadmapRelease,
  suite,
} from '../../client/src/test/fixtures.js';
import { roleMatrix } from '../../client/src/test/roleMatrix.js';
import { READ_TOOLS } from './reads.js';
import { isListed, type ToolDefinition } from './tool.js';
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

describe('tcms_read_issue', () => {
  const REVIEWING = issueStatus({ id: 'is-3', name: 'In Review', kind: 'IN_PROGRESS', position: 2 });
  const DONE = issueStatus({ id: 'is-4', name: 'Done', kind: 'DONE', position: 3 });

  it('sends every filter it was given, so a narrowed ask is not answered with the whole list', async () => {
    const stub = api(agentRoutes({ 'GET /issues': { status: 200, body: [issueSummary()] } }));

    await run(stub, 'tcms_read_issue', {
      level: 'STORY', epic: 'ACME-3', parent: 'ACME-12', unparented: true, sort: 'dueDate',
    });

    const query = queryOf(stub, '/issues');
    expect(query.get('projectId')).toBe(PROJECT.id);
    expect(query.get('level')).toBe('STORY');
    expect(query.get('epicKey')).toBe('ACME-3');
    expect(query.get('parentKey')).toBe('ACME-12');
    expect(query.get('unparented')).toBe('true');
    expect(query.get('sort')).toBe('dueDate');
  });

  it('lists through the one envelope, with the type and status as names', async () => {
    const stub = api(agentRoutes({
      'GET /issues': {
        status: 200,
        body: [issueSummary({ key: 'ACME-7', type: issueType({ name: 'Bug' }), status: REVIEWING })],
      },
    }));

    const answer = await run(stub, 'tcms_read_issue', {});

    expect(answer['total']).toBe(1);
    expect(answer['hasMore']).toBe(false);
    expect((answer['items'] as Record<string, unknown>[])[0])
      .toMatchObject({ key: 'ACME-7', type: 'Bug', status: 'In Review', statusKind: 'IN_PROGRESS' });
  });

  it('spells allowedTransitions as the NAMES the write tool takes', async () => {
    /*
      The whole point of the read. `IssueStatusRef` carries a UUID nothing on this surface accepts,
      and `tcms_write_issue move` takes a name — handing back the objects would make the model
      translate between two spellings of one thing, which is the step it gets wrong.
    */
    const stub = api(agentRoutes({
      'GET /issues/ACME-124': {
        status: 200,
        body: issue({ allowedTransitions: [REVIEWING, DONE], watchers: [] }),
      },
    }));

    const answer = await run(stub, 'tcms_read_issue', { issue: 'ACME-124' });

    expect(answer['allowedTransitions']).toEqual(['In Review', 'Done']);
  });

  it('reads the comments and the spec links only when they were asked for', async () => {
    const routes = {
      'GET /issues/ACME-124': { status: 200, body: issue() },
      'GET /issues/ACME-124/comments': { status: 200, body: [issueComment()] },
      'GET /issues/ACME-124/doc-links': { status: 200, body: [issueDocLink()] },
    };

    const quiet = api(agentRoutes(routes));
    const bare = await run(quiet, 'tcms_read_issue', { issue: 'ACME-124' });
    expect(bare['comments']).toBeUndefined();
    expect(quiet.sent.some((request) => request.path.endsWith('/comments'))).toBe(false);

    const asked = api(agentRoutes(routes));
    const full = await run(asked, 'tcms_read_issue', {
      issue: 'ACME-124', comments: true, docLinks: true,
    });
    expect(full['comments']).toHaveLength(1);
    expect(full['docLinks']).toHaveLength(1);
  });
});

describe('tcms_triage', () => {
  it('answers the dashboard and the quarantined cases, and asks for no more than that', async () => {
    const stub = api(agentRoutes({
      '/dashboard': { status: 200, body: dashboard() },
      // ~~`/work-items`~~ → `/board` since 011 M1. The path named a table that no longer exists;
      // quarantine still comes off the board because §121's cards are generated from
      // `case_flake`'s authored half and there is no quarantined-cases endpoint to read instead.
      '/board': {
        status: 200,
        body: [
          boardCard({ id: 'ACME-9', kind: 'QUARANTINE', title: 'TC-42 is quarantined', description: 'Flaky since 2026-07' }),
          boardCard({ id: 'ACME-8', kind: 'REVIEW', title: 'Review TS-7', description: '' }),
        ],
      },
    }));

    const answer = await run(stub, 'tcms_triage', {});

    expect(answer['dashboard']).toBeDefined();
    expect(answer['quarantined']).toEqual([
      { title: 'TC-42 is quarantined', description: 'Flaky since 2026-07', status: 'To Do' },
    ]);
    expect(answer['ingestions']).toBeUndefined();
    expect(stub.sent.some((request) => request.path.endsWith('/ingestions'))).toBe(false);
  });
});

describe('tcms_read_board', () => {
  const BOARDS = {
    'GET /boards': { status: 200, body: [boardSummary()] },
    'GET /boards/bd-1': { status: 200, body: board() },
  } as const;

  it('lists the project\'s boards when nothing is named', async () => {
    const stub = api(agentRoutes(BOARDS));

    const answer = await run(stub, 'tcms_read_board', {});

    expect(answer['items']).toEqual([
      { id: 'bd-1', name: 'Team board', columnCount: 3, swimlaneBy: 'NONE' },
    ]);
    expect(stub.sent.some((request) => request.path.endsWith('/cards'))).toBe(false);
  });

  it('puts the cards inside their columns, named, and never sends a status id', async () => {
    const stub = api(agentRoutes({
      ...BOARDS,
      'GET /boards/bd-1/cards': {
        status: 200,
        body: boardContents({
          lanes: [boardLane({
            cards: [boardIssueCard({ columnId: 'bc-2', status: issueStatus({ id: 'is-2', name: 'In Progress', kind: 'IN_PROGRESS', position: 1 }) })],
          })],
          breaches: [wipBreach()],
        }),
      },
    }));

    const answer = await run(stub, 'tcms_read_board', { board: 'team board' });

    // The column's NAME on the card, so the model reading a board does not have to join a
    // `columnId` against a list it was handed separately.
    expect(answer['lanes']).toMatchObject([{ cards: [{ key: 'ACME-124', column: 'In flight' }] }]);
    expect(answer['breaches']).toEqual([{ column: 'In flight', limit: 2, count: 3 }]);
    expect(JSON.stringify(answer)).not.toContain('is-2');
  });

  it('resolves label and assignee NAMES to the ids the filter takes', async () => {
    const stub = api(agentRoutes({
      ...BOARDS,
      'GET /labels': { status: 200, body: [label()] },
      'GET /members': { status: 200, body: [userRef()] },
      'GET /boards/bd-1/cards': { status: 200, body: boardContents() },
    }));

    await run(stub, 'tcms_read_board', { board: 'Team board', label: ['Flaky'], assignee: ['Ana Engineer'] });

    const query = queryOf(stub, '/boards/bd-1/cards');
    expect(query.getAll('labelId')).toEqual(['lb-1']);
    expect(query.getAll('assigneeId')).toEqual(['u-1']);
  });

  it('refuses a label the project does not have rather than filtering by nothing', async () => {
    /*
      The failure this guards is silent: an unresolved name sent as a filter matches no row, and an
      empty board is exactly what a model reads as "there is no work here".
    */
    const stub = api(agentRoutes({ ...BOARDS, 'GET /labels': { status: 200, body: [label()] } }));

    await expect(run(stub, 'tcms_read_board', { board: 'Team board', label: ['Regression'] }))
      .rejects.toThrow(/It has: Flaky/);
    expect(stub.sent.some((request) => request.path.endsWith('/cards'))).toBe(false);
  });

  it('reads the backlog in rank order, with the rank left as the string it is', async () => {
    const stub = api(agentRoutes({
      'GET /backlog': {
        status: 200,
        body: [backlogRow({ rank: '0.30000000000000004' })],
      },
    }));

    const answer = await run(stub, 'tcms_read_board', { backlog: true });

    // A decimal string, never parsed: `NUMERIC` is arbitrary precision and a double is not, so a
    // client that read it as a number would collide two cards a few hundred inserts in.
    expect(answer['items']).toMatchObject([{ position: 1, rank: '0.30000000000000004' }]);
  });

  it('refuses a board this project does not have, and names the ones it does', async () => {
    const stub = api(agentRoutes(BOARDS));

    await expect(run(stub, 'tcms_read_board', { board: 'Stakeholders' }))
      .rejects.toThrow(/It has: Team board/);
  });
});

describe('tcms_read_release', () => {
  const RELEASES = {
    'GET /releases': {
      status: 200,
      body: [releaseSummary({ readiness: readinessSummary({ storiesTotal: 6, linkedCases: 4 }) })],
    },
  } as const;

  it('lists the releases with their readiness inline, through the one list envelope', async () => {
    const stub = api(agentRoutes(RELEASES));

    const answer = await run(stub, 'tcms_read_release', {}) as { items: unknown[] };

    // Inline, because the list draws two meters and a badge per row — resolving each id one at a
    // time would be N+1 over a screen the server already has every answer for.
    expect(answer.items).toMatchObject([{ version: '2.4', readiness: { hasRun: false } }]);
  });

  it('hands back null counts as null, so nothing downstream can round them to zero', async () => {
    const stub = api(agentRoutes(RELEASES));

    const answer = await run(stub, 'tcms_read_release', {}) as
      { items: { readiness: { casesPassed: number | null; casesFailed: number | null } }[] };

    /*
      The one number a model must not round. `hasRun: false` means nothing has been executed
      against this version, and passing 0 up in place of null turns *nothing has been tested* into
      *nothing failed* — the sentence this whole module exists to stop the product saying. So the
      tool is a pass-through here and this is the assertion that keeps it one.
    */
    expect(answer.items[0]?.readiness.casesPassed).toBe(null);
    expect(answer.items[0]?.readiness.casesFailed).toBe(null);
  });

  it('resolves a VERSION before reading readiness, never sending it as an id', async () => {
    const stub = api(agentRoutes({
      ...RELEASES,
      'GET /releases/rl-1/readiness': {
        status: 200,
        body: releaseReadiness({
          verdict: 'NOT_READY',
          tests: releaseTests({ hasRun: true, linkedCases: 4, passed: 3, failed: 1, blocked: 0, untested: 0, withResult: 4 }),
        }),
      },
    }));

    const answer = await run(stub, 'tcms_read_release', { release: '2.4', readiness: true }) as
      { verdict: string };

    expect(answer.verdict).toBe('NOT_READY');
    expect(stub.sent.some((request) => request.path.endsWith('/releases/rl-1/readiness'))).toBe(true);
  });

  it('refuses a version this project does not have, and names the ones it does', async () => {
    /*
      `tcms_read_board`'s label-and-assignee rule, and the reason bites harder here: a version
      passed through as a filter matching nothing reads to a model as an EMPTY RELEASE, so it
      would report that 9.9 has no stories rather than that there is no 9.9.
    */
    const stub = api(agentRoutes(RELEASES));

    await expect(run(stub, 'tcms_read_release', { release: '9.9' })).rejects.toThrow(/It has: 2\.4/);
    expect(stub.sent.some((request) => request.path.includes('/readiness'))).toBe(false);
  });

  it('reads the roadmap instead of the list when asked, and answers an empty one as an answer', async () => {
    const stub = api(agentRoutes({ 'GET /roadmap': { status: 200, body: roadmap() } }));

    const answer = await run(stub, 'tcms_read_release', { roadmap: true }) as
      { releases: unknown[]; unscheduled: unknown[] };

    // Empty on the day the module merges (D14): nothing is migrated into a release, so both arrays
    // being empty is a real answer and not a failed read.
    expect(answer).toEqual({ releases: [], unscheduled: [] });
    expect(stub.sent.some((request) => request.path.endsWith('/releases'))).toBe(false);
  });

  it('keeps the epics inside the release they target, rather than flattening them', async () => {
    const stub = api(agentRoutes({
      'GET /roadmap': {
        status: 200,
        body: roadmap({
          releases: [roadmapRelease({ epics: [roadmapEpic({ releaseId: 'rl-1' })] })],
          unscheduled: [roadmapEpic({ key: 'ACME-9' })],
        }),
      },
    }));

    const answer = await run(stub, 'tcms_read_release', { roadmap: true }) as
      { releases: { epics: unknown[] }[] };

    // Same rule as the board's columns: a join the model has to perform itself is a turn spent
    // rebuilding a shape the endpoint already had.
    expect(answer.releases[0]?.epics).toHaveLength(1);
  });

  it('is the first read tool an Automation Service credential is not shown', () => {
    /*
      `release:read` is held by six of the seven roles and not by Automation Service (§A3): a CI
      token posts results and has no business reading a delivery plan. Every read tool before this
      one is listed to every credential that can reach the project at all, so this is the first
      narrowing on the read half — and `server.test.ts` drives the same fact through a real
      `tools/list` rather than through `isListed` alone.
    */
    const automation = new Set(roleMatrix().AS);
    expect(automation.has('release:read')).toBe(false);

    const hidden = READ_TOOLS.filter((candidate) => !isListed(candidate, automation));
    expect(hidden.map((candidate) => candidate.name)).toContain('tcms_read_release');
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
