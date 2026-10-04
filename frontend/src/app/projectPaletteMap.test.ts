import { describe, expect, it } from 'vitest';
import { projectColorLabels } from '../lib/labels';
import { projectColorFill } from '../lib/projectColors';

const slots = Object.keys(projectColorFill);

describe('the project palette tables', () => {
  it('maps the same eight slots in the fill and label tables', () => {
    expect(Object.keys(projectColorLabels)).toEqual(slots);
    expect(slots).toHaveLength(8);
  });

  it.each(slots)('%s resolves to the custom property of its own name', (slot) => {
    expect(projectColorFill[slot as keyof typeof projectColorFill]).toBe(`var(--color-${slot})`);
  });
});
