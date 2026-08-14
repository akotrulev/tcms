/**
 * Every exchange that crosses a `fetch` double in `packages/` is compared against `openapi.yaml` —
 * the body it serves and the payload it was called with — and a body the server could not send, or
 * could not accept, fails the test.
 *
 * The comparison is `contractBodies.ts`, which is `frontend/`'s walker paired character for
 * character. This file is only the seam that gets it a body to look at, and it is a *different* seam
 * from the SPA's for one structural reason worth stating: over there the double replaces a method on
 * the `api` object, so the seam has to hijack `vi.spyOn` to find out what was served. **Here the
 * double is a `fetch`.** The URL, the method, the request body and the response body are all one
 * argument list, which makes this the simpler half of the same instrument — and it makes one question
 * askable that the SPA's cannot ask at all: the **status**. A 400 does not carry the success schema,
 * so only a 2xx answer is compared against it.
 *
 * ## The population this exists for
 *
 * `packages/` doubles the API in three places — `stubFetch` in this package, and an `api(routes)`
 * harness in each of `cli/` and `mcp/` — and until §203 not one body crossing any of them was
 * compared with anything. The bodies are hand-written object literals typed as nothing at all, so
 * this is §197's population reached through a different door: the SPA's fixtures were unconstrained
 * because `as never` erased their shape, and these are unconstrained because no shape was ever
 * claimed.
 *
 * The two `api(routes)` harnesses are near-copies of each other, and the comment at the top of the
 * MCP one already says the stub *is* the CLI's — which it is not; it is a fork with an origin
 * passthrough and a `set`. That is not repaired here. What is shared is the thing that matters for a
 * body being right: **one comparison**, called from both, so the two surfaces cannot come to disagree
 * about what the server sends.
 *
 * ## Why the failure lands in `afterEach` rather than at the call
 *
 * `frontend/`'s reason, one layer lower and sharper: throwing from inside the stub would be caught by
 * `http.ts`, which turns a transport failure into a `TcmsClientError` — so a bad fixture would
 * surface as the *client's* error handling, which is a behaviour several of these tests are actually
 * asserting. Collected and raised after the test, it names the endpoint, the field and the test.
 *
 * ## One escape hatch, and the second one this tree turned out not to need
 *
 * `drivingTheTransport()` suspends the check for a test in which the exchange's content is
 * **scenery**: the subject is an `Idempotency-Key` header, an `If-Match` of zero, a timeout, a
 * cancellation. Such a test picks a URL because it needed one and sends no body because none was the
 * point. Four tests take it, all in `http.test.ts`, and each names what it is asserting on.
 *
 * The obvious second hatch is `frontend/`'s `servingADriftedBody()` — for a test whose *subject* is a
 * body the contract cannot describe. **It is not here, because the population is zero**, and that is
 * a measurement rather than an omission: every drift test in this package answers a **non-2xx** (a
 * 401 with an empty body, a 502 of HTML, a 400 whose `errors` map is the assertion), and `checkServed`
 * already skips those — the success schema is not what a refusal is measured against. A hatch with no
 * callers is a way to disarm the check that nothing has yet had a reason to use.
 *
 * Keeping them apart matters for §202's reason and is why the one that exists is named for its
 * population rather than for the act of excusing: a test asserting on a header has said *nothing
 * whatever* about whether the body beside it was one the server could send, so a general-purpose
 * `skipTheContractCheck()` would let the dullest group in the tree turn off the instrument in the
 * place it was built for. It is a call inside the test that needs it, so the reason stands where the
 * exception is taken and cannot widen to a file, a directory or a pattern.
 */
import { afterEach, expect } from 'vitest';

import { contractPathOfRequest, requestBodyOf, responseSchemaOf, violationsOf } from './contractBodies.js';
import type { Violation } from './contractBodies.js';

export interface Exchange {
  /** As sent — `GET`, `POST`, … */
  method: string;
  /** The URL's path, prefix and all: `/api/v1/projects/p-1`. */
  pathname: string;
  /** What the caller sent, already serialized, or null for a request with no body. */
  requestBody: string | null;
  /** The status the double answered with. Only a 2xx answer is compared against a success schema. */
  status: number;
  /** What the double answered with, before serialization. `undefined` for a 204 and for a hang. */
  responseBody: unknown;
}

interface Reported {
  method: string;
  url: string;
  direction: 'served' | 'sent';
  message: string;
}

let reported: Reported[] = [];
let suspended = false;

/**
 * Suspends the comparison for the remainder of the current test.
 *
 * For a test whose subject is the request **envelope** rather than its content — a header, a timeout,
 * a cancellation, a credential. Name what is being asserted at the call site.
 */
export function drivingTheTransport(): void {
  suspended = true;
}

/**
 * Everything reported since the last drain, cleared.
 *
 * `afterEach` below drains to decide whether to fail, and `contractExchange.test.ts` drains to assert
 * *what* was reported — a pin that could not see the collection could only observe this seam by
 * failing, which is not something a green suite can contain.
 */
export function drainContractViolations(): Reported[] {
  const found = reported;
  reported = [];
  return found;
}

/** True while a test has taken the hatch — read by the seam's own tests. */
export function isSuspended(): boolean {
  return suspended;
}

/**
 * Compares one exchange, both directions, and collects what it finds.
 *
 * Called by every `fetch` double in `packages/`. A URL that resolves to no contract path is reported
 * **once**, against the request, because the two directions would otherwise name one defect twice.
 */
