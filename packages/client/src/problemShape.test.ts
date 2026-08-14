/**
 * The reading of RFC-7807, pinned against the contract rather than against either client.
 *
 * **`errors.ts` has cited this file by name since §137 and it did not exist.** Its header gives the
 * argument for why `TcmsApiError` is a near-copy of the SPA's `ApiError` rather than an import — a
 * browser client and a Node one share the wire format and nothing else — and then says *"the one
 * thing that must not diverge is the reading of that format, so `problemShape.test.ts` derives the
 * field list from the contract."* Nothing did. The two readings diverged (see below), which is what
 * the sentence was written to prevent and what the missing file could not notice.
 *
 * ## What a check here can and cannot pin
 *
 * It can pin **this** package's reading, in both directions, against `Problem` as `openapi.yaml`
 * declares it: a property the document adds fails the build until somebody decides whether this
 * client carries it, and a member of {@link READS} that the document no longer declares fails too
 * (§162's rule — a one-directional list cannot report an entry that stopped being needed).
 *
 * It cannot pin the SPA's, and that is a structural limit rather than an omission: two npm roots,
 * two CI jobs, and `frontend/src/api/client.ts` is not importable from here. §203's answer for the
 * walker was a copied region and a pairing test; a five-property object does not earn that. So the
 * divergence is *stated*, not enforced, and the statement is a measurement:
 *
 * > **`frontend/src/api/client.ts` reads four of the five and drops `type`.** It is a `required`
 * > property of `Problem`, so the server always sends it. `ApiError` has no field for it, this
 * > package's `TcmsApiError` does, and **neither stack reads the value anywhere** — over here it is
 * > on the published surface of `@tcms/client`, which is a caller's to use, and over there it
 * > reaches nothing at all. Whether the SPA should carry it is a question about what
 * > `describeError.ts` could do with a problem *kind* that it cannot do with a status, and adding a
 * > field with no reader to answer it would be the shape §154 and §165 each paid for.
 */
import { describe, expect, it } from 'vitest';

import { TcmsApiError } from './errors.js';
import { createTransport } from './http.js';
import { schemaProperties } from './test/contractBodies.js';
import { drivingTheTransport } from './test/contractExchange.js';
import { stubFetch } from './test/stubFetch.js';

/**
 * Each property `Problem` declares, and the `TcmsApiError` member that carries it.
 *
 * Both directions are asserted, so this map is the whole statement of what this client makes of a
 * refusal document — there is no second list anywhere and no `else` branch that quietly keeps a
 * sixth property.
 */
const READS: Record<string, keyof TcmsApiError> = {
  type: 'type',
  title: 'title',
  status: 'status',
  detail: 'detail',
  errors: 'fieldErrors',
};

/**
 * The one property read from somewhere other than the body, and why.
 *
 * `Problem.status` is declared and required, and `problemFrom` takes `response.status` instead. That
 * is not a gap: the transport's status is the thing every caller branches on — `isTransient`, the
 * retry budget, the MCP's refusal copy — and a document that disagreed with the response it came in
 * would otherwise give this client two answers to one question. A proxy rewriting a status without
 * rewriting the body it forwards is exactly how that happens in the field.
 */
const FROM_THE_TRANSPORT = new Set(['status']);

/** A complete refusal document — every property `Problem` declares, with a distinguishable value. */
const PROBLEM = {
  type: 'https://tcms.example.com/problems/validation',
  title: 'Validation failed',
  status: 400,
  detail: 'The case could not be saved.',
  errors: { gherkinSource: ['must not be blank'] },
};

const refuse = async (
  body: unknown,
  init: { status?: number; statusText?: string } = {},
): Promise<TcmsApiError> => {
  // The subject is a refusal, whose body is a `Problem` and not the operation's success schema —
  // `checkServed` already skips a non-2xx, so this hatch is here for the *request* half only.
  drivingTheTransport();
  const stub = stubFetch({ status: init.status ?? 400, body });
  const transport = createTransport({
    baseUrl: 'https://tcms.example.com',
    token: 'tcms_abc',
    userAgent: 'tcms-cli/0.1.0',
    fetch: stub.fetch,
  });

  try {
    await transport.fetch('https://tcms.example.com/api/v1/me');
  } catch (error) {
    if (error instanceof TcmsApiError) return error;
    throw error;
  }
  throw new Error('The transport did not refuse.');
};

describe('Problem, as this client reads it', () => {
  const declared = schemaProperties('Problem');

  it('names every property the contract declares, and no other', () => {
    // Both directions in one assertion, sorted so the failure message is the set difference rather
    // than a re-ordering. A property added to `Problem` lands here before it lands in a release.
    expect(Object.keys(READS).sort()).toEqual([...declared.properties].sort());
  });

  it('carries every one of them onto the error a caller catches', async () => {
    const error = await refuse(PROBLEM);

    for (const [property, member] of Object.entries(READS)) {
      expect(error[member], `Problem.${property} did not reach TcmsApiError.${member}`)
        .toEqual(PROBLEM[property as keyof typeof PROBLEM]);
    }
  });

  it('takes the status from the response, not from the document', async () => {
    // The pin on FROM_THE_TRANSPORT, driven rather than asserted about: a body claiming 400 inside
    // a 503 is what a proxy that rewrote one and forwarded the other produces, and a client that
    // believed the body would report a validation failure as retryable — or the reverse.
    const error = await refuse({ ...PROBLEM, status: 400 }, { status: 503 });

    expect(error.status).toBe(503);
    expect(FROM_THE_TRANSPORT).toContain('status');
  });

  it('every property it reads from the body is one the server always sends, or handled as absent', async () => {
    // `errors` is the only optional one, so it is the only member that may legitimately be null on
    // a well-formed refusal. The four required ones arriving as null would mean the reader dropped
    // something the contract guarantees.
    const error = await refuse({ type: PROBLEM.type, title: PROBLEM.title, status: 400, detail: PROBLEM.detail });

    for (const property of declared.required) {
      const member = READS[property];
      if (member === undefined || FROM_THE_TRANSPORT.has(property)) continue;
      expect(error[member], `Problem.${property} is required and arrived as null`).not.toBeNull();
    }
    expect(error.fieldErrors).toBeNull();
    expect(error.hasFieldErrors).toBe(false);
  });

  it('floors detail rather than letting a bodiless refusal print an empty line', async () => {
    // §148: the API's 401 has no body at all, and `statusText` is empty over HTTP/2. Without the
    // floor `detail` becomes '' and every consumer that renders it shows a blank where the reason
    // goes. The sentence names the status so the reader has something to quote.
    const error = await refuse(undefined, { status: 401 });

    expect(error.detail).toMatch(/HTTP 401/);
  });
});
