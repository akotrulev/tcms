/**
 * One canonical format per data type, defined once (ui-rules §1).
 *
 * Every ad-hoc `toLocaleString()` or `` `${n}%` `` in a component is where a deviation
 * lives, so there are none: pages import from here. Timestamps are always labeled with
 * their zone — mixing unlabeled UTC, local, and relative age is a correctness hazard, not
 * a cosmetic one, because "failed at 02:14" means different things to different readers.
 */

const DATE = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  timeZone: 'UTC',
});

const DATE_TIME = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
  timeZone: 'UTC',
});

const DATE_TIME_SECONDS = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
  timeZone: 'UTC',
});

const TIME_OF_DAY = new Intl.DateTimeFormat('en-CA', {
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
  timeZone: 'UTC',
});

const EM_DASH = '—';

/**
 * `14:02 UTC`. For the staleness marker on a region whose refresh failed — the question there
 * is "how old is what I'm looking at", and the date is almost always today.
 *
 * Takes an epoch millisecond value rather than an ISO string because its caller is TanStack
 * Query's `dataUpdatedAt`, which is a number.
 */
export function formatTimeOfDay(epochMs: number | null | undefined): string {
  if (!epochMs) return EM_DASH;
  return `${TIME_OF_DAY.format(new Date(epochMs))} UTC`;
}

/** `2026-07-30`. Dates only — no time, so no zone label is needed. */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return EM_DASH;
  return DATE.format(new Date(iso));
}

/**
 * `2026-07-30 02:14 UTC`. The zone suffix is not optional: a run that failed overnight is
 * read by people in several places, and an unlabeled wall-clock time is ambiguous.
 */
export function formatDateTime(iso: string | null | undefined, seconds = false): string {
  if (!iso) return EM_DASH;
  const fmt = seconds ? DATE_TIME_SECONDS : DATE_TIME;
  return `${fmt.format(new Date(iso)).replace(',', '')} UTC`;
}

/**
 * Relative age, for "last executed" columns where recency is the question and the exact
 * instant is not. Always paired with the absolute time in a tooltip at the call site.
 */
export function formatAge(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return 'never';
  const ms = now - new Date(iso).getTime();
  if (ms < 0) return 'just now';

  const minutes = Math.floor(ms / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;

  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;

  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;

  const months = Math.floor(days / 30);
  if (months < 12) return `${months}mo ago`;

  return `${Math.floor(days / 365)}y ago`;
}

/**
 * How old the *arithmetic* on a screen is — the sentence for `computedAt` (§101).
 *
 * Here rather than in the two pages that render it, per `CLAUDE.md`: new display logic goes in the
 * shared layer, and a second implementation of this is how two screens come to word the same fact
 * differently.
 *
 * **Null is a positive statement and gets a positive sentence.** It means the server computed these
 * numbers from the executions themselves on this request — the `WORST` / `BEST` / `ALL` policies,
 * and any plan or suite whose rollup has not been written yet — so it is *more* current than a
 * timestamp, not less. Rendering it as "unknown", or rendering nothing at all, would make the
 * freshest possible answer look like a missing one.
 *
 * Deliberately NOT merged with `PlanStatistics.stale`, which is a different claim about a different
 * subject: `stale` says nothing has been *executed* in fourteen days, this says how old the *sums*
 * are. A plan executed an hour ago and rolled up ten minutes ago is fresh by one measure and behind
 * by the other, and collapsing them into one signal is §65's two-contradictory-percentages defect
 * with the contradiction hidden.
 */
export function formatComputedAt(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return 'Computed now';
  return `Figures as of ${formatDateTime(iso)} (${formatAge(iso, now)})`;
}

/** `87.4%` — one decimal, one spacing rule, everywhere. */
export function formatPercent(value: number | null | undefined, decimals = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return EM_DASH;
  return `${value.toFixed(decimals)}%`;
}

/**
 * Takes a 0–1 ratio (as the API models pass rate and coverage) and renders a percentage.
 * Kept separate from `formatPercent` so the unit is explicit at the call site — mixing
 * 0.89 and 89 into one helper is how a report ends up showing "0.9%".
 */
export function formatRatio(ratio: number | null | undefined, decimals = 1): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) return EM_DASH;
  return formatPercent(ratio * 100, decimals);
}

/** Counts are the unit of truth in this app; percentages are derived for reading. */
export function percentOf(part: number, whole: number): number | null {
  if (whole <= 0) return null;
  return (part / whole) * 100;
}

