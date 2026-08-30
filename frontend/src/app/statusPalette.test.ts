import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Design language §6 sets a floor of 15 ΔE (Euclidean distance in OKLab, ×100) between any two
 * fills that can sit next to each other in a `ResultBar`. §6 derived its numbers for the dark
 * palette ONLY, and the light one was never put through the same check — the 2026-08-30 renders
 * audit found it failing on two of three adjacent pairs, red beside brown reading as one warm band.
 *
 * This measures both schemes from the stylesheet text, so a third scheme cannot repeat the
 * omission. It reads the CSS rather than the DOM because jsdom does not resolve
 * `@media (prefers-color-scheme)` and the defect lives in the declared value rather than in the
 * rendered one — the same reason `ConfigConventionTest` reads the YAML text.
 */

const HERE = dirname(fileURLToPath(import.meta.url));
const CSS = readFileSync(resolve(HERE, '../index.css'), 'utf8');

/** `ResultBar`'s fill order is fixed, so these three pairs are the only ones that ever touch. */
const ADJACENT: [string, string][] = [
  ['passed', 'failed'],
  ['failed', 'blocked'],
  ['blocked', 'skipped'],
];

const FLOOR = 15;

function srgbToLinear(channel: number): number {
  const c = channel / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function oklab(hex: string): [number, number, number] {
  const r = srgbToLinear(parseInt(hex.slice(1, 3), 16));
  const g = srgbToLinear(parseInt(hex.slice(3, 5), 16));
  const b = srgbToLinear(parseInt(hex.slice(5, 7), 16));
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

function deltaE(a: string, b: string): number {
  const [l1, a1, b1] = oklab(a);
  const [l2, a2, b2] = oklab(b);
  return 100 * Math.hypot(l1 - l2, a1 - a2, b1 - b2);
}

/**
 * The two blocks that declare the status set. Splitting on the dark block's opening selector is
 * enough: `index.css` declares the light palette on bare `:root` and redefines it once under
 * `[data-color-scheme='dark']` — `app/colorScheme.tsx` resolves the system preference into that
 * attribute, so there is no `prefers-color-scheme` block to account for — and
 * `tokenSystem.test.ts` already fails if a third declaration site appears.
 */
function schemes(): Record<'light' | 'dark', Record<string, string>> {
  // The RULE, not the first mention: the light block's comment names the dark selector in prose,
  // and splitting on that would put the dark palette in the light half and pass on the wrong data.
  const darkAt = CSS.indexOf("[data-color-scheme='dark'] {");
  expect(darkAt, 'the dark palette block moved — update this test, do not delete it').toBeGreaterThan(
    0,
  );
  const read = (block: string) =>
    Object.fromEntries(
      [
        ...block.matchAll(
          /--color-(passed|failed|blocked|skipped|untested):\s*(#[0-9a-fA-F]{6})/g,
        ),
      ].map((match) => [match[1], match[2].toLowerCase()]),
    );
  return { light: read(CSS.slice(0, darkAt)), dark: read(CSS.slice(darkAt)) };
}

describe('the status palette, per scheme', () => {
  const palettes = schemes();

  for (const scheme of ['light', 'dark'] as const) {
    it(`declares all five status fills in ${scheme}`, () => {
      expect(Object.keys(palettes[scheme]).sort()).toEqual([
        'blocked',
        'failed',
        'passed',
        'skipped',
        'untested',
      ]);
    });

    for (const [left, right] of ADJACENT) {
      it(`${scheme}: ${left} and ${right} are at least ${FLOOR} ΔE apart`, () => {
        const distance = deltaE(palettes[scheme][left], palettes[scheme][right]);
        expect(
          distance,
          `${scheme} ${left}(${palettes[scheme][left]}) | ${right}(${palettes[scheme][right]}) = ${distance.toFixed(1)}`,
        ).toBeGreaterThanOrEqual(FLOOR);
      });
    }
  }
});
