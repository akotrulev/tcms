/**
 * How a capacity figure reads, in one place, for the five surfaces that show one.
 *
 * New display logic goes in the shared layer, never the page — and this file is where *"what does
 * an em dash mean here"* is decided once, so the timesheet's capacity tile, My Work's meter, the
 * company timeline, the directory card and the person page cannot disagree about the same three
 * states.
 *
 * The three are genuinely different and look identical if the rule is written per screen:
 *
 *   - **a number** — there is a profile and there are hours to report;
 *   - **`—` with *no profile recorded*** — nobody has recorded this person's contracted hours. A
 *     real state, and the one 016 D-j refused to paper over with a default of 40. `0h` here is a
 *     different claim: it says somebody wrote down zero contracted hours;
 *   - **the region's own failed state** — the read did not answer, which `DataRegion` renders and
 *     which nothing in this file may imitate. A screen that printed *no profile recorded* over a
 *     failed read would tell the reader a fact about a person when the truth is a fact about the
 *     network.
 *
 * `tcmsFaults.staffing('no-profile')` is the only way to reach the second of those, which is why
 * it exists.
 */
import type { PersonCapacity, SelfCapacity } from '../../api/types';
import { formatHours } from '../../lib/format';

/** What the em dash is, spelled once so a test and a screen assert the same character. */
export const NO_FIGURE = '—';

/**
 * The sentence beside an em dash, and it is a sentence rather than a word for a reason.
 *
 * *"No profile"* reads as a missing screen; *"no profile recorded"* names the act somebody has not
 * performed, which is the one a reader can go and do something about.
 */
export const NO_PROFILE = 'no profile recorded';

/**
 * An hours figure, or the em dash when there is nothing to report.
 *
 * `hasProfile` is taken separately from `hours` and **both** are consulted, because the two nulls
 * they carry are not the same null: the server sends `profile: null` together with every hours
 * figure null (017 correction 4), so either one alone is enough to know there is no number — and a
 * caller that checked only the hours would print a figure for a person whose profile arrived null
 * with a stale number beside it.
 *
 * The formatting itself is {@link formatHours}, which is the product's one spelling of an hour
 * (`14h` / `0.5h`), rather than a second one written here.
 */
export function capacityLabel(hours: number | null | undefined, hasProfile: boolean): string {
  if (!hasProfile) return NO_FIGURE;
  return formatHours(hours);
}

/**
 * The detail line under a capacity figure — what the number means, or why there isn't one.
 *
 * The absent case is the whole reason this returns a string rather than the caller writing one:
 * every surface showing a capacity figure needs the same eight words, and a screen that invented
 * its own would be the second spelling that makes two screens disagree.
 */
export function capacityDetail(hasProfile: boolean, netOfAbsence = true): string {
  if (!hasProfile) return NO_PROFILE;
  return netOfAbsence ? 'net of absence' : 'contracted';
}

/**
 * Utilisation, and the copy has to say which of the two nulls it is.
 *
 * `utilisationPercent` is **null, not zero**, when allocated capacity is zero (017 D-i): a person
 * nobody has allocated has no utilisation, and `0%` would say they are idle when the true statement
 * is that nobody has planned their time. The two read differently here, and they have to — one is
 * a person with nothing to do and the other is a plan nobody made.
 */
export function utilisationLabel(percent: number | null | undefined): string {
  return percent === null || percent === undefined ? 'not allocated' : `${percent}%`;
}

/**
 * The denominator, said out loud.
 *
 * Every other time figure in this product names its spelling — `epic.html`'s *"approved hours
 * only"*, `timesheets.html`'s *"everything in it, whatever anybody has decided about it"* — and one
 * that did not would be the first. Approved rather than pending because Finance holds
 * `staffing:directory` for exactly this figure, and hours nobody has accepted are not yet a fact
 * about the money (017 D-i).
 */
export const UTILISATION_BASIS = 'approved hours ÷ allocated capacity';

/**
 * The allocation percentage as it reads on a row label — and it is a **mean over the window**.
 *
 * Named `mean` in the copy nowhere and named here instead, because the number the reader acts on is
 * the peak: `allocatedPercent` averages the window, so a person at 200% for one week of twelve
 * reads as 17% and is still over-allocated. {@link overAllocationLabel} is the one that flags.
 */
export function allocationLabel(percent: number): string {
  return `${percent}%`;
}

/**
 * The over-allocation badge's text, or `null` when there is nothing to flag.
 *
 * **The flag fires at 101 and above, and this function does not decide that** — `overAllocated` is
 * served, computed from the peak *day* rather than a month's sum (017 D-e), and re-deriving it from
 * `peakPercent > 100` here would put a second rule in the client that could disagree with the
 * server's on the day the threshold moves. The percentage shown is the peak, because that is the
 * day the reader would have to fix.
 */
export function overAllocationLabel(person: {
  overAllocated: boolean;
  peakPercent: number;
}): string | null {
  return person.overAllocated ? `${person.peakPercent}%` : null;
}

/**
 * How much of this person is unclaimed — `directory.html`'s `60% free` / `no free capacity`.
 *
 * `freePercent` is served as `100 - allocatedPercent` floored at zero, so a person at 130% reads
 * *no free capacity* rather than `-30%`, which would be arithmetic shown to somebody who wanted an
 * answer.
 */
export function freeCapacityLabel(freePercent: number): string {
  return freePercent === 0 ? 'no free capacity' : `${freePercent}% free`;
}

/** Whether a payload from either capacity endpoint has a profile behind its figures. */
export function hasProfile(subject: PersonCapacity | SelfCapacity): boolean {
  return 'hasProfile' in subject ? subject.hasProfile : subject.profile !== null;
}
