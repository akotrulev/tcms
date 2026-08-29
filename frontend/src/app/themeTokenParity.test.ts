import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * `theme.ts` re-declares the status and lifecycle hexes that `index.css` already holds, because MUI
 * needs literal values for contrast and alpha computation and cannot read a custom property. Its own
 * comment calls that "the one accepted duplication" and says any change must be made in both — and
 * nothing checked that it was. A duplication policed by a comment is a fork with a delay: the two
 * copies agree until the day someone edits one, and nothing goes red.
 *
 * **Both sides are read as TEXT**, for the reason §108 paid for: a check that imports `theme.ts` and
 * asks the browser for `--color-passed` is asking the files under test what they say, and would pass
 * against two palettes that had already diverged. The bytes are the artifact.
 */

const HERE = dirname(fileURLToPath(import.meta.url));
const css = readFileSync(resolve(HERE, '../index.css'), 'utf8');
const themeSource = readFileSync(resolve(HERE, 'theme.ts'), 'utf8');

function cssBlock(selector: string): string {
  const start = css.indexOf(`${selector} {`);
  expect(start, `${selector} block not found in index.css`).toBeGreaterThan(-1);
  return css.slice(start, css.indexOf('\n}', start));
}

function cssTokens(selector: string): Map<string, string> {
  const found = new Map<string, string>();
  for (const m of cssBlock(selector).matchAll(/(--[a-z0-9-]+):\s*(#[0-9a-f]{3,8})\s*;/gi)) {
    found.set(m[1]!, m[2]!.toLowerCase());
  }
  return found;
}

/** The `NAME: { light: '#…', dark: '#…' }` entries of one exported map in `theme.ts`. */
function themeMap(name: string): Map<string, { light: string; dark: string }> {
  const start = themeSource.indexOf(`export const ${name} = {`);
  expect(start, `${name} not exported from theme.ts`).toBeGreaterThan(-1);
  const body = themeSource.slice(start, themeSource.indexOf('} as const;', start));
  const found = new Map<string, { light: string; dark: string }>();
  for (const m of body.matchAll(
    /(\w+):\s*\{\s*light:\s*'(#[0-9a-f]{3,8})',\s*dark:\s*'(#[0-9a-f]{3,8})'\s*\}/gi,
  )) {
    found.set(m[1]!, { light: m[2]!.toLowerCase(), dark: m[3]!.toLowerCase() });
  }
  expect(found.size, `no entries parsed out of ${name}`).toBeGreaterThan(0);
  return found;
}

const light = cssTokens(':root');
const dark = cssTokens("[data-mui-color-scheme='dark']");

const mirrored = [
  ...[...themeMap('statusColors')].map(
    ([key, value]) => [`--color-${key.toLowerCase()}`, value] as const,
  ),
  ...[...themeMap('lifecycleColors')].map(
    ([key, value]) => [`--color-${key.toLowerCase().replace(/_/g, '-')}`, value] as const,
  ),
];

describe('theme.ts mirrors index.css', () => {
  it('declares every token it claims to mirror', () => {
    expect(mirrored.filter(([name]) => !light.has(name) || !dark.has(name)).map(([n]) => n)).toEqual(
      [],
    );
  });

  it.each(mirrored)('%s matches in both palettes', (name, value) => {
    expect(light.get(name)).toBe(value.light);
    expect(dark.get(name)).toBe(value.dark);
  });

  /**
   * The render kit adds four names the app had none for (design language §1): two surfaces, a
   * fourth ink weight, and the three Gherkin keyword hues. They are asserted here rather than
   * left to a screen to discover, because a missing token renders as `transparent` — a surface
   * that vanishes rather than an error anything reports.
   */
  it.each([
    '--color-surface-ground',
    '--color-surface-field',
    '--color-text-ghost',
    '--color-given',
    '--color-when',
    '--color-then',
  ])('%s is declared in both palettes', (token) => {
    expect(light.get(token), `${token} missing from :root`).toBeDefined();
    expect(dark.get(token), `${token} missing from the dark palette`).toBeDefined();
  });

  /**
   * Design language rule 5: one palette serves keywords and results. `Given` is the green that
   * `Passed` is, `When` the amber that `Blocked` is, `Then` the blue that `Retest` is.
   *
   * This is a computed check rather than a careful reading for the reason CLAUDE.md states about
   * catalogue-wide invariants: the two names sit in different parts of the file, both look right
   * in isolation, and the failure — the editor and the runner colouring the same step differently
   * — is only visible on a screen nobody opens with both in view.
   */
  it.each([
    ['--color-given', '--color-passed'],
    ['--color-when', '--color-blocked'],
    ['--color-then', '--color-retest'],
  ])('%s is the same hue as %s in both palettes', (keyword, result) => {
    expect(light.get(keyword)).toBe(light.get(result));
    expect(dark.get(keyword)).toBe(dark.get(result));
  });

  /**
   * The tier split is the point of the 002 audit's B1: one saturated palette had been answering
   * five unrelated questions, and a `COMPLETED` run reusing the outcome green is what put a green
   * chip on a run with a 0% pass rate. The progress and viz tiers must stay disjoint from the
   * outcome tier **by value**, not only by name — sharing a hex is how the overload comes back.
   */
  it.each(['--color-progress-done', '--color-viz-1', '--color-viz-2'])(
    '%s is not a recoloured outcome token',
    (token) => {
      const outcomes = [...themeMap('statusColors').values()];
      for (const palette of ['light', 'dark'] as const) {
        const value = (palette === 'light' ? light : dark).get(token);
        expect(value, `${token} missing from the ${palette} palette`).toBeDefined();
        expect(outcomes.map((c) => c[palette])).not.toContain(value);
      }
    },
  );
});

/**
 * Tailwind's breakpoint scale must be the app's, not Tailwind's own.
 *
 * The port added a second responsive system by omission: Tailwind ships `md: 768px` and this
 * app's contract has been `md: 900` since it adopted MUI's set wholesale. A `md:` in a class and
 * an `md` in `breakpoints.ts` meaning two different widths is invisible until a layout breaks in
 * the 132px between them — and `breakpoints.ts`'s own header forbids exactly this ("never
 * introduce a second system"). So the agreement is computed rather than remembered.
 */
describe('the breakpoint contract is single', () => {
  const themeStart = css.indexOf('@theme inline');
  const themeBlock = css.slice(themeStart, css.indexOf('\n}', themeStart));
  const declared = new Map(
    [...themeBlock.matchAll(/--breakpoint-([a-z]+):\s*(\d+)px\s*;/g)].map((m) => [m[1]!, Number(m[2])]),
  );

  /* Read, not restated: a copy of the contract in the test is a third system to keep in step. */
  const contract = new Map(
    [
      ...readFileSync(resolve(HERE, 'breakpoints.ts'), 'utf8').matchAll(/^\s*(sm|md|lg|xl):\s*(\d+),/gm),
    ].map((m) => [m[1]!, Number(m[2])]),
  );

  it('parsed the contract at all', () => {
    expect([...contract.keys()].sort()).toEqual(['lg', 'md', 'sm', 'xl']);
  });

  it.each(['sm', 'md', 'lg', 'xl'])('--breakpoint-%s matches breakpoints.ts', (name) => {
    expect(declared.get(name), `--breakpoint-${name} not declared in @theme inline`).toBe(
      contract.get(name),
    );
  });
});
