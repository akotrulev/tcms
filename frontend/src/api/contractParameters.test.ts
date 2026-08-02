/**
 * Every query parameter the SPA sends must be one `openapi.yaml` declares for that path.
 *
 * This is the check §47 and §49 each ended by naming, having each shipped the same defect.
 * The mechanism both times: **Spring silently ignores a query parameter no handler binds.**
 * The request succeeds, the server answers the broader question, and the screen renders that
 * answer under the heading the client had already written.
 *
 *   §47 — the dashboard's by-owner tile linked with `ownerId`, undeclared, so "Ana · Approved
 *         · 34" opened *everyone's* 210 approved cases.
 *   §49 — `useCaseHistory` had sent `?scope=CURRENT_VERSION` since Phase 1, undeclared, so the
 *         "Current version" chip would have summarized every version's results.
 *
 * §47's repair was a check over dashboard *filter keys* (`DashboardEndpointTest`), which is
 * why §49's defect got through: that one reads keys out of a live response body and cannot see
 * a query string written by hand in `api/queries/`. This one reads the query strings.
 *
 * ## Why static analysis rather than a runtime assertion
 *
 * The alternative is for `qs()` to validate its keys against the contract at runtime in dev.
 * That only checks the call sites a test or a session actually reaches, and both defects lived
 * on paths that *were* reached — by a human, who read the confident wrong answer and believed
 * it. A parameter is dropped in silence, so the failure has to be found without executing it.
 *
 * The compiler is doing the reading here, not a regex: the case list spreads `...filters`, and
 * the fourteen keys that spread contributes are a *type*, not text. `CaseFilters` gaining a
 * field is exactly how the next occurrence arrives, and only the checker can see it.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { parse } from 'yaml';
import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const QUERIES_DIR = resolve(HERE, 'queries');
const SPEC = resolve(HERE, '../../../docs/specs/openapi.yaml');

/** As spelled in an OpenAPI path item; anything else under a path is metadata. */
const OPERATION_KEYS = new Set(['get', 'put', 'post', 'delete', 'patch', 'head', 'options', 'trace']);

/** A path with its variables blanked, so `/cases/{caseId}` and a built `/cases/${id}` compare. */
const shape = (path: string) => path.replace(/\{[^}]*\}/g, '{}');

// --------------------------------------------------------------- the contract ----

interface Parameter {
  in?: string;
  name?: string;
  $ref?: string;
}

interface Spec {
  paths: Record<string, Record<string, unknown> & { parameters?: Parameter[] }>;
}

const spec = parse(readFileSync(SPEC, 'utf8')) as Spec;

function deref(node: Parameter): Parameter {
  if (!node.$ref) return node;
  let current: unknown = spec;
  for (const segment of node.$ref.slice(2).split('/')) {
    current = (current as Record<string, unknown>)[segment];
  }
  return deref(current as Parameter);
}

/**
 * Every query parameter name declared for a path — the path item's own block plus each
 * operation's. Union rather than per-operation on purpose: this side of the check asks "would
 * the server bind this key at all", and the SPA's `qs()` call sites do not carry their HTTP
 * method in a form worth recovering. The per-operation question — *this* handler must bind
 * *this* declaration — is the backend's `ContractParameterBindingTest`, and the two together
 * close the loop.
 */
function declaredFor(path: string): Set<string> {
  const item = spec.paths[path];
  const names = new Set<string>();
  const collect = (parameters?: Parameter[]) => {
    for (const entry of parameters ?? []) {
      const parameter = deref(entry);
      if (parameter.in === 'query' && parameter.name) names.add(parameter.name);
    }
  };
  collect(item.parameters);
  for (const [key, value] of Object.entries(item)) {
    if (OPERATION_KEYS.has(key)) collect((value as { parameters?: Parameter[] }).parameters);
  }
  return names;
}

const contractPathByShape = new Map(Object.keys(spec.paths).map((path) => [shape(path), path]));

// ------------------------------------------------------------------ the SPA ----

const sources = readdirSync(QUERIES_DIR)
  .filter((file) => file.endsWith('.ts') && !file.endsWith('.test.ts'))
  .map((file) => join(QUERIES_DIR, file));

const program = ts.createProgram(sources, {
  target: ts.ScriptTarget.ESNext,
  module: ts.ModuleKind.ESNext,
  moduleResolution: ts.ModuleResolutionKind.Bundler,
  strict: true,
  skipLibCheck: true,
  noEmit: true,
});
const checker = program.getTypeChecker();

interface CallSite {
  where: string;
  urlShape: string;
  keys: string[];
  /** Spread expressions that contributed no properties — a resolution failure, not a clean call. */
  emptySpreads: string[];
}

/**
 * Renders a template literal as a path shape, substituting `{}` for each interpolation.
 *
 * Two spans get special treatment. The `qs(...)` call contributes nothing — it *is* the query
 * string, not part of the path. A call to a helper whose whole body is another template
 * literal (`base(projectId)` → `` `/projects/${projectId}` ``) is expanded through, rather
 * than mapped by a hard-coded string here: `base` is the one path helper every module builds
 * URLs from, and a literal `'/projects/{}'` in this file would be a second definition of it,
 * agreeing right up until someone changes the real one.
 */
