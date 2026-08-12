import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { HealthChip } from './StatusChip';
import { healthLabels } from '../lib/labels';
import type { HealthSignal } from '../api/types';

/**
 * The automation-drift pair, and the one thing about it that a refactor destroys.
 *
 * The two signals are mirrors and read as a matched set, so the tidy change is to give
 * them a matched appearance. That erases the only distinction between them that a
 * reader acts on: stale automation is a result the team believes it is getting and is
 * not — a red test can be invisible — while undeclared automation is a result arriving
 * that no coverage number counts, which is wrong in the direction that costs nobody a
 * missed defect. The server draws the same line in `HealthSignal.Severity` and it is
 * not on the wire for a chip, so nothing but this test holds the two sides together.
 *
 * §118's `IssueTokenDialog` has one test for the same reason: a rule whose violation is
 * indistinguishable from a tidy-up needs a test that says so out loud.
 */
describe('HealthChip — the automation-drift pair', () => {
  const colorOf = (signal: HealthSignal) => {
    render(<HealthChip signal={signal} testId="chip" />);
    const chip = screen.getByTestId('chip');
    return getComputedStyle(chip).color;
  };

  it('renders stale automation as severe', () => {
    expect(colorOf('STALE_AUTOMATION')).toBe('var(--color-failed)');
  });

  it('renders undeclared automation as a warning, NOT as severe', () => {
    expect(colorOf('UNDECLARED_AUTOMATION')).toBe('var(--color-blocked)');
  });

  it('explains the spec-changed signal by the comparison the server actually makes', async () => {
    // §178 gave this signal its first derivation, and its tooltip had described a comparison
    // nobody makes: the block rather than the page, and only the links that claim to VERIFY.
    // Neither error is visible on screen — both read as perfectly sensible sentences — so the
    // regression is a "tidy" rewrite toward the wording the enum's NAME suggests. That is
    // §158's finding on CONDITION_DRIFT one signal later, which is why it is pinned this time.
    render(<HealthChip signal="OUTDATED_SPEC_CHANGED" testId="chip" />);
    const help = await screen.findByTestId('chip');

    expect(help).toHaveAttribute(
      'aria-label',
      expect.stringContaining('specification page this case links to'),
    );
  });

  it('labels the pair as two different problems', () => {
    // Not two spellings of one. The labels are what a reader triages by, and a pair
    // worded as variations on "automation" leaves them unable to tell which way the
    // error runs.
    expect(healthLabels.STALE_AUTOMATION).not.toBe(healthLabels.UNDECLARED_AUTOMATION);
    expect(healthLabels.UNDECLARED_AUTOMATION).toBe('Automated, but not marked so');
  });
});
