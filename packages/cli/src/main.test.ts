/**
 * `main()` against a fake sink and a fake `fetch` — see `test/harness.ts` for why that is the
 * seam. This file covers dispatch, argument handling and the reads; the writes are next door in
 * `writes.test.ts`.
 */
import { describe, expect, it } from 'vitest';

import {
  caseDetail,
  casePage,
  caseSummary,
  condition,
  currentUser,
  issue,
  issueComment,
  issueStatus,
  issueSummary,
  issueTestCase,
  permissions,
  readinessSummary,
  releaseReadiness,
  releaseBlocker,
  releaseStories,
  releaseSummary,
  releaseTests,
  roadmap,
  roadmapEpic,
  roadmapRelease,
  suite,
} from '../../client/src/test/fixtures.js';
import { EXIT, main } from './main.js';
import { PROJECT, api, env, mainWith, recorder } from './test/harness.js';

/**
 * A case detail with all four condition arrays, which is what the contract requires of one.
 *
 * `sc-1` and `sc-2` are `suite_condition` **attachment** ids — what `--decline` takes — and
 * `cond-1`/`cond-2` the library entries behind them. The two are different ids on purpose: the
 * same library entry attached at two suites is two attachments, and declining one must leave the
 * other in force.
 */
const CASE_DETAIL = caseDetail({
  id: 'c-9',
  key: 'TC-42',
  title: 'Login works',
  lifecycleStatus: 'APPROVED',
  preconditions: [
    condition({
      id: 'sc-1',
      conditionId: 'cond-1',
      title: 'Log in',
      body: ['Sign in as ops@acme.test'],
      inline: false,
      inherited: true,
      inheritedFrom: 'Billing',
    }),
  ],
  declinedPreconditions: [
    condition({
      id: 'sc-2',
      position: 2,
      conditionId: 'cond-2',
      title: 'Seed the ledger',
      body: ['Insert three settled invoices'],
      inline: false,
      inherited: true,
      inheritedFrom: 'Billing',
    }),
  ],
  lockVersion: 3,
});

describe('argument handling', () => {
  it('prints usage and exits 2 when given nothing', async () => {
    const out = recorder();
    expect(await main([], out, env)).toBe(EXIT.usage);
    expect(out.stdout).toContain('Usage: tcms <noun> <verb>');
  });

  it('prints usage and exits 0 for an explicit help', async () => {
    const out = recorder();
    expect(await main(['help'], out, env)).toBe(EXIT.ok);
  });

  it('exits 2 on an unknown command, and says so on stderr', async () => {
    const out = recorder();
    expect(await main(['case', 'invent'], out, env)).toBe(EXIT.usage);
    expect(out.stderr).toContain('Unknown command');
    // stdout stays clean, so `tcms ... --json | jq` never receives a diagnostic.
    expect(out.stdout).toBe('');
  });

  // The distinction scripts depend on: a mistyped flag and a refused request need different
  // reactions, and a pipeline that cannot tell them apart retries the one that never succeeds.
  it('exits 2 for a bad --timeout, not 1', async () => {
    const out = recorder();
    expect(await main(['project', 'list', '--timeout', 'soon'], out, env)).toBe(EXIT.usage);
  });

  it('refuses to run without an instance or a token, before making a request', async () => {
    const out = recorder();
    // `EXIT.usage`, not `EXIT.failed`: a missing `TCMS_URL` is the caller's own configuration and
    // no retry fixes it. Every one of these used to be `1`, which is the collapse the ten-code
    // table exists to undo — see `EXIT`.
    expect(await main(['project', 'list'], out, { TCMS_TOKEN: 'x' })).toBe(EXIT.usage);
    expect(out.stderr).toContain('TCMS_URL');

    const second = recorder();
    expect(await main(['project', 'list'], second, { TCMS_URL: env.TCMS_URL })).toBe(EXIT.usage);
    expect(second.stderr).toContain('TCMS_TOKEN');
  });
});

