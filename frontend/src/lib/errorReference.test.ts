import { describe, expect, it } from 'vitest';

import { newErrorReference } from './errorReference';

describe('an error reference is something a person can read out', () => {
  it('is six characters from the unambiguous alphabet', () => {
    for (let i = 0; i < 200; i += 1) {
      expect(newErrorReference()).toMatch(/^[23456789ABCDEFGHJKMNPQRSTVWXYZ]{6}$/);
    }
  });

  it('contains none of the glyph pairs that get misread aloud', () => {
    // The whole reason for the alphabet: this string is spoken into a call and typed back.
    const many = Array.from({ length: 200 }, () => newErrorReference()).join('');
    for (const glyph of ['0', 'O', '1', 'I', 'L', 'U']) {
      expect(many).not.toContain(glyph);
    }
  });

  it('is different each time, so two failures are two references', () => {
    const seen = new Set(Array.from({ length: 200 }, () => newErrorReference()));
    expect(seen.size).toBeGreaterThan(190);
  });
});
