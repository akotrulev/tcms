import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * The token system, checked against itself — `index.css` is now the only place a colour is decided.
 *
 * ## ~~`themeTokenParity.test.ts`~~ → renamed here in T16, and it stopped checking its main subject
 *
 * ~~`theme.ts` re-declares the status and lifecycle hexes that `index.css` already holds, because
 * MUI needs literal values for contrast and alpha computation and cannot read a custom property.
 * Its own comment calls that "the one accepted duplication" and says any change must be made in
 * both — and nothing checked that it was. A duplication policed by a comment is a fork with a
 * delay: the two copies agree until the day someone edits one, and nothing goes red.~~
 *
 * **That file is gone.** T16 deleted it with the rest of MUI, so the duplication this file was
 * built to police no longer exists — there is one palette, in `index.css`, and Tailwind's
 * `@theme inline` maps utilities onto it rather than copying values out of it. The parity suite
 * that compared two files therefore had no second file to compare against and was deleted with
 * its subject.
 *
 * The paragraph above is kept rather than removed because the *reason* it existed is still true
 * and still the standard this file is held to: a duplication policed by a comment is a fork with
 * a delay. What changed is that the duplication went, not that the caution was wrong. If a second
 * source of colour is ever introduced, this is the file that has to grow a parity check again.
 *
 * ## What survives, and why each of the four still has a subject
 *
 * 1. **Rule 5's identity check** (T1 Step 6) — `Given`/`When`/`Then` are the same hues as
 *    `Passed`/`Blocked`/`Retest`. One palette, two vocabularies; nothing else asserts it.
 * 2. **The kit's six new token names are declared in both palettes.** A missing custom property
 *    renders as `transparent` — a surface that vanishes rather than an error anything reports.
 * 3. **The tier split** — progress and viz stay disjoint from outcome *by value*. ~~The outcome
 *    values used to be read out of `theme.ts`'s `statusColors`~~; they are read out of the CSS
 *    now, which is the only place they exist.
 * 4. **The breakpoint contract is single** — `@theme inline` against `breakpoints.ts`.
 *
 * And one addition the deletion made possible to check: **every name `@theme inline` maps to
 * resolves to a declared custom property.** While the theme module existed a token could be
 * half-real —
 * present in one file and not the other — and the parity suite was what caught it. Now the failure
 * mode is a bridge line pointing at a property nobody declared, which compiles, lints, and paints
 * nothing.
 *
 * **Both sides are read as TEXT**, for the reason §108 paid for: a check that asks the browser for
 * `--color-passed` is asking the file under test what it says, and would pass against a palette
 * that had already drifted. The bytes are the artifact.
 */

const HERE = dirname(fileURLToPath(import.meta.url));
const css = readFileSync(resolve(HERE, '../index.css'), 'utf8');

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

const light = cssTokens(':root');
const dark = cssTokens("[data-color-scheme='dark']");

/** The outcome tier, by name — the five `StatusKind` members `lib/statusColors.ts` maps. */
const OUTCOME_TOKENS = [
  '--color-passed',
  '--color-failed',
  '--color-blocked',
  '--color-skipped',
  '--color-untested',
];

describe('index.css is the whole palette', () => {
  it('declares both palettes at all', () => {
    expect(light.size, 'no hex tokens parsed out of :root').toBeGreaterThan(20);
    expect(dark.size, 'no hex tokens parsed out of the dark palette').toBeGreaterThan(20);
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
      for (const [name, palette] of [
        ['light', light],
        ['dark', dark],
      ] as const) {
        const value = palette.get(token);
        expect(value, `${token} missing from the ${name} palette`).toBeDefined();
        expect(OUTCOME_TOKENS.map((outcome) => palette.get(outcome))).not.toContain(value);
      }
    },
  );
});

/**
 * Every name the Tailwind bridge maps resolves to a property that is actually declared.
 *
 * This is the check the parity suite's deletion made necessary. `@theme inline` is what makes
 * `bg-panel` mean `var(--color-surface)`; `inline` is load-bearing precisely because it emits the
 * `var()` rather than copying the value, so a bridge line pointing at a property nobody declared
 * emits a utility that resolves to nothing. Tailwind does not complain, `tsc` cannot see it, and
 * the screen paints a transparent box — the same silent failure the six-token check above covers
 * from the other end.
 */