describe('failures', () => {
  it('prints the server’s own detail and the field errors under it', async () => {
    const out = recorder();
    const stub = api({
      '/projects': {
        status: 400,
        body: { title: 'Validation failed', status: 400, detail: 'That is not valid.', errors: { key: ['is required'] } },
      },
    });

    const code = await mainWith(['project', 'list'], out, stub.fetch);

    // A payload the server rejected is a usage mistake found one hop later than a mistyped flag.
    expect(code).toBe(EXIT.usage);
    expect(out.stderr).toContain('That is not valid.');
    expect(out.stderr).toContain('key: is required');
  });

  it('says nothing about permissions on a 404', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 404, body: { detail: 'Not found.', status: 404 } } });

    expect(await mainWith(['project', 'list'], out, stub.fetch)).toBe(EXIT.notFound);
    // The copy is the core's now, so this asserts §4.5's boundary on the CLI as well as the MCP:
    // a 404 must not distinguish "exists but you cannot see it" from "does not exist".
    expect(out.stderr).not.toMatch(/permission|role|Viewer|Test Lead/i);
  });
});

describe('whoami', () => {
  /*
    §137 shipped this command with **no test at all**, and it printed `[object Object]` once per
    permission — `GET /me/permissions` has answered `{key, qualifier}` pairs since §65, not bare
    keys, and `[...permissions].sort().join('  ')` stringifies each object. The command exited 0.
    §140 found it while building the MCP server's capability summary against the same endpoint.
  */
  it('prints permission keys, with the qualifier when it narrows them', async () => {
    const out = recorder();
    const stub = api({
      '/me': { status: 200, body: currentUser() },
      '/projects': { status: 200, body: [PROJECT] },
      '/me/permissions': {
        status: 200,
        body: permissions(['case:read', 'case:delete'], { 'case:delete': 'OWN' }, null),
      },
    });

    expect(await mainWith(['whoami'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).not.toContain('[object Object]');
    expect(out.stdout).toContain('case:read');
    // The qualifier is the difference between "may I change this" and "may I change this one",
    // which is the question the command exists to answer.
    expect(out.stdout).toContain('case:delete (OWN)');
  });
});

describe('reads', () => {
  it('lists projects', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 200, body: [PROJECT] } });

    expect(await mainWith(['project', 'list'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('OLB');
    expect(out.stdout).toContain('Online Banking');
  });

  it('prints the response verbatim under --json', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 200, body: [PROJECT] } });

    expect(await mainWith(['project', 'list', '--json'], out, stub.fetch)).toBe(EXIT.ok);
    // Verbatim: the parsed output is the API's array, not a reshaping of it, because a `--json`
    // that reshaped would be a second undocumented schema for `jq` scripts to depend on.
    expect(JSON.parse(out.stdout)).toEqual([PROJECT]);
  });

  it('infers the project when the token can see exactly one', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/suites': { status: 200, body: [suite({ key: 'TS-1', lifecycleStatus: 'APPROVED' })] },
    });

    expect(await mainWith(['suite', 'list'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('TS-1');
    expect(stub.urls.some((url) => url.includes('/projects/p-1/suites'))).toBe(true);
  });

  it('asks for --project when several are visible', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT, { ...PROJECT, id: 'p-2', key: 'PAY' }] },
    });

    expect(await mainWith(['suite', 'list'], out, stub.fetch)).toBe(EXIT.usage);
    expect(out.stderr).toContain('--project');
  });

  it('resolves a display key before reading the case', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/cases': { status: 200, body: casePage([caseSummary({ id: 'c-9', key: 'TC-42' })], { size: 1 }) },
      '/cases/c-9': { status: 200, body: CASE_DETAIL },
    });

    expect(await mainWith(['case', 'show', 'TC-42'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('Login works');
    // The lock version is printed because every edit needs it — omitting If-Match is
    // last-write-wins, which is the worst thing this surface can do silently.
    expect(out.stdout).toContain('Lock version: 3');
  });

  /*
    §189. The ids are the reason this table exists: `case edit --decline` takes `suite_condition`
    ATTACHMENT ids, and until this command printed them the only way to learn one at a terminal
    was `--json`. A flag whose argument the surface cannot tell you is a flag nobody can use.

    The fixture is the whole resource for the reason `writes.test.ts` states over its own: this
    stub used to carry six fields, none of the four condition arrays among them, and a partial
    fixture agrees with a client that reads nothing.
  */
  it('prints each condition with its attachment id, and says which are declined', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/cases': { status: 200, body: casePage([caseSummary({ id: 'c-9', key: 'TC-42' })], { size: 1 }) },
      '/cases/c-9': { status: 200, body: CASE_DETAIL },
    });

    expect(await mainWith(['case', 'show', 'TC-42'], out, stub.fetch)).toBe(EXIT.ok);

    expect(out.stdout).toContain('sc-1');
    expect(out.stdout).toContain('Log in');
    // Declined entries are printed BESIDE the live ones, never merged into them: the server
    // serves two arrays because a declined condition is not part of what the case does.
    expect(out.stdout).toMatch(/sc-2.*declined/);
    expect(out.stdout).toMatch(/sc-1.*in force/);
    // Where an inherited rule came from is the context for refusing it.
    expect(out.stdout).toContain('Billing');
  });

  /*
    §189's other half. `suite edit --precondition` takes a LIBRARY id, and `case edit --decline`
    takes an ATTACHMENT id; the two are one column apart in this table and neither works in the
    other's flag. Printing only one of them makes one of the two writes unusable.
  */
  it('prints a suite’s conditions with the library id first and the attachment id beside it', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/suites': { status: 200, body: [suite({ key: 'TS-7', caseCount: 2, lockVersion: 4 })] },
      '/suites/s-1': {
        status: 200,
        body: suite({
          key: 'TS-7',
          description: 'What we check on every build.',
          caseCount: 2,
          tagRules: ['smoke'],
          preconditions: [condition({ id: 'sc-1', conditionId: 'cond-1', title: 'Log in', inline: false })],
          lockVersion: 4,
        }),
      },
    });

    expect(await mainWith(['suite', 'show', 'TS-7'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toMatch(/cond-1.*PRE.*sc-1/);
    expect(out.stdout).toContain('Lock version: 4');
  });

  it('reads the library and passes both filters through', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/conditions': { status: 200, body: [{ id: 'cond-1', title: 'Log in', type: 'PRE', body: [], usageCount: 4, suiteUsageCount: 1, lockVersion: 1 }] },
    });

    expect(await mainWith(['library', 'list', '--q', 'log', '--type', 'PRE'], out, stub.fetch)).toBe(EXIT.ok);

    // The id is the first column because it is what a write takes; the title is how a person
    // finds the row and the id is what they then have to type.
    expect(out.stdout).toContain('cond-1');
    const asked = stub.urls.find((url) => url.includes('/conditions')) ?? '';
    expect(asked).toContain('q=log');
    expect(asked).toContain('type=PRE');
    // ONE endpoint since §239. This command read `/shared-steps` too until the shared-step
    // library was retired, and the assertion that used to sit here — that `type`, a
    // conditions-only filter, did not leak onto that second read — went with it.
    expect(stub.urls.filter((url) => url.includes('/shared-steps'))).toEqual([]);
  });

  /*
    §139's regression, and it was live from §137 until then: an option a command had not declared
    was parsed as `true` and its value pushed into the positionals, so every string filter on
    every read command was dropped. `case list --q login` answered the whole project's cases,
    with a 200, a full table and no diagnostic anywhere.
  */
  it('sends the filter values, rather than parsing them as booleans', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/cases': { status: 200, body: casePage([], { size: 25 }) },
    });

    expect(await mainWith(
      ['case', 'list', '--q', 'login', '--tag', 'smoke', '--tag', 'slow', '--size', '5'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    const asked = stub.urls.find((url) => url.includes('/cases')) ?? '';
    expect(asked).toContain('q=login');
    expect(asked).toContain('tag=smoke');
    expect(asked).toContain('tag=slow');
    expect(asked).toContain('size=5');
  });

  it('sends the issue filters, and prints the moves an issue may make as NAMES', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      'GET /issues': { status: 200, body: [issueSummary({ key: 'ACME-7' })] },
    });

    expect(await mainWith(
      ['issue', 'list', '--level', 'STORY', '--unparented'], out, stub.fetch,
    )).toBe(EXIT.ok);

    const asked = stub.urls.find((url) => url.includes('/issues')) ?? '';
    expect(asked).toContain('level=STORY');
    expect(asked).toContain('unparented=true');
    expect(out.stdout).toContain('ACME-7');
  });

  it('shows one issue with the statuses it may move to, spelled as `issue move` takes them', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      'GET /issues/ACME-124': {
        status: 200,
        body: issue({
          allowedTransitions: [issueStatus({ id: 'is-2', name: 'In Progress', kind: 'IN_PROGRESS', position: 1 })],
        }),
      },
      'GET /issues/ACME-124/comments': { status: 200, body: [issueComment({ body: 'Reproduced.' })] },
      'GET /issues/ACME-124/doc-links': { status: 200, body: [] },
    });

    expect(await mainWith(['issue', 'show', 'ACME-124'], out, stub.fetch)).toBe(EXIT.ok);
    // The name, never the id: it is the value `--status` takes, and printing a UUID beside it
    // would offer a spelling this surface does not accept.
    expect(out.stdout).toContain('In Progress');
    expect(out.stdout).not.toContain('is-2');
    expect(out.stdout).toContain('Reproduced.');
  });

  it('refuses a key of the wrong kind without making a request', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 200, body: [PROJECT] } });

    expect(await mainWith(['case', 'show', 'TS-7'], out, stub.fetch)).toBe(EXIT.usage);
    expect(out.stderr).toMatch(/is a suite key, but a case was expected/);
    expect(stub.urls.filter((url) => url.includes('/cases'))).toEqual([]);
  });

  it('exits 2 when a show command is given no argument', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 200, body: [PROJECT] } });

    expect(await mainWith(['case', 'show'], out, stub.fetch)).toBe(EXIT.usage);
    expect(out.stderr).toContain('tcms case show TC-42');
  });
});