function pathShapeOf(template: ts.TemplateExpression): string {
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
 * `getAliasedSymbol` therefore fails to expand every helper that lives in another module, which
 * is all of them, and the failure is a shape that matches no contract path rather than an
 * error. The vacuity guard above is what turned that into a visible failure.
 */
function arrowBodyOf(identifier: ts.Identifier): ts.Expression | undefined {
  let symbol = checker.getSymbolAtLocation(identifier);
  if (symbol && symbol.flags & ts.SymbolFlags.Alias) symbol = checker.getAliasedSymbol(symbol);
  const declaration = symbol?.declarations?.[0];
  if (!declaration || !ts.isVariableDeclaration(declaration)) return undefined;
  const initializer = declaration.initializer;
  if (!initializer || !ts.isArrowFunction(initializer)) return undefined;
  return ts.isBlock(initializer.body) ? undefined : initializer.body;
}

function collectCallSites(): CallSite[] {
  const sites: CallSite[] = [];

  for (const file of sources) {
    const source = program.getSourceFile(file);
    if (!source) throw new Error(`TypeScript did not load ${file}`);

    const visit = (node: ts.Node): void => {
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === 'qs') {
        const keys: string[] = [];
        const emptySpreads: string[] = [];
        const argument = node.arguments[0];

        if (argument && ts.isObjectLiteralExpression(argument)) {
          for (const property of argument.properties) {
            if (ts.isShorthandPropertyAssignment(property) || ts.isPropertyAssignment(property)) {
              keys.push(property.name.getText(source));
            } else if (ts.isSpreadAssignment(property)) {
              const spread = checker.getPropertiesOfType(
                checker.getTypeAtLocation(property.expression),
              );
              if (spread.length === 0) emptySpreads.push(property.expression.getText(source));
              for (const member of spread) keys.push(member.getName());
            }
          }
        }

        let template: ts.Node | undefined = node.parent;
        while (template && !ts.isTemplateExpression(template)) template = template.parent;

        const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
        sites.push({
          where: `${file.slice(file.lastIndexOf('/') + 1)}:${line}`,
          urlShape: template ? pathShapeOf(template as ts.TemplateExpression) : '(not in a URL template)',
          keys,
          emptySpreads,
        });
      }
      ts.forEachChild(node, visit);
    };

    visit(source);
  }

  return sites;
}

const callSites = collectCallSites();

// ------------------------------------------------------------------- checks ----

describe('contract parameters — every key the SPA sends is one the server binds', () => {
  /**
   * The guard that keeps the rest of this file from passing vacuously. Every assertion below
   * iterates something derived from the compiler, and each of the three ways this machinery can
   * quietly break — the program failing to load the sources, `qs` being renamed, the checker
   * declining to resolve a spread — ends in an empty iteration and a green test. A check whose
   * failure mode is silence is the defect it was written about.
   */
  /*
    The floor is hand-maintained on purpose and moving it DOWN deserves a moment's suspicion, since
    "the analyser stopped finding things" and "there is genuinely one fewer call site" fail
    identically here. It went 10 → 9 in §63, when `useRoleAssignments` stopped sending `?projectId=`
    — verified as exactly that by counting `qs(` in `api/queries/` before and after the change, not
    by assuming. Deriving this number from the same traversal it guards would make it agree with a
    broken analyser, which is the whole failure this assertion exists to catch.
  */
  it('found the SPA query strings it is written to inspect', () => {
    expect(callSites.length, 'no qs() call sites found — has the helper been renamed?').toBeGreaterThanOrEqual(9);
    expect(
      callSites.flatMap((site) => site.emptySpreads),
      'a spread contributed no properties: the checker did not resolve its type, so the keys it '
        + 'carries were never compared against anything',
    ).toEqual([]);
    expect(
      callSites.find((site) => site.urlShape === '/projects/{}/cases')?.keys.length,
      'the case list spreads CaseFilters and must contribute its whole filter set',
    ).toBeGreaterThanOrEqual(10);
  });

  it.each(callSites.map((site) => [site.where, site] as const))(
    '%s sends only declared parameters',
    (_where, site) => {
      const contractPath = contractPathByShape.get(site.urlShape);

      expect(
        contractPath,
        `${site.where} builds ${site.urlShape}, which matches no path in openapi.yaml. Either the `
          + 'SPA is calling an endpoint the contract does not describe, or the path was renamed on '
          + 'one side only.',
      ).toBeDefined();

      const declared = declaredFor(contractPath as string);
      const undeclared = site.keys.filter((key) => !declared.has(key));

      expect(
        undeclared,
        `${site.where} sends ${undeclared.join(', ')} to ${contractPath}, which the contract does `
          + 'not declare.\n\n'
          + 'An undeclared query parameter is not rejected — it is IGNORED. The request returns '
          + '200 carrying the answer to a wider question than the one asked, and the screen '
          + 'renders it under the heading it had already written. That is §47 (a tile linking to '
          + "everyone's cases under one person's name) and §49 (a version chip that would have "
          + 'answered with every version).\n\n'
          + 'Declare it in openapi.yaml on the operation that reads it, run `npm run gen:api`, and '
          + 'bind it in the controller — the backend\'s ContractParameterBindingTest checks that '
          + `last step.\n\nDeclared for ${contractPath}: ${[...declared].join(', ') || '(none)'}`,
      ).toEqual([]);
    },
  );
});
