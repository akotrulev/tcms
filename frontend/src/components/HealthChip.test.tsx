import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { HealthChip } from './StatusChip';
import { healthLabels } from '../lib/labels';
import { severityVar } from '../lib/statusColors';
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

  it('does NOT render an INFO signal in the warning amber', () => {
    // §183, the decision §179 recorded and deferred through four slices. Amber was carrying two
    // meanings — "something is wrong now" and "worth knowing" — and by §182 three of the ten
    // signals were INFO, one of them (UNLINKED) firing on any case with no spec link. Most rows of
    // a young project were amber, which spends the triage colour on ordinary state.
    //
    // Asserted as a pair on purpose. The value is what INFO looks like now; the inequality is the
    // property the decision is actually about, and it survives a later change of token in a way
    // the equality alone does not. The second assertion reads the table rather than rendering a
    // second chip — two renders in one test put two `chip` testids in the document.
    expect(colorOf('INFO')).toBe('var(--color-info)');
    expect(severityVar.INFO).not.toBe(severityVar.WARNING);
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

  it('explains the completeness signal by the sign-off, not by "required fields"', async () => {
    // §182's finding, and the fourth instance of §158's. The server's predicate is three optional
    // columns (steps, an expected result anywhere, any classification) behind one gate —
    // lifecycle_status = 'APPROVED'. The old sentence, "Required fields are missing.", got both
    // halves wrong: none of the three is required, and the gate is the whole meaning, since a DRAFT
    // missing all three raises nothing at all.
    //
    // Pinned as the absence of "required" as well as the presence of the sign-off, for §181's
    // reason: it is the word the signal's own name suggests and exactly what a later tidy-up
    // reaches for, and re-adding it would satisfy a stringContaining assertion.
    render(<HealthChip signal="INCOMPLETE" severity="INFO" testId="chip" />);
    const help = await screen.findByTestId('chip');

    expect(help).toHaveAttribute('aria-label', expect.stringContaining('was approved without'));
    expect(help).toHaveAttribute('aria-label', expect.not.stringContaining('required'));
  });

  it('labels the completeness signal with the gate that gives it its meaning', () => {
    // Same finding at the label. "Missing required fields" describes a state no chip is raised for:
    // a case can miss all three of those things and be perfectly fine, right up until somebody
    // approves it.
    expect(healthLabels.INCOMPLETE).toBe('Approved but unfinished');
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
