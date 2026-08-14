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
 * Two questions, both computed from the declared schema:
 *
 *   - a **required** property the body does not carry — the failure §196 measured, arriving through
 *     the other door: a fixture that stands in for a response the day before a component reads the
 *     field it omits;
 *   - a property the body carries that the schema **does not declare** — the sharper of the two,
 *     because an undeclared property is usually not a typo but a double answering a question the
 *     server was never asked (a `/projects/{id}` body served for `/projects/{id}/settings`), or a
 *     vocabulary the product removed and the fixture kept.
 *
 * Values are **not** type-checked. A `number` where the schema says string is a defect this could
 * report and does not, because the population it was written for is structural and a value check
 * would drag in format-level questions (`date-time`, `uuid`) whose false positives would arrive as
 * noise in 76 files at once. It is a rung, in the sense §53 uses the word.
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
  if (value === null || value === undefined) return
  if (schema.oneOf || schema.anyOf) return

  if (schema.type === 'array') {
    const items = schema.items ? deref(schema.items as Node) : undefined
    if (!items || !Array.isArray(value)) return
    value.forEach((element, index) => walk(items, element, `${where}[${index}]`, out, depth + 1))
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
  if (typeof value !== 'object' || Array.isArray(value)) return

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
