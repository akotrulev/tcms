import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

/**
 * **There is no velocity in this product, and this is the check rather than the promise** — scope
 * D6, and M9's fourth definition-of-done.
 *
 * D6 removed the sprint. There is no `sprint` table, no sprint-planning screen and no cadence a
 * velocity would be measured over, so a chart implying otherwise would be a lie about how the team
 * plans. The specific danger is that such a chart would look entirely plausible: the parts to
 * assemble one out of — cycle time per type, a weekly flow — all now exist on `/analytics`.
 *
 * ## Why it is here as well as in the backend
 *
 * "No velocity chart exists anywhere" is a property of the whole tree, and no screen can assert it
 * about itself. It is split across two files because each corpus has to be watched by the job that
 * can watch it cheaply (020 D-h): `NoVelocityAnywhereTest` covers `backend/src/main` and
 * `openapi.yaml`, both already declared inputs of the backend `:test` task, and `build.gradle.kts`'
 * own paragraph argues against declaring the SPA's source tree there — *"another stack's whole
 * source tree … would re-run every backend test on every unrelated component edit"*. This file
 * covers the two trees that argument leaves out, from the job that already owns them.
 *
 * ## Prose is not a violation — and in this tree, prose includes STRINGS
 *
 * The word has to be sayable, or the text explaining the absence becomes the thing that fails — the
 * trap this repository has met three times, once inside a check written to end it. Comments are the
 * obvious half. The half this rule found on its first run is that **the product states its own
 * absence in user-facing copy**, and there are three places:
 *
 *   - `features/releases/ReleaseNewPage.tsx` renders a *"what this screen deliberately is not"*
 *     list whose second item is literally `'Velocity and sprint burndown'`;
 *   - `packages/cli/src/reads.ts` prints *"no velocity — there are no sprints to measure that
 *     over"* under the cycle-time table;
 *   - `packages/mcp/src/reads.ts` tells a model, in the tool description, to say the product has no
 *     sprint rather than deriving a number.
 *
 * All three are the feature working. A rule that failed them would fail this product for saying
 * what it does not do, which is the same defect one layer out from the comment case. So string
 * literals are stripped too, and what is left is **identifiers** — a component, a hook, a query
 * key, a field, a route segment. A velocity chart needs one of those; a sentence about its absence
 * needs none.
 *
 * **JSX text is prose too, and it is handled at the other end.** `CycleTimeFigure` prints the
 * mockup's own sentence — *"No velocity chart: there are no sprints to measure it over"* — under
 * the figure, which is the product telling a reader, on the screen where they would go looking,
 * that the thing is not there. Bare JSX text is neither a comment nor a quoted string, and adding a
 * `>…<` alternative to the pattern below **is not the fix**: in a `.ts` file full of generics, that
 * alternative matches from a `>` in `Record<A, B>` to the `<` of the next one, swallowing whatever
 * lies between — including a `/*` — and silently desynchronising the comment stripping for the rest
 * of the file. It did exactly that to `api/types.ts` on this rule's second run. So the sentence is
 * a named copy constant in that component instead, which is where copy belongs when a rule has to
 * tell copy from code.
 *
 * **Tests are not in the corpus.** A velocity chart cannot ship from a `.test.tsx`, and the two
 * files that would otherwise fail are this one — whose own matcher is a regex literal, so the rule
 * would report the rule — and `AnalyticsPage.test.tsx`, which asserts that the no-velocity sentence
 * is on screen. A check that fails the test asserting the feature has inverted itself.
 *
 * **The gap all this leaves, stated rather than discovered:** a `fetch('/analytics/velocity')` is a
 * string and would survive. It is covered from the other side — the contract half of
 * `NoVelocityAnywhereTest` fails on such a path in `openapi.yaml`, and an endpoint that is not in
 * the contract is one no generated client can reach.
 */

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND_SRC = join(HERE, '..');
const PACKAGES = join(HERE, '..', '..', '..', 'packages');

const VELOCITY = /velocity/i;

/**
 * Everything a person writes as prose rather than as a name: comments and quoted strings of all
 * three kinds. What survives is identifiers.
 */
const PROSE =
  /\/\*[\s\S]*?\*\/|\/\/[^\n]*|'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*"|`(?:[^`\\]|\\.)*`/g;

function sourcesUnder(root: string): string[] {
  const found: string[] = [];
  const walk = (at: string) => {
    for (const entry of readdirSync(at)) {
      // `dist` and `node_modules` are build output and other people's code; neither is ours to
      // assert about, and walking them would make this rule minutes long.
      if (entry === 'node_modules' || entry === 'dist' || entry === '.vite') continue;
      const path = join(at, entry);
      if (statSync(path).isDirectory()) walk(path);
      // Shipped source only — see the header for why a test is not in this rule's corpus.
      else if (/\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry)) found.push(path);
    }
  };
  walk(root);
  return found;
}

describe('no velocity anywhere in the SPA or the shared packages (scope D6)', () => {
  const corpus = [...sourcesUnder(FRONTEND_SRC), ...sourcesUnder(join(PACKAGES))];

  it('reads a corpus that is not empty', () => {
    // The vacuity half. An empty walk and a clean tree look identical from the rule below, which is
    // exactly how a source-text check passes silently the day a layout moves.
    expect(
      corpus.length,
      'no sources were found — the layout has moved and the rule below would pass over nothing',
    ).toBeGreaterThan(300);
  });

  it('names nothing after a velocity', () => {
    const hits = corpus.filter((path) =>
      VELOCITY.test(readFileSync(path, 'utf8').replaceAll(PROSE, ' ')),
    );

    expect(
      hits.map((path) => path.slice(HERE.length)),
      'Scope D6 removed the sprint velocity would be measured over, so there is nothing to compute '
        + 'it across and a figure implying otherwise reports a number nobody planned against. The '
        + 'parts to assemble one now exist, which is exactly why this is a check and not a comment. '
        + 'Comments and string literals are already stripped, so a hit here is an IDENTIFIER — a '
        + 'component, a hook, a query key, a field or a route segment named after a velocity. '
        + 'If a cadence is ever introduced, delete this rule and its backend half in the commit '
        + 'that introduces it rather than carving an exception into either.',
    ).toEqual([]);
  });
});
