/**
 * TCMS spec markdown, both directions.
 *
 * The **round trip is the property that matters** and it is asserted over a corpus rather than a
 * handful of literals: parse, assign the ids a server would, render, parse again, and compare
 * every block's type, text, level, rows and id. A converter can be wrong in exactly one way that
 * costs a link — a block whose id does not survive the trip — and no example-by-example test finds
 * that reliably, because the block it drops is the one nobody wrote an example for.
 *
 * The corpus includes both worked examples from
 * `docs/specs/assets/spec-formatter/`, which are the two client specifications the format was
 * fixed against: a Confluence export with nine reconstructed tables, and a plain-language
 * narrative with fifty-two labelled statements and a nested control list.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import type { components } from '../schema.js';
import { REPO } from '../test/corpus.js';

import { MarkdownError, anchorsFor, blocksFromMarkdown, markdownFromBlocks } from './markdown.js';

type Schemas = components['schemas'];

const SAMPLES = ['margin-interest', 'liquidator'] as const;

function sample(name: string): string {
  return readFileSync(join(REPO, 'docs/specs/assets/spec-formatter', `${name}.page.md`), 'utf8');
}

/** What the server answers with: the blocks as sent, each carrying the id it was given. */
function stored(blocks: readonly Schemas['DocBlockInput'][]): Schemas['DocBlock'][] {
  return blocks.map((block, index) => ({
    id: block.id ?? `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    position: index,
    blockType: block.blockType,
    level: block.level ?? null,
    text: block.text,
    rows: block.rows ?? null,
    attachmentId: block.attachmentId ?? null,
    links: [],
    coverage: null,
  }));
}

function shapeOf(blocks: readonly (Schemas['DocBlock'] | Schemas['DocBlockInput'])[]): unknown[] {
  return blocks.map((block) => ({
    blockType: block.blockType,
    text: block.text,
    level: block.level ?? null,
    rows: block.rows ?? null,
    id: block.id ?? undefined,
  }));
}

describe('the grammar', () => {
  it('maps each construct to exactly one block', () => {
    const { blocks } = blocksFromMarkdown(
      '# Title\n\n'
      + 'The system charges interest daily. {#accrue-daily}\n\n'
      + '- First rule {#one}\n'
      + '  - Its safeguard {#one-guard}\n'
      + '- Second rule {#two}\n\n'
      + '```json\n{"a": 1}\n```\n\n'
      + 'Table: Rates per currency. {#rates}\n'
      + '| Currency | Rate |\n'
      + '|---|---|\n'
      + '| USD | 7.1% |\n',
      { mode: 'create' },
    );

    expect(blocks.map((block) => block.blockType)).toEqual([
      'heading', 'paragraph', 'list', 'list', 'list', 'code', 'table',
    ]);
    expect(blocks[0]).toEqual({ blockType: 'heading', level: 1, text: 'Title' });
    expect(blocks[3]).toEqual({ blockType: 'list', level: 2, text: 'Its safeguard' });
    expect(blocks[5]).toEqual({ blockType: 'code', text: '{"a": 1}' });
    expect(blocks[6]).toEqual({
      blockType: 'table',
      text: 'Rates per currency.',
      rows: [['Currency', 'Rate'], ['USD', '7.1%']],
    });
  });

  it('numbers the labels by block index, which is what anchorsFor resolves', () => {
    const { blocks, labels } = blocksFromMarkdown(
      '# Title\n\nA statement. {#first}\n\nAnother. {#second}\n',
      { mode: 'create' },
    );

    expect([...labels]).toEqual([['first', 1], ['second', 2]]);
    expect(anchorsFor(labels, { blocks: stored(blocks) })).toEqual({
      first: '00000000-0000-4000-8000-000000000001',
      second: '00000000-0000-4000-8000-000000000002',
    });
  });

  it('reads a UUID anchor as the block id, and a label as a new block', () => {
    const { blocks, labels } = blocksFromMarkdown(
      'Kept. {#0f6a1c2e-7b41-4d90-9d1a-4c1f2b6e33aa}\n\nAdded. {#new-one}\n',
      { mode: 'edit' },
    );

    expect(blocks[0]!.id).toBe('0f6a1c2e-7b41-4d90-9d1a-4c1f2b6e33aa');
    expect(blocks[1]!.id).toBeUndefined();
    expect(labels.get('new-one')).toBe(1);
  });

  it('refuses a block id on a create, because the page has no blocks yet', () => {
    expect(() => blocksFromMarkdown(
      'A statement. {#0f6a1c2e-7b41-4d90-9d1a-4c1f2b6e33aa}\n',
      { mode: 'create' },
    )).toThrow(/Line 1.*create has no blocks/s);
  });

  it('refuses a duplicate label, because a label names one block', () => {
    expect(() => blocksFromMarkdown('One. {#same}\n\nTwo. {#same}\n', { mode: 'create' }))
      .toThrow(/Line 3.*'same' is used twice/s);
  });

  it('refuses an anchor that is neither a UUID nor a label', () => {
    expect(() => blocksFromMarkdown('One. {#Not A Label}\n', { mode: 'create' }))
      .toThrow(/neither a block id nor a label/);
  });

  it('refuses what it has no block type for, naming the line', () => {
    const refusals: [string, RegExp][] = [
      ['A statement.\n\n> quoted\n', /Line 3.*blockquote/s],
      ['A statement.\n\n<div>markup</div>\n', /Line 3.*HTML/s],
      ['A statement.\n\n---\n\nAnother.\n', /Line 3.*thematic break/s],
      ['Table: No table under it. {#x}\n\nA paragraph.\n', /followed immediately by the table/],
      ['| a | b |\n|---|---|\n', /needs a `Table: <caption>` line/],
      ['A statement.\n\n  indented\n', /Line 3.*Indented text/s],
      ['- item {#a}\n   - misaligned {#b}\n', /Line 2.*two spaces at a time/s],
      ['```\nunclosed\n', /Line 1.*never closed/s],
    ];

    for (const [text, message] of refusals) {
      expect(() => blocksFromMarkdown(text, { mode: 'create' }), text).toThrow(message);
    }
  });

  it('checks the table bounds the contract states, before the request rather than after it', () => {
    const wide = `Table: Too wide.\n|${' a |'.repeat(13)}\n|${'---|'.repeat(13)}\n`;
    expect(() => blocksFromMarkdown(wide, { mode: 'create' })).toThrow(/13 columns/);

    const ragged = 'Table: Ragged.\n| a | b |\n|---|---|\n| one |\n';
    expect(() => blocksFromMarkdown(ragged, { mode: 'create' })).toThrow(/1 cells and the header has 2/);

    const long = `Table: Long cell.\n| a | b |\n|---|---|\n| ${'x'.repeat(501)} | b |\n`;
    expect(() => blocksFromMarkdown(long, { mode: 'create' })).toThrow(/501 characters/);

    const noSeparator = 'Table: No separator.\n| a | b |\n| c | d |\n';
    expect(() => blocksFromMarkdown(noSeparator, { mode: 'create' })).toThrow(/separator row/);
  });

  it('carries an image as a paragraph on a create and as an image on an edit', () => {
    const text = '![The band ladder](attachment:0f6a1c2e-7b41-4d90-9d1a-4c1f2b6e33aa)\n';

    const created = blocksFromMarkdown(text, { mode: 'create' });
    expect(created.blocks[0]).toEqual({ blockType: 'paragraph', text: 'Figure: The band ladder' });
    expect(created.warnings[0]).toMatch(/no attachments until it exists/);

    const edited = blocksFromMarkdown(text, { mode: 'edit' });
    expect(edited.blocks[0]).toEqual({
      blockType: 'image',
      text: 'The band ladder',
      attachmentId: '0f6a1c2e-7b41-4d90-9d1a-4c1f2b6e33aa',
    });
    expect(edited.warnings).toEqual([]);
  });

  it('passes inline emphasis through as text, with a warning, because the page renders it as typed', () => {
    const { blocks, warnings } = blocksFromMarkdown(
      'The system **must** refuse it. {#refuse}\n',
      { mode: 'create' },
    );

    expect(blocks[0]!.text).toBe('The system **must** refuse it.');
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toMatch(/rendered as typed/);
  });

  it('warns when a heading is labelled, because a case verifying a heading verifies nothing', () => {
    const { warnings } = blocksFromMarkdown('# The guards {#guards}\n', { mode: 'create' });
    expect(warnings[0]).toMatch(/verifies nothing/);
  });
});