export function checkExchange(exchange: Exchange): void {
  if (suspended) return;

  const method = exchange.method.toLowerCase();
  const contractPath = contractPathOfRequest(exchange.pathname);
  if (!contractPath) {
    reported.push({
      method,
      url: exchange.pathname,
      direction: 'sent',
      message:
        'resolves to no path in openapi.yaml. Either the double answers a URL this package no '
        + 'longer calls, or the path was renamed on one side only.',
    });
    return;
  }

  checkSent(method, contractPath, exchange.requestBody);
  checkServed(method, contractPath, exchange);
}

/**
 * The payload the code under test handed the double, against what the operation says it accepts.
 *
 * The body arrives **serialized**, because that is the only form the stub can be sure it has: `http.ts`
 * forwards `request.body` unbuffered so an evidence upload is not held in memory, and the harnesses
 * already drain it to a string for their own assertions. A body that is not JSON is not walked —
 * `multipart/form-data` is refused by `requestBodyOf` for the frontend's reason, and a stream that is
 * anything else is not a shape this compares.
 */
function checkSent(method: string, contractPath: string, body: string | null): void {
  const declared = requestBodyOf(method, contractPath);
  if (declared.kind === 'no-operation') {
    reported.push({
      method,
      url: contractPath,
      direction: 'sent',
      message:
        'the contract declares no such operation on that path. The double answered a method the '
        + 'server does not implement there.',
    });
    return;
  }

  const value = parseJson(body);
  if (declared.kind === 'none') {
    // `{}` and nothing at all are the same request, so an operation that takes no body is only
    // being sent one when the object has something in it.
    if (value !== undefined && !isEmptyObject(value)) {
      reported.push({
        method,
        url: contractPath,
        direction: 'sent',
        message:
          'the contract declares no request body for that operation, and this call sent one. The '
          + 'server reads none of it.',
      });
    }
    return;
  }
  if (declared.kind !== 'json') return;
  // A required body that was not sent at all is a defect the *server* answers with a 400, and the
  // walker says so when it is handed the `{}` such a call is equivalent to.
  report(method, contractPath, 'sent', violationsOf(declared.schema, value ?? {}, 'sent'));
}

/**
 * The body the double served, against the success response the operation declares.
 *
 * Three things are skipped and each for its own reason. A **non-2xx** answer carries a refusal, not
 * this schema — `Problem` is a different shape and the tests that drive one are asserting the
 * client's refusal handling. A **204** carries nothing. And an operation whose success answer
 * declares no JSON body is reported rather than skipped: a double that served one for it is
 * answering a question the contract says has no answer.
 */
function checkServed(method: string, contractPath: string, exchange: Exchange): void {
  if (exchange.status < 200 || exchange.status >= 300) return;
  if (exchange.status === 204 || exchange.responseBody === undefined || exchange.responseBody === null) return;

  const schema = responseSchemaOf(method, contractPath);
  if (!schema) {
    reported.push({
      method,
      url: contractPath,
      direction: 'served',
      message:
        'the contract declares no JSON success body for that operation, and this double served one.',
    });
    return;
  }

  report(method, contractPath, 'served', violationsOf(schema, exchange.responseBody, 'served'));
}

function parseJson(body: string | null): unknown {
  if (body === null || body.length === 0) return undefined;
  try {
    return JSON.parse(body) as unknown;
  } catch {
    // Not JSON, so not this instrument's question. A test that sends one is either driving the
    // transport or uploading a file.
    return undefined;
  }
}

function isEmptyObject(body: unknown): boolean {
  return typeof body === 'object' && body !== null && !Array.isArray(body) && Object.keys(body).length === 0;
}

function report(method: string, url: string, direction: 'served' | 'sent', found: Violation[]): void {
  for (const violation of found) {
    reported.push({ method, url, direction, message: `${violation.where} — ${violation.message}` });
  }
}

afterEach(() => {
  const found = drainContractViolations();
  suspended = false;

  if (found.length === 0) return;

  const lines = found
    .map((violation) =>
      `  ${violation.direction === 'sent' ? 'sent to' : 'served for'} `
      + `${violation.method.toUpperCase()} ${violation.url}\n    ${violation.message}`)
    .join('\n');

  const sent = found.filter((violation) => violation.direction === 'sent').length;

  expect.fail(
    `This test moved ${found.length} ${found.length === 1 ? 'body' : 'bodies'} the server could `
      + `not ${sent === found.length ? 'accept' : sent === 0 ? 'send' : 'send or accept'}:\n\n`
      + `${lines}\n\n`
      + 'A stubbed answer is an object literal with no declared type, so nothing compares what it '
      + 'serves against the contract except this. A missing required field is a command one commit '
      + 'away from printing `undefined`; a field the contract does not declare is usually a double '
      + 'answering a different endpoint, or a vocabulary the product removed; and a value the '
      + 'contract does not admit — an enum member that is not one, a null in a field the server '
      + 'always fills — is a surface rendering something the product cannot produce (§198).\n\n'
      + 'A **sent** body is the other direction and usually not a fixture at all: it is what the '
      + 'command core composed, so the repair is normally in `core/` or in the command rather than '
      + 'in the test (§199).\n\n'
      + 'If the contract is the side that is wrong, change it and regenerate BOTH `schema` copies. '
      + 'If the drifted body IS the subject of this test, call `servingADriftedBody()` and say why.',
  );
});
