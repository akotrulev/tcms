/**
 * Every URL the SPA calls must resolve to an operation `openapi.yaml` declares **with that method**.
 *
 * ## The rung §52 named, and the defect that was sitting there while it did
 *
 * §50 built the parameter check: every *query key* the SPA sends is one the contract declares. §52's
 * *Not done* then named the next rung precisely — a check that the URL itself resolves to a declared
 * operation with that method — and observed that `contractParameters.test.ts` structurally cannot
 * see it, because the calls that were wrong carried no query string at all.
 *
 * It was right. `POST /work-items` and `PATCH /work-items/{id}` had been called by the SPA since
 * Phase 1 and were **declared nowhere in the contract**. Sixteen slices, four contract-hygiene
 * checks, and two reviews of this exact seam went past them, because every existing check answers a
 * question about something the contract already knows about:
 *
 *   - `ContractConventionTest` (§48) checks declared operations have 4xx responses and `required`.
 *   - `contractParameters.test.ts` (§50) checks query keys against a declared path.
 *   - `ContractParameterBindingTest` (§50) checks declared parameters are bound by a handler.
 *
 * None of them can notice an operation that was never declared. An undeclared operation is invisible
 * to every check that starts from the spec — you have to start from the *caller*.
 *
 * ## Why the method matters as much as the path
 *
 * A path-only check would have passed on both defects: `/work-items` was declared, for `GET`. The
 * SPA's `POST` and `PATCH` to the same path resolve to a path that exists and to operations that do
 * not — so the generated types describe neither request body nor response, `npm run gen:api` emits
 * nothing for them, and both calls type-check only because `api.post<T>` casts. That is §52's
 * "`api.get<T>` is an assertion, not a comparison", arriving one level up: not a response shape the
 * contract disagreed with, but a whole operation it had never heard of.
 */
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import {
  OPERATION_KEYS,
  contractPathByShape,
  program,
  sources,
  spec,
  urlShapeOfArgument,
} from './contractIntrospection.js';

/** The HTTP verbs `api` exposes, as spelled on the client. */
const API_METHODS = new Set(['get', 'post', 'patch', 'put', 'delete']);

interface CallSite {
  where: string;
  method: string;
  urlShape: string;
}

function collectCallSites(): CallSite[] {
  const sites: CallSite[] = [];

  for (const file of sources) {
    const source = program.getSourceFile(file);
    if (!source) throw new Error(`TypeScript did not load ${file}`);

    const visit = (node: ts.Node): void => {
      if (
        ts.isCallExpression(node)
        && ts.isPropertyAccessExpression(node.expression)
        && ts.isIdentifier(node.expression.expression)
        && node.expression.expression.text === 'api'
        && API_METHODS.has(node.expression.name.text)
      ) {
        const argument = node.arguments[0];
        const urlShape = argument ? urlShapeOfArgument(argument) : undefined;
        const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;

        sites.push({
          where: `${file.slice(file.lastIndexOf('/') + 1)}:${line}`,
          method: node.expression.name.text,
          // An unrecognisable first argument is recorded rather than skipped. Skipping is how a
          // checker quietly stops covering a call site — the vacuity guard below turns it into a
          // failure instead.
          urlShape: urlShape ?? '(unrecognised URL expression)',
        });
      }
      ts.forEachChild(node, visit);
    };

    visit(source);
  }

  return sites;
}

const callSites = collectCallSites();

/** The methods declared for a contract path. */
function declaredMethods(path: string): Set<string> {
  return new Set(
    Object.keys(spec.paths[path]).filter((key) => OPERATION_KEYS.has(key)),
  );
}

describe('contract operations — every call the SPA makes is an operation the contract declares', () => {
  /**
   * The guard that keeps the rest of this file from passing vacuously.
   *
   * Every assertion below iterates something derived from the compiler, and each way this machinery
   * can quietly break — the program failing to load, `api` being renamed or destructured, a URL
   * built by a helper the shape reconstruction does not expand — ends in an empty iteration and a
   * green run. A check whose failure mode is silence is the defect it was written about.
   */
  it('found the SPA call sites it is written to inspect', () => {
    expect(
      callSites.length,
      'no api.* call sites found — has the client been renamed or destructured?',
    ).toBeGreaterThanOrEqual(40);

    expect(
      callSites.filter((site) => site.urlShape === '(unrecognised URL expression)'),
      'an api.* call was found whose URL could not be reconstructed, so it was never compared '
        + 'against the contract. Teach contractIntrospection.urlShapeOfArgument about it rather '
        + 'than letting it pass unchecked.',
    ).toEqual([]);

    expect(
      new Set(callSites.map((site) => site.method)).size,
      'only one HTTP method seen across every call site — the method is what this check adds over '
        + 'a path-only one, so seeing one verb means it is not discriminating',
    ).toBeGreaterThanOrEqual(3);
  });

  it.each(callSites.map((site) => [`${site.method.toUpperCase()} ${site.where}`, site] as const))(
    '%s calls a declared operation',
    (_where, site) => {
      const contractPath = contractPathByShape.get(site.urlShape);

      expect(
        contractPath,
        `${site.where} calls ${site.method.toUpperCase()} ${site.urlShape}, which matches no path `
          + 'in openapi.yaml at all. Either the SPA is calling an endpoint the contract does not '
          + 'describe, or the path was renamed on one side only.',
      ).toBeDefined();

      const methods = declaredMethods(contractPath as string);

      expect(
        methods.has(site.method),
        `${site.where} calls ${site.method.toUpperCase()} ${contractPath}, and the contract `
          + `declares only ${[...methods].map((m) => m.toUpperCase()).join(', ')} for that path.\n\n`
          + 'An undeclared operation is invisible to every other contract check, because they all '
          + 'start from the spec. `npm run gen:api` emits no types for it, so the request body and '
          + 'the response shape are whatever the call site asserted them to be — and nothing on '
          + 'either side has to agree. POST and PATCH /work-items lived here for sixteen slices '
          + '(§53).\n\n'
          + 'Declare the operation in openapi.yaml, run `npm run gen:api`, and name the generated '
          + 'types at the call site.',
      ).toBe(true);
    },
  );
});