/**
 * The release reads — and the first of these is an acceptance criterion in its own right.
 *
 * `casesPassed` and `casesFailed` are null on the wire when `hasRun` is false, precisely so that a
 * terminal can print words instead of a number, and the whole module exists to stop the product
 * saying *nothing failed* when it means *nothing has been tested*. A `?? 0` anywhere between the
 * response and the cell reintroduces that sentence and no type in this tree would notice.
 */
describe('the release reads', () => {
  it("prints 'no run yet' and never 0/0 for a version nothing has been executed against", async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      'GET /releases': {
        status: 200,
        body: [releaseSummary({
          version: '2.5',
          targetDate: '2026-11-14',
          readiness: readinessSummary({ storiesDone: 0, storiesTotal: 6, linkedCases: 4 }),
        })],
      },
    });

    expect(await mainWith(['release', 'list'], out, stub.fetch)).toBe(EXIT.ok);

    expect(out.stdout).toContain('no run yet');
    expect(out.stdout).not.toContain('0/0');
    // The badge is the verdict's vocabulary, not the status's (013 D-e): the same word would mean
    // "nobody has started" on one row and "somebody has, and it failed" on the next.
    expect(out.stdout).toContain('Not tested');
  });

  it('prints the counts once something has run, and reads Released off the status', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      'GET /releases': {
        status: 200,
        body: [
          releaseSummary({
            version: '2.4',
            status: 'RELEASED',
            releasedAt: '2026-08-20T09:00:00Z',
            readiness: readinessSummary({
              verdict: 'READY', storiesDone: 27, storiesTotal: 27,
              linkedCases: 9, hasRun: true, casesPassed: 9, casesFailed: 0,
            }),
          }),
        ],
      },
    });

    expect(await mainWith(['release', 'list'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('9/9');
    expect(out.stdout).toContain('Released');
    expect(out.stdout).not.toContain('Ready\n');
  });

  it('names the stories that stand in the way, with the cases that failed under each', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      'GET /releases': { status: 200, body: [releaseSummary({ version: '2.4' })] },
      'GET /releases/rl-1/readiness': {
        status: 200,
        body: releaseReadiness({
          verdict: 'NOT_READY',
          stories: releaseStories({ done: 21, total: 27, inProgress: 4, notStarted: 2, excludedSpikes: 3 }),
          tests: releaseTests({
            hasRun: true, linkedCases: 9, withResult: 8, passed: 7, failed: 1, blocked: 0,
            untested: 1, latestRunName: 'Nightly 2.4',
          }),
          blockers: [releaseBlocker({
            key: 'ACME-118',
            status: issueStatus({ id: 'is-4', name: 'Done', kind: 'DONE', position: 3 }),
            casesPassed: 1,
            casesFailed: 1,
            failedCaseKeys: ['TC-311'],
          })],
          scopeAddedAfterCreation: 4,
        }),
      },
    });

    expect(await mainWith(['release', 'show', '2.4'], out, stub.fetch)).toBe(EXIT.ok);

    // The case this module exists for: a story that IS done, whose linked case failed. The status
    // is printed because it is what separates that from "nobody has started".
    expect(out.stdout).toContain('ACME-118');
    expect(out.stdout).toContain('TC-311');
    expect(out.stdout).toContain('Done');
    // Served so the screen can say why the total does not match the backlog (D8).
    expect(out.stdout).toContain('3 spikes not counted');
  });

  it('says a readiness answer has no run rather than printing nulls as zeroes', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      'GET /releases': { status: 200, body: [releaseSummary({ version: '2.5' })] },
      'GET /releases/rl-1/readiness': {
        status: 200,
        body: releaseReadiness({ version: '2.5', tests: releaseTests({ linkedCases: 4 }) }),
      },
    });

    expect(await mainWith(['release', 'show', '2.5'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('no run yet');
    expect(out.stdout).not.toContain('0 passed');
  });

  it('explains an empty roadmap and offers the command that resolves it', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      'GET /roadmap': { status: 200, body: roadmap() },
    });

    expect(await mainWith(['roadmap'], out, stub.fetch)).toBe(EXIT.ok);
    // Empty is the ordinary state of a fresh instance (D14). A blank answer would read as a broken
    // command, and a reader has no way to tell the difference.
    expect(out.stdout).toContain('tcms release create');
  });

  it('draws the unscheduled lane last, and always, because it answers "where did my epic go"', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      'GET /roadmap': {
        status: 200,
        body: roadmap({
          releases: [roadmapRelease({
            version: '2.4',
            targetDate: '2026-09-18',
            epics: [roadmapEpic({ key: 'ACME-3', startDate: '2026-08-01', targetDate: '2026-09-10', completionPercent: 60 })],
          })],
          unscheduled: [roadmapEpic({ key: 'ACME-9', title: 'Card payments' })],
        }),
      },
    });

    expect(await mainWith(['roadmap'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout.indexOf('ACME-3')).toBeLessThan(out.stdout.indexOf('Unscheduled'));
    expect(out.stdout).toContain('ACME-9');
    expect(out.stdout).toContain('no dates');
  });

  it('lists the cases a story is proven by, and their three nulls when nothing has run one', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      'GET /issues/ACME-118/test-cases': {
        status: 200,
        body: [issueTestCase({ caseKey: 'TC-311', title: 'Sign-out clears the cookie' })],
      },
    });

    expect(await mainWith(['issue', 'cases', 'ACME-118'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('TC-311');
    expect(out.stdout).toContain('Sign-out clears the cookie');
  });

  it('tells a story with no linked cases how to link some', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      'GET /issues/ACME-118/test-cases': { status: 200, body: [] },
    });

    expect(await mainWith(['issue', 'cases', 'ACME-118'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('tcms issue cases set ACME-118');
  });
});
