/**
 * The shipped user guide against the registries — the same instrument `matrixCopies.test.ts` is,
 * pointed at the other prose copy of a list this repository computes.
 *
 * `frontend/src/features/help/sections.tsx` is what a user reads to find out what their agent can
 * do, and it is a hand-maintained enumeration of a registry twenty files away. It went stale the
 * moment §253 shipped: `tcms_shape` was in neither the tool list nor the count, so the tool built
 * specifically to stop an agent guessing a format was the one the guide did not mention. CLAUDE.md
 * names the family — *a catalogue-wide invariant needs a computed check, not a careful reading* —
 * and this is that check for the MCP half. `cli/src/guideCopies.test.ts` is the other.
 *
 * Read as TEXT rather than rendered, deliberately: the tool names live inside a `Definitions`
 * item's prose, and rendering the component to find them would test the renderer.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { TOOLS } from './tools.js';

const GUIDE = join(
  dirname(fileURLToPath(import.meta.url)),
  '../../../frontend/src/features/help/sections.tsx',
);

describe('the shipped user guide against the tool registry', () => {
  const text = readFileSync(GUIDE, 'utf8');
  const named = new Set(text.match(/\btcms_[a-z_]+\b/g) ?? []);
  const registered: string[] = TOOLS.map((tool) => tool.name);

  it('names every tool this server serves', () => {
    const missing = registered.filter((name) => !named.has(name));
    expect(missing, 'the guide does not mention these, so a user never learns they exist')
      .toEqual([]);
  });

  it('names no tool this server does not serve', () => {
    // The other direction, §162's rule: a one-way check cannot report a tool that was renamed or
    // removed, and a guide naming a tool a host will not list is worse than one naming none.
    const phantom = [...named].filter((name) => !registered.includes(name));
    expect(phantom, 'the guide names these and the registry does not').toEqual([]);
  });

  it('states the tool count the registry actually has', () => {
    // The count is prose beside the list and drifts independently of it — it said 23 while the
    // registry held 24, one slice after the list itself went stale.
    expect(text, `the guide should say "${registered.length} tools"`)
      .toContain(`${registered.length} tools`);
  });
});
