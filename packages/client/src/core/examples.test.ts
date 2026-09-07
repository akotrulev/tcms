/**
 * The two worked examples in `docs/specs/assets/spec-formatter/` against the code that ships.
 *
 * The README beside them states label counts and claims every `verifies` in each bundle names a
 * label on its page, and the design spec points a reader at them as the format's demonstration.
 * `markdown.test.ts` already proves the pages parse and round-trip; this is the other half — the
 * bundles are the payloads `tcms spec import` would file, so a case in them that the catalogue
 * would refuse is a worked example teaching a shape the surface rejects.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { REPO } from '../test/corpus.js';

import { blocksFromMarkdown } from './markdown.js';
import { CASE_SHAPE, SHAPES, violationsOfShape } from './shapes.js';

const DIR = 'docs/specs/assets/spec-formatter';
const SAMPLES = ['margin-interest', 'liquidator'] as const;

interface Bundle {
  page: { title: string; markdown: string };
  cases: (Record<string, unknown> & { verifies: string[] })[];
  plan?: Record<string, unknown>;
}

function load(name: string): { labels: Set<string>; bundle: Bundle } {
  const page = readFileSync(join(REPO, DIR, `${name}.page.md`), 'utf8');
  const bundle = JSON.parse(readFileSync(join(REPO, DIR, `${name}.bundle.json`), 'utf8')) as Bundle;
  return { labels: new Set(blocksFromMarkdown(page, { mode: 'create' }).labels.keys()), bundle };
}

describe.each(SAMPLES)('the %s worked example', (name) => {
  const { labels, bundle } = load(name);

  it('files cases the case shape would accept', () => {
    for (const [index, entry] of bundle.cases.entries()) {
      expect(violationsOfShape(CASE_SHAPE, 'propose', entry), `cases[${index}]`).toEqual([]);
    }
  });

  it('verifies only labels its page carries', () => {
    for (const entry of bundle.cases) {
      for (const label of entry.verifies) {
        expect(labels, `'${label}' in "${String(entry['title'])}"`).toContain(label);
      }
    }
  });

  it('plans with fields the plan shape would accept', () => {
    if (!bundle.plan) return;
    expect(violationsOfShape(SHAPES['plan']!, 'create', bundle.plan)).toEqual([]);
  });
});
