import { describe, expect, it } from 'vitest';

import { contractDocument } from './contractBodies.js';

/**
 * **A credential may not appear in a response schema.**
 *
 * `§217 FD-15` and `§218 NC-5` are the same missing rule, recorded by two slices two days apart:
 * the pairing rule compares the schema and its Java record *to each other*, so adding `clientSecret`
 * to both satisfies it, and nothing else looks. Three prose statements and a type-level obstacle
 * stood in for a check.
 *
 * ## What is walked, and what is deliberately not
 *
 * **Responses only.** `IdentityProviderInput.clientSecret` and `NotificationChannelInput.secret` are
 * credentials travelling the direction they are supposed to; a rule over every schema reddens on
 * both and its by-decision map fills with entries that teach the reader nothing.
 *
 * **Strings only.** `IdentityProvider.clientSecretSet` and `InvitationPreview.passwordRequired` are
 * booleans, and a boolean cannot carry a secret. That is a structural exemption rather than a listed
 * one, which is why neither appears below.
 *
 * **Bodies only.** The one credential this API knowingly puts in a `Location` header is the SSO
 * session fragment, and it is a fragment precisely so it never reaches a server (§6r). `§217 SO-4`
 * is the observation that covers it, from the only place it is observable — a browser.
 */

/** Substrings that make a property name credential-shaped. Case-insensitive. */
const CREDENTIAL = ['secret', 'password', 'passphrase', 'credential', 'privatekey', 'apikey', 'token'];

/**
 * The exceptions, each a decision with its reason. **A new entry here is a decision about
 * disclosure**, not a way to make the suite green.
 */
const BY_DECISION: Record<string, string> = {
  'AuthResult.token':
    'The session itself. This is the response that exists to issue one; withholding it would leave ' +
    'the caller with nothing to authenticate with.',
  'IssuedApiToken.secret':
    'The plaintext, shown once and recoverable never (§118). Its own type rather than a nullable ' +
    'field on ApiToken, so no read path is one forgotten null away from serving it.',
  'ApiToken.tokenPrefix':
    'The first characters only, so an administrator can tell two credentials apart in a list. ' +
    'Nothing accepts it — it authenticates nothing.',
  'ExecutionHistoryEntry.executedByToken':
    "The credential's NAME, not its id and not its secret — `ExecutionTokens` resolves the one to " +
    'the other so a timeline can say who recorded a result. `tokenPrefix`\'s category: it is chosen ' +
    'by the administrator who wrote it into a CI configuration, and it authenticates nothing. The ' +
    'needle that catches it is `token`, which is kept for `AuthResult.token`.',
  'AuditEvent.actorTokenId':
    'The credential\'s PRIMARY KEY, not its secret and not its prefix — the same id `ApiToken.id` ' +
    'already serves to the same readers. It authenticates nothing, and the audit trail needs it ' +
    'because a token-driven act names the person AND the credential: dropping it would say a ' +
    'human typed what a pipeline did. The needle that catches it is `token`.',
  'AuditEvent.actorTokenName':
    "The credential's NAME — `ExecutionHistoryEntry.executedByToken`'s category exactly, and " +
    'resolved the same way, so a trail can say which CI credential acted. Chosen by the ' +
    'administrator who wrote it into a pipeline configuration, already served on `ApiToken.name`, ' +
    'and it authenticates nothing.',
  'NotificationChannel.secretHint':
    'Four end-anchored characters of a webhook URL (§218). Enough to answer "is this the right ' +
    'webhook", short enough to be useless as one.',
};

type Node = Record<string, unknown>;

function isNode(value: unknown): value is Node {
  return typeof value === 'object' && value !== null;
}

/** Resolves a local `$ref` and returns the schema's component name alongside it. */
function resolve(node: Node, doc: Node): { schema: Node; name?: string } {
  const ref = node.$ref;
  if (typeof ref !== 'string') return { schema: node };
  const name = ref.replace('#/components/schemas/', '');
  const schemas = (doc.components as Node | undefined)?.schemas as Node | undefined;
  const target = schemas?.[name];
  return isNode(target) ? { schema: target, name } : { schema: {} };
}

/**
 * Every `(owner, property)` pair reachable from a schema, following `$ref`, `allOf`/`oneOf`/`anyOf`
 * and array items. `seen` is keyed on the component name so a self-referential schema — a doc page
 * with children, a team with a parent — terminates.
 */
function propertiesReachableFrom(
  start: Node,
  doc: Node,
  owner: string,
  seen: Set<string>,
  found: Map<string, Node>,
): void {
  const { schema, name } = resolve(start, doc);
  const under = name ?? owner;
  if (name) {
    if (seen.has(name)) return;
    seen.add(name);
  }

  for (const branch of ['allOf', 'oneOf', 'anyOf'] as const) {
    const members = schema[branch];
    if (Array.isArray(members)) {
      for (const member of members) {
        if (isNode(member)) propertiesReachableFrom(member, doc, under, seen, found);
      }
    }
  }

  const items = schema.items;
  if (isNode(items)) propertiesReachableFrom(items, doc, under, seen, found);

  const properties = schema.properties;
  if (!isNode(properties)) return;
  for (const [property, definition] of Object.entries(properties)) {
    if (!isNode(definition)) continue;
    found.set(`${under}.${property}`, definition);
    propertiesReachableFrom(definition, doc, under, seen, found);
  }
}

/** Every property any 2xx response body can carry, keyed `Schema.property`. */
function responseProperties(): Map<string, Node> {
  const doc = contractDocument() as unknown as Node;
  const found = new Map<string, Node>();
  const paths = doc.paths as Node;

  for (const [path, item] of Object.entries(paths)) {
    if (!isNode(item)) continue;
    for (const [method, operation] of Object.entries(item)) {
      if (!isNode(operation) || !isNode(operation.responses)) continue;
      for (const [status, response] of Object.entries(operation.responses)) {
        if (!status.startsWith('2') || !isNode(response) || !isNode(response.content)) continue;
        for (const media of Object.values(response.content)) {
          if (!isNode(media) || !isNode(media.schema)) continue;
          propertiesReachableFrom(media.schema, doc, `${method.toUpperCase()} ${path}`, new Set(), found);
        }
      }
    }
  }
  return found;
}

function looksLikeACredential(property: string): boolean {
  const lowered = property.toLowerCase();
  return CREDENTIAL.some((needle) => lowered.includes(needle));
}

describe('response schemas', () => {
  it('carry no credential-shaped string that is not a recorded decision', () => {
    const offenders: string[] = [];

    for (const [key, definition] of responseProperties()) {
      const property = key.slice(key.lastIndexOf('.') + 1);
      if (!looksLikeACredential(property)) continue;
      if (definition.type !== 'string') continue;
      if (key in BY_DECISION) continue;
      offenders.push(key);
    }

    expect(
      offenders,
      'A credential-shaped string reached a response schema. If serving it is deliberate, add it to ' +
        'BY_DECISION with the reason it is safe to disclose — a masked hint, a one-time secret, the ' +
        'session itself. If it is not deliberate, this is the disclosure §217 FD-15 and §218 NC-5 ' +
        'both predicted.',
    ).toEqual([]);
  });

  it('does not carry a by-decision entry for something no response serves', () => {
    const served = new Set(responseProperties().keys());
    const stale = Object.keys(BY_DECISION).filter((key) => !served.has(key));

    expect(
      stale,
      'A recorded exception names a property no response carries any more. Delete it — an exception ' +
        'nobody can reach reads as a standing permission for a shape that no longer exists.',
    ).toEqual([]);
  });
});
