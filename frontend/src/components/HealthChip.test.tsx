import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { HealthChip } from './StatusChip';
import { healthLabels } from '../lib/labels';
import type { HealthSignalSeverity } from '../api/types';

/**
 * What the chip decides, and what it no longer decides.
 *
 * ~~The automation-drift pair, and the one thing about it that a refactor destroys.~~ → **§179
 * moved that assertion to the server**, where the decision is. This file used to hold the only
 * check that `STALE_AUTOMATION` renders as severe and `UNDECLARED_AUTOMATION` does not, because the
 * chip's colour came from a hand-written `SEVERE_SIGNALS` list in `StatusChip.tsx` and severity was
 * not on the wire. That list had drifted from `HealthSignal.Severity` about two of the ten members
 * by the time §178 read it, so a case rendered red on a dashboard tile and amber on its own chip.
 *
 * The severity arrives with the signal now, so the pair's asymmetry is
 * `RaisedSignalTest.theAutomationPairStaysAsymmetric` in the backend and the tests here are about
 * the only thing left on this side: **that the chip renders what it was handed.** Deleting the list
 * without moving its test would have been §153's finding — a decision moved to the side that owns
 * it and its only coverage left behind.
 */
describe('HealthChip — renders the severity it is given, and decides nothing', () => {
  const colorOf = (severity: HealthSignalSeverity) => {
    render(<HealthChip signal="STALE_AUTOMATION" severity={severity} testId="chip" />);
    const chip = screen.getByTestId('chip');
    return getComputedStyle(chip).color;
  };

  it('renders an ERROR signal as severe', () => {
    expect(colorOf('ERROR')).toBe('var(--color-failed)');
  });

  it('renders a WARNING signal as a warning, NOT as severe', () => {
    // The pair's asymmetry read from this side: the same signal name with the other severity has
    // to come out a different colour, or the chip is ignoring the server and this file is back to
    // holding an opinion of its own.
    expect(colorOf('WARNING')).toBe('var(--color-blocked)');
  });

  it('renders an INFO signal in the same amber as a WARNING, deliberately', () => {
    // Not an oversight: since §178 gave UNLINKED a writer, INFO is most rows of a young project.
    // Quietening it is a change to the grid's reading plan and is recorded rather than made here —
    // this test exists so that change is a decision somebody takes on purpose.
    expect(colorOf('INFO')).toBe('var(--color-blocked)');
  });

  it('takes the label from the signal and the colour from the severity', () => {
    // The failure this guards is a chip showing one signal's name in another's colour, which is
    // what an off-by-one over two separately-assembled lists produces. Both values come off one
    // `RaisedSignal`; nothing here may derive one from the other.
    render(<HealthChip signal="UNDECLARED_AUTOMATION" severity="WARNING" testId="chip" />);
    const chip = screen.getByTestId('chip');

    expect(chip).toHaveTextContent(healthLabels.UNDECLARED_AUTOMATION);
    expect(getComputedStyle(chip).color).toBe('var(--color-blocked)');
  });

  it('explains the spec-changed signal by the comparison the server actually makes', async () => {
    // §178 gave this signal its first derivation, and its tooltip had described a comparison
    // nobody makes: the block rather than the page, and only the links that claim to VERIFY.
    // Neither error is visible on screen — both read as perfectly sensible sentences — so the
    // regression is a "tidy" rewrite toward the wording the enum's NAME suggests. That is
    // §158's finding on CONDITION_DRIFT one signal later, which is why it is pinned this time.
    render(<HealthChip signal="OUTDATED_SPEC_CHANGED" severity="ERROR" testId="chip" />);
    const help = await screen.findByTestId('chip');

    expect(help).toHaveAttribute(
      'aria-label',
      expect.stringContaining('specification page this case links to'),
    );
  });

  it('explains the owner signal by the state a deployment can actually reach', async () => {
    // §181's finding, and the third instance of §158's — after OUTDATED_SPEC_CHANGED above, a
    // tooltip written before its signal could appear turned out to describe the wrong thing.
    //
    // The server's predicate has three terms: no membership row, membership REMOVED, account
    // DEACTIVATED. These words named ONLY the third ("This case's owner has been deactivated"),
    // which is the one term no running deployment can produce — `AppUser.deactivate` has four
    // callers and all four are tests. The reader who sees this chip is looking at a case whose
    // owner was offboarded, and was being told something that had not happened.
    //
    // Pinned as the absence of the narrow word rather than only the presence of the broad one:
    // "deactivated" is exactly what a later tidy-up reaches for, because it is what the signal's
    // own enum name suggests, and re-adding it would pass a stringContaining assertion.
    render(<HealthChip signal="ORPHANED" severity="INFO" testId="chip" />);
    const help = await screen.findByTestId('chip');

    expect(help).toHaveAttribute('aria-label', expect.stringContaining('no longer works here'));
    expect(help).toHaveAttribute('aria-label', expect.not.stringContaining('deactivated'));
  });

  it('labels the owner signal by the person, not by the mechanism', () => {
    // Same finding at the label. 'Owner deactivated' named one of the three states and the chip
    // fires on all three; the fix is not a longer sentence but a word that is true of every term
    // in the predicate — which is also what keeps it true when the account screen ships.
    expect(healthLabels.ORPHANED).toBe('Owner has left');
  });

  it('labels the pair as two different problems', () => {
    // Not two spellings of one. The labels are what a reader triages by, and a pair
    // worded as variations on "automation" leaves them unable to tell which way the
    // error runs. The words stay this side of the wire on purpose: `CaseService.facets`
    // argues that a second set of enum labels on the server is the one that goes stale.
    expect(healthLabels.STALE_AUTOMATION).not.toBe(healthLabels.UNDECLARED_AUTOMATION);
    expect(healthLabels.UNDECLARED_AUTOMATION).toBe('Automated, but not marked so');
  });
});
