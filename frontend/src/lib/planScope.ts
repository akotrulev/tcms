import { compareCaseKeys } from './format';

/**
 * Turning **N per-reference reads into one list of cases**, for the plan's Test cases tab (§103).
 *
 * ## Why this is a union and not one request
 *
 * A group's references *widen* it: a section naming `Suite Payments` and `Tag smoke` wants every
 * case in either. `GET /cases` narrows — its `tag` parameter repeats and **ANDs** (`openapi.yaml`,
 * and the contract says so in as many words), and it takes one `suiteId`. So one request carrying
 * every reference answers the intersection, which is the opposite question and is usually empty.
 *
 * One read per reference, unioned here. That also makes each arm's failure and each arm's
 * truncation attributable to the reference that caused it, which a single blended read could not be.
 *
 * ## Why the server has to do the resolving
 *
 * A suite's membership has been **filed ∪ added ∪ tagged** since §98, so `case.suiteId === x` is the
 * filed arm alone. §96 shipped exactly that and produced a tag-defined suite showing a count of 2
 * over a grid of 0 rows. Nothing in this file filters by `suiteId`; it only merges what the server
 * already resolved.
 *
 * ## Why there is no count here
 *
 * `resolveReferences` returns rows and never a total. The group already carries the server's
 * `caseCount` over *all* of its references — including the cases named directly, which this list
 * deliberately does not repeat — so a second number computed here would sit beside it, mean
 * something different, and be read as a contradiction (§52's two-numbers-on-one-screen, §65's two
 * percentages). The rows are the evidence; the count stays the server's.
 */

/** The subset of a case this list renders. Structural, so both the contract type and a test fixture fit. */
export interface ScopeCase {
  id: string;
  key: string;
  title: string;
}

/** One reference's read: what it is called on screen, what came back, and how much there was. */
export interface ReferenceRead {
  /** The reference as the reader sees it — `Suite Payments`, `Tag smoke`. */
  label: string;
  cases: ScopeCase[];
  /** The server's `total` for that read, which is not `cases.length` when the page truncated it. */
  total: number;
}

export interface ResolvedCase extends ScopeCase {
  /**
   * Which references brought this case in, in the order the references are listed.
   *
   * A list, not a single label: a case filed in `Payments` and tagged `smoke` arrives through both,
   * and picking one to show would make removing the *other* reference look like it should have
   * dropped the row.
   */
  via: string[];
}

export interface Truncation {
  label: string;
  shown: number;
  total: number;
}

export interface ResolvedScope {
  cases: ResolvedCase[];
  /**
   * The references whose read did not fit on one page. Stated rather than swallowed: a list that
   * silently stops at 200 is indistinguishable from a suite with 200 cases, and this list's whole
   * job is to be the answer to "what is actually in here".
   */
  truncated: Truncation[];
}

/**
 * Unions the reads, dedupes by case id, and sorts by display key **numerically**.
 *
 * `compareCaseKeys`, never `localeCompare` — `TC-10` sorts ahead of `TC-9` otherwise, invisible on
 * any fixture under ten cases and wrong on every real project (the class `RunDetailPage` shipped).
 */
export function resolveReferences(reads: ReferenceRead[]): ResolvedScope {
  const byId = new Map<string, ResolvedCase>();
  const truncated: Truncation[] = [];

  for (const read of reads) {
    if (read.total > read.cases.length) {
      truncated.push({ label: read.label, shown: read.cases.length, total: read.total });
    }
    for (const row of read.cases) {
      const existing = byId.get(row.id);
      if (existing) {
        // Guarded because two references CAN carry the same label — a section referencing the same
        // tag twice is refused by `addReference`, but a suite and a tag of the same name are not.
        if (!existing.via.includes(read.label)) existing.via.push(read.label);
      } else {
        byId.set(row.id, { id: row.id, key: row.key, title: row.title, via: [read.label] });
      }
    }
  }

  return {
    cases: [...byId.values()].sort((a, b) => compareCaseKeys(a.key, b.key)),
    truncated,
  };
}
