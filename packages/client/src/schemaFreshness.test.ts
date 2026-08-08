/**
 * `schema.ts` is generated, and a generated file's failure mode is silence: it does not go wrong,
 * it goes *old*. Nothing in a build notices that `openapi.yaml` gained a path last week and this
 * package still types the contract as it was — every call site keeps compiling, against a shape
 * the server no longer has.
 *
 * The §48 rule, which this repo has paid for repeatedly: a catalogue-wide invariant needs a
 * computed check, not a careful reading. So this file reads the contract itself.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { parse } from 'yaml';
import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const SPEC = resolve(HERE, '../../../docs/specs/openapi.yaml');
const GENERATED = resolve(HERE, 'schema.ts');
/**
 * The SPA generates the same file from the same contract with the same tool. Comparing them is
 * what catches the half-done regeneration — one stack regenerated, the other not — which no
 * amount of checking either file against the spec alone can see, because each is self-consistent.
 */
const FRONTEND_GENERATED = resolve(HERE, '../../../frontend/src/api/schema.d.ts');

interface Spec {
  paths: Record<string, Record<string, unknown>>;
}

const spec = parse(readFileSync(SPEC, 'utf8')) as Spec;
const generated = readFileSync(GENERATED, 'utf8');

describe('the generated types are current with openapi.yaml', () => {
  // A vacuity guard, first: every assertion below is over a list, and a list that silently
  // became empty is a check that passes by knowing nothing.
  it('read a contract with paths in it', () => {
    expect(Object.keys(spec.paths).length).toBeGreaterThan(80);
  });

  it('types every path the contract declares', () => {
    const missing = Object.keys(spec.paths).filter(
      (path) => !generated.includes(`"${path}": {`),
    );

    expect(missing, 'run `npm run gen:api` — openapi.yaml has paths this package cannot type')
      .toEqual([]);
  });

  it('types every operation on every path', () => {
    const verbs = new Set(['get', 'put', 'post', 'delete', 'patch', 'head', 'options', 'trace']);
    // openapi-typescript writes `get?: never` for a verb a path does not declare, so presence of
    // the key proves nothing — the check is that a *declared* operation is not typed as `never`.
    const undeclared: string[] = [];

    for (const [path, item] of Object.entries(spec.paths)) {
      const start = generated.indexOf(`"${path}": {`);
      if (start === -1) continue;
      const block = generated.slice(start, generated.indexOf('\n    };', start));
      for (const verb of Object.keys(item)) {
        if (!verbs.has(verb)) continue;
        if (new RegExp(`\\b${verb}\\??: never`).test(block)) undeclared.push(`${verb} ${path}`);
      }
    }

    expect(undeclared, 'run `npm run gen:api`').toEqual([]);
  });

  it('is byte-identical to the SPA copy, so neither stack can regenerate alone', () => {
    // Same generator, same version, same input — so any difference is one side being stale.
    expect(generated).toEqual(readFileSync(FRONTEND_GENERATED, 'utf8'));
  });
});