describe('the Tailwind bridge points only at declared properties', () => {
  const start = css.indexOf('@theme inline');
  const block = css.slice(start, css.indexOf('\n}', start));

  /* `--color-x: var(--color-y);` — the left name is Tailwind's, the right one is the app's. */
  const bridged = [...block.matchAll(/--[a-z0-9-]+:\s*var\((--[a-z0-9-]+)\)\s*;/g)].map(
    (m) => m[1]!,
  );

  it('found the bridge at all', () => {
    expect(bridged.length).toBeGreaterThan(5);
  });

  it.each(bridged)('%s is declared in :root', (property) => {
    /*
      Declared, not necessarily a hex: `--font-sans` and the surfaces are, but the check is about
      the NAME existing, so it reads the raw block rather than `cssTokens`, which only keeps hexes.
    */
    expect(cssBlock(':root')).toContain(`${property}:`);
  });
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

/**
 * Lines and washes exist in both palettes, and nothing draws one by hand.
 *
 * The T16 port spelled these as `border-white/8` and `bg-white/5` in 86 files. A white alpha is
 * correct over the dark palette and invisible over the light one, so the light theme shipped with
 * borders it could not draw — and no gate saw it, because every literal was a valid Tailwind
 * class and every token in `@theme inline` resolved. The two halves below are what would have
 * caught it: the names have to exist in both palettes, and no component may go back to a literal.
 */
describe('lines and washes are tokens, not literals', () => {
  const surfaceModifiers = [
    '--color-rule',
    '--color-rule-strong',
    '--color-overlay-1',
    '--color-overlay-2',
    '--color-overlay-3',
    '--color-surface-rail',
  ];

  it.each(surfaceModifiers)('%s is declared in both palettes', (token) => {
    expect(cssBlock(':root'), `${token} missing from :root`).toContain(`${token}:`);
    expect(
      cssBlock("[data-color-scheme='dark']"),
      `${token} missing from the dark palette`,
    ).toContain(`${token}:`);
  });

  /** `git grep` exits 1 on no matches, which is the passing case — the status is the answer. */
  function gitGrep(pattern: string): string[] {
    const result = spawnSync('git', ['grep', '-nE', pattern, '--', 'src'], {
      cwd: resolve(HERE, '..', '..'),
      encoding: 'utf8',
    });
    expect(result.status, `git grep failed: ${result.stderr}`).toBeLessThan(2);
    return (result.stdout ?? '').split('\n').filter((line) => line !== '');
  }

  /**
   * White alphas only. A black alpha is still legitimate for a modal scrim and a shadow, because
   * those dim whatever is behind them and are the same gesture in either palette; a white alpha
   * has no such reading — it is always a light-on-dark assumption.
   */
  it('no component draws a border, fill or divider with a raw white alpha', () => {
    const offenders = gitGrep('(border|bg|divide|ring|outline)-white/').filter(
      (line) => !/\.(test\.tsx?|css):/.test(line),
    );

    expect(offenders, 'use border-line / border-line-strong / bg-wash-1..3 / bg-line').toEqual([]);
  });
});

/**
 * `lib/statusColors.ts` names only properties `index.css` declares.
 *
 * `Record<Union, string>` over there already makes a *missing enum member* a compile error, which
 * is the half the compiler can see. It cannot see the other half: the values are strings, so
 * `var(--color-pased)` typechecks, lints, and paints **transparent** — a status chip that vanishes
 * rather than an error anything reports. Same failure this file was written for at the bridge,
 * one layer out.
 *
 * The table is read as TEXT rather than imported, for the reason the breakpoint check gives: a
 * test that imports it is comparing the module to itself, and a `node:fs` read keeps this file's
 * dependency graph at zero.
 */
describe('the status colour tables resolve', () => {
  const table = readFileSync(resolve(HERE, '../lib/statusColors.ts'), 'utf8');
  const referenced = [...new Set([...table.matchAll(/var\((--[a-z0-9-]+)\)/g)].map((m) => m[1]!))];

  it('parsed the tables at all', () => {
    expect(referenced.length).toBeGreaterThan(12);
  });

  it.each(referenced)('%s is declared in both palettes', (property) => {
    expect(cssBlock(':root'), `${property} missing from :root`).toContain(`${property}:`);
    expect(
      cssBlock("[data-color-scheme='dark']"),
      `${property} missing from the dark palette`,
    ).toContain(`${property}:`);
  });
});
