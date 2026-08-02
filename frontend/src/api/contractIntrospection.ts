/**
 * The shared machinery behind the contract checks — the spec, the TypeScript program over
 * `api/queries/`, and the URL-shape reconstruction both checks need.
 *
 * ## Why this is a module rather than copied into each test
 *
 * `contractParameters.test.ts` (§50) grew this plumbing first, and `contractOperations.test.ts`
 * (§53) needs the same URL-shape reconstruction to ask a different question of the same call
 * sites. Copying forty lines of AST walking would be a second implementation of "what URL does this
 * line build" — and the two would agree exactly until the day somebody taught one of them about a
 * new helper. That is the failure mode `lib/labels.ts` and `lib/results.ts` exist to prevent on the
 * rendering side, and it applies with more force here, because a checker that quietly stops
 * understanding a call site does not fail: it reports nothing.
 *
 * Not a `.test.ts` file, deliberately — Vitest would collect it and report "no tests" every run.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { parse } from 'yaml';

const HERE = dirname(fileURLToPath(import.meta.url));
const QUERIES_DIR = resolve(HERE, 'queries');
const CLIENT = resolve(HERE, 'client.ts');
const SPEC = resolve(HERE, '../../../docs/specs/openapi.yaml');

/** As spelled in an OpenAPI path item; anything else under a path is metadata. */
export const OPERATION_KEYS = new Set([
  'get',
  'put',
  'post',
  'delete',
  'patch',
  'head',
  'options',
  'trace',
]);

/** A path with its variables blanked, so `/cases/{caseId}` and a built `/cases/${id}` compare. */
export const shape = (path: string) => path.replace(/\{[^}]*\}/g, '{}');

export interface Parameter {
  in?: string;
  name?: string;
  $ref?: string;
}

export interface Spec {
  paths: Record<string, Record<string, unknown> & { parameters?: Parameter[] }>;
}

export const spec = parse(readFileSync(SPEC, 'utf8')) as Spec;

export function deref(node: Parameter): Parameter {
  if (!node.$ref) return node;
  let current: unknown = spec;
  for (const segment of node.$ref.slice(2).split('/')) {
    current = (current as Record<string, unknown>)[segment];
  }
  return deref(current as Parameter);
}

/** Contract path keyed by its blanked shape, so a built URL can be looked up. */
export const contractPathByShape = new Map(
  Object.keys(spec.paths).map((path) => [shape(path), path]),
);

export const sources = readdirSync(QUERIES_DIR)
  .filter((file) => file.endsWith('.ts') && !file.endsWith('.test.ts'))
  .map((file) => join(QUERIES_DIR, file));

export const program = ts.createProgram(sources, {
  target: ts.ScriptTarget.ESNext,
  module: ts.ModuleKind.ESNext,
  moduleResolution: ts.ModuleResolutionKind.Bundler,
  strict: true,
  skipLibCheck: true,
  noEmit: true,
});

export const checker = program.getTypeChecker();

/**
 * Every verb `api` exposes, mapped to the HTTP method it actually sends — **read out of
 * `client.ts`**, not listed here.
 *
 * ## Why this is derived rather than written down
 *
 * Both checks that consume it used to carry their own `new Set(['get', 'post', 'patch', 'put',
 * 'delete'])`, one of them under the comment *"the HTTP verbs `api` exposes, **as spelled on the
 * client**"*. The client does not spell them that way. Its delete is `del` and it has an `upload`,
 * so `'delete'` matched **nothing** from the day it was written and `api.upload` was never in the
 * set at all: seven `api.del` call sites and one `api.upload` were invisible to *both* checks —
 * never compared against a declared operation, never refused an inline response type.
 *
 * Nothing could see it. Both files guard against vacuity by *count* (`>= 40`, `>= 25`) and by
 * distinct-verb count (`>= 3`), and every one of those thresholds is comfortably met by the four
 * verbs that do match. A hard-coded list of what another module exports is a second definition of
 * it, and this one had been wrong since §53 while reading as exhaustive — §48's rule, inside the
 * §48 family of checks: a catalogue-wide invariant needs a computed check, not a careful reading.
 *
 * The mapping matters as much as the membership. `contractOperations` asks whether the contract
 * declares this method for this path, and the key there is the OpenAPI spelling — so `del` has to
 * arrive as `delete` and `upload` as `post`, or teaching the check about them would only replace
 * "unchecked" with "compared against a method no path item can declare".
 *
 * The HTTP method comes from the `RequestInit` each verb hands `request`; a verb that passes no
 * `method` is a GET, which is exactly how `api.get` is written.
 */
function collectApiMethods(): { methods: Map<string, string>; unresolved: string[] } {
  const source = program.getSourceFile(CLIENT);
  if (!source) {
    throw new Error(
      `TypeScript did not load ${CLIENT} — the client is reached through the queries module's `
        + 'imports, so this means the program root set changed.',
    );
  }

  const methods = new Map<string, string>();
  const unresolved: string[] = [];

  const visit = (node: ts.Node): void => {
    if (
      ts.isVariableDeclaration(node)
      && ts.isIdentifier(node.name)
      && node.name.text === 'api'
      && node.initializer
      && ts.isObjectLiteralExpression(node.initializer)
    ) {
      for (const property of node.initializer.properties) {
        // Anything that is not a plain `name: (…) => …` is recorded as unresolved rather than
        // skipped. Skipping is the failure this whole function exists to end.
        if (!ts.isPropertyAssignment(property) || !ts.isIdentifier(property.name)) {
          unresolved.push(property.name?.getText(source) ?? '(unnamed member)');
          continue;
        }
        const method = httpMethodOf(property.initializer);
        if (method) methods.set(property.name.text, method);
        else unresolved.push(property.name.text);
      }
    }
    ts.forEachChild(node, visit);
  };

  visit(source);

  return { methods, unresolved };
}

