/**
 * A file a comment names must exist.
 *
 * ## Why this is a check and not a habit
 *
 * §205 opened by looking for one thing and finding five: `writeConformance.test.ts`,
 * ~~conformance.test.ts~~, `problemShape.test.ts`, ~~idempotency.test.ts~~ and ~~schemas.ts~~ were
 * named by eleven comments across `packages/` and **not one of them was a file**. (Two are files now
 * and three are struck, which is the notation below doing its own job: the plain ones were written,
 * the struck ones named a check that already existed under another name or a decision nobody had
 * recorded.) They are not typos. Each
 * was written at the moment the author knew what the check should be, and each then stood in for it:
 * `commands.ts` said *"the list is not decoration — `writeConformance.test.ts` walks it"* for
 * sixty-five sections while nothing walked it from that direction, and `tool.ts` justified
 * hand-written input schemas by pointing at a ~~schemas.ts~~ that had never been created.
 *
 * That is this repository's most-paid-for shape arriving through a new door. §152 found a callerless
 * method whose javadoc was evidence nothing had checked; §160 found four sites giving *"there is no
 * adapter"* as a live reason for a port that had existed since §125; §165 found three arguments
 * resting on a module *"that does not exist"* while it was 119 files. In every one of those the
 * prose was load-bearing and wrong, and in every one it was believed because prose is what a reader
 * reaches first. A filename is the cheapest possible instance and the only one a machine can settle
 * outright: the claim is `this path exists`, and the filesystem answers.
 *
 * ## The corpus is both TypeScript stacks, from one place
 *
 * `frontend/src` is scanned here rather than by a copy over there, and that is deliberate. The
 * walker in `contractBodies.ts` had to be copied and paired because a Vite `?raw` import and two npm
 * roots make an import impossible **in production code**; this is a test reading files off disk, the
 * repository is checked out whole by both CI jobs, and a second copy of a twenty-line scanner is the
 * §196 shape the pairing test exists to punish. So: one check, one corpus, no pair.
 *
 * What that costs is stated plainly — **a broken citation in `frontend/` reddens the `packages`
 * job.** That is the wrong job for the blame to land in, and it is still better than two scanners
 * that agree until somebody teaches one about a new extension.
 *
 * The two generated files are excluded: `schema.ts` and `schema.d.ts` carry `openapi.yaml`'s own
 * prose, which cites files freely and is not this corpus's to police. `backend/` is excluded too,
 * for a different reason — javadoc cites with `{@link}` and `{@code}`, so the same question there is
 * a different scanner and belongs to a rule of its own.
 *
 * ## The escape hatch is the repository's own notation
 *
 * A superseded claim in this repo is struck through — `~~x~~ → y` — and a struck-through filename is
 * therefore already a statement that it does *not* exist. So strikethrough spans are removed before
 * scanning, and no separate opt-out is needed for the commonest case.
 *
 * **That exclusion is where this check can quietly break, and §156 already paid for it**: a fixture's
 * unterminated `UPDATE` swallowed a whole file's literals and hid two members of the population the
 * check was written to find. An opening marker with no closing one pairs with the next marker in the
 * file instead, which inverts which text is excused and, at the limit, excuses the file.
 *
 * The guard is on that **symptom** rather than on marker parity, and the difference is not
 * fastidiousness: a parity rule cannot be satisfied by a file that writes *about* the notation an odd
 * number of times — this one — so the check would have had to exempt itself from its own corpus,
 * which is §154's defect verbatim. What is asserted instead is that the excused spans cover a small
 * share of a file. A file whose comments are two-fifths superseded text is either mid-rewrite or has
 * a marker missing, and both are worth a build failure.
 *
 * {@link CITED_BUT_ABSENT_BY_DECISION} is for what strikethrough cannot say: a present-tense
 * statement that this project *has no such file*, and a citation into somebody else's source tree.
 * It is pinned both ways (§162) — an entry naming a file that now exists fails, because a
 * one-directional ignore list cannot report a member that stopped needing to be on it.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');

/** Directories a scan must never descend into — build output, dependencies, VCS. */
const PRUNED = new Set([
  'node_modules', 'dist', 'build', '.git', '.gradle', '.idea', 'coverage', '.vite',
]);

/** Where comments are read from. Generated files are not in it — see the header. */
const CORPUS: { root: string; extensions: string[]; skip: string[] }[] = [
  {
    root: 'packages',
    extensions: ['.ts'],
    skip: ['packages/client/src/schema.ts'],
  },
  {
    root: 'frontend/src',
    extensions: ['.ts', '.tsx'],
    skip: ['frontend/src/api/schema.d.ts'],
  },
];

/**
 * A backtick-quoted path with a known extension.
 *
 * Backticks only, and that is what keeps the reader honest without a whitelist: this repository
 * writes every identifier and path in a comment as code, so an unquoted filename in a sentence is
 * prose about a hypothetical and quoting it is the act of claiming the file is real.
 */
const CITATION = /`([A-Za-z0-9_][A-Za-z0-9_./-]*\.(?:ts|tsx|yaml|yml|json|sql|java|md|css|html))`/g;

/** A superseded claim, in this repo's notation. Non-greedy and multi-line — a span often is. */
const STRUCK = /~~[\s\S]*?~~/g;

