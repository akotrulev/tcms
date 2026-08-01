/**
 * Every response type the SPA declares must come from the **generated** contract types, never
 * from a shape written by hand at the call site.
 *
 * This is the rung above §50. That check compares the query *keys* the SPA sends against the
 * contract; this one is about what it expects *back*, and the defect it was written for had been
 * live since Phase 1:
 *
 *   §52 — `useSuiteHistory` declared `api.get<{ runs: SuiteRunSummary[]; versionMarkers: … }>`
 *         while `openapi.yaml` called the array `entries`. The MSW handler was written from the
 *         same hand-written shape, so mock mode agreed with itself perfectly — and the real
 *         server would have answered `entries`, leaving the page reading `undefined`.
 *
 * **The compiler cannot catch this on its own**, which is the whole reason for the rule. A type
 * argument is an assertion about the response, not a comparison with anything: `api.get<T>` casts
 * the parsed body to `T` and TypeScript has no second opinion to check it against. Only naming a
 * generated type puts the contract on the other side of the equals sign — after which a renamed
 * field is a build error at every consumer, which is exactly what happened to `data.runs` when
 * this hook was repaired.
 *
 * So the rule is structural rather than semantic: **no inline object or array-of-object literal
 * as a type argument to an `api.*` call.** It cannot be "the literal must match the schema",
 * because a checker that could decide that would be re-implementing the generator. Requiring the
 * generated name is the version of the rule a machine can enforce, and it costs one line in
 * `types.ts` per response shape.
 */
import { readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const QUERIES_DIR = resolve(HERE, 'queries');

/** The client's verbs. Each takes the response type as its single type argument. */
const VERBS = new Set(['get', 'post', 'patch', 'put', 'delete']);

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

interface TypeArgument {
  where: string;
  text: string;
  /** True when the argument is a structural literal rather than a named type. */
  inline: boolean;
}

/**
 * Whether a type node is (or wraps) an object literal type.
 *
 * `Paged<TestCaseSummary>` and `TestCase[]` are fine — both name generated types. `{ runs: … }`
 * and `{ a: number }[]` are not. Unwrapping arrays matters: `api.get<{ id: string }[]>` is the
 * same defect one bracket deeper, and it is the more natural thing to type by hand.
 */
function isInlineShape(node: ts.TypeNode): boolean {
  if (ts.isTypeLiteralNode(node)) return true;
  if (ts.isArrayTypeNode(node)) return isInlineShape(node.elementType);
  if (ts.isParenthesizedTypeNode(node)) return isInlineShape(node.type);
  // A union or intersection is inline if any member is — `Foo | { bar: string }` smuggles one in.
  if (ts.isUnionTypeNode(node) || ts.isIntersectionTypeNode(node)) {
    return node.types.some(isInlineShape);
  }
  return false;
}

function collect(): TypeArgument[] {
  const found: TypeArgument[] = [];

  for (const file of sources) {
    const source = program.getSourceFile(file);
    if (!source) throw new Error(`TypeScript did not load ${file}`);

    const visit = (node: ts.Node): void => {
      if (
        ts.isCallExpression(node)
        && ts.isPropertyAccessExpression(node.expression)
        && ts.isIdentifier(node.expression.expression)
        && node.expression.expression.text === 'api'
        && VERBS.has(node.expression.name.text)
      ) {
        const argument = node.typeArguments?.[0];
        if (argument) {
          const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
          found.push({
            where: `${file.slice(file.lastIndexOf('/') + 1)}:${line}`,
            text: argument.getText(source),
            inline: isInlineShape(argument),
          });
        }
      }
      ts.forEachChild(node, visit);
    };

    visit(source);
  }

  return found;
}

const typeArguments = collect();

describe('api response types — the shape comes from the contract, not from the call site', () => {
  /**
   * The vacuity guard §50 earned within an hour of writing: every assertion below iterates
   * something derived from the compiler, so a program that failed to load, a renamed client, or a
   * verb set that stopped matching all end in an empty iteration and a green test.
   */
  it('found the api.* call sites it is written to inspect', () => {
    expect(
      typeArguments.length,
      'no api.get/post/patch/delete type arguments found — has the client been renamed, or did '
        + 'the program fail to load src/api/queries?',
    ).toBeGreaterThanOrEqual(25);
  });

  it.each(typeArguments.map((argument) => [argument.where, argument] as const))(
    '%s names a generated type',
    (_where, argument) => {
      expect(
        argument.inline ? argument.text : null,
        `${argument.where} declares its response as an inline shape:\n\n  ${argument.text}\n\n`
          + 'A type argument is an assertion, not a comparison — `api.get<T>` casts the body to T '
          + 'and nothing checks T against openapi.yaml. That is how `useSuiteHistory` spent five '
          + 'months expecting `{ runs: … }` from an endpoint whose contract says `entries` (§52): '
          + 'the hook and the MSW handler were written from the same hand-made shape, so mock mode '
          + 'agreed with itself and live mode would have rendered nothing.\n\n'
          + 'Add the schema to openapi.yaml if it has none, run `npm run gen:api`, export it from '
          + 'src/api/types.ts, and name it here.',
      ).toBeNull();
    },
  );
});