describe('the edit rule that keeps a link from being voided silently', () => {
  const current: Schemas['DocBlock'][] = [
    {
      id: '0f6a1c2e-7b41-4d90-9d1a-4c1f2b6e33aa',
      position: 0,
      blockType: 'paragraph',
      level: null,
      text: 'The system sells to the 105% target, never to exactly 100%.',
      rows: null,
      attachmentId: null,
      coverage: null,
      links: [{
        id: '11111111-1111-4111-8111-111111111111',
        sourceType: 'TEST_CASE',
        sourceId: '22222222-2222-4222-8222-222222222222',
        sourceKey: 'TC-42',
        sourceTitle: 'Sells to the target',
        relationship: 'VERIFIES',
      }],
    },
  ];

  it('refuses an edit that drops a claimed block, naming the block and its claimants', () => {
    let thrown: unknown;
    try {
      blocksFromMarkdown('Something else entirely.\n', { mode: 'edit', current });
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(MarkdownError);
    expect((thrown as Error).message).toMatch(/sells to the 105% target/);
    expect((thrown as Error).message).toMatch(/TC-42/);
    expect((thrown as Error).message).toMatch(/dropLinked/);
  });

  it('allows it with dropLinked, and reports the loss instead', () => {
    const { warnings } = blocksFromMarkdown(
      'Something else entirely.\n',
      { mode: 'edit', current, dropLinked: true },
    );
    expect(warnings[0]).toMatch(/1 linked block\(s\) were dropped/);
  });

  it('is silent when the block is kept, however much its text changed', () => {
    const { warnings } = blocksFromMarkdown(
      'The system sells to the 105% target. {#0f6a1c2e-7b41-4d90-9d1a-4c1f2b6e33aa}\n',
      { mode: 'edit', current },
    );
    expect(warnings).toEqual([]);
  });
});

describe('the round trip', () => {
  const corpus: [string, string][] = [
    ...SAMPLES.map((name): [string, string] => [`${name}.page.md`, sample(name)]),
    ['every construct', '# Title\n\nA statement.\n\n- one\n  - nested\n\n```\ncode\n```\n\n'
      + 'Table: A caption.\n| a | b |\n|---|---|\n| c | d |\n'],
    ['a cell containing a pipe', 'Table: Escapes.\n| a | b |\n|---|---|\n| x \\| y | z |\n'],
    ['a paragraph spanning lines', 'One line\nand its continuation.\n'],
  ];

  for (const [name, text] of corpus) {
    it(`reproduces every block of ${name}`, () => {
      const first = blocksFromMarkdown(text, { mode: 'create' });
      const page = stored(first.blocks);

      const second = blocksFromMarkdown(markdownFromBlocks(page), { mode: 'edit', current: page });

      expect(shapeOf(second.blocks)).toEqual(shapeOf(page));
    });
  }

  it('renders an image round-trip too, which only an edit can carry', () => {
    const page = stored(blocksFromMarkdown(
      '![The ladder](attachment:0f6a1c2e-7b41-4d90-9d1a-4c1f2b6e33aa)\n',
      { mode: 'edit' },
    ).blocks);

    const back = blocksFromMarkdown(markdownFromBlocks(page), { mode: 'edit', current: page });
    expect(back.blocks[0]!.attachmentId).toBe('0f6a1c2e-7b41-4d90-9d1a-4c1f2b6e33aa');
  });

  it('parses both worked examples with no warnings a reviewer has to act on', () => {
    for (const name of SAMPLES) {
      const { blocks, labels, warnings } = blocksFromMarkdown(sample(name), { mode: 'create' });
      expect(blocks.length, name).toBeGreaterThan(20);
      expect(labels.size, name).toBeGreaterThan(20);
      expect(warnings, name).toEqual([]);
    }
  });

  it('refuses to write a page this format cannot express, rather than emitting it broken', () => {
    const split: Schemas['DocBlock'][] = [{
      id: '0f6a1c2e-7b41-4d90-9d1a-4c1f2b6e33aa',
      position: 0,
      blockType: 'paragraph',
      level: null,
      text: 'One statement.\n\nAnd a second.',
      rows: null,
      attachmentId: null,
      links: [],
      coverage: null,
    }];

    expect(() => markdownFromBlocks(split)).toThrow(/read back as two/);
  });
});

describe('anchorsFor', () => {
  it('refuses to guess when the page came back shorter than the labels expect', () => {
    expect(() => anchorsFor(new Map([['late', 3]]), { blocks: [{ id: 'a' }] }))
      .toThrow(/cannot be resolved/);
  });
});