/** `4m 20s` / `1.4s`. Execution durations span three orders of magnitude. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return EM_DASH;
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;

  const totalSeconds = Math.round(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return seconds ? `${minutes}m ${seconds}s` : `${minutes}m`;

  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}

/** Estimated durations are authored in seconds, not milliseconds. */
export function formatSeconds(seconds: number | null | undefined): string {
  return seconds === null || seconds === undefined ? EM_DASH : formatDuration(seconds * 1000);
}

/**
 * The stopwatch boundary: seconds on screen, milliseconds on the wire.
 *
 * The column is milliseconds because that is what a JUnit/TestNG/Cucumber report reports, and a
 * tester timing their own run does not think in milliseconds. Both halves live here rather than on
 * the one screen that needs them today, because the second implementation of a unit conversion is
 * how two screens come to disagree about the same number.
 *
 * **An empty box is `null` and a typed `0` is zero** — the two are different statements ("I did not
 * time this" against "it was instant"), and a falsy check collapses them. `parseDurationSeconds`
 * answers `undefined` for text that is not a duration at all, which is the caller's cue to refuse
 * the save rather than silently clear a value.
 */
export function parseDurationSeconds(text: string): number | null | undefined {
  const trimmed = text.trim();
  if (trimmed === '') return null;
  const seconds = Number(trimmed);
  if (!Number.isFinite(seconds) || seconds < 0) return undefined;
  return Math.round(seconds * 1000);
}

/** The inverse, for seeding the control from what the row already carries. */
export function durationSecondsField(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '';
  return String(Math.round(ms / 100) / 10);
}

/** `842 kB`. Decimal units, matching what operating systems show for downloads. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return EM_DASH;
  if (bytes < 1000) return `${bytes} B`;
  const units = ['kB', 'MB', 'GB'];
  let value = bytes / 1000;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

/** Integers in grid cells, right-aligned by the column definition. */
export function formatCount(n: number | null | undefined): string {
  return n === null || n === undefined ? EM_DASH : n.toLocaleString('en-US');
}

/**
 * `1 result` / `3 results` / `4 switches`.
 *
 * The sibilant rule is here because the naive `+ 's'` produced **"4 switchs"** in `lib/flake.ts`'s
 * own first run — every noun that module pluralises happened to be regular except the one that
 * carries the headline number. Not a general inflector: it covers the endings English adds `es` to,
 * and a noun that needs more than that should be passed already-plural rather than handled here.
 *
 * **It lived private in `lib/flake.ts` until §194**, which is where it was written and where it was
 * the only consumer. A second consumer is the moment this file's own header applies — one canonical
 * format per data type, defined once — so it moved here rather than being copied, `formatCount`'s
 * neighbour for the same reason: both turn a number into the words beside it.
 */
export function plural(n: number, noun: string): string {
  if (n === 1) return `1 ${noun}`;
  return `${n} ${noun}${/(?:s|x|z|ch|sh)$/.test(noun) ? 'es' : 's'}`;
}

/**
 * Releases must sort by precedence, never lexicographically — `4.10.0` comes *after*
 * `4.9.0`, and a string compare puts it before. Same class of bug as ordering by case key.
 */
export function compareReleaseVersions(a: string, b: string): number {
  const parse = (v: string) => v.split(/[.\-+]/).map((p) => (/^\d+$/.test(p) ? Number(p) : p));
  const left = parse(a);
  const right = parse(b);
  for (let i = 0; i < Math.max(left.length, right.length); i += 1) {
    const l = left[i] ?? 0;
    const r = right[i] ?? 0;
    if (l === r) continue;
    if (typeof l === 'number' && typeof r === 'number') return l - r;
    return String(l).localeCompare(String(r));
  }
  return 0;
}

/**
 * Display keys in their natural order — `TC-9` before `TC-10`, which a string compare inverts.
 *
 * `compareReleaseVersions` above already named this "the same class of bug as ordering by case
 * key", and §100 found the case doing it: `RunDetailPage`'s tiebreaker was a bare
 * `caseKey.localeCompare`, so a run whose keys crossed a power of ten listed TC-10 through TC-19
 * ahead of TC-9. Invisible on a fixture set that never crosses one, and read as data corruption
 * by a tester on a real run scrolling for the case they just marked.
 *
 * `numeric: true` is the whole fix and it is a collator option rather than arithmetic: keys are
 * `PREFIX-N`, the prefix is per-project and not always two letters, and splitting on the dash
 * would need a second rule for the day a project uses one in its prefix.
 */
export function compareCaseKeys(a: string, b: string): number {
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
}

export { EM_DASH };