/**
 * Citations that name no file in this repository on purpose, with the reason.
 *
 * Two kinds, and neither is something strikethrough could express. The first three are
 * present-tense statements that this project **has no such file** — the point of the sentence is the
 * absence, so striking the name would say the opposite. The fourth names a file in a dependency's
 * own source. The fifth is a file this repository deleted and whose deletion is the subject.
 */
const CITED_BUT_ABSENT_BY_DECISION: Record<string, string> = {
  'error.tsx':
    'A Next.js App Router file. `ErrorBoundaries.tsx` names it to say this is a Vite SPA and the '
    + 'framework tier does not exist here, so the boundaries are the whole of it.',
  'global-error.tsx':
    'The same sentence, one tier up — see `error.tsx`.',
  'middleware.ts':
    'Next.js again. `RequireAuth.tsx` names it because a route guard is the thing a Next app would '
    + 'put there, and this app has no server to put it on.',
  'queryObserver.ts':
    'TanStack Query\'s own source, cited in `api/queries/cases.ts` for where the placeholder '
    + 'identity comparison lives. A dependency\'s file, not this repository\'s.',
  'src/mocks/handlers/injection.ts':
    'Deleted with the whole mock layer on 2026-08-06, and `lib/devFaults.ts` names it because the '
    + 'deletion is the point: the fault seam moved in front of `fetch` so it faults the real backend.',
};

describe('a file a comment names', () => {
  const files = CORPUS.flatMap((entry) =>
    walk(join(REPO, entry.root))
      .map((absolute) => relative(REPO, absolute))
      .filter((path) => entry.extensions.some((extension) => path.endsWith(extension)))
      .filter((path) => !entry.skip.includes(path)));

  const everything = new Set(walk(REPO).map((absolute) => relative(REPO, absolute)));

  const cited = files.flatMap((path) => {
    const text = readFileSync(join(REPO, path), 'utf8');
    const struck = [...text.matchAll(STRUCK)].map((match) => ({
      from: match.index,
      to: match.index + match[0].length,
    }));

    /*
      §156's defect, pre-empted — see the header. An unterminated marker excuses text nobody meant to
      excuse, and a corpus quietly missing half its citations reads exactly like a corpus with
      nothing wrong in it.
    */
    const excused = struck.reduce((total, span) => total + (span.to - span.from), 0);
    expect(
      excused / Math.max(text.length, 1),
      `${path}: superseded spans cover ${Math.round((excused / Math.max(text.length, 1)) * 100)}% `
        + 'of this file, which normally means an opening strikethrough marker has no closing one and '
        + 'has paired with the next span instead',
    ).toBeLessThan(0.4);

    return [...text.matchAll(CITATION)]
      .filter((match) => !struck.some((span) => match.index >= span.from && match.index < span.to))
      .map((match) => ({ path, name: match[1] ?? '' }));
  });

  it('reads a corpus, and finds citations in it', () => {
    // Two vacuity guards, because either one alone can be satisfied by a scan that found nothing.
    // A regex that stopped matching and a walk that returned nothing look identical from the
    // outside, and both look identical to a repository in which every citation resolves.
    expect(files.length).toBeGreaterThan(300);
    expect(new Set(cited.map((entry) => entry.name)).size).toBeGreaterThan(30);
  });

  /*
    One assertion over the whole population rather than `it.each` per name, which is the shape the
    conformance checks use. The difference is what a failure is *about*: there, each arm is a
    separate subject and a reader wants to know which one broke; here the names are one list and the
    useful failure is the whole set that stopped resolving — a rename typically breaks several at
    once, and 139 green test names for 139 filenames is noise in every run that passes.
  */
  it('resolves every one of them', () => {
    const names = [...new Set(cited.map((entry) => entry.name))].sort();
    const unresolved = names
      .filter((name) => CITED_BUT_ABSENT_BY_DECISION[name] === undefined)
      .filter((name) => ![...everything].some((path) => path === name || path.endsWith(`/${name}`)))
      .map((name) => `  \`${name}\` — named by `
        + [...new Set(cited.filter((entry) => entry.name === name).map((entry) => entry.path))].join(', '));

    expect(unresolved.join('\n'), `${unresolved.length} of ${names.length} cited files do not exist:\n\n`
      + `${unresolved.join('\n')}\n\n`
      + 'Either the citation is stale — the check it points at exists under another name, or the file '
      + 'was renamed and this comment was not — or it names a check nobody wrote, which is the '
      + 'population §205 was opened by. If the absence is the point of the sentence, strike the name '
      + 'through (`~~x~~ → y`) or put it in CITED_BUT_ABSENT_BY_DECISION with the reason.')
      .toBe('');
  });

  it('excuses nothing that has stopped needing it', () => {
    // §162's rule. A one-directional ignore list cannot report a member that came back.
    for (const [name, reason] of Object.entries(CITED_BUT_ABSENT_BY_DECISION)) {
      expect(reason.length, `${name} is excused without a reason`).toBeGreaterThan(40);
      expect(
        [...everything].some((path) => path === name || path.endsWith(`/${name}`)),
        `${name} is excused as absent and now exists — take the entry out`,
      ).toBe(false);
      expect(
        cited.some((entry) => entry.name === name),
        `${name} is excused and nothing cites it any more — take the entry out`,
      ).toBe(true);
    }
  });
});

function walk(root: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (PRUNED.has(entry.name)) continue;
    const path = join(root, entry.name);
    if (entry.isDirectory()) found.push(...walk(path));
    else if (entry.isFile()) found.push(path);
  }
  return found;
}
