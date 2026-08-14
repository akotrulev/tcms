/**
 * The seam, observed rather than inferred.
 *
 * Everything `contractExchange.ts` does, it does by *failing* — which is the one outcome a green
 * suite cannot contain. So the collection is drained here and asserted on directly: what it reports,
 * what it deliberately does not, and the fact that the hatch stops it. Without this the only evidence
 * the instrument works would be that 118 tests once went red, which is a fact about a moment rather
 * than a property of the tree.
 *
 * The second half is §202's guard one instrument over: **a double the seam cannot see is invisible
 * exactly the way a file in which nothing failed is invisible.** Every `fetch` stub in `packages/`
 * routes through `checkExchange`, and a test file that builds its own would silently opt out. Nothing
 * pinned that before this slice, because before it the stubs' contents did not matter.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import { declaredPathCount } from './contractBodies.js';
import { checkExchange, drainContractViolations, drivingTheTransport } from './contractExchange.js';
import { currentUser, suite } from './fixtures.js';

/*
  Every case below reports on purpose, so the module's own `afterEach` would fail this file on
  every test. Draining first is what makes the reports assertable — and it has to run BEFORE that
  hook, which `afterEach` ordering gives: hooks run in registration order, and the seam registered
  its when it was imported two lines above.
*/
afterEach(() => {
  drainContractViolations();
});

/** One exchange, with the defaults a well-formed read has, and whatever the case overrides. */
function exchange(overrides: Partial<Parameters<typeof checkExchange>[0]> = {}): void {
  checkExchange({
    method: 'GET',
    pathname: '/api/v1/me',
    requestBody: null,
    status: 200,
    responseBody: currentUser(),
    ...overrides,
  });
}

describe('what it reports', () => {
  // A vacuity guard, first: every case below resolves a URL against the document, and a document
  // that failed to load resolves nothing and reports everything for the wrong reason.
  it('read a contract with paths in it', () => {
    expect(declaredPathCount()).toBeGreaterThan(80);
  });

  it('says nothing about a body the server could send', () => {
    exchange();
    expect(drainContractViolations()).toEqual([]);
  });

  it('names a required property the body left out', () => {
    exchange({ responseBody: { id: 'u-1' } });

    const found = drainContractViolations();
    expect(found.map((violation) => violation.message)).toContain(
      '$.email — required by the contract, absent from the body this double served',
    );
    expect(found.every((violation) => violation.direction === 'served')).toBe(true);
  });

  it('names a property the contract does not declare', () => {
    exchange({ responseBody: { ...currentUser(), status: 'ACTIVE' } });

    expect(drainContractViolations().map((violation) => violation.message)).toEqual([
      '$.status — served by this double, declared nowhere in the contract',
    ]);
  });

  it('names an enum member that is not one', () => {
    exchange({
      method: 'GET',
      pathname: '/api/v1/projects/p-1/suites',
      responseBody: [{ ...suite(), lifecycleStatus: 'ACTIVE' }],
    });

    expect(drainContractViolations()[0]?.message).toContain('which is not one of the members');
  });

  /*
    The §204 rung, and it is a guard rather than a repair: nothing in this tree answers an undeclared
    2xx, so the only evidence it works is a case that makes it fire. `POST …/comments` is the right
    subject because it declares 201 **alone** — most creates here declare 200 and 201 both, which is
    exactly why the population is zero and why the candidate that asked for this expected otherwise.
  */
  it('names a success status the operation does not declare', () => {
    exchange({
      method: 'POST',
      pathname: '/api/v1/projects/p-1/reviews/r-1/comments',
      requestBody: JSON.stringify({ body: 'Looks right.' }),
      status: 200,
      responseBody: undefined,
    });

    expect(drainContractViolations().map((violation) => violation.message)).toEqual([
      'answered 200, and the contract declares 201 for that operation. A client may branch on '
      + 'which — a create that replays an `Idempotency-Key` answers 200 where the first call '
      + 'answered 201 — so a double serving the wrong one tests a response the server does not send.',
    ]);
  });

  it('compares the payload a call sent, and says so in the other direction', () => {
    exchange({
      method: 'POST',
      pathname: '/api/v1/projects/p-1/cases',
      requestBody: JSON.stringify({ title: 'Login works' }),
      status: 201,
      responseBody: undefined,
    });

    const found = drainContractViolations();
    expect(found.every((violation) => violation.direction === 'sent')).toBe(true);
    expect(found.map((violation) => violation.message)).toContain(
      '$.stepFormat — required by the contract, absent from the payload this call sent',
    );
  });

  it('reports a URL that resolves to no path exactly once, against the request', () => {
    exchange({ method: 'PATCH', pathname: '/api/v1/x', requestBody: '{"title":"x"}' });

    expect(drainContractViolations()).toEqual([
      {
        method: 'patch',
        url: '/api/v1/x',
        direction: 'sent',
        message:
          'resolves to no path in openapi.yaml. Either the double answers a URL this package no '
          + 'longer calls, or the path was renamed on one side only.',
      },
    ]);
  });

  /*
    The prefix is the difference between this seam and the SPA's, and getting it wrong would not
    look like a failure: every URL would resolve to nothing, every request would report the message
    above, and a reader would conclude the contract had moved.
  */
  it('needs the server prefix, because that is what this stack sends', () => {
    exchange({ pathname: '/me' });

    expect(drainContractViolations()[0]?.message).toContain('resolves to no path');
  });
});

