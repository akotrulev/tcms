/**
 * Does this body describe something the server could actually send — or actually accept?
 *
 * **This is the `packages/` copy of `frontend/src/api/contractBodies.ts`**, and the region below the
 * sentinel is byte-identical to that file's — `contractPairing.test.ts` compares them and names the
 * first line that differs. Read that file's header for what the six questions are, which four the
 * §198 rung added, and why `format: uuid` is deliberately not one of them. Nothing here restates it.
 *
 * ## Why a copy rather than an import
 *
 * `packages/` is a second npm root. `frontend/`'s copy reads the contract through Vite's `?raw`
 * import, which exists nowhere outside a Vite graph; its `node_modules` is installed by a CI job that
 * never touches this tree, and this tree's by one that never touches that. An import in either
 * direction would therefore break the other stack's build rather than share anything — so the walker
 * is copied and *paired*, which is the same trade the two `schema.d.ts` copies make and is checked
 * the same way (`schemaFreshness.test.ts`, one directory up, compares those byte for byte).
 *
 * A copy nothing pairs is §196's two tsconfig lists — the shape this repo keeps paying for. So the
 * cost of the copy is paid once, by a test, rather than on the day somebody teaches one walker about
 * a schema keyword and not the other.
 *
 * ## What differs, and each difference has to earn its place
 *
 * Two of the three are about *reaching* the document rather than reading it: how it is obtained, and
 * the **prefix**. Everything this stack sends carries the contract's own `servers` entry —
 * `/api/v1/me`, not `/me` — because `@tcms/client` composes a real absolute URL where the SPA hands
 * its client a contract-relative one. Both live outside the region: the loader above it,
 * `contractPathOfRequest` below.
 *
 * The third is `successStatusesOf`, added in §204, and it is outside the region because the SPA's
 * seam has **no status to ask about** — its double replaces a method on the `api` object, which
 * returns `{ data }`. Copying it across would put an uncallable export in the other stack, which is
 * the readerless shape §165 and §154 each paid for. `declaredHeadersOf` (§205) is the fourth and
 * sits there on the same rule: **a question only one stack can ask does not go in the region.**
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { parse } from 'yaml';

/** A node of the parsed document. Untyped by intent — this walks a schema, it does not model one. */
type Node = Record<string, unknown>;

interface Document {
  paths: Record<string, Node>;
  components: Node;
  servers?: { url?: string }[];
}

const SPEC = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../docs/specs/openapi.yaml');

/**
 * Parsed on **first use**, not on import.
 *
 * The frontend's reason applies unchanged and with more force: this module is reached through two
 * test harnesses imported by ten test files, and parsing 550 kB of YAML is not free. Read off disk
 * rather than through a bundler import — the same way `schemaFreshness.test.ts` reads it, and for the
 * same reason it can: this package runs on Node and every one of its configs already says so.
 */
let document: Document | undefined;
function spec(): Document {
  document ??= parse(readFileSync(SPEC, 'utf8')) as Document;
  return document;
}

/* ==== SHARED REGION START ====
 *
 * This region exists **twice**, character for character: `frontend/src/api/contractBodies.ts` and
 * `packages/client/src/test/contractBodies.ts`. Two stacks double the same server against the same
 * contract, and neither can import the other — separate npm roots, a Vite-only `?raw` import on one
 * side, and two CI jobs each of which installs a tree the other never sees.
 *
 * So it is copied, and **paired**: `packages/client/src/test/contractPairing.test.ts` compares the
 * two slices and names the first line that differs. That is the trade the two `schema.d.ts` copies
 * already make, checked the same way — because a copy nothing pairs is §196's two tsconfig lists,
 * and the walker going quiet about a keyword on one stack only is exactly what that costs.
 *
 * Everything in here touches `spec()` and `Node` and nothing else. What differs between the stacks
 * is outside it: how the document is obtained, and — in `packages/` — the `/api/v1` prefix its URLs
 * carry, which the SPA does not send.
 *
 * Editing anything below means editing both. The pairing test names the line.
 */

