import type { ReleaseSummary } from '../../api/types';
import { compareReleaseVersions } from '../../lib/format';

/**
 * Which release are we in — one rule, because three screens ask it.
 *
 * `project-overview.html`'s **Next release** panel, its two release KPI tiles and the calendar all
 * need the same answer, and a later module asking it makes four. Two lines that three screens each
 * guessed at is two lines that three screens eventually disagree about, on a page where the
 * disagreement is two tiles side by side naming different versions.
 *
 * <h2>The rule</h2>
 *
 * The earliest `PLANNED` release by target date; among those with **no** target date, the lowest
 * version. A `RELEASED` release is never "next" — it shipped, and the question is what ships now.
 *
 * <h2>A dated release always outranks an undated one</h2>
 *
 * Not because a date is more important, but because "next" is a question about *when*: a version
 * with a target date has an answer and one without does not, so an undated `3.0` cannot come before
 * a `2.4` due on Friday. Undated releases are ordered among themselves by
 * {@link compareReleaseVersions} — the same precedence the server's `release_sort_key` encodes, and
 * the reason this compares `version` rather than a sort key: **`sortKey` is not on the wire.** It is
 * a server-side ordering column with two deliberate `READERLESS_BY_DECISION` entries (013 D-a), so
 * a client that wanted it would be asking for the column to be published. Comparing the version
 * with the same precedence rule is the same order without the contract change.
 */
export function nextRelease(releases: readonly ReleaseSummary[]): ReleaseSummary | null {
  const planned = releases.filter((release) => release.status === 'PLANNED');
  if (planned.length === 0) return null;

  const dated = planned
    .filter((release) => release.targetDate !== null)
    .sort((a, b) => (a.targetDate ?? '').localeCompare(b.targetDate ?? ''));
  if (dated.length > 0) return dated[0];

  return [...planned].sort((a, b) => compareReleaseVersions(a.version, b.version))[0];
}