/** The HTTP method a `<T>(path, …) => request<T>(path, { method: 'X' }, …)` verb sends. */
function httpMethodOf(initializer: ts.Expression): string | undefined {
  if (!ts.isArrowFunction(initializer) || ts.isBlock(initializer.body)) return undefined;
  const call = initializer.body;
  if (!ts.isCallExpression(call) || !ts.isIdentifier(call.expression)) return undefined;
  if (call.expression.text !== 'request') return undefined;

  const init = call.arguments[1];
  if (!init || !ts.isObjectLiteralExpression(init)) return undefined;

  for (const property of init.properties) {
    if (
      ts.isPropertyAssignment(property)
      && ts.isIdentifier(property.name)
      && property.name.text === 'method'
    ) {
      return ts.isStringLiteral(property.initializer)
        ? property.initializer.text.toLowerCase()
        : undefined;
    }
  }

  // `request(path, {}, options)` — no method, so `fetch`'s default. `api.get` is the only one.
  return 'get';
}

const collected = collectApiMethods();

/** Client verb → the OpenAPI operation key it sends. `del` → `delete`, `upload` → `post`. */
export const apiMethods: ReadonlyMap<string, string> = collected.methods;

/**
 * Members of the `api` object whose HTTP method could not be read.
 *
 * Exported so each consuming check can **fail** on a non-empty list rather than quietly covering a
 * smaller client than the one that ships. A new verb written in an unfamiliar shape must make a
 * test red; being dropped is precisely how `del` and `upload` went eight slices unchecked.
 */
export const unresolvedApiVerbs: readonly string[] = collected.unresolved;

/**
 * Renders a template literal as a path shape, substituting `{}` for each interpolation.
 *
 * Two spans get special treatment. The `qs(...)` call contributes nothing — it *is* the query
 * string, not part of the path. A call to a helper whose whole body is another template literal
 * (`base(projectId)` → `` `/projects/${projectId}` ``) is expanded through, rather than mapped by a
 * hard-coded string here: `base` is the one path helper every module builds URLs from, and a
 * literal `'/projects/{}'` in this file would be a second definition of it, agreeing right up until
 * someone changes the real one.
 */
export function pathShapeOf(template: ts.TemplateExpression): string {
  let out = template.head.text;
  for (const span of template.templateSpans) {
    out += renderSpan(span.expression) + span.literal.text;
  }
  return out;
}

function renderSpan(expression: ts.Expression): string {
  if (ts.isCallExpression(expression) && ts.isIdentifier(expression.expression)) {
    if (expression.expression.text === 'qs') return '';
    const body = arrowBodyOf(expression.expression);
    if (body && ts.isTemplateExpression(body)) return pathShapeOf(body);
    if (body && ts.isNoSubstitutionTemplateLiteral(body)) return body.text;
  }
  return '{}';
}

/**
 * The expression body of a single-expression arrow function an identifier resolves to.
 *
 * The alias hop is not optional. `base` is imported, so the symbol at the call site is the
 * *import*, whose only declaration is the `ImportSpecifier` — not the arrow function. Skipping
 * `getAliasedSymbol` therefore fails to expand every helper that lives in another module, which is
 * all of them, and the failure is a shape that matches no contract path rather than an error. Each
 * check's vacuity guard is what turns that into a visible failure.
 */
export function arrowBodyOf(identifier: ts.Identifier): ts.Expression | undefined {
  let symbol = checker.getSymbolAtLocation(identifier);
  if (symbol && symbol.flags & ts.SymbolFlags.Alias) symbol = checker.getAliasedSymbol(symbol);
  const declaration = symbol?.declarations?.[0];
  if (!declaration || !ts.isVariableDeclaration(declaration)) return undefined;
  const initializer = declaration.initializer;
  if (!initializer || !ts.isArrowFunction(initializer)) return undefined;
  return ts.isBlock(initializer.body) ? undefined : initializer.body;
}

/**
 * The path shape an `api.*` URL argument builds, or undefined when it is not a recognisable URL.
 *
 * Three forms, and the third is the one the first draft missed: a URL that is *entirely* a helper
 * call — `api.get<Project>(base(projectId!))` — with no template around it. `renderSpan` already
 * expands that helper when it appears **inside** a template, so the capability existed and simply
 * was not reachable from here; the vacuity guard in `contractOperations.test.ts` is what turned the
 * gap into a failure rather than a call site silently going unchecked.
 */
export function urlShapeOfArgument(argument: ts.Expression): string | undefined {
  if (ts.isTemplateExpression(argument)) return pathShapeOf(argument);
  if (ts.isNoSubstitutionTemplateLiteral(argument) || ts.isStringLiteral(argument)) {
    return argument.text;
  }
  if (ts.isCallExpression(argument) && ts.isIdentifier(argument.expression)) {
    const body = arrowBodyOf(argument.expression);
    if (body && ts.isTemplateExpression(body)) return pathShapeOf(body);
    if (body && (ts.isNoSubstitutionTemplateLiteral(body) || ts.isStringLiteral(body))) {
      return body.text;
    }
  }
  return undefined;
}
