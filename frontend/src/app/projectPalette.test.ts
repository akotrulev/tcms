import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const CSS = readFileSync(resolve(HERE, '../index.css'), 'utf8');
const WHITE = '#ffffff';
const FLOOR = 4.5;

function linear(channel: number): number {
  const c = channel / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function luminance(hex: string): number {
  return (
    0.2126 * linear(parseInt(hex.slice(1, 3), 16)) +
    0.7152 * linear(parseInt(hex.slice(3, 5), 16)) +
    0.0722 * linear(parseInt(hex.slice(5, 7), 16))
  );
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
}

function palette(from: number, to: number): Record<string, string> {
  return Object.fromEntries(
    [...CSS.slice(from, to).matchAll(/--color-(project-\d+):\s*(#[0-9a-fA-F]{6})/g)].map((m) => [
      m[1],
      m[2]!.toLowerCase(),
    ]),
  );
}

const darkAt = CSS.indexOf("[data-color-scheme='dark'] {");
const schemes = {
  light: palette(0, darkAt),
  dark: palette(darkAt, CSS.length),
};
const slots = Array.from({ length: 8 }, (_, index) => `project-${index + 1}`);

describe('the project palette, per scheme', () => {
  it('has the dark block to split on', () => {
    expect(darkAt).toBeGreaterThan(0);
  });

  for (const scheme of ['light', 'dark'] as const) {
    it.each(slots)(`${scheme}: %s is declared and carries white at ${FLOOR}:1`, (slot) => {
      const fill = schemes[scheme][slot];
      expect(fill, `--color-${slot} missing from the ${scheme} palette`).toBeDefined();
      const ratio = contrast(WHITE, fill!);
      expect(ratio, `${scheme} ${slot}(${fill}) = ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(
        FLOOR,
      );
    });
  }
});