describe('what it deliberately does not report', () => {
  /*
    The question the SPA's seam cannot ask. A refusal is a `Problem`, and measuring one against the
    success schema would report every error-path test in the tree as a defect — which is the failure
    mode that makes a check get switched off.
  */
  it('leaves a refusal alone, because a 4xx does not carry the success schema', () => {
    exchange({
      status: 403,
      responseBody: { type: 'about:blank', title: 'Forbidden', status: 403, detail: 'No.' },
    });

    expect(drainContractViolations()).toEqual([]);
  });

  /*
    The pair of the case above and the reason the whole population is zero. A create declares both,
    because a replayed `Idempotency-Key` answers 200 — so neither arm may be reported.
  */
  it('accepts either arm of a create that declares 200 and 201', () => {
    for (const status of [200, 201]) {
      exchange({
        method: 'POST',
        pathname: '/api/v1/projects/p-1/suites',
        requestBody: JSON.stringify({ name: 'Smoke' }),
        status,
        responseBody: suite(),
      });

      expect(drainContractViolations(), `status ${status}`).toEqual([]);
    }
  });

  /*
    A status the operation cannot answer stops the body comparison rather than adding to it — the
    schema that would be reached describes a response the double is not claiming to be, so reporting
    both would name one defect twice and send the reader to the fixture instead of to the status.
  */
  it('says nothing about the body once the status is the defect', () => {
    exchange({
      method: 'POST',
      pathname: '/api/v1/projects/p-1/reviews/r-1/comments',
      requestBody: JSON.stringify({ body: 'Looks right.' }),
      status: 200,
      responseBody: { nothing: 'the contract declares' },
    });

    expect(drainContractViolations()).toHaveLength(1);
  });

  it('leaves a 204 alone', () => {
    exchange({
      method: 'DELETE',
      pathname: '/api/v1/api-tokens/t-1',
      status: 204,
      responseBody: undefined,
    });

    expect(drainContractViolations()).toEqual([]);
  });

  /*
    `{}` and nothing at all are the same request — `JSON.stringify(body ?? {})` — so reporting the
    empty case would tell the reader to write the call they already wrote.
  */
  it('leaves an empty body to an operation that takes none alone', () => {
    exchange({ requestBody: '{}' });

    expect(drainContractViolations()).toEqual([]);
  });

  it('reports a NON-empty body sent to an operation that takes none', () => {
    exchange({ requestBody: '{"title":"x"}' });

    expect(drainContractViolations().map((violation) => violation.message)).toEqual([
      'the contract declares no request body for that operation, and this call sent one. The '
      + 'server reads none of it.',
    ]);
  });

  it('stops entirely once a test says it is driving the transport', () => {
    drivingTheTransport();
    exchange({ responseBody: { id: 'u-1' }, pathname: '/api/v1/x' });

    expect(drainContractViolations()).toEqual([]);
  });
});

/* ------------------------------------------------- the population it can see ---- */

const PACKAGES = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');

/** Every `*.test.ts` under `packages/`, with its path relative to that root. */
function testFiles(): string[] {
  const out: string[] = [];
  const walk = (directory: string): void => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (entry.name === 'node_modules' || entry.name === 'dist') continue;
      const path = join(directory, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.name.endsWith('.test.ts')) out.push(path.slice(PACKAGES.length + 1));
    }
  };
  walk(PACKAGES);
  return out;
}

const SELF = 'client/src/test/contractExchange.test.ts';

/** The three files allowed to build a `fetch` — the ones that call `checkExchange`. */
const DOUBLES = [
  'client/src/test/stubFetch.ts',
  'cli/src/test/harness.ts',
  'mcp/src/test/harness.ts',
];

describe('still covers the tree it was installed for', () => {
  const files = testFiles();

  it('found the suite', () => {
    expect(files.length).toBeGreaterThan(8);
  });

  it('reaches every test file that talks to the API', () => {
    const reaching = files.filter((file) => {
      const text = readFileSync(join(PACKAGES, file), 'utf8');
      return text.includes('stubFetch') || text.includes("from './test/harness.js'");
    });

    // A floor rather than a count, so adding a test file that makes no request is not a failure.
    // Eight of the ten files in this tree drive the API; the two that do not are this one and the
    // pairing check.
    expect(reaching.length, `only ${reaching.length} of ${files.length} test files reach a double`)
      .toBeGreaterThanOrEqual(6);
  });

  /*
    The guard §202 wrote for `createTestQueryClient`, one instrument over. A test file that builds
    its own `fetch` is invisible to the seam and looks exactly like a file in which every body was
    fine — so the list of files doing it must be **empty**, asserted as a list rather than a count so
    the failure names the file.
  */
  it('has no test file building a fetch of its own', () => {
    const ownDouble = files.filter((file) => {
      // This file names both patterns in order to search for them, which is the sweeping file's
      // usual problem — `renderFailures.test.tsx` excludes itself for the same reason.
      if (file === SELF) return false;
      const text = readFileSync(join(PACKAGES, file), 'utf8');
      return text.includes('typeof globalThis.fetch =') || text.includes('globalThis.fetch = async');
    });

    expect(
      ownDouble,
      'A `fetch` built outside the three shared doubles is one `contractExchange.ts` never sees. '
        + 'Use `stubFetch` or the package harness, or teach the new double to call `checkExchange`.',
    ).toEqual([]);
  });

  it('has exactly three doubles, each of which calls the seam', () => {
    const calling = DOUBLES.filter((file) =>
      readFileSync(join(PACKAGES, file), 'utf8').includes('checkExchange('));

    expect(calling).toEqual(DOUBLES);
  });
});
