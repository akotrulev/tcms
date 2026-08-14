/**
 * Does this response body describe something the server could actually send?
 *
 * ## The population this exists for
 *
 * §196 removed every `as TestCase`-shaped assertion from the tree and left one behind that it could
 * not reach: **`as never`**. `vi.spyOn(api, 'get').mockImplementation((async (path: string) => …) as
 * never)` is how every mocked read in this repo satisfies a client that is generic over its
 * response, and `never` carries no shape at all — so the body a test *serves* is unconstrained by
 * the contract in a way no type-level check can see. There are 229 of those casts across 76 files.
 *
 * The cast is not the defect and cannot be removed: `api.get<T>` resolves `T` from the call site,
 * and a spy has no call site. What can be compared is the **value**, at the moment the double hands
 * it over — which is what this module does, and why the instrument is a runtime one where §196's was
 * a compiler one.
 *
 * ## What it compares against
 *
 * `openapi.yaml` itself, read through Vite's `?raw` import rather than `node:fs`. The other contract
 * checks (`contractIntrospection.ts` and friends) read the file off disk and therefore need
 * `types: ["node"]`, which is exactly what `tsconfig.app.json` must not have — so they are excluded
 * from the app project and included in the node one. **This module cannot make that trade**: it is
 * imported by the test harness, which is imported by every test, so an exclusion would not keep it
 * out of the app program anyway (`exclude` drops a file from the root set, not from the graph of
 * what the roots import). The `?raw` import is the form that needs no node types, and
 * `vitest.config.ts` carries the one line of `server.fs.allow` that lets a file above the Vite root
 * be read.
 *
 * ## What "violation" means here, and what it deliberately does not
 *
 * Six questions, all computed from the declared schema. The first two are structural and are §197's:
 *
 *   - a **required** property the body does not carry — the failure §196 measured, arriving through
 *     the other door: a fixture that stands in for a response the day before a component reads the
 *     field it omits;
 *   - a property the body carries that the schema **does not declare** — the sharper of the two,
 *     because an undeclared property is usually not a typo but a double answering a question the
 *     server was never asked (a `/projects/{id}` body served for `/projects/{id}/settings`), or a
 *     vocabulary the product removed and the fixture kept.
 *
 * The other four are the **value**, and are §198's:
 *
 *   - a string the schema declares an `enum` for, whose value is not a member. This is the one the
 *     rung was named for: `status: 'ACTIVE'` sat in `RunCreateDialog` for four slices, is not a
 *     `PlanStatus`, and was found by a person reading rather than by anything running;
 *   - a value of the wrong **type** — a `number` where the schema says string, an object where it
 *     says array, `'0'` where it says integer;
 *   - a **null** in a property the contract does not mark `nullable`. The mirror of the required
 *     check and not the same question: `'owner' in body` is satisfied by `owner: null`, so a body
 *     that nulls a field the server always fills passes every structural check there is;
 *   - a `format: date-time` string that is not one. These reach `lib/format.ts`, which renders
 *     `Invalid Date` rather than throwing, so a malformed instant is a screen defect that no test
 *     asserting on *other* text would notice.
 *
 * **`format: uuid` is NOT checked, and that is a decision rather than an omission.** The contract
 * declares it 208 times and not one id in this suite is a UUID — `'p1'`, `'c1'`, `'TS-1'` — because
 * an id here is a thing a reader has to recognise across four files. Enforcing the format would
 * rewrite every one of them as a 36-character string and buy nothing: the SPA treats an id as opaque
 * (it goes into a URL and comes back), so unlike an enum member or a timestamp there is no code
 * downstream that can tell the two apart. The other formats the document uses (`email`, `uri`,
 * `int32`/`int64`) are three and five sites; the integers are covered by the type check and the rest
 * are not worth a rule. `pattern` is three sites and is skipped on the same grounds as `uuid` — two
 * of the three are display keys, which a fixture spells readably on purpose.
 *
 * `oneOf` / `anyOf` schemas are skipped rather than guessed at: reporting the union's *intersection*
 * of required properties would be wrong in one direction and reporting one arm's would be wrong in
 * the other, and a check that reports a defect nobody can fix is worse than one that says nothing.
 */
import { parse } from 'yaml'
import specText from '../../../docs/specs/openapi.yaml?raw'

/** A node of the parsed document. Untyped by intent — this walks a schema, it does not model one. */
type Node = Record<string, unknown>

interface Document {
  paths: Record<string, Node>
  components: Node
}

/**
 * Parsed on **first use**, not on import.
 *
 * The module is reached through the test harness, so it is loaded once per test file — 99 of them —
 * and parsing 550 kB of YAML measures at ~110 ms each. Roughly forty of those files never build an
 * `api` double at all, and a lazy parse is the difference between charging them for this and not.
 */
let document: Document | undefined
function spec(): Document {
  document ??= parse(specText) as Document
  return document
}

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
  return pathMatchers().find((matcher) => matcher.regex.test(url.split('?')[0]))?.path
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
  if (!status) return undefined
  const content = deref(responses[status]).content as Record<string, Node> | undefined
  const schema = content?.['application/json']?.schema
  return schema ? deref(schema as Node) : undefined
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
  enums?: unknown[]
  type?: string
  format?: string
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

/** Every way this body disagrees with the schema. Empty is the answer for a body the server sends. */
export function violationsOf(schema: Node, body: unknown): Violation[] {
  const out: Violation[] = []
  walk(schema, body, '$', out, 0)
  return out
}

function walk(schema: Node, value: unknown, where: string, out: Violation[], depth: number): void {
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
        message:
          'null, where the contract declares no `nullable: true`. The server always sends a value '
          + 'here, so a component may read straight through it.',
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
    value.forEach((member, index) => walk(element, member, `${where}[${index}]`, out, depth + 1))
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
      out.push({
        where: `${where}.${name}`,
        message: 'required by the contract, absent from the body this double served',
      })
    }
  }
  if (!open) {
    for (const name of Object.keys(record)) {
      if (!declared.has(name)) {
        out.push({
          where: `${where}.${name}`,
          message: 'served by this double, declared nowhere in the contract',
        })
      }
    }
  }
  for (const [name, property] of declared) {
    if (name in record) walk(property, record[name], `${where}.${name}`, out, depth + 1)
  }
}
