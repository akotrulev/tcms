import type { DateWindow } from '../releases/timelineScale';

/**
 * The window a set of people's tracks are drawn against, taken from the payload rather than a picker.
 *
 * `GET /staffing/people` and `GET /staffing/people/{userId}` both answer **without a `from`/`to`** —
 * unlike `GET /staffing/capacity` and `GET /projects/{id}/staffing`, which carry the window they
 * used — so when the URL names no range the only evidence of what the server defaulted to is the
 * spans it sent back. Widest of those, so a card whose allocation runs past another's is not clipped
 * by its neighbour's shorter one.
 *
 * Shared rather than private to a screen because the directory and the person page draw the same
 * bars from the same shape: two spellings of "widest span" would let one screen clip a bar the other
 * shows in full, and a reader comparing the two would have no way to tell which was lying.
 *
 * A person with no allocation and no absence contributes nothing, which is why the fallback matters:
 * a window of one day draws an empty track, which is the honest picture of *"nothing is planned"*
 * rather than a bar of arbitrary width.
 */
export interface SpannedPerson {
  allocations: readonly { startsOn: string; endsOn: string }[];
  absences: readonly { startsOn: string; endsOn: string }[];
}

export function windowOver(
  people: readonly SpannedPerson[],
  fallback: { from: string; to: string },
): DateWindow {
  const starts = people.flatMap((person) => [
    ...person.allocations.map((span) => span.startsOn),
    ...person.absences.map((absence) => absence.startsOn),
  ]);
  const ends = people.flatMap((person) => [
    ...person.allocations.map((span) => span.endsOn),
    ...person.absences.map((absence) => absence.endsOn),
  ]);

  return {
    from: starts.length === 0 ? fallback.from : starts.reduce((min, at) => (at < min ? at : min)),
    to: ends.length === 0 ? fallback.to : ends.reduce((max, at) => (at > max ? at : max)),
  };
}

/** Today as `YYYY-MM-DD` — the fallback both callers use when the URL names no range either. */
export function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}
