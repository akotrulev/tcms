/**
 * The walker exists twice. This is the thing that stops the two copies from drifting.
 *
 * `contractBodies.ts` here and `frontend/src/api/contractBodies.ts` carry a region marked by two
 * sentinels, and between them the text must be **identical, character for character**. Neither stack
 * can import the other's — separate npm roots, a Vite-only `?raw` import on that side, and two CI
 * jobs each installing a tree the other never sees — so the choice was a copy or a third
 * implementation of "does this body match the schema". A copy nothing pairs is §196's two tsconfig
 * lists, which is the shape this repo keeps paying for; this is the pairing.
 *
 * Same instrument `schemaFreshness.test.ts` one directory up points at the two `schema.d.ts` copies,
 * and for the same reason: each file is self-consistent, so nothing either one is checked against can
 * see that the other is older.
 *
 * **A green here is not a claim that the walkers agree in behaviour** — it is the stronger and much
 * cheaper claim that they are the same text. Comparing behaviour would need a corpus, and a corpus is
 * a third thing to keep current.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const HERE_COPY = resolve(HERE, 'contractBodies.ts');
const SPA_COPY = resolve(HERE, '../../../../frontend/src/api/contractBodies.ts');

const START = '/* ==== SHARED REGION START';
const END = '/* ==== SHARED REGION END ==== */';

/** The marked region of a file, or a failure that names which sentinel is missing. */
function region(path: string): string[] {
  const text = readFileSync(path, 'utf8');
  const start = text.indexOf(START);
  const end = text.indexOf(END);
  if (start === -1) throw new Error(`${path} has no '${START}' sentinel`);
  if (end === -1) throw new Error(`${path} has no '${END}' sentinel`);
  if (end < start) throw new Error(`${path} has its sentinels the wrong way round`);
  return text.slice(start, end + END.length).split('\n');
}

describe('the shared comparison region', () => {
  const here = region(HERE_COPY);
  const spa = region(SPA_COPY);

  /*
    A vacuity guard first, and it is not decoration: the region is located by two string literals,
    and the cheapest way for this check to pass while knowing nothing is for both sentinels to end
    up adjacent — an empty slice equals an empty slice. The walker is ~330 lines and the floor is
    set well under it so ordinary editing does not trip it.
  */
  it('read a region with the walker in it', () => {
    expect(here.length).toBeGreaterThan(200);
    expect(here.join('\n')).toContain('function walk(');
    expect(here.join('\n')).toContain('export function violationsOf(');
  });

  it('is byte-identical to the SPA copy, line for line', () => {
    // Line-wise rather than one string comparison, so a failure prints the line that differs
    // instead of two 13 kB blobs a reader has to diff by eye.
    const differing = here
      .map((line, index) => ({ index, here: line, spa: spa[index] }))
      .filter((pair) => pair.here !== pair.spa);

    expect(
      differing.slice(0, 5),
      'The two copies of the walker have diverged. Edit BOTH — the region is shared on purpose; '
        + 'see the sentinel comment at its head for why it cannot be an import.',
    ).toEqual([]);
    expect(here.length, 'one copy has more lines in the shared region than the other').toBe(spa.length);
  });
});