/** As spelled in an OpenAPI path item; anything else under a path is metadata. */
const OPERATIONS = new Set(['get', 'put', 'post', 'patch', 'delete'])

/** How many paths the document declares — the number the vacuity guard reads. */
export function declaredPathCount(): number {
  return Object.keys(spec().paths).length
}

function deref(node: Node): Node {
  const ref = node.$ref
  if (typeof ref !== 'string') return node
  let current: unknown = spec()
  for (const segment of ref.slice(2).split('/')) {
    current = (current as Record<string, unknown>)[segment]
  }
  return deref(current as Node)
}

/**
 * Contract paths as matchers, **fewest variables first**.
 *
 * The order is the whole of the resolution rule and it is not decoration: `/projects/{projectId}`
 * and `/projects/{projectId}/settings` are different operations, and a matcher list in document
 * order would resolve whichever came first. Sorting by how many segments a path leaves free means a
 * URL is attributed to the most literal path that can accept it, which is also how a router does it.
 */
let matchers: { path: string; regex: RegExp }[] | undefined
function pathMatchers(): { path: string; regex: RegExp }[] {
  matchers ??= Object.keys(spec().paths)
    .map((path) => ({
      path,
      variables: (path.match(/\{/g) ?? []).length,
      regex: new RegExp(`^${path.replace(/\{[^}]*\}/g, '[^/]+')}$`),
    }))
    .sort((a, b) => a.variables - b.variables)
  return matchers
}

/** The contract path a request URL resolves to, query string discarded. */
export function contractPathOf(url: string): string | undefined {
  const path = url.split('?')[0] ?? url
  return pathMatchers().find((matcher) => matcher.regex.test(path))?.path
}

/**
 * The JSON schema of the success response an operation declares.
 *
 * `undefined` has two causes the caller must keep apart — no such operation, and an operation whose
 * success answer carries no JSON body (a 204). Both are reported, in different words.
 */
export function responseSchemaOf(method: string, contractPath: string): Node | undefined {
  const operation = OPERATIONS.has(method) ? (spec().paths[contractPath]?.[method] as Node | undefined) : undefined
  const responses = operation?.responses as Record<string, Node> | undefined
  if (!responses) return undefined
  const status = Object.keys(responses).find((code) => code.startsWith('2'))
  const response = status === undefined ? undefined : responses[status]
  if (!response) return undefined
  const content = deref(response).content as Record<string, Node> | undefined
  const schema = content?.['application/json']?.schema
  return schema ? deref(schema as Node) : undefined
}

/**
 * What an operation declares it will accept.
 *
 * Four answers rather than a schema-or-nothing, because the seam's next move differs for each and
 * collapsing them would report the wrong thing three times: an operation that takes **no** body at
 * all is a legitimate `api.post(url)`; a **multipart** one is deliberately unchecked; and a URL that
 * resolves to no operation is already reported one layer up, so this only sees the method being
 * wrong for a path that exists.
 */
export type RequestBody =
  | { kind: 'json'; schema: Node }
  | { kind: 'multipart' }
  | { kind: 'none' }
  | { kind: 'no-operation' }

export function requestBodyOf(method: string, contractPath: string): RequestBody {
  const operation = OPERATIONS.has(method) ? (spec().paths[contractPath]?.[method] as Node | undefined) : undefined
  if (!operation) return { kind: 'no-operation' }
  const body = operation.requestBody as Node | undefined
  if (!body) return { kind: 'none' }
  const content = deref(body).content as Record<string, Node> | undefined
  const schema = content?.['application/json']?.schema
  if (!schema) return { kind: 'multipart' }
  return { kind: 'json', schema: deref(schema as Node) }
}

/**
 * Every branch of an `allOf`, flattened and resolved.
 *
 * `required` does not descend through `allOf` — the spec says so in its own comments — so the
 * branches have to be read together: one contributes `PageMeta`'s required keys and the next
 * contributes `items` and `facets`, and a reader that took only the first would report the second's
 * properties as undeclared.
 */
function branches(schema: Node): Node[] {
  const all = schema.allOf as Node[] | undefined
  return all ? all.flatMap((branch) => branches(deref(branch))) : [schema]
}

/**
 * The scalar constraints that apply at a node, read **through** `allOf` for the same reason
 * `branches` exists.
 *
 * A nullable reference is spelled `allOf: [{ $ref: UserRef }]` plus `nullable: true` — 36 sites, and
 * the only legal spelling in OpenAPI 3.0, because a `$ref` may carry no siblings. `deref` returns
 * such a node unchanged (it has no direct `$ref`), so a reader that looked only at the node itself
 * would find neither the `enum` under the reference nor, for `{ $ref: PlanStatus }` wrapped this way,
 * the members it is meant to check against.
 */
interface Constraints {
  // Spelled `| undefined` rather than left to `?` alone because the shared region has to compile
  // under `packages/`'s `exactOptionalPropertyTypes`, where assigning `undefined` to an optional
  // property is an error unless the type admits it.
  enums?: unknown[] | undefined
  type?: string | undefined
  format?: string | undefined
  nullable: boolean
}

function constraintsOf(schema: Node): Constraints {
  let enums: unknown[] | undefined
  let type: string | undefined
  let format: string | undefined
  let nullable = schema.nullable === true
  for (const branch of branches(schema)) {
    if (Array.isArray(branch.enum)) enums ??= branch.enum
    if (typeof branch.type === 'string') type ??= branch.type
    if (typeof branch.format === 'string') format ??= branch.format
    if (branch.nullable === true) nullable = true
  }
  return { enums, type, format, nullable }
}

/** Does a value of this shape satisfy the declared JSON type? Unknown types accept everything. */
function accepts(type: string, value: unknown): boolean {
  switch (type) {
    case 'string':
      return typeof value === 'string'
    case 'boolean':
      return typeof value === 'boolean'
    case 'number':
      return typeof value === 'number' && Number.isFinite(value)
    // JSON has one number type, so an integer is a number that happens to be whole. `1.5` for a
    // count is a defect; `1` for a `number` is not.
    case 'integer':
      return typeof value === 'number' && Number.isInteger(value)
    case 'array':
      return Array.isArray(value)
    case 'object':
      return typeof value === 'object' && value !== null && !Array.isArray(value)
    default:
      return true
  }
}

/** What a body actually carries, in the vocabulary the schema uses, so the two can be compared. */
function shapeOf(value: unknown): string {
  if (Array.isArray(value)) return 'an array'
  if (value === null) return 'null'
  if (typeof value === 'number') return Number.isInteger(value) ? `the integer ${value}` : `the number ${value}`
  if (typeof value === 'string') return `the string ${JSON.stringify(value)}`
  if (typeof value === 'boolean') return `the boolean ${value}`
  if (typeof value === 'object') return 'an object'
  return typeof value
}

/**
 * An RFC 3339 instant, which is what `format: date-time` means and what `lib/format.ts` parses.
 *
 * The regex is the shape and `Date.parse` is the validity — `2026-13-01T00:00:00Z` satisfies the
 * first and not the second, and a date fixture is exactly the kind of literal somebody types a month
 * number into.
 */
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/

export interface Violation {
  /** Where in the body, as a path a reader can follow: `$.items[0].owner`. */
  where: string
  message: string
}

/**
 * Which way the body was travelling.
 *
 * It changes three sentences and nothing else. A violation is read by somebody deciding what to
 * repair, and "absent from the body this double served" sends them to a fixture while "absent from
 * the payload this call sent" sends them to the code that built it — which for a request is usually
 * `api/queries/` rather than a test at all.
 */
export type Direction = 'served' | 'sent'

const WORDS = {
  served: {
    missing: 'required by the contract, absent from the body this double served',
    undeclared: 'served by this double, declared nowhere in the contract',
    null: 'The server always sends a value here, so a component may read straight through it.',
  },
  sent: {
    missing: 'required by the contract, absent from the payload this call sent',
    undeclared: 'sent by this call, declared nowhere in the contract',
    null: 'Jackson reads an absent field and an explicit null as the same value, so this clears it.',
  },
} satisfies Record<Direction, Record<string, string>>

/** Every way this body disagrees with the schema. Empty is the answer for a body the server sends. */
export function violationsOf(schema: Node, body: unknown, direction: Direction = 'served'): Violation[] {
  const out: Violation[] = []
  walk(schema, body, '$', out, 0, direction)
  return out
}

function walk(
  schema: Node,
  value: unknown,
  where: string,
  out: Violation[],
  depth: number,
  direction: Direction,
): void {
  // A guard against a `$ref` cycle in the document rather than a limit on how deep a body may be:
  // nothing the API serves nests eight levels, and a self-referential schema would otherwise not
  // terminate. Doc blocks are the deepest at four.
  if (depth > 8) return
  if (value === undefined) return
  if (schema.oneOf || schema.anyOf) return

  const { enums, type, format, nullable } = constraintsOf(schema)

  if (value === null) {
    if (!nullable) {
      out.push({
        where,
        message: `null, where the contract declares no \`nullable: true\`. ${WORDS[direction].null}`,
      })
    }
    return
  }

  if (enums && !enums.includes(value)) {
    out.push({
      where,
      message:
        `${shapeOf(value)}, which is not one of the members the contract lists: `
        + `${(enums as unknown[]).map((member) => JSON.stringify(member)).join(', ')}.`,
    })
    return
  }

  if (type && !accepts(type, value)) {
    out.push({ where, message: `${shapeOf(value)}, where the contract declares \`${type}\`.` })
    return
  }

  if (format === 'date-time' && typeof value === 'string' && (!INSTANT.test(value) || Number.isNaN(Date.parse(value)))) {
    out.push({
      where,
      message:
        `${shapeOf(value)}, which is not the \`date-time\` the contract declares. \`lib/format.ts\` `
        + 'renders `Invalid Date` for one of these rather than throwing.',
    })
    return
  }

  if (type === 'array') {
    const items = branches(schema).map((branch) => branch.items).find(Boolean)
    if (!items || !Array.isArray(value)) return
    const element = deref(items as Node)
    value.forEach((member, index) => walk(element, member, `${where}[${index}]`, out, depth + 1, direction))
    return
  }

  const declared = new Map<string, Node>()
  const required = new Set<string>()
  let open = false
  for (const branch of branches(schema)) {
    for (const [name, property] of Object.entries((branch.properties ?? {}) as Record<string, Node>)) {
      declared.set(name, deref(property))
    }
    for (const name of (branch.required ?? []) as string[]) required.add(name)
    // A string map (`additionalProperties: { type: string }`) declares no property names, so the
    // undeclared-property question does not apply to it — `configuration.attributes` is one.
    if (branch.additionalProperties) open = true
  }

  // A schema with no declared properties describes something this check has nothing to say about:
  // a free-form object, an enum, a scalar.
  if (declared.size === 0) return
  // Reached only when the schema names properties without naming a `type`, which the type check
  // above would otherwise have answered: declaring properties is what makes it an object.
  if (typeof value !== 'object' || Array.isArray(value)) {
    out.push({ where, message: `${shapeOf(value)}, where the contract declares an object.` })
    return
  }

  const record = value as Record<string, unknown>
  for (const name of required) {
    if (!(name in record)) {
      out.push({ where: `${where}.${name}`, message: WORDS[direction].missing })
    }
  }
  if (!open) {
    for (const name of Object.keys(record)) {
      if (!declared.has(name)) {
        out.push({ where: `${where}.${name}`, message: WORDS[direction].undeclared })
      }
    }
  }
  for (const [name, property] of declared) {
    if (name in record) walk(property, record[name], `${where}.${name}`, out, depth + 1, direction)
  }
}

/* ==== SHARED REGION END ==== */

/**
 * The 2xx statuses an operation declares, in document order.
 *
 * Outside the shared region because it answers a question the SPA's seam **cannot ask**: over there
 * the double replaces a method on the `api` object, which returns `{ data }` and no status at all —
 * so a copy of this in `frontend/`'s walker would be an export with no possible caller. Here the
 * double is a `fetch` and the status is one of its arguments.
 *
 * `undefined` means the path declares no such operation, which `checkSent` already reports in its own
 * words; an empty array means an operation with no 2xx response at all, which no operation in this
 * document has.
 */
export function successStatusesOf(method: string, contractPath: string): number[] | undefined {
  const operation = OPERATIONS.has(method)
    ? (spec().paths[contractPath]?.[method] as Node | undefined)
    : undefined;
  const responses = operation?.responses as Record<string, Node> | undefined;
  if (!responses) return undefined;
  return Object.keys(responses)
    .filter((code) => code.startsWith('2'))
    .map(Number)
    .filter((code) => Number.isInteger(code));
}

/**
 * The property names and the `required` list of one named component schema.
 *
 * Outside the shared region on the region's own economics rather than on `successStatusesOf`'s
 * argument: the SPA *could* ask this, and does not. The region is byte-identical and paired line by
 * line, so a line only one stack calls is a line the other maintains for nothing — which is the
 * readerless shape the pairing rule exists beside, not a version of it. If `frontend/` ever wants
 * it, it moves in and the pairing test says so on the same commit.
 *
 * Written for `problemShape.test.ts`, which asks it of `Problem`.
 */
export function schemaProperties(name: string): { properties: string[]; required: string[] } {
  const schemas = spec().components.schemas as Record<string, Node> | undefined;
  const schema = schemas?.[name];
  if (!schema) throw new Error(`openapi.yaml declares no schema '${name}'.`);
  return {
    properties: Object.keys((schema.properties as Node | undefined) ?? {}),
    required: ((schema.required as string[] | undefined) ?? []).slice(),
  };
}

/**
 * The header parameters an operation declares, lower-cased, in document order.
 *
 * Outside the shared region for `successStatusesOf`'s reason, one argument further along: the SPA's
 * double replaces a method on the `api` object, and a header never reaches it — `frontend/src/api/client.ts`
 * composes them *inside* the call the double replaced. Here the double is a `fetch` and the headers are
 * one of its arguments, so this is a question only this stack can ask, and a copy over there would be an
 * export with no possible caller.
 *
 * Path-level parameters are merged with the operation's, which is what the spec says an operation
 * accepts; a `$ref` is followed because every header in this document is declared once under
 * `components/parameters` and referenced.
 *
 * `undefined` means the path declares no such operation — the case `checkSent` already reports in its
 * own words.
 */
export function declaredHeadersOf(method: string, contractPath: string): string[] | undefined {
  const item = spec().paths[contractPath];
  const operation = item && OPERATIONS.has(method) ? (item[method] as Node | undefined) : undefined;
  if (!operation) return undefined;

  const declared = [
    ...((item?.parameters as Node[] | undefined) ?? []),
    ...((operation.parameters as Node[] | undefined) ?? []),
  ];

  return declared
    .map((parameter) => deref(parameter))
    .filter((parameter) => parameter.in === 'header')
    .map((parameter) => String(parameter.name).toLowerCase());
}

/**
 * The contract path a URL sent by `@tcms/client` resolves to, with the server prefix taken off.
 *
 * Outside the shared region because the SPA has no prefix to take off: it hands its client a
 * contract-relative path and the browser resolves it, so `contractPathOf('/me')` is the whole of the
 * question over there. Here every URL is absolute and carries `/api/v1`, which matches no path in the
 * document — a reader that skipped this step would resolve **every** request to nothing and report a
 * hundred URLs the contract does not declare, which is the same as reporting none.
 *
 * The prefix is read off the document's own `servers` entry rather than written down here, for §48's
 * reason: a literal `'/api/v1'` in a checker is a second declaration of the contract's base path, and
 * it agrees until the day the contract moves.
 */
export function contractPathOfRequest(pathname: string): string | undefined {
  const prefix = serverPrefix();
  if (prefix && !pathname.startsWith(prefix)) return undefined;
  return contractPathOf(pathname.slice(prefix.length));
}

let prefix: string | undefined;
function serverPrefix(): string {
  prefix ??= (spec().servers?.[0]?.url ?? '').replace(/\/$/, '');
  return prefix;
}
