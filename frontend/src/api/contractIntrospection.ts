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
